#!/usr/bin/env bash
# 下载预编译 sing-box 内核（LxBox 同款 fork：Leadaxe/sing-box-lx，含 balancer/pool、AWG2、XHTTP 扩展）。
# CI 与本机构建共用；产物 app/libs/libbox.aar 不提交进 git。
#
# 为什么用 sing-box-lx 而不是上游/AsteriskBOX 的 AndroidLibBoxLite：
#   负载均衡「仅使用 N 个节点」依赖 fork 特有的 outbound.balancer{pool,pool_tolerance,sticky_hash}
#   与 urltest.mode=round_robin 扩展，上游 sing-box 没有这些字段。
set -euo pipefail

LIBBOX_VERSION="${LIBBOX_VERSION:-v1.14.2-lx.11}"
REPO="Leadaxe/sing-box-lx"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/libs/libbox.aar"
AAR_NAME="libbox-${LIBBOX_VERSION#v}.aar"
BASE_URL="https://github.com/$REPO/releases/download/$LIBBOX_VERSION"
MARKER="$ROOT/app/libs/.libbox.version"

mkdir -p "$(dirname "$DEST")"

if [[ -f "$DEST" && -f "$MARKER" && "$(cat "$MARKER")" == "$LIBBOX_VERSION" && "${FORCE:-0}" != "1" ]]; then
  echo "✓ libbox.aar already $LIBBOX_VERSION — skipping"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "→ Fetching $AAR_NAME ($REPO @ $LIBBOX_VERSION)"
curl -fsSL --retry 3 -o "$TMP/$AAR_NAME" "$BASE_URL/$AAR_NAME"
curl -fsSL --retry 3 -o "$TMP/SHA256SUMS" "$BASE_URL/SHA256SUMS"

# 校验 SHA256（与 LxBox 的 fetch-libbox.sh 同策略）
(
  cd "$TMP"
  grep -E "  ?$AAR_NAME\$" SHA256SUMS > expected.sum
  sha256sum -c expected.sum
)

# 仅保留 arm64-v8a（项目要求只支持 ARMv8），把 115MB 降到 ~79MB
python3 - "$TMP/$AAR_NAME" "$DEST" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src)
keep = [n for n in zin.namelist()
        if not n.startswith('jni/') or n.startswith('jni/arm64-v8a/')]
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_STORED) as zout:
    for n in keep:
        zi = zipfile.ZipInfo(n, date_time=(2026, 1, 1, 0, 0, 0))
        zi.external_attr = 0o644 << 16
        zout.writestr(zi, zin.read(n))
abis = sorted({n.split('/')[1] for n in keep if n.startswith('jni/')})
print(f"✓ kept ABIs: {abis}")
assert abis == ['arm64-v8a'], f"expected arm64-v8a only, got {abis}"
PY

printf '%s' "$LIBBOX_VERSION" > "$MARKER"
echo "✅ $DEST ← $LIBBOX_VERSION ($(du -h "$DEST" | cut -f1))"
