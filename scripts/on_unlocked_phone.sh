#!/usr/bin/env bash
# Runs a command against the USB-connected phone only (emulators stay out of it). Refuses to start on
# a locked phone, where an input method cannot appear, and keeps the screen on while the command runs.
set -euo pipefail

adb="${ADB:-adb}"

if ! "$adb" -d get-state >/dev/null 2>&1; then
    echo "No USB device: connect the phone and enable USB debugging (adb devices)." >&2
    exit 1
fi

window="$("$adb" -d shell dumpsys window)"
power="$("$adb" -d shell dumpsys power)"
if grep -q 'isKeyguardShowing=true' <<<"$window" || ! grep -q 'mWakefulness=Awake' <<<"$power"; then
    echo "The phone is locked or its screen is off: unlock it and run this again." >&2
    exit 1
fi

previous="$("$adb" -d shell settings get global stay_on_while_plugged_in | tr -d '\r')"
restore() {
    "$adb" -d shell settings put global stay_on_while_plugged_in "$previous" || true
}
trap restore EXIT
"$adb" -d shell svc power stayon true

ANDROID_SERIAL="$("$adb" -d get-serialno)"
export ANDROID_SERIAL
"$@"
