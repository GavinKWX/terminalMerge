package com.sc.mf919pro.kotlin.helper_common

import android.os.Bundle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

sealed class UiEvent {
    //data class Toast(val message: String) : UiEvent()
    data class FragmentNavigation(val actionId: Int, val bundle: Bundle): UiEvent()
    data class EndPaymentSession(val endSession: Boolean): UiEvent()
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

    // With replay=0, an emit while no collector is active (e.g. screen off, activity
    // stopped) is silently dropped. Wait for a subscriber to appear (screen waking up,
    // activity restarting) before emitting, so the event is not lost.
    suspend fun emitWhenSubscribed(event: UiEvent, timeoutMs: Long = 5000L) {
        withTimeoutOrNull(timeoutMs) {
            _uiEvents.subscriptionCount.first { it > 0 }
        }
        _uiEvents.emit(event)
    }
}