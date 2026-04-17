package sgnv.anubis.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerParseTest {

    @Test
    fun parses_normal_release_with_apk_and_sha256() {
        val body = """
        {
          "tag_name": "v0.2.0",
          "html_url": "https://github.com/ealosev-ai/anubis/releases/tag/v0.2.0",
          "body": "Hotfix audit-logs.\n\nSHA-256: a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890",
          "assets": [
            {"name": "anubis-0.2.0-release.apk", "browser_download_url": "https://github.com/x/y/anubis.apk"},
            {"name": "anubis-0.2.0-debug.apk",   "browser_download_url": "https://github.com/x/y/debug.apk"}
          ]
        }
        """.trimIndent()

        val info = UpdateChecker.parseReleaseJson(body, currentVersion = "0.1.1")
        assertNotNull(info)
        requireNotNull(info)

        assertEquals("0.2.0", info.latestVersion)
        assertEquals("0.1.1", info.currentVersion)
        assertEquals("https://github.com/x/y/anubis.apk", info.apkUrl)
        // debug-ассет должен быть отфильтрован
        assertTrue(!info.apkUrl!!.contains("debug"))
        // SHA-256 извлечён и приведён к lowercase
        assertEquals(
            "a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890",
            info.apkSha256,
        )
        assertTrue(info.isUpdateAvailable)
    }

    @Test
    fun parses_sha256_with_dashed_prefix_and_uppercase_hex() {
        val body = """
        {
          "tag_name": "v0.2.0",
          "body": "Sha-256: AAAA1111BBBB2222CCCC3333DDDD4444EEEE5555FFFF6666AAAA1111BBBB2222",
          "assets": []
        }
        """.trimIndent()

        val info = UpdateChecker.parseReleaseJson(body, currentVersion = "0.1.0")
        assertNotNull(info)
        assertEquals(
            "регистр не важен, результат всегда lowercase",
            "aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666aaaa1111bbbb2222",
            info!!.apkSha256,
        )
    }

    @Test
    fun returns_null_apk_when_no_release_apk_in_assets() {
        val body = """
        {
          "tag_name": "v0.2.0",
          "body": "No APK this time, only source code.",
          "assets": [
            {"name": "source.zip", "browser_download_url": "https://github.com/x.zip"}
          ]
        }
        """.trimIndent()

        val info = UpdateChecker.parseReleaseJson(body, currentVersion = "0.1.1")
        assertNotNull(info)
        assertNull("нет .apk — apkUrl должен быть null", info!!.apkUrl)
    }

    @Test
    fun returns_null_sha_when_release_notes_have_no_hash() {
        val body = """
        {
          "tag_name": "v0.2.0",
          "body": "Just a release, no hash mentioned.",
          "assets": []
        }
        """.trimIndent()

        val info = UpdateChecker.parseReleaseJson(body, currentVersion = "0.1.0")
        assertNull("хэша в body нет — SHA-256 null", info!!.apkSha256)
    }

    @Test
    fun ignores_malformed_sha256_that_is_too_short() {
        val body = """
        {
          "tag_name": "v0.2.0",
          "body": "SHA-256: abc123",
          "assets": []
        }
        """.trimIndent()

        val info = UpdateChecker.parseReleaseJson(body, currentVersion = "0.1.0")
        // 64-hex-char guard отсеивает короткие строки
        assertNull(info!!.apkSha256)
    }

    @Test
    fun returns_null_on_garbage_json() {
        assertNull(UpdateChecker.parseReleaseJson("not json at all", "0.1.0"))
        assertNull(UpdateChecker.parseReleaseJson("{}", "0.1.0"))  // нет tag_name
        assertNull(UpdateChecker.parseReleaseJson("""{"tag_name": ""}""", "0.1.0"))
    }

    @Test
    fun version_comparison_detects_newer_correctly() {
        // Частые regressions: prefix 'v', разные lengths, строка vs число.
        assertEquals(1, UpdateInfo.compareVersions("0.2.0", "0.1.1"))
        assertEquals(1, UpdateInfo.compareVersions("v0.2.0", "0.1.1"))
        assertEquals(0, UpdateInfo.compareVersions("0.1.1", "0.1.1"))
        assertEquals(-1, UpdateInfo.compareVersions("0.1.0", "0.1.1"))
        assertEquals(1, UpdateInfo.compareVersions("1.0.0", "0.99.99"))
        // Разная длина — недостающее = 0
        assertEquals(1, UpdateInfo.compareVersions("0.2", "0.1.9"))
        assertEquals(0, UpdateInfo.compareVersions("0.1", "0.1.0"))
    }

    @Test
    fun isUpdateAvailable_flag_matches_comparison() {
        val info = UpdateChecker.parseReleaseJson(
            """{"tag_name":"v0.1.0","body":"","assets":[]}""",
            currentVersion = "0.1.1",
        )!!
        assertTrue("0.1.0 < 0.1.1 — обновления нет", !info.isUpdateAvailable)
    }
}
