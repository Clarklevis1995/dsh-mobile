#!/bin/sh
set -eu

if [ "$#" -eq 0 ]; then
  echo "Usage: $0 <command> [arguments...]" >&2
  exit 64
fi

# Xcode/Android Studio 从 Finder 启动时不会读取终端的 shell 配置。
# 保留调用方已选的 Go；缺失时仅补入实际包含 Go 的安装目录。
if ! command -v go >/dev/null 2>&1; then
  for go_directory in "${GOROOT:+$GOROOT/bin}" /opt/homebrew/bin /usr/local/go/bin /usr/local/bin; do
    if [ -n "$go_directory" ] && [ -x "$go_directory/go" ]; then
      PATH="$go_directory:${PATH:-/usr/bin:/bin}"
      export PATH
      break
    fi
  done
fi

if ! command -v go >/dev/null 2>&1; then
  echo "error: 未找到 Go 编译器。请安装 Go，或通过 PATH/GOROOT 指定安装位置，再重新构建。" >&2
  exit 69
fi

exec "$@"
