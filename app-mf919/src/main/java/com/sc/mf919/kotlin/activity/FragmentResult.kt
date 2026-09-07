package com.sc.mf919.kotlin.activity

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.library.terminal.Utility
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import data_enum.CardErrorDataEnum
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog

class FragmentResult: Fragment() {
    private var listener: OnFragmentInteractionListener? = null
    private var timeSelected : Int = ServiceHolder.ackCountDownSecond
    private var timeCountDown: CountDownTimer? = null
    private var timeProgress = 0
    //private var pauseOffSet: Long = 0
    //private var isStart = true


    interface OnFragmentInteractionListener {
        fun fragmentResultBackAction()
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
    private fun isStringEmpty(receiveString: String): String{
        if(receiveString.isEmpty()){
            return "-"
        }
        return receiveString
    }

    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    /**
     * The auto-dismiss countdown can fire while the view is being torn down, so guard rather than
     * assume helperLog is up -- a lateinit access on the card result screen would crash the
     * terminal at the worst possible moment. Same shape as EmvActivity.logEmv.
     */
    private fun logResult(msg: String) {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, msg)
        } else {
            Utils.debugLogPrint("FragmentResult", msg)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_result, container, false)
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
            "Card Payment Result Screen"
        )

        var respCode = ""
        var desc = ""
        try {
            respCode = Utility.HexString2ASCII(TransData.respCode)
            val formedEnumTag = "TAG_$respCode"
            desc = "(" + respCode + ")" + CardErrorDataEnum.valueOf(formedEnumTag).data
        } catch (e: Exception) {
            desc = respCode.ifEmpty { "Failed" }
        }

        view.findViewById<TextView>(R.id.type_tr).text = TransData.txnTypeLabel
        view.findViewById<TextView>(R.id.amount_tr).text = "RM ${Utils.getActualAmount(TransData.amount.toString())}"
        view.findViewById<TextView>(R.id.cardNo_tr).text = TransData.maskedPan
        view.findViewById<TextView>(R.id.authCode_tr).text = isStringEmpty(TransData.approvalCode)
        view.findViewById<TextView>(R.id.refNo_tr).text = isStringEmpty(TransData.rrn)
        view.findViewById<TextView>(R.id.traceNo_tr).text = isStringEmpty(TransData.stan)
        view.findViewById<TextView>(R.id.dateNtime_tr).text = Utils.DateTimeFormat(TransData.transDateAsci)
        view.findViewById<TextView>(R.id.respCode_tr).text = desc
        logResult("Result screen shown :: ${TransData.txnTypeLabel} resp=$desc " +
            "stan=${TransData.stan} rrn=${TransData.rrn} pan=${TransData.maskedPan}")


        view.findViewById<LinearLayout>(R.id.cancelBtn).setOnClickListener {
            logResult("Selected :: Done [CARD RESULT]")
            if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
            listener?.fragmentResultBackAction()
        }
        //view.findViewById<LinearLayout>(R.id.buttonGroupRight).setOnClickListener {}
        startTimer(0)
    }

    private fun resetTime() {
        if (timeCountDown!=null) {
            timeCountDown!!.cancel()
            timeProgress=0
            timeSelected=0
            //pauseOffSet=0
            timeCountDown=null

            val timeLeftTv = view?.findViewById<TextView>(R.id.cancelBtnTextview)
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
        timeCountDown = object :CountDownTimer((timeSelected*1000).toLong() - pauseOffSetL*1000, 1000) {
            override fun onTick(p0: Long) {
                timeProgress++
                //pauseOffSet = timeSelected.toLong()- p0/1000
                /*progressBar?.let {
                    progressBar.progress = timeSelected-timeProgress
                }*/
                val timeLeftTv = view?.findViewById<TextView>(R.id.cancelBtnTextview)
                timeLeftTv?.let{
                    timeLeftTv.text = "CANCEL (${timeSelected - timeProgress})"
                }
            }

            override fun onFinish() {
                resetTime()
                listener?.fragmentResultBackAction()
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "FragmentResult OnDestroyView :: card result screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}
