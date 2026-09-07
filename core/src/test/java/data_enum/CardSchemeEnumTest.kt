package data_enum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card scheme detection and the Luhn check, now shared by both apps.
 *
 * Getting a scheme wrong picks the wrong acquirer route for a live transaction, so the BIN
 * ranges are worth pinning. All PANs below are the payment industry's published test numbers,
 * not real cards.
 */
class CardSchemeEnumTest {

	// ---------- detect(pan) ----------

	@Test
	fun `detects the major schemes from their BIN ranges`() {
		assertEquals(CardSchemeEnum.VISA, CardSchemeEnum.detect("4111111111111111"))
		assertEquals(CardSchemeEnum.MASTERCARD, CardSchemeEnum.detect("5555555555554444"))
		assertEquals(CardSchemeEnum.AMERICAN_EXPRESS, CardSchemeEnum.detect("378282246310005"))
		assertEquals(CardSchemeEnum.DISCOVER, CardSchemeEnum.detect("6011111111111117"))
		assertEquals(CardSchemeEnum.JCB, CardSchemeEnum.detect("3530111333300000"))
		assertEquals(CardSchemeEnum.UPI, CardSchemeEnum.detect("6212345678901234"))
	}

	@Test
	fun `visa accepts 13 and 16 digit forms`() {
		// ^4[0-9]{12}(?:[0-9]{3}){0,2}$ -- 13, 16 or 19 digits
		assertEquals(CardSchemeEnum.VISA, CardSchemeEnum.detect("4111111111111"))
		assertEquals(CardSchemeEnum.VISA, CardSchemeEnum.detect("4111111111111111"))
	}

	@Test
	fun `UPI and DISCOVER both start with 6 but do not collide`() {
		// DISCOVER needs 6011 or 64x/65x; UPI needs 62. A card matching one must not match
		// the other, or the acquirer route flips depending on enum declaration order.
		assertEquals(CardSchemeEnum.DISCOVER, CardSchemeEnum.detect("6011111111111117"))
		assertEquals(CardSchemeEnum.UPI, CardSchemeEnum.detect("6212345678901234"))
	}

	@Test
	fun `unrecognised input is UNKNOWN, not a guess`() {
		assertEquals(CardSchemeEnum.UNKNOWN, CardSchemeEnum.detect("1234"))
		assertEquals(CardSchemeEnum.UNKNOWN, CardSchemeEnum.detect("9999999999999999"))
		assertEquals(CardSchemeEnum.UNKNOWN, CardSchemeEnum.detect("not-a-pan"))
	}

	// ---------- detectByCardSchemeID(id) ----------

	@Test
	fun `scheme ids map to the same schemes the ISO layer expects`() {
		listOf("11", "12", "91").forEach { assertEquals(CardSchemeEnum.VISA, CardSchemeEnum.detectByCardSchemeID(it)) }
		listOf("20", "21", "22", "92").forEach { assertEquals(CardSchemeEnum.MASTERCARD, CardSchemeEnum.detectByCardSchemeID(it)) }
		listOf("31", "93").forEach { assertEquals(CardSchemeEnum.UPI, CardSchemeEnum.detectByCardSchemeID(it)) }
		listOf("81", "98", "82", "99").forEach { assertEquals(CardSchemeEnum.MCCS, CardSchemeEnum.detectByCardSchemeID(it)) }
	}

	@Test
	fun `an unmapped scheme id is UNKNOWN`() {
		assertEquals(CardSchemeEnum.UNKNOWN, CardSchemeEnum.detectByCardSchemeID("00"))
		assertEquals(CardSchemeEnum.UNKNOWN, CardSchemeEnum.detectByCardSchemeID(""))
	}

	@Test
	fun `typeIdentifier is the string the QR and acquirer config key off`() {
		assertEquals("visa", CardSchemeEnum.VISA.typeIdentifier)
		assertEquals("master", CardSchemeEnum.MASTERCARD.typeIdentifier)
		assertEquals("upi", CardSchemeEnum.UPI.typeIdentifier)
		assertEquals("mccs", CardSchemeEnum.MCCS.typeIdentifier)
	}

	// ---------- isValid(pan) : Luhn ----------

	@Test
	fun `Luhn accepts the published test PANs`() {
		assertTrue(CardSchemeEnum.isValid("4111111111111111"))
		assertTrue(CardSchemeEnum.isValid("5555555555554444"))
		assertTrue(CardSchemeEnum.isValid("378282246310005"))
	}

	@Test
	fun `Luhn rejects a single altered digit`() {
		// The check digit exists to catch exactly this -- a mistyped or misread PAN.
		assertFalse(CardSchemeEnum.isValid("4111111111111112"))
		assertFalse(CardSchemeEnum.isValid("5555555555554445"))
	}
}
