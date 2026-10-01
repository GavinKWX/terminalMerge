package tms

import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.content.Context.MODE_PRIVATE
import android.os.BatteryManager
import enums.EnumLogFileName
import env.EnvironmentManager
import helpers.HelperLog
import helpers.HelperNetwork
import helpers.TerminalInfo
import iso.CurrentStore
import tms.handlers.DeviceInfoHandler
import tms.models.DeviceTestCaseList
import ws.newLogSession

/**
 * The DeviceInfo poll and the tasks TMS hands back. Moved to `:core` from both apps, where it was
 * identical (audit item 107); the per-app side is [TmsHost].
 */
class Tms(private val mContext: Context) {

	private val className: String = Tms::class.java.name

	private val log = HelperLog(
		newLogSession(),
		HelperNetwork.isConnectedWifi(mContext),
		TerminalInfo.ipAddress(),
		"TMS Activity",
		Tms::class.java.simpleName,
		className
	)

	fun downloadMerchantInfo(): Int = if (CurrentTmsHost.downloadMerchantConfig(log, mContext)) 0 else -1

	fun uploadTms() {
		val environmentManager = EnvironmentManager(mContext.getSharedPreferences(mContext.packageName, MODE_PRIVATE))
		val deviceInfoHandler = DeviceInfoHandler(environmentManager)
		try {
			val testCase = arrayListOf(DeviceTestCaseList("BATTERYSTATUS", batteryLevel()))
			val apiResp = deviceInfoHandler.invoke(
				log,
				TerminalInfo.sqnNum(),
				TerminalInfo.serialNumber(),
				TerminalInfo.deviceModel(),
				CurrentTmsHost.appName(mContext),
				CurrentTmsHost.terminalConfigValue("DEV_PROJECT"),
				CurrentTmsHost.terminalConfigValue("DEV_LOCATION"),
				CurrentTmsHost.terminalConfigValue("DEV_LANE_ID"),
				"",
				TerminalInfo.appVersion(),
				CurrentTmsHost.mcVersion(),
				"-",
				testCase
			)
			log.appendLine(className, "DeviceInfoHandler Response -> ", apiResp.toString())

			// Gson can leave TASK_NAME null despite its type; that is no tasks, as before.
			@Suppress("USELESS_CAST")
			val tasks = (apiResp.TASK_NAME as List<String?>?).orEmpty()
			applyTasks(tasks, CurrentTmsHost, log, mContext) { value, tag, subtag ->
				CurrentStore.updateBatchInfo(mContext, value, tag, subtag)
			}
		} catch (ex: Exception) {
			log.appendLine(className, "DeviceInfoHandler Response (Exception)", ex.toString())
		} finally {
			log.logToFile(EnumLogFileName.TerminaLog)
		}
	}

	private fun batteryLevel(): Int =
		(mContext.getSystemService(BATTERY_SERVICE) as BatteryManager)
			.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

	companion object {
		/**
		 * Runs each task in order; unknown and null entries are skipped (the old JSONArray read null as "null").
		 * After a merchant-config download the acquirer fields are copied to both schemes' batch rows.
		 */
		internal fun applyTasks(
			tasks: List<String?>,
			host: TmsHost,
			log: HelperLog,
			context: Context,
			writeBatch: (value: String, tag: String, subtag: String) -> Unit
		) {
			for (task in tasks) {
				when (task) {
					"MerchantConfigUpdate" -> if (host.downloadMerchantConfig(log, context)) {
						val acqMid = host.merchantConfigValue("AcqMid")
						val acqTid = host.merchantConfigValue("AcqTid")
						val tpdu = host.merchantConfigValue("TPDU")
						val nii = host.merchantConfigValue("NII")
						for (scheme in listOf("visam", "mccs")) {
							writeBatch(acqMid, "mid", scheme)
							writeBatch(acqTid, "tid", scheme)
							writeBatch(tpdu, "isoTpduHeader", scheme)
							writeBatch(tpdu, "isoTpduHeaderTle", scheme)
							writeBatch(nii, "nii", scheme)
							writeBatch(nii, "niiTle", scheme)
						}
					}
					"TerminalConfigUpdate" -> host.downloadTerminalConfig(log, context)
					"InjectKeyUpdate" -> host.downloadInjectionKey(log, context)
				}
			}
		}
	}
}
