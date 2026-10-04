<#
.SYNOPSIS
    Verifies guardian extra-time selection and payout from the Curbox app rule screen.
.DESCRIPTION
    Backs up the device baseline, installs a test PIN and a four-rule snapshot, opens
    the protected app-rule screen, verifies global candidates and blocker-first
    selection, checks rule switching clears both fields, grants time to UsageRule,
    and confirms the unrelated NightRule still blocks the target app.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "",
    [string]$OtherPackage = "com.initialcoms.ridi",
    [string]$Pin = "1234"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice
$accessibilityBackup = Backup-DeviceAccessibilitySettings

$tmpDir = Join-Path $env:TEMP "curbox_main_app_extra_time_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}
$backupFile = Join-Path $tmpDir "settings_backup.json"
$passedAll = $true

function Stop-MainCurboxProcessAndWait([int]$TimeoutSeconds = 10) {
    $processName = "neth.iecal.curbox.debug"
    $initialPid = Get-DeviceProcessPid -ProcessName $processName
    if (-not $initialPid) {
        return [PSCustomObject]@{ Stopped = $true; ProcessId = $null }
    }

    Stop-ServiceProcess -TargetPid $initialPid -ProcessName $processName | Out-Null
    $wait = [System.Diagnostics.Stopwatch]::StartNew()
    while ($wait.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $currentPid = Get-DeviceProcessPid -ProcessName $processName
        if (-not $currentPid -or $currentPid -ne $initialPid) {
            return [PSCustomObject]@{ Stopped = $true; ProcessId = $currentPid }
        }
        Start-Sleep -Milliseconds 500
    }
    return [PSCustomObject]@{ Stopped = $false; ProcessId = $initialPid }
}

try {
    Write-Step "1. Backing up current device settings..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile
    if (-not $rawSettings) { throw "Could not create a settings backup." }
    Write-Success "Baseline settings backed up to $backupFile."

    Set-DeviceAwake $true
    $testStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $deviceTimeInfo = Get-DeviceTimeInfo
    if (-not $deviceTimeInfo) { throw "Could not read device time for the future-window rule fixture." }
    $futureWindowStartMinute = (($deviceTimeInfo.CurrentMinute + 120) % 1440)
    $snapshot = New-GuardianExtraTimePickerAppRuleConfig `
        -TargetPackage $TargetPackage `
        -OtherPackage $OtherPackage `
        -FutureWindowStartMinute $futureWindowStartMinute

    Write-Step "2. Applying the test PIN and app-rule snapshot..."
    if (-not (Set-DeviceGuardianAuthConfig -GuardianAuthConfig (New-GuardianPinAuthConfig -Pin $Pin))) {
        throw "Could not set the test guardian PIN."
    }
    if (-not (Set-DeviceAppRuleSnapshot -AppRuleSnapshot $snapshot)) {
        throw "Could not persist the test AppRuleSnapshot."
    }
    Inject-TestAppRules -AppRuleSnapshot $snapshot -UsageGenerationStartedAtMs $testStartTimeMs
    $readySettings = Get-DeviceSettings -AsObject
    $persistedRules = @($readySettings.appRuleSnapshot.appRules)
    $persistedRuleSummary = $persistedRules | ForEach-Object {
        "{0}:active={1},extraTime={2}" -f $_.id, $_.isActive, $_.guardianExtraTimeAllowed
    }
    Write-Host "[DIAGNOSTIC] Persisted picker rules: $($persistedRuleSummary -join ' | ')" -ForegroundColor DarkGray
    $persistedEligibleRuleIds = @($persistedRules | Where-Object {
        $_.isActive -and $_.guardianExtraTimeAllowed
    } | ForEach-Object { $_.id })
    $expectedEligibleRuleIds = @("blocking-rule", "usage-rule", "future-rule")
    $hasExpectedPickerFixture = ($persistedRules.Count -eq 4 -and
        $expectedEligibleRuleIds.Count -eq $persistedEligibleRuleIds.Count -and
        @($expectedEligibleRuleIds | Where-Object { $persistedEligibleRuleIds -notcontains $_ }).Count -eq 0 -and
        (@($persistedRules | Where-Object { $_.id -eq "night-rule" -and -not $_.guardianExtraTimeAllowed }).Count -eq 1))
    if (-not $hasExpectedPickerFixture) {
        throw "The persisted rule snapshot did not retain the four-rule picker fixture."
    }
    $remainingGrants = @($readySettings.appRuleOverrideState.grants | Where-Object { $_ })
    if ($remainingGrants.Count -gt 0) {
        throw "Test setup did not clear prior app-rule grants."
    }
    Write-Success "Test PIN and four-rule snapshot are active with clean grant state."

    Write-Step "3. Opening the protected app-rule screen in Curbox..."
    $retiredMainProcess = Stop-MainCurboxProcessAndWait
    if (-not $retiredMainProcess.Stopped) {
        throw "The main Curbox process did not retire after the persisted app-rule fixture was written."
    }
    Write-Success "Retired the stale main Curbox task so the protected fragment opens with the persisted fixture."
    Start-TestApp `
        -PackageName "neth.iecal.curbox.debug" `
        -ActivityName "neth.iecal.curbox.ui.activity.FragmentActivity" `
        -IntentArguments "-f 0x10008000 --es fragment app_rule_groups"

    $authUi = Wait-For-UI "guardian_enter_password|Enter guardian password|비밀번호 입력|Password" 8
    if ($authUi -match "guardian_enter_password|Enter guardian password|비밀번호 입력|Password") {
        if (Submit-GuardianPin -PinValue $Pin) {
            Write-Success "Guardian management authentication accepted the test PIN."
        } else {
            Write-Fail "Could not submit the guardian PIN for app-rule access."
            $passedAll = $false
        }
    } else {
        Write-Fail "The app-rule screen did not request guardian authentication."
        $passedAll = $false
    }

    $ui = Wait-For-UI "change_extra_time_button" 10
    if ($ui -match "change_extra_time_button") {
        Write-Success "The protected app-rule screen opened after authentication."
    } else {
        Write-Fail "The protected app-rule screen did not open."
        $passedAll = $false
    }

    Write-Step "4. Opening app-internal extra-time entry..."
    $opened = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/change_extra_time_button"' "앱 규칙 추가 시간 버튼" -Optional
    if (-not $opened) {
        $opened = Tap-Node $ui 'text="추가 시간 변경"' "앱 규칙 추가 시간 버튼" -Optional
    }
    if (-not $opened) {
        $opened = Tap-Node $ui 'text="Change extra time"' "Change extra time button"
    }

    $ui = Wait-For-UI "current_total" 8
    $hasPicker = $ui -match "rule_picker"
    $defaultIsBlocker = ($ui -match 'rule_picker[^>]*text="BlockingRule"' -or $ui -match 'text="BlockingRule"[^>]*rule_picker')
    if ($hasPicker -and $defaultIsBlocker) {
        Write-Success "The blocker-first eligible rule is selected by default in the Curbox app."
    } else {
        Write-Fail "The Curbox app did not select the eligible blocking rule by default."
        $passedAll = $false
    }

    Write-Step "5. Verifying global candidates and rule-switch input reset..."
    $pickerNode = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/rule_picker"'
    $pickerCandidates = $null
    $hasExpectedPickerCandidates = $false
    if ($pickerNode.Found) {
        $pickerCandidates = Get-TestRulePickerOptions -CurrentUi $ui -CandidateCount 3
        $candidateLabels = @($pickerCandidates.Options | ForEach-Object { $_.Label })
        $expectedCandidateLabels = @("BlockingRule", "UsageRule", "FutureRule")
        $hasExpectedPickerCandidates = ($pickerCandidates.Success -and
            $candidateLabels.Count -eq $expectedCandidateLabels.Count -and
            @($expectedCandidateLabels | Where-Object { $candidateLabels -notcontains $_ }).Count -eq 0 -and
            $candidateLabels -notcontains "NightRule")
        if ($hasExpectedPickerCandidates) {
            Write-Success "The Curbox picker includes another app's future-window rule and excludes the disallowed NightRule."
        } else {
            Write-Fail "The Curbox picker candidate list did not match the eligible global rule set."
            Write-Host "[DIAGNOSTIC] Picker options: $($candidateLabels -join ' | ')" -ForegroundColor DarkYellow
            $passedAll = $false
        }

        if ($hasExpectedPickerCandidates) {
            $usageResetSelection = Select-TestRulePickerOption -CurrentUi $pickerCandidates.Ui -CandidateOptions $pickerCandidates.Options -RuleName "UsageRule"
            if ($usageResetSelection.Success) {
                $ui = $usageResetSelection.Ui
                $additionalNode = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/additional_minutes_input"'
                if ($additionalNode.Found) {
                    adb shell "input tap $($additionalNode.X) $($additionalNode.Y)" | Out-Null
                    adb shell "input text 15" | Out-Null
                    $ui = Dump-UI
                    $futureSelection = Select-TestRulePickerOption -CurrentUi $ui -CandidateOptions $pickerCandidates.Options -RuleName "FutureRule"
                    if ($futureSelection.Success) {
                        $resetUi = $futureSelection.Ui
                        try {
                            [xml]$resetDoc = $resetUi
                            $resetAdditional = $resetDoc.SelectSingleNode("//node[@resource-id='neth.iecal.curbox.debug:id/additional_minutes_input']")
                            $resetTotal = $resetDoc.SelectSingleNode("//node[@resource-id='neth.iecal.curbox.debug:id/total_minutes_input']")
                            $inputsReset = ($resetAdditional -and $resetTotal -and $resetAdditional.GetAttribute("text") -ne "15" -and $resetTotal.GetAttribute("text") -ne "15")
                        } catch {
                            $inputsReset = $false
                        }
                        if ($inputsReset) {
                            Write-Success "Changing to FutureRule cleared both inputs from UsageRule."
                        } else {
                            Write-Fail "Changing rules retained a stale UsageRule input value."
                            $passedAll = $false
                        }
                        $ui = $resetUi
                    } else {
                        Write-Fail "Could not switch to FutureRule after entering a value."
                        $passedAll = $false
                    }
                } else {
                    Write-Fail "Could not find the app-internal additional-time input."
                    $passedAll = $false
                }
            } else {
                Write-Fail "Could not select UsageRule for the app-internal input reset check."
                $passedAll = $false
            }
        }
    } else {
        Write-Fail "The app-internal rule selector is missing."
        $passedAll = $false
    }

    Write-Step "6. Granting time to UsageRule and checking the unrelated block..."
    $usageSelection = $null
    $canSubmitUsageGrant = $false
    if ($hasExpectedPickerCandidates) {
        $usageSelection = Select-TestRulePickerOption -CurrentUi $ui -CandidateOptions $pickerCandidates.Options -RuleName "UsageRule"
    }
    if ($usageSelection -and $usageSelection.Success -and $usageSelection.Label -eq "UsageRule") {
        $ui = $usageSelection.Ui
        $additionalNode = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/additional_minutes_input"'
        if ($additionalNode.Found) {
            adb shell "input tap $($additionalNode.X) $($additionalNode.Y)" | Out-Null
            adb shell "input text 15" | Out-Null
            $canSubmitUsageGrant = $true
        } else {
            Write-Fail "Could not find the additional-time input for the payout."
            $passedAll = $false
        }
    } else {
        Write-Fail "Could not select and verify UsageRule for the app-internal payout."
        $passedAll = $false
    }

    if ($canSubmitUsageGrant) {
        $ui = Dump-UI
        $selectedBeforeSubmit = Get-TestRulePickerLabel -Xml $ui
        if ($selectedBeforeSubmit -ne "UsageRule") {
            Write-Fail "The app-internal form no longer shows UsageRule; refusing to submit the grant."
            $canSubmitUsageGrant = $false
            $passedAll = $false
        }
    }
    if ($canSubmitUsageGrant) {
        $applied = Tap-Node $ui 'resource-id="android:id/button1"' "적용/Apply 버튼" -Optional
        if (-not $applied) { $applied = Tap-Node $ui 'text="적용"' "적용 버튼" -Optional }
        if (-not $applied) { $applied = Tap-Node $ui 'text="Apply"' "Apply button" }
        Start-Sleep -Seconds 2
    } else {
        Write-Fail "Skipped the app-internal grant because UsageRule was not confirmed in the selector."
    }

    $updatedSettings = Get-DeviceSettings -AsObject
    $usageGrant = @($updatedSettings.appRuleOverrideState.grants | Where-Object { $_.ruleId -eq "usage-rule" })
    if ($usageGrant.Count -eq 1 -and $usageGrant[0].grantedMillis -eq 900000) {
        Write-Success "Curbox saved exactly 15 minutes to UsageRule."
    } else {
        Write-Fail "Curbox did not save the expected UsageRule grant."
        $passedAll = $false
    }

    $blockingRule = $snapshot.appRules | Where-Object { $_.id -eq "blocking-rule" }
    $blockingRule.allowedMinutes = [long]1440
    if (-not (Set-DeviceAppRuleSnapshot -AppRuleSnapshot $snapshot)) {
        Write-Fail "Could not remove the temporary eligible blocker before checking NightRule."
        $passedAll = $false
    }
    $settingsAfterSnapshotRefresh = Get-DeviceSettings -AsObject
    $usageGrantAfterRefresh = @($settingsAfterSnapshotRefresh.appRuleOverrideState.grants | Where-Object { $_.ruleId -eq "usage-rule" })
    if ($usageGrantAfterRefresh.Count -ne 1 -or $usageGrantAfterRefresh[0].grantedMillis -ne 900000) {
        Write-Fail "Refreshing the rule snapshot did not preserve the UsageRule payout."
        $passedAll = $false
    }

    adb shell "input keyevent 3" | Out-Null
    Start-TestApp -PackageName $TargetPackage -ActivityName $TargetActivity
    $blockingFocus = $null
    $focusTimer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($focusTimer.Elapsed.TotalSeconds -lt 10) {
        $blockingFocus = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity|WarningActivity" -PassThru
        if ($blockingFocus.Success) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($blockingFocus -and $blockingFocus.Success) {
        Write-Success "NightRule still blocks the target after the app-internal UsageRule payout."
    } else {
        Write-Fail "The target was not blocked after the app-internal payout. Focus: $($blockingFocus.RawFocus)"
        $passedAll = $false
    }

    if ($passedAll) {
        Write-Host "`n>>> [TOTAL MAIN APP EXTRA TIME RESULT: PASS] App-internal rule selection and payout verified. <<<`n" -ForegroundColor Green
    } else {
        Write-Host "`n>>> [TOTAL MAIN APP EXTRA TIME RESULT: FAIL] Checks failed. Inspect logs above. <<<`n" -ForegroundColor Red
        exit 1
    }
} finally {
    Write-Step "7. Restoring settings, accessibility state, and target apps..."
    Complete-DeviceTest -BackupPath $backupFile -TargetPackages @($TargetPackage) -AccessibilitySettings $accessibilityBackup
}
