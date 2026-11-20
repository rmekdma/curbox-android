param([string]$ApkPath, [string]$ExpectedHash, [string]$BuildToolsDirectory)
. "$PSScriptRoot/lib/learning-device-test-common.ps1"
Assert-LearningFakeApk $ApkPath $ExpectedHash $BuildToolsDirectory
