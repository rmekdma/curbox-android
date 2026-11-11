# Instrumentation runs

`run-instrumentation.ps1` runs the functional suite or the fixed fixture P95 measurement separately. Install the fake fullDebug target and its matching instrumentation APK first. Disable the Debug Curbox accessibility service before running either suite; the script checks this precondition and does not change or restore accessibility settings. Use a dedicated test device. The script checks the selected device serial, target fake debug identity, both APK hashes and debug signer, and the installed APK hashes. It does not build or install APKs.

```powershell
$common = @{
    DeviceId = '<adb serial>'
    LearningApkPath = '<fake fullDebug APK path>'
    ExpectedApkSha256 = '<target APK SHA256>'
    InstrumentationApkPath = '<instrumentation APK path>'
    InstrumentationApkSha256 = '<instrumentation APK SHA256>'
    BuildToolsDirectory = '<Android SDK build-tools directory>'
}
.\device-tests\run-instrumentation.ps1 -Mode Functional @common
.\device-tests\run-instrumentation.ps1 -Mode Performance @common `
    -PseudonymousDeviceId '<pseudonymous device ID>' `
    -SourceCommit '<source commit>' -HarnessCommit '<harness commit>'
```

Functional mode excludes only the method annotated `PerformanceMeasurement`; its two fixture guard and cleanup tests remain in the functional suite. Performance mode selects the exact measurement method and requires all six run identities. Report the two results and their test counts separately. No performance budget is defined by this runner.

An unfiltered `am instrument` or another runner entrypoint still discovers the measurement and will fail without its required identities. Use the entrypoint above for functional runs. The existing `run-all-device-tests.ps1` remains the separate ADB device scenario suite.
