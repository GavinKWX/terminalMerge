package helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PublicIp] must always return, and give `0.0.0.0` whenever the lookup has nothing usable. */
class PublicIpTest {

	@Test
	fun returnsTrimmedLookupResult() {
		assertEquals("203.0.113.7", PublicIp.get(1_000) { " 203.0.113.7\n" })
	}

	@Test
	fun failedLookupGivesFallback() {
		assertEquals(PublicIp.FALLBACK, PublicIp.get(1_000) { throw java.io.IOException("down") })
	}

	@Test
	fun emptyOrNullLookupGivesFallback() {
		assertEquals(PublicIp.FALLBACK, PublicIp.get(1_000) { "" })
		assertEquals(PublicIp.FALLBACK, PublicIp.get(1_000) { null })
	}

	@Test
	fun hungLookupReturnsFallbackAfterTheJoinTimeout() {
		val start = System.nanoTime()
		val result = PublicIp.get(200) { Thread.sleep(10_000); "203.0.113.7" }
		val elapsedMs = (System.nanoTime() - start) / 1_000_000
		assertEquals(PublicIp.FALLBACK, result)
		assertTrue("returned after ${elapsedMs}ms", elapsedMs < 2_000)
	}
}
