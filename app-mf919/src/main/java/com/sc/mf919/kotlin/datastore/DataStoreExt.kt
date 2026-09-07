package com.sc.mf919.kotlin.datastore

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.appDataStore by preferencesDataStore(
	name = "app_state"
)