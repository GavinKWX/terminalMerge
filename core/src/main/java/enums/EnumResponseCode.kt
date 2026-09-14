package enums

/**
 * The SHCxxx response contract both apps put on the wire, in one place.
 *
 * These codes are vendor-visible: POS integrations branch on them over HTTP and over the
 * app-to-app intent. Today they exist only as ~60 scattered string literals across the two apps,
 * which is how SHC007 came to carry four different meanings (see [TERMINAL_SYSTEM_ERROR]). This
 * enum is the single source of truth; call sites migrate onto it incrementally rather than in one
 * sweep, because every one of them is a vendor-facing string.
 *
 * Descriptions are the exact text already shipping - **do not "tidy" them**. A vendor matching on
 * the description rather than the code (which happens) breaks on a whitespace change.
 */
enum class EnumResponseCode(val code: String, val description: String) {

	/** Another transaction owns the terminal. */
	SYSTEM_BUSY("SHC000", "System Busy"),

	/** Crash recovery is restarting the app; the caller should retry. */
	TERMINAL_RESTARTING("SHC000", "Terminal Error - restarting"),

	/** Request could not be parsed, or a required field was absent. */
	INVALID_INPUT("SHC001", "Invalid Input"),

	/** Request parsed but was not a recognised operation. */
	INVALID_REQUEST("SHC001", "Invalid Request"),

	INVALID_TRANSACTION_TYPE("SHC001", "Invalid Transaction Type"),
	INVALID_TRANSACTION_DETAILS("SHC001", "Invalid Transaction Details"),
	INVALID_TRANSACTION_INVOICE("SHC001", "Invalid Transaction Invoice"),

	/**
	 * Found shipping in 2026-09-09's sweep, absent from this enum until then -- so the class that
	 * calls itself the single source of truth was missing six of the strings actually on the wire.
	 * Added verbatim; do not tidy the wording.
	 */
	INVALID_PAYMENT_CHANNEL("SHC001", "Invalid Payment Channel"),
	INVALID_REF_ID("SHC001", "Invalid Ref ID"),
	INVALID_POS_REFERENCE_NO("SHC001", "Invalid PosReference No"),
	INVALID_TRANSACTION_ID("SHC001", "Invalid Transaction ID"),
	AMOUNT_NOT_POSITIVE("SHC001", "Trade amount should be greater than 0"),
	AMOUNT_TOO_LARGE("SHC001", "Trade amount too large"),

	/** Settlement already in progress; the caller must wait it out. */
	AUTO_SETTLEMENT_RUNNING("SHC002", "Auto Settlement is running"),

	/** MF919 only - Pro has never emitted these two. */
	NO_BATCH_TO_SETTLE("SHC003", "No Batch to Settle"),
	SETTLEMENT_FAILED("SHC004", "Settlement Fail"),

	/** Operator abandoned the payment screen, or the VMC aborted the vend. */
	USER_CANCELLED("SHC005", "User Cancel the Transaction"),

	/**
	 * The decided meaning of SHC007 (see docs/merge-audit-mf919.md, item A3).
	 *
	 * Product-not-configured is reported as this code with the cause in parentheses, rather than
	 * as its own description - that was the resolution of MF919 saying "Terminal System Error"
	 * while Pro said "Product Is Not Configured" for the same code.
	 */
	TERMINAL_SYSTEM_ERROR("SHC007", "Terminal System Error"),
	PRODUCT_NOT_CONFIGURED("SHC007", "Terminal System Error (Product Is Not Configured)"),
	UNEXPECTED_ERROR("SHC007", "Unexpected Error"),

	/**
	 * ⚠️ UNRESOLVED COLLISION - it contradicts the A3 decision above.
	 *
	 * A timeout is not a system error, but it shares SHC007, so a vendor cannot tell "the
	 * terminal broke" from "the terminal did not answer in time" - and those want opposite
	 * client behaviour (investigate vs retry). Giving it its own code is a vendor-visible
	 * contract change, so it needs a decision rather than a quiet edit; recorded here so the
	 * next reader sees the conflict instead of rediscovering it.
	 *
	 * For integrators the rule is: match on the description for SHC007, and treat this one as
	 * query-before-retry -- a timeout may mean the transaction completed and charged the card.
	 *
	 * Emitted by **both** apps since 2026-09-08. MF919 previously answered a timeout SHC000
	 * "System Busy", i.e. "safe to retry immediately", which is the one answer that invites a
	 * double charge; see A3b in docs/merge-audit-mf919.md.
	 */
	TERMINAL_RESPONSE_TIMEOUT("SHC007", "Terminal Response Timeout"),

	TRANSACTION_NOT_FOUND("SHC008", "Transaction Not Found"),
	QR_TRANSACTION_NOT_FOUND("SHC008", "QR Transaction Not Found"),

	/** An external caller ended the session while a payment screen was up. */
	PAYMENT_SESSION_TERMINATED("SHC009", "Payment Session Terminated"),

	TRANSACTION_NOT_SUPPORTED("SHC010", "Transaction Not Supported"),
	MYDEBIT_PREAUTH_VOID_UNSUPPORTED("SHC010", "MyDebit Preauth Void is Not Supported"),
	MYDEBIT_COMPLETION_VOID_UNSUPPORTED("SHC010", "MyDebit Completion Void is Not Supported"),

	SETTLE_PREVIOUS_DAY_FIRST(
		"SHC011",
		"Please Run Settlement for Last day Transaction before Proceed"
	);

	companion object {

		/**
		 * The parameterised SHC001, e.g. `invalidParameter("TransactionAmount")` ->
		 * `"Invalid Parameter - (TransactionAmount)"`. Both apps build this string by hand in a
		 * dozen places; the spacing and brackets are part of the contract.
		 */
		@JvmStatic
		fun invalidParameter(field: String): Pair<String, String> =
			"SHC001" to "Invalid Parameter - ($field)"

		/** Every distinct code in the contract, for tests and documentation. */
		@JvmStatic
		fun codes(): Set<String> = values().map { it.code }.toSet()
	}
}
