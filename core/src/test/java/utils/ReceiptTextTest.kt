package utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receipt wording moved to [ReceiptText] in tranche 4 of the Utils slice.
 *
 * These strings are what the cardholder's copy says about PIN and signature, so they are a
 * contract with the acquirer's receipt rules rather than cosmetic text. Pinned exactly, newlines
 * included, because the printer lays them out verbatim.
 */
class ReceiptTextTest {

	private val noPin = "NO PIN REQUIRED\nNO SIGNATURE REQUIRED"
	private val pinNoSign = "PIN VERIFIED\nNO SIGNATURE REQUIRED"
	private val pinAndSign = "PIN VERIFIED\n\n\n______________________________\nSign"
	private val signOnly = "\n\n\n______________________________\nSign"

	// ---------- CVM results ----------

	@Test
	fun `CVM 1 and 2 print PIN verified with no signature`() {
		// Offline plaintext PIN and offline enciphered PIN.
		assertEquals(pinNoSign, ReceiptText.CVMAnalysis("41", ""))
		assertEquals(pinNoSign, ReceiptText.CVMAnalysis("42", ""))
	}

	@Test
	fun `CVM 3 and 5 print PIN verified AND a signature line`() {
		assertEquals(pinAndSign, ReceiptText.CVMAnalysis("43", ""))
		assertEquals(pinAndSign, ReceiptText.CVMAnalysis("45", ""))
	}

	@Test
	fun `CVM E prints a signature line only`() {
		// Signature alone: no PIN was checked, so the receipt must not claim one was.
		assertEquals(signOnly, ReceiptText.CVMAnalysis("4E", ""))
		assertTrue(!ReceiptText.CVMAnalysis("4E", "").contains("PIN"))
	}

	@Test
	fun `an unrecognised CVM falls back to no PIN and no signature`() {
		assertEquals(noPin, ReceiptText.CVMAnalysis("1F", ""))
		assertEquals(noPin, ReceiptText.CVMAnalysis("00", ""))
	}

	@Test
	fun `CVMAnalysis reads the second character, so a one-character value throws`() {
		// It indexes charAt(1) with no length check. Tag 9F34 is always three bytes so this has
		// never fired, but a caller that passes a nibble instead of a byte gets a crash on a
		// receipt print. Pinned rather than guarded -- guarding it would silently print
		// "NO PIN REQUIRED" for a malformed CVM, which is the worse failure.
		try {
			ReceiptText.CVMAnalysis("4", "")
			throw AssertionError("expected a StringIndexOutOfBoundsException")
		} catch (expected: StringIndexOutOfBoundsException) {
			// documented behaviour
		}
	}

	@Test
	fun `entryMode is accepted and ignored`() {
		// The branch that used it is commented out in both apps; every caller passes "". Kept in
		// the signature so the call sites did not have to change during the move.
		assertEquals(
			ReceiptText.CVMAnalysis("41", ""),
			ReceiptText.CVMAnalysis("41", "anything at all"),
		)
	}

	// ---------- POS entry mode ----------

	@Test
	fun `contact entry codes print Contact`() {
		for (code in listOf("0260", "0261", "0059", "0051")) {
			assertEquals(code, "Contact", ReceiptText.getPayMeythod(code))
		}
	}

	@Test
	fun `contactless entry codes print Contactless`() {
		for (code in listOf("0270", "0271", "0081", "0071")) {
			assertEquals(code, "Contactless", ReceiptText.getPayMeythod(code))
		}
	}

	@Test
	fun `magstripe entry codes print MagStripe`() {
		for (code in listOf("0021", "0801")) {
			assertEquals(code, "MagStripe", ReceiptText.getPayMeythod(code))
		}
	}

	@Test
	fun `an unknown entry code prints Manual, not an error`() {
		// Worth knowing: a new entry mode from the host shows up on the receipt as a manual key
		// entry. Silent, and wrong in a way a customer could query.
		assertEquals("Manual", ReceiptText.getPayMeythod("0999"))
		assertEquals("Manual", ReceiptText.getPayMeythod(""))
	}
}
