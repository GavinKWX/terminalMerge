package utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte / BCD / C-string primitives moved out of both apps' `Utils` on 2026-09-10.
 *
 * These are the layer every ISO message is built from, and they are the reason the move was done
 * with tests rather than on a textual diff: a wrong `memcmp` or `sscanf` does not fail a build or
 * a smoke test, it produces a malformed host message. The move itself was verbatim, so these
 * cases exist to pin behaviour from here on — especially the C-library shapes (int returns,
 * out-parameters) that are easy to "tidy" into something subtly different.
 *
 * They also nearly earned their keep immediately: the scripted delegation of these methods
 * mis-wired three overloads, and one of them (`memcmp(byte[], String, int)` called with a
 * four-argument list) would have been a compile error, but the same class of slip between two
 * same-arity overloads would not have been.
 */
class ByteOpsPrimitivesTest {

	// ---------- ASCII / byte conversion ----------

	@Test
	fun `AsciiToByteArray and Byte2ASCII round-trip`() {
		val text = "0200ABC"
		val bytes = ByteOps.AsciiToByteArray(text)
		assertEquals(text.length, bytes.size)
		assertEquals(text, ByteOps.Byte2ASCII(bytes))
	}

	@Test
	fun `Byte2ASCII does NOT stop at NUL -- every byte becomes a char`() {
		// Written as an expectation first and the test corrected it: there is no terminator
		// handling at all. A fixed-size NUL-padded buffer therefore comes back with the padding
		// as U+0000 characters, which is precisely how a short read once turned into a bogus
		// "SHC001 Invalid Input" -- see the readRequestBody note in the audit doc. Callers must
		// slice to the real length before calling.
		val buf = byteArrayOf(0x31, 0x32, 0x00, 0x39)
		assertEquals(4, ByteOps.Byte2ASCII(buf).length)
		// Built from chars so no control character ends up in this source file.
		val expected = String(charArrayOf('1', '2', Char(0), '9'))
		assertEquals(expected, ByteOps.Byte2ASCII(buf))
	}

	// ---------- BCD ----------

	@Test
	fun `bcd2Int reads packed decimal, not hex`() {
		// 0x12 0x34 is the decimal 1234 in BCD, not 0x1234.
		assertEquals(1234, ByteOps.bcd2Int(byteArrayOf(0x12, 0x34), 0, 2))
		assertEquals(0, ByteOps.bcd2Int(byteArrayOf(0x00, 0x00), 0, 2))
		assertEquals(99, ByteOps.bcd2Int(byteArrayOf(0x99.toByte()), 0, 1))
	}

	@Test
	fun `bcd2Int honours the offset`() {
		val buf = byteArrayOf(0xFF.toByte(), 0x12, 0x34)
		assertEquals(1234, ByteOps.bcd2Int(buf, 1, 2))
	}

	@Test
	fun `bcd2hex converts one packed byte`() {
		assertEquals(12.toByte(), ByteOps.bcd2hex(0x12.toByte()))
		assertEquals(0.toByte(), ByteOps.bcd2hex(0x00.toByte()))
	}

	// ---------- memcmp: 0 means equal, C-style ----------

	@Test
	fun `memcmp returns zero for equal regions and non-zero otherwise`() {
		val a = byteArrayOf(1, 2, 3, 4)
		val b = byteArrayOf(9, 1, 2, 3)
		assertEquals("equal regions must compare 0", 0, ByteOps.memcmp(a, 0, b, 1, 3))
		assertNotEquals(0, ByteOps.memcmp(a, 0, b, 0, 3))
	}

	@Test
	fun `memcmp compares only dataLen bytes`() {
		val a = byteArrayOf(1, 2, 3, 9)
		val b = byteArrayOf(1, 2, 3, 8)
		assertEquals(0, ByteOps.memcmp(a, 0, b, 0, 3))
		assertNotEquals(0, ByteOps.memcmp(a, 0, b, 0, 4))
	}

	@Test
	fun `the String overloads compare against US-ASCII bytes`() {
		val buf = ByteOps.AsciiToByteArray("0210")
		assertEquals(0, ByteOps.memcmp(buf, "0210", 4))
		assertEquals(0, ByteOps.memcmp(buf, 0, "0210", 4))
		assertNotEquals(0, ByteOps.memcmp(buf, "0200", 4))
	}

	// ---------- strcmp ----------

	@Test
	fun `strcmp returns zero for equal strings`() {
		assertEquals(0, ByteOps.strcmp("abc", "abc"))
		assertNotEquals(0, ByteOps.strcmp("abc", "abd"))
	}

	// ---------- sscanf: the out-parameter shape ----------

	@Test
	fun `sscanf writes the found value into the caller's array`() {
		val found = arrayOfNulls<String>(1)
		val n = ByteOps.sscanf("TAG=0210;", "TAG=", found)
		assertTrue("a hit must report a non-negative result", n >= 0)
		assertTrue("the out-parameter must be populated, not returned", found[0] != null)
	}

	@Test
	fun `sscanf reports a miss without touching the out-parameter`() {
		val found = arrayOfNulls<String>(1)
		ByteOps.sscanf("NOTHING HERE", "TAG=", found)
		assertEquals(null, found[0])
	}

	// ---------- string helpers ----------

	@Test
	fun `findCharWithLoc returns every index of the character`() {
		val at = ByteOps.findCharWithLoc("a,b,c", ',')
		assertArrayEquals(intArrayOf(1, 3), at)
	}

	@Test
	fun `String2ArrayString splits on newlines, not characters`() {
		assertArrayEquals(arrayOf("a", "b", "c"), ByteOps.String2ArrayString("a\nb\nc"))
		assertArrayEquals(arrayOf("a", ""), ByteOps.String2ArrayString("a\n"))
	}

	@Test
	fun `String2ArrayString throws when the input has no newline`() {
		// Found by writing this test, not by reading the code. With no newline the index array
		// is empty and the j==0 branch still dereferences loc[0]. Every caller today happens to
		// pass multi-line text, so it has never fired -- pinned here so a new caller learns it
		// from a red test rather than from a crash on a terminal.
		try {
			ByteOps.String2ArrayString("abc")
			throw AssertionError("expected an ArrayIndexOutOfBoundsException")
		} catch (expected: ArrayIndexOutOfBoundsException) {
			// documented behaviour, not a wish
		}
	}
}
