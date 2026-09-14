package com.sc.mf919pro.kotlin.fragment
import enums.EnumResponseCode

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ViewfinderView
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentQrscanBinding
import com.sc.mf919pro.java.activity.Utils
import emv.EmvUtil
import utils.Util
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelReceiptUpload
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTransactionQr
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919pro.kotlin.helper_common.AppBus
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.TTSManager
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.UiEvent
import env.EnvironmentManager
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys
import enums.EnumDateFormat
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import tms.handlers.QrEnquiryHandler
import tms.handlers.ScanQrHandler
import tms.handlers.ScanQrUPIHandler
import tms.models.QrEnquiryResponseModel
import tms.models.ScanQrResponseModel
import tms.models.ScanQrUPIResponseModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanQrFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String
    val TAG = "QRSCAN"
    var timeoutSeconds: Int = 90

    //Scanner Result
    var qrPayload: String? = null
    var qrScannedCompleted = -1

    var amt: String = "0.00"
    var textAmt: String = "000000000000"
    var txn_map: HashMap<String, String> = hashMapOf()
    var jObject: JSONObject = JSONObject()
    var cameraFacing: Int = 1
    private var orderingItem: String? = null
    private var orderingItemImage: String? = null
    lateinit var environmentManager: EnvironmentManager

    //Button
    var isFlashOn = false
    lateinit var flashButton: Button
    var isBackCamera = true

    lateinit var barcodeView: DecoratedBarcodeView
    lateinit var viewFinderView: ViewfinderView

    private var _binding: FragmentQrscanBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQrscanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.simpleName.toString()
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Generate QR Fragment"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Generate QR Fragment")
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            }
        )

        txnSession = transData.reset()
        transData.txnTypeLabel = "Sale"
        ServiceHolder.saleModelCache?.let {
            transData.salesType = it.SalesType
            textAmt = Utils.zeroPadding(it.TransAmount.toString(), 12)
            transData.amount = it.TransAmount
            transData.amountString = Utils.getActualAmount(it.TransAmount.toString())
        }
        transData.isUPIQR = arguments?.getBoolean("isUPIQR", false) == true
        cameraFacing = arguments?.getInt("cameraFacing") ?: 1
        transData.posReference = arguments?.getString("posReference") ?: "-"
        orderingItem = arguments?.getString("orderingItem")
        orderingItemImage = arguments?.getString("orderingItemImage")
        //TODO FOODLINK INTEGRATION
        transData.correlationRef = ServiceHolder.foodLinkCorrelationRef
        transData.additionalInfo = ServiceHolder.foodLinkAdditionalInfo
        ServiceHolder.clearFoodLinkCache()
        //TODO FOODLINK INTEGRATION
        helperLog.appendLine(helperLogClassName, "Add Pos Reference :: ", transData.posReference)
        environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)

        barcodeView = binding.dbvCustom
        // Force start with front camera (1)
        val settings = barcodeView.barcodeView.cameraSettings
        settings.requestedCameraId = cameraFacing
        barcodeView.barcodeView.cameraSettings = settings
        isBackCamera = cameraFacing == 0

        flashButton = view.findViewById(R.id.toggleFlash)
        updateFlashButtonVisibility() // set correct state at start

        // Button Listener
        val buttonIds = listOf(
            R.id.backBtn,
            R.id.toggleCamera,
            R.id.toggleFlash
        )
        val listener = View.OnClickListener { v ->
            when (v.id) {
                R.id.backBtn -> requireActivity().onBackPressedDispatcher.onBackPressed()
                R.id.toggleCamera -> cameraFlip()
                R.id.toggleFlash -> toggleFlash()
            }
        }
        buttonIds.forEach { id ->
            view.findViewById<View>(id).setOnClickListener(listener)
        }

        setupScanner()

        viewLifecycleOwner.lifecycleScope.launch {
            TTSManager.speak("Please Scan the QR")
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppBus.uiEvents.collect { event ->
                    when(event) {
                        is UiEvent.EndPaymentSession -> {
                            // Latch first so any running enquiry loop stops retrying and
                            // stops writing, THEN run the existing teardown. Setting the flag
                            // alone is not enough (MF919 shipped that and the screen hung); the
                            // teardown below is what actually leaves the screen.
                            aborted = true
                            customOnBackPress()
                        }
                        else -> { /* not required */ }
                    }
                }
            }
        }
    }

    private fun setupScanner() {
        barcodeView.decodeContinuous { result ->
            result?.text?.let { qrText ->
                barcodeView.pause() // stop scanning after first result
                qrPayload = qrText

                // launch coroutine to process result
                lifecycleScope.launch {
                    processScanResult()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.dbvCustom.resume()
    }

    override fun onPause() {
        super.onPause()
        binding.dbvCustom.pause()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "ScanQr OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    private suspend fun processScanResult() = withContext(Dispatchers.IO) {
        val log = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            "QR Scan Activity",
            helperLogClassName,
            helperLogClassName
        )

        val currDT = Date()
        TransData.transDateAsci = SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmss.dateFormat, Locale.ENGLISH).format(currDT)
        TransData.qrRef = SimpleDateFormat(EnumDateFormat.yyMMddHHmmss.dateFormat, Locale.ENGLISH).format(currDT)

        val merchantInfo: DbModelMerchantConfig? = ServiceHolder.getMerchantInfo()
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val timeoutMs = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "TIMEOUT_SECONDS")

        if (timeoutMs.isNotEmpty()) {
            val timeout = Utils.atoi(timeoutMs)
            if (timeout > 0) {
                timeoutSeconds = timeout
            }
        }

        if(merchantInfo != null){
            loadingDialog()
            MfHelper.lockStatusBarAndNavigation(true)

            //Processing QR to TMS
            if (TransData.isUPIQR) {
                qrPayloadUPIUpload(log, merchantInfo)
            } else {
                qrPayloadUpload(log, merchantInfo)
            }

            /* 9011 - INVALID_UPIQR <-- no need do enquiry due to qr not valid */
            if(TransData.qrRespCode != "0000" && TransData.qrRespCode != "9011"){
                var enquiryLoop = 3
                // Abort and session are re-checked every iteration, not just at the end.
                while(enquiryLoop > 0 && !aborted && TransData.isCurrentSession(txnSession)){
                    enquiryLoop--
                    // Enquiry QR Result
                    if(qrPaymentEnquiry(log, merchantInfo)){
                        enquiryLoop = 0
                    } else {
                        interruptibleDelayMili(5 * 1000) //delay 5 second, abort-aware
                    }
                    log.appendLine(helperLogClassName, "Payment enquiry loop remaining -> ", enquiryLoop.toString())
                }
                if (aborted) {
                    log.appendLine(helperLogClassName, "Enquiry loop stopped :: session aborted")
                }
            }
            hideProgress()
            MfHelper.lockStatusBarAndNavigation(false)
        }
        log.logToFile(EnumLogFileName.TerminaLog)
        navigationToResultPage()
    }

    private fun loadingDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            var incrementTimer = 1
            showProgress("Processing QR Payment", "Loading... (${incrementTimer}s)")

            while (qrScannedCompleted != 0 && isActive) {
                updateProgress(msg = "Loading... (${++incrementTimer}s)")
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun qrPayloadUpload(log: HelperLog, merchantInfo: DbModelMerchantConfig){
        log.appendLine(helperLogClassName, "Upload Payload API Request")
        val scanQrHandler = ScanQrHandler(environmentManager)

        try {
            val scanQrResp = scanQrHandler.invoke(
                log,
                textAmt,
                TransData.transDateAsci,
                qrPayload!!,
                merchantInfo.QrMid!!,
                merchantInfo.QrTid!!,
                TransData.qrRef,
                TransData.posReference,
                orderingItem,
                orderingItemImage,
                TransData.correlationRef,
                TransData.additionalInfo,
                timeoutSeconds
            )
            log.appendLine(helperLogClassName, "ScanQrHandler Response -> ", scanQrHandler.toString())


            TransData.qrRespCode = scanQrResp.RESP_CODE ?: ""
            TransData.qrTxnRef = scanQrResp.QR_TXN_REFNO ?: ""
            TransData.qrHostRef = scanQrResp.QR_HOST_REFNO ?: ""
            TransData.qrPayBrand = scanQrResp.QR_PAY_BRAND ?: ""
            TransData.qrPayBrandDesc = scanQrResp.QR_PAY_BRAND_DESC ?: ""
            TransData.acqCode = scanQrResp.ACQ_CODE ?: ""
            TransData.mid = scanQrResp.ACQ_MID ?: ""
            TransData.tid = scanQrResp.ACQ_TID ?: ""

            TransData.isUPIQR = scanQrResp.IS_UNIONPAY_TXN ?: false
            TransData.upiVoucherCode = scanQrResp.UPI_VOUCHER_CODE ?: ""
            TransData.upiDiscountAmt = scanQrResp.UPI_DISCOUNT_AMOUNT ?: ""
            TransData.upiMarkupFee = scanQrResp.UPI_MARKUP_FEE ?: ""
            TransData.isTpaAccount = scanQrResp.IS_TPA_ACCOUNT ?: false
            captureQrSnapshot()   // Freeze this response for the DB write
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.appendLine(helperLogClassName, "ScanQrHandler (Exception) -> ", ex.toString())

            try {
                val errorResponse = Gson().fromJson(ex.message, ScanQrResponseModel::class.java)
                TransData.qrRespCode = errorResponse.RESP_CODE ?: "1100"
                TransData.qrRespDesc = errorResponse.RESP_DESC ?: ""
                TransData.qrPayBrand = errorResponse.QR_PAY_BRAND ?: ""
                TransData.qrTxnRef = errorResponse.QR_TXN_REFNO ?: ""
                TransData.qrHostRef = errorResponse.QR_HOST_REFNO ?: ""
                TransData.isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false
                captureQrSnapshot()   // Freeze this response for the DB write
            } catch (jsonEx: Exception) {
                log.appendLine(helperLogClassName, "Json Exception in Error -> ", jsonEx.toString())
            }
        }
    }

    private fun qrPayloadUPIUpload(log: HelperLog, merchantInfo: DbModelMerchantConfig){
        log.appendLine(helperLogClassName, "Upload Payload API Request")
        val scanQrUPIHandler = ScanQrUPIHandler(environmentManager)

        try {
            val scanQrUPIResp = scanQrUPIHandler.invoke(
                log,
                textAmt,
                TransData.transDateAsci,
                qrPayload!!,
                merchantInfo.QrMid!!,
                merchantInfo.QrTid!!,
                TransData.qrRef,
                TransData.posReference
            )
            log.appendLine(helperLogClassName, "scanQrUPIHandler Response -> ", scanQrUPIHandler.toString())

            TransData.qrRespCode = scanQrUPIResp.RESP_CODE ?: ""
            TransData.qrTxnRef = scanQrUPIResp.QR_TXN_REFNO ?: ""
            TransData.qrHostRef = scanQrUPIResp.QR_HOST_REFNO ?: ""
            TransData.qrPayBrand = scanQrUPIResp.QR_PAY_BRAND ?: ""
            TransData.qrPayBrandDesc = scanQrUPIResp.QR_PAY_BRAND_DESC ?: ""
            TransData.acqCode = scanQrUPIResp.ACQ_CODE ?: ""
            TransData.mid = scanQrUPIResp.ACQ_MID ?: ""
            TransData.tid = scanQrUPIResp.ACQ_TID ?: ""

            TransData.isUPIQR = scanQrUPIResp.IS_UNIONPAY_TXN ?: false
            TransData.upiVoucherCode = scanQrUPIResp.UPI_VOUCHER_CODE ?: ""
            TransData.upiDiscountAmt = scanQrUPIResp.UPI_DISCOUNT_AMOUNT ?: ""
            TransData.upiMarkupFee = scanQrUPIResp.UPI_MARKUP_FEE ?: ""
            TransData.isTpaAccount = scanQrUPIResp.IS_TPA_ACCOUNT ?: false
            captureQrSnapshot()   // Freeze this response for the DB write
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.appendLine(helperLogClassName, "scanQrUPIHandler (Exception) -> ", ex.toString())

            try {
                val errorResponse = Gson().fromJson(ex.message, ScanQrUPIResponseModel::class.java)
                TransData.qrRespCode = errorResponse.RESP_CODE ?: "1100"
                TransData.qrRespDesc = errorResponse.RESP_DESC ?: ""
                TransData.qrPayBrand = errorResponse.QR_PAY_BRAND ?: ""
                TransData.qrTxnRef = errorResponse.QR_TXN_REFNO ?: ""
                TransData.qrHostRef = errorResponse.QR_HOST_REFNO ?: ""
                TransData.isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false
                captureQrSnapshot()   // Freeze this response for the DB write
            } catch (jsonEx: Exception) {
                log.appendLine(helperLogClassName, "Json Exception in Error -> ", jsonEx.toString())
            }
        }
    }

    private fun qrPaymentEnquiry(log: HelperLog, merchantInfo: DbModelMerchantConfig): Boolean{
        var result = false
        log.appendLine(helperLogClassName, "Enquiring Transaction Result")
        val qrEnquiryHandler = QrEnquiryHandler(environmentManager)

        try {
            val qrEnquiryResp = qrEnquiryHandler.invoke(
                log,
                merchantInfo.QrMid ?: "",
                merchantInfo.QrTid ?: "",
                TransData.qrRef
            )
            log.appendLine(helperLogClassName, "QrEnquiryHandler Response -> ", qrEnquiryResp.toString())

            TransData.qrRespCode = qrEnquiryResp.RESP_CODE ?: ""
            //qrRespRefId = qrEnquiryResp.QR_REFID ?: ""
            TransData.qrHostRef = qrEnquiryResp.QR_HOST_REFNO ?: ""
            TransData.qrTxnRef = qrEnquiryResp.QR_TXN_REFNO ?: ""
            TransData.qrApprovalCode = qrEnquiryResp.BANK_AUTH_CODE ?: ""
            TransData.qrPayBrand = qrEnquiryResp.QR_PAY_BRAND ?: ""
            TransData.qrPayBrandDesc = qrEnquiryResp.QR_PAY_BRAND_DESC ?: ""

            TransData.isUPIQR = qrEnquiryResp.IS_UNIONPAY_TXN ?: false
            TransData.upiVoucherCode = qrEnquiryResp.UPI_VOUCHER_CODE ?: ""
            TransData.upiDiscountAmt = qrEnquiryResp.UPI_DISCOUNT_AMOUNT ?: ""
            TransData.upiMarkupFee = qrEnquiryResp.UPI_MARKUP_FEE ?: ""
            TransData.isTpaAccount = qrEnquiryResp.IS_TPA_ACCOUNT ?: false
            captureQrSnapshot()   // Freeze this response for the DB write
            result = true
        }catch (ex: Exception){
            ex.printStackTrace()
            log.appendLine(helperLogClassName, "QrEnquiryHandler (Exception) -> ", ex.toString())

            try {
                val errorResponse = Gson().fromJson(ex.message, QrEnquiryResponseModel::class.java)
                errorResponse.RESP_CODE?.let {
                    if(it != "0404"){
                        TransData.qrRespCode = it
                        TransData.qrRespDesc = errorResponse.RESP_DESC ?: ""
                        TransData.qrTxnRef = errorResponse.QR_TXN_REFNO ?: ""
                        TransData.qrHostRef = errorResponse.QR_HOST_REFNO ?: ""
                        TransData.qrApprovalCode = errorResponse.BANK_AUTH_CODE ?: ""
                        TransData.qrPayBrand = errorResponse.QR_PAY_BRAND ?: TransData.qrPayBrand
                        TransData.qrPayBrandDesc = errorResponse.QR_PAY_BRAND_DESC ?: TransData.qrPayBrandDesc
                        TransData.isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false
                        captureQrSnapshot()   // Freeze this response for the DB write
                    }
                }
                errorResponse.RESP_DESC?.let {
                    if(errorResponse.RESP_CODE != "99999"){
                        result = true
                    }
                }
            } catch (jsonEx: Exception) {
                log.appendLine(helperLogClassName, "Json Exception in Error -> ", jsonEx.toString())
            }
        }
        return result
    }

    // Snapshot of the QR fields this flow persists.
    //
    // insertTransactionIntoDB() builds THIS scan's permanent ReceiptUpload/TransactionQr rows by
    // reading TransData back, up to ~15s after the network response that set those fields (the
    // enquiry loop runs 3 x 5s). Any concurrent flow writing the singleton in that window would
    // put a different transaction's response code, RRN or POS reference into this transaction's
    // own audit record. Captured at each response handler instead, then read back from here.
    //
    // A single capture function rather than 18 individual mirrors: there are SIX response
    // handlers (scan, scan-UPI, enquiry, plus each one's error path) and hand-mirroring every
    // field at every site is how you miss one.
    private val qrSnap = HashMap<String, String>()

    // Ownership token, minted by TransData.reset() in onViewCreated.
    private var txnSession = 0L

    // This fragment had NO abort latch at all: it subscribed to the
    // abort event but only called customOnBackPress(), so an enquiry loop already running kept
    // retrying and kept writing. @Volatile because it is set from the AppBus collector and read
    // by the loop on another thread.
    @Volatile
    private var aborted = false

    /**
     * Sleeps in short steps, re-checking abort/session between each, so an abort arriving
     * mid-delay is noticed in ~500 ms instead of at the end of the full 5 s sleep.
     *
     * Caveat carried over from MF919: this does not cancel a request already in flight —
     * `QrEnquiryHandler.invoke(...)` is a blocking call (measured at 32 s in the incident, 90 s
     * timeout). Aborting stops the *next* attempt starting; it does not interrupt the current one.
     */
    private fun interruptibleDelayMili(totalMs: Long, stepMs: Long = 500) {
        var slept = 0L
        while (slept < totalMs) {
            if (aborted || !TransData.isCurrentSession(txnSession)) return
            Util.DelayMili(minOf(stepMs, totalMs - slept).toInt())
            slept += stepMs
        }
    }

    private fun captureQrSnapshot() {
        qrSnap["transDateAsci"] = TransData.transDateAsci.toString()
        qrSnap["txnTypeLabel"] = TransData.txnTypeLabel.toString()
        qrSnap["mid"] = TransData.mid.toString()
        qrSnap["tid"] = TransData.tid.toString()
        qrSnap["qrRef"] = TransData.qrRef.toString()
        qrSnap["qrPayBrand"] = TransData.qrPayBrand.toString()
        qrSnap["qrPayBrandDesc"] = TransData.qrPayBrandDesc.toString()
        qrSnap["qrHostRef"] = TransData.qrHostRef.toString()
        qrSnap["qrApprovalCode"] = TransData.qrApprovalCode.toString()
        qrSnap["qrRespCode"] = TransData.qrRespCode.toString()
        qrSnap["qrRespDesc"] = TransData.qrRespDesc.toString()
        qrSnap["posReference"] = TransData.posReference.toString()
        qrSnap["correlationRef"] = TransData.correlationRef
        qrSnap["additionalInfo"] = TransData.additionalInfo
        qrSnap["acqCode"] = TransData.acqCode.toString()
        qrSnap["isUPIQR"] = TransData.isUPIQR.toString()
        qrSnap["isTpaAccount"] = TransData.isTpaAccount.toString()
        qrSnap["upiVoucherCode"] = TransData.upiVoucherCode.toString()
        qrSnap["upiDiscountAmt"] = TransData.upiDiscountAmt.toString()
        qrSnap["upiMarkupFee"] = TransData.upiMarkupFee.toString()
        qrSnap["qrTxnRef"] = TransData.qrTxnRef.toString()
    }

    private fun snap(key: String): String = qrSnap[key] ?: ""

    private fun insertTransactionIntoDB() {
        var isUnionPayTxn = "0"
        if (snap("isUPIQR").toBoolean()) {
            isUnionPayTxn = "1"
        }

        //TODO Receipt Upload
        val jsonObject = JsonObject()
        try {
            jsonObject.addProperty("TXN_DT", snap("transDateAsci"))
            jsonObject.addProperty("TXN_TYPE", snap("txnTypeLabel"))
            jsonObject.addProperty("TXN_AMT", textAmt)
            jsonObject.addProperty("MID", snap("mid"))
            jsonObject.addProperty("TID", snap("tid"))
            jsonObject.addProperty("QrRefId", snap("qrRef"))
            jsonObject.addProperty("QrType", snap("qrPayBrand"))
            jsonObject.addProperty("RRN", snap("qrHostRef"))
            jsonObject.addProperty("APPR_CODE", snap("qrApprovalCode"))
            jsonObject.addProperty("RESP_CODE", snap("qrRespCode"))
            jsonObject.addProperty("APP_VER", ServiceHolder.getAppVersion())
            jsonObject.addProperty("SN", ServiceHolder.getTerminalSerialNumber())
            jsonObject.addProperty("POS_REF_NO", snap("posReference"))
            jsonObject.addProperty("ORDERING_ITEM", orderingItem ?: "")
            jsonObject.addProperty("ORDERING_ITEM_IMG", orderingItemImage ?: "")
            jsonObject.addProperty("CORRELATION_REF", snap("correlationRef"))
            jsonObject.addProperty("ADDITIONAL_INFO", snap("additionalInfo"))
            jsonObject.addProperty("CreationDate", SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmss.dateFormat, Locale.ENGLISH).format(Date()))
            jsonObject.addProperty("LastUpdateDt", "")
            jsonObject.addProperty("IsProcessing", "true")
            jsonObject.addProperty("IsSend", "true")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val dbModel = Gson().fromJson(jsonObject.toString(), DbModelReceiptUpload::class.java)
        ReceiptUploadRepo.insertToDbByModel(requireContext(), dbModel)
        //TODO Receipt Upload

        //TODO Transaction QR
        val transQrModel = DbModelTransactionQr(snap("txnTypeLabel"), snap("transDateAsci"), "", textAmt, "SCAN", snap("qrPayBrand"),
            snap("qrPayBrand"), snap("qrPayBrandDesc"), snap("mid"), snap("tid"), snap("acqCode"), snap("qrRef"), snap("qrHostRef"), snap("qrTxnRef"), snap("qrApprovalCode"),
            snap("qrRespCode"), snap("qrRespDesc"), isUnionPayTxn, snap("upiVoucherCode"), snap("upiDiscountAmt"), snap("upiMarkupFee"), snap("posReference"), snap("isTpaAccount"))
        TransactionQrRepo.insertToDb(requireContext(), transQrModel)
        //TODO Transaction QR
    }

    private fun navigationToResultPage() {
        // The DB write comes first and is deliberately NOT gated: it is built entirely from the
        // snapshot (DT2) and keyed by this scan's own qrRefId, so it stays correct — and still
        // owed — even when the session has moved on.
        insertTransactionIntoDB()

        // A newer transaction owns TransData now. Do not write it, and do not navigate to
        // the result screen, which is what answers the ECR; replying here would answer under the
        // other transaction's PosReference.
        if (!TransData.isCurrentSession(txnSession)) {
            helperLog.appendLine(helperLogClassName, "STALE SESSION :: skipping TransData write and ECR reply",
                "mine=$txnSession current=${TransData.sessionId}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return
        }

        if (TransData.upiDiscountAmt.isNotEmpty()) {
            TransData.upiFinalAmount = (TransData.amount - TransData.upiDiscountAmt.toLong()).toString()
        }
        TransData.transDateAsci = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
          navigateSafe(R.id.action_scanQr_to_transactionResultQr)
        }
    }

    fun customOnBackPress() {
        try {
            txn_map = HashMap()
            txn_map["ResponseCode"] = EnumResponseCode.USER_CANCELLED.code
            txn_map["ResponseDescription"] = EnumResponseCode.USER_CANCELLED.description

            jObject.put("ResponseCode", EnumResponseCode.USER_CANCELLED.code)
            jObject.put("ResponseDescription", EnumResponseCode.USER_CANCELLED.description)
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        if(ServiceHolder.appIntent) {
            onBackToApp(txn_map)
        } else{
            if (ServiceHolder.appHTTP) {
                HTTPServer.getInstance().setResponseMessage(jObject.toString())
                ServiceHolder.appHTTP = false
            }

            val dbModelTerminalConfig = getTerminalConfig()
            navigateToHome(dbModelTerminalConfig)
        }
    }


    private fun cameraFlip() {
        // Initialize flag (0 = back, 1 = front)
        isBackCamera = barcodeView.cameraSettings.requestedCameraId == 0

        barcodeView.pauseAndWait()
        val settings = barcodeView.barcodeView.cameraSettings
        if (isBackCamera) {
            // switch to front
            settings.requestedCameraId = 1
            isBackCamera = false
        } else {
            // switch to back
            settings.requestedCameraId = 0
            isBackCamera = true
        }
        barcodeView.barcodeView.cameraSettings = settings
        barcodeView.resume()

    }

    private fun updateFlashButtonVisibility() {
        val hasFlash = requireContext().packageManager
            .hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

        // Show flash only if device supports it AND current cam is BACK
        if (hasFlash && isBackCamera) {
            flashButton.visibility = View.VISIBLE
        } else {
            flashButton.visibility = View.GONE
        }
    }

    private fun toggleFlash() {
        if (!isFlashOn) {
            isFlashOn = true
            barcodeView.setTorchOn()
            flashButton.setBackgroundResource(R.drawable.flash_off)
        } else {
            isFlashOn = false
            barcodeView.setTorchOff()
            flashButton.setBackgroundResource(R.drawable.flash_on)
        }
    }

    private fun onBackToApp(txn_map: HashMap<String, String>) {
        // Handing control back to the calling POS app: this path ends in finish(), which
        // can be the last thing this process does -- onDestroyView is not guaranteed to
        // run to completion after it. Flush so the hand-back is always on disk.
        if (this@ScanQrFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "ScanQr :: returning to caller app, response -> ${txn_map["ResponseCode"]}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        startActivity(Intent(requireContext(), TransactionTransmitter::class.java).apply {
            putExtra(TxnKeys.EXTRA_TXN_MAP, txn_map)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        requireActivity().finish()
    }
}