package com.sc.mf919.kotlin.helper_common

import com.sc.mf919.java.activity.Utils
import ecr.Route
import ecr.TransactionParser
import ecr.TransactionRouter
import ecr.TxnKeys
import helpers.IntegrationMode

/**
 * App-to-App new integration on MF919 (audit item 98, phase 3, D3). TransactionReceiver.java calls
 * this before its own switch when the request carries `IsNewIntegration: true`. Old requests never
 * reach it.
 */
object Mf919A2aNewIntegration {

	private val parser = TransactionParser(Mf919NewIntegrationHost)
	private val router = TransactionRouter(Mf919NewIntegrationHost)

	@JvmStatic
	fun isNew(txnMap: Map<String, String>): Boolean = IntegrationMode.flagIsTrue(txnMap[TxnKeys.NEW_INTEGRATION])

	/**
	 * Handles a new-integration request. Returns the answer to send back, or null when a screen
	 * was opened or there is no caller to answer (the receiver then just finishes), as Pro's does.
	 */
	@JvmStatic
	fun handle(txnMap: HashMap<String, String>): HashMap<String, String>? {
		if (IntegrationMode.flagIsTrue(txnMap[TxnKeys.OLD_INTEGRATION])) {
			Mf919NewIntegrationHost.begin(false)
			return txnMap.apply {
				put(TxnKeys.RESP_CODE, "SHC001")
				put(TxnKeys.RESP_DESC, "Invalid Parameter - (IsNewIntegration)")
			}
		}
		Mf919NewIntegrationHost.begin(true)
		val req = parser.parseMap(txnMap, forceNew = true).getOrElse {
			// No caller to answer (as Pro); undo the receiver's appIntent so later manual flows are not
			// treated as App-to-App (item 101).
			Mf919NewIntegrationHost.begin(false)
			ServiceHolder.appIntent = false
			return null
		}
		ServiceHolder.txnType = req.txnType
		ServiceHolder.ackCountDownSecond = if (req.hasAckCountdown) {
			Utils.atoi(req.ackCountdown ?: ServiceHolder.defaultAckCountdownSecond.toString())
		} else {
			ServiceHolder.defaultAckCountdownSecond
		}
		return when (val route = router.route(req)) {
			is Route.Navigate -> {
				Mf919NewIntegrationHost.launch(route.destination, route.args, finishAttend = false)
				null
			}
			is Route.Return -> route.resultMap
		}
	}
}
