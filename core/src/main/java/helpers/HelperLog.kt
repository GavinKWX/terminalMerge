package helpers

import android.util.Log
import com.google.gson.Gson
import enums.EnumDateFormat
import enums.EnumLogFileName
import java.text.SimpleDateFormat
import java.util.*

/**
 * Single consolidated logging type for the app.
 *
 * Writing is delegated to [AsyncLogWriter]: appends are batched and flushed on a single
 * background thread, and file rotation/retention is handled there. This class performs no
 * synchronous disk I/O on the calling thread and does not hand-roll rotation.
 *
 * TWO SINKS. Every [appendLine] does both:
 *  1. writes the line to TerminaLog immediately, tagged with this instance's [RowIdentifier];
 *  2. accumulates it for the bulk JSON block emitted by [logToFile].
 *
 * Sink 1 is the point of the design. Previously appendLine went to logcat and an in-memory
 * buffer only, so a whole flow existed nowhere on disk until its single logToFile call --
 * and since that call only enqueues, a process kill anywhere in between erased the lot. That
 * is how a completed sale could upload its receipt and still leave no trace in TerminaLog.
 *
 * So the line trail is the source of truth on disk; the block is a grouped convenience view.
 * Losing a block costs the grouping, never the evidence.
 *
 * Blocks are SEGMENTS: [logToFile] clears the buffer, so each block holds only the lines since
 * the previous flush. Before, Msg was never cleared and every re-flush re-serialised the whole
 * growing buffer -- the duplicate RowIdentifier blocks in the logs, each a superset of the last.
 *
 * The INSTANCE shape (fields below) is unchanged: it is GSON-serialized as one JSON line
 * per [logToFile] call, which is the on-disk content the TMS upload job
 * (TmsHelper.uploadAllTerminalLog) ships to the server. Do not rename/remove fields
 * without coordinating that contract.
 *
 * The COMPANION API (init/appendLine/logToFile over a StringBuilder) is the former
 * com.sc.mf919pro.kotlin.helper_common.HelperLog, folded in here so its callers
 * (DbHandler, Migration1001, AppServices, Utils) only changed their import.
 */
class HelperLog(
	session: String,
	isWiFi: Boolean,
	ipAddress: String?,
	deviceScreen: String?,
	deviceObject: String?,
	activity: String?
) {
	var Session: String = session
	var RowIdentifier: String = UUID.randomUUID().toString().substring(0, 7)
	var IsWiFi: Boolean? = isWiFi
	var IPAddress: String? = ipAddress
	var DeviceScreen: String? = deviceScreen
	var DeviceObject: String? = deviceObject
	var Activity: String? = activity
	private var Msg: StringBuilder = StringBuilder()

	/*
	 * @Transient is REQUIRED: this instance is GSON-serialized wholesale by logToFile, and
	 * without it the lock object is emitted as a stray "msgLock":{} field in every JSON block
	 * uploaded to TMS -- changing the on-disk contract the uploader parses. Verified on device.
	 *
	 * Msg is touched from several threads for one flow: the card/payment path appends from a
	 * Dispatchers.IO coroutine, the EMV path from the EMV callback thread, and onDestroyView from
	 * main. Unsynchronised, two logToFile calls in the same millisecond both pass the isEmpty()
	 * check and both serialise the same buffer -- observed on a live sale, where "Search Card End"
	 * and "EMV process finished" flushed together and produced two byte-identical blocks. Only
	 * in-memory work happens under this lock; the disk write stays outside it.
	 */
	@Transient
	private val msgLock = Any()
	var LogDate: String = sdf().format(Date())

	fun appendLine(className: String, key: String, msg: String) {
		try {
			Log.d(className, "$key\t: $msg")
			appendToMsg("${sdf().format(Date())} -($className) ${key}\t: $msg")
		} catch (ex: Exception) {
			// Message only, never the stack trace: a trace per failed write is how a full disk
			// turns a contained error into an amplification loop (see AsyncLogWriter D1).
			Log.w("HelperLog", "log call failed: ${ex.javaClass.simpleName}: ${ex.message}")
		}
	}

	fun appendLine(className: String, msg: String) {
		try {
			Log.d(className, msg)
			appendToMsg("${sdf().format(Date())} -($className) $msg")
		} catch (ex: Exception) {
			// Message only, never the stack trace: a trace per failed write is how a full disk
			// turns a contained error into an amplification loop (see AsyncLogWriter D1).
			Log.w("HelperLog", "log call failed: ${ex.javaClass.simpleName}: ${ex.message}")
		}
	}

	/**
	 * Sink 1 (per-line, straight to disk) + sink 2 (bulk buffer). [line] is already in the exact
	 * on-disk format, so both sinks take the same value -- no second formatter to keep in step,
	 * and the per-line entries are indistinguishable in shape from the Timber lines
	 * ([FileLoggingTree]) they interleave with.
	 */
	private fun appendToMsg(line: String) {
		// Outside the lock: the writer has its own single-threaded queue, and holding msgLock
		// across it would serialise every logging thread behind the slowest one.
		AsyncLogWriter.write(EnumLogFileName.TerminaLog.name, "$line [$RowIdentifier]")

		synchronized(msgLock) {
			// A segment's LogDate should be when the segment started, not when the instance was
			// constructed -- otherwise every block of a long transaction reports the same timestamp.
			if (Msg.isEmpty()) LogDate = sdf().format(Date())

			Msg.appendLine(line)
			if (Msg.length > MAX_MSG_LENGTH) {
				val overflow = Msg.length - MAX_MSG_LENGTH
				Msg.delete(0, overflow)
			}
		}
	}

	/**
	 * Emit the lines accumulated since the last flush as one JSON block, then clear the buffer.
	 * Call this at a function / class / process boundary -- it is the "bulk process flow" record.
	 *
	 * Nothing is lost by clearing: every line in the block was already written individually by
	 * [appendToMsg]. An empty buffer emits nothing, so the repeated no-op flushes on paths with
	 * several exit points stop producing near-empty duplicate blocks.
	 *
	 * CONSEQUENCE -- do not chain two flushes. The old idiom
	 *
	 *     logToFile(TerminaLog); logToFile(TerminaLogException)
	 *
	 * worked only because the buffer was never cleared. Now the first call consumes the segment
	 * and the second writes NOTHING -- silently losing the durable, synchronous record on
	 * exactly the error paths that need it. On an error path flush to TerminaLogException
	 * ALONE: sink 1 has already put every line in TerminaLog, so the block belongs in the
	 * stream that survives a process kill. 17 such chained call sites were removed when this
	 * was found.
	 */
	fun logToFile(fileName: EnumLogFileName) {
		try {
			// Serialise and clear as one atomic step, or two threads flushing together each emit
			// the same segment. gson.toJson is thread-safe and in-memory, so it belongs in here.
			val json = synchronized(msgLock) {
				if (Msg.isEmpty()) return
				val snapshot = gson.toJson(this)
				Msg.setLength(0)
				snapshot
			}

			if (fileName == EnumLogFileName.TerminaLogException) {
				AsyncLogWriter.writeSync(fileName.name, json)
			} else {
				AsyncLogWriter.write(fileName.name, json)
				// Durability point at the boundary: enqueued behind the block write, so by the
				// time it runs this whole segment is on disk. Non-blocking on purpose -- some
				// boundaries are hit on the main thread and a bounded wait there risks an ANR.
				AsyncLogWriter.flushSoon()
			}
		} catch (ex: Exception) {
			// Message only, never the stack trace: a trace per failed write is how a full disk
			// turns a contained error into an amplification loop (see AsyncLogWriter D1).
			Log.w("HelperLog", "log call failed: ${ex.javaClass.simpleName}: ${ex.message}")
		}
	}

	companion object {
		/** ~256 KB cap on a single instance's accumulated buffer. */
		private const val MAX_MSG_LENGTH = 256 * 1024

		private val gson = Gson()

		private val sdfThreadLocal = object : ThreadLocal<SimpleDateFormat>() {
			override fun initialValue(): SimpleDateFormat =
				SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmssSSS.dateFormat, Locale.ENGLISH)
		}

		private fun sdf(): SimpleDateFormat = sdfThreadLocal.get()!!

		var NTab = 0

		/*
		 * Correlation ids for the StringBuilder (companion) API used by AppServices, the
		 * schedulers, DbHandler and the Migration* classes.
		 *
		 * The instance API hangs its RowIdentifier off the object. Here the context IS the
		 * caller-owned StringBuilder, so the id is keyed off that. StringBuilder does not override
		 * equals/hashCode, so WeakHashMap gives identity semantics and drops the entry once the
		 * caller's buffer is collected -- no leak from jobs that never flush. Synchronized because
		 * WorkManager runs several of these jobs concurrently.
		 */
		private val sbRowIds = Collections.synchronizedMap(WeakHashMap<StringBuilder, String>())

		/** Buffers built without init() still log, just uncorrelated. */
		private fun rowIdFor(sbLog: StringBuilder): String = sbRowIds[sbLog] ?: "-------"

		/**
		 * Sink 1 for the companion API. Same two-sink contract as the instance path: the line hits
		 * disk now, and the caller's StringBuilder still accumulates for its logToFile blob. Always
		 * TerminaLog, even when the blob goes to TerminaDbException, so the per-line timeline stays
		 * in one file.
		 */
		private fun writeLine(line: String, sbLog: StringBuilder) {
			AsyncLogWriter.write(EnumLogFileName.TerminaLog.name, "$line [${rowIdFor(sbLog)}]")
		}

		fun init(moduleName: String): StringBuilder {
			NTab = 0
			val currentDate = sdf().format(Date())
			val sbLog = StringBuilder()
			sbRowIds[sbLog] = UUID.randomUUID().toString().substring(0, 7)
			sbLog.append("--------------------------------------------------")
			sbLog.append("\n")
			sbLog.append("$currentDate (Init) : $moduleName")
			sbLog.append("\n")
			writeLine("$currentDate (Init) : $moduleName", sbLog)
			return sbLog
		}

		fun appendLine(sbLog: StringBuilder, msg: String) {
			var strNTab = "\t"
			for (i in 0 until NTab) {
				strNTab += "\t"
			}
			val currentDate = sdf().format(Date())
			val line = "$currentDate$strNTab - $msg"
			writeLine(line, sbLog)
			sbLog.append(line)
			sbLog.append("\n")
		}

		fun appendLine(sbLog: StringBuilder, key: String, msg: String) {
			var strNTab = "\t"
			for (i in 0 until NTab) {
				strNTab += "\t"
			}
			val currentDate = sdf().format(Date())
			val line = "$currentDate$strNTab - $key\t: $msg"
			writeLine(line, sbLog)
			sbLog.append(line)
			sbLog.append("\n")
		}

		/** Flush an accumulated StringBuilder block to the async writer (rotated). */
		fun logToFile(sbLog: StringBuilder, fileName: EnumLogFileName) {
			try {
				if (fileName == EnumLogFileName.TerminaLogException) {
					AsyncLogWriter.writeSync(fileName.name, sbLog.toString())
				} else {
					AsyncLogWriter.write(fileName.name, sbLog.toString())
					// Durability point at the boundary, same as the instance path. The buffer is
					// caller-owned so it is deliberately NOT cleared -- callers build one
					// StringBuilder per flow and some flush it from mutually exclusive branches.
					AsyncLogWriter.flushSoon()
				}
			} catch (ex: Exception) {
				Log.w("HelperLog", "logToFile failed: ${ex.javaClass.simpleName}: ${ex.message}")
			}
		}
	}
}
