#!/usr/bin/env bash
# 下载预编译 sing-box 内核（sb-lxf：基于上游 v1.15.0-alpha.12 + tsbAI 栈 + Leadaxe Android 扩展）
# CI 与本机构建共用；产物 app/libs/libbox.aar 不提交进 git。
#
# sb-lxf = SagerNet/sing-box v1.15.0-alpha.12 + tsbAI Go TUN 栈 + Leadaxe 魔改扩展
#   - tsbAI: 纯 Go TUN 栈 + splice + multi_queue，低功耗优化
#   - balancer pool: urltest round_robin 模式支持 pool_size/pool_tolerance/sticky_hash
#   - XHTTP: Xray splithttp 传输，xmux + padding，始终编译
#   - AWG2/AWG3: 通过 with_awg 构建标签可选启用
#   - chain 出站: 通过 with_lx_chain 构建标签可选启用
set -euo pipefail

LIBBOX_VERSION="${LIBBOX_VERSION:-v1.15.0-ts.1}"
# 默认使用 QWERZXCVA/sb-lxf（tsbAI 内核），基于上游 1.15.0-alpha.12
REPO="${SB_LXF_REPO:-QWERZXCVA/sb-lxf}"
# 内核变体：slim（默认，省电/省空间，drop usbip/openvpn/openconnect + 启用 lx_idle_suspend；
# sb-AI 不引用这些传输，可安全使用）或 full（完整功能集，含 usbip/openvpn/openconnect）。
LIBBOX_VARIANT="${LIBBOX_VARIANT:-slim}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/libs/libbox.aar"
# slim 变体产物名带 -slim 后缀；full 变体保持原名
if [[ "$LIBBOX_VARIANT" == "slim" ]]; then
  AAR_NAME="libbox-${LIBBOX_VERSION#v}-slim.aar"
  SHA_FILE="SHA256SUMS.all"
else
  AAR_NAME="libbox-${LIBBOX_VERSION#v}.aar"
  SHA_FILE="SHA256SUMS"
fi
BASE_URL="https://github.com/$REPO/releases/download/$LIBBOX_VERSION"
MARKER="$ROOT/app/libs/.libbox.version"

mkdir -p "$(dirname "$DEST")"

if [[ -f "$DEST" && -f "$MARKER" && "$(cat "$MARKER")" == "$LIBBOX_VERSION" && "${FORCE:-0}" != "1" ]]; then
  echo "✓ libbox.aar already $LIBBOX_VERSION — skipping"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "→ Fetching $AAR_NAME ($REPO @ $LIBBOX_VERSION, variant=$LIBBOX_VARIANT)"
curl -fsSL --retry 3 -o "$TMP/$AAR_NAME" "$BASE_URL/$AAR_NAME"
# 优先用合并的 SHA256SUMS.all（含 full + slim）；不存在时回退到 SHA256SUMS
curl -fsSL --retry 3 -o "$TMP/SHA256SUMS.all" "$BASE_URL/SHA256SUMS.all" || \
  curl -fsSL --retry 3 -o "$TMP/SHA256SUMS.all" "$BASE_URL/SHA256SUMS"

# 校验 SHA256（slim 变体在 SHA256SUMS.all 中；full 变体在 SHA256SUMS 中）
(
  cd "$TMP"
  if [[ -f "SHA256SUMS.all" ]] && grep -E "  ?$AAR_NAME\$" SHA256SUMS.all > expected.sum 2>/dev/null; then
    sha256sum -c expected.sum
  else
    grep -E "  ?$AAR_NAME\$" SHA256SUMS.all > expected.sum || grep -E "  ?$AAR_NAME\$" <(cat SHA256SUMS.all 2>/dev/null || echo) > expected.sum
    sha256sum -c expected.sum
  fi
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
