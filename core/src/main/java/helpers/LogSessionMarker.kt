package helpers

import android.app.ActivityManager
import android.content.Context
import enums.EnumDateFormat
import enums.EnumLogFileName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records HOW THE PREVIOUS RUN ENDED, so a restart explains itself in one line.
 *
 * The whole reason this exists: a completed sale could upload its receipt and leave no trace in
 * TerminaLog, because the process died and the log simply jumped over the gap where it cold-started
 * several times. Working out *why* took reading logcat, meminfo, the TMS task list and the ticker
 * cadence -- and the answer was still only "probably a low-memory kill". Nothing in the app
 * recorded whether the previous run exited cleanly or was killed under it.
 *
 * The mechanism is deliberately dumb: a marker file says RUNNING while the app is alive, and is
 * rewritten to CLOSED on the two paths that are genuine app exits. Anything else -- LMK kill, ANR
 * kill, power loss, `am force-stop`, watchdog -- leaves RUNNING behind, and the next startup reports
 * it. A marker cannot be written by a process that was SIGKILLed, which is exactly the property
 * needed: absence of a clean close IS the signal.
 *
 * Also captures the two numbers that make a kill diagnosable after the fact: available memory
 * (this device runs with MemFree in the tens of MB) and usable disk. Both are read once at startup,
 * not polled.
 */
object LogSessionMarker {

	private const val MARKER_NAME = "session.marker"
	private const val STATE_RUNNING = "RUNNING"
	private const val STATE_CLOSED = "CLOSED"

	@Volatile
	private var markerFile: File? = null

	private fun stamp(): String =
		SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmssSSS.dateFormat, Locale.ENGLISH).format(Date())

	private fun line(msg: String) =
		AsyncLogWriter.write(EnumLogFileName.TerminaLog.name, "${stamp()} -(LogSessionMarker) $msg")

	/**
	 * Call once at startup, after AsyncLogWriter.init. Reports how the previous run ended, then
	 * marks this run as RUNNING.
	 *
	 * Writes through AsyncLogWriter rather than HelperLog so it works before Timber is planted and
	 * before any HelperLog instance exists.
	 */
	@JvmStatic
	fun openSession(dir: File, context: Context) {
		try {
			if (!dir.exists()) dir.mkdirs()
			val f = File(dir, MARKER_NAME)
			markerFile = f

			val previous = try {
				if (f.exists()) f.readText().trim() else ""
			} catch (e: Exception) {
				"UNREADABLE: ${e.javaClass.simpleName}"
			}

			when {
				previous.isEmpty() ->
					line("Previous run :: no marker (first start after install or data clear)")

				previous.startsWith(STATE_RUNNING) -> {
					// The previous process never got to write a close marker.
					val startedAt = previous.split("|").getOrNull(1) ?: "?"
					val pid = previous.split("|").getOrNull(2) ?: "?"
					line("Previous run :: DID NOT EXIT CLEANLY -- no close marker was written. " +
						"It started at $startedAt (pid $pid). Cause is external to the app: " +
						"low-memory kill, ANR kill, force-stop, watchdog or power loss. " +
						"Any log lines it had buffered but not yet flushed are lost.")
				}

				previous.startsWith(STATE_CLOSED) -> {
					val reason = previous.split("|").getOrNull(1) ?: "?"
					val at = previous.split("|").getOrNull(2) ?: "?"
					// A crash DOES write a close marker -- CrashHandler calls closeSession so the next
					// run can tell a crash apart from an external kill. That makes the marker "closed"
					// in the file-format sense while the run was anything but, so the REASON has to be
					// read, not just the state, or every crash reads as a clean exit.
					if (reason.startsWith("CRASH")) {
						line("Previous run :: ended in a CRASH ($reason at $at)")
					} else {
						line("Previous run :: exited cleanly ($reason at $at)")
					}
				}

				else -> line("Previous run :: unrecognised marker [$previous]")
			}

			// The marker knows the previous run died; only the platform knows why. An ANR kill, a
			// low-memory kill and a force-stop all leave an identical RUNNING marker behind, and
			// none of them reach CrashHandler -- this is the only thing that tells them apart.
			ExitReason.describeNewExits(context, dir).forEach { line(it) }

			line("Startup state :: ${describeResources(context)}")

			write(f, "$STATE_RUNNING|${stamp()}|${android.os.Process.myPid()}")
		} catch (e: Exception) {
			// Never let session bookkeeping break startup.
			line("openSession failed :: ${e.javaClass.simpleName}: ${e.message}")
		}
	}

	/**
	 * Call on a genuine app exit only -- MainActivity.closeApplication and CrashHandler.
	 *
	 * Deliberately NOT called from AppServices.onDestroy: MainActivity tears that service down and
	 * recreates it on every fresh load, so marking CLOSED there would report a clean exit on a
	 * process that is still very much alive, and hide the next real kill.
	 */
	@JvmStatic
	fun closeSession(reason: String) {
		try {
			val f = markerFile ?: return
			write(f, "$STATE_CLOSED|$reason|${stamp()}")
			line("Session closed :: $reason")
		} catch (e: Exception) {
			line("closeSession failed :: ${e.javaClass.simpleName}")
		}
	}

	/** Synchronous and tiny -- this must survive the killProcess that follows it in CrashHandler. */
	private fun write(f: File, content: String) {
		f.writeText(content)
	}

	/**
	 * Available memory and usable disk. These are what turn "the process died" into "the process
	 * died with 23 MB free", which is the difference between guessing and knowing.
	 */
	private fun describeResources(context: Context): String = try {
		val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
		val mi = ActivityManager.MemoryInfo()
		am.getMemoryInfo(mi)
		val disk = StorageGuard.usableBytes(context)
		"availMem=${mi.availMem / 1048576}MB totalMem=${mi.totalMem / 1048576}MB " +
			"lowMemory=${mi.lowMemory} threshold=${mi.threshold / 1048576}MB " +
			"usableDisk=${if (disk < 0) "unknown" else "${disk / 1048576}MB"}"
	} catch (e: Exception) {
		"unavailable (${e.javaClass.simpleName})"
	}
}
