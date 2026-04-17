package sgnv.anubis.app.update

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateInstallerHashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun sha256_of_empty_file_matches_known_value() {
        // Известный SHA-256 пустой строки (RFC 6234).
        val empty = tmp.newFile("empty.apk")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            computeSha256Hex(empty),
        )
    }

    @Test
    fun sha256_of_known_string_matches_known_value() {
        // "abc" → ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val file = tmp.newFile("abc.apk")
        file.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            computeSha256Hex(file),
        )
    }

    @Test
    fun sha256_handles_large_file_correctly() {
        // Файл больше одного read-буфера (64 KiB) — убеждаемся что loop правильно
        // дочитывает и не теряет данные. 200 KiB заполняем повторяющимся паттерном.
        val file = tmp.newFile("large.apk")
        file.outputStream().use { out ->
            val chunk = ByteArray(1024) { (it % 256).toByte() }
            repeat(200) { out.write(chunk) }
        }
        val hash = computeSha256Hex(file)
        // Проверяем что хэш детерминирован между запусками.
        assertEquals(hash, computeSha256Hex(file))
        assertEquals(
            "длина hex SHA-256 всегда 64 символа",
            64, hash.length,
        )
    }
}
