package data_enum

import java.util.regex.Pattern;

enum class CardSchemeEnum {
	UNKNOWN,
	MCCS("", "mccs"),
	VISA("^4[0-9]{12}(?:[0-9]{3}){0,2}$", "visa"),
	MASTERCARD("^(?:5[1-5]|2(?!2([01]|20)|7(2[1-9]|3))[2-7])\\d{14}$", "master"),
	AMERICAN_EXPRESS("^3[47][0-9]{13}$", ""),
	DINERS_CLUB("^3(?:0[0-5]\\d|095|6\\d{0,2}|[89]\\d{2})\\d{12,15}$", ""),
	DISCOVER("^6(?:011|[45][0-9]{2})[0-9]{12}$", ""),
	JCB("^(?:2131|1800|35\\d{3})\\d{11}$", ""),
	UPI("^62[0-9]{14,17}$", "upi");

	private var pattern: Pattern?
	var typeIdentifier: String
	constructor() {
		pattern = null
		typeIdentifier = ""
	}

	constructor(pattern: String, typeIdentifier: String) {
		this.pattern = Pattern.compile(pattern)
		this.typeIdentifier = typeIdentifier
	}

	companion object {
		fun detectByCardSchemeID(schemeId :String): CardSchemeEnum {
			return when (schemeId) {
				"11", "12", "91" -> VISA
				"20", "21", "22", "92" -> MASTERCARD
				"31", "93" -> UPI
				"81", "98", "82", "99" -> MCCS
				else -> UNKNOWN
			}
		}

		fun detect(cardNumber: String): CardSchemeEnum {
			for (cardType in values()) {
				if (null == cardType.pattern) continue
				if (cardType.pattern!!.matcher(cardNumber).matches()) return cardType
			}
			return UNKNOWN
		}

		fun isValid(cardNumber: String): Boolean{
			var sum = 0
			var alternate = false
			for (i in cardNumber.length - 1 downTo 0) {
				var n = cardNumber.substring(i, i + 1).toInt()
				if (alternate) {
					n *= 2
					if (n > 9) {
						n = n % 10 + 1
					}
				}
				sum += n
				alternate = !alternate
			}
			return sum % 10 == 0
		}
	}
}

