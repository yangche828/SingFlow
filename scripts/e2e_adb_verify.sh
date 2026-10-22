#!/usr/bin/env bash
# SingFlow Phase 6：端到端自动验收（Linux / macOS / Git Bash）
#
# 与 scripts/e2e_adb_verify.ps1 行为一致：安装 -> 导入节点 -> 建立 VPN ->
# 断言 TUN/前台服务/无崩溃 -> 断言真实转发（浏览器出网）。
#
# 参数通过环境变量传入（都有默认值）：
#   NODE_URI='vless://<uuid>@<host>:<port>?...#MyNode'  自备节点；留空则用本机回环节点
#   PROXY_PORT=8443       本机回环节点端口（NODE_URI 为空时生效，脚本会自动 adb reverse）
#   PROXY_LOG=/path/log   代理服务端日志，用于断言「流量确实到达了服务端」
#   REMOTE_DNS=...        连接前通过界面修改「远程 DoH / DoT」
#   TEST_URL=...          浏览器打开的地址
#   EXPECT_TEXT=...       页面里应出现的文本
#   APK=/path/app.apk  KEEP_DATA=1  SKIP_INSTALL=1  CONNECT_TIMEOUT=120  LOG_FILE=/path/log
#
# 本地回环验收示例：
#   node scripts/dev/vless-loopback-server.js --port 8443 &
#   PROXY_LOG=/tmp/vless.log ./scripts/e2e_adb_verify.sh
#
# 退出码：0 全部通过；1 存在失败项；2 无可用设备；3 安装失败；4 进程未存活。
set -u

# Git Bash(MSYS) 下避免把 /sdcard/... 这类设备路径改写成 Windows 路径
export MSYS_NO_PATHCONV=1 2>/dev/null || true
export MSYS2_ARG_CONV_EXCL='*' 2>/dev/null || true

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.singflow.android"
ACTIVITY="$PKG/.MainActivity"
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
NODE_URI="${NODE_URI:-}"
PROXY_PORT="${PROXY_PORT:-8443}"
PROXY_LOG="${PROXY_LOG:-}"
REMOTE_DNS="${REMOTE_DNS:-}"
TEST_URL="${TEST_URL:-}"
EXPECT_TEXT="${EXPECT_TEXT:-documentation examples}"
KEEP_DATA="${KEEP_DATA:-0}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-120}"
LOG_FILE="${LOG_FILE:-}"
LOOPBACK_UUID="11111111-1111-1111-1111-111111111111"

if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/d/Apps/Android/Sdk" "/opt/android-sdk" "/usr/lib/android-sdk"; do
    if [ -d "$candidate" ]; then export ANDROID_HOME="$candidate"; break; fi
  done
fi
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

SERIAL=""
FAILURES=0
TOTAL=0

say() {
  echo "$1"
  [ -n "$LOG_FILE" ] && printf '%s\n' "$1" >>"$LOG_FILE"
  return 0
}
section() { say ""; say "=== $1 ==="; }
# check <名称> <1|0> [详情]
check() {
  TOTAL=$((TOTAL + 1))
  if [ "$2" = "1" ]; then
    say "[PASS] $1${3:+ | $3}"
  else
    FAILURES=$((FAILURES + 1))
    say "[FAIL] $1${3:+ | $3}"
  fi
}
warn() { say "[WARN] $1"; }
adbx() { adb -s "$SERIAL" "$@"; }

# adb 是原生的 Windows 程序（Git Bash 下），host 侧路径必须转成 Windows 形式。
# 注意：脚本里已经关掉 MSYS 的自动路径转换，所以这里显式转换。
to_win_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s' "$1"
  fi
}

# 从 APK 清单里读 foregroundServiceType（如 0x40000400），用于断言 systemExempted 位。
# 各 API 的 dumpsys 输出不一致，清单是跨版本最稳的证据来源。
manifest_fgs_type() {
  local aapt out
  aapt="$(ls -1 "${ANDROID_HOME:-}/build-tools"/*/aapt2 "${ANDROID_HOME:-}/build-tools"/*/aapt2.exe 2>/dev/null | sort -V | tail -1)"
  [ -z "$aapt" ] && return 1
  # aapt2 是原生程序，APK 路径同样要转成 Windows 形式（Git Bash）
  out="$("$aapt" dump xmltree "$(to_win_path "$APK")" --file AndroidManifest.xml 2>/dev/null)"
  printf '%s' "$out" | sed -n 's/.*foregroundServiceType([^)]*)=\(0x[0-9a-fA-F]*\).*/\1/p' | head -1
}

# ---------------------------------------------------------------- UI 辅助
ui_dump() {
  local attempt out
  for attempt in 1 2 3; do
    # 先删掉设备上的旧快照，否则 dump 失败时会读到上一次的陈旧内容
    adbx shell "timeout 5 rm -f /sdcard/singflow_ui.xml" >/dev/null 2>&1 || true
    adbx shell "timeout 25 uiautomator dump /sdcard/singflow_ui.xml" >/dev/null 2>&1 || true
    out="$(adbx shell "timeout 10 cat /sdcard/singflow_ui.xml" 2>/dev/null | tr -d '\r')"
    case "$out" in
      *"<hierarchy"*) printf '%s' "$out"; return 0 ;;
    esac
    sleep 2
  done
  printf ''
}
ui_text() { ui_dump | grep -o 'text="[^"]*"' | sed 's/^text="//; s/"$//' | tr '\n' '|'; }
# 输出 "文本|中心x|中心y|class"，供精确匹配与点击
ui_nodes() {
  ui_dump | tr '>' '\n' | grep 'text="[^"]*"' | while IFS= read -r node; do
    text="$(printf '%s' "$node" | sed -n 's/.*text="\([^"]*\)".*/\1/p')"
    cls="$(printf '%s' "$node" | sed -n 's/.*class="\([^"]*\)".*/\1/p')"
    bounds="$(printf '%s' "$node" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')"
    [ -z "$bounds" ] && continue
    set -- $bounds
    echo "$text|$((($1 + $3) / 2))|$((($2 + $4) / 2))|$cls"
  done
}
# 设置页第一个输入框就是「远程 DoH / DoT」；冷启动时可能还没回填文本
ui_dns_field() {
  local nodes by_content
  nodes="$(ui_nodes)"
  by_content="$(printf '%s\n' "$nodes" | awk -F'|' '$4 ~ /EditText/ && $1 ~ /:\/\// { print; exit }')"
  if [ -n "$by_content" ]; then printf '%s' "$by_content"; return 0; fi
  if printf '%s\n' "$nodes" | grep -q 'DoH'; then
    printf '%s\n' "$nodes" | awk -F'|' '$4 ~ /EditText/ { print; exit }'
    return 0
  fi
  return 1
}
ui_tap_text() {
  local want="$1" line
  line="$(ui_nodes | awk -F'|' -v w="$want" '$1 == w { print; exit }')"
  [ -z "$line" ] && return 1
  adbx shell input tap "$(printf '%s' "$line" | cut -d'|' -f2)" "$(printf '%s' "$line" | cut -d'|' -f3)" >/dev/null 2>&1
  sleep 1
  return 0
}
ui_tap_contains() {
  local want="$1" line
  line="$(ui_nodes | grep -F "$want" | head -1)"
  [ -z "$line" ] && return 1
  adbx shell input tap "$(printf '%s' "$line" | cut -d'|' -f2)" "$(printf '%s' "$line" | cut -d'|' -f3)" >/dev/null 2>&1
  sleep 1
  return 0
}
# 低配模拟器上 SystemUI 偶发 ANR，弹窗会盖住真实界面、让 UI 断言读到弹窗文本。
# 这里自动点掉弹窗，避免把环境抖动误判为应用缺陷。
ui_clear_anr() {
  local line
  line="$(ui_nodes | awk -F'|' '$1 == "Wait" { print; exit }')"
  [ -z "$line" ] && return 1
  warn "检测到系统 ANR 弹窗（System UI isn't responding），自动点击 Wait 后继续"
  adbx shell input tap "$(printf '%s' "$line" | cut -d'|' -f2)" "$(printf '%s' "$line" | cut -d'|' -f3)" >/dev/null 2>&1 || true
  sleep 3
  return 0
}

# 通过界面把「远程 DoH / DoT」改成指定值（best-effort，失败只告警）
set_remote_dns() {
  local dns="$1" i field current save
  section "修改远程 DNS（界面自动化）"
  adbx shell am start -n "$ACTIVITY" >/dev/null 2>&1 || true
  sleep 3
  if ! ui_tap_text "设置"; then warn "未找到底部「设置」Tab，跳过 DNS 修改"; return 1; fi

  field=""
  for i in $(seq 1 6); do
    field="$(ui_dns_field || true)"
    [ -n "$field" ] && break
    if [ "$i" = "3" ]; then
      # 可能是「设置」Tab 点击未生效，重新点一次
      ui_tap_text "设置" || true
    else
      adbx shell input swipe 160 200 160 520 >/dev/null 2>&1 || true
    fi
    sleep 1
  done
  [ -z "$field" ] && { warn "未找到远程 DNS 输入框，跳过 DNS 修改"; return 1; }

  adbx shell input tap "$(printf '%s' "$field" | cut -d'|' -f2)" "$(printf '%s' "$field" | cut -d'|' -f3)" >/dev/null 2>&1
  sleep 1
  adbx shell input keyevent 123 >/dev/null 2>&1 || true
  adbx shell 'for i in $(seq 1 48); do input keyevent 67; done' >/dev/null 2>&1 || true
  adbx shell input text "$dns" >/dev/null 2>&1 || true
  adbx shell input keyevent 4 >/dev/null 2>&1 || true  # 收起输入法，否则后续滑动会污染输入框
  sleep 1

  current="$(ui_dns_field | cut -d'|' -f1 || true)"
  if [ "$current" != "$dns" ]; then
    warn "远程 DNS 输入框内容为 '$current'，期望 '$dns'"
    return 1
  fi
  say "远程 DNS 输入框已填入: $current"

  save=""
  for i in $(seq 1 5); do
    save="$(ui_nodes | grep '保存' | head -1)"
    [ -n "$save" ] && break
    adbx shell input swipe 160 520 160 200 >/dev/null 2>&1 || true
    sleep 1
  done
  [ -z "$save" ] && { warn "未找到「保存」按钮，DNS 修改可能未生效"; return 1; }
  adbx shell input tap "$(printf '%s' "$save" | cut -d'|' -f2)" "$(printf '%s' "$save" | cut -d'|' -f3)" >/dev/null 2>&1
  sleep 2

  # 保存按钮在表单下方，回读字段前先滚回顶部
  current=""
  for i in $(seq 1 5); do
    current="$(ui_dns_field | cut -d'|' -f1 || true)"
    [ -n "$current" ] && break
    adbx shell input swipe 160 200 160 520 >/dev/null 2>&1 || true
    sleep 1
  done
  if [ "$current" = "$dns" ]; then say "[OK] 远程 DNS 已保存: $dns"; return 0; fi
  warn "保存后回读 DNS 失败（读到 '$current'）"
  return 1
}

# ---------------------------------------------------------------- 0. 设备与参数
DEVICE_LINE="$(adb devices | awk 'NR>1 && $2=="device" { print; exit }')"
if [ -z "$DEVICE_LINE" ]; then
  # 蓝图 Phase 6 情况 B：没有在线设备时自动拉起本机已有 AVD
  echo "未检测到在线设备，尝试自动启动本机模拟器（AVD）..."
  EMULATOR_BIN="${ANDROID_HOME:-}/emulator/emulator"
  if [ -x "$EMULATOR_BIN" ]; then
    AVDS="$(emulator -list-avds 2>/dev/null | grep -v '^\s*$' || true)"
    AVD="$(printf '%s\n' "$AVDS" | grep SingFlow | head -1)"
    [ -z "$AVD" ] && AVD="$(printf '%s\n' "$AVDS" | head -1)"
    if [ -n "$AVD" ]; then
      echo "启动 AVD: $AVD（无窗口模式；如需可见界面请自行先启动模拟器）"
      nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect >/dev/null 2>&1 &
      BOOT_DEADLINE=$(( $(date +%s) + 480 ))
      while [ "$(date +%s)" -lt "$BOOT_DEADLINE" ]; do
        sleep 10
        DEVICE_LINE="$(adb devices | awk 'NR>1 && $2=="device" { print; exit }')"
        if [ -n "$DEVICE_LINE" ]; then
          BOOTED="$(adb -s "$(printf '%s' "$DEVICE_LINE" | awk '{print $1}')" shell "timeout 15 getprop sys.boot_completed" 2>/dev/null | tr -d '\r')"
          [ "$BOOTED" = "1" ] && { echo "模拟器已就绪"; break; }
        fi
      done
    else
      echo "未找到任何 AVD（emulator -list-avds 为空）"
    fi
  else
    echo "未找到 emulator 可执行文件: $EMULATOR_BIN"
  fi
  DEVICE_LINE="$(adb devices | awk 'NR>1 && $2=="device" { print; exit }')"
  if [ -z "$DEVICE_LINE" ]; then
    echo "[🔔 用户操作提醒] 没有可用设备：请连接 Android 13~17 手机并开启 USB 调试，或手动启动模拟器后重跑本脚本。"
    exit 2
  fi
fi
SERIAL="$(printf '%s' "$DEVICE_LINE" | awk '{print $1}')"
API="$(adbx shell "timeout 15 getprop ro.build.version.sdk" 2>/dev/null | tr -d '\r')"
RELEASE="$(adbx shell "timeout 15 getprop ro.build.version.release" 2>/dev/null | tr -d '\r')"
ABI="$(adbx shell "timeout 15 getprop ro.product.cpu.abi" 2>/dev/null | tr -d '\r')"

[ -z "$LOG_FILE" ] && LOG_FILE="$ROOT/acceptance/e2e_api$API.log"
mkdir -p "$(dirname "$LOG_FILE")"
: >"$LOG_FILE"

say "=============================================================="
say " SingFlow E2E 验收（Phase 6）"
say " 时间: $(date '+%Y-%m-%d %H:%M:%S')"
say " 设备: $SERIAL  Android $RELEASE (API $API) $ABI"
say "=============================================================="
if [ "$API" -ge 33 ] 2>/dev/null; then
  check "运行环境满足 minSdk 33" 1 "API $API"
else
  check "运行环境满足 minSdk 33" 0 "API $API"
fi

if [ ! -f "$APK" ]; then
  say "[ERROR] 未找到 APK：$APK（请先执行 ./gradlew :app:assembleDebug）"
  exit 3
fi
say "APK: $APK ($(( $(wc -c <"$APK") / 1048576 )) MB)"
APK_FOR_ADB="$(to_win_path "$APK")"
[ -z "$TEST_URL" ] && TEST_URL="https://www.example.com/?singflow=$RANDOM"

USE_LOOPBACK=0
if [ -z "$NODE_URI" ]; then
  USE_LOOPBACK=1
  NODE_URI="vless://$LOOPBACK_UUID@127.0.0.1:$PROXY_PORT?encryption=none&security=none&type=tcp#SingFlow-LoopBack"
  say "未指定 NODE_URI，使用本机回环节点: 127.0.0.1:$PROXY_PORT"
fi
NODE_NAME="$(printf '%s' "$NODE_URI" | sed -n 's/.*#\(.*\)$/\1/p')"
# 节点名在 URI 里是 percent-encoded（含中文时很常见），数据库里存的是解码后的名字
[ -n "$NODE_NAME" ] && NODE_NAME="$(printf '%b' "$(printf '%s' "$NODE_NAME" | sed 's/%/\\x/g')")"
[ -z "$NODE_NAME" ] && NODE_NAME="SingFlow-E2E-Node"
say "节点: $NODE_URI"

if [ "$USE_LOOPBACK" = "1" ]; then
  listening=0
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | grep -q ":$PROXY_PORT " && listening=1
  else
    netstat -an 2>/dev/null | grep -q ":$PROXY_PORT .*LISTEN" && listening=1
  fi
  check "本机代理服务端已监听 $PROXY_PORT" "$listening" "请先运行: node scripts/dev/vless-loopback-server.js --port $PROXY_PORT"
  adbx reverse "tcp:$PROXY_PORT" "tcp:$PROXY_PORT" >/dev/null 2>&1 || true
  check "adb reverse 已建立" "$(adbx reverse --list 2>/dev/null | grep -q "tcp:$PROXY_PORT" && echo 1 || echo 0)"
fi

# ---------------------------------------------------------------- 1. 安装与授权
section "安装与授权"
if [ "$SKIP_INSTALL" != "1" ]; then
  say "[*] 正在安装 APK（大包上传可能需要 1~2 分钟）..."
  INSTALL_OUT="$(adbx install -r -t "$APK_FOR_ADB" 2>&1 | tr -d '\r')"
  say "$INSTALL_OUT"
  if ! printf '%s' "$INSTALL_OUT" | grep -q "Success"; then
    check "APK 安装成功" 0 "adb install 返回非 Success"
    exit 3
  fi
  check "APK 安装成功" 1
else
  warn "已跳过安装（SKIP_INSTALL=1）"
fi

if [ "$KEEP_DATA" != "1" ]; then
  say "清空应用数据（KEEP_DATA=1 可保留已保存的节点/DNS）"
  adbx shell pm clear "$PKG" >/dev/null 2>&1 || true
fi
adbx shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adbx shell appops set "$PKG" ACTIVATE_VPN allow >/dev/null 2>&1 || true

[ -n "$REMOTE_DNS" ] && set_remote_dns "$REMOTE_DNS"

# ---------------------------------------------------------------- 2. 导入节点并连接
section "导入节点并建立隧道"
adbx logcat -c >/dev/null 2>&1 || true
adbx shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 1
adbx shell "am start -n $ACTIVITY --es EXTRA_IMPORT_URI '$NODE_URI' --ez EXTRA_AUTO_CONNECT true" >/dev/null 2>&1 || true

VPN_UP=0
TUN_INFO=""
ELAPSED=0
while [ "$ELAPSED" -lt "$CONNECT_TIMEOUT" ]; do
  sleep 3
  ELAPSED=$((ELAPSED + 3))
  if adbx shell "timeout 20 dumpsys window" 2>/dev/null | grep -q "VpnDialogs"; then
    say "[🔔 用户操作提醒] 首次 VPN 授权框已弹出，脚本正在自动确认（如未生效请手动点「确定」）"
    adbx shell input keyevent KEYCODE_TAB >/dev/null 2>&1 || true
    adbx shell input keyevent KEYCODE_ENTER >/dev/null 2>&1 || true
  fi
  TUN_INFO="$(adbx shell "timeout 15 ip addr show tun0" 2>/dev/null | tr -d '\r')"
  if printf '%s' "$TUN_INFO" | grep -q "172.19.0.1"; then VPN_UP=1; break; fi
done
if [ "$VPN_UP" = "1" ]; then say "$(printf '%s\n' "$TUN_INFO" | head -2)"; fi
check "tun0 已建立（172.19.0.1/30）" "$VPN_UP"
if [ "$VPN_UP" != "1" ]; then
  say "--- 最近日志 ---"
  adbx shell "timeout 20 logcat -d -t 60" 2>/dev/null | tr -d '\r' | while IFS= read -r line; do say "$line"; done
  say "[ERROR] VPN 未建立，后续真实转发断言无意义"
  [ "$FAILURES" -gt 0 ] || FAILURES=1
  exit 1
fi
MTU="$(printf '%s' "$TUN_INFO" | sed -n 's/.*mtu \([0-9]*\).*/\1/p' | head -1)"
check "tun0 MTU 已按设置生效" "$([ "$MTU" = "9000" ] && echo 1 || echo 0)" "mtu=$MTU"

PID="$(adbx shell "timeout 15 pidof $PKG" 2>/dev/null | tr -d '\r')"
check "应用进程存活" "$([ -n "$PID" ] && echo 1 || echo 0)" "pid=$PID"
[ -z "$PID" ] && exit 4

# ---------------------------------------------------------------- 3. 内核与系统状态
section "内核与系统状态"
LOG_ALL="$(adbx shell "timeout 30 logcat -d -v brief" 2>/dev/null | tr -d '\r')"
JAVA_CRASH="$(printf '%s' "$LOG_ALL" | grep -A4 "FATAL EXCEPTION" | grep "$PKG" || true)"
NATIVE_CRASH="$(printf '%s' "$LOG_ALL" | grep ">>> $PKG <<<" || true)"
check "无 Java 崩溃（FATAL EXCEPTION）" "$([ -z "$JAVA_CRASH" ] && echo 1 || echo 0)"
check "无原生崩溃（tombstone）" "$([ -z "$NATIVE_CRASH" ] && echo 1 || echo 0)"

DB_PATH="/data/data/$PKG/databases/singflow.db"
NODE_ROWS="$(adbx shell "timeout 20 run-as $PKG sqlite3 $DB_PATH 'select name,server,port from nodes;'" 2>/dev/null | tr -d '\r')"
if [ -n "$NODE_ROWS" ]; then
  say "节点表: $(printf '%s' "$NODE_ROWS" | tr '\n' ';')"
  check "测试节点已入库" "$(printf '%s' "$NODE_ROWS" | grep -qF "$NODE_NAME" && echo 1 || echo 0)" "$NODE_NAME"
else
  warn "无法读取节点数据库（run-as/sqlite3 不可用），跳过入库断言"
fi

SVC="$(adbx shell "timeout 20 dumpsys activity services $PKG" 2>/dev/null | tr -d '\r')"
check "前台服务 SingFlowVpnService 运行中" "$(printf '%s' "$SVC" | grep -q "SingFlowVpnService" && echo 1 || echo 0)"
check "前台服务 isForeground=true" "$(printf '%s' "$SVC" | grep -q "isForeground=true" && echo 1 || echo 0)"
# 注意：API 33 的 dumpsys 不输出 types 字段，所以前台服务类型以 APK 清单为准（各 API 通用）
FGS_TYPE="$(manifest_fgs_type || true)"
if [ -n "$FGS_TYPE" ]; then
  check "清单声明前台服务类型含 systemExempted(0x400)" "$([ "$(( FGS_TYPE & 1024 ))" -ne 0 ] && echo 1 || echo 0)" "foregroundServiceType=$FGS_TYPE"
else
  warn "无法从 APK 清单读取 foregroundServiceType（未找到 aapt2 或清单无该属性），跳过断言"
fi
if printf '%s' "$SVC" | grep -q "types="; then
  check "运行时前台服务类型 systemExempted(0x400)" "$(printf '%s' "$SVC" | grep -q "types=0x00000400" && echo 1 || echo 0)"
else
  warn "该 API 的 dumpsys 不输出前台服务 types，跳过运行时断言"
fi

# 客户端 DNS 必须落在隧道上：VPN 网络没有 DNS 服务器时 Android 会退回底层网络解析，
# 客户端 DNS 就会绕过 hijack-dns / 远程 DoH，在受限网络里拿到被污染的结果。
CONN="$(adbx shell "timeout 30 dumpsys connectivity" 2>/dev/null | tr -d '\r')"
TUN_LP="$(printf '%s' "$CONN" | grep 'InterfaceName: tun0' | head -1)"
if [ -n "$TUN_LP" ]; then
  TUN_DNS="$(printf '%s' "$TUN_LP" | sed -n 's/.*DnsAddresses: \[\([^]]*\)\].*/\1/p' | tr -s ' ' | sed 's/^ *//; s/ *$//')"
  check "TUN 已下发 DNS 服务器（客户端 DNS 走隧道）" "$([ -n "$TUN_DNS" ] && echo 1 || echo 0)" "DnsAddresses=[$TUN_DNS]"
else
  warn "未解析到 VPN 网络的 DnsAddresses 字段，跳过 TUN DNS 断言"
fi

UI_TEXT=""
for i in 1 2 3; do
  UI_TEXT="$(ui_text)"
  if printf '%s' "$UI_TEXT" | grep -q "isn't responding"; then ui_clear_anr && continue; fi
  [ -n "$UI_TEXT" ] && break
done
if printf '%s' "$UI_TEXT" | grep -q "已保护" && ! printf '%s' "$UI_TEXT" | grep -q "诊断"; then
  check "界面处于已保护状态（非 TUN 诊断模式）" 1
else
  check "界面处于已保护状态（非 TUN 诊断模式）" 0 "$UI_TEXT"
fi
check "界面渲染出 SingFlow 应用要素" "$(printf '%s' "$UI_TEXT" | grep -q "SingFlow" && echo 1 || echo 0)"

# ---------------------------------------------------------------- 4. 真实转发
section "真实转发（浏览器出网）"
PROXY_BEFORE=0
[ -n "$PROXY_LOG" ] && [ -f "$PROXY_LOG" ] && PROXY_BEFORE="$(wc -l <"$PROXY_LOG" | tr -d ' ')"

adbx shell am force-stop com.android.chrome >/dev/null 2>&1 || true
sleep 2
adbx shell "am start -a android.intent.action.VIEW -d '$TEST_URL'" >/dev/null 2>&1 || true
say "已请求浏览器打开: $TEST_URL"

RENDERED=0
LAST_TEXT=""
ELAPSED=0
while [ "$ELAPSED" -lt 90 ]; do
  sleep 4
  ELAPSED=$((ELAPSED + 4))
  LAST_TEXT="$(ui_text)"
  if printf '%s' "$LAST_TEXT" | grep -q "isn't responding"; then
    # ANR 弹窗会盖住 Chrome：点掉后重新发起同一次导航再继续等待
    if ui_clear_anr; then adbx shell "am start -a android.intent.action.VIEW -d '$TEST_URL'" >/dev/null 2>&1 || true; fi
    continue
  fi
  if [ -n "$EXPECT_TEXT" ] && printf '%s' "$LAST_TEXT" | grep -qF "$EXPECT_TEXT"; then RENDERED=1; break; fi
done
if [ -n "$EXPECT_TEXT" ]; then
  check "浏览器渲染出预期页面内容" "$RENDERED" "期望文本: '$EXPECT_TEXT'"
  [ "$RENDERED" = "0" ] && say "页面文本片段: $(printf '%s' "$LAST_TEXT" | cut -c1-300)"
  # 默认 URL 带随机查询串：地址栏里能看到它就说明加载的是本次请求（而非浏览器缓存）
  TOKEN="$(printf '%s' "$TEST_URL" | sed -n 's/.*singflow=\([0-9A-Za-z_-]*\).*/\1/p')"
  if [ -n "$TOKEN" ]; then
    check "浏览器加载的是本次随机 URL（排除缓存）" "$(printf '%s' "$LAST_TEXT" | grep -qF "singflow=$TOKEN" && echo 1 || echo 0)" "要求包含: singflow=$TOKEN"
  else
    warn "测试 URL 未包含 singflow=<token>，跳过缓存排除断言"
  fi
else
  warn "未指定 EXPECT_TEXT，跳过页面渲染断言"
fi

if [ -n "$PROXY_LOG" ] && [ -f "$PROXY_LOG" ]; then
  sleep 2
  PROXY_AFTER="$(wc -l <"$PROXY_LOG" | tr -d ' ')"
  DELTA=$((PROXY_AFTER - PROXY_BEFORE))
  say "--- 代理服务端新增日志（$DELTA 行） ---"
  tail -n 12 "$PROXY_LOG" | while IFS= read -r line; do say "  $line"; done
  check "流量已到达代理服务端" "$([ "$DELTA" -gt 0 ] && echo 1 || echo 0)" "新增 $DELTA 行"
elif [ "$USE_LOOPBACK" = "1" ]; then
  warn "未指定 PROXY_LOG，跳过「流量到达服务端」断言"
fi

# ---------------------------------------------------------------- 5. 汇总
section "汇总"
say "总计 $TOTAL 项，通过 $((TOTAL - FAILURES)) 项，失败 $FAILURES 项"
say "结果: $([ "$FAILURES" -eq 0 ] && echo PASS || echo FAIL)"
say "验收日志已写入: $LOG_FILE"
if [ "$FAILURES" -eq 0 ]; then
  say "[OK] E2E 验收通过"
  exit 0
fi
say "[ERROR] E2E 验收存在失败项"
exit 1
