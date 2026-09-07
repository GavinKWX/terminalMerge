package helpers

/**
 * The single rule for deciding whether a request is old-integration or new-integration.
 *
 * Pro previously decided this in two places that disagreed:
 *
 *  - `HTTPServer.checkTransactionType` used `requestJson.has("IsOldIntegration")` -- presence of
 *    the key alone, value ignored, so `"IsOldIntegration": false` selected **old**;
 *  - `TransactionParser` used `toBooleanStrictOrNull() ?: false`, so `"1"`, `"TRUE"` and `"yes"`
 *    all fell through to **new**.
 *
 * The same request could therefore route differently over HTTP than over App-to-App.
 *
 * **Decided 2026-09-04: the flag is read by value, and only a true value selects old integration.**
 * Presence alone does not -- `"IsOldIntegration": false` is a caller stating they are on new
 * integration, and is now honoured as such on both surfaces.
 *
 * MF919 has no new-integration path at all, so this only governs Pro.
 */
object IntegrationMode {

	/**
	 * True only for boolean `true` or the string `"true"`.
	 *
	 * Both forms are accepted because the two surfaces carry the flag differently: over HTTP it is
	 * a JSON primitive that may be a boolean or a quoted string, while intent extras arrive as a
	 * `HashMap<String, String>` so it can only ever be a string there. Callers pass the string
	 * form and Gson's `asString` renders a JSON boolean as `"true"`/`"false"`, so one check covers
	 * both.
	 *
	 * Matching is case-insensitive and trims surrounding whitespace, so `"TRUE"` and `" true "`
	 * count. Nothing else does -- `"1"`, `"yes"` and `"Y"` are **not** true, and neither is a
	 * present-but-empty value.
	 */
	@JvmStatic
	fun flagIsTrue(raw: String?): Boolean =
		raw != null && raw.trim().equals("true", ignoreCase = true)

	/**
	 * @param flagIsTrue `IsOldIntegration` was sent and its value is true -- see [flagIsTrue].
	 * @param amountInOldFormat the amount arrived in the old integration's decimal-ringgit form
	 *        rather than integer cents. This remains a fallback signal because a caller on old
	 *        integration need not send the flag at all -- MF919 has no such field, so its vendors
	 *        never do -- and without it `"10.00"` would be read as 10 cents. Each surface detects
	 *        it differently: over HTTP the `TransactionAmount` JSON primitive is a *string* rather
	 *        than a number; over intents every extra is already a String, so the equivalent signal
	 *        is an amount that is not digits-only.
	 */
	@JvmStatic
	fun isOld(flagIsTrue: Boolean, amountInOldFormat: Boolean): Boolean =
		flagIsTrue || amountInOldFormat
}
