package helpers

import com.library.terminal.Cryptography
import com.library.terminal.Utility

/**
 * Request signing shared by :core.
 *
 * Lifted out of HelperCommon for the same reason as [HelperDate]: every TMS handler signs its
 * body into the `CHECKSUM` header, so the implementation has to live where the handlers do.
 * Backed by terminalLib.jar, which :core already exposes to both apps.
 */
object HelperCrypto {

	/** HMAC-SHA256 over the ASCII-hex of the message, keyed by the ASCII-hex of the key. */
	@JvmStatic
	fun toHmacSha256(msg: String, key: String): String =
		Cryptography.HMAC(
			Utility.ASCIItoHexString(msg),
			Utility.ASCIItoHexString(key),
			Cryptography.hashAlgorithm.SHA_256
		)
}
