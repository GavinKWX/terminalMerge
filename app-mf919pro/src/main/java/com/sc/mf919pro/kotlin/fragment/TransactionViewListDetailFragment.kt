package com.sc.mf919pro.kotlin.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.gson.Gson
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentTransactionViewListDetailBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919pro.kotlin.data_enum.AcquirerSettingModel
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.PrintReceiptRepo
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tms.models.AcquirerLogoReplaceObject
import java.io.File
import java.util.Locale

class TransactionViewListDetailFragment: BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    lateinit var mainReceipt: LinearLayout
    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    lateinit var acqSetting: AcquirerSettingModel
    lateinit var acqLogoEnumModel: AcquirerLogoEnumModel
    lateinit var acquirerLogoConfig: AcquirerLogoReplaceObject
    var txnList: List<DbModelPrintReceipt> = listOf()

    //var txnDt = ""
    //var rrn = ""
    //var stan = ""
    var mid = ""
    var tid = ""
    var batchNo = ""
    var currAppLabel = ""
    var schemeIds = ""

    private var _binding: FragmentTransactionViewListDetailBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTransactionViewListDetailBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "TransactionViewListDetail OnDestroyView :: screen ended")
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
            "Transaction View List Detail Fragment",
        )
        helperLog.appendLine(helperLogClassName, "Initialize Transaction View List Detail Fragment")
        binding.appToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )
        //buttonGroup
        binding.cancelBtn.setDebouncedOnClickListener {
            customOnBackPress()
        }
        binding.printBtn.setDebouncedOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val animSlideUp = AnimationUtils.loadAnimation(requireContext(), R.anim.receipt_slide_up)
                binding.scrollReceipt.apply {
                    startAnimation(animSlideUp)
                }
                printInfo()
            }
        }

        acqSetting = ServiceHolder.getAcquirerSetting()
        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        acqLogoEnumModel = AcquirerLogoEnumModel(R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, resources.getString(R.string.app_name_about), resources.getString(R.string.app_name_about))
        acquirerLogoConfig = AcquirerLogoReplaceObject(IS_REPLACE_ACQ_LOGO = false, IS_SHOW_BOTTOM_ACQ_LOGO = false, "", "")
        if (dbModelMerchantConfig != null) {
            try {
                acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(dbModelMerchantConfig?.AcqCode ?: "").data
            } catch (e: Exception) {
                e.printStackTrace()
                helperLog.appendLine(helperLogClassName, "Exception in Transaction History Get Acquirer Enum :: ", e.toString())
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }
            Gson().fromJson(DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java) ?.let {
                acquirerLogoConfig = it
            }
        }

        mainReceipt = binding.mainLayout
        mid = dbModelMerchantConfig?.AcqMid ?: ""
        tid = dbModelMerchantConfig?.AcqTid ?: ""
        if(dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "true") {
            mid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
            tid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")
        }
        if (acqSetting.acqName.equals("GOBIZ", ignoreCase = true)) {
            tid = Utils.maskString(tid, 4)
            mid = Utils.maskString(mid, 4)
        }

        IsoBatchInfoRepo.getBatchInfo(requireContext(), "batchNo", "visam")?.let {
            batchNo = it.value
        }

        viewLifecycleOwner.lifecycleScope.launch {
            initializeData()
            schemeSelectionDialog()
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    private fun initializeData() {
        schemeIds = ""
        currAppLabel = ""
    }

    private fun schemeSelectionDialog() {
        val alertDialogBuilder = AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
        val inflater = this.layoutInflater
        @SuppressLint("InflateParams") val dialogView = inflater.inflate(
            R.layout.activity_schemeselection, null
        )

        dialogView.findViewById<LinearLayout>(R.id.cancel_btn).apply {
            setOnClickListener {
                alertDialog?.dismiss()
                customOnBackPress()
            }
        }

        dialogView.findViewById<View>(R.id.all_scheme).apply {
            setOnClickListener {
                // ALL
                schemeIds = ""
                currAppLabel = ""
                alertDialog?.dismiss()
                lifecycleScope.launch {
                    getTrxDetailsList()
                }
            }
        }

        dialogView.findViewById<View>(R.id.visa_scheme).apply {
            setOnClickListener {
                // VISA
                schemeIds = "11, 12, 91"
                currAppLabel = "VISA"
                alertDialog?.dismiss()
                lifecycleScope.launch {
                    getTrxDetailsList()
                }
            }
        }

        dialogView.findViewById<View>(R.id.master_scheme).apply {
            setOnClickListener {
                // MASTER
                schemeIds = "20, 21, 22, 92"
                currAppLabel = "MASTER"
                alertDialog?.dismiss()
                lifecycleScope.launch {
                    getTrxDetailsList()
                }
            }
        }

        val terminalConfig = ServiceHolder.getTerminalConfig()
        val optIn = DbModelTerminalConfig.getBooleanValue(terminalConfig, "OptIn")
        if(optIn) {
            dialogView.findViewById<View>(R.id.mydebit_scheme).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    // MYDEBIT
                    schemeIds = "81, 98, 82, 99"
                    currAppLabel = "MyDebit"
                    alertDialog?.dismiss()
                    lifecycleScope.launch {
                        getTrxDetailsList()
                    }
                }
            }
        }

        if(acqSetting.acqName.uppercase(Locale.ENGLISH).equals("GOBIZ", true)) {
            dialogView.findViewById<View>(R.id.union_scheme).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    // UNIONPAY
                    schemeIds = "31, 93"
                    currAppLabel = "UnionPay"
                    alertDialog?.dismiss()
                    lifecycleScope.launch {
                        getTrxDetailsList()
                    }
                }
            }
        }

        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    private suspend fun getTrxDetailsList() {
        showProgress("Finding the Transaction", "Searching...")
        try {
            txnList = withContext(Dispatchers.IO) {
                PrintReceiptRepo.getMultipleSuccessRecordBySchemeIds(requireContext(), schemeIds)
            }
            formLayout()
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            delay(100L)
            hideProgress()
        }
    }

    private fun  formLayout() {
        mainReceipt.removeAllViews()
        mainReceipt.orientation = LinearLayout.VERTICAL

        var powerByBmp: Bitmap? = null
        val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
            helperLog.appendLine(helperLogClassName, "PowerBy Logo Replace from internal files")
        }

        val headerImageView = ImageView(requireContext())
        val headerImgParam = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 51f, resources.displayMetrics).toInt()
        )
        headerImageView.layoutParams = headerImgParam
        headerImageView.setPadding(0,10,0,10)
        if(powerByBmp != null && (acquirerLogoConfig.IS_REPLACE_ACQ_LOGO == true || dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "true")) {
            headerImageView.setImageBitmap(powerByBmp)
        } else {
            headerImageView.setImageResource(acqLogoEnumModel.HeaderLogoPng)
        }
        mainReceipt.addView(headerImageView)

        val name = TextView(requireContext())
        name.textSize = 9f
        name.textAlignment = View.TEXT_ALIGNMENT_CENTER
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        name.text = dbModelMerchantConfig?.MerchantName ?: ""

        val addr = TextView(requireContext())
        addr.textSize = 9f
        addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
        addr.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        addr.text = dbModelMerchantConfig?.MerchantAddress ?: ""
        mainReceipt.addView(name)
        mainReceipt.addView(addr)

        val headerList: MutableList<Pair<String, String>> = mutableListOf(
            "MERCHANT ID" to mid,
            "TERMINAL ID" to tid,
            "BATCH NO" to batchNo,
        )
        if(currAppLabel.trim().isNotEmpty()){
            headerList.add("SCHEME" to currAppLabel)
        }

        val mainLLTopLabel = LinearLayout(requireContext())
        mainLLTopLabel.orientation = LinearLayout.VERTICAL
        val mainLTopLabelParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        mainLTopLabelParams.setMargins(5,5,5,15)

        headerList.forEach { (key, keyValue) ->
            val ll = LinearLayout(requireContext())
            ll.orientation = LinearLayout.HORIZONTAL
            ll.weightSum = 2f
            val params1 = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params1.setMargins(5, 0, 5, 0)
            params1.weight = 1f
            val tv = TextView(requireContext())
            tv.layoutParams = params1
            tv.textSize = 11f
            tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            tv.text = key
            val tv1 = TextView(requireContext())
            tv1.layoutParams = params1
            tv1.textSize = 11f
            tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            tv1.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            tv1.text = keyValue
            ll.addView(tv)
            ll.addView(tv1)
            mainLLTopLabel.addView(ll)
        }
        mainReceipt.addView(mainLLTopLabel, mainLTopLabelParams)

        val receiptTitle = TextView(requireContext())
        receiptTitle.textSize = 14f
        receiptTitle.textAlignment = View.TEXT_ALIGNMENT_CENTER
        receiptTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        receiptTitle.setTypeface(receiptTitle.typeface, Typeface.BOLD_ITALIC)
        receiptTitle.text = "Transaction Details"
        receiptTitle.setPadding(5,10,5,10)
        mainReceipt.addView(receiptTitle)

        if (currAppLabel.trim().isEmpty()) {
            val appLabel = TextView(requireContext())
            appLabel.textSize = 11f
            appLabel.textAlignment = View.TEXT_ALIGNMENT_CENTER
            appLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            appLabel.text = "SCHEME"
            mainReceipt.addView(appLabel)
        }

        val labelList: MutableList<Pair<String, String>> = mutableListOf(
            "INV NO" to "DATE/TIME",
            "TYPE" to "APPROVAL CODE",
            "CARD NO" to "TOTAL",
        )
        val mainLLLabel = LinearLayout(requireContext())
        mainLLLabel.orientation = LinearLayout.VERTICAL
        val mainLLabelParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        mainLLabelParams.setMargins(5,5,5,15)
        labelList.forEach{(labelKey, labelValue) ->
            val ll = LinearLayout(requireContext())
            ll.orientation = LinearLayout.HORIZONTAL
            ll.weightSum = 2f
            val params1 = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params1.setMargins(5, 0, 5, 0)
            params1.weight = 1f
            val tv = TextView(requireContext())
            tv.layoutParams = params1
            tv.textSize = 11f
            tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            tv.text = labelKey
            val tv1 = TextView(requireContext())
            tv1.layoutParams = params1
            tv1.textSize = 11f
            tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            tv1.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            tv1.text = labelValue
            ll.addView(tv)
            ll.addView(tv1)
            mainLLLabel.addView(ll)
        }
        mainReceipt.addView(mainLLLabel, mainLLabelParams)

        for(printReceiptItem in txnList) {
            if (currAppLabel.trim().isEmpty()) {
                val appLabel = TextView(requireContext())
                appLabel.textSize = 11f
                appLabel.textAlignment = View.TEXT_ALIGNMENT_CENTER
                appLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                appLabel.text = Utils.getSchemeName(printReceiptItem.schemeId)
                appLabel.setPadding(0, 5, 0, 0)
                mainReceipt.addView(appLabel)
            }

            val txnDetails: MutableList<Pair<String, String>> = mutableListOf(
                printReceiptItem.invoiceNo to Utils.DateTimeFormat(printReceiptItem.txnDt),
                printReceiptItem.txnType to printReceiptItem.txnApprCode,
                printReceiptItem.cardMasked to Utils.getActualAmount(printReceiptItem.txnAmt),
            )
            val mainLLTxnList = LinearLayout(requireContext())
            mainLLTxnList.orientation = LinearLayout.VERTICAL
            val mainLTxnListParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            mainLTxnListParams.setMargins(5,5,5,5)

            txnDetails.forEach{(labelKey, labelValue) ->
                val ll = LinearLayout(requireContext())
                ll.orientation = LinearLayout.HORIZONTAL
                ll.weightSum = 2f
                val params1 = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                params1.setMargins(5, 0, 5, 0)
                params1.weight = 1f
                val tv = TextView(requireContext())
                tv.layoutParams = params1
                tv.textSize = 11f
                tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                tv.text = labelKey
                val tv1 = TextView(requireContext())
                tv1.layoutParams = params1
                tv1.textSize = 11f
                tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                tv1.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                tv1.text = labelValue
                ll.addView(tv)
                ll.addView(tv1)
                mainLLTxnList.addView(ll)
            }
            mainReceipt.addView(mainLLTxnList, mainLTxnListParams)
        }

        val footer = LinearLayout(requireContext())
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 5, 0, 0)
        params.gravity = Gravity.CENTER
        footer.orientation = LinearLayout.VERTICAL
        footer.layoutParams = params
        footer.setPadding(0, 0, 0, 20)
        val power = TextView(requireContext())
        power.textSize = 9f
        power.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
        power.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        //power.text = "POWERED BY"
        //footer.addView(power)
        val im = ImageView(requireContext())
        val params1 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, Helper().getDpValue(30)
        )
        im.layoutParams = params1
        im.adjustViewBounds = true

        if(dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "false") {
            if(acquirerLogoConfig.IS_REPLACE_ACQ_LOGO == true) {
                //if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
                if (acquirerLogoConfig.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
                    power.text = "PARTNER WITH"
                    footer.addView(power)
                    im.setImageResource(acqLogoEnumModel.HeaderLogoPng)
                    footer.addView(im)
                }
            } else {
                power.text = "POWERED BY"
                footer.addView(power)
                if(powerByBmp != null) {
                    im.setImageBitmap(powerByBmp)
                    helperLog.appendLine(helperLogClassName, "PowerBy Logo Replace from internal files")
                }else {
                    im.setImageResource(R.mipmap.logo)
                }
                footer.addView(im)
            }
        }
        mainReceipt.addView(footer)
    }

    private fun printInfo() {
        val list: MutableList<MulPrintStrEntity> = ArrayList()
        val fontSize = FontFamily.MIDDLE
        val acquirerBmp = getImageFromAssetsFile(requireContext(), acqLogoEnumModel.HeaderLogoBmp)
        var powerByBmp = getImageFromAssetsFile(requireContext(), "image/logo_footer_small.bmp")
        val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
        }

        val labelList: MutableList<Pair<String, String>> = mutableListOf(
            "INV NO" to "DATE/TIME",
            "TYPE" to "APPROVAL CODE",
            "CARD NO" to "TOTAL",
        )
        var entity = MulPrintStrEntity("", fontSize)
        entity.bitmap = if(acquirerLogoConfig.IS_REPLACE_ACQ_LOGO == true || dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "true") powerByBmp else acquirerBmp
        entity.marginX = 50
        entity.gravity = Gravity.CENTER
        entity.isUnderline = true
        entity.yspace = 30
        list.add(entity)

        receiptMerchantDetailsModifier(list)

        list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

        val headerList: MutableList<Pair<String, String>> = mutableListOf(
            "MERCHANT ID" to mid,
            "TERMINAL ID" to tid,
            "BATCH NO" to batchNo,
        )
        if(currAppLabel.trim().isNotEmpty()){
            headerList.add("SCHEME" to currAppLabel)
        }
        headerList.forEach { (key, keyValue) ->
            list += MulPrintStrEntity(
                Utils.makeLineText(
                    Utils.TextItem(key).setFont(fontSize),
                    Utils.TextItem(keyValue).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        }
        list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

        val mulPrintStrEntity = MulPrintStrEntity("Transaction Details", FontFamily.BIG, false, Gravity.CENTER)
        list.add(mulPrintStrEntity)
        list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

        if (currAppLabel == "" || currAppLabel.isNullOrEmpty()) {
            list.add(MulPrintStrEntity("SCHEME", FontFamily.MIDDLE, false, Gravity.CENTER))
        }
        labelList.forEach { (labelKey, labelValue) ->
            list += MulPrintStrEntity(
                Utils.makeLineText(
                    Utils.TextItem(labelKey).setFont(fontSize),
                    Utils.TextItem(labelValue).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        }
        list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

        for(printReceiptItem in txnList){
            if(currAppLabel.trim().isEmpty()) {
                list.add(MulPrintStrEntity(Utils.getSchemeName(printReceiptItem.schemeId), FontFamily.MIDDLE, false, Gravity.CENTER))
            }

            val txnDetails: MutableList<Pair<String, String>> = mutableListOf(
                printReceiptItem.invoiceNo to Utils.DateTimeFormat(printReceiptItem.txnDt),
                printReceiptItem.txnType to printReceiptItem.txnApprCode,
                printReceiptItem.cardMasked to Utils.getActualAmount(printReceiptItem.txnAmt),
            )
            txnDetails.forEach { (detailKey, detailvalue) ->
                list += MulPrintStrEntity(
                    Utils.makeLineText(
                        Utils.TextItem(detailKey.toString()).setFont(fontSize),
                        Utils.TextItem(detailvalue.toString()).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            }
            list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))
        }

        if(dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "false") {
            if (acquirerLogoConfig.IS_REPLACE_ACQ_LOGO == true) {
                if (acquirerLogoConfig.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
                    list.add(MulPrintStrEntity("PARTNER WITH", FontFamily.SMALL, false, Gravity.CENTER))
                    entity = MulPrintStrEntity("", fontSize)
                    entity.bitmap = acquirerBmp
                    entity.marginX = 50
                    entity.gravity = Gravity.CENTER
                    entity.isUnderline = true
                    entity.yspace = 30
                    list.add(entity)
                }
            } else {
                list.add(MulPrintStrEntity("POWERED BY", FontFamily.SMALL, false, Gravity.CENTER))
                entity = MulPrintStrEntity("", fontSize)
                entity.bitmap = powerByBmp
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

    private fun customOnBackPress() {
        //navigateSafe(R.id.action_settlePreview_to_settleOption)
        findNavController().popBackStack()
    }
}