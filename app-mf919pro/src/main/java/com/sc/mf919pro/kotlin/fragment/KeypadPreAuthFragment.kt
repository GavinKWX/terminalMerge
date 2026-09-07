package com.sc.mf919pro.kotlin.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentKeypadPreauthBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import java.math.BigDecimal

class KeypadPreAuthFragment: BaseFragment() {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    var amountString = ""
    lateinit var textViewAmount: TextView
    private var isSalesComp = false
    private var isCashOut = false

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
            helperLog.appendLine(helperLogClassName, "KeypadPreAuth OnDestroyView :: screen ended")
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
        helperLog.appendLine(helperLogClassName, "Initialize Keypad Activity")
        textViewAmount = binding.textViewAmount

        isSalesComp = arguments?.getBoolean("isSalesComp") ?: false
        isCashOut = arguments?.getBoolean("isCashOut") ?: false
        if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "isSalesComp: $isSalesComp")
        if(isSalesComp) {
            binding.textView2.text = "SALE-COM AMOUNT"
            binding.textHeader.text = "Sale Completion"
        }
        if(isCashOut) {
            binding.textView2.text = "Sales"
            binding.textHeader.setText(R.string.enter_the_amount)
        }

        //Start Bottom Group Button
        binding.buttonBack.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: pre-auth/sale-comp entry abandoned")
            customOnBackPress()
        }
        binding.buttonOK.setDebouncedOnClickListener {
            if (isSalesComp) {
                helperLog.appendLine(helperLogClassName, "Selected :: Sale Completion [KEYPAD_PREAUTH]")
                saleCom()
            } else if (isCashOut) {
                helperLog.appendLine(helperLogClassName, "Selected :: Cash Out [KEYPAD_PREAUTH]")
                cashOut()
            } else {
                helperLog.appendLine(helperLogClassName, "Selected :: Pre-Auth [KEYPAD_PREAUTH]")
                preauth()
            }
        }

        val keypad = binding.keypad1
        keypad.setFilter(textViewAmount, true, 12, null)
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun checkAmountValidity(isSaleCom: Boolean): Long {
        helperLog.appendLine(helperLogClassName, "Validating Amount :: ", textViewAmount.text.toString())
        val txnAmount = textViewAmount.text.toString().replace("RM ".toRegex(), "")
        amountString = txnAmount

        if (!isSaleCom && txnAmount == "0.00") {
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

    private fun preauth(){
        helperLog.appendLine(helperLogClassName, "Process PreAuth Onclick")
        val result = checkAmountValidity(false)
        if(result > 0) {
            val cardProductModel = ProductListRepo.getSingle(requireContext(), listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
            helperLog.appendLine(helperLogClassName, "Obtaining Product List :: ", cardProductModel.toString())
            val jsonProductList = Gson().toJson(cardProductModel)
            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
            saleModelNew.SalesType = 8
            saleModelNew.TransAmount = result
            ServiceHolder.saleModelCache = saleModelNew
            helperLog.appendLine(helperLogClassName, "Update PreAuth Sale Model :: ", saleModelNew.toString())
            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> CardPayment")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            navigateSafe(R.id.action_keypadPreAuth_to_cardPayment)
        }
    }

    private fun saleCom() {
        helperLog.appendLine(helperLogClassName, "Process Sales Completion Onclick")
        val result = checkAmountValidity(true)
        if(result >= 0){
            val cardProductModel = ProductListRepo.getSingle(requireContext(), listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
            helperLog.appendLine(helperLogClassName, "Obtaining Product List :: ", cardProductModel.toString())
            val jsonProductList = Gson().toJson(cardProductModel)
            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
            saleModelNew.SalesType = 4
            saleModelNew.TransAmount = result
            ServiceHolder.saleModelCache = saleModelNew
            helperLog.appendLine(helperLogClassName, "Update Sale Model :: ", saleModelNew.toString())
            helperLog.appendLine(helperLogClassName, "End Process Sales Completion Onclick")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            navigateSafe(R.id.action_keypadPreAuth_to_saleCompletion)
        }
    }

    private fun cashOut() {
        helperLog.appendLine(helperLogClassName, "Process CashOut Onclick")
        val result = checkAmountValidity(true)
        if(result >= 0){
            val cardProductModel = ProductListRepo.getSingle(requireContext(), listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
            helperLog.appendLine(helperLogClassName, "Obtaining Product List :: ", cardProductModel.toString())
            val jsonProductList = Gson().toJson(cardProductModel)
            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.CASH_OUT.data.SalesType
            saleModelNew.TransAmount = result
            ServiceHolder.saleModelCache = saleModelNew
            helperLog.appendLine(helperLogClassName, "Update Sale Model :: ", saleModelNew.toString())
            helperLog.appendLine(helperLogClassName, "End Process CashOut Onclick")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            val bundle = bundleOf("isCashOut" to true)
            navigateSafe(R.id.action_keypadPreAuth_to_keypadCashout, bundle)
        }
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }
}