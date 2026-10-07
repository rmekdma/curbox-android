<#
.SYNOPSIS
    Common device testing harness library for Curbox ADB-based E2E tests.
.DESCRIPTION
    Provides shared helper functions across device test scripts:
    - Assert-AdbDevice: Verify connected ADB device
    - Start-TestApp: Launch a package by activity or its launcher intent
    - Backup-DeviceAccessibilitySettings: Capture secure accessibility settings
    - Restore-DeviceAccessibilitySettings: Restore secure accessibility settings
    - Set-DeviceAwake: Control stay-awake state (svc power stayon true/false)
    - Get-DeviceSettings: Read settings.json from device as raw string or PSCustomObject
    - Backup-DeviceSettings: Safely back up settings.json from device
    - Restore-DeviceSettings: Restore settings.json, broadcast refreshes, and sync cache
    - Inject-TestAppRules: Inject test rules JSON via broadcast seam
    - Clear-TestAppRules: Clear test rule overrides and refresh
    - Dump-UI: UIAutomator dump with retry logic
    - Wait-For-UI: Wait until UIAutomator dump matches expected pattern
    - Get-NodeBounds: Parse bounding rectangle coordinates from UI dump
    - Tap-Node: Locate and tap UI element
    - Assert-WindowFocus: Assert current focused activity on device
    - New-GuardianPinAuthConfig: Generate PBKDF2 salt/verifier GuardianAuthConfig object
    - New-TestAppGroup: Generate AppRuleAppGroup object with default membership history
    - New-ContributorAppRuleConfig: Generate AppRuleSnapshot with contributor condition rule
    - New-GuardianExtraTimePickerAppRuleConfig: Generate blocking, unrelated, and future-window rules
    - New-TimeRangeAppRuleConfig: Generate AppRuleSnapshot with timeRanges schedule rule
    - New-DailyLimitAppRuleConfig: Generate AppRuleSnapshot with daily allowance limit rule
    - Get-DeviceTimeInfo: Query and calculate device local time in minutes and seconds
    - Wait-DeviceMinute: Wait until device reaches target minute
    - Set-DeviceUsageGeneration: Reset useDay session generation to start fresh tracking epoch
    - Test-AppRuleSkip: Verify whether a rule skip override is recorded and active
    - Test-GuardianAuthConfig: Verify whether a guardian auth config or settings object has active credentials
    - Set-DeviceGuardianAuthConfig: Inject GuardianAuthConfig into device settings.json with proper 660 permissions and broadcast refresh
    - Set-DeviceUseDayResetTime: Update the test device use-day reset clock and refresh the blocker
    - Get-DeviceProcessPid: Query running process PID from ps/ps -ef
    - Test-AccessibilityServiceBound: Verify whether accessibility service is bound
    - Stop-ServiceProcess: Terminate or induce crash on target process PID
    - Restart-DeviceAccessibilityServiceIfEnabled: Rebind the service only when it was enabled in the saved baseline
    - Enable-AccessibilityService: Ensure accessibility service is enabled in secure settings
    - New-RolloverAppRuleConfig: Generate AppRuleSnapshot with rolloverEnabled and unlockDays
    - New-RuleRolloverPool: Generate RuleRolloverPool PSCustomObject
    - Test-AppRuleGuardianGrant: Verify whether an AppRuleGuardianGrant is recorded in override state
    - Get-RuleRolloverPool: Query RuleRolloverPool object from Settings or AppRuleRolloverState
    - Set-DeviceRolloverState: Inject AppRuleRolloverState into device settings.json with 660 permissions and broadcast refresh
    - Set-DeviceAppRuleSnapshot: Persist AppRuleSnapshot for main-app UI tests
    - Submit-GuardianPin: Enter and submit guardian PIN in password dialog via UIAutomator
#>

# Optional script-scoped command seams keep helper tests offline. Production runs
# leave these unset and use adb; Pester assigns in-memory handlers instead.
$script:DeviceTestShellHandler = $null
$script:DeviceTestShellOutputHandler = $null
$script:DeviceTestFilePushHandler = $null
$script:DeviceTestTempStringPushHandler = $null
$script:DeviceTestSettingsReader = $null
$script:DeviceTestSecureSettingReader = $null
$script:DeviceTestUiWaitHandler = $null
$script:DeviceTestSettingsBackupPath = $null

function Write-Step([string]$Msg) {
    Write-Host "`n====> $Msg" -ForegroundColor Cyan
}

function Write-Success([string]$Msg) {
    Write-Host "[PASS] $Msg" -ForegroundColor Green
}

function Write-Fail([string]$Msg) {
    Write-Host "[FAIL] $Msg" -ForegroundColor Red
}

function Assert-AdbDevice {
    $device = (adb devices | Select-String -Pattern "device$")
    if (-not $device) {
        Write-Error "No connected adb device found!"
    }
}

function Get-TestAppLaunchCommand(
    [string]$PackageName,
    [string]$ActivityName = "",
    [string]$IntentArguments = ""
) {
    if ([string]::IsNullOrWhiteSpace($PackageName)) {
        Write-Error "PackageName is required to launch a test app."
    }

    if ([string]::IsNullOrWhiteSpace($ActivityName)) {
        if (-not [string]::IsNullOrWhiteSpace($IntentArguments)) {
            Write-Error "IntentArguments require an explicit ActivityName."
        }
        return "monkey -p $PackageName 1"
    }

    $activity = $ActivityName.Trim()
    if ($activity.StartsWith(".")) {
        $activity = "$PackageName$activity"
    }
    $command = "am start -n $PackageName/$activity"
    if (-not [string]::IsNullOrWhiteSpace($IntentArguments)) {
        $command += " $($IntentArguments.Trim())"
    }
    return $command
}

function Start-TestApp(
    [string]$PackageName,
    [string]$ActivityName = "",
    [string]$IntentArguments = ""
) {
    $launchCommand = Get-TestAppLaunchCommand -PackageName $PackageName -ActivityName $ActivityName -IntentArguments $IntentArguments
    Invoke-TestDeviceShell -Command $launchCommand
}

function Backup-DeviceAccessibilitySettings {
    $enabledServices = Get-DeviceSecureSetting -Name "enabled_accessibility_services"
    $accessibilityEnabled = Get-DeviceSecureSetting -Name "accessibility_enabled"
    return [PSCustomObject]@{
        EnabledServices = $enabledServices
        AccessibilityEnabled = $accessibilityEnabled
    }
}

function Get-DeviceSecureSetting([string]$Name) {
    if ($script:DeviceTestSecureSettingReader -is [scriptblock]) {
        return (& $script:DeviceTestSecureSettingReader $Name)
    }
    return (adb shell "settings get secure $Name" | Out-String).Trim()
}

function Get-DeviceAccessibilityRestoreCommands($AccessibilitySettings) {
    if (-not $AccessibilitySettings) {
        Write-Error "Accessibility settings backup is required."
    }

    $commands = @()
    $settings = @(
        @{ Name = "enabled_accessibility_services"; Value = [string]$AccessibilitySettings.EnabledServices },
        @{ Name = "accessibility_enabled"; Value = [string]$AccessibilitySettings.AccessibilityEnabled }
    )
    foreach ($setting in $settings) {
        if ([string]::IsNullOrWhiteSpace($setting.Value) -or $setting.Value -eq "null") {
            $commands += "settings delete secure $($setting.Name)"
        } else {
            $commands += "settings put secure $($setting.Name) $($setting.Value)"
        }
    }
    return ,$commands
}

function Restore-DeviceAccessibilitySettings($AccessibilitySettings) {
    $commands = Get-DeviceAccessibilityRestoreCommands -AccessibilitySettings $AccessibilitySettings
    foreach ($command in $commands) {
        Invoke-TestDeviceShell -Command $command
    }
}

function Invoke-TestDeviceShell([string]$Command) {
    if ($script:DeviceTestShellHandler -is [scriptblock]) {
        & $script:DeviceTestShellHandler $Command | Out-Null
        return
    }
    adb shell $Command | Out-Null
}

function Get-TestDeviceShellOutput([string]$Command) {
    if ($script:DeviceTestShellOutputHandler -is [scriptblock]) {
        return (& $script:DeviceTestShellOutputHandler $Command | Out-String).TrimEnd()
    }
    return (adb shell $Command | Out-String).TrimEnd()
}

function Push-TestDeviceFile([string]$LocalPath, [string]$RemotePath) {
    if ($script:DeviceTestFilePushHandler -is [scriptblock]) {
        & $script:DeviceTestFilePushHandler $LocalPath $RemotePath | Out-Null
        return
    }
    adb push $LocalPath $RemotePath | Out-Null
}

function Complete-DeviceTest(
    [string]$BackupPath = "",
    [string[]]$TargetPackages = @(),
    $AccessibilitySettings = $null,
    [string]$PackageName = "neth.iecal.curbox.debug"
) {
    try {
        Set-DeviceAwake $false
    } catch {
        Write-Host "[WARN] Could not restore the device wake state: $($_.Exception.Message)" -ForegroundColor Yellow
    }

    $hasSettingsBackup = $false
    if (-not [string]::IsNullOrWhiteSpace($BackupPath) -and (Test-Path -LiteralPath $BackupPath)) {
        $resolvedBackupPath = [System.IO.Path]::GetFullPath($BackupPath)
        $hasSettingsBackup = ($script:DeviceTestSettingsBackupPath -eq $resolvedBackupPath)
    }

    if ($hasSettingsBackup) {
        try {
            Clear-TestAppRules -PackageName $PackageName | Out-Null
        } catch {
            Write-Host "[WARN] Could not clear test rule overrides before restore: $($_.Exception.Message)" -ForegroundColor Yellow
        }

        try {
            Start-Sleep -Milliseconds 500
            Restore-DeviceSettings -BackupPath $BackupPath -PackageName $PackageName | Out-Null
        } catch {
            Write-Host "[WARN] Could not restore settings backup: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    } else {
        Write-Host "[WARN] No current-run settings backup exists; skipped test override cleanup to preserve current grants and skips." -ForegroundColor Yellow
    }

    if ($AccessibilitySettings) {
        try {
            Restore-DeviceAccessibilitySettings -AccessibilitySettings $AccessibilitySettings
        } catch {
            Write-Host "[WARN] Could not restore accessibility settings: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }

    foreach ($targetPackage in $TargetPackages) {
        if ([string]::IsNullOrWhiteSpace($targetPackage)) { continue }
        try {
            Invoke-TestDeviceShell -Command "am force-stop $targetPackage"
        } catch {
            Write-Host "[WARN] Could not stop target app ${targetPackage}: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    try {
        Invoke-TestDeviceShell -Command "input keyevent 3"
    } catch {
        Write-Host "[WARN] Could not return the device to Home: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

function Set-DeviceAwake([bool]$Awake = $true) {
    $val = if ($Awake) { "true" } else { "false" }
    Invoke-TestDeviceShell -Command "svc power stayon $val"
    if ($Awake) {
        $screenState = Get-TestDeviceShellOutput -Command "dumpsys display | grep -i mScreenState"
        if ($screenState -match "OFF") {
            Invoke-TestDeviceShell -Command "input keyevent 26" # POWER
            Start-Sleep -Milliseconds 500
        }
        Invoke-TestDeviceShell -Command "input keyevent 224" # WAKEUP
        Invoke-TestDeviceShell -Command "wm dismiss-keyguard"
        Invoke-TestDeviceShell -Command "input keyevent 82" # UNLOCK / MENU
        Start-Sleep -Milliseconds 500
    }
}

function Push-TempStringToDevice([string]$Content, [string]$RemotePath) {
    if ($script:DeviceTestTempStringPushHandler -is [scriptblock]) {
        & $script:DeviceTestTempStringPushHandler $Content $RemotePath | Out-Null
        return
    }
    $tempLocal = [System.IO.Path]::GetTempFileName()
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($tempLocal, $Content, $utf8NoBom)
    adb push $tempLocal $RemotePath | Out-Null
    Remove-Item $tempLocal -Force -ErrorAction SilentlyContinue
}

function Get-DeviceSettings([string]$PackageName = "neth.iecal.curbox.debug", [switch]$AsObject) {
    if ($script:DeviceTestSettingsReader -is [scriptblock]) {
        return (& $script:DeviceTestSettingsReader $PackageName ([bool]$AsObject))
    }
    $rawSettings = (adb shell "run-as $PackageName cat files/datastore/settings.json" | Out-String).Trim().Trim([char]65279)
    if (-not $rawSettings -or $rawSettings -notmatch "\{") {
        return $null
    }
    if ($AsObject) {
        return ($rawSettings | ConvertFrom-Json)
    }
    return $rawSettings
}

function Backup-DeviceSettings([string]$DestinationPath, [string]$PackageName = "neth.iecal.curbox.debug") {
    $script:DeviceTestSettingsBackupPath = $null
    if (Test-Path -LiteralPath $DestinationPath) {
        Remove-Item -LiteralPath $DestinationPath -Force
    }
    $rawSettings = Get-DeviceSettings -PackageName $PackageName
    if (-not $rawSettings) {
        Write-Error "Failed to read settings.json from device!"
        return $null
    }
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($DestinationPath, $rawSettings, $utf8NoBom)
    $script:DeviceTestSettingsBackupPath = [System.IO.Path]::GetFullPath($DestinationPath)
    return $rawSettings
}

function Restore-DeviceSettings([string]$BackupPath, [string]$PackageName = "neth.iecal.curbox.debug") {
    if (-not (Test-Path $BackupPath)) {
        Write-Error "Backup file not found at: $BackupPath"
        return $false
    }
    Push-TestDeviceFile -LocalPath $BackupPath -RemotePath "/data/local/tmp/settings_backup.json"
    Invoke-TestDeviceShell -Command "run-as $PackageName cp /data/local/tmp/settings_backup.json files/datastore/settings.json"
    Invoke-TestDeviceShell -Command "run-as $PackageName chmod 660 files/datastore/settings.json"
    Invoke-TestDeviceShell -Command "rm -f /data/local/tmp/settings_backup.json"
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName"
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName"
    return $true
}

function Set-DeviceAppRuleSnapshot($AppRuleSnapshot, [string]$PackageName = "neth.iecal.curbox.debug") {
    $settingsObj = Get-DeviceSettings -PackageName $PackageName -AsObject
    if (-not $settingsObj) {
        Write-Error "Could not read settings before updating the AppRuleSnapshot."
        return $false
    }

    $settingsObj.appRuleSnapshot = $AppRuleSnapshot
    $jsonStr = $settingsObj | ConvertTo-Json -Depth 25 -Compress
    Push-TempStringToDevice -Content $jsonStr -RemotePath "/data/local/tmp/settings_app_rules.json"
    Invoke-TestDeviceShell -Command "run-as $PackageName cp /data/local/tmp/settings_app_rules.json files/datastore/settings.json"
    Invoke-TestDeviceShell -Command "run-as $PackageName chmod 660 files/datastore/settings.json"
    Invoke-TestDeviceShell -Command "rm -f /data/local/tmp/settings_app_rules.json"
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName"
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName"
    Start-Sleep -Seconds 1
    return $true
}

function Set-DeviceUsageGeneration([long]$GenerationStartedAtMs = 0, [string]$PackageName = "neth.iecal.curbox.debug") {
    if ($GenerationStartedAtMs -le 0) {
        $GenerationStartedAtMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    }
    $settingsObj = Get-DeviceSettings -PackageName $PackageName -AsObject
    if ($settingsObj) {
        $settingsObj.useDayGenerationStartedAtMs = $GenerationStartedAtMs

        $jsonStr = $settingsObj | ConvertTo-Json -Depth 20 -Compress
        Push-TempStringToDevice -Content $jsonStr -RemotePath "/data/local/tmp/settings_gen.json"
        adb shell "run-as $PackageName cp /data/local/tmp/settings_gen.json files/datastore/settings.json" | Out-Null
        adb shell "run-as $PackageName chmod 660 files/datastore/settings.json" | Out-Null
        adb shell "rm -f /data/local/tmp/settings_gen.json" | Out-Null

        adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName" | Out-Null
        adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName" | Out-Null
        Start-Sleep -Seconds 1
    }
    return $GenerationStartedAtMs
}

function Inject-TestAppRules(
    $AppRuleSnapshot,
    [string]$PackageName = "neth.iecal.curbox.debug",
    [long]$UsageGenerationStartedAtMs = 0,
    $AppRuleRolloverState = $null,
    [switch]$PreserveOverrides
) {
    if (-not $PreserveOverrides) {
        Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p $PackageName"
        Start-Sleep -Milliseconds 500
    }

    if ($UsageGenerationStartedAtMs -gt 0) {
        Set-DeviceUsageGeneration -GenerationStartedAtMs $UsageGenerationStartedAtMs -PackageName $PackageName | Out-Null
    }

    if ($AppRuleRolloverState) {
        Set-DeviceRolloverState -RolloverState $AppRuleRolloverState -PackageName $PackageName | Out-Null
    }

    $rulesSnapshotJson = if ($AppRuleSnapshot -is [string]) {
        $AppRuleSnapshot
    } else {
        ($AppRuleSnapshot | ConvertTo-Json -Depth 20 -Compress)
    }

    Push-TempStringToDevice -Content $rulesSnapshotJson -RemotePath "/data/local/tmp/app_rules_inject.json"

    $shScript = "CONTENT=`$(cat /data/local/tmp/app_rules_inject.json)`nam broadcast -a neth.iecal.curbox.action.APPLY_TEST_APP_RULES -p $PackageName --es extra_app_rules_json `"`$CONTENT`"`n"
    Push-TempStringToDevice -Content $shScript -RemotePath "/data/local/tmp/inject.sh"

    Invoke-TestDeviceShell -Command "chmod 755 /data/local/tmp/inject.sh; /data/local/tmp/inject.sh"
    Invoke-TestDeviceShell -Command "rm -f /data/local/tmp/app_rules_inject.json /data/local/tmp/inject.sh"
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName"
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName"
    Start-Sleep -Seconds 1
}

function Clear-TestAppRules([string]$PackageName = "neth.iecal.curbox.debug") {
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p $PackageName"
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName"
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName"
}

function Dump-UI([int]$MaxRetries = 3) {
    for ($i = 1; $i -le $MaxRetries; $i++) {
        adb shell "rm -f /sdcard/curbox_dump.xml" | Out-Null
        $dumpResult = adb shell "uiautomator dump /sdcard/curbox_dump.xml" | Out-String
        if ($dumpResult -match "ERROR: could not get idle state" -or $dumpResult -match "ERROR:") {
            Start-Sleep -Milliseconds 500
            continue
        }
        $content = adb shell "cat /sdcard/curbox_dump.xml" | Out-String
        if ($content -and $content -match "<\?xml") {
            return $content
        }
        Start-Sleep -Milliseconds 300
    }
    # Final attempt fallback
    return (adb shell "cat /sdcard/curbox_dump.xml" | Out-String)
}

function Wait-For-UI([string]$Pattern, [int]$TimeoutSeconds = 8) {
    if ($script:DeviceTestUiWaitHandler -is [scriptblock]) {
        return (& $script:DeviceTestUiWaitHandler $Pattern $TimeoutSeconds)
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $lastUi = ""
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $lastUi = Dump-UI
        if ($lastUi -match $Pattern) {
            return $lastUi
        }
        Start-Sleep -Milliseconds 500
    }
    return $lastUi
}

function Get-NodeBounds([string]$Xml, [string]$Pattern) {
    # Attempt structured XML XPath query if pattern matches attribute
    if ($Pattern -match '^([a-zA-Z0-9_\-]+)="([^"]+)"$') {
        $attrName = $matches[1]
        $attrValue = $matches[2]
        try {
            [xml]$doc = $Xml
            $node = $doc.SelectSingleNode("//node[@$attrName='$attrValue']")
            if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                $x1 = [int]$matches[1]; $y1 = [int]$matches[2]; $x2 = [int]$matches[3]; $y2 = [int]$matches[4]
                $cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)
                return @{ Found = $true; X = $cx; Y = $cy; X1 = $x1; Y1 = $y1; X2 = $x2; Y2 = $y2 }
            }
        } catch {
            # Fall back to regex on XML parse error
        }
    }

    # Regex fallback
    if ($Xml -match "$Pattern[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"") {
        $x1 = [int]$matches[1]; $y1 = [int]$matches[2]; $x2 = [int]$matches[3]; $y2 = [int]$matches[4]
        $cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)
        return @{ Found = $true; X = $cx; Y = $cy; X1 = $x1; Y1 = $y1; X2 = $x2; Y2 = $y2 }
    }
    return @{ Found = $false }
}

function Tap-Node([string]$Xml, [string]$Pattern, [string]$Label = "", [switch]$Optional) {
    $node = Get-NodeBounds $Xml $Pattern
    if ($node.Found) {
        adb shell "input tap $($node.X) $($node.Y)"
        if ($Label) {
            Write-Host "Tapped $Label at ($($node.X), $($node.Y))" -ForegroundColor DarkGray
        }
        return $true
    }
    if (-not $Optional -and $Label) {
        Write-Host "[FAIL] Could not find node: $Label" -ForegroundColor Red
    }
    return $false
}

function Tap-TestRulePicker([string]$Xml, [int]$EndIconInsetPixels = 48) {
    $picker = Get-NodeBounds $Xml 'resource-id="neth.iecal.curbox.debug:id/rule_picker"'
    if (-not $picker.Found) {
        return $false
    }

    $inset = [Math]::Max(1, $EndIconInsetPixels)
    $tapX = [Math]::Max($picker.X1, $picker.X2 - $inset)
    Write-Host "[DIAGNOSTIC] Rule picker tap at ($tapX, $($picker.Y)); bounds=[$($picker.X1),$($picker.Y1)][$($picker.X2),$($picker.Y2)]." -ForegroundColor DarkGray
    Invoke-TestDeviceShell -Command "input tap $tapX $($picker.Y)"
    return $true
}

function Get-TestRulePickerLabel([string]$Xml) {
    try {
        [xml]$doc = $Xml
        $node = $doc.SelectSingleNode("//node[@resource-id='neth.iecal.curbox.debug:id/rule_picker']")
        if ($node) {
            return $node.GetAttribute("text").Trim()
        }
    } catch {
        return ""
    }
    return ""
}

function Get-TestRulePickerPopupBounds([string]$WindowDump) {
    if ([string]::IsNullOrWhiteSpace($WindowDump)) {
        return [PSCustomObject]@{ Found = $false }
    }

    $lines = $WindowDump -split "`r?`n"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notmatch '^\s*Window #\d+ Window\{[^}]*PopupWindow:') {
            continue
        }

        $blockLines = @($lines[$i])
        for ($j = $i + 1; $j -lt $lines.Count -and $lines[$j] -notmatch '^\s*Window #\d+ Window\{'; $j++) {
            $blockLines += $lines[$j]
        }
        $block = $blockLines -join "`n"
        if ($block -notmatch 'mParentWindow=.*neth\.iecal\.curbox\.debug/' -or
            $block -notmatch 'mHasSurface=true' -or
            $block -notmatch 'isVisible=true') {
            continue
        }

        $frameLine = @($blockLines | Where-Object { $_ -match '^\s*Frames:' } | Select-Object -First 1)
        if ($frameLine -and $frameLine[0] -match 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $x1 = [int]$matches[1]
            $y1 = [int]$matches[2]
            $x2 = [int]$matches[3]
            $y2 = [int]$matches[4]
            if ($x2 -gt $x1 -and $y2 -gt $y1) {
                return [PSCustomObject]@{
                    Found = $true
                    X1 = $x1
                    Y1 = $y1
                    X2 = $x2
                    Y2 = $y2
                    Width = $x2 - $x1
                    Height = $y2 - $y1
                }
            }
        }
    }
    return [PSCustomObject]@{ Found = $false }
}

function Get-TestRulePickerRowTapPoint($PopupBounds, [int]$CandidateIndex, [int]$CandidateCount) {
    if (-not $PopupBounds -or -not $PopupBounds.Found -or
        $CandidateCount -lt 1 -or $CandidateIndex -lt 0 -or $CandidateIndex -ge $CandidateCount -or
        $PopupBounds.Width -le 0 -or $PopupBounds.Height -lt $CandidateCount) {
        return [PSCustomObject]@{ Found = $false }
    }

    $rowHeight = [double]$PopupBounds.Height / $CandidateCount
    return [PSCustomObject]@{
        Found = $true
        X = [int][Math]::Floor($PopupBounds.X1 + ($PopupBounds.Width / 2.0))
        Y = [int][Math]::Floor($PopupBounds.Y1 + (($CandidateIndex + 0.5) * $rowHeight))
    }
}

function Wait-ForTestRulePickerPopup([int]$TimeoutSeconds = 10) {
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $lastWindowDump = ""
    do {
        $lastWindowDump = Get-TestDeviceShellOutput -Command "dumpsys window windows"
        $bounds = Get-TestRulePickerPopupBounds -WindowDump $lastWindowDump
        if ($bounds.Found) {
            return $bounds
        }
        Start-Sleep -Milliseconds 200
    } while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    $windowLines = $lastWindowDump -split "`r?`n"
    $popupHeaders = @(for ($i = 0; $i -lt $windowLines.Count; $i++) { if ($windowLines[$i] -match 'Window #\d+ Window\{[^}]*PopupWindow:') { $i } })
    $windowSummary = @($windowLines | Where-Object { $_ -match 'PopupWindow|mParentWindow|mHasSurface|Frames:|isVisible|mCurrentFocus' } | Select-Object -Last 24)
    if ($windowSummary.Count -gt 0) {
        Write-Host "[DIAGNOSTIC] Could not locate the visible picker popup in WindowManager: $($windowSummary -join ' | ')" -ForegroundColor DarkYellow
    }
    foreach ($headerIndex in $popupHeaders) {
        $popupBlock = @($windowLines[$headerIndex..([Math]::Min($windowLines.Count - 1, $headerIndex + 35))])
        $popupInfo = @($popupBlock | Where-Object { $_ -match 'Window #|mAttrs|mParentWindow|mHasSurface|Frames:|isVisible' })
        Write-Host "[DIAGNOSTIC] Popup window state: $($popupInfo -join ' | ')" -ForegroundColor DarkYellow
    }
    return [PSCustomObject]@{ Found = $false }
}

function Tap-TestRulePickerRow([int]$CandidateIndex, [int]$CandidateCount) {
    $popupBounds = Wait-ForTestRulePickerPopup
    $tapPoint = Get-TestRulePickerRowTapPoint -PopupBounds $popupBounds -CandidateIndex $CandidateIndex -CandidateCount $CandidateCount
    if (-not $tapPoint.Found) {
        return $false
    }
    Write-Host "Tapped rule picker row $CandidateIndex at ($($tapPoint.X), $($tapPoint.Y)) from visible popup bounds [$($popupBounds.X1),$($popupBounds.Y1)][$($popupBounds.X2),$($popupBounds.Y2)]." -ForegroundColor DarkGray
    Invoke-TestDeviceShell -Command "input tap $($tapPoint.X) $($tapPoint.Y)"
    Start-Sleep -Milliseconds 250
    return $true
}

function Get-TestRulePickerOptions([string]$CurrentUi, [int]$CandidateCount) {
    if ($CandidateCount -lt 1) {
        return [PSCustomObject]@{ Success = $false; Options = @(); Ui = $CurrentUi }
    }

    $options = @()
    $ui = $CurrentUi
    for ($index = 0; $index -lt $CandidateCount; $index++) {
        if (-not (Tap-TestRulePicker -Xml $ui)) {
            return [PSCustomObject]@{ Success = $false; Options = @($options); Ui = $ui }
        }
        if (-not (Tap-TestRulePickerRow -CandidateIndex $index -CandidateCount $CandidateCount)) {
            return [PSCustomObject]@{ Success = $false; Options = @($options); Ui = $ui }
        }
        $ui = Wait-For-UI -Pattern 'resource-id="neth\.iecal\.curbox\.debug:id/rule_picker"' -TimeoutSeconds 3
        $label = Get-TestRulePickerLabel -Xml $ui
        if ([string]::IsNullOrWhiteSpace($label)) {
            return [PSCustomObject]@{ Success = $false; Options = @($options); Ui = $ui }
        }
        $options += [PSCustomObject]@{ Index = $index; Label = $label }
    }

    $uniqueLabels = @($options | ForEach-Object { $_.Label } | Select-Object -Unique)
    return [PSCustomObject]@{
        Success = ($options.Count -eq $CandidateCount -and $uniqueLabels.Count -eq $CandidateCount)
        Options = @($options)
        Ui = $ui
    }
}

function Select-TestRulePickerOption([string]$CurrentUi, $CandidateOptions, [string]$RuleName) {
    $options = @($CandidateOptions)
    $matches = @($options | Where-Object { $_.Label -eq $RuleName })
    if ($matches.Count -ne 1) {
        return [PSCustomObject]@{ Success = $false; Label = (Get-TestRulePickerLabel -Xml $CurrentUi); Ui = $CurrentUi }
    }

    $currentLabel = Get-TestRulePickerLabel -Xml $CurrentUi
    if ($currentLabel -eq $RuleName) {
        return [PSCustomObject]@{ Success = $true; Label = $currentLabel; Ui = $CurrentUi }
    }

    if (-not (Tap-TestRulePicker -Xml $CurrentUi) -or
        -not (Tap-TestRulePickerRow -CandidateIndex ([int]$matches[0].Index) -CandidateCount $options.Count)) {
        return [PSCustomObject]@{ Success = $false; Label = $currentLabel; Ui = $CurrentUi }
    }

    $ui = Wait-For-UI -Pattern 'resource-id="neth\.iecal\.curbox\.debug:id/rule_picker"' -TimeoutSeconds 3
    $selectedLabel = Get-TestRulePickerLabel -Xml $ui
    return [PSCustomObject]@{
        Success = ($selectedLabel -eq $RuleName)
        Label = $selectedLabel
        Ui = $ui
    }
}

function Assert-WindowFocus([string]$ExpectedActivity, [switch]$PassThru) {
    $windowFocus = adb shell "dumpsys window displays | grep -E 'mCurrentFocus|mFocusedApp'" | Out-String
    if (-not $windowFocus -or $windowFocus.Trim() -eq "") {
        $windowFocus = adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'" | Out-String
    }
    $isMatch = $windowFocus -match $ExpectedActivity
    if (-not $isMatch -and -not $PassThru) {
        Write-Error "Window focus mismatch! Expected '$ExpectedActivity' but observed: $($windowFocus.Trim())"
    }
    if ($PassThru) {
        return @{
            Success = $isMatch
            RawFocus = $windowFocus.Trim()
        }
    }
    return $isMatch
}

function New-GuardianPinAuthConfig(
    [string]$Pin,
    [byte[]]$SaltBytes = $null,
    [int]$Iterations = 120000,
    [string]$Algorithm = "PBKDF2WithHmacSHA256"
) {
    if (-not $Pin) {
        return [PSCustomObject]@{
            passwordSalt = ""
            passwordVerifier = ""
            kdfAlgorithm = $Algorithm
            kdfIterations = $Iterations
        }
    }

    if (-not $SaltBytes) {
        $SaltBytes = [byte[]]::new(16)
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($SaltBytes)
    }

    $kdf = [System.Security.Cryptography.Rfc2898DeriveBytes]::new(
        $Pin,
        $SaltBytes,
        $Iterations,
        [System.Security.Cryptography.HashAlgorithmName]::SHA256
    )
    $verifierBytes = $kdf.GetBytes(32)

    return [PSCustomObject]@{
        passwordSalt = [Convert]::ToBase64String($SaltBytes)
        passwordVerifier = [Convert]::ToBase64String($verifierBytes)
        kdfAlgorithm = $Algorithm
        kdfIterations = $Iterations
    }
}

function New-TestAppGroup([string]$GroupId, [string]$GroupName, [string[]]$Packages, [long]$EffectiveFromMs = [long]::MinValue) {
    return [PSCustomObject]@{
        id = $GroupId
        name = $GroupName
        selectedPackages = @($Packages)
        membershipHistory = @(
            [PSCustomObject]@{
                effectiveFromMs = $EffectiveFromMs
                selectedPackages = @($Packages)
            }
        )
    }
}

function New-ContributorAppRuleConfig(
    [string]$TargetPackage,
    [string]$ContributorPackage = "com.initialcoms.ridi",
    [long]$RequiredMinutes = 1,
    [long]$AllowedMinutes = 1440,
    [string]$TargetGroupId = "test-target-group-01",
    [string]$ContributorGroupId = "test-contrib-group-01",
    [string]$RuleId = "test-rule-01",
    [bool]$GuardianExtraTimeAllowed = $true,
    [long]$EffectiveFromMs = [long]::MinValue
) {
    $groupTarget = New-TestAppGroup -GroupId $TargetGroupId -GroupName "테스트 타깃 앱" -Packages @($TargetPackage)
    $groupContrib = New-TestAppGroup -GroupId $ContributorGroupId -GroupName "학습" -Packages @($ContributorPackage) -EffectiveFromMs $EffectiveFromMs

    $rule = [PSCustomObject]@{
        id = $RuleId
        name = "게임 제한"
        isActive = $true
        weekdays = @(0, 1, 2, 3, 4, 5, 6)
        startMinute = 0
        endMinute = 0
        appGroupId = $TargetGroupId
        allowedMinutes = $AllowedMinutes
        usageConditionEnabled = $true
        usageConditionMinutes = $RequiredMinutes
        contributorGroupConditionMinutes = [PSCustomObject]@{
            $ContributorGroupId = $RequiredMinutes
        }
        contributorGroupIds = @($ContributorGroupId)
        earnedAllowanceEnabled = $false
        guardianExtraTimeAllowed = $GuardianExtraTimeAllowed
        timeRanges = @(
            [PSCustomObject]@{
                startMinute = 0
                endMinute = 0
            }
        )
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($TargetGroupId)
            excludedGroupIds = @()
        }
    }

    return [PSCustomObject]@{
        appGroups = @($groupTarget, $groupContrib)
        appRules = @($rule)
    }
}

function New-GuardianExtraTimePickerAppRuleConfig(
    [string]$TargetPackage,
    [string]$OtherPackage,
    [string]$TargetGroupId = "picker-target-group",
    [string]$OtherGroupId = "picker-other-group",
    [string]$NightRuleId = "night-rule",
    [string]$BlockingRuleId = "blocking-rule",
    [string]$UsageRuleId = "usage-rule",
    [string]$FutureRuleId = "future-rule",
    [int]$FutureWindowStartMinute = 480
) {
    if ($FutureWindowStartMinute -lt 0 -or $FutureWindowStartMinute -ge 1440) {
        Write-Error "FutureWindowStartMinute must be between 0 and 1439."
    }

    $targetGroup = New-TestAppGroup -GroupId $TargetGroupId -GroupName "테스트 타깃 앱" -Packages @($TargetPackage)
    $otherGroup = New-TestAppGroup -GroupId $OtherGroupId -GroupName "다른 앱" -Packages @($OtherPackage)

    $sharedRuleValues = @{
        isActive = $true
        weekdays = @(0, 1, 2, 3, 4, 5, 6)
        startMinute = 0
        endMinute = 0
        usageConditionEnabled = $false
        usageConditionMinutes = 0
        contributorGroupConditionMinutes = [PSCustomObject]@{}
        contributorGroupIds = @()
        earnedAllowanceEnabled = $false
        timeRanges = @([PSCustomObject]@{ startMinute = 0; endMinute = 0 })
        rolloverEnabled = $false
    }

    $nightRule = [PSCustomObject]@{
        id = $NightRuleId
        name = "NightRule"
        appGroupId = $TargetGroupId
        allowedMinutes = [long]0
        guardianExtraTimeAllowed = $false
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($TargetGroupId)
            excludedGroupIds = @()
        }
    }
    $blockingRule = [PSCustomObject]@{
        id = $BlockingRuleId
        name = "BlockingRule"
        appGroupId = $TargetGroupId
        allowedMinutes = [long]0
        guardianExtraTimeAllowed = $true
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($TargetGroupId)
            excludedGroupIds = @()
        }
    }
    $usageRule = [PSCustomObject]@{
        id = $UsageRuleId
        name = "UsageRule"
        appGroupId = $TargetGroupId
        allowedMinutes = [long]1440
        guardianExtraTimeAllowed = $true
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($TargetGroupId)
            excludedGroupIds = @()
        }
    }
    $futureRule = [PSCustomObject]@{
        id = $FutureRuleId
        name = "FutureRule"
        appGroupId = $OtherGroupId
        allowedMinutes = [long]1440
        guardianExtraTimeAllowed = $true
        timeRanges = @(
            [PSCustomObject]@{
                startMinute = $FutureWindowStartMinute
                endMinute = (($FutureWindowStartMinute + 30) % 1440)
            }
        )
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($OtherGroupId)
            excludedGroupIds = @()
        }
    }

    foreach ($rule in @($nightRule, $blockingRule, $usageRule, $futureRule)) {
        foreach ($property in $sharedRuleValues.GetEnumerator()) {
            if (-not $rule.PSObject.Properties[$property.Key]) {
                $rule | Add-Member -MemberType NoteProperty -Name $property.Key -Value $property.Value
            }
        }
    }

    return [PSCustomObject]@{
        appGroups = @($targetGroup, $otherGroup)
        appRules = @($nightRule, $blockingRule, $usageRule, $futureRule)
    }
}

function Get-DeviceTimeInfo([string]$DateString = "") {
    if (-not $DateString) {
        $DateString = (adb shell "date +'%H %M %S'" | Out-String).Trim()
    }
    if ($DateString -match '(\d{1,2})\s+(\d{1,2})\s+(\d{1,2})') {
        $hour = [int]$matches[1]
        $minute = [int]$matches[2]
        $second = [int]$matches[3]
        $currentMinute = ($hour * 60) + $minute
        $nextMinute = ($currentMinute + 1) % 1440
        $secondsUntilNextMinute = 60 - $second
        $timeString = "{0:D2}:{1:D2}:{2:D2}" -f $hour, $minute, $second
        return [PSCustomObject]@{
            Hour = $hour
            Minute = $minute
            Second = $second
            CurrentMinute = $currentMinute
            NextMinute = $nextMinute
            SecondsUntilNextMinute = $secondsUntilNextMinute
            TimeString = $timeString
        }
    } else {
        Write-Error "Failed to parse time from device date output: '$DateString'"
        return $null
    }
}

function Wait-DeviceMinute([int]$TargetMinute) {
    $currentTime = Get-DeviceTimeInfo
    if ($currentTime.CurrentMinute -ne $TargetMinute) {
        $remaining = $currentTime.SecondsUntilNextMinute
        Write-Host "Device is at minute $($currentTime.TimeString). Waiting ${remaining}s for minute ${TargetMinute}:00..." -ForegroundColor Cyan
        Start-Sleep -Seconds $remaining
    } else {
        Write-Host "Current minute ($($currentTime.TimeString)) already reached target start minute ($TargetMinute)." -ForegroundColor Yellow
    }
}

function New-TimeRangeAppRuleConfig(
    [string]$TargetPackage,
    [int]$StartMinute,
    [int]$EndMinute,
    [long]$AllowedMinutes = 0,
    [string]$TargetGroupId = "test-target-group-01",
    [string]$RuleId = "test-rule-timerange-01"
) {
    $groupTarget = New-TestAppGroup -GroupId $TargetGroupId -GroupName "테스트 타깃 앱" -Packages @($TargetPackage)

    $rule = [PSCustomObject]@{
        id = $RuleId
        name = "시간대 차단 테스트"
        isActive = $true
        weekdays = @(0, 1, 2, 3, 4, 5, 6)
        startMinute = $StartMinute
        endMinute = $EndMinute
        appGroupId = $TargetGroupId
        allowedMinutes = $AllowedMinutes
        usageConditionEnabled = $false
        usageConditionMinutes = 0
        contributorGroupConditionMinutes = [PSCustomObject]@{}
        contributorGroupIds = @()
        earnedAllowanceEnabled = $false
        guardianExtraTimeAllowed = $true
        timeRanges = @(
            [PSCustomObject]@{
                startMinute = $StartMinute
                endMinute = $EndMinute
            }
        )
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($TargetGroupId)
            excludedGroupIds = @()
        }
    }

    return [PSCustomObject]@{
        appGroups = @($groupTarget)
        appRules = @($rule)
    }
}

function New-DailyLimitAppRuleConfig(
    [string]$TargetPackage,
    [long]$AllowedMinutes = 1,
    [string]$RuleName = "일일 허용량 제한",
    [string]$TargetGroupId = "test-target-group-01",
    [string]$RuleId = "test-rule-dailylimit-01"
) {
    $groupTarget = New-TestAppGroup -GroupId $TargetGroupId -GroupName "테스트 타깃 앱" -Packages @($TargetPackage)

    $rule = [PSCustomObject]@{
        id = $RuleId
        name = $RuleName
        isActive = $true
        weekdays = @(0, 1, 2, 3, 4, 5, 6)
        startMinute = 0
        endMinute = 0
        appGroupId = $TargetGroupId
        allowedMinutes = $AllowedMinutes
        usageConditionEnabled = $false
        usageConditionMinutes = 0
        contributorGroupConditionMinutes = [PSCustomObject]@{}
        contributorGroupIds = @()
        earnedAllowanceEnabled = $false
        guardianExtraTimeAllowed = $true
        timeRanges = @(
            [PSCustomObject]@{
                startMinute = 0
                endMinute = 0
            }
        )
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($TargetGroupId)
            excludedGroupIds = @()
        }
    }

    return [PSCustomObject]@{
        appGroups = @($groupTarget)
        appRules = @($rule)
    }
}

function Test-AppRuleSkip($OverrideState, [string]$RuleId, [long]$MinSkipUntilMs = 0) {
    if (-not $OverrideState -or -not $RuleId) {
        return $false
    }
    $skips = if ($OverrideState.PSObject.Properties['skips']) {
        $OverrideState.skips
    } elseif ($OverrideState -is [System.Collections.IEnumerable] -and $OverrideState -isnot [string]) {
        $OverrideState
    } else {
        $null
    }
    if (-not $skips) {
        return $false
    }
    foreach ($skip in $skips) {
        if ($skip.ruleId -eq $RuleId) {
            $until = [long]$skip.skipUntilMs
            if ($until -gt $MinSkipUntilMs) {
                return $true
            }
        }
    }
    return $false
}

function Test-GuardianAuthConfig($SettingsOrAuth) {
    if (-not $SettingsOrAuth) {
        return $false
    }
    $auth = if ($SettingsOrAuth.PSObject.Properties['guardianAuthConfig']) {
        $SettingsOrAuth.guardianAuthConfig
    } else {
        $SettingsOrAuth
    }
    if (-not $auth) {
        return $false
    }
    $hasSalt = ($auth.PSObject.Properties['passwordSalt'] -and -not [string]::IsNullOrWhiteSpace($auth.passwordSalt))
    $hasVerifier = ($auth.PSObject.Properties['passwordVerifier'] -and -not [string]::IsNullOrWhiteSpace($auth.passwordVerifier))
    return ($hasSalt -and $hasVerifier)
}

function Set-DeviceGuardianAuthConfig($GuardianAuthConfig, [string]$PackageName = "neth.iecal.curbox.debug") {
    $settingsObj = Get-DeviceSettings -PackageName $PackageName -AsObject
    if (-not $settingsObj) {
        Write-Error "Failed to read settings.json to update guardianAuthConfig!"
        return $false
    }
    $settingsObj.guardianAuthConfig = $GuardianAuthConfig
    $jsonStr = $settingsObj | ConvertTo-Json -Depth 20 -Compress
    Push-TempStringToDevice -Content $jsonStr -RemotePath "/data/local/tmp/settings_guardian.json" | Out-Null
    adb shell "run-as $PackageName cp /data/local/tmp/settings_guardian.json files/datastore/settings.json" | Out-Null
    adb shell "run-as $PackageName chmod 660 files/datastore/settings.json" | Out-Null
    adb shell "rm -f /data/local/tmp/settings_guardian.json" | Out-Null

    adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName" | Out-Null
    Start-Sleep -Seconds 1
    return $true
}

function Get-DeviceProcessPid([string]$ProcessOutput = "", [string]$ProcessName = ":app_blocker_service") {
    if (-not $PSBoundParameters.ContainsKey('ProcessOutput')) {
        $ProcessOutput = (adb shell "ps -ef" | Out-String)
        if (-not $ProcessOutput -or $ProcessOutput -notmatch [regex]::Escape($ProcessName)) {
            $ProcessOutput = (adb shell "ps" | Out-String)
        }
    }
    if ([string]::IsNullOrWhiteSpace($ProcessOutput)) {
        return $null
    }
    foreach ($line in ($ProcessOutput -split "`r?`n")) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        $tokens = -split $trimmed
        if ($tokens[0] -eq "UID" -or $tokens[0] -eq "USER") { continue }
        if ($tokens[-1] -eq "grep" -or $tokens[-1] -eq "sh" -or ($tokens -contains "grep")) { continue }

        if ($tokens.Count -ge 2 -and $tokens[1] -match '^\d+$') {
            $cmd = $tokens[-1]
            if ($cmd -eq $ProcessName -or $cmd.EndsWith($ProcessName)) {
                return [int]$tokens[1]
            }
        }
    }
    return $null
}

function Test-AccessibilityServiceBound([string]$DumpsysOutput = "", [string]$ServiceName = "AppBlockerService") {
    if (-not $PSBoundParameters.ContainsKey('DumpsysOutput')) {
        $DumpsysOutput = (adb shell "dumpsys accessibility" | Out-String)
    }
    if ([string]::IsNullOrWhiteSpace($DumpsysOutput)) {
        return $false
    }

    if ($DumpsysOutput -match "Crashed services:\{([^}]*)\}") {
        $crashedContent = $matches[1]
        if ($crashedContent -match $ServiceName -or $crashedContent -match "Curbox App Blocker") {
            return $false
        }
    }

    if ($DumpsysOutput -match "Bound services:\{([^}]*)\}") {
        $boundContent = $matches[1]
        if ($boundContent -match $ServiceName -or $boundContent -match "Curbox App Blocker") {
            return $true
        }
    }

    return $false
}

function Stop-ServiceProcess([int]$TargetPid, [string]$ProcessName = ":app_blocker_service") {
    if ($TargetPid -le 0) {
        return $false
    }
    adb shell "kill -9 $TargetPid 2>/dev/null || su 0 kill -9 $TargetPid 2>/dev/null" | Out-Null
    Start-Sleep -Milliseconds 300
    $currentPid = Get-DeviceProcessPid -ProcessName $ProcessName
    if ($currentPid -eq $TargetPid) {
        adb shell "am crash $TargetPid" | Out-Null
    }
    return $true
}

function Restart-DeviceAccessibilityServiceIfEnabled(
    $AccessibilitySettings,
    [int]$TimeoutSeconds = 30,
    [string]$ProcessName = ":app_blocker_service"
) {
    if (-not $AccessibilitySettings) {
        Write-Error "Accessibility settings backup is required before restarting the service."
        return $null
    }

    $serviceComponent = "neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService"
    $enabledComponents = @(([string]$AccessibilitySettings.EnabledServices) -split ":" | Where-Object { $_ })
    if ([string]$AccessibilitySettings.AccessibilityEnabled -ne "1" -or $enabledComponents -notcontains $serviceComponent) {
        return [PSCustomObject]@{ Skipped = $true; ProcessId = $null }
    }

    $initialPid = Get-DeviceProcessPid -ProcessName $ProcessName
    if ($initialPid) {
        Stop-ServiceProcess -TargetPid $initialPid -ProcessName $ProcessName | Out-Null
    }

    $wait = [System.Diagnostics.Stopwatch]::StartNew()
    while ($wait.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $currentPid = Get-DeviceProcessPid -ProcessName $ProcessName
        if ($currentPid -and (!$initialPid -or $currentPid -ne $initialPid) -and (Test-AccessibilityServiceBound)) {
            return [PSCustomObject]@{ Skipped = $false; ProcessId = [int]$currentPid }
        }
        Start-Sleep -Milliseconds 500
    }
    return [PSCustomObject]@{ Skipped = $false; ProcessId = $null }
}

function Enable-AccessibilityService([string]$PackageName = "neth.iecal.curbox.debug", [string]$ServiceName = "neth.iecal.curbox.services.AppBlockerService") {
    $fullService = "$PackageName/$ServiceName"
    $enabledServices = Get-DeviceSecureSetting -Name "enabled_accessibility_services"
    $accessibilityEnabled = Get-DeviceSecureSetting -Name "accessibility_enabled"
    $serviceList = @($enabledServices -split ":" | Where-Object { $_ -and $_ -ne "null" })
    $needsServiceUpdate = $serviceList -notcontains $fullService
    $needsAccessibilityUpdate = $accessibilityEnabled -ne "1"

    if ($needsServiceUpdate) {
        $serviceList += $fullService
        Invoke-TestDeviceShell -Command "settings put secure enabled_accessibility_services $($serviceList -join ':')"
    }
    if ($needsAccessibilityUpdate) {
        Invoke-TestDeviceShell -Command "settings put secure accessibility_enabled 1"
    }
    if ($needsServiceUpdate -or $needsAccessibilityUpdate) {
        Start-Sleep -Seconds 2
    }
    return $true
}

function New-RolloverAppRuleConfig(
    [string]$TargetPackage,
    [int[]]$UnlockDays = @(0, 6),
    [long]$AllowedMinutes = 0,
    [string]$TargetGroupId = "test-target-group-01",
    [string]$RuleId = "test-rule-rollover-01"
) {
    $groupTarget = New-TestAppGroup -GroupId $TargetGroupId -GroupName "테스트 타깃 앱" -Packages @($TargetPackage)

    $rule = [PSCustomObject]@{
        id = $RuleId
        name = "이월 보호자 추가시간 테스트"
        isActive = $true
        weekdays = @(0, 1, 2, 3, 4, 5, 6)
        startMinute = 0
        endMinute = 0
        appGroupId = $TargetGroupId
        allowedMinutes = $AllowedMinutes
        usageConditionEnabled = $false
        usageConditionMinutes = 0
        contributorGroupConditionMinutes = [PSCustomObject]@{}
        contributorGroupIds = @()
        earnedAllowanceEnabled = $false
        guardianExtraTimeAllowed = $true
        rolloverEnabled = $true
        unlockDays = @($UnlockDays)
        timeRanges = @(
            [PSCustomObject]@{
                startMinute = 0
                endMinute = 0
            }
        )
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($TargetGroupId)
            excludedGroupIds = @()
        }
    }

    return [PSCustomObject]@{
        appGroups = @($groupTarget)
        appRules = @($rule)
    }
}

function New-RuleRolloverPool(
    [string]$RuleId,
    [long]$AccumulatedMinutes = 0,
    [string]$LastSettledUseDayId = ""
) {
    return [PSCustomObject]@{
        ruleId = $RuleId
        accumulatedMinutes = $AccumulatedMinutes
        lastSettledUseDayId = $LastSettledUseDayId
    }
}

function Test-AppRuleGuardianGrant(
    $OverrideState,
    [string]$RuleId,
    [long]$ExpectedGrantedMillis = 0,
    [bool]$IsFromAccumulatedPool = $false,
    [string]$ExpectedUseDayId = ""
) {
    if (-not $OverrideState -or -not $RuleId) {
        return $false
    }
    $grants = if ($OverrideState.PSObject.Properties['grants']) {
        $OverrideState.grants
    } elseif ($OverrideState -is [System.Collections.IEnumerable] -and $OverrideState -isnot [string]) {
        $OverrideState
    } else {
        $null
    }
    if (-not $grants) {
        return $false
    }
    foreach ($grant in $grants) {
        if ($grant.ruleId -eq $RuleId) {
            $millisMatch = ($ExpectedGrantedMillis -le 0 -or [long]$grant.grantedMillis -eq $ExpectedGrantedMillis)
            $fromPool = if ($grant.PSObject.Properties['isFromAccumulatedPool']) { [bool]$grant.isFromAccumulatedPool } else { $false }
            $poolMatch = ($fromPool -eq $IsFromAccumulatedPool)
            $useDayMatch = ([string]::IsNullOrEmpty($ExpectedUseDayId) -or $grant.useDayId -eq $ExpectedUseDayId)
            if ($millisMatch -and $poolMatch -and $useDayMatch) {
                return $true
            }
        }
    }
    return $false
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
    # Android IME Backspace keyevent 67 repeated per device-tests/AGENTS.md
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

function Get-RuleRolloverPool(
    $SettingsOrRolloverState,
    [string]$RuleId
) {
    if (-not $SettingsOrRolloverState -or -not $RuleId) {
        return $null
    }
    $rolloverState = if ($SettingsOrRolloverState.PSObject.Properties['appRuleRolloverState']) {
        $SettingsOrRolloverState.appRuleRolloverState
    } else {
        $SettingsOrRolloverState
    }
    if (-not $rolloverState -or -not $rolloverState.PSObject.Properties['pools']) {
        return $null
    }
    $pools = $rolloverState.pools
    if ($pools.PSObject.Properties[$RuleId]) {
        return $pools.$RuleId
    }
    return $null
}

function Set-DeviceRolloverState($RolloverState, [string]$PackageName = "neth.iecal.curbox.debug") {
    $settingsObj = Get-DeviceSettings -PackageName $PackageName -AsObject
    if (-not $settingsObj) {
        Write-Error "Failed to read settings.json to update appRuleRolloverState!"
        return $false
    }
    $settingsObj.appRuleRolloverState = $RolloverState
    $jsonStr = $settingsObj | ConvertTo-Json -Depth 20 -Compress
    Push-TempStringToDevice -Content $jsonStr -RemotePath "/data/local/tmp/settings_rollover.json" | Out-Null
    adb shell "run-as $PackageName cp /data/local/tmp/settings_rollover.json files/datastore/settings.json" | Out-Null
    adb shell "run-as $PackageName chmod 660 files/datastore/settings.json" | Out-Null
    adb shell "rm -f /data/local/tmp/settings_rollover.json" | Out-Null

    adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName" | Out-Null
    Start-Sleep -Seconds 1
    return $true
}

function Set-DeviceUseDayResetTime([int]$Hour, [int]$Minute, [string]$PackageName = "neth.iecal.curbox.debug") {
    if ($Hour -lt 0 -or $Hour -gt 23 -or $Minute -lt 0 -or $Minute -gt 59) {
        Write-Error "Use-day reset time must be a valid local clock time."
        return $false
    }

    $settingsObj = Get-DeviceSettings -PackageName $PackageName -AsObject
    if (-not $settingsObj) {
        Write-Error "Failed to read settings.json to update the use-day reset time!"
        return $false
    }

    $settingsObj.useDayResetHour = $Hour
    $settingsObj.useDayResetMinute = $Minute
    $jsonStr = $settingsObj | ConvertTo-Json -Depth 20 -Compress
    Push-TempStringToDevice -Content $jsonStr -RemotePath "/data/local/tmp/settings_use_day_reset.json" | Out-Null
    Invoke-TestDeviceShell -Command "run-as $PackageName cp /data/local/tmp/settings_use_day_reset.json files/datastore/settings.json"
    Invoke-TestDeviceShell -Command "run-as $PackageName chmod 660 files/datastore/settings.json"
    Invoke-TestDeviceShell -Command "rm -f /data/local/tmp/settings_use_day_reset.json"
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName"
    Invoke-TestDeviceShell -Command "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName"
    Start-Sleep -Seconds 1
    return $true
}


