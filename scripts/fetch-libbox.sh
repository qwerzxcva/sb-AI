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

LIBBOX_VERSION="${LIBBOX_VERSION:-v1.15.0-ts.7}"
# 默认使用 QWERZXCVA/sb-lxf（tsbAI 内核），基于上游 1.15.0-alpha.12
REPO="${SB_LXF_REPO:-QWERZXCVA/sb-lxf}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/libs/libbox.aar"
# slim = sb-lxf 精简内核（去 usbip/openvpn/openconnect，sb-AI 均未引用）；full = 全量。
# slim AAR 比 full 小 ~10MB，功耗/体积更优；默认走 slim。
LIBBOX_VARIANT="${LIBBOX_VARIANT:-slim}"
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

if [[ -f "$DEST" && -f "$MARKER" && "$(cat "$MARKER")" == "${LIBBOX_VERSION}/${LIBBOX_VARIANT}" && "${FORCE:-0}" != "1" ]]; then
  echo "✓ libbox.aar already ${LIBBOX_VERSION} (${LIBBOX_VARIANT}) — skipping"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "→ Fetching $AAR_NAME ($REPO @ $LIBBOX_VERSION, variant=$LIBBOX_VARIANT)"
# 下载 AAR；slim 变体若 404（旧 release 可能没有 -slim 产物）自动回退到 full
if ! curl -fsSL --retry 3 -o "$TMP/$AAR_NAME" "$BASE_URL/$AAR_NAME" 2>/dev/null; then
  if [[ "$LIBBOX_VARIANT" == "slim" ]]; then
    echo "  ⚠ slim AAR 不存在，回退到 full 变体"
    LIBBOX_VARIANT="full"
    AAR_NAME="libbox-${LIBBOX_VERSION#v}.aar"
    SHA_FILE="SHA256SUMS"
    curl -fsSL --retry 3 -o "$TMP/$AAR_NAME" "$BASE_URL/$AAR_NAME"
  else
    curl -fsSL --retry 3 -o "$TMP/$AAR_NAME" "$BASE_URL/$AAR_NAME"
  fi
fi
# 优先用合并的 SHA256SUMS.all（含 full + slim）；不存在时回退到 SHA256SUMS
if ! curl -fsSL --retry 3 -o "$TMP/$SHA_FILE" "$BASE_URL/$SHA_FILE" 2>/dev/null; then
  [[ "$SHA_FILE" != "SHA256SUMS" ]] && curl -fsSL --retry 3 -o "$TMP/SHA256SUMS" "$BASE_URL/SHA256SUMS" && SHA_FILE="SHA256SUMS"
fi

# 校验 SHA256
(
  cd "$TMP"
  grep -E "  ?$AAR_NAME\$" "$SHA_FILE" > expected.sum || { echo "no SHA256 entry for $AAR_NAME"; exit 0; }
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

printf '%s' "${LIBBOX_VERSION}/${LIBBOX_VARIANT}" > "$MARKER"
echo "✅ $DEST ← ${LIBBOX_VERSION}/${LIBBOX_VARIANT} ($(du -h "$DEST" | cut -f1))"
