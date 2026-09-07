package mdb

/**
 * Everything [MdbController] needs from the app it is running inside.
 *
 * The controller is ~634 lines of MDB protocol -- frame decoding, the VMC state machine, session
 * and vend handling -- and only about 66 lines that touch the app. Those are gathered here so the
 * protocol can live once in `:core` while each fleet keeps its own screens, database models and
 * navigation:
 *
 *  - **MF919** drives Activities: it starts `AttendDenominationActivity` /
 *    `DenominationPaymentOptionActivity` with Intents and checks `HTTPServer.attendActivityContext`.
 *  - **MF919 Pro** drives a Nav graph: it emits `UiEvent.FragmentNavigation` on `AppBus` and asks
 *    the NavController which destination is current.
 *
 * Registered once per process in each app's Application class, next to [helpers.TerminalInfo] and
 * [database.DbSchema].
 */
interface MdbHost {

	// ---- configuration -------------------------------------------------------------------

	/**
	 * The terminal's `CABLE_CONNECTION` setting, compared against [data_enum.ConnMethodEnum].
	 * MDB only starts when this reads `MDB`. Null or unset means MDB stays off.
	 */
	fun cableConnectionMethod(): String?

	/**
	 * The device SDK's MDB service, or null when it cannot be obtained.
	 *
	 * Each app reaches it through its own `DeviceHelper`, which is per-app because it wraps far
	 * more than MDB. The service type itself comes from the YSDK jar that :core already exposes,
	 * so only the lookup needs to be asked of the host.
	 */
	fun mdbService(): com.morefun.yapi.device.mdb.IMdbService?

	// ---- transport state -----------------------------------------------------------------

	/**
	 * Called when the MDB link comes up or goes down, so the app can mark its request transport.
	 * Both apps set `HTTPServer.socketInterface` -- 3 while MDB owns the link, -1 otherwise.
	 */
	fun onTransportActive(active: Boolean)

	// ---- navigation and screen readiness -------------------------------------------------

	/** Bring the terminal back to its home/attend screen after a vend completes or fails. */
	fun navigateHome()

	/**
	 * True when the denomination attend screen is up and not going away.
	 *
	 * The controller waits on this before driving a VMC-initiated session, because a session
	 * started while the screen is absent has nowhere to display the price.
	 */
	fun isVendingScreenReady(): Boolean

	/** Dismiss the attend screen, where the app has one to dismiss. */
	fun finishVendingScreen()

	/**
	 * Auto-session: the VMC set a price, so open the payment-option screen for it.
	 *
	 * @param decimalPrice the price in minor units, e.g. "150" for RM 1.50.
	 * Each app builds its own denomination model from this and navigates its own way.
	 */
	fun navigateToPaymentOption(decimalPrice: String)

	// ---- the transaction in flight -------------------------------------------------------

	/** Invoice number of the transaction that funded the current vend, for the audit trail. */
	fun currentInvoiceNo(): String

	/**
	 * QR reference of the current transaction when it was an approved e-wallet payment,
	 * otherwise empty. Used to void the right payment when the VMC reports a vend failure.
	 */
	fun currentQrRef(): String

	// ---- UI events -----------------------------------------------------------------------

	/**
	 * The VMC enabled or disabled the card reader. Each app publishes this on its own `AppBus`
	 * as `UiEvent.MdbStateChange`, which its attend screen listens for.
	 */
	fun onReaderStateChanged(enabled: Boolean)

	/** The VMC set a vend price, as raw hex. Published as `UiEvent.MdbVendingPrice`. */
	fun onVendingPrice(priceHex: String)

	/**
	 * The VMC aborted the vend in flight -- reset, vend cancel or reader disable -- so any
	 * payment UI must come down immediately. Published as `UiEvent.MdbVendingForceEnd`.
	 */
	fun onVendingForceEnd()

	// ---- logging -------------------------------------------------------------------------

	/** Debug print, routed to whatever the app uses (`Utils.printLog` in both today). */
	fun printLog(message: String)

	/** Session id, connectivity and IP for the MDB log header. */
	fun logHeader(): MdbLogHeader
}

/** The per-app context stamped on the MDB log file's header. */
data class MdbLogHeader(
	val session: String,
	val isWifi: Boolean,
	val ipAddress: String,
)
