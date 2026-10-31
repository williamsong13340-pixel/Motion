package com.motioncue.app;

/** 服务与界面之间共享的实时状态（仅用于显示/调试）。 */
public final class LiveStats {
    private LiveStats() {}

    public static volatile boolean serviceRunning;
    public static volatile boolean overlayShown;
    public static volatile boolean demoActive;

    // 运动估计（车辆坐标系，已滤波）
    public static volatile float accelForward;  // m/s²，正 = 加速
    public static volatile float accelRight;    // m/s²，正 = 向右的加速度（左转时为负）
    public static volatile float yawRate;       // rad/s，正 = 左转
    public static volatile float handlingGain;  // 0..1，手持晃动时降低

    // 乘车检测
    public static volatile boolean inVehicle;
    public static volatile float vehicleScore;
    public static volatile float vibRms;
    public static volatile float walkRms;
    public static volatile float motionRms;
    public static volatile long lastStepMs;

    // 屏幕
    public static volatile float displayHz;      // 系统报告的当前刷新率
    public static volatile float measuredFps;    // 动画回调实测帧率
    public static volatile boolean animIdle;     // 画面静止时停止逐帧绘制以省电
    public static volatile String sensorInfo = "";
}
