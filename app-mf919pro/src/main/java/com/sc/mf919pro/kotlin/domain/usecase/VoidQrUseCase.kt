package com.sc.mf919pro.kotlin.domain.usecase

import android.content.Context
import com.google.gson.Gson
import com.sc.mf919pro.java.activity.Utils
import emv.EmvUtil
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import enums.EnumDateFormat
import enums.EnumLogFileName
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tms.handlers.VoidQrHandler
import tms.models.VoidQrResponseModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoidQrUseCase {
    suspend fun lookupInvoice(context: Context, refId: String): VoidQrLookupResult = withContext(Dispatchers.IO) {
        try {
            val fetchSalesRecord = TransactionQrRepo.getSingleTransactionQr(context, listOf("refId"), listOf(refId))
            if (fetchSalesRecord == null) {
                return@withContext VoidQrLookupResult.NotFound("SHC001", "Invalid Ref ID")
            }

            val amount = fetchSalesRecord.txnAmount ?: "0"
            VoidQrLookupResult.Found(
                VoidQrLookupData(
                    transaction = fetchSalesRecord,
                    amountDisplay = "RM" + Utils.getActualAmount(amount),
                    productName = fetchSalesRecord.productName ?: "-",
                    hostRefNo = fetchSalesRecord.hostRefNo ?: "-",
                    refId = fetchSalesRecord.refId ?: "-",
                )
            )
        } catch (ex: Exception) {
            VoidQrLookupResult.Error("SHC999", ex.message ?: "Void QR lookup failed", ex)
        }
    }

    suspend fun executeVoid(context: Context, request: VoidQrExecutionRequest): VoidQrExecutionResult = withContext(Dispatchers.IO) {
        val transaction = request.lookupData.transaction
        val helperLog: HelperLog = request.helperLog
        val transData = request.transData
        val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)

        request.posReference?.let {
            transData.posReference = it
            helperLog.appendLine(VoidQrUseCase::class.simpleName.toString(), "Add Pos Reference :: $it")
        } ?: run {
            transData.posReference = transaction.posRefNo
            helperLog.appendLine(VoidQrUseCase::class.simpleName.toString(), "Fallback for Pos Reference from BatchTable")
            helperLog.appendLine(VoidQrUseCase::class.simpleName.toString(), "Add Pos Reference :: ${transaction.posRefNo}")
        }

        val sdf = SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmss.dateFormat, Locale.ENGLISH)
        val qrTxnDt = sdf.format(Date())
        val qrRefId = transaction.refId ?: ""

        transData.transDateAsci = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
        transData.txnTypeLabel = "Void"
        transData.amount = (transaction.txnAmount ?: "0").toLong()
        transData.acqCode = transaction.acqCode ?: ""
        transData.mid = transaction.mid ?: ""
        transData.tid = transaction.tid ?: ""
        transData.qrRef = qrRefId
        transData.qrHostRef = transaction.hostRefNo ?: ""
        transData.qrPayBrand = transaction.productCode ?: ""
        transData.qrPayBrandDesc = transaction.productName ?: ""

        transData.qrApprovalCode = transaction.approvalCode ?: ""
        transData.isUPIQR = transaction.isUnionPayTxn == "1"
        transData.upiVoucherCode = transaction.upiVoucherCode
        transData.upiDiscountAmt = transaction.upiDiscountAmt
        transData.upiMarkupFee = transaction.upiMarkupFee
        transData.upiFinalAmount = if (!transaction.upiVoucherCode.isNullOrEmpty()) {
            val finalAmt = (transaction.txnAmount ?: "0").toLong() - transaction.upiDiscountAmt.ifBlank { "0" }.toLong()
            "RM" + Utils.getActualAmount(finalAmt.toString())
        } else {
            ""
        }

        var respCode = "1100"
        var respDesc = ""
        var qrRespRefId = ""
        var qrRespTxnRefNo = ""
        var isTpaAccount = false
        var success = false

        helperLog.appendLine(VoidQrUseCase::class.simpleName.toString(), "Upload Payload API Request")
        val voidQrHandler = VoidQrHandler(environmentManager)
        var encodedPIN = ""

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val voidWithPIN = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "VOID_WITH_PIN")
        if (voidWithPIN.toInt() == 1) {
            encodedPIN = TmsHelper.generateEncodedPIN(request.terminalPw)
        }

        try {
            val voidQrResp = voidQrHandler.invoke(
                helperLog,
                qrTxnDt,
                transaction.mid ?: "",
                transaction.tid ?: "",
                transaction.refId ?: "",
                transaction.txnRefNo ?: "",
                "refund",
                encodedPIN
            )
            helperLog.appendLine(VoidQrUseCase::class.simpleName.toString(), "VoidQrHandler Response -> ", voidQrHandler.toString())

            respCode = voidQrResp.RESP_CODE ?: ""
            respDesc = voidQrResp.RESP_DESC ?: ""
            qrRespRefId = voidQrResp.QR_REFID ?: ""
            qrRespTxnRefNo = voidQrResp.QR_TXN_REFNO ?: ""
            isTpaAccount = voidQrResp.IS_TPA_ACCOUNT ?: run {
                transaction.isTpaAccount == "true"
            }
            success = true
        } catch (ex: Exception) {
            ex.printStackTrace()
            helperLog.appendLine(VoidQrUseCase::class.simpleName.toString(), "VoidQrHandler (Exception) -> ", ex.toString())

            try {
                val errorResponse = Gson().fromJson(ex.message, VoidQrResponseModel::class.java)
                respCode = errorResponse.RESP_CODE ?: "1100"
                respDesc = errorResponse.RESP_DESC ?: ""
                qrRespRefId = errorResponse.QR_REFID ?: ""
                qrRespTxnRefNo = errorResponse.QR_TXN_REFNO ?: ""
                isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: run {
                    transaction.isTpaAccount == "true"
                }
            } catch (jsonEx: Exception) {
                helperLog.appendLine(VoidQrUseCase::class.simpleName.toString(), "Json Exception in Error -> ", jsonEx.toString())
            }

            helperLog.logToFile(EnumLogFileName.TerminaLogException)
        }

        transData.isTpaAccount = isTpaAccount
        transData.qrRespCode = respCode
        transData.qrRespDesc = respDesc
        transData.qrTxnRef = qrRespTxnRefNo

        if (success) {
            val criteriaHM = hashMapOf<Any, Any>(
                "id" to (transaction.id ?: "0").toString(),
                "refId" to qrRefId
            )
            val valueHM = hashMapOf<Any, Any>(
                "txnType" to transData.txnTypeLabel,
                "voidDateTime" to qrTxnDt,
                "txnRefNo" to qrRespTxnRefNo,
                "respCode" to respCode,
                "respDesc" to respDesc,
            )
            TransactionQrRepo.updateTransactionQr(context, valueHM, criteriaHM)

            val receiptUploadCriteria = hashMapOf<Any, Any>(
                "QrRefId" to qrRefId
            )
            val receiptUploadValue = hashMapOf<Any, Any>(
                "TXN_DT" to qrTxnDt,
                "TXN_TYPE" to transData.txnTypeLabel,
            )
            ReceiptUploadRepo.updateData(context, receiptUploadValue, receiptUploadCriteria)

            VoidQrExecutionResult.Success(
                respCode = respCode,
                respDesc = respDesc,
                qrTxnRefNo = qrRespTxnRefNo,
                qrRefId = qrRespRefId,
                isTpaAccount = isTpaAccount,
            )
        } else {
            VoidQrExecutionResult.Error(
                respCode = respCode,
                respDesc = respDesc,
                qrTxnRefNo = qrRespTxnRefNo,
                qrRefId = qrRespRefId,
                isTpaAccount = isTpaAccount,
            )
        }
    }
}