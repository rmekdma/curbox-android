# Tests for device-test-common.ps1 functions

Describe "Device Test Common Helpers" {
    BeforeEach {
        if (Test-Path "$PSScriptRoot/../lib/device-test-common.ps1") {
            . "$PSScriptRoot/../lib/device-test-common.ps1"
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

    Context "Stop-ServiceProcess" {
        It "returns false when target PID is 0 or negative" {
            (Stop-ServiceProcess -TargetPid 0) | Should Be $false
            (Stop-ServiceProcess -TargetPid -1) | Should Be $false
        }
    }
}


