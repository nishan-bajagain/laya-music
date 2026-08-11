package ca.ilianokokoro.umihi.music.core.managers

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateManagerVersionCompareTest {

    @Test
    fun equalVersionsAreNotNewer() {
        assertFalse(UpdateManager.isNewerVersion("v1.2.3", "v1.2.3"))
        assertFalse(UpdateManager.isNewerVersion("1.2.3", "1.2.3"))
    }

    @Test
    fun missingTrailingComponentsCountAsZero() {
        assertFalse(UpdateManager.isNewerVersion("v1.2", "v1.2.0"))
        assertFalse(UpdateManager.isNewerVersion("v1.2.0", "v1.2"))
        assertTrue(UpdateManager.isNewerVersion("v1.3", "v1.2.9"))
        assertTrue(UpdateManager.isNewerVersion("v1.2.3.0.1", "v1.2.3"))
    }

    @Test
    fun vPrefixIsIgnored() {
        assertFalse(UpdateManager.isNewerVersion("V1.2.3", "v1.2.3"))
        assertTrue(UpdateManager.isNewerVersion("v1.2.4", "V1.2.3"))
        assertFalse(UpdateManager.isNewerVersion("V1.2.3", "v1.2.4"))
    }

    @Test
    fun comparesComponentsNumerically() {
        assertTrue(UpdateManager.isNewerVersion("v1.10.0", "v1.2.0"))
        assertTrue(UpdateManager.isNewerVersion("v2.0.0", "v1.99.99"))
        assertFalse(UpdateManager.isNewerVersion("v1.2.0", "v1.10.0"))
    }

    @Test
    fun preReleaseAndBuildMetadataSuffixesAreHandled() {
        // A pre-release of a newer base version is newer than the old stable.
        assertTrue(UpdateManager.isNewerVersion("v1.0.4-rc1", "v1.0.3.1"))
        assertTrue(UpdateManager.isNewerVersion("v1.0.4+5", "v1.0.3.1"))
        // GitHub-style auto pre-release tag.
        assertTrue(UpdateManager.isNewerVersion("v1.0.4.0-0", "v1.0.3.1"))
        // Dotted pre-release suffix on a genuinely newer base version.
        assertTrue(UpdateManager.isNewerVersion("v1.2.3.4-rc.2", "v1.2.3"))
        // …but a pre-release never beats the stable release of the same version.
        assertFalse(UpdateManager.isNewerVersion("v1.0.4-rc1", "v1.0.4"))
        assertFalse(UpdateManager.isNewerVersion("v1.0.3.1", "v1.0.4-rc1"))
    }

    @Test
    fun nonNumericGarbageIsNeverNewer() {
        assertFalse(UpdateManager.isNewerVersion("abc", "v1.2.3"))
        assertFalse(UpdateManager.isNewerVersion("v1.2.3-beta", "v1.2.3"))
        assertFalse(UpdateManager.isNewerVersion("", "v1.2.3"))
        assertFalse(UpdateManager.isNewerVersion("v1.2.3", "not-a-version"))
        assertFalse(UpdateManager.isNewerVersion("v1..3", "v1.2.3"))
    }

    @Test
    fun parsesLatestReleaseJson() {
        val body = """
            {
              "tag_name": "v1.0.4.0",
              "body": "Release notes here",
              "assets": [
                {
                  "name": "some-other-file.txt",
                  "browser_download_url": "https://example.com/other"
                },
                {
                  "name": "laya-music-release.apk",
                  "browser_download_url": "https://example.com/laya-music-release.apk"
                }
              ]
            }
        """.trimIndent()

        val info = UpdateManager.parseRelease(body)
        assertEquals("v1.0.4.0", info?.version)
        assertEquals("https://example.com/laya-music-release.apk", info?.downloadUrl)
        assertEquals("Release notes here", info?.notes)
    }

    @Test
    fun missingApkAssetIsNotAnUpdate() {
        val body =
            """{"tag_name": "v1.0.4.0", "assets": [{"name": "other.txt", "browser_download_url": "https://example.com/other"}]}"""
        assertNull(UpdateManager.parseRelease(body))
    }
}
