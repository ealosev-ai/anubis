#!/usr/bin/env bash
# Автонастройка AVD для инструментированных тестов.
#
# Что делает:
#   1. Проверяет подключение эмулятора через adb.
#   2. Устанавливает Shizuku release APK (скачивает последнюю версию с GitHub).
#   3. Запускает shizuku-demon через adb (libshizuku.so внутри APK).
#   4. Устанавливает anubis debug APK.
#   5. Открывает Anubis → тапает «Разрешить» → в диалоге Shizuku «Allow all the time».
#   6. Проверяет что Shizuku теперь authorized (через Anubis UI).
#
# После этого можно запускать:  ./gradlew connectedDebugAndroidTest
#
# Использование:
#   scripts/setup-avd-shizuku.sh               — автонастройка
#   scripts/setup-avd-shizuku.sh --only-demon  — только перезапустить shizuku-demon
#                                                (повторяется после каждого ребута AVD)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
if [ ! -x "$ADB" ]; then
  ADB="$(command -v adb || true)"
fi
if [ -z "$ADB" ] || [ ! -e "$ADB" ]; then
  echo "❌ adb не найден. Укажи через ADB=/path/to/adb.exe" >&2
  exit 1
fi

SHIZUKU_PKG="moe.shizuku.privileged.api"
ANUBIS_PKG="sgnv.anubis.app.debug"
CACHE_DIR="${TMPDIR:-/tmp}/anubis-setup"
mkdir -p "$CACHE_DIR"

log() { echo "→ $*"; }

adb_shell() { "$ADB" shell "$@"; }

require_device() {
  local count
  count=$("$ADB" devices | awk 'NR>1 && /device$/ { n++ } END { print n+0 }')
  if [ "$count" -lt 1 ]; then
    echo "❌ нет подключённого устройства/эмулятора. Запусти AVD или подключи телефон." >&2
    exit 1
  fi
  if [ "$count" -gt 1 ]; then
    echo "⚠ найдено $count устройств, adb будет жаловаться. Отключи лишние или задай ANDROID_SERIAL." >&2
  fi
  log "adb видит устройство ✓"
}

install_shizuku_if_needed() {
  if adb_shell "pm list packages | grep -q $SHIZUKU_PKG"; then
    log "Shizuku уже установлен ✓"
    return
  fi
  log "Shizuku не найден — скачиваю latest release"
  local apk="$CACHE_DIR/shizuku.apk"
  local url
  url=$(curl -s https://api.github.com/repos/RikkaApps/Shizuku/releases/latest \
        | grep -oE '"browser_download_url":[^,]*apk' \
        | head -1 | cut -d'"' -f4)
  if [ -z "$url" ]; then
    echo "❌ не удалось получить URL Shizuku APK (github rate-limit?)" >&2
    exit 1
  fi
  curl -sL -o "$apk" "$url"
  "$ADB" install -r "$apk"
  log "Shizuku установлен ✓"
}

start_shizuku_demon() {
  # Если демон уже бежит — нечего делать.
  if adb_shell "pgrep -f shizuku_server > /dev/null"; then
    log "Shizuku-demon уже работает ✓"
    return
  fi
  # Путь к libshizuku.so внутри APK зависит от сборки (hash в имени директории).
  # Извлекаем его через pm path + заменяем base.apk на lib/<abi>/libshizuku.so.
  local apk_path lib_path abi
  apk_path=$(adb_shell "pm path $SHIZUKU_PKG" | head -1 | sed 's|package:||' | tr -d '\r')
  if [ -z "$apk_path" ]; then
    echo "❌ не найден pm path для $SHIZUKU_PKG" >&2
    exit 1
  fi
  # ABI: обычно x86_64 в эмуляторе на Intel/AMD; на ARM-телефонах arm64-v8a.
  abi=$(adb_shell "getprop ro.product.cpu.abi" | tr -d '\r')
  lib_path="${apk_path%/base.apk}/lib/${abi}/libshizuku.so"
  log "запускаю Shizuku: $lib_path"
  if ! adb_shell "$lib_path" | grep -q "starter exit with 0"; then
    echo "❌ shizuku-starter не завершился успешно" >&2
    exit 1
  fi
  log "Shizuku-demon запущен ✓"
}

install_anubis() {
  local apk
  apk=$(find "$PROJECT_ROOT/app/build/outputs/apk/debug" -name 'anubis-*-debug*.apk' | head -1)
  if [ -z "$apk" ]; then
    log "debug APK не найден — собираю через gradle"
    (cd "$PROJECT_ROOT" && ./gradlew :app:assembleDebug)
    apk=$(find "$PROJECT_ROOT/app/build/outputs/apk/debug" -name 'anubis-*-debug*.apk' | head -1)
  fi
  log "install $apk"
  "$ADB" install -r "$apk" > /dev/null
  log "Anubis установлен ✓"
}

ui_dump() {
  adb_shell "uiautomator dump /sdcard/ui.xml >/dev/null && cat /sdcard/ui.xml" | tr -d '\r'
}

# Находит bounds кликабельного элемента по точному тексту, возвращает "x y" центра.
# Пустая строка если элемент не найден.
find_center_by_text() {
  local target="$1"
  local xml bounds
  xml=$(ui_dump)
  bounds=$(echo "$xml" | tr '>' '\n' | grep -F "text=\"$target\"" | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"' | head -1)
  if [ -z "$bounds" ]; then
    return
  fi
  # bounds="[x1,y1][x2,y2]"
  local coords
  coords=$(echo "$bounds" | grep -oE '[0-9]+')
  # shellcheck disable=SC2206
  local arr=($coords)
  local cx=$(( (arr[0] + arr[2]) / 2 ))
  local cy=$(( (arr[1] + arr[3]) / 2 ))
  echo "$cx $cy"
}

tap_text() {
  local label="$1"
  local xy
  xy=$(find_center_by_text "$label")
  if [ -z "$xy" ]; then
    echo "❌ не нашёл на экране текст: $label" >&2
    return 1
  fi
  # shellcheck disable=SC2086
  adb_shell "input tap $xy"
  sleep 2
}

grant_shizuku_permission_for_anubis() {
  log "открываю Anubis и проверяю Shizuku permission"
  adb_shell "monkey -p $ANUBIS_PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"
  sleep 4
  # Закрываем update-dialog ТОЛЬКО если он видим — не делаем безусловный back,
  # иначе закроем Anubis и не увидим «Разрешить».
  local xml
  xml=$(ui_dump)
  if echo "$xml" | grep -qE 'text="(Пропустить|Скачать APK|Проверить и установить)"'; then
    log "прокручиваю update-dialog через «Пропустить»"
    tap_text "Пропустить" || adb_shell "input keyevent KEYCODE_BACK"
    sleep 1
    xml=$(ui_dump)
  fi

  # Если permission есть — на главном экране нет текста "Разрешить".
  if echo "$xml" | grep -q 'text="Разрешить"'; then
    log "Shizuku permission не выдан — тапаю «Разрешить»"
    tap_text "Разрешить" || return 1
    # Ждём Shizuku dialog; может вылететь «Allow all the time» или «Разрешить».
    local dialog_xml
    dialog_xml=$(ui_dump)
    if echo "$dialog_xml" | grep -q 'text="Allow all the time"'; then
      tap_text "Allow all the time"
    elif echo "$dialog_xml" | grep -q 'text="Разрешать всегда"'; then
      tap_text "Разрешать всегда"
    else
      echo "❌ Shizuku dialog не появился — проверь что демон запущен" >&2
      return 1
    fi
    log "Shizuku permission для Anubis выдан ✓"
  else
    log "«Разрешить» кнопки не видно — Shizuku permission уже выдан ✓"
  fi
}

main() {
  require_device
  if [ "${1:-}" = "--only-demon" ]; then
    start_shizuku_demon
    exit 0
  fi
  install_shizuku_if_needed
  start_shizuku_demon
  install_anubis
  grant_shizuku_permission_for_anubis
  echo
  echo "✅ AVD готов. Запускай: ./gradlew connectedDebugAndroidTest"
}

main "$@"
