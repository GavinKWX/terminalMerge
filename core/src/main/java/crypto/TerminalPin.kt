package crypto

import java.util.Random

/**
 * Encodes the terminal password for the TMS PIN-check endpoint. Moved out of both apps'
 * `TmsHelper`, which held identical copies.
 *
 * The wire format is random padding around the PIN so that the same PIN does not encrypt to the
 * same ciphertext twice:
 *
 * ```
 * %02d(frontLen) %02d(rearLen) <frontLen random digits> <pin> <rearLen random digits>
 * ```
 *
 * then AES/ECB/PKCS5 with the server hash key, Base64. The server strips the padding using the
 * two length prefixes, so **the layout is a contract with the TMS side** -- it is not free to
 * change, which is why [buildPaddedPin] is split out and pinned by tests.
 *
 * The key arrives as a parameter rather than being read here: each app resolves it through its own
 * `Helper.getInstance().getPrefs()`, which is app-local by ruling.
 */
object TerminalPin {

	/**
	 * The padded plaintext, before encryption.
	 *
	 * Note [frontLen] and [rearLen] are digit *counts*, and a count of 0 contributes no digits --
	 * so `"00" + "00" + pin` is a legitimate outcome roughly one time in a hundred.
	 */
	@JvmStatic
	fun buildPaddedPin(pin: String, frontLen: Int, rearLen: Int, random: Random = Random()): String {
		val front = if (frontLen > 0) randomDigits(frontLen, random).toString() else ""
		val rear = if (rearLen > 0) randomDigits(rearLen, random).toString() else ""
		return String.format("%02d", frontLen) + String.format("%02d", rearLen) + front + pin + rear
	}

	/** Encodes [pin] for the TMS PIN-check endpoint using [serverHashKey]. */
	@JvmStatic
	fun encodePin(pin: String, serverHashKey: String): String {
		val rnd = Random()
		val padded = buildPaddedPin(pin, rnd.nextInt(10), rnd.nextInt(10), rnd)
		return Encryption.AESencrypt(padded, serverHashKey)
	}

	/**
	 * An [n]-digit number, i.e. one with no leading zero.
	 *
	 * `10^(n-1) + nextInt(9 * 10^(n-1))` -- so n=1 yields 1..9 and never 0, which is why a
	 * zero-length request is handled by the caller rather than here.
	 */
	private fun randomDigits(n: Int, random: Random): Int {
		val m = Math.pow(10.0, (n - 1).toDouble()).toInt()
		return m + random.nextInt(9 * m)
	}
}
