# SingFlow Phase 0: Windows 环境与 Android SDK 检查
# 用法：
#   pwsh -File scripts\setup_env_and_sdk.ps1                    # 仅自检
#   pwsh -File scripts\setup_env_and_sdk.ps1 -InstallEmulator   # 自检 + 安装模拟器/系统镜像/AEHD 加速驱动
param(
    [switch]$InstallEmulator
)
$ErrorActionPreference = "Continue"

Write-Host "=============================================================="
Write-Host " SingFlow 环境自检 (JDK / Android SDK / ADB)"
Write-Host "=============================================================="

if (-not $env:JAVA_HOME) {
    $candidates = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "D:\Apps\JDK\jdk-21",
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Java\jdk-17"
    )
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c "bin\java.exe")) { $env:JAVA_HOME = $c; break }
    }
}
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;" + $env:Path }
Write-Host "[JDK]"
java -version 2>&1 | Select-Object -First 3

if (-not $env:ANDROID_HOME) {
    $candidates = @("$env:LOCALAPPDATA\Android\Sdk", "D:\Apps\Android\Sdk", "C:\Android\Sdk")
    foreach ($c in $candidates) {
        if (Test-Path $c) { $env:ANDROID_HOME = $c; break }
    }
}
if (-not $env:ANDROID_HOME) { Write-Host "[ERROR] 未找到 Android SDK"; exit 1 }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\emulator;" + $env:Path
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"

$sdkmanager = Join-Path $env:ANDROID_HOME "cmdline-tools\latest\bin\sdkmanager.bat"
if (Test-Path $sdkmanager) {
    Write-Host "[SDK] 已安装组件:"
    & $sdkmanager --list_installed 2>$null | Select-Object -First 40
    foreach ($api in 37, 36, 35) {
        $platformDir = Join-Path $env:ANDROID_HOME "platforms\android-$api"
        if (-not (Test-Path $platformDir)) {
            Write-Host "[SDK] 尝试安装 platforms;android-$api ..."
            & $sdkmanager "platforms;android-$api" 2>&1 | Select-Object -Last 3
            if (Test-Path $platformDir) { break }
        }
    }
} else {
    Write-Host "[WARN] 未找到 sdkmanager，跳过 SDK 自动安装"
}

if ($InstallEmulator -and (Test-Path $sdkmanager)) {
    Write-Host "[SDK] 安装模拟器与 API 33 x86_64 系统镜像（首次约 2GB）"
    & $sdkmanager "emulator" "system-images;android-33;google_apis;x86_64" 2>&1 | Select-Object -Last 3
    Write-Host "[SDK] 安装 AEHD 加速驱动（需要管理员权限，无需重启）"
    & $sdkmanager "extras;google;Android_Emulator_Hypervisor_Driver" 2>&1 | Select-Object -Last 3
    $aehd = Join-Path $env:ANDROID_HOME "extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat"
    if (Test-Path $aehd) { & cmd /c "`"$aehd`"" 2>&1 | Select-Object -Last 5 }
    $emulatorExe = Join-Path $env:ANDROID_HOME "emulator\emulator.exe"
    if (Test-Path $emulatorExe) { & $emulatorExe -accel-check 2>&1 | Select-Object -First 3 }
}

Write-Host "[ADB]"
adb version 2>&1 | Select-Object -First 2
Write-Host "--------------------------------------------------------------"
adb devices -l
Write-Host "--------------------------------------------------------------"
$devices = @(adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
Write-Host "在线设备数量: $($devices.Count)"
if ($devices.Count -gt 0) {
    Write-Host ("Android 版本: " + (adb shell getprop ro.build.version.release).Trim())
    Write-Host ("API Level: " + (adb shell getprop ro.build.version.sdk).Trim())
} else {
    Write-Host "[INFO] 当前无在线设备；Phase 6 将自动尝试拉起模拟器。"
}

Write-Host "[OK] 环境自检完成"
