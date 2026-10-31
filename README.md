# 乘车动态提示（Motion Cues for Android）

给一加 Ace 3（以及其他 Android 8.0+ 手机）用的防晕车圆点，效果类似 iOS 18 的「车辆运动提示」和 Pixel 的 Motion Assist：
屏幕边缘的圆点会跟着车辆的加速、刹车、转弯移动，让眼睛看到的运动和内耳感受到的保持一致。

## 安装

1. 打开本仓库的 **Releases** 页面，下载最新的 `MotionCue-v1.0.x.apk` 并安装（也可以在 Actions 里下载构建产物）。
2. 打开 App，按提示授予 **悬浮窗** 权限（必需）、通知权限。
3. 建议在 ColorOS 中：设置 → 应用 → 乘车动态提示 → 耗电管理 → 打开「允许后台行为」「允许自启动」，防止服务被杀。
4. 选择模式：
   - **始终显示**：上车前打开，最可靠；
   - **自动**：检测到乘车约 15 秒后自动显示，走路时自动隐藏（实验性）。
5. 可以在下拉控制中心添加「防晕车圆点」快捷开关：关闭 → 始终显示 → 自动。
6. 在家可以先用「演示 12 秒」看效果。

## 圆点怎么动

把圆点想象成车里没固定住的小球：

| 车辆动作 | 圆点 |
|---|---|
| 加速 | 往下（往后）滑 |
| 刹车 | 往上（往前）滑 |
| 左转 | 往右 |
| 右转 | 往左 |

## 技术实现

- **传感器**：一加 Ace 3 有加速度计、陀螺仪，系统提供融合后的「游戏旋转矢量」（不用磁力计，不受车内磁场干扰）。
  App 用它估计竖直方向，把加速度投影到水平面得到车辆加速度（自动去掉重力），陀螺仪在竖直轴上的分量就是转弯角速度。
  手机平放、斜拿、竖拿和横屏都会根据屏幕方向自动换算前/右方向。没有旋转矢量的手机会退化为陀螺仪+加速度计互补滤波。
- **滤波**：晕动症最敏感的频段约 0.1–0.5Hz，车辆操控大多低于 1Hz，所以采用 1.2Hz 两级低通滤掉路面颠簸和手抖；
  自适应慢速去偏消除零漂和姿态误差；软死区去掉微小噪声；检测到用户在晃动/转动手机时暂时压低反馈。
- **刷新率**：圆点由 `Choreographer` 在每个 VSYNC 驱动，回调频率就是屏幕当前的真实刷新率（60/90/120Hz、LTPO 动态变化都跟随）。
  物理模拟使用真实帧间隔 + 固定 2ms 子步长的临界阻尼弹簧，因此刷新率变化时运动速度和轨迹完全一致，高刷只是更顺滑。
  圆点静止时停止逐帧绘制，屏幕可以降频省电；设置里还可以选择「尽量最高」或「60Hz 省电」向系统投票。
  App 内「实时数据」会显示当前刷新率、支持的刷新率和动画实测帧率。
- **乘车检测**：国行 ColorOS 没有 Google 活动识别 API，App 用振动能量（>3Hz）、步频能量（1–3Hz）/计步器、车辆操控（低频水平加速度/偏航）三个特征加积分迟滞自行判断。
- **悬浮窗**：`TYPE_APPLICATION_OVERLAY` + 不可触摸，延伸到刘海/状态栏区域；Android 12+ 系统要求此类窗口不透明度 ≤ 80% 才能让触摸穿透，App 会自动限制。

## 已知限制

- 普通应用的悬浮窗无法显示在锁屏和部分系统界面之上（Pixel 的系统级实现可以）。
- 自动检测是启发式的，可能会有延迟或误判，可在「实时数据」中查看判断依据。
- 仅供乘客使用，驾驶时请勿看手机。

## 参考

- Apple, iOS 18 Vehicle Motion Cues（2024）
- Google, Android Motion Assist（原名 Motion Cues，2026）
- Hanau, E. & Popescu, V. *MotionReader: Visual Acceleration Cues for Alleviating Passenger E-Reader Motion Sickness.* AutomotiveUI 2017.
- Karjanto, J. et al. *The effect of peripheral visual feedforward system in enhancing situation awareness and mitigating motion sickness in fully automated driving.* Transportation Research Part F, 2018.
- Reason, J. T. & Brand, J. J. *Motion Sickness.* Academic Press, 1975（感觉冲突理论）
- Golding, J. F. *Motion sickness susceptibility.* Autonomic Neuroscience, 2006；ISO 2631-1（低频振动与晕动症）

## 构建

```bash
./gradlew assembleRelease
```

每次 push 都会由 GitHub Actions 构建并发布到 Releases。仓库内的 `app/motioncue.keystore` 是公开的固定签名（只为了方便覆盖升级），
如需私有签名，可设置环境变量 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。
