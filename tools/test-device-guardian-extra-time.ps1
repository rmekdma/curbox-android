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
    [string]$TargetPackage = "com.woodenpharm.choseonggacha"
)

$ErrorActionPreference = "Stop"

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice

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

    Write-Step "2. Crafting Test AppRule configuration..."
    $settingsObj = $rawSettings | ConvertFrom-Json

    $targetGroupId = "test-target-group-01"
    $contributorGroupId = "test-contrib-group-01"
    $ruleId = "test-rule-01"

    $groupTarget = [PSCustomObject]@{
        id = $targetGroupId
        name = "테스트 타깃 앱"
        selectedPackages = @($TargetPackage)
        membershipHistory = @(
            [PSCustomObject]@{
                effectiveFromMs = [long]-9223372036854775808
                selectedPackages = @($TargetPackage)
            }
        )
    }

    $groupContrib = [PSCustomObject]@{
        id = $contributorGroupId
        name = "학습"
        selectedPackages = @("com.initialcoms.ridi")
        membershipHistory = @(
            [PSCustomObject]@{
                effectiveFromMs = [long]-9223372036854775808
                selectedPackages = @("com.initialcoms.ridi")
            }
        )
    }

    $rule = [PSCustomObject]@{
        id = $ruleId
        name = "게임 제한"
        isActive = $true
        weekdays = @(0, 1, 2, 3, 4, 5, 6)
        startMinute = 0
        endMinute = 0
        appGroupId = $targetGroupId
        allowedMinutes = [long]30
        usageConditionEnabled = $true
        usageConditionMinutes = [long]20
        contributorGroupConditionMinutes = [PSCustomObject]@{
            $contributorGroupId = [long]15
        }
        contributorGroupIds = @($contributorGroupId)
        earnedAllowanceEnabled = $false
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

    # Reset any existing grants/skips for clean test state
    $settingsObj.appRuleOverrideState = [PSCustomObject]@{
        grants = @()
        skips = @()
        useDayGenerationStartedAtMs = [long]0
        useDayId = (Get-Date -Format "yyyy-MM-dd")
    }

    $appRuleSnapshot = [PSCustomObject]@{
        appGroups = @($groupTarget, $groupContrib)
        appRules = @($rule)
    }
    $settingsObj.appRuleSnapshot = $appRuleSnapshot

    Write-Step "3. Injecting test AppRule configuration via broadcast seam..."
    Inject-TestAppRules -AppRuleSnapshot $appRuleSnapshot
    Write-Success "Injected test AppRules via broadcast seam."

    Write-Step "4. Launching Target App ($TargetPackage) to trigger Lock Screen..."
    Set-DeviceAwake $true
    adb shell "input keyevent 224" # WAKEUP
    adb shell "wm dismiss-keyguard"
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/com.woodenpharm.choseonggacha.MainActivity" | Out-Null

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

    Write-Step "7. Entering 15 minutes in Additional Minutes..."
    $nodeInput = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/additional_minutes_input"'
    if ($nodeInput.Found) {
        adb shell "input tap $($nodeInput.X) $($nodeInput.Y)"
        Start-Sleep -Milliseconds 500
        adb shell "input text 15"
        Start-Sleep -Seconds 1
        Write-Success "Entered '15' into additional_minutes_input."
    } else {
        Write-Fail "Could not find additional_minutes_input."
        $passedAll = $false
    }

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

    Write-Step "9. Verifying Grant Persistence in DataStore..."
    $updatedSettings = (adb shell "run-as neth.iecal.curbox.debug cat files/datastore/settings.json" | Out-String).Trim().Trim([char]65279)
    $updatedObj = $updatedSettings | ConvertFrom-Json
    $grants = $updatedObj.appRuleOverrideState.grants
    if ($grants -and $grants.Count -gt 0 -and $grants[0].grantedMillis -eq 900000 -and $grants[0].ruleId -eq $ruleId) {
        Write-Success "DataStore successfully recorded 15-minute grant! (900,000 ms, Rule: $($grants[0].ruleId))"
    } else {
        Write-Fail "Grant of 15 minutes not found in DataStore settings! Grants: $($grants | ConvertTo-Json -Compress)"
        $passedAll = $false
    }

    Write-Step "10. Verifying Target App is now unlocked and on top..."
    $targetFocus = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
    if ($targetFocus.Success) {
        Write-Success "Target app is unlocked and running in foreground!"
    } else {
        # Check if activity is still on top or resuming
        Start-Sleep -Seconds 1
        $targetFocusRetry = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if ($targetFocusRetry.Success) {
            Write-Success "Target app is unlocked and running in foreground!"
        } else {
            Write-Fail "Target app was not unlocked / did not regain focus! Focus: $($targetFocusRetry.RawFocus)"
            $passedAll = $false
        }
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
    Set-DeviceAwake $false
    if (Test-Path $backupFile) {
        Restore-DeviceSettings -BackupPath $backupFile | Out-Null
        adb shell "am force-stop $TargetPackage" | Out-Null
        adb shell "input keyevent 3" # HOME
        Write-Success "Original settings restored, target app stopped, returned to home."
    }
}
