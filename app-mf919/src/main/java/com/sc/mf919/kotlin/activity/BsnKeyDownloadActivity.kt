package com.sc.mf919.kotlin.activity

import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.library.terminal.Cryptography
import com.library.terminal.Utility
import com.morefun.yapi.ServiceResult
import com.morefun.yapi.card.cpu.CPUCardHandler
import com.morefun.yapi.device.reader.icc.*
import com.sc.mf919.R
import com.sc.mf919.java.activity.Encryption
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.device.DeviceHelper
import com.sc.mf919.java.utils.*
import utils.*
import utils.Util.IsoGenerate
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelSecureData
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.database.repo.SecureDataRepo
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.*
import org.apache.commons.lang3.math.NumberUtils
import java.lang.Runnable

class BsnKeyDownloadActivity: ActivityBase() {
	var cardSlot = 0
	lateinit var resultTV: TextView
	lateinit var submitButton: Button
	lateinit var cpuCardHandler: CPUCardHandler
	lateinit var merchantInfo: DbModelMerchantConfig
	lateinit var productList: List<DbModelProductList>
	lateinit var passwodEntered: String
	lateinit var sn: String

	//BSN Configuration
	private val default_enc = "01"
	private val default_mac = "04"
	private val default_application_id = "0002"
	private val default_key_type = "03"
	private val default_key_size = "02"
	private val default_delimiter = "01"

	//Card Data
	private var pin_verification_mode = ""
	private var pin_block = ""
	private var counter = ""
	private var encrypt_method = ""
	private val msgEnc = "00000000000000000000000000000000"

	//downloadedData
	private val secureKey = "aforapplebforboy"
	private var dataBundle = Bundle()

	// For Running Task
	var startJob = false
	var jobRunning = false
	var runCount = 100
	val maxCount = 50

	private var helperlogClassName:String = ""
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_bsn_key_download)
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				if (this@BsnKeyDownloadActivity::helperLog.isInitialized) {
					helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving BSN key download")
					helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> HomeScreen")
					helperLog.logToFile(EnumLogFileName.TerminaLog)
				}
				val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
				val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
				newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
				startActivity(newIntent)
			}
		})
		helperlogClassName = this::class.qualifiedName.toString()
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"BSN Key Download Activity"
		)
		helperLog.appendLine(helperLogClassName, "BSN key download screen opened")

		resultTV = findViewById<EditText>(R.id.resultTV)
		submitButton = findViewById(R.id.submit_button)
		merchantInfo = ServiceHolder.getMerchantInfo()!!
		productList= ProductListRepo.getSelectedProductEnhanced(
			applicationContext,
			ArrayList(listOf("AcqCode")),
			arrayOf("BSN")
		)
		sn = ServiceHolder.getTerminalSerialNumber()
	}

	private fun showResult(textView: TextView, text: String) {
		Log.d("Logging Result", text)
		runOnUiThread(Runnable {
            textView.append("${text.trimIndent()} \r\n")
		})
	}

	fun btnDownloadKey(view: View?) {
		val keyInPassword = findViewById<EditText>(R.id.input_pass)
		passwodEntered = keyInPassword.text.toString()
		helperLog.appendLine(helperLogClassName, "Selected :: SUBMIT [BSN KEY DOWNLOAD]")
		if(jobRunning){
			helperLog.appendLine(helperLogClassName, "User Cancel :: key download in progress cancelled by user")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			cancelDownload()
			return
		}

		if(passwodEntered.isEmpty()){
			helperLog.appendLine(helperLogClassName, "REJECT :: password not entered")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			Toast.makeText(this, "Please Enter Password", Toast.LENGTH_SHORT).show()
			return
		}

		if(productList.isEmpty()){
			helperLog.appendLine(helperLogClassName, "REJECT :: no BSN MID/TID configured")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			Toast.makeText(this, "MID/TID Not Found", Toast.LENGTH_SHORT).show()
			return
		}

		helperLog.appendLine(helperLogClassName, "Validation passed :: awaiting card for ${productList.size} BSN account(s)")
		helperLog.logToFile(EnumLogFileName.TerminaLog)

		SecureDataRepo.truncateTable(this@BsnKeyDownloadActivity)
		resultTV.text = ""
		showResult(resultTV, "Please insert card")

		prepareForSubmit(
			arrayOf(
				IccCardType.CPUCARD,
				IccCardType.M1CARD,
				IccCardType.M0CCARD,
				IccCardType.M0Ev1CARD
			)
		)
	}

	fun cancelDownload() {
		runCount = 100
		startJob = false
	}


	fun prepareForSubmit(cardTypeList: Array<String>) {
		try {
			val icReader: IccCardReader = DeviceHelper.getIccCardReader(IccReaderSlot.ICSlOT1)
			val rfReader = DeviceHelper.getIccCardReader(IccReaderSlot.RFSlOT)

			val listener: OnSearchIccCardListener.Stub = object : OnSearchIccCardListener.Stub() {
				@Throws(RemoteException::class)
				override fun onSearchResult(retCode: Int, bundle: Bundle) {
					icReader.stopSearch()
					rfReader.stopSearch()
					if (ServiceResult.Success == retCode) {
						val cardType = bundle.getString(ICCSearchResult.CARDTYPE)
						//showResult(resultTV, "cardType:$cardType")

						if (IccCardType.CPUCARD == cardType) {
							val slot = bundle.getInt(ICCSearchResult.CARDOTHER)
							val uid = bundle.getString(ICCSearchResult.M1SN)
							//showResult(resultTV, "uid:$uid")
							cardSlot = slot
							lifecycleScope.launch {
								withContext(Dispatchers.Default) {
									startJob = true
									for(index in productList.indices){
										if(!startJob) return@withContext
										jobRunning = true
										runOnUiThread(Runnable {
											submitButton.text = "Cancel"
										})
										val log = helpers.HelperLog(
											HelperCommon.getSession(),
											TmsHelper.checkIsConnectedWifi(applicationContext),
											Utils.getIPAddress(),
											"BsnKeyDownloadActivity",
											BsnKeyDownloadActivity::class.java.simpleName,
											BsnKeyDownloadActivity::class.java.simpleName,
										)
										val currentProduct = productList[index]
										log.appendLine(helperlogClassName, "-----------------BSN Key Download [START]-------------------->")
										log.appendLine(helperlogClassName, "MID -> ", currentProduct.AcqMid)
										log.appendLine(helperlogClassName, "TID -> ", currentProduct.AcqTid)
										exchangeApdu(slot, currentProduct.AcqMid, currentProduct.AcqTid, log)
										log.appendLine(helperlogClassName, "-----------------BSN Key Download [END]-------------------->")
										log.logToFile(EnumLogFileName.TerminaLog)
									}
									startJob = false
								}
							}
						}
					} else {
						if (this@BsnKeyDownloadActivity::helperLog.isInitialized) {
							helperLog.appendLine(helperLogClassName, "REJECT :: card search failed, retCode=$retCode")
							helperLog.logToFile(EnumLogFileName.TerminaLogException)
						}
						showResult(resultTV, "result:$retCode")
					}
				}
			}
			icReader.searchCard(listener, 10, cardTypeList)
			rfReader.searchCard(listener, 10, cardTypeList)
		}catch (e: Exception) {
			e.printStackTrace()
		}
	}

	suspend fun exchangeApdu(slot: Int, mid: String, tid: String, log: helpers.HelperLog) {
		val cardReader = DeviceHelper.getIccCardReader(slot)
		cpuCardHandler = DeviceHelper.getCpuCardHandler(cardReader)
		var proceedISO = false

		val coroutineScopes = CoroutineScope(Dispatchers.IO).launch {
			try {
				var aResult = ""
				if (cpuCardHandler == null) {
					showResult(resultTV, "CpuCardHandler is null!")
					cpuCardHandler.setPowerOff()
					return@launch
				}

				val atr = ByteArray(16)
				if (0 == cpuCardHandler.setPowerOn(atr)) {
					showResult(resultTV, "Power on Fail!")
					cpuCardHandler.setPowerOff()
					return@launch
				}

				//TODO Select Applet
				var cmd = "00A404000B0102030405060708090001"
				var cmdBytes: ByteArray = HexUtil.hexStringToByte(cmd)
				var res = ByteArray(256)
				showResult(resultTV, " ")
				showResult(resultTV, "-----------------------------------------")
				showResult(resultTV, "TID: $tid")
				showResult(resultTV, "MID: $mid")

				//showResult(resultTV, "Select Applet")
				var ret = cpuCardHandler.exchangeCmd(res, cmdBytes, cmdBytes.size)
				if (ret <= 0) {
					throw java.lang.Exception("Select Applet Fail")
				}

				aResult =
					HexUtil.bytesToHexString(HexUtil.subByte(res, ret - 2, ret)).subSequence(0, 4).toString()
				if (aResult != "9000") {
					throw java.lang.Exception("Select Applet Fail: $aResult")
				}
				//showResult(resultTV, HexUtil.bytesToHexString(HexUtil.subByte(res, 0, ret)))

				//TODO Get Serial
				cmd = "0C010000"
				cmdBytes = HexUtil.hexStringToByte(cmd)
				res = ByteArray(256)

				//showResult(resultTV, "Get Serial")
				ret = cpuCardHandler.exchangeCmd(res, cmdBytes, cmdBytes.size)
				if (ret <= 0) {
					throw java.lang.Exception("Get Serial Fail")
				}

				aResult =
					HexUtil.bytesToHexString(HexUtil.subByte(res, ret - 2, ret)).subSequence(0, 4)
						.toString()
				if (aResult != "9000") {
					throw java.lang.Exception("Get Serial Fail: $aResult")
				}
				sn = HexUtil.bytesToHexString(HexUtil.subByte(res, 0, ret - 2))
				showResult(resultTV, "Card Serial Number: $sn")

				//TODO Get Pin Verification Mode
				cmd = "0C020000"
				cmdBytes = HexUtil.hexStringToByte(cmd)
				res = ByteArray(256)

				//showResult(resultTV, "Get Pin Verification Mode")
				ret = cpuCardHandler.exchangeCmd(res, cmdBytes, cmdBytes.size)
				if (ret <= 0) {
					throw java.lang.Exception("Get Pin Verification Mode Fail")
				}

				aResult =
					HexUtil.bytesToHexString(HexUtil.subByte(res, ret - 2, ret)).subSequence(0, 4)
						.toString()
				if (aResult != "9000") {
					throw java.lang.Exception("Get Pin Verification Mode Fail: $aResult")
				}
				pin_verification_mode = HexUtil.bytesToHexString(HexUtil.subByte(res, 0, ret - 2))
				//showResult(resultTV, pin_verification_mode)

				//TODO Pin Verification
				cmd = "0C02010003${passwodEntered}"
				cmdBytes = HexUtil.hexStringToByte(cmd)
				res = ByteArray(256)

				showResult(resultTV, "Pin Verification")
//            resultMsg += "Pin Verification \r\n"
				ret = cpuCardHandler.exchangeCmd(res, cmdBytes, cmdBytes.size)
				if (ret <= 0) {
					throw java.lang.Exception("Pin Verification Fail")
				}

				aResult =
					HexUtil.bytesToHexString(HexUtil.subByte(res, ret - 2, ret)).subSequence(0, 4)
						.toString()
				log.appendLine(helperlogClassName, "Pin Verification -> ", aResult)
				if (aResult != "9000") {
					log.appendLine(helperlogClassName, "Pin Verification Fail")
					log.logToFile(EnumLogFileName.TerminaLog)
					throw java.lang.Exception("Pin Verification Fail: $aResult")
				}
				if (pin_verification_mode == "01") {
					pin_block = HexUtil.bytesToHexString(HexUtil.subByte(res, 0, ret - 2))
				}
				//showResult(resultTV, HexUtil.bytesToHexString(HexUtil.subByte(res, 0, ret - 2)))

				//TODO Get Counter
				cmd = "0C03010000"
				cmdBytes = HexUtil.hexStringToByte(cmd)
				res = ByteArray(256)

				showResult(resultTV, "Get Counter")
//            resultMsg += "
//            Get Counter \r\n"
				ret = cpuCardHandler.exchangeCmd(res, cmdBytes, cmdBytes.size)
				if (ret <= 0) {
					throw java.lang.Exception("Get Counter Fail")
				}

				aResult =
					HexUtil.bytesToHexString(HexUtil.subByte(res, ret - 2, ret)).subSequence(0, 4)
						.toString()
				if (aResult != "9000") {
					throw java.lang.Exception("Get Counter Fail: $aResult")
				}
				counter = HexUtil.bytesToHexString(HexUtil.subByte(res, 0, ret - 2))
				log.appendLine(helperlogClassName, "Counter -> ", counter)

				showResult(resultTV, "Counter: $counter")

				//TODO Get Encrypt Method
				cmd = "0C03000000"
				cmdBytes = HexUtil.hexStringToByte(cmd)
				res = ByteArray(256)

				//showResult(resultTV, "Get Encrypt Method")
				ret = cpuCardHandler.exchangeCmd(res, cmdBytes, cmdBytes.size)
				if (ret <= 0) {
					throw java.lang.Exception("Get Encrypt Method fail")
				}

				aResult =
					HexUtil.bytesToHexString(HexUtil.subByte(res, ret - 2, ret)).subSequence(0, 4)
						.toString()
				if (aResult != "9000") {
					throw java.lang.Exception("Get Encrypt Method fail: $aResult")
				}
				encrypt_method = HexUtil.bytesToHexString(HexUtil.subByte(res, 0, ret - 2))
				//showResult(resultTV, encrypt_method)

				// all good and can proceed to iso
				proceedISO = true
			} catch (e: Exception) {
				e.printStackTrace()
				cpuCardHandler.setPowerOff()
				showResult(resultTV, "Exception in Exchange APDU $e")
			} finally {
				if (proceedISO) {
					withContext(Dispatchers.Default) {
						isoPreparation(mid, tid, log)
					}
				}
			}
		}

		coroutineScopes.join()
	}

	fun getMac(hMac: String): String {
		var result = ""
		try {
			var aResult = ""

			//TODO Generate Mac
			val cmd = "0C06000018$hMac"
			val cmdBytes: ByteArray = HexUtil.hexStringToByte(cmd)
			val res = ByteArray(256)

			//showResult(resultTV, "Generate Mac")
			val ret = cpuCardHandler.exchangeCmd(res, cmdBytes, cmdBytes.size)
			if (ret <= 0) {
				throw java.lang.Exception("Generate MAC Fail")
			}

			aResult = HexUtil.bytesToHexString(HexUtil.subByte(res, ret - 2, ret)).subSequence(0, 4)
				.toString()
			if (aResult != "9000") {
				throw java.lang.Exception("Generate MAC Fail: $aResult")
			}
			result = HexUtil.bytesToHexString(HexUtil.subByte(res, 0, ret - 2))
			showResult(resultTV, "Generated MAC: $result ")
		} catch (e: Exception) {
			e.printStackTrace()
			cpuCardHandler.setPowerOff()
			showResult(resultTV, "Exception in GetMac APDU $e")
		}

		return result
	}

	fun decryptBsnKey(keyValue: String, keyCat: String ): String  {
		var result = ""
		try {
			var aResult = ""
			//TODO Select Applet
			var cmd = "00A404000B0102030405060708090001"
			var cmdBytes: ByteArray = HexUtil.hexStringToByte(cmd)
			var res = ByteArray(256)

			var ret = cpuCardHandler.exchangeCmd(res, cmdBytes, cmdBytes.size)
			if (ret <= 0) {
				throw java.lang.Exception("Select Applet Fail")
			}

			aResult = HexUtil.bytesToHexString(HexUtil.subByte(res, ret - 2, ret)).subSequence(0, 4)
				.toString()
			if (aResult != "9000") {
				throw java.lang.Exception("Select Applet Fail: $aResult")
			}

			//TODO Decrypt Keys
			cmd = "0C04000010$keyValue"
			cmdBytes = HexUtil.hexStringToByte(cmd)
			res = ByteArray(256)

			//showResult(resultTV, "Decrypt $keyCat")
			ret = cpuCardHandler.exchangeCmd(res, cmdBytes, cmdBytes.size)
			if (ret <= 0) {
				throw java.lang.Exception("Decrypt $keyCat Fail")
			}

			aResult = HexUtil.bytesToHexString(HexUtil.subByte(res, ret - 2, ret)).subSequence(0, 4)
				.toString()
			if (aResult != "9000") {
				throw java.lang.Exception("Decrypt $keyCat Fail: $aResult")
			}
			result = HexUtil.bytesToHexString(HexUtil.subByte(res, 0, ret - 2))
			//showResult(resultTV, "Decrypted Key: $result")
		} catch (e: Exception) {
			e.printStackTrace()
			showResult(resultTV, "Exception in Decrypt Key APDU $e")
		}

		return result
	}


	suspend fun isoPreparation(mid: String, tid: String, log: helpers.HelperLog) {
		//TODO ISO GENERATION

		val coroutineScopes = CoroutineScope(Dispatchers.IO).launch {
			try {
				val nii = DbModelMerchantConfig.getSafeValue(merchantInfo, "NII").substring(1)

				showResult(resultTV, "Preparing ISO Message")
				val ig = Util.IsoGenerate()
				ig.setMessageType("0800")
				ig.setTPDU(nii)
				ig.addField(3, "010000");
				ig.addField(11, "000001");
				ig.addField(24, nii)
				ig.addField(41, tid)
				ig.addField(42, mid)

				if (pin_verification_mode == "01") {
					ig.addField(52, pin_block)
				}

				//TODO Generation DE62
				var generateDE62 = ""
				//Encryption Algorithm
				generateDE62 += default_enc

				//Unique Key Type
				generateDE62 += default_key_type

				//Key Size
				generateDE62 += default_key_size

				//Mac Algorithm
				generateDE62 += default_mac

				//Delimiter
				generateDE62 += default_delimiter

				//Application ID
				generateDE62 += default_application_id

				//Pin Verification Mode (Online/Offline)
				generateDE62 += pin_verification_mode

				//Counter
				generateDE62 += counter

				//SN Size
				generateDE62 += Utility.zeroPadding(sn.length.toString(), 2)

				//SN
				generateDE62 += Utility.zeroPadding(sn, 16, true)
				ig.addField(62, generateDE62)
				//TODO Generation DE62

				val de63 = "$tid~$mid"
				ig.addField(63, de63)

				val tMac = ig.isoMessage.substring(14)
				var hMac =
					Cryptography.hashData(Utility.ASCIItoHexString(tMac), Cryptography.hashAlgorithm.SHA_1)
				hMac += "80000000"
				val mac = getMac(hMac)
				ig.addField(64, mac)

				log.appendLine(helperlogClassName, "ISO -> ", ig.isoMessage)
				//TODO send request
				runCount = 0;
				while (runCount <= maxCount){
					++runCount
					showResult(resultTV, "------- Attempting $runCount -------------")
					log.appendLine(helperlogClassName, "Attempting -> ", runCount.toString())
					if(sendToBsn(ig, mid, tid, log)){
						runCount = 100
					}
				}
				jobRunning = false
				runOnUiThread(Runnable {
					submitButton.text = "Submit"
				})
				cpuCardHandler.setPowerOff()
			} catch (e: Exception) {
				e.printStackTrace()
				log.appendLine(helperlogClassName, "REJECT :: key download aborted :: ${e.message}")
				log.logToFile(EnumLogFileName.TerminaLogException)
				cpuCardHandler.setPowerOff()
			}
		}
		coroutineScopes.join()
	}

	suspend fun sendToBsn(igVar: IsoGenerate, mid: String, tid: String, log: helpers.HelperLog): Boolean {
		var result = false
		val coroutineScopes = CoroutineScope(Dispatchers.IO).launch {
			try {
				val ip1 = DbModelMerchantConfig.getSafeValue(merchantInfo, "PrimaryHostIp")
				val port1 = NumberUtils.toInt(DbModelMerchantConfig.getSafeValue(merchantInfo, "PrimaryHostPort"), 443)
				val ip2 = DbModelMerchantConfig.getSafeValue(merchantInfo, "SecondaryHostIp")
				val port2 = NumberUtils.toInt(DbModelMerchantConfig.getSafeValue(merchantInfo, "SecondaryHostPort"), 443)
				val timeout = NumberUtils.toInt(DbModelMerchantConfig.getSafeValue(merchantInfo, "HostTimeoutMs"), 60 * 1000)

				val scConn = MessageSender(ip1, port1, ip2, port2, timeout, this@BsnKeyDownloadActivity)
				//showResult(resultTV, "ISO Msg:")
				showResult(resultTV, "Generated ISO Msg")

				scConn.SendMessage(igVar.isoMessageByte, true)
				Util.DelayMili(1000)

				while (scConn.status) {
					println(scConn.status)
					Util.DelayMili(500);
				}

				val resp: ByteArray = scConn.returnMessage
				if (resp.isNotEmpty()) {
					showResult(resultTV, "Received Message from Host")

					if (resp.size < 34) {
						showResult(resultTV, "C9 Invalid Length")
						log.appendLine(helperlogClassName, "C9 Invalid Length")
					} else {
						result = true
						val DM = Util.DecomposedIsoMessage()
						//showResult(resultTV, Utility.Bytes2HexString(resp))
						DM.setISOMsg(Utility.Bytes2HexString(resp))

						if (DM.mti.equals("0800")) {
							showResult(resultTV, "C5 MIT Mismatch")
							log.appendLine(helperlogClassName, "C5 MIT Mismatch")
						} else {
							if (DM.getValue(39) != "00") {
								showResult(resultTV, "${DM.getValue(39)}: Transaction Fail")
								log.appendLine(helperlogClassName, "RespCode -> ", DM.getValue(39))
							} else {
								showResult(resultTV, "Txn Approved")
								log.appendLine(helperlogClassName, "Txn Approved")

								var resp62 = DM.getValue(62)
								// Field 62 carries the encrypted key block; log its length, not its bytes.
								log.appendLine(helperlogClassName, "resp62 received, length -> ", resp62.length.toString())
								dataBundle.putString("iso62", resp62)
								resp62 = resp62.substring(42)

								val tle_ksn = Utility.HexString2ASCII(resp62.substring(0, 12)) + tid + "E00000"
								dataBundle.putString("ksn", tle_ksn)
								showResult(resultTV, "KSN: $tle_ksn")
								log.appendLine(helperlogClassName, "KSN -> ", tle_ksn)
								resp62 = resp62.substring(26)

								var loop = 3
								var count = 4
								while (loop > 0) {
									val _key = Utility.HexString2ASCII(resp62.substring(0, 64))
									resp62 = resp62.substring(66)
									val _kcv = Utility.HexString2ASCII(resp62.substring(0, 12))
									resp62 = resp62.substring(14)

									var type = "KSN"
									when (loop) {
										3 -> type = "TLE"
										2 -> type = "MAC"
										1 -> type = "PIN"
									}
									showResult(resultTV, "------- $type ---------")
									//showResult(resultTV, "Encrypted Key: $_key")
									showResult(resultTV, "kcv from iso: $_kcv")

									val decryptedData = decryptBsnKey(_key, type)
									// Never log the key itself (was: "$type -> $decryptedData", the plaintext
									// TLE/MAC/PIN key). The KCV is the safe check value for the same key.
									log.appendLine(helperlogClassName, "$type key loaded, KCV -> ", _kcv)
									val ownEncData: String = Encryption.encrypt("${decryptedData}8000000000000000", Utility.ASCIItoHexString(secureKey), "DESede", "CBC")
									dataBundle.putString(type, ownEncData)
									//var tmpKCV = Cryptography.encrypt(msgEnc, decryptedData)
									//tmpKCV = tmpKCV.substring(0, 6)
									//showResult(resultTV, "kcv from local: $tmpKCV")

									loop--;
									count++;
								}
								showResult(resultTV, "PIN KSN: $resp62")
								log.appendLine(helperlogClassName, "PIN KSN -> ", resp62)
								dataBundle.putString("pinKsn", "FFFF$resp62")

								updateBSNKey(mid, tid)
							}
						}
					}
				}
			} catch (e: Exception) {
				e.printStackTrace()
				log.appendLine(helperlogClassName, "REJECT :: BSN host exchange failed :: ${e.message}")
				log.logToFile(EnumLogFileName.TerminaLogException)
			} finally {
				log.logToFile(EnumLogFileName.TerminaLog)
			}
		}

		coroutineScopes.join()
		return result
	}

	fun updateBSNKey(mid: String, tid: String){
		val insertTMK = DbModelSecureData("eWekKey", "visam-tleBsn-$mid", dataBundle.getString("TLE")!!)
		SecureDataRepo.insertToDb(this@BsnKeyDownloadActivity, insertTMK)

		val insertTMKId = DbModelSecureData("eWakKey", "visam-tleBsn-$mid", dataBundle.getString("MAC")!!)
		SecureDataRepo.insertToDb(this@BsnKeyDownloadActivity, insertTMKId)

		val insertTAK = DbModelSecureData("eTpkKey", "visam-tleBsn-$mid", dataBundle.getString("PIN")!!)
		SecureDataRepo.insertToDb(this@BsnKeyDownloadActivity, insertTAK)

		IsoBatchInfoRepo.updateBatchInfo(this@BsnKeyDownloadActivity, dataBundle.getString("ksn")!!, "ksn", "visam")
		IsoBatchInfoRepo.updateBatchInfo(this@BsnKeyDownloadActivity, dataBundle.getString("pinKsn")!!, "ksn", "visam-pin")

		val criteriaHM = hashMapOf<Any, Any>(
			"AcqMid" to mid,
			"AcqTid" to tid
		)

		val valueHM = hashMapOf<Any, Any>(
			"Ksn" to dataBundle.getString("ksn")!!,
			"PinKsn" to dataBundle.getString("pinKsn")!!
		)

		ProductListRepo.updateData(this@BsnKeyDownloadActivity, valueHM, criteriaHM)
		DeviceHelper.resetAID()
		DeviceHelper.resetCAPK()
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "BsnKeyDownloadActivity OnDestroy :: key download screen closed (jobRunning=$jobRunning)")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		runCount = 100
		startJob = false
		jobRunning = false
		lifecycleScope.cancel()
	}
}