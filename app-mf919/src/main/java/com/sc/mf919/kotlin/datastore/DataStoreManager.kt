package com.sc.mf919.kotlin.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

class DataStoreManager(private val context: Context) {

    private val ds = context.appDataStore

    suspend fun putBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        ds.edit { it[key] = value }
    }

    suspend fun getBoolean(key: Preferences.Key<Boolean>): Boolean {
        return ds.data.first()[key] ?: false
    }
}