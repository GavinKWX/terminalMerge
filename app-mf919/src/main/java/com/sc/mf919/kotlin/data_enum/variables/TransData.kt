package com.sc.mf919.kotlin.data_enum.variables

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.library.terminal.Utility
import com.sc.mf919.java.activity.Dukpt
import com.sc.mf919.java.activity.EmvTag
import com.sc.mf919.java.activity.Global
import com.sc.mf919.java.activity.Tlv
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.utils.EmvUtil
import utils.HexUtil
import com.sc.mf919.kotlin.database.model.DbModelDenominationList
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.iso.IsoInfoModel
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

object TransData {
    var tempContext: WeakReference<Context> = WeakReference(null)
    //lateinit var tempContext: Context
    var salesType: Int = 0
    var txnTypeLabel: String = ""
    var pinRequired: Boolean = false
    val pinBlock = ByteArray(8)
    var encPinBlock : String = ""
    var amount: Long = 0
    val amountAuth = ByteArray(6) //bcd
    var cashOutAmount: Long = 0
    val cashOutAmountAuth = ByteArray(6)
    val transType = ByteArray(1)
    val transDate = ByteArray(3)
    var transDateAsci = ""
    var payMethod: Int = Global.paymentMethod.Non
    val entryMode = ByteArray(3)
    var entryModeLabel = ""
    var batchNo: String = ""
    var stan: String = ""
    var invoiceNo: String = ""
    var ksn: String = ""
    var pinKsn: String = ""
    var dukpt = ByteArray(16)
    var tid: String = ""
    var mid: String = ""
    var acqCode: String = ""
    var product: String = ""
    var productName: String = ""
    var productCode: String = ""
    var eppTenure: String = ""
    var eppTenureCode: String = ""
    var posReference: String = ""
    var respCode: String = ""
    var transResult: Int = Global.iso.err.failed
    var approvalCode: String = ""
    var rrn: String = ""
    var orderingItem: String = ""
    var orderingItemImage: String = ""
    var transIsoInfoModel: IsoInfoModel? = null
    var denominationType: String = ""
    var denominationProduct: DbModelDenominationList? = null
    //TODO current only have visam and mccs is for mydebit
    var startTime: Long = System.currentTimeMillis()
    var loadingTitle = ""
    var loadingMessage = ""

    var transStartDate: String = ""
    var transEndDate: String = ""

    //Preauth
    var reqInvoiceNo: String = ""
    var reqRrn: String = ""
    var reqApprovalCode: String = ""
    var reqBatchNo: String = ""
    var reqCardPan: String = ""

    //CardRelatead
    var schemeId: String = ""
    var schemeType: String = ""
    var schemeTag: String = ""
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
    var onlinePinInput = false
    var offlinePinInput = false
    var signRequired: Boolean = false
    var transSerialNO: Int = 0 // TODO increase the NO. after every transaction and should be save in files
    val amountCashback = ByteArray(6) // bcd

    //Offset required 2 for the length of whole byte
    val transactionDb = ByteArray(4096)
    var transactionDbLen: Int = 0

    val transactionDbBak = ByteArray(4096)
    var transactionDbLenBak: Int = 0

    var ptrValue2 = ByteArray(1024)

    var isTpaAccount: Boolean = false
    var tpaMid: String = ""
    var tpaTid: String = ""

    // Ownership token: bumped on every reset() so a flow that outlives its transaction
    // (e.g. a QR enquiry loop still retrying after the ECR/UI moved on to a new sale) can
    // detect it no longer owns TransData before writing into it. AtomicLong (not a plain
    // @Volatile Long) because reset() can run on more than one thread (e.g. an activity's
    // onCreate on the main thread racing a background reversal loop) -- a plain `sessionId++`
    // is a non-atomic read-modify-write and could hand out the same token to two callers.
    // See obsidian FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
    private val sessionIdHolder = AtomicLong(0L)

    val sessionId: Long get() = sessionIdHolder.get()

    fun isCurrentSession(id: Long) = id == sessionId

    fun reset(context: Context): Long {
        val session = sessionIdHolder.incrementAndGet()
        startTime = System.currentTimeMillis()
        loadingTitle = ""
        loadingMessage = ""
        tempContext = WeakReference(context)
        salesType = 0
        txnTypeLabel = ""
        pinRequired = false
        pinBlock.fill(0, 0, pinBlock.size)
        encPinBlock = ""
        amount = 0
        amountAuth.fill(0, 0, amountAuth.size)
        cashOutAmount = 0
        cashOutAmountAuth.fill(0, 0, cashOutAmountAuth.size)
        transType.fill(0, 0, transType.size)
        transDate.fill(0, 0, transDate.size)
        transDateAsci = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
        payMethod = Global.paymentMethod.Non
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
        transResult = Global.iso.err.failed
        approvalCode = ""
        rrn = ""
        orderingItem = ""
        orderingItemImage = ""
        transIsoInfoModel = null
        denominationType = ""
        denominationProduct = null

        reqInvoiceNo = ""
        reqRrn = ""
        reqApprovalCode = ""
        reqBatchNo = ""
        reqCardPan = ""

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
        // Return the token minted by THIS call specifically, not a fresh read of sessionId --
        // a concurrent reset() on another thread could have bumped it again by now, and the
        // caller must get back exactly the token that makes isCurrentSession() true for them.
        return session
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun addHexStrIntoTransDB(tag: String, strData: String?){
        val emvTag = EmvTag()
        if(strData != null) {
            val data = HexUtil.hexStringToByte(strData)

            //transactionDbLen = emvTag.addTlvByTv(tag, data, 0, data.size, transactionDb)
            val temp = emvTag.addTlvByTv(tag, data, 0, data.size, transactionDb)
            if(temp > 0) {
                transactionDbLen = temp
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun addHexStrWithPadIntoTransDB(tag: String, strData: String?, padChar: String){
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

    @RequiresApi(Build.VERSION_CODES.O)
    fun addTlvIntoTransDB(tag: String, tlvData: ByteArray, dataOffSet: Int, tlvLen: Int){
        val emvTag = EmvTag()
        if(tlvLen > 0) {
            // transactionDbLen = emvTag.addTlvByTv(tag, tlvData, dataOffSet, tlvLen, transactionDb)
            val temp = emvTag.addTlvByTv(tag, tlvData, dataOffSet, tlvLen, transactionDb)
            if(temp > 0) {
                transactionDbLen = temp
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
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

    @RequiresApi(Build.VERSION_CODES.O)
    fun removeTlvFromTransDb(tag: String){
        val tlv = Tlv()
        val removedLen = tlv.markoffTag(transactionDb, 2, transactionDbLen + 2, tag)
        Utils.printLog("\t[-]TLV: $tag")
        if(removedLen > 0) {
            transactionDbLen -= removedLen
        } else {
            Utils.printLog("\t[-] $$tag Nothing Removed")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun getFromTransactionDb(tag: String, formatType: Int /*16 = hexString, 256=ascii*/): String {
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

    @RequiresApi(Build.VERSION_CODES.O)
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

        //tmp = getFromTransactionDb(Global.cube.CUBE_TAG_CARD_APPLABEL, 16)
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

        tmp = getFromTransactionDb(Global.cube.CUBE_TAG_EPP_DETAILS, 256)
        if(!tmp.isNullOrEmpty() && acqCode.equals("PAYDEE", true)) {
            tmp = ""
        }
        receiptInfo[14] = tmp.trim()

        tmp = getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ARQC, 16)
        receiptInfo[15] = tmp
        receiptInfo[16] = aid

        tmp = getFromTransactionDb(Global.cube.CUBE_TAG_CARD_TVR, 16)
        receiptInfo[17] = tmp
        receiptInfo[18] = cvm

        return receiptInfo
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun generateReceiptInfoQr(): Array<String> {
        //0. Txn Dt
        //1. E-Wallet
        //2. Txn Type
        //3. MID
        //4. TID
        //5. Approval Code
        //6. Host Ref
        //7. Amount (exclude if UPIQR)
        //8. Ref ID

        val receiptInfo = Array(9) { _ -> ""  }

        receiptInfo[0] = Utils.DateTimeFormat(transDateAsci)
        receiptInfo[1] = qrPayBrand
        receiptInfo[2] = Utils.getTxnType(txnTypeLabel)
        receiptInfo[3] = mid
        receiptInfo[4] = tid
        receiptInfo[5] = qrApprovalCode
        receiptInfo[6] = qrHostRef
        if (isUPIQR && upiVoucherCode.isNotEmpty()){
            receiptInfo[7] =  qrRef
        } else {
            receiptInfo[7] = "RM" + Utils.getActualAmount(amount.toString())
            receiptInfo[8] = qrRef
        }

        return receiptInfo
    }


    @RequiresApi(Build.VERSION_CODES.O)
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
        transResult = Global.iso.err.failed
        approvalCode = ""
        rrn = ""
        cvm = ""
    }
}