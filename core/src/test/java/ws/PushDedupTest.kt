package ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushDedupTest {

	private var clock = 0L
	private val dedup = PushDedup(3_000) { clock }

	private fun push(ref: String, checksum: String = "c1", cmd: String = "UpdatePrice") =
		"""{"Checksum":"$checksum","Command":"$cmd","RequestRef":"$ref","TerminalSN":"X"}"""

	@Test
	fun sameRequestRefWithDifferentBytesIsADuplicate() {
		assertFalse(dedup.isDuplicate(push("1", checksum = "aa")))
		assertTrue(dedup.isDuplicate(push("1", checksum = "bb")))
	}

	@Test
	fun differentRequestRefIsNotADuplicate() {
		assertFalse(dedup.isDuplicate(push("1")))
		assertFalse(dedup.isDuplicate(push("2")))
	}

	@Test
	fun sameRefDifferentCommandIsNotADuplicate() {
		assertFalse(dedup.isDuplicate(push("1", cmd = "UpdatePrice")))
		assertFalse(dedup.isDuplicate(push("1", cmd = "TerminalDMDispense")))
	}

	@Test
	fun repeatAfterTheWindowIsAccepted() {
		assertFalse(dedup.isDuplicate(push("1")))
		clock = 2_999
		assertTrue(dedup.isDuplicate(push("1")))
		clock = 2_999 + 3_000
		assertFalse(dedup.isDuplicate(push("1")))
	}

	@Test
	fun anInterleavedMessageDoesNotHideARepeat() {
		// The old single-slot state forgot A once B arrived.
		assertFalse(dedup.isDuplicate(push("A")))
		assertFalse(dedup.isDuplicate(push("B")))
		assertTrue(dedup.isDuplicate(push("A")))
	}

	@Test
	fun withoutRequestRefTheWholeMessageIsTheKey() {
		assertFalse(dedup.isDuplicate("""{"Command":"UpdatePrice"}"""))
		assertTrue(dedup.isDuplicate("""{"Command":"UpdatePrice"}"""))
		assertFalse(dedup.isDuplicate("not json"))
		assertTrue(dedup.isDuplicate("not json"))
	}

	@Test
	fun doubleEncodedJsonIsUnwrapped() {
		val inner = push("7")
		val wrapped = "\"" + inner.replace("\"", "\\\"") + "\""
		assertEquals(PushDedup.keyOf(inner), PushDedup.keyOf(wrapped))
		assertEquals("UpdatePrice|7", PushDedup.keyOf(inner))
	}
}
