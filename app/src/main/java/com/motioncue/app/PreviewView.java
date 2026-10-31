package com.motioncue.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

/**
 * App 内的实时预览：一部迷你手机，屏幕里是示意内容，边缘是和悬浮层完全相同的点阵、样式与动画。
 * 服务运行时跟随真实传感器数据；否则播放一段模拟的「加速 → 转弯 → 刹车」。
 */
final class PreviewView extends View implements Choreographer.FrameCallback {

    /** 预览里手机很小，圆点尺寸按此倍数放大保证看得清；位移不放大，和真实悬浮层的比例一致 */
    private static final float EXAGGERATE = 1.25f;

    private final Palette pal;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final CueGeometry geo = new CueGeometry();
    private final DotStyle style = new DotStyle();
    private final float dp1;
    private final CueDynamics dyn = new CueDynamics();
    private final float[] st = dyn.st;
    private long lastNs;
    private boolean playing, posted;
    private float demoT;
    private boolean live;

    private int screenW = 1264, screenH = 2780;   // 真实屏幕尺寸（像素）
    private float sensitivity = 1f;
    private boolean yawCue = true;

    private final RectF body = new RectF(), screen = new RectF();
    private final Path screenClip = new Path();
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private float k = 0.2f;    // 真实屏幕像素 → 预览像素

    private final DrawSink sink = new DrawSink();

    /** 复用的绘制回调 */
    private final class DrawSink implements CueGeometry.DotSink {
        Canvas canvas;

        @Override
        public void dot(float x, float y, float alpha) {
            float size = dyn.size(geo, x, y);
            if (size <= 0f) return;
            style.draw(canvas, x, y, st[2], st[3], alpha * CueDynamics.drawAlpha(size), CueDynamics.drawSize(size));
        }
    }

    PreviewView(Context ctx, Palette pal) {
        super(ctx);
        this.pal = pal;
        dp1 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, ctx.getResources().getDisplayMetrics());
        bodyPaint.setColor(pal.dark ? 0xFF3A3D45 : 0xFF3A3D44);
        screenPaint.setColor(pal.dark ? 0xFF14161A : 0xFFF8F9FB);
        blockPaint.setColor(pal.dark ? 0xFF262931 : 0xFFE7E9EE);
        chipPaint.setColor(pal.surfaceVariant);
        chipText.setColor(pal.textSecondary);
        chipText.setTextSize(11.5f * dp1);
        chipText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        reload();
    }

    /** 真实屏幕尺寸（决定预览手机的比例和点阵） */
    void setScreen(int w, int h) {
        if (w <= 0 || h <= 0 || (w == screenW && h == screenH)) return;
        screenW = w;
        screenH = h;
        reload();
        layoutPhone(getWidth(), getHeight());
    }

    /** 重新读取设置 */
    void reload() {
        Context c = getContext();
        sensitivity = Prefs.sensitivity(c);
        yawCue = Prefs.yawCue(c);
        int color = Prefs.color(c);
        float r = Prefs.dotSize(c) * dp1 * EXAGGERATE;
        float dpx = dp1 * EXAGGERATE;
        int accent = Palette.dotAccent(c);
        style.configure(color, r, dpx, accent, pal.dark);
        float halo = style.haloWidth();
        float body = r + halo + 2 * dpx;
        geo.compute(screenW, screenH, Prefs.dotCount(c), Prefs.layout(c), r, halo, body, (int) Math.ceil(body));
        geo.setDrawPad(style.maxExtent());
        style.configure(color, geo.dotR, dpx, accent, pal.dark);
        invalidate();
    }

    void setPlaying(boolean p) {
        playing = p;
        if (p) post();
    }

    private void post() {
        if (playing && !posted && isAttachedToWindow()) {
            posted = true;
            lastNs = 0;
            choreographer.postFrameCallback(this);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post();
    }

    @Override
    protected void onDetachedFromWindow() {
        choreographer.removeFrameCallback(this);
        posted = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = Math.round(Math.min(w * 0.9f, 300 * dp1));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutPhone(w, h);
    }

    /** 按真实屏幕比例在视图中央摆放迷你手机 */
    private void layoutPhone(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float aspect = (float) screenW / screenH;
        float bh = h - 16 * dp1;
        float bw = bh * aspect;
        if (bw > w - 32 * dp1) {
            bw = w - 32 * dp1;
            bh = bw / aspect;
        }
        float left = (w - bw) / 2f, top = (h - bh) / 2f;
        body.set(left, top, left + bw, top + bh);
        float inset = Math.max(1.5f * dp1, bw * 0.011f);   // 细边框，更轻盈
        screen.set(body.left + inset, body.top + inset, body.right - inset, body.bottom - inset);
        float bodyR = Math.min(bw, bh) * 0.13f;
        screenClip.reset();
        screenClip.addRoundRect(screen, bodyR - inset, bodyR - inset, Path.Direction.CW);
        k = screen.width() / screenW;
        invalidate();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        posted = false;
        if (!playing) return;
        float dt = lastNs == 0 ? 1f / 60f : MotionEngine.clamp((frameTimeNanos - lastNs) * 1e-9f, 0f, 0.1f);
        lastNs = frameTimeNanos;

        float aF, aR, yawX;
        live = LiveStats.serviceRunning;
        if (live) {
            aF = LiveStats.accelForward;
            aR = LiveStats.accelRight;
            yawX = LiveStats.yawScreen;
        } else {
            // 模拟一段行驶：加速、左转、刹车、右转，循环播放
            demoT += dt;
            double t = demoT;
            aF = (float) (1.3 * Math.sin(2 * Math.PI * t / 6.0));
            aR = (float) (-1.0 * Math.sin(2 * Math.PI * t / 7.5 + 1.2));
            yawX = (float) (-aR * 0.14);
        }
        // 位移按真实屏幕像素计算（与悬浮层完全相同），再随预览一起缩小
        dyn.step(dt, aF, aR, yawX, sensitivity, yawCue, dp1);
        invalidate();
        post();
    }

    @Override
    protected void onDraw(Canvas c) {
        float bodyR = Math.min(body.width(), body.height()) * 0.13f;
        c.drawRoundRect(body, bodyR, bodyR, bodyPaint);

        c.save();
        c.clipPath(screenClip);
        c.drawRect(screen, screenPaint);
        drawMockContent(c);

        // 点阵：在真实屏幕坐标系里画，再整体缩放到预览大小
        c.translate(screen.left, screen.top);
        c.scale(k, k);
        sink.canvas = c;
        geo.forEachDot(0, 0, screenW, screenH, st[0], st[1], sink);
        sink.canvas = null;
        c.restore();

        // 角标：实时 / 演示
        String label = live ? "实时" : "演示";
        float tw = chipText.measureText(label);
        float pad = 8 * dp1;
        tmp.set(12 * dp1, 10 * dp1, 12 * dp1 + tw + 2 * pad, 10 * dp1 + 22 * dp1);
        c.drawRoundRect(tmp, 11 * dp1, 11 * dp1, chipPaint);
        c.drawText(label, tmp.left + pad, tmp.centerY() - (chipText.ascent() + chipText.descent()) / 2f, chipText);
    }

    /** 屏幕里的示意内容：标题栏、几行文字、一张图片 */
    private void drawMockContent(Canvas c) {
        float w = screen.width(), h = screen.height();
        float x = screen.left + w * 0.2f, right = screen.right - w * 0.2f;
        float y = screen.top + h * 0.09f;
        float line = Math.max(2 * dp1, h * 0.016f);
        float rr = line / 2f;
        tmp.set(x, y, x + (right - x) * 0.55f, y + line * 1.5f);
        c.drawRoundRect(tmp, rr, rr, blockPaint);
        y += line * 3.2f;
        tmp.set(x, y, right, y + h * 0.16f);
        c.drawRoundRect(tmp, line, line, blockPaint);
        y += h * 0.16f + line * 2f;
        float[] widths = {1f, 0.92f, 0.97f, 0.7f, 0f, 1f, 0.88f, 0.95f, 0.6f, 0f, 0.98f, 0.9f, 0.75f};
        for (float f : widths) {
            if (f > 0f && y + line < screen.bottom - h * 0.05f) {
                tmp.set(x, y, x + (right - x) * f, y + line);
                c.drawRoundRect(tmp, rr, rr, blockPaint);
            }
            y += line * 2.1f;
        }
    }
}
