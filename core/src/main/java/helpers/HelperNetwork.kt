package helpers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Network reachability checks shared by `:core`. Moved out of both apps' `TmsHelper`, which held
 * identical copies.
 *
 * Each app keeps a `TmsHelper.checkIsConnectedWifi` that delegates here -- there are around 200
 * call sites between them and widening the seam is not worth churning every one.
 */
object HelperNetwork {

	/**
	 * True only when the active network is **WiFi**. Mobile data reads as false.
	 *
	 * That is deliberate, not an oversight: log upload and firmware download are gated on this so
	 * the terminal never spends a SIM's data allowance on them.
	 */
	@JvmStatic
	fun isConnectedWifi(context: Context): Boolean {
		val connectivityManager = context.getSystemService(
			Context.CONNECTIVITY_SERVICE
		) as ConnectivityManager
		val capabilities = connectivityManager.getNetworkCapabilities(
			connectivityManager.activeNetwork
		)
		if (capabilities != null) {
			return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
		}
		return false
	}
}
