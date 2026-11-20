param(
    [string]$ApkPath,
    [string]$ExpectedApkSha256,
    [string]$BuildToolsDirectory
)
. "$PSScriptRoot/lib/learning-device-test-common.ps1"
if ($ApkPath) { Assert-LearningFakeApk $ApkPath $ExpectedApkSha256 $BuildToolsDirectory }
$skip = if ($ApkPath) { $null } else { 'Fake debug artifact was not provided.' }
$common = @('-TargetPackage', 'neth.iecal.curbox.apitester', '-LearningApkPath', $ApkPath, '-ExpectedApkSha256', $ExpectedApkSha256, '-BuildToolsDirectory', $BuildToolsDirectory)
foreach ($entry in @(
    @{ Name = 'test-device-learning-monitor.ps1'; Extra = @() },
    @{ Name = 'test-device-todo-extra-time.ps1'; Extra = @('-Scenario', 'Daily') }
)) {
    [PSCustomObject]@{ Name = $entry.Name; FullName = (Join-Path $PSScriptRoot $entry.Name); Arguments = @($common + $entry.Extra); SkipReason = $skip }
}
