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
    - New-ContributorAppRuleConfig: Generate AppRuleSnapshot with contributor condition rule
    - Set-DeviceUsageGeneration: Reset useDay session generation to start fresh tracking epoch
#>

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

function Set-DeviceAwake([bool]$Awake = $true) {
    $val = if ($Awake) { "true" } else { "false" }
    adb shell "svc power stayon $val" | Out-Null
    if ($Awake) {
        $screenState = (adb shell "dumpsys display | grep -i mScreenState" | Out-String)
        if ($screenState -match "OFF") {
            adb shell "input keyevent 26" | Out-Null # POWER
            Start-Sleep -Milliseconds 500
        }
        adb shell "input keyevent 224" | Out-Null # WAKEUP
        adb shell "wm dismiss-keyguard" | Out-Null
        adb shell "input keyevent 82" | Out-Null # UNLOCK / MENU
        Start-Sleep -Milliseconds 500
    }
}

function Push-TempStringToDevice([string]$Content, [string]$RemotePath) {
    $tempLocal = [System.IO.Path]::GetTempFileName()
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($tempLocal, $Content, $utf8NoBom)
    adb push $tempLocal $RemotePath | Out-Null
    Remove-Item $tempLocal -Force -ErrorAction SilentlyContinue
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
    Clear-TestAppRules -PackageName $PackageName
    return $true
}

function Set-DeviceUsageGeneration([long]$GenerationStartedAtMs = 0, [string]$PackageName = "neth.iecal.curbox.debug") {
    if ($GenerationStartedAtMs -le 0) {
        $GenerationStartedAtMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    }
    $rawSettings = (adb shell "run-as $PackageName cat files/datastore/settings.json" | Out-String).Trim().Trim([char]65279)
    if ($rawSettings -and $rawSettings -match "\{") {
        $settingsObj = $rawSettings | ConvertFrom-Json
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

function Inject-TestAppRules($AppRuleSnapshot, [string]$PackageName = "neth.iecal.curbox.debug", [long]$UsageGenerationStartedAtMs = 0) {
    adb shell "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p $PackageName" | Out-Null
    Start-Sleep -Milliseconds 500

    if ($UsageGenerationStartedAtMs -gt 0) {
        Set-DeviceUsageGeneration -GenerationStartedAtMs $UsageGenerationStartedAtMs -PackageName $PackageName | Out-Null
    }

    $rulesSnapshotJson = if ($AppRuleSnapshot -is [string]) {
        $AppRuleSnapshot
    } else {
        ($AppRuleSnapshot | ConvertTo-Json -Depth 20 -Compress)
    }

    Push-TempStringToDevice -Content $rulesSnapshotJson -RemotePath "/data/local/tmp/app_rules_inject.json"

    $shScript = "CONTENT=`$(cat /data/local/tmp/app_rules_inject.json)`nam broadcast -a neth.iecal.curbox.action.APPLY_TEST_APP_RULES -p $PackageName --es extra_app_rules_json `"`$CONTENT`"`n"
    Push-TempStringToDevice -Content $shScript -RemotePath "/data/local/tmp/inject.sh"

    adb shell "chmod 755 /data/local/tmp/inject.sh; /data/local/tmp/inject.sh" | Out-Null
    adb shell "rm -f /data/local/tmp/app_rules_inject.json /data/local/tmp/inject.sh" | Out-Null
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

function New-ContributorAppRuleConfig(
    [string]$TargetPackage,
    [string]$ContributorPackage = "com.initialcoms.ridi",
    [long]$RequiredMinutes = 1,
    [long]$AllowedMinutes = 1440,
    [string]$TargetGroupId = "test-target-group-01",
    [string]$ContributorGroupId = "test-contrib-group-01",
    [string]$RuleId = "test-rule-01"
) {
    $groupTarget = [PSCustomObject]@{
        id = $TargetGroupId
        name = "테스트 타깃 앱"
        selectedPackages = @($TargetPackage)
        membershipHistory = @(
            [PSCustomObject]@{
                effectiveFromMs = [long]::MinValue
                selectedPackages = @($TargetPackage)
            }
        )
    }

    $groupContrib = [PSCustomObject]@{
        id = $ContributorGroupId
        name = "학습"
        selectedPackages = @($ContributorPackage)
        membershipHistory = @(
            [PSCustomObject]@{
                effectiveFromMs = [long]::MinValue
                selectedPackages = @($ContributorPackage)
            }
        )
    }

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

