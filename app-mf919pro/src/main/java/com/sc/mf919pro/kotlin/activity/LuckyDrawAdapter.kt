package com.sc.mf919pro.kotlin.activity

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.sc.mf919pro.R
import com.sc.mf919pro.kotlin.data_enum.LuckyDrawItem

class LuckyDrawAdapter(
    private val items: List<LuckyDrawItem>,
    private val onItemClick: (LuckyDrawItem) -> Unit
) : RecyclerView.Adapter<LuckyDrawAdapter.ItemVH>() {

    inner class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val imgGift: ImageView = view.findViewById(R.id.imgGift)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lucky_draw, parent, false)
        return ItemVH(view)
    }

    override fun onBindViewHolder(holder: ItemVH, position: Int) {
        val item = items[position]

        holder.imgGift.setImageResource(item.imageRes)

        holder.imgGift.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = items.size
}
