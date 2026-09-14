package com.sc.mf919.kotlin.activity
import enums.EnumResponseCode

import android.content.Context
import android.content.Intent
import android.hardware.Camera
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.loader.app.LoaderManager
import androidx.loader.content.AsyncTaskLoader
import androidx.loader.content.Loader
import com.google.gson.Gson
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.kotlin.database.repo.BnplPayTableRepo
import com.sc.mf919.kotlin.activity.zxing.CaptureActivity
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import data_enum.SalesModel
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import org.json.JSONException
import org.json.JSONObject
import tms.handlers.ioupay.IoupayScanQrHandler
import tms.models.ioupay.IoupayScanQrResponseModel
import java.text.SimpleDateFormat
import java.util.*

class BnplScanActivity : ActivityBase(), LoaderManager.LoaderCallbacks<Any> {
    lateinit var mContext: Context
    private val TAG = "BNPL_QRSCAN"
    private val SCANNEDQR = 9000001
    lateinit var loaderManager: LoaderManager
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    /**
     * This class is constructed manually from the companion (see navigationToResultPage), so
     * onCreate never runs on those instances and helperLog stays uninitialised. Every log call
     * that a companion-reached path can hit must go through here.
     */
    private fun logBnpl(msg: String) {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, msg)
        } else {
            Utils.debugLogPrint(TAG, msg)
        }
    }

    companion object {
        var qrPayload = ""
        var qrSeqNo = ""
        var qrTxnDt = ""
        var qrRefId = ""
        var acqMid = ""
        var acqTid = ""
        var qrRespRefId = "" // remove maybe?
        var qrRespTxnRefNo = ""
        var qrRespHostRefNo = ""
        var qrRespPayBrand = ""
        var qrRespPayBrandDesc = ""
        var respCode = "1100"
        var qrRespAcqCode = ""
        var acqCode = ""
        var respDesc = ""
        var tenure = 0

        //var xTime=50
        var amt: String? = "000000000000"
        var tenureDesc: String? = ""
        var paymentType: String? = ""
        var packageCode: String? = ""
        var txn_map: HashMap<String, String> = hashMapOf()
        var jObject: JSONObject = JSONObject()

        lateinit var tempModel: String

        fun navigationToResultPage(context: Context) {
            BnplScanActivity().closeProgressDialog()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
            val txnDt = sdf.format(Date())

            //val cacheSales = ServiceHolder.selectedCacheModel as SalesModel
            //cacheSales.ProductName = qrRespPayBrand
            ServiceHolder.selectedCacheModel?.let {
                val tempModel = ServiceHolder.selectedCacheModel as SalesModel
                tempModel.ProductName = qrRespPayBrand
                //tempModel.AcqMid = Utils.maskString(acqMid, 4)
                //tempModel.AcqTid = Utils.maskString(acqTid, 4)
                ServiceHolder.selectedCacheModel = tempModel
            }

            tempModel = ServiceHolder.getDeviceModel().uppercase()

            var intent = Intent(context, TransactionResultQrActivity::class.java)
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE") && tempModel == ServiceHolder.SR800_MODEL) {
                intent = Intent(context, DenominationTransactionResultActivity::class.java)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra("txnType", "Sale")
            intent.putExtra("amount", "RM" + Utils.getActualAmount(amt))
            intent.putExtra("txnId", qrRespHostRefNo)
            intent.putExtra("txnRefId", qrRespRefId)
            intent.putExtra("txnRefId", qrRefId)
            intent.putExtra("txnDt", txnDt)
            intent.putExtra("respCode", respCode)
            intent.putExtra("eWallet", qrRespPayBrand)
            intent.putExtra("acqCode", qrRespAcqCode)
            context.startActivity(intent)
            BnplScanActivity().finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qrscan)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "BNPL QR Scan Payment"
        )
        amt = intent.getStringExtra("txnAmt")
        packageCode = intent.getStringExtra("packageCode")
        paymentType = intent.getStringExtra("paymentType")
        tenure = intent.getIntExtra("tenure", 0)
        tenureDesc = intent.getStringExtra("tenureDesc")
        (findViewById<View>(R.id.textView_amountDisplay) as TextView).text = amt
        amt = Utils.zeroPadding(amt!!.replace(".", ""), 12)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbarCP)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener {
            logBnpl("User Cancel :: toolbar back pressed, abandoning BNPL scan")
            onBackPressed()
        }

        mContext = this
        helperLog.appendLine(helperLogClassName, "Amount entered :: $amt")
        helperLog.appendLine(helperLogClassName, "BNPL scan opened :: tenure=$tenure tenureDesc=$tenureDesc paymentType=$paymentType packageCode=$packageCode")
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
        initializeData()
        loaderManager = LoaderManager.getInstance(this)
        scanner()
    }

    private fun initializeData() {
        respCode = "1100"
        acqMid = ""
        acqTid = ""
        qrRespRefId = "" // remove maybe?
        qrRespPayBrand = ""
        qrRespPayBrandDesc = ""
        qrRespTxnRefNo = ""
        qrRespHostRefNo = ""
        txn_map = hashMapOf()
        jObject = JSONObject()
    }

    fun customOnBackPress() {
        logBnpl("User Cancel :: BNPL QR scan abandoned")
        onBackPressedDispatcher.onBackPressed()
//        if (isScannerActive) {
//            try {
//                innerScanner!!.stopScan()
//            } catch (exception: RemoteException) {
//                exception.printStackTrace()
//            }
//        }
        txn_map = HashMap()
        txn_map["ResponseCode"] = EnumResponseCode.USER_CANCELLED.code
        txn_map["ResponseDescription"] = EnumResponseCode.USER_CANCELLED.description

        try {
            jObject.put("ResponseCode", EnumResponseCode.USER_CANCELLED.code)
            jObject.put("ResponseDescription", EnumResponseCode.USER_CANCELLED.description)
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        if (ServiceHolder.appIntent) {
            onBackToApp(txn_map)
        } else {
            if (ServiceHolder.appHTTP) {
                Log.d(TAG, "onBackPressed: ${jObject.toString()}")
                HTTPServer.getInstance().setResponseMessage(jObject.toString())
                ServiceHolder.appHTTP = false
            }
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            if (this::helperLog.isInitialized) {
                helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> HomeScreen")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
            }
            val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(newIntent)
            finish()
        }
    }

    private fun onBackToApp(txn_map: HashMap<String, String>) {
        txn_map["TransactionType"] = Integer.toString(ServiceHolder.txnType)
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionTransmitter")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        val intent: Intent = Intent(this, TransactionTransmitter::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra("txn_map", txn_map)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        // Manually-constructed instances (companion navigationToResultPage) never ran onCreate,
        // so neither helperLog nor loaderManager exists on them.
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "BnplScan OnDestroy :: BNPL QR scan screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        loaderManager.destroyLoader(SCANNEDQR)
        super.onDestroy()
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Any> {
        if (id == SCANNEDQR) {
            startProgressDialog(mContext, "Processing BNPL Payment", "Loading...")
            return BnplIoupayPaymentLoader(mContext)
        }
        return Loader(mContext)
    }

    override fun onLoadFinished(loader: Loader<Any>, data: Any) {
        val merchantInfo: DbModelMerchantConfig? = ServiceHolder.getMerchantInfo()
         if (merchantInfo == null || data == false) {
            helperLog.appendLine(helperLogClassName, "REJECT :: BNPL payment failed (merchantInfo=${merchantInfo != null}) respCode=$respCode :: navigate -> result page")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return navigationToResultPage(mContext)
        }

        //insert to db
        BnplPayTableRepo.insertToDb(
            applicationContext,
            "Sale",
            qrRespPayBrand,
            qrRespPayBrandDesc,
            qrTxnDt,
            qrSeqNo,
            acqMid,
            acqTid,
            amt,
            qrRefId,
            qrRespHostRefNo,
            qrRespTxnRefNo,
            "PENDING",
            "",
            qrRespAcqCode,
            "",
            packageCode,
            paymentType,
            tenure.toString(),
            tenureDesc,
            Gson().toJson(data)
        )

        helperLog.appendLine(helperLogClassName, "BNPL payment accepted :: respCode=$respCode brand=$qrRespPayBrand tenure=$tenure")
        helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> BnplIoupayViewDetailsActivity")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val intent = Intent(applicationContext, BnplIoupayViewDetailsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra("qrRefId", qrRefId)
        startActivity(intent);
        finish()
    }

    override fun onLoaderReset(loader: Loader<Any>) {
        logBnpl("BNPL payment loader reset")
        closeProgressDialog()
    }

    private val barcodeLauncher = registerForActivityResult<ScanOptions, ScanIntentResult>(
        ScanContract()
    ) { result: ScanIntentResult ->
        logBnpl("Scan result received :: hasContents=${result.contents != null}")
        if (result.contents == null) {
            val originalIntent = result.originalIntent
            if (originalIntent == null) {
                logBnpl("REJECT :: scan cancelled")
            } else if (originalIntent.hasExtra(Intents.Scan.MISSING_CAMERA_PERMISSION)) {
                logBnpl("REJECT :: scan cancelled due to missing camera permission")
            }
            customOnBackPress()
        } else {
            qrPayload = result.contents
            runOnUiThread {
                loaderManager.initLoader(SCANNEDQR, null, this@BnplScanActivity)
            }
        }
    }

    private fun scanner() {
        try{
            val scanOption = ScanOptions()
            scanOption.captureActivity = CaptureActivity::class.java
            //scanOption.setOrientationLocked(false)
            scanOption.setDesiredBarcodeFormats(ScanOptions.QR_CODE, ScanOptions.PDF_417, ScanOptions.CODE_39, ScanOptions.CODE_128)
            scanOption.setPrompt("")
            scanOption.setBeepEnabled(true)
            scanOption.setBarcodeImageEnabled(false)
            scanOption.setCameraId(Camera.CameraInfo.CAMERA_FACING_BACK)
            barcodeLauncher.launch(scanOption)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }

//        try {
//            innerScanner = DeviceHelper.getInnerScanner()
//            val bundle = Bundle()
//            bundle.putInt(ScannerConfig.COMM_SCANNER_TYPE, 1)
//            innerScanner?.initScanner(bundle)
//            innerScanner?.startScan(60, object : OnScannedListener.Stub() {
//                override fun onScanResult(retCode: Int, scanResult: ByteArray) {
//                    Utils.debugLogPrint(TAG, "onScanResult: $retCode")
//                    isScannerActive = false
//
//                    if (qrScanStatus == -1) {
//                        if (retCode == 0) {
//                            qrScanStatus = retCode
//                            qrPayload = String(scanResult)
//                            runOnUiThread {
//                                loaderManager.initLoader(SCANNEDQR, null, this@BnplScanActivity)
//                            }
//                        } else {
//                            customOnBackPress()
//                        }
//                    }
//                }
//            })
//        } catch (e: RemoteException) {
//            e.printStackTrace()
//        }
    }

    class BnplIoupayPaymentLoader(context: Context) : AsyncTaskLoader<Any>(context) {
        lateinit var log: HelperLog
        val logClassName = BnplScanActivity::class.java.name

        override fun onStartLoading() {
            log = HelperLog(
                HelperCommon.getSession(),
                TmsHelper.checkIsConnectedWifi(context),
                Utils.getIPAddress(),
                "BNPL Scan Activity",
                BnplScanActivity::class.java.simpleName,
                BnplScanActivity::class.java.name,
            )
            forceLoad()
        }

        override fun loadInBackground(): Any {
            try {
                val merchantInfo: DbModelMerchantConfig? = ServiceHolder.getMerchantInfo()
                if (merchantInfo == null) {
                    navigationToResultPage(context)
                }

                val res: Any = uploadPayload(qrPayload)
                if (res == false) {
                    log.appendLine(logClassName, "REJECT :: BNPL payload upload failed, respCode=$respCode")
                    log.logToFile(EnumLogFileName.TerminaLog)
                    return false
                }

                log.appendLine(logClassName, "BNPL payload upload finished :: respCode=$respCode")
                log.logToFile(EnumLogFileName.TerminaLog)
                return res
            } catch (e: Exception) {
                e.printStackTrace()
                log.appendLine(logClassName, "Failed Process BNPL payment")
                log.logToFile(EnumLogFileName.TerminaLogException)
                return false
            }
        }

        private fun uploadPayload(payload: String): Any {
            log.appendLine(logClassName, "Processing BNPL Scan - Upload Payload")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
            val timeStamp = sdf.format(Date())

            try {
                val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
                val ioupayScanQrHandler = IoupayScanQrHandler(environmentManager)

                val merchantInfo: DbModelMerchantConfig? = ServiceHolder.getMerchantInfo()
                if (merchantInfo == null) {
                    navigationToResultPage(context)
                }

                qrTxnDt = timeStamp
                qrRefId = SimpleDateFormat("yyMMddHHmmss", Locale.ENGLISH).format(
                    Calendar.getInstance().time
                )

                val resp = ioupayScanQrHandler.invoke(
                    log,
                    amt ?: "", //TXN_AMT
                    timeStamp, //QR_TXN_DT
                    payload, //QR_PAYLOAD
                    merchantInfo?.QrMid ?: "", //QR_MID
                    merchantInfo?.QrTid ?: "", //QR_ TID
                    qrRefId, //QR_REFID
                    paymentType ?: "", //PAYMENT_TYPE
                    packageCode ?: "", //PACKAGE_CODE
                    tenure, //TENUR//QR_TID
                )
                log.appendLine(logClassName, "IoupayScanQrHandler Response -> ", resp.toString())

                respCode = resp.RESP_CODE ?: ""

                if (respCode != "0000" && respCode != "5555") {
                    log.appendLine(logClassName, "REJECT :: BNPL host declined, respCode=$respCode desc=${resp.RESP_DESC ?: ""}")
                    return false
                }

                qrRespRefId = resp.QR_REFID ?: ""
                qrRespTxnRefNo = resp.QR_TXN_REFNO ?: ""
                qrRespHostRefNo = resp.QR_HOST_REFNO ?: ""
                qrRespAcqCode = resp.ACQ_CODE ?: ""
                acqCode = resp.ACQ_CODE ?: ""
                respDesc = resp.RESP_DESC ?: ""
                qrRespPayBrand = resp.QR_PAY_BRAND ?: ""
                qrRespPayBrandDesc = resp.QR_PAY_BRAND_DESC ?: ""
                acqMid = merchantInfo?.QrMid ?: ""
                acqTid = merchantInfo?.QrTid ?: ""

                return resp
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    val errorResponse = e.message?.split("Response: ")
                    if (!errorResponse?.get(1).isNullOrEmpty()) {
                        val modelData = Gson().fromJson(
                            errorResponse?.get(1),
                            IoupayScanQrResponseModel::class.java
                        )
                        respCode = modelData.RESP_CODE ?: "1100"
                        qrRespRefId = modelData.QR_REFID ?: ""
                        qrRespPayBrand = modelData.QR_PAY_BRAND ?: ""
                        qrRespTxnRefNo = modelData.QR_TXN_REFNO ?: ""
                        qrRespHostRefNo = modelData.QR_HOST_REFNO ?: ""
                    }
                } catch (ex: Exception) {
                    log.appendLine(logClassName, "Exception Get Error Resp: ", ex.toString())
                }

                log.appendLine(logClassName, "IoupayScanQrHandler (Exception) -> ", e.toString())
                log.logToFile(EnumLogFileName.TerminaLogException)
            }
            return false
        }
    }
}