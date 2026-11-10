#!/usr/bin/env bash
# Pleos Connect 에뮬레이터를 켜고 Virtual Console 을 브라우저로 연다.
#
# 사용법:
#   ./scripts/run-emulator.sh            에뮬레이터 + Virtual Console
#   ./scripts/run-emulator.sh --install  위에 더해 앱 빌드·설치까지
#
# 바꿀 수 있는 값 (환경 변수):
#   AVD_NAME      에뮬레이터 이름 (기본: Pleos_Connect_v2.1.0)
#   ANDROID_HOME  Android SDK 경로 (기본: ~/Library/Android/sdk)
#   JAVA_HOME     --install 때 쓸 JDK (기본: Android Studio 내장 JBR)
#
# 근거: Pleos 문서 "Virtual Console Setup Guide", "Configuring Emulator"
#   - Virtual Console 은 에뮬레이터가 8088 포트로 띄우는 웹 페이지다 (adb forward tcp:8088 tcp:8088 → http://127.0.0.1:8088/)
#   - Quick boot 로 켜면 VHAL 을 쓸 수 없어 Cold boot 로 켠다 (-no-snapshot-load)
set -euo pipefail

AVD_NAME="${AVD_NAME:-Pleos_Connect_v2.1.0}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
CONSOLE_PORT=8088
CONSOLE_URL="http://127.0.0.1:${CONSOLE_PORT}/"
BOOT_TIMEOUT_S=300
CONSOLE_TIMEOUT_S=120

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
LOG_FILE="${TMPDIR:-/tmp}/catmeter-emulator.log"

INSTALL=false
for arg in "$@"; do
  case "$arg" in
    --install) INSTALL=true ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $arg (--install, --help 만 쓸 수 있어요)" >&2; exit 1 ;;
  esac
done

[[ -x "$EMULATOR" ]] || { echo "에뮬레이터를 찾을 수 없어요: $EMULATOR (ANDROID_HOME 을 확인해 주세요)" >&2; exit 1; }
[[ -x "$ADB" ]] || { echo "adb 를 찾을 수 없어요: $ADB" >&2; exit 1; }
if ! "$EMULATOR" -list-avds | grep -qx "$AVD_NAME"; then
  echo "AVD '$AVD_NAME' 가 없어요. 있는 AVD:" >&2
  "$EMULATOR" -list-avds >&2
  echo "AVD_NAME=이름 ./scripts/run-emulator.sh 로 다시 실행해 주세요." >&2
  exit 1
fi

# 이미 켜진 에뮬레이터가 있으면 그대로 쓴다
serial="$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/ {print $1; exit}')"
if [[ -z "$serial" ]]; then
  echo "▶ 에뮬레이터 켜는 중: $AVD_NAME (Cold boot, 로그: $LOG_FILE)"
  nohup "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-load >"$LOG_FILE" 2>&1 &
  for _ in $(seq 1 60); do
    serial="$("$ADB" devices | awk '/^emulator-[0-9]+\t/ {print $1; exit}')"
    [[ -n "$serial" ]] && break
    sleep 2
  done
  [[ -n "$serial" ]] || { echo "에뮬레이터가 adb 에 나타나지 않았어요. 로그를 확인해 주세요: $LOG_FILE" >&2; exit 1; }
else
  echo "▶ 이미 켜진 에뮬레이터를 써요: $serial"
fi

echo "▶ 부팅 기다리는 중 ($serial)"
"$ADB" -s "$serial" wait-for-device
waited=0
until [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  if (( waited >= BOOT_TIMEOUT_S )); then
    echo "${BOOT_TIMEOUT_S}초 안에 부팅이 끝나지 않았어요. 로그: $LOG_FILE" >&2
    exit 1
  fi
  sleep 3
  waited=$((waited + 3))
done
echo "✓ 부팅 완료"

if $INSTALL; then
  echo "▶ 앱 빌드·설치 중"
  export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
  (cd "$ROOT_DIR" && ANDROID_SERIAL="$serial" ./gradlew installDebug --console=plain -q)
  echo "✓ 설치 완료"
fi

echo "▶ Virtual Console 연결 중 (포트 $CONSOLE_PORT)"
"$ADB" -s "$serial" forward "tcp:$CONSOLE_PORT" "tcp:$CONSOLE_PORT"
waited=0
until curl -s -o /dev/null --max-time 2 "$CONSOLE_URL"; do
  if (( waited >= CONSOLE_TIMEOUT_S )); then
    echo "Virtual Console 이 응답하지 않아요: $CONSOLE_URL" >&2
    echo "PC 에서 $CONSOLE_PORT 포트를 다른 프로그램이 쓰고 있는지 확인하거나, 잠시 뒤 이 스크립트를 다시 실행해 주세요." >&2
    exit 1
  fi
  sleep 3
  waited=$((waited + 3))
done

open "$CONSOLE_URL"
echo "✓ Virtual Console 을 브라우저로 열었어요: $CONSOLE_URL"
