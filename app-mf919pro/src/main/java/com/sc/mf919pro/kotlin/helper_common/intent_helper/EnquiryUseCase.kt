package com.sc.mf919pro.kotlin.helper_common.intent_helper
import enums.EnumResponseCode

import android.content.Context
import com.google.gson.Gson
import com.sc.mf919pro.java.activity.Utils
import data_enum.CardErrorDataEnum
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import tms.models.EppDetail

class EnquiryUseCase {
    fun buildRoute(ctx: Context, req: TxnRequest): Route {
        val txn = req.raw
        val posReference = req.posReference
        if(posReference.isNullOrEmpty()) {
            txn[TxnKeys.RESP_CODE] = "SHC001"
            txn[TxnKeys.RESP_DESC] = "Invalid Parameter - (PosReference)"
            return Route.Return(txn)
        }

        return try {
            val dbModelReceiptUpload = ReceiptUploadRepo.getSingleDesc(ServiceHolder.getContext(), listOf("POS_REF_NO"), arrayOf(posReference))

            println("dbModelReceiptUpload :: ${Gson().toJson(dbModelReceiptUpload)}")
            if(dbModelReceiptUpload != null) {
                if(dbModelReceiptUpload.QrRefId?.trim()?.isNotEmpty() == true) {
                    val transactionQrData = TransactionQrRepo.getSingleTransactionQr(ServiceHolder.getContext(), listOf("refId"), listOf(dbModelReceiptUpload.QrRefId ?: ""))
                    transactionQrData?.let {
                        val transactionDateTime = if(transactionQrData.txnType?.trim().equals("Void", true)){
                            transactionQrData.voidDateTime
                        } else {
                            transactionQrData.txnDateTime
                        }

                        txn[TxnKeys.RESP_CODE] = transactionQrData.respCode ?: ""
                        txn[TxnKeys.RESP_DESC] = transactionQrData.respDesc ?: ""
                        txn["TransactionLabel"] = transactionQrData.txnType ?: ""
                        txn["TransactionAmount"] = Utils.getActualAmount(transactionQrData.txnAmount)
                        txn["TransactionId"] = transactionQrData.hostRefNo ?: ""
                        txn["TransactionRefId"] = transactionQrData.refId ?: ""
                        txn["TransactionEWallet"] = transactionQrData.productCode ?: ""
                        txn["TransactionEWalletDescription"] = transactionQrData.productName ?: ""
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
                        val formedEnumTag = "TAG_${dbModelReceiptUpload.RESP_CODE}"
                        desc = "(" + dbModelReceiptUpload.RESP_CODE + ")" + CardErrorDataEnum.valueOf(formedEnumTag).data
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    txn[TxnKeys.RESP_CODE] = dbModelReceiptUpload.RESP_CODE ?: ""
                    txn[TxnKeys.RESP_DESC] = desc
                    txn["TransactionLabel"] = dbModelReceiptUpload.TXN_TYPE ?: ""
                    txn["TransactionAmount"] = Utils.getActualAmount(dbModelReceiptUpload.TXN_AMT)
                    txn["TransactionMID"] = dbModelReceiptUpload.MID ?: ""
                    txn["TransactionTID"] = dbModelReceiptUpload.TID ?: ""
                    txn["TransactionSTN"] = dbModelReceiptUpload.STAN ?: ""
                    txn["TransactionRRN"] = dbModelReceiptUpload.RRN ?: ""
                    txn["TransactionBatchNo"] = dbModelReceiptUpload.BATCH_NO ?: ""
                    txn["TransactionApplicationLabel"] = dbModelReceiptUpload.CARD_LABEL ?: ""
                    txn["TransactionCardNo"] = dbModelReceiptUpload.CARD_MASKED ?: ""
                    txn["TransactionEntryType"] = dbModelReceiptUpload.ENTRY_TYPE ?: ""
                    txn["TransactionARQC"] = dbModelReceiptUpload.ARQC ?: ""
                    txn["TransactionTVR"] = dbModelReceiptUpload.TVR ?: ""
                    txn["TransactionAID"] = dbModelReceiptUpload.AID ?: ""
                    txn["TransactionCVM"] = dbModelReceiptUpload.CVM ?: ""
                    txn["TransactionTSI"] = "-"
                    txn["TransactionApprovalCode"] = dbModelReceiptUpload.APPR_CODE ?: ""
                    txn["OriTransactionRRN"] = dbModelReceiptUpload.RRN_ORI ?: ""
                    txn["OriTransactionApprovalCode"] = dbModelReceiptUpload.APPR_CODE_ORI ?: ""
                    txn["TransactionInvoice"] = dbModelReceiptUpload.INV_NO ?: ""
                    txn["TransactionSchemeID"] = dbModelReceiptUpload.SCHEME_ID ?: ""
                    txn["TransactionDateTime"] = dbModelReceiptUpload.TXN_DT ?: ""

                    val eppDetail = Gson().fromJson(dbModelReceiptUpload.EPP_DETAIL, EppDetail::class.java)
                    if(eppDetail?.Tenure != null && eppDetail.Tenure != "00"){
                        txn["TransactionEPP"] = dbModelReceiptUpload.EPP_DETAIL ?: ""
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