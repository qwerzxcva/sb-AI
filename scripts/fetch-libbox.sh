#!/usr/bin/env bash
# 下载预编译 sing-box 内核（AndroidLibBoxLite，arm64-v8a）。
# CI 与本机构建共用；产物 app/libs/libbox.aar 不提交进 git。
set -euo pipefail

LIBBOX_VERSION="${LIBBOX_VERSION:-v1.15.0-alpha.9-reF1nd}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs/libbox.aar"
URL="https://github.com/Asterisk4Magisk/AndroidLibBoxLite/releases/download/${LIBBOX_VERSION}/libbox.aar"

mkdir -p "$(dirname "$DEST")"

if [[ -f "$DEST" && "${FORCE:-0}" != "1" ]]; then
  echo "libbox.aar already present: $DEST"
  exit 0
fi

echo "Downloading libbox ${LIBBOX_VERSION} ..."
curl -fL --retry 3 -o "$DEST" "$URL"
echo "Saved to $DEST ($(du -h "$DEST" | cut -f1))"
