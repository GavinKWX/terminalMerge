package com.sc.mf919.kotlin.helper_common
import emv.EmvUtil
import emv.Tlv

import constants.TerminalConstants

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.library.terminal.Utility
import com.morefun.yapi.ServiceResult
import com.morefun.yapi.device.reader.icc.*
import com.morefun.yapi.device.reader.mag.MagCardInfoEntity
import com.morefun.yapi.device.reader.mag.MagCardReader
import com.morefun.yapi.device.reader.mag.OnSearchMagCardListener
import com.morefun.yapi.emv.*
import com.sc.mf919.java.MF919
import com.sc.mf919.java.activity.*
import com.sc.mf919.java.device.DeviceHelper
import com.sc.mf919.java.utils.*
import utils.*
import com.sc.mf919.kotlin.activity.ActivityBase
import com.sc.mf919.kotlin.activity.AppServices
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo.Companion.getSelectedProduct
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.selectedCacheModel
import data_enum.SalesModel
import helpers.HelperCommon
import java.util.*
import org.apache.commons.lang3.StringUtils
import com.sc.mf919.kotlin.helper_common.MfHelper

open class BaseActivity : ActivityBase() {
	var indexM = 0
	var indexT = 0
	protected var SEQ: String? = null
	protected var typeOfSales = 0
	protected var lps = 0
	var isNotEnd = true
	var pinRequired = false
	var pinWait = true
	var pinCancel = false
	protected var mPinNum: String? = null
	protected lateinit var mAmount: String
	protected lateinit var mOptData: String
	protected lateinit var tempContext: Context
	private lateinit var mAID: String
	private val send2BankPDIsActive = false
	protected var payMethod = TerminalConstants.paymentMethod.Non.toByte()
	protected var cardDetected: Byte = 0x00
	private var timeout_cardSearch = 0

	private var iccCardReader: IccCardReader? = null
	private var rfReader: IccCardReader? = null
	private var magCardReader: MagCardReader? = null

	var cube: CubeActivity? = null
	private var myTlv: Tlv? = null

	var isOptIn = false
	var strSchemeTag = "visam"
	var startTick: Long = 0
	var cvmLimit = 250.00


	var webSocketSignKey = "";

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (DeviceHelper.application != null) {
			DeviceHelper.application.bindDeviceService()
		} else {
			MF919.getApp().bindDeviceService()
		}

		//ButterKnife.bind(this);
		cube = CubeActivity()
		ServiceHolder.mCube = cube
		myTlv = Tlv()
	}

	private fun getAIDnCVMLimit() {
		mAID = EmvUtil.getPbocData("4F", true)
		val strCvmLimit = EmvUtil.getPbocData("DF21", true)
		if (strCvmLimit != null) cvmLimit = (strCvmLimit.toInt() / 100).toDouble()
	}

	private fun selApp(appList: List<String>) {
		val options = arrayOfNulls<String>(appList.size)
		var forceSelect: Int = -1
		for (i in appList.indices) {
			val splitString = appList[i].split("|")
			if(isOptIn && splitString[1] != null &&  splitString[1].contains("A000000615",true)){
				forceSelect = i
			}
			//options[i] = appList[i]
			options[i] = splitString[0]
		}
		if(forceSelect != -1){
			try {
				DeviceHelper.getEmvHandler().onSetSelAppResponse(forceSelect)
			} catch (e: RemoteException) {
				e.printStackTrace()
			}
		} else {
			runOnUiThread {
				val alertBuilder = AlertDialog.Builder(this@BaseActivity)
				alertBuilder.setTitle("Please select app")
				alertBuilder.setItems(
					options
				) { dialogInterface, index ->
					try {
						DeviceHelper.getEmvHandler().onSetSelAppResponse(index)
					} catch (e: RemoteException) {
						e.printStackTrace()
					}
				}
				alertBuilder.setCancelable(false)
				val alertDialog1 = alertBuilder.create()
				alertDialog1.show()
			}
		}
	}

	@Throws(RemoteException::class)
	private fun startSearchContactless(listener: OnSearchIccCardListener.Stub) {
		Utils.printLog("New Thread start.")
		rfReader = DeviceHelper.getIccCardReader(IccReaderSlot.RFSlOT)
		rfReader?.searchCard(
			listener,
			timeout_cardSearch,
			arrayOf(IccCardType.CPUCARD, IccCardType.AT24CXX, IccCardType.AT88SC102)
		)
	}

	open fun stopSearch() {
		if (rfReader == null || iccCardReader == null) {
			return
		}
		runOnUiThread {
			try {
				//MfHelper.lockStatusBarAndNavigation(false)
				if (iccCardReader != null) {
					iccCardReader!!.stopSearch()
				}
				if (rfReader != null) {
					rfReader!!.stopSearch()
				}
				if (magCardReader != null) {
					magCardReader!!.stopSearch()
				}
				payMethod = TerminalConstants.paymentMethod.Cancel.toByte()
			} catch (e: RemoteException) {
				e.printStackTrace()
			} catch (e: NullPointerException) {
				e.printStackTrace()
			}
		}
	}

	open fun endPBOC() {
		// Every EMV error path funnels here, but not all of them hide the progress
		// dialog. It is non-cancelable, so a missed hide freezes the terminal;
		// hideProgress() is a posted no-op when nothing is showing, making it safe
		// to call unconditionally.
		hideProgress()
		try {
			DeviceHelper.getEmvHandler().endPBOC()
		} catch (e: RemoteException) {
			e.printStackTrace()
		}
		isNotEnd = false
	}

	/*
	 * NOT converted to HelperLog.appendLine, and it must not be: `builder` is assembled from
	 * EmvUtil.readPan() and EmvUtil.readTrack2(), i.e. the full PAN and the full track 2. A
	 * correlated appendLine would put both into a TerminaLog block that
	 * TmsHelper.uploadAllTerminalLog ships to the server. Nothing here currently calls
	 * onFinishShow() (EmvActivity declares its own onFinishShow(Bundle)), so this is a latent
	 * hazard rather than a live leak - if it is ever revived, log
	 * Utils.hideCardDetails(...) / the masked PAN tag instead of the raw builder.
	 */
	@Throws(RemoteException::class)
	open fun onFinishShow() {
		val builder = java.lang.StringBuilder()
		val tlv = EmvUtil.getTLVDatas(EmvUtil.tags)
		val tlvDataList = TlvDataList.fromBinary(tlv)
		builder.append("Card No:${EmvUtil.readPan()}")
		builder.append("Card Org:${CardUtil.getCardTypFromAid(EmvUtil.getPbocData("4F", true))}")
		builder.append("Card Track 2:${EmvUtil.readTrack2()}")
		for (tag in EmvUtil.tags) {
			when {
				"9F4E".equals(tag, ignoreCase = true) -> {
					builder.append("$tag=${tlvDataList.getTLV(tag)}")
				}
				"5F20".equals(tag, ignoreCase = true) -> {
					builder.append("$tag=${tlvDataList.getTLV(tag)}")
				}
				else -> {
					builder.append("$tag=${tlvDataList.getTLV(tag)}")
				}
			}
		}
		Utils.printLog("Builder $builder")
	}

//	@RequiresApi(Build.VERSION_CODES.O)
//	private fun sendTmsReceipt(): Int {
//		val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
//		val timeStamp = sdf.format(Date())
//
//		//Header
//		val header: MutableMap<String, String> = HashMap()
//		header["Content-Type"] = "application/json"
//		var strTxnType = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_TXN_TYPE)
//			)
//		)
//
//		val strPaymentProductId = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_PYMT_PRODUCT_ID)
//			)
//		)
//		val strSchemeId = cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_CARD_SCHEME_ID)
//		val strStan = cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_STAN)
//		val strTxnAmt = cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_TXN_AMT)
//		val strRrn = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_RRN)
//			)
//		)
//		val strApprCode = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_APPRCODE)
//			)
//		)
//		//String strRespCode = cube.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_RESPCODE);
//		val strTid = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_TID)
//			)
//		)
//		val strMid = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_MID)
//			)
//		)
//		val strBatchNo = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_BATCHNO)
//			)
//		)
//		val strRespCode = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_RESPCODE)
//			)
//		)
//		val strInvNo = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_INVNO)
//			)
//		)
//		val strAid = cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_CARD_AID)
//		val strNii = isos!!.getIsoComponent("DF24", 16)
//		val strMaskPanBcd = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_CARDPAN_MASKBCD)
//			)
//		)
//		val strEntryType = Utils.byteArrayToAsciiString(
//			HexUtil.hexStringToByte(
//				cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_CARD_ENTRY_MODE)
//			)
//		)
//		val strARQC = cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_CARD_ARQC)
//		val strTVR = cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_CARD_TVR)
//		val strPosReference = Utils.byteArrayToAsciiString(HexUtil.hexStringToByte(cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_POS_REFERENCE)))
//		if (strTxnType == "Pre Authorization") strTxnType = "PreAuth"
//		else if (strTxnType == "Sale Completion") strTxnType = "SaleCompletion"
//		else if (strTxnType == "Instalment Sale") strTxnType = "EPP"
//
//		//Json Body
//		val jsonObject = JSONObject()
//		try {
//			val Seq = ServiceHolder.getSqnNum()
//			jsonObject.put("SEQ_NO", Seq)
//			jsonObject.put("TXN_DT", timeStamp)
//			jsonObject.put("TXN_TYPE", strTxnType)
//			jsonObject.put("MID", strMid)
//			jsonObject.put("TID", strTid)
//			jsonObject.put("MTI", "")
//			jsonObject.put("NII", strNii)
//			jsonObject.put("SCHEME_ID", strSchemeId)
//			jsonObject.put("AID", strAid)
//			jsonObject.put("CARD_MASKED", strMaskPanBcd)
//			jsonObject.put("CARD_HASHED", "")
//			jsonObject.put("RRN", strRrn)
//			jsonObject.put("APPR_CODE", strApprCode)
//			jsonObject.put("TXN_AMT", strTxnAmt)
//			jsonObject.put("INV_NO", strInvNo)
//			jsonObject.put("STAN", strStan)
//			jsonObject.put("BATCH_NO", strBatchNo)
//			jsonObject.put("RESP_CODE", strRespCode)
//			jsonObject.put("APP_VER", ServiceHolder.getAppVersion())
//			jsonObject.put("SN", ServiceHolder.getTerminalSerialNumber())
//			jsonObject.put("ENTRY_TYPE", strEntryType)
//			jsonObject.put("ARQC", strARQC)
//			jsonObject.put("TVR", strTVR)
//			jsonObject.put("POS_REF_NO", strPosReference)
//			jsonObject.put("PAYMENT_PRODUCT_ID", strPaymentProductId)
//		} catch (e: JSONException) {
//			e.printStackTrace()
//		}
//		val body = jsonObject.toString()
//		UploadTMS.getInstance().addReceipt(body)
//		return 0
//	}

	@RequiresApi(Build.VERSION_CODES.O)
	private fun updateReceiptInfo(): Int {
		//TODO ADD EPP_DETAIL
		val strStan = cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_STAN)
		val strRrn = Utility.HexString2ASCII(cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_RRN))
		val strApprCode = Utility.HexString2ASCII(cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_APPRCODE))
		val strRespCode = Utility.HexString2ASCII(cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_RESPCODE))
		val strARQC = cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_CARD_ARQC)
		val strTVR = cube!!.tlv_get_value_in_string(TerminalConstants.cube.CUBE_TAG_CARD_TVR)

		val strEppDetails = cube?.tlv_get_value_in_asciistring(TerminalConstants.cube.CUBE_TAG_EPP_DETAILS)?.trim()
		println("strEppDetails: $strEppDetails")
		val hmEppDetails = parseEppDetailsJson(strEppDetails)
		println("hmEppDetails: $hmEppDetails")


		val valueHM = HashMap<Any, Any>()
		valueHM["RRN"] = strRrn
		valueHM["APPR_CODE"] = strApprCode
		valueHM["RESP_CODE"] = strRespCode
		valueHM["ARQC"] = strARQC
		valueHM["TVR"] = strTVR
		valueHM["EPP_DETAIL"] = hmEppDetails

		val criteriaHM = HashMap<Any, Any>()
		criteriaHM["STAN"] = strStan

		ReceiptUploadRepo.updateData(tempContext, valueHM, criteriaHM)
		return 0
	}

	private fun getSpecificKsn(context: Context, fieldList: ArrayList<String>, valueList: Array<String>): DbModelProductList? {
		val specificProdList = getSelectedProduct(context, fieldList, valueList)
		var productItem: DbModelProductList? = null
		if (specificProdList.isNotEmpty()) {
			try {
				val gson = Gson()
				val jsonString = gson.toJson(specificProdList[0])
				productItem = gson.fromJson(jsonString, DbModelProductList::class.java)
			} catch (e: java.lang.Exception) {
				e.printStackTrace()
			}
		}
		return productItem
	}

	fun parseEppDetailsJson(eppDe63: String?): String {
		val eppDetails = HashMap<Any, Any>()
		eppDetails["Acquirer"] = ServiceHolder.getAcquirerSetting().acqName.uppercase(Locale.getDefault())
		if (eppDe63 != null) {
			if (eppDetails["Acquirer"] == "BSN"){
				eppDetails["Tenure"] = eppDe63.substring(1, 3)
				eppDetails["TotalAmt"] = Utils.getActualAmount(eppDe63.substring(48))
				eppDetails["FirstMonthAmt"] = Utils.getActualAmount(eppDe63.substring(22, 35))
				eppDetails["MonthlyAmt"] = Utils.getActualAmount(eppDe63.substring(22, 35))
				eppDetails["FinalAmt"] = Utils.getActualAmount(eppDe63.substring(9, 22))
			} else {
				eppDetails["Tenure"] = eppDe63.substring(1, 3)
				eppDetails["FirstMonthAmt"] = Utils.getActualAmount(eppDe63.substring(4, 15))
				eppDetails["MonthlyAmt"] = Utils.getActualAmount(eppDe63.substring(16, 27))
				eppDetails["TotalAmt"] = Utils.getActualAmount(eppDe63.substring(40))
			}
		} else {
			eppDetails["Tenure"] = "00"
			eppDetails["TotalAmt"] = "0.00"
			eppDetails["FirstMonthAmt"] = "0.00"
			eppDetails["MonthlyAmt"] = "0.00"
			eppDetails["FinalAmt"] = "0.00"
		}
		return eppDetails.toString()
	}
}