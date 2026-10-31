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
            openApp();
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
        MotionService.sync(this);
        refresh();
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
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
