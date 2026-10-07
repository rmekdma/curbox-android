<#
.SYNOPSIS
    Exercises uncertain guardian writes and superseded confirmation results on a real device.
.DESCRIPTION
    Uses the actual DataStore write path, the app_blocker_service decision worker, and the
    visible GuardianApprovalActivity. The DEBUG-only gates delay one real worker outcome and
    inject an exception only after the guardian write has committed.
#>

param(
    [string]$TargetPackage = "com.woodenpharm.choseonggacha",
    [string]$TargetActivity = "",
    [string]$DeviceId = "",
    [switch]$GateCleanupOnly
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/lib/device-test-common.ps1"

if ($DeviceId) { $env:ANDROID_SERIAL = $DeviceId }
Assert-AdbDevice

$packageName = "neth.iecal.curbox.debug"
$runId = [guid]::NewGuid().ToString("N")
$tmpDir = Join-Path $env:TEMP "curbox_guardian_retry_$runId"
New-Item -ItemType Directory -Path $tmpDir | Out-Null
$backupFile = Join-Path $tmpDir "settings_backup.json"
$accessibilityBackup = Backup-DeviceAccessibilitySettings
$activeGateId = ""
$writeFailureArmed = $false
$restoreVerified = $true
$originalSettings = $null
$originalOverlayMode = ""

function Get-GuardianApprovalLogs {
    return Get-TestDeviceShellOutput -Command "logcat -d -s GuardianApprovalE2E:I"
}

function Wait-ForGuardianLog([string]$Pattern, [string]$Label, [int]$TimeoutSeconds = 25) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $lastLogs = ""
    while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $lastLogs = Get-GuardianApprovalLogs
        $match = [regex]::Match($lastLogs, "(?m)^.*$Pattern.*$")
        if ($match.Success) { return $match.Value }
        Start-Sleep -Milliseconds 300
    }
    throw "Timed out waiting for $Label. Recent GuardianApprovalE2E logs:`n$lastLogs"
}

function Get-LogValue([string]$LogLine, [string]$Name) {
    $match = [regex]::Match($LogLine, "(?:^|\s)$([regex]::Escape($Name))=([^\s]+)")
    if (-not $match.Success) { return "" }
    return $match.Groups[1].Value
}

function Send-TestBroadcast([string]$Action, [string]$GateId, [string]$ExtraArguments = "") {
    $command = "am broadcast -a $Action -p $packageName"
    if ($GateId) { $command += " --es guardian_test_gate_id $GateId" }
    if ($ExtraArguments) { $command += " $ExtraArguments" }
    Invoke-TestDeviceShell -Command $command
}

function Arm-ServiceEvaluationGate([string]$GateId) {
    $script:activeGateId = $GateId
    Send-TestBroadcast `
        -Action "neth.iecal.curbox.blockers.TEST_ARM_GUARDIAN_EVALUATION_GATE" `
        -GateId $GateId `
        -ExtraArguments "--es guardian_test_ack_package $packageName"
    [void](Wait-ForGuardianLog `
        -Pattern "service_gate action=neth\.iecal\.curbox\.blockers\.TEST_ARM_GUARDIAN_EVALUATION_GATE id=$GateId accepted=true" `
        -Label "service-process evaluation gate acknowledgement")
}

function Arm-WriteFailure([string]$GateId) {
    $script:writeFailureArmed = $true
    Send-TestBroadcast `
        -Action "neth.iecal.curbox.guardian.TEST_ARM_WRITE_FAILURE" `
        -GateId $GateId
    [void](Wait-ForGuardianLog `
        -Pattern "write_failure_armed id=$GateId accepted=true" `
        -Label "Activity write-failure gate acknowledgement")
}

function Release-ServiceEvaluationGate([string]$GateId) {
    if (-not $GateId) { return }
    Send-TestBroadcast `
        -Action "neth.iecal.curbox.blockers.TEST_RELEASE_GUARDIAN_EVALUATION_GATE" `
        -GateId $GateId
    [void](Wait-ForGuardianLog `
        -Pattern "service_gate action=neth\.iecal\.curbox\.blockers\.TEST_RELEASE_GUARDIAN_EVALUATION_GATE id=$GateId accepted=true" `
        -Label "service-process evaluation gate release acknowledgement")
    $script:activeGateId = ""
}

function Release-UnconsumedServiceGate([string]$GateId) {
    if ($script:activeGateId -ne $GateId) { return }
    Send-TestBroadcast `
        -Action "neth.iecal.curbox.blockers.TEST_RELEASE_GUARDIAN_EVALUATION_GATE" `
        -GateId $GateId
    [void](Wait-ForGuardianLog `
        -Pattern "service_gate_released id=$GateId accepted=true consumed=false" `
        -Label "cleanup of unconsumed service gate")
    $script:activeGateId = ""
}

function Assert-UnconsumedServiceGateCanBeRearmed {
    $abortedGateId = "$runId-aborted-before-consume"
    $abortObserved = $false
    try {
        $script:activeGateId = $abortedGateId
        Send-TestBroadcast `
            -Action "neth.iecal.curbox.blockers.TEST_ARM_GUARDIAN_EVALUATION_GATE" `
            -GateId $abortedGateId `
            -ExtraArguments "--es guardian_test_ack_package $packageName"
        [void](Wait-ForGuardianLog `
            -Pattern "service_gate_armed id=$abortedGateId accepted=true" `
            -Label "initial unconsumed service gate arm")
        throw "Injected abort after the gate acknowledgement."
    } catch {
        if ($_.Exception.Message -ne "Injected abort after the gate acknowledgement.") { throw }
        $abortObserved = $true
    } finally {
        Release-UnconsumedServiceGate -GateId $abortedGateId
    }
    if (-not $abortObserved) { throw "The unconsumed service gate abort path was not exercised." }

    $rearmedGateId = "$runId-immediate-rearm"
    try {
        $script:activeGateId = $rearmedGateId
        Send-TestBroadcast `
            -Action "neth.iecal.curbox.blockers.TEST_ARM_GUARDIAN_EVALUATION_GATE" `
            -GateId $rearmedGateId `
            -ExtraArguments "--es guardian_test_ack_package $packageName"
        [void](Wait-ForGuardianLog `
            -Pattern "service_gate_armed id=$rearmedGateId accepted=true" `
            -Label "immediate service gate rearm after abort")
        Release-UnconsumedServiceGate -GateId $rearmedGateId
    } finally {
        Release-UnconsumedServiceGate -GateId $rearmedGateId
    }
}

function Clear-WriteFailure {
    if (-not $script:writeFailureArmed) { return }
    Send-TestBroadcast `
        -Action "neth.iecal.curbox.guardian.TEST_CLEAR_WRITE_FAILURE" `
        -GateId ""
    $script:writeFailureArmed = $false
}

function New-GuardianRule(
    [string]$RuleId,
    [string]$RuleName,
    [string]$GroupId,
    [bool]$RolloverEnabled = $false,
    [bool]$GuardianExtraTimeAllowed = $true,
    [bool]$IsActive = $true
) {
    $unlockDays = @()
    if ($RolloverEnabled) { $unlockDays = @(0, 1, 2, 3, 4, 5, 6) }
    return [PSCustomObject]@{
        id = $RuleId
        name = $RuleName
        isActive = $IsActive
        weekdays = @(0, 1, 2, 3, 4, 5, 6)
        startMinute = 0
        endMinute = 0
        appGroupId = $GroupId
        allowedMinutes = [long]0
        scope = [PSCustomObject]@{
            includeAllApps = $false
            includedGroupIds = @($GroupId)
            excludedGroupIds = @()
        }
        timeRanges = @([PSCustomObject]@{ startMinute = 0; endMinute = 0 })
        usageConditionEnabled = $false
        usageConditionMinutes = [long]0
        contributorGroupConditionMinutes = [PSCustomObject]@{}
        contributorGroupIds = @()
        earnedAllowanceEnabled = $false
        rolloverEnabled = $RolloverEnabled
        unlockDays = $unlockDays
        guardianExtraTimeAllowed = $GuardianExtraTimeAllowed
    }
}

function New-GuardianSnapshot([string]$GroupId, $Rules) {
    return [PSCustomObject]@{
        appGroups = @(
            (New-TestAppGroup `
                -GroupId $GroupId `
                -GroupName "Guardian retry test" `
                -Packages @($TargetPackage))
        )
        appRules = @($Rules)
    }
}

function Start-GuardianApproval([string]$ExpectedRuleName) {
    Invoke-TestDeviceShell -Command "input keyevent 3"
    Start-Sleep -Milliseconds 800
    Invoke-TestDeviceShell -Command "am force-stop $TargetPackage"
    Start-TestApp -PackageName $TargetPackage -ActivityName $TargetActivity

    $focus = $null
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 15) {
        $focus = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
        if ($focus.Success) { break }
        Start-Sleep -Milliseconds 400
    }
    if (-not $focus -or -not $focus.Success) {
        throw "GuardianApprovalActivity did not appear for $ExpectedRuleName. Focus: $($focus.RawFocus)"
    }

    $ui = Wait-For-UI -Pattern ([regex]::Escape($ExpectedRuleName)) -TimeoutSeconds 10
    if ($ui -notmatch [regex]::Escape($ExpectedRuleName)) {
        throw "GuardianApprovalActivity did not show the expected denial '$ExpectedRuleName'. UI: $ui"
    }
    $ruleTextPattern = "text=`"[^`"]*$([regex]::Escape($ExpectedRuleName))[^`"]*`""
    if (-not (Tap-Node $ui $ruleTextPattern "Select $ExpectedRuleName denial" -Optional)) {
        throw "Could not select the '$ExpectedRuleName' denial in GuardianApprovalActivity."
    }
}

function Tap-ApprovalAction([string]$ResourceId, [string]$Description) {
    $ui = Wait-For-UI -Pattern ([regex]::Escape($ResourceId)) -TimeoutSeconds 8
    if (-not (Tap-Node $ui "resource-id=`"$packageName`:id/$ResourceId`"" $Description -Optional)) {
        throw "Could not tap $Description ($ResourceId)."
    }
}

function Tap-DialogApply {
    $inputMethodState = Get-TestDeviceShellOutput -Command "dumpsys input_method"
    if ($inputMethodState -match 'mInputShown=true') {
        Invoke-TestDeviceShell -Command "input keyevent 4"
        Start-Sleep -Milliseconds 250
    }
    $ui = Dump-UI
    $applied = Tap-Node $ui 'resource-id="android:id/button1"' "Apply" -Optional
    if (-not $applied) { $applied = Tap-Node $ui 'text="적용"' "적용" -Optional }
    if (-not $applied) { $applied = Tap-Node $ui 'text="Apply"' "Apply" -Optional }
    if (-not $applied) { throw "Could not find the dialog Apply button. UI: $ui" }
}

function Tap-ApprovalRetry([string]$GeometryLine) {
    # Do not use UIAutomator after confirmation starts: it temporarily replaces the
    # accessibility service on this device and would reset the in-memory request owner.
    $left = [int](Get-LogValue $GeometryLine "retry_left")
    $top = [int](Get-LogValue $GeometryLine "retry_top")
    $width = [int](Get-LogValue $GeometryLine "retry_width")
    $height = [int](Get-LogValue $GeometryLine "retry_height")
    if ($width -le 0 -or $height -le 0 -or
        (Get-LogValue $GeometryLine "visible") -ne "true") {
        throw "The Activity did not report visible retry-button bounds: $GeometryLine"
    }
    $x = $left + [int][math]::Floor($width / 2.0)
    $y = $top + [int][math]::Floor($height / 2.0)
    Invoke-TestDeviceShell -Command "input tap $x $y"
}

function Assert-GuardianLedger($Settings, [string]$RuleId, [string]$Kind, [string]$UseDayId) {
    $state = $Settings.appRuleOverrideState
    if ($Kind -eq "direct_grant") {
        if (-not (Test-AppRuleGuardianGrant `
            -OverrideState $state `
            -RuleId $RuleId `
            -ExpectedGrantedMillis 60000 `
            -ExpectedUseDayId $UseDayId)) {
            throw "The committed direct grant receipt for '$RuleId' was not present in DataStore."
        }
        return
    }
    if ($Kind -eq "accumulated_grant") {
        if (-not (Test-AppRuleGuardianGrant `
            -OverrideState $state `
            -RuleId $RuleId `
            -ExpectedGrantedMillis 1200000 `
            -IsFromAccumulatedPool $true `
            -ExpectedUseDayId $UseDayId)) {
            throw "The committed accumulated grant receipt for '$RuleId' was not present in DataStore."
        }
        $pool = Get-RuleRolloverPool -SettingsOrRolloverState $Settings -RuleId $RuleId
        if (-not $pool -or [long]$pool.accumulatedMinutes -ne 0) {
            throw "The accumulated pool was not debited exactly once for '$RuleId'."
        }
        return
    }
    $skip = @($state.skips | Where-Object {
        $_.ruleId -eq $RuleId -and $_.useDayId -eq $UseDayId -and [long]$_.skipUntilMs -gt [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    })
    if ($skip.Count -ne 1) {
        throw "The committed skip receipt for '$RuleId' was not present exactly once in DataStore."
    }
}

function Invoke-AmbiguousConfirmationCase(
    [string]$CaseName,
    [string]$RuleId,
    [string]$RuleName,
    [string]$ExpectedKind,
    [scriptblock]$SubmitOperation,
    [scriptblock]$UpdateCurrentRules,
    [string]$ExpectedCurrentDenialName
) {
    $gateId = "$runId-$CaseName"
    Start-GuardianApproval -ExpectedRuleName $RuleName
    & $SubmitOperation $gateId

    $writeCommitted = Wait-ForGuardianLog `
        -Pattern "write_committed id=$gateId " `
        -Label "$CaseName post-commit injected write failure"
    $storedSettings = Get-DeviceSettings -PackageName $packageName -AsObject
    $firstRequest = Wait-ForGuardianLog `
        -Pattern "check_request test=$gateId action=neth\.iecal\.curbox\.guardian\.approval\.stored " `
        -Label "$CaseName initial confirmation request"
    $operationId = Get-LogValue $firstRequest "operation"
    $firstCheckId = Get-LogValue $firstRequest "check"
    $kind = Get-LogValue $firstRequest "kind"
    $receiptRuleId = Get-LogValue $firstRequest "rule"
    $useDayId = Get-LogValue $firstRequest "use_day"
    if (-not $operationId -or -not $firstCheckId -or $kind -ne $ExpectedKind -or $receiptRuleId -ne $RuleId) {
        throw "$CaseName confirmation request did not carry the actual $ExpectedKind receipt: $firstRequest"
    }
    Assert-GuardianLedger -Settings $storedSettings -RuleId $RuleId -Kind $ExpectedKind -UseDayId $useDayId

    [void](Wait-ForGuardianLog `
        -Pattern "service_gate action=neth\.iecal\.curbox\.blockers\.TEST_GUARDIAN_EVALUATION_GATE_REACHED id=$gateId accepted=true operation=$operationId check=$firstCheckId" `
        -Label "$CaseName real service-worker evaluation")
    [void](Wait-ForGuardianLog `
        -Pattern "ui_state status=timeout operation=$operationId check=$firstCheckId retry_visible=true failed=true" `
        -Label "$CaseName bounded confirmation failure and visible retry UI")
    $retryGeometry = Wait-ForGuardianLog `
        -Pattern "ui_geometry operation=$operationId check=$firstCheckId retry_left=\d+ retry_top=\d+ retry_width=\d+ retry_height=\d+ visible=true" `
        -Label "$CaseName laid-out retry button geometry"

    & $UpdateCurrentRules
    $settingsAfterRules = Get-DeviceSettings -PackageName $packageName -AsObject
    $latestRule = @($settingsAfterRules.appRuleSnapshot.appRules | Where-Object { $_.name -eq $ExpectedCurrentDenialName })
    if ($latestRule.Count -ne 1) {
        throw "$CaseName current rule snapshot was not stored through DataStore."
    }

    Tap-ApprovalRetry -GeometryLine $retryGeometry
    $retryRequest = Wait-ForGuardianLog `
        -Pattern "check_request test=$gateId action=neth\.iecal\.curbox\.guardian\.approval\.check_retry " `
        -Label "$CaseName user-triggered confirmation retry"
    $retryOperationId = Get-LogValue $retryRequest "operation"
    $retryCheckId = Get-LogValue $retryRequest "check"
    if ($retryOperationId -ne $operationId -or $retryCheckId -eq $firstCheckId) {
        throw "$CaseName retry did not reuse the operation with a fresh check id. First: $firstRequest Retry: $retryRequest"
    }
    [void](Wait-ForGuardianLog `
        -Pattern "service_check_accepted operation=$operationId check=$retryCheckId retry=true" `
        -Label "$CaseName retry accepted by the actual service coordinator")
    foreach ($field in @("kind", "rule", "use_day", "generation", "granted_at", "granted_millis", "skip_from", "skip_until")) {
        $firstValue = Get-LogValue $firstRequest $field
        $retryValue = Get-LogValue $retryRequest $field
        if ($firstValue -ne $retryValue) {
            throw "$CaseName retry changed receipt field '$field' from '$firstValue' to '$retryValue'."
        }
    }

    [void](Wait-ForGuardianLog `
        -Pattern "ui_state status=remaining operation=$operationId check=$retryCheckId retry_visible=false failed=false .*denials=.*$([regex]::Escape($ExpectedCurrentDenialName))" `
        -Label "$CaseName current denial rendered by the Activity after retry")

    $settingsBeforeLateResult = Get-DeviceSettings -PackageName $packageName -AsObject
    Release-ServiceEvaluationGate -GateId $gateId
    [void](Wait-ForGuardianLog `
        -Pattern "service_outcome_ignored operation=$operationId check=$firstCheckId current_check=$retryCheckId reason=stale_request" `
        -Label "$CaseName actual service worker discarded its superseded outcome")
    $allLogs = Get-GuardianApprovalLogs
    $staleAllowedResult = [regex]::IsMatch(
        $allLogs,
        "result test=$([regex]::Escape($gateId)) status=allowed operation=$([regex]::Escape($operationId)) check=$([regex]::Escape($firstCheckId))"
    )
    $activityFocus = Assert-WindowFocus -ExpectedActivity "GuardianApprovalActivity" -PassThru
    if (-not $activityFocus.Success -or $staleAllowedResult) {
        throw "$CaseName allowed a superseded service result to reach the Activity or lost the latest denial. Focus: $($activityFocus.RawFocus) staleAllowedResult=$staleAllowedResult"
    }

    Clear-WriteFailure
    $settingsAfterRetry = Get-DeviceSettings -PackageName $packageName -AsObject
    $writesForOperation = [regex]::Matches((Get-GuardianApprovalLogs), "write_committed id=$gateId ")
    if ($writesForOperation.Count -ne 1) {
        throw "$CaseName committed $($writesForOperation.Count) write transactions; expected exactly one."
    }
    if ((ConvertTo-Json -InputObject $settingsBeforeLateResult.appRuleOverrideState -Depth 20 -Compress) -ne
        (ConvertTo-Json -InputObject $settingsAfterRetry.appRuleOverrideState -Depth 20 -Compress)) {
        throw "$CaseName retry or stale-result handling changed the persisted guardian ledger."
    }
    Write-Success "$CaseName committed once, timed out, retried the same receipt, showed current denial, and discarded the superseded worker outcome."
}

try {
    Write-Step "1. Back up current settings and verify the actual service environment..."
    $rawSettings = Backup-DeviceSettings -DestinationPath $backupFile -PackageName $packageName
    $originalSettings = $rawSettings | ConvertFrom-Json
    Set-DeviceAwake $true
    $settings = Get-DeviceSettings -PackageName $packageName -AsObject
    if ($settings.guardianAuthConfig.isConfigured) {
        throw "The device has a guardian PIN configured; this test requires the existing unset-PIN test setup."
    }
    $delayConfig = $settings.settingsChangeDelayConfig2
    if (@($delayConfig.pendingChanges | Where-Object { $_.field -eq "APP_RULES" }).Count -gt 0 -or
        ($delayConfig.isEnabled -and [int]$delayConfig.delayMinutes -gt 0) -or
        ($delayConfig.requireTamperProtectionOff -and $settings.antiUninstallConfig2.isEnabled)) {
        throw "The current app-rule settings change delay prevents this test from applying a controlled immediate snapshot."
    }
    $accessibilityDump = Get-TestDeviceShellOutput -Command "dumpsys accessibility"
    if (-not (Test-AccessibilityServiceBound -DumpsysOutput $accessibilityDump)) {
        throw "Curbox AppBlockerService is not bound. This device integration requires the real accessibility service."
    }
    $overlayState = Get-TestDeviceShellOutput -Command "appops get $packageName SYSTEM_ALERT_WINDOW"
    if ($overlayState -notmatch "allow") {
        throw "Curbox overlay permission is not allowed: $overlayState"
    }
    $originalOverlayMode = if ($overlayState -match 'SYSTEM_ALERT_WINDOW:\s*(\w+)') { $matches[1] } else { "" }
    if ((Get-TestDeviceShellOutput -Command "pm path $TargetPackage") -notmatch "package:") {
        throw "The target package '$TargetPackage' is not installed on the connected device."
    }
    Write-Success "Settings backed up; accessibility service is bound, overlay is allowed, and target app is installed."

    Write-Step "2. Verify that an aborted, unused worker gate can be rearmed immediately..."
    Assert-UnconsumedServiceGateCanBeRearmed
    Write-Success "The real service released an unconsumed gate and accepted an immediate rearm."
    if ($GateCleanupOnly) { return }

    Write-Step "3. Install a one-rule direct-grant fixture through the existing debug DataStore receiver..."
    $groupId = "guardian-retry-$runId"
    $directRuleId = "guardian-direct-$runId"
    $accumulatedRuleId = "guardian-accumulated-$runId"
    $skipRuleId = "guardian-skip-$runId"
    $replacementSkipRuleId = "guardian-skip-current-$runId"
    $generation = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    $emptyRollover = [PSCustomObject]@{ pools = [PSCustomObject]@{} }
    $directRule = New-GuardianRule -RuleId $directRuleId -RuleName "E2E direct grant" -GroupId $groupId
    $directSnapshot = New-GuardianSnapshot -GroupId $groupId -Rules @($directRule)
    Inject-TestAppRules `
        -AppRuleSnapshot $directSnapshot `
        -UsageGenerationStartedAtMs $generation `
        -AppRuleRolloverState $emptyRollover `
        -PackageName $packageName
    $storedFixture = Get-DeviceSettings -PackageName $packageName -AsObject
    if ($storedFixture.appRuleSnapshot.appRules.Count -ne 1 -or
        $storedFixture.appRuleSnapshot.appRules[0].id -ne $directRuleId) {
        $storedRulesJson = ConvertTo-Json -InputObject $storedFixture.appRuleSnapshot.appRules -Depth 20 -Compress
        $bootLogs = Get-TestDeviceShellOutput -Command "logcat -d -s BootReceiver:I"
        throw "The direct fixture did not reach DataStore through the test receiver. Stored rules: $storedRulesJson. BootReceiver logs: $bootLogs"
    }
    if (@($storedFixture.appRuleOverrideState.grants).Count -ne 0 -or
        @($storedFixture.appRuleOverrideState.skips).Count -ne 0) {
        throw "The direct fixture did not start with empty guardian ledgers."
    }
    Write-Success "Installed a real DataStore snapshot with one currently denying direct-grant rule."

    Invoke-AmbiguousConfirmationCase `
        -CaseName "direct" `
        -RuleId $directRuleId `
        -RuleName "E2E direct grant" `
        -ExpectedKind "direct_grant" `
        -ExpectedCurrentDenialName "Current denial after direct grant" `
        -SubmitOperation {
            param($gateId)
            Arm-ServiceEvaluationGate -GateId $gateId
            Arm-WriteFailure -GateId $gateId
            Tap-ApprovalAction -ResourceId "approval_add_time" -Description "Change extra time"
            $ui = Wait-For-UI -Pattern 'resource-id="neth\.iecal\.curbox\.debug:id/rule_picker"' -TimeoutSeconds 8
            $candidates = Get-TestRulePickerOptions -CurrentUi $ui -CandidateCount 1
            if (-not $candidates.Success) { throw "Could not confirm the real direct-grant candidate list." }
            $selection = Select-TestRulePickerOption -CurrentUi $candidates.Ui -CandidateOptions $candidates.Options -RuleName "E2E direct grant"
            if (-not $selection.Success) { throw "Could not select the direct-grant rule from the real form." }
            $inputNode = Get-NodeBounds $selection.Ui 'resource-id="neth.iecal.curbox.debug:id/additional_minutes_input"'
            if (-not $inputNode.Found) { throw "Could not find the direct-grant minutes field." }
            Invoke-TestDeviceShell -Command "input tap $($inputNode.X) $($inputNode.Y)"
            Invoke-TestDeviceShell -Command "input text 1"
            Tap-DialogApply
        } `
        -UpdateCurrentRules {
            $current = New-GuardianRule `
                -RuleId $directRuleId `
                -RuleName "Current denial after direct grant" `
                -GroupId $groupId `
                -GuardianExtraTimeAllowed $false
            $snapshot = New-GuardianSnapshot -GroupId $groupId -Rules @($current)
            Inject-TestAppRules -AppRuleSnapshot $snapshot -PreserveOverrides -PackageName $packageName
        }

    Invoke-TestDeviceShell -Command "input keyevent 3"
    Start-Sleep -Milliseconds 800
    Write-Step "4. Exercise the accumulated-pool receipt through the same service and Activity path..."
    $poolState = [PSCustomObject]@{
        pools = [PSCustomObject]@{
            $accumulatedRuleId = [PSCustomObject]@{
                ruleId = $accumulatedRuleId
                accumulatedMinutes = [long]20
                lastSettledUseDayId = ""
            }
        }
    }
    if (-not (Set-DeviceRolloverState -RolloverState $poolState -PackageName $packageName)) {
        throw "Could not seed the accumulated-pool DataStore state."
    }
    $accumulatedRule = New-GuardianRule `
        -RuleId $accumulatedRuleId `
        -RuleName "E2E accumulated grant" `
        -GroupId $groupId `
        -RolloverEnabled $true
    $accumulatedSnapshot = New-GuardianSnapshot -GroupId $groupId -Rules @($accumulatedRule)
    Inject-TestAppRules -AppRuleSnapshot $accumulatedSnapshot -PreserveOverrides -PackageName $packageName
    $accumulatedSetup = Get-DeviceSettings -PackageName $packageName -AsObject
    $accumulatedPool = Get-RuleRolloverPool -SettingsOrRolloverState $accumulatedSetup -RuleId $accumulatedRuleId
    if ([long]$accumulatedPool.accumulatedMinutes -ne 20) {
        throw "The accumulated fixture pool was not stored before the approval screen opened."
    }

    Invoke-AmbiguousConfirmationCase `
        -CaseName "accumulated" `
        -RuleId $accumulatedRuleId `
        -RuleName "E2E accumulated grant" `
        -ExpectedKind "accumulated_grant" `
        -ExpectedCurrentDenialName "Current denial after accumulated grant" `
        -SubmitOperation {
            param($gateId)
            Arm-ServiceEvaluationGate -GateId $gateId
            Arm-WriteFailure -GateId $gateId
            Tap-ApprovalAction -ResourceId "approval_use_accumulated_time" -Description "Use accumulated time"
            $ui = Wait-For-UI -Pattern 'accumulated_minutes_input' -TimeoutSeconds 8
            if ($ui -notmatch 'accumulated_minutes_input') { throw "The accumulated grant form did not appear." }
            Tap-DialogApply
        } `
        -UpdateCurrentRules {
            $current = New-GuardianRule `
                -RuleId $accumulatedRuleId `
                -RuleName "Current denial after accumulated grant" `
                -GroupId $groupId `
                -RolloverEnabled $true `
                -GuardianExtraTimeAllowed $false
            $snapshot = New-GuardianSnapshot -GroupId $groupId -Rules @($current)
            Inject-TestAppRules -AppRuleSnapshot $snapshot -PreserveOverrides -PackageName $packageName
        }

    Invoke-TestDeviceShell -Command "input keyevent 3"
    Start-Sleep -Milliseconds 800
    Write-Step "5. Exercise the skip receipt after the skipped rule is superseded..."
    $skipRule = New-GuardianRule -RuleId $skipRuleId -RuleName "E2E skip rule" -GroupId $groupId
    $skipSnapshot = New-GuardianSnapshot -GroupId $groupId -Rules @($skipRule)
    Inject-TestAppRules -AppRuleSnapshot $skipSnapshot -PreserveOverrides -PackageName $packageName
    Invoke-AmbiguousConfirmationCase `
        -CaseName "skip" `
        -RuleId $skipRuleId `
        -RuleName "E2E skip rule" `
        -ExpectedKind "rule_skip" `
        -ExpectedCurrentDenialName "Current denial after skip" `
        -SubmitOperation {
            param($gateId)
            Arm-ServiceEvaluationGate -GateId $gateId
            Arm-WriteFailure -GateId $gateId
            Tap-ApprovalAction -ResourceId "approval_skip_rule" -Description "Skip this rule"
            $ui = Wait-For-UI -Pattern 'Skip for 15 minutes|guardian_skip_15_minutes' -TimeoutSeconds 6
            $clicked = Tap-Node $ui 'text="Skip for 15 minutes"' "Skip for 15 minutes" -Optional
            if (-not $clicked) {
                $clicked = Tap-Node $ui 'resource-id="android:id/text1"[^>]*text="Skip for 15 minutes"' "Skip for 15 minutes" -Optional
            }
            if (-not $clicked) { throw "Could not select the 15-minute option in the actual skip dialog. UI: $ui" }
        } `
        -UpdateCurrentRules {
            $inactive = New-GuardianRule `
                -RuleId $skipRuleId `
                -RuleName "E2E skip rule" `
                -GroupId $groupId `
                -IsActive $false
            $current = New-GuardianRule `
                -RuleId $replacementSkipRuleId `
                -RuleName "Current denial after skip" `
                -GroupId $groupId `
                -GuardianExtraTimeAllowed $false
            $snapshot = New-GuardianSnapshot -GroupId $groupId -Rules @($inactive, $current)
            Inject-TestAppRules -AppRuleSnapshot $snapshot -PreserveOverrides -PackageName $packageName
        }

    $finalSettings = Get-DeviceSettings -PackageName $packageName -AsObject
    $finalGrantCount = @($finalSettings.appRuleOverrideState.grants).Count
    $finalSkipCount = @($finalSettings.appRuleOverrideState.skips).Count
    if ($finalGrantCount -ne 0 -or $finalSkipCount -ne 1) {
        throw "The final current snapshot should revoke both invalid grants while retaining one superseded skip receipt; observed grants=$finalGrantCount skips=$finalSkipCount."
    }
    if ((Get-GuardianApprovalLogs) -notmatch "write_committed id=$runId-(?:direct|accumulated|skip) ") {
        throw "The device log does not contain an actual post-commit failure injection."
    }

    Write-Host "`n==========================================================================" -ForegroundColor Green
    Write-Host ">>> [GUARDIAN RETRY AND SUPERSEDE DEVICE RESULT: PASS] <<<" -ForegroundColor Green
    Write-Host "Direct, accumulated, and skip receipts were each committed once by DataStore, evaluated by the bound :app_blocker_service worker, retried against a real updated snapshot, and protected from a superseded worker outcome." -ForegroundColor Green
    Write-Host "==========================================================================`n" -ForegroundColor Green
} finally {
    if ($activeGateId) {
        try {
            Send-TestBroadcast `
                -Action "neth.iecal.curbox.blockers.TEST_RELEASE_GUARDIAN_EVALUATION_GATE" `
                -GateId $activeGateId
        } catch {
            Write-Host "[WARN] Could not release service test gate '$activeGateId': $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    if ($writeFailureArmed) {
        try {
            Send-TestBroadcast `
                -Action "neth.iecal.curbox.guardian.TEST_CLEAR_WRITE_FAILURE" `
                -GateId ""
        } catch {
            Write-Host "[WARN] Could not clear the Activity write-failure hook: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    if (Test-Path -LiteralPath $backupFile) {
        Complete-DeviceTest `
            -BackupPath $backupFile `
            -TargetPackages @($TargetPackage) `
            -AccessibilitySettings $accessibilityBackup `
            -PackageName $packageName
        Start-Sleep -Seconds 1
        $restoredSettings = Get-DeviceSettings -PackageName $packageName
        $backupSettingsText = (Get-Content -LiteralPath $backupFile -Raw).Trim()
        if ($restoredSettings -ne $backupSettingsText) {
            $restoreVerified = $false
            Write-Host "[FAIL] Device settings.json did not return byte-for-byte to the saved user settings." -ForegroundColor Red
        }
        $enabledServices = Get-DeviceSecureSetting -Name "enabled_accessibility_services"
        $accessibilityEnabled = Get-DeviceSecureSetting -Name "accessibility_enabled"
        if ($enabledServices -ne [string]$accessibilityBackup.EnabledServices -or
            $accessibilityEnabled -ne [string]$accessibilityBackup.AccessibilityEnabled) {
            $restoreVerified = $false
            Write-Host "[FAIL] Accessibility settings did not return to their original values." -ForegroundColor Red
        }
        $overlayAfter = Get-TestDeviceShellOutput -Command "appops get $packageName SYSTEM_ALERT_WINDOW"
        $overlayModeAfter = if ($overlayAfter -match 'SYSTEM_ALERT_WINDOW:\s*(\w+)') { $matches[1] } else { "" }
        if ($originalOverlayMode -and $overlayModeAfter -ne $originalOverlayMode) {
            $restoreVerified = $false
            Write-Host "[FAIL] Overlay permission changed from '$originalOverlayMode' to '$overlayModeAfter'." -ForegroundColor Red
        }
    }
    Remove-Item -LiteralPath $backupFile -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tmpDir -Force -ErrorAction SilentlyContinue
}

if (-not $restoreVerified) {
    throw "The device checks completed, but the original settings or permissions did not restore exactly."
}
