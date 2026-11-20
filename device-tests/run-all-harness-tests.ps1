$ErrorActionPreference = 'Stop'
$testFiles = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests') -Filter '*.Tests.ps1' -File | Sort-Object FullName | ForEach-Object { $_.FullName })
$testFiles += @(Get-ChildItem -Path (Join-Path $PSScriptRoot '..') -Directory | ForEach-Object {
    $testDirectory = Join-Path $_.FullName 'device-tests/tests'
    if (Test-Path -LiteralPath $testDirectory -PathType Container) {
        Get-ChildItem -LiteralPath $testDirectory -Filter '*.Tests.ps1' -File | Sort-Object FullName | ForEach-Object { $_.FullName }
    }
})
$result = Invoke-Pester -Script $testFiles -PassThru
if ($result.FailedCount -gt 0) { exit 1 }
