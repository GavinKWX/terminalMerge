package utils

/**
 * Minor-unit amount formatting shared by :core.
 *
 * Turns an amount in cents into its display form: "1000" -> "10.00". Lifted out of each app's
 * Utils for the MDB port, since MdbController formats VMC prices for the log and for the
 * denomination product it hands to the payment screen. Pure, so each app's Utils delegates here.
 */
object AmountFormat {

	/** Left-pads with '0' to at least [len] characters. */
	@JvmStatic
	fun zeroPadding(value: String, len: Int): String = value.padStart(len, '0')

	/**
	 * "1000" -> "10.00". "0" is returned unchanged, matching the original behaviour that a
	 * zero amount is displayed bare rather than as "0.00".
	 */
	@JvmStatic
	fun getActualAmount(value: String): String {
		if (value == "0") return value
		val v = if (value.length < 3) zeroPadding(value, 3) else value
		val major = v.toLong() / 100
		return major.toString() + "." + v.substring(v.length - 2)
	}
}
