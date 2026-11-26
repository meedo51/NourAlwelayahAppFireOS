<#
.SYNOPSIS
    Nour Alwelayah Appliance — One-Click Physical Tablet Deployment Script (1280 x 800, Kiosk Mode)

.DESCRIPTION
    Deploys the Nour Alwelayah debug APK to a connected Android / Fire OS tablet via C:\platform-tools\adb.exe,
    grants android.permission.RECORD_AUDIO for Kiosk-mode offline Voice Assistant operation,
    optionally pushes app\src\main\assets\voice_models\ directly to external storage when -PushModels is specified,
    and launches com.nouralwelayah.clock/.MainActivity.

.PARAMETER PushModels
    If specified, pushes app\src\main\assets\voice_models\* to /sdcard/NourAlwelayah/voice_models/
    and /sdcard/Android/data/com.nouralwelayah.clock/files/NourAlwelayah/voice_models/.

.PARAMETER AdbPath
    Path to adb.exe. Defaults to C:\platform-tools\adb.exe (falls back to 'adb' in PATH if not found at C:\platform-tools\adb.exe).

.PARAMETER Serial
    Optional ADB device serial number if multiple devices are attached.

.EXAMPLE
    .\scripts\deploy_tablet.ps1
    .\scripts\deploy_tablet.ps1 -PushModels
#>

param(
    [switch]$PushModels,
    [string]$AdbPath = "C:\platform-tools\adb.exe",
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

$PackageName = "com.nouralwelayah.clock"
$MainActivity = "$PackageName/.MainActivity"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ApkPath = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
$VoiceModelsDir = Join-Path $ProjectRoot "app\src\main\assets\voice_models"
$KbDbPath = Join-Path $ProjectRoot "app\src\main\assets\kb\nour_kb.db"

if (-not (Test-Path $AdbPath)) {
    $fallbackAdb = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $fallbackAdb) {
        $AdbPath = $fallbackAdb.Source
    } else {
        Write-Error "ADB executable not found at '$AdbPath' and not in PATH. Please install Android platform-tools to C:\platform-tools\."
        exit 1
    }
}

$AdbBaseArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $AdbBaseArgs += @("-s", $Serial)
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $fullArgs = $AdbBaseArgs + $Arguments
    & $AdbPath @fullArgs
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed (exit code $LASTEXITCODE): $AdbPath $($fullArgs -join ' ')"
    }
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " Nour Alwelayah Appliance — Tablet Deployment Pipeline" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# 1. Verify connected tablet
Write-Host "[1/5] Checking connected ADB devices..." -ForegroundColor Yellow
Invoke-Adb @("wait-for-device")
$deviceModel = (& $AdbPath @($AdbBaseArgs + @("shell", "getprop", "ro.product.model"))).Trim()
$androidVer = (& $AdbPath @($AdbBaseArgs + @("shell", "getprop", "ro.build.version.release"))).Trim()
Write-Host "      Connected Tablet : $deviceModel (Android $androidVer)" -ForegroundColor Green

# 2. Verify and install Debug APK
if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found at '$ApkPath'. Run '.\gradlew.bat assembleDebug' first."
    exit 1
}
$apkSizeMb = [math]::Round((Get-Item $ApkPath).Length / 1MB, 2)
Write-Host "[2/5] Installing Debug APK ($apkSizeMb MB)..." -ForegroundColor Yellow
Invoke-Adb @("install", "-r", "-t", $ApkPath)
Write-Host "      APK installed successfully." -ForegroundColor Green

# 3. Grant RECORD_AUDIO permission for Kiosk mode Voice Assistant
Write-Host "[3/5] Granting android.permission.RECORD_AUDIO to $PackageName..." -ForegroundColor Yellow
try {
    Invoke-Adb @("shell", "pm", "grant", $PackageName, "android.permission.RECORD_AUDIO")
    Write-Host "      RECORD_AUDIO permission granted." -ForegroundColor Green
} catch {
    Write-Warning "Could not grant RECORD_AUDIO via pm grant (device may be pre-API 23 install-time permission): $_"
}

# 4. Optionally push voice_models to external storage if -PushModels is passed
if ($PushModels) {
    Write-Host "[4/5] Pushing sherpa-onnx voice models to tablet external storage..." -ForegroundColor Yellow
    if (-not (Test-Path $VoiceModelsDir)) {
        Write-Warning "Local voice models directory not found at '$VoiceModelsDir'. Run 'python scripts\download_voice_models.py' first."
    } else {
        $RemoteAppModelsDir = "/sdcard/Android/data/$PackageName/files/NourAlwelayah/voice_models"
        $RemoteSharedModelsDir = "/sdcard/NourAlwelayah/voice_models"

        Invoke-Adb @("shell", "mkdir", "-p", $RemoteAppModelsDir)
        Invoke-Adb @("shell", "mkdir", "-p", $RemoteSharedModelsDir)

        Invoke-Adb @("push", "$VoiceModelsDir\.", $RemoteAppModelsDir)
        Invoke-Adb @("push", "$VoiceModelsDir\.", $RemoteSharedModelsDir)
        Write-Host "      Voice models pushed to $RemoteAppModelsDir and $RemoteSharedModelsDir" -ForegroundColor Green
    }
} else {
    Write-Host "[4/5] Skipping external model push (models are bundled in APK assets; pass -PushModels to push via ADB)." -ForegroundColor DarkGray
}

# 5. Launch MainActivity
Write-Host "[5/5] Launching $MainActivity..." -ForegroundColor Yellow
Invoke-Adb @("shell", "am", "start", "-n", $MainActivity)

Write-Host ""
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " DEPLOYMENT COMPLETE — NOUR ALWELAYAH APPLIANCE READY" -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " Tablet Model       : $deviceModel (Android $androidVer)"
Write-Host " Package            : $PackageName"
Write-Host " APK Installed      : $ApkPath ($apkSizeMb MB)"
if (Test-Path $KbDbPath) {
    $kbSizeKb = [math]::Round((Get-Item $KbDbPath).Length / 1KB, 1)
    Write-Host " Bundled KB DB      : nour_kb.db ($kbSizeKb KB)"
}
Write-Host " RECORD_AUDIO       : GRANTED"
Write-Host " External Models    : $(if ($PushModels) { 'PUSHED via ADB' } else { 'Bundled in APK assets/voice_models/' })"
Write-Host "=================================================================" -ForegroundColor Cyan
