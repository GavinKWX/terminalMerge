package ecr

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for new integration over HTTP/WS/cable, moved from Pro's
 * `HTTPServer.newIntegrationType` (audit item 98, phase 2). Each case pins what that code did,
 * including the quirks kept by D4 (marked QUIRK).
 */
class HttpNewIntegrationTest {

	private class FakeHost : NewIntegrationHost {
		val flags = mutableMapOf("SALES_CARD" to true, "SALES_EWALLET" to true)
		var clearBatch = false
		val products = mutableMapOf<String, Any>()
		var epp = false
		var receipt: EnquiryReceipt? = null
		var qr: EnquiryQr? = null
		val saleModels = mutableListOf<Triple<Any, Long?, Int>>()
		val logs = mutableListOf<String>()

		override fun autoSettlementIsRunning() = false
		override fun configFlag(name: String) = flags[name] ?: false
		override fun clearSettlementBatch() = clearBatch
		override fun activeProduct(product: String, qrProductCode: String?): Any? =
			if (qrProductCode == null) products.entries.firstOrNull { it.key.substringBefore('|') == product }?.value
			else products["$product|$qrProductCode"]
		override fun setSaleModel(productRow: Any, amountCents: Long?, salesType: Int) {
			saleModels += Triple(productRow, amountCents, salesType)
		}
		override fun hasEppAcquirer() = epp
		override fun receiptByPosRef(posReference: String) = receipt
		override fun qrByRef(refId: String) = qr
		var tpa: Pair<String, String>? = null
		val tpaAsked = mutableListOf<Pair<String?, String?>>()
		override fun tpaMidTid(acqMid: String?, acqTid: String?): Pair<String, String>? {
			tpaAsked += acqMid to acqTid
			return tpa
		}
		override fun log(tag: String, message: String) {
			logs += "$tag:$message"
		}
		val gates = mutableMapOf<Destination, enums.EnumResponseCode>()
		override fun gate(destination: Destination) = gates[destination]
	}

	private class FakeHttp : HttpSurface {
		var appHttp: Boolean? = null
		var txnType: Int? = null
		var ack: Int? = null
		var woke = false
		val toasts = mutableListOf<String>()
		val replies = mutableListOf<String>()
		var defaultErrors = 0
		val navs = mutableListOf<Pair<Destination, Map<String, Any?>>>()

		override fun setAppHttp(on: Boolean) { appHttp = on }
		override fun setTxnType(txnType: Int) { this.txnType = txnType }
		override fun setAckCountdown(seconds: Int) { ack = seconds }
		override fun wakeScreen() { woke = true }
		override fun toast(message: String) { toasts += message }
		override fun reply(json: String) { replies += json }
		override fun hasReply() = replies.isNotEmpty()
		override fun defaultError() { defaultErrors++ }
		override fun navigate(destination: Destination, args: Map<String, Any?>) { navs += destination to args }
	}

	private val host = FakeHost()
	private val http = FakeHttp()
	private val handler = HttpNewIntegration(host, http)

	private fun send(json: String) = handler.handle(JsonParser.parseString(json).asJsonObject)

	private fun reply(): JsonObject = JsonParser.parseString(http.replies.single()).asJsonObject

	private fun assertReply(code: String, desc: String) {
		val r = reply()
		assertEquals(code, r.get("ResponseCode").asString)
		assertEquals(desc, r.get("ResponseDescription").asString)
	}

	private fun assertNav(dest: Destination, args: Map<String, Any?>) {
		assertEquals(listOf(dest to args), http.navs)
		assertTrue("no reply expected, got ${http.replies}", http.replies.isEmpty())
	}

	private val noOrdering = mapOf("posReference" to null, "orderingItem" to null, "orderingItemImage" to null)

	// ---------- common ----------

	@Test fun `type 0 echoes the request with its JSON types and says no session`() {
		send("""{"TransactionType":0,"TransactionAmount":50,"PosReference":"R1"}""")
		assertEquals("""{"TransactionType":0,"TransactionAmount":50,"PosReference":"R1","ResponseCode":"00","ResponseDescription":"No Session Running"}""", http.replies.single())
		assertEquals(true, http.appHttp)
		assertEquals(0, http.txnType)
		assertFalse(http.woke)
	}

	@Test fun `no type is 99 and answers invalid transaction type with a toast`() {
		send("""{"PosReference":"R1"}""")
		assertEquals(99, http.txnType)
		assertReply("SHC001", "Invalid Transaction Type")
		assertEquals(listOf("Invalid Transaction Type"), http.toasts)
	}

	@Test fun `a non-numeric type falls to the default error, not a reply`() {
		send("""{"TransactionType":"abc"}""")
		assertTrue(http.replies.isEmpty())
		assertEquals(1, http.defaultErrors)
		assertEquals(false, http.appHttp)
	}

	@Test fun `types 2 to 5 wake the screen, 0 and 1 do not`() {
		send("""{"TransactionType":1}"""); assertFalse(http.woke)
		send("""{"TransactionType":4,"PaymentChannel":"CARD"}"""); assertTrue(http.woke)
	}

	@Test fun `acknowledge countdown is applied as an int`() {
		send("""{"TransactionType":0,"AcknowledgeCountdown":7}""")
		assertEquals(7, http.ack)
	}

	@Test fun `ordering item is dropped when an image is sent`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"CARD","OrderingItem":"burger","OrderingItemImage":"img"}""")
		assertNull(http.navs.single().second["orderingItem"])
		assertEquals("img", http.navs.single().second["orderingItemImage"])
		http.navs.clear()
		send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"CARD","OrderingItem":"burger"}""")
		assertEquals("burger", http.navs.single().second["orderingItem"])
	}

	// ---------- enquiry (TT1) ----------

	@Test fun `enquiry without a pos reference toasts and answers SHC001`() {
		send("""{"TransactionType":1}""")
		assertReply("SHC001", "Invalid Parameter - (PosReference)")
		assertEquals(listOf("Invalid Parameter - (PosReference)"), http.toasts)
	}

	@Test fun `enquiry not found`() {
		send("""{"TransactionType":1,"PosReference":"R1"}""")
		assertReply("SHC008", "Transaction Not Found")
	}

	@Test fun `card enquiry fields - no Ori or TSI fields, EPP always dash, nulls as JSON null`() {
		host.receipt = EnquiryReceipt(RESP_CODE = "00", TXN_TYPE = "Sale", TXN_AMT = "050", STAN = "001", INV_NO = "000001", EPP_DETAIL = """{"Tenure":"06"}""", RRN_ORI = "RO")
		send("""{"TransactionType":1,"PosReference":"R1"}""")
		val r = reply()
		assertEquals("(00)Approved", r.get("ResponseDescription").asString)
		assertEquals("0.50", r.get("TransactionAmount").asString)
		assertEquals("-", r.get("TransactionEPP").asString)
		assertFalse(r.has("OriTransactionRRN"))
		assertFalse(r.has("TransactionTSI"))
		assertTrue(r.get("TransactionMID").isJsonNull)
		assertEquals("R1", r.get("PosReference").asString)
	}

	@Test fun `card enquiry on a TPA product answers the TPA pair, otherwise the acquirer pair`() {
		host.receipt = EnquiryReceipt(RESP_CODE = "00", TXN_TYPE = "Sale", TXN_AMT = "050", MID = "ACQMID", TID = "ACQTID")
		send("""{"TransactionType":1,"PosReference":"R1"}""")
		assertEquals("ACQMID", reply().get("TransactionMID").asString)
		assertEquals("ACQTID", reply().get("TransactionTID").asString)
		http.replies.clear()
		host.tpa = "SCMID" to "SCTID"
		send("""{"TransactionType":1,"PosReference":"R1"}""")
		assertEquals("SCMID", reply().get("TransactionMID").asString)
		assertEquals("SCTID", reply().get("TransactionTID").asString)
	}

	@Test fun `QUIRK qr enquiry with no qr row says Transaction Not Found, not QR Transaction Not Found`() {
		host.receipt = EnquiryReceipt(QrRefId = "REF1")
		send("""{"TransactionType":1,"PosReference":"R1"}""")
		assertReply("SHC008", "Transaction Not Found")
	}

	@Test fun `qr enquiry fields`() {
		host.receipt = EnquiryReceipt(QrRefId = "REF1")
		host.qr = EnquiryQr(txnType = "Sale", txnDateTime = "t1", voidDateTime = "t2", txnAmount = "010", productCode = "QR_DUITNOW",
			productName = "DuitNow", refId = "REF1", hostRefNo = "H1", respCode = "0000", respDesc = "Approved")
		send("""{"TransactionType":1,"PosReference":"R1"}""")
		val r = reply()
		assertEquals("0000", r.get("ResponseCode").asString)
		assertEquals("0.10", r.get("TransactionAmount").asString)
		assertEquals("t1", r.get("TransactionDateTime").asString)
		assertEquals("DuitNow", r.get("TransactionEWalletDescription").asString)
	}

	@Test fun `enquiry on a row with no amount falls to the default error`() {
		host.receipt = EnquiryReceipt(RESP_CODE = "00")
		send("""{"TransactionType":1,"PosReference":"R1"}""")
		assertTrue(http.replies.isEmpty())
		assertEquals(1, http.defaultErrors)
	}

	// ---------- sale (TT2) ----------

	@Test fun `sale amount errors are all SHC001 TransactionAmount, with different toasts`() {
		for ((body, toast) in listOf(
			"""{"TransactionType":2,"TransactionAmount":0,"PaymentChannel":"CARD"}""" to "Trade amount should be greater than 0",
			"""{"TransactionType":2,"TransactionAmount":1000000000,"PaymentChannel":"CARD"}""" to "Trade amount should be less than 999999.99",
			"""{"TransactionType":2,"TransactionAmount":"abc","PaymentChannel":"CARD"}""" to "Invalid Amount",
		)) {
			http.replies.clear(); http.toasts.clear()
			send(body)
			assertReply("SHC001", "Invalid Parameter - (TransactionAmount)")
			assertEquals(listOf(toast), http.toasts)
			assertEquals(false, http.appHttp)
		}
		http.replies.clear(); http.toasts.clear()
		send("""{"TransactionType":2,"PaymentChannel":"CARD"}""")
		assertReply("SHC001", "Invalid Parameter - (TransactionAmount)")
		assertTrue(http.toasts.isEmpty())
	}

	@Test fun `a missing channel is rejected before the config gate`() {
		host.flags["SALES_CARD"] = false
		send("""{"TransactionType":2,"TransactionAmount":50}""")
		assertReply("SHC001", "Invalid Parameter - (PaymentChannel)")
	}

	@Test fun `QUIRK a lower-case qr is gated as card`() {
		host.flags["SALES_CARD"] = false
		send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"qr","PaymentCode":"QR_DUITNOW"}""")
		assertReply("SHC010", "Transaction Not Supported")
	}

	@Test fun `forced settlement pending answers SHC011 and leaves appHttp on`() {
		host.flags["FORCE_SETTLEMENT"] = true
		host.clearBatch = true
		send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"CARD"}""")
		assertEquals("SHC011", reply().get("ResponseCode").asString)
		assertEquals(listOf("Please Run Settlement for Last day Transaction before Proceed"), http.toasts)
		assertEquals(true, http.appHttp)
	}

	@Test fun `card sale navigates and caches the sale model`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":" card ","PosReference":"R1"}""")
		assertNav(Destination.CARD_SALE, noOrdering + ("posReference" to "R1"))
		assertEquals(Triple<Any, Long?, Int>("cardRow", 50L, 1), host.saleModels.single())
	}

	@Test fun `a missing product toasts System Error, answers SHC007 and leaves appHttp on`() {
		send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"CARD"}""")
		assertReply("SHC007", "Terminal System Error (Product Is Not Configured)")
		assertEquals(listOf("System Error"), http.toasts)
		assertEquals(true, http.appHttp)
	}

	@Test fun `scan camera defaults to 1 and only accepts 0 or 1`() {
		host.products["EWALLET_MERCHANT_SCANS"] = "scanRow"
		for ((body, cam) in listOf("" to 1, ""","CameraFacing":0""" to 0, ""","CameraFacing":5""" to 1)) {
			http.navs.clear()
			send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"SCAN"$body}""")
			assertEquals(cam, http.navs.single().second["cameraFacing"])
		}
		assertEquals(10, host.saleModels.first().third)
	}

	@Test fun `qr sale needs a payment code, trimmed and upper-cased`() {
		send("""{"TransactionType":2,"TransactionAmount":10,"PaymentChannel":"QR"}""")
		assertReply("SHC001", "Invalid Parameter - (PaymentCode)")
		http.replies.clear()
		host.products["GENERATE_QR|QR_DUITNOW"] = "duitnowRow"
		send("""{"TransactionType":2,"TransactionAmount":10,"PaymentChannel":"QR","PaymentCode":" qr_duitnow "}""")
		assertNav(Destination.GENERATE_QR, noOrdering)
		assertEquals(Triple<Any, Long?, Int>("duitnowRow", 10L, 20), host.saleModels.single())
	}

	@Test fun `epp sale`() {
		send("""{"TransactionType":2,"TransactionAmount":5000,"PaymentChannel":"EPP"}""")
		assertReply("SHC007", "Terminal System Error (Product Is Not Configured)")
		http.replies.clear()
		host.epp = true
		send("""{"TransactionType":2,"TransactionAmount":5000,"PaymentChannel":"EPP"}""")
		assertNav(Destination.EPP_ACQUIRER, mapOf("txnAmt" to 5000L) + noOrdering)
	}

	@Test fun `moto checks presence only, falls back to card`() {
		send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"MOTO"}""")
		assertReply("SHC001", "Invalid Parameter - (CardNumber)")
		http.replies.clear()
		send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"MOTO","CardNumber":"4111"}""")
		assertReply("SHC001", "Invalid Parameter - (ExpiryDate)")
		http.replies.clear()
		host.products["CARD_SETTINGS"] = "cardRow"
		// QUIRK: an empty CardNumber is accepted.
		send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"MOTO","CardNumber":"","ExpiryDate":"2912"}""")
		assertNav(Destination.KEYPAD_MOTO, mapOf("txnAmt" to 50L, "cardNumber" to "", "expDate" to "2912") + noOrdering)
		assertTrue(host.logs.contains("HTTPSERVER:MOTO merged with CARD"))
		assertTrue(host.saleModels.isEmpty())
	}

	@Test fun `ALL or unknown sale channel answers Invalid Payment Channel`() {
		for (ch in listOf("ALL", "BITCOIN")) {
			http.replies.clear(); http.toasts.clear()
			send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"$ch"}""")
			assertReply("SHC001", "Invalid Payment Channel")
			assertEquals(listOf("Invalid Payment Channel"), http.toasts)
		}
	}

	// ---------- void (TT3) ----------

	@Test fun `void needs invoice then channel`() {
		send("""{"TransactionType":3,"PaymentChannel":"CARD"}""")
		assertReply("SHC001", "Invalid Parameter - (TransactionInvoice)")
		http.replies.clear()
		send("""{"TransactionType":3,"TransactionInvoice":"1"}""")
		assertReply("SHC001", "Invalid Parameter - (PaymentChannel)")
	}

	@Test fun `card and epp void, QUIRK an empty invoice is accepted`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		send("""{"TransactionType":3,"PaymentChannel":"epp","TransactionInvoice":"","ForceVoid":1,"PosReference":"R1"}""")
		assertNav(Destination.VOID_SALE, mapOf("Invoice" to "", "forceVoid" to 1, "posReference" to "R1"))
		assertEquals(Triple<Any, Long?, Int>("cardRow", null, 1), host.saleModels.single())
	}

	@Test fun `qr void and unknown channel`() {
		host.products["EWALLET_MERCHANT_SCANS"] = "scanRow"
		send("""{"TransactionType":3,"PaymentChannel":"QR","TransactionInvoice":"REF1"}""")
		assertNav(Destination.VOID_QR, mapOf("Invoice" to "REF1", "forceVoid" to 0, "posReference" to null))
		http.navs.clear()
		send("""{"TransactionType":3,"PaymentChannel":"SCAN","TransactionInvoice":"1"}""")
		assertReply("SHC001", "Invalid Payment Channel")
	}

	// ---------- settlement (TT4) ----------

	@Test fun `QUIRK settlement type keeps its case`() {
		send("""{"TransactionType":4,"PaymentChannel":"all"}""")
		assertNav(Destination.SETTLE_OPTION, mapOf("settlementType" to "all"))
	}

	@Test fun `settlement EPP becomes CARD only in upper case, SettlementType is the fallback`() {
		send("""{"TransactionType":4,"PaymentChannel":"EPP"}""")
		assertNav(Destination.SETTLE_OPTION, mapOf("settlementType" to "CARD"))
		http.navs.clear()
		send("""{"TransactionType":4,"SettlementType":"QR"}""")
		assertNav(Destination.SETTLE_OPTION, mapOf("settlementType" to "QR"))
		http.navs.clear()
		// QUIRK: a lower-case "epp" is rejected.
		send("""{"TransactionType":4,"PaymentChannel":"epp"}""")
		assertReply("SHC001", "Invalid Parameter - (PaymentChannel)")
	}

	@Test fun `settlement without or with a bad type`() {
		send("""{"TransactionType":4}""")
		assertReply("SHC001", "Invalid Parameter - (PaymentChannel)")
		http.replies.clear()
		send("""{"TransactionType":4,"SettlementType":"SCAN"}""")
		assertReply("SHC001", "Invalid Parameter - (PaymentChannel)")
	}

	// ---------- preauth (TT5) ----------

	@Test fun `QUIRK preauth errors are a fresh object, not the request echo`() {
		send("""{"TransactionType":5,"PosReference":"R1"}""")
		assertEquals("""{"ResponseCode":"SHC001","ResponseDescription":"Invalid Parameter - (PreAuthType)"}""", http.replies.single())
	}

	@Test fun `QUIRK every preauth type needs the card product first`() {
		send("""{"TransactionType":5,"PreAuthType":"VOIDPREAUTH","TransactionInvoice":"1"}""")
		assertReply("SHC007", "Terminal System Error (Product Is Not Configured)")
		assertEquals(false, http.appHttp)
	}

	@Test fun `preauth needs SALES_CARD`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		host.flags["SALES_CARD"] = false
		send("""{"TransactionType":5,"PreAuthType":"PREAUTH","TransactionAmount":100}""")
		assertReply("SHC010", "Transaction Not Supported")
	}

	@Test fun `preauth navigates with sales type 8`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		send("""{"TransactionType":5,"PreAuthType":"preauth","TransactionAmount":300,"PosReference":"R1"}""")
		assertNav(Destination.PREAUTH, noOrdering + ("posReference" to "R1"))
		assertEquals(Triple<Any, Long?, Int>("cardRow", 300L, 8), host.saleModels.single())
	}

	@Test fun `QUIRK a zero preauth amount toasts twice and replies twice`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		send("""{"TransactionType":5,"PreAuthType":"PREAUTH","TransactionAmount":0}""")
		assertEquals(listOf("Trade amount should be greater than 0", "Invalid Amount"), http.toasts)
		assertEquals(2, http.replies.size)
		assertTrue(http.replies.all { it == """{"ResponseCode":"SHC001","ResponseDescription":"Invalid Parameter - (TransactionAmount)"}""" })
		assertTrue(host.saleModels.isEmpty())
	}

	@Test fun `preauth missing amount replies once`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		send("""{"TransactionType":5,"PreAuthType":"PREAUTH"}""")
		assertEquals(listOf("""{"ResponseCode":"SHC001","ResponseDescription":"Invalid Parameter - (TransactionAmount)"}"""), http.replies)
		assertTrue(http.toasts.isEmpty())
	}

	@Test fun `completion allows zero and checks approval code, rrn, invoice in that order, after caching the model`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		send("""{"TransactionType":5,"PreAuthType":"PREAUTHCOMPLETE","TransactionAmount":0}""")
		assertEquals("""{"ResponseCode":"SHC001","ResponseDescription":"Invalid Parameter - (TransactionApprovalCode)"}""", http.replies.single())
		assertEquals(Triple<Any, Long?, Int>("cardRow", 0L, 8), host.saleModels.single())
		http.replies.clear()
		send("""{"TransactionType":5,"PreAuthType":"PREAUTHCOMPLETE","TransactionAmount":0,"TransactionApprovalCode":"A"}""")
		assertEquals("Invalid Parameter - (TransactionRRN)", reply().get("ResponseDescription").asString)
		http.replies.clear()
		send("""{"TransactionType":5,"PreAuthType":"PREAUTHCOMPLETE","TransactionAmount":0,"TransactionApprovalCode":"A","TransactionRRN":"R"}""")
		assertEquals("Invalid Parameter - (TransactionInvoice)", reply().get("ResponseDescription").asString)
		http.replies.clear()
		send("""{"TransactionType":5,"PreAuthType":"PREAUTHCOMPLETE","TransactionAmount":0,"TransactionApprovalCode":"A","TransactionRRN":"R","TransactionInvoice":"1"}""")
		assertNav(Destination.SALE_COMPLETION, mapOf("apprCode" to "A", "rrn" to "R", "invNo" to "1", "forceVoid" to 0) + noOrdering)
	}

	@Test fun `preauth voids cache the model without an amount`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		send("""{"TransactionType":5,"PreAuthType":"VOIDPREAUTH"}""")
		assertEquals("""{"ResponseCode":"SHC001","ResponseDescription":"Invalid Parameter - (TransactionInvoice)"}""", http.replies.single())
		http.replies.clear()
		send("""{"TransactionType":5,"PreAuthType":"VOIDPREAUTHCOMPLETE","TransactionInvoice":"9"}""")
		assertNav(Destination.VOID_SALE_COMPLETION, mapOf("Invoice" to "9", "forceVoid" to 0, "posReference" to null))
		assertEquals(Triple<Any, Long?, Int>("cardRow", null, 8), host.saleModels.single())
	}

	@Test fun `ForceVoid reaches the completion and both preauth voids`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		send("""{"TransactionType":5,"PreAuthType":"PREAUTHCOMPLETE","TransactionAmount":0,"TransactionApprovalCode":"A","TransactionRRN":"R","TransactionInvoice":"1","ForceVoid":1}""")
		assertNav(Destination.SALE_COMPLETION, mapOf("apprCode" to "A", "rrn" to "R", "invNo" to "1", "forceVoid" to 1) + noOrdering)
		http.navs.clear()
		send("""{"TransactionType":5,"PreAuthType":"VOIDPREAUTH","TransactionInvoice":"2","ForceVoid":1}""")
		assertNav(Destination.VOID_PREAUTH, mapOf("Invoice" to "2", "forceVoid" to 1, "posReference" to null))
		http.navs.clear()
		send("""{"TransactionType":5,"PreAuthType":"VOIDPREAUTHCOMPLETE","TransactionInvoice":"3","ForceVoid":1}""")
		assertNav(Destination.VOID_SALE_COMPLETION, mapOf("Invoice" to "3", "forceVoid" to 1, "posReference" to null))
	}

	@Test fun `an app gate replies once, clears appHttp and opens nothing, even on the MOTO path`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		host.gates[Destination.KEYPAD_MOTO] = enums.EnumResponseCode.TRANSACTION_NOT_SUPPORTED
		send("""{"TransactionType":2,"TransactionAmount":50,"PaymentChannel":"MOTO","CardNumber":"4","ExpiryDate":"2912"}""")
		assertReply("SHC010", "Transaction Not Supported")
		assertTrue(http.navs.isEmpty())
		assertEquals(false, http.appHttp)
		assertEquals(0, http.defaultErrors)
	}

	@Test fun `unknown preauth type`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		send("""{"TransactionType":5,"PreAuthType":"REFUND"}""")
		assertEquals("""{"ResponseCode":"SHC001","ResponseDescription":"Invalid Parameter - (PreAuthType)"}""", http.replies.single())
	}
}
