package com.sc.mf919.kotlin.activity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.library.terminal.Utility
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919.BuildConfig
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.activity.Utils.TextItem
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.Helper.Companion.getInstance
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tms.models.AcquirerLogoReplaceObject
import java.io.File

class FragmentReceiptQr: Fragment() {
    private var listener: OnFragmentInteractionListener? = null

    lateinit var receipt: ScrollView
    lateinit var mainReceipt: LinearLayout
    lateinit var acqLogoEnumModel: AcquirerLogoEnumModel
    lateinit var details: Array<String>
    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
    var isHideBottom = false

    private var timeSelected : Int = ServiceHolder.ackCountDownSecond
    private var timeCountDown: CountDownTimer? = null
    private var timeProgress = 0

    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    /**
     * printInfo runs on Dispatchers.Default and the countdown timer fires independently of the
     * view lifecycle, so a lateinit read is not guaranteed safe at every call site. Fall back to
     * the old Timber path rather than throwing on a receipt screen -- same shape as
     * EmvActivity.logEmv.
     */
    private fun logReceiptQr(msg: String) {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, msg)
        } else {
            Utils.debugLogPrint("FragmentReceiptQr", msg)
        }
    }

    interface OnFragmentInteractionListener {
        fun fragmentReceiptQrBackAction()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnFragmentInteractionListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement OnFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
        timeCountDown?.let {
            it.cancel()
            timeCountDown = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_receipt_qr, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireActivity().applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "QR Payment Receipt Screen"
        )

        receipt = view.findViewById(R.id.receipt)
        mainReceipt = view.findViewById(R.id.mainReceipt)

        isHideBottom = BuildConfig.hide_bottom
        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        acqLogoEnumModel = AcquirerLogoDataEnum.from(TransData.acqCode)?.data ?: AcquirerLogoDataEnum.BLANK.data
        if (dbModelMerchantConfig != null) {
            acquirerLogoConfig = Gson().fromJson(DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)
        }
        acqLogoEnumModel = Helper.getInstance().checkConfigOverrideLogo(acqLogoEnumModel)

        // The QR outcome is final by the time the receipt renders -- record it here so an
        // approved sale is on disk even if the receipt screen is the last thing before a kill.
        logReceiptQr("QR receipt shown :: type ${TransData.txnTypeLabel}, refId ${TransData.qrRef.ifEmpty { "-" }}, " +
            "brand ${TransData.qrPayBrand.ifEmpty { "-" }}, amount ${Utils.getActualAmount(TransData.amount.toString())}, " +
            "hostRef ${TransData.qrHostRef.ifEmpty { "-" }}, respCode ${TransData.qrRespCode.ifEmpty { "-" }}")

        view.findViewById<LinearLayout>(R.id.cancelBtn).setOnClickListener {
            logReceiptQr("User Cancel :: dismissed QR receipt screen (refId ${TransData.qrRef.ifEmpty { "-" }})")
            if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
            listener?.fragmentReceiptQrBackAction()
        }

        val terminalConfig = ServiceHolder.getTerminalConfig()
        //no print receipt button on unattended terminal, buttonLinearGroup centers the remaining button on its own
        val isUnattendedMode = DbModelTerminalConfig.getBooleanValue(terminalConfig, "UNATTENDED_MODE")
        view.findViewById<LinearLayout>(R.id.printBtn).apply {
            visibility = if (isUnattendedMode) View.GONE else View.VISIBLE
            setOnClickListener {
                logReceiptQr("Selected :: Print Receipt (customer copy) [QR RECEIPT]")
                printReceipt(false)
            }
        }

        details = TransData.generateReceiptInfoQr()
        formLayout(view)

        if (TransData.salesType == 0 || DbModelTerminalConfig.getBooleanValue(terminalConfig, "ReceiptPrint")) {
            logReceiptQr("Auto-printing merchant copy :: salesType ${TransData.salesType}")
            printReceipt(true)
        }
        startTimer(0)
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun resetTime() {
        if (timeCountDown!=null) {
            timeCountDown!!.cancel()
            timeProgress=0
            timeSelected=0
            timeCountDown=null

            val timeLeftTv = view?.findViewById<TextView>(R.id.cancelTextView)
            timeLeftTv?.let{
                timeLeftTv.text = "CANCEL (0)"
            }
        }
    }

    private fun startTimer(pauseOffSetL: Long) {
        timeCountDown = object :CountDownTimer((timeSelected*1000).toLong() - pauseOffSetL*1000, 1000) {
            override fun onTick(p0: Long) {
                timeProgress++
                val timeLeftTv = view?.findViewById<TextView>(R.id.cancelTextView)
                timeLeftTv?.let {
                    timeLeftTv.text = "CANCEL (${timeSelected - timeProgress})"
                }
            }

            override fun onFinish() {
                logReceiptQr("QR receipt screen auto-dismissed :: acknowledge countdown elapsed")
                if (this@FragmentReceiptQr::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
                resetTime()
                listener?.fragmentReceiptQrBackAction()
            }
        }.start()
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun formLayout(view: View) {
        var tags = arrayOf(
            "DATE/TIME",
            "E-WALLET",
            "TXN TYPE",
            "MID",
            "TID",
            "APPROVAL CODE",
            "HOST REF",
            "AMOUNT",
            "REF ID"
        )
        if (TransData.isUPIQR && TransData.upiVoucherCode.isNotEmpty()) {
            tags = arrayOf(
                "DATE/TIME",
                "E-WALLET",
                "TXN TYPE",
                "MID",
                "TID",
                "APPROVAL CODE",
                "HOST REF",
                "REF ID"
            )
        }
        var powerByBmp: Bitmap? = null
        val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
        }
        // The old line here dumped the whole receipt detail array (MID/TID/approval code and all)
        // under a literal "TAG". The transaction identity is already logged in onViewCreated, so
        // this only records that the receipt body was built and how many rows it has.
        logReceiptQr("Receipt layout built :: ${details.size} detail row(s), UPI ${TransData.isUPIQR}")

        val headerImageView = view.findViewById<ImageView>(R.id.headerImageView)
        if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || TransData.isTpaAccount) {
            if(powerByBmp != null) {
                headerImageView.setImageBitmap(powerByBmp)
            } else {
                headerImageView.setImageResource(R.mipmap.logo)
            }
        } else {
            headerImageView.setImageResource(acqLogoEnumModel.HeaderLogoPng)
        }

        val name = TextView(requireActivity().applicationContext)
        name.textSize = 9f
        name.textAlignment = View.TEXT_ALIGNMENT_CENTER
        name.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
        name.text = getSafeValue(dbModelMerchantConfig, "MerchantName")
        val addr = TextView(requireActivity().applicationContext)
        addr.textSize = 9f
        addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
        addr.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
        addr.text = getSafeValue(dbModelMerchantConfig, "MerchantAddress")
        mainReceipt.addView(name)
        mainReceipt.addView(addr)
        for (j in tags.indices) {
            //TODO HIDE MID[3] AND TID[4]
            if (details[j] == "" || j == 3 || j ==4) {
                continue
            }
            val ll = LinearLayout(requireActivity().applicationContext)
            ll.orientation = LinearLayout.HORIZONTAL
            ll.weightSum = 2f
            val params1 = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params1.setMargins(5, 0, 5, 0)
            params1.weight = 1f
            val tv = TextView(requireActivity().applicationContext)
            tv.layoutParams = params1
            tv.textSize = 11f
            tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            tv.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
            tv.text = tags[j]
            val tv1 = TextView(requireActivity().applicationContext)
            tv1.layoutParams = params1
            tv1.textSize = 11f
            tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            tv1.setTypeface(tv1.typeface, Typeface.BOLD)
            tv1.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
            tv1.text = details[j]
            ll.addView(tv)
            ll.addView(tv1)
            mainReceipt.addView(ll)
        }

        /* upi txn */
        if (TransData.isUPIQR && TransData.upiVoucherCode.isNotEmpty()) {
            val upiQrDetailsTag = arrayOf(
                "VC CODE",
                "AMT (MYR)",
                "DISCOUNT AMT (MYR)",
                "TOTAL AMT (MYR)"
            )
            val upiQrDetails = arrayOf(TransData.upiVoucherCode, Utils.getActualAmount(TransData.amount.toString()), TransData.upiDiscountAmt, TransData.upiFinalAmount)

            for (i in upiQrDetailsTag.indices) {
                if (upiQrDetails[i] == "") {
                    continue
                }
                val ll = LinearLayout(requireActivity().applicationContext)
                ll.orientation = LinearLayout.HORIZONTAL
                ll.weightSum = 2f
                val params1 = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params1.setMargins(5, 0, 5, 0)
                params1.weight = 1f
                val tv = TextView(requireActivity().applicationContext)
                tv.layoutParams = params1
                tv.textSize = 11f
                tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tv.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv.text = upiQrDetailsTag[i]
                val tv1 = TextView(requireActivity().applicationContext)
                tv1.layoutParams = params1
                tv1.textSize = 11f
                tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                tv1.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv1.text = upiQrDetails[i]

                val ll2 = LinearLayout(requireActivity().applicationContext)
                val params2 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                ll2.orientation = LinearLayout.VERTICAL
                ll2.layoutParams = params2
                val tvDashed = TextView(requireActivity().applicationContext)
                tvDashed.textSize = 11f
                tvDashed.layoutParams = params1
                tvDashed.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tvDashed.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tvDashed.setSingleLine(true)
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val dashWidth = tvDashed.paint.measureText("-")
                val dashCount = (screenWidth / dashWidth).toInt()
                tvDashed.text = "-".repeat(dashCount)

                ll.addView(tv)
                ll.addView(tv1)

                if (i == 0) {
                    ll2.addView(tvDashed)
                    mainReceipt.addView(ll2)
                }
                mainReceipt.addView(ll)
                if (i == 3) {
                    ll2.addView(tvDashed)
                    mainReceipt.addView(ll2)
                }
            }
        }
        /* upi txn */

        val footer = LinearLayout(requireActivity().applicationContext)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 5, 0, 0)
        params.gravity = Gravity.CENTER
        footer.orientation = LinearLayout.VERTICAL
        footer.layoutParams = params
        val power1 = TextView(requireActivity().applicationContext)
        power1.textSize = 7.5f
        power1.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power1.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
        power1.text = "I AGREE TO PAY THE ABOVE TOTAL AMOUNT"
        footer.addView(power1)
        val power = TextView(requireActivity().applicationContext)
        power.textSize = 9f
        power.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
        power.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))

        val im = ImageView(requireActivity().applicationContext)
        val params1 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            getInstance().getDpValue(30)
        )
        params1.gravity = Gravity.CENTER
        im.layoutParams = params1
        im.adjustViewBounds = true

        if(!isHideBottom) {
            if(TransData.isTpaAccount) {
                power.text = "Behind Every Payment, There's A Smile."
                footer.addView(power)
            } else {
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
                    if(powerByBmp != null) {
                        im.setImageBitmap(powerByBmp)
                    } else {
                        im.setImageResource(R.mipmap.logo)
                    }
                    footer.addView(im)
                }
            }
        }

        mainReceipt.addView(footer)
    }

    fun printReceipt(printCopy: Boolean) {
        CoroutineScope(Dispatchers.Default).launch {
            printInfo(printCopy)
        }
        val animSlideDown = AnimationUtils.loadAnimation(requireActivity().applicationContext, R.anim.receipt_slide_up)
        receipt.startAnimation(animSlideDown)
    }

    suspend fun printInfo(printCopy: Boolean) = withContext(Dispatchers.Default) {
        val list: MutableList<MulPrintStrEntity?> = ArrayList()
        val fontSize = FontFamily.MIDDLE
        var tags = arrayOf(
            "DATE/TIME",
            "E-WALLET",
            "TXN TYPE",
            "MID",
            "TID",
            "APPROVAL CODE",
            "HOST REF",
            "AMOUNT",
            "REF ID"
        )

        if (TransData.isUPIQR && TransData.upiVoucherCode.isNotEmpty()) {
            tags = arrayOf(
                "DATE/TIME",
                "E-WALLET",
                "TXN TYPE",
                "MID",
                "TID",
                "APPROVAL CODE",
                "HOST REF",
                "REF ID"
            )
        }
        val acquirerBmp = PrintsActivity.getImageFromAssetsFile(requireActivity().applicationContext, acqLogoEnumModel.HeaderLogoBmp)
        var powerByBmp = PrintsActivity.getImageFromAssetsFile(requireActivity().applicationContext, "image/logo_footer_small.bmp")
        val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
        }

        var entity = MulPrintStrEntity("", fontSize)
        entity.bitmap = if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || TransData.isTpaAccount) powerByBmp else acquirerBmp
        entity.marginX = 50
        entity.gravity = Gravity.CENTER
        entity.isUnderline = true
        entity.yspace = 30
        list.add(entity)

        var mulPrintStrEntity = MulPrintStrEntity(
            getSafeValue(dbModelMerchantConfig, "MerchantName"),
            FontFamily.SMALL,
            false,
            Gravity.CENTER
        )
        list.add(mulPrintStrEntity)
        mulPrintStrEntity = MulPrintStrEntity(
            getSafeValue(dbModelMerchantConfig, "MerchantAddress"),
            FontFamily.SMALL,
            false,
            Gravity.CENTER
        )
        list.add(mulPrintStrEntity)
        list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

        list.add(
            MulPrintStrEntity(
                Utils.makeLineText(
                    TextItem(tags[0]).setFont(fontSize), TextItem(
                        details[0]
                    ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        )
        list.add(
            MulPrintStrEntity(
                Utils.makeLineText(
                    TextItem(tags[1]).setFont(fontSize), TextItem(
                        details[1]
                    ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        )
        list.add(
            MulPrintStrEntity(
                Utils.makeLineText(
                    TextItem(tags[2]).setFont(fontSize), TextItem(
                        details[2]
                    ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        )
        //TODO HIDE MID/TID/APPROVALCODE
        /*if (details[3] != "") {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[3]).setFont(fontSize), TextItem(
                            details[3]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }
        if (details[4] != "") {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[4]).setFont(fontSize), TextItem(
                            details[4]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }*/
        /*if (details[5] != "") {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[5]).setFont(fontSize), TextItem(
                            details[5]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }*/
        //TODO HIDE MID/TID/APPROVALCODE
        list.add(
            MulPrintStrEntity(
                Utils.makeLineText(
                    TextItem(tags[6]).setFont(fontSize), TextItem(
                        details[6]
                    ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        )

        list.add(
            MulPrintStrEntity(
                Utils.makeLineText(
                    TextItem(tags[7]).setFont(fontSize), TextItem(
                        details[7]
                    ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        )

        if (TransData.isUPIQR && TransData.upiVoucherCode.isNotEmpty()) {
            val upiQrDetailsTag = arrayOf(
                "VC CODE",
                "AMT (MYR)",
                "DISCOUNT AMT (MYR)",
                "TOTAL AMT (MYR)"
            )

            val upiQrDetails = arrayOf(TransData.upiVoucherCode, Utils.getActualAmount(TransData.amount.toString()), TransData.upiDiscountAmt, TransData.upiFinalAmount)

            list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))

            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(upiQrDetailsTag[0]).setFont(fontSize), TextItem(
                            upiQrDetails[0]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )

            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(upiQrDetailsTag[1]).setFont(fontSize), TextItem(
                            upiQrDetails[1]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )

            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(upiQrDetailsTag[2]).setFont(fontSize), TextItem(
                            upiQrDetails[2]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )

            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(upiQrDetailsTag[3]).setFont(fontSize), TextItem(
                            upiQrDetails[3]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )

            list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
        } else {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[8]).setFont(fontSize), TextItem(
                            details[8]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }


        list.add(
            MulPrintStrEntity(
                "I AGREE TO PAY THE ABOVE TOTAL AMOUNT",
                FontFamily.SMALL,
                false,
                Gravity.CENTER
            )
        )

        if (printCopy) {
            list.add(
                MulPrintStrEntity(
                    "***** MERCHANT COPY *****",
                    FontFamily.MIDDLE,
                    false,
                    Gravity.CENTER
                ).setIsBold(Typeface.BOLD).setYspace(10)
            )
        } else {
            list.add(
                MulPrintStrEntity(
                    "***** CUSTOMER COPY *****",
                    FontFamily.MIDDLE,
                    false,
                    Gravity.CENTER
                ).setIsBold(Typeface.BOLD).setYspace(10)
            )
        }

        if(!isHideBottom) {
            if(TransData.isTpaAccount) {
                list.add(MulPrintStrEntity("Behind Every Payment, There's A Smile.", FontFamily.SMALL, false, Gravity.CENTER))
            } else {
                if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
                    //if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
                    if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
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
        }

        entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
        entity.gravity = Gravity.CENTER
        entity.yspace = 80
        list.add(entity)
        HelperCommon.sdkPrint(list)
        logReceiptQr("Receipt printed :: ${if (printCopy) "merchant copy" else "customer copy"}, refId ${TransData.qrRef.ifEmpty { "-" }}")
        if (this@FragmentReceiptQr::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "FragmentReceiptQr OnDestroyView :: QR receipt screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}