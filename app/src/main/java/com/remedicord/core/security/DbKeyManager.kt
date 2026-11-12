package com.remedicord.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The database key can't be recovered (Keystore key gone or key file missing while the DB exists). */
class DatabaseKeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Protects the database key (PLAN.md §3).
 *
 * - A random 256-bit database key is generated once with [SecureRandom].
 * - It is encrypted with an AES-256-GCM key that lives inside the Android Keystore (hardware-backed,
 *   StrongBox when available). The Keystore key can never be exported.
 * - Only the encrypted form is written to app-private, never-backed-up storage ([Context.getNoBackupFilesDir]).
 *
 * The key deliberately does NOT require the device to be unlocked, so reminders keep working
 * while the phone is locked (the app PIN protects the screens, PLAN.md §3.1).
 */
class DbKeyManager(
    private val context: Context,
    private val keyAlias: String = DEFAULT_ALIAS,
    private val keyFileName: String = DEFAULT_KEY_FILE,
) {
    private val keyFile: File get() = File(context.noBackupFilesDir, keyFileName)

    /**
     * Returns the SQLCipher key in raw-key form (`x'<64 hex>'`), which skips the slow passphrase
     * derivation because the key is already 256 bits of randomness.
     *
     * @param databaseExists if true and no key file exists, fail instead of creating a new key:
     *   a new key would make the existing data permanently unreadable.
     */
    @Synchronized
    fun getOrCreateDatabaseKey(databaseExists: Boolean): ByteArray {
        val rawKey: ByteArray = if (keyFile.exists()) {
            decrypt(keyFile.readBytes())
        } else {
            if (databaseExists) {
                throw DatabaseKeyUnavailableException("Database exists but its key file is missing")
            }
            ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }.also { writeKeyFile(encrypt(it)) }
        }
        try {
            return toSqlCipherRawKey(rawKey)
        } finally {
            rawKey.fill(0)
        }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return try {
            generateKeystoreKey(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generateKeystoreKey(strongBox = false)
        }
    }

    private fun generateKeystoreKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /** File format: [version:1][ivLength:1][iv][ciphertext+GCM tag]. */
    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ct
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        try {
            require(blob.size > 2 && blob[0] == FORMAT_VERSION) { "Unknown key file format" }
            val ivLen = blob[1].toInt()
            require(ivLen in 12..16 && blob.size > 2 + ivLen) { "Corrupt key file" }
            val iv = blob.copyOfRange(2, 2 + ivLen)
            val ct = blob.copyOfRange(2 + ivLen, blob.size)
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = ks.getKey(keyAlias, null) as? SecretKey
                ?: throw DatabaseKeyUnavailableException("Keystore key is missing")
            val cipher = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv)) }
            return cipher.doFinal(ct)
        } catch (e: DatabaseKeyUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw DatabaseKeyUnavailableException("Database key could not be decrypted", e)
        }
    }

    private fun writeKeyFile(bytes: ByteArray) {
        val atomic = AtomicFile(keyFile)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "remedicord_db_key_wrapper"
        const val DEFAULT_KEY_FILE = "db_key.bin"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_BYTES = 32
        private const val FORMAT_VERSION: Byte = 1

        /** `x'` + 64 lowercase hex chars + `'` as ASCII bytes (SQLCipher raw key syntax). */
        internal fun toSqlCipherRawKey(raw: ByteArray): ByteArray {
            val hex = "0123456789abcdef"
            val out = ByteArray(3 + raw.size * 2)
            out[0] = 'x'.code.toByte()
            out[1] = '\''.code.toByte()
            raw.forEachIndexed { i, b ->
                val v = b.toInt() and 0xFF
                out[2 + i * 2] = hex[v ushr 4].code.toByte()
                out[3 + i * 2] = hex[v and 0x0F].code.toByte()
            }
            out[out.size - 1] = '\''.code.toByte()
            return out
        }
    }
}
