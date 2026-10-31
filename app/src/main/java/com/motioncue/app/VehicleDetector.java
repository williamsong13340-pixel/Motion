package com.motioncue.app;

/**
 * 不依赖 Google Play 服务的乘车检测（国行 ColorOS 没有 GMS 的 Activity Recognition）。
 *
 * 特征：
 *  - 振动：加速度幅值 3Hz 以上的能量（发动机/路面）；
 *  - 步行：计步器事件，或 1–3Hz 频段里「周期稳定」的能量（步频 1.3–2.5Hz，自相关峰明显），
 *    或者该频段能量很大。注意 1–3Hz 带通（两个一阶低通相减）的增益只有约 0.5，阈值已按此折算；
 *  - 车辆操控：低频水平加速度或偏航角速度（加减速、转弯）。
 *
 * 迟滞积分：
 *  - 必须「振动 + 操控」同时出现才会累积进入（约 8 秒）。只有振动（例如手持时的手抖、点屏幕）不会进入；
 *  - 已在车上时，匀速行驶（只有振动）保持分数，停车等红灯缓慢下降（约 3 分钟才退出）；
 *  - 检测到走路会很快退出。
 */
public final class VehicleDetector {
    private static final float ENTER = 8f;
    private static final float MAX = 30f;

    // 1–3Hz 频段信号降采样到 25Hz，保留 4 秒做自相关
    private static final float DECIM_DT = 0.04f;
    private static final int AC_N = 100;
    private static final int LAG_MIN = 10;   // 0.40 s → 2.5 Hz
    private static final int LAG_MAX = 19;   // 0.76 s → 1.3 Hz

    private float lp1, lp3;       // 幅值的 1Hz / 3Hz 低通
    private float vibPow, walkPow, motionPow, yawPow;
    private long lastStepNs = Long.MIN_VALUE / 2;
    private long lastEvalNs;
    private float score;
    private boolean inVehicle;
    private boolean init;

    private final float[] ring = new float[AC_N];
    private int ringPos, ringCount;
    private float decimAcc, decimSum;
    private int decimCnt;
    private int periodicStreak;

    public synchronized void onStep(long tNs) {
        lastStepNs = tNs;
        LiveStats.lastStepMs = System.currentTimeMillis();
    }

    /** 屏幕关闭一段时间后调用，按经过的时间衰减分数 */
    public synchronized void decayFor(float seconds) {
        score = Math.max(0f, score - 0.15f * seconds);
        if (score <= 0f) inVehicle = false;
        // 熄屏期间的信号已经过时
        ringCount = 0;
        periodicStreak = 0;
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
        float walk = lp3 - lp1;          // 1–3Hz（增益约 0.5）
        float k2 = MotionEngine.alphaTau(dt, 2f);
        float k6 = MotionEngine.alphaTau(dt, 6f);
        vibPow += (vib * vib - vibPow) * k2;
        walkPow += (walk * walk - walkPow) * k2;
        motionPow += ((aF * aF + aR * aR) - motionPow) * k6;
        yawPow += (yaw * yaw - yawPow) * k6;

        // 降采样进环形缓冲
        decimSum += walk;
        decimCnt++;
        decimAcc += dt;
        if (decimAcc >= DECIM_DT) {
            ring[ringPos] = decimSum / decimCnt;
            ringPos = (ringPos + 1) % AC_N;
            if (ringCount < AC_N) ringCount++;
            decimAcc = Math.min(decimAcc - DECIM_DT, DECIM_DT);
            decimSum = 0;
            decimCnt = 0;
        }

        if (tNs - lastEvalNs >= 500_000_000L) {
            float step = (tNs - lastEvalNs) * 1e-9f;
            lastEvalNs = tNs;
            evaluate(tNs, Math.min(step, 2f));
        }
        return inVehicle;
    }

    /** 1–3Hz 信号在步频范围内的最大归一化自相关（0 = 无周期，接近 1 = 很规律） */
    private float periodicity() {
        if (ringCount < AC_N) return 0f;
        float mean = 0f;
        for (float v : ring) mean += v;
        mean /= AC_N;
        float e0 = 0f;
        for (float v : ring) {
            float x = v - mean;
            e0 += x * x;
        }
        if (e0 < 1e-6f) return 0f;
        float best = 0f;
        for (int lag = LAG_MIN; lag <= LAG_MAX; lag++) {
            float s = 0f;
            for (int i = lag; i < AC_N; i++) {
                float a = ring[(ringPos + i) % AC_N] - mean;
                float b = ring[(ringPos + i - lag) % AC_N] - mean;
                s += a * b;
            }
            float r = s / e0 * AC_N / (AC_N - lag);
            if (r > best) best = r;
        }
        return best;
    }

    private void evaluate(long tNs, float step) {
        float vibRms = (float) Math.sqrt(vibPow);
        float walkRms = (float) Math.sqrt(walkPow);
        float motion = (float) Math.sqrt(motionPow);
        float yawRms = (float) Math.sqrt(yawPow);
        float per = periodicity();
        boolean recentStep = tNs - lastStepNs < 8_000_000_000L;

        periodicStreak = (per > 0.6f && walkRms > 0.15f) ? periodicStreak + 1 : 0;
        boolean walking = recentStep || walkRms > 0.6f || periodicStreak >= 2;
        boolean vibration = vibRms > 0.025f && vibRms < 1.5f;
        // 车辆转弯一定伴随向心加速度；只有偏航没有线加速度，多半是手在慢慢转动手机
        boolean maneuver = motion > 0.15f || (yawRms > 0.05f && motion > 0.06f);

        if (walking) {
            score -= 6f * step;
        } else if (vibration && maneuver) {
            score += 1f * step;
        } else if (vibration) {
            // 只有振动：在车上时视为匀速行驶，保持；不在车上时不累积（手抖/点屏幕也会有振动）
            score += (inVehicle ? 0.1f : -0.05f) * step;
        } else {
            score -= 0.15f * step;  // 静止
        }
        score = MotionEngine.clamp(score, 0f, MAX);
        if (!inVehicle && score >= ENTER) inVehicle = true;
        if (inVehicle && score <= 0f) inVehicle = false;

        LiveStats.vibRms = vibRms;
        LiveStats.walkRms = walkRms;
        LiveStats.walkPeriodicity = per;
        LiveStats.motionRms = motion;
        LiveStats.vehicleScore = score;
        LiveStats.inVehicle = inVehicle;
    }
}
