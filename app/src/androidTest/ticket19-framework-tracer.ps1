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

function Assert-RealCalculatorDenial {
    param([int]$ExpectedServicePid)
    $token = [guid]::NewGuid().ToString()
    $triggeredAt = Get-Date -Format o
    $beforeInstall = Invoke-ObserverCommand 'snapshot'
    $install = Invoke-ObserverCommand 'install_calculator_denial_rule' $token
    Assert-NoFailures $install
    if (-not $install.temporaryRulePresent) {
        throw "temporary Calculator denial rule is not effective: $($install | ConvertTo-Json -Depth 12 -Compress)"
    }
    $script:temporaryRuleToken = $token
    Wait-Until 'temporary Calculator rule refresh publication' {
        (Invoke-ObserverCommand 'snapshot').runtimePublicationCount -gt
            $beforeInstall.runtimePublicationCount
    }
    Invoke-ObserverCommand 'await_quiescence' '15000' | Out-Null
    Invoke-ObserverCommand 'reset_observation' | Out-Null
    Invoke-ObserverCommand 'arm_external_outcome' $token | Out-Null
    Invoke-AdbCommand @('shell', 'input', 'keyevent', 'HOME') | Out-Null
    Start-Sleep -Milliseconds 1500
    Invoke-AdbCommand @('shell', 'am', 'force-stop', 'com.android.calculator2') | Out-Null
    Start-Sleep -Milliseconds 1500
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
                $script:latestSnapshot.lastEvaluatedPackage -eq 'com.android.calculator2' -and
                $script:latestSnapshot.deniedEvaluationCount -ge 1 -and
                $script:latestSnapshot.warningFrameworkBoundaryCount -ge 1
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
    $evaluationEvents = @($outcome.events | Where-Object {
        $_.name -eq 'evaluation_completed' -and $_.detail -match [regex]::Escape("token=$token") -and
        $_.detail -match 'package=com\.android\.calculator2 allowed=False'
    })
    $warningEvents = @($outcome.events | Where-Object {
        $_.name -eq 'warning_framework_boundary' -and $_.detail -match [regex]::Escape("token=$token") -and
        $_.detail -match 'package=com\.android\.calculator2'
    })
    if ($evaluationEvents.Count -lt 1 -or $warningEvents.Count -lt 1) {
        throw "UUID-correlated evaluator/warning events missing: $($outcome | ConvertTo-Json -Depth 12 -Compress)"
    }
    $activities = Invoke-AdbCommand @('shell', 'dumpsys', 'activity', 'activities') -Quiet
    if ($activities -notmatch 'neth\.iecal\.curbox\.debug/neth\.iecal\.curbox\.ui\.activity\.GuardianApprovalActivity') {
        throw 'GuardianApprovalActivity was not observable in the Android activity stack'
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
    $beforeRemoval = Invoke-ObserverCommand 'snapshot'
    $removal = Invoke-ObserverCommand 'remove_calculator_denial_rule' $token
    Assert-NoFailures $removal
    if ($removal.temporaryRulePresent) {
        throw "temporary Calculator rule removal was deferred and cannot quiesce this run: $($removal | ConvertTo-Json -Depth 12 -Compress)"
    }
    $script:temporaryRuleToken = $null
    Wait-Until 'temporary Calculator rule removal publication' {
        (Invoke-ObserverCommand 'snapshot').runtimePublicationCount -gt
            $beforeRemoval.runtimePublicationCount
    }
    $cleaned = Invoke-ObserverCommand 'snapshot'
    Assert-NoFailures $cleaned
    if ($cleaned.deniedEvaluationCount -ne $outcome.deniedEvaluationCount -or
        $cleaned.warningFrameworkBoundaryCount -ne $outcome.warningFrameworkBoundaryCount) {
        throw 'real denial evaluator/external effect counts changed during cleanup quiescence'
    }
    Write-Trace "real external denial cleanup snapshot pendingLifecycleCallbacks=$($cleaned.workCounts.callbacks) snapshot=$($cleaned | ConvertTo-Json -Depth 12 -Compress)"
    Write-Trace "real external denial token=$token triggeredAt=$triggeredAt calculator=$calculatorComponent servicePid=$ExpectedServicePid mainPid=$mainPid guardianActivity=present"
    return [pscustomobject]@{ Token = $token; ServicePid = $ExpectedServicePid; MainPid = $mainPid }
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
$restoreError = $null
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
            $cleanup = Invoke-ObserverCommand `
                'remove_calculator_denial_rule' $script:temporaryRuleToken
            Assert-NoFailures $cleanup
            if ($cleanup.temporaryRulePresent) {
                throw 'temporary Calculator rule remained effective after cleanup request'
            }
            Write-Trace "temporary rule cleanup verified token=$script:temporaryRuleToken"
            $script:temporaryRuleToken = $null
        } catch {
            Write-Trace "temporary rule cleanup unavailable: $($_.Exception.Message)"
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
        $restoreError = $_
    }
    if ($initialWakefulness -ne 'Awake') {
        try {
            Invoke-AdbCommand @('shell', 'input', 'keyevent', 'SLEEP') | Out-Null
            Wait-Until 'original non-awake device state' {
                (Get-Wakefulness) -ne 'Awake'
            }
            Write-Trace "wakefulness restoration verified state=$(Get-Wakefulness)"
        } catch {
            if ($null -eq $restoreError) {
                $restoreError = $_
            } else {
                $restoreError = [System.Management.Automation.ErrorRecord]::new(
                    [AggregateException]::new(
                        'accessibility and wakefulness restoration both failed',
                        [Exception[]]@($restoreError.Exception, $_.Exception)
                    ),
                    'Ticket19RestorationFailed',
                    [System.Management.Automation.ErrorCategory]::InvalidResult,
                    $null
                )
            }
        }
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
