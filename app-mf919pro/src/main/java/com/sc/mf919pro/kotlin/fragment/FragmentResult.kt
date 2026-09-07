package com.sc.mf919pro.kotlin.fragment

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import com.library.terminal.Utility
import com.sc.mf919pro.databinding.FragmentResultBinding
import com.sc.mf919pro.java.activity.Utils
import data_enum.CardErrorDataEnum
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder

class FragmentResult : BaseFragment() {
    private var listener: OnFragmentInteractionListener? = null

    private var timeSelected : Int = ServiceHolder.ackCountDownSecond
    private var timeCountDown: CountDownTimer? = null
    private var timeProgress = 0

    private var _binding: FragmentResultBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentResultBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        timeCountDown?.cancel()
        timeCountDown = null
    }

    interface OnFragmentInteractionListener {
        fun fragmentResultBackAction()
    }
    override fun onAttach(context: Context) {
        super.onAttach(context)
        val parent = parentFragment
        if (parent is OnFragmentInteractionListener) {
            listener = parent
        } else {
            throw RuntimeException("$parent must implement OnFragmentInteractionListener")
        }
    }
    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun isStringEmpty(receiveString: String): String{
        if(receiveString.isEmpty()){
            return "-"
        }
        return receiveString
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val linearLayout = binding.buttonGroupLeft
        linearLayout.background.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(Color.GRAY, BlendModeCompat.SRC_ATOP)

        var respCode = ""
        var desc = ""
        try {
            respCode = Utility.HexString2ASCII(transData.respCode)
            val formedEnumTag = "TAG_$respCode"
            desc = "(" + respCode + ")" + CardErrorDataEnum.valueOf(formedEnumTag).data
        } catch (e: Exception) {
            desc = respCode.ifEmpty { "Failed" }
            //TODO Pass in Log interface
            /*log.appendLine(helperlogClassName, "Card Error Enum not Found")
            log.appendLine(helperlogClassName, "Acquirer Response Code:", respCode)
            log.logToFile(EnumLogFileName.TerminaLogException)*/
        }

        binding.textViewType.text = transData.txnTypeLabel
        binding.textViewCardNo.text = transData.maskedPan
        binding.textViewDatetime.text = Utils.DateTimeFormat(transData.transDateAsci)
        binding.textViewAmount.text = "RM ${Utils.getActualAmount(transData.amount.toString())}"
        //view.findViewById<TextView>(R.id.textViewInvoice).text = isStringEmpty(TransData.invoiceNo)
        binding.textViewTrace.text = isStringEmpty(transData.stan)
        binding.textViewRefNo.text = isStringEmpty(transData.rrn)
        //view.findViewById<TextView>(R.id.textViewApprovalCode).text = isStringEmpty(TransData.approvalCode)
        binding.textViewMethod.text = isStringEmpty(transData.entryModeLabel)
        binding.textViewDescription.text = desc


        // Custom back press handling
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    listener?.fragmentResultBackAction()
                }
            })

        binding.buttonGroupLeft.setOnClickListener {
            listener?.fragmentResultBackAction()
        }
        startTimer(0)
    }

    private fun resetTime() {
        if (timeCountDown!=null) {
            timeCountDown!!.cancel()
            timeProgress=0
            timeSelected=0
            timeCountDown=null

            val timeLeftTv = binding.buttonGroupLeftTv
            timeLeftTv.let{
                timeLeftTv.text = "CANCEL (0)"
            }
        }
    }

    private fun startTimer(pauseOffSetL: Long) {
        timeCountDown = object :CountDownTimer((timeSelected*1000).toLong() - pauseOffSetL*1000, 1000) {
            override fun onTick(p0: Long) {
                timeProgress++
                val timeLeftTv = binding.buttonGroupLeftTv
                timeLeftTv.let{
                    timeLeftTv.text = "CANCEL (${timeSelected - timeProgress})"
                }
            }

            override fun onFinish() {
                resetTime()
                listener?.fragmentResultBackAction()
            }
        }.start()
    }
}