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

function Invoke-ObserverCommand([string]$Method, [long]$Argument = 0) {
    $raw = Invoke-AdbCommand @('shell', 'content', 'call', '--uri', $observerUri,
        '--method', $Method, '--arg', $Argument.ToString()) -Quiet
    $prefix = 'Result: Bundle[{result='
    if (-not $raw.StartsWith($prefix) -or -not $raw.EndsWith('}]')) {
        throw "unexpected observer response: $raw"
    }
    $json = $raw.Substring($prefix.Length, $raw.Length - $prefix.Length - 2)
    return $json | ConvertFrom-Json
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

function Assert-WorkCountsZero($Snapshot, [string]$Stage) {
    foreach ($property in $Snapshot.workCounts.PSObject.Properties) {
        if ([int]$property.Value -ne 0) { throw "$Stage work count $($property.Name)=$($property.Value)" }
    }
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

function Assert-NoActiveProcessFilters([int]$ProcessId, [string]$Stage) {
    Assert-SystemFilterCount $ProcessId 0 $Stage
}

function Try-RealAllowedEvaluation {
    Invoke-ObserverCommand 'reset_observation' | Out-Null
    try {
        Invoke-AdbCommand @('shell', 'input', 'keyevent', 'HOME') | Out-Null
        Invoke-AdbCommand @('shell', 'monkey', '-p', 'com.android.calculator2', '1') | Out-Null
        $script:latestSnapshot = $null
        Wait-Until 'real Calculator accessibility evaluation' {
            $script:latestSnapshot = Invoke-ObserverCommand 'snapshot'
            return $script:latestSnapshot.lastEvaluatedPackage -eq 'com.android.calculator2' -and
                $script:latestSnapshot.evaluationCount -ge 1 -and
                $script:latestSnapshot.allowedEvaluationCount -ge 1
        } 20
    } catch {
        $snapshot = Invoke-ObserverCommand 'snapshot'
        $window = Invoke-AdbCommand @('shell', 'dumpsys', 'window', 'windows') -Quiet
        $focus = [regex]::Match($window, '(?m)^\s*mCurrentFocus=.*$').Value.Trim()
        Write-Trace "real external allow INCONCLUSIVE blocker=$($_.Exception.Message) focus=$focus snapshot=$($snapshot | ConvertTo-Json -Depth 12 -Compress)"
        return $false
    }
    $quiescent = Invoke-ObserverCommand 'await_quiescence' 15000
    Assert-NoFailures $quiescent
    Assert-WorkCountsZero $quiescent 'real allowed evaluation'
    $window = Invoke-AdbCommand @('shell', 'dumpsys', 'window', 'windows') -Quiet
    if ($window -notmatch 'mCurrentFocus=.*com\.android\.calculator2') {
        throw 'allowed evaluator outcome was not paired with an externally focused Calculator window'
    }
    $stable = Assert-QuiescentStable $quiescent 'real allowed evaluation'
    Write-Trace "real external allow focused=com.android.calculator2 snapshot=$($stable | ConvertTo-Json -Depth 12 -Compress)"
    return $true
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
$primaryError = $null
$restoreError = $null
$traceCompleted = $false
Write-Trace "start serial=$Serial services=$($initialServices -join ':') accessibility_enabled=$initialAccessibilityEnabled"
try {
    Invoke-AdbCommand @('shell', 'logcat', '-c') | Out-Null
    Invoke-AdbCommand @('shell', 'am', 'start', '-W', '-n',
        "$packageName/neth.iecal.curbox.ui.activity.FragmentActivity") | Out-Null
    Assert-PackageNotStopped

    Enable-Curbox 'initial enable'
    Wait-Until 'initial framework bind' { Test-FrameworkBound }
    $initialPid = Get-ServicePid
    if ($initialPid -le 0) { throw 'initial service PID missing' }
    $initial = Wait-ReadySnapshot 'initial observer setup'
    if ($initial.processPid -ne $initialPid) { throw 'observer PID did not match service PID' }
    $initialSystemFilters = @(Get-ActiveServiceProcessFilters $initialPid)
    if ($initialSystemFilters.Count -lt 15) {
        throw "system exposed fewer than 15 service-process filters: $($initialSystemFilters.Count)"
    }
    Write-Trace "initial system filters pid=$initialPid count=$($initialSystemFilters.Count) ids=$($initialSystemFilters -join ',')"
    Write-Trace "initial snapshot=$($initial | ConvertTo-Json -Depth 12 -Compress)"
    Assert-SingleRefresh 'baseline' $initialPid
    $externalAllowObserved = Try-RealAllowedEvaluation

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
    $runLog = Invoke-AdbCommand @('shell', 'logcat', '-d', '-v', 'epoch') -Quiet
    $newPid = [int]$afterKill.processPid
    $shizukuLines = @($runLog -split "`n" | Where-Object {
        $_ -match '(?i)(Shizuku|IntentReceiverLeaked)' -and $_ -match "\s($oldPid|$newPid)\s"
    })
    Write-Trace "run-specific Shizuku/IntentReceiver lines count=$($shizukuLines.Count) lines=$($shizukuLines -join ' | ')"
    $traceCompleted = $true
} catch {
    $primaryError = $_
} finally {
    try {
        Invoke-ObserverCommand 'release_runtime_barrier' | Out-Null
    } catch {
        Write-Trace "barrier release during cleanup unavailable: $($_.Exception.Message)"
    }
    try {
        Set-OwnedAccessibilityState $initialServices $initialAccessibilityEnabled 'final restoration'
        Assert-AccessibilityState $initialServices $initialAccessibilityEnabled 'final restoration exact verification'
    } catch {
        $restoreError = $_
    }
}

if ($null -ne $primaryError -and $null -ne $restoreError) {
    throw [AggregateException]::new(
        'ticket19 trace and accessibility restoration both failed',
        [Exception[]]@($primaryError.Exception, $restoreError.Exception)
    )
}
if ($null -ne $primaryError) { throw $primaryError }
if ($null -ne $restoreError) { throw $restoreError }
if (-not $traceCompleted) { throw 'ticket19 trace did not reach its scoped completion point' }
Write-Trace "TRACE_COMPLETE scoped assertions passed; decision remains INCONCLUSIVE, ticket open, gate closed; restored services=$((Get-EnabledServices) -join ':') accessibility_enabled=$(Get-AccessibilityEnabled)"
