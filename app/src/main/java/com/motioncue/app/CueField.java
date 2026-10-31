package com.motioncue.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 圆点场：一张无限平铺的交错点阵，整体随车辆运动平移；再用一个固定在屏幕上的遮罩
 * 只让边缘带（两侧 / 四周）可见。点阵平移多少都不会飞出屏幕或在中间堆积——
 * 点在边缘带里滑动，越过内侧边界时平滑淡出，另一侧自然有新的点进来。
 *
 * 一个 CueField 可以同时画进多个互不重叠的悬浮窗（每个窗口只画自己那一块），
 * 拼起来是无缝的；这样屏幕中间没有悬浮窗覆盖，不会影响其它 App 的触摸。
 *
 * 刷新率：由 Choreographer 逐 VSYNC 驱动，回调频率 = 屏幕当前真实刷新率（60/90/120Hz、LTPO 动态变化）。
 * 物理模拟用真实帧间隔 + 固定 2ms 子步长积分，刷新率怎么变，运动速度和轨迹都一致。
 * 省电：圆点静止后停止逐帧回调，屏幕可以降回低刷新率；传感器检测到变化时再唤醒。
 *
 * 除 onSensorSample() 外，所有方法都必须在主线程调用。
 */
final class CueField implements Choreographer.FrameCallback {

    interface Callback {
        void onFadedOut();
    }

    private static final float SUBSTEP = 0.002f;
    private static final float SPRING_W = 11f;   // 弹簧角频率（rad/s），临界阻尼
    private static final float MAX_DT = 0.5f;    // 低于 2Hz 的帧间隔才会被截断
    private static final float FADE_S = 0.35f;

    private final Context ctx;
    private final MotionEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Choreographer choreographer = Choreographer.getInstance();
    private final ArrayList<View> views = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Callback callback;

    // 设置（传感器线程也会读，所以用 volatile）
    private volatile float sensitivity = 1f;
    private volatile boolean yawCue = true;
    private float dotR = 12f;
    private float strokeW = 3f;
    private int dotCount = 9;
    private int layout = Prefs.LAYOUT_SIDES;
    private int colorMode = Prefs.COLOR_AUTO;

    // 几何（屏幕坐标，像素）
    private final CueGeometry geo = new CueGeometry();
    private int screenW, screenH;
    private final DrawSink drawSink = new DrawSink();

    // 物理状态（主线程）
    private float px, py, vx, vy;
    private float fade, fadeTarget;
    private long lastFrameNs;
    private boolean running;
    private float fpsEma;

    // 静止 / 唤醒（传感器线程读写）
    private volatile boolean idle = true;
    private volatile float idleTx, idleTy;
    private final Runnable startFrames = this::startFrames;

    CueField(Context ctx, MotionEngine engine) {
        this.ctx = ctx;
        this.engine = engine;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
    }

    void setCallback(Callback cb) { callback = cb; }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }

    // ---------------- 设置与几何 ----------------

    void applySettings() {
        sensitivity = Prefs.sensitivity(ctx);
        yawCue = Prefs.yawCue(ctx);
        dotR = dp(Prefs.dotSize(ctx));
        dotCount = Prefs.dotCount(ctx);
        layout = Prefs.layout(ctx);
        colorMode = Prefs.color(ctx);
        switch (colorMode) {
            case Prefs.COLOR_WHITE:
                fill.setColor(Color.WHITE);
                break;
            case Prefs.COLOR_BLACK:
                fill.setColor(Color.BLACK);
                break;
            default:
                fill.setColor(0xFFF2F2F2);
                stroke.setColor(0xFF000000);
                break;
        }
        strokeW = colorMode == Prefs.COLOR_AUTO ? Math.max(1f, dp(1.2f)) : 0f;
        stroke.setStrokeWidth(strokeW);
        computeGeometry();
        invalidateViews();
        startFrames();
    }

    /** @return true 表示尺寸有变化 */
    boolean setScreenSize(int w, int h) {
        if (w == screenW && h == screenH) return false;
        screenW = w;
        screenH = h;
        computeGeometry();
        return true;
    }

    private void computeGeometry() {
        float margin = dotR + strokeW + dp(2);
        geo.compute(screenW, screenH, dotCount, layout, dotR, strokeW, margin, (int) Math.ceil(margin));
    }

    /** 当前布局需要的悬浮窗矩形（屏幕坐标），互不重叠。 */
    List<Rect> windowRects() {
        return geo.windowRects();
    }

    // ---------------- 窗口 ----------------

    void attach(View v) {
        if (!views.contains(v)) views.add(v);
        startFrames();
    }

    void detach(View v) {
        views.remove(v);
        if (views.isEmpty()) {
            choreographer.removeFrameCallback(this);
            running = false;
            idle = true;
            LiveStats.animIdle = true;
        }
    }

    private void invalidateViews() {
        for (int i = 0; i < views.size(); i++) views.get(i).invalidate();
    }

    // ---------------- 显隐 ----------------

    void fadeIn() {
        fadeTarget = 1f;
        startFrames();
    }

    void fadeOut() {
        fadeTarget = 0f;
        startFrames();
    }

    boolean isFadingOutOrHidden() { return fadeTarget == 0f; }

    /** 窗口重新出现时从静止位置开始，不带上次隐藏前的位移。 */
    void resetMotion() {
        px = py = vx = vy = 0f;
        fade = 0f;
    }

    // ---------------- 目标位移 ----------------

    private float maxDisp() {
        return dp(58f) * (float) Math.sqrt(Math.max(0.25f, sensitivity));
    }

    /** 车辆运动 → 圆点位移（像车里没固定的小球：加速往后、刹车往前、左转往右）。 */
    private float targetX() {
        float s = sensitivity;
        float g = dp(18f) * s;     // px / (m/s²)
        float yg = dp(80f) * s;    // px / (rad/s)
        float t = -engine.outRight * g + (yawCue ? engine.outYaw * yg : 0f);
        return softClamp(t, maxDisp());
    }

    private float targetY() {
        float g = dp(18f) * sensitivity;
        return softClamp(engine.outForward * g, maxDisp());
    }

    private static float softClamp(float v, float max) {
        return max * (float) Math.tanh(v / max);
    }

    /** 传感器线程调用：静止状态下，如果目标位置明显改变就唤醒动画。 */
    void onSensorSample() {
        if (!idle) return;
        float dx = targetX() - idleTx, dy = targetY() - idleTy;
        if (dx * dx + dy * dy > 0.25f) {
            idle = false;
            main.post(startFrames);
        }
    }

    // ---------------- 逐帧动画 ----------------

    void startFrames() {
        if (views.isEmpty()) return;
        idle = false;
        LiveStats.animIdle = false;
        if (!running) {
            running = true;
            lastFrameNs = 0;
            choreographer.postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        running = false;
        if (views.isEmpty()) return;
        float dt;
        if (lastFrameNs == 0) {
            dt = 1f / 60f;
        } else {
            dt = (frameTimeNanos - lastFrameNs) * 1e-9f;
            if (dt > 0f && dt < 0.25f) {
                float fps = 1f / dt;
                fpsEma = fpsEma == 0 ? fps : fpsEma + (fps - fpsEma) * 0.1f;
                LiveStats.measuredFps = fpsEma;
            }
            dt = MotionEngine.clamp(dt, 0f, MAX_DT);
        }
        lastFrameNs = frameTimeNanos;

        float tx = targetX(), ty = targetY();

        // 临界阻尼弹簧，固定子步长 → 与刷新率无关
        float w2 = SPRING_W * SPRING_W, c = 2f * SPRING_W;
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

        float fs = dt / FADE_S;
        if (fade < fadeTarget) fade = Math.min(fadeTarget, fade + fs);
        else if (fade > fadeTarget) fade = Math.max(fadeTarget, fade - fs);

        invalidateViews();

        if (fade == 0f && fadeTarget == 0f) {
            idle = true;
            LiveStats.animIdle = true;
            fpsEma = 0;
            if (callback != null) callback.onFadedOut();
            return;
        }
        boolean settled = Math.abs(tx - px) < 0.3f && Math.abs(ty - py) < 0.3f
                && Math.abs(vx) < 2f && Math.abs(vy) < 2f && fade == fadeTarget
                && !engine.demoActive();
        if (settled) {
            // 停止逐帧回调，等待传感器唤醒（先写目标再置 idle，传感器线程据此比较）
            idleTx = tx;
            idleTy = ty;
            idle = true;
            LiveStats.animIdle = true;
            fpsEma = 0;
            return;
        }
        running = true;
        choreographer.postFrameCallback(this);
    }

    /** 由各窗口的 onDraw 调用：把屏幕坐标的点阵画进窗口（窗口左上角在屏幕上的位置为 ox, oy）。 */
    void draw(Canvas canvas, int ox, int oy, int vw, int vh) {
        if (fade <= 0f) return;
        DrawSink d = drawSink;
        d.canvas = canvas;
        d.ox = ox;
        d.oy = oy;
        d.stroke = strokeW > 0f;
        geo.forEachDot(ox, oy, ox + vw, oy + vh, px, py, d);
        d.canvas = null;
    }

    /** 复用的绘制回调，避免每帧分配对象 */
    private final class DrawSink implements CueGeometry.DotSink {
        Canvas canvas;
        int ox, oy;
        boolean stroke;

        @Override
        public void dot(float x, float y, float alpha) {
            float a = alpha * fade;
            if (a <= 0.004f) return;
            float cx = x - ox, cy = y - oy;
            float r = geo.dotR;
            fill.setAlpha(Math.round(255 * a));
            canvas.drawCircle(cx, cy, r, fill);
            if (stroke) {
                CueField.this.stroke.setAlpha(Math.round(0xCC * a));
                canvas.drawCircle(cx, cy, r, CueField.this.stroke);
            }
        }
    }
}
