package com.sc.mf919pro.kotlin.helper_common.iso
import iso.IsoInfoModel
import iso.IsoHelperNew
import iso.bsn.IsoStepsBSNNew
import iso.bsn_cardzone.IsoStepsBsnCardZone
import iso.gobiz.IsoStepsGobiz

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.library.terminal.Utility
import emv.EmvTag
import constants.TerminalConstants
import iso.IsoComm
import com.sc.mf919pro.java.activity.UploadTMS
import com.sc.mf919pro.java.activity.Utils
import emv.EmvUtil
import utils.HexUtil
import com.sc.mf919pro.kotlin.activity.AppServices
//import com.sc.mf919.kotlin.activity.SettlementActivity
import data_enum.CardSchemeEnum
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelBatchTableInsert
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelPreAuthTableInsert
import com.sc.mf919pro.kotlin.database.model.DbModelPrintReceiptInsert
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.model.DbModelRevBatchTableInsert
import com.sc.mf919pro.kotlin.database.model.DbModelSettlementSummary
import com.sc.mf919pro.kotlin.database.repo.BatchTableRepo
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.IsoBatchLongInfoRepo
import com.sc.mf919pro.kotlin.database.repo.PreAuthTableRepo
import com.sc.mf919pro.kotlin.database.repo.PrintReceiptRepo
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo.Companion.getUnSettledProduct
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919pro.kotlin.database.repo.ReversalBatchTableRepo
import com.sc.mf919pro.kotlin.database.repo.SettlementSummaryRepo
import com.sc.mf919pro.kotlin.datastore.DataStoreManager
import com.sc.mf919pro.kotlin.datastore.PrefKeys
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.isoComm
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.sendWriteLog
import iso.paydee.IsoStepsNew
import enums.EnumDateFormat
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.LogRedact
import helpers.HelperLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.math.NumberUtils
import org.json.JSONException
import org.json.JSONObject
import helpers.StorageGuard
import java.io.Serializable
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

object IsoActivity: Serializable {
    private var thisIsoDbBufLen = 0
    private var thisIsoDbBuf: ByteArray = ByteArray(6000)
    private var cacheDe55 = ""

    private val logClassName: String = this::class.java.simpleName

    // D8 -- how long the host owns this transaction.
    //
    // TransData is a process-wide singleton and sendToHost() blocks for up to HostTimeoutMs
    // (60s default), with the response parsed straight into it. Anything that resets or re-owns
    // TransData in that window corrupts a live authorisation, so callers gate on
    // isHostRequestInFlight first.
    //
    // A counter, not a flag: settlement and batch upload issue several sendToHost() calls, and a
    // bool would read clear as soon as the first one finished. It also lives here rather than in a
    // Fragment, because this object is what makes the host calls -- the previous flag sat in
    // EmvFragment and was only ever raised around the EMV sale paths, so settlement ran with the
    // guard down the whole time.
    private val hostRequestsInFlight = AtomicInteger(0)

    val isHostRequestInFlight: Boolean get() = hostRequestsInFlight.get() > 0

    /**
     * Hold the guard across a whole flow, not just the network call.
     *
     * `sendToHost` raises the counter itself, which covers the 0200 and the response parse. But a
     * caller like `EmvFragment` needs it held wider than that: the window it must protect runs from
     * before the ISO is built to after the approval has been persisted, and a back press in the
     * tail end would blank stan/invoiceNo/respCode with the approval already taken.
     *
     * Nesting is why this is a counter. This wraps a block that itself calls `sendToHost` -- maybe
     * several times -- and each one increments and decrements again inside. A boolean would be
     * cleared by the first inner call to finish and leave the outer window unguarded.
     */
    fun <T> withHostRequest(block: () -> T): T {
        hostRequestsInFlight.incrementAndGet()
        try {
            return block()
        } finally {
            hostRequestsInFlight.decrementAndGet()
        }
    }

    // Builds the ISO settlement value field. Lives here (with the ISO logic) so it survives
    // the removal of settlement code from SettlementFragment; used by SettleOptionFragment too.
    fun constructSettlementValueString(txnCount: String, txnTotal: String, tcCount: String): String {
        val iTxnCount = Utils.atoi(txnCount)
        val iTxnTotal = Utils.atoi(txnTotal)
        val iTcCount = Utils.atoi(tcCount)
        return String.format(Locale.ENGLISH, "%03d", iTxnCount) + String.format(Locale.ENGLISH, "%012d", iTxnTotal) + "000" + "000000000000" + "000" + "000000000000" + String.format(Locale.ENGLISH, "%03d", iTcCount)
    }

    private fun invokeFunction(acqCode: String, functionName: String, vararg params: Any): Any? {
        val isoSteps: Any
        var classMethod: Method? = null
        isoSteps = if(acqCode.equals("BSN",  true)){
            IsoStepsBSNNew
        } else if (acqCode.equals("BSN_CARDZONE",  true)) {
            IsoStepsBsnCardZone
        } else if (acqCode.equals("GOBIZ",  true) || acqCode.equals("FINEXUS",  true)) {
            IsoStepsGobiz
        } else {
            IsoStepsNew
        }

        when(functionName){
            "transformBatchData" -> {
                classMethod = isoSteps.javaClass.getDeclaredMethod("transformBatchData", Context::class.java)
            }
            "formIsoMessage" -> {
                classMethod = isoSteps.javaClass.getDeclaredMethod("formIsoMessage", Context::class.java, HelperLog::class.java, IsoInfoModel::class.java, Int::class.java, ByteArray::class.java, IntArray::class.java)
            }
            "parseIsoResp" -> {
                classMethod = isoSteps.javaClass.getDeclaredMethod("parseIsoResp", HelperLog::class.java, ByteArray::class.java, Int::class.java, Int::class.java)
            }
            "formIsoIsolate" -> {
                classMethod = isoSteps.javaClass.getDeclaredMethod("formIsoIsolate", Context::class.java, HelperLog::class.java, IsoInfoModel::class.java, String::class.java, ByteArray::class.java, IntArray::class.java)
            }
            "parseIsoRespIsolate" -> {
                classMethod = isoSteps.javaClass.getDeclaredMethod("parseIsoRespIsolate", HelperLog::class.java, ByteArray::class.java, Int::class.java, Int::class.java, ByteArray::class.java, IntArray::class.java, MutableMap::class.java)
            }
            "formIsoSignOn" -> {
                classMethod = isoSteps.javaClass.getDeclaredMethod("formIsoSignOn", Context::class.java, HelperLog::class.java, IsoInfoModel::class.java, String::class.java, ByteArray::class.java, IntArray::class.java)
            }
            "parseRespSignOn" -> {
                classMethod = isoSteps.javaClass.getDeclaredMethod("parseRespSignOn", Context::class.java, HelperLog::class.java, ByteArray::class.java, IntArray::class.java)
            }
            "derivedFutureKey" -> {
                classMethod = isoSteps.javaClass.getDeclaredMethod("derivedFutureKey", Context::class.java, HelperLog::class.java)
            }
        }

        if(classMethod != null){
            classMethod.isAccessible = true

            // One block per entry into the acquirer layer. The four IsoStepsNew objects append
            // over a hundred lines each and have no logToFile of their own, so their step flow
            // used to surface only inside the caller's block -- and died with it if the process
            // was killed mid-step. They all append to the caller's HelperLog, which is always one
            // of the params, so bracketing here covers every acquirer and every step in one place
            // and the four IsoStepsNew files need no edits at all.
            // finally, not a plain call after invoke: a step that throws is exactly the step whose
            // block is worth having.
            val stepLog = params.firstOrNull { it is HelperLog } as? HelperLog
            if (stepLog == null) {
                return classMethod.invoke(isoSteps, *params)
            }
            stepLog.appendLine(logClassName, "-----------------$acqCode :: $functionName [START]-------------------->")
            try {
                return classMethod.invoke(isoSteps, *params)
            } finally {
                stepLog.appendLine(logClassName, "-----------------$acqCode :: $functionName [END]-------------------->")
                stepLog.logToFile(EnumLogFileName.TerminaLog)
            }
        }
        return null
    }

    fun setCacheDe55(value: String) {
        cacheDe55 = value
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun processTcUpload(context: Context, log: HelperLog) {
        log.appendLine(logClassName, "-----------------PROCESS TcUpload [START]-------------------->")
        Thread.sleep(500)
        log.appendLine(logClassName, "AcqCode :: ${TransData.acqCode}")
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "tc_upload")

        //TODO Check Speed
        acquirerIsoModel?.let {isoInfoModel ->
            val emvTag = EmvTag()
            var isoDbLen = IntArray(2)
            var isoDbByte = ByteArray(6000)
            val isoBatchData = HexUtil.bytesToHexString(TransData.transactionDb, 0, TransData.transactionDbLen + 2)
            val variantMap = invokeFunction(TransData.acqCode.uppercase(), "formIsoIsolate", context, log, isoInfoModel, isoBatchData, isoDbByte, isoDbLen) as MutableMap<String, String>

            if(isoDbLen[0] > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, isoDbByte, isoDbLen[0], true, respIsoDb, respDbLen, log)
                isoDbLen = IntArray(2)
                isoDbByte = ByteArray(6000)
                invokeFunction(TransData.acqCode.uppercase(), "parseIsoRespIsolate", log, respIsoDb, 2, respDbLen[0], isoDbByte, isoDbLen, variantMap)
            }

            val tempByte = ByteArray(2048)
            val tempLen = emvTag.getValueFrom(isoDbByte, "BF39", tempByte)
            var responseCode = -1
            if(tempLen > 0){
                val stringRespCode = HexUtil.bytesToHexString(tempByte, 0, tempLen)
                log.appendLine(logClassName, "stringRespCode :: $stringRespCode")
                responseCode = stringRespCode.toInt(16)
            }

            if(tempLen <= 0 || responseCode != 0x3030){
                log.appendLine(logClassName, "ERR: TC Upload Failed. Ignore")
            }
            invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
        }

        //TODO Check Speed
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "TCStartTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "TCEndTime :: $finish")
        log.appendLine(logClassName, "TCTimeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------PROCESS  TcUpload [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processOnlineSale(context: Context, log: HelperLog) {
        // Refuse before the host is contacted and before any counter advances.
        // This is the only point where nothing has happened yet and nothing needs reconciling.
        if (!StorageGuard.canTransact(context)) {
            StorageGuard.logBlocked(context, "processOnlineSale")
            log.appendLine(logClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(context))
            TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
            // TransData.respCode is HEX-ASCII on this codebase (FragmentResult does
            // HexString2ASCII(respCode) -> "TAG_$code", and EmvFragment builds "8A02$respCode").
            // "5A53" is hex for "ZS" -> CardErrorDataEnum.TAG_ZS. A literal "ZS" here is not hex,
            // produces an invalid 8A TLV, and reaches the EMV kernel as a non-numeric REJCODE,
            // which throws across the binder and leaves the kernel unanswered (UI hangs on
            // "waiting for approval").
            TransData.respCode = StorageGuard.RESP_CODE_HEX
            return
        }
        log.appendLine(logClassName, "-----------------Process Sales [START]-------------------->")
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "Sale")

        //TODO Check Speed
        log.appendLine(logClassName, "SchemeID :: ${TransData.schemeId}")
        acquirerIsoModel?.let { isoInfoModel ->
            TransData.transIsoInfoModel = isoInfoModel
            /*val cardSchemeBrand = IsoBatchInfoRepo.getBatchInfo(context, "schemeTag", TransData.schemeId)?.let {
                it.value
            } ?: run { "" }
            TransData.schemeTag = cardSchemeBrand
            println("cardSchemeBrand -> $cardSchemeBrand")
            log.appendLine(logClassName, "cardSchemeBrand -> $cardSchemeBrand")*/
            TransData.schemeTag = "visam"

            // A swipe has no chip session, so EmvUtil.readTrack2() comes back empty and the
            // substring below throws -- the flow then dies inside this let{} with the progress
            // dialog still up. Take the track from TransData when the reader gave us one.
            val track2 = if (TransData.magTrack2Len > 0){
                val magTrack2str = Utils.byteArrayToAsciiString(TransData.magTrack2, 0, TransData.magTrack2Len)
                log.appendLine(logClassName, "magTrack2 :: ${LogRedact.track2(magTrack2str)}")
                magTrack2str
            } else {
                EmvUtil.readTrack2()
            }
            //val track2 = CommonConvert.bytesToString(TransData.track2EQ, 0, TransData.track2EQlen)
            val track2Delimiter = track2.indexOf("D")
            val cardMask = track2.substring(0, track2Delimiter)
            val md = TransData.transDateAsci.substring(4, 8)
            val hhmmss = TransData.transDateAsci.substring(8)

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same invoiceNo to two concurrent flows.
            TransData.invoiceNo = IsoBatchInfoRepo.allocateCounter(context, "invoiceNo", "pos")
            log.appendLine(logClassName, "Invoice No :: ${TransData.invoiceNo}")

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same stan to two concurrent flows.
            TransData.stan = IsoBatchInfoRepo.allocateCounter(context, "stan", TransData.schemeTag)
            log.appendLine(logClassName, "Stan :: ${TransData.stan}")

            TransData.batchNo = IsoBatchInfoRepo.getBatchInfo(context, "batchNo", TransData.schemeTag)?.value ?: "000001"
            log.appendLine(logClassName, "Batch No :: ${TransData.batchNo}")
            TransData.maskedPan = Utils.hideCardDetails(cardMask)
            log.appendLine(logClassName, "Masked Pan :: ${TransData.maskedPan}")
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_MASKBCD, Utils.ASCIItoHexString(Utils.hideCardDetails(cardMask)))
            TransData.hashedPan = cardMask.substring(0,9)
            log.appendLine(logClassName, "Hashed Pan :: ${TransData.hashedPan}")
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_HASH, Utils.ASCIItoHexString(TransData.hashedPan))
            if(track2Delimiter > 0){
                val cardExp = track2.substring(track2Delimiter + 1, track2Delimiter + 5)
                TransData.addHexStrWithPadIntoTransDB("DF02", cardMask, "F")
                TransData.addHexStrIntoTransDB("DF14", cardExp)
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.PANSTRING, cardMask)
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.EXPDATE, cardExp)
            }
            TransData.addHexStrIntoTransDB("DA", TransData.schemeId)
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)
            TransData.addTlvIntoTransDB("DF04", TransData.amountAuth, 0, TransData.amountAuth.size)
            TransData.addHexStrIntoTransDB("DF11", TransData.stan)
            TransData.addHexStrIntoTransDB("DF12", hhmmss)
            TransData.addHexStrIntoTransDB("DF13", md)
            EmvUtil.getPbocData("5F34", true)?.let {
                TransData.addHexStrIntoTransDB("DF23", Utils.paddingWith(it, "0", 4, false))
            }
            TransData.addHexStrIntoTransDB("DF25", isoInfoModel.posCondition)
            TransData.addHexStrWithPadIntoTransDB("DF35",track2, "F")
            TransData.addHexStrIntoTransDB("DF41", HexUtil.str2HexStr(TransData.tid))
            TransData.addHexStrIntoTransDB("DF42", HexUtil.str2HexStr(TransData.mid))
            TransData.addHexStrIntoTransDB("DF60", HexUtil.str2HexStr(TransData.batchNo))
            TransData.addHexStrIntoTransDB("DF62", HexUtil.str2HexStr(TransData.invoiceNo))
            //val track3 = EmvUtil.getAcquirerRequiredTlvData()
            val track3 = Utils.byteArrayToAsciiString(TransData.track3, 0 ,TransData.track3Len)
            log.appendLine(logClassName, "track3 :: $track3")
            TransData.addHexStrIntoTransDB("DF55", track3)

            //TODO PINBLOCK
            if (TransData.encPinBlock !== "") {
                TransData.addHexStrIntoTransDB("DF52", TransData.encPinBlock)
            }

            IsoBatchInfoRepo.getBatchInfo(context, "isoTpduHeaderTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.TPDU, it.value)
            } ?: run { "" }

            IsoBatchInfoRepo.getBatchInfo(context, "niiTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB("DF24", it.value)
            } ?: run { "" }

            /* val strSchemeTagWithSchemeIdAndAcquirer = "${TransData.schemeTag}-${TransData.schemeId}-${TransData.acqCode.lowercase()}"
             val strPosEntryMode = IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithSchemeIdAndAcquirer)?.let {
                 println("Obtained PosEntry -> $strSchemeTagWithSchemeIdAndAcquirer")
                 log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithSchemeIdAndAcquirer")
                 it.value
             } ?: run {
                 val strSchemeTagWithAcquirer = "${TransData.schemeTag}-${TransData.acqCode.lowercase()}"
                 IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithAcquirer)?.let {
                     println("Obtained PosEntry -> $strSchemeTagWithAcquirer")
                     log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithAcquirer")
                     it.value
                 } ?: run {
                     val strSchemeTagWithSchemeId = "${TransData.schemeTag}-${TransData.schemeId}"
                     IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithSchemeId)?.let {
                         println("Obtained PosEntry -> $strSchemeTagWithSchemeId")
                         log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithSchemeId")
                         it.value
                     } ?: run {
                         val strSchemeTagWithMti = "${TransData.schemeTag}-${isoInfoModel.mti}"
                         IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithMti)?.let {
                             println("Obtained PosEntry -> $strSchemeTagWithMti")
                             log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithMti")
                             it.value
                         } ?: run{
                             IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", TransData.schemeTag)?.let {
                                 println("Obtained PosEntry -> ${TransData.schemeTag}")
                                 log.appendLine(logClassName, "Obtained PosEntry -> ${TransData.schemeTag}")
                                 it.value
                             } ?: run { "" }
                         }
                     }
                 }
             }*/
            val strSchemeTagWithSchemeId = "${TransData.schemeTag}-${TransData.schemeId}"
            val strPosEntryMode = IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithSchemeId)?.let {
                log.appendLine(logClassName, "Obtained PosEntry :: $strSchemeTagWithSchemeId")
                it.value
            } ?: run {
                val strSchemeTagWithMti = "${TransData.schemeTag}-${isoInfoModel.mti}"
                IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithMti)?.let {
                    log.appendLine(logClassName, "Obtained PosEntry :: $strSchemeTagWithMti")
                    it.value
                } ?: run{
                    IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", TransData.schemeTag)?.let {
                        log.appendLine(logClassName, "Obtained PosEntry :: ${TransData.schemeTag}")
                        it.value
                    } ?: run { "" }
                }
            }
            log.appendLine(logClassName, "strPosEntryMode :: $strPosEntryMode")
            TransData.addHexStrIntoTransDB("DF22", strPosEntryMode)

            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                insertPendingTransactionInto(context, TransData.txnTypeLabel)
            }

            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)
            thisIsoDbBufLen = dbLen[0]
            log.appendLine(logClassName, "dbBuffLen :: $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff :: ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                checkTransactionStatus(context, log)

                log.appendLine(logClassName, "Transaction Result :: ${TransData.transResult}")
                log.appendLine(logClassName, "Response Code ::${TransData.respCode}")
                if (TransData.transResult == TerminalConstants.iso.err.txnApproved) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoBatchTable(context)
                        //insertIntoPrintReceipt(context)
                    }
                } else if (TransData.transResult == TerminalConstants.iso.err.communicationTimeout || TransData.respCode.isEmpty()) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoRevBatchTable(context)
                    }
                }
            }
            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                insertIntoPrintReceipt(context)
            }
            TransData.transEndDate = HelperCommon.getDateString(EnumDateFormat.yyyyMMddHHmmssSSS.dateFormat)
            invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
        }
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "startTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "endTime :: $finish")
        log.appendLine(logClassName, "timeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------Process Sales [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processPreauth(context: Context, log: HelperLog) {
        // Refuse before the host is contacted and before any counter advances.
        // This is the only point where nothing has happened yet and nothing needs reconciling.
        if (!StorageGuard.canTransact(context)) {
            StorageGuard.logBlocked(context, "processPreauth")
            log.appendLine(logClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(context))
            TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
            // TransData.respCode is HEX-ASCII on this codebase (FragmentResult does
            // HexString2ASCII(respCode) -> "TAG_$code", and EmvFragment builds "8A02$respCode").
            // "5A53" is hex for "ZS" -> CardErrorDataEnum.TAG_ZS. A literal "ZS" here is not hex,
            // produces an invalid 8A TLV, and reaches the EMV kernel as a non-numeric REJCODE,
            // which throws across the binder and leaves the kernel unanswered (UI hangs on
            // "waiting for approval").
            TransData.respCode = StorageGuard.RESP_CODE_HEX
            return
        }
        log.appendLine(logClassName, "-----------------Process Pre-Auth [START]-------------------->")
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "preauth")

        //TODO Check Speed
        log.appendLine(logClassName, "SchemeID :: ${TransData.schemeId}")
        acquirerIsoModel?.let { isoInfoModel ->
            TransData.transIsoInfoModel = isoInfoModel
            TransData.schemeTag = "visam"
            val track2 = EmvUtil.readTrack2()
            val track2Delimiter = track2.indexOf("D")
            val cardMask = track2.substring(0, track2Delimiter)
            val md = TransData.transDateAsci.substring(4, 8)
            val hhmmss = TransData.transDateAsci.substring(8)

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same invoiceNo to two concurrent flows.
            TransData.invoiceNo = IsoBatchInfoRepo.allocateCounter(context, "invoiceNo", "pos")
            log.appendLine(logClassName, "Invoice No :: ${TransData.invoiceNo}")

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same stan to two concurrent flows.
            TransData.stan = IsoBatchInfoRepo.allocateCounter(context, "stan", TransData.schemeTag)
            log.appendLine(logClassName, "Stan :: ${TransData.stan}")

            TransData.batchNo = IsoBatchInfoRepo.getBatchInfo(context, "batchNo", TransData.schemeTag)?.value ?: "000001"
            log.appendLine(logClassName, "Batch No :: ${TransData.batchNo}")
            TransData.maskedPan = Utils.hideCardDetails(cardMask)
            log.appendLine(logClassName, "Masked Pan :: ${TransData.maskedPan}")
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_MASKBCD, Utils.ASCIItoHexString(Utils.hideCardDetails(cardMask)))
            TransData.hashedPan = cardMask.substring(0,9)
            log.appendLine(logClassName, "Hashed Pan :: ${TransData.hashedPan}")
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_HASH, Utils.ASCIItoHexString(TransData.hashedPan))
            if(track2Delimiter > 0){
                val cardExp = track2.substring(track2Delimiter + 1, track2Delimiter + 5)
                TransData.addHexStrWithPadIntoTransDB("DF02", cardMask, "F")
                TransData.addHexStrIntoTransDB("DF14", cardExp)
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.PANSTRING, cardMask)
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.EXPDATE, cardExp)
            }
            TransData.addHexStrIntoTransDB("DA", TransData.schemeId)
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)
            TransData.addTlvIntoTransDB("DF04", TransData.amountAuth, 0, TransData.amountAuth.size)
            TransData.addHexStrIntoTransDB("DF11", TransData.stan)
            TransData.addHexStrIntoTransDB("DF12", hhmmss)
            TransData.addHexStrIntoTransDB("DF13", md)
            EmvUtil.getPbocData("5F34", true)?.let {
                TransData.addHexStrIntoTransDB("DF23", Utils.paddingWith(it, "0", 4, false))
            }
            TransData.addHexStrIntoTransDB("DF25", isoInfoModel.posCondition)
            TransData.addHexStrWithPadIntoTransDB("DF35",track2, "F")
            TransData.addHexStrIntoTransDB("DF41", HexUtil.str2HexStr(TransData.tid))
            TransData.addHexStrIntoTransDB("DF42", HexUtil.str2HexStr(TransData.mid))
            TransData.addHexStrIntoTransDB("DF60", HexUtil.str2HexStr(TransData.batchNo))
            TransData.addHexStrIntoTransDB("DF62", HexUtil.str2HexStr(TransData.invoiceNo))
            //val track3 = EmvUtil.getAcquirerRequiredTlvData()
            val track3 = Utils.byteArrayToAsciiString(TransData.track3, 0 ,TransData.track3Len)
            log.appendLine(logClassName, "track3 :: $track3")
            TransData.addHexStrIntoTransDB("DF55", track3)
            //TODO PINBLOCK
            if (TransData.encPinBlock !== "") {
                TransData.addHexStrIntoTransDB("DF52", TransData.encPinBlock)
            }

            IsoBatchInfoRepo.getBatchInfo(context, "isoTpduHeaderTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.TPDU, it.value)
            } ?: run { "" }

            IsoBatchInfoRepo.getBatchInfo(context, "niiTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB("DF24", it.value)
            } ?: run { "" }

            val strSchemeTagWithSchemeId = "${TransData.schemeTag}-${TransData.schemeId}"
            val strPosEntryMode = IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithSchemeId)?.let {
                log.appendLine(logClassName, "Obtained PosEntry :: $strSchemeTagWithSchemeId")
                it.value
            } ?: run {
                val strSchemeTagWithMti = "${TransData.schemeTag}-${isoInfoModel.mti}"
                IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithMti)?.let {
                    log.appendLine(logClassName, "Obtained PosEntry :: $strSchemeTagWithMti")
                    it.value
                } ?: run{
                    IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", TransData.schemeTag)?.let {
                        log.appendLine(logClassName, "Obtained PosEntry :: ${TransData.schemeTag}")
                        it.value
                    } ?: run { "" }
                }
            }
            log.appendLine(logClassName, "strPosEntryMode :: $strPosEntryMode")
            TransData.addHexStrIntoTransDB("DF22", strPosEntryMode)

            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                println("insertPendingTransactionInto")
                insertPendingTransactionInto(context, TransData.txnTypeLabel)
            }

            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)
            thisIsoDbBufLen = dbLen[0]
            log.appendLine(logClassName, "dbBuffLen :: $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff :: ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                checkTransactionStatus(context, log)
                log.appendLine(logClassName, "Transaction Result :: ${TransData.transResult}")
                if (TransData.transResult == TerminalConstants.iso.err.txnApproved) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoPreauthTable(context)
                        //insertIntoPrintReceipt(context)
                    }
                } else if (!TransData.schemeType.equals("MCCS", false) && (TransData.transResult == TerminalConstants.iso.err.communicationTimeout || TransData.respCode.isEmpty())) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoRevBatchTable(context)
                    }
                }
                CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                    insertIntoPrintReceipt(context)
                }
                TransData.transEndDate = HelperCommon.getDateString(EnumDateFormat.yyyyMMddHHmmssSSS.dateFormat)
                invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
            }
        }
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "startTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "endTime :: $finish")
        log.appendLine(logClassName, "timeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------Process Pre-Auth [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processSaleComp(context: Context, log: HelperLog){
        // Refuse before the host is contacted and before any counter advances.
        // This is the only point where nothing has happened yet and nothing needs reconciling.
        if (!StorageGuard.canTransact(context)) {
            StorageGuard.logBlocked(context, "processSaleComp")
            log.appendLine(logClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(context))
            TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
            // TransData.respCode is HEX-ASCII on this codebase (FragmentResult does
            // HexString2ASCII(respCode) -> "TAG_$code", and EmvFragment builds "8A02$respCode").
            // "5A53" is hex for "ZS" -> CardErrorDataEnum.TAG_ZS. A literal "ZS" here is not hex,
            // produces an invalid 8A TLV, and reaches the EMV kernel as a non-numeric REJCODE,
            // which throws across the binder and leaves the kernel unanswered (UI hangs on
            // "waiting for approval").
            TransData.respCode = StorageGuard.RESP_CODE_HEX
            return
        }
        log.appendLine(logClassName, "-----------------Process Sales-Complete [START]-------------------->")
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "salecomp")

        acquirerIsoModel?.let {isoInfoModel ->
            TransData.transIsoInfoModel = isoInfoModel
            log.appendLine(logClassName, "Transform Batch Data")
            invokeFunction(TransData.acqCode.uppercase(), "transformBatchData", context)
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)

            TransData.removeTlvFromTransDb("DF04")
            TransData.removeTlvFromTransDb("BF04")
            TransData.addTlvIntoTransDB("DF04", TransData.amountAuth, 0, TransData.amountAuth.size)
            TransData.addHexStrIntoTransDB("DF11", TransData.stan)

            if (TransData.schemeType.equals("MCCS", true)){
                TransData.removeTlvFromTransDb("DF22")
                TransData.addHexStrIntoTransDB("DF22", "0010")
            }

            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                insertPendingTransactionInto(context, TransData.txnTypeLabel)
            }

            log.appendLine(logClassName, "Forming Iso Message")
            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)

            thisIsoDbBufLen = dbLen[0]
            log.appendLine(logClassName, "dbBuffLen :: $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff :: ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                checkTransactionStatus(context, log)

                log.appendLine(logClassName, "Transaction Result :: ${TransData.transResult}")
                if (TransData.transResult == TerminalConstants.iso.err.txnApproved) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoBatchTable(context)
                        insertIntoPrintReceipt(context)
                        deleteVoidedInPreauthTable(context)
                        deleteVoidPreAuthInPrintReceipt(context)
                    }
                } else if (!TransData.schemeType.equals("MCCS", false) && (TransData.transResult == TerminalConstants.iso.err.communicationTimeout || TransData.respCode.isEmpty())) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoRevBatchTable(context)
                    }
                }
                CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                    TransData.transEndDate = HelperCommon.getDateString(EnumDateFormat.yyyyMMddHHmmssSSS.dateFormat)
                    updateReceiptInfo(context)
                }
                invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
            }
        }
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "startTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "endTime :: $finish")
        log.appendLine(logClassName, "timeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------Process Sales-Complete [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processVoidSale(context: Context, statusArray: BooleanArray, log: HelperLog){
        // Refuse before the host is contacted and before any counter advances.
        // This is the only point where nothing has happened yet and nothing needs reconciling.
        if (!StorageGuard.canTransact(context)) {
            StorageGuard.logBlocked(context, "processVoidSale")
            log.appendLine(logClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(context))
            TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
            // TransData.respCode is HEX-ASCII on this codebase (FragmentResult does
            // HexString2ASCII(respCode) -> "TAG_$code", and EmvFragment builds "8A02$respCode").
            // "5A53" is hex for "ZS" -> CardErrorDataEnum.TAG_ZS. A literal "ZS" here is not hex,
            // produces an invalid 8A TLV, and reaches the EMV kernel as a non-numeric REJCODE,
            // which throws across the binder and leaves the kernel unanswered (UI hangs on
            // "waiting for approval").
            TransData.respCode = StorageGuard.RESP_CODE_HEX
            return
        }
        log.appendLine(logClassName, "-----------------Process Void Sales [START]-------------------->")
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "void")

        acquirerIsoModel?.let {isoInfoModel ->
            TransData.transIsoInfoModel = isoInfoModel
            log.appendLine(logClassName, "Transform Batch Data")
            invokeFunction(TransData.acqCode.uppercase(), "transformBatchData", context)
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)
            TransData.addHexStrIntoTransDB("DF11", TransData.stan)

            log.appendLine(logClassName, "Forming Iso Message")
            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)

            thisIsoDbBufLen = dbLen[0]

            log.appendLine(logClassName, "dbBuffLen :: $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff :: ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                checkTransactionStatus(context, log)
                invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
            }
            log.appendLine(logClassName, "Transaction Result :: ${TransData.transResult}")
            if (TransData.transResult == TerminalConstants.iso.err.txnApproved) {
                CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                    deleteVoidedInBatchTable(context)
                    insertIntoPrintReceipt(context)
                    deleteVoidedInPrintReceipt(context)
                }
            }
        }

        //TODO Check Speed
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "startTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "endTime :: $finish")
        log.appendLine(logClassName, "timeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------Process Void Sales [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processVoidPreauth(context: Context, statusArray: BooleanArray, log: HelperLog){
        // Refuse before the host is contacted and before any counter advances.
        // This is the only point where nothing has happened yet and nothing needs reconciling.
        if (!StorageGuard.canTransact(context)) {
            StorageGuard.logBlocked(context, "processVoidPreauth")
            log.appendLine(logClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(context))
            TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
            // TransData.respCode is HEX-ASCII on this codebase (FragmentResult does
            // HexString2ASCII(respCode) -> "TAG_$code", and EmvFragment builds "8A02$respCode").
            // "5A53" is hex for "ZS" -> CardErrorDataEnum.TAG_ZS. A literal "ZS" here is not hex,
            // produces an invalid 8A TLV, and reaches the EMV kernel as a non-numeric REJCODE,
            // which throws across the binder and leaves the kernel unanswered (UI hangs on
            // "waiting for approval").
            TransData.respCode = StorageGuard.RESP_CODE_HEX
            return
        }
        log.appendLine(logClassName, "-----------------Process Void Pre-Auth [START]-------------------->")
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "void_preauth")

        acquirerIsoModel?.let {isoInfoModel ->
            TransData.transIsoInfoModel = isoInfoModel
            log.appendLine(logClassName, "Transform Batch Data")
            invokeFunction(TransData.acqCode.uppercase(), "transformBatchData", context)
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)
            TransData.addHexStrIntoTransDB("DF11", TransData.stan)

            log.appendLine(logClassName, "Forming Iso Message")
            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)

            thisIsoDbBufLen = dbLen[0]
            log.appendLine(logClassName, "dbBuffLen :: $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff :: ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                checkTransactionStatus(context, log)
                invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
            }
            log.appendLine(logClassName, "Transaction Result :: ${TransData.transResult}")
            if (TransData.transResult == TerminalConstants.iso.err.txnApproved) {
                CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                    deleteVoidedInPreauthTable(context)
                    deleteVoidPreAuthInPrintReceipt(context)
                    insertIntoPrintReceipt(context)
                }
            }
        }

        //TODO Check Speed
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "startTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "endTime :: $finish")
        log.appendLine(logClassName, "timeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------Process Void Pre-Auth [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processVoidSaleComp(context: Context, statusArray: BooleanArray, log: HelperLog){
        // Refuse before the host is contacted and before any counter advances.
        // This is the only point where nothing has happened yet and nothing needs reconciling.
        if (!StorageGuard.canTransact(context)) {
            StorageGuard.logBlocked(context, "processVoidSaleComp")
            log.appendLine(logClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(context))
            TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
            // TransData.respCode is HEX-ASCII on this codebase (FragmentResult does
            // HexString2ASCII(respCode) -> "TAG_$code", and EmvFragment builds "8A02$respCode").
            // "5A53" is hex for "ZS" -> CardErrorDataEnum.TAG_ZS. A literal "ZS" here is not hex,
            // produces an invalid 8A TLV, and reaches the EMV kernel as a non-numeric REJCODE,
            // which throws across the binder and leaves the kernel unanswered (UI hangs on
            // "waiting for approval").
            TransData.respCode = StorageGuard.RESP_CODE_HEX
            return
        }
        log.appendLine(logClassName, "-----------------Process Void Sales-Complete [START]-------------------->")
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "void_salecomp")

        acquirerIsoModel?.let {isoInfoModel ->
            TransData.transIsoInfoModel = isoInfoModel
            log.appendLine(logClassName, "Transform Batch Data")
            invokeFunction(TransData.acqCode.uppercase(), "transformBatchData", context)
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)
            TransData.addHexStrIntoTransDB("DF11", TransData.stan)

            log.appendLine(logClassName, "Forming Iso Message")
            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)

            thisIsoDbBufLen = dbLen[0]
            log.appendLine(logClassName, "dbBuffLen :: $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff :: ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                checkTransactionStatus(context, log)
                invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
            }
            log.appendLine(logClassName, "Transaction Result :: ${TransData.transResult}")
            if (TransData.transResult == TerminalConstants.iso.err.txnApproved) {
                CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                    deleteVoidedInBatchTable(context)
                    deleteVoidedSaleCompInPrintReceipt(context)
                    insertIntoPrintReceipt(context)
                }
            }
        }

        //TODO Check Speed
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "startTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "endTime :: $finish")
        log.appendLine(logClassName, "timeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------Process Void Sales-Complete [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processEppSale(context: Context, log: HelperLog) {
        // Refuse before the host is contacted and before any counter advances.
        // This is the only point where nothing has happened yet and nothing needs reconciling.
        if (!StorageGuard.canTransact(context)) {
            StorageGuard.logBlocked(context, "processEppSale")
            log.appendLine(logClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(context))
            TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
            // TransData.respCode is HEX-ASCII on this codebase (FragmentResult does
            // HexString2ASCII(respCode) -> "TAG_$code", and EmvFragment builds "8A02$respCode").
            // "5A53" is hex for "ZS" -> CardErrorDataEnum.TAG_ZS. A literal "ZS" here is not hex,
            // produces an invalid 8A TLV, and reaches the EMV kernel as a non-numeric REJCODE,
            // which throws across the binder and leaves the kernel unanswered (UI hangs on
            // "waiting for approval").
            TransData.respCode = StorageGuard.RESP_CODE_HEX
            return
        }
        log.appendLine(logClassName, "-----------------Process Epp-Sales [START]-------------------->")
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "epp_sale")

        //TODO Check Speed
        log.appendLine(logClassName, "SchemeID :: ${TransData.schemeId}")
        acquirerIsoModel?.let { isoInfoModel ->
            TransData.transIsoInfoModel = isoInfoModel
            TransData.schemeTag = "visam"

            val track2 = EmvUtil.readTrack2()
            val track2Delimiter = track2.indexOf("D")
            val cardMask = track2.substring(0, track2Delimiter)
            val md = TransData.transDateAsci.substring(4, 8)
            val hhmmss = TransData.transDateAsci.substring(8)

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same invoiceNo to two concurrent flows.
            TransData.invoiceNo = IsoBatchInfoRepo.allocateCounter(context, "invoiceNo", "pos")
            log.appendLine(logClassName, "Invoice No :: ${TransData.invoiceNo}")

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same stan to two concurrent flows.
            TransData.stan = IsoBatchInfoRepo.allocateCounter(context, "stan", TransData.schemeTag)
            log.appendLine(logClassName, "Stan :: ${TransData.stan}")

            TransData.batchNo = IsoBatchInfoRepo.getBatchInfo(context, "batchNo", TransData.schemeTag)?.value ?: "000001"
            log.appendLine(logClassName, "Batch No :: ${TransData.batchNo}")
            TransData.maskedPan = Utils.hideCardDetails(cardMask)
            log.appendLine(logClassName, "Masked Pan :: ${TransData.maskedPan}")
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_MASKBCD, Utils.ASCIItoHexString(Utils.hideCardDetails(cardMask)))
            TransData.hashedPan = cardMask.substring(0,9)
            log.appendLine(logClassName, "Hashed Pan :: ${TransData.hashedPan}")
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_HASH, Utils.ASCIItoHexString(TransData.hashedPan))
            if(track2Delimiter > 0){
                val cardExp = track2.substring(track2Delimiter + 1, track2Delimiter + 5)
                TransData.addHexStrWithPadIntoTransDB("DF02", cardMask, "F")
                TransData.addHexStrIntoTransDB("DF14", cardExp)
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.PANSTRING, cardMask)
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.EXPDATE, cardExp)
            }
            TransData.addHexStrIntoTransDB("DA", TransData.schemeId)
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)
            TransData.addTlvIntoTransDB("DF04", TransData.amountAuth, 0, TransData.amountAuth.size)
            TransData.addHexStrIntoTransDB("DF11", TransData.stan)
            TransData.addHexStrIntoTransDB("DF12", hhmmss)
            TransData.addHexStrIntoTransDB("DF13", md)
            EmvUtil.getPbocData("5F34", true)?.let {
                TransData.addHexStrIntoTransDB("DF23", Utils.paddingWith(it, "0", 4, false))
            }
            TransData.addHexStrIntoTransDB("DF25", isoInfoModel.posCondition)
            TransData.addHexStrWithPadIntoTransDB("DF35",track2, "F")
            TransData.addHexStrIntoTransDB("DF41", HexUtil.str2HexStr(TransData.tid))
            TransData.addHexStrIntoTransDB("DF42", HexUtil.str2HexStr(TransData.mid))
            TransData.addHexStrIntoTransDB("DF60", HexUtil.str2HexStr(TransData.batchNo))
            TransData.addHexStrIntoTransDB("DF62", HexUtil.str2HexStr(TransData.invoiceNo))
            val track3 = Utils.byteArrayToAsciiString(TransData.track3, 0 ,TransData.track3Len)
            log.appendLine(logClassName, "track3 :: $track3")
            TransData.addHexStrIntoTransDB("DF55", track3)

            //TODO PINBLOCK
            if (TransData.encPinBlock !== "") {
                TransData.addHexStrIntoTransDB("DF52", TransData.encPinBlock)
            }

            IsoBatchInfoRepo.getBatchInfo(context, "isoTpduHeaderTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.TPDU, it.value)
            } ?: run { "" }

            IsoBatchInfoRepo.getBatchInfo(context, "niiTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB("DF24", it.value)
            } ?: run { "" }

            val strSchemeTagWithSchemeId = "${TransData.schemeTag}-${TransData.schemeId}"
            val strPosEntryMode = IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithSchemeId)?.let {
                log.appendLine(logClassName, "Obtained PosEntry :: $strSchemeTagWithSchemeId")
                it.value
            } ?: run {
                val strSchemeTagWithMti = "${TransData.schemeTag}-${isoInfoModel.mti}"
                IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithMti)?.let {
                    log.appendLine(logClassName, "Obtained PosEntry :: $strSchemeTagWithMti")
                    it.value
                } ?: run{
                    IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", TransData.schemeTag)?.let {
                        log.appendLine(logClassName, "Obtained PosEntry :: ${TransData.schemeTag}")
                        it.value
                    } ?: run { "" }
                }
            }
            log.appendLine(logClassName, "strPosEntryMode :: $strPosEntryMode")
            TransData.addHexStrIntoTransDB("DF22", strPosEntryMode)

            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                insertPendingTransactionInto(context, TransData.txnTypeLabel)
            }

            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)
            thisIsoDbBufLen = dbLen[0]
            log.appendLine(logClassName, "dbBuffLen :: $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff :: ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                checkTransactionStatus(context, log)

                log.appendLine(logClassName, "Transaction Result :: ${TransData.transResult}")
                if (TransData.transResult == TerminalConstants.iso.err.txnApproved) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoBatchTable(context)
                        //insertIntoPrintReceipt(context)
                    }
                } else if (TransData.transResult == TerminalConstants.iso.err.communicationTimeout || TransData.respCode.isEmpty()) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoRevBatchTable(context)
                    }
                }
            }
            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                insertIntoPrintReceipt(context)
            }
            TransData.transEndDate = HelperCommon.getDateString(EnumDateFormat.yyyyMMddHHmmssSSS.dateFormat)
            invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
        }
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "startTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "endTime :: $finish")
        log.appendLine(logClassName, "timeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------Process Epp-Sales [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    fun processAutoSettlement(log: HelperLog, mContext: Context, settleProduct: DbModelProductList, batchNo: String): Boolean {
        log.appendLine(logClassName, "------------------ Processing Auto Settlement [Start] ------------------ ")
        var finalResult = false
        //TODO Reversal Before Settlement
        var reversalResult = true
        val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(settleProduct.AcqCode, "reversal") ?: return finalResult
        val reversalBatch = ReversalBatchTableRepo.getBatchData(mContext, listOf("batchNo", "mid", "tid"), arrayOf(batchNo, settleProduct.AcqMid, settleProduct.AcqTid))
        log.appendLine(logClassName, "Reversal Batch List::(${reversalBatch.size})")

        if(reversalBatch.isNotEmpty()) {
            for (tempFor in reversalBatch) {
                log.appendLine(logClassName, "Reversal Batch Model::(${Gson().toJson(tempFor)})")
                TransData.reset()

                val productModel = ProductListRepo.getSingle(mContext, listOf("AcqCode", "AcqMid", "AcqTid"), arrayOf(settleProduct.AcqCode, settleProduct.AcqMid, settleProduct.AcqTid))
                TransData.acqCode = settleProduct.AcqCode
                TransData.product = settleProduct.Product
                TransData.mid = settleProduct.AcqMid
                TransData.tid = settleProduct.AcqTid
                TransData.schemeTag = "visam"
                TransData.txnTypeLabel = "Reversal"
                TransData.ksn = productModel?.Ksn ?: ""
                TransData.pinKsn = productModel?.PinKsn ?: ""
                TransData.stan = tempFor.stan
                TransData.invoiceNo = tempFor.invNo
                TransData.batchNo = tempFor.batchNo

                val revResult = processReversal(mContext, false, acquirerRevIsoModel, tempFor.batchData, true, log)
                if(reversalResult && revResult == null){
                    reversalResult = false
                }
            }
        }
        //TODO Reversal Fail Abort Settlement
        if(!reversalResult) {
            log.appendLine(logClassName, "Reversal Fail, Settlement Abort)")
            return finalResult
        }

        log.appendLine(logClassName, "Reset Iso Batch Long last postingDt")
        IsoBatchLongInfoRepo.updateBatchLongInfo(mContext, "", "postingDt", "last")

        log.appendLine(logClassName, "Preparing Settlement...")
        val recordSummary = SettlementSummaryRepo.getSelectiveData(
            mContext,
            listOf("acq_code", "mid", "tid"),
            arrayOf(settleProduct.AcqCode, settleProduct.AcqMid, settleProduct.AcqTid)
        )

        var txnCount = ""
        var txnTotal = ""
        var voidTxnCount = ""
        var voidTxnTotal = ""
        //TODO txnTotal
        val summaryRecordTxnTotal = SettlementSummaryRepo.getSelectiveData(
            mContext,
            ArrayList(listOf("acq_code", "mid", "tid", "tag")),
            arrayOf(settleProduct.AcqCode, settleProduct.AcqMid, settleProduct.AcqTid, "txnTotal")
        )
        if (summaryRecordTxnTotal.isNotEmpty()) {
            val tempTxnTotal = AtomicInteger()
            summaryRecordTxnTotal.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
                tempTxnTotal.addAndGet(
                    Utils.atoi(value)
                )
            })
            txnTotal = Utils.getActualAmount(tempTxnTotal.toString())
            log.appendLine(logClassName, "\ttxnTotal = $txnTotal")
        }
        //TODO txnCount
        val summaryRecordTxnCount = SettlementSummaryRepo.getSelectiveData(
            mContext,
            ArrayList(listOf("acq_code", "mid", "tid", "tag")),
            arrayOf(settleProduct.AcqCode, settleProduct.AcqMid, settleProduct.AcqTid, "txnCount")
        )
        if (summaryRecordTxnCount.isNotEmpty()) {
            val tempTxnCount = AtomicInteger()
            summaryRecordTxnCount.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
                tempTxnCount.addAndGet(
                    Utils.atoi(value)
                )
            })
            txnCount = tempTxnCount.toString()
            log.appendLine(logClassName, "\ttxnCount = $txnCount")
        }
        //TODO CASHOUT
        val summaryRecordCashOutTotal = SettlementSummaryRepo.getSelectiveData(
            mContext,
            ArrayList(listOf("acq_code", "mid", "tid", "tag")),
            arrayOf(settleProduct.AcqCode, settleProduct.AcqMid, settleProduct.AcqTid, "cashOutTotal")
        )
        if (summaryRecordCashOutTotal.isNotEmpty()) {
            val tempTxnTotal = AtomicInteger()
            summaryRecordCashOutTotal.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
                tempTxnTotal.addAndGet(
                    Utils.atoi(value)
                )
            })
            val salesTotal = txnTotal.replace(".", "")
            tempTxnTotal.addAndGet(Utils.atoi(salesTotal))
            txnTotal = Utils.getActualAmount(tempTxnTotal.toString())
            log.appendLine(logClassName, "\tcashOutTotal = $txnTotal")
        }
        //TODO voidTxnTotal
        val summaryRecordVoidTxnTotal = SettlementSummaryRepo.getSelectiveData(
            mContext,
            ArrayList(listOf("acq_code", "mid", "tid", "tag")),
            arrayOf(settleProduct.AcqCode, settleProduct.AcqMid, settleProduct.AcqTid, "voidTxnTotal")
        )
        if (summaryRecordVoidTxnTotal.isNotEmpty()) {
            val tempVoidTxnTotal = AtomicInteger()
            summaryRecordVoidTxnTotal.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
                tempVoidTxnTotal.addAndGet(
                    Utils.atoi(value)
                )
            })
            voidTxnTotal = Utils.getActualAmount(tempVoidTxnTotal.toString())
            log.appendLine(logClassName, "\tvoidTxnTotal = $voidTxnTotal")
        }
        //TODO voidTxnCount
        val summaryRecordVoidTxnCount = SettlementSummaryRepo.getSelectiveData(
            mContext,
            ArrayList(listOf("acq_code", "mid", "tid", "tag")),
            arrayOf(settleProduct.AcqCode, settleProduct.AcqMid, settleProduct.AcqTid, "voidTxnCount")
        )
        if (summaryRecordVoidTxnCount.isNotEmpty()) {
            val tempVoidTxnCount = AtomicInteger()
            summaryRecordVoidTxnCount.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
                tempVoidTxnCount.addAndGet(
                    Utils.atoi(value)
                )
            })
            voidTxnCount = tempVoidTxnCount.toString()
            log.appendLine(logClassName, "\tvoidTxnCount = $voidTxnCount")
        }

        val settlementValueString = constructSettlementValueString(txnCount, txnTotal, "")
        log.appendLine(logClassName, "\tsettlementValueString = $settlementValueString")
        val productModel = ProductListRepo.getSingle(mContext, listOf("AcqCode", "AcqMid", "AcqTid"), arrayOf(settleProduct.AcqCode, settleProduct.AcqMid, settleProduct.AcqTid))
        TransData.reset()
        TransData.acqCode = settleProduct.AcqCode
        TransData.product = settleProduct.Product
        TransData.mid = settleProduct.AcqMid
        TransData.tid = settleProduct.AcqTid
        TransData.schemeTag = "visam"
        TransData.txnTypeLabel = "Settle"
        TransData.ksn = productModel?.Ksn ?: ""
        TransData.pinKsn = productModel?.PinKsn ?: ""
        processSettlement(mContext, settleProduct, settlementValueString, log)

        if (TransData.transResult < 0) {
            log.appendLine(logClassName, "Fail to Settle")
            log.appendLine(logClassName, "ResponseCode :: [${Utility.HexString2ASCII(TransData.respCode)}]")
            finalResult = false
        } else {
            finalResult = true
        }

        log.appendLine(logClassName, "------------------ Processing Auto Settlement [End] ------------------ ")
        log.logToFile(EnumLogFileName.TerminaLog)
        return finalResult
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processSettlement(context: Context, settleProduct: DbModelProductList, settlementValueString: String, log: HelperLog): Boolean {
        val store = DataStoreManager(context)
        CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
            store.putBoolean(PrefKeys.settlementBlock, true)
        }
        log.appendLine(logClassName, "Settlement BLOCK = TRUE (START)")
        log.appendLine(logClassName, "-----------------Process Settlement [START]-------------------->")
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "settlement")
        acquirerIsoModel?.let { isoInfoModel ->
            val txnDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
            TransData.transDateAsci = txnDt

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same invoiceNo to two concurrent flows.
            TransData.invoiceNo = IsoBatchInfoRepo.allocateCounter(context, "invoiceNo", "pos")

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same stan to two concurrent flows.
            TransData.stan = IsoBatchInfoRepo.allocateCounter(context, "stan", TransData.schemeTag)

            TransData.batchNo = IsoBatchInfoRepo.getBatchInfo(context, "batchNo", TransData.schemeTag)?.value ?: "000001"
            settleProduct.BatchNo = TransData.batchNo
            log.appendLine(logClassName, "Batch No :: ${TransData.batchNo}")

            IsoBatchInfoRepo.getBatchInfo(context, "isoTpduHeaderTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.TPDU, it.value)
            }
            IsoBatchInfoRepo.getBatchInfo(context, "niiTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB("DF24", it.value)
            }
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)
            TransData.addHexStrIntoTransDB("DF11", TransData.stan)
            TransData.addHexStrIntoTransDB("DF41", HexUtil.str2HexStr(TransData.tid))
            TransData.addHexStrIntoTransDB("DF42", HexUtil.str2HexStr(TransData.mid))
            TransData.addHexStrIntoTransDB("DF60", HexUtil.str2HexStr(TransData.batchNo))

            if (TransData.acqCode.equals("BSN_CARDZONE",  true)) {
                val tempSettlementValueString = settlementValueString.substring(0, 30)
                TransData.addHexStrIntoTransDB("DF63", HexUtil.str2HexStr(tempSettlementValueString))
            } else {
                TransData.addHexStrIntoTransDB("DF63", HexUtil.str2HexStr(settlementValueString))
            }

            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)
            thisIsoDbBufLen = dbLen[0]
            log.appendLine(logClassName, "dbBuffLen :: $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff :: ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
                checkSettlementStatus(context, settleProduct, settlementValueString, log)
            }
        }
        println("saveSettlementReceipt")
        saveSettlementReceipt(context)
        CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
            AppServices.receiptUploadToTms(context)
        }

        if(TransData.transResult == TerminalConstants.iso.err.txnApproved){
            val (_, requireSignOn, _, _) = ServiceHolder.getAcquirerSetting()
            if(requireSignOn){
                //TODO
                //val iSignOnResp = CubeActivity().proceedSignOn()
                //Utils.printLog("ISOENGINE:SignOn. Resp=$iSignOnResp")
                //log.appendLine(logClassName, "ISOENGINE:SignOn. Resp=$iSignOnResp")
                val signOnResp = processSignOn(context, log)
                log.appendLine(logClassName, "SignOn Resp. Resp :: $signOnResp")
            }
        }

        //TODO Check Speed
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "startTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "endTime :: $finish")
        log.appendLine(logClassName, "timeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------Process Settlement [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
        return true
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processBatchUpload(context: Context, settleProduct: DbModelProductList, settlementValueString: String, log: HelperLog): Int {
        //TODO End Batch Upload if Fail Occur
        log.appendLine(logClassName, "-----------------PROCESS BATCH UPLOAD [START]-------------------->")
        TransData.loadingTitle = "Batch Upload"
        val findIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "batch_upload")
        log.appendLine(logClassName, "batchUpload IsoHelper:: $findIsoModel")

        findIsoModel?.let { isoModel ->
            val batchTransList = BatchTableRepo.getBatchData(context, listOf("batchNo", "mid", "tid"), arrayOf(settleProduct.BatchNo, settleProduct.AcqMid, settleProduct.AcqTid))
            log.appendLine(logClassName, "batchTransList :: ${batchTransList.size}")

            var currItem = 0
            for (transItem in batchTransList){
                TransData.loadingMessage = "Uploading...(${++currItem}/${batchTransList.size})"
                var retryCount = 5
                val (_, _, _, _, retryFailBatchUpload) = ServiceHolder.getAcquirerSetting()
                // D7 -- batch upload reads each record's PAN from the batch table, the same
                // source a void uses, and nothing else registers it on this path. Without this the
                // 0320 forming logs DF02, DF63 and DE2 in the clear -- and so does the line below,
                // which prints the whole batch record.
                try {
                    val bPan = ByteArray(12)
                    val panLen = EmvTag().getValueFrom(HexUtil.hexStringToByte(transItem.batchData), "DF02", bPan)
                    if (panLen > 0) {
                        LogRedact.registerCardData(HexUtil.bytesToHexString(bPan, 0, panLen).replace("F", ""), null)
                    }
                } catch (ex: Exception) {
                    // Redaction must never be the reason a settlement fails.
                    ex.printStackTrace()
                }
                log.appendLine(logClassName, "Batch Upload Transaction :: $transItem")
                val emvTag = EmvTag()

                while(retryCount > 0){
                    var tempIsoDbLen = IntArray(2)
                    var tempIsoDb = ByteArray(6000)
                    val variantMap = invokeFunction(TransData.acqCode.uppercase(), "formIsoIsolate", context, log, isoModel, transItem.batchData, tempIsoDb, tempIsoDbLen) as MutableMap<String, String>

                    if(tempIsoDbLen[0] > 0) {
                        val respDbLen = IntArray(2)
                        val respIsoDb = ByteArray(6000)
                        sendToHost(context, tempIsoDb, tempIsoDbLen[0], true, respIsoDb, respDbLen, log)

                        tempIsoDbLen = IntArray(2)
                        tempIsoDb = ByteArray(6000)
                        invokeFunction(TransData.acqCode.uppercase(), "parseIsoRespIsolate", log, respIsoDb, 2, respDbLen[0], tempIsoDb, tempIsoDbLen, variantMap)
                    }

                    val tempByte = ByteArray(2048)
                    val tempLen = emvTag.getValueFrom(tempIsoDb, "BF39", tempByte)
                    if(tempLen > 0){
                        val stringRespCode = HexUtil.bytesToHexString(tempByte, 0, tempLen)
                        log.appendLine(logClassName, "stringRespCode :: $stringRespCode")
                        val responseCode = stringRespCode.toInt(16)
                        retryCount = if(responseCode != 0x3030 && retryFailBatchUpload) {
                            log.appendLine(logClassName, "retryFailBatchUpload :: $retryFailBatchUpload")
                            maxOf(0, retryCount - 2)
                        } else {
                            0
                        }
                    } else {
                        retryCount--
                    }
                }
                invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
                Thread.sleep(100)
            }
        }
        log.appendLine(logClassName, "-----------------PROCESS BATCH UPLOAD [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
        return processSettlementTrailer(context, settleProduct, settlementValueString, log)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processSettlementTrailer(context: Context, settleProduct: DbModelProductList, settlementValueString: String, log: HelperLog): Int {
        log.appendLine(logClassName, "-----------------PROCESS SETTLEMENT TRAILER [START]-------------------->")
        var responseCode: Int = 0x3936
        val findIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "settle_trailer")
        TransData.loadingMessage = "Settlement..."
        findIsoModel?.let { isoModel ->
            val emvTag = EmvTag()
            var retryCount = 5

            while(retryCount > 0){
                var tempDbLen = IntArray(2)
                var tempIsoDb = ByteArray(6000)
                val lastSettleString = HexUtil.bytesToHexString(TransData.transactionDb, 0 ,TransData.transactionDbLen + 2)
                val variantMap = invokeFunction(TransData.acqCode.uppercase(), "formIsoIsolate", context, log, isoModel, lastSettleString, tempIsoDb, tempDbLen) as MutableMap<String, String>

                if(tempDbLen[0] > 0) {
                    val respDbLen = IntArray(2)
                    val respIsoDb = ByteArray(6000)
                    sendToHost(context, tempIsoDb, tempDbLen[0], true, respIsoDb, respDbLen, log)

                    tempDbLen = IntArray(2)
                    tempIsoDb = ByteArray(6000)
                    invokeFunction(TransData.acqCode.uppercase(), "parseIsoRespIsolate", log, respIsoDb, 2, respDbLen[0], tempIsoDb, tempDbLen, variantMap)
                }

                val tempByte = ByteArray(2048)
                val tempLen = emvTag.getValueFrom(tempIsoDb, "BF39", tempByte)
                if(tempLen > 0){
                    val stringRespCode = HexUtil.bytesToHexString(tempByte, 0, tempLen)
                    retryCount = 0
                    log.appendLine(logClassName, "stringRespCode :: $stringRespCode")
                    responseCode = stringRespCode.toInt(16)

                    when(responseCode) {
                        0x3030 -> {
                            val store = DataStoreManager(context)
                            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                                store.putBoolean(PrefKeys.settlementBlock, false)
                            }
                            log.appendLine(logClassName, "Settlement BLOCK = FALSE (END)")
                        }
                    }
                } else {
                    retryCount--
                }
            }
            invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
        }
        log.appendLine(logClassName, "-----------------PROCESS SETTLEMENT TRAILER [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
        return responseCode
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processReversal(context: Context, prevResult: Boolean, isoInfoModel: IsoInfoModel, prevIsoString: String, isDeleteData: Boolean, log: HelperLog): Int? {
        log.appendLine(logClassName, "-----------------PROCESS REVERSAL [START]-------------------->")
        Thread.sleep(500)

        if(!TransData.transIsoInfoModel?.processCode.isNullOrEmpty()){
            log.appendLine(logClassName, "TransIsoInfoModel::${TransData.transIsoInfoModel?.processCode}")
            isoInfoModel.processCode = TransData.transIsoInfoModel?.processCode ?: isoInfoModel.processCode
        }
        log.appendLine(logClassName, "isoInfoModel::${isoInfoModel.processCode}")
        var responseCode: Int? = null

        val emvTag = EmvTag()
        var revDbLen = IntArray(2)
        var reversalIsoDb = ByteArray(6000)
        val variantMap = invokeFunction(TransData.acqCode.uppercase(), "formIsoIsolate", context, log, isoInfoModel, prevIsoString, reversalIsoDb, revDbLen) as MutableMap<String, String>
        if(revDbLen[0] > 0) {
            val respDbLen = IntArray(2)
            val respIsoDb = ByteArray(6000)
            sendToHost(context, reversalIsoDb, revDbLen[0], true, respIsoDb, respDbLen, log)

            revDbLen = IntArray(2)
            reversalIsoDb = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "parseIsoRespIsolate", log, respIsoDb, 2, respDbLen[0], reversalIsoDb, revDbLen, variantMap)
            invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
        }

        val tempByte = ByteArray(2048)
        val tempLen = emvTag.getValueFrom(reversalIsoDb, "BF39", tempByte)
        if(tempLen > 0){
            val stringRespCode = HexUtil.bytesToHexString(tempByte, 0, tempLen)
            log.appendLine(logClassName, "stringRespCode :: $stringRespCode")
            responseCode = stringRespCode.toInt(16)
            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                if(isDeleteData) {
                    deleteSuccessReversal(context)
                }

                if(prevResult) {
                    deleteVoidedInBatchTable(context)
                    deleteVoidedInPrintReceipt(context)
                    updateSettlementValue(context, log, true)
                    //updateReceiptInfo(context)
                }
            }
        }
        log.appendLine(logClassName, "-----------------PROCESS REVERSAL [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
        return responseCode
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processMoto(context: Context, log: HelperLog) {
        // Refuse before the host is contacted and before any counter advances.
        // This is the only point where nothing has happened yet and nothing needs reconciling.
        if (!StorageGuard.canTransact(context)) {
            StorageGuard.logBlocked(context, "processMoto")
            log.appendLine(logClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(context))
            TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
            // TransData.respCode is HEX-ASCII on this codebase (FragmentResult does
            // HexString2ASCII(respCode) -> "TAG_$code", and EmvFragment builds "8A02$respCode").
            // "5A53" is hex for "ZS" -> CardErrorDataEnum.TAG_ZS. A literal "ZS" here is not hex,
            // produces an invalid 8A TLV, and reaches the EMV kernel as a non-numeric REJCODE,
            // which throws across the binder and leaves the kernel unanswered (UI hangs on
            // "waiting for approval").
            TransData.respCode = StorageGuard.RESP_CODE_HEX
            return
        }
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "moto")

        //TODO Check Speed
        println("SchemeID -> ${TransData.schemeId}")
        log.appendLine(logClassName, "processMoto")
        log.appendLine(logClassName, "SchemeID -> ${TransData.schemeId}")
        acquirerIsoModel?.let { isoInfoModel ->
            TransData.schemeTag = "visam"
            val md = TransData.transDateAsci.substring(4, 8)
            val hhmmss = TransData.transDateAsci.substring(8)

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same invoiceNo to two concurrent flows.
            TransData.invoiceNo = IsoBatchInfoRepo.allocateCounter(context, "invoiceNo", "pos")
            log.appendLine(logClassName, "Invoice No >> ${TransData.invoiceNo}")

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same stan to two concurrent flows.
            TransData.stan = IsoBatchInfoRepo.allocateCounter(context, "stan", TransData.schemeTag)
            log.appendLine(logClassName, "Stan >> ${TransData.stan}")

            TransData.batchNo = IsoBatchInfoRepo.getBatchInfo(context, "batchNo", TransData.schemeTag)?.value ?: "000001"
            log.appendLine(logClassName, "Batch No >> ${TransData.batchNo}")
            log.appendLine(logClassName, "Masked Pan >> ${TransData.maskedPan}")
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_MASKBCD, Utils.ASCIItoHexString(TransData.maskedPan))
            log.appendLine(logClassName, "Hashed Pan >> ${TransData.hashedPan}")
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_HASH, Utils.ASCIItoHexString(TransData.hashedPan))

            val cardNo = Utils.byteArrayToAsciiString(TransData.pan,0,TransData.panLen)
            TransData.addHexStrWithPadIntoTransDB("DF02", cardNo, "F")
            val cardExpDt = Utils.byteArrayToAsciiString(TransData.expirationDate)
            TransData.addHexStrIntoTransDB("DF14", cardExpDt)

            TransData.addHexStrIntoTransDB("DA", TransData.schemeId)
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)
            TransData.addTlvIntoTransDB("DF04", TransData.amountAuth, 0, TransData.amountAuth.size)
            TransData.addHexStrIntoTransDB("DF11", TransData.stan)
            TransData.addHexStrIntoTransDB("DF12", hhmmss)
            TransData.addHexStrIntoTransDB("DF13", md)
            TransData.addHexStrIntoTransDB("DF25", isoInfoModel.posCondition)
            TransData.addHexStrIntoTransDB("DF41", HexUtil.str2HexStr(TransData.tid))
            TransData.addHexStrIntoTransDB("DF42", HexUtil.str2HexStr(TransData.mid))
            TransData.addHexStrIntoTransDB("DF60", HexUtil.str2HexStr(TransData.batchNo))
            TransData.addHexStrIntoTransDB("DF62", HexUtil.str2HexStr(TransData.invoiceNo))

            val de63_01 = "X"
            val de63_02 = String.format("%1$" + 6 + "s", "")
            val de63_03 = String.format("%1$" + 28 + "s", "")
            val de63_04 = "09"
            val de63_05 = String.format("%1$" + 36 + "s", "")
            val de63_06 = "01"
            val de63 = Utils.ASCIItoHexString(
                de63_01 + de63_02 + de63_03 + de63_04 + de63_05 + de63_06
            )
            println("Generating DE63 -> $de63")
            log.appendLine(logClassName, "Generating DE63 -> ", de63)
            TransData.addHexStrIntoTransDB("DF63", de63)

            IsoBatchInfoRepo.getBatchInfo(context, "isoTpduHeaderTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.TPDU, it.value)
            } ?: run { "" }

            IsoBatchInfoRepo.getBatchInfo(context, "niiTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB("DF24", it.value)
            } ?: run { "" }

            val strSchemeTagWithSchemeId = "${TransData.schemeTag}-${TransData.schemeId}"
            val strPosEntryMode = IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithSchemeId)?.let {
                println("Obtained PosEntry -> $strSchemeTagWithSchemeId")
                log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithSchemeId")
                it.value
            } ?: run {
                val strSchemeTagWithMti = "${TransData.schemeTag}-${isoInfoModel.mti}"
                IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithMti)?.let {
                    println("Obtained PosEntry -> $strSchemeTagWithMti")
                    log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithMti")
                    it.value
                } ?: run{
                    IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", TransData.schemeTag)?.let {
                        println("Obtained PosEntry -> ${TransData.schemeTag}")
                        log.appendLine(logClassName, "Obtained PosEntry -> ${TransData.schemeTag}")
                        it.value
                    } ?: run { "" }
                }
            }
            println("strPosEntryMode -> $strPosEntryMode")
            log.appendLine(logClassName, "strPosEntryMode -> $strPosEntryMode")
            TransData.addHexStrIntoTransDB("DF22", strPosEntryMode)

            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                insertPendingTransactionInto(context, TransData.txnTypeLabel)
            }

            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)
            thisIsoDbBufLen = dbLen[0]
            println("dbBuffLen => $thisIsoDbBufLen")
            println("thisIsoDbBuff -> ")
            println(HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            log.appendLine(logClassName, "dbBuffLen => $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff -> ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                checkTransactionStatus(context, log)

                println("Transaction Result -> ${TransData.transResult}")
                log.appendLine(logClassName, "Transaction Result -> ${TransData.transResult}")
                if (TransData.transResult == TerminalConstants.iso.err.txnApproved) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoBatchTable(context)
                        insertIntoPrintReceipt(context)
                    }
                } else if (TransData.respCode.isEmpty()) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoRevBatchTable(context)
                    }
                }
                CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                    TransData.transEndDate = HelperCommon.getDateString(EnumDateFormat.yyyyMMddHHmmssSSS.dateFormat)
                    updateReceiptInfo(context)
                }
                invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
            }
        }
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        println("startTime -> ${TransData.startTime}")
        println("endTime -> $finish")
        println("timeElapsed -> $timeElapsed")

        log.appendLine(logClassName, "startTime -> ${TransData.startTime}")
        log.appendLine(logClassName, "endTime -> $finish")
        log.appendLine(logClassName, "timeElapsed -> $timeElapsed")
        log.appendLine(logClassName, "-----------------Process MOTO [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun sendToHost(context: Context, isoByteArray: ByteArray, isoByteArrayLen: Int, isReturnValue: Boolean, returnByte: ByteArray, returnByteArrayLen: IntArray, log: HelperLog){
        val merchantInfo = ServiceHolder.getMerchantInfo()
        val acqSetting = ServiceHolder.getAcquirerSetting()
        isoComm = IsoComm()
        val primaryHostIP = DbModelMerchantConfig.getSafeValue(merchantInfo, "PrimaryHostIp")
        val primaryHostPort = DbModelMerchantConfig.getSafeValue(merchantInfo, "PrimaryHostPort")
        val primaryHostSSL = DbModelMerchantConfig.getBooleanValue(merchantInfo, "PrimaryHostSSL")
        val secondaryHostIP = DbModelMerchantConfig.getSafeValue(merchantInfo, "SecondaryHostIp")
        val secondaryHostPort = DbModelMerchantConfig.getSafeValue(merchantInfo, "SecondaryHostPort")
        val secondaryHostSSL = DbModelMerchantConfig.getBooleanValue(merchantInfo, "SecondaryHostSSL")
        val timeoutMs = NumberUtils.toInt(DbModelMerchantConfig.getSafeValue(merchantInfo, "HostTimeoutMs"), 60000)
        val connectTimeoutMs = 3500

        val isoRespBuf = ByteArray(5000)
        /*val isoRespLen: Int
        val iConResp: Int
        if (acqSetting.isSSL) {
            isoRespLen = isoComm!!.sendToHostWithSSL(log, acqSetting.sslCert, primaryHostIP, primaryHostPort, primaryHostSSL, secondaryHostIP, secondaryHostPort, secondaryHostSSL, connectTimeoutMs, timeoutMs, isoByteArray, 0, isoByteArrayLen, isoRespBuf, 0, 1024)
            iConResp = isoRespLen
        } else {
            isoRespLen = isoComm!!.sendToHost(log, primaryHostIP, primaryHostPort, secondaryHostIP, secondaryHostPort, connectTimeoutMs, timeoutMs, isoByteArray, 0, isoByteArrayLen, isoRespBuf, 0, 1024)
            iConResp = isoRespLen
        }*/
        // finally, not a trailing decrement: sendToHostWithSSL can throw, and a leaked
        // count would wedge the terminal into permanently refusing new transactions.
        hostRequestsInFlight.incrementAndGet()
        try {
            val isoRespLen = isoComm!!.sendToHostWithSSL(log, acqSetting.sslCert, primaryHostIP, primaryHostPort, primaryHostSSL, secondaryHostIP, secondaryHostPort, secondaryHostSSL, connectTimeoutMs, timeoutMs, isoByteArray, 0, isoByteArrayLen, isoRespBuf, 0, 1024)
            val iConResp = isoRespLen

            log.appendLine(logClassName, "SendToHost Resp=$iConResp  ---  Resplen=$isoRespLen")
            if(isoRespLen > 0){
                log.appendLine(logClassName, "OK, data received $isoRespLen-bytes")
                if(isReturnValue){
                    Utils.memcpy(returnByte, isoRespBuf, isoRespLen)
                    returnByteArrayLen[0] = isoRespLen
                    log.appendLine(logClassName, "Direct return response message")
                } else {
                    invokeFunction(TransData.acqCode.uppercase(), "parseIsoResp", log, isoRespBuf, 2, isoRespLen - 2)
                }
            } else {
                log.appendLine(logClassName, "FAILED")
                if (iConResp == TerminalConstants.iso.err.connectionFailed) {
                    log.appendLine(logClassName, "Connection Error. No Reversal Needed")
                    if(!isReturnValue) {
                        TransData.transResult = TerminalConstants.iso.err.connectionFailed
                    }
                } else {
                    log.appendLine(logClassName, "Transmit Error. Reversal Needed")
                    if(!isReturnValue) {
                        TransData.transResult = TerminalConstants.iso.err.communicationTimeout
                    }
                }
            }
        } finally {
            hostRequestsInFlight.decrementAndGet()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun insertPendingTransactionInto(context: Context, txnType: String) = withContext(Dispatchers.IO){
        println("insertPendingTransactionInto")
        val strSchemeId: String = TransData.schemeId
        var strTxnAmt = HexUtil.bytesToHexString(TransData.amountAuth)
        val strCashOutAmt = HexUtil.bytesToHexString(TransData.cashOutAmountAuth)
        if(TransData.cashOutAmount > 0) {
            val tempAmt = TransData.amount - TransData.cashOutAmount
            strTxnAmt = Utils.zeroPadding(tempAmt.toString(), 12)
        }
        val strStan: String = TransData.stan
        val strInvNo = TransData.invoiceNo
        val strNii: String = TransData.getFromTransactionDb("DF24", 16)
        val strTid = TransData.tid
        val strMid = TransData.mid
        var strBatchNo = TransData.batchNo
        var preAuthBatchNo = ""

        val strAid = TransData.aid
        val strMaskPanBcd = TransData.maskedPan
        val strCardHash = TransData.hashedPan
        val strEntryType = TransData.entryModeLabel
        val strPosReference = TransData.posReference
        val strCvm = TransData.cvm
        val strCardLabel = Utils.byteArrayToAsciiString(TransData.appLabel, 0, TransData.appLabelLen)
        val strOrderingItem = TransData.orderingItem
        val strOrderingItemImage = TransData.orderingItemImage
        val strPaymentProductId = TransData.productCode
        println("strPaymentProductId::$strPaymentProductId")

        val strCorrelationRef = TransData.correlationRef
        val strAdditionalInfo = TransData.additionalInfo

        var receiptTxnType = txnType
        when (txnType) {
            "CtSale", "Sale" -> {
                receiptTxnType = "Sale"
            }
            "Pre Authorization" -> {
                receiptTxnType = "PreAuth"
                preAuthBatchNo = Utility.HexString2ASCII(TransData.getFromTransactionDb("DF60", 16));
            }
            "Sale Completion" -> {
                receiptTxnType = "SaleCompletion"
                preAuthBatchNo = Utility.HexString2ASCII(TransData.getFromTransactionDb("BF60", 16));
            }
            "Instalment Sale" -> {
                receiptTxnType = "EPP"
                strBatchNo = TransData.batchNo
            }
            "Moto" -> {
                receiptTxnType = "MOTO"
            }
        }

        val jsonObject = JsonObject()
        try {
            jsonObject.addProperty("SEQ_NO", ServiceHolder.getSqnNum())
            jsonObject.addProperty("TXN_DT", Utils.DateTimeFormat(TransData.transDateAsci))
            jsonObject.addProperty("TXN_TYPE", receiptTxnType)
            jsonObject.addProperty("MID", strMid)
            jsonObject.addProperty("TID", strTid)
            jsonObject.addProperty("MTI", "")
            jsonObject.addProperty("NII", strNii)
            jsonObject.addProperty("SCHEME_ID", strSchemeId)
            jsonObject.addProperty("AID", strAid)
            jsonObject.addProperty("CARD_MASKED", strMaskPanBcd)
            jsonObject.addProperty("CARD_HASHED", strCardHash)
            jsonObject.addProperty("RRN", "-")
            jsonObject.addProperty("RESP_CODE", "-")
            jsonObject.addProperty("APPR_CODE", "-")
            jsonObject.addProperty("RRN_ORI", "-")
            jsonObject.addProperty("APPR_CODE_ORI", "-")
            jsonObject.addProperty("TXN_AMT", strTxnAmt)
            jsonObject.addProperty("INV_NO", strInvNo)
            jsonObject.addProperty("STAN", strStan)
            jsonObject.addProperty("BATCH_NO", strBatchNo)
            jsonObject.addProperty("APP_VER", ServiceHolder.getAppVersion())
            jsonObject.addProperty("SN", ServiceHolder.getTerminalSerialNumber())
            jsonObject.addProperty("ENTRY_TYPE", strEntryType)
            jsonObject.addProperty("ARQC", "-")
            jsonObject.addProperty("TVR", "-")
            jsonObject.addProperty("POS_REF_NO", strPosReference)
            jsonObject.addProperty("PAYMENT_PRODUCT_ID", strPaymentProductId)
            jsonObject.addProperty("CVM", strCvm)
            jsonObject.addProperty("BATCHNO_PREAUTH", preAuthBatchNo)
            jsonObject.addProperty("EPP_DETAIL", "")
            jsonObject.addProperty("CARD_LABEL", strCardLabel)
            jsonObject.addProperty("CASHOUT_AMT", strCashOutAmt)
            jsonObject.addProperty("ORDERING_ITEM", strOrderingItem)
            jsonObject.addProperty("ORDERING_ITEM_IMG", strOrderingItemImage)
            jsonObject.addProperty("CORRELATION_REF", strCorrelationRef)
            jsonObject.addProperty("ADDITIONAL_INFO", strAdditionalInfo)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        UploadTMS.getInstance().addReceipt(jsonObject.toString())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun updateReceiptInfo(context: Context) = withContext(Dispatchers.IO){
        val strStan = TransData.stan
        val batchNo = TransData.batchNo
        val strRrn = TransData.rrn
        val strApprCode = TransData.approvalCode
        val strRespCode = Utility.HexString2ASCII(TransData.respCode)
        val mti = TransData.getFromTransactionDb(TerminalConstants.iso.tag.MTI, 16)
        val strARQC = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_ARQC, 16)
        val strTVR = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_TVR, 16)
        val valueHM = java.util.HashMap<Any, Any>()
        var strEppDetails: String? = null
        /*if(TransData.salesType == ProductCatSelectionDataEnum.EPP.data.SalesType) {
            strEppDetails = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_EPP_DETAILS, 256).trim()
        }*/
        val hmEppDetails = parseEppDetailsJson(strEppDetails)

        valueHM["RRN"] = strRrn
        valueHM["APPR_CODE"] = strApprCode
        valueHM["RRN_ORI"] = strRrn
        valueHM["APPR_CODE_ORI"] = strApprCode
        valueHM["RESP_CODE"] = strRespCode
        valueHM["ARQC"] = strARQC
        valueHM["TVR"] = strTVR
        valueHM["MTI"] = mti
        valueHM["EPP_DETAIL"] = hmEppDetails
        valueHM["IsProcessing"] = "false"
        valueHM["IsSend"] = "false"

        val criteriaHM = java.util.HashMap<Any, Any>()
        criteriaHM["STAN"] = strStan
        criteriaHM["BATCH_NO"] = batchNo

        println("updateReceiptInfo::${valueHM}")
        ReceiptUploadRepo.updateData(context, valueHM, criteriaHM)
        AppServices.receiptUploadToTms(context)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun updateCashOutReceiptInfo(context: Context) = withContext(Dispatchers.IO){
        val strStan = TransData.stan
        val batchNo = TransData.batchNo
        val strRrn = TransData.rrn
        val strApprCode = TransData.approvalCode
        val strRespCode = Utility.HexString2ASCII(TransData.respCode)
        val mti = TransData.getFromTransactionDb(TerminalConstants.iso.tag.MTI, 16)
        val strARQC = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_ARQC, 16)
        val strTVR = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_TVR, 16)
        val valueHM = java.util.HashMap<Any, Any>()
        //TODO UPDATE CASHOUT AMOUNT
        //valueHM["TXN_AMT"] = TransData.
        //valueHM["CASHOUT_AMT"] = TransData.
        valueHM["RRN"] = strRrn
        valueHM["APPR_CODE"] = strApprCode
        valueHM["RESP_CODE"] = strRespCode
        valueHM["ARQC"] = strARQC
        valueHM["TVR"] = strTVR
        valueHM["MTI"] = mti
        valueHM["IsProcessing"] = "false"
        valueHM["IsSend"] = "false"

        val criteriaHM = java.util.HashMap<Any, Any>()
        criteriaHM["STAN"] = strStan
        criteriaHM["BATCH_NO"] = batchNo

        ReceiptUploadRepo.updateData(context, valueHM, criteriaHM)
        AppServices.receiptUploadToTms(context)
    }

    suspend fun insertIntoBatchTable(context: Context) = withContext(Dispatchers.IO){
        val isoBatchData = HexUtil.bytesToHexString(TransData.transactionDb, 0, TransData.transactionDbLen + 2)
        val batchTableModel = DbModelBatchTableInsert(
            TransData.transDateAsci,
            TransData.txnTypeLabel,
            TransData.stan,
            TransData.invoiceNo,
            isoBatchData,
            TransData.schemeTag,
            TransData.schemeId,
            "0",
            "a",
            TransData.batchNo,
            TransData.mid,
            TransData.tid,
            TransData.posReference
        )
        println(batchTableModel)
        BatchTableRepo.insertToDb(context, batchTableModel)
    }

    suspend fun insertIntoRevBatchTable(context: Context) = withContext(Dispatchers.IO){
        val isoBatchData = HexUtil.bytesToHexString(TransData.transactionDb, 0, TransData.transactionDbLen + 2)
        val revBatchModel = DbModelRevBatchTableInsert(
            TransData.transDateAsci,
            TransData.txnTypeLabel,
            TransData.stan,
            TransData.invoiceNo,
            isoBatchData,
            TransData.schemeTag,
            TransData.schemeId,
            "0",
            "a",
            TransData.batchNo,
            TransData.mid,
            TransData.tid
        )
        ReversalBatchTableRepo.insertToDb(context, revBatchModel)
    }

    suspend fun deleteSuccessReversal(context: Context) = withContext(Dispatchers.IO){
        ReversalBatchTableRepo.deleteSuccessReversalRecord(context, TransData.stan, TransData.invoiceNo, TransData.batchNo, TransData.mid, TransData.tid)
    }

    suspend fun deleteVoidedInBatchTable(context: Context) = withContext(Dispatchers.IO){
        BatchTableRepo.deleteVoidedInvoice(context, TransData.invoiceNo)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun insertIntoPrintReceipt(context: Context) = withContext(Dispatchers.IO){
        val details = TransData.generateReceiptInfo()
        val strReceiptInfo: String = details.contentToString()

        val printReceiptModel = DbModelPrintReceiptInsert(
            TransData.transDateAsci,
            TransData.transDateAsci,
            TransData.txnTypeLabel,
            TransData.maskedPan,
            TransData.schemeId,
            HexUtil.bytesToHexString(TransData.amountAuth),
            TransData.invoiceNo,
            TransData.stan,
            TransData.approvalCode,
            strReceiptInfo,
            TransData.isTpaAccount.toString(),
            Utility.HexString2ASCII(TransData.respCode)
        )
        PrintReceiptRepo.insertToDb(context, printReceiptModel)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun deleteVoidedInPrintReceipt(context: Context) = withContext(Dispatchers.IO){
        //PrintReceiptRepo.deleteVoidedInvoice(context, HexUtil.bytesToHexString(TransData.amountAuth), TransData.invoiceNo, TransData.invoiceNo)
        PrintReceiptRepo.deleteVoidedInvoice(context, HexUtil.bytesToHexString(TransData.amountAuth), TransData.prevInvoice, TransData.prevStan)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun deleteVoidedSaleCompInPrintReceipt(context: Context) = withContext(Dispatchers.IO){
        PrintReceiptRepo.deleteVoidedSaleCompInvoice(context, HexUtil.bytesToHexString(TransData.amountAuth), TransData.invoiceNo)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun deleteVoidPreAuthInPrintReceipt(context: Context) = withContext(Dispatchers.IO){
        PrintReceiptRepo.deleteVoidedPreAuth(context, /*HexUtil.bytesToHexString(TransData.amountAuth),*/ TransData.invoiceNo, TransData.approvalCode)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun insertIntoPreauthTable(context: Context) = withContext(Dispatchers.IO){
        val isoBatchData = HexUtil.bytesToHexString(TransData.transactionDb, 0, TransData.transactionDbLen + 2)
        val preAuthTableModel = DbModelPreAuthTableInsert(
            TransData.transDateAsci,
            TransData.schemeType,
            TransData.schemeTag,
            TransData.approvalCode,
            TransData.rrn,
            TransData.invoiceNo,
            "a",
            isoBatchData
        )
        PreAuthTableRepo.insertToDb(context, preAuthTableModel)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun deleteVoidedInPreauthTable(context: Context) = withContext(Dispatchers.IO){
        PreAuthTableRepo.deleteVoidedInvoice(context, TransData.invoiceNo)
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun checkTransactionStatus(context: Context, log: HelperLog) {
        val respMti = TransData.getFromTransactionDb(TerminalConstants.iso.tag.MTI_RESP, 16)
        var respCode = TransData.getFromTransactionDb("BF39", 16)
        var intRespCode = 0x3936
        //val reqStan = TransData.getFromTransactionDb("DF11", 16)
        val reqStan = TransData.stan
        val respStan = TransData.getFromTransactionDb("BF11", 16)
        if(TransData.transResult == TerminalConstants.iso.err.communicationTimeout) {
            respCode = Utils.ASCIItoHexString("ZU")
        }

        log.appendLine(logClassName, "respCode :: $respCode")
        log.appendLine(logClassName, "TransData Stan :: ${TransData.stan}")
        log.appendLine(logClassName, "respStan :: $respStan")
        log.appendLine(logClassName, "reqStan :: $reqStan")

        if(respMti.isNullOrBlank()){
            log.appendLine(logClassName, "respMti :: $respMti")
            log.appendLine(logClassName, "Invalid MTI received")
            // TODO Exit
        }

        if(respStan != reqStan){
            log.appendLine(logClassName, "Invalid Trace Number")
            // TODO Exit
        }

        if(respCode.isNotEmpty()){
            intRespCode = respCode.toInt(16)
        }

        TransData.respCode = respCode
        val approvalCode = Utility.HexString2ASCII(TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_APPRCODE, 16))
        TransData.approvalCode = approvalCode
        val rrn = Utility.HexString2ASCII(TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_RRN, 16))
        TransData.rrn = rrn
        log.appendLine(logClassName, "approvalCode :: $approvalCode")
        log.appendLine(logClassName, "rrn :: $rrn")

        when(intRespCode){
            0x3030 -> {
                TransData.transResult = TerminalConstants.iso.err.txnApproved
                // The card is already charged at this point. A bare
                // launch here has no exception handler, so anything thrown inside takes the whole
                // process down *after* the customer has paid, losing the settlement update and
                // leaving the batch out of balance.
                // Contain it: log and carry on, never crash.
                CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                    updateSettlementValue(context, log, false)
                }
            }
            0x3139 -> {
                log.appendLine(logClassName, "Re-Enter Txn")
            }
            0x3338 -> {
                log.appendLine(logClassName, "Pin Tries Exceeded")
            }
            0x3535 -> {
                log.appendLine(logClassName, "Incorrect Pin")
            }
            0x3530 -> {
                //MyDebit PIN Required
                log.appendLine(logClassName, "Pin Needed")
                TransData.transResult = TerminalConstants.iso.err.txnDeclined_pinNeeded
            }
            0x3635, 0x3436 -> {
                log.appendLine(logClassName, "Txn Rejected")
            }
            else -> {
                //TODO exit with failed
                log.appendLine(logClassName, "Txn Declined with no special Code")
                //return TerminalConstants.iso.err.txnDeclined
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun checkSettlementStatus(context: Context, settlementProduct: DbModelProductList, settlementValueString: String, log: HelperLog) {
        val respMti = TransData.getFromTransactionDb(TerminalConstants.iso.tag.MTI_RESP, 16)
        val respCode = TransData.getFromTransactionDb("BF39", 16)
        var intRespCode = 0x3936
        val reqStan = TransData.stan
        val respStan = TransData.getFromTransactionDb("BF11", 16)
        log.appendLine(logClassName, "respCode::$respCode")
        log.appendLine(logClassName, "TransData Stan::${TransData.stan}")
        log.appendLine(logClassName, "respStan::$respStan")
        log.appendLine(logClassName, "reqStan::$reqStan")

        if(respMti.isNullOrBlank()){
            log.appendLine(logClassName, "respMti::$respMti")
            log.appendLine(logClassName, "Invalid MTI received")
            // TODO Exit
        }

        if(respStan != reqStan){
            log.appendLine(logClassName, "Invalid Trace Number")
            // TODO Exit
        }

        if(respCode.isNotEmpty()){
            intRespCode = respCode.toInt(16)
        }

        TransData.respCode = respCode
        val approvalCode = Utility.HexString2ASCII(TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_APPRCODE, 16))
        TransData.approvalCode = approvalCode
        val rrn = Utility.HexString2ASCII(TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_RRN, 16))
        TransData.rrn = rrn
        log.appendLine(logClassName, "approvalCode::$approvalCode")
        log.appendLine(logClassName, "rrn::$rrn")

        when(intRespCode){
            0x3030 -> {
                val store = DataStoreManager(context)
                TransData.transResult = TerminalConstants.iso.err.txnApproved
                CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                    initNewBatchNo(context, settlementProduct, log)
                    store.putBoolean(PrefKeys.settlementBlock, false)
                }
                log.appendLine(logClassName, "Settlement BLOCK = FALSE (END)")
            }
            0x3935 -> {
                log.appendLine(logClassName, "Settlement Failed. Batch Upload Required")
                TransData.transResult = TerminalConstants.iso.err.reconcileError
                val batchUploadRes = processBatchUpload(context, settlementProduct, settlementValueString, log)
                log.appendLine(logClassName, "batchUploadRes :: $batchUploadRes")
                if(batchUploadRes == 0x3030){
                    TransData.transResult = TerminalConstants.iso.err.txnApproved
                    TransData.respCode = batchUploadRes.toString(16).uppercase()
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        initNewBatchNo(context, settlementProduct, log)
                    }
                }
            }
            else -> {
                //TODO exit with failed
                log.appendLine(logClassName, "Settle Declined")
                TransData.transResult = TerminalConstants.iso.err.txnDeclined
                //return TerminalConstants.iso.err.txnDeclined
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    /**
     * Outer net for the persistence work this class launches.
     *
     * Every `CoroutineScope(Dispatchers.IO + postApprovalHandler).launch` in this file is post-authorisation
     * bookkeeping — settlement totals, batch rows, receipt rows, reversal cleanup. A bare launch
     * has no handler, so a single throw inside any of them kills the process *after* the customer
     * has been charged, losing the write and leaving the batch out of balance. A lost bookkeeping
     * write is recoverable; a dead app mid-payment is not, so these are contained and logged
     * rather than propagated.
     *
     * Applied to all 35 sites here, not just the settlement one, because they are the same class
     * of work with the same consequence.
     */
    private val postApprovalHandler = CoroutineExceptionHandler { _, e ->
        try {
            val sb = HelperLog.init(logClassName)
            HelperLog.appendLine(sb, "POST-APPROVAL TASK FAILED (contained)", e.toString())
            HelperLog.logToFile(sb, EnumLogFileName.TerminaLogException)
        } catch (_: Exception) { /* logging must never be the thing that crashes us */ }
        android.util.Log.e(logClassName, "post-approval task failed (contained): $e")
    }

    /**
     * This function runs AFTER the card has been charged, so it must
     * never throw. The settlement reads below were force-unwrapped: a missing SettlementSummary
     * row (which a session clobber can easily produce) turned a data-state problem into an NPE,
     * and because the caller launches it in a bare coroutine with no handler, that killed the
     * process after the customer had paid and left the batch out of balance. A missing row now
     * reads as "0", matching what the cashOutTotal read already did.
     */
    suspend fun updateSettlementValue(context: Context, log: HelperLog, isReversal: Boolean) = withContext(Dispatchers.IO){
        if(TransData.txnTypeLabel.equals("Pre Authorization", true) || TransData.txnTypeLabel.equals("PreAuth Cancel", true)){
            log.appendLine(logClassName, "${TransData.txnTypeLabel} skip Update Settlement Value")
            return@withContext
        }

        var txnAmount = TransData.amount

        var strTempTxnTotal = ""
        var strTempTxnCount = ""
        var strTempVoidTxnTotal = ""
        var strTempVoidTxnCount = ""
        var iTempTxnTotal: Long = 0
        var iTempTxnCount = 0
        var iTempVoidTxnTotal: Long = 0
        var iTempVoidTxnCount = 0
        var strTempCashOutTotal = ""
        var iTempCashOutTotal: Long = 0

        var cardType = CardSchemeEnum.detectByCardSchemeID(TransData.schemeId)
        var schemeType = TransData.schemeType

        if(cardType == CardSchemeEnum.UNKNOWN) {
            log.appendLine(logClassName, "Card Type Detection Scheme Failed")
            val track2 = TransData.getFromTransactionDb("DF02", 16).replace("F", "")
            log.appendLine(logClassName, "Track2 :: ${LogRedact.track2(track2)}")
            cardType = CardSchemeEnum.detect(track2)
        }

        if(schemeType.isEmpty()){
            log.appendLine(logClassName, "Override schemeType")
            schemeType = cardType.typeIdentifier
        } else if (schemeType.equals("pboc", true)) {
            log.appendLine(logClassName, "Override ScehemeType UPI from PBOC to UPI")
            schemeType = CardSchemeEnum.UPI.typeIdentifier
        }
        val settlementRefTag = "${TransData.schemeTag}-$schemeType".lowercase()
        log.appendLine(logClassName, "settlementRefTag :: $settlementRefTag")
        //val settlementRefTag = "visam-$schemeType".lowercase()

        if(cardType != CardSchemeEnum.UNKNOWN){
            val fieldList = arrayListOf<String>("acq_code", "mid", "tid", "tag", "subtag")
            val acqCode = TransData.acqCode.uppercase()
            val thisTxnMid = TransData.mid
            val thisTxnTid = TransData.tid

            strTempTxnTotal = SettlementSummaryRepo.getRecordValue(
                context,
                fieldList,
                arrayOf<String>(acqCode, thisTxnMid, thisTxnTid, "txnTotal", settlementRefTag)
            ) ?: "0"
            iTempTxnTotal = Utils.convertLong(strTempTxnTotal)
            strTempTxnCount = SettlementSummaryRepo.getRecordValue(
                context,
                fieldList, arrayOf<String>(acqCode, thisTxnMid, thisTxnTid, "txnCount", settlementRefTag)
            ) ?: "0"
            iTempTxnCount = Utils.atoi(strTempTxnCount)

            strTempVoidTxnTotal = SettlementSummaryRepo.getRecordValue(
                context,
                fieldList, arrayOf<String>(acqCode, thisTxnMid, thisTxnTid, "voidTxnTotal", settlementRefTag)
            ) ?: "0"
            iTempVoidTxnTotal = Utils.convertLong(strTempVoidTxnTotal)
            strTempVoidTxnCount = SettlementSummaryRepo.getRecordValue(
                context,
                fieldList, arrayOf<String>(acqCode, thisTxnMid, thisTxnTid, "voidTxnCount", settlementRefTag)
            ) ?: "0"
            iTempVoidTxnCount = Utils.atoi(strTempVoidTxnCount)

            strTempCashOutTotal = SettlementSummaryRepo.getRecordValue(
                context,
                fieldList,
                arrayOf<String>(acqCode, thisTxnMid, thisTxnTid, "cashOutTotal", settlementRefTag)
            ) ?: "0"
            iTempCashOutTotal = Utils.convertLong(strTempCashOutTotal)
            log.appendLine(logClassName, "${TransData.schemeType} :: PreTotal= $iTempTxnTotal")
            log.appendLine(logClassName, "${TransData.schemeType} :: PreCount= $iTempTxnCount")
            log.appendLine(logClassName, "${TransData.schemeType} :: VoidPreTotal= $iTempVoidTxnTotal")
            log.appendLine(logClassName, "${TransData.schemeType} :: VoidPreCount= $iTempVoidTxnCount")
            log.appendLine(logClassName, "${TransData.schemeType} :: CashOutTotal= $iTempCashOutTotal")

            if(TransData.txnTypeLabel.contains("Void", true) || isReversal){
                if(TransData.txnTypeLabel.equals("CashOut Void", true)) {
                    txnAmount = TransData.amount - TransData.cashOutAmount
                    iTempCashOutTotal -= TransData.cashOutAmount
                }

                iTempTxnTotal -= txnAmount
                if (txnAmount > 0) {
                    iTempTxnCount -= 1
                } else {
                    log.appendLine(logClassName, "\t\t Note: ZERO AMOUNT not reduce to TxnCount")
                }

                if(!isReversal) {
                    iTempVoidTxnTotal += txnAmount
                    if (txnAmount > 0) {
                        iTempVoidTxnCount += 1
                    } else {
                        log.appendLine(logClassName, "\t\t Note: ZERO AMOUNT not added to TxnCount")
                    }
                }
            } else {
                if(TransData.txnTypeLabel.equals("cash out", true)) {
                    txnAmount = TransData.amount - TransData.cashOutAmount
                    iTempCashOutTotal += TransData.cashOutAmount
                }

                iTempTxnTotal += txnAmount
                if (txnAmount > 0) {
                    iTempTxnCount += 1
                } else {
                    log.appendLine(logClassName, "\t\t Note: ZERO AMOUNT not added to TxnCount")
                }
            }

            val valueHM = HashMap<Any, Any>()
            val criteriaHM = HashMap<Any, Any>()
            criteriaHM["acq_code"] = acqCode
            criteriaHM["mid"] = thisTxnMid
            criteriaHM["tid"] = thisTxnTid
            criteriaHM["subtag"] = settlementRefTag

            criteriaHM["tag"] = "txnTotal"
            valueHM["value"] = iTempTxnTotal.toString()
            SettlementSummaryRepo.updateData(context, valueHM, criteriaHM)
            criteriaHM["tag"] = "txnCount"
            valueHM["value"] = iTempTxnCount.toString()
            SettlementSummaryRepo.updateData(context, valueHM, criteriaHM)

            criteriaHM["tag"] = "voidTxnTotal"
            valueHM["value"] = iTempVoidTxnTotal.toString()
            SettlementSummaryRepo.updateData(context, valueHM, criteriaHM)
            criteriaHM["tag"] = "voidTxnCount"
            valueHM["value"] = iTempVoidTxnCount.toString()
            SettlementSummaryRepo.updateData(context, valueHM, criteriaHM)

            criteriaHM["tag"] = "cashOutTotal"
            valueHM["value"] = iTempCashOutTotal.toString()
            SettlementSummaryRepo.updateData(context, valueHM, criteriaHM)

            log.appendLine(logClassName, "New ${TransData.schemeType} :: Total= $iTempTxnTotal")
            log.appendLine(logClassName, "New ${TransData.schemeType} :: Count= $iTempTxnCount")
            log.appendLine(logClassName, "New ${TransData.schemeType} :: VoidTotal= $iTempVoidTxnTotal")
            log.appendLine(logClassName, "New ${TransData.schemeType} :: VoidCount= $iTempVoidTxnCount")
            log.appendLine(logClassName, "New ${TransData.schemeType} :: CashOutTotal= $iTempCashOutTotal")
        }
    }

    private suspend fun initNewBatchNo(context: Context, settlementProduct: DbModelProductList, log: HelperLog) = withContext(Dispatchers.IO) {
        log.appendLine(logClassName, "initNewBatchNo")
        if(TransData.transResult == TerminalConstants.iso.err.txnApproved){
            val unSettleProductList = getUnSettledProduct(context)

            var valueHM = HashMap<Any, Any>()
            valueHM["value"] = "0"
            valueHM["is_settle"] = "true"

            var criteriaHM = HashMap<Any, Any>()
            criteriaHM["acq_code"] = settlementProduct.AcqCode
            criteriaHM["mid"] = settlementProduct.AcqMid
            criteriaHM["tid"] = settlementProduct.AcqTid
            SettlementSummaryRepo.updateData(context, valueHM, criteriaHM)

            if (unSettleProductList.size <= 1) {
                // Increment Batch Number
                var strBatchNoToRemove = ""

                strBatchNoToRemove = IsoBatchInfoRepo.getBatchInfo(context, "batchNo", TransData.schemeTag)?.value ?: "000001"
                var iBatchNo = Utils.atoi(strBatchNoToRemove)

                iBatchNo += 1
                if (iBatchNo > 999999) iBatchNo = 1

                val strBatchNo = String.format(Locale.ENGLISH, "%06d", iBatchNo)
                Utils.printLog("\tNew BatchNo: $strBatchNo")
                log.appendLine(logClassName, "New BatchNo: $strBatchNo")
                IsoBatchInfoRepo.updateBatchInfo(context, strBatchNo, "batchNo", TransData.schemeTag)

                // Clear txn Count and Amount
                IsoBatchInfoRepo.updateBatchInfo(context, "0", "txnTotal", TransData.schemeTag)
                IsoBatchInfoRepo.updateBatchInfo(context, "0", "txnCount", TransData.schemeTag)
                IsoBatchInfoRepo.updateBatchInfo(context, "0", "voidTxnTotal", TransData.schemeTag)
                IsoBatchInfoRepo.updateBatchInfo(context, "0", "voidTxnCount", TransData.schemeTag)
                IsoBatchInfoRepo.updateBatchInfo(context, "0", "tcCount", TransData.schemeTag)

                // Clear txn Count and Amount
                IsoBatchInfoRepo.updateBatchInfo(context, "0", "buTxnTotal", TransData.schemeTag)
                IsoBatchInfoRepo.updateBatchInfo(context, "0", "buTxnCount", TransData.schemeTag)
                Utils.printLog("\t New BuTxnTotal=0")
                Utils.printLog("\t New BuTxnCount=0")

                log.appendLine(logClassName, "New BuTxnTotal=0")
                log.appendLine(logClassName, "New BuTxnCount=0")

                // Delete Batch
                BatchTableRepo.deleteBatchRecord(context, strBatchNoToRemove)
                PrintReceiptRepo.deleteAllData(context)

                //All Product current batch are settled, clean up settlement record
                valueHM = HashMap<Any, Any>()
                valueHM["value"] = "0"
                valueHM["is_settle"] = "false"

                criteriaHM = HashMap<Any, Any>()
                SettlementSummaryRepo.updateData(context, valueHM, criteriaHM)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun saveSettlementReceipt(context: Context) {
        val strNii: String = TransData.getFromTransactionDb("DF24", 16)
        val strRespCode = Utility.HexString2ASCII(TransData.respCode)

        //Json Body
        val jsonObject = JSONObject()
        try {
            jsonObject.put("SEQ_NO", ServiceHolder.getSqnNum())
            jsonObject.put("TXN_DT", Utils.DateTimeFormat(TransData.transDateAsci))
            jsonObject.put("TXN_TYPE", TransData.txnTypeLabel)
            jsonObject.put("MID", TransData.mid)
            jsonObject.put("TID", TransData.tid)
            jsonObject.put("MTI", "-")
            jsonObject.put("NII", strNii)
            jsonObject.put("SCHEME_ID", "-")
            jsonObject.put("AID", "-")
            jsonObject.put("CARD_MASKED", "-")
            jsonObject.put("CARD_HASHED", "-")
            jsonObject.put("RRN", TransData.rrn)
            jsonObject.put("APPR_CODE", TransData.approvalCode)
            jsonObject.put("RRN_ORI", TransData.rrn)
            jsonObject.put("APPR_CODE_ORI", TransData.approvalCode)
            jsonObject.put("TXN_AMT", "-")
            jsonObject.put("INV_NO", TransData.invoiceNo)
            jsonObject.put("STAN", TransData.stan)
            jsonObject.put("BATCH_NO", TransData.batchNo)
            jsonObject.put("RESP_CODE", strRespCode)
            jsonObject.put("APP_VER", ServiceHolder.getAppVersion())
            jsonObject.put("SN", ServiceHolder.getTerminalSerialNumber())
            jsonObject.put("ENTRY_TYPE", "-")
            jsonObject.put("ARQC", "-")
            jsonObject.put("TVR", "-")
            jsonObject.put("PAYMENT_PRODUCT_ID", "")
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        val body = jsonObject.toString()
        UploadTMS.getInstance().addReceipt(body)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processCashOutSale(context: Context, log: HelperLog) {
        // Refuse before the host is contacted and before any counter advances.
        // This is the only point where nothing has happened yet and nothing needs reconciling.
        if (!StorageGuard.canTransact(context)) {
            StorageGuard.logBlocked(context, "processCashOutSale")
            log.appendLine(logClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(context))
            TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
            // TransData.respCode is HEX-ASCII on this codebase (FragmentResult does
            // HexString2ASCII(respCode) -> "TAG_$code", and EmvFragment builds "8A02$respCode").
            // "5A53" is hex for "ZS" -> CardErrorDataEnum.TAG_ZS. A literal "ZS" here is not hex,
            // produces an invalid 8A TLV, and reaches the EMV kernel as a non-numeric REJCODE,
            // which throws across the binder and leaves the kernel unanswered (UI hangs on
            // "waiting for approval").
            TransData.respCode = StorageGuard.RESP_CODE_HEX
            return
        }
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "sale_cashout")

        //TODO Check Speed
        log.appendLine(logClassName, "processCashOutSale")
        log.appendLine(logClassName, "SchemeID :: ${TransData.schemeId}")
        acquirerIsoModel?.let { isoInfoModel ->
            TransData.transIsoInfoModel = isoInfoModel
            TransData.schemeTag = "visam"

            val track2 = EmvUtil.readTrack2()
            val track2Delimiter = track2.indexOf("D")
            val cardMask = track2.substring(0, track2Delimiter)
            val md = TransData.transDateAsci.substring(4, 8)
            val hhmmss = TransData.transDateAsci.substring(8)

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same invoiceNo to two concurrent flows.
            TransData.invoiceNo = IsoBatchInfoRepo.allocateCounter(context, "invoiceNo", "pos")
            log.appendLine(logClassName, "Invoice No :: ${TransData.invoiceNo}")

            // Atomic allocation; the open-coded get/increment/put could hand the
            // same stan to two concurrent flows.
            TransData.stan = IsoBatchInfoRepo.allocateCounter(context, "stan", TransData.schemeTag)
            log.appendLine(logClassName, "Stan :: ${TransData.stan}")

            TransData.batchNo = IsoBatchInfoRepo.getBatchInfo(context, "batchNo", TransData.schemeTag)?.value ?: "000001"
            log.appendLine(logClassName, "Batch No :: ${TransData.batchNo}")
            TransData.maskedPan = Utils.hideCardDetails(cardMask)
            log.appendLine(logClassName, "Masked Pan :: ${TransData.maskedPan}")
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_MASKBCD, Utils.ASCIItoHexString(Utils.hideCardDetails(cardMask)))
            TransData.hashedPan = cardMask.substring(0,9)
            log.appendLine(logClassName, "Hashed Pan :: ${TransData.hashedPan}")
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_HASH, Utils.ASCIItoHexString(TransData.hashedPan))
            if(track2Delimiter > 0){
                val cardExp = track2.substring(track2Delimiter + 1, track2Delimiter + 5)
                TransData.addHexStrWithPadIntoTransDB("DF02", cardMask, "F")
                TransData.addHexStrIntoTransDB("DF14", cardExp)
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.PANSTRING, cardMask)
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.EXPDATE, cardExp)
            }
            TransData.addHexStrIntoTransDB("DA", TransData.schemeId)
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)
            TransData.addTlvIntoTransDB("DF04", TransData.amountAuth, 0, TransData.amountAuth.size)
            val ascCashOut = HexUtil.bytesToHexString(TransData.cashOutAmountAuth)
            TransData.addHexStrIntoTransDB("DF54", HexUtil.str2HexStr(ascCashOut))
            TransData.addTlvIntoTransDB(TerminalConstants.cube.CUBE_TAG_RETAIL_AMOUNT, TransData.amountAuth, 0, TransData.amountAuth.size)
            TransData.addTlvIntoTransDB(TerminalConstants.cube.CUBE_TAG_CASH_OUT_AMOUNT, TransData.cashOutAmountAuth, 0, TransData.cashOutAmountAuth.size)

            TransData.addHexStrIntoTransDB("DF11", TransData.stan)
            TransData.addHexStrIntoTransDB("DF12", hhmmss)
            TransData.addHexStrIntoTransDB("DF13", md)
            EmvUtil.getPbocData("5F34", true)?.let {
                TransData.addHexStrIntoTransDB("DF23", Utils.paddingWith(it, "0", 4, false))
            }
            TransData.addHexStrIntoTransDB("DF25", isoInfoModel.posCondition)
            TransData.addHexStrWithPadIntoTransDB("DF35",track2, "F")
            TransData.addHexStrIntoTransDB("DF41", HexUtil.str2HexStr(TransData.tid))
            TransData.addHexStrIntoTransDB("DF42", HexUtil.str2HexStr(TransData.mid))
            TransData.addHexStrIntoTransDB("DF60", HexUtil.str2HexStr(TransData.batchNo))
            TransData.addHexStrIntoTransDB("DF62", HexUtil.str2HexStr(TransData.invoiceNo))
            //val track3 = EmvUtil.getAcquirerRequiredTlvData()
            val track3 = Utils.byteArrayToAsciiString(TransData.track3, 0 ,TransData.track3Len)
            log.appendLine(logClassName, "track3 :: $track3")
            TransData.addHexStrIntoTransDB("DF55", track3)
            //TODO PINBLOCK
            if (TransData.encPinBlock !== "") {
                TransData.addHexStrIntoTransDB("DF52", TransData.encPinBlock)
            }

            IsoBatchInfoRepo.getBatchInfo(context, "isoTpduHeaderTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.TPDU, it.value)
            } ?: run { "" }

            IsoBatchInfoRepo.getBatchInfo(context, "niiTle", TransData.schemeTag) ?.let {
                TransData.addHexStrIntoTransDB("DF24", it.value)
            } ?: run { "" }

            /* val strSchemeTagWithSchemeIdAndAcquirer = "${TransData.schemeTag}-${TransData.schemeId}-${TransData.acqCode.lowercase()}"
             val strPosEntryMode = IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithSchemeIdAndAcquirer)?.let {
                 println("Obtained PosEntry -> $strSchemeTagWithSchemeIdAndAcquirer")
                 log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithSchemeIdAndAcquirer")
                 it.value
             } ?: run {
                 val strSchemeTagWithAcquirer = "${TransData.schemeTag}-${TransData.acqCode.lowercase()}"
                 IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithAcquirer)?.let {
                     println("Obtained PosEntry -> $strSchemeTagWithAcquirer")
                     log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithAcquirer")
                     it.value
                 } ?: run {
                     val strSchemeTagWithSchemeId = "${TransData.schemeTag}-${TransData.schemeId}"
                     IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithSchemeId)?.let {
                         println("Obtained PosEntry -> $strSchemeTagWithSchemeId")
                         log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithSchemeId")
                         it.value
                     } ?: run {
                         val strSchemeTagWithMti = "${TransData.schemeTag}-${isoInfoModel.mti}"
                         IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithMti)?.let {
                             println("Obtained PosEntry -> $strSchemeTagWithMti")
                             log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithMti")
                             it.value
                         } ?: run{
                             IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", TransData.schemeTag)?.let {
                                 println("Obtained PosEntry -> ${TransData.schemeTag}")
                                 log.appendLine(logClassName, "Obtained PosEntry -> ${TransData.schemeTag}")
                                 it.value
                             } ?: run { "" }
                         }
                     }
                 }
             }*/
            val strSchemeTagWithSchemeId = "${TransData.schemeTag}-${TransData.schemeId}"
            val strPosEntryMode = IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithSchemeId)?.let {
                log.appendLine(logClassName, "Obtained PosEntry :: $strSchemeTagWithSchemeId")
                it.value
            } ?: run {
                val strSchemeTagWithMti = "${TransData.schemeTag}-${isoInfoModel.mti}"
                IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithMti)?.let {
                    log.appendLine(logClassName, "Obtained PosEntry :: $strSchemeTagWithMti")
                    it.value
                } ?: run{
                    IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", TransData.schemeTag)?.let {
                        log.appendLine(logClassName, "Obtained PosEntry :: ${TransData.schemeTag}")
                        it.value
                    } ?: run { "" }
                }
            }
            log.appendLine(logClassName, "strPosEntryMode :: $strPosEntryMode")
            TransData.addHexStrIntoTransDB("DF22", strPosEntryMode)

            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                insertPendingTransactionInto(context, TransData.txnTypeLabel)
            }

            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)
            thisIsoDbBufLen = dbLen[0]
            log.appendLine(logClassName, "dbBuffLen :: $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff :: ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                checkTransactionStatus(context, log)
                log.appendLine(logClassName, "Transaction Result :: ${TransData.transResult}")
                if (TransData.transResult == TerminalConstants.iso.err.txnApproved) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoBatchTable(context)
                        //insertIntoPrintReceipt(context)
                    }
                } else if (TransData.transResult == TerminalConstants.iso.err.communicationTimeout || TransData.respCode.isEmpty()) {
                    CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                        insertIntoRevBatchTable(context)
                    }
                }
                CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                    TransData.transEndDate = HelperCommon.getDateString(EnumDateFormat.yyyyMMddHHmmssSSS.dateFormat)
                    //updateCashOutReceiptInfo(context)
                    insertIntoPrintReceipt(context)
                }
                invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
            }
        }
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "startTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "endTime :: $finish")
        log.appendLine(logClassName, "timeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------Process CashOut Sale [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun processCashOutVoid(context: Context, statusArray: BooleanArray, log: HelperLog){
        // Refuse before the host is contacted and before any counter advances.
        // This is the only point where nothing has happened yet and nothing needs reconciling.
        if (!StorageGuard.canTransact(context)) {
            StorageGuard.logBlocked(context, "processCashOutVoid")
            log.appendLine(logClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(context))
            TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
            // TransData.respCode is HEX-ASCII on this codebase (FragmentResult does
            // HexString2ASCII(respCode) -> "TAG_$code", and EmvFragment builds "8A02$respCode").
            // "5A53" is hex for "ZS" -> CardErrorDataEnum.TAG_ZS. A literal "ZS" here is not hex,
            // produces an invalid 8A TLV, and reaches the EMV kernel as a non-numeric REJCODE,
            // which throws across the binder and leaves the kernel unanswered (UI hangs on
            // "waiting for approval").
            TransData.respCode = StorageGuard.RESP_CODE_HEX
            return
        }
        log.appendLine(logClassName, "Processing Void Cash Out Sale")

        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "void_sale_cashout")

        acquirerIsoModel?.let {isoInfoModel ->
            TransData.transIsoInfoModel = isoInfoModel
            log.appendLine(logClassName, "Transform Batch Data")
            invokeFunction(TransData.acqCode.uppercase(), "transformBatchData", context)
            TransData.addHexStrIntoTransDB(TerminalConstants.iso.tag.MTI, isoInfoModel.mti)
            TransData.addHexStrIntoTransDB("DF03", isoInfoModel.processCode)
            TransData.addHexStrIntoTransDB("DF11", TransData.stan)

            log.appendLine(logClassName, "Forming Iso Message")
            val dbLen = IntArray(2)
            thisIsoDbBuf = ByteArray(6000)
            invokeFunction(TransData.acqCode.uppercase(), "formIsoMessage", context, log, isoInfoModel, 2, thisIsoDbBuf, dbLen)

            thisIsoDbBufLen = dbLen[0]
            log.appendLine(logClassName, "dbBuffLen :: $thisIsoDbBufLen")
            log.appendLine(logClassName, "thisIsoDbBuff :: ")
            log.appendLine(logClassName, HexUtil.bytesToHexString(thisIsoDbBuf, 0, thisIsoDbBufLen))

            if(thisIsoDbBufLen > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, thisIsoDbBuf, dbLen[0], false, respIsoDb, respDbLen, log)
                checkTransactionStatus(context, log)
                invokeFunction(TransData.acqCode.uppercase(), "derivedFutureKey", context, log)
            }
            log.appendLine(logClassName, "Transaction Result :: ${TransData.transResult}")
            if (TransData.transResult == TerminalConstants.iso.err.txnApproved) {
                CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                    deleteVoidedInBatchTable(context)
                    insertIntoPrintReceipt(context)
                    deleteVoidedInPrintReceipt(context)
                }
            }
        }

        //TODO Check Speed
        val finish = System.currentTimeMillis()
        val timeElapsed = finish - TransData.startTime
        log.appendLine(logClassName, "startTime :: ${TransData.startTime}")
        log.appendLine(logClassName, "endTime :: $finish")
        log.appendLine(logClassName, "timeElapsed :: $timeElapsed")
        log.appendLine(logClassName, "-----------------Process CashOut Void [END]-------------------->")
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @JvmStatic
    fun processSignOn(context: Context, helperLog: HelperLog): Boolean {
        helperLog.appendLine(logClassName, "------------------------PROCESS SignOn [START]----------------------->")
        var result = false
        val acquirerSetting = ServiceHolder.getAcquirerSetting()
        val acquirerIsoModel = IsoHelperNew.getIsoHelperObject(acquirerSetting.acqName, "sign_on")
        acquirerIsoModel?.let {
            val emvTag = EmvTag()
            val tempSignOnByte = ByteArray(4096)

            val txnDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
            val md = txnDt.substring(4, 8)
            val hhmmss = txnDt.substring(8)
            emvTag.addTlvByTvHexString("DF12", hhmmss, 0, hhmmss.length, tempSignOnByte)
            emvTag.addTlvByTvHexString("DF13", md, 0, md.length, tempSignOnByte)

            val merchantInfo = ServiceHolder.getMerchantInfo()
            val acqTid = merchantInfo?.AcqTid ?: ""
            val tidHex = HexUtil.str2HexStr(acqTid)
            TransData.tid = acqTid
            emvTag.addTlvByTvHexString("DF41", tidHex, 0, tidHex.length, tempSignOnByte)

            val acqMid = merchantInfo?.AcqMid ?: ""
            val midHex = HexUtil.str2HexStr(acqMid)
            TransData.mid = acqMid
            emvTag.addTlvByTvHexString("DF42", midHex, 0, midHex.length, tempSignOnByte)

            var networkDbLen = IntArray(2)
            var networkIsoDb = ByteArray(6000)

            //val tempSignOnLen = HexUtil.bcd2str(tempSignOnByte, 0, 2).toInt(16)
            val isoString = HexUtil.bytesToHexString(tempSignOnByte)
            val variantMap = invokeFunction(TransData.acqCode.uppercase(), "formIsoSignOn", context, helperLog, acquirerIsoModel, isoString, networkIsoDb, networkDbLen) as MutableMap<String, String>

            if(networkDbLen[0] > 0) {
                val respDbLen = IntArray(2)
                val respIsoDb = ByteArray(6000)
                sendToHost(context, networkIsoDb, networkDbLen[0], true, respIsoDb, respDbLen, helperLog)

                networkDbLen = IntArray(2)
                networkIsoDb = ByteArray(6000)
                invokeFunction(TransData.acqCode.uppercase(), "parseIsoRespIsolate", helperLog, respIsoDb, 2, respDbLen[0], networkIsoDb, networkDbLen, variantMap)
            }

            var stringRespCode = "-"
            val tempByte = ByteArray(2048)
            val tempLen = emvTag.getValueFrom(networkIsoDb, "BF39", tempByte)
            if(tempLen > 0){
                stringRespCode = HexUtil.bytesToHexString(tempByte, 0, tempLen)
                helperLog.appendLine(logClassName, "stringRespCode :: $stringRespCode")
                val responseCode = stringRespCode.toInt(16)
                if(responseCode == 0x3030) {
                    val signOnResult = invokeFunction(TransData.acqCode.uppercase(), "parseRespSignOn", context, helperLog, networkIsoDb, networkDbLen)
                    helperLog.appendLine(logClassName, "signOnResult :: $signOnResult")
                    if(signOnResult == true){
                        result = true
                    }
                }
            }

            CoroutineScope(Dispatchers.IO + postApprovalHandler).launch {
                val jsonObject = JSONObject()
                try {
                    jsonObject.put("Activity", "SignOn")
                    jsonObject.put("RespCode", stringRespCode)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
                val body = jsonObject.toString()
                sendWriteLog(helperLog, body)
            }
        }
        helperLog.appendLine(logClassName, "------------------------PROCESS SignOn [END]----------------------->")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        return result
    }

    fun parseEppDetailsJson(eppDe63: String?): String {
        val eppDetails = java.util.HashMap<Any, Any>()
        eppDetails["Acquirer"] = ServiceHolder.getAcquirerSetting().acqName.uppercase(Locale.getDefault())
        if (!eppDe63.isNullOrEmpty()) {
            if (eppDetails["Acquirer"] == "BSN"){
                eppDetails["Tenure"] = eppDe63.substring(1, 3)
                eppDetails["TotalAmt"] = Utils.getActualAmount(eppDe63.substring(48))
                eppDetails["FirstMonthAmt"] = Utils.getActualAmount(eppDe63.substring(22, 35))
                eppDetails["MonthlyAmt"] = Utils.getActualAmount(eppDe63.substring(22, 35))
                eppDetails["FinalAmt"] = Utils.getActualAmount(eppDe63.substring(9, 22))
            } else {
                eppDetails["Tenure"] = eppDe63.substring(1, 3)
                eppDetails["FirstMonthAmt"] = Utils.getActualAmount(eppDe63.substring(4, 15))
                eppDetails["MonthlyAmt"] = Utils.getActualAmount(eppDe63.substring(16, 27))
                eppDetails["TotalAmt"] = Utils.getActualAmount(eppDe63.substring(40))
            }
        } else {
            eppDetails["Tenure"] = "00"
            eppDetails["TotalAmt"] = "0.00"
            eppDetails["FirstMonthAmt"] = "0.00"
            eppDetails["MonthlyAmt"] = "0.00"
            eppDetails["FinalAmt"] = "0.00"
        }
        return eppDetails.toString()
    }

    @JvmStatic
    suspend fun runLastReversalIo(
        mContext: Context,
        helperLogClassName: String,
        onStartLoading: () -> Unit,
        onStopLoading: () -> Unit,
        onComplete: suspend (Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(mContext),
            Utils.getIPAddress(),
            "Sales Last Reversal",
            helperLogClassName,
            helperLogClassName
        )

        var reversalResult = true
        try {
            val lastPostingDt = IsoBatchLongInfoRepo.getBatchLongInfo(mContext, "postingDt", "last")
            helperLog.appendLine(helperLogClassName, "Last PostingDT :: ${lastPostingDt?.value}")
            if (lastPostingDt == null || lastPostingDt.value.isEmpty()) {
                return@withContext true
            }

            helperLog.appendLine(helperLogClassName, "Last Reversal Before Sales Trigger")
            var postingDtData = lastPostingDt.value
            onStartLoading()
            val cardProduct = ProductListRepo.getSinglev2(mContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
            val batchNo = IsoBatchInfoRepo.getBatchInfo(mContext, "batchNo", "visam")?.value ?: ""
            val reversalBatch = ReversalBatchTableRepo.getBatchData(mContext, listOf("batchNo", "mid", "tid"), arrayOf(batchNo, cardProduct.AcqMid, cardProduct.AcqTid))
            if(reversalBatch.isNotEmpty()) {
                val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
                val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(cardProduct.AcqCode, "reversal")
                if(acquirerRevIsoModel != null) {
                    for (tempFor in reversalBatch) {
                        if(tempFor.postingDt < postingDtData) {
                            helperLog.appendLine(helperLogClassName, "Skip Current postingDT :: ${tempFor.postingDt}")
                            continue
                        }

                        helperLog.appendLine(helperLogClassName, "Reversal Batch Model :: (${Gson().toJson(tempFor)})")
                        TransData.reset()

                        TransData.acqCode = cardProduct.AcqCode
                        TransData.product = cardProduct.Product
                        TransData.mid = cardProduct.AcqMid
                        TransData.tid = cardProduct.AcqTid
                        TransData.schemeTag = "visam"
                        TransData.txnTypeLabel = "Reversal"
                        TransData.ksn = cardProduct.Ksn
                        TransData.pinKsn = cardProduct.PinKsn
                        TransData.stan = tempFor.stan
                        TransData.invoiceNo = tempFor.invNo
                        TransData.batchNo = tempFor.batchNo
                        TransData.isTpaAccount = cardProduct.IsTpaAccount.lowercase() == "true"
                        TransData.tpaMid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
                        TransData.tpaTid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")
                        val revResult = processReversal(mContext, false, acquirerRevIsoModel, tempFor.batchData, false, helperLog)
                        if(reversalResult && revResult == null){
                            reversalResult = false
                            postingDtData = tempFor.postingDt
                        }
                    }

                    if(reversalResult) {
                        postingDtData = ""
                    }
                    helperLog.appendLine(helperLogClassName, "Final postingDT :: $postingDtData")
                    IsoBatchLongInfoRepo.updateBatchLongInfo(mContext, postingDtData, "postingDt", "last")
                }
            }
            Thread.sleep(500)
            onStopLoading()
        } finally {
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            onComplete(reversalResult)
        }
    }
}