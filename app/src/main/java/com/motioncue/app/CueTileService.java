package com.motioncue.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 下拉快捷开关：关闭 → 始终显示 → 自动 → 关闭。长按进入设置。 */
public final class CueTileService extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        if (!Settings.canDrawOverlays(this)) {
            collapseAndStart(MainActivity.class);
            return;
        }
        int mode = Prefs.mode(this);
        int next;
        switch (mode) {
            case Prefs.MODE_OFF: next = Prefs.MODE_ALWAYS; break;
            case Prefs.MODE_ALWAYS: next = Prefs.MODE_AUTO; break;
            default: next = Prefs.MODE_OFF; break;
        }
        Prefs.setMode(this, next);
        // 关闭 / 在两种开启模式之间切换：运行中的服务会通过设置监听自己处理，无需再启动。
        // 从关闭到开启：尝试直接启动；被系统拒绝（Android 15 后台限制）就借助透明 Activity 启动。
        if (next != Prefs.MODE_OFF && !LiveStats.serviceRunning && !MotionService.sync(this)) {
            collapseAndStart(StarterActivity.class);
        }
        refresh();
    }

    private void collapseAndStart(Class<?> activity) {
        Intent i = new Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, activity == MainActivity.class ? 0 : 1, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        int mode = Prefs.mode(this);
        t.setState(mode == Prefs.MODE_OFF ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        t.setLabel(getString(R.string.tile_label));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t.setSubtitle(Prefs.modeName(mode));
        }
        t.updateTile();
    }
}
