package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.sc.mf919.R
import com.sc.mf919.java.activity.EmvTag
import com.sc.mf919.java.activity.Global
import com.sc.mf919.java.activity.Keypad
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.activity.onKeypadEventListener
import com.sc.mf919.java.utils.EmvUtil
import utils.HexUtil
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelBatchTable
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.helper_common.BaseActivity
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919.kotlin.helper_common.iso.IsoHelperNew
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdjustmentActivity: BaseActivity() {
    lateinit var mContext: Context
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    lateinit var tvRRN: EditText
    lateinit var tvApprCode: EditText
    lateinit var tvAmount: TextView
    lateinit var keypad: Keypad
    lateinit var keypadContainer: View

    private var adjustmentType: String = ""

    // Selected transaction (from batchTable) + values decoded from its batchData
    private var batchTableModel: DbModelBatchTable? = null
    private var oriAmount: String = ""
    private var cardPan: String = ""
    private var transRRN: String = ""
    private var approvalCode: String = ""


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adjustment)
        val toolbar = findViewById<Toolbar>(R.id.toolbarMoto)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
        mContext = this@AdjustmentActivity
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Adjustment Activity"
        )
        adjustmentType = intent.getStringExtra("adjustmentType") ?: ""
        helperLog.appendLine(helperLogClassName, "Initialize Adjustment Activity >> Type[$adjustmentType]")
        TransData.reset(mContext)

        // RRN and Approval Code are typed on the system keyboard (Approval Code is
        // alphanumeric, which the numeric Keypad cannot produce). The Keypad drives the
        // amount only, and stays hidden until the amount field is tapped.
        tvRRN = findViewById(R.id.tvRRN)
        tvApprCode = findViewById(R.id.tvApprCode)
        tvAmount = findViewById(R.id.tvAmount)
        tvAmount.setOnClickListener(onClickListener)
        keypadContainer = findViewById(R.id.keypadContainer)
        keypad = findViewById(R.id.keypad1)
        keypad.registerOnOKEventListener(mListener)

        // Only one input surface at a time - going back to a text field hides the keypad.
        val hideKeypadOnFocus = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) keypadContainer.visibility = View.GONE
        }
        tvRRN.onFocusChangeListener = hideKeypadOnFocus
        tvApprCode.onFocusChangeListener = hideKeypadOnFocus

        // Done on the Approval Code moves the operator straight to the amount.
        tvApprCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                showAmountKeypad()
                true
            } else {
                false
            }
        }

        /*val cardNumber = intent.getStringExtra("cardNumber")
        val expDate = intent.getStringExtra("expDate")
        val amount = intent.getStringExtra("txnAmt")
        posReference = intent.getStringExtra("posReference")*/
        /*if (cardNumber != null
            && expDate != null
            && amount  != null) {
            helperLog.appendLine(helperLogClassName, "MOTO Request from Intent >> CardNo[${Utils.hideCardDetails(cardNumber)}], ExpDate[$expDate], Amount[$amount] ")
            tvCardNo?.text = cardNumber
            tvExpDt?.text = expDate
            tvAmount?.text = amount
            motoSales(cardNumber, expDate, amount).execute()
        }*/
    }

    // The keypad now only owns the amount, so OK is the single commit point for the
    // whole form - RRN and Approval Code are validated here rather than step by step.
    @RequiresApi(Build.VERSION_CODES.O)
    var mListener = onKeypadEventListener { isOK, msg ->
        println(msg)
        if (isOK && msg != null) {
            if (keypad.textViewID == R.id.tvAmount) {
                submitAdjustment(msg)
            }
            return@onKeypadEventListener
        }
        customOnBackPress()
    }

    var onClickListener: View.OnClickListener = object : View.OnClickListener {
        override fun onClick(v: View) {
            if (v.id == R.id.tvAmount) {
                helperLog.appendLine(helperLogClassName, "Selected :: Amount field [ADJUSTMENT]")
                showAmountKeypad()
                // setFilter() hands the TextView's click listener to the Keypad itself,
                // which would swallow later taps and leave the keypad hidden - take it back.
                tvAmount.setOnClickListener(this)
            }
        }
    }

    private fun showAmountKeypad() {
        // Close the IME before clearing focus - closeKeyboard() needs a live window token.
        closeKeyboard()
        tvRRN.clearFocus()
        tvApprCode.clearFocus()
        tvAmount.text = "0.00"
        keypad.setFilter(tvAmount, true, 12)
        keypadContainer.visibility = View.VISIBLE
        // clearFocus() hands focus back to the first focusable view - tvRRN - which
        // leaves it looking active and can re-open the IME. Park focus on the keypad.
        keypadContainer.requestFocus()
    }

    private fun closeKeyboard() {
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        // Any view in the window carries the same token, so take it from the keypad
        // container rather than currentFocus - focus may already have been cleared,
        // and tapping the amount TextView can leave currentFocus null anyway.
        val token = (currentFocus ?: keypadContainer).windowToken
        inputMethodManager.hideSoftInputFromWindow(token, 0)
    }

    /**
     * Validate the full form and start the lookup. Approval Code is uppercased because
     * receiptUpload.APPR_CODE is a plain TEXT column (case-sensitive "="), and the same
     * value goes on to TransData.prevApprovalCode / ISO field 38, which is 6 alphanumeric.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun submitAdjustment(amount: String) {
        val rrn = tvRRN.text.toString().trim()
        val apprCode = tvApprCode.text.toString().trim().uppercase()

        if (rrn.length != 12) {
            Toast.makeText(applicationContext, "Invalid RRN", Toast.LENGTH_SHORT).show()
            helperLog.appendLine(helperLogClassName, "REJECT :: RRN must be 12 digits")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return
        }
        if (apprCode.length != 6) {
            Toast.makeText(applicationContext, "Invalid Approval Code", Toast.LENGTH_SHORT).show()
            helperLog.appendLine(helperLogClassName, "REJECT :: approval code must be 6 characters")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return
        }
        if (amount == "0.00") {
            Toast.makeText(applicationContext, "Invalid Amount", Toast.LENGTH_SHORT).show()
            helperLog.appendLine(helperLogClassName, "REJECT :: amount must be greater than 0")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return
        }
        // Show the operator the normalised value that is actually being sent.
        tvApprCode.setText(apprCode)
        helperLog.appendLine(helperLogClassName, "Amount entered :: $amount")
        helperLog.appendLine(helperLogClassName, "Validation passed :: searching transaction RRN[$rrn] APPR[$apprCode]")
        searchTransaction(rrn, apprCode, amount)
    }

    /**
     * Look up the transaction by RRN + Approval Code. batchTable has no RRN/Approval
     * Code columns, so we locate it through receiptUpload and INNER JOIN back to
     * batchTable, returning the batch record (with batchData for processing). The join
     * also guarantees the transaction is still unsettled (still present in batchTable).
     * The DB query runs on Dispatchers.IO; UI work stays on the main dispatcher and
     * the whole flow is lifecycle-scoped (auto-cancelled if the activity is destroyed).
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun searchTransaction(rrn: String, apprCode: String, adjAmount: String) {
        lifecycleScope.launch {
            startProgressDialog(mContext, "Finding the Transaction", "Searching...")
            val batch = withContext(Dispatchers.IO) {
                try {
                    ReceiptUploadRepo.getUnsettledBatchByRrnAndApprCode(mContext, rrn, apprCode)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    helperLog.appendLine(helperLogClassName, "Search Transaction Exception >> ${ex.message}")
                    helperLog.logToFile(EnumLogFileName.TerminaLogException)
                    null
                }
            }
            closeProgressDialog()
            if (batch == null) {
                helperLog.appendLine(helperLogClassName, "REJECT :: transaction not found / already settled >> RRN[$rrn] APPR[$apprCode]")
                Toast.makeText(applicationContext, "Transaction not found or already settled", Toast.LENGTH_SHORT).show()
                helperLog.logToFile(EnumLogFileName.TerminaLog)
            } else {
                batchTableModel = batch
                // RRN + Approval Code come straight from what the user typed -
                // no need to decode them from batchData again.
                transRRN = rrn
                approvalCode = apprCode
                decodeBatchData(batch)
                helperLog.appendLine(helperLogClassName, "Transaction found >> INV[${batch.invNo}] AMT[$oriAmount]")
                showAdjustmentConfirmationDialog(batch, adjAmount)
            }
        }
    }

    /**
     * Decode only the fields the user did NOT type (card number + original amount)
     * from the batch record's TLV batchData. RRN and Approval Code are taken from
     * the user's keypad input instead.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun decodeBatchData(batch: DbModelBatchTable) {
        val bBatchInfo = HexUtil.hexStringToByte(batch.batchData)
        val emvTag = EmvTag()
        val bTxnAmt = ByteArray(6)
        val bCardPan = ByteArray(12)

        emvTag.getValueFrom(bBatchInfo, "DF04", bTxnAmt)
        val bCardPanLen = emvTag.getValueFrom(bBatchInfo, "DF02", bCardPan)

        oriAmount = HexUtil.bytesToHexString(bTxnAmt)
        cardPan = HexUtil.bytesToHexString(bCardPan, 0, bCardPanLen).replace("F", "") /*Remove padding "F"*/
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showAdjustmentConfirmationDialog(batch: DbModelBatchTable, adjAmount: String) {
        helperLog.appendLine(helperLogClassName, "Dialog opened :: [CONFIRM ADJUSTMENT]")
        val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
        val inflater = this.layoutInflater
        @SuppressLint("InflateParams") val dialogView = inflater.inflate(
            R.layout.activity_adjustment_confirmation, null
        )
        (dialogView.findViewById<View>(R.id.cardNo_tr) as TextView).text = Utils.hideCardDetails(cardPan)
        (dialogView.findViewById<View>(R.id.invoice_tr) as TextView).text = batch.invNo
        (dialogView.findViewById<View>(R.id.authCode_tr) as TextView).text = approvalCode
        (dialogView.findViewById<View>(R.id.oriAmount_tr) as TextView).text = Utils.getActualAmount(oriAmount)
        (dialogView.findViewById<View>(R.id.adjAmount_tr) as TextView).text = adjAmount

        val cancelBtn = dialogView.findViewById<LinearLayout>(R.id.cancel_btn)
        cancelBtn.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [CONFIRM ADJUSTMENT]")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            alertDialog?.dismiss()
        }

        val confirmBtn = dialogView.findViewById<Button>(R.id.adjustmentConfirmBtn)
        confirmBtn.setOnClickListener {
            helperLog.appendLine(helperLogClassName, "Selected :: Confirm [CONFIRM ADJUSTMENT]")
            alertDialog?.dismiss()
            lifecycleScope.launch {
                startDummyAdjustment(batch, adjAmount)
            }
        }

        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog = alertDialogBuilder.create()
        alertDialog?.show()
    }

    /**
     * Dummy adjustment process against the acquirer. The host round-trip runs on
     * Dispatchers.IO with a 5s cancellable delay, then returns a failure result.
     * Lifecycle-scoped, so backing out during the wait cancels it cleanly.
     * batch.batchData holds the original transaction data for real processing later.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun startDummyAdjustment(batch: DbModelBatchTable, adjAmount: String) {
        val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
            TransData.startTime = System.currentTimeMillis()
            val merchantConfig = ServiceHolder.getMerchantInfo()
            TransData.tpaMid = DbModelMerchantConfig.getSafeValue(merchantConfig, "ScMid")
            TransData.tpaTid = DbModelMerchantConfig.getSafeValue(merchantConfig, "ScTid")

            ServiceHolder.saleModelCache?.let {
                //TransData.salesType = it.SalesType
                TransData.acqCode = it.AcqCode ?: ""
                //TransData.mid = it.AcqMid ?: Utils.paddingWith("", "0", 12, true)
                //TransData.tid = it.AcqTid ?: Utils.paddingWith("", "0", 8, true)
                TransData.product = it.Product ?: ""
                TransData.productName = it.ProductName ?: ""
                //TransData.productCode = it.Product ?: ""
                TransData.eppTenure = it.EppTenure ?: ""
                TransData.eppTenureCode = it.EppTenureCode ?: ""
                TransData.ksn = it.Ksn ?: ""
                TransData.pinKsn = it.PinKsn ?: ""
                TransData.isTpaAccount = it.IsTpaAccount?.lowercase() == "true"
            }

            val txnDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
            TransData.transDateAsci = txnDt

            TransData.mid = batch.mid
            TransData.tid = batch.tid
            TransData.schemeId = batch.schemeId
            TransData.schemeTag = batch.schemeTag

            TransData.stan = IsoBatchInfoRepo.allocateCounter(mContext, "stan", TransData.schemeTag)
            helperLog.appendLine(helperLogClassName, "STAN :: ${TransData.stan}")

            val invoiceNo = IsoBatchInfoRepo.allocateCounter(mContext, "invoiceNo", "pos")

            TransData.invoiceNo = batch.invNo
            helperLog.appendLine(helperLogClassName, "Invoice No :: ${TransData.invoiceNo}")
            TransData.prevStan = batch.stan
            TransData.prevInvoice = batch.invNo
            TransData.prevApprovalCode = approvalCode
            TransData.prevRRN = transRRN

            TransData.batchNo = batch.batchNo
            TransData.amount = adjAmount.replace(".", "").toLong()
            HexUtil.hexStringToByte(Utils.zeroPadding(TransData.amount.toString(), 12)).copyInto(TransData.amountAuth)

            TransData.maskedPan = Utils.hideCardDetails(cardPan)
            TransData.hashedPan = cardPan.substring(0, 9)
            val bytePan = HexUtil.hexStringToByte(cardPan)
            bytePan.copyInto(TransData.pan, 0)
            TransData.panLen = cardPan.length
            val oldTransDb = HexUtil.hexStringToByte(batch.batchData)
            oldTransDb.copyInto(TransData.transactionDb, 0, 0, oldTransDb.size)
            TransData.transactionDbLen = oldTransDb.size - 2
            TransData.entryModeLabel = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_ENTRY_MODE, 256)
            TransData.cvm = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_CVM, 16)
            TransData.aid = TransData.getFromTransactionDb(Global.cube.CUBE_TAG_CARD_AID, 16)

            //TODO for transaction before revamp version
            if(TransData.aid == "" && TransData.cvm == "") {
                TransData.aid = TransData.getFromTransactionDb(Global.iso.tag.AID, 16)
                TransData.cvm = "1F0303"
                TransData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARD_AID, TransData.aid)
                TransData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARD_CVM, TransData.cvm)
            }

            val isNotCompl = booleanArrayOf(true)
            object : Thread() {
                @RequiresApi(Build.VERSION_CODES.O)
                override fun run() {
                    super.run()
                    TransData.txnTypeLabel = "Adjustment"
                    if(adjustmentType == "debit_adjustment") {
                        TransData.txnTypeLabel = "Debit Adjustment"
                    } else if (adjustmentType == "credit_adjustment") {
                        TransData.txnTypeLabel = "Credit Adjustment"
                    }

                    IsoActivity.processAdjustment(mContext, helperLog, adjustmentType)
                    helperLog.appendLine(helperLogClassName, "Trans Result :: ${TransData.transResult}")
                    helperLog.appendLine(helperLogClassName, "Resp Code :: ${TransData.respCode}")
                    isNotCompl[0] = false
                }
            }.start()

            while (isNotCompl[0]) {
                val isoComm = ServiceHolder.isoComm
                if (isoComm != null) {
                    pDMsg = isoComm.connectionStatus
                    if (pDMsg != null && pDMsg!!.isNotEmpty()) {
                        runOnUiThread(changeMessage)
                    }
                }
                withContext(Dispatchers.IO) {
                    Thread.sleep(500)
                }
            }

            //Reversal
            if(TransData.transResult != Global.iso.err.txnApproved && (TransData.transResult == Global.iso.err.communicationTimeout || TransData.respCode.isEmpty())) {
                ServiceHolder.isoComm = null
                isNotCompl[0] = true
                object : Thread() {
                    override fun run() {
                        super.run()
                        var loop = 0
                        val maxLoop = 3
                        while (loop < maxLoop) {
                            loop++
                            pDTitle = "Reversal ($loop)"
                            runOnUiThread(changeTitle)

                            val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "reversal")
                            acquirerRevIsoModel?.let { revIsoModel ->
                                val allIsoString = HexUtil.bytesToHexString(TransData.transactionDb, 0, TransData.transactionDbLen + 2)
                                val result = IsoActivity.processReversal(mContext, false, revIsoModel, allIsoString, true, helperLog)
                                helperLog.appendLine(helperLogClassName, "reversal result :: $result")
                                if(result != null){
                                    loop = maxLoop // used for exit
                                }
                            }
                        }
                        isNotCompl[0] = false
                    }
                }.start()

                while (isNotCompl[0]) {
                    val isoComm = ServiceHolder.isoComm
                    if (isoComm != null) {
                        pDMsg = isoComm.connectionStatus
                        if (pDMsg != null) {
                            if (pDMsg!!.isNotEmpty()) {
                                runOnUiThread(changeMessage)
                            }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        Thread.sleep(500)
                    }
                }
            }
            //Reversal
        }

        ServiceHolder.isoComm = null
        startProgressDialog(mContext, "Bank Authorization", "Waiting for Approval")
        startCoroutine.join()
        helperLog.appendLine(helperLogClassName, "Void Transaction End")

        CoroutineScope(Dispatchers.IO).launch {
            helperLog.appendLine(helperLogClassName, "Send Void Receipt to TMS")
            //sendTmsVoidReceipt()
        }
        closeProgressDialog()

        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        var intent = Intent(mContext, TransactionResultActivity::class.java)
        if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE") /*&& tempModel == ServiceHolder.SR800_MODEL*/) {
            intent = Intent(mContext, DenominationTransactionResultActivity::class.java)
        }
        helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> ${intent.component?.shortClassName}")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    fun customOnBackPress() {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, abandoning adjustment")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }

        ServiceHolder.appIntent = false
        ServiceHolder.appHTTP = false
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "AdjustmentActivity OnDestroy :: adjustment screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}