package com.motioncue.app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/** 负责把圆点悬浮窗挂到 / 移出 WindowManager。 */
public final class OverlayController implements CueOverlayView.Callback {

    private final Context ctx;
    private final WindowManager wm;
    private final CueOverlayView view;
    private final WindowManager.LayoutParams lp;
    private boolean attached;

    public OverlayController(Context ctx, MotionEngine engine) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.view = new CueOverlayView(ctx, engine);
        view.setCallback(this);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("MotionCues");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        applySettings();
    }

    public CueOverlayView view() { return view; }

    public boolean isShown() { return attached && !view.isFadingOutOrHidden(); }

    public void applySettings() {
        view.applySettings(ctx);

        // Android 12+：不可触摸的悬浮窗透明度必须 ≤ 0.8，否则系统会拦截下层应用的触摸
        float alpha = Prefs.opacity(ctx);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            InputManager im = ctx.getSystemService(InputManager.class);
            if (im != null) alpha = Math.min(alpha, im.getMaximumObscuringOpacityForTouch());
        }
        lp.alpha = alpha;

        applyRefreshPolicy();

        if (attached) {
            try {
                wm.updateViewLayout(view, lp);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** 根据设置向系统投票刷新率。物理模拟本身与刷新率无关。 */
    private void applyRefreshPolicy() {
        int policy = Prefs.refresh(ctx);
        lp.preferredDisplayModeId = 0;
        lp.preferredRefreshRate = 0;
        Display d = ctx.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null || policy == Prefs.REFRESH_FOLLOW) {
            setViewFrameRate(policy);
            return;
        }
        Display.Mode cur = d.getMode();
        Display.Mode best = null;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth()
                    || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            if (best == null) {
                best = m;
            } else if (policy == Prefs.REFRESH_MAX) {
                if (m.getRefreshRate() > best.getRefreshRate()) best = m;
            } else {
                if (Math.abs(m.getRefreshRate() - 60f) < Math.abs(best.getRefreshRate() - 60f)) best = m;
            }
        }
        if (best != null) {
            lp.preferredDisplayModeId = best.getModeId();
            lp.preferredRefreshRate = best.getRefreshRate();
        }
        setViewFrameRate(policy);
    }

    private void setViewFrameRate(int policy) {
        if (Build.VERSION.SDK_INT >= 35) {
            float cat;
            switch (policy) {
                case Prefs.REFRESH_MAX: cat = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH; break;
                case Prefs.REFRESH_60: cat = View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL; break;
                default: cat = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT; break;
            }
            view.setRequestedFrameRate(cat);
        }
    }

    public void show() {
        if (!attached) {
            try {
                wm.addView(view, lp);
                attached = true;
            } catch (RuntimeException e) {
                // 没有悬浮窗权限等
                attached = false;
                LiveStats.overlayShown = false;
                return;
            }
        }
        view.fadeIn();
        LiveStats.overlayShown = true;
    }

    public void hide() {
        if (attached) view.fadeOut();
        LiveStats.overlayShown = false;
    }

    public void removeNow() {
        if (attached) {
            try {
                wm.removeViewImmediate(view);
            } catch (RuntimeException ignored) {
            }
            attached = false;
        }
        LiveStats.overlayShown = false;
    }

    @Override
    public void onFadedOut() {
        // 完全淡出后移除窗口：让系统可以把刷新率降下来，更省电
        view.post(() -> {
            if (view.isFadingOutOrHidden()) removeNow();
        });
    }
}
