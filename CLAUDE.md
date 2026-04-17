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

## Tests

```bash
./gradlew testDebugUnitTest           # JVM unit tests (all modules)
./gradlew connectedDebugAndroidTest   # instrumentation tests on connected device/AVD
```

Unit tests live in `core-data/` (GroupsBackup) and `core-audit/` (parsers, honeypot smoke). Instrumented tests — в `app/src/androidTest/` (Room migrations, honeypot on-device, Shizuku e2e, FreezeActionReceiver).

Чтобы инструментированные тесты работали полноценно, AVD должен иметь запущенный Shizuku-demon + выданное разрешение Anubis. Автоматизация — `scripts/setup-avd-shizuku.sh`:

```bash
scripts/setup-avd-shizuku.sh              # полная настройка (install Shizuku + demon + grant)
scripts/setup-avd-shizuku.sh --only-demon # только перезапустить demon после ребута AVD
```

Без Shizuku тесты, которым он нужен, пропускаются через `Assume.assumeTrue`, сборка остаётся зелёной.

## Important naming quirks

- Gradle project name is `VpnStealthSwitch` (см. `settings.gradle.kts`), the app package is `sgnv.anubis.app`, and the product is called **Anubis**. All three refer to the same thing — не «чини».
- Room DB file — `vpn_stealth.db`, schema version 6. Миграции 3→4→5→6 явные (`MIGRATION_3_4_EXPOSED`, `MIGRATION_4_5_EXPOSED`, `MIGRATION_5_6_EXPOSED` в `AppDatabase.kt`), v1/v2 — `fallbackToDestructiveMigrationFrom(1, 2)` как safety net.
- **Kotlin package ≠ Gradle namespace.** Файлы в `:core-shizuku` имеют namespace модуля `sgnv.anubis.core.shizuku`, но Kotlin package у них остался `sgnv.anubis.app.shizuku` — это намеренно, чтобы импорты в `:app` не ломались при переезде.

## Module structure

Проект разбит на 4 Gradle-модуля:

```
:app                ← MainActivity + ViewModels + UI screens + services
                      (StealthOrchestrator, VpnMonitorService, StealthVpnService,
                      ShortcutActivity, StealthTileService, BootReceiver,
                      FreezeActionReceiver, HitNotifier, AuditViewModel,
                      MainViewModel + UpdateController/AppListController/SettingsController,
                      VpnClientManager + VpnClient, UpdateChecker/UpdateInstaller)

:core-shizuku       ← ShizukuManager, UserService, ShellExec, IUserService.aidl
                      Зависит только от dev.rikka.shizuku:api/provider + kotlinx-coroutines.
                      `ShizukuManager(packageManager, hostPackageName)` — applicationId
                      передаётся извне, т.к. BuildConfig хоста недоступен из library.

:core-data          ← Room: AppDatabase, ManagedAppDao, AuditHitDao + migrations;
                      entities (ManagedApp, AuditHitEntity, AppGroup, InstalledAppInfo);
                      AppRepository, GroupsBackup, DefaultRestrictedApps;
                      interface GroupsStore — узкий контракт для тестов GroupsBackup.

:core-audit         ← HoneypotListener, UidResolver, NativeUidResolver,
                      PackageResolver, TlsSniParser, AuditRepository, AuditHit/Suspect.
                      api(:core-shizuku) + api(:core-data). HitNotifier + AuditViewModel
                      остаются в :app потому что тянут R, MainActivity, ApplicationContext.
```

Граф зависимостей:
```
:app → :core-audit → :core-shizuku
                   → :core-data
     → :core-shizuku (для AnubisApp.onCreate)
     → :core-data    (для AppDatabase.getInstance, GroupsBackup)
```

**Правило:** новый модуль = Android library с namespace `sgnv.anubis.{core,feature}.<domain>`, минимальный manifest, зависимости от фреймворка через `api(...)` если выходят в публичные сигнатуры. `/<module>/build` — в `.gitignore` (на Windows длинные KSP intermediate-пути ломают `git add`).

## Architecture

Single-activity Jetpack Compose app. `MainActivity` держит `NavigationBar` с тремя экранами (`HomeScreen`, `AppListScreen`, `SettingsScreen`) + `RecoveryScreen` + `AuditScreen` доступные из Settings/Home. State оркестрирует `MainViewModel` (фасад), делегирующий на `UpdateController` / `AppListController` / `SettingsController` — извлечены, чтобы god-object не рос.

### Shizuku → shell pipeline (core mechanism)

Every privileged action flows through a two-hop path:

1. `IUserService.aidl` (в `:core-shizuku`) определяет `execCommand` / `execCommandWithOutput`. `UserService` реализует их, вызывая `Runtime.getRuntime().exec(...)` внутри Shizuku-spawned shell-uid процесса с drain-threads для stdout/stderr, 15-секундным timeout и лимитом вывода 2 MiB.
2. `ShizukuManager` (создан в `AnubisApp.onCreate`, shared across the process) биндит service. `awaitUserService()` / `awaitShizukuReady()` — suspend-хелперы которые ждут реального коннекта вместо `delay(200)`. `freezeApp` / `unfreezeApp` / `isAppFrozen` уважают `FreezeMode { DISABLE_USER, SUSPEND }` из SharedPreferences — `pm suspend` добавлен как альтернатива для Honor-лаунчеров где `disable-user` шлёт `PACKAGE_REMOVED` и разрушает папки.

### Stealth orchestration

`StealthOrchestrator` — state machine для Home toggle. Правила при редактировании:

- **`LOCAL`** = frozen пока VPN ON. **`VPN_ONLY`** = frozen пока VPN OFF. **`LAUNCH_VPN`** = никогда не auto-frozen, но тап по иконке форсирует VPN.
- LOCAL и VPN_ONLY по умолчанию заморожены и размораживаются **только через явный launch** (`launchWithVpn` / `launchLocal`).
- `disable()` никогда не размораживает если VPN не отключился; при сбое `stopVpn()` state откатывается в `ENABLED` и ошибка идёт в `lastError`.
- `frozenVersion` — монотонный счётчик, UI читает его чтобы пере-resolve'ить реальный frozen-state из PackageManager.
- `freezeGroup(group)` параллельно с `Semaphore(permits = 4)` — на 20+ приложениях последовательный цикл тормозил.

### VPN control & detection

`VpnClientControls` (в `vpn/VpnClient.kt`) маппит клиенты на `VpnControlMode`:

- **SEPARATE** (NekoBox) — отдельные `am start` для QuickEnable / QuickDisable.
- **TOGGLE** (v2rayNG, Happ, v2rayTun, V2Box) — один `am broadcast`. Для TOGGLE/MANUAL `stopVpn()` идёт через dummy-VPN takeover + `am force-stop`.
- **MANUAL** (Amnezia, любой пользовательский пакет) — просто запуск, пользователь жмёт сам.

VPN liveness — `ConnectivityManager.NetworkCallback` на `TRANSPORT_VPN`. Owner-пакет резолвится через `dumpsys connectivity` (парсинг в Kotlin по регексам `type: VPN\[` / `Transports: VPN` / `[VPN]`, устойчиво на A11..A15) + `pm list packages --uid` через Shizuku. Race фиксит `Mutex` + `cancelAndJoin` предыдущего detect-job.

`StealthVpnService` — собственный `VpnService`. `establish()` заставляет Android revoke другой VPN; тунель закрывается сразу. Это step 2 трёхфазного stop (SEPARATE-API → decoy takeover → force-stop). Decoy-режим (для аудита) держит soft-tun0 без блокировки трафика, auto-stop 24ч.

### Audit (honeypot)

`HoneypotListener` поднимает ServerSocket+DatagramSocket на IPv4+IPv6 loopback для портов из методички Минцифры (1080/9000/5555/9050/9051/9150/3128/8080/8888/10808/10809/7890/9090/2080). На hit:

1. UID: сначала `ConnectivityManager.getConnectionOwnerUid` (native fast path, но без `NETWORK_STACK` permission не работает на loopback — см. `project_getconnectionowneruid_loopback.md`). Fallback — `/proc/net/tcp[6]` через Shizuku-shell.
2. Package: `PackageManager.getPackagesForUid(uid)`; fallback — `pm list packages --uid` через Shizuku.
3. TLS SNI: если первые байты `16 03 XX` — `TlsSniParser.extractSni` тащит `server_name` из ClientHello.
4. SOCKS5 greeting `05 01 00` отвечается `05 00` (no-auth) чтобы сканер прошёл handshake дальше.

Хиты персистятся в Room (таблица `audit_hits`). `AuditRepository.exportAsJson` дампит для ShareSheet.

`HitNotifier` читает `hit_action_mode` из prefs: `off` (только список), `ask` (нотификация с кнопками Заморозить/Отклонить), `auto` (замораживаем сразу, в нотификации «Разморозить»). Actions обрабатывает `FreezeActionReceiver` (manifest-registered, exported=false).

### Auto-freeze / background paths

Все entry points маршрутизируют через `StealthOrchestrator` + `ShizukuManager` (singletons в `AnubisApp`):

- `BootReceiver` — переморозка после reboot.
- `VpnMonitorService` — foreground service (`specialUse`), чтобы VPN-callback жил после swipe.
- `StealthTileService` — Quick Settings tile.
- `ShortcutActivity` — pin-shortcuts.
- `MainViewModel.observeVpnState` — если VPN меняется вне Anubis, freeze LOCAL / VPN_ONLY.
- `HitNotifier` — ASK/AUTO hit от honeypot → `FreezeActionReceiver`.

### Data layer

- `ManagedApp(packageName PK, group: AppGroup)` — основная entity. `AppGroup` хранится string через `AppGroupConverter`.
- `AuditHitEntity` — persist-копия `AuditHit` с autoGenerate id. Колонки: `timestampMs`, `port`, `uid?`, `packageName?`, `handshakePreview?`, `sni?` (v5), `protocol = "TCP"|"UDP"` (v6, default `TCP`).
- `AppRepository.cycleGroup` продвигает `none → LOCAL → VPN_ONLY → LAUNCH_VPN → none` — AppList UI полагается на порядок.
- `DefaultRestrictedApps` — статичный список для first-run (банки, маркетплейсы, `com.yandex.*` / `ru.yandex.*`).
- `GroupsBackup.export/import/replaceAll` — JSON dump/restore managed_apps. Использует `GroupsStore` interface ради тестируемости.
- Settings live в plain `SharedPreferences("settings")`. Ключи: `vpn_client_package`, `freeze_mode` (disable|suspend), `hit_action_mode` (off|ask|auto), `update_source` (fork|upstream|off), `background_monitoring`, `freeze_on_boot`, `update_last_check_ms`, `update_skipped_version`.

### Self-update (app/update/)

- `UpdateChecker` парсит GitHub releases API. `update_source=fork` (default) → `ealosev-ai/anubis`; `upstream` → `sogonov/anubis`; `off` → без HTTP.
- `UpdateInstaller` скачивает APK в cache, считает SHA-256, сверяет с `SHA-256: <64hex>` из release body. Установка через FileProvider + ACTION_INSTALL_PACKAGES. Если в release нет хеша — UI показывает warning и fallback в браузер.

## Conventions worth respecting

- **UI strings — русские** (см. `HomeScreen`, ошибки в `StealthOrchestrator`). Новые user-facing строки — тоже на русском.
- **Терминология:** «заморозить / разморозить» = `pm disable-user`/`pm enable` или `pm suspend`/`unsuspend`; «отключить / включить» = VPN toggle. Не смешивать.
- **Accessibility:** иконки приложений озвучивают состояние (`contentDescription` включает "заморожено"/"активно"), Switch-и имеют `stateDescription`. Port-cards в AuditScreen используют `semantics(mergeDescendants = true)` для слитного TalkBack-чтения.
- **Не мокай Shizuku в логике.** Если функции нужен shell — принимает `ShellExec` (interface в `:core-shizuku`). Не добавлять параллельный путь в обход.
- **Broadcast/activity actions для новых VPN-клиентов** — только в `VpnClientControls`; README описывает jadx-поиск.
- **Миграции Room:** если добавляешь колонку — bump version + написать `MIGRATION_X_Y_EXPOSED` top-level в `AppDatabase.kt` (public, чтобы тест `:app/androidTest/AppDatabaseMigrationTest` мог их применить). `fallbackToDestructiveMigrationFrom(1, 2)` — только для совсем исторических версий.
- **Автонастройка AVD** после ребута: `scripts/setup-avd-shizuku.sh --only-demon`. После `connectedDebugAndroidTest` gradle деинсталлирует тестовый APK, но основной остаётся — Shizuku-permission тоже остаётся.
- **Build на Windows:** исключать `/<module>/build` из git (длинные имена KSP-generated'ов ломают `git add`).
