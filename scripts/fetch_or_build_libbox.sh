#!/usr/bin/env bash
# SingFlow Phase 4: 获取或构建 16KB Page Size 对齐的 libbox.aar
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="$ROOT/app/libs"
AAR="$LIBS_DIR/libbox.aar"
mkdir -p "$LIBS_DIR"

echo "=============================================================="
echo " SingFlow libbox.aar 获取/构建"
echo "=============================================================="

if [ -f "$AAR" ]; then
  echo "[OK] 已存在 $AAR ($(du -h "$AAR" | cut -f1))"
  exit 0
fi

# 1) 尝试预编译产物（若网络可达）
PREBUILT_URL="${SINGFLOW_LIBBOX_URL:-}"
if [ -n "$PREBUILT_URL" ]; then
  echo "[INFO] 下载预编译 libbox: $PREBUILT_URL"
  if curl -fL --retry 2 -o "$AAR" "$PREBUILT_URL"; then
    echo "[OK] 下载完成"
    exit 0
  fi
  rm -f "$AAR"
fi

# 2) 本地 Go + gomobile + NDK 编译
if command -v go >/dev/null 2>&1 && [ -n "${ANDROID_NDK_HOME:-}" ]; then
  echo "[INFO] 检测到 Go 与 NDK，开始源码编译 libbox.aar"
  WORK="$ROOT/.build-libbox"
  mkdir -p "$WORK"
  if [ ! -d "$WORK/sing-box" ]; then
    git clone --depth 1 --branch v1.14.3 https://github.com/SagerNet/sing-box.git "$WORK/sing-box"
  fi
  cd "$WORK/sing-box"
  go install golang.org/x/mobile/cmd/gomobile@latest
  export PATH="$PATH:$(go env GOPATH)/bin"
  gomobile init
  export CGO_LDFLAGS="-Wl,-z,max-page-size=16384"
  (cd experimental/libbox && gomobile bind -target=android/arm64,android/arm,android/amd64 \
      -androidapi 33 -trimpath -buildvcs=false \
      -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" -o "$AAR" .)
  echo "[OK] libbox.aar 已生成: $AAR"
  exit 0
fi

echo "[WARN] 未找到 libbox.aar，且缺少 Go / NDK / 预编译下载地址。"
echo "[WARN] SingFlow 将以「TUN 诊断回退引擎」运行：真实创建系统 VPN 网卡、"
echo "       完整编译并展示 sing-box 配置，但不会转发流量。"
echo "       设置 SINGFLOW_LIBBOX_URL 环境变量或执行 .github/workflows/build-libbox.yml 可启用原生内核。"
exit 0
