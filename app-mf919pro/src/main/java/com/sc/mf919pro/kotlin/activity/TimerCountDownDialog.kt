package com.sc.mf919pro.kotlin.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.DialogTimerCountDownBinding
import com.sc.mf919pro.kotlin.helper_common.MfHelper

class TimerCountDownDialog : DialogFragment() {
    private var timeMills: Long = 0
    private var infoText: String? = null
    private var timer: CountDownTimer? = null
    private var binding: DialogTimerCountDownBinding? = null
    private var btnText: String = "Cancel"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.DialogStyle)
    }

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogTimerCountDownBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.tvInfo?.text = infoText
        timer = object : CountDownTimer(timeMills, 1000) {
            @SuppressLint("SetTextI18n")
            override fun onTick(millisUntilFinished: Long) {
                try {
                    binding?.tvStop?.text = Html.fromHtml("<font color=\"#000000\">$btnText</font> <font color=\"#FF0071\">(${millisUntilFinished / 1000})</font>", Html.FROM_HTML_MODE_LEGACY)
                    binding?.tvStop?.invalidate()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    cancelTimer()
                    MfHelper.closeNfcUrlInterface()
                }

            }

            override fun onFinish() {
                iOnTimerFinishes!!.onTimerFinishes()
            }
        }

        binding?.tvStop?.setOnClickListener {
            iOnButtonClickListener!!.onCancelCLick()
        }
        timer!!.start()
    }

    fun cancelTimer() {
        timer!!.cancel()
        timer = null
    }

    fun setTimeout(timeMills: Long): TimerCountDownDialog {
        this.timeMills = timeMills
        return this
    }

    fun setInfoText(infoText: String?): TimerCountDownDialog {
        this.infoText = infoText
        return this
    }

    fun setBtnText(btnText: String): TimerCountDownDialog{
        this.btnText = btnText
        return this
    }

    interface IOnButtonClickListener {
        fun onCancelCLick()
    }

    private var iOnButtonClickListener: IOnButtonClickListener? = null
    fun setOnButtonClickListener(iOnButtonClickListener: IOnButtonClickListener): TimerCountDownDialog {
        this.iOnButtonClickListener = iOnButtonClickListener
        return this
    }

    interface IOnTimerFinishes {
        fun onTimerFinishes()
    }

    private var iOnTimerFinishes: IOnTimerFinishes? = null
    fun setOnTimerFinishes(iOnTimerFinishes: IOnTimerFinishes): TimerCountDownDialog {
        this.iOnTimerFinishes = iOnTimerFinishes
        return this
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}