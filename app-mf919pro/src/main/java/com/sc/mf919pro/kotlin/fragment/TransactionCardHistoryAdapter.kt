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
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import java.util.Locale

class TransactionCardHistoryAdapter(
    private val onClickNfc: (DbModelPrintReceipt) -> Unit,
    private val onClickVoid: (DbModelPrintReceipt) -> Unit,
    private val onClickPrint: (DbModelPrintReceipt) -> Unit,
    private val onClickDetail: (DbModelPrintReceipt) -> Unit,

    ) : RecyclerView.Adapter<TransactionCardHistoryAdapter.ItemVH>() {

    private val items = mutableListOf<DbModelPrintReceipt>()
    private var expandedKey: String? = null

    init {
        setHasStableIds(true)
    }

    fun replaceItems(newItems: List<DbModelPrintReceipt>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun appendItems(newItems: List<DbModelPrintReceipt>) {
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

    override fun getItemId(position: Int): Long = items[position].id.toLong()

    override fun onBindViewHolder(holder: ItemVH, position: Int) {
        holder.bind(items[position])
    }

    inner class ItemVH(
        private val binding: ItemTransactionHistoryExpandableBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DbModelPrintReceipt) {
            val key = itemKey(item)
            val isExpanded = expandedKey == key
            val scheme = extractScheme(item.receiptInfo)
            val txnStatus = item.txnType.trim().uppercase(Locale.ROOT)

            binding.tvPrimaryLeft.text = item.invoiceNo
            binding.tvPrimaryRight.text = "RM${Utils.getActualAmount(item.txnAmt)}"
            binding.tvSecondaryLeft.text = Utils.DateTimeFormat(item.txnDt)
            binding.tvSecondaryRight.text = scheme
            setAmountStyle(binding.tvPrimaryRight, item.respCode, txnStatus)

            binding.expandableContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.tvExpandIndicator.apply {
                text =  if (isExpanded) "^" else "v"
                visibility = if (item.respCode == "00") View.VISIBLE else View.GONE
            }

            binding.summaryContainer.setOnClickListener {
                if(item.respCode != "00") return@setOnClickListener
                val currentPosition = adapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener

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
        }
    }

    private fun itemKey(item: DbModelPrintReceipt): String = "${item.id}_${item.invoiceNo}_${item.stan}"

    private fun shouldShowVoidAction(item: DbModelPrintReceipt): Boolean {
        val txnType = item.txnType.trim()
        return item.respCode == "00" && txnType.equals("Sale", ignoreCase = true)
    }

    private fun extractScheme(receiptInfo: String): String {
        return try {
            val receiptInfos = receiptInfo.replace("[", "").replace("]", "").split(", ")
            receiptInfos.getOrNull(4) ?: "-"
        } catch (_: Exception) {
            "-"
        }
    }

    private fun setAmountStyle(view: android.widget.TextView, respCode: String, txnStatus: String) {
        view.paintFlags = view.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        when (txnStatus) {
            "SALE", "PRE AUTHORIZATION", "SALE COMPLETION", "CASH OUT", "INSTALMENT SALE", "MOTO" -> {
                val color = if (respCode == "00") R.color.colorGreen else R.color.colorRed
                view.setTextColor(ContextCompat.getColor(view.context, color))
            }

            "VOID", "PREAUTH CANCEL", "VOID SALE COMPLETION", "CASHOUT VOID", "VOID INSTALMENT" -> {
                view.setTextColor(ContextCompat.getColor(view.context, R.color.colorGreyD))
                view.paintFlags = view.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }

            else -> view.setTextColor(ContextCompat.getColor(view.context, R.color.colorRed))
        }
        view.setTypeface(null, Typeface.BOLD)
    }
}