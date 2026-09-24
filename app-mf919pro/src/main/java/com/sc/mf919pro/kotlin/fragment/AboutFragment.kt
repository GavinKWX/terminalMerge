package com.sc.mf919pro.kotlin.fragment

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.library.terminal.Utility
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentAboutBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.InstallIdentity
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getAppVersion
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getMerchantInfo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalSerialNumber
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.utils.NetworkChangeObserver
import com.sc.mf919pro.kotlin.helper_common.utils.updateConnectionViews
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.launch

class AboutFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    lateinit var main: LinearLayout
    lateinit var tvWifi: TextView
    lateinit var tvSim: TextView

    private var networkChangeObserver: NetworkChangeObserver? = null

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "About OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun onPause() {
        networkChangeObserver?.stop()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_LOCK_HOME")) {
            MfHelper.lockStatusBarAndNavigation(true)
        }
        updateConnectionViews(requireContext(), tvWifi, tvSim)
        networkChangeObserver?.start()
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
            "About Fragment Initialization"
        )
        binding.appToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener {  customOnBackPress() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )
        binding.textView2.text = getAppVersion()
        main = binding.moreDetails
        tvWifi = binding.tvWifi
        tvWifi.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "Wifi Connection Setting OnClick")
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_LOCK_HOME")) {
                MfHelper.lockStatusBarAndNavigation(true)
            }
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            startActivity(intent)
        }
        tvSim = binding.tvSim
        tvSim.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "SimCard Connection Setting OnClick")
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_LOCK_HOME")) {
                MfHelper.lockStatusBarAndNavigation(true)
            }
            val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
            startActivity(intent)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            informationThread()
        }

        networkChangeObserver = NetworkChangeObserver(
            context = requireContext(),
            debounceMs = 700L
        ) {
            if (_binding != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    informationThread()
                    updateConnectionViews(requireContext(), tvWifi, tvSim)
                }
            }
        }
    }

    fun informationThread() {
        main.removeAllViews()
        val dbModelMerchantConfig = getMerchantInfo()
        renderSimpleLinearView("MODEL", ServiceHolder.getDeviceModel())
        // Support needs this to match a device against a row on the Portal manual-settle worklist.
        renderSimpleLinearView("INSTALL ID", InstallIdentity.getShortToken())
        renderSimpleLinearView("IP", ServiceHolder.getCurrentLocalIpAddress())
        renderSimpleLinearView("", getTerminalSerialNumber())
        renderSimpleLinearView("", getSafeValue(dbModelMerchantConfig, "MerchantName", "-"))

        val isTpa = getSafeValue(dbModelMerchantConfig, "IsTpaAccount", "false").toBoolean()
        if (isTpa){
            renderSimpleLinearView("MID", getSafeValue(dbModelMerchantConfig, "ScMid", "-"))
        } else {
            renderSimpleLinearView("MID", getSafeValue(dbModelMerchantConfig, "AcqMid", "-"))
        }
        renderSimpleLinearView("TID", getSafeValue(dbModelMerchantConfig, "AcqTid", "-"))

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val connMethod = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "CABLE_CONNECTION")
        if(connMethod.isNotEmpty() && !connMethod.equals("None", true)) {
            renderSimpleLinearView("Connection Mode :", connMethod)
        }
    }

    private fun renderSimpleLinearView(label: String?, value: String?) {
        val ll = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(5, 0, 5, 0)
        }
        val tv = TextView(requireContext()).apply {
            layoutParams = params
            textSize = 14f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            text = label
        }
        val tv1 = TextView(requireContext()).apply {
            layoutParams = params
            textSize = 14f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            text = value
        }
        ll.addView(tv)
        ll.addView(tv1)
        main.addView(ll)
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        findNavController().popBackStack()
    }
}