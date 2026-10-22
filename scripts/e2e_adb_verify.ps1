# SingFlow Phase 6：端到端自动验收（Windows / PowerShell）
#
# 目标：在一台 Android 13~17 设备（真机或模拟器）上，用一次运行完成
#       安装 -> 导入节点 -> 建立 VPN -> 断言 TUN/前台服务/无崩溃 -> 断言真实转发（浏览器出网）。
#
# 基本用法（需要自备可用节点）：
#   pwsh -File scripts\e2e_adb_verify.ps1 -NodeUri 'vless://<uuid>@<host>:<port>?...#MyNode'
#
# 无公网节点时的本地回环验收（另开一个终端先启动本地 VLESS 服务端）：
#   node scripts\dev\vless-loopback-server.js --port 8443
#   pwsh -File scripts\e2e_adb_verify.ps1 -ProxyLog C:\tmp\vless.log
#   说明：脚本会自动执行 adb reverse tcp:8443 tcp:8443，让设备的 127.0.0.1:8443 指向 PC 本机。
#
# 常用参数：
#   -RemoteDns <url>   连接前通过界面把「远程 DoH / DoT」改成指定值（本机沙箱里 1.1.1.1 不可达时很有用）
#   -KeepData          不清空应用数据（保留已保存的节点/DNS 设置）
#   -ProxyLog <path>   代理服务端日志路径；用于断言「流量确实到达了服务端」
#   -ExpectText <str>  浏览器渲染后页面里应出现的文本（默认 example.com 的说明文字）
#
# 退出码：0 全部通过；1 存在失败项；2 没有可用设备；3 APK 安装失败；4 应用进程未存活。
param(
    [string]$NodeUri = "",
    [int]$ProxyPort = 8443,
    [string]$ProxyLog = "",
    [string]$RemoteDns = "",
    [string]$TestUrl = "",
    [string]$ExpectText = "documentation examples",
    [string]$ApkPath = "",
    [string]$LogFile = "",
    [int]$ConnectTimeoutSec = 120,
    [switch]$KeepData,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Continue"
# adb 等原生程序输出 UTF-8：默认控制台编码会把中文节点名解成乱码，导致入库断言误判。
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }
$root = Split-Path -Parent $PSScriptRoot
$pkg = "com.singflow.android"
$activity = "$pkg/.MainActivity"
$loopbackUuid = "11111111-1111-1111-1111-111111111111"

if (-not $env:ANDROID_HOME) {
    foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk", "D:\Apps\Android\Sdk", "C:\Android\Sdk")) {
        if (Test-Path $c) { $env:ANDROID_HOME = $c; break }
    }
}
$env:Path = "$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;" + $env:Path

$script:serial = ""
$script:logPath = ""
$script:utf8NoBom = New-Object 'System.Text.UTF8Encoding'($false)
$script:results = New-Object 'System.Collections.Generic.List[object]'

# 日志逐行追加写入：脚本中途失败也能留下现场
function Say([string]$message) {
    Write-Host $message
    if ($script:logPath) {
        [IO.File]::AppendAllText($script:logPath, $message + [Environment]::NewLine, $script:utf8NoBom)
    }
    [Console]::Out.Flush()
}
function Section([string]$title) {
    Say ""
    Say ("=== {0} ===" -f $title)
}
function Check([string]$name, [bool]$ok, [string]$detail = "") {
    $script:results.Add([pscustomobject]@{ Name = $name; Ok = $ok })
    $tag = if ($ok) { "[PASS]" } else { "[FAIL]" }
    if ($detail) { Say ("{0} {1} | {2}" -f $tag, $name, $detail) } else { Say ("{0} {1}" -f $tag, $name) }
}
function Warn([string]$message) { Say "[WARN] $message" }

function AdbEx { & adb -s $script:serial @args }

# 从 APK 清单里读 foregroundServiceType（如 0x40000400），用于断言 systemExempted 位。
# 各 API 的 dumpsys 输出不一致（API 33 不打印 types），清单是跨版本最稳的证据来源。
function Get-ManifestFgsType {
    $aapt = Get-ChildItem (Join-Path $env:ANDROID_HOME "build-tools") -Recurse -Filter "aapt2*" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match "^aapt2(\.exe)?$" } |
    Sort-Object FullName -Descending |
    Select-Object -First 1
    if (-not $aapt) { return "" }
    $out = (& $aapt.FullName dump xmltree $ApkPath --file AndroidManifest.xml 2>$null | Out-String)
    $m = [regex]::Match($out, "foregroundServiceType\([^)]*\)=(0x[0-9a-fA-F]+)")
    if ($m.Success) { return $m.Groups[1].Value }
    return ""
}

function Get-UiXml {
    # uiautomator dump 偶发长时间不返回、或在界面动画期间取不到 idle 状态，
    # 因此必须加设备端 timeout 并重试；返回空串表示本次抓取失败。
    $local = Join-Path $env:TEMP "singflow_ui.xml"
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        # 先删掉设备上的旧快照，否则 dump 失败时会拉到上一次的陈旧内容
        AdbEx shell "timeout 5 rm -f /sdcard/singflow_ui.xml" 2>$null | Out-Null
        AdbEx shell "timeout 25 uiautomator dump /sdcard/singflow_ui.xml" 2>$null | Out-Null
        Remove-Item $local -ErrorAction SilentlyContinue
        AdbEx pull /sdcard/singflow_ui.xml $local 2>$null | Out-Null
        if (Test-Path $local) {
            $xml = Get-Content $local -Raw -Encoding UTF8
            if ($xml -match "<hierarchy") { return $xml }
        }
        Start-Sleep -Milliseconds 1500
    }
    return ""
}

# UI 树是单行 XML：逐个 node 解析出 text / class / 中心点，避免直接对中文做正则。
function Get-UiNodes([string]$xml = "") {
    if (-not $xml) { $xml = Get-UiXml }
    $list = New-Object 'System.Collections.Generic.List[object]'
    foreach ($m in [regex]::Matches($xml, '<node[^>]*?>')) {
        $n = $m.Value
        $b = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $x1 = [int]$b.Groups[1].Value; $y1 = [int]$b.Groups[2].Value
        $x2 = [int]$b.Groups[3].Value; $y2 = [int]$b.Groups[4].Value
        $list.Add([pscustomobject]@{
                Text = [regex]::Match($n, 'text="([^"]*)"').Groups[1].Value
                Cls  = [regex]::Match($n, 'class="([^"]*)"').Groups[1].Value
                X    = [int](($x1 + $x2) / 2)
                Y    = [int](($y1 + $y2) / 2)
            })
    }
    return $list
}

function Get-UiText([string]$xml = "") {
    if (-not $xml) { $xml = Get-UiXml }
    return (([regex]::Matches($xml, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value }) -join " | ")
}

function TapNode($node) {
    AdbEx shell input tap $node.X $node.Y 2>$null | Out-Null
    Start-Sleep -Milliseconds 700
}

# 低配模拟器上 SystemUI 偶发 ANR，弹窗（System UI isn't responding）会盖住真实界面、
# 让 UI 断言读到弹窗文本。这里自动点掉弹窗，避免把环境抖动误判为应用缺陷。
function Clear-AnrDialog {
    $wait = (Get-UiNodes) | Where-Object { $_.Text -eq "Wait" } | Select-Object -First 1
    if (-not $wait) { return $false }
    Warn "检测到系统 ANR 弹窗（System UI isn't responding），自动点击 Wait 后继续"
    TapNode $wait
    Start-Sleep -Seconds 3
    return $true
}

# 设置页的第一个输入框就是「远程 DoH / DoT」。冷启动时它可能还没回填文本，
# 因此先按内容（含 "://"）匹配；匹配不到时，只有确认当前确实在设置页（能看到 DoH 标签）
# 才退化为取第一个输入框，避免把 DNS 打进别的页面。
function Get-DnsField {
    $nodes = Get-UiNodes
    $byContent = $nodes | Where-Object { $_.Cls -match "EditText" -and $_.Text -match "://" } | Select-Object -First 1
    if ($byContent) { return $byContent }
    if (@($nodes | Where-Object { $_.Text -like "*DoH*" }).Count -gt 0) {
        return ($nodes | Where-Object { $_.Cls -match "EditText" } | Select-Object -First 1)
    }
    return $null
}

# 通过界面把「远程 DoH / DoT」改成指定值（best-effort，失败只告警不中断）。
function Set-RemoteDns([string]$dns) {
    Section "修改远程 DNS（界面自动化）"
    AdbEx shell am start -n $activity 2>$null | Out-Null

    # 冷启动后界面尚未就绪时抓不到节点，这里重试等待
    $tab = $null
    for ($i = 0; $i -lt 8 -and -not $tab; $i++) {
        Start-Sleep -Seconds 2
        $tab = (Get-UiNodes) | Where-Object { $_.Text -eq "设置" } | Select-Object -First 1
    }
    if (-not $tab) { Warn "未找到底部「设置」Tab，跳过 DNS 修改"; return $false }
    TapNode $tab

    $field = $null
    for ($i = 0; $i -lt 6 -and -not $field; $i++) {
        $field = Get-DnsField
        if ($field) { break }
        if ($i -eq 2) {
            # 可能是「设置」Tab 点击未生效，重新点一次
            $tab = (Get-UiNodes) | Where-Object { $_.Text -eq "设置" } | Select-Object -First 1
            if ($tab) { TapNode $tab } else { AdbEx shell input swipe 160 200 160 520 2>$null | Out-Null }
        }
        else { AdbEx shell input swipe 160 200 160 520 2>$null | Out-Null }
        Start-Sleep -Milliseconds 900
    }
    if (-not $field) { Warn "未找到远程 DNS 输入框，跳过 DNS 修改"; return $false }

    TapNode $field
    AdbEx shell input keyevent 123 2>$null | Out-Null
    AdbEx shell 'for i in $(seq 1 48); do input keyevent 67; done' 2>$null | Out-Null
    AdbEx shell input text $dns 2>$null | Out-Null
    AdbEx shell input keyevent 4 2>$null | Out-Null  # 收起输入法，否则后续滑动会污染输入框
    Start-Sleep -Milliseconds 800

    $verify = Get-DnsField
    if (-not $verify -or $verify.Text -ne $dns) {
        Warn ("远程 DNS 输入框内容为 '{0}'，期望 '{1}'" -f ($verify.Text), $dns)
        return $false
    }
    Say ("远程 DNS 输入框已填入: {0}" -f $verify.Text)

    $save = (Get-UiNodes) | Where-Object { $_.Text -like "*保存*" } | Select-Object -First 1
    for ($i = 0; $i -lt 5 -and -not $save; $i++) {
        AdbEx shell input swipe 160 520 160 200 2>$null | Out-Null
        Start-Sleep -Milliseconds 900
        $save = (Get-UiNodes) | Where-Object { $_.Text -like "*保存*" } | Select-Object -First 1
    }
    if (-not $save) { Warn "未找到「保存」按钮，DNS 修改可能未生效"; return $false }
    TapNode $save
    Start-Sleep -Seconds 1

    # 保存按钮在表单下方，回读字段前先滚回顶部
    $final = $null
    for ($i = 0; $i -lt 5 -and -not $final; $i++) {
        $final = Get-DnsField
        if (-not $final) {
            AdbEx shell input swipe 160 200 160 520 2>$null | Out-Null
            Start-Sleep -Milliseconds 900
        }
    }
    if ($final -and $final.Text -eq $dns) {
        Say "[OK] 远程 DNS 已保存: $dns"
        return $true
    }
    Warn "保存后回读 DNS 失败"
    return $false
}

# ---------------------------------------------------------------- 0. 设备与参数
# 设备探测放在最前面：没有设备时无需写日志文件
$devices = @(adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
if ($devices.Count -eq 0) {
    # 蓝图 Phase 6 情况 B：没有在线设备时自动拉起本机已有 AVD
    $emulatorBin = Join-Path $env:ANDROID_HOME "emulator\emulator.exe"
    Write-Host "未检测到在线设备，尝试自动启动本机模拟器（AVD）..."
    if (Test-Path $emulatorBin) {
        $avds = @(& $emulatorBin -list-avds 2>$null | Where-Object { $_ -match "\S" })
        $avd = ($avds | Where-Object { $_ -match "SingFlow" } | Select-Object -First 1)
        if (-not $avd) { $avd = ($avds | Select-Object -First 1) }
        if ($avd) {
            Write-Host "启动 AVD: $avd（无窗口模式；如需可见界面请自行先启动模拟器）"
            Start-Process -FilePath $emulatorBin -WindowStyle Hidden -ArgumentList @(
                "-avd", $avd, "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot-save", "-gpu", "swiftshader_indirect"
            ) | Out-Null
            $bootDeadline = (Get-Date).AddMinutes(8)
            while ((Get-Date) -lt $bootDeadline) {
                Start-Sleep -Seconds 10
                $devices = @(adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
                if ($devices.Count -gt 0) {
                    $candidate = ($devices[0] -split "\s+")[0]
                    $booted = ((adb -s $candidate shell "timeout 15 getprop sys.boot_completed" 2>$null) | Out-String).Trim()
                    if ($booted -eq "1") { Write-Host "模拟器已就绪: $candidate"; break }
                }
            }
        }
        else { Write-Host "未找到任何 AVD（emulator -list-avds 为空）" }
    }
    else { Write-Host "未找到 emulator 可执行文件: $emulatorBin" }
    $devices = @(adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
    if ($devices.Count -eq 0) {
        Write-Host "[🔔 用户操作提醒] 没有可用设备：请连接 Android 13~17 手机并开启 USB 调试，或手动启动模拟器后重跑本脚本。"
        exit 2
    }
}
$script:serial = ($devices[0] -split "\s+")[0]

$api = ((AdbEx shell "timeout 15 getprop ro.build.version.sdk" 2>$null) | Out-String).Trim()
$release = ((AdbEx shell "timeout 15 getprop ro.build.version.release" 2>$null) | Out-String).Trim()
$abi = ((AdbEx shell "timeout 15 getprop ro.product.cpu.abi" 2>$null) | Out-String).Trim()

if (-not $LogFile) { $LogFile = "acceptance\e2e_api$api.log" }
if (-not [IO.Path]::IsPathRooted($LogFile)) { $LogFile = Join-Path $root $LogFile }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogFile) | Out-Null
[IO.File]::WriteAllText($LogFile, "", $script:utf8NoBom)
$script:logPath = $LogFile

Say "=============================================================="
Say " SingFlow E2E 验收（Phase 6）"
Say " 时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Say " 设备: $script:serial  Android $release (API $api) $abi"
Say (" adb: {0}" -f (Get-Command adb -ErrorAction SilentlyContinue).Source)
Say "=============================================================="
Check "运行环境满足 minSdk 33" ([int]$api -ge 33) "API $api"
if ([int]$api -lt 33) { Warn "低于 minSdk 33，后续断言不具代表性" }

if (-not $ApkPath) { $ApkPath = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk" }
if (-not (Test-Path $ApkPath)) {
    Say "[ERROR] 未找到 APK：$ApkPath（请先执行 .\gradlew.bat :app:assembleDebug）"
    exit 3
}
Say ("APK: {0} ({1:N1} MB)" -f $ApkPath, ((Get-Item $ApkPath).Length / 1MB))

if (-not $TestUrl) { $TestUrl = "https://www.example.com/?singflow=$((Get-Random -Maximum 999999))" }

# 节点 URI：未指定时使用本机回环节点 + adb reverse
$useLoopback = $false
if (-not $NodeUri) {
    $useLoopback = $true
    # 注意：必须写成 ${ProxyPort}，否则 PowerShell 会把 "?" 当成变量名的一部分
    $NodeUri = "vless://$loopbackUuid@127.0.0.1:${ProxyPort}?encryption=none&security=none&type=tcp#SingFlow-LoopBack"
    Say "未指定 -NodeUri，使用本机回环节点: 127.0.0.1:$ProxyPort"
}
$nodeName = ""
# 节点名在 URI 里是 percent-encoded（含中文时很常见），数据库里存的是解码后的名字
if ($NodeUri -match "#(.+)$") { $nodeName = [System.Uri]::UnescapeDataString($Matches[1]) }
if (-not $nodeName) { $nodeName = "SingFlow-E2E-Node" }
Say "节点: $NodeUri"

if ($useLoopback) {
    $listening = (netstat -ano | Select-String ":$ProxyPort\s" | Select-String "LISTENING")
    Check "本机代理服务端已监听 $ProxyPort" ([bool]$listening) "如未监听，请先运行: node scripts\dev\vless-loopback-server.js --port $ProxyPort"
    AdbEx reverse "tcp:$ProxyPort" "tcp:$ProxyPort" 2>$null | Out-Null
    $rev = ((AdbEx reverse --list 2>$null) | Out-String).Trim()
    Check "adb reverse 已建立" ($rev -match "tcp:$ProxyPort") $rev
}

# ---------------------------------------------------------------- 1. 安装与授权
Section "安装与授权"
if (-not $SkipInstall) {
    Say "[*] 正在安装 APK（大包上传可能需要 1~2 分钟）..."
    $install = (AdbEx install -r -t $ApkPath 2>&1 | Out-String)
    Say ($install.Trim())
    if ($install -notmatch "Success" -and -not $SkipInstall) {
        Check "APK 安装成功" $false "adb install 返回非 Success"
        exit 3
    }
    Check "APK 安装成功" $true
}
else { Warn "已跳过安装（-SkipInstall）" }

if (-not $KeepData) {
    Say "清空应用数据（可用 -KeepData 保留已保存的节点/DNS）"
    AdbEx shell pm clear $pkg 2>$null | Out-Null
}
AdbEx shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
AdbEx shell appops set $pkg ACTIVATE_VPN allow 2>$null | Out-Null

if ($RemoteDns) { [void](Set-RemoteDns $RemoteDns) }

# ---------------------------------------------------------------- 2. 导入节点并连接
Section "导入节点并建立隧道"
AdbEx logcat -c 2>$null | Out-Null

AdbEx shell am force-stop $pkg 2>$null | Out-Null
Start-Sleep -Seconds 1
AdbEx shell "am start -n $activity --es EXTRA_IMPORT_URI '$NodeUri' --ez EXTRA_AUTO_CONNECT true" 2>$null | Out-Null

$vpnUp = $false
$tunInfo = ""
$deadline = (Get-Date).AddSeconds($ConnectTimeoutSec)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    $focus = ((AdbEx shell "timeout 20 dumpsys window" 2>$null) | Out-String)
    if ($focus -match "VpnDialogs") {
        Say "[🔔 用户操作提醒] 首次 VPN 授权框已弹出，脚本正在自动确认（如未生效请手动点「确定」）"
        AdbEx shell input keyevent KEYCODE_TAB 2>$null | Out-Null
        AdbEx shell input keyevent KEYCODE_ENTER 2>$null | Out-Null
    }
    $tunInfo = ((AdbEx shell "timeout 15 ip addr show tun0" 2>$null) | Out-String)
    if ($tunInfo -match "172\.19\.0\.1") { $vpnUp = $true; break }
}
if ($vpnUp) { Say ($tunInfo.Trim() -split "`n" | Select-Object -First 2 | Out-String).Trim() }
Check "tun0 已建立（172.19.0.1/30）" $vpnUp
if (-not $vpnUp) {
    Say "--- 最近日志（仅本应用相关） ---"
    (AdbEx shell "timeout 20 logcat -d -t 400" 2>$null) | Where-Object { $_ -match ([regex]::Escape($pkg) + "|VpnService|libbox|sing-box|VpnDialogs|Vpn") } | Select-Object -Last 25 | ForEach-Object { Say $_ }
    Warn "VPN 未建立：真实转发断言无意义，直接结束"
    $failCount = @($script:results | Where-Object { -not $_.Ok }).Count
    exit ([Math]::Max(1, $failCount))
}
$mtu = [regex]::Match($tunInfo, "mtu (\d+)").Groups[1].Value
Check "tun0 MTU 已按设置生效" ($mtu -eq "9000") "mtu=$mtu"

$pidValue = ((AdbEx shell "timeout 15 pidof $pkg" 2>$null) | Out-String).Trim()
Check "应用进程存活" ([bool]$pidValue) "pid=$pidValue"
if (-not $pidValue) { exit 4 }

# ---------------------------------------------------------------- 3. 内核与系统状态
Section "内核与系统状态"
$logAll = ((AdbEx shell "timeout 30 logcat -d -v brief" 2>$null) | Out-String)
$javaCrash = [regex]::IsMatch($logAll, "FATAL EXCEPTION[\s\S]{0,400}?Process: com\.singflow\.android")
$nativeCrash = [regex]::IsMatch($logAll, ">>> com\.singflow\.android <<<")
Check "无 Java 崩溃（FATAL EXCEPTION）" (-not $javaCrash)
Check "无原生崩溃（tombstone）" (-not $nativeCrash)

$dbPath = "/data/data/$pkg/databases/singflow.db"
$rows = ((AdbEx shell "timeout 20 run-as $pkg sqlite3 $dbPath 'select name,server,port from nodes;'" 2>$null) | Out-String).Trim()
# 名称可能含中文：用 hex 比较，避免主机侧任何编码差异导致误判
$rowsHex = ((AdbEx shell "timeout 20 run-as $pkg sqlite3 $dbPath 'select hex(name) from nodes;'" 2>$null) | Out-String).Trim()
if ($rowsHex) {
    Say "节点表: $($rows -replace "`r?`n", ' ; ')"
    $nameHex = -join ([Text.Encoding]::UTF8.GetBytes($nodeName) | ForEach-Object { $_.ToString("x2") })
    $inDb = @(($rowsHex -split "`r?`n") | Where-Object { $_.Trim().ToLowerInvariant() -eq $nameHex }).Count -gt 0
    Check "测试节点已入库" $inDb $nodeName
}
elseif ($rows) {
    Say "节点表: $($rows -replace "`r?`n", ' ; ')"
    Check "测试节点已入库" ($rows -match [regex]::Escape($nodeName)) $nodeName
}
else { Warn "无法读取节点数据库（run-as/sqlite3 不可用），跳过入库断言" }

$svc = ((AdbEx shell "timeout 20 dumpsys activity services $pkg" 2>$null) | Out-String)
Check "前台服务 SingFlowVpnService 运行中" ($svc -match "SingFlowVpnService")
Check "前台服务 isForeground=true" ($svc -match "isForeground=true")
# 注意：API 33 的 dumpsys 不输出 types 字段，所以前台服务类型以 APK 清单为准（各 API 通用）
$fgsType = Get-ManifestFgsType
if ($fgsType) {
    $fgsValue = [Convert]::ToInt32($fgsType.Substring(2), 16)
    Check "清单声明前台服务类型含 systemExempted(0x400)" (($fgsValue -band 0x400) -ne 0) "foregroundServiceType=$fgsType"
}
else { Warn "未找到 aapt2 或清单中无 foregroundServiceType，跳过清单前台服务类型断言" }
if ($svc -match "types=") {
    Check "运行时前台服务类型 systemExempted(0x400)" ($svc -match "types=0x00000400")
}
else { Warn "该 API 的 dumpsys 不输出前台服务 types，跳过运行时断言" }

# 客户端 DNS 必须落在隧道上：VPN 网络没有 DNS 服务器时 Android 会退回底层网络解析，
# 客户端 DNS 就会绕过 hijack-dns / 远程 DoH，在受限网络里拿到被污染的结果。
$conn = ((AdbEx shell "timeout 30 dumpsys connectivity" 2>$null) | Out-String)
$dnsMatch = [regex]::Match($conn, "InterfaceName: tun0[^\r\n]*?DnsAddresses: \[([^\]]*)\]")
if ($dnsMatch.Success) {
    $tunDns = ($dnsMatch.Groups[1].Value -replace "\s+", " ").Trim()
    Check "TUN 已下发 DNS 服务器（客户端 DNS 走隧道）" ([bool]$tunDns) "DnsAddresses=[$tunDns]"
}
else { Warn "未解析到 VPN 网络的 DnsAddresses 字段，跳过 TUN DNS 断言" }

$uiText = ""
for ($i = 0; $i -lt 3; $i++) {
    $uiText = Get-UiText
    if ($uiText -match "isn't responding") { if (Clear-AnrDialog) { continue } }
    if ($uiText) { break }
}
$protectedText = [string]([char]0x5DF2) + [char]0x4FDD + [char]0x62A4   # 已保护
Check "界面处于已保护状态（非 TUN 诊断模式）" (($uiText -match "已保护") -and ($uiText -notmatch "诊断")) $protectedText
Check "界面渲染出 SingFlow 应用要素" ($uiText -match "SingFlow")

# ---------------------------------------------------------------- 4. 真实转发
Section "真实转发（浏览器出网）"
$proxyBefore = 0
if ($ProxyLog -and (Test-Path $ProxyLog)) { $proxyBefore = @(Get-Content $ProxyLog).Count }

AdbEx shell am force-stop com.android.chrome 2>$null | Out-Null
Start-Sleep -Seconds 2
AdbEx shell "am start -a android.intent.action.VIEW -d '$TestUrl'" 2>$null | Out-Null
Say "已请求浏览器打开: $TestUrl"

$rendered = $false
$lastText = ""
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 4
    $lastText = Get-UiText
    if ($lastText -match "isn't responding") {
        # ANR 弹窗会盖住 Chrome：点掉后重新发起同一次导航再继续等待
        if (Clear-AnrDialog) {
            AdbEx shell "am start -a android.intent.action.VIEW -d '$TestUrl'" 2>$null | Out-Null
        }
        continue
    }
    if ($ExpectText -and $lastText -match [regex]::Escape($ExpectText)) { $rendered = $true; break }
    if (-not $ExpectText) { break }
}
if ($ExpectText) {
    Check "浏览器渲染出预期页面内容" $rendered "期望文本: '$ExpectText'"
    if (-not $rendered) { Say ("页面文本片段: {0}" -f $lastText.Substring(0, [Math]::Min(300, $lastText.Length))) }

    # 默认 URL 带随机查询串：地址栏里能看到它就说明加载的是本次请求（而非 Chrome 缓存）
    $token = ""
    if ($TestUrl -match "singflow=([0-9A-Za-z_-]+)") { $token = $Matches[1] }
    if ($token) {
        Check "浏览器加载的是本次随机 URL（排除缓存）" ($lastText -match [regex]::Escape("singflow=" + $token)) ("要求包含: singflow=$token")
    }
    else { Warn "测试 URL 未包含 singflow=<token>，跳过缓存排除断言" }
}
else { Warn "未指定 -ExpectText，跳过页面渲染断言" }

if ($ProxyLog -and (Test-Path $ProxyLog)) {
    Start-Sleep -Seconds 2
    $all = @(Get-Content $ProxyLog)
    $delta = $all.Count - $proxyBefore
    Say "--- 代理服务端新增日志（$delta 行） ---"
    $all | Select-Object -Skip $proxyBefore | Select-Object -Last 12 | ForEach-Object { Say "  $_" }
    Check "流量已到达代理服务端" ($delta -gt 0) "新增 $delta 行"
}
elseif ($useLoopback) { Warn "未指定 -ProxyLog，跳过「流量到达服务端」断言" }

# ---------------------------------------------------------------- 5. 汇总
Section "汇总"
$failed = @($script:results | Where-Object { -not $_.Ok })
foreach ($r in $script:results) { Say ("{0} {1}" -f $(if ($r.Ok) { "[PASS]" } else { "[FAIL]" }), $r.Name) }
Say ("总计 {0} 项，通过 {1} 项，失败 {2} 项" -f $script:results.Count, ($script:results.Count - $failed.Count), $failed.Count)
Say ("结果: {0}" -f $(if ($failed.Count -eq 0) { "PASS" } else { "FAIL" }))
Say "验收日志已写入: $script:logPath"

if ($failed.Count -eq 0) { Say "[OK] E2E 验收通过"; exit 0 }
Say "[ERROR] E2E 验收存在失败项"
exit 1
