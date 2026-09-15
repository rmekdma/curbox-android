<#
.SYNOPSIS
    Common device testing harness library for Curbox ADB-based E2E tests.
.DESCRIPTION
    Provides shared helper functions across device test scripts:
    - Assert-AdbDevice: Verify connected ADB device
    - Set-DeviceAwake: Control stay-awake state (svc power stayon true/false)
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
#>

function Assert-AdbDevice {
    $device = (adb devices | Select-String -Pattern "device$")
    if (-not $device) {
        Write-Error "No connected adb device found!"
    }
}

function Set-DeviceAwake([bool]$Awake = $true) {
    $val = if ($Awake) { "true" } else { "false" }
    adb shell "svc power stayon $val" | Out-Null
}

function Backup-DeviceSettings([string]$DestinationPath, [string]$PackageName = "neth.iecal.curbox.debug") {
    $rawSettings = (adb shell "run-as $PackageName cat files/datastore/settings.json" | Out-String).Trim().Trim([char]65279)
    if (-not $rawSettings -or $rawSettings -notmatch "\{") {
        Write-Error "Failed to read settings.json from device!"
        return $null
    }
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($DestinationPath, $rawSettings, $utf8NoBom)
    return $rawSettings
}

function Restore-DeviceSettings([string]$BackupPath, [string]$PackageName = "neth.iecal.curbox.debug") {
    if (-not (Test-Path $BackupPath)) {
        Write-Error "Backup file not found at: $BackupPath"
        return $false
    }
    adb push $BackupPath "/data/local/tmp/settings_backup.json" | Out-Null
    adb shell "run-as $PackageName cp /data/local/tmp/settings_backup.json files/datastore/settings.json" | Out-Null
    adb shell "run-as $PackageName chmod 660 files/datastore/settings.json" | Out-Null
    adb shell "rm -f /data/local/tmp/settings_backup.json" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p $PackageName" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName" | Out-Null
    return $true
}

function Inject-TestAppRules($AppRuleSnapshot, [string]$PackageName = "neth.iecal.curbox.debug") {
    adb shell "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p $PackageName" | Out-Null
    Start-Sleep -Milliseconds 500

    $rulesSnapshotJson = if ($AppRuleSnapshot -is [string]) {
        $AppRuleSnapshot
    } else {
        ($AppRuleSnapshot | ConvertTo-Json -Depth 20 -Compress)
    }
    $escapedJson = $rulesSnapshotJson.Replace('"', '\"')
    adb shell "am broadcast -a neth.iecal.curbox.action.APPLY_TEST_APP_RULES -p $PackageName --es extra_app_rules_json '$escapedJson'" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName" | Out-Null
    Start-Sleep -Seconds 1
}

function Clear-TestAppRules([string]$PackageName = "neth.iecal.curbox.debug") {
    adb shell "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p $PackageName" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName" | Out-Null
    adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName" | Out-Null
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
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $ui = Dump-UI
        if ($ui -match $Pattern) {
            return $ui
        }
        Start-Sleep -Milliseconds 500
    }
    return Dump-UI
}

function Get-NodeBounds([string]$Xml, [string]$Pattern) {
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

function Assert-WindowFocus([string]$ExpectedActivity) {
    $windowFocus = adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'" | Out-String
    $isMatch = $windowFocus -match $ExpectedActivity
    return @{
        Success = $isMatch
        RawFocus = $windowFocus.Trim()
    }
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
