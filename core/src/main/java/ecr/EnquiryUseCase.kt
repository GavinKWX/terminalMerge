package ecr

import com.google.gson.Gson
import data_enum.CardErrorDataEnum
import enums.EnumResponseCode
import tms.models.EppDetail
import utils.AmountFormat

class EnquiryUseCase(private val host: NewIntegrationHost = CurrentNewIntegrationHost) {
    fun buildRoute(req: TxnRequest): Route {
        val txn = req.raw
        val posReference = req.posReference
        if (posReference.isNullOrEmpty()) {
            txn[TxnKeys.RESP_CODE] = "SHC001"
            txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (PosReference)"
            return Route.Return(txn)
        }

        return try {
            val receipt = host.receiptByPosRef(posReference)

            // logcat only, as before: this is receipt data and must not reach the uploaded file log.
            println("dbModelReceiptUpload :: ${Gson().toJson(receipt)}")
            if (receipt != null) {
                if (receipt.QrRefId?.trim()?.isNotEmpty() == true) {
                    val qr = host.qrByRef(receipt.QrRefId)
                    qr?.let {
                        val transactionDateTime = if (qr.txnType?.trim().equals("Void", true)) {
                            qr.voidDateTime
                        } else {
                            qr.txnDateTime
                        }

                        txn[TxnKeys.RESP_CODE] = qr.respCode ?: ""
                        txn[TxnKeys.RESP_DESC] = qr.respDesc ?: ""
                        txn["TransactionLabel"] = qr.txnType ?: ""
                        txn["TransactionAmount"] = AmountFormat.getActualAmount(qr.txnAmount!!)
                        txn["TransactionId"] = qr.hostRefNo ?: ""
                        txn["TransactionRefId"] = qr.refId ?: ""
                        txn["TransactionEWallet"] = qr.productCode ?: ""
                        txn["TransactionEWalletDescription"] = qr.productName ?: ""
                        txn["TransactionDateTime"] = transactionDateTime ?: ""
                        Route.Return(txn)
                    } ?: run {
                        txn[TxnKeys.RESP_CODE] = EnumResponseCode.QR_TRANSACTION_NOT_FOUND.code
                        txn[TxnKeys.RESP_DESC] = EnumResponseCode.QR_TRANSACTION_NOT_FOUND.description
                        Route.Return(txn)
                    }
                } else {
                    var desc = "Failed"
                    try {
                        val formedEnumTag = "TAG_${receipt.RESP_CODE}"
                        desc = "(" + receipt.RESP_CODE + ")" + CardErrorDataEnum.valueOf(formedEnumTag).data
                    } catch (e: Exception) {
                        // Unknown code: keep "Failed".
                    }

                    txn[TxnKeys.RESP_CODE] = receipt.RESP_CODE ?: ""
                    txn[TxnKeys.RESP_DESC] = desc
                    txn["TransactionLabel"] = receipt.TXN_TYPE ?: ""
                    txn["TransactionAmount"] = AmountFormat.getActualAmount(receipt.TXN_AMT!!)
                    txn["TransactionMID"] = receipt.MID ?: ""
                    txn["TransactionTID"] = receipt.TID ?: ""
                    txn["TransactionSTN"] = receipt.STAN ?: ""
                    txn["TransactionRRN"] = receipt.RRN ?: ""
                    txn["TransactionBatchNo"] = receipt.BATCH_NO ?: ""
                    txn["TransactionApplicationLabel"] = receipt.CARD_LABEL ?: ""
                    txn["TransactionCardNo"] = receipt.CARD_MASKED ?: ""
                    txn["TransactionEntryType"] = receipt.ENTRY_TYPE ?: ""
                    txn["TransactionARQC"] = receipt.ARQC ?: ""
                    txn["TransactionTVR"] = receipt.TVR ?: ""
                    txn["TransactionAID"] = receipt.AID ?: ""
                    txn["TransactionCVM"] = receipt.CVM ?: ""
                    txn["TransactionTSI"] = "-"
                    txn["TransactionApprovalCode"] = receipt.APPR_CODE ?: ""
                    txn["OriTransactionRRN"] = receipt.RRN_ORI ?: ""
                    txn["OriTransactionApprovalCode"] = receipt.APPR_CODE_ORI ?: ""
                    txn["TransactionInvoice"] = receipt.INV_NO ?: ""
                    txn["TransactionSchemeID"] = receipt.SCHEME_ID ?: ""
                    txn["TransactionDateTime"] = receipt.TXN_DT ?: ""

                    val eppDetail = Gson().fromJson(receipt.EPP_DETAIL, EppDetail::class.java)
                    if (eppDetail?.Tenure != null && eppDetail.Tenure != "00") {
                        txn["TransactionEPP"] = receipt.EPP_DETAIL ?: ""
                    } else {
                        txn["TransactionEPP"] = "-"
                    }
                    Route.Return(txn)
                }
            } else {
                Route.Return(txn.apply {
                    put(TxnKeys.RESP_CODE, EnumResponseCode.TRANSACTION_NOT_FOUND.code)
                    put(TxnKeys.RESP_DESC, EnumResponseCode.TRANSACTION_NOT_FOUND.description)
                })
            }
        } catch (_: Exception) {
            Route.Return(txn.apply {
                put(TxnKeys.RESP_CODE, "SHC001")
                put(TxnKeys.RESP_DESC, "Invalid Parameter - (TransactionType)")
            })
        }
    }
}
