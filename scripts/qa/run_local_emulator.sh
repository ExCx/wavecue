#!/usr/bin/env bash
# Boot the WaveCue AVD and run the instrumented suite against it.
#
# Intended for machines without KVM: pass SLOW=1 to force software emulation.
# The emulator data partition is kept between runs, so only the first boot pays
# the dex2oat cost. Fixtures are seeded through /data/media/0 because the
# FUSE-backed /sdcard can take a while to mount on a software-emulated device.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVD="${AVD:-wavecue}"
SERIAL="${SERIAL:-emulator-5554}"
SLOW="${SLOW:-0}"

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  mkdir -p "$ROOT/build"
  ACCEL=()
  if [ "$SLOW" = "1" ]; then
    ACCEL=(-accel off)
  fi
  echo "starting $AVD (slow=$SLOW)"
  nohup "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot "${ACCEL[@]}" -memory 2048 \
    > "$ROOT/build/local-emulator.log" 2>&1 &
fi

echo "waiting for boot"
"$ADB" -s "$SERIAL" wait-for-device
for _ in $(seq 1 180); do
  [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = "1" ] || {
  echo "device did not finish booting" >&2
  exit 1
}

"$ADB" -s "$SERIAL" shell input keyevent 82 || true

# Wait for the primary user to unlock and for FUSE storage to settle.
for _ in $(seq 1 120); do
  state="$("$ADB" -s "$SERIAL" shell 'dumpsys user | grep -m1 "State:"' 2>/dev/null | tr -d '\r')"
  case "$state" in *RUNNING_UNLOCKED*) break;; esac
  sleep 5
done
for _ in $(seq 1 120); do
  if "$ADB" -s "$SERIAL" shell 'ls /sdcard/Download' >/dev/null 2>&1; then break; fi
  sleep 5
done

"$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0 || true
"$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0 || true
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0 || true

python3 scripts/qa/make_fixtures.py --out build/qa-fixtures

# The app browses storage through SAF, so the files must be in the emulated
# primary storage. Copy through /data/media/0 so a sluggish FUSE mount cannot
# make the push fail halfway.
"$ADB" -s "$SERIAL" push build/qa-fixtures/. /data/local/tmp/qa-fixtures/
"$ADB" -s "$SERIAL" shell 'rm -rf /data/media/0/WaveCueFixtures
  mkdir -p /data/media/0/WaveCueFixtures
  cp /data/local/tmp/qa-fixtures/* /data/media/0/WaveCueFixtures/
  chown -R media_rw:media_rw /data/media/0/WaveCueFixtures
  chmod -R 0660 /data/media/0/WaveCueFixtures' || {
  echo "warning: could not seed fixtures as root; trying /sdcard" >&2
  "$ADB" -s "$SERIAL" root >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" wait-for-device
  "$ADB" -s "$SERIAL" shell mkdir -p /sdcard/WaveCueFixtures
  "$ADB" -s "$SERIAL" push build/qa-fixtures/. /sdcard/WaveCueFixtures/
}

echo "fixtures:"
for _ in $(seq 1 60); do
  if "$ADB" -s "$SERIAL" shell 'ls "/sdcard/WaveCueFixtures/01 Opening.wav"' >/dev/null 2>&1; then
    break
  fi
  sleep 5
done
"$ADB" -s "$SERIAL" shell 'ls /sdcard/WaveCueFixtures' || {
  echo "fixtures are not visible through /sdcard; the storage mount did not come up" >&2
  exit 1
}

set +e
./gradlew :app:connectedDebugAndroidTest --stacktrace --console=plain 2>&1 \
  | tee build/local-connected.log
status=${PIPESTATUS[0]}
set -e

python3 scripts/qa/summarize_results.py || true
echo "connectedDebugAndroidTest exit=$status"
exit "$status"
