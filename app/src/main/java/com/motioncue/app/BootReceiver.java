package com.motioncue.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后，如果之前开着就恢复服务。 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Prefs.startOnBoot(c) && Prefs.mode(c) != Prefs.MODE_OFF) {
            MotionService.start(c, MotionService.ACTION_APPLY);
        }
    }
}
