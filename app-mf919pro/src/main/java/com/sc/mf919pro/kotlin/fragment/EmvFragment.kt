package com.sc.mf919pro.kotlin.fragment

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
import com.morefun.yapi.device.reader.mag.MagCardInfoEntity
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
import com.sc.mf919pro.java.MF919
import crypto.Dukpt
import crypto.DukptVariant
import crypto.Encryption
import constants.TerminalConstants
import com.sc.mf919pro.java.activity.PinPadListener
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.device.DeviceHelper
import utils.CardUtil
import emv.EmvUtil
import com.sc.mf919pro.kotlin.activity.AppServices
import utils.HexUtil
import utils.TlvData
import utils.TlvDataList
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.repo.IsoBatchLongInfoRepo
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo.Companion.getSelectedProduct
import com.sc.mf919pro.kotlin.database.repo.SecureDataRepo
import helpers.LogRedact
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import iso.CardTagsEnum
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity.updateReceiptInfo
import iso.IsoHelperNew
import com.sc.mf919pro.kotlin.helper_common.utils.PinBlockUtil
import emv.Tlv
import enums.EnumDateFormat
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class EmvFragment : BaseFragment() {
    var myTlv: Tlv? = null

    protected lateinit var tempContext: Context
    val logClassName: String = this::class.java.simpleName
    lateinit var tempHelperLog: HelperLog

    /**
     * EMV kernel flow logging.
     *
     * These lines are the callback trail of a transaction and belong in the transaction's block --
     * Utils.printLog carries no RowIdentifier so it appears in no block at all. tempHelperLog is a
     * lateinit assigned in startEMV, and the kernel can invoke these callbacks on paths where that
     * never ran, so fall back to printLog rather than risking
     * UninitializedPropertyAccessException inside a payment callback.
     */
    private fun logX(msg: String) {
        if (this::tempHelperLog.isInitialized) {
            tempHelperLog.appendLine(logClassName, msg)
        } else {
            Utils.printLog(msg)
        }
    }
    private var startTick: Long = 0

    //Transaction
    // @Volatile: written on the EMV callback thread
    // (endEMV), read on the UI thread (CardPaymentFragment's back handler) and on an IO
    // thread (its `while (isNotEnd)` poll loop).
    @Volatile
    var isNotEnd = true

    /**
     * True while the host owns the transaction.
     *
     * The back button was never gated on this. `customOnBackPress` blanks
     * stan/invoiceNo/respCode whenever `isNotEnd` is true, and `isNotEnd` stays true *through the
     * host call*, so a back press or toolbar tap with the authorisation in flight wiped the live
     * transaction and the approval landed on cleared data. This is the same clobber MF919 hit via
     * `android:noHistory`, reached by a different route.
     *
     * D8: this used to be a `@Volatile var` owned here, raised only around the EMV sale paths
     * below -- so settlement and batch upload, which also call the host, ran with the guard down.
     * It now reads IsoActivity's counter, which every `sendToHost` raises.
     */
    val isHostRequestInFlight: Boolean get() = IsoActivity.isHostRequestInFlight
    var pinRequired = false
    var pinWait = true
    var pinCancel = false
    protected var mPinNum: String? = null
    var mAmount: String = ""
    protected var payMethod = TerminalConstants.paymentMethod.Non.toByte()
    private var timeout_cardSearch = 0

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
                @Throws(RemoteException::class)
                override fun onFindMagCard(magCardInfoEntity: MagCardInfoEntity) {
                    Utils.debugLogPrint("Search Card", "onFindMagCard")
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
                    logX("Builder $builder")

                    //get pin
                    logX("MagCard Online Pin $payMethod  $mAmount")
                    pinWait = true
                    //TODO GetPin
                    getPin(true, magCardInfoEntity.cardNo)
                    if(pinCancel){
                        val online = Bundle()
                        DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, online)
                        endEMV()
                        return
                    }
                    showProgress("Bank Authorization", "Waiting for Approval")

                    // online txn
                    // Form LEVEL 3 data
                    if (magCardInfoEntity.serviceCode.startsWith("2")
                        || magCardInfoEntity.serviceCode.startsWith("6")) {
                        Utils.printLog("magCardInfoEntity.getServiceCode()=${magCardInfoEntity.serviceCode}")
                        TransData.schemeId  = "20"
                    } else {
                        TransData.schemeId  = "97"
                    }
                    logX("strSchemeIdnow=${TransData.schemeId}")

                    val txnDt = "20" + EmvUtil.getCurrentTime("yyMMddHHmmss")
                    val magTrack2 = magCardInfoEntity.tk2.replace("=", "D")
                    if (magTrack2.length >= 38) {
                        Utils.printLog("Track 2 length more than 37. Declined")
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
                    Utils.printLog("onSearchResult: $de55")
                    val bD3 = ByteArray(2)
                    val de55len = de55.length / 2
                    Utils.printLog("track2len=$de55len")
                    myTlv!!.encodeLen(de55len, bD3, 0)
                    val D3 = "D309$de55"
                    val vl3 = TransData.schemeId + txnDt + D2 + D3
                    Utils.printLog("VL3:$vl3")
                    Utils.printLog("VL3 len:" + vl3.length)
                    val bVl3 = HexUtil.hexStringToByte(vl3)
                    val bVl3Len = bVl3.size
                    val optData = ByteArray(100)
                    val optDataLen = 0
                    Utils.printLog("bvl3:" + HexUtil.bytesToHexString(bVl3))
                    Utils.printLog("bvl3len=$bVl3Len")
                    Utils.printLog("optData:" + HexUtil.bytesToHexString(optData))
                    val isNotCompl = booleanArrayOf(true)
                    logX("onlineProc: magCard")

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

                    // Hold the guard across the whole flow, so the back button cannot blank
                    // stan/invoiceNo/respCode underneath it -- including after the approval
                    // arrives but before it is persisted.

                    IsoActivity.withHostRequest {
                        when (TransData.salesType) {
                            8 -> {IsoActivity.processPreauth(tempContext, tempHelperLog)}
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
                                    updateProgress(title = "Reversal ($loop)")

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
                                val status = ServiceHolder.isoComm!!.connectionStatus
                                if (!status.isNullOrEmpty()) {
                                    updateProgress(msg = status)
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
                    Utils.debugLogPrint("Search Card", "onSwipeCardFail")
                    endEMV()
                }

                @Throws(RemoteException::class)
                override fun onFindICCard() {
                    Utils.debugLogPrint("Search Card", "onFindICCard")
                    ServiceHolder.appRunningProcess = true
                    payMethod = TerminalConstants.paymentMethod.ICC.toByte()
                    TransData.payMethod = TerminalConstants.paymentMethod.ICC
                    showProgress("Card Detected", "Read Card Info...")
                    emvTrans(amount, cashOutAmountString, isEnableContact, isEnableContactless, EmvChannelType.FROM_ICC)
                }

                @Throws(RemoteException::class)
                override fun onFindRFCard() {
                    Utils.debugLogPrint("Search Card", "onFindRFCard")
                    ServiceHolder.appRunningProcess = true
                    payMethod = TerminalConstants.paymentMethod.RF.toByte()
                    TransData.payMethod = TerminalConstants.paymentMethod.RF
                    showProgress("Card Detected", "Read Card Info...")
                    emvTrans(amount, cashOutAmountString, isEnableContact, isEnableContactless, EmvChannelType.FROM_PICC)
                }

                @Throws(RemoteException::class)
                override fun onTimeout() {
                    Utils.debugLogPrint("Search Card", "onTimeOut")
                    endEMV()
                }

                @Throws(RemoteException::class)
                override fun onCanceled() {
                    Utils.debugLogPrint("Search Card", "onCanceled")
                    endEMV()
                }

                @Throws(RemoteException::class)
                override fun onError(code: Int) {
                    Utils.debugLogPrint("Search Card", "onError: $code")
                    endEMV()
                }
            })
        } catch (ex: Exception) {
            Utils.debugLogPrint("Exception", ex.message)
            requireActivity().runOnUiThread {
                endEMV()
            }
        }
    }

    @Throws(RemoteException::class)
    private fun emvTrans(amount: String, cashOutAmount: String, isEnableContact: Boolean, isEnableContactless: Boolean, channel: Int) {
        logX("START EMV TRANS")
        startTick = System.currentTimeMillis()

        DeviceHelper.getDeviceService().login(Bundle(), "00000000")
        DeviceHelper.getEmvHandler().initTermConfig(EmvUtil.getInitTermConfig())
        val ret = DeviceHelper.getEmvHandler().emvProcess(EmvUtil.getTransBundle(amount, cashOutAmount, channel, isEnableContact, isEnableContactless), object : OnEmvProcessListener.Stub() {
            @Throws(RemoteException::class)
            override fun onSelApp(appNameList: List<String>, isFirstSelect: Boolean) {
                logX("onSelApp")
                selApp(appNameList)
            }

            @Throws(RemoteException::class)
            override fun onConfirmCardNo(cardNo: String) {
                Utils.printLog("onConfirmCardNo:$cardNo")
                logX("time = " + (System.currentTimeMillis() - startTick) + "ms")
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
                logX("onCardHolderInputPin isOnlinePin:$isOnlinePin, Paymethod:$payMethod")
                logX("time = ${(System.currentTimeMillis() - startTick)} ms")
                logX("9F34 : ${EmvUtil.getPbocData("9F34", true)}")
                val cardNo: String = EmvUtil.readPan()

                if (!isOnlinePin && pinCancel) {
                    logX("Manual Cancel Transaction")
                    DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, Bundle())
                    return
                }

                if (isOnlinePin) {
                    getPin(true, cardNo)
                } else {
                    getPin(false, cardNo)
                }
                logX("onGetCardHolderInputPin???")

                try {
                    DeviceHelper.getEmvHandler().onSetCardHolderInputPin(HexUtil.hexStringToByte(mPinNum))
                } catch (e: RemoteException) {
                    e.printStackTrace()
                    logX("OnlinePin Exception $e")
                }
            }

            @Throws(RemoteException::class)
            override fun onPinPress(keyCode: Byte) {
                logX("Callback:onPinPress")
            }

            @Throws(RemoteException::class)
            override fun onDisplayOfflinePin(retCode: Int) {
                logX("Callback:onDisplayOfflinePin: $retCode,is succeed: ${(retCode == 0)}")
            }

            @Throws(RemoteException::class)
            override fun inputAmount(type: Int) {
                logX("Callback:inputAmount ")
                try {
                    DeviceHelper.getEmvHandler().onSetInputAmountResponse("0.3")
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }

            @Throws(RemoteException::class)
            override fun onGetCardResult(retCode: Int, bundle: Bundle) { }

            @Throws(RemoteException::class)
            override fun onDisplayMessage() {
                logX("CallBack:onDisplayMessage")
                DeviceHelper.getEmvHandler().onSetConfirmDisplayMessage(0)
            }

            @Throws(RemoteException::class)
            override fun onUpdateServiceAmount(serviceRelatedData: String) {
                logX("CallBack:onUpdateServiceAmount")
            }

            @Throws(RemoteException::class)
            override fun onCheckServiceBlackList(pan: String, amount: String) {
                logX("CallBack:onCheckServiceBlackList")
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

            // Was TODO(), which threw and killed the app mid-sale (audit item 81). Answer as MF919 does.
            override fun onEmvKernelCallback(p0: Int, p1: Int, p2: Bundle?) {
                logX("Callback:onEmvKernelCallback kernelType=$p0 flowStep=$p1")
                val ret = Bundle()
                ret.putInt(EmvKernelCallback.KEY_RET_CODE, 0)
                DeviceHelper.getEmvHandler().onSetEmvKernelCallback(ret)
            }

            @RequiresApi(Build.VERSION_CODES.O)
            @Throws(RemoteException::class)
            override fun onOnlineProc(data: Bundle) {
                logX("Callback:onOnlineProc")
                if (pinCancel || payMethod.toInt() == TerminalConstants.paymentMethod.Non) {
                    logX("Manual Cancel Transaction")
                    DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, Bundle())
                    return
                }
                logX("time = ${(System.currentTimeMillis() - startTick)}ms")
                //TODO ISO_SERVER
                /*val isIsoServer = false *//*temporary hardcoded *//*
                if(isIsoServer) onlineProcIsoServer() else onlineProc()*/
                onlineProc()
            }

            @Throws(RemoteException::class)
            override fun onContactlessOnlinePlaceCardMode(mode: Int) {
                logX("Callback:onContactlessOnlinePlaceCardMode")
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
                logX("Callback: onFinish")
                logX("time = ${(System.currentTimeMillis() - startTick)}ms")
                logX("cvm_flag: ${data.getInt(EmvOnlineRequest.CVM_FLAG)}")
                logX("CVM_SIGNATURE: ${data.getBoolean(EmvOnlineRequest.CVM_SIGNATURE)}")
                emvFinish(retCode, data)
            }

            @Throws(RemoteException::class)
            override fun onCertVerify(certName: String, certInfo: String) {
                logX("Callback:onCertVerify")
                DeviceHelper.getEmvHandler().onSetCertVerifyResponse(true)
            }

            @Throws(RemoteException::class)
            override fun onSetAIDParameter(aid: String) {
                logX("Callback:onSetAIDParameter :: $aid")
            }

            @Throws(RemoteException::class)
            override fun onSetCAPubkey(rid: String, index: Int, algMode: Int) {
                logX("Callback:onSetCAPubkey")
            }

            @Throws(RemoteException::class)
            override fun onTRiskManage(pan: String, panSn: String) {
                logX("Callback:onTRiskManage")
            }

            @Throws(RemoteException::class)
            override fun onSelectLanguage(language: String) {
                logX("Callback:onSelectLanguage")
            }

            @Throws(RemoteException::class)
            override fun onSelectAccountType(accountTypes: List<String>) {
                logX("Callback:onSelectAccountType")
            }

            @Throws(RemoteException::class)
            override fun onIssuerVoiceReference(pan: String) {
                logX("Callback:onIssuerVoiceReference")
            }
        })

        if (ret != 0) {
            logX("EMV INIT ERROR: $ret")
            endEMV()
        }
    }

    @Throws(RemoteException::class)
    private fun onlineProc() {
        Utils.playSound("success.wav")
        logX( "onlineProc: $payMethod")

        val builder = java.lang.StringBuilder()
        val arqcTlv = EmvUtil.getTLVDatas(EmvUtil.arqcTLVTags)
        builder.append(EmvUtil.getTLVDatas(EmvUtil.arqcTLVTags))
        if (!TlvDataList.fromBinary(arqcTlv).contains("9F27")) {
            builder.append(TlvData.fromData("9F27", byteArrayOf(0x80.toByte())))
        }
        Utils.printLog("TAG: Track2 -> ${LogRedact.track2(EmvUtil.readTrack2())}")

        val tlv = EmvUtil.getTLVDatas(EmvUtil.tags)
        logX("onlineProc: $tlv")

        //ForcePin for Cashout txn
        if (TransData.salesType == ProductCatSelectionDataEnum.CASH_OUT.data.SalesType && mPinNum == null){
            pinWait = true
            logX("Cashout Online Pin $payMethod $pinWait")
            getPin(true, EmvUtil.readPan())
            if (pinCancel) {
                val online = Bundle()
                DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, online)
                return
            }
        }

        val txnDt = "20" + EmvUtil.getCurrentTime("yyMMddHHmmss")
        TransData.transDateAsci = txnDt
        logX("TAG: txnDt -> $txnDt")
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

        if (EmvUtil.readTrack2().length >= 38) {
            Utils.printLog("Track 2 length more than 37. Declined")
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
        Utils.printLog("Before de55 -> $de55")
        if(override95){
            de55 = de55.replace("95050000000000", "9505$tvr")
        }
        Utils.printLog("After de55 -> $de55")
        val track3Byte = de55.toByteArray()
        track3Byte.copyInto(TransData.track3, 0, 0, track3Byte.size)
        TransData.track3Len = track3Byte.size

        updateProgress(title = "Bank Authorization"
)
        updateProgress(msg = "Waiting for Approval"
)
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
                    if (TransData.transResult == TerminalConstants.iso.err.txnDeclined_pinNeeded){
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
                    }
                    // Hold the guard across the whole flow, so the back button cannot blank
                    // stan/invoiceNo/respCode underneath it -- including after the approval
                    // arrives but before it is persisted.
                    IsoActivity.withHostRequest {
                        when (TransData.salesType) {
                            8 -> {IsoActivity.processPreauth(tempContext, tempHelperLog)}
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
                val status = ServiceHolder.isoComm!!.connectionStatus
                if (!status.isNullOrEmpty()) {
                    updateProgress(msg = status)
                }
            }
            Utils.DelayMili(100)
        }

        // StorageGuard tripped inside the ISO layer: no host request was sent, so there is nothing
        // to reverse and nothing to confirm. The kernel is mid-`onOnlineProc` and MUST still be
        // answered, with a NUMERIC REJCODE — TransData.respCode carries "ZS" for the result screen
        // and the kernel cannot parse that. Decline cleanly and skip the reversal block entirely.
        if (TransData.transResult == TerminalConstants.iso.err.txnNotAllowed) {
            tempHelperLog.appendLine(logClassName, "StorageGuard block :: declining to EMV kernel, no reversal")
            tempHelperLog.logToFile(EnumLogFileName.TerminaLog)
            val blocked = Bundle()
            blocked.putString(EmvOnlineResult.REJCODE, "05")
            DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Declined, blocked)
            return
        }

        // Pack data and send back to contact card
        logX("CUBE:EMVCT: Proceed Card ARQC2 Processing...")
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
        Utils.printLog("1. strDe55=$strDe55")
        if (strChipData.isNotEmpty()) {
            //val bChipData = HexUtil.hexStringToByte(strChipData)
            //isos!!.addTlvByTv("91", bChipData, 0, bChipData.size)
            //if (strChipData.indexOf("0520") > 0 || strChipData.indexOf("050A") > 0) {
            //    isos!!.addTlvsTo(bChipData, 2, bChipData.size - 2)
            //} else {
            //    isos!!.addTlvsTo(bChipData, 0, bChipData.size)
            //}
            strDe55 += strChipData
        }
        logX("strRespCodeAsc::$strRespCodeAsc")
        Utils.printLog("2. strDe55=$strDe55")
        // Pack data and send back to contact card

        //Reversal //Skip Reversal for MyDebit Preauth
        var preAuthReversal = true
        if(TransData.salesType == 8 && TransData.schemeType.equals("MCCS", true)) {
            preAuthReversal = false
        }

        if (preAuthReversal) {
            // A StorageGuard block satisfies BOTH conjuncts (respCode is empty, transResult is
            // an error), so without this exclusion a refused sale sends 3 real ISO reversals for a
            // transaction the host never saw. txnNotAllowed has no other producer in this codebase.
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
                            updateProgress(title = "Reversal ($loop)"
)

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
                        val status = ServiceHolder.isoComm!!.connectionStatus
                        if (!status.isNullOrEmpty()) {
                            updateProgress(msg = status)
                        }
                    }
                    Utils.DelayMili(100)
                }
            }
        }

        val online = Bundle()
        //TODO onlineRespCode is DE 39—RESPONSE CODE, detail see ISO8583
        /*"8A023030910AA77C66A0008600000000"*/
        val arpcData = HexUtil.hexStringToByte(strDe55)
        online.putString(EmvOnlineResult.REJCODE, strRespCodeAsc)
        online.putByteArray(EmvOnlineResult.RECVARPC_DATA, arpcData)
        DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Success, online)
        val tlv1 = EmvUtil.getTLVDatas(EmvUtil.tags)
        logX("onlineProc: $tlv1")
        logX("ISOENGINE:ProcessTxn. Resp= ${TransData.transResult}")
    }

    private fun selApp(appList: List<String>) {
        val options = arrayOfNulls<String>(appList.size)
        var forceSelect: Int = -1
        for (i in appList.indices) {
            val splitString = appList[i].split("|")
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
            /*if(isOptIn && splitString[1] != null &&  splitString[1].contains("A000000615",true)){
                forceSelect = i
            }*/
            //options[i] = appList[i]
            options[i] = splitString[0]
        }

        if(forceSelect != -1) {
            try {
                DeviceHelper.getEmvHandler().onSetSelAppResponse(forceSelect)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        } else {
            if (isOptIn) {
                try {
                    DeviceHelper.getEmvHandler().onSetSelAppResponse(-1)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            } else {
                requireActivity().runOnUiThread {
                    val alertBuilder = AlertDialog.Builder(tempContext)
                    alertBuilder.setTitle("PLEASE SELECT APP")
                    alertBuilder.setItems(options) { _, index ->
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
    }

    @Throws(RemoteException::class)
    private fun emvFinish(ret: Int, bundle: Bundle) {
        logX("emvFinish: $ret --> ${HexUtil.bytesToHexString(bundle.getByteArray(EmvErrorConstrants.EMV_ERROR_CODE))}")
        logX("emvFinish: $ret --> ${bundle.getByteArray(EmvErrorConstrants.EMV_ERROR_CODE)}")
        val errorCode = bundle.getByteArray(EmvErrorConstrants.EMV_ERROR_CODE)

        if (ret == ServiceResult.Success) { //trans accept
            onFinishShow(bundle)
            logX("emvFinish: Success")
            if (payMethod.toInt() == TerminalConstants.paymentMethod.ICC) {
                IsoActivity.processTcUpload(tempContext, tempHelperLog)
            }
        } else if (ret == ServiceResult.Emv_FallBack) { // fallback
            logX("emvFinish: Emv_FallBack")
            //TODO
            // cube!!.tlv_remove_tag(TerminalConstants.cube.CUBE_TAG_RESPCODE)
            // cube!!.tlv_add_by_tv_in_string(TerminalConstants.cube.CUBE_TAG_RESPCODE, Utils.ASCIItoHexString("ZX"))
        } else if (ret == ServiceResult.Emv_Terminate) { // trans end
            logX("emvFinish: Emv_Terminate")
            if (errorCode != null) {
                logX("Error Code: ${String(errorCode).trim { it <= ' ' }}")
                //TODO if the amount of connect less transactions is more than 2,0000. The interface prompts you to swipe or insert a card.
                if (DeviceHelper.getEmvHandler().isErrorCode(EmvErrorCode.QPBOC_ERR_PRE_AMTLIMIT)) {
                    logX("RF Limit Exceed, Pls Try Another Page!")
                } else if (DeviceHelper.getEmvHandler().isErrorCode(EmvErrorCode.EMV_ERR_INITAPP_GETOP)) {
                    logX("VISA Read Card Error!")
                } else if (DeviceHelper.getEmvHandler().isErrorCode(EmvErrorCode.EMV_ERR_SELAPP_APPLOCK)) {
                    logX("Card APP Lock!")
                }
            }
        } else if (ret == ServiceResult.Emv_Declined) { // trans refuse
            //TODO Please noted android time is correct.
            logX("emvFinish: Emv_Declined")
            if (errorCode != null) {
                logX("ERROR CODE: " + String(errorCode).trim { it <= ' ' })
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
                            updateProgress(title = "Reversal ($loop)"
)

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
                        val status = ServiceHolder.isoComm!!.connectionStatus
                        if (!status.isNullOrEmpty()) {
                            updateProgress(msg = status)
                        }
                    }
                    Utils.DelayMili(100)
                }
                //TODO Reversal
            }
        } else if (ret == ServiceResult.Emv_TryAgain) {
            logX("emvFinish: Emv_TryAgain")
            //Master need support
            val retCode = bundle.getInt(EmvErrorConstrants.EMV_GOTO_CODE, 0)
            if (retCode == GoToConstants.GOTO_TRY_AGAIN_CARD || retCode == GoToConstants.GOTO_TRY_AGAIN_MOBILE) {
                logX("Please call contactless search ")
                requireActivity().runOnUiThread {
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
            logX("emvFinish: Emv_TryOtherPage")
            val retCode = bundle.getInt(EmvErrorConstrants.EMV_GOTO_CODE, 0)
            if (retCode == -8) {
                logX("PLEASE DIP, SWIPE OR TRY ANOTHER CARD")
                //TODO
                //cube!!.tlv_remove_tag(TerminalConstants.cube.CUBE_TAG_RESPCODE)
                //cube!!.tlv_add_by_tv_in_string(TerminalConstants.cube.CUBE_TAG_RESPCODE, Utils.ASCIItoHexString("ZX"))
            } else if (retCode == -9) {
                logX("PLEASE DIP, SWIPE CARD")
                //TODO
                //cube!!.tlv_remove_tag(TerminalConstants.cube.CUBE_TAG_RESPCODE)
                //cube!!.tlv_add_by_tv_in_string(TerminalConstants.cube.CUBE_TAG_RESPCODE, Utils.ASCIItoHexString("ZY"))
            } else {
                logX("EMV TRY OTHER PAGE:$retCode")
            }
        } else if (ret == ServiceResult.Emv_Cancel) {
            logX("emv cancel")

        } else {
            logX("Other trans result")
        }

        CoroutineScope(Dispatchers.IO).launch {
            updateReceiptInfo(tempContext)
        }
        hideProgress()
        endEMV()

        // End of the EMV flow: this callback is the last thing to run on the kernel thread, and
        // everything appended since the last boundary (online processing, reversal attempts,
        // TC upload) is only in the buffer until now. tempHelperLog is a lateinit assigned in
        // startEMV, and this callback can be reached on paths where that never ran, so guard it.
        if (this::tempHelperLog.isInitialized) {
            tempHelperLog.appendLine(logClassName, "EMV process finished :: ret=$ret")
            tempHelperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }



    private fun getPin(isOnline: Boolean, cardNo: String) {
        pinRequired = true
        pinWait = true

        requireActivity().runOnUiThread {
            val newCardNo = cardNo.replace("F", "")
            PinPadDialogFragment
                .newInstance(newCardNo, if(isOnline) PinBlockUtil.ISO.ISO_0 else PinBlockUtil.ISO.ISO_2)
                .setPinPadListener(object : PinPadListener {
                    override fun onReadPinSuccess(pinBlock: String) {
                        //TODO ISO_SERVER pin block
                        if (isOnline) {
                            try {
                                mPinNum = if (pinBlock.isEmpty()) {
                                    pinBlock
                                } else {
                                    val acqName = ServiceHolder.getAcquirerSetting().acqName
                                    val secureLabel = acqName.substring(0,1) + acqName.substring(1).lowercase()

                                    val currMid = transData.mid
                                    val currTid = transData.tid
                                    logX("currMid=$currMid, currTid=$currTid")
                                    var encKey = SecureDataRepo.getDecryptedSingle(tempContext, listOf("tag", "subtag"), listOf("eTpkKey", "visam-tle$secureLabel-$currMid"))?.value ?: run {" "}

                                    if (acqName.uppercase() == "BSN") {
                                        try {
                                            val hexTpkKey = HexUtil.hexStringToByte(encKey)
                                            val ksnDetails = getSpecificKsn(tempContext, ArrayList(listOf("AcqMid", "AcqTid")), arrayOf(currMid, currTid))
                                            val pinBlockKsn = ksnDetails?.PinKsn
                                            if (!pinBlockKsn.isNullOrEmpty()) {
                                                val dukpt: ByteArray = pinDukptDeriv(hexTpkKey, pinBlockKsn)
                                                encKey = Dukpt.toHex(dukpt)
                                            }

                                        } catch (e: java.lang.Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                    val encPinBlock = Encryption.encrypt(pinBlock, encKey, "DESede", "CBC")
                                    logX("EncPinBlock=$encPinBlock")
                                    encPinBlock
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else {
                            mPinNum = if (pinBlock.isEmpty()) {
                                pinBlock
                            } else {
                                Utility.ASCIItoHexString(PinBlockUtil.pinBlockDecode(pinBlock, cardNo))
                            }
                        }

                        logX("onReadPinSuccess: $pinBlock")
                        logX("onReadPinSuccess mPinNum: $mPinNum")

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
                        logX( "onReadPinCancel -------")
                        // Same as MF919: a Cancel-key press also reports ZQ "PIN Not Entered".
                        TransData.respCode = Utils.ASCIItoHexString("ZQ")
                        pinWait = false
                        pinCancel = true
                    }
                    override fun onReadPinNotEntered() {
                        logX( "onReadPinNotEntered -------")
                        TransData.respCode = Utils.ASCIItoHexString("ZQ")
                        pinWait = false
                        pinCancel = true
                    }
                    override fun onReadingPin(len: Int, masked: String) {
                        logX("onReadingPin: $masked----$len")
                    }
                })
                .show(parentFragmentManager, "PinPadDialog")

        }
        /*requireActivity().runOnUiThread {
            val newCardNo = cardNo.replace("F", "")
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
                override fun onReadPinSuccess(pinBlock: String) {
                    //TODO ISO_SERVER pin block

                    if (isOnline) {
                        try {
                            mPinNum = if (pinBlock.isEmpty()) {
                                pinBlock
                            } else {
                                //val isodb = IsoDb()
                                val acqName = ServiceHolder.getAcquirerSetting().acqName
                                val secureLabel = acqName.substring(0,1) + acqName.substring(1).lowercase()

                                *//*val salesModel = selectedCacheModel as SalesModel?
                                val currMid = salesModel?.AcqMid ?: ""
                                val currTid = salesModel?.AcqTid ?: ""*//*
                                //var encKey = isodb.getSecureData("eTpkKey", "visam-tle$secureLabel-$currMid")
                                val currMid = transData.mid
                                val currTid = transData.tid
                                logX("currMid=$currMid, currTid=$currTid")
                                var encKey = SecureDataRepo.getDecryptedSingle(tempContext, listOf("tag", "subtag"), listOf("eTpkKey", "visam-tle$secureLabel-$currMid"))?.value ?: run {" "}

                                if (acqName.uppercase() == "BSN") {
                                    try {
                                        val hexTpkKey = HexUtil.hexStringToByte(encKey)
                                        val ksnDetails = getSpecificKsn(tempContext, ArrayList(listOf("AcqMid", "AcqTid")), arrayOf(currMid, currTid))
                                        val pinBlockKsn = ksnDetails?.PinKsn
                                        if (!pinBlockKsn.isNullOrEmpty()){
                                            val dukpt: ByteArray = pinDukptDeriv(hexTpkKey, pinBlockKsn)
                                            encKey = Dukpt.toHex(dukpt)
                                        }

                                    } catch (e: java.lang.Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                val encPinBlock = Encryption.encrypt(pinBlock, encKey, "DESede", "CBC")
                                logX("EncPinBlock=$encPinBlock")
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

                    logX("onReadPinSuccess: $pinBlock")
                    logX("onReadPinSuccess mPinNum: $mPinNum")
                    TransData.encPinBlock = mPinNum?:""
                    if (TransData.encPinBlock.isNotEmpty()){
                        TransData.onlinePinInput = true
                    }
                    pinWait = false
                }

                override fun onReadPinCancel() {
                    logX( "onReadPinCancel -------")
                    pinWait = false
                    pinCancel = true
                }

                override fun onReadingPin(len: Int, pin: String) {
                    logX("onReadingPin: $pin----$len")
                }
            })
        }*/

        while (pinWait) {
            Utils.DelayMili(100)
        }
    }


    @Throws(RemoteException::class)
    open fun onFinishShow(bundle: Bundle) {
        val list = bundle.getStringArrayList(EmvProcessResult.EMVLOG)
        val builder = java.lang.StringBuilder()
        val tlv = EmvUtil.getTLVDatas(EmvUtil.tags)
        val tlvDataList = TlvDataList.fromBinary(tlv)

        logX("Builder $builder")
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

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Throws(java.lang.Exception::class)
    fun pinDukptDeriv(ipek: ByteArray?, ksnHexString: String): ByteArray {
        var ksnHexString = ksnHexString
        ksnHexString = Dukpt.getNewKsn(ksnHexString)
        val ksn = Dukpt.toByteArray(ksnHexString)

        // Action
        val dukptVariant = DukptVariant(Dukpt.KEY_REGISTER_BITMASK, Dukpt.PIN_VARIANT_BITMASK)
        val derivedKey = dukptVariant.ipekComputeKey(ipek, ksn)
        logX("Current PinBlock KSN >> $ksnHexString")
        // D7 — this printed the derived PIN-block DUKPT key. Utils.printLog persists to
        // TerminalLog.txt in release builds (FileLoggingTree is planted unconditionally) and that
        // file is uploaded to TMS, so the key left the device. Never log key material.
        return derivedKey
    }

    open fun stopSearch() {
        /*if (rfReader == null || iccCardReader == null) {
            return
        }*/
        try {
            //iccCardReader?.stopSearch()
            //rfReader?.stopSearch()
            //magCardReader?.stopSearch()
            payMethod = TerminalConstants.paymentMethod.Cancel.toByte()
        } catch (e: RemoteException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            e.printStackTrace()
        }
    }

    open fun endEMV() {
        isNotEnd = false
        // Every EMV error path (init failure, timeout, cancel, onError) funnels here,
        // but not all of them go through emvFinish's hideProgress(). The dialog is
        // non-cancelable, so a missed hide freezes the terminal; hideProgress() is a
        // posted no-op when nothing is showing, making it safe to call unconditionally.
        hideProgress()
        try {
            payMethod = TerminalConstants.paymentMethod.Non.toByte()
            DeviceHelper.getEmvHandler().cancelCheckCard()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    open suspend fun resetEMV() {
        try {
            Utils.debugLogPrint("EmvActivity", "Reset EMV")
            Utils.debugLogPrint("EmvActivity", "isNotEnd :: $isNotEnd")
            if(isNotEnd) {
                val online = Bundle()
                DeviceHelper.getEmvHandler().onSetOnlineProcResponse(ServiceResult.Emv_Terminate, online)
                DeviceHelper.getEmvHandler().cancelCheckCard()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}