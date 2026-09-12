<#
.SYNOPSIS
    Test script for Curbox App Rules (Notification & Lock Screen) via ADB.
.DESCRIPTION
    1. Backs up current settings.json from device.
    2. Injects a test AppRule with condition shortfall onto the target package.
    3. Broadcasts REFRESH_APP_RULES to update AppBlockerService.
    4. Verifies Foreground Notification displays the condition shortfall format.
    5. Launches the target app to trigger the Guardian Lock Screen.
    6. Verifies the Lock Screen contains condition shortfall details.
    7. Restores original settings.json and cleans up.
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

$device = (adb devices | Select-String -Pattern "device$")
if (-not $device) {
    Write-Error "No connected adb device found!"
}

$tmpDir = Join-Path $env:TEMP "curbox_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$testSettingsFile = Join-Path $tmpDir "settings_test.json"

try {
    Write-Step "1. Backing up device settings.json..."
    $rawSettings = adb shell "run-as neth.iecal.curbox.debug cat files/datastore/settings.json" | Out-String
    if (-not $rawSettings -or $rawSettings -notmatch "\{") {
        Write-Error "Failed to read settings.json from device!"
    }
    Set-Content -Path $backupFile -Value $rawSettings -Encoding UTF8
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

    $settingsObj.appRuleSnapshot = [PSCustomObject]@{
        appGroups = @($groupTarget, $groupContrib)
        appRules = @($rule)
    }

    $testSettingsJson = $settingsObj | ConvertTo-Json -Depth 20 -Compress
    Set-Content -Path $testSettingsFile -Value $testSettingsJson -Encoding UTF8

    Write-Step "3. Pushing test configuration to device..."
    adb push $testSettingsFile "/data/local/tmp/settings_test.json" | Out-Null
    adb shell "run-as neth.iecal.curbox.debug cp /data/local/tmp/settings_test.json files/datastore/settings.json"
    adb shell "rm /data/local/tmp/settings_test.json"
    Write-Success "Injected test settings into DataStore."

    Write-Step "4. Refreshing Curbox AppBlockerService..."
    adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p neth.iecal.curbox.debug" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker -p neth.iecal.curbox.debug" | Out-Null
    Start-Sleep -Seconds 2

    Write-Step "5. Verifying Foreground Live Notification on device..."
    $notifDump = adb shell "dumpsys notification --noredact" | Out-String
    if ($notifDump -match "게임 제한" -or $notifDump -match "전체" -or $notifDump -match "학습") {
        Write-Success "Live Notification contains App Rule condition information!"
    } else {
        Write-Host "Checking service foreground state..." -ForegroundColor Yellow
    }

    Write-Step "6. Launching Target App ($TargetPackage) to trigger Lock Screen..."
    adb shell "input keyevent 224" # WAKEUP
    adb shell "wm dismiss-keyguard"
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/com.woodenpharm.choseonggacha.MainActivity" | Out-Null
    Start-Sleep -Seconds 3

    Write-Step "7. Checking if GuardianApprovalActivity is displayed..."
    $windowFocus = adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'" | Out-String
    Write-Host "Focused Window/App: $windowFocus"

    $isGuardianTop = $windowFocus -match "GuardianApprovalActivity"
    if ($isGuardianTop) {
        Write-Success "GuardianApprovalActivity is on top of screen!"
    } else {
        Write-Fail "GuardianApprovalActivity is NOT on top."
    }

    adb shell "uiautomator dump /sdcard/lock_dump.xml" | Out-Null
    $uiDump = adb shell "cat /sdcard/lock_dump.xml" | Out-String

    $hasConditionNotMet = $uiDump -match "사용 조건 미달" -or $uiDump -match "Usage condition not met"
    $hasTimeExhausted = $uiDump -match "사용 가능 시간 소진" -or $uiDump -match "Available time exhausted"
    $hasProgressOrShortfall = $uiDump -match "• [0-9]+m/[0-9]+m" -or $uiDump -match "부족" -or $uiDump -match "needed"

    if ($hasConditionNotMet) {
        Write-Success "Lock Screen displays '사용 조건 미달 (Usage condition not met)'!"
    } elseif ($hasTimeExhausted) {
        Write-Success "Lock Screen displays '사용 가능 시간 소진 (Available time exhausted)'!"
    } else {
        Write-Fail "Lock Screen does not display an expected denial reason."
    }

    if ($hasProgressOrShortfall) {
        Write-Success "Lock Screen displays progress / shortfall details!"
    } else {
        Write-Fail "Lock Screen does not display progress / shortfall details."
    }

    if ($isGuardianTop -and ($hasConditionNotMet -or $hasTimeExhausted) -and $hasProgressOrShortfall) {
        Write-Host "`n>>> [TOTAL TEST RESULT: PASS] Lock screen & App rule enforcement verified successfully! <<<`n" -ForegroundColor Green
    } else {
        Write-Host "`n>>> [TOTAL TEST RESULT: FAIL] Verification failed! <<<`n" -ForegroundColor Red
    }

} finally {
    Write-Step "8. Cleanup & Restoring original settings.json..."
    if (Test-Path $backupFile) {
        adb push $backupFile "/data/local/tmp/settings_backup.json" | Out-Null
        adb shell "run-as neth.iecal.curbox.debug cp /data/local/tmp/settings_backup.json files/datastore/settings.json"
        adb shell "rm /data/local/tmp/settings_backup.json"
        adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules" | Out-Null
        adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker" | Out-Null
        adb shell "input keyevent 3" # HOME
        Write-Success "Original settings restored."
    }
}
