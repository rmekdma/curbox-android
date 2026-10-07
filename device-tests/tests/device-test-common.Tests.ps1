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
Describe "Device Test Common Helpers" {
    BeforeEach {
        if (Test-Path "$PSScriptRoot/../lib/device-test-common.ps1") {
            . "$PSScriptRoot/../lib/device-test-common.ps1"
        }
        $global:DeviceTestUnexpectedAdbCalls = 0
        $ErrorActionPreference = "Continue"
    }
    AfterEach {
        if ($global:DeviceTestUnexpectedAdbCalls -gt 0) {
            throw "A helper attempted adb during the offline Pester suite."
        }
    }

    Context "New-GuardianPinAuthConfig" {
        It "generates valid salt, verifier, iterations, and algorithm" {
            $auth = New-GuardianPinAuthConfig -Pin "1234" -Iterations 120000
            $auth.kdfAlgorithm | Should Be "PBKDF2WithHmacSHA256"
            $auth.kdfIterations | Should Be 120000
            $auth.passwordSalt | Should Not BeNullOrEmpty
            $auth.passwordVerifier | Should Not BeNullOrEmpty

            $saltBytes = [Convert]::FromBase64String($auth.passwordSalt)
            $saltBytes.Length | Should Be 16

            $verifierBytes = [Convert]::FromBase64String($auth.passwordVerifier)
            $verifierBytes.Length | Should Be 32
        }

        It "derives expected verifier for known test vector" {
            # Test vector with fixed salt
            $knownSaltBase64 = "AAAAAAAAAAAAAAAAAAAAAA=="
            $saltBytes = [Convert]::FromBase64String($knownSaltBase64)
            $auth = New-GuardianPinAuthConfig -Pin "testPassword" -SaltBytes $saltBytes -Iterations 120000
            $auth.passwordSalt | Should Be $knownSaltBase64
            $auth.passwordVerifier | Should Be "Vd4Oa0aOG4PYq/SdAgBMY1k3uUUooXgp1hRcz81uijs="
        }
    }

    Context "Offline adb guard" {
        It "blocks any unexpected device command in the Pester process" {
            $adbFunction = Get-Command adb -CommandType Function -ErrorAction SilentlyContinue
            $adbFunction | Should Not BeNullOrEmpty
            $adbFunction.ScriptBlock.ToString() | Should Match "Unexpected adb invocation in offline Pester tests"
            (Get-Command adb.exe -ErrorAction SilentlyContinue) | Should Be $null
        }
    }

    Context "Test-GuardianAuthConfig" {
        It "returns true for configured auth config" {
            $auth = New-GuardianPinAuthConfig -Pin "1234"
            (Test-GuardianAuthConfig -SettingsOrAuth $auth) | Should Be $true
        }

        It "returns true for settings object containing configured guardianAuthConfig" {
            $auth = New-GuardianPinAuthConfig -Pin "1234"
            $settings = [PSCustomObject]@{
                guardianAuthConfig = $auth
            }
            (Test-GuardianAuthConfig -SettingsOrAuth $settings) | Should Be $true
        }

        It "returns false for empty or unconfigured auth config" {
            $emptyAuth = New-GuardianPinAuthConfig -Pin ""
            (Test-GuardianAuthConfig -SettingsOrAuth $emptyAuth) | Should Be $false
        }

        It "returns false for null" {
            (Test-GuardianAuthConfig -SettingsOrAuth $null) | Should Be $false
        }
    }

    Context "Get-NodeBounds" {
        It "parses node bounds correctly from xml" {
            $xml = '<node text="Confirm" bounds="[100,200][300,400]"/>'
            $bounds = Get-NodeBounds -Xml $xml -Pattern 'text="Confirm"'
            $bounds.Found | Should Be $true
            $bounds.X | Should Be 200
            $bounds.Y | Should Be 300
            $bounds.X1 | Should Be 100
            $bounds.Y1 | Should Be 200
            $bounds.X2 | Should Be 300
            $bounds.Y2 | Should Be 400
        }

        It "returns Found = false when pattern does not match" {
            $xml = '<node text="Cancel" bounds="[100,200][300,400]"/>'
            $bounds = Get-NodeBounds -Xml $xml -Pattern 'text="NonExistent"'
            $bounds.Found | Should Be $false
        }
    }

    Context "Tap-TestRulePicker" {
        It "taps the exposed dropdown end icon instead of the field center" {
            $script:tapCommands = @()
            $script:DeviceTestShellHandler = { param($command) $script:tapCommands += $command }
            $xml = '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/rule_picker" bounds="[100,200][900,280]" /></hierarchy>'

            (Tap-TestRulePicker -Xml $xml) | Should Be $true
            $script:tapCommands.Count | Should Be 1
            $script:tapCommands[0] | Should Be "input tap 852 240"
        }

        It "returns false when the picker is not present" {
            $script:DeviceTestShellHandler = { param($command) throw "Unexpected shell command: $command" }
            (Tap-TestRulePicker -Xml '<hierarchy />') | Should Be $false
        }
    }

    Context "Rule picker popup automation" {
        BeforeEach {
            $script:pickerWindowDump = @'
Window #8 Window{a8b1ec1 u0 PopupWindow:9e367db}:
  mParentWindow=Window{af23a46 u0 neth.iecal.curbox.debug/neth.iecal.curbox.ui.activity.FragmentActivity}
  mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false
  Frames: parent=[120,318][1079,1548] display=[-100000,-100000][100000,100000] frame=[228,844][971,1204] last=[228,844][971,1204] insetsChanged=false
  isVisible=true
Window #9 Window{af23a46 u0 neth.iecal.curbox.debug/neth.iecal.curbox.ui.activity.FragmentActivity}:
mCurrentFocus=Window{af23a46 u0 neth.iecal.curbox.debug/neth.iecal.curbox.ui.activity.FragmentActivity}
'@
        }

        It "reads the selected label from the picker field" {
            $xml = '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/rule_picker" text="UsageRule" /></hierarchy>'
            (Get-TestRulePickerLabel -Xml $xml) | Should Be "UsageRule"
        }

        It "uses a visible Curbox popup window frame rather than guessed screen coordinates" {
            $bounds = Get-TestRulePickerPopupBounds -WindowDump $script:pickerWindowDump
            $bounds.Found | Should Be $true
            $bounds.X1 | Should Be 228
            $bounds.Y1 | Should Be 844
            $bounds.X2 | Should Be 971
            $bounds.Y2 | Should Be 1204

            $point = Get-TestRulePickerRowTapPoint -PopupBounds $bounds -CandidateIndex 1 -CandidateCount 3
            $point.Found | Should Be $true
            $point.X | Should Be 599
            $point.Y | Should Be 1024
        }

        It "requires a visible Curbox popup while accepting the windows-only dump without focus metadata" {
            $hiddenDump = $script:pickerWindowDump -replace 'isVisible=true', 'isVisible=false'
            (Get-TestRulePickerPopupBounds -WindowDump $hiddenDump).Found | Should Be $false
            $unrelatedDump = $script:pickerWindowDump -replace 'mParentWindow=.*neth.iecal.curbox.debug/', 'mParentWindow=Window{af23a46 u0 com.example.other/SomeActivity}'
            (Get-TestRulePickerPopupBounds -WindowDump $unrelatedDump).Found | Should Be $false
            $windowsOnlyDump = $script:pickerWindowDump -replace '(?m)^mCurrentFocus=.*$', ''
            (Get-TestRulePickerPopupBounds -WindowDump $windowsOnlyDump).Found | Should Be $true
            (Get-TestRulePickerPopupBounds -WindowDump 'Window #1 Window{PopupWindow:test}').Found | Should Be $false
        }

        It "taps a row from the current popup geometry and row count" {
            $script:DeviceTestShellOutputHandler = { param($command) $script:pickerWindowDump }
            $script:pickerTapCommands = @()
            $script:DeviceTestShellHandler = { param($command) $script:pickerTapCommands += $command }

            (Tap-TestRulePickerRow -CandidateIndex 1 -CandidateCount 3) | Should Be $true
            $script:pickerTapCommands.Count | Should Be 1
            $script:pickerTapCommands[0] | Should Be "input tap 599 1024"
        }

        It "discovers picker candidates by selecting each popup row" {
            $script:DeviceTestShellOutputHandler = { param($command) $script:pickerWindowDump }
            $script:pickerTapCommands = @()
            $script:DeviceTestShellHandler = { param($command) $script:pickerTapCommands += $command }
            $script:pickerUiResults = @(
                '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/rule_picker" text="BlockingRule" bounds="[228,713][971,842]" /></hierarchy>',
                '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/rule_picker" text="UsageRule" bounds="[228,713][971,842]" /></hierarchy>',
                '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/rule_picker" text="FutureRule" bounds="[228,713][971,842]" /></hierarchy>'
            )
            $script:pickerUiIndex = 0
            $script:DeviceTestUiWaitHandler = {
                param($pattern, $timeout)
                $result = $script:pickerUiResults[$script:pickerUiIndex]
                $script:pickerUiIndex++
                return $result
            }
            $initialUi = '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/rule_picker" text="BlockingRule" bounds="[228,713][971,842]" /></hierarchy>'

            $result = Get-TestRulePickerOptions -CurrentUi $initialUi -CandidateCount 3

            $result.Success | Should Be $true
            $result.Options.Count | Should Be 3
            (@($result.Options | ForEach-Object { $_.Label }) -join ',') | Should Be "BlockingRule,UsageRule,FutureRule"
            $script:pickerUiIndex | Should Be 3
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }

        It "selects the requested mapped row only when the field confirms the selection" {
            $script:DeviceTestShellOutputHandler = { param($command) $script:pickerWindowDump }
            $script:pickerTapCommands = @()
            $script:DeviceTestShellHandler = { param($command) $script:pickerTapCommands += $command }
            $script:DeviceTestUiWaitHandler = { param($pattern, $timeout) '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/rule_picker" text="UsageRule" bounds="[228,713][971,842]" /></hierarchy>' }
            $options = @(
                [PSCustomObject]@{ Index = 0; Label = "BlockingRule" },
                [PSCustomObject]@{ Index = 1; Label = "UsageRule" },
                [PSCustomObject]@{ Index = 2; Label = "FutureRule" }
            )
            $initialUi = '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/rule_picker" text="BlockingRule" bounds="[228,713][971,842]" /></hierarchy>'

            $result = Select-TestRulePickerOption -CurrentUi $initialUi -CandidateOptions $options -RuleName "UsageRule"

            $result.Success | Should Be $true
            $result.Label | Should Be "UsageRule"
            ($script:pickerTapCommands -contains "input tap 599 1024") | Should Be $true
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }

        It "fails closed if the field does not confirm the requested rule" {
            $script:DeviceTestShellOutputHandler = { param($command) $script:pickerWindowDump }
            $script:DeviceTestShellHandler = { param($command) }
            $script:DeviceTestUiWaitHandler = { param($pattern, $timeout) '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/rule_picker" text="BlockingRule" bounds="[228,713][971,842]" /></hierarchy>' }
            $options = @([PSCustomObject]@{ Index = 1; Label = "UsageRule" })
            $initialUi = '<hierarchy><node resource-id="neth.iecal.curbox.debug:id/rule_picker" text="BlockingRule" bounds="[228,713][971,842]" /></hierarchy>'

            $result = Select-TestRulePickerOption -CurrentUi $initialUi -CandidateOptions $options -RuleName "UsageRule"

            $result.Success | Should Be $false
            $result.Label | Should Be "BlockingRule"
        }
    }

    Context "New-ContributorAppRuleConfig" {
        It "builds a valid AppRuleSnapshot structure with target and contributor groups" {
            $snapshot = New-ContributorAppRuleConfig -TargetPackage "com.test.target" -ContributorPackage "com.test.contrib" -RequiredMinutes 1
            $snapshot | Should Not BeNullOrEmpty
            $snapshot.appGroups.Count | Should Be 2
            $snapshot.appRules.Count | Should Be 1

            $targetGroup = $snapshot.appGroups | Where-Object { $_.selectedPackages -contains "com.test.target" }
            $contribGroup = $snapshot.appGroups | Where-Object { $_.selectedPackages -contains "com.test.contrib" }
            $targetGroup | Should Not BeNullOrEmpty
            $contribGroup | Should Not BeNullOrEmpty

            $rule = $snapshot.appRules[0]
            $rule.isActive | Should Be $true
            $rule.usageConditionEnabled | Should Be $true
            ($rule.contributorGroupIds -contains $contribGroup.id) | Should Be $true
            $rule.contributorGroupConditionMinutes.($contribGroup.id) | Should Be 1
            $rule.allowedMinutes | Should Be 1440
            $rule.guardianExtraTimeAllowed | Should Be $true
        }
    }

    Context "New-GuardianExtraTimePickerAppRuleConfig" {
        It "keeps the disallowed blocker out and includes an unrelated rule before its time range starts" {
            $futureWindowStartMinute = 720
            $snapshot = New-GuardianExtraTimePickerAppRuleConfig `
                -TargetPackage "com.test.target" `
                -OtherPackage "com.test.other" `
                -FutureWindowStartMinute $futureWindowStartMinute
            $snapshot.appRules.Count | Should Be 4

            $nightRule = $snapshot.appRules | Where-Object { $_.id -eq "night-rule" }
            $blockingRule = $snapshot.appRules | Where-Object { $_.id -eq "blocking-rule" }
            $usageRule = $snapshot.appRules | Where-Object { $_.id -eq "usage-rule" }
            $futureRule = $snapshot.appRules | Where-Object { $_.id -eq "future-rule" }

            $nightRule.guardianExtraTimeAllowed | Should Be $false
            $blockingRule.guardianExtraTimeAllowed | Should Be $true
            $blockingRule.allowedMinutes | Should Be 0
            $usageRule.guardianExtraTimeAllowed | Should Be $true
            $futureRule.guardianExtraTimeAllowed | Should Be $true
            $futureRule.isActive | Should Be $true
            $futureRule.timeRanges[0].startMinute | Should Be $futureWindowStartMinute
            $futureRule.timeRanges[0].endMinute | Should Be 750
            ($snapshot.appGroups[0].selectedPackages -contains "com.test.target") | Should Be $true
            ($snapshot.appGroups[1].selectedPackages -contains "com.test.other") | Should Be $true
            $usageRule.allowedMinutes | Should Be 1440
            $futureRule.allowedMinutes | Should Be 1440
        }
    }

    Context "Get-TestAppLaunchCommand" {
        It "uses the package launcher when no activity is supplied" {
            (Get-TestAppLaunchCommand -PackageName "com.android.chrome") | Should Be "monkey -p com.android.chrome 1"
        }

        It "expands a relative activity name against the package" {
            (Get-TestAppLaunchCommand -PackageName "com.test.app" -ActivityName ".MainActivity") | Should Be "am start -n com.test.app/com.test.app.MainActivity"
        }

        It "passes explicit extras when launching a routed activity" {
            (Get-TestAppLaunchCommand -PackageName "neth.iecal.curbox.debug" -ActivityName "neth.iecal.curbox.ui.activity.FragmentActivity" -IntentArguments "--es fragment app_rule_groups") | Should Be "am start -n neth.iecal.curbox.debug/neth.iecal.curbox.ui.activity.FragmentActivity --es fragment app_rule_groups"
        }
    }

    Context "Start-TestApp" {
        It "uses the shared shell helper to launch the requested package" {
            $script:lastShellCommand = ""
            $script:DeviceTestShellHandler = { param($Command) $script:lastShellCommand = $Command }

            Start-TestApp -PackageName "com.android.chrome"

            $script:lastShellCommand | Should Be "monkey -p com.android.chrome 1"
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }

        It "passes extras to an explicit activity through the shared shell helper" {
            $script:lastShellCommand = ""
            $script:DeviceTestShellHandler = { param($Command) $script:lastShellCommand = $Command }

            Start-TestApp -PackageName "neth.iecal.curbox.debug" -ActivityName "neth.iecal.curbox.ui.activity.FragmentActivity" -IntentArguments "--es fragment app_rule_groups"

            $script:lastShellCommand | Should Be "am start -n neth.iecal.curbox.debug/neth.iecal.curbox.ui.activity.FragmentActivity --es fragment app_rule_groups"
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Get-TestDeviceShellOutput" {
        It "returns mocked shell output without invoking adb" {
            $script:DeviceTestShellOutputHandler = { param($Command) return "screen state is OFF" }

            (Get-TestDeviceShellOutput -Command "dumpsys display") | Should Be "screen state is OFF"
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Get-DeviceAccessibilityRestoreCommands" {
        It "restores the captured secure settings exactly" {
            $state = [PSCustomObject]@{
                EnabledServices = "com.test/.Service:neth.iecal.curbox.debug/.services.AppBlockerService"
                AccessibilityEnabled = "1"
            }
            $commands = Get-DeviceAccessibilityRestoreCommands -AccessibilitySettings $state
            ($commands -contains "settings put secure enabled_accessibility_services com.test/.Service:neth.iecal.curbox.debug/.services.AppBlockerService") | Should Be $true
            ($commands -contains "settings put secure accessibility_enabled 1") | Should Be $true
        }

        It "deletes secure keys that were unset before the test" {
            $state = [PSCustomObject]@{
                EnabledServices = "null"
                AccessibilityEnabled = ""
            }
            $commands = Get-DeviceAccessibilityRestoreCommands -AccessibilitySettings $state
            ($commands -contains "settings delete secure enabled_accessibility_services") | Should Be $true
            ($commands -contains "settings delete secure accessibility_enabled") | Should Be $true
        }
    }

    Context "Backup-DeviceAccessibilitySettings" {
        It "captures both secure accessibility settings" {
            $script:DeviceTestSecureSettingReader = {
                param($Name)
                if ($Name -eq "enabled_accessibility_services") { return "com.test/.Service" }
                if ($Name -eq "accessibility_enabled") { return "1" }
            }

            $state = Backup-DeviceAccessibilitySettings

            $state.EnabledServices | Should Be "com.test/.Service"
            $state.AccessibilityEnabled | Should Be "1"
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Restore-DeviceAccessibilitySettings" {
        It "applies the exact commands produced from the saved accessibility state" {
            $state = [PSCustomObject]@{
                EnabledServices = "com.test/.Service"
                AccessibilityEnabled = "1"
            }
            $script:accessibilityCommands = @()
            $script:DeviceTestShellHandler = { param($Command) $script:accessibilityCommands += $Command }

            Restore-DeviceAccessibilitySettings -AccessibilitySettings $state

            ($script:accessibilityCommands -contains "settings put secure enabled_accessibility_services com.test/.Service") | Should Be $true
            ($script:accessibilityCommands -contains "settings put secure accessibility_enabled 1") | Should Be $true
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Complete-DeviceTest" {
        It "does not clear grants or skips when no current-run settings backup exists" {
            $script:cleanupCommands = @()
            $script:DeviceTestShellHandler = { param($Command) $script:cleanupCommands += $Command }

            Complete-DeviceTest -TargetPackages @("com.android.chrome")

            ($script:cleanupCommands -contains "am broadcast -a neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES -p neth.iecal.curbox.debug") | Should Be $false
            ($script:cleanupCommands -contains "am force-stop com.android.chrome") | Should Be $true
            ($script:cleanupCommands -contains "input keyevent 3") | Should Be $true
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Complete-DeviceTest backup identity" {
        It "does not restore or clear using a leftover backup file from another run" {
            $backupPath = Join-Path $TestDrive "settings_backup_leftover.json"
            [System.IO.File]::WriteAllText($backupPath, '{"old":"baseline"}')
            $script:DeviceTestSettingsBackupPath = $null
            $script:cleanupCommands = @()
            $script:DeviceTestShellHandler = { param($Command) $script:cleanupCommands += $Command }

            Complete-DeviceTest -BackupPath $backupPath

            ($script:cleanupCommands -match "CLEAR_TEST_APP_RULE_OVERRIDES").Count | Should Be 0
            ($script:cleanupCommands -match "refresh.app_rules").Count | Should Be 0
            ($script:cleanupCommands -contains "input keyevent 3") | Should Be $true
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Backup-DeviceSettings" {
        It "marks the current-run baseline only after settings have been saved" {
            $backupPath = Join-Path $TestDrive "settings_backup_current.json"
            $script:DeviceTestSettingsReader = { param($PackageName, $AsObject) return '{"baseline":true}' }

            $result = Backup-DeviceSettings -DestinationPath $backupPath

            $result | Should Be '{"baseline":true}'
            $script:DeviceTestSettingsBackupPath | Should Be ([System.IO.Path]::GetFullPath($backupPath))
            (Get-Content -LiteralPath $backupPath -Raw) | Should Be '{"baseline":true}'
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }

        It "does not leave an older backup in place when the current settings cannot be read" {
            $backupPath = Join-Path $TestDrive "settings_backup_stale.json"
            [System.IO.File]::WriteAllText($backupPath, '{"old":"baseline"}')
            $script:DeviceTestSettingsReader = { param($PackageName, $AsObject) return $null }

            $result = Backup-DeviceSettings -DestinationPath $backupPath

            $result | Should Be $null
            (Test-Path -LiteralPath $backupPath) | Should Be $false
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Restore-DeviceSettings" {
        It "restores the backup and refreshes rules without clearing existing grants or skips" {
            $backupPath = Join-Path $TestDrive "settings_backup.json"
            [System.IO.File]::WriteAllText($backupPath, "{}")
            $script:pushedFiles = @()
            $script:restoredCommands = @()
            $script:DeviceTestFilePushHandler = { param($LocalPath, $RemotePath) $script:pushedFiles += "$LocalPath=>$RemotePath" }
            $script:DeviceTestShellHandler = { param($Command) $script:restoredCommands += $Command }

            $result = Restore-DeviceSettings -BackupPath $backupPath

            $result | Should Be $true
            ($script:pushedFiles -contains "$backupPath=>/data/local/tmp/settings_backup.json") | Should Be $true
            ($script:restoredCommands -contains "am broadcast -a neth.iecal.curbox.refresh.app_rules -p neth.iecal.curbox.debug") | Should Be $true
            ($script:restoredCommands -contains "am broadcast -a neth.iecal.curbox.refresh.appblocker -p neth.iecal.curbox.debug") | Should Be $true
            ($script:restoredCommands -match "CLEAR_TEST_APP_RULE_OVERRIDES").Count | Should Be 0
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Complete-DeviceTest backup ordering" {
        It "clears the temporary override before restoring the saved baseline" {
            $backupPath = Join-Path $TestDrive "settings_backup_order.json"
            [System.IO.File]::WriteAllText($backupPath, "{}")
            $script:DeviceTestSettingsBackupPath = [System.IO.Path]::GetFullPath($backupPath)
            $script:cleanupOrder = @()
            $script:DeviceTestShellHandler = {
                param($Command)
                if ($Command -match "CLEAR_TEST_APP_RULE_OVERRIDES") { $script:cleanupOrder += "clear" }
            }
            $script:DeviceTestFilePushHandler = { param($LocalPath, $RemotePath) $script:cleanupOrder += "restore" }

            Complete-DeviceTest -BackupPath $backupPath

            $clearIndex = [array]::IndexOf($script:cleanupOrder, "clear")
            $restoreIndex = [array]::IndexOf($script:cleanupOrder, "restore")
            ($clearIndex -ge 0 -and $restoreIndex -gt $clearIndex) | Should Be $true
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "New-TestAppGroup" {
        It "builds a valid AppRuleAppGroup object with default membership history" {
            $group = New-TestAppGroup -GroupId "test-group" -GroupName "테스트 그룹" -Packages @("com.test.app")
            $group.id | Should Be "test-group"
            $group.name | Should Be "테스트 그룹"
            ($group.selectedPackages -contains "com.test.app") | Should Be $true
            $group.membershipHistory.Count | Should Be 1
            $group.membershipHistory[0].effectiveFromMs | Should Be ([long]::MinValue)
            ($group.membershipHistory[0].selectedPackages -contains "com.test.app") | Should Be $true
        }
    }

    Context "Inject-TestAppRules" {
        It "preserves existing guardian overrides when applying a changed snapshot" {
            $script:injectedRuleCommands = @()
            $script:DeviceTestShellHandler = {
                param($Command)
                $script:injectedRuleCommands += $Command
            }
            $script:DeviceTestTempStringPushHandler = {
                param($Content, $RemotePath)
                $script:injectedRuleCommands += "push:$RemotePath"
                $script:injectedRuleCommands += $Content
            }
            $snapshot = [PSCustomObject]@{ appGroups = @(); appRules = @() }

            Inject-TestAppRules -AppRuleSnapshot $snapshot -PreserveOverrides

            ($script:injectedRuleCommands -join "`n") | Should Not Match "CLEAR_TEST_APP_RULE_OVERRIDES"
            ($script:injectedRuleCommands -join "`n") | Should Match "APPLY_TEST_APP_RULES"
            ($script:injectedRuleCommands -join "`n") | Should Match "refresh.app_rules"
            ($script:injectedRuleCommands -join "`n") | Should Match "refresh.appblocker"
        }
    }

    Context "Get-DeviceTimeInfo" {
        It "parses date string correctly and calculates minutes and remaining seconds" {
            $info = Get-DeviceTimeInfo -DateString "14 30 15"
            $info.Hour | Should Be 14
            $info.Minute | Should Be 30
            $info.Second | Should Be 15
            $info.CurrentMinute | Should Be (14 * 60 + 30) # 870
            $info.NextMinute | Should Be 871
            $info.SecondsUntilNextMinute | Should Be 45
            $info.TimeString | Should Be "14:30:15"
        }

        It "handles midnight rollover edge case (23:59:40 -> next minute is 0)" {
            $info = Get-DeviceTimeInfo -DateString "23 59 40"
            $info.Hour | Should Be 23
            $info.Minute | Should Be 59
            $info.Second | Should Be 40
            $info.CurrentMinute | Should Be 1439
            $info.NextMinute | Should Be 0
            $info.SecondsUntilNextMinute | Should Be 20
            $info.TimeString | Should Be "23:59:40"
        }
    }

    Context "New-TimeRangeAppRuleConfig" {
        It "builds a valid AppRuleSnapshot structure with timeRanges" {
            $snapshot = New-TimeRangeAppRuleConfig -TargetPackage "com.test.target" -StartMinute 600 -EndMinute 660
            $snapshot | Should Not BeNullOrEmpty
            $snapshot.appGroups.Count | Should Be 1
            $snapshot.appRules.Count | Should Be 1

            $targetGroup = $snapshot.appGroups[0]
            ($targetGroup.selectedPackages -contains "com.test.target") | Should Be $true

            $rule = $snapshot.appRules[0]
            $rule.isActive | Should Be $true
            $rule.allowedMinutes | Should Be 0
            $rule.guardianExtraTimeAllowed | Should Be $true
            $rule.timeRanges.Count | Should Be 1
            $rule.timeRanges[0].startMinute | Should Be 600
            $rule.timeRanges[0].endMinute | Should Be 660
            ($rule.scope.includedGroupIds -contains $targetGroup.id) | Should Be $true
        }
    }

    Context "New-DailyLimitAppRuleConfig" {
        It "builds a valid AppRuleSnapshot structure with daily limit allowance" {
            $snapshot = New-DailyLimitAppRuleConfig -TargetPackage "com.test.target" -AllowedMinutes 1 -RuleName "일일 허용량 제한"
            $snapshot | Should Not BeNullOrEmpty
            $snapshot.appGroups.Count | Should Be 1
            $snapshot.appRules.Count | Should Be 1

            $targetGroup = $snapshot.appGroups[0]
            ($targetGroup.selectedPackages -contains "com.test.target") | Should Be $true

            $rule = $snapshot.appRules[0]
            $rule.isActive | Should Be $true
            $rule.allowedMinutes | Should Be 1
            $rule.name | Should Be "일일 허용량 제한"
            $rule.usageConditionEnabled | Should Be $false
            $rule.guardianExtraTimeAllowed | Should Be $true
            ($rule.scope.includedGroupIds -contains $targetGroup.id) | Should Be $true
        }
    }

    Context "Test-AppRuleSkip" {
        It "returns true when skips contains matching ruleId and valid skipUntilMs" {
            $overrideState = [PSCustomObject]@{
                skips = @(
                    [PSCustomObject]@{
                        ruleId = "test-rule-01"
                        useDayId = "2026-09-19"
                        skipUntilMs = [long]1770000000000
                        skipFromMs = [long]1760000000000
                    }
                )
            }
            (Test-AppRuleSkip -OverrideState $overrideState -RuleId "test-rule-01" -MinSkipUntilMs 1765000000000) | Should Be $true
        }

        It "returns false when skips is empty or does not contain ruleId" {
            $overrideState = [PSCustomObject]@{
                skips = @()
            }
            (Test-AppRuleSkip -OverrideState $overrideState -RuleId "test-rule-01") | Should Be $false

            $overrideState2 = [PSCustomObject]@{
                skips = @(
                    [PSCustomObject]@{
                        ruleId = "other-rule"
                        skipUntilMs = [long]1770000000000
                    }
                )
            }
            (Test-AppRuleSkip -OverrideState $overrideState2 -RuleId "test-rule-01") | Should Be $false
        }

        It "returns false when skipUntilMs is less than or equal to MinSkipUntilMs" {
            $overrideState = [PSCustomObject]@{
                skips = @(
                    [PSCustomObject]@{
                        ruleId = "test-rule-01"
                        skipUntilMs = [long]1760000000000
                    }
                )
            }
            (Test-AppRuleSkip -OverrideState $overrideState -RuleId "test-rule-01" -MinSkipUntilMs 1765000000000) | Should Be $false
        }
    }

    Context "Get-DeviceProcessPid" {
        It "extracts PID from ps -ef format output" {
            $psEf = @"
UID            PID  PPID C STIME TTY          TIME CMD
root             1     0 0 10:00 ?        00:00:02 init
u0_a1351      6271   964 0 11:03 ?        00:00:54 neth.iecal.curbox.debug
u0_a1351     13837   964 0 20:00 ?        00:03:11 neth.iecal.curbox.debug:app_blocker_service
shell        10788 10785 1 10:28 ?        00:00:00 ps -ef
"@
            $pidFound = Get-DeviceProcessPid -ProcessOutput $psEf -ProcessName ":app_blocker_service"
            $pidFound | Should Be 13837
        }

        It "extracts PID from standard ps format output" {
            $psStd = @"
USER           PID   PPID     VSZ    RSS WCHAN            ADDR S NAME
root             1      0   24480   3120 0                   0 S init
u0_a1351      6271    964 17063660 220392 do_epoll_wait      0 S neth.iecal.curbox.debug
u0_a1351     13837    964 16799000 116600 do_epoll_wait      0 S neth.iecal.curbox.debug:app_blocker_service
"@
            $pidFound = Get-DeviceProcessPid -ProcessOutput $psStd -ProcessName ":app_blocker_service"
            $pidFound | Should Be 13837
        }

        It "matches exact full package:process name" {
            $psEf = @"
UID            PID  PPID C STIME TTY          TIME CMD
u0_a1351     25432   964 0 20:00 ?        00:03:11 neth.iecal.curbox.debug:app_blocker_service
"@
            $pidFound = Get-DeviceProcessPid -ProcessOutput $psEf -ProcessName "neth.iecal.curbox.debug:app_blocker_service"
            $pidFound | Should Be 25432
        }

        It "returns null when process name is not found" {
            $psEf = @"
UID            PID  PPID C STIME TTY          TIME CMD
u0_a1351      6271   964 0 11:03 ?        00:00:54 neth.iecal.curbox.debug
"@
            $pidFound = Get-DeviceProcessPid -ProcessOutput $psEf -ProcessName ":app_blocker_service"
            $pidFound | Should Be $null
        }

        It "returns null for empty or whitespace output" {
            $pidFound = Get-DeviceProcessPid -ProcessOutput "" -ProcessName ":app_blocker_service"
            $pidFound | Should Be $null
        }
    }

    Context "Test-AccessibilityServiceBound" {
        It "returns true when service is listed in Bound services" {
            $dump = @"
  Bound services:{Service[label=Curbox App Blocker, feedbackType[FEEDBACK_GENERIC], capabilities=1, eventTypes=TYPES_ALL_MASK, notificationTimeout=0, requestA11yBtn=false]}
  Enabled services:{{neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService}}
  Binding services:{}
  Crashed services:{}
"@
            (Test-AccessibilityServiceBound -DumpsysOutput $dump -ServiceName "AppBlockerService") | Should Be $true
        }

        It "returns false when service is listed in Crashed services" {
            $dump = @"
  Bound services:{}
  Enabled services:{{neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService}}
  Binding services:{}
  Crashed services:{Service[label=Curbox App Blocker]}
"@
            (Test-AccessibilityServiceBound -DumpsysOutput $dump -ServiceName "AppBlockerService") | Should Be $false
        }

        It "returns false when Bound services does not contain the service" {
            $dump = @"
  Bound services:{}
  Enabled services:{{neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService}}
  Binding services:{}
  Crashed services:{}
"@
            (Test-AccessibilityServiceBound -DumpsysOutput $dump -ServiceName "AppBlockerService") | Should Be $false
        }

        It "returns false for null or empty dumpsys output" {
            (Test-AccessibilityServiceBound -DumpsysOutput "" -ServiceName "AppBlockerService") | Should Be $false
        }
    }

    Context "Enable-AccessibilityService" {
        It "adds Curbox without replacing other enabled services and enables accessibility" {
            $script:secureValues = @{
                enabled_accessibility_services = "com.example/.Reader:com.example/.Magnifier"
                accessibility_enabled = "0"
            }
            $script:accessibilityCommands = @()
            $script:DeviceTestSecureSettingReader = { param($Name) return $script:secureValues[$Name] }
            $script:DeviceTestShellHandler = { param($Command) $script:accessibilityCommands += $Command }

            (Enable-AccessibilityService) | Should Be $true

            ($script:accessibilityCommands -contains "settings put secure enabled_accessibility_services com.example/.Reader:com.example/.Magnifier:neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService") | Should Be $true
            ($script:accessibilityCommands -contains "settings put secure accessibility_enabled 1") | Should Be $true
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }

        It "does not rewrite accessibility settings when Curbox is already enabled" {
            $serviceName = "neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService"
            $script:secureValues = @{
                enabled_accessibility_services = "com.example/.Reader:$serviceName"
                accessibility_enabled = "1"
            }
            $script:accessibilityCommands = @()
            $script:DeviceTestSecureSettingReader = { param($Name) return $script:secureValues[$Name] }
            $script:DeviceTestShellHandler = { param($Command) $script:accessibilityCommands += $Command }

            (Enable-AccessibilityService) | Should Be $true

            $script:accessibilityCommands.Count | Should Be 0
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }

        It "treats an unset enabled-services value as empty" {
            $script:secureValues = @{
                enabled_accessibility_services = "null"
                accessibility_enabled = "0"
            }
            $script:accessibilityCommands = @()
            $script:DeviceTestSecureSettingReader = { param($Name) return $script:secureValues[$Name] }
            $script:DeviceTestShellHandler = { param($Command) $script:accessibilityCommands += $Command }

            (Enable-AccessibilityService) | Should Be $true

            ($script:accessibilityCommands -contains "settings put secure enabled_accessibility_services neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService") | Should Be $true
            ($script:accessibilityCommands -contains "settings put secure accessibility_enabled 1") | Should Be $true
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Stop-ServiceProcess" {
        It "returns false when target PID is 0 or negative" {
            (Stop-ServiceProcess -TargetPid 0) | Should Be $false
            (Stop-ServiceProcess -TargetPid -1) | Should Be $false
        }
    }

    Context "Restart-DeviceAccessibilityServiceIfEnabled" {
        It "rebinds the service only when it was enabled in the saved baseline" {
            $script:servicePidReads = 0
            $script:stoppedServicePid = 0
            Mock Get-DeviceProcessPid {
                $script:servicePidReads++
                if ($script:servicePidReads -eq 1) { return 123 }
                return 456
            }
            Mock Stop-ServiceProcess {
                $script:stoppedServicePid = $TargetPid
                return $true
            }
            Mock Test-AccessibilityServiceBound { return $true }
            $baseline = [PSCustomObject]@{
                EnabledServices = "com.example/.Reader:neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService"
                AccessibilityEnabled = "1"
            }

            $result = Restart-DeviceAccessibilityServiceIfEnabled -AccessibilitySettings $baseline -TimeoutSeconds 2

            $result.Skipped | Should Be $false
            $result.ProcessId | Should Be 456
            $script:stoppedServicePid | Should Be 123
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }

        It "skips without process commands when the saved baseline disabled accessibility" {
            $script:serviceProcessQueried = $false
            Mock Get-DeviceProcessPid { $script:serviceProcessQueried = $true; return 123 }
            Mock Stop-ServiceProcess { throw "Disabled baseline must not stop the service process." }
            $baseline = [PSCustomObject]@{
                EnabledServices = "com.example/.Reader:neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService"
                AccessibilityEnabled = "0"
            }

            $result = Restart-DeviceAccessibilityServiceIfEnabled -AccessibilitySettings $baseline -TimeoutSeconds 1

            $result.Skipped | Should Be $true
            $result.ProcessId | Should Be $null
            $script:serviceProcessQueried | Should Be $false
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "New-RolloverAppRuleConfig" {
        It "builds a valid AppRuleSnapshot structure with rolloverEnabled and unlockDays" {
            $snapshot = New-RolloverAppRuleConfig -TargetPackage "com.test.target" -UnlockDays @(0, 6) -AllowedMinutes 0
            $snapshot | Should Not BeNullOrEmpty
            $snapshot.appGroups.Count | Should Be 1
            $snapshot.appRules.Count | Should Be 1

            $rule = $snapshot.appRules[0]
            $rule.isActive | Should Be $true
            $rule.rolloverEnabled | Should Be $true
            $rule.guardianExtraTimeAllowed | Should Be $true
            ($rule.unlockDays -contains 0) | Should Be $true
            ($rule.unlockDays -contains 6) | Should Be $true
            $rule.allowedMinutes | Should Be 0
            ($rule.scope.includedGroupIds -contains $snapshot.appGroups[0].id) | Should Be $true
        }
    }

    Context "New-RuleRolloverPool" {
        It "creates a PSCustomObject with ruleId, accumulatedMinutes, and lastSettledUseDayId" {
            $pool = New-RuleRolloverPool -RuleId "test-rule-01" -AccumulatedMinutes 30 -LastSettledUseDayId "2026-09-19"
            $pool | Should Not BeNullOrEmpty
            $pool.ruleId | Should Be "test-rule-01"
            $pool.accumulatedMinutes | Should Be 30
            $pool.lastSettledUseDayId | Should Be "2026-09-19"
        }
    }

    Context "Test-AppRuleGuardianGrant" {
        It "returns true when matching grant is found in overrideState" {
            $overrideState = [PSCustomObject]@{
                grants = @(
                    [PSCustomObject]@{
                        ruleId = "test-rule-01"
                        useDayId = "2026-09-19"
                        grantedMillis = [long]1800000
                        grantedAtMs = [long]1760000000000
                        isFromAccumulatedPool = $true
                    }
                )
            }
            (Test-AppRuleGuardianGrant -OverrideState $overrideState -RuleId "test-rule-01" -ExpectedGrantedMillis 1800000 -IsFromAccumulatedPool $true -ExpectedUseDayId "2026-09-19") | Should Be $true
        }

        It "returns false when expected useDayId does not match" {
            $overrideState = [PSCustomObject]@{
                grants = @(
                    [PSCustomObject]@{
                        ruleId = "test-rule-01"
                        useDayId = "2026-09-18"
                        grantedMillis = [long]1800000
                        grantedAtMs = [long]1760000000000
                        isFromAccumulatedPool = $true
                    }
                )
            }
            (Test-AppRuleGuardianGrant -OverrideState $overrideState -RuleId "test-rule-01" -ExpectedGrantedMillis 1800000 -IsFromAccumulatedPool $true -ExpectedUseDayId "2026-09-19") | Should Be $false
        }

        It "returns false when isFromAccumulatedPool does not match" {
            $overrideState = [PSCustomObject]@{
                grants = @(
                    [PSCustomObject]@{
                        ruleId = "test-rule-01"
                        useDayId = "2026-09-19"
                        grantedMillis = [long]1800000
                        grantedAtMs = [long]1760000000000
                        isFromAccumulatedPool = $false
                    }
                )
            }
            (Test-AppRuleGuardianGrant -OverrideState $overrideState -RuleId "test-rule-01" -ExpectedGrantedMillis 1800000 -IsFromAccumulatedPool $true) | Should Be $false
        }

        It "returns false when ruleId does not match or grants is empty" {
            $overrideState = [PSCustomObject]@{ grants = @() }
            (Test-AppRuleGuardianGrant -OverrideState $overrideState -RuleId "test-rule-01") | Should Be $false
        }
    }

    Context "Get-RuleRolloverPool" {
        It "extracts pool by ruleId from settings object" {
            $poolObj = [PSCustomObject]@{
                ruleId = "test-rule-01"
                accumulatedMinutes = [long]25
                lastSettledUseDayId = "2026-09-19"
            }
            $settings = [PSCustomObject]@{
                appRuleRolloverState = [PSCustomObject]@{
                    pools = [PSCustomObject]@{
                        "test-rule-01" = $poolObj
                    }
                }
            }
            $result = Get-RuleRolloverPool -SettingsOrRolloverState $settings -RuleId "test-rule-01"
            $result | Should Not BeNullOrEmpty
            $result.accumulatedMinutes | Should Be 25
        }

        It "returns null when pool does not exist" {
            $settings = [PSCustomObject]@{
                appRuleRolloverState = [PSCustomObject]@{
                    pools = [PSCustomObject]@{}
                }
            }
            $result = Get-RuleRolloverPool -SettingsOrRolloverState $settings -RuleId "non-existent"
            $result | Should Be $null
        }
    }

    Context "Submit-GuardianPin" {
        It "returns false when PIN dialog is not found in UI" {
            $script:DeviceTestUiWaitHandler = { param($Pattern, $TimeoutSeconds) return "<empty/>" }
            $res = Submit-GuardianPin -PinValue "1234"
            $res | Should Be $false
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Set-DeviceRolloverState" {
        It "returns false when Get-DeviceSettings returns null" {
            $script:DeviceTestSettingsReader = { param($PackageName, $AsObject) return $null }
            $res = Set-DeviceRolloverState -RolloverState ([PSCustomObject]@{})
            $res | Should Be $false
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Set-DeviceUseDayResetTime" {
        It "rejects an invalid clock time before reading settings or writing to the device" {
            $script:settingsReadCount = 0
            $script:DeviceTestSettingsReader = { param($PackageName, $AsObject) $script:settingsReadCount++; return [PSCustomObject]@{} }

            $result = Set-DeviceUseDayResetTime -Hour 24 -Minute 0

            $result | Should Be $false
            $script:settingsReadCount | Should Be 0
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }

        It "returns false when settings cannot be read" {
            $script:DeviceTestSettingsReader = { param($PackageName, $AsObject) return $null }

            $result = Set-DeviceUseDayResetTime -Hour 4 -Minute 30

            $result | Should Be $false
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }

        It "updates reset fields while preserving other settings and refreshing both rule consumers" {
            $existingSettings = [PSCustomObject]@{
                useDayResetHour = 4
                useDayResetMinute = 0
                guardianAuthConfig = [PSCustomObject]@{ passwordVerifier = "existing" }
            }
            $script:settingsJson = ""
            $script:settingsCommands = @()
            $script:DeviceTestSettingsReader = { param($PackageName, $AsObject) return $existingSettings }
            $script:DeviceTestTempStringPushHandler = { param($Content, $RemotePath) $script:settingsJson = $Content }
            $script:DeviceTestShellHandler = { param($Command) $script:settingsCommands += $Command }

            $result = Set-DeviceUseDayResetTime -Hour 1 -Minute 15

            $result | Should Be $true
            $written = $script:settingsJson | ConvertFrom-Json
            $written.useDayResetHour | Should Be 1
            $written.useDayResetMinute | Should Be 15
            $written.guardianAuthConfig.passwordVerifier | Should Be "existing"
            ($script:settingsCommands -contains "run-as neth.iecal.curbox.debug chmod 660 files/datastore/settings.json") | Should Be $true
            ($script:settingsCommands -contains "am broadcast -a neth.iecal.curbox.refresh.app_rules -p neth.iecal.curbox.debug") | Should Be $true
            ($script:settingsCommands -contains "am broadcast -a neth.iecal.curbox.refresh.appblocker -p neth.iecal.curbox.debug") | Should Be $true
            ($script:settingsCommands -contains "rm -f /data/local/tmp/settings_use_day_reset.json") | Should Be $true
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }
    }

    Context "Set-DeviceAppRuleSnapshot" {
        It "does not write when settings cannot be read" {
            $script:DeviceTestSettingsReader = { param($PackageName, $AsObject) return $null }

            $result = Set-DeviceAppRuleSnapshot -AppRuleSnapshot ([PSCustomObject]@{ appGroups = @(); appRules = @() })

            $result | Should Be $false
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
        }

        It "preserves other settings while persisting and refreshing the app rules" {
            $existingSettings = [PSCustomObject]@{
                guardianAuthConfig = [PSCustomObject]@{ passwordVerifier = "existing" }
                appRuleSnapshot = [PSCustomObject]@{ appGroups = @(); appRules = @() }
            }
            $newSnapshot = [PSCustomObject]@{
                appGroups = @()
                appRules = @([PSCustomObject]@{ id = "new-rule"; guardianExtraTimeAllowed = $true })
            }
            $script:settingsJson = ""
            $script:settingsCommands = @()
            $script:DeviceTestSettingsReader = { param($PackageName, $AsObject) return $existingSettings }
            $script:DeviceTestTempStringPushHandler = { param($Content, $RemotePath) $script:settingsJson = $Content }
            $script:DeviceTestShellHandler = { param($Command) $script:settingsCommands += $Command }

            $result = Set-DeviceAppRuleSnapshot -AppRuleSnapshot $newSnapshot

            $result | Should Be $true
            $written = $script:settingsJson | ConvertFrom-Json
            $written.guardianAuthConfig.passwordVerifier | Should Be "existing"
            $written.appRuleSnapshot.appRules[0].id | Should Be "new-rule"
            ($script:settingsCommands -contains "run-as neth.iecal.curbox.debug chmod 660 files/datastore/settings.json") | Should Be $true
            ($script:settingsCommands -contains "am broadcast -a neth.iecal.curbox.refresh.app_rules -p neth.iecal.curbox.debug") | Should Be $true
            ($script:settingsCommands -contains "am broadcast -a neth.iecal.curbox.refresh.appblocker -p neth.iecal.curbox.debug") | Should Be $true
            ($script:settingsCommands -contains "rm -f /data/local/tmp/settings_app_rules.json") | Should Be $true
            $global:DeviceTestUnexpectedAdbCalls | Should Be 0
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



