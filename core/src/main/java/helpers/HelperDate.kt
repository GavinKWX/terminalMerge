package helpers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Date formatting shared by :core.
 *
 * Lifted out of HelperCommon, which cannot move into :core yet (it still reaches for
 * DeviceHelper, the attend screens and ServiceHolder). The TMS handlers use this for the
 * `TERMINAL-DT` header on every call, so it has to be here; each app's HelperCommon
 * delegates to it, keeping one implementation.
 *
 * Locale.ENGLISH is deliberate: these strings go on the wire to TMS, so they must not follow
 * the device locale.
 */
object HelperDate {

	@JvmStatic
	fun getDateString(dateFormat: String): String =
		SimpleDateFormat(dateFormat, Locale.ENGLISH).format(Date())

	@JvmStatic
	fun getDateString(date: Date, dateFormat: String): String =
		SimpleDateFormat(dateFormat, Locale.ENGLISH).format(date)
}
