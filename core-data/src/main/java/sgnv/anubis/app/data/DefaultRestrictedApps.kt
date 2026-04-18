package sgnv.anubis.app.data

/**
 * Куратируемый список RU-приложений для автоматической группировки в LOCAL
 * («палят VPN»). Используется кнопкой «Авто-выбор» в AppListScreen и first-run
 * seed'ом в AppRepository.
 *
 * Философия: **"лучше перестраховаться"**. Мы точно не знаем, какие приложения
 * активно сканят 127.0.0.1 в поисках SOCKS5 — это чёрный ящик с обратной
 * стороны APK. Но точно знаем что:
 *  - банки, гос, платежи → по методичке Минцифры сканят (RSHB подтверждён honeypot'ом)
 *  - RU-маркетплейсы и лоялки → сливают геолокацию и могут триггерить на non-RU IP
 *  - RU-стриминг → проверяют RU-IP для доступа к контенту
 *  - Yandex/VK-экосистема → большие трекинговые контуры
 *
 * False-positive (freeze безобидного приложения) стоит копейки — иконка
 * пропадает с рабочего стола. False-negative (не заморозили сканера) = VPN
 * спалился. Поэтому когда сомнения — добавляем.
 *
 * Не включаем только:
 *  - мессенджеры общего назначения (Telegram/WhatsApp/Viber — юзер их через VPN)
 *  - иностранные (они в [DefaultVpnOnlyApps])
 *  - игры и developer-утилиты (4pda, Pikabu — не сливают критичного)
 *  - VPN-клиенты (они запускаются ДО группы)
 */
object DefaultRestrictedApps {

    /** Банки, инвестиции, BNPL, банк-карты, карты лояльности с платёжной частью. */
    val banks = setOf(
        // Сбер
        "ru.sberbankmobile",
        "ru.sberbank.sberbankid",
        "ru.sberbank.sbbol",            // СберБизнес
        "ru.sber.telecom",              // Сбер Мобайл
        // ВТБ
        "ru.vtb24.mobilebanking.android",
        "ru.vtb.vtbinvestments",
        // Альфа
        "ru.alfabank.mobile.android",
        "ru.alfadirect.client",
        // Т-Банк / Тинькофф
        "ru.tinkoff.investing",
        "ru.tcsbank.android",
        "com.idamob.tinkoff.android",
        "ru.tbank.mobile",
        "ru.tinkoff.bnpl",              // Долями
        // Газпромбанк
        "ru.gazprombank.android.mobilebank.app",
        // Россельхозбанк — honeypot hit confirmed
        "ru.rshb.dbo",
        // Совкомбанк + Халва
        "ru.sovcombank.app",
        "ru.sovcombank.dms",
        "ru.sovcomcard.halva.v1",
        // МКБ, ПСБ, Почта Банк, Райффайзен, Открытие, Росбанк, Ак Барс
        "ru.mkb.mobile",
        "ru.psb.ifl",
        "ru.pochtabank.android",
        "ru.raiffeisen.mobile.new",
        "ru.openbank.mobile",
        "ru.rosbank.android",
        "com.akbars.mw",
        // Региональные
        "ru.bankuralsib.mb.android",    // Уралсиб
        // Карты лояльности с функциями платежа
        "ru.cardsmobile.mw3",           // Кошелёк
    )

    /** Госуслуги, ФНС, муниципалы, платные дороги, парковки. */
    val government = setOf(
        // Госуслуги
        "ru.gosuslugi.pos",
        "ru.gosuslugi.zkh",             // Госуслуги Дом
        "ru.gosuslugi.auto",            // Госуслуги Авто
        "ru.gosuslugi.goskey",          // ГосКлюч
        // Москва
        "ru.mos.app",                   // Моя Москва
        "ru.mos.polls",                 // Активный гражданин
        "ru.mos.myid",                  // Мой ID Москвы
        "ru.altarix.mos.pgu",           // Москва ПГУ
        "ru.mosgorpass",                // МосГорПасс
        "ru.mosoblgaz",                 // МосОблГаз
        "ru.mosparking.appnew",         // МосПаркинг
        "ru.spb.parking",               // СПб Паркинг
        // Федеральные
        "ru.fns.mytaxes",               // ФНС Налоги ФЛ
        "ru.fns.lkfl",                  // ЛК Налогоплательщика
        "ru.fns.billy",
        "com.gnivts.selfemployed",      // Мой налог (самозанятые)
        "ru.emias.smart",               // ЕМИАС
        // Транспорт / дороги / почта
        "ru.russianhighways.mobile",    // Автодор (платные дороги)
        "com.octopod.russianpost.client.android",  // Почта России
        "ru.rzd.pass",                  // РЖД
        "ru.tutu.etrains",              // Туту.Электрички
        // Платёжные
        "ru.nspk.mirpay",
        "ru.nspk.sbpay",
    )

    /** Операторы связи. */
    val telecom = setOf(
        "ru.mts.mymts",
        "ru.megafon.mlk",
        "ru.beeline.services",
        "com.tele2.mytele2",
        "ru.yota.android",
        "ru.rt.smarthome",
        "ru.rostel",
        "ru.rostel.max",
    )

    /**
     * Маркетплейсы, универсамы, DIY, мебель — всё где есть платёжная
     * часть и/или геолокация. Приоритет — максимальный охват: падает
     * иконка в лаунчере безопаснее чем палим VPN.
     */
    val marketplaces = setOf(
        // Большие маркетплейсы
        "com.wildberries.ru",
        "ru.ozon.app.android",
        "ru.ozon.select",
        "ru.beru.android",              // Я.Маркет legacy
        "ru.megamarket.marketplace",
        "com.avito.android",
        "ru.lamoda.main",
        "ru.sbcs.store",                // СберМаркет / Купер
        "ru.instamart",                 // Купер (бывший Instamart)
        // Универсамы / продукты
        "ru.lenta.lentochka",
        "ru.globus.app",
        "ru.myauchan.droid",            // Ашан
        "ru.reksoft.okey",              // O'KEY
        "ru.perekrestok.app",
        "ru.tander.magnit",
        "ru.myspar",
        "ru.pyaterochka.app.browser",   // Пятёрочка
        "ru.vkusvill",
        "ru.vkusvill.android",
        "ru.winelab",
        // Одежда / обувь / ювелирка
        "ru.letu",                      // Л'Этуаль
        "ru.sportmaster.app",
        "ru.sunlight.sunlight",
        "ru.sokolov.android",
        "ru.zolotoy585.customer",       // 585 Золотой
        // DIY / мебель / электроника
        "ru.hoff.app",                  // Хофф
        "ru.askonaapp.android",         // Аскона
        "ru.mvideo.mobile",
        "ru.filit.mvideo.b2c",          // М.Видео B2C
        "ru.dns.shop.android",
        "ru.dns_shop.new",
        "ru.citilink",
        "ru.citilink.mobileapp",
        "ru.onlinetrade.app",
        // Авто
        "ru.autodoc.autodocapp",        // АвтоДок (запчасти)
    )

    /** Аптеки и медицина — сливают состояние здоровья + геолок. */
    val healthcare = setOf(
        "ru.apteka",
        "ru.getpharma.eapteka",         // eapteka
        "ru.neopharm.stolichki",        // Столички
        "ru.uteka.app",
        "ru.zdravcity.app",
        "ru.smclinic.app.lk",           // СМ-Клиника
        "ru.smclinic.lk_android",
    )

    /** Доставка еды и фастфуд. */
    val delivery = setOf(
        "ru.foodfox.client",
        "ru.yandex.eda",
        "com.deliveryclub",
        "ru.samokat.mobile",
        "ru.dodopizza.app",             // Додо
        "ru.kfc.kfc_delivery",          // KFC
        "ru.burgerking",                // Burger King
    )

    /** Соцсети, мессенджеры RU, почта, знакомства. */
    val social = setOf(
        // VK экосистема
        "com.vkontakte.android",
        "com.vk.im",                    // VK Мессенджер (отдельный APK)
        "com.vk.vkvideo",
        "com.vk.love",                  // ВК Знакомства
        "com.uma.musicvk",
        "ru.vk.store",                  // RuStore
        // Одноклассники
        "ru.ok.android",
        // MAX (VK-мессенджер ребрендинг)
        "ru.oneme.app",
        "ru.oneme.max",
        "com.oneme.max",
        // Mail
        "ru.mail.mailapp",
        "ru.mail.cloud",
        // Прочие RU-мессенджеры
        "ru.dahl.messenger",
    )

    /** RU-медиа/стриминг — палят VPN чтоб отказать в воспроизведении с non-RU IP. */
    val media = setOf(
        "ru.rutube.app",
        "ru.ivi.client",
        "ru.okko.tv",
        "ru.mts.mtstv",                 // KION
        "ru.rt.video.app.mobile",       // Wink
        "ru.more.play",                 // PREMIER
        "ru.mobileup.channelone",
        "ru.start.androidmobile",       // START
        "ru.litres.android",
        "ru.plus.bookmate",             // Bookmate (Я.Плюс)
    )

    /** Транспорт, шеринг, каршеринг. */
    val transport = setOf(
        "com.punicapp.whoosh",
        "ru.urentbike.app",
        "com.citymobil.android",
        "ru.belkacar.belkacar",         // BelkaCar
    )

    /**
     * Яндекс-экосистема. Частично матчится через prefix `ru.yandex.` /
     * `com.yandex.` — но явный список помогает UI показать конкретные имена.
     */
    val yandex = setOf(
        "com.yandex.browser",
        "com.yandex.searchplugin",
        "com.yandex.searchapp",
        "com.yandex.aliceapp",
        "com.yandex.mail",
        "com.yandex.lavka",
        "com.yandex.yamb",
        "com.yandex.plus.home",
        "com.yandex.bank",
        "com.yandex.iot",
        "com.yandex.mobile.drive",
        "com.yandex.shedevrus",
        "ru.yandex.disk",
        "ru.yandex.market",
        "ru.yandex.taxi",
        "ru.yandex.music",
        "ru.yandex.yandexmaps",
        "ru.yandex.yandexnavi",
        "ru.yandex.metro",
        "ru.yandex.realty",
        "ru.yandex.mobile.afisha",
        "ru.yandex.mobile.gasstations",
        "ru.yandex.travel",
        "ru.yandex.translate",
        "ru.yandex.weatherplugin",
        "ru.yandex.androidkeyboard",
        "ru.yandex.key",
        "ru.kinopoisk",
        "ru.zen.android",
    )

    /** АЗС — сливают геолок, лояльность, реестр топлива. */
    val fuel = setOf(
        "ru.pichesky.rosneft",              // Роснефть
        "ru.serebryakovas.lukoilmobileapp", // Лукойл
    )

    /** Страхование. */
    val insurance = setOf(
        "ru.alfastrah.app",
        "ru.ingos.ingomobile",
        "ru.ingos.mobile.insurance",
        "ru.reso.client",
    )

    /** Классифайды, объявления, недвижимость. */
    val classifieds = setOf(
        "com.cian.main",
        "ru.auto.ara",
    )

    /** Прочее RU-происхождения с трекерами / геолокой. */
    val other = setOf(
        "ru.hh.android",
        "ru.polypay.otk",
        "ru.dublgis.dgismobile",            // 2GIS
        "ru.afisha.android",                // Афиша
        "ru.chitaigorod.mobile",            // Читай-город
        "ru.moremania.techboss",
        "ru.webinar.mobile",
        "ru.obshchinaru.obshchina",
        "ru.palich.android",
        "ru.briz.rendezvous",
        "com.setka",
    )

    /**
     * Категория для UI. Кнопки «Авто-выбор → Банки» / «→ Банки+Гос» /
     * «→ Всё» маппятся на [Category.packages].
     */
    data class Category(val id: String, val label: String, val packages: Set<String>)

    val categories: List<Category> = listOf(
        Category("banks", "Банки и финтех", banks),
        Category("gov", "Госуслуги / муниципалы", government),
        Category("telecom", "Связь", telecom),
        Category("marketplaces", "Маркетплейсы и магазины", marketplaces),
        Category("healthcare", "Аптеки и клиники", healthcare),
        Category("delivery", "Доставка / фастфуд", delivery),
        Category("social", "Соцсети и почта", social),
        Category("media", "Медиа и стриминг (RU)", media),
        Category("transport", "Транспорт / шеринг", transport),
        Category("yandex", "Яндекс", yandex),
        Category("fuel", "АЗС", fuel),
        Category("insurance", "Страхование", insurance),
        Category("classifieds", "Объявления", classifieds),
        Category("other", "Прочее", other),
    )

    /** Полный плоский набор. */
    val packageNames: Set<String> = categories.fold(emptySet()) { acc, cat -> acc + cat.packages }

    /** Префиксы — любой пакет начинающийся с них = restricted. */
    val prefixPatterns = listOf(
        "com.yandex.",
        "ru.yandex.",
    )

    /**
     * Явные исключения из авто-выбора — приложения, заморозка которых сломает
     * UX устройства. Даже если пакет матчится по prefix или попал в какой-то
     * set, мы его НЕ включаем.
     *
     * Главная категория — input methods (клавиатуры): без активной IME
     * пользователь физически не сможет печатать когда VPN on. Android не
     * переключается автоматически на другую клавиатуру если текущая замёрзла —
     * показывает пустое место и тихо страдает.
     */
    val neverRestrict = setOf(
        // Активные IME пользователя — не морозим, иначе не сможет печатать.
        // Google Gboard: телеметрия есть, но через Firebase (не AppMetrica),
        // банки этот канал не видят. Свайп у Gboard лучший из доступных.
        "com.google.android.inputmethod.latin",
        "com.google.android.inputmethod.pinyin",
        // FUTO Keyboard (платная, keyboard.futo.org) — ML on-device, без сети.
        "org.futo.inputmethod.latin",
        // OEM IME — last-resort fallback если Android при заморозке user-IME
        // откатится к стоковому Honor/Huawei/Samsung keyboard'у.
        "com.hihonor.ims",                  // Honor Input
        "com.huawei.ims",                   // Huawei Input
        "com.samsung.android.honeyboard",   // Samsung Keyboard
        // Яндекс.Клавиатура (ru.yandex.androidkeyboard) НЕ в whitelist —
        // сливает VPN-статус через AppMetrica (тот же device_id что знают
        // Сбер/РСХБ/Газпромбанк). Не использовать.
    )

    fun isKnownRestricted(packageName: String): Boolean {
        if (packageName in neverRestrict) return false
        return packageName in packageNames || prefixPatterns.any { packageName.startsWith(it) }
    }
}
