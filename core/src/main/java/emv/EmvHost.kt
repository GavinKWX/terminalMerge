package emv

import com.morefun.yapi.emv.EmvHandler

/**
 * What [EmvUtil] needs from the app that `:core` cannot reach on its own.
 *
 * Both are app-owned for different reasons. The EMV handler comes from each app's `DeviceHelper`,
 * which owns the bound device-service lifecycle — `:core` can name the `EmvHandler` type (the
 * MoreFun SDK is a `:core` dependency) but has no business binding the service. The `OptIn` flag
 * is read from `DbModelTerminalConfig`, and models stay per app by ruling.
 */
interface EmvHost {

	/**
	 * The bound device service's EMV handler.
	 *
	 * Declared `@Throws(RemoteException)` in effect — `DeviceHelper.getEmvHandler()` throws, and
	 * the callers in [EmvUtil] already sit inside try/catch for exactly that.
	 */
	fun emvHandler(): EmvHandler

	/** The terminal config's `OptIn` flag. */
	fun isOptIn(): Boolean
}

/**
 * Process-wide holder, registered once per app in `Application.onCreate`.
 *
 * Throws when unregistered rather than failing soft: every chip and contactless read goes through
 * here, so an unwired seam is not something to discover from a card that mysteriously will not
 * read. [isOptIn] is the exception — it answers `false`, because a missing config flag has a
 * sensible default and should not stop a transaction.
 */
object CurrentEmvHost : EmvHost {

	@Volatile
	private var backing: EmvHost? = null

	@JvmStatic
	fun register(host: EmvHost) {
		backing = host
	}

	@JvmStatic
	fun isRegistered(): Boolean = backing != null

	override fun emvHandler(): EmvHandler =
		(backing ?: error("CurrentEmvHost used before register() -- wire it in Application.onCreate"))
			.emvHandler()

	override fun isOptIn(): Boolean = try {
		backing?.isOptIn() ?: false
	} catch (t: Throwable) {
		false
	}
}
