package com.motioncue.app;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/**
 * 一个悬浮窗里的视图：只负责把共享的 CueField 中落在本窗口范围内的那部分点画出来。
 * 不使用硬件图层——内容每帧都在变，离屏图层只会多一次全尺寸的绘制。
 */
final class CueOverlayView extends View {

    private final CueField field;
    private int originX, originY;

    CueOverlayView(Context ctx, CueField field) {
        super(ctx);
        this.field = field;
    }

    /** 本窗口左上角在屏幕上的位置 */
    void setOrigin(int x, int y) {
        originX = x;
        originY = y;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        field.attach(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        field.detach(this);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        field.draw(canvas, originX, originY, getWidth(), getHeight());
    }
}
