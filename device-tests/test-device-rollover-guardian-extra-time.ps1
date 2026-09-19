<#
.SYNOPSIS
    End-to-end test script for Guardian Rollover Extra Time on a connected device via ADB.
.DESCRIPTION
    1. Backs up original settings.json and acquires wake lock.
    2. Injects Guardian PIN authentication config (PIN: 1234).
    3. Injects test AppRule with rollover enabled (unlockDays includes today) and accumulated pool (20m).
    4. Launches target package and verifies GuardianApprovalActivity is displayed.
    5. Verifies "누적 시간 사용 (20분 사용 가능)" button is exposed on approval surface.
    6. Opens accumulated grant dialog, verifies 20 minutes is prefilled, and performs 1-Tap approval.
    7. Authenticates with Guardian PIN.
    8. Verifies DataStore records pool deduction and AppRuleGuardianGrant(isFromAccumulatedPool = true).
    9. Verifies target app is unblocked and running in foreground.
    10. Verifies expiration/reset of pool (to 0) on restore boundary transition (unlock day -> accrual day).
    11. Restores original settings.json and cleans up in finally block.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "com.woodenpharm.choseonggacha.MainActivity",
    [string]$Pin = "1234",
    [string]$DeviceId = ""
)

$ErrorActionPreference = "Stop"

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice

$tmpDir = Join-Path $env:TEMP "curbox_rollover_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$passedAll = $true

try {
    Write-Step "1. Backing up device settings.json and acquiring wake lock..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile
    Set-DeviceAwake $true
    Write-Success "Backup saved to $backupFile and wake lock acquired."

    $testStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

    Write-Step "2. Determining device local date and weekday..."
    $dateRaw = (adb shell "date +'%Y-%m-%d %w'" | Out-String).Trim()
    if ($dateRaw -match '^(\d{4}-\d{2}-\d{2})\s+(\d)$') {
        $todayUseDayId = $matches[1]
        $todayWeekday = [int]$matches[2]
    } else {
        $now = [System.DateTime]::UtcNow
        $todayUseDayId = $now.ToString("yyyy-MM-dd")
        $todayWeekday = [int]$now.DayOfWeek
    }

    $parsedDate = [System.DateTime]::ParseExact($todayUseDayId, "yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture)
    $yesterdayDate = $parsedDate.AddDays(-1)
    $yesterdayUseDayId = $yesterdayDate.ToString("yyyy-MM-dd")
    $yesterdayWeekday = [int]$yesterdayDate.DayOfWeek

    Write-Host "Device Today: $todayUseDayId (Weekday: $todayWeekday)" -ForegroundColor Cyan
    Write-Host "Device Yesterday: $yesterdayUseDayId (Weekday: $yesterdayWeekday)" -ForegroundColor Cyan

    Write-Step "3. Generating and injecting Guardian PIN auth config (PIN: $Pin)..."
    $guardianAuth = New-GuardianPinAuthConfig -Pin $Pin
    $injectedAuth = Set-DeviceGuardianAuthConfig -GuardianAuthConfig $guardianAuth
    if (-not $injectedAuth) {
        Write-Fail "Failed to inject Guardian PIN auth config into device settings.json!"
        $passedAll = $false
    } else {
        $deviceSettings = Get-DeviceSettings -AsObject
        if (Test-GuardianAuthConfig $deviceSettings) {
            Write-Success "Guardian PIN auth config successfully verified."
        } else {
            Write-Fail "Injected Guardian PIN auth config was not active in settings.json!"
            $passedAll = $false
        }
    }

    Write-Step "4. Crafting and injecting test AppRule with rollover enabled and accumulated pool on Unlock Day..."
    $targetGroupId = "test-target-group-01"
    $ruleId = "test-rule-rollover-01"
    $initialPoolMinutes = 20

    # Ensure today is an UNLOCK day for the rule
    $appRuleSnapshot = New-RolloverAppRuleConfig `
        -TargetPackage $TargetPackage `
        -UnlockDays @($todayWeekday) `
        -AllowedMinutes 0 `
        -TargetGroupId $targetGroupId `
        -RuleId $ruleId

    $poolObj = New-RuleRolloverPool -RuleId $ruleId -AccumulatedMinutes $initialPoolMinutes -LastSettledUseDayId $todayUseDayId
    $rolloverState = [PSCustomObject]@{
        pools = [PSCustomObject]@{
            $ruleId = $poolObj
        }
    }

    Inject-TestAppRules `
        -AppRuleSnapshot $appRuleSnapshot `
        -AppRuleRolloverState $rolloverState `
        -UsageGenerationStartedAtMs $testStartTimeMs | Out-Null

    Write-Success "Injected test AppRules with rolloverEnabled, unlock day ($todayWeekday), and accumulated pool ($initialPoolMinutes min)."

    Write-Step "6. Launching Target App ($TargetPackage) to trigger GuardianApprovalActivity..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/$TargetActivity" | Out-Null

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $focusResult = $null
    while ($sw.Elapsed.TotalSeconds -lt 10) {
        $focusResult = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        if ($focusResult.Success) { break }
        Start-Sleep -Milliseconds 500
    }

    if ($focusResult -and $focusResult.Success) {
        Write-Success "GuardianApprovalActivity is in foreground!"
    } else {
        Write-Fail "GuardianApprovalActivity is NOT in foreground! Focus: $($focusResult.RawFocus)"
        $passedAll = $false
    }

    Write-Step "7. Verifying '누적 시간 사용 ($initialPoolMinutes`분 사용 가능)' button is displayed..."
    $ui = Wait-For-UI -Pattern "approval_use_accumulated_time" -TimeoutSeconds 8
    $hasButtonNode = ($ui -match 'resource-id="neth.iecal.curbox.debug:id/approval_use_accumulated_time"')
    $hasCorrectMinutes = ($ui -match "text=`"누적 시간 사용 \($initialPoolMinutes`분 사용 가능\)`"" -or `
                          $ui -match "text=`"Use accumulated time \($initialPoolMinutes min available\)`"")
    if ($hasButtonNode -and $hasCorrectMinutes) {
        Write-Success "Approval screen displays accumulated time button with exact available minutes ($initialPoolMinutes min)!"
    } else {
        Write-Fail "Approval screen missing accumulated time button with exact $initialPoolMinutes minutes. UI dump: $ui"
        $passedAll = $false
    }

    Write-Step "8. Tapping Accumulated Time Button to open 1-Tap approval dialog..."
    $tapped = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_use_accumulated_time"' "누적 시간 사용 버튼 (ID)" -Optional
    if (-not $tapped) {
        $tapped = Tap-Node $ui 'text="누적 시간 사용"' "누적 시간 사용 텍스트 (Korean)" -Optional
    }
    if (-not $tapped) {
        $tapped = Tap-Node $ui 'text="Use accumulated time"' "Use accumulated time text (English)"
    }

    $dialogUi = Wait-For-UI "accumulated_minutes_input|accumulated_total_desc|guardian_use_accumulated_time_title|누적 시간 사용" 6

    Write-Step "9. Verifying dialog pre-fills full accumulated amount ($initialPoolMinutes minutes)..."
    $nodeInput = Get-NodeBounds $dialogUi 'resource-id="neth.iecal.curbox.debug:id/accumulated_minutes_input"'
    $hasInput = $nodeInput.Found
    $hasPrefilledAmount = ($dialogUi -match ('resource-id="neth.iecal.curbox.debug:id/accumulated_minutes_input"[^>]*text="' + $initialPoolMinutes + '"') -or `
                           $dialogUi -match ('text="' + $initialPoolMinutes + '"[^>]*resource-id="neth.iecal.curbox.debug:id/accumulated_minutes_input"'))

    if ($hasInput -and $hasPrefilledAmount) {
        Write-Success "Approval dialog pre-filled with total accumulated amount ($initialPoolMinutes minutes) in input field!"
    } else {
        Write-Fail "Dialog input field did not pre-fill expected accumulated minutes ($initialPoolMinutes). UI: $dialogUi"
        $passedAll = $false
    }

    Write-Step "10. Submitting 1-Tap approval without modification..."
    $applied = Tap-Node $dialogUi 'resource-id="android:id/button1"' "적용/확인 버튼 (Resource-ID)" -Optional
    if (-not $applied) {
        $applied = Tap-Node $dialogUi 'text="적용"' "적용 버튼 (Korean)" -Optional
    }
    if (-not $applied) {
        $applied = Tap-Node $dialogUi 'text="Apply"' "Apply button (English)"
    }
    if ($applied) {
        Write-Success "Submitted 1-Tap approval."
    } else {
        Write-Fail "Failed to tap Apply button in accumulated time dialog."
        $passedAll = $false
    }

    Write-Step "11. Authenticating with Guardian PIN ($Pin)..."
    $pinSuccess = Submit-GuardianPin -PinValue $Pin
    if ($pinSuccess) {
        Write-Success "Guardian PIN successfully submitted."
    } else {
        Write-Fail "Failed to submit Guardian PIN."
        $passedAll = $false
    }

    Write-Step "12. Verifying DataStore: pool deducted and AppRuleGuardianGrant(isFromAccumulatedPool = true) issued..."
    Start-Sleep -Seconds 2
    $updatedSettings = Get-DeviceSettings -AsObject
    $expectedGrantedMillis = [long]($initialPoolMinutes * 60 * 1000)

    $grantRecorded = Test-AppRuleGuardianGrant `
        -OverrideState $updatedSettings.appRuleOverrideState `
        -RuleId $ruleId `
        -ExpectedGrantedMillis $expectedGrantedMillis `
        -IsFromAccumulatedPool $true `
        -ExpectedUseDayId $todayUseDayId

    if ($grantRecorded) {
        Write-Success "DataStore successfully recorded AppRuleGuardianGrant with isFromAccumulatedPool = true ($expectedGrantedMillis ms)!"
    } else {
        Write-Fail "AppRuleGuardianGrant(isFromAccumulatedPool = true) NOT found in DataStore! Overrides: $($updatedSettings.appRuleOverrideState | ConvertTo-Json -Compress)"
        $passedAll = $false
    }

    $updatedPool = Get-RuleRolloverPool -SettingsOrRolloverState $updatedSettings -RuleId $ruleId
    if ($updatedPool -and $updatedPool.accumulatedMinutes -eq 0) {
        Write-Success "Accumulated pool successfully deducted to 0 minutes ($initialPoolMinutes - $initialPoolMinutes = 0)."
    } else {
        $poolMinutes = if ($updatedPool) { $updatedPool.accumulatedMinutes } else { "null" }
        Write-Fail "Accumulated pool was not deducted to 0! Observed pool minutes: $poolMinutes"
        $passedAll = $false
    }

    Write-Step "13. Verifying Target App is immediately unlocked and relaunched in foreground..."
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $targetFocus = $null
    while ($sw.Elapsed.TotalSeconds -lt 10) {
        $targetFocus = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if ($targetFocus.Success) { break }
        Start-Sleep -Milliseconds 500
    }

    if ($targetFocus -and $targetFocus.Success) {
        Write-Success "Target app is unblocked and running in foreground!"
    } else {
        Write-Fail "Target app did not regain foreground focus! Focus: $($targetFocus.RawFocus)"
        $passedAll = $false
    }

    Write-Step "14. Verifying boundary transition (Unlock Day -> Accrual Day): Pool reset to 0..."
    # Craft transition state:
    # Rule unlockDays was only yesterday ($yesterdayWeekday); today ($todayWeekday) is an ACCRUAL day.
    # Prior pool was settled yesterday on an unlock day with 25 accumulated minutes.
    $transitionRule = New-RolloverAppRuleConfig `
        -TargetPackage $TargetPackage `
        -UnlockDays @($yesterdayWeekday) `
        -AllowedMinutes 0 `
        -TargetGroupId $targetGroupId `
        -RuleId $ruleId

    Inject-TestAppRules -AppRuleSnapshot $transitionRule -UsageGenerationStartedAtMs $testStartTimeMs | Out-Null

    $preTransitionMinutes = 25
    $transitionPool = New-RuleRolloverPool -RuleId $ruleId -AccumulatedMinutes $preTransitionMinutes -LastSettledUseDayId $yesterdayUseDayId
    $transitionRolloverState = [PSCustomObject]@{
        pools = [PSCustomObject]@{
            $ruleId = $transitionPool
        }
    }
    Set-DeviceRolloverState -RolloverState $transitionRolloverState | Out-Null
    Write-Host "Injected pre-transition pool ($preTransitionMinutes minutes on unlock day '$yesterdayUseDayId'). Current day is accrual day '$todayUseDayId'." -ForegroundColor Cyan

    # Trigger settlement catch-up on device
    # BootReceiver processes MY_PACKAGE_REPLACED and reconciles settlement via AppRuleRolloverCoordinator
    adb shell "am broadcast -a android.intent.action.MY_PACKAGE_REPLACED -p neth.iecal.curbox.debug" | Out-Null
    Start-Sleep -Seconds 2

    # Also signal app blocker refresh
    adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p neth.iecal.curbox.debug" | Out-Null
    Start-Sleep -Seconds 1

    $postTransitionSettings = Get-DeviceSettings -AsObject
    $postTransitionPool = Get-RuleRolloverPool -SettingsOrRolloverState $postTransitionSettings -RuleId $ruleId

    if ($postTransitionPool -and $postTransitionPool.accumulatedMinutes -eq 0) {
        Write-Success "Boundary transition successfully expired pool to 0 minutes! (Unlock day $yesterdayUseDayId -> Accrual day $todayUseDayId)"
    } else {
        # Check if restarting service triggers catchup if receiver hasn't run yet
        $servicePid = Get-DeviceProcessPid -ProcessName ":app_blocker_service"
        if ($servicePid) {
            Stop-ServiceProcess -TargetPid $servicePid | Out-Null
            Start-Sleep -Seconds 3
            $postTransitionSettings = Get-DeviceSettings -AsObject
            $postTransitionPool = Get-RuleRolloverPool -SettingsOrRolloverState $postTransitionSettings -RuleId $ruleId
        }

        if ($postTransitionPool -and $postTransitionPool.accumulatedMinutes -eq 0) {
            Write-Success "Boundary transition successfully expired pool to 0 minutes after service settlement catch-up!"
        } else {
            $poolMinutes = if ($postTransitionPool) { $postTransitionPool.accumulatedMinutes } else { "null" }
            Write-Fail "Pool was NOT reset to 0 after boundary transition! Observed minutes: $poolMinutes"
            $passedAll = $false
        }
    }

    Write-Step "15. Final Result Summary"
    if ($passedAll) {
        Write-Host "`n=========================================================================================" -ForegroundColor Green
        Write-Host ">>> [TOTAL ROLLOVER GUARDIAN EXTRA TIME RESULT: PASS] All requirements verified! <<<" -ForegroundColor Green
        Write-Host "=========================================================================================`n" -ForegroundColor Green
    } else {
        Write-Host "`n=========================================================================================" -ForegroundColor Red
        Write-Host ">>> [TOTAL ROLLOVER GUARDIAN EXTRA TIME RESULT: FAIL] Some checks failed. Inspect logs. <<<" -ForegroundColor Red
        Write-Host "=========================================================================================`n" -ForegroundColor Red
        exit 1
    }

} finally {
    Write-Step "16. Cleanup & Restoring original settings..."
    try {
        Set-DeviceAwake $false
    } catch { }

    if (Test-Path $backupFile) {
        Restore-DeviceSettings -BackupPath $backupFile | Out-Null
        Write-Success "Original settings restored from backup."
    }

    Clear-TestAppRules | Out-Null

    adb shell "am force-stop $TargetPackage" | Out-Null
    adb shell "input keyevent 3" | Out-Null # HOME
    Write-Success "Target app stopped and device returned to home."
}
