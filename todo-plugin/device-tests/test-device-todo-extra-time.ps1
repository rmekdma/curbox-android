param(
    [string]$TargetPackage = "neth.iecal.curbox.apitester",
    [string]$DeviceId = "",
    [Parameter(Mandatory = $true)][string]$LearningApkPath,
    [Parameter(Mandatory = $true)][string]$ExpectedApkSha256,
    [Parameter(Mandatory = $true)][string]$BuildToolsDirectory,
    [ValidateSet('Daily', 'Weekend')][string]$Scenario = 'Daily'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/../../device-tests/lib/device-test-common.ps1"
. "$PSScriptRoot/lib/learning-device-test-common.ps1"
if ($Scenario -eq 'Weekend' -and $DeviceId -notmatch '^emulator-[0-9]+$') { throw 'Weekend requires an explicitly selected disposable emulator serial.' }
if ($DeviceId) { $env:ANDROID_SERIAL = $DeviceId }
Assert-AdbDevice
Assert-LearningFakeApk $LearningApkPath $ExpectedApkSha256 $BuildToolsDirectory
if ($TargetPackage -ne 'neth.iecal.curbox.apitester') { throw 'This test requires the harmless API tester.' }

# Requires the common harness and a verified fake debug APK. The caller owns fixture reset.
# Pass one mutable state object to setup and cleanup, including when setup throws.
function New-TodoSyntheticChildState {
    [pscustomobject]@{ RegistrationMayBeOwned = $false; FixtureWritten = $false }
}
function Start-TodoSyntheticLearning {
    Start-TestApp -PackageName 'neth.iecal.curbox.debug' -ActivityName 'neth.iecal.curbox.ui.activity.FragmentActivity' -IntentArguments '-f 0x10008000 --es fragment learning' | Out-Null
    $ui = Wait-For-UI 'id/signIn|id/dashboard|password="true"' 20
    if ($ui -match 'password="true"' -and $ui -notmatch 'id/signIn|id/dashboard') { Submit-TestPin; $ui = Wait-For-UI 'id/signIn|id/dashboard' 20 }
    return $ui
}
function Initialize-TodoSyntheticChild($State, [string]$ReportDate) {
    if ($ReportDate -notmatch '^\d{8}$') { throw 'Synthetic report date must be yyyyMMdd.' }
    if ((Get-LearningRegistrationCount) -ne 0) { throw 'Synthetic setup requires no existing learning registrations.' }
    $fixture = "{`"revision`":`"childsetup`",`"children`": [{`"name`":`"Child1`",`"users`":{`"MATH`":`"user1`"}}],`"reports`": [{`"user`":`"user1`",`"subject`":`"MATH`",`"date`":`"$ReportDate`",`"count`":2,`"learningSecondsByCategory`":{`"study`":180}}]}"
    $State.FixtureWritten = $true
    Set-LearningFixture $fixture
    $ui = Start-TodoSyntheticLearning
    if ($ui -match 'id/dashboard') {
        Refresh-SyntheticLearningDashboard $ui
    } else {
        Submit-SyntheticLearningSignIn $false
    }
    $ui = Wait-SyntheticChildAction 'Start watching' 20
    $State.RegistrationMayBeOwned = $true
    if (-not (Tap-Node $ui 'content-desc="Start watching"' 'Register synthetic child')) { throw 'Cannot register synthetic child.' }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 20) {
        if ((Get-LearningRegistrationCount) -eq 1) {
            $ui = Wait-SyntheticChildAction 'Stop watching' 20
            return
        }
        Start-Sleep -Seconds 1
    }
    throw 'Synthetic registration did not complete.'
}
function Connect-TodoSyntheticChild([bool]$RolloverEnabled = $true) {
    $ui = Start-TodoSyntheticLearning
    if (-not (Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/intervalSettings"' 'Learning settings')) { throw 'Cannot open learning settings.' }
    $ui = Wait-For-UI 'id/todo_connection_button' 15
    if (-not (Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/todo_connection_button"' 'Connect Todo')) { throw 'Cannot open Todo connection.' }
    $ui = Wait-For-UI 'TodoTestRule' 15
    [xml]$dialog = $ui
    $automatic = $dialog.SelectSingleNode('//node[@class="android.widget.CheckBox"]')
    $expected = $RolloverEnabled.ToString().ToLowerInvariant()
    if (-not $automatic -or $automatic.enabled -ne $expected -or $automatic.checked -ne $expected) { throw 'Automatic settlement did not match the rule rollover setting.' }
    if (-not (Tap-Node $ui 'resource-id="android:id/button1"' 'Confirm test rule')) { throw 'Cannot confirm test rule.' }
    $summary = 'text="(?:Connected|연결됨): Child1, TodoTestRule"'
    $ui = Wait-For-UI $summary 20
    if ($ui -notmatch $summary) { throw 'Learning settings did not show the saved child and rule.' }
    if (-not (Tap-Node $ui 'resource-id="android:id/button2"' 'Close learning settings')) { throw 'Cannot close learning settings.' }
}
function Remove-TodoSyntheticChild($State) {
    if (-not $State.RegistrationMayBeOwned) { return }
    $names = @(Get-LearningRegistrationNames)
    if ($names.Count -eq 0) { $State.RegistrationMayBeOwned = $false; return }
    if ($names.Count -ne 1 -or $names[0] -cne 'Child1') { throw 'Cannot safely remove registration: its identity changed.' }
    $ui = Start-TodoSyntheticLearning
    $ui = Wait-SyntheticChildAction 'Stop watching' 20
    [xml]$doc = $ui
    if (@($doc.SelectNodes('//node[@text="Child1"]')).Count -ne 1 -or @($doc.SelectNodes('//node[@content-desc="Stop watching"]')).Count -ne 1) { throw 'Cannot safely identify owned Child1 registration for cleanup.' }
    if (-not (Tap-Node $ui 'content-desc="Stop watching"' 'Remove synthetic registration')) { throw 'Cannot remove synthetic registration.' }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 20) {
        if ((Get-LearningRegistrationCount) -eq 0) { $State.RegistrationMayBeOwned = $false; return }
        Start-Sleep -Seconds 1
    }
    throw 'Synthetic registration remains.'
}

function Open-GrantForm {
    $ui = Wait-For-UI 'approval_add_time' 15
    if (-not (Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_add_time"' 'Change extra time')) { throw 'Cannot open extra time.' }
    return (Wait-For-UI 'offer_minutes_input' 15)
}
function Get-InputText([string]$Ui, [string]$Id) {
    [xml]$doc = $Ui
    $node = $doc.SelectSingleNode("//node[@resource-id='neth.iecal.curbox.debug:id/$Id']")
    if (-not $node) { throw "Missing $Id input." }
    return [string]$node.text
}
function Assert-Offer([string]$Ui, [string]$Expected) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt 20) {
        if ((Get-InputText $Ui 'additional_minutes_input') -eq '0' -and (Get-InputText $Ui 'offer_minutes_input') -eq $Expected) { return }
        Start-Sleep -Milliseconds 500
        $Ui = Dump-UI
    }
    throw "Expected manual 0 and Todo $Expected. UI: $Ui"
}
function Submit-TestPin {
    $ui = Wait-For-UI 'password="true"' 10
    [xml]$doc = $ui
    $node = $doc.SelectSingleNode('//node[@password="true"]')
    if (-not $node) { throw 'PIN field was not displayed.' }
    $bounds = Get-NodeBounds $ui 'password="true"'
    Invoke-TestDeviceShell -Command "input tap $($bounds.X) $($bounds.Y)"
    Invoke-TestDeviceShell -Command 'input keyevent 67 67 67 67 67 67 67 67'
    Invoke-TestDeviceShell -Command 'input text 1234'
    $ui = Dump-UI
    [xml]$doc = $ui
    $node = $doc.SelectSingleNode('//node[@password="true"]')
    if (-not $node -or ([string]$node.text).Length -ne 4) { throw 'PIN field did not contain four characters.' }
    Invoke-TestDeviceShell -Command 'input keyevent 4'
    $ui = Dump-UI
    if (-not (Tap-Node $ui 'resource-id="android:id/button1"' 'Continue')) { throw 'Cannot submit PIN.' }
}

function Invoke-TodoWeekendScenario {
    if ($DeviceId -notmatch '^emulator-[0-9]+$') { throw 'Weekend requires an explicitly selected disposable emulator serial.' }
    $env:ANDROID_SERIAL = $DeviceId
    Assert-AdbDevice
    Assert-LearningFakeApk $LearningApkPath $ExpectedApkSha256 $BuildToolsDirectory
    if ($TargetPackage -ne 'neth.iecal.curbox.apitester') { throw 'Weekend requires the API tester.' }
    if ((Get-TestDeviceShellOutput -Command 'getprop ro.kernel.qemu').Trim() -ne '1' -or (Get-TestDeviceShellOutput -Command 'id -u').Trim() -ne '0') { throw 'Weekend requires a rooted Android emulator.' }
    if ((Get-LearningRegistrationCount) -ne 0) { throw 'Remove existing fake learning registrations before running.' }
    $existingFixture = Get-TestDeviceShellOutput -Command "run-as neth.iecal.curbox.debug sh -c 'if [ -e files/synthetic-learning-fixture.json ]; then echo PRESENT; else echo ABSENT; fi'"
    if ($existingFixture.Trim() -ne 'ABSENT') { throw 'Remove the existing synthetic fixture before running.' }
    $trust = Get-TestDeviceShellOutput -Command 'dumpsys trust'
    if ($trust -notmatch '(?m)^\s*User [^\r\n]*\(current\):[^\r\n]*deviceLocked=0(?:,|\s|$)') { throw 'Unlock the emulator first.' }
    $original = Get-DeviceSettings -AsObject
    if (-not $original) { throw 'Cannot read settings.' }
    $originalEpoch = [long](Get-TestDeviceEpochTimeMs)
    $offset = (Get-TestDeviceShellOutput -Command 'date +%z').Trim()
    if ($originalEpoch -le 0 -or $offset -notmatch '^([+-])(\d{2})(\d{2})$') { throw 'Cannot read clock or UTC offset.' }
    $offsetMinutes = ([int]$Matches[2] * 60 + [int]$Matches[3]) * $(if ($Matches[1] -eq '-') { -1 } else { 1 })
    $autoTime = Get-DeviceGlobalSetting 'auto_time'
    $autoZone = Get-DeviceGlobalSetting 'auto_time_zone'
    if ($autoTime -notin @('0','1','null') -or $autoZone -notin @('0','1','null')) { throw 'Cannot preserve automatic clock settings.' }
    $local = (Get-TestDeviceShellOutput -Command 'date +%Y-%m-%d').Trim()
    $day = [datetime]::ParseExact($local, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
    $friday = $day.AddDays((([int][DayOfWeek]::Friday - [int]$day.DayOfWeek + 7) % 7))
    $resetMinutes = [int]$original.useDayResetHour * 60 + [int]$original.useDayResetMinute
    $boundary = $friday.AddDays(1).AddMinutes($resetMinutes)
    $fridayLocal = $boundary.AddMinutes(-4)
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $backup = Join-Path $env:TEMP ('curbox_weekend_' + [guid]::NewGuid().ToString('N') + '.json')
    $accessibility = Backup-DeviceAccessibilitySettings
    $backupReady = $false; $fixtureWritten = $false; $clockTouched = $false
    $childState = New-TodoSyntheticChildState
    $fakeBackup = $null
    $awakeSetting = Get-DeviceGlobalSetting 'stay_on_while_plugged_in'
    $seedPath = "$backup.seed"
    function Set-WeekendClock([datetime]$LocalTime) {
        $utc = [DateTimeOffset]::new($LocalTime, [TimeSpan]::FromMinutes($offsetMinutes)).ToUnixTimeSeconds()
        Invoke-TestDeviceShell -Command "date -s @$utc" | Out-Null
        $readback = [long](Get-TestDeviceEpochTimeMs)
        $actualLocal = (Get-TestDeviceShellOutput -Command 'date +%Y-%m-%d').Trim()
        if ([math]::Abs($readback - $utc * 1000) -gt 10000 -or $actualLocal -ne $LocalTime.ToString('yyyy-MM-dd') -or (Get-TestDeviceShellOutput -Command 'date +%z').Trim() -ne $offset) { throw 'Clock setting or local offset verification failed.' }
    }
    function Wait-WeekendState([scriptblock]$Condition, [string]$Description) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($watch.Elapsed.TotalSeconds -lt 45) {
            $state = Get-DeviceSettings -AsObject
            if (& $Condition $state) { return $state }
            Start-Sleep -Seconds 1
        }
        throw "Timed out waiting for $Description."
    }
    function Get-WeekendServicePid {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($watch.Elapsed.TotalSeconds -lt 20) {
            $id = Get-DeviceProcessPid -ProcessName 'neth.iecal.curbox.debug:app_blocker_service'
            if ($id) { return [int]$id }
            Start-Sleep -Milliseconds 500
        }
        throw 'Debug blocker service process did not start.'
    }
    function Restart-WeekendService {
        $oldProcess = Get-WeekendServicePid
        Disable-AccessibilityService | Out-Null
        Invoke-TestDeviceShell -Command 'am force-stop neth.iecal.curbox.debug' | Out-Null
        Start-TestApp -PackageName 'neth.iecal.curbox.debug' -ActivityName 'neth.iecal.curbox.ui.activity.FragmentActivity' -IntentArguments '-f 0x10008000 --es fragment learning' | Out-Null
        Enable-AccessibilityService | Out-Null
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($watch.Elapsed.TotalSeconds -lt 20) {
            $newProcess = Get-DeviceProcessPid -ProcessName 'neth.iecal.curbox.debug:app_blocker_service'
            if ($newProcess -and [int]$newProcess -ne $oldProcess) { return }
            Start-Sleep -Milliseconds 500
        }
        throw 'Debug blocker service PID did not change after restart.'
    }
    try {
        Backup-DeviceSettings -DestinationPath $backup | Out-Null; $backupReady = $true
        Disable-AccessibilityService | Out-Null
        Invoke-TestDeviceShell -Command 'am force-stop neth.iecal.curbox.debug' | Out-Null
        $fakeBackup = Backup-FakeLearningFiles
        Set-DeviceAwake $true
        $clockTouched = $true
        Set-DeviceGlobalSetting 'auto_time' '0'
        Set-DeviceGlobalSetting 'auto_time_zone' '0'
        Set-WeekendClock $fridayLocal
        $epoch = [long](Get-TestDeviceEpochTimeMs)
        $dayId = $friday.ToString('yyyy-MM-dd'); $reportDay = $friday.ToString('yyyyMMdd')
        if (@((Get-TodoAwardState $original).ledger | Where-Object { $_.childName -eq 'Child1' -and $_.useDayId -eq $dayId }).Count) { throw 'Synthetic child already has an award for Friday.' }
        $failure = "{`"revision`":`"weekendfail`",`"children`": [{`"name`":`"Child1`",`"users`":{`"MATH`":`"user1`"}}],`"reports`": [{`"user`":`"user1`",`"subject`":`"MATH`",`"date`":`"$reportDay`",`"failure`":`"NETWORK`"}]}"
        $success = "{`"revision`":`"weekendok`",`"children`": [{`"name`":`"Child1`",`"users`":{`"MATH`":`"user1`"}}],`"reports`": [{`"user`":`"user1`",`"subject`":`"MATH`",`"date`":`"$reportDay`",`"count`":2,`"learningSecondsByCategory`":{`"study`":180}}]}"
        $ruleId = 'test-todo-weekend'
        $snapshot = New-RolloverAppRuleConfig -TargetPackage $TargetPackage -RuleId $ruleId -AllowedMinutes 0
        $snapshot.appRules[0].name = 'TodoTestRule'
        $original | Add-Member -Force -NotePropertyName guardianAuthConfig -NotePropertyValue (New-GuardianPinAuthConfig -Pin '1234')
        $original | Add-Member -Force -NotePropertyName appRuleSnapshot -NotePropertyValue $snapshot
        $original | Add-Member -Force -NotePropertyName useDayGenerationStartedAtMs -NotePropertyValue $epoch
        Clear-TodoAwardState $original
        $original | Add-Member -Force -NotePropertyName appRuleOverrideState -NotePropertyValue ([pscustomobject]@{ useDayId = $dayId; useDayGenerationStartedAtMs = $epoch; skips = @(); grants = @([pscustomobject]@{ ruleId = $ruleId; useDayId = $dayId; grantedAtMs = $epoch; grantedMillis = 120000; isFromAccumulatedPool = $false; contributedMillisByPlugin = @{} }) })
        $original | Add-Member -Force -NotePropertyName appRuleRolloverState -NotePropertyValue ([pscustomobject]@{ pools = [pscustomobject]@{} })
        [IO.File]::WriteAllText($seedPath, ($original | ConvertTo-Json -Depth 30 -Compress), [Text.UTF8Encoding]::new($false))
        Restore-DeviceSettings -BackupPath $seedPath | Out-Null
        Start-TestApp -PackageName 'neth.iecal.curbox.debug' -ActivityName 'neth.iecal.curbox.ui.activity.FragmentActivity' -IntentArguments '-f 0x10008000 --es fragment learning' | Out-Null
        Enable-AccessibilityService | Out-Null
        $null = Get-WeekendServicePid
        Initialize-TodoSyntheticChild $childState $reportDay
        $fixtureWritten = $true
        Set-LearningFixture $failure
        Connect-TodoSyntheticChild
        $linked = Wait-WeekendState { param($s) (Get-TodoAwardState $s).connection.ruleId -eq $ruleId -and (Get-TodoAwardState $s).connection.childName -eq 'Child1' -and (Get-TodoAwardState $s).connection.connectedUseDayId -eq $dayId } 'connection'
        # The service starts before the boundary so its elapsed-realtime alarm stays valid.
        $liveProcess = Get-WeekendServicePid
        $failedBefore = Get-TodoSettlementFailureCount
        $boundaryEpoch = [DateTimeOffset]::new($boundary, [TimeSpan]::FromMinutes($offsetMinutes)).ToUnixTimeMilliseconds()
        $deadline = [Diagnostics.Stopwatch]::StartNew()
        while ([long](Get-TestDeviceEpochTimeMs) -lt $boundaryEpoch -and $deadline.Elapsed.TotalSeconds -lt 300) { Start-Sleep -Seconds 2 }
        if ([long](Get-TestDeviceEpochTimeMs) -lt $boundaryEpoch) { throw 'Device clock did not cross the reset boundary.' }
        $saturday = $friday.AddDays(1).ToString('yyyy-MM-dd')
        $state = Wait-WeekendState { param($s) $p = Get-RuleRolloverPool $s $ruleId; $p.lastSettledUseDayId -eq $saturday -and @((Get-TodoAwardState $s).pending | Where-Object { $_.useDayId -eq $dayId }).Count -eq 1 } 'ordinary reset and failed pending report'
        $failureWait = [Diagnostics.Stopwatch]::StartNew()
        while ((Get-TodoSettlementFailureCount) -le $failedBefore -and $failureWait.Elapsed.TotalSeconds -lt 45) { Start-Sleep -Seconds 1 }
        if ((Get-TodoSettlementFailureCount) -le $failedBefore) { throw 'Failed Todo report lookup was not observed.' }
        if ((Get-WeekendServicePid) -ne $liveProcess) { throw 'Service restarted before the live reset was observed.' }
        $base = [long](Get-RuleRolloverPool $state $ruleId).accumulatedMinutes
        if ($base -ne 2 -or @((Get-TodoAwardState $state).ledger | Where-Object { $_.useDayId -eq $dayId -and $_.settled }).Count) { throw 'Friday reset or failed report state incorrect.' }
        Restart-WeekendService
        $state = Wait-WeekendState { param($s) @((Get-TodoAwardState $s).pending | Where-Object { $_.useDayId -eq $dayId }).Count -eq 1 } 'pending after service restart'
        if ([long](Get-RuleRolloverPool $state $ruleId).accumulatedMinutes -ne $base) { throw 'Unexpected credit while report fails.' }
        Set-LearningFixture $success
        Restart-WeekendService
        $state = Wait-WeekendState { param($s) $p = Get-RuleRolloverPool $s $ruleId; [long]$p.accumulatedMinutes -eq ($base + 3) -and @((Get-TodoAwardState $s).pending | Where-Object { $_.useDayId -eq $dayId }).Count -eq 0 -and @((Get-TodoAwardState $s).ledger | Where-Object { $_.useDayId -eq $dayId -and $_.settled }).Count -eq 1 } 'one Todo credit'
        Restart-WeekendService
        Start-Sleep -Seconds 3
        if ([long](Get-RuleRolloverPool (Get-DeviceSettings -AsObject) $ruleId).accumulatedMinutes -ne ($base + 3)) { throw 'Report was credited more than once.' }
        Start-TestApp -PackageName $TargetPackage
        if (-not (Wait-ForWindowFocus -ExpectedActivity 'GuardianApprovalActivity' -TimeoutSeconds 15).Success) { throw 'Saturday blocker missing.' }
        $ui = Wait-For-UI 'approval_use_accumulated_time' 15
        if (-not (Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_use_accumulated_time"' 'Use pool')) { throw 'Pool action missing.' }
        $ui = Wait-For-UI 'accumulated_minutes_input' 15
        $bounds = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/accumulated_minutes_input"'
        Invoke-TestDeviceShell -Command "input tap $($bounds.X) $($bounds.Y)" | Out-Null
        Invoke-TestDeviceShell -Command 'input keyevent 67 67 67 67 67' | Out-Null
        Invoke-TestDeviceShell -Command 'input text 1' | Out-Null
        Invoke-TestDeviceShell -Command 'input keyevent 4' | Out-Null
        $ui = Dump-UI
        if (-not (Tap-Node $ui 'resource-id="android:id/button1"' 'Apply pool')) { throw 'Cannot apply pool grant.' }
        Submit-TestPin
        $null = Wait-WeekendState { param($s) [long](Get-RuleRolloverPool $s $ruleId).accumulatedMinutes -eq ($base + 2) -and @($s.appRuleOverrideState.grants | Where-Object { $_.ruleId -eq $ruleId -and $_.isFromAccumulatedPool -and [long]$_.grantedMillis -eq 60000 }).Count -eq 1 } 'pool debit and grant'
        Invoke-TestDeviceShell -Command 'input keyevent 3' | Out-Null
        Set-WeekendClock ($boundary.AddDays(2).AddMinutes(1))
        Restart-WeekendService
        $monday = $friday.AddDays(3).ToString('yyyy-MM-dd')
        $null = Wait-WeekendState { param($s) $p = Get-RuleRolloverPool $s $ruleId; $p.lastSettledUseDayId -eq $monday -and [long]$p.accumulatedMinutes -eq 0 } 'Monday pool expiry'
        Write-Success 'Weekend failed report, recovery, pool approval, and expiry verified.'
    } finally {
        # Attempt each cleanup independently, including clock restoration even if app cleanup fails.
        $cleanupErrors = @()
        try { Remove-TodoSyntheticChild $childState } catch { $cleanupErrors += $_.Exception.Message }
        if ($backupReady) {
            try { Clear-TestAppRules | Out-Null } catch { $cleanupErrors += $_.Exception.Message }
        }
        if ($clockTouched) {
            try {
                $restore = [long][math]::Floor(($originalEpoch + $clock.ElapsedMilliseconds) / 1000)
                Invoke-TestDeviceShell -Command "date -s @$restore" | Out-Null
                $actual = [long](Get-TestDeviceEpochTimeMs)
                if ([math]::Abs($actual - $restore * 1000) -gt 10000) { throw 'Clock restore verification failed.' }
            } catch { $cleanupErrors += $_ }
            foreach ($item in @(@('auto_time', $autoTime), @('auto_time_zone', $autoZone))) {
                try {
                    Set-DeviceGlobalSetting $item[0] $item[1]
                } catch { $cleanupErrors += $_ }
            }
        }
        if ($fixtureWritten -or $childState.FixtureWritten) { try { Reset-LearningFixture } catch { $cleanupErrors += $_.Exception.Message } }
        $cold = $false
        try { Disable-AccessibilityService | Out-Null; Invoke-TestDeviceShell -Command 'am force-stop neth.iecal.curbox.debug' | Out-Null; $cold = $true } catch { $cleanupErrors += $_.Exception.Message }
        if ($fakeBackup -and $cold) { try { Restore-FakeLearningFiles $fakeBackup } catch { $cleanupErrors += $_.Exception.Message } }
        if ($backupReady -and $cold) { try { Restore-DeviceSettings -BackupPath $backup | Out-Null } catch { $cleanupErrors += $_ } }
        if ($backupReady -and -not $cold) { $cleanupErrors += 'Settings restoration skipped because Debug was not stopped.' }
        try { Set-DeviceGlobalSetting 'stay_on_while_plugged_in' $awakeSetting } catch { $cleanupErrors += $_.Exception.Message }
        if ($cold -and @(([string]$accessibility.EnabledServices -split ':') | Where-Object { $_ -ceq 'neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService' }).Count -eq 1) {
            try { Start-TestApp -PackageName 'neth.iecal.curbox.debug' | Out-Null } catch { $cleanupErrors += $_.Exception.Message }
        }
        try { Restore-DeviceAccessibilitySettings -AccessibilitySettings $accessibility } catch { $cleanupErrors += $_ }
        try { Invoke-TestDeviceShell -Command "am force-stop $TargetPackage" | Out-Null; Invoke-TestDeviceShell -Command 'input keyevent 3' | Out-Null } catch { $cleanupErrors += $_ }
        if ($backupReady -and $cleanupErrors.Count -eq 0) { Remove-Item -LiteralPath $backup -Force }
        if (Test-Path -LiteralPath $seedPath) { Remove-Item -LiteralPath $seedPath -Force }
        if ($cleanupErrors.Count) { throw "Weekend cleanup failed; settings backup: $backup; private fake backup ID: $fakeBackup. $($cleanupErrors -join '; ')" }
    }
}

if ($Scenario -eq 'Weekend') { Invoke-TodoWeekendScenario; return }

# This precondition avoids touching an unrelated learning registration.
if ((Get-LearningRegistrationCount) -ne 0) { throw 'Remove existing fake learning registrations before running.' }
$existingFixture = Get-TestDeviceShellOutput -Command "run-as neth.iecal.curbox.debug sh -c 'if [ -e files/synthetic-learning-fixture.json ]; then echo PRESENT; else echo ABSENT; fi'"
if ($existingFixture.Trim() -ne 'ABSENT') { throw 'Remove the existing synthetic fixture before running.' }
$trust = Get-TestDeviceShellOutput -Command 'dumpsys trust'
if ($trust -notmatch '(?m)^\s*User [^\r\n]*\(current\):[^\r\n]*deviceLocked=0(?:,|\s|$)') { throw 'Unlock the device before running.' }
$epoch = Get-TestDeviceEpochTimeMs
if (-not $epoch) { throw 'Cannot read device time.' }
$dateRaw = (Get-TestDeviceShellOutput -Command "date +'%Y-%m-%d %H %M'").Trim()
if ($dateRaw -notmatch '^(\d{4}-\d{2}-\d{2})\s+(\d{1,2})\s+(\d{1,2})$') { throw 'Cannot read device local date.' }
$calendarDay = [datetime]::ParseExact($Matches[1], 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
$minute = [int]$Matches[2] * 60 + [int]$Matches[3]
$original = Get-DeviceSettings -AsObject
if (-not $original) { throw 'Cannot read Curbox settings.' }
$reset = [int]$original.useDayResetHour * 60 + [int]$original.useDayResetMinute
if ([math]::Min(([math]::Abs($minute - $reset)), (1440 - [math]::Abs($minute - $reset))) -lt 5) { throw 'Run at least five minutes away from the use day boundary.' }
$useDay = if ($minute -lt $reset) { $calendarDay.AddDays(-1) } else { $calendarDay }
$dayId = $useDay.ToString('yyyy-MM-dd')
$reportDay = $useDay.ToString('yyyyMMdd')
if (@((Get-TodoAwardState $original).ledger | Where-Object { $_.childName -eq 'Child1' -and $_.useDayId -eq $dayId }).Count -ne 0) { throw 'The synthetic child already has an award on this use day.' }
$fixture = "{`"revision`":`"daily`",`"children`": [{`"name`":`"Child1`",`"users`":{`"MATH`":`"user1`"}}],`"reports`": [{`"user`":`"user1`",`"subject`":`"MATH`",`"date`":`"$reportDay`",`"count`":2,`"learningSecondsByCategory`":{`"study`":150,`"etc`":120}}]}"
$backup = Join-Path $env:TEMP ('curbox_todo_extra_' + [guid]::NewGuid().ToString('N') + '.json')
$seed = "$backup.seed"
$accessibility = Backup-DeviceAccessibilitySettings
$awakeSetting = Get-DeviceGlobalSetting 'stay_on_while_plugged_in'
if ($awakeSetting -notmatch '^(null|\d+)$') { throw 'Cannot read the stay awake setting.' }
$fixtureWritten = $false
$backupReady = $false

$childState = New-TodoSyntheticChildState
$fakeBackup = $null
try {
    Backup-DeviceSettings -DestinationPath $backup | Out-Null
    $backupReady = $true
    Set-DeviceAwake $true
    Invoke-TestDeviceShell -Command 'input keyevent 224'
    Invoke-TestDeviceShell -Command 'input keyevent 82'
    Disable-AccessibilityService | Out-Null
    Invoke-TestDeviceShell -Command 'input keyevent 3'
    Invoke-TestDeviceShell -Command 'am kill neth.iecal.curbox.debug'
    $stopTimer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $mainPid = Get-DeviceProcessPid -ProcessName 'neth.iecal.curbox.debug'
        $servicePid = Get-DeviceProcessPid -ProcessName 'neth.iecal.curbox.debug:app_blocker_service'
        if (-not $mainPid -and -not $servicePid) { break }
        Start-Sleep -Milliseconds 500
    } while ($stopTimer.Elapsed.TotalSeconds -lt 15)
    if ($mainPid -or $servicePid) { throw 'Debug processes did not stop before fixture setup.' }
    $fakeBackup = Backup-FakeLearningFiles
    $ruleId = 'test-todo-extra-time'
    $snapshot = New-RolloverAppRuleConfig -TargetPackage $TargetPackage -RuleId $ruleId -AllowedMinutes 0
    $snapshot.appRules[0].rolloverEnabled = $false
    $snapshot.appRules[0].name = 'TodoTestRule'
    $original | Add-Member -Force -NotePropertyName guardianAuthConfig -NotePropertyValue (New-GuardianPinAuthConfig -Pin '1234')
    $original | Add-Member -Force -NotePropertyName appRuleSnapshot -NotePropertyValue $snapshot
    $original | Add-Member -Force -NotePropertyName useDayGenerationStartedAtMs -NotePropertyValue $epoch
    Clear-TodoAwardState $original
    [IO.File]::WriteAllText($seed, ($original | ConvertTo-Json -Depth 30 -Compress), [Text.UTF8Encoding]::new($false))
    Restore-DeviceSettings -BackupPath $seed | Out-Null
    Start-TestApp -PackageName 'neth.iecal.curbox.debug' -ActivityName 'neth.iecal.curbox.ui.activity.FragmentActivity' -IntentArguments '-f 0x10008000 --es fragment learning'
    Enable-AccessibilityService | Out-Null
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $bound = $false
    while ($timer.Elapsed.TotalSeconds -lt 15) {
        if (Test-AccessibilityServiceBound) { $bound = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $bound) { throw 'Debug accessibility service did not bind.' }
    Initialize-TodoSyntheticChild $childState $reportDay
    $fixtureWritten = $true
    Set-LearningFixture $fixture
    Connect-TodoSyntheticChild $false
    Start-Sleep -Seconds 2
    $linked = Get-DeviceSettings -AsObject
    if ((Get-TodoAwardState $linked).connection.ruleId -ne $ruleId -or (Get-TodoAwardState $linked).connection.childName -ne 'Child1') { throw 'Synthetic connection was not saved.' }
    Start-TestApp -PackageName $TargetPackage
    $focus = Wait-ForWindowFocus -ExpectedActivity 'GuardianApprovalActivity' -TimeoutSeconds 15
    if (-not $focus.Success) { throw 'The real blocker did not open guardian approval.' }
    $ui = Open-GrantForm
    Assert-Offer $ui '3'
    $ui = Dump-UI
    if (-not (Tap-Node $ui 'resource-id="android:id/button2"' 'Cancel')) { throw 'Cannot cancel grant.' }
    Start-Sleep -Seconds 1
    $cancelled = Get-DeviceSettings -AsObject
    if (@((Get-TodoAwardState $cancelled).ledger | Where-Object { $_.childName -eq 'Child1' -and $_.useDayId -eq $dayId }).Count -ne 0 -or @($cancelled.appRuleOverrideState.grants | Where-Object { $_.ruleId -eq $ruleId }).Count -ne 0) { throw 'Cancel unexpectedly granted time.' }
    $ui = Open-GrantForm
    Assert-Offer $ui '3'
    $input = Get-NodeBounds $ui 'resource-id="neth.iecal.curbox.debug:id/offer_minutes_input"'
    Invoke-TestDeviceShell -Command "input tap $($input.X) $($input.Y)"
    Invoke-TestDeviceShell -Command 'input keyevent 67 67 67 67 67'
    Invoke-TestDeviceShell -Command 'input text 1'
    Invoke-TestDeviceShell -Command 'input keyevent 4'
    $ui = Dump-UI
    if ((Get-InputText $ui 'offer_minutes_input') -ne '1') { throw 'Could not enter the partial Todo grant.' }
    if (-not (Tap-Node $ui 'resource-id="android:id/button1"' 'Apply')) { throw 'Cannot apply grant.' }
    Submit-TestPin
    Start-Sleep -Seconds 3
    $granted = Get-DeviceSettings -AsObject
    $grant = @($granted.appRuleOverrideState.grants | Where-Object { $_.ruleId -eq $ruleId })
    if ($grant.Count -ne 1 -or [long]$grant[0].grantedMillis -ne 60000 -or [long]$grant[0].contributedMillisByPlugin.todo -ne 60000 -or $grant[0].isFromAccumulatedPool) { throw 'Incorrect grant or source.' }
    $ledger = @((Get-TodoAwardState $granted).ledger | Where-Object { $_.childName -eq 'Child1' -and $_.useDayId -eq $dayId })
    if ($ledger.Count -ne 1 -or [long]$ledger[0].grantedMinutes -ne 1) { throw 'Incorrect Todo ledger.' }
    $focus = Wait-ForWindowFocus -ExpectedActivity $TargetPackage -TimeoutSeconds 15
    if (-not $focus.Success) { throw 'Approved allowance did not unblock the target.' }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $blocked = $false
    while ($timer.Elapsed.TotalSeconds -lt 100) {
        if ((Assert-WindowFocus -ExpectedActivity 'GuardianApprovalActivity' -PassThru).Success) { $blocked = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $blocked -or $timer.Elapsed.TotalSeconds -lt 50) { throw 'Allowance was not consumed before the next lock.' }
    $ui = Open-GrantForm
    Assert-Offer $ui '2'
    Write-Success 'Daily Todo approval, source, allowance, and remaining offer verified.'
} finally {
    $cleanupErrors = @()
    try { Remove-TodoSyntheticChild $childState } catch { $cleanupErrors += $_.Exception.Message }
    if ($backupReady) { try { Clear-TestAppRules | Out-Null } catch { $cleanupErrors += $_.Exception.Message } }
    try { if ($fixtureWritten -or $childState.FixtureWritten) { Reset-LearningFixture } } catch { $cleanupErrors += $_.Exception.Message }
    $cold = $false
    try { Disable-AccessibilityService | Out-Null; Invoke-TestDeviceShell -Command 'am force-stop neth.iecal.curbox.debug' | Out-Null; $cold = $true } catch { $cleanupErrors += $_.Exception.Message }
    try { if ($fakeBackup -and $cold) { Restore-FakeLearningFiles $fakeBackup } } catch { $cleanupErrors += $_.Exception.Message }
    if ($backupReady -and $cold) { try { Restore-DeviceSettings -BackupPath $backup | Out-Null } catch { $cleanupErrors += $_.Exception.Message } }
    if ($backupReady -and -not $cold) { $cleanupErrors += 'Settings restoration skipped because Debug was not stopped.' }
    try {
        Set-DeviceGlobalSetting 'stay_on_while_plugged_in' $awakeSetting
    } catch { $cleanupErrors += $_.Exception.Message }
    if ($cold -and @(([string]$accessibility.EnabledServices -split ':') | Where-Object { $_ -ceq 'neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService' }).Count -eq 1) {
        try { Start-TestApp -PackageName 'neth.iecal.curbox.debug' | Out-Null } catch { $cleanupErrors += $_.Exception.Message }
    }
    try { Restore-DeviceAccessibilitySettings -AccessibilitySettings $accessibility } catch { $cleanupErrors += $_.Exception.Message }
    try { Invoke-TestDeviceShell -Command "am force-stop $TargetPackage" } catch { $cleanupErrors += $_.Exception.Message }
    try { Invoke-TestDeviceShell -Command 'input keyevent 3' } catch { $cleanupErrors += $_.Exception.Message }
    if ($cleanupErrors.Count -eq 0 -and $backupReady) { Remove-Item -LiteralPath $backup -Force }
    if (Test-Path -LiteralPath $seed) { Remove-Item -LiteralPath $seed -Force }
    if ($cleanupErrors.Count) { throw "Cleanup failed; settings backup: $backup; private fake backup ID: $fakeBackup. $($cleanupErrors -join '; ')" }
}
