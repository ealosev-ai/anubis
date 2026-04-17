package sgnv.anubis.app.audit

import sgnv.anubis.app.shizuku.ShellExec

/**
 * Подменяет Shizuku shell в тестах. Мапа `(cmd prefix) → stdout` — удобнее
 * чем списки вызовов, потому что в логике разные команды приходят в
 * произвольном порядке. Первое совпадение по startsWith побеждает.
 */
class FakeShellExec(
    private val responses: MutableMap<String, String?> = mutableMapOf(),
) : ShellExec {

    val calls = mutableListOf<List<String>>()

    fun on(cmdPrefix: String, output: String?) {
        responses[cmdPrefix] = output
    }

    override suspend fun runShell(vararg args: String): String? {
        calls += args.toList()
        val joined = args.joinToString(" ")
        return responses.entries.firstOrNull { joined.startsWith(it.key) }?.value
    }
}
