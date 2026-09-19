<#
.SYNOPSIS
    End-to-end test script for Contributor App Usage Unlock Flow on a connected device via ADB.
.DESCRIPTION
    1. Backs up original settings.json from device using device-test-common.
    2. Injects test AppRule configuration requiring 1 minute of contributor app usage.
    3. Launches target app and verifies GuardianApprovalActivity blocks it with '사용 조건 미달' (Step 1).
    4. Launches contributor app (com.initialcoms.ridi) and maintains foreground for 70s to accumulate usage (Step 2).
    5. Returns to home screen and waits 2s to flush usage session (Step 3).
    6. Relaunches target app and verifies it is unblocked and running in foreground (Step 4).
    7. Restores original settings.json and cleans up (Step 5).
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "com.woodenpharm.choseonggacha.MainActivity",
    [string]$ContributorPackage = "com.initialcoms.ridi",
    [string]$ContributorActivity = "com.ridi.books.viewer.main.activity.SplashActivity",
    [int]$UsageWaitSeconds = 70
)

$ErrorActionPreference = "Stop"

. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice

$tmpDir = Join-Path $env:TEMP "curbox_contrib_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$passedAll = $true

try {
    Write-Step "0. Backing up device settings.json..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile
    Write-Success "Backup saved to $backupFile"

    $testEpochMs = [long](adb shell "date +%s%3N").Trim()
    $appRuleSnapshot = New-ContributorAppRuleConfig `
        -TargetPackage $TargetPackage `
        -ContributorPackage $ContributorPackage `
        -RequiredMinutes 1 `
        -AllowedMinutes 1440 `
        -EffectiveFromMs $testEpochMs

    Inject-TestAppRules -AppRuleSnapshot $appRuleSnapshot
    Write-Success "Test AppRule configuration injected successfully."

    Write-Step "1-1. Ensuring device is awake..."
    Set-DeviceAwake $true
    adb shell "input keyevent 224"
    adb shell "wm dismiss-keyguard"
    Start-Sleep -Seconds 1

    Write-Step "1-2. Launching Target App ($TargetPackage) - Expecting Block (GuardianApprovalActivity)..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/$TargetActivity" | Out-Null

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $focusResult = $null
    while ($sw.Elapsed.TotalSeconds -lt 8) {
        $focusResult = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        if ($focusResult.Success) { break }
        Start-Sleep -Milliseconds 500
    }
    Write-Host "Focused Window/App: $($focusResult.RawFocus)"

    if ($focusResult.Success) {
        Write-Success "GuardianApprovalActivity is on top of screen!"
    } else {
        Write-Fail "GuardianApprovalActivity is NOT on top."
        $passedAll = $false
    }

    $uiDump = Dump-UI
    $hasConditionNotMet = $uiDump -match "사용 조건 미달" -or $uiDump -match "Usage condition not met"
    if ($hasConditionNotMet) {
        Write-Success "Lock Screen correctly displays '사용 조건 미달 (Usage condition not met)'!"
    } else {
        Write-Fail "Lock Screen does not display '사용 조건 미달'."
        $passedAll = $false
    }

    Write-Step "2. Launching Contributor App ($ContributorPackage) and holding foreground for ${UsageWaitSeconds}s..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    adb shell "am force-stop $ContributorPackage" | Out-Null
    Start-Sleep -Milliseconds 500

    adb shell "am start -n $ContributorPackage/$ContributorActivity" | Out-Null
    Start-Sleep -Seconds 2

    $contribFocus = Assert-WindowFocus -ExpectedActivity $ContributorPackage -PassThru
    Write-Host "Contributor Focused Window/App: $($contribFocus.RawFocus)"
    if ($contribFocus.Success) {
        Write-Success "Contributor app is running in foreground."
    } else {
        Write-Fail "Contributor app is NOT focused."
        $passedAll = $false
    }

    Write-Host "Accumulating usage for $UsageWaitSeconds seconds (monitoring 20s heartbeats)..." -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $UsageWaitSeconds) {
        $remaining = [math]::Max(0, [int]($UsageWaitSeconds - $sw.Elapsed.TotalSeconds))
        Write-Host "Elapsed: $([int]$sw.Elapsed.TotalSeconds)s / ${UsageWaitSeconds}s (remaining: ${remaining}s)"
        Start-Sleep -Seconds 10
    }
    Write-Success "Usage accumulation completed ($([int]$sw.Elapsed.TotalSeconds) seconds)."

    Write-Step "3. Navigating to Home and waiting 2 seconds to flush usage session..."
    adb shell "input keyevent 3"
    Start-Sleep -Seconds 2
    Write-Success "Switched to Home screen and waited 2 seconds for session flush."

    Write-Step "4. Relaunching Target App ($TargetPackage) - Expecting Unblocked (MainActivity on top)..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/$TargetActivity" | Out-Null
    Start-Sleep -Seconds 3

    $finalFocus = Assert-WindowFocus -ExpectedActivity "$TargetPackage" -PassThru
    Write-Host "Target App Focused Window/App: $($finalFocus.RawFocus)"

    $isBlockedByGuardian = $finalFocus.RawFocus -match "GuardianApprovalActivity"
    $isMainActivityOnTop = $finalFocus.RawFocus -match [regex]::Escape($TargetActivity) -and (-not $isBlockedByGuardian)

    if ($isMainActivityOnTop) {
        Write-Success "Target App ($TargetPackage) is unblocked with $TargetActivity on top!"
    } else {
        # Retry once after 1 second if still transitioning
        Start-Sleep -Seconds 1
        $retryFocus = Assert-WindowFocus -ExpectedActivity "$TargetPackage" -PassThru
        $isBlockedRetry = $retryFocus.RawFocus -match "GuardianApprovalActivity"
        $isMainActivityRetry = $retryFocus.RawFocus -match [regex]::Escape($TargetActivity) -and (-not $isBlockedRetry)
        if ($isMainActivityRetry) {
            Write-Success "Target App ($TargetPackage) is unblocked with $TargetActivity on top!"
        } else {
            Write-Fail "Target App was NOT unblocked or MainActivity is not on top! Current focus: $($retryFocus.RawFocus)"
            $passedAll = $false
        }
    }

    Write-Step "5. Final Result Summary"
    if ($passedAll) {
        Write-Host "
=================================================================================" -ForegroundColor Green
        Write-Host ">>> [TOTAL CONTRIBUTOR FLOW RESULT: PASS] Contributor Unlock verified! <<<" -ForegroundColor Green
        Write-Host "=================================================================================
" -ForegroundColor Green
    } else {
        Write-Host "
=================================================================================" -ForegroundColor Red
        Write-Host ">>> [TOTAL CONTRIBUTOR FLOW RESULT: FAIL] Some checks failed. Inspect logs above. <<<" -ForegroundColor Red
        Write-Host "=================================================================================
" -ForegroundColor Red
        exit 1
    }

} finally {
    Write-Step "Cleanup: Restoring original settings and returning home..."
    Set-DeviceAwake $false
    if (Test-Path $backupFile) {
        Restore-DeviceSettings -BackupPath $backupFile | Out-Null
        adb shell "am force-stop $TargetPackage" | Out-Null
        adb shell "am force-stop $ContributorPackage" | Out-Null
        adb shell "input keyevent 3"
        Write-Success "Original settings restored, apps stopped, returned to home."
    }
}
