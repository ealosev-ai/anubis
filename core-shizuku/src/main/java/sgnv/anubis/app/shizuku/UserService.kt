package sgnv.anubis.app.shizuku

import sgnv.anubis.app.IUserService
import java.io.InputStream
import java.util.concurrent.TimeUnit

class UserService : IUserService.Stub() {

    override fun destroy() {
        // Shizuku рвёт процесс сам, чистить нечего.
    }

    override fun execCommand(command: Array<String>): Int {
        val process = Runtime.getRuntime().exec(command)
        return try {
            // Потоки надо дренировать даже когда вывод нам не нужен:
            // иначе pm/am залипнут на переполненной pipe.
            val stdout = drainAsync(process.inputStream)
            val stderr = drainAsync(process.errorStream)
            if (!process.waitFor(EXEC_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                return TIMEOUT_EXIT_CODE
            }
            stdout.join(JOIN_TIMEOUT_MS)
            stderr.join(JOIN_TIMEOUT_MS)
            process.exitValue()
        } finally {
            process.destroyForcibly()
        }
    }

    override fun execCommandWithOutput(command: Array<String>): String {
        val process = Runtime.getRuntime().exec(command)
        return try {
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
            val stdout = drainAsync(process.inputStream, stdoutBuf)
            val stderr = drainAsync(process.errorStream, stderrBuf)
            val finished = process.waitFor(EXEC_TIMEOUT_SEC, TimeUnit.SECONDS)
            stdout.join(JOIN_TIMEOUT_MS)
            stderr.join(JOIN_TIMEOUT_MS)
            when {
                !finished -> "ERROR:timeout after ${EXEC_TIMEOUT_SEC}s"
                stderrBuf.isNotEmpty() -> "ERROR:$stderrBuf"
                else -> stdoutBuf.toString()
            }
        } finally {
            process.destroyForcibly()
        }
    }

    private fun drainAsync(stream: InputStream, sink: StringBuilder? = null): Thread {
        val thread = Thread {
            try {
                val buf = ByteArray(4096)
                var total = 0
                stream.use { input ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        if (sink != null && total < MAX_OUTPUT_BYTES) {
                            val room = MAX_OUTPUT_BYTES - total
                            val take = if (read <= room) read else room
                            sink.append(String(buf, 0, take))
                        }
                        total += read
                    }
                }
            } catch (_: Exception) {
                // Процесс прибили — обычное дело, молча выходим.
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private companion object {
        const val EXEC_TIMEOUT_SEC = 15L
        const val JOIN_TIMEOUT_MS = 500L
        const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024  // 2 MiB — dumpsys connectivity и /proc/net/tcp под нагрузкой влезают
        const val TIMEOUT_EXIT_CODE = 124  // совместимо с coreutils `timeout`
    }
}
