package sgnv.anubis.app.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import sgnv.anubis.app.data.DefaultRestrictedApps
import sgnv.anubis.app.data.DefaultVpnOnlyApps
import sgnv.anubis.app.data.db.ManagedAppDao
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.InstalledAppInfo
import sgnv.anubis.app.data.model.ManagedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(
    private val dao: ManagedAppDao,
    private val context: Context
) : GroupsStore, PackageGroupsReader {
    override suspend fun getPackagesByGroup(group: AppGroup): Set<String> =
        dao.getPackageNamesByGroup(group).toSet()

    suspend fun getAllManagedPackages(): List<String> =
        AppGroup.entries.flatMap { getPackagesByGroup(it) }.distinct()

    override suspend fun getAppsByGroup(group: AppGroup): List<ManagedApp> =
        dao.getByGroup(group)

    suspend fun getAppGroup(packageName: String): AppGroup? =
        dao.get(packageName)?.group

    override suspend fun setAppGroup(packageName: String, group: AppGroup) {
        dao.insert(ManagedApp(packageName, group))
    }

    override suspend fun removeApp(packageName: String) {
        dao.delete(packageName)
    }

    /** Cycle through groups: none → LOCAL → VPN_ONLY → LAUNCH_VPN → none */
    suspend fun cycleGroup(packageName: String) {
        val current = dao.get(packageName)
        when (current?.group) {
            null -> dao.insert(ManagedApp(packageName, AppGroup.LOCAL))
            AppGroup.LOCAL -> dao.insert(ManagedApp(packageName, AppGroup.VPN_ONLY))
            AppGroup.VPN_ONLY -> dao.insert(ManagedApp(packageName, AppGroup.LAUNCH_VPN))
            AppGroup.LAUNCH_VPN -> dao.delete(packageName)
        }
    }

    /**
     * Seed для first-run и кнопки «Авто-выбор»: добавляет RU-приложения в
     * [AppGroup.LOCAL] (палят VPN → freeze при VPN on) и приложения
     * требующие VPN — в [AppGroup.VPN_ONLY] (freeze при VPN off).
     *
     * [restrictedPackages] — набор пакетов-кандидатов для LOCAL. По умолчанию
     * все из [DefaultRestrictedApps.packageNames]. UI может сузить до
     * отдельных категорий (банки/гос/только критичное).
     * [vpnOnlyPackages] — то же для VPN_ONLY.
     *
     * Уже управляемые пакеты не трогаем — иначе `insertAll(REPLACE)` перетёр бы
     * пользовательский выбор (например, он вручную положил YouTube в LAUNCH_VPN —
     * не надо откатывать обратно в VPN_ONLY). Также пакеты из
     * [DefaultRestrictedApps.neverRestrict] (активные IME) пропускаем всегда.
     */
    suspend fun autoSelectRestricted(
        restrictedPackages: Set<String> = DefaultRestrictedApps.packageNames,
        restrictedPrefixes: List<String> = DefaultRestrictedApps.prefixPatterns,
        vpnOnlyPackages: Set<String> = DefaultVpnOnlyApps.packageNames,
    ): Int {
        val installed = getInstalledPackageNames().toSet()
        val alreadyManaged = dao.getAllPackageNames().toSet()
        val candidates = installed - alreadyManaged - DefaultRestrictedApps.neverRestrict

        val newVpnOnly = candidates
            .filter { it in vpnOnlyPackages }
            .map { ManagedApp(it, AppGroup.VPN_ONLY) }
        val vpnOnlyPkgs = newVpnOnly.map { it.packageName }.toSet()

        val newRestricted = candidates
            .filter { it !in vpnOnlyPkgs }  // VPN_ONLY приоритет — не дублируем
            .filter { pkg ->
                pkg in restrictedPackages ||
                    restrictedPrefixes.any { pkg.startsWith(it) }
            }
            .map { ManagedApp(it, AppGroup.LOCAL) }

        dao.insertAll(newRestricted + newVpnOnly)
        return newRestricted.size + newVpnOnly.size
    }

    suspend fun countByGroup(group: AppGroup): Int = dao.countByGroup(group)

    suspend fun getInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
        )

        apps.map { appInfo ->
            val label = try {
                appInfo.loadLabel(pm).toString()
            } catch (e: Exception) {
                appInfo.packageName
            }
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val managed = dao.get(appInfo.packageName)
            InstalledAppInfo(
                packageName = appInfo.packageName,
                label = label,
                isSystem = isSystem,
                group = managed?.group,
                isDisabled = !appInfo.enabled
            )
        }.sortedBy { it.label.lowercase() }
    }

    private suspend fun getInstalledPackageNames(): Set<String> = withContext(Dispatchers.IO) {
        context.packageManager
            .getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
            .map { it.packageName }
            .toSet()
    }
}
