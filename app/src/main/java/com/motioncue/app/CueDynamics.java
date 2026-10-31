package com.motioncue.app;

/**
 * 圆点场的动态：位移弹簧 + 「强度」+ 方向堆积。悬浮层和 App 内预览共用。
 *
 * 防晕车的核心是让眼睛看到与前庭一致的运动，所以车辆每一次加减速、转弯都用三重线索表达，方向完全一致：
 *  1. 整体滑动：点阵像车里没固定的小球一样滑动（加速往下、刹车往上、左转往右）；
 *  2. 绽放：运动越强，圆点越大、越多（平稳时每侧两列小点，运动时长成最多三列大点），
 *     强度从屏幕边缘逐列向内传播，形成一道柔和的波；运动结束后慢慢收回；
 *  3. 堆积：小球往哪边滑，哪边的圆点就更大、更密——刹车时上半部分涨起来，左转时右侧涨起来。
 *     这样前后方向在两侧点列里也一目了然。
 */
final class CueDynamics {

    /** 平稳时可见的范围（列）：两列同样大小的小点，整齐的交错点阵 */
    private static final float REST_REACH = 2.0f;
    /** 强度为 1 时再多展开的列数：长出第三列（第四列被遮罩挡住） */
    private static final float REACH_GAIN = 1.0f;
    private static final float REST_SIZE = 0.42f;
    /** 方向堆积的强度 */
    private static final float PILE = 0.45f;
    /** 强度每向内传播一列的延迟（秒） */
    private static final float WAVE_TAU = 0.05f;
    /** 低于这个尺寸的点不再继续缩小，而是淡出（避免出现灰尘一样的小点） */
    private static final float MIN_DRAW = 0.28f;

    /** 弹簧状态 {px, py, vx, vy}（像素） */
    final float[] st = new float[4];
    private final float[] lvl = new float[4];   // 第 0..3 列的强度
    private float bx, by;                       // 堆积方向：+bx 往右，+by 往上

    void reset() {
        st[0] = st[1] = st[2] = st[3] = 0f;
        lvl[0] = lvl[1] = lvl[2] = lvl[3] = 0f;
        bx = by = 0f;
    }

    /**
     * 推进一帧。
     * @param aF   屏幕前方（上方）的加速度 m/s²
     * @param aR   屏幕右方的加速度 m/s²
     * @param yawX 映射到屏幕 x 方向的偏航角速度 rad/s
     * @return true 表示已经静止（可以停止逐帧绘制）
     */
    boolean step(float dt, float aF, float aR, float yawX, float sens, boolean yawCue, float dp) {
        float tx = CueResponse.targetX(aR, yawX, sens, yawCue, dp);
        float ty = CueResponse.targetY(aF, sens, dp);
        CueResponse.springStep(st, tx, ty, dt);

        // 强度：起势快（0.07 秒，运动一开始就醒目），回落慢（0.9 秒，柔和收回）
        float mag = (float) Math.hypot(aF, aR) + (yawCue ? 2.5f * Math.abs(yawX) : 0f);
        float target = MotionEngine.smoothstep(0.05f, 1.5f, mag * (float) Math.sqrt(Math.max(0.25f, sens)));
        lvl[0] += (target - lvl[0]) * MotionEngine.alphaTau(dt, target > lvl[0] ? 0.07f : 0.9f);
        float kw = MotionEngine.alphaTau(dt, WAVE_TAU);
        for (int k = 1; k < lvl.length; k++) lvl[k] += (lvl[k - 1] - lvl[k]) * kw;

        // 堆积方向：小球往哪边滑（与加速度相反），哪边涨起来；刹车时 aF < 0 → 往上
        float tbx = MotionEngine.clamp(-aR / 2f, -1f, 1f);
        float tby = MotionEngine.clamp(-aF / 2f, -1f, 1f);
        float kb = MotionEngine.alphaTau(dt, 0.12f);
        bx += (tbx - bx) * kb;
        by += (tby - by) * kb;

        return Math.abs(tx - st[0]) < 0.3f && Math.abs(ty - st[1]) < 0.3f
                && Math.abs(st[2]) < 2f && Math.abs(st[3]) < 2f
                && Math.abs(target - lvl[0]) < 0.002f && Math.abs(lvl[3] - lvl[0]) < 0.002f
                && Math.abs(tbx - bx) < 0.002f && Math.abs(tby - by) < 0.002f;
    }

    /** 实际绘制的尺寸：太小的点保持最小尺寸，改用透明度淡入淡出 */
    static float drawSize(float size) {
        return Math.max(size, MIN_DRAW);
    }

    /** 实际绘制的透明度系数：新长出的点淡入；小点略透明、大点更实（平稳时不打扰阅读，运动时醒目） */
    static float drawAlpha(float size) {
        return MotionEngine.smoothstep(0.08f, MIN_DRAW, size) * (0.72f + 0.28f * Math.min(1f, size));
    }

    /** 当前（最外列的）强度 0..1，供诊断显示 */
    float intensity() { return lvl[0]; }

    /** 第 c 列（可为小数）的强度 */
    private float level(float c) {
        if (c <= 0f) return lvl[0];
        if (c >= lvl.length - 1) return lvl[lvl.length - 1];
        int i = (int) c;
        float f = c - i;
        return lvl[i] + (lvl[i + 1] - lvl[i]) * f;
    }

    /**
     * 某个点的尺寸系数（0 = 不显示，1 = 设置的最大尺寸）。
     * 由「它在边缘带里是第几列」「当前强度」和「它是否在堆积的一侧」决定。
     */
    float size(CueGeometry g, float x, float y) {
        float xn = 2f * x / g.screenW - 1f;     // -1 左 … +1 右
        float yn = 2f * y / g.screenH - 1f;     // -1 上 … +1 下
        float pile = PILE * (bx * xn - by * yn);

        float c;   // 从屏幕边缘往里数第几列（0 = 最外列）
        boolean band = true;
        switch (g.layout) {
            case Prefs.LAYOUT_GRID:
                c = 0f;
                band = false;
                break;
            case Prefs.LAYOUT_FRAME:
                c = g.hasTopBottom() ? Math.min(g.sideColumn(x), g.tbRow(y)) : g.sideColumn(x);
                break;
            default:
                c = g.sideColumn(x);
                break;
        }
        float lv = MotionEngine.clamp(level(Math.max(0f, c)) * (1f + pile), 0f, 1f);
        float prof = 1f;
        if (band) {
            float reach = REST_REACH + REACH_GAIN * lv;
            prof = 1f - MotionEngine.smoothstep(reach - 0.9f, reach + 0.1f, c);
            if (prof <= 0f) return 0f;
        }
        float halftone = 1f - 0.1f * MotionEngine.clamp(c / 2.5f, 0f, 1f);   // 越往里越小一点
        return (REST_SIZE + (1f - REST_SIZE) * lv) * prof * halftone;
    }
}
