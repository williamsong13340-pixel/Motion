package com.motioncue.app;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * 点阵与遮罩的纯几何计算（不依赖 View / Choreographer，便于单独测试）。
 *
 * 点阵：交错排列，第 j 行的点在 x = x0 + (i + (j 为奇数 ? 0.5 : 0)) · sx，y = y0 + j · sy，
 * 整体再加上位移 (dx, dy)。点阵是无限的，所以任意位移下都铺满屏幕。
 * 遮罩：只让边缘带可见（两侧最多三列、上下最多两行），越往内越透明；每个点的大小由 CueDynamics 决定。
 * 所有坐标都是屏幕像素。
 */
final class CueGeometry {

    interface DotSink {
        void dot(float x, float y, float alpha);
    }

    int screenW, screenH;
    int layout = Prefs.LAYOUT_SIDES;
    float dotR, strokeW;   // dotR：实际绘制半径（可能比设置的小，见 compute）；strokeW：光晕宽度
    float sx, sy, half, x0, y0;
    float sideFull, sideZero, tbFull, tbZero;
    private int pad;
    private float drawPad;              // 枚举点时的外扩（含尾迹），可大于窗口外扩
    private float outerFullX, outerZeroX, outerFullY, outerZeroY;   // 靠屏幕物理边缘的淡出
    private float cornerR;              // 屏幕圆角半径（px），角上的点提前淡出
    private boolean fullScreenWindow;   // 只用一个全屏窗口（网格布局，或边缘带太宽）
    private boolean topBottom;          // 「四周」布局里上下边带是否有窗口

    /**
     * @param strokeW   圆芯外的光晕宽度
     * @param minEdgePx 最外列与屏幕边缘的最小距离（通常为放大后的半径 + 光晕 + 2dp）
     * @param pad       窗口在遮罩可见范围外额外留出的边距（点的最大绘制范围 + 余量）
     */
    void compute(int w, int h, int dotCount, int layout, float dotR, float strokeW, float minEdgePx, int pad) {
        this.screenW = w;
        this.screenH = h;
        this.layout = layout;
        this.dotR = dotR;
        this.strokeW = strokeW;
        this.pad = pad;
        if (w <= 0 || h <= 0) return;
        float minDim = Math.min(w, h);
        int n = Math.max(4, Math.min(16, dotCount));
        float s = minDim / n;                                    // 行内间距
        // 最外列离屏幕边缘的距离：尽量落在一般 App 的 16dp 页边距里，少压正文
        float edge = Math.max(minDim * 0.025f, minEdgePx);

        // 左右两侧的点列关于屏幕中线对称：两侧最外列之间正好是偶数个半间距
        int kx = Math.max(2, Math.round((w - 2 * edge) / (s / 2f)));
        if ((kx & 1) == 1) kx++;
        half = (w - 2 * edge) / kx;
        sx = 2 * half;
        x0 = edge;

        int ky = Math.max(2, Math.round((h - 2 * edge) / (s * 0.866f)));
        if ((ky & 1) == 1) ky++;
        sy = (h - 2 * edge) / ky;
        y0 = edge;

        // 点又大又密时（小屏 + 最大尺寸 + 最多点数）限制实际半径，保证相邻的点不会挤在一起
        float nearest = Math.min(sx, (float) Math.hypot(half, sy));
        // 含描边的直径最多占最近邻距离的 90%
        this.dotR = Math.max(1f, Math.min(dotR, 0.45f * nearest - 0.5f * strokeW));

        // 遮罩：两侧最多三列、上下最多两行可见（每一时刻实际显示几列由 CueDynamics 的尺寸决定），
        // 再往里的完全隐藏，所以窗口宽度是固定的
        sideFull = edge + 2.2f * half;
        sideZero = edge + 2.6f * half;
        tbFull = edge + 1.2f * sy;
        tbZero = edge + 1.6f * sy;

        // 靠屏幕边缘的淡出与实际绘制的半径挂钩：圆点碰到屏幕边缘之前就开始淡出，而不是被切掉一半；
        // 同时保证静止时屏幕外那一列（行）完全不可见
        float rDraw = this.dotR + strokeW;
        outerFullX = Math.min(rDraw, x0);
        outerZeroX = Math.max(x0 - half, outerFullX - 0.5f * half);
        if (outerZeroX >= outerFullX) outerZeroX = outerFullX - 1f;
        outerFullY = Math.min(rDraw, y0);
        outerZeroY = Math.max(y0 - sy, outerFullY - 0.5f * sy);
        if (outerZeroY >= outerFullY) outerZeroY = outerFullY - 1f;

        // 窗口布局决定（与 alphaAt 保持一致：没有窗口的地方，遮罩也不显示点）
        int sideW = sideWidth(), tbH = tbHeight();
        fullScreenWindow = layout == Prefs.LAYOUT_GRID || sideW * 2 >= w;
        topBottom = !fullScreenWindow && layout == Prefs.LAYOUT_FRAME && tbH * 2 < h && w - 2 * sideW > 0;
    }

    /** 点的最大绘制范围（含放大、光晕、尾迹）：跨窗口边界的点两边都要画到 */
    void setDrawPad(float px) { drawPad = px; }

    /** 屏幕圆角半径（px，0 = 直角） */
    void setCornerRadius(float px) { cornerR = Math.max(0f, px); }

    /** 从左右屏幕边缘往里数第几列（0 = 最外列，可为小数） */
    float sideColumn(float x) {
        return half > 0f ? (Math.min(x, screenW - x) - x0) / half : 0f;
    }

    /** 从上下屏幕边缘往里数第几行（0 = 最外行，可为小数） */
    float tbRow(float y) {
        return sy > 0f ? (Math.min(y, screenH - y) - y0) / sy : 0f;
    }

    /** 「四周」布局是否有上下边带 */
    boolean hasTopBottom() { return topBottom; }

    private int sideWidth() { return (int) Math.ceil(sideZero) + pad; }

    private int tbHeight() { return (int) Math.ceil(tbZero) + pad; }

    /** 当前布局需要的悬浮窗矩形（屏幕坐标），互不重叠。 */
    List<Rect> windowRects() {
        ArrayList<Rect> out = new ArrayList<>();
        int w = screenW, h = screenH;
        if (w <= 0 || h <= 0) return out;
        if (fullScreenWindow) {
            out.add(new Rect(0, 0, w, h));
            return out;
        }
        int sideW = sideWidth();
        out.add(new Rect(0, 0, sideW, h));
        out.add(new Rect(w - sideW, 0, w, h));
        if (topBottom) {
            int tbH = tbHeight();
            out.add(new Rect(sideW, 0, w - sideW, tbH));
            out.add(new Rect(sideW, h - tbH, w - sideW, h));
        }
        return out;
    }

    /**
     * 每个点的不透明度。除了边缘带内侧的淡出，靠屏幕物理边缘一侧也有淡出：
     * 静止时屏幕外那一列（行）完全不可见（不会在屏幕边上露出半个点），
     * 运动时点走到屏幕边缘前先淡出，而不是被生硬地切掉。
     */
    float alphaAt(float x, float y) {
        float a = maskAt(x, y);
        if (a <= 0f || cornerR <= 0f) return a;
        return a * cornerAlpha(x, y);
    }

    /** 圆角处：圆点完整落在圆角内才完全可见，压到圆角边缘时已淡出 */
    private float cornerAlpha(float x, float y) {
        float cx = MotionEngine.clamp(x, cornerR, screenW - cornerR);
        float cy = MotionEngine.clamp(y, cornerR, screenH - cornerR);
        float dx = x - cx, dy = y - cy;
        if (dx == 0f && dy == 0f) return 1f;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        float rDraw = dotR + strokeW;
        return 1f - MotionEngine.smoothstep(cornerR - rDraw, cornerR, d);
    }

    private float maskAt(float x, float y) {
        switch (layout) {
            case Prefs.LAYOUT_GRID:
                return outerX(x) * outerY(y);
            case Prefs.LAYOUT_FRAME:
                if (topBottom || fullScreenWindow) {
                    return Math.max(innerX(x) * outerX(x) * outerY(y), innerY(y) * outerY(y) * outerX(x));
                }
                // 屏幕太小放不下上下边带的窗口：退化为两侧
                return innerX(x) * outerX(x) * outerY(y);
            default:
                return innerX(x) * outerX(x) * outerY(y);
        }
    }

    /** 左右边缘带内侧的淡出 */
    private float innerX(float x) {
        return 1f - MotionEngine.smoothstep(sideFull, sideZero, Math.min(x, screenW - x));
    }

    /** 上下边缘带内侧的淡出 */
    private float innerY(float y) {
        return 1f - MotionEngine.smoothstep(tbFull, tbZero, Math.min(y, screenH - y));
    }

    /** 靠左右屏幕边缘的淡出：最外列完全可见，碰到屏幕边缘前开始淡出，再往外一列完全隐藏 */
    private float outerX(float x) {
        return MotionEngine.smoothstep(outerZeroX, outerFullX, Math.min(x, screenW - x));
    }

    /** 靠上下屏幕边缘的淡出 */
    private float outerY(float y) {
        return MotionEngine.smoothstep(outerZeroY, outerFullY, Math.min(y, screenH - y));
    }

    /**
     * 枚举与给定矩形（屏幕坐标，含 pad 外扩）相交的所有可见点。
     * @param dx 点阵整体位移 x
     * @param dy 点阵整体位移 y
     */
    void forEachDot(float left, float top, float right, float bottom, float dx, float dy, DotSink sink) {
        if (sx <= 0f || sy <= 0f) return;
        // 外扩 = 点的最大绘制范围（放大、光晕、尾迹），保证跨窗口边界的点两边都画到
        float pad = Math.max(drawPad, Math.max(this.pad, dotR + strokeW));
        float gx0 = left - pad, gx1 = right + pad;
        float gy0 = top - pad, gy1 = bottom + pad;
        int jMin = (int) Math.floor((gy0 - y0 - dy) / sy);
        int jMax = (int) Math.ceil((gy1 - y0 - dy) / sy);
        for (int j = jMin; j <= jMax; j++) {
            float y = y0 + j * sy + dy;
            float shift = ((j & 1) != 0) ? half : 0f;
            int iMin = (int) Math.floor((gx0 - x0 - shift - dx) / sx);
            int iMax = (int) Math.ceil((gx1 - x0 - shift - dx) / sx);
            for (int i = iMin; i <= iMax; i++) {
                float x = x0 + shift + i * sx + dx;
                float a = alphaAt(x, y);
                if (a > 0.004f) sink.dot(x, y, a);
            }
        }
    }
}
