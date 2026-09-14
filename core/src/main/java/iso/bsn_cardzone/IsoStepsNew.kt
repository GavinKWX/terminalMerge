package iso.bsn_cardzone

import helpers.LogRedact
import com.sc.terminal.core.BuildConfig
import crypto.DataHash
import iso.CurrentStore
import iso.CurrentTxn
import iso.IsoInfoModel
import utils.AmountFormat
import utils.ByteOps
import utils.StringUtils
import android.util.Log

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.library.terminal.Utility
import crypto.Dukpt
import crypto.DukptVariant
import emv.EmvTag
import constants.TerminalConstants
import iso.IsoUtil
import utils.HexUtil
import helpers.HelperDate
import helpers.HelperLog
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import org.threeten.bp.format.DateTimeFormatter

object IsoStepsBsnCardZone {
    var ptrValue = ByteArray(1024)
    var ptrValue2 = ByteArray(1024)
    var secureLabel = ""
    private val logClassName: String = this::class.java.simpleName

    /*
     * Acquirer-certification diagnostics: DE57/DE59 cleartext (which is the sensitive-field
     * payload before encryption) and the per-message MAC. These are genuinely needed to debug a
     * certification failure on a bench build, and must never reach a production terminal's log --
     * every TerminaLog file is uploaded to TMS.
     *
     * inline + a lambda so a release build compiles this away entirely: the string is never even
     * built, let alone written. Note Utils.debugLogPrint would NOT be enough -- it goes through
     * Timber to FileLoggingTree to AsyncLogWriter, i.e. straight to the uploaded file. Only
     * println and a DEBUG-gated Log.d are logcat-only.
     */
    private inline fun certLog(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(logClassName, msg())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun transformBatchData(context: Context){
        val new60 = StringBuilder()
        // Backup previous value
        val df60 = CurrentTxn.getFromTransactionDb("DF60", 16)
        if(df60.isNotEmpty()){
            println("df60 :: $df60")
            new60.append(df60)
            CurrentTxn.addHexStrIntoTransDB("BF60", df60)
        }

        // Form DE60 = MTI[2] + DE11[3] + BE37-RRN[6]
        val strMti = Utility.ASCIItoHexString(CurrentTxn.getFromTransactionDb(TerminalConstants.iso.tag.MTI, 16))
        new60.append(strMti)
        val strStan = CurrentTxn.getFromTransactionDb("DF11", 16)
        new60.append(strStan)
        val strRRN = CurrentTxn.getFromTransactionDb("BF37", 16)
        new60.append(strRRN)
        println("new60 :: $new60")
        // Form DE60 = MTI[2] + DE11[3] + BE37-RRN[6]

        //CurrentTxn.removeTlvFromTransDb(TerminalConstants.iso.tag.TPDU)
        CurrentTxn.removeTlvFromTransDb(TerminalConstants.iso.tag.MTI)
        CurrentTxn.removeTlvFromTransDb("DF03")
        CurrentTxn.removeTlvFromTransDb("DF11")
        //CurrentTxn.removeTlvFromTransDb("DF57")
        CurrentTxn.removeTlvFromTransDb("DF58")
        CurrentTxn.removeTlvFromTransDb("DF59")
        CurrentTxn.removeTlvFromTransDb("DF64")

        // Handle DE07 & DE12 using current void time
        CurrentTxn.removeTlvFromTransDb("DF07")
        val gmtPlus0 = ZonedDateTime.now(ZoneId.of("GMT+8"))
        val formatter = DateTimeFormatter.ofPattern("MMddHHmmss")
        val strGmtPlus0 = gmtPlus0.format(formatter)
        CurrentTxn.addHexStrIntoTransDB("DF07", strGmtPlus0)

        CurrentTxn.removeTlvFromTransDb("DF12")
        val txnTm: String = HelperDate.getDateString("HHmmss")
        CurrentTxn.addHexStrIntoTransDB("DF12", txnTm)

        // Copy DE37, 38, 39 from RESP TO CMD
        CurrentTxn.removeTlvFromTransDb("DF37")
        CurrentTxn.addHexStrIntoTransDB("DF37", strRRN)

        if (strMti != "0220") {
            CurrentTxn.removeTlvFromTransDb("DF38")
            val strAuthId = CurrentTxn.getFromTransactionDb("BF38", 16)
            CurrentTxn.addHexStrIntoTransDB("DF38", strAuthId)
        }

        CurrentTxn.removeTlvFromTransDb("DF39")
        val strRespCode = CurrentTxn.getFromTransactionDb("BF39", 16)
        CurrentTxn.addHexStrIntoTransDB("DF39", strRespCode)
        // Copy DE37, 38, 39 from RESP TO CMD

        //val strInvoiceNo = CurrentTxn.getFromTransactionDb("DF62", 16)
        //CurrentTxn.addHexStrIntoTransDB("DF62", strInvoiceNo)
        //outInvNo.get(0) = ByteOps.byteArrayToAsciiString(ptrValue, 0, 6)

        val strCardPan = CurrentTxn.getFromTransactionDb("DF02", 16)
        if(strCardPan.isEmpty()){
            println("DF02 is null. Get from D5 now")
            val d5Pan = CurrentTxn.getFromTransactionDb(TerminalConstants.iso.tag.PANSTRING, 16)
            CurrentTxn.removeTlvFromTransDb("DF02")
            CurrentTxn.addHexStrWithPadIntoTransDB("DF02", d5Pan, "F")
        }

        val cashOutAmount = CurrentTxn.getFromTransactionDb(TerminalConstants.cube.CUBE_TAG_CASH_OUT_AMOUNT, 16)
        if(cashOutAmount.isNotEmpty()){
            CurrentTxn.cashOutAmount = AmountFormat.getActualAmount(cashOutAmount).replace(".", "").toLong()
            HexUtil.hexStringToByte(AmountFormat.zeroPadding(cashOutAmount, 12)).copyInto(CurrentTxn.cashOutAmountAuth)
        }

        // Remove Response DB that required updated from new response
        CurrentTxn.removeTlvFromTransDb("BF00")
        CurrentTxn.removeTlvFromTransDb("BF11")
        CurrentTxn.removeTlvFromTransDb("BF37")
        CurrentTxn.removeTlvFromTransDb("BF38")
        CurrentTxn.removeTlvFromTransDb("BF39")
        //CurrentTxn.removeTlvFromTransDb("BF57")
        CurrentTxn.removeTlvFromTransDb("BF58")
        CurrentTxn.removeTlvFromTransDb("BF59")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun formIsoMessage(context: Context, helperLog: HelperLog, isoInfoModel: IsoInfoModel, destByteOffset: Int, isoByte: ByteArray, byteArrayLen: IntArray) {
        val etag = EmvTag()
        val isou = IsoUtil()
        val desField = IntArray(30)
        val desFieldLen = isou.splitIntegerText(isoInfoModel.transactionDes!!, " ", desField)
        val encDesField = IntArray(30)
        val encDesFieldLen = isou.splitIntegerText(isoInfoModel.transactionDesSensitive!!, " ", encDesField)
        helperLog.appendLine(logClassName, "Forming Iso Message")
        val schemeTag = CurrentTxn.schemeTag

        //val refTag = "${CurrentTxn.schemeTag}-tle${CurrentTxn.acqCode.lowercase().replaceFirstChar { it.uppercase() }}-${CurrentTxn.mid}"
        val refTag = "$schemeTag-tle${CurrentTxn.acqCode.lowercase().replaceFirstChar { it.uppercase() }}-${CurrentTxn.mid}"
        val strKsn = CurrentStore.secureValue(context, "eTmkId", refTag) ?: ""
        val strWakKey = CurrentStore.secureValue(context, "eWakKey", refTag) ?: ""
        val strWekKey = CurrentStore.secureValue(context, "eWekKey", refTag) ?: ""
        helperLog.appendLine(logClassName, "strKsn :: $strKsn")
        if(strWakKey.isEmpty() || strWekKey.isEmpty()) {
            CurrentTxn.transResult = TerminalConstants.iso.err.txnDeclined_emptySecureKey
            CurrentTxn.respCode = ByteOps.ASCIItoHexString("ZV")
            return
        }
        CurrentTxn.ksn = strKsn
        CurrentTxn.pinKsn = strKsn
        CurrentTxn.addHexStrIntoTransDB("DF58", Utility.ASCIItoHexString(strKsn))

        if(CurrentTxn.eppTenureCode.isNotEmpty()) {
            helperLog.appendLine(logClassName, "EPP Tenure :: ${CurrentTxn.eppTenure}")
            helperLog.appendLine(logClassName, "EPP Tenure Code :: ${CurrentTxn.eppTenureCode}")
            val mipData = "EPP_${CurrentTxn.eppTenureCode} "
            CurrentTxn.addHexStrIntoTransDB("DF61", HexUtil.str2HexStr(mipData))
        }
        helperLog.appendLine(logClassName, "FormIsoMessage for Des ($desFieldLen) :: ${desField.joinToString { it.toString() }}")
        helperLog.appendLine(logClassName, "Sensitive Des Field ($encDesFieldLen) :: ${encDesField.joinToString { it.toString() }}")

        // Generate DE59
        val encDe59 = ByteArray(1024)
        var iEncDe59Len = 0
        var clearByteDe59 = ByteArray(1024)
        var iClearByteDe59Len = 0

        if(encDesField[0] != 0){
            //No Required BitMap
            //val bBmp = ByteArray(8)
            //isou.bmpEncode(isoInfoModel.transactionDesSensitive!!, bBmp)

            // DEs
            repeat(encDesFieldLen){
                //val iThisField: Byte = encDesField[it].toByte()
                val iThisField = encDesField[it]
                val iThisTag = "DF" + String.format("%02d", iThisField)
                val iThisLen = etag.getValueFrom(CurrentTxn.transactionDb, iThisTag, ptrValue)
                if(iThisLen > 0){
                    helperLog.appendLine(logClassName, "$iThisTag :: ${HexUtil.bytesToHexString(ptrValue, 0, iThisLen)}")
                    helperLog.appendLine(logClassName, "$iThisTag Length :: $iThisLen")
                    val bufTemp = ByteArray(256)
                    ByteOps.memset(bufTemp, 0.toByte(), bufTemp.size)
                    when(iThisField){
                        35 -> {
                            val byteLastTrack2Byte = ptrValue[iThisLen - 1]
                            if (byteLastTrack2Byte.toInt() and 0x0F == 0x0F) {
                                //bufTemp[iThisLen - 1] = 0x0F

                                val strTrack2 = HexUtil.bytesToHexString(ptrValue, 0, iThisLen)
                                //val newTrack2 = strTrack2.replace("F", " ")
                                val newTrack2 = strTrack2
                                helperLog.appendLine(logClassName, "strTrack2 :: ${LogRedact.track2(strTrack2)}")
                                helperLog.appendLine(logClassName, "newTrack2 :: ${LogRedact.track2(newTrack2)}")

                                val bTrack2 = HexUtil.hexStringToByte(newTrack2)
                                ByteOps.memcpy(clearByteDe59, iClearByteDe59Len, bTrack2, 0, bTrack2.size)
                                iClearByteDe59Len += bTrack2.size
                            } else {
                                ByteOps.memcpy(clearByteDe59, iClearByteDe59Len, ptrValue, 0, iThisLen)
                                iClearByteDe59Len += iThisLen
                            }
                        }
                        2 -> {
                            var iCardPanLength = iThisLen * 2
                            val byteLastCardPanByte = ptrValue[iThisLen - 1]
                            if (byteLastCardPanByte.toInt() and 0x0F == 0x0F) {
                                iCardPanLength -= 1 //minus 1 byte as padding
                                bufTemp[iThisLen - 1] = 0x0F
                            }

                            ByteOps.memcpy(clearByteDe59, iClearByteDe59Len, ptrValue, 0, iThisLen)
                            iClearByteDe59Len += iThisLen
                            helperLog.appendLine(logClassName, "DE02-check iThisLen :: $iThisLen")
                            CurrentTxn.addTlvIntoTransDB("DF63", ptrValue, 0, iThisLen) //backup to 0xDF63 for reversal
                        }
                        14, 45, 48, 58 -> {
                            ByteOps.memcpy(clearByteDe59, iClearByteDe59Len, ptrValue, 0, iThisLen)
                            iClearByteDe59Len += iThisLen
                        }
                        else -> {
                            helperLog.appendLine(logClassName, "ERR: Unable to obtain data for DE-$iThisField")
                        }
                    }
                }
            }

            if (iClearByteDe59Len > 0) {
                certLog { "CLEAR before padding [] :: ${HexUtil.bytesToHexString(clearByteDe59, 0, iClearByteDe59Len)}" }

                val strDE59 = HexUtil.bytesToHexString(clearByteDe59, 0, iClearByteDe59Len)
                val hexClearDE59 = ByteOps.ASCIItoHexString(strDE59)
                val hexByteArr59 = ByteOps.hexStringToByteArray(hexClearDE59)
                ByteOps.memcpy(clearByteDe59, hexByteArr59, hexByteArr59.size)
                iClearByteDe59Len = hexByteArr59.size

                // Add Padding
                var iPadlen = iClearByteDe59Len % 8
                if (iPadlen != 0) {
                    iPadlen = 8 - iPadlen
                    helperLog.appendLine(logClassName, "Pad Length :: $iPadlen")

                    val spaceChar = ' '
                    val spaceByte: Byte = spaceChar.code.toByte()
                    ByteOps.memset(clearByteDe59, iClearByteDe59Len, spaceByte, iPadlen)
                    iClearByteDe59Len += iPadlen

                    /*var strDE59 = HexUtil.bytesToHexString(clearByteDe59, 0, iClearByteDe59Len)
                    var hexClearDE59 = ByteOps.ASCIItoHexString(strDE59)
                    for (i in 0..iPadlen) {
                        hexClearDE59 += "2020"
                    }
                    println("hexClearDE59 :: $hexClearDE59")
                    strDE59 = Utility.HexString2ASCII(hexClearDE59)
                    println("strDE59 :: [$hexClearDE59]")
                    clearByteDe59 = ByteOps.hexStringToByteArray(strDE59)
                    iClearByteDe59Len += iPadlen
                    ByteOps.memcpy(clearByteDe59, ByteOps.hexStringToByteArray(strDE59), iClearByteDe59Len)*/
                }
                certLog { "CLEAR after padding [] :: ${HexUtil.bytesToHexString(clearByteDe59, 0, iClearByteDe59Len)}" }

                val strClearDe59 = HexUtil.bytesToHexString(clearByteDe59, 0, iClearByteDe59Len)
                certLog { "strlen(strClearDe57) :: ${ByteOps.strlen(strClearDe59)}" }
                certLog { "CLEAR [] :: $strClearDe59" }
                var strEncDe59: String? = null
                try {
                    strEncDe59 = Dukpt.encryptSenstiveData(strClearDe59, strWekKey)
                } catch (ex : Exception) {
                    ex.printStackTrace()
                }
                helperLog.appendLine(logClassName, "strEncDe59 :: $strEncDe59")

                val bEncDe59 = HexUtil.hexStringToByte(strEncDe59)
                ByteOps.memcpy(encDe59, iEncDe59Len, bEncDe59, 0, bEncDe59.size)
                iEncDe59Len += bEncDe59.size
                helperLog.appendLine(logClassName, "ENCRYPTED[] :: ${HexUtil.bytesToHexString(encDe59, 0 , iEncDe59Len)}")
                helperLog.appendLine(logClassName, "bEncDe59.length :: ${bEncDe59.size}")
                helperLog.appendLine(logClassName, "iEncDe59Len :: $iEncDe59Len")
            }
        }
        CurrentTxn.addTlvIntoTransDB("DF59", encDe59, 0 , iEncDe59Len)

        // Form Message from MTI to DE63 (Bitmap include DE64)
        val macIsoByte = ByteArray(6000)
        var macIsoByteLen = destByteOffset
        var isoByteLen = destByteOffset
        var ptrLen = 0

        // TPDU
        ptrLen = etag.getValueFrom(CurrentTxn.transactionDb, TerminalConstants.iso.tag.TPDU, ptrValue)
        if(ptrLen > 0) {
            ByteOps.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
            macIsoByteLen += ptrLen
            ByteOps.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
            isoByteLen += ptrLen
        }

        //MTI
        ptrLen = etag.getValueFrom(CurrentTxn.transactionDb, TerminalConstants.iso.tag.MTI, ptrValue)
        if(ptrLen > 0) {
            ByteOps.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
            macIsoByteLen += ptrLen
            ByteOps.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
            isoByteLen += ptrLen
        }

        // BITMAPS
        val bBmp = ByteArray(8)
        isou.bmpEncode(isoInfoModel.transactionDes!!, bBmp)
        println(HexUtil.bytesToHexString(bBmp))

        //Cache Len for know the BMP location
        val iCacheLen = macIsoByteLen
        ByteOps.memcpy(macIsoByte, macIsoByteLen, bBmp, 0, 8)
        macIsoByteLen += 8
        ByteOps.memcpy(isoByte, isoByteLen, bBmp, 0, 8)
        isoByteLen += 8

        helperLog.appendLine(logClassName, "ENCODE-ISO :: ")
        try {
            repeat(desFieldLen) {
                val iFieldId: Int = desField[it]

                val tempValue = ByteArray(2000)
                val tempLen = 0

                val indexOfEnc = encDesField.indexOf(iFieldId)
                val iThisFieldLen = setField(iFieldId, CurrentTxn.transactionDb, tempValue, tempLen, true)
                if(iThisFieldLen > 0) {
                    helperLog.appendLine(logClassName, "\tDE$iFieldId($iThisFieldLen) :: ${HexUtil.bytesToHexString(tempValue, 0, iThisFieldLen)}")
                    /*if (iFieldId != 59) {
                        ByteOps.memcpy(macIsoByte, macIsoByteLen, tempValue, 0, iThisFieldLen)
                        macIsoByteLen += iThisFieldLen
                    }

                    if(indexOfEnc == -1){
                        ByteOps.memcpy(isoByte, isoByteLen, tempValue, 0, iThisFieldLen)
                        isoByteLen += iThisFieldLen
                    }*/
                    if(indexOfEnc == -1) {
                        ByteOps.memcpy(macIsoByte, macIsoByteLen, tempValue, 0, iThisFieldLen)
                        macIsoByteLen += iThisFieldLen
                        ByteOps.memcpy(isoByte, isoByteLen, tempValue, 0, iThisFieldLen)
                        isoByteLen += iThisFieldLen
                    }
                } else {
                    helperLog.appendLine(logClassName, "ERR DE$iFieldId Not Found")
                }

                // Remove BMP tag for 0 value or sensitive element
                if((iThisFieldLen <= 0  || indexOfEnc >= 0) && iFieldId != 64) {
                    isou.bmpAlterBit(iFieldId, 0.toByte(), isoByte, iCacheLen)
                    isou.bmpAlterBit(iFieldId, 0.toByte(), macIsoByte, iCacheLen)
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            helperLog.appendLine(logClassName, "Exception::${ex.message}")
            helperLog.appendLine(logClassName, "Exception::$ex")
        }

        val macFullIsoLen = macIsoByteLen - 2
        if(macFullIsoLen > 0){
            ByteOps.set_ushort(macFullIsoLen.toShort(), macIsoByte, 0)
        }
        val macPartialIsoLen = isoByteLen - 2
        if(macPartialIsoLen > 0){
            ByteOps.set_ushort(macPartialIsoLen.toShort(), isoByte, 0)
        }
        certLog { "Mac Iso :: " }
        certLog { "\t${HexUtil.bytesToHexString(macIsoByte, 0,  macIsoByteLen)}" }
        helperLog.appendLine(logClassName, "Iso Byte :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")

        // Generate Mac
        try {
            val strIsoMessageBuf = HexUtil.bytesToHexString(macIsoByte, 7, macIsoByteLen - 7)
            helperLog.appendLine(logClassName, "Formed ISO String :: $strIsoMessageBuf")
            val strMac = Dukpt.cardzoneRetailMac(strWakKey, strIsoMessageBuf)
            certLog { "MAB :: $strMac" }

            val mac = ByteOps.hexStringToByteArray(strMac)
            //mac only required left 4 byte, right 4 byte pad with 0
            ByteOps.memset(mac, 4, 0.toByte(), 4)
            certLog { "MAC :: ${HexUtil.bytesToHexString(mac)}" }

            ByteOps.memcpy(isoByte, isoByteLen, mac, 0, 8)
            isoByteLen += 8

            val actualMacIsoLen = macIsoByteLen - 2
            if(actualMacIsoLen > 0){
                ByteOps.set_ushort(actualMacIsoLen.toShort(), macIsoByte, 0)
            }
            val actualIsoLen = isoByteLen - 2
            if(actualIsoLen > 0){
                ByteOps.set_ushort(actualIsoLen.toShort(), isoByte, 0)
            }
        } catch (ex : Exception) {
            ex.printStackTrace()
            helperLog.appendLine(logClassName, "MAC Generation Error")
            isoByteLen = 0
        }
        byteArrayLen[0] = isoByteLen
        helperLog.appendLine(logClassName, "Iso Byte after MAC ($isoByteLen) :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0, isoByteLen)}")
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun setField(fieldId: Int, dataByte: ByteArray, dataout: ByteArray, dataoutOffset: Int, removeCardNoPad: Boolean): Int {
        val etag = EmvTag()
        var iLenSize = 0
        var iFixFieldSize = 0
        var isoTag: String? = null
        var iMaxLen = 0
        var iResp: Int
        var fieldType = 'n' //n=numeric(bcd), a=ascii)
        if (fieldId == 2) {
            iLenSize = 1
            iFixFieldSize = 0
            isoTag = "DF02"
            if (removeCardNoPad) {
                var modLastByte = false
                //iResp = etag.getValueFrom(CurrentTxn.transactionDb, "DF02", ptrValue)
                iResp = etag.getValueFrom(dataByte, "DF02", ptrValue)
                if (iResp <= 0) {
                    println("WARNING: DE%d got no data$fieldId")
                    iResp = 0
                }
                var iTrack2Length = iResp * 2
                val byteLastTrack2Byte = ptrValue[iResp - 1]
                if (byteLastTrack2Byte.toInt() and 0x0F == 0x0F) {
                    modLastByte = true
                    iTrack2Length -= 1 //minus 1 byte as padding
                }
                dataout[dataoutOffset] = ByteOps.hex2bcd(iTrack2Length) //BCD need to times 2
                ByteOps.memcpy(dataout, dataoutOffset + iLenSize, ptrValue, 0, iResp)
                if (modLastByte) {
                    dataout[dataoutOffset + iLenSize + iResp - 1] = 0x00
                }
                println("\tremoveCardNoPad:$removeCardNoPad")
                println("\tDE" + fieldId + ": " + HexUtil.bytesToHexString(dataout, dataoutOffset, iResp + iLenSize))
                return iResp + iLenSize
            }
        } else if (fieldId == 3) {
            iFixFieldSize = 3 //in bytes
            iLenSize = 0
            isoTag = "DF03"
        } else if (fieldId == 4) //Amt, Txn
        {
            iFixFieldSize = 6 //in bytes
            iLenSize = 0
            isoTag = "DF04"
        } else if (fieldId == 5) //Amt, Settlement
        {
            iFixFieldSize = 6 //in bytes
            iLenSize = 0
            isoTag = "DF05"
        } else if (fieldId == 6) //Amt, CardHolder
        {
            iFixFieldSize = 6 //in bytes
            iLenSize = 0
            isoTag = "DF06"
        } else if (fieldId == 7) //txn DateTime
        {
            iFixFieldSize = 5 //in bytes
            iLenSize = 0
            isoTag = "DF07"
        } else if (fieldId == 8) //Amt, Cardholder billing fee
        {
            iFixFieldSize = 64 //in bytes
            iLenSize = 0
            isoTag = "DF08"
        } else if (fieldId == 9) //Conversion Rate, settlemnt
        {
            iFixFieldSize = 4 //in bytes
            iLenSize = 0
            isoTag = "DF09"
        } else if (fieldId == 10) //Conversion rate, cardholder billing
        {
            iFixFieldSize = 4 //in bytes
            iLenSize = 0
            isoTag = "DF10"
        } else if (fieldId == 11) //STAN
        {
            iFixFieldSize = 3 //in bytes
            iLenSize = 0
            isoTag = "DF11"
        } else if (fieldId == 12) //Local Txn Time
        {
            iFixFieldSize = 3 //in bytes
            iLenSize = 0
            isoTag = "DF12"
        } else if (fieldId == 13) //Local Txn Date MMDD
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF13"
        } else if (fieldId == 14) //Date, Expiration
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF14"
        } else if (fieldId == 15) //Date, Settlement
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF15"
        } else if (fieldId == 16) //Date, Conversion
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF16"
        } else if (fieldId == 17) //Date, Capture
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF17"
        } else if (fieldId == 18) //Merchant type
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF18"
        } else if (fieldId == 19) //Acquiring institution country code
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF19"
        } else if (fieldId == 20) //PAN extended, country code
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF20"
        } else if (fieldId == 21) //Forwarding institution. country code
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF21"
        } else if (fieldId == 22) //PosCond
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF22"
        } else if (fieldId == 23) //App PAN SeqNo
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF23"
        } else if (fieldId == 24) //NII
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF24"
        } else if (fieldId == 25) //POS ConCode
        {
            iFixFieldSize = 1 //in bytes
            iLenSize = 0
            isoTag = "DF25"
        } else if (fieldId == 26) //POS CapCode
        {
            iFixFieldSize = 1 //in bytes
            iLenSize = 0
            isoTag = "DF26"
        } else if (fieldId == 27) //Authorizing ID Length
        {
            iFixFieldSize = 1 //in bytes
            iLenSize = 0
            isoTag = "DF27"
        } else if (fieldId == 28) //Amt, Txn Fee
        {
            iFixFieldSize = 4 //in bytes
            iLenSize = 0
            isoTag = "DF28"
        } else if (fieldId == 29) //Amt, Settlement Fee
        {
            iFixFieldSize = 4 //in bytes
            iLenSize = 0
            isoTag = "DF29"
        } else if (fieldId == 30) //Amt, Processing Fee
        {
            iFixFieldSize = 4 //in bytes
            iLenSize = 0
            isoTag = "DF30"
        } else if (fieldId == 31) //Amt, Settlement Fee
        {
            iFixFieldSize = 4 //in bytes
            iLenSize = 0
            isoTag = "DF31"
        } else if (fieldId == 32) //Acquiring institution identification code
        {
            iFixFieldSize = 0 //in bytes
            iLenSize = 1
            isoTag = "DF32"
        } else if (fieldId == 33 || fieldId == 34 || fieldId == 36) //Forwarding institution identification code
        {
            iFixFieldSize = 0
            iLenSize = 1
            isoTag = "DF" + String.format("%02d", fieldId)
            iMaxLen = 99
        } else if (fieldId == 35) //Track 2 data
        {
            var modLastByte = false
            iLenSize = 1
            //iResp = etag.getValueFrom(CurrentTxn.transactionDb, "DF35", ptrValue)
            iResp = etag.getValueFrom(dataByte, "DF35", ptrValue)
            if (iResp <= 0) {
                println("WARNING: DE%d got no data$fieldId")
                iResp = 0
            }
            var iTrack2Length = iResp * 2
            val byteLastTrack2Byte = ptrValue[iResp - 1]
            if (byteLastTrack2Byte.toInt() and 0x0F == 0x0F) {
                modLastByte = true
                iTrack2Length -= 1 //minus 1 byte as padding
            }
            dataout[dataoutOffset] = ByteOps.hex2bcd(iTrack2Length) //BCD need to times 2
            ByteOps.memcpy(dataout, dataoutOffset + iLenSize, ptrValue, 0, iResp)
            if (modLastByte) {
                val newLastByteInt = byteLastTrack2Byte.toInt() and 0xF0
                dataout[dataoutOffset + iLenSize + iResp - 1] = newLastByteInt.toByte()
            }
            println("\tDE" + fieldId + ": " + HexUtil.bytesToHexString(dataout, dataoutOffset, 1) + " " + HexUtil.bytesToHexString(dataout, dataoutOffset + 1, 1) + " ** ** ** ** " + HexUtil.bytesToHexString(dataout, dataoutOffset + (iResp + iLenSize) / 2, (iResp + iLenSize) / 2))
            return iResp + iLenSize
        } else if (fieldId == 36) //Track 3 data
        {
            iFixFieldSize = 0 //in bytes
            iLenSize = 1
            isoTag = "DF36"
        } else if (fieldId == 37) //
        {
            iFixFieldSize = 12 //in bytes
            iLenSize = 0
            isoTag = "DF37"
            fieldType = 'a'
        } else if (fieldId == 38) //
        {
            iFixFieldSize = 6 //in bytes
            iLenSize = 0
            isoTag = "DF38"
            fieldType = 'a'
        } else if (fieldId == 39) //
        {
            iFixFieldSize = 2 //in bytes
            iLenSize = 0
            isoTag = "DF39"
            fieldType = 'a'
        } else if (fieldId == 40) //
        {
            iFixFieldSize = 3 //in bytes
            iLenSize = 0
            isoTag = "DF40"
            fieldType = 'a'
        } else if (fieldId == 41) //
        {
            iFixFieldSize = 8 //in bytes
            iLenSize = 0
            isoTag = "DF41"
            fieldType = 'a'
        } else if (fieldId == 42) //
        {
            iFixFieldSize = 15 //in bytes
            iLenSize = 0
            isoTag = "DF42"
            fieldType = 'a'
        } else if (fieldId == 43) //
        {
            iFixFieldSize = 40 //in bytes
            iLenSize = 0
            isoTag = "DF43"
            fieldType = 'a'
        } else if (fieldId == 44) //
        {
            iFixFieldSize = 0 //in bytes
            iLenSize = 1
            isoTag = "DF44"
            iMaxLen = 25
            fieldType = 'a'
        } else if (fieldId == 45) //
        {
            iFixFieldSize = 0 //in bytes
            iLenSize = 1
            isoTag = "DF45"
            iMaxLen = 76
            fieldType = 'a'
        } else if (fieldId == 52) //Personal identification number data (b 64)
        {
            iFixFieldSize = 8 //in bytes
            iLenSize = 0
            isoTag = "DF52"
        } else if (fieldId == 53) //Security related control information(n 16)
        {
            iFixFieldSize = 8 //in bytes
            iLenSize = 0
            isoTag = "DF53"
        } else if (fieldId == 64) //TLE MAC
        {
            iFixFieldSize = 8 //in bytes
            iLenSize = 0
            isoTag = "DF64"
            fieldType = 'b'
        } else if (fieldId == 46 || fieldId == 47 || fieldId == 48 || fieldId >= 54) //OTHER ASC FIELDS
        {
            iFixFieldSize = 0
            iLenSize = 2
            isoTag = "DF" + String.format("%02d", fieldId)
            iMaxLen = 999
            fieldType = 'a'
        } else {
            println("ERR: SetFiled Err, unknown DE-$fieldId")
            return 0
        }

        return if (isoTag != null && isoTag !== "") {
            if (iFixFieldSize > 0) {
                ByteOps.memset(dataout, dataoutOffset + iLenSize, 0.toByte(), iFixFieldSize)
                iLenSize = 0
            }
            var paddingLen = 0
            //iResp = etag.getValueFrom(CurrentTxn.transactionDb, isoTag, ptrValue)
            iResp = etag.getValueFrom(dataByte, isoTag, ptrValue)
            if (iResp <= 0) {
                println("\tERR: DE%2d NOT FOUND$fieldId")
                iResp = 0
                return -2
            } else {
                ByteOps.memcpy(dataout, dataoutOffset + iLenSize, ptrValue, 0, iResp)
                if (fieldType == 'n') //bcd
                {
                    val lastChar = ptrValue[iResp - 1]
                    if (lastChar.toInt() and 0x0F == 0x0F) {
                        paddingLen = 1
                    }
                }
            }

            if (iMaxLen > 0) {
                if (iResp > iMaxLen) iResp = iMaxLen
            }
            if (iLenSize == 1) {
                if (fieldType == 'n') ByteOps.bin2bcd(iResp * 2 - paddingLen, 1, dataout, dataoutOffset) else ByteOps.bin2bcd(iResp, 1, dataout, dataoutOffset)
            }
            if (iLenSize == 2) {
                if (fieldType == 'n') ByteOps.bin2bcd(iResp * 2 - paddingLen, 2, dataout, dataoutOffset) else ByteOps.bin2bcd(iResp, 2, dataout, dataoutOffset)
            }
            if (iFixFieldSize > 0) iResp = iFixFieldSize
            //println("\tDE" + fieldId + ": " + HexUtil.bytesToHexString(dataout, dataoutOffset, iResp + iLenSize))
            iResp + iLenSize
        } else {
            println("ERR: Invalid TAG")
            0
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun parseIsoResp(helperLog: HelperLog, isoRespByte: ByteArray, isoRespByteOffset: Int, isoRespByteLen: Int) {
        helperLog.appendLine(logClassName, "Parse Iso Resp :: ")
        helperLog.appendLine(logClassName, "isoResp($isoRespByteLen) :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoRespByte, isoRespByteOffset, isoRespByteLen)}")

        val isou = IsoUtil()
        val destByteLen = IntArray(2)
        destByteLen[0] = CurrentTxn.transactionDbLen

        val iResp = isou.msgDecode(isoRespByte, isoRespByteOffset, isoRespByteLen, CurrentTxn.transactionDb, destByteLen)
        if(iResp < 0){
            helperLog.appendLine(logClassName, "ISOE:ERR: Failed to parse ISO Resp. Handle as Timeout")
        } else {
            CurrentTxn.transactionDbLen = destByteLen[0]
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun parseIsoRespIsolate(helperLog: HelperLog, isoRespByte: ByteArray, isoRespByteOffset: Int, isoRespByteLen: Int, returnByte: ByteArray, returnByteLenArr: IntArray, mapVariable: MutableMap<String, String>) {
        helperLog.appendLine(logClassName, "Parse Iso Resp Isolate :: ")
        helperLog.appendLine(logClassName, "isoResp($isoRespByteLen) :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoRespByte, isoRespByteOffset, isoRespByteLen)}")
        val isou = IsoUtil()
        //val destByteLen = IntArray(2)

        val iResp = isou.msgDecode(isoRespByte, isoRespByteOffset, isoRespByteLen, returnByte, returnByteLenArr)
        if(iResp < 0){
            helperLog.appendLine(logClassName, "ISOE:ERR: Failed to parse ISO Resp. Handle as Timeout")
            returnByteLenArr[0] = 0
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun formIsoIsolate(context: Context, helperLog: HelperLog, isoInfoModel: IsoInfoModel, prevIsoString: String, isoByte: ByteArray, byteArrayLen: IntArray): MutableMap<String, String> {
        val emvTag = EmvTag()
        val isou = IsoUtil()
        var ptrLen = 0
        val schemeTag = "visam"

        val tempBuffArray = ByteArray(4096)
        val previousIsoByte = HexUtil.hexStringToByte(prevIsoString)
        ByteOps.memcpy(tempBuffArray, 0, previousIsoByte, 0, previousIsoByte.size)
        val variantMap = mutableMapOf<String, String>()

        var acqCode = ""
        var acqMid = ""
        var acqTid = ""
        var ksn = ""
        //TODO

        //TID
        ptrLen = emvTag.getValueFrom(previousIsoByte, "DF41", ptrValue)
        if(ptrLen > 0){
            acqTid = ByteOps.byteArrayToAsciiString(ptrValue, 0 , ptrLen)
        }

        // MID
        ptrLen = emvTag.getValueFrom(previousIsoByte, "DF42", ptrValue)
        if(ptrLen > 0){
            acqMid = ByteOps.byteArrayToAsciiString(ptrValue, 0 , ptrLen)
        }
        val stan = CurrentStore.allocateCounter(context, "stan", schemeTag)
        helperLog.appendLine(logClassName, "Stan :: $stan")
        val invoiceNo = CurrentStore.allocateCounter(context, "invoiceNo", "pos")
        helperLog.appendLine(logClassName, "Invoice No :: $invoiceNo")

        ptrLen = emvTag.getValueFrom(previousIsoByte, "BF37", ptrValue)
        if(ptrLen > 0){
            emvTag.removeTlvByT(tempBuffArray, "DF37")
            val refNum = HexUtil.bytesToHexString(ptrValue, 0 , ptrLen)
            emvTag.addTlvByTvHexString("DF37", refNum, 0, refNum.length, tempBuffArray)
        }

        ptrLen = emvTag.getValueFrom(previousIsoByte, "BF38", ptrValue)
        if(ptrLen > 0){
            emvTag.removeTlvByT(tempBuffArray, "DF38")
            val apvCde = HexUtil.bytesToHexString(ptrValue, 0 , ptrLen)
            emvTag.addTlvByTvHexString("DF38", apvCde, 0, apvCde.length, tempBuffArray)
        }

        ptrLen = emvTag.getValueFrom(previousIsoByte, "BF39", ptrValue)
        if(ptrLen > 0){
            emvTag.removeTlvByT(tempBuffArray, "DF39")
            val respCde = HexUtil.bytesToHexString(ptrValue, 0 , ptrLen)
            emvTag.addTlvByTvHexString("DF39", respCde, 0, respCde.length, tempBuffArray)
        }

        val productModel = CurrentStore.acquirerProduct(context, acqMid, acqTid)
        productModel?.let {
            acqCode = it.acqCode
        }
        helperLog.appendLine(logClassName, "MTI :: ${isoInfoModel.mti}")
        emvTag.removeTlvByT(tempBuffArray, TerminalConstants.iso.tag.MTI)
        emvTag.addTlvByTvHexString(TerminalConstants.iso.tag.MTI, isoInfoModel.mti, 0, isoInfoModel.mti.length, tempBuffArray)
        if(isoInfoModel.processCode.isNotEmpty()) {
            helperLog.appendLine(logClassName, "DF03 Not Empty:: ${isoInfoModel.processCode}")
            emvTag.removeTlvByT(tempBuffArray, "DF03")
            emvTag.addTlvByTvHexString("DF03", isoInfoModel.processCode, 0, isoInfoModel.processCode.length, tempBuffArray)
        } else {
            helperLog.appendLine(logClassName, "Use Original Transaction DF03")
        }

        //Reversal reuse same STAN as Ori Txn
        if (isoInfoModel.mti != "0400"){
            helperLog.appendLine(logClassName, "Reversal Detected, Modify Stan to Previous Trans Stan")
            emvTag.removeTlvByT(tempBuffArray, "DF11")
            emvTag.addTlvByTvHexString("DF11", stan, 0, stan.length, tempBuffArray)
        }

        emvTag.removeTlvByT(tempBuffArray, "DF58")
        emvTag.removeTlvByT(tempBuffArray, "DF59")
        emvTag.removeTlvByT(tempBuffArray, "DF64")

        //DE60 Handling
        when (isoInfoModel.mti){
            "0500" -> {
                helperLog.appendLine(logClassName, "Prepare DE60 for settlement")
                emvTag.removeTlvByT(tempBuffArray, "DF60")
                val hexBatchNo = HexUtil.str2HexStr(CurrentTxn.batchNo)
                emvTag.addTlvByTvHexString("DF60", hexBatchNo, 0, hexBatchNo.length, tempBuffArray)
            }
            "0320" -> {
                helperLog.appendLine(logClassName, "Prepare DE60 for TC Upload")
                //Form DE60 = MTI[2] + DE11[3] + BE37-RRN[6]
                var tempDE60= ""
                ptrLen = emvTag.getValueFrom(previousIsoByte, TerminalConstants.iso.tag.MTI, ptrValue)
                if(ptrLen > 0){
                    val strMti = HexUtil.bytesToHexString(ptrValue, 0 , ptrLen)
                    //val hexStrMti = ByteOps.ASCIItoHexString(strMti)
                    tempDE60 += Utility.ASCIItoHexString(strMti)
                }
                ptrLen = emvTag.getValueFrom(previousIsoByte, "DF11", ptrValue)
                if(ptrLen > 0){
                    val strStan = HexUtil.bytesToHexString(ptrValue, 0 , ptrLen)
                    //val hexStrStan = ByteOps.ASCIItoHexString(strStan)
                    tempDE60 += Utility.ASCIItoHexString(strStan)
                }
                tempDE60 += "202020202020202020202020"
                emvTag.removeTlvByT(tempBuffArray, "DF60")
                emvTag.addTlvByTvHexString("DF60", tempDE60, 0, tempDE60.length, tempBuffArray)
            }
        }

        // Remove Response DB that required updated from new response
        CurrentTxn.removeTlvFromTransDb("BF00")
        CurrentTxn.removeTlvFromTransDb("BF11")
        CurrentTxn.removeTlvFromTransDb("BF37")
        CurrentTxn.removeTlvFromTransDb("BF38")
        CurrentTxn.removeTlvFromTransDb("BF39")
        CurrentTxn.removeTlvFromTransDb("BF58")
        CurrentTxn.removeTlvFromTransDb("BF59")
        emvTag.removeTlvByT(tempBuffArray, "BF00")
        emvTag.removeTlvByT(tempBuffArray, "BF11")
        emvTag.removeTlvByT(tempBuffArray, "BF37")
        emvTag.removeTlvByT(tempBuffArray, "BF38")
        emvTag.removeTlvByT(tempBuffArray, "BF39")
        emvTag.removeTlvByT(tempBuffArray, "BF58")
        emvTag.removeTlvByT(tempBuffArray, "BF59")

        val desField = IntArray(30)
        val desFieldLen = isou.splitIntegerText(isoInfoModel.transactionDes!!, " ", desField)
        val encDesField = IntArray(30)
        val encDesFieldLen = isou.splitIntegerText(isoInfoModel.transactionDesSensitive!!, " ", encDesField)
        helperLog.appendLine(logClassName, "FormIsoMessage for Des ($desFieldLen) :: ${desField.joinToString { it.toString() }}")
        helperLog.appendLine(logClassName, "Sensitive Des Field ($encDesFieldLen) :: ${encDesField.joinToString { it.toString() }}")

        val refTag = "$schemeTag-tle${acqCode.lowercase().replaceFirstChar { it.uppercase() }}-${acqMid}"
        ksn = CurrentStore.secureValue(context, "eTmkId", refTag) ?: ""
        val strWakKey = CurrentStore.secureValue(context, "eWakKey", refTag) ?: ""
        val strWekKey = CurrentStore.secureValue(context, "eWekKey", refTag) ?: ""
        helperLog.appendLine(logClassName, "strKsn :: $ksn")
        if(strWakKey.isEmpty() || strWekKey.isEmpty()) {
            CurrentTxn.transResult = TerminalConstants.iso.err.txnDeclined_emptySecureKey
            CurrentTxn.respCode = ByteOps.ASCIItoHexString("ZV")
            return variantMap
        }
        val hexKsn = Utility.ASCIItoHexString(ksn)
        emvTag.addTlvByTvHexString("DF58", hexKsn, 0, hexKsn.length, tempBuffArray)
        // Generate DE59
        val encDe59 = ByteArray(1024)
        var iEncDe59Len = 0
        var clearByteDe59 = ByteArray(1024)
        var iClearByteDe59Len = 0
        if(encDesField[0] != 0){
            // DEs
            repeat(encDesFieldLen){
                val iThisField = encDesField[it]
                val iThisTag = "DF" + String.format("%02d", iThisField)
                val iThisLen = emvTag.getValueFrom(tempBuffArray, iThisTag, ptrValue)
                if(iThisLen > 0){
                    helperLog.appendLine(logClassName, "$iThisTag :: ${HexUtil.bytesToHexString(ptrValue, 0, iThisLen)}")
                    helperLog.appendLine(logClassName, "$iThisTag Length :: $iThisLen")
                    val bufTemp = ByteArray(256)
                    ByteOps.memset(bufTemp, 0.toByte(), bufTemp.size)
                    when(iThisField){
                        35 -> {
                            val byteLastTrack2Byte = ptrValue[iThisLen - 1]
                            if (byteLastTrack2Byte.toInt() and 0x0F == 0x0F) {
                                //bufTemp[iThisLen - 1] = 0x0F

                                val strTrack2 = HexUtil.bytesToHexString(ptrValue, 0, iThisLen)
                                //val newTrack2 = strTrack2.replace("F", " ")
                                val newTrack2 = strTrack2
                                helperLog.appendLine(logClassName, "strTrack2 :: ${LogRedact.track2(strTrack2)}")
                                helperLog.appendLine(logClassName, "newTrack2 :: ${LogRedact.track2(newTrack2)}")

                                val bTrack2 = HexUtil.hexStringToByte(newTrack2)
                                ByteOps.memcpy(clearByteDe59, iClearByteDe59Len, bTrack2, 0, bTrack2.size)
                                iClearByteDe59Len += bTrack2.size
                            } else {
                                ByteOps.memcpy(clearByteDe59, iClearByteDe59Len, ptrValue, 0, iThisLen)
                                iClearByteDe59Len += iThisLen
                            }
                        }
                        2 -> {
                            var iCardPanLength = iThisLen * 2
                            val byteLastCardPanByte = ptrValue[iThisLen - 1]
                            if (byteLastCardPanByte.toInt() and 0x0F == 0x0F) {
                                iCardPanLength -= 1 //minus 1 byte as padding
                                bufTemp[iThisLen - 1] = 0x0F
                            }

                            ByteOps.memcpy(clearByteDe59, iClearByteDe59Len, ptrValue, 0, iThisLen)
                            iClearByteDe59Len += iThisLen
                            helperLog.appendLine(logClassName, "DE02-check iThisLen :: $iThisLen")
                            emvTag.addTlvByTv("DF63", ptrValue, 0, iThisLen, tempBuffArray) //backup to 0xDF63 for reversal
                        }
                        14, 45, 48, 58 -> {
                            ByteOps.memcpy(clearByteDe59, iClearByteDe59Len, ptrValue, 0, iThisLen)
                            iClearByteDe59Len += iThisLen
                        }
                        else -> {
                            helperLog.appendLine(logClassName, "ERR: Unable to obtain data for DE-$iThisField")
                        }
                    }
                }
            }

            if (iClearByteDe59Len > 0) {
                certLog { "CLEAR before padding [] :: ${HexUtil.bytesToHexString(clearByteDe59, 0, iClearByteDe59Len)}" }

                val strDE59 = HexUtil.bytesToHexString(clearByteDe59, 0, iClearByteDe59Len)
                val hexClearDE59 = ByteOps.ASCIItoHexString(strDE59)
                val hexByteArr59 = ByteOps.hexStringToByteArray(hexClearDE59)
                ByteOps.memcpy(clearByteDe59, hexByteArr59, hexByteArr59.size)
                iClearByteDe59Len = hexByteArr59.size

                // Add Padding
                var iPadlen = iClearByteDe59Len % 8
                if (iPadlen != 0) {
                    iPadlen = 8 - iPadlen
                    helperLog.appendLine(logClassName, "Pad Length :: $iPadlen")

                    val spaceChar = ' '
                    val spaceByte: Byte = spaceChar.code.toByte()
                    ByteOps.memset(clearByteDe59, iClearByteDe59Len, spaceByte, iPadlen)
                    iClearByteDe59Len += iPadlen
                }
                certLog { "CLEAR after padding [] :: ${HexUtil.bytesToHexString(clearByteDe59, 0, iClearByteDe59Len)}" }

                val strClearDe59 = HexUtil.bytesToHexString(clearByteDe59, 0, iClearByteDe59Len)
                certLog { "strlen(strClearDe57) :: ${ByteOps.strlen(strClearDe59)}" }
                certLog { "CLEAR [] :: $strClearDe59" }
                var strEncDe59: String? = null
                try {
                    strEncDe59 = Dukpt.encryptSenstiveData(strClearDe59, strWekKey)
                } catch (ex : Exception) {
                    ex.printStackTrace()
                }
                helperLog.appendLine(logClassName, "strEncDe59 :: $strEncDe59")

                val bEncDe59 = HexUtil.hexStringToByte(strEncDe59)
                ByteOps.memcpy(encDe59, iEncDe59Len, bEncDe59, 0, bEncDe59.size)
                iEncDe59Len += bEncDe59.size
                helperLog.appendLine(logClassName, "ENCRYPTED[] :: ${HexUtil.bytesToHexString(encDe59, 0 , iEncDe59Len)}")
                helperLog.appendLine(logClassName, "bEncDe59.length :: ${bEncDe59.size}")
                helperLog.appendLine(logClassName, "iEncDe59Len :: $iEncDe59Len")
            }
        }
        emvTag.addTlvByTv("DF59", encDe59, 0, iEncDe59Len, tempBuffArray)

        // Form Message from MTI to DE63 (Bitmap include DE64)
        val macIsoByte = ByteArray(6000)
        var macIsoByteLen = 2
        var isoByteLen = 2

        // TPDU
        ptrLen = emvTag.getValueFrom(tempBuffArray, TerminalConstants.iso.tag.TPDU, ptrValue)
        if(ptrLen > 0) {
            ByteOps.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
            macIsoByteLen += ptrLen
            ByteOps.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
            isoByteLen += ptrLen
        }

        // MTI
        ptrLen = emvTag.getValueFrom(tempBuffArray, TerminalConstants.iso.tag.MTI, ptrValue)
        if(ptrLen > 0) {
            ByteOps.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
            macIsoByteLen += ptrLen
            ByteOps.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
            isoByteLen += ptrLen
        }

        // BITMAPS
        val bBmp = ByteArray(8)
        isou.bmpEncode(isoInfoModel.transactionDes!!, bBmp)
        helperLog.appendLine(logClassName, "ISO:BMP :: ${HexUtil.bytesToHexString(bBmp, 0, 8)}")

        //Cache Len for know the BMP location
        val iCacheLen = macIsoByteLen
        ByteOps.memcpy(macIsoByte, macIsoByteLen, bBmp, 0, 8)
        macIsoByteLen += 8
        ByteOps.memcpy(isoByte, isoByteLen, bBmp, 0, 8)
        isoByteLen += 8

        helperLog.appendLine(logClassName, "ENCODE-ISO :: ")
        try {
            repeat(desFieldLen) {
                val iFieldId: Int = desField[it]

                val tempValue = ByteArray(2000)
                val tempLen = 0

                val indexOfEnc = encDesField.indexOf(iFieldId)
                val iThisFieldLen = setField(iFieldId, tempBuffArray, tempValue, tempLen, true)
                if(iThisFieldLen > 0) {
                    helperLog.appendLine(logClassName, "\tDE$iFieldId($iThisFieldLen) :: ${HexUtil.bytesToHexString(tempValue, 0, iThisFieldLen)}")
                    /*if (iFieldId != 59) {
                        ByteOps.memcpy(macIsoByte, macIsoByteLen, tempValue, 0, iThisFieldLen)
                        macIsoByteLen += iThisFieldLen
                    }

                    if(indexOfEnc == -1){
                        ByteOps.memcpy(isoByte, isoByteLen, tempValue, 0, iThisFieldLen)
                        isoByteLen += iThisFieldLen
                    }*/
                    if(indexOfEnc == -1) {
                        ByteOps.memcpy(macIsoByte, macIsoByteLen, tempValue, 0, iThisFieldLen)
                        macIsoByteLen += iThisFieldLen
                        ByteOps.memcpy(isoByte, isoByteLen, tempValue, 0, iThisFieldLen)
                        isoByteLen += iThisFieldLen
                    }
                } else {
                    helperLog.appendLine(logClassName, "ERR DE$iFieldId Not Found")
                }

                // Remove BMP tag for 0 value or sensitive element
                if((iThisFieldLen <= 0  || indexOfEnc >= 0) && iFieldId != 64) {
                    isou.bmpAlterBit(iFieldId, 0.toByte(), isoByte, iCacheLen)
                    isou.bmpAlterBit(iFieldId, 0.toByte(), macIsoByte, iCacheLen)
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            helperLog.appendLine(logClassName, "Exception::${ex.message}")
            helperLog.appendLine(logClassName, "Exception::$ex")
        }

        val macFullIsoLen = macIsoByteLen - 2
        if(macFullIsoLen > 0){
            ByteOps.set_ushort(macFullIsoLen.toShort(), macIsoByte, 0)
        }
        val macPartialIsoLen = isoByteLen - 2
        if(macPartialIsoLen > 0){
            ByteOps.set_ushort(macPartialIsoLen.toShort(), isoByte, 0)
        }
        certLog { "Mac Iso :: " }
        certLog { "\t${HexUtil.bytesToHexString(macIsoByte, 0,  macIsoByteLen)}" }
        helperLog.appendLine(logClassName, "Iso Byte :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")

        // Generate Mac
        try {
            val strIsoMessageBuf = HexUtil.bytesToHexString(macIsoByte, 7, macIsoByteLen - 7)
            helperLog.appendLine(logClassName, "Formed ISO String :: $strIsoMessageBuf")
            val strMac = Dukpt.cardzoneRetailMac(strWakKey, strIsoMessageBuf)
            certLog { "MAB :: $strMac" }

            val mac = ByteOps.hexStringToByteArray(strMac)
            //mac only required left 4 byte, right 4 byte pad with 0
            ByteOps.memset(mac, 4, 0.toByte(), 4)
            certLog { "MAC :: ${HexUtil.bytesToHexString(mac)}" }

            ByteOps.memcpy(isoByte, isoByteLen, mac, 0, 8)
            isoByteLen += 8

            val actualMacIsoLen = macIsoByteLen - 2
            if(actualMacIsoLen > 0){
                ByteOps.set_ushort(actualMacIsoLen.toShort(), macIsoByte, 0)
            }
            val actualIsoLen = isoByteLen - 2
            if(actualIsoLen > 0){
                ByteOps.set_ushort(actualIsoLen.toShort(), isoByte, 0)
            }
        } catch (ex : Exception) {
            ex.printStackTrace()
            helperLog.appendLine(logClassName, "MAC Generation Error")
            isoByteLen = 0
        }
        byteArrayLen[0] = isoByteLen
        helperLog.appendLine(logClassName, "Iso Byte after MAC ($isoByteLen) :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")
        return variantMap
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun formIsoSignOn(context: Context, helperLog: HelperLog, isoInfoModel: IsoInfoModel, prevIsoString: String, isoByte: ByteArray, byteArrayLen: IntArray): MutableMap<String, String>  {
        val emvTag = EmvTag()
        val isou = IsoUtil()
        var ptrLen = 0
        val schemeTag = "visam"

        val tempBuffArray = ByteArray(4096)
        val previousIsoByte = HexUtil.hexStringToByte(prevIsoString)
        ByteOps.memcpy(tempBuffArray, 0, previousIsoByte, 0, previousIsoByte.size)
        val variantMap = mutableMapOf<String, String>()

        var acqCode = ""
        var acqMid = ""
        var acqTid = ""
        var ksn = ""
        var pinKsn = ""

        //TID
        ptrLen = emvTag.getValueFrom(previousIsoByte, "DF41", ptrValue)
        if(ptrLen > 0){
            acqTid = ByteOps.byteArrayToAsciiString(ptrValue, 0 , ptrLen)
        }

        // MID
        ptrLen = emvTag.getValueFrom(previousIsoByte, "DF42", ptrValue)
        if(ptrLen > 0){
            acqMid = ByteOps.byteArrayToAsciiString(ptrValue, 0 , ptrLen)
        }
        val stan = CurrentStore.allocateCounter(context, "stan", schemeTag)
        emvTag.addTlvByTvHexString("DF11", stan, 0, stan.length, tempBuffArray)
        helperLog.appendLine(logClassName, "Stan :: $stan")
        val invoiceNo = CurrentStore.allocateCounter(context, "invoiceNo", "pos")
        helperLog.appendLine(logClassName, "Invoice No :: $invoiceNo")

        CurrentStore.batchInfoValue(context, "isoTpduHeaderTle", schemeTag) ?.let {
            emvTag.addTlvByTvHexString(TerminalConstants.iso.tag.TPDU, it, 0, it.length, tempBuffArray)
        } ?: run { "" }

        CurrentStore.batchInfoValue(context, "niiTle", schemeTag) ?.let {
            CurrentTxn.addHexStrIntoTransDB("DF24", it)
            emvTag.addTlvByTvHexString("DF24", it, 0, it.length, tempBuffArray)

        } ?: run { "" }

        helperLog.appendLine(logClassName, "MTI :: ${isoInfoModel.mti}")
        emvTag.removeTlvByT(tempBuffArray, TerminalConstants.iso.tag.MTI)
        emvTag.addTlvByTvHexString(TerminalConstants.iso.tag.MTI, isoInfoModel.mti, 0, isoInfoModel.mti.length, tempBuffArray)
        emvTag.removeTlvByT(tempBuffArray, "DF03")
        emvTag.addTlvByTvHexString("DF03", isoInfoModel.processCode, 0, isoInfoModel.processCode.length, tempBuffArray)

        val desField = IntArray(30)
        val desFieldLen = isou.splitIntegerText(isoInfoModel.transactionDes!!, " ", desField)
        val encDesField = IntArray(30)
        val encDesFieldLen = isou.splitIntegerText(isoInfoModel.transactionDesSensitive!!, " ", encDesField)
        helperLog.appendLine(logClassName, "FormIsoMessage for Des ($desFieldLen) :: ${desField.joinToString { it.toString() }}")
        helperLog.appendLine(logClassName, "Sensitive Des Field ($encDesFieldLen) :: ${encDesField.joinToString { it.toString() }}")

        // Form Message from MTI to DE63 to sha1
        val macIsoByte = ByteArray(6000)
        var macIsoByteLen = 2
        var isoByteLen = 2

        // TODO CARDZONE LOOKS LIKE NO TPDU
        // TPDU
        ptrLen = emvTag.getValueFrom(tempBuffArray, TerminalConstants.iso.tag.TPDU, ptrValue)
        ByteOps.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
        macIsoByteLen += ptrLen
        ByteOps.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
        isoByteLen += ptrLen

        // MTI
        ptrLen = emvTag.getValueFrom(tempBuffArray, TerminalConstants.iso.tag.MTI, ptrValue)
        ByteOps.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
        macIsoByteLen += ptrLen
        ByteOps.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
        isoByteLen += ptrLen

        // BITMAPS
        val bBmp = ByteArray(8)
        isou.bmpEncode(isoInfoModel.transactionDes!!, bBmp)
        helperLog.appendLine(logClassName, "ISO:BMP :: ${HexUtil.bytesToHexString(bBmp, 0, 8)}")

        //Cache Len for know the BMP location
        val iCacheLen = macIsoByteLen
        ByteOps.memcpy(macIsoByte, macIsoByteLen, bBmp, 0, 8)
        macIsoByteLen += 8
        ByteOps.memcpy(isoByte, isoByteLen, bBmp, 0, 8)
        isoByteLen += 8

        helperLog.appendLine(logClassName, "ENCODE-ISO :: ")
        try {
            repeat(desFieldLen) {
                val iFieldId: Int = desField[it]

                val tempValue = ByteArray(2000)
                val tempLen = 0

                val indexOfEnc = encDesField.indexOf(iFieldId)
                val iThisFieldLen = setField(iFieldId, tempBuffArray, tempValue, tempLen, false)
                if(iThisFieldLen > 0) {
                    helperLog.appendLine(logClassName, "\tDE$iFieldId($iThisFieldLen) :: ${HexUtil.bytesToHexString(tempValue, 0, iThisFieldLen)}")
                    if (iFieldId != 57) {
                        ByteOps.memcpy(macIsoByte, macIsoByteLen, tempValue, 0, iThisFieldLen)
                        macIsoByteLen += iThisFieldLen
                    }

                    if(indexOfEnc == -1){
                        ByteOps.memcpy(isoByte, isoByteLen, tempValue, 0, iThisFieldLen)
                        isoByteLen += iThisFieldLen
                    }
                } else {
                    helperLog.appendLine(logClassName, "ERR DE$iFieldId Not Found")
                }

                // Remove BMP tag for 0 value or sensitive element
                if (iThisFieldLen <= 0 || indexOfEnc >= 0) {
                    if (iFieldId != 64) {
                        isou.bmpAlterBit(iFieldId, 0.toByte(), isoByte, iCacheLen)
                    }
                    if(iThisFieldLen <= 0){
                        isou.bmpAlterBit(iFieldId, 0.toByte(), macIsoByte, iCacheLen)
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            helperLog.appendLine(logClassName, "Exception::${ex.message}")
            helperLog.appendLine(logClassName, "Exception::$ex")
        }

        val macFullIsoLen = macIsoByteLen - 2
        if(macFullIsoLen > 0){
            ByteOps.set_ushort(macFullIsoLen.toShort(), macIsoByte, 0)
        }
        val macPartialIsoLen = isoByteLen - 2
        if(macPartialIsoLen > 0){
            ByteOps.set_ushort(macPartialIsoLen.toShort(), isoByte, 0)
        }
        byteArrayLen[0] = isoByteLen

        certLog { "Mac Iso :: " }
        certLog { "\t${HexUtil.bytesToHexString(macIsoByte, 0,  macIsoByteLen)}" }
        helperLog.appendLine(logClassName, "Iso Byte :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")
        return variantMap
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun parseRespSignOn(context: Context, helperLog: HelperLog, isoByte: ByteArray, byteArrayLen: IntArray): Boolean {
        var result = false
        secureLabel = CurrentTxn.acqCode.substring(0, 1).uppercase() + CurrentTxn.acqCode.substring(1).lowercase()
        val emvTag = EmvTag()
        var ptrLen = 0

        helperLog.appendLine(logClassName, "parseRespSignOn :: ${HexUtil.bytesToHexString(isoByte, 0, byteArrayLen[0])}")
        ptrLen = emvTag.getValueFrom(isoByte, "BF58", ptrValue)

        val rawIpek = ByteOps.byteArrayToAsciiString(ptrValue, 0, ptrLen)
        helperLog.appendLine(logClassName, "ptrLen :: $ptrLen")
        helperLog.appendLine(logClassName, "Hex :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(ptrValue, 0, ptrLen)}")
        helperLog.appendLine(logClassName, "ASC :: ")
        helperLog.appendLine(logClassName, "\t${ByteOps.byteArrayToAsciiString(ptrValue, 0, ptrLen)}")

        if(rawIpek.length <  51){
            helperLog.appendLine(logClassName, "Invalid key length for downloaded EncKey")
            return result
        }

        val strSecureTleScheme = "visam-tle$secureLabel-${CurrentTxn.mid}"
        val keyLeftHex = CurrentStore.secureValue(context, "eKeyLeft", strSecureTleScheme) ?: ""
        val keyRightHex = CurrentStore.secureValue(context, "eKeyRight", strSecureTleScheme) ?: ""

        val strIpek: String = rawIpek.substring(4, 36)
        val strKsn: String = rawIpek.substring(36) + "00000"
        val bKsn = HexUtil.hexStringToByte(strKsn)

        try{
            // Convert hex strings to byte arrays
            val component1Bytes = HexUtil.hexStringToByte(keyLeftHex)
            val component2Bytes = HexUtil.hexStringToByte(keyRightHex)

            // XOR the components to get the final TMK
            val tmkBytes: ByteArray = Dukpt.xorByteArrays(component1Bytes, component2Bytes)

            // Convert the encrypted IPEK from hex string to byte array
            val encryptedIpekBytes = HexUtil.hexStringToByte(strIpek)

            // Decrypt the IPEK using the final TMK
            val decryptedIpekBytes: ByteArray = Dukpt.decryptIpek(tmkBytes, encryptedIpekBytes)

            helperLog.appendLine(logClassName, "KSN >> $strKsn" )
            CurrentStore.storeSecureValue(context, "eTmkKey", strSecureTleScheme, decryptedIpekBytes, decryptedIpekBytes.size)
            CurrentStore.storeSecureValue(context, "eTmkId", strSecureTleScheme, bKsn, bKsn.size)

            // Derived Future Key from IPEK
            result = derivedFutureKey(context, helperLog)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return result
    }

    fun derivedFutureKey(context: Context, helperLog: HelperLog): Boolean {
        helperLog.appendLine(logClassName, "-------------------------------- Derived Future Key From IPEK ----------------------------------------")
        var result = false
        secureLabel = CurrentTxn.acqCode.substring(0, 1).uppercase() + CurrentTxn.acqCode.substring(1).lowercase()
        val strSecureTleScheme = "visam-tle$secureLabel-${CurrentTxn.mid}"
        val strIpek = CurrentStore.secureValue(context, "eTmkKey", strSecureTleScheme) ?: ""
        var strKsn = CurrentStore.secureValue(context, "eTmkId", strSecureTleScheme) ?: ""
        if(strIpek.isEmpty() || strKsn.isEmpty()) {
            return result
        }
        helperLog.appendLine(logClassName, "Old KSN :: $strKsn")
        strKsn = Dukpt.getNewKsn(strKsn)
        helperLog.appendLine(logClassName, "New KSN :: $strKsn")

        try {
            val bIPek = HexUtil.hexStringToByte(strIpek)
            val bKsn = HexUtil.hexStringToByte(strKsn)
            helperLog.appendLine(logClassName, "KSN :: ${HexUtil.bytesToHexString(bKsn)}")
            val bPinKey: ByteArray = ftkDeriv("PIN", bIPek, strKsn)
            val bMacKey: ByteArray = ftkDeriv("MAC", bIPek, strKsn)
            val bDataKey: ByteArray = ftkDeriv("DATA", bIPek, strKsn)

            CurrentStore.storeSecureValue(context, "eTmkId", strSecureTleScheme, bKsn, bKsn.size)
            CurrentStore.storeSecureValue(context, "eTpkKey", strSecureTleScheme, bPinKey, bPinKey.size)
            CurrentStore.storeSecureValue(context, "eWakKey", strSecureTleScheme, bMacKey, bMacKey.size)
            CurrentStore.storeSecureValue(context, "eWekKey", strSecureTleScheme, bDataKey, bDataKey.size)
            result = true
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return result
    }

    @Throws(java.lang.Exception::class)
    fun ftkDeriv(keyType: String, ipek: ByteArray, ksnHexString: String): ByteArray {
        val ksn = Dukpt.toByteArray(ksnHexString)
        val dataKey: ByteArray = when (keyType) {
            "DATA" -> {
                val dukptVariant = DukptVariant(Dukpt.KEY_REGISTER_BITMASK, Dukpt.DATA_VARIANT_BITMASK)
                val derivedKey: ByteArray = dukptVariant.ipekComputeKey(ipek, ksn)
                dukptVariant.toDataKey(derivedKey)
            }
            "MAC" -> {
                val dukptVariant = DukptVariant(Dukpt.KEY_REGISTER_BITMASK, Dukpt.MAC_VARIANT_BITMASK)
                val derivedKey: ByteArray = dukptVariant.ipekComputeKey(ipek, ksn)
                derivedKey
            }
            "PIN" -> {
                val dukptVariant = DukptVariant(Dukpt.KEY_REGISTER_BITMASK, Dukpt.PIN_VARIANT_BITMASK)
                val derivedKey: ByteArray = dukptVariant.ipekComputeKey(ipek, ksn)
                derivedKey
            }
            else -> {
                throw IllegalArgumentException("Invalid key type: $keyType")
            }
        }
        // D7: never log the FTK or the derived data key.
        return dataKey
    }
}