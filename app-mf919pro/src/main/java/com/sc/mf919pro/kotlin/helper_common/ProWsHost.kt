package com.sc.mf919pro.kotlin.helper_common

import android.content.Context
import com.sc.mf919pro.kotlin.database.repo.DenominationListRepo
import env.EnvironmentManager
import env.EnvironmentVariables
import ws.WsHost

/** Pro's half of the WebSocket seam; the pair itself lives in `:core` (audit item 84). */
object ProWsHost : WsHost {

	override fun context(): Context = ServiceHolder.getContext()

	override fun onEcrMessage(message: String) = HTTPServer.checkWebSocketIncoming(message)

	override fun clearDenominationList() {
		DenominationListRepo.truncateTable(ServiceHolder.mContext)
	}

	override fun socketHandlerUrl(): String =
		EnvironmentManager(Helper.getInstance().getPrefs()!!).get(EnvironmentVariables::socketHandlerUrl)
}
