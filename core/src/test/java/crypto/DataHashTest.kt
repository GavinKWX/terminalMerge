package crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Known-answer vectors, so the digest cannot quietly turn back into a constant. */
class DataHashTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun hashHexMatchesKnownVectors() {
		// "abc" = 616263
		assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", DataHash.hashHex("616263", "SHA-1"))
		assertEquals("900150983CD24FB0D6963F7D28E17F72", DataHash.hashHex("616263", "MD5"))
	}

	@Test
	fun hashHexIsNotAConstant() {
		assertNotEquals(DataHash.hashHex("00", "MD5"), DataHash.hashHex("01", "MD5"))
	}

	@Test
	fun hashFileStreamsTheWholeFile() {
		val f = tmp.newFile("a.apk")
		f.writeBytes("abc".toByteArray())
		assertEquals("900150983CD24FB0D6963F7D28E17F72", DataHash.hashFile(f.path, "MD5"))

		// Larger than one 64 KiB buffer, so the loop runs more than once.
		val big = ByteArray(200_000) { (it % 251).toByte() }
		f.writeBytes(big)
		val expected = java.security.MessageDigest.getInstance("MD5").digest(big)
			.joinToString("") { "%02X".format(it) }
		assertEquals(expected, DataHash.hashFile(f.path, "MD5"))
	}

	@Test(expected = java.io.IOException::class)
	fun hashFileOnAMissingFileThrows() {
		DataHash.hashFile(tmp.root.path + "/missing.apk", "MD5")
	}
}
