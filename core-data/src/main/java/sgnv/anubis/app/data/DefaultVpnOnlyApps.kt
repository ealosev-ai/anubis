package sgnv.anubis.app.data

/**
 * Куратируемый список приложений, которые **требуют VPN для работы** в РФ —
 * заблокированы Роскомнадзором либо сами палят non-RU IP для гео-ограничений.
 * Сидятся в группу [sgnv.anubis.app.data.model.AppGroup.VPN_ONLY] при тапе
 * «Авто-выбор» в AppListScreen.
 *
 * VPN_ONLY-поведение: приложение заморожено пока VPN off → пользователь не
 * успеет случайно открыть YouTube на RU-IP (получить "видео недоступно")
 * или запалить себя без VPN. Размораживается когда VPN up, либо при явном
 * «Рабочее окружение → Развернуть».
 *
 * Критерий включения:
 *  - Заблокирован РКН на уровне DPI (Instagram, Facebook, X/Twitter)
 *  - Замедлен до неработоспособности (YouTube — с 2024)
 *  - Требует non-RU IP для полной функциональности (Spotify, Netflix,
 *    LinkedIn — часть функций)
 *
 * Не включаем:
 *  - Мессенджеры работающие без VPN в РФ (Telegram, WhatsApp, Viber)
 *  - TikTok — работает на RU-IP, только выкладывать нельзя
 *  - VPN-клиенты сами (v2rayNG, Amnezia) — их нельзя помещать в VPN_ONLY,
 *    они должны запускаться раньше группы
 */
object DefaultVpnOnlyApps {

    /** Гугл-сервисы, замедленные/частично заблокированные. */
    val google = setOf(
        "com.google.android.youtube",          // YouTube
        "com.google.android.apps.youtube.music", // YouTube Music
        "com.google.android.apps.youtube.kids",  // YouTube Kids
    )

    /** Meta-экосистема — полная блокировка РКН. */
    val meta = setOf(
        "com.instagram.android",               // Instagram
        "com.instagram.threadsapp",            // Threads
        "com.facebook.katana",                 // Facebook
        "com.facebook.lite",                   // Facebook Lite
        "com.facebook.orca",                   // Messenger
    )

    /** Twitter/X — заблокирован РКН. */
    val twitter = setOf(
        "com.twitter.android",                 // X (Twitter)
        "com.twitter.android.lite",            // Twitter Lite
    )

    /** Стриминг — требуют не-RU IP или гео-блокировки. */
    val streaming = setOf(
        "com.spotify.music",                   // Spotify (ушёл из РФ)
        "com.netflix.mediaclient",             // Netflix (ушёл из РФ)
        "com.amazon.avod.thirdpartyclient",    // Prime Video
        "com.hbo.hbonow",                      // HBO Max
        "com.disney.disneyplus",               // Disney+
    )

    /** Профессиональные соцсети / запрещённые / ограниченные. */
    val professional = setOf(
        "com.linkedin.android",                // LinkedIn (заблокирован РКН)
    )

    /** Чат / discourse / форумы. */
    val chat = setOf(
        "com.discord",                         // Discord (замедлен РКН)
        "com.reddit.frontpage",                // Reddit
    )

    /** AI-чатботы — требуют зарубежного IP для регистрации / работы. */
    val ai = setOf(
        "com.openai.chatgpt",                  // ChatGPT
        "com.anthropic.claude",                // Claude
        "com.google.android.apps.bard",        // Gemini (ex-Bard)
        "com.perplexity.app.android",          // Perplexity
    )

    /** Категория для UI. Параллельная [DefaultRestrictedApps.Category]. */
    data class Category(val id: String, val label: String, val packages: Set<String>)

    val categories: List<Category> = listOf(
        Category("google", "Google-сервисы", google),
        Category("meta", "Meta (Instagram / Facebook)", meta),
        Category("twitter", "X / Twitter", twitter),
        Category("streaming", "Стриминг", streaming),
        Category("professional", "Профессиональные", professional),
        Category("chat", "Чаты и форумы", chat),
        Category("ai", "AI-чатботы", ai),
    )

    /** Полный плоский набор. */
    val packageNames: Set<String> = categories.fold(emptySet()) { acc, cat -> acc + cat.packages }

    fun isKnownVpnOnly(packageName: String): Boolean = packageName in packageNames
}
