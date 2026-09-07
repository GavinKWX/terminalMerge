package com.sc.mf919pro.kotlin.helper_common

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.RemoteException
import android.os.SystemClock
import com.library.terminal.Utility
import com.morefun.yapi.engine.DeviceInfoConstrants
import com.sc.mf919pro.R
import com.sc.mf919pro.java.MF919
import com.sc.mf919pro.java.activity.Global
import com.sc.mf919pro.java.activity.IsoComm
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.device.DeviceHelper
import com.sc.mf919pro.kotlin.data_enum.AcquirerSettingModel
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.database.repo.MerchantConfigurationRepo
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.database.repo.TerminalConfigurationRepo
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import enums.EnumLogFileName
import helpers.HelperCommon
import java.io.File
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*

class ServiceHolder {
	val m919ProModel = setOf("MF919S10")
	private fun getDeviceInfo() {
		val devInfo: Bundle?
		try {
			val deviceHelper = DeviceHelper.getDeviceService()
			if (deviceHelper != null) {
				devInfo = deviceHelper.devInfo
				val vendor = devInfo.getString(DeviceInfoConstrants.COMMOM_VENDOR)
				deviceModel = devInfo.getString(DeviceInfoConstrants.COMMOM_MODEL)
				//MF919 Pro Hardcode checking by deviceModelEx due to certification issue
				modelEx = devInfo.getString(DeviceInfoConstrants.COMMOM_MODEL_EX)
				modelEx?.let {
					val temp = it.trim()
						.uppercase()
						.replace("[^A-Za-z0-9]".toRegex(), "")

					if(temp in m919ProModel) {
						deviceModel = "MF919 PRO"
					}
				}
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

		private const val SN_TAG = "devices_serial_number"
		private const val DEVICE_MODEL_TAG = "devices_model"
		private const val SQN_TAG = "sqn_no"
		var ServiceCon: ServiceConnection? = null
		var receiverIntent: Intent? = null
		private var terminalSerialNumber: String? = null
		private var deviceModel: String? = null
		private var modelEx: String? = null
		private var MC_VER: String? = null
		private var CERT_VER: String? = null
		private var MIGRATE_VER: Int = 0
		private var version: String? = null
		private var hardware: String? = null
		private var merchantConfigInfo: DbModelMerchantConfig? = null
		private var terminalConfig: DbModelTerminalConfig? = null
		var remarkFooter = ""

		private var merchantProductList: List<DbModelProductList>? = null
		private var merchantProduct: List<String>? = null
		var selectedCacheModel: Any? = null
		var saleModelCache: SaleModelNew? = null
		// FoodLink Integration — survives the transData.reset() every payment fragment performs
		// on entry, so the verification result stays attached to the sale across the
		// keypad -> phone number -> (sub product) -> payment hops.
		var foodLinkCorrelationRef: String = ""
		var foodLinkAdditionalInfo: String = ""

		fun clearFoodLinkCache() {
			foodLinkCorrelationRef = ""
			foodLinkAdditionalInfo = ""
		}
		// -1 = not yet loaded from prefs
		private var sqnNo = -1
		/** Read on the NanoHTTPD worker thread in `HTTPServer.handleIncomingRequest`. */
		@Volatile
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
		var appFreshLoad = true

        /**
         * ECR readiness, kept separate from `appFreshLoad`: that flag also selects whether
         * `loadTask()` runs the full TMS refresh, so it cannot double as "startup in progress".
         *
         * `activityLoading` covers a single activity load, set at the top of
         * `MainActivity.onCreate` and cleared once that load has navigated. `startupTaskRunning`
         * belongs to the process: a recreate can finish its own load while the previous
         * incarnation's startup coroutine is still signing on, and ECR must stay closed until
         * that finishes too.
         *
         * Both are read on NanoHTTPD worker threads in `HTTPServer.submitRequest`.
         */
        @Volatile
        var activityLoading = false
        @Volatile
        var startupTaskRunning = false
        @Volatile
        var startupClaimedAt = 0L

        /** A startup claim older than this is treated as abandoned. */
        private const val STARTUP_GUARD_CEILING_MS = 120_000L

        /**
         * True while ECR must refuse with SHC000. The age ceiling means a missed clear cannot keep
         * ECR closed for the life of the process.
         */
        fun ecrStartupBlocking(): Boolean {
            if (!activityLoading && !startupTaskRunning) return false
            return SystemClock.elapsedRealtime() - startupClaimedAt < STARTUP_GUARD_CEILING_MS
        }

        fun markStartupBegun() {
            startupClaimedAt = SystemClock.elapsedRealtime()
        }
		var requireDownloadLogo = false
        /**
         * `@Volatile` because this is genuinely cross-thread: written
         * from **9 fragment sites on the UI thread** (EmvFragment, VoidQrFragment,
         * VoidPreAuthFragment, VoidSaleCompFragment, TransactionViewListQrFragment set it;
         * AttendFragment, AttendDenominationFragment, UnAttendFragment, TransactionResultFragment,
         * TransactionResultQrFragment clear it) and read on the **NanoHTTPD worker thread** in
         * `HTTPServer.handleCancelWhileBusy` and `handleIncomingRequest`. Without it the HTTP
         * thread can read an arbitrarily stale value and allow a cancel that should have been
         * refused — or refuse one that should have been allowed.
         *
         * It guards one thing: whether a transaction is far enough along that an ECR
         * `TransactionType 0` must NOT be able to cancel it.
         */
        @Volatile
        var appRunningProcess = false
		var uploadingReceipt = false
		var uploadingReceiptTimeStamp = 0L
		var blockSale = false

		val defaultAckCountdownSecond = 30
		var ackCountDownSecond = 30
		//var ackCountDownSecond = AtomicInteger(30)

		val className: String = ServiceHolder::class.java.name

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

		@JvmStatic
		fun getTerminalSerialNumber(): String {
			var result = terminalSerialNumber
			if (result.isNullOrEmpty()) {
				val prefs = Helper.getInstance().getPrefs()!!
				result = prefs.getString(SN_TAG, null)?.let {
					terminalSerialNumber = it
					it
				} ?: run {
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
				result = prefs.getString(DEVICE_MODEL_TAG, null)?.let {
					println("DEVICE_MODEL_TAG :: $it")
					deviceModel = it
					it
				} ?: run {
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

		/** Clears the in-memory cache so a later pref reset is actually observed. */
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

		@JvmStatic
		fun getAcquirerSetting(): AcquirerSettingModel {
			val config = merchantConfigInfo ?: getMerchantInfo()
			return when (config?.AcqCode?.uppercase()) {
				"GOBIZ" -> AcquirerSettingModel("Gobiz", true, false, 0, false, false)
				"PAYDEE" -> AcquirerSettingModel("Paydee", true, false, 0, false, false)
				"BSN_CARDZONE" -> AcquirerSettingModel("Bsn_Cardzone", true, true, R.raw.bsn_keystore, true, true)
				"BSN" -> AcquirerSettingModel("Bsn", false, true, R.raw.bsn_keystore, false, false)
				"PAYEX_GOBIZ" -> AcquirerSettingModel("Payex_Gobiz", true, false, 0, false, false)
				"FINEXUS" -> AcquirerSettingModel("Finexus", true, false, 0, false, false)
				else -> AcquirerSettingModel("", false, false, 0, false, false)
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
	}
}