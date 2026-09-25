package com.sc.mf919pro.kotlin.fragment
import enums.EnumResponseCode

import mdb.MdbController

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.zxing.WriterException
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentDuitnowqrBinding
import com.sc.mf919pro.databinding.FragmentDynamicqrBinding
import com.sc.mf919pro.java.activity.Utils
import emv.EmvUtil
import utils.Util
import com.sc.mf919pro.kotlin.data_enum.QrProductDataEnum
import com.sc.mf919pro.kotlin.data_enum.QrProductEnumModel
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelReceiptUpload
import com.sc.mf919pro.kotlin.database.model.DbModelTransactionQr
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import utils.GenerateQr
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import enums.EnumDateFormat
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import tms.handlers.GenerateQrHandler
import tms.handlers.QrCancelHandler
import tms.handlers.QrEnquiryHandler
import tms.models.GenerateQrResponseModel
import tms.models.QrEnquiryResponseModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.helper_common.AppBus
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.TTSManager
import com.sc.mf919pro.kotlin.helper_common.UiEvent
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys

class GenerateQrFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    var qrProductModel: QrProductEnumModel? = null
    lateinit var selectedSales: SaleModelNew
    lateinit var environmentManager: EnvironmentManager
    lateinit var timerTextView: TextView
    // Written from the AppBus abort collector and read by the
    // enquiry loop on another thread. Without @Volatile the loop can read a stale false and keep
    // polling after the session was aborted.
    @Volatile
    var isForceStop = false
    var stopTimer = false

    // Local mirrors of the TransData fields this flow needs at
    // persistence time. insertTransactionIntoDB() runs in an UNJOINED coroutine fired from
    // qrPayloadGenerate()'s finally block, so by the time it builds this QR's own permanent
    // ReceiptUpload/TransactionQr rows another flow may already have rewritten the singleton —
    // corrupting THIS transaction's audit record with a different transaction's values. Each
    // mirror is captured at the same moment its TransData counterpart is written.
    private var localTxnTypeLabel: String = ""
    private var localPosReference: String = ""
    private var localCorrelationRef: String = ""
    private var localAdditionalInfo: String = ""
    private var localIsUPIQR: Boolean = false
    private var localUpiVoucherCode: String = ""
    private var localUpiDiscountAmt: String = ""
    private var localUpiMarkupFee: String = ""

    // This flow's ownership token, minted by TransData.reset() in
    // onViewCreated. The QR enquiry loop can run for tens of seconds; if another transaction
    // takes over in that window, this flow must not write TransData or answer the ECR.
    private var txnSession = 0L

    /**
     * Abort-aware sleep. Re-checks the stop flag and the session between
     * short steps so an abort mid-delay is noticed in ~500 ms rather than after the full 5 s.
     *
     * Caveat carried over from MF919: this does not cancel an enquiry already in flight — that
     * call is blocking (32 s observed in the incident, 90 s timeout). It stops the *next* attempt.
     */
    private fun interruptibleDelayMili(totalMs: Long, stepMs: Long = 500) {
        var slept = 0L
        while (slept < totalMs) {
            if (isForceStop || !transData.isCurrentSession(txnSession)) return
            Util.DelayMili(minOf(stepMs, totalMs - slept).toInt())
            slept += stepMs
        }
    }

    //QR Information
    var barcodeQR: String = ""
    var payloadQR: String = ""
    var payloadImage: String = ""

    //Trsanction Data
    var qrSeqNo: String = "" // No Use
    var qrTxnDt: String = ""
    var qrRefId: String = ""
    var acqMid: String = ""
    var acqTid: String = ""
    var qrRespTxnRefNo: String = ""
    var qrRespHostRefNo: String = ""
    var qrRespApprovalCode: String = ""
    var respCode: String = "1100"
    var respDesc: String = ""
    var qrRespPayBrand: String = ""
    var qrRespPayBrandDesc: String = ""
    var isTpaAccount = false

    var amt: Int = 0
    var textAmt: String = "000000000000"
    private var orderingItem: String? = null
    private var orderingItemImage: String? = null

    var txnMap: HashMap<String, String> = hashMapOf()
    var jObject: JSONObject = JSONObject()

    private var _binding: ViewBinding? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        selectedSales = ServiceHolder.saleModelCache ?:
            SaleModelNew(0, 0, 0, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", null)
        val uiFragment = selectedSales.let {
            try {
                qrProductModel = QrProductDataEnum.valueOf(selectedSales.QrProductCode!!).data
                qrProductModel?.LayoutFragmentId ?: R.layout.fragment_dynamicqr
            } catch (e: Exception) {
                e.printStackTrace()
                R.layout.fragment_dynamicqr
            }
        }
        val view = inflater.inflate(uiFragment, container, false)

        _binding = when (uiFragment) {
            R.layout.fragment_duitnowqr -> FragmentDuitnowqrBinding.bind(view)
            else -> FragmentDynamicqrBinding.bind(view)
        }
        return view
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        isForceStop = true
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "GenerateQr OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
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
        view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbarCP).apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            }
        )
        val linearClose = view.findViewById<LinearLayout>(R.id.buttonCloseLinear)
        linearClose.background.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(Color.GRAY, BlendModeCompat.SRC_ATOP)
        linearClose.setOnClickListener {
            customOnBackPress()
        }

        qrProductModel?.qrHeaderImage?.let {
            try {
                val brandImageView = (view.findViewById<View>(R.id.qrBrandImage) as ImageView)
                brandImageView.visibility = View.VISIBLE
                brandImageView.setImageResource(it)
            } catch (e: java.lang.Exception) {
                helperLog.appendLine(helperLogClassName, "brandImageView (Exception) -> ", e.toString())
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
                e.printStackTrace()
            }
        }

        selectedSales.ProductName.let {
            val toolbarTextView: TextView = view.findViewById<View>(R.id.toolbarTV) as TextView
            toolbarTextView.text = selectedSales.ProductName
        }

        //TODO Revamp
        txnSession = transData.reset()
        transData.posReference = arguments?.getString("posReference") ?: "-"
        localPosReference = transData.posReference
        orderingItem = arguments?.getString("orderingItem")
        orderingItemImage = arguments?.getString("orderingItemImage")
        //TODO FOODLINK INTEGRATION
        transData.correlationRef = ServiceHolder.foodLinkCorrelationRef
        transData.additionalInfo = ServiceHolder.foodLinkAdditionalInfo
        localCorrelationRef = transData.correlationRef
        localAdditionalInfo = transData.additionalInfo
        ServiceHolder.clearFoodLinkCache()
        //TODO FOODLINK INTEGRATION
        helperLog.appendLine(helperLogClassName, "Add Pos Reference :: ", transData.posReference)
        transData.txnTypeLabel = "Sale"
        localTxnTypeLabel = transData.txnTypeLabel
        ServiceHolder.saleModelCache?.let {
            transData.salesType = it.SalesType
            textAmt = Utils.zeroPadding(it.TransAmount.toString(), 12)
            transData.amount = it.TransAmount
            transData.amountString = Utils.getActualAmount(it.TransAmount.toString())
        }
        (view.findViewById<View>(R.id.textView_amountDisplay) as TextView).text = transData.amountString
        transData.isUPIQR = arguments?.getBoolean("isUPIQR", false) == true
        localIsUPIQR = transData.isUPIQR
        timerTextView = view.findViewById(R.id.timerTV)
        isForceStop = false
        environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)


        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppBus.uiEvents.collect { event ->
                    when(event) {
                        is UiEvent.EndPaymentSession -> {
                            isForceStop = true
                            navigationToResultPage()
                        }
                        // The VMC can abort the vend while this QR is on screen (VEND CANCEL,
                        // RESET, reader disable). There is no polling loop here to notice it the
                        // way CardPaymentFragment does, so this screen is event-driven.
                        is UiEvent.MdbVendingForceEnd -> onMdbVendingForceEnd()
                        else -> { /* not required */ }
                    }
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            processGenerateQrResult()
        }
    }

    private suspend fun processGenerateQrResult() = withContext(Dispatchers.IO) {
        helperLog.appendLine(helperLogClassName, "Process Generate QR Result")
        val currDT = Date()
        qrTxnDt = SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmss.dateFormat, Locale.ENGLISH).format(currDT)
        qrRefId = SimpleDateFormat(EnumDateFormat.yyMMddHHmmss.dateFormat, Locale.ENGLISH).format(currDT)

        ServiceHolder.getMerchantInfo()?.let { merchantConfig ->
            showProgress("Downloading QR", "Loading...")
            transData.transDateAsci = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
            transData.qrRef = qrRefId
            transData.acqCode = selectedSales.AcqCode?: ""

            if(qrPayloadGenerate(merchantConfig)) {
                var successGenerate = false
                try{
                    if(barcodeQR.isNotEmpty()) {
                        successGenerate = true
                        CoroutineScope(Dispatchers.IO).launch {
                            requireActivity().runOnUiThread {
                                (requireView().findViewById<View>(R.id.barcodeImg) as ImageView).setImageBitmap(GenerateQr().createZxingBitmap(requireContext(), barcodeQR))
                                (requireView().findViewById<View>(R.id.barcodeImg) as ImageView).visibility = View.VISIBLE
                            }
                        }
                    }

                    if(payloadQR.isNotEmpty()) {
                        successGenerate = true
                        CoroutineScope(Dispatchers.IO).launch {
                            var scaledBitmap: Bitmap? = null
                            var qrCodeColor = R.color.black
                            qrProductModel?.let {
                                it.qrColor?.let { qrColor ->
                                    qrCodeColor = qrColor
                                }
                                it.qrOverlay?.let { _ ->
                                    val oriBitmap: Bitmap? = BitmapFactory.decodeResource(requireContext().resources, it.qrOverlay!!)
                                    oriBitmap?.let { _ ->
                                        scaledBitmap = oriBitmap.scale(Helper.getInstance().dpToPx(it.qrOverlaySizeWidth!!), Helper.getInstance().dpToPx(it.qrOverlaySizeHeight!!), false)
                                        /*scaledBitmap = Bitmap.createScaledBitmap(
                                            oriBitmap, Helper.getInstance().dpToPx(it.qrOverlaySizeWidth!!),
                                            Helper.getInstance().dpToPx(it.qrOverlaySizeHeight!!), false
                                        )*/
                                    }
                                }
                            }
                            println("payloadQR :: $payloadQR")
                            MfHelper.showAuxLcdQrCode(payloadQR, ContextCompat.getColor(requireContext(), qrCodeColor))
                            requireActivity().runOnUiThread {
                                (requireView().findViewById<View>(R.id.qrImg) as ImageView).setImageBitmap(
                                    GenerateQr().createZxingBitmap(requireContext(), payloadQR, "qrCode", scaledBitmap, ContextCompat.getColor(requireContext(), qrCodeColor))
                                )
                            }
                        }
                    }

                    if (payloadImage.isNotEmpty()) {
                        successGenerate = true
                        var scaledBitmap: Bitmap? = null
                        var qrCodeColor = R.color.black
                        qrProductModel?.let {
                            it.qrColor?.let { qrColor ->
                                qrCodeColor = qrColor
                            }
                            it.qrOverlay?.let { _ ->
                                val oriBitmap: Bitmap? = BitmapFactory.decodeResource(
                                    requireContext().resources, it.qrOverlay!!
                                )
                                oriBitmap?.let { _ ->
                                    scaledBitmap = oriBitmap.scale(Helper.getInstance().dpToPx(it.qrOverlaySizeWidth!!), Helper.getInstance().dpToPx(it.qrOverlaySizeHeight!!), false)
                                    /*scaledBitmap = Bitmap.createScaledBitmap(
                                        oriBitmap, Helper.getInstance().dpToPx(it.qrOverlaySizeWidth!!),
                                        Helper.getInstance().dpToPx(it.qrOverlaySizeHeight!!), false
                                    )*/
                                }
                            }
                        }
                        val qrImageByte = Base64.decode(payloadImage, Base64.DEFAULT)
                        val decodedQRImage = BitmapFactory.decodeByteArray(qrImageByte, 0, qrImageByte.size)

                        requireActivity().runOnUiThread {
                            (requireView().findViewById<View>(R.id.qrImg) as ImageView).setImageBitmap(decodedQRImage)
                        }
                    }

                    println("successGenerate :: $successGenerate")
                    if(successGenerate) {
                        TTSManager.speak("Please Scan the QR")
                    }

                    hideProgress()
                    startIncrementTimer()
                    transData.qrRespCode = "1100"
                    var enquiryLoop = 3
                    // The session check is new alongside the existing isForceStop check;
                    // a flow that has been superseded should stop enquiring even if nobody
                    // explicitly aborted it.
                    while(enquiryLoop > 0 && !isForceStop && transData.isCurrentSession(txnSession)) {
                        helperLog.appendLine(helperLogClassName, "QR Enquiry Loop Remaining -> ", enquiryLoop.toString())
                        enquiryLoop--

                        if(enquiryQrTransaction(merchantConfig)){
                            enquiryLoop = 0
                        } else {
                            interruptibleDelayMili(5 * 1000) //delay 5 second, abort-aware
                        }

                        if(isForceStop){
                            enquiryLoop = 0
                        }
                    }
                } catch (e: WriterException) {
                    e.printStackTrace()
                    helperLog.appendLine(helperLogClassName, "Exception in display QR/Barcode", e.toString())
                    Utils.DelayMili(1000)
                    hideProgress()
                }
            }else{
                Utils.DelayMili(1000)
                hideProgress()
            }
        }

        if(!isForceStop){
            helperLog.appendLine(helperLogClassName, "Generate Qr end Automatically")
            navigationToResultPage()
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }


    private fun qrPayloadGenerate(merchantInfo: DbModelMerchantConfig): Boolean{
        var result = false
        helperLog.appendLine(helperLogClassName, "Getting QR Payload")
        val generateQrHandler = GenerateQrHandler(environmentManager)

        try {
            val generateQrResp = generateQrHandler.invoke(
                helperLog,
                textAmt,
                qrTxnDt,
                merchantInfo.QrMid ?: "",
                merchantInfo.QrTid ?: "",
                qrRefId,
                selectedSales.QrProductCode ?: "",
                transData.posReference,
                orderingItem,
                orderingItemImage,
                transData.correlationRef,
                transData.additionalInfo
            )
            helperLog.appendLine(helperLogClassName, "GenerateQrHandler Response -> ", generateQrResp.toString())

            respCode = "1100"
            qrRespTxnRefNo = generateQrResp.QR_TXN_REFNO ?: ""
            qrRespHostRefNo = generateQrResp.QR_HOST_REFNO ?: ""
            qrRespPayBrand = generateQrResp.QR_PAY_BRAND ?: ""
            qrRespPayBrandDesc = generateQrResp.QR_PAY_BRAND_DESC ?: ""
            acqMid = generateQrResp.ACQ_MID ?: ""
            acqTid = generateQrResp.ACQ_TID ?: ""
            barcodeQR = generateQrResp.BARCODE_QR ?: ""
            payloadQR = generateQrResp.PAYLOAD_QR ?: ""
            payloadImage = generateQrResp.PAYLOAD_IMAGE ?: ""
            result = true
        } catch (ex: Exception) {
            ex.printStackTrace()
            helperLog.appendLine(helperLogClassName, "GenerateQrHandler (Exception) -> ", ex.toString())

            try {
                val errorResponse = Gson().fromJson(ex.message, GenerateQrResponseModel::class.java)
                respCode = errorResponse.RESP_CODE ?: "1100"
                respDesc = errorResponse.RESP_DESC ?: ""
                qrRespTxnRefNo = errorResponse.QR_TXN_REFNO ?: ""
                qrRespHostRefNo = errorResponse.QR_HOST_REFNO ?: ""
                qrRespPayBrand = errorResponse.QR_PAY_BRAND ?: qrRespPayBrand
                qrRespPayBrandDesc = errorResponse.QR_PAY_BRAND_DESC ?: qrRespPayBrandDesc
            } catch (jsonEx: Exception) {
                helperLog.appendLine(helperLogClassName, "Json Exception in Error -> ", jsonEx.toString())
            }
        } finally {
            CoroutineScope(Dispatchers.IO).launch {
                insertTransactionIntoDB()
            }
        }
        return result
    }

    private fun enquiryQrTransaction(merchantConfig: DbModelMerchantConfig): Boolean{
        var result = false
        helperLog.appendLine(helperLogClassName, "Enquiring Transaction Result")
        val qrEnquiryHandler = QrEnquiryHandler(environmentManager)

        try {
            val qrEnquiryResp = qrEnquiryHandler.invoke(
                helperLog,
                merchantConfig.QrMid ?: "",
                merchantConfig.QrTid ?: "",
                qrRefId
            )
            helperLog.appendLine(helperLogClassName, "QrEnquiryHandler Response -> ", qrEnquiryResp.toString())

            respCode = qrEnquiryResp.RESP_CODE ?: ""
            respDesc = ""
            qrRespHostRefNo = qrEnquiryResp.QR_HOST_REFNO ?: ""
            qrRespTxnRefNo = qrEnquiryResp.QR_TXN_REFNO ?: ""
            qrRespApprovalCode = qrEnquiryResp.BANK_AUTH_CODE ?: ""
            qrRespPayBrand = qrEnquiryResp.QR_PAY_BRAND ?: ""
            qrRespPayBrandDesc = qrEnquiryResp.QR_PAY_BRAND_DESC ?: ""
            isTpaAccount = qrEnquiryResp.IS_TPA_ACCOUNT ?: false

            transData.isUPIQR = qrEnquiryResp.IS_UNIONPAY_TXN ?: false
            transData.upiVoucherCode = qrEnquiryResp.UPI_VOUCHER_CODE ?: ""
            transData.upiDiscountAmt = qrEnquiryResp.UPI_DISCOUNT_AMOUNT ?: ""
            transData.upiMarkupFee = qrEnquiryResp.UPI_MARKUP_FEE ?: ""
            // Mirror the network response locally; the DB write that consumes these runs
            // detached and must not re-read the singleton.
            localIsUPIQR = transData.isUPIQR
            localUpiVoucherCode = transData.upiVoucherCode
            localUpiDiscountAmt = transData.upiDiscountAmt
            localUpiMarkupFee = transData.upiMarkupFee

            result = true
        }catch (ex: Exception){
            ex.printStackTrace()
            helperLog.appendLine(helperLogClassName, "QrEnquiryHandler (Exception) -> ", ex.toString())

            try {
                val errorResponse = Gson().fromJson(ex.message, QrEnquiryResponseModel::class.java)
                errorResponse.RESP_CODE?.let {
                    if(it != "0404"){
                        respCode = it
                        respDesc = errorResponse.RESP_DESC ?: ""
                        qrRespTxnRefNo = errorResponse.QR_TXN_REFNO ?: ""
                        qrRespHostRefNo = errorResponse.QR_HOST_REFNO ?: ""
                        qrRespApprovalCode = errorResponse.BANK_AUTH_CODE ?: ""
                        qrRespPayBrand = errorResponse.QR_PAY_BRAND ?: qrRespPayBrand
                        qrRespPayBrandDesc = errorResponse.QR_PAY_BRAND_DESC ?: qrRespPayBrandDesc
                        isTpaAccount = errorResponse.IS_TPA_ACCOUNT ?: false
                    }
                }
                errorResponse.RESP_DESC?.let {
                    if(errorResponse.RESP_CODE != "99999"){
                        result = true
                    }
                }
            } catch (jsonEx: Exception) {
                helperLog.appendLine(helperLogClassName, "Json Exception in Error -> ", jsonEx.toString())
            }
        }
        return result
    }

    private fun insertTransactionIntoDB() {
        // Every value below comes from a local mirror, never from TransData: this runs
        // detached from the caller, after the network round-trip.
        var isUnionPayTxn = "0"
        if (localIsUPIQR) {
            isUnionPayTxn = "1"
        }

        //TODO Receipt Upload
        val jsonObject = JsonObject()
        try {
            jsonObject.addProperty("TXN_DT", qrTxnDt)
            jsonObject.addProperty("TXN_TYPE", localTxnTypeLabel)
            jsonObject.addProperty("TXN_AMT", textAmt)
            jsonObject.addProperty("MID", acqMid)
            jsonObject.addProperty("TID", acqTid)
            jsonObject.addProperty("QrRefId", qrRefId)
            jsonObject.addProperty("QrType", qrRespPayBrand)
            jsonObject.addProperty("RRN", qrRespHostRefNo)
            jsonObject.addProperty("APPR_CODE", qrRespApprovalCode)
            jsonObject.addProperty("RESP_CODE", respCode)
            jsonObject.addProperty("APP_VER", ServiceHolder.getAppVersion())
            jsonObject.addProperty("SN", ServiceHolder.getTerminalSerialNumber())
            jsonObject.addProperty("POS_REF_NO", localPosReference)
            jsonObject.addProperty("ORDERING_ITEM", orderingItem ?: "")
            jsonObject.addProperty("ORDERING_ITEM_IMG", orderingItemImage ?: "")
            jsonObject.addProperty("CORRELATION_REF", localCorrelationRef)
            jsonObject.addProperty("ADDITIONAL_INFO", localAdditionalInfo)
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
        val transQrModel = DbModelTransactionQr(localTxnTypeLabel, qrTxnDt, "", textAmt, "GENERATE", selectedSales.Product ?: "-",
            qrRespPayBrand, qrRespPayBrandDesc, acqMid, acqTid, selectedSales.AcqCode ?: "-", qrRefId, qrRespHostRefNo, qrRespTxnRefNo, qrRespApprovalCode,
            respCode, respDesc, isUnionPayTxn, localUpiVoucherCode, localUpiDiscountAmt, localUpiMarkupFee, localPosReference, isTpaAccount.toString())
        TransactionQrRepo.insertToDb(requireContext(), transQrModel)
        //TODO Transaction QR
    }

    private fun updateTransactionResult() {
        val criteriaHM = hashMapOf<Any, Any>(
            "refId" to qrRefId
        )
        val valueHM = hashMapOf<Any, Any>(
            "respCode" to respCode,
            "respDesc" to respDesc,
            "hostRefNo" to qrRespHostRefNo,
            "txnRefNo" to qrRespTxnRefNo,
            "approvalCode" to qrRespApprovalCode,
            "productCode" to qrRespPayBrand,
            "productName" to qrRespPayBrandDesc,
            "isTpaAccount" to isTpaAccount.toString(),
        )
        TransactionQrRepo.updateTransactionQr(requireContext(), valueHM, criteriaHM)

        val receiptUploadCriteria = hashMapOf<Any, Any>(
            "QrRefId" to qrRefId
        )
        val receiptUploadValue = hashMapOf<Any, Any>(
            "RRN" to qrRespHostRefNo,
            "RESP_CODE" to respCode,
        )
        ReceiptUploadRepo.updateData(requireContext(), receiptUploadValue, receiptUploadCriteria)
    }

    private fun navigationToResultPage() {
        stopTimer = true

        // Another transaction took over while we sat in the enquiry loop. Persist this
        // QR's own record (it is keyed by qrRefId, so it is still correct and still owed), but do
        // NOT touch TransData and do NOT answer the ECR — answering here would reply under the
        // *other* transaction's PosReference, which is the second symptom of the 08-02 incident.
        if (!transData.isCurrentSession(txnSession)) {
            helperLog.appendLine(helperLogClassName, "STALE SESSION :: skipping TransData write and ECR reply", "mine=$txnSession current=${transData.sessionId}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            updateTransactionResult()
            return
        }

        // Fix E Stage A — these ten writes are one logical commit of this QR's outcome. Going
        // through ifCurrentSession means the ownership check cannot be forgotten by a later edit,
        // and the group runs under the same lock as reset(), so a reset cannot land halfway
        // through and leave a mix of two transactions' values.
        val committed = transData.ifCurrentSession(txnSession) {
            transData.mid = acqMid
            transData.tid = acqTid
            transData.qrRespCode = respCode
            transData.qrRespDesc = respDesc
            transData.qrTxnRef = qrRespTxnRefNo
            transData.qrHostRef = qrRespHostRefNo
            transData.qrPayBrand = qrRespPayBrand
            transData.qrPayBrandDesc = qrRespPayBrandDesc
            transData.qrApprovalCode = qrRespApprovalCode
            transData.isTpaAccount = isTpaAccount

            if (transData.upiVoucherCode != "") {
                val finalAmt = textAmt.toLong() - transData.upiDiscountAmt.toLong()
                transData.upiFinalAmount = Utils.getActualAmount(finalAmt.toString())
            }
        }
        if (!committed) {
            helperLog.appendLine(helperLogClassName, "Session moved on mid-commit :: TransData not written")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        updateTransactionResult()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            navigateSafe(R.id.action_generateQr_to_transactionResultQr)
        }
    }

    private fun startIncrementTimer() {
        object : Thread() {
            override fun run() {
                super.run()
                var incrementTimer = 1

                while (true) {
                    if (stopTimer) break
                    val minutes = (incrementTimer % 3600) / 60
                    val seconds  = incrementTimer % 60
                    val timerString = String.format("%02d:%02d", minutes, seconds)
                    requireActivity().runOnUiThread {
                        timerTextView.text = timerString
                    }
                    Utils.DelayMili(1000)
                    incrementTimer++
                }
            }
        }.start()
    }

    /**
     * VMC aborted the vend while this QR was displayed.
     *
     * The important case is the one this does NOT act on: if the payload has already been paid,
     * tearing it down would try to cancel a payment the acquirer has settled. Money has moved, so
     * the enquiry flow is allowed to finish and record the transaction, leaving it voidable.
     */
    private fun onMdbVendingForceEnd() {
        if (isForceStop) return

        if (respCode == "0000") {
            helperLog.appendLine(helperLogClassName,
                "Vend force-end IGNORED :: QR already approved (refId ${if (qrRefId.isEmpty()) "-" else qrRefId}), letting the result flow finish")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return
        }

        // Stops the enquiry loop within <=500ms (interruptibleDelayMili polls this) and suppresses
        // its trailing navigationToResultPage() so it cannot race the navigation below.
        isForceStop = true
        stopTimer = true
        // Clearing mdbVending stops navigationToResultPage()/customOnBackPress firing a second
        // VEND DENIED. Assigning false never re-broadcasts: the setter only emits on true.
        MdbController.mdbVending = false
        MdbController.mdbVendingForceEnd = false
        helperLog.appendLine(helperLogClassName,
            "Vend force-end :: VMC aborted the vend (refId ${if (qrRefId.isEmpty()) "-" else qrRefId}), cancelling payload -> home")
        helperLog.logToFile(EnumLogFileName.TerminaLog)

        object : Thread() {
            override fun run() {
                super.run()
                // An empty refId means qrPayloadGenerate() never returned - nothing exists at the
                // acquirer to cancel, so skip the host call and just leave.
                if (qrRefId.isNotEmpty()) {
                    cancelGenerateQrPayload()
                } else {
                    activity?.runOnUiThread {
                        val cfg = ServiceHolder.getTerminalConfig()
                        navigateToHome(cfg)
                    }
                }
            }
        }.start()
    }

    private fun customOnBackPress() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setMessage("Are you sure to Exit? \nThis transaction will be treat as fail transaction")
            .setCancelable(false)
            .setPositiveButton("Yes") { dialog, id ->
                // isVending -- see CardPaymentFragment.customOnBackPress.
                if (MdbController.isVending) {
                    helperLog.appendLine(helperLogClassName, "Vend DENIED :: QR payment abandoned, notifying VMC")
                    MdbController.sendVendDenied()
                }
                // Delete selected note from database
                object : Thread() {
                    override fun run() {
                        super.run()
                        cancelGenerateQrPayload()
                    }
                }.start()
            }
            .setNegativeButton("No") { dialog, id ->
                // Dismiss the dialog
                dialog.dismiss()
            }
        val alert = builder.create()
        alert.show()
    }

    fun cancelGenerateQrPayload() {
        helperLog.appendLine(helperLogClassName, "Cancel QR Payload")
        showProgress("", "Loading...")
        val qrCancelHandler = QrCancelHandler(environmentManager)

        try {
            val merchantConfig = ServiceHolder.getMerchantInfo()
            val qrCancelResp = qrCancelHandler.invoke(
                helperLog,
                merchantConfig?.QrMid ?: "",
                merchantConfig?.QrTid ?: "",
                transData.qrRef
            )
            helperLog.appendLine(helperLogClassName, "QrCancelHandler Response -> ", qrCancelResp.toString())

            val isCancelSuccess = qrCancelResp.IS_CANCEL_SUCCESS ?: false
            if(isCancelSuccess){
                //Success
                isForceStop = true
                respCode = "SHC005"
                respDesc = "User Cancel the Transaction"

                try {
                    txnMap = HashMap()
                    txnMap["ResponseCode"] = EnumResponseCode.USER_CANCELLED.code
                    txnMap["ResponseDescription"] = EnumResponseCode.USER_CANCELLED.description

                    jObject.put("ResponseCode", EnumResponseCode.USER_CANCELLED.code)
                    jObject.put("ResponseDescription", EnumResponseCode.USER_CANCELLED.description)
                } catch (e: JSONException) {
                    helperLog.appendLine(helperLogClassName, "Json Exception in Error -> ", e.toString())
                    helperLog.logToFile(EnumLogFileName.TerminaLogException)
                    e.printStackTrace()
                }

                if (ServiceHolder.appIntent) {
                    onBackToApp()
                } else {
                    navigationToResultPage()
                }
            }
        }catch (ex: Exception){
            ex.printStackTrace()
            helperLog.appendLine(helperLogClassName, "QrCancelHandler (Exception) -> ", ex.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
            showToast("Fail to Cancel QR", Toast.LENGTH_SHORT)
        }
        hideProgress()
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun onBackToApp() {
        // Handing control back to the calling POS app: this path ends in finish(), which
        // can be the last thing this process does -- onDestroyView is not guaranteed to
        // run to completion after it. Flush so the hand-back is always on disk.
        if (this@GenerateQrFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "GenerateQr :: returning to caller app, no response map")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        helperLog.appendLine(helperLogClassName, "Back to App")
        stopTimer = true
        startActivity(Intent(requireContext(), TransactionTransmitter::class.java).apply {
            putExtra(TxnKeys.EXTRA_TXN_MAP, txnMap)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        requireActivity().finish()
    }
}