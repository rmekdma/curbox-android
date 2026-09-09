param(
    [string]$Serial = 'T811MA256GB23418064398',
    [string]$AdbPath = 'C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe'
)

$ErrorActionPreference = 'Stop'
$packageName = 'neth.iecal.curbox.debug'
$serviceClass = 'neth.iecal.curbox.services.AppBlockerService'
$serviceComponent = "$packageName/$serviceClass"
$observerUri = 'content://neth.iecal.curbox.debug.ticket19.observer'
$refreshAction = 'neth.iecal.curbox.refresh.app_rules'
$mutationRuleTokenExtra = 'ticket19_mutation_rule_token'
$mutationBroadcastKeyExtra = 'ticket19_mutation_broadcast_key'

function Write-Trace([string]$Message) {
    Write-Host "$(Get-Date -Format o) $Message"
}

function Invoke-AdbCommand {
    param(
        [string[]]$CommandArgs,
        [switch]$Quiet
    )
    if (-not $Quiet) { Write-Trace "adb $($CommandArgs -join ' ')" }
    $output = & $AdbPath -s $Serial @CommandArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed ($LASTEXITCODE): $($CommandArgs -join ' ')`n$($output -join "`n")"
    }
    $text = $output -join "`n"
    if (-not $Quiet) { Write-Trace "output=$text" }
    return $text
}

function Get-EnabledServices {
    $raw = (Invoke-AdbCommand @('shell', 'settings', 'get', 'secure',
        'enabled_accessibility_services') -Quiet).Trim()
    if ([string]::IsNullOrWhiteSpace($raw) -or $raw -eq 'null') { return @() }
    return @($raw.Split(':') | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Get-AccessibilityEnabled {
    return [int](Invoke-AdbCommand @('shell', 'settings', 'get', 'secure',
        'accessibility_enabled') -Quiet).Trim()
}

function Get-Wakefulness {
    $power = Invoke-AdbCommand @('shell', 'dumpsys', 'power') -Quiet
    $match = [regex]::Match($power, '(?m)^\s*mWakefulness=(?<state>\w+)\s*$')
    if (-not $match.Success) { throw 'unable to read device wakefulness' }
    return $match.Groups['state'].Value
}

function Test-CurboxComponent([string]$Component) {
    return $Component -eq $serviceComponent -or
        $Component -eq "$packageName/.services.AppBlockerService"
}

function Assert-AccessibilityState {
    param([string[]]$ExpectedServices, [int]$ExpectedEnabled, [string]$Stage)
    $actualServices = @(Get-EnabledServices)
    $actualEnabled = Get-AccessibilityEnabled
    if (($actualServices -join ':') -cne ($ExpectedServices -join ':') -or
        $actualEnabled -ne $ExpectedEnabled) {
        throw "$Stage concurrent accessibility change: expected services=$($ExpectedServices -join ':') enabled=$ExpectedEnabled; actual services=$($actualServices -join ':') enabled=$actualEnabled"
    }
    Write-Trace "$Stage verified services=$($actualServices -join ':') accessibility_enabled=$actualEnabled"
}

function Set-OwnedAccessibilityState {
    param([string[]]$Services, [int]$Enabled, [string]$Stage)
    Assert-AccessibilityState $script:ownedServices $script:ownedEnabled "$Stage pre-write"
    Invoke-AdbCommand @('shell', 'settings', 'put', 'secure',
        'accessibility_enabled', '0') | Out-Null
    Assert-AccessibilityState $script:ownedServices 0 "$Stage disabled"
    $script:ownedEnabled = 0
    Assert-AccessibilityState $script:ownedServices $script:ownedEnabled "$Stage pre-list-write"
    if ($Services.Count -eq 0) {
        Invoke-AdbCommand @('shell', 'settings', 'delete', 'secure',
            'enabled_accessibility_services') | Out-Null
    } else {
        Invoke-AdbCommand @('shell', 'settings', 'put', 'secure',
            'enabled_accessibility_services', ($Services -join ':')) | Out-Null
    }
    # Android 13 may derive the global flag when the list value changes, but leaves it unchanged
    # when the same list is written. Own the observed boolean only after the list is exact, then
    # converge it with the explicit final flag write below.
    $actualAfterList = @(Get-EnabledServices)
    $actualEnabledAfterList = Get-AccessibilityEnabled
    if (($actualAfterList -join ':') -cne ($Services -join ':') -or
        $actualEnabledAfterList -notin @(0, 1)) {
        throw "$Stage list write result was not exact: expected services=$($Services -join ':'); actual services=$($actualAfterList -join ':') enabled=$actualEnabledAfterList"
    }
    $script:ownedServices = @($Services)
    $script:ownedEnabled = $actualEnabledAfterList
    Write-Trace "$Stage list-written verified services=$($actualAfterList -join ':') platform_accessibility_enabled=$actualEnabledAfterList"
    Invoke-AdbCommand @('shell', 'settings', 'put', 'secure',
        'accessibility_enabled', $Enabled.ToString()) | Out-Null
    Assert-AccessibilityState $script:ownedServices $Enabled "$Stage complete"
    $script:ownedEnabled = $Enabled
}

function Enable-Curbox([string]$Stage) {
    $after = @($script:ownedServices | Where-Object { -not (Test-CurboxComponent $_) }) +
        @($serviceComponent)
    Set-OwnedAccessibilityState $after 1 $Stage
}

function Disable-Curbox([string]$Stage) {
    $after = @($script:ownedServices | Where-Object { -not (Test-CurboxComponent $_) })
    Set-OwnedAccessibilityState $after $(if ($after.Count -eq 0) { 0 } else { 1 }) $Stage
}

function Test-FrameworkBound {
    $dump = Invoke-AdbCommand @('shell', 'dumpsys', 'accessibility') -Quiet
    $boundServices = [regex]::Match(
        $dump,
        '(?s)Bound services:\{(?<services>.*?)\}\s*Enabled services:'
    )
    return $boundServices.Success -and
        $boundServices.Groups['services'].Value -match 'Curbox App Blocker'
}

function Get-ServicePid {
    $raw = (Invoke-AdbCommand @('shell', 'pidof',
        "$packageName`:app_blocker_service") -Quiet).Trim()
    $pidValue = 0
    if ([int]::TryParse($raw, [ref]$pidValue)) { return $pidValue }
    return -1
}

function Get-PackageState {
    return Invoke-AdbCommand @('shell', 'dumpsys', 'package', $packageName) -Quiet
}

function Assert-PackageNotStopped {
    $state = Get-PackageState
    if ($state -notmatch 'stopped=false') { throw "package is not stopped=false" }
    Write-Trace 'package stopped=false'
}

function Get-LogcatCursor {
    $tail = Invoke-AdbCommand @('shell', 'logcat', '-d', '-v', 'epoch', '-t', '1') -Quiet
    $match = [regex]::Match($tail, '(?m)^\s*(?<cursor>\d+\.\d+)\s+')
    if (-not $match.Success) { throw "unable to capture non-destructive logcat cursor: $tail" }
    return $match.Groups['cursor'].Value
}

function Invoke-ObserverCommand([string]$Method, [string]$Argument = '0') {
    $raw = Invoke-AdbCommand @('shell', 'content', 'call', '--uri', $observerUri,
        '--method', $Method, '--arg', $Argument) -Quiet
    $prefix = 'Result: Bundle[{result='
    if (-not $raw.StartsWith($prefix) -or -not $raw.EndsWith('}]')) {
        throw "unexpected observer response: $raw"
    }
    $json = $raw.Substring($prefix.Length, $raw.Length - $prefix.Length - 2)
    return $json | ConvertFrom-Json
}

function Get-ActivityState {
    return Invoke-AdbCommand @('shell', 'dumpsys', 'activity', 'activities') -Quiet
}

function Get-TopResumedActivity([string]$ActivityState) {
    $match = [regex]::Match(
        $ActivityState,
        '(?m)^\s*(?:topResumedActivity|ResumedActivity):.*?\s(?<component>[^\s}]+/[^\s}]+)'
    )
    if (-not $match.Success) { return '' }
    return $match.Groups['component'].Value
}

function Test-GuardianActivityPresent([string]$ActivityState) {
    return $ActivityState -match [regex]::Escape(
        "$packageName/neth.iecal.curbox.ui.activity.GuardianApprovalActivity"
    )
}

function Wait-Until {
    param(
        [string]$Description,
        [scriptblock]$Condition,
        [int]$TimeoutSeconds = 30
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            if (& $Condition) {
                Write-Trace "reached=$Description"
                return
            }
        } catch {
        }
        Start-Sleep -Milliseconds 100
    }
    throw "timed out waiting for $Description"
}

function Assert-NoFailures($Snapshot) {
    if (@($Snapshot.failures).Count -ne 0) {
        throw "observer failures: $($Snapshot | ConvertTo-Json -Depth 12 -Compress)"
    }
}

function Assert-Ready($Snapshot) {
    if (-not $Snapshot.appRuleSetupReady -or $Snapshot.activeReceiverCount -ne 5) {
        throw "service not ready: $($Snapshot | ConvertTo-Json -Depth 12 -Compress)"
    }
    foreach ($receiver in @($Snapshot.receiverOwnership)) {
        if (-not $receiver.active -or $receiver.identity -eq 0 -or
            $receiver.registrationIdentity -eq 0 -or
            $receiver.ownerServiceIdentity -ne $Snapshot.serviceIdentity) {
            throw "invalid receiver ownership: $($receiver | ConvertTo-Json -Compress)"
        }
    }
    $serviceWide = @($Snapshot.serviceWideReceiverOwnership)
    if ($serviceWide.Count -ne 15 -or @($serviceWide | Where-Object { $_.identity -eq 0 }).Count -ne 0) {
        throw "invalid service-wide receiver ownership: $($serviceWide | ConvertTo-Json -Compress)"
    }
    Assert-NoFailures $Snapshot
}

function Wait-ReadySnapshot([string]$Description) {
    $script:latestSnapshot = $null
    Wait-Until $Description {
        $script:latestSnapshot = Invoke-ObserverCommand 'snapshot'
        return $script:latestSnapshot.appRuleSetupReady -and
            $script:latestSnapshot.activeReceiverCount -eq 5 -and
            @($script:latestSnapshot.serviceWideReceiverOwnership).Count -eq 15
    }
    Assert-Ready $script:latestSnapshot
    return $script:latestSnapshot
}

function Send-Refresh([string]$Stage) {
    $token = [guid]::NewGuid().ToString()
    $sentAt = Get-Date -Format o
    $output = Invoke-AdbCommand @('shell', 'am', 'broadcast', '-a',
        $refreshAction, '-p', $packageName, '--es', 'ticket19_run_token', $token)
    if ($output -notmatch 'result=0') { throw "matching broadcast failed: $output" }
    Write-Trace "$Stage sent token=$token sentAt=$sentAt"
    return [pscustomobject]@{ Token = $token; SentAt = $sentAt }
}

function Get-CorrelatedRefreshDelivery([string]$Token, [int]$ExpectedPid) {
    $dump = Invoke-AdbCommand @('shell', 'dumpsys', 'activity', 'broadcasts') -Quiet
    $pattern = '(?ms)Historical Broadcast background #\d+:\s+BroadcastRecord\{[^\r\n]*neth\.iecal\.curbox\.refresh\.app_rules\}.*?(?=\r?\n\s*Historical Broadcast background #|\r?\n\s*Historical broadcasts summary)'
    $records = @([regex]::Matches($dump, $pattern) | Where-Object {
        $_.Value -match [regex]::Escape($Token)
    })
    if ($records.Count -ne 1) {
        throw "token-correlated broadcast record count expected=1 actual=$($records.Count) token=$Token"
    }
    $deliveries = [regex]::Matches($records[0].Value, '(?m)^\s*Deliver .*#\d+:.*$')
    if ($deliveries.Count -ne 1 -or
        $deliveries[0].Value -notmatch "\s$ExpectedPid\s+$([regex]::Escape("$packageName`:app_blocker_service"))/") {
        throw "token-correlated delivery mismatch token=$Token expectedPid=$ExpectedPid record=$($records[0].Value.Trim())"
    }
    return $deliveries[0].Value.Trim()
}

function Send-PassThroughRefresh {
    param(
        [string]$BroadcastKey,
        [string]$RuleToken,
        [string]$RunToken = '',
        [string]$Operation = ''
    )
    $arguments = @(
        'shell', 'am', 'broadcast', '-a', $refreshAction, '-p', $packageName,
        '--es', $mutationBroadcastKeyExtra, $BroadcastKey,
        '--es', $mutationRuleTokenExtra, $RuleToken
    )
    if (-not [string]::IsNullOrWhiteSpace($RunToken)) {
        $arguments += @('--es', 'ticket19_run_token', $RunToken)
    }
    if (-not [string]::IsNullOrWhiteSpace($Operation)) {
        $arguments += @('--es', 'ticket19_mutation_operation', $Operation)
    }
    $output = Invoke-AdbCommand $arguments
    if ($output -notmatch 'result=0') {
        throw "pass-through broadcast failed: $output"
    }
    return $output
}

function Get-PassThroughObservation($Snapshot, [string]$BroadcastKey, [string]$RuleToken) {
    return @($Snapshot.mutationPassThroughObservations | Where-Object {
        $_.key -eq $BroadcastKey -and $_.ruleToken -eq $RuleToken
    })
}

function Assert-PassThroughRefresh {
    param(
        [string]$BroadcastKey,
        [string]$RuleToken,
        [string]$RunToken,
        [string]$ExpectedState,
        [int]$ExpectedFailureCount,
        [string]$Stage
    )
    Send-PassThroughRefresh $BroadcastKey $RuleToken $RunToken | Out-Null
    Wait-Until "$Stage matching source reservation/publication" {
        $snapshot = Invoke-ObserverCommand 'snapshot'
        $matches = @(Get-PassThroughObservation $snapshot $BroadcastKey $RuleToken)
        return $matches.Count -eq 1 -and
            $snapshot.mutationRefreshState -eq $ExpectedState -and
            [long]$matches[0].reservationCount -eq 1 -and
            [long]$matches[0].publicationCount -eq 1 -and
            @($snapshot.failures).Count -eq $ExpectedFailureCount
    }
    $completed = Invoke-ObserverCommand 'snapshot'
    $matches = @(Get-PassThroughObservation $completed $BroadcastKey $RuleToken)
    if ($matches.Count -ne 1 -or
        [long]$matches[0].reservationCount -ne 1 -or
        [long]$matches[0].publicationCount -ne 1) {
        throw "$Stage did not correlate exactly one production reservation/publication: $($completed | ConvertTo-Json -Depth 12 -Compress)"
    }
    if ([long]$matches[0].sourceIdentity -le 0 -or
        [long]$matches[0].runtimeRevision -le 0) {
        throw "$Stage did not expose a valid source/revision pair: $($completed | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Trace "$Stage correlated key=$BroadcastKey ruleToken=$RuleToken source=$($matches[0].sourceIdentity) revision=$($matches[0].runtimeRevision)"
    return $completed
}

function Assert-WorkCountsZero($Snapshot, [string]$Stage) {
    foreach ($property in $Snapshot.workCounts.PSObject.Properties) {
        if ([int]$property.Value -ne 0) { throw "$Stage work count $($property.Name)=$($property.Value)" }
    }
}

function Wait-MutationWorkQuiescent([string]$Stage) {
    Wait-Until "$Stage mutation work quiescence" {
        $snapshot = Invoke-ObserverCommand 'snapshot'
        foreach ($name in @(
            'refreshes', 'notifications', 'usageResetCompletions',
            'recheckPlans', 'workerQueued', 'workerInFlight'
        )) {
            if ([int]$snapshot.workCounts.$name -ne 0) { return $false }
        }
        return $true
    }
    return Invoke-ObserverCommand 'snapshot'
}

function Assert-MutationRefresh {
    param(
        $MutationSnapshot,
        [string]$Operation,
        [string]$RuleToken,
        [int]$ExpectedPid,
        [long]$BeforeCompletionCount,
        [int]$ExpectedFilterCount,
        [string]$Stage,
        [int]$ExpectedFailureCount = 0
    )
    $refreshToken = [string]$MutationSnapshot.mutationRefreshToken
    if ([string]::IsNullOrWhiteSpace($refreshToken)) {
        throw "$Stage did not return a UUID-tagged mutation refresh"
    }
    $broadcastKey = [guid]::NewGuid().ToString()
    $sentAt = Get-Date -Format o
    Send-PassThroughRefresh $broadcastKey $RuleToken $refreshToken $Operation | Out-Null
    Write-Trace "$Stage sent ruleToken=$RuleToken refreshToken=$refreshToken sentAt=$sentAt"
    $completed = Invoke-ObserverCommand 'await_mutation_refresh' "$Operation`:$refreshToken"
    if (@($completed.failures).Count -ne $ExpectedFailureCount) {
        throw "$Stage changed the observer failure set: $($completed | ConvertTo-Json -Depth 12 -Compress)"
    }
    if ($completed.mutationRefreshRuleToken -ne $RuleToken -or
        $completed.mutationRefreshToken -ne $refreshToken -or
        $completed.mutationRefreshOperation -ne $Operation -or
        $completed.mutationRefreshState -ne 'COMPLETED' -or
        $completed.mutationRefreshPid -ne $ExpectedPid -or
        $completed.mutationRefreshDeliveryCount -ne 1 -or
        $completed.mutationRefreshReservationCount -ne 1 -or
        $completed.mutationRefreshPublicationCount -ne 1 -or
        $completed.mutationRefreshCompletionCount -le $BeforeCompletionCount -or
        [long]$completed.mutationRefreshSourceIdentity -le 0 -or
        [long]$completed.mutationRefreshRuntimeRevision -le 0) {
        throw "$Stage exact mutation refresh correlation failed: $($completed | ConvertTo-Json -Depth 12 -Compress)"
    }
    foreach ($name in @(
        'refreshes', 'notifications', 'usageResetCompletions',
        'recheckPlans', 'workerQueued', 'workerInFlight'
    )) {
        if ([int]$completed.workCounts.$name -ne 0) {
            throw "$Stage work $name was not quiescent: $($completed | ConvertTo-Json -Depth 12 -Compress)"
        }
    }
    if (@(Get-PassThroughObservation $completed $broadcastKey $RuleToken).Count -ne 0) {
        throw "$Stage owned delivery reached the pass-through production boundary: $($completed | ConvertTo-Json -Depth 12 -Compress)"
    }
    $delivery = Get-CorrelatedRefreshDelivery $refreshToken $ExpectedPid
    $ack = @($completed.events | Where-Object {
        $_.name -eq 'mutation_refresh_callback_completed' -and
        $_.detail -match [regex]::Escape("operation=$Operation") -and
        $_.detail -match [regex]::Escape("ruleToken=$RuleToken") -and
        $_.detail -match [regex]::Escape("refreshToken=$refreshToken")
    })
    if ($ack.Count -ne 1) {
        throw "$Stage callback completion ACK was not uniquely correlated"
    }
    Assert-MutationCleanupState $completed $ExpectedPid $ExpectedFilterCount $Stage
    Write-Trace "$Stage exact delivery token=$refreshToken line=$delivery"
    return $completed
}

function Arm-MutationProbe {
    param(
        [string]$Operation,
        [string]$RuleToken,
        [string]$Stage
    )
    $before = Invoke-ObserverCommand 'snapshot'
    $beforeFailures = @($before.failures).Count
    $armed = Invoke-ObserverCommand 'arm_mutation_probe' "$Operation`:$RuleToken"
    if ($armed.mutationRefreshState -ne 'ARMED' -or
        $armed.mutationRefreshOperation -ne $Operation -or
        $armed.mutationRefreshRuleToken -ne $RuleToken -or
        [string]::IsNullOrWhiteSpace([string]$armed.mutationRefreshToken) -or
        @($armed.failures).Count -ne $beforeFailures) {
        throw "$Stage did not arm an independent mutation probe: $($armed | ConvertTo-Json -Depth 12 -Compress)"
    }
    return $armed
}

function Assert-OwnedMalformedProbe {
    param(
        [string]$Operation,
        [string]$Stage
    )
    $probeRuleToken = [guid]::NewGuid().ToString()
    $armed = Arm-MutationProbe $Operation $probeRuleToken "$Stage arm"
    $refreshToken = [string]$armed.mutationRefreshToken
    if ([string]::IsNullOrWhiteSpace($refreshToken)) {
        throw "$Stage did not return a UUID-tagged mutation refresh"
    }
    $beforeFailures = @($armed.failures).Count
    $malformedKey = [guid]::NewGuid().ToString()
    $malformedRuleToken = [guid]::NewGuid().ToString()
    $malformedOperation = if ($Operation -eq 'install') { 'remove' } else { 'install' }
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    Send-PassThroughRefresh $malformedKey $malformedRuleToken $refreshToken $malformedOperation | Out-Null
    $failed = Invoke-ObserverCommand 'await_mutation_refresh' "$Operation`:$refreshToken"
    $timer.Stop()
    if ($timer.Elapsed.TotalSeconds -ge 5) {
        throw "$Stage await_mutation_refresh did not surface FAILED promptly: elapsed=$($timer.Elapsed.TotalSeconds)s"
    }
    if ($failed.mutationRefreshState -ne 'FAILED' -or
        @($failed.failures).Count -ne ($beforeFailures + 1) -or
        $failed.mutationRefreshReceiverRegistered -ne $false -or
        $failed.mutationRefreshPassThroughReceiverRegistered -ne $true -or
        $failed.mutationRefreshRuleToken -ne $probeRuleToken -or
        $failed.mutationRefreshOperation -ne $Operation -or
        [long]$failed.mutationRefreshDeliveryCount -ne 0) {
        throw "$Stage malformed owned delivery did not fail deterministically and leave only recovery receiver: $($failed | ConvertTo-Json -Depth 12 -Compress)"
    }
    if (@(Get-PassThroughObservation $failed $malformedKey $malformedRuleToken).Count -ne 0) {
        throw "$Stage malformed owned delivery reached a matching production reservation/publication: $($failed | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Trace "$Stage malformed owned delivery failed promptly; high-priority receiver was absent and recovery receiver retained"
    return [pscustomobject]@{
        Armed = $armed
        Failed = $failed
        Operation = $Operation
        FailureCount = $beforeFailures + 1
    }
}

function Assert-NoExtraMalformedRecovery {
    param(
        $Probe,
        [int]$ExpectedPid,
        [int]$ExpectedFilterCount,
        [string]$Stage
    )
    $beforeUncorrelated = [long]$Probe.Failed.mutationRefreshUncorrelatedDeliveryCount
    $send = Invoke-AdbCommand @(
        'shell', 'am', 'broadcast', '-a', $refreshAction, '-p', $packageName
    )
    if ($send -notmatch 'result=0') {
        throw "$Stage no-extra unowned recovery failed: $send"
    }
    Wait-Until "$Stage no-extra production boundary" {
        $snapshot = Invoke-ObserverCommand 'snapshot'
        return [long]$snapshot.mutationRefreshUncorrelatedDeliveryCount -eq ($beforeUncorrelated + 1) -and
            $snapshot.mutationRefreshState -eq 'FAILED' -and
            $snapshot.mutationRefreshReceiverRegistered -eq $false -and
            $snapshot.mutationRefreshPassThroughReceiverRegistered -eq $false -and
            $snapshot.mutationRefreshProductionReceiverRegistered -eq $true -and
            @($snapshot.failures).Count -eq $Probe.FailureCount
    }
    $recovered = Wait-MutationWorkQuiescent $Stage
    if (@($recovered.failures).Count -ne $Probe.FailureCount) {
        throw "$Stage no-extra recovery introduced an observer failure: $($recovered | ConvertTo-Json -Depth 12 -Compress)"
    }
    Assert-MutationCleanupState $recovered $ExpectedPid $ExpectedFilterCount $Stage
    Write-Trace "$Stage no-extra unowned recovery reached production once and unregistered the retained receiver"
    return $recovered
}

function Assert-UnregisterFailureRecovery {
    param(
        [string]$Operation,
        [int]$ExpectedPid,
        [int]$ExpectedFilterCount,
        [string]$Stage
    )
    $capability = Invoke-ObserverCommand 'snapshot'
    if ($capability.PSObject.Properties.Name -notcontains 'mutationRefreshUnregisterFailureInjectionAvailable' -or
        $capability.mutationRefreshUnregisterFailureInjectionAvailable -ne $true) {
        Write-Trace "$Stage unregister failure injection unavailable; skipped without claiming evidence"
        return $null
    }

    $probe = Assert-OwnedMalformedProbe $Operation "$Stage malformed"
    $baseline = Invoke-ObserverCommand 'snapshot'
    $baselineFilterCount = @(Get-ActiveServiceProcessFilters $ExpectedPid).Count
    if ($baseline.mutationRefreshState -ne 'FAILED' -or
        $baseline.mutationRefreshReceiverRegistered -ne $false -or
        $baseline.mutationRefreshPassThroughReceiverRegistered -ne $true -or
        $baseline.mutationRefreshProductionReceiverRegistered -ne $false -or
        $baseline.mutationRefreshProductionReceiverDetached -ne $true -or
        $baseline.mutationRefreshUnregisterFailureInjectionArmed -ne $false -or
        @($baseline.failures).Count -ne $probe.FailureCount -or
        $baselineFilterCount -ne $ExpectedFilterCount) {
        throw "$Stage malformed-owned probe was not finished before baselines: $($baseline | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Trace "$Stage malformed-owned probe finished; pass-through retained, tagged absent, production detached"

    $beforeFailures = @($baseline.failures).Count
    $beforeRetryCount = [long]$baseline.mutationRefreshCleanupRetryCount
    $beforeRestoreEvents = @($baseline.events | Where-Object {
        $_.name -eq 'mutation_refresh_production_receiver_restored' -and
        $_.detail -match 'stage=mutation_refresh_cleanup_retry'
    }).Count
    $broadcastKey = [guid]::NewGuid().ToString()
    $broadcastRuleToken = [guid]::NewGuid().ToString()
    if (@(Get-PassThroughObservation $baseline $broadcastKey $broadcastRuleToken).Count -ne 0) {
        throw "$Stage fresh key unexpectedly had an existing observation"
    }
    $armed = Invoke-ObserverCommand 'inject_mutation_unregister_failure'
    if ($armed.mutationRefreshUnregisterFailureInjectionArmed -ne $true -or
        $armed.mutationRefreshState -ne $baseline.mutationRefreshState -or
        @($armed.failures).Count -ne $beforeFailures -or
        [long]$armed.mutationRefreshCleanupRetryCount -ne $beforeRetryCount -or
        $armed.mutationRefreshReceiverRegistered -ne $baseline.mutationRefreshReceiverRegistered -or
        $armed.mutationRefreshPassThroughReceiverRegistered -ne $baseline.mutationRefreshPassThroughReceiverRegistered -or
        $armed.mutationRefreshProductionReceiverRegistered -ne $baseline.mutationRefreshProductionReceiverRegistered -or
        $armed.mutationRefreshProductionReceiverDetached -ne $baseline.mutationRefreshProductionReceiverDetached -or
        [long](Get-ActiveServiceProcessFilters $ExpectedPid).Count -ne $baselineFilterCount) {
        throw "$Stage injection arm changed the recorded baselines: $($armed | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Trace "$Stage pass-through unregister failure injection armed immediately before key=$broadcastKey ruleToken=$broadcastRuleToken"

    $delegated = Assert-PassThroughRefresh `
        $broadcastKey $broadcastRuleToken '' 'FAILED' ($beforeFailures + 1) `
        "$Stage fresh unowned refresh during failed cleanup"
    if ($delegated.mutationRefreshReceiverRegistered -ne $false -or
        $delegated.mutationRefreshPassThroughReceiverRegistered -ne $true -or
        $delegated.mutationRefreshProductionReceiverRegistered -ne $false -or
        $delegated.mutationRefreshProductionReceiverDetached -ne $true -or
        $delegated.mutationRefreshUnregisterFailureInjectionArmed -ne $false -or
        [long]$delegated.mutationRefreshCleanupRetryCount -ne $beforeRetryCount -or
        [long](Get-ActiveServiceProcessFilters $ExpectedPid).Count -ne $baselineFilterCount) {
        throw "$Stage failed cleanup did not retain only pass-through and detached production: $($delegated | ConvertTo-Json -Depth 12 -Compress)"
    }
    $match = @(Get-PassThroughObservation $delegated $broadcastKey $broadcastRuleToken)
    $sourceIdentity = [long]$match[0].sourceIdentity
    $runtimeRevision = [long]$match[0].runtimeRevision
    $revisionBefore = [long]$match[0].revisionBefore
    if ($match.Count -ne 1 -or
        $sourceIdentity -le 0 -or
        $runtimeRevision -le 0 -or
        $revisionBefore -lt 0 -or
        $runtimeRevision -ne ($revisionBefore + 1L)) {
        throw "$Stage fresh unowned refresh did not expose its exact source/revision capture: $($delegated | ConvertTo-Json -Depth 12 -Compress)"
    }
    $delegationEvents = @($delegated.events | Where-Object {
        $_.name -eq 'mutation_pass_through_delegation' -and
        $_.detail -match [regex]::Escape("key=$broadcastKey") -and
        $_.detail -match [regex]::Escape("ruleToken=$broadcastRuleToken")
    })
    $reservationEvents = @($delegated.events | Where-Object {
        $_.name -eq 'mutation_pass_through_reservation' -and
        $_.detail -match [regex]::Escape("key=$broadcastKey") -and
        $_.detail -match [regex]::Escape("ruleToken=$broadcastRuleToken") -and
        $_.detail -match [regex]::Escape("revisionBefore=$revisionBefore") -and
        $_.detail -match [regex]::Escape("sourceIdentity=$sourceIdentity") -and
        $_.detail -match [regex]::Escape("runtimeRevision=$runtimeRevision")
    })
    $publicationEvents = @($delegated.events | Where-Object {
        $_.name -eq 'mutation_pass_through_publication' -and
        $_.detail -match [regex]::Escape("key=$broadcastKey") -and
        $_.detail -match [regex]::Escape("ruleToken=$broadcastRuleToken") -and
        $_.detail -match [regex]::Escape("sourceIdentity=$sourceIdentity") -and
        $_.detail -match [regex]::Escape("runtimeRevision=$runtimeRevision")
    })
    $completedEvents = @($delegated.events | Where-Object {
        $_.name -eq 'mutation_pass_through_delivery_completed' -and
        $_.detail -match [regex]::Escape("key=$broadcastKey") -and
        $_.detail -match [regex]::Escape("ruleToken=$broadcastRuleToken") -and
        $_.detail -match 'passThroughRegistered=True' -and
        $_.detail -match 'taggedRegistered=False' -and
        $_.detail -match 'productionDetached=True'
    })
    if ($delegationEvents.Count -ne 1 -or
        $reservationEvents.Count -ne 1 -or
        $publicationEvents.Count -ne 1 -or
        $completedEvents.Count -ne 1 -or
        $delegationEvents[0].detail -notmatch 'productionDetached=True') {
        throw "$Stage fresh unowned refresh did not produce exactly one detached delegation and keyed reservation/publication: $($delegated | ConvertTo-Json -Depth 12 -Compress)"
    }
    $delegatedQuiescent = Wait-MutationWorkQuiescent "$Stage delegated refresh"
    $lastFailure = @($delegatedQuiescent.failures)[-1]
    if (@($delegatedQuiescent.failures).Count -ne ($beforeFailures + 1) -or
        $lastFailure.stage -ne 'mutation_refresh_pass_through_cleanup' -or
        $delegatedQuiescent.mutationRefreshReceiverRegistered -ne $false -or
        $delegatedQuiescent.mutationRefreshPassThroughReceiverRegistered -ne $true -or
        $delegatedQuiescent.mutationRefreshProductionReceiverRegistered -ne $false -or
        $delegatedQuiescent.mutationRefreshProductionReceiverDetached -ne $true -or
        [long]$delegatedQuiescent.mutationRefreshCleanupRetryCount -ne $beforeRetryCount -or
        [long](Get-ActiveServiceProcessFilters $ExpectedPid).Count -ne $baselineFilterCount) {
        throw "$Stage delegated refresh changed failure, cleanup, or actual production filter baselines: $($delegatedQuiescent | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Trace "$Stage delegated exactly once with key=$broadcastKey source=$sourceIdentity revision=$runtimeRevision; pass-through retained and production stayed detached"

    $retry = Invoke-ObserverCommand 'retry_mutation_receiver_cleanup'
    if ([long]$retry.mutationRefreshCleanupRetryCount -ne ($beforeRetryCount + 1)) {
        throw "$Stage cleanup retry counter did not advance: $($retry | ConvertTo-Json -Depth 12 -Compress)"
    }
    Assert-MutationCleanupState $retry $ExpectedPid $ExpectedFilterCount "$Stage cleanup retry"
    if (@($retry.failures).Count -ne ($beforeFailures + 1)) {
        throw "$Stage cleanup retry changed the recorded failure set: $($retry | ConvertTo-Json -Depth 12 -Compress)"
    }
    $restoreEvents = @($retry.events | Where-Object {
        $_.name -eq 'mutation_refresh_production_receiver_restored' -and
        $_.detail -match 'stage=mutation_refresh_cleanup_retry'
    })
    if ($restoreEvents.Count -ne ($beforeRestoreEvents + 1) -or
        @(Get-PassThroughObservation $retry $broadcastKey $broadcastRuleToken).Count -ne 1 -or
        [long](@(Get-PassThroughObservation $retry $broadcastKey $broadcastRuleToken)[0].reservationCount) -ne 1 -or
        [long](@(Get-PassThroughObservation $retry $broadcastKey $broadcastRuleToken)[0].publicationCount) -ne 1 -or
        [long](Get-ActiveServiceProcessFilters $ExpectedPid).Count -ne $ExpectedFilterCount) {
        throw "$Stage cleanup retry did not restore production exactly once without changing the matching publication: $($retry | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Trace "$Stage cleanup retry removed the retained handle and restored production filter inventory"

    $idempotentRetry = Invoke-ObserverCommand 'retry_mutation_receiver_cleanup'
    Assert-MutationCleanupState $idempotentRetry $ExpectedPid $ExpectedFilterCount "$Stage idempotent cleanup retry"
    $idempotentRestoreEvents = @($idempotentRetry.events | Where-Object {
        $_.name -eq 'mutation_refresh_production_receiver_restored' -and
        $_.detail -match 'stage=mutation_refresh_cleanup_retry'
    })
    $idempotentMatch = @(Get-PassThroughObservation $idempotentRetry $broadcastKey $broadcastRuleToken)
    if (@($idempotentRetry.failures).Count -ne ($beforeFailures + 1) -or
        $idempotentRestoreEvents.Count -ne ($beforeRestoreEvents + 1) -or
        $idempotentMatch.Count -ne 1 -or
        [long]$idempotentMatch[0].reservationCount -ne 1 -or
        [long]$idempotentMatch[0].publicationCount -ne 1 -or
        [long](Get-ActiveServiceProcessFilters $ExpectedPid).Count -ne $ExpectedFilterCount) {
        throw "$Stage idempotent cleanup retry changed the matching failure, publication, or restore evidence: $($idempotentRetry | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Trace "$Stage repeated cleanup retry was idempotent"

    $nextRuleToken = [guid]::NewGuid().ToString()
    $next = Arm-MutationProbe $Operation $nextRuleToken "$Stage next probe arm"
    if ($next.mutationRefreshRuleToken -ne $nextRuleToken -or
        $next.mutationRefreshToken -eq $probe.Armed.mutationRefreshToken) {
        throw "$Stage next probe did not arm with fresh ownership UUIDs: $($next | ConvertTo-Json -Depth 12 -Compress)"
    }
    $nextCleanup = Invoke-ObserverCommand 'retry_mutation_receiver_cleanup'
    Assert-MutationCleanupState $nextCleanup $ExpectedPid $ExpectedFilterCount "$Stage next probe cleanup"
    Write-Trace "$Stage next probe armed after cleanup retry and was explicitly cleaned"
    return Assert-RestoredProductionRefresh `
        $ExpectedPid $ExpectedFilterCount "$Stage restored production refresh"
}

function Assert-OwnedMalformedMutationThenPassThrough {
    param(
        [string]$Operation,
        [int]$ExpectedPid,
        [string]$Stage
    )
    $preProbeFilterCount = @(Get-ActiveServiceProcessFilters $ExpectedPid).Count
    if ($preProbeFilterCount -le 0) {
        throw "$Stage could not establish the pre-probe framework filter inventory"
    }
    $noExtraProbe = Assert-OwnedMalformedProbe $Operation "$Stage no-extra"
    Assert-NoExtraMalformedRecovery $noExtraProbe $ExpectedPid $preProbeFilterCount "$Stage no-extra recovery" | Out-Null

    $correlatedProbe = Assert-OwnedMalformedProbe $Operation "$Stage correlated"
    $passThroughKey = [guid]::NewGuid().ToString()
    $passThroughRuleToken = [guid]::NewGuid().ToString()
    $afterPassThrough = Assert-PassThroughRefresh `
        $passThroughKey $passThroughRuleToken '' 'FAILED' $correlatedProbe.FailureCount `
        "$Stage correlated subsequent unowned"
    if ($afterPassThrough.mutationRefreshReceiverRegistered -ne $false -or
        $afterPassThrough.mutationRefreshPassThroughReceiverRegistered -ne $false -or
        $afterPassThrough.mutationRefreshProductionReceiverRegistered -ne $true) {
        throw "$Stage correlated subsequent unowned refresh did not leave mutation receivers absent: $($afterPassThrough | ConvertTo-Json -Depth 12 -Compress)"
    }
    Assert-MutationCleanupState $afterPassThrough $ExpectedPid $preProbeFilterCount "$Stage correlated subsequent unowned cleanup"
    if ([long]$afterPassThrough.mutationRefreshDeliveryCount -ne 0) {
        throw "$Stage correlated subsequent unowned refresh was claimed by the owned receiver: $($afterPassThrough | ConvertTo-Json -Depth 12 -Compress)"
    }
    $quiescent = Wait-MutationWorkQuiescent "$Stage correlated subsequent unowned"
    if (@($quiescent.failures).Count -ne $correlatedProbe.FailureCount) {
        throw "$Stage correlated subsequent unowned refresh introduced an observer failure: $($quiescent | ConvertTo-Json -Depth 12 -Compress)"
    }
    $injectedCleanup = Assert-UnregisterFailureRecovery `
        $Operation $ExpectedPid $preProbeFilterCount "$Stage unregister failure"
    if ($null -ne $injectedCleanup) {
        $quiescent = $injectedCleanup
    }
    Write-Trace "$Stage correlated subsequent unowned refresh correlated exactly one production reservation/publication key=$passThroughKey ruleToken=$passThroughRuleToken"
    return $quiescent
}

function Assert-ImmediatePublicationRegression {
    param(
        [string]$Operation,
        [int]$ExpectedPid,
        [string]$Stage
    )
    $expectedFilterCount = @(Get-ActiveServiceProcessFilters $ExpectedPid).Count
    if ($expectedFilterCount -le 0) {
        throw "$Stage could not establish the pre-probe framework filter inventory"
    }
    $ruleToken = [guid]::NewGuid().ToString()
    $armed = Arm-MutationProbe $Operation $ruleToken "$Stage arm"
    $beforeFailures = @($armed.failures).Count
    $hook = Invoke-ObserverCommand 'force_mutation_publication_before_ready'
    if ($hook.mutationRefreshImmediatePublicationBeforeReadyArmed -ne $true) {
        throw "$Stage could not arm the immediate-publication regression gate: $($hook | ConvertTo-Json -Depth 12 -Compress)"
    }
    $broadcastKey = [guid]::NewGuid().ToString()
    $broadcastRuleToken = [guid]::NewGuid().ToString()
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $completed = Assert-PassThroughRefresh `
        $broadcastKey $broadcastRuleToken '' 'ARMED' $beforeFailures "$Stage keyed unowned publication"
    $timer.Stop()
    if ($timer.Elapsed.TotalSeconds -ge 5) {
        throw "$Stage did not complete promptly before the boundary-ready timeout: elapsed=$($timer.Elapsed.TotalSeconds)s"
    }
    $match = @(Get-PassThroughObservation $completed $broadcastKey $broadcastRuleToken)
    $revisionBefore = [long]$match[0].revisionBefore
    $runtimeRevision = [long]$match[0].runtimeRevision
    if ($revisionBefore -lt 0 -or $runtimeRevision -ne ($revisionBefore + 1)) {
        throw "$Stage did not preserve revisionBefore+1: $($completed | ConvertTo-Json -Depth 12 -Compress)"
    }
    $waitEvents = @($completed.events | Where-Object {
        $_.name -eq 'mutation_refresh_boundary_placeholder_wait' -and
        $_.detail -match [regex]::Escape("revision=$runtimeRevision") -and
        $_.detail -match [regex]::Escape("revisionBefore=$revisionBefore")
    })
    if ($waitEvents.Count -ne 1) {
        throw "$Stage did not observe publication waiting on the exact PENDING placeholder: $($completed | ConvertTo-Json -Depth 12 -Compress)"
    }
    $boundaryFailures = @($completed.failures | Where-Object {
        $_.stage -match '^mutation_refresh_boundary_'
    })
    if ($boundaryFailures.Count -ne 0 -or
        @($completed.failures).Count -ne $beforeFailures) {
        throw "$Stage recorded a capture failure: $($completed | ConvertTo-Json -Depth 12 -Compress)"
    }
    $publicationEvents = @($completed.events | Where-Object {
        $_.name -eq 'mutation_pass_through_publication' -and
        $_.detail -match [regex]::Escape("key=$broadcastKey") -and
        $_.detail -match [regex]::Escape("runtimeRevision=$runtimeRevision")
    })
    if ($publicationEvents.Count -ne 1) {
        throw "$Stage did not record exactly one keyed publication: $($completed | ConvertTo-Json -Depth 12 -Compress)"
    }
    $quiescent = Wait-MutationWorkQuiescent "$Stage keyed unowned"
    if (@($quiescent.failures).Count -ne $beforeFailures) {
        throw "$Stage introduced a failure while draining production work: $($quiescent | ConvertTo-Json -Depth 12 -Compress)"
    }
    $cleaned = Invoke-ObserverCommand 'retry_mutation_receiver_cleanup'
    Assert-MutationCleanupState $cleaned $ExpectedPid $expectedFilterCount "$Stage cleanup"
    if (@($cleaned.failures).Count -ne $beforeFailures) {
        throw "$Stage cleanup changed the failure set: $($cleaned | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Trace "$Stage observed publication before READY exactly once key=$broadcastKey revisionBefore=$revisionBefore runtimeRevision=$runtimeRevision"
    return $cleaned
}

function Get-EffectFingerprint($Snapshot) {
    return @($Snapshot.runtimePublicationCount, $Snapshot.notificationPublicationCount,
        $Snapshot.evaluationCount, $Snapshot.allowedEvaluationCount, $Snapshot.deniedEvaluationCount,
        $Snapshot.warningFrameworkBoundaryCount, @($Snapshot.failures).Count) -join ','
}

function Assert-QuiescentStable($Before, [string]$Stage) {
    $after = Invoke-ObserverCommand 'await_quiescence' 15000
    Assert-NoFailures $after
    Assert-WorkCountsZero $after $Stage
    if ((Get-EffectFingerprint $Before) -ne (Get-EffectFingerprint $after)) {
        throw "$Stage effect counts changed after quiescence"
    }
    return $after
}

function Assert-SingleRefresh([string]$Stage, [int]$ExpectedPid) {
    Invoke-ObserverCommand 'reset_observation' | Out-Null
    $sent = Send-Refresh $Stage
    Wait-Until "$Stage production callback entered" {
        (Invoke-ObserverCommand 'snapshot').runtimePublicationCount -ge 1
    }
    $snapshot = Invoke-ObserverCommand 'await_quiescence' 15000
    Assert-NoFailures $snapshot
    Assert-WorkCountsZero $snapshot $Stage
    $stable = Assert-QuiescentStable $snapshot $Stage
    $delivery = Get-CorrelatedRefreshDelivery $sent.Token $ExpectedPid
    if ($stable.runtimePublicationCount -ne 1) {
        throw "$Stage was not single-delivery: $($stable | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Trace "$Stage correlated delivery token=$($sent.Token) line=$delivery"
    Write-Trace "$Stage snapshot=$($stable | ConvertTo-Json -Depth 12 -Compress)"
}

function Assert-RestoredProductionRefresh {
    param(
        [int]$ExpectedPid,
        [int]$ExpectedFilterCount,
        [string]$Stage
    )
    $before = Invoke-ObserverCommand 'snapshot'
    Assert-MutationCleanupState $before $ExpectedPid $ExpectedFilterCount "$Stage preflight"
    $beforeFailures = @($before.failures).Count
    $sent = Send-Refresh $Stage
    Wait-Until "$Stage exactly one restored production delivery" {
        $snapshot = Invoke-ObserverCommand 'snapshot'
        return $snapshot.mutationRefreshState -eq 'DISARMED' -and
            @($snapshot.failures).Count -eq $beforeFailures
    }
    $completed = Invoke-ObserverCommand 'await_quiescence' 15000
    if (@($completed.failures).Count -ne $beforeFailures) {
        throw "$Stage changed the observer failure set: $($completed | ConvertTo-Json -Depth 12 -Compress)"
    }
    Assert-WorkCountsZero $completed $Stage
    $delivery = Get-CorrelatedRefreshDelivery $sent.Token $ExpectedPid
    Assert-SystemFilterCount $ExpectedPid $ExpectedFilterCount "$Stage framework filter inventory"
    Write-Trace "$Stage restored production completed one correlated delivery token=$($sent.Token) line=$delivery"
    return $completed
}

function Get-ActiveServiceProcessFilters([int]$ProcessId) {
    $dump = Invoke-AdbCommand @('shell', 'dumpsys', 'activity', 'broadcasts') -Quiet
    $active = ($dump -split '(?m)^\s*Historical broadcasts \[', 2)[0]
    $processName = [regex]::Escape("$packageName`:app_blocker_service")
    $matches = [regex]::Matches(
        $active,
        "(?m)^.*BroadcastFilter\{(?<id>[0-9a-f]+).*ReceiverList\{[^\r\n]*\s$ProcessId\s+$processName[/\s].*$"
    )
    return @($matches | ForEach-Object { $_.Groups['id'].Value } | Sort-Object -Unique)
}

function Assert-SystemFilterCount([int]$ProcessId, [int]$Expected, [string]$Stage) {
    $filters = @(Get-ActiveServiceProcessFilters $ProcessId)
    if ($filters.Count -ne $Expected) {
        throw "$Stage system filter count expected=$Expected actual=$($filters.Count) ids=$($filters -join ',')"
    }
    Write-Trace "$Stage system filters pid=$ProcessId count=$($filters.Count) ids=$($filters -join ',')"
}

function Assert-MutationCleanupState {
    param(
        $Snapshot,
        [int]$ExpectedPid,
        [int]$ExpectedFilterCount,
        [string]$Stage
    )
    if ($Snapshot.mutationRefreshReceiverRegistered -ne $false -or
        $Snapshot.mutationRefreshPassThroughReceiverRegistered -ne $false -or
        $Snapshot.mutationRefreshProductionReceiverRegistered -ne $true -or
        $Snapshot.mutationRefreshProductionReceiverDetached -ne $false) {
        throw "$Stage did not restore debug and production receiver registrations: $($Snapshot | ConvertTo-Json -Depth 12 -Compress)"
    }
    Assert-SystemFilterCount $ExpectedPid $ExpectedFilterCount "$Stage framework filter inventory"
}

function Assert-NoActiveProcessFilters([int]$ProcessId, [string]$Stage) {
    Assert-SystemFilterCount $ProcessId 0 $Stage
}

function Ensure-CurboxBoundForRuleCleanup([string]$Stage) {
    if (Test-FrameworkBound) { return }
    Invoke-AdbCommand @('shell', 'am', 'start', '-W', '-n',
        "$packageName/neth.iecal.curbox.ui.activity.FragmentActivity") | Out-Null
    Assert-PackageNotStopped
    $current = @(Get-EnabledServices)
    if (@($current | Where-Object { Test-CurboxComponent $_ }).Count -gt 0) {
        Disable-Curbox "$Stage rebind-disable"
    }
    Enable-Curbox "$Stage rebind-enable"
    Wait-Until "$Stage current framework bind" { Test-FrameworkBound }
    Wait-ReadySnapshot "$Stage current observer setup" | Out-Null
}

function Remove-TemporaryCalculatorRule {
    param([string]$Token, [string]$Stage, [int]$Attempts = 3)
    $lastError = $null
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            Ensure-CurboxBoundForRuleCleanup "$Stage attempt $attempt"
            $before = Invoke-ObserverCommand 'snapshot'
            $beforeFailures = @($before.failures).Count
            $beforeCleanupAcks = [long]$before.ruleCleanupCompletionCount
            $expectedFilterCount = @(Get-ActiveServiceProcessFilters ([int]$before.processPid)).Count
            if ($expectedFilterCount -le 0) {
                throw "$Stage could not establish the pre-probe framework filter inventory"
            }
            $removal = Invoke-ObserverCommand 'remove_calculator_denial_rule' $Token
            if (@($removal.failures).Count -ne $beforeFailures) {
                throw "temporary rule removal command failed: $($removal | ConvertTo-Json -Depth 12 -Compress)"
            }
            Assert-MutationRefresh $removal 'remove' $Token ([int]$before.processPid) `
                ([long]$before.mutationRefreshCompletionCount) $expectedFilterCount "$Stage removal" `
                $beforeFailures | Out-Null
            $completed = Invoke-ObserverCommand 'await_calculator_rule_cleanup' $Token
            if (@($completed.failures).Count -ne $beforeFailures -or
                $completed.ruleCleanupCompletionCount -le $beforeCleanupAcks) {
                throw "temporary rule cleanup completion ACK failed: $($completed | ConvertTo-Json -Depth 12 -Compress)"
            }
            foreach ($name in @(
                'refreshes', 'notifications', 'usageResetCompletions',
                'recheckPlans', 'workerQueued', 'workerInFlight'
            )) {
                if ([int]$completed.workCounts.$name -ne 0) {
                    throw "$Stage removal work $name was not quiescent: $($completed | ConvertTo-Json -Depth 12 -Compress)"
                }
            }
            $scopedAcks = @($completed.events | Where-Object {
                $_.name -eq 'temporary_rule_cleanup_scoped_quiescence' -and
                $_.detail -match [regex]::Escape("token=$Token")
            })
            if ($scopedAcks.Count -lt 1) {
                throw "$Stage scoped quiescence ACK missing: $($completed | ConvertTo-Json -Depth 12 -Compress)"
            }
            $verified = Invoke-ObserverCommand 'verify_calculator_rule_absent' $Token
            if (@($verified.failures).Count -ne $beforeFailures -or
                $verified.temporaryRulePresent -or
                $verified.temporaryRuleEffectivePresent -or
                $verified.temporaryRulePendingPresent -or
                $verified.temporaryRuleEditingPresent) {
                throw "temporary rule absence is uncertain: $($verified | ConvertTo-Json -Depth 12 -Compress)"
            }
            Write-Trace "$Stage verified token=$Token effective=false pending=false editing=false attempt=$attempt"
            return $verified
        } catch {
            $lastError = $_
            Write-Trace "$Stage attempt=$attempt failed: $($_.Exception.Message)"
            if ($attempt -lt $Attempts) { Start-Sleep -Milliseconds 250 }
        }
    }
    throw $lastError
}

function Assert-RealCalculatorDenial {
    param([int]$ExpectedServicePid)
    $token = [guid]::NewGuid().ToString()
    $triggeredAt = Get-Date -Format o
    $preflight = Invoke-ObserverCommand 'preflight_calculator_rule'
    Assert-NoFailures $preflight
    if (-not $preflight.ruleMutationPreflightReady) {
        throw "temporary Calculator rule preflight rejected: $($preflight.ruleMutationPreflightReason)"
    }
    $script:temporaryRuleToken = $token
    $installExpectedFilterCount = @(Get-ActiveServiceProcessFilters $ExpectedServicePid).Count
    if ($installExpectedFilterCount -le 0) {
        throw 'temporary Calculator install could not establish the pre-probe framework filter inventory'
    }
    $install = Invoke-ObserverCommand 'install_calculator_denial_rule' $token
    Assert-NoFailures $install
    if (-not $install.temporaryRulePresent) {
        throw "temporary Calculator denial rule is not effective: $($install | ConvertTo-Json -Depth 12 -Compress)"
    }
    $installQuiescent = Assert-MutationRefresh $install 'install' $token ([int]$install.processPid) `
        ([long]$install.mutationRefreshCompletionCount) $installExpectedFilterCount 'temporary Calculator install normal mutation'
    $malformedQuiescent = Assert-OwnedMalformedMutationThenPassThrough 'install' `
        ([int]$install.processPid) 'temporary Calculator install malformed ownership'
    $immediateQuiescent = Assert-ImmediatePublicationRegression 'install' `
        ([int]$install.processPid) 'temporary Calculator install immediate publication'
    $malformedQuiescent = $immediateQuiescent
    Invoke-AdbCommand @('shell', 'am', 'force-stop', 'com.android.calculator2') | Out-Null
    Invoke-AdbCommand @('shell', 'input', 'keyevent', 'HOME') | Out-Null
    Start-Sleep -Milliseconds 500
    $baselineQuiescent = Invoke-ObserverCommand 'await_quiescence' '15000'
    if (@($baselineQuiescent.failures).Count -ne @($malformedQuiescent.failures).Count) {
        throw "external outcome baseline introduced an unexpected observer failure: $($baselineQuiescent | ConvertTo-Json -Depth 12 -Compress)"
    }
    Assert-WorkCountsZero $baselineQuiescent 'external outcome baseline quiescence'
    $baselineActivities = Get-ActivityState
    if (Test-GuardianActivityPresent $baselineActivities) {
        throw 'GuardianApprovalActivity was present before the external outcome window was armed'
    }
    $reset = Invoke-ObserverCommand 'reset_observation'
    Assert-NoFailures $reset
    Assert-WorkCountsZero $reset 'external outcome reset baseline'
    if ($reset.evaluationCount -ne 0 -or $reset.warningFrameworkBoundaryCount -ne 0 -or
        $reset.externalOutcomeWindowState -ne 'DISARMED') {
        throw "external outcome reset baseline was not clean: $($reset | ConvertTo-Json -Depth 12 -Compress)"
    }
    $armed = Invoke-ObserverCommand 'arm_external_outcome' $token
    Assert-NoFailures $armed
    if ($armed.externalOutcomeWindowState -ne 'ARMED' -or
        $armed.externalOutcomeToken -ne $token) {
        throw "external outcome one-shot window did not arm: $($armed | ConvertTo-Json -Depth 12 -Compress)"
    }
    $resolved = (Invoke-AdbCommand @(
        'shell', 'cmd', 'package', 'resolve-activity', '--brief',
        '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER',
        'com.android.calculator2'
    ) -Quiet).Trim()
    $calculatorComponent = @($resolved -split "`r?`n" | Where-Object {
        $_ -match '^com\.android\.calculator2/'
    })[-1]
    if ([string]::IsNullOrWhiteSpace($calculatorComponent)) {
        throw "Calculator launcher activity did not resolve: $resolved"
    }
    $launch = Invoke-AdbCommand @(
        'shell', 'am', 'start', '-W', '-n', $calculatorComponent,
        '--es', 'ticket19_run_token', $token
    )
    if ($launch -notmatch 'Status: ok') { throw "Calculator launch failed: $launch" }
    $script:latestSnapshot = $null
    try {
        Wait-Until 'real Calculator denial and warning boundary' {
            $script:latestSnapshot = Invoke-ObserverCommand 'snapshot'
            return $script:latestSnapshot.externalOutcomeToken -eq $token -and
                $script:latestSnapshot.externalOutcomeWindowState -eq 'CONSUMED' -and
                $script:latestSnapshot.externalOutcomeRequestAccepted -and
                $script:latestSnapshot.externalOutcomeEvaluationDenied -and
                $script:latestSnapshot.externalOutcomeDeniedPublished -and
                $script:latestSnapshot.externalOutcomeWarningCalled
        } 20
    } catch {
        $diagnostic = Invoke-ObserverCommand 'snapshot'
        $activityDiagnostic = Invoke-AdbCommand @(
            'shell', 'dumpsys', 'activity', 'activities'
        ) -Quiet
        $resumed = [regex]::Match(
            $activityDiagnostic,
            '(?m)^\s*ResumedActivity:.*$'
        ).Value.Trim()
        throw "$($_.Exception.Message); resumed=$resumed; observer=$($diagnostic | ConvertTo-Json -Depth 12 -Compress)"
    }
    $outcome = Invoke-ObserverCommand 'snapshot'
    Assert-NoFailures $outcome
    if ($outcome.allowedEvaluationCount -ne 0 -or
        $outcome.deniedEvaluationCount -lt 1 -or
        $outcome.warningFrameworkBoundaryCount -lt 1) {
        throw "unexpected real denial counts: $($outcome | ConvertTo-Json -Depth 12 -Compress)"
    }
    $sourceIdentity = [long]$outcome.externalOutcomeSourceIdentity
    if ($sourceIdentity -le 0) { throw 'causal source identity was not captured' }
    $requestEvents = @($outcome.events | Where-Object {
        $_.name -eq 'causal_request_accepted' -and
        $_.detail -match [regex]::Escape("token=$token") -and
        $_.detail -match 'kind=REAL_EVENT package=com\.android\.calculator2' -and
        $_.detail -match [regex]::Escape("sourceIdentity=$sourceIdentity")
    })
    $evaluationEvents = @($outcome.events | Where-Object {
        $_.name -eq 'causal_evaluation_completed' -and
        $_.detail -match [regex]::Escape("token=$token") -and
        $_.detail -match 'package=com\.android\.calculator2 allowed=false' -and
        $_.detail -match [regex]::Escape("sourceIdentity=$sourceIdentity")
    })
    $publishedEvents = @($outcome.events | Where-Object {
        $_.name -eq 'causal_denied_outcome_published' -and
        $_.detail -match [regex]::Escape("token=$token") -and
        $_.detail -match [regex]::Escape("sourceIdentity=$sourceIdentity")
    })
    $warningEvents = @($outcome.events | Where-Object {
        $_.name -eq 'causal_warning_framework_call' -and
        $_.detail -match [regex]::Escape("token=$token") -and
        $_.detail -match 'package=com\.android\.calculator2' -and
        $_.detail -match [regex]::Escape("sourceIdentity=$sourceIdentity")
    })
    if ($requestEvents.Count -ne 1 -or $evaluationEvents.Count -ne 1 -or
        $publishedEvents.Count -ne 1 -or $warningEvents.Count -ne 1) {
        throw "causal request/evaluator/outcome/warning chain missing: $($outcome | ConvertTo-Json -Depth 12 -Compress)"
    }
    $activities = Get-ActivityState
    $topResumed = Get-TopResumedActivity $activities
    $expectedGuardian = "$packageName/neth.iecal.curbox.ui.activity.GuardianApprovalActivity"
    if ($topResumed -ne $expectedGuardian) {
        throw "GuardianApprovalActivity was not newly top-resumed after warning: top=$topResumed"
    }
    $mainPidText = (Invoke-AdbCommand @('shell', 'pidof', $packageName) -Quiet).Trim()
    $mainPid = 0
    if (-not [int]::TryParse($mainPidText, [ref]$mainPid) -or $mainPid -le 0) {
        throw "Curbox main-process PID missing after Guardian launch: $mainPidText"
    }
    if ($mainPid -eq $ExpectedServicePid -or $outcome.processPid -ne $ExpectedServicePid) {
        throw "external outcome PID correlation failed main=$mainPid service=$ExpectedServicePid snapshot=$($outcome.processPid)"
    }
    Write-Trace "real external denial boundary token=$token snapshot=$($outcome | ConvertTo-Json -Depth 12 -Compress)"
    Invoke-AdbCommand @('shell', 'input', 'keyevent', 'BACK') | Out-Null
    $cleaned = Remove-TemporaryCalculatorRule $token 'real external denial cleanup'
    $script:temporaryRuleToken = $null
    if ($cleaned.deniedEvaluationCount -ne $outcome.deniedEvaluationCount -or
        $cleaned.warningFrameworkBoundaryCount -ne $outcome.warningFrameworkBoundaryCount) {
        throw 'real denial evaluator/external effect counts changed during cleanup quiescence'
    }
    Write-Trace "real external denial cleanup snapshot pendingLifecycleCallbacks=$($cleaned.workCounts.callbacks) snapshot=$($cleaned | ConvertTo-Json -Depth 12 -Compress)"
    Write-Trace "real external denial token=$token sourceIdentity=$sourceIdentity triggeredAt=$triggeredAt calculator=$calculatorComponent servicePid=$ExpectedServicePid mainPid=$mainPid guardianActivity=top-resumed"
    return [pscustomobject]@{ Token = $token; SourceIdentity = $sourceIdentity; ServicePid = $ExpectedServicePid; MainPid = $mainPid }
}

function Assert-ScopedTeardown([string]$Stage) {
    $script:latestSnapshot = $null
    Wait-Until "$Stage AppRule teardown" {
        $script:latestSnapshot = Invoke-ObserverCommand 'snapshot'
        return $script:latestSnapshot.appRuleDestroyed -and
            $script:latestSnapshot.activeReceiverCount -eq 0 -and
            -not $script:latestSnapshot.serviceScopeActive -and
            -not $script:latestSnapshot.protectionScopeActive
    }
    Assert-NoFailures $script:latestSnapshot
    Write-Trace "$Stage scoped snapshot=$($script:latestSnapshot | ConvertTo-Json -Depth 12 -Compress)"
}

$initialServices = @(Get-EnabledServices)
$initialAccessibilityEnabled = Get-AccessibilityEnabled
$initialWakefulness = Get-Wakefulness
if ($initialWakefulness -notin @('Awake', 'Dozing')) {
    throw "refusing to mutate from unsupported wakefulness baseline: $initialWakefulness"
}
if (@($initialServices | Where-Object {
    $_ -match '(?i)(lock\s*me\s*out|lockmeout|com\.teqtic)'
}).Count -ne 0) {
    throw "refusing to mutate a baseline containing intentionally removed Lock Me Out: $($initialServices -join ':')"
}
if (@($initialServices | Where-Object { $_ -match '(?i)safeincloud' }).Count -eq 0) {
    throw "refusing to mutate because SafeInCloud is absent: $($initialServices -join ':')"
}
$script:ownedServices = @($initialServices)
$script:ownedEnabled = $initialAccessibilityEnabled
$script:temporaryRuleToken = $null
$primaryError = $null
$ruleCleanupError = $null
$accessibilityRestoreError = $null
$wakeRestoreError = $null
$traceCompleted = $false
Write-Trace "start serial=$Serial services=$($initialServices -join ':') accessibility_enabled=$initialAccessibilityEnabled wakefulness=$initialWakefulness"
$runLogCursor = Get-LogcatCursor
Write-Trace "run logcat cursor=$runLogCursor (non-destructive; existing logs preserved)"
try {
    if ($initialWakefulness -ne 'Awake') {
        Invoke-AdbCommand @('shell', 'input', 'keyevent', 'WAKEUP') | Out-Null
        Invoke-AdbCommand @('shell', 'wm', 'dismiss-keyguard') | Out-Null
        Wait-Until 'device awake for framework accessibility events' {
            (Get-Wakefulness) -eq 'Awake'
        }
    }
    Invoke-AdbCommand @('shell', 'am', 'start', '-W', '-n',
        "$packageName/neth.iecal.curbox.ui.activity.FragmentActivity") | Out-Null
    Assert-PackageNotStopped

    Enable-Curbox 'initial enable'
    Wait-Until 'initial framework bind' { Test-FrameworkBound }
    $initialPid = Get-ServicePid
    if ($initialPid -le 0) { throw 'initial service PID missing' }
    $initialReset = Invoke-ObserverCommand 'reset_observation'
    Assert-NoFailures $initialReset
    $initial = Wait-ReadySnapshot 'initial observer setup'
    if ($initial.processPid -ne $initialPid) { throw 'observer PID did not match service PID' }
    $initialSystemFilters = @(Get-ActiveServiceProcessFilters $initialPid)
    if ($initialSystemFilters.Count -lt 15) {
        throw "system exposed fewer than 15 service-process filters: $($initialSystemFilters.Count)"
    }
    Write-Trace "initial system filters pid=$initialPid count=$($initialSystemFilters.Count) ids=$($initialSystemFilters -join ',')"
    Write-Trace "initial snapshot=$($initial | ConvertTo-Json -Depth 12 -Compress)"
    Assert-SingleRefresh 'baseline' $initialPid
    $externalDenial = Assert-RealCalculatorDenial $initialPid
    Disable-Curbox 'external outcome lifecycle teardown'
    Wait-Until 'external outcome framework disable' { -not (Test-FrameworkBound) }
    Assert-ScopedTeardown 'external outcome'
    $externalTeardown = Invoke-ObserverCommand 'snapshot'
    Assert-WorkCountsZero $externalTeardown 'external outcome lifecycle teardown'
    Assert-NoActiveProcessFilters $initialPid 'external outcome disabled'
    Enable-Curbox 'external outcome lifecycle rebind'
    Wait-Until 'external outcome framework rebind' { Test-FrameworkBound }
    $initial = Wait-ReadySnapshot 'post-external-outcome observer setup'
    if ($initial.processPid -ne $initialPid) {
        throw 'external outcome cleanup unexpectedly changed service process'
    }
    $initialSystemFilters = @(Get-ActiveServiceProcessFilters $initialPid)
    Write-Trace "external outcome lifecycle cleanup/rebind snapshot=$($initial | ConvertTo-Json -Depth 12 -Compress)"

    Invoke-ObserverCommand 'reset_observation' | Out-Null
    Invoke-ObserverCommand 'arm_runtime_barrier' 15000 | Out-Null
    $barrierBroadcast = Send-Refresh 'barrier'
    Wait-Until 'runtime barrier entered' {
        (Invoke-ObserverCommand 'snapshot').barrierState -eq 'ENTERED'
    }
    $entered = Invoke-ObserverCommand 'snapshot'
    Write-Trace "barrier entered token=$($barrierBroadcast.Token) snapshot=$($entered | ConvertTo-Json -Depth 12 -Compress)"

    Disable-Curbox 'same-PID disable'
    Wait-Until 'same-PID framework disable' { -not (Test-FrameworkBound) }
    Assert-ScopedTeardown 'same-PID'
    Invoke-ObserverCommand 'release_runtime_barrier' | Out-Null
    $continued = Invoke-ObserverCommand 'await_refresh_continuation' 15000
    Assert-NoFailures $continued
    Assert-WorkCountsZero $continued 'post-barrier continuation'
    if ($continued.barrierState -ne 'RELEASED' -or
        $continued.refreshContinuationCompletionCount -ne 1) {
        throw "post-barrier continuation acknowledgement missing: $($continued | ConvertTo-Json -Depth 12 -Compress)"
    }
    $continuedStable = Assert-QuiescentStable $continued 'post-barrier continuation'
    Get-CorrelatedRefreshDelivery $barrierBroadcast.Token $initialPid | Out-Null
    Assert-NoActiveProcessFilters $initialPid 'same-PID disabled'
    Write-Trace "post-barrier stable snapshot=$($continuedStable | ConvertTo-Json -Depth 12 -Compress)"

    Enable-Curbox 'same-PID enable'
    Wait-Until 'same-PID framework rebind' { Test-FrameworkBound }
    $rebound = Wait-ReadySnapshot 'same-PID new service instance'
    if ($rebound.processPid -ne $initialPid) { throw 'same-PID branch changed process' }
    if ($rebound.serviceGeneration -le $initial.serviceGeneration -or
        $rebound.serviceIdentity -eq $initial.serviceIdentity) {
        throw 'same-PID rebind did not create a new service identity'
    }
    Write-Trace "same-PID rebound snapshot=$($rebound | ConvertTo-Json -Depth 12 -Compress)"
    Assert-SystemFilterCount $initialPid $initialSystemFilters.Count 'same-PID rebind'
    $initialServiceWideIds = @($initial.serviceWideReceiverOwnership | ForEach-Object { $_.identity }) -join ','
    $reboundServiceWideIds = @($rebound.serviceWideReceiverOwnership | ForEach-Object { $_.identity }) -join ','
    if ($initialServiceWideIds -eq $reboundServiceWideIds) {
        throw 'same-PID rebind retained every service-wide receiver identity'
    }
    Assert-SingleRefresh 'same-PID rebind' $initialPid

    $beforeNarrow = Invoke-ObserverCommand 'snapshot'
    Invoke-ObserverCommand 'reapply_app_rule_receivers' | Out-Null
    $afterNarrow = Invoke-ObserverCommand 'snapshot'
    Assert-Ready $afterNarrow
    $beforeIds = @($beforeNarrow.receiverOwnership | ForEach-Object { $_.identity }) -join ','
    $afterIds = @($afterNarrow.receiverOwnership | ForEach-Object { $_.identity }) -join ','
    if ($beforeIds -ne $afterIds) { throw 'narrow receiver reapply changed receiver identity' }
    Assert-SystemFilterCount $initialPid $initialSystemFilters.Count 'narrow AppRule reapply'
    Assert-SingleRefresh 'narrow receiver reapply' $initialPid

    Invoke-ObserverCommand 'reset_observation' | Out-Null
    Invoke-ObserverCommand 'fail_next_runtime_publication' | Out-Null
    $failureBroadcast = Send-Refresh 'deterministic failure'
    Wait-Until 'deterministic failure transport' {
        @((Invoke-ObserverCommand 'snapshot').failures).Count -eq 1
    }
    $transported = Invoke-ObserverCommand 'await_quiescence' 15000
    if ($transported.failures[0].stage -ne 'runtime_publication_injected' -or
        $transported.runtimePublicationCount -ne 1) {
        throw "unexpected transported failure: $($transported | ConvertTo-Json -Depth 12 -Compress)"
    }
    Get-CorrelatedRefreshDelivery $failureBroadcast.Token $initialPid | Out-Null
    Write-Trace "failure transported snapshot=$($transported | ConvertTo-Json -Depth 12 -Compress)"

    Invoke-ObserverCommand 'reset_observation' | Out-Null
    $beforeKill = Invoke-ObserverCommand 'snapshot'
    $oldPid = [int]$beforeKill.processPid
    $oldToken = [string]$beforeKill.processToken
    Invoke-ObserverCommand 'terminate_process' 500 | Out-Null
    Wait-Until 'distinct process PID' {
        $newPid = Get-ServicePid
        return $newPid -gt 0 -and $newPid -ne $oldPid
    } 20
    Assert-PackageNotStopped
    try {
        Wait-Until 'automatic framework rebind after process termination' {
            Test-FrameworkBound
        } 15
    } catch {
        Write-Trace 'automatic rebind absent; using explicit launch then disable/enable'
        Invoke-AdbCommand @('shell', 'am', 'start', '-W', '-n',
            "$packageName/neth.iecal.curbox.ui.activity.FragmentActivity") | Out-Null
        Assert-PackageNotStopped
        Disable-Curbox 'distinct-PID fallback disable'
        Enable-Curbox 'distinct-PID fallback enable'
        Wait-Until 'framework rebind after explicit launch' { Test-FrameworkBound }
    }
    $afterKill = Wait-ReadySnapshot 'distinct-process observer setup'
    if ($afterKill.processPid -eq $oldPid -or $afterKill.processToken -eq $oldToken) {
        throw 'distinct-process branch retained old process identity'
    }
    Write-Trace "distinct PID before=$($beforeKill | ConvertTo-Json -Depth 12 -Compress)"
    Write-Trace "distinct PID after=$($afterKill | ConvertTo-Json -Depth 12 -Compress)"
    Assert-NoActiveProcessFilters $oldPid 'distinct-PID old process'
    Assert-SystemFilterCount $afterKill.processPid $initialSystemFilters.Count 'distinct-PID rebind'
    Assert-SingleRefresh 'distinct-PID rebind' $afterKill.processPid

    Disable-Curbox 'final disable'
    Wait-Until 'final framework disable' { -not (Test-FrameworkBound) }
    Assert-ScopedTeardown 'final'
    Assert-NoActiveProcessFilters $afterKill.processPid 'final disabled'
    $activityServices = Invoke-AdbCommand @('shell', 'dumpsys', 'activity', 'services',
        $packageName) -Quiet
    $activeServiceRecord = [regex]::Match(
        $activityServices,
        "(?m)^\s*\* ServiceRecord\{[^\r\n]*$([regex]::Escape($serviceClass))\}"
    )
    if ($activeServiceRecord.Success) {
        throw "AppBlockerService remained active: $($activeServiceRecord.Value.Trim())"
    }
    $runLog = Invoke-AdbCommand @(
        'shell', 'logcat', '-d', '-v', 'epoch', '-T', $runLogCursor
    ) -Quiet
    $newPid = [int]$afterKill.processPid
    $pidPattern = "(?:$oldPid|$newPid)"
    $leakPattern = "(?m)^\s*\d+\.\d+\s+$pidPattern\s+\d+\s+E ActivityThread:\s+(?:android\.app\.IntentReceiverLeaked:\s+)?Service neth\.iecal\.curbox\.services\.AppBlockerService has leaked IntentReceiver rikka\.shizuku\.ShizukuProvider\`$1@[0-9a-f]+ that was originally registered here\. Are you missing a call to unregisterReceiver\(\)\?\s*`$"
    $shizukuLines = @($runLog -split "`n" | Where-Object {
        $_ -match $leakPattern
    })
    Write-Trace "run-cursor/exact-PID Shizuku IntentReceiverLeaked signature lines count=$($shizukuLines.Count) lines=$($shizukuLines -join ' | ')"
    $traceCompleted = $true
} catch {
    $primaryError = $_
} finally {
    if ($null -ne $script:temporaryRuleToken) {
        try {
            Remove-TemporaryCalculatorRule `
                $script:temporaryRuleToken 'final temporary rule cleanup' | Out-Null
            $script:temporaryRuleToken = $null
        } catch {
            $ruleCleanupError = $_
        }
    }
    try {
        Invoke-ObserverCommand 'release_runtime_barrier' | Out-Null
    } catch {
        Write-Trace "barrier release during cleanup unavailable: $($_.Exception.Message)"
    }
    try {
        Set-OwnedAccessibilityState $initialServices $initialAccessibilityEnabled 'final restoration'
        Assert-AccessibilityState $initialServices $initialAccessibilityEnabled 'final restoration exact verification'
    } catch {
        $accessibilityRestoreError = $_
    }
    try {
        if ($initialWakefulness -eq 'Dozing') {
            Invoke-AdbCommand @('shell', 'input', 'keyevent', 'SLEEP') | Out-Null
        } elseif ((Get-Wakefulness) -ne 'Awake') {
            Invoke-AdbCommand @('shell', 'input', 'keyevent', 'WAKEUP') | Out-Null
            Invoke-AdbCommand @('shell', 'wm', 'dismiss-keyguard') | Out-Null
        }
        Wait-Until "exact $initialWakefulness wakefulness restoration" {
            (Get-Wakefulness) -ceq $initialWakefulness
        }
        Write-Trace "wakefulness restoration verified state=$(Get-Wakefulness)"
    } catch {
        $wakeRestoreError = $_
    }
}

$failures = @(@(
    $primaryError,
    $ruleCleanupError,
    $accessibilityRestoreError,
    $wakeRestoreError
) | Where-Object { $null -ne $_ })
if ($failures.Count -gt 1) {
    throw [AggregateException]::new(
        'ticket19 trace and restoration failures',
        [Exception[]]@($failures | ForEach-Object { $_.Exception })
    )
}
if ($failures.Count -eq 1) { throw $failures[0] }
if (-not $traceCompleted) { throw 'ticket19 trace did not reach its scoped completion point' }
Write-Trace "TRACE_COMPLETE scoped assertions passed; decision DONE/NO-GO; ticket closed; architecture gate closed; restored services=$((Get-EnabledServices) -join ':') accessibility_enabled=$(Get-AccessibilityEnabled)"
