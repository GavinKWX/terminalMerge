package ws

import android.content.Context
import java.util.UUID

/**
 * What the WebSocket pair needs from its app. Moved into `:core` from both apps, where the two
 * files were byte-identical (audit item 84). Each app registers its implementation in `java.MF919`.
 */
interface WsHost {

	fun context(): Context

	/** Hand an incoming ECR message to this app's HTTPServer. */
	fun onEcrMessage(message: String)

	/** TMS `UpdatePrice` push: clear the local denomination list so it is re-fetched. */
	fun clearDenominationList()

	/** The TMS push-channel URL (`socketHandlerUrl`), without the `?sn=` query. */
	fun socketHandlerUrl(): String
}

object CurrentWsHost : WsHost {

	@Volatile
	private var backing: WsHost? = null

	@JvmStatic
	fun register(host: WsHost) {
		backing = host
	}

	@JvmStatic
	fun isRegistered(): Boolean = backing != null

	private val host: WsHost
		get() = backing
			?: error("CurrentWsHost used before register() -- wire it in Application.onCreate")

	override fun context(): Context = host.context()

	override fun onEcrMessage(message: String) = host.onEcrMessage(message)

	override fun clearDenominationList() = host.clearDenominationList()

	override fun socketHandlerUrl(): String = host.socketHandlerUrl()
}

/** Log session id, same shape as each app's HelperCommon.getSession(). */
internal fun newLogSession(): String = UUID.randomUUID().toString().substring(0, 7)
