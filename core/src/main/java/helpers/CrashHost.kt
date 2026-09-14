package helpers

import android.content.Context

/**
 * Everything [CrashHandler] needs from the app it is running inside.
 *
 * The handler is ~400 lines of crash bookkeeping -- capture the throwable, write a crash record,
 * tell whoever asked for the transaction that it is not coming, then restart or reboot -- and only
 * about a dozen lines that touch the app. Those are gathered here, the same way [mdb.MdbHost]
 * gathers MDB's.
 *
 * **Every method here runs inside a crash.** The handler is already dealing with one failure and
 * must not be derailed by a second, so each implementation should be defensive and cheap: no disk
 * I/O, no network, no locks that a dying thread might be holding. The handler wraps these calls in
 * its own try/catch as well, but an implementation that blocks is one the handler cannot recover
 * from -- there is nothing left to catch it.
 *
 * Registered once per process in each app's Application class, next to [TerminalInfo] and
 * [mdb.MdbController].
 */
interface CrashHost {

	// ---- crash-report context ------------------------------------------------------------

	/**
	 * The terminal's IP, or a placeholder when it cannot be read.
	 *
	 * Implementations must not throw. This is the call that motivated the handler's current
	 * ordering: `Utils.getIPAddress()` threw on Android 10+, where `getConnectionInfo()` is
	 * nullable, and the exception escaped the handler so `killProcess()` was never reached --
	 * leaving the main thread dead and the process alive, screen frozen on whatever was showing.
	 */
	fun ipAddress(): String

	/** Whether the terminal is on Wi-Fi, for the crash log header. Must not throw. */
	fun isWifi(context: Context): Boolean

	/** Build identity for the crash record, e.g. `"sharecomm/debug"`. */
	fun buildLabel(): String

	/**
	 * Which channel asked for the transaction in flight: `"http"`, `"app2app"`, or `"none"`.
	 * Recorded so a crash can be matched to the caller that was waiting on it.
	 */
	fun callerChannel(): String

	// ---- telling the caller ---------------------------------------------------------------

	/**
	 * Answer a waiting ECR/POS caller so it is not left on a socket until timeout.
	 *
	 * A no-op when nothing is waiting -- the handler does not know which transports the app
	 * supports, so the decision belongs here. `payload` is the already-built JSON body.
	 */
	fun notifyCallerBusy(payload: String)

	// ---- recovery -------------------------------------------------------------------------

	/**
	 * Start the app's own crash-recovery screen, which lives in a separate process and relaunches
	 * the app after [delayMs].
	 *
	 * The Activity stays per app -- it carries a layout and a manifest entry -- so the handler
	 * hands over the four values it computed and lets the app build the Intent. [mainPid] is this
	 * process's pid, which recovery waits on before relaunching.
	 */
	fun launchRecovery(context: Context, crashId: String, attempt: Int, delayMs: Long, mainPid: Int)

	/**
	 * Ask the ROM to reboot the terminal.
	 *
	 * @return true if the request was accepted, in which case the handler stops -- there is
	 *   nothing left to do. False means fall through to a normal app restart, which is also the
	 *   right answer when the binder is gone or the ROM refuses.
	 */
	fun requestReboot(): Boolean
}

/**
 * The registered [CrashHost] for this process.
 *
 * Unlike [iso.CurrentTxn] this **does not throw** when unregistered, and deliberately so: it is
 * read from inside a crash handler, and a handler that throws on a missing registration turns a
 * recoverable crash into a frozen terminal. Every accessor degrades to something harmless instead,
 * so a crash during startup -- before registration -- still gets written down and still restarts.
 */
object CurrentCrashHost : CrashHost {

	private var backing: CrashHost? = null

	/** Called once per process from each app's Application class. */
	@JvmStatic
	fun register(host: CrashHost) {
		backing = host
	}

	@JvmStatic
	fun isRegistered(): Boolean = backing != null

	override fun ipAddress(): String = safe("0.0.0.0") { it.ipAddress() }

	override fun isWifi(context: Context): Boolean = safe(false) { it.isWifi(context) }

	override fun buildLabel(): String = safe("unknown") { it.buildLabel() }

	override fun callerChannel(): String = safe("none") { it.callerChannel() }

	override fun notifyCallerBusy(payload: String) {
		safe(Unit) { it.notifyCallerBusy(payload); Unit }
	}

	override fun launchRecovery(
		context: Context,
		crashId: String,
		attempt: Int,
		delayMs: Long,
		mainPid: Int,
	) {
		safe(Unit) { it.launchRecovery(context, crashId, attempt, delayMs, mainPid); Unit }
	}

	override fun requestReboot(): Boolean = safe(false) { it.requestReboot() }

	/**
	 * Run [block] against the registered host, falling back to [fallback] if there is none or if
	 * the implementation throws. The handler is mid-crash; a second failure here must not be the
	 * thing that stops it finishing.
	 */
	private inline fun <T> safe(fallback: T, block: (CrashHost) -> T): T = try {
		backing?.let(block) ?: fallback
	} catch (t: Throwable) {
		fallback
	}
}
