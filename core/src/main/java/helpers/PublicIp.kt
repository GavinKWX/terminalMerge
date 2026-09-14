package helpers

import utils.Util
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

/**
 * Asks an external service for this terminal's public IP, for the `TERMINAL_IP` header on TMS
 * requests. Moved out of both apps' Utils in tranche 4 of the Utils slice.
 *
 * **The hazard came with it.** [get] starts a thread, then *busy-waits on the calling thread* in
 * 100 ms steps until that thread sets a flag. There is no timeout on the HTTP call, so a host that
 * accepts the connection and never answers parks the caller indefinitely. Both live callers are on
 * a TMS upload thread rather than the main thread, so it shows up as a stalled upload, not an ANR.
 *
 * That is left as it was on purpose: a real fix means a timeout and a cached value, which changes
 * what the TMS header contains when the lookup fails, and that is a contract change to make
 * deliberately. See `docs/merge-audit-mf919.md` item 50.
 *
 * Two things did change in the move, neither observable to a caller: the flag is cleared in a
 * `finally` rather than in both the success and failure paths, and the reader is closed, which the
 * original never did.
 */
object PublicIp {

	/** Returns the public IP, or `"0.0.0.0"` if the lookup failed. */
	@JvmStatic
	fun get(): String {
		val waitTime = booleanArrayOf(true)
		val ip = arrayOf("0.0.0.0")

		object : Thread() {
			override fun run() {
				super.run()
				try {
					val whatismyip = URL("https://myexternalip.com/raw")
					BufferedReader(InputStreamReader(whatismyip.openStream())).use { reader ->
						ip[0] = reader.readLine()
					}
				} catch (e: Exception) {
					// Swallowed as before: a failed lookup leaves the 0.0.0.0 placeholder.
				} finally {
					waitTime[0] = false
				}
			}
		}.start()

		while (waitTime[0]) {
			Util.DelayMili(100)
		}
		return ip[0]
	}
}
