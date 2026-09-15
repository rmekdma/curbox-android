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
}
