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
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import java.math.BigDecimal

class KeypadCashoutFragment: BaseFragment() {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    var saleAmount: Long = 0
    lateinit var textViewAmount: TextView
    private var posReference: String? = null
    private var selectedSales: SaleModelNew? = null

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
            helperLog.appendLine(helperLogClassName, "KeypadCashout OnDestroyView :: screen ended")
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
            "PreAuth Keypad Input Action"
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
        textViewAmount = binding.textViewAmount

        // TODO merge togther with revamped method
        binding.textView2.text = "CASH-OUT AMOUNT"
        binding.textHeader.text = "CASH-OUT"
        helperLog.appendLine(helperLogClassName, "Initialize CashOut Keypad Activity")

        //TODO
        //posReference = arguments?.getString("posReference")
        ServiceHolder.saleModelCache?.let {
            saleAmount = it.TransAmount
            selectedSales = it
        }

        //Start Bottom Group Button
        binding.buttonBack.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: cash-out entry abandoned")
            customOnBackPress()
        }
        binding.buttonOK.setDebouncedOnClickListener { cashOut() }
        val keypad = binding.keypad1
        keypad.setFilter(textViewAmount, true, 12, null)
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun cashOut() {
        helperLog.appendLine(helperLogClassName, "Selected :: Cash Out [KEYPAD_CASHOUT]")
        val txnAmount = textViewAmount.text.toString().replace("RM ".toRegex(), "")
        var txnAmountLong = 0L

        if (txnAmount == "0.00") {
            helperLog.appendLine(helperLogClassName, "Trade amount should be greater than 0")
            showToast("Trade amount should be greater than 0", Toast.LENGTH_SHORT)
            return
        }

        val ss = BigDecimal(txnAmount)
        val ss1 = BigDecimal("999999.99")
        if (ss > ss1) {
            helperLog.appendLine(helperLogClassName, "Trade amount should be less than 999999.99")
            showToast("Trade amount should be less than 999999.99", Toast.LENGTH_SHORT)
            return
        }

        txnAmountLong = txnAmount.replace(".", "").toLong()
        saleAmount += txnAmountLong

        selectedSales?.TransAmount = saleAmount
        selectedSales?.CashOutAmount = txnAmountLong
        ServiceHolder.saleModelCache = selectedSales
        navigateSafe(R.id.action_keypadCashout_to_cardPayment)
    }

    private fun customOnBackPress() {
        ServiceHolder.saleModelCache = null
        ServiceHolder.selectedCacheModel = null
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }
}