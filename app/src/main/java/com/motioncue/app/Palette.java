package com.motioncue.app;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/** 界面配色：跟随系统深浅色；Android 12+ 使用系统动态主题色（Material You）。 */
final class Palette {
    final boolean dark;
    final int bg, surface, surfaceVariant, text, textSecondary, divider, accent, onAccent, accentSoft, warning;

    private Palette(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            bg = 0xFF0F1013;
            surface = 0xFF1B1D22;
            surfaceVariant = 0xFF272A31;
            text = 0xFFF3F4F6;
            textSecondary = 0xFF9DA3AE;
            divider = 0xFF2C2F36;
            warning = 0xFFFFB454;
        } else {
            bg = 0xFFF2F3F6;
            surface = 0xFFFFFFFF;
            surfaceVariant = 0xFFECEEF2;
            text = 0xFF14161A;
            textSecondary = 0xFF6B7280;
            divider = 0xFFE6E8EC;
            warning = 0xFFB45309;
        }
        accent = accentColor(c, dark);
        // 深色模式的主题色是浅色调，上面的文字要用深色，否则对比度不够
        onAccent = dark ? onAccentDark(c) : 0xFFFFFFFF;
        accentSoft = (accent & 0x00FFFFFF) | (dark ? 0x33000000 : 0x1F000000);
    }

    static Palette of(Context c) {
        return new Palette(c);
    }

    /** 主题色：Android 12+ 取系统动态色，否则用默认蓝色 */
    static int accentColor(Context c, boolean dark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return c.getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
            } catch (RuntimeException ignored) {
            }
        }
        return dark ? 0xFF8AB4FF : 0xFF2F6BFF;
    }

    private static int onAccentDark(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return c.getColor(android.R.color.system_accent1_900);
            } catch (RuntimeException ignored) {
            }
        }
        return 0xFF0B1A33;
    }

    /** 圆点用的主题色：取中间色调，在亮、暗内容上都清楚 */
    static int dotAccent(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return c.getColor(android.R.color.system_accent1_300);
            } catch (RuntimeException ignored) {
            }
        }
        return 0xFF6C9BFF;
    }
}
