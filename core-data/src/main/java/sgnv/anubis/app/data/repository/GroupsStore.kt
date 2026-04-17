package sgnv.anubis.app.data.repository

import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.ManagedApp

/**
 * Узкий контракт доступа к managed_apps для операций бекапа/рестора.
 * AppRepository его реализует, но тестам [GroupsBackup] не нужен ни
 * Context (для PackageManager), ни полный repository — fake реализация
 * этого интерфейса с in-memory Map достаточна.
 */
interface GroupsStore {
    suspend fun getAppsByGroup(group: AppGroup): List<ManagedApp>
    suspend fun setAppGroup(packageName: String, group: AppGroup)
    suspend fun removeApp(packageName: String)
}
