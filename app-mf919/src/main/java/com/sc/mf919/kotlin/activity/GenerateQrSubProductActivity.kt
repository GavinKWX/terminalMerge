package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.helper_common.*
import data_enum.SalesModel
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog

class GenerateQrSubProductActivity : ActivityBase() {
    lateinit var mContext: Context
    lateinit var productCat: String
    lateinit var paymentCode: String
    lateinit var containerLinear: LinearLayout

    private var helperlogClassName:String = ""
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    var txnAmt: String? = null
    var denominationType: String? = null
    var denominationProduct: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mContext = this
        helperlogClassName = this::class.qualifiedName.toString()
        setContentView(R.layout.activity_subproductsel)
        productCat = intent.getStringExtra("product_cat") ?: ""

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbarCP)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener {
            customOnBackPress()
        }
        val toolbarTextView: TextView = findViewById<View>(R.id.toolbarTV) as TextView
        toolbarTextView.text = productCat.replace("_", " ")
        containerLinear = findViewById(R.id.scrollLinearCon)

        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Generate QR Sub-Product Selection"
        )
        // Same instance the click listeners and customOnBackPress use, so the whole selection
        // flow shares one RowIdentifier instead of splitting across two logs.
        val log = helperLog
        log.appendLine(helperLogClassName, "Sub-product selection opened :: $productCat")

        paymentCode = intent.getStringExtra("paymentCode") ?: ""
        if (paymentCode.isNotEmpty()){
            txnAmt = if (intent.getStringExtra("txnAmt") == null) "0.00" else intent.getStringExtra("txnAmt")!!
            denominationType = intent.getStringExtra("denomination_type")
            denominationProduct = intent.getStringExtra("denomination_product")
            if (denominationNavigation(log)) {
                // Already navigated to GenerateQrActivity and finished this activity.
                return
            }
        }

        renderDynamicProduct(log)
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "User Cancel :: abandoned $productCat sub-product selection, navigate -> home screen")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "GenerateQrSubProduct OnDestroy :: sub-product selection ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    private fun renderDynamicProduct(log: helpers.HelperLog) {
        val subProductList = ProductListRepo.getSelectedProduct(
            mContext,
            mutableListOf("Product"),
            arrayOf(productCat)
        )
        if (subProductList.isEmpty()) {
            log.appendLine(helperlogClassName, "REJECT :: no active sub-product for $productCat")
            log.logToFile(EnumLogFileName.TerminaLog)
            return
        }

        if (subProductList.size == 1) {
            val modelData = subProductList.first()

            val salesModel = SalesModel(
                20,
                modelData.Product,
                modelData.AcqCode,
                modelData.AcqMid,
                modelData.AcqTid,
                modelData.QrProductCode,
                modelData.ProductName,
                modelData.EppProductCode,
                modelData.EppTenure,
                modelData.EppTenureCode
            )
            ServiceHolder.selectedCacheModel = salesModel
            log.appendLine(helperlogClassName, "Selected :: ${modelData.ProductName} [$productCat] (only sub-product, auto-selected)")
            log.appendLine(helperlogClassName, "Validation passed :: navigate -> KeypadActivity")
            log.logToFile(EnumLogFileName.TerminaLog)
            val intent = Intent(mContext, KeypadActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
            // Same fall-through as above, one scope down: without this the render loop below
            // builds the full list onto an activity that is already finishing.
            return
        }

        try {
            for (i in subProductList.indices) {
                val modelData = subProductList[i]

                val itemLinear = LinearLayout(mContext)
                val linearParam = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Helper.getInstance().getDpValue(60)
                )
                linearParam.setMargins(
                    Helper.getInstance().getDpValue(20),
                    0,
                    Helper.getInstance().getDpValue(20),
                    0
                )
                itemLinear.layoutParams = linearParam
                itemLinear.orientation = LinearLayout.VERTICAL
                itemLinear.gravity = Gravity.CENTER_VERTICAL
                itemLinear.setBackgroundResource(R.drawable.divider)

                val tv = TextView(mContext)
                tv.text = modelData.ProductName
                tv.gravity = Gravity.START
                tv.setTextColor(ContextCompat.getColor(mContext, R.color.black))
                tv.textSize = 20f
                val paramsTV = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                tv.layoutParams = paramsTV

                itemLinear.setOnClickListener {
                    val salesModel = SalesModel(
                        20,
                        modelData.Product,
                        modelData.AcqCode,
                        modelData.AcqMid,
                        modelData.AcqTid,
                        modelData.QrProductCode,
                        modelData.ProductName,
                        modelData.EppProductCode,
                        modelData.EppTenure,
                        modelData.EppTenureCode
                    )
                    ServiceHolder.selectedCacheModel = salesModel
                    log.appendLine(helperlogClassName, "Selected :: ${modelData.ProductName} [$productCat]")
                    log.appendLine(helperlogClassName, "Validation passed :: navigate -> KeypadActivity")
                    log.logToFile(EnumLogFileName.TerminaLog)
                    val intent = Intent(mContext, KeypadActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                }

                itemLinear.addView(tv)
                containerLinear.addView(itemLinear)
            }
        } catch (e: Exception) {
            log.appendLine(helperlogClassName, "Exception in Rendering Product List -> ", e.toString())
            log.logToFile(EnumLogFileName.TerminaLogException)
            e.printStackTrace()
        }
    }

    /** @return true when it navigated to GenerateQrActivity (and finished this activity). */
    private fun denominationNavigation(log: helpers.HelperLog): Boolean {
        log.appendLine(helperlogClassName, "denominationNavigation >> $paymentCode")
        val specificQrGenModel = ProductListRepo.getSingle(
            mContext,
            listOf("Product", "QrProductCode", "IsActive"),
            arrayOf(ProductCatSelectionDataEnum.GENERATE_QR.name, paymentCode, "true"))

        if (specificQrGenModel == null) {
            log.appendLine(helperlogClassName, "REJECT :: no active GENERATE_QR product for paymentCode $paymentCode")
            log.logToFile(EnumLogFileName.TerminaLog)
            return false
        }

        val (_, _, SalesType) = ProductCatSelectionDataEnum.valueOf("GENERATE_QR").data
        val salesModel = SalesModel(
            SalesType,
            specificQrGenModel.Product,
            specificQrGenModel.AcqCode,
            specificQrGenModel.AcqMid,
            specificQrGenModel.AcqTid,
            specificQrGenModel.QrProductCode,
            specificQrGenModel.ProductName,
            specificQrGenModel.EppProductCode,
            specificQrGenModel.EppTenure,
            specificQrGenModel.EppTenureCode
        )
        ServiceHolder.selectedCacheModel = salesModel
        log.appendLine(helperlogClassName, "Selected :: ${specificQrGenModel.ProductName} [$paymentCode] (denomination)")
        log.appendLine(helperlogClassName, "Amount entered :: $txnAmt")
        log.appendLine(helperlogClassName, "Validation passed :: navigate -> GenerateQrActivity")
        log.logToFile(EnumLogFileName.TerminaLog)
        val intent = Intent(mContext, GenerateQrActivity::class.java)
        intent.putExtra("txnAmt", txnAmt)
        intent.putExtra("denomination_type", denominationType)
        intent.putExtra("denomination_product", denominationProduct)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
        return true
    }
}