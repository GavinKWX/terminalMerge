package helpers

import enums.EnumLogFileName

import android.content.Context

/**
 * Free-space gate for the data partition.
 *
 * Ported from MF919's `kotlin/helper_common/StorageGuard.kt`, itself ported from Aisino's
 * `Helpers/StorageGuard.kt` (fix F3). MF919 Pro had no space check of any kind before this —
 * `grep -rn "usableSpace|getFreeSpace|StatFs" app/src/main` returned nothing.
 *
 * Why it matters: on a full disk SQLite raises SQLITE_FULL, and the terminal previously discovered
 * that only *after* the host had approved and the card had been charged. Refusing the transaction up
 * front — before the host is contacted and before the STAN/invoice counters advance — is the only
 * point at which nothing has happened yet and nothing needs reconciling. In the Aisino incident the
 * OS had been warning about low space for 40 minutes and the app had no way to notice.
 *
 * Thresholds match MF919/Aisino so the fleet behaves consistently. They are generous relative to what
 * one transaction needs (a few KB): stopping early costs one refused sale with a clear message,
 * stopping late costs a charged customer with no record.
 */
object StorageGuard {

	/** Below this, refuse to start a financial transaction. */
	const val BLOCK_BYTES = 50L * 1024 * 1024   // 50 MB

	/** Below this, still transact but surface a low-space warning. */
	const val WARN_BYTES = 200L * 1024 * 1024   // 200 MB

	/**
	 * `TransData.respCode` for a storage block, as HEX-ASCII — this codebase stores respCode hex
	 * encoded, not as the literal two characters. `HexString2ASCII("5A53")` = `"ZS"`, which
	 * `FragmentResult` turns into `CardErrorDataEnum.TAG_ZS` -> "Storage Full - Contact Support".
	 *
	 * Do NOT put the literal "ZS" in respCode: `EmvFragment.onlineProc` builds `"8A02$respCode"`
	 * and hands `HexString2ASCII(respCode)` to the EMV kernel as `EmvOnlineResult.REJCODE`, which
	 * the kernel parses numerically. A non-numeric REJCODE throws back across the binder and the
	 * kernel is never answered, hanging the UI on "waiting for approval".
	 */
	const val RESP_CODE_HEX = "5A53"   // ASCII "ZS"

	/**
	 * Usable bytes on the partition holding the app's private data. `usableSpace`, not `freeSpace`,
	 * because it accounts for the reserve the OS will not hand to a normal app.
	 *
	 * Returns -1 when it cannot be determined. Callers must NOT treat that as "full": a failed
	 * measurement is not evidence of a full disk, and blocking every sale on it would be a worse
	 * failure than the one being guarded against.
	 */
	@JvmStatic
	fun usableBytes(context: Context): Long = try {
		(context.filesDir ?: context.dataDir).usableSpace
	} catch (e: Exception) {
		try {
			val sb = HelperLog.init("StorageGuard")
			HelperLog.appendLine(sb, "usableBytes failed", e.toString())
			HelperLog.logToFile(sb, EnumLogFileName.TerminaDbException)
		} catch (_: Exception) { /* logging must never break the guard */ }
		-1L
	}

	/** True when there is room to record a transaction. Unknown space counts as OK — see above. */
	@JvmStatic
	fun canTransact(context: Context): Boolean {
		val free = usableBytes(context)
		return free < 0 || free >= BLOCK_BYTES
	}

	@JvmStatic
	fun shouldWarn(context: Context): Boolean {
		val free = usableBytes(context)
		return free in 0 until WARN_BYTES
	}

	@JvmStatic
	fun describe(context: Context): String {
		val free = usableBytes(context)
		return if (free < 0) "free=unknown" else "free=${free / (1024 * 1024)}MB"
	}

	/**
	 * Records a refusal where reconciliation would look for it. Nothing has been sent to the host at
	 * this point, so this is informational — explicitly NOT a reconciliation item.
	 */
	@JvmStatic
	fun logBlocked(context: Context, entryPoint: String) {
		try {
			val sb = HelperLog.init("StorageGuard")
			HelperLog.appendLine(sb, "TRANSACTION BLOCKED :: insufficient storage " +
				"(${describe(context)}) at $entryPoint")
			HelperLog.appendLine(sb, "No host request was sent; nothing to reconcile")
			HelperLog.logToFile(sb, EnumLogFileName.TerminaDbException)
		} catch (_: Exception) { /* logging must never break the guard */ }
	}

	/** Once-per-start low-space notice, so a filling disk is visible before it blocks anything. */
	@JvmStatic
	fun warnIfLow(context: Context) {
		if (!shouldWarn(context)) return
		try {
			val sb = HelperLog.init("StorageGuard")
			HelperLog.appendLine(sb, "LOW STORAGE WARNING :: ${describe(context)} " +
				"(warn<${WARN_BYTES / (1024 * 1024)}MB, block<${BLOCK_BYTES / (1024 * 1024)}MB)")
			HelperLog.logToFile(sb, EnumLogFileName.TerminaDbException)
		} catch (_: Exception) { /* logging must never break the guard */ }
	}
}
