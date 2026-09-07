package com.sc.mf919.kotlin.helper_common

import android.content.Intent
import com.google.gson.Gson
import com.morefun.yapi.device.mdb.IMdbService
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.device.DeviceHelper
import com.sc.mf919.kotlin.activity.AttendDenominationActivity
import com.sc.mf919.kotlin.activity.DenominationPaymentOptionActivity
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelDenominationList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import helpers.HelperCommon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mdb.MdbHost
import mdb.MdbLogHeader
import utils.AmountFormat

/**
 * MF919's half of the MDB seam.
 *
 * The controller itself moved into `:core` so both fleets share one MDB protocol implementation.
 * Everything below is what stays MF919-specific: it drives Activities with Intents and checks
 * `HTTPServer.attendActivityContext`, where Pro drives a Nav graph. Behaviour is unchanged from
 * when these lines lived inside MdbController.
 */
object Mf919MdbHost : MdbHost {

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

	override fun navigateHome() {
		val cfg = ServiceHolder.getTerminalConfig()
		val intent = HelperCommon.getHomeScreenIntent(ServiceHolder.getContext(), cfg)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		ServiceHolder.getContext().startActivity(intent)
	}

	/**
	 * The attend screen is only usable while the denomination Activity is up and not finishing --
	 * a vend price has nowhere to go otherwise.
	 */
	override fun isVendingScreenReady(): Boolean {
		val ctx = HTTPServer.attendActivityContext
		return ctx is AttendDenominationActivity && !ctx.isFinishing
	}

	override fun finishVendingScreen() {
		HTTPServer.attendActivityContext?.finish()
	}

	override fun navigateToPaymentOption(decimalPrice: String) {
		val product = DbModelDenominationList(
			"", "", "", AmountFormat.getActualAmount(decimalPrice), "", decimalPrice,
			"", "", "", "", "", ""
		)
		val intent = Intent(ServiceHolder.getContext(), DenominationPaymentOptionActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		intent.putExtra("denomination_product", Gson().toJson(product))
		ServiceHolder.getContext().startActivity(intent)
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
