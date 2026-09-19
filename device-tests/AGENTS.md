# Curbox device test automation guide (`device-tests/`)

ADB-based End-to-End (E2E) device testing patterns and nonnegotiable invariants for `device-tests/`.

## Architecture & test script anatomy

- Harness SSOT: `device-tests/lib/device-test-common.ps1` is the authoritative source for all device interaction helpers. Do not inline raw adb or settings logic.
- Pester unit tests: `device-tests/tests/device-test-common.Tests.ps1` validates harness helpers. Any helper added to `device-test-common.ps1` must have unit tests here.
- Standard script flow:
  1. Standard parameter block:
     ```powershell
     param(
         [string]$TargetPackage = "com.woodenpharm.choseonggacha",
         [string]$DeviceId = ""
     )
     $ErrorActionPreference = "Stop"
     ```
  2. Dot-source common harness: `. "$PSScriptRoot/lib/device-test-common.ps1"`.
  3. Pre-flight check: `Assert-AdbDevice`.
  4. Backup settings (`Backup-DeviceSettings`) and acquire wake lock (`Set-DeviceAwake $true`) in `try` block.
  5. Test execution steps with structured logging (`Write-Step`, `Write-Success`, `Write-Fail`).
  6. Teardown in `finally`: revert wake lock (`Set-DeviceAwake $false`), restore backup if present, and **unconditionally** run `Clear-TestAppRules`, `am force-stop $TargetPackage`, and `input keyevent 3`.

---

## Conventions and rules checklist

| Category | Rule & Pitfall | Correct Pattern |
|---|---|---|
| **Pipeline leaks** | Pipeline outputs pollute return values into arrays (`$true, $result`). Pipe all commands to `Out-Null`. | `adb push ... \| Out-Null; Push-TempStringToDevice ... \| Out-Null` |
| **PowerShell variables** | `$PID` is a reserved read-only variable (PowerShell host PID). Parameter naming causes runtime errors. | Use `[int]$TargetPid` or `[int]$ServicePid` |
| **Pester 3.4.0 scope** | `BeforeAll` outside `Describe` throws `Assert-DescribeInProgress`. | Place setup in `BeforeEach` inside `Describe` or `Context` |
| **Pester 3.4.0 arrays** | `Should Contain` checks file contents, not array elements. | `($array -contains $item) \| Should Be $true` |
| **Error termination** | Non-terminating `Write-Error` allows execution to proceed unless stopped. | `$ErrorActionPreference = "Stop"` at top level; pair `Write-Error` with `return $null` |
| **Dynamic UI elements** | Immediate `Dump-UI` after focus check misses uninflated views. Always poll with regex. (`Dump-UI` internally retries 3x on `could not get idle state`). | `Wait-For-UI -Pattern "검색\|Search" -TimeoutSeconds 10` |
| **IME input & clear** | `Ctrl+A` fails across Android software keyboards. Tap coordinates and send backspaces. | `adb shell "input tap $x $y; input keyevent 67 67 67 67 67; input text $val"` |
| **Accessibility service** | `am start-service` fails with `BIND_ACCESSIBILITY_SERVICE`. Enable via secure settings and check binding. | `Enable-AccessibilityService`; verify with `Test-AccessibilityServiceBound` |
| **DataStore permissions** | Multi-process access (`main` vs `:app_blocker_service`) requires 660 permission. Harness mutation helpers enforce this automatically. | Never push raw JSON without harness: use `Restore-DeviceSettings`, `Set-DeviceUsageGeneration`, `Set-DeviceGuardianAuthConfig` |
| **Settings access** | Never inline raw `run-as ... cat`. Use safe structured getter. | `$obj = Get-DeviceSettings -PackageName $pkg -AsObject` |
| **Rule injection** | Apply test rule snapshot and atomically initialize clean session generation epoch. | `Inject-TestAppRules -AppRuleSnapshot $snapshot -UsageGenerationStartedAtMs $testStartTimeMs` |
| **Daily usage isolation** | Prior usage recorded today in Room leaks into tests. Reset evaluation epoch without wiping DB. | `Set-DeviceUsageGeneration -PackageName $pkg` |
| **Guardian credentials** | PBKDF2 hash injection must set 660 permissions and broadcast refresh. | `Set-DeviceGuardianAuthConfig -GuardianAuthConfig (New-GuardianPinAuthConfig -Pin $pin)` |
| **Override validation** | Validate recorded skips without manual JSON regex. | `Test-AppRuleSkip -Skips $skips -RuleId $id -MinSkipUntilMs $minMs` |
| **Toast / Logcat** | Modern Android system Toasts do not render in UIAutomator XML dumps. Inspect package-scoped logcat events. | `adb logcat -c \| Out-Null; <action>; $logs = (adb logcat -d -t 200 \| Out-String); ($logs -match "Toast" -and $logs -match $pkg) \| Should Be $true` |

---

## Core architecture & timing patterns

### 1. Universal UI target resolution hierarchy
Attributes in XML dumps (`text`, `resource-id`, `bounds`) have non-deterministic order across Android versions. `Get-NodeBounds` prioritizes XPath (`//node[@$attrName='$attrValue']`) over `[xml]$Xml` with regex bounds fallback.
Always chain resolution from stable ID to localized strings with `-Optional`, asserting on the final step:
```powershell
# Pattern: Resource-ID -> Primary Locale (Korean) -> Fallback Locale (English)
$clicked = Tap-Node $ui 'resource-id="android:id/button1"' "확인/적용 버튼" -Optional
if (-not $clicked) { $clicked = Tap-Node $ui 'text="확인"' "확인 텍스트 버튼" -Optional }
if (-not $clicked) { $clicked = Tap-Node $ui 'text="OK"' "OK text button" }
```

### 2. Stale overlay dismissal & causal re-interception
An existing blocking activity (`GuardianApprovalActivity` or `WarningActivity`) sitting on top of the task stack masks subsequent behavior and causes false positive passes.
- After verifying an initial block, dismiss the overlay to Home via `adb shell "input keyevent 3"` while retaining the target app task in the background.
- When verifying re-interception (e.g. after service recovery or rule re-evaluation), bring the target app back with `am start` (do NOT `force-stop`).
- Confirm that the active service genuinely intercepts the foreground transition and displays a fresh blocking screen.

### 3. Time-based scheduling & minute rollover drift
Scheduling future time boundaries (time ranges, wake scheduler alarms, bedtime rules) requires a 5 to 10 second execution margin for ADB rule injection and app launch. If device clock seconds are near the boundary ($\ge 45$s), setup will bleed into the target minute.
```powershell
$timeInfo = Get-DeviceTimeInfo
if ($timeInfo.Second -ge 45) {
    # Pause for rollover to start setup with a full 60s window
    Start-Sleep -Seconds ($timeInfo.SecondsUntilNextMinute + 2)
    $timeInfo = Get-DeviceTimeInfo
}
$targetMinute = $timeInfo.NextMinute
# Inject rule targeting $targetMinute, launch app, then synchronize wait
Wait-DeviceMinute -TargetMinute $targetMinute
```

### 4. Usage accumulation & session flush timing
`AppUsageTracker` logs usage in 20-second heartbeat increments while an app is focused, and commits residual session time to Room only when the app leaves the foreground.
- **Accumulation duration**: To satisfy an $N$-minute requirement, maintain foreground for:
  $$\text{UsageWaitSeconds} = \lceil N \times 60 \rceil + 10$$
  Poll focus every 10s: `while ($sw.Elapsed.TotalSeconds -lt $UsageWaitSeconds) { Assert-WindowFocus -ExpectedActivity $TrackedPackage \| Out-Null; Start-Sleep -Seconds 10 }`
- **Session flush**: Never assert target state transitions while the tracked app is focused. Always send Home (`adb shell input keyevent 3`) or stop the app, and wait at least 2 seconds for Room commit before testing rules.
- **Lower-bound verification**: In limit-exhaustion tests, verify elapsed time before accepting interception to rule out stale state leakage:
  $$\text{MinRequiredSeconds} = [math]::Max(5, (N \times 60) - 5)$$
  `if ($elapsed -lt $minRequiredSeconds) { Write-Fail "Premature block: stale usage leaked" }`

### 5. Process control & SELinux / non-root resilience
On Android 14+ or non-root devices, executing `kill -9 <PID>` under the adb `shell` user (UID 2000) against an app process running under another UID (`u0_a...`) fails with `Operation not permitted`.
`Stop-ServiceProcess` implements automatic fallback: attempts `kill -9` first, and if the process remains alive after 300ms, executes `adb shell "am crash $TargetPid"`. The `ActivityManager` `am crash` command holds system permissions to terminate any app process cleanly without requiring root.

---

## Verification commands

```powershell
# 1. Harness Pester unit tests
Invoke-Pester device-tests/tests/device-test-common.Tests.ps1

# 2. Target device test execution
powershell -File device-tests/test-device-guardian-dialog-ui.ps1
powershell -File device-tests/test-device-contributor-flow.ps1
powershell -File device-tests/test-device-timerange-interception.ps1
powershell -File device-tests/test-device-limit-exhaustion.ps1
powershell -File device-tests/test-device-guardian-skip-today.ps1
powershell -File device-tests/test-device-guardian-pin-unlock.ps1
powershell -File device-tests/test-device-service-recovery.ps1
powershell -File device-tests/test-device-rollover-guardian-extra-time.ps1

# 3. Android codebase unit tests
$env:JAVA_HOME = 'C:\Users\DELL\.jdks\jbr-21.0.11'
.\gradlew.bat testFullDebugUnitTest
```
