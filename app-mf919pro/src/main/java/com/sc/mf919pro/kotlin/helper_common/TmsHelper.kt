package com.sc.mf919pro.kotlin.helper_common

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.library.terminal.UrlDownload
import com.sc.mf919pro.R
import crypto.Encryption
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.device.DeviceHelper
import utils.HexUtil
import com.sc.mf919pro.kotlin.helper_common.WebSocketClientSingleton
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919pro.kotlin.database.model.*
import com.sc.mf919pro.kotlin.database.repo.*
import env.EnvironmentManager
import env.EnvironmentVariables
import enums.EnumDateFormat
import enums.EnumLogFileName
import helpers.HelperLog
import tms.handlers.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.util.*

import tms.models.*
import java.text.SimpleDateFormat

object TmsHelper {
	private var firmID: String = ""
	private val className: String = (TmsHelper::class.qualifiedName).toString()

	private fun compareAndBatchInfo(log: HelperLog, mContext: Context, lastBatchNo: Int, lastInvoiceNo: Int): Boolean {
		//get current batchNo from db
		val dbBatchNo = IsoBatchInfoRepo.getBatchInfo(mContext, "batchNo", "visam")
		var batchNoOverride = dbBatchNo?.value ?: "000001"
		log.appendLine(className, "BatchNo from DB :: $batchNoOverride")
		log.appendLine(className, "BatchNo from MerchantConfig :: $lastBatchNo")

		val checkPrintReceipt = PrintReceiptRepo.getSingle(mContext, listOf(), listOf())
		if(checkPrintReceipt != null) {
			log.appendLine(className, "Terminal have Transaction aborting compare!!")
			return true
		}

		if (dbBatchNo != null) {
			val newDbBatchNo = Utils.atoi(dbBatchNo.value)
			val incrementalBatchNo = lastBatchNo + 1
			//compare the value
			if (incrementalBatchNo > newDbBatchNo) {
				val finalBatchNo = String.format("%06d", incrementalBatchNo)
				batchNoOverride = finalBatchNo

				//update
				log.appendLine(className, "Update visam BatchNo to :: $finalBatchNo")
				IsoBatchInfoRepo.updateBatchInfo(mContext, finalBatchNo, "batchNo", "visam")
				compareLastInvoiceNo(log, mContext, lastInvoiceNo)
			}
		}

		val dbBatchNoMccs = IsoBatchInfoRepo.getBatchInfo(mContext, "batchNo", "mccs")
		if (dbBatchNoMccs != null) {
			val newDbBatchNo = Utils.atoi(dbBatchNoMccs.value)
			val incrementalBatchNo = lastBatchNo + 1
			//compare the value
			if (incrementalBatchNo > newDbBatchNo) {
				val finalBatchNo = String.format("%06d", incrementalBatchNo)

				//update
				//IsoBatchInfoRepo.updateBatchInfo(mContext, finalBatchNo, "batchNo", "mccs")
				log.appendLine(className, "Update mccs BatchNo to :: $batchNoOverride")
				IsoBatchInfoRepo.updateBatchInfo(mContext, batchNoOverride, "batchNo", "mccs")
				compareLastInvoiceNo(log, mContext, lastInvoiceNo)
			}
		}

		return true
	}

	private fun compareLastInvoiceNo(log: HelperLog, mContext: Context, lastInvoiceNo: Int): Boolean {
		val dbInvoiceNo = IsoBatchInfoRepo.getBatchInfo(mContext, "invoiceNo", "pos")
		log.appendLine(className, "InvoiceNo from DB :: ${dbInvoiceNo?.value}")
		log.appendLine(className, "InvoiceNo from MerchantConfig :: $lastInvoiceNo")

		if (dbInvoiceNo != null) {
			val newDbInvoiceNo = Utils.atoi(dbInvoiceNo.value)
			val incrementalInvoiceNo = lastInvoiceNo + 1
			//compare the value
			if (incrementalInvoiceNo > newDbInvoiceNo) {
				val finalInvoiceNo = String.format("%06d", incrementalInvoiceNo)

				//update
				log.appendLine(className, "Update InvoiceNo to :: $finalInvoiceNo")
				IsoBatchInfoRepo.updateBatchInfo(mContext, finalInvoiceNo, "invoiceNo", "pos")
				IsoBatchInfoRepo.updateBatchInfo(mContext, finalInvoiceNo, "stan", "visam")
			}
		}

		return true
	}



	fun merchantConfigProductModel(
		product: String,
		modelObject: EWalletMerchantScan
	): DbModelProductList {
		return DbModelProductList(
			product,
			modelObject.ACQ_CODE ?: "",
			modelObject.ACQ_MID ?: "",
			modelObject.ACQ_TID ?: "",
			"",
			"",
			"",
			"",
			"",
			"false",
			"KIV",
			"KIV",
			"true",
			"",
			"",
			"false"
		)
	}

	fun merchantConfigProductModel(
		product: String,
		modelObject: ProductList
	): DbModelProductList {
		return DbModelProductList(
			product,
			modelObject.ACQ_CODE ?: "",
			modelObject.ACQ_MID ?: "",
			modelObject.ACQ_TID ?: "",
			modelObject.QR_PRODUCT_CODE ?: "",
			modelObject.PRODUCT_NAME ?: "",
			"",
			"",
			"",
			"false",
			"KIV",
			"KIV",
			"true",
			"",
			"",
			"false"
		)
	}

	fun merchantConfigProductModel(
		product: String,
		acqCode: String?,
		modelObject: EppSettingsListObjectModel
	): DbModelProductList {
		return DbModelProductList(
			product,
			acqCode ?: "",
			modelObject.Acq_MID ?: "",
			modelObject.Acq_TID ?: "",
			"",
			"",
			modelObject.PAYMENT_PRODUCT_ID ?: "",
			modelObject.TENURE ?: "",
			modelObject.TENURE_CODE ?: "",
			"true",
			"KIV",
			"KIV",
			"true",
			"",
			"",
			(modelObject.IS_TPA_ACCOUNT ?: false).toString()
		)
	}



	fun checkSettlementSummary(mContext: Context, modelSummary: DbModelSettlementSummary) {
		val dbData = SettlementSummaryRepo.getSingle(
			mContext,
			listOf("acq_code", "mid", "tid", "subtag"),
			arrayOf(modelSummary.acq_code, modelSummary.mid, modelSummary.tid, "visam-master")
		)

		if (dbData == null) {
			val tagList = listOf("txnTotal", "txnCount", "voidTxnTotal", "voidTxnCount", "cashOutTotal")
			val subtagList = listOf("visa", "master", "upi", "mccs")
			val subItr = subtagList.iterator()

			while (subItr.hasNext()) {
				val subtagObj = subItr.next()
				val itr = tagList.iterator()
				while (itr.hasNext()) {
					val tagObj = itr.next()
					modelSummary.tag = tagObj
					modelSummary.subtag = "visam-$subtagObj"
					SettlementSummaryRepo.insertToDb(mContext, modelSummary)
				}
			}
		}

		val mydebitData = SettlementSummaryRepo.getSelectiveData(
			mContext,
			listOf("acq_code", "mid", "tid", "subtag"),
			arrayOf(modelSummary.acq_code, modelSummary.mid, modelSummary.tid, "visam-mccs")
		)
		if (mydebitData.isEmpty()) {
			val tagList = listOf("txnTotal", "txnCount", "voidTxnTotal", "voidTxnCount", "cashOutTotal")

			val itr = tagList.iterator()
			while (itr.hasNext()) {
				val tagObj = itr.next()
				modelSummary.tag = tagObj
				modelSummary.subtag = "visam-mccs"
				SettlementSummaryRepo.insertToDb(mContext, modelSummary)
			}
		} else if (mydebitData.size != 5) {
			val tagList = listOf("txnTotal", "txnCount", "voidTxnTotal", "voidTxnCount", "cashOutTotal")

			val itr = tagList.iterator()
			while (itr.hasNext()) {
				val tagObj = itr.next()
				if(!mydebitData.any { it.tag == tagObj }) {
					modelSummary.tag = tagObj
					modelSummary.subtag = "visam-mccs"
					SettlementSummaryRepo.insertToDb(mContext, modelSummary)
				}
			}
		}
	}

	@JvmStatic
	// Around 200 call sites between the two apps, so the name stays put and delegates.
	fun checkIsConnectedWifi(mContext: Context): Boolean =
		helpers.HelperNetwork.isConnectedWifi(mContext)

	@JvmStatic

	fun uploadAllTerminalLog(log: HelperLog, mContext: Context, isUploadAll: Boolean = false): Boolean {
		if (!checkIsConnectedWifi(mContext)) {
			log.appendLine(className, "TMS UploadLog skipped - no WiFi")
			log.logToFile(EnumLogFileName.TerminaLog)
			return false
		}

		//var result = false
		log.appendLine(className, "TMS UploadLog [START] :: uploadAll=$isUploadAll")
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)

		// Sweep stale staging copies before starting. createTemporaryFile writes <name>_COPY.txt
		// into this same directory and it is deleted only after a successful upload, so a process
		// death mid-upload orphans it -- and on the next run EnumLogFileName.valueOf() throws for
		// "TerminaLog_COPY", so the walk below classifies the orphan as a backup file and uploads
		// it. Fixed names make it sweepable. Ported from Pro, which already had this.
		try {
			File("${ServiceHolder.getContext().filesDir}/Logs").listFiles { f ->
				f.isFile && f.name.endsWith("_COPY.txt")
			}?.forEach { stale ->
				log.appendLine(className, "Removing stale staging copy: ", stale.name)
				stale.delete()
			}
		} catch (e: Exception) {
			log.appendLine(className, "Stale copy sweep failed: ", e.toString())
		}

		File("${ServiceHolder.getContext().filesDir}/Logs").walkTopDown().forEach {
			if (it.isFile) {
				if (!checkIsConnectedWifi(mContext)) {
					log.appendLine(className, "TMS UploadLog [END] :: WiFi lost mid-upload")
					log.logToFile(EnumLogFileName.TerminaLogException)
					return false
				}
				log.appendLine(className, "Uploading Log -> ", it.name)
				var result: Boolean
				var isBackupFile = false
				lateinit var fileToUpload: File

				try {
					fileToUpload = File(it.toURI())

					try {
						EnumLogFileName.valueOf(fileToUpload.nameWithoutExtension)
						if (!isUploadAll) {
							// continue loop to skip this item
							return@forEach
						}
						isBackupFile = false
						val tempDir = File("${ServiceHolder.getContext().filesDir}/Logs")
						val oriFile: File = fileToUpload
						fileToUpload = utils.FileOps.createTemporaryFile(
							oriFile, tempDir, "${it.nameWithoutExtension}_COPY.txt"
						)
					} catch (e: IllegalArgumentException) {
						isBackupFile = true
					}

					val oldUploadLogHandler = OldUploadLogHandler(environmentManager)
					oldUploadLogHandler.invoke(log, ServiceHolder.getSqnNum(), ServiceHolder.getTerminalSerialNumber(), fileToUpload)

					log.appendLine(className, "Upload Log Success")
					result = true
				} catch (ex: Exception) {
					log.appendLine(className, "Upload Log Handler Exeception: ", ex.toString())
					result = false
				}

				if (isBackupFile && !result) {
					log.appendLine(className, "No Delete Log File: ", fileToUpload.name)
				} else {
					log.appendLine(className, "Deleting Log File: ", fileToUpload.name)
					fileToUpload.delete()
				}
				if (!result) {
					// upload fail exit function
					log.appendLine(className, "TMS UploadLog [END] :: aborted after a failed upload")
					log.logToFile(EnumLogFileName.TerminaLogException)
					return false
				}
			}
		}
		log.appendLine(className, "TMS UploadLog [END] :: all files uploaded")
		log.logToFile(EnumLogFileName.TerminaLog)
		return true
	}

	@JvmStatic
	fun sendDeviceInfo(log: HelperLog, mContext: Context): Boolean {
		var result = false
		log.appendLine(className, "TMS DeviceInfo [START]")
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		val deviceInfoHandler = DeviceInfoHandler(environmentManager)
		/*
		if (Utility.exist_file(ServiceHolder.getInternalFilesPaths() + "firmID.txt")) {
			val file = Utility.read_file(ServiceHolder.getInternalFilesPaths() + "firmID.txt")
			firmID = file[0]
		}
		* */

		try {
			val batteryCheck = Utils.checkBatteryStatus()
			val testCase: List<DeviceTestCaseList> = listOf(
				DeviceTestCaseList("BATTERYSTATUS", batteryCheck)
			)
			val terminalConfig = ServiceHolder.getTerminalConfig()
			val apiResp = deviceInfoHandler.invoke(
				log,
				ServiceHolder.getSqnNum(),
				ServiceHolder.getTerminalSerialNumber(),
				ServiceHolder.getDeviceModel(),
				mContext.getString(R.string.app_name),
				terminalConfig?.DEV_PROJECT ?: "",
				terminalConfig?.DEV_LOCATION ?: "",
				terminalConfig?.DEV_LANE_ID ?: "",
				firmID,
				ServiceHolder.getAppVersion(),
				ServiceHolder.getMcVersion(),
				"-",
				testCase
			)
			log.appendLine(className, "DeviceInfoHandler Response -> ", helpers.HelperText.oneLine(apiResp.toString()))
			result = true
			log.appendLine(className, "TMS DeviceInfo [END] :: RESP_CODE=${apiResp.RESP_CODE} " +
					"RESP_DESC=${apiResp.RESP_DESC}")
			log.logToFile(EnumLogFileName.TerminaLog)
		}catch (ex: Exception) {
			log.appendLine(className, "DeviceInfoHandler Response (Exception)", ex.toString())
			log.appendLine(className, "TMS DeviceInfo [END] :: failed")
			log.logToFile(EnumLogFileName.TerminaLogException)
		}

		return result
	}

	@JvmStatic
	fun getMerchantConfiguration(log: HelperLog, mContext: Context): Boolean {
		var result = false
		var respBatchNo = 0
		var respInvoiceNo = 0
		log.appendLine(className, "TMS Helper - Get Merchant Configuration")

		val dbMerchantConfig = ServiceHolder.getMerchantInfo()
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		val merchantConfigHandler = MerchantConfigHandler(environmentManager)

		try {
			val terminalConfig = ServiceHolder.getTerminalConfig()
			val apiResp = merchantConfigHandler.invoke(
				log,
				ServiceHolder.getSqnNum(),
				ServiceHolder.getTerminalSerialNumber(),
				ServiceHolder.getDeviceModel(),
				mContext.getString(R.string.app_name),
				terminalConfig?.DEV_PROJECT ?: "",
				terminalConfig?.DEV_LOCATION ?: "",
				"-",
				ServiceHolder.getMcVersion(),
			)
			ServiceHolder.clearMerchantInformation()
			ServiceHolder.blockSale = false
			log.appendLine(className, "MerchantConfigHandler Response :: ", apiResp.toString())

			val gson = Gson()
			val cardSetting = apiResp.CARD_SETTINGS
			var cardSettingsObjectModel = gson.fromJson("", CardSettingsObjectModel::class.java)
			if (cardSetting != null) {
				cardSettingsObjectModel = cardSetting::class.members.find { it.name == cardSetting.ACQ_CODE }?.call(cardSetting) as CardSettingsObjectModel?
			}

			//BSN_CARDZONE MIGRATION
			val cardAcqCode = cardSetting?.ACQ_CODE ?: ""
			if(cardAcqCode == AcquirerLogoDataEnum.BSN_CARDZONE.name) {
				log.appendLine(className, "Old Config :: ", dbMerchantConfig.toString())
				if(DbModelMerchantConfig.getSafeValue(dbMerchantConfig, "AcqCode") == AcquirerLogoDataEnum.BSN.name) {
					log.appendLine(className, "Doing BSN to BSN CZ Migration")
					val valueHM = HashMap<Any, Any>()
					val criteriaHM = HashMap<Any, Any>()
					criteriaHM["acq_code"] = AcquirerLogoDataEnum.BSN.name
					valueHM["acq_code"] = AcquirerLogoDataEnum.BSN_CARDZONE.name
					SettlementSummaryRepo.updateData(mContext, valueHM, criteriaHM)
				}
			}
			//BSN_CARDZONE MIGRATION

			var customizationJsonString = ""
			val customizationList = apiResp.Customization
			if (customizationList != null) {
				customizationJsonString = gson.toJson(customizationList)
			}

			var marketingJsonString = ""
			apiResp.Marketings?.let {
				marketingJsonString = gson.toJson(it)
			}

			var advertisementJsonString = ""
			apiResp.Advertisements?.let {
				advertisementJsonString = gson.toJson(it)
			}

			var acquirerLogoJsonString = ""
			apiResp.AcquirerLogo?.let {
				if(it.IS_REPLACE_ACQ_LOGO == true) {
					acquirerLogoJsonString = gson.toJson(it)
				}
			}

			var supportJsonString = ""
			apiResp.SUPPORT?.let {
				supportJsonString = gson.toJson(it)
			}

			val dbModel = DbModelMerchantConfig(
				apiResp.MERCHANT_NAME,
				apiResp.MERCHANT_ADDR,
				apiResp.AUTO_SETTLE_T1,
				apiResp.AUTO_SETTLE_T2,
				apiResp.AUTO_SETTLE_T3,
				apiResp.WAIT_CARD_MS,
				apiResp.QR_PAY_MID,
				apiResp.QR_PAY_TID,
				apiResp.MC_VER,
				cardSetting?.ACQ_CODE ?: "",
				cardSettingsObjectModel?.ACQ_MID ?: "",
				cardSettingsObjectModel?.ACQ_TID ?: "",
				cardSettingsObjectModel?.PRIMARY_HOST_IP ?: "",
				cardSettingsObjectModel?.PRIMARY_HOST_PORT ?: "",
				cardSettingsObjectModel?.PRIMARY_HOST_SSL ?: "0",
				cardSettingsObjectModel?.SECONDARY_HOST_IP ?: "",
				cardSettingsObjectModel?.SECONDARY_HOST_PORT ?: "",
				cardSettingsObjectModel?.SECONDARY_HOST_SSL ?: "0",
				cardSettingsObjectModel?.TPDU ?: "",
				cardSettingsObjectModel?.NII ?: "",
				cardSettingsObjectModel?.HOST_TIMEOUTMS ?: "",
				cardSettingsObjectModel?.LAST_SETTLEMENT_BATCH_NO ?: "",
				cardSettingsObjectModel?.LAST_STAN ?: "",
				cardSettingsObjectModel?.LAST_INVOICE_NO ?: "",
				customizationJsonString,
				marketingJsonString,
				advertisementJsonString,
				acquirerLogoJsonString,
				apiResp.QUICK_ACTION?.Action_1,
				apiResp.QUICK_ACTION?.Action_2,
				apiResp.QUICK_ACTION?.Action_3,
				apiResp.QUICK_ACTION?.Action_4,
				apiResp.SKIP_TXN_VALIDATION,
				(cardSettingsObjectModel?.IS_TPA_ACCOUNT ?: "false").toString(),
				apiResp.TPA_MERCHANT_LOGO_URL ?: "",
				apiResp.SC_MID ?: "",
				apiResp.SC_TID ?: "",
				supportJsonString,
				apiResp.IS_FOODLINK,
			)
			MerchantConfigurationRepo.truncateTable(mContext)
			MerchantConfigurationRepo.insertToDb(mContext, dbModel)
			log.appendLine(className, "Insert into MerchantConfiguration -> ", gson.toJson(dbModel))

			cardSettingsObjectModel?.LAST_SETTLEMENT_BATCH_NO?.let {
				respBatchNo = Utils.atoi(it)
			}
			cardSettingsObjectModel?.LAST_INVOICE_NO?.let {
				respInvoiceNo = Utils.atoi(it)
			}

			val certFileUrl = apiResp.CERT_FILE_URL
			if(!certFileUrl.isNullOrEmpty()){
				val fileUrl = certFileUrl
				val fileName = fileUrl.substringAfterLast('/')
				val nameWithoutExtension = fileName.substringBeforeLast('.')
				val parts = nameWithoutExtension.split('_')
				if(ServiceHolder.checkCertVersion(parts.last())) {
					var url: String = fileUrl
					val f = File(url)
					val file = f.name
					url = url.replace(file, "")
					val download = UrlDownload(file, url, ServiceHolder.getInternalFilesPaths() + "/terminal_keystore.bks")
					download.getFile()
					if (download.percentage == 100) {
						log.appendLine(className, "Success Download Network Certification :: ${parts.last().last()}")
						ServiceHolder.setCertVersion(parts.last())
					} else {
						log.appendLine(className, "Fail To Download Network Certification")
					}
				}
			}

			val productArray = arrayOf("CARD_SETTINGS", "EWALLET_MERCHANT_SCANS", "BNPL", "GENERATE_QR", "EPP", "MOTO_SETTINGS")
			//Truncate Table ProductList
			ProductListRepo.updateData(mContext, hashMapOf("IsActive" to "false"), hashMapOf())

			productArray.forEach { arrayItem ->
				if (arrayItem == "CARD_SETTINGS") {
					if (cardSetting != null) {
						val dbModelProductList = DbModelProductList(
							arrayItem, // ProductType
							cardSetting.ACQ_CODE ?: "",
							cardSettingsObjectModel?.ACQ_MID ?: "",
							cardSettingsObjectModel?.ACQ_TID ?: "",
							"", "", "", "", "", "true", "KIV", "KIV", "true", "", "",
							(cardSettingsObjectModel?.IS_TPA_ACCOUNT ?: false).toString()
						)
						ProductListRepo.addOrInsert(mContext, dbModelProductList)
						log.appendLine(className, "Insert into ProductList(Card Settings) -> ", gson.toJson(dbModelProductList))

						val dbModelSettlementSummary = DbModelSettlementSummary(
							cardSetting.ACQ_CODE ?: "",
							cardSettingsObjectModel?.ACQ_MID ?: "",
							cardSettingsObjectModel?.ACQ_TID ?: "",
							"", "", "0", "false"
						)
						checkSettlementSummary(mContext, dbModelSettlementSummary)
					}
					return@forEach
				} else if (arrayItem == "MOTO_SETTINGS") {
					val dataSetting = apiResp.MOTO_SETTINGS ?: return@forEach
					val dataSettingsObjectModel = dataSetting::class.members.find { it.name == dataSetting.ACQ_CODE }?.call(dataSetting) as CardSettingsObjectModel? ?: return@forEach

					if (dataSettingsObjectModel.ACQ_TID.equals(cardSettingsObjectModel.ACQ_TID)){
						log.appendLine(className, "Merge MOTO_SETTINGS[${dataSettingsObjectModel.ACQ_TID}] with CARD_SETTINGS[${cardSettingsObjectModel.ACQ_TID}]")
					} else {
						val dbModelProductList = DbModelProductList(
							"MOTO", // ProductType
							dataSetting.ACQ_CODE!!,
							dataSettingsObjectModel.ACQ_MID ?: "",
							dataSettingsObjectModel.ACQ_TID ?: "",
							"", "", "", "", "", "true", "KIV", "KIV", "true", "", "",
							(dataSettingsObjectModel.IS_TPA_ACCOUNT ?: false).toString()
						)
						ProductListRepo.addOrInsert(mContext, dbModelProductList)
						log.appendLine(className, "Insert into ProductList(MOTO SETTINGS) -> ", gson.toJson(dbModelProductList))

						val dbModelSettlementSummary = DbModelSettlementSummary(
							dataSetting.ACQ_CODE!!,
							dataSettingsObjectModel.ACQ_MID ?: "",
							dataSettingsObjectModel.ACQ_TID ?: "",
							"", "", "0", "false"
						)
						checkSettlementSummary(mContext, dbModelSettlementSummary)
					}
				} else {
					val itemObject = apiResp::class.members.find { it.name == arrayItem }
						?.call(apiResp) ?: return@forEach

					if (arrayItem == "EPP") {
						val isAcqList = itemObject::class.members.find { it.name == "ACQ_LIST" }
							?.call(itemObject)
						if (isAcqList != null) {
							val eppAcqObjectModel: List<EppAcqObjectModel> = isAcqList as List<EppAcqObjectModel>

							for (i in eppAcqObjectModel.indices) {
								val getRow: Any = eppAcqObjectModel[i]
								val jsonObject: JsonObject = Gson().toJsonTree(getRow).asJsonObject
								val acqListModel = Gson().fromJson(jsonObject, EppAcqObjectModel::class.java)

								if (acqListModel != null) {
									val isAcqSetting = acqListModel::class.members.find { it.name == "ACQ_SETTING" }
										?.call(acqListModel)

									if (isAcqSetting != null) {
										val eppSettingModel: List<EppSettingsListObjectModel> = isAcqSetting as List<EppSettingsListObjectModel>

										val itr = eppSettingModel.iterator()
										while (itr.hasNext()) {
											val obj = itr.next()
											val dbModelProductList = merchantConfigProductModel(
												arrayItem, acqListModel.ACQ_CODE, obj
											)
											ProductListRepo.addOrInsert(mContext, dbModelProductList)
											log.appendLine(className, "Insert into ProductList(EPP) -> ", gson.toJson(dbModelProductList))

											val dbModelSettlementSummary = DbModelSettlementSummary(
												acqListModel.ACQ_CODE ?: "",
												obj.Acq_MID ?: "",
												obj.Acq_TID ?: "",
												"", "", "0", "false"
											)

											val tempBatchNo = Utils.atoi(obj.LAST_SETTLEMENT_BATCH_NO)
											if (tempBatchNo < respBatchNo) respBatchNo = tempBatchNo
											checkSettlementSummary(mContext, dbModelSettlementSummary)
										}
									}
								}
							}
						}
						return@forEach
					} else if (arrayItem == "EWALLET_MERCHANT_SCANS") {
						// Product-Object Flow
						val dbModelProductList = merchantConfigProductModel(
							arrayItem, itemObject as EWalletMerchantScan
						)
						ProductListRepo.addOrInsert(mContext, dbModelProductList)
						log.appendLine(className, "Insert into ProductList(Ewallet) -> ", gson.toJson(dbModelProductList))
					} else {
						// Product-List flow
						val isProductList = itemObject::class.members.find { it.name == "PRODUCT_LIST" }
							?.call(itemObject)
						if (isProductList != null) {
							val qrProductConObjectModel: List<ProductList> = isProductList as List<ProductList>

							val itr = qrProductConObjectModel.iterator()
							while (itr.hasNext()) {
								val obj = itr.next()
								val dbModelProductList = merchantConfigProductModel(
									arrayItem, obj
								)
								ProductListRepo.addOrInsert(mContext, dbModelProductList)
								log.appendLine(className, "Insert into ProductList(Others) -> ", gson.toJson(dbModelProductList))
							}
							return@forEach
						}
					}
				}
			}

			compareAndBatchInfo(log, mContext, respBatchNo, respInvoiceNo)
			ProductListRepo.deleteInActiveProduct(mContext)
			SettlementSummaryRepo.deleteSettlementByProductList(mContext)
			apiResp.MC_VER?.let { ServiceHolder.setMcVersion(it) }
			result = true
		} catch (ex: Exception) {
			log.appendLine(className, "MerchantConfigHandler Response (Exception)", ex.toString())
			try {
				val errorResponse = Gson().fromJson(ex.message, MerchantConfigResp::class.java)
				errorResponse?.let {
					if(it.RESP_CODE == "9000"){
						ServiceHolder.clearProductListing()
						ServiceHolder.blockSale = true
					}
				}
			} catch (jsonEx: Exception) {
				log.appendLine(className, "Json Exception in Error -> ", jsonEx.toString())
			}
		}

		log.logToFile(EnumLogFileName.TerminaLog)
		return result
	}

	@JvmStatic
	fun getTerminalConfiguration(log: HelperLog, mContext: Context): Boolean {
		var result = false
		log.appendLine(className, "TMS Helper - Terminal Configuration")
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		val terminalConfigHandler = TerminalConfigHandler(environmentManager)

		try {
			val terminalConfig = ServiceHolder.getTerminalConfig()
			val terminalConfigRes = terminalConfigHandler.invoke(
				log,
				ServiceHolder.getSqnNum(),
				ServiceHolder.getTerminalSerialNumber(),
				ServiceHolder.getDeviceModel(),
				mContext.getString(R.string.app_name),
				terminalConfig?.DEV_PROJECT ?: "",
				terminalConfig?.DEV_LOCATION ?: "",
				ServiceHolder.getMcVersion()
			)
			ServiceHolder.clearTerminalConfigInformation()

			val gson = Gson()
			var ewProductListJsonString = ""
			val ewProductList = terminalConfigRes.EWALLET_PRODUCT_LIST
			if (ewProductList != null) {
				ewProductListJsonString = gson.toJson(ewProductList)
			}

			val merchantInfoSize = terminalConfigRes.RECEIPT_MERCHANT_INFO_SIZE ?: "0"
			val txnInfoSize = terminalConfigRes.RECEIPT_TXN_INFO_SIZE ?: "0"

			val dbModel = DbModelTerminalConfig(
				terminalConfigRes.DEV_PROJECT,
				terminalConfigRes.DEV_LOCATION,
				terminalConfigRes.DEV_LANE_ID,
				terminalConfigRes.CONTACT.toString(),
				terminalConfigRes.CONTACTLESS.toString(),
				terminalConfigRes.MAGSTRIPE.toString(),
				terminalConfigRes.QR_PAY.toString(),
				terminalConfigRes.FORCE_PIN.toString(),
				terminalConfigRes.ISO_PRINT.toString(),
				terminalConfigRes.RECEIPT_PRINT.toString(),
				terminalConfigRes.OPT_IN.toString(),
				terminalConfigRes.SALECOM_ONLINE.toString(),
				terminalConfigRes.AUTO_SETTLEMENT.toString(),
				terminalConfigRes.SALE.toString(),
				terminalConfigRes.VOID.toString(),
				terminalConfigRes.PREAUTH.toString(),
				terminalConfigRes.SALECOM.toString(),
				terminalConfigRes.REFUND.toString(),
				terminalConfigRes.TMS_RECEIPT.toString(),
				terminalConfigRes.TMS_ENABLE.toString(),
				terminalConfigRes.POWERED_BY,
				terminalConfigRes.POWERED_BY_BW,
				terminalConfigRes.HOME_LOGO,
				terminalConfigRes.MC_VER,
				terminalConfigRes.VOID_WITH_PIN.toString(),
				terminalConfigRes.MOTO.toString(),
				terminalConfigRes.TIMEOUT_SECONDS.toString(),
				terminalConfigRes.SETTLEMENT_WITH_PIN.toString(),
				terminalConfigRes.REMOTE_DOWNLOAD_BSN_KEY.toString(),
				ewProductListJsonString,
				terminalConfigRes.FORCE_LOCK_HOME.toString(),
				terminalConfigRes.FORCE_SETTLEMENT.toString(),
				terminalConfigRes.UNATTENDED_MODE.toString(),
				terminalConfigRes.CABLE_CONNECTION_METHOD.toString(),
				terminalConfigRes.FORCE_SETTLEMENT_DAILY.toString(),
				terminalConfigRes.CASHOUT.toString(),
				terminalConfigRes.ISO_WEBSOCKET.toString(),
				terminalConfigRes.WEBSOCKET.toString(),
				terminalConfigRes.SALES_CARD.toString(),
				terminalConfigRes.SALES_EWALLET.toString(),
				terminalConfigRes.DENOMINATION.toString(),
				merchantInfoSize,
				txnInfoSize,
				terminalConfigRes.BYPASS_PIN.toString(),
			)
			TerminalConfigurationRepo.truncateTable(mContext)
			TerminalConfigurationRepo.insertToDb(mContext, dbModel)
			terminalConfigRes.MC_VER?.let { ServiceHolder.setMcVersion(it) }
			log.appendLine(className, "TerminalConfigHandler Response -> ", terminalConfigRes.toString())

			HTTPServer.startServeCable()
			HTTPServer.startWebSocketServer()
			val enableWebSocket = DbModelTerminalConfig.getBooleanValue(dbModel, "WEBSOCKET")
			if(enableWebSocket) {
				val wsURI = "${environmentManager.get(EnvironmentVariables::socketHandlerUrl)}?sn=${ServiceHolder.getTerminalSerialNumber()}"
				WebSocketClientSingleton.connect(wsURI)
			} else {
				WebSocketClientSingleton.disconnect()
			}

			if (ServiceHolder.requireDownloadLogo) {
				log.appendLine(className, "Downloading Terminal Logo...")
				println("Downloading Merchant Logo")

				val internalPath = ServiceHolder.getInternalFilesPaths()

				downloadLogo(
					log,
					mContext,
					terminalConfigRes.POWERED_BY,
					"$internalPath/powerLogo.png",
					"Fail To Download PoweredBy Logo",
					"Fail to download Powered Logo"
				)

				downloadLogo(
					log,
					mContext,
					terminalConfigRes.POWERED_BY_BW,
					"$internalPath/powerBWLogo.png",
					"Fail To Download PoweredBy BW Logo",
					"Fail to download Power BW Logo"
				)

				downloadLogo(
					log,
					mContext,
					terminalConfigRes.HOME_LOGO,
					"$internalPath/homeLogo.png",
					"Fail To Download Home Logo",
					"Fail to download Home Logo"
				)

				ServiceHolder.requireDownloadLogo = false
			}
			if (terminalConfigRes.FORCE_LOCK_HOME == 1) {
				MfHelper.lockStatusBarAndNavigation(true)
			} else {
				MfHelper.lockStatusBarAndNavigation(false)
			}

			DeviceHelper.resetAID()
			result = true
		}catch (ex: Exception){
			log.appendLine(className, "TerminalConfigHandler Response (Exception)", ex.toString())
		}
		log.logToFile(EnumLogFileName.TerminaLog)
		return result
	}

	@JvmStatic
	fun getInjectionKey(log: HelperLog, mContext: Context): Boolean {
		var result = false
		log.appendLine(className, "TMS InjectionKey [START]")
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		val injectionKeyHandler = InjectionKeyHandler(environmentManager)

		try{
			val terminalConfig = ServiceHolder.getTerminalConfig()
			val injectionKeyRes = injectionKeyHandler.invoke(
				log,
				ServiceHolder.getSqnNum(),
				ServiceHolder.getTerminalSerialNumber(),
				ServiceHolder.getDeviceModel(),
				mContext.getString(R.string.app_name),
				terminalConfig?.DEV_PROJECT ?: "",
				terminalConfig?.DEV_LOCATION ?: "",
				"-"
			)
			val merchantConfig = ServiceHolder.getMerchantInfo()

			val keyModelObject = injectionKeyRes.KEY
			if (keyModelObject != null) {
				val keyAcqCode = keyModelObject.ACQ_CODE
				if (keyAcqCode != null) {
					val acqCode = keyAcqCode.substring(0, 1) + keyAcqCode.substring(1).lowercase()
					val getInjectKeyTemp = keyModelObject::class.members.find { it.name == "${keyAcqCode}_KEY" }?.call(keyModelObject)

					if (getInjectKeyTemp != null) {
						if (keyAcqCode.uppercase() == "BSN_CARDZONE") {
							//TODO Remove for temporary
							//SecureDataRepo.truncateTable(mContext)
							val acqKey = getInjectKeyTemp as BsnCardZoneInjectionKeyModel
							val strSecureTleScheme = "visam-tle$acqCode-${merchantConfig?.AcqMid}"

							val leftByte = HexUtil.hexStringToByte(acqKey.TMK_Key_Left)
							SecureDataRepo.setSecureData(mContext, "eKeyLeft", strSecureTleScheme, leftByte, leftByte.size)

							val rightByte = HexUtil.hexStringToByte(acqKey.TMK_Key_Right)
							SecureDataRepo.setSecureData(mContext, "eKeyRight", strSecureTleScheme, rightByte, rightByte.size)

						} else if (keyAcqCode == "BSN") {
							val acqKey = getInjectKeyTemp as List<BsnInjectionKeyModel>

							for (i in acqKey.indices) {
								val getRow: Any = acqKey[i]
								val jsonObject: JsonObject = Gson().toJsonTree(getRow).asJsonObject
								val acqKeyModel = Gson().fromJson(
									jsonObject, BsnInjectionKeyModel::class.java
								)

								//Check key to prevent override KSN
								val oldKeyCriteria = listOf("tag", "subtag")
								val oldKeyCriteriaValue = arrayOf(
									"eWekKey",
									"visam-tle$acqCode-${acqKeyModel.BSN_MID}"
								)
								val oldKey: DbModelSecureData? = SecureDataRepo.getSingle(
									mContext, oldKeyCriteria, oldKeyCriteriaValue
								)
								if (oldKey?.value == acqKeyModel.TLE) break

								if (i == 0) SecureDataRepo.truncateTable(mContext)
								val insertTMK = DbModelSecureData(
									"eWekKey",
									"visam-tle$acqCode-${acqKeyModel.BSN_MID}",
									acqKeyModel.TLE
								)
								SecureDataRepo.insertToDb(mContext, insertTMK)

								val insertTMKId = DbModelSecureData(
									"eWakKey",
									"visam-tle$acqCode-${acqKeyModel.BSN_MID}",
									acqKeyModel.MEK
								)
								SecureDataRepo.insertToDb(mContext, insertTMKId)

								val insertTAK = DbModelSecureData(
									"eTpkKey",
									"visam-tle$acqCode-${acqKeyModel.BSN_MID}",
									acqKeyModel.PIN
								)
								SecureDataRepo.insertToDb(mContext, insertTAK)

								IsoBatchInfoRepo.updateBatchInfo(
									mContext, acqKeyModel.KSN, "ksn", "visam"
								)
								IsoBatchInfoRepo.updateBatchInfo(
									mContext, acqKeyModel.PIN_KSN, "ksn", "visam-pin"
								)

								val criteriaHM = hashMapOf<Any, Any>(
									"AcqMid" to acqKeyModel.BSN_MID,
									"AcqTid" to acqKeyModel.BSN_TID
								)

								val valueHM = hashMapOf<Any, Any>(
									"Ksn" to acqKeyModel.KSN,
									"PinKsn" to acqKeyModel.PIN_KSN
								)

								ProductListRepo.updateData(mContext, valueHM, criteriaHM)

							}
						} else {
							//TODO TEMPORARY REMOVE
							//SecureDataRepo.truncateTable(mContext)
							val acqKey = getInjectKeyTemp as InjectionKeyModelKeyAcquirerModel
							/*val insertTMK = DbModelSecureData(
								"eTmkKey",
								"visam-tle$acqCode-${merchantConfig?.AcqMid}",
								acqKey.TMK ?: ""
							)
							SecureDataRepo.insertToDb(mContext, insertTMK)*/
							/*val insertTMKId = DbModelSecureData(
								"eTmkId",
								"visam-tle$acqCode-${merchantConfig?.AcqMid}",
								acqKey.TMKId ?: ""
							)
							SecureDataRepo.insertToDb(mContext, insertTMKId)*/
							/*val insertTAK = DbModelSecureData(
								"eTakKey",
								"visam-tle$acqCode-${merchantConfig?.AcqMid}",
								acqKey.TAK ?: ""
							)
							SecureDataRepo.insertToDb(mContext, insertTAK)*/
							SecureDataRepo.updateOrInsert(mContext, "eTmkKey", "visam-tle$acqCode-${merchantConfig?.AcqMid}", acqKey.TMK)
							SecureDataRepo.updateOrInsert(mContext, "eTmkId", "visam-tle$acqCode-${merchantConfig?.AcqMid}", acqKey.TMKId)
							SecureDataRepo.updateOrInsert(mContext, "eTakKey", "visam-tle$acqCode-${merchantConfig?.AcqMid}", acqKey.TAK)
						}

						DeviceHelper.resetAID()
						DeviceHelper.resetCAPK()
					}
				}
			}
			// NOT injectionKeyRes.toString(): that data class carries TMK, TAK, MEK, PIN, TLE and
			// both TMK_Key_Left/Right for every acquirer, so stringifying it wrote the terminal's
			// entire key set into TerminaLog.txt -- the file uploadAllTerminalLog ships to TMS.
			// Log only what identifies the exchange; TMKId and KCV elsewhere already let you verify
			// which key loaded.
			log.appendLine(className, "InjectionKeyHandler Response -> ",
				"SEQ_NO=${injectionKeyRes?.SEQ_NO} RESP_CODE=${injectionKeyRes?.RESP_CODE} " +
				"RESP_DESC=${injectionKeyRes?.RESP_DESC} TMKId=${injectionKeyRes?.TMKId} " +
				"MC_VER=${injectionKeyRes?.MC_VER} acq=${injectionKeyRes?.KEY?.ACQ_CODE} " +
				"[key material omitted]")
			result = true
			// Boundary line follows the same rule as the response line above: identify the
			// exchange, never the key.
			log.appendLine(className, "TMS InjectionKey [END] :: RESP_CODE=${injectionKeyRes?.RESP_CODE} " +
					"acq=${injectionKeyRes?.KEY?.ACQ_CODE}")
			log.logToFile(EnumLogFileName.TerminaLog)
		}catch (ex: Exception) {
			// NOT ex.toString(). InjectionKeyHandler throws IOException(resp) carrying the raw
			// response body, and on this endpoint that body is every acquirer's key material -
			// the same leak that was just closed on the success line above (its 99999 fallback
			// wraps the same body inside RESP_DESC, so RESP_DESC is not safe to log either).
			// Only the exception type and the response code are recorded.
			log.appendLine(className, "InjectionKeyHandler Response (Exception)",
				"${ex.javaClass.simpleName} RESP_CODE=${extractRespCode(ex.message)} [response body omitted]")
			log.appendLine(className, "TMS InjectionKey [END] :: failed")
			log.logToFile(EnumLogFileName.TerminaLogException)
		}
		return result
	}

	/**
	 * Pull RESP_CODE out of a JSON error body without putting the body in the log. Used on the
	 * key-injection error path, where the body cannot be logged at all.
	 */
	private fun extractRespCode(message: String?): String =
		Regex("\"RESP_CODE\"\\s*:\\s*\"([^\"]*)\"").find(message ?: "")?.groupValues?.get(1) ?: "-"

	@JvmStatic
	fun checkTerminalPIN(log: HelperLog, mContext: Context, pin: String, type: String): Boolean {
		var result = false
		log.appendLine(className, "TMS TerminalPIN [START] :: type=$type")
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)

		try{
			val encodedPIN = generateEncodedPIN(pin)
			val apiResp: TerminalPINResponseModel

			if (type == "settlement") {
				val terminalPinHandler = CheckSettlementPinHandler(environmentManager)
				apiResp = terminalPinHandler.invoke(log, ServiceHolder.getSqnNum(), ServiceHolder.getTerminalSerialNumber(), encodedPIN)
			} else {
				val terminalPinHandler = CheckVoidPinHandler(environmentManager)
				apiResp = terminalPinHandler.invoke(log, ServiceHolder.getSqnNum(), ServiceHolder.getTerminalSerialNumber(), encodedPIN)
			}

			// The entered PIN and its encoded form are never logged - only the outcome.
			log.appendLine(className, "checkTerminalPIN Response", helpers.HelperText.oneLine(apiResp.toString()))
			result = true
			log.appendLine(className, "TMS TerminalPIN [END] :: RESP_CODE=${apiResp.RESP_CODE} " +
					"RESULT=${apiResp.RESULT}")
			log.logToFile(EnumLogFileName.TerminaLog)
		} catch (ex: Exception){
			log.appendLine(className, "checkTerminalPIN Response (Exception)", ex.toString())
			log.appendLine(className, "TMS TerminalPIN [END] :: failed")
			log.logToFile(EnumLogFileName.TerminaLogException)
		}

		return result
	}

	@JvmStatic
	fun generateEncodedPIN(pin: String): String {
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		return crypto.TerminalPin.encodePin(
			pin, environmentManager.get(EnvironmentVariables::serverHashKey)
		)
	}

	@JvmStatic

	fun checkServerDateTime(log: HelperLog, mContext: Context) {
		log.appendLine(className, "TMS Ping [START]")
		try{
			val tmsPingHandler = TmsPingHandler(EnvironmentManager(Helper.getInstance().getPrefs()!!))
			val pingResp = tmsPingHandler.invoke(log)
			log.appendLine(className, "TmsPingHandler Response -> ", helpers.HelperText.oneLine(pingResp.toString()))

			val systemDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSXXX", Locale.getDefault())
			val systemDate = systemDateFormat.parse(pingResp.systemtime!!)

			val df = SimpleDateFormat(EnumDateFormat.yyyyMMddHHmmss_XDot.dateFormat, Locale.getDefault())
			val newDateTime = df.format(systemDate!!)
			DeviceHelper.updateSystemDatetime(newDateTime)
			log.appendLine(className, "TMS Ping [END] :: system datetime set to $newDateTime")
			log.logToFile(EnumLogFileName.TerminaLog)
		}catch (ex: Exception){
			ex.printStackTrace()
			log.appendLine(className, "TmsPingHandler (Exception) -> ", ex.toString())
			log.appendLine(className, "TMS Ping [END] :: failed")
			log.logToFile(EnumLogFileName.TerminaLogException)
		}
	}

	@JvmStatic
	fun getDenominationPrice(log: HelperLog, mContext: Context): Boolean {
		var result = false
		log.appendLine(className, "TMS DenominationPriceList [START]")
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		val denominationPriceListHandler = DenominationPriceListHandler(environmentManager)
		val dbMerchantConfig = ServiceHolder.getMerchantInfo()

		try{
			val denominationPriceResp = denominationPriceListHandler.invoke(
				log,
				ServiceHolder.getSqnNum(),
				ServiceHolder.getTerminalSerialNumber(),
				DbModelMerchantConfig.getSafeValue(dbMerchantConfig, "ScMid"),
				DbModelMerchantConfig.getSafeValue(dbMerchantConfig, "ScTid")
			)
			log.appendLine(className, "DenominationPriceListHandler Response", helpers.HelperText.oneLine(denominationPriceResp.toString()))
			val gson = Gson()

			val maintenanceSchedule = gson.toJson(denominationPriceResp.MAINTENANCE_SCHEDULE ?: "")
			denominationPriceResp.REMARK?.let {
				ServiceHolder.remarkFooter = it
			}
			denominationPriceResp.PACKAGE_LIST?.let {
				val itr = it.iterator()
				while (itr.hasNext()) {
					val obj = itr.next()
					val jsonDenominationList = gson.toJson(obj)
					val denominationListInsert = DbModelDenominationListInsert(
						obj.PACKAGE_ID ?: "",
						obj.DESCRIPTION ?: "",
						obj.AMOUNT ?: "",
						obj.REF_1 ?: "",
						obj.REF_2 ?: "",
						obj.REF_3 ?: "",
						obj.REF_4 ?: "",
						obj.REF_5 ?: "",
						denominationPriceResp.REMARK ?: "",
						maintenanceSchedule,
						denominationPriceResp.DISPLAY_MENU_TYPE ?: "",
					)
					DenominationListRepo.updateOrInsert(mContext, denominationListInsert)
					log.appendLine(className, "Insert into DenominationList :: $jsonDenominationList")
				}
			}
			result = true
			log.appendLine(className, "TMS DenominationPriceList [END] :: RESP_CODE=${denominationPriceResp.RESP_CODE} " +
					"packages=${denominationPriceResp.PACKAGE_LIST?.size ?: 0}")
			log.logToFile(EnumLogFileName.TerminaLog)
		}catch (ex: Exception){
			log.appendLine(className, "DenominationPriceListHandler Response (Exception)", ex.toString())
			log.appendLine(className, "TMS DenominationPriceList [END] :: failed")
			log.logToFile(EnumLogFileName.TerminaLogException)
		}
		return result
	}

	@JvmStatic
	fun sendWriteLog(log: HelperLog, action: String) {
		log.appendLine(className, "TMS WriteLog [START] :: action=$action")
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		val writeLogHandler = WriteLogHandler(environmentManager)

		try{
			val writeLogResp = writeLogHandler.invoke(
				log,
				ServiceHolder.getTerminalSerialNumber(),
				action
			)
			log.appendLine(className, "TMS WriteLog [END] :: RESP_CODE=${writeLogResp.RESP_CODE} " +
					"RESP_DESC=${writeLogResp.RESP_DESC}")
			log.logToFile(EnumLogFileName.TerminaLog)
		}catch (ex: Exception){
			log.appendLine(className, "WriteLogHandler Response (Exception)", ex.toString())
			log.appendLine(className, "TMS WriteLog [END] :: failed")
			log.logToFile(EnumLogFileName.TerminaLogException)
		}
	}

	@JvmStatic
	fun paperRollRequest(log: HelperLog, action: String): Boolean {
		var result = false
		log.appendLine(className, "TMS Helper - Request Paper Roll")
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		val paperRollRequestHandler = PaperRollRequestHandler(environmentManager)

		try{
			val paperRollRequestResp = paperRollRequestHandler.invoke(
				log,
				ServiceHolder.getTerminalSerialNumber(),
				action
			)
			result = true
			log.appendLine(className, "PaperRollRequestHandler Response", paperRollRequestResp.toString())
		} catch (ex: Exception) {
			result = false
			log.appendLine(className, "PaperRollRequestHandler Response (Exception)", ex.toString())
		} finally {
			log.logToFile(EnumLogFileName.TerminaLog)
		}
		return result
	}

	private fun downloadLogo(
		log: HelperLog,
		mContext: Context,
		logoUrl: String?,
		localPath: String,
		failLog: String,
		failToast: String
	) {
		logoUrl?.takeIf { it.isNotBlank() }?.let { url ->
			val fileName = File(url).name
			val baseUrl = url.removeSuffix(fileName)

			val download = UrlDownload(fileName, baseUrl, localPath)
			download.getFile()

			if (download.percentage != 100) {
				log.appendLine(className, failLog)
				Toast.makeText(mContext, failToast, Toast.LENGTH_SHORT).show()
			}
		} ?: File(localPath).delete()
	}

	@JvmStatic
	fun foodLinkVerificationRequest(
		log: HelperLog,
		correlationRef: String,
		systemMID: String,
		systemTID: String,
		phoneCountryCode: String,
		phoneNo: String,
		txnAmt: String,): Boolean
	{
		var result = false
		log.appendLine(className, "TMS Helper - FoodLink Verification")
		val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
		val foodLinkVerificationHandler = FoodLinkVerificationHandler(environmentManager)

		try{
			val foodLinkVerificationResp = foodLinkVerificationHandler.invoke(
				log,
				correlationRef,
				systemMID,
				systemTID,
				phoneCountryCode,
				phoneNo,
				txnAmt
			)
			result = true
			log.appendLine(className, "FoodLinkVerificationHandler Response", foodLinkVerificationResp.toString())
		} catch (ex: Exception) {
			result = false
			log.appendLine(className, "FoodLinkVerificationHandler Response (Exception)", ex.toString())
		} finally {
			log.logToFile(EnumLogFileName.TerminaLog)
		}
		return result
	}
}