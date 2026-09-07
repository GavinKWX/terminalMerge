package helpers

import android.util.Log
import enums.EnumDateFormat
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timber tree that persists log calls to the rolling terminal log via AsyncLogWriter
 * (background thread). Used by Utils.debugLogPrint and friends.
 *
 * The timestamp + tag are embedded in the message (the writer adds nothing), preserving
 * the raw on-disk content the TMS upload job ships. ERROR/WARN go to the exception stream
 * (synchronous, durable); everything else to the general log (async).
 */
class FileLoggingTree : Timber.Tree() {

	private val sdf = object : ThreadLocal<SimpleDateFormat>() {
		override fun initialValue(): SimpleDateFormat =
			SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmssSSS.dateFormat, Locale.ENGLISH)
	}

	override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
		// D7 scrubbing happens in AsyncLogWriter (the single sink every path funnels through),
		// not here — HelperLog writes to it directly and would bypass a scrub placed at this level.
		val line = "${sdf.get()!!.format(Date())} -($tag) $message"
		when (priority) {
			Log.ERROR, Log.WARN -> AsyncLogWriter.writeSync("TerminaLogException", line)
			else -> AsyncLogWriter.write("TerminaLog", line)
		}
	}
}
