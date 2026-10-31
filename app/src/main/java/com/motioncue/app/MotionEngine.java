package com.motioncue.app;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;

/**
 * 传感器融合：把手机测到的加速度 / 角速度换算成「相对屏幕的车辆运动」。
 *
 * 思路（与 iOS Vehicle Motion Cues / Android Motion Assist 一致，基于感觉冲突理论）：
 *  1. 用 GAME_ROTATION_VECTOR（陀螺仪 + 加速度计融合，不受车内磁场干扰）得到「竖直向上」方向 u。
 *  2. 把加速度投影到水平面，就自动去掉了重力，只剩车辆的水平加速度（加减速 + 转弯离心）。
 *  3. 用屏幕朝向定义「前方」f（屏幕上方/屏幕背面在水平面的投影）和「右方」r = f × u。
 *  4. 角速度在 u 上的投影就是车辆的偏航角速度（转弯）。
 *  5. 低通滤波（晕动症敏感频段约 0.1–0.5Hz，车辆操控 < 1Hz）滤掉路面颠簸和手抖；
 *     慢速去偏（自适应），软死区，手持晃动抑制。
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
    private static final float YAW_LP_HZ = 1.0f;
    private static final float ACC_BIAS_TAU = 20f;
    private static final float YAW_BIAS_TAU = 30f;
    private static final float ACC_DEADZONE = 0.07f;  // m/s²
    private static final float YAW_DEADZONE = 0.02f;  // rad/s

    private final SensorManager sm;
    private final VehicleDetector detector = new VehicleDetector();
    private Listener listener;

    private HandlerThread thread;
    private Handler handler;
    private Sensor accSensor, gyroSensor, rotSensor, stepSensor;
    private boolean useStep;

    // 输出（渲染线程读取）
    public volatile float outForward, outRight, outYaw;

    // 状态（仅传感器线程访问）
    private final float[] rotM = new float[9];
    private final float[] u = {0, 0, 1};        // 竖直向上方向（设备坐标）
    private boolean haveUp;
    private final float[] fwd = {0, 1, 0};
    private final float[] gyro = new float[3];
    private boolean haveGyro;
    private long lastAccNs, lastGyroNs;
    private volatile int displayRotation = Surface.ROTATION_0;

    private float f1, f2, r1, r2;       // 两级低通
    private float fBias, rBias;
    private float y1, y2, yBias;
    private float gyroLpX, gyroLpY, gyroLpZ, gyroHfPow;
    private float handling = 1f;
    private boolean lastVehicle;

    // 演示模式
    private volatile long demoUntilMs;
    private long demoStartMs;

    public MotionEngine(SensorManager sm) {
        this.sm = sm;
        accSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotSensor == null) rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
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

    public void setUseStepDetector(boolean use) { useStep = use; }

    public void setDisplayRotation(int rotation) { displayRotation = rotation; }

    public void startDemo(long durationMs) {
        demoStartMs = System.currentTimeMillis();
        demoUntilMs = demoStartMs + durationMs;
    }

    public boolean demoActive() { return System.currentTimeMillis() < demoUntilMs; }

    public synchronized void start() {
        if (thread != null) return;
        thread = new HandlerThread("motion-sensors", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        haveUp = false;
        haveGyro = false;
        lastAccNs = 0;
        lastGyroNs = 0;
        if (accSensor != null) sm.registerListener(this, accSensor, SAMPLE_US, handler);
        if (gyroSensor != null) sm.registerListener(this, gyroSensor, SAMPLE_US, handler);
        if (rotSensor != null) sm.registerListener(this, rotSensor, SAMPLE_US, handler);
        if (useStep && stepSensor != null) {
            sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
        }
    }

    public synchronized void stop() {
        if (thread == null) return;
        sm.unregisterListener(this);
        thread.quitSafely();
        thread = null;
        handler = null;
        outForward = outRight = outYaw = 0;
    }

    public boolean isRunning() { return thread != null; }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
            case Sensor.TYPE_ROTATION_VECTOR:
                SensorManager.getRotationMatrixFromVector(rotM, e.values);
                // R 把设备坐标变换到世界坐标；世界 Z(向上) 在设备坐标中 = R 的第三行
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
            normalize(u);
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
        float dt = lastAccNs == 0 ? 0.01f : clamp((e.timestamp - lastAccNs) * 1e-9f, 0.0005f, 0.1f);
        lastAccNs = e.timestamp;
        float amag = (float) Math.sqrt(ax * ax + ay * ay + az * az);

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
            normalize(u);
        }

        // ---- 屏幕坐标轴（设备坐标）随屏幕旋转变化 ----
        float upx, upy; // 屏幕「上」
        switch (displayRotation) {
            case Surface.ROTATION_90:  upx = -1; upy = 0; break;
            case Surface.ROTATION_180: upx = 0;  upy = -1; break;
            case Surface.ROTATION_270: upx = 1;  upy = 0; break;
            default:                   upx = 0;  upy = 1; break;
        }
        // 前方 = 水平化(屏幕上方) + 水平化(屏幕背面 -Z)。平放、斜拿、竖拿都成立。
        float d1 = upx * u[0] + upy * u[1];
        float hx = upx - d1 * u[0], hy = upy - d1 * u[1], hz = -d1 * u[2];
        float d2 = -u[2];
        hx += -d2 * u[0];
        hy += -d2 * u[1];
        hz += -1 - d2 * u[2];
        float hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
        if (hl > 0.15f) {
            fwd[0] = hx / hl; fwd[1] = hy / hl; fwd[2] = hz / hl;
        }
        // 右方 = 前方 × 上方
        float rx = fwd[1] * u[2] - fwd[2] * u[1];
        float ry = fwd[2] * u[0] - fwd[0] * u[2];
        float rz = fwd[0] * u[1] - fwd[1] * u[0];

        // 前方/右方都在水平面内，所以直接点乘就得到「去重力」后的水平加速度
        float aF = ax * fwd[0] + ay * fwd[1] + az * fwd[2];
        float aR = ax * rx + ay * ry + az * rz;
        float yaw = haveGyro ? gyro[0] * u[0] + gyro[1] * u[1] + gyro[2] * u[2] : 0f;

        // ---- 两级低通 ----
        float la = alpha(dt, ACC_LP_HZ);
        f1 += (aF - f1) * la; f2 += (f1 - f2) * la;
        r1 += (aR - r1) * la; r2 += (r1 - r2) * la;
        float ya = alpha(dt, YAW_LP_HZ);
        y1 += (yaw - y1) * ya; y2 += (y1 - y2) * ya;

        // ---- 自适应去偏：信号大时（真实的加减速/转弯）几乎不更新偏置 ----
        fBias += (f2 - fBias) * alphaTau(dt, ACC_BIAS_TAU) * adaptive(f2 - fBias, 0.6f);
        rBias += (r2 - rBias) * alphaTau(dt, ACC_BIAS_TAU) * adaptive(r2 - rBias, 0.6f);
        yBias += (y2 - yBias) * alphaTau(dt, YAW_BIAS_TAU) * adaptive(y2 - yBias, 0.08f);
        float fwdA = f2 - fBias;
        float rightA = r2 - rBias;
        float yawR = y2 - yBias;

        // ---- 手持晃动抑制：快速压低，1 秒恢复 ----
        float hfRms = (float) Math.sqrt(gyroHfPow);
        float targetH = 1f - smoothstep(0.35f, 1.2f, hfRms);
        handling += (targetH - handling) * (targetH < handling ? alphaTau(dt, 0.05f) : alphaTau(dt, 1.0f));

        float outF = softDeadzone(fwdA, ACC_DEADZONE) * handling;
        float outR = softDeadzone(rightA, ACC_DEADZONE) * handling;
        float outY = softDeadzone(yawR, YAW_DEADZONE) * handling;

        // ---- 乘车检测 ----
        boolean veh = detector.onSample(e.timestamp, dt, amag, fwdA * handling, rightA * handling, outY);

        // ---- 演示模式：注入一段模拟的加速/刹车/转弯 ----
        long now = System.currentTimeMillis();
        if (now < demoUntilMs) {
            float t = (now - demoStartMs) / 1000f;
            outF = 2.2f * (float) Math.sin(2 * Math.PI * t / 4.0);
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
        LiveStats.yawRate = outY;
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

    // ---------------- 工具函数 ----------------

    /** 一阶低通系数（截止频率 hz） */
    static float alpha(float dt, float hz) {
        float rc = 1f / (2f * (float) Math.PI * hz);
        return dt / (rc + dt);
    }

    /** 一阶低通系数（时间常数 tau 秒） */
    static float alphaTau(float dt, float tau) {
        return dt / (tau + dt);
    }

    /** 差值越大，更新越慢 */
    private static float adaptive(float diff, float scale) {
        float q = diff / scale;
        return 1f / (1f + q * q * q * q);
    }

    /** 平滑死区：小信号趋近 0，大信号几乎不变 */
    private static float softDeadzone(float x, float dz) {
        float x2 = x * x;
        return x * x2 / (x2 + dz * dz);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void normalize(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l > 1e-6f) { v[0] /= l; v[1] /= l; v[2] /= l; }
    }
}
