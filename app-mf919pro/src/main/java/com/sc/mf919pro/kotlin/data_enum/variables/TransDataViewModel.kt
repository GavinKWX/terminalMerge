package com.sc.mf919pro.kotlin.data_enum.variables


import androidx.lifecycle.ViewModel

class TransDataViewModel : ViewModel() {
    val data = TransData

    override fun onCleared() {
        super.onCleared()
        // Optional: Reset when ViewModel is destroyed
        data.reset()
    }
}