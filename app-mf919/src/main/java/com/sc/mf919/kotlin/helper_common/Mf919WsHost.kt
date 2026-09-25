package com.sc.mf919.kotlin.helper_common

import android.content.Context
import com.sc.mf919.kotlin.database.repo.DenominationListRepo
import env.EnvironmentManager
import env.EnvironmentVariables
import ws.WsHost

/** MF919's half of the WebSocket seam; the pair itself lives in `:core` (audit item 84). */
object Mf919WsHost : WsHost {

	override fun context(): Context = ServiceHolder.getContext()

	override fun onEcrMessage(message: String) = HTTPServer.checkWebSocketIncoming(message)

	override fun clearDenominationList() {
		DenominationListRepo.truncateTable(ServiceHolder.mContext)
	}

	override fun socketHandlerUrl(): String =
		EnvironmentManager(Helper.getInstance().getPrefs()!!).get(EnvironmentVariables::socketHandlerUrl)
}
