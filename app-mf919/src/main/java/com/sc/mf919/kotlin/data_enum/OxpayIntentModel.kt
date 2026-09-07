package com.sc.mf919.kotlin.data_enum

import android.content.Context

data class OxpayIntentModel (
    val name: String,
    val packageName: String,
    val isInstalled: Boolean
) {
    companion object {
        fun isAppInstalled(packageName: String, context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}