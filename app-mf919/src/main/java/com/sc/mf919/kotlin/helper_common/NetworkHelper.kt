import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.TextView
import com.sc.mf919.R

fun getConnectionStatus(context: Context): Pair<Boolean, Boolean> {
	val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
	val network = cm.activeNetwork ?: return Pair(false, false)
	val caps = cm.getNetworkCapabilities(network) ?: return Pair(false, false)

	val wifiConnected = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
	val mobileConnected = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

	return Pair(wifiConnected, mobileConnected)
}

// --- UI helper ---
fun setConnectionStatus(textView: TextView, label: String, isConnected: Boolean) {
	textView.text = label

	val iconRes = if (isConnected)
		R.drawable.ic_tick
	else
		R.drawable.ic_cross

	textView.setCompoundDrawablesWithIntrinsicBounds(0, 0, iconRes, 0)
}

// --- Convenience wrapper ---
fun updateConnectionViews(context: Context, wifiView: TextView, simView: TextView) {
	val (wifi, mobile) = getConnectionStatus(context)

	setConnectionStatus(wifiView, "WIFI ", wifi)
	setConnectionStatus(simView, "Sim Card ", mobile)
}
