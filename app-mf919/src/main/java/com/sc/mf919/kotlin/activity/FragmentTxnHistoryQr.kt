package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919.kotlin.database.model.DbModelTransactionQrGet
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog

class FragmentTxnHistoryQr : Fragment() {
    private var listener: OnFragmentInteractionListener? = null

    private lateinit var scrollView: ScrollView
    private lateinit var container: LinearLayout

    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    /**
     * loadNextBatch runs from a scroll listener and its click listeners fire later still, so a
     * lateinit read here is not guaranteed safe if the view was torn down. Fall back to the old
     * Timber path rather than throwing -- same shape as EmvActivity.logEmv.
     */
    private fun logTxnHistory(msg: String) {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, msg)
        } else {
            Utils.debugLogPrint("FragmentTxnHistoryQr", msg)
        }
    }

    private var tList: ArrayList<DbModelTransactionQrGet>? = null
    private var currentIndex = 0
    private val batchSize = 20

    interface OnFragmentInteractionListener {
        fun fragmentTxnHistoryQrAction()

        fun fragmentQrHistoryEnquiry(dbModelTransactionQrGet: DbModelTransactionQrGet)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnFragmentInteractionListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement OnFragmentInteractionListener")
        }
    }
    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_txn_history_card, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireActivity().applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "QR Transaction History List Fragment"
        )

        tList = arguments?.getParcelableArrayList<DbModelTransactionQrGet>("transList")
        helperLog.appendLine(helperLogClassName, "QR history list rendered :: ${tList?.size ?: 0} transaction(s)")
        scrollView = view.findViewById(R.id.cardHistoryScroll)
        container = view.findViewById(R.id.cardhistoryList)
        container.removeAllViews()

        if(tList?.isNotEmpty() == true) {
            loadNextBatch()
            scrollView.viewTreeObserver.addOnScrollChangedListener {
                val scrollViewChild = scrollView.getChildAt(scrollView.childCount - 1)
                val diff = scrollViewChild.bottom - (scrollView.height + scrollView.scrollY)
                if (diff < 200) { // near bottom
                    loadNextBatch()
                }
            }
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)

            /*txnList?.forEachIndexed { _, txn ->
            val linearLayout = LinearLayout(context)
            linearLayout.orientation = LinearLayout.VERTICAL

            val gridViewLayout1 = renderTrx(txn)
            linearLayout.addView(gridViewLayout1)

            val dashedLineView = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.default_gap)
                )
                background = ContextCompat.getDrawable(context, R.drawable.dashed_line)
            }

            linearLayout.addView(dashedLineView)

            gridViewLayout1.setDebouncedOnClickListener {
                if(txn.respCode == "0000"){
                    val intent = Intent(context, TransactionViewQrDetailsActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    Utils.debugLogPrint("TAG", "refID test >> ${txn.refId}")
                    intent.putExtra("refId", txn.refId.toString())
                    startActivity(intent)
                } else {
                    listener?.fragmentQrHistoryEnquiry(txn)
                }
            }

            parentLayout.addView(linearLayout)
        }*/
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun loadNextBatch() {
        val end = minOf(currentIndex + batchSize, tList!!.size)
        for (i in currentIndex until end) {
            val txn = tList!![i]
            val linearLayout = LinearLayout(context)
            linearLayout.orientation = LinearLayout.VERTICAL

            val gridViewLayout1 = renderTrx(txn)
            linearLayout.addView(gridViewLayout1)

            val dashedLineView = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.default_gap)
                )
                background = ContextCompat.getDrawable(context, R.drawable.dashed_line)
            }

            linearLayout.addView(dashedLineView)

            gridViewLayout1.setDebouncedOnClickListener {
                if(txn.respCode == "0000"){
                    logTxnHistory("Selected :: refId ${txn.refId ?: "-"} [QR TRANSACTION HISTORY]")
                    logTxnHistory("Validation passed :: navigate -> TransactionViewQrDetailsActivity")
                    if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
                    val intent = Intent(context, TransactionViewQrDetailsActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    intent.putExtra("refId", txn.refId.toString())
                    startActivity(intent)
                } else {
                    logTxnHistory("Selected :: refId ${txn.refId ?: "-"} (respCode ${txn.respCode ?: "-"}) [QR TRANSACTION HISTORY] -> re-enquiry")
                    if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
                    listener?.fragmentQrHistoryEnquiry(txn)
                }
            }
            container.addView(linearLayout)
        }
        currentIndex = end
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun renderTrx(txn: DbModelTransactionQrGet): GridLayout {
        val gridLayout = GridLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            )
            columnCount = 2
            setPadding(Helper.getInstance().dpToPx(8), 0, Helper.getInstance().dpToPx(8), 0) // Set left and right margin
        }

        var refId = "-"
        var scheme = "-"
        val txnStatus = (txn.txnType.toString()).trim()
        val qrTransResult = txn.respCode == "0000"
        val txnDateTime = if(txnStatus == "Void") txn.voidDateTime ?: "" else txn.txnDateTime ?: ""


        try {
            txn.refId?.let {
                refId = it
            }

            txn.productName?.let {
                scheme = it
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

        for (i in 0 until 4) {
            val textView = TextView(context).apply {
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Helper.getInstance().dpToPx(3), Helper.getInstance().dpToPx(3), Helper.getInstance().dpToPx(3), Helper.getInstance().dpToPx(3))

                text = when (i) {
                    0 -> refId
                    1 -> "RM" + Utils.getActualAmount(txn.txnAmount.toString())
                    2 -> txnDateTime
                    3 -> scheme
                    else -> ""
                }
                textSize = 12f
                if (i == 0 || i == 1) {
                    setTypeface(null, Typeface.BOLD)
                    textSize = 15f
                }

                if (i == 1 || i == 3) {
                    gravity = Gravity.END
                }

                if (i == 1) {
                    if (txnStatus == "Sale") {
                        if(qrTransResult) {
                            setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.colorGreen))
                        } else {
                            setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.colorRed))
                        }
                    } else if (txnStatus == "Void") {
                        setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.colorGreyD))
                        paintFlags = Paint.STRIKE_THRU_TEXT_FLAG
                    }
                } else {
                    setTextColor(Color.BLACK)
                }
            }

            val linearLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f)
                }
                addView(textView)
            }

            gridLayout.addView(linearLayout)
        }
        return gridLayout
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "FragmentTxnHistoryQr OnDestroyView :: QR history list ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

//    private fun dpToPx(dp: Int): Int {
//        return TypedValue.applyDimension(
//            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
//        ).toInt()
//    }
}