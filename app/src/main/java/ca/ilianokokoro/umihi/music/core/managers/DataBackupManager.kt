package ca.ilianokokoro.umihi.music.core.managers

import android.content.Context
import android.net.Uri
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Exports and restores the app's user data — Room databases, DataStore
 * settings (including the login session) and downloaded music — as a single
 * zip file the user picks via the Storage Access Framework.
 *
 * Why this exists: a one-time signing-key change (v1.0.3 was accidentally
 * signed with the debug key) means affected users must uninstall the app
 * once; Android only restores OS-level backups for the same signing
 * certificate, so this manual backup is the only way to keep their data
 * across that reinstall. It is also a general, user-invoked backup for any
 * device transfer.
 *
 * Layout inside the zip:
 *   laya-backup/files/<rel>      → context.filesDir (databases, datastore, downloads, …)
 *   laya-backup/external/<abs>   → custom SD-card download root (if configured)
 */
object DataBackupManager {

    private const val ROOT = "laya-backup"
    private const val FILES_PREFIX = "$ROOT/files/"
    private const val EXTERNAL_PREFIX = "$ROOT/external/"

    /** Directories that are caches or re-creatable — never part of a backup. */
    private val EXCLUDED_DIRS = setOf(
        "cache", "code_cache", "no_backup", "updates"
    )

    /**
     * Writes the app's data to [outputUri] as a zip. Returns the number of
     * files written on success.
     */
    suspend fun export(context: Context, outputUri: Uri): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = buildList {
                    collect(context.filesDir, FILES_PREFIX, this)
                    // Custom SD-card download root, if one is configured and mounted.
                    val custom = customDownloadRoot(context)
                    if (custom != null && custom.exists()) {
                        collect(custom, "$EXTERNAL_PREFIX${custom.absolutePath}/", this)
                    }
                }

                var written = 0
                val resolver = context.contentResolver
                (resolver.openOutputStream(outputUri)
                    ?: error("Could not open the chosen location for writing")).use { os ->
                    ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                        for ((entryName, file) in entries) {
                            zos.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            written++
                        }
                    }
                }
                LogHelper.printe("Backup written: $written files")
                written
            }.onFailure {
                LogHelper.printe("Backup failed: ${it.message}", exception = it as? Exception)
            }
        }

    /**
     * Restores app data from [inputUri] into the app's private storage (and
     * the custom SD-card root, when the backup contains one). Returns the
     * number of files restored. The caller should restart the process
     * afterwards so Room/DataStore pick up the restored files.
     */
    suspend fun import(context: Context, inputUri: Uri): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val filesDir = context.filesDir
                var restored = 0
                val resolver = context.contentResolver
                (resolver.openInputStream(inputUri)
                    ?: error("Could not open the backup file")).use { ins ->
                    ZipInputStream(BufferedInputStream(ins)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (!entry.isDirectory && name.startsWith(FILES_PREFIX)) {
                                val rel = name.removePrefix(FILES_PREFIX)
                                if (rel.isNotBlank()) {
                                    safeExtract(zis, File(filesDir, rel), filesDir) { restored++ }
                                }
                            } else if (!entry.isDirectory && name.startsWith(EXTERNAL_PREFIX)) {
                                // Absolute path is embedded for SD-card restores.
                                val abs = name.removePrefix(EXTERNAL_PREFIX)
                                val target = File(abs)
                                val root = File(target.absolutePath.substringBeforeLast('/'))
                                if (abs.isNotBlank()) {
                                    safeExtract(zis, target, root) { restored++ }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                LogHelper.printe("Restore completed: $restored files")
                restored
            }.onFailure {
                LogHelper.printe("Restore failed: ${it.message}", exception = it as? Exception)
            }
        }

    private fun collect(dir: File, prefix: String, out: MutableList<Pair<String, File>>) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.isDirectory) {
                if (file.name in EXCLUDED_DIRS) return@forEach
                collect(file, prefix, out)
            } else {
                out.add("$prefix${file.name}" to file)
            }
        }
    }

    /** Extracts one entry, refusing paths that escape [root] (zip-slip guard). */
    private fun safeExtract(
        zis: ZipInputStream,
        target: File,
        root: File,
        onExtracted: () -> Unit
    ) {
        if (!target.canonicalPath.startsWith(root.canonicalPath)) {
            LogHelper.printe("Restore: refusing path outside app storage: ${target.path}")
            return
        }
        target.parentFile?.mkdirs()
        target.outputStream().use { zis.copyTo(it) }
        onExtracted()
    }

    private suspend fun customDownloadRoot(context: Context): File? {
        val path = runCatching {
            ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository(context)
                .getSettings()
                .downloadPath
        }.getOrNull()
        return path?.takeIf { it.isNotBlank() }?.let { File(it) }
    }
}
