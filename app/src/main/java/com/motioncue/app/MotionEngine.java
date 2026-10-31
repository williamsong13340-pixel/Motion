package com.motioncue.app;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;

/**
 * 传感器融合：把手机测到的加速度 / 角速度换算成「相对屏幕的车辆运动」。
 *
 * 思路（与 iOS Vehicle Motion Cues / Android Motion Assist 一致，基于感觉冲突理论）：
 *  1. 用 GAME_ROTATION_VECTOR（陀螺仪 + 加速度计融合，不受车内磁场干扰）得到「竖直向上」方向 u。
 *  2. 「右方」「前方」直接取屏幕轴在水平面上的投影（屏幕上方对应前方，竖着拿时「往屏幕里」对应前方）。
 *     它们都在水平面内，所以加速度与它们点乘就自动去掉了重力，只剩车辆的水平加速度；
 *     结果就是车里「没固定的小球」的滑动方向在屏幕上的投影，屏幕朝上、竖直、朝下都正确。
 *  3. 角速度在 u 上的投影就是车辆的偏航角速度（转弯）。
 *  4. 低通滤波（晕动症敏感频段约 0.1–0.5Hz，车辆操控 < 1Hz）滤掉路面颠簸和手抖；
 *     有门限的慢速去偏（只吸收小的零漂/姿态误差，不吞掉真实的长时间加速），软死区，手持晃动抑制。
 *
 * 全部在独立的 HandlerThread 上运行，输出写入 volatile 字段，渲染线程直接读取。
 */
public final class MotionEngine implements SensorEventListener {

    public interface Listener {
        /** 每个加速度样本后在传感器线程回调 */
        void onMotionSample();
        /** 乘车状态改变，在传感器线程回调 */
        void onVehicleStateChanged(boolean inVehicle);
    }

    private static final int SAMPLE_US = 10_000; // 100Hz，足够覆盖 120Hz 屏幕（渲染层还有弹簧插值）

    // 滤波参数
    private static final float ACC_LP_HZ = 1.2f;
    // 偏航用更低的截止频率：车辆转弯持续数秒（< 0.3Hz），手的晃动多在 0.3–1Hz，滤掉后手持时圆点不会左右晃
    private static final float YAW_LP_HZ = 0.6f;
    /** 超前补偿（秒）：抵消低通滤波的延迟，让圆点和身体感受到的加减速更同步 */
    private static final float LEAD_S = 0.08f;
    /** 超前补偿只作用于这个频率以下（车辆操控），不放大路面颠簸 */
    private static final float LEAD_LP_HZ = 0.8f;
    /** 死区：路面颠簸的残余不显示，只显示真正的加减速 / 转弯（配合四次方的锐利拐点） */
    private static final float ACC_DEADZONE = 0.13f;  // m/s²
    private static final float YAW_DEADZONE = 0.04f;  // rad/s（城市转弯 0.2–0.5 rad/s 不受影响）
    /** 屏幕轴水平投影长度低于此值时，该方向的提示增益开始减小 */
    private static final float AXIS_MIN = 0.35f;

    // 去偏：只学习小偏差（传感器零漂、约 2° 以内的姿态误差），真实的持续加速/弯道不会被吸收
    private static final float ACC_BIAS_TAU = 30f;
    private static final float ACC_BIAS_GATE_LO = 0.20f, ACC_BIAS_GATE_HI = 0.35f;
    private static final float ACC_BIAS_MAX = 0.40f;
    private static final float YAW_BIAS_TAU = 30f;
    private static final float YAW_BIAS_GATE_LO = 0.02f, YAW_BIAS_GATE_HI = 0.05f;
    private static final float YAW_BIAS_MAX = 0.03f;

    private final SensorManager sm;
    private final VehicleDetector detector = new VehicleDetector();
    private Listener listener;

    private HandlerThread thread;
    private Handler handler;
    private final Sensor accSensor, gyroSensor, rotSensor, stepSensor;
    private boolean useStep;

    // 输出（渲染线程读取）：前后 / 左右加速度（m/s²，相对屏幕），偏航映射到屏幕 x 方向（rad/s）
    public volatile float outForward, outRight, outYaw;

    // 状态（仅传感器线程访问）
    private final float[] rotM = new float[9];
    private final float[] u = {0, 0, 1};        // 竖直向上方向（设备坐标）
    private boolean haveUp;
    private final float[] fwd = {0, 1, 0};      // 水平前方（设备坐标）
    private final float[] right = {1, 0, 0};    // 水平右方（设备坐标）
    private float yawToScreenX = 1f;            // 偏航 → 屏幕 x 方向的符号与增益
    private final float[] gyro = new float[3];
    private boolean haveGyro;
    private long lastAccNs, lastGyroNs;
    private volatile int displayRotation = Surface.ROTATION_0;

    private float f1, f2, r1, r2;       // 两级低通
    private float dF, dR, prevDF, prevDR;   // 超前补偿用的慢速副本及其上一值
    private float fBias, rBias;
    private float y1, y2, yBias;
    private float gyroLpX, gyroLpY, gyroLpZ, gyroHfPow;
    private float handling;
    private boolean lastVehicle;

    // 演示模式
    private volatile long demoUntilMs;
    private volatile long demoStartMs;

    public MotionEngine(SensorManager sm) {
        this.sm = sm;
        accSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor rot = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rot == null) rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        rotSensor = rot;
        stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
    }

    public static String describeSensors(SensorManager sm) {
        StringBuilder sb = new StringBuilder();
        append(sb, "加速度计", sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
        append(sb, "陀螺仪", sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE));
        append(sb, "游戏旋转矢量", sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR));
        append(sb, "计步器", sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, Sensor s) {
        sb.append(label).append("：");
        if (s == null) {
            sb.append("✗ 无\n");
        } else {
            sb.append("✓ ").append(s.getName());
            if (s.getMinDelay() > 0) {
                sb.append("（最高 ").append(Math.round(1_000_000f / s.getMinDelay())).append("Hz）");
            }
            sb.append('\n');
        }
    }

    public VehicleDetector detector() { return detector; }

    public void setListener(Listener l) { listener = l; }

    /** 计步器（区分走路和乘车）。引擎运行中也会立即注册 / 注销。 */
    public synchronized void setUseStepDetector(boolean use) {
        if (use == useStep) return;
        useStep = use;
        if (thread == null || stepSensor == null) return;
        if (use) sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
        else sm.unregisterListener(this, stepSensor);
    }

    public void setDisplayRotation(int rotation) { displayRotation = rotation; }

    public void startDemo(long durationMs) {
        long now = SystemClock.elapsedRealtime();
        demoStartMs = now;
        demoUntilMs = now + durationMs;
    }

    public boolean demoActive() { return SystemClock.elapsedRealtime() < demoUntilMs; }

    public synchronized void start() {
        if (thread != null) return;
        resetState();
        thread = new HandlerThread("motion-sensors", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        if (accSensor != null) sm.registerListener(this, accSensor, SAMPLE_US, handler);
        if (gyroSensor != null) sm.registerListener(this, gyroSensor, SAMPLE_US, handler);
        if (rotSensor != null) sm.registerListener(this, rotSensor, SAMPLE_US, handler);
        if (useStep && stepSensor != null) {
            sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
        }
        LiveStats.engineRunning = true;
    }

    public synchronized void stop() {
        if (thread == null) return;
        sm.unregisterListener(this);
        thread.quitSafely();
        thread = null;
        handler = null;
        outForward = outRight = outYaw = 0;
        LiveStats.engineRunning = false;
        LiveStats.accelForward = LiveStats.accelRight = LiveStats.yawRate = LiveStats.yawScreen = 0;
    }

    public boolean isRunning() { return thread != null; }

    /**
     * 每次启动都从干净的状态开始：不带上次熄屏前的滤波值和偏置（否则亮屏时圆点会跳一下，
     * 或者带着旧偏置漂很久）。手持抑制从 0 开始，提示在约 1 秒内淡入。
     */
    private void resetState() {
        haveUp = false;
        haveGyro = false;
        lastAccNs = 0;
        lastGyroNs = 0;
        f1 = f2 = r1 = r2 = 0;
        dF = dR = prevDF = prevDR = 0;
        y1 = y2 = 0;
        fBias = rBias = yBias = 0;
        gyroLpX = gyroLpY = gyroLpZ = 0;
        gyroHfPow = 0;
        handling = 0;
        fwd[0] = 0; fwd[1] = 1; fwd[2] = 0;
        right[0] = 1; right[1] = 0; right[2] = 0;
        yawToScreenX = 1f;
        outForward = outRight = outYaw = 0;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
            case Sensor.TYPE_ROTATION_VECTOR:
                SensorManager.getRotationMatrixFromVector(rotM, e.values);
                // R 把设备坐标变换到世界坐标；世界 Z(向上) 在设备坐标中 = R 的第三行
                if (Float.isNaN(rotM[6]) || Float.isNaN(rotM[7]) || Float.isNaN(rotM[8])) break;
                u[0] = rotM[6];
                u[1] = rotM[7];
                u[2] = rotM[8];
                haveUp = true;
                break;
            case Sensor.TYPE_GYROSCOPE:
                onGyro(e);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                onAccel(e);
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                detector.onStep(e.timestamp);
                break;
            default:
                break;
        }
    }

    private void onGyro(SensorEvent e) {
        float dt = lastGyroNs == 0 ? 0.01f : clamp((e.timestamp - lastGyroNs) * 1e-9f, 0.0005f, 0.1f);
        lastGyroNs = e.timestamp;
        gyro[0] = e.values[0];
        gyro[1] = e.values[1];
        gyro[2] = e.values[2];

        // 没有旋转矢量传感器时：陀螺仪积分维持竖直方向（互补滤波的预测步）
        if (rotSensor == null && haveUp) {
            // du/dt = -ω × u
            float cx = gyro[1] * u[2] - gyro[2] * u[1];
            float cy = gyro[2] * u[0] - gyro[0] * u[2];
            float cz = gyro[0] * u[1] - gyro[1] * u[0];
            u[0] -= cx * dt;
            u[1] -= cy * dt;
            u[2] -= cz * dt;
            if (!normalize(u)) haveUp = false;
        }

        // 手持晃动检测：高频角速度能量大 → 用户在拿手机/转手机，而不是车在转
        float a = alpha(dt, 2.0f);
        gyroLpX += (gyro[0] - gyroLpX) * a;
        gyroLpY += (gyro[1] - gyroLpY) * a;
        gyroLpZ += (gyro[2] - gyroLpZ) * a;
        float hx = gyro[0] - gyroLpX, hy = gyro[1] - gyroLpY, hz = gyro[2] - gyroLpZ;
        float p = hx * hx + hy * hy + hz * hz;
        gyroHfPow += (p - gyroHfPow) * alphaTau(dt, 0.25f);
        haveGyro = true;
    }

    private void onAccel(SensorEvent e) {
        final float ax = e.values[0], ay = e.values[1], az = e.values[2];
        float amag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        // 异常样本（全 0 / NaN / 自由落体）直接丢弃，避免把状态污染成 NaN
        if (!(amag > 0.5f) || amag > 200f) return;
        float dt = lastAccNs == 0 ? 0.01f : clamp((e.timestamp - lastAccNs) * 1e-9f, 0.0005f, 0.1f);
        lastAccNs = e.timestamp;

        if (!haveUp) {
            if (rotSensor != null) return; // 等第一帧旋转矢量
            u[0] = ax / amag; u[1] = ay / amag; u[2] = az / amag;
            haveUp = true;
        } else if (rotSensor == null) {
            // 互补滤波校正步：慢慢拉向加速度方向（时间常数 3 秒，车辆加速时不会把方向带偏太多）
            float k = haveGyro ? alphaTau(dt, 3f) : alphaTau(dt, 0.5f);
            u[0] += (ax / amag - u[0]) * k;
            u[1] += (ay / amag - u[1]) * k;
            u[2] += (az / amag - u[2]) * k;
            if (!normalize(u)) { haveUp = false; return; }
        }

        updateAxes();

        // 前方/右方都在水平面内，所以直接点乘就得到「去重力」后的水平加速度
        float aF = ax * fwd[0] + ay * fwd[1] + az * fwd[2];
        float aR = ax * right[0] + ay * right[1] + az * right[2];
        float yaw = haveGyro ? gyro[0] * u[0] + gyro[1] * u[1] + gyro[2] * u[2] : 0f;

        // ---- 两级低通 ----
        float la = alpha(dt, ACC_LP_HZ);
        f1 += (aF - f1) * la; f2 += (f1 - f2) * la;
        r1 += (aR - r1) * la; r2 += (r1 - r2) * la;
        float ya = alpha(dt, YAW_LP_HZ);
        y1 += (yaw - y1) * ya; y2 += (y1 - y2) * ya;

        // ---- 门限去偏：偏差超过门限（真实的加减速/转弯）就完全不学习，且偏置有上限 ----
        fBias = updateBias(fBias, f2, dt, ACC_BIAS_TAU, ACC_BIAS_GATE_LO, ACC_BIAS_GATE_HI, ACC_BIAS_MAX);
        rBias = updateBias(rBias, r2, dt, ACC_BIAS_TAU, ACC_BIAS_GATE_LO, ACC_BIAS_GATE_HI, ACC_BIAS_MAX);
        yBias = updateBias(yBias, y2, dt, YAW_BIAS_TAU, YAW_BIAS_GATE_LO, YAW_BIAS_GATE_HI, YAW_BIAS_MAX);
        float fwdA = f2 - fBias;
        float rightA = r2 - rBias;
        float yawR = y2 - yBias;

        // ---- 手持晃动抑制：快速压低，1 秒恢复 ----
        float hfRms = (float) Math.sqrt(gyroHfPow);
        // 两种「在动手机」：高频抖动（拿起、晃动），或平稳但很快的转动（翻转、转屏）——车辆本身的转动不超过约 0.6 rad/s
        float rot = (float) Math.sqrt(gyroLpX * gyroLpX + gyroLpY * gyroLpY + gyroLpZ * gyroLpZ);
        float targetH = Math.min(1f - smoothstep(0.5f, 1.5f, hfRms), 1f - smoothstep(0.7f, 1.3f, rot));
        handling += (targetH - handling) * (targetH < handling ? alphaTau(dt, 0.05f) : alphaTau(dt, 1.0f));
        if (handling < 0.3f) {
            // 正在动手机：偏航滤波器里积攒的是手的转动，不是车的转弯——逐渐清掉，免得抑制结束时被放出来
            float bleed = 1f - alphaTau(dt, 0.1f);
            y1 *= bleed;
            y2 *= bleed;
        }

        // 超前补偿：x + τ·d(x̃)/dt，其中 x̃ 是再经过 0.8Hz 低通的副本——只给车辆操控提速，不放大颠簸
        float kl = alpha(dt, LEAD_LP_HZ);
        dF += (fwdA - dF) * kl;
        dR += (rightA - dR) * kl;
        float fLead = fwdA + LEAD_S * (dF - prevDF) / dt;
        float rLead = rightA + LEAD_S * (dR - prevDR) / dt;
        prevDF = dF;
        prevDR = dR;

        float outF = sharpDeadzone(fLead, ACC_DEADZONE) * handling;
        float outR = sharpDeadzone(rLead, ACC_DEADZONE) * handling;
        float yawOut = softDeadzone(yawR, YAW_DEADZONE) * handling;   // 车辆偏航（+ = 左转）
        float outY = yawOut * yawToScreenX;                           // 映射到屏幕 x 方向

        if (Float.isNaN(outF) || Float.isNaN(outR) || Float.isNaN(outY)) {
            // 理论上不会发生；万一发生就整体复位，绝不让 NaN 进入渲染
            resetState();
            return;
        }

        // ---- 乘车检测 ----
        boolean veh = detector.onSample(e.timestamp, dt, amag, fwdA * handling, rightA * handling, yawOut);

        // ---- 演示模式：注入一段模拟的加速/刹车/转弯 ----
        long now = SystemClock.elapsedRealtime();
        if (now < demoUntilMs) {
            float t = (now - demoStartMs) / 1000f;
            outF = 2.2f * (float) Math.sin(2 * Math.PI * t / 4.0);
            // 左转（yaw > 0）对应向左的向心加速度（outR < 0），两者符号保持一致
            outR = 1.6f * (float) Math.sin(2 * Math.PI * t / 5.0 + 1.0);
            outY = -0.25f * (float) Math.sin(2 * Math.PI * t / 5.0 + 1.0);
            LiveStats.demoActive = true;
        } else {
            LiveStats.demoActive = false;
        }

        outForward = outF;
        outRight = outR;
        outYaw = outY;

        LiveStats.accelForward = outF;
        LiveStats.accelRight = outR;
        LiveStats.yawRate = yawOut;
        LiveStats.yawScreen = outY;
        LiveStats.handlingGain = handling;

        Listener l = listener;
        if (l != null) {
            l.onMotionSample();
            if (veh != lastVehicle) {
                lastVehicle = veh;
                l.onVehicleStateChanged(veh);
            }
        }
    }

    /**
     * 根据屏幕方向计算「右方」「前方」两个水平投影向量（设备坐标），以及偏航提示的方向。
     *
     * 两个轴都直接取屏幕轴在水平面上的投影（不靠与竖直方向做叉乘推出另一个轴）：
     * 提示 = 车里「没固定的小球」的滑动方向在屏幕上的投影，因此屏幕朝上、竖直、朝下（躺着举在脸上方）
     * 以及各种屏幕旋转都一致正确。
     *  - 右方 = 屏幕右方的水平投影；
     *  - 前方 = 屏幕上方的水平投影 + |屏幕上方的竖直分量| × 屏幕背面(-Z)的水平投影。
     *    手机竖着拿时屏幕上方几乎竖直，此时把「往屏幕里」当作前方（约定：前方对应屏幕上方）。
     * 轴接近水平时增益为 1；轴越接近竖直（无法显示水平运动），增益平滑降到 0。
     *
     * Display.getRotation() 是画面相对自然方向的旋转，与机身的物理旋转方向相反：
     * 机身逆时针转 90° → ROTATION_90，此时屏幕右方 = 设备 -Y，屏幕上方 = 设备 +X。
     */
    private void updateAxes() {
        float rsx, rsy, usx, usy;
        switch (displayRotation) {
            case Surface.ROTATION_90:  rsx = 0;  rsy = -1; usx = 1;  usy = 0;  break;
            case Surface.ROTATION_180: rsx = -1; rsy = 0;  usx = 0;  usy = -1; break;
            case Surface.ROTATION_270: rsx = 0;  rsy = 1;  usx = -1; usy = 0;  break;
            default:                   rsx = 1;  rsy = 0;  usx = 0;  usy = 1;  break;
        }

        // 屏幕右方的水平投影
        float d = rsx * u[0] + rsy * u[1];
        float rx = rsx - d * u[0], ry = rsy - d * u[1], rz = -d * u[2];
        // 屏幕上方的水平投影
        float du = usx * u[0] + usy * u[1];
        float ux = usx - du * u[0], uy = usy - du * u[1], uz = -du * u[2];
        // 屏幕背面 (-Z) 的水平投影
        float dz = -u[2];
        float zx = -dz * u[0], zy = -dz * u[1], zz = -1f - dz * u[2];
        float w = Math.abs(du);
        float fx = ux + w * zx, fy = uy + w * zy, fz = uz + w * zz;

        float rl = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        float rg = 1f / Math.max(rl, AXIS_MIN), fg = 1f / Math.max(fl, AXIS_MIN);
        right[0] = rx * rg; right[1] = ry * rg; right[2] = rz * rg;
        fwd[0] = fx * fg; fwd[1] = fy * fg; fwd[2] = fz * fg;

        // 偏航提示方向：假设车头 ≈ 屏幕前方。左转时车的「左方」= u × 前方，
        // 小球滑向车的右方 → 屏幕 x 分量 = -(左方 · 屏幕右方)。屏幕朝下时自动取反。
        // 屏幕明显朝下（举过头顶、躺着看）时，分不清头朝车头还是车尾，车头方向无法推断：
        // 此时关掉偏航提示（横向加速度提示是纯物理投影，任何姿势都正确，照常显示）
        float yawConf = smoothstep(-0.6f, -0.3f, u[2]) * Math.min(1f, fl / AXIS_MIN);
        if (rl > 1e-3f && fl > 1e-3f && yawConf > 0f) {
            float hx = fx / fl, hy = fy / fl, hz = fz / fl;
            float lx = u[1] * hz - u[2] * hy, ly = u[2] * hx - u[0] * hz, lz = u[0] * hy - u[1] * hx;
            yawToScreenX = -(lx * rx + ly * ry + lz * rz) / rl * Math.min(1f, rl / AXIS_MIN) * yawConf;
        } else {
            yawToScreenX = 0f;
        }
    }

    // ---------------- 工具函数 ----------------

    private static float updateBias(float bias, float x, float dt, float tau, float gateLo, float gateHi, float max) {
        float diff = x - bias;
        float w = 1f - smoothstep(gateLo, gateHi, Math.abs(diff));
        if (w > 0f) bias += diff * alphaTau(dt, tau) * w;
        return clamp(bias, -max, max);
    }

    /** 一阶低通系数（截止频率 hz） */
    static float alpha(float dt, float hz) {
        float rc = 1f / (2f * (float) Math.PI * hz);
        return dt / (rc + dt);
    }

    /** 一阶低通系数（时间常数 tau 秒） */
    static float alphaTau(float dt, float tau) {
        return dt / (tau + dt);
    }

    /** 平滑死区：小信号趋近 0，大信号几乎不变 */
    private static float softDeadzone(float x, float dz) {
        float x2 = x * x;
        return x * x2 / (x2 + dz * dz);
    }

    /**
     * 拐点更陡的平滑死区（四次方）：路面颠簸的残余（< 0.5·dz）几乎全滤掉，
     * 轻微但真实的加减速（> 2·dz）几乎原样保留——两者的大小很接近，需要更锐利的分界。
     */
    private static float sharpDeadzone(float x, float dz) {
        float x4 = x * x * x * x, d4 = dz * dz * dz * dz;
        return x * x4 / (x4 + d4);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** @return false 表示向量长度为 0 或 NaN，无法归一化 */
    private static boolean normalize(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (!(l > 1e-6f)) return false;
        v[0] /= l; v[1] /= l; v[2] /= l;
        return true;
    }
}
