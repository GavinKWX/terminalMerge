package tms

import android.content.Context
import helpers.HelperLog

/**
 * What [Tms] needs from an app: its name, its config rows and the three per-app downloads. The
 * downloads stay in each app's `TmsHelper` because they write that app's tables (audit item 107).
 * Registered in each app's `java.MF919`.
 */
interface TmsHost {

	/** `R.string.app_name`, sent as `DEV_APPNAME`. */
	fun appName(context: Context): String

	/** `ServiceHolder.getMcVersion()`, sent as the merchant config version. */
	fun mcVersion(): String

	/** A terminal-config field by name (`DEV_PROJECT`, ...), "" when absent. */
	fun terminalConfigValue(attr: String): String

	/** A merchant-config field by name (`AcqMid`, ...), "" when absent. */
	fun merchantConfigValue(attr: String): String

	fun downloadMerchantConfig(log: HelperLog, context: Context): Boolean

	fun downloadTerminalConfig(log: HelperLog, context: Context): Boolean

	fun downloadInjectionKey(log: HelperLog, context: Context): Boolean
}

object CurrentTmsHost : TmsHost {

	@Volatile
	private var backing: TmsHost? = null

	@JvmStatic
	fun register(host: TmsHost) {
		backing = host
	}

	@JvmStatic
	fun isRegistered(): Boolean = backing != null

	private val host: TmsHost
		get() = backing ?: error("CurrentTmsHost used before register() -- wire it in Application.onCreate")

	override fun appName(context: Context) = host.appName(context)

	override fun mcVersion() = host.mcVersion()

	override fun terminalConfigValue(attr: String) = host.terminalConfigValue(attr)

	override fun merchantConfigValue(attr: String) = host.merchantConfigValue(attr)

	override fun downloadMerchantConfig(log: HelperLog, context: Context) = host.downloadMerchantConfig(log, context)

	override fun downloadTerminalConfig(log: HelperLog, context: Context) = host.downloadTerminalConfig(log, context)

	override fun downloadInjectionKey(log: HelperLog, context: Context) = host.downloadInjectionKey(log, context)
}
