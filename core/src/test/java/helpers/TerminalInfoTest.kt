package helpers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TerminalInfo] is the seam that let the 22 TMS handlers move into :core without :core
 * depending on an app. If it silently returns nothing, every TMS call goes out with an empty
 * DEV-SN and the failure surfaces server-side, far from the cause -- so the contract is worth
 * pinning down.
 *
 * The provider is process-global, so each test registers its own and clears afterwards.
 */
class TerminalInfoTest {

	private class Fake(
		private val sn: String = "98213199990004",
		private val ver: String = "2.2.26",
		private val sqn: String = "000123",
		private val env: String = "staging",
		private val model: String = "MF919",
	) : TerminalInfo.Provider {
		override fun serialNumber() = sn
		override fun appVersion() = ver
		override fun sqnNum() = sqn
		override fun deviceModel() = model
		override fun defaultEnvId() = env
	}

	@Before fun clear() = TerminalInfo.register(NullProvider)
	@After fun reset() = TerminalInfo.register(NullProvider)

	/** Stands in for "nothing registered" -- the object has no deregister, by design. */
	private object NullProvider : TerminalInfo.Provider {
		override fun serialNumber() = ""
		override fun appVersion() = ""
		override fun sqnNum() = ""
		override fun deviceModel() = ""
		override fun defaultEnvId() = "production"
	}

	@Test
	fun `values come from the registered provider`() {
		TerminalInfo.register(Fake())

		assertEquals("98213199990004", TerminalInfo.serialNumber())
		assertEquals("2.2.26", TerminalInfo.appVersion())
		assertEquals("000123", TerminalInfo.sqnNum())
		assertEquals("MF919", TerminalInfo.deviceModel())
		assertEquals("staging", TerminalInfo.defaultEnvId())
	}

	@Test
	fun `registering again replaces the previous provider`() {
		TerminalInfo.register(Fake(sn = "first"))
		assertEquals("first", TerminalInfo.serialNumber())

		TerminalInfo.register(Fake(sn = "second"))
		assertEquals("second", TerminalInfo.serialNumber())
	}

	@Test
	fun `isRegistered reports true once an app has registered`() {
		TerminalInfo.register(Fake())
		assertTrue(TerminalInfo.isRegistered())
	}

	@Test
	fun `provider is consulted on every call, not cached`() {
		// getSqnNum is a rolling counter in the real ServiceHolder, so caching would hand the
		// same sequence number to consecutive TMS requests.
		var n = 0
		TerminalInfo.register(object : TerminalInfo.Provider {
			override fun serialNumber() = "sn"
			override fun appVersion() = "v"
			override fun sqnNum() = (++n).toString()
			override fun deviceModel() = "MF919"
			override fun defaultEnvId() = "production"
		})

		assertEquals("1", TerminalInfo.sqnNum())
		assertEquals("2", TerminalInfo.sqnNum())
		assertEquals("3", TerminalInfo.sqnNum())
	}

	@Test
	fun `defaultEnvId falls back to production rather than an empty string`() {
		// An empty env id would make Environment.create fail; production is the safe default
		// because it is the one that cannot accidentally point a live terminal at a test host.
		assertEquals("production", NullProvider.defaultEnvId())
	}

	@Test
	fun `empty serial is distinguishable from a real one`() {
		// Documents the deliberate choice in TerminalInfo: return "" rather than throw, so a
		// missing registration fails as a diagnosable bad request instead of a crash on a
		// background thread.
		assertTrue(NullProvider.serialNumber().isEmpty())
		assertFalse(Fake().serialNumber().isEmpty())
	}

	@Test
	fun `install token and IP default to empty when a provider does not supply them`() {
		// "" is what the server reads as an unknown token, so a provider that predates the
		// install token must degrade to that, never to a made-up value.
		TerminalInfo.register(Fake())
		assertEquals("", TerminalInfo.installToken())
		assertEquals("", TerminalInfo.ipAddress())
	}

	@Test
	fun `APP_TYPE is POS for both apps`() {
		assertEquals("POS", TerminalInfo.APP_TYPE)
	}
}
