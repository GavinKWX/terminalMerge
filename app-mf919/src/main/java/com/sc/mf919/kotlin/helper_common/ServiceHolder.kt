package com.sc.mf919.kotlin.helper_common

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.RemoteException
import com.library.terminal.Utility
import com.morefun.yapi.engine.DeviceInfoConstrants
import com.sc.mf919.R
import com.sc.mf919.java.MF919
import com.sc.mf919.java.activity.CubeActivity
import com.sc.mf919.java.activity.Global
import com.sc.mf919.java.activity.IsoComm
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.device.DeviceHelper
import com.sc.mf919.kotlin.data_enum.AcquirerSettingModel
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.MerchantConfigurationRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.database.repo.TerminalConfigurationRepo
import enums.EnumLogFileName
import helpers.HelperCommon
import java.io.File
import java.net.NetworkInterface
import java.net.SocketException

class ServiceHolder {
	private fun getDeviceInfo() {
		val devInfo: Bundle?
		try {
			val deviceHelper = DeviceHelper.getDeviceService()
			if (deviceHelper != null) {
				devInfo = deviceHelper.devInfo
				val vendor = devInfo.getString(DeviceInfoConstrants.COMMOM_VENDOR)
				deviceModel = devInfo.getString(DeviceInfoConstrants.COMMOM_MODEL)
				version = devInfo.getString(DeviceInfoConstrants.COMMOM_OS_VER)
				terminalSerialNumber = devInfo.getString(DeviceInfoConstrants.COMMOM_SN)
				val tusn = devInfo.getString(DeviceInfoConstrants.TID_SN)
				val versionCode = devInfo.getString(DeviceInfoConstrants.COMMON_SERVICE_VER)
				hardware = devInfo.getString("hardware")
			}
		} catch (e: RemoteException) {
			e.printStackTrace()
		}
	}

	companion object {
		@SuppressLint("StaticFieldLeak")
		val serviceHolder = ServiceHolder()

		@SuppressLint("StaticFieldLeak")
		var isoComm: IsoComm? = null
		var cardResult: Int = Global.iso.err.txnApproved
		var settlementDialogMessage: String = ""


		// Always the Application context — seeded once in MF919.onCreate().
		@SuppressLint("StaticFieldLeak")
		lateinit var mContext: Context

		fun setContext(con: Context) {
			mContext = con.applicationContext
		}

		fun getContext(): Context{
			if(!this::mContext.isInitialized){
				mContext = MF919.getAppContext()
				HelperCommon.context = mContext
				Helper.getInstance().Initialize(mContext)
			}
			return mContext
		}

		private const val TAG = "BindService"
		private const val SN_TAG = "devices_serial_number"
		private const val DEVICE_MODEL_TAG = "devices_model"
		private const val SQN_TAG = "sqn_no"
		var mCube: CubeActivity? = null
		private var terminalSerialNumber: String? = null
		private var deviceModel: String? = null
		private var MC_VER: String? = null
		private var CERT_VER: String? = null
		private var MIGRATE_VER: Int = 0
		private var version: String? = null
		private var hardware: String? = null
		private var merchantInfo: MutableMap<String, String> = mutableMapOf()
		private var termInfo: MutableMap<String, String> = mutableMapOf()
		private var isoEngine: MutableMap<String, String> = mutableMapOf()
		private var tms: MutableMap<String, String> = mutableMapOf()
		private var tmsUrl: MutableMap<String, String> = mutableMapOf()
		private var merchantConfigInfo: DbModelMerchantConfig? = null
		private var terminalConfig: DbModelTerminalConfig? = null
		var remarkFooter = ""

		private var merchantProductList: List<DbModelProductList>? = null
		private var merchantProduct: List<String>? = null
		var selectedCacheModel: Any? = null
		var saleModelCache: SaleModelNew? = null
		var selectedSettlementModel: Any? = null
		// -1 = not yet loaded from prefs
		private var sqnNo = -1
		var autoSettlementIsRunning = false
		var clearSettlementBatch = false
		var autoSettlementPastDate = false
		var autoSettlementQueue = false
		var autoSettlementTimeStamp = 0L
		var packageName: String? = null
		var activityName: String? = null
		var appIntent = false
		var appHTTP = false
		var txnType = 0
		// True while the app is starting up. Read on nanohttpd worker threads by the HTTPServer
		// startup guard, so it needs @Volatile.
		@Volatile
		var appFreshLoad = true
		var requireDownloadLogo = false
        var appRunningProcess = false
		var uploadingReceipt = false
		var uploadingReceiptTimeStamp = 0L
		var blockSale = false
		var oxpayEwalletIntent = ""

		val defaultAckCountdownSecond = 30
		var ackCountDownSecond = 30

		val className: String = ServiceHolder::class.java.name

		var autoTestCard = 0

		val SR800_MODEL = "SR800"

		fun getExternalStoragePaths(): String {
			var paths: String
			try {
				val context = getContext()
				paths = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					val file = context.getExternalFilesDir("Downloads")!!
					file.absolutePath
				} else {
					"${Environment.getExternalStorageDirectory()}/Download/${context.packageName}"
				}
				val isFileExist = File(paths)
				if (!isFileExist.exists()) {
					isFileExist.mkdirs()
				}
			} catch (e: Exception) {
				val log = helpers.HelperLog(
					HelperCommon.getSession(),
					TmsHelper.checkIsConnectedWifi(getContext()),
					Utils.getIPAddress(),
					"ServiceHolder - getExternalStoragePaths",
					this.javaClass.simpleName,
					this.javaClass.simpleName
				)
				log.appendLine(className, "Exception in Get External Storage Path", e.toString())
				log.logToFile(EnumLogFileName.TerminaLogException)
				e.printStackTrace()
				paths = ""
			}
			return paths
		}

		fun getInternalStoragePaths(): String {
			var paths: String

			try {
				paths = getContext().applicationInfo.dataDir
			} catch (e: Exception) {
				val log = helpers.HelperLog(
					HelperCommon.getSession(),
					TmsHelper.checkIsConnectedWifi(getContext()),
					Utils.getIPAddress(),
					"ServiceHolder - getInternalStoragePaths",
					this.javaClass.simpleName,
					this.javaClass.simpleName
				)
				log.appendLine(className, "Exception in Get Internal Storage Path", e.toString())
				log.logToFile(EnumLogFileName.TerminaLogException)
				e.printStackTrace()
				paths = ""
			}
			return paths
		}

		fun getInternalFilesPaths(): String? {
			return "${getInternalStoragePaths()}/files/"
		}

		fun getGeneralExternalStoragePaths(): String? {
			return Environment.getExternalStorageDirectory().absolutePath
		}

		@JvmStatic
		fun getTerminalSerialNumber(): String {
			var result = terminalSerialNumber
			if (result.isNullOrEmpty()) {
				val prefs = Helper.getInstance().getPrefs()!!
				result = prefs.getString(SN_TAG, null) ?: run {
					serviceHolder.getDeviceInfo()
					prefs.edit().putString(SN_TAG, terminalSerialNumber).apply()
					terminalSerialNumber
				}
			}
			return "98213199990004"
			return result ?: ""
		}

		@JvmStatic
		fun getDeviceModel(): String {
			var result = deviceModel
			if (result.isNullOrEmpty()) {
				val prefs = Helper.getInstance().getPrefs()!!
				result = prefs.getString(DEVICE_MODEL_TAG, null) ?: run {
					serviceHolder.getDeviceInfo()
					prefs.edit().putString(DEVICE_MODEL_TAG, deviceModel).apply()
					deviceModel
				}
			}
			return "MF919"
			return result ?: ""
		}

		@JvmStatic
		fun checkCertVersion(newCertVersion: String): Boolean {
			var result = false
			var currentCertVersion = CERT_VER
			if(currentCertVersion.isNullOrEmpty()) {
				val prefs = Helper.getInstance().getPrefs()!!
				currentCertVersion = prefs.getString("CERT_VER", "")
			}

			if (currentCertVersion != newCertVersion) {
				result = true
			}
			return result
		}

		@JvmStatic
		fun setCertVersion(newCertVersion: String) {
			val prefs = Helper.getInstance().getPrefs()!!
			prefs.edit().putString("CERT_VER", newCertVersion).apply()
			CERT_VER = newCertVersion
		}

		@JvmStatic
		fun getMcVersion(): String {
			var result = MC_VER
			if (result.isNullOrEmpty()) {
				val prefs = Helper.getInstance().getPrefs()!!
				result = prefs.getString("MC_VER", "")
			}
			return result ?: ""
		}

		@JvmStatic
		fun setMcVersion(value: String) {
			val tempVal = getMcVersion()
			var tempDownloadLogo = false
			if (tempVal != value) {
				val prefs = Helper.getInstance().getPrefs()!!
				prefs.edit().putString("MC_VER", value).apply()
				tempDownloadLogo = true
			}

			// if detected true then cannot change this field
			if (!requireDownloadLogo) {
				requireDownloadLogo = tempDownloadLogo
			}
		}

		/**
		 * Drops the in-memory MIGRATE_VER copy.
		 *
		 * [getMigrationVersion] returns this static and only falls back to SharedPreferences when
		 * it is 0, so a value already read this process would mask a reset. DbHandler calls this
		 * (via DbSchema.onMigrationVersionReset) after it recopies the preloaded database, so the
		 * migrations replay. MF919 gained this path in Phase 2b along with the shared DbHandler.
		 */
		@JvmStatic
		fun invalidateMigrationVersionCache() {
			MIGRATE_VER = 0
		}

		@JvmStatic
		fun getMigrationVersion(): Int {
			var result = MIGRATE_VER
			if (result == 0) {
				val prefs = Helper.getInstance().getPrefs()!!
				result = prefs.getInt("MIGRATE_VER", 0)
			}
			return result
		}

		@JvmStatic
		fun setMigrationVersion(version: Int) {
			val prefs = Helper.getInstance().getPrefs()!!
			prefs.edit().putInt("MIGRATE_VER", version).apply()
			MIGRATE_VER = version
		}

		/**
		 * Construction is guarded: several of these helpers run before the app context or the
		 * session exist, and none of them may fail because of their own logging.
		 */
		private fun newLog(purpose: String): helpers.HelperLog? = try {
			helpers.HelperLog(
				HelperCommon.getSession(),
				TmsHelper.checkIsConnectedWifi(getContext()),
				Utils.getIPAddress(),
				purpose,
				ServiceHolder::class.java.simpleName,
				ServiceHolder::class.java.simpleName
			)
		} catch (e: Exception) {
			Utils.printErrorLog(TAG, "newLog", e.message)
			null
		}

		fun initialFiles() {
			val filenames = arrayOf(
				"isoengine.ini",
				"merchantInfo.txt",
				"termInfo.txt",
				"tms.txt",
				"tmsUrl.ini"
			)
			// One block for the whole config load. Per-line parse failures are appended here
			// rather than fired off individually: they arrive in bursts from one bad file and
			// are only meaningful next to the file they came from.
			val log = newLog("ServiceHolder - initialFiles")
			log?.appendLine(className, "Initial Files [START]")
			var parseErrors = 0
			for (file in filenames) {
				val value = Utils.readFromFile(file)
				val temp: MutableMap<String, String> = LinkedHashMap()
				for (line in value) {
					try {
						temp[line.split("=").toTypedArray()[0]] = line.split("=").toTypedArray()[1]
					} catch (e: Exception) {
						parseErrors++
						log?.appendLine(className, "initialFiles parse error", "$file :: ${e.message}")
					}
				}
				when (file) {
					"isoengine.ini" -> {
						isoEngine = temp
					}
					"merchantInfo.txt" -> {
						merchantInfo = temp
					}
					"termInfo.txt" -> {
						termInfo = temp
					}
					"tms.txt" -> {
						tms = temp
					}
					"tmsUrl.ini" -> {
						tmsUrl = temp
					}
				}
			}
			log?.appendLine(className, "Initial Files [END] :: files=${filenames.size} parseErrors=$parseErrors")
			log?.logToFile(
				if (parseErrors > 0) EnumLogFileName.TerminaLogException else EnumLogFileName.TerminaLog
			)
		}

		// Deliberately left on Timber: getStringValue is the accessor for every config lookup
		// in the app (getIntValue/getBooleanValue both route through it), so a correlated file
		// line here would be a per-read disk write for no diagnostic gain.
		fun getStringValue(filename: String, tag: String): String {
			Utils.debugLogPrint(
				TAG,
				"getStringValue: $filename-->$tag"
			)
			var temp: MutableMap<String, String> = mutableMapOf()
			when (filename) {
				"isoengine.ini" -> {
					temp = isoEngine
				}
				"merchantInfo.txt" -> {
					temp = merchantInfo
				}
				"termInfo.txt" -> {
					temp = termInfo
				}
				"tms.txt" -> {
					temp = tms
				}
				"tmsUrl.ini" -> {
					temp = tmsUrl
				}
			}
			var value = temp[tag]
			if (value == null) {
				value = ""
			}
			return value
		}

		fun getIntValue(filename: String, tag: String): Int {
			var value = getStringValue(filename, tag)
			if (value == "") {
				value = "0"
			}
			return value.toInt()
		}

		fun getBooleanValue(filename: String, tag: String): Boolean {
			var value = getStringValue(filename, tag)
			if (value == "") {
				value = "0"
			}
			return value.toInt() == 1
		}

		fun setStringValue(filename: String?, tag: String, value: String) {
			var temp: MutableMap<String, String> = mutableMapOf()
			when (filename) {
				"isoengine.ini" -> {
					isoEngine[tag] = value
					temp = isoEngine
				}
				"merchantInfo.txt" -> {
					merchantInfo[tag] = value
					temp = merchantInfo
				}
				"termInfo.txt" -> {
					termInfo[tag] = value
					temp = termInfo
				}
				"tms.txt" -> {
					tms[tag] = value
					temp = tms
				}
				"tmsUrl.ini" -> {
					tmsUrl[tag] = value
					temp = tmsUrl
				}
			}
			val finalTemp = temp
			object : Thread() {
				override fun run() {
					super.run()
					if (finalTemp.isNotEmpty()) {
						val values = finalTemp.keys.toTypedArray()
						for (i in 0 until finalTemp.size) {
							values[i] = values[i] + "=" + finalTemp[values[i]]
						}
						Utils.write2File(values, filename)
					}
				}
			}.start()
		}

		fun setIntValue(filename: String?, tag: String, _value: Int) {
			var temp: MutableMap<String, String> = mutableMapOf()
			val value = _value.toString()
			when (filename) {
				"isoengine.ini" -> {
					isoEngine[tag] = value
					temp = isoEngine
				}
				"merchantInfo.txt" -> {
					merchantInfo[tag] = value
					temp = merchantInfo
				}
				"termInfo.txt" -> {
					termInfo[tag] = value
					temp = termInfo
				}
				"tms.txt" -> {
					tms[tag] = value
					temp = tms
				}
				"tmsUrl.ini" -> {
					tmsUrl[tag] = value
					temp = tmsUrl
				}
			}
			val finalTemp = temp
			object : Thread() {
				override fun run() {
					super.run()
					val values = finalTemp.keys.toTypedArray()
					for (i in 0 until finalTemp.size) {
						values[i] = values[i] + "=" + finalTemp[values[i]]
					}
					Utils.write2File(values, filename)
				}
			}.start()
		}

		fun setBooleanValue(filename: String?, tag: String, _value: Boolean) {
			var temp: MutableMap<String, String> = mutableMapOf()
			var value = "0"
			if (_value) {
				value = "1"
			}

			when (filename) {
				"isoengine.ini" -> {
					isoEngine[tag] = value
					temp = isoEngine
				}
				"merchantInfo.txt" -> {
					merchantInfo[tag] = value
					temp = merchantInfo
				}
				"termInfo.txt" -> {
					termInfo[tag] = value
					temp = termInfo
				}
				"tms.txt" -> {
					tms[tag] = value
					temp = tms
				}
				"tmsUrl.ini" -> {
					tmsUrl[tag] = value
					temp = tmsUrl
				}
			}
			val finalTemp = temp
			object : Thread() {
				override fun run() {
					super.run()
					val values = finalTemp.keys.toTypedArray()
					for (i in 0 until finalTemp.size) {
						values[i] = values[i] + "=" + finalTemp[values[i]]
					}
					Utils.write2File(values, filename)
				}
			}.start()
		}

		fun restoreFile(mapTem: Map<String?, String?>?, currentTableName: String?) {}

		fun getFile(currentTableName: String?): Map<String, String>? {
			return isoEngine
		}

		@JvmStatic
		@Synchronized
		fun getSqnNum(): String {
			val prefs = Helper.getInstance().getPrefs()!!
			if (sqnNo < 0) {
				sqnNo = prefs.getInt(SQN_TAG, 0)
			}
			sqnNo = if (sqnNo >= 999999) 0 else sqnNo + 1
			prefs.edit().putInt(SQN_TAG, sqnNo).apply()
			return Utility.zeroPadding(sqnNo.toString(), 6)
		}

		@JvmStatic
		fun getAppVersion(): String {
			var appVersion = ""
			try {
				val context = getContext()
				val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
				appVersion = pInfo.versionName ?: ""
			} catch (e: PackageManager.NameNotFoundException) {
				val log = helpers.HelperLog(
					HelperCommon.getSession(),
					TmsHelper.checkIsConnectedWifi(getContext()),
					Utils.getIPAddress(),
					"ServiceHolder - getAppVersion",
					this.javaClass.simpleName,
					this.javaClass.simpleName
				)
				log.appendLine(className, "Exception in Get App Version", e.toString())
				log.logToFile(EnumLogFileName.TerminaLogException)
				e.printStackTrace()
			}
			return appVersion
		}

		fun getMap(file: String?): Map<String, String>? {
			var temp: Map<String, String>? = null
			when (file) {
				"isoengine.ini" -> {
					temp = isoEngine
				}
				"merchantInfo.txt" -> {
					temp = merchantInfo
				}
				"termInfo.txt" -> {
					temp = termInfo
				}
				"tms.txt" -> {
					temp = tms
				}
				"tmsUrl.ini" -> {
					temp = tmsUrl
				}
			}
			return temp
		}

		fun restoreMap(temp: MutableMap<String, String>, filename: String?) {
			when (filename) {
				"isoengine.ini" -> {
					isoEngine = temp
				}
				"merchantInfo.txt" -> {
					merchantInfo = temp
				}
				"termInfo.txt" -> {
					termInfo = temp
				}
				"tms.txt" -> {
					tms = temp
				}
				"tmsUrl.ini" -> {
					tmsUrl = temp
				}
			}
		}

		@JvmStatic
		fun getAcquirerSetting(): AcquirerSettingModel {
			val config = merchantConfigInfo ?: getMerchantInfo()
			return when (config?.AcqCode?.uppercase()) {
				"GOBIZ" -> AcquirerSettingModel("Gobiz", true, false, 0, false)
				"PAYDEE" -> AcquirerSettingModel("Paydee", true, false, 0, false)
				"BSN_CARDZONE" -> AcquirerSettingModel("Bsn_Cardzone", true, true, R.raw.bsn_keystore, true)
				"BSN" -> AcquirerSettingModel("Bsn", false, true, R.raw.bsn_keystore, false)
				"PAYEX_GOBIZ" -> AcquirerSettingModel("Payex_Gobiz", true, false, 0, false)
				"FINEXUS" -> AcquirerSettingModel("Finexus", true, false, 0, false)
				else -> AcquirerSettingModel("", false, false, 0, false)
			}
		}

		@JvmStatic
		fun getMerchantProduct(): List<String>? {
			if (merchantProduct.isNullOrEmpty()) {
				val products = ProductListRepo.getDistinctProduct(getContext()).map { it.Product }
				val desiredOrder = listOf("CARD_SETTINGS", "EWALLET_MERCHANT_SCANS", "BNPL", "GENERATE_QR", "EPP", "MOTO")
				merchantProduct = desiredOrder.filter { it in products }
			}
			return merchantProduct
		}

		@JvmStatic
		fun getMerchantProductList(): List<DbModelProductList>? {
			if (merchantProductList == null || merchantProductList!!.isEmpty()) {
				val modelProductLists = ProductListRepo.getAll(getContext())
				merchantProductList = modelProductLists
				return modelProductLists
			}
			return merchantProductList
		}

		@JvmStatic
		fun getMerchantInfo(): DbModelMerchantConfig? {
			if (merchantConfigInfo == null) {
				val tempMerchantConfigData = MerchantConfigurationRepo.getSingle(getContext())
				merchantConfigInfo = tempMerchantConfigData
				return tempMerchantConfigData
			}
			return merchantConfigInfo
		}

		@JvmStatic
		fun getTerminalConfig(): DbModelTerminalConfig? {
			if (terminalConfig == null) {
				val tempTerminalConfig = TerminalConfigurationRepo.getSingle(getContext())
				terminalConfig = tempTerminalConfig
				return tempTerminalConfig
			}
			return terminalConfig
		}

		@JvmStatic
		fun getSpecificProduct(name: String): DbModelProductList? {
			if(getMerchantProductList() != null){
				for (item in merchantProductList!!) {
					if (item.Product == name) {
						return item
					}
				}
			}
			return null
		}

		@JvmStatic
		fun clearMerchantInformation() {
			merchantConfigInfo = null
			merchantProduct = null
			merchantProductList = null
		}

		@JvmStatic
		fun clearProductListing() {
			merchantProduct = null
			merchantProductList = null
		}

		@JvmStatic
		fun clearTerminalConfigInformation() {
			terminalConfig = null
		}

		@JvmStatic
		fun getCurrentLocalIpAddress(): String {
			try {
				val interfaces = NetworkInterface.getNetworkInterfaces()
				for (intf in interfaces) {
					if (intf.isUp && !intf.isLoopback) {
						val addresses = intf.inetAddresses
						for (address in addresses) {
							val host = address.hostAddress
							if (!address.isLoopbackAddress && host != null && !host.contains(":")) {
								return host
							}
						}
					}
				}
			} catch (e: SocketException) {
				e.printStackTrace()
			}
			return "127.0.0.1"
		}

		@JvmStatic
		fun getOxpayIntent(): String {
			var result = oxpayEwalletIntent
			if (result.isEmpty()) {
				val prefs = Helper.getInstance().getPrefs()
				result = prefs?.getString("OXPAY_INTENT", "") ?: ""
			}
			return result
		}

		@JvmStatic
		fun setOxpayIntent(value: String) {
			val tempVal = getOxpayIntent()
			if (tempVal != value) {
				oxpayEwalletIntent = ""
				val prefs = Helper.getInstance().getPrefs()
                prefs?.edit()?.putString("OXPAY_INTENT", value)?.apply()

				// Only the change is recorded - this is called with the same value repeatedly.
				val log = newLog("ServiceHolder - setOxpayIntent")
				log?.appendLine(className, "Oxpay intent changed", "'$tempVal' -> '$value'")
				log?.logToFile(EnumLogFileName.TerminaLog)
			}
		}
	}
}