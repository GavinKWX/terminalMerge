package utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tranche 5 of the Utils slice: the methods `:core` **already had**, where each app kept its own
 * copy alongside.
 *
 * The risk here is the reverse of the earlier tranches. Nothing moves, so nothing can break in
 * transit; what can go wrong is that the two implementations were never actually the same and a
 * delegation quietly changes an answer. Eight of them are byte-identical to the app version once
 * comments and whitespace are stripped, so those are pinned briefly. The four that are *not*
 * identical get real terminal inputs, and they are the reason this file exists:
 *
 *  - `zeroPadding` -- a while-loop in the app, `padStart` in `:core`
 *  - `getActualAmount` -- reimplemented in Kotlin
 *  - `convertLong` -- Apache `NumberUtils.toLong(v, 0)` in the app, try/catch `parseLong` here
 *  - `hideCardDetails` -- the app's own `symbolString`, the vendor library's here
 */
class CoreEquivalenceTest {

	// ---------- zeroPadding: while-loop vs padStart ----------

	@Test
	fun `zeroPadding left-pads to the requested width`() {
		assertEquals("0001", AmountFormat.zeroPadding("1", 4))
		assertEquals("000123", AmountFormat.zeroPadding("123", 6))
	}

	@Test
	fun `zeroPadding never truncates, and an exact fit is untouched`() {
		// The app's guard is `if (s.length() < len)`, padStart's is the same comparison. Both
		// return a longer input unchanged -- which matters, because STAN and amount fields get
		// padded here and a truncating version would silently corrupt them.
		assertEquals("123456", AmountFormat.zeroPadding("123456", 4))
		assertEquals("1234", AmountFormat.zeroPadding("1234", 4))
		assertEquals("", AmountFormat.zeroPadding("", 0))
	}

	// ---------- getActualAmount: cents to display ----------

	@Test
	fun `getActualAmount turns cents into a two-decimal amount`() {
		assertEquals("10.00", AmountFormat.getActualAmount("1000"))
		assertEquals("1.00", AmountFormat.getActualAmount("100"))
		assertEquals("123.45", AmountFormat.getActualAmount("12345"))
	}

	@Test
	fun `getActualAmount pads short values to three digits first`() {
		// "5" becomes "005" then 0.05 -- five cents, not five ringgit.
		assertEquals("0.05", AmountFormat.getActualAmount("5"))
		assertEquals("0.50", AmountFormat.getActualAmount("50"))
	}

	@Test
	fun `getActualAmount returns a bare zero rather than 0-00`() {
		// Deliberate in the original and kept: a zero amount displays as "0", not "0.00".
		// Receipts and the MDB price log both rely on this.
		assertEquals("0", AmountFormat.getActualAmount("0"))
	}

	// ---------- convertLong: NumberUtils vs try-catch parseLong ----------

	@Test
	fun `convertLong parses a plain number`() {
		assertEquals(1234L, ByteOps.convertLong("1234"))
		assertEquals(0L, ByteOps.convertLong("0"))
		assertEquals(-5L, ByteOps.convertLong("-5"))
	}

	@Test
	fun `convertLong returns zero for everything it cannot parse`() {
		// This is the whole contract, and it is what makes the Apache version replaceable:
		// NumberUtils.toLong(v, 0) is documented as try/catch parseLong with a default, which is
		// exactly what :core does. Every one of these cases returns 0 either way.
		assertEquals(0L, ByteOps.convertLong(null))
		assertEquals(0L, ByteOps.convertLong(""))
		assertEquals(0L, ByteOps.convertLong("abc"))
		assertEquals(0L, ByteOps.convertLong("12.5"))
		assertEquals(0L, ByteOps.convertLong(" 12 "))
		assertEquals(0L, ByteOps.convertLong("99999999999999999999"))
	}

	@Test
	fun `convertLong accepts a leading plus, as parseLong does`() {
		assertEquals(12L, ByteOps.convertLong("+12"))
	}

	// ---------- hideCardDetails: masking with the vendor symbolString ----------

	@Test
	fun `hideCardDetails keeps first six and last four by default`() {
		assertEquals("411111******1111", Util.hideCardDetails("4111111111111111"))
	}

	@Test
	fun `hideCardDetails with false masks everything but the last four`() {
		assertEquals("************1111", Util.hideCardDetails("4111111111111111", false))
	}

	@Test
	fun `hideCardDetails leaves anything eleven characters or shorter alone`() {
		// The guard is length > 11, so a short value is returned in the clear. Nothing feeds it
		// short PANs today, but it is not a redaction guarantee -- LogRedact is.
		assertEquals("41111111111", Util.hideCardDetails("41111111111"))
		assertEquals("1234", Util.hideCardDetails("1234"))
	}

	// ---------- the byte-identical ones, pinned briefly ----------

	@Test
	fun `ASCIItoByte and ASCIItoHexString are the pair the ISO code uses`() {
		assertArrayEquals(byteArrayOf(0x30, 0x32, 0x30, 0x30), ByteOps.ASCIItoByte("0200"))
		assertEquals("30323030", ByteOps.ASCIItoHexString("0200"))
	}

	@Test
	fun `arrayCopy returns the offset just past what it wrote`() {
		// The return value is the point of it: ISO field packing chains one call into the next.
		val dest = ByteArray(8)
		val next = ByteOps.arrayCopy(byteArrayOf(1, 2, 3), 0, dest, 2, 3)
		assertEquals(5, next)
		assertArrayEquals(byteArrayOf(0, 0, 1, 2, 3, 0, 0, 0), dest)
	}

	@Test
	fun `bin2bcd writes packed decimal, most significant byte first`() {
		val out = ByteArray(2)
		ByteOps.bin2bcd(1234, 2, out, 0)
		assertArrayEquals(byteArrayOf(0x12, 0x34), out)
	}

	@Test
	fun `hex2bcd packs a value under 100 into one byte, in all three overloads`() {
		assertEquals(0x12.toByte(), ByteOps.hex2bcd(12.toByte()))
		assertEquals(0x12.toByte(), ByteOps.hex2bcd(12))
		assertEquals(0x12.toByte(), ByteOps.hex2bcd(12L))
	}

	@Test
	fun `toByte maps a hex digit to its value, and anything else to minus one`() {
		assertEquals(0.toByte(), ByteOps.toByte('0'))
		assertEquals(10.toByte(), ByteOps.toByte('A'))
		assertEquals(15.toByte(), ByteOps.toByte('F'))
		// Lowercase is NOT handled -- the lookup string is uppercase only, so 'a' is a miss.
		assertEquals((-1).toByte(), ByteOps.toByte('a'))
		assertEquals((-1).toByte(), ByteOps.toByte('x'))
	}
}
