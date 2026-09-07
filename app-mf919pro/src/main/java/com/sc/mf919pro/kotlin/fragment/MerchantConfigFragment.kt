package com.sc.mf919pro.kotlin.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.navigation.fragment.findNavController
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentMerchantConfigBinding
import com.sc.mf919pro.java.activity.ParameterValueDisplay
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import enums.EnumLogFileName
import helpers.HelperLog

class MerchantConfigFragment : BaseFragment() {
    lateinit var helperLog: HelperLog

    var tms: DbModelTerminalConfig? = null
    private var tagTms = arrayOf(
        "DEV_PROJECT","DEV_LOCATION","DEV_LANE_ID"
    )
    var merchantInfo: DbModelMerchantConfig? = null
    private var tagMerchantInfo = arrayOf(
        "MerchantName","MerchantAddress","AutoSettleT1","AutoSettleT2","AutoSettleT3","WaitCardMs","QrMid","QrTid","McVer",
        "AcqCode","AcqMid","AcqTid","PrimaryHostIp","PrimaryHostPort","SecondaryHostIp","SecondaryHostPort","TPDU","NII",
        "HostTimeoutMs","LastSettlementBatchNo","LastStan","LastInvoiceNo"
    )

    private var rl: LinearLayout? = null

    private var _binding: FragmentMerchantConfigBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMerchantConfigBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(this::class.simpleName.toString(), "MerchantConfig OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = binding.toolbarMercCfg
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            })

        rl = binding.merchantConfigField
        merchantInfo = ServiceHolder.getMerchantInfo()
        tms = getTerminalConfig()
        formLayout()
    }

    private fun formLayout() {
        rl!!.removeAllViews()
        for (i in tagTms.indices) {
            val pd = ParameterValueDisplay(requireActivity(), i + 1, tagTms[i], DbModelTerminalConfig.getSafeValue(tms, tagTms[i]))
            rl!!.addView(pd.view)
        }
        for (j in tagMerchantInfo.indices) {
            val pd = ParameterValueDisplay(requireActivity(), j + 1, tagMerchantInfo[j], DbModelMerchantConfig.getSafeValue(merchantInfo, tagMerchantInfo[j]))
            rl!!.addView(pd.view)
        }
    }

    private fun customOnBackPress() {
        findNavController().popBackStack()
    }
}