package iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CurrentTxn] forwards 25 members to the registered [TransactionData] by hand. A delegate that
 * reads or writes the wrong field would compile perfectly and corrupt an ISO message at runtime,
 * so each one is exercised here rather than eyeballed.
 */
class CurrentTxnTest {

	private class Fake : TransactionData {
		override var acqCode = ""
		override var mid = ""
		override var tid = ""
		override var batchNo = ""
		override var stan = ""
		override var invoiceNo = ""
		override var product = ""
		override var schemeTag = ""
		override var schemeType = ""
		override var ksn = ""
		override var pinKsn = ""
		override var dukpt = ByteArray(16)
		override var cashOutAmount = 0L
		override val cashOutAmountAuth = ByteArray(6)
		override var eppTenure = ""
		override var eppTenureCode = ""
		override var respCode = ""
		override var transResult = 0
		override var txnTypeLabel = ""
		override var offlinePinInput = false
		override var onlinePinInput = false
		override val transactionDb = ByteArray(4096)
		override var transactionDbLen = 0

		val calls = mutableListOf<String>()
		override fun addHexStrIntoTransDB(tag: String, strData: String?) {
			calls += "hex:$tag:$strData"
		}
		override fun addHexStrWithPadIntoTransDB(tag: String, strData: String?, padChar: String) {
			calls += "pad:$tag:$strData:$padChar"
		}
		override fun addTlvIntoTransDB(tag: String, tlvData: ByteArray, dataOffSet: Int, tlvLen: Int) {
			calls += "tlv:$tag:${tlvData.size}:$dataOffSet:$tlvLen"
		}
		override fun getFromTransactionDb(tag: String, formatType: Int): String = "$tag/$formatType"
		override fun removeTlvFromTransDb(tag: String) {
			calls += "rm:$tag"
		}
	}

	@Test
	fun `every string property round-trips to its own field`() {
		val f = Fake()
		CurrentTxn.register(f)

		// Distinct values, so a delegate wired to the neighbouring field fails instead of passing.
		val strings: List<Pair<String, Pair<(String) -> Unit, () -> String>>> = listOf(
			"acqCode" to (({ v: String -> CurrentTxn.acqCode = v }) to ({ f.acqCode })),
			"mid" to (({ v: String -> CurrentTxn.mid = v }) to ({ f.mid })),
			"tid" to (({ v: String -> CurrentTxn.tid = v }) to ({ f.tid })),
			"batchNo" to (({ v: String -> CurrentTxn.batchNo = v }) to ({ f.batchNo })),
			// stan and invoiceNo are adjacent counters of the same type -- an easy pair to cross.
			"stan" to (({ v: String -> CurrentTxn.stan = v }) to ({ f.stan })),
			"invoiceNo" to (({ v: String -> CurrentTxn.invoiceNo = v }) to ({ f.invoiceNo })),
			"product" to (({ v: String -> CurrentTxn.product = v }) to ({ f.product })),
			"schemeTag" to (({ v: String -> CurrentTxn.schemeTag = v }) to ({ f.schemeTag })),
			// schemeTag (acquirer routing) and schemeType (card brand) are one letter apart.
			"schemeType" to (({ v: String -> CurrentTxn.schemeType = v }) to ({ f.schemeType })),
			"ksn" to (({ v: String -> CurrentTxn.ksn = v }) to ({ f.ksn })),
			"pinKsn" to (({ v: String -> CurrentTxn.pinKsn = v }) to ({ f.pinKsn })),
			"eppTenure" to (({ v: String -> CurrentTxn.eppTenure = v }) to ({ f.eppTenure })),
			"eppTenureCode" to (({ v: String -> CurrentTxn.eppTenureCode = v }) to ({ f.eppTenureCode })),
			"respCode" to (({ v: String -> CurrentTxn.respCode = v }) to ({ f.respCode })),
			"txnTypeLabel" to (({ v: String -> CurrentTxn.txnTypeLabel = v }) to ({ f.txnTypeLabel })),
		)
		for ((name, io) in strings) {
			val (set, readBack) = io
			set("v-$name")
			assertEquals("$name did not reach its own field", "v-$name", readBack())
		}
		// and reading back through the seam agrees
		assertEquals("v-acqCode", CurrentTxn.acqCode)
		assertEquals("v-respCode", CurrentTxn.respCode)
	}

	@Test
	fun `non-string properties round-trip`() {
		val f = Fake()
		CurrentTxn.register(f)

		CurrentTxn.cashOutAmount = 12345L
		assertEquals(12345L, f.cashOutAmount)

		CurrentTxn.transResult = -7
		assertEquals(-7, f.transResult)

		CurrentTxn.transactionDbLen = 512
		assertEquals(512, f.transactionDbLen)
		assertEquals(512, CurrentTxn.transactionDbLen)

		CurrentTxn.offlinePinInput = true
		CurrentTxn.onlinePinInput = false
		assertTrue("offlinePinInput and onlinePinInput must not be crossed", f.offlinePinInput)
		assertEquals(false, f.onlinePinInput)

		val k = ByteArray(16) { 0x5A }
		CurrentTxn.dukpt = k
		assertSame(k, f.dukpt)
	}

	@Test
	fun `the two buffers are exposed by identity, not copied`() {
		val f = Fake()
		CurrentTxn.register(f)
		// The forming code mutates these in place; a copy would silently discard every write.
		assertSame(f.transactionDb, CurrentTxn.transactionDb)
		assertSame(f.cashOutAmountAuth, CurrentTxn.cashOutAmountAuth)
		assertEquals(4096, CurrentTxn.transactionDb.size)
		assertEquals(6, CurrentTxn.cashOutAmountAuth.size)
	}

	@Test
	fun `functions forward their arguments in order`() {
		val f = Fake()
		CurrentTxn.register(f)

		CurrentTxn.addHexStrIntoTransDB("DF01", "AABB")
		CurrentTxn.addHexStrIntoTransDB("DF02", null)          // the widened signature
		CurrentTxn.addHexStrWithPadIntoTransDB("DF03", "ABC", "0")
		CurrentTxn.addTlvIntoTransDB("DF04", ByteArray(9), 2, 7)
		CurrentTxn.removeTlvFromTransDb("DF05")

		assertEquals(
			listOf("hex:DF01:AABB", "hex:DF02:null", "pad:DF03:ABC:0", "tlv:DF04:9:2:7", "rm:DF05"),
			f.calls
		)
		assertEquals("DF06/16", CurrentTxn.getFromTransactionDb("DF06", 16))
	}

	@Test
	fun `registration is observable`() {
		CurrentTxn.register(Fake())
		assertTrue(CurrentTxn.isRegistered())
	}
}
