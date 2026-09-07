package helpers

import java.io.File

/**
 * Counts crashes that happen BEFORE the terminal reaches a usable screen.
 *
 * The distinction is the whole point. A crash on the Result screen, or anywhere else after the
 * home screen has loaded, is transient: a fresh start fixes it and the terminal is back in service
 * in seconds, so it must restart instantly no matter how often it happens. Pacing those would cost
 * real transactions for no benefit.
 *
 * A crash that happens before any home screen resumes is a different animal -- it reproduces on
 * every start, so restarting immediately just spins: crash, restart, crash, ~2s apart, forever.
 * That is what fills the disk with crash logs and never leaves TMS a window to push a fix. Only
 * those crashes are counted here, and only they get backed off.
 *
 * [markHealthy] is the reset: reaching a home screen means the previous start worked, so the
 * counter goes back to zero and the next crash is treated as transient again.
 *
 * Format matches [LogSessionMarker] deliberately -- a single pipe-delimited line, no JSON parser,
 * nothing that can throw on a malformed read. It is written from a dying process.
 */
object CrashState {

	private const val STATE_NAME = "crash.state"

	/** Backoff ladder for consecutive pre-home-screen crashes, indexed by count-1. */
	private val BACKOFF_MS = longArrayOf(2_000, 2_000, 2_000, 15_000, 30_000, 60_000)

	@Volatile
	private var stateFile: File? = null

	/** Call once from Application.attachBaseContext, before the crash handler can fire. */
	@JvmStatic
	fun init(filesDir: File) {
		stateFile = File(filesDir, STATE_NAME)
	}

	/**
	 * Record a crash and return the new consecutive pre-home-screen count.
	 *
	 * Runs on the crash path, so it does the least possible work and never throws: on any failure
	 * it reports 1, which pages the caller onto the shortest backoff rather than the longest.
	 */
	@JvmStatic
	fun recordCrash(signature: String): Int = try {
		val f = stateFile
		if (f == null) {
			1
		} else {
			val next = readCount(f) + 1
			f.writeText("$next|$signature")
			next
		}
	} catch (e: Exception) {
		1
	}

	/**
	 * The terminal reached a usable screen, so whatever happened before is not a boot loop.
	 *
	 * Called on every home-screen resume, so it skips the write when the counter is already zero --
	 * which is the overwhelmingly common case.
	 */
	@JvmStatic
	fun markHealthy() {
		try {
			val f = stateFile ?: return
			if (!f.exists() || readCount(f) == 0) return
			f.writeText("0|")
		} catch (e: Exception) {
			// Bookkeeping must never affect a screen becoming visible.
		}
	}

	/** Consecutive pre-home-screen crashes, 0 if none or unreadable. */
	@JvmStatic
	fun consecutiveCrashes(): Int = try {
		stateFile?.let { readCount(it) } ?: 0
	} catch (e: Exception) {
		0
	}

	/** How long the recovery screen should wait before relaunching, for a given crash count. */
	@JvmStatic
	fun backoffMsFor(count: Int): Long = when {
		count <= 0 -> 0L
		count > BACKOFF_MS.size -> BACKOFF_MS.last()
		else -> BACKOFF_MS[count - 1]
	}

	private fun readCount(f: File): Int {
		if (!f.exists()) return 0
		return f.readText().substringBefore('|').trim().toIntOrNull() ?: 0
	}
}
