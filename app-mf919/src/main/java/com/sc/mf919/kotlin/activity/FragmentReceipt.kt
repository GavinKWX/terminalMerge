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
import org.apache.commons.lang3.StringUtils
import tms.models.AcquirerLogoReplaceObject
import java.io.File
import java.util.Arrays

class FragmentReceipt: Fragment() {
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
    //private var pauseOffSet: Long = 0
    //private var isStart = true

    interface OnFragmentInteractionListener {
        fun fragmentReceiptBackAction()
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

    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    /**
     * getEppDetails() and the image loaders are reachable from formLayout() paths, so guard rather
     * than assume helperLog is up. Same shape as EmvActivity.logEmv.
     */
    private fun logReceipt(msg: String) {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, msg)
        } else {
            Utils.debugLogPrint("FragmentReceipt", msg)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_receipt, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext().applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Transaction Receipt Screen"
        )
        receipt = view.findViewById(R.id.receipt)
        mainReceipt = view.findViewById(R.id.mainReceipt)

        isHideBottom = BuildConfig.hide_bottom
        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        acqLogoEnumModel = AcquirerLogoDataEnum.from(dbModelMerchantConfig?.AcqCode)?.data ?: AcquirerLogoDataEnum.BLANK.data
        if (dbModelMerchantConfig != null) {
            acquirerLogoConfig = Gson().fromJson(DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)
        }
        acqLogoEnumModel = Helper.getInstance().checkConfigOverrideLogo(acqLogoEnumModel)

        view.findViewById<LinearLayout>(R.id.cancelBtn).setOnClickListener {
            logReceipt("Selected :: Done [RECEIPT]")
            if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
            listener?.fragmentReceiptBackAction()
        }

        val terminalConfig = ServiceHolder.getTerminalConfig()
        //no print receipt button on unattended terminal, buttonLinearGroup centers the remaining button on its own
        val isUnattendedMode = DbModelTerminalConfig.getBooleanValue(terminalConfig, "UNATTENDED_MODE")
        view.findViewById<LinearLayout>(R.id.printBtn).apply {
            visibility = if (isUnattendedMode) View.GONE else View.VISIBLE
            setOnClickListener {
                logReceipt("Selected :: Print receipt [RECEIPT]")
                if (this@FragmentReceipt::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
                printReceipt(false)
            }
        }

        details = TransData.generateReceiptInfo()
        formLayout(view)

        //TODO
        /*//skip print receipt for Zakat
        var isZakatTxn = false
        if (posReference != "-"){
            val tmpPosRef = posReference!!.substring(0, 5.coerceAtMost(posReference!!.length))
            println("\tis $tmpPosRef txn!")
            if (tmpPosRef.uppercase() == "ZAKAT"){
                isZakatTxn = true
            }
        }*/

        //if ((details[3].uppercase() != "SALE" || DbModelTerminalConfig.getBooleanValue(terminalConfig, "ReceiptPrint"))) {
        if (TransData.salesType == 0 || DbModelTerminalConfig.getBooleanValue(terminalConfig, "ReceiptPrint")) {
            printReceipt(true)
        }
        startTimer(0)
    }

    private fun resetTime() {
        if (timeCountDown!=null) {
            timeCountDown!!.cancel()
            timeProgress=0
            timeSelected=0
            //pauseOffSet=0
            timeCountDown=null

           /* val progressBar = view?.findViewById<ProgressBar>(R.id.pbTimer)
            progressBar?.let {
                progressBar.progress = 0
            }*/
            val timeLeftTv = view?.findViewById<TextView>(R.id.cancelTextView)
            timeLeftTv?.let{
                timeLeftTv.text = "CANCEL (0)"
            }
        }
    }

    /*private fun timePause() {
        if (timeCountDown!=null) {
            timeCountDown!!.cancel()
        }
    }*/

    private fun startTimer(pauseOffSetL: Long) {
        /*val progressBar = view?.findViewById<ProgressBar>(R.id.pbTimer)
        progressBar?.let {
            progressBar.max = timeSelected
            progressBar.progress = timeProgress
        }*/
        timeCountDown = object :CountDownTimer((timeSelected*1000).toLong() - pauseOffSetL*1000, 1000) {
            override fun onTick(p0: Long) {
                timeProgress++
                //pauseOffSet = timeSelected.toLong()- p0/1000
                /*progressBar?.let {
                    progressBar.progress = timeSelected-timeProgress
                }*/
                val timeLeftTv = view?.findViewById<TextView>(R.id.cancelTextView)
                timeLeftTv?.let {
                    timeLeftTv.text = "CANCEL (${timeSelected - timeProgress})"
                }
            }

            override fun onFinish() {
                resetTime()
                listener?.fragmentReceiptBackAction()
            }
        }.start()
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun formLayout(view: View) {
        val tags = arrayOf(
            "MERCHANT ID",
            "TERMINAL ID",
            "BATCH NO",
            "",
            "APP LABEL",
            "CARD NO",
            "DATE/TIME",
            "INV NO",
            "TRACE NO",
            "ENTRY TYPE",
            "REF NO",
            "APPROVAL CODE",
            "CASHOUT AMOUNT",
            "TOTAL",
            "",
            "ARQC",
            "AID",
            "TVR",
            ""
        )
        var powerByBmp: Bitmap? = null
        val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
            logReceipt("Power-by logo loaded from internal storage")
        }
        logReceipt("Receipt fields :: " + Arrays.toString(details))

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
        for (j in details.indices) {
            if (details[j] == null || details[j] == "") {
                continue
            }
            if (j == 3) {
                val ll = LinearLayout(requireActivity().applicationContext)
                ll.orientation = LinearLayout.HORIZONTAL
                val params1 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params1.setMargins(5, 0, 5, 0)
                params1.weight = 1f
                val tv = TextView(requireActivity().applicationContext)
                tv.layoutParams = params1
                tv.textSize = 14f
                tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                tv.setTypeface(tv.typeface, Typeface.BOLD)
                tv.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv.text = details[j]
                ll.addView(tv)
                mainReceipt.addView(ll)
            } else if (j == 5) {
                val ll = LinearLayout(requireActivity().applicationContext)
                ll.orientation = LinearLayout.VERTICAL
                val params1 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params1.setMargins(5, 0, 5, 0)
                val tv = TextView(requireActivity().applicationContext)
                tv.layoutParams = params1
                tv.textSize = 11f
                tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tv.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv.text = tags[j]
                val tv1 = TextView(requireActivity().applicationContext)
                tv1.layoutParams = params1
                tv1.textSize = 14f
                tv1.textAlignment = View.TEXT_ALIGNMENT_CENTER
                tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                tv1.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv1.text = details[j]
                ll.addView(tv)
                ll.addView(tv1)
                mainReceipt.addView(ll)
            } else if (j == 12) {
                if (details[j] != "0") {
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
                    tv1.text = "RM" + details[j]
                    ll.addView(tv)
                    ll.addView(tv1)
                    mainReceipt!!.addView(ll)
                }
            } else if (j == 13) {
                val ll = LinearLayout(requireActivity().applicationContext)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                ll.orientation = LinearLayout.VERTICAL
                ll.layoutParams = params
                val tv4 = TextView(requireActivity().applicationContext)
                tv4.textSize = 11f
                tv4.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tv4.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv4.text = "------------------------------------------------------------------"
                val tv3 = TextView(requireActivity().applicationContext)
                tv3.textSize = 11f
                tv3.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tv3.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv3.text = "------------------------------------------------------------------"
                val ll_1 = LinearLayout(requireActivity().applicationContext)
                ll_1.orientation = LinearLayout.HORIZONTAL
                ll_1.weightSum = 2f
                val params2 = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params2.setMargins(5, 0, 5, 0)
                params2.weight = 1f
                val tv2 = TextView(requireActivity().applicationContext)
                tv2.layoutParams = params2
                tv2.textSize = 14f
                tv2.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tv2.setTypeface(tv2.typeface, Typeface.BOLD)
                tv2.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv2.text = tags[j]
                val tv1 = TextView(requireActivity().applicationContext)
                tv1.layoutParams = params2
                tv1.textSize = 14f
                tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                tv1.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv1.text = "RM" + details[j]
                ll_1.addView(tv2)
                ll_1.addView(tv1)
                ll.addView(tv4)
                ll.addView(ll_1)
                ll.addView(tv3)
                mainReceipt!!.addView(ll)
            } else if (j == 14) {
                val isCZ = TransData.acqCode.equals("BSN_CARDZONE", true)
                val eppDetails = parseEppDetailsReceipt(details[j], isCZ)
                val eppTags = arrayOf("TENURE", "FIRST AMT", "MTHLY AMT", "TOTAL AMT")
                val ll = LinearLayout(requireActivity().applicationContext)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                ll.orientation = LinearLayout.VERTICAL
                ll.layoutParams = params
                val tv3 = TextView(requireActivity().applicationContext)
                tv3.textSize = 11f
                tv3.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tv3.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv3.text = "------------------------------------------------------------------"
                for (k in eppDetails.indices) {
                    val ll_1 = LinearLayout(requireActivity().applicationContext)
                    val params3 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    ll_1.orientation = LinearLayout.HORIZONTAL
                    ll_1.weightSum = 2f
                    val params2 = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params2.setMargins(5, 0, 5, 0)
                    params2.weight = 1f
                    val tv2 = TextView(requireActivity().applicationContext)
                    tv2.layoutParams = params2
                    tv2.textSize = 11f
                    tv2.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv2.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                    tv2.text = eppTags[k]
                    val tv1 = TextView(requireActivity().applicationContext)
                    tv1.layoutParams = params2
                    tv1.textSize = 11f
                    tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                    tv1.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                    if (k == 0) {
                        tv1.text = String.format(eppDetails[k]!!)
                    } else {
                        tv1.text = "RM" + eppDetails[k]
                    }
                    ll_1.addView(tv2)
                    ll_1.addView(tv1)
                    ll.addView(ll_1)
                }
                ll.addView(tv3)
                mainReceipt.addView(ll)
            } else if (j == 18) {
                val ll = LinearLayout(requireActivity().applicationContext)
                ll.orientation = LinearLayout.HORIZONTAL
                val params1 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params1.setMargins(5, 0, 5, 0)
                params1.weight = 1f
                val tv = TextView(requireActivity().applicationContext)
                tv.layoutParams = params1
                tv.textSize = 9f
                tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                tv.setTypeface(tv.typeface, Typeface.BOLD)
                tv.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
                tv.text = Utils.CVMAnalysis(details[j], "")
                ll.addView(tv)
                mainReceipt!!.addView(ll)
            } else {
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
                mainReceipt!!.addView(ll)
            }
        }

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
        power1.text = "I AGREE TO PAY THE ABOVE TOTAL AMOUNT ACCORDING TO THE CARD ISSUER AGREEMENT"
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
        val tags = arrayOf(
            "MERCHANT ID",
            "TERMINAL ID",
            "BATCH NO",
            "",
            "APP LABEL",
            "CARD NO",
            "DATE/TIME",
            "INV NO",
            "TRACE NO",
            "ENTRY TYPE",
            "REF NO",
            "APPROVAL CODE",
            "CASHOUT AMOUNT",
            "TOTAL",
            "",
            "ARQC",
            "AID",
            "TVR",
            ""
        )
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
        list.add(MulPrintStrEntity(details[3], FontFamily.BIG, false, Gravity.CENTER))
        if (details[4] != null && details[4] != "") {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[4]).setFont(fontSize), TextItem(
                            details[4]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }
        list.add(MulPrintStrEntity(tags[5], fontSize))
        list.add(MulPrintStrEntity(details[5], FontFamily.BIG, false, Gravity.CENTER))
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
        list.add(
            MulPrintStrEntity(
                Utils.makeLineText(
                    TextItem(tags[8]).setFont(fontSize), TextItem(
                        details[8]
                    ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        )
        list.add(
            MulPrintStrEntity(
                Utils.makeLineText(
                    TextItem(tags[9]).setFont(fontSize), TextItem(
                        details[9]
                    ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        )
        list.add(
            MulPrintStrEntity(
                Utils.makeLineText(
                    TextItem(tags[10]).setFont(fontSize), TextItem(
                        details[10]
                    ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        )
        list.add(
            MulPrintStrEntity(
                Utils.makeLineText(
                    TextItem(tags[11]).setFont(fontSize), TextItem(
                        details[11]
                    ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                ), fontSize
            )
        )
        if (details[12] != "0") {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[12]).setFont(fontSize), TextItem(
                            "RM" + details[12]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }
        list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
        list.add(
            MulPrintStrEntity(
                Utils.makeLineText(
                    TextItem(tags[13]).setFont(FontFamily.BIG),
                    TextItem("RM" + details[13]).setFont(
                        FontFamily.BIG
                    ).setPaddingAlign(Gravity.RIGHT)
                ), FontFamily.BIG
            )
        )
        list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))

        if (details[3] == "Instalment Sale" || details[3] == "Void Instalment") {
            if (details[14] != null && details[14] != "") {
                val isCZ = TransData.acqCode.equals("BSN_CARDZONE", true)
                val eppDetails = parseEppDetailsReceipt(details[14], isCZ)
                val eppTags = if (isCZ) {
                    arrayOf("TENURE", "FIRST AMT", "MTHLY AMT", "TOTAL AMT")
                } else {
                    arrayOf("TENURE", "TOTAL DUE", "MTHLY AMT", "FINAL AMT")
                }
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(eppTags[0]).setFont(fontSize),
                            TextItem(eppDetails[0]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
                for (k in 1 until eppDetails.size) {
                    list.add(
                        MulPrintStrEntity(
                            Utils.makeLineText(
                                TextItem(eppTags[k]).setFont(fontSize),
                                TextItem("RM" + eppDetails[k]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                            ), fontSize
                        )
                    )
                }
                list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
            }
        }
        if (details[15] != null && details[15] != "") {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[15]).setFont(fontSize), TextItem(
                            details[15]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }
        if (details[16] != null && details[16] != "") {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[16]).setFont(fontSize), TextItem(
                            details[16]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }
        if (details[17] != null && details[17] != "") {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[17]).setFont(fontSize), TextItem(
                            details[17]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }
        list.add(
            MulPrintStrEntity(
                Utils.CVMAnalysis(details[18], ""),
                FontFamily.MIDDLE,
                false,
                Gravity.CENTER
            ).setIsBold(Typeface.BOLD)
        )
        list.add(
            MulPrintStrEntity(
                "I AGREE TO PAY THE ABOVE TOTAL AMOUNT ACCORDING TO THE CARD ISSUER AGREEMENT",
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
    }

    private fun parseEppDetailsReceipt(eppDe63: String?, isCZ: Boolean): Array<String?> {
        val eppDetails = arrayOfNulls<String>(4)
        val strEppDetails = StringUtils.trim(eppDe63)
        if (strEppDetails.isNullOrEmpty()) {
            eppDetails[0] = ""
            eppDetails[1] = "0.00"
            eppDetails[2] = "0.00"
            eppDetails[3] = "0.00"
        } else {
            eppDetails[0] = "EPP " + String.format("%02d", strEppDetails.substring(0, 3).toInt()) + " Month" //Tenure
            if (isCZ){
                eppDetails[1] = Utils.getActualAmount(strEppDetails.substring(4, 15)) //First Amt
                eppDetails[2] = Utils.getActualAmount(strEppDetails.substring(16, 27)) //Monthly Amt
                eppDetails[3] = Utils.getActualAmount(strEppDetails.substring(40)) //Total Amt
            } else {
                eppDetails[1] = Utils.getActualAmount(strEppDetails.substring(48)) //Total Amt
                eppDetails[2] = Utils.getActualAmount(strEppDetails.substring(22, 35)) //Monthly Amt
                eppDetails[3] = Utils.getActualAmount(strEppDetails.substring(9, 22)) //Final Amt
            }
        }
        logReceipt("EPP details :: " + eppDetails.contentToString())
        return eppDetails
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "FragmentReceipt OnDestroyView :: receipt screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}
