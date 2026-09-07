package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.sc.mf919.R
import com.sc.mf919.java.activity.TransactionTransmitter
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class TransactionResultQrActivity : AppCompatActivity(), FragmentResultQr.OnFragmentInteractionListener, FragmentReceiptQr.OnFragmentInteractionListener {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog
    lateinit var mContext: Context

    private var desc: String = ""

    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    //lateinit var acqLogoEnumModel: AcquirerLogoEnumModel

    //App to App
    lateinit var txn_map: HashMap<String, String>

    override fun fragmentResultQrBackAction() {
        if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "Selected :: DONE [QR RESULT]")
        customOnBackPress()
    }

    override fun fragmentReceiptQrBackAction() {
        if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "Selected :: DONE [QR RECEIPT]")
        customOnBackPress()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transactionresult_qr)
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
        ServiceHolder.appRunningProcess = false

        mContext = this@TransactionResultQrActivity
        HTTPServer.getInstance().attendActivityContext = this@TransactionResultQrActivity
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Transaction Result QR Main"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Transaction Result QR Activity")

        /*acqLogoEnumModel = AcquirerLogoEnumModel(R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, resources.getString(R.string.app_name_about))
        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        if (dbModelMerchantConfig != null) {
            try {
                acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(dbModelMerchantConfig!!.AcqCode!!).data
            } catch (e: Exception) {
                e.printStackTrace()
                helperLog.appendLine(helperLogClassName, "Exception in QR Sales Get Acquirer Enum -> ", e.toString())
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }
        }*/

        //App to App
        txn_map = HashMap()

        desc = if (TransData.qrRespCode == "0000") "Approved" else "Failed"

        CoroutineScope(Dispatchers.Default).launch {
            helperLog.appendLine(helperLogClassName, "Response Code :: ", TransData.qrRespCode)
            helperLog.appendLine(helperLogClassName, "${TransData.txnTypeLabel} result :: $desc for refId ${TransData.qrRef} / hostRef ${TransData.qrHostRef}")
            if(TransData.qrRespCode == "0000"){
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

    private fun processApprovedTransaction() {
        supportFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentReceiptQr()).addToBackStack(null).commit()
    }

    private fun processDeclinedTransaction() {
        supportFragmentManager.beginTransaction().replace(R.id.transFrameLayout, FragmentResultQr()).addToBackStack(null).commit()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun processBackgroundTask() = withContext(Dispatchers.Default) {
        helperLog.appendLine(helperLogClassName, "Process Background Task")
        if(ServiceHolder.appHTTP) {
            helperLog.appendLine(helperLogClassName, "App HTTP task")
            val jsonObject = JSONObject()

            try {
                jsonObject.put("ResponseCode", TransData.qrRespCode)
                jsonObject.put("ResponseDescription", desc)
                jsonObject.put("TransactionType", ServiceHolder.txnType.toString())
                jsonObject.put("TransactionAmount", Utils.getActualAmount(TransData.amount.toString()))
                jsonObject.put("TransactionId", TransData.qrHostRef)
                jsonObject.put("TransactionRefId", TransData.qrRef)
                jsonObject.put("TransactionEWallet", TransData.qrPayBrand)
                jsonObject.put("TransactionDateTime", Utils.DateTimeFormat(TransData.transDateAsci))
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
            txn_map["ResponseCode"] = TransData.qrRespCode
            txn_map["ResponseDescription"] = desc
            txn_map["TransactionType"] = ServiceHolder.txnType.toString()
            txn_map["TransactionAmount"] = Utils.getActualAmount(TransData.amount.toString())
            txn_map["TransactionId"] = TransData.qrHostRef
            txn_map["TransactionRefId"] = TransData.qrRef
            txn_map["TransactionEWallet"] = TransData.qrPayBrand
            txn_map["TransactionDateTime"] = Utils.DateTimeFormat(TransData.transDateAsci)
            txn_map["PosReference"] = TransData.posReference


            helperLog.appendLine(helperLogClassName, "Response TxnMap :: ", txn_map.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            //onBackToApp()
        }
    }

    fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving QR transaction result")
        ServiceHolder.selectedCacheModel = null

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        if(ServiceHolder.appIntent) {
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionTransmitter")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            onBackToApp()
        } else{
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> home screen")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
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
        val intent = Intent(this, TransactionTransmitter::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra("txn_map", txn_map)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "TransactionResultQr OnDestroy :: QR result screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}