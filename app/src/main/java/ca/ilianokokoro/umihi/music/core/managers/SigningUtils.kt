package ca.ilianokokoro.umihi.music.core.managers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import java.io.File
import java.security.MessageDigest

/**
 * Compares the signing certificate of the currently installed app with the
 * certificate of a downloaded update APK — *without* installing it.
 *
 * Android only allows an APK to be installed over an existing one when both
 * are signed by the same certificate. When they differ (e.g. a v1.0.3 build
 * that was accidentally signed with the debug key), the system rejects the
 * install with a bare *"App not installed as package conflicts with an
 * existing package"* error. [signaturesMatch] lets the app detect that
 * situation up front so it can guide the user instead of showing the raw
 * system error.
 */
object SigningUtils {

    /** SHA-256 of an X.509 certificate's DER bytes, lower-case hex, no separators. */
    fun fingerprintOf(cert: ByteArray): String? =
        try {
            MessageDigest.getInstance("SHA-256")
                .digest(cert)
                .joinToString("") { byte -> "%02x".format(byte) }
        } catch (e: Exception) {
            LogHelper.printe("SigningUtils: failed to hash certificate", exception = e)
            null
        }

    /** Normalizes a fingerprint for comparison: lower-case, strips `:` and spaces. */
    fun normalizeFingerprint(raw: String): String =
        raw.lowercase().replace(":", "").replace(" ", "").trim()

    /**
     * SHA-256 fingerprint of the certificate that signed the *currently
     * installed* APK. `null` if it cannot be determined.
     */
    fun installedSigningFingerprint(context: Context): String? =
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            cert?.let(::fingerprintOf)
        } catch (e: Exception) {
            LogHelper.printe("SigningUtils: failed to read installed certificate", exception = e)
            null
        }

    /**
     * SHA-256 fingerprint of the certificate that signed the APK at [apkFile]
     * (read straight from the file — the APK does not need to be installed).
     * `null` if the APK cannot be parsed.
     */
    fun apkSigningFingerprint(context: Context, apkFile: File): String? =
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return null
            val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            cert?.let(::fingerprintOf)
        } catch (e: Exception) {
            LogHelper.printe("SigningUtils: failed to read APK certificate", exception = e)
            null
        }

    /**
     * `true` — the update can be installed in place (same signing key).
     * `false` — the keys differ; Android would refuse the install (signature
     * mismatch) and the user must go through a one-time uninstall/reinstall.
     * `null` — could not determine either certificate; callers should fall
     * back to letting the system decide rather than showing a wrong warning.
     */
    fun signaturesMatch(context: Context, apkFile: File): Boolean? {
        val installed = installedSigningFingerprint(context) ?: return null
        val apk = apkSigningFingerprint(context, apkFile) ?: return null
        return normalizeFingerprint(installed) == normalizeFingerprint(apk)
    }
}
