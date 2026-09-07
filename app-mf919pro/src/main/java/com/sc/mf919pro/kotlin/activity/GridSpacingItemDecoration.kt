package com.sc.mf919pro.kotlin.activity

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class GridSpacingItemDecoration(
    private val spacingHorizontal: Int,   // spacing BETWEEN items
    private val spacingVertical: Int,     // spacing BETWEEN rows
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        val itemCount = state.itemCount

        val column = position % 2        // 0 = left, 1 = right

        // -----------------------------
        // Horizontal spacing
        // -----------------------------
        if (column == 0) {
            // LEFT COLUMN → no left spacing
            outRect.left = 0
            outRect.right = spacingHorizontal
        } else {
            // RIGHT COLUMN → no right spacing
            outRect.left = spacingHorizontal
            outRect.right = 0
        }

        // -----------------------------
        // Vertical spacing
        // -----------------------------
        val totalRows = (itemCount + 1) / 2
        val thisRow = (position / 2) + 1

        // Add spacing above
        outRect.top = spacingVertical

        // Add spacing below ONLY if NOT last row
        outRect.bottom = if (thisRow < totalRows) spacingVertical else 0
    }
}