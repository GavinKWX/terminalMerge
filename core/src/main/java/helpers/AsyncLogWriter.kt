package helpers

import android.util.Log
import enums.EnumLogFileName
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lightweight async file logger. Replaces the previous synchronous open-write-close
 * per line (which blocked the calling/UI thread) without any external logging library
 * (no slf4j/logback) — those caused unresolvable SLF4J binding conflicts on this build.
 *
 * Design:
 *  - All async writes run on a single daemon thread, so writers/rotation need no locks.
 *  - One BufferedWriter is held open per stream (no open/close per line).
 *  - Flushing is BATCHED: every FLUSH_LINE_THRESHOLD lines, or FLUSH_INTERVAL_MS after the
 *    first still-unflushed line, whichever comes first. A flush-per-line (the previous
 *    behaviour) is the dominant cost on terminal eMMC and is what made the log lag behind the
 *    app; batching collapses hundreds of flushes per transaction into a handful.
 *  - Callers that need a hard durability point call flushSoon() (non-blocking, enqueued behind
 *    the writes already queued) or drain() (blocking, bounded). See HelperLog.logToFile.
 *  - Rotation matches the legacy contract the TMS upload job relies on
 *    (TmsHelper.uploadAllTerminalLog): active file = "<StreamName>.txt"; once it passes
 *    MAX_FILE_BYTES it is renamed to "<StreamName>_<timestamp>.txt" (a name that fails
 *    EnumLogFileName.valueOf, so the uploader treats it as a backup to upload + delete).
 *  - Plain .txt, no gzip — the uploader ships raw bytes.
 *  - The exception stream is written SYNCHRONOUSLY (writeSync) so crash lines survive the
 *    Process.killProcess in CrashHandler. It owns its file exclusively (never via the async
 *    path), so there is no cross-thread file contention.
 */
object AsyncLogWriter {

	/*
	 * D6 was 5 MB x (1+5) to hold the log footprint down. HelperLog now writes every appendLine
	 * straight to disk as well as buffering it (two sinks), which roughly doubles the bytes per
	 * transaction, so 5 MB x 5 backups would have halved the wall-clock history the file covers.
	 * 10 MB x 10 restores it: ~110 MB for TerminaLog, and every rotated file is uploaded to TMS
	 * and deleted anyway — the cap only bounds what piles up on a terminal that cannot reach TMS.
	 */
	private const val MAX_FILE_BYTES = 10L * 1000 * 1024 // 10 MB

	/** Fallback backup count when a stream name does not map to an EnumLogFileName. */
	private const val DEFAULT_MAX_BACKUPS = 4

	/*
	 * Flush policy. The old code flushed every line, so the executor could not drain faster than
	 * the app produced lines and the queue became the log's loss window: on a process kill,
	 * everything still queued died with the daemon thread (Android gives no shutdown hook).
	 * Batching removes the per-line cost; the deadline bounds how stale the file can get.
	 */
	private const val FLUSH_LINE_THRESHOLD = 20
	private const val FLUSH_INTERVAL_MS = 100L

	/** Upper bound on how long a blocking drain() will hold up its caller. */
	private const val DRAIN_TIMEOUT_MS = 2_000L

	// D1 — the amplifier. When the disk is full EVERY write fails, and logging each failure with
	// its throwable wrote thousands of stack traces back to the same full disk (Aisino measured
	// 6,471 in 23 minutes). Report at most one line per interval, and never the stack trace.
	private const val FAILURE_LOG_INTERVAL_MS = 60_000L

	@Volatile
	private var lastFailureLogMs = 0L
	private val suppressedFailures = java.util.concurrent.atomic.AtomicInteger(0)

	private fun reportFailure(what: String, e: Exception) {
		val now = android.os.SystemClock.elapsedRealtime()
		if (now - lastFailureLogMs < FAILURE_LOG_INTERVAL_MS) {
			suppressedFailures.incrementAndGet()
			return
		}
		lastFailureLogMs = now
		val dropped = suppressedFailures.getAndSet(0)
		// Message only — no throwable. A stack trace here is what turns a full disk into a
		// self-sustaining write storm.
		Log.e("AsyncLogWriter", "$what: ${e.javaClass.simpleName}: ${e.message}" +
			if (dropped > 0) " (+$dropped suppressed)" else "")
	}

	/*
	 * Scheduled rather than plain single-thread: armDeadlineFlush() needs schedule(). Still one
	 * thread, so writers/pendingLines/deadlineFlushArmed remain lock-free.
	 */
	private val executor = Executors.newSingleThreadScheduledExecutor { r ->
		Thread(r, "AsyncLogWriter").apply { isDaemon = true }
	}

	/** Unflushed line count per stream. Touched only on the executor thread. */
	private val pendingLines = HashMap<String, Int>()

	/** True while a deadline flush is already armed. Touched only on the executor thread. */
	private var deadlineFlushArmed = false

	/*
	 * Coalesces pending flushes. logToFile calls flushSoon at every function/process boundary,
	 * and there are hundreds of those call sites across the app -- a sale alone closes ten
	 * segments. Without this, a burst of boundaries enqueues a flushAll each, which walks every
	 * open writer and defeats the batching this class exists to provide. One pending flush is
	 * enough: it runs after whatever is already queued, so it covers all of them.
	 */
	private val flushQueued = java.util.concurrent.atomic.AtomicBoolean(false)

	@Volatile
	private var logDir: File? = null

	/** Open writers for async streams, keyed by stream name. Touched only on the executor thread. */
	private val writers = HashMap<String, BufferedWriter>()

	@JvmStatic
	fun init(dir: File) {
		try {
			if (!dir.exists()) dir.mkdirs()
		} catch (e: Exception) {
			reportFailure("init mkdirs failed", e)
		}
		logDir = dir
	}

	/** Async, batched write for general/DB streams. Returns immediately. */
	@JvmStatic
	fun write(streamName: String, message: String) {
		// D7 — scrub here, not at the callers and not in FileLoggingTree. Every path to disk
		// funnels through this object: Timber/FileLoggingTree for Utils.printLog, and HelperLog
		// directly for appendLine/logToFile. Scrubbing in FileLoggingTree alone still let the
		// HelperLog path through — measured, 10 raw PANs survived a 10-sale stress run.
		val safe = LogRedact.scrubPans(message)
		executor.execute {
			try {
				val dir = logDir ?: return@execute
				val file = File(dir, "$streamName.txt")
				if (file.exists() && file.length() > MAX_FILE_BYTES) {
					// close() flushes, so nothing is pending against the rotated file any more.
					writers.remove(streamName)?.let { runCatching { it.close() } }
					// Reset the counter HERE, not in rotate(): rotate() is also reached from
					// writeSync() on the caller's thread, and pendingLines is executor-thread-only.
					pendingLines[streamName] = 0
					rotate(dir, file, streamName)
				} else if (!file.exists()) {
					// The file vanished under us (TMS upload job deleted it, or a manual clear).
					// The cached writer points at an unlinked inode, so drop it and reopen.
					writers.remove(streamName)?.let { runCatching { it.close() } }
					pendingLines[streamName] = 0
				}

				val writer = writers.getOrPut(streamName) { BufferedWriter(FileWriter(file, true)) }
				writer.write(safe)
				writer.newLine()

				val unflushed = (pendingLines[streamName] ?: 0) + 1
				if (unflushed >= FLUSH_LINE_THRESHOLD) {
					writer.flush()
					pendingLines[streamName] = 0
				} else {
					pendingLines[streamName] = unflushed
					armDeadlineFlush()
				}
			} catch (e: Exception) {
				reportFailure("write failed for $streamName", e)
			}
		}
	}

	/**
	 * Synchronous write for the exception stream — guarantees durability when the caller
	 * (CrashHandler) kills the process right after. Owns its file exclusively (never via the
	 * async path), so no BufferedWriter is ever held open against this file and there is no
	 * unflushed state to interleave with -- which is what keeps open-write-close safe here even
	 * though the async streams are now batched rather than flushed per line.
	 */
	@JvmStatic
	fun writeSync(streamName: String, message: String) {
		val safe = LogRedact.scrubPans(message)
		try {
			val dir = logDir ?: return
			val file = File(dir, "$streamName.txt")
			if (file.exists() && file.length() > MAX_FILE_BYTES) {
				rotate(dir, file, streamName)
			}
			BufferedWriter(FileWriter(file, true)).use { w ->
				w.write(safe)
				w.newLine()
			}
		} catch (e: Exception) {
			reportFailure("writeSync failed for $streamName", e)
		}
	}

	/**
	 * Non-blocking durability point. Enqueues a flush behind whatever is already queued, so by the
	 * time it runs, every write issued before this call is on disk. Used by HelperLog.logToFile at
	 * every function/process boundary: it must not block, because some of those boundaries are hit
	 * on the main thread and a bounded wait there is an ANR risk.
	 */
	@JvmStatic
	fun flushSoon() {
		// One pending flush is enough. A write enqueued AFTER that flush task is not covered
		// by it -- it is covered by the FLUSH_INTERVAL_MS deadline armed by write() instead, so
		// the worst case stays the same bounded window rather than an immediate flush.
		if (!flushQueued.compareAndSet(false, true)) return
		try {
			executor.execute {
				flushQueued.set(false)
				flushAll()
			}
		} catch (e: Exception) {
			flushQueued.set(false)
			// RejectedExecutionException after shutdownAndDrain() -- nothing left to flush.
			reportFailure("flush enqueue failed", e)
		}
	}

	/**
	 * Blocking durability point, bounded by DRAIN_TIMEOUT_MS. For the few callers that are about
	 * to lose the process and can afford to wait: AppServices.onDestroy / onTrimMemory.
	 * Does NOT shut the executor down -- logging continues afterwards.
	 */
	@JvmStatic
	fun drain() {
		val done = CountDownLatch(1)
		try {
			executor.execute {
				try { flushAll() } finally { done.countDown() }
			}
			done.await(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
		} catch (e: Exception) {
			reportFailure("drain failed", e)
		}
	}

	/**
	 * Last-gasp flush for CrashHandler, which calls Process.killProcess immediately after. The
	 * exception stream is already durable (writeSync), but every queued TerminaLog line -- the
	 * lines leading up to the crash, which are the ones worth having -- dies with the daemon
	 * thread unless we drain first. Closes the writers: nothing should log after this.
	 */
	@JvmStatic
	fun shutdownAndDrain() {
		try {
			executor.execute {
				flushAll()
				writers.values.forEach { runCatching { it.close() } }
				writers.clear()
			}
			executor.shutdown()
			executor.awaitTermination(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
		} catch (e: Exception) {
			reportFailure("shutdownAndDrain failed", e)
		}
	}

	/** Executor thread only. Arms one deferred flush so a quiet stream still lands on disk. */
	private fun armDeadlineFlush() {
		if (deadlineFlushArmed) return
		deadlineFlushArmed = true
		try {
			executor.schedule({
				deadlineFlushArmed = false
				flushAll()
			}, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
		} catch (e: Exception) {
			deadlineFlushArmed = false
			reportFailure("flush schedule failed", e)
		}
	}

	/** Executor thread only. */
	private fun flushAll() {
		writers.forEach { (name, writer) ->
			try {
				writer.flush()
				pendingLines[name] = 0
			} catch (e: Exception) {
				reportFailure("flush failed for $name", e)
			}
		}
	}

	private fun rotate(dir: File, file: File, streamName: String) {
		val ts = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH).format(Date())
		file.renameTo(File(dir, "${streamName}_$ts.txt"))
		purgeOldBackups(dir, streamName)
	}

	/**
	 * Delete the OLDEST backups down to the retention limit.
	 *
	 * Ordered by the yyyyMMddHHmmss stamp in the filename, not lastModified(). mtime is the wrong
	 * key twice over: rotations landing in the same second tie, and with a tie the delete order is
	 * whatever listFiles() happened to return -- which can take out the backup that was just
	 * rotated instead of the stale one. It is also fragile against anything that rewrites mtime
	 * (a pull/push, a restore, the TMS upload job touching files). The name is authoritative and
	 * lexicographic order on a fixed-width stamp is chronological order.
	 *
	 * This is a re-fix of the bug class in commit e5d3ff78 ("Fix HelperLog delete newest file
	 * instead of old file"), which the move to AsyncLogWriter reintroduced in a different form.
	 * Reproduced on an MF919: 12 same-second backups plus a fresh 17.7 MB rotation, and the
	 * 17.7 MB one was the file deleted.
	 */
	private fun purgeOldBackups(dir: File, streamName: String) {
		val max = maxBackupsFor(streamName)
		val prefix = "${streamName}_"
		val backups = dir.listFiles { f ->
			f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".txt")
		}?.sortedWith(
			// Name first (chronological for the fixed-width stamp), mtime only as a tiebreak for
			// any legacy name the stamp cannot be read from.
			compareBy({ it.name.removePrefix(prefix).removeSuffix(".txt") }, { it.lastModified() })
		) ?: return
		if (backups.size > max) {
			backups.take(backups.size - max).forEach { runCatching { it.delete() } }
		}
	}

	/**
	 * Retention is driven by the existing EnumLogFileName contract (TerminaLog=10, etc.) rather
	 * than a map here, so the enum is the single source of truth. The previous local map silently
	 * overrode the enum, which meant editing EnumLogFileName had no effect on what was kept.
	 */
	private fun maxBackupsFor(streamName: String): Int =
		try {
			EnumLogFileName.valueOf(streamName).maximumFile
		} catch (e: IllegalArgumentException) {
			DEFAULT_MAX_BACKUPS
		}
}
