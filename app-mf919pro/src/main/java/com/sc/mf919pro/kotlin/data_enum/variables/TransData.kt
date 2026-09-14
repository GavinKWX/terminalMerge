package com.sc.mf919pro.kotlin.data_enum.variables

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import crypto.Dukpt
import emv.EmvTag
import constants.TerminalConstants
import emv.Tlv
import com.sc.mf919pro.java.activity.Utils
import emv.EmvUtil
import utils.HexUtil
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import iso.IsoInfoModel
import iso.TransactionData
import java.lang.ref.WeakReference

object TransData : TransactionData {
    var tempContext: WeakReference<Context> = WeakReference(null)
    //lateinit var tempContext: Context
    var salesType: Int = 0
    override var txnTypeLabel: String = ""
    var pinRequired: Boolean = false
    val pinBlock = ByteArray(8)
    var encPinBlock : String = ""
    var amountString: String = ""
    var amount: Long = 0
    val amountAuth = ByteArray(6) //bcd
    override var cashOutAmount: Long = 0
    override val cashOutAmountAuth = ByteArray(6)
    val transType = ByteArray(1)
    val transDate = ByteArray(3)
    var transDateAsci = ""
    var payMethod: Int = TerminalConstants.paymentMethod.Non
    val entryMode = ByteArray(3)
    var entryModeLabel = ""
    override var batchNo: String = ""
    override var stan: String = ""
    override var invoiceNo: String = ""
    override var ksn: String = ""
    override var pinKsn: String = ""
    override var dukpt = ByteArray(16)
    override var tid: String = ""
    override var mid: String = ""
    override var acqCode: String = ""
    override var product: String = ""
    var productName: String = ""
    var productCode: String = ""
    override var eppTenure: String = ""
    override var eppTenureCode: String = ""
    var posReference: String = ""
    override var respCode: String = ""
    override var transResult: Int = TerminalConstants.iso.err.failed
    var approvalCode: String = ""
    var rrn: String = ""
    var orderingItem: String = ""
    var orderingItemImage: String = ""
    //FoodLink Integration
    var correlationRef: String = ""
    var additionalInfo: String = ""
    var transIsoInfoModel: IsoInfoModel? = null
    //TODO current only have visam and mccs is for mydebit
    var startTime: Long = System.currentTimeMillis()
    var loadingTitle = ""
    var loadingMessage = ""

    var transStartDate: String = ""
    var transEndDate: String = ""

    //Preauth
    var reqAuthId: String = ""
    var reqRrn: String = ""
    var reqApprovalCode: String = ""

    //CardRelatead
    var schemeId: String = ""
    override var schemeType: String = ""
    override var schemeTag: String = ""
    var aid: String = ""
    var cvm: String = ""
    var maskedPan = ""
    var hashedPan = ""
    var pan = ByteArray(19)
    var panLen: Int = 0
    val appLabel = ByteArray(16)
    var appLabelLen: Int = 0
    val track1 = ByteArray(76)
    var track1Len: Int = 0
    val track2EQ = ByteArray(40)
    var track2EQlen: Int = 0
    val track3 = ByteArray(2048)
    var track3Len: Int = 0
    val cardHolderName = ByteArray(26)
    var cardHolderNameLen: Int = 0
    val expirationDate = ByteArray(4)
    var prevInvoice: String = ""
    var prevStan: String = ""
    var prevRRN: String = ""
    var prevApprovalCode: String = ""
    val magTrack2 = ByteArray(40)
    var magTrack2Len: Int = 0

    //QR Related
    var qrPayBrand: String = ""
    var qrPayBrandDesc: String = ""
    var qrRef: String = ""
    var qrTxnRef: String = ""
    var qrHostRef: String = ""
    var qrApprovalCode: String = ""
    var qrRespCode = "1100"
    var qrRespDesc = ""
    var isUPIQR: Boolean = false
    var upiVoucherCode: String = ""
    var upiDiscountAmt: String = ""
    var upiMarkupFee: String = ""
    var upiFinalAmount: String = ""

    var pinInput = false
    override var onlinePinInput = false
    override var offlinePinInput = false
    var signRequired: Boolean = false
    var transSerialNO: Int = 0 // TODO increase the NO. after every transaction and should be save in files
    val amountCashback = ByteArray(6) // bcd

    //Offset required 2 for the length of whole byte
    override val transactionDb = ByteArray(4096)
    override var transactionDbLen: Int = 0

    val transactionDbBak = ByteArray(4096)
    var transactionDbLenBak: Int = 0

    var ptrValue2 = ByteArray(1024)

    var isTpaAccount: Boolean = false
    var tpaMid: String = ""
    var tpaTid: String = ""

//    fun reset(context: Context){
    /**
     * Ownership token.
     *
     * `TransData` is a process-wide singleton with no notion of who owns it, so a flow that has
     * been superseded (aborted QR enquiry, a task whose Activity is gone) can still write into a
     * *live* transaction's state. Each `reset()` mints a new id; a flow captures it at start and
     * checks [isCurrentSession] before any late write.
     *
     * `AtomicLong`, not `@Volatile var` with `++`: volatile guarantees *visibility*, not
     * atomicity, and `reset()` genuinely runs on multiple threads (see the `@Synchronized` note
     * below). With a plain increment two callers could read the same value and mint the **same
     * token for two different transactions**, silently defeating the whole mechanism. This is the
     * exact bug MF919's own review pass found in its first cut of this fix.
     */
    private val sessionIdHolder = java.util.concurrent.atomic.AtomicLong(0L)

    val sessionId: Long get() = sessionIdHolder.get()

    /** True when [id] is still the live session, i.e. the caller may write to TransData. */
    @JvmStatic
    fun isCurrentSession(id: Long): Boolean = id == sessionIdHolder.get()

    /**
     * Fix E Stage A — the single checked write point for background/long-running flows.
     *
     * Stage A (the Fix B token) left one gap named in the fix direction: the QR flows checked
     * ownership *manually* at each write site rather than going through a shared enforcement
     * point, so the check was something a future edit had to remember. This makes it structural —
     * a background write is expressed as a block that simply does not run when the session has
     * moved on:
     *
     * ```
     * TransData.ifCurrentSession(txnSession) {
     *     qrRespCode = respCode
     *     qrHostRef  = qrRespHostRefNo
     * }
     * ```
     *
     * The whole block is checked once and executed under the same lock that `reset()` takes, so a
     * reset cannot land halfway through a group of related writes — which a per-field check
     * cannot promise, however diligently it is applied.
     *
     * **The block deliberately has NO receiver.** An earlier version took `TransData.() -> Unit`,
     * which reads nicely but silently shadows the caller's own properties with same-named members
     * of this object — and both sides are usually `String`, so the compiler cannot see it. That
     * shipped a real bug: `GenerateQrFragment` did `qrRespCode = respCode` intending its own
     * `respCode` ("SHC005" on a user cancel) and got `TransData.respCode` (empty on a QR flow)
     * instead, so the result screen showed a blank response code. Callers now write
     * `transData.qrRespCode = respCode` exactly as before and no name can be captured.
     *
     * @return true if the block ran (session still owned), false if it was skipped.
     */
    @Synchronized
    fun ifCurrentSession(id: Long, block: () -> Unit): Boolean {
        if (id != sessionIdHolder.get()) return false
        block()
        return true
    }

    /**
     * `@Synchronized` because this genuinely runs on more than
     * one thread at once.
     *
     * `SettleOptionFragment.doInBackground` calls it **per item inside a loop** on a background
     * dispatcher (`:516` for each reversal, `:617` for each acquirer) while any fragment's
     * `onViewCreated` can call it on main. Every field here is a plain `var` with no
     * synchronisation, so two overlapping resets could interleave and leave the object holding a
     * mix of both — half-cleared state that no single caller ever intended.
     *
     * This makes the clear **atomic with respect to other clears**. It does NOT make the whole
     * object thread-safe: a concurrent *writer* (not resetter) can still interleave, which is what
     * Fix B's ownership token and Fix E's retirement of the singleton are for.
     *
     * @return the session id this reset minted. Returning a value is source-compatible — Kotlin
     *         lets existing `TransData.reset()` call sites ignore it.
     */
    @Synchronized
    fun reset(): Long {
        val session = sessionIdHolder.incrementAndGet()
        Utils.debugLogPrint("TransData", "TransData reset! session=$session")
        startTime = System.currentTimeMillis()
        loadingTitle = ""
        loadingMessage = ""
//        tempContext = WeakReference(context)
        salesType = 0
        txnTypeLabel = ""
        pinRequired = false
        pinBlock.fill(0, 0, pinBlock.size)
        encPinBlock = ""
        amountString = ""
        amount = 0
        amountAuth.fill(0, 0, amountAuth.size)
        cashOutAmount = 0
        cashOutAmountAuth.fill(0, 0, cashOutAmountAuth.size)
        transType.fill(0, 0, transType.size)
        transDate.fill(0, 0, transDate.size)
        transDateAsci = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
        payMethod = TerminalConstants.paymentMethod.Non
        entryMode.fill(0,0, entryMode.size)
        entryModeLabel = ""

        //batchNo = IsoBatchInfoRepo.getBatchInfo(context, "batchNo", "visam")?.value ?: "000001"
        //stan = IsoBatchInfoRepo.getBatchInfo(context, "stan", "visam")?.value ?: "000001"
        //invoiceNo = IsoBatchInfoRepo.getBatchInfo(context, "invoiceNo", "pos")?.value ?: "000001"
        batchNo = "000001"
        stan = "000001"
        invoiceNo = "000001"
        ksn = ""
        pinKsn = ""
        dukpt.fill(0, 0, dukpt.size)
        tid = ""
        mid = ""
        acqCode = ""
        product = ""
        productName = ""
        productCode = ""
        eppTenure = ""
        eppTenureCode = ""
        posReference = "-"
        respCode = ""
        transResult = TerminalConstants.iso.err.failed
        approvalCode = ""
        rrn = ""
        orderingItem = ""
        orderingItemImage = ""
        correlationRef = ""
        additionalInfo = ""
        transIsoInfoModel = null

        reqAuthId = ""
        reqRrn = ""
        reqApprovalCode = ""

        schemeId = ""
        schemeType = ""
        schemeTag = ""
        aid = ""
        maskedPan = ""
        hashedPan = ""
        pan.fill(0, 0, pan.size)
        panLen = 0
        appLabel.fill(0, 0, appLabel.size)
        appLabelLen = 0
        track1.fill(0, 0, track1.size)
        track1Len = 0
        track2EQ.fill(0, 0, track2EQ.size)
        track2EQlen = 0
        track3.fill(0, 0, track3.size)
        track3Len = 0
        cardHolderName.fill(0, 0, cardHolderName.size)
        cardHolderNameLen = 0
        expirationDate.fill(0, 0, expirationDate.size)
        prevInvoice = ""
        prevStan = ""
        prevRRN = ""
        prevApprovalCode = ""
        magTrack2.fill(0, 0, magTrack2.size)
        magTrack2Len = 0

        qrPayBrand = ""
        qrPayBrandDesc = ""
        qrRef = ""
        qrTxnRef = ""
        qrHostRef = ""
        qrApprovalCode = ""
        qrRespCode = "1100"
        qrRespDesc = ""
        isUPIQR = false
        upiVoucherCode = ""
        upiDiscountAmt = ""
        upiMarkupFee = ""
        upiFinalAmount = ""

        pinInput = false
        onlinePinInput = false
        offlinePinInput = false
        signRequired = false
        transSerialNO = 0
        amountCashback.fill(0, 0, amountCashback.size)

        transactionDb.fill(0, 0, transactionDb.size)
        transactionDbLen = 0

        ptrValue2.fill(0, 0, ptrValue2.size)
        isTpaAccount = false
        tpaMid = ""
        tpaTid = ""
        // Return the LOCAL value, never a re-read of the field: re-reading could pick up a
        // concurrent reset's id and hand the caller a token it does not actually hold.
        return session
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun addHexStrIntoTransDB(tag: String, strData: String?){
        // Null-safe to match MF919, which the TransactionData seam takes its signature from.
        // No caller passes null today; without the guard one would NPE through Java interop.
        if(strData != null) {
            val emvTag = EmvTag()
            val data = HexUtil.hexStringToByte(strData)

            //transactionDbLen = emvTag.addTlvByTv(tag, data, 0, data.size, transactionDb)
            val temp = emvTag.addTlvByTv(tag, data, 0, data.size, transactionDb)
            if(temp > 0) {
                transactionDbLen = temp
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun addHexStrWithPadIntoTransDB(tag: String, strData: String?, padChar: String){
        // Null-safe to match MF919 -- see addHexStrIntoTransDB above.
        if(strData != null) {
            val emvTag = EmvTag()
            var finalData = strData
            val currentLen = Utils.strlen(strData)
            if(currentLen % 2 != 0){
                finalData += padChar
            }
            val data = HexUtil.hexStringToByte(finalData)

            //transactionDbLen = emvTag.addTlvByTv(tag, data, 0, data.size, transactionDb)
            val temp = emvTag.addTlvByTv(tag, data, 0, data.size, transactionDb)
            if(temp > 0){
                transactionDbLen = temp
            }
        }
    }

    override fun addTlvIntoTransDB(tag: String, tlvData: ByteArray, dataOffSet: Int, tlvLen: Int){
        val emvTag = EmvTag()
        // transactionDbLen = emvTag.addTlvByTv(tag, tlvData, dataOffSet, tlvLen, transactionDb)
        val temp = emvTag.addTlvByTv(tag, tlvData, dataOffSet, tlvLen, transactionDb)
        if(temp > 0) {
            transactionDbLen = temp
        }
    }

    fun addTlvsTo(datain: ByteArray?, dataOffSet: Int, datainlen: Int) {
        val emvTag = EmvTag()
        Utils.memset(ptrValue2, 0.toByte(), ptrValue2.size)
        var ilen: Int = if (dataOffSet > 0) {
            Utils.memcpy(ptrValue2, 0, datain, dataOffSet, datainlen)
            datainlen - dataOffSet
        } else {
            Utils.memcpy(ptrValue2, 0, datain, 0, datainlen)
            datainlen
        }
        //transactionDbLen = emvTag.addTlvsTo(ptrValue2, ilen, transactionDb)
        val temp = emvTag.addTlvsTo(ptrValue2, ilen, transactionDb)
        if(temp > 0) {
            transactionDbLen = temp
        }
    }

    override fun removeTlvFromTransDb(tag: String){
        val tlv = Tlv()
        val removedLen = tlv.markoffTag(transactionDb, 2, transactionDbLen + 2, tag)
        Utils.printLog("\t[-]TLV: $tag")
        if(removedLen > 0) {
            transactionDbLen -= removedLen
        } else {
            Utils.printLog("\t[-] $$tag Nothing Removed")
        }
    }

    override fun getFromTransactionDb(tag: String, formatType: Int /*16 = hexString, 256=ascii*/): String {
        val emvTag = EmvTag()
        val tempByte = ByteArray(1024)

        val iDataLen = emvTag.getValueFrom(transactionDb, tag, tempByte)
        if(iDataLen < 0) return ""

        //System.arraycopy(tempByte, 0, resultByte, 0, iDataLen)
        if (formatType == 16) {
            println("GetIsoComponent($tag) -> ${HexUtil.bytesToHexString(tempByte, 0, iDataLen)}")
            return HexUtil.bytesToHexString(tempByte, 0, iDataLen)
        } else { //ascii
            println("GetIsoComponentHex($tag) -> ${HexUtil.bytesToHexString(tempByte, 0, iDataLen)}")
            println("GetIsoComponent($tag) -> ${Utils.byteArrayToAsciiString(tempByte, 0, iDataLen)}")
            return Utils.byteArrayToAsciiString(tempByte, 0, iDataLen)
        }
    }

    fun generateReceiptInfo(): Array<String> {
        //0. MID
        //1. TID
        //2. Batch No
        //3. Type of transaction
        //4. application label
        //5. card no
        //6. txn dt
        //7. invoice no
        //8. stan no
        //9. entry type
        //10. rrn
        //11. appr code
        //12. cash out amount
        //13. amt
        //14. Epp details
        //15. arqc 9f26
        //16. aid
        //17. tvr (95)
        //18. cvm (check pin)
        val receiptInfo = Array(19) { _ -> ""  }

        val (acqName, _, _, _) = ServiceHolder.getAcquirerSetting()
        var tmp = ""

        if(isTpaAccount && tpaMid.isNotEmpty() && tpaTid.isNotEmpty()) {
            receiptInfo[0] = tpaMid
            receiptInfo[1] = tpaTid
        } else {
            if(acqName.equals("GOBIZ", true)){
                receiptInfo[0] = Utils.maskString(mid, 4)
                receiptInfo[1] = Utils.maskString(tid, 4)
            } else {
                receiptInfo[0] = mid
                receiptInfo[1] = tid
            }
        }

        receiptInfo[2] = batchNo
        receiptInfo[3] = Utils.getTxnType(txnTypeLabel)

        //tmp = getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_APPLABEL, 16)
        //receiptInfo[4] = Utility.HexString2ASCII(tmp)
        receiptInfo[4] = Utils.byteArrayToAsciiString(appLabel, 0, appLabelLen)
        receiptInfo[5] = maskedPan
        receiptInfo[6] = Utils.DateTimeFormat(transDateAsci)
        receiptInfo[7] = invoiceNo
        receiptInfo[8] = stan
        receiptInfo[9] = entryModeLabel
        receiptInfo[10] = rrn
        receiptInfo[11] = approvalCode
        receiptInfo[12] = Utils.getActualAmount(cashOutAmount.toString())
        receiptInfo[13] = Utils.getActualAmount(amount.toString())

        tmp = getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_EPP_DETAILS, 256)
        if(!tmp.isNullOrEmpty() && acqCode.equals("PAYDEE", true)) {
            tmp = ""
        }
        receiptInfo[14] = tmp.trim()

        tmp = getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_ARQC, 16)
        receiptInfo[15] = tmp
        receiptInfo[16] = aid

        tmp = getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_TVR, 16)
        receiptInfo[17] = tmp
        receiptInfo[18] = cvm

        return receiptInfo
    }

    fun generateReceiptInfoQr(): Array<String> {
        //0. Txn Dt
        //1. E-Wallet
        //2. Txn Type
        //3. MID
        //4. TID
        //5. Approval Code
        //6. Host Ref
        //7. Amount if(UPI QR = Ref ID)
        //8. Ref ID if(UPI QR = Voucher Code)
        //9. if(UPI QR = Amount)
        //10. if(UPI QR = Discount Amount)
        //11. if(UPI QR = Total Amount)

        val receiptInfo = Array(12) { _ -> ""  }
        receiptInfo[0] = Utils.DateTimeFormat(transDateAsci)
        receiptInfo[1] = qrPayBrand
        receiptInfo[2] = Utils.getTxnType(txnTypeLabel)
        receiptInfo[3] = mid
        receiptInfo[4] = tid
        receiptInfo[5] = qrApprovalCode
        receiptInfo[6] = qrHostRef
        if (isUPIQR && upiVoucherCode.isNotEmpty()){
            receiptInfo[7] =  qrRef
            receiptInfo[8] =  upiVoucherCode
            receiptInfo[9] =  "RM " + Utils.getActualAmount(amount.toString())
            receiptInfo[10] =  "RM " + upiDiscountAmt
            receiptInfo[11] =  "RM " + upiFinalAmount
        } else {
            receiptInfo[7] = "RM" + Utils.getActualAmount(amount.toString())
            receiptInfo[8] = qrRef
        }

        return receiptInfo
    }


    fun resetTransactionDbFromBak(){
        transactionDb.fill(0, 0, transactionDb.size)
        transactionDbLen = 0
        ptrValue2.fill(0, 0, ptrValue2.size)

        transactionDbBak.copyInto(transactionDb, 0, 0, transactionDbBak.size)
        transactionDbLen = transactionDbLenBak

        transDate.fill(0, 0, transDate.size)
        stan = "000001"
        invoiceNo = "000001"
        if(acqCode.equals("BSN", true) || acqCode.equals("BSN_CARDZONE", true)) {
            ksn = Dukpt.getNewKsn(ksn) //ASCCEND MCCS pin retry
        }
        //ksn = ""
        //pinKsn = ""
        dukpt.fill(0, 0, dukpt.size)
        respCode = ""
        transResult = TerminalConstants.iso.err.failed
        approvalCode = ""
        rrn = ""
        cvm = ""
    }
}