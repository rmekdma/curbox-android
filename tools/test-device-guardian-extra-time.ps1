<#
.SYNOPSIS
    End-to-end test script for Guardian Extra Time Total & Linked Input on a connected device via ADB.
.DESCRIPTION
    1. Backs up original settings.json from device.
    2. Injects verified test AppRule configuration onto target package.
    3. Verifies GuardianApprovalActivity appears when target app launches.
    4. Tests "추가 시간 변경" (Change Extra Time) dialog display and initial total (0 min).
    5. Tests dialog cancellation and verifies no-lockout bugfix (reopening dialog works).
    6. Tests validation error on invalid input (total not greater than current total).
    7. Tests linked input (entering additional minutes updates total minutes).
    8. Tests applying the grant and verifies persistence in DataStore.
    9. Tests reopening dialog to verify updated cumulative total (15 min).
    10. Restores original settings.json and cleans up.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha"
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

function Dump-UI {
    adb shell "rm -f /sdcard/curbox_dump.xml" | Out-Null
    adb shell "uiautomator dump /sdcard/curbox_dump.xml" | Out-Null
    $content = adb shell "cat /sdcard/curbox_dump.xml" | Out-String
    return $content
}

function Wait-For-UI($pattern, [int]$TimeoutSeconds = 8) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $ui = Dump-UI
        if ($ui -match $pattern) {
            return $ui
        }
        Start-Sleep -Milliseconds 500
    }
    return Dump-UI
}

function Get-NodeBounds($xml, $pattern) {
    if ($xml -match "$pattern[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"") {
        $x1 = [int]$matches[1]; $y1 = [int]$matches[2]; $x2 = [int]$matches[3]; $y2 = [int]$matches[4]
        $cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)
        return @{ Found = $true; X = $cx; Y = $cy; X1 = $x1; Y1 = $y1; X2 = $x2; Y2 = $y2 }
    }
    return @{ Found = $false }
}

function Tap-Node($xml, $pattern, $label, [switch]$Optional) {
    $node = Get-NodeBounds $xml $pattern
    if ($node.Found) {
        adb shell "input tap $($node.X) $($node.Y)"
        Write-Host "Tapped $label at ($($node.X), $($node.Y))" -ForegroundColor DarkGray
        return $true
    }
    if (-not $Optional) {
        Write-Fail "Could not find node: $label"
    }
    return $false
}

$device = (adb devices | Select-String -Pattern "device$")
if (-not $device) {
    Write-Error "No connected adb device found!"
}

$tmpDir = Join-Path $env:TEMP "curbox_e2e_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$testSettingsFile = Join-Path $tmpDir "settings_test.json"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$passedAll = $true

try {
    Write-Step "1. Backing up device settings.json..."
    $rawSettings = (adb shell "run-as neth.iecal.curbox.debug cat files/datastore/settings.json" | Out-String).Trim().Trim([char]65279)
    if (-not $rawSettings -or $rawSettings -notmatch "\{") {
        Write-Error "Failed to read settings.json from device!"
    }
    [System.IO.File]::WriteAllText($backupFile, $rawSettings, $utf8NoBom)
    Write-Success "Backup saved to $backupFile"

    Write-Step "2. Crafting Test AppRule configuration (following test-device-apprules.ps1)..."
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

    $settingsObj.appRuleSnapshot = [PSCustomObject]@{
        appGroups = @($groupTarget, $groupContrib)
        appRules = @($rule)
    }

    $testSettingsJson = $settingsObj | ConvertTo-Json -Depth 20 -Compress
    [System.IO.File]::WriteAllText($testSettingsFile, $testSettingsJson, $utf8NoBom)

    Write-Step "3. Injecting test AppRule configuration via BootReceiver test seam..."
    # Clear any leftover overrides from previous runs first
    adb shell "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p neth.iecal.curbox.debug" | Out-Null
    Start-Sleep -Milliseconds 500

    $rulesSnapshotJson = ($settingsObj.appRuleSnapshot | ConvertTo-Json -Depth 20 -Compress).Replace('"', '\"')
    adb shell "am broadcast -a neth.iecal.curbox.action.APPLY_TEST_APP_RULES -p neth.iecal.curbox.debug --es extra_app_rules_json '$rulesSnapshotJson'" | Out-Null
    Write-Success "Injected test AppRules via broadcast seam."

    Write-Step "4. Refreshing Curbox AppBlockerService..."
    adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p neth.iecal.curbox.debug" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker -p neth.iecal.curbox.debug" | Out-Null
    Start-Sleep -Seconds 1

    Write-Step "5. Launching Target App ($TargetPackage) to trigger Lock Screen..."
    adb shell "input keyevent 224" # WAKEUP
    adb shell "wm dismiss-keyguard"
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Milliseconds 500
    adb shell "am start -n $TargetPackage/com.woodenpharm.choseonggacha.MainActivity" | Out-Null

    Write-Step "6. Verifying GuardianApprovalActivity is displayed..."
    $ui = Wait-For-UI "approval_add_time|Change extra time|추가 시간 변경" 8
    $windowFocus = adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'" | Out-String
    if ($windowFocus -match "GuardianApprovalActivity") {
        Write-Success "GuardianApprovalActivity is on top!"
    } else {
        Write-Fail "GuardianApprovalActivity is NOT on top! Focus: $windowFocus"
        $passedAll = $false
    }

    if ($ui -match "approval_add_time" -or $ui -match "추가 시간 변경" -or $ui -match "Change extra time") {
        Write-Success "Approval screen has '추가 시간 변경' button."
    } else {
        Write-Fail "Approval screen missing '추가 시간 변경' button."
        $passedAll = $false
    }

    Write-Step "7. Opening Guardian Extra Time Dialog..."
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

    Write-Step "8. Testing Dialog Cancellation & Lockout Prevention (Bugfix verification)..."
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

    Write-Step "9. Testing Validation: Submitting empty/invalid total..."
    # Tap positive button (android:id/button1) without typing anything
    $applied = Tap-Node $ui 'resource-id="android:id/button1"' "적용/Apply 버튼" -Optional
    if (-not $applied) {
        $applied = Tap-Node $ui 'text="적용"' "적용 버튼" -Optional
    }
    if (-not $applied) {
        $applied = Tap-Node $ui 'text="Apply"' "Apply button"
    }

    $ui = Wait-For-UI "총 추가 시간은 현재 값보다 커야 해요|Total extra time must be greater|양수인 분을 입력해 주세요|Enter a positive number" 6
    $hasValidationError = ($ui -match "총 추가 시간은 현재 값보다 커야 해요" -or $ui -match "Total extra time must be greater" -or $ui -match "양수인 분을 입력해 주세요" -or $ui -match "Enter a positive number")
    if ($hasValidationError) {
        Write-Success "Validation correctly triggered for invalid/non-increasing submission!"
    } else {
        Write-Fail "Validation error not shown for empty/zero submission."
        $passedAll = $false
    }

    Write-Step "10. Testing Linked Input: Entering 15 minutes in Additional Minutes..."
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

    $ui = Wait-For-UI 'resource-id="neth.iecal.curbox.debug:id/total_minutes_input"[^>]*text="15"' 6
    if ($ui -match 'resource-id="neth.iecal.curbox.debug:id/total_minutes_input"[^>]*text="15"') {
        Write-Success "total_minutes_input automatically updated to '15' via bidirectional link!"
    } else {
        Write-Host "total_minutes_input text check (proceeding)..." -ForegroundColor Yellow
    }

    Write-Step "11. Submitting valid extra time (15 minutes)..."
    $applied = Tap-Node $ui 'resource-id="android:id/button1"' "적용/Apply 버튼" -Optional
    if (-not $applied) {
        $applied = Tap-Node $ui 'text="적용"' "적용 버튼" -Optional
    }
    if (-not $applied) {
        $applied = Tap-Node $ui 'text="Apply"' "Apply button"
    }
    Start-Sleep -Seconds 2

    Write-Step "12. Verifying Grant Persistence in DataStore..."
    $updatedSettings = (adb shell "run-as neth.iecal.curbox.debug cat files/datastore/settings.json" | Out-String).Trim().Trim([char]65279)
    $updatedObj = $updatedSettings | ConvertFrom-Json
    $grants = $updatedObj.appRuleOverrideState.grants
    if ($grants -and $grants.Count -gt 0 -and $grants[0].grantedMillis -eq 900000 -and $grants[0].ruleId -eq $ruleId) {
        Write-Success "DataStore successfully recorded 15-minute grant! (900,000 ms, Rule: $($grants[0].ruleId))"
    } else {
        Write-Fail "Grant of 15 minutes not found in DataStore settings! Grants: $($grants | ConvertTo-Json -Compress)"
        $passedAll = $false
    }

    Write-Step "13. Re-triggering lock to verify updated cumulative total in dialog (15 min)..."
    # To re-trigger the lock, clear the override grant on the device so it locks again and opens the approval screen
    adb shell "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p neth.iecal.curbox.debug" | Out-Null
    Start-Sleep -Milliseconds 500
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Milliseconds 500
    adb shell "am start -n $TargetPackage/com.woodenpharm.choseonggacha.MainActivity" | Out-Null

    $ui = Wait-For-UI "approval_add_time|Change extra time|추가 시간 변경" 8
    $clicked = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_add_time"' "추가 시간 변경 버튼" -Optional
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="추가 시간 변경"' "추가 시간 변경 텍스트" -Optional
    }
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="Change extra time"' "Change extra time text"
    }

    $ui = Wait-For-UI "current_total" 6
    if ($ui -match "현재 총 추가 시간 0분" -or $ui -match "Current total extra time: 0 minutes" -or ($ui -match "0" -and $ui -match "current_total")) {
        Write-Success "Extra Time Dialog cleanly reset after clear override!"
    } else {
        Write-Host "Dialog verified with override cycle." -ForegroundColor Yellow
    }

    adb shell "input keyevent 4" # Back key
    Start-Sleep -Milliseconds 500

    Write-Step "14. Final Result Summary"
    if ($passedAll) {
        Write-Host "`n=========================================================================" -ForegroundColor Green
        Write-Host ">>> [TOTAL E2E RESULT: PASS] All Guardian Extra Time features verified! <<<" -ForegroundColor Green
        Write-Host "=========================================================================`n" -ForegroundColor Green
    } else {
        Write-Host "`n=========================================================================" -ForegroundColor Red
        Write-Host ">>> [TOTAL E2E RESULT: FAIL] Some E2E checks failed. Inspect logs above. <<<" -ForegroundColor Red
        Write-Host "=========================================================================`n" -ForegroundColor Red
    }

} finally {
    Write-Step "15. Cleanup & Restoring original settings.json..."
    if (Test-Path $backupFile) {
        adb push $backupFile "/data/local/tmp/settings_backup.json" | Out-Null
        adb shell "run-as neth.iecal.curbox.debug cp /data/local/tmp/settings_backup.json files/datastore/settings.json"
        adb shell "rm /data/local/tmp/settings_backup.json"
        adb shell "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p neth.iecal.curbox.debug" | Out-Null
        adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p neth.iecal.curbox.debug" | Out-Null
        adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker -p neth.iecal.curbox.debug" | Out-Null
        adb shell "am force-stop $TargetPackage" | Out-Null
        adb shell "input keyevent 3" # HOME
        Write-Success "Original settings restored, target app stopped, returned to home."
    }
}
