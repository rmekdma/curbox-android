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
$packageName = "neth.iecal.curbox.debug"
$passedAll = $true

function New-GuardianApprovalRuleSnapshot(
    [string]$TargetPackage,
    [string]$GroupId,
    [string]$UsageRuleId,
    [string]$NightRuleId = "",
    [int]$NightStartMinute = 0,
    [int]$NightEndMinute = 0
) {
    $targetGroup = New-TestAppGroup `
        -GroupId $GroupId `
        -GroupName "Guardian approval target" `
        -Packages @($TargetPackage)
    $usageRule = [PSCustomObject]@{
        id = $UsageRuleId
        name = "UsageRule"
        isActive = $true
        weekdays = @(0, 1, 2, 3, 4, 5, 6)
        startMinute = 0
        endMinute = 0
        appGroupId = $GroupId
        allowedMinutes = [long]0
        usageConditionEnabled = $false
        usageConditionMinutes = [long]0
        contributorGroupConditionMinutes = [PSCustomObject]@{}
        contributorGroupIds = @()
        earnedAllowanceEnabled = $false
        rolloverEnabled = $false
        unlockDays = @()
        guardianExtraTimeAllowed = $true
        timeRanges = @([PSCustomObject]@{ startMinute = 0; endMinute = 0 })
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($GroupId)
            excludedGroupIds = @()
        }
    }
    $rules = @($usageRule)
    if (-not [string]::IsNullOrWhiteSpace($NightRuleId)) {
        $rules += [PSCustomObject]@{
            id = $NightRuleId
            name = "NightRule"
            isActive = $true
            weekdays = @(0, 1, 2, 3, 4, 5, 6)
            startMinute = 0
            endMinute = 0
            appGroupId = $GroupId
            allowedMinutes = [long]0
            usageConditionEnabled = $false
            usageConditionMinutes = [long]0
            contributorGroupConditionMinutes = [PSCustomObject]@{}
            contributorGroupIds = @()
            earnedAllowanceEnabled = $false
            rolloverEnabled = $false
            unlockDays = @()
            guardianExtraTimeAllowed = $false
            timeRanges = @(
                [PSCustomObject]@{
                    startMinute = $NightStartMinute
                    endMinute = $NightEndMinute
                }
            )
            scope = [PSCustomObject]@{
                includeAllApps = $true
                includedGroupIds = @()
                excludedGroupIds = @()
            }
        }
    }
    return [PSCustomObject]@{
        appGroups = @($targetGroup)
        appRules = $rules
    }
}

function Invoke-DirectGuardianGrant([string]$RuleName, [int]$CandidateCount) {
    $ui = Wait-For-UI "approval_add_time|Change extra time|추가 시간 변경" 8
    $clicked = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_add_time"' "Change extra time" -Optional
    if (-not $clicked) { $clicked = Tap-Node $ui 'text="추가 시간 변경"' "추가 시간" -Optional }
    if (-not $clicked) { $clicked = Tap-Node $ui 'text="Change extra time"' "Change extra time" }
    if (-not $clicked) {
        Write-Fail "Could not open the direct grant form."
        return $false
    }

    $ui = Wait-For-UI "current_total" 6
    $candidates = Get-TestRulePickerOptions -CurrentUi $ui -CandidateCount $CandidateCount
    if (-not $candidates.Success) {
        Write-Fail "Could not inspect the $RuleName grant candidates."
        return $false
    }
    $selection = Select-TestRulePickerOption `
        -CurrentUi $candidates.Ui `
        -CandidateOptions $candidates.Options `
        -RuleName $RuleName
    if (-not $selection.Success) {
        Write-Fail "Could not select $RuleName for the direct grant."
        return $false
    }

    $inputNode = Get-NodeBounds $selection.Ui 'resource-id="neth.iecal.curbox.debug:id/additional_minutes_input"'
    if (-not $inputNode.Found) {
        Write-Fail "Could not find the direct grant minutes input."
        return $false
    }
    Invoke-TestDeviceShell -Command "input tap $($inputNode.X) $($inputNode.Y)"
    Start-Sleep -Milliseconds 250
    Invoke-TestDeviceShell -Command "input text 15"
    $ui = Dump-UI
    $applied = Tap-Node $ui 'resource-id="android:id/button1"' "Apply" -Optional
    if (-not $applied) { $applied = Tap-Node $ui 'text="적용"' "적용" -Optional }
    if (-not $applied) { $applied = Tap-Node $ui 'text="Apply"' "Apply" }
    if (-not $applied) { return $false }
    Start-Sleep -Milliseconds 500
    return $true
}

function Send-GuardianTestBroadcast([string]$Action, [string]$ExtraArguments = "") {
    $command = "am broadcast -a $Action -p $packageName"
    if ($ExtraArguments) { $command += " $ExtraArguments" }
    Invoke-TestDeviceShell -Command $command | Out-Null
}

function Select-GuardianApprovalRule([string]$RuleId) {
    Send-GuardianTestBroadcast `
        -Action "neth.iecal.curbox.guardian.TEST_SELECT_APPROVAL_RULE" `
        -ExtraArguments "--es guardian_test_rule_id $RuleId"
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 5) {
        $logs = Get-TestDeviceShellOutput -Command "logcat -d -s GuardianApprovalE2E:I"
        if ($logs -match "test_rule_selection instance=\d+ screen=[^ ]+ rule=$([regex]::Escape($RuleId)) accepted=true selected=$([regex]::Escape($RuleId))") {
            return $true
        }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

function Start-NightApprovalCase(
    $Fixture,
    $RolloverState,
    [string]$UsageRuleId,
    [long]$GenerationStartedAtMs
) {
    Invoke-TestDeviceShell -Command "input keyevent 3"
    Start-Sleep -Milliseconds 800
    Inject-TestAppRules `
        -AppRuleSnapshot $Fixture `
        -AppRuleRolloverState $RolloverState `
        -UsageGenerationStartedAtMs $GenerationStartedAtMs
    Invoke-TestDeviceShell -Command "logcat -c"
    Start-TestApp -PackageName $TargetPackage -ActivityName $TargetActivity

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $activityLine = ""
    $focus = $null
    while ($watch.Elapsed.TotalSeconds -lt 15) {
        $logs = Get-TestDeviceShellOutput -Command "logcat -d -s GuardianApprovalE2E:I"
        $activityLine = @($logs -split "`r?`n" | Where-Object { $_ -match "activity_created .*denials=" } | Select-Object -Last 1)
        $focus = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        if ($activityLine -and $focus.Success) { break }
        Start-Sleep -Milliseconds 300
    }
    if (-not $activityLine -or -not $focus -or -not $focus.Success -or
        $activityLine -notmatch "NightRule" -or $activityLine -notmatch "UsageRule") {
        throw "The active NightRule and UsageRule did not both reach GuardianApprovalActivity. Activity log: $activityLine; focus: $($focus.RawFocus)"
    }
    if (-not (Select-GuardianApprovalRule -RuleId $UsageRuleId)) {
        throw "GuardianApprovalActivity did not select UsageRule for the approval test."
    }
}

function Invoke-AccumulatedGuardianApproval([int]$ExpectedMinutes = 15) {
    $ui = Wait-For-UI "approval_use_accumulated_time|Use accumulated time|누적 시간 사용" 8
    $tapped = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_use_accumulated_time"' "Use accumulated time" -Optional
    if (-not $tapped) { $tapped = Tap-Node $ui 'text="누적 시간 사용"' "누적 시간 사용" -Optional }
    if (-not $tapped) { $tapped = Tap-Node $ui 'text="Use accumulated time"' "Use accumulated time" -Optional }
    if (-not $tapped) { return $false }

    $dialogUi = Wait-For-UI "accumulated_minutes_input|accumulated_total_desc" 8
    $inputNode = Get-NodeBounds $dialogUi 'resource-id="neth.iecal.curbox.debug:id/accumulated_minutes_input"'
    if (-not $inputNode.Found) { return $false }
    $prefilled = $dialogUi -match ('resource-id="neth.iecal.curbox.debug:id/accumulated_minutes_input"[^>]*text="' + $ExpectedMinutes + '"') -or
        $dialogUi -match ('text="' + $ExpectedMinutes + '"[^>]*resource-id="neth.iecal.curbox.debug:id/accumulated_minutes_input"')
    if (-not $prefilled) { return $false }
    $applied = Tap-Node $dialogUi 'resource-id="android:id/button1"' "Apply accumulated time" -Optional
    if (-not $applied) { $applied = Tap-Node $dialogUi 'text="적용"' "적용" -Optional }
    if (-not $applied) { $applied = Tap-Node $dialogUi 'text="Apply"' "Apply" -Optional }
    return $applied
}

function Invoke-GuardianSkip([string]$RuleId) {
    if (-not (Select-GuardianApprovalRule -RuleId $RuleId)) { return $false }
    $ui = Wait-For-UI "approval_skip_rule|Skip this rule|규칙 건너뛰기" 8
    $tapped = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_skip_rule"' "Skip this rule" -Optional
    if (-not $tapped) { $tapped = Tap-Node $ui 'text="규칙 건너뛰기"' "규칙 건너뛰기" -Optional }
    if (-not $tapped) { $tapped = Tap-Node $ui 'text="Skip this rule"' "Skip this rule" -Optional }
    if (-not $tapped) { return $false }

    $dialogUi = Wait-For-UI "Skip for 15 minutes|guardian_skip_15_minutes" 8
    $tapped = Tap-Node $dialogUi 'text="Skip for 15 minutes"' "Skip for 15 minutes" -Optional
    if (-not $tapped) {
        $tapped = Tap-Node $dialogUi 'text="15분 동안 건너뛰기"' "15분 동안 건너뛰기" -Optional
    }
    if (-not $tapped) {
        $tapped = Tap-Node $dialogUi 'resource-id="android:id/text1"' "Skip for 15 minutes" -Optional
    }
    return $tapped
}

function Wait-ForGuardianNightDenial([string]$ExpectedKind, [string]$UsageRuleId) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $serviceCheckSeen = $false
    $requestSeen = $false
    $remainingLine = ""
    $focus = $null
    while ($watch.Elapsed.TotalSeconds -lt 15) {
        $logs = Get-TestDeviceShellOutput -Command "logcat -d -s GuardianApprovalE2E:I"
        $serviceCheckSeen = $logs -match "service_check_received action=neth\.iecal\.curbox\.guardian\.approval\.stored .*receipt_valid=true"
        $requestSeen = $logs -match "check_request test=- action=neth\.iecal\.curbox\.guardian\.approval\.stored .*kind=$([regex]::Escape($ExpectedKind)) rule=$([regex]::Escape($UsageRuleId))"
        $remainingLine = @($logs -split "`r?`n" | Where-Object { $_ -match "ui_state status=remaining" } | Select-Object -Last 1)
        $focus = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        if ($serviceCheckSeen -and $requestSeen -and $remainingLine -and
            $remainingLine -match "denials=NightRule" -and
            $remainingLine -notmatch "UsageRule" -and $focus.Success) {
            return $true
        }
        Start-Sleep -Milliseconds 300
    }
    Write-Host "[DIAGNOSTIC] serviceCheck=$serviceCheckSeen typedRequest=$requestSeen remaining='$remainingLine' focus='$($focus.RawFocus)'" -ForegroundColor DarkYellow
    return $false
}

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

    Write-Step "11. Preparing direct, accumulated, and skip checks during one active NightRule interval..."
    Invoke-TestDeviceShell -Command "input keyevent 3"
    Start-Sleep -Seconds 1
    $directCaseTime = Get-DeviceTimeInfo
    if (-not $directCaseTime) { throw "Could not read device time for the active NightRule regression." }
    if ($directCaseTime.Second -ge 45) {
        Start-Sleep -Seconds ($directCaseTime.SecondsUntilNextMinute + 2)
        $directCaseTime = Get-DeviceTimeInfo
        if (-not $directCaseTime) { throw "Could not reread device time after minute rollover." }
    }
    $nightStartMinute = ($directCaseTime.CurrentMinute + 1430) % 1440
    $nightEndMinute = ($nightStartMinute + 180) % 1440
    $nightRuleId = "night-direct-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $usageRuleId = "usage-direct-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $groupId = "direct-target-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $nightFixture = New-GuardianApprovalRuleSnapshot `
        -TargetPackage $TargetPackage `
        -GroupId $groupId `
        -UsageRuleId $usageRuleId `
        -NightRuleId $nightRuleId `
        -NightStartMinute $nightStartMinute `
        -NightEndMinute $nightEndMinute
    $nightActiveMinutes = ($directCaseTime.CurrentMinute - $nightStartMinute + 1440) % 1440
    if ($nightActiveMinutes -ge 180) {
        throw "The device clock is outside the NightRule interval prepared for this test."
    }
    $emptyRollover = [PSCustomObject]@{ pools = [PSCustomObject]@{} }
    $directCaseStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Start-NightApprovalCase `
        -Fixture $nightFixture `
        -RolloverState $emptyRollover `
        -UsageRuleId $usageRuleId `
        -GenerationStartedAtMs $directCaseStartTimeMs

    Write-Step "12. Confirming NightRule and UsageRule both deny before the direct grant..."
    Write-Success "The active NightRule and UsageRule denials both appear before any grant is saved."
    $beforeDirectSettings = Get-DeviceSettings -AsObject
    $preexistingDirectGrants = @(
        $beforeDirectSettings.appRuleOverrideState.grants |
            Where-Object { $_.ruleId -eq $usageRuleId }
    )
    if ($preexistingDirectGrants.Count -ne 0) {
        Write-Fail "The UsageRule already had a grant before this independent regression case."
        $passedAll = $false
    }

    Write-Step "13. Paying only UsageRule while NightRule stays active..."
    if (Invoke-DirectGuardianGrant -RuleName "UsageRule" -CandidateCount 1) {
        Write-Success "Saved a direct grant for UsageRule without changing NightRule."
    } else {
        Write-Fail "Could not save the direct UsageRule grant."
        $passedAll = $false
    }
    if (Wait-ForGuardianNightDenial -ExpectedKind "direct_grant" -UsageRuleId $usageRuleId) {
        Write-Success "The direct receipt reached the service; the same screen shows only the latest NightRule denial."
    } else {
        Write-Fail "The direct confirmation did not retain the guardian screen with only NightRule remaining."
        $passedAll = $false
    }
    $nightCaseSettings = Get-DeviceSettings -AsObject
    $nightCaseGrants = @(
        $nightCaseSettings.appRuleOverrideState.grants |
            Where-Object { $_.ruleId -eq $usageRuleId }
    )
    if ($nightCaseGrants.Count -eq 1 -and $nightCaseGrants[0].grantedMillis -eq 900000 -and
        -not [bool]$nightCaseGrants[0].isFromAccumulatedPool) {
        Write-Success "The direct grant belongs only to UsageRule; no rule snapshot refresh was used."
    } else {
        Write-Fail "The minimal regression fixture did not persist exactly one 15 minute UsageRule grant."
        $passedAll = $false
    }

    Write-Step "14. Granting accumulated time to UsageRule while the same NightRule remains active..."
    $accumulatedRuleId = "usage-accumulated-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $accumulatedNightRuleId = "night-accumulated-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $accumulatedGroupId = "accumulated-target-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $accumulatedFixture = New-GuardianApprovalRuleSnapshot `
        -TargetPackage $TargetPackage `
        -GroupId $accumulatedGroupId `
        -UsageRuleId $accumulatedRuleId `
        -NightRuleId $accumulatedNightRuleId `
        -NightStartMinute $nightStartMinute `
        -NightEndMinute $nightEndMinute
    $accumulatedRule = @($accumulatedFixture.appRules | Where-Object { $_.id -eq $accumulatedRuleId })[0]
    $accumulatedRule.rolloverEnabled = $true
    $accumulatedRule.unlockDays = @(0, 1, 2, 3, 4, 5, 6)
    $accumulatedMinutes = 15
    $accumulatedRollover = [PSCustomObject]@{
        pools = [PSCustomObject]@{
            $accumulatedRuleId = (New-RuleRolloverPool `
                -RuleId $accumulatedRuleId `
                -AccumulatedMinutes $accumulatedMinutes)
        }
    }
    $accumulatedStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Start-NightApprovalCase `
        -Fixture $accumulatedFixture `
        -RolloverState $accumulatedRollover `
        -UsageRuleId $accumulatedRuleId `
        -GenerationStartedAtMs $accumulatedStartTimeMs
    $accumulatedBefore = Get-DeviceSettings -AsObject
    $accumulatedPoolBefore = Get-RuleRolloverPool -SettingsOrRolloverState $accumulatedBefore -RuleId $accumulatedRuleId
    if ([long]$accumulatedPoolBefore.accumulatedMinutes -ne $accumulatedMinutes) {
        Write-Fail "The accumulated test pool was not present before approval."
        $passedAll = $false
    }
    if (Invoke-AccumulatedGuardianApproval -ExpectedMinutes $accumulatedMinutes) {
        Write-Success "Submitted the accumulated pool through the guardian approval screen."
    } else {
        Write-Fail "Could not submit the accumulated time approval."
        $passedAll = $false
    }
    if (Wait-ForGuardianNightDenial -ExpectedKind "accumulated_grant" -UsageRuleId $accumulatedRuleId) {
        Write-Success "The accumulated receipt reached the service and NightRule remained the only denial."
    } else {
        Write-Fail "The accumulated approval did not keep NightRule on the guardian screen."
        $passedAll = $false
    }
    $accumulatedAfter = Get-DeviceSettings -AsObject
    $accumulatedGrants = @(
        $accumulatedAfter.appRuleOverrideState.grants |
            Where-Object { $_.ruleId -eq $accumulatedRuleId }
    )
    $accumulatedPoolAfter = Get-RuleRolloverPool -SettingsOrRolloverState $accumulatedAfter -RuleId $accumulatedRuleId
    if ($accumulatedGrants.Count -eq 1 -and
        $accumulatedGrants[0].grantedMillis -eq ($accumulatedMinutes * 60000) -and
        [bool]$accumulatedGrants[0].isFromAccumulatedPool -and
        [long]$accumulatedPoolAfter.accumulatedMinutes -eq 0) {
        Write-Success "The pool paid exactly one grant to UsageRule and its balance is zero."
    } else {
        Write-Fail "The accumulated ledger or pool balance was wrong after confirmation."
        $passedAll = $false
    }

    Write-Step "15. Skipping UsageRule while the same NightRule remains active..."
    $skipRuleId = "usage-skip-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $skipNightRuleId = "night-skip-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $skipGroupId = "skip-target-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $skipFixture = New-GuardianApprovalRuleSnapshot `
        -TargetPackage $TargetPackage `
        -GroupId $skipGroupId `
        -UsageRuleId $skipRuleId `
        -NightRuleId $skipNightRuleId `
        -NightStartMinute $nightStartMinute `
        -NightEndMinute $nightEndMinute
    $skipStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Start-NightApprovalCase `
        -Fixture $skipFixture `
        -RolloverState $emptyRollover `
        -UsageRuleId $skipRuleId `
        -GenerationStartedAtMs $skipStartTimeMs
    if (Invoke-GuardianSkip -RuleId $skipRuleId) {
        Write-Success "Submitted a 15 minute skip for UsageRule."
    } else {
        Write-Fail "Could not submit the UsageRule skip."
        $passedAll = $false
    }
    if (Wait-ForGuardianNightDenial -ExpectedKind "rule_skip" -UsageRuleId $skipRuleId) {
        Write-Success "The skip receipt reached the service and NightRule remained the only denial."
    } else {
        Write-Fail "Skipping UsageRule did not keep NightRule on the guardian screen."
        $passedAll = $false
    }
    $skipAfter = Get-DeviceSettings -AsObject
    $skipCheckTimeMs = Get-TestDeviceEpochTimeMs
    if ($null -eq $skipCheckTimeMs) {
        throw "Could not read device epoch milliseconds to verify the skip ledger."
    }
    $minimumSkipUntilMs = $skipCheckTimeMs
    $skipRecorded = Test-AppRuleSkip `
        -OverrideState $skipAfter.appRuleOverrideState `
        -RuleId $skipRuleId `
        -MinSkipUntilMs $minimumSkipUntilMs
    $skipRecords = @($skipAfter.appRuleOverrideState.skips | Where-Object { $_.ruleId -eq $skipRuleId })
    $skipIntervalIsActive = $skipRecords.Count -eq 1 -and
        [long]$skipRecords[0].skipFromMs -le $skipCheckTimeMs -and
        [long]$skipRecords[0].skipUntilMs -gt $skipCheckTimeMs
    $skipGrantCount = @($skipAfter.appRuleOverrideState.grants).Count
    if ($skipRecorded -and $skipIntervalIsActive -and $skipGrantCount -eq 0) {
        Write-Success "The skip ledger contains only the UsageRule interval; no grant was written."
    } else {
        $skipLedger = $skipRecords | ConvertTo-Json -Depth 5 -Compress
        Write-Fail "The skip ledger did not contain the expected active UsageRule skip. recorded=$skipRecorded active=$skipIntervalIsActive grants=$skipGrantCount expectedRule=$skipRuleId currentDeviceTimeMs=$skipCheckTimeMs ledger=$skipLedger"
        $passedAll = $false
    }

    Write-Step "16. Checking the separate all-allow launch path after a real UsageRule denial..."
    Invoke-TestDeviceShell -Command "input keyevent 3"
    Start-Sleep -Seconds 1
    $controlRuleId = "usage-control-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $controlGroupId = "control-target-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $allAllowFixture = New-GuardianApprovalRuleSnapshot `
        -TargetPackage $TargetPackage `
        -GroupId $controlGroupId `
        -UsageRuleId $controlRuleId
    $allAllowStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Inject-TestAppRules `
        -AppRuleSnapshot $allAllowFixture `
        -AppRuleRolloverState $emptyRollover `
        -UsageGenerationStartedAtMs $allAllowStartTimeMs
    Invoke-TestDeviceShell -Command "logcat -c"
    Invoke-TestDeviceShell -Command "am force-stop $TargetPackage"
    Start-TestApp -PackageName $TargetPackage -ActivityName $TargetActivity
    $controlActivityWatch = [System.Diagnostics.Stopwatch]::StartNew()
    $controlActivityLine = ""
    $usageOnlyFocus = $null
    while ($controlActivityWatch.Elapsed.TotalSeconds -lt 15) {
        $logs = Get-TestDeviceShellOutput -Command "logcat -d -s GuardianApprovalE2E:I"
        $controlActivityLine = @(
            $logs -split "`r?`n" |
                Where-Object { $_ -match "activity_created .*denials=" } |
                Select-Object -Last 1
        )
        $usageOnlyFocus = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        if ($controlActivityLine -and $usageOnlyFocus.Success) { break }
        Start-Sleep -Milliseconds 300
    }
    $controlCaseReady = $controlActivityLine -and
        $controlActivityLine -match "denials=UsageRule" -and
        $usageOnlyFocus -and $usageOnlyFocus.Success
    if ($controlCaseReady) {
        Write-Success "The control case begins with an actual UsageRule denial before saving its grant."
    } else {
        Write-Fail "The all-allow control did not reach GuardianApprovalActivity with a real UsageRule denial. Activity: $controlActivityLine; focus: $($usageOnlyFocus.RawFocus)"
        $passedAll = $false
    }
    $controlGrantSaved = $false
    if ($controlCaseReady -and (Invoke-DirectGuardianGrant -RuleName "UsageRule" -CandidateCount 1)) {
        $controlGrantSaved = $true
        Write-Success "Saved the UsageRule grant in the separate all-allow control."
    } else {
        Write-Fail "Could not save the all-allow control grant."
        $passedAll = $false
    }
    $allowedLaunch = $null
    $allowWatch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($allowWatch.Elapsed.TotalSeconds -lt 15) {
        $allowedLaunch = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if ($allowedLaunch.Success) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not ($allowedLaunch -and $allowedLaunch.Success)) {
        Write-Fail "The all-allow control did not launch the requested app. Focus: $($allowedLaunch.RawFocus)"
        $passedAll = $false
    }
    $allAllowLogs = Get-TestDeviceShellOutput -Command "logcat -d -s GuardianApprovalE2E:I"
    $allAllowReceiptReceived = $allAllowLogs -match "service_check_received action=neth\.iecal\.curbox\.guardian\.approval\.stored .*receipt_valid=true" -and
        $allAllowLogs -match "check_request test=- action=neth\.iecal\.curbox\.guardian\.approval\.stored .*kind=direct_grant rule=$([regex]::Escape($controlRuleId))"
    if ($controlGrantSaved -and $allAllowReceiptReceived -and $allowedLaunch -and $allowedLaunch.Success) {
        Write-Success "After a valid stored receipt, the fresh all-allow evaluation launches the requested app."
    } else {
        Write-Fail "The all-allow control did not complete a stored receipt before the target app was allowed."
        $passedAll = $false
    }
    if ($allAllowReceiptReceived) {
        Write-Success "The all-allow control also used the shared typed receipt path."
    } else {
        Write-Fail "The all-allow launch did not show a valid shared confirmation receipt in both processes."
        $passedAll = $false
    }

    Write-Step "17. Final Result Summary"
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
    Write-Step "18. Cleanup & Restoring original settings.json..."
    Complete-DeviceTest -BackupPath $backupFile -TargetPackages @($TargetPackage) -AccessibilitySettings $accessibilityBackup
}
