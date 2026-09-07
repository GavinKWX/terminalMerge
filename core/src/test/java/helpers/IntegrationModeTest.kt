package helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the old-vs-new integration rule, which decides how a POS request is routed and how its
 * amount is interpreted -- RM 10.00 versus 1000 cents. Getting it wrong charges the wrong amount
 * or rejects a valid sale, so the cases that used to differ between the two surfaces are asserted
 * explicitly.
 */
class IntegrationModeTest {

	// ---------- flagIsTrue: only boolean true or the string "true" ----------

	@Test
	fun `boolean true and the string true are the only true values`() {
		// Gson renders a JSON boolean as "true" via asString, so one check covers both the
		// boolean and quoted-string shapes the flag can arrive in.
		assertTrue(IntegrationMode.flagIsTrue("true"))
		assertTrue(IntegrationMode.flagIsTrue("TRUE"))
		assertTrue(IntegrationMode.flagIsTrue("True"))
		assertTrue(IntegrationMode.flagIsTrue(" true "))
	}

	@Test
	fun `false values are false`() {
		assertFalse(IntegrationMode.flagIsTrue("false"))
		assertFalse(IntegrationMode.flagIsTrue("FALSE"))
	}

	@Test
	fun `truthy-looking values are NOT true`() {
		// Deliberately strict: only "true" counts. A caller sending "1" is not stating old
		// integration, and guessing otherwise would reinterpret their amount.
		assertFalse(IntegrationMode.flagIsTrue("1"))
		assertFalse(IntegrationMode.flagIsTrue("yes"))
		assertFalse(IntegrationMode.flagIsTrue("Y"))
		assertFalse(IntegrationMode.flagIsTrue("on"))
	}

	@Test
	fun `absent or empty is not true`() {
		assertFalse(IntegrationMode.flagIsTrue(null))
		assertFalse(IntegrationMode.flagIsTrue(""))
		assertFalse(IntegrationMode.flagIsTrue("   "))
	}

	// ---------- isOld ----------

	@Test
	fun `no flag and a cents amount is new integration`() {
		assertFalse(IntegrationMode.isOld(flagIsTrue = false, amountInOldFormat = false))
	}

	@Test
	fun `a true flag selects old integration`() {
		assertTrue(IntegrationMode.isOld(flagIsTrue = true, amountInOldFormat = false))
	}

	@Test
	fun `an explicit false flag no longer selects old integration`() {
		// This is the correction to the earlier presence-based rule. Over HTTP,
		// `"IsOldIntegration": false` used to select OLD because only has() was checked; a caller
		// stating they are on new integration is now honoured.
		assertFalse(IntegrationMode.isOld(
			flagIsTrue = IntegrationMode.flagIsTrue("false"),
			amountInOldFormat = false))
	}

	@Test
	fun `an old-format amount still selects old integration with no flag`() {
		// The fallback matters: MF919 has no IsOldIntegration field at all, so its vendors never
		// send one. Without this, "10.00" would be read as 10 cents.
		assertTrue(IntegrationMode.isOld(flagIsTrue = false, amountInOldFormat = true))
	}

	@Test
	fun `both signals together still means old`() {
		assertTrue(IntegrationMode.isOld(flagIsTrue = true, amountInOldFormat = true))
	}

	@Test
	fun `the two surfaces now agree on the cases they used to differ on`() {
		// "IsOldIntegration": false with a cents amount.
		//   was: HTTP -> OLD (has() only) | intent -> NEW  ->  disagreed
		//   now: NEW on both
		assertFalse(IntegrationMode.isOld(IntegrationMode.flagIsTrue("false"), false))

		// "IsOldIntegration": "1" with a cents amount.
		//   was: HTTP -> OLD (has() only) | intent -> NEW  ->  disagreed
		//   now: NEW on both, because "1" is not "true"
		assertFalse(IntegrationMode.isOld(IntegrationMode.flagIsTrue("1"), false))

		// "IsOldIntegration": "true" -- OLD on both, as it always should have been
		assertTrue(IntegrationMode.isOld(IntegrationMode.flagIsTrue("true"), false))
	}
}
