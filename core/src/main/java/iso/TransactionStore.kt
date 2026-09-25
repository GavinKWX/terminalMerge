package iso

import android.content.Context

/**
 * One acquirer product row, reduced to the six fields the ISO forming code actually reads.
 *
 * The apps' own row models (`DbModelProductListGet`) carry far more and differ between fleets --
 * MF919 has denomination/vending columns Pro does not. Repos and models stay per app by the
 * decision in section 12 of the merge audit, so the seam exposes a value type of its own rather
 * than either app's model.
 */
data class AcquirerProduct(
	val acqCode: String,
	val product: String,
	val acqMid: String,
	val acqTid: String,
	val ksn: String,
	val pinKsn: String,
)

/**
 * The persisted state the ISO forming code reads and writes: transaction counters, the acquirer
 * product row, and the injected keys.
 *
 * Companion to [TransactionData], which covers the in-flight state. Between them they are
 * everything the forming code needs from the app, which is what lets ~5,830 lines of ISO message
 * construction live once in `:core` while each fleet keeps its own database layer.
 *
 * Backed by three per-app repositories -- `IsoBatchInfoRepo`, `ProductListRepo`, `SecureDataRepo` --
 * and only six of their methods. `Context` stays in the signatures because that is how the forming
 * code passes it today; the alternative (each app sourcing it from its own `ServiceHolder`) would
 * hide the dependency for no gain.
 */
interface TransactionStore {

	// ---- counters ------------------------------------------------------------------------

	/**
	 * Read-modify-write a counter (`stan`, `invoiceNo`) and return the new value, zero-padded.
	 *
	 * Must be atomic against concurrent callers -- this is audit item R8. Two flows handed the
	 * same STAN produce two transactions the host cannot tell apart.
	 */
	fun allocateCounter(context: Context, tag: String, subtag: String): String

	/** A batch-info value (`isoTpduHeaderTle`, `niiTle`), or null when the row is absent. */
	fun batchInfoValue(context: Context, tag: String, subtag: String): String?

	/** Insert a batch-info row. Used for the install token (`installAppVer`/`pos`, item 86). */
	fun storeBatchInfo(context: Context, tag: String, subtag: String, value: String)

	// ---- acquirer product ----------------------------------------------------------------

	/** The product row for this MID/TID pair, or null when there is none. */
	fun acquirerProduct(context: Context, mid: String, tid: String): AcquirerProduct?

	/**
	 * Update product columns. Both maps are column name to value, matching the repository call
	 * this replaces -- the forming code already passes column names as string literals, so a
	 * typed API here would invent structure rather than capture it.
	 *
	 * @return true when a row was updated.
	 */
	fun updateProduct(
		context: Context,
		values: Map<String, String>,
		criteria: Map<String, String>,
	): Boolean

	// ---- injected keys -------------------------------------------------------------------

	/**
	 * A decrypted secure value (`eTmkId`, `eWakKey`, `eWekKey`, `eTakKey`, `eTpkKey`, ...), or
	 * null when absent. Callers treat null and empty the same: decline rather than build a
	 * message with an empty key.
	 */
	fun secureValue(context: Context, tag: String, subtag: String): String?

	/** Store a secure value, encrypting on the way in. */
	fun storeSecureValue(context: Context, tag: String, subtag: String, data: ByteArray, dataLen: Int)
}

/**
 * The registered [TransactionStore] for this process. Same contract as [CurrentTxn]: registered in
 * each app's Application class, and throws rather than degrading if read before that happens.
 */
object CurrentStore : TransactionStore {

	private var backing: TransactionStore? = null

	/** Called once per process from each app's Application class. */
	@JvmStatic
	fun register(store: TransactionStore) {
		backing = store
	}

	@JvmStatic
	fun isRegistered(): Boolean = backing != null

	private val store: TransactionStore
		get() = backing ?: error("CurrentStore used before register() -- wire it in Application.onCreate")

	override fun allocateCounter(context: Context, tag: String, subtag: String): String =
		store.allocateCounter(context, tag, subtag)

	override fun batchInfoValue(context: Context, tag: String, subtag: String): String? =
		store.batchInfoValue(context, tag, subtag)

	override fun storeBatchInfo(context: Context, tag: String, subtag: String, value: String) =
		store.storeBatchInfo(context, tag, subtag, value)

	override fun acquirerProduct(context: Context, mid: String, tid: String): AcquirerProduct? =
		store.acquirerProduct(context, mid, tid)

	override fun updateProduct(
		context: Context,
		values: Map<String, String>,
		criteria: Map<String, String>,
	): Boolean = store.updateProduct(context, values, criteria)

	override fun secureValue(context: Context, tag: String, subtag: String): String? =
		store.secureValue(context, tag, subtag)

	override fun storeSecureValue(
		context: Context,
		tag: String,
		subtag: String,
		data: ByteArray,
		dataLen: Int,
	) = store.storeSecureValue(context, tag, subtag, data, dataLen)
}
