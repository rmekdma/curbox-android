# Tests for device-test-common.ps1 functions

# Run the harness tests in an offline command scope. Remove every adb.exe search
# directory from this Pester process before loading the helpers, then shadow adb
# with a throwing function in the test script scope. Restore the caller's command
# environment when this test file finishes.
$script:OfflineAdbOriginalPath = $env:PATH
$script:OfflineAdbHadGlobalFunction = Test-Path Function:\global:adb
$script:OfflineAdbGlobalFunction = if ($script:OfflineAdbHadGlobalFunction) {
    (Get-Item Function:\global:adb).ScriptBlock
} else { $null }
$script:OfflineAdbHadScriptFunction = Test-Path Function:\adb
$script:OfflineAdbScriptFunction = if ($script:OfflineAdbHadScriptFunction) {
    (Get-Item Function:\adb).ScriptBlock
} else { $null }
$script:OfflineAdbHadGlobalCounter = Test-Path Variable:\global:DeviceTestUnexpectedAdbCalls
$script:OfflineAdbGlobalCounter = if ($script:OfflineAdbHadGlobalCounter) {
    $global:DeviceTestUnexpectedAdbCalls
} else { $null }
$adbDirectories = @(Get-Command adb.exe -All -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandType -eq "Application" -and $_.Source } |
    ForEach-Object { Split-Path $_.Source -Parent } |
    Select-Object -Unique)
$pathEntries = @($env:PATH -split [regex]::Escape([string][System.IO.Path]::PathSeparator) |
    Where-Object { $_ -and ($adbDirectories -notcontains $_) })
$env:PATH = $pathEntries -join [System.IO.Path]::PathSeparator
$global:DeviceTestUnexpectedAdbCalls = 0
$offlineAdbStub = {
    $global:DeviceTestUnexpectedAdbCalls++
    throw "Unexpected adb invocation in offline Pester tests: $($args -join ' ')"
}
Set-Item -Path Function:\global:adb -Value $offlineAdbStub -Force
Set-Item -Path Function:\adb -Value $offlineAdbStub -Force

try {
Describe "Learning Device Test Helpers" {
    BeforeEach {
        . "$PSScriptRoot/../../../device-tests/lib/device-test-common.ps1"
        . "$PSScriptRoot/../lib/learning-device-test-common.ps1"
        $global:DeviceTestUnexpectedAdbCalls = 0
        $ErrorActionPreference = "Continue"
    }
    AfterEach {
        if ($global:DeviceTestUnexpectedAdbCalls -gt 0) { throw "A helper attempted adb during the offline Pester suite." }
    }

    Context "Synthetic learning sign in" {
        AfterEach { $script:DeviceTestSyntheticSignInUiHandler = $null; $script:DeviceTestShellHandler = $null; $script:DeviceTestShellOutputHandler = $null }
        It "reselects password after keyboard reflows and verifies before submitting" {
            $script:signInDump = 0; $script:signInCommands = @()
            $script:DeviceTestSyntheticSignInUiHandler = {
                $script:signInDump++
                switch ($script:signInDump) {
                    1 { '<hierarchy><node class="android.widget.EditText" text="old" bounds="[0,0][100,100]"/><node class="android.widget.EditText" password="true" text="" bounds="[0,200][100,300]"/></hierarchy>' }
                    2 { '<hierarchy><node class="android.widget.EditText" focused="true" text="old"/><node class="android.widget.EditText" password="true" text=""/></hierarchy>' }
                    3 { '<hierarchy><node class="android.widget.EditText" text="fake@example.com" bounds="[0,0][100,100]"/><node class="android.widget.EditText" password="true" text="" bounds="[0,100][100,200]"/></hierarchy>' }
                    4 { '<hierarchy><node class="android.widget.EditText" text="fake@example.com"/><node class="android.widget.EditText" password="true" focused="true" text=""/></hierarchy>' }
                    5 { '<hierarchy><node class="android.widget.EditText" text="fake@example.com"/><node class="android.widget.EditText" password="true" focused="true" text="••••"/></hierarchy>' }
                    6 { '<hierarchy><node resource-id="android:id/button1" bounds="[0,300][100,400]"/></hierarchy>' }
                    default { '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/dashboard"/></hierarchy>' }
                }
            }
            $script:DeviceTestShellHandler = { param($command) $script:signInCommands += $command }
            $script:imeReads = 0
            $script:DeviceTestShellOutputHandler = { param($command) $script:imeReads++; if ($script:imeReads -eq 1 -or $script:imeReads -eq 4) { 'mInputShown=true' } else { 'mInputShown=false' } }
            Submit-SyntheticLearningSignIn $true
            ($script:signInCommands -contains 'input tap 50 150') | Should Be $true
            ($script:signInCommands -contains 'input tap 50 250') | Should Be $false
            ($script:signInCommands -contains 'input keyevent 4') | Should Be $true
            ($script:signInCommands -contains 'input tap 50 350') | Should Be $true
        }
        It "swipes within the dashboard refresh area" {
            $script:signInCommands = @()
            $script:DeviceTestShellHandler = { param($command) $script:signInCommands += $command }
            Refresh-SyntheticLearningDashboard '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/swipeRefresh" bounds="[0,100][1000,1100]"/></hierarchy>'
            @($script:signInCommands).Count | Should Be 2
            ($script:signInCommands[0] -eq 'input swipe 500 300 500 800 500') | Should Be $true
        }
        It "reports field structure and lengths without content" {
            $state = Get-SyntheticSignInDiagnostics '<hierarchy><node class="android.widget.EditText" text="secret" focused="false"/><node class="android.widget.EditText" password="true" text="••" focused="true"/></hierarchy>'
            ($state -match 'emailCount=1, passwordCount=1') | Should Be $true
            ($state -match 'passwordFocused=True, emailLength=6, passwordLength=2') | Should Be $true
            ($state -match 'secret|••') | Should Be $false
        }
        It "reports only screen state and not UI content" {
            $state = Get-SyntheticLearningUiState '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/signIn" text="private-value"/></hierarchy>'
            ($state -match 'login=True') | Should Be $true
            ($state -match 'private-value') | Should Be $false
        }
    }

    Context "Synthetic child action polling" {
        AfterEach { $script:DeviceTestSyntheticUiDumpHandler = $null }
        It "waits through a stale Sample and an action in the wrong header" {
            $script:pollCount = 0
            $script:DeviceTestSyntheticUiDumpHandler = {
                $script:pollCount++
                if ($script:pollCount -eq 1) { return '<hierarchy><node><node text="Sample"/><node content-desc="Start watching"/></node></hierarchy>' }
                if ($script:pollCount -eq 2) { return '<hierarchy><node><node text="Child1"/></node><node><node content-desc="Start watching"/></node></hierarchy>' }
                return '<hierarchy><node><node text="Child1"/><node content-desc="Start watching"/></node></hierarchy>'
            }
            $result = Wait-SyntheticChildAction 'Start watching' 3
            $script:pollCount | Should Be 3
            ($result -match 'Child1') | Should Be $true
        }
        It "accepts the stop label in the child header" {
            $script:DeviceTestSyntheticUiDumpHandler = { return '<hierarchy><node><node text="Child1"/><node content-desc="Stop watching"/></node></hierarchy>' }
            (Wait-SyntheticChildAction 'Stop watching' 2) | Should Match 'Stop watching'
        }
        It "times out when the matching action never appears" {
            $script:DeviceTestSyntheticUiDumpHandler = { return '<hierarchy><node><node text="Sample"/><node content-desc="Start watching"/></node></hierarchy>' }
            $watch = [Diagnostics.Stopwatch]::StartNew()
            $message = ''
            try { Wait-SyntheticChildAction 'Start watching' 1 } catch { $message = $_.Exception.Message }
            ($message -match 'timed out.*Child1=0, Sample=1') | Should Be $true
            ($watch.Elapsed.TotalSeconds -lt 3) | Should Be $true
        }
    }

    Context "Approved fake learning APK" {
        It "requires matching hashes, a debug signer, fake markers, and no real endpoint" {
            $hash = "A" * 64
            $manifest = 'android:debuggable(0x0101000f)=(type 0x12)0xffffffff android:appComponentFactory Ticket19DebugAppComponentFactory SyntheticFixtureProvider neth.iecal.curbox.debug.synthetic.fixture android.permission.DUMP'
            $dex = 'LearningComposition$createApi$1 SyntheticFixtureProvider neth.iecal.curbox.apitester'
            $signer = '2429a67995d6e0a03312ff8f67d7df53c40dbdeb86e454d62f87d8e8243e4c6a'
            (Test-ApprovedLearningApk $hash $hash $hash $signer $manifest $dex) | Should Be $true
            (Test-ApprovedLearningApk $hash $hash ("0" * 64) $signer $manifest $dex) | Should Be $false
            (Test-ApprovedLearningApk $hash $hash $hash $signer $manifest ($dex + ' https://api.todomath.com')) | Should Be $false
            (Test-ApprovedLearningApk $hash $hash $hash $signer $manifest 'real composition') | Should Be $false
            (Test-ApprovedLearningApk $hash $hash $hash ("0" * 64) $manifest $dex) | Should Be $false
        }
    }

    Context "Learning APK inspection" {
        It "fails before device access if the artifact does not exist" {
            $failed = $false
            try { Assert-LearningFakeApk -ApkPath 'missing.apk' -ExpectedHash ('A' * 64) -BuildToolsDirectory 'missing' } catch { $failed = $true }
            $failed | Should Be $true
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Learning notifications" {
        It "recognizes Android 16 silent baseline output" {
            $record = "    NotificationRecord(x: sound=null defaults=0 flags=ONGOING_EVENT|SILENT color=0x00000000 vis=PRIVATE))`n                android.title=String (Child1)`n                android.text=String (수학 2/5)"
            (Test-SilentLearningBaseline $record) | Should Be $true
        }
        It "recognizes the legacy silent group baseline" {
            $record = "    NotificationRecord(x: sound=null defaults=0x0 flags=0x2 color=0x00000000 groupKey=silent vis=PRIVATE))`n                android.title=String (Child1)`n                android.text=String (수학 2/5)"
            (Test-SilentLearningBaseline $record) | Should Be $true
            (Test-SilentLearningBaseline $record.Replace('sound=null', 'sound=content://sound')) | Should Be $false
        }
        It "rejects sound, another child, another progress, and missing silent markers" {
            $record = "    NotificationRecord(x: sound=null defaults=0 flags=ONGOING_EVENT|SILENT color=0x00000000 vis=PRIVATE))`n                android.title=String (Child1)`n                android.text=String (수학 2/5)"
            foreach ($other in @($record.Replace('sound=null', 'sound=content://sound'), $record.Replace('Child1', 'Child2'), $record.Replace('2/5', '3/5'), $record.Replace('|SILENT', ''), $record.Replace('|SILENT', '|NOT_SILENT'))) {
                (Test-SilentLearningBaseline $other) | Should Be $false
            }
        }
        It "returns only active child notification records" {
            $dump = "  Notification List:`n    NotificationRecord(x: pkg=neth.iecal.curbox.debug user=UserHandle{0} id=1279611218 tag=learning:abc importance=2`n title=Child1 Items: 2`n  mArchive=Child1 completed Math Items: 5`n"
            $script:DeviceTestShellOutputHandler = { param($command) $dump }
            $records = @(Get-LearningNotifications)
            $records.Count | Should Be 1
            $records[0].Tag | Should Be 'learning:abc'
            $records[0].Id | Should Be '1279611218'
            ($records[0].Text -match 'completed Math') | Should Be $false
        }
    }

    Context "Learning registration precondition" {
        It "counts current registrations and legacy child without inspecting account snapshots" {
            $script:DeviceTestShellOutputHandler = { param($command) '{"state":{"accountId":"fake"},"monitor":{"accountId":"fake","childName":null,"registrations":{"Child1":1},"accounts":{"other":{"registrations":{"Child2":2}}}}}' }
            (Get-LearningRegistrationCount) | Should Be 1
            $script:DeviceTestShellOutputHandler = { param($command) '{"state":{"accountId":"fake"},"monitor":{"accountId":"fake","childName":"Child1","registrations":{}}}' }
            (Get-LearningRegistrationCount) | Should Be 1
            $script:DeviceTestShellOutputHandler = { param($command) '{"state":{"accountId":"fake"},"monitor":{"accountId":"fake","childName":null,"registrations":{}}}' }
            (Get-LearningRegistrationCount) | Should Be 0
            $script:DeviceTestShellOutputHandler = { param($command) '__ABSENT__' }
            (Get-LearningRegistrationCount) | Should Be 0
        }
        It "returns only current registration names including a legacy child" {
            $script:DeviceTestShellOutputHandler = { param($command) '{"state":{"accountId":"fake"},"monitor":{"accountId":"fake","childName":null,"registrations":{"Child1":1},"accounts":{"other":{"registrations":{"Child2":2}}}}}' }
            (@(Get-LearningRegistrationNames) -join ',') | Should Be 'Child1'
            $script:DeviceTestShellOutputHandler = { param($command) '{"state":{"accountId":"fake"},"monitor":{"accountId":"fake","childName":"Child1","registrations":{}}}' }
            (@(Get-LearningRegistrationNames) -join ',') | Should Be 'Child1'
        }
        It "accepts the empty serializer default and omitted null account fields" {
            $script:DeviceTestShellOutputHandler = { param($command) '' }
            (Get-LearningRegistrationCount) | Should Be 0
            $script:DeviceTestShellOutputHandler = { param($command) '{"revision":0,"state":{"children":[],"reports":{}},"monitor":{"registrations":{}}}' }
            (Get-LearningRegistrationCount) | Should Be 0
        }
        It "fails closed on invalid data or mismatched accounts" {
            foreach ($value in @('STATE: ', '{', '{"state":{"children":[],"reports":{}}}', '{"state":{"accountId":"x"},"monitor":{"accountId":"y","registrations":{}}}')) {
                $script:DeviceTestShellOutputHandler = { param($command) $value }
                $failed = $false
                try { Get-LearningRegistrationCount | Out-Null } catch { $failed = $true }
                $failed | Should Be $true
            }
        }
    }

    Context "Learning fixture provider" {
        It "rejects failed provider writes and avoids shell injection" {
            $script:DeviceTestShellOutputHandler = { param($command) "Bundle[{ok=false}]" }
            $failed = $false
            try { Set-LearningFixture -Json "{}" } catch { $failed = $true }
            $failed | Should Be $true
            $failed = $false
            try { Set-LearningFixture -Json "'; rm -rf /; '" } catch { $failed = $true }
            $failed | Should Be $true
        }
        It "accepts a successful provider reset" {
            $script:DeviceTestShellOutputHandler = { param($command) "Bundle[{ok=true}]" }
            Reset-LearningFixture
        }
    }

    Context "Plugin award state" {
        It "reads only the Todo plugin payload" {
            $settings = [pscustomobject]@{ pluginTimes = [pscustomobject]@{ todo = [pscustomobject]@{ data = '{"connection":{"childName":"Child1"},"ledger":[],"pending":[]}' }; other = [pscustomobject]@{ data = '{}' } }; todoAwardConnection = @{}; todoAwardConnectionRevision = 1; todoAwardLedger = @(); todoPendingSettlements = @(); otherSetting = 'kept' }
            (Get-TodoAwardState $settings).connection.childName | Should Be 'Child1'
            Clear-TodoAwardState $settings
            $settings.pluginTimes.other.data | Should Be '{}'
            $settings.pluginTimes.PSObject.Properties['todo'] | Should BeNullOrEmpty
            foreach ($key in @('todoAwardConnection', 'todoAwardConnectionRevision', 'todoAwardLedger', 'todoPendingSettlements')) { $settings.PSObject.Properties[$key] | Should BeNullOrEmpty }
            $settings.otherSetting | Should Be 'kept'
        }
        It "clears legacy Todo fields when plugin state is absent" {
            $settings = [pscustomobject]@{ todoAwardConnection = @{ childName = 'Child1' }; todoAwardConnectionRevision = 1; todoAwardLedger = @(); todoPendingSettlements = @(); otherSetting = 'kept' }
            Clear-TodoAwardState $settings
            foreach ($key in @('todoAwardConnection', 'todoAwardConnectionRevision', 'todoAwardLedger', 'todoPendingSettlements')) { $settings.PSObject.Properties[$key] | Should BeNullOrEmpty }
            $settings.otherSetting | Should Be 'kept'
        }
        It "fails closed for invalid payloads" {
            $settings = [pscustomobject]@{ pluginTimes = [pscustomobject]@{ todo = [pscustomobject]@{ data = '{' } } }
            $failed = $false; try { Get-TodoAwardState $settings } catch { $failed = $true }
            $failed | Should Be $true
        }
    }

    Context "Todo settlement failure count" {
        It "returns only a validated numeric count" {
            $script:DeviceTestShellOutputHandler = { param($command) '3' }
            Get-TodoSettlementFailureCount | Should Be 3
            $script:DeviceTestShellOutputHandler = { param($command) 'invalid' }
            $failed = $false; try { Get-TodoSettlementFailureCount } catch { $failed = $true }; $failed | Should Be $true
        }
    }
    Context "Private fake learning backup" {
        It "backs up only the two fake files inside the app and restores absent files safely" {
            $script:fakeCommands = @()
            $script:DeviceTestShellHandler = { param($command) $script:fakeCommands += $command }
            $id = Backup-FakeLearningFiles
            $id | Should Match '^[a-f0-9]{32}$'
            Restore-FakeLearningFiles $id
            $script:fakeCommands.Count | Should Be 2
            $script:fakeCommands[0] | Should Match 'umask 077'
            $script:fakeCommands[0] | Should Match 'learning-fake-state.json learning-fake-session.json'
            $script:fakeCommands[1] | Should Match 'test -d'
            $script:fakeCommands[1] | Should Match 'mv -f'
            $script:fakeCommands[1] | Should Match 'else rm -f'
        }
        It "rejects a bad backup identifier without a shell command" {
            $script:DeviceTestShellHandler = { param($command) throw 'unexpected shell command' }
            $failed = $false; try { Restore-FakeLearningFiles '../other' } catch { $failed = $true }; $failed | Should Be $true
        }
    }
}

} finally {
    $env:PATH = $script:OfflineAdbOriginalPath
    if ($script:OfflineAdbHadScriptFunction) {
        Set-Item -Path Function:\adb -Value $script:OfflineAdbScriptFunction -Force
    } else {
        Remove-Item -Path Function:\adb -ErrorAction SilentlyContinue
    }
    if ($script:OfflineAdbHadGlobalFunction) {
        Set-Item -Path Function:\global:adb -Value $script:OfflineAdbGlobalFunction -Force
    } else {
        Remove-Item -Path Function:\global:adb -ErrorAction SilentlyContinue
    }
    if ($script:OfflineAdbHadGlobalCounter) {
        $global:DeviceTestUnexpectedAdbCalls = $script:OfflineAdbGlobalCounter
    } else {
        Remove-Variable -Name DeviceTestUnexpectedAdbCalls -Scope Global -ErrorAction SilentlyContinue
    }
}
