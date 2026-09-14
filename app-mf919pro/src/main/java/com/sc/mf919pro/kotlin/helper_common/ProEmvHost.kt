package com.sc.mf919pro.kotlin.helper_common

import com.morefun.yapi.emv.EmvHandler
import com.sc.mf919pro.java.device.DeviceHelper
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import emv.EmvHost

/**
 * This app's side of [EmvHost].
 *
 * The handler comes from DeviceHelper, which owns the bound device-service lifecycle, and the
 * flag from this app's terminal config -- models stay per app by ruling. Registered once per
 * process in MF919.onCreate.
 */
object ProEmvHost : EmvHost {

	override fun emvHandler(): EmvHandler = DeviceHelper.getEmvHandler()

	override fun isOptIn(): Boolean =
		DbModelTerminalConfig.getBooleanValue(ServiceHolder.getTerminalConfig(), "OptIn")
}
