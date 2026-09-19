<#
.SYNOPSIS
    End-to-end test script for Guardian PIN unlock authentication flow on a connected device via ADB.
.DESCRIPTION
    1. Backs up original settings.json from device using device-test-common.
    2. Generates PBKDF2 salt/verifier for PIN "1234" via New-GuardianPinAuthConfig and injects into settings.json.
    3. Injects verified test AppRule configuration to block the target package with usage isolation.
    4. Launches target package and verifies GuardianApprovalActivity is displayed.
    5. Triggers PIN input mode via UIAutomator.
    6. Inputs incorrect PIN ("9999"), verifies strict error display and lock maintenance.
    7. Inputs correct PIN ("1234"), verifies successful authentication and return to target app.
    8. Verifies DataStore persistence of the approved override.
    9. Verifies target app relaunch maintains unblocked execution.
    10. Restores original settings.json and cleans up.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "com.woodenpharm.choseonggacha.MainActivity",
    [string]$Pin = "1234",
    [string]$WrongPin = "9999"
)

$ErrorActionPreference = "Stop"

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice

$tmpDir = Join-Path $env:TEMP "curbox_guardian_pin_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$passedAll = $true

function Request-GuardianSkipApproval() {
    $ui = Wait-For-UI "approval_skip_rule|Skip this rule|Skip for today|이 규칙 건너뛰기|오늘 건너뛰기" 8
    if (-not ($ui -match "approval_skip_rule" -or $ui -match "Skip this rule" -or $ui -match "이 규칙 건너뛰기" -or $ui -match "오늘 건너뛰기")) {
        Write-Fail "Approval screen missing Skip Rule button."
        return $false
    }

    # Resolution hierarchy: Resource-ID -> Primary Locale (Korean) -> Fallback Locale (English)
    $clicked = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_skip_rule"' "이 규칙 건너뛰기 버튼 (Resource-ID)" -Optional
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="이 규칙 건너뛰기"' "이 규칙 건너뛰기 텍스트 (Korean)" -Optional
    }
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="오늘 건너뛰기"' "오늘 건너뛰기 텍스트 (Korean)" -Optional
    }
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="Skip this rule"' "Skip this rule text (English)" -Optional
    }
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="Skip for today"' "Skip for today text (English)"
    }

    $uiDialog = Wait-For-UI "15분|30분|리셋 시까지|Skip for 15 minutes|Skip until the next reset" 6
    # Resolution hierarchy: Resource-ID + Pattern -> Primary Locale (Korean) -> Fallback Locale (English)
    $optionSelected = Tap-Node $uiDialog 'resource-id="android:id/text1"[^>]*text="[^"]*(?:15분|15 minutes)[^"]*"' "15분 건너뛰기 옵션 (ID + Pattern)" -Optional
    if (-not $optionSelected) {
        $optionSelected = Tap-Node $uiDialog 'text="15분 동안 건너뛰기"' "15분 건너뛰기 옵션 (Korean text)" -Optional
    }
    if (-not $optionSelected) {
        $optionSelected = Tap-Node $uiDialog 'text="15분 건너뛰기"' "15분 건너뛰기 옵션 (Korean text)" -Optional
    }
    if (-not $optionSelected) {
        $optionSelected = Tap-Node $uiDialog 'text="Skip for 15 minutes"' "Skip for 15 minutes option (English text)" -Optional
    }
    if (-not $optionSelected) {
        $optionSelected = Tap-Node $uiDialog 'resource-id="android:id/text1"' "첫 번째 라디오 옵션 (ID fallback)"
    }

    return ($clicked -and $optionSelected)
}

function Submit-GuardianPin([string]$PinValue) {
    $uiPinDialog = Wait-For-UI "guardian_enter_password|Enter guardian password|비밀번호 입력|Password" 6
    if (-not ($uiPinDialog -match "Enter guardian password" -or $uiPinDialog -match "비밀번호 입력" -or $uiPinDialog -match "Password")) {
        Write-Fail "Guardian PIN input dialog was NOT displayed."
        return $false
    }

    $nodePin = Get-NodeBounds $uiPinDialog 'class="android.widget.EditText"'
    if (-not $nodePin.Found) {
        $nodePin = Get-NodeBounds $uiPinDialog 'password="true"'
    }
    if (-not $nodePin.Found) {
        $nodePin = Get-NodeBounds $uiPinDialog 'text="Password"'
    }

    if (-not $nodePin.Found) {
        Write-Fail "Could not locate PIN EditText node in UI dump."
        return $false
    }

    adb shell "input tap $($nodePin.X) $($nodePin.Y)" | Out-Null
    Start-Sleep -Milliseconds 500
    # Android IME Backspace keyevent 67 repeated per tools/AGENTS.md
    adb shell "input keyevent 67 67 67 67 67" | Out-Null
    adb shell "input text $PinValue" | Out-Null
    Start-Sleep -Milliseconds 500

    $uiAfterTyping = Dump-UI
    # Resolution hierarchy: Resource-ID -> Primary Locale (Korean) -> Fallback Locale (English)
    $continued = Tap-Node $uiAfterTyping 'resource-id="android:id/button1"' "계속/확인 버튼 (Resource-ID)" -Optional
    if (-not $continued) {
        $continued = Tap-Node $uiAfterTyping 'text="계속"' "계속 텍스트 버튼 (Korean)" -Optional
    }
    if (-not $continued) {
        $continued = Tap-Node $uiAfterTyping 'text="Continue"' "Continue text button (English)"
    }

    return $continued
}

try {
    Write-Step "1. Backing up device settings.json..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile
    Set-DeviceAwake $true
    Write-Success "Backup saved to $backupFile and wake lock acquired."

    $testStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

    Write-Step "2. Generating and injecting Guardian PIN auth config (PIN: $Pin)..."
    $guardianAuth = New-GuardianPinAuthConfig -Pin $Pin
    $injected = Set-DeviceGuardianAuthConfig -GuardianAuthConfig $guardianAuth
    if (-not $injected) {
        Write-Fail "Failed to inject Guardian PIN auth config into device settings.json!"
        $passedAll = $false
    } else {
        $deviceSettings = Get-DeviceSettings -AsObject
        if (Test-GuardianAuthConfig $deviceSettings) {
            Write-Success "Guardian PIN auth config successfully injected and verified in device settings.json!"
        } else {
            Write-Fail "Injected Guardian PIN auth config was not active in settings.json!"
            $passedAll = $false
        }
    }

    Write-Step "3. Crafting Test AppRule configuration..."
    $targetGroupId = "test-target-group-01"
    $contributorGroupId = "test-contrib-group-01"
    $ruleId = "test-rule-01"

    $appRuleSnapshot = New-ContributorAppRuleConfig `
        -TargetPackage $TargetPackage `
        -ContributorPackage "com.initialcoms.ridi" `
        -RequiredMinutes 15 `
        -AllowedMinutes 30 `
        -TargetGroupId $targetGroupId `
        -ContributorGroupId $contributorGroupId `
        -RuleId $ruleId

    Write-Step "4. Injecting test AppRule configuration via broadcast seam..."
    # Isolate daily accumulation with UsageGenerationStartedAtMs per tools/AGENTS.md
    Inject-TestAppRules -AppRuleSnapshot $appRuleSnapshot -UsageGenerationStartedAtMs $testStartTimeMs
    Write-Success "Injected test AppRules via broadcast seam with usage generation isolation."

    Write-Step "5. Launching Target App ($TargetPackage) to trigger Lock Screen..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/$TargetActivity" | Out-Null

    Write-Step "6. Verifying GuardianApprovalActivity is displayed..."
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

    Write-Step "7. Triggering PIN input mode..."
    $skipRequested = Request-GuardianSkipApproval
    if ($skipRequested) {
        Write-Success "Guardian PIN input dialog is displayed!"
    } else {
        Write-Fail "Failed to trigger Guardian PIN input dialog."
        $passedAll = $false
    }

    Write-Step "8. Testing Invalid PIN ($WrongPin): Verifying error and lock maintenance..."
    # Clear logcat before wrong PIN input to reliably capture error toast
    adb logcat -c | Out-Null

    $wrongSubmitted = Submit-GuardianPin -PinValue $WrongPin
    if ($wrongSubmitted) {
        Write-Success "Submitted invalid PIN '$WrongPin'."
        Start-Sleep -Seconds 1

        # Check lock maintenance: GuardianApprovalActivity must remain in foreground
        $lockFocus = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        if ($lockFocus.Success) {
            Write-Success "Lock maintained: GuardianApprovalActivity remains in foreground after invalid PIN!"
        } else {
            Write-Fail "Lock was NOT maintained! Focus: $($lockFocus.RawFocus)"
            $passedAll = $false
        }

        # Check error indication: assert toast event for Curbox package
        $recentLogs = (adb logcat -d -t 200 | Out-String)
        $hasErrorIndication = (
            ($recentLogs -match "Toast" -and $recentLogs -match "neth.iecal.curbox.debug") -or
            ($recentLogs -match "That password is wrong" -or $recentLogs -match "guardian_wrong_password")
        )
        if ($hasErrorIndication) {
            Write-Success "Error feedback strictly verified for invalid PIN submission (Toast event for package detected)."
        } else {
            Write-Fail "Error feedback for invalid PIN not found in logs! Expected Toast event for package."
            $passedAll = $false
        }
    } else {
        Write-Fail "Failed to submit invalid PIN."
        $passedAll = $false
    }

    Write-Step "9. Testing Valid PIN ($Pin): Verifying authentication and app return..."
    $skipRequestedAgain = Request-GuardianSkipApproval
    if (-not $skipRequestedAgain) {
        Write-Fail "Failed to re-trigger Guardian PIN input dialog for valid PIN."
        $passedAll = $false
    }

    $validSubmitted = Submit-GuardianPin -PinValue $Pin
    if ($validSubmitted) {
        Write-Success "Submitted valid PIN '$Pin'."

        # Wait for Target App to regain focus
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $targetFocus = $null
        while ($sw.Elapsed.TotalSeconds -lt 10) {
            $targetFocus = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
            if ($targetFocus.Success) { break }
            Start-Sleep -Milliseconds 500
        }

        if ($targetFocus -and $targetFocus.Success) {
            Write-Success "Target app is unlocked and successfully returned to foreground!"
        } else {
            Write-Fail "Target app was not unlocked / did not regain focus! Focus: $($targetFocus.RawFocus)"
            $passedAll = $false
        }
    } else {
        Write-Fail "Failed to submit valid PIN."
        $passedAll = $false
    }

    Write-Step "10. Verifying Skip Persistence in DataStore..."
    $updatedObj = Get-DeviceSettings -AsObject
    $minSkipThresholdMs = $testStartTimeMs + (10 * 60 * 1000) # At least 10 minutes from test start
    $hasSkip = Test-AppRuleSkip -OverrideState $updatedObj.appRuleOverrideState -RuleId $ruleId -MinSkipUntilMs $minSkipThresholdMs
    if ($hasSkip) {
        $skipsJson = $updatedObj.appRuleOverrideState.skips | ConvertTo-Json -Compress
        Write-Success "DataStore successfully recorded skip override for rule '$ruleId'! Skips: $skipsJson"
    } else {
        Write-Fail "Skip for rule '$ruleId' not found in DataStore settings! State: $($updatedObj.appRuleOverrideState | ConvertTo-Json -Compress)"
        $passedAll = $false
    }

    Write-Step "11. Verifying Target App relaunch maintains unblocked execution..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    adb shell "am start -n $TargetPackage/$TargetActivity" | Out-Null

    Start-Sleep -Seconds 2
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $relaunchSuccess = $true
    while ($sw.Elapsed.TotalSeconds -lt 5) {
        $currentFocus = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if (-not $currentFocus.Success) {
            Write-Fail "Target app did not maintain focus upon restart! Focus: $($currentFocus.RawFocus)"
            $relaunchSuccess = $false
            $passedAll = $false
            break
        }
        $blockerFocus = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity|WarningActivity" -PassThru
        if ($blockerFocus.Success) {
            Write-Fail "Target app was unexpectedly blocked upon restart! Focus: $($blockerFocus.RawFocus)"
            $relaunchSuccess = $false
            $passedAll = $false
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if ($relaunchSuccess) {
        Write-Success "Target app relaunched cleanly without blocking screen and retained foreground focus!"
    }

    Write-Step "12. Final Result Summary"
    if ($passedAll) {
        Write-Host "`n=========================================================================" -ForegroundColor Green
        Write-Host ">>> [TOTAL GUARDIAN PIN UNLOCK RESULT: PASS] PIN unlock flow verified! <<<" -ForegroundColor Green
        Write-Host "=========================================================================`n" -ForegroundColor Green
    } else {
        Write-Host "`n=========================================================================" -ForegroundColor Red
        Write-Host ">>> [TOTAL GUARDIAN PIN UNLOCK RESULT: FAIL] Some checks failed. Inspect logs above. <<<" -ForegroundColor Red
        Write-Host "=========================================================================`n" -ForegroundColor Red
        exit 1
    }

} finally {
    Write-Step "13. Cleanup & Restoring original settings.json..."
    try {
        Set-DeviceAwake $false
    } catch { }

    if (Test-Path $backupFile) {
        Restore-DeviceSettings -BackupPath $backupFile | Out-Null
        Write-Success "Original settings restored from backup."
    } else {
        Clear-TestAppRules | Out-Null
    }

    adb shell "am force-stop $TargetPackage" | Out-Null
    adb shell "input keyevent 3" | Out-Null # HOME
    Write-Success "Target app stopped, returned to home."
}
