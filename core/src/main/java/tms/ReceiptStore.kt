package tms

import android.content.Context

/**
 * What [ReceiptReconciler] needs from an app's receipt tables. The repos stay per app; this is the
 * narrow slice the reconciler uses (audit item 86). Registered in each app's `java.MF919`.
 */
interface ReceiptStore {

	/** The approved sale's stored response TLV (`batchTable`), or null when there is no such row. */
	fun approvedBatchTlv(context: Context, stan: String, invNo: String, batchNo: String): StoredTlv?

	/** The approved pre-auth's stored TLV (`preauthTable`, keyed by invoice), or null when absent. */
	fun approvedPreAuthTlv(context: Context, invNo: String): StoredTlv?

	/** True when a reversal is queued for the transaction (`revBatchTable`), i.e. it timed out. */
	fun hasReversal(context: Context, stan: String, invNo: String, batchNo: String): Boolean

	fun updateReceipt(context: Context, values: HashMap<Any, Any>, criteria: HashMap<Any, Any>)

	/** This app's EPP_DETAIL JSON for a DE63 value (`IsoActivity.parseEppDetailsJson`). */
	fun eppDetailsJson(eppDe63: String?): String

	/** Kick an immediate receipt upload. */
	fun triggerUpload(context: Context)
}

/** A stored TLV row. [data] can be null: the row exists but carries no TLV. */
data class StoredTlv(val data: String?)

object CurrentReceiptStore : ReceiptStore {

	@Volatile
	private var backing: ReceiptStore? = null

	@JvmStatic
	fun register(store: ReceiptStore) {
		backing = store
	}

	@JvmStatic
	fun isRegistered(): Boolean = backing != null

	private val store: ReceiptStore
		get() = backing
			?: error("CurrentReceiptStore used before register() -- wire it in Application.onCreate")

	override fun approvedBatchTlv(context: Context, stan: String, invNo: String, batchNo: String) =
		store.approvedBatchTlv(context, stan, invNo, batchNo)

	override fun approvedPreAuthTlv(context: Context, invNo: String) = store.approvedPreAuthTlv(context, invNo)

	override fun hasReversal(context: Context, stan: String, invNo: String, batchNo: String) =
		store.hasReversal(context, stan, invNo, batchNo)

	override fun updateReceipt(context: Context, values: HashMap<Any, Any>, criteria: HashMap<Any, Any>) =
		store.updateReceipt(context, values, criteria)

	override fun eppDetailsJson(eppDe63: String?) = store.eppDetailsJson(eppDe63)

	override fun triggerUpload(context: Context) = store.triggerUpload(context)
}
