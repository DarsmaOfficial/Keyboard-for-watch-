package dev.darsma.wearkey

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.darsma.wearkey.imecore.ClipboardStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted-at-rest persistence for clipboard history (spec §6).
 *
 * Design points that matter:
 *
 *  - **AES-GCM via the Android Keystore.** The key never leaves the secure hardware/TEE; this
 *    process only ever holds a handle to it. GCM is authenticated, so a tampered file fails to
 *    decrypt rather than silently yielding garbage.
 *  - **Credential-protected storage.** The spec calls for the clipboard to live in CE storage
 *    while layout/UI preferences live in DE storage, and this is the one place that separation
 *    genuinely earns its keep: clipboard contents should not be readable before first unlock.
 *  - **Fail closed, never crash.** A keyboard that dies leaves the user unable to type at all
 *    (spec §11 failure modes). Every operation here degrades to "no history" instead of
 *    throwing — corrupt file, missing key, rotated key, all end up the same benign way.
 *  - **Sensitive entries are never written.** OTPs and card numbers expire in minutes by
 *    design; persisting them across reboots would defeat that.
 */
class EncryptedClipboardPersistence(private val context: Context) {

    // Clipboard data belongs in credential-protected storage (spec §6). There is no
    // createCredentialProtectedStorageContext() to call — an ordinary Context already IS
    // credential-encrypted storage; only device-protected (DE) storage needs an explicit opt-in
    // via createDeviceProtectedStorageContext(), which is what SettingsStore uses instead.
    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    private fun secretKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateKey()
    }.getOrNull()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately NOT requiring user authentication: the keyboard must work on a
                // watch with no lock credential set, which is this device's default state.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** Serialises and encrypts the current history. Sensitive entries are excluded. */
    fun save(store: ClipboardStore) {
        runCatching {
            val key = secretKey() ?: return
            val payload = store.visibleEntries()
                .filterNot { it.sensitive }
                .joinToString("\n") { entry ->
                    // pinned flag + timestamp + text, with the text escaped so newlines inside
                    // an entry cannot corrupt the record separator.
                    val escaped = entry.text.replace("\\", "\\\\").replace("\n", "\\n")
                    "${if (entry.pinned) 1 else 0}\t${entry.createdAtMs}\t$escaped"
                }

            if (payload.isEmpty()) {
                file.delete()
                return
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

            // Layout: [iv length][iv][ciphertext]
            file.outputStream().use { out ->
                out.write(cipher.iv.size)
                out.write(cipher.iv)
                out.write(ciphertext)
            }
        }
    }

    /** Decrypts and restores history into [store]. Silently yields nothing on any failure. */
    fun load(store: ClipboardStore) {
        runCatching {
            if (!file.exists()) return
            val key = secretKey() ?: return

            val bytes = file.readBytes()
            if (bytes.size < 2) return
            val ivLength = bytes[0].toInt()
            if (ivLength <= 0 || bytes.size < 1 + ivLength) return
            val iv = bytes.copyOfRange(1, 1 + ivLength)
            val ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = String(cipher.doFinal(ciphertext), Charsets.UTF_8)

            // Restore oldest-first so the store's newest-first ordering comes out right.
            plaintext.lines().reversed().forEach { line ->
                val parts = line.split("\t", limit = 3)
                if (parts.size != 3) return@forEach
                val pinned = parts[0] == "1"
                val text = parts[2].replace("\\n", "\n").replace("\\\\", "\\")
                if (text.isBlank()) return@forEach
                store.add(text)
                if (pinned) store.pin(text, true)
            }
        }.onFailure {
            // Corrupt or undecryptable (e.g. the key was invalidated) — start clean rather than
            // leaving a file that will fail forever.
            runCatching { file.delete() }
        }
    }

    /** Erases persisted history. Backs the "clear all learned data" action (spec §11.5). */
    fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "wearkey_clipboard_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FILE_NAME = "clipboard.bin"
    }
}
