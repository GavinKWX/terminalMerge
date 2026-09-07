package com.sc.mf919pro.kotlin.activity

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.sc.mf919pro.R

class ProgressDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ProgressDialogFragment"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_MSG = "arg_msg"

        fun newInstance(title: String, message: String) = ProgressDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_MSG, message)
            }
        }
    }

    private var messageView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false   // 🚫 disables BACK button + outside touch
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        val msg = requireArguments().getString(ARG_MSG).orEmpty()

        val view = LayoutInflater.from(ctx).inflate(R.layout.activity_progressdialogcircle, null)
        messageView = view.findViewById<TextView>(R.id.message).apply {
            text = Html.fromHtml("<big>$msg</big>", Html.FROM_HTML_MODE_LEGACY)
        }

        val dialog = AlertDialog.Builder(ctx, R.style.DialogThemeColor)
            .setTitle(Html.fromHtml("<b>$title</b>", Html.FROM_HTML_MODE_LEGACY))
            .setView(view)
            .setCancelable(false)
            .create()

        // Prevent back button dismiss (optional)
        dialog.setCanceledOnTouchOutside(false)

        return dialog
    }

    override fun onDestroyView() {
        messageView = null
        super.onDestroyView()
    }

    fun update(title: String? = null, message: String? = null) {
        // Must be called on the main thread (BaseFragment/ActivityBase wrappers ensure this).
        // Also persist into arguments so a recreated dialog (rotation/process death) shows the latest text.
        // Unchanged values are skipped: transaction flows poll status at ~10Hz, and every
        // set costs an Html.fromHtml allocation plus a full dialog layout pass.
        title?.takeIf { it != arguments?.getString(ARG_TITLE) }?.let {
            arguments?.putString(ARG_TITLE, it)
            dialog?.setTitle(Html.fromHtml("<b>$it</b>", Html.FROM_HTML_MODE_LEGACY))
        }
        message?.takeIf { it != arguments?.getString(ARG_MSG) }?.let {
            arguments?.putString(ARG_MSG, it)
            messageView?.text = Html.fromHtml("<big>$it</big>", Html.FROM_HTML_MODE_LEGACY)
        }
    }
}
