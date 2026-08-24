package com.faceauth.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.faceauth.app.databinding.ActivityAuthSuccessBinding
import com.faceauth.app.util.AuthLogger
import java.text.SimpleDateFormat
import java.util.*

class AuthSuccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthSuccessBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthSuccessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val conf = intent.getFloatExtra("conf", 0f)
        val ts   = intent.getLongExtra("ts",   System.currentTimeMillis())
        val sdf  = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)

        binding.tvTitle.text      = "Authentication\nSuccessful"
        binding.tvClass.text      = "Identity: Class A — authenticated user"
        binding.tvConfidence.text = "Confidence: ${"%.1f".format(conf * 100)}%"
        binding.tvTimestamp.text  = "Authenticated at:\n${sdf.format(Date(ts))}"

        // Show last 5 logged events
        val logs = AuthLogger.readAll(this)
        binding.tvLog.text = if (logs.isEmpty()) {
            "No previous events."
        } else {
            logs.takeLast(5).joinToString("\n") {
                "[${it.timestamp}]\n  ${it.event}  ${it.classLabel}" +
                "  (${"%.1f".format(it.confidence * 100)}%)"
            }
        }

        binding.btnDone.setOnClickListener { finish() }
    }
}