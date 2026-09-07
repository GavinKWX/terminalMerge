package com.sc.mf919pro.kotlin.helper_common

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.util.TypedValue

class Helper {
    fun Initialize(ctxt: Context?) {
        mContext = ctxt
        mMyPreferences = mContext?.getSharedPreferences(mContext?.packageName, Context.MODE_PRIVATE)
    }

    fun getPrefs(): SharedPreferences? {
        return mMyPreferences
    }

    fun getPrefs(ctxt: Context): SharedPreferences {
        mMyPreferences ?: run {
            mContext = ctxt
            mMyPreferences = ctxt.getSharedPreferences(ctxt.packageName, Context.MODE_PRIVATE)
        }
        return mMyPreferences!!
    }

    /*fun getDpValue(v: Int): Int {
        val dp1 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            1f,
            mContext?.resources?.displayMetrics
        ).toInt()

        return dp1 * v
    }*/
    fun getDpValue(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            mContext!!.resources.displayMetrics
        ).toInt()
    }

    fun dpToPx(v:Int): Int {
        return (v * Resources.getSystem().displayMetrics.density).toInt()
    }

    fun getNumberSuffix(n: Int): String? {
        return if (n in 11..13) {
            "th"
        } else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private lateinit var mInstance: Helper

        @SuppressLint("StaticFieldLeak")
        private var mContext: Context? = null

        private var mMyPreferences: SharedPreferences? = null

        fun getInstance(): Helper {
            if (!this::mInstance.isInitialized) mInstance = Helper()
            return mInstance
        }
    }
}