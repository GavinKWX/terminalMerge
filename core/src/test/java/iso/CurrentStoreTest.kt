package iso

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CurrentStore] forwards six methods by hand. Each is checked with distinct arguments so a
 * delegate that swaps `tag` and `subtag`, or drops the criteria map, fails here instead of writing
 * the wrong database row during a transaction.
 */
class CurrentStoreTest {

	private class Fake : TransactionStore {
		val calls = mutableListOf<String>()
		var counter = 0
		var product: AcquirerProduct? = null
		var secure: String? = null
		var stored: ByteArray? = null
		var storedLen = -1

		override fun allocateCounter(context: Context, tag: String, subtag: String): String {
			calls += "alloc:$tag:$subtag"
			return String.format("%06d", ++counter)
		}
		override fun batchInfoValue(context: Context, tag: String, subtag: String): String? {
			calls += "batch:$tag:$subtag"
			return if (tag == "niiTle") "0323" else null
		}
		override fun acquirerProduct(context: Context, mid: String, tid: String): AcquirerProduct? {
			calls += "product:$mid:$tid"
			return product
		}
		override fun updateProduct(
			context: Context,
			values: Map<String, String>,
			criteria: Map<String, String>,
		): Boolean {
			calls += "update:$values:$criteria"
			return true
		}
		override fun secureValue(context: Context, tag: String, subtag: String): String? {
			calls += "secure:$tag:$subtag"
			return secure
		}
		override fun storeSecureValue(
			context: Context,
			tag: String,
			subtag: String,
			data: ByteArray,
			dataLen: Int,
		) {
			calls += "store:$tag:$subtag:${data.size}:$dataLen"
			stored = data
			storedLen = dataLen
		}
	}

	// The seam never dereferences the Context, it only passes it along -- so a bare
	// ContextWrapper is enough. It is constructible here because unitTests.isReturnDefaultValues
	// is on for :core.
	private fun c(): Context = ContextWrapper(null)

	@Test
	fun `counters forward tag and subtag in order`() {
		val f = Fake()
		CurrentStore.register(f)
		assertEquals("000001", CurrentStore.allocateCounter(c(), "stan", "visam"))
		assertEquals("000002", CurrentStore.allocateCounter(c(), "invoiceNo", "pos"))
		assertEquals(listOf("alloc:stan:visam", "alloc:invoiceNo:pos"), f.calls)
	}

	@Test
	fun `batch info passes through, including its null`() {
		val f = Fake()
		CurrentStore.register(f)
		assertEquals("0323", CurrentStore.batchInfoValue(c(), "niiTle", "visam"))
		// An absent row must stay null: the forming code skips the field rather than writing "".
		assertNull(CurrentStore.batchInfoValue(c(), "isoTpduHeaderTle", "visam"))
	}

	@Test
	fun `acquirer product round-trips every field`() {
		val f = Fake()
		f.product = AcquirerProduct(
			acqCode = "BSN_CARDZONE",
			product = "CARD",
			acqMid = "011110000000014",
			acqTid = "40000014",
			ksn = "00000126109821000001",
			pinKsn = "00000126109821000002",
		)
		CurrentStore.register(f)

		val p = CurrentStore.acquirerProduct(c(), "011110000000014", "40000014")!!
		assertEquals("BSN_CARDZONE", p.acqCode)
		assertEquals("CARD", p.product)
		assertEquals("011110000000014", p.acqMid)
		assertEquals("40000014", p.acqTid)
		assertEquals("00000126109821000001", p.ksn)
		// ksn and pinKsn are the pair most likely to be crossed by a bad delegate.
		assertEquals("00000126109821000002", p.pinKsn)
		assertEquals(listOf("product:011110000000014:40000014"), f.calls)
	}

	@Test
	fun `update carries both maps, in the right roles`() {
		val f = Fake()
		CurrentStore.register(f)
		val ok = CurrentStore.updateProduct(
			c(),
			values = mapOf("Ksn" to "NEWKSN"),
			criteria = mapOf("AcqMid" to "M1", "AcqTid" to "T1"),
		)
		assertTrue(ok)
		assertEquals(
			listOf("update:{Ksn=NEWKSN}:{AcqMid=M1, AcqTid=T1}"),
			f.calls
		)
	}

	@Test
	fun `secure values forward both ways without copying the buffer`() {
		val f = Fake()
		f.secure = "AABBCCDD"
		CurrentStore.register(f)

		assertEquals("AABBCCDD", CurrentStore.secureValue(c(), "eWakKey", "visam-tleBsn-011110000000014"))

		val key = ByteArray(16) { it.toByte() }
		CurrentStore.storeSecureValue(c(), "eWekKey", "visam", key, key.size)
		assertArrayEquals(key, f.stored)
		assertEquals(16, f.storedLen)
		assertEquals(
			listOf("secure:eWakKey:visam-tleBsn-011110000000014", "store:eWekKey:visam:16:16"),
			f.calls
		)
	}

	@Test
	fun `registration is observable`() {
		CurrentStore.register(Fake())
		assertTrue(CurrentStore.isRegistered())
	}
}
