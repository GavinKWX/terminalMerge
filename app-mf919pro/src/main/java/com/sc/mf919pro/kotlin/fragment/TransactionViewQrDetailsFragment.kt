package com.sc.mf919pro.kotlin.fragment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
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
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919pro.kotlin.database.model.DbModelTransactionQrGet
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import helpers.HelperCommon
import enums.EnumLogFileName
import helpers.HelperLog
import tms.models.AcquirerLogoReplaceObject
import java.io.File

class TransactionViewQrDetailsFragment : BaseFragment(){
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    lateinit var receipt: ScrollView
    lateinit var mainReceipt: LinearLayout
    lateinit var headerImageView: ImageView
    lateinit var acqLogoEnumModel: AcquirerLogoEnumModel
    var acquirerLogoConfig: AcquirerLogoReplaceObject? = null

    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    private var refId: String = ""
    private var transactionQrGet: DbModelTransactionQrGet? = null
    var details: MutableMap<String, Any> = mutableMapOf()
    var isUPITxn: Boolean = false

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
            helperLog.appendLine(helperLogClassName, "TransactionViewQrDetails OnDestroyView :: screen ended")
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
            "TransactionViewQrDetailsActivity",
            helperLogClassName,
            helperLogClassName
        )

        receipt = binding.receipt
        mainReceipt = binding.mainReceipt
        headerImageView = binding.headerImageView
        binding.printTV.text = "Print Receipt"

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            }
        )

        // Button Listener
        binding.cancelBtn.setOnClickListener { customOnBackPress() }
        binding.printBtn.setOnClickListener { printInfo() }

        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        acqLogoEnumModel = AcquirerLogoEnumModel(R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, resources.getString(R.string.app_name_about), resources.getString(R.string.app_name_about))
        acquirerLogoConfig = Gson().fromJson(DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)

        refId = arguments?.getString("refId") ?: ""
        try{
            transactionQrGet = TransactionQrRepo.getSingleTransactionQr(requireContext(), listOf("refId"), listOf(refId))
            transactionQrGet?.let {
                try {
                    acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(it.acqCode ?: "").data
                } catch (e: Exception) {
                    e.printStackTrace()
                    /*log.appendLine(helperlogClassName, "Exception in Print Qr Result -> ", e.toString())
                    log.logToFile(EnumLogFileName.TerminaLogException)*/
                }
                val txnDateTime = if(it.txnType?.trim() == "Void") it.voidDateTime ?: "" else it.txnDateTime ?: ""
                details["DATE/TIME"] = txnDateTime
                details["E-WALLET"] = it.productName ?: ""
                details["TXN TYPE"] = it.txnType ?: ""
                //details["MID"] = it.mid ?: ""
                //details["TID"] = it.tid ?: ""
                //details["APPROVAL CODE"] = it.approvalCode ?: ""
                details["HOST REF"] = it.hostRefNo ?: ""
                if (it.isUnionPayTxn == "1") {
                    isUPITxn = true
                    details["REF ID"] = it.refId ?: ""
                    details["VC CODE"] = it.upiVoucherCode ?: ""
                    details["AMT (MYR)"] = "RM ${Utils.getActualAmount(it.txnAmount ?: "0")}"
                    details["DISCOUNT AMT (MYR)"] = "RM ${Utils.getActualAmount(it.upiDiscountAmt ?: "0")}"

                    var finalAmt = "0"
                    if (!it.upiDiscountAmt.isNullOrEmpty()) {
                        finalAmt = ((it.txnAmount?.toLong() ?: 0) - it.upiDiscountAmt.toLong()).toString()
                    }
                    details["TOTAL AMT (MYR)"] = "RM ${Utils.getActualAmount(finalAmt ?: "0")}"
                } else {
                    isUPITxn = false
                    details["AMOUNT"] = "RM ${Utils.getActualAmount(it.txnAmount ?: "0")}"
                    details["REF ID"] = it.refId ?: ""
                }

                formLayout()
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    private fun formLayout() {
        var powerByBmp: Bitmap? = null
        val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
        }

        if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || transactionQrGet?.isTpaAccount == "true") {
            /*val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
            val imgFile = File(paths)
            if (imgFile.exists()) {
                val myBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                headerImageView.setImageBitmap(myBitmap)
            }*/
            if(powerByBmp != null) {
                headerImageView.setImageBitmap(powerByBmp)
            } else {
                headerImageView.setImageResource(R.mipmap.logo)
            }
        } else {
            headerImageView.setImageResource(acqLogoEnumModel.HeaderLogoPng)
        }

        mainReceipt.removeAllViews()
        //val tags = arrayOf("DATE/TIME", "E-WALLET", "TXN TYPE", "MID", "TID", "APPROVAL CODE", "HOST REF", "AMOUNT", "REF ID")
        val name = TextView(requireContext())
        name.textSize = 9f
        name.textAlignment = View.TEXT_ALIGNMENT_CENTER
        name.setTextColor(resources.getColor(R.color.black))
        name.text = getSafeValue(dbModelMerchantConfig, "MerchantName")
        val addr = TextView(requireContext())
        addr.textSize = 9f
        addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
        addr.setTextColor(resources.getColor(R.color.black))
        addr.text = getSafeValue(dbModelMerchantConfig, "MerchantAddress")
        mainReceipt.addView(name)
        mainReceipt.addView(addr)

        details.forEach{(key, value) ->
            val keyValue = value.toString()
            if (keyValue.trim().isEmpty()) {
                return@forEach
            }

            val ll = LinearLayout(requireContext())
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            ll.orientation = LinearLayout.HORIZONTAL
            ll.weightSum = 2f
            val params1 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params1.setMargins(5, 0, 5, 0)
            params1.weight = 1f
            val tv = TextView(requireContext())
            tv.layoutParams = params1
            tv.textSize = 11f
            tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            tv.setTextColor(resources.getColor(R.color.black))
            tv.text = key
            val tv1 = TextView(requireContext())
            tv1.layoutParams = params1
            tv1.textSize = 11f
            tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            tv1.setTypeface(tv1.typeface, Typeface.BOLD)
            tv1.setTextColor(resources.getColor(R.color.black))
            tv1.text = keyValue
            ll.addView(tv)
            ll.addView(tv1)
            mainReceipt.addView(ll)

            if (isUPITxn && (key == "REF ID" || key == "TOTAL AMT (MYR)")) {
                val ll2 = LinearLayout(requireContext())
                val params2 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                ll2.orientation = LinearLayout.VERTICAL
                ll2.layoutParams = params2
                val tvDashed = TextView(requireContext())
                tvDashed.textSize = 11f
                tvDashed.layoutParams = params1
                tvDashed.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tvDashed.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                tvDashed.setSingleLine(true)
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val dashWidth = tvDashed.paint.measureText("-")
                val dashCount = (screenWidth / dashWidth).toInt()
                tvDashed.text = "-".repeat(dashCount)

                if (key == "REF ID" || key == "TOTAL AMT (MYR)") {
                    ll2.addView(tvDashed)
                    mainReceipt.addView(ll2)
                }
            }
        }

        val footer = LinearLayout(requireContext())
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, 5, 0, 0)
        params.gravity = Gravity.CENTER
        footer.orientation = LinearLayout.VERTICAL
        footer.layoutParams = params
        footer.setPadding(0, 0, 0, 20)
        val power1 = TextView(requireContext())
        power1.textSize = 7.5f
        power1.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power1.setTextColor(resources.getColor(R.color.black))
        power1.text = "I AGREE TO PAY THE ABOVE TOTAL AMOUNT"
        footer.addView(power1)

        val power = TextView(requireContext())
        power.textSize = 9f
        power.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
        power.setTextColor(resources.getColor(R.color.black))
        val im = ImageView(requireContext())
        val params1 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Helper.getInstance().getDpValue(30))
        params1.gravity = Gravity.CENTER
        im.layoutParams = params1
        im.adjustViewBounds = true

        if(transactionQrGet?.isTpaAccount == "true") {
            power.text = "Behind Every Payment, There's A Smile."
            footer.addView(power)
        } else {
            if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
                if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
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
                    Utils.debugLogPrint("TAG", "onCreate: image")
                } else {
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
        //val tags = arrayOf("DATE/TIME", "E-WALLET", "TXN TYPE", "MID", "TID", "APPROVAL CODE", "HOST REF", "AMOUNT", "REF ID")
        var entity = MulPrintStrEntity("", fontSize)

        val acquirerImageAsset = getImageFromAssetsFile(requireActivity().applicationContext, acqLogoEnumModel.HeaderLogoBmp)
        var powerByImageAsset = getImageFromAssetsFile(requireActivity().applicationContext, "image/logo_footer_small.bmp")
        val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByImageAsset = BitmapFactory.decodeFile(imgFile.absolutePath)
        }

        entity.bitmap = if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || transactionQrGet?.isTpaAccount == "true") {
            powerByImageAsset
        } else {
            acquirerImageAsset
        }
        entity.marginX = 50
        entity.gravity = Gravity.CENTER
        entity.isUnderline = true
        entity.yspace = 30
        list.add(entity)
        var mulPrintStrEntity = MulPrintStrEntity(
            getSafeValue(dbModelMerchantConfig, "MerchantName"),
            FontFamily.SMALL, false,
            Gravity.CENTER
        )
        list.add(mulPrintStrEntity)
        mulPrintStrEntity = MulPrintStrEntity(
            getSafeValue(dbModelMerchantConfig, "MerchantAddress"),
            FontFamily.SMALL, false,
            Gravity.CENTER
        )
        list.add(mulPrintStrEntity)
        list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

        details.forEach { (key, value) ->
            val keyValue = value.toString()
            if (keyValue.trim().isEmpty()) {
                return@forEach
            }

            if (isUPITxn && key == "VC CODE") {
                list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
            }

            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(key).setFont(fontSize),
                        TextItem(keyValue).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )

            if (isUPITxn && key == "TOTAL AMT (MYR)") {
                list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
            }
        }

        list.add(
            MulPrintStrEntity("I AGREE TO PAY THE ABOVE TOTAL AMOUNT", FontFamily.SMALL, false, Gravity.CENTER)
        )
        list.add(
            MulPrintStrEntity("***** DUPLICATE COPY *****", FontFamily.MIDDLE, false, Gravity.CENTER
            ).setIsBold(Typeface.BOLD).setYspace(10)
        )

        if(transactionQrGet?.isTpaAccount == "true") {
            list.add(MulPrintStrEntity("Behind Every Payment, There's A Smile.", FontFamily.SMALL, false, Gravity.CENTER))
        } else {
            if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
                if(acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
                    list.add(MulPrintStrEntity("PARTNER WITH", FontFamily.SMALL, false, Gravity.CENTER))
                    entity = MulPrintStrEntity("", fontSize)
                    entity.bitmap = acquirerImageAsset
                    entity.marginX = 50
                    entity.gravity = Gravity.CENTER
                    entity.isUnderline = true
                    entity.yspace = 30
                    list.add(entity)
                }
            } else {
                list.add(MulPrintStrEntity("POWERED BY", FontFamily.SMALL, false, Gravity.CENTER))
                entity = MulPrintStrEntity("", fontSize)
                entity.bitmap = powerByImageAsset
                entity.marginX = 50
                entity.gravity = Gravity.CENTER
                entity.isUnderline = true
                entity.yspace = 30
                list.add(entity)
            }
        }

        entity = MulPrintStrEntity(" \n ", FontFamily.BIG)
        entity.gravity = Gravity.CENTER
        entity.yspace = 140
        list.add(entity)
        HelperCommon.sdkPrint(list)
    }

    private fun customOnBackPress() {
        navigateSafe(R.id.action_details_to_listQr)
    }
}