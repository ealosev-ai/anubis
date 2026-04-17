package sgnv.anubis.app.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import sgnv.anubis.app.BuildConfig
import sgnv.anubis.app.IUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class FreezeMode {
    /** `pm disable-user --user 0` — шлёт PACKAGE_REMOVED, ломает иконки в папках Honor-лаунчера. */
    DISABLE_USER,

    /**
     * `pm suspend --user 0` — приложение запускается в диалог «приостановлено»,
     * PACKAGE_REMOVED НЕ шлётся. Цель: не разрушать структуру папок лаунчера
     * после заморозки. Требует Android 7+.
     */
    SUSPEND,
}

class ShizukuManager(private val packageManager: PackageManager) : ShellExec {

    override suspend fun runShell(vararg args: String): String? = runCommandWithOutput(*args)


    @Volatile
    private var userService: IUserService? = null

    /**
     * Способ заморозки. Меняется рантайм из настроек (SettingsScreen). Volatile —
     * чтобы freeze/unfreeze с разных потоков (Tile/Shortcut/VM) видели свежее значение.
     */
    @Volatile
    var freezeMode: FreezeMode = FreezeMode.DISABLE_USER

    private val _status = MutableStateFlow(ShizukuStatus.UNAVAILABLE)
    val status: StateFlow<ShizukuStatus> = _status

    private val _userServiceConnected = MutableStateFlow(false)
    val userServiceConnected: StateFlow<Boolean> = _userServiceConnected

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = IUserService.Stub.asInterface(binder)
            _userServiceConnected.value = userService != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            _userServiceConnected.value = false
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
        if (hasPermission()) {
            bindUserService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        userService = null
        _status.value = ShizukuStatus.UNAVAILABLE
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _status.value = ShizukuStatus.READY
                bindUserService()
            } else {
                _status.value = ShizukuStatus.NO_PERMISSION
            }
        }

    fun startListening() {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshStatus()
    }

    fun stopListening() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    fun refreshStatus() {
        _status.value = when {
            !isAvailable() -> ShizukuStatus.UNAVAILABLE
            !hasPermission() -> ShizukuStatus.NO_PERMISSION
            else -> ShizukuStatus.READY
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE)
    }

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            // Берём applicationId из BuildConfig, а не хардкод — чтобы debug-сборка
            // с applicationIdSuffix (".debug") тоже могла забиндить свой UserService через Shizuku.
            BuildConfig.APPLICATION_ID,
            UserService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("shell")
        .version(1)

    fun bindUserService() {
        if (userService != null) return
        if (!isAvailable() || !hasPermission()) return
        try {
            Shizuku.bindUserService(serviceArgs, serviceConnection)
        } catch (_: Exception) {
        }
    }

    fun unbindUserService() {
        try {
            Shizuku.unbindUserService(serviceArgs, serviceConnection, true)
        } catch (_: Exception) {
        }
        userService = null
        _userServiceConnected.value = false
    }

    /**
     * Ждёт, пока UserService действительно забиндится. Если уже готов — возвращает сразу.
     * Возвращает false если Shizuku недоступен/нет разрешения или тайм-аут.
     */
    suspend fun awaitUserService(timeoutMs: Long = 2000L): Boolean {
        if (userService != null) return true
        if (!isAvailable() || !hasPermission()) return false
        bindUserService()
        return withTimeoutOrNull(timeoutMs) {
            _userServiceConnected.first { it }
        } != null
    }

    /**
     * Для сценариев после загрузки: ждёт пока сам Shizuku-демон поднимется,
     * потом — пока UserService забиндится. Polling т.к. binder готовность приходит
     * через глобальные listener'ы, которые мы уже навешиваем в startListening().
     */
    suspend fun awaitShizukuReady(totalTimeoutMs: Long = 30_000L, pollMs: Long = 500L): Boolean {
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isAvailable() && hasPermission()) {
                return awaitUserService(timeoutMs = deadline - System.currentTimeMillis())
            }
            delay(pollMs)
        }
        return false
    }

    suspend fun execShellCommand(vararg args: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCommand(*args)
    }

    suspend fun forceStopApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCommand("am", "force-stop", packageName)
    }

    suspend fun freezeApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        when (freezeMode) {
            FreezeMode.DISABLE_USER -> runCommand("pm", "disable-user", "--user", "0", packageName)
            FreezeMode.SUSPEND -> runCommand("pm", "suspend", "--user", "0", packageName)
        }
    }

    suspend fun unfreezeApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        // На unfreeze делаем ОБЕ операции: приложение могло быть заморожено
        // в одном режиме, а разморозка прилетает в другом — всё равно должно
        // разморозиться. Ошибка одной команды не блокирует вторую.
        val enable = runCommand("pm", "enable", packageName)
        val unsuspend = runCommand("pm", "unsuspend", "--user", "0", packageName)
        if (enable.isSuccess || unsuspend.isSuccess) Result.success(Unit) else enable
    }

    fun isAppFrozen(packageName: String): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            if (!info.enabled) return true
            // В режиме SUSPEND flag `enabled` остаётся true, но приложение всё равно
            // «заморожено» с точки зрения пользователя — проверяем через isPackageSuspended.
            return try {
                packageManager.isPackageSuspended(packageName)
            } catch (_: Exception) {
                false
            }
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Run a shell command and return its stdout output.
     */
    suspend fun runCommandWithOutput(vararg args: String): String? = withContext(Dispatchers.IO) {
        try {
            val service = userService ?: return@withContext null
            service.execCommandWithOutput(args.toList().toTypedArray())
        } catch (e: Exception) {
            null
        }
    }

    private fun runCommand(vararg args: String): Result<Unit> {
        return try {
            val service = userService
                ?: return Result.failure(IllegalStateException("Shizuku UserService not connected"))
            val exitCode = service.execCommand(args.toList().toTypedArray())
            if (exitCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Command failed with exit code $exitCode"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val REQUEST_CODE = 1001
    }
}

enum class ShizukuStatus {
    UNAVAILABLE,
    NO_PERMISSION,
    READY
}
