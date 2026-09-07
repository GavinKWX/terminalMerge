package com.sc.mf919pro.kotlin.activity

import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import com.sc.mf919pro.R
import com.sc.mf919pro.kotlin.fragment.BaseDialogFragment

class GiftBoxResultDialogFragment: BaseDialogFragment() {
    private var prize: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prize = arguments?.getString("prize") ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_gift_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val imgGiftBox = view.findViewById<ImageView>(R.id.imgGiftBox)
        val txtReward = view.findViewById<TextView>(R.id.txtReward)
        txtReward.text = prize

        // Animation
        imgGiftBox.scaleX = 0.3f
        imgGiftBox.scaleY = 0.3f

        imgGiftBox.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .withEndAction {
                imgGiftBox.setImageResource(R.drawable.gift_box_open)

                txtReward.alpha = 0f
                txtReward.visibility = View.VISIBLE
                txtReward.animate()
                    .alpha(1f)
                    .setDuration(400)
                    .start()
            }.start()

        // Close when user taps anywhere
        view.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    companion object {
        fun newInstance(prize: String) = GiftBoxResultDialogFragment().apply {
            arguments = Bundle().apply { putString("prize", prize) }
        }
    }
}