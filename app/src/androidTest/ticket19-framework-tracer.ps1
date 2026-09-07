param(
    [string]$Serial = 'T811MA256GB23418064398',
    [string]$AdbPath = 'C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe'
)

$ErrorActionPreference = 'Stop'
$packageName = 'neth.iecal.curbox.debug'
$serviceClass = 'neth.iecal.curbox.services.AppBlockerService'
$serviceComponent = "$packageName/$serviceClass"
$observerUri = 'content://neth.iecal.curbox.debug.ticket19.observer'

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

function Set-AccessibilityState([string[]]$Services, [int]$Enabled) {
    Invoke-AdbCommand @('shell', 'settings', 'put', 'secure',
        'accessibility_enabled', '0') | Out-Null
    if ($Services.Count -eq 0) {
        Invoke-AdbCommand @('shell', 'settings', 'delete', 'secure',
            'enabled_accessibility_services') | Out-Null
    } else {
        Invoke-AdbCommand @('shell', 'settings', 'put', 'secure',
            'enabled_accessibility_services', ($Services -join ':')) | Out-Null
    }
    Invoke-AdbCommand @('shell', 'settings', 'put', 'secure',
        'accessibility_enabled', $Enabled.ToString()) | Out-Null
}

function Enable-Curbox {
    $before = @(Get-EnabledServices)
    $after = @($before | Where-Object { -not (Test-CurboxComponent $_) }) +
        @($serviceComponent)
    Write-Trace "enable before=$($before -join ':') after=$($after -join ':')"
    Set-AccessibilityState $after 1
}

function Disable-Curbox {
    $before = @(Get-EnabledServices)
    $after = @($before | Where-Object { -not (Test-CurboxComponent $_) })
    Write-Trace "disable before=$($before -join ':') after=$($after -join ':')"
    Set-AccessibilityState $after $(if ($after.Count -eq 0) { 0 } else { 1 })
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
    Assert-NoFailures $Snapshot
}

function Wait-ReadySnapshot([string]$Description) {
    $script:latestSnapshot = $null
    Wait-Until $Description {
        $script:latestSnapshot = Invoke-ObserverCommand 'snapshot'
        return $script:latestSnapshot.appRuleSetupReady -and
            $script:latestSnapshot.activeReceiverCount -eq 5
    }
    Assert-Ready $script:latestSnapshot
    return $script:latestSnapshot
}

function Send-Refresh {
    $output = Invoke-AdbCommand @('shell', 'am', 'broadcast', '-a',
        'neth.iecal.curbox.refresh.app_rules', '-p', $packageName)
    if ($output -notmatch 'result=0') { throw "matching broadcast failed: $output" }
}

function Get-LatestRefreshDelivery {
    $dump = Invoke-AdbCommand @('shell', 'dumpsys', 'activity', 'broadcasts') -Quiet
    $pattern = '(?ms)Historical Broadcast background #\d+:\s+BroadcastRecord\{[^\r\n]*neth\.iecal\.curbox\.refresh\.app_rules\}.*?(?=\r?\n\s*Historical Broadcast background #|\r?\n\s*Historical broadcasts summary)'
    $record = [regex]::Match($dump, $pattern)
    if (-not $record.Success) { throw 'matching broadcast history record not found' }
    $deliveries = [regex]::Matches($record.Value, '(?m)^\s*Deliver .*#\d+:.*$')
    return [pscustomobject]@{
        Count = $deliveries.Count
        Lines = @($deliveries | ForEach-Object { $_.Value.Trim() })
        Record = $record.Value.Trim()
    }
}

function Assert-SingleRefresh([string]$Stage) {
    Invoke-ObserverCommand 'reset_observation' | Out-Null
    Send-Refresh
    $script:latestSnapshot = $null
    Wait-Until "$Stage runtime publication" {
        $script:latestSnapshot = Invoke-ObserverCommand 'snapshot'
        return $script:latestSnapshot.runtimePublicationCount -ge 1
    }
    Start-Sleep -Milliseconds 300
    $stable = Invoke-ObserverCommand 'snapshot'
    Assert-NoFailures $stable
    $delivery = Get-LatestRefreshDelivery
    if ($delivery.Count -ne 1 -or
        $delivery.Lines[0] -notmatch [regex]::Escape("$packageName`:app_blocker_service") -or
        $stable.runtimePublicationCount -ne 1) {
        throw "$Stage was not single-delivery: $($stable | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Trace "$Stage delivery=$($delivery.Lines -join ' | ')"
    Write-Trace "$Stage snapshot=$($stable | ConvertTo-Json -Depth 12 -Compress)"
}

function Assert-Teardown([string]$Stage) {
    $script:latestSnapshot = $null
    Wait-Until "$Stage teardown" {
        $script:latestSnapshot = Invoke-ObserverCommand 'snapshot'
        return $script:latestSnapshot.appRuleDestroyed -and
            $script:latestSnapshot.activeReceiverCount -eq 0 -and
            -not $script:latestSnapshot.serviceScopeActive -and
            -not $script:latestSnapshot.protectionScopeActive
    }
    Assert-NoFailures $script:latestSnapshot
    Write-Trace "$Stage snapshot=$($script:latestSnapshot | ConvertTo-Json -Depth 12 -Compress)"
}

$initialServices = @(Get-EnabledServices)
$initialAccessibilityEnabled = Get-AccessibilityEnabled
Write-Trace "start serial=$Serial services=$($initialServices -join ':') accessibility_enabled=$initialAccessibilityEnabled"
try {
    Invoke-AdbCommand @('shell', 'am', 'start', '-W', '-n',
        "$packageName/neth.iecal.curbox.ui.activity.FragmentActivity") | Out-Null
    Assert-PackageNotStopped

    Enable-Curbox
    Wait-Until 'initial framework bind' { Test-FrameworkBound }
    $initialPid = Get-ServicePid
    if ($initialPid -le 0) { throw 'initial service PID missing' }
    $initial = Wait-ReadySnapshot 'initial observer setup'
    if ($initial.processPid -ne $initialPid) { throw 'observer PID did not match service PID' }
    Write-Trace "initial snapshot=$($initial | ConvertTo-Json -Depth 12 -Compress)"
    Assert-SingleRefresh 'baseline'

    Invoke-ObserverCommand 'reset_observation' | Out-Null
    Invoke-ObserverCommand 'arm_runtime_barrier' 15000 | Out-Null
    Send-Refresh
    Wait-Until 'runtime barrier entered' {
        (Invoke-ObserverCommand 'snapshot').barrierState -eq 'ENTERED'
    }
    $entered = Invoke-ObserverCommand 'snapshot'
    Write-Trace "barrier entered snapshot=$($entered | ConvertTo-Json -Depth 12 -Compress)"

    Disable-Curbox
    Wait-Until 'same-PID framework disable' { -not (Test-FrameworkBound) }
    Assert-Teardown 'same-PID'
    Invoke-ObserverCommand 'release_runtime_barrier' | Out-Null
    $released = Invoke-ObserverCommand 'snapshot'
    Assert-NoFailures $released
    if ($released.barrierState -ne 'RELEASED') { throw 'barrier was not released' }

    Enable-Curbox
    Wait-Until 'same-PID framework rebind' { Test-FrameworkBound }
    $rebound = Wait-ReadySnapshot 'same-PID new service instance'
    if ($rebound.processPid -ne $initialPid) { throw 'same-PID branch changed process' }
    if ($rebound.serviceGeneration -le $initial.serviceGeneration -or
        $rebound.serviceIdentity -eq $initial.serviceIdentity) {
        throw 'same-PID rebind did not create a new service identity'
    }
    Write-Trace "same-PID rebound snapshot=$($rebound | ConvertTo-Json -Depth 12 -Compress)"
    Assert-SingleRefresh 'same-PID rebind'

    $beforeNarrow = Invoke-ObserverCommand 'snapshot'
    Invoke-ObserverCommand 'reapply_app_rule_receivers' | Out-Null
    $afterNarrow = Invoke-ObserverCommand 'snapshot'
    Assert-Ready $afterNarrow
    $beforeIds = @($beforeNarrow.receiverOwnership | ForEach-Object { $_.identity }) -join ','
    $afterIds = @($afterNarrow.receiverOwnership | ForEach-Object { $_.identity }) -join ','
    if ($beforeIds -ne $afterIds) { throw 'narrow receiver reapply changed receiver identity' }
    Assert-SingleRefresh 'narrow receiver reapply'

    Invoke-ObserverCommand 'reset_observation' | Out-Null
    Invoke-ObserverCommand 'fail_next_runtime_publication' | Out-Null
    Send-Refresh
    Wait-Until 'deterministic failure transport' {
        @((Invoke-ObserverCommand 'snapshot').failures).Count -eq 1
    }
    $transported = Invoke-ObserverCommand 'snapshot'
    $failureDelivery = Get-LatestRefreshDelivery
    if ($transported.failures[0].stage -ne 'runtime_publication_injected' -or
        $transported.runtimePublicationCount -ne 1 -or $failureDelivery.Count -ne 1) {
        throw "unexpected transported failure: $($transported | ConvertTo-Json -Depth 12 -Compress)"
    }
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
        Disable-Curbox
        Enable-Curbox
        Wait-Until 'framework rebind after explicit launch' { Test-FrameworkBound }
    }
    $afterKill = Wait-ReadySnapshot 'distinct-process observer setup'
    if ($afterKill.processPid -eq $oldPid -or $afterKill.processToken -eq $oldToken) {
        throw 'distinct-process branch retained old process identity'
    }
    Write-Trace "distinct PID before=$($beforeKill | ConvertTo-Json -Depth 12 -Compress)"
    Write-Trace "distinct PID after=$($afterKill | ConvertTo-Json -Depth 12 -Compress)"
    Assert-SingleRefresh 'distinct-PID rebind'

    Disable-Curbox
    Wait-Until 'final framework disable' { -not (Test-FrameworkBound) }
    Assert-Teardown 'final'
    $activityServices = Invoke-AdbCommand @('shell', 'dumpsys', 'activity', 'services',
        $packageName) -Quiet
    $activeServiceRecord = [regex]::Match(
        $activityServices,
        "(?m)^\s*\* ServiceRecord\{[^\r\n]*$([regex]::Escape($serviceClass))\}"
    )
    if ($activeServiceRecord.Success) {
        throw "AppBlockerService remained active: $($activeServiceRecord.Value.Trim())"
    }
    Write-Trace 'PASS canonical lifecycle trace completed without duplicate callback/effect'
} finally {
    try { Invoke-ObserverCommand 'release_runtime_barrier' | Out-Null } catch {}
    try { Set-AccessibilityState $initialServices $initialAccessibilityEnabled } catch {}
    Write-Trace "finish services=$((Get-EnabledServices) -join ':') accessibility_enabled=$(Get-AccessibilityEnabled)"
}
