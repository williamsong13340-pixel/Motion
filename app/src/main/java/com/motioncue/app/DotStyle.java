package com.motioncue.app;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * 圆点的外观：实心圆芯 + 一圈细细的对比色描边；运动时拖出一条由粗到细、由浓到淡的彗尾。
 * 「自动」配色跟随系统深浅色（大多数 App 也跟随系统）：浅色模式深灰点、深色模式浅色点，
 * 对比色细边保证遇到相反底色（例如浅色模式下看视频）时也清楚。悬浮层和 App 内预览共用。
 */
final class DotStyle {

    /** 尾迹长度 = 速度 × 该时间（与刷新率无关） */
    private static final float TRAIL_TIME = 0.05f;
    /** 尾迹最长为几个半径 */
    private static final float MAX_TRAIL = 1.5f;
    /** 彗尾分三段叠加：越靠近圆点越浓、越粗 */
    private static final int TRAIL_SEGMENTS = 3;
    private static final float TRAIL_SEGMENT_ALPHA = 0.22f;

    private final Paint coreFill = fill();
    private final Paint rimRing = ring();
    private final Paint trailLine = line();

    private float radius = 12f;          // 最大半径（尺寸系数为 1 时）
    private float rimW;
    private int coreA = 255, rimA;

    private static Paint fill() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    private static Paint ring() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        return p;
    }

    private static Paint line() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    /**
     * @param radiusPx  圆芯半径（px）
     * @param dp        每 dp 的像素数
     * @param accent    主题色（COLOR_ACCENT 模式使用）
     * @param darkTheme 系统当前是否为深色模式
     */
    void configure(int colorMode, float radiusPx, float dp, int accent, boolean darkTheme) {
        radius = radiusPx;
        int core, rim;
        float coreAlpha, rimAlpha;
        switch (colorMode) {
            case Prefs.COLOR_WHITE:
                core = 0xFFFFFFFF; coreAlpha = 0.96f; rim = 0xFF000000; rimAlpha = 0.22f;
                break;
            case Prefs.COLOR_BLACK:
                core = 0xFF17181C; coreAlpha = 0.92f; rim = 0xFFFFFFFF; rimAlpha = 0.40f;
                break;
            case Prefs.COLOR_ACCENT:
                core = accent | 0xFF000000; coreAlpha = 0.96f;
                rim = darkTheme ? 0xFF000000 : 0xFFFFFFFF; rimAlpha = darkTheme ? 0.40f : 0.60f;
                break;
            default: // 自动：跟随系统深浅色
                // 描边较宽较实：内容与主题一致时描边融进背景看不见；不一致（深色模式下的浅色页面、
                // 浅色模式下的视频）时由描边勾出圆点，对比度仍 ≥ 3:1
                if (darkTheme) {
                    core = 0xFFEDEEF2; coreAlpha = 0.94f; rim = 0xFF000000; rimAlpha = 0.72f;
                } else {
                    core = 0xFF2C2D32; coreAlpha = 0.88f; rim = 0xFFFFFFFF; rimAlpha = 0.80f;
                }
                break;
        }
        rimW = (colorMode == Prefs.COLOR_AUTO ? 1.7f : 1.2f) * dp;
        coreA = Math.round(255 * coreAlpha);
        rimA = Math.round(255 * rimAlpha);
        coreFill.setColor(core);
        trailLine.setColor(core);
        rimRing.setColor(rim);
        rimRing.setStrokeWidth(rimW);
    }

    float radius() { return radius; }

    /** 圆芯之外的描边宽度 */
    float haloWidth() { return rimW; }

    /** 一个点（最大尺寸，含描边、尾迹）离圆心最远能画到多远 */
    float maxExtent() {
        return radius + rimW + MAX_TRAIL * radius;
    }

    /**
     * @param vx    圆点速度（px/s），用于尾迹
     * @param alpha 0..1（遮罩、淡入淡出已乘好）
     * @param size  尺寸系数 0..1（CueDynamics 计算）
     */
    void draw(Canvas c, float x, float y, float vx, float vy, float alpha, float size) {
        float a = alpha;
        float r = radius * size;
        if (a <= 0.004f || r < 0.6f) return;

        // 彗尾：从圆心向运动反方向延伸；三段由短到长叠加，越靠近圆点越浓，越远越细越淡
        float tx = vx * TRAIL_TIME, ty = vy * TRAIL_TIME;
        float len = (float) Math.hypot(tx, ty);
        float maxLen = MAX_TRAIL * r;
        if (len > maxLen) {
            float k = maxLen / len;
            tx *= k;
            ty *= k;
            len = maxLen;
        }
        if (len > 0.6f) {
            float strength = MotionEngine.smoothstep(0.6f, Math.max(1.2f, 0.6f * r), len);
            int al = Math.round(coreA * a * TRAIL_SEGMENT_ALPHA * strength);
            if (al > 0) {
                trailLine.setAlpha(al);
                for (int i = 1; i <= TRAIL_SEGMENTS; i++) {
                    float f = (float) i / TRAIL_SEGMENTS;
                    trailLine.setStrokeWidth(2f * r * (1f - 0.22f * i));
                    c.drawLine(x, y, x - tx * f, y - ty * f, trailLine);
                }
            }
        }
        int ra = Math.round(rimA * a);
        if (ra > 0) {
            // 描边是圆芯外侧的一圈，不和半透明的圆芯重叠；小点的描边也相应变细
            float rw = rimW * (0.45f + 0.55f * Math.min(1f, size));
            rimRing.setStrokeWidth(rw);
            rimRing.setAlpha(ra);
            c.drawCircle(x, y, r + rw * 0.5f, rimRing);
        }
        coreFill.setAlpha(Math.round(coreA * a));
        c.drawCircle(x, y, r, coreFill);
    }
}
