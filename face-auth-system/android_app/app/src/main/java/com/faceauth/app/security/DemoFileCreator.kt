package com.faceauth.app.security

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Creates a set of realistic demo "sensitive" files in the app's private
 * storage at startup.  These files represent data a real ransomware would
 * target: documents, logs, reports, config files.
 *
 * Files are created only if they don't already exist (idempotent).
 *
 * Directory: /data/data/com.faceauth.app/files/secure_docs/
 *
 * Inspect with ADB before authentication:
 *   adb shell run-as com.faceauth.app ls -la files/secure_docs/
 *   adb shell run-as com.faceauth.app cat files/secure_docs/employee_records.txt
 */
object DemoFileCreator {

    private const val TAG      = "DemoFileCreator"
    const val DEMO_DIR         = "secure_docs"
    const val RANSOM_NOTE_NAME = "README_ENCRYPTED.txt"

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    data class FileInfo(
        val name:    String,
        val content: String
    )

    private fun demoFiles(ts: String) = listOf(
        FileInfo(
            "employee_records.txt",
            """EMPLOYEE RECORDS — CONFIDENTIAL
Generated: $ts
========================================
ID    | Name              | Department  | Salary
------|-------------------|-------------|--------
E001  | Alice Johnson     | Engineering | 92,000
E002  | Bob Martinez      | Marketing   | 78,000
E003  | Carol White       | HR          | 71,000
E004  | David Lee         | Finance     | 85,000
E005  | Eva Brown         | Engineering | 95,000
========================================
Access restricted to authorised personnel only.
"""
        ),
        FileInfo(
            "system_config.json",
            """{
  "app_version": "1.0.0",
  "database_host": "db.internal.company.com",
  "database_port": 5432,
  "api_key": "sk-live-aBcDeFgHiJkLmNoPqRsTuVwXyZ123456",
  "encryption_enabled": true,
  "admin_email": "admin@company.com",
  "generated": "$ts",
  "environment": "production"
}
"""
        ),
        FileInfo(
            "access_log.log",
            """ACCESS LOG — $ts
INFO  [08:01:23] User alice.johnson logged in from 192.168.1.101
INFO  [08:15:44] User bob.martinez accessed /reports/q3_revenue.pdf
WARN  [09:22:11] Failed login attempt for user 'admin' from 10.0.0.55
INFO  [09:45:30] User carol.white updated HR record E003
INFO  [10:01:00] Scheduled backup completed — 2847 files archived
ERROR [10:15:22] Disk usage at 87% — warning threshold exceeded
INFO  [11:30:00] Security scan completed — 0 threats detected
INFO  [12:00:00] Daily report generated and emailed to management
"""
        ),
        FileInfo(
            "financial_report_q3.csv",
            """Quarter,Revenue,Expenses,Profit,Growth
Q1 2025,1250000,980000,270000,+12.3%
Q2 2025,1380000,1050000,330000,+22.2%
Q3 2025,1520000,1100000,420000,+27.3%
Generated: $ts
Confidential — Finance Department Only
"""
        ),
        FileInfo(
            "passwords_backup.txt",
            """SYSTEM CREDENTIALS BACKUP — $ts
STRICTLY CONFIDENTIAL
========================================
Service          | Username      | Notes
-----------------|---------------|------------------
Database Primary | db_admin      | rotate quarterly
API Gateway      | api_service   | auto-rotated
Backup Server    | backup_usr    | last rotated: Q2
Admin Panel      | sysadmin      | 2FA enabled
========================================
Destroy after use. Do not transmit via email.
"""
        )
    )

    /**
     * Creates all demo files. Call from MainActivity.onCreate().
     * Returns list of created file paths.
     */
    fun createDemoFiles(ctx: Context): List<String> {
        val dir = File(ctx.filesDir, DEMO_DIR).also { it.mkdirs() }
        val ts  = sdf.format(Date())
        val created = mutableListOf<String>()

        for (info in demoFiles(ts)) {
            val file = File(dir, info.name)
            if (!file.exists()) {
                file.writeText(info.content)
                created.add(file.absolutePath)
                Log.d(TAG, "Created demo file: ${file.name}")
            }
        }

        Log.i(TAG, "Demo files ready in ${dir.absolutePath}  (${created.size} new)")
        return created
    }

    /**
     * Lists all files in the demo directory with their sizes and read status.
     */
    fun listDemoFiles(ctx: Context): List<Map<String, String>> {
        val dir = File(ctx.filesDir, DEMO_DIR)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()?.sortedBy { it.name }?.map { f ->
            mapOf(
                "name"     to f.name,
                "size"     to "${f.length()} bytes",
                "readable" to if (f.canRead()) "YES" else "NO",
                "type"     to if (f.name.endsWith(".enc")) "ENCRYPTED" else "PLAINTEXT",
                "path"     to f.absolutePath
            )
        } ?: emptyList()
    }

    /** Returns true if any demo files have been encrypted. */
    fun hasEncryptedFiles(ctx: Context): Boolean {
        val dir = File(ctx.filesDir, DEMO_DIR)
        return dir.listFiles()?.any { it.name.endsWith(".enc") } == true
    }
}
