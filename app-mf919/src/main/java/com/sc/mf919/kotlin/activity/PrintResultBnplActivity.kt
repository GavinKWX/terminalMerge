package com.sc.mf919.kotlin.activity

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.*
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.google.gson.Gson
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.kotlin.database.repo.PrintReceiptBnplRepo
import com.sc.mf919.java.activity.Utils.TextItem
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getInternalFilesPaths
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getMerchantInfo
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.selectedCacheModel
import com.sc.mf919.kotlin.helper_common.TmsHelper
import data_enum.SalesModel
import enums.EnumLogFileName
import helpers.HelperCommon
import tms.models.AcquirerLogoReplaceObject
import tms.models.ioupay.IoupayScanQrResponseModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.collections.ArrayList

class PrintResultBnplActivity : ActivityBase() {
    private var receipt: LinearLayout? = null
    private var mainReceipt: LinearLayout? = null
    private var topLogoView: ImageView? = null
    private var whosCopy = true
    private var txnType: String? = ""
    private var txnAmt: String? = ""
    private var txnDt: String? = ""
    private var txnId: String? = ""
    private var mid: String? = ""
    private var tid: String? = ""
    private var txnRefId: String? = ""
    private var eWallet: String? = ""
    private var acqCode: String? = ""
    private var bankAuthCode: String? = ""
    private var tenureDesc: String? = ""
    private var status: String? = ""
    var selectedSalesModel: SalesModel? = null
    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    private var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
    lateinit var bnplResp: IoupayScanQrResponseModel
    private val SUPERSCRIPT_REGEX = "(?<=\\b\\d{0,9999})(st|nd|rd|th)(?=\\b)"
    private val PATTERN: Pattern = Pattern.compile(SUPERSCRIPT_REGEX)
    private lateinit var acqLogoEnumModel: AcquirerLogoEnumModel
    lateinit var firstItemTitle: SpannableString
    private var helperlogClassName:String = ""
    private lateinit var helperLog: helpers.HelperLog
    private val isHideBottom get() = com.sc.mf919.BuildConfig.hide_bottom

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printresult_bnpl)
        helperlogClassName = this::class.qualifiedName.toString()

        // Was `val log = ...`: constructed on every open and never used once, so this screen
        // produced no log at all. Promoted to a field so its flow is actually recorded.
        helperLog = helpers.HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperlogClassName,
            PrintResultBnplActivity::class.java.simpleName,
            "BNPL Print Result Screen"
        )
        helperLog.appendLine(helperlogClassName, "BNPL print result screen opened")

        acqLogoEnumModel = AcquirerLogoEnumModel(
            R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, getResources().getString(R.string.app_name_about)
        )

        dbModelMerchantConfig = getMerchantInfo()
        selectedSalesModel = selectedCacheModel as SalesModel?
        if (selectedSalesModel == null) {
            selectedSalesModel = SalesModel(0, "", "", "", "", "", "", "", "", "")
        }
        acqCode = intent.getStringExtra("acqCode")
        if (acqCode!!.isEmpty()) {
            acqCode = selectedSalesModel!!.AcqCode
        }
        acqLogoEnumModel = AcquirerLogoDataEnum.from(acqCode)?.data ?: AcquirerLogoDataEnum.BLANK.data
        acqLogoEnumModel = com.sc.mf919.kotlin.helper_common.Helper.getInstance().checkConfigOverrideLogo(acqLogoEnumModel)

        txnType = intent.getStringExtra("txnType")
        txnAmt = intent.getStringExtra("amount")
        mid = selectedSalesModel!!.AcqMid
        tid = selectedSalesModel!!.AcqTid
        txnId = intent.getStringExtra("txnId")
        txnRefId = intent.getStringExtra("txnRefId")
        txnDt = intent.getStringExtra("txnDt")
        //eWallet = intent.getStringExtra("eWallet");
        eWallet = selectedSalesModel!!.ProductName
        bankAuthCode = intent.getStringExtra("bankAuthCode")
        receipt = findViewById(R.id.receipt)
        mainReceipt = findViewById(R.id.mainReceipt)
        topLogoView = findViewById(R.id.headerImageView)

        //no print receipt button on unattended terminal, the button row centers the remaining button on its own
        if (DbModelTerminalConfig.getBooleanValue(ServiceHolder.getTerminalConfig(), "UNATTENDED_MODE")) {
            (findViewById<View>(R.id.printBtn) as LinearLayout).visibility = View.GONE
        }

        val bnplDetailsResp = PrintReceiptBnplRepo.getRowArrayByRefId(applicationContext, txnRefId ?: "")
        if (bnplDetailsResp != null) {
            bnplResp = Gson().fromJson(bnplDetailsResp[20], IoupayScanQrResponseModel::class.java)
            tenureDesc = bnplDetailsResp[19]
            status = bnplDetailsResp[13]
        }

        formLayout()
        whosCopy = true
        onBackPressedDispatcher.addCallback(this@PrintResultBnplActivity, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
        print_receipt(View(this))
    }

    private fun formLayout() {
        topLogoView!!.setImageResource(acqLogoEnumModel.HeaderLogoPng)
        if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
            val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
            val imgFile = File(paths)
            if (imgFile.exists()) {
                val myBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                topLogoView!!.setImageBitmap(myBitmap)
            }
        }

        val tags = arrayOf("DATE/TIME", "TXN ID", "TOTAL", "TENURE", "STATUS")
        val details = arrayOf(txnDt, txnId, txnAmt, tenureDesc, status)
        val name = TextView(applicationContext)
        name.textSize = 9f
        name.textAlignment = View.TEXT_ALIGNMENT_CENTER
        name.setTextColor(resources.getColor(R.color.black))
        name.text = getSafeValue(dbModelMerchantConfig, "MerchantName")
        val addr = TextView(applicationContext)
        addr.textSize = 9f
        addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
        addr.setTextColor(resources.getColor(R.color.black))
        addr.text = getSafeValue(dbModelMerchantConfig, "MerchantAddress")
        mainReceipt!!.addView(name)
        mainReceipt!!.addView(addr)


        for (j in tags.indices) {
            if (details[j] == null || details[j] == "") {
                continue
            }
            val ll = LinearLayout(applicationContext)
            /*val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )*/
            ll.orientation = LinearLayout.HORIZONTAL
            ll.weightSum = 2f
            val params1 = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params1.setMargins(5, 0, 5, 0)
            params1.weight = 1f
            val tv = TextView(applicationContext)
            tv.layoutParams = params1
            tv.textSize = 11f
            tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            tv.setTextColor(resources.getColor(R.color.black))
            tv.text = tags[j]
            val tv1 = TextView(applicationContext)
            tv1.layoutParams = params1
            tv1.textSize = 11f
            tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            tv1.setTypeface(tv1.typeface, Typeface.BOLD)
            tv1.setTextColor(resources.getColor(R.color.black))
            tv1.text = details[j]
            ll.addView(tv)
            ll.addView(tv1)
            mainReceipt!!.addView(ll)
        }

        if (bnplResp.SCHEDULE_LIST?.isNotEmpty() == true) {
            renderScheduleItem();
        }

        val footerTags = arrayOf(
            firstItemTitle, "TOTAL PAYABLE", "REF ID"
        )
        val footerDetails = arrayOf(
            "RM " + bnplResp.SCHEDULE_LIST!![0].SCHEDULE_AMOUNT_STRING, txnAmt, txnRefId
        )

        for (h in footerTags.indices) {
            if (footerDetails[h] == null || footerDetails[h] == "") {
                continue
            }
            val ll = LinearLayout(applicationContext)
            ll.orientation = LinearLayout.HORIZONTAL
            ll.weightSum = 2f
            val params1 = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params1.setMargins(5, 0, 5, 0)
            params1.weight = 1f
            val tv = TextView(applicationContext)
            tv.layoutParams = params1
            tv.textSize = 11f
            tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            tv.setTextColor(resources.getColor(R.color.black))
            tv.text = footerTags[h]
            val tv1 = TextView(applicationContext)
            tv1.layoutParams = params1
            tv1.textSize = 11f
            tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            tv1.setTypeface(tv1.typeface, Typeface.BOLD)
            tv1.setTextColor(resources.getColor(R.color.black))
            tv1.text = footerDetails[h]
            ll.addView(tv)
            ll.addView(tv1)
            mainReceipt!!.addView(ll)
        }

        val footer = LinearLayout(applicationContext)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 10, 0, 10)
        params.gravity = Gravity.CENTER
        footer.orientation = LinearLayout.VERTICAL
        footer.layoutParams = params
        val power1 = TextView(applicationContext)
        power1.textSize = 7.5f
        power1.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power1.setTextColor(resources.getColor(R.color.black))
        power1.text = "I AGREE TO PAY THE ABOVE TOTAL AMOUNT"
        footer.addView(power1)
        val power = TextView(applicationContext)
        power.textSize = 9f
        power.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
        power.setTextColor(resources.getColor(R.color.black))
        //power.text = "POWERED BY"
        //footer.addView(power)

        val im = ImageView(applicationContext)
        val params1 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, Helper.getInstance().getDpValue(30)
        )
        params1.gravity = Gravity.CENTER
        im.layoutParams = params1
        im.adjustViewBounds = true

        if(!isHideBottom) {
            if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
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
                    if (this::helperLog.isInitialized) helperLog.appendLine(helperlogClassName, "Footer logo loaded from internal storage")
                }
                footer.addView(im)
            }
        }

        mainReceipt!!.addView(footer)
    }

    private fun printInfo(whoseCp: Boolean) {
        val list: MutableList<MulPrintStrEntity> = ArrayList()
        val fontSize = FontFamily.MIDDLE
        val tags = arrayOf("DATE/TIME", "TXN ID", "TOTAL", "TENURE", "STATUS")
        val details = arrayOf(txnDt, txnId, txnAmt, tenureDesc, status)
        var entity = MulPrintStrEntity("", fontSize)

        var imageFromAssetsFile = PrintsActivity.getImageFromAssetsFile(applicationContext, acqLogoEnumModel.HeaderLogoBmp)
        if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
            val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
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

        var mulPrintStrEntity = MulPrintStrEntity(
            getSafeValue(dbModelMerchantConfig, "MerchantName"), FontFamily.SMALL, false,
            Gravity.CENTER
        )
        list.add(mulPrintStrEntity)
        mulPrintStrEntity = MulPrintStrEntity(
            getSafeValue(dbModelMerchantConfig, "MerchantAddress"), FontFamily.SMALL, false,
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
        if (details[3] != null && details[3] != "") {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(
                            tags[3]
                        ).setFont(fontSize),
                        TextItem(details[3]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }
        if (details[4] != null && details[4] != "") {
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(
                            tags[4]
                        ).setFont(fontSize),
                        TextItem(details[4]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
        }

        list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

        if (bnplResp.SCHEDULE_LIST?.isNotEmpty() == true) {
            for (i in bnplResp.SCHEDULE_LIST?.indices!!) {
                var paymentTitle: String?
                if (bnplResp.SCHEDULE_LIST!![i].IS_TODAY_PAYMENT == true) {
                    paymentTitle = "1st Payment (Today) "
                } else {
                    val currIndex = i+1
                    val suffix = Helper.getInstance().getNumberSuffix(currIndex)
                    paymentTitle = currIndex.toString() + suffix + " Payment "
                }

                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(paymentTitle.trimEnd()).setFont(fontSize), TextItem(
                                " RM" + bnplResp.SCHEDULE_LIST!![i].SCHEDULE_AMOUNT_STRING
                            ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    ).setGravity(Gravity.RIGHT)
                )

                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val date = dateFormat.parse(bnplResp.SCHEDULE_LIST!![i].PAYMENT_DATE)
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
                val timeStamp = sdf.format(date)

                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem("(" + timeStamp + ")").setFont(fontSize), TextItem(
                                ""
                            ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    ).setYspace(10)
                )
            }

            list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem("1st PAYMENT ").setFont(fontSize), TextItem(
                            " RM" + bnplResp.SCHEDULE_LIST!![0].SCHEDULE_AMOUNT_STRING
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                ).setYspace(10).setGravity(Gravity.RIGHT)
            )

            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem("TOTAL PAYABLE").setFont(fontSize), TextItem(
                            txnAmt
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )

            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem("REF ID").setFont(fontSize), TextItem(
                            txnRefId
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                ).setYspace(10)
            )
        }
        list.add(
            MulPrintStrEntity(
                "I AGREE TO PAY THE ABOVE TOTAL AMOUNT", FontFamily.SMALL, false, Gravity.CENTER
            )
        )
        if (whoseCp) {
            list.add(
                MulPrintStrEntity(
                    "***** MERCHANT COPY *****", FontFamily.MIDDLE, false, Gravity.CENTER
                ).setIsBold(Typeface.BOLD).setYspace(10)
            )
        } else {
            list.add(
                MulPrintStrEntity(
                    "***** CUSTOMER COPY *****", FontFamily.MIDDLE, false, Gravity.CENTER
                ).setIsBold(Typeface.BOLD).setYspace(10)
            )
        }

        if(!isHideBottom) {
            if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
                //if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
                if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
                    list.add(MulPrintStrEntity("PARTNER WITH", FontFamily.SMALL, false, Gravity.CENTER))

                    entity = MulPrintStrEntity("", fontSize)
                    imageFromAssetsFile = PrintsActivity.getImageFromAssetsFile(applicationContext, acqLogoEnumModel.HeaderLogoBmp)
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
                imageFromAssetsFile = PrintsActivity.getImageFromAssetsFile(
                    applicationContext, "image/logo_footer_small.bmp"
                )
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
        entity.yspace = 80
        list.add(entity)
        print(list)
    }

    fun print_receipt(view: View?) {
        val animSlideDown = AnimationUtils.loadAnimation(
            applicationContext, R.anim.receipt_slide_up
        )
        receipt!!.startAnimation(animSlideDown)

        printInfo(whosCopy)
        if (!whosCopy) {
            autoClose()
        }
        whosCopy = false
        (findViewById<View>(R.id.printTV) as TextView).text = "CUSTOMER COPY"
    }

    fun print_receipt_cancel(view: View?) {
        if (!whosCopy) {
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(newIntent)
            finish()
        } else {
            whosCopy = false
            (findViewById<View>(R.id.printTV) as TextView).text = "CUSTOMER COPY"
        }
    }

    fun customOnBackPress() {
        try {
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(newIntent)
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun autoClose() {
        object : Thread() {
            override fun run() {
                super.run()
                Utils.DelayMili(5000)
                handlerAuto.sendMessage(Message())
            }
        }.start()
    }

    private fun renderScheduleItem() {
        firstItemTitle = SpannableString("1st Payment (Today)")
        val matcher: Matcher = PATTERN.matcher(firstItemTitle)

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            firstItemTitle.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            firstItemTitle.setSpan(RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        val mainLinear = LinearLayout(applicationContext)
        val mainLinearParam = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        mainLinearParam.setMargins(5,15,5,20)
        mainLinear.layoutParams = mainLinearParam
        mainLinear.orientation = LinearLayout.VERTICAL

        try {
            for (i in bnplResp.SCHEDULE_LIST?.indices!!) {
                val productMainLV = LinearLayout(applicationContext)
                val productMainLVParam = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                productMainLV.layoutParams = productMainLVParam
                productMainLV.orientation = LinearLayout.VERTICAL

                val productLV = LinearLayout(applicationContext)
                val productLVParam = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                productLV.setPadding(0,5,0,0)
                productLV.layoutParams = productLVParam


                val productLV2 = LinearLayout(applicationContext)
                val productLV2Param = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                productLV2.setPadding(0,5,0,0)
                productLV2.layoutParams = productLV2Param

                val productTV1 = TextView(applicationContext)
                val productTV2 = TextView(applicationContext)
                val productTV3 = TextView(applicationContext)
                productTV1.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                productTV2.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                productTV3.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                productTV1.gravity = Gravity.LEFT
                productTV2.gravity = Gravity.RIGHT
                productTV3.gravity = Gravity.LEFT

                if (bnplResp.SCHEDULE_LIST!![i].IS_TODAY_PAYMENT == true) {
                    productTV1.text = firstItemTitle
                } else {
                    val currIndex = i+1
                    val suffix = Helper.getInstance().getNumberSuffix(currIndex)
                    val itemTitle = SpannableString(currIndex.toString() + suffix + " Payment ")
                    val matcher: Matcher = PATTERN.matcher(itemTitle)

                    while (matcher.find()) {
                        val start = matcher.start()
                        val end = matcher.end()
                        itemTitle.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        itemTitle.setSpan(RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    productTV1.text = itemTitle
                }

                productTV2.text = "RM " + bnplResp.SCHEDULE_LIST!![i].SCHEDULE_AMOUNT_STRING
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val date = dateFormat.parse(bnplResp.SCHEDULE_LIST!![i].PAYMENT_DATE)
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
                val timeStamp = sdf.format(date)
                productTV3.text = "(" + timeStamp.toString() + ")"

                productTV1.setTypeface(productTV1.typeface, Typeface.NORMAL)
                productTV1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                productTV1.setTextColor(Color.parseColor("#000000"))
                productTV1.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE

                productTV2.setTypeface(productTV2.typeface, Typeface.BOLD)
                productTV2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                productTV2.setTextColor(Color.parseColor("#000000"))
                productTV2.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE

                productTV3.setTypeface(productTV3.typeface, Typeface.NORMAL)
                productTV3.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                productTV3.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE

                productLV.addView(productTV1)
                productLV.addView(productTV2)

                productLV2.addView(productTV3)

                productMainLV.addView(productLV)
                productMainLV.addView(productLV2)
                mainLinear.addView(productMainLV)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mainReceipt!!.addView(mainLinear)
    }

    var handlerAuto: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            customOnBackPress()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperlogClassName, "PrintResultBnplActivity OnDestroy :: BNPL print result screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}
