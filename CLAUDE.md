# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK — needs signing.properties in project root
./gradlew :app:lint            # Android lint
./gradlew installDebug         # install on connected device
```

Release builds read `signing.properties` at the project root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). APK output path pattern: `app/build/outputs/apk/<variant>/anubis-<versionName>-<variant>.apk`.

No unit/instrumentation tests exist in the repo.

## Important naming quirks

- Gradle project name is `VpnStealthSwitch` (see `settings.gradle.kts`), the app package is `sgnv.anubis.app`, and the product is called **Anubis**. All three refer to the same thing — don't "fix" them.
- Room DB file is `vpn_stealth.db`; schema version 3 uses `fallbackToDestructiveMigration()` — bumping the version wipes user data, so migrations must be added explicitly if that's not desired.

## Architecture

Single-activity Jetpack Compose app. `MainActivity` hosts a `NavigationBar` with three screens (`HomeScreen`, `AppListScreen`, `SettingsScreen`) plus a `RecoveryScreen` reachable from Home/Settings. A single `MainViewModel` owns almost all state — subscreens observe its `StateFlow`s.

### Shizuku → shell pipeline (core mechanism)

Every privileged action flows through a two-hop path:

1. `IUserService.aidl` defines `execCommand` / `execCommandWithOutput`. The `UserService` implementation calls `Runtime.getRuntime().exec(...)` inside the Shizuku-spawned shell-uid process.
2. `ShizukuManager` (created once in `AnubisApp.onCreate`, shared across the process) binds that service and exposes `freezeApp`, `unfreezeApp`, `forceStopApp`, `execShellCommand`, `runCommandWithOutput`. Everything that needs root-ish powers goes through here — there is no second path.

Freezing is literally `pm disable-user --user 0 <pkg>`; unfreezing is `pm enable <pkg>`. "Is frozen" is read synchronously from `PackageManager.getApplicationInfo().enabled` — the app does not maintain its own "frozen" flag.

### Stealth orchestration

`StealthOrchestrator` is the state machine for the Home toggle. Rules to preserve when editing:

- **`LOCAL` apps** = frozen while VPN is ON. **`VPN_ONLY` apps** = frozen while VPN is OFF. **`LAUNCH_VPN` apps** = never auto-frozen, but tapping their icon forces VPN on first.
- Both LOCAL and VPN_ONLY stay frozen by default and are only unfrozen by an **explicit launch** via `launchWithVpn` / `launchLocal` — so re-entering the app does not silently unfreeze things.
- `disable()` never unfreezes apps unless VPN is actually off. If `stopVpn()` fails, the state rolls back to `ENABLED` and an error is surfaced via `lastError`.
- `frozenVersion` is a monotonic counter bumped on every freeze/unfreeze; the UI collects it to trigger icon re-read (since the real "frozen" state lives in PackageManager, not in ViewModel fields).

### VPN control & detection

`VpnClientControls` (in `VpnClient.kt`) maps each known client to a `VpnControlMode`:

- **SEPARATE** (NekoBox) — distinct `am start` commands for QuickEnable / QuickDisable activities.
- **TOGGLE** (v2rayNG, Happ, v2rayTun, V2Box) — one `am broadcast` to the widget receiver; same command toggles on/off. Because toggle-stop is unreliable, `StealthOrchestrator.stopVpn()` only uses the API for SEPARATE clients; for TOGGLE/MANUAL it falls through to dummy-VPN takeover, then `am force-stop`.
- **MANUAL** (any user-picked package, including unknown clients) — app is just launched, user connects manually.

VPN liveness is observed with `ConnectivityManager.NetworkCallback` on `TRANSPORT_VPN`. The *owning* app is resolved by shelling `dumpsys connectivity | grep -A 30 'type: VPN\[' | grep OwnerUid` then `pm list packages --uid <uid>` — this is what enables custom/unknown VPN clients to be detected and force-stopped.

`StealthVpnService` is the project's **own** `VpnService` and exists solely so that calling `establish()` causes Android to revoke the other VPN; it closes its tunnel immediately afterward. This is the `disconnect()` step in the 3-phase stop sequence.

### Auto-freeze / background paths

Work can enter the freeze pipeline from several places outside `MainActivity`:

- `BootReceiver` — re-applies freeze policy after reboot.
- `VpnMonitorService` — foreground service (`specialUse`) kept alive while stealth is on, so the VPN callback stays registered when the app is swiped away.
- `StealthTileService` — Quick Settings tile toggles stealth.
- `ShortcutActivity` — target of pinned home-screen shortcuts; runs the freeze/VPN/launch orchestration in one shot then finishes.
- `MainViewModel.observeVpnState` — if the user toggles VPN *outside* Anubis, the callback fires and we freeze `LOCAL` (VPN just went on) or `VPN_ONLY` (VPN just went off).

All of these ultimately route through `StealthOrchestrator` + `ShizukuManager`, so changes to the freeze policy belong there rather than in each entry point.

### Data layer

- `ManagedApp(packageName PK, group: AppGroup)` is the only Room entity. `AppGroup` is stored as a string via `AppGroupConverter`.
- `AppRepository.cycleGroup` advances `none → LOCAL → VPN_ONLY → LAUNCH_VPN → none` — the AppList UI relies on this ordering.
- `DefaultRestrictedApps` is a static list used on first run to propose a LOCAL assignment (banks, marketplaces, `com.yandex.*` / `ru.yandex.*`).
- Settings live in plain `SharedPreferences("settings")`; the `vpn_client` → `vpn_client_package` migration in `loadSelectedClient` is intentional and still needs to stay.

## Conventions worth respecting

- **UI strings are Russian** (see `HomeScreen`, error messages in `StealthOrchestrator`). New user-facing strings should match — don't silently switch to English.
- **Don't mock Shizuku in logic code.** If a function needs shell access, it takes `ShizukuManager` — don't add a parallel code path that bypasses it.
- **Broadcast/activity actions for new VPN clients** belong in `VpnClientControls` only; the README documents the jadx-based discovery process.
- `fallbackToDestructiveMigration()` is in place — if you add/rename columns, either bump the version *and* provide a real `Migration`, or explicitly accept data loss.
