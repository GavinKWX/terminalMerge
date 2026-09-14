package com.sc.mf919.kotlin.activity

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
import com.morefun.yapi.device.reader.icc.IccCardReader
import com.morefun.yapi.device.reader.icc.IccCardType
import com.morefun.yapi.device.reader.icc.IccReaderSlot
import com.morefun.yapi.device.reader.icc.OnSearchIccCardListener
import com.morefun.yapi.device.reader.mag.MagCardInfoEntity
import com.morefun.yapi.device.reader.mag.MagCardReader
import com.morefun.yapi.emv.EmvChannelType
import com.morefun.yapi.emv.EmvErrorCode
import com.morefun.yapi.emv.EmvErrorConstrants
import com.morefun.yapi.emv.EmvKernelCallback
import com.morefun.yapi.emv.EmvListenerConstrants
import com.morefun.yapi.emv.EmvOnlineRequest
import com.morefun.yapi.emv.EmvOnlineResult
import com.morefun.yapi.emv.EmvProcessResult
import com.morefun.yapi.emv.EmvRupayCallback
import com.morefun.yapi.emv.EmvTransDataConstrants
import com.morefun.yapi.emv.GoToConstants
import com.morefun.yapi.emv.ICheckCardListener
import com.morefun.yapi.emv.OnEmvProcessListener
import com.sc.mf919.java.MF919
import com.sc.mf919.java.activity.ActivityBase
import com.sc.mf919.java.activity.CubeActivity
import crypto.Dukpt
import crypto.DukptVariant
import crypto.Encryption
import constants.TerminalConstants
import com.sc.mf919.kotlin.database.repo.SecureDataRepo
import com.sc.mf919.java.activity.PinPad
import com.sc.mf919.java.activity.PinPadListener
import emv.Tlv
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.device.DeviceHelper
import utils.CardUtil
import emv.EmvUtil
import utils.HexUtil
import utils.TlvData
import utils.TlvDataList
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import data_enum.SalesModel
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.IsoBatchLongInfoRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo.Companion.getSelectedProduct
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.selectedCacheModel
import iso.CardTagsEnum
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity.updateReceiptInfo
import iso.IsoHelperNew
import enums.EnumDateFormat
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import helpers.LogRedact
import com.sc.mf919.kotlin.helper_common.MfHelper

open class EmvActivity: ActivityBase() {
    var cube: CubeActivity? = null
    var myTlv: Tlv? = null

    protected lateinit var tempContext: Context
    val logClassName: String = this::class.java.simpleName

    /**
     * EMV kernel logging. Replaces Utils.printLog / Utils.debugLogPrint at every live site in
     * this class, which had two problems: printLog tags every line "-(Utils)", and neither call
     * carries a RowIdentifier or lands in any block -- so an EMV flow could not be tied back to
     * the transaction it belonged to. appendLine gives both, per-line on disk and grouped.
     *
     * tempHelperLog is lateinit and only assigned in startEMV, so calls made outside a
     * transaction fall back to the old Timber path instead of throwing.
     */
    private fun logEmv(msg: String) = logEmv(logClassName, msg)

    private fun logEmv(tag: String, msg: String) {
        if (this::tempHelperLog.isInitialized) {
            tempHelperLog.appendLine(tag, msg)
        } else {
            Utils.debugLogPrint(tag, msg)
        }
    }
    lateinit var tempHelperLog: HelperLog

    private var iccCardReader: IccCardReader? = null
    private var rfReader: IccCardReader? = null
    private var magCardReader: MagCardReader? = null
    private var startTick: Long = 0

    //Transaction
    protected var typeOfSales = 0
    var isNotEnd = true
    var pinRequired = false
    var pinWait = true
    var pinCancel = false
    protected var mPinNum: String? = null
    var mAmount: String = ""
    var mOptData: String = ""
    protected var payMethod = TerminalConstants.paymentMethod.Non.toByte()

    var isOptIn = false
    //var cvmLimit = 250.00
    val cvmLimit = 25000L
    /*var readCountDown = 20
    var readTimer: Timer? = null*/


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DeviceHelper.application != null) {
            DeviceHelper.application.bindDeviceService()
        } else {
            MF919.getApp().bindDeviceService()
        }

        cube = CubeActivity()
        ServiceHolder.mCube = cube
        myTlv = Tlv()
    }

    @Throws(java.lang.Exception::class)
    fun startEMV(currentContext: Context, amount: String, cashOutAmount: Long, forceContact: Boolean, log: HelperLog) {
        isNotEnd = true
        tempHelperLog = log
        var cashOutAmountString = Utils.getActualAmount(cashOutAmount.toString())
        if(cashOutAmountString == "0") {
            cashOutAmountString = "0.00"
        }

        try {
            tempContext = currentContext

            val bundle = Bundle()
            bundle.putInt(EmvTransDataConstrants.CHECK_CARD_TIME_OUT, 60)
            val terminalConfig = ServiceHolder.getTerminalConfig()
            isOptIn = DbModelTerminalConfig.getBooleanValue(terminalConfig, "OptIn")

            /* Check for Contact Enable */
            val isEnableContact = DbModelTerminalConfig.getBooleanValue(terminalConfig, "Contact")
            tempHelperLog.appendLine(logClassName, "isEnableContact :: $isEnableContact")
            bundle.putBoolean(EmvTransDataConstrants.SUPPORT_IC_CARD, isEnableContact)

            /* Check for Contactless Enable */
            var isEnableContactless = DbModelTerminalConfig.getBooleanValue(terminalConfig, "Contactless")
            tempHelperLog.appendLine(logClassName, "isEnableContactless :: $isEnableContactless")
            if(forceContact) isEnableContactless = false
            bundle.putBoolean(EmvTransDataConstrants.SUPPORT_RF_CARD, isEnableContactless)

            /* Check for Magstripe Enable */
            val isEnableMagStripe = DbModelTerminalConfig.getBooleanValue(terminalConfig, "MagStripe")
            bundle.putBoolean(EmvTransDataConstrants.SUPPORT_MAG_CARD, isEnableMagStripe)


            DeviceHelper.getEmvHandler().searchCard(bundle, object : ICheckCardListener.Stub() {
                @RequiresApi(Build.VERSION_CODES.O)
                @Throws(RemoteException::class)
                override fun onFindMagCard(magCardInfoEntity: MagCardInfoEntity) {
                    logEmv("Search Card", "onFindMagCard")
                    ServiceHolder.appRunningProcess = true
                    mAmount = amount
                    TransData.payMethod = TerminalConstants.paymentMethod.Meg
                    payMethod = TerminalConstants.paymentMethod.Meg.toByte()

                    // D7 — a swipe never reaches EmvUtil.readTrack2(), so nothing else arms the log sink
                    // for mag-stripe. Register before the builder below, which logs the raw tracks.
                    LogRedact.registerCardData(magCardInfoEntity.cardNo, magCardInfoEntity.tk2)

                    val builder = java.lang.StringBuilder()
                    builder.append("PAN:" + magCardInfoEntity.cardNo)
                    builder.append("TRACK1:${magCardInfoEntity.tk1}".trimIndent())
                    builder.append("TRACK2:${magCardInfoEntity.tk2}".trimIndent())
                    builder.append("TRACK3:${magCardInfoEntity.tk3}".trimIndent())
                    builder.append("KSN: ${magCardInfoEntity.ksn}".trimIndent())
                    builder.append("SERVICE CODE: ${magCardInfoEntity.serviceCode}".trimIndent())
                    logEmv("Builder $builder")

                    //get pin
                    logEmv("MagCard Online Pin $payMethod  $mAmount")
                    pinWait = true
                    //TODO GetPin
                    getPin(true, magCardInfoEntity.cardNo)
                    if(pinCancel){
                        val online = Bundle()
                        DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, online)
                        endEMV()
                        return
                    }
                    startProgressDialog("Bank Authorization", "Waiting for Approval")

                    // online txn
                    // Form LEVEL 3 data
                    if (magCardInfoEntity.serviceCode.startsWith("2")
                        || magCardInfoEntity.serviceCode.startsWith("6")) {
                        logEmv("magCardInfoEntity.getServiceCode()=${magCardInfoEntity.serviceCode}")
                        TransData.schemeId  = "20"
                    } else {
                        TransData.schemeId  = "97"
                    }
                    logEmv("strSchemeIdnow=${TransData.schemeId}")

                    val txnDt = "20" + EmvUtil.getCurrentTime("yyMMddHHmmss")
                    val magTrack2 = magCardInfoEntity.tk2.replace("=", "D")
                    if (magTrack2.length >= 38) {
                        logEmv("Track 2 length more than 37. Declined")
                        val online = Bundle()
                        online.putString(EmvOnlineResult.REJCODE, "05")
                        DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Declined, online)
                        return
                    }
                    val magTrack2Byte = magTrack2.toByteArray()

                    val D2: String = if (magTrack2.length % 2 != 0) {
                        "D2" + Utils.zeroPadding(
                            Integer.toHexString((magTrack2.length + 1) / 2),
                            2
                        ) + magTrack2 + "F"
                    } else {
                        "D2" + Utils.zeroPadding(
                            Integer.toHexString(magTrack2.length / 2),
                            2
                        ) + magTrack2
                    }
                    val de55 = "9F0206" + Utils.zeroPadding(mAmount.replace(".", ""), 12)
                    logEmv("onSearchResult: $de55")
                    val bD3 = ByteArray(2)
                    val de55len = de55.length / 2
                    logEmv("track2len=$de55len")
                    myTlv!!.encodeLen(de55len, bD3, 0)
                    val D3 = "D309$de55"
                    val vl3 = TransData.schemeId + txnDt + D2 + D3
                    logEmv("VL3:$vl3")
                    logEmv("VL3 len:" + vl3.length)
                    val bVl3 = HexUtil.hexStringToByte(vl3)
                    val bVl3Len = bVl3.size
                    val optData = ByteArray(100)
                    val optDataLen = 0
                    logEmv("bvl3:" + HexUtil.bytesToHexString(bVl3))
                    logEmv("bvl3len=$bVl3Len")
                    logEmv("optData:" + HexUtil.bytesToHexString(optData))
                    val isNotCompl = booleanArrayOf(true)
                    logEmv("onlineProc: magCard")

                    magTrack2Byte.copyInto(TransData.magTrack2, 0, 0, magTrack2Byte.size)
                    TransData.magTrack2Len = magTrack2Byte.size

                    tempHelperLog.appendLine(logClassName, "schemeType[${TransData.schemeType}]; schemeId[${TransData.schemeId}]")
                    TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_SCHEME_ID, TransData.schemeId)

                    TransData.cvm = "4E3020"
                    TransData.removeTlvFromTransDb(TerminalConstants.cube.CUBE_TAG_CARD_CVM)
                    TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_CVM, "4E3020")
                    TransData.entryModeLabel = "MagStripe"
                    TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_ENTRY_MODE, Utils.ASCIItoHexString("MagStripe"))
                    TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_MASKBCD, Utils.ASCIItoHexString(Utils.hideCardDetails(magCardInfoEntity.cardNo)))
                    TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARDPAN_HASH, Utils.ASCIItoHexString(magCardInfoEntity.cardNo.substring(0,9)))

                    // D8 -- hold the host guard across the whole flow, so a back press cannot
                    // blank stan/invoiceNo/respCode after the approval arrives but before it is
                    // persisted. sendToHost raises the same counter again inside.
                    IsoActivity.withHostRequest {
                        when (TransData.salesType) {
                            8 -> {IsoActivity.processPreauth(tempContext, tempHelperLog)}
                            4 -> {IsoActivity.processSaleCompCardPresented(tempContext, tempHelperLog)}
                            ProductCatSelectionDataEnum.CASH_OUT.data.SalesType -> {IsoActivity.processCashOutSale(tempContext, tempHelperLog)}
                            ProductCatSelectionDataEnum.EPP.data.SalesType -> {IsoActivity.processEppSale(tempContext, tempHelperLog)}
                            else -> {IsoActivity.processOnlineSale(tempContext, tempHelperLog)}
                        }
                    }
                    isNotCompl[0] = false

                    // Send Reversal if timeout
                    if(TransData.transResult != TerminalConstants.iso.err.txnApproved && TransData.transResult != TerminalConstants.iso.err.txnNotAllowed &&
                        (TransData.transResult == TerminalConstants.iso.err.communicationTimeout || TransData.respCode.isEmpty())) {
                        val isNotCompl = booleanArrayOf(true)
                        ServiceHolder.isoComm = null
                        isNotCompl[0] = true
                        object : Thread() {
                            override fun run() {
                                super.run()
                                var loop = 0
                                val maxLoop = 3
                                while (loop < maxLoop) {
                                    loop++
                                    pDTitle = "Reversal ($loop)"
                                    runOnUiThread(changeTitle)

                                    val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(
                                        TransData.acqCode,
                                        "reversal"
                                    )
                                    acquirerRevIsoModel?.let { revIsoModel ->
                                        val allIsoString = HexUtil.bytesToHexString(
                                            TransData.transactionDb,
                                            0,
                                            TransData.transactionDbLen + 2
                                        )
                                        val result = IsoActivity.processReversal(
                                            tempContext,
                                            true,
                                            revIsoModel,
                                            allIsoString,
                                            false,
                                            tempHelperLog
                                        )
                                        tempHelperLog.appendLine(
                                            logClassName,
                                            "reversal result :: $result"
                                        )
                                        if (result != null) {
                                            loop = maxLoop // used for exit
                                        }
                                    }
                                }
                                isNotCompl[0] = false
                            }
                        }.start()
                        while (isNotCompl[0]) {
                            if (ServiceHolder.isoComm != null) {
                                pDMsg = ServiceHolder.isoComm!!.connectionStatus
                                if (pDMsg != null) {
                                    if (pDMsg!!.isNotEmpty()) {
                                        runOnUiThread(changeMessage)
                                    }
                                }
                            }
                            Utils.DelayMili(100)
                        }
                    }

                    CoroutineScope(Dispatchers.IO).launch {
                        updateReceiptInfo(tempContext)
                    }
                    AppServices.receiptUploadToTms(tempContext)
                    endEMV()
                }

                @Throws(RemoteException::class)
                override fun onSwipeCardFail() {
                    logEmv("Search Card", "onSwipeCardFail")
                    endEMV()
                }

                @Throws(RemoteException::class)
                override fun onFindICCard() {
                    logEmv("Search Card", "onFindICCard")
                    ServiceHolder.appRunningProcess = true
                    payMethod = TerminalConstants.paymentMethod.ICC.toByte()
                    TransData.payMethod = TerminalConstants.paymentMethod.ICC
                    startProgressDialog("Card Detected", "Read Card Info...")
                    emvTrans(amount, cashOutAmountString, isEnableContact, isEnableContactless, EmvChannelType.FROM_ICC)
                }

                @Throws(RemoteException::class)
                override fun onFindRFCard() {
                    logEmv("Search Card", "onFindRFCard")
                    ServiceHolder.appRunningProcess = true
                    payMethod = TerminalConstants.paymentMethod.RF.toByte()
                    TransData.payMethod = TerminalConstants.paymentMethod.RF
                    startProgressDialog("Card Detected", "Read Card Info...")
                    emvTrans(amount, cashOutAmountString, isEnableContact, isEnableContactless, EmvChannelType.FROM_PICC)
                }

                @Throws(RemoteException::class)
                override fun onTimeout() {
                    logEmv("Search Card", "onTimeOut")
                    endEMV()
                }

                @Throws(RemoteException::class)
                override fun onCanceled() {
                    logEmv("Search Card", "onCanceled")
                    endEMV()
                }

                @Throws(RemoteException::class)
                override fun onError(code: Int) {
                    logEmv("Search Card", "onError: $code")
                    endEMV()
                }
            })
        } catch (ex: Exception) {
            logEmv("Exception", ex.message ?: "-")
            endEMV()
        }
    }

    @Throws(RemoteException::class)
    private fun emvTrans(amount: String, cashOutAmount: String, isEnableContact: Boolean, isEnableContactless: Boolean, channel: Int) {
        logEmv("START EMV TRANS")
        startTick = System.currentTimeMillis()

        DeviceHelper.getDeviceService().login(Bundle(), "00000000")
        DeviceHelper.getEmvHandler().initTermConfig(EmvUtil.getInitTermConfig())
        val ret = DeviceHelper.getEmvHandler().emvProcess(EmvUtil.getTransBundle(amount, cashOutAmount, channel, isEnableContact, isEnableContactless), object : OnEmvProcessListener.Stub() {
            @Throws(RemoteException::class)
            override fun onSelApp(appNameList: List<String>, isFirstSelect: Boolean) {
                logEmv("onSelApp")
                selApp(appNameList)
            }

            @Throws(RemoteException::class)
            override fun onConfirmCardNo(cardNo: String) {
                logEmv("onConfirmCardNo:$cardNo")
                logEmv("time = " + (System.currentTimeMillis() - startTick) + "ms")
                DeviceHelper.getEmvHandler().onSetConfirmCardNoResponse(true)
                //DeviceHelper.getEmvHandler().onSetConfirmCardNoResponse(false)
            }

            /**
             *
             * @param isOnlinePin
             * @param offlinePinType  3:offline pin normal 2:offline pin again 1:offline pin last
             * @throws RemoteException
             */
            @Throws(RemoteException::class)
            override fun onCardHolderInputPin(isOnlinePin: Boolean, offlinePinType: Int) {
                logEmv("onCardHolderInputPin isOnlinePin:$isOnlinePin, Paymethod:$payMethod")
                logEmv("time = ${(System.currentTimeMillis() - startTick)} ms")
                logEmv("9F34 : ${EmvUtil.getPbocData("9F34", true)}")
                val cardNo: String = EmvUtil.readPan()

                if (!isOnlinePin && pinCancel) {
                    logEmv("Manual Cancel Transaction")
                    DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, Bundle())
                    return
                }

                if (isOnlinePin) {
                    getPin(true, cardNo)
                } else {
                    getPin(false, cardNo)
                }
                logEmv("onGetCardHolderInputPin???")

                try {
                    DeviceHelper.getEmvHandler().onSetCardHolderInputPin(HexUtil.hexStringToByte(mPinNum))
                } catch (e: RemoteException) {
                    e.printStackTrace()
                    logEmv("OnlinePin Exception $e")
                }
            }

            @Throws(RemoteException::class)
            override fun onPinPress(keyCode: Byte) {
                logEmv("Callback:onPinPress")
            }

            @Throws(RemoteException::class)
            override fun onDisplayOfflinePin(retCode: Int) {
                logEmv("Callback:onDisplayOfflinePin: $retCode,is succeed: ${(retCode == 0)}")
            }

            @Throws(RemoteException::class)
            override fun inputAmount(type: Int) {
                logEmv("Callback:inputAmount ")
                try {
                    DeviceHelper.getEmvHandler().onSetInputAmountResponse("0.3")
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }

            @Throws(RemoteException::class)
            override fun onGetCardResult(retCode: Int, bundle: Bundle) {
            }

            @Throws(RemoteException::class)
            override fun onDisplayMessage() {
                logEmv("CallBack:onDisplayMessage")
                DeviceHelper.getEmvHandler().onSetConfirmDisplayMessage(0)
            }

            @Throws(RemoteException::class)
            override fun onUpdateServiceAmount(serviceRelatedData: String) {
                logEmv("CallBack:onUpdateServiceAmount")
            }

            @Throws(RemoteException::class)
            override fun onCheckServiceBlackList(pan: String, amount: String) {
                logEmv("CallBack:onCheckServiceBlackList")
            }

            @Throws(RemoteException::class)
            override fun onGetServiceDirectory(directory: ByteArray) {
                DeviceHelper.getEmvHandler().onGetServiceDirectory(0)
            }

            @Throws(RemoteException::class)
            override fun onRupayCallback(type: Int, bundle: Bundle) {
                //val data = bundle.getByteArray(EmvRupayCallback.RUPAY_DATA_OUT)
                val ret = Bundle()
                ret.putInt(EmvRupayCallback.KEY_RET_CODE, 0)
                DeviceHelper.getEmvHandler().onSetRupayCallback(type, ret)
            }

            /**
             * Added by YSDK 6.14 (absent in 6.05). The kernel asks whether the terminal wants to
             * intervene at a particular kernel/flow step; KEY_RET_CODE 0 means "not executed",
             * 1 means "apply the parameter update I am returning".
             *
             * We answer 0 unconditionally. That reproduces exactly how the kernel behaved on 6.05,
             * where this callback did not exist, so upgrading the jar cannot change EMV outcomes.
             * The kernel waits for onSetEmvKernelCallback, so it must always be answered -- an
             * empty body would stall the transaction.
             *
             * kernelType/flowStep are logged so that if we later want to use this (e.g. the
             * Mastercard pre-auth DF8126 tag, or the contactless multi-AID list the demo shows),
             * we can see which steps this fleet's cards actually reach.
             */
            @Throws(RemoteException::class)
            override fun onEmvKernelCallback(kernelType: Int, flowStep: Int, bundle: Bundle) {
                logEmv("Callback:onEmvKernelCallback kernelType=$kernelType flowStep=$flowStep")
                val ret = Bundle()
                ret.putInt(EmvKernelCallback.KEY_RET_CODE, 0)
                DeviceHelper.getEmvHandler().onSetEmvKernelCallback(ret)
            }

            @RequiresApi(Build.VERSION_CODES.O)
            @Throws(RemoteException::class)
            override fun onOnlineProc(data: Bundle) {
                logEmv("Callback:onOnlineProc")
                if (pinCancel || payMethod.toInt() == TerminalConstants.paymentMethod.Non) {
                    logEmv("Manual Cancel Transaction")
                    DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, Bundle())
                    return
                }
                logEmv("time = ${(System.currentTimeMillis() - startTick)}ms")
                //TODO ISO_SERVER
                val isIsoServer = false /*temporary hardcoded */
                if(isIsoServer) onlineProcIsoServer() else onlineProc()
            }

            @Throws(RemoteException::class)
            override fun onContactlessOnlinePlaceCardMode(mode: Int) {
                logEmv("Callback:onContactlessOnlinePlaceCardMode")
                if (mode == EmvListenerConstrants.NEED_CHECK_CONTACTLESS_CARD_AGAIN) {
                    /*startSearchContractLess(object : OnSearchIccCardListener.Stub() {
                        @Throws(RemoteException::class)
                        override fun onSearchResult(retCode: Int, bundle: Bundle) {
                            stopSearch()
                            try {
                                DeviceHelper.getEmvHandler().onSetContactlessOnlinePlaceCardModeResponse(ServiceResult.Success == retCode)
                            } catch (e: RemoteException) {
                                e.printStackTrace()
                            }
                        }
                    })*/
                    DeviceHelper.getEmvHandler().onSetContactlessOnlinePlaceCardModeResponse(true)
                } else {
                    //show Dialog Prompt the user not to remove the card
                    DeviceHelper.getEmvHandler().onSetContactlessOnlinePlaceCardModeResponse(true)
                }
            }

            @RequiresApi(Build.VERSION_CODES.O)
            @Throws(RemoteException::class)
            override fun onFinish(retCode: Int, data: Bundle) {
                logEmv("Callback: onFinish")
                logEmv("time = ${(System.currentTimeMillis() - startTick)}ms")
                logEmv("cvm_flag: ${data.getInt(EmvOnlineRequest.CVM_FLAG)}")
                logEmv("CVM_SIGNATURE: ${data.getBoolean(EmvOnlineRequest.CVM_SIGNATURE)}")
                emvFinish(retCode, data)
            }

            @Throws(RemoteException::class)
            override fun onCertVerify(certName: String, certInfo: String) {
                logEmv("Callback:onCertVerify")
                DeviceHelper.getEmvHandler().onSetCertVerifyResponse(true)
            }

            @Throws(RemoteException::class)
            override fun onSetAIDParameter(aid: String) {
                logEmv("Callback:onSetAIDParameter :: $aid")
            }

            @Throws(RemoteException::class)
            override fun onSetCAPubkey(rid: String, index: Int, algMode: Int) {
                logEmv("Callback:onSetCAPubkey")
            }

            @Throws(RemoteException::class)
            override fun onTRiskManage(pan: String, panSn: String) {
                logEmv("Callback:onTRiskManage")
            }

            @Throws(RemoteException::class)
            override fun onSelectLanguage(language: String) {
                logEmv("Callback:onSelectLanguage")
            }

            @Throws(RemoteException::class)
            override fun onSelectAccountType(accountTypes: List<String>) {
                logEmv("Callback:onSelectAccountType")
            }

            @Throws(RemoteException::class)
            override fun onIssuerVoiceReference(pan: String) {
                logEmv("Callback:onIssuerVoiceReference")
            }
        })

        if (ret != 0) {
            logEmv("EMV INIT ERROR: $ret")
            endEMV()
        }
    }

    private fun getPin(isOnline: Boolean, cardNo: String) {
        pinRequired = true
        pinWait = true
        runOnUiThread {
            MfHelper.lockStatusBarAndNavigation(true)
            val newCardNo = cardNo.replace("F", "");
            val pinPad: PinPad = if (isOnline) {
                PinPad(
                    tempContext,
                    newCardNo,
                    PinPad.ISO.ISO_0
                )
            } else {
                PinPad(
                    tempContext,
                    newCardNo,
                    PinPad.ISO.ISO_2
                )
            }
            pinPad.getPin(object : PinPadListener {
                @RequiresApi(Build.VERSION_CODES.O)
                override fun onReadPinSuccess(pinBlock: String) {
                    //TODO ISO_SERVER pin block
                    MfHelper.lockStatusBarAndNavigation(false)
                    if (isOnline) {
                        try {
                            mPinNum = if (pinBlock.isEmpty()) {
                                pinBlock
                            } else {
                                val acqName = ServiceHolder.getAcquirerSetting().acqName
                                val secureLabel = acqName.substring(0,1) + acqName.substring(1).lowercase()

                                val salesModel = selectedCacheModel as SalesModel?
                                val currMid = salesModel?.AcqMid ?: ""
                                val currTid = salesModel?.AcqTid ?: ""
                                logEmv("currMid=$currMid, currTid=$currTid")
                                var encKey = SecureDataRepo.getDecryptedSingle(tempContext, listOf("tag", "subtag"), listOf("eTpkKey", "visam-tle$secureLabel-$currMid"))?.value ?: ""

                                if (acqName.uppercase() == "BSN") {
                                    try {
                                        val hexTpkKey = HexUtil.hexStringToByte(encKey)
                                        val ksnDetails = getSpecificKsn(tempContext, ArrayList(listOf("AcqMid", "AcqTid")), arrayOf(currMid, currTid))
                                        val pinBlockKsn = ksnDetails?.PinKsn

                                        val dukpt: ByteArray? = pinDukptDeriv(hexTpkKey, pinBlockKsn ?: "")
                                        encKey = Dukpt.toHex(dukpt)
                                    } catch (e: java.lang.Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                val encPinBlock = Encryption.encrypt(pinBlock, encKey, "DESede", "CBC")
                                logEmv("EncPinBlock=$encPinBlock")
                                encPinBlock
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        mPinNum = if (pinBlock.isEmpty()) {
                            pinBlock
                        } else {
                            Utility.ASCIItoHexString(pinPad.pinBlockDecode(pinBlock, cardNo))
                        }
                    }

                    logEmv("onReadPinSuccess: $pinBlock")
                    logEmv("onReadPinSuccess mPinNum: $mPinNum")
                    //TODO Testing FNX
                    if(isOnline) {
                        TransData.encPinBlock = mPinNum ?: ""
                        if (TransData.encPinBlock.isNotEmpty()){
                            TransData.onlinePinInput = true
                        }
                    }
                    pinWait = false
                }

                override fun onReadPinCancel() {
                    logEmv( "onReadPinCancel -------")
                    MfHelper.lockStatusBarAndNavigation(false)

                    //TransData.respCode = "4535"
                    pinWait = false
                    pinCancel = true
                }

                override fun onReadingPin(len: Int, pin: String) {
                    logEmv("onReadingPin: $pin----$len")
                }
            })
        }

        while (pinWait) {
            Utils.DelayMili(100)
        }
    }

    private fun selApp(appList: List<String>) {
        val options = arrayOfNulls<String>(appList.size)
        var forceSelect: Int = -1
        for (i in appList.indices) {
            val splitString = appList[i].split("|")

            // Comment For MCCS Certification
            if(isOptIn) {
                var checkValue = splitString[0]
                if(splitString.size > 1) {
                    checkValue = splitString[1]
                }

                try {
                    if(arrayOf("A000000615").contains(checkValue.substring(0,10))) {
                        forceSelect = i
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
            options[i] = splitString[0]
        }

        if(forceSelect != -1) {
            try {
                DeviceHelper.getEmvHandler().onSetSelAppResponse(forceSelect)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        } else {
            //Only Uncomment for MCCS Cert
//            if (isOptIn) {
//                try {
//                    DeviceHelper.getEmvHandler().onSetSelAppResponse(-1)
//                } catch (e: RemoteException) {
//                    e.printStackTrace()
//                }
//            } else {
                runOnUiThread(Runnable {
                    val alertBuilder = AlertDialog.Builder(tempContext)
                    alertBuilder.setTitle("PLEASE SELECT APP")
                    alertBuilder.setItems(options) { dialogInterface, index ->
                        try {
                            DeviceHelper.getEmvHandler().onSetSelAppResponse(index)
                        } catch (e: RemoteException) {
                            e.printStackTrace()
                        }
                    }
                    alertBuilder.setCancelable(false)
                    val alertDialog1 = alertBuilder.create()
                    alertDialog1.show()
                })
//            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Throws(RemoteException::class)
    private fun emvFinish(ret: Int, bundle: Bundle) {
        var resultFinish = false
        logEmv("emvFinish: $ret --> ${HexUtil.bytesToHexString(bundle.getByteArray(EmvErrorConstrants.EMV_ERROR_CODE))}")
        //Utils.printLog("emvFinish: $ret --> ${bundle.getByteArray(EmvErrorConstrants.EMV_ERROR_CODE)}")
        val errorCode = bundle.getByteArray(EmvErrorConstrants.EMV_ERROR_CODE)

        if (ret == ServiceResult.Success) { //trans accept
            //onFinishShow(bundle)
            logEmv("emvFinish: Success")
            if (payMethod.toInt() == TerminalConstants.paymentMethod.ICC) {
                IsoActivity.processTcUpload(tempContext, tempHelperLog)
            }
        } else if (ret == ServiceResult.Emv_FallBack) { // fallback
            logEmv("emvFinish: Emv_FallBack")
            //TODO
            // cube!!.tlv_remove_tag(TerminalConstants.cube.CUBE_TAG_RESPCODE)
            // cube!!.tlv_add_by_tv_in_string(TerminalConstants.cube.CUBE_TAG_RESPCODE, Utils.ASCIItoHexString("ZX"))
        } else if (ret == ServiceResult.Emv_Terminate) { // trans end
            logEmv("emvFinish: Emv_Terminate")
            if (errorCode != null) {
                logEmv("Error Code: ${String(errorCode).trim { it <= ' ' }}")
                //TODO if the amount of connect less transactions is more than 2,0000. The interface prompts you to swipe or insert a card.
                if (DeviceHelper.getEmvHandler().isErrorCode(EmvErrorCode.QPBOC_ERR_PRE_AMTLIMIT)) {
                    logEmv("RF Limit Exceed, Pls Try Another Page!")
                } else if (DeviceHelper.getEmvHandler().isErrorCode(EmvErrorCode.EMV_ERR_INITAPP_GETOP)) {
                    logEmv("VISA Read Card Error!")
                } else if (DeviceHelper.getEmvHandler().isErrorCode(EmvErrorCode.EMV_ERR_SELAPP_APPLOCK)) {
                    logEmv("Card APP Lock!")
                }
            }
        } else if (ret == ServiceResult.Emv_Declined) { // trans refuse
            //TODO Please noted android time is correct.
            logEmv("emvFinish: Emv_Declined")
            if (errorCode != null) {
                logEmv("ERROR CODE: " + String(errorCode).trim { it <= ' ' })
            }
            //TODO ISO_SERVER Do reversal

            //TODO Reversal
            val respCode = Utility.HexString2ASCII(TransData.respCode)
            if(respCode == "00"){
                TransData.transResult = TerminalConstants.iso.err.failed
                TransData.respCode = Utils.ASCIItoHexString("ZW")
                TransData.prevInvoice = TransData.invoiceNo
                TransData.prevStan = TransData.stan

                val isNotCompl = booleanArrayOf(true)
                ServiceHolder.isoComm = null
                isNotCompl[0] = true
                object : Thread() {
                    override fun run() {
                        super.run()
                        var loop = 0
                        val maxLoop = 3
                        while (loop < maxLoop) {
                            loop++
                            pDTitle = "Reversal ($loop)"
                            runOnUiThread(changeTitle)

                            val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "reversal")
                            acquirerRevIsoModel?.let { revIsoModel ->
                                val allIsoString = HexUtil.bytesToHexString(TransData.transactionDb, 0, TransData.transactionDbLen + 2)
                                val result = IsoActivity.processReversal(tempContext, true, revIsoModel, allIsoString, true, tempHelperLog)
                                tempHelperLog.appendLine(logClassName, "reversal result :: $result")
                                if(result != null){
                                    loop = maxLoop // used for exit
                                }
                            }
                        }
                        isNotCompl[0] = false
                    }
                }.start()
                while (isNotCompl[0]) {
                    if (ServiceHolder.isoComm != null) {
                        pDMsg = ServiceHolder.isoComm!!.connectionStatus
                        if (pDMsg != null) {
                            if (pDMsg!!.isNotEmpty()) {
                                runOnUiThread(changeMessage)
                            }
                        }
                    }
                    Utils.DelayMili(100)
                }
                //TODO Reversal
            }
        } else if (ret == ServiceResult.Emv_TryAgain) {
            logEmv("emvFinish: Emv_TryAgain")
            //Master need support
            val retCode = bundle.getInt(EmvErrorConstrants.EMV_GOTO_CODE, 0)
            if (retCode == GoToConstants.GOTO_TRY_AGAIN_CARD || retCode == GoToConstants.GOTO_TRY_AGAIN_MOBILE) {
                logEmv("Please call contactless search ")
                resultFinish = true
                runOnUiThread {
                    Toast.makeText(tempContext, "Search Card, Please Tap Longer or Insert Card", Toast.LENGTH_SHORT).show()
                    try {
                        //searchCard(mAmount, mOptData, timeout_cardSearch, tempContext, true)
                        //searchCard(mAmount, mOptData, timeout_cardSearch, tempContext, false) //temp for Mastercard Cert
                    } catch (e: RemoteException) {
                        e.printStackTrace();
                    }
                }
                /*startSearchContractLess(object : OnSearchIccCardListener.Stub() {
                    @Throws(RemoteException::class)
                    override fun onSearchResult(retCode: Int, bundle: Bundle) {
                        stopSearch()
                        //TODO do next transaction
                    }
                })*/
            }
        } else if (ret == ServiceResult.Emv_TryOtherPage) {
            logEmv("emvFinish: Emv_TryOtherPage")
            val retCode = bundle.getInt(EmvErrorConstrants.EMV_GOTO_CODE, 0)
            if (retCode == -8) {
                logEmv("PLEASE DIP, SWIPE OR TRY ANOTHER CARD")
            } else if (retCode == -9) {
                logEmv("PLEASE DIP, SWIPE CARD")
            } else {
                logEmv("EMV TRY OTHER PAGE:$retCode")
            }
        } else if (ret == ServiceResult.Emv_Cancel) {
            logEmv("emv cancel")

        } else {
            logEmv("Other trans result")
        }

        CoroutineScope(Dispatchers.IO).launch {
            updateReceiptInfo(tempContext)
        }
        closeProgressDialog()
        endEMV()

        // EMV kernel flow ends here -- emit it as its own block. Without this, everything this
        // class appended only ever reached disk as part of CardPaymentActivity's block, so an
        // EMV failure that never got back to CardPayment left no grouped record at all.
        if (this::tempHelperLog.isInitialized) {
            tempHelperLog.appendLine(logClassName, "EMV process finished :: ret=$ret")
            tempHelperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Throws(RemoteException::class)
    private fun onlineProcIsoServer() {
        Utils.playSound("success.wav")
        logEmv( "onlineProcIsoServer: $payMethod")

        //TODO hardcode TransScheme
        val schemeTag = "visam"

        val builder = java.lang.StringBuilder()
        var arqcTlv = EmvUtil.getTLVDatas(EmvUtil.arqcTLVTags)
        builder.append(EmvUtil.getTLVDatas(EmvUtil.arqcTLVTags))
        if (!TlvDataList.fromBinary(arqcTlv).contains("9F27")) {
            builder.append(TlvData.fromData("9F27", byteArrayOf(0x80.toByte())))
        }
        val track2 = EmvUtil.readTrack2()
        logEmv("TAG: Track2 -> ${EmvUtil.readTrack2()}")
        if (track2.length >= 38) {
            logEmv("Track 2 length more than 37. Declined")
            val online = Bundle()
            online.putString(EmvOnlineResult.REJCODE, "05")
            DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Declined, online)
            return
        }

        val stringAid = EmvUtil.getPbocData("4F", true)
        val cardScheme = CardUtil.getCardTypFromAid(stringAid)
        val schemeId = TerminalConstants.iso.isoInfo.getSchemeId(TransData.schemeType, TransData.payMethod, TransData.acqCode)

        //TODO Obtain DF22
        val strSchemeTagWithSchemeId = "$schemeTag-$schemeId"
        val strPosEntryMode = IsoBatchInfoRepo.getBatchInfo(tempContext, "posEntryMode", strSchemeTagWithSchemeId)?.let {
            tempHelperLog.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithSchemeId")
            it.value
        } ?: run {
            //MTI not at app level cannot obtain schemeTag with MTI
            /*val strSchemeTagWithMti = "${TransData.schemeTag}-${isoInfoModel.mti}"
            IsoBatchInfoRepo.getBatchInfo(context, "posEntryMode", strSchemeTagWithMti)?.let {
                println("Obtained PosEntry -> $strSchemeTagWithMti")
                log.appendLine(logClassName, "Obtained PosEntry -> $strSchemeTagWithMti")
                it.value
            } ?: run{

            }*/
            IsoBatchInfoRepo.getBatchInfo(tempContext, "posEntryMode", schemeTag)?.let {
                tempHelperLog.appendLine(logClassName, "Obtained PosEntry -> ${TransData.schemeTag}")
                it.value
            } ?: run { "" }
        }
        println("strPosEntryMode -> $strPosEntryMode")
        //TODO Obtain DF22

        //TODO Track3
        var tvr = EmvUtil.getPbocData("95", true)
        var override95 = false
        val acqName = ServiceHolder.getAcquirerSetting().acqName
        if (acqName.uppercase() == "BSN") {
            if (tvr == "0000000000" && pinRequired){
                if (mPinNum.isNullOrEmpty()) {
                    tempHelperLog.appendLine(logClassName, "Override TVR")
                    override95 = true
                    tvr = "0400088000"
                }
            }
        }
        var de55 = EmvUtil.getTLVDatas_1()
        logEmv("de55 -> $de55")
        if(override95){
            de55 = de55.replace("95050000000000", "9505$tvr")
        }
        val track3Byte = de55.toByteArray()
        track3Byte.copyInto(TransData.track3, 0, 0, track3Byte.size)

        //TODO ISO_SERVER Do you online submission

        // Pack data and send back to contact card
        logEmv("CUBE:EMVCT: Proceed Card ARQC2 Processing...")
        var strRespCodeAsc = "96"
        var strDe55 = "8A023936"
        //val strRespCode1 = TransData.getFromTransactionDb("BF39", 16)
        val strChipData = TransData.getFromTransactionDb("BF55", 16)
        if (TransData.respCode.isNotEmpty()) {
            //val bRespCode = HexUtil.hexStringToByte(strRespCode1)
            //isos!!.addTlvByTv("8A", HexUtil.hexStringToByte(strRespCode1), 0, bRespCode.size)
            strRespCodeAsc = Utility.HexString2ASCII(TransData.respCode)
            strDe55 = "8A02${TransData.respCode}"
        }
        logEmv("1. strDe55=$strDe55")
        //TODO Hardcoded for end emv
        val onlineTemp = Bundle()
        onlineTemp.putString(EmvOnlineResult.REJCODE, "05")
        DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Declined, onlineTemp)
        //TODO Hardcoded for end emv
        //TODO ISO_SERVER Do reversal???


        //TODO ISO_SERVER Pack Data and Submit Script to Acquirer
        val online = Bundle()
        //TODO onlineRespCode is DE 39—RESPONSE CODE, detail see ISO8583
        val arpcData = HexUtil.hexStringToByte(strDe55)
        online.putString(EmvOnlineResult.REJCODE, strRespCodeAsc)
        online.putByteArray(EmvOnlineResult.RECVARPC_DATA, arpcData)
        DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Success, online)
        val tlv1 = EmvUtil.getTLVDatas(EmvUtil.tags)
        logEmv("onlineProc: $tlv1")
        logEmv("ISOENGINE:ProcessTxn. Resp= ${TransData.transResult}")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Throws(RemoteException::class)
    private fun onlineProc() {
        logEmv( "onlineProc: $payMethod")

        val builder = java.lang.StringBuilder()
        val arqcTlv = EmvUtil.getTLVDatas(EmvUtil.arqcTLVTags)
        builder.append(EmvUtil.getTLVDatas(EmvUtil.arqcTLVTags))
        if (!TlvDataList.fromBinary(arqcTlv).contains("9F27")) {
            builder.append(TlvData.fromData("9F27", byteArrayOf(0x80.toByte())))
        }

        val track2 = EmvUtil.readTrack2()
        logEmv("TAG: Track2 -> $track2")
        if(TransData.salesType == 4) {
            logEmv("Do Card Verification Checking for PreAuth and Sales Completion")
            val fullPan = EmvUtil.getPanFromTrack2() ?: ""
            val reqPan = TransData.reqCardPan.replace("F", "")

            if(fullPan.isEmpty() || reqPan.isEmpty() || fullPan != reqPan) {
                logEmv("Card Verification Failed :: presented card is not the pre-auth card")
                TransData.transResult = TerminalConstants.iso.err.txnNotAllowed
                TransData.respCode = Utils.ASCIItoHexString("ZR")
                val online = Bundle()
                online.putString(EmvOnlineResult.REJCODE, "05")
                DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Declined, online)
                return
            }
        }

        Utils.playSound("success.wav")
        val tlv = EmvUtil.getTLVDatas(EmvUtil.tags)
        logEmv("onlineProc: $tlv")

        //ForcePin for Cashout txn
        if (TransData.salesType == ProductCatSelectionDataEnum.CASH_OUT.data.SalesType && mPinNum == null){
            pinWait = true
            logEmv("Cashout Online Pin $payMethod $pinWait")
            getPin(true, EmvUtil.readPan())
            if (pinCancel) {
                val online = Bundle()
                DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, online)
                return
            }
        }

        val txnDt = "20" + EmvUtil.getCurrentTime("yyMMddHHmmss")
        TransData.transDateAsci = txnDt
        logEmv("TAG: txnDt -> $txnDt")
        TransData.transStartDate = HelperCommon.getDateString(EnumDateFormat.yyyyMMddHHmmssSSS.dateFormat)

        val stringAid = EmvUtil.getPbocData("4F", true)
        TransData.schemeType = CardUtil.getCardTypFromAid(stringAid)
        TransData.aid = stringAid
        tempHelperLog.appendLine(logClassName, "AID :: $stringAid")
        TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_AID, TransData.aid)

        TransData.schemeId = TerminalConstants.iso.isoInfo.getSchemeId(TransData.schemeType, TransData.payMethod, TransData.acqCode)
        tempHelperLog.appendLine(logClassName, "schemeType[${TransData.schemeType}]; schemeId[${TransData.schemeId}]")
        TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_SCHEME_ID, TransData.schemeId)

        var appLabel = EmvUtil.getPbocData("50", true) ?: ""
        if(appLabel.isEmpty()) {
            tempHelperLog.appendLine(logClassName, "appLabel is empty take label from schemeType")
            appLabel = Utils.ASCIItoHexString(TransData.schemeType)
        }
        if(TransData.aid.contains("A000000615",true)) {
            appLabel = Utils.ASCIItoHexString("MYDEBIT")
        }

        val appLabelByte = Utils.hexStringToByteArray(appLabel)
        if(appLabelByte != null){
            appLabelByte.copyInto(TransData.appLabel, 0, 0, appLabelByte.size)
            TransData.appLabelLen = appLabelByte.size
            TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_APPLABEL, appLabel)
        }

        val arqc = EmvUtil.getPbocData("9F26", true)
        TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_ARQC, arqc)

        var cvm: String? = EmvUtil.getPbocData("9F34", true)
        tempHelperLog.appendLine(logClassName, "onlineProc -> -$mPinNum-")
        tempHelperLog.appendLine(logClassName, "onlineProc_cvm -> $cvm--")

        //TODO Revamp
        if(cvm.isNullOrEmpty()) {
            cvm = if (TransData.onlinePinInput){
                "020302"
            } else if (TransData.offlinePinInput){
                "010302"
            } else {
                "1F0302"
            }
        }

        //TODO Testing
        if(TransData.amount > cvmLimit) {
            /*if(!(TransData.onlinePinInput || TransData.offlinePinInput)) {
                cvm = "1E" + cvm.substring(2)
            } else if (cvm.substring(0, 2) == "1F" && (TransData.onlinePinInput || TransData.offlinePinInput)) {
                cvm = "42" + cvm.substring(2)
            }*/
            if ((cvm.substring(1, 2) == "1" || cvm.substring(1, 2) == "2") && !(TransData.onlinePinInput || TransData.offlinePinInput)) {
                println("Modifying CVM from pin verified to unverified")
                cvm = "1F" + cvm.substring(2)
            }
        }
        //TODO Revamp
        /*if(cvm == null) {
            cvm = "1F0302"
            //cvm = "1E0300"
        } else if (cvm.isEmpty()) {
            cvm = if (TransData.onlinePinInput){
                "020302"
            } else if (TransData.offlinePinInput){
                "010302"
            } else {
                "3F0302"
            }
        }
        //TODO KIV
        if (cvm[1] == '2' && mPinNum == null) {
            cvm = "1E0302"
        }
        if (cvm[1] == '2' && mPinNum == null && TransData.amount < cvmLimit) {
            cvm = "1F0302"
        }*/
        tempHelperLog.appendLine(logClassName, "onlineProc_cvm_m -> $cvm")
        TransData.cvm = cvm
        TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_CVM, cvm)

        var tvr = EmvUtil.getPbocData("95", true)
        var override95 = false
        val acqName = ServiceHolder.getAcquirerSetting().acqName
        if (acqName.uppercase() == "BSN") {
            if (tvr == "0000000000" && pinRequired){
                if (mPinNum.isNullOrEmpty()) {
                    tempHelperLog.appendLine(logClassName, "Override TVR")
                    override95 = true
                    tvr = "0400088000"
                }
            }
        }
        TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_TVR, tvr)
        tempHelperLog.appendLine(logClassName, "TVR :: $tvr")

        when (TransData.payMethod) {
            TerminalConstants.paymentMethod.ICC -> {
                TransData.entryModeLabel = "Contact"
                TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_ENTRY_MODE, Utils.ASCIItoHexString("Contact"))
            }
            TerminalConstants.paymentMethod.RF -> {
                TransData.entryModeLabel = "Contactless"
                TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_ENTRY_MODE, Utils.ASCIItoHexString("Contactless"))
            }
            TerminalConstants.paymentMethod.Meg -> {
                TransData.entryModeLabel = "MagStripe"
                TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_ENTRY_MODE, Utils.ASCIItoHexString("MagStripe"))
            }
            else -> {
                TransData.entryModeLabel = " "
                TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_ENTRY_MODE, Utils.ASCIItoHexString(" "))
            }
        }
        tempHelperLog.appendLine(logClassName, "Entry Label :: ${TransData.entryModeLabel}")

        // Form LEVEL 3 data
        if (EmvUtil.readTrack2().length >= 38) {
            logEmv("Track 2 length more than 37. Declined")
            val online = Bundle()
            online.putString(EmvOnlineResult.REJCODE, "05")
            DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Declined, online)
            return
        }

        var de55 = CardTagsEnum.getAcquirerChipTags(TransData.acqCode, TransData.schemeType)?.let {
            EmvUtil.getTLVDatas(it)
        } ?: run {
            EmvUtil.getTLVDatas_1()
        }
        logEmv("Before de55 -> $de55")
        if(override95){
            de55 = de55.replace("95050000000000", "9505$tvr")
        }
        logEmv("After de55 -> $de55")
        val track3Byte = de55.toByteArray()
        track3Byte.copyInto(TransData.track3, 0, 0, track3Byte.size)
        TransData.track3Len = track3Byte.size

        pDTitle = "Bank Authorization"
        runOnUiThread(changeTitle)
        pDMsg = "Waiting for Approval"
        runOnUiThread(changeMessage)
        tempHelperLog.appendLine(logClassName, "onlineProc: ${TransData.payMethod}")
        tempHelperLog.logToFile(EnumLogFileName.TerminaLog)

        if (payMethod.toInt() == TerminalConstants.paymentMethod.ICC) {
            if (pinCancel) {
                val online = Bundle()
                DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, online)
                return
            }
        }
        val isNotCompl = booleanArrayOf(true)
        ServiceHolder.isoComm = null
        TransData.transactionDb.copyInto(TransData.transactionDbBak, 0, 0, TransData.transactionDb.size)
        TransData.transactionDbLenBak = TransData.transactionDbLen
        var count = 1

        object : Thread() {
            override fun run() {
                super.run()
                do {
                    /*if (TransData.transResult == TerminalConstants.iso.err.txnDeclined_pinNeeded) {
                        count--
                        TransData.resetTransactionDbFromBak()
                        val cardNo: String = EmvUtil.readPan()
                        getPin(true, cardNo)
                        cvm = if (TransData.onlinePinInput){
                            "020302"
                        } else if (TransData.offlinePinInput){
                            "010302"
                        } else {
                            "3F0302"
                        }

                        TransData.cvm = cvm.toString()
                        TransData.removeTlvFromTransDb(TerminalConstants.cube.CUBE_TAG_CARD_CVM)
                        TransData.addHexStrIntoTransDB(TerminalConstants.cube.CUBE_TAG_CARD_CVM, TransData.cvm)
                    }*/
                    // D8 -- hold the host guard across the whole flow, so a back press cannot
                    // blank stan/invoiceNo/respCode after the approval arrives but before it is
                    // persisted. sendToHost raises the same counter again inside.
                    IsoActivity.withHostRequest {
                        when (TransData.salesType) {
                            8 -> {IsoActivity.processPreauth(tempContext, tempHelperLog)}
                            4 -> {IsoActivity.processSaleCompCardPresented(tempContext, tempHelperLog)}
                            ProductCatSelectionDataEnum.CASH_OUT.data.SalesType -> {IsoActivity.processCashOutSale(tempContext, tempHelperLog)}
                            ProductCatSelectionDataEnum.EPP.data.SalesType -> {IsoActivity.processEppSale(tempContext, tempHelperLog)}
                            else -> {IsoActivity.processOnlineSale(tempContext, tempHelperLog)}
                        }
                    }
                } while (TransData.transResult == TerminalConstants.iso.err.txnDeclined_pinNeeded && count != 0)
                tempHelperLog.appendLine(logClassName, "onlineProc: Bank waiting done")
                isNotCompl[0] = false
            }
        }.start()
        while (isNotCompl[0]) {
            if (ServiceHolder.isoComm != null) {
                pDMsg = ServiceHolder.isoComm!!.connectionStatus
                if (pDMsg != null) {
                    if (pDMsg!!.isNotEmpty()) {
                        runOnUiThread(changeMessage)
                    }
                }
            }
            Utils.DelayMili(100)
        }

        // Pack data and send back to contact card
        logEmv("CUBE:EMVCT: Proceed Card ARQC2 Processing...")
        var strRespCodeAsc = "96"
        var strDe55 = "8A023936"
        val strChipData = TransData.getFromTransactionDb("BF55", 16)
        if (TransData.respCode.isNotEmpty()) {
            strRespCodeAsc = Utility.HexString2ASCII(TransData.respCode)
            strDe55 = "8A02${TransData.respCode}"
        }
        logEmv("1. strDe55=$strDe55")
        if (strChipData.isNotEmpty()) {
            strDe55 += strChipData
        }
        logEmv("strRespCodeAsc::$strRespCodeAsc")
        logEmv("2. strDe55=$strDe55")
        // Pack data and send back to contact card

        //Reversal //Skip Reversal for MyDebit Preauth
        var preAuthReversal = true
        if(TransData.salesType == 8 && TransData.schemeType.equals("MCCS", true)) {
            preAuthReversal = false
        }

        if (preAuthReversal) {
            // txnNotAllowed means StorageGuard refused the sale before any host request was formed.
            // There is nothing at the host to reverse, so the 3-attempt loop below would send
            // reversals for a transaction that never existed. Only StorageGuard sets this code.
            if(TransData.transResult != TerminalConstants.iso.err.txnApproved && TransData.transResult != TerminalConstants.iso.err.txnNotAllowed && (TransData.transResult == TerminalConstants.iso.err.communicationTimeout || TransData.respCode.isEmpty())) {
                ServiceHolder.isoComm = null
                isNotCompl[0] = true
                object : Thread() {
                    override fun run() {
                        super.run()
                        var loop = 0
                        val maxLoop = 3
                        val lastPostingDt = IsoBatchLongInfoRepo.getBatchLongInfo(tempContext, "postingDt", "last")
                        if(lastPostingDt != null) {
                            if(lastPostingDt.value.isEmpty()) {
                                IsoBatchLongInfoRepo.updateBatchLongInfo(tempContext, TransData.transDateAsci, "postingDt", "last")
                            }
                        }

                        while (loop < maxLoop) {
                            loop++
                            pDTitle = "Reversal ($loop)"
                            runOnUiThread(changeTitle)

                            val acquirerRevIsoModel = IsoHelperNew.getIsoHelperObject(TransData.acqCode, "reversal")
                            acquirerRevIsoModel?.let { revIsoModel ->
                                val allIsoString = HexUtil.bytesToHexString(TransData.transactionDb, 0, TransData.transactionDbLen + 2)
                                val result = IsoActivity.processReversal(tempContext, false, revIsoModel, allIsoString, false, tempHelperLog)
                                tempHelperLog.appendLine(logClassName, "reversal result :: $result")
                                if(result != null){
                                    loop = maxLoop // used for exit
                                }
                            }
                        }
                        isNotCompl[0] = false
                    }
                }.start()
                while (isNotCompl[0]) {
                    if (ServiceHolder.isoComm != null) {
                        pDMsg = ServiceHolder.isoComm!!.connectionStatus
                        if (pDMsg != null) {
                            if (pDMsg!!.isNotEmpty()) {
                                runOnUiThread(changeMessage)
                            }
                        }
                    }
                    Utils.DelayMili(100)
                }
            }
        }
        val online = Bundle()
        //TODO onlineRespCode is DE 39—RESPONSE CODE, detail see ISO8583
        val arpcData = HexUtil.hexStringToByte(strDe55)
        online.putString(EmvOnlineResult.REJCODE, strRespCodeAsc)
        online.putByteArray(EmvOnlineResult.RECVARPC_DATA, arpcData)
        DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Success, online)
        val tlv1 = EmvUtil.getTLVDatas(EmvUtil.tags)
        logEmv("onlineProc: $tlv1")
        logEmv("ISOENGINE:ProcessTxn. Resp= ${TransData.transResult}")
    }

    @Throws(RemoteException::class)
    private fun startSearchContractLess(listener: OnSearchIccCardListener.Stub) {
        rfReader = DeviceHelper.getIccCardReader(IccReaderSlot.RFSlOT)
        rfReader?.searchCard(listener, 60, arrayOf<String>(IccCardType.CPUCARD))
    }

    open fun stopSearch() {
        if (rfReader == null || iccCardReader == null) {
            return
        }
        try {
            iccCardReader?.stopSearch()
            rfReader?.stopSearch()
            magCardReader?.stopSearch()
            payMethod = TerminalConstants.paymentMethod.Cancel.toByte()
        } catch (e: RemoteException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            e.printStackTrace()
        }
    }

    open fun endEMV() {
        isNotEnd = false
        // Every EMV error path funnels here, but not all of them hide the progress
        // dialog. It is non-cancelable, so a missed hide freezes the terminal;
        // hideProgress() is a posted no-op when nothing is showing, making it safe
        // to call unconditionally.
        hideProgress()
        try {
            payMethod = TerminalConstants.paymentMethod.Non.toByte()
            //DeviceHelper.getEmvHandler().endPBOC()
            DeviceHelper.getEmvHandler().cancelCheckCard()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    open suspend fun resetEMV() {
        /*try {
            Utils.debugLogPrint("EmvActivity", "Reset EMV")
            val online = Bundle()
            DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, online)

            iccCardReader?.stopSearch()
            rfReader?.stopSearch()
            magCardReader?.stopSearch()
            payMethod = TerminalConstants.paymentMethod.Non.toByte()
            DeviceHelper.getEmvHandler().endPBOC()
            //DeviceHelper.getEmvHandler().cancelCheckCard()
        } catch (e: Exception) {
            e.printStackTrace()
        }*/
        try {
            logEmv("EmvActivity", "Reset EMV")
            logEmv("EmvActivity", "isNotEnd :: $isNotEnd")
            if(isNotEnd) {
                val online = Bundle()
                DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, online)
                DeviceHelper.getEmvHandler().cancelCheckCard()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Throws(RemoteException::class)
    open fun onFinishShow(bundle: Bundle) {
        val list = bundle.getStringArrayList(EmvProcessResult.EMVLOG)
        val builder = java.lang.StringBuilder()
        val tlv = EmvUtil.getTLVDatas(EmvUtil.tags)
        val tlvDataList = TlvDataList.fromBinary(tlv)

       /*builder.append("CARD NO:${EmvUtil.readPan()} \n")
        builder.append("CARD ORG:${CardUtil.getCardTypFromAid(EmvUtil.getPbocData("4F", true))} \n")
        builder.append("CARD TRACK 2:${EmvUtil.readTrack2()} \n")
        builder.append("CARD SN:${EmvUtil.getPbocData("5F34", true)} \n")

        if (list != null) {
            builder.append("CARD LOG:$list \n")
        }
        for (tag in EmvUtil.tags) {
            when {
                "9F4E".equals(tag, ignoreCase = true) -> {
                    builder.append("$tag=${tlvDataList.getTLV(tag)} \n")
                }
                "5F20".equals(tag, ignoreCase = true) -> {
                    builder.append("$tag=${tlvDataList.getTLV(tag)} \n")
                }
                else -> {
                    builder.append("$tag=${tlvDataList.getTLV(tag)} \n")
                }
            }
        }*/
        logEmv("Builder $builder")
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

    private fun startProgressDialog(title: String, msg: String) {
        startProgressDialog(tempContext, title, msg)
        //MfHelper.lockStatusBarAndNavigation(true)
    }

    private fun inputPinDetectCardRemove() {
        //TODO Enhancement in future
        /*Thread(Runnable {
            try {
                while (true) {
                    try {
                        Thread.sleep(300)
                    } catch (e: InterruptedException) {
                        throw RuntimeException(e)
                    }

                    if (!DeviceHelper.getIccCardReader(IccReaderSlot.ICSlOT1).isCardExists) {
                        Log.w(TAG, "Card Have Remove")
                        DeviceHelper.getPinpad().cancelInput()
                        ToastUtils.show(getContext(), "Card Have Remove")
                        return@Runnable
                    }

                    if (!DeviceHelper.getPinpad().isInputting) {
                        Log.w(TAG, "isInputting false")
                        return@Runnable
                    }
                }
            } catch (e: RemoteException) {
            }
        }).start()*/
    }

    // Ported from IsoSteps.pinDukptDeriv when the dead IsoEngine/IsoSteps stack was removed —
    // derives the PIN-variant DUKPT key from the IPEK for BSN PIN block encryption.
    fun pinDukptDeriv(ipek: ByteArray?, ksnHexString: String): ByteArray {
        val newKsnHexString = Dukpt.getNewKsn(ksnHexString)
        val ksn = Dukpt.toByteArray(newKsnHexString)

        val dukptVariant = DukptVariant(Dukpt.KEY_REGISTER_BITMASK, Dukpt.PIN_VARIANT_BITMASK)
        val derivedKey = dukptVariant.ipekComputeKey(ipek, ksn)
        logEmv("Current PinBlock KSN >> $newKsnHexString")
        logEmv("Current PinBlock Dukpt >> " + Dukpt.toHex(derivedKey))
        return derivedKey
    }

}