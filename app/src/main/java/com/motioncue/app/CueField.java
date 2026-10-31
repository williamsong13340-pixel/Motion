package com.motioncue.app;

import android.content.Context;
import android.graphics.Canvas;
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

    private static final float MAX_DT = 0.5f;    // 低于 2Hz 的帧间隔才会被截断
    private static final float FADE_S = 0.4f;

    private final Context ctx;
    private final MotionEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Choreographer choreographer = Choreographer.getInstance();
    private final ArrayList<View> views = new ArrayList<>();
    private final DotStyle style = new DotStyle();
    private final float dp1;
    private Callback callback;

    // 设置（传感器线程也会读，所以用 volatile）
    private volatile float sensitivity = 1f;
    private volatile boolean yawCue = true;
    private float dotR = 12f;               // 设置的半径（px）
    private int dotCount = 9;
    private int layout = Prefs.LAYOUT_SIDES;
    private int colorMode = Prefs.COLOR_AUTO;
    private int accent;

    // 几何（屏幕坐标，像素）
    private final CueGeometry geo = new CueGeometry();
    private int screenW, screenH;
    private final DrawSink drawSink = new DrawSink();

    // 动态（主线程）：位移弹簧、强度、方向堆积
    private final CueDynamics dyn = new CueDynamics();
    private final float[] st = dyn.st;      // {px, py, vx, vy}
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
        this.dp1 = dp(1f);
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
        accent = Palette.dotAccent(ctx);
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
        // 先按设置的半径算出点的最大绘制范围（放大 + 描边 + 尾迹），决定边距和窗口外扩
        boolean dark = isDarkTheme();
        style.configure(colorMode, dotR, dp1, accent, dark);
        float halo = style.haloWidth();
        float body = dotR + halo + dp(2);   // 圆点本体（最大尺寸 + 描边）
        // 窗口只需容纳圆点本体：遮罩内侧边界处的点已几乎透明，尾迹被窗口裁掉也看不出来，窗口更窄、遮挡更少
        geo.compute(screenW, screenH, dotCount, layout, dotR, halo, body, (int) Math.ceil(body));
        geo.setDrawPad(style.maxExtent() + dp(2));
        // 点很密时几何会缩小实际半径
        style.configure(colorMode, geo.dotR, dp1, accent, dark);
    }

    private boolean isDarkTheme() {
        return (ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
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
        dyn.reset();
        fade = 0f;
    }

    // ---------------- 目标位移 ----------------

    /** 车辆运动 → 圆点位移（像车里没固定的小球：加速往后、刹车往前、左转往右）。 */
    private float targetX() {
        return CueResponse.targetX(engine.outRight, engine.outYaw, sensitivity, yawCue, dp1);
    }

    private float targetY() {
        return CueResponse.targetY(engine.outForward, sensitivity, dp1);
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

        boolean still = dyn.step(dt, engine.outForward, engine.outRight, engine.outYaw, sensitivity, yawCue, dp1);

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
        boolean settled = still && fade == fadeTarget && !engine.demoActive();
        if (settled) {
            // 停止逐帧回调，等待传感器唤醒（先写目标再置 idle，传感器线程据此比较）
            idleTx = targetX();
            idleTy = targetY();
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
        float e = CueResponse.ease(fade);
        d.fadeA = e;
        d.fadeSize = 0.35f + 0.65f * e;   // 出现时圆点从小「绽放」开来
        geo.forEachDot(ox, oy, ox + vw, oy + vh, st[0], st[1], d);
        d.canvas = null;
    }

    /** 复用的绘制回调，避免每帧分配对象 */
    private final class DrawSink implements CueGeometry.DotSink {
        Canvas canvas;
        int ox, oy;
        float fadeA, fadeSize;

        @Override
        public void dot(float x, float y, float alpha) {
            float size = dyn.size(geo, x, y);
            if (size <= 0f) return;
            style.draw(canvas, x - ox, y - oy, st[2], st[3],
                    alpha * fadeA * CueDynamics.drawAlpha(size), CueDynamics.drawSize(size) * fadeSize);
        }
    }
}
