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
}

