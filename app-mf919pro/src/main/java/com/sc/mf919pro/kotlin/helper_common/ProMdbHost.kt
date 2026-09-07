package com.sc.mf919pro.kotlin.helper_common

import androidx.core.os.bundleOf
import com.google.gson.Gson
import com.morefun.yapi.device.mdb.IMdbService
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.device.DeviceHelper
import com.sc.mf919pro.kotlin.activity.MainActivity
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelDenominationList
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import helpers.HelperCommon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mdb.MdbHost
import mdb.MdbLogHeader
import utils.AmountFormat

/**
 * Pro's half of the MDB seam.
 *
 * The MDB protocol itself lives in `:core` and is shared with MF919. What differs is everything
 * below: Pro drives a Nav graph rather than Activities, so navigation goes out as
 * [UiEvent.FragmentNavigation] on [AppBus] and screen readiness is answered from the
 * NavController's current destination rather than from an Activity instance.
 */
object ProMdbHost : MdbHost {

	override fun cableConnectionMethod(): String? =
		DbModelTerminalConfig.getSafeValue(ServiceHolder.getTerminalConfig(), "CABLE_CONNECTION")

	override fun mdbService(): IMdbService? = try {
		DeviceHelper.getMdbService()
	} catch (ex: Exception) {
		Utils.printLog("MDB service lookup failed :: ${ex.javaClass.simpleName}: ${ex.message}")
		null
	}

	override fun onTransportActive(active: Boolean) {
		HTTPServer.socketInterface = if (active) 3 else -1
	}

	/**
	 * Mirrors MainActivity.setupNavigation's start-destination pick, so "home" means the same
	 * screen whether the app just launched or MDB sent it back here.
	 */
	private fun homeDestinationId(): Int {
		val cfg = ServiceHolder.getTerminalConfig()
		return when {
			DbModelTerminalConfig.getBooleanValue(cfg, "DENOMINATION") -> R.id.attendDenominationFragment
			DbModelTerminalConfig.getBooleanValue(cfg, "UNATTENDED_MODE") -> R.id.unAttendFragment
			else -> R.id.attendFragment
		}
	}

	override fun navigateHome() {
		CoroutineScope(Dispatchers.Main).launch {
			AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(homeDestinationId(), bundleOf()))
		}
	}

	/**
	 * The denomination screen must be the one on top -- a VMC vend price has nowhere to be shown
	 * otherwise. MF919 answers this by checking its Activity; Pro asks the nav graph.
	 */
	override fun isVendingScreenReady(): Boolean =
		MainActivity.currentDestinationId == R.id.attendDenominationFragment

	/**
	 * No-op. On MF919 this finishes the attend Activity so the new one starts clean; Pro navigates
	 * within a single Activity, so [navigateHome] already leaves the graph where it should be and
	 * popping here would fight it.
	 */
	override fun finishVendingScreen() = Unit

	override fun navigateToPaymentOption(decimalPrice: String) {
		val product = DbModelDenominationList(
			"", "", "", AmountFormat.getActualAmount(decimalPrice), "", decimalPrice,
			"", "", "", "", "", ""
		)
		CoroutineScope(Dispatchers.Main).launch {
			AppBus.emitWhenSubscribed(
				UiEvent.FragmentNavigation(
					R.id.denominationPaymentOptionFragment,
					bundleOf("denomination_product" to Gson().toJson(product))
				)
			)
		}
	}

	override fun currentInvoiceNo(): String = TransData.invoiceNo

	/**
	 * qrRespCode 0000 is the only thing that says the sale was paid by QR; anything else means
	 * card, and an empty ref sends the void down the card path.
	 */
	override fun currentQrRef(): String =
		if (TransData.qrRespCode == "0000") TransData.qrRef else ""

	override fun printLog(message: String) = Utils.printLog(message)

	override fun logHeader(): MdbLogHeader = MdbLogHeader(
		session = HelperCommon.getSession(),
		isWifi = TmsHelper.checkIsConnectedWifi(ServiceHolder.getContext()),
		ipAddress = Utils.getIPAddress(),
	)

	override fun onReaderStateChanged(enabled: Boolean) {
		CoroutineScope(Dispatchers.IO).launch { AppBus.emit(UiEvent.MdbStateChange(enabled)) }
	}

	override fun onVendingPrice(priceHex: String) {
		CoroutineScope(Dispatchers.IO).launch { AppBus.emit(UiEvent.MdbVendingPrice(priceHex)) }
	}

	override fun onVendingForceEnd() {
		AppBus.tryEmit(UiEvent.MdbVendingForceEnd)
	}
}
