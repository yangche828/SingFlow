# SingFlow Phase 4: Windows 下的 libbox.aar 获取/构建
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$libs = Join-Path $root "app\libs"
$aar = Join-Path $libs "libbox.aar"
New-Item -ItemType Directory -Force -Path $libs | Out-Null

Write-Host "=============================================================="
Write-Host " SingFlow libbox.aar 获取/构建"
Write-Host "=============================================================="

if (Test-Path $aar) {
    Write-Host "[OK] 已存在 $aar ($([math]::Round((Get-Item $aar).Length / 1MB, 2)) MB)"
    exit 0
}

if ($env:SINGFLOW_LIBBOX_URL) {
    Write-Host "[INFO] 下载预编译 libbox: $($env:SINGFLOW_LIBBOX_URL)"
    try {
        Invoke-WebRequest -Uri $env:SINGFLOW_LIBBOX_URL -OutFile $aar -UseBasicParsing -TimeoutSec 120
        Write-Host "[OK] 下载完成"
        exit 0
    } catch {
        Write-Host "[WARN] 下载失败: $($_.Exception.Message)"
        Remove-Item $aar -ErrorAction SilentlyContinue
    }
}

$go = Get-Command go -ErrorAction SilentlyContinue
$ndk = $env:ANDROID_NDK_HOME
if ($go -and $ndk) {
    Write-Host "[INFO] 检测到 Go 与 NDK，开始源码编译 libbox.aar"
    $work = Join-Path $root ".build-libbox"
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $repo = Join-Path $work "sing-box"
    if (-not (Test-Path $repo)) {
        git clone --depth 1 --branch v1.14.3 https://github.com/SagerNet/sing-box.git $repo
    }
    Push-Location $repo
    go install golang.org/x/mobile/cmd/gomobile@latest
    $env:Path = "$(go env GOPATH)\bin;$env:Path"
    gomobile init
    $env:CGO_LDFLAGS = "-Wl,-z,max-page-size=16384"
    Push-Location (Join-Path $repo "experimental\libbox")
    gomobile bind -target=android/arm64,android/arm,android/amd64 -androidapi 33 -trimpath -buildvcs=false `
        -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" -o $aar .
    Pop-Location
    Pop-Location
    Write-Host "[OK] libbox.aar 已生成"
    exit 0
}

Write-Host "[WARN] 未找到 libbox.aar，且缺少 Go / NDK / 预编译下载地址。"
Write-Host "[WARN] SingFlow 将以「TUN 诊断回退引擎」运行：真实创建系统 VPN 网卡、"
Write-Host "       完整编译并展示 sing-box 配置，但不会转发流量。"
exit 0
