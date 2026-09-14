package com.sc.mf919pro.kotlin.fragment

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.helper.widget.Layer
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.gson.Gson
import com.library.terminal.Utility
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentReceiptBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.activity.Utils.TextItem
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919pro.kotlin.database.model.DbModelLastSettlement
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.model.DbModelSettlementSummary
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.LastSettlementRepo
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.database.repo.SettlementSummaryRepo
import com.sc.mf919pro.kotlin.helper_common.Helper.Companion.getInstance
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.appHTTP
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.appIntent
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getInternalFilesPaths
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tms.models.AcquirerLogoReplaceObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Display + print of a settlement summary. This fragment performs NO settlement itself
 * (the actual ISO settlement runs in SettleOptionFragment). It is reached for:
 *  - pre-settle preview (from SettleOption, previewSettlement=1),
 *  - last-settlement view (from Settings, lastSettlement=1),
 *  - history reprint.
 * It also absorbs the former SettlementPreviewFragment (QR-preview button + settleOption back).
 */
class SettlementFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    private lateinit var mainReceipt: LinearLayout
    private lateinit var settleInfo: MutableList<Pair<String, String>>
    private lateinit var acqLogoEnumModel: AcquirerLogoEnumModel

    private var isLastSettlement = false
    private var isPreviewSettlement = false
    // Entered from Card History: hide the QR-preview option and pop back to history on exit
    // (instead of navigating to SettleOption like the pre-settle preview flow does).
    private var isFromHistory = false

    var batchNo: String = ""

    var totalSettlementCount = 0
    var currentProductIndex = 0
    var settlementProduct: List<DbModelProductList> = arrayListOf()
    private var settlementSummaryData: List<DbModelSettlementSummary> = arrayListOf()
    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    private var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
    var dbModelTerminalConfig: DbModelTerminalConfig? = null
    private var lastSettlementList: List<DbModelLastSettlement> = arrayListOf()
    var isTpaAccount = false

    private var _binding: FragmentReceiptBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReceiptBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "Settlement OnDestroyView :: screen ended")
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
            "Settlement Fragment"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Settlement Fragment")
        acqLogoEnumModel = AcquirerLogoEnumModel(R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, resources.getString(R.string.app_name_about), resources.getString(R.string.app_name_about))

        mainReceipt = binding.mainReceipt
        settleInfo = mutableListOf()
        isPreviewSettlement = (arguments?.getInt("previewSettlement") ?: 0) != 0
        isLastSettlement = (arguments?.getInt("lastSettlement") ?: 0) != 0
        isFromHistory = (arguments?.getInt("fromHistory") ?: 0) != 0

        // Custom back press handling
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            })

        // Button Listener
        binding.cancelBtn.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: settlement preview closed")
            customOnBackPress()
        }
        binding.previousBtn.setOnClickListener { switchRecordBackward() }
        binding.nextBtn.setOnClickListener { switchRecordForward() }
        binding.previousBtnLastSettlement.setOnClickListener { switchLastSettlementBackward() }
        binding.nextBtnLastSettlement.setOnClickListener { switchLastSettlementForward() }
        binding.settleBtn.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: print settlement receipt [SETTLE_BTN]")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            printReceipt()
        }
        binding.printBtn.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: print settlement receipt [PRINT_BTN]")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            printReceipt()
        }
        binding.forwardBtn.setOnClickListener {
            navigateSafe(R.id.action_settlePreview_to_settleQrPreview, androidx.core.os.bundleOf("previewSettlement" to 1))
        }

        lifecycleScope.launch {
            getRecordCoroutine()
        }
    }

    private suspend fun getRecordCoroutine() {
        helperLog.appendLine(helperLogClassName, "Get Settlement Record -----start-----")
        val startCoroutine = CoroutineScope(Dispatchers.IO).launch {

            dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
            dbModelTerminalConfig = getTerminalConfig()
            acquirerLogoConfig = Gson().fromJson(DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)
            batchNo = IsoBatchInfoRepo.getBatchInfo(requireContext(), "batchNo", "visam")?.value ?: ""

            if (dbModelMerchantConfig != null) {
                try {
                    acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(dbModelMerchantConfig!!.AcqCode!!).data
                } catch (e: Exception) {
                    e.printStackTrace()
                    helperLog.appendLine(helperLogClassName, "Exception in Settlement Get Acquirer Enum -> ", e.toString())
                    helperLog.logToFile(EnumLogFileName.TerminaLogException)
                }
            }

            // Display/print only: never show the settle button.
            requireActivity().runOnUiThread {
                binding.settleBtn.visibility = View.GONE
                binding.printBtn.visibility = View.VISIBLE
            }

            if (isLastSettlement) {
                lastSettlementList = LastSettlementRepo.getAll(ServiceHolder.getContext())
                if (lastSettlementList.isNotEmpty()) {
                    currentProductIndex = 0

                    if (lastSettlementList.size > 1) {
                        requireActivity().runOnUiThread {
                            binding.previousBtnLastSettlement.visibility = View.VISIBLE
                            binding.nextBtnLastSettlement.visibility = View.VISIBLE
                        }
                    }

                    val tempObj = lastSettlementList[currentProductIndex]
                    isTpaAccount = tempObj.isTpaAccount.lowercase() == "true"
                    tempObj.settleInfo.let { fileData ->
                        val lastSettleString = fileData.split("|").map {
                            it.split(",").let { pairItem ->
                                Pair(pairItem[0].substring(1), pairItem.getOrNull(1)?.dropLast(1) ?: "")
                            }
                        }
                        settleInfo = lastSettleString as MutableList<Pair<String, String>>
                    }
                } else {
                    showToast("Last Settlement Not Found.", Toast.LENGTH_SHORT)
                }
            } else {
                settlementProduct = ProductListRepo.getUnSettledProduct(requireContext())
                if (settlementProduct.isNotEmpty()) {
                    currentProductIndex = 0
                    val currentProduct = settlementProduct[0]
                    isTpaAccount = currentProduct.IsTpaAccount.lowercase() == "true"

                    if (settlementProduct.size > 1) {
                        requireActivity().runOnUiThread {
                            binding.previousBtn.visibility = View.VISIBLE
                            binding.nextBtn.visibility = View.VISIBLE
                        }
                    } else if (isPreviewSettlement && !isFromHistory) {
                        requireActivity().runOnUiThread {
                            binding.forwardBtn.visibility = View.VISIBLE
                        }
                    }

                    settlementSummaryData = SettlementSummaryRepo.getSelectiveData(
                        requireContext(),
                        listOf("acq_code", "mid", "tid"),
                        arrayOf(currentProduct.AcqCode, currentProduct.AcqMid, currentProduct.AcqTid)
                    )
                    settleInfo = withContext(Dispatchers.Default) {
                        constructPrintRecord(currentProduct, settlementSummaryData)
                    }
                }
            }

            helperLog.appendLine(helperLogClassName, "Finish Obtain Data.")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }

        var loadingTitle = "Settlement"
        if (isPreviewSettlement) {
            loadingTitle = "Preview Summary"
        }

        showProgress(loadingTitle, "Loading...")
        startCoroutine.join()
        formLayout(mainReceipt, settleInfo)
        hideProgress()
    }

    private suspend fun switchRecordCoroutine() {
        helperLog.appendLine(helperLogClassName, "Switch Settlement Record -----start-----")
        val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
            val currentProduct = settlementProduct[currentProductIndex]

            settlementSummaryData = SettlementSummaryRepo.getSelectiveData(
                requireContext(),
                listOf("acq_code", "mid", "tid"),
                arrayOf(currentProduct.AcqCode, currentProduct.AcqMid, currentProduct.AcqTid)
            )
            settleInfo = withContext(Dispatchers.Default) {
                constructPrintRecord(currentProduct, settlementSummaryData)
            }
            helperLog.appendLine(helperLogClassName, "Finish Switch Data.")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }

        showProgress("Settlement", "Loading...")
        startCoroutine.join()
        formLayout(mainReceipt, settleInfo)
        hideProgress()
    }

    private suspend fun switchLastSettlementCoroutine() {
        helperLog.appendLine(helperLogClassName, "Switch Last Settlement Record -----start-----")
        val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
            val currentProduct = lastSettlementList[currentProductIndex]
            currentProduct.settleInfo.let { fileData ->
                val lastSettleString = fileData.split("|").map {
                    it.split(",").let { pairItem ->
                        Pair(pairItem[0].substring(1), pairItem.getOrNull(1)?.dropLast(1) ?: "")
                    }
                }
                settleInfo = lastSettleString as MutableList<Pair<String, String>>
            }
            helperLog.appendLine(helperLogClassName, "Finish Switch Data.")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }

        showProgress("Last Settlement", "Loading...")
        startCoroutine.join()
        formLayout(mainReceipt, settleInfo)
        hideProgress()
    }

    private fun constructPrintRecord(productModel: DbModelProductList, settlementSummary: List<DbModelSettlementSummary>): MutableList<Pair<String, String>>{
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

        var headerText = "SETTLEMENT REPORT"
        if (isPreviewSettlement) {
            headerText = "SALES SUMMARY"
        }
        val toPrintInfo = mutableListOf(
            "Date/Time" to timeStamp,
            "TERMINAL ID" to tid,
            "MERCHANT ID" to mid,
            "BATCH NO" to batchNo,
            "HEADER" to headerText,
        )

        val terminalConfig = getTerminalConfig()
        val optIn = getBooleanValue(terminalConfig, "OptIn")
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
            "cashOutTotal" to "CASHOUT TOTAL",
        )
        val summaryMap: MutableMap<String, Int> = mutableMapOf(
            "txnCount" to 0,
            "txnTotal" to 0,
            "voidTxnCount" to 0,
            "voidTxnTotal" to 0,
            "cashOutTotal" to 0,
        )

        for (cardTypeKey in cardTypeMap.keys) {
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

                if(!settleTypeKey.equals("cashOutTotal", true)) {
                    toPrintInfo.add(settlementType[settleTypeKey]!! to summaryValue)
                }
            }
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

    private fun printInfo(toPrintData: MutableList<Pair<String, String>>, forcePrint: Boolean) {
        var totalTxnCount = 0
        var productTxnCount = 0
        val list: MutableList<MulPrintStrEntity> = ArrayList()
        var productList: MutableList<MulPrintStrEntity> = ArrayList()
        val fontSize = FontFamily.MIDDLE

        var entity = MulPrintStrEntity("", fontSize)

        var imageFromAssetsFile = getImageFromAssetsFile(requireContext(), acqLogoEnumModel.HeaderLogoBmp)
        if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || isTpaAccount) {
            val paths = getInternalFilesPaths() + "powerBWLogo.png"
            val imgFile = File(paths)
            if (imgFile.exists()) {
                imageFromAssetsFile = BitmapFactory.decodeFile(imgFile.absolutePath)
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
                productList = ArrayList()
            }

            when (key){
                "HEADER" -> {
                    productList.add(MulPrintStrEntity(keyValue, FontFamily.BIG, false, Gravity.CENTER).setIsBold(Typeface.BOLD))
                    list.let { list1 -> productList.let(list1::addAll) }
                    productTxnCount = 0
                    productList = ArrayList()
                }
                "hr" -> {
                    list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
                    productTxnCount = 0
                    productList = ArrayList()
                }
                else -> {
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

        if(isLastSettlement) {
            list.add(MulPrintStrEntity("Settlement Successful!", FontFamily.SMALL, false, Gravity.CENTER))

            if(isTpaAccount) {
                list.add(MulPrintStrEntity("Behind Every Payment, There's A Smile.", FontFamily.SMALL, false, Gravity.CENTER))
            }
        }

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
        }

        entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
        entity.gravity = Gravity.CENTER
        entity.yspace = 140
        list.add(entity)
        HelperCommon.sdkPrint(list)
    }

    private fun printReceipt() {
        totalSettlementCount = 0
        val animSlideDown = AnimationUtils.loadAnimation(requireContext(), R.anim.receipt_slide_up)
        val scrollReceipt: ScrollView = binding.receipt
        scrollReceipt.startAnimation(animSlideDown)
        printInfo(settleInfo, true)
    }

    private fun formLayout(mainReceipt: LinearLayout, listData: MutableList<Pair<String, String>>) {
        mainReceipt.removeAllViews()
        val headerImageView = binding.headerImageView
        headerImageView.setImageResource(acqLogoEnumModel.HeaderLogoPng)
        if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || isTpaAccount) {
            val paths = getInternalFilesPaths() + "powerLogo.png"
            val imgFile = File(paths)
            if (imgFile.exists()) {
                val myBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                headerImageView.setImageBitmap(myBitmap)
            }
        }

        val name = TextView(requireContext())
        name.textSize = 9f
        name.textAlignment = View.TEXT_ALIGNMENT_CENTER
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        name.text = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantName")
        val addr = TextView(requireContext())
        addr.textSize = 9f
        addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
        addr.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        addr.text = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantAddress")
        mainReceipt.addView(name)
        mainReceipt.addView(addr)

        listData.forEach { (key, keyValue) ->
            val ll = LinearLayout(requireContext())
            when (key) {
                "HEADER" -> {
                    ll.orientation = LinearLayout.HORIZONTAL
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params1.setMargins(5, 0, 5, 0)
                    val tv = TextView(requireContext())
                    tv.layoutParams = params1
                    tv.textSize = 14f
                    tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    tv.setTypeface(tv.typeface, Typeface.BOLD)
                    tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                    tv.text = keyValue
                    ll.addView(tv)
                }
                "hr" -> {
                    ll.orientation = LinearLayout.HORIZONTAL
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, getInstance().getDpValue(3)
                    )
                    val viewContainer = View(requireContext())
                    viewContainer.layoutParams = params1
                    viewContainer.setBackgroundResource(R.drawable.dotted_line)
                    viewContainer.setLayerType(Layer.LAYER_TYPE_SOFTWARE, null)
                    ll.addView(viewContainer)
                }
                else -> {
                    ll.orientation = LinearLayout.HORIZONTAL
                    ll.weightSum = 2f
                    val params1 = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params1.setMargins(5, 0, 5, 0)
                    params1.weight = 1f
                    val label = if (key == "br") "" else key
                    val tv = TextView(requireContext())
                    tv.layoutParams = params1
                    tv.textSize = 11f
                    tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                    tv.text = label
                    val tv1 = TextView(requireContext())
                    tv1.layoutParams = params1
                    tv1.textSize = 11f
                    tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    tv1.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                    tv1.text = keyValue
                    ll.addView(tv)
                    ll.addView(tv1)
                }
            }
            mainReceipt.addView(ll)
        }

        val footer = LinearLayout(requireContext())
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 5, 0, 0)
        params.gravity = Gravity.CENTER
        footer.orientation = LinearLayout.VERTICAL
        footer.layoutParams = params

        if (isLastSettlement && lastSettlementList.isNotEmpty()) {
            val settleSuccess = TextView(requireContext())
            settleSuccess.textSize = 9f
            settleSuccess.textAlignment = View.TEXT_ALIGNMENT_CENTER
            settleSuccess.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            settleSuccess.text = "Settlement Successful!"
            footer.addView(settleSuccess)

            if(isTpaAccount) {
                val tpaSlogan = TextView(requireContext()).apply {
                    textSize = 9f
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                    text = "Behind Every Payment, There's A Smile."
                }
                footer.addView(tpaSlogan)
            }
        }

        val power = TextView(requireContext())
        power.textSize = 9f
        power.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
        power.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))

        val im = ImageView(requireContext())
        val params1 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, getInstance().getDpValue(30)
        )
        params1.gravity = Gravity.CENTER
        im.layoutParams = params1
        im.adjustViewBounds = true

        if(!isTpaAccount) {
            if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
                //if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
                if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
                    power.text = "PARTNER WITH"
                    footer.addView(power)
                    im.setImageResource(acqLogoEnumModel.HeaderLogoPng)
                    footer.addView(im)
                }
            } else {
                power.text = "POWERED BY"
                footer.addView(power)
                im.setImageResource(R.mipmap.logo)
                val paths = getInternalFilesPaths() + "powerLogo.png"
                val imgFile = File(paths)
                if (imgFile.exists()) {
                    val myBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                    im.setImageBitmap(myBitmap)
                    Utils.debugLogPrint("TAG", "onCreate: image")
                }
                footer.addView(im)
            }
        }
        mainReceipt.addView(footer)
    }

    private fun switchRecordBackward() {
        if(currentProductIndex == 0){
            showToast("First page has reached", Toast.LENGTH_SHORT)
            return
        }

        lifecycleScope.launch {
            currentProductIndex--
            switchRecordCoroutine()
            if (isPreviewSettlement && settlementProduct.size > 1) {
                binding.nextBtn.visibility = View.VISIBLE
                binding.forwardBtn.visibility = View.GONE
            }
        }
    }

    private fun switchRecordForward() {
        if(currentProductIndex + 1 == settlementProduct.size){
            showToast("Last page has reached", Toast.LENGTH_SHORT)
            return
        }

        lifecycleScope.launch {
            currentProductIndex++
            switchRecordCoroutine()
            if (isPreviewSettlement && !isFromHistory && currentProductIndex + 1 == settlementProduct.size) {
                binding.nextBtn.visibility = View.GONE
                binding.forwardBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun switchLastSettlementBackward() {
        if(currentProductIndex == 0){
            showToast("First page has reached", Toast.LENGTH_SHORT)
            return
        }

        lifecycleScope.launch {
            currentProductIndex--
            switchLastSettlementCoroutine()
        }
    }

    private fun switchLastSettlementForward() {
        if(currentProductIndex + 1 == lastSettlementList.size){
            showToast( "Last page has reached", Toast.LENGTH_SHORT)
            return
        }

        lifecycleScope.launch {
            currentProductIndex++
            switchLastSettlementCoroutine()
        }
    }

    private fun customOnBackPress() {
        // D8 -- gate BEFORE clearing appIntent/appHTTP. A settlement issues several host calls and
        // the responses land in the global TransData, so leaving mid-flight abandons a batch the
        // host may already have closed -- and wiping the transport flags on the way out would strand
        // the caller that asked for the settlement. The non-cancelable progress dialog normally
        // swallows the press first; this makes the requirement this screen's own.
        if (IsoActivity.isHostRequestInFlight) {
            if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
                helperLog.appendLine(helperLogClassName, "IGNORE OnBack Press :: host request in flight, cannot leave settlement")
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }
            showToast("Processing, please wait", Toast.LENGTH_SHORT)
            return
        }

        appIntent = false
        appHTTP = false
        if (isPreviewSettlement && !isFromHistory) {
            navigateSafe(R.id.action_settlePreview_to_settleOption)
        } else {
            findNavController().popBackStack()
        }
    }
}
