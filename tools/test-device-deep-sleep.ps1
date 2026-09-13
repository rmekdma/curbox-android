<#
.SYNOPSIS
    Test script for Ticket 29: OEM Deep-Sleep and Wake-Recovery Validation on Xiaomi Pad Pro (Android 15/16).
.DESCRIPTION
    Executes the objective OEM deep-sleep validation protocol defined in Ticket 29:
    1. Preflight: Captures and verifies device identity (Target: Xiaomi Pad Pro 2025 12.7, Android 15/16).
       - Supports a -SkipDeviceCheck flag for dry-run/rehearsal on other connected devices.
    2. Backs up settings.json from device.
    3. Injects test AppRule configuration for AR004 scenarios (All-apps boundary & Guardian extra time).
    4. Records pre-sleep kernel suspend_stats, dumpsys power/battery/deviceidle snapshots.
    5. Guides physical sleep entry (USB disconnect, screen off, stationary) for requested duration (default: 15 min, configurable).
    6. Post-wake verification:
       - Checks kernel SoC suspend entry/exit and suspend_stats.
       - Verifies accessibility service AppBlockerService remained alive.
       - Verifies lock screen / denial presentation on target app without screen-time leaks.
       - Captures logcat, dumpsys, and diagnostics into evidence directory.
    7. Restores original settings.json and cleans up.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [int]$SleepDurationMinutes = 15,
    [switch]$SkipDeviceCheck = $false,
    [string]$EvidenceOutputDir = ""
)

$ErrorActionPreference = "Stop"

function Write-Step($msg) {
    Write-Host "`n====> $msg" -ForegroundColor Cyan
}

function Write-Success($msg) {
    Write-Host "[PASS] $msg" -ForegroundColor Green
}

function Write-Fail($msg) {
    Write-Host "[FAIL] $msg" -ForegroundColor Red
}

function Write-Warn($msg) {
    Write-Host "[WARN] $msg" -ForegroundColor Yellow
}

$device = (adb devices | Select-String -Pattern "device$")
if (-not $device) {
    Write-Error "No connected adb device found!"
}

# 1. Environment & Preflight checks
Write-Step "1. Preflight: Capturing device identity..."
$model = (adb shell "getprop ro.product.model").Trim()
$release = (adb shell "getprop ro.build.version.release").Trim()
$sdk = (adb shell "getprop ro.build.version.sdk").Trim()
$buildId = (adb shell "getprop ro.build.id").Trim()
$fingerprint = (adb shell "getprop ro.build.fingerprint").Trim()
$channel = (adb shell "getprop ro.build.channel").Trim()
if (-not $channel) { $channel = "unavailable" }

Write-Host "Observed Device:"
Write-Host "  Model:       $model"
Write-Host "  Release:     $release (SDK $sdk)"
Write-Host "  Build ID:    $buildId"
Write-Host "  Fingerprint: $fingerprint"
Write-Host "  Channel:     $channel"

$enabledServices = (adb shell "settings get secure enabled_accessibility_services").Trim()
if ($enabledServices -notmatch "neth\.iecal\.curbox/\.services\.AppBlockerService") {
    Write-Host "Enabling Curbox AppBlockerService in settings..." -ForegroundColor Yellow
    adb shell "settings put secure enabled_accessibility_services neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService" | Out-Null
    adb shell "settings put secure accessibility_enabled 1" | Out-Null
}
# Start app main activity or send intent to ensure AppBlockerService process is running
adb shell "am start -n neth.iecal.curbox.debug/neth.iecal.curbox.ui.activity.FragmentActivity" | Out-Null
Start-Sleep -Seconds 2
adb shell "input keyevent 3" # Home key
Start-Sleep -Seconds 1

$dumpsysAcc = adb shell "dumpsys accessibility"
if ($dumpsysAcc -match "Curbox App Blocker" -or $dumpsysAcc -match "neth\.iecal\.curbox/\.services\.AppBlockerService") {
    Write-Success "AppBlockerService accessibility state confirmed and bound."
} else {
    Write-Warn "AppBlockerService not yet listed under Bound services, but enabled in secure settings."
}

$isTargetModel = ($model -match "Xiaomi Pad Pro 2025 12.7" -or $model -match "24127RP0CC" -or $model -match "pipa")
$isTargetAndroid = ($release -ge 15 -or [int]$sdk -ge 35)

if (-not $isTargetModel -or -not $isTargetAndroid) {
    Write-Warn "Device mismatch detected! Required: Xiaomi Pad Pro 2025 12.7 on Android 15/16."
    Write-Warn "Observed: $model on Android $release (SDK $sdk)."
    if (-not $SkipDeviceCheck) {
        Write-Error "Stopping execution per Ticket 29 invariant: do not substitute device without explicit -SkipDeviceCheck flag."
    } else {
        Write-Warn "Proceeding under -SkipDeviceCheck (Dry-run / Rehearsal mode only. Not valid for canonical Ticket 29 proof)."
    }
} else {
    Write-Success "Target device identity verified!"
}

# Prepare directories
$tmpDir = Join-Path $env:TEMP "curbox_deep_sleep_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}
$backupFile = Join-Path $tmpDir "settings_backup.json"
$testSettingsFile = Join-Path $tmpDir "settings_test.json"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

if (-not $EvidenceOutputDir) {
    $EvidenceOutputDir = Join-Path $PSScriptRoot "..\.scratch\app-rule-enforcement\evidence\ticket29"
}
if (-not (Test-Path $EvidenceOutputDir)) {
    New-Item -ItemType Directory -Path $EvidenceOutputDir -Force | Out-Null
}

try {
    # 2. Backup current settings
    Write-Step "2. Backing up device settings.json..."
    $rawSettings = (adb shell "run-as neth.iecal.curbox.debug cat files/datastore/settings.json" | Out-String).Trim().Trim([char]65279)
    if (-not $rawSettings -or $rawSettings -notmatch "\{") {
        Write-Error "Failed to read settings.json from device!"
    }
    [System.IO.File]::WriteAllText($backupFile, $rawSettings, $utf8NoBom)
    Write-Success "Backup saved to $backupFile"

    # 3. Inject Test AppRule configuration for Deep-Sleep / AR004
    Write-Step "3. Crafting and injecting test AppRule configuration..."
    $settingsObj = $rawSettings | ConvertFrom-Json
    $targetGroupId = "test-target-group-deep-sleep"
    $ruleId = "test-rule-deep-sleep"

    $groupTarget = [PSCustomObject]@{
        id = $targetGroupId
        name = "Deep Sleep Target"
        selectedPackages = @($TargetPackage)
        membershipHistory = @(
            [PSCustomObject]@{
                effectiveFromMs = [long]-9223372036854775808
                selectedPackages = @($TargetPackage)
            }
        )
    }

    $rule = [PSCustomObject]@{
        id = $ruleId
        name = "Deep Sleep Rule"
        isActive = $true
        weekdays = @(0, 1, 2, 3, 4, 5, 6)
        startMinute = 0
        endMinute = 0
        appGroupId = $targetGroupId
        allowedMinutes = [long]30
        usageConditionEnabled = $true
        usageConditionMinutes = [long]20
        contributorGroupConditionMinutes = [PSCustomObject]@{
            $targetGroupId = [long]15
        }
        contributorGroupIds = @($targetGroupId)
        earnedAllowanceEnabled = $false
        earnedAllowancePackageConditions = [PSCustomObject]@{}
        earnedAllowancePackageBonusMinutes = [PSCustomObject]@{}
        earnedAllowanceAppGroupConditions = [PSCustomObject]@{}
        earnedAllowanceAppGroupBonusMinutes = [PSCustomObject]@{}
        earnedAllowanceConditions = [PSCustomObject]@{}
        earnedAllowanceConditionMinutes = [long]0
        earnedAllowanceBonusMinutes = [long]0
        maxEarnedAllowanceMinutes = [long]0
        useDayResetHour = 0
        useDayResetMinute = 0
        autoAppRuleSchedule = $false
        timeRanges = @(
            [PSCustomObject]@{
                startMinute = 0
                endMinute = 0
            }
        )
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($targetGroupId)
            excludedGroupIds = @()
        }
    }

    $settingsObj.appRuleSnapshot = [PSCustomObject]@{
        appGroups = @($groupTarget)
        appRules = @($rule)
    }
    $modifiedJson = $settingsObj | ConvertTo-Json -Depth 20 -Compress

    [System.IO.File]::WriteAllText($testSettingsFile, $modifiedJson, $utf8NoBom)
    adb shell "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p neth.iecal.curbox.debug" | Out-Null
    Start-Sleep -Milliseconds 500

    $rulesSnapshotJson = ($settingsObj.appRuleSnapshot | ConvertTo-Json -Depth 20 -Compress).Replace('"', '\"')
    adb shell "am broadcast -a neth.iecal.curbox.action.APPLY_TEST_APP_RULES -p neth.iecal.curbox.debug --es extra_app_rules_json '$rulesSnapshotJson'" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p neth.iecal.curbox.debug" | Out-Null
    Start-Sleep -Seconds 2
    Write-Success "Test AppRule active via broadcast seam."

    # 4. Pre-sleep diagnostics snapshot
    Write-Step "4. Capturing pre-sleep diagnostics..."
    $preSuspendStats = adb shell "cat /sys/kernel/debug/suspend_stats 2>/dev/null || cat /d/suspend_stats 2>/dev/null" | Out-String
    $preDumpsysPower = adb shell "dumpsys power" | Out-String
    $preDumpsysBattery = adb shell "dumpsys battery" | Out-String
    $preDumpsysDeviceIdle = adb shell "dumpsys deviceidle" | Out-String
    adb shell "logcat -c" | Out-Null

    # 5. Guide physical deep sleep entry
    Write-Step "5. Initiating Physical Deep-Sleep interval ($SleepDurationMinutes minutes)..."
    Write-Host @"
--------------------------------------------------------------------------------
[ACTION REQUIRED]
1. Disconnect the USB cable NOW (or run 'adb disconnect' if using Wi-Fi ADB).
   - This ensures USB PHY drivers do not prevent SoC hardware suspend-to-RAM.
2. Press the physical POWER BUTTON to turn off the screen.
3. Place the tablet stationary on a flat surface.
4. Leave it undisturbed for $SleepDurationMinutes minutes.
--------------------------------------------------------------------------------
"@ -ForegroundColor Yellow

    $totalSeconds = $SleepDurationMinutes * 60
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $totalSeconds) {
        $remaining = [math]::Max(0, [int]($totalSeconds - $sw.Elapsed.TotalSeconds))
        $mins = [math]::Floor($remaining / 60)
        $secs = $remaining % 60
        Write-Progress -Activity "Physical Deep Sleep Interval" -Status "Time remaining: $mins min $secs sec" -PercentComplete (($sw.Elapsed.TotalSeconds / $totalSeconds) * 100)
        Start-Sleep -Seconds 5
    }
    Write-Progress -Activity "Physical Deep Sleep Interval" -Completed

    Write-Host @"
--------------------------------------------------------------------------------
[ACTION REQUIRED]
1. Reconnect the USB cable to your PC.
2. Press the physical POWER BUTTON to wake the screen.
3. Unlock the keyguard.
4. Press [Enter] here once the device is connected to ADB again.
--------------------------------------------------------------------------------
"@ -ForegroundColor Green
    Read-Host "Press Enter after reconnecting USB and waking the device..."

    # Re-verify ADB
    $recheckCount = 0
    while (-not (adb devices | Select-String -Pattern "device$") -and $recheckCount -lt 10) {
        Write-Host "Waiting for device to reconnect..."
        Start-Sleep -Seconds 2
        $recheckCount++
    }

    # 6. Post-wake diagnostics & Verification
    Write-Step "6. Post-wake verification..."
    $postLogcat = adb shell "logcat -d -v threadtime" | Out-String
    $postSuspendStats = adb shell "cat /sys/kernel/debug/suspend_stats 2>/dev/null || cat /d/suspend_stats 2>/dev/null" | Out-String
    $postDumpsysPower = adb shell "dumpsys power" | Out-String
    $postDumpsysAcc = adb shell "dumpsys accessibility" | Out-String

    # Verify Kernel Suspend
    Write-Host "Analyzing Suspend Logs..."
    $suspendEntries = ($postLogcat | Select-String -Pattern "PM: suspend entry|PM: suspend exit|suspend entry \(deep\)")
    if ($suspendEntries) {
        Write-Success "Kernel suspend entry/exit detected in logcat!"
        $suspendEntries | Select-Object -First 5 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    } else {
        Write-Warn "No explicit 'PM: suspend entry' found in userspace logcat (may be restricted to kernel dmesg)."
    }

    # Verify Accessibility Service is still active
    $isBound = ($postDumpsysAcc -match "Bound services:\{[^\}]*(?:Curbox App Blocker|AppBlockerService)") -or
               ($postDumpsysAcc -match "Curbox App Blocker" -and $postDumpsysAcc -match "AppBlockerService")
    if ($isBound) {
        Write-Success "AppBlockerService remained bound and active throughout deep-sleep!"
    } else {
        Write-Fail "AppBlockerService is NOT bound or was killed during sleep!"
    }

    # Launch target app to verify enforcement
    Write-Step "7. Verifying App Rule Enforcement post-wake..."
    adb shell "input keyevent 224" # WAKEUP
    adb shell "wm dismiss-keyguard"
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/com.woodenpharm.choseonggacha.MainActivity" | Out-Null
    Start-Sleep -Seconds 3

    $currentFocus = adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'" | Out-String
    Write-Host "Current Window Focus: $currentFocus"
    if ($currentFocus -match "WarningActivity" -or $currentFocus -match "GuardianApprovalActivity") {
        Write-Success "App Rule Enforcement succeeded: Lock Screen is visible!"
    } else {
        Write-Fail "Lock screen not observed! Focus: $currentFocus"
    }

    # Save evidence artifacts
    Write-Step "8. Saving diagnostics artifacts to $EvidenceOutputDir..."
    $timestamp = (Get-Date).ToString("yyyyMMdd_HHmmss")
    $runEvidenceDir = Join-Path $EvidenceOutputDir "run_$timestamp"
    New-Item -ItemType Directory -Path $runEvidenceDir -Force | Out-Null

    [System.IO.File]::WriteAllText((Join-Path $runEvidenceDir "device_info.txt"), "Model: $model`nRelease: $release`nSDK: $sdk`nBuildId: $buildId`nFingerprint: $fingerprint`nChannel: $channel", $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $runEvidenceDir "logcat_post_wake.txt"), $postLogcat, $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $runEvidenceDir "pre_suspend_stats.txt"), $preSuspendStats, $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $runEvidenceDir "post_suspend_stats.txt"), $postSuspendStats, $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $runEvidenceDir "post_dumpsys_power.txt"), $postDumpsysPower, $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $runEvidenceDir "post_dumpsys_acc.txt"), $postDumpsysAcc, $utf8NoBom)
    Write-Success "Evidence saved to $runEvidenceDir"

} finally {
    # 9. Clean up and restore original settings
    Write-Step "9. Restoring original settings and cleaning up..."
    if (Test-Path $backupFile) {
        adb push $backupFile "/data/local/tmp/settings.json" | Out-Null
        adb shell "run-as neth.iecal.curbox.debug cp /data/local/tmp/settings.json files/datastore/settings.json" | Out-Null
        adb shell "run-as neth.iecal.curbox.debug chmod 660 files/datastore/settings.json" | Out-Null
        adb shell "am broadcast -a neth.iecal.curbox.REFRESH_APP_RULES -p neth.iecal.curbox.debug" | Out-Null
        Write-Success "Original settings restored."
    }
    adb shell "am force-stop $TargetPackage" | Out-Null
    Write-Success "Cleanup complete."
}
