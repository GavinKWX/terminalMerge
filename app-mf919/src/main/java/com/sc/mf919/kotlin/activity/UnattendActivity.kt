package com.sc.mf919.kotlin.activity

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import com.sc.mf919.R
import com.sc.mf919.java.activity.Global
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.BaseActivity
import com.sc.mf919.kotlin.helper_common.HTTPServer
import com.sc.mf919.kotlin.helper_common.MfHelper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.*

class UnattendActivity: BaseActivity() {
    lateinit var main: LinearLayout
    private var doubleBackToExitPressedOnce = false
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    // TODO private consstants
    private val CODE_DOWNLOAD_CONFIG = 3000
    private val CODE_UPLOAD_LOG = 3001
    private val CODE_MANUAL = 3002
    // TODO private consstants

    companion object{
        private val TAG = "UnattendActivity"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unattend)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Unattended Terminal Home"
        )
        helperLog.appendLine(helperLogClassName, "Unattended home screen opened")

        //TODO Dynamic Layout For Small Terminal
        val displayMetrics = resources.displayMetrics
        val screenHeightPx = displayMetrics.heightPixels
        val screenHeightDp = screenHeightPx / displayMetrics.density
        println("screenHeightDp :: $screenHeightDp")

        findViewById<View>(R.id.invisibleMarginTop30).apply{
            if (screenHeightDp < 500) {
                layoutParams.height = (screenHeightDp * 0).toInt()
            } else {
                layoutParams.height = (screenHeightDp * 0.3).toInt()
            }
        }
        //TODO Dynamic Layout For Small Terminal

        main = findViewById(R.id.moreDetails)
        val versionNumber = "v${ServiceHolder.getAppVersion()}"
        (findViewById<View>(R.id.versionTv) as TextView).text = versionNumber

        val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        var mid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqMid", "-")
        if (!mid.equals("-", true)) {
            mid = Utils.maskString(mid, 4)
        }
        renderSimpleLinearView("IP", Utils.maskIp(ServiceHolder.getCurrentLocalIpAddress()))
        renderSimpleLinearView("", ServiceHolder.getTerminalSerialNumber())
        renderSimpleLinearView("", DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantName", "-"))

        val isTpa = getSafeValue(dbModelMerchantConfig, "IsTpaAccount", "false").toBoolean()
        if (isTpa){
            renderSimpleLinearView("MID", getSafeValue(dbModelMerchantConfig, "ScMid", "-"))
            renderSimpleLinearView("TID", getSafeValue(dbModelMerchantConfig, "ScTid", "-"))
        } else {
            renderSimpleLinearView("MID", mid)
            renderSimpleLinearView("TID", getSafeValue(dbModelMerchantConfig, "AcqTid", "-"))
        }

        findViewById<ImageView>(R.id.imageView).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Manual Mode [UNATTENDED HOME]")
            val msg = Message()
            msg.arg1 = CODE_MANUAL
            passwordDialog(msg)
        }

        findViewById<LinearLayout>(R.id.buttonConfiguration).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Download Configuration [UNATTENDED HOME]")
            val msg = Message()
            msg.arg1 = CODE_DOWNLOAD_CONFIG
            passwordDialog(msg)
        }
        findViewById<LinearLayout>(R.id.buttonUploadLog).setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Upload Log [UNATTENDED HOME]")
            val msg = Message()
            msg.arg1 = CODE_UPLOAD_LOG
            passwordDialog(msg)
        }

        //HTTPServer.getInstance().isActive = true
        HTTPServer.getInstance().attendActivityContext = this@UnattendActivity
        ServiceHolder.appRunningProcess = false
        ServiceHolder.selectedCacheModel = null
        ServiceHolder.ackCountDownSecond = ServiceHolder.defaultAckCountdownSecond

        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })

        helperLog.logToFile(EnumLogFileName.TerminaLog)
        lifecycleScope.launch {
            HTTPServer.resetCommunicationPort()
            checkingTerminalFlag(false)
        }
    }

    /*private fun maskIp(ip: String): String {
        var result = "-"
        if(ip.trim().isNotEmpty()){
            val parts = ip.split(".")
            result = "xxx.xxx.xxx.${parts.last()}"
        }
        return result
    }*/

    private fun checkingTerminalFlag(fromDownload: Boolean) = CoroutineScope(Dispatchers.IO).launch {
        MfHelper.closeNfcUrlInterface()
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_LOCK_HOME")) {
            HelperCommon.bottomActionBarEvent(applicationContext, "1")
        } else {
            HelperCommon.bottomActionBarEvent(applicationContext, "0")
        }

        if(fromDownload) {
            if(!DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE")){
                helperLog.appendLine(helperLogClassName, "Unattended mode disabled by config :: navigate -> AttendActivity")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                val newIntent = Intent(this@UnattendActivity, AttendActivity::class.java)
                newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(newIntent)
                finish()
            }
        }

        if(ServiceHolder.autoSettlementQueue) {
            helperLog.appendLine(helperLogClassName, "Auto settlement queued :: triggering auto settle")
            AppServices.triggerAutoSettle(this@UnattendActivity, AppServices.ACTION_RUN_AUTO_SETTLE)
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun renderSimpleLinearView(label: String?, value: String?) {
        val ll = LinearLayout(applicationContext)
        ll.orientation = LinearLayout.HORIZONTAL
        ll.gravity = Gravity.CENTER
        val params1 = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params1.setMargins(5, 0, 5, 0)
        val tv = TextView(applicationContext)
        tv.layoutParams = params1
        tv.textSize = 14f
        tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        tv.setTextColor(resources.getColor(R.color.black))
        tv.text = label
        val tv1 = TextView(applicationContext)
        tv1.layoutParams = params1
        tv1.textSize = 14f
        tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        tv1.setTextColor(resources.getColor(R.color.black))
        tv1.text = value
        ll.addView(tv)
        ll.addView(tv1)
        main.addView(ll)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun downloadTerminalConfiguration() = withContext(Dispatchers.Default) {
        startProgressDialog(this@UnattendActivity, "Downloading", "Downloading Configuration....")
        val log = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            "Unattend Download Configuration",
            this@UnattendActivity.javaClass.simpleName,
            this@UnattendActivity.javaClass.name
        )

        TmsHelper.checkServerDateTime(log, applicationContext)
        TmsHelper.sendDeviceInfo(log, applicationContext)
        var configurationMessage = "Failed to download configuration."
        var proceedSignOn = TmsHelper.getTerminalConfiguration(log, applicationContext)
        var remoteDownloadKey = false
        if (proceedSignOn) {
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            remoteDownloadKey = DbModelTerminalConfig.getBooleanValue(
                dbModelTerminalConfig,
                "REMOTE_DOWNLOAD_BSN_KEY"
            )
        }

        if (TmsHelper.getMerchantConfiguration(log, applicationContext)) {
            val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
            val acqMid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqMid")
            val acqTid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcqTid")
            val tpdu = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "TPDU")
            val nii = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "NII")
            //VISAM
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqMid, "mid", "visam")
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqTid, "tid", "visam")
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpdu, "isoTpduHeader", "visam")
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpdu, "isoTpduHeaderTle", "visam")
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, nii, "nii", "visam")
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, nii, "niiTle", "visam")
            //MCCS
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqMid, "mid", "mccs")
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqTid, "tid", "mccs")
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpdu, "isoTpduHeader", "mccs")
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpdu, "isoTpduHeaderTle", "mccs")
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, nii, "nii", "mccs")
            IsoBatchInfoRepo.updateBatchInfo(applicationContext, nii, "niiTle", "mccs")
        } else proceedSignOn = false
        val (acqName, requireSignOn) = ServiceHolder.getAcquirerSetting()
        if (proceedSignOn && (remoteDownloadKey || requireSignOn)) {
            proceedSignOn = TmsHelper.getInjectionKey(log, applicationContext)
        }

        if(proceedSignOn){
            if(requireSignOn){
                TransData.reset(applicationContext)
                TransData.acqCode = acqName
                val iSignOnResp = IsoActivity.processSignOn(applicationContext, log)
                if (iSignOnResp) {
                    configurationMessage = "Successfully download configuration"
                }
            } else {
                configurationMessage = "Successfully download configuration"
            }
        }
        val finalMessage = configurationMessage
        log.appendLine(this@UnattendActivity.javaClass.name, "Configuration download finished :: $finalMessage")
        runOnUiThread {
            Toast.makeText(applicationContext, finalMessage, Toast.LENGTH_LONG).show()
        }

        try {
            checkingTerminalFlag(true)
            delay(1000)
            closeProgressDialog()
            log.logToFile(EnumLogFileName.TerminaLog)
        } catch (e: InterruptedException) {
            e.printStackTrace()
            log.appendLine(this@UnattendActivity.javaClass.name, "Configuration download interrupted -> $e")
            log.logToFile(EnumLogFileName.TerminaLogException)
        }
    }

    private suspend fun uploadTerminalLogFile() = withContext(Dispatchers.IO) {
        startProgressDialog(this@UnattendActivity, "Upload File", "Uploading....")
        val log = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            "Unattend Upload Log",
            this@UnattendActivity.javaClass.simpleName,
            this@UnattendActivity.javaClass.name
        )
        val result = TmsHelper.uploadAllTerminalLog(log, applicationContext, true)
        if (result) {
            log.appendLine(this@UnattendActivity.javaClass.name, "Upload Completed")
            ToastMake(this@UnattendActivity, "Upload Completed", Toast.LENGTH_LONG)
        } else {
            if (TmsHelper.checkIsConnectedWifi(applicationContext)) {
                log.appendLine(this@UnattendActivity.javaClass.name, "Upload Fail")
                ToastMake(this@UnattendActivity, "Upload Failed", Toast.LENGTH_LONG)
            } else {
                log.appendLine(this@UnattendActivity.javaClass.name, "Upload Cancel Wi-Fi is required")
                ToastMake(this@UnattendActivity, "Wifi Connection is required", Toast.LENGTH_LONG)
            }
        }

        try {
            delay(1000)
            closeProgressDialog()
            log.logToFile(EnumLogFileName.TerminaLog)
        } catch (e: InterruptedException) {
            e.printStackTrace()
            log.appendLine(this@UnattendActivity.javaClass.name, "Log upload interrupted -> $e")
            log.logToFile(EnumLogFileName.TerminaLogException)
        }
    }

    fun customOnBackPress() {
        if (doubleBackToExitPressedOnce) {
            helperLog.appendLine(helperLogClassName, "Dialog opened :: [EXIT UNATTENDED PASSWORD]")
            passwordAlertDialog(1, "132465", object : onAlertDialogListener {
                override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
                    if (clickStatus) {
                        if (isOK) {
                            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> MainActivity (exit unattended)")
                            helperLog.logToFile(EnumLogFileName.TerminaLog)
                            val intent = Intent(this@UnattendActivity, MainActivity::class.java)
                            intent.putExtra("closeFlag", true)
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(intent)
                            finish()
                        } else {
                            helperLog.appendLine(helperLogClassName, "REJECT :: incorrect exit pin")
                            helperLog.logToFile(EnumLogFileName.TerminaLog)
                            ToastMake(this@UnattendActivity, "Incorrect pin", Toast.LENGTH_SHORT)
                        }
                    }
                }
            })
            return
        }

        helperLog.appendLine(helperLogClassName, "User Cancel :: first back press, awaiting confirmation")
        doubleBackToExitPressedOnce = true
        Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onDestroy() {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "UnattendActivity OnDestroy :: unattended home screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        super.onDestroy()
        //HTTPServer.getInstance().isActive = false
        //HTTPServer.getInstance().attendActivityContext = null
    }

    override fun onResume() {
        super.onResume()
        val myKM = this.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (myKM.isKeyguardLocked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                myKM.requestDismissKeyguard(this,null)
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            }
        }
    }

    fun passwordDialog(msg: Message){
        helperLog.appendLine(helperLogClassName, "Dialog opened :: [SUPERVISOR PASSWORD] action=${msg.arg1}")
        passwordAlertDialog(1, "132465", object : onAlertDialogListener {
            override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
                if (clickStatus) {
                    if (isOK) {
                        customHandler.sendMessage(msg)
                    } else {
                        helperLog.appendLine(helperLogClassName, "REJECT :: incorrect supervisor pin, action=${msg.arg1}")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        ToastMake(this@UnattendActivity, "Incorrect pin", Toast.LENGTH_SHORT)
                    }
                }
            }
        })
    }

    val customHandler = object:  Handler(Looper.getMainLooper()) {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun handleMessage(msg: Message) {
            try {
                alertDialog?.dismiss()
            } catch (ex: Exception) {
              ex.printStackTrace()
              helperLog.appendLine(helperLogClassName, "Dismiss password dialog (Exception) -> $ex")
              helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }

            val handleValue = msg.arg1
            if(handleValue > 0){
                when(handleValue){
                    CODE_DOWNLOAD_CONFIG -> {
                        helperLog.appendLine(helperLogClassName, "Password accepted :: starting configuration download")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        lifecycleScope.launch {
                            downloadTerminalConfiguration()
                        }
                    }
                    CODE_UPLOAD_LOG -> {
                        helperLog.appendLine(helperLogClassName, "Password accepted :: starting terminal log upload")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        lifecycleScope.launch {
                            uploadTerminalLogFile()
                        }
                    }
                    CODE_MANUAL -> {
                        helperLog.appendLine(helperLogClassName, "Password accepted :: navigate -> AttendActivity (manual mode)")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        val intent = Intent(this@UnattendActivity, AttendActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(intent)
                        finish()
                    }
                    else -> {
                        helperLog.appendLine(helperLogClassName, "REJECT :: unknown handler action -> $handleValue")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                    }
                }
            }
        }
    }
}