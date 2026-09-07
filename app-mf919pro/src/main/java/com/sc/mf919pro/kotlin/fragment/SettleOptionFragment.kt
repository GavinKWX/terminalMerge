package com.sc.mf919pro.kotlin.fragment

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.library.terminal.Utility
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentSettleoptionBinding
import com.sc.mf919pro.java.activity.Global
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.activity.Utils.TextItem
import com.sc.mf919pro.kotlin.activity.AppServices.Companion.LAST_SETTLE_TAG
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919pro.kotlin.data_enum.AcquirerSettingModel
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.database.model.DbModelLastSettlement
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.model.DbModelQrPayBrandGet
import com.sc.mf919pro.kotlin.database.model.DbModelSettlementSummary
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.IsoBatchLongInfoRepo
import com.sc.mf919pro.kotlin.database.repo.LastSettlementRepo
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.database.repo.ReversalBatchTableRepo
import com.sc.mf919pro.kotlin.database.repo.SettlementSummaryRepo
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919pro.kotlin.helper_common.CoroutineTask
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getInternalFilesPaths
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919pro.kotlin.helper_common.iso.IsoHelperNew
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tms.models.AcquirerLogoReplaceObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class SettleOptionFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String
    lateinit var selectAllCheckBox: CheckBox
    lateinit var settleOptionlist: LinearLayout
    lateinit var btnBack: LinearLayout
    lateinit var btnOK: LinearLayout
    var runningBlock = 0

    lateinit var settleInfo: MutableList<Pair<String, String>>
    lateinit var acqLogoEnumModel: AcquirerLogoEnumModel
    lateinit var acquirerSettingModel: AcquirerSettingModel

    //private var previewSettlement = 0
    //private var isLastSettlement = false
    private var loadingLoop = false

    private var txnDt: String = ""
    private var batchNo: String = ""

    private var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
    private var httpSettleType: String? = null
    private var totalSettlementCount = 0
    private var currentProductIndex = 0
    private var settlementProduct: List<DbModelProductList> = arrayListOf()
    private var settlementSummaryData: List<DbModelSettlementSummary> = arrayListOf()
    private var dbModelMerchantConfig: DbModelMerchantConfig? = null
    private var dbModelTerminalConfig: DbModelTerminalConfig? = null
    private var settledProduct = HashMap<DbModelProductList, List<DbModelSettlementSummary>>()
    var isTpaAccount = false

    //---QR-----
    private var id = 0
    private var settleInfoQr = arrayOf<String?>()
    lateinit var printInfoQr: MutableList<Pair<String, String>>
    private var qrPayBrand = arrayOf<String?>()
    lateinit var qrPayBrandV2: List<DbModelQrPayBrandGet>
    private var qrPayTotalTxnAmt = arrayOf<String?>()
    private var qrPayTotalTxnCount = arrayOf<String?>()
    private var qrPayTotalVoidTxnAmt = arrayOf<String?>()
    private var qrPayTotalVoidTxnCount = arrayOf<String?>()

    private var SaleA: Long = 0
    private var SaleC: Long = 0
    private var VoidA: Long = 0
    private var VoidC: Long = 0

    //TODO HTTP/INTENT
    lateinit var jObject: JsonObject
    lateinit var settleJsonArray: JsonArray
    lateinit var txnMap: HashMap<String, Any>
    lateinit var txnMapList: ArrayList<HashMap<String, Any>>

    private var _binding: FragmentSettleoptionBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettleoptionBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "SettleOption OnDestroyView :: screen ended")
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
            "Settle Option Fragment"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Settle Option Fragment")
        val toolbar = view.findViewById<Toolbar>(R.id.toolbarSettleOpt)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )

        binding.settlementPreviewBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> SettlementPreview")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            navigateSafe(R.id.action_settleOption_to_settlePreview, androidx.core.os.bundleOf("previewSettlement" to 1))
        }
        selectAllCheckBox = binding.selectAllChkbx
        btnOK = binding.buttonSettle
        btnBack = binding.buttonBack
        runningBlock = 0

        acqLogoEnumModel = AcquirerLogoEnumModel(R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, resources.getString(R.string.app_name_about), resources.getString(R.string.app_name_about),)

        settleInfo = mutableListOf()
        /*previewSettlement = arguments?.getInt("previewSettlement")?: 0
        isLastSettlement = (arguments?.getInt("Last", 0) != 0)*/
        httpSettleType = arguments?.getString("settlementType") ?: ""
        acquirerSettingModel = ServiceHolder.getAcquirerSetting()

        //TODO HashMap
        settleJsonArray = JsonArray()
        jObject = JsonObject()
        jObject.addProperty("ResponseCode", "00")
        jObject.addProperty("ResponseDescription", "Settlement")
        jObject.addProperty("TransactionType", ServiceHolder.txnType.toString())
        jObject.addProperty("SettlementType", httpSettleType)
        txnMap = hashMapOf()
        txnMapList = arrayListOf()

        settleOptionlist = binding.settleOptionlist
        qrPayBrandV2 = TransactionQrRepo.getDistinctProduct(requireContext())

        val terminalConfig = ServiceHolder.getTerminalConfig()
        if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "SETTLEMENT_WITH_PIN")) {
            helperLog.appendLine(helperLogClassName, "SETTLEMENT_WITH_PIN is true")
            pinDialog("") { canceled ->
                if (canceled) {
                    customOnBackPress()
                } else {
                    lifecycleScope.launch {
                        renderDynamicProduct()
                    }
                }
            }
        } else {
            lifecycleScope.launch {
                renderDynamicProduct()
            }
        }
    }

    private suspend fun renderDynamicProduct() {
        val acqName = ServiceHolder.getAcquirerSetting().acqName
        helperLog.appendLine(helperLogClassName, "Rendering Settlement Product")
        val merchantProduct = ServiceHolder.getMerchantProduct()
        withContext(Dispatchers.Main) {
            val loadingTitle = "Checking Settlement"
            showProgress(loadingTitle, "Loading...")

            try {
                var isQRShow = false
                for (a in merchantProduct!!) {
                    val (_, productTitle, _, active, _, _, _, _, _, _) = ProductCatSelectionDataEnum.valueOf(a).data
                    if (!active || a == "BNPL" || a == "EPP" || (a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") && isQRShow) {
                        // Category not yet configure and active on terminal
                        continue
                    }

                    val displaySettleItem = if ((a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS")){
                        checkRecordQr()
                    } else if (acqName.equals("BSN", ignoreCase = true)){
                        checkRecordAsccend()
                    } else {
                        checkRecord()
                    }

                    if (displaySettleItem) {
                        val settleMethod = LinearLayout(requireContext())
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, Helper.getInstance().getDpValue(80)
                        )
                        params.setMargins(20, 0, 20, 20)
                        params.gravity = Gravity.CENTER
                        settleMethod.gravity = Gravity.CENTER_VERTICAL
                        settleMethod.layoutParams = params
                        settleMethod.orientation = LinearLayout.HORIZONTAL
                        settleMethod.setBackgroundResource(R.drawable.border3)

                        val textViewLeft = TextView(requireContext())
                        val leftParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        textViewLeft.layoutParams = leftParams
                        textViewLeft.setPadding(10, 0, 0, 0)
                        textViewLeft.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mf919_bg_theme_color))

                        // Create CheckedTextView
                        val checkedTextView = CheckedTextView(requireContext())
                        val textParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        textParams.weight = 1.0f
                        checkedTextView.layoutParams = textParams
                        checkedTextView.setPadding(30, 0, 0, 0)
                        checkedTextView.textSize = 20f
                        checkedTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.WordColor))
                        checkedTextView.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        checkedTextView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mf919_dark_gray))

                        // Create CheckBox
                        val checkBox = CheckBox(requireContext())
                        val checkParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        checkParams.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        checkBox.layoutParams = checkParams
                        checkBox.setPadding(0, 0, 0, 0)
                        checkBox.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mf919_dark_gray))

                        // GENERATE_QR & EWALLET
                        if (a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") {
                            isQRShow = true
                            settleMethod.addView(textViewLeft)
                            checkedTextView.text = "QR PAYMENT"
                            settleMethod.addView(checkedTextView)
                            settleMethod.addView(checkBox)
                        } else {
                            settleMethod.addView(textViewLeft)
                            checkedTextView.text = productTitle
                            settleMethod.addView(checkedTextView)
                            settleMethod.addView(checkBox)
                        }

                        settleMethod.setOnClickListener {
                            checkBox.isChecked = !checkBox.isChecked
                            if (this@SettleOptionFragment::helperLog.isInitialized) {
                                helperLog.appendLine(helperLogClassName,
                                    "Selected :: $productTitle -> ${if (checkBox.isChecked) "included" else "excluded"} [SETTLEMENT]")
                            }
                            updateSelectAllCheckBox()
                        }
                        checkBox.setOnClickListener {
                            if (this@SettleOptionFragment::helperLog.isInitialized) {
                                helperLog.appendLine(helperLogClassName,
                                    "Selected :: $productTitle -> ${if (checkBox.isChecked) "included" else "excluded"} [SETTLEMENT]")
                            }
                            updateSelectAllCheckBox()
                        }
                        settleOptionlist.addView(settleMethod)
                    } else {
                        println("$a SKIP DISPLAY!!!")
                    }
                }
            } catch (e: Exception) {
                helperLog.appendLine(helperLogClassName, "Exception in Rendering Settlement Product -> ", e.toString())
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
                e.printStackTrace()
            }

            updateBtnCheckboxVisibility()
            hideProgress()

            if(!httpSettleType.isNullOrEmpty()){
                httpSettleType = httpSettleType!!.trim().uppercase()

                for (i in 0 until settleOptionlist.childCount) {
                    val child = settleOptionlist.getChildAt(i)
                    if (child is LinearLayout) {
                        val checkBox = child.getChildAt(2) as CheckBox
                        val productTitle = child.getChildAt(1) as CheckedTextView
                        if (child.visibility != View.GONE) {
                            when (productTitle.text.toString()) {
                                "QR PAYMENT" -> {
                                    if (listOf("QR").contains(httpSettleType)) {
                                        checkBox.isChecked = true
                                    }
                                }
                                else -> {
                                    if (listOf("CARD").contains(httpSettleType)) {
                                        checkBox.isChecked = true
                                    }
                                }
                            }
                        }

                        if(httpSettleType == "ALL") {
                            selectAllCheckBox.isChecked = true
                            val isChecked = true
                            for (httpCheck in 0 until settleOptionlist.childCount) {
                                val childLinear = settleOptionlist.getChildAt(httpCheck)
                                if (childLinear is LinearLayout) {
                                    val childCheck = childLinear.getChildAt(2) as CheckBox
                                    childCheck.isChecked = isChecked
                                }
                            }
                        }
                    }
                }
                performSettleAction(true)
            }
        }
    }

    suspend fun checkRecord(): Boolean {
        helperLog.appendLine(helperLogClassName, "Checking Transaction Record")
        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()

        if (dbModelMerchantConfig != null) {
            try {
                acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(dbModelMerchantConfig?.AcqCode ?: "").data
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        settlementProduct = ProductListRepo.getUnSettledProduct(requireContext())
        if(settlementProduct.isNotEmpty()){
            currentProductIndex = 0
            val currentProduct = settlementProduct[0]
            settlementSummaryData = SettlementSummaryRepo.getSelectiveData(
                requireContext(),
                listOf("acq_code", "mid", "tid"),
                arrayOf(currentProduct.AcqCode, currentProduct.AcqMid, currentProduct.AcqTid)
            )
            settleInfo = withContext(Dispatchers.Default) {
                constructPrintRecord(currentProduct, settlementSummaryData, JsonArray())
            }

            var totalTxnCount = 0
            var productTxnCount = 0

            val mapIterator = settleInfo.iterator()
            while(mapIterator.hasNext()){
                val (key, keyValue) = mapIterator.next()
                if(key == "SCHEME"){
                    totalTxnCount += productTxnCount
                    productTxnCount = 0
                }

                if (key.contains("COUNT")){
                    productTxnCount += Utils.atoi(keyValue.trim())
                }

                if(!mapIterator.hasNext()){
                    totalTxnCount += productTxnCount
                    productTxnCount = 0
                }
            }
            if(totalTxnCount <= 0){
                return false
            }
        }
        return true
    }

    suspend fun checkRecordAsccend(): Boolean {
        helperLog.appendLine(helperLogClassName, "Checking Asccend Transaction Record")
        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()

        if (dbModelMerchantConfig != null) {
            try {
                acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(dbModelMerchantConfig!!.AcqCode!!).data
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        settlementProduct = ProductListRepo.getUnSettledProduct(requireContext())
        if(settlementProduct.isNotEmpty()){
            var totalTxnCount = 0
            for (currentProductIndex in settlementProduct.indices){
                val currentProduct = settlementProduct[currentProductIndex]
                settlementSummaryData = SettlementSummaryRepo.getSelectiveData(
                    requireContext(),
                    listOf("acq_code", "mid", "tid"),
                    arrayOf(currentProduct.AcqCode, currentProduct.AcqMid, currentProduct.AcqTid)
                )
                settleInfo = withContext(Dispatchers.Default) {
                    constructPrintRecord(currentProduct, settlementSummaryData, JsonArray())
                }

                var productTxnCount = 0

                val mapIterator = settleInfo.iterator()
                while(mapIterator.hasNext()){
                    val (key, keyValue) = mapIterator.next()
                    if(key == "SCHEME"){
                        totalTxnCount += productTxnCount
                        productTxnCount = 0
                    }

                    if (key.contains("COUNT")){
                        productTxnCount += Utils.atoi(keyValue.trim())
                    }

                    if(!mapIterator.hasNext()){
                        totalTxnCount += productTxnCount
                        productTxnCount = 0
                    }
                }
            }
            if(totalTxnCount <= 0){
                return false
            }
        }
        return true
    }

    private inner class Trans(private var child: View) : CoroutineTask<String?, Boolean>() {
        override fun onPreExecute() {
            super.onPreExecute()
            helperLog.appendLine(helperLogClassName, "Processing Settlement")
        }

        override fun doInBackground(vararg params: String?): Boolean {
            var result = true
            getRecordCoroutine(helperLog)
            showProgress("Settlement", "Preparing...")
            val sharedPreferences: SharedPreferences = Helper.getInstance().getPrefs(requireContext())
            val timeString = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date())
            sharedPreferences.edit { putString(LAST_SETTLE_TAG, timeString) }

            if(settlementProduct.isEmpty()){
                SettlementSummaryRepo.updateData(requireContext(), hashMapOf("is_settle" to "false"), hashMapOf())
                showToast("Abnormal Detected! Fail To Settle. \nPlease Run Settlement Again...", Toast.LENGTH_SHORT)
            }

            for(forIndex in settlementProduct.indices){
                val tempObj = settlementProduct[forIndex]
                var midDisplay = tempObj.AcqMid
                var tidDisplay = tempObj.AcqTid
                if(isTpaAccount) {
                    midDisplay = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
                    tidDisplay = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")
                }

                val cardJson = JsonObject()
                val productArray = JsonArray()
                val timeStamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH).format(Calendar.getInstance().time)
                cardJson.addProperty("TransactionDateTime", timeStamp)
                //cardJson.addProperty("TransactionMID", tempObj.AcqMid)
                //cardJson.addProperty("TransactionTID", tempObj.AcqTid)
                cardJson.addProperty("TransactionMID", midDisplay)
                cardJson.addProperty("TransactionTID", tidDisplay)
                cardJson.addProperty("TransactionBatchNo", batchNo)

                if (ServiceHolder.appIntent){
                    txnMap = HashMap<String, Any>()
                    txnMap["SettlementDateTime"] = timeStamp
                    //txnMap["SettlementMID"] = tempObj.AcqMid
                    //txnMap["SettlementTID"] = tempObj.AcqTid
                    txnMap["SettlementMID"] = midDisplay
                    txnMap["SettlementTID"] = tidDisplay
                    txnMap["SettlementBatchNo"] = batchNo
                }

                updateProgress(msg = "Checking Reversal..."
)
                val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(tempObj.AcqCode, "reversal") ?: continue
                var reversalResult = true
                val reversalBatch = ReversalBatchTableRepo.getBatchData(requireContext(), listOf("batchNo", "mid", "tid"), arrayOf(batchNo, tempObj.AcqMid, tempObj.AcqTid))
                if(reversalBatch.isNotEmpty()) {
                    for (tempFor in reversalBatch) {
                        transData.reset()

                        val productModel = ProductListRepo.getSingle(requireContext(), listOf("AcqCode", "AcqMid", "AcqTid"), arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid))
                        transData.acqCode = tempObj.AcqCode
                        transData.product = tempObj.Product
                        transData.mid = tempObj.AcqMid
                        transData.tid = tempObj.AcqTid
                        transData.schemeTag = "visam"
                        transData.txnTypeLabel = "Reversal"
                        transData.ksn = productModel?.Ksn ?: ""
                        transData.pinKsn = productModel?.PinKsn ?: ""
                        transData.stan = tempFor.stan
                        transData.invoiceNo = tempFor.invNo
                        transData.batchNo = tempFor.batchNo
                        transData.isTpaAccount = tempObj.IsTpaAccount.lowercase() == "true"
                        transData.tpaMid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
                        transData.tpaTid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")

                        val revResult = IsoActivity.processReversal(requireContext(), false, acquirerRevIsoModel, tempFor.batchData, true, helperLog)
                        if(reversalResult && revResult == null){
                            reversalResult = false
                        }
                    }
                }

                //TODO Reversal Fail Abort Settlement
                if(!reversalResult) {
                    showToast("Settlement Abort, Reversal Could Not Complete...", Toast.LENGTH_LONG)
                    continue
                }

                helperLog.appendLine(helperLogClassName, "Reset Iso Batch Long last postingDt")
                IsoBatchLongInfoRepo.updateBatchLongInfo(requireContext(), "", "postingDt", "last")

                updateProgress(msg = "Uploading... Please Wait"
)

                val recordSummary = SettlementSummaryRepo.getSelectiveData(
                    requireContext(),
                    listOf("acq_code", "mid", "tid"),
                    arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid)
                )

                var txnCount = ""
                var txnTotal = ""
                var voidTxnCount = ""
                var voidTxnTotal = ""

                val summaryRecordTxnTotal = SettlementSummaryRepo.getSelectiveData(requireContext(),
                    ArrayList(listOf("acq_code", "mid", "tid", "tag")),
                    arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "txnTotal")
                )
                if (summaryRecordTxnTotal.isNotEmpty()) {
                    val tempTxnTotal = AtomicInteger()
                    summaryRecordTxnTotal.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
                        tempTxnTotal.addAndGet(Utils.atoi(value))
                    })
                    txnTotal = tempTxnTotal.toString()
                    if (this@SettleOptionFragment::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "\ttxnTotal = $txnTotal")
                }

                val summaryRecordTxnCount = SettlementSummaryRepo.getSelectiveData(
                    requireContext(), ArrayList(listOf("acq_code", "mid", "tid", "tag")),
                    arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "txnCount")
                )
                if (summaryRecordTxnCount.isNotEmpty()) {
                    val tempTxnCount = AtomicInteger()
                    summaryRecordTxnCount.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
                        tempTxnCount.addAndGet(Utils.atoi(value))
                    })
                    txnCount = tempTxnCount.toString()
                    if (this@SettleOptionFragment::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "\ttxnCount = $txnCount")
                }

                val summaryRecordVoidTxnTotal = SettlementSummaryRepo.getSelectiveData(requireContext(),
                    ArrayList(listOf("acq_code", "mid", "tid", "tag")),
                    arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "voidTxnTotal")
                )
                if (summaryRecordVoidTxnTotal.isNotEmpty()) {
                    val tempVoidTxnTotal = AtomicInteger()
                    summaryRecordVoidTxnTotal.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
                        tempVoidTxnTotal.addAndGet(Utils.atoi(value))
                    })
                    voidTxnTotal = tempVoidTxnTotal.toString()
                    if (this@SettleOptionFragment::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "\tvoidTxnTotal = $voidTxnTotal")
                }

                val summaryRecordVoidTxnCount = SettlementSummaryRepo.getSelectiveData(
                    requireContext(), ArrayList(listOf("acq_code", "mid", "tid", "tag")),
                    arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid, "voidTxnCount")
                )
                if (summaryRecordVoidTxnCount.isNotEmpty()) {
                    val tempVoidTxnCount = AtomicInteger()
                    summaryRecordVoidTxnCount.forEach(Consumer { (_, _, _, _, _, value): DbModelSettlementSummary ->
                        tempVoidTxnCount.addAndGet(Utils.atoi(value))
                    })
                    voidTxnCount = tempVoidTxnCount.toString()
                    if (this@SettleOptionFragment::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "\tvoidTxnCount = $voidTxnCount")
                }

                val settlementValueString = IsoActivity.constructSettlementValueString(txnCount, txnTotal, "")
                val productModel = ProductListRepo.getSingle(requireContext(), listOf("AcqCode", "AcqMid", "AcqTid"), arrayOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid))
                transData.reset()
                transData.acqCode = tempObj.AcqCode
                transData.product = tempObj.Product
                transData.mid = tempObj.AcqMid
                transData.tid = tempObj.AcqTid
                transData.schemeTag = "visam"
                transData.txnTypeLabel = "Settle"
                transData.ksn = productModel?.Ksn ?: ""
                transData.pinKsn = productModel?.PinKsn ?: ""
                transData.isTpaAccount = tempObj.IsTpaAccount.lowercase() == "true"
                transData.tpaMid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
                transData.tpaTid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")


                loadingLoop = true
                object : Thread() {
                    override fun run() {
                        super.run()
                        while(loadingLoop) {
                            if(transData.loadingTitle.isNotEmpty()){
                                updateProgress(title = transData.loadingTitle)
                                transData.loadingTitle = ""
                            }

                            if(transData.loadingMessage.isNotEmpty()){
                                updateProgress(msg = transData.loadingMessage)
                                transData.loadingMessage = ""
                            }
                            sleep(1000)
                        }
                    }
                }.start()
                IsoActivity.processSettlement(requireContext(), tempObj, settlementValueString, helperLog)
                loadingLoop = false
                if (transData.transResult == -8001) {
                    helperLog.appendLine(helperLogClassName, "Nothing To Settle")
                    helperLog.appendLine(helperLogClassName, "ResponseCode >> [99]")
                    showToast("Nothing to Settle", Toast.LENGTH_LONG)
                    result = false
                    cardJson.addProperty("ResponseCode", "99")
                    cardJson.addProperty("ResponseDescription", "Failed")
                    if (ServiceHolder.appIntent){
                        txnMap["ResponseCode"] = "99"
                        txnMap["ResponseDescription"] = "Failed"
                        txnMapList.add(txnMap)
                    }
                    continue
                }
                if (transData.transResult < 0) {
                    helperLog.appendLine(helperLogClassName, "Fail to Settle")
                    helperLog.appendLine(helperLogClassName, "ResponseCode >> [${Utility.HexString2ASCII(transData.respCode)}]")
                    showToast("Fail to Settle", Toast.LENGTH_LONG)
                    result = false
                    cardJson.addProperty("ResponseCode", Utility.HexString2ASCII(transData.respCode))
                    cardJson.addProperty("ResponseDescription", "Failed")
                    if (ServiceHolder.appIntent){
                        txnMap["ResponseCode"] = Utility.HexString2ASCII(transData.respCode)
                        txnMap["ResponseDescription"] = "Failed"
                        txnMapList.add(txnMap)
                    }
                    continue
                }

                settledProduct.put(tempObj, recordSummary)
                preparePrintRecord(tempObj, recordSummary, productArray)
                cardJson.addProperty("ResponseCode", "00")
                cardJson.addProperty("ResponseDescription", "Success")
                cardJson.add("SettlementDetail", productArray)
                settleJsonArray.add(cardJson)

                if (ServiceHolder.appIntent){
                    val gson = Gson()
                    val productArrayStr = gson.toJson(productArray)
                    txnMap["ResponseCode"] = "00"
                    txnMap["ResponseDescription"] = "Success"
                    txnMap["SettlementDetail"] = productArrayStr
                    txnMapList.add(txnMap)
                }

                val isLastItem = settlementProduct.lastIndex == forIndex
                if(isLastItem && totalSettlementCount <= 0){
                    printNoTransactionInfo()
                }

                val duplicateData = SettlementSummaryRepo.checkDuplicateData(requireContext())
                if(duplicateData) {
                    SettlementSummaryRepo.deleteSettlementSummary(requireContext(), listOf("acq_code", "mid", "tid"), listOf(tempObj.AcqCode, tempObj.AcqMid, tempObj.AcqTid))

                    val dbModelSettlementSummary = DbModelSettlementSummary(
                        tempObj.AcqCode,
                        tempObj.AcqMid,
                        tempObj.AcqTid,
                        "", "", "0", "false"
                    )
                    TmsHelper.checkSettlementSummary(requireContext(), dbModelSettlementSummary)
                }

                if(!isLastItem){
                    Thread.sleep(1000)
                }
            }

            if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT") ||  DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_SETTLEMENT_DAILY")) {
                ServiceHolder.clearSettlementBatch = !result
            }
            ServiceHolder.autoSettlementIsRunning = false
            return result
        }

        override fun onPostExecute(result: Boolean?) {
            super.onPostExecute(result)
            hideProgress()
            if(transData.transResult == Global.iso.err.txnApproved){
                child.visibility = View.GONE
            }

            updateBtnCheckboxVisibility()
        }
    }

    private fun getRecordCoroutine(log: HelperLog) {
        log.appendLine(helperLogClassName, "Get Settlement Record")

        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        acquirerLogoConfig = Gson().fromJson(DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)
        //batchNo = isoDb.getIsoBatchData("batchNo", "visam")
        batchNo = IsoBatchInfoRepo.getBatchInfo(requireContext(), "batchNo", "visam")?.value ?: "000001"

        if (dbModelMerchantConfig != null) {
            try {
                acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(dbModelMerchantConfig!!.AcqCode!!).data
            } catch (e: Exception) {
                e.printStackTrace()
                log.appendLine(helperLogClassName, "Exception in Settlement Get Acquirer Enum -> ", e.toString())
                log.logToFile(EnumLogFileName.TerminaLogException)
            }
        }

        // Check Is Last Settlement
        /*if (isLastSettlement) {
            Utils.readFromFile("lastsettlement.txt")[0]?.let { fileData ->
                val lastSettleString = fileData.split("|").map {
                    it.split(",").let { pairItem ->
                        Pair(pairItem[0].substring(1), pairItem.getOrNull(1)?.dropLast(1) ?: "")
                    }
                }
                settleInfo = lastSettleString as MutableList<Pair<String, String>>
            } ?: run {
                log.appendLine(helperLogClassName, "Last Settlement Not Found")
                requireActivity().runOnUiThread{
                    showToast("Last Settlement Not Found.", Toast.LENGTH_SHORT)
                }
            }
        } else {
            settlementProduct = ProductListRepo.getUnSettledProduct(requireContext())
            if(settlementProduct.isNotEmpty()){
                currentProductIndex = 0
                val currentProduct = settlementProduct[0]

                settlementSummaryData = SettlementSummaryRepo.getSelectiveData(
                    requireContext(),
                    listOf("acq_code", "mid", "tid"),
                    arrayOf(currentProduct.AcqCode, currentProduct.AcqMid, currentProduct.AcqTid)
                )
                isTpaAccount = currentProduct.IsTpaAccount.lowercase() == "true"
                settleInfo = constructPrintRecord(currentProduct, settlementSummaryData, JsonArray())
                helperLog.appendLine(helperLogClassName, "Settlement Info >> ", "$settleInfo")
            }
        }*/
        settlementProduct = ProductListRepo.getUnSettledProduct(requireContext())
        if(settlementProduct.isNotEmpty()){
            currentProductIndex = 0
            val currentProduct = settlementProduct[0]

            settlementSummaryData = SettlementSummaryRepo.getSelectiveData(
                requireContext(),
                listOf("acq_code", "mid", "tid"),
                arrayOf(currentProduct.AcqCode, currentProduct.AcqMid, currentProduct.AcqTid)
            )
            isTpaAccount = currentProduct.IsTpaAccount.lowercase() == "true"
            settleInfo = constructPrintRecord(currentProduct, settlementSummaryData, JsonArray())
            helperLog.appendLine(helperLogClassName, "Settlement Info >> ", "$settleInfo")
        }
        log.appendLine(helperLogClassName, "Finish Obtain Data.")
        log.logToFile(EnumLogFileName.TerminaLog)
    }


    private fun constructPrintRecord(productModel: DbModelProductList, settlementSummary: List<DbModelSettlementSummary>, productJsonArray: JsonArray): MutableList<Pair<String, String>>{
        helperLog.appendLine(helperLogClassName, "Constructing Print Record")
        Thread.sleep(200)
        val timeStamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH).format(Calendar.getInstance().time)
        var tid = productModel.AcqTid
        var mid = productModel.AcqMid
        if(isTpaAccount) {
            mid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
            tid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")
        }

        val acqName = ServiceHolder.getAcquirerSetting().acqName
        if (acqName.uppercase(Locale.ENGLISH) == "GOBIZ") {
            tid = Utils.maskString(tid, 4)
            mid = Utils.maskString(mid, 4)
        }

        val headerText = "SETTLEMENT REPORT"
        /*if (previewSettlement == 1) {
            headerText = "SALES SUMMARY"
        }*/
        val toPrintInfo = mutableListOf(
            "DATE/TIME" to timeStamp,
            "TERMINAL ID" to tid,
            "MERCHANT ID" to mid,
            "BATCH NO" to batchNo,
            "HEADER" to headerText,
        )

        val terminalConfig = ServiceHolder.getTerminalConfig()
        val optIn = DbModelTerminalConfig.getBooleanValue(terminalConfig, "OptIn")
        val cardTypeMap: MutableMap<String, String> = mutableMapOf(
            "visa" to "Visa",
            "master" to "Master",
        )
        if(acqName.equals("GOBIZ", true)) {
            cardTypeMap.put("upi", "UnionPay")
        }
        if (optIn) {
            cardTypeMap.put("mccs", "MyDebit")
        }

        val settlementType: MutableMap<String, String> = mutableMapOf(
            "txnCount" to "SALE COUNT",
            "txnTotal" to "SALE TOTAL",
            "voidTxnCount" to "VOID COUNT",
            "voidTxnTotal" to "VOID TOTAL",
        )

        val summaryMap: MutableMap<String, Int> = mutableMapOf(
            "txnCount" to 0,
            "txnTotal" to 0,
            "voidTxnCount" to 0,
            "voidTxnTotal" to 0,
        )

        for (cardTypeKey in cardTypeMap.keys) {
            val brandJson = JsonObject()
            brandJson.addProperty("SCHEME", cardTypeMap[cardTypeKey]!!)
            toPrintInfo.add("SCHEME" to cardTypeMap[cardTypeKey]!!)
            for(settleTypeKey in settlementType.keys) {
                val filteredData = settlementSummary.find { it.tag == settleTypeKey && it.subtag == "visam-$cardTypeKey" }

                var summaryValue = filteredData?.value ?: "0"
                var temp = summaryMap[settleTypeKey] ?: 0
                temp += Utils.atoi(summaryValue)
                summaryMap[settleTypeKey] = temp

                if(settleTypeKey.contains("Total")) {
                    summaryValue = Utils.getActualAmount(summaryValue)
                }
                toPrintInfo.add(settlementType[settleTypeKey]!! to summaryValue)
                brandJson.addProperty((settlementType[settleTypeKey]!!).replace(" ", ""), summaryValue)
            }
            productJsonArray.add(brandJson)
            toPrintInfo.add("br" to "")
        }
        toPrintInfo.add("hr" to "")
        toPrintInfo.add("br" to "")
        toPrintInfo.add("HEADER" to "TOTAL SETTLEMENT")
        for(summaryTypeKey in summaryMap.keys) {
            var summaryValue = summaryMap[summaryTypeKey].toString()
            if(summaryTypeKey.contains("Total")) {
                summaryValue = Utils.getActualAmount(summaryValue)
            }
            toPrintInfo.add(settlementType[summaryTypeKey]!! to summaryValue)
        }
        toPrintInfo.add("br" to "")
        toPrintInfo.add("hr" to "")
        return toPrintInfo
    }

    private fun printNoTransactionInfo() {
        val list: MutableList<MulPrintStrEntity> = java.util.ArrayList()
        val fontSize = FontFamily.MIDDLE

        list.add(MulPrintStrEntity("No Transaction Activity", FontFamily.BIG, false, Gravity.CENTER).setIsBold(Typeface.BOLD))
        val entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
        entity.gravity = Gravity.CENTER
        entity.yspace = 140
        list.add(entity)
        HelperCommon.sdkPrint(list)
    }


    fun preparePrintRecord(modelToPrint: DbModelProductList, recordSummary: List<DbModelSettlementSummary>, productJsonArray: JsonArray){
        helperLog.appendLine(helperLogClassName, "Prepare Print Record")
        val toPrintInfo = constructPrintRecord(modelToPrint, recordSummary, productJsonArray)
        val stringInfo = toPrintInfo.joinToString("|")
        Utils.write2File(arrayOf(stringInfo), "lastsettlement.txt")
        printInfo(toPrintInfo, false)


        val dbModelLastSettlement = DbModelLastSettlement (modelToPrint.AcqMid, modelToPrint.AcqTid, stringInfo, isTpaAccount.toString())
        LastSettlementRepo.addOrInsert(requireContext(), dbModelLastSettlement)

        helperLog.appendLine(helperLogClassName, "Finish Prepare Data.")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun printInfo(toPrintData: MutableList<Pair<String, String>>, forcePrint: Boolean) {
        var totalTxnCount = 0
        var productTxnCount = 0
        val list: MutableList<MulPrintStrEntity> = java.util.ArrayList()
        var productList: MutableList<MulPrintStrEntity> = java.util.ArrayList()
        val fontSize = FontFamily.MIDDLE

        var entity = MulPrintStrEntity("", fontSize)

        var imageFromAssetsFile = getImageFromAssetsFile(requireContext(), acqLogoEnumModel.HeaderLogoBmp)
        if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || isTpaAccount) {
            val paths = getInternalFilesPaths() + "powerBWLogo.png"
            val imgFile = File(paths)
            if (imgFile.exists()) {
                imageFromAssetsFile = BitmapFactory.decodeFile(imgFile.absolutePath)
            } else {
                imageFromAssetsFile = getImageFromAssetsFile(requireContext(), "image/logo_footer_small.bmp")
            }
        }
        entity.bitmap = imageFromAssetsFile
        entity.marginX = 50
        entity.gravity = Gravity.CENTER
        entity.isUnderline = true
        entity.yspace = 30
        list.add(entity)

        receiptMerchantDetailsModifier(list)

        list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

        var isTotalSettlement = false
        val mapIterator = toPrintData.iterator()
        while(mapIterator.hasNext()){
            val (key, keyValue) = mapIterator.next()
            if (key == "HEADER" && keyValue == "TOTAL SETTLEMENT") {
                isTotalSettlement = true
            }
            if(key == "SCHEME" || key == "hr"){
                if(productTxnCount > 0) list.let { list1 -> productList.let(list1::addAll) }
                totalTxnCount += productTxnCount
                productTxnCount = 0
                productList = java.util.ArrayList()
            }

            if(key == "HEADER"){
                productList.add(MulPrintStrEntity(keyValue, FontFamily.BIG, false, Gravity.CENTER).setIsBold(Typeface.BOLD))
                list.let { list1 -> productList.let(list1::addAll) }
                productTxnCount = 0
                productList = java.util.ArrayList()
            } else if (key == "hr") {
                list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
                productTxnCount = 0
                productList = java.util.ArrayList()
            } else {
                val label = if (key == "br") "" else key
                if (key.contains("COUNT")){
                    productTxnCount += Utils.atoi(keyValue.trim())
                }
                if (isTotalSettlement && key.contains("TOTAL")){
                    receiptTxnDetailsModifier(productList, label, keyValue)
                } else {
                    productList.add(
                        MulPrintStrEntity(
                            Utils.makeLineText(
                                TextItem(label).setFont(fontSize),
                                TextItem(keyValue).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                            ), fontSize
                        )
                    )
                }
            }
        }

        totalSettlementCount += totalTxnCount
        if(totalTxnCount <= 0){
            list.add(MulPrintStrEntity(Utils.makeLineText(TextItem("").setFont(fontSize), TextItem("").setFont(fontSize).setPaddingAlign(Gravity.RIGHT)), fontSize))
            list.add(MulPrintStrEntity("No Transaction Activity", FontFamily.BIG, false, Gravity.CENTER).setIsBold(Typeface.BOLD))
            list.add(MulPrintStrEntity(Utils.makeLineText(TextItem("").setFont(fontSize), TextItem("").setFont(fontSize).setPaddingAlign(Gravity.RIGHT)), fontSize))

            if(!forcePrint){
                return
            }
        }

        list.add(MulPrintStrEntity("Settlement Successful!", FontFamily.SMALL, false, Gravity.CENTER))

        if(!isTpaAccount) {
            if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
                //if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
                if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
                    list.add(MulPrintStrEntity("PARTNER WITH", FontFamily.SMALL, false, Gravity.CENTER))

                    entity = MulPrintStrEntity("", fontSize)
                    imageFromAssetsFile = getImageFromAssetsFile(requireContext(), acqLogoEnumModel.HeaderLogoBmp)
                    entity.bitmap = imageFromAssetsFile
                    entity.marginX = 50
                    entity.gravity = Gravity.CENTER
                    entity.isUnderline = true
                    entity.yspace = 30
                    list.add(entity)
                }
            } else {
                list.add(MulPrintStrEntity("POWERED BY", FontFamily.SMALL, false, Gravity.CENTER))

                entity = MulPrintStrEntity("", fontSize)
                imageFromAssetsFile = getImageFromAssetsFile(requireContext(), "image/logo_footer_small.bmp")
                val paths = getInternalFilesPaths() + "powerBWLogo.png"
                val imgFile = File(paths)
                if (imgFile.exists()) {
                    imageFromAssetsFile = BitmapFactory.decodeFile(imgFile.absolutePath)
                }
                entity.bitmap = imageFromAssetsFile
                entity.marginX = 50
                entity.gravity = Gravity.CENTER
                entity.isUnderline = true
                entity.yspace = 30
                list.add(entity)
            }
        } else {
            list.add(MulPrintStrEntity("Behind Every Payment, There's A Smile.", FontFamily.SMALL, false, Gravity.CENTER))
        }

        entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
        entity.gravity = Gravity.CENTER
        entity.yspace = 140
        list.add(entity)
        HelperCommon.sdkPrint(list)
    }

    //QR
    private inner class TransQR(private var child: View) : CoroutineTask<String?, Boolean>() {
        override fun onPreExecute() {
            super.onPreExecute()
            helperLog.appendLine(helperLogClassName, "Processing QR Settlement")
            showProgress("QR Settlement", "Processing... Please Wait")
        }

        override fun doInBackground(vararg params: String?): Boolean {
            getRecordCoroutineQR(helperLog)
            printQrReceipt(printInfoQr)
            Utils.write2File(settleInfoQr, "qrlastsettlement.txt")
            TransactionQrRepo.truncateTable(requireContext())
            return true
        }

        override fun onPostExecute(result: Boolean?) {
            super.onPostExecute(result)
            hideProgress()

            child.visibility = View.GONE
            updateBtnCheckboxVisibility()
        }
    }

    private fun getRecordCoroutineQR(log: HelperLog) {
        log.appendLine(helperLogClassName, "Get QR Settlement Record")
        val settlementInfoQr: Array<String?>
        val totalSettlementInfoQr: Array<String?>
        id = arguments?.getInt("Last")?: 0
        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()

        if (id == 0) {
            qrPayTotalTxnAmt = arrayOfNulls(qrPayBrandV2.size)
            qrPayTotalTxnCount = arrayOfNulls(qrPayBrandV2.size)
            qrPayTotalVoidTxnAmt = arrayOfNulls(qrPayBrandV2.size)
            qrPayTotalVoidTxnCount = arrayOfNulls(qrPayBrandV2.size)

            for (i in qrPayBrandV2.indices) {
                val modelData = qrPayBrandV2[i]

                val tempSaleA = getTxnTotalAmount(modelData.productCode, "Sale").toLong()
                val tempSaleC = getTxnTotalCount(modelData.productCode, "Sale")
                val tempVoidA =  getTxnTotalAmount(modelData.productCode, "Void").toLong()
                val tempVoidC = getTxnTotalCount(modelData.productCode, "Void")
                SaleA += tempSaleA
                SaleC += tempSaleC
                VoidA += tempVoidA
                VoidC += tempVoidC

                qrPayTotalTxnCount[i] = tempSaleC.toString()
                qrPayTotalTxnAmt[i] = tempSaleA.toString()
                qrPayTotalVoidTxnCount[i] =  tempVoidC.toString()
                qrPayTotalVoidTxnAmt[i] =  tempVoidA.toString()
            }

            txnDt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH).format(
                Calendar.getInstance().time
            )
            settlementInfoQr = arrayOf(txnDt, dbModelMerchantConfig?.QrMid ?: "", "")
            totalSettlementInfoQr = arrayOf("", "", "", SaleC.toString(), Utils.getActualAmount(SaleA.toString()), VoidC.toString(), Utils.getActualAmount(VoidA.toString()), "", "")
        } else {
            settleInfoQr = Utils.readFromFile("qrlastsettlement.txt")
            settlementInfoQr = arrayOfNulls<String>(3)
            totalSettlementInfoQr = arrayOfNulls<String>(9)
            val qrDetailsLen = (settleInfoQr.size - settlementInfoQr.size - totalSettlementInfoQr.size) / 5
            if (qrDetailsLen > 0) {
                System.arraycopy(settleInfoQr, 0, settlementInfoQr, 0, settlementInfoQr.size)
            } else {
                System.arraycopy(settleInfoQr, 0, settlementInfoQr, 0, settleInfoQr.size - totalSettlementInfoQr.size)
            }
            qrPayBrand = arrayOfNulls(qrDetailsLen)
            qrPayTotalTxnAmt = arrayOfNulls(qrDetailsLen)
            qrPayTotalTxnCount = arrayOfNulls(qrDetailsLen)
            qrPayTotalVoidTxnAmt = arrayOfNulls(qrDetailsLen)
            qrPayTotalVoidTxnCount = arrayOfNulls(qrDetailsLen)
            var count = 0
            var tCount = 0
            for (s in settleInfoQr) {
                when (count) {
                    3 -> qrPayBrand[tCount] = s
                    4 -> qrPayTotalTxnCount[tCount] = s
                    5 -> qrPayTotalTxnAmt[tCount] = s
                    6 -> qrPayTotalVoidTxnCount[tCount] = s
                    7 -> qrPayTotalVoidTxnAmt[tCount] = s
                }
                count++
                if (count > 7) {
                    count = 3
                    tCount++
                }
            }
        }
        log.appendLine(helperLogClassName, "Finish Obtain Data.")
        log.logToFile(EnumLogFileName.TerminaLog)

        val tags2 = arrayOf("SCHEME", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL")
        val tags3 = arrayOf("", "", "TOTAL SETTLEMENT", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL", "", "")
        settleInfoQr = arrayOfNulls(settlementInfoQr.size + (qrPayBrandV2.size * tags2.size) + tags3.size)
        System.arraycopy(settlementInfoQr, 0, settleInfoQr, 0, settlementInfoQr.size)
        System.arraycopy(totalSettlementInfoQr, 0, settleInfoQr, 0, totalSettlementInfoQr.size)

        formSettleInfoQr(settlementInfoQr)
    }

    private fun formSettleInfoQr(value: Array<String?>) {
        val tags2 = arrayOf("SCHEME", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL")
        val tags3 = arrayOf("", "", "TOTAL SETTLEMENT", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL", "", "")
        settleInfoQr = arrayOfNulls(value.size + (qrPayBrandV2.size * tags2.size) + tags3.size)
        System.arraycopy(value, 0, settleInfoQr, 0, value.size)
        var count = value.size

        val qrJson = JsonObject()
        val productArray = JsonArray()
        qrJson.addProperty("ResponseCode", "00")
        qrJson.addProperty("ResponseDescription", "Success")
        qrJson.addProperty("TransactionDateTime", value[0])
        qrJson.addProperty("TransactionMID", value[1])
        qrJson.addProperty("TransactionTID", "-")

        val headerText = "SETTLEMENT REPORT"
        /*if (previewSettlement == 1) {
            headerText = "SALES SUMMARY"
        }*/
        val toPrintInfo = mutableListOf(
            "Date/Time" to (settleInfoQr[0] ?: ""),
            "MERCHANT CODE" to (settleInfoQr[1] ?: ""),
            "HEADER" to headerText,
        )

        qrBrand@ for (j in qrPayBrandV2.indices) {
            val modelData = qrPayBrandV2[j]

            val brandJson = JsonObject()
            for (i in tags2.indices) {
                var res: String? = ""
                if (i == 0 && qrPayTotalTxnCount[j] == "0" && qrPayTotalVoidTxnCount[j] == "0") {
                    continue@qrBrand
                }

                when (i) {
                    0 -> res = modelData.productCode
                    1 -> res = qrPayTotalTxnCount[j]
                    2 -> {
                        if (qrPayTotalTxnAmt[j] == null) {
                            qrPayTotalTxnAmt[j] = "000"
                        }
                        res = if (id == 1) { qrPayTotalTxnAmt[j] }
                        else Utils.getActualAmount(qrPayTotalTxnAmt[j])
                    }
                    3 -> res = qrPayTotalVoidTxnCount[j]
                    4 -> {
                        if (qrPayTotalVoidTxnAmt[j] == null) {
                            qrPayTotalVoidTxnAmt[j] = "000"
                        }
                        res = if (id == 1) { qrPayTotalVoidTxnAmt[j] }
                        else Utils.getActualAmount(qrPayTotalVoidTxnAmt[j])
                    }
                }
                if (res == null) {
                    res = "0"
                }
                toPrintInfo.add(tags2[i] to res)
                brandJson.addProperty(tags2[i].replace(" ", ""), res)
                settleInfoQr[count] = res
                count++
            }
            productArray.add(brandJson)
            toPrintInfo.add("br" to "")
        }

        toPrintInfo.add("hr" to "")
        toPrintInfo.add("br" to "")
        for (j in tags3.indices) {
            val res = when (j){
                2 -> {
                    toPrintInfo.add("HEADER" to tags3[j])
                    tags3[j]
                }
                3 -> {
                    toPrintInfo.add(tags3[j] to SaleC.toString())
                    SaleC.toString()
                }
                4 -> {
                    toPrintInfo.add(tags3[j] to Utils.getActualAmount(SaleA.toString()))
                    Utils.getActualAmount(SaleA.toString())
                }
                5 -> {
                    toPrintInfo.add(tags3[j] to VoidC.toString())
                    VoidC.toString()
                }
                6 -> {
                    toPrintInfo.add(tags3[j] to Utils.getActualAmount(VoidA.toString()))
                    Utils.getActualAmount(VoidA.toString())
                }
                else -> ""
            }
            settleInfoQr[count] = res
            count++
        }
        toPrintInfo.add("br" to "")
        toPrintInfo.add("hr" to "")

        qrJson.add("SettlementDetail", productArray)
        settleJsonArray.add(qrJson)
        printInfoQr = toPrintInfo
        settleInfoQr = settleInfoQr.filterNotNull().toTypedArray()

        if (ServiceHolder.appIntent){
            val gson = Gson()
            val productArrayStr = gson.toJson(productArray)
            txnMap = HashMap<String, Any>()

            txnMap["ResponseCode"] = "00"
            txnMap["ResponseDescription"] = "Settled"
            txnMap["SettlementDateTime"] = value[0].toString()
            txnMap["SettlementMID"] = value[1].toString()
            txnMap["SettlementTID"] = "-"
            txnMap["SettlementDetail"] = productArrayStr
            txnMapList.add(txnMap)
        }
    }

    private fun printQrReceipt(toPrintData: MutableList<Pair<String, String>>) {
        val list: MutableList<MulPrintStrEntity> = ArrayList()
        val fontSize = FontFamily.MIDDLE

        var entity = MulPrintStrEntity("", fontSize)
        entity.marginX = 50
        entity.gravity = Gravity.CENTER
        entity.isUnderline = true
        entity.yspace = 30
        list.add(entity)
        receiptMerchantDetailsModifier(list)
        list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

        var isTotalSettlement = false
        val mapIterator = toPrintData.iterator()
        while(mapIterator.hasNext()){
            val (key, keyValue) = mapIterator.next()
            if (key == "HEADER" && keyValue == "TOTAL SETTLEMENT") {
                isTotalSettlement = true
            }
            when (key){
                "HEADER" -> {
                    list += MulPrintStrEntity(keyValue, FontFamily.BIG, false, Gravity.CENTER).setIsBold(Typeface.BOLD)
                }
                "hr" -> {
                    list += MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize)
                }
                else -> {
                    val label = if (key == "br") "" else key
                    if (isTotalSettlement && key.contains("TOTAL")){
                        receiptTxnDetailsModifier(list, label, keyValue )
                    } else {
                        list += MulPrintStrEntity(Utils.makeLineText(
                            TextItem(label).setFont(fontSize),
                            TextItem(keyValue).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)), fontSize)
                    }
                }
            }
        }

        list.add(MulPrintStrEntity("POWERED BY", FontFamily.SMALL, false, Gravity.CENTER))
        entity = MulPrintStrEntity("", fontSize)
        var imageFromAssetsFile = getImageFromAssetsFile(requireContext(), "image/logo_footer_small.bmp")
        val paths = getInternalFilesPaths() + "powerBWLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            imageFromAssetsFile = BitmapFactory.decodeFile(imgFile.absolutePath)
        }
        entity.bitmap = imageFromAssetsFile
        entity.marginX = 50
        entity.gravity = Gravity.CENTER
        entity.isUnderline = true
        entity.yspace = 30
        list.add(entity)
        entity = MulPrintStrEntity(" \n ", FontFamily.BIG)
        entity.gravity = Gravity.CENTER
        entity.yspace = 140
        list.add(entity)
        HelperCommon.sdkPrint(list)
    }

    private fun checkRecordQr(): Boolean {
        helperLog.appendLine(helperLogClassName, "Checking QR Transaction Record")
        var totalTxnCount = 0

        for (i in qrPayBrandV2.indices) {
            val modelData = qrPayBrandV2[i]

            totalTxnCount += getTxnTotalCount(modelData.productCode, "Sale")
            totalTxnCount += getTxnTotalCount(modelData.productCode, "Void")
            if(totalTxnCount > 0) {
                break
            }
        }
        return totalTxnCount > 0
    }


    private fun getTxnTotalAmount(productCode: String, txnType: String): String {
        val strTotalTxnAmt = TransactionQrRepo.countTransactionAmountByType(requireContext(), productCode, txnType)
        return if (strTotalTxnAmt <= 0) "000" else strTotalTxnAmt.toString()
    }

    private fun getTxnTotalCount(productCode: String, txnType: String): Int {
        val iTotalCount = TransactionQrRepo.countTransactionCountByType(requireContext(), productCode, txnType)
        return if (iTotalCount <= 0) 0 else iTotalCount
    }

    // Function to update the state of the "Select All" checkbox
    private fun updateSelectAllCheckBox() {
        val allChecked = areAllCheckBoxesChecked()
        selectAllCheckBox.isChecked = allChecked
    }

    // Function to check if all individual checkboxes are checked
    private fun areAllCheckBoxesChecked(): Boolean {
        for (i in 0 until settleOptionlist.childCount) {
            val child = settleOptionlist.getChildAt(i)
            if (child is LinearLayout) {
                val checkBox = child.getChildAt(2) as CheckBox
                if (!checkBox.isChecked) {
                    return false
                }
            }
        }
        return true
    }

    private suspend fun performSettleAction(directOnBack: Boolean) {
        helperLog.appendLine(helperLogClassName, "Initiate Perform Settlement")
        if (runningBlock == 0) {
            helperLog.appendLine(helperLogClassName, "Into Settlement Action")
            runningBlock = 1
            for (i in 0 until settleOptionlist.childCount) {
                val child = settleOptionlist.getChildAt(i)
                if (child is LinearLayout) {
                    val checkBox = child.getChildAt(2) as CheckBox
                    if (checkBox.isChecked) {
                        val productTitle = child.getChildAt(1) as CheckedTextView
                        if (this@SettleOptionFragment::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "SettleItem[$i]- ${productTitle.text}")
                        if (child.visibility != View.GONE) {
                            when (productTitle.text.toString()) {
                                "QR PAYMENT" -> {
                                    TransQR(child).executeAwait().await()
                                }

                                else -> {
                                    Trans(child).executeAwait().await()
                                }
                            }
                            if (this@SettleOptionFragment::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "\tDONE ${productTitle.text}!")
                        } else {
                            if (this@SettleOptionFragment::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "\tSKIP ${productTitle.text}!")
                        }
                    }
                }
            }
            runningBlock = 0

            if (directOnBack) {
                customOnBackPress()
            }
        } else {
            helperLog.appendLine(helperLogClassName, "Settlement already running")
            println("Settlement already running")
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun updateBtnCheckboxVisibility(){
        var allHidden = true

        for (i in 0 until settleOptionlist.childCount) {
            val childView = settleOptionlist.getChildAt(i)
            if (childView.visibility != View.GONE) {
                allHidden = false
                break
            }
        }

        if (allHidden) {
            selectAllCheckBox.visibility = View.GONE
            btnBack.visibility = View.GONE
            btnOK.visibility = View.GONE

            val emptyTextView = TextView(requireContext())
            emptyTextView.text = "No Settlement Option Available.\nPlease Perform Transaction First."
            emptyTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            emptyTextView.textSize = 18f
            val paramsTV = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            paramsTV.setMargins(
                Helper.getInstance().getDpValue(20), 0, Helper.getInstance().getDpValue(20),
                Helper.getInstance().getDpValue(20)
            )
            emptyTextView.layoutParams = paramsTV
            settleOptionlist.addView(emptyTextView)

        } else {
            selectAllCheckBox.visibility = View.VISIBLE
            selectAllCheckBox.setOnClickListener {
                val isChecked = selectAllCheckBox.isChecked
                helperLog.appendLine(helperLogClassName,
                    "Selected :: ALL acquirers -> ${if (isChecked) "included" else "excluded"} [SETTLEMENT]")
                for (i in 0 until settleOptionlist.childCount) {
                    val child = settleOptionlist.getChildAt(i)
                    if (child is LinearLayout) {
                        val checkBox = child.getChildAt(2) as CheckBox
                        checkBox.isChecked = isChecked
                    }
                }
            }
            btnBack.visibility = View.VISIBLE
            btnBack.setDebouncedOnClickListener {
                helperLog.appendLine(helperLogClassName, "User Cancel :: settlement abandoned at acquirer selection")
                customOnBackPress()
            }

            btnOK.visibility = View.VISIBLE
            btnOK.setDebouncedOnClickListener {
                lifecycleScope.launch {
                    if(!ServiceHolder.autoSettlementIsRunning && runningBlock == 0) {
                        helperLog.appendLine(helperLogClassName, "Validation passed :: operator confirmed settlement -> performSettleAction")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        performSettleAction(false)
                    } else {
                        helperLog.appendLine(helperLogClassName,
                            "REJECT :: settlement already running (autoRunning=${ServiceHolder.autoSettlementIsRunning} runningBlock=$runningBlock)")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        showToast("Please hold on Settlement Running", Toast.LENGTH_SHORT)
                    }
                }
            }
        }
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        if (ServiceHolder.appIntent) {
            ServiceHolder.appIntent = false
            onBackToApp()
        } else {
            if (ServiceHolder.appHTTP) {
                jObject.add("SettlementDetail", settleJsonArray)
                HTTPServer.getInstance().setResponseMessage(jObject.toString())
                ServiceHolder.appHTTP = false
            }

            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            navigateToHome(dbModelTerminalConfig)
        }
    }

    private fun onBackToApp() {
        // Handing control back to the calling POS app: this path ends in finish(), which
        // can be the last thing this process does -- onDestroyView is not guaranteed to
        // run to completion after it. Flush so the hand-back is always on disk.
        if (this@SettleOptionFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "SettleOption :: returning to caller app, no response map")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        startActivity(Intent(requireContext(), TransactionTransmitter::class.java).apply {
            putExtra(TxnKeys.SETTLE_TXN_MAP, txnMapList)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        requireActivity().finish()
    }
}