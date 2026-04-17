package sgnv.anubis.app.audit

import android.content.pm.PackageManager

/**
 * uid → packageName без shell. Android PackageManager отдаёт эти мапинги
 * всем приложениям — Shizuku для pm list packages нужен только как fallback.
 */
interface PackageResolver {
    fun resolve(uid: Int): String?
}

class AndroidPackageResolver(
    private val pm: PackageManager,
) : PackageResolver {
    override fun resolve(uid: Int): String? {
        return runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
    }
}
