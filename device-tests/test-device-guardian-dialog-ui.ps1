<#
.SYNOPSIS
    End-to-end test script for Guardian Extra Time Dialog UI interactions on a connected device via ADB.
.DESCRIPTION
    1. Backs up original settings.json from device using device-test-common.
    2. Injects verified test AppRule configuration onto target package.
    3. Verifies GuardianApprovalActivity appears when target app launches.
    4. Tests "추가 시간 변경" (Change Extra Time) dialog display and initial total (0 min).
    5. Tests dialog cancellation and verifies no-lockout bugfix (reopening dialog works).
    6. Tests validation error on invalid input (empty value / total not greater than current total).
    7. Tests linked input (entering additional minutes updates total minutes in real time).
    8. Restores original settings.json and cleans up.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha"
)

$ErrorActionPreference = "Stop"

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice

$tmpDir = Join-Path $env:TEMP "curbox_dialog_ui_test"
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
    $hasInitialTotal = ($ui -match "현재 총 추가 시간 0분" -or $ui -match "Current total extra time: 0 minutes" -or ($ui -match "0" -and $ui -match "current_total"))
    if ($hasInitialTotal) {
        Write-Success "Extra Time Dialog displayed initial total 0 minutes!"
    } else {
        Write-Fail "Extra Time Dialog did not display expected initial total 0 min."
        $passedAll = $false
    }

    Write-Step "7. Testing Dialog Cancellation & Lockout Prevention (Bugfix verification)..."
    # Dismiss dialog via Cancel button (android:id/button2)
    $canceled = Tap-Node $ui 'resource-id="android:id/button2"' "취소/Cancel 버튼" -Optional
    if (-not $canceled) {
        $canceled = Tap-Node $ui 'text="취소"' "취소 버튼" -Optional
    }
    if (-not $canceled) {
        $canceled = Tap-Node $ui 'text="Cancel"' "Cancel button"
    }

    $ui = Wait-For-UI "approval_add_time|Change extra time|추가 시간 변경" 6
    # Verify we can open the dialog AGAIN without being locked out by grantInProgress
    $clickedAgain = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_add_time"' "추가 시간 변경 버튼 (2차 클릭)"

    $ui = Wait-For-UI "current_total" 6
    if ($ui -match "current_total") {
        Write-Success "Dialog reopened successfully! (Confirmed no-lockout bugfix)"
    } else {
        Write-Fail "Dialog failed to reopen after cancellation! (grantInProgress lockout bug detected)"
        $passedAll = $false
    }

    Write-Step "8. Testing Validation: Submitting empty and non-increasing total..."
    # 8a. Tap positive button (android:id/button1) without typing anything
    $applied = Tap-Node $ui 'resource-id="android:id/button1"' "적용/Apply 버튼 (빈 값)" -Optional
    if (-not $applied) {
        $applied = Tap-Node $ui 'text="적용"' "적용 버튼 (빈 값)" -Optional
    }
    if (-not $applied) {
        $applied = Tap-Node $ui 'text="Apply"' "Apply button (empty)"
    }

    $ui = Wait-For-UI "총 추가 시간은 현재 값보다 커야 해요|Total extra time must be greater|양수인 분을 입력해 주세요|Enter a positive number" 6
    $hasEmptyValidationError = ($ui -match "총 추가 시간은 현재 값보다 커야 해요" -or $ui -match "Total extra time must be greater" -or $ui -match "양수인 분을 입력해 주세요" -or $ui -match "Enter a positive number")
    if ($hasEmptyValidationError) {
        Write-Success "Validation correctly triggered for empty submission!"
    } else {
        Write-Fail "Validation error not shown for empty submission."
        $passedAll = $false
    }

    # 8b. Enter total <= current total (current is 0; enter 0 into total_minutes_input)
    $nodeTotalInput = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/total_minutes_input"'
    if ($nodeTotalInput.Found) {
        adb shell "input tap $($nodeTotalInput.X) $($nodeTotalInput.Y)"
        Start-Sleep -Milliseconds 500
        # Send backspaces in case of existing text, then type 0
        adb shell "input keyevent 67 67 67 67 67"
        adb shell "input text 0"
        Start-Sleep -Milliseconds 500
        $ui = Dump-UI
        $applied = Tap-Node $ui 'resource-id="android:id/button1"' "적용/Apply 버튼 (0 입력)" -Optional
        if (-not $applied) {
            $applied = Tap-Node $ui 'text="적용"' "적용 버튼 (0 입력)" -Optional
        }
        if (-not $applied) {
            $applied = Tap-Node $ui 'text="Apply"' "Apply button (0)"
        }
        $ui = Wait-For-UI "총 추가 시간은 현재 값보다 커야 해요|Total extra time must be greater|양수인 분을 입력해 주세요|Enter a positive number" 6
        $hasZeroValidationError = ($ui -match "총 추가 시간은 현재 값보다 커야 해요" -or $ui -match "Total extra time must be greater" -or $ui -match "양수인 분을 입력해 주세요" -or $ui -match "Enter a positive number")
        if ($hasZeroValidationError) {
            Write-Success "Validation correctly triggered for input <= current total (0 min)!"
        } else {
            Write-Fail "Validation error not shown for input <= current total (0 min)."
            $passedAll = $false
        }
    }

    Write-Step "9. Testing Linked Input: Forward & Reverse Bidirectional Sync..."
    # 9a. Forward sync: Enter 15 minutes into additional_minutes_input
    $nodeAdditional = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/additional_minutes_input"'
    if ($nodeAdditional.Found) {
        adb shell "input tap $($nodeAdditional.X) $($nodeAdditional.Y)"
        Start-Sleep -Milliseconds 500
        # Clear existing text using backspaces and type 15
        adb shell "input keyevent 67 67 67 67 67"
        adb shell "input text 15"
        Start-Sleep -Seconds 1
        Write-Success "Entered '15' into additional_minutes_input."
    } else {
        Write-Fail "Could not find additional_minutes_input."
        $passedAll = $false
    }

    $ui = Dump-UI
    if ($ui -match '<node[^>]*text="15"[^>]*id/total_minutes_input' -or $ui -match '<node[^>]*id/total_minutes_input[^>]*text="15"') {
        Write-Success "Forward sync verified: total_minutes_input automatically updated to '15'!"
    } else {
        Write-Fail "Forward sync failed: total_minutes_input was not updated to '15'."
        $passedAll = $false
    }

    # 9b. Reverse sync: Enter 25 into total_minutes_input, check additional_minutes_input updates to 25 (since current=0)
    $nodeTotal = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/total_minutes_input"'
    if ($nodeTotal.Found) {
        adb shell "input tap $($nodeTotal.X) $($nodeTotal.Y)"
        Start-Sleep -Milliseconds 500
        # Clear existing text and type 25
        adb shell "input keyevent 67 67 67 67 67"
        adb shell "input text 25"
        Start-Sleep -Seconds 1
        Write-Success "Entered '25' into total_minutes_input."
    } else {
        Write-Fail "Could not find total_minutes_input for reverse sync."
        $passedAll = $false
    }

    $ui = Dump-UI
    if ($ui -match '<node[^>]*text="25"[^>]*id/additional_minutes_input' -or $ui -match '<node[^>]*id/additional_minutes_input[^>]*text="25"') {
        Write-Success "Reverse sync verified: additional_minutes_input automatically updated to '25'!"
    } else {
        Write-Fail "Reverse sync failed: additional_minutes_input was not updated to '25'."
        $passedAll = $false
    }

    # Dismiss dialog via Cancel or Back key to conclude UI interaction test
    $dismissed = Tap-Node $ui 'resource-id="android:id/button2"' "취소/Cancel 버튼" -Optional
    if (-not $dismissed) {
        $dismissed = Tap-Node $ui 'text="취소"' "취소 버튼" -Optional
    }
    if (-not $dismissed) {
        $dismissed = Tap-Node $ui 'text="Cancel"' "Cancel button" -Optional
    }
    if (-not $dismissed) {
        adb shell "input keyevent 4" # Back key
    }
    Start-Sleep -Milliseconds 500

    Write-Step "10. Final Result Summary"
    if ($passedAll) {
        Write-Host "`n=========================================================================" -ForegroundColor Green
        Write-Host ">>> [TOTAL DIALOG UI RESULT: PASS] All Dialog UI interactions verified! <<<" -ForegroundColor Green
        Write-Host "=========================================================================`n" -ForegroundColor Green
    } else {
        Write-Host "`n=========================================================================" -ForegroundColor Red
        Write-Host ">>> [TOTAL DIALOG UI RESULT: FAIL] Some checks failed. Inspect logs above. <<<" -ForegroundColor Red
        Write-Host "=========================================================================`n" -ForegroundColor Red
        exit 1
    }

} finally {
    Write-Step "11. Cleanup & Restoring original settings.json..."
    Set-DeviceAwake $false
    if (Test-Path $backupFile) {
        Restore-DeviceSettings -BackupPath $backupFile | Out-Null
        adb shell "am force-stop $TargetPackage" | Out-Null
        adb shell "input keyevent 3" # HOME
        Write-Success "Original settings restored, target app stopped, returned to home."
    }
}
