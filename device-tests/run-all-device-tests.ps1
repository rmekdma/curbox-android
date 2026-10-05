<#
.SYNOPSIS
    Runs every automated device-test script and reports excluded physical deep sleep separately.
.DESCRIPTION
    Child PowerShell processes isolate each test's exit code. The suite continues after
    failures so the final report lists every runnable test. Physical deep sleep is
    always recorded as skipped by user request and is never counted as a pass.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "",
    [string]$ContributorPackage = "com.initialcoms.ridi",
    [string]$OtherPackage = "com.initialcoms.ridi",
    [string]$DeviceId = ""
)

$ErrorActionPreference = "Stop"
$scriptsRoot = $PSScriptRoot
$powerShellExe = Join-Path $PSHOME "powershell.exe"
if (-not (Test-Path $powerShellExe)) {
    $command = Get-Command powershell.exe -ErrorAction SilentlyContinue
    if ($command) { $powerShellExe = $command.Source }
}
if (-not (Test-Path $powerShellExe)) {
    throw "Windows PowerShell executable was not found."
}

$previousAndroidSerial = $env:ANDROID_SERIAL
if (-not [string]::IsNullOrWhiteSpace($DeviceId)) {
    $env:ANDROID_SERIAL = $DeviceId
}

$results = New-Object System.Collections.Generic.List[object]
$commonLoaded = $false
$suiteBackupReady = $false
$suiteAccessibilityBaseline = $null
$suiteAccessibilityBackupReady = $false
$suiteBaselineRestored = $false
$suiteBackupPath = Join-Path (Join-Path $env:TEMP "curbox_device_test_suite") "settings_backup.json"
$suiteTargetPackages = @($TargetPackage, $ContributorPackage, $OtherPackage)

function Stop-SuiteMainProcessAndWait([int]$TimeoutSeconds = 10) {
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

$knownContributorScripts = @(
    "test-device-apprules.ps1",
    "test-device-contributor-flow.ps1",
    "test-device-guardian-pin-unlock.ps1",
    "test-device-guardian-skip-today.ps1",
    "test-device-service-recovery.ps1"
)
$knownOtherPackageScripts = @(
    "test-device-guardian-dialog-ui.ps1",
    "test-device-guardian-extra-time.ps1",
    "test-device-main-app-extra-time.ps1"
)

try {
    . "$PSScriptRoot/lib/device-test-common.ps1"
    $commonLoaded = $true

    Assert-AdbDevice
    $suiteBackupDirectory = Split-Path -Parent $suiteBackupPath
    if (-not (Test-Path -LiteralPath $suiteBackupDirectory)) {
        New-Item -ItemType Directory -Path $suiteBackupDirectory | Out-Null
    }
    $suiteSettingsBaseline = Backup-DeviceSettings -DestinationPath $suiteBackupPath
    if (-not $suiteSettingsBaseline) {
        throw "Could not create the suite-level settings backup."
    }
    $suiteBackupReady = $true
    $suiteAccessibilityBaseline = Backup-DeviceAccessibilitySettings
    $suiteAccessibilityBackupReady = $true

    Write-Host "`nBacking up suite baseline and enabling Curbox accessibility for E2E tests..." -ForegroundColor Cyan
    Enable-AccessibilityService -PackageName "neth.iecal.curbox.debug" | Out-Null
    $bound = $false
    $bindTimer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($bindTimer.Elapsed.TotalSeconds -lt 15) {
        if (Test-AccessibilityServiceBound) {
            $bound = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $bound) {
        throw "Curbox AppBlockerService did not bind after enabling accessibility."
    }
    Write-Host "Curbox AppBlockerService is bound; the original accessibility state will be restored after the suite." -ForegroundColor Cyan

    $testScripts = @(Get-ChildItem -Path $scriptsRoot -Filter "test-device-*.ps1" -File | Sort-Object Name)
    foreach ($scriptFile in $testScripts) {
        if ($scriptFile.Name -eq "test-device-deep-sleep.ps1") {
            $results.Add([PSCustomObject]@{
                Name = $scriptFile.Name
                Status = "SKIPPED"
                Detail = "Physical sleep test excluded by user request; not run."
            })
            Write-Host "[SKIPPED] $($scriptFile.Name) — physical sleep excluded by user request; not run." -ForegroundColor Yellow
            continue
        }

        $childArguments = @("-TargetPackage", $TargetPackage)
        if (-not [string]::IsNullOrWhiteSpace($TargetActivity)) {
            $childArguments += @("-TargetActivity", $TargetActivity)
        }
        if ($knownContributorScripts -contains $scriptFile.Name) {
            $childArguments += @("-ContributorPackage", $ContributorPackage)
        }
        if ($knownOtherPackageScripts -contains $scriptFile.Name) {
            $childArguments += @("-OtherPackage", $OtherPackage)
        }

        Write-Host "`n[RUN] $($scriptFile.Name)" -ForegroundColor Cyan
        $detail = "Exit code 0."
        try {
            & $powerShellExe -NoProfile -ExecutionPolicy Bypass -File $scriptFile.FullName @childArguments
            $exitCode = $LASTEXITCODE
        } catch {
            $exitCode = 1
            $detail = $_.Exception.Message
        }
        if ($exitCode -eq 0) {
            $results.Add([PSCustomObject]@{ Name = $scriptFile.Name; Status = "PASS"; Detail = $detail })
            Write-Host "[PASS] $($scriptFile.Name)" -ForegroundColor Green
        } else {
            if ($detail -eq "Exit code 0.") { $detail = "Exit code $exitCode." }
            $results.Add([PSCustomObject]@{ Name = $scriptFile.Name; Status = "FAIL"; Detail = $detail })
            Write-Host "[FAIL] $($scriptFile.Name) (exit $exitCode)" -ForegroundColor Red
        }
    }
} catch {
    $detail = $_.Exception.Message
    $results.Add([PSCustomObject]@{ Name = "suite-setup"; Status = "FAIL"; Detail = $detail })
    Write-Host "[FAIL] Device test suite setup: $detail" -ForegroundColor Red
} finally {
    if ($commonLoaded -and $suiteBackupReady) {
        try {
            Write-Host "`nRestoring suite settings and accessibility baseline..." -ForegroundColor Cyan
            Complete-DeviceTest `
                -BackupPath $suiteBackupPath `
                -TargetPackages $suiteTargetPackages `
                -AccessibilitySettings $suiteAccessibilityBaseline

            $mainProcessReload = Stop-SuiteMainProcessAndWait
            if (-not $mainProcessReload.Stopped) {
                throw "The main Curbox process did not retire after suite settings were restored."
            }
            $serviceReload = Restart-DeviceAccessibilityServiceIfEnabled -AccessibilitySettings $suiteAccessibilityBaseline
            if ($serviceReload.Skipped) {
                Write-Host "Accessibility was disabled in the saved baseline; service restart was skipped." -ForegroundColor DarkGray
            } elseif ($serviceReload.ProcessId) {
                Write-Host "AppBlockerService re-bound as PID $($serviceReload.ProcessId) from the restored suite settings." -ForegroundColor Green
            } else {
                throw "AppBlockerService did not rebind from the restored suite settings."
            }

            Start-Sleep -Seconds 1
            $restoredSettings = Get-DeviceSettings
            if ([string]$restoredSettings -cne [string]$suiteSettingsBaseline) {
                throw "Suite settings did not match the saved baseline after cleanup."
            }
            if ($suiteAccessibilityBackupReady) {
                $restoredAccessibility = Backup-DeviceAccessibilitySettings
                if ([string]$restoredAccessibility.EnabledServices -cne [string]$suiteAccessibilityBaseline.EnabledServices -or
                    [string]$restoredAccessibility.AccessibilityEnabled -cne [string]$suiteAccessibilityBaseline.AccessibilityEnabled) {
                    throw "Accessibility settings did not match the saved baseline after cleanup."
                }
            }
            $suiteBaselineRestored = $true
            Write-Host "Suite settings and accessibility baseline restored and verified." -ForegroundColor Green
        } catch {
            Write-Host "[WARN] Suite baseline restoration reported an error: $($_.Exception.Message)" -ForegroundColor Yellow
            $results.Add([PSCustomObject]@{ Name = "suite-cleanup"; Status = "FAIL"; Detail = $_.Exception.Message })
        }
        if ($suiteBaselineRestored) {
            Remove-Item -LiteralPath $suiteBackupPath -Force -ErrorAction SilentlyContinue
        } else {
            Write-Host "[WARN] Preserving the suite settings backup at $suiteBackupPath because restoration could not be verified." -ForegroundColor Yellow
        }
    }
    if ([string]::IsNullOrWhiteSpace($previousAndroidSerial)) {
        Remove-Item Env:\ANDROID_SERIAL -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_SERIAL = $previousAndroidSerial
    }
}

$passCount = @($results | Where-Object { $_.Status -eq "PASS" }).Count
$failCount = @($results | Where-Object { $_.Status -eq "FAIL" }).Count
$skipCount = @($results | Where-Object { $_.Status -eq "SKIPPED" }).Count

Write-Host "`nDevice test suite results: $passCount passed, $failCount failed, $skipCount skipped." -ForegroundColor Cyan
$results | Format-Table -AutoSize Name, Status, Detail | Out-Host

if ($failCount -gt 0) { exit 1 }
