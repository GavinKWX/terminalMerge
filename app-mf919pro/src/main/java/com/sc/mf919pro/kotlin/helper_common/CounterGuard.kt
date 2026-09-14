package com.sc.mf919pro.kotlin.helper_common
import enums.EnumLogFileName

import android.content.Context
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import helpers.HelperLog

/**
 * D5 / F10 — keeps STAN, invoice and batch numbers monotonic across a database recopy.
 *
 * `stan`, `invoiceNo` and `batchNo` live in the `IsoBatchInfo` table, and the shipped asset DB
 * seeds all three to `000001`. So any event that recreates the DB from the asset — the corrupt-DB
 * recovery in `DbHandler.copyPreloadedDatabaseIfNeeded`, or a manual data clear — silently rewinds
 * every counter to 1 and the terminal begins **reusing invoice and STAN numbers it has already
 * sent to the host**. That is not a local cosmetic problem: the acquirer keys reconciliation on
 * those numbers, and Aisino's incident showed the offset shifting mid-batch after exactly this.
 *
 * The fix mirrors each counter into SharedPreferences (which survives a DB wipe) as a high-water
 * mark, and reapplies it on startup if the DB has gone backwards.
 *
 * **This is a local mitigation, not host reconciliation.** It prevents *new* reuse going forward.
 * It cannot repair numbers already duplicated before the mark existed, and if the device is
 * factory-reset (clearing prefs too) the protection is gone with it — the same caveat Aisino
 * recorded against its own F10.
 */
object CounterGuard {

	private const val PREFIX = "hwm_"

	/** Counter tags that must never go backwards. Others in IsoBatchInfo are config, not counters. */
	private val TRACKED = setOf("stan", "invoiceNo", "batchNo")

	private fun key(tag: String, subtag: String) = "$PREFIX${tag}_$subtag"

	/**
	 * Records [value] as the high-water mark for (tag, subtag) if it exceeds what we have.
	 *
	 * Deliberately keeps the MAX rather than the latest: counters wrap at 999999 and a wrap is a
	 * legitimate move backwards, so blindly storing the newest value would let a wrap erase the
	 * mark. A wrap is handled at restore time instead — see [restore].
	 */
	@JvmStatic
	fun record(mContext: Context, tag: String, subtag: String, value: String) {
		if (tag !in TRACKED) return
		val n = value.trim().toIntOrNull() ?: return
		try {
			val prefs = Helper.getInstance().getPrefs() ?: return
			val k = key(tag, subtag)
			if (n > prefs.getInt(k, 0)) prefs.edit().putInt(k, n).apply()
		} catch (_: Exception) {
			// Never break a transaction over bookkeeping.
		}
	}

	/**
	 * Reapplies any high-water mark the DB has fallen behind, and reports how many it fixed.
	 *
	 * Must run **after** the schema exists (i.e. after `DbHandler.onOpen`'s heal and after
	 * migrations) and **before** the first transaction of the session, which is why it is called
	 * from `MainActivity`'s startup path rather than from `DbHandler` itself — calling repo code
	 * from inside `onOpen` would re-enter `getWritableDatabase`.
	 *
	 * A DB value that is merely *lower* than the mark is treated as a rewind only when the gap is
	 * large; a small drop is what a legitimate 999999 wrap looks like, and forcing the counter back
	 * up would break the wrap instead of protecting it.
	 */
	@JvmStatic
	fun restore(mContext: Context): Int {
		val sbLog = HelperLog.init("CounterGuard")
		var repaired = 0
		try {
			val prefs = Helper.getInstance().getPrefs() ?: return 0
			for ((k, v) in prefs.all) {
				if (!k.startsWith(PREFIX)) continue
				val mark = (v as? Int) ?: continue
				val rest = k.removePrefix(PREFIX)
				val tag = TRACKED.firstOrNull { rest.startsWith("${it}_") } ?: continue
				val subtag = rest.removePrefix("${tag}_")

				val current = IsoBatchInfoRepo.getBatchInfo(mContext, tag, subtag)
					?.value?.trim()?.toIntOrNull() ?: 0
				if (current >= mark) continue

				// A wrap (999999 -> small) is legitimate; a recopy resets to the asset seed of 1.
				// Only treat it as a rewind when the counter has collapsed to near the seed.
				if (mark > 900000 && current < 1000) {
					HelperLog.appendLine(sbLog, "Wrap detected, not restoring", "$tag/$subtag $current (mark $mark)")
					prefs.edit().putInt(k, current).apply()
					continue
				}

				HelperLog.appendLine(sbLog, "COUNTER REWIND DETECTED",
					"$tag/$subtag db=$current mark=$mark -- restoring")
				IsoBatchInfoRepo.updateBatchInfo(mContext, String.format("%06d", mark), tag, subtag)
				repaired++
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
