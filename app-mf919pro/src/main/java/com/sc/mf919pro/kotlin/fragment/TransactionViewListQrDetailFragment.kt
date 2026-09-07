package com.sc.mf919pro.kotlin.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTransactionQrGet
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumCustomizationAcq
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.launch
import tms.models.EWProducts
import java.io.File

class TransactionViewListQrDetailFragment: BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    lateinit var mainReceipt: LinearLayout
    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    //lateinit var acqLogoEnumModel: AcquirerLogoEnumModel
    //lateinit var acquirerLogoConfig: AcquirerLogoReplaceObject
    lateinit var txnList: List<DbModelTransactionQrGet>

    var txnDt = ""
    var rrn = ""
    var stan = ""
    var mid = ""
    var tid = ""
    var batchNo = ""
    var isRHB = false

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
            helperLog.appendLine(helperLogClassName, "TransactionViewListQrDetail OnDestroyView :: screen ended")
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
            "Transaction View QR List Detail Fragment",
        )
        helperLog.appendLine(helperLogClassName, "Initialize Transaction View QR List Detail Fragment")
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

        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        val customizationList = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "Customization")
        if (customizationList.isNotEmpty()) {
            val customizationArray = Gson().fromJson(customizationList, Array<String>::class.java).asList()
            if (customizationArray.any { a -> a.contains(EnumCustomizationAcq.RHB.value) }) {
                isRHB = true
            }
        }

        mainReceipt = binding.mainLayout
        mid = dbModelMerchantConfig?.AcqMid ?: ""
        tid = dbModelMerchantConfig?.AcqTid ?: ""
        if(dbModelMerchantConfig?.IsTpaAccount?.lowercase() == "true") {
            mid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
            tid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            SelectionDialog_txnViewer()
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    private fun SelectionDialog_txnViewer() {
        val alertDialogBuilder = AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
        val inflater = this.layoutInflater
        @SuppressLint("InflateParams")
        val dialogView = inflater.inflate(R.layout.activity_dynamic_selection, null)

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val ewProductList = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "EWALLET_PRODUCT_LIST")

        dialogView.findViewById<LinearLayout>(R.id.cancel_btn).apply {
            setOnClickListener {
                alertDialog?.dismiss()
                customOnBackPress()
            }
        }

        val mainContainer = dialogView.findViewById<LinearLayout>(R.id.container_layout)
        val dynamicTitle = dialogView.findViewById<TextView>(R.id.dynamic_title)
        dynamicTitle.text = "SELECT PAYMENT METHOD"

        //KIV
        /*val childLinear = LinearLayout(requireContext())
        val params = LinearLayout.LayoutParams(
            Helper.getInstance().getDpValue(250), Helper.getInstance().getDpValue(67)
        )
        params.setMargins(0, 0, 0, Helper.getInstance().getDpValue(35))
        params.gravity = Gravity.CENTER_HORIZONTAL
        childLinear.gravity = Gravity.CENTER_VERTICAL
        childLinear.setBackgroundResource(R.drawable.other_btn)
        childLinear.orientation = LinearLayout.HORIZONTAL
        childLinear.layoutParams = params

        val tv = TextView(requireContext())
        tv.text = "ALL"
        tv.gravity = Gravity.CENTER_HORIZONTAL
        tv.setTextColor(resources.getColor(R.color.black))
        tv.textSize = 16f
        val paramsTV = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tv.layoutParams = paramsTV
        childLinear.addView(tv)

        childLinear.setOnClickListener {
            getTrxnDetailsList(null)
            alertDialog?.dismiss()
        }
        mainContainer.addView(childLinear)*/
        //KIV

        val scrollView = ScrollView(requireContext())
        val scrollViewParam = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        scrollView.layoutParams = scrollViewParam

        val mainLV = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(mainLV)

        val childLinear = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                Helper.getInstance().getDpValue(250),
                Helper.getInstance().getDpValue(67),
            ).apply {
                setMargins(0, 0, 0, Helper.getInstance().getDpValue(15))
                gravity = Gravity.CENTER
            }
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.other_btn)
            orientation = LinearLayout.HORIZONTAL
        }

        val tv = TextView(requireContext()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            textSize = 16f
            setTextColor(resources.getColor(R.color.black))
            text = "ALL"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        childLinear.addView(tv)
        childLinear.setOnClickListener {
            getTrxnDetailsList(null)
            alertDialog?.dismiss()
        }
        mainLV.addView(childLinear)

        if (!ewProductList.isNullOrEmpty()) {
            val ewArray = Gson().fromJson(ewProductList, Array<EWProducts>::class.java).asList()
                .sortedBy { x -> x.PRODUCT_SEQ }
            for (i in ewArray.indices) {
                val childLinear = LinearLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        Helper.getInstance().getDpValue(250),
                        Helper.getInstance().getDpValue(67),
                    ).apply {
                        setMargins(0, 0, 0, Helper.getInstance().getDpValue(15))
                        gravity = Gravity.CENTER
                    }
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundResource(R.drawable.other_btn)

                }

                val tv = TextView(requireContext()).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    textSize = 16f
                    setTextColor(resources.getColor(R.color.black))
                    text = ewArray[i].PRODUCT_CODE.uppercase()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                childLinear.addView(tv)
                childLinear.setOnClickListener {
                    getTrxnDetailsList(ewArray[i].PRODUCT_NAME)
                    alertDialog?.dismiss()
                }
                mainLV.addView(childLinear)
            }
        }
        mainContainer.addView(scrollView)

        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    private fun getTrxnDetailsList(productNames: List<String>?) {
        showProgress("Preview Details", "Loading...")
        txnList = TransactionQrRepo.getListTransactionQrNoCase(requireContext(), productNames)
        formLayout()
        hideProgress()
    }

    private fun formLayout() {
        mainReceipt.removeAllViews()

        mainReceipt.orientation = LinearLayout.VERTICAL

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

        val receiptTitle = TextView(requireContext())
        receiptTitle.textSize = 14f
        receiptTitle.textAlignment = View.TEXT_ALIGNMENT_CENTER
        receiptTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        receiptTitle.setTypeface(receiptTitle.typeface, Typeface.BOLD_ITALIC)
        receiptTitle.text = "Transaction Details"
        receiptTitle.setPadding(5,10,5,10)

        mainReceipt.addView(receiptTitle)

        var tagsLabel1 = arrayOf("REF ID", "TYPE", "E-WALLET")
        var tagsLabel2 = arrayOf("", "DATE/TIME", "AMOUNT")

        if (isRHB) {
            tagsLabel1 = arrayOf("REF ID", "TYPE", "E-WALLET", "HOST REF")
            tagsLabel2 = arrayOf("", "DATE/TIME", "AMOUNT", "")
        }

        val mainLLTopLabel = LinearLayout(requireContext())
        mainLLTopLabel.orientation = LinearLayout.VERTICAL
        val mainLTopLabelParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        mainLTopLabelParams.setMargins(5,5,5,15)

        for (j in tagsLabel1.indices) {
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
            tv.text = tagsLabel1[j]
            val tv1 = TextView(requireContext())
            tv1.layoutParams = params1
            tv1.textSize = 11f
            tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            tv1.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            tv1.text = tagsLabel2[j]
            ll.addView(tv)
            ll.addView(tv1)
            mainLLTopLabel.addView(ll)
        }
        mainReceipt.addView(mainLLTopLabel, mainLTopLabelParams)


        if (txnList.isNotEmpty()) {
            txnList.forEach{
                var dataVal1 = arrayOf(it.refId, it.txnType, it.productCode)
                var dataVal2 = arrayOf("", it.txnDateTime, "RM" + Utils.getActualAmount(it.txnAmount))

                if (isRHB) {
                    dataVal1 = arrayOf(it.refId, it.txnType, it.productCode, it.hostRefNo)
                    dataVal2 = arrayOf("", it.txnDateTime, "RM" + Utils.getActualAmount(it.txnAmount), "")
                }

                val mainLLTxnList = LinearLayout(requireContext())
                mainLLTxnList.orientation = LinearLayout.VERTICAL
                val mainLTxnListParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                mainLTxnListParams.setMargins(5,5,5,5)

                for (j in dataVal1.indices) {
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
                    tv.text = dataVal1[j]
                    val tv1 = TextView(requireContext())
                    tv1.layoutParams = params1
                    tv1.textSize = 11f
                    tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    tv1.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                    tv1.text = dataVal2[j]
                    ll.addView(tv)
                    ll.addView(tv1)
                    mainLLTxnList.addView(ll)
                }

                mainReceipt.addView(mainLLTxnList, mainLTxnListParams)
            }
            /*for (i in txnList.indices) {
                //var t = Gson().fromJson(txnList[i], DbModelQrPayTable::class.java)
                var dataVal1 = arrayOf(t.refId, t.txnType, t.productCode)
                var dataVal2 = arrayOf("", t.txnDt, "RM" + Utils.getActualAmount(t.txnAmt as String?))

                if (isRHB) {
                    dataVal1 = arrayOf(t.refId, t.txnType, t.productCode, t.hostRefNo)
                    dataVal2 = arrayOf("", t.txnDt, "RM" + Utils.getActualAmount(t.txnAmt as String?), "")
                }

                val mainLLTxnList = LinearLayout(requireContext())
                mainLLTxnList.orientation = LinearLayout.VERTICAL
                val mainLTxnListParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                mainLTxnListParams.setMargins(5,5,5,5)

                for (j in dataVal1.indices) {
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
                    tv.setTextColor(ContextCompat.getColor(this, R.color.black))
                    tv.text = dataVal1[j]
                    val tv1 = TextView(requireContext())
                    tv1.layoutParams = params1
                    tv1.textSize = 11f
                    tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    tv1.setTextColor(ContextCompat.getColor(this, R.color.black))
                    tv1.text = dataVal2[j]
                    ll.addView(tv)
                    ll.addView(tv1)
                    mainLLTxnList.addView(ll)
                }

                mainReceipt.addView(mainLLTxnList, mainLTxnListParams)
            }*/
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
        power.text = "POWERED BY"
        footer.addView(power)
        val im = ImageView(requireContext())
        val params1 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, Helper.getInstance().getDpValue(30)
        )
        im.layoutParams = params1
        im.adjustViewBounds = true
        im.setImageResource(R.mipmap.logo)
        val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            val myBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            im.setImageBitmap(myBitmap)
            Utils.debugLogPrint("TAG", "onCreate: image")
        }
        footer.addView(im)
        mainReceipt.addView(footer)
    }

    private fun printInfo() {
        val list: MutableList<MulPrintStrEntity> = ArrayList()
        val fontSize = FontFamily.MIDDLE

        var tagsLabel1 = arrayOf("REF ID", "TYPE", "E-WALLET")
        var tagsLabel2 = arrayOf("", "DATE/TIME", "AMOUNT")

        if (isRHB) {
            tagsLabel1 = arrayOf("REF ID", "TYPE", "E-WALLET", "HOST REF")
            tagsLabel2 = arrayOf("", "DATE/TIME", "AMOUNT", "")
        }

        var entity = MulPrintStrEntity("", fontSize)
        var imageFromAssetsFile = getImageFromAssetsFile(
            requireContext(), "image/logo_footer_small.bmp"
        )
        entity.marginX = 50
        entity.gravity = Gravity.CENTER
        entity.isUnderline = true
        entity.yspace = 30
        list.add(entity)

        receiptMerchantDetailsModifier(list)

        list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

        val mulPrintStrEntity = MulPrintStrEntity("Transaction Details", FontFamily.BIG, false, Gravity.CENTER)
        list.add(mulPrintStrEntity)

        list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))

        for (j in tagsLabel1.indices) {
            list += MulPrintStrEntity(
                Utils.makeLineText(
                    Utils.TextItem(tagsLabel1[j]).setFont(fontSize),
                    Utils.TextItem(tagsLabel2[j]).setFont(fontSize)
                        .setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        }

        list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))
        if(txnList.isNotEmpty()) {
            txnList.forEach{
                var dataVal1 = arrayOf(it.refId, it.txnType, it.productCode)
                var dataVal2 = arrayOf("", it.txnDateTime, "RM" + Utils.getActualAmount(it.txnAmount))

                if (isRHB) {
                    dataVal1 = arrayOf(it.refId, it.txnType, it.productCode, it.hostRefNo)
                    dataVal2 = arrayOf("", it.txnDateTime, "RM" + Utils.getActualAmount(it.txnAmount), "")
                }

                for (h in dataVal1.indices) {
                    list += MulPrintStrEntity(
                        Utils.makeLineText(
                            Utils.TextItem(dataVal1[h]).setFont(fontSize),
                            Utils.TextItem(dataVal2[h]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                }

                list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))
            }
        }

        //TODO
        /*if (txnList.isNotEmpty() && txnList != null) {
            for (j in txnList.indices) {
                var t = Gson().fromJson(txnList[j], DbModelQrPayTable::class.java)

                var dataVal1 = arrayOf(t.refId, t.txnType, t.productCode)
                var dataVal2 = arrayOf(
                    "", t.txnDt, "RM" + Utils.getActualAmount(t.txnAmt as String?)
                )

                if (isRHB) {
                    dataVal1 = arrayOf(t.refId, t.txnType, t.productCode, t.hostRefNo)
                    dataVal2 = arrayOf(
                        "", t.txnDt, "RM" + Utils.getActualAmount(t.txnAmt as String?), ""
                    )
                }

                for (h in dataVal1.indices) {
                    list += MulPrintStrEntity(
                        Utils.makeLineText(
                            Utils.TextItem(dataVal1[h] as String?).setFont(fontSize),
                            Utils.TextItem(dataVal2[h] as String?).setFont(fontSize)
                                .setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                }

                list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))
            }
        }*/

        list.add(MulPrintStrEntity("POWERED BY", FontFamily.SMALL, false, Gravity.CENTER))
        entity = MulPrintStrEntity("", fontSize)
        imageFromAssetsFile = getImageFromAssetsFile(requireContext(), "image/logo_footer_small.bmp")
        val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
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
        entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
        entity.gravity = Gravity.CENTER
        entity.yspace = 140
        list.add(entity)
        HelperCommon.sdkPrint(list)
    }

    private fun customOnBackPress() {
        findNavController().popBackStack()
    }
}