#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEVECO_PATH="${DEVECO_STUDIO_PATH:-/Applications/DevEco-Studio.app/Contents}"
MODE="${1:-all}"
if [[ ! -d "$DEVECO_PATH/sdk" ]]; then
  echo "找不到 DevEco Studio，请设置 DEVECO_STUDIO_PATH 指向其 Contents 目录。" >&2
  exit 1
fi
export JAVA_HOME="${JAVA_HOME:-$DEVECO_PATH/jbr/Contents/Home}"
export PATH="$DEVECO_PATH/tools/node/bin:$DEVECO_PATH/tools/ohpm/bin:$PATH"
export DEVECO_SDK_HOME="$DEVECO_PATH/sdk"
case "$MODE" in
  test)
    python3 "$ROOT/harmony/testing/generate_ui_messages.py" --check
    "$ROOT/harmony/gradlew" -p "$ROOT/harmony" :shared:jvmTest --console=plain
    node --test "$ROOT/harmony/testing/transport.test.cjs" "$ROOT/harmony/testing/host_repository.test.cjs" "$ROOT/harmony/testing/navigation.test.cjs" "$ROOT/harmony/testing/markdown_model.test.cjs" "$ROOT/harmony/testing/schedule_dates.test.cjs" "$ROOT/harmony/testing/cache_controller.test.cjs" "$ROOT/harmony/testing/notifications.test.cjs" "$ROOT/harmony/testing/ui_messages.test.cjs" "$ROOT/harmony/testing/workspace_controller.test.cjs" "$ROOT/harmony/testing/host_runtime.test.cjs" "$ROOT/harmony/testing/markdown_parser.test.cjs"
    exit 0 ;;
  smoke)
    "$ROOT/harmony/gradlew" -p "$ROOT/harmony" :shared:publishNative --console=plain
    exec python3 "$ROOT/harmony/testing/smoke_ui.py" ;;
  native|all) "$ROOT/harmony/gradlew" -p "$ROOT/harmony" :shared:publishNative --console=plain ;;
  app) ;;
  *) echo "用法：$0 [test|native|app|all|smoke]" >&2; exit 1 ;;
esac
[[ "$MODE" == native ]] && exit 0
cd "$ROOT/harmonyApp"
ohpm install --all
node "$DEVECO_PATH/tools/hvigor/bin/hvigorw.js" --mode module \
  -p module=entry@default -p product=default -p buildMode=debug assembleHap --no-daemon
