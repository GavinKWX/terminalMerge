package com.sc.mf919.kotlin.scheduler

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sc.mf919.java.activity.Utils
import helpers.HelperLog

import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import java.io.File

class TmsUploadLogScheduler(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {
    val mContext = appContext
    val className: String = TmsUploadLogScheduler::class.java.name

    override fun doWork(): Result {
        val isWiFi = TmsHelper.checkIsConnectedWifi(applicationContext)
        val log = helpers.HelperLog(
            HelperCommon.getSession(),
            isWiFi,
            Utils.getIPAddress(),
            "Auto Upload Log Job - Triggered",
            this@TmsUploadLogScheduler.javaClass.simpleName,
            this@TmsUploadLogScheduler.javaClass.name
        )

        /*
         * This job DESTROYS evidence: uploadAllTerminalLog deletes every backup file it manages
         * to ship, and copies+deletes the live files when isUploadAll is set. TmsHelper does log
         * each upload/delete as it goes -- but those lines are written into the very files being
         * uploaded and removed, so they can leave with them. The inventory below is taken here,
         * in the scheduler's own segment, and the before/after diff is what lets you reconstruct
         * what was destroyed on a run that misbehaved.
         *
         * uploadAllTerminalLog also returns false silently when WiFi is down (its first check is
         * an unlogged early return), so isWiFi is recorded before the call to tell "not connected"
         * apart from "upload rejected".
         */
        val logsDir = File(mContext.filesDir, "Logs")
        log.appendLine(className, "Auto Upload Log Job [START] :: isWiFi[$isWiFi] isUploadAll[false] dir[${logsDir.absolutePath}]")

        val before = inventory(logsDir)
        log.appendLine(className, "Log files before :: count[${before.size}] totalBytes[${before.values.sum()}]")
        log.appendLine(className, "Log files before (name:bytes)", describe(before))
        // Boundary before the upload: everything above is on disk in a block even if the upload
        // call never returns (killed mid-HTTP, or the process dies while files are being deleted).
        log.logToFile(EnumLogFileName.TerminaLog)

        var response: Boolean? = null
        try {
            response = TmsHelper.uploadAllTerminalLog(log, mContext, false)
            log.appendLine(className, "Job Result: ", response.toString())
        } catch (e: Exception) {
            log.appendLine(className, "Exception: ", e.toString())
            // Synchronous stream: the accounting of what was deleted must survive the kill that
            // may follow, and this is the one path where the upload state is unknown.
            log.logToFile(EnumLogFileName.TerminaLogException)
        }

        val after = inventory(logsDir)
        val removed = before.keys - after.keys
        val added = after.keys - before.keys
        val bytesFreed = removed.sumOf { before[it] ?: 0L }
        log.appendLine(className, "Log files after :: count[${after.size}] totalBytes[${after.values.sum()}]")
        log.appendLine(className, "Deleted after upload :: count[${removed.size}] bytesFreed[$bytesFreed]")
        log.appendLine(className, "Deleted file names", if (removed.isEmpty()) "-" else removed.joinToString(", "))
        if (added.isNotEmpty()) {
            // A leftover *_COPY.txt here means the temporary copy path did not clean up.
            log.appendLine(className, "New/left-behind file names", added.joinToString(", "))
        }
        log.appendLine(
            className,
            "Auto Upload Log Job [END] :: uploadResult[${response ?: "EXCEPTION"}] deleted[${removed.size}] remaining[${after.size}] result[SUCCESS]"
        )
        log.logToFile(EnumLogFileName.TerminaLog)

        return Result.success()
    }

    /**
     * name -> size in bytes for every file directly under [dir]. Read-only; a missing or
     * unreadable directory yields an empty map rather than throwing, because this is
     * accounting and must never be the reason the job fails.
     */
    private fun inventory(dir: File): Map<String, Long> = try {
        dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.associate { it.name to it.length() }
            ?: emptyMap()
    } catch (ex: Exception) {
        emptyMap()
    }

    /** One physical line for the whole inventory -- a name-per-line list is un-greppable. */
    private fun describe(files: Map<String, Long>): String =
        if (files.isEmpty()) "-" else files.entries.joinToString(", ") { "${it.key}:${it.value}" }
}
