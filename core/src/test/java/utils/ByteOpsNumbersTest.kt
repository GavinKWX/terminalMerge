package utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ByteOps.atoi] came into `:core` with `IsoComm` and [ByteOps.convertLong] with `EmvUtil`.
 * Neither is a plain parse.
 *
 * It strips `.` and `,` before parsing and answers 0 for anything unparseable instead of
 * throwing. Both matter on the host path: `iPort = atoi(port1)` runs on every connection, and a
 * throw there would break a transaction that today just fails to connect. The original used
 * Apache `NumberUtils.toInt(value, 0)`, which `:core` does not depend on, so these cases pin the
 * behaviour that was reimplemented rather than trusting the two to agree.
 */
class ByteOpsNumbersTest {

	@Test
	fun `plain digits parse`() {
		assertEquals(15023, ByteOps.atoi("15023"))
		assertEquals(0, ByteOps.atoi("0"))
	}

	@Test
	fun `separators are stripped, not rejected`() {
		// An amount string reaching atoi becomes its minor units: "1,234.56" -> 123456.
		assertEquals(123456, ByteOps.atoi("1,234.56"))
		assertEquals(100, ByteOps.atoi("1.00"))
	}

	@Test
	fun `unparseable input answers zero rather than throwing`() {
		// The host path depends on this: a bad port must fail to connect, not crash the sale.
		assertEquals(0, ByteOps.atoi(""))
		assertEquals(0, ByteOps.atoi("abc"))
		assertEquals(0, ByteOps.atoi(" 5"))      // no trim, exactly as NumberUtils.toInt behaved
		assertEquals(0, ByteOps.atoi(null))
	}

	@Test
	fun `negatives survive`() {
		assertEquals(-7, ByteOps.atoi("-7"))
	}

	// ---------- convertLong ----------

	@Test
	fun `convertLong parses and answers zero rather than throwing`() {
		assertEquals(1234L, ByteOps.convertLong("1234"))
		assertEquals(0L, ByteOps.convertLong(""))
		assertEquals(0L, ByteOps.convertLong("abc"))
		assertEquals(0L, ByteOps.convertLong(null))
		assertEquals(-5L, ByteOps.convertLong("-5"))
	}

	@Test
	fun `convertLong does NOT strip separators, unlike atoi`() {
		// The callers in EmvUtil strip their own before calling -- `amount.replaceAll("\.", "")`
		// -- so stripping here too would silently double-handle an already-clean value.
		assertEquals(0L, ByteOps.convertLong("1,234"))
		assertEquals(0L, ByteOps.convertLong("12.34"))
		assertEquals(1234L, ByteOps.atoi("12.34").toLong())   // atoi is the one that strips
	}
}
