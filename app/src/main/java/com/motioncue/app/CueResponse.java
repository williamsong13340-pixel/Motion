package com.motioncue.app;

/**
 * 车辆运动 → 圆点位移的映射，以及动画用到的弹簧 / 强调计算。悬浮层和 App 内预览共用。
 *
 * 映射原则：圆点像车里没固定的小球——加速往后（屏幕下方）、刹车往前（上方）、左转往右、右转往左。
 *  - 响应曲线 |a|^0.7：轻微的加减速（0.3–1 m/s²，日常驾驶最常见）也有明显位移，急刹不会冲得太远；
 *  - 前后方向增益更大：两侧点列在竖直方向上的小位移最不显眼，而且竖直方向不会把点推出边缘带；
 *  - 超出范围时用 tanh 软限幅，不会硬生生顶住。
 */
final class CueResponse {
    private CueResponse() {}

    /** 弹簧角频率（rad/s），临界阻尼：约 0.13 秒走完一半 */
    static final float SPRING_W = 13f;
    private static final float SUBSTEP = 0.002f;

    private static final float GAMMA = 0.7f;
    private static final float GAIN_FWD_DP = 30f;   // 每 (m/s²)^0.7，前后
    private static final float GAIN_LAT_DP = 22f;   // 每 (m/s²)^0.7，左右
    private static final float GAIN_YAW_DP = 80f;   // 每 rad/s
    private static final float MAX_FWD_DP = 100f;
    private static final float MAX_LAT_DP = 64f;

    static float shape(float a) {
        float m = Math.abs(a);
        if (m < 1e-5f) return 0f;
        return Math.copySign((float) Math.pow(m, GAMMA), a);
    }

    static float softClamp(float v, float max) {
        return max * (float) Math.tanh(v / max);
    }

    private static float range(float sens) {
        return (float) Math.sqrt(Math.max(0.25f, sens));
    }

    /**
     * @param outRight   屏幕右方向的加速度（m/s²）
     * @param yawScreenX 已映射到屏幕 x 方向的偏航角速度（rad/s）
     * @param dp         每 dp 的像素数
     */
    static float targetX(float outRight, float yawScreenX, float sens, boolean yawCue, float dp) {
        float t = -shape(outRight) * GAIN_LAT_DP * dp * sens
                + (yawCue ? yawScreenX * GAIN_YAW_DP * dp * sens : 0f);
        return softClamp(t, MAX_LAT_DP * dp * range(sens));
    }

    /** @param outForward 屏幕前方（上方）的加速度（m/s²），加速为正 → 圆点往下 */
    static float targetY(float outForward, float sens, float dp) {
        return softClamp(shape(outForward) * GAIN_FWD_DP * dp * sens, MAX_FWD_DP * dp * range(sens));
    }

    /**
     * 临界阻尼弹簧，固定 2ms 子步长积分：不管刷新率是 1Hz 还是 144Hz，轨迹都一样。
     * @param s {px, py, vx, vy}，原地更新
     */
    static void springStep(float[] s, float tx, float ty, float dt) {
        float w2 = SPRING_W * SPRING_W, c = 2f * SPRING_W;
        float px = s[0], py = s[1], vx = s[2], vy = s[3];
        float rem = dt;
        while (rem > 1e-6f) {
            float h = Math.min(SUBSTEP, rem);
            float ax = w2 * (tx - px) - c * vx;
            float ay = w2 * (ty - py) - c * vy;
            vx += ax * h;
            vy += ay * h;
            px += vx * h;
            py += vy * h;
            rem -= h;
        }
        s[0] = px; s[1] = py; s[2] = vx; s[3] = vy;
    }

    /**
     * 运动强调：圆点移动越快越醒目（略微放大、更不透明、带拖影），静止时稍微收敛，不打扰阅读。
     * 车辆运动的「变化」正是前庭最敏感、最需要视觉配合的时刻。
     * @return 新的强调值 0..1（起势快、回落慢）
     */
    static float activity(float current, float speedPx, float dp, float dt) {
        float target = MotionEngine.smoothstep(8f * dp, 90f * dp, speedPx);
        float tau = target > current ? 0.06f : 0.35f;
        return current + (target - current) * MotionEngine.alphaTau(dt, tau);
    }

    static float scaleFor(float activity) { return 1f + 0.2f * activity; }

    static float alphaFor(float activity) { return 0.82f + 0.18f * activity; }

    /** 淡入淡出的缓动 */
    static float ease(float t) {
        return t * t * (3f - 2f * t);
    }
}
