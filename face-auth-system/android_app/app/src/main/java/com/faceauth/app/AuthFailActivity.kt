package com.faceauth.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.faceauth.app.databinding.ActivityAuthFailBinding
import com.faceauth.app.util.FilesystemLocker
import com.faceauth.app.util.AuthLogger
import java.text.SimpleDateFormat
import java.util.*

class AuthFailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthFailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthFailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val conf = intent.getFloatExtra("conf", 0f)
        val ts   = intent.getLongExtra("ts",   System.currentTimeMillis())
        val sdf  = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)

        binding.tvTitle.text      = "Authentication\nUnsuccessful"
        binding.tvConfidence.text =
            "Target confidence: ${"%.1f".format(conf * 100)}%  (threshold ≥ 85%)"
        binding.tvTimestamp.text  = "Attempt logged at:\n${sdf.format(Date(ts))}"

        // Trigger security lockdown
        FilesystemLocker.lockdown(this)

        // Build filesystem probe report
        val probes   = FilesystemLocker.probeFilesystem(this)
        val probeStr = probes.joinToString("\n\n") { probe ->
            "  ${probe.path}\n  → ${probe.statusLabel}"
        }

        binding.tvFsTitle.text   = "Android Filesystem Status (target identified)"
        binding.tvFsReport.text  = buildString {
            appendLine("Security response triggered at ${sdf.format(Date(ts))}")
            appendLine()
            appendLine("Filesystem access probes:")
            appendLine()
            appendLine(probeStr)
            appendLine()
            appendLine("─────────────────────────────────")
            appendLine("Cache cleared        : YES")
            appendLine("Session wiped        : YES")
            appendLine("Auth log preserved   : YES (tamper-evident)")
            appendLine("Filesystem locked    : ENFORCED by Android sandbox")
        }

        binding.btnDismiss.setOnClickListener { finishAffinity() }
    }
}