package com.motioncue.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** 纯代码构建界面用的小组件：卡片、分段选择器、设置行、滑块、按钮、折叠区块。 */
final class Ui {

    interface IntSink { void put(int v); }
    interface IntFmt { String fmt(int v); }

    final Context ctx;
    final Palette pal;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    Ui(Context ctx, Palette pal) {
        this.ctx = ctx;
        this.pal = pal;
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** 带按压水波纹的圆角背景 */
    Drawable pressable(int color, float radiusDp) {
        int ripple = pal.dark ? 0x33FFFFFF : 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), rounded(color, radiusDp), rounded(0xFFFFFFFF, radiusDp));
    }

    // ---------------- 文字 ----------------

    TextView text(CharSequence s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.18f);
        return t;
    }

    TextView title(CharSequence s, float sp) {
        TextView t = text(s, sp, pal.text);
        t.setTypeface(medium);
        return t;
    }

    TextView secondary(CharSequence s) {
        return text(s, 13.5f, pal.textSecondary);
    }

    /** 卡片外的小节标题 */
    TextView sectionHeader(String s) {
        TextView t = text(s, 13f, pal.textSecondary);
        t.setTypeface(medium);
        t.setLetterSpacing(0.04f);
        t.setPadding(dp(6), dp(22), dp(6), dp(8));
        return t;
    }

    // ---------------- 容器 ----------------

    LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setBackground(rounded(pal.surface, 22));
        return c;
    }

    View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(pal.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    View spacer(float dpHeight) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dpHeight)));
        return v;
    }

    // ---------------- 设置行 ----------------

    static final class Row {
        LinearLayout view;
        TextView title, subtitle;
    }

    /** 左侧标题 + 副标题，右侧任意控件 */
    Row row(String title, String subtitle, View trailing) {
        Row r = new Row();
        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setMinimumHeight(dp(48));
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        r.title = title(title, 16f);
        texts.addView(r.title);
        r.subtitle = secondary(subtitle == null ? "" : subtitle);
        r.subtitle.setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
        r.subtitle.setPadding(0, dp(2), 0, 0);
        texts.addView(r.subtitle);
        h.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(12);
            h.addView(trailing, lp);
        }
        r.view = h;
        return r;
    }

    Switch toggle(boolean checked) {
        Switch s = new Switch(ctx);
        s.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        // 打开：主题色滑块 + 主题色轨道；关闭：浅色滑块 + 明显的灰色轨道（白卡片上也看得清）
        s.setThumbTintList(new ColorStateList(states, new int[]{pal.accent, pal.dark ? 0xFFC4C7CE : 0xFFF4F5F7}));
        s.setTrackTintList(new ColorStateList(states, new int[]{pal.accent, pal.dark ? 0xFF7A808A : 0xFF5F656E}));
        s.setShowText(false);
        return s;
    }

    // ---------------- 按钮 ----------------

    /** 实心主按钮 */
    TextView primaryButton(String s, View.OnClickListener l) {
        TextView b = text(s, 15f, pal.onAccent);
        b.setTypeface(medium);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(11), dp(18), dp(11));
        b.setBackground(pressable(pal.accent, 24));
        b.setOnClickListener(l);
        return b;
    }

    /** 浅色调按钮 */
    TextView tonalButton(String s, View.OnClickListener l) {
        TextView b = text(s, 15f, pal.accent);
        b.setTypeface(medium);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(11), dp(18), dp(11));
        b.setBackground(pressable(pal.accentSoft, 24));
        b.setOnClickListener(l);
        return b;
    }

    // ---------------- 分段选择器 ----------------

    final class Segmented extends LinearLayout {
        private final TextView[] items;
        private int selected = -1;
        private IntSink listener;

        Segmented(String[] labels, int initial, IntSink l) {
            super(ctx);
            setOrientation(HORIZONTAL);
            setPadding(dp(3), dp(3), dp(3), dp(3));
            setBackground(rounded(pal.surfaceVariant, 14));
            items = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                TextView t = text(labels[i], 14f, pal.textSecondary);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(dp(6), dp(8), dp(6), dp(8));
                t.setOnClickListener(v -> {
                    if (!isEnabled()) return;
                    select(idx, true);
                });
                addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                items[i] = t;
            }
            select(initial, false);
            listener = l;
        }

        void select(int idx, boolean notify) {
            if (idx < 0 || idx >= items.length) return;
            boolean changed = idx != selected;
            if (!changed) return;
            selected = idx;
            for (int i = 0; i < items.length; i++) {
                boolean on = i == idx;
                items[i].setTextColor(on ? pal.text : pal.textSecondary);
                items[i].setTypeface(on ? medium : Typeface.DEFAULT);
                items[i].setBackground(on ? rounded(pal.dark ? 0xFF3A3E47 : 0xFFFFFFFF, 11) : null);
                items[i].setElevation(on && !pal.dark ? dp(1) : 0);
            }
            if (notify && listener != null) listener.put(idx);
        }

        int selected() { return selected; }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.45f);
        }
    }

    Segmented segmented(String[] labels, int selected, IntSink l) {
        return new Segmented(labels, selected, l);
    }

    // ---------------- 滑块 ----------------

    /** 标题在左、当前值在右，下方是滑块。拖动中只更新数值，松手才保存。 */
    LinearLayout slider(String name, int min, int max, int value, IntFmt fmt, IntSink sink) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(2));
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = title(name, 15f);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = text(fmt.fmt(value), 14f, pal.accent);
        val.setTypeface(medium);
        head.addView(val);
        box.addView(head);

        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        sb.setProgressTintList(ColorStateList.valueOf(pal.accent));
        sb.setThumbTintList(ColorStateList.valueOf(pal.accent));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(pal.dark ? 0xFF5A5F69 : 0xFFB9BEC6));
        sb.setPadding(dp(4), dp(10), dp(4), dp(6));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean tracking;

            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText(fmt.fmt(p + min));
                // 拖动中只更新数值；键盘 / 无障碍调整没有拖动过程，直接保存
                if (fromUser && !tracking) sink.put(p + min);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                tracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                tracking = false;
                sink.put(s.getProgress() + min);
            }
        });
        box.addView(sb, matchWrap());
        return box;
    }

    // ---------------- 折叠区块 ----------------

    /** 点击标题展开 / 收起内容 */
    LinearLayout collapsible(String title, String subtitle, View content) {
        LinearLayout c = card();
        TextView chevron = text("›", 22f, pal.textSecondary);
        chevron.setRotation(90f);
        Row r = row(title, subtitle, chevron);
        r.view.setBackground(pressable(pal.surface, 14));
        c.addView(r.view, matchWrap());
        content.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        c.addView(content, lp);
        r.view.setOnClickListener(v -> {
            boolean show = content.getVisibility() != View.VISIBLE;
            content.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(show ? 270f : 90f).setDuration(180).start();
        });
        return c;
    }
}
