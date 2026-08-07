package dev.darsma.wearkey

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A dictionary available beyond the two bundled in the APK (spec §4.3).
 *
 * [languageTag] is a BCP-47 tag such as `nl-NL`. [source] records where it came from, which is
 * shown in Settings so the user can tell a pack they installed from a file they imported.
 */
data class LanguagePack(
    val languageTag: String,
    val displayName: String,
    val source: Source,
    /** Package name for [Source.PACK], absolute path for [Source.IMPORTED]. */
    val location: String
) {
    enum class Source { PACK, IMPORTED }
}

/**
 * Discovers and loads add-on dictionaries (spec §4.3).
 *
 * ## Two supply routes, no network — ever
 *
 * The spec permits exactly two ways for a dictionary to arrive: a **separately installed, signed
 * APK pack**, or an **offline file import**. The IME declares no `INTERNET` permission, so neither
 * route can become a download, and that is a structural guarantee rather than a policy one — the
 * manifest simply does not carry the permission.
 *
 * ## Why installed packs are signature-checked
 *
 * A pack is an ordinary APK, so *any* app on the device could declare the marker metadata and offer
 * a dictionary. The index is a binary format read through absolute buffer offsets; a hostile file
 * is a memory-safety concern, not merely a quality one. Requiring the pack to be signed by the same
 * key as the keyboard means only the person who built this keyboard can ship a pack for it, which
 * is the whole point of the spec saying *signed*.
 *
 * Imported files cannot be signature-checked — the user chose them deliberately, which is a
 * different and weaker trust model. They are therefore validated structurally before use, and a
 * malformed file is rejected rather than mapped.
 */
class LanguagePackManager(private val context: Context) {

    /** Directory holding user-imported indexes. Private storage: no other app can write here. */
    private val importDir: File
        get() = File(context.noBackupFilesDir, "imported-dictionaries").apply { mkdirs() }

    /**
     * All usable add-on dictionaries.
     *
     * Packs that fail the signature check are silently omitted rather than surfaced as errors.
     * Reporting them would teach users to expect and then dismiss such warnings, and there is no
     * legitimate case where a correctly built pack fails this check.
     */
    fun available(): List<LanguagePack> = discoverPacks() + discoverImported()

    private fun discoverPacks(): List<LanguagePack> {
        val pm = context.packageManager
        val installed = runCatching {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val out = ArrayList<LanguagePack>()
        for (info in installed) {
            val meta = info.applicationInfo?.metaData ?: continue
            val tag = meta.getString(META_LANGUAGE_TAG) ?: continue
            if (!isSignedLikeUs(info.packageName)) continue

            out.add(
                LanguagePack(
                    languageTag = tag,
                    displayName = meta.getString(META_DISPLAY_NAME) ?: tag,
                    source = LanguagePack.Source.PACK,
                    location = info.packageName
                )
            )
        }
        return out
    }

    private fun discoverImported(): List<LanguagePack> {
        val files = importDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".bin") }
            .map { file ->
                val tag = file.name.removeSuffix(".bin")
                LanguagePack(
                    languageTag = tag,
                    displayName = tag,
                    source = LanguagePack.Source.IMPORTED,
                    location = file.absolutePath
                )
            }
            .sortedBy { it.languageTag }
    }

    /**
     * True when [packageName] carries the same signing certificate as this app.
     *
     * Compares the full certificate set, not just the first entry: an APK can be signed by several
     * certificates, and checking only one would let a hostile pack pass by including our
     * certificate alongside its own.
     */
    private fun isSignedLikeUs(packageName: String): Boolean = runCatching {
        val pm = context.packageManager
        val ours = signaturesOf(context.packageName) ?: return false
        val theirs = signaturesOf(packageName) ?: return false
        ours.size == theirs.size && ours.toSet() == theirs.toSet()
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun signaturesOf(packageName: String): List<Signature>? {
        val pm = context.packageManager
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signing = info.signingInfo ?: return null
                if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners.toList()
                } else {
                    signing.signingCertificateHistory.toList()
                }
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.toList()
            }
        }.getOrNull()
    }

    /**
     * Maps a pack's index, or null when it cannot be read.
     *
     * Pack assets are mapped straight out of the other APK the same way the bundled ones are — no
     * copy into our own storage, so a pack costs no additional disk and no heap.
     */
    fun map(pack: LanguagePack): ByteBuffer? = runCatching {
        when (pack.source) {
            LanguagePack.Source.PACK -> {
                val packContext = context.createPackageContext(pack.location, 0)
                val fd = packContext.assets.openFd(PACK_ASSET_NAME)
                fd.use {
                    it.createInputStream().channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        it.startOffset,
                        it.declaredLength
                    )
                }
            }

            LanguagePack.Source.IMPORTED -> {
                val file = File(pack.location)
                if (!file.isFile) return null
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                }
            }
        }
    }.getOrNull()

    /**
     * Copies an index into private storage under [languageTag], returning the stored file.
     *
     * The copy is deliberate. Mapping a file the user picked from shared storage would let it be
     * modified or deleted underneath a live mapping, which turns an ordinary file operation into a
     * crash or worse. Copying into `noBackupFilesDir` also keeps dictionaries out of cloud backup,
     * where they would count against the user's storage for no benefit.
     *
     * Returns null when the bytes are not a valid index, so a mistyped file cannot be installed and
     * then fail mysteriously at every keystroke.
     */
    fun import(languageTag: String, bytes: ByteArray): File? {
        if (!isPlausibleIndex(bytes)) return null
        if (!languageTag.matches(SAFE_TAG)) return null

        val target = File(importDir, "$languageTag.bin")
        return runCatching {
            target.writeBytes(bytes)
            target
        }.getOrNull()
    }

    /** Removes an imported dictionary. Packs are removed by uninstalling them. */
    fun removeImported(languageTag: String): Boolean {
        if (!languageTag.matches(SAFE_TAG)) return false
        return File(importDir, "$languageTag.bin").delete()
    }

    /**
     * Cheap structural check before accepting a file.
     *
     * This is not a full parse — [dev.darsma.wearkey.dict.WordIndex] validates thoroughly when it
     * maps — but it rejects the common accidents (an empty file, a text file, a truncated download)
     * at the point where a useful error can still be shown.
     */
    private fun isPlausibleIndex(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_BYTES) return false
        // Little-endian explicitly: ByteBuffer defaults to big-endian, which would silently reject
        // every valid index. WordIndex reads the same field little-endian.
        val magic = ByteBuffer.wrap(bytes, 0, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .int
        return magic == INDEX_MAGIC
    }

    companion object {
        /** Manifest metadata a pack APK must declare to be recognised. */
        const val META_LANGUAGE_TAG = "dev.darsma.wearkey.languageTag"
        const val META_DISPLAY_NAME = "dev.darsma.wearkey.displayName"

        /** Asset name inside a pack APK; matches the bundled naming. */
        const val PACK_ASSET_NAME = "dictionary.bin"

        /**
         * Magic word written by `tools/build_index.py`: the bytes "WKD1".
         *
         * Read here as little-endian to match [dev.darsma.wearkey.dict.WordIndex], which is what
         * the reader does and therefore what "valid" means. A mismatch in this constant could only
         * ever cause a *good* file to be rejected at import, never a bad one to be accepted,
         * because WordIndex checks the magic again when it maps.
         */
        const val INDEX_MAGIC = 0x31444B57

        private const val HEADER_BYTES = 40

        /** Tags are used as filenames, so anything path-like is refused outright. */
        private val SAFE_TAG = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    }
}
