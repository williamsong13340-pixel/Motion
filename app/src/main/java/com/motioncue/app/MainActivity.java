package com.motioncue.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;
import java.util.TreeSet;

/** 主界面：权限、模式、外观设置、实时状态（传感器 / 刷新率 / 乘车检测）。 */
public final class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView statusText;
    private TextView liveText;
    private Button overlayBtn, notifBtn, stepBtn, batteryBtn;
    private RadioGroup modeGroup;
    private boolean suppressModeEvents;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateLive();
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionState();
        syncModeRadio();
        if (Prefs.mode(this) != Prefs.MODE_OFF && Settings.canDrawOverlays(this)) {
            MotionService.start(this, MotionService.ACTION_APPLY);
        }
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    // =============================== UI ===============================

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(40));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("乘车动态提示");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = text("在屏幕边缘显示随车辆运动而移动的圆点，让眼睛看到的运动和内耳感受到的一致，减轻看手机时的晕车。", 14);
        sub.setAlpha(0.75f);
        root.addView(sub);

        // ---------- 状态 / 权限 ----------
        LinearLayout perm = card("权限");
        statusText = text("", 14);
        perm.addView(statusText);
        overlayBtn = button("授予悬浮窗权限（必需）", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        perm.addView(overlayBtn);
        notifBtn = button("允许通知（显示常驻开关）", v -> {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
        });
        perm.addView(notifBtn);
        stepBtn = button("允许「身体活动」（区分走路和乘车，可选）", v -> {
            if (Build.VERSION.SDK_INT >= 29) requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ_PERMS);
        });
        perm.addView(stepBtn);
        batteryBtn = button("不限制后台耗电（防止被系统杀掉）", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        perm.addView(batteryBtn);
        perm.addView(button("打开应用详情（ColorOS：耗电管理 → 允许后台/自启动）", v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())))));

        // ---------- 模式 ----------
        LinearLayout modeCard = card("模式");
        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        addRadio(modeGroup, Prefs.MODE_OFF, "关闭");
        addRadio(modeGroup, Prefs.MODE_AUTO, "自动（检测到乘车时显示，实验性）");
        addRadio(modeGroup, Prefs.MODE_ALWAYS, "始终显示");
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            if (suppressModeEvents) return;
            View v = g.findViewById(id);
            if (v == null) return;
            int mode = (Integer) v.getTag();
            if (mode != Prefs.MODE_OFF && !Settings.canDrawOverlays(this)) {
                overlayBtn.performClick();
            } else if (mode == Prefs.MODE_AUTO && Build.VERSION.SDK_INT >= 29
                    && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
                // 自动模式靠计步器区分走路和乘车，最准确
                requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ_PERMS);
            }
            Prefs.setMode(this, mode);
            MotionService.sync(this);
        });
        modeCard.addView(modeGroup);
        modeCard.addView(button("演示 12 秒（模拟加速/刹车/转弯，在家测试用）", v -> {
            if (!Settings.canDrawOverlays(this)) {
                overlayBtn.performClick();
                return;
            }
            MotionService.start(this, MotionService.ACTION_DEMO);
        }));
        TextView tip = text("提示：也可以在下拉控制中心添加「防晕车圆点」快捷开关，点一下切换 关闭 → 始终显示 → 自动。", 13);
        tip.setAlpha(0.7f);
        modeCard.addView(tip);

        // ---------- 外观 ----------
        SharedPreferences sp = Prefs.sp(this);
        LinearLayout look = card("外观与灵敏度");
        addSlider(look, "灵敏度", 25, 300, Math.round(Prefs.sensitivity(this) * 100),
                p -> String.format(Locale.US, "%.2f×", p / 100f),
                p -> sp.edit().putFloat(Prefs.K_SENS, p / 100f).apply());
        addSlider(look, "圆点大小", 20, 100, Math.round(Prefs.dotSize(this) * 10),
                p -> String.format(Locale.US, "%.1f dp", p / 10f),
                p -> sp.edit().putFloat(Prefs.K_SIZE, p / 10f).apply());
        addSlider(look, "圆点数量（每列）", 4, 16, Prefs.dotCount(this),
                p -> p + " 个",
                p -> sp.edit().putInt(Prefs.K_COUNT, p).apply());
        addSlider(look, "不透明度", 20, 100, Math.round(Prefs.opacity(this) * 100),
                p -> p + "%" + (Build.VERSION.SDK_INT >= 31 && p > 80 ? "（系统限制最高 80%，否则会挡住触摸）" : ""),
                p -> sp.edit().putFloat(Prefs.K_OPACITY, p / 100f).apply());

        look.addView(label("布局"));
        look.addView(choice(new String[]{"两侧", "四周", "全屏网格"}, Prefs.layout(this),
                i -> sp.edit().putInt(Prefs.K_LAYOUT, i).apply()));
        look.addView(label("颜色"));
        look.addView(choice(new String[]{"自动（白点黑边）", "白色", "黑色"}, Prefs.color(this),
                i -> sp.edit().putInt(Prefs.K_COLOR, i).apply()));

        Switch yaw = new Switch(this);
        yaw.setText("转弯时使用陀螺仪（低速转弯也有反馈）");
        yaw.setChecked(Prefs.yawCue(this));
        yaw.setOnCheckedChangeListener((CompoundButton b, boolean c) -> sp.edit().putBoolean(Prefs.K_YAW, c).apply());
        yaw.setPadding(0, dp(10), 0, dp(4));
        look.addView(yaw);

        // ---------- 刷新率 ----------
        LinearLayout hz = card("刷新率");
        hz.addView(text("动画由屏幕 VSYNC 逐帧驱动，自动跟随当前刷新率（60/90/120Hz 以及 LTPO 动态变化）。"
                + "运动按真实帧间隔计算，所以刷新率变化时速度不变，只是越高越顺滑。圆点静止时会暂停逐帧绘制，让屏幕可以降频省电。", 13));
        hz.addView(choice(new String[]{"跟随系统", "尽量最高", "60Hz 省电"}, Prefs.refresh(this),
                i -> sp.edit().putInt(Prefs.K_REFRESH, i).apply()));

        Switch boot = new Switch(this);
        boot.setText("开机后自动恢复");
        boot.setChecked(Prefs.startOnBoot(this));
        boot.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean(Prefs.K_BOOT, c).apply());
        boot.setPadding(0, dp(10), 0, dp(4));
        hz.addView(boot);

        // ---------- 实时 ----------
        LinearLayout live = card("实时数据");
        liveText = text("", 13);
        liveText.setTypeface(Typeface.MONOSPACE);
        live.addView(liveText);

        // ---------- 说明 ----------
        LinearLayout about = card("原理与说明");
        TextView a = text(ABOUT, 13);
        a.setMovementMethod(LinkMovementMethod.getInstance());
        about.addView(a);
    }

    private static final String ABOUT =
            "• 原理：晕动症主要来自「感觉冲突」——内耳前庭感到加减速和转弯，但眼睛盯着静止的屏幕。"
                    + "圆点像车里没有固定的小球：加速时往后（下）滑，刹车时往前（上）滑，左转时往右、右转时往左，"
                    + "用余光就能看到与身体感受一致的运动。\n\n"
                    + "• 传感器：使用「游戏旋转矢量」（陀螺仪+加速度计融合，不用磁力计，不受车内磁场干扰）估计重力方向，"
                    + "把加速度投影到水平面得到车辆加速度；陀螺仪给出转弯角速度。手机平放、斜拿、竖拿、横屏都会自动换算。\n\n"
                    + "• 滤波：晕车最敏感的频段约 0.1–0.5Hz，车辆操控通常低于 1Hz，所以用 1.2Hz 低通去掉路面颠簸和手抖；"
                    + "自适应去偏消除传感器零漂；检测到你在晃动/转动手机时会暂时压低反馈。\n\n"
                    + "• 自动模式：国行系统没有 Google 的活动识别，这里用振动、步频、车辆操控三个特征自行判断，"
                    + "约 15 秒确认上车，走路会很快退出。如不准确请用「始终显示」或快捷开关。\n\n"
                    + "• 限制：普通应用的悬浮窗无法盖在锁屏和部分系统界面上；Android 12+ 悬浮窗不透明度最高 80%。"
                    + "仅供乘客使用，驾驶时请勿看手机。\n\n"
                    + "• 参考：Apple iOS 18「车辆运动提示」；Google Android「Motion Assist」；"
                    + "Hanau & Popescu, MotionReader (AutomotiveUI 2017)；Karjanto 等，周边视觉信息系统与自动驾驶晕动症 "
                    + "(Transportation Research Part F, 2018)；Reason & Brand 感觉冲突理论 (1975)；"
                    + "Golding, 晕动症频率特性 (ISO 2631 / 2006)。";

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        bg.setColor(night ? 0x14FFFFFF : 0x0D000000);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        root.addView(c, lp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(6));
        c.addView(t);
        return c;
    }

    private TextView text(String s, float size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14);
        t.setPadding(0, dp(10), 0, 0);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void addRadio(RadioGroup g, int id, String text) {
        RadioButton rb = new RadioButton(this);
        rb.setId(View.generateViewId());
        // 界面每次都按当前设置重建；不让系统在进程重建后恢复旧的勾选状态（会把模式改回旧值）
        rb.setSaveEnabled(false);
        rb.setText(text);
        rb.setTag(id);
        rb.setPadding(dp(4), dp(6), 0, dp(6));
        g.addView(rb);
    }

    interface IntFmt { String fmt(int p); }
    interface IntSink { void put(int p); }

    private void addSlider(LinearLayout parent, String name, int min, int max, int value, IntFmt fmt, IntSink sink) {
        TextView t = label(name + "：" + fmt.fmt(value));
        parent.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                t.setText(name + "：" + fmt.fmt(p + min));
                if (fromUser) sink.put(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        parent.addView(sb);
    }

    private RadioGroup choice(String[] labels, int selected, IntSink sink) {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            ids[i] = View.generateViewId();
            rb.setId(ids[i]);
            rb.setSaveEnabled(false);
            rb.setText(labels[i]);
            rb.setPadding(0, 0, dp(12), 0);
            g.addView(rb);
        }
        g.check(ids[Math.max(0, Math.min(labels.length - 1, selected))]);
        g.setOnCheckedChangeListener((grp, id) -> {
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) sink.put(i);
        });
        return g;
    }

    // =============================== 状态 ===============================

    private int radioIdForMode(int mode) {
        for (int i = 0; i < modeGroup.getChildCount(); i++) {
            View v = modeGroup.getChildAt(i);
            if (Integer.valueOf(mode).equals(v.getTag())) return v.getId();
        }
        return View.NO_ID;
    }

    private void syncModeRadio() {
        suppressModeEvents = true;
        modeGroup.check(radioIdForMode(Prefs.mode(this)));
        suppressModeEvents = false;
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPermissionState() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS);
        boolean step = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        overlayBtn.setVisibility(overlay ? View.GONE : View.VISIBLE);
        notifBtn.setVisibility(notif ? View.GONE : View.VISIBLE);
        stepBtn.setVisibility(step ? View.GONE : View.VISIBLE);
        batteryBtn.setVisibility(battery ? View.GONE : View.VISIBLE);
        statusText.setText(
                (overlay ? "✓" : "✗") + " 悬浮窗\n"
                        + (notif ? "✓" : "✗") + " 通知\n"
                        + (step ? "✓" : "–") + " 身体活动（计步）\n"
                        + (battery ? "✓" : "–") + " 不限制后台耗电");
        statusText.setGravity(Gravity.START);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshPermissionState();
        if (Prefs.mode(this) != Prefs.MODE_OFF) MotionService.start(this, MotionService.ACTION_APPLY);
    }

    private String supportedRates(Display d) {
        TreeSet<Integer> set = new TreeSet<>();
        for (Display.Mode m : d.getSupportedModes()) set.add(Math.round(m.getRefreshRate()));
        StringBuilder sb = new StringBuilder();
        for (Integer r : set) {
            if (sb.length() > 0) sb.append('/');
            sb.append(r);
        }
        return sb.toString();
    }

    private void updateLive() {
        int want = radioIdForMode(Prefs.mode(this));
        if (modeGroup.getCheckedRadioButtonId() != want) syncModeRadio();
        DisplayManager dm = getSystemService(DisplayManager.class);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        String sensors = LiveStats.sensorInfo;
        if (sensors == null || sensors.isEmpty()) {
            sensors = MotionEngine.describeSensors(getSystemService(SensorManager.class));
            LiveStats.sensorInfo = sensors;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(sensors);
        if (d != null) {
            sb.append(String.format(Locale.US, "屏幕刷新率：当前 %.0fHz（支持 %sHz）\n",
                    d.getRefreshRate(), supportedRates(d)));
        }
        if (LiveStats.serviceRunning && LiveStats.overlayShown) {
            sb.append(LiveStats.animIdle ? "动画帧率：静止中（已暂停逐帧绘制）\n"
                    : String.format(Locale.US, "动画帧率：%.0f fps（实测）\n", LiveStats.measuredFps));
        }
        sb.append("\n服务：").append(LiveStats.serviceRunning ? "运行中" : "未运行")
                .append(" · 圆点：").append(LiveStats.overlayShown ? "显示" : "隐藏")
                .append(LiveStats.demoActive ? " · 演示中" : "").append('\n');
        if (LiveStats.serviceRunning) {
            sb.append(String.format(Locale.US,
                    "前后加速度 %+5.2f m/s²\n左右加速度 %+5.2f m/s²\n转弯角速度 %+5.2f rad/s\n手持抑制   %3.0f%%\n",
                    LiveStats.accelForward, LiveStats.accelRight, LiveStats.yawRate,
                    (1 - LiveStats.handlingGain) * 100));
            sb.append(String.format(Locale.US,
                    "\n乘车判断：%s（分数 %.1f / 进入≥8）\n振动 %.3f · 步频能量 %.2f · 步频周期 %.2f · 操控 %.2f",
                    LiveStats.inVehicle ? "在车上" : "不在车上", LiveStats.vehicleScore,
                    LiveStats.vibRms, LiveStats.walkRms, LiveStats.walkPeriodicity, LiveStats.motionRms));
        }
        liveText.setText(sb.toString());
    }
}
