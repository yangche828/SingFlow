#!/usr/bin/env bash
# SingFlow Phase 0: 环境与 Android SDK 自动检查/安装
set -u

echo "=============================================================="
echo " SingFlow 环境自检 (JDK / Android SDK / ADB)"
echo "=============================================================="

# ---------- JDK ----------
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/usr/lib/jvm/java-21-openjdk-amd64" \
    "/usr/lib/jvm/java-17-openjdk-amd64" \
    "$HOME/.jdks"/* ; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if command -v java >/dev/null 2>&1; then
  java -version 2>&1 | head -3
else
  echo "[ERROR] 未找到 java，请安装 JDK 17 或 21"
  exit 1
fi

# ---------- Android SDK ----------
if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/opt/android-sdk" ; do
    if [ -d "$candidate" ]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then
  echo "[ERROR] 未找到 Android SDK，请设置 ANDROID_HOME"
  exit 1
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$PATH"
echo "ANDROID_HOME=$ANDROID_HOME"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKMANAGER" ]; then
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" \
    "platform-tools" \
    "build-tools;35.0.0" \
    "platforms;android-33" >/dev/null 2>&1 || true
  # 尝试安装最新平台（37/36/35），失败不阻塞
  for api in 37 36 35; do
    "$SDKMANAGER" "platforms;android-$api" >/dev/null 2>&1 && break
  done
  "$SDKMANAGER" --list_installed 2>/dev/null | sed -n '1,40p' || true
else
  echo "[WARN] 未找到 sdkmanager，跳过 SDK 自动安装"
fi

# ---------- ADB 设备 ----------
if command -v adb >/dev/null 2>&1; then
  adb version | head -2
  echo "--------------------------------------------------------------"
  adb devices -l
  echo "--------------------------------------------------------------"
  DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  echo "在线设备数量: $DEVICE_COUNT"
  if [ "$DEVICE_COUNT" -gt 0 ]; then
    adb shell getprop ro.build.version.release 2>/dev/null | head -1
    adb shell getprop ro.build.version.sdk 2>/dev/null | head -1
  else
    echo "[INFO] 当前无在线设备；Phase 6 将自动尝试拉起模拟器。"
  fi
else
  echo "[ERROR] 未找到 adb"
  exit 1
fi

echo "[OK] 环境自检完成"
