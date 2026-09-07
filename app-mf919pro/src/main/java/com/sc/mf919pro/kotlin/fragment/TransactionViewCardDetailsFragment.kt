package com.sc.mf919pro.kotlin.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentReceiptBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919pro.kotlin.database.repo.PrintReceiptRepo
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import helpers.HelperCommon
import enums.EnumLogFileName
import helpers.HelperLog

class TransactionViewCardDetailsFragment : BaseFragment(){
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    lateinit var receipt: ScrollView
    lateinit var mainReceipt: LinearLayout
    lateinit var headerImageView: ImageView
    private var dbModelPrintReceipt: DbModelPrintReceipt? = null

    private var _binding: FragmentReceiptBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReceiptBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "TransactionViewCardDetails OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.simpleName.toString()
        val log = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            "TransactionViewCardDetailsActivity",
            helperLogClassName,
            helperLogClassName
        )

        receipt = binding.receipt
        mainReceipt = binding.mainReceipt
        headerImageView = binding.headerImageView
        binding.printTV.text = "Print Receipt"

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )
        dbModelPrintReceipt = PrintReceiptRepo.getSingle(requireContext(), listOf("stan"), listOf(transData.stan ?: ""))
        println("dbModelPrintReceipt :: ${dbModelPrintReceipt.toString()}")
        var msg = dbModelPrintReceipt?.receiptInfo ?: ""
        msg = msg.replace("[", "").replace("]", "").replace(", ", "\n")
        if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "onItemSelected:  $msg")
        val details = Utils.String2ArrayString(msg)
        val bundleValue = Bundle().apply {
            putBoolean("isTpa", dbModelPrintReceipt?.isTpaAccount == "true")
        }
        formLayout(log, helperLogClassName, details, mainReceipt, headerImageView, bundleValue)

        // Button Listener
        binding.cancelBtn.setOnClickListener { customOnBackPress() }
        binding.printBtn.setOnClickListener { printReceipt(receipt, details,"DUPLICATE", bundleValue) }
    }

    private fun customOnBackPress() {
        navigateSafe(R.id.action_details_to_list)
    }
}