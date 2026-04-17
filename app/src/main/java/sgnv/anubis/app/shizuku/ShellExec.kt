package sgnv.anubis.app.shizuku

/**
 * Узкий интерфейс «выполнить shell-команду, получить stdout». Нужен чтобы отвязать
 * audit-логику (UidResolver) от [ShizukuManager] для юнит-тестов: подставим
 * fake-реализацию с заготовленным выводом /proc/net/tcp и pm list packages.
 *
 * Прод-реализация — [ShizukuManager.runCommandWithOutput]; адаптер ниже.
 */
interface ShellExec {
    suspend fun runShell(vararg args: String): String?
}
