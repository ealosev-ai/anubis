package sgnv.anubis.app.data

/**
 * Apps commonly pre-selected for the "No VPN" (restricted) group.
 * These are apps that users may want to isolate from VPN traffic.
 */
object DefaultRestrictedApps {

    val packageNames = setOf(
        // Government / state services
        "ru.gosuslugi.pos",
        "ru.mos.app",
        "ru.mos.polls",
        "ru.nspk.mirpay",

        // Banking
        "ru.sberbankmobile",
        "ru.sberbank.sberbankid",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "ru.tinkoff.investing",
        "ru.tcsbank.android",
        "com.idamob.tinkoff.android",

        // Telecom
        "ru.mts.mymts",
        "ru.megafon.mlk",
        "ru.beeline.services",
        "com.tele2.mytele2",
        "ru.rt.smarthome",
        "ru.rostel",

        // Social / messaging
        "com.vkontakte.android",
        "ru.ok.android",
        "com.vk.vkvideo",
        "ru.vk.store",
        "ru.oneme.app",
        "ru.oneme.max",
        "com.oneme.max",
        "ru.mail.mailapp",
        "com.uma.musicvk",
        "ru.dahl.messenger",

        // Marketplaces
        "com.wildberries.ru",
        "ru.ozon.app.android",
        "ru.beru.android",
        "ru.megamarket.marketplace",
        "com.avito.android",
        "ru.lamoda.main",
        "ru.sbcs.store",

        // Yandex
        "com.yandex.browser",
        "com.yandex.searchplugin",
        "ru.yandex.market",
        "ru.yandex.taxi",
        "ru.yandex.music",
        "ru.yandex.yandexmaps",
        "ru.zen.android",
        "ru.kinopoisk",

        // Food / delivery
        "ru.foodfox.client",

        // Maps / navigation
        "ru.dublgis.dgismobile",

        // Media
        "ru.rutube.app",

        // Retail / groceries
        "ru.perekrestok.app",
        "ru.tander.magnit",
        "ru.myspar",

        // Transport / mobility
        "com.punicapp.whoosh",

        // Insurance
        "ru.alfastrah.app",

        // Loyalty / payments
        "ru.polypay.otk",

        // Other
        "ru.hh.android",
        "com.cian.main",
        "ru.litres.android",
        "ru.rostel.max",
        "ru.palich.android",
        "ru.briz.rendezvous",
        "com.setka",
    )

    /** Prefix patterns — any package starting with these is likely restricted */
    val prefixPatterns = listOf(
        "com.yandex.",
        "ru.yandex.",
    )

    fun isKnownRestricted(packageName: String): Boolean {
        return packageName in packageNames || prefixPatterns.any { packageName.startsWith(it) }
    }
}
