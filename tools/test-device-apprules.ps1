<#
.SYNOPSIS
    Test script for Curbox App Rules (Notification & Lock Screen) via ADB.
.DESCRIPTION
    1. Backs up current settings.json from device using device-test-common.
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

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

function Write-Step($msg) {
    Write-Host "`n====> $msg" -ForegroundColor Cyan
}

function Write-Success($msg) {
    Write-Host "[PASS] $msg" -ForegroundColor Green
}

function Write-Fail($msg) {
    Write-Host "[FAIL] $msg" -ForegroundColor Red
}

Assert-AdbDevice

$tmpDir = Join-Path $env:TEMP "curbox_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$testSettingsFile = Join-Path $tmpDir "settings_test.json"

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

    $appRuleSnapshot = [PSCustomObject]@{
        appGroups = @($groupTarget, $groupContrib)
        appRules = @($rule)
    }

    $settingsObj.appRuleSnapshot = $appRuleSnapshot

    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    $testSettingsJson = $settingsObj | ConvertTo-Json -Depth 20 -Compress
    [System.IO.File]::WriteAllText($testSettingsFile, $testSettingsJson, $utf8NoBom)

    Write-Step "3. Pushing test configuration to device..."
    Inject-TestAppRules -AppRuleSnapshot $appRuleSnapshot
    Write-Success "Injected test AppRules via broadcast seam."

    Write-Step "4. Refreshing Curbox AppBlockerService..."
    # Inject-TestAppRules already triggers refreshes; additional wait if needed
    Start-Sleep -Seconds 1

    Write-Step "5. Verifying Foreground Live Notification on device..."
    $notifDump = adb shell "dumpsys notification --noredact" | Out-String
    if ($notifDump -match "게임 제한" -or $notifDump -match "전체" -or $notifDump -match "학습") {
        Write-Success "Live Notification contains App Rule condition information!"
    } else {
        Write-Host "Checking service foreground state..." -ForegroundColor Yellow
    }

    Write-Step "6. Launching Target App ($TargetPackage) to trigger Lock Screen..."
    Set-DeviceAwake $true
    adb shell "input keyevent 224" # WAKEUP
    adb shell "wm dismiss-keyguard"
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/com.woodenpharm.choseonggacha.MainActivity" | Out-Null
    Start-Sleep -Seconds 3

    Write-Step "7. Checking if GuardianApprovalActivity is displayed..."
    $focusResult = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity"
    Write-Host "Focused Window/App: $($focusResult.RawFocus)"

    $isGuardianTop = $focusResult.Success
    if ($isGuardianTop) {
        Write-Success "GuardianApprovalActivity is on top of screen!"
    } else {
        Write-Fail "GuardianApprovalActivity is NOT on top."
    }

    $uiDump = Dump-UI

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
        Restore-DeviceSettings -BackupPath $backupFile | Out-Null
        adb shell "input keyevent 3" # HOME
        Write-Success "Original settings restored."
    }
}
