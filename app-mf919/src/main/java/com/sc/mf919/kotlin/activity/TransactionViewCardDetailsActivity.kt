package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.library.terminal.Utility
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.activity.Utils.TextItem
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919.kotlin.database.repo.PrintReceiptRepo
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tms.models.AcquirerLogoReplaceObject
import java.io.File
import java.util.Locale


class TransactionViewCardDetailsActivity : ActivityBase() {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    private val isHideBottom get() = com.sc.mf919.BuildConfig.hide_bottom
    private var stan: String? = ""

    lateinit var headerImageView: ImageView
    lateinit var mainReceipt: LinearLayout
    lateinit var receipt: ScrollView

    private var dbModelPrintReceipt: DbModelPrintReceipt? = null
    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    private var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
    lateinit var acqLogoEnumModel: AcquirerLogoEnumModel
    var acqName: String = ""

    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.printresult)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Card Transaction Details"
        )
        helperLog.appendLine(helperLogClassName, "Card transaction details opened")
        receipt = findViewById(R.id.receipt)
        mainReceipt = findViewById(R.id.mainReceipt)
        headerImageView = findViewById(R.id.headerImageView)
        (findViewById<View>(R.id.printTV) as TextView).text = "Print Receipt"
        findViewById<LinearLayout>(R.id.cancelBtn).setDebouncedOnClickListener {
            customOnBackPress()
        }
        findViewById<LinearLayout>(R.id.printBtn).setDebouncedOnClickListener {
            printReceipt()
        }

        acqName = ServiceHolder.getAcquirerSetting().acqName.uppercase(Locale.getDefault())
        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        if (dbModelMerchantConfig != null) {
            acquirerLogoConfig = Gson().fromJson(getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)
        }
        acqLogoEnumModel = com.sc.mf919.kotlin.helper_common.Helper.getInstance().checkConfigOverrideLogo(
            AcquirerLogoDataEnum.from(dbModelMerchantConfig?.AcqCode)?.data ?: AcquirerLogoDataEnum.BLANK.data
        )

        onBackPressedDispatcher.addCallback(this@TransactionViewCardDetailsActivity, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })

        stan = intent.getStringExtra("stan")
        dbModelPrintReceipt = PrintReceiptRepo.getSingle(applicationContext, listOf("stan"), listOf(stan ?: ""))
        println("dbModelPrintReceipt :: ${dbModelPrintReceipt.toString()}")
        var msg = dbModelPrintReceipt?.receiptInfo ?: ""
        msg = msg.replace("[", "").replace("]", "").replace(", ", "\n")
        if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "Receipt fields :: $msg")
        val msg_1 = Utils.String2ArrayString(msg)
        formLayout(msg_1)
    }

    @SuppressLint("MissingSuperCall")
    override fun onSaveInstanceState(outState: Bundle) {
        // Do NOT call super to avoid saving a huge view hierarchy.
        // We can fully reconstruct UI from 'stan' + DB in onCreate().
        // super.onSaveInstanceState(outState)
    }

    private fun formLayout(details: Array<String?>) {
        mainReceipt.removeAllViews()
        var powerByBmp: Bitmap? = null
        val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
            if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "Power-by logo loaded from internal storage")
        }

        val tags = arrayOf(
            "MERCHANT ID", "TERMINAL ID", "BATCH NO", "", "APP LABEL", "CARD NO", "DATE/TIME",
            "INV NO", "TRACE NO", "ENTRY TYPE", "REF NO", "APPROVAL CODE", "CASHOUT AMOUNT", "TOTAL", "", "ARQC",
            "AID", "TVR", ""
        )
        if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || dbModelPrintReceipt?.isTpaAccount?.lowercase() == "true") {
            if(powerByBmp != null) {
                headerImageView.setImageBitmap(powerByBmp)
            } else {
                headerImageView.setImageResource(R.mipmap.logo)
            }
        } else {
            headerImageView.setImageResource(acqLogoEnumModel.HeaderLogoPng)

        }

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
        mainReceipt.addView(name)
        mainReceipt.addView(addr)
        for (j in details.indices) {
            if (details[j] == null || details[j] == "") {
                continue
            }

            when (j) {
                3 -> {
                    val ll = LinearLayout(applicationContext)
                    ll.orientation = LinearLayout.HORIZONTAL
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params1.setMargins(5, 0, 5, 0)
                    params1.weight = 1f
                    val tv = TextView(applicationContext)
                    tv.layoutParams = params1
                    tv.textSize = 14f
                    tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    tv.setTypeface(tv.typeface, Typeface.BOLD)
                    tv.setTextColor(resources.getColor(R.color.black))
                    tv.text = details[j]
                    ll.addView(tv)
                    mainReceipt.addView(ll)
                }
                5 -> {
                    val ll = LinearLayout(applicationContext)
                    ll.orientation = LinearLayout.VERTICAL
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params1.setMargins(5, 0, 5, 0)
                    val tv = TextView(applicationContext)
                    tv.layoutParams = params1
                    tv.textSize = 11f
                    tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv.setTextColor(resources.getColor(R.color.black))
                    tv.text = tags[j]
                    val tv1 = TextView(applicationContext)
                    tv1.layoutParams = params1
                    tv1.textSize = 14f
                    tv1.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                    tv1.setTextColor(resources.getColor(R.color.black))
                    tv1.text = details[j]
                    ll.addView(tv)
                    ll.addView(tv1)
                    mainReceipt.addView(ll)
                }
                12 -> {
                    if (details[j] != "0") {
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
                        tv.text = tags[j]
                        val tv1 = TextView(applicationContext)
                        tv1.layoutParams = params1
                        tv1.textSize = 11f
                        tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                        tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                        tv1.setTextColor(resources.getColor(R.color.black))
                        tv1.text = "RM" + details[j]
                        ll.addView(tv)
                        ll.addView(tv1)
                        mainReceipt.addView(ll)
                    }
                }
                13 -> {
                    val ll = LinearLayout(applicationContext)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    ll.orientation = LinearLayout.VERTICAL
                    ll.layoutParams = params
                    val tv4 = TextView(applicationContext)
                    tv4.textSize = 11f
                    tv4.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv4.setTextColor(resources.getColor(R.color.black))
                    tv4.text = "------------------------------------------------------------------"
                    val tv3 = TextView(applicationContext)
                    tv3.textSize = 11f
                    tv3.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv3.setTextColor(resources.getColor(R.color.black))
                    tv3.text = "------------------------------------------------------------------"
                    val ll_1 = LinearLayout(applicationContext)
                    ll_1.orientation = LinearLayout.HORIZONTAL
                    ll_1.weightSum = 2f
                    val params2 = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params2.setMargins(5, 0, 5, 0)
                    params2.weight = 1f
                    val tv2 = TextView(applicationContext)
                    tv2.layoutParams = params2
                    tv2.textSize = 14f
                    tv2.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv2.setTypeface(tv2.typeface, Typeface.BOLD)
                    tv2.setTextColor(resources.getColor(R.color.black))
                    tv2.text = tags[j]
                    val tv1 = TextView(applicationContext)
                    tv1.layoutParams = params2
                    tv1.textSize = 14f
                    tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                    tv1.setTextColor(resources.getColor(R.color.black))
                    tv1.text = "RM ${details[j]}"
                    ll_1.addView(tv2)
                    ll_1.addView(tv1)
                    ll.addView(tv4)
                    ll.addView(ll_1)
                    ll.addView(tv3)
                    mainReceipt.addView(ll)
                }
                14 -> {
                    var eppDetails = arrayOfNulls<String>(4)
                    var eppTags = arrayOfNulls<String>(4)
                    when (acqName) {
                        "BSN_CARDZONE" -> {
                            eppDetails = parseEppDetailsReceipt(details[j]!!, true)
                            eppTags = arrayOf("TENURE", "FIRST AMT", "MTHLY AMT", "TOTAL AMT")
                        }
                        "BSN" -> {
                            eppDetails = parseEppDetailsReceipt(details[j]!!, false)
                            eppTags = arrayOf("TENURE", "TOTAL DUE", "MTHLY AMT", "FINAL AMT")
                        }
                    }

                    val ll = LinearLayout(applicationContext)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    ll.orientation = LinearLayout.VERTICAL
                    ll.layoutParams = params

                    val tv3 = TextView(applicationContext)
                    tv3.textSize = 11f
                    tv3.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv3.setTextColor(resources.getColor(R.color.black))
                    tv3.text = "------------------------------------------------------------------"

                    for (k in eppDetails.indices) {
                        val ll_1 = LinearLayout(applicationContext)
                        ll_1.orientation = LinearLayout.HORIZONTAL
                        ll_1.weightSum = 2f
                        val params2 = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        params2.setMargins(5, 0, 5, 0)
                        params2.weight = 1f
                        val tv2 = TextView(applicationContext)
                        tv2.layoutParams = params2
                        tv2.textSize = 11f
                        tv2.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        tv2.setTextColor(resources.getColor(R.color.black))
                        tv2.text = eppTags[k]
                        val tv1 = TextView(applicationContext)
                        tv1.layoutParams = params2
                        tv1.textSize = 11f
                        tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                        tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                        tv1.setTextColor(resources.getColor(R.color.black))
                        if (k == 0) {
                            tv1.text = eppDetails[k]?.let { String.format(it) }
                        } else {
                            tv1.text = "RM" + eppDetails[k]
                        }
                        ll_1.addView(tv2)
                        ll_1.addView(tv1)
                        ll.addView(ll_1)
                    }

                    ll.addView(tv3)
                    mainReceipt.addView(ll)
                }
                18 -> {
                    val ll = LinearLayout(applicationContext)
                    ll.orientation = LinearLayout.HORIZONTAL
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params1.setMargins(5, 0, 5, 0)
                    params1.weight = 1f
                    val tv = TextView(applicationContext)
                    tv.layoutParams = params1
                    tv.textSize = 9f
                    tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    tv.setTypeface(tv.typeface, Typeface.BOLD)
                    tv.setTextColor(resources.getColor(R.color.black))
                    tv.text = Utils.CVMAnalysis(details[j], "")
                    ll.addView(tv)
                    mainReceipt.addView(ll)
                }
                else -> {
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
                    mainReceipt.addView(ll)
                }
            }
        }
        val footer = LinearLayout(applicationContext)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 5, 0, 0)
        params.gravity = Gravity.CENTER
        footer.orientation = LinearLayout.VERTICAL
        footer.layoutParams = params
        footer.setPadding(0, 0, 0, 20)
        val power1 = TextView(applicationContext)
        power1.textSize = 7.5f
        power1.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power1.setTextColor(resources.getColor(R.color.black))
        power1.text = "I AGREE TO PAY THE ABOVE TOTAL AMOUNT ACCORDING TO THE CARD ISSUER AGREEMENT"
        footer.addView(power1)
        val power = TextView(applicationContext)
        power.textSize = 9f
        power.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
        power.setTextColor(resources.getColor(R.color.black))

        val im = ImageView(applicationContext)
        val params1 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, Helper.getInstance().getDpValue(30)
        )
        params1.gravity = Gravity.CENTER
        im.layoutParams = params1
        im.adjustViewBounds = true


        if(!isHideBottom) {
            if(dbModelPrintReceipt?.isTpaAccount?.lowercase() == "true") {
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

    private fun printInfo(details: Array<String>) {
        val list: MutableList<MulPrintStrEntity> = ArrayList()
        val fontSize = FontFamily.MIDDLE
        val tags = arrayOf(
            "MERCHANT ID", "TERMINAL ID", "BATCH NO", "", "APP LABEL", "CARD NO", "DATE/TIME",
            "INV NO", "TRACE NO", "ENTRY TYPE", "REF NO", "APPROVAL CODE", "CASHOUT AMOUNT", "TOTAL", "", "ARQC", "AID",
            "TVR", ""
        )

        val acquirerBmp = PrintsActivity.getImageFromAssetsFile(applicationContext, acqLogoEnumModel.HeaderLogoBmp)
        var powerByBmp = PrintsActivity.getImageFromAssetsFile(applicationContext, "image/logo_footer_small.bmp")
        val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
        }

        var entity = MulPrintStrEntity("", FontFamily.BIG)
        entity.bitmap = if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || dbModelPrintReceipt?.isTpaAccount?.lowercase() == "true") powerByBmp else acquirerBmp
        entity.marginX = 50
        entity.gravity = Gravity.CENTER
        entity.isUnderline = true
        entity.yspace = 30
        list.add(entity)

        list.add(MulPrintStrEntity("  ", FontFamily.SMALL, false, Gravity.CENTER))
        var mulPrintStrEntity = MulPrintStrEntity(
            getSafeValue(dbModelMerchantConfig, "MerchantName"),
            FontFamily.SMALL, false,
            Gravity.CENTER
        )
        mulPrintStrEntity.yspace = 15
        list.add(mulPrintStrEntity)
        mulPrintStrEntity = MulPrintStrEntity(
            getSafeValue(dbModelMerchantConfig, "MerchantAddress"),
            FontFamily.SMALL, false,
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

        if(details[4].isNotEmpty()){
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

        if(details[14].isNotEmpty()){
            var eppDetails = arrayOfNulls<String>(4)
            var eppTags = arrayOfNulls<String>(4)
            when (acqName) {
                "BSN_CARDZONE" -> {
                    eppDetails = parseEppDetailsReceipt(details[14], true)
                    eppTags = arrayOf("TENURE", "FIRST AMT", "MTHLY AMT", "TOTAL AMT")
                }
                "BSN" -> {
                    eppDetails = parseEppDetailsReceipt(details[14], false)
                    eppTags = arrayOf("TENURE", "TOTAL DUE", "MTHLY AMT", "FINAL AMT")
                }
            }
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(eppTags[0]).setFont(fontSize), TextItem(
                            eppDetails[0]
                        ).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            for (k in 1 until eppDetails.size) {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(
                                eppTags[k]
                            ).setFont(fontSize), TextItem("RM" + eppDetails[k]).setFont(fontSize)
                                .setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
            }
            list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
        }

        if(details[15].isNotEmpty()){
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

        if(details[16].isNotEmpty()){
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

        if(details[17].isNotEmpty()){
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
                Utils.CVMAnalysis(details[18], ""), FontFamily.MIDDLE, false, Gravity.CENTER
            ).setIsBold(Typeface.BOLD)
        )
        list.add(
            MulPrintStrEntity(
                "I AGREE TO PAY THE ABOVE TOTAL AMOUNT ACCORDING TO THE CARD ISSUER AGREEMENT",
                FontFamily.SMALL, false, Gravity.CENTER
            )
        )
        list.add(
            MulPrintStrEntity(
                "***** DUPLICATE COPY *****", FontFamily.MIDDLE, false, Gravity.CENTER
            ).setIsBold(Typeface.BOLD).setYspace(10)
        )

        if(!isHideBottom) {
            if(dbModelPrintReceipt?.isTpaAccount?.lowercase() == "true") {
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
        print(list)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun printReceipt() = CoroutineScope(Dispatchers.Main).launch{
        print_receipt()
    }

    suspend fun print_receipt() {
        val msg = withContext(Dispatchers.IO) {
            val data = PrintReceiptRepo.getSingle(applicationContext, listOf("stan"), listOf(stan ?: ""))?.receiptInfo ?: ""
            data.replace("[", "").replace("]", "").replace(", ", "\n")
        }

        val animSlideDown = AnimationUtils.loadAnimation(applicationContext, R.anim.receipt_slide_up)
        receipt.startAnimation(animSlideDown)

        if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "Selected :: Print receipt [CARD TXN DETAILS]")
        if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
        val stringMessage = Utils.String2ArrayString(msg)
        printInfo(stringMessage)
    }

    fun print_receipt_cancel(view: View?) {
        customOnBackPress()
    }

    fun customOnBackPress() {
        try {
            var intent = Intent(applicationContext, TransactionViewListActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "TransactionViewCardDetailsActivity OnDestroy :: details screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}
