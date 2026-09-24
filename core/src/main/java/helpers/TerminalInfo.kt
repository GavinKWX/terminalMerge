package helpers

/**
 * The seam that lets shared code ask "which terminal am I on?" without :core depending on an app.
 *
 * The TMS handlers needed exactly three things from `ServiceHolder` -- the serial number, the app
 * version and the sequence number -- plus `BuildConfig.DEFAULT_ENV` for [env.EnvironmentManager].
 * Moving `ServiceHolder` itself was never an option: it reaches for R, the Application class,
 * Global, IsoComm, Utils, DeviceHelper and six database types. So the handlers depend on this
 * four-method interface instead, and each app registers an implementation that delegates to its
 * own ServiceHolder and BuildConfig.
 *
 * Registration happens in each app's Application class (`java.MF919`), which runs before any
 * activity, service or TMS call, so a handler can never observe an unregistered provider in
 * normal operation. [isRegistered] exists so a caller can assert that rather than assume it.
 */
object TerminalInfo {

	/** Implemented per app, backed by that app's ServiceHolder and BuildConfig. */
	interface Provider {
		/** Terminal serial, sent as the `DEV-SN` header on every TMS call. */
		fun serialNumber(): String

		/** App version, sent as `APP-VER`. */
		fun appVersion(): String

		/** Rolling sequence number used by the TMS request envelope. */
		fun sqnNum(): String

		/** Device model, e.g. "MF919". Reported in the MDB PERIPHERAL ID response. */
		fun deviceModel(): String

		/** `BuildConfig.DEFAULT_ENV` -- "development", "staging" or "production". */
		fun defaultEnvId(): String

		/** Install token, sent as `INSTALL-APP-VER`. "" means unknown, never a mismatch. */
		fun installToken(): String = ""

		/** Local IP, sent as `TERMINAL_IP` on UpdateToken. */
		fun ipAddress(): String = ""
	}

	/** `APP-TYPE` sent with the install token. The same for both apps (audit item 72). */
	const val APP_TYPE = "POS"

	@Volatile
	private var provider: Provider? = null

	@JvmStatic
	fun register(p: Provider) {
		provider = p
	}

	@JvmStatic
	fun isRegistered(): Boolean = provider != null

	/**
	 * Returns "" when no provider is registered rather than throwing.
	 *
	 * A missing DEV-SN header makes the TMS call fail with a diagnosable server-side error; an
	 * exception thrown from deep inside a handler on a background thread would surface as an
	 * unrelated crash. The empty string is the more debuggable failure of the two, and
	 * [isRegistered] is there for callers that want to check first.
	 */
	@JvmStatic
	fun serialNumber(): String = provider?.serialNumber() ?: ""

	@JvmStatic
	fun appVersion(): String = provider?.appVersion() ?: ""

	@JvmStatic
	fun sqnNum(): String = provider?.sqnNum() ?: ""

	@JvmStatic
	fun deviceModel(): String = provider?.deviceModel() ?: ""

	@JvmStatic
	fun defaultEnvId(): String = provider?.defaultEnvId() ?: "production"

	@JvmStatic
	fun installToken(): String = provider?.installToken() ?: ""

	@JvmStatic
	fun ipAddress(): String = provider?.ipAddress() ?: ""
}
