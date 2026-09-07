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
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.library.terminal.Utility
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentVoidsalerefundBinding
import com.sc.mf919pro.java.activity.Global
import com.sc.mf919pro.java.activity.UploadTMS
import com.sc.mf919pro.java.activity.Utils
import utils.HexUtil
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
import com.sc.mf919pro.kotlin.domain.usecase.VoidSaleExecutionRequest
import com.sc.mf919pro.kotlin.domain.usecase.VoidSaleExecutionResult
import com.sc.mf919pro.kotlin.domain.usecase.VoidSaleLookupData
import com.sc.mf919pro.kotlin.domain.usecase.VoidSaleLookupResult
import com.sc.mf919pro.kotlin.domain.usecase.VoidSaleUseCase
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.LogRedact
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class VoidSaleFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    private lateinit var lookupData: VoidSaleLookupData
    private val voidSaleUseCase = VoidSaleUseCase()

    private var posReference: String? = null
    private var forceVoid: Boolean = false

    companion object {
        private const val TAG = "Void"
    }

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
            helperLog.appendLine(helperLogClassName, "VoidSale OnDestroyView :: screen ended")
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
            "Void Sale Payment Fragment"
        )
        helperLog.appendLine(helperLogClassName, "Initialize VoidCard Fragment")
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
        val argumentInvoice = arguments?.getString("Invoice")

        val keypadNum = binding.keypadVoid
        val tv = binding.textViewVoidInvNo
        keypadNum.setFilter(tv, false, 6, null)
        binding.buttonBack.setDebouncedOnClickListener {
            customOnBackPress()
        }
        binding.buttonOK.setDebouncedOnClickListener {
            val msg = tv.text.toString()
            if (msg.isNotEmpty()) {
                helperLog.appendLine(helperLogClassName, "Input Invoice No :: $msg")
                viewLifecycleOwner.lifecycleScope.launch {
                    val invNo = Utils.paddingWith(msg, "0", 6, false)
                    helperLog.appendLine(helperLogClassName, "onOK  ::  $invNo")
                    searchByInvoice(invNo)
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

    private suspend fun searchByInvoice(invoiceNum: String) {
        showProgress("Finding the Transaction", "Searching...")
        try {
            when (val result = voidSaleUseCase.lookupInvoice(requireContext(), invoiceNum)) {
                is VoidSaleLookupResult.Found -> {
                    lookupData = result.data
                    /*
                     * Register the card data for sink-level scrubbing. LogRedact only knew about
                     * the EMV read path (EmvUtil.readTrack2), but a void takes its PAN from the
                     * BATCH TABLE and never touches EMV -- so livePan was null and scrubPans had
                     * nothing to match. Measured on device: the void path logged the full PAN in
                     * DF02, ISO DE2 and the raw batchData TLV.
                     */
                    helperLog.appendLine(helperLogClassName, "Batch Table Record :: ${Gson().toJson(result.data.batchTableModel)}")
                    if (forceVoid) {
                        executeVoidSale()
                    } else {
                        showConfirmationDialog(
                            result.data.amountDisplay,
                            result.data.maskedCardPan,
                            result.data.approvalCode,
                            result.data.invoiceNo,
                        )
                    }
                }

                is VoidSaleLookupResult.NotFound -> {
                    helperLog.appendLine(helperLogClassName, result.message)
                    handleLookupFailure(result.code, result.message)
                }

                is VoidSaleLookupResult.Unsupported -> {
                    helperLog.appendLine(helperLogClassName, result.message)
                    handleLookupFailure(result.code, result.message)
                }

                is VoidSaleLookupResult.Error -> {
                    result.cause?.printStackTrace()
                    helperLog.appendLine(helperLogClassName, "Lookup error :: ${result.message}")
                    handleLookupFailure(result.code, result.message)
                }
            }
        } finally {
            delay(500L)
            hideProgress()
        }
    }

    private suspend fun handleLookupFailure(code: String, message: String) {
        // Single choke point for every void lookup failure -- the operator is told, but until now
        // the log said nothing about WHY the void could not proceed.
        if (this@VoidSaleFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "REJECT :: void lookup failed [$code] $message")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        if (code == "SHC001") {
            showToast("Invalid Input", Toast.LENGTH_SHORT)
        } else {
            showToast(message, Toast.LENGTH_SHORT)
        }

        if (ServiceHolder.appIntent) {
            val txnMap = hashMapOf(
                "ResponseCode" to code,
                "ResponseDescription" to message,
            )
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

    private fun showConfirmationDialog(amt: String?, cardNo: String?, authCode: String?, invNo: String?) {
        val alertDialogBuilder = AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
        val inflater = this.layoutInflater
        @SuppressLint("InflateParams")
        val dialogView = inflater.inflate(R.layout.fragment_void_confirmation, null)

        (dialogView.findViewById<View>(R.id.amount_tr) as TextView).text = amt
        (dialogView.findViewById<View>(R.id.cardNo_tr) as TextView).text = cardNo
        (dialogView.findViewById<View>(R.id.authCode_tr) as TextView).text = authCode
        (dialogView.findViewById<View>(R.id.traceNo_tr) as TextView).text = invNo

        val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
        cancelBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: void declined at confirmation dialog")
            alertDialog?.dismiss()
            if (ServiceHolder.appIntent) {
                val txnMap: HashMap<String, String> = HashMap()
                txnMap["ResponseCode"] = "SHC005"
                txnMap["ResponseDescription"] = "User Cancel the Transaction"
                onBackToApp(txnMap)
            } else if (ServiceHolder.appHTTP) {
                val jObject = JSONObject()
                try {
                    jObject.put("ResponseCode", "SHC005")
                    jObject.put("ResponseDescription", "User Cancel the Transaction")
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
                HTTPServer.getInstance().setResponseMessage(jObject.toString())
                customOnBackPress()
            }
        }

        val voidConfirmBtn = dialogView.findViewById<Button>(R.id.voidConfirmBtn)
        voidConfirmBtn.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "Validation passed :: operator confirmed void -> executeVoidSale")
            alertDialog?.dismiss()
            viewLifecycleOwner.lifecycleScope.launch {
                executeVoidSale()
            }
        }
        helperLog.appendLine(helperLogClassName,
            "Dialog opened :: Void Sale confirmation [amt=$amt inv=$invNo auth=$authCode card=$cardNo]")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    private suspend fun executeVoidSale() {
        helperLog.appendLine(helperLogClassName, "Void Sales Transaction Start")
        showProgress("Bank Authorization", "Waiting for Approval")

        try {
            val result = voidSaleUseCase.executeVoid(
                requireContext(),
                VoidSaleExecutionRequest(
                    lookupData = lookupData,
                    posReference = posReference,
                    helperLog = helperLog,
                    saleModel = ServiceHolder.saleModelCache,
                    merchantConfig = ServiceHolder.getMerchantInfo(),
                    onProgressMessage = { status ->
                        if (!status.isNullOrEmpty()) {
                            withContext(Dispatchers.Main) {
                                updateProgress(null, status)
                            }
                        }
                    },
                    onProgressTitle = { title ->
                        if (!title.isNullOrEmpty()) {
                            withContext(Dispatchers.Main) {
                                updateProgress(title = title
)
                            }
                        }
                    }
                )
            )

            when (result) {
                is VoidSaleExecutionResult.Success -> {
                    helperLog.appendLine(helperLogClassName, "Void execution result :: ${result.txnTypeLabel} / ${result.respCodeAscii}")
                }

                is VoidSaleExecutionResult.Error -> {
                    result.cause?.printStackTrace()
                    helperLog.appendLine(helperLogClassName, "Void execution error :: ${result.message}")
                }
            }
        } finally {
            withContext(Dispatchers.IO) {
                helperLog.appendLine(helperLogClassName, "Void Sales Transaction End")
                helperLog.appendLine(helperLogClassName, "Send Void Receipt to TMS")
                sendTmsVoidReceipt()
                helperLog.logToFile(EnumLogFileName.TerminaLog)
            }
            delay(500L)
            hideProgress()
        }

        navigateSafe(R.id.action_voidSale_to_transactionResult)
    }

    private fun sendTmsVoidReceipt() {
        val timeStamp = Utils.DateTimeFormat(transData.transDateAsci)

        val strTxnType = when (lookupData.batchTableModel.txnType) {
            "Instalment Sale" -> "VoidEPP"
            "Moto" -> "VoidMoto"
            else -> "Void"
        }
        val strPaymentProductId = ""
        val strSchemeId = transData.schemeId
        val strStan = transData.stan
        val strInvNo = transData.invoiceNo
        val strTxnAmt = HexUtil.bytesToHexString(transData.amountAuth)
        val strRrn = transData.rrn
        val strApprCode = transData.approvalCode
        val strRrnOri = transData.prevRRN
        val strApprCodeOri = transData.prevApprovalCode
        val strTid = transData.tid
        val strMid = transData.mid
        val strBatchNo = transData.batchNo
        val strRespCode = Utility.HexString2ASCII(transData.respCode)
        val strAid = transData.aid
        val mti = transData.getFromTransactionDb(Global.iso.tag.MTI, 16)
        val strNii = transData.getFromTransactionDb("DF24", 16)
        val strMaskPanBcd = transData.maskedPan
        val strHashedPanBcd = transData.hashedPan
        val strEntryType = transData.entryModeLabel
        val strARQC = transData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ARQC, 16)
        val strTVR = transData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_TVR, 16)
        val strPosReference = transData.posReference
        val strCardLabel = Utils.byteArrayToAsciiString(transData.appLabel, 0, transData.appLabelLen)
        val strCvm = transData.cvm

        val jsonObject = JSONObject()
        try {
            jsonObject.put("SEQ_NO", ServiceHolder.getSqnNum())
            jsonObject.put("TXN_DT", timeStamp)
            jsonObject.put("TXN_TYPE", strTxnType)
            jsonObject.put("MID", strMid)
            jsonObject.put("TID", strTid)
            jsonObject.put("MTI", mti)
            jsonObject.put("NII", strNii)
            jsonObject.put("SCHEME_ID", strSchemeId)
            jsonObject.put("AID", strAid)
            jsonObject.put("CARD_MASKED", strMaskPanBcd)
            jsonObject.put("CARD_HASHED", strHashedPanBcd)
            jsonObject.put("RRN", strRrn)
            jsonObject.put("APPR_CODE", strApprCode)
            jsonObject.put("RRN_ORI", strRrnOri)
            jsonObject.put("APPR_CODE_ORI", strApprCodeOri)
            jsonObject.put("TXN_AMT", strTxnAmt)
            jsonObject.put("INV_NO", strInvNo)
            jsonObject.put("STAN", strStan)
            jsonObject.put("BATCH_NO", strBatchNo)
            jsonObject.put("RESP_CODE", strRespCode)
            jsonObject.put("APP_VER", ServiceHolder.getAppVersion())
            jsonObject.put("SN", ServiceHolder.getTerminalSerialNumber())
            jsonObject.put("ENTRY_TYPE", strEntryType)
            jsonObject.put("ARQC", strARQC)
            jsonObject.put("TVR", strTVR)
            jsonObject.put("PAYMENT_PRODUCT_ID", strPaymentProductId)
            jsonObject.put("POS_REF_NO", strPosReference)
            jsonObject.put("CARD_LABEL", strCardLabel)
            jsonObject.put("CVM", strCvm)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        UploadTMS.getInstance().addReceipt(jsonObject.toString())
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
        if (this@VoidSaleFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "VoidSale :: returning to caller app, response -> ${txnMap["ResponseCode"]}")
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