package database

/**
 * What [DbHandler] needs to know about a table, without knowing whose schema it is.
 *
 * The two fleets' schemas have genuinely diverged -- MF919 carries 21 tables including
 * QR_PAY_TABLE and PRINT_RECEIPT_QR for its BNPL/QR features, Pro carries 19 with extra
 * columns of its own, and they sit on separate migration histories (28 vs 3) against live
 * data. Converging them is a data-migration project, not a refactor, so each app keeps its
 * own `DatabaseTables` enum and simply implements this interface.
 *
 * The enums already had exactly this shape, so implementing it changed no call site.
 */
interface DbTable {

	/** Table name as it appears in SQLite. */
	val id: String

	/** Canonical column definitions, used to CREATE the table when self-healing. */
	val createSql: List<String>

	/** Default rows re-inserted after a heal, as (tag, subtag, value). */
	val seedData: List<Triple<String, String, String>>

	/**
	 * True for tables whose `value` column holds key material.
	 *
	 * DbHandler logs the ContentValues of every insert, and those logs are uploaded to TMS,
	 * so secureData's values are redacted to a length before they are written. Defaults to
	 * false; only the secure-data table overrides it.
	 */
	val holdsSecrets: Boolean get() = false
}
