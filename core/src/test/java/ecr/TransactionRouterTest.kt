package ecr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for the App-to-App router moved from Pro's intent_helper (audit item 98, phase 1).
 * Each case pins what Pro's code did before the move: the screen and its args, or the answer.
 */
class TransactionRouterTest {

	private class FakeHost : NewIntegrationHost {
		var autoSettle = false
		val flags = mutableMapOf("SALES_CARD" to true, "SALES_EWALLET" to true)
		var clearBatch = false
		/** "PRODUCT" or "PRODUCT|QRCODE" -> row. A lookup without a QR code matches any row of that product. */
		val products = mutableMapOf<String, Any>()
		var epp = false
		var receipt: EnquiryReceipt? = null
		var qr: EnquiryQr? = null
		val saleModels = mutableListOf<Triple<Any, Long?, Int>>()
		val logs = mutableListOf<String>()

		override fun autoSettlementIsRunning() = autoSettle
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
			logs += message
		}
		val gates = mutableMapOf<Destination, enums.EnumResponseCode>()
		override fun gate(destination: Destination) = gates[destination]
	}

	private val host = FakeHost()
	private val router = TransactionRouter(host)
	private val parser = TransactionParser(host)

	private fun req(vararg fields: Pair<String, String>): TxnRequest =
		parser.parseMap(hashMapOf("Package_Name" to "p", "Activity_Name" to "a", *fields)).getOrThrow()

	private fun route(vararg fields: Pair<String, String>) = router.route(req(*fields))

	private fun assertAnswer(code: String, desc: String, r: Route) {
		assertTrue("expected an answer, got $r", r is Route.Return)
		r as Route.Return
		assertEquals(code, r.resultMap["ResponseCode"])
		assertEquals(desc, r.resultMap["ResponseDescription"])
	}

	private fun assertScreen(dest: Destination, args: Map<String, Any?>, r: Route) {
		assertEquals(Route.Navigate(dest, args), r)
	}

	private val noOrdering = mapOf("posReference" to "R1", "orderingItem" to null, "orderingItemImage" to null)

	// ---------- common ----------

	@Test fun `auto settlement running answers SHC002 before anything else`() {
		host.autoSettle = true
		assertAnswer("SHC002", "Auto Settlement is running", route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "CARD"))
	}

	@Test fun `type 0 answers no session, both modes`() {
		assertAnswer("00", "No Session Running", route("TransactionType" to "0"))
		assertAnswer("00", "No Session Running", route("IsOldIntegration" to "true", "TransactionType" to "0"))
	}

	@Test fun `unknown type answers invalid transaction type, both modes`() {
		assertAnswer("SHC001", "Invalid Transaction Type", route("TransactionType" to "9"))
		assertAnswer("SHC001", "Invalid Transaction Type", route("IsOldIntegration" to "true", "TransactionType" to "14"))
		assertAnswer("SHC001", "Invalid Transaction Type", route())
	}

	@Test fun `the answer is the request map with the code added`() {
		val r = route("TransactionType" to "9", "PosReference" to "R1") as Route.Return
		assertEquals("R1", r.resultMap["PosReference"])
		assertEquals("p", r.resultMap["Package_Name"])
	}

	// ---------- sale (new TT2) ----------

	@Test fun `sale amount must be 1 to 999999999 cents`() {
		assertAnswer("SHC001", "Trade amount should be greater than 0", route("TransactionType" to "2", "PaymentChannel" to "CARD"))
		assertAnswer("SHC001", "Trade amount should be greater than 0", route("TransactionType" to "2", "TransactionAmount" to "0", "PaymentChannel" to "CARD"))
		assertAnswer("SHC001", "Trade amount too large", route("TransactionType" to "2", "TransactionAmount" to "1000000000", "PaymentChannel" to "CARD"))
	}

	@Test fun `card sale needs SALES_CARD`() {
		host.flags["SALES_CARD"] = false
		assertAnswer("SHC010", "Transaction Not Supported", route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "CARD"))
	}

	@Test fun `card sale is blocked by an unsettled previous day only when forced settlement is on`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		host.clearBatch = true
		assertTrue(route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "CARD") is Route.Navigate)
		host.flags["FORCE_SETTLEMENT_DAILY"] = true
		assertEquals("SHC011", (route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "CARD") as Route.Return).resultMap["ResponseCode"])
	}

	@Test fun `card sale opens card payment and caches the sale model`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		assertScreen(Destination.CARD_SALE, noOrdering,
			route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "card", "PosReference" to "R1"))
		assertEquals(listOf(Triple<Any, Long?, Int>("cardRow", 50L, 1)), host.saleModels)
	}

	@Test fun `card sale without an active card product answers SHC007`() {
		assertAnswer("SHC007", "Terminal System Error (Product Is Not Configured)",
			route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "CARD"))
	}

	@Test fun `scan sale needs SALES_EWALLET and passes camera facing`() {
		host.products["EWALLET_MERCHANT_SCANS"] = "scanRow"
		host.flags["SALES_EWALLET"] = false
		assertAnswer("SHC010", "Transaction Not Supported", route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "SCAN"))
		host.flags["SALES_EWALLET"] = true
		host.flags["SALES_CARD"] = false // not consulted for e-wallet
		assertScreen(Destination.SCAN_QR, mapOf("posReference" to "R1", "cameraFacing" to 1, "orderingItem" to null, "orderingItemImage" to null),
			route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "SCAN", "PosReference" to "R1", "CameraFacing" to "1"))
		assertEquals(Triple<Any, Long?, Int>("scanRow", 50L, 10), host.saleModels.single())
	}

	@Test fun `scan sale camera facing defaults to 0`() {
		host.products["EWALLET_MERCHANT_SCANS"] = "scanRow"
		val r = route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "SCAN") as Route.Navigate
		assertEquals(0, r.args["cameraFacing"])
	}

	@Test fun `qr sale looks up the product by payment code`() {
		host.products["GENERATE_QR|QR_DUITNOW"] = "duitnowRow"
		assertScreen(Destination.GENERATE_QR, noOrdering,
			route("TransactionType" to "2", "TransactionAmount" to "10", "PaymentChannel" to "QR", "PaymentCode" to "QR_DUITNOW", "PosReference" to "R1"))
		assertEquals(Triple<Any, Long?, Int>("duitnowRow", 10L, 20), host.saleModels.single())
		assertAnswer("SHC007", "Terminal System Error (Product Is Not Configured)",
			route("TransactionType" to "2", "TransactionAmount" to "10", "PaymentChannel" to "QR", "PaymentCode" to "QR_BOOST"))
		assertAnswer("SHC007", "Terminal System Error (Product Is Not Configured)",
			route("TransactionType" to "2", "TransactionAmount" to "10", "PaymentChannel" to "QR"))
	}

	@Test fun `epp sale needs an epp acquirer and passes the amount in cents`() {
		assertAnswer("SHC007", "Terminal System Error (Product Is Not Configured)",
			route("TransactionType" to "2", "TransactionAmount" to "5000", "PaymentChannel" to "EPP"))
		host.epp = true
		assertScreen(Destination.EPP_ACQUIRER, mapOf("txnAmt" to 5000L) + noOrdering,
			route("TransactionType" to "2", "TransactionAmount" to "5000", "PaymentChannel" to "EPP", "PosReference" to "R1"))
		assertTrue(host.saleModels.isEmpty())
	}

	@Test fun `moto sale validates card and expiry, falls back to the card product`() {
		assertAnswer("SHC001", "Invalid Parameter - (CardNumber)", route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "MOTO"))
		assertAnswer("SHC001", "Invalid Parameter - (ExpiryDate)", route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "MOTO", "CardNumber" to "4111"))
		assertAnswer("SHC007", "Terminal System Error (Product Is Not Configured)",
			route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "MOTO", "CardNumber" to "4111", "ExpiryDate" to "2912"))
		host.products["CARD_SETTINGS"] = "cardRow"
		assertScreen(Destination.KEYPAD_MOTO,
			mapOf("txnAmt" to 50L, "cardNumber" to "4111", "expDate" to "2912") + noOrdering,
			route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "MOTO", "CardNumber" to "4111", "ExpiryDate" to "2912", "PosReference" to "R1"))
		assertTrue(host.logs.contains("MOTO merged with CARD"))
		assertTrue(host.saleModels.isEmpty())
	}

	@Test fun `sale with a missing, ALL or unknown channel answers invalid payment channel`() {
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "2", "TransactionAmount" to "50"))
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "ALL"))
		// Used to throw out of parse() (PaymentChannel.valueOf); now answered.
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "BITCOIN"))
	}

	@Test fun `a junk channel or preauth type is rejected on every path, not ignored`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		// Before the move these threw in the parser; ignoring them would run the transaction.
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("IsOldIntegration" to "true", "TransactionType" to "1", "TransactionAmount" to "0.50", "PaymentChannel" to "junk"))
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "1", "PosReference" to "R1", "PaymentChannel" to "CARD "))
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "4", "SettlementType" to "weekly"))
		assertAnswer("SHC001", "Invalid Parameter - (PreAuthType)", route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "CARD", "PreAuthType" to "x"))
		assertAnswer("SHC001", "Invalid Parameter - (PreAuthType)", route("TransactionType" to "5", "PreAuthType" to ""))
		host.flags["SALES_CARD"] = false
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "BITCOIN"))
		assertTrue(host.saleModels.isEmpty())
	}

	@Test fun `an empty PaymentChannel falls back to SettlementType and is not junk`() {
		assertScreen(Destination.SETTLE_OPTION, mapOf("settlementType" to "CARD"), route("TransactionType" to "4", "PaymentChannel" to "", "SettlementType" to "CARD"))
		host.products["CARD_SETTINGS"] = "cardRow"
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to ""))
	}

	@Test fun `an app gate turns a screen into an answer`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		host.gates[Destination.PREAUTH] = enums.EnumResponseCode.TRANSACTION_NOT_SUPPORTED
		assertAnswer("SHC010", "Transaction Not Supported", route("TransactionType" to "5", "PreAuthType" to "PREAUTH", "TransactionAmount" to "1"))
		assertTrue(route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "CARD") is Route.Navigate)
	}

	@Test fun `forceNew ignores the old flag and the amount format`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		val m = hashMapOf("Package_Name" to "p", "Activity_Name" to "a", "IsOldIntegration" to "true", "TransactionType" to "2",
			"TransactionAmount" to "50", "PaymentChannel" to "CARD")
		val r = parser.parseMap(m, forceNew = true).getOrThrow()
		assertEquals(false, r.oldIntegration)
		assertEquals(50L, r.amount)
		assertEquals(Destination.CARD_SALE, (router.route(r) as Route.Navigate).destination)
	}

	@Test fun `ordering items are passed through`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		val r = route("TransactionType" to "2", "TransactionAmount" to "50", "PaymentChannel" to "CARD", "OrderingItem" to "burger", "OrderingItemImage" to "img") as Route.Navigate
		assertEquals("burger", r.args["orderingItem"])
		assertEquals("img", r.args["orderingItemImage"])
	}

	// ---------- void (new TT3) ----------

	@Test fun `void needs an invoice`() {
		assertAnswer("SHC001", "Invalid Parameter - (TransactionInvoice)", route("TransactionType" to "3", "PaymentChannel" to "CARD"))
	}

	@Test fun `card and epp void open void sale with force void`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		val args = mapOf("Invoice" to "000123", "forceVoid" to 1, "posReference" to "R1")
		assertScreen(Destination.VOID_SALE, args,
			route("TransactionType" to "3", "PaymentChannel" to "CARD", "TransactionInvoice" to "000123", "ForceVoid" to "1", "PosReference" to "R1"))
		assertScreen(Destination.VOID_SALE, args,
			route("TransactionType" to "3", "PaymentChannel" to "EPP", "TransactionInvoice" to "000123", "ForceVoid" to "1", "PosReference" to "R1"))
		assertEquals(Triple<Any, Long?, Int>("cardRow", null, 1), host.saleModels.first())
	}

	@Test fun `force void defaults to 0`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		val r = route("TransactionType" to "3", "PaymentChannel" to "CARD", "TransactionInvoice" to "1") as Route.Navigate
		assertEquals(0, r.args["forceVoid"])
	}

	@Test fun `qr void needs either qr product`() {
		assertAnswer("SHC007", "Terminal System Error (Product Is Not Configured)",
			route("TransactionType" to "3", "PaymentChannel" to "QR", "TransactionInvoice" to "REF1"))
		host.products["GENERATE_QR|QR_BOOST"] = "boostRow"
		assertScreen(Destination.VOID_QR, mapOf("Invoice" to "REF1", "forceVoid" to 0, "posReference" to null),
			route("TransactionType" to "3", "PaymentChannel" to "QR", "TransactionInvoice" to "REF1"))
		assertTrue(host.saleModels.isEmpty())
	}

	@Test fun `void on another channel answers invalid payment channel`() {
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "3", "PaymentChannel" to "SCAN", "TransactionInvoice" to "1"))
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "3", "TransactionInvoice" to "1"))
	}

	// ---------- settlement (new TT4) ----------

	@Test fun `settlement opens settle option for ALL, CARD and QR, EPP counts as CARD`() {
		for (t in listOf("ALL", "CARD", "QR")) {
			assertScreen(Destination.SETTLE_OPTION, mapOf("settlementType" to t), route("TransactionType" to "4", "PaymentChannel" to t))
		}
		assertScreen(Destination.SETTLE_OPTION, mapOf("settlementType" to "CARD"), route("TransactionType" to "4", "PaymentChannel" to "EPP"))
	}

	@Test fun `settlement type falls back to SettlementType`() {
		assertScreen(Destination.SETTLE_OPTION, mapOf("settlementType" to "QR"), route("TransactionType" to "4", "SettlementType" to "qr"))
	}

	@Test fun `settlement without a valid type answers invalid payment channel`() {
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "4"))
		assertAnswer("SHC001", "Invalid Parameter - (PaymentChannel)", route("TransactionType" to "4", "PaymentChannel" to "SCAN"))
	}

	// ---------- preauth (new TT5) ----------

	@Test fun `preauth and completion need SALES_CARD, the two voids do not`() {
		host.flags["SALES_CARD"] = false
		assertAnswer("SHC010", "Transaction Not Supported", route("TransactionType" to "5", "PreAuthType" to "PREAUTH", "TransactionAmount" to "50"))
		assertAnswer("SHC010", "Transaction Not Supported", route("TransactionType" to "5", "PreAuthType" to "PREAUTHCOMPLETE"))
		assertTrue(route("TransactionType" to "5", "PreAuthType" to "VOIDPREAUTH", "TransactionInvoice" to "1") is Route.Navigate)
		assertTrue(route("TransactionType" to "5", "PreAuthType" to "VOIDPREAUTHCOMPLETE", "TransactionInvoice" to "1") is Route.Navigate)
	}

	@Test fun `preauth opens card payment with sales type 8`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		assertScreen(Destination.PREAUTH, noOrdering,
			route("TransactionType" to "5", "PreAuthType" to "preauth", "TransactionAmount" to "300", "PosReference" to "R1"))
		assertEquals(Triple<Any, Long?, Int>("cardRow", 300L, 8), host.saleModels.single())
	}

	@Test fun `preauth completion validates invoice, approval code and rrn in that order`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		assertAnswer("SHC001", "Invalid Parameter - (TransactionInvoice)", route("TransactionType" to "5", "PreAuthType" to "PREAUTHCOMPLETE"))
		assertAnswer("SHC001", "Invalid Parameter - (TransactionApprovalCode)", route("TransactionType" to "5", "PreAuthType" to "PREAUTHCOMPLETE", "TransactionInvoice" to "1"))
		assertAnswer("SHC001", "Invalid Parameter - (TransactionRRN)",
			route("TransactionType" to "5", "PreAuthType" to "PREAUTHCOMPLETE", "TransactionInvoice" to "1", "TransactionApprovalCode" to "A"))
		assertScreen(Destination.SALE_COMPLETION, mapOf("apprCode" to "A", "rrn" to "R", "invNo" to "1", "forceVoid" to 0) + noOrdering,
			route("TransactionType" to "5", "PreAuthType" to "PREAUTHCOMPLETE", "TransactionInvoice" to "1", "TransactionApprovalCode" to "A",
				"TransactionRRN" to "R", "TransactionAmount" to "300", "PosReference" to "R1"))
		assertEquals(Triple<Any, Long?, Int>("cardRow", 300L, 8), host.saleModels.single())
	}

	@Test fun `the preauth voids need an invoice`() {
		assertAnswer("SHC001", "Invalid Parameter - (TransactionInvoice)", route("TransactionType" to "5", "PreAuthType" to "VOIDPREAUTH"))
		assertScreen(Destination.VOID_PREAUTH, mapOf("Invoice" to "1", "forceVoid" to 0, "posReference" to null), route("TransactionType" to "5", "PreAuthType" to "VOIDPREAUTH", "TransactionInvoice" to "1"))
		assertScreen(Destination.VOID_SALE_COMPLETION, mapOf("Invoice" to "1", "forceVoid" to 0, "posReference" to null), route("TransactionType" to "5", "PreAuthType" to "VOIDPREAUTHCOMPLETE", "TransactionInvoice" to "1"))
	}

	@Test fun `ForceVoid reaches the completion and both preauth voids`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		assertScreen(Destination.SALE_COMPLETION, mapOf("apprCode" to "A", "rrn" to "R", "invNo" to "1", "forceVoid" to 1) + noOrdering,
			route("TransactionType" to "5", "PreAuthType" to "PREAUTHCOMPLETE", "TransactionInvoice" to "1", "TransactionApprovalCode" to "A",
				"TransactionRRN" to "R", "TransactionAmount" to "300", "PosReference" to "R1", "ForceVoid" to "1"))
		assertScreen(Destination.VOID_PREAUTH, mapOf("Invoice" to "1", "forceVoid" to 1, "posReference" to null),
			route("TransactionType" to "5", "PreAuthType" to "VOIDPREAUTH", "TransactionInvoice" to "1", "ForceVoid" to "1"))
		assertScreen(Destination.VOID_SALE_COMPLETION, mapOf("Invoice" to "1", "forceVoid" to 1, "posReference" to null),
			route("TransactionType" to "5", "PreAuthType" to "VOIDPREAUTHCOMPLETE", "TransactionInvoice" to "1", "ForceVoid" to "1"))
	}

	@Test fun `a missing or unknown preauth type answers invalid preauth type`() {
		assertAnswer("SHC001", "Invalid Parameter - (PreAuthType)", route("TransactionType" to "5"))
		// Used to throw out of parse() (PreAuthType.valueOf); now answered.
		assertAnswer("SHC001", "Invalid Parameter - (PreAuthType)", route("TransactionType" to "5", "PreAuthType" to "REFUND"))
	}

	@Test fun `preauth without a card product answers SHC007`() {
		assertAnswer("SHC007", "Terminal System Error (Product Is Not Configured)", route("TransactionType" to "5", "PreAuthType" to "PREAUTH", "TransactionAmount" to "1"))
	}

	// ---------- enquiry (new TT1) ----------

	@Test fun `enquiry needs a pos reference`() {
		assertAnswer("SHC001", "Invalid Parameter - (PosReference)", route("TransactionType" to "1"))
	}

	@Test fun `enquiry for an unknown pos reference answers SHC008`() {
		assertAnswer("SHC008", "Transaction Not Found", route("TransactionType" to "1", "PosReference" to "R1"))
	}

	@Test fun `card enquiry returns the receipt fields`() {
		host.receipt = EnquiryReceipt(RESP_CODE = "00", TXN_TYPE = "Sale", TXN_AMT = "050", MID = "M", TID = "T", STAN = "001", RRN = "RRN1",
			BATCH_NO = "B", CARD_LABEL = "VISA", CARD_MASKED = "4111******1111", ENTRY_TYPE = "Contactless", ARQC = "AR", TVR = "TV",
			AID = "AID", CVM = "CV", APPR_CODE = "APP", RRN_ORI = "RO", APPR_CODE_ORI = "AO", INV_NO = "000001", SCHEME_ID = "11", TXN_DT = "2026-09-28 10:00:00")
		val m = (route("TransactionType" to "1", "PosReference" to "R1") as Route.Return).resultMap
		assertEquals("00", m["ResponseCode"])
		assertEquals("(00)Approved", m["ResponseDescription"])
		assertEquals("0.50", m["TransactionAmount"])
		assertEquals("Sale", m["TransactionLabel"])
		assertEquals("4111******1111", m["TransactionCardNo"])
		assertEquals("-", m["TransactionTSI"])
		assertEquals("RO", m["OriTransactionRRN"])
		assertEquals("-", m["TransactionEPP"])
		assertEquals("R1", m["PosReference"])
	}

	@Test fun `card enquiry on a TPA product answers the TPA pair, otherwise the acquirer pair`() {
		host.receipt = EnquiryReceipt(RESP_CODE = "00", TXN_AMT = "050", MID = "ACQMID", TID = "ACQTID")
		var m = (route("TransactionType" to "1", "PosReference" to "R1") as Route.Return).resultMap
		assertEquals("ACQMID", m["TransactionMID"])
		assertEquals("ACQTID", m["TransactionTID"])
		assertEquals(listOf<Pair<String?, String?>>("ACQMID" to "ACQTID"), host.tpaAsked)
		host.tpa = "SCMID" to "SCTID"
		m = (route("TransactionType" to "1", "PosReference" to "R1") as Route.Return).resultMap
		assertEquals("SCMID", m["TransactionMID"])
		assertEquals("SCTID", m["TransactionTID"])
	}

	@Test fun `card enquiry with an unknown response code says Failed, and keeps an epp tenure`() {
		host.receipt = EnquiryReceipt(RESP_CODE = "Q9", TXN_AMT = "100", EPP_DETAIL = """{"Tenure":"06"}""")
		val m = (route("TransactionType" to "1", "PosReference" to "R1") as Route.Return).resultMap
		assertEquals("Failed", m["ResponseDescription"])
		assertEquals("""{"Tenure":"06"}""", m["TransactionEPP"])
	}

	@Test fun `qr enquiry returns the qr fields, void uses the void time`() {
		host.receipt = EnquiryReceipt(QrRefId = "REF1")
		host.qr = EnquiryQr(txnType = "Void", txnDateTime = "t1", voidDateTime = "t2", txnAmount = "010", productCode = "QR_DUITNOW",
			productName = "DuitNow", refId = "REF1", hostRefNo = "H1", respCode = "0000", respDesc = "Approved")
		val m = (route("TransactionType" to "1", "PosReference" to "R1") as Route.Return).resultMap
		assertEquals("0000", m["ResponseCode"])
		assertEquals("0.10", m["TransactionAmount"])
		assertEquals("t2", m["TransactionDateTime"])
		assertEquals("DuitNow", m["TransactionEWalletDescription"])
		assertNull(m["TransactionCardNo"])
	}

	@Test fun `qr enquiry with no qr row answers QR transaction not found`() {
		host.receipt = EnquiryReceipt(QrRefId = "REF1")
		assertAnswer("SHC008", "QR Transaction Not Found", route("TransactionType" to "1", "PosReference" to "R1"))
	}

	@Test fun `enquiry on a row with no amount answers invalid transaction type, as before`() {
		host.receipt = EnquiryReceipt(RESP_CODE = "00")
		assertAnswer("SHC001", "Invalid Parameter - (TransactionType)", route("TransactionType" to "1", "PosReference" to "R1"))
	}

	// ---------- old integration mapped onto the use cases ----------

	private fun old(vararg f: Pair<String, String>) = route("IsOldIntegration" to "true", *f)

	@Test fun `old 1 is a card sale, amount in ringgit`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		assertEquals(Destination.CARD_SALE, (old("TransactionType" to "1", "TransactionAmount" to "0.50") as Route.Navigate).destination)
		assertEquals(50L, host.saleModels.single().second)
	}

	@Test fun `old amount format alone selects old integration`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		assertEquals(Destination.CARD_SALE, (route("TransactionType" to "1", "TransactionAmount" to "0.50") as Route.Navigate).destination)
	}

	@Test fun `old 2 and 8 are card and qr voids, 8 takes the ref id`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		host.products["GENERATE_QR|QR_DUITNOW"] = "qrRow"
		assertEquals(Destination.VOID_SALE, (old("TransactionType" to "2", "TransactionInvoice" to "1") as Route.Navigate).destination)
		val r = old("TransactionType" to "8", "TransactionRefId" to "REF9") as Route.Navigate
		assertEquals(Destination.VOID_QR, r.destination)
		assertEquals("REF9", r.args["Invoice"])
	}

	@Test fun `old 3 and 9 are card and qr settlement`() {
		assertScreen(Destination.SETTLE_OPTION, mapOf("settlementType" to "CARD"), old("TransactionType" to "3"))
		assertScreen(Destination.SETTLE_OPTION, mapOf("settlementType" to "QR"), old("TransactionType" to "9"))
	}

	@Test fun `old 4 5 6 10 are the preauth family`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		assertEquals(Destination.PREAUTH, (old("TransactionType" to "4", "TransactionAmount" to "1.00") as Route.Navigate).destination)
		assertEquals(Destination.SALE_COMPLETION, (old("TransactionType" to "5", "TransactionInvoice" to "1", "TransactionApprovalCode" to "A", "TransactionRRN" to "R") as Route.Navigate).destination)
		assertEquals(Destination.VOID_PREAUTH, (old("TransactionType" to "6", "TransactionInvoice" to "1") as Route.Navigate).destination)
		assertEquals(Destination.VOID_SALE_COMPLETION, (old("TransactionType" to "10", "TransactionInvoice" to "1") as Route.Navigate).destination)
	}

	@Test fun `old 7 is qr with a product code, scan without`() {
		host.products["GENERATE_QR|QR_DUITNOW"] = "qrRow"
		host.products["EWALLET_MERCHANT_SCANS"] = "scanRow"
		assertEquals(Destination.GENERATE_QR, (old("TransactionType" to "7", "TransactionAmount" to "0.10", "ProductCode" to "QR_DUITNOW") as Route.Navigate).destination)
		assertEquals(Destination.SCAN_QR, (old("TransactionType" to "7", "TransactionAmount" to "0.10") as Route.Navigate).destination)
	}

	@Test fun `old 11 12 13 are moto, epp and enquiry`() {
		host.products["CARD_SETTINGS"] = "cardRow"
		host.epp = true
		assertEquals(Destination.KEYPAD_MOTO, (old("TransactionType" to "11", "TransactionAmount" to "1.00", "CardNumber" to "4", "ExpiryDate" to "2912") as Route.Navigate).destination)
		assertEquals(Destination.EPP_ACQUIRER, (old("TransactionType" to "12", "TransactionAmount" to "1.00") as Route.Navigate).destination)
		assertAnswer("SHC001", "Invalid Parameter - (PosReference)", old("TransactionType" to "13"))
	}
}
