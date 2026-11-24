Describe 'Guardian diagnostic log projection' {
    BeforeEach { . "$PSScriptRoot/../lib/guardian-diagnostics.ps1" }
    It 'keeps correlated test events while discarding unrelated data' {
        $log = @'
11-25 12:00:00.123  123  234 I GuardianApprovalE2E: write_committed id=abc-home-replacement-reconnect secret=1234
11-25 12:00:01.123  123  234 I GuardianApprovalE2E: check_request test=abc-home-replacement-reconnect operation=op1 check=check2 screen=screen3 connection=conn4 rule=private
11-25 12:00:02.123  123  234 I GuardianApprovalE2E: write_committed id=other secret=4321
'@
        $result = @(ConvertTo-GuardianDiagnosticEvents -Log $log -GateId 'abc-home-replacement-reconnect')
        $result.Count | Should Be 2
        $result[0] | Should Match 'write_committed.*id=abc-home-replacement-reconnect'
        $result[1] | Should Match 'operation=op1.*check=check2.*screen=screen3.*connection=conn4'
        ($result -join ' ') | Should Not Match 'secret|private|other'
    }
}
