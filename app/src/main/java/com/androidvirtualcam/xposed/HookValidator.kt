package com.androidvirtualcam.xposed

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * HookValidator – runs on module load and logs which hooks succeeded, which failed, and why.
 * Stored to a log file the main app can read and display.
 *
 * Log file: /data/data/com.androidvirtualcam/files/hook_validation.log
 * Also: /data/data/[target_package]/files/vcam_hook_status.json for per-app status
 */
object HookValidator {

    private const val TAG = "HookValidator"
    private const val LOG_FILE_NAME = "hook_validation.log"
    private const val STATUS_FILE_NAME = "vcam_hook_status.json"

    data class HookResult(
        val hookName: String,
        val className: String,
        val methodName: String,
        val success: Boolean,
        val error: String? = null,
        val durationMs: Long = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val packageName: String = "",
        val versionCode: Long = 0
    )

    private val results = ConcurrentHashMap<String, HookResult>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun recordSuccess(hookName: String, className: String, methodName: String, durationMs: Long = 0, packageName: String = "", versionCode: Long = 0) {
        val result = HookResult(
            hookName = hookName,
            className = className,
            methodName = methodName,
            success = true,
            durationMs = durationMs,
            packageName = packageName,
            versionCode = versionCode
        )
        results[hookName] = result
        Log.i(TAG, "Hook SUCCESS: $hookName $className#$methodName in ${durationMs}ms")
        appendToLogFile(result)
    }

    fun recordFailure(hookName: String, className: String, methodName: String, error: String, durationMs: Long = 0, packageName: String = "", versionCode: Long = 0) {
        val result = HookResult(
            hookName = hookName,
            className = className,
            methodName = methodName,
            success = false,
            error = error,
            durationMs = durationMs,
            packageName = packageName,
            versionCode = versionCode
        )
        results[hookName] = result
        Log.w(TAG, "Hook FAILED: $hookName $className#$methodName error=$error in ${durationMs}ms")
        appendToLogFile(result)
    }

    private fun appendToLogFile(result: HookResult) {
        try {
            // Try to write to main app's files dir and also to Xposed module's own dir
            val possibleDirs = listOf(
                "/data/data/com.androidvirtualcam/files",
                "/data/user/0/com.androidvirtualcam/files",
                "/data/data/com.androidvirtualcam/cache"
            )

            for (dirPath in possibleDirs) {
                try {
                    val dir = File(dirPath)
                    if (!dir.exists()) dir.mkdirs()
                    val logFile = File(dir, LOG_FILE_NAME)
                    val timestamp = dateFormat.format(Date(result.timestamp))
                    val line = "[$timestamp] ${if (result.success) "SUCCESS" else "FAILED"} ${result.hookName} ${result.className}#${result.methodName} pkg=${result.packageName} v=${result.versionCode} ${result.durationMs}ms ${result.error ?: ""}\n"
                    logFile.appendText(line)
                    break
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append to log file: ${e.message}")
        }
    }

    fun writeStatusFile(context: Context?, packageName: String, versionCode: Long) {
        try {
            val status = buildStatusJson(packageName, versionCode)

            // Write to main app files dir if context available
            context?.let { ctx ->
                try {
                    val file = File(ctx.filesDir, STATUS_FILE_NAME)
                    file.writeText(status)
                    Log.i(TAG, "Wrote status file to ${file.path}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write status file to context filesDir: ${e.message}")
                }
            }

            // Also try to write to target app's files dir (for main app to read via world-readable? But scoped storage restricts)
            // Instead, write to /data/data/com.androidvirtualcam/files/hook_status_$packageName.json
            try {
                val dir = File("/data/data/com.androidvirtualcam/files")
                dir.mkdirs()
                val file = File(dir, "hook_status_${packageName}.json")
                file.writeText(status)
                Log.i(TAG, "Wrote status file to ${file.path}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write status to main app dir: ${e.message}")
            }

            // Also write to log file directory as json
            try {
                val dir = File("/data/data/com.androidvirtualcam/files")
                val file = File(dir, "hook_validation_${packageName}.json")
                file.writeText(status)
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.e(TAG, "writeStatusFile failed", e)
        }
    }

    private fun buildStatusJson(packageName: String, versionCode: Long): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"packageName\": \"$packageName\",\n")
        sb.append("  \"versionCode\": $versionCode,\n")
        sb.append("  \"timestamp\": ${System.currentTimeMillis()},\n")
        sb.append("  \"timestampFormatted\": \"${dateFormat.format(Date())}\",\n")
        sb.append("  \"hooks\": [\n")

        val sorted = results.values.sortedBy { it.hookName }
        for ((index, result) in sorted.withIndex()) {
            sb.append("    {\n")
            sb.append("      \"hookName\": \"${result.hookName}\",\n")
            sb.append("      \"className\": \"${result.className}\",\n")
            sb.append("      \"methodName\": \"${result.methodName}\",\n")
            sb.append("      \"success\": ${result.success},\n")
            sb.append("      \"error\": ${result.error?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"},\n")
            sb.append("      \"durationMs\": ${result.durationMs},\n")
            sb.append("      \"packageName\": \"${result.packageName}\",\n")
            sb.append("      \"versionCode\": ${result.versionCode}\n")
            sb.append("    }${if (index < sorted.size - 1) "," else ""}\n")
        }

        sb.append("  ],\n")
        val successCount = results.values.count { it.success }
        val failCount = results.values.count { !it.success }
        sb.append("  \"summary\": {\n")
        sb.append("    \"total\": ${results.size},\n")
        sb.append("    \"success\": $successCount,\n")
        sb.append("    \"failed\": $failCount,\n")
        sb.append("    \"successRate\": ${if (results.isNotEmpty()) successCount.toFloat() / results.size else 0f}\n")
        sb.append("  }\n")
        sb.append("}\n")
        return sb.toString()
    }

    fun getResults(): List<HookResult> = results.values.toList()

    // Added for SettingsViewModel – returns log lines as List<String>
    fun getLogs(): List<String> {
        return try {
            if (results.isNotEmpty()) {
                results.values.map { result ->
                    val status = if (result.success) "SUCCESS" else "FAILED"
                    "$status ${result.hookName} ${result.className}#${result.methodName} pkg=${result.packageName} v=${result.versionCode} ${result.durationMs}ms ${result.error ?: ""}".trim()
                }
            } else {
                // Fallback: try to read from log file in main app dir
                val candidates = listOf(
                    File("/data/data/com.androidvirtualcam/files/$LOG_FILE_NAME"),
                    File("/data/user/0/com.androidvirtualcam/files/$LOG_FILE_NAME")
                )
                for (file in candidates) {
                    if (file.exists()) {
                        return file.readLines().takeLast(200)
                    }
                }
                listOf("No hook logs yet – ensure LSPosed module enabled and rebooted")
            }
        } catch (e: Exception) {
            listOf("Failed to get logs: ${e.message}")
        }
    }

    fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    fun readLogFile(context: Context): String {
        return try {
            val file = getLogFile(context)
            if (file.exists()) file.readText() else "No log file yet"
        } catch (e: Exception) {
            "Failed to read log: ${e.message}"
        }
    }

    fun clear() {
        results.clear()
    }

    fun logValidationStart(packageName: String, versionCode: Long) {
        Log.i(TAG, "=== Hook validation started for $packageName v$versionCode ===")
        try {
            val dir = File("/data/data/com.androidvirtualcam/files")
            dir.mkdirs()
            val logFile = File(dir, LOG_FILE_NAME)
            val timestamp = dateFormat.format(Date())
            logFile.appendText("\n[$timestamp] === Validation started for $packageName v$versionCode ===\n")
        } catch (_: Exception) {}
    }

    fun logValidationEnd(packageName: String, versionCode: Long) {
        val successCount = results.values.count { it.success }
        val failCount = results.values.count { !it.success }
        Log.i(TAG, "=== Hook validation ended for $packageName v$versionCode: $successCount success, $failCount failed ===")
        try {
            val dir = File("/data/data/com.androidvirtualcam/files")
            val logFile = File(dir, LOG_FILE_NAME)
            val timestamp = dateFormat.format(Date())
            logFile.appendText("[$timestamp] === Validation ended for $packageName: $successCount success, $failCount failed ===\n")
        } catch (_: Exception) {}
    }
}
