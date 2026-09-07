package com.sc.mf919pro.kotlin.fragment

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat.finishAffinity
import androidx.lifecycle.lifecycleScope
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentUnattendBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.AppServices
import com.sc.mf919pro.kotlin.activity.onAlertDialogListener
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919pro.kotlin.helper_common.utils.NetworkChangeObserver
import com.sc.mf919pro.kotlin.helper_common.utils.getConnectionStatus
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.AsyncLogWriter
import helpers.LogSessionMarker
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

class UnAttendFragment : BaseFragment(), FragmentImageSlider.OnFragmentInteractionListener {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    lateinit var detailLinearLayout: LinearLayout
    private var doubleBackToExitPressedOnce = false
    private var networkChangeObserver: NetworkChangeObserver? = null

    // TODO private consstants
    private val CODE_DOWNLOAD_CONFIG = 3000
    private val CODE_SETTLEMENT = 3001
    private val CODE_MANUAL = 3002
    // TODO private consstants

    private var _binding: FragmentUnattendBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUnattendBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        networkChangeObserver?.stop()
        networkChangeObserver = null
        super.onDestroyView()
        _binding = null
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "UnAttend OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
    override fun onPause() {
        networkChangeObserver?.stop()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        networkChangeObserver?.start()
    }

    override fun fragmentImageSlideAction() { /*customOnBackPress()*/ }
    private fun processRenderBannerImg() {
        childFragmentManager.beginTransaction().replace(R.id.bannerImageView, FragmentImageSlider()).addToBackStack(null).commit()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.simpleName.toString()
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "UnAttend Fragment Initialization"
        )
        if (Helper.getInstance().getPrefs() == null) {
            Helper.getInstance().Initialize(requireContext())
        }
        ServiceHolder.appRunningProcess = false
        ServiceHolder.selectedCacheModel = null
        ServiceHolder.saleModelCache = null

        // Custom back press handling
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )
        detailLinearLayout = binding.moreDetails
        binding.versionTv.text = "v${ServiceHolder.getAppVersion()}"
        binding.imageView.setDebouncedOnClickListener {
            val msg = Message().apply { arg1 = CODE_MANUAL }
            passWordActivity(msg)
        }
        binding.buttonConfiguration.setDebouncedOnClickListener {
            val msg = Message().apply { arg1 = CODE_DOWNLOAD_CONFIG }
            passWordActivity(msg)
        }
        binding.buttonSettlement.setDebouncedOnClickListener {
            val msg = Message().apply { arg1 = CODE_SETTLEMENT }
            passWordActivity(msg)
        }

        processRenderBannerImg()
        viewLifecycleOwner.lifecycleScope.launch {
            informationThread()
            checkingTerminalFlag(false)
            showShareCommerceLogo()
        }
        networkChangeObserver = NetworkChangeObserver(context = requireContext(), debounceMs = 700L) {
            if (_binding != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    informationThread()
                }
            }
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    fun showShareCommerceLogo() {
        val bitmap = BitmapFactory.decodeResource(requireContext().resources, R.mipmap.sharecomm_logo)
        MfHelper.showAuxLcdImg(bitmap)
    }

    private fun checkingTerminalFlag(fromDownload: Boolean) = CoroutineScope(Dispatchers.IO).launch {
        MfHelper.closeNfcUrlInterface()
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val lockHome = DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_LOCK_HOME")
        MfHelper.lockStatusBarAndNavigation(lockHome)

        if(ServiceHolder.autoSettlementQueue) {
            AppServices.triggerAutoSettle(requireContext(), AppServices.ACTION_RUN_AUTO_SETTLE)
        }

        if(fromDownload){
            if(!DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE")) {
                withContext(Dispatchers.Main) {
                    navigateToHome(dbModelTerminalConfig)
                }
            }
        }
    }

    fun informationThread() {
        detailLinearLayout.removeAllViews()
        val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        var connectionMethod = "-"
        val (wifi, mobile) = getConnectionStatus(requireContext())
        if(wifi) {
            connectionMethod = "WIFI"
        } else if (mobile) {
            connectionMethod = "Sim Card"
        }
        renderSimpleLinearView("Connection Method : ", connectionMethod)
        renderSimpleLinearView("IP : ", ServiceHolder.getCurrentLocalIpAddress())
        renderSimpleLinearView("", ServiceHolder.getTerminalSerialNumber())
        renderSimpleLinearView("", getSafeValue(dbModelMerchantConfig, "MerchantName", "-"))

        val isTpa = getSafeValue(dbModelMerchantConfig, "IsTpaAccount", "false").toBoolean()
        if (isTpa){
            renderSimpleLinearView("MID : ", getSafeValue(dbModelMerchantConfig, "ScMid", "-"))
        } else {
            renderSimpleLinearView("MID : ", getSafeValue(dbModelMerchantConfig, "AcqMid", "-"))
        }
        renderSimpleLinearView("TID : ", getSafeValue(dbModelMerchantConfig, "AcqTid", "-"))
    }

    private fun renderSimpleLinearView(label: String?, value: String?) {
        val context = requireContext()

        val ll = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(5, 0, 5, 0)
        }

        val tvLabel = TextView(context).apply {
            layoutParams = params
            textSize = 14f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextColor(resources.getColor(R.color.black, null))
            text = label
        }

        val tvValue = TextView(context).apply {
            layoutParams = params
            textSize = 14f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            setTextColor(resources.getColor(R.color.black, null))
            text = value
        }

        ll.addView(tvLabel)
        ll.addView(tvValue)
        detailLinearLayout.addView(ll)

        helperLog.appendLine(helperLogClassName, "render Detail Linear ($label) >> $value")
    }

    suspend fun downloadTerminalConfiguration() {
        val mContext = requireContext()
        showProgress("Downloading Configuration", "Please Wait")
        val innerHelperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(mContext),
            Utils.getIPAddress(),
            "UnAttend Download Configuration",
            helperLogClassName,
            helperLogClassName
        )

        withContext(Dispatchers.IO) {
            TmsHelper.checkServerDateTime(innerHelperLog, mContext)
            TmsHelper.sendDeviceInfo(innerHelperLog, mContext)
        }
        var configurationMessage = "Failed to download configuration."
        var proceedSignOn = withContext(Dispatchers.IO) {
            TmsHelper.getTerminalConfiguration(innerHelperLog, mContext)
        }
        var remoteDownloadKey = false
        if (proceedSignOn) {
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            remoteDownloadKey = DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "REMOTE_DOWNLOAD_BSN_KEY")
        }

        proceedSignOn = withContext(Dispatchers.IO) {
            TmsHelper.getMerchantConfiguration(innerHelperLog, mContext)
        }
        if (proceedSignOn) {
            val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
            //VISAM
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "AcqMid"), "mid", "visam")
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "AcqTid"), "tid", "visam")
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeader", "visam")
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeaderTle", "visam")
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "NII"), "nii", "visam")
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "NII"), "niiTle", "visam")

            //MCCS
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "AcqMid"), "mid", "mccs")
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "AcqTid"), "tid", "mccs")
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeader", "mccs")
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeaderTle", "mccs")
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "NII"), "nii", "mccs")
            IsoBatchInfoRepo.updateBatchInfo(mContext, getSafeValue(dbModelMerchantConfig, "NII"), "niiTle", "mccs")
        } else proceedSignOn = false
        val (acqName, requireSignOn) = ServiceHolder.getAcquirerSetting()
        if (proceedSignOn && (remoteDownloadKey || requireSignOn)) {
            proceedSignOn = withContext(Dispatchers.IO) {
                TmsHelper.getInjectionKey(innerHelperLog, mContext)
            }
        }

        if(proceedSignOn){
            if(requireSignOn){
                transData.reset()
                transData.acqCode = acqName
                val signOnResp = withContext(Dispatchers.IO) {
                    IsoActivity.processSignOn(mContext, innerHelperLog)
                }
                if(signOnResp) {
                    configurationMessage = "Successfully download configuration"
                }
            } else {
                configurationMessage = "Successfully download configuration"
            }
        }
        val finalMessage = configurationMessage
        Toast.makeText(mContext, finalMessage, Toast.LENGTH_LONG).show()
        hideProgress()
        delay(500)
        withContext(Dispatchers.IO) {
            innerHelperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        delay(500)
        checkingTerminalFlag(true)
    }

    fun customOnBackPress() {
        if (doubleBackToExitPressedOnce) {
            passWordActivity(null)
            return
        }
        helperLog.appendLine(helperLogClassName, "customOnBackPress detected :: $doubleBackToExitPressedOnce")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        doubleBackToExitPressedOnce = true
        showToast("Please click BACK again to exit", Toast.LENGTH_SHORT)
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    private fun passWordActivity(msg: Message?) {
        passwordAlertDialog(1, "132465", object : onAlertDialogListener {
            override fun onResult(Id: Int, clickStatus: Boolean, isOK: Boolean) {
                helperLog.appendLine(helperLogClassName, "Pin Checker :: $clickStatus")
                helperLog.appendLine(helperLogClassName, "is OK :: $isOK")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                if (clickStatus) {
                    if (isOK) {
                        if(msg == null) {
                            closeApplication()
                        } else {
                            customHandler.sendMessage(msg)
                        }
                    } else {
                        showToast("Incorrect pin", Toast.LENGTH_SHORT)
                    }
                }
            }
        })
        return
    }

    private fun closeApplication() {
        helperLog.appendLine(helperLogClassName, "closeApplication detected!")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        // This is a GENUINE app exit, so record it -- otherwise the next startup reports
        // "DID NOT EXIT CLEANLY" for an orderly shutdown and masks the next real kill.
        // Drain after: exitProcess takes the daemon writer thread with it.
        LogSessionMarker.closeSession("UnAttendFragment.closeApplication")
        AsyncLogWriter.drain()
        MfHelper.lockStatusBarAndNavigation(false)
        finishAffinity(requireActivity())
        exitProcess(0)
    }

    private val customHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.arg1) {
                CODE_DOWNLOAD_CONFIG -> {
                    helperLog.appendLine(helperLogClassName, "Custom Handler :: ${msg.arg1}")
                    viewLifecycleOwner.lifecycleScope.launch {
                        downloadTerminalConfiguration()
                    }
                }
                CODE_SETTLEMENT -> {
                    /*viewLifecycleOwner.lifecycleScope.launch {
                        navigateSafe(R.id.action_unattend_to_settlement)
                    }*/
                    helperLog.appendLine(helperLogClassName, "Custom Handler :: ${msg.arg1}")
                    navigateSafe(R.id.action_unattend_to_settlement)
                }
                CODE_MANUAL -> {
                    /*viewLifecycleOwner.lifecycleScope.launch {
                        navigateSafe(R.id.action_unattend_to_attend)
                    }*/
                    helperLog.appendLine(helperLogClassName, "Custom Handler :: ${msg.arg1}")
                    navigateSafe(R.id.action_unattend_to_attend)
                }
                else -> {
                    helperLog.appendLine(helperLogClassName, "Custom Handler Out of Cases :: ${msg.arg1}")
                    helperLog.logToFile(EnumLogFileName.TerminaLog)
                }
            }
        }
    }
}