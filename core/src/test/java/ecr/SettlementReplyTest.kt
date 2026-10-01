package ecr

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import ecr.SettlementReply.QrProductTotals
import ecr.SettlementReply.SummaryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pro's new-integration settlement shape (SettleOptionFragment), reproduced for MF919 (item 98 D1). */
class SettlementReplyTest {

	private val rows = listOf(
		SummaryRow("txnCount", "visam-visa", "3"),
		SummaryRow("txnTotal", "visam-visa", "25700"),
		SummaryRow("voidTxnCount", "visam-visa", "1"),
		SummaryRow("voidTxnTotal", "visam-visa", "60"),
		SummaryRow("txnCount", "visam-upi", "2"),
		SummaryRow("txnTotal", "visam-mccs", "150"),
	)

	@Test fun `card schemes - Visa and Master always, missing values are 0, totals formatted`() {
		assertEquals(
			"""[{"SCHEME":"Visa","SALECOUNT":"3","SALETOTAL":"257.00","VOIDCOUNT":"1","VOIDTOTAL":"0.60"},""" +
				"""{"SCHEME":"Master","SALECOUNT":"0","SALETOTAL":"0","VOIDCOUNT":"0","VOIDTOTAL":"0"}]""",
			SettlementReply.cardSchemes(rows, "PAYDEE", optIn = false).toString())
	}

	@Test fun `UnionPay only for GOBIZ, MyDebit only with OptIn`() {
		val names = { a: JsonArray -> a.map { it.asJsonObject.get("SCHEME").asString } }
		assertEquals(listOf("Visa", "Master", "UnionPay", "MyDebit"), names(SettlementReply.cardSchemes(rows, "gobiz", optIn = true)))
		assertEquals(listOf("Visa", "Master", "MyDebit"), names(SettlementReply.cardSchemes(rows, "PAYDEE", optIn = true)))
		val gobiz = SettlementReply.cardSchemes(rows, "GOBIZ", optIn = false)
		assertEquals("2", gobiz[2].asJsonObject.get("SALECOUNT").asString)
	}

	@Test fun `card entry and wrapper, HTTP form`() {
		val schemes = SettlementReply.cardSchemes(rows, "PAYDEE", false)
		val entry = SettlementReply.cardEntryHttp("2026/09/28 14:00:00", "M1", "T1", "000706", schemes)
		val w = SettlementReply.wrapperHttp(4, "CARD", JsonArray().apply { add(entry) })
		assertEquals(
			"""{"ResponseCode":"00","ResponseDescription":"Settlement","TransactionType":"4","SettlementType":"CARD","SettlementDetail":[""" +
				"""{"TransactionDateTime":"2026/09/28 14:00:00","TransactionMID":"M1","TransactionTID":"T1","TransactionBatchNo":"000706",""" +
				""""ResponseCode":"00","ResponseDescription":"Success","SettlementDetail":${schemes}}]}""",
			w.toString())
	}

	@Test fun `card entry, App-to-App form, success and failure`() {
		val schemes = SettlementReply.cardSchemes(rows, "PAYDEE", false)
		val ok = SettlementReply.cardEntryA2a("d", "M", "T", "B", schemes)
		assertEquals("Success", ok["ResponseDescription"])
		assertEquals(schemes.toString(), ok["SettlementDetail"])
		assertEquals("M", ok["SettlementMID"])
		val fail = SettlementReply.cardEntryA2a("d", "M", "T", "B", null, "96")
		assertEquals("96", fail["ResponseCode"])
		assertEquals("Failed", fail["ResponseDescription"])
		assertEquals(false, fail.containsKey("SettlementDetail"))
	}

	@Test fun `qr products drop empty products and format amounts`() {
		val a = SettlementReply.qrProducts(listOf(
			QrProductTotals("QR_DUITNOW", 1, 10, 2, 200),
			QrProductTotals("QR_BOOST", 0, 0, 0, 0),
			QrProductTotals("QR_TNG", 0, 0, 1, 5),
		))
		assertEquals(
			"""[{"SCHEME":"QR_DUITNOW","SALECOUNT":"1","SALETOTAL":"0.10","VOIDCOUNT":"2","VOIDTOTAL":"2.00"},""" +
				"""{"SCHEME":"QR_TNG","SALECOUNT":"0","SALETOTAL":"0","VOIDCOUNT":"1","VOIDTOTAL":"0.05"}]""",
			a.toString())
	}

	@Test fun `qr entry, HTTP and App-to-App forms`() {
		val p = SettlementReply.qrProducts(listOf(QrProductTotals("QR_DUITNOW", 1, 10, 0, 0)))
		assertEquals(
			"""{"ResponseCode":"00","ResponseDescription":"Success","TransactionDateTime":"d","TransactionMID":"MC","TransactionTID":"-","SettlementDetail":$p}""",
			SettlementReply.qrEntryHttp("d", "MC", p).toString())
		val a2a = SettlementReply.qrEntryA2a("d", "MC", p)
		assertEquals("Settled", a2a["ResponseDescription"])
		assertEquals("-", a2a["SettlementTID"])
		assertEquals(p, JsonParser.parseString(a2a["SettlementDetail"] as String))
	}
}
