param(
    [string]$TargetPackage = "neth.iecal.curbox.apitester",
    [string]$DeviceId = "",
    [Parameter(Mandatory = $true)][string]$LearningApkPath,
    [Parameter(Mandatory = $true)][string]$ExpectedApkSha256,
    [Parameter(Mandatory = $true)][string]$BuildToolsDirectory
)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot/../../device-tests/lib/device-test-common.ps1"
. "$PSScriptRoot/lib/learning-device-test-common.ps1"
if ($DeviceId) { $env:ANDROID_SERIAL = $DeviceId }
Assert-AdbDevice
Assert-LearningFakeApk $LearningApkPath $ExpectedApkSha256 $BuildToolsDirectory
if ($TargetPackage -ne "neth.iecal.curbox.apitester") { throw "This test requires the harmless API tester." }
$trust = Get-TestDeviceShellOutput -Command "dumpsys trust"
if ($trust -notmatch '(?m)^\s*User [^\r\n]*\(current\):[^\r\n]*deviceLocked=0(?:,|\s|$)') { throw "Unlock the test device before running this test." }
if (-not (Test-AccessibilityServiceBound)) { throw "Curbox accessibility service must already be bound." }
$epoch = Get-TestDeviceEpochTimeMs
if (-not $epoch) { throw "Cannot read device time." }
$seoul = [DateTimeOffset]::FromUnixTimeMilliseconds($epoch).ToOffset([TimeSpan]::FromHours(9))
if ($seoul.Hour -eq 23 -and $seoul.Minute -ge 55) { throw "Run again after midnight in Seoul." }
$date = $seoul.ToString('yyyyMMdd')
$registered = $false
$fixtureWritten = $false
try {
    Set-DeviceAwake $true
    Invoke-TestDeviceShell -Command 'input keyevent 224'
    $window = Get-TestDeviceShellOutput -Command 'dumpsys window'
    if ($window -match 'mCurrentFocus=.*NotificationShade') {
        $size = Get-TestDeviceShellOutput -Command 'wm size'
        if ($size -notmatch 'Physical size: (\d+)x(\d+)') { throw "Cannot read device screen size." }
        $x = [int]([int]$Matches[1] / 2); $height = [int]$Matches[2]
        Invoke-TestDeviceShell -Command "input swipe $x $([int]($height * .8)) $x $([int]($height * .2)) 350"
    }
    $window = Get-TestDeviceShellOutput -Command 'dumpsys window'
    if ($window -match 'mCurrentFocus=.*(NotificationShade|Keyguard)') { throw "Unlock the test device and run again." }
    if ((Get-LearningRegistrationCount) -ne 0 -or @(Get-LearningNotifications).Count -ne 0) { throw "Remove existing learning registrations on this dedicated test device first." }
    $existingFixture = Get-TestDeviceShellOutput -Command "run-as neth.iecal.curbox.debug sh -c 'if [ -e files/synthetic-learning-fixture.json ]; then echo PRESENT; else echo ABSENT; fi'"
    if ($existingFixture.Trim() -ne 'ABSENT') { throw "Remove the existing synthetic fixture before running." }
    $accessibilityBackup = Backup-DeviceAccessibilitySettings
    $previousServicePid = Get-DeviceProcessPid -ProcessName 'neth.iecal.curbox.debug:app_blocker_service'
    if (-not $previousServicePid) { throw "Cannot identify the bound Curbox service process." }
    $coldStart = Restart-TestCurboxCold -AccessibilitySettings $accessibilityBackup
    if ($coldStart.Skipped -or -not $coldStart.ProcessId -or $coldStart.ProcessId -eq $previousServicePid) {
        throw "Curbox service did not restart and bind with a new process."
    }
    $baseline = "{`"revision`":`"baseline`",`"children`":[{`"name`":`"Child1`",`"users`":{`"MATH`":`"user1`"}}],`"reports`":[{`"user`":`"user1`",`"subject`":`"MATH`",`"date`":`"$date`",`"count`":2}]}"
    $fixtureWritten = $true
    Set-LearningFixture $baseline
    Start-TestApp -PackageName 'neth.iecal.curbox.debug' -ActivityName 'neth.iecal.curbox.ui.activity.FragmentActivity' -IntentArguments '-f 0x10008000 --es fragment learning' | Out-Null
    $ui = Wait-For-UI 'id/signIn|id/dashboard|password="true"' 20
    if ($ui -match 'password="true"' -and $ui -notmatch 'id/signIn|id/dashboard') { throw "Unlock Curbox before running this test." }
    if ($ui -match 'id/dashboard') {
        Refresh-SyntheticLearningDashboard $ui
    } else {
        Submit-SyntheticLearningSignIn $false
    }
    $ui = Wait-SyntheticChildAction 'Start watching' 20
    if (-not (Tap-Node $ui 'content-desc="Subject settings"' 'Subject settings')) { throw "Subject settings not found." }
    $ui = Wait-For-UI 'Subject settings' 10
    [xml]$doc = $ui
    if (-not $doc.SelectSingleNode('//node[@class="android.widget.EditText" and @text="5"]')) { throw "Set the synthetic Math goal to 5 before running." }
    if (-not (Tap-Node $ui 'text="Cancel"' 'Cancel subject settings')) { throw "Cancel not found." }
    $ui = Wait-SyntheticChildAction 'Start watching' 20
    $registered = $true
    if (-not (Tap-Node $ui 'content-desc="Start watching"' 'Start watching')) { $registered = $false; throw "Registration button not found." }
    $wait = [Diagnostics.Stopwatch]::StartNew()
    $record = $null
    while ($wait.Elapsed.TotalSeconds -lt 40) {
        $items = @(Get-LearningNotifications)
        if ($items.Count -eq 1 -and $items[0].Text -match '(?m)^                android\.title=String \(Child1\)' -and $items[0].Text -match '(?m)^                android\.text=(?:String|SpannableString|SpannedString|SpannableStringBuilder) \(\uc218\ud559 2/5\)' -and $items[0].Text -match '(?m)^    NotificationRecord\([^\r\n]*sound=null[^\r\n]*groupKey=silent') { $record = $items[0]; break }
        Start-Sleep -Seconds 2
    }
    if (-not $record) { throw "Silent 2 of 5 baseline notification was not observed." }
    $completion = $baseline.Replace('baseline', 'completed').Replace('"count":2', '"count":5')
    Set-LearningFixture $completion
    Start-TestApp -PackageName $TargetPackage | Out-Null
    $focus = Wait-ForWindowFocus -ExpectedActivity $TargetPackage -TimeoutSeconds 15
    if (-not $focus.Success) { throw "API tester did not enter foreground." }
    $wait.Restart()
    $finished = $false
    while ($wait.Elapsed.TotalSeconds -lt 100) {
        $focus = Assert-WindowFocus -ExpectedActivity $TargetPackage -PassThru
        if (-not $focus.Success) { throw "API tester lost foreground." }
        $items = @(Get-LearningNotifications)
        if ($items.Count -eq 1 -and $items[0].Tag -eq $record.Tag -and $items[0].Id -eq $record.Id -and $items[0].Text -match '(?m)^                android\.title=String \(Child1\)' -and $items[0].Text -match '(?m)^                android\.text=(?:String|SpannableString|SpannedString|SpannableStringBuilder) \(\uc218\ud559 \u2713\)') { $finished = $true; break }
        Start-Sleep -Seconds 3
    }
    if (-not $finished) { throw "Completion notification did not replace the baseline." }
    Write-Success "Foreground tester produced the same child's completion notification."
} finally {
    $cleanupErrors = @()
    if ($registered) {
        try {
            Start-TestApp -PackageName 'neth.iecal.curbox.debug' -ActivityName 'neth.iecal.curbox.ui.activity.FragmentActivity' -IntentArguments '-f 0x10008000 --es fragment learning' | Out-Null
            $ui = Wait-For-UI 'id/dashboard|password="true"' 20
            if ($ui -match 'password="true"') { throw "Unlock Curbox to remove the synthetic registration." }
            $ui = Wait-SyntheticChildAction 'Stop watching' 20
            $names = @(Get-LearningRegistrationNames)
            [xml]$doc = $ui
            if ($names.Count -ne 1 -or $names[0] -cne 'Child1' -or @($doc.SelectNodes('//node[@text="Child1"]')).Count -ne 1 -or @($doc.SelectNodes('//node[@content-desc="Stop watching"]')).Count -ne 1) { throw "Cannot safely identify the synthetic registration." }
            if (-not (Tap-Node $ui 'content-desc="Stop watching"' 'Stop watching')) { throw "Could not stop the synthetic registration." }
            $ui = Wait-SyntheticChildAction 'Start watching' 20
        } catch { $cleanupErrors += $_.Exception.Message }
    }
    if ($fixtureWritten) { try { Reset-LearningFixture } catch { $cleanupErrors += $_.Exception.Message } }
    try { Set-DeviceAwake $false } catch { $cleanupErrors += $_.Exception.Message }
    try { Invoke-TestDeviceShell -Command "am force-stop neth.iecal.curbox.apitester" } catch { $cleanupErrors += $_.Exception.Message }
    try { Invoke-TestDeviceShell -Command 'input keyevent 3' } catch { $cleanupErrors += $_.Exception.Message }
    try {
        $cleanupWait = [Diagnostics.Stopwatch]::StartNew()
        while (@(Get-LearningNotifications).Count -ne 0 -and $cleanupWait.Elapsed.TotalSeconds -lt 10) { Start-Sleep -Seconds 1 }
        if (@(Get-LearningNotifications).Count -ne 0) { throw "Learning notification remains." }
    } catch { $cleanupErrors += $_.Exception.Message }
    try { if ((Get-LearningRegistrationCount) -ne 0) { throw "Learning registration remains." } } catch { $cleanupErrors += $_.Exception.Message }
    try { if (-not (Test-AccessibilityServiceBound)) { throw "Accessibility service is not bound." } } catch { $cleanupErrors += $_.Exception.Message }
    if ($cleanupErrors.Count) { throw "Cleanup failed: $($cleanupErrors -join '; ')" }
}
