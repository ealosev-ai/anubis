package sgnv.anubis.app.data

/**
 * Куратируемый список RU-приложений, которые пользователь чаще всего хочет
 * изолировать от VPN-трафика. Используется кнопкой «Авто-выбор» в AppListScreen
 * и first-run seed'ом в AppRepository.
 *
 * Критерии включения:
 *  - Банки / финтех / платежи — по методичке Минцифры стучатся в локальные
 *    SOCKS5-порты и проверяют `tun0` через ConnectivityManager. Sber на Exodus
 *    Privacy — 5 трекеров (AppMetrica, AppsFlyer, Firebase, CrashLytics, HMS).
 *    RSHB (ru.rshb.dbo) — подтверждённый хит нашего honeypot 2026-04-16.
 *  - Госуслуги и смежные — тот же pipeline, явно следуют методичке.
 *  - Маркетплейсы с платёжной частью (WB Pay, Ozon Pay и т.п.).
 *  - Медиа / стриминг с RU-only контентом (Okko, Wink, IVI, Premier) —
 *    палят VPN чтобы отказать в воспроизведении при зарубежном IP.
 *  - Яндекс / VK / OK — большие трекинговые экосистемы.
 *  - Telecom — сливают всё подряд, включая геолокацию.
 *
 * Не включаем:
 *  - Мессенджеры (Telegram/WhatsApp/Viber) — пользователь часто хочет их через VPN.
 *  - Иностранные стриминги (Netflix/YouTube/Spotify) — пользователь через VPN и так.
 *  - Игры — редкая категория для VPN-контроля.
 *  - Браузеры (кроме Yandex Browser, который трекает).
 *
 * При расширении — добавлять в правильную категорию [Category], не в общий
 * набор. UI использует категории для кнопок «Только банки» / «Банки + гос» / «Всё».
 */
object DefaultRestrictedApps {

    /**
     * Банки, инвестиции, BNPL. Ядро целевой аудитории — именно из-за них
     * вся эта история и существует. Все пакеты подтверждены на GitHub-issues
     * Anubis/YourVPNDead или на форуме 4pda как известные VPN-детекторы.
     */
    val banks = setOf(
        "ru.sberbankmobile",           // СберБанк
        "ru.sberbank.sberbankid",      // Сбер ID
        "ru.sberbank.sbbol",           // СберБизнес
        "ru.rshb.dbo",                 // РСХБ Онлайн (honeypot hit confirmed)
        "ru.vtb24.mobilebanking.android",  // ВТБ Онлайн
        "ru.vtb.vtbinvestments",       // ВТБ Мои Инвестиции
        "ru.alfabank.mobile.android",  // Альфа-Банк
        "ru.alfadirect.client",        // Альфа-Инвестиции
        "ru.tinkoff.investing",        // Т-Инвестиции
        "ru.tcsbank.android",          // Т-Банк (legacy id)
        "com.idamob.tinkoff.android",  // Т-Банк (современный id)
        "ru.tbank.mobile",             // Т-Банк (ребрендинг 2024)
        "ru.tinkoff.bnpl",             // Долями (Т-BNPL)
        "ru.gazprombank.android.mobilebank.app",  // Газпромбанк
        "ru.sovcombank.app",           // Совкомбанк
        "ru.mkb.mobile",               // МКБ
        "ru.psb.ifl",                  // ПСБ
        "ru.pochtabank.android",       // Почта Банк
        "ru.raiffeisen.mobile.new",    // Райффайзенбанк
        "ru.openbank.mobile",          // Банк «Открытие»
        "ru.rosbank.android",          // Росбанк
        "ru.uniastrum",                // Юникредит / UniCredit Russia
        "com.akbars.mw",               // Ак Барс
    )

    /** Госуслуги, ФНС, налоги, Мои документы. Строго следуют методичке. */
    val government = setOf(
        "ru.gosuslugi.pos",            // Госуслуги
        "ru.gosuslugi.zkh",            // Госуслуги Дом
        "ru.mos.app",                  // Мои документы Москвы
        "ru.mos.polls",                // Активный гражданин
        "ru.fns.mytaxes",              // ФНС — Налоги ФЛ
        "ru.fns.billy",                // ФНС — Мой налог (самозанятые legacy)
        "com.gnivts.selfemployed",     // Мой налог (самозанятые new)
        "ru.emias.smart",              // ЕМИАС (медицина Москва)
        "com.octopod.russianpost.client.android",  // Почта России
        "ru.rzd.pass",                 // РЖД Пассажирам
        "ru.nspk.mirpay",              // Mir Pay
        "ru.nspk.sbpay",               // SberPay (НСПК)
    )

    /** Операторы связи — сливают геолокацию + трафик, поэтому freeze. */
    val telecom = setOf(
        "ru.mts.mymts",                // Мой МТС
        "ru.megafon.mlk",              // МегаФон
        "ru.beeline.services",         // Билайн
        "com.tele2.mytele2",           // Tele2
        "ru.yota.android",             // Yota
        "ru.rt.smarthome",             // Ростелеком Умный Дом
        "ru.rostel",                   // Ростелеком
        "ru.rostel.max",               // Ростелеком (другой id)
    )

    /** Маркетплейсы — каждый со своей платёжной частью, многие палят VPN. */
    val marketplaces = setOf(
        "com.wildberries.ru",          // Wildberries
        "ru.ozon.app.android",         // OZON
        "ru.beru.android",             // Яндекс.Маркет (legacy)
        "ru.megamarket.marketplace",   // Мегамаркет (Сбер)
        "com.avito.android",           // Авито
        "ru.lamoda.main",              // Lamoda
        "ru.sbcs.store",               // СберМаркет
    )

    /** Доставка еды и продуктов. */
    val delivery = setOf(
        "ru.foodfox.client",           // Яндекс.Еда
        "ru.yandex.eda",               // Яндекс.Еда (новый id)
        "com.deliveryclub",            // Delivery Club
        "ru.samokat.mobile",           // Самокат
        "ru.vkusvill.android",         // ВкусВилл
    )

    /** Соцсети, мессенджеры РФ-происхождения, почта. */
    val social = setOf(
        "com.vkontakte.android",       // VK
        "ru.ok.android",               // Одноклассники
        "com.vk.vkvideo",              // VK Видео
        "ru.vk.store",                 // RuStore
        "ru.oneme.app",                // MAX (мессенджер)
        "ru.oneme.max",
        "com.oneme.max",
        "ru.mail.mailapp",             // Mail
        "com.uma.musicvk",             // VK Музыка (legacy)
        "ru.dahl.messenger",           // Даль — ещё один RU-мессенджер
    )

    /** RU-медиа/стриминг. Палят VPN чтоб отказать в воспроизведении с non-RU IP. */
    val media = setOf(
        "ru.rutube.app",               // RUTUBE
        "ru.ivi.client",               // ivi
        "ru.okko.tv",                  // Okko
        "ru.mts.mtstv",                // KION (МТС ТВ)
        "ru.rt.video.app.mobile",      // Wink (Ростелеком)
        "ru.more.play",                // PREMIER
        "ru.mobileup.channelone",      // Первый канал
        "ru.litres.android",           // ЛитРес
    )

    /** Транспорт, шеринг, каршеринг. */
    val transport = setOf(
        "com.punicapp.whoosh",         // Whoosh (самокаты)
        "ru.urentbike.app",            // Urent (велосипеды)
        "com.citymobil.android",       // Ситимобил (ушёл, но может остаться)
    )

    /**
     * Яндекс-экосистема. Дублирует prefix `ru.yandex.` / `com.yandex.`, но явно
     * перечислено — чтобы UI показывал конкретные приложения.
     */
    val yandex = setOf(
        "com.yandex.browser",          // Я.Браузер
        "com.yandex.searchplugin",     // Я.Старт / Я.Приложение
        "com.yandex.mail",             // Я.Почта
        "ru.yandex.disk",              // Я.Диск
        "ru.yandex.market",            // Я.Маркет
        "ru.yandex.taxi",              // Я.Go (бывший такси)
        "ru.yandex.music",             // Я.Музыка
        "ru.yandex.yandexmaps",        // Я.Карты
        "ru.yandex.yandexnavi",        // Я.Навигатор
        "com.yandex.lavka",            // Я.Лавка
        "com.yandex.yamb",             // Я.Мессенджер
        "com.yandex.plus.home",        // Я.Плюс
        "com.yandex.bank",             // Я.Банк (Ozon Bank preview)
        "ru.yandex.realty",            // Я.Недвижимость
        "ru.kinopoisk",                // Кинопоиск
        "ru.zen.android",              // Дзен (отпочковался, но ещё алиасится)
    )

    /** Ритейл, электроника, одежда. */
    val retail = setOf(
        "ru.perekrestok.app",          // Перекрёсток
        "ru.tander.magnit",            // Магнит
        "ru.myspar",                   // SPAR
        "ru.mvideo.mobile",            // М.Видео
        "ru.dns_shop.new",             // DNS
        "ru.citilink.mobileapp",       // Ситилинк
    )

    /** Страхование. */
    val insurance = setOf(
        "ru.alfastrah.app",            // АльфаСтрахование
        "ru.ingos.mobile.insurance",   // Ингосстрах
        "ru.reso.client",              // РЕСО
    )

    /** Классифайды, объявления. */
    val classifieds = setOf(
        "com.cian.main",               // Циан (недвижимость)
        "ru.auto.ara",                 // Auto.ru
    )

    /** Прочее — то что не влезает в категории, но точно хочется freeze'ить. */
    val other = setOf(
        "ru.hh.android",               // hh.ru
        "ru.polypay.otk",              // Полипэй / лояльность
        "ru.dublgis.dgismobile",       // 2GIS (карты, палят геолок)
        "ru.palich.android",           // Палыч
        "ru.briz.rendezvous",          // Briz
        "com.setka",                   // Сетка
    )

    /**
     * Категория для UI. Кнопки «Авто-выбор → Банки» / «→ Банки+Гос» /
     * «→ Всё» маппятся на [Category.packages].
     */
    data class Category(val id: String, val label: String, val packages: Set<String>)

    val categories: List<Category> = listOf(
        Category("banks", "Банки и финтех", banks),
        Category("gov", "Госуслуги", government),
        Category("telecom", "Связь", telecom),
        Category("marketplaces", "Маркетплейсы", marketplaces),
        Category("delivery", "Доставка", delivery),
        Category("social", "Соцсети и почта", social),
        Category("media", "Медиа и стриминг", media),
        Category("transport", "Транспорт", transport),
        Category("yandex", "Яндекс", yandex),
        Category("retail", "Магазины", retail),
        Category("insurance", "Страхование", insurance),
        Category("classifieds", "Объявления", classifieds),
        Category("other", "Прочее", other),
    )

    /** Полный плоский набор — объединение всех категорий. */
    val packageNames: Set<String> = categories.fold(emptySet()) { acc, cat -> acc + cat.packages }

    /** Префиксы — любой пакет, начинающийся с этих строк, считается restricted. */
    val prefixPatterns = listOf(
        "com.yandex.",
        "ru.yandex.",
    )

    fun isKnownRestricted(packageName: String): Boolean {
        return packageName in packageNames || prefixPatterns.any { packageName.startsWith(it) }
    }
}
