# Anubis

Android-приложение, которое защищает пользователей от детекторов VPN в «особо бдительных» приложениях (банки, маркетплейсы, гос-сервисы) через **полное отключение** таких приложений на уровне системы, когда VPN активен.

В отличие от sandbox-решений (Island, Insular, Shelter), которые изолируют приложения в work profile, но оставляют им общий сетевой стек и возможность обнаружить VPN — Anubis использует `pm disable-user`, что делает приложение **нерабочим целиком**: оно не может запустить ни одного процесса, принять broadcast, получить доступ к сетевым интерфейсам или заметить наличие VPN.

## Фичи

### Группы приложений
- **LOCAL** — заморожено когда VPN включён, запускается без VPN (бизнес-приложения)
- **VPN_ONLY** — заморожено когда VPN выключён, запускается через VPN
- **LAUNCH_VPN** — никогда не замораживается, но тап по иконке форсирует включение VPN

### Стелс-режим
- **Home screen launcher** — тап по иконке запускает приложение с правильным состоянием VPN
- **Grayscale иконки** — визуально отличают замороженные приложения
- **Long-press menu** — заморозить/разморозить вручную, создать shortcut, поменять группу
- **Pinned shortcuts** — ярлыки на домашнем экране, которые одним тапом оркестрируют freeze/VPN/launch
- **Quick Settings tile** — переключатель стелс-режима из шторки
- **Auto-freeze** при загрузке, при запуске приложения и при переключении VPN вне Anubis

### Аудит детекторов VPN (honeypot)
- Поднимаем ServerSocket/DatagramSocket на localhost'е на портах из методички Минцифры (SOCKS5: 1080/9000/5555; Tor: 9050/9051/9150; HTTP CONNECT: 3128/8080/8888; xray: 10808/10809; Clash: 7890/9090; sing-box: 2080)
- Когда приложение туда ломится — ловим uid через `/proc/net/tcp[6]` + резолвим pkg
- **TLS ClientHello SNI capture** — если сканер пришёл HTTPS'ом, видим внешний хост (например `api.rshb.ru`)
- **UDP honeypot** — ловим QUIC/WireGuard-сканеры отдельным listener'ом
- **Decoy VPN** — soft-tun0 без блокировки трафика, провоцирует детекторы на скан localhost'а
- **Auto-freeze при hit** — режимы OFF (только в списке), ASK (нотификация с кнопками), AUTO (моментальная заморозка + «Разморозить»)
- **Экспорт лога** через ShareSheet в JSON

### Режимы заморозки
- **`pm disable-user`** (default) — надёжно, но шлёт `PACKAGE_REMOVED`, что ломает раскладку папок на лаунчерах Honor/MagicOS
- **`pm suspend`** (Android 7+) — приложение при запуске показывает системный «приостановлено», папки лаунчера живут
- Переключатель в Settings

### VPN-клиенты
- **SEPARATE** (NekoBox) — отдельные `am start` для QuickEnable/QuickDisable
- **TOGGLE** (v2rayNG, Happ, v2rayTun, V2Box) — `am broadcast` к widget receiver
- **MANUAL** (Amnezia, любой пользовательский пакет) — просто открывается, пользователь подключается сам
- **Автодетект активного VPN** через `dumpsys connectivity` + UID resolver — работает для любого клиента, не только known

### VPN disconnect (3-phase)
Toggle-broadcast ненадёжен для остановки (может сразу включить обратно), поэтому:
1. **API stop** — только SEPARATE-клиенты
2. **Decoy VPN** — `VpnService.establish()` заставляет Android revoke чужой туннель, потом сразу закрывает свой
3. **`am force-stop`** — убивает процесс VPN-приложения

Приложения никогда не размораживаются пока VPN ещё активен.

### Self-update
Проверка релизов через GitHub API. Источник настраивается: свой форк (default), upstream, off.
- **SHA-256 верификация** скачанного APK перед установкой
- Если SHA-256 указан в release notes — качаем в cache, сверяем, ставим через `FileProvider` + `ACTION_VIEW`
- Нет SHA-256 — UI показывает warning и fallback в браузер

### Дополнительно
- **Backup/Restore** групп приложений в JSON через ShareSheet
- **Network check** — ping, страна, город (IP скрыт по умолчанию)
- **Recovery screen** — аварийная разморозка всех отключённых приложений, сброс групп
- **Shizuku badge** на главном экране — цветная точка показывает статус демона

## Как это работает

### Shizuku pipeline
Все привилегированные действия идут через двухшаговый путь:
1. `IUserService.aidl` в `:core-shizuku` определяет `execCommand` / `execCommandWithOutput`. `UserService` реализует их через `Runtime.getRuntime().exec(...)` внутри Shizuku-spawned shell-UID процесса. Процесс защищён drain-threads для stdout/stderr, 15-секундным timeout и лимитом вывода 2 MiB.
2. `ShizukuManager` (singleton в `AnubisApp`) биндит service и предоставляет `freezeApp`, `unfreezeApp`, `isAppFrozen`, `isAppInstalled`, `runCommandWithOutput`, `execShellCommand`, `awaitUserService`.

Заморозка = `pm disable-user --user 0 <pkg>` или `pm suspend --user 0 <pkg>` (по настройке).
Разморозка = `pm enable <pkg>` + `pm unsuspend --user 0 <pkg>` (идемпотентно, чтобы переключить режим на лету).

### Honeypot flow
```
Приложение делает коннект на 127.0.0.1:1080
  → HoneypotListener.acceptLoop принимает
  → UidResolver.resolve(remotePort, 1080):
      — Попытка 1: ConnectivityManager.getConnectionOwnerUid
        (не работает на loopback без NETWORK_STACK permission)
      — Попытка 2: shell `cat /proc/net/tcp6` через Shizuku
        → парсер находит строку с uid
  → PackageResolver.resolve(uid):
      — Попытка 1: PackageManager.getPackagesForUid
      — Попытка 2: shell `pm list packages --uid <uid>` через Shizuku
  → AuditHit(port, uid, pkg, preview, sni?, protocol) → Room
  → HitNotifier читает prefs `hit_action_mode`:
      — OFF: только в AuditScreen
      — ASK: нотификация с «Заморозить» / «Отклонить»
      — AUTO: freezeApp() + нотификация с «Разморозить»
```

## Требования

- Android 10+ (API 29)
- [Shizuku](https://shizuku.rikka.app/) установлен и запущен
- Хотя бы один VPN-клиент установлен (v2rayNG, NekoBox, Happ, v2rayTun, V2Box, AmneziaVPN или любой другой в manual-режиме)

## Установка

1. Установи и запусти Shizuku (через ADB или Wireless Debugging)
2. Установи Anubis
3. Разреши Shizuku-permission в диалоге
4. Разреши VPN-permission (нужен для decoy-disconnect)
5. Вкладка **Apps** — распределить приложения по группам (или «Автовыбор» для банков)
6. Вкладка **Settings** — выбрать VPN-клиент
7. Главный экран — переключатель стелс-режима

## Сборка

```bash
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK, требует signing.properties
./gradlew :app:lint            # Android lint
```

APK: `app/build/outputs/apk/<variant>/anubis-<versionName>-<variant>.apk`.

Release-сборка читает `signing.properties` в корне проекта:
```properties
storeFile=release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Debug-вариант имеет `applicationIdSuffix=".debug"` — её можно держать одновременно с release-сборкой автора на одном устройстве.

## Тесты

```bash
./gradlew testDebugUnitTest                                          # 79 JVM unit тестов, ~3 сек
scripts/setup-avd-shizuku.sh && ./gradlew connectedDebugAndroidTest  # 12 на AVD, ~30 сек
```

Скрипт `scripts/setup-avd-shizuku.sh` автоматизирует настройку AVD: скачивает Shizuku APK, поднимает демона, устанавливает Anubis и грантит Shizuku-permission через uiautomator.

Подробнее про тесты и архитектуру в [CLAUDE.md](./CLAUDE.md).

## Архитектура

Проект разбит на 4 Gradle-модуля:

| Модуль | Ответственность |
|---|---|
| `:app` | UI (Jetpack Compose), ViewModels, AnubisApp, сервисы, MainActivity |
| `:core-shizuku` | ShizukuManager, UserService, ShellExec, AIDL |
| `:core-data` | Room: AppDatabase, DAOs, migrations, entities, repositories |
| `:core-audit` | Honeypot listener, парсеры (TLS SNI, UID, dumpsys), AuditRepository |

Связи: `:app` → `:core-audit` → `:core-shizuku` + `:core-data`.

Технологии:
- Kotlin + Jetpack Compose (Material 3)
- Shizuku API 13.1.5 (AIDL UserService)
- Room 2.7.2 + KSP 2.3.2
- ConnectivityManager NetworkCallback
- ShortcutManager (pinned shortcuts)
- Coroutines + StateFlow/SharedFlow

## Roadmap

- [x] Background VPN monitoring service
- [x] Export/import app group configuration
- [x] Audit honeypot против детекторов VPN
- [x] `pm suspend` режим для Honor/MagicOS
- [x] Self-update с SHA-256 проверкой
- [x] Multi-module архитектура
- [x] E2E scenarios + unit coverage (91 тест)
- [ ] Self-hosted `app_process` demon для жизни без Shizuku
- [ ] UDP honeypot для QUIC (частично: только при `connect()` клиенте)
- [ ] Дополнительные VPN-клиенты (WireGuard, sing-box, Hiddify)

## Лицензия

MIT
