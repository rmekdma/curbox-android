. "$PSScriptRoot/../run-instrumentation.ps1"
Describe 'Instrumentation entrypoint' {
    BeforeEach {
        Mock Assert-AdbDevice {}
        Mock Assert-LearningFakeApk {}
        Mock Assert-InstrumentationApk {}
        Mock Get-TestDeviceShellOutput {
            param($Command)
            if ($Command -eq 'getprop ro.serialno') { 'serial' }
            elseif ($Command -like 'pm path*') { 'package:/data/app/test/base.apk' }
            elseif ($Command -like 'sha256sum*') { ('b' * 64) + '  /data/app/test/base.apk' }
            else { 'OK (128 tests)' }
        }
        $script:params = @{ DeviceId='serial'; LearningApkPath='fake.apk'; ExpectedApkSha256=('a' * 64); BuildToolsDirectory='tools'; InstrumentationApkPath='test.apk'; InstrumentationApkSha256=('b' * 64) }
    }
    It 'excludes only the measurement from functional runs' {
        Invoke-CurboxInstrumentation -Mode Functional @script:params
        Assert-MockCalled Get-TestDeviceShellOutput -ParameterFilter { $Command -match '-e notAnnotation neth.iecal.curbox.blockers.PerformanceMeasurement' } -Times 1
    }
    It 'rejects missing measurement identity before accessing a device' {
        $failed = $false
        try { Invoke-CurboxInstrumentation -Mode Performance @script:params } catch { $failed = $true }
        $failed | Should Be $true
        Assert-MockCalled Assert-AdbDevice -Times 0 -Scope It
    }
    It 'passes all six measurement identities to the exact method' {
        Invoke-CurboxInstrumentation -Mode Performance @script:params -PseudonymousDeviceId device1 -SourceCommit abcdef1 -HarnessCommit abcdef2
        Assert-MockCalled Get-TestDeviceShellOutput -Times 1 -ParameterFilter {
            $Command -like '*class neth.iecal.curbox.blockers.AppRuleBlockerFixedFixtureP95MeasurementTest#measureFixedSyntheticFixtureP95*' -and
            $Command -like '*t27RawDeviceSerial serial*' -and $Command -like '*t27PseudonymousDeviceId device1*' -and
            $Command -like '*t27SourceCommit abcdef1*' -and $Command -like '*t27HarnessCommit abcdef2*' -and
            $Command -like "*t27TargetApkSha256 $('a' * 64)*" -and $Command -like "*t27InstrumentationApkSha256 $('b' * 64)*"
        }
    }
    It 'fails when runner reports failure' {
        Mock Get-TestDeviceShellOutput {
            param($Command)
            if ($Command -eq 'getprop ro.serialno') { 'serial' }
            elseif ($Command -like 'pm path*') { 'package:/data/app/test/base.apk' }
            elseif ($Command -like 'sha256sum*') { ('b' * 64) + '  /data/app/test/base.apk' }
            else { 'FAILURES!!!' }
        }
        $failed = $false
        try { Invoke-CurboxInstrumentation -Mode Functional @script:params } catch { $failed = $true }
        $failed | Should Be $true
    }
}
