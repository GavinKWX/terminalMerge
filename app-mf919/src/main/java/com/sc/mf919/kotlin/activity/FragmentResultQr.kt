package com.sc.mf919.kotlin.activity

import android.content.Context
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

class FragmentResultQr: Fragment() {
    private var listener: OnFragmentInteractionListener? = null
    private var timeSelected : Int = ServiceHolder.ackCountDownSecond
    private var timeCountDown: CountDownTimer? = null
    private var timeProgress = 0

    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    /**
     * The countdown timer and the cancel button both outlive onViewCreated only in one direction,
     * but onFinish can still fire while the view is being torn down, so guard rather than assume
     * helperLog is up -- a lateinit access here would crash a payment terminal on the result
     * screen. Same shape as EmvActivity.logEmv.
     */
    private fun logResultQr(msg: String) {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, msg)
        } else {
            Utils.debugLogPrint("FragmentResultQr", msg)
        }
    }

    interface OnFragmentInteractionListener {
        fun fragmentResultQrBackAction()
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_result_qr, container, false)
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
            "QR Payment Result Screen"
        )

        val desc: String = try {
            "(" + TransData.qrRespCode + ")" + TransData.qrRespDesc
        } catch (e: Exception) {
            TransData.qrRespCode.ifEmpty { "Failed" }
        }

        view.findViewById<TextView>(R.id.type_tr).text = TransData.txnTypeLabel
        view.findViewById<TextView>(R.id.amount_tr).text = "RM ${Utils.getActualAmount(TransData.amount.toString())}"
        view.findViewById<TextView>(R.id.hostRef_tr).text = TransData.qrHostRef
        view.findViewById<TextView>(R.id.refId_tr).text = isStringEmpty(TransData.qrRef)
        view.findViewById<TextView>(R.id.dateNtime_tr).text = Utils.DateTimeFormat(TransData.transDateAsci)
        view.findViewById<TextView>(R.id.respCode_tr).text = desc

        // The QR result is known by the time this screen renders -- record it here so an
        // approved/declined outcome is on disk even if the result screen is the last thing the
        // terminal does before a kill.
        logResultQr("QR payment result shown :: type ${TransData.txnTypeLabel}, " +
            "refId ${isStringEmpty(TransData.qrRef)}, brand ${isStringEmpty(TransData.qrPayBrand)}, " +
            "amount ${Utils.getActualAmount(TransData.amount.toString())}, " +
            "hostRef ${isStringEmpty(TransData.qrHostRef)}, result $desc")
        helperLog.logToFile(EnumLogFileName.TerminaLog)

        view.findViewById<LinearLayout>(R.id.cancelBtn).setOnClickListener {
            logResultQr("User Cancel :: dismissed QR result screen (refId ${isStringEmpty(TransData.qrRef)})")
            if (this::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
            listener?.fragmentResultQrBackAction()
        }
        startTimer(0)
    }

    private fun resetTime() {
        if (timeCountDown!=null) {
            timeCountDown!!.cancel()
            timeProgress=0
            timeSelected=0
            //pauseOffSet=0
            timeCountDown=null

            val timeLeftTv = view?.findViewById<TextView>(R.id.cancelTextView)
            timeLeftTv?.let{
                timeLeftTv.text = "CANCEL (0)"
            }
        }
    }

    private fun startTimer(pauseOffSetL: Long) {
        timeCountDown = object :CountDownTimer((timeSelected*1000).toLong() - pauseOffSetL*1000, 1000) {
            override fun onTick(p0: Long) {
                timeProgress++
                val timeLeftTv = view?.findViewById<TextView>(R.id.cancelTextView)
                timeLeftTv?.let{
                    timeLeftTv.text = "CANCEL (${timeSelected - timeProgress})"
                }
            }

            override fun onFinish() {
                logResultQr("QR result screen auto-dismissed :: acknowledge countdown elapsed")
                if (this@FragmentResultQr::helperLog.isInitialized) helperLog.logToFile(EnumLogFileName.TerminaLog)
                resetTime()
                listener?.fragmentResultQrBackAction()
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "FragmentResultQr OnDestroyView :: QR result screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}