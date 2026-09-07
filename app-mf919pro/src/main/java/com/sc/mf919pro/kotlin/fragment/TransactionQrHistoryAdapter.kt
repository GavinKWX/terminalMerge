package com.sc.mf919pro.kotlin.fragment

import android.graphics.Paint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.ItemTransactionHistoryExpandableBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919pro.kotlin.database.model.DbModelTransactionQrGet
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener

class TransactionQrHistoryAdapter(
    private val onClickNfc: (DbModelTransactionQrGet) -> Unit,
    private val onClickVoid: (DbModelTransactionQrGet) -> Unit,
    private val onClickPrint: (DbModelTransactionQrGet) -> Unit,
    private val onClickDetail: (DbModelTransactionQrGet) -> Unit,
    private val onReEnquiry: (DbModelTransactionQrGet) -> Unit,
) : RecyclerView.Adapter<TransactionQrHistoryAdapter.ItemVH>() {
    private val items = mutableListOf<DbModelTransactionQrGet>()
    private var expandedKey: String? = null

    init {
        setHasStableIds(true)
    }

    fun replaceItems(newItems: List<DbModelTransactionQrGet>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun appendItems(newItems: List<DbModelTransactionQrGet>) {
        if (newItems.isEmpty()) return
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    fun clearItems() {
        items.clear()
        expandedKey = null
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemVH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemTransactionHistoryExpandableBinding.inflate(inflater, parent, false)
        return ItemVH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = (items[position].id ?: position).toLong()

    override fun onBindViewHolder(holder: ItemVH, position: Int) {
        holder.bind(items[position])
    }

    inner class ItemVH(
        private val binding: ItemTransactionHistoryExpandableBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DbModelTransactionQrGet) {
            val key = itemKey(item)
            val isExpanded = expandedKey == key

            val refId = item.refId ?: "-"
            val txnType = item.txnType ?: "-"
            val txnDateTime = if (txnType.equals("Void", true)) item.voidDateTime else item.txnDateTime
            val scheme = item.productName ?: "-"
            val respCode = item.respCode ?: ""

            binding.tvPrimaryLeft.text = refId
            binding.tvPrimaryRight.text = "RM${Utils.getActualAmount(item.txnAmount ?: "0")}"
            binding.tvSecondaryLeft.text = txnDateTime ?: "-"
            binding.tvSecondaryRight.text = scheme
            setAmountStyle(binding.tvPrimaryRight, txnType, respCode)
            binding.expandableContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE

            binding.tvExpandIndicator.apply {
                text =  if (isExpanded) "^" else "v"
                visibility = if (item.respCode == "0000") View.VISIBLE else View.GONE
            }

            binding.footerGroup1.setDebouncedOnClickListener {
                onClickNfc(item)
            }
            binding.footerGroup2.apply {
                visibility = if (shouldShowVoidAction(item)) View.VISIBLE else View.GONE
                setDebouncedOnClickListener {
                    onClickVoid(item)
                }
            }
            binding.footerGroup3.setDebouncedOnClickListener {
                onClickPrint(item)
            }
            binding.footerGroup4.setDebouncedOnClickListener {
                onClickDetail(item)
            }

            binding.summaryContainer.setOnClickListener {
                val currentPosition = adapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener

                if(item.respCode != "0000") {
                    onReEnquiry(item)
                    return@setOnClickListener
                }

                val previousExpandedKey = expandedKey
                expandedKey = if (expandedKey == key) null else key

                previousExpandedKey
                    ?.takeIf { it != expandedKey }
                    ?.let { previousKey ->
                        val previousIndex = items.indexOfFirst { itemKey(it) == previousKey }
                        if (previousIndex != -1) {
                            notifyItemChanged(previousIndex)
                        }
                    }

                notifyItemChanged(currentPosition)
            }
        }
    }

    private fun itemKey(item: DbModelTransactionQrGet): String {
        return "${item.id ?: 0}_${item.refId ?: ""}"
    }

    private fun shouldShowVoidAction(item: DbModelTransactionQrGet): Boolean {
        val txnType = item.txnType?.trim()
        return item.respCode == "0000" && txnType.equals("Sale", ignoreCase = true)
    }

    private fun setAmountStyle(view: android.widget.TextView, txnType: String, respCode: String) {
        view.paintFlags = view.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        when {
            txnType.equals("Sale", true) -> {
                val color = if (respCode == "0000") R.color.colorGreen else R.color.colorRed
                view.setTextColor(ContextCompat.getColor(view.context, color))
            }

            txnType.equals("Void", true) -> {
                view.setTextColor(ContextCompat.getColor(view.context, R.color.colorGreyD))
                view.paintFlags = view.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }

            else -> view.setTextColor(ContextCompat.getColor(view.context, R.color.colorRed))
        }
        view.setTypeface(null, Typeface.BOLD)
    }
}