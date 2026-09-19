<#
.SYNOPSIS
    End-to-end test script for Guardian Skip for Today approval flow on a connected device via ADB.
.DESCRIPTION
    1. Backs up original settings.json from device using device-test-common.
    2. Injects verified test AppRule configuration onto target package.
    3. Launches target package and verifies GuardianApprovalActivity is displayed.
    4. Opens "오늘 건너뛰기 / 이 규칙 건너뛰기" dialog via UIAutomator.
    5. Selects "리셋 시까지 / Skip until the next reset" radio option and applies approval.
    6. Verifies return to target app screen and DataStore persistence in appRuleOverrideState.skips.
    7. Force-stops target app and relaunches it to verify unblocked execution is maintained.
    8. Restores original settings.json and cleans up.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "com.woodenpharm.choseonggacha.MainActivity"
)

$ErrorActionPreference = "Stop"

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice

$tmpDir = Join-Path $env:TEMP "curbox_skip_today_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$passedAll = $true

try {
    Write-Step "1. Backing up device settings.json..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile
    Set-DeviceAwake $true
    Write-Success "Backup saved to $backupFile and wake lock acquired."

    $testStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

    Write-Step "2. Crafting Test AppRule configuration..."
    $targetGroupId = "test-target-group-01"
    $contributorGroupId = "test-contrib-group-01"
    $ruleId = "test-rule-01"

    $appRuleSnapshot = New-ContributorAppRuleConfig `
        -TargetPackage $TargetPackage `
        -ContributorPackage "com.initialcoms.ridi" `
        -RequiredMinutes 15 `
        -AllowedMinutes 30 `
        -TargetGroupId $targetGroupId `
        -ContributorGroupId $contributorGroupId `
        -RuleId $ruleId

    Write-Step "3. Injecting test AppRule configuration via broadcast seam..."
    Inject-TestAppRules -AppRuleSnapshot $appRuleSnapshot
    Write-Success "Injected test AppRules via broadcast seam."

    Write-Step "4. Launching Target App ($TargetPackage) to trigger Lock Screen..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/$TargetActivity" | Out-Null

    Write-Step "5. Verifying GuardianApprovalActivity is displayed..."
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $focusResult = $null
    while ($sw.Elapsed.TotalSeconds -lt 10) {
        $focusResult = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        if ($focusResult.Success) { break }
        Start-Sleep -Milliseconds 500
    }

    if ($focusResult -and $focusResult.Success) {
        Write-Success "GuardianApprovalActivity is on top!"
    } else {
        Write-Fail "GuardianApprovalActivity is NOT on top! Focus: $($focusResult.RawFocus)"
        $passedAll = $false
    }

    $ui = Wait-For-UI "approval_skip_rule|Skip this rule|Skip for today|이 규칙 건너뛰기|오늘 건너뛰기" 8
    if ($ui -match "approval_skip_rule" -or $ui -match "Skip this rule" -or $ui -match "Skip for today" -or $ui -match "이 규칙 건너뛰기" -or $ui -match "오늘 건너뛰기") {
        Write-Success "Approval screen has Skip Rule button."
    } else {
        Write-Fail "Approval screen missing Skip Rule button."
        $passedAll = $false
    }

    Write-Step "6. Opening Guardian Skip Rule Dialog..."
    $clicked = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_skip_rule"' "이 규칙 건너뛰기 버튼 (Resource-ID)" -Optional
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="오늘 건너뛰기"' "오늘 건너뛰기 텍스트 버튼 (Korean)" -Optional
    }
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="이 규칙 건너뛰기"' "이 규칙 건너뛰기 텍스트 버튼 (Korean)" -Optional
    }
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="Skip this rule"' "Skip this rule text button (English)" -Optional
    }
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="Skip for today"' "Skip for today text button (English)"
    }

    Write-Step "7. Selecting 'Skip until reset' in Radio Group Dialog..."
    $uiDialog = Wait-For-UI "guardian_skip_until_reset|Skip until the next reset|리셋 시까지" 6
    $radioSelected = Tap-Node $uiDialog 'text="리셋 시까지"' "리셋 시까지 라디오 옵션 (Korean text)" -Optional
    if (-not $radioSelected) {
        $radioSelected = Tap-Node $uiDialog 'text="다음 리셋 시까지"' "다음 리셋 시까지 라디오 옵션 (Korean text)" -Optional
    }
    if (-not $radioSelected) {
        $radioSelected = Tap-Node $uiDialog 'text="Skip until the next reset"' "Skip until the next reset radio option (English text)" -Optional
    }
    if (-not $radioSelected) {
        $radioSelected = Tap-Node $uiDialog 'resource-id="android:id/text1"[^>]*text="[^"]*(?:리셋 시까지|next reset)[^"]*"' "리셋 시까지/next reset 라디오 옵션 (ID + Pattern)" -Optional
    }
    if (-not $radioSelected) {
        $radioSelected = Tap-Node $uiDialog 'text="[^"]*(?:리셋 시까지|next reset)[^"]*"' "리셋 시까지/next reset 라디오 옵션 (Pattern fallback)"
    }

    Write-Step "8. Applying Skip for Today approval..."
    $uiAfterRadio = Dump-UI
    $hasDialogButton = ($uiAfterRadio -match 'resource-id="android:id/button1"' -or $uiAfterRadio -match 'text="적용"' -or $uiAfterRadio -match 'text="확인"' -or $uiAfterRadio -match 'text="Apply"' -or $uiAfterRadio -match 'text="OK"')
    if ($hasDialogButton) {
        $applied = Tap-Node $uiAfterRadio 'resource-id="android:id/button1"' "적용/확인 버튼 (Resource-ID)" -Optional
        if (-not $applied) {
            $applied = Tap-Node $uiAfterRadio 'text="적용"' "적용 텍스트 버튼 (Korean)" -Optional
        }
        if (-not $applied) {
            $applied = Tap-Node $uiAfterRadio 'text="확인"' "확인 텍스트 버튼 (Korean)" -Optional
        }
        if (-not $applied) {
            $applied = Tap-Node $uiAfterRadio 'text="Apply"' "Apply text button (English)" -Optional
        }
        if (-not $applied) {
            $applied = Tap-Node $uiAfterRadio 'text="OK"' "OK text button (English)"
        }
    } else {
        Write-Host "Radio option selection automatically committed and dismissed dialog." -ForegroundColor DarkGray
    }
    Start-Sleep -Seconds 2

    Write-Step "9. Verifying Target App screen return and unlock..."
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $targetFocus = $null
    while ($sw.Elapsed.TotalSeconds -lt 10) {
        $targetFocus = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if ($targetFocus.Success) { break }
        Start-Sleep -Milliseconds 500
    }

    if ($targetFocus -and $targetFocus.Success) {
        Write-Success "Target app is unlocked and foregrounded!"
    } else {
        Write-Fail "Target app was not unlocked / did not regain focus! Focus: $($targetFocus.RawFocus)"
        $passedAll = $false
    }

    Write-Step "10. Verifying Skip Persistence in DataStore..."
    $updatedObj = Get-DeviceSettings -AsObject
    $minSkipThresholdMs = $testStartTimeMs + (31 * 60 * 1000)
    $hasSkip = Test-AppRuleSkip -OverrideState $updatedObj.appRuleOverrideState -RuleId $ruleId -MinSkipUntilMs $minSkipThresholdMs
    if ($hasSkip) {
        $skipsJson = $updatedObj.appRuleOverrideState.skips | ConvertTo-Json -Compress
        Write-Success "DataStore successfully recorded skip override for rule '$ruleId' valid until reset! Skips: $skipsJson"
    } else {
        Write-Fail "Skip for rule '$ruleId' (until reset) not found in DataStore settings! State: $($updatedObj.appRuleOverrideState | ConvertTo-Json -Compress)"
        $passedAll = $false
    }

    Write-Step "11. Verifying Target App relaunch maintains unblocked execution..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/$TargetActivity" | Out-Null

    Start-Sleep -Seconds 2
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $relaunchSuccess = $true
    while ($sw.Elapsed.TotalSeconds -lt 5) {
        $currentFocus = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if (-not $currentFocus.Success) {
            Write-Fail "Target app did not maintain focus upon restart! Focus: $($currentFocus.RawFocus)"
            $relaunchSuccess = $false
            $passedAll = $false
            break
        }
        $blockerFocus = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity|WarningActivity" -PassThru
        if ($blockerFocus.Success) {
            Write-Fail "Target app was unexpectedly blocked upon restart! Focus: $($blockerFocus.RawFocus)"
            $relaunchSuccess = $false
            $passedAll = $false
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if ($relaunchSuccess) {
        Write-Success "Target app relaunched cleanly without blocking screen and retained foreground focus!"
    }

    Write-Step "12. Final Result Summary"
    if ($passedAll) {
        Write-Host "`n=========================================================================" -ForegroundColor Green
        Write-Host ">>> [TOTAL GUARDIAN SKIP TODAY RESULT: PASS] Skip Today flow verified! <<<" -ForegroundColor Green
        Write-Host "=========================================================================`n" -ForegroundColor Green
    } else {
        Write-Host "`n=========================================================================" -ForegroundColor Red
        Write-Host ">>> [TOTAL GUARDIAN SKIP TODAY RESULT: FAIL] Some checks failed. Inspect logs above. <<<" -ForegroundColor Red
        Write-Host "=========================================================================`n" -ForegroundColor Red
        exit 1
    }

} finally {
    Write-Step "13. Cleanup & Restoring original settings.json..."
    try {
        Set-DeviceAwake $false
    } catch { }

    if (Test-Path $backupFile) {
        Restore-DeviceSettings -BackupPath $backupFile | Out-Null
        Write-Success "Original settings restored from backup."
    } else {
        Clear-TestAppRules | Out-Null
    }

    adb shell "am force-stop $TargetPackage" | Out-Null
    adb shell "input keyevent 3" | Out-Null # HOME
    Write-Success "Target app stopped, returned to home."
}
