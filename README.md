# HoshiDrink

一个离线使用的 Android 饮水提醒应用，支持 Android 8.0 及以上。

## 技术

- Kotlin、Jetpack Compose、Material 3：原生界面与动态水位。
- ViewModel、StateFlow、Coroutines：界面状态与后台数据读写。
- SQLite：饮水记录；SharedPreferences：目标和提醒偏好。
- AlarmManager、WorkManager、Notification API：系统闹钟、后台兜底与系统通知。

无需服务器、账号或网络权限。准时提醒为可选功能，Android 12+ 需用户允许“闹钟和提醒”权限。

## 功能

- 今日饮水量、完成比例、动态水瓶、剩余目标。
- 150 / 250 / 350 mL 快捷选择，1–2000 mL 自定义容量。
- 今日及历史记录，删除与撤销。
- 最近七天统计，前后翻页，按日期查看明细。
- 500–6000 mL 每日目标。
- 30 / 60 / 90 / 120 分钟提醒间隔，起止时间，达标暂停。
- Android 13+ 通知权限申请及系统通知设置入口。
- 仅响铃、响铃和震动、仅震动、静音四种通知方式，以及试发提醒。
- 可选准时提醒、闹钟权限状态、电池优化与后台设置入口。
- 空数据状态、跨日刷新、应用重启后数据保留。

## 构建

使用 Android Studio 打开本目录，安装 Android SDK 36，使用 JDK 17 或更高版本。
Android Studio 会生成 `local.properties`；命令行也可以设置 `ANDROID_HOME`。

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

安装包：`app/build/outputs/apk/debug/app-debug.apk`。

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 界面与验证

实际运行截图：
[今日](docs/screenshots/today.png) · [统计](docs/screenshots/statistics.png) · [设置](docs/screenshots/settings.png)。
[震动方式](docs/screenshots/reminder-style.png) · [准时提醒与电池设置](docs/screenshots/reminder-reliability.png)。

单元测试覆盖提醒窗口、跨日、达标暂停、夏令时和过期任务拦截；Android 设备测试覆盖记水、
自定义容量、目标校验、历史浏览、删除撤销、SQLite 持久化、通知方式、重复拦截和系统闹钟。
深度休眠测试仅在模拟器执行，并在结束时恢复电池和休眠状态。

连接模拟器或测试设备后：

```sh
./gradlew connectedDebugAndroidTest
```

## 提醒行为

首次使用默认关闭提醒。开启后，在允许通知的前提下，每隔设定时间发送一次系统通知，点击进入应用。
默认“仅响铃”沿用原版本通知渠道；可以切换“仅震动”等方式，并用“试发提醒”直接验证，无需等到下一次提醒。
每种方式使用独立的稳定通知渠道，因为 Android 8+ 不允许应用覆盖用户已配置的渠道声音和震动。
实际声音、震动、锁屏可见性由系统通知渠道与勿扰模式决定；试发提醒不启用定时提醒，也不改变下一次提醒时间。

记水、调整目标、时段或间隔会重新计算下一次提醒；重新打开应用、切换通知方式或准时模式会保留原倒计时。
达到目标后可暂停至次日开始时间。提醒时段限定在同一天内，结束时间必须晚于开始时间。

普通模式使用 `AlarmManager.setAndAllowWhileIdle`，比纯后台任务更适合用户可见的定时提醒。
开启“准时提醒”且具有权限时，使用 `setExactAndAllowWhileIdle`，可在 Doze 休眠时触发。
Android 12+ 必须由用户在系统页面允许“闹钟和提醒”；未授权时自动使用普通模式。
两种模式均安排在预定时间五分钟后的 WorkManager 任务作为兜底，实际执行仍可能延迟；持久化任务标识防止重复与过期通知。
升级时，原版本后台任务会迁移为新调度。重启、应用覆盖升级与时区/时间变化后会恢复或重新计算闹钟。
撤销精确闹钟权限会使 Android 取消精确闹钟；已有兜底任务可在系统允许执行时恢复普通提醒，重新打开应用也会立即重新调度。

精确闹钟不能绕过强行停止、系统勿扰、用户关闭通知或所有厂商后台限制，无法承诺绝不延迟。
可在设置中管理电池优化；华为等机型还可能需要在应用启动管理中允许自启动、关联启动和后台活动。
系统强行停止应用后，需要重新打开应用才能恢复任务。

所有统计按设备当前时区计算。达标天数按当前目标计算；日均为所显示七天的总量除以七。
数据仅存本机，卸载后清除。当前版本不含云同步、导入导出或医疗建议。
