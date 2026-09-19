# Curbox device test automation guide (`tools/`)

This guide defines patterns, nonnegotiable invariants, and troubleshooting solutions for developing ADB-based End-to-End (E2E) device tests and helper modules in `tools/`.

## Architecture & conventions

- Harness module: `tools/lib/device-test-common.ps1` is the single source of truth for all device interaction helpers. Do not inline duplicate ADB/settings helpers in test scripts.
- Unit tests: `tools/tests/device-test-common.Tests.ps1` tests harness helper functions using Pester.
- Script structure: Every test script must adhere to this flow:
  1. Parameter declaration (`TargetPackage`, timeouts, etc.) with `$ErrorActionPreference = "Stop"`.
  2. Dot-source common harness: `. "$PSScriptRoot/lib/device-test-common.ps1"`.
  3. Pre-flight ADB check: `Assert-AdbDevice`.
  4. Backup settings and acquire wake lock in `try` block.
  5. Test execution steps with distinct logging (`Write-Step`, `Write-Success`, `Write-Fail`).
  6. Teardown in `finally` block: restore original `settings.json`, clear test overrides, stop target packages, press Home, and revert wake lock (`Set-DeviceAwake $false`).

---

## 1. UIAutomator and UI exploration

### `could not get idle state` mitigation
- Cause: `uiautomator dump` fails when background UI animations, video/reels playback, or rapid window transitions prevent accessibility framework from detecting an idle state.
- Solution:
  - Always purge stale dump artifacts before invoking dump (`rm -f /sdcard/curbox_dump.xml`).
  - Use `Dump-UI` from `device-test-common.ps1`, which implements a 3-attempt retry loop with 500ms backoff before fallback.
  - When waiting for dynamic UI elements, use `Wait-For-UI -Pattern <regex> -TimeoutSeconds <sec>` rather than a single dump call.

### XML attribute ordering and node bounds parsing
- Problem: Node attributes in XML dumps (`text`, `resource-id`, `content-desc`, `bounds`) have no guaranteed ordering across Android versions or OEM builds. Hardcoded sequential regexes such as `text="X"[^>]*resource-id="Y"` fail unpredictably.
- Solution:
  - `Get-NodeBounds` prioritizes structured XML parsing via XPath (`//node[@$attrName='$attrValue']`) over `[xml]$Xml`.
  - On parse failure, it falls back to regex matching `Pattern[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"` without assuming other attribute positions.
  - Universal UI target resolution pattern:
    Prioritize stable `resource-id` first, followed by primary locale text (Korean), and English fallback. Use `-Optional` on intermediate attempts and assert on the final step:
    ```powershell
    # Pattern: Resource-ID -> Primary Locale (Korean) -> Fallback Locale (English)
    $clicked = Tap-Node $ui 'resource-id="android:id/button1"' "확인/적용 버튼" -Optional
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="확인"' "확인 텍스트 버튼" -Optional
    }
    if (-not $clicked) {
        $clicked = Tap-Node $ui 'text="OK"' "OK text button"
    }
    ```

### Android IME text input and field clearing
- Problem: Sending select-all shortcuts (`Ctrl+A` via key combinations) fails or produces unpredictable characters across different soft keyboards (Gboard, Samsung Keyboard, AOSP IME).
- Solution:
  - Tap the input field node coordinates to request focus.
  - Send repeated backspace key events (`KEYCODE_DEL = 67`) before typing new text:
    ```powershell
    adb shell "input tap $($node.X) $($node.Y)"
    Start-Sleep -Milliseconds 500
    adb shell "input keyevent 67 67 67 67 67"
    adb shell "input text $NewValue"
    ```

---

## 2. PowerShell & Pester 3.4.0 compatibility

Windows systems run PowerShell 5.1 with bundled Pester 3.4.0 by default. Modern Pester 5 features will fail.

### Scope and block placement
- `BeforeAll` placed outside a `Describe` block throws `Assert-DescribeInProgress`.
- Rule: Always place setup logic inside `Describe` or `Context` blocks using `BeforeEach`:
  ```powershell
  Describe "Device Test Common Helpers" {
      BeforeEach {
          if (Test-Path "$PSScriptRoot/../lib/device-test-common.ps1") {
              . "$PSScriptRoot/../lib/device-test-common.ps1"
          }
      }
      ...
  }
  ```

### Array assertions
- `Should Contain` in Pester 3.4.0 is a file-content assertion, not an array element assertion. Writing `$array | Should Contain $item` throws an error.
- Rule: Use the PowerShell `-contains` operator combined with `Should Be $true`:
  ```powershell
  ($snapshot.appRules[0].contributorGroupIds -contains $contribGroup.id) | Should Be $true
  ($group.selectedPackages -contains "com.test.app") | Should Be $true
  ```

### Pipeline output leaks
- Problem: Commands or helper expressions returning booleans or strings leak into the PowerShell pipeline. This causes functions to return an array `($true, $actualResult)` instead of a single scalar, breaking downstream comparisons.
- Rule: Pipe all command executions, file pushes, and adb invocations to `| Out-Null`:
  ```powershell
  adb push $tempLocal $RemotePath | Out-Null
  adb shell "run-as $PackageName chmod 660 files/datastore/settings.json" | Out-Null
  Push-TempStringToDevice -Content $json -RemotePath $path | Out-Null
  ```

### Error handling semantics
- Non-terminating `Write-Error` allows execution to proceed unless `$ErrorActionPreference = "Stop"`.
- Rule: Set `$ErrorActionPreference = "Stop"` at script top level. In shared helpers, combine `Write-Error` with explicit `return $null` or terminate directly.

---

## 3. ADB & Android system timing

### Time-based scheduling and minute rollover drift prevention
- Context: Any test verifying future time boundaries (scheduled time ranges, wake scheduler alarms, daily limit resets, bedtime rules) requires sufficient execution margin (5 to 10 seconds) for ADB rule injection and target app setup.
- Problem: If the device clock seconds are near the boundary ($\ge 45$ seconds), ADB commands and activity launches will cross into the scheduled minute before the app reaches steady state, violating precondition checks.
- Universal boundary alignment pattern:
  - Query device time with `Get-DeviceTimeInfo`.
  - When `$timeInfo.Second -ge 45`, pause for `$timeInfo.SecondsUntilNextMinute + 2` to start test setup on a fresh minute:
    ```powershell
    $timeInfo = Get-DeviceTimeInfo
    if ($timeInfo.Second -ge 45) {
        $bufferWait = $timeInfo.SecondsUntilNextMinute + 2
        Write-Host "Second is $($timeInfo.Second)s (>= 45s). Waiting ${bufferWait}s for minute turnover..." -ForegroundColor Yellow
        Start-Sleep -Seconds $bufferWait
        $timeInfo = Get-DeviceTimeInfo
    }
    $targetMinute = $timeInfo.NextMinute
    ```
  - Inject test configuration targeting `$targetMinute`, launch the target app into the foreground, and synchronize wait using `Wait-DeviceMinute -TargetMinute $targetMinute`.

### Usage tracking heartbeat and session flush
- Architectural foundation: `AppUsageTracker` records usage through two distinct mechanisms:
  1. 20-second heartbeat ticks: Writes active foreground usage in 20-second increments while the tracked app remains on top.
  2. Lifecycle session flush: When the tracked app leaves the foreground, residual session time is committed to Room database.
- Universal usage accumulation rule:
  - For any target duration of $N$ minutes, maintain the app in the foreground for at least:
    $$\text{UsageWaitSeconds} = \lceil N \times 60 \rceil + 10$$
    This ensures all 20-second heartbeat intervals complete with a 10-second margin for startup transitions.
  - Track elapsed time in a loop with periodic focus checks:
    ```powershell
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $UsageWaitSeconds) {
        Assert-WindowFocus -ExpectedActivity $TrackedPackage | Out-Null
        Start-Sleep -Seconds 10
    }
    ```
- Universal session flush rule:
  - Never assert target rule transitions immediately while the tracked app is in the foreground.
  - Always navigate to Home (`adb shell input keyevent 3`) or stop the package, and wait at least 2 seconds for coroutines to commit the flushed session to Room before testing rule evaluation:
    ```powershell
    adb shell "input keyevent 3"
    Start-Sleep -Seconds 2
    ```

### Screen stay-awake management
- Inactivity turns off device screens, which freezes accessibility node events and causes UI dump failures.
- Rule:
  - At test start, call `Set-DeviceAwake $true`, which enables `svc power stayon true`, checks screen power state, sends `keyevent 224` (WAKEUP), and dismisses the keyguard.
  - In `finally`, ALWAYS call `Set-DeviceAwake $false` to restore normal power management.

### Accessibility service binding lifecycle
- Security constraint: Calling `am start-service neth.iecal.curbox.debug/...AppBlockerService` fails with `Requires permission android.permission.BIND_ACCESSIBILITY_SERVICE`.
- Rule: To enable or restart the service, write to secure settings:
  ```powershell
  adb shell "settings put secure enabled_accessibility_services neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService"
  adb shell "settings put secure accessibility_enabled 1"
  ```
  Allow 1 to 2 seconds for service binding and warmup before dispatching broadcasts.

---

## 4. DataStore and test state isolation

### File permissions (660) and multi-process access
- Curbox runs across processes: main UI process and `:app_blocker_service` accessibility service process.
- Rule: When restoring or replacing `files/datastore/settings.json` via `run-as`, ALWAYS set permissions to `660`:
  ```powershell
  adb shell "run-as $PackageName cp /data/local/tmp/settings.json files/datastore/settings.json" | Out-Null
  adb shell "run-as $PackageName chmod 660 files/datastore/settings.json" | Out-Null
  ```
  Omitting `chmod 660` leaves the file inaccessible to `:app_blocker_service`, causing silent read fallbacks or permission denials.
- Broadcast refresh: Always notify services after file modification:
  ```powershell
  adb shell "am broadcast -a neth.iecal.curbox.refresh.app_rules -p $PackageName" | Out-Null
  adb shell "am broadcast -a neth.iecal.curbox.refresh.appblocker -p $PackageName" | Out-Null
  ```

### Rule override seam and cleanup
- Test rules are applied via the broadcast seam `neth.iecal.curbox.action.APPLY_TEST_APP_RULES`.
- Rule: Always broadcast `neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES` before applying new test rules and inside `finally` cleanup to prevent test rule leakage between runs.

### `useDayGenerationStartedAtMs` and daily accumulation isolation
- Problem: If contributor or target apps were used earlier today on the test device, Room database contains existing session records for the calendar day. A test expecting a clean 0-minute baseline will see pre-existing usage and prematurely unblock.
- Solution:
  - Curbox's `AppRuleEvaluator` filters out daily usage recorded before `useDayGenerationStartedAtMs`.
  - Use `Set-DeviceUsageGeneration` (or pass `UsageGenerationStartedAtMs` into `Inject-TestAppRules`) to set `useDayGenerationStartedAtMs` to the current Unix timestamp:
    ```powershell
    Set-DeviceUsageGeneration -PackageName $PackageName
    ```
  - This resets the evaluation epoch to 0 minutes for the current test run without wiping user database records.

---

## 5. Verification commands

Run these checks when modifying test scripts or harness modules:

```powershell
# 1. Harness Pester unit tests
Invoke-Pester tools/tests/device-test-common.Tests.ps1

# 2. Target device test execution
powershell -File tools/test-device-guardian-dialog-ui.ps1
powershell -File tools/test-device-contributor-flow.ps1
powershell -File tools/test-device-timerange-interception.ps1
powershell -File tools/test-device-limit-exhaustion.ps1

# 3. Android codebase unit tests
$env:JAVA_HOME = 'C:\Users\DELL\.jdks\jbr-21.0.11'
.\gradlew.bat testFullDebugUnitTest
```
