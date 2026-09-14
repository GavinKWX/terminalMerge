package helpers

/**
 * Redaction helpers for anything that reaches a log.
 *
 * D7. Two things make this non-optional on this codebase:
 *  - `HelperLog`/`Utils.printLog` do not just go to logcat. They route through Timber to
 *    `FileLoggingTree`, which is planted **unconditionally** in `MF919.java` (only `DebugTree` is
 *    debug-gated), so they persist to `TerminalLog.txt` in release builds — and
 *    `TmsHelper.uploadAllTerminalLog` then ships that file to the backend.
 *  - `println` ignores build type entirely; a `BuildConfig.DEBUG` guard elsewhere does not help.
 *
 * So a card number written to a log on a production terminal leaves the device. Track 2 carries
 * the full PAN, the expiry and the service code; keys must never appear at all.
 */
object LogRedact {

	/**
	 * Masks a Track 2 string (or a DE57 field built from one) for logging.
	 *
	 * Keeps the first 6 (BIN) and last 4 digits of the PAN — the industry-standard maximum that
	 * may be retained — and drops everything from the field separator onwards, since the expiry
	 * and discretionary data have no diagnostic value and are exactly what must not be stored.
	 * The length is preserved in the output so the log still shows whether the field was the size
	 * the parser expected, which is what these lines are actually for.
	 */
	@JvmStatic
	fun track2(value: String?): String {
		if (value.isNullOrEmpty()) return "<empty>"
		val sep = value.indexOfFirst { it == 'D' || it == 'd' || it == '=' }
		val pan = if (sep > 0) value.substring(0, sep) else value
		val masked = when {
			pan.length <= 10 -> "*".repeat(pan.length)
			else -> pan.take(6) + "*".repeat(pan.length - 10) + pan.takeLast(4)
		}
		return "$masked[len=${value.length}]"
	}

	/** For any value that must never be logged in full — keys, KCVs, cleartext key material. */
	@JvmStatic
	fun secret(value: String?): String =
		if (value.isNullOrEmpty()) "<empty>" else "<redacted:${value.length} chars>"

	/**
	 * The card data of the transaction in flight, registered when the card is read so the log sink
	 * can redact it wherever it appears — including in raw TLV/hex dumps nobody enumerated.
	 *
	 * Why exact-value matching rather than a generic PAN detector: the first attempt here scrubbed
	 * any Luhn-valid 13-19 digit run, which is far too weak a filter. Measured on a real 12-sale
	 * run it masked 165 occurrences of a hex-encoded ASCII payload, 121 of EMV AID/tag data and
	 * 76 of hex "000..." — corrupting exactly the ISO diagnostics these logs exist for, while the
	 * genuine PAN was only 176 of them. Redacting the *known* value is exact: no false positives,
	 * no missed sites, and it costs one string search per log line instead of a Luhn scan.
	 */
	@Volatile
	private var livePan: String? = null

	@Volatile
	private var liveTrack2: String? = null

	@Volatile
	private var livePanMask: String = ""

	/**
	 * Which registration owns the data above.
	 *
	 * D11. Without this, the screen that ENDS one transaction could wipe the card data the NEXT
	 * transaction had already registered. Measured on Pro: a void registers its PAN at screen
	 * entry, and the outgoing result screen of the previous sale reached onDestroyView 150 ms
	 * later -- inside that window -- so every following void line reached the uploaded log
	 * unmasked. Clearing is now gated on owning the current registration.
	 */
	@Volatile
	private var generation = 0L

	/** The registration in force right now. Snapshot it on screen entry, hand it back to [clearCardData]. */
	@JvmStatic
	@Synchronized
	fun currentGeneration(): Long = generation

	/**
	 * Called as soon as the card is read. Safe to call repeatedly with the same value.
	 *
	 * @return the generation this registration created, for a later [clearCardData].
	 */
	@JvmStatic
	@Synchronized
	fun registerCardData(pan: String?, track2: String?): Long {
		val p = pan?.filter { it.isDigit() }
		if (!p.isNullOrEmpty() && p.length >= 12) {
			livePan = p
			livePanMask = p.take(6) + "*".repeat(p.length - 10) + p.takeLast(4)
		}
		val t = track2?.trim()
		if (!t.isNullOrEmpty() && t.length >= 12) liveTrack2 = t
		generation += 1
		return generation
	}

	/**
	 * Called when a transaction ends; card data must not outlive it.
	 *
	 * Only clears if [generation] is still the one in force. A caller holding a stale generation
	 * is a screen being torn down after a newer transaction already registered -- its clear must
	 * not take that newer transaction's data with it.
	 */
	@JvmStatic
	@Synchronized
	fun clearCardData(generation: Long) {
		if (generation != this.generation) return
		livePan = null
		liveTrack2 = null
		livePanMask = ""
	}

	/**
	 * Redacts the in-flight card data from a line on its way to disk.
	 *
	 * Applied in `AsyncLogWriter` — the single sink every path funnels through. Scrubbing in
	 * `FileLoggingTree` instead is NOT sufficient and was measured to miss 10 raw PANs in a
	 * 10-sale run: `HelperLog.appendLine`/`logToFile` write to `AsyncLogWriter` directly and
	 * never pass through Timber.
	 */
	@JvmStatic
	fun scrubPans(line: String): String {
		var out = line
		val t = liveTrack2
		if (!t.isNullOrEmpty() && out.contains(t)) out = out.replace(t, track2(t))
		val p = livePan
		if (!p.isNullOrEmpty() && out.contains(p)) out = out.replace(p, livePanMask)
		return out
	}
}
