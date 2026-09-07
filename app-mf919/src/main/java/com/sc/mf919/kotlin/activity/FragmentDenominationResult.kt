package com.sc.mf919.kotlin.activity

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.library.terminal.Utility
import com.sc.mf919.R
import com.sc.mf919.java.activity.Global
import com.sc.mf919.java.activity.Utils
import data_enum.CardErrorDataEnum
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.data_enum.variables.TransData.transDateAsci
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog

class FragmentDenominationResult: Fragment() {
    private var listener: OnFragmentInteractionListener? = null
    private var timeSelected : Int = ServiceHolder.ackCountDownSecond
    private var timeCountDown: CountDownTimer? = null
    private var timeProgress = 0

    lateinit var linearDescription: LinearLayout
    lateinit var textViewProduct: TextView

    interface OnFragmentInteractionListener {
        fun fragmentDenominationBackAction()
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
        timeCountDown?.let {
            it.cancel()
            timeCountDown = null
        }
    }

    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_denomination_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext().applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Denomination Result Screen"
        )

//        val linearLayout = view.findViewById<LinearLayout>(R.id.buttonGroupLeft)
//        linearLayout.background.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(Color.GRAY, BlendModeCompat.SRC_ATOP)
        view.findViewById<LinearLayout>(R.id.buttonGroupLeft).setOnClickListener {
            if (this::helperLog.isInitialized) {
                helperLog.appendLine(helperLogClassName, "Selected :: Done [DENOMINATION RESULT]")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
            }
            listener?.fragmentDenominationBackAction()
        }

        val transactionResult = TransData.transResult
        helperLog.appendLine(helperLogClassName, "Denomination result :: transResult=$transactionResult " +
            "stan=${TransData.stan} rrn=${TransData.rrn}")
        if(transactionResult == Global.iso.err.txnApproved){
            //helperLog.appendLine(helperLogClassName, "Transaction Approved...")
            view.findViewById<TextView>(R.id.tvTitle).text = "Transaction Success"
            view.findViewById<TextView>(R.id.tvTitle).setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary))
            view.findViewById<ImageView>(R.id.imageViewStatus).background = AppCompatResources.getDrawable(requireContext(), R.drawable.icon_circle_green_tick)
            openNfc()
        }

        //TODO Dynamic Layout For Small Terminal
        val displayMetrics = resources.displayMetrics
        val screenHeightPx = displayMetrics.heightPixels
        val screenHeightDp = screenHeightPx / displayMetrics.density
        println("screenHeightDp :: $screenHeightDp")

        if (screenHeightDp < 500) {
            var imgView = view.findViewById<ImageView>(R.id.imageViewStatus)
            val imgViewParams = imgView.getLayoutParams() as ConstraintLayout.LayoutParams
            imgViewParams.topMargin = 10
            imgView.setLayoutParams(imgViewParams)

            imgView.layoutParams.height = Helper.getInstance().getDpValue(60)
            imgView.layoutParams.width = Helper.getInstance().getDpValue(60)

            var bglView = view.findViewById<LinearLayout>(R.id.buttonGroupLeft)
            bglView.setPadding(0,0,0,0)
            val bglParams = bglView.getLayoutParams() as ConstraintLayout.LayoutParams
            bglParams.topMargin = 0
            bglView.setLayoutParams(bglParams)

            val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)

//            val tvProduct = view.findViewById<TextView>(R.id.tvProduct)
//            tvProduct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        //TODO Dynamic Layout For Small Terminal

        var respCode = ""
        var desc = ""
        try {
            respCode = Utility.HexString2ASCII(TransData.respCode)
            val formedEnumTag = "TAG_$respCode"
            desc = "(" + respCode + ")" + CardErrorDataEnum.valueOf(formedEnumTag).data
        } catch (e: Exception) {
            desc = respCode.ifEmpty { "Failed" }
            /*helperLog.appendLine(helperLogClassName, "Card Error Enum not Found")
            helperLog.appendLine(helperLogClassName, "Acquirer Response Code:", respCode)
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            helperLog.logToFile(EnumLogFileName.TerminaLogException)*/
        }

        linearDescription = view.findViewById(R.id.linearDescriptionList)
        textViewProduct = view.findViewById(R.id.tvProduct)
        TransData.denominationProduct?.let {
            var amountDisplay = it.Amount
            if(amountDisplay.contains(".")) {
                val tempList = amountDisplay.split(".")
                amountDisplay = tempList.first()
            }
            textViewProduct.text = "RM$amountDisplay = ${it.Desc}"
        }
        renderListItem("Type", TransData.txnTypeLabel)
        renderListItem("Channel", "Card")
        renderListItem("Date/Time", Utils.DateTimeFormat(transDateAsci))
        renderListItem("Inv No", TransData.invoiceNo)
        renderListItem("Ref No", TransData.rrn)
        renderListItem("Approval Code", TransData.approvalCode)
        renderListItem("Response Code", desc)
        startTimer(0)
    }

    private fun resetTime() {
        if (timeCountDown!=null) {
            timeCountDown!!.cancel()
            timeProgress=0
            timeSelected=0
            //pauseOffSet=0
            timeCountDown=null

            val timeLeftTv = view?.findViewById<TextView>(R.id.buttonGroupLeftTv)
            timeLeftTv?.let{
                timeLeftTv.text = "CANCEL (0)"
            }
        }
    }

    /*private fun timePause() {
        if (timeCountDown!=null) {
            timeCountDown!!.cancel()
        }
    }*/

    private fun startTimer(pauseOffSetL: Long) {
        /*val progressBar = view?.findViewById<ProgressBar>(R.id.pbTimer)
        progressBar?.let {
            progressBar.max = timeSelected
            progressBar.progress = timeProgress
        }*/
        timeCountDown = object :
            CountDownTimer((timeSelected*1000).toLong() - pauseOffSetL*1000, 1000) {
            override fun onTick(p0: Long) {
                timeProgress++
                //pauseOffSet = timeSelected.toLong()- p0/1000
                /*progressBar?.let {
                    progressBar.progress = timeSelected-timeProgress
                }*/
                val timeLeftTv = view?.findViewById<TextView >(R.id.buttonGroupLeftTv)
                timeLeftTv?.let {
                    timeLeftTv.text = "BACK (${timeSelected - timeProgress})"
                }
            }

            override fun onFinish() {
                resetTime()
                listener?.fragmentDenominationBackAction()
            }
        }.start()
    }

    private fun openNfc() {
//        val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
//        val aesData = AisinoHelper.encryptAsAesUrl(environmentManager.get(EnvironmentVariables::nfcAesKey),
//            TransData.mid,
//            TransData.tid,
//            TransData.batchNo,
//            TransData.invoiceNo,
//            TransData.prevRRN.ifEmpty {
//                TransData.rrn
//            },
//            TransData.prevApprovalCode.ifEmpty {
//                TransData.approvalCode
//            },
//            "",
//            true,
//            "NFC",
//            ServiceHolder.getTerminalSerialNumber()
//        )
//
//        println("openNfc :: $aesData")
//        AisinoHelper.openNfcUrlInterface("https://", "${environmentManager.get(EnvironmentVariables::nfcUrl)}$aesData")
    }

    private fun renderListItem(label: String, value: String) {
        val linearListItem = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0,0 ,0 , Helper.getInstance().dpToPx(5))
            }
        }

        val firstView = TextView(context).apply {
            text = label
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTypeface(null, Typeface.BOLD)
        }
        val secondView = TextView(context).apply {
            text = value.ifEmpty { "-" }
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            setTypeface(null, Typeface.BOLD)
        }

        // Add children to parent
        linearListItem.addView(firstView)
        linearListItem.addView(secondView)
        linearDescription.addView(linearListItem)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "FragmentDenominationResult OnDestroyView :: denomination result screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}
