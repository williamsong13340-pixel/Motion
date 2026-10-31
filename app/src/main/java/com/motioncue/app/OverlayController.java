package com.motioncue.app;

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责把圆点悬浮窗挂到 / 移出 WindowManager。
 *
 * 「两侧」「四周」布局只在屏幕边缘放几个互不重叠的窄条窗口，屏幕中间没有任何悬浮窗：
 * 其它 App 中间区域的触摸不会被系统标记为「被遮挡」（银行/支付类密码键盘常会拒收被遮挡的触摸）。
 * 窗口互不重叠也保证了 Android 12+ 的触摸穿透不透明度限制（≤ 0.8）不会因叠加而超标。
 */
public final class OverlayController implements CueField.Callback {

    private final Context ctx;
    private final WindowManager wm;
    private final CueField field;
    private final ArrayList<CueOverlayView> views = new ArrayList<>();
    private final ArrayList<Rect> rects = new ArrayList<>();
    private boolean attached;
    private int screenW, screenH;

    /**
     * 旋转时 DisplayListener 和窗口上下文的配置更新到达顺序不确定，两边都监听，
     * 尺寸统一从 Display（与 getRotation() 同一份 DisplayInfo）读取。
     */
    private int uiMode;

    private final ComponentCallbacks configCallbacks = new ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            int night = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (night != uiMode) {
                // 系统深浅色切换：「自动」配色的圆点跟着换
                uiMode = night;
                field.applySettings();
            }
            onDisplayChanged();
        }

        @Override
        public void onLowMemory() {}
    };

    public OverlayController(Context ctx, MotionEngine engine) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.field = new CueField(ctx, engine);
        field.setCallback(this);
        updateScreenSize();
        uiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        field.applySettings();
        ctx.registerComponentCallbacks(configCallbacks);
    }

    /** 服务销毁时调用 */
    public void release() {
        removeNow();
        try {
            ctx.unregisterComponentCallbacks(configCallbacks);
        } catch (RuntimeException ignored) {
        }
    }

    public CueField field() { return field; }

    public boolean isShown() { return attached && !field.isFadingOutOrHidden(); }

    /**
     * 设置变化：重新计算几何。
     * @param rebuild 影响窗口尺寸 / 透明度 / 刷新率投票的设置需要重建窗口；灵敏度之类的不需要
     */
    public void applySettings(boolean rebuild) {
        field.applySettings();
        if (!rebuild || !attached) return;
        if (field.windowRects().equals(rects)) {
            // 窗口位置不变（例如只改了透明度 / 刷新率）：原地更新，不闪烁
            float alpha = windowAlpha();
            for (int i = 0; i < views.size(); i++) {
                CueOverlayView v = views.get(i);
                applyViewFrameRate(v);
                try {
                    wm.updateViewLayout(v, layoutParams(rects.get(i), alpha));
                } catch (RuntimeException ignored) {
                }
                v.invalidate();
            }
        } else {
            rebuildWindows();
        }
    }

    /** 屏幕旋转 / 分辨率变化 */
    public void onDisplayChanged() {
        if (updateScreenSize() && attached) rebuildWindows();
    }

    @SuppressWarnings("deprecation")
    private boolean updateScreenSize() {
        // 与 MotionService 里 getRotation() 读的是同一份 DisplayInfo，旋转时两者一致
        // （窗口上下文的 WindowMetrics 可能比 DisplayListener 回调晚一步更新）
        Display d = defaultDisplay();
        if (d == null) return false;
        Point p = new Point();
        d.getRealSize(p);
        int w = p.x, h = p.y;
        if (w <= 0 || h <= 0) return false;
        boolean changed = w != screenW || h != screenH;
        screenW = w;
        screenH = h;
        field.setScreenSize(w, h);
        return changed;
    }

    private Display defaultDisplay() {
        return ctx.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
    }

    // ---------------- 显隐 ----------------

    public void show() {
        if (!attached) {
            updateScreenSize();
            field.resetMotion();
            if (!addWindows()) {
                LiveStats.overlayShown = false;
                return;
            }
            attached = true;
        }
        field.fadeIn();
        LiveStats.overlayShown = true;
    }

    public void hide() {
        if (attached) field.fadeOut();
        LiveStats.overlayShown = false;
    }

    public void removeNow() {
        removeWindows();
        attached = false;
        LiveStats.overlayShown = false;
    }

    @Override
    public void onFadedOut() {
        // 完全淡出后移除窗口：让系统可以把刷新率降下来，更省电
        if (field.isFadingOutOrHidden()) removeNow();
    }

    // ---------------- 窗口管理 ----------------

    private boolean addWindows() {
        List<Rect> rects = field.windowRects();
        if (rects.isEmpty()) return false;
        float alpha = windowAlpha();
        for (Rect r : rects) {
            CueOverlayView v = new CueOverlayView(ctx, field);
            v.setOrigin(r.left, r.top);
            applyViewFrameRate(v);
            try {
                wm.addView(v, layoutParams(r, alpha));
            } catch (RuntimeException e) {
                // 没有悬浮窗权限等
                removeWindows();
                return false;
            }
            views.add(v);
        }
        this.rects.clear();
        this.rects.addAll(rects);
        return true;
    }

    private void removeWindows() {
        for (CueOverlayView v : views) {
            try {
                wm.removeViewImmediate(v);
            } catch (RuntimeException ignored) {
            }
        }
        views.clear();
        rects.clear();
    }

    /** 几何或布局改变时整体替换窗口（只在旋转 / 改设置时发生）。圆点的位移和淡入状态保留。 */
    private void rebuildWindows() {
        removeWindows();
        if (!addWindows()) {
            attached = false;
            LiveStats.overlayShown = false;
        }
    }

    private WindowManager.LayoutParams layoutParams(Rect r, float alpha) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                r.width(), r.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = r.left;
        lp.y = r.top;
        lp.setTitle("MotionCues");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.alpha = alpha;
        applyRefreshPolicy(lp);
        return lp;
    }

    /** Android 12+：不可触摸的悬浮窗透明度必须 ≤ 0.8，否则系统会拦截下层应用的触摸 */
    private float windowAlpha() {
        float alpha = Prefs.opacity(ctx);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            InputManager im = ctx.getSystemService(InputManager.class);
            if (im != null) alpha = Math.min(alpha, im.getMaximumObscuringOpacityForTouch());
        }
        return alpha;
    }

    /** 根据设置向系统投票刷新率。物理模拟本身与刷新率无关。 */
    private void applyRefreshPolicy(WindowManager.LayoutParams lp) {
        int policy = Prefs.refresh(ctx);
        lp.preferredDisplayModeId = 0;
        lp.preferredRefreshRate = 0;
        if (policy == Prefs.REFRESH_FOLLOW) return;
        Display d = defaultDisplay();
        if (d == null) return;
        Display.Mode cur = d.getMode();
        Display.Mode best = null;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth()
                    || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            if (best == null) {
                best = m;
            } else if (policy == Prefs.REFRESH_MAX) {
                if (m.getRefreshRate() > best.getRefreshRate()) best = m;
            } else if (Math.abs(m.getRefreshRate() - 60f) < Math.abs(best.getRefreshRate() - 60f)) {
                best = m;
            }
        }
        if (best != null) {
            lp.preferredDisplayModeId = best.getModeId();
            lp.preferredRefreshRate = best.getRefreshRate();
        }
    }

    private void applyViewFrameRate(View v) {
        if (Build.VERSION.SDK_INT >= 35) {
            float cat;
            switch (Prefs.refresh(ctx)) {
                case Prefs.REFRESH_MAX: cat = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH; break;
                case Prefs.REFRESH_60: cat = View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL; break;
                default: cat = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT; break;
            }
            v.setRequestedFrameRate(cat);
        }
    }
}
