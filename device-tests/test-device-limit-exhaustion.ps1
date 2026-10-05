<#
.SYNOPSIS
    End-to-end test script for real-time Daily Limit Exhaustion on a connected device via ADB.
.DESCRIPTION
    1. Backs up original settings.json from device using device-test-common.
    2. Ensures device is awake (Set-DeviceAwake $true).
    3. Injects test AppRule configuration with a 1-minute daily allowance limit and resets tracking epoch.
    4. Launches target app in advance and verifies it is running unblocked in the foreground.
    5. Accumulates usage for 65~75 seconds while maintaining the app in foreground.
    6. Verifies that upon exhausting the 1-minute allowance, the accessibility service recheck and wake scheduler
       automatically trigger GuardianApprovalActivity in real time without delay.
    7. Verifies that the lock screen UI correctly displays the '허용 시간 초과' (Available time exhausted / 사용 가능 시간 소진) reason.
    8. Restores original settings.json, reverts stay-awake state, and cleans up.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "",
    [int]$AllowedMinutes = 1,
    [int]$MaxWaitSeconds = 95
)

$ErrorActionPreference = "Stop"

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice
$accessibilityBackup = Backup-DeviceAccessibilitySettings

$tmpDir = Join-Path $env:TEMP "curbox_limit_exhaustion_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$passedAll = $true

try {
    Write-Step "0. Ensuring device is awake..."
    Set-DeviceAwake $true
    Start-Sleep -Seconds 1

    Write-Step "1. Backing up device settings.json..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile
    Write-Success "Backup saved to $backupFile"

    Write-Step "1a. Ensuring the accessibility service is bound before measuring usage..."
    Enable-AccessibilityService | Out-Null
    $serviceWait = [System.Diagnostics.Stopwatch]::StartNew()
    $serviceBound = $false
    while ($serviceWait.Elapsed.TotalSeconds -lt 15) {
        if (Test-AccessibilityServiceBound) {
            $serviceBound = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $serviceBound) {
        throw "Curbox AppBlockerService did not bind; daily-limit enforcement cannot be measured."
    }
    Write-Success "Curbox AppBlockerService is bound and ready for the daily-limit test."

    Write-Step "2. Crafting and injecting AppRule with ${AllowedMinutes}-minute Daily Allowance limit..."
    $appRuleSnapshot = New-DailyLimitAppRuleConfig `
        -TargetPackage $TargetPackage `
        -AllowedMinutes $AllowedMinutes `
        -RuleName "일일 허용량 제한"

    $testEpochMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Inject-TestAppRules -AppRuleSnapshot $appRuleSnapshot -UsageGenerationStartedAtMs $testEpochMs
    Write-Success "Test AppRule configuration injected with usage epoch $testEpochMs."

    Write-Step "3. Launching Target App ($TargetPackage) - Expecting Unblocked initially..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Milliseconds 500
    Start-TestApp -PackageName $TargetPackage -ActivityName $TargetActivity

    $launchSw = [System.Diagnostics.Stopwatch]::StartNew()
    $targetFocused = $false
    while ($launchSw.Elapsed.TotalSeconds -lt 8) {
        $focus = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if ($focus.Success -and $focus.RawFocus -notmatch "GuardianApprovalActivity") {
            $targetFocused = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }

    if ($targetFocused) {
        Write-Success "Target app is running unblocked in foreground initially."
    } else {
        Write-Fail "Target app failed to enter foreground or was prematurely blocked. Current focus: $($focus.RawFocus)"
        $passedAll = $false
    }

    Write-Step "4. Accumulating usage and monitoring for real-time Limit Exhaustion interception (up to ${MaxWaitSeconds}s)..."
    Write-Host "Allowed time: ${AllowedMinutes} minute(s). Expecting GuardianApprovalActivity around 60~75 seconds." -ForegroundColor Cyan

    $pollSw = [System.Diagnostics.Stopwatch]::StartNew()
    $intercepted = $false
    $lastFocus = ""

    while ($pollSw.Elapsed.TotalSeconds -lt $MaxWaitSeconds) {
        $focusCheck = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        $lastFocus = $focusCheck.RawFocus
        if ($focusCheck.Success) {
            $intercepted = $true
            $elapsed = [math]::Round($pollSw.Elapsed.TotalSeconds, 1)
            Write-Success "GuardianApprovalActivity successfully intercepted screen after ${elapsed}s! Focus: $lastFocus"

            # Verify that usage accumulation was sustained for at least 55 seconds (lower bound)
            if ($elapsed -lt 55) {
                Write-Fail "Interception occurred prematurely after only ${elapsed}s (< 55s lower bound). Stale usage may have leaked."
                $passedAll = $false
            } else {
                Write-Success "Usage accumulation time verified: ${elapsed}s satisfies >= 55s threshold for 1-minute allowance."
            }
            break
        }

        # Check that target package maintains active foreground while waiting for limit exhaustion
        $targetCheck = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if (-not $targetCheck.Success) {
            Write-Host "Warning: Target app ($TargetPackage) temporarily lost top focus. Current: $($targetCheck.RawFocus)" -ForegroundColor Yellow
        }

        $currentElapsed = [int]$pollSw.Elapsed.TotalSeconds
        if ($currentElapsed % 10 -eq 0 -and $currentElapsed -gt 0) {
            Write-Host "Accumulating usage: ${currentElapsed}s / ${MaxWaitSeconds}s (current focus: $lastFocus)"
            $serviceBoundNow = Test-AccessibilityServiceBound
            $enabledAccessibilityNow = Get-DeviceSecureSetting -Name "enabled_accessibility_services"
            Write-Host "[DIAGNOSTIC] Accessibility service at ${currentElapsed}s: bound=$serviceBoundNow; enabled=$($enabledAccessibilityNow -match 'AppBlockerService')"
        }
        Start-Sleep -Milliseconds 1000
    }

    if ($intercepted) {
        Write-Step "5. Verifying lock reason on GuardianApprovalActivity UI..."
        $lockReasonFound = Wait-For-UI -Pattern "사용 가능 시간 소진|Available time exhausted" -TimeoutSeconds 10
        if ($lockReasonFound) {
            Write-Success "Lock screen correctly displays '허용 시간 초과' ('사용 가능 시간 소진' / 'Available time exhausted') reason!"
        } else {
            $uiDump = Dump-UI
            Write-Fail "Lock screen does not display expected limit exhaustion reason. UI dump preview: $($uiDump.Substring(0, [math]::Min(500, $uiDump.Length)))"
            $passedAll = $false
        }

        $diagnosticScript = Join-Path (Split-Path -Parent $PSScriptRoot) ".scratch\diagnose-limit-ledger.py"
        $pythonPath = "C:\Users\DELL\.pixi\bin\python.exe"
        if ((Test-Path -LiteralPath $diagnosticScript) -and (Test-Path -LiteralPath $pythonPath)) {
            Write-Host "[DIAGNOSTIC] Capturing the successful test rule, service, focus, and ledger before cleanup..." -ForegroundColor Cyan
            $diagnosticTemp = Join-Path (Split-Path -Parent $PSScriptRoot) ".scratch\limit-ledger-probe-temp"
            if (-not (Test-Path -LiteralPath $diagnosticTemp)) {
                New-Item -ItemType Directory -Path $diagnosticTemp | Out-Null
            }
            & $pythonPath $diagnosticScript --serial $env:ANDROID_SERIAL --package $TargetPackage
        }
    } else {
        Write-Fail "GuardianApprovalActivity did NOT intercept within ${MaxWaitSeconds}s. Last focus: $lastFocus"
        $passedAll = $false

        $diagnosticScript = Join-Path (Split-Path -Parent $PSScriptRoot) ".scratch\diagnose-limit-ledger.py"
        $pythonPath = "C:\Users\DELL\.pixi\bin\python.exe"
        if ((Test-Path -LiteralPath $diagnosticScript) -and (Test-Path -LiteralPath $pythonPath)) {
            Write-Host "[DIAGNOSTIC] Capturing the test rule, service, foreground focus, and Chrome session ledger before cleanup..." -ForegroundColor Cyan
            $diagnosticTemp = Join-Path (Split-Path -Parent $PSScriptRoot) ".scratch\limit-ledger-probe-temp"
            if (-not (Test-Path -LiteralPath $diagnosticTemp)) {
                New-Item -ItemType Directory -Path $diagnosticTemp | Out-Null
            }
            & $pythonPath $diagnosticScript --serial $env:ANDROID_SERIAL --package $TargetPackage
        }
    }

    Write-Step "6. Final Result Summary"
    if ($passedAll) {
        Write-Host "
=================================================================================" -ForegroundColor Green
        Write-Host ">>> [TOTAL LIMIT EXHAUSTION RESULT: PASS] Real-time Exhaustion verified! <<<" -ForegroundColor Green
        Write-Host "=================================================================================
" -ForegroundColor Green
    } else {
        Write-Host "
=================================================================================" -ForegroundColor Red
        Write-Host ">>> [TOTAL LIMIT EXHAUSTION RESULT: FAIL] Checks failed. Inspect logs above. <<<" -ForegroundColor Red
        Write-Host "=================================================================================
" -ForegroundColor Red
        exit 1
    }

} finally {
    Write-Step "Cleanup: Restoring original settings, stopping app, and resetting awake state..."
    Complete-DeviceTest -BackupPath $backupFile -TargetPackages @($TargetPackage) -AccessibilitySettings $accessibilityBackup
    Write-Success "Cleanup completed: original settings restored, target app stopped, returned to home."
}
