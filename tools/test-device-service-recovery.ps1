<#
.SYNOPSIS
    End-to-end test script for Curbox accessibility service process recovery and immediate re-blocking upon unexpected termination.
.DESCRIPTION
    Validates the self-recovery lifecycle when the background accessibility service process (:app_blocker_service)
    is forcibly terminated (SIGKILL / crash induction) while blocking rules are active:
    1. Pre-flight check: Assert ADB device connectivity and verify AppBlockerService is enabled.
    2. Backs up settings.json from device and acquires device wake lock (Set-DeviceAwake $true).
    3. Injects test AppRule configuration blocking the target package.
    4. Launches target package, verifies initial blocking, and dismisses overlay to Home while retaining target app in background.
    5. Queries and records the current PID of `:app_blocker_service` using Get-DeviceProcessPid.
    6. Forcibly terminates the service process using Stop-ServiceProcess (kill -9 / am crash).
    7. Polls `dumpsys accessibility` and process state until a new PID is assigned, service is re-bound, and warmup completes.
    8. Brings target app back to the foreground (without force-stop) and verifies it is immediately intercepted and blocked with no leaks.
    9. Restores original settings.json, unconditionally clears rule overrides, stops target package, and reverts wake lock.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "com.woodenpharm.choseonggacha.MainActivity",
    [string]$ServiceProcessName = ":app_blocker_service",
    [int]$MaxRecoveryWaitSeconds = 30,
    [int]$WarmupWaitSeconds = 2
)

$ErrorActionPreference = "Stop"

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice

$tmpDir = Join-Path $env:TEMP "curbox_service_recovery_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$passedAll = $true

function Assert-TargetBlocked([int]$TimeoutSeconds = 10) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $lastFocus = ""
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $focusCheck = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity|WarningActivity" -PassThru
        $lastFocus = $focusCheck.RawFocus
        if ($focusCheck.Success) {
            return @{
                Blocked = $true
                Focus = $lastFocus
                ElapsedSeconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
            }
        }
        Start-Sleep -Milliseconds 500
    }
    return @{
        Blocked = $false
        Focus = $lastFocus
        ElapsedSeconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    }
}

try {
    Write-Step "0. Preflight: Ensuring device is awake and accessibility service is enabled..."
    Set-DeviceAwake $true
    Enable-AccessibilityService | Out-Null

    $isBoundInitial = Test-AccessibilityServiceBound
    if (-not $isBoundInitial) {
        Write-Host "Service not yet bound; waking service via launcher..." -ForegroundColor Yellow
        adb shell "am start -n neth.iecal.curbox.debug/neth.iecal.curbox.ui.activity.FragmentActivity" | Out-Null
        Start-Sleep -Seconds 2
        adb shell "input keyevent 3" | Out-Null # Home
        Start-Sleep -Seconds 1
    }

    Write-Step "1. Backing up device settings.json..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile
    if (-not $rawSettings) {
        Write-Fail "Failed to back up settings.json from device."
        $passedAll = $false
        exit 1
    }
    Write-Success "Backup saved to $backupFile"

    $testStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

    Write-Step "2. Crafting and injecting test AppRule configuration..."
    $targetGroupId = "test-target-group-recovery-01"
    $contributorGroupId = "test-contrib-group-recovery-01"
    $ruleId = "test-rule-recovery-01"

    $appRuleSnapshot = New-ContributorAppRuleConfig `
        -TargetPackage $TargetPackage `
        -ContributorPackage "com.initialcoms.ridi" `
        -RequiredMinutes 15 `
        -AllowedMinutes 30 `
        -TargetGroupId $targetGroupId `
        -ContributorGroupId $contributorGroupId `
        -RuleId $ruleId

    Inject-TestAppRules -AppRuleSnapshot $appRuleSnapshot -UsageGenerationStartedAtMs $testStartTimeMs
    Write-Success "Test AppRule configuration injected successfully."

    Write-Step "3. Launching Target App ($TargetPackage) and verifying initial blocking..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Milliseconds 500
    adb shell "am start -n $TargetPackage/$TargetActivity" | Out-Null

    $initialCheck = Assert-TargetBlocked -TimeoutSeconds 10
    if ($initialCheck.Blocked) {
        Write-Success "Initial blocking screen verified on top after $($initialCheck.ElapsedSeconds)s! Focus: $($initialCheck.Focus)"
    } else {
        Write-Fail "Target app was NOT blocked initially! Current focus: $($initialCheck.Focus)"
        $passedAll = $false
    }

    # Dismiss blocking overlay back to Home so the target app remains running in background tasks,
    # and preventing stale overlay from masking the post-recovery re-blocking assertion.
    Write-Host "Dismissing blocking overlay to Home while retaining target app task in background..." -ForegroundColor DarkGray
    adb shell "input keyevent 3" | Out-Null # Home
    Start-Sleep -Seconds 1

    Write-Step "4. Querying current PID of $ServiceProcessName..."
    $initialPid = Get-DeviceProcessPid -ProcessName $ServiceProcessName
    if ($initialPid) {
        Write-Success "Found $ServiceProcessName running with PID: $initialPid"
    } else {
        Write-Fail "Could not find PID for $ServiceProcessName via ps/ps -ef!"
        $passedAll = $false
        exit 1
    }

    Write-Step "5. Forcibly terminating $ServiceProcessName (PID: $initialPid)..."
    Stop-ServiceProcess -TargetPid $initialPid -ProcessName $ServiceProcessName | Out-Null
    Write-Host "Dispatched kill signal / termination for PID $initialPid." -ForegroundColor DarkGray

    # Verify initial PID is terminated
    $verifyDeadSw = [System.Diagnostics.Stopwatch]::StartNew()
    $initialDied = $false
    while ($verifyDeadSw.Elapsed.TotalSeconds -lt 5) {
        $currentCheckPid = Get-DeviceProcessPid -ProcessName $ServiceProcessName
        if ($currentCheckPid -ne $initialPid) {
            $initialDied = $true
            break
        }
        Start-Sleep -Milliseconds 300
    }

    if ($initialDied) {
        Write-Success "Initial process PID $initialPid terminated successfully."
    } else {
        Write-Fail "Initial process PID $initialPid did NOT terminate!"
        $passedAll = $false
    }

    Write-Step "6. Polling dumpsys accessibility for service re-binding and new PID..."
    $recoverySw = [System.Diagnostics.Stopwatch]::StartNew()
    $recovered = $false
    $newPid = $null

    while ($recoverySw.Elapsed.TotalSeconds -lt $MaxRecoveryWaitSeconds) {
        $candidatePid = Get-DeviceProcessPid -ProcessName $ServiceProcessName
        $isBound = Test-AccessibilityServiceBound

        if ($candidatePid -and $candidatePid -ne $initialPid -and $isBound) {
            $newPid = $candidatePid
            $recovered = $true
            $elapsedSeconds = [math]::Round($recoverySw.Elapsed.TotalSeconds, 1)
            Write-Success "Accessibility service recovered and re-bound in ${elapsedSeconds}s! (Old PID: $initialPid -> New PID: $newPid)"
            break
        }
        Start-Sleep -Milliseconds 500
    }

    if (-not $recovered) {
        Write-Fail "Accessibility service failed to recover within ${MaxRecoveryWaitSeconds}s!"
        $passedAll = $false
    } else {
        Write-Host "Waiting ${WarmupWaitSeconds}s for onServiceConnected warmup to complete..." -ForegroundColor DarkGray
        Start-Sleep -Seconds $WarmupWaitSeconds
        Write-Success "Warmup interval completed."
    }

    Write-Step "7. Bringing Target App back to foreground and verifying immediate re-blocking..."
    # Do NOT force-stop: bring existing background task back to foreground
    adb shell "am start -n $TargetPackage/$TargetActivity" | Out-Null

    $reblockCheck = Assert-TargetBlocked -TimeoutSeconds 8
    if ($reblockCheck.Blocked) {
        Write-Success "Immediate re-blocking confirmed after $($reblockCheck.ElapsedSeconds)s! Focus: $($reblockCheck.Focus)"
        Write-Success "Target app is completely covered; no screen leak detected."
    } else {
        Write-Fail "Target app was NOT re-blocked after service recovery! Current focus: $($reblockCheck.Focus)"
        $passedAll = $false
    }

    Write-Step "8. Final Result Summary"
    if ($passedAll) {
        Write-Host "`n=========================================================================" -ForegroundColor Green
        Write-Host ">>> [TOTAL SERVICE PROCESS RECOVERY RESULT: PASS] Self-recovery verified! <<<" -ForegroundColor Green
        Write-Host "=========================================================================`n" -ForegroundColor Green
    } else {
        Write-Host "`n=========================================================================" -ForegroundColor Red
        Write-Host ">>> [TOTAL SERVICE PROCESS RECOVERY RESULT: FAIL] Checks failed. Inspect logs. <<<" -ForegroundColor Red
        Write-Host "=========================================================================`n" -ForegroundColor Red
        exit 1
    }

} finally {
    Write-Step "9. Cleanup: Restoring original settings and resetting device state..."
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
    Write-Success "Target app stopped, returned to home, wake lock released."
}
