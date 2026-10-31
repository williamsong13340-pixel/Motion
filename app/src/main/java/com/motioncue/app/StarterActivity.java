package com.motioncue.app;

import android.app.Activity;
import android.os.Bundle;

/**
 * 透明的「跳板」Activity：只在快捷开关无法从后台直接启动前台服务时使用
 * （Android 15 起，靠悬浮窗权限在后台启动前台服务要求当前已有可见的悬浮窗）。
 * Activity 可见的一瞬间应用处于前台，可以合法地启动服务，随后立即关闭。
 */
public final class StarterActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MotionService.sync(this);
        finish();
        overridePendingTransition(0, 0);
    }
}
