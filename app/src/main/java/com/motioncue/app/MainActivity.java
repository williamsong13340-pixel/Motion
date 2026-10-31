package com.motioncue.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;
import java.util.TreeSet;

/** 主界面：实时预览、开关与模式、权限引导、外观与运动设置、诊断数据、原理说明。 */
public final class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Palette pal;
    private Ui ui;
    private LinearLayout content;
    private PreviewView preview;
    private Switch master;
    private Ui.Row masterRow;
    private Ui.Segmented modeSeg;
    private TextView modeHint;
    private LinearLayout permCard;
    private Ui.Row permOverlay, permNotif, permStep, permBattery;
    private TextView liveText;
    private boolean syncing;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateLive();
            syncModeUi();
            handler.postDelayed(this, 300);
        }
    };

    /** 设置变化（包括快捷开关、通知栏按钮改的模式）→ 刷新预览和开关状态 */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        preview.reload();
        syncModeUi();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pal = Palette.of(this);
        ui = new Ui(this, pal);
        setupWindow();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissions();
        syncModeUi();
        if (Prefs.mode(this) != Prefs.MODE_OFF && Settings.canDrawOverlays(this)) {
            MotionService.start(this, MotionService.ACTION_APPLY);
        }
        updatePreviewScreen();
        preview.reload();
        preview.setPlaying(true);
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        preview.setPlaying(false);
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener);
        handler.removeCallbacks(ticker);
    }

    // =============================== 窗口 ===============================

    /** 沉浸式：内容延伸到状态栏/导航栏下面，状态栏图标颜色跟随深浅色 */
    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Window w = getWindow();
        w.getDecorView().setBackgroundColor(pal.bg);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) w.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(pal.dark ? 0 : mask, mask);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!pal.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyInsets(WindowInsets insets) {
        int l, t, r, b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            l = i.left; t = i.top; r = i.right; b = i.bottom;
        } else {
            l = insets.getSystemWindowInsetLeft();
            t = insets.getSystemWindowInsetTop();
            r = insets.getSystemWindowInsetRight();
            b = insets.getSystemWindowInsetBottom();
        }
        content.setPadding(ui.dp(16) + l, ui.dp(14) + t, ui.dp(16) + r, ui.dp(28) + b);
    }

    @SuppressWarnings("deprecation")
    private void updatePreviewScreen() {
        int w, h;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect b = getWindowManager().getMaximumWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        } else {
            Point p = new Point();
            getWindowManager().getDefaultDisplay().getRealSize(p);
            w = p.x;
            h = p.y;
        }
        preview.setScreen(Math.min(w, h), Math.max(w, h));   // 预览始终画竖屏手机
    }

    // =============================== 界面 ===============================

    private LinearLayout.LayoutParams top(float dpMargin) {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = ui.dp(dpMargin);
        return lp;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(insets);
            return insets;
        });
        setContentView(scroll);
        SharedPreferences sp = Prefs.sp(this);

        // ---------- 标题 ----------
        TextView title = ui.title("乘车动态提示", 28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(ui.dp(4), ui.dp(8), 0, 0);
        content.addView(title);
        TextView sub = ui.secondary("让眼睛看到的运动和身体感受到的一致，减轻看手机时的晕车");
        sub.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), 0);
        content.addView(sub);

        // ---------- 预览 ----------
        LinearLayout pc = ui.card();
        pc.setPadding(0, ui.dp(8), 0, ui.dp(16));
        preview = new PreviewView(this, pal);
        pc.addView(preview, Ui.matchWrap());
        TextView cap = ui.secondary("加速时圆点往下、刹车往上，左转往右、右转往左；运动越强，圆点越大越多");
        cap.setGravity(Gravity.CENTER);
        cap.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), 0);
        pc.addView(cap, Ui.matchWrap());
        content.addView(pc, top(18));

        // ---------- 开关与模式 ----------
        LinearLayout mc = ui.card();
        master = ui.toggle(Prefs.mode(this) != Prefs.MODE_OFF);
        masterRow = ui.row("运动提示", "", master);
        mc.addView(masterRow.view, Ui.matchWrap());
        modeSeg = ui.segmented(new String[]{"自动检测乘车", "始终显示"},
                Prefs.lastOnMode(this) == Prefs.MODE_AUTO ? 0 : 1,
                i -> onModeChosen(i == 0 ? Prefs.MODE_AUTO : Prefs.MODE_ALWAYS));
        mc.addView(modeSeg, top(12));
        modeHint = ui.secondary("");
        modeHint.setPadding(ui.dp(2), 0, ui.dp(2), 0);
        mc.addView(modeHint, top(10));
        mc.addView(ui.tonalButton("演示 12 秒（模拟加速、转弯、刹车）", v -> demo()), top(14));
        master.setOnCheckedChangeListener((b, on) -> {
            if (!syncing) onMasterToggled(on);
        });
        content.addView(mc, top(14));

        // ---------- 权限（缺什么才显示什么） ----------
        permCard = ui.card();
        permCard.addView(ui.title("还需要几步设置", 16f));
        permOverlay = permRow("悬浮窗权限", "必需：在其它 App 上方显示圆点", "去开启", true, v -> openOverlaySettings());
        permNotif = permRow("通知", "显示常驻开关和快捷操作", "允许", false, v -> {
            if (Build.VERSION.SDK_INT >= 33) askOrOpenSettings(Manifest.permission.POST_NOTIFICATIONS, true);
        });
        permStep = permRow("身体活动", "用计步器区分走路和乘车，自动模式更准", "允许", false, v -> {
            if (Build.VERSION.SDK_INT >= 29) askOrOpenSettings(Manifest.permission.ACTIVITY_RECOGNITION, false);
        });
        permBattery = permRow("后台运行", "不限制耗电，防止被系统清理", "去设置", false, v -> openBatterySettings());
        content.addView(permCard, top(14));

        // ---------- 外观 ----------
        content.addView(ui.sectionHeader("外观"));
        LinearLayout ac = ui.card();
        ac.addView(ui.title("布局", 15f));
        ac.addView(ui.segmented(new String[]{"两侧", "四周", "网格"}, Prefs.layout(this),
                i -> sp.edit().putInt(Prefs.K_LAYOUT, i).apply()), top(8));
        ac.addView(ui.title("颜色", 15f), top(16));
        ac.addView(ui.segmented(new String[]{"自动", "白色", "黑色", "主题色"}, Prefs.color(this),
                i -> sp.edit().putInt(Prefs.K_COLOR, i).apply()), top(8));
        ac.addView(ui.divider());
        ac.addView(ui.slider("灵敏度", 25, 300, Math.round(Prefs.sensitivity(this) * 100),
                p -> String.format(Locale.US, "%.2f×", p / 100f),
                p -> sp.edit().putFloat(Prefs.K_SENS, p / 100f).apply()), Ui.matchWrap());
        ac.addView(ui.slider("圆点大小（运动时）", 20, 100, Math.round(Prefs.dotSize(this) * 10),
                p -> String.format(Locale.US, "%.1f dp", p / 10f),
                p -> sp.edit().putFloat(Prefs.K_SIZE, p / 10f).apply()), Ui.matchWrap());
        ac.addView(ui.slider("圆点密度", 4, 16, Prefs.dotCount(this),
                p -> p <= 6 ? "稀疏" : (p <= 11 ? "适中" : "密集"),
                p -> sp.edit().putInt(Prefs.K_COUNT, p).apply()), Ui.matchWrap());
        ac.addView(ui.slider("不透明度", 20, 100, Math.round(Prefs.opacity(this) * 100),
                p -> p + "%",
                p -> sp.edit().putFloat(Prefs.K_OPACITY, p / 100f).apply()), Ui.matchWrap());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            TextView note = ui.secondary("系统规定悬浮层最高 80% 不透明，否则会挡住下面 App 的触摸。");
            note.setTextSize(12.5f);
            ac.addView(note, top(2));
        }
        content.addView(ac);

        // ---------- 运动与刷新率 ----------
        content.addView(ui.sectionHeader("运动与刷新率"));
        LinearLayout mo = ui.card();
        Switch yaw = ui.toggle(Prefs.yawCue(this));
        yaw.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean(Prefs.K_YAW, c).apply());
        mo.addView(ui.row("转弯时使用陀螺仪", "低速转弯（几乎没有离心力）时也有提示", yaw).view, Ui.matchWrap());
        mo.addView(ui.divider());
        mo.addView(ui.title("刷新率", 15f));
        mo.addView(ui.segmented(new String[]{"跟随系统", "尽量最高", "60Hz 省电"}, Prefs.refresh(this),
                i -> sp.edit().putInt(Prefs.K_REFRESH, i).apply()), top(8));
        mo.addView(ui.secondary("动画逐帧跟随屏幕当前的刷新率（包括 LTPO 动态变化），运动速度与刷新率无关；"
                + "圆点静止时暂停绘制，屏幕可以降频省电。"), top(10));
        content.addView(mo);

        // ---------- 其它 ----------
        content.addView(ui.sectionHeader("其它"));
        LinearLayout oc = ui.card();
        Switch boot = ui.toggle(Prefs.startOnBoot(this));
        boot.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean(Prefs.K_BOOT, c).apply());
        oc.addView(ui.row("开机后自动恢复", null, boot).view, Ui.matchWrap());
        oc.addView(ui.divider());
        TextView chevron = ui.text("›", 22f, pal.textSecondary);
        Ui.Row app = ui.row("应用详情与耗电设置", "ColorOS：耗电管理 → 允许后台行为、允许自启动", chevron);
        app.view.setBackground(ui.pressable(pal.surface, 14));
        app.view.setOnClickListener(v -> openAppDetails());
        oc.addView(app.view, Ui.matchWrap());
        TextView tileTip = ui.secondary("提示：在下拉控制中心添加「防晕车圆点」快捷开关，点一下切换 关闭 → 始终显示 → 自动。");
        oc.addView(tileTip, top(10));
        content.addView(oc);

        // ---------- 诊断与说明（默认收起） ----------
        liveText = ui.text("", 12.5f, pal.textSecondary);
        liveText.setTypeface(Typeface.MONOSPACE);
        content.addView(ui.collapsible("实时数据", "传感器、刷新率、乘车判断", liveText), top(22));
        content.addView(ui.collapsible("原理与说明", "感觉冲突理论、传感器融合、参考文献",
                ui.text(ABOUT, 14f, pal.textSecondary)), top(12));

        String vn = versionName();
        if (vn != null && !vn.isEmpty()) {
            TextView ver = ui.secondary("版本 " + vn);
            ver.setGravity(Gravity.CENTER);
            ver.setTextSize(12f);
            content.addView(ver, top(20));
        }
    }

    private Ui.Row permRow(String title, String desc, String action, boolean primary, View.OnClickListener l) {
        TextView btn = primary ? ui.primaryButton(action, l) : ui.tonalButton(action, l);
        btn.setTextSize(14f);
        btn.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        Ui.Row r = ui.row(title, desc, btn);
        permCard.addView(r.view, top(8));
        return r;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static final String ABOUT =
            "圆点像车里没有固定住的小球：加速时往后（下）滑，刹车时往前（上）滑，左转时往右、右转时往左。"
                    + "同时运动越强，圆点越大、越多，并且往小球滑去的那一边「堆」起来——刹车时上半部分涨起来，左转时右侧涨起来；"
                    + "车辆平稳时只留两列小点，不打扰阅读。\n\n"
                    + "晕动症主要来自「感觉冲突」——内耳前庭感到加减速和转弯，眼睛却盯着静止的屏幕；"
                    + "余光里与身体感受一致的运动可以减轻这种冲突。\n\n"
                    + "传感器：用「游戏旋转矢量」（陀螺仪 + 加速度计融合，不用磁力计，不受车内磁场干扰）估计竖直方向，"
                    + "把屏幕的右方、上方投影到水平面，加速度在这两个方向上的分量就是提示量；"
                    + "手机平放、斜拿、竖拿、横屏、躺着举在脸上方都一致正确。\n\n"
                    + "滤波：晕车最敏感的频段约 0.1–0.5Hz，车辆操控通常低于 1Hz，所以低通滤掉路面颠簸和手抖，"
                    + "再用超前补偿抵消滤波延迟；轻微的加减速也会被适度放大，急刹不会冲得太远。\n\n"
                    + "自动模式：国行系统没有 Google 的活动识别，这里用振动、步频、车辆操控三个特征自行判断，"
                    + "约 15 秒确认上车，走路会退出。不准确时请用「始终显示」或快捷开关。\n\n"
                    + "限制：普通应用的悬浮窗无法盖在锁屏和部分系统界面上。仅供乘客使用，驾驶时请勿看手机。\n\n"
                    + "参考：Apple iOS 18「车辆运动提示」；Google Android「Motion Assist」；"
                    + "Hanau & Popescu, MotionReader (AutomotiveUI 2017)；Karjanto 等 (Transportation Research Part F, 2018)；"
                    + "Reason & Brand, Motion Sickness (1975)；Golding (2006)；ISO 2631-1。";

    // =============================== 开关与模式 ===============================

    private void onMasterToggled(boolean on) {
        if (on) {
            int m = Prefs.lastOnMode(this);
            if (!Settings.canDrawOverlays(this)) openOverlaySettings();
            else if (m == Prefs.MODE_AUTO) maybeAskStepPermission();
            Prefs.setMode(this, m);
            MotionService.sync(this);
        } else {
            Prefs.setMode(this, Prefs.MODE_OFF);
        }
        syncModeUi();
    }

    private void onModeChosen(int m) {
        Prefs.setLastOnMode(this, m);
        if (Prefs.mode(this) != Prefs.MODE_OFF) {
            Prefs.setMode(this, m);
            if (m == Prefs.MODE_AUTO) maybeAskStepPermission();
            MotionService.sync(this);
        }
        syncModeUi();
    }

    private void maybeAskStepPermission() {
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)
                && !Prefs.sp(this).getBoolean("asked_" + Manifest.permission.ACTIVITY_RECOGNITION, false)) {
            askOrOpenSettings(Manifest.permission.ACTIVITY_RECOGNITION, false);
        }
    }

    /**
     * 第一次直接申请；被拒绝且系统不再弹窗（「不再询问」或拒绝两次）后，改为打开对应的系统设置页，
     * 否则按钮点了没反应。
     */
    private void askOrOpenSettings(String perm, boolean notification) {
        SharedPreferences sp = Prefs.sp(this);
        String key = "asked_" + perm;
        if (sp.getBoolean(key, false) && !shouldShowRequestPermissionRationale(perm)) {
            if (notification) {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } else {
                openAppDetails();
            }
            return;
        }
        sp.edit().putBoolean(key, true).apply();
        requestPermissions(new String[]{perm}, REQ_PERMS);
    }

    private void syncModeUi() {
        if (master == null) return;
        syncing = true;
        int mode = Prefs.mode(this);
        boolean on = mode != Prefs.MODE_OFF;
        if (master.isChecked() != on) master.setChecked(on);
        int shown = on ? mode : Prefs.lastOnMode(this);
        modeSeg.select(shown == Prefs.MODE_AUTO ? 0 : 1, false);
        modeSeg.setEnabled(on);
        String status;
        if (!on) {
            status = "已关闭";
        } else if (!Settings.canDrawOverlays(this)) {
            status = "需要先开启悬浮窗权限";
        } else if (LiveStats.demoActive) {
            status = "演示中";
        } else if (mode == Prefs.MODE_AUTO) {
            status = LiveStats.inVehicle ? "已开启 · 检测到乘车，正在显示" : "已开启 · 等待检测到乘车";
        } else {
            status = "已开启 · 始终显示";
        }
        masterRow.subtitle.setText(status);
        masterRow.subtitle.setVisibility(View.VISIBLE);
        masterRow.subtitle.setTextColor(on && !Settings.canDrawOverlays(this) ? pal.warning : pal.textSecondary);
        modeHint.setText(shown == Prefs.MODE_AUTO
                ? "检测到坐车约 15 秒后自动显示，走路时自动隐藏（实验性）。"
                : "开启后一直显示，最可靠。");
        syncing = false;
    }

    private void demo() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings();
            return;
        }
        MotionService.start(this, MotionService.ACTION_DEMO);
    }

    // =============================== 权限 ===============================

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPermissions() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS);
        boolean step = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        permOverlay.view.setVisibility(overlay ? View.GONE : View.VISIBLE);
        permNotif.view.setVisibility(notif ? View.GONE : View.VISIBLE);
        permStep.view.setVisibility(step ? View.GONE : View.VISIBLE);
        permBattery.view.setVisibility(battery ? View.GONE : View.VISIBLE);
        permCard.setVisibility(overlay && notif && step && battery ? View.GONE : View.VISIBLE);
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void openAppDetails() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshPermissions();
        if (Prefs.mode(this) != Prefs.MODE_OFF) MotionService.start(this, MotionService.ACTION_APPLY);
    }

    // =============================== 实时数据 ===============================

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
                    "\n乘车判断：%s（分数 %.1f / 进入≥8）\n振动 %.3f · 步频能量 %.2f\n步频周期 %.2f · 操控 %.2f",
                    LiveStats.inVehicle ? "在车上" : "不在车上", LiveStats.vehicleScore,
                    LiveStats.vibRms, LiveStats.walkRms, LiveStats.walkPeriodicity, LiveStats.motionRms));
        }
        liveText.setText(sb.toString());
    }
}
