package com.sc.mf919.kotlin.activity

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.library.terminal.Utility
import com.sc.mf919.R
import constants.TerminalConstants
import com.sc.mf919.java.activity.Utils
import utils.HexUtil
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.HTTPServer
import mdb.MdbController
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

open class CardPaymentActivity : EmvActivity() {
    lateinit var mContext: Context
    lateinit var tempModel: String
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    private var posReference: String? = null
    var txnAmount: Long = 0
    var amountString = ""
    var cashOutAmount: Long = 0

    //Sale Completion with card presented (salesType == 4, re-scan card through EMV)
    private var scApprCode: String? = null
    private var scRrn: String? = null
    private var scInvNo: String? = null
    private var scBatchNo: String? = null
    private var scCardPan: String? = null

    var isOnBackPress = false

    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cardpayment)
        HTTPServer.getInstance().attendActivityContext = this@CardPaymentActivity
        val toolbar = findViewById<Toolbar>(R.id.toolbarCP)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { customOnBackPress() }
        })

        mContext = this@CardPaymentActivity
        tempModel = ServiceHolder.getDeviceModel().uppercase()
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Card Payment Detection"
        )
        helperLog.appendLine(helperLogClassName, "Initialize CardPayment Activity")
        typeOfSales = intent.getIntExtra("typeofSale", 0)
        amountString = intent.getStringExtra("txnAmt") ?: "0"
        txnAmount = amountString.replace(".", "").toLong()
        //txnAmount = intent.getLongExtra("txnAmt", 0)

        //TODO CASHOUT
        val cashOutAmountString = intent.getStringExtra("cashOutAmt") ?: "0"
        cashOutAmount = cashOutAmountString.replace(".", "").toLong()
        //TODO CASHOUT

        posReference = intent.getStringExtra("posReference")
        //ApprCode = intent.getStringExtra("apprCode")
        //Rrn = intent.getStringExtra("rrn")
        //InvNo = intent.getStringExtra("invNo")

        //Sale Completion with card presented (salesType == 4, re-scan card through EMV)
        scApprCode = intent.getStringExtra("apprCode")
        scRrn = intent.getStringExtra("rrn")
        scInvNo = intent.getStringExtra("invNo")
        scBatchNo = intent.getStringExtra("preAuthBatchNo")
        scCardPan = intent.getStringExtra("cardPan")
        helperLog.appendLine(helperLogClassName, "CardPaymentActivity (OnCreate): $typeOfSales")
        val textViewAmount = findViewById<TextView>(R.id.textView_amountDisplay)
        textViewAmount.text = amountString

        lifecycleScope.launch {
            searchCardCoroutines()
        }
        Toast.makeText(mContext, "Search card, please insert or wave", Toast.LENGTH_SHORT).show()
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private suspend fun searchCardCoroutines() = withContext(Dispatchers.IO) {
        // A previous authorization may still be on the wire, and its response will be parsed
        // into the global TransData whatever happened to the screen that started it. Calling
        // reset() now silently re-owns that singleton underneath a live transaction: the
        // approval then lands carrying this activity's acq/mid/tid and the settlement update
        // targets the wrong row, or no row at all. Refuse to start instead of corrupting it.
        if (IsoActivity.isHostRequestInFlight) {
            helperLog.appendLine(helperLogClassName, "ABORT Search Card :: previous host request " +
                "still in flight, refusing to reset TransData")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
            // Leave the EMV kernel alone -- it still belongs to the in-flight transaction.
            // Clearing isNotEnd keeps onDestroy's resetEMV() a no-op so it cannot terminate
            // that transaction's card session on our way out.
            isNotEnd = false
            withContext(Dispatchers.Main) {
                Toast.makeText(mContext, "Previous transaction still processing", Toast.LENGTH_LONG).show()
                finish()
            }
            return@withContext
        }

        helperLog.appendLine(helperLogClassName, "Searching Card Reset Transaction Data")
        TransData.payMethod = TerminalConstants.paymentMethod.Non
        TransData.reset(mContext)

        val merchantConfig = ServiceHolder.getMerchantInfo()
        TransData.tpaMid = DbModelMerchantConfig.getSafeValue(merchantConfig, "ScMid")
        TransData.tpaTid = DbModelMerchantConfig.getSafeValue(merchantConfig, "ScTid")
        // Gavin Predefined some data
        posReference?.let {
            helperLog.appendLine(helperLogClassName, "Add Pos Reference :: ", it)
            TransData.posReference = it
        }

        ServiceHolder.saleModelCache?.let {
            TransData.salesType = it.SalesType
            TransData.acqCode = it.AcqCode ?: ""
            TransData.mid = it.AcqMid ?: Utils.paddingWith("", "0", 12, true)
            TransData.tid = it.AcqTid ?: Utils.paddingWith("", "0", 8, true)
            TransData.product = it.Product ?: ""
            TransData.productName = it.ProductName ?: ""
            TransData.productCode = it.EppProductCode ?: ""
            TransData.eppTenure = it.EppTenure ?: ""
            TransData.eppTenureCode = it.EppTenureCode ?: ""
            TransData.ksn = it.Ksn ?: ""
            TransData.pinKsn = it.PinKsn ?: ""
            TransData.isTpaAccount = it.IsTpaAccount?.lowercase() == "true"
            TransData.denominationType = it.DenominationType ?: ""
            TransData.denominationProduct = it.DenominationProduct
        }

        when (TransData.salesType){
            8 -> TransData.txnTypeLabel = "Pre Authorization"
            4 -> TransData.txnTypeLabel = "Sale Completion"
            ProductCatSelectionDataEnum.CASH_OUT.data.SalesType -> TransData.txnTypeLabel = "Cash Out"
            ProductCatSelectionDataEnum.EPP.data.SalesType -> TransData.txnTypeLabel = "Instalment Sale"
            else -> TransData.txnTypeLabel = "Sale"
        }

        //Sale Completion with card presented -> keep the original preauth references for processSaleCompCardPresented
        if (TransData.salesType == 4) {
            TransData.reqInvoiceNo = scInvNo ?: ""
            TransData.reqApprovalCode = scApprCode ?: ""
            TransData.reqRrn = scRrn ?: ""
            TransData.reqBatchNo  = scBatchNo ?: ""
            TransData.reqCardPan  = scCardPan ?: ""
            helperLog.appendLine(helperLogClassName, "Sale Completion (Card Presented) :: apprCode=$scApprCode, rrn=$scRrn, invNo=$scInvNo")
            helperLog.appendLine(helperLogClassName, "Sale Completion (Card Presented) :: preAuthBatchNo=$scBatchNo")
        }

        TransData.amount = txnAmount
        HexUtil.hexStringToByte(Utils.zeroPadding(txnAmount.toString(), 12)).copyInto(TransData.amountAuth)
        helperLog.appendLine(helperLogClassName, "Transaction Amount :: $txnAmount")
        TransData.cashOutAmount = cashOutAmount
        HexUtil.hexStringToByte(Utils.zeroPadding(cashOutAmount.toString(), 12)).copyInto(TransData.cashOutAmountAuth)
        helperLog.appendLine(helperLogClassName, "CashOut Amount :: $cashOutAmount")

        helperLog.appendLine(helperLogClassName, "Start Search Card")
        //resetEMV()
        //Thread.sleep(500L)
        startEMV(mContext, amountString, cashOutAmount, false, helperLog)
        while (isNotEnd){
            if(MdbController.mdbVending && MdbController.mdbVendingForceEnd) {
                stopSearch()
                endEMV()
                TransData.stan = ""
                TransData.invoiceNo = ""
                TransData.respCode = Utility.ASCIItoHexString("SHC005")
                MdbController.mdbVending = false
                MdbController.mdbVendingForceEnd = false
            }
            Thread.sleep(500L)
        }
        helperLog.appendLine(helperLogClassName, "Search Card End")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        navigateToResult()
    }

    private fun navigateToResult() {
        var intent = Intent(mContext, TransactionResultActivity::class.java)

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "DENOMINATION")) {
            if(isOnBackPress) {
                if(MdbController.mdbVending) {
                    MdbController.sendVendDenied()
                }
                intent = Intent(mContext, AttendDenominationActivity::class.java)
            } else {
                intent = Intent(mContext, DenominationTransactionResultActivity::class.java)
            }
        } else if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE") && tempModel == ServiceHolder.SR800_MODEL) {
            intent = Intent(mContext, DenominationTransactionResultActivity::class.java)
        }

        intent.putExtra("Cube", cube!!.cubess_tlv_db)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")

        // Once the 0200 is on the wire the transaction is no longer ours to cancel: the host may
        // already have approved it, and the response lands in the global TransData regardless of
        // what happens to this screen. Clearing stan/invoiceNo here would corrupt that live
        // authorization exactly the way a mid-flight teardown did. Swallow the press instead --
        // the screen stays up, the polling loop below still ends it once the response arrives.
        if (IsoActivity.isHostRequestInFlight) {
            helperLog.appendLine(helperLogClassName, "IGNORE OnBack Press :: host request in flight, cannot cancel")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            Toast.makeText(mContext, "Processing, please wait", Toast.LENGTH_SHORT).show()
            return
        }

        isOnBackPress = true
        if (isNotEnd) {
            stopSearch()
            endEMV()
            TransData.stan = ""
            TransData.invoiceNo = ""
            TransData.respCode = Utility.ASCIItoHexString("SHC005")
        }
    }

    override fun onResume() {
        super.onResume()
        val myKM = this.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (myKM.isKeyguardLocked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                myKM.requestDismissKeyguard(this, null)
            } else window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    // Replaces android:noHistory on this activity. noHistory exists so an App2App / ECR launch can
    // never surface a leftover payment screen still holding a previous transaction's amount and
    // cached state -- that requirement stands. The problem was only that the framework applied it
    // unconditionally, including while an authorization was on the wire, which killed the screen
    // mid-transaction with no interceptable callback and lost the sale.
    //
    // Doing it here keeps the guarantee (backgrounded => finished, nothing to come back to) but
    // defers the one case where tearing down loses money. Nothing is leaked by deferring: the
    // response arrives, the poll loop ends, and navigateToResult() finishes this activity itself.
    // See obsidian FIX-2026-08-04-noHistory-MidAuthorization-Teardown.
    override fun onStop() {
        super.onStop()
        if (isFinishing) return

        if (IsoActivity.isHostRequestInFlight) {
            helperLog.appendLine(helperLogClassName, "Backgrounded with host request in flight :: " +
                "deferring finish so the authorization is not lost")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return
        }

        // No card has been authorized yet, so this is the safe case noHistory was written for.
        // Plain finish(), matching the previous behaviour exactly -- onDestroy's resetEMV() below
        // still disarms the reader because isNotEnd is untouched here.
        helperLog.appendLine(helperLogClassName, "Backgrounded while idle :: finishing (noHistory equivalent)")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()

        // True end of the transaction. The six earlier logToFile calls each close their own
        // segment; this one closes whatever was appended after the last of them, so no tail of
        // the flow is left sitting in the buffer when the activity goes away.
        helperLog.appendLine(helperLogClassName, "CardPayment OnDestroy :: transaction screen ended")
        helperLog.logToFile(EnumLogFileName.TerminaLog)

        CoroutineScope(Dispatchers.IO).launch {
            Utils.debugLogPrint("CardPayment OnDestroy", "Resetting EMV")
            resetEMV()
        }
    }
}