package com.motioncue.app;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;

/**
 * 前台服务：持有传感器引擎、悬浮层和乘车检测，按模式决定圆点何时显示。
 */
public final class MotionService extends Service implements MotionEngine.Listener,
        SharedPreferences.OnSharedPreferenceChangeListener, DisplayManager.DisplayListener {

    public static final String ACTION_APPLY = "com.motioncue.app.APPLY";
    public static final String ACTION_STOP = "com.motioncue.app.STOP";
    public static final String ACTION_TOGGLE = "com.motioncue.app.TOGGLE";
    public static final String ACTION_DEMO = "com.motioncue.app.DEMO";

    private static final String CHANNEL = "motion_cues";
    private static final int NOTIF_ID = 1;
    private static final long DEMO_MS = 12_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MotionEngine engine;
    private OverlayController overlay;
    private DisplayManager dm;
    private AppOpsManager appOps;
    private boolean screenOn = true;
    private long screenOffAt;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOn = false;
                screenOffAt = SystemClock.elapsedRealtime();
                engine.stop();
                overlay.removeNow();
            } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                screenOn = true;
                if (screenOffAt > 0) {
                    engine.detector().decayFor((SystemClock.elapsedRealtime() - screenOffAt) / 1000f);
                }
                applyState();
            }
        }
    };

    /** 悬浮窗权限被授予 / 撤销时（系统不会通知应用，只能监听 AppOps） */
    private final AppOpsManager.OnOpChangedListener overlayOpListener =
            (op, pkg) -> main.postDelayed(this::applyState, 300);

    /**
     * 启动（或通知）前台服务。
     * @return false 表示系统拒绝了启动（例如 Android 15 在后台、且当前没有可见悬浮窗时）
     */
    public static boolean start(Context c, String action) {
        Intent i = new Intent(c, MotionService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException 等
            return false;
        }
    }

    /**
     * 设置改变后调用：需要时启动服务。
     * 关闭时什么都不用做——正在运行的服务通过设置监听自己停止（再发一次启动反而会重新创建服务）。
     */
    public static boolean sync(Context c) {
        if (Prefs.mode(c) == Prefs.MODE_OFF) return true;
        return start(c, ACTION_APPLY);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startInForeground();

        SensorManager sm = getSystemService(SensorManager.class);
        engine = new MotionEngine(sm);
        engine.setListener(this);
        LiveStats.sensorInfo = MotionEngine.describeSensors(sm);

        Context overlayCtx = this;
        dm = getSystemService(DisplayManager.class);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && display != null) {
            overlayCtx = createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        overlay = new OverlayController(overlayCtx, engine);

        dm.registerDisplayListener(this, main);
        onDisplayChanged(Display.DEFAULT_DISPLAY);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, f);
        }
        PowerManager pm = getSystemService(PowerManager.class);
        screenOn = pm == null || pm.isInteractive();

        appOps = getSystemService(AppOpsManager.class);
        try {
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, getPackageName(), overlayOpListener);
        } catch (RuntimeException ignored) {
        }

        Prefs.sp(this).registerOnSharedPreferenceChangeListener(this);
        LiveStats.serviceRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            Prefs.setMode(this, Prefs.MODE_OFF);
        } else if (ACTION_TOGGLE.equals(action)) {
            // 通知栏按钮：在「始终显示」和「自动」之间切换
            Prefs.setMode(this, Prefs.mode(this) == Prefs.MODE_ALWAYS ? Prefs.MODE_AUTO : Prefs.MODE_ALWAYS);
        } else if (ACTION_DEMO.equals(action)) {
            engine.startDemo(DEMO_MS);
            main.postDelayed(this::applyState, DEMO_MS + 100);
        }
        applyState();
        return START_STICKY;
    }

    private void applyState() {
        int mode = Prefs.mode(this);
        boolean demo = engine.demoActive();
        if (mode == Prefs.MODE_OFF && !demo) {
            engine.stop();
            overlay.removeNow();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            // 权限被撤销：传感器和动画也一起停掉，等权限恢复（AppOps 监听会再次调用这里）
            engine.stop();
            overlay.removeNow();
            updateNotification();
            return;
        }
        if (!screenOn) return;

        engine.setUseStepDetector(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED);
        engine.start();

        boolean show = demo || mode == Prefs.MODE_ALWAYS
                || (mode == Prefs.MODE_AUTO && engine.detector().isInVehicle());
        if (show) overlay.show();
        else overlay.hide();
        updateNotification();
    }

    // ---------------- MotionEngine.Listener（传感器线程） ----------------

    @Override
    public void onMotionSample() {
        overlay.field().onSensorSample();
    }

    @Override
    public void onVehicleStateChanged(boolean inVehicle) {
        main.post(this::applyState);
    }

    // ---------------- 设置变化 ----------------

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key == null) {                       // 设置被清空（Android 11+）
            applyState();
            overlay.applySettings(true);
        } else if (Prefs.K_MODE.equals(key)) {
            applyState();
        } else if (Prefs.K_SENS.equals(key) || Prefs.K_YAW.equals(key)) {
            overlay.applySettings(false);        // 只影响位移，不用重建窗口
        } else if (Prefs.K_SIZE.equals(key) || Prefs.K_COUNT.equals(key) || Prefs.K_OPACITY.equals(key)
                || Prefs.K_LAYOUT.equals(key) || Prefs.K_COLOR.equals(key) || Prefs.K_REFRESH.equals(key)) {
            overlay.applySettings(true);
        }
        // K_LAST_MODE / K_BOOT 与显示无关
    }

    // ---------------- 屏幕：旋转与刷新率 ----------------

    @Override
    public void onDisplayAdded(int displayId) {}

    @Override
    public void onDisplayRemoved(int displayId) {}

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) return;
        Display d = dm.getDisplay(displayId);
        if (d == null) return;
        engine.setDisplayRotation(d.getRotation());
        LiveStats.displayHz = d.getRefreshRate();
        overlay.onDisplayChanged();
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "运动提示服务", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        int mode = Prefs.mode(this);
        String text;
        if (!Settings.canDrawOverlays(this)) {
            text = "需要「悬浮窗」权限，点此打开 App 授权";
        } else if (engine != null && engine.demoActive()) {
            text = "演示中";
        } else if (mode == Prefs.MODE_ALWAYS) {
            text = "始终显示运动提示圆点";
        } else if (engine != null && engine.detector().isInVehicle()) {
            text = "自动模式 · 检测到乘车，正在显示";
        } else {
            text = "自动模式 · 等待检测到乘车";
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, MotionService.class).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, MotionService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("乘车动态提示")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null,
                        mode == Prefs.MODE_ALWAYS ? "切换为自动" : "始终显示", toggle).build())
                .addAction(new Notification.Action.Builder(null, "关闭", stop).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void startInForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(this);
        try {
            appOps.stopWatchingMode(overlayOpListener);
        } catch (RuntimeException ignored) {
        }
        try {
            unregisterReceiver(screenReceiver);
        } catch (RuntimeException ignored) {
        }
        dm.unregisterDisplayListener(this);
        engine.stop();
        overlay.removeNow();
        main.removeCallbacksAndMessages(null);
        LiveStats.serviceRunning = false;
        LiveStats.overlayShown = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
