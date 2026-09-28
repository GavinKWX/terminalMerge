package helpers

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Asks an external service for this terminal's public IP, for the `TERMINAL_IP` header on TMS
 * requests. Bounded by timeouts; a failed or slow lookup gives the same `0.0.0.0` as before (item 93).
 */
object PublicIp {

	private const val URL_LOOKUP = "https://myexternalip.com/raw"
	internal const val CONNECT_TIMEOUT_MS = 5_000
	internal const val READ_TIMEOUT_MS = 5_000
	internal const val JOIN_TIMEOUT_MS = 12_000L
	internal const val FALLBACK = "0.0.0.0"

	/** Returns the public IP, or `"0.0.0.0"` if the lookup failed or timed out. */
	@JvmStatic
	fun get(): String = get(JOIN_TIMEOUT_MS) { fetch() }

	// The worker thread keeps the network call off the caller, which may be the main thread.
	internal fun get(joinTimeoutMs: Long, lookup: () -> String?): String {
		val ip = AtomicReference<String?>(null)
		val worker = Thread {
			try {
				ip.set(lookup()?.trim()?.takeIf { it.isNotEmpty() })
			} catch (e: Exception) {
				// A failed lookup leaves the fallback.
			}
		}
		worker.isDaemon = true
		worker.start()
		try {
			worker.join(joinTimeoutMs)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
		}
		return ip.get() ?: FALLBACK
	}

	private fun fetch(): String? {
		val conn = URL(URL_LOOKUP).openConnection() as HttpURLConnection
		conn.connectTimeout = CONNECT_TIMEOUT_MS
		conn.readTimeout = READ_TIMEOUT_MS
		return try {
			BufferedReader(InputStreamReader(conn.inputStream)).use { it.readLine() }
		} finally {
			conn.disconnect()
		}
	}
}
