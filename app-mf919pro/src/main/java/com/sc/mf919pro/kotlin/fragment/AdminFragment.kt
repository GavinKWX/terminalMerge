package com.sc.mf919pro.kotlin.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import datastore.DataStoreManager
import datastore.PrefKeys
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentAdminBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.database.repo.BatchTableRepo
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.IsoBatchLongInfoRepo
import com.sc.mf919pro.kotlin.database.repo.PrintReceiptRepo
import com.sc.mf919pro.kotlin.database.repo.ReversalBatchTableRepo
import com.sc.mf919pro.kotlin.database.repo.SettlementSummaryRepo
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.checkIsConnectedWifi
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.getSession
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    private var _binding: FragmentAdminBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "Admin OnDestroyView :: screen ended")
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
            "Admin Fragment Initialization"
        )
        val toolbar = binding.toolbarAdmin
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            })

        // Button Listener
        binding.terminalConfigBtn.setOnClickListener { navigateSafe(R.id.action_admin_to_terminalConfig) }
        binding.merchantConfigBtn.setOnClickListener { navigateSafe(R.id.action_admin_to_merchantConfig) }
        binding.hostConfigBtn.setOnClickListener { navigateSafe(R.id.action_admin_to_hostConfig) }
        binding.clearBatchBtn.setOnClickListener {clearBatchBtn()}
        binding.clearReversalBtn.setOnClickListener {clearReversalBtn()}
        binding.clearSettlementRevBtn.setOnClickListener {clearSettlementRevBtn()}
    }

    private fun clearBatchBtn() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Clear Batch")
        builder.setMessage("Are you confirm to Clear Current Batch?")

        // Set the positive button and its action
        builder.setNegativeButton("OK") { dialog, _ ->
            helperLog.appendLine(helperLogClassName, "ClearBatch Button OnClick")
            clearBatchFunc()
            dialog.dismiss()
        }
        // Set the negative button and its action
        builder.setPositiveButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        // Create and show the alert dialog
        val alertDialog: AlertDialog = builder.create()
        alertDialog.show()

    }

    private fun clearBatchFunc () {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                showProgress("Clear All Batch", "Clearing... Please wait")
            }
            BatchTableRepo.truncateTable(requireContext())
            IsoBatchInfoRepo.getBatchInfo(requireContext(), "batchNo", "visam")?.let {
                //TODO Reset All Product First
                val strBatchNoToRemove = it.value
                var newDbBatchNo = Utils.atoi(it.value) + 1
                if (newDbBatchNo > 999999) newDbBatchNo = 1
                val finalBatchNo = String.format("%06d", newDbBatchNo)
                IsoBatchInfoRepo.updateBatchInfo(requireContext(), finalBatchNo, "batchNo", "visam")

                IsoBatchInfoRepo.updateBatchInfo(requireContext(), "0", "txnTotal", "visam")
                IsoBatchInfoRepo.updateBatchInfo(requireContext(), "0", "txnCount", "visam")
                IsoBatchInfoRepo.updateBatchInfo(requireContext(), "0", "voidTxnTotal", "visam")
                IsoBatchInfoRepo.updateBatchInfo(requireContext(), "0", "voidTxnCount", "visam")
                IsoBatchInfoRepo.updateBatchInfo(requireContext(), "0", "tcCount", "visam")

                // Clear txn Count and Amount
                IsoBatchInfoRepo.updateBatchInfo(requireContext(), "0", "buTxnTotal", "visam")
                IsoBatchInfoRepo.updateBatchInfo(requireContext(), "0", "buTxnCount", "visam")

                // Delete Batch
                BatchTableRepo.deleteBatchRecord(requireContext(), strBatchNoToRemove)
                PrintReceiptRepo.deleteAllData(requireContext())

                //All Product current batch are settled, clean up settlement record
                val valueHM = HashMap<Any, Any>()
                valueHM["value"] = "0"
                valueHM["is_settle"] = "false"

                val criteriaHM = HashMap<Any, Any>()
                SettlementSummaryRepo.updateData(requireContext(), valueHM, criteriaHM)

                val store = DataStoreManager(requireContext())
                store.putBoolean(PrefKeys.settlementBlock, false)
                println("Settlement BLOCK = FALSE (END)")
            }
            delay(5000)
            showToast("Batch Successfully Cleared...", Toast.LENGTH_SHORT)
            helperLog.appendLine(helperLogClassName, "Batch Successfully Cleared")
            delay(1000)
            withContext(Dispatchers.Main) {
                hideProgress()
            }
        }
    }

    private fun clearReversalBtn () {
        helperLog.appendLine(helperLogClassName, "ClearReversal Button OnClick")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                showProgress("Clear reversal", "Clearing... Please wait")
            }
            delay(1000)

            val resp = clearReversalFunc()
            withContext(Dispatchers.Main) {
                if (resp > 0) {
                    showToast("Successfully Clear Reversal", Toast.LENGTH_SHORT)
                } else {
                    showToast("Fail To Clear Reversal", Toast.LENGTH_SHORT)
                }
            }
            delay(1000)
            withContext(Dispatchers.Main) {
                hideProgress()
            }
        }
    }

    private fun clearReversalFunc(): Int {
        val log = HelperLog(
            getSession(),
            checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            "Clear Reversal",
            helperLogClassName,
            helperLogClassName
        )
        ReversalBatchTableRepo.truncateTable(requireContext())
        log.appendLine(helperLogClassName, "Deleted revBatch records")

        var iAFfectedCount = 0
        iAFfectedCount += if (IsoBatchLongInfoRepo.updateBatchLongInfo(requireContext(), "", "revDes", "visam")) 1 else 0
        iAFfectedCount += if (IsoBatchLongInfoRepo.updateBatchLongInfo(requireContext(), "", "revType", "visam")) 1 else 0
        iAFfectedCount += if (IsoBatchLongInfoRepo.updateBatchLongInfo(requireContext(), "", "revIsoDb", "visam")) 1 else 0
        iAFfectedCount += if (IsoBatchLongInfoRepo.updateBatchLongInfo(requireContext(), "", "revIsoOri", "visam")) 1 else 0
        iAFfectedCount += if (IsoBatchLongInfoRepo.updateBatchLongInfo(requireContext(), "", "revSchemeTag", "visam")) 1 else 0
        iAFfectedCount += if (IsoBatchLongInfoRepo.updateBatchLongInfo(requireContext(), "", "revSchemeId", "visam")) 1 else 0

        if (iAFfectedCount <= 0) log.appendLine(helperLogClassName, "ERR+WARNING: Failed to clear reversal")
        else log.appendLine(helperLogClassName, "Reversal cleared successfully")

        log.logToFile(EnumLogFileName.TerminaLog)
        return iAFfectedCount
    }

    private fun clearSettlementRevBtn () {
        helperLog.appendLine(helperLogClassName, "ClearSettlementReversal Button OnClick")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            requireActivity().runOnUiThread {
                showProgress("Clear Settlement Reversal", "Clearing... ")
            }

            // background work
            ReversalBatchTableRepo.truncateTable(requireContext())

            withContext(Dispatchers.Main) {
                showToast("Successfully Cleared...", Toast.LENGTH_SHORT)
            }

            delay(1000)

            withContext(Dispatchers.Main) {
                hideProgress()
            }
        }
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        findNavController().popBackStack()
    }
}