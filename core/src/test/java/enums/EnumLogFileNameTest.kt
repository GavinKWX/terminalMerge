package enums

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the log-retention contract that used to live in two hand-synchronised copies.
 *
 * Until Phase 1b this enum was duplicated as `helper_common.HelperLogFileName`, and each
 * copy's comment claimed to be the source of truth while telling you to keep the other in
 * step. `AsyncLogWriter.maxBackupsFor` reads `maximumFile` from this one, so these values
 * decide how much history survives rotation -- and the duplicate is now a typealias onto it.
 *
 * `id` is deliberately asserted too: it is the on-disk filename stem, so changing it
 * silently orphans every existing log file and anything on the TMS side that looks for them.
 */
class EnumLogFileNameTest {

	@Test
	fun `file stems are the on-disk contract`() {
		assertEquals("TerminalLog", EnumLogFileName.TerminaLog.id)
		assertEquals("TerminalLog_Exception", EnumLogFileName.TerminaLogException.id)
		assertEquals("TerminalDb_Exception", EnumLogFileName.TerminaDbException.id)
	}

	@Test
	fun `retention counts match the agreed 10-1-1`() {
		// TerminaLog is 10 rather than 5 because per-line logging roughly doubled its volume;
		// 10 backups keeps about the same wall-clock history 5 covered before that change.
		assertEquals(10, EnumLogFileName.TerminaLog.maximumFile)
		assertEquals(1, EnumLogFileName.TerminaLogException.maximumFile)
		assertEquals(1, EnumLogFileName.TerminaDbException.maximumFile)
	}

	@Test
	fun `the three streams are the whole set`() {
		// A fourth stream needs a retention decision and a rotation review, not a silent addition.
		assertEquals(3, EnumLogFileName.entries.size)
	}
}
