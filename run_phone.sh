#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

PACKAGE=dev.davidv.londontransport
APK=app/build/outputs/apk/debug/app-debug.apk

get_serial () {
  while read -r line; do
    [[ -z "$line" || "$line" == "List of devices attached" ]] && continue
    id=$(awk '{print $1}' <<< "$line")
    state=$(awk '{print $2}' <<< "$line")
    [[ "$state" != "device" ]] && continue
    if grep -qi 'model:LE2123' <<< "$line"; then
      echo $id
      break
    fi
  done < <(adb devices -l | uniq)
}

serial="$(get_serial)"
if [[ -z "$serial" ]]; then
  echo "No phone (model:LE2123) found among attached devices:" >&2
  connect_adb_wifi || exit 1
  serial="$(get_serial)"
fi

echo "Using phone: $serial"

./gradlew :app:assembleDebug

adb -s "$serial" shell input keyevent KEYCODE_WAKEUP

if [[ "$#" -gt 0 ]] && [[ "$1" == "--tail" ]]; then
  adb -s "$serial" logcat -c
fi

~/Downloads/android run --apks "$APK" --device "$serial"

if [[ "$#" -gt 0 ]] && [[ "$1" == "--tail" ]]; then
  for _ in $(seq 20); do
    pid="$(adb -s "$serial" shell pidof -s "$PACKAGE" | tr -d '\r')"
    [[ -n "$pid" ]] && break
    sleep 0.5
  done
  if [[ -n "${pid:-}" ]]; then
    adb -s "$serial" logcat --pid="$pid" "*:V"
  else
    echo "App process not running" >&2
  fi
fi
