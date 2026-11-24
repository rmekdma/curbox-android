function ConvertTo-GuardianDiagnosticEvents {
    param([string]$Log, [string]$GateId)
    $safeFields = @('id', 'test', 'operation', 'check', 'screen', 'connection', 'status', 'action', 'accepted', 'retry', 'recovery', 'reason', 'pending_check', 'checking', 'previous', 'current', 'instance', 'restored_state', 'finishing')
    foreach ($line in ($Log -split "`n")) {
        if ($line -notmatch [regex]::Escape($GateId)) { continue }
        if ($line -notmatch '^(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)\s+\d+\s+\d+\s+\w\s+GuardianApprovalE2E:\s+(\w+)') { continue }
        $parts = @($matches[1], $matches[2])
        foreach ($field in $safeFields) {
            $match = [regex]::Match($line, "(?:^|\s)$field=([A-Za-z0-9_.:-]+)")
            if ($match.Success) { $parts += "$field=$($match.Groups[1].Value)" }
        }
        $parts -join ' '
    }
}
