package com.sc.mf919pro.kotlin.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentKeypadPreauthBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import java.math.BigDecimal

class EppKeypadFragment: BaseFragment() {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    lateinit var textViewAmount: TextView
    var extraBundle: Bundle? = null

    private var _binding: FragmentKeypadPreauthBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentKeypadPreauthBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "EppKeypad OnDestroyView :: screen ended")
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
            "EPP Keypad Input Action"
        )
        val toolbar = binding.appToolbar
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener {  customOnBackPress() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            }
        )
        helperLog.appendLine(helperLogClassName, "Initialize Keypad Activity")

        binding.textHeader.text = "EPP"
        binding.textView2.text = "TOTAL INSTALMENT AMOUNT"
        textViewAmount = binding.textViewAmount
        extraBundle = arguments

        //Start Bottom Group Button
        binding.buttonBack.setDebouncedOnClickListener {
            customOnBackPress()
        }
        binding.buttonOK.setDebouncedOnClickListener {
            eppSale()
        }

        val amount = arguments?.getLong("txnAmt", 0) ?: 0L
        helperLog.appendLine(helperLogClassName, "Amount Checking :: $amount")
        if(amount > 0L) {
            helperLog.appendLine(helperLogClassName, "running of sale checking")
            textViewAmount.text = Utils.getActualAmount(amount.toString())
            eppSale()
        }
        val keypad = binding.keypad1
        keypad.setFilter(textViewAmount, true, 12, null)
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun checkAmountValidity(): Long{
        helperLog.appendLine(helperLogClassName, "Validating Amount :: ", textViewAmount.text.toString())
        val txnAmount = textViewAmount.text.toString().replace("RM ".toRegex(), "")
        val amountString = txnAmount

        if (txnAmount == "0.00") {
            helperLog.appendLine(helperLogClassName, "Trade amount should be greater than 0")
            showToast("Trade amount should be greater than 0", Toast.LENGTH_SHORT)
            return 0
        }

        val ss = BigDecimal(txnAmount)
        val ss1 = BigDecimal("999999.99")
        if (ss > ss1) {
            helperLog.appendLine(helperLogClassName, "Trade amount should be less than 999999.99")
            showToast("Trade amount should be less than 999999.99", Toast.LENGTH_SHORT)
            return 0
        }

        return amountString.replace(".", "").toLongOrNull()
            ?: run { 0 }
    }

    fun eppSale() {
        helperLog.appendLine(helperLogClassName, "Process EPP Onclick")
        val result = checkAmountValidity()
        helperLog.appendLine(helperLogClassName, "Amount after validate :: $result")
        try {
            if(result > 0) {
                //TODO amount passing
                ServiceHolder.saleModelCache?.TransAmount = result
                helperLog.appendLine(helperLogClassName, "Proceed to EPP Product Selection")
                navigateSafe(R.id.action_eppKeypad_to_eppProduct, extraBundle)
            }
        } finally {
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }
}