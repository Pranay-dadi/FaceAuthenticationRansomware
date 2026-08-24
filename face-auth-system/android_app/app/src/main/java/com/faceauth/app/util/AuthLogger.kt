package com.faceauth.app.util

import android.content.Context
import android.provider.Settings
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class AuthEvent(
    val timestamp  : String,
    val event      : String,
    val classLabel : String,
    val confidence : Float,
    val deviceId   : String
)

object AuthLogger {

    private const val LOG_FILE = "auth_log.json"
    private val gson = Gson()
    private val sdf  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(ctx: Context, event: String, classLabel: String, conf: Float) {
        val entry = AuthEvent(
            timestamp  = sdf.format(Date()),
            event      = event,
            classLabel = classLabel,
            confidence = conf,
            deviceId   = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        )
        val file  = File(ctx.filesDir, LOG_FILE)
        val lines = if (file.exists()) file.readLines().toMutableList()
                    else mutableListOf()
        lines.add(gson.toJson(entry))
        file.writeText(lines.joinToString("\n"))
    }

    fun readAll(ctx: Context): List<AuthEvent> {
        val file = File(ctx.filesDir, LOG_FILE)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull {
                runCatching { gson.fromJson(it, AuthEvent::class.java) }.getOrNull()
            }
    }
}