package com.motioncue.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有用户设置集中在这里。 */
public final class Prefs {
    public static final String FILE = "motioncue";

    public static final int MODE_OFF = 0;
    public static final int MODE_AUTO = 1;   // 检测到乘车时自动显示
    public static final int MODE_ALWAYS = 2; // 一直显示

    public static final int LAYOUT_SIDES = 0;  // 左右两侧（类似 iOS）
    public static final int LAYOUT_FRAME = 1;  // 四周一圈
    public static final int LAYOUT_GRID = 2;   // 全屏稀疏网格

    public static final int COLOR_AUTO = 0;  // 白点黑描边，任何背景都能看清
    public static final int COLOR_WHITE = 1;
    public static final int COLOR_BLACK = 2;

    public static final int REFRESH_FOLLOW = 0; // 跟随系统（LTPO 自动调节，省电）
    public static final int REFRESH_MAX = 1;    // 请求最高刷新率（最流畅）
    public static final int REFRESH_60 = 2;     // 请求 60Hz（最省电）

    public static final String K_MODE = "mode";
    public static final String K_SENS = "sensitivity";
    public static final String K_SIZE = "dot_size";
    public static final String K_COUNT = "dot_count";
    public static final String K_OPACITY = "opacity";
    public static final String K_LAYOUT = "layout";
    public static final String K_COLOR = "color";
    public static final String K_REFRESH = "refresh";
    public static final String K_YAW = "yaw_cue";
    public static final String K_BOOT = "start_on_boot";
    public static final String K_LAST_MODE = "last_on_mode";

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int mode(Context c) { return sp(c).getInt(K_MODE, MODE_OFF); }

    public static void setMode(Context c, int mode) {
        SharedPreferences.Editor e = sp(c).edit().putInt(K_MODE, mode);
        if (mode != MODE_OFF) e.putInt(K_LAST_MODE, mode);
        e.apply();
    }

    /** 灵敏度倍率 0.25 ~ 3.0 */
    public static float sensitivity(Context c) { return sp(c).getFloat(K_SENS, 1.0f); }
    /** 圆点半径 dp */
    public static float dotSize(Context c) { return sp(c).getFloat(K_SIZE, 4.5f); }
    /** 每侧每列的点数 */
    public static int dotCount(Context c) { return sp(c).getInt(K_COUNT, 9); }
    public static float opacity(Context c) { return sp(c).getFloat(K_OPACITY, 0.85f); }
    public static int layout(Context c) { return sp(c).getInt(K_LAYOUT, LAYOUT_SIDES); }
    public static int color(Context c) { return sp(c).getInt(K_COLOR, COLOR_AUTO); }
    public static int refresh(Context c) { return sp(c).getInt(K_REFRESH, REFRESH_FOLLOW); }
    public static boolean yawCue(Context c) { return sp(c).getBoolean(K_YAW, true); }
    public static boolean startOnBoot(Context c) { return sp(c).getBoolean(K_BOOT, true); }

    public static String modeName(int mode) {
        switch (mode) {
            case MODE_AUTO: return "自动";
            case MODE_ALWAYS: return "始终显示";
            default: return "关闭";
        }
    }
}
