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
    [string]$DeviceId = "",
    [string]$TargetApkPath = "",
    [string]$ExpectedApkSha256 = "",
    [string]$BuildToolsDirectory = "",
    [switch]$ListOnly
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

    if (-not $ListOnly) { Assert-AdbDevice }
    $pluginScripts = @()
    $pluginSuites = @(Get-ChildItem -Path (Join-Path $PSScriptRoot '..') -Directory | ForEach-Object { Get-Item -LiteralPath (Join-Path $_.FullName 'device-tests/device-test-suite.ps1') -ErrorAction SilentlyContinue } | Sort-Object FullName)
    foreach ($suite in $pluginSuites) {
        $artifact = if ($ListOnly) { '' } else { $TargetApkPath }
        $pluginScripts += @(& $suite.FullName -ApkPath $artifact -ExpectedApkSha256 $ExpectedApkSha256 -BuildToolsDirectory $BuildToolsDirectory)
    }
    if ($ListOnly) {
        $recovery = 'test-device-service-recovery.ps1'
        $scripts = @(Get-ChildItem -Path $scriptsRoot -Filter 'test-device-*.ps1' -File | Sort-Object Name)
        @($scripts | Where-Object { $_.Name -ne $recovery }) + $pluginScripts + @($scripts | Where-Object { $_.Name -eq $recovery }) |
            ForEach-Object { $_.FullName }
        return
    }
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
    if (-not (Test-AccessibilityServiceBound)) {
        try {
            Start-TestApp -PackageName "neth.iecal.curbox.debug" -ActivityName "neth.iecal.curbox.ui.activity.FragmentActivity"
        } finally {
            Invoke-TestDeviceShell -Command "input keyevent 3"
        }
    }
    if (-not (Ensure-AccessibilityServiceBound -PackageName "neth.iecal.curbox.debug")) {
        throw "Curbox AppBlockerService did not bind after enabling accessibility."
    }
    Write-Host "Curbox AppBlockerService is bound; the original accessibility state will be restored after the suite." -ForegroundColor Cyan

    # Run the deliberate service recovery scenario after the other tests.
    $recoveryScript = "test-device-service-recovery.ps1"
    $discoveredScripts = @(Get-ChildItem -Path $scriptsRoot -Filter "test-device-*.ps1" -File | Sort-Object Name)
    $testScripts = @($discoveredScripts | Where-Object { $_.Name -ne $recoveryScript })
    $testScripts += $pluginScripts
    $testScripts += @($discoveredScripts | Where-Object { $_.Name -eq $recoveryScript })
    foreach ($scriptFile in $testScripts) {
        $isPlugin = $null -ne $scriptFile.PSObject.Properties['Arguments']
        if ($isPlugin -and $scriptFile.SkipReason) {
            $results.Add([PSCustomObject]@{ Name = $scriptFile.Name; Status = "SKIPPED"; Detail = $scriptFile.SkipReason })
            Write-Host "[SKIPPED] $($scriptFile.Name) - $($scriptFile.SkipReason)" -ForegroundColor Yellow
            continue
        }
        if ($scriptFile.Name -eq "test-device-deep-sleep.ps1") {
            $results.Add([PSCustomObject]@{
                Name = $scriptFile.Name
                Status = "SKIPPED"
                Detail = "Physical sleep test excluded by user request; not run."
            })
            Write-Host "[SKIPPED] $($scriptFile.Name) — physical sleep excluded by user request; not run." -ForegroundColor Yellow
            continue
        }

        $childArguments = if ($isPlugin) { @($scriptFile.Arguments) } else { @("-TargetPackage", $TargetPackage) }
        if (-not $isPlugin -and -not [string]::IsNullOrWhiteSpace($TargetActivity)) {
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

            $cleanupErrors = @()
            $debugComponent = "neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService"
            $baselineServices = @(([string]$suiteAccessibilityBaseline.EnabledServices) -split ':' | Where-Object { $_ })
            if ([string]$suiteAccessibilityBaseline.AccessibilityEnabled -eq '1' -and $baselineServices -contains $debugComponent) {
                try {
                    Invoke-TestDeviceShell -Command "am force-stop neth.iecal.curbox.debug"
                    try {
                        Start-TestApp -PackageName "neth.iecal.curbox.debug" -ActivityName "neth.iecal.curbox.ui.activity.FragmentActivity"
                    } finally {
                        Invoke-TestDeviceShell -Command "input keyevent 3"
                    }
                    Enable-AccessibilityService -PackageName "neth.iecal.curbox.debug" | Out-Null
                    $rebindTimer = [System.Diagnostics.Stopwatch]::StartNew()
                    $rebound = $false
                    while ($rebindTimer.Elapsed.TotalSeconds -lt 30) {
                        if (Test-AccessibilityServiceBound) { $rebound = $true; break }
                        Start-Sleep -Milliseconds 500
                    }
                    if (-not $rebound) { throw "AppBlockerService did not rebind from the restored suite settings." }
                    Write-Host "AppBlockerService re-bound from the restored suite settings." -ForegroundColor Green
                } catch {
                    $cleanupErrors += $_.Exception.Message
                }
            } else {
                try {
                    $mainPid = Get-DeviceProcessPid -ProcessName "neth.iecal.curbox.debug"
                    $servicePid = Get-DeviceProcessPid -ProcessName "neth.iecal.curbox.debug:app_blocker_service"
                    if ($mainPid -or $servicePid) {
                        Invoke-TestDeviceShell -Command "am force-stop neth.iecal.curbox.debug"
                        $stopTimer = [System.Diagnostics.Stopwatch]::StartNew()
                        do {
                            $mainPid = Get-DeviceProcessPid -ProcessName "neth.iecal.curbox.debug"
                            $servicePid = Get-DeviceProcessPid -ProcessName "neth.iecal.curbox.debug:app_blocker_service"
                            if (-not $mainPid -and -not $servicePid) { break }
                            Start-Sleep -Milliseconds 500
                        } while ($stopTimer.Elapsed.TotalSeconds -lt 10)
                        if ($mainPid -or $servicePid) {
                            throw "Debug Curbox processes did not stop after suite settings were restored."
                        }
                    }
                    Write-Host "Debug accessibility was disabled in the saved baseline; service reload was skipped." -ForegroundColor DarkGray
                } catch { $cleanupErrors += $_.Exception.Message }
            }

            Start-Sleep -Seconds 1
            try {
                $restoredSettings = Get-DeviceSettings
                if ([string]$restoredSettings -cne [string]$suiteSettingsBaseline) {
                    throw "Suite settings did not match the saved baseline after cleanup."
                }
            } catch { $cleanupErrors += $_.Exception.Message }
            if ($suiteAccessibilityBackupReady) {
                try {
                    $restoredAccessibility = Backup-DeviceAccessibilitySettings
                    if ([string]$restoredAccessibility.EnabledServices -cne [string]$suiteAccessibilityBaseline.EnabledServices -or
                        [string]$restoredAccessibility.AccessibilityEnabled -cne [string]$suiteAccessibilityBaseline.AccessibilityEnabled) {
                        throw "Accessibility settings did not match the saved baseline after cleanup."
                    }
                } catch { $cleanupErrors += $_.Exception.Message }
            }
            if ($cleanupErrors.Count -gt 0) { throw ($cleanupErrors -join ' | ') }
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
