param(
    [ValidateSet('Functional', 'Performance')][string]$Mode = 'Functional',
    [string]$DeviceId = '', [string]$LearningApkPath = '', [string]$ExpectedApkSha256 = '',
    [string]$BuildToolsDirectory = '', [string]$PseudonymousDeviceId = '',
    [string]$SourceCommit = '', [string]$HarnessCommit = '',
    [string]$InstrumentationApkPath = '', [string]$InstrumentationApkSha256 = ''
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/lib/device-test-common.ps1"

function Assert-InstrumentationApk([string]$ApkPath, [string]$ExpectedHash, [string]$BuildToolsDirectory) {
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf) -or (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash -ne $ExpectedHash) { throw 'Instrumentation APK hash mismatch.' }
    $signer = Join-Path $BuildToolsDirectory 'apksigner.bat'
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = (& $signer verify --print-certs $ApkPath 2>$null | Out-String)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($exitCode -ne 0 -or $output -notmatch 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})' -or $Matches[1] -ne '2429a67995d6e0a03312ff8f67d7df53c40dbdeb86e454d62f87d8e8243e4c6a') { throw 'Instrumentation APK signer mismatch.' }
}

function Invoke-CurboxInstrumentation {
    param(
        [string]$Mode, [string]$DeviceId, [string]$LearningApkPath, [string]$ExpectedApkSha256,
        [string]$BuildToolsDirectory, [string]$PseudonymousDeviceId, [string]$SourceCommit,
        [string]$HarnessCommit, [string]$InstrumentationApkPath, [string]$InstrumentationApkSha256
    )
    if ($Mode -notin @('Functional', 'Performance')) { throw 'Invalid mode.' }
    foreach ($value in @($DeviceId, $LearningApkPath, $ExpectedApkSha256, $BuildToolsDirectory, $InstrumentationApkPath, $InstrumentationApkSha256)) {
        if ([string]::IsNullOrWhiteSpace($value)) { throw 'Device and fake debug APK identity are required.' }
    }
    if ($DeviceId -notmatch '^[A-Za-z0-9._:-]+$' -or $ExpectedApkSha256 -notmatch '^[a-fA-F0-9]{64}$' -or $InstrumentationApkSha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid device or APK identity.' }
    if ($Mode -eq 'Performance') {
        foreach ($value in @($PseudonymousDeviceId, $SourceCommit, $HarnessCommit, $InstrumentationApkSha256, $InstrumentationApkPath)) {
            if ([string]::IsNullOrWhiteSpace($value)) { throw 'All measurement identity arguments are required.' }
        }
        if ($PseudonymousDeviceId -notmatch '^[A-Za-z0-9._:-]+$' -or $SourceCommit -notmatch '^[a-fA-F0-9]{7,64}$' -or $HarnessCommit -notmatch '^[a-fA-F0-9]{7,64}$' -or $InstrumentationApkSha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid measurement identity.' }
    }
    $previousSerial = $env:ANDROID_SERIAL
    try {
        $env:ANDROID_SERIAL = $DeviceId
        Assert-AdbDevice
        $serial = (Get-TestDeviceShellOutput -Command 'getprop ro.serialno').Trim()
        if ($serial -cne $DeviceId) { throw 'Selected device serial does not match.' }
        $enabledServices = Get-DeviceSecureSetting -Name 'enabled_accessibility_services'
        if ([string]::IsNullOrWhiteSpace($enabledServices)) { throw 'Cannot read enabled accessibility services.' }
        if ($enabledServices -match '(?i)neth\.iecal\.curbox\.debug/') { throw 'Disable the Curbox accessibility service before instrumentation.' }
        Assert-LearningFakeApk $LearningApkPath $ExpectedApkSha256 $BuildToolsDirectory
        Assert-InstrumentationApk $InstrumentationApkPath $InstrumentationApkSha256 $BuildToolsDirectory
        $installed = Get-TestDeviceShellOutput -Command 'pm path neth.iecal.curbox.debug.test'
        if ($installed -notmatch '^package:(/data/app/[A-Za-z0-9_./~=-]+/base\.apk)$') { throw 'Cannot identify installed instrumentation APK.' }
        $installedHash = Get-TestDeviceShellOutput -Command "sha256sum $($Matches[1])"
        if ($installedHash -notmatch '^([a-fA-F0-9]{64})\s' -or $Matches[1] -ne $InstrumentationApkSha256) { throw 'Installed instrumentation APK hash mismatch.' }
        $runner = 'neth.iecal.curbox.debug.test/androidx.test.runner.AndroidJUnitRunner'
        if ($Mode -eq 'Functional') {
            $selector = '-e notAnnotation neth.iecal.curbox.blockers.PerformanceMeasurement'
        } else {
            $selector = "-e class neth.iecal.curbox.blockers.AppRuleBlockerFixedFixtureP95MeasurementTest#measureFixedSyntheticFixtureP95 -e t27RawDeviceSerial $DeviceId -e t27PseudonymousDeviceId $PseudonymousDeviceId -e t27SourceCommit $SourceCommit -e t27HarnessCommit $HarnessCommit -e t27TargetApkSha256 $ExpectedApkSha256 -e t27InstrumentationApkSha256 $InstrumentationApkSha256"
        }
        $output = Get-TestDeviceShellOutput -Command "am instrument -w -r $selector $runner"
        Write-Output $output
        if ($output -notmatch '(?m)^OK \([1-9][0-9]* tests?\)' -or $output -match '(?m)^(FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED)') { throw 'Instrumentation did not report success.' }
    } finally { $env:ANDROID_SERIAL = $previousSerial }
}
if ($MyInvocation.InvocationName -ne '.') { $PSBoundParameters['Mode'] = $Mode; Invoke-CurboxInstrumentation @PSBoundParameters }
