package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.library.terminal.Utility
import com.sc.mf919.BuildConfig
import com.sc.mf919.R
import com.sc.mf919.java.activity.Global
import com.sc.mf919.java.activity.TransactionTransmitter
import com.sc.mf919.java.activity.Utils
import data_enum.CardErrorDataEnum
import com.sc.mf919.kotlin.helper_common.HTTPServer
import mdb.MdbController
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919.kotlin.helper_common.MfHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.StringUtils
import org.json.JSONException
import org.json.JSONObject
import kotlin.collections.set

class DenominationTransactionResultActivity: AppCompatActivity(), FragmentDenominationResult.OnFragmentInteractionListener, FragmentDenominationQrResult.OnFragmentInteractionListener, FragmentSimpleDenominationResult.OnFragmentInteractionListener {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog
    lateinit var mContext: Context
    lateinit var tempModel: String

    //App to App
    lateinit var txn_map: HashMap<String, String>
    private var desc: String = ""

    override fun fragmentDenominationBackAction() {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "User Cancel :: result fragment back action")
        }
        customOnBackPress()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_result_new)
        val toolbar = findViewById<Toolbar>(R.id.appToolbar)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: toolbar back pressed on denomination result")
            customOnBackPress()
        }
        toolbar.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: toolbar tapped on denomination result")
            customOnBackPress()
        }
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
        mContext = this@DenominationTransactionResultActivity
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Transaction Result Denomination Main"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Transaction Result Denomination Activity")

        //App to App
        txn_map = HashMap()
        if(TransData.qrRef.isEmpty()) {
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
        } else {
            desc = if (TransData.qrRespCode == "0000") "Approved" else "Failed"
        }

        helperLog.appendLine(helperLogClassName, "Denomination payment result known :: $desc invoice=${TransData.invoiceNo} rrn=${TransData.rrn} amount=${Utils.getActualAmount(TransData.amount.toString())} denomination=${TransData.denominationProduct?.Desc ?: "-"}")
        helperLog.logToFile(EnumLogFileName.TerminaLog)

        tempModel = ServiceHolder.getDeviceModel().uppercase()
        if (tempModel == ServiceHolder.SR800_MODEL){
            supportFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentSimpleDenominationResult()).addToBackStack(null).commit()
        } else {
            if(TransData.qrRef.isEmpty()) {
                supportFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentDenominationResult()).addToBackStack(null).commit()
            } else {
                supportFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentDenominationQrResult()).addToBackStack(null).commit()
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            TransData.denominationProduct?.let {
                if(TransData.transResult == Global.iso.err.txnApproved || TransData.qrRespCode == "0000") {
                    val pulseCount = Utils.atoi(it.Ref1)
//                    AisinoHelper.outputPulseSignal(pulseCount)
                }
            }


            if (ServiceHolder.appHTTP || ServiceHolder.appIntent){
                processBackgroundTask()
                helperLog.logToFile(EnumLogFileName.TerminaLog)

            }

            if(MdbController.mdbVending) {
                if(TransData.transResult == Global.iso.err.txnApproved || TransData.qrRespCode == "0000") {
                    helperLog.appendLine(helperLogClassName, "Vend APPROVED :: notifying VMC, invoice=${TransData.invoiceNo}")
                    helperLog.logToFile(EnumLogFileName.TerminaLog)
                    MdbController.sendVendApproved()
                } else {
                    helperLog.appendLine(helperLogClassName, "Vend DENIED :: payment not approved ($desc), notifying VMC, invoice=${TransData.invoiceNo}")
                    helperLog.logToFile(EnumLogFileName.TerminaLog)
                    MdbController.sendVendDenied()
                }
            }
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun processBackgroundTask() = withContext(Dispatchers.Default) {
        helperLog.appendLine(helperLogClassName, "Process Background Task")
        if(ServiceHolder.appHTTP) {
            helperLog.appendLine(helperLogClassName, "App HTTP task")
            val jsonObject = JSONObject()

            try {
                if(TransData.qrRef.isEmpty()) {
                    val isCZ = TransData.acqCode.equals("BSN_CARDZONE", true)
                    val eppDetail = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_EPP_DETAILS, 256)
                    val respCode = Utility.HexString2ASCII(TransData.respCode)
                    jsonObject.put("ResponseCode", respCode)
                    jsonObject.put("ResponseDescription", desc)
                    jsonObject.put("TransactionType", ServiceHolder.txnType.toString())
                    jsonObject.put("TransactionAmount", Utils.getActualAmount(TransData.amount.toString()))
                    jsonObject.put("TransactionMID", TransData.mid)
                    jsonObject.put("TransactionTID", TransData.tid)
                    jsonObject.put("TransactionSTN", TransData.stan)
                    jsonObject.put("TransactionRRN", TransData.rrn)
                    jsonObject.put("OriTransactionRRN", TransData.prevRRN.ifEmpty { TransData.rrn })
                    jsonObject.put("OriTransactionApprovalCode", TransData.prevApprovalCode.ifEmpty { TransData.approvalCode })
                    jsonObject.put("TransactionBatchNo", TransData.batchNo)
                    jsonObject.put("TransactionApplicationLabel", Utils.byteArrayToAsciiString(TransData.appLabel, 0, TransData.appLabelLen))
                    jsonObject.put("TransactionCardNo", TransData.maskedPan)
                    jsonObject.put("TransactionEntryType", TransData.entryModeLabel)
                    jsonObject.put("TransactionARQC", TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ARQC, 16))
                    jsonObject.put("TransactionTVR", TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_TVR, 16))
                    jsonObject.put("TransactionAID", TransData.aid)
                    jsonObject.put("TransactionCVM", TransData.cvm)
                    jsonObject.put("TransactionTSI", "-")
                    jsonObject.put("TransactionApprovalCode", TransData.approvalCode)
                    jsonObject.put("TransactionInvoice", TransData.invoiceNo)
                    jsonObject.put("TransactionSchemeID", TransData.schemeId)
                    jsonObject.put("TransactionDateTime", Utils.DateTimeFormat(TransData.transDateAsci))
                    jsonObject.put("TransactionEPP", parseEppDetailsJson(eppDetail, isCZ))
                    jsonObject.put("PosReference", TransData.posReference)
                } else {
                    jsonObject.put("ResponseCode", TransData.qrRespCode)
                    jsonObject.put("ResponseDescription", desc)
                    jsonObject.put("TransactionType", ServiceHolder.txnType.toString())
                    jsonObject.put("TransactionAmount", Utils.getActualAmount(TransData.amount.toString()))
                    jsonObject.put("TransactionId", TransData.qrHostRef)
                    jsonObject.put("TransactionRefId", TransData.qrRef)
                    jsonObject.put("TransactionEWallet", TransData.qrPayBrand)
                    jsonObject.put("TransactionDateTime", Utils.DateTimeFormat(TransData.transDateAsci))
                    jsonObject.put("PosReference", TransData.posReference)
                }
            } catch (e: JSONException) {
                e.printStackTrace()
                helperLog.appendLine(helperLogClassName, "Build response JSON (Exception) -> $e")
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }
            helperLog.appendLine(helperLogClassName, "Response Json :: ", jsonObject.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            onBackToHTTP(jsonObject.toString())
        }

        if(ServiceHolder.appIntent) {
            helperLog.appendLine(helperLogClassName, "App Intent task")
            //TODO form and return for app intent request
            val isCZ = TransData.acqCode.equals("BSN_CARDZONE", true)
            val eppDetail = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_EPP_DETAILS, 256)
            val respCode = Utility.HexString2ASCII(TransData.respCode)

            if(TransData.qrRef.isEmpty()) {
                txn_map["ResponseCode"] = respCode
                txn_map["ResponseDescription"] = desc
                txn_map["TransactionType"] = ServiceHolder.txnType.toString()
                txn_map["TransactionAmount"] = Utils.getActualAmount(TransData.amount.toString())
                txn_map["TransactionMID"] = TransData.mid
                txn_map["TransactionTID"] = TransData.tid
                txn_map["TransactionSTN"] = TransData.stan
                txn_map["TransactionRRN"] = TransData.rrn
                txn_map["OriTransactionRRN"] = TransData.prevRRN.ifEmpty { TransData.rrn }
                txn_map["OriTransactionApprovalCode"] = TransData.prevApprovalCode.ifEmpty { TransData.approvalCode }
                txn_map["TransactionBatchNo"] = TransData.batchNo
                txn_map["TransactionApplicationLabel"] = Utils.byteArrayToAsciiString(TransData.appLabel, 0, TransData.appLabelLen)
                txn_map["TransactionCardNo"] = TransData.maskedPan
                txn_map["TransactionEntryType"] = TransData.entryModeLabel
                txn_map["TransactionARQC"] = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ARQC, 16)
                txn_map["TransactionTVR"] = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_TVR, 16)
                txn_map["TransactionAID"] = TransData.aid
                txn_map["TransactionCVM"] = TransData.cvm
                txn_map["TransactionTSI"] = "-"
                txn_map["TransactionApprovalCode"] = TransData.approvalCode
                txn_map["TransactionInvoice"] = TransData.invoiceNo
                txn_map["TransactionSchemeID"] = TransData.schemeId
                txn_map["TransactionDateTime"] = Utils.DateTimeFormat(TransData.transDateAsci)
                txn_map["TransactionEPP"] = parseEppDetailsJson(eppDetail, isCZ).toString()
                txn_map["PosReference"] = TransData.posReference
            } else {
                txn_map["ResponseCode"] = TransData.qrRespCode
                txn_map["ResponseDescription"] = desc
                txn_map["TransactionType"] = ServiceHolder.txnType.toString()
                txn_map["TransactionAmount"] = Utils.getActualAmount(TransData.amount.toString())
                txn_map["TransactionId"] = TransData.qrHostRef
                txn_map["TransactionRefId"] = TransData.qrRef
                txn_map["TransactionEWallet"] = TransData.qrPayBrand
                txn_map["TransactionDateTime"] = Utils.DateTimeFormat(TransData.transDateAsci)
                txn_map["PosReference"] = TransData.posReference
            }
            helperLog.appendLine(helperLogClassName, "Response TxnMap :: ", txn_map.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            //onBackToApp()
        }
    }

    private fun parseEppDetailsJson(eppDe63: String?, isCZ: Boolean): Any {
        val eppDetails = JSONObject()
        val strEppDetails = StringUtils.trim(eppDe63)
        if (strEppDetails.isNullOrEmpty()) {
            return "-"
        } else {
            eppDetails.put("Tenure", strEppDetails.substring(0, 3))
            if (isCZ){
                eppDetails.put("TotalAmt", Utils.getActualAmount(strEppDetails.substring(40)))
                eppDetails.put("FirstMonthAmt", Utils.getActualAmount(strEppDetails.substring(4, 15)))
                eppDetails.put("MonthlyAmt", Utils.getActualAmount(strEppDetails.substring(16, 27)))
                eppDetails.put("FinalAmt", "0.00")
            } else {
                eppDetails.put("TotalAmt", Utils.getActualAmount(strEppDetails.substring(48)))
                eppDetails.put("FirstMonthAmt", Utils.getActualAmount(strEppDetails.substring(22, 35)))
                eppDetails.put("MonthlyAmt", Utils.getActualAmount(strEppDetails.substring(22, 35)))
                eppDetails.put("FinalAmt", Utils.getActualAmount(strEppDetails.substring(9, 22)))
            }
        }
        return eppDetails
    }

    fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        MfHelper.closeNfcUrlInterface()
        ServiceHolder.selectedCacheModel = null

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        if(ServiceHolder.appIntent) {
            onBackToApp()
        } else{
            var newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
            val runningFlavor = BuildConfig.FLAVOR
            if(runningFlavor == "oxpay") {
                newIntent = Intent(applicationContext, AttendActivityOxpay::class.java)
            }
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> ${newIntent.component?.className ?: "HomeScreen"}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(newIntent)
            finish()
        }
    }

    private fun onBackToHTTP(msg: String) {
        HTTPServer.getInstance().setResponseMessage(msg)
        ServiceHolder.appHTTP = false
    }

    private fun onBackToApp() {
        helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionTransmitter")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val intent = Intent(this, TransactionTransmitter::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra("txn_map", txn_map)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "DenominationTransactionResult OnDestroy :: denomination result screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        MfHelper.closeNfcUrlInterface()
    }
}