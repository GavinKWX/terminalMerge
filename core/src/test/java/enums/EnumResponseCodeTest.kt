package enums

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are vendor-visible strings: a POS integration branches on the code and, in practice,
 * sometimes matches the description. So the tests pin the exact text rather than merely checking
 * that a constant exists -- the failure they exist to catch is someone "tidying" the wording and
 * silently breaking a live integration.
 */
class EnumResponseCodeTest {

	@Test
	fun `the A3 decision on SHC007 is encoded`() {
		// MF919 said "Terminal System Error", Pro said "Product Is Not Configured", for the same
		// code. The resolution keeps the former and carries the cause in parentheses.
		assertEquals("SHC007", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
		assertEquals("Terminal System Error", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
		assertEquals("SHC007", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
		assertEquals(
			"Terminal System Error (Product Is Not Configured)",
			EnumResponseCode.PRODUCT_NOT_CONFIGURED.description
		)
	}

	@Test
	fun `product-not-configured is never emitted as a bare description`() {
		// The pre-fix MF919 wording. If this string ever comes back as a whole description, the
		// A3 decision has been reverted somewhere.
		val bare = "Product Is Not Configured"
		val offenders = EnumResponseCode.values().filter { it.description == bare }
		assertTrue("bare '$bare' must not be a description on its own", offenders.isEmpty())
	}

	@Test
	fun `invalidParameter builds the exact shipping string`() {
		val (code, desc) = EnumResponseCode.invalidParameter("TransactionAmount")
		assertEquals("SHC001", code)
		// Spacing and brackets are part of the contract.
		assertEquals("Invalid Parameter - (TransactionAmount)", desc)
	}

	@Test
	fun `codes cover the documented contract surface`() {
		assertEquals(
			setOf(
				"SHC000", "SHC001", "SHC002", "SHC003", "SHC004", "SHC005",
				"SHC007", "SHC008", "SHC009", "SHC010", "SHC011"
			),
			EnumResponseCode.codes()
		)
	}

	@Test
	fun `SHC007 carries more than one meaning -- documents the open collision`() {
		// Not an aspiration: this asserts the CURRENT shipping state so the collision cannot be
		// forgotten. Timeout sharing a code with a system error means a vendor cannot tell
		// "investigate" from "retry". When that is resolved this test changes deliberately.
		val shc007 = EnumResponseCode.values().filter { it.code == "SHC007" }.map { it.description }
		assertTrue(
			"expected the timeout description still colliding on SHC007, got $shc007",
			shc007.contains("Terminal Response Timeout")
		)
		assertTrue("SHC007 should still be overloaded", shc007.size > 1)
	}

	@Test
	fun `the timeout pair is pinned -- both apps emit these exact bytes`() {
		// A3b: MF919 and Pro both write this code/description literally into their own HTTPServer,
		// so the two copies can only stay identical if the text is treated as fixed. Pin it here;
		// a reword that misses one app puts two different strings on the wire for one event.
		assertEquals("SHC007", EnumResponseCode.TERMINAL_RESPONSE_TIMEOUT.code)
		assertEquals("Terminal Response Timeout", EnumResponseCode.TERMINAL_RESPONSE_TIMEOUT.description)
	}

	@Test
	fun `the six descriptions found shipping in 2026-09 are pinned`() {
		// These were on the wire before they were in this enum. They are pinned for the same
		// reason as everything else here: a vendor may be matching on the text.
		val expected = mapOf(
			EnumResponseCode.INVALID_PAYMENT_CHANNEL to "Invalid Payment Channel",
			EnumResponseCode.INVALID_REF_ID to "Invalid Ref ID",
			EnumResponseCode.INVALID_POS_REFERENCE_NO to "Invalid PosReference No",
			EnumResponseCode.INVALID_TRANSACTION_ID to "Invalid Transaction ID",
			EnumResponseCode.AMOUNT_NOT_POSITIVE to "Trade amount should be greater than 0",
			EnumResponseCode.AMOUNT_TOO_LARGE to "Trade amount too large",
		)
		for ((constant, text) in expected) {
			assertEquals("$constant description", text, constant.description)
			assertEquals("$constant code", "SHC001", constant.code)
		}
	}

	@Test
	fun `no two entries share a code AND a description`() {
		val seen = EnumResponseCode.values().map { it.code to it.description }
		assertEquals("duplicate code+description pair", seen.size, seen.toSet().size)
	}
}
