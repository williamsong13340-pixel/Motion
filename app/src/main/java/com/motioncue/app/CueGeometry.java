package com.motioncue.app;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * 点阵与遮罩的纯几何计算（不依赖 View / Choreographer，便于单独测试）。
 *
 * 点阵：交错排列，第 j 行的点在 x = x0 + (i + (j 为奇数 ? 0.5 : 0)) · sx，y = y0 + j · sy，
 * 整体再加上位移 (dx, dy)。点阵是无限的，所以任意位移下都铺满屏幕。
 * 遮罩：只让边缘带可见，静止时每条边显示两列（行），越往内越透明。
 * 所有坐标都是屏幕像素。
 */
final class CueGeometry {

    interface DotSink {
        void dot(float x, float y, float alpha);
    }

    int screenW, screenH;
    int layout = Prefs.LAYOUT_SIDES;
    float dotR, strokeW;   // dotR：实际绘制半径（可能比设置的小，见 compute）
    float sx, sy, half, x0, y0;
    float sideFull, sideZero, tbFull, tbZero;
    private int pad;
    private boolean fullScreenWindow;   // 只用一个全屏窗口（网格布局，或边缘带太宽）
    private boolean topBottom;          // 「四周」布局里上下边带是否有窗口

    /**
     * @param minEdgePx 最外列与屏幕边缘的最小距离（通常为半径 + 描边 + 2dp）
     * @param pad       窗口在遮罩可见范围外额外留出的边距（半径 + 描边 + 余量）
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
        float edge = Math.max(minDim * 0.04f, minEdgePx);         // 最外列离屏幕边缘的距离

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

        // 遮罩：静止时每条边显示两列（行），第三列（行）完全隐藏；内侧淡出区尽量窄，少遮挡内容
        sideFull = edge + 1.15f * half;
        sideZero = edge + 1.45f * half;
        tbFull = edge + 1.15f * sy;
        tbZero = edge + 1.45f * sy;

        // 窗口布局决定（与 alphaAt 保持一致：没有窗口的地方，遮罩也不显示点）
        int sideW = sideWidth(), tbH = tbHeight();
        fullScreenWindow = layout == Prefs.LAYOUT_GRID || sideW * 2 >= w;
        topBottom = !fullScreenWindow && layout == Prefs.LAYOUT_FRAME && tbH * 2 < h && w - 2 * sideW > 0;
    }

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

    /** 靠左右屏幕边缘的淡出：最外列（距边 edge）完全可见，再往外一列（edge - half）完全隐藏 */
    private float outerX(float x) {
        return MotionEngine.smoothstep(x0 - 0.75f * half, x0 - 0.25f * half, Math.min(x, screenW - x));
    }

    /** 靠上下屏幕边缘的淡出 */
    private float outerY(float y) {
        return MotionEngine.smoothstep(y0 - 0.75f * sy, y0 - 0.25f * sy, Math.min(y, screenH - y));
    }

    /**
     * 枚举与给定矩形（屏幕坐标，含 pad 外扩）相交的所有可见点。
     * @param dx 点阵整体位移 x
     * @param dy 点阵整体位移 y
     */
    void forEachDot(float left, float top, float right, float bottom, float dx, float dy, DotSink sink) {
        if (sx <= 0f || sy <= 0f) return;
        float pad = dotR + strokeW;
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
