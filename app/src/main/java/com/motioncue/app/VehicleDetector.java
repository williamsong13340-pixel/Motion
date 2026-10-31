package com.motioncue.app;

/**
 * 不依赖 Google Play 服务的乘车检测（国行 ColorOS 没有 GMS 的 Activity Recognition）。
 *
 * 用三个特征做启发式判断，并带有积分迟滞：
 *  - 振动：加速度幅值 3Hz 以上的能量（发动机/路面），乘车时有、桌面静置几乎没有；
 *  - 步行：1–3Hz 的周期性能量（步频约 1.6–2.2Hz）或计步器事件 → 一定不是在车上；
 *  - 车辆操控：低频水平加速度或偏航角速度（加减速、转弯）。
 *
 * 连续约 16 秒「像在车上」才进入乘车状态；在车上停车等红灯时分数缓慢下降（约 3 分钟才退出）；
 * 一旦检测到走路会很快退出。
 */
public final class VehicleDetector {
    private static final float ENTER = 8f;
    private static final float MAX = 30f;

    private float lp1, lp3;       // 幅值的 1Hz / 3Hz 低通
    private float vibPow, walkPow, motionPow, yawPow;
    private long lastStepNs = Long.MIN_VALUE / 2;
    private long lastEvalNs;
    private float score;
    private boolean inVehicle;
    private boolean init;

    public synchronized void onStep(long tNs) {
        lastStepNs = tNs;
        LiveStats.lastStepMs = System.currentTimeMillis();
    }

    /** 屏幕关闭一段时间后调用，按经过的时间衰减分数 */
    public synchronized void decayFor(float seconds) {
        score = Math.max(0f, score - 0.3f * seconds);
        if (score <= 0f) inVehicle = false;
        LiveStats.vehicleScore = score;
        LiveStats.inVehicle = inVehicle;
    }

    public synchronized boolean isInVehicle() { return inVehicle; }

    /** @return 当前是否判定为在车上 */
    public synchronized boolean onSample(long tNs, float dt, float amag, float aF, float aR, float yaw) {
        if (!init) {
            lp1 = lp3 = amag;
            lastEvalNs = tNs;
            init = true;
        }
        lp3 += (amag - lp3) * MotionEngine.alpha(dt, 3f);
        lp1 += (amag - lp1) * MotionEngine.alpha(dt, 1f);
        float vib = amag - lp3;          // > 3Hz
        float walk = lp3 - lp1;          // 1–3Hz
        float k2 = MotionEngine.alphaTau(dt, 2f);
        float k6 = MotionEngine.alphaTau(dt, 6f);
        vibPow += (vib * vib - vibPow) * k2;
        walkPow += (walk * walk - walkPow) * k2;
        motionPow += ((aF * aF + aR * aR) - motionPow) * k6;
        yawPow += (yaw * yaw - yawPow) * k6;

        if (tNs - lastEvalNs >= 500_000_000L) {
            float step = (tNs - lastEvalNs) * 1e-9f;
            lastEvalNs = tNs;
            evaluate(tNs, Math.min(step, 2f));
        }
        return inVehicle;
    }

    private void evaluate(long tNs, float step) {
        float vibRms = (float) Math.sqrt(vibPow);
        float walkRms = (float) Math.sqrt(walkPow);
        float motion = (float) Math.sqrt(motionPow);
        float yawRms = (float) Math.sqrt(yawPow);
        boolean recentStep = tNs - lastStepNs < 8_000_000_000L;

        boolean walking = recentStep || walkRms > 0.9f;
        boolean vibration = vibRms > 0.025f && vibRms < 1.5f;
        boolean maneuver = motion > 0.15f || yawRms > 0.05f;

        if (walking) {
            score -= 6f * step;
        } else if (vibration && maneuver) {
            score += 1f * step;
        } else if (vibration) {
            score += 0.1f * step;   // 匀速行驶：保持
        } else {
            score -= 0.15f * step;  // 静止
        }
        score = MotionEngine.clamp(score, 0f, MAX);
        if (!inVehicle && score >= ENTER) inVehicle = true;
        if (inVehicle && score <= 0f) inVehicle = false;

        LiveStats.vibRms = vibRms;
        LiveStats.walkRms = walkRms;
        LiveStats.motionRms = motion;
        LiveStats.vehicleScore = score;
        LiveStats.inVehicle = inVehicle;
    }
}
