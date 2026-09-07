package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919.kotlin.database.model.DbModelDenominationList
import com.sc.mf919.kotlin.database.model.DbModelProductListGet
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.helper_common.AppBus
import com.sc.mf919.kotlin.helper_common.HTTPServer
import mdb.MdbController
import com.sc.mf919.kotlin.helper_common.UiEvent
import com.sc.mf919.kotlin.helper_common.HTTPServer.attendActivityContext
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumDenominationType
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DenominationPaymentOptionActivity: ActivityBase() {
    lateinit var mContext: Context
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    lateinit var productTextView: TextView
    lateinit var optionList: LinearLayout

    var dbModelDenominationList: DbModelDenominationList? = null
    var dbModelProductModel: DbModelProductListGet? = null

    private var timeSelected : Int = 30
    private var timeCountDown: CountDownTimer? = null
    private var timeProgress = 0
    //private var pauseOffSet: Long = 0
    //private var isStart = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_denomination)
        val toolbar = findViewById<Toolbar>(R.id.toolbarDeno)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: toolbar back pressed, abandoning payment option selection")
            timeCountDown?.let {
                it.cancel()
                timeCountDown = null
            }
            customOnBackPress()
        }
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, abandoning payment option selection")
                timeCountDown?.let {
                    it.cancel()
                    timeCountDown = null
                }
                customOnBackPress()
            }
        })

        mContext = this@DenominationPaymentOptionActivity
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Initialize Denomination Payment Option Activity"
        )
        productTextView = findViewById(R.id.linearProductTV)
        val tempDenomination = intent.getStringExtra("denomination_product")
        dbModelDenominationList = Gson().fromJson(tempDenomination, DbModelDenominationList::class.java)
        dbModelDenominationList?.let {
            /*var amountDisplay = it.Amount
            if(amountDisplay.contains(".")) {
                val tempList = amountDisplay.split(".")
                amountDisplay = tempList.first()
            }*/
            var amountDisplay = it.Amount
            if(amountDisplay.contains(".")) {
                val tempList = amountDisplay.split(".")
                amountDisplay = if (tempList[1] == "00") {
                    // remove decimals
                    tempList[0]
                } else {
                    // keep original
                    amountDisplay
                }
            }

            if(it.Desc.isNotEmpty()) {
                productTextView.text = "RM$amountDisplay = ${it.Desc}"
            } else {
                productTextView.text = "RM$amountDisplay"
            }
            helperLog.appendLine(helperLogClassName, "Denomination selected :: ${it.Desc.ifEmpty { "-" }} :: Amount entered :: ${it.Amount}")
        }
        optionList = findViewById(R.id.optionList)

        // React immediately when the VMC aborts the vend (reset / vend cancel / reader disable)
        // instead of waiting for the selection timer or a back-press.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppBus.uiEvents.collect { event ->
                    if (event is UiEvent.MdbVendingForceEnd) {
                        helperLog.appendLine(helperLogClassName, "Vend ABORTED by VMC :: force end received on payment option screen")
                        runOnUiThread { onMdbVendingForceEnd() }
                    }
                }
            }
        }

        startTimer(0)
        lifecycleScope.launch {
            val modelProductLists = ProductListRepo.getAll(mContext)
            val filteredList = modelProductLists.filter { item ->
                item.Product == "CARD_SETTINGS" || (item.Product == "GENERATE_QR" && item.QrProductCode == "QR_DUITNOW")
            }
            if(filteredList.size == 1 ) {
                timeCountDown?.let {
                    it.cancel()
                    timeCountDown = null
                }

                val item = filteredList.first()
                val autoSelectProductModel = DbModelProductListGet(
                    Id = "",
                    Product = item.Product,
                    AcqCode = item.AcqCode,
                    AcqMid = item.AcqMid,
                    AcqTid = item.AcqTid,
                    QrProductCode = item.QrProductCode,
                    ProductName = item.ProductName,
                    EppProductCode = item.EppProductCode,
                    EppTenure = item.EppTenure,
                    EppTenureCode = item.EppTenureCode,
                    IsSettlement = item.IsSettlement,
                    IsSettled = item.IsSettled,
                    BatchNo = item.BatchNo,
                    IsActive = item.IsActive,
                    Ksn = item.Ksn,
                    PinKsn = item.PinKsn,
                    IsTpaAccount = item.IsTpaAccount
                )
                helperLog.appendLine(helperLogClassName, "Selected :: ${item.Product} (only method available, auto-selected) [PAYMENT OPTION]")
                helperLog.appendLine(helperLogClassName, "Proceed AutoSelect Payment Method")
                productNavigation(autoSelectProductModel)
            } else {
                renderDynamicProduct()
            }
        }
    }

    fun productNavigation(productSelected: DbModelProductListGet?) {
        dbModelProductModel = productSelected
        val amountDisplay = dbModelDenominationList?.Amount
        val amountInt = Utils.atoi(amountDisplay)
        helperLog.appendLine(helperLogClassName, "Payment method chosen :: ${productSelected?.Product} :: amount=$amountDisplay")
        if(dbModelProductModel?.Product == ProductCatSelectionDataEnum.CARD_SETTINGS.name) {
            helperLog.appendLine(helperLogClassName, "Obtaining Product List >> ", dbModelProductModel.toString())
            val salesModel = SalesModel(
                ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType,
                dbModelProductModel?.Product ?: "",
                dbModelProductModel?.AcqCode ?: "",
                dbModelProductModel?.AcqMid ?: "",
                dbModelProductModel?.AcqTid ?: "",
                dbModelProductModel?.QrProductCode ?: "",
                dbModelProductModel?.ProductName ?: "",
                dbModelProductModel?.EppProductCode ?: "",
                dbModelProductModel?.EppTenure ?: "",
                dbModelProductModel?.EppTenureCode ?: ""
            )
            ServiceHolder.selectedCacheModel = salesModel
            val jsonProductList = Gson().toJson(dbModelProductModel)
            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
            if(MdbController.mdbVending) {
                val hexPrice = String.format("%04x", amountInt)
                MdbController.priceHex = hexPrice
                MdbController.priceBytes = Utils.hexStringToByteArray(hexPrice)
            } else {
                saleModelNew.DenominationType = EnumDenominationType.TokenDispense.value
                saleModelNew.DenominationProduct = dbModelDenominationList
            }
            ServiceHolder.saleModelCache = saleModelNew
            helperLog.appendLine(helperLogClassName, "Update Sale Model :: ", saleModelNew.toString())
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> CardPaymentActivity")
            helperLog.logToFile(EnumLogFileName.TerminaLog)

            val newIntent = Intent(applicationContext, CardPaymentActivity::class.java)
            newIntent.putExtra("txnAmt", amountDisplay)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(newIntent)
            finish()
        } else {
            val newIntent = Intent(ServiceHolder.mContext, GenerateQrSubProductActivity::class.java)
            newIntent.putExtra("txnAmt", amountDisplay)
            newIntent.putExtra("product_cat", "GENERATE_QR")
            newIntent.putExtra("paymentCode", "QR_DUITNOW")
            if(MdbController.mdbVending) {
                val hexPrice = String.format("%04x", amountInt)
                MdbController.priceHex = hexPrice
                MdbController.priceBytes = Utils.hexStringToByteArray(hexPrice)
            } else {
                newIntent.putExtra("denomination_type", EnumDenominationType.TokenDispense.value)
                newIntent.putExtra("denomination_product", Gson().toJson(dbModelDenominationList))
            }
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> GenerateQrSubProductActivity (QR_DUITNOW)")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            ServiceHolder.mContext.startActivity(newIntent)
            attendActivityContext?.finish()
        }
    }

    private fun optionCheckBoxRow(renderProduct: DbModelProductListGet, title: String, optionLogo: Int) {
        val settleMethod = LinearLayout(mContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Helper.getInstance().getDpValue(80)
            ).apply {
                gravity = Gravity.CENTER
                setMargins(Helper.getInstance().getDpValue(10), 0, Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(20))
            }
            setBackgroundResource(R.drawable.border3)
        }

        val textViewLeft = TextView(applicationContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(10, 0, 0, 0)
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.BoxColor))
        }

        val logoImageView = ImageView(applicationContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                Helper.getInstance().getDpValue(80),
                Helper.getInstance().getDpValue(80),
            )
            adjustViewBounds = true
            setPadding(20, 5, 10,5)
            setImageResource(optionLogo)
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.colorGreyDD))
        }

        // Create CheckedTextView
        val checkedTextView = TextView(mContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                weight = 1.0f
            }
            setPadding(20, 0, 0, 0)
            textSize = 20f
            setTextColor(ContextCompat.getColor(applicationContext, R.color.WordColor))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.colorGreyDD))
        }
        // Create CheckBox
        /*val checkBox = CheckBox(mContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            setPadding(0, 0, 0, 0)
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.aisino_dark_gray))
        }*/

        settleMethod.addView(textViewLeft)
        settleMethod.addView(logoImageView)
        checkedTextView.text = title
        settleMethod.addView(checkedTextView)

        settleMethod.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: $title [PAYMENT OPTION]")
            timeCountDown?.let {
                it.cancel()
                timeCountDown = null
            }
            dbModelProductModel = renderProduct
            productNavigation(renderProduct)
        }
        optionList.addView(settleMethod)
    }

    private suspend fun renderDynamicProduct() {
        helperLog.appendLine(helperLogClassName, "Render Dynamic Product Listing")
        withContext(Dispatchers.Main) {
            startProgressDialog(mContext, "", "Loading...")

            try {
                val dbModelProductModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                dbModelProductModel?.let {
                    optionCheckBoxRow(dbModelProductModel, "Card", R.mipmap.deno_logo_card)
                }

                val generateQrModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "QrProductCode", "IsActive"), arrayOf(ProductCatSelectionDataEnum.GENERATE_QR.name, "QR_DUITNOW", "true"))
                generateQrModel?.let {
                    optionCheckBoxRow(generateQrModel, "DuitNow QR", R.mipmap.deno_logo_duitnow)
                }
            } catch (ex: Exception) {
                helperLog.appendLine(helperLogClassName, "Exception in Rendering :: ", "${ex.message}")
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
                ex.printStackTrace()
            }
            closeProgressDialog()
        }
    }

    private fun startTimer(pauseOffSetL: Long) {
        timeCountDown = object :
            CountDownTimer((timeSelected * 1000).toLong() - pauseOffSetL * 1000, 1000) {
            override fun onTick(p0: Long) {
                timeProgress++
                //pauseOffSet = timeSelected.toLong()- p0/1000
            }

            override fun onFinish() {
                helperLog.appendLine(helperLogClassName, "Payment option selection TIMEOUT :: no method chosen in ${timeSelected}s")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                customOnBackPress()
            }
        }.start()
    }

    fun customOnBackPress() {
        if(MdbController.mdbVending) {
            helperLog.appendLine(helperLogClassName, "Vend DENIED :: payment option abandoned, notifying VMC")
            MdbController.sendVendDenied()
        }
        helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> AttendDenominationActivity")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val newIntent = Intent(applicationContext, AttendDenominationActivity::class.java)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
        finish()
    }

    /**
     * VMC aborted the vend while this option screen was showing. The VMC already got its
     * response (VEND DENIED / reset), and nothing was charged here, so just clear the vend
     * flags and return home - do NOT call sendVendDenied again.
     */
    private fun onMdbVendingForceEnd() {
        if (isFinishing) return
        timeCountDown?.let {
            it.cancel()
            timeCountDown = null
        }
        MdbController.mdbVending = false
        MdbController.mdbVendingForceEnd = false
        helperLog.appendLine(helperLogClassName, "Vend force-end handled :: navigate -> AttendDenominationActivity")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val newIntent = Intent(applicationContext, AttendDenominationActivity::class.java)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "DenominationPaymentOption OnDestroy :: payment option screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        timeCountDown?.let {
            it.cancel()
            timeCountDown = null
        }
    }
}