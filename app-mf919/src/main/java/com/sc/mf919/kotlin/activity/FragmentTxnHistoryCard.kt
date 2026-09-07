package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
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
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import java.util.Locale

class FragmentTxnHistoryCard : Fragment() {
    private var listener: OnFragmentInteractionListener? = null

    private lateinit var scrollView: ScrollView
    private lateinit var container: LinearLayout

    private var tList: ArrayList<DbModelPrintReceipt>? = null
    private var currentIndex = 0
    private val batchSize = 20

    interface OnFragmentInteractionListener {
        fun fragmentTxnHistoryCardAction()
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

    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_txn_history_card, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext().applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Card Transaction History"
        )
        helperLog.appendLine(helperLogClassName, "Card history screen opened")
        tList = arguments?.getParcelableArrayList<DbModelPrintReceipt>("transList")
        scrollView = view.findViewById(R.id.cardHistoryScroll)
        container = view.findViewById(R.id.cardhistoryList)
        container.removeAllViews()

        helperLog.appendLine(helperLogClassName, "Getting Txn Listing - Start :: ${tList?.size ?: 0} records")
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
        helperLog.appendLine(helperLogClassName, "Getting Txn Listing - End")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
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
                if(txn.respCode == "00") {
                    val intent = Intent(context, TransactionViewCardDetailsActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    intent.putExtra("stan", txn.stan)
                    startActivity(intent)
                }
            }
            container.addView(linearLayout)
        }
        currentIndex = end
    }

//    private fun dpToPx(dp: Int): Int {
//        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
//    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun renderTrx(txn: DbModelPrintReceipt): GridLayout {
        var receiptInfoMsg: Array<String> = arrayOf()

        try {
            val receiptInfos = txn.receiptInfo?.replace("[", "")?.replace("]", "")?.replace(", ", "\n")
            receiptInfoMsg = Utils.String2ArrayString(receiptInfos)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

        val gridLayout = GridLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            columnCount = 2
            setPadding(Helper.getInstance().dpToPx(8), 0, Helper.getInstance().dpToPx(8), 0) // Set left and right margin
        }

        val txnStatus = txn.txnType.trim().uppercase(Locale.ROOT)
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
                var invNo = "-"
                var scheme = "-"

                try {
                    if (receiptInfoMsg.size > 0) {
                        scheme = receiptInfoMsg[4]
                    }
                } catch (e: Exception) {
                }

                text = when (i) {
                    0 -> txn.invoiceNo
                    1 -> "RM" + Utils.getActualAmount(txn.txnAmt)
                    2 -> Utils.DateTimeFormat(txn.txnDt)
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
                    when (txnStatus) {
                        "SALE", "PRE AUTHORIZATION", "SALE COMPLETION", "CASH OUT", "INSTALMENT SALE", "MOTO"-> {
                            if(txn.respCode == "00") {
                                setTextColor(resources.getColor(R.color.colorGreen))
                            } else {
                                setTextColor(resources.getColor(R.color.colorRed))
                            }
                        }
                        "VOID", "PREAUTH CANCEL", "VOID SALE COMPLETION", "CASHOUT VOID", "VOID INSTALMENT" -> {
                            setTextColor(resources.getColor(R.color.colorGreyD))
                            paintFlags = Paint.STRIKE_THRU_TEXT_FLAG
                        }
                        else -> setTextColor(resources.getColor(R.color.colorRed))
                    }
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

        /*var isClicked = false
        gridLayout.setOnClickListener {
            isClicked = !isClicked
            if (isClicked) {
                val intent = Intent(context, TransactionViewCardDetailsActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.putExtra("stan", txn.stan)
                startActivity(intent)
            }
        }*/
        return gridLayout
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "FragmentTxnHistoryCard OnDestroyView :: history screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}
