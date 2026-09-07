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
import com.google.gson.JsonObject
import com.library.terminal.Utility
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentReceiptBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.activity.Utils.TextItem
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelQrPayBrandGet
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.appHTTP
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.appIntent
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getInternalFilesPaths
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SettlementQrPreviewFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    private var qrPayBrand = arrayOf<String?>()
    lateinit var qrPayBrandV2: List<DbModelQrPayBrandGet>
    private var qrPayTotalTxnAmt = arrayOf<String?>()
    private var qrPayTotalTxnCount = arrayOf<String?>()
    private var qrPayTotalVoidTxnAmt = arrayOf<String?>()
    private var qrPayTotalVoidTxnCount = arrayOf<String?>()
    private var settleInfo = arrayOf<String?>()
    lateinit var mainReceipt: LinearLayout
    private var isLastSettlement = false
    private var isPreviewSettlement = false
    // Entered from Ewallet History: hide the card-preview option and pop back to history on exit
    // (instead of navigating to SettleOption like the pre-settle preview flow does).
    private var isFromHistory = false

    private var SaleA: Long = 0
    private var SaleC: Long = 0
    private var VoidA: Long = 0
    private var VoidC: Long = 0
    var dbModelMerchantConfig: DbModelMerchantConfig? = null

    var txnDt = ""
    var merchantCode = ""
//    var rrn = ""
//    var stan = ""
//    var mid = ""
//    var tid = ""
//    var batchNo = ""

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
            helperLog.appendLine(helperLogClassName, "SettlementQrPreview OnDestroyView :: screen ended")
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
            "QR Settlement Preview Fragment"
        )
        helperLog.appendLine(helperLogClassName, "Initialize QR Settlement Preview Fragment")

        // Custom back press handling
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )

        mainReceipt = binding.mainReceipt
        qrPayBrandV2 = TransactionQrRepo.getDistinctProduct(requireContext())

        isLastSettlement = (arguments?.getInt("lastSettlement") ?: 0) != 0
        isPreviewSettlement = (arguments?.getInt("previewSettlement") ?: 0) == 1
        isFromHistory = (arguments?.getInt("fromHistory") ?: 0) != 0
        if (!isLastSettlement && !isFromHistory){
            //Only show for previewQR, not last Settlement & from History
            val cardPreviewButton = binding.previousBtn
            cardPreviewButton.visibility = View.VISIBLE
            cardPreviewButton.setOnClickListener {
                navigateSafe(R.id.action_settleQrPreview_to_settlePreview, androidx.core.os.bundleOf("previewSettlement" to 1))
            }
        }

        //Button Listener
        binding.printBtn.setOnClickListener {printBtn()}
        binding.cancelBtn.setOnClickListener {customOnBackPress()}

        lifecycleScope.launch {
            getRecordCoroutine()
        }
    }

    private suspend fun getRecordCoroutine() {
        helperLog.appendLine(helperLogClassName, "Get QR Settlement Record -----start-----")
        var settlementInfo: Array<String?> = arrayOf()
        var totalSettlementInfoQr: Array<String?>

        val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
            dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
            if (isLastSettlement) {
                txnDt = ""
                try {
                    qrPayBrandV2 = listOf()
                    settleInfo = Utils.readFromFile("qrlastsettlement.txt")
                    println("settleInfo :: ${settleInfo.joinToString()}")
                    settlementInfo = arrayOfNulls<String>(3)
                    totalSettlementInfoQr = arrayOfNulls<String>(9)

                    System.arraycopy(settleInfo, 0, settlementInfo, 0, settlementInfo.size)
                    txnDt = settleInfo[0].toString()
                    merchantCode = settleInfo[1].toString()
                    val qrDetailsLen = (settleInfo.size - settlementInfo.size - totalSettlementInfoQr.size) / 5
                    if (qrDetailsLen > 0) {
                        System.arraycopy(settleInfo, 0, settlementInfo, 0, settlementInfo.size)
                    } else {
                        System.arraycopy(settleInfo, 0, settlementInfo, 0, settleInfo.size)
                    }
                    val payBrandMutableList = mutableListOf<DbModelQrPayBrandGet>()
                    qrPayBrand = arrayOfNulls(qrDetailsLen)
                    qrPayTotalTxnAmt = arrayOfNulls(qrDetailsLen)
                    qrPayTotalTxnCount = arrayOfNulls(qrDetailsLen)
                    qrPayTotalVoidTxnAmt = arrayOfNulls(qrDetailsLen)
                    qrPayTotalVoidTxnCount = arrayOfNulls(qrDetailsLen)
                    var count = 0
                    var tCount = 0
                    var totalSettleStart = 0

                    for (s in 0 until (settleInfo.size - totalSettlementInfoQr.size)) {
                        when (count) {
                            //3 -> qrPayBrand[tCount] = s
                            3 -> payBrandMutableList.add(DbModelQrPayBrandGet("", settleInfo[s].toString(), settleInfo[s].toString()))
                            4 -> qrPayTotalTxnCount[tCount] = settleInfo[s]
                            5 -> qrPayTotalTxnAmt[tCount] = settleInfo[s]
                            6 -> qrPayTotalVoidTxnCount[tCount] = settleInfo[s]
                            7 -> qrPayTotalVoidTxnAmt[tCount] = settleInfo[s]
                        }
                        totalSettleStart++
                        count++
                        if (count > 7) {
                            count = 3
                            tCount++
                        }
                    }
                    qrPayBrandV2 = payBrandMutableList.toList()

                    for ((totalSettleCount, s) in (totalSettleStart until settleInfo.size).withIndex()){
                        when (totalSettleCount) {
                            3 -> SaleC = settleInfo[s]?.toLong() ?: 0
                            4 -> SaleA = (settleInfo[s]?.toBigDecimalOrNull()?.multiply(BigDecimal(100)))?.toLong() ?: 0
                            5 -> VoidC = settleInfo[s]?.toLong() ?: 0
                            6 -> VoidA = (settleInfo[s]?.toBigDecimalOrNull()?.multiply(BigDecimal(100)))?.toLong() ?: 0
                        }
                    }
                } catch (e: Exception) {
                    showToast("QR Last Settlement Not Found.", Toast.LENGTH_SHORT)
                }
            } else {
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
                settlementInfo = arrayOf(txnDt, dbModelMerchantConfig?.QrMid ?: "", "")
            }
            helperLog.appendLine(helperLogClassName, "Finish Obtain Data.")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }

        val loadingTitle = "Preview Summary"
        showProgress(loadingTitle, "Loading...")
        startCoroutine.join()
        formLayout(mainReceipt, settlementInfo)
        hideProgress()
    }

    private fun formLayout(mainReceipt: LinearLayout, value: Array<String?>) {
        mainReceipt.removeAllViews()
        val formTitle = if (isLastSettlement) "SETTLEMENT REPORT" else "SALES SUMMARY"

        val tags = arrayOf("Date/Time", "MERCHANT CODE", formTitle)
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

        for (j in tags.indices) {
            if (j == 2) {
                val ll = LinearLayout(requireContext())
                ll.orientation = LinearLayout.HORIZONTAL
                val params1 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params1.setMargins(5, 0, 5, 0)
                val tv = TextView(requireContext())
                tv.layoutParams = params1
                tv.textSize = 14f
                tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                tv.setTypeface(tv.typeface, Typeface.BOLD)
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                tv.text = tags[j]
                ll.addView(tv)
                mainReceipt.addView(ll)
            } else {
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
                tv.text = tags[j]
                val tv1 = TextView(requireContext())
                tv1.layoutParams = params1
                tv1.textSize = 11f
                tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                tv1.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                tv1.text = value[j]
                ll.addView(tv)
                ll.addView(tv1)
                mainReceipt.addView(ll)
            }
        }

        val tags2 = arrayOf("SCHEME", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL")
        settleInfo = arrayOfNulls(value.size + qrPayBrandV2.size * tags2.size)
        System.arraycopy(value, 0, settleInfo, 0, value.size)
        var count = value.size
        qrBrand@ for (j in qrPayBrandV2.indices) {
            val modelData = qrPayBrandV2[j]

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
                        res = if (isLastSettlement) { qrPayTotalTxnAmt[j] }
                        else Utils.getActualAmount(qrPayTotalTxnAmt[j])
                    }
                    3 -> res = qrPayTotalVoidTxnCount[j]
                    4 -> {
                        if (qrPayTotalVoidTxnAmt[j] == null) {
                            qrPayTotalVoidTxnAmt[j] = "000"
                        }
                        res = if (isLastSettlement) { qrPayTotalVoidTxnAmt[j] }
                        else Utils.getActualAmount(qrPayTotalVoidTxnAmt[j])
                    }
                }
                if (res == null) {
                    res = "0"
                }
                settleInfo[count] = res
                count++

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
                tv.text = tags2[i]
                val tv1 = TextView(requireContext())
                tv1.layoutParams = params1
                tv1.textSize = 11f
                tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                tv1.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                tv1.text = res
                ll.addView(tv)
                ll.addView(tv1)
                mainReceipt.addView(ll)
            }

            val ll = LinearLayout(requireContext())
            val params1 = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val tv = TextView(requireContext())
            tv.layoutParams = params1
            tv.textSize = 11f
            tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
            tv.text = ""
            ll.addView(tv)
            mainReceipt.addView(ll)
        }
        settleInfo = settleInfo.filterNotNull().toTypedArray()


        val tags3 = arrayOf("", "", "TOTAL SETTLEMENT", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL", "", "")
        for (j in tags3.indices) {
            when (j){
                0, 8 -> {
                    val ll = LinearLayout(requireContext())
                    ll.orientation = LinearLayout.HORIZONTAL
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Helper.getInstance().getDpValue(3)
                    )
                    val viewContainer = View(requireContext())
                    viewContainer.layoutParams = params1
                    viewContainer.setBackgroundResource(R.drawable.dotted_line)
                    viewContainer.setLayerType(Layer.LAYER_TYPE_SOFTWARE, null)
                    ll.addView(viewContainer)
                    mainReceipt.addView(ll)
                }
                1, 7 -> {
                    val ll = LinearLayout(requireContext())
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    val tv = TextView(requireContext())
                    tv.layoutParams = params1
                    tv.textSize = 11f
                    tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    tv.text = ""
                    ll.addView(tv)
                    mainReceipt.addView(ll)
                }
                2 -> {
                    val ll = LinearLayout(requireContext())
                    ll.orientation = LinearLayout.HORIZONTAL
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params1.setMargins(5, 0, 5, 0)
                    val tv = TextView(requireContext())
                    tv.layoutParams = params1
                    tv.textSize = 14f
                    tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    tv.setTypeface(tv.typeface, Typeface.BOLD)
                    tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                    tv.text = tags3[j]
                    ll.addView(tv)
                    mainReceipt.addView(ll)
                }
                else -> {
                    val settleData: String? = when (j) {
                        3 -> SaleC.toString()
                        4 -> Utils.getActualAmount(SaleA.toString())
                        5 -> VoidC.toString()
                        6 -> Utils.getActualAmount(VoidA.toString())
                        else -> ""
                    }

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
                    tv.text = tags3[j]
                    val tv1 = TextView(requireContext())
                    tv1.layoutParams = params1
                    tv1.textSize = 11f
                    tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    tv1.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                    tv1.text = settleData
                    ll.addView(tv)
                    ll.addView(tv1)
                    mainReceipt.addView(ll)
                }
            }
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

    private fun getTxnTotalCount(productCode: String, txnType: String): Int {
        val iTotalCount = TransactionQrRepo.countTransactionCountByType(requireContext(), productCode, txnType)
        return if (iTotalCount <= 0) 0 else iTotalCount
    }

    private fun getTxnTotalAmount(productCode: String, txnType: String): String {
        val strTotalTxnAmt = TransactionQrRepo.countTransactionAmountByType(requireContext(), productCode, txnType)
        return if (strTotalTxnAmt <= 0) "000" else strTotalTxnAmt.toString()
    }

    private fun constructPrintRecord(): MutableList<Pair<String, String>>{
        Thread.sleep(200)
        val timeStamp = if (isLastSettlement) txnDt else SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH).format(Calendar.getInstance().time)
        val mid = if (isLastSettlement) merchantCode else dbModelMerchantConfig?.QrMid

        val headerText = if (isLastSettlement) "SETTLEMENT REPORT" else "SALES SUMMARY"
        val toPrintInfo = mutableListOf(
            "Date/Time" to timeStamp,
            "MERCHANT CODE" to mid,
            "HEADER" to headerText,
        )

        val qrSettlementType = arrayOf("SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL")

        qrBrand@ for (j in qrPayBrandV2.indices) {
            val modelData = qrPayBrandV2[j]

            if (qrPayTotalTxnCount[j] == "0" && qrPayTotalVoidTxnCount[j] == "0") {
                continue@qrBrand
            }

            toPrintInfo.add("SCHEME" to modelData.productCode)
            for(qrSettlementKey in qrSettlementType) {
                when(qrSettlementKey) {
                    "SALE COUNT" -> {
                        var summaryValue = qrPayTotalTxnCount[j] ?: "0"
                        toPrintInfo.add(qrSettlementKey to summaryValue)
                    }
                    "SALE TOTAL" -> {
                        var summaryValue = qrPayTotalTxnAmt[j] ?: "0"
                        if (!summaryValue.contains(".")) {
                            summaryValue = Utils.getActualAmount(summaryValue)
                        }
                        toPrintInfo.add(qrSettlementKey to summaryValue)
                    }
                    "VOID COUNT" -> {
                        var summaryValue = qrPayTotalVoidTxnCount[j] ?: "0"
                        toPrintInfo.add(qrSettlementKey to summaryValue)
                    }
                    "VOID TOTAL" -> {
                        var summaryValue = qrPayTotalVoidTxnAmt[j] ?: "0"
                        if (!summaryValue.contains(".")) {
                            summaryValue = Utils.getActualAmount(summaryValue)
                        }
                        toPrintInfo.add(qrSettlementKey to summaryValue)
                    }
                }
            }
            toPrintInfo.add("br" to "")
        }
        toPrintInfo.add("hr" to "")
        toPrintInfo.add("br" to "")
        toPrintInfo.add("HEADER" to "TOTAL SETTLEMENT")
        for(totalQrSettlementKey in qrSettlementType) {
            when(totalQrSettlementKey) {
                "SALE COUNT" -> {
                    toPrintInfo.add(totalQrSettlementKey to SaleC.toString())
                }
                "SALE TOTAL" -> {
                    toPrintInfo.add(totalQrSettlementKey to Utils.getActualAmount(SaleA.toString()))
                }
                "VOID COUNT" -> {
                    toPrintInfo.add(totalQrSettlementKey to VoidC.toString())
                }
                "VOID TOTAL" -> {
                    toPrintInfo.add(totalQrSettlementKey to Utils.getActualAmount(VoidA.toString()))
                }
            }
        }
        toPrintInfo.add("br" to "")
        toPrintInfo.add("hr" to "")
        return toPrintInfo
    }


    private fun printInfo(toPrintData: MutableList<Pair<String, String>>) {
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


    private fun printBtn() {
        val animSlideDown = AnimationUtils.loadAnimation(requireContext(), R.anim.receipt_slide_up)
        val scrollReceipt : ScrollView = binding.receipt
        scrollReceipt.startAnimation(animSlideDown)
        val toPrintInfo = constructPrintRecord()
        printInfo(toPrintInfo)
    }

    private fun customOnBackPress() {
        appIntent = false
        appHTTP = false
        if (isPreviewSettlement && !isFromHistory) {
            navigateSafe(R.id.action_settleQrPreview_to_settleOption)
        } else {
            findNavController().popBackStack()
        }
    }
}