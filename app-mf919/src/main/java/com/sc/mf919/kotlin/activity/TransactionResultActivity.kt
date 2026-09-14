package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.library.terminal.Utility
import com.sc.mf919.R
import constants.TerminalConstants
import com.sc.mf919.java.activity.TransactionTransmitter
import com.sc.mf919.java.activity.Utils
import data_enum.CardErrorDataEnum
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import helpers.LogRedact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.StringUtils
import org.json.JSONException
import org.json.JSONObject

class TransactionResultActivity : AppCompatActivity(), FragmentResult.OnFragmentInteractionListener, FragmentReceipt.OnFragmentInteractionListener {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog
    lateinit var mContext: Context

    private var desc: String = ""

    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    //lateinit var details: Array<String>
    //lateinit var acqLogoEnumModel: AcquirerLogoEnumModel

    //App to App
    lateinit var txn_map: HashMap<String, String>

    override fun fragmentResultBackAction() {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "Selected :: Done [DECLINED RESULT]")
        }
        customOnBackPress()
    }

    override fun fragmentReceiptBackAction() {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "Selected :: Done [RECEIPT]")
        }
        customOnBackPress()
    }

    private var cardDataGeneration = 0L

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // D11 -- the card registration in force when this screen appeared, i.e. this transaction's.
        // Taken here, not in onDestroy, because by then the next transaction may already have
        // registered and clearing then would wipe its data instead of ours.
        cardDataGeneration = LogRedact.currentGeneration()
        setContentView(R.layout.activity_transactionresult)
        /*val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.appToolbar)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }*/
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
        ServiceHolder.appRunningProcess = false

        mContext = this@TransactionResultActivity
        HTTPServer.getInstance().attendActivityContext = this@TransactionResultActivity
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Transaction Result Main"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Transaction Result Activity")

        /*acqLogoEnumModel = AcquirerLogoEnumModel(R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, resources.getString(R.string.app_name_about))
        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        if (dbModelMerchantConfig != null) {
            try {
                acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(dbModelMerchantConfig!!.AcqCode!!).data
            } catch (e: Exception) {
                e.printStackTrace()
                helperLog.appendLine(helperLogClassName, "Exception in Card Sales Get Acquirer Enum -> ", e.toString())
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }
        }*/

        //details = TransData.generateReceiptInfo()
        //App to App
        txn_map = HashMap()
        var respCode = ""
        try {
            respCode = Utility.HexString2ASCII(TransData.respCode)
            val formedEnumTag = "TAG_$respCode"
            desc = "(" + respCode + ")" + CardErrorDataEnum.valueOf(formedEnumTag).data
        } catch (e: Exception) {
            desc = respCode.ifEmpty { "Failed" }
            helperLog.appendLine(helperLogClassName, "Card Error Enum not Found")
            helperLog.appendLine(helperLogClassName, "Acquirer Response Code:", respCode)
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
        }

        CoroutineScope(Dispatchers.Default).launch {
            val transactionResult = TransData.transResult
            helperLog.appendLine(helperLogClassName, "Transaction Result :: ", "$transactionResult")
            if(transactionResult == TerminalConstants.iso.err.txnApproved){
                helperLog.appendLine(helperLogClassName, "Transaction Approved...")
                processApprovedTransaction()
            } else {
                helperLog.appendLine(helperLogClassName, "Transaction Declined")
                processDeclinedTransaction()
            }

            processBackgroundTask()
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    private suspend fun processApprovedTransaction() = withContext(Dispatchers.Main) {
        // Fragment commits are main-thread only. This ran on the caller's Dispatchers.Default
        // and raced FragmentManager's back-press callback list -- a ConcurrentModificationException
        // that killed the app after the sale was already authorised.
        supportFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentReceipt()).addToBackStack(null).commit()
    }

    private suspend fun processDeclinedTransaction() = withContext(Dispatchers.Main) {
        supportFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentResult()).addToBackStack(null).commit()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun processBackgroundTask() = withContext(Dispatchers.Default) {
        helperLog.appendLine(helperLogClassName, "Process Background Task")
        if(ServiceHolder.appHTTP) {
            helperLog.appendLine(helperLogClassName, "App HTTP task")
            val jsonObject = JSONObject()

            try {
                val isCZ = TransData.acqCode.equals("BSN_CARDZONE", true)
                val eppDetail = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_EPP_DETAILS, 256)
                val respCode = Utility.HexString2ASCII(TransData.respCode)
                jsonObject.put("ResponseCode", respCode)
                jsonObject.put("ResponseDescription", desc)
                jsonObject.put("TransactionType", ServiceHolder.txnType.toString())
                jsonObject.put("TransactionAmount", Utils.getActualAmount(TransData.amount.toString()))
                jsonObject.put("TransactionMID", if(TransData.isTpaAccount) TransData.tpaMid else TransData.mid)
                jsonObject.put("TransactionTID", if(TransData.isTpaAccount) TransData.tpaTid else TransData.tid)
                jsonObject.put("TransactionSTN", TransData.stan)
                jsonObject.put("TransactionRRN", TransData.rrn)
                jsonObject.put("OriTransactionRRN", TransData.prevRRN.ifEmpty { TransData.rrn })
                jsonObject.put("OriTransactionApprovalCode", TransData.prevApprovalCode.ifEmpty { TransData.approvalCode })
                jsonObject.put("TransactionBatchNo", TransData.batchNo)
                jsonObject.put("TransactionApplicationLabel", Utils.byteArrayToAsciiString(TransData.appLabel, 0, TransData.appLabelLen))
                jsonObject.put("TransactionCardNo", TransData.maskedPan)
                jsonObject.put("TransactionEntryType", TransData.entryModeLabel)
                jsonObject.put("TransactionARQC", TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_ARQC, 16))
                jsonObject.put("TransactionTVR", TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_TVR, 16))
                jsonObject.put("TransactionAID", TransData.aid)
                jsonObject.put("TransactionCVM", TransData.cvm)
                jsonObject.put("TransactionTSI", "-")
                jsonObject.put("TransactionApprovalCode", TransData.approvalCode)
                jsonObject.put("TransactionInvoice", TransData.invoiceNo)
                jsonObject.put("TransactionSchemeID", TransData.schemeId)
                jsonObject.put("TransactionDateTime", Utils.DateTimeFormat(TransData.transDateAsci))
                jsonObject.put("TransactionEPP", parseEppDetailsJson(eppDetail, isCZ))
                jsonObject.put("PosReference", TransData.posReference)
            } catch (e: JSONException) {
                e.printStackTrace()
            }
            helperLog.appendLine(helperLogClassName, "Response Json :: ", jsonObject.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            onBackToHTTP(jsonObject.toString())
        }

        if(ServiceHolder.appIntent) {
            helperLog.appendLine(helperLogClassName, "App Intent task")
            val isCZ = TransData.acqCode.equals("BSN_CARDZONE", true)
            val eppDetail = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_EPP_DETAILS, 256)
            val respCode = Utility.HexString2ASCII(TransData.respCode)

            txn_map["ResponseCode"] = respCode
            txn_map["ResponseDescription"] = desc
            txn_map["TransactionType"] = ServiceHolder.txnType.toString()
            txn_map["TransactionAmount"] = Utils.getActualAmount(TransData.amount.toString())
            txn_map["TransactionMID"] = if(TransData.isTpaAccount) TransData.tpaMid else TransData.mid
            txn_map["TransactionTID"] = if(TransData.isTpaAccount) TransData.tpaTid else TransData.tid
            txn_map["TransactionSTN"] = TransData.stan
            txn_map["TransactionRRN"] = TransData.rrn
            txn_map["OriTransactionRRN"] = TransData.prevRRN.ifEmpty { TransData.rrn }
            txn_map["OriTransactionApprovalCode"] = TransData.prevApprovalCode.ifEmpty { TransData.approvalCode }
            txn_map["TransactionBatchNo"] = TransData.batchNo
            txn_map["TransactionApplicationLabel"] = Utils.byteArrayToAsciiString(TransData.appLabel, 0, TransData.appLabelLen)
            txn_map["TransactionCardNo"] = TransData.maskedPan
            txn_map["TransactionEntryType"] = TransData.entryModeLabel
            txn_map["TransactionARQC"] = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_ARQC, 16)
            txn_map["TransactionTVR"] = TransData.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CARD_TVR, 16)
            txn_map["TransactionAID"] = TransData.aid
            txn_map["TransactionCVM"] = TransData.cvm
            txn_map["TransactionTSI"] = "-"
            txn_map["TransactionApprovalCode"] = TransData.approvalCode
            txn_map["TransactionInvoice"] = TransData.invoiceNo
            txn_map["TransactionSchemeID"] = TransData.schemeId
            txn_map["TransactionDateTime"] = Utils.DateTimeFormat(TransData.transDateAsci)
            txn_map["TransactionEPP"] = parseEppDetailsJson(eppDetail, isCZ).toString()
            txn_map["PosReference"] = TransData.posReference

            helperLog.appendLine(helperLogClassName, "Response TxnMap :: ", txn_map.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            //onBackToApp()
        }
    }

    private fun parseEppDetailsJson(eppDe63: String?, isCZ: Boolean): Any {
        val eppDetails = JSONObject()
        val strEppDetails = StringUtils.trim(eppDe63)
        if (strEppDetails.isNullOrEmpty()) {
            /*eppDetails.put("Tenure", "-")
            eppDetails.put("TotalAmt", "0.00")
            eppDetails.put("FirstMonthAmt", "0.00")
            eppDetails.put("MonthlyAmt", "0.00")
            eppDetails.put("FinalAmt", "0.00")*/
            return "-"
        } else {
            eppDetails.put("Tenure", strEppDetails.substring(0, 3))
            if (isCZ){
                /*eppDetails["FirstMonthAmt"] = Utils.getActualAmount(strEppDetails.substring(4, 15))
                eppDetails["MonthlyAmt"] = Utils.getActualAmount(strEppDetails.substring(16, 27))
                eppDetails["TotalAmt"] = Utils.getActualAmount(strEppDetails.substring(40))
                eppDetails["FinalAmt"] = "0.00"*/
                eppDetails.put("TotalAmt", Utils.getActualAmount(strEppDetails.substring(40)))
                eppDetails.put("FirstMonthAmt", Utils.getActualAmount(strEppDetails.substring(4, 15)))
                eppDetails.put("MonthlyAmt", Utils.getActualAmount(strEppDetails.substring(16, 27)))
                eppDetails.put("FinalAmt", "0.00")
            } else {
                /*eppDetails["TotalAmt"] = Utils.getActualAmount(strEppDetails.substring(48))
                eppDetails["FirstMonthAmt"] = Utils.getActualAmount(strEppDetails.substring(22, 35))
                eppDetails["MonthlyAmt"] = Utils.getActualAmount(strEppDetails.substring(22, 35))
                eppDetails["FinalAmt"] = Utils.getActualAmount(strEppDetails.substring(9, 22))*/
                eppDetails.put("TotalAmt", Utils.getActualAmount(strEppDetails.substring(48)))
                eppDetails.put("FirstMonthAmt", Utils.getActualAmount(strEppDetails.substring(22, 35)))
                eppDetails.put("MonthlyAmt", Utils.getActualAmount(strEppDetails.substring(22, 35)))
                eppDetails.put("FinalAmt", Utils.getActualAmount(strEppDetails.substring(9, 22)))
            }
        }
        Utils.debugLogPrint("TAG", "eppDetails = $eppDetails")
        return eppDetails
    }

    fun customOnBackPress() {
        val logReady = this::helperLog.isInitialized
        if (logReady) {
            helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving transaction result")
        }
        ServiceHolder.selectedCacheModel = null

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        if(ServiceHolder.appIntent) {
            onBackToApp()
        } else{
            val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (logReady) {
                helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> home screen")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
            }
            startActivity(newIntent)
            finish()
        }
    }

    private fun onBackToHTTP(msg: String) {
        HTTPServer.getInstance().setResponseMessage(msg)
        ServiceHolder.appHTTP = false
    }

    private fun onBackToApp() {
        val intent = Intent(this, TransactionTransmitter::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra("txn_map", txn_map)
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionTransmitter")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // D7 -- the in-flight card data must not outlive the transaction. D11 -- but only if it is
        // still ours; a void started while this screen was going away has registered its own.
        LogRedact.clearCardData(cardDataGeneration)
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "TransactionResultActivity OnDestroy :: transaction result screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}