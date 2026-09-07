package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.PrintReceiptRepo
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import helpers.HelperCommon
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransactionViewListActivity : ActivityBase(), FragmentTxnHistoryCard.OnFragmentInteractionListener {
    lateinit var editText: EditText
    var txnList: List<DbModelPrintReceipt> = listOf()

    var selectedInvNoValue = ""
    var selectedTxnTypeValue = ""

    lateinit var helperLog: HelperLog
    var helperLogClassName:String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_txnview_list)
        val toolbar = findViewById<Toolbar>(R.id.toolbarCP)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        onBackPressedDispatcher.addCallback(this@TransactionViewListActivity, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        //TODO Dynamic Layout For Small Terminal
        val displayMetrics = resources.displayMetrics
        val screenHeightPx = displayMetrics.heightPixels
        val screenHeightDp = screenHeightPx / displayMetrics.density
        println("screenHeightDp :: $screenHeightDp")

        if (screenHeightDp < 500) {
            val historyBox = findViewById<FragmentContainerView>(R.id.trxHistoryCardFrameLayout)
            val params = historyBox.layoutParams
            params.height = Helper.getInstance().dpToPx(270)
            historyBox.layoutParams = params
        }
        //TODO Dynamic Layout For Small Terminal

        // The field was declared but its construction was commented out, so any use of helperLog
        // here would have thrown on a lateinit access. Constructed for real now.
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Transaction History List"
        )
        helperLog.appendLine(helperLogClassName, "Transaction history list opened")

        val btnMoreOtp = findViewById<LinearLayout>(R.id.moreOption)
        btnMoreOtp.visibility = View.VISIBLE
        btnMoreOtp.setOnClickListener {
            if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "Dialog opened :: [PRINT HISTORY MENU]")
            val popupMenu = PopupMenu(this, btnMoreOtp)
            popupMenu.menuInflater.inflate(R.menu.print_history_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.printDetails -> {
                        val intent =
                            Intent(applicationContext, TransactionDetailsActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(intent)
                    }

                    R.id.printSummary -> {
                        val intent = Intent(applicationContext, SettlementActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        intent.putExtra("previewSettlement", 1)
                        startActivity(intent)
                        finish()
                    }

                    R.id.printLastSettlement -> {
                        val intent = Intent(applicationContext, SettlementActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        intent.putExtra("Last", 1)
                        intent.putExtra("previewLastSettlement", 1)
                        startActivity(intent)
                        finish()
                    }

                }
                true
            }
            popupMenu.show()
        }

        editText = findViewById(R.id.inv)
        editText.addTextChangedListener(callbackET)
        lifecycleScope.launch {
            getPrintReceiptList()
        }
    }

    suspend fun getPrintReceiptList() = withContext(Dispatchers.IO) {
        if(txnList.isEmpty()){
            startProgressDialog(this@TransactionViewListActivity, "", "Loading...")
            txnList = PrintReceiptRepo.getMultipleRecord(applicationContext, listOf(), listOf())
            val batchNo = IsoBatchInfoRepo.getBatchInfo(applicationContext, "batchNo", "visam")?.value ?: "000001"

            var txnCount = 0
            var txnAmount = 0L
            var voidCount = 0
            txnList.forEach {
                if(it.txnType.contains("void", true) || it.txnType.contains("cancel", true)){
                    voidCount += 1
                } else if(it.respCode == "00") {
                    txnCount += 1
                    val tempLongAmount = it.txnAmt.toLongOrNull() ?: 0
                    txnAmount += tempLongAmount
                }
            }
            runOnUiThread {
                (findViewById<View>(R.id.batch_no) as TextView).text = batchNo
                (findViewById<View>(R.id.sale_count) as TextView).text = txnCount.toString()
                (findViewById<View>(R.id.sale_amt) as TextView).text = Utils.getActualAmount(txnAmount.toString())
                (findViewById<View>(R.id.void_count) as TextView).text = voidCount.toString()
            }
            delay(500)
            closeProgressDialog()
        }

        val filteredTxnList = txnList.filter { txn ->
            txn.invoiceNo.contains(selectedInvNoValue, ignoreCase = true) && txn.txnType.contains(selectedTxnTypeValue, ignoreCase = true)
        }
        processCardHistoryFragment(filteredTxnList)
    }

    private fun processCardHistoryFragment(cardTxnList: List<DbModelPrintReceipt>){
        val bundle = Bundle()
        bundle.putParcelableArrayList("transList", ArrayList(cardTxnList))
        val fragment = FragmentTxnHistoryCard()
        fragment.arguments = bundle
        supportFragmentManager.beginTransaction().replace(R.id.trxHistoryCardFrameLayout, fragment)
            //.addToBackStack(null)
            .commit()
    }

    @SuppressLint("MissingSuperCall")
    override fun onSaveInstanceState(outState: Bundle) {
        // Do NOT call super to avoid saving a huge view hierarchy.
        // We can fully reconstruct UI from 'stan' + DB in onCreate().
        // super.onSaveInstanceState(outState)
    }

    fun tv_btn_cancel(view: View?) {
        customOnBackPress()
    }
    fun customOnBackPress() {
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
        finish()
    }

    var callbackET: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            Utils.debugLogPrint("TAG", "beforeTextChanged: $s---$count")
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            Utils.debugLogPrint("TAG", "onTextChanged: $s---$count")
            val strInvNo = s.toString()
            selectedInvNoValue = strInvNo
        }

        override fun afterTextChanged(s: Editable) {
            Utils.debugLogPrint("TAG", "afterTextChanged: $s")
            lifecycleScope.launch {
                getPrintReceiptList()
            }
        }
    }

    override fun fragmentTxnHistoryCardAction() {
        customOnBackPress()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "TransactionViewListActivity OnDestroy :: history list ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}
