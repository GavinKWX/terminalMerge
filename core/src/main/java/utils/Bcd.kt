package utils

/**
 * BCD/hex helpers shared by :core.
 *
 * Lifted out of each app's Utils for the MDB port: every frame arriving from the VMC is decoded
 * with [bcdToASCString], and MdbController now lives in :core. Pure -- no Android, no app state --
 * so each app's Utils delegates here rather than keeping a second copy.
 */
object Bcd {

	/** Renders each nibble as its ASCII hex character, so 0xAB becomes "AB". */
	@JvmStatic
	fun bcdToASCString(bytes: ByteArray): String {
		val temp = ByteArray(bytes.size * 2)
		for (i in bytes.indices) {
			var v = ((bytes[i].toInt() and 0xF0) shr 4 and 0x0F)
			temp[i * 2] = (if (v > 9) v + 65 - 10 else v + 48).toByte()
			v = bytes[i].toInt() and 0x0F
			temp[i * 2 + 1] = (if (v > 9) v + 65 - 10 else v + 48).toByte()
		}
		return String(temp)
	}
}
