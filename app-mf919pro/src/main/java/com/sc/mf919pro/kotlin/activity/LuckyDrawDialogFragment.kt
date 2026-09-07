package com.sc.mf919pro.kotlin.activity

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sc.mf919pro.R
import com.sc.mf919pro.kotlin.data_enum.LuckyDrawItem
import com.sc.mf919pro.kotlin.fragment.BaseDialogFragment
import java.util.ArrayList

class LuckyDrawDialogFragment: BaseDialogFragment() {
    private var items: List<LuckyDrawItem> = listOf()
    var listener: Listener? = null

    interface Listener {
        fun onPrizeSelected(prize: String)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // items = arguments?.getParcelableArrayList("items", LuckyDrawItem::class.java) ?: listOf()
        items = arguments?.getParcelableArrayList<LuckyDrawItem>("prizeList") ?: emptyList()
        isCancelable = false   // ❗ disables BACK button cancel
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setCanceledOnTouchOutside(false) // ❗ disables outside touch
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.dialog_lucky_draw, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.rvLuckyItems)
        rv.layoutManager = GridLayoutManager(requireContext(), 2)
        rv.adapter = LuckyDrawAdapter(items) { selectedItem ->
            dismiss()
            Handler(Looper.getMainLooper()).post {
                listener?.onPrizeSelected(selectedItem.reward)
            }
        }

        // Add spacing between items
        rv.addItemDecoration(GridSpacingItemDecoration(10, 35))
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val params = attributes

            // Screen size
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            // Set custom size (90% width, 70% height)
            params.width = (screenWidth * 0.90f).toInt()
            params.height = (screenHeight * 0.50f).toInt()
            attributes = params

            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    companion object {
        fun newInstance(prizeList: List<LuckyDrawItem>) =
            LuckyDrawDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList("prizeList", ArrayList(prizeList))
                }
        }
    }
}
