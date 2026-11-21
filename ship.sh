#!/usr/bin/env bash
# Build, test and (optionally) publish the MyFiles APK yourself — no AI credits used.
#   ./ship.sh               build + unit tests, then upload to the download link
#   ./ship.sh --fast        skip unit tests (quicker iteration)
#   ./ship.sh --build-only  just build the APK; install it manually (no upload)
set -euo pipefail
cd "$(dirname "$0")"

RUN_TESTS=1
UPLOAD=1
for arg in "$@"; do
  case "$arg" in
    --fast) RUN_TESTS=0 ;;
    --build-only) UPLOAD=0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

APK=app/build/outputs/apk/debug/app-debug.apk
if [ "$RUN_TESTS" = 1 ]; then
  ./gradlew testDebugUnitTest assembleDebug --console=plain
else
  ./gradlew assembleDebug --console=plain
fi
echo "Built: $APK"

if [ "$UPLOAD" = 0 ]; then
  echo "Build-only mode: install manually with 'adb install -r $APK', or copy it to the phone."
  exit 0
fi

openkbs storage upload "$APK" media/folio/MyFiles.apk
openkbs site invalidate /media/folio/MyFiles.apk
echo
echo "Live download: https://d241ahnq6f1f6p.cloudfront.net"
echo "Re-download on the phone (refresh if the browser cached the old file)."