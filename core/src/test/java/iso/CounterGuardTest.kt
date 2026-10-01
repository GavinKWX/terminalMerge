package iso

import iso.CounterGuard.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** The restore decision: a rewind to near the asset seed is restored, a 999999 wrap is not. */
class CounterGuardTest {

	@Test fun `a counter at or above its mark is kept`() {
		assertEquals(Action.KEEP, CounterGuard.actionFor(mark = 1500, current = 1500))
		assertEquals(Action.KEEP, CounterGuard.actionFor(mark = 1500, current = 1600))
	}

	@Test fun `a counter below its mark is restored`() {
		assertEquals(Action.RESTORE, CounterGuard.actionFor(mark = 1500, current = 1))
		assertEquals(Action.RESTORE, CounterGuard.actionFor(mark = 1500, current = 1499))
		assertEquals(Action.RESTORE, CounterGuard.actionFor(mark = 950000, current = 5000))
	}

	@Test fun `a high mark with a counter near the seed is a wrap, not a rewind`() {
		assertEquals(Action.WRAP, CounterGuard.actionFor(mark = 999999, current = 1))
		assertEquals(Action.WRAP, CounterGuard.actionFor(mark = 900001, current = 999))
	}

	@Test fun `the wrap window has exact edges`() {
		assertEquals(Action.RESTORE, CounterGuard.actionFor(mark = 900000, current = 1))
		assertEquals(Action.RESTORE, CounterGuard.actionFor(mark = 999999, current = 1000))
	}
}
