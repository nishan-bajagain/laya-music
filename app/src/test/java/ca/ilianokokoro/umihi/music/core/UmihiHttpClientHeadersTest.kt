package ca.ilianokokoro.umihi.music.core

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for the per-host image-request header policy. The profile
 * avatar is served from Google's account-photo CDN (lh3.googleusercontent.com)
 * and has no i.ytimg.com fallback, so it must never be sent with the
 * cross-site music.youtube.com Origin/Referer that can get it rejected with a
 * 403/400 — while YouTube's own thumbnail CDNs keep those headers so the
 * artwork regression they were added to fix cannot come back.
 */
class UmihiHttpClientHeadersTest {

    @Test
    fun youtubeThumbnailCdnsKeepTheCrossSiteHeaders() {
        assertTrue(isYouTubeImageCdn("i.ytimg.com"))
        assertTrue(isYouTubeImageCdn("yt3.ggpht.com"))
        // Sibling subdomains of the same YouTube CDNs (e.g. i1.ytimg.com)
        // must also keep the headers.
        assertTrue(isYouTubeImageCdn("i1.ytimg.com"))
        assertTrue(isYouTubeImageCdn("yt4.ggpht.com"))
    }

    @Test
    fun googleAccountPhotoCdnNeverGetsTheCrossSiteHeaders() {
        // lh3.googleusercontent.com hosts the account avatar — the exact
        // request that was failing. No Origin/Referer for it.
        assertFalse(isYouTubeImageCdn("lh3.googleusercontent.com"))
    }

    @Test
    fun unrelatedHostsNeverGetTheCrossSiteHeaders() {
        assertFalse(isYouTubeImageCdn("example.com"))
        assertFalse(isYouTubeImageCdn("googleusercontent.com"))
        assertFalse(isYouTubeImageCdn(""))
    }
}
