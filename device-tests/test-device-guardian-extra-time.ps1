<#
.SYNOPSIS
    End-to-end test script for Guardian Extra Time Grant & Unlock on a connected device via ADB.
.DESCRIPTION
    1. Backs up original settings.json from device using device-test-common.
    2. Injects verified test AppRule configuration onto target package.
    3. Verifies GuardianApprovalActivity appears when target app launches.
    4. Opens "추가 시간 변경" (Change Extra Time) dialog and grants 15 minutes.
    5. Verifies grant persistence in DataStore and app unblocking.
    6. Restores original settings.json and cleans up.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "",
    [string]$OtherPackage = "com.initialcoms.ridi"
)

$ErrorActionPreference = "Stop"

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice
$accessibilityBackup = Backup-DeviceAccessibilitySettings

$tmpDir = Join-Path $env:TEMP "curbox_extra_time_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$passedAll = $true

try {
    Write-Step "1. Backing up device settings.json..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile
    Write-Success "Backup saved to $backupFile"

    Write-Step "2. Crafting a denying rule and eligible rules for two other app rules..."
    $testStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $deviceTimeInfo = Get-DeviceTimeInfo
    if (-not $deviceTimeInfo) { throw "Could not read device time for the future-window rule fixture." }
    $futureWindowStartMinute = (($deviceTimeInfo.CurrentMinute + 120) % 1440)
    $ruleId = "usage-rule"
    $nightRuleId = "night-rule"
    $futureRuleId = "future-rule"
    $appRuleSnapshot = New-GuardianExtraTimePickerAppRuleConfig `
        -TargetPackage $TargetPackage `
        -OtherPackage $OtherPackage `
        -NightRuleId $nightRuleId `
        -UsageRuleId $ruleId `
        -FutureRuleId $futureRuleId `
        -FutureWindowStartMinute $futureWindowStartMinute

    Write-Step "3. Injecting test AppRule configuration via broadcast seam..."
    Inject-TestAppRules -AppRuleSnapshot $appRuleSnapshot -UsageGenerationStartedAtMs $testStartTimeMs
    Write-Success "Injected test AppRules via broadcast seam."

    Write-Step "4. Launching Target App ($TargetPackage) to trigger Lock Screen..."
    Set-DeviceAwake $true
    adb shell "input keyevent 224" # WAKEUP
    adb shell "wm dismiss-keyguard"
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    Start-TestApp -PackageName $TargetPackage -ActivityName $TargetActivity

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

    $ui = Wait-For-UI "approval_add_time|Change extra time|추가 시간 변경" 8
    if ($ui -match "approval_add_time" -or $ui -match "추가 시간 변경" -or $ui -match "Change extra time") {
        Write-Success "Approval screen has '추가 시간 변경' button."
    } else {
        Write-Fail "Approval screen missing '추가 시간 변경' button."
        $passedAll = $false
    }

    Write-Step "6. Opening Guardian Extra Time Dialog..."
    $clicked = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_add_time"' "추가 시간 변경 버튼" -Optional
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="추가 시간 변경"' "추가 시간 변경 텍스트" -Optional
    }
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="Change extra time"' "Change extra time text"
    }

    $ui = Wait-For-UI "current_total" 6
    $picker = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/rule_picker"'
    $pickerCandidates = $null
    $canSubmitUsageGrant = $false
    if ($picker.Found) {
        $pickerCandidates = Get-TestRulePickerOptions -CurrentUi $ui -CandidateCount 3
        $candidateLabels = @($pickerCandidates.Options | ForEach-Object { $_.Label })
        $expectedCandidateLabels = @("BlockingRule", "UsageRule", "FutureRule")
        $hasExpectedCandidates = ($pickerCandidates.Success -and
            $candidateLabels.Count -eq $expectedCandidateLabels.Count -and
            @($expectedCandidateLabels | Where-Object { $candidateLabels -notcontains $_ }).Count -eq 0)
        if ($hasExpectedCandidates -and $candidateLabels -notcontains "NightRule") {
            Write-Success "Picker includes another app's future-window rule and excludes the disallowed blocking rule."
        } else {
            Write-Fail "Picker did not show the expected eligible rule list."
            Write-Host "[DIAGNOSTIC] Picker options: $($candidateLabels -join ' | ')" -ForegroundColor DarkYellow
            $passedAll = $false
        }

        if ($hasExpectedCandidates) {
            $futureSelection = Select-TestRulePickerOption -CurrentUi $pickerCandidates.Ui -CandidateOptions $pickerCandidates.Options -RuleName "FutureRule"
            if (-not $futureSelection.Success) {
                Write-Fail "Could not select FutureRule from the visible eligible list."
                $passedAll = $false
            }
            if ($futureSelection.Success) {
                $usageSelection = Select-TestRulePickerOption -CurrentUi $futureSelection.Ui -CandidateOptions $pickerCandidates.Options -RuleName "UsageRule"
                if ($usageSelection.Success -and $usageSelection.Label -eq "UsageRule") {
                    Write-Success "UsageRule is selected for the target app grant."
                    $ui = $usageSelection.Ui
                    $canSubmitUsageGrant = $true
                } else {
                    Write-Fail "The picker did not confirm UsageRule selection for the target app grant."
                    $passedAll = $false
                }
            }
        } else {
            Write-Fail "Could not validate the eligible picker set, so no grant will be submitted."
            $passedAll = $false
        }
    } else {
        Write-Fail "The app rule selector is missing from the grant form."
        $passedAll = $false
    }

    Write-Step "7. Entering 15 minutes in Additional Minutes..."
    $nodeInput = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/additional_minutes_input"'
    if ($canSubmitUsageGrant -and $nodeInput.Found) {
        adb shell "input tap $($nodeInput.X) $($nodeInput.Y)"
        Start-Sleep -Milliseconds 500
        adb shell "input text 15"
        Start-Sleep -Seconds 1
        Write-Success "Entered '15' into additional_minutes_input."
    } elseif (-not $nodeInput.Found) {
        Write-Fail "Could not find additional_minutes_input."
        $passedAll = $false
    }

    if ($canSubmitUsageGrant) {
        $ui = Dump-UI
        Write-Step "8. Submitting valid extra time (15 minutes)..."
        $applied = Tap-Node $ui 'resource-id="android:id/button1"' "적용/Apply 버튼" -Optional
        if (-not $applied) {
            $applied = Tap-Node $ui 'text="적용"' "적용 버튼" -Optional
        }
        if (-not $applied) {
            $applied = Tap-Node $ui 'text="Apply"' "Apply button"
        }
        Start-Sleep -Seconds 2
    } else {
        Write-Fail "Skipped the grant because the picker did not confirm UsageRule."
        $passedAll = $false
    }

    Write-Step "9. Verifying Grant Persistence in DataStore..."
    $updatedObj = Get-DeviceSettings -AsObject
    $grants = $updatedObj.appRuleOverrideState.grants
    if ($grants -and $grants.Count -eq 1 -and $grants[0].grantedMillis -eq 900000 -and $grants[0].ruleId -eq $ruleId) {
        Write-Success "DataStore recorded 15 minutes on UsageRule only; NightRule remains unpaid."
    } else {
        Write-Fail "Grant of 15 minutes not found in DataStore settings! Grants: $($grants | ConvertTo-Json -Compress)"
        $passedAll = $false
    }

    $blockingRule = $appRuleSnapshot.appRules | Where-Object { $_.id -eq "blocking-rule" }
    $blockingRule.allowedMinutes = [long]1440
    if (-not (Set-DeviceAppRuleSnapshot -AppRuleSnapshot $appRuleSnapshot)) {
        Write-Fail "Could not remove the temporary eligible blocker before checking NightRule."
        $passedAll = $false
    }
    $settingsAfterSnapshotRefresh = Get-DeviceSettings -AsObject
    $usageGrantAfterRefresh = @($settingsAfterSnapshotRefresh.appRuleOverrideState.grants | Where-Object { $_.ruleId -eq $ruleId })
    if ($usageGrantAfterRefresh.Count -ne 1 -or $usageGrantAfterRefresh[0].grantedMillis -ne 900000) {
        Write-Fail "Refreshing the rule snapshot did not preserve the UsageRule payout."
        $passedAll = $false
    }

    Write-Step "10. Verifying NightRule still blocks the target app after granting UsageRule..."
    adb shell "input keyevent 3" | Out-Null
    Start-TestApp -PackageName $TargetPackage -ActivityName $TargetActivity
    $blockingFocus = $null
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt 10) {
        $blockingFocus = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity|WarningActivity" -PassThru
        if ($blockingFocus.Success) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($blockingFocus -and $blockingFocus.Success) {
        Write-Success "The target app is still blocked by the unrelated NightRule after UsageRule received extra time."
    } else {
        Write-Fail "The target app was not re-intercepted by NightRule after granting another rule. Focus: $($blockingFocus.RawFocus)"
        $passedAll = $false
    }

    Write-Step "11. Final Result Summary"
    if ($passedAll) {
        Write-Host "`n=========================================================================" -ForegroundColor Green
        Write-Host ">>> [TOTAL EXTRA TIME RESULT: PASS] Extra Time Grant & Unlock verified! <<<" -ForegroundColor Green
        Write-Host "=========================================================================`n" -ForegroundColor Green
    } else {
        Write-Host "`n=========================================================================" -ForegroundColor Red
        Write-Host ">>> [TOTAL EXTRA TIME RESULT: FAIL] Some checks failed. Inspect logs above. <<<" -ForegroundColor Red
        Write-Host "=========================================================================`n" -ForegroundColor Red
        exit 1
    }

} finally {
    Write-Step "12. Cleanup & Restoring original settings.json..."
    Complete-DeviceTest -BackupPath $backupFile -TargetPackages @($TargetPackage) -AccessibilitySettings $accessibilityBackup
}
