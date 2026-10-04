<#
.SYNOPSIS
    End-to-end test script for Guardian Rollover Extra Time on a connected device via ADB.
.DESCRIPTION
    1. Backs up original settings.json and acquires wake lock.
    2. Injects Guardian PIN authentication config (PIN: 1234).
    3. Injects test AppRule with rollover enabled (unlockDays includes today) and accumulated pool (20m).
    4. Launches target package and verifies GuardianApprovalActivity is displayed.
    5. Verifies "누적 시간 사용 (20분 사용 가능)" button is exposed on approval surface.
    6. Opens accumulated grant dialog, verifies 20 minutes is prefilled, and performs 1-Tap approval.
    7. Authenticates with Guardian PIN.
    8. Verifies DataStore records pool deduction and AppRuleGuardianGrant(isFromAccumulatedPool = true).
    9. Verifies target app is unblocked and running in foreground.
    10. Verifies expiration/reset of pool (to 0) on restore boundary transition (unlock day -> accrual day).
    11. Restores original settings.json and cleans up in finally block.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "",
    [string]$Pin = "1234",
    [string]$DeviceId = ""
)

$ErrorActionPreference = "Stop"

# Dot-source common device testing harness
. "$PSScriptRoot/lib/device-test-common.ps1"

Assert-AdbDevice
$accessibilityBackup = Backup-DeviceAccessibilitySettings

$tmpDir = Join-Path $env:TEMP "curbox_rollover_test"
if (-not (Test-Path $tmpDir)) {
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
}

$backupFile = Join-Path $tmpDir "settings_backup.json"
$passedAll = $true

function Write-RolloverBoundaryDiagnostics([string]$Phase) {
    $clockRaw = (Get-TestDeviceShellOutput -Command "date +'%Y-%m-%d %H %M %S'").Trim()
    $settings = Get-DeviceSettings -AsObject
    $pool = Get-RuleRolloverPool -SettingsOrRolloverState $settings -RuleId "test-rule-rollover-01"
    $rule = @($settings.appRuleSnapshot.appRules | Where-Object { $_.id -eq "test-rule-rollover-01" }) | Select-Object -First 1
    $currentUseDayId = "unavailable"
    if ($settings -and $clockRaw -match '^(\d{4}-\d{2}-\d{2})\s+(\d{1,2})\s+(\d{1,2})\s+(\d{1,2})$') {
        $clockDate = [System.DateTime]::ParseExact($matches[1], "yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture)
        $clockMinute = ([int]$matches[2] * 60) + [int]$matches[3]
        $resetMinute = ([int]$settings.useDayResetHour * 60) + [int]$settings.useDayResetMinute
        if ($clockMinute -lt $resetMinute) { $clockDate = $clockDate.AddDays(-1) }
        $currentUseDayId = $clockDate.ToString("yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture)
    }

    $ruleSummary = if ($rule) {
        "id=$($rule.id) rollover=$($rule.rolloverEnabled) guardianExtraTime=$($rule.guardianExtraTimeAllowed) unlockDays=$(@($rule.unlockDays) -join ',')"
    } else {
        "missing"
    }
    $poolSummary = if ($pool) {
        "minutes=$($pool.accumulatedMinutes) lastSettled=$($pool.lastSettledUseDayId)"
    } else {
        "missing"
    }
    Write-Host "[DIAGNOSTIC][$Phase] deviceClock='$clockRaw' currentUseDay='$currentUseDayId' reset=$($settings.useDayResetHour):$($settings.useDayResetMinute) generation=$($settings.useDayGenerationStartedAtMs) ruleCount=$(@($settings.appRuleSnapshot.appRules).Count) testRule=[$ruleSummary] pool=[$poolSummary]" -ForegroundColor DarkGray

    $alarmDump = Get-TestDeviceShellOutput -Command "dumpsys alarm"
    $alarmLines = @($alarmDump -split "`r?`n")
    $alarmStatsIndex = [Array]::FindIndex([string[]]$alarmLines, [Predicate[string]]{ param($line) $line -match '^\s*Alarm Stats:' })
    if ($alarmStatsIndex -lt 0) { $alarmStatsIndex = $alarmLines.Count }
    $activeWakeIndexes = @(
        for ($i = 0; $i -lt $alarmStatsIndex; $i++) {
            if ($alarmLines[$i] -match 'ACTION_APP_RULE_WAKE') { $i }
        }
    )
    Write-Host "[DIAGNOSTIC][$Phase] active wake alarm blocks:" -ForegroundColor DarkGray
    if ($activeWakeIndexes.Count -eq 0) {
        Write-Host "  none" -ForegroundColor DarkGray
    } else {
        foreach ($index in $activeWakeIndexes) {
            $start = [Math]::Max(0, $index - 1)
            $end = [Math]::Min($alarmStatsIndex - 1, $index + 5)
            $timerLine = @($alarmLines[$start..$end] | Where-Object { $_ -match 'type=.*origWhen=' } | Select-Object -First 1)
            $operationLine = @($alarmLines[$start..$end] | Where-Object { $_ -match '^\s*operation=' } | Select-Object -First 1)
            $timerSummary = if ($timerLine.Count -gt 0) { $timerLine[0].Trim() } else { "requested time unavailable" }
            $operationSummary = if ($operationLine.Count -gt 0) { $operationLine[0].Trim() } else { "operation unavailable" }
            Write-Host "  $($alarmLines[$index].Trim()); $timerSummary; $operationSummary" -ForegroundColor DarkGray
        }
    }
    $wakeHistory = @($alarmLines | Where-Object { $_ -match 'ACTION_APP_RULE_WAKE' } | Select-Object -Unique | Select-Object -Last 6)
    Write-Host "[DIAGNOSTIC][$Phase] recent wake alarm history:" -ForegroundColor DarkGray
    foreach ($line in $wakeHistory) { Write-Host "  $($line.Trim())" -ForegroundColor DarkGray }

    $serviceDump = Get-TestDeviceShellOutput -Command "dumpsys activity services neth.iecal.curbox.debug"
    $serviceLines = @($serviceDump -split "`r?`n" | Where-Object {
        $_ -match 'ServiceRecord|ProcessRecord|isForeground|startRequested|lastActivity|app=' -and
        $_ -match 'AppBlockerService|app_blocker_service|neth\.iecal\.curbox\.debug'
    } | Select-Object -First 12)
    Write-Host "[DIAGNOSTIC][$Phase] app service state:" -ForegroundColor DarkGray
    if ($serviceLines.Count -eq 0) { Write-Host "  no matching service record" -ForegroundColor DarkGray }
    foreach ($line in $serviceLines) { Write-Host "  $($line.Trim())" -ForegroundColor DarkGray }

    $recentLogs = Get-TestDeviceShellOutput -Command "logcat -d -v time -t 2000"
    $settlementLogLines = @($recentLogs -split "`r?`n" | Where-Object {
        $_ -match 'AppRuleBlocker|SerializedDecisionWorker|AndroidAppRuleWakeScheduler|AppRuleRollover|BootReceiver|CrashLogger|DataStore' -and
        $_ -notmatch 'settings\.json|pin|password|token='
    } | Select-Object -Last 25)
    Write-Host "[DIAGNOSTIC][$Phase] relevant app log lines:" -ForegroundColor DarkGray
    foreach ($line in $settlementLogLines) { Write-Host "  $line" -ForegroundColor DarkGray }
}

function Stop-RolloverMainProcessAndWait([int]$TimeoutSeconds = 10) {
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

function Test-RolloverBoundaryWakeScheduled([System.DateTime]$TargetResetAt, [int]$ToleranceSeconds = 20) {
    $clockRaw = (Get-TestDeviceShellOutput -Command "date +'%Y-%m-%d %H %M %S'").Trim()
    if ($clockRaw -notmatch '^(\d{4}-\d{2}-\d{2})\s+(\d{1,2})\s+(\d{1,2})\s+(\d{1,2})$') {
        return $false
    }
    $currentClock = [System.DateTime]::ParseExact(
        ("{0} {1:D2} {2:D2} {3:D2}" -f
            $matches[1], [int]$matches[2], [int]$matches[3], [int]$matches[4]),
        "yyyy-MM-dd HH mm ss",
        [System.Globalization.CultureInfo]::InvariantCulture
    )
    $expectedDelaySeconds = ($TargetResetAt - $currentClock).TotalSeconds
    if ($expectedDelaySeconds -le 0) { return $false }

    $alarmDump = Get-TestDeviceShellOutput -Command "dumpsys alarm"
    $alarmLines = @($alarmDump -split "`r?`n")
    $alarmStatsIndex = [Array]::FindIndex([string[]]$alarmLines, [Predicate[string]]{ param($line) $line -match '^\s*Alarm Stats:' })
    if ($alarmStatsIndex -lt 0) { $alarmStatsIndex = $alarmLines.Count }
    for ($index = 0; $index -lt $alarmStatsIndex; $index++) {
        if ($alarmLines[$index] -notmatch 'ACTION_APP_RULE_WAKE') { continue }
        $start = [Math]::Max(0, $index - 1)
        $end = [Math]::Min($alarmStatsIndex - 1, $index + 4)
        for ($lineIndex = $start; $lineIndex -le $end; $lineIndex++) {
            if ($alarmLines[$lineIndex] -notmatch 'origWhen=\+(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?(?:(\d+)ms)?') { continue }
            $hours = if ($matches[1]) { [double]$matches[1] } else { 0 }
            $minutes = if ($matches[2]) { [double]$matches[2] } else { 0 }
            $seconds = if ($matches[3]) { [double]$matches[3] } else { 0 }
            $milliseconds = if ($matches[4]) { [double]$matches[4] } else { 0 }
            $scheduledDelaySeconds = ($hours * 3600) + ($minutes * 60) + $seconds + ($milliseconds / 1000)
            if ([Math]::Abs($scheduledDelaySeconds - $expectedDelaySeconds) -le $ToleranceSeconds) {
                return $true
            }
        }
    }
    return $false
}

try {
    Write-Step "1. Backing up device settings.json and acquiring wake lock..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile
    Set-DeviceAwake $true
    Write-Success "Backup saved to $backupFile and wake lock acquired."

    $testStartTimeMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

    Write-Step "2. Determining the active use day from device time and reset settings..."
    $dateRaw = (adb shell "date +'%Y-%m-%d %w'" | Out-String).Trim()
    if ($dateRaw -match '^(\d{4}-\d{2}-\d{2})\s+(\d)$') {
        $deviceCalendarDate = [System.DateTime]::ParseExact(
            $matches[1],
            "yyyy-MM-dd",
            [System.Globalization.CultureInfo]::InvariantCulture
        )
    } else {
        throw "Could not parse the device calendar date: '$dateRaw'."
    }

    $timeInfo = Get-DeviceTimeInfo
    $settingsForDay = Get-DeviceSettings -AsObject
    if (-not $timeInfo -or -not $settingsForDay) {
        throw "Could not read the device clock and use-day reset settings."
    }
    $resetHour = [int]$settingsForDay.useDayResetHour
    $resetMinute = [int]$settingsForDay.useDayResetMinute
    if ($resetHour -lt 0 -or $resetHour -gt 23 -or $resetMinute -lt 0 -or $resetMinute -gt 59) {
        throw "Device use-day reset clock is invalid: ${resetHour}:$resetMinute."
    }

    $resetMinutesSinceMidnight = ($resetHour * 60) + $resetMinute
    $todayUseDayDate = if ($timeInfo.CurrentMinute -lt $resetMinutesSinceMidnight) {
        $deviceCalendarDate.AddDays(-1)
    } else {
        $deviceCalendarDate
    }
    $todayUseDayId = $todayUseDayDate.ToString("yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture)
    $todayWeekday = [int]$todayUseDayDate.DayOfWeek
    $yesterdayUseDayDate = $todayUseDayDate.AddDays(-1)
    $yesterdayUseDayId = $yesterdayUseDayDate.ToString("yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture)
    $yesterdayWeekday = [int]$yesterdayUseDayDate.DayOfWeek

    Write-Host ("Device use-day reset: {0:D2}:{1:D2}" -f $resetHour, $resetMinute) -ForegroundColor Cyan
    Write-Host "Current use day: $todayUseDayId (Weekday: $todayWeekday)" -ForegroundColor Cyan
    Write-Host "Previous use day: $yesterdayUseDayId (Weekday: $yesterdayWeekday)" -ForegroundColor Cyan

    Write-Step "3. Generating and injecting Guardian PIN auth config (PIN: $Pin)..."
    $guardianAuth = New-GuardianPinAuthConfig -Pin $Pin
    $injectedAuth = Set-DeviceGuardianAuthConfig -GuardianAuthConfig $guardianAuth
    if (-not $injectedAuth) {
        Write-Fail "Failed to inject Guardian PIN auth config into device settings.json!"
        $passedAll = $false
    } else {
        $deviceSettings = Get-DeviceSettings -AsObject
        if (Test-GuardianAuthConfig $deviceSettings) {
            Write-Success "Guardian PIN auth config successfully verified."
        } else {
            Write-Fail "Injected Guardian PIN auth config was not active in settings.json!"
            $passedAll = $false
        }
    }

    Write-Step "4. Crafting and injecting test AppRule with rollover enabled and accumulated pool on Unlock Day..."
    $targetGroupId = "test-target-group-01"
    $ruleId = "test-rule-rollover-01"
    $initialPoolMinutes = 20

    # Ensure today is an UNLOCK day for the rule
    $appRuleSnapshot = New-RolloverAppRuleConfig `
        -TargetPackage $TargetPackage `
        -UnlockDays @($todayWeekday) `
        -AllowedMinutes 0 `
        -TargetGroupId $targetGroupId `
        -RuleId $ruleId

    $poolObj = New-RuleRolloverPool -RuleId $ruleId -AccumulatedMinutes $initialPoolMinutes -LastSettledUseDayId $todayUseDayId
    $rolloverState = [PSCustomObject]@{
        pools = [PSCustomObject]@{
            $ruleId = $poolObj
        }
    }

    Inject-TestAppRules `
        -AppRuleSnapshot $appRuleSnapshot `
        -AppRuleRolloverState $rolloverState `
        -UsageGenerationStartedAtMs $testStartTimeMs | Out-Null

    Write-Success "Injected test AppRules with rolloverEnabled, unlock day ($todayWeekday), and accumulated pool ($initialPoolMinutes min)."

    Write-Step "6. Launching Target App ($TargetPackage) to trigger GuardianApprovalActivity..."
    adb shell "am force-stop $TargetPackage" | Out-Null
    Start-Sleep -Seconds 1
    Start-TestApp -PackageName $TargetPackage -ActivityName $TargetActivity

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $focusResult = $null
    while ($sw.Elapsed.TotalSeconds -lt 10) {
        $focusResult = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        if ($focusResult.Success) { break }
        Start-Sleep -Milliseconds 500
    }

    if ($focusResult -and $focusResult.Success) {
        Write-Success "GuardianApprovalActivity is in foreground!"
    } else {
        Write-Fail "GuardianApprovalActivity is NOT in foreground! Focus: $($focusResult.RawFocus)"
        $passedAll = $false
    }

    Write-Step "7. Verifying '누적 시간 사용 ($initialPoolMinutes`분 사용 가능)' button is displayed..."
    $ui = Wait-For-UI -Pattern "approval_use_accumulated_time" -TimeoutSeconds 8
    $hasButtonNode = ($ui -match 'resource-id="neth.iecal.curbox.debug:id/approval_use_accumulated_time"')
    $hasCorrectMinutes = ($ui -match "text=`"누적 시간 사용 \($initialPoolMinutes`분 사용 가능\)`"" -or `
                          $ui -match "text=`"Use accumulated time \($initialPoolMinutes min available\)`"")
    if ($hasButtonNode -and $hasCorrectMinutes) {
        Write-Success "Approval screen displays accumulated time button with exact available minutes ($initialPoolMinutes min)!"
    } else {
        Write-Fail "Approval screen missing accumulated time button with exact $initialPoolMinutes minutes. UI dump: $ui"
        $passedAll = $false
    }

    Write-Step "8. Tapping Accumulated Time Button to open 1-Tap approval dialog..."
    $tapped = Tap-Node $ui 'resource-id="neth.iecal.curbox.debug:id/approval_use_accumulated_time"' "누적 시간 사용 버튼 (ID)" -Optional
    if (-not $tapped) {
        $tapped = Tap-Node $ui 'text="누적 시간 사용"' "누적 시간 사용 텍스트 (Korean)" -Optional
    }
    if (-not $tapped) {
        $tapped = Tap-Node $ui 'text="Use accumulated time"' "Use accumulated time text (English)"
    }

    $dialogUi = Wait-For-UI "accumulated_minutes_input|accumulated_total_desc|guardian_use_accumulated_time_title|누적 시간 사용" 6

    Write-Step "9. Verifying dialog pre-fills full accumulated amount ($initialPoolMinutes minutes)..."
    $nodeInput = Get-NodeBounds $dialogUi 'resource-id="neth.iecal.curbox.debug:id/accumulated_minutes_input"'
    $hasInput = $nodeInput.Found
    $hasPrefilledAmount = ($dialogUi -match ('resource-id="neth.iecal.curbox.debug:id/accumulated_minutes_input"[^>]*text="' + $initialPoolMinutes + '"') -or `
                           $dialogUi -match ('text="' + $initialPoolMinutes + '"[^>]*resource-id="neth.iecal.curbox.debug:id/accumulated_minutes_input"'))

    if ($hasInput -and $hasPrefilledAmount) {
        Write-Success "Approval dialog pre-filled with total accumulated amount ($initialPoolMinutes minutes) in input field!"
    } else {
        Write-Fail "Dialog input field did not pre-fill expected accumulated minutes ($initialPoolMinutes). UI: $dialogUi"
        $passedAll = $false
    }

    Write-Step "10. Submitting 1-Tap approval without modification..."
    $applied = Tap-Node $dialogUi 'resource-id="android:id/button1"' "적용/확인 버튼 (Resource-ID)" -Optional
    if (-not $applied) {
        $applied = Tap-Node $dialogUi 'text="적용"' "적용 버튼 (Korean)" -Optional
    }
    if (-not $applied) {
        $applied = Tap-Node $dialogUi 'text="Apply"' "Apply button (English)"
    }
    if ($applied) {
        Write-Success "Submitted 1-Tap approval."
    } else {
        Write-Fail "Failed to tap Apply button in accumulated time dialog."
        $passedAll = $false
    }

    Write-Step "11. Authenticating with Guardian PIN ($Pin)..."
    $pinSuccess = Submit-GuardianPin -PinValue $Pin
    if ($pinSuccess) {
        Write-Success "Guardian PIN successfully submitted."
    } else {
        Write-Fail "Failed to submit Guardian PIN."
        $passedAll = $false
    }

    Write-Step "12. Verifying DataStore: pool deducted and AppRuleGuardianGrant(isFromAccumulatedPool = true) issued..."
    Start-Sleep -Seconds 2
    $updatedSettings = Get-DeviceSettings -AsObject
    $expectedGrantedMillis = [long]($initialPoolMinutes * 60 * 1000)

    $grantRecorded = Test-AppRuleGuardianGrant `
        -OverrideState $updatedSettings.appRuleOverrideState `
        -RuleId $ruleId `
        -ExpectedGrantedMillis $expectedGrantedMillis `
        -IsFromAccumulatedPool $true `
        -ExpectedUseDayId $todayUseDayId

    if ($grantRecorded) {
        Write-Success "DataStore successfully recorded AppRuleGuardianGrant with isFromAccumulatedPool = true ($expectedGrantedMillis ms)!"
    } else {
        Write-Fail "AppRuleGuardianGrant(isFromAccumulatedPool = true) NOT found in DataStore! Overrides: $($updatedSettings.appRuleOverrideState | ConvertTo-Json -Compress)"
        $passedAll = $false
    }

    $updatedPool = Get-RuleRolloverPool -SettingsOrRolloverState $updatedSettings -RuleId $ruleId
    if ($updatedPool -and $updatedPool.accumulatedMinutes -eq 0) {
        Write-Success "Accumulated pool successfully deducted to 0 minutes ($initialPoolMinutes - $initialPoolMinutes = 0)."
    } else {
        $poolMinutes = if ($updatedPool) { $updatedPool.accumulatedMinutes } else { "null" }
        Write-Fail "Accumulated pool was not deducted to 0! Observed pool minutes: $poolMinutes"
        $passedAll = $false
    }

    Write-Step "13. Verifying Target App is immediately unlocked and relaunched in foreground..."
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $targetFocus = $null
    while ($sw.Elapsed.TotalSeconds -lt 10) {
        $targetFocus = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if ($targetFocus.Success) { break }
        Start-Sleep -Milliseconds 500
    }

    if ($targetFocus -and $targetFocus.Success) {
        Write-Success "Target app is unblocked and running in foreground!"
    } else {
        Write-Fail "Target app did not regain foreground focus! Focus: $($targetFocus.RawFocus)"
        $passedAll = $false
    }

    Write-Step "14. Verifying settlement across a real use-day reset boundary..."
    $boundaryClockRaw = (adb shell "date +'%Y-%m-%d %H %M %S'" | Out-String).Trim()
    $boundaryClockMatch = [regex]::Match($boundaryClockRaw, '^(\d{4}-\d{2}-\d{2})\s+(\d{1,2})\s+(\d{1,2})\s+(\d{1,2})$')
    if (-not $boundaryClockMatch.Success) {
        throw "Could not parse the device clock for the rollover boundary test: '$boundaryClockRaw'."
    }
    $boundaryNow = [System.DateTime]::ParseExact(
        ("{0} {1:D2} {2:D2} {3:D2}" -f
            $boundaryClockMatch.Groups[1].Value,
            [int]$boundaryClockMatch.Groups[2].Value,
            [int]$boundaryClockMatch.Groups[3].Value,
            [int]$boundaryClockMatch.Groups[4].Value),
        "yyyy-MM-dd HH mm ss",
        [System.Globalization.CultureInfo]::InvariantCulture
    )
    $targetResetAt = $boundaryNow.AddMinutes(3)
    $targetResetAt = $targetResetAt.AddSeconds(-$targetResetAt.Second)
    $transitionUseDayDate = $targetResetAt.Date.AddDays(-1)
    $transitionUseDayId = $transitionUseDayDate.ToString("yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture)
    $transitionUseDayWeekday = [int]$transitionUseDayDate.DayOfWeek
    $nextUseDayId = $targetResetAt.Date.ToString("yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture)

    # Before the configured reset, the active use day is the previous date. At the reset
    # boundary it advances to targetResetAt.Date, which is deliberately an accrual day.
    $transitionRule = New-RolloverAppRuleConfig `
        -TargetPackage $TargetPackage `
        -UnlockDays @($transitionUseDayWeekday) `
        -AllowedMinutes 0 `
        -TargetGroupId $targetGroupId `
        -RuleId $ruleId

    $preTransitionMinutes = 25
    $transitionPool = New-RuleRolloverPool `
        -RuleId $ruleId `
        -AccumulatedMinutes $preTransitionMinutes `
        -LastSettledUseDayId $transitionUseDayId
    $transitionRolloverState = [PSCustomObject]@{
        pools = [PSCustomObject]@{
            $ruleId = $transitionPool
        }
    }
    if (-not (Set-DeviceUseDayResetTime -Hour $targetResetAt.Hour -Minute $targetResetAt.Minute)) {
        throw "Could not set the temporary reset time for the rollover boundary test."
    }
    Clear-TestAppRules | Out-Null
    $transitionGenerationMs = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Set-DeviceUsageGeneration -GenerationStartedAtMs $transitionGenerationMs | Out-Null
    if (-not (Set-DeviceRolloverState -RolloverState $transitionRolloverState)) {
        throw "Could not persist the transition rollover state."
    }
    if (-not (Set-DeviceAppRuleSnapshot -AppRuleSnapshot $transitionRule)) {
        throw "Could not persist the transition rollover rule snapshot."
    }

    $transitionSettings = Get-DeviceSettings -AsObject
    $transitionPoolReadback = Get-RuleRolloverPool -SettingsOrRolloverState $transitionSettings -RuleId $ruleId
    $transitionRuleReadback = @($transitionSettings.appRuleSnapshot.appRules | Where-Object { $_.id -eq $ruleId })
    if ([int]$transitionSettings.useDayResetHour -ne $targetResetAt.Hour -or
        [int]$transitionSettings.useDayResetMinute -ne $targetResetAt.Minute -or
        -not $transitionPoolReadback -or
        [long]$transitionPoolReadback.accumulatedMinutes -ne $preTransitionMinutes -or
        [string]$transitionPoolReadback.lastSettledUseDayId -ne $transitionUseDayId -or
        $transitionRuleReadback.Count -ne 1 -or
        -not $transitionRuleReadback[0].rolloverEnabled -or
        @($transitionRuleReadback[0].unlockDays).Count -ne 1 -or
        [int]$transitionRuleReadback[0].unlockDays[0] -ne $transitionUseDayWeekday) {
        throw "Temporary reset boundary fixture did not persist the expected settings and pool."
    }
    if (-not (Test-AccessibilityServiceBound)) {
        throw "Curbox AppBlockerService is not bound before the rollover reset boundary."
    }
    Write-Host "Injected $preTransitionMinutes minutes on unlock use day '$transitionUseDayId'; accrual use day '$nextUseDayId' begins at $($targetResetAt.ToString('yyyy-MM-dd HH:mm:ss'))." -ForegroundColor Cyan

    Write-Step "14a. Reloading persisted rollover settings into a fresh app blocker process..."
    $freshMainProcess = Stop-RolloverMainProcessAndWait
    if (-not $freshMainProcess.Stopped) {
        throw "The main Curbox process did not retire after the persisted rollover settings were written."
    }
    $freshServiceResult = Restart-DeviceAccessibilityServiceIfEnabled -AccessibilitySettings $accessibilityBackup
    if ($freshServiceResult.Skipped -or -not $freshServiceResult.ProcessId) {
        throw "AppBlockerService did not rebind to a fresh process from the persisted rollover settings."
    }
    Start-Sleep -Seconds 2
    $freshServicePid = $freshServiceResult.ProcessId
    Write-Success "AppBlockerService re-bound as PID $freshServicePid after the fixture settings were persisted."
    if (-not (Test-RolloverBoundaryWakeScheduled -TargetResetAt $targetResetAt)) {
        Write-RolloverBoundaryDiagnostics -Phase "fresh service schedule verification"
        throw "Fresh AppBlockerService did not schedule a wake for the configured use-day reset boundary."
    }
    Write-Success "Fresh AppBlockerService scheduled a wake for the configured use-day reset boundary."

    $boundaryWait = [System.Diagnostics.Stopwatch]::StartNew()
    $boundaryReached = $false
    $settlementRefreshPosted = $false
    while ($boundaryWait.Elapsed.TotalSeconds -lt 240) {
        $currentClockRaw = (adb shell "date +'%Y-%m-%d %H %M %S'" | Out-String).Trim()
        if ($currentClockRaw -match '^(\d{4}-\d{2}-\d{2})\s+(\d{1,2})\s+(\d{1,2})\s+(\d{1,2})$') {
            $currentClock = [System.DateTime]::ParseExact(
                ("{0} {1:D2} {2:D2} {3:D2}" -f
                    $matches[1],
                    [int]$matches[2],
                    [int]$matches[3],
                    [int]$matches[4]),
                "yyyy-MM-dd HH mm ss",
                [System.Globalization.CultureInfo]::InvariantCulture
            )
            $secondsUntilBoundary = ($targetResetAt - $currentClock).TotalSeconds
            if (-not $settlementRefreshPosted -and $secondsUntilBoundary -gt 0 -and $secondsUntilBoundary -le 15) {
                Write-Host "Refreshing the active app-rule schedule $([int][Math]::Ceiling($secondsUntilBoundary)) seconds before settlement so the handler fallback can cover an inexact alarm." -ForegroundColor Cyan
                $refreshReceipt = Get-TestDeviceShellOutput -Command "am broadcast -W -a neth.iecal.curbox.refresh.app_rules -p neth.iecal.curbox.debug"
                Write-Host "[DIAGNOSTIC] refresh broadcast receipt: $refreshReceipt" -ForegroundColor DarkGray
                Start-Sleep -Milliseconds 500
                $settlementRefreshPosted = $true
            }
            if ($currentClock -ge $targetResetAt) {
                $boundaryReached = $true
                break
            }
        }
        Start-Sleep -Seconds 2
    }

    if (-not $boundaryReached) {
        Write-Fail "The device did not reach the temporary use-day reset boundary within 240 seconds."
        $passedAll = $false
    } else {
        Write-Success "Device reached the use-day reset boundary; waiting for rollover settlement."
        if (-not $settlementRefreshPosted) {
            Write-Host "[DIAGNOSTIC] No final near-boundary schedule refresh was posted before the reset." -ForegroundColor DarkYellow
        }
        $settlementWait = [System.Diagnostics.Stopwatch]::StartNew()
        $settled = $false
        $postTransitionPool = $null
        while ($settlementWait.Elapsed.TotalSeconds -lt 20) {
            $postTransitionSettings = Get-DeviceSettings -AsObject
            $postTransitionPool = Get-RuleRolloverPool -SettingsOrRolloverState $postTransitionSettings -RuleId $ruleId
            if ($postTransitionPool -and
                [long]$postTransitionPool.accumulatedMinutes -eq 0 -and
                [string]$postTransitionPool.lastSettledUseDayId -eq $nextUseDayId) {
                $settled = $true
                break
            }
            Start-Sleep -Seconds 1
        }
        if ($settled) {
            Write-Success "Boundary settlement reset the accumulated pool to 0 ($transitionUseDayId unlock day -> $nextUseDayId accrual day)."
        } else {
            $poolMinutes = if ($postTransitionPool) { $postTransitionPool.accumulatedMinutes } else { "null" }
            $lastSettledDayId = if ($postTransitionPool) { $postTransitionPool.lastSettledUseDayId } else { "null" }
            Write-Fail "Boundary settlement did not reset the pool. Minutes: $poolMinutes; last settled use day: $lastSettledDayId."
            Write-RolloverBoundaryDiagnostics -Phase "after boundary failure"
            $passedAll = $false
        }
    }

} finally {
    Write-Step "16. Cleanup & Restoring original settings..."
    Complete-DeviceTest -BackupPath $backupFile -TargetPackages @($TargetPackage) -AccessibilitySettings $accessibilityBackup
    try {
        $restoredMainProcess = Stop-RolloverMainProcessAndWait
        if (-not $restoredMainProcess.Stopped) {
            throw "The main Curbox process did not retire after the saved user settings were restored."
        }
        $restoredServiceResult = Restart-DeviceAccessibilityServiceIfEnabled -AccessibilitySettings $accessibilityBackup
        if ($restoredServiceResult.Skipped) {
            Write-Host "Accessibility was disabled in the saved baseline; the app blocker service restart was skipped." -ForegroundColor DarkGray
        } elseif ($restoredServiceResult.ProcessId) {
            Write-Success "AppBlockerService re-bound as PID $($restoredServiceResult.ProcessId) with the restored user settings."
        } else {
            throw "AppBlockerService did not rebind from the restored user settings."
        }
    } catch {
        $passedAll = $false
        Write-Fail "Could not reload restored settings into AppBlockerService: $($_.Exception.Message)"
    }
    Write-Success "Target app stopped and device returned to home."
}

Write-Step "15. Final Result Summary"
if ($passedAll) {
    Write-Host "`n=========================================================================================" -ForegroundColor Green
    Write-Host ">>> [TOTAL ROLLOVER GUARDIAN EXTRA TIME RESULT: PASS] All requirements verified! <<<" -ForegroundColor Green
    Write-Host "=========================================================================================`n" -ForegroundColor Green
} else {
    Write-Host "`n=========================================================================================" -ForegroundColor Red
    Write-Host ">>> [TOTAL ROLLOVER GUARDIAN EXTRA TIME RESULT: FAIL] Some checks failed. Inspect logs. <<<" -ForegroundColor Red
    Write-Host "=========================================================================================`n" -ForegroundColor Red
    exit 1
}
