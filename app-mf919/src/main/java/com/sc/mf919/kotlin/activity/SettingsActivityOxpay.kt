package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.CubeActivity
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelProductListGet
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.dialog.DialogFragmentPasswordCheck
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.TmsHelper.checkIsConnectedWifi
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.getSession
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivityOxpay: ActivityBase(), DialogFragmentPasswordCheck.DialogFragmentPasswordCheckListener  {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog
    lateinit var mContext: Context

    lateinit var cube: CubeActivity
    var productDetail: DbModelProductListGet? = null

    override fun onDialogRequestProgress(isRequest: Boolean, title:String, message:String) {
        if(isRequest){
            startProgressDialog(mContext, title, message)
        } else {
            closeProgressDialog()
        }
    }
    override fun onDialogPositiveClick(dialogType: String) {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "Password check :: pass [PASSWORD CHECK] type=$dialogType")
        }
        if(productDetail != null) {
            val jsonProductList = Gson().toJson(productDetail)
            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
            ServiceHolder.saleModelCache = saleModelNew
        }

        var newIntent = Intent(applicationContext, VoidSaleActivity::class.java)
        if(dialogType == "settlement") {
            newIntent = Intent(applicationContext, SettlementActivity::class.java)
        }
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> ${newIntent.component?.shortClassName}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
        finish()
    }
    override fun onDialogNegativeClick(dialogType: String) {
        // Handle the negative button click
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [PASSWORD CHECK] type=$dialogType")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting_oxpay)
        val toolbar = findViewById<Toolbar>(R.id.appToolbar)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })

        cube = CubeActivity()
        mContext = this@SettingsActivityOxpay
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "OxPay settings / functions menu"
        )
        productDetail = null
        helperLog.appendLine(helperLogClassName, "Initialize Settings Activity")

        //TODO PRINT FUNCTION
        findViewById<TextView>(R.id.textViewPrint).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Print [SETTINGS]")
            lifecycleScope.launch {
                processPrintFunction()
            }
        }

        //TODO VOID FUNCTION
        findViewById<TextView>(R.id.textViewVoid).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Void [SETTINGS]")
            lifecycleScope.launch {
                processVoidFunction()
            }
        }

        //TODO SETTLEMENT FUNCTION
        findViewById<TextView>(R.id.textViewSettlement).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Settlement [SETTINGS]")
            lifecycleScope.launch{
                processSettlementFunction()
            }
        }

        //TODO PRE-AUTH
        findViewById<TextView>(R.id.textViewPreAuth).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Pre-Auth [SETTINGS]")
            val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
            val terminalConfig = ServiceHolder.getTerminalConfig()
            if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "PreAuth")) {
                if (DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqMid") == "") {
                    helperLog.appendLine(helperLogClassName, "REJECT :: AcqMid not configured")
                    helperLog.logToFile(EnumLogFileName.TerminaLog)
                    Toast.makeText(applicationContext, "Please download/configure Terminal Settings at Portal", Toast.LENGTH_SHORT).show()
                } else {
                    selectionDialog_preAuth()
                }
            } else {
                helperLog.appendLine(helperLogClassName, "REJECT :: PreAuth disabled in terminal config")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                Toast.makeText(applicationContext, "PreAuth is disabled", Toast.LENGTH_SHORT).show()
            }
        }

        //TODO ADMIN BUTTON
        findViewById<TextView>(R.id.textViewAdmin).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Admin [SETTINGS]")
            passwordAlertDialog(1, "666666", object : onAlertDialogListener {
                override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
                    if (clickStatus) {
                        if (isOK) {
                            helperLog.appendLine(helperLogClassName, "Password check :: pass [ADMIN PASSWORD]")
                            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> AdminActivity")
                            helperLog.logToFile(EnumLogFileName.TerminaLog)
                            val newIntent = Intent(applicationContext, AdminActivity::class.java)
                            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(newIntent)
                            finish()
                        } else {
                            helperLog.appendLine(helperLogClassName, "REJECT :: password check failed [ADMIN PASSWORD]")
                            helperLog.logToFile(EnumLogFileName.TerminaLog)
                            ToastMake(applicationContext, "Incorrect pin", Toast.LENGTH_SHORT)
                        }
                    }
                }
            })
        }

        //TODO DOWNLOAD CONFIGURATION
        findViewById<TextView>(R.id.textViewConfiguration).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Download Configuration [SETTINGS]")
            lifecycleScope.launch {
                downloadConfiguration()
            }
        }

        //TODO DOWNLOAD INJECTION KEY
        findViewById<TextView>(R.id.textViewInject).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Download Injection Key [SETTINGS]")
            val (acqName) = ServiceHolder.getAcquirerSetting()
            if (acqName.equals("BSN", ignoreCase = true)) {
                helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> BsnKeyDownloadActivity")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                val newIntent = Intent(applicationContext, BsnKeyDownloadActivity::class.java)
                newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(newIntent)
                finish()
            } else {
                lifecycleScope.launch {
                    downloadInjectionKey()
                }
            }
        }

        //TODO SIGN ON
        findViewById<TextView>(R.id.textViewSignOn).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Sign On [SETTINGS]")
            val (acqName) = ServiceHolder.getAcquirerSetting()
            if (acqName.equals("BSN", ignoreCase = true)) {
                helperLog.appendLine(helperLogClassName, "REJECT :: sign on not required for BSN")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                ToastMake(applicationContext, "Sign on is not required", Toast.LENGTH_SHORT)
            } else {
                lifecycleScope.launch {
                    performSignOn()
                }
            }
        }

        //TODO RESET COMMUNICATION PORT
        findViewById<LinearLayout>(R.id.linearResetComPort).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Reset Communication Port [SETTINGS]")
            lifecycleScope.launch {
               resetCommPort()
            }
        }

        //TODO UPLOAD LOG
        findViewById<TextView>(R.id.textViewUploadLog).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Upload Log [SETTINGS]")
            lifecycleScope.launch {
                uploadLogFile()
            }
        }

        //TODO APP INFO
        findViewById<TextView>(R.id.textViewAppInfo).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: App Info [SETTINGS]")
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> AboutActivity")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            val newIntent = Intent(applicationContext, AboutActivity::class.java)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(newIntent)
            finish()
        }

        helperLog.logToFile(EnumLogFileName.TerminaLog)
        dynamicScreenOption()
    }

    private fun dynamicScreenOption() {
        val terminalConfig = ServiceHolder.getTerminalConfig()
        //val motoProduct = getSpecificQrProductItem(ArrayList(listOf("Product")), arrayOf(ProductCatSelectionDataEnum.MOTO.name))
        runOnUiThread {
            /*//MOTO Visibility
            if (motoProduct != null) {
                motoLinearLayout!!.visibility = View.VISIBLE
            } else {
                motoLinearLayout!!.visibility = View.GONE
            }*/

            //PreAuth Visibility
            findViewById<LinearLayout>(R.id.linearPreAuth)
            if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "PreAuth")) {
                findViewById<LinearLayout>(R.id.linearPreAuth).visibility = View.VISIBLE
            } else {
                findViewById<LinearLayout>(R.id.linearPreAuth).visibility = View.GONE
            }
        }
    }

    private suspend fun processPrintFunction() = withContext(Dispatchers.IO) {
        helperLog.appendLine(helperLogClassName, "Process Print Function")
        val merchantInfo = ServiceHolder.getMerchantInfo()
        if (merchantInfo == null) {
            helperLog.appendLine(helperLogClassName, "REJECT :: merchant info missing, terminal not configured")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            runOnUiThread {
                Toast.makeText(applicationContext, "Please download/configure Terminal Settings at Portal", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }

        val prodDetail = ProductListRepo.getSelectedProductEnhanced(applicationContext, listOf("Product"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name))
        if (prodDetail.isEmpty()) {
            helperLog.appendLine(helperLogClassName, "REJECT :: CARD_SETTINGS product details not found")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            runOnUiThread {
                Toast.makeText(applicationContext, "Error While Obtain Product Details", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }
        val (Product, AcqCode, AcqMid, AcqTid, QrProductCode, ProductName, EppProductCode, EppTenure, EppTenureCode) = prodDetail[0]
        val salesModel = SalesModel(
            0,
            Product,
            AcqCode,
            AcqMid,
            AcqTid,
            QrProductCode,
            ProductName,
            EppProductCode,
            EppTenure,
            EppTenureCode
        )
        ServiceHolder.selectedSettlementModel = salesModel

        helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TransactionViewListActivity")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val newIntent = Intent(applicationContext, TransactionViewListActivity::class.java)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
        finish()
    }

    private suspend fun processVoidFunction() = withContext(Dispatchers.IO) {
        helperLog.appendLine(helperLogClassName, "Process Void Function")
        val merchantInfo = ServiceHolder.getMerchantInfo()
        if (merchantInfo == null) {
            helperLog.appendLine(helperLogClassName, "REJECT :: merchant info missing, terminal not configured")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            Toast.makeText(applicationContext, "Please download/configure Terminal Settings at Portal", Toast.LENGTH_SHORT).show()
            return@withContext
        }

        val prodDetail = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name))
        if (prodDetail == null) {
            helperLog.appendLine(helperLogClassName, "REJECT :: CARD_SETTINGS product details not found")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            Toast.makeText(applicationContext, "Error While Obtain Product Details", Toast.LENGTH_SHORT).show()
            return@withContext
        }
        productDetail = prodDetail

        val terminalConfig = ServiceHolder.getTerminalConfig()
        if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "VOID_WITH_PIN")) {
            helperLog.appendLine(helperLogClassName, "Dialog opened :: [PASSWORD CHECK] void requires PIN")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            val dialog = DialogFragmentPasswordCheck.newInstance("")
            dialog.show(supportFragmentManager, "DialogFragmentPasswordCheckVoid")
        } else {
            val jsonProductList = Gson().toJson(productDetail)
            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
            ServiceHolder.saleModelCache = saleModelNew

            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> VoidSaleActivity")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            val newIntent = Intent(applicationContext, VoidSaleActivity::class.java)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            //newIntent.putExtra("typeofSale", 3)
            startActivity(newIntent)
            finish()
        }
    }

    private suspend fun processSettlementFunction() = withContext(Dispatchers.IO) {
        helperLog.appendLine(helperLogClassName, "Process Settlement Function")
        val merchantInfo = ServiceHolder.getMerchantInfo()
        if (merchantInfo == null) {
            helperLog.appendLine(helperLogClassName, "REJECT :: merchant info missing, terminal not configured")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            Toast.makeText(applicationContext, "Please download/configure Terminal Settings at Portal", Toast.LENGTH_SHORT).show()
            return@withContext
        }

        val prodDetail = ProductListRepo.getSelectedProductEnhanced(applicationContext, listOf("Product"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name))
        if (prodDetail.isEmpty()) {
            helperLog.appendLine(helperLogClassName, "REJECT :: CARD_SETTINGS product details not found")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            Toast.makeText(applicationContext, "Error While Obtain Product Details", Toast.LENGTH_SHORT).show()
            return@withContext
        }

        val terminalConfig = ServiceHolder.getTerminalConfig()
        if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "SETTLEMENT_WITH_PIN")) {
            helperLog.appendLine(helperLogClassName, "Dialog opened :: [PASSWORD CHECK] settlement requires PIN")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            val dialog = DialogFragmentPasswordCheck.newInstance("settlement")
            dialog.show(supportFragmentManager, "DialogFragmentPasswordCheckSettlement")
        } else {
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> SettlementActivity")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            val newIntent = Intent(applicationContext, SettlementActivity::class.java)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(newIntent)
            finish()
        }
    }

    private fun selectionDialog_preAuth() {
        val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
        val inflater = this.layoutInflater
        @SuppressLint("InflateParams") val dialogView = inflater.inflate(
            R.layout.activity_preauthselection, null
        )
        helperLog.appendLine(helperLogClassName, "Dialog opened :: [SELECT PRE-AUTH FUNCTION]")
        val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn_preauth)
        cancelBtn.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT PRE-AUTH FUNCTION]")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            alertDialog?.dismiss()
        }
        val cardSale = dialogView.findViewById<LinearLayout>(R.id.preauth)
        cardSale.setOnClickListener(preAuthSelectionDialog)
        val qrSale = dialogView.findViewById<LinearLayout>(R.id.SaleComp)
        qrSale.setOnClickListener(preAuthSelectionDialog)
        val preauthCancel = dialogView.findViewById<LinearLayout>(R.id.PCancel)
        preauthCancel.setOnClickListener(preAuthSelectionDialog)
        val voidSaleComp = dialogView.findViewById<LinearLayout>(R.id.VoidSaleComp)
        voidSaleComp.setOnClickListener(preAuthSelectionDialog)
        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    var preAuthSelectionDialog = View.OnClickListener { v ->
        when (v.id) {
            R.id.preauth -> {
                try {
                    val dbProductModel = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
                    val jsonProductList = Gson().toJson(dbProductModel)
                    val saleModelOld = Gson().fromJson(jsonProductList, SalesModel::class.java)
                    ServiceHolder.selectedCacheModel = saleModelOld
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = 8
                    ServiceHolder.saleModelCache = saleModelNew
                    alertDialog?.dismiss()

                    helperLog.appendLine(helperLogClassName, "Selected :: Pre-Auth [SELECT PRE-AUTH FUNCTION]")
                    helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> KeypadActivity")
                    helperLog.logToFile(EnumLogFileName.TerminaLog)

                    val intent = Intent(this@SettingsActivityOxpay, KeypadActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    intent.putExtra("typeofSale", 8)
                    intent.putExtra("indexM", 1)
                    intent.putExtra("indexT", 1)
                    startActivity(intent)
                } catch (ex: Exception) {
                    helperLog.appendLine(helperLogClassName, "Pre-Auth product lookup failed :: ${ex.message}")
                    helperLog.logToFile(EnumLogFileName.TerminaLogException)
                    ex.printStackTrace()
                }
            }
            R.id.SaleComp -> {
                try{
                    val dbProductModel = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
                    val jsonProductList = Gson().toJson(dbProductModel)
                    val saleModelOld = Gson().fromJson(jsonProductList, SalesModel::class.java)
                    ServiceHolder.selectedCacheModel = saleModelOld
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = 4
                    ServiceHolder.saleModelCache = saleModelNew
                    alertDialog?.dismiss()

                    helperLog.appendLine(helperLogClassName, "Selected :: Sale Complete [SELECT PRE-AUTH FUNCTION]")
                    helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> KeypadActivity")
                    helperLog.logToFile(EnumLogFileName.TerminaLog)

                    val intent = Intent(this@SettingsActivityOxpay, KeypadActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    intent.putExtra("typeofSale", 4)
                    intent.putExtra("indexM", 1)
                    intent.putExtra("indexT", 1)
                    startActivity(intent)
                } catch (ex: Exception) {
                    helperLog.appendLine(helperLogClassName, "Sale-Complete product lookup failed :: ${ex.message}")
                    helperLog.logToFile(EnumLogFileName.TerminaLogException)
                    ex.printStackTrace()
                }
            }
            R.id.PCancel -> {
                try{
                    val dbProductModel = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
                    val jsonProductList = Gson().toJson(dbProductModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = 4
                    ServiceHolder.saleModelCache = saleModelNew
                    alertDialog?.dismiss()

                    helperLog.appendLine(helperLogClassName, "Selected :: Pre-Auth Cancel [SELECT PRE-AUTH FUNCTION]")
                    helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> VoidPreauthActivity")
                    helperLog.logToFile(EnumLogFileName.TerminaLog)

                    val intent = Intent(this@SettingsActivityOxpay, VoidPreauthActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    intent.putExtra("typeofSale", 4)
                    intent.putExtra("indexM", 1)
                    intent.putExtra("indexT", 1)
                    startActivity(intent)
                } catch (ex: Exception) {
                    alertDialog?.dismiss()
                    helperLog.appendLine(helperLogClassName, "REJECT :: product not configured for Pre-Auth Cancel")
                    helperLog.logToFile(EnumLogFileName.TerminaLogException)
                    ex.printStackTrace()
                    ToastMake(this@SettingsActivityOxpay, "Product Not Configured", Toast.LENGTH_SHORT)
                }
            }
            R.id.VoidSaleComp -> {
                try {
                    val dbProductModel = ProductListRepo.getSinglev2(applicationContext, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: throw Exception()
                    val jsonProductList = Gson().toJson(dbProductModel)
                    val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                    saleModelNew.SalesType = 4
                    ServiceHolder.saleModelCache = saleModelNew
                    alertDialog?.dismiss()

                    helperLog.appendLine(helperLogClassName, "Selected :: Void Sale Complete [SELECT PRE-AUTH FUNCTION]")
                    helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> VoidOffSaleActivity")
                    helperLog.logToFile(EnumLogFileName.TerminaLog)

                    val intent = Intent(this@SettingsActivityOxpay, VoidOffSaleActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    intent.putExtra("typeofSale", 4)
                    intent.putExtra("indexM", 1)
                    intent.putExtra("indexT", 1)
                    startActivity(intent)
                } catch (ex: Exception) {
                    alertDialog?.dismiss()
                    helperLog.appendLine(helperLogClassName, "REJECT :: product not configured for Void Sale Complete")
                    helperLog.logToFile(EnumLogFileName.TerminaLogException)
                    ex.printStackTrace()
                    ToastMake(this@SettingsActivityOxpay, "Product Not Configured", Toast.LENGTH_SHORT)
                }
            }
            R.id.cancel_btn_preauth -> {
                helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT PRE-AUTH FUNCTION]")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                alertDialog?.dismiss()
            }
        }
    }

    private suspend fun downloadConfiguration() = withContext(Dispatchers.Default) {
        helperLog.appendLine(helperLogClassName, "Process Download Configuration")
        startProgressDialog(this@SettingsActivityOxpay, "Download Configuration", "Downloading... Please wait")
        val taskLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            "Setting Download Configuration",
            helperLogClassName,
            helperLogClassName
        )
        val terminalConfigResp = TmsHelper.getTerminalConfiguration(taskLog, applicationContext)
        helperLog.appendLine(helperLogClassName, "Terminal Config download :: $terminalConfigResp")
        if (terminalConfigResp) {
            ToastMake(applicationContext, "Successfully Downloaded Terminal Configuration", Toast.LENGTH_SHORT)
        } else {
            ToastMake(applicationContext, "Fail To Download Terminal Configuration", Toast.LENGTH_SHORT)
        }

        val merchantConfigResp = TmsHelper.getMerchantConfiguration(taskLog, applicationContext)
        helperLog.appendLine(helperLogClassName, "Merchant Config download :: $merchantConfigResp")
        if (merchantConfigResp) {
            ToastMake(applicationContext, "Successfully Downloaded Merchant Configuration", Toast.LENGTH_SHORT)
        } else {
            ToastMake(applicationContext, "Fail To Download Merchant Configuration", Toast.LENGTH_SHORT)
        }
        val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        helperLog.appendLine(helperLogClassName, "Setting changed :: merchant config MID=" +
                DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqMid") +
                " TID=" + DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqTid"))
        //VISAM
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqMid"), "mid", "visam")
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqTid"), "tid", "visam")
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeader", "visam")
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeaderTle", "visam")
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "NII"),  "nii", "visam")
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "NII"), "niiTle", "visam")

        //MCCS
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqMid"), "mid", "mccs")
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqTid"), "tid", "mccs")
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeader", "mccs")
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeaderTle", "mccs")
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "NII"), "nii", "mccs")
        IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "NII"), "niiTle", "mccs")
        delay(500)
        dynamicScreenOption()
        closeProgressDialog()
        taskLog.logToFile(EnumLogFileName.TerminaLog)
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private suspend fun downloadInjectionKey() = withContext(Dispatchers.Default) {
        helperLog.appendLine(helperLogClassName, "Process Download Injection Key")
        startProgressDialog(this@SettingsActivityOxpay, "Inject Keys", "Downloading... Please wait")
        val taskLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            "Setting Download Injection Key",
            helperLogClassName,
            helperLogClassName
        )

        val resp = TmsHelper.getInjectionKey(taskLog, applicationContext)
        helperLog.appendLine(helperLogClassName, "Download Injection Key :: $resp")
        delay(500)
        closeProgressDialog()
        if (resp) {
            ToastMake(applicationContext, "Successfully Inject Keys", Toast.LENGTH_SHORT)
        } else {
            ToastMake(applicationContext, "Fail To Download Inject Keys", Toast.LENGTH_SHORT)
        }
        taskLog.logToFile(EnumLogFileName.TerminaLog)
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun performSignOn() = withContext(Dispatchers.Default) {
       /* Utils.printLog("Process Sign On")
        startProgressDialog(this@SettingsActivityOxpay, "Download Keys", "Downloading... Please wait")
        val iSignOnResp = cube.proceedSignOn(applicationContext)
        Utils.debugLogPrint("ISOENGINE:SignOn. Resp=", iSignOnResp.toString())
        delay(500)
        closeProgressDialog()
        if (iSignOnResp == 0) {
            ToastMake(applicationContext, "Successfully Downloaded Keys", Toast.LENGTH_SHORT)
        } else {
            ToastMake(applicationContext, "Fail To Download Keys", Toast.LENGTH_SHORT)
        }*/
        startProgressDialog(this@SettingsActivityOxpay, "Download Keys", "Downloading... Please wait")
        TransData.reset(this@SettingsActivityOxpay)
        val acquirerInfo = ServiceHolder.getAcquirerSetting()
        TransData.acqCode = acquirerInfo.acqName
        val taskLog = HelperLog(
            getSession(),
            checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            "SignOn",
            this@SettingsActivityOxpay.javaClass.simpleName,
            this@SettingsActivityOxpay.javaClass.simpleName
        )
        val iSignOnResp = IsoActivity.processSignOn(this@SettingsActivityOxpay, taskLog)
        helperLog.appendLine(helperLogClassName, "Sign On result :: $iSignOnResp")
        closeProgressDialog()
        if (iSignOnResp) {
            ToastMake(applicationContext, "Successfully Downloaded Keys", Toast.LENGTH_SHORT)
        } else {
            ToastMake(applicationContext, "Fail To Download Keys", Toast.LENGTH_SHORT)
        }
        taskLog.logToFile(EnumLogFileName.TerminaLog)
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun resetCommPort() = withContext(Dispatchers.Default) {
        startProgressDialog(this@SettingsActivityOxpay, "Reset", "Resetting Communication Port...")
        withContext(Dispatchers.IO) {
            HTTPServer.resetCommunicationPort()
            delay(2 * 1000)
        }
        ToastMake(applicationContext, "Refreshed Communication Port", Toast.LENGTH_SHORT)
        closeProgressDialog()
        helperLog.appendLine(helperLogClassName, "Communication port reset")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private suspend fun uploadLogFile() = withContext(Dispatchers.Default) {
        helperLog.appendLine(helperLogClassName, "Process Uploading Log File")
        startProgressDialog(this@SettingsActivityOxpay, "Upload File", "Uploading...")
        val taskLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            "Setting Uploading Log",
            helperLogClassName,
            helperLogClassName
        )

        val result = TmsHelper.uploadAllTerminalLog(taskLog, applicationContext, true)
        helperLog.appendLine(helperLogClassName, "Uploading Log result :: $result")
        delay(500)
        closeProgressDialog()
        if (result) {
            ToastMake(applicationContext, "Upload Completed", Toast.LENGTH_LONG)
        } else {
            if (TmsHelper.checkIsConnectedWifi(applicationContext)) {
                ToastMake(applicationContext, "Upload Failed", Toast.LENGTH_LONG)
            } else {
                ToastMake(applicationContext, "Wifi Connection is required", Toast.LENGTH_LONG)
            }
        }
        taskLog.logToFile(EnumLogFileName.TerminaLog)
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    fun customOnBackPress(){
        val dbModelTerminalConfig = getTerminalConfig()
        var newIntent = Intent(applicationContext, AttendActivityOxpay::class.java)
        if(getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE")){
            newIntent = Intent(applicationContext, UnattendActivity::class.java)
        }

        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving Settings")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "SettingsActivityOxpay OnDestroy :: settings screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}