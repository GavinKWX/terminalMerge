package com.sc.mf919pro.kotlin.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentContactUsBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.helper_common.GenerateQr
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import tms.models.SupportObject

class ContactUsFragment: BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String
    var merchantSupportObject:SupportObject? = null

    private var _binding: FragmentContactUsBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContactUsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "ContactUs OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
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
            "Contact Us Fragment Initialization"
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

        val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        val supportString = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "Support")
        println("supportString :: $supportString")
        try{
            merchantSupportObject = Gson().fromJson(supportString, SupportObject::class.java)
        } catch (_: Exception) { }
        println("merchantSupportObject :: $merchantSupportObject")

        binding.textViewPhone.apply {
            text = merchantSupportObject?.Terminal_SupportPhoneNo ?: "-"
        }
        binding.imageViewQr.apply {
            if(true) {
            //if(merchantSupportObject?.Terminal_SupportURL?.isEmpty() == false) {
                setImageBitmap(GenerateQr().createZxingBitmap(
                    requireContext(),
                    merchantSupportObject?.Terminal_SupportURL ?: "",
                    "qrCode",
                    null,
                    qrSize = 300
                ))
            } else {
                visibility = View.GONE

            }
        }
        binding.textViewEmail.apply {
            text = merchantSupportObject?.Terminal_SupportEmail ?: "-"
        }
    }

    fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        //findNavController().popBackStack()
        val dbModelTerminalConfig = getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }
}