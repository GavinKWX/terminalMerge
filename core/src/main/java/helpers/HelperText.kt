package helpers

/**
 * Small text helpers shared by :core.
 *
 * [oneLine] lived on HelperCommon, which cannot move into :core yet -- it still reaches for
 * DeviceHelper, the attend screens and ServiceHolder (see Phase 2/3). HelperHttp needs it to
 * collapse a JSON body onto a single log line, so the implementation lives here and each app's
 * HelperCommon.oneLine now delegates to it, keeping one definition rather than two.
 */
object HelperText {

	/** Collapses all whitespace runs to single spaces so a payload logs as one line. */
	@JvmStatic
	fun oneLine(value: String?): String =
		if (value.isNullOrEmpty()) "<empty>" else value.replace(Regex("""\s+"""), " ").trim()
}
