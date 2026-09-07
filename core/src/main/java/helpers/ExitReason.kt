package helpers

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the OS's own record of how previous processes of this app ended.
 *
 * [LogSessionMarker] can tell that a run did not exit cleanly, but not why -- a marker cannot be
 * written by a process that was SIGKILLed, so "RUNNING was left behind" is the whole of what it
 * knows. Answering the follow-up question meant a developer running
 * `dumpsys activity exit-info <pkg>` over adb, which is not available on a terminal in the field.
 *
 * This closes that gap. The platform keeps the answer in ApplicationExitInfo -- reason, the ANR or
 * crash description, memory footprint at death -- and it survives the process, so it can be read on
 * the next startup and written into the app's own log next to the marker verdict.
 *
 * This is the only source that distinguishes the three deaths a crash handler structurally cannot
 * see: an ANR kill, a low-memory kill, and a force-stop all leave an identical RUNNING marker.
 *
 * API 30+. app-mf919 ships to Android 7 terminals, so on anything older this reports that the
 * platform has no record and the marker verdict stands alone.
 */
object ExitReason {

	/** Remembers the newest exit already reported, so a restart loop does not re-log its history. */
	private const val SEEN_NAME = "exitinfo.seen"

	/** More than a couple is noise; a restart loop is still visible in the count. */
	private const val MAX_REPORTED = 5

	/**
	 * Returns one line per process exit not yet reported, newest first, and records how far it got.
	 * Empty when the platform is too old, the records are gone, or nothing new has happened.
	 *
	 * Never throws: this runs on the startup path and a diagnostic must not be able to stop the app
	 * from booting.
	 */
	@JvmStatic
	fun describeNewExits(context: Context, dir: File): List<String> = try {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			emptyList()
		} else {
			val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
			val records = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_REPORTED)

			val seenFile = File(dir, SEEN_NAME)
			val seen = try {
				if (seenFile.exists()) seenFile.readText().trim().toLongOrNull() ?: 0L else 0L
			} catch (e: Exception) {
				0L
			}

			val fresh = records.filter { it.timestamp > seen }
			if (fresh.isNotEmpty()) {
				try {
					seenFile.writeText(fresh.maxOf { it.timestamp }.toString())
				} catch (e: Exception) {
					// A failed write only costs a duplicate report next boot.
				}
			}
			fresh.map { describe(it) }
		}
	} catch (e: Exception) {
		listOf("previous exit :: unavailable (${e.javaClass.simpleName}: ${e.message})")
	}

	private fun describe(info: ApplicationExitInfo): String {
		val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH).format(Date(info.timestamp))
		val sb = StringBuilder()
		sb.append("previous exit :: ").append(reasonName(info.reason))
			.append(" at ").append(at)
			.append(" (pid ").append(info.pid).append(")")

		// pss/rss read 0 when the process was already gone before the record was taken; printing
		// "0MB" there would look like a measurement rather than an absence.
		if (info.pss > 0) sb.append(" pss=").append(info.pss / 1024).append("MB")
		if (info.rss > 0) sb.append(" rss=").append(info.rss / 1024).append("MB")
		sb.append(" importance=").append(info.importance)
		if (info.status != 0) sb.append(" status=").append(info.status)

		// The ANR text names the unresponsive component and what it was waiting for -- the single
		// most useful field here. Scrubbed because it is free-form platform text going into a log
		// that gets uploaded to TMS.
		info.description?.takeIf { it.isNotBlank() }?.let {
			sb.append(" :: ").append(LogRedact.scrubPans(it))
		}
		return sb.toString()
	}

	/**
	 * Platform reason codes. Spelled out rather than printed as integers because the number alone
	 * ("reason=6") is exactly the thing that sends someone back to the documentation.
	 */
	private fun reasonName(reason: Int): String = when (reason) {
		ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF (the app ended itself)"
		ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed by a signal)"
		ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed to reclaim memory)"
		ApplicationExitInfo.REASON_CRASH -> "CRASH (uncaught Java exception)"
		ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native crash)"
		ApplicationExitInfo.REASON_ANR -> "ANR (not responding; killed by the system)"
		ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
		ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
		ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
		ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (force-stop or similar)"
		ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
		ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
		ApplicationExitInfo.REASON_OTHER -> "OTHER"
		ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN (no record of the cause)"
		else -> "code $reason"
	}
}
