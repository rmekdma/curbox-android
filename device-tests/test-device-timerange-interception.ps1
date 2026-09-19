<#
.SYNOPSIS
    End-to-end test script for real-time Time Range Interception on a connected device via ADB.
.DESCRIPTION
    1. Backs up original settings.json from device using device-test-common.
    2. Ensures device is awake (Set-DeviceAwake $true).
    3. Queries device local time and crafts an AppRule with a timeRanges schedule starting at next minute ((currentMinute + 1) % 1440).
    4. Injects test AppRule configuration via broadcast seam.
    5. Launches target app in advance and verifies it is running unblocked in the foreground.
    6. Safely waits for next minute 00s rollover.
    7. Verifies AppRuleWakeScheduler triggers real-time interception and GuardianApprovalActivity covers the screen (polling up to 90s).
    8. Restores original settings.json, reverts stay-awake state, and cleans up.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "com.woodenpharm.choseonggacha.MainActivity",
    [int]$MaxPollSeconds = 90
)

$ErrorActionPreference = "Stop"

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice

$tmpDir = Join-Path $env:TEMP "curbox_timerange_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$passedAll = $true

try {
    Write-Step "0. Ensuring device is awake..."
    Set-DeviceAwake $true

    Write-Step "1. Backing up device settings.json..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile
    Write-Success "Backup saved to $backupFile"

    Write-Step "2. Determining device local time and target minute..."
    $timeInfo = Get-DeviceTimeInfo
    Write-Host "Device local time: $($timeInfo.TimeString) (CurrentMinute: $($timeInfo.CurrentMinute))"

    # If seconds are high (>= 45s), wait for current minute to roll over to guarantee safe injection and launch margins
    if ($timeInfo.Second -ge 45) {
        $bufferWait = $timeInfo.SecondsUntilNextMinute + 2
        Write-Host "Second is $($timeInfo.Second)s (>= 45s). Waiting ${bufferWait}s for minute turnover to ensure sufficient test window..." -ForegroundColor Yellow
        Start-Sleep -Seconds $bufferWait
        $timeInfo = Get-DeviceTimeInfo
        Write-Host "Refreshed device time: $($timeInfo.TimeString) (CurrentMinute: $($timeInfo.CurrentMinute))"
    }

    $startMinute = $timeInfo.NextMinute
    $endMinute = ($startMinute + 10) % 1440
    Write-Host "Target schedule timeRange: startMinute=$startMinute, endMinute=$endMinute" -ForegroundColor Cyan

    Write-Step "3. Crafting and injecting AppRule with timeRange [$startMinute, $endMinute]..."
    $appRuleSnapshot = New-TimeRangeAppRuleConfig `
        -TargetPackage $TargetPackage `
        -StartMinute $startMinute `
        -EndMinute $endMinute `
        -AllowedMinutes 0

    $testEpochMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Inject-TestAppRules -AppRuleSnapshot $appRuleSnapshot -UsageGenerationStartedAtMs $testEpochMs
    Write-Success "Test AppRule configuration injected successfully."

    Write-Step "4. Launching Target App ($TargetPackage) in advance (before timeRange start)..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Milliseconds 500
    adb shell "am start -n $TargetPackage/$TargetActivity" | Out-Null

    $launchSw = [System.Diagnostics.Stopwatch]::StartNew()
    $targetFocused = $false
    while ($launchSw.Elapsed.TotalSeconds -lt 8) {
        $focus = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if ($focus.Success -and $focus.RawFocus -notmatch "GuardianApprovalActivity") {
            $targetFocused = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }

    if ($targetFocused) {
        Write-Success "Target app is running unblocked in foreground prior to scheduled time."
    } else {
        Write-Fail "Target app failed to enter foreground or was prematurely blocked. Current focus: $($focus.RawFocus)"
        $passedAll = $false
    }

    Write-Step "5. Safely waiting until scheduled time arrives (minute ${startMinute}:00)..."
    Wait-DeviceMinute -TargetMinute $startMinute

    Write-Step "6. Monitoring for real-time GuardianApprovalActivity interception (polling up to ${MaxPollSeconds}s)..."
    $pollSw = [System.Diagnostics.Stopwatch]::StartNew()
    $intercepted = $false
    $lastFocus = ""

    while ($pollSw.Elapsed.TotalSeconds -lt $MaxPollSeconds) {
        $focusCheck = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        $lastFocus = $focusCheck.RawFocus
        if ($focusCheck.Success) {
            $intercepted = $true
            $elapsed = [math]::Round($pollSw.Elapsed.TotalSeconds, 1)
            Write-Success "GuardianApprovalActivity successfully intercepted screen after ${elapsed}s! Focus: $lastFocus"
            break
        }
        Start-Sleep -Milliseconds 500
    }

    if ($intercepted) {
        $uiDump = Dump-UI
        if ($uiDump -match "GuardianApprovalActivity" -or $uiDump -match "보호자" -or $uiDump -match "시간" -or $uiDump -match "차단") {
            Write-Success "Lock screen UI verified in UI dump."
        } else {
            Write-Host "Note: Lock screen activity focused; UI dump retrieved." -ForegroundColor Gray
        }
    } else {
        Write-Fail "GuardianApprovalActivity did NOT intercept within ${MaxPollSeconds}s. Last focus: $lastFocus"
        $passedAll = $false
    }

    Write-Step "7. Final Result Summary"
    if ($passedAll) {
        Write-Host "
=================================================================================" -ForegroundColor Green
        Write-Host ">>> [TOTAL TIMERANGE INTERCEPTION RESULT: PASS] Real-time Interception verified! <<<" -ForegroundColor Green
        Write-Host "=================================================================================
" -ForegroundColor Green
    } else {
        Write-Host "
=================================================================================" -ForegroundColor Red
        Write-Host ">>> [TOTAL TIMERANGE INTERCEPTION RESULT: FAIL] Checks failed. Inspect logs above. <<<" -ForegroundColor Red
        Write-Host "=================================================================================
" -ForegroundColor Red
        exit 1
    }

} finally {
    Write-Step "Cleanup: Restoring original settings and resetting awake state..."
    Set-DeviceAwake $false
    if (Test-Path $backupFile) {
        Restore-DeviceSettings -BackupPath $backupFile | Out-Null
        adb shell "am force-stop $TargetPackage" | Out-Null
        adb shell "input keyevent 3"
        Write-Success "Original settings restored, target app stopped, returned to home."
    }
}
