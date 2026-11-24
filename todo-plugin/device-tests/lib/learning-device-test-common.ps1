function Test-ApprovedLearningApk([string]$ExpectedHash, [string]$ArtifactHash, [string]$InstalledHash, [string]$Signer, [string]$Manifest, [string]$Dex) {
    $expectedSigner = "2429a67995d6e0a03312ff8f67d7df53c40dbdeb86e454d62f87d8e8243e4c6a"
    return ($ExpectedHash -match '^[0-9a-fA-F]{64}$' -and $ArtifactHash -eq $ExpectedHash -and
        $InstalledHash -eq $ExpectedHash -and $Signer -eq $expectedSigner -and
        $Manifest.Contains('android:debuggable(0x0101000f)=(type 0x12)0xffffffff') -and
        $Manifest.Contains('Ticket19DebugAppComponentFactory') -and
        $Manifest.Contains('SyntheticFixtureProvider') -and $Manifest.Contains('neth.iecal.curbox.debug.synthetic.fixture') -and
        $Manifest.Contains('android.permission.DUMP') -and $Dex.Contains('LearningComposition$createApi$1') -and
        $Dex.Contains('SyntheticFixtureProvider') -and $Dex.Contains('neth.iecal.curbox.apitester') -and
        -not $Dex.Contains('https://api.todomath.com'))
}

function Assert-LearningFakeApk([string]$ApkPath, [string]$ExpectedHash, [string]$BuildToolsDirectory) {
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw "Fake debug APK not found." }
    $aapt = Join-Path $BuildToolsDirectory "aapt.exe"
    $signerTool = Join-Path $BuildToolsDirectory "apksigner.bat"
    if (-not (Test-Path -LiteralPath $aapt -PathType Leaf) -or -not (Test-Path -LiteralPath $signerTool -PathType Leaf)) { throw "Android build tools not found." }
    $packagePath = Get-TestDeviceShellOutput -Command "pm path neth.iecal.curbox.debug"
    if ($packagePath -notmatch '^package:(/data/app/[A-Za-z0-9_./~=-]+/base\.apk)$') { throw "Cannot identify installed Curbox APK." }
    $installedPath = $Matches[1]
    $installedOutput = Get-TestDeviceShellOutput -Command "sha256sum $installedPath"
    if ($installedOutput -notmatch '^([0-9a-fA-F]{64})\s') { throw "Cannot hash installed Curbox APK." }
    $installedHash = $Matches[1]
    $artifactHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
    $manifest = (& $aapt dump xmltree $ApkPath AndroidManifest.xml | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect APK manifest." }
    $previousErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $signerOutput = (& $signerTool verify --print-certs $ApkPath 2>$null | Out-String)
        $signerExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorPreference }
    if ($signerExitCode -ne 0 -or $signerOutput -notmatch 'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})') { throw "Cannot verify APK signer." }
    $signer = $Matches[1]
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath).Path)
    try {
        $dex = New-Object System.Text.StringBuilder
        $entries = @($archive.Entries | Where-Object { $_.FullName -match '^classes[0-9]*\.dex$' })
        if ($entries.Count -eq 0) { throw "APK contains no DEX." }
        foreach ($entry in $entries) {
            $stream = $entry.Open()
            try {
                $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::GetEncoding(28591))
                try { [void]$dex.Append($reader.ReadToEnd()) } finally { $reader.Dispose() }
            } finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
    if (-not (Test-ApprovedLearningApk $ExpectedHash $artifactHash $installedHash $signer $manifest $dex.ToString())) { throw "Installed APK did not pass fake debug identity checks." }
}

function Get-LearningRegistrationNames {
    $raw = Get-TestDeviceShellOutput -Command "run-as neth.iecal.curbox.debug sh -c 'if [ -f files/datastore/learning-fake-state.json ]; then if [ -s files/datastore/learning-fake-state.json ]; then printf STATE:; cat files/datastore/learning-fake-state.json; fi; else echo __ABSENT__; fi'"
    if ($raw -ceq '' -or $raw.Trim() -eq '__ABSENT__') { return }
    if ($raw.StartsWith('STATE:')) { $raw = $raw.Substring(6) }
    try { $document = $raw | ConvertFrom-Json -ErrorAction Stop } catch { throw "Invalid fake learning state." }
    if (-not $document.state -or -not $document.monitor -or
        -not $document.monitor.PSObject.Properties['registrations'] -or
        $document.monitor.registrations -isnot [pscustomobject] -or
        $document.state.accountId -cne $document.monitor.accountId) { throw "Unsupported fake learning state." }
    $names = @($document.monitor.registrations.PSObject.Properties | ForEach-Object { $_.Name })
    if ($names.Count -eq 0 -and $document.monitor.childName) { return [string]$document.monitor.childName }
    return $names
}
function Get-LearningRegistrationCount { return @(Get-LearningRegistrationNames).Count }

function Test-SilentLearningBaseline([string]$Record) {
    return ($Record -match '(?m)^    NotificationRecord\([^\r\n]*\bsound=null(?:\s|\))' -and
        ($Record -match '(?m)^    NotificationRecord\([^\r\n]*\bflags=(?:[^\s|]+\|)*SILENT(?:\||\s)' -or
         $Record -match '(?m)^    NotificationRecord\([^\r\n]*\bgroupKey=silent(?:\s|\))') -and
        $Record -match '(?m)^\s+android\.title=String \(Child1\)\s*$' -and
        $Record -match '(?m)^\s+android\.text=(?:String|SpannableString|SpannedString|SpannableStringBuilder) \(\uc218\ud559 2/5\)\s*$')
}

function Get-LearningNotifications {
    $dump = Get-TestDeviceShellOutput -Command "dumpsys notification --noredact"
    if ($dump -notmatch '(?ms)^  Notification List:\s*\r?\n(.*?)(?=^  \S|\z)') { throw "Unsupported notification dump format." }
    $active = $Matches[1]
    foreach ($record in ($active -split '(?m)(?=^    NotificationRecord\()')) {
        if ($record -match '(?s)^\s*NotificationRecord\([^\r\n]*pkg=neth\.iecal\.curbox\.debug\s[^\r\n]*id=(-?\d+)\s+tag=(learning:[^\s]+)') {
            [PSCustomObject]@{ Id = $Matches[1]; Tag = $Matches[2]; Text = $record }
        }
    }
}

function Set-LearningFixture([string]$Json) {
    if ($Json -notmatch '^[A-Za-z0-9{},:"_\[\] ]+$') { throw "Invalid synthetic fixture characters." }
    $output = Get-TestDeviceShellOutput -Command "content call --uri content://neth.iecal.curbox.debug.synthetic.fixture --method write --arg '$Json'"
    if ($output -notmatch 'ok=true') { throw "Synthetic fixture write failed: $output" }
}

function Reset-LearningFixture {
    $output = Get-TestDeviceShellOutput -Command "content call --uri content://neth.iecal.curbox.debug.synthetic.fixture --method reset"
    if ($output -notmatch 'ok=true') { throw "Synthetic fixture reset failed: $output" }
}


# These backups remain private to the Debug app and contain no copied host-side credentials.
function Get-TodoSettlementFailureCount {
    $count = (Get-TestDeviceShellOutput -Command "run-as neth.iecal.curbox.debug sh -c 'if [ -f files/crash_log.txt ]; then grep -c `"Todo settlement lookup failed`" files/crash_log.txt || true; else echo 0; fi'").Trim()
    if ($count -notmatch '^\d+$') { throw 'Cannot read Todo settlement failure count.' }
    return [long]$count
}
function Backup-FakeLearningFiles {
    $id = [guid]::NewGuid().ToString('N')
    $directory = "files/.codex-learning-$id"
    Invoke-TestDeviceShell -Command "run-as neth.iecal.curbox.debug sh -c 'umask 077; mkdir $directory || exit 1; for name in learning-fake-state.json learning-fake-session.json; do if [ -e files/datastore/`$name ]; then cp -p files/datastore/`$name $directory/`$name || exit 1; fi; done'"
    return $id
}
function Restore-FakeLearningFiles([string]$BackupId) {
    if ($BackupId -notmatch '^[a-f0-9]{32}$') { throw 'Invalid fake learning backup identifier.' }
    $directory = "files/.codex-learning-$BackupId"
    Invoke-TestDeviceShell -Command "run-as neth.iecal.curbox.debug sh -c 'test -d $directory || exit 1; for name in learning-fake-state.json learning-fake-session.json; do if [ -e $directory/`$name ]; then cp -p $directory/`$name files/datastore/`$name.restore || exit 1; mv -f files/datastore/`$name.restore files/datastore/`$name || exit 1; else rm -f files/datastore/`$name || exit 1; fi; done; rm -f $directory/learning-fake-state.json $directory/learning-fake-session.json; rmdir $directory'"
}

# Uses only the synthetic fake account. UI dumps are never printed because fields may contain credentials.
function Get-SyntheticSignInUi {
    if ($script:DeviceTestSyntheticSignInUiHandler -is [scriptblock]) { return (& $script:DeviceTestSyntheticSignInUiHandler) }
    return (Dump-UI)
}

function Get-SyntheticSignInFields([string]$Ui) {
    [xml]$doc = $Ui
    $email = @($doc.SelectNodes('//node[@class="android.widget.EditText" and not(@password="true")]'))
    $password = @($doc.SelectNodes('//node[@class="android.widget.EditText" and @password="true"]'))
    return @{ Email = $email; Password = $password }
}

function Get-SyntheticSignInDiagnostics([string]$Ui) {
    try {
        $fields = Get-SyntheticSignInFields $Ui
        $email = if ($fields.Email.Count -eq 1) { $fields.Email[0] } else { $null }
        $password = if ($fields.Password.Count -eq 1) { $fields.Password[0] } else { $null }
        $emailLength = if ($email) { $email.GetAttribute('text').Length } else { -1 }
        $passwordLength = if ($password) { $password.GetAttribute('text').Length } else { -1 }
        $emailFocused = $null -ne $email -and $email.GetAttribute('focused') -eq 'true'
        $passwordFocused = $null -ne $password -and $password.GetAttribute('focused') -eq 'true'
        return "emailCount=$($fields.Email.Count), passwordCount=$($fields.Password.Count), emailFocused=$emailFocused, passwordFocused=$passwordFocused, emailLength=$emailLength, passwordLength=$passwordLength"
    } catch { return 'unreadable fields' }
}

function Submit-SyntheticLearningSignIn([bool]$AccountDialog) {
    foreach ($fieldName in @('email', 'password')) {
        $ui = Get-SyntheticSignInUi
        try {
            $fields = Get-SyntheticSignInFields $ui
            $nodes = @(if ($fieldName -eq 'email') { $fields.Email } else { $fields.Password })
            if ($nodes.Count -ne 1 -or $nodes[0].GetAttribute('bounds') -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'missing bounds' }
            $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        } catch { throw "Synthetic sign in $fieldName field unavailable ($(Get-SyntheticSignInDiagnostics $ui))." }
        Invoke-TestDeviceShell -Command "input tap $x $y"
        $focusWait = [Diagnostics.Stopwatch]::StartNew()
        $focused = $false
        while ($focusWait.Elapsed.TotalSeconds -lt 5) {
            $ui = Get-SyntheticSignInUi
            try {
                $fields = Get-SyntheticSignInFields $ui
                $nodes = @(if ($fieldName -eq 'email') { $fields.Email } else { $fields.Password })
                if ($nodes.Count -eq 1 -and $nodes[0].GetAttribute('focused') -eq 'true') { $focused = $true; break }
            } catch { }
            Start-Sleep -Milliseconds 250
        }
        if (-not $focused) { throw "Synthetic sign in $fieldName field did not gain focus ($(Get-SyntheticSignInDiagnostics $ui))." }
        Invoke-TestDeviceShell -Command ('input keyevent 123 ' + (('67 ' * 200).Trim()))
        $value = if ($fieldName -eq 'email') { 'fake@example.com' } else { 'fake' }
        Invoke-TestDeviceShell -Command "input text $value"
        if ($fieldName -eq 'email' -and (Get-TestDeviceShellOutput -Command 'dumpsys input_method') -match 'mInputShown=true') {
            Invoke-TestDeviceShell -Command 'input keyevent 4'
            $imeWait = [Diagnostics.Stopwatch]::StartNew()
            while ((Get-TestDeviceShellOutput -Command 'dumpsys input_method') -match 'mInputShown=true' -and $imeWait.Elapsed.TotalSeconds -lt 5) { Start-Sleep -Milliseconds 250 }
            if ((Get-TestDeviceShellOutput -Command 'dumpsys input_method') -match 'mInputShown=true') { throw 'Synthetic sign in keyboard did not close.' }
        }
    }
    $ui = Get-SyntheticSignInUi
    try {
        $fields = Get-SyntheticSignInFields $ui
        if ($fields.Email.Count -ne 1 -or $fields.Password.Count -ne 1 -or $fields.Email[0].GetAttribute('text') -cne 'fake@example.com' -or $fields.Password[0].GetAttribute('text').Length -ne 4) { throw 'invalid' }
    } catch { throw "Synthetic sign in fields did not contain the expected values ($(Get-SyntheticSignInDiagnostics $ui))." }
    if ((Get-TestDeviceShellOutput -Command 'dumpsys input_method') -match 'mInputShown=true') {
        Invoke-TestDeviceShell -Command 'input keyevent 4'
    }
    $buttonPattern = if ($AccountDialog) { 'resource-id="android:id/button1"' } else { 'resource-id="neth.iecal.curbox.debug:id/signIn"' }
    $ui = Get-SyntheticSignInUi
    $button = Get-NodeBounds $ui $buttonPattern
    if (-not $button.Found) { throw 'Synthetic sign in button missing.' }
    Invoke-TestDeviceShell -Command "input tap $($button.X) $($button.Y)"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt 20) {
        $ui = Get-SyntheticSignInUi
        if ($ui -match 'id/dashboard' -and $ui -notmatch 'resource-id="android:id/button1"|id/signIn') { return }
        Start-Sleep -Milliseconds 500
    }
    throw "Synthetic sign in did not reach dashboard ($(Get-SyntheticLearningUiState $ui))."
}

function Refresh-SyntheticLearningDashboard([string]$Ui) {
    $refresh = Get-NodeBounds $Ui 'resource-id="neth.iecal.curbox.debug:id/swipeRefresh"'
    if (-not $refresh.Found -or $refresh.Y2 -le $refresh.Y1) { throw 'Learning refresh area not found.' }
    $height = $refresh.Y2 - $refresh.Y1
    $startY = [int]($refresh.Y1 + $height * 0.2)
    $endY = [int]($refresh.Y1 + $height * 0.7)
    # The first gesture also returns a previously scrolled dashboard to the top.
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        Invoke-TestDeviceShell -Command "input swipe $($refresh.X) $startY $($refresh.X) $endY 500"
        Start-Sleep -Milliseconds 500
    }
}

function Get-SyntheticLearningUiState([string]$Ui) {
    try {
        [xml]$doc = $Ui
        $login = $null -ne $doc.SelectSingleNode('//node[contains(@resource-id, ":id/signIn")]')
        $dialog = $null -ne $doc.SelectSingleNode('//node[@resource-id="android:id/button1"]') -and $null -ne $doc.SelectSingleNode('//node[@password="true"]')
        $dashboard = $null -ne $doc.SelectSingleNode('//node[contains(@resource-id, ":id/dashboard")]')
        $error = $Ui -match '(?i)error'
        return "login=$login, dialog=$dialog, dashboard=$dashboard, error=$error"
    } catch { return 'unreadable UI' }
}

function Wait-SyntheticChildAction([ValidateSet('Start watching', 'Stop watching')][string]$Action, [ValidateRange(1, 300)][int]$TimeoutSeconds = 20) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $childCount = 0; $sampleCount = 0; $actionCount = 0; $matchingHeaders = 0
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $ui = if ($script:DeviceTestSyntheticUiDumpHandler -is [scriptblock]) { & $script:DeviceTestSyntheticUiDumpHandler } else { Dump-UI }
        try {
            [xml]$doc = $ui
            $childCount = @($doc.SelectNodes('//node[@text="Child1"]')).Count
            $sampleCount = @($doc.SelectNodes('//node[@text="Sample"]')).Count
            $actionCount = @($doc.SelectNodes("//node[@content-desc='$Action']")).Count
            $matchingHeaders = @($doc.SelectNodes("//node[node[@text='Child1'] and node[@content-desc='$Action']]")).Count
            if ($childCount -eq 1 -and $actionCount -eq 1 -and $matchingHeaders -eq 1) { return $ui }
        } catch { }
        $remainingMs = [int][math]::Max(0, ($TimeoutSeconds * 1000) - $timer.ElapsedMilliseconds)
        if ($remainingMs -gt 0) { Start-Sleep -Milliseconds ([math]::Min(500, $remainingMs)) }
    }
    throw "Synthetic child action timed out (Child1=$childCount, Sample=$sampleCount, action=$actionCount, matching headers=$matchingHeaders; $(Get-SyntheticLearningUiState $ui))."
}


function Get-TodoAwardState($Settings) {
    $entry = $Settings.pluginTimes.todo
    if (-not $entry -or [string]::IsNullOrWhiteSpace([string]$entry.data)) {
        return [pscustomobject]@{ connection = $null; ledger = @(); pending = @() }
    }
    try { return ($entry.data | ConvertFrom-Json -ErrorAction Stop) }
    catch { throw 'Invalid Todo plugin award state.' }
}

function Clear-TodoAwardState($Settings) {
    if ($Settings.pluginTimes) { $Settings.pluginTimes.PSObject.Properties.Remove('todo') }
    foreach ($key in @('todoAwardConnection', 'todoAwardConnectionRevision', 'todoAwardLedger', 'todoPendingSettlements')) {
        $Settings.PSObject.Properties.Remove($key)
    }
}
