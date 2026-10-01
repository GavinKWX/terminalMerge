package com.sc.mf919pro.kotlin.helper_common

import android.content.Context
import com.sc.mf919pro.R
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import helpers.HelperLog
import tms.TmsHost

/** This app's half of the TMS seam; [tms.Tms] itself lives in `:core` (audit item 107). */
object ProTmsHost : TmsHost {

	override fun appName(context: Context): String = context.getString(R.string.app_name)

	override fun mcVersion(): String = ServiceHolder.getMcVersion()

	override fun terminalConfigValue(attr: String): String =
		DbModelTerminalConfig.getSafeValue(ServiceHolder.getTerminalConfig(), attr)

	override fun merchantConfigValue(attr: String): String =
		DbModelMerchantConfig.getSafeValue(ServiceHolder.getMerchantInfo(), attr)

	override fun downloadMerchantConfig(log: HelperLog, context: Context): Boolean =
		TmsHelper.getMerchantConfiguration(log, context)

	override fun downloadTerminalConfig(log: HelperLog, context: Context): Boolean =
		TmsHelper.getTerminalConfiguration(log, context)

	override fun downloadInjectionKey(log: HelperLog, context: Context): Boolean =
		TmsHelper.getInjectionKey(log, context)
}
