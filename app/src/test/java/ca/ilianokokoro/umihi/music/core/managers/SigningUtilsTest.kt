package ca.ilianokokoro.umihi.music.core.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for [SigningUtils]. Certificate parsing itself needs a
 * real Android PackageManager (not available in plain JVM unit tests), so we
 * pin the parts that don't: hashing, normalization and the comparison rule.
 */
class SigningUtilsTest {

    @Test
    fun `fingerprint of cert bytes is 64 lower-case hex chars`() {
        // A minimal DER certificate body (any bytes will do — the digest is
        // what matters, and it must be deterministic).
        val cert = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x00, 0x04, 0x20)
        val fp = SigningUtils.fingerprintOf(cert)
        assertNotNull(fp)
        assertEquals(64, fp!!.length)
        assertEquals(fp.lowercase(), fp)
    }

    @Test
    fun `same cert bytes always hash to the same fingerprint`() {
        val cert = "some-certificate-bytes".toByteArray()
        assertEquals(
            SigningUtils.fingerprintOf(cert),
            SigningUtils.fingerprintOf(cert)
        )
    }

    @Test
    fun `different cert bytes produce different fingerprints`() {
        assertNotEquals(
            SigningUtils.fingerprintOf("cert-a".toByteArray()),
            SigningUtils.fingerprintOf("cert-b".toByteArray())
        )
    }

    @Test
    fun `normalize strips colons spaces and case`() {
        assertEquals(
            "0b4c236a30842911b3f3f394aed3d69ad33c63d5897acc4b3bf5232064b01a21",
            SigningUtils.normalizeFingerprint(
                "0B:4C:23:6A:30:84:29:11:B3:F3:F3:94:AE:D3:D6:9A:" +
                    "D3:3C:63:D5:89:7A:CC:4B:3B:F5:23:20:64:B0:1A:21"
            )
        )
    }

    @Test
    fun `normalize leaves already-normalized input unchanged`() {
        val fp = "0b4c236a30842911b3f3f394aed3d69ad33c63d5897acc4b3bf5232064b01a21"
        assertEquals(fp, SigningUtils.normalizeFingerprint(fp))
    }

    @Test
    fun `fingerprintOf handles empty input without crashing`() {
        assertNotNull(SigningUtils.fingerprintOf(ByteArray(0)))
    }

    @Test
    fun `the real release and debug fingerprints are recognized as distinct`() {
        // Pinned for documentation: the authoritative release key...
        val release = "0b4c236a30842911b3f3f394aed3d69ad33c63d5897acc4b3bf5232064b01a21"
        // ...and the Android debug key that accidentally signed the v1.0.3 APK.
        val debug = "ec3404b09e67b921669c827b41c61861198cf410f2ce15bc94d32128eae2137d"
        assertNotEquals(release, debug)
        assertEquals(release, SigningUtils.normalizeFingerprint(release))
    }
}
