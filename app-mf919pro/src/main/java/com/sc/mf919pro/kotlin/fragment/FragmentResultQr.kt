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
import com.sc.mf919pro.databinding.FragmentResultQrBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder

class FragmentResultQr : BaseFragment() {
    private var listener: OnFragmentInteractionListener? = null

    private var timeSelected : Int = ServiceHolder.ackCountDownSecond
    private var timeCountDown: CountDownTimer? = null
    private var timeProgress = 0

    private var _binding: FragmentResultQrBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentResultQrBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        timeCountDown?.cancel()
        timeCountDown = null
    }

    interface OnFragmentInteractionListener {
        fun fragmentResultQrBackAction()
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

        val desc: String = try {
            "(" + transData.qrRespCode + ")" + transData.qrRespDesc
        } catch (e: Exception) {
            transData.qrRespCode.ifEmpty { "Failed" }
        }

        binding.textViewType.text = transData.txnTypeLabel
        binding.textViewDatetime.text = Utils.DateTimeFormat(transData.transDateAsci)
        binding.textViewAmount.text = "RM ${Utils.getActualAmount(transData.amount.toString())}"
        binding.textViewQrRef.text = isStringEmpty(transData.qrRef)
        binding.textViewHostRef.text = isStringEmpty(transData.qrHostRef)
        //binding.textViewRespCode.text = isStringEmpty(transData.qrRespCode)
        binding.textViewDescription.text = desc

        // Custom back press handling
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    listener?.fragmentResultQrBackAction()
                }
            })

        binding.buttonGroupLeft.setOnClickListener {
            println("Result On Click Listener")
            listener?.fragmentResultQrBackAction()
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
                listener?.fragmentResultQrBackAction()
            }
        }.start()
    }
}