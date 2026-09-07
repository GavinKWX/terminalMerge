package com.sc.mf919.kotlin.helper_common

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class UiEvent {
    //data class Toast(val message: String) : UiEvent()
    data class MdbStateChange(val stateValue: Boolean): UiEvent()
    data class MdbVendingPrice(val price: String): UiEvent()
    /** VMC aborted the in-flight vend (reset / vend cancel / reader disable) - dismiss payment UI immediately. */
    object MdbVendingForceEnd: UiEvent()
}

object AppBus {
    private val _uiEvents = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvents = _uiEvents.asSharedFlow()

    fun tryEmit(event: UiEvent): Boolean = _uiEvents.tryEmit(event)

    suspend fun emit(event: UiEvent) {
        _uiEvents.emit(event)
    }
}