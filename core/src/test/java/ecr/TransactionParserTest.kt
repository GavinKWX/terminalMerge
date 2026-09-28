package ecr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionParserTest {

	private class QuietHost : NewIntegrationHost {
		override fun autoSettlementIsRunning() = false
		override fun configFlag(name: String) = false
		override fun clearSettlementBatch() = false
		override fun activeProduct(product: String, qrProductCode: String?): Any? = null
		override fun setSaleModel(productRow: Any, amountCents: Long?, salesType: Int) {}
		override fun hasEppAcquirer() = false
		override fun receiptByPosRef(posReference: String): EnquiryReceipt? = null
		override fun qrByRef(refId: String): EnquiryQr? = null
		override fun log(tag: String, message: String) {}
	}

	private val parser = TransactionParser(QuietHost())

	private fun parse(vararg f: Pair<String, String>) =
		parser.parseMap(hashMapOf("Package_Name" to "p", "Activity_Name" to "a", *f)).getOrThrow()

	@Test fun `a missing return target fails`() {
		assertTrue(parser.parseMap(hashMapOf("Package_Name" to "p")).isFailure)
		assertTrue(parser.parseMap(hashMapOf("Activity_Name" to "a")).isFailure)
		assertTrue(parser.parseMap(hashMapOf("Package_Name" to " ", "Activity_Name" to "a")).isFailure)
	}

	@Test fun `new integration takes the amount in cents`() {
		val r = parse("TransactionAmount" to "1000")
		assertFalse(r.oldIntegration)
		assertEquals(1000L, r.amount)
	}

	@Test fun `old integration takes ringgit, by flag or by amount format`() {
		val byFlag = parse("IsOldIntegration" to "true", "TransactionAmount" to "10")
		assertTrue(byFlag.oldIntegration)
		assertEquals(1000L, byFlag.amount)
		val byFormat = parse("TransactionAmount" to "10.50")
		assertTrue(byFormat.oldIntegration)
		assertEquals(1050L, byFormat.amount)
	}

	@Test fun `only a true flag selects old`() {
		assertFalse(parse("IsOldIntegration" to "false").oldIntegration)
		assertFalse(parse("IsOldIntegration" to "1").oldIntegration)
		assertTrue(parse("IsOldIntegration" to " TRUE ").oldIntegration)
	}

	@Test fun `a non-numeric amount is old format with no amount`() {
		val r = parse("TransactionAmount" to "abc")
		assertTrue(r.oldIntegration)
		assertNull(r.amount)
	}

	@Test fun `channel is case-insensitive and falls back to SettlementType`() {
		assertEquals(PaymentChannel.QR, parse("PaymentChannel" to "qr").channel)
		assertEquals(PaymentChannel.ALL, parse("SettlementType" to "All").channel)
		assertEquals(PaymentChannel.CARD, parse("PaymentChannel" to "CARD", "SettlementType" to "QR").channel)
	}

	@Test fun `unknown channel and preauth type are null, not an exception`() {
		assertNull(parse("PaymentChannel" to "BITCOIN").channel)
		assertNull(parse("PreAuthType" to "REFUND").preAuthType)
		assertEquals(PreAuthType.VOIDPREAUTH, parse("PreAuthType" to "voidPreAuth").preAuthType)
	}

	@Test fun `defaults and type`() {
		val r = parse()
		assertEquals(-1, r.txnType)
		assertEquals(0, r.cameraFacing)
		assertEquals(0, r.forceVoid)
		assertEquals(3, parse("TransactionType" to "3").txnType)
		assertEquals(1, parse("ForceVoid" to "1").forceVoid)
	}

	@Test fun `acknowledge countdown is handed back, not applied`() {
		assertFalse(parse().hasAckCountdown)
		val r = parse("AcknowledgeCountdown" to "7")
		assertTrue(r.hasAckCountdown)
		assertEquals("7", r.ackCountdown)
	}
}
