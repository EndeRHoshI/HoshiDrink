# 提醒行为依据与故障排查

调研日期：2026-11-03。这里只记录实际查阅的公开资料；商业应用的内部调度代码没有公开，不能声称已确认其使用的 Android API。

## 同类产品的可确认行为

- [Hydro Coach 首页](https://hydrocoach.com/)说明支持 Smart Drink Reminder 和 Interval Drink Reminder。
- [Hydro Coach 提醒介绍](https://hydrocoach.com/blog/app/drink-water-reminder/)说明唤醒/睡眠时段、按周计划及 Android 自定义提醒铃声。其时间/饮水量两种提醒的具体段落标注为 iOS，不能直接套用为 Android 实现依据。
- [WaterMinder 官网](https://waterminder.com/)的 “Why am I not getting reminders from the app?” 列出四项：已经达到目标、提醒关闭、提醒类型设置、星期设置。它没有公开后台调度实现。

本应用据此明确区分“固定时间计划”“达标暂停”“系统通知状态”，不把商业产品的描述当作可靠性保证。

## Android 官方依据

- [闹钟调度](https://developer.android.com/develop/background-work/services/alarms)：普通闹钟允许延迟；`setExactAndAllowWhileIdle` 适合用户指定的时间敏感操作，Android 12+ 检查并请求精确闹钟权限。重复闹钟并不精确，因此按固定时刻安排一次性精确闹钟。
- [Activity 生命周期](https://developer.android.com/guide/components/activities/activity-lifecycle)：从桌面或最近任务返回可能只触发恢复生命周期，不能只在特定通知 Intent 中停止震动。
- [前台服务后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)：用户交互和完成用户请求的精确闹钟是例外；普通后台任务不保证能启动前台服务。
- [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)：持续震动采用 `specialUse`，并在 Manifest 说明用途。若发布 Google Play，需要申报和审核前台服务用途，不能把本地可运行等同于商店已获准。
- 本机 SDK 35 的 `android/os/SystemVibratorManager.java`：震动与取消都使用管理器的 Binder token。因此统一使用 Application Context 的同一个缓存实例，避免不同 Context 的所有者不一致。

## 已确认的代码问题及修正

1. 旧版本只在 `ACTION_OPEN_AND_STOP` 的 `onCreate/onNewIntent` 入口停止。现在任何入口的 `onResume` 都确认提醒，并无条件调用拥有该震动的实例的 `cancel()`。
2. 不再用异步持久化的 `repeating` 布尔值决定是否取消。会话标识和启动/停止都通过同一个锁管理；停止先使待启动请求失效，避免通知点击与服务启动竞态。
3. 原来在广播接收器里直接启动无限震动。现在只在用户启用持续震动、实际提醒时运行可见的前台服务；停止后结束服务，不为定时提醒长期保活。
4. 原来在通知处理完后才排下一次任务，异常路径还可能回写旧任务。现在先提交下一次闹钟，再处理本次通知，不回写已经消费的旧标识。
5. 休眠唤醒广播中的异步工作使用最多 10 秒的短 CPU 锁，完成即释放。
6. 设置页显示下次计划和实际提交的精确/普通模式，缺少精确权限会明确提示；整点预设会打开权限入口。

以上代码变更未经编译、自动测试或真机复现验证（遵照用户约定）。当前没有连接手机，尚不能确定本次漏提醒的设备端原因。

## 下次复发时如何定位

更新后，进入设置 → 提醒可靠性 → 查看提醒诊断 → 复制诊断。
日志默认开启，只保存在本机，不自动上传，不记录饮水明细。界面展示最近 150 条；完整日志按大小轮转，
共 7 个约 1 MiB 的 JSONL 文件，文件满后依次覆盖最旧记录，不承诺固定保存天数。`events-0.jsonl` 为最新文件。
记录包含 ISO 时间、时区、elapsed realtime、PID/TID、进程会话、线程、日志级别、事件、结构化字段和异常堆栈（单条最多 24,000 字符）。
队列容量 2048，过载时计数并在下一条日志的 `droppedSinceLastWrite` 中报告；进程退出或被系统结束前最后排队的日志可能来不及落盘。
未捕获异常会尝试同步写入 FATAL 条目，再交给系统原有异常处理器。系统直接杀进程不会触发此回调，
下次启动会补记 Android 11+ 提供的最近 5 次进程退出原因。

同一次提醒用 `alarmId/traceId` 关联，持续震动另有 `sessionId`，进程重启通过 `processSession` 区分。
系统快照在后台采样，记录其实际采样时间；服务每 30 秒记一次心跳，不每秒写文件。
记录了权限、省电/休眠/亮屏状态、通知渠道、勿扰与静音状态；日志不代表马达实际震动，仍需系统服务取证。

## 取回日志

应用内：设置 → 提醒可靠性 → 导出完整日志，选择保存位置。ZIP 包含状态报告和全部轮转文件。

调试包连接电脑后，可直接拉取应用私有目录，无需先打开 App：

```sh
bash scripts/collect-reminder-logs.sh
# 多设备时指定序列号和本机输出目录：
bash scripts/collect-reminder-logs.sh SERIAL diagnostics/incident-20261103
```

脚本同时收集仅包含本应用的 Logcat、相关闹钟、渠道、震动、前台服务、退出信息、设备时间、省电状态及精确权限状态。
输出目录 `diagnostics/` 已忽略，避免意外提交到 Git。不要把取证文件当作源码提交。
正式签名包无法使用 `run-as` 时，通过应用内导出；卸载或清除应用数据会删除本地日志。
脚本不修改手机、不清空日志、不强停应用；系统 dumpsys 格式因厂商不同可能缺少部分信息。

## 日志判断方法

- 没有“安排提醒”：检查是否开启、安装版本、恢复广播或保存失败。
- 计划为整点，实际模式为普通：精确闹钟权限未允许或请求被系统拒绝。
- 有“安排提醒”，没有“收到提醒”：结合设备的 `dumpsys alarm`、后台限制、是否强行停止和重启状态定位，不能只凭应用日志认定厂商拦截。
- “收到提醒”延迟：比较记录中的计划时间和回调时间；“后台兜底”不承诺整点。
- 回调正常但没有通知：检查“通知被关闭”“不发送通知”“发送通知失败”等记录。API 返回成功只代表请求已提交，不等于用户看到了通知。
- 震动未停止：检查“界面恢复”“停止震动请求已提交”及对应会话标识。若已提交取消但仍震动，需要连接当时的手机检查系统震动服务。
- “持续震动服务被拒绝”：Android 不允许此次后台启动，退回普通通知及一次短震动；不可用死循环反复尝试绕过限制。

连接手机时可只读收集以下信息，不要在排查前清空日志或强行停止应用：

```sh
adb shell dumpsys alarm | rg -n -B 6 -A 16 com.hoshi.drink
adb logcat -d -s DrinkReminder
adb shell dumpsys vibrator_manager | rg -n -B 5 -A 20 com.hoshi.drink
adb shell dumpsys notification | rg -n -B 3 -A 12 com.hoshi.drink
adb shell dumpsys activity services com.hoshi.drink
```

需要区分“计划时刻”“系统回调”“通知请求”“震动请求”“用户确认”五个阶段。精确闹钟不能绕过强行停止、关闭通知、勿扰或全部厂商限制。
