package com.sc.mf919pro.kotlin.data_enum

import android.os.Parcelable
import com.sc.mf919pro.R
import kotlinx.parcelize.Parcelize

@Parcelize
data class LuckyDrawItem(
    val id: Int,
    val reward: String,
    val imageRes: Int = R.drawable.gift_box_closed
) : Parcelable