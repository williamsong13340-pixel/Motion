package com.motioncue.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

/**
 * 悬浮层上的运动提示圆点。
 *
 * 刷新率：由 Choreographer 逐 VSYNC 驱动，回调频率 = 屏幕当前真实刷新率（60/90/120Hz 或 LTPO 动态变化）。
 * 物理模拟用 frameTimeNanos 的真实间隔 + 固定 2ms 子步长积分，所以不管刷新率怎么变，
 * 圆点的运动速度和轨迹都完全一致，只是高刷下更顺滑。
 *
 * 省电：圆点静止（弹簧收敛）后停止逐帧回调，屏幕可以降回低刷新率；传感器检测到变化时再唤醒。
 */
public final class CueOverlayView extends View implements Choreographer.FrameCallback {

    public interface Callback {
        void onFadedOut();
    }

    private static final float SUBSTEP = 0.002f;
    private static final float SPRING_W = 11f;   // 弹簧角频率（rad/s），临界阻尼

    private final MotionEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private Callback callback;

    // 设置
    private float sensitivity = 1f;
    private float dotRadiusPx;
    private int dotCount = 9;
    private int layout = Prefs.LAYOUT_SIDES;
    private int colorMode = Prefs.COLOR_AUTO;
    private boolean yawCue = true;

    // 圆点基准位置
    private float[] baseX = new float[0], baseY = new float[0];

    // 物理状态（主线程）
    private float px, py, vx, vy;
    private float fade = 0f, fadeTarget = 0f;
    private long lastFrameNs;
    private boolean running;
    private float fpsEma;

    // 唤醒判断（传感器线程读写）
    private volatile boolean idle = true;
    private volatile float idleTx, idleTy;
    private final Runnable startFrames = this::startFrames;

    public CueOverlayView(Context ctx, MotionEngine engine) {
        super(ctx);
        this.engine = engine;
        density = ctx.getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setCallback(Callback cb) { callback = cb; }

    public void applySettings(Context c) {
        sensitivity = Prefs.sensitivity(c);
        dotRadiusPx = dp(Prefs.dotSize(c));
        dotCount = Prefs.dotCount(c);
        layout = Prefs.layout(c);
        colorMode = Prefs.color(c);
        yawCue = Prefs.yawCue(c);
        switch (colorMode) {
            case Prefs.COLOR_WHITE:
                fill.setColor(Color.WHITE);
                stroke.setColor(Color.TRANSPARENT);
                break;
            case Prefs.COLOR_BLACK:
                fill.setColor(Color.BLACK);
                stroke.setColor(Color.TRANSPARENT);
                break;
            default:
                fill.setColor(0xFFF2F2F2);
                stroke.setColor(0xCC000000);
                break;
        }
        stroke.setStrokeWidth(Math.max(1f, dp(1.2f)));
        rebuildDots(getWidth(), getHeight());
        startFrames();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------------- 显隐 ----------------

    public void fadeIn() {
        fadeTarget = 1f;
        startFrames();
    }

    public void fadeOut() {
        fadeTarget = 0f;
        startFrames();
    }

    public boolean isFadingOutOrHidden() { return fadeTarget == 0f; }

    // ---------------- 布局 ----------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        rebuildDots(w, h);
    }

    private void rebuildDots(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float minDim = Math.min(w, h);
        int n = Math.max(3, dotCount);
        java.util.ArrayList<float[]> pts = new java.util.ArrayList<>();

        if (layout == Prefs.LAYOUT_GRID) {
            float spacing = minDim / (n * 0.55f);
            int cols = Math.max(2, Math.round(w / spacing));
            int rows = Math.max(2, Math.round(h / spacing));
            float sx = (float) w / cols, sy = (float) h / rows;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    float off = (r % 2 == 0) ? 0.25f : 0.75f;
                    pts.add(new float[]{(c + off) * sx, (r + 0.5f) * sy});
                }
            }
        } else {
            // 左右两侧各两列，交错排列（参考 iOS 的边缘点阵）
            float margin = minDim * 0.05f;
            float colGap = minDim * 0.055f;
            float top = h * 0.10f, bottom = h * 0.90f;
            float spacing = (bottom - top) / (n - 1);
            for (int i = 0; i < n; i++) {
                float y = top + i * spacing;
                pts.add(new float[]{margin, y});
                pts.add(new float[]{w - margin, y});
                if (i < n - 1) {
                    float y2 = y + spacing / 2f;
                    pts.add(new float[]{margin + colGap, y2});
                    pts.add(new float[]{w - margin - colGap, y2});
                }
            }
            if (layout == Prefs.LAYOUT_FRAME) {
                float left = margin + colGap * 2.2f, right = w - left;
                int m = Math.max(2, Math.round((right - left) / spacing));
                float sp = (right - left) / m;
                for (int i = 0; i <= m; i++) {
                    float x = left + i * sp;
                    pts.add(new float[]{x, h * 0.045f});
                    pts.add(new float[]{x, h - h * 0.045f});
                }
            }
        }
        float[] bx = new float[pts.size()], by = new float[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            bx[i] = pts.get(i)[0];
            by[i] = pts.get(i)[1];
        }
        baseX = bx;
        baseY = by;
        invalidate();
    }

    // ---------------- 目标位移 ----------------

    private float maxDisp() {
        return dp(58f) * (float) Math.sqrt(Math.max(0.25f, sensitivity));
    }

    /** 车辆运动 → 圆点位移（像车里没固定的小球：加速往后、刹车往前、左转往右）。 */
    private float targetX() {
        float g = dp(18f) * sensitivity;     // px / (m/s²)
        float yg = dp(80f) * sensitivity;    // px / (rad/s)
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
    public void onSensorSample() {
        if (!idle) return;
        float tx = targetX(), ty = targetY();
        float dx = tx - idleTx, dy = ty - idleTy;
        if (dx * dx + dy * dy > 0.25f) {
            idle = false;
            main.post(startFrames);
        }
    }

    // ---------------- 逐帧动画 ----------------

    private void startFrames() {
        idle = false;
        LiveStats.animIdle = false;
        if (!running && isAttachedToWindow()) {
            running = true;
            lastFrameNs = 0;
            choreographer.postFrameCallback(this);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startFrames();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        choreographer.removeFrameCallback(this);
        running = false;
        idle = true;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        running = false;
        float dt;
        if (lastFrameNs == 0) {
            dt = 1f / 60f;
        } else {
            dt = (frameTimeNanos - lastFrameNs) * 1e-9f;
            if (dt > 0f && dt < 0.2f) {
                float fps = 1f / dt;
                fpsEma = fpsEma == 0 ? fps : fpsEma + (fps - fpsEma) * 0.1f;
                LiveStats.measuredFps = fpsEma;
            }
            dt = MotionEngine.clamp(dt, 0f, 0.1f);
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
            vx += ax * h; vy += ay * h;
            px += vx * h; py += vy * h;
            rem -= h;
        }

        // 淡入淡出 ~350ms
        float fs = dt / 0.35f;
        if (fade < fadeTarget) fade = Math.min(fadeTarget, fade + fs);
        else if (fade > fadeTarget) fade = Math.max(fadeTarget, fade - fs);

        invalidate();

        boolean settled = Math.abs(tx - px) < 0.3f && Math.abs(ty - py) < 0.3f
                && Math.abs(vx) < 2f && Math.abs(vy) < 2f && fade == fadeTarget
                && !engine.demoActive();

        if (fade == 0f && fadeTarget == 0f) {
            idle = true;
            LiveStats.animIdle = true;
            if (callback != null) callback.onFadedOut();
            return;
        }
        if (settled) {
            // 停止逐帧回调，等待传感器唤醒
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

    @Override
    protected void onDraw(Canvas canvas) {
        if (fade <= 0f) return;
        float r = dotRadiusPx;
        int fa = Math.round(255 * fade);
        fill.setAlpha(fa);
        boolean drawStroke = colorMode == Prefs.COLOR_AUTO;
        if (drawStroke) stroke.setAlpha(Math.round(0xCC * fade));
        float ox = px, oy = py;
        float[] bx = baseX, by = baseY;
        for (int i = 0; i < bx.length; i++) {
            float x = bx[i] + ox, y = by[i] + oy;
            canvas.drawCircle(x, y, r, fill);
            if (drawStroke) canvas.drawCircle(x, y, r, stroke);
        }
    }
}
