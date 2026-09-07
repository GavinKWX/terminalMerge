package com.sc.mf919.kotlin.activity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.*
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
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
import com.sc.mf919.kotlin.database.model.DbModelTransactionQrGet
import com.sc.mf919.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import data_enum.SalesModel
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import java.io.File
import java.util.ArrayList

class TransactionViewQrDetailsActivity : ActivityBase() {
    private val isHideBottom get() = com.sc.mf919.BuildConfig.hide_bottom
    lateinit var mainReceipt: LinearLayout
    lateinit var receipt: ScrollView
    lateinit var topLogoView: ImageView
    lateinit var acqLogoEnumModel: AcquirerLogoEnumModel

    var selectedSalesModel: SalesModel? = null
    var dbModelMerchantConfig: DbModelMerchantConfig? = null
    private var helperlogClassName:String = ""
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog
    private var refId: String = ""
    private var transactionQrGet: DbModelTransactionQrGet? = null
    var details: MutableMap<String, Any> = mutableMapOf()
    var isUPITxn: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printresult_qr)
        helperlogClassName = this::class.qualifiedName.toString()

        mainReceipt = findViewById(R.id.mainReceipt)
        receipt = findViewById(R.id.receipt)
        topLogoView = findViewById(R.id.headerImageView)
        (findViewById<View>(R.id.printTV) as TextView).text = "Print Receipt"

        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "QR Transaction Receipt View / Reprint"
        )
        helperLog.appendLine(helperLogClassName, "QR transaction receipt view opened")

        onBackPressedDispatcher.addCallback(this@TransactionViewQrDetailsActivity, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })

        dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        acqLogoEnumModel = AcquirerLogoDataEnum.BLANK.data
        refId = intent.getStringExtra("refId") ?: ""
        helperLog.appendLine(helperLogClassName, "Loading QR receipt :: refId ${if (refId.isEmpty()) "-" else refId}")
        try{
            transactionQrGet = TransactionQrRepo.getSingleTransactionQr(applicationContext, listOf("refId"), listOf(refId))
            if (transactionQrGet == null) {
                helperLog.appendLine(helperLogClassName, "REJECT :: no QR transaction found for refId ${if (refId.isEmpty()) "-" else refId}")
            }
            transactionQrGet?.let {
                acqLogoEnumModel = com.sc.mf919.kotlin.helper_common.Helper.getInstance().checkConfigOverrideLogo(
                    AcquirerLogoDataEnum.from(it.acqCode)?.data ?: AcquirerLogoDataEnum.BLANK.data
                )
                val txnDateTime = if(it.txnType?.trim() == "Void") it.voidDateTime ?: "" else it.txnDateTime ?: ""
                details["DATE/TIME"] = txnDateTime
                details["E-WALLET"] = it.productName ?: ""
                details["TXN TYPE"] = it.txnType ?: ""
                //details["MID"] = it.mid ?: ""
                //details["TID"] = it.tid ?: ""
                //details["APPROVAL CODE"] = it.approvalCode ?: ""
                details["HOST REF"] = it.hostRefNo ?: ""
                if (it?.isUnionPayTxn == "1") {
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
                helperLog.appendLine(helperLogClassName, "QR receipt loaded :: refId ${it.refId ?: "-"}, " +
                    "txnType ${it.txnType ?: "-"}, brand ${it.productName ?: "-"}, respCode ${it.respCode ?: "-"}")
                formLayout()
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            helperLog.appendLine(helperLogClassName, "Exception loading QR receipt -> ${ex.message ?: "-"}")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun formLayout() {
        var powerByBmp: Bitmap? = null
        val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
        }

        if(transactionQrGet?.isTpaAccount == "true") {
            if(powerByBmp != null) {
                topLogoView.setImageBitmap(powerByBmp)
            } else {
                topLogoView.setImageResource(R.mipmap.logo)
            }
        } else {
            topLogoView.setImageResource(acqLogoEnumModel.HeaderLogoPng)
        }

        mainReceipt.removeAllViews()
        //val tags = arrayOf("DATE/TIME", "E-WALLET", "TXN TYPE", "MID", "TID", "APPROVAL CODE", "HOST REF", "AMOUNT", "REF ID")
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

        details.forEach{(key, value) ->
            val keyValue = value.toString()
            if (keyValue.trim().isEmpty()) {
                return@forEach
            }

            val ll = LinearLayout(applicationContext)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            ll.orientation = LinearLayout.HORIZONTAL
            ll.weightSum = 2f
            val params1 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params1.setMargins(5, 0, 5, 0)
            params1.weight = 1f
            val tv = TextView(applicationContext)
            tv.layoutParams = params1
            tv.textSize = 11f
            tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            tv.setTextColor(resources.getColor(R.color.black))
            tv.text = key
            val tv1 = TextView(applicationContext)
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
                val ll2 = LinearLayout(applicationContext)
                val params2 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
                ll2.orientation = LinearLayout.VERTICAL
                ll2.layoutParams = params2
                val tvDashed = TextView(applicationContext)
                tvDashed.textSize = 11f
                tvDashed.layoutParams = params1
                tvDashed.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tvDashed.setTextColor(ContextCompat.getColor(applicationContext, R.color.black))
                tvDashed.setSingleLine(true)
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val dashWidth = tvDashed.paint.measureText("-")
                val dashCount = (screenWidth / dashWidth).toInt()
                tvDashed.text = "-".repeat(dashCount)

                if (key == "REF ID" || key == "TOTAL AMT (MYR)") {
                    ll2.addView(tvDashed)
                    mainReceipt!!.addView(ll2)
                }
            }
        }

        val footer = LinearLayout(applicationContext)
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, 5, 0, 0)
        params.gravity = Gravity.CENTER
        footer.orientation = LinearLayout.VERTICAL
        footer.layoutParams = params
        footer.setPadding(0, 0, 0, 20)
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
        val im = ImageView(applicationContext)
        val params1 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Helper.getInstance().getDpValue(30))
        params1.gravity = Gravity.CENTER
        im.layoutParams = params1
        im.adjustViewBounds = true

        if(!isHideBottom) {
            if(transactionQrGet?.isTpaAccount == "true") {
                power.text = "Behind Every Payment, There's A Smile."
                footer.addView(power)
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
        mainReceipt.addView(footer)
    }

    private fun printInfo() {
        val list: MutableList<MulPrintStrEntity> = ArrayList()
        val fontSize = FontFamily.MIDDLE
        val acquirerBmp = PrintsActivity.getImageFromAssetsFile(applicationContext, acqLogoEnumModel.HeaderLogoBmp)
        var powerByBmp = PrintsActivity.getImageFromAssetsFile(applicationContext, "image/logo_footer_small.bmp")
        val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
        }

        var entity = MulPrintStrEntity("", fontSize)
        entity.bitmap = if(transactionQrGet?.isTpaAccount == "true") powerByBmp else acquirerBmp
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
            MulPrintStrEntity(
                "I AGREE TO PAY THE ABOVE TOTAL AMOUNT", FontFamily.SMALL, false, Gravity.CENTER
            )
        )
        list.add(
            MulPrintStrEntity(
                "***** DUPLICATE COPY *****", FontFamily.MIDDLE, false, Gravity.CENTER
            ).setIsBold(Typeface.BOLD).setYspace(10)
        )

        if(!isHideBottom) {
            if(transactionQrGet?.isTpaAccount == "true") {
                list.add(MulPrintStrEntity("Behind Every Payment, There's A Smile.", FontFamily.SMALL, false, Gravity.CENTER))
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
        entity.yspace = 80
        list.add(entity)
        print(list)
    }

    fun print_receipt(view: View?) {
        helperLog.appendLine(helperLogClassName, "Selected :: Print Receipt [QR RECEIPT VIEW] :: refId ${if (refId.isEmpty()) "-" else refId}")
        val animSlideDown = AnimationUtils.loadAnimation(applicationContext, R.anim.receipt_slide_up)
        receipt.startAnimation(animSlideDown)
        //val msg = isoDb.getPrintReceiptQrTable(refId)
        //Utils.debugLogPrint("TAG", "onItemSelected:  " + Arrays.toString(msg))
        printInfo()
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    fun print_receipt_cancel(view: View?) {
        helperLog.appendLine(helperLogClassName, "Cancel button pressed")
        customOnBackPress()
    }

    fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "User Cancel :: closed QR receipt view, navigate -> TransactionViewListQrActivity")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        try {
            val intent = Intent(applicationContext, TransactionViewListQrActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            helperLog.appendLine(helperLogClassName, "Exception navigating back to QR history -> ${e.message ?: "-"}")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "TransactionViewQrDetails OnDestroy :: QR receipt view ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}
