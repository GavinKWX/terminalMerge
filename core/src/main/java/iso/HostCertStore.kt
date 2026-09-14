package iso

import java.io.InputStream

/**
 * The two things [IsoComm] needs from the app to build a TLS socket to the acquirer.
 *
 * It exists because both are app-owned and neither belongs in `:core`. The certificate is a raw
 * **resource**, and `:core` has no `res/` and deliberately does not gain one (see
 * `docs/merge-audit-mf919.md` item 18). The keystore directory is resolved by each app's own
 * `ServiceHolder`, which is per-app by ruling.
 *
 * Only the SSL branch of `IsoComm` uses this. A plain TCP host connection never touches it, which
 * is why an app that never enables SSL will not notice if it forgets to register — see the note
 * on [CurrentCertStore].
 */
interface HostCertStore {

	/**
	 * Opens the bundled certificate the caller identified by [certId].
	 *
	 * The id travels from the call site as a plain `Int`, so `:core` never names an `R.` symbol.
	 */
	fun openCertificate(certId: Int): InputStream

	/**
	 * Directory `terminal_keystore.bks` is written to and read back from.
	 *
	 * Nullable because the app's own accessor is; `IsoComm` passes it straight to `File(...)`,
	 * which treats null as "no parent", exactly as it did before this seam existed.
	 */
	fun keystoreDir(): String?
}

/**
 * Process-wide holder, registered once per app in `Application.onCreate`.
 *
 * This one **throws** when unregistered rather than failing soft. The alternative is a TLS
 * handshake that fails somewhere deeper with a confusing socket error, and an unregistered seam
 * is a wiring mistake that should be loud the first time SSL is used, not diagnosed from a
 * connection failure in the field.
 */
object CurrentCertStore : HostCertStore {

	@Volatile
	private var backing: HostCertStore? = null

	@JvmStatic
	fun register(store: HostCertStore) {
		backing = store
	}

	@JvmStatic
	fun isRegistered(): Boolean = backing != null

	private val store: HostCertStore
		get() = backing
			?: error("CurrentCertStore used before register() -- wire it in Application.onCreate")

	override fun openCertificate(certId: Int): InputStream = store.openCertificate(certId)

	override fun keystoreDir(): String? = store.keystoreDir()
}
