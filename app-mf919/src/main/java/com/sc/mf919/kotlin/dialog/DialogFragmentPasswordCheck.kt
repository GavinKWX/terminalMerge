package com.sc.mf919.kotlin.dialog

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.DialogFragment
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.helper_common.CoroutineTask
import com.sc.mf919.kotlin.helper_common.TmsHelper
import helpers.HelperCommon
import helpers.HelperLog

class DialogFragmentPasswordCheck : DialogFragment(){
    // Interface for callbacks to the parent
    interface DialogFragmentPasswordCheckListener {
        fun onDialogRequestProgress(isRequest: Boolean, title:String, message:String)
        fun onDialogPositiveClick(dialogType: String)
        fun onDialogNegativeClick(dialogType: String)
    }
    var terminalPin: String = ""
    lateinit var mContext: Context
    private var listener: DialogFragmentPasswordCheckListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.actvivty_passwordlock, container, false)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Verify that the host activity implements the callback interface
        mContext = context
        terminalPin = ""
        listener = try {
            context as DialogFragmentPasswordCheckListener
        } catch (e: ClassCastException) {
            throw ClassCastException("$context must implement DialogFragmentPasswordCheckListener")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val dialogType = arguments?.getString(ARG_TYPE) ?: ""
        val edTx = view.findViewById<EditText>(R.id.passwordString)
        edTx.setOnEditorActionListener(
            TextView.OnEditorActionListener { textView, actionId, keyEvent ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    if (edTx.text.length != 6 || edTx.text.isEmpty()) {
                        edTx.setText("")
                        Toast.makeText(mContext, "Invalid PIN", Toast.LENGTH_SHORT).show()
                    } else {
                        terminalPin = edTx.text.toString()
                        VoidSaleTask().execute(terminalPin, dialogType)
                        edTx.setText("")
                    }
                    return@OnEditorActionListener true
                }

                false
            }
        )

        val position = view.findViewById<TextView>(R.id.positionBtn)
        position.setOnClickListener {
            if (edTx.text.length != 6 || edTx.text.isEmpty()) {
                edTx.setText("")
                Toast.makeText(mContext, "Invalid PIN", Toast.LENGTH_SHORT).show()
            } else {
                terminalPin = edTx.text.toString()
                VoidSaleTask().execute(terminalPin, dialogType)
                edTx.setText("")
            }
        }

        val negative = view.findViewById<TextView>(R.id.negativeBtn)
        negative.setOnClickListener {
            edTx.setText("")
            dismiss()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), R.style.DialogThemeColor).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }
    }

    private inner class VoidSaleTask : CoroutineTask<String?, Boolean>() {
        override fun onPreExecute() {
            listener?.onDialogRequestProgress(true, "Verifying PIN", "Loading...")
            super.onPreExecute()
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        override fun doInBackground(vararg params: String?): Boolean? {
            try {
                return params[0]?.let {
                    params[1]?.let { it1 ->
                        val log = HelperLog(
                            HelperCommon.getSession(),
                            TmsHelper.checkIsConnectedWifi(mContext),
                            Utils.getIPAddress(),
                            "Setting Download Configuration",
                            "Dialog Fragment Password Checker",
                            "Dialog Fragment Password Checker"
                        )

                        TmsHelper.checkTerminalPIN(log, mContext, it, it1)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }

        override fun onPostExecute(result: Boolean?) {
            super.onPostExecute(result)
            listener?.onDialogRequestProgress(false, "", "")
            if (result == true) {
                dismiss()
                val dialogType = arguments?.getString(ARG_TYPE) ?: ""
                listener?.onDialogPositiveClick(dialogType)
            } else {
                Toast.makeText(mContext, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val ARG_TYPE = ""

        fun newInstance(dialogType: String): DialogFragmentPasswordCheck {
            val fragment = DialogFragmentPasswordCheck()
            val args = Bundle().apply {
                putString(ARG_TYPE, dialogType)
            }
            fragment.arguments = args
            return fragment
        }
    }
}