package com.sc.mf919pro.kotlin.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentVoidsalerefundBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
import com.sc.mf919pro.kotlin.domain.usecase.VoidQrExecutionRequest
import com.sc.mf919pro.kotlin.domain.usecase.VoidQrExecutionResult
import com.sc.mf919pro.kotlin.domain.usecase.VoidQrLookupData
import com.sc.mf919pro.kotlin.domain.usecase.VoidQrLookupResult
import com.sc.mf919pro.kotlin.domain.usecase.VoidQrUseCase
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoidQrFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String
    private lateinit var lookupData: VoidQrLookupData

    var posReference: String? = null
    private var forceVoid: Boolean = false
    var terminalPw = ""
    private val voidQrUseCase = VoidQrUseCase()

    private var _binding: FragmentVoidsalerefundBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVoidsalerefundBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "VoidQr OnDestroyView :: screen ended")
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
            "Void QR Fragment"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Void QR Fragment")
        binding.appToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }

        transData.reset()
        posReference = arguments?.getString("posReference")
        forceVoid = (arguments?.getInt("forceVoid") ?: 0) != 0
        // Whether this void is running unattended is the first thing anyone asks when a
        // terminal in the field fails to refund an MDB vend, and it is not otherwise
        // recoverable from the log. MF919 records it at the same point.
        helperLog.appendLine(helperLogClassName, "Force Void :: $forceVoid")
        terminalPw = arguments?.getString("terminalPIN", "").toString()
        val argumentInvoice = arguments?.getString("Invoice")

        @SuppressLint("SimpleDateFormat")
        val timeStamp = java.text.SimpleDateFormat("yyMMdd").format(java.util.Calendar.getInstance().time)
        binding.textViewVoidLabel.text = "QR REF ID:"
        binding.resultImageView.setBackgroundResource(R.drawable.mf_void_qr)
        val keypadNum = binding.keypadVoid
        val tv = binding.textViewVoidInvNo
        tv.text = timeStamp
        keypadNum.setFilter(tv, false, 12, null)

        binding.buttonBack.setDebouncedOnClickListener {
            customOnBackPress()
        }
        binding.buttonOK.setDebouncedOnClickListener {
            val msg = tv.text.toString()
            if (msg.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    helperLog.appendLine(helperLogClassName, "onOK  ::  $msg")
                    searchByInvoice(msg)
                }
            } else {
                helperLog.appendLine(helperLogClassName, "Invoice No is Empty")
                showToast("Invoice No is Empty!", Toast.LENGTH_SHORT)
            }
        }

        if (!argumentInvoice.isNullOrEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                helperLog.appendLine(helperLogClassName, "onOK(arguments)  ::  $argumentInvoice")
                tv.text = argumentInvoice
                searchByInvoice(argumentInvoice)
            }
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    suspend fun searchByInvoice(tempQrRef: String) {
        showProgress("Finding the Transaction", "Searching...")
        helperLog.appendLine(helperLogClassName, "Start Search QR txn by Invoice")
        try {
            when (val result = voidQrUseCase.lookupInvoice(requireContext(), tempQrRef)) {
                is VoidQrLookupResult.Found -> {
                    lookupData = result.data
                    helperLog.appendLine(helperLogClassName, "Transaction QR Record :: ${result.data.transaction}")
                    if (forceVoid) {
                        // Unattended auto-void (MDB vend failure): nobody is there to confirm, so
                        // do exactly what Confirm does, minus the dialog. Logged because a refund
                        // silently waiting on a dialog is the failure this branch exists to avoid.
                        helperLog.appendLine(helperLogClassName,
                            "Force Void :: skipping [VOID QR CONFIRMATION]")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        delay(700)
                        executeVoidQr()
                    } else {
                        showConfirmationDialog()
                    }
                }
                is VoidQrLookupResult.NotFound -> {
                    handleLookupFailure(result.code, result.message)
                }
                is VoidQrLookupResult.Error -> {
                    result.cause?.printStackTrace()
                    handleLookupFailure(result.code, result.message)
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            delay(500L)
            hideProgress()
        }
    }

    private suspend fun handleLookupFailure(code: String, message: String) {
        showToast("Invalid Input", Toast.LENGTH_SHORT)
        if (ServiceHolder.appIntent) {
            val txnMap = hashMapOf<String, String>()
            txnMap["ResponseCode"] = code
            txnMap["ResponseDescription"] = message
            delay(500L)
            hideProgress()
            onBackToApp(txnMap)
        } else if (ServiceHolder.appHTTP) {
            val jObject = JsonObject()
            try {
                jObject.addProperty("ResponseCode", code)
                jObject.addProperty("ResponseDescription", message)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            HTTPServer.getInstance().setResponseMessage(jObject.toString())
            delay(500L)
            hideProgress()
            customOnBackPress()
        }
    }

    private fun showConfirmationDialog() {
        val alertDialogBuilder = AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
        val inflater = this.layoutInflater

        @SuppressLint("InflateParams")
        val dialogView = inflater.inflate(R.layout.fragment_voidqr_confirmation, null)
        (dialogView.findViewById<View>(R.id.amount_tr) as TextView).text = lookupData.amountDisplay
        (dialogView.findViewById<View>(R.id.cardNo_tr) as TextView).text = lookupData.productName
        (dialogView.findViewById<View>(R.id.authCode_tr) as TextView).text = lookupData.hostRefNo
        (dialogView.findViewById<View>(R.id.traceNo_tr) as TextView).text = lookupData.refId

        val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
        cancelBtn.setDebouncedOnClickListener {
            alertDialog?.dismiss()
            if (ServiceHolder.appIntent) {
                val txnMap = hashMapOf<String, String>()
                txnMap["ResponseCode"] = "SHC005"
                txnMap["ResponseDescription"] = "User Cancel the Transaction"
                onBackToApp(txnMap)
            } else if (ServiceHolder.appHTTP) {
                val jObject = JsonObject()
                try {
                    jObject.addProperty("ResponseCode", "SHC005")
                    jObject.addProperty("ResponseDescription", "User Cancel the Transaction")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                HTTPServer.getInstance().setResponseMessage(jObject.toString())
                customOnBackPress()
            }
        }

        val remarkRow = dialogView.findViewById<LinearLayout>(R.id.remark_row)
        remarkRow.visibility = View.GONE

        val voidConfirmBtn = dialogView.findViewById<Button>(R.id.voidConfirmBtn)
        voidConfirmBtn.setDebouncedOnClickListener {
            alertDialog?.dismiss()
            viewLifecycleOwner.lifecycleScope.launch {
                executeVoidQr()
            }
        }

        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    private suspend fun executeVoidQr() {
        ServiceHolder.appRunningProcess = true
        showProgress("QR Payment Authorization", "Waiting for Approval")
        try {
            val result = voidQrUseCase.executeVoid(
                requireContext(),
                VoidQrExecutionRequest(
                    lookupData = lookupData,
                    posReference = posReference,
                    terminalPw = terminalPw,
                    helperLog = helperLog,
                    transData = transData,
                )
            )

            when (result) {
                is VoidQrExecutionResult.Success -> {
                    helperLog.appendLine(helperLogClassName, "Void QR result :: ${result.respCode} / ${result.respDesc}")
                }
                is VoidQrExecutionResult.Error -> {
                    result.cause?.printStackTrace()
                    helperLog.appendLine(helperLogClassName, "Void QR error :: ${result.respCode} / ${result.respDesc}")
                }
            }
        } finally {
            hideProgress()
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            navigateSafe(R.id.action_voidQr_to_transactionResultQr)
        }
    }

    private fun customOnBackPress() {
        ServiceHolder.appIntent = false
        ServiceHolder.appHTTP = false
        val dbModelTerminalConfig = getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }

    private fun onBackToApp(txnMap: HashMap<String, String>) {
        // Handing control back to the calling POS app: finish() can be the last thing this
        // process does, and onDestroyView is not guaranteed to run to completion after it. Flush
        // here so the app-to-app hand-back is always on disk.
        if (this@VoidQrFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "VoidQr :: returning to caller app, response -> ${txnMap["ResponseCode"]}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        txnMap["TransactionType"] = ServiceHolder.txnType.toString()
        startActivity(Intent(requireContext(), TransactionTransmitter::class.java).apply {
            putExtra(TxnKeys.EXTRA_TXN_MAP, txnMap)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        requireActivity().finish()
    }
}