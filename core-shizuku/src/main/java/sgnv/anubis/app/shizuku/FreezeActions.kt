package sgnv.anubis.app.shizuku

/**
 * Узкий контракт «привилегированные действия над пакетами», который тянет
 * StealthOrchestrator (и друзья). ShizukuManager реализует его; в тестах
 * подсовывается fake-реализация — Context/Shizuku-демон не нужны.
 */
interface FreezeActions {
    fun isAvailable(): Boolean
    fun hasPermission(): Boolean
    fun isAppFrozen(packageName: String): Boolean
    fun isAppInstalled(packageName: String): Boolean
    suspend fun freezeApp(packageName: String): Result<Unit>
    suspend fun unfreezeApp(packageName: String): Result<Unit>
    suspend fun forceStopApp(packageName: String): Result<Unit>
}
