package com.sc.mf919pro.kotlin.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.StyleRes
import androidx.fragment.app.DialogFragment
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Keypad
import com.sc.mf919pro.java.activity.PinPadListener
import com.sc.mf919pro.java.activity.onKeypadEventListener
import com.sc.mf919pro.kotlin.helper_common.utils.PinBlockUtil

class PinPadDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_CARD_NO = "arg_card_no"
        private const val ARG_ISO_MODE = "arg_iso_mode"

        fun newInstance(cardNo: String, isoType: PinBlockUtil.ISO): PinPadDialogFragment {
            return PinPadDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CARD_NO, cardNo)
                    putInt(ARG_ISO_MODE, isoType.ordinal)
                }
            }
        }
    }

    private var pinPadListener: PinPadListener? = null

    private var cardNumber: String = "0000000000000000"
    private var isoType: PinBlockUtil.ISO = PinBlockUtil.ISO.ISO_0

    private var pinString: LinearLayout? = null
    private var keypad: Keypad? = null

    // Use your fullscreen style here
    @StyleRes
    private val dialogStyle: Int = R.style.myFullscreenAlertDialogStyle

    fun setPinPadListener(listener: PinPadListener): PinPadDialogFragment {
        pinPadListener = listener
        return this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setStyle(STYLE_NO_TITLE, dialogStyle)

        arguments?.let { b ->
            b.getString(ARG_CARD_NO)?.let { cardNumber = it }
            val mode = b.getInt(ARG_ISO_MODE, 0)
            if (mode in PinBlockUtil.ISO.values().indices) isoType = PinBlockUtil.ISO.values()[mode]
        }

        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), dialogStyle).apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)

            // Block BACK
            setOnKeyListener { _, keyCode, _ ->
                keyCode == KeyEvent.KEYCODE_BACK
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.activity_pinpad, container, false)

        keypad = v.findViewById(R.id.keypad_ll)
        pinString = v.findViewById(R.id.pinMsg)

        keypad?.registerOnOKEventListener(keypadListener)
        keypad?.hideDotForPinPad()
        return v
    }

    override fun onStart() {
        super.onStart()

        // Force fullscreen (Android 13 safe)
        dialog?.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.decorView.setPadding(0, 0, 0, 0)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        keypad = null
        pinString = null
    }

    // ---------------- Keypad callbacks ----------------

    private val keypadListener = object : onKeypadEventListener {
        override fun onOK(isOK: Boolean, msg: String?) {
            val listener = pinPadListener
            if (listener == null) {
                dismissAllowingStateLoss()
                return
            }

            if (isOK) {
                if (msg != null) {
                    if (msg.isEmpty()) {
                        listener.onReadPinSuccess("")
                    } else {
                        val pinBlock = PinBlockUtil.pinBlockEncode(msg, cardNumber, isoType).uppercase()
                        listener.onReadPinSuccess(pinBlock)
                    }
                } else {
                    listener.onReadPinCancel()
                }
                dismissAllowingStateLoss()
            } else {
                val typed = msg ?: ""
                listener.onReadingPin(typed.length, PinBlockUtil.paddingWith("", "*", typed.length, end = false))

                pinString?.let { container ->
                    container.removeAllViews()
                    repeat(typed.length) {
                        val child = LayoutInflater.from(requireContext())
                            .inflate(R.layout.pinbox, container, false)
                        container.addView(child)
                    }
                }
            }
        }
    }
}