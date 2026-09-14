package iso

/**
 * The slice of a transaction's in-flight state that the ISO forming code needs.
 *
 * Each app owns a `TransData` object of ~120 properties, and those two objects legitimately differ:
 * MF919 carries denomination/vending fields, Pro carries the newer HTTP contract fields
 * (`additionalInfo`, `correlationRef`, `reqAuthId`). Models stay per app by design, so `TransData`
 * itself does not move to `:core`.
 *
 * What *can* move is the ISO forming code, and it turns out to touch only these **25** members --
 * all of which already exist in both apps with matching declarations. Gathering them here lets the
 * forming code live once without either app giving up its own fields.
 *
 * Same shape as [mdb.MdbHost] and [helpers.TerminalInfo]: an interface here, implemented by each
 * app's `TransData`, registered once per process in that app's Application class.
 *
 * Two declarations were widened to get here, both nullability only:
 * [addHexStrIntoTransDB] and [addHexStrWithPadIntoTransDB] take `String?` because MF919's versions
 * did. MF919 skipped the write when the value was null; Pro's took a non-null `String` and now
 * carries the same guard. No caller behaviour changes -- nobody was passing null to Pro.
 */
interface TransactionData {

	// ---- acquirer / terminal identity ----------------------------------------------------

	var acqCode: String
	var mid: String
	var tid: String
	var batchNo: String
	var product: String
	var schemeTag: String

	/**
	 * The card brand as the AID resolved it ("VISA", "MASTER", ...), distinct from
	 * [schemeTag] which is the acquirer's routing tag. EmvUtil needs it to pick the chip
	 * tag list for the acquirer.
	 */
	var schemeType: String

	/**
	 * The transaction counters. Not used by the ISO forming code -- these are here for
	 * [helpers.CrashHandler], which records them so a crash can be matched to the transaction that
	 * was in flight. Present and identically declared in both apps.
	 */
	var stan: String
	var invoiceNo: String

	// ---- keys ----------------------------------------------------------------------------

	var ksn: String
	var pinKsn: String
	var dukpt: ByteArray

	// ---- amounts and EPP -----------------------------------------------------------------

	var cashOutAmount: Long
	val cashOutAmountAuth: ByteArray
	var eppTenure: String
	var eppTenureCode: String

	// ---- outcome -------------------------------------------------------------------------

	var respCode: String
	var transResult: Int
	var txnTypeLabel: String

	// ---- cardholder verification ---------------------------------------------------------

	var offlinePinInput: Boolean
	var onlinePinInput: Boolean

	// ---- the TLV scratch buffer the ISO message is assembled in ---------------------------

	/**
	 * 4096-byte TLV buffer. Deliberately `val`: the array identity is fixed and callers mutate
	 * it in place, which is exactly what the forming code does via [addTlvIntoTransDB].
	 */
	val transactionDb: ByteArray
	var transactionDbLen: Int

	fun addHexStrIntoTransDB(tag: String, strData: String?)
	fun addHexStrWithPadIntoTransDB(tag: String, strData: String?, padChar: String)
	fun addTlvIntoTransDB(tag: String, tlvData: ByteArray, dataOffSet: Int, tlvLen: Int)

	/** @param formatType 16 = hex string, 256 = ascii. */
	fun getFromTransactionDb(tag: String, formatType: Int): String
	fun removeTlvFromTransDb(tag: String)
}

/**
 * The registered [TransactionData] for this process, so `:core` code can reach it statically the
 * way it reaches [helpers.TerminalInfo].
 *
 * Reads before registration throw rather than returning a dummy. A dummy would let a transaction
 * form an ISO message against an empty buffer and put it on the wire, which is worse than a crash
 * at startup -- and registration happens in `Application.onCreate`, before any screen or service
 * can run, so a throw here means the app was wired wrong, not that a race was lost.
 */
object CurrentTxn : TransactionData {

	private var backing: TransactionData? = null

	/** Called once per process from each app's Application class. */
	@JvmStatic
	fun register(data: TransactionData) {
		backing = data
	}

	@JvmStatic
	fun isRegistered(): Boolean = backing != null

	private val txn: TransactionData
		get() = backing ?: error("CurrentTxn used before register() -- wire it in Application.onCreate")

	override var acqCode: String
		get() = txn.acqCode
		set(v) { txn.acqCode = v }
	override var mid: String
		get() = txn.mid
		set(v) { txn.mid = v }
	override var tid: String
		get() = txn.tid
		set(v) { txn.tid = v }
	override var batchNo: String
		get() = txn.batchNo
		set(v) { txn.batchNo = v }
	override var stan: String
		get() = txn.stan
		set(v) { txn.stan = v }
	override var invoiceNo: String
		get() = txn.invoiceNo
		set(v) { txn.invoiceNo = v }
	override var product: String
		get() = txn.product
		set(v) { txn.product = v }
	override var schemeTag: String
		get() = txn.schemeTag
		set(v) { txn.schemeTag = v }
	override var schemeType: String
		get() = txn.schemeType
		set(v) { txn.schemeType = v }

	override var ksn: String
		get() = txn.ksn
		set(v) { txn.ksn = v }
	override var pinKsn: String
		get() = txn.pinKsn
		set(v) { txn.pinKsn = v }
	override var dukpt: ByteArray
		get() = txn.dukpt
		set(v) { txn.dukpt = v }

	override var cashOutAmount: Long
		get() = txn.cashOutAmount
		set(v) { txn.cashOutAmount = v }
	override val cashOutAmountAuth: ByteArray get() = txn.cashOutAmountAuth
	override var eppTenure: String
		get() = txn.eppTenure
		set(v) { txn.eppTenure = v }
	override var eppTenureCode: String
		get() = txn.eppTenureCode
		set(v) { txn.eppTenureCode = v }

	override var respCode: String
		get() = txn.respCode
		set(v) { txn.respCode = v }
	override var transResult: Int
		get() = txn.transResult
		set(v) { txn.transResult = v }
	override var txnTypeLabel: String
		get() = txn.txnTypeLabel
		set(v) { txn.txnTypeLabel = v }

	override var offlinePinInput: Boolean
		get() = txn.offlinePinInput
		set(v) { txn.offlinePinInput = v }
	override var onlinePinInput: Boolean
		get() = txn.onlinePinInput
		set(v) { txn.onlinePinInput = v }

	override val transactionDb: ByteArray get() = txn.transactionDb
	override var transactionDbLen: Int
		get() = txn.transactionDbLen
		set(v) { txn.transactionDbLen = v }

	override fun addHexStrIntoTransDB(tag: String, strData: String?) =
		txn.addHexStrIntoTransDB(tag, strData)

	override fun addHexStrWithPadIntoTransDB(tag: String, strData: String?, padChar: String) =
		txn.addHexStrWithPadIntoTransDB(tag, strData, padChar)

	override fun addTlvIntoTransDB(tag: String, tlvData: ByteArray, dataOffSet: Int, tlvLen: Int) =
		txn.addTlvIntoTransDB(tag, tlvData, dataOffSet, tlvLen)

	override fun getFromTransactionDb(tag: String, formatType: Int): String =
		txn.getFromTransactionDb(tag, formatType)

	override fun removeTlvFromTransDb(tag: String) = txn.removeTlvFromTransDb(tag)
}
