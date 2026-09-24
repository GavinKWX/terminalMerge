package com.sc.mf919pro.kotlin.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.navigation.fragment.findNavController
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentTerminalConfigBinding
import com.sc.mf919pro.java.device.DeviceHelper
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig.Companion.getSafeValue
import com.sc.mf919pro.kotlin.database.repo.TerminalConfigurationRepo
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import enums.EnumLogFileName
import helpers.HelperLog

class TerminalConfigFragment : BaseFragment() {
    lateinit var helperLog: HelperLog

//    lateinit var Contact: Switch
//    lateinit var Contactless: Switch
//    lateinit var MagStripe: Switch
//    lateinit var QrPay: Switch
//    lateinit var ForcePin: Switch
//    lateinit var IsoPrint: Switch
//    lateinit var ReceiptPrint: Switch
//    lateinit var OptIn: Switch
//    lateinit var SaleComOnline: Switch
//    lateinit var AutoSettle: Switch
//    lateinit var Sale: Switch
//    lateinit var Void: Switch
//    lateinit var PreAuth: Switch
//    lateinit var SaleCom: Switch
//    lateinit var Refund: Switch
//    lateinit var CashOut: Switch
//    lateinit var TmsReceipt: Switch
//    lateinit var TmsEnable: Switch
//    lateinit var ForceSettlementCheck: Switch
//    lateinit var ForceSettlementDaily: Switch
//    lateinit var ForceLockHome: Switch


    private var _binding: FragmentTerminalConfigBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTerminalConfigBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(this::class.simpleName.toString(), "TerminalConfig OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = binding.toolbarTermCfg
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            })

        val configMappings = listOf(
            { binding.contact } to "Contact",
            { binding.contactless } to "Contactless",
            { binding.megStripe } to "MagStripe",
            { binding.qrPay } to "QrPay",
            { binding.forcePin } to "ForcePin",
            { binding.bypassPin } to "BYPASS_PIN",
            { binding.printIso } to "IsoPrint",
            { binding.printReceipt } to "ReceiptPrint",
            { binding.optIn } to "OptIn",
            { binding.SaleCompOnline } to "SaleComOnline",
            { binding.autoSettle } to "AutoSettle",
            { binding.sale } to "Sale",
            { binding.void1 } to "Void",
            { binding.preauth } to "PreAuth",
            { binding.SaleComp } to "SaleCom",
            { binding.refund } to "Refund",
            { binding.cashout } to "CASHOUT",
            { binding.receiptTms } to "TmsReceipt",
            { binding.deviceTms } to "TmsEnable",
            { binding.forceSettlementCheck } to "FORCE_SETTLEMENT",
            { binding.forceSettlementDaily } to "FORCE_SETTLEMENT_DAILY",
            { binding.forceLockHome } to "FORCE_LOCK_HOME"
        )

        val terminalConfig = getTerminalConfig()

        configMappings.forEach { (checkBoxProvider, key) ->
            checkBoxProvider().isChecked = getBooleanValue(terminalConfig, key)
        }

        val merchantLabels = listOf(
            "SMALL",
            "MEDIUM",
            "LARGE"
        )
        val merchantAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, merchantLabels)
        merchantAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.merchantInfoSize.adapter = merchantAdapter
        val merchantTextSize = getSafeValue(terminalConfig, "RECEIPT_MERCHANT_INFO_SIZE").toIntOrNull() ?: 0
        binding.merchantInfoSize.setSelection(merchantTextSize)

        val txnLabels = listOf(
            "NORMAL",
            "LARGE"
        )
        val txnAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, txnLabels)
        txnAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.txnInfoSize.adapter = txnAdapter
        val txnTextSize = getSafeValue(terminalConfig, "RECEIPT_TXN_INFO_SIZE").toIntOrNull() ?: 0
        binding.txnInfoSize.setSelection(txnTextSize)
    }

    fun customOnBackPress() {
        object : Thread() {
            override fun run() {
                super.run()
                save()
                DeviceHelper.resetAID()
            }
        }.start()
        findNavController().popBackStack()
    }

    private fun save() {
        if (binding.forceLockHome.isChecked) {
            MfHelper.lockStatusBarAndNavigation(true)
        } else {
            MfHelper.lockStatusBarAndNavigation(false)
        }

        val updateMap = mutableMapOf<Any, Any>()
        listOf(
            "Contact" to { binding.contact },
            "Contactless" to { binding.contactless },
            "MagStripe" to { binding.megStripe },
            "ForcePin" to { binding.forcePin },
            "BYPASS_PIN" to { binding.bypassPin },
            "IsoPrint" to { binding.printIso },
            "ReceiptPrint" to { binding.printReceipt },
            "Sale" to { binding.sale },
            "Void" to { binding.void1 },
            "PreAuth" to { binding.preauth },
            "SaleCom" to { binding.SaleComp },
            "SaleComOnline" to { binding.SaleCompOnline },
            "Refund" to { binding.refund },
            "OptIn" to { binding.optIn },
            "TmsReceipt" to { binding.receiptTms },
            "TmsEnable" to { binding.deviceTms },
            "AutoSettle" to { binding.autoSettle },
            "QrPay" to { binding.qrPay },
            "FORCE_SETTLEMENT" to { binding.forceSettlementCheck },
            "FORCE_SETTLEMENT_DAILY" to { binding.forceSettlementDaily },
            "FORCE_LOCK_HOME" to { binding.forceLockHome },
            "CASHOUT" to { binding.cashout }
        ).forEach { (key, checkBoxProvider) ->
            updateMap[key] = DbModelTerminalConfig.setBooleanValue(checkBoxProvider().isChecked)
        }

        updateMap["RECEIPT_MERCHANT_INFO_SIZE"] =
            binding.merchantInfoSize.selectedItemPosition.toString()

        updateMap["RECEIPT_TXN_INFO_SIZE"] =
            binding.txnInfoSize.selectedItemPosition.toString()

        TerminalConfigurationRepo.updateTerminalConfig(requireContext(), updateMap)
        ServiceHolder.clearTerminalConfigInformation()
    }
}