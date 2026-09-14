package crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The padded-PIN layout moved to [TerminalPin] from both apps' `TmsHelper`.
 *
 * The TMS server strips the random padding using the two length prefixes, so this layout is a
 * contract with the server and not an implementation detail. Get the prefix width or the digit
 * count wrong and the server recovers the wrong PIN, which presents as a terminal password that
 * "sometimes" fails -- roughly the worst possible symptom to debug from the field.
 *
 * Only [TerminalPin.buildPaddedPin] is tested. [TerminalPin.encodePin] goes on to call
 * `Encryption.AESencrypt`, which uses `android.util.Base64`; with `returnDefaultValues` that is
 * stubbed, so encrypting proves nothing here. The padding is the part that can silently drift.
 */
class TerminalPinTest {

	@Test
	fun `zero padding lengths still emit both two-digit prefixes`() {
		// Happens about one time in a hundred (both nextInt(10) draws are 0). The prefixes must
		// still be there or the server misreads the whole field.
		assertEquals("00001234", TerminalPin.buildPaddedPin("1234", 0, 0))
	}

	@Test
	fun `the prefixes are the digit counts, zero-padded to two`() {
		val out = TerminalPin.buildPaddedPin("1234", 2, 3, Random(1))
		assertEquals("02", out.substring(0, 2))
		assertEquals("03", out.substring(2, 4))
		assertEquals(4 + 2 + 4 + 3, out.length)
	}

	@Test
	fun `the pin sits between exactly frontLen and rearLen random digits`() {
		for (front in 0..9) {
			for (rear in 0..9) {
				val out = TerminalPin.buildPaddedPin("9999", front, rear, Random(7))
				assertEquals(
					"front=$front rear=$rear",
					4 + front + 4 + rear,
					out.length,
				)
				assertEquals("9999", out.substring(4 + front, 4 + front + 4))
			}
		}
	}

	@Test
	fun `padding digits never start with zero, so the count is always exact`() {
		// randomDigits is 10^(n-1) + nextInt(9 * 10^(n-1)), which is why the count can be trusted
		// as a length. A leading zero would make the server's substring arithmetic wrong.
		for (front in 1..9) {
			val out = TerminalPin.buildPaddedPin("1111", front, 0, Random(3))
			assertTrue("front=$front produced $out", out[4] != '0')
		}
	}

	@Test
	fun `the whole output is digits only`() {
		val out = TerminalPin.buildPaddedPin("0000", 5, 5, Random(42))
		assertTrue(out, out.all { it.isDigit() })
	}

	@Test
	fun `the same seed gives the same padding, so this is reproducible`() {
		assertEquals(
			TerminalPin.buildPaddedPin("4321", 4, 4, Random(99)),
			TerminalPin.buildPaddedPin("4321", 4, 4, Random(99)),
		)
	}
}
