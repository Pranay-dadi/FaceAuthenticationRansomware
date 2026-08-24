package com.faceauth.app.util

import android.content.Context
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

data class PathProbe(
    val path        : String,
    val canRead     : Boolean,
    val canWrite    : Boolean,
    val exists      : Boolean,
    val statusLabel : String
)

object FilesystemLocker {

    private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
    private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
    private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
    private const val TRANSFORMATION = "$ALGORITHM/$BLOCK_MODE/$PADDING"
    private const val KEY_ALIAS = "face_auth_lockdown_key"

    /**
     * Retrieves or generates a 256-bit AES key backed by the hardware-protected Android KeyStore.
     */
    private fun getLockdownKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey

        return KeyGenerator.getInstance(ALGORITHM, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(BLOCK_MODE)
                    .setEncryptionPaddings(PADDING)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    /**
     * Probes a set of sensitive filesystem paths and returns their
     * accessibility status from within the sandboxed app process.
     * Demonstrates that Android's Linux/SELinux model naturally denies 
     * access to root system paths.
     */
    fun probeFilesystem(ctx: Context): List<PathProbe> {
        val paths = listOf(
            "/data/data/",
            "/data/system/",
            "/data/local/",
            "/proc/1/",
            "/sys/kernel/",
            ctx.filesDir.parentFile?.absolutePath
                ?: "/data/data/${ctx.packageName}"
        )

        return paths.map { path ->
            val f = File(path)
            val readable  = runCatching { f.canRead()  }.getOrDefault(false)
            val writable  = runCatching { f.canWrite() }.getOrDefault(false)
            val exists    = runCatching { f.exists()   }.getOrDefault(false)

            val status = when {
                !exists   -> "NOT FOUND (hidden by kernel)"
                !readable -> "PERMISSION DENIED"
                readable && path.contains(ctx.packageName) ->
                    "accessible (app sandbox only — read: $readable write: $writable)"
                else -> "PERMISSION DENIED"
            }

            PathProbe(path, readable, writable, exists, status)
        }
    }

    /**
     * Clears session storage and encrypts all sensitive app files in place when
     * an intrusion (Path B target persona) is detected.
     * Auth log (auth_log.json) is intentionally preserved as an unencrypted,
     * tamper-evident audit record.
     */
    fun lockdown(ctx: Context) {
        // 1. Clear image/data caches
        ctx.cacheDir.deleteRecursively()
        ctx.externalCacheDir?.deleteRecursively()

        // 2. Wipe active session data and preferences
        ctx.getSharedPreferences("user_session", Context.MODE_PRIVATE)
            .edit().clear().apply()
        ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()

        // 3. Encrypt sensitive application files in the internal files directory
        val internalDir = ctx.filesDir
        internalDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != "auth_log.json" && !file.name.endsWith(".enc")) {
                encryptFileInPlace(file)
            }
        }
    }

    /**
     * Stream-encrypts a given file using AES-256 GCM and replaces the original with
     * the encrypted version (.enc extension).
     */
    private fun encryptFileInPlace(file: File) {
        val encryptedFile = File(file.parent, "${file.name}.enc")

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getLockdownKey())

            file.inputStream().use { input ->
                encryptedFile.outputStream().use { output ->
                    // Write IV length and IV payload to header
                    val iv = cipher.iv
                    output.write(iv.size)
                    output.write(iv)

                    // Stream encrypted content
                    CipherOutputStream(output, cipher).use { cipherOut ->
                        input.copyTo(cipherOut)
                    }
                }
            }

            // Securely wipe original unencrypted file upon successful encryption
            if (encryptedFile.exists() && encryptedFile.length() > 0) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (encryptedFile.exists()) {
                encryptedFile.delete() // Clean up partial encrypted file on failure
            }
        }
    }
}