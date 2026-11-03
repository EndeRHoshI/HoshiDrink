#!/usr/bin/env bash
# Read-only evidence collection. Never clears logcat, stops the app or modifies device settings.
set -euo pipefail

device_serial="${1:-}"
device_adb() {
    if [[ -n "$device_serial" ]]; then
        adb -s "$device_serial" "$@"
    else
        adb "$@"
    fi
}
output_dir="${2:-diagnostics/$(date +%Y%m%d-%H%M%S)}"
command -v adb >/dev/null || { echo "未找到 adb，请安装 Android platform-tools。" >&2; exit 1; }
mkdir -p "$output_dir"
device_adb get-state > "$output_dir/device-state.txt"
device_adb shell date > "$output_dir/device-time.txt" 2>&1 || true

# Debuggable installations can export app-private files without launching the app.
if ! device_adb exec-out run-as com.hoshi.drink tar -cf - files/diagnostics > "$output_dir/app-logs.tar" 2> "$output_dir/app-logs-error.txt"; then
    echo "私有日志拉取失败：正式签名包可在应用设置中导出完整日志。" >&2
fi
device_adb logcat -d -v threadtime -s DrinkReminder > "$output_dir/logcat.txt" 2>&1 || true
device_adb shell dumpsys alarm |
    awk '/com[.]hoshi[.]drink/ { remaining=20 } remaining>0 { print; remaining-- }' > "$output_dir/alarms.txt" || true
device_adb shell dumpsys notification |
    awk '
        /AppSettings:/ { own=($0 ~ /AppSettings: com[.]hoshi[.]drink([ (]|$)/) }
        own && /AppSettings:|NotificationChannel/ { print }
        /NotificationRecord/ && /com[.]hoshi[.]drink/ { print }
        /mZenMode=|mInterruptionFilter=|mSuppressedEffects=/ { print }
    ' > "$output_dir/notifications.txt" || true
device_adb shell dumpsys vibrator_manager |
    awk '/com[.]hoshi[.]drink/ { remaining=8 } remaining>0 { print; remaining-- }' > "$output_dir/vibration.txt" || true
device_adb shell dumpsys power |
    awk '/mWakefulness=|mIsPowered=|mBatteryLevel=|mDeviceIdleMode=|mLightDeviceIdleMode=|mLowPowerModeEnabled=|mLastWakeTime=|mLastSleepTime=|com[.]hoshi[.]drink/ { print }' > "$output_dir/power-state.txt" || true
device_adb shell dumpsys activity services com.hoshi.drink > "$output_dir/services.txt" 2>&1 || true
device_adb shell dumpsys activity exit-info com.hoshi.drink > "$output_dir/process-exits.txt" 2>&1 || true
device_adb shell dumpsys package com.hoshi.drink |
    awk '/versionCode=|versionName=|lastUpdateTime=|stopped=|android.permission.VIBRATE|android.permission.POST_NOTIFICATIONS|android.permission.SCHEDULE_EXACT_ALARM/ { print }' > "$output_dir/package-state.txt" || true
device_adb shell cmd appops get com.hoshi.drink SCHEDULE_EXACT_ALARM > "$output_dir/exact-alarm-permission.txt" 2>&1 || true

echo "已保存到 $output_dir。日志中的 API 成功记录不等于系统实际展示或马达实际震动。"
