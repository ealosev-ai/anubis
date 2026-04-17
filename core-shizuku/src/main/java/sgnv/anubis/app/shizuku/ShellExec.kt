package sgnv.anubis.app.shizuku

/**
 * Узкий интерфейс «выполнить shell-команду, получить stdout». Нужен чтобы отвязать
 * audit-логику (UidResolver) от [ShizukuManager] для юнит-тестов: подставим
 * fake-реализацию с заготовленным выводом /proc/net/tcp и pm list packages.
 *
 * Прод-реализация — [ShizukuManager.runCommandWithOutput]; адаптер ниже.
 */
interface ShellExec {
    /** Выполнить и вернуть stdout (или `"ERROR:..."` при сбое). */
    suspend fun runShell(vararg args: String): String?

    /**
     * Выполнить команду ради побочного эффекта (pm enable / am broadcast).
     * Success если exit code 0. Нужен VpnClientManager'у чтобы проверять
     * что команда реально выполнилась — raw stdout для этих случаев не нужен.
     */
    suspend fun execCommand(vararg args: String): Result<Unit>
}
