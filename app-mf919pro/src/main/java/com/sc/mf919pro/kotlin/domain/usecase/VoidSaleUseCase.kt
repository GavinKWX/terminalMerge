package com.sc.mf919pro.kotlin.domain.usecase

import android.content.Context
import com.library.terminal.Utility
import com.sc.mf919pro.java.activity.EmvTag
import com.sc.mf919pro.java.activity.Global
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.utils.EmvUtil
import utils.HexUtil
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.repo.BatchTableRepo
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import helpers.LogRedact
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919pro.kotlin.helper_common.iso.IsoHelperNew
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Immutable snapshot of a void's outcome, taken the moment the ISO exchange completes. */
private data class VoidOutcome(
    val transResult: String = "",
    val respCodeHex: String = "",
    val txnTypeLabel: String = "",
    val invoiceNo: String = "",
    val stan: String = "",
)

class VoidSaleUseCase {
    suspend fun lookupInvoice(context: Context, invoiceNum: String): VoidSaleLookupResult = withContext(Dispatchers.IO) {
        try {
            val fetchSalesRecord = BatchTableRepo.getSingleForCertainType(
                context,
                listOf("invNo"),
                listOf(invoiceNum),
                listOf("Sale", "Cash Out", "Instalment Sale", "Moto")
            )

            if (fetchSalesRecord == null) {
                return@withContext VoidSaleLookupResult.NotFound("SHC001", "Invalid Transaction Invoice")
            }

            val bBatchInfo = HexUtil.hexStringToByte(fetchSalesRecord.batchData)
            val emvTag = EmvTag()
            val bTxnAmt = ByteArray(6)
            val bCardPan = ByteArray(12)
            val bTxnRRN = ByteArray(12)
            val bApprCode = ByteArray(6)

            emvTag.getValueFrom(bBatchInfo, "DF04", bTxnAmt)
            emvTag.getValueFrom(bBatchInfo, "BF37", bTxnRRN)
            emvTag.getValueFrom(bBatchInfo, "BF38", bApprCode)
            val bCardPanLen = emvTag.getValueFrom(bBatchInfo, "DF02", bCardPan)

            val amountHex = HexUtil.bytesToHexString(bTxnAmt)
            val cardPan = HexUtil.bytesToHexString(bCardPan, 0, bCardPanLen).replace("F", "")
            /*
             * Register here, not at the callers. A void takes its PAN from the BATCH TABLE and
             * never touches EMV, so LogRedact (which only learns the PAN in EmvUtil.readTrack2)
             * had nothing to match and the raw PAN reached the uploaded log via DF02, ISO DE2 and
             * the batchData TLV.
             *
             * This is the single point where every void path obtains card data -- VoidSaleFragment,
             * VoidSaleCompFragment, VoidPreAuthFragment AND TransactionViewListFragment all call
             * lookupInvoice. Registering at the call sites missed the history-initiated void
             * (measured on device) and left VoidSaleCompFragment registering AFTER it had already
             * logged the batch record.
             */
            LogRedact.registerCardData(cardPan, null)
            val transRRN = Utils.byteArrayToAsciiString(bTxnRRN)
            val approvalCode = Utils.byteArrayToAsciiString(bApprCode)

            VoidSaleLookupResult.Found(
                VoidSaleLookupData(
                    batchTableModel = fetchSalesRecord,
                    amountHex = amountHex,
                    amountDisplay = Utils.getActualAmount(amountHex),
                    cardPan = cardPan,
                    maskedCardPan = Utils.hideCardDetails(cardPan),
                    rrn = transRRN,
                    approvalCode = approvalCode,
                    invoiceNo = fetchSalesRecord.invNo,
                )
            )
        } catch (ex: Exception) {
            VoidSaleLookupResult.Error(
                code = "SHC999",
                message = ex.message ?: "Void sale lookup failed",
                cause = ex,
            )
        }
    }

    suspend fun executeVoid(context: Context, request: VoidSaleExecutionRequest): VoidSaleExecutionResult = withContext(Dispatchers.IO) {
        val batchTableModel = request.lookupData.batchTableModel
        val helperLog = request.helperLog
        val isNotCompl = booleanArrayOf(true)

        // The void's own outcome, captured inside the coroutine the instant the ISO
        // exchange completes, so the result handed back to the caller cannot be rewritten by a
        // concurrent flow during the busy-wait below.
        //
        // AtomicReference rather than plain captured locals: it is written on the IO coroutine and
        // read on this one, and Kotlin does not allow @Volatile on a local (the closure captures
        // it in a Ref with no visibility guarantee). This gives a real happens-before.
        val outcome = java.util.concurrent.atomic.AtomicReference(VoidOutcome())

        try {
            ServiceHolder.isoComm = null
            TransData.startTime = System.currentTimeMillis()
            TransData.tpaMid = DbModelMerchantConfig.getSafeValue(request.merchantConfig, "ScMid")
            TransData.tpaTid = DbModelMerchantConfig.getSafeValue(request.merchantConfig, "ScTid")

            request.saleModel?.let {
                TransData.acqCode = it.AcqCode ?: ""
                TransData.mid = it.AcqMid ?: Utils.paddingWith("", "0", 12, true)
                TransData.tid = it.AcqTid ?: Utils.paddingWith("", "0", 8, true)
                TransData.product = it.Product ?: ""
                TransData.productName = it.ProductName ?: ""
                TransData.eppTenure = it.EppTenure ?: ""
                TransData.eppTenureCode = it.EppTenureCode ?: ""
                TransData.ksn = it.Ksn ?: ""
                TransData.pinKsn = it.PinKsn ?: ""
                TransData.isTpaAccount = it.IsTpaAccount?.lowercase() == "true"
            }

            TransData.transDateAsci = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
            TransData.mid = batchTableModel.mid
            TransData.tid = batchTableModel.tid
            TransData.schemeId = batchTableModel.schemeId
            TransData.schemeTag = batchTableModel.schemeTag

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same stan to two concurrent flows.
            TransData.stan = IsoBatchInfoRepo.allocateCounter(context, "stan", TransData.schemeTag)
            helperLog.appendLine(VoidSaleUseCase::class.simpleName.toString(), "STAN :: ${TransData.stan}")

            val // Atomic allocation; the open-coded get/increment/put could hand the
 // same invoiceNo to two concurrent flows.
 invoiceNo = IsoBatchInfoRepo.allocateCounter(context, "invoiceNo", "pos")

            TransData.invoiceNo = batchTableModel.invNo
            TransData.prevStan = batchTableModel.stan
            TransData.prevInvoice = batchTableModel.invNo
            TransData.prevApprovalCode = request.lookupData.approvalCode
            TransData.prevRRN = request.lookupData.rrn
            TransData.batchNo = batchTableModel.batchNo
            TransData.amount = request.lookupData.amountHex.toLong()
            val byteAmount = HexUtil.hexStringToByte(request.lookupData.amountHex)
            byteAmount.copyInto(TransData.amountAuth, 0, 0, byteAmount.size)
            TransData.maskedPan = request.lookupData.maskedCardPan
            TransData.hashedPan = request.lookupData.cardPan.substring(0, 9)
            val bytePan = HexUtil.hexStringToByte(request.lookupData.cardPan)
            bytePan.copyInto(TransData.pan, 0)
            TransData.panLen = request.lookupData.cardPan.length

            val oldTransDb = HexUtil.hexStringToByte(batchTableModel.batchData)
            oldTransDb.copyInto(TransData.transactionDb, 0, 0, oldTransDb.size)
            TransData.transactionDbLen = oldTransDb.size - 2
            TransData.entryModeLabel = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ENTRY_MODE, 256)
            TransData.cvm = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_CVM, 16)
            TransData.aid = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_AID, 16)

            if (TransData.aid == "" && TransData.cvm == "") {
                TransData.aid = TransData.getFromTransactionDb(Global.iso.tag.AID, 16)
                TransData.cvm = "1F0303"
                TransData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARD_AID, TransData.aid)
                TransData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARD_CVM, TransData.cvm)
            }

            request.posReference?.let {
                TransData.posReference = it
                helperLog.appendLine(VoidSaleUseCase::class.simpleName.toString(), "Add Pos Reference :: $it")
            } ?: run {
                TransData.posReference = batchTableModel.posRefNo
                helperLog.appendLine(VoidSaleUseCase::class.simpleName.toString(), "Fallback for Pos Reference from BatchTable")
                helperLog.appendLine(VoidSaleUseCase::class.simpleName.toString(), "Add Pos Reference :: ${batchTableModel.posRefNo}")
            }

            CoroutineScope(Dispatchers.IO).launch {
                if (batchTableModel.txnType.equals("Cash Out", true)) {
                    TransData.txnTypeLabel = "CashOut Void"
                    IsoActivity.processCashOutVoid(context, isNotCompl, helperLog)
                } else {
                    TransData.txnTypeLabel = if (batchTableModel.txnType.equals("Instalment Sale", true)) {
                        "Void Instalment"
                    } else {
                        "Void"
                    }
                    IsoActivity.processVoidSale(context, isNotCompl, helperLog)
                }

                if (TransData.transResult != Global.iso.err.txnApproved &&
                    TransData.respCode.isEmpty() &&
                    TransData.acqCode.equals("BSN_CARDZONE", true)
                ) {
                    var loop = 0
                    val maxLoop = 3
                    while (loop < maxLoop) {
                        loop++
                        request.onProgressTitle("Reversal ($loop)")

                        val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "reversal")
                        acquirerRevIsoModel?.let { revIsoModel ->
                            val allIsoString = HexUtil.bytesToHexString(
                                TransData.transactionDb,
                                0,
                                TransData.transactionDbLen + 2
                            )
                            val result = IsoActivity.processReversal(context, false, revIsoModel, allIsoString, true, helperLog)
                            helperLog.appendLine(VoidSaleUseCase::class.simpleName.toString(), "reversal result :: $result")
                            if (result != null) {
                                loop = maxLoop
                            }
                        }
                    }
                }
                // Snapshot the outcome HERE, inside the
                // coroutine, the moment the ISO exchange is done. Reading TransData back after
                // the busy-wait below means any concurrent flow that touches the singleton in
                // between decides what this void reports to its caller — and the caller uses
                // these values for the result screen and the App2App reply.
                outcome.set(
                    VoidOutcome(
                        transResult = TransData.transResult.toString(),
                        respCodeHex = TransData.respCode,
                        txnTypeLabel = TransData.txnTypeLabel,
                        invoiceNo = TransData.invoiceNo,
                        stan = TransData.stan,
                    )
                )
                isNotCompl[0] = false
            }

            while (isNotCompl[0]) {
                val status = ServiceHolder.isoComm?.connectionStatus
                if (!status.isNullOrEmpty()) {
                    request.onProgressMessage(status)
                }
                delay(500L)
            }

            val snap = outcome.get()
            VoidSaleExecutionResult.Success(
                transResult = snap.transResult,
                respCodeHex = snap.respCodeHex,
                respCodeAscii = Utility.HexString2ASCII(snap.respCodeHex),
                txnTypeLabel = snap.txnTypeLabel,
                invoiceNo = snap.invoiceNo,
                stan = snap.stan,
            )
        } catch (ex: Exception) {
            isNotCompl[0] = false
            VoidSaleExecutionResult.Error(
                code = "SHC999",
                message = ex.message ?: "Void sale execution failed",
                cause = ex,
            )
        }
    }
}