package ecr

import com.google.gson.JsonObject
import data_enum.CardErrorDataEnum
import enums.EnumResponseCode
import utils.AmountFormat

/** What the HTTP/WS/cable new-integration handler needs beyond [NewIntegrationHost]. */
interface HttpSurface {
    fun setAppHttp(on: Boolean)
    fun setTxnType(txnType: Int)
    fun setAckCountdown(seconds: Int)
    fun wakeScreen()
    fun toast(message: String)
    /** HTTPServer.setResponseMessage. */
    fun reply(json: String)
    /** A reply has already been set for this request (HTTPServer.responseMsg != null). */
    fun hasReply(): Boolean
    /** HTTPServer.defaultError("", 1). */
    fun defaultError()
    fun navigate(destination: Destination, args: Map<String, Any?>)
}

/**
 * New-integration requests over HTTP, WebSocket and cable. Moved from Pro's
 * `HTTPServer.newIntegrationType` (audit item 98, phase 2), line for line: HTTP answers differ from
 * the App-to-App use cases in about 20 places and are kept exactly (D4). Pinned by
 * `HttpNewIntegrationTest`. Validation failures reply, then throw to the outer catch, as before.
 */
class HttpNewIntegration(
    private val host: NewIntegrationHost,
    private val http: HttpSurface,
) {
    fun handle(requestJson: JsonObject) {
        try {
            http.setAppHttp(true)
            var transType = 99
            if (requestJson.has("TransactionType")) {
                transType = requestJson.get("TransactionType").asInt
            }
            val resultObject = requestJson.deepCopy().asJsonObject
            http.setTxnType(transType)

            // Wake the screen only when a transaction flow is about to start (not for enquiry/status)
            if (transType in 2..5) {
                http.wakeScreen()
            }

            var posReference: String? = null
            if (requestJson.has("PosReference")) {
                posReference = requestJson.get("PosReference").asString
            }

            var orderingItemImage: String? = null
            if (requestJson.has("OrderingItemImage")) {
                orderingItemImage = requestJson.get("OrderingItemImage").asString
            }
            var orderingItem: String? = null
            if (requestJson.has("OrderingItem") && orderingItemImage.isNullOrEmpty()) {
                orderingItem = requestJson.get("OrderingItem").asString
            }

            if (requestJson.has("AcknowledgeCountdown")) {
                http.setAckCountdown(requestJson.get("AcknowledgeCountdown").asInt)
            }

            when (transType) {
                0 -> {
                    resultObject.addProperty("ResponseCode", "00")
                    resultObject.addProperty("ResponseDescription", "No Session Running")
                    http.reply(resultObject.toString())
                }
                1 -> enquiry(posReference, resultObject)
                2 -> sale(requestJson, resultObject, posReference, orderingItem, orderingItemImage)
                3 -> void(requestJson, resultObject, posReference)
                4 -> settlement(requestJson, resultObject)
                5 -> preAuth(requestJson, resultObject, posReference, orderingItem, orderingItemImage)
                else -> {
                    http.toast("Invalid Transaction Type")
                    resultObject.addProperty("ResponseCode", EnumResponseCode.INVALID_TRANSACTION_TYPE.code)
                    resultObject.addProperty("ResponseDescription", EnumResponseCode.INVALID_TRANSACTION_TYPE.description)
                    http.reply(resultObject.toString())
                }
            }
        } catch (ex: Exception) {
            http.setAppHttp(false)
            ex.printStackTrace()
            if (!http.hasReply()) {
                http.defaultError()
            }
        }
    }

    private fun enquiry(posReference: String?, resultObject: JsonObject) {
        posReference?.let {
            val receipt = host.receiptByPosRef(posReference)
            if (receipt != null) {
                var desc = "Failed"
                try {
                    val formedEnumTag = "TAG_${receipt.RESP_CODE}"
                    desc = "(" + receipt.RESP_CODE + ")" + CardErrorDataEnum.valueOf(formedEnumTag).data
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                if (receipt.QrRefId?.trim()?.isNotEmpty() == true) {
                    val qr = host.qrByRef(receipt.QrRefId)
                    qr?.let {
                        val transactionDateTime = if (qr.txnType?.trim().equals("Void", true)) {
                            qr.voidDateTime
                        } else {
                            qr.txnDateTime
                        }

                        resultObject.addProperty("ResponseCode", qr.respCode)
                        resultObject.addProperty("ResponseDescription", qr.respDesc)
                        resultObject.addProperty("TransactionLabel", qr.txnType)
                        resultObject.addProperty("TransactionAmount", AmountFormat.getActualAmount(qr.txnAmount!!))
                        resultObject.addProperty("TransactionId", qr.hostRefNo)
                        resultObject.addProperty("TransactionRefId", qr.refId)
                        resultObject.addProperty("TransactionEWallet", qr.productCode)
                        resultObject.addProperty("TransactionEWalletDescription", qr.productName)
                        resultObject.addProperty("TransactionDateTime", transactionDateTime)
                    } ?: run {
                        resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_FOUND.code)
                        resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_FOUND.description)
                    }
                } else {
                    resultObject.addProperty("ResponseCode", receipt.RESP_CODE)
                    resultObject.addProperty("ResponseDescription", desc)
                    resultObject.addProperty("TransactionLabel", receipt.TXN_TYPE)
                    resultObject.addProperty("TransactionAmount", AmountFormat.getActualAmount(receipt.TXN_AMT!!))
                    resultObject.addProperty("TransactionMID", receipt.MID)
                    resultObject.addProperty("TransactionTID", receipt.TID)
                    resultObject.addProperty("TransactionSTN", receipt.STAN)
                    resultObject.addProperty("TransactionRRN", receipt.RRN)
                    resultObject.addProperty("TransactionBatchNo", receipt.BATCH_NO)
                    resultObject.addProperty("TransactionApplicationLabel", receipt.CARD_LABEL)
                    resultObject.addProperty("TransactionCardNo", receipt.CARD_MASKED)
                    resultObject.addProperty("TransactionEntryType", receipt.ENTRY_TYPE)
                    resultObject.addProperty("TransactionARQC", receipt.ARQC)
                    resultObject.addProperty("TransactionTVR", receipt.TVR)
                    resultObject.addProperty("TransactionAID", receipt.AID)
                    resultObject.addProperty("TransactionCVM", receipt.CVM)
                    resultObject.addProperty("TransactionApprovalCode", receipt.APPR_CODE)
                    resultObject.addProperty("TransactionInvoice", receipt.INV_NO)
                    resultObject.addProperty("TransactionSchemeID", receipt.SCHEME_ID)
                    resultObject.addProperty("TransactionDateTime", receipt.TXN_DT)
                    resultObject.addProperty("TransactionEPP", "-")
                }
            } else {
                resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_FOUND.code)
                resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_FOUND.description)
            }
            http.reply(resultObject.toString())
        } ?: run {
            http.toast("Invalid Parameter - (PosReference)")
            resultObject.addProperty("ResponseCode", "SHC001")
            resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PosReference)")
            http.reply(resultObject.toString())
        }
    }

    private fun sale(requestJson: JsonObject, resultObject: JsonObject, posReference: String?, orderingItem: String?, orderingItemImage: String?) {
        var txnAmount: Long = 0
        if (requestJson.has("TransactionAmount")) {
            var toastMessage = "Invalid Amount"
            try {
                txnAmount = (requestJson.get("TransactionAmount").asNumber).toLong()
                val amountLimit = 999999999
                if (txnAmount > amountLimit) {
                    toastMessage = "Trade amount should be less than 999999.99"
                    throw Exception()
                } else if (txnAmount <= 0) {
                    toastMessage = "Trade amount should be greater than 0"
                    throw Exception()
                }
            } catch (_: Exception) {
                http.toast(toastMessage)
                resultObject.addProperty("ResponseCode", "SHC001")
                resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                http.reply(resultObject.toString())
                throw Exception()
            }
        } else {
            resultObject.addProperty("ResponseCode", "SHC001")
            resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
            http.reply(resultObject.toString())
            throw Exception()
        }

        val paymentChannel: String
        if (requestJson.has("PaymentChannel")) {
            paymentChannel = requestJson.get("PaymentChannel").asString
        } else {
            resultObject.addProperty("ResponseCode", "SHC001")
            resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PaymentChannel)")
            http.reply(resultObject.toString())
            throw Exception()
        }

        // Case-sensitive on purpose: a lower-case "qr" is gated as card, as before (item 98 quirk list).
        val qrList = listOf("SCAN", "QR")
        var isCard = false
        val isEnableSales = if (paymentChannel in qrList) {
            host.configFlag("SALES_EWALLET")
        } else {
            isCard = true
            host.configFlag("SALES_CARD")
        }
        if (!isEnableSales) {
            resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
            resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
            http.reply(resultObject.toString())
            throw Exception()
        }

        if (isCard) {
            if (host.configFlag("FORCE_SETTLEMENT") || host.configFlag("FORCE_SETTLEMENT_DAILY")) {
                if (host.clearSettlementBatch()) {
                    http.toast("Please Run Settlement for Last day Transaction before Proceed")
                    resultObject.addProperty("ResponseCode", EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.code)
                    resultObject.addProperty("ResponseDescription", EnumResponseCode.SETTLE_PREVIOUS_DAY_FIRST.description)
                    http.reply(resultObject.toString())
                    return
                }
            }
        }

        when (paymentChannel.trim().uppercase()) {
            "CARD" -> {
                host.activeProduct(Products.CARD_SETTINGS)?.let { row ->
                    host.setSaleModel(row, txnAmount, Products.SALES_TYPE_CARD)
                    http.navigate(Destination.CARD_SALE,
                        mapOf("posReference" to posReference, "orderingItem" to orderingItem, "orderingItemImage" to orderingItemImage))
                } ?: productNotConfigured(resultObject)
            }
            "SCAN" -> {
                var cameraFacing: Int = 1
                if (requestJson.has("CameraFacing")) {
                    val tempCamera = requestJson.get("CameraFacing").asInt
                    if (tempCamera == 1 || tempCamera == 0) {
                        cameraFacing = tempCamera
                    }
                }

                host.activeProduct(Products.EWALLET_MERCHANT_SCANS)?.let { row ->
                    host.setSaleModel(row, txnAmount, Products.SALES_TYPE_EWALLET_SCAN)
                    http.navigate(Destination.SCAN_QR,
                        mapOf("posReference" to posReference, "cameraFacing" to cameraFacing, "orderingItem" to orderingItem, "orderingItemImage" to orderingItemImage))
                } ?: productNotConfigured(resultObject)
            }
            "QR" -> {
                val paymentCode: String
                if (requestJson.has("PaymentCode")) {
                    paymentCode = requestJson.get("PaymentCode").asString.trim().uppercase()
                } else {
                    resultObject.addProperty("ResponseCode", "SHC001")
                    resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PaymentCode)")
                    http.reply(resultObject.toString())
                    throw Exception()
                }

                host.activeProduct(Products.GENERATE_QR, paymentCode)?.let { row ->
                    host.setSaleModel(row, txnAmount, Products.SALES_TYPE_GENERATE_QR)
                    http.navigate(Destination.GENERATE_QR,
                        mapOf("posReference" to posReference, "orderingItem" to orderingItem, "orderingItemImage" to orderingItemImage))
                } ?: productNotConfigured(resultObject)
            }
            "EPP" -> {
                if (host.hasEppAcquirer()) {
                    http.navigate(Destination.EPP_ACQUIRER,
                        mapOf("txnAmt" to txnAmount, "posReference" to posReference, "orderingItem" to orderingItem, "orderingItemImage" to orderingItemImage))
                } else {
                    productNotConfigured(resultObject)
                }
            }
            "MOTO" -> {
                if (!requestJson.has("CardNumber")) {
                    http.toast("Invalid Parameter - (CardNumber)")
                    resultObject.addProperty("ResponseCode", "SHC001")
                    resultObject.addProperty("ResponseDescription", "Invalid Parameter - (CardNumber)")
                    http.reply(resultObject.toString())
                    throw Exception()
                }
                val cardNumber: String = requestJson.get("CardNumber").asString

                if (!requestJson.has("ExpiryDate")) {
                    http.toast("Invalid Parameter - (ExpiryDate)")
                    resultObject.addProperty("ResponseCode", "SHC001")
                    resultObject.addProperty("ResponseDescription", "Invalid Parameter - (ExpiryDate)")
                    http.reply(resultObject.toString())
                    throw Exception()
                }
                val expDate: String = requestJson.get("ExpiryDate").asString

                if (!requestJson.has("TransactionAmount")) {
                    http.toast("Invalid Parameter - (TransactionAmount)")
                    resultObject.addProperty("ResponseCode", "SHC001")
                    resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionAmount)")
                    http.reply(resultObject.toString())
                    throw Exception()
                }

                try {
                    var row = host.activeProduct(Products.MOTO)
                    if (row == null) {
                        //Handle for MOTO merged with CARD
                        host.log(MOTO_TAG, "MOTO merged with CARD")
                        row = host.activeProduct(Products.CARD_SETTINGS)
                    }

                    row?.let {
                        http.navigate(Destination.KEYPAD_MOTO,
                            mapOf(
                                "txnAmt" to txnAmount,
                                "cardNumber" to cardNumber,
                                "expDate" to expDate,
                                "posReference" to posReference,
                                "orderingItem" to orderingItem,
                                "orderingItemImage" to orderingItemImage
                            ))
                    } ?: productNotConfigured(resultObject)
                } catch (_: Exception) {
                    http.toast("System Error")
                    resultObject.addProperty("ResponseCode", EnumResponseCode.TERMINAL_SYSTEM_ERROR.code)
                    resultObject.addProperty("ResponseDescription", EnumResponseCode.TERMINAL_SYSTEM_ERROR.description)
                    http.reply(resultObject.toString())
                    return
                }
            }
            else -> invalidPaymentChannel(resultObject)
        }
    }

    private fun void(requestJson: JsonObject, resultObject: JsonObject, posReference: String?) {
        val txnInvoice: String
        if (requestJson.has("TransactionInvoice")) {
            txnInvoice = requestJson.get("TransactionInvoice").asString
        } else {
            resultObject.addProperty("ResponseCode", "SHC001")
            resultObject.addProperty("ResponseDescription", "Invalid Parameter - (TransactionInvoice)")
            http.reply(resultObject.toString())
            throw Exception()
        }

        var forceVoid = 0
        if (requestJson.has("ForceVoid")) {
            forceVoid = requestJson.get("ForceVoid").asInt
        }

        val paymentChannel: String
        if (requestJson.has("PaymentChannel")) {
            paymentChannel = requestJson.get("PaymentChannel").asString
        } else {
            resultObject.addProperty("ResponseCode", "SHC001")
            resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PaymentChannel)")
            http.reply(resultObject.toString())
            throw Exception()
        }

        when (paymentChannel.trim().uppercase()) {
            "CARD", "EPP" -> {
                host.activeProduct(Products.CARD_SETTINGS)?.let { row ->
                    host.setSaleModel(row, null, Products.SALES_TYPE_CARD)
                    http.navigate(Destination.VOID_SALE,
                        mapOf("Invoice" to txnInvoice, "forceVoid" to forceVoid, "posReference" to posReference))
                } ?: productNotConfigured(resultObject)
            }
            "QR" -> {
                val scan = host.activeProduct(Products.EWALLET_MERCHANT_SCANS)
                val genQr = host.activeProduct(Products.GENERATE_QR)
                if (scan != null || genQr != null) {
                    http.navigate(Destination.VOID_QR,
                        mapOf("Invoice" to txnInvoice, "forceVoid" to forceVoid, "posReference" to posReference))
                } else {
                    productNotConfigured(resultObject)
                }
            }
            else -> invalidPaymentChannel(resultObject)
        }
    }

    private fun settlement(requestJson: JsonObject, resultObject: JsonObject) {
        // The chosen value keeps its original case, as before: a lower-case "all" reaches
        // SettleOptionFragment as "all" (item 98 quirk list).
        val settlementType: String
        val key = when {
            requestJson.has("PaymentChannel") -> "PaymentChannel"
            requestJson.has("SettlementType") -> "SettlementType"
            else -> null
        }
        if (key == null) {
            invalidSettlementType(resultObject)
        }
        val tempType = if (requestJson.get(key).asString == "EPP") "CARD" else requestJson.get(key).asString
        if (listOf("ALL", "CARD", "QR").contains(tempType.trim().uppercase())) {
            settlementType = tempType
        } else {
            invalidSettlementType(resultObject)
        }

        http.navigate(Destination.SETTLE_OPTION, mapOf("settlementType" to settlementType))
    }

    private fun preAuth(requestJson: JsonObject, resultObject: JsonObject, posReference: String?, orderingItem: String?, orderingItemImage: String?) {
        if (!requestJson.has("PreAuthType")) {
            sendInvalidParameterResponse("PreAuthType")
        }

        val row = host.activeProduct(Products.CARD_SETTINGS)
        if (row == null) {
            http.toast("System Error")
            resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
            resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
            http.reply(resultObject.toString())
            throw Exception()
        }

        val preAuthType: String = requestJson.get("PreAuthType").asString
        when (preAuthType.trim().uppercase()) {
            "PREAUTH", "PREAUTHCOMPLETE" -> {
                if (!host.configFlag("SALES_CARD")) {
                    resultObject.addProperty("ResponseCode", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.code)
                    resultObject.addProperty("ResponseDescription", EnumResponseCode.TRANSACTION_NOT_SUPPORTED.description)
                    http.reply(resultObject.toString())
                    throw Exception()
                }

                val txnAmount = validateTransactionAmount(
                    requestJson = requestJson,
                    allowZero = preAuthType.trim().uppercase() == "PREAUTHCOMPLETE",
                )
                host.setSaleModel(row, txnAmount, Products.SALES_TYPE_PREAUTH)

                when (preAuthType.trim().uppercase()) {
                    "PREAUTH" -> {
                        http.navigate(Destination.PREAUTH,
                            mapOf("posReference" to posReference, "orderingItem" to orderingItem, "orderingItemImage" to orderingItemImage))
                    }
                    "PREAUTHCOMPLETE" -> {
                        if (!requestJson.has("TransactionApprovalCode")) {
                            http.toast("Invalid Parameter - (TransactionApprovalCode)")
                            sendInvalidParameterResponse("TransactionApprovalCode")
                        }
                        if (!requestJson.has("TransactionRRN")) {
                            http.toast("Invalid Parameter - (TransactionRRN)")
                            sendInvalidParameterResponse("TransactionRRN")
                        }
                        if (!requestJson.has("TransactionInvoice")) {
                            http.toast("Invalid Parameter - (TransactionInvoice)")
                            sendInvalidParameterResponse("TransactionInvoice")
                        }

                        http.navigate(Destination.SALE_COMPLETION,
                            mapOf(
                                "apprCode" to requestJson.get("TransactionApprovalCode").asString,
                                "rrn" to requestJson.get("TransactionRRN").asString,
                                "invNo" to requestJson.get("TransactionInvoice").asString,
                                "posReference" to posReference,
                                "orderingItem" to orderingItem,
                                "orderingItemImage" to orderingItemImage
                            ))
                    }
                }
            }
            "VOIDPREAUTH", "VOIDPREAUTHCOMPLETE" -> {
                if (!requestJson.has("TransactionInvoice")) {
                    http.toast("Invalid Parameter - (TransactionInvoice)")
                    sendInvalidParameterResponse("TransactionInvoice")
                }
                val txnInvoice: String = requestJson.get("TransactionInvoice").asString
                host.setSaleModel(row, null, Products.SALES_TYPE_PREAUTH)

                when (preAuthType.trim().uppercase()) {
                    "VOIDPREAUTH" -> http.navigate(Destination.VOID_PREAUTH,
                        mapOf("Invoice" to txnInvoice, "posReference" to posReference))
                    "VOIDPREAUTHCOMPLETE" -> http.navigate(Destination.VOID_SALE_COMPLETION,
                        mapOf("Invoice" to txnInvoice, "posReference" to posReference))
                }
            }
            else -> sendInvalidParameterResponse("PreAuthType")
        }
    }

    private fun productNotConfigured(resultObject: JsonObject) {
        http.toast("System Error")
        resultObject.addProperty("ResponseCode", EnumResponseCode.PRODUCT_NOT_CONFIGURED.code)
        resultObject.addProperty("ResponseDescription", EnumResponseCode.PRODUCT_NOT_CONFIGURED.description)
        http.reply(resultObject.toString())
    }

    private fun invalidPaymentChannel(resultObject: JsonObject) {
        http.toast("Invalid Payment Channel")
        resultObject.addProperty("ResponseCode", EnumResponseCode.INVALID_PAYMENT_CHANNEL.code)
        resultObject.addProperty("ResponseDescription", EnumResponseCode.INVALID_PAYMENT_CHANNEL.description)
        http.reply(resultObject.toString())
    }

    private fun invalidSettlementType(resultObject: JsonObject): Nothing {
        resultObject.addProperty("ResponseCode", "SHC001")
        resultObject.addProperty("ResponseDescription", "Invalid Parameter - (PaymentChannel)")
        http.reply(resultObject.toString())
        throw Exception()
    }

    // As before, including the quirk that the inner throw is caught here and the reply is sent twice.
    private fun validateTransactionAmount(requestJson: JsonObject, allowZero: Boolean = false): Long {
        if (!requestJson.has("TransactionAmount")) {
            sendInvalidParameterResponse("TransactionAmount")
        }

        var result: Long = -1
        try {
            val amount = requestJson.get("TransactionAmount").asNumber.toLong()
            val amountLimit = 999999999

            when {
                amount > amountLimit -> {
                    http.toast("Trade amount should be less than 999999.99")
                    sendInvalidParameterResponse("TransactionAmount")
                }

                amount < 0 || (!allowZero && amount == 0L) -> {
                    val message = if (allowZero) "Trade amount should be greater than or equal to 0"
                    else "Trade amount should be greater than 0"
                    http.toast(message)
                    sendInvalidParameterResponse("TransactionAmount")
                }

                else -> result = amount
            }
        } catch (_: Exception) {
            http.toast("Invalid Amount")
            sendInvalidParameterResponse("TransactionAmount")
        }
        return result
    }

    /** A fresh object, not the request echo, as before. Always throws. */
    private fun sendInvalidParameterResponse(paramName: String): Nothing {
        val resultObject = JsonObject()
        resultObject.addProperty("ResponseCode", "SHC001")
        resultObject.addProperty("ResponseDescription", "Invalid Parameter - ($paramName)")
        http.reply(resultObject.toString())
        throw Exception()
    }

    private companion object {
        const val MOTO_TAG = "HTTPSERVER" // HTTPServer.TAG
    }
}
