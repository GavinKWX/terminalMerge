package helpers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The first tests in this repo.
 *
 * [LogRedact] is the right place to start: both apps now depend on it for PAN and key
 * material never reaching TerminaLog, which is uploaded to TMS -- so a regression here
 * leaks cardholder data off the device. It is also pure Kotlin with no Android types,
 * so it runs as a plain JVM test with no instrumentation.
 *
 * The registered-card-data state is process-global and @Volatile, so every test clears it
 * on both sides of the run; otherwise these pass or fail depending on ordering.
 */
class LogRedactTest {

	// Clearing needs the generation in force, which register() hands out -- so ask for the
	// current one rather than assuming a value.
	private fun wipe() = LogRedact.clearCardData(LogRedact.currentGeneration())

	@Before fun reset() = wipe()
	@After fun clear() = wipe()

	// ---------- track2 ----------

	@Test
	fun `track2 keeps BIN and last four, drops everything after the separator`() {
		// 16-digit PAN, 'D' separator, then expiry + service code + discretionary data
		val raw = "4111111111111111D25121011234567890"
		val out = LogRedact.track2(raw)

		assertEquals("411111******1111[len=${raw.length}]", out)
		assertFalse("expiry must not survive", out.contains("2512"))
		assertFalse("discretionary data must not survive", out.contains("1234567890"))
	}

	@Test
	fun `track2 accepts the equals separator as well as D`() {
		assertEquals("411111******1111[len=21]", LogRedact.track2("4111111111111111=2512"))
		assertEquals("411111******1111[len=21]", LogRedact.track2("4111111111111111d2512"))
	}

	@Test
	fun `track2 masks a bare PAN with no separator`() {
		assertEquals("411111******1111[len=16]", LogRedact.track2("4111111111111111"))
	}

	@Test
	fun `track2 preserves length so a size problem is still diagnosable`() {
		// The whole point of these log lines: was the field the size the parser expected?
		val short = LogRedact.track2("4111111111111111D2512")
		val long = LogRedact.track2("4111111111111111D25121011234567890")
		assertTrue(short.endsWith("[len=21]"))
		assertTrue(long.endsWith("[len=34]"))
	}

	@Test
	fun `track2 fully masks a value too short to retain BIN and last four`() {
		// <= 10 digits: keeping 6 + 4 would reveal the whole thing, so mask everything
		assertEquals("**********[len=10]", LogRedact.track2("1234567890"))
		assertEquals("****[len=4]", LogRedact.track2("1234"))
	}

	@Test
	fun `track2 handles null and empty`() {
		assertEquals("<empty>", LogRedact.track2(null))
		assertEquals("<empty>", LogRedact.track2(""))
	}

	// ---------- secret ----------

	@Test
	fun `secret reveals only the length, never any content`() {
		val key = "A1B2C3D4E5F60718"
		val out = LogRedact.secret(key)

		assertEquals("<redacted:16 chars>", out)
		assertFalse(out.contains("A1B2"))
	}

	@Test
	fun `secret handles null and empty`() {
		assertEquals("<empty>", LogRedact.secret(null))
		assertEquals("<empty>", LogRedact.secret(""))
	}

	// ---------- the scrubPans backstop ----------

	@Test
	fun `scrubPans removes a registered PAN from an arbitrary line`() {
		val pan = "5432109876543210"
		LogRedact.registerCardData(pan, null)

		val out = LogRedact.scrubPans("DE002 field -> $pan trailing")

		assertFalse("raw PAN must not survive", out.contains(pan))
		assertTrue(out.contains("543210******3210"))
	}

	@Test
	fun `scrubPans catches the PAN inside an unstructured hex dump`() {
		// This is why scrubbing sits at the sink: nobody enumerates every TLV dump site.
		val pan = "5432109876543210"
		LogRedact.registerCardData(pan, null)

		val out = LogRedact.scrubPans("57135432109876543210D25122011")

		assertFalse(out.contains(pan))
	}

	@Test
	fun `scrubPans removes a registered track2`() {
		val track = "5432109876543210D25122011000000"
		LogRedact.registerCardData(null, track)

		val out = LogRedact.scrubPans("raw=$track")

		assertFalse("raw track2 must not survive", out.contains(track))
		assertTrue(out.contains("543210******3210"))
	}

	@Test
	fun `scrubPans strips non-digits from the registered PAN before matching`() {
		LogRedact.registerCardData("5432-1098-7654-3210", null)

		assertFalse(LogRedact.scrubPans("pan=5432109876543210").contains("5432109876543210"))
	}

	@Test
	fun `scrubPans leaves unrelated diagnostics untouched`() {
		// The earlier Luhn-based approach corrupted these; exact-value matching must not.
		LogRedact.registerCardData("5432109876543210", null)
		val line = "AID=A0000000031010 TVR=0000008000 ARQC=1A2B3C4D5E6F7081 amount=000000001000"

		assertEquals(line, LogRedact.scrubPans(line))
	}

	@Test
	fun `scrubPans is inert before any card is registered`() {
		val line = "pan=5432109876543210"
		assertEquals(line, LogRedact.scrubPans(line))
	}

	@Test
	fun `clearCardData stops the previous card being scrubbed`() {
		val pan = "5432109876543210"
		LogRedact.registerCardData(pan, null)
		assertFalse(LogRedact.scrubPans("pan=$pan").contains(pan))

		wipe()

		// Card data must not outlive the transaction -- after clearing, nothing is registered,
		// so the sink has nothing to match. This documents that clearing is what ends the scope.
		assertTrue(LogRedact.scrubPans("pan=$pan").contains(pan))
	}

	@Test
	fun `registerCardData ignores values too short to be a PAN`() {
		LogRedact.registerCardData("12345678901", null)   // 11 digits, below the 12 minimum

		val line = "pan=12345678901"
		assertEquals("a too-short value must not be registered", line, LogRedact.scrubPans(line))
	}

	// ---------- D11: a stale clear must not wipe a newer transaction's card data ----------

	@Test
	fun `a clear holding an old generation leaves the newer registration intact`() {
		// The sale that just finished registers, and its result screen snapshots the generation.
		val salePan = "4365091500002381"
		LogRedact.registerCardData(salePan, null)
		val resultScreenGeneration = LogRedact.currentGeneration()

		// A void starts before that screen is torn down and registers its own card data.
		val voidPan = "5432109876543210"
		LogRedact.registerCardData(voidPan, null)

		// Now the outgoing result screen reaches onDestroyView. Measured on Pro, this landed
		// 150 ms after the void had registered, and the unguarded clear wiped the void's PAN --
		// so every following void line reached the uploaded log unmasked.
		LogRedact.clearCardData(resultScreenGeneration)

		assertFalse(
			"a stale clear must not disarm the void's registration",
			LogRedact.scrubPans("DF02 :: $voidPan").contains(voidPan)
		)
	}

	@Test
	fun `the owning generation still clears`() {
		LogRedact.registerCardData("4365091500002381", null)
		val mine = LogRedact.currentGeneration()

		LogRedact.clearCardData(mine)

		val line = "DF02 :: 4365091500002381"
		assertEquals("the registration's own generation must clear it", line, LogRedact.scrubPans(line))
	}

	@Test
	fun `each registration gets its own generation`() {
		val first = LogRedact.registerCardData("4365091500002381", null)
		val second = LogRedact.registerCardData("5432109876543210", null)
		assertNotEquals("generations must not repeat", first, second)
		assertEquals("register returns the generation now in force", second, LogRedact.currentGeneration())
	}
}
