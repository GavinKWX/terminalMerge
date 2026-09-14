package utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The text-shaping and masking helpers moved out of both apps' `Utils` in tranche 2 of the
 * Utils slice.
 *
 * Most of these are used on receipts and on log lines, so a quiet change of shape would show up
 * as a wrong-looking receipt long after the fact rather than as a failure. Several also slice with
 * unguarded `substring()` and throw on short input. That is pre-existing behaviour with live
 * callers, so it is pinned here as an expectation rather than repaired during a move.
 */
class StringUtilsTextTest {

	// ---------- padding and spacing ----------

	@Test
	fun `blankSpace returns one fewer space than asked -- off by one, kept deliberately`() {
		// The loop is `for (loop = 1; loop < i)`, so the count is i-1. Every caller has been
		// compensating for this since the beginning; "fixing" it would silently shift the column
		// of every receipt line that uses it.
		assertEquals("", StringUtils.blankSpace(0))
		assertEquals("", StringUtils.blankSpace(1))
		assertEquals("    ", StringUtils.blankSpace(5))
	}

	@Test
	fun `symbolString repeats the symbol, and gives empty for a non-positive length`() {
		assertEquals("***", StringUtils.symbolString("*", 3))
		assertEquals("", StringUtils.symbolString("*", 0))
		assertEquals("", StringUtils.symbolString("*", -4))
	}

	@Test
	fun `spaceBtwNoChar groups in fours and leaves the tail alone`() {
		assertEquals("1234 5678 9012 3456", StringUtils.spaceBtwNoChar("1234567890123456", 4))
		// The threshold is a "stop when this short" limit, not a group size: the grouping is
		// always 4 regardless of what is passed.
		assertEquals("1234 567", StringUtils.spaceBtwNoChar("1234567", 4))
		assertEquals("123", StringUtils.spaceBtwNoChar("123", 4))
	}

	// ---------- masking ----------

	@Test
	fun `maskString keeps the requested number of trailing characters`() {
		assertEquals("******7890", StringUtils.maskString("1234567890", 4))
		// Nothing to mask: returned untouched, not padded.
		assertEquals("1234", StringUtils.maskString("1234", 4))
		assertEquals("123", StringUtils.maskString("123", 4))
	}

	@Test
	fun `mask_pan keeps first six and last four, then pads to twenty`() {
		// No caller today -- checked, it is zero across both apps. So the 20-wide zero padding
		// is not a verified host field width, just what this code does; pinned as behaviour so
		// a future caller can see the shape before trusting it.
		assertEquals("41111100000011110000", StringUtils.mask_pan("4111111111111111"))
		assertEquals(20, StringUtils.mask_pan("4111111111111111").length)
	}

	@Test
	fun `mask_pan throws on a PAN shorter than six digits`() {
		// substring(0, 6) is unguarded. Real PANs are 13-19 digits so this has never fired, but
		// it is one bad tag-5A read away from a crash -- pinned so the next caller learns it here.
		try {
			StringUtils.mask_pan("41111")
			throw AssertionError("expected a StringIndexOutOfBoundsException")
		} catch (expected: StringIndexOutOfBoundsException) {
			// documented behaviour, not a wish
		}
	}

	@Test
	fun `maskIp keeps only the last dot-separated part`() {
		assertEquals("xxx.xxx.xxx.165", StringUtils.maskIp("192.168.100.165"))
		assertEquals("-", StringUtils.maskIp(""))
		assertEquals("-", StringUtils.maskIp("   "))
	}

	@Test
	fun `maskIp does not validate -- it just takes whatever follows the last dot`() {
		// Worth knowing before reusing it: a hostname or a host:port is passed through almost
		// intact, so it is a log-tidying helper, not a redaction guarantee.
		assertEquals("xxx.xxx.xxx.my-host", StringUtils.maskIp("my-host"))
		assertEquals("xxx.xxx.xxx.165:8888", StringUtils.maskIp("192.168.100.165:8888"))
	}

	// ---------- trimming ----------

	@Test
	fun `removeCarNumChar cuts at the first F -- the tag 5A pad character`() {
		assertEquals("411111111111111", StringUtils.removeCarNumChar("411111111111111F"))
		assertEquals("4111111111111111", StringUtils.removeCarNumChar("4111111111111111"))
		// It cuts at the FIRST F, not just trailing pad. Only ever fed digits-plus-pad today.
		assertEquals("", StringUtils.removeCarNumChar("F123"))
	}

	@Test
	fun `removeWhiteSpace strips spaces and tabs, inside as well as at the ends`() {
		assertEquals("abcdef", StringUtils.removeWhiteSpace("  ab c\tde f "))
		assertEquals("", StringUtils.removeWhiteSpace("   "))
	}

	// ---------- date and time shaping ----------

	@Test
	fun `DateFormat turns YYYYMMDD into DD-MM-YYYY`() {
		assertEquals("10-09-2026", StringUtils.DateFormat("20260910"))
	}

	@Test
	fun `TimeFormat turns HHMMSS into HH colon MM colon SS`() {
		assertEquals("14:30:45", StringUtils.TimeFormat("143045"))
	}

	@Test
	fun `DateFormat and TimeFormat throw on short input`() {
		// Both are fed fixed-width ISO fields, so a short value means the field was missing --
		// which is exactly when a crash is least welcome. Pinned, not fixed.
		try {
			StringUtils.DateFormat("2026")
			throw AssertionError("expected DateFormat to throw")
		} catch (expected: StringIndexOutOfBoundsException) {
		}
		try {
			StringUtils.TimeFormat("14")
			throw AssertionError("expected TimeFormat to throw")
		} catch (expected: StringIndexOutOfBoundsException) {
		}
	}

	// ---------- receipt labelling ----------

	@Test
	fun `getTxnType renames only MOTO, and passes everything else through`() {
		assertEquals("Sale", StringUtils.getTxnType("MOTO"))
		assertEquals("Sale", StringUtils.getTxnType("moto"))
		assertEquals("Void", StringUtils.getTxnType("Void"))
		assertEquals("Settlement", StringUtils.getTxnType("Settlement"))
	}
}
