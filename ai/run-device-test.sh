#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -uo pipefail
mkdir -p ai-artifacts/screenshots
adb logcat -c
./gradlew :app:connectedAppReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.ai.MemoryUpgradeDeviceTest --console=plain --no-daemon --max-workers=2 > device-memory.log 2>&1
result=$?
cat device-memory.log
adb pull /sdcard/Android/data/com.mzw.legado.ai.memory.release/files/memory-test-screens ai-artifacts/screenshots/ || true
adb logcat -d > ai-artifacts/device-logcat.txt
exit "$result"
