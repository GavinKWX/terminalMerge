package tms

import android.content.Context
import enums.EnumDateFormat
import helpers.HelperDate
import enums.EnumLogFileName
import helpers.HelperLog
import helpers.TerminalInfo
import iso.CurrentStore
import java.util.UUID

/*
 * The install token: the one piece of information only the terminal has -- "the transactions this
 * app is uploading now come from a different installation than the older ones the server is still
 * holding open". Reinstalling wipes the local store and restarts the batch counter, and from the
 * server's side the second batch 000001 is indistinguishable from the first, so it settles
 * transactions the acquirer never posted. See obsidian
 * IR-Portal-Settled-After-App-Reinstall-Acquirer-Unposted and Terminal-InstallToken-Terminal-Integration.
 *
 * Stored in IsoBatchInfo on purpose -- the same table as batchNo/stan/invoiceNo. What the server
 * needs is "the token changes exactly when the local transaction store is lost", and keeping it in
 * the table that holds the batch counter makes that true by construction: anything that resets the
 * counter (fresh install, cleared data, DbHandler.copyPreloadedDatabaseIfNeeded replacing the file
 * from assets, schema self-heal reseeding the table) takes the token with it, and nothing else does.
 * SharedPreferences would survive an asset-DB recopy and report a reinstalled store as intact --
 * the incident, undetected.
 *
 * Moved to `:core` from both apps, where the copies were identical (audit item 86). The DB goes
 * through `CurrentStore`, so each app keeps its own `IsoBatchInfoRepo`.
 *
 * Never give this tag a seedData entry in DatabaseTables: a seeded value would be the same constant
 * on every terminal in the fleet, which is exactly what a token must not be.
 */
object InstallIdentity {
	private val className: String = InstallIdentity::class.java.name

	private const val TAG = "installAppVer"
	private const val SUBTAG = "pos"

	/*
	 * A row that is always present whenever IsoBatchInfo is readable -- it ships in the asset DB and
	 * is re-seeded by DbHandler.seedTableDefaults. Used to tell "there is no token row yet" apart
	 * from "the read failed": selectSingleData swallows its exceptions and returns null for both.
	 */
	private const val ANCHOR_TAG = "batchNo"
	private const val ANCHOR_SUBTAG = "visam"

	private const val REPORTED_TOKEN_PREF = "install_token_reported"

	/** The same file each app's Helper.getPrefs() opens: named after the package. */
	private fun prefs(context: Context) = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)

	/** Server-side limit on INSTALL-APP-VER. */
	private const val MAX_TOKEN_LENGTH = 128

	/**
	 * The token for this installation, generating and persisting one on first call after an install.
	 * Returns "" if it cannot be read or created -- the server reads a blank token as *unknown*,
	 * never as a mismatch, so a blank degrades to exactly today's settlement behaviour.
	 */
	@JvmStatic
	@Synchronized
	fun getToken(context: Context): String {
		return try {
			val existing = CurrentStore.batchInfoValue(context, TAG, SUBTAG)
			if (!existing.isNullOrEmpty()) return existing

			val sbLog = HelperLog.init("InstallIdentity")

			/*
			 * No token row -- but a failed read looks identical, and generating a token off the back
			 * of a transient DB error would tell the server this installation is new when it is not,
			 * parking good transactions onto the Operations worklist. Only generate once the anchor
			 * proves the table really is readable.
			 */
			val anchor = CurrentStore.batchInfoValue(context, ANCHOR_TAG, ANCHOR_SUBTAG)
			if (anchor.isNullOrEmpty()) {
				HelperLog.appendLine(
					sbLog, className,
					"No token row and no $ANCHOR_TAG/$ANCHOR_SUBTAG anchor -- IsoBatchInfo is not " +
						"readable, so this is NOT treated as a new installation. Sending a blank token."
				)
				HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
				return ""
			}

			val freshToken = newToken()
			CurrentStore.storeBatchInfo(context, TAG, SUBTAG, freshToken)
			HelperLog.appendLine(
				sbLog, className,
				"New installation :: install token generated [$freshToken] -- will be reported to TMS " +
					"on the next receipt upload run"
			)
			HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
			freshToken
		} catch (ex: Exception) {
			ex.printStackTrace()
			val sbLog = HelperLog.init("InstallIdentity")
			HelperLog.appendLine(sbLog, className, "Exception resolving install token :: " + ex.toString())
			HelperLog.logToFile(sbLog, EnumLogFileName.TerminaDbException)
			""
		}
	}

	/** True when the server has not yet been told about the token this app is currently running. */
	@JvmStatic
	fun needsReporting(context: Context, installToken: String): Boolean {
		if (installToken.isEmpty()) return false
		return prefs(context).getString(REPORTED_TOKEN_PREF, null) != installToken
	}

	/*
	 * Keyed on the token value rather than a boolean. The token lives in the database and this flag
	 * lives in preferences, so the token can change while the flag survives (the DB file is replaced
	 * but shared_prefs is not) -- a boolean would silently skip UpdateToken for the new token, which
	 * is the one call that links this installation to the previous one in Terminal_App_History.
	 */
	@JvmStatic
	fun markReported(context: Context, installToken: String) {
		prefs(context).edit().putString(REPORTED_TOKEN_PREF, installToken).apply()
	}

	/**
	 * Short form for the About screen -- the UUID's first group. The full token is 66 characters and
	 * cannot be read off a terminal screen; 8 hex characters identify a device unambiguously in a
	 * fleet this size, and the Portal worklist carries the full value with this as its prefix, so
	 * support can still match the two. "-" when there is no token to show.
	 */
	@JvmStatic
	fun getShortToken(context: Context): String {
		val installToken = getToken(context)
		return if (installToken.isEmpty()) "-" else installToken.substringBefore('-').take(8)
	}

	/*
	 * UUID + serial + install timestamp. The UUID alone already satisfies the spec, but pinning the
	 * device and the moment of generation onto it means a token is still self-describing when it
	 * turns up on the Portal worklist weeks later, and two terminals cannot collide even if a
	 * platform RNG is ever seeded badly. Shape: <uuid>-<serial>-<yyyyMMddHHmmss>.
	 */
	private fun newToken(): String {
		val serial = sanitize(TerminalInfo.serialNumber())
		val stamp = HelperDate.getDateString(EnumDateFormat.yyyyMMddHHmmss_XDot.dateFormat)
		val token = "${UUID.randomUUID()}-${if (serial.isEmpty()) "NOSN" else serial}-$stamp"
		// The UUID leads, so even a truncated token stays unique.
		return if (token.length > MAX_TOKEN_LENGTH) token.substring(0, MAX_TOKEN_LENGTH) else token
	}

	/*
	 * The server accepts 1-128 characters of A-Z a-z 0-9 . _ : - and rejects anything else outright.
	 * The serial is the only part of the token that comes from outside this class, so it is filtered
	 * rather than trusted. Explicit ASCII ranges, not isLetterOrDigit(), which would pass a Unicode
	 * letter the server would then reject.
	 */
	private fun sanitize(value: String): String =
		value.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._:-" }
}
