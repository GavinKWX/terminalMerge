package iso

import android.content.Context
import enums.EnumLogFileName
import helpers.HelperLog

/**
 * Keeps STAN, invoice and batch numbers monotonic across a database recopy.
 *
 * `stan`, `invoiceNo` and `batchNo` live in the `IsoBatchInfo` table, and the shipped asset DB
 * seeds all three to `000001`. So any event that recreates the DB from the asset -- the corrupt-DB
 * recovery in `DbHandler`, or a manual data clear -- silently rewinds every counter to 1 and the
 * terminal begins **reusing invoice and STAN numbers it has already sent to the host**. That is not
 * a local cosmetic problem: the acquirer keys reconciliation on those numbers.
 *
 * The fix mirrors each counter into SharedPreferences (which survives a DB wipe) as a high-water
 * mark, and reapplies it on startup if the DB has gone backwards.
 *
 * This is the second half of the counter story. `IsoBatchInfoRepo.allocateCounter` stops two
 * callers getting the *same* number going forward; this stops the whole sequence going *backwards*.
 *
 * **A local mitigation, not host reconciliation.** It prevents *new* reuse going forward. It cannot
 * repair numbers already duplicated before the mark existed, and a factory reset clears the prefs
 * along with the DB, taking the protection with it.
 *
 * Moved to `:core` from both apps (audit item 104), MF919's version. Prefs are opened from the
 * context, the same file both apps' `Helper.getPrefs(ctx)` opens, so existing marks carry over.
 * Pro's copy used the no-argument `getPrefs()`, which is null until `Helper.Initialize` and made
 * the guard silently do nothing on the cold start it exists to protect.
 */
object CounterGuard {

	private const val PREFIX = "hwm_"

	/** Counter tags that must never go backwards. Other IsoBatchInfo rows are config, not counters. */
	private val TRACKED = setOf("stan", "invoiceNo", "batchNo")

	private fun key(tag: String, subtag: String) = "$PREFIX${tag}_$subtag"

	private fun prefs(context: Context) = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)

	/** What [restore] does with one counter whose DB value is [current] against its [mark]. */
	internal enum class Action { KEEP, WRAP, RESTORE }

	/**
	 * A DB value merely *lower* than the mark is a rewind only when the counter has collapsed to
	 * near the asset seed. A small drop from a high value is a legitimate 999999 wrap, and forcing
	 * it back up would break the wrap rather than protect it.
	 */
	internal fun actionFor(mark: Int, current: Int): Action = when {
		current >= mark -> Action.KEEP
		mark > 900000 && current < 1000 -> Action.WRAP
		else -> Action.RESTORE
	}

	/**
	 * Records [value] as the high-water mark for (tag, subtag) if it exceeds what we have.
	 *
	 * Deliberately keeps the MAX rather than the latest: counters wrap at 999999 and a wrap is a
	 * legitimate move backwards, so storing the newest value blindly would let a wrap erase the
	 * mark. The wrap is handled at restore time instead -- see [restore].
	 */
	@JvmStatic
	fun record(mContext: Context, tag: String, subtag: String, value: String) {
		if (tag !in TRACKED) return
		val n = value.trim().toIntOrNull() ?: return
		try {
			val prefs = prefs(mContext)
			val k = key(tag, subtag)
			if (n > prefs.getInt(k, 0)) prefs.edit().putInt(k, n).apply()
		} catch (_: Exception) {
			// Never break a transaction over bookkeeping.
		}
	}

	/**
	 * Reapplies any high-water mark the DB has fallen behind, and reports how many it fixed.
	 *
	 * Must run **after** the schema exists (after migrations) and **before** the first transaction
	 * of the session, which is why it is called from MainActivity's startup path rather than from
	 * `DbHandler` -- calling repo code from inside `onOpen` would re-enter `getWritableDatabase`.
	 */
	@JvmStatic
	fun restore(mContext: Context): Int {
		val sbLog = HelperLog.init("CounterGuard")
		var repaired = 0
		try {
			val prefs = prefs(mContext)
			for ((k, v) in prefs.all) {
				if (!k.startsWith(PREFIX)) continue
				val mark = (v as? Int) ?: continue
				val rest = k.removePrefix(PREFIX)
				val tag = TRACKED.firstOrNull { rest.startsWith("${it}_") } ?: continue
				val subtag = rest.removePrefix("${tag}_")

				val current = CurrentStore.batchInfoValue(mContext, tag, subtag)?.trim()?.toIntOrNull() ?: 0
				when (actionFor(mark, current)) {
					Action.KEEP -> continue
					Action.WRAP -> {
						HelperLog.appendLine(sbLog, "Wrap detected, not restoring", "$tag/$subtag $current (mark $mark)")
						prefs.edit().putInt(k, current).apply()
					}
					Action.RESTORE -> {
						HelperLog.appendLine(sbLog, "COUNTER REWIND DETECTED",
							"$tag/$subtag db=$current mark=$mark -- restoring")
						CurrentStore.updateBatchInfo(mContext, String.format("%06d", mark), tag, subtag)
						repaired++
					}
				}
			}
			if (repaired > 0) {
				HelperLog.appendLine(sbLog, "Counters restored", repaired.toString())
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			}
		} catch (e: Exception) {
			try {
				HelperLog.appendLine(sbLog, "DbException", e.toString())
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			} catch (_: Exception) { }
		}
		return repaired
	}
}
