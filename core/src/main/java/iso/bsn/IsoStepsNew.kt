package iso.bsn

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
import android.util.Base64
import androidx.annotation.RequiresApi
import crypto.Dukpt
import crypto.DukptVariant
import emv.EmvTag
import crypto.Encryption
import constants.TerminalConstants
import iso.IsoUtil
import utils.HexUtil
import helpers.HelperLog
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object IsoStepsBSNNew {
    var ptrValue = ByteArray(1024)
    var ptrValue2 = ByteArray(1024)
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
        val strMti = CurrentTxn.getFromTransactionDb(TerminalConstants.iso.tag.MTI, 16)
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
        CurrentTxn.removeTlvFromTransDb("DF57")
        CurrentTxn.removeTlvFromTransDb("DF59")
        CurrentTxn.removeTlvFromTransDb("DF64")

        // Copy DE37, 38, 39 from RESP TO CMD
        CurrentTxn.removeTlvFromTransDb("DF37")
        //val strRRN = CurrentTxn.getFromTransactionDb("BF37", 16)
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

        val strCardPan = CurrentTxn.getFromTransactionDb("DF02", 16)
        if(strCardPan.isEmpty()){
            println("DF02 is null. Get from D5 now")
            val d5Pan = CurrentTxn.getFromTransactionDb(TerminalConstants.iso.tag.PANSTRING, 16)
            CurrentTxn.removeTlvFromTransDb("DF02")
            CurrentTxn.addHexStrWithPadIntoTransDB("DF02", d5Pan, "F")
        }

        // Remove Response DB that required updated from new response
        CurrentTxn.removeTlvFromTransDb("BF00")
        CurrentTxn.removeTlvFromTransDb("BF11")
        CurrentTxn.removeTlvFromTransDb("BF37")
        //CurrentTxn.removeTlvFromTransDb("BF38") //BSN VOID CANNOT remove
        CurrentTxn.removeTlvFromTransDb("BF39")
        CurrentTxn.removeTlvFromTransDb("BF57")
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

        if(CurrentTxn.eppTenureCode.isNotEmpty()) {
            helperLog.appendLine(logClassName, "EPP Tenure :: ${CurrentTxn.eppTenure}")
            helperLog.appendLine(logClassName, "EPP Tenure Code :: ${CurrentTxn.eppTenureCode}")
            CurrentTxn.removeTlvFromTransDb("DF60")
            CurrentTxn.addHexStrIntoTransDB("DF60", HexUtil.str2HexStr(CurrentTxn.eppTenureCode))
        }

        if (CurrentTxn.txnTypeLabel == "Void Instalment"){
            val productModel = CurrentStore.acquirerProduct(context, CurrentTxn.mid, CurrentTxn.tid)
            if (productModel != null) {
                CurrentTxn.ksn = productModel.ksn
                CurrentTxn.pinKsn = productModel.pinKsn
                CurrentTxn.product = productModel.product
                helperLog.appendLine(logClassName, "Changed Product :: ${CurrentTxn.product}")
                helperLog.appendLine(logClassName, "Changed EPP Ksn :: ${CurrentTxn.ksn}")
            }
        }

        if (CurrentTxn.onlinePinInput || CurrentTxn.offlinePinInput) {
            var newPinKsn = ""
            try {
                newPinKsn = Dukpt.getNewKsn(CurrentTxn.pinKsn)
                CurrentStore.updateProduct(context, mapOf("PinKsn" to newPinKsn), mapOf("Product" to CurrentTxn.product, "AcqMid" to CurrentTxn.mid, "AcqTid" to CurrentTxn.tid))
                newPinKsn = newPinKsn.substring(4)
            }catch (ex: Exception) {
                ex.printStackTrace()
            }
            helperLog.appendLine(logClassName, "Old Pin Ksn :: ${CurrentTxn.pinKsn}")
            helperLog.appendLine(logClassName, "New Pin Ksn :: $newPinKsn")
            CurrentTxn.addHexStrIntoTransDB("DF53", newPinKsn)
        }

        helperLog.appendLine(logClassName, "Old Ksn :: ${CurrentTxn.ksn}")
        var newKsn = ""
        try{
            newKsn = Dukpt.getNewKsn(CurrentTxn.ksn)
            CurrentStore.updateProduct(context, mapOf("Ksn" to newKsn), mapOf("Product" to CurrentTxn.product, "AcqMid" to CurrentTxn.mid, "AcqTid" to CurrentTxn.tid))
        }catch (e: Exception){
            e.printStackTrace()
        }
        helperLog.appendLine(logClassName, "New Ksn :: $newKsn")
        helperLog.appendLine(logClassName, "FormIsoMessage for Des ($desFieldLen) :: ${desField.joinToString { it.toString() }}")
        helperLog.appendLine(logClassName, "Sensitive Des Field ($encDesFieldLen) :: ${encDesField.joinToString { it.toString() }}")

        val refTag = "${CurrentTxn.schemeTag}-tle${CurrentTxn.acqCode.lowercase().replaceFirstChar { it.uppercase() }}-${CurrentTxn.mid}"
        val strWakKey = CurrentStore.secureValue(context, "eWakKey", refTag) ?: ""
        val strWekKey = CurrentStore.secureValue(context, "eWekKey", refTag) ?: ""
        if(strWakKey.isEmpty() || strWekKey.isEmpty()) {
            CurrentTxn.transResult = TerminalConstants.iso.err.txnDeclined_emptySecureKey
            CurrentTxn.respCode = ByteOps.ASCIItoHexString("ZV")
            return
        }

        // Tle Version
        val de57 = ByteArray(1000)
        var iDe57Len = 0

        // Generate DE57
        var de57HexString = "123456"
        val encDe57 = ByteArray(1024)
        var iEncDe57Len = 0

        if(encDesField[0] != 0){
            // DEs
            repeat(encDesFieldLen){
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

                            var strTrack2 = HexUtil.bytesToHexString(ptrValue, 0, iThisLen)
                            if (byteLastTrack2Byte.toInt() and 0x0F == 0x0F) {
                                strTrack2 = strTrack2.substring(0, strTrack2.length - 1)
                            }
                            /*var tt = strTrack2
                            tt = tt.substring(0, tt.length - 1)
                            println("data :: $strTrack2")
                            println("tt :: $tt")*/
                            strTrack2 = StringUtils.paddingWith(iThisField.toString(), "0", 3, false) + "0" + StringUtils.paddingWith(strTrack2.length.toString(), "0", 3, false) + strTrack2
                            helperLog.appendLine(logClassName, "new strTrack2 :: ${LogRedact.track2(strTrack2)}")
                            de57HexString += strTrack2
                        }
                        2, 14, 45, 48, 52, 55, 58, 60, 63 -> {
                           /*
                            var de57EncMethod = 0
                            if(iThisField == 60 || iThisField == 63){
                                de57EncMethod = 1
                            }

                            var tempSenString = HexUtil.bytesToHexString(ptrValue, 0, iThisLen)
                            if (iThisField == 2 || iThisField == 14) {
                                tempSenString = tempSenString.replace("F", "")
                            }
                            val newSenString = StringUtils.paddingWith(iThisField.toString(), "0", 3, false) + de57EncMethod + StringUtils.paddingWith(tempSenString.length.toString(), "0", 3, false) + tempSenString
                            de57HexString += newSenString
                           * */
                            var de57EncMethod = 1
                            if(iThisField == 52 || iThisField == 55){
                                de57EncMethod = 0
                            }

                            var tempSenString = HexUtil.bytesToHexString(ptrValue, 0, iThisLen)
                            if (iThisField == 2 || iThisField == 14) {
                                tempSenString = tempSenString.replace("F", "")
                                tempSenString = ByteOps.ASCIItoHexString(tempSenString)
                            }
                            val newSenString = StringUtils.paddingWith(iThisField.toString(), "0", 3, false) + de57EncMethod + StringUtils.paddingWith(tempSenString.length.toString(), "0", 3, false) + tempSenString
                            de57HexString += newSenString
                        }
                        else -> {
                            helperLog.appendLine(logClassName, "ERR: Unable to obtain data for DE-$iThisField")
                        }
                    }
                }
            }

            if(de57HexString.length > 6){
                certLog { "CLEAR before padding [] :: $de57HexString" }

                if((de57HexString.length % 2) != 0){
                    de57HexString += 0
                    certLog { "NEW CLEAR before padding [] :: $de57HexString" }
                }

                val clearByteDe57 = ByteArray(1024)
                val tempDE57 = HexUtil.hexStringToByte(de57HexString)
                ByteOps.memcpy(clearByteDe57, tempDE57, tempDE57.size)
                var iClearByteDe57Len: Int = tempDE57.size

                // Add Padding
                val padRemaining = iClearByteDe57Len % 8
                var iPadlen = 7
                if (padRemaining != 0) {
                    iPadlen = 7 - padRemaining
                }
                ByteOps.memset(clearByteDe57, iClearByteDe57Len, 0.toByte(), iPadlen)
                iClearByteDe57Len += iPadlen

                // Appended bytes to match 8 bytes
                val t = iPadlen + 1
                clearByteDe57[iClearByteDe57Len++] = t.toByte()
                //println("CLEAR after padding [] > ${HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)}")

                var strClearDe57 = ""
                strClearDe57 = HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)
                certLog { "strlen(strClearDe57) :: ${ByteOps.strlen(strClearDe57)}" }
                certLog { "CLEAR after padding [] :: $strClearDe57" }

                helperLog.appendLine(logClassName, "=======DUKPT[START]=======")
                var TleKcvDe57 = ByteArray(3)
                var dukpt = ByteArray(16)
                try {
                    dukpt = dukptDeriv(ByteOps.hexStringToByteArray(strWekKey), newKsn)
                    dukpt.copyInto(CurrentTxn.dukpt)
                    TleKcvDe57 = KCVDeriv(dukpt)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                var strEncDe57: String? = null
                try {
                    strEncDe57 = Encryption.encrypt(strClearDe57, Dukpt.toHex(dukpt), "DESede", "CBC")
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                }
                helperLog.appendLine(logClassName, "strEncDe57 :: $strEncDe57")
                val bEncDe57 = HexUtil.hexStringToByte(strEncDe57)
                val encodedBytes = Base64.encode(bEncDe57, Base64.DEFAULT or Base64.NO_WRAP)
                ByteOps.memcpy(encDe57, iEncDe57Len, encodedBytes, 0, encodedBytes.size)
                iEncDe57Len += encodedBytes.size
                helperLog.appendLine(logClassName, "ENCRYPTED[] :: ${HexUtil.bytesToHexString(encDe57, 0 , iEncDe57Len)}")
                helperLog.appendLine(logClassName, "bEncDe57.length :: ${bEncDe57.size}")
                helperLog.appendLine(logClassName, "iEncDe57Len :: $iEncDe57Len")
                helperLog.appendLine(logClassName, "=======DUKPT[END]=======")

                //TLE Header
                val applicationId = "0002"
                val keyId = "FFF000000000"
                val headerVer = "0001"
                val encAlgo = "01"
                val uniqueKeyType = "03"
                val macAlgo = "04"
                val delimiterChar = "01"
                val eMode = "01"
                val tidLength = AmountFormat.zeroPadding(CurrentTxn.tid.length.toString(), 2)
                val tleTid = StringUtils.paddingWith(CurrentTxn.tid, "0", 16, true)

                val hexTleHeader = ByteOps.ASCIItoHexString(applicationId + keyId + headerVer + encAlgo + uniqueKeyType + macAlgo + newKsn + delimiterChar + eMode + tidLength + tleTid + Dukpt.toHex(TleKcvDe57))
                val tleHeader = HexUtil.hexStringToByte(hexTleHeader)
                //TLE Header

                ByteOps.memcpy(de57, 0, tleHeader, 0, tleHeader.size)
                iDe57Len += tleHeader.size
                helperLog.appendLine(logClassName, "tleHeaderLen :: ${tleHeader.size}")

                ByteOps.memcpy(de57, tleHeader.size, encDe57, 0, iEncDe57Len)
                iDe57Len += iEncDe57Len
                helperLog.appendLine(logClassName, "iDe57Len :: $iDe57Len")
            }
        }
        CurrentTxn.addTlvIntoTransDB("DF57", de57, 0 , iDe57Len)

        // Form Message from MTI to DE63 to sha1
        val macIsoByte = ByteArray(6000)
        var macIsoByteLen = destByteOffset
        var isoByteLen = destByteOffset

        var ptrLen = 0
        //var iRespLen = destByteOffset
        // TPDU
        ptrLen = etag.getValueFrom(CurrentTxn.transactionDb, TerminalConstants.iso.tag.TPDU, ptrValue)
        //if (ptrLen < 0) {
        //IsoSteps.sysPrint("ISOS:ERR: Failed to retrieve TPDU header")
        //return TerminalConstants.iso.err.fileNotFound
        //}
        ByteOps.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
        macIsoByteLen += ptrLen
        ByteOps.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
        isoByteLen += ptrLen

        // MTI
        ptrLen = etag.getValueFrom(CurrentTxn.transactionDb, TerminalConstants.iso.tag.MTI, ptrValue)
        //if (ptrLen < 0) {
        //IsoSteps.sysPrint("ISOS:ERR: Failed to retrieve MTI")
        //return TerminalConstants.iso.err.fileNotFound
        //}
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
        isou.bmpAlterBit(57, 0.toByte(), macIsoByte, iCacheLen)
        isou.bmpAlterBit(64, 0.toByte(), macIsoByte, iCacheLen)
        ByteOps.memcpy(isoByte, isoByteLen, bBmp, 0, 8)
        isoByteLen += 8

        try {
            helperLog.appendLine(logClassName, "ENCODE-ISO :: ")
            repeat(desFieldLen){
                val iFieldId: Int = desField[it]

                val tempValue = ByteArray(2000)
                val tempLen = 0

                val indexOfEnc = encDesField.indexOf(iFieldId)
                val iThisFieldLen = setField(iFieldId, CurrentTxn.transactionDb, tempValue, tempLen, true)
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
        certLog { "Mac Iso :: " }
        certLog { "\t${HexUtil.bytesToHexString(macIsoByte, 0,  macIsoByteLen)}" }
        helperLog.appendLine(logClassName, "Iso Byte :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")

        // Generate Mac
        val mac: ByteArray
        val hash = ByteArray(1000)
        ByteOps.memset(hash, 0.toByte(), hash.size)
        var strHash = DataHash.hashDataWithClearText(HexUtil.bytesToHexString(macIsoByte, 7, macIsoByteLen - 7))
        helperLog.appendLine(logClassName, "hash :: $strHash")
        val tempHash = HexUtil.hexStringToByte(strHash)
        ByteOps.memcpy(hash, 0, tempHash, 0, tempHash.size)

        // Add Padding
        var hashLen = tempHash.size
        var iHashPadlen = hashLen % 8
        if (iHashPadlen != 0) {
            hash[hashLen++] = 0x80.toByte()
            iHashPadlen = 7 - iHashPadlen
            ByteOps.memset(hash, hashLen, 0.toByte(), iHashPadlen)
            hashLen += iHashPadlen
            strHash = HexUtil.bytesToHexString(hash, 0, hashLen)
        }
        helperLog.appendLine(logClassName, "hash[] :: ${HexUtil.bytesToHexString(hash)}")
        helperLog.appendLine(logClassName, "strHash After  :: $strHash")

        var strMac = ""
        try {
            val fullMac = Encryption.encrypt(strHash, strWakKey, "DESede", "CBC")
            val bFullMac = HexUtil.hexStringToByte(fullMac)
            val macByte = ByteArray(8)
            ByteOps.memcpy(macByte, 0, bFullMac, bFullMac.size - 8, 8)
            strMac = HexUtil.bytesToHexString(macByte)
            certLog { "MAC :: $strMac" }

            mac = ByteOps.hexStringToByteArray(strMac)
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
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            helperLog.appendLine(logClassName, "MAC Generation Error")
            isoByteLen = 0
        }
        byteArrayLen[0] = isoByteLen
        helperLog.appendLine(logClassName, "Iso Byte after MAC ($isoByteLen):: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")
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
                    val newLastByteInt = byteLastTrack2Byte.toInt() and 0xF0
                    dataout[dataoutOffset + iLenSize + iResp - 1] = newLastByteInt.toByte()
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
        helperLog.appendLine(logClassName, "Parsing ISO Response")

        val isou = IsoUtil()
        val destByteLen = IntArray(2)
        destByteLen[0] = CurrentTxn.transactionDbLen

        val iResp = isou.msgDecode(isoRespByte, isoRespByteOffset, isoRespByteLen, CurrentTxn.transactionDb, destByteLen)
        if(iResp < 0){
            helperLog.appendLine(logClassName, "ERR: Failed to parse ISO Resp. Handle as Timeout")
        } else {
            CurrentTxn.transactionDbLen = destByteLen[0]
        }

        //Decryption of Field-57
        var bf57 = CurrentTxn.getFromTransactionDb("BF57", 256)
        helperLog.appendLine(logClassName, "raw bf57 :: $bf57")
        if(bf57.isNotEmpty()){
            //Remove TLE Header
            bf57 = bf57.substring(74)
            helperLog.appendLine(logClassName, "bf57 :: $bf57")
            val decodedBytes: ByteArray = Base64.decode(bf57, Base64.DEFAULT or Base64.NO_WRAP)
            var strClearDe57resp = ""
            certLog { "Dukpt Hex :: ${Dukpt.toHex(CurrentTxn.dukpt)}" }
            helperLog.appendLine(logClassName, "Decoded Base64 :: ${HexUtil.bytesToHexString(decodedBytes)}")

            try {
                strClearDe57resp = Encryption.decrypt(HexUtil.bytesToHexString(decodedBytes), Dukpt.toHex(CurrentTxn.dukpt), "DESede", "CBC")
                certLog { "strClearDe57resp :: $strClearDe57resp" }
                val de57padlen = strClearDe57resp.substring(strClearDe57resp.length - 1).toInt()
                helperLog.appendLine(logClassName, "de57padlen :: $de57padlen")
                var sensDataDE57 = strClearDe57resp.substring(6, strClearDe57resp.length - de57padlen * 2)
                var i = sensDataDE57.length
                while (i > 3) {
                    helperLog.appendLine(logClassName, "sensDataDE57 :: $sensDataDE57")
                    val tag = sensDataDE57.substring(0, 3).toInt()
                    val enc = sensDataDE57.substring(3, 4).toInt() //Encoding Method: 0=none, 1=ASCII
                    val len = sensDataDE57.substring(4, 7).toInt()
                    val value = sensDataDE57.substring(7, 7 + len)

                    helperLog.appendLine(logClassName, "\tDE${String.format("%02d", tag)} :: $value")
                    CurrentTxn.addHexStrIntoTransDB("BF" + String.format("%02d", tag), value)
                    sensDataDE57 = sensDataDE57.substring(7 + len)
                    i = sensDataDE57.length
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        } else {
            helperLog.appendLine(logClassName, "SKIP Parse DE57 Response, DE57 not found")
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun parseIsoRespIsolate(helperLog: HelperLog, isoRespByte: ByteArray, isoRespByteOffset: Int, isoRespByteLen: Int, returnByte: ByteArray, returnByteLenArr: IntArray, mapVariable: MutableMap<String, String>) {
        helperLog.appendLine(logClassName, "Parsing Isolate Iso Response")
        val isou = IsoUtil()
        val emvTag = EmvTag()

        var returnByteLen = 0
        val iResp = isou.msgDecode(isoRespByte, isoRespByteOffset, isoRespByteLen, returnByte, returnByteLenArr)
        if(iResp < 0){
            helperLog.appendLine(logClassName, "ERR: Failed to parse ISO Resp. Handle as Timeout")
        } else {
            returnByteLen = returnByteLenArr[0]
        }

        val ptrLen: Int
        //Decryption of Field-57
        var bf57 = ""
        ptrLen = emvTag.getValueFrom(returnByte, "BF57", ptrValue)
        if(ptrLen > 0){
            //bf57 = ByteOps.byteArrayToHexString(ptrValue, 0 , ptrLen)
            //bf57 = ByteOps.byteArrayToAsciiString(ByteOps.hexStringToByteArray(bf57))
            bf57 = ByteOps.byteArrayToAsciiString(ptrValue, 0 , ptrLen)
        }

        helperLog.appendLine(logClassName, "raw bf57 :: $bf57")
        if(bf57.isNotEmpty()){
            //Remove TLE Header
            bf57 = bf57.substring(74)
            helperLog.appendLine(logClassName, "bf57 :: $bf57")

            try {
                val decodedBytes: ByteArray = Base64.decode(bf57, Base64.DEFAULT or Base64.NO_WRAP)
                var strClearDe57resp = ""

                var dukpt = ""
                if(mapVariable.containsKey("dukpt")){
                    dukpt = mapVariable.get("dukpt")!!
                }
                //TODO DUKPT
                certLog { "Dukpt Hex :: $dukpt" }
                helperLog.appendLine(logClassName, "Decoded Base64 :: ${HexUtil.bytesToHexString(decodedBytes)}")

                //TODO DUKPT
                strClearDe57resp = Encryption.decrypt(HexUtil.bytesToHexString(decodedBytes), dukpt, "DESede", "CBC")
                certLog { "strClearDe57resp :: $strClearDe57resp" }
                val de57padlen = strClearDe57resp.substring(strClearDe57resp.length - 1).toInt()
                helperLog.appendLine(logClassName, "de57padlen :: $de57padlen")
                var sensDataDE57 = strClearDe57resp.substring(6, strClearDe57resp.length - de57padlen * 2)
                var i = sensDataDE57.length
                while (i > 3) {
                    helperLog.appendLine(logClassName, "sensDataDE57 :: $sensDataDE57")
                    val tag = sensDataDE57.substring(0, 3).toInt()
                    val enc = sensDataDE57.substring(3, 4).toInt() //Encoding Method: 0=none, 1=ASCII
                    val len = sensDataDE57.substring(4, 7).toInt()
                    val value = sensDataDE57.substring(7, 7 + len)

                    helperLog.appendLine(logClassName, "\tDE${String.format("%02d", tag)} :: $value")
                    emvTag.addTlvByTvHexString("BF${String.format("%02d", tag)}", value, 0, value.length, returnByte)
                    returnByteLen += value.length / 2
                    sensDataDE57 = sensDataDE57.substring(7 + len)
                    i = sensDataDE57.length
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        } else {
            helperLog.appendLine(logClassName, "SKIP Parse DE57 Response, DE57 not found")
        }

        returnByteLenArr[0] = returnByteLen
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun formIsoIsolate(context: Context, helperLog: HelperLog, isoInfoModel: IsoInfoModel, prevIsoString: String, isoByte: ByteArray, byteArrayLen: IntArray): MutableMap<String, String> {
        helperLog.appendLine(logClassName, "Forming Iso Isolate")
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
        helperLog.appendLine(logClassName, "Stan :: $stan")
        val invoiceNo = CurrentStore.allocateCounter(context, "invoiceNo", "pos")
        helperLog.appendLine(logClassName, "Invoice No :: $invoiceNo")

        ptrLen = emvTag.getValueFrom(previousIsoByte, "BF37", ptrValue)
        if(ptrLen > 0){
            val refNum = HexUtil.bytesToHexString(ptrValue, 0 , ptrLen)
            emvTag.addTlvByTvHexString("DF37", refNum, 0, refNum.length, tempBuffArray)
        }

        ptrLen = emvTag.getValueFrom(previousIsoByte, "BF38", ptrValue)
        if(ptrLen > 0){
            val apvCde = HexUtil.bytesToHexString(ptrValue, 0 , ptrLen)
            emvTag.addTlvByTvHexString("DF38", apvCde, 0, apvCde.length, tempBuffArray)
        }

        ptrLen = emvTag.getValueFrom(previousIsoByte, "BF39", ptrValue)
        if(ptrLen > 0){
            val respCde = HexUtil.bytesToHexString(ptrValue, 0 , ptrLen)
            emvTag.addTlvByTvHexString("DF39", respCde, 0, respCde.length, tempBuffArray)
        }

        val productModel = CurrentStore.acquirerProduct(context, acqMid, acqTid)
        productModel?.let {
            acqCode = it.acqCode
            ksn = it.ksn
            pinKsn = it.pinKsn
            try {
                ksn = Dukpt.getNewKsn(ksn)
                helperLog.appendLine(logClassName, "newKsn :: $ksn")
                CurrentStore.updateProduct(context, mapOf("Ksn" to ksn), mapOf("AcqMid" to it.acqMid, "AcqTid" to it.acqTid))
            } catch (e: Exception){
                e.printStackTrace()
                ksn = ""
            }
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

        emvTag.removeTlvByT(tempBuffArray, "DF57")
        emvTag.removeTlvByT(tempBuffArray, "DF59")

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
                    val hexStrMti = ByteOps.ASCIItoHexString(strMti)
                    tempDE60+=hexStrMti
                }
                ptrLen = emvTag.getValueFrom(previousIsoByte, "DF11", ptrValue)
                if(ptrLen > 0){
                    val strStan = HexUtil.bytesToHexString(ptrValue, 0 , ptrLen)
                    val hexStrStan = ByteOps.ASCIItoHexString(strStan)
                    tempDE60+=hexStrStan
                }
                ptrLen = emvTag.getValueFrom(previousIsoByte, "BF37", ptrValue)
                if(ptrLen > 0){
                    val refNum = HexUtil.bytesToHexString(ptrValue, 0 , ptrLen)
                    tempDE60+=refNum
                }
                emvTag.removeTlvByT(tempBuffArray, "DF60")
                emvTag.addTlvByTvHexString("DF60", tempDE60, 0, tempDE60.length, tempBuffArray)
            }
        }

        emvTag.removeTlvByT(tempBuffArray, "DF64")

        // Remove Response DB that required updated from new response
        CurrentTxn.removeTlvFromTransDb("BF00")
        CurrentTxn.removeTlvFromTransDb("BF11")
        CurrentTxn.removeTlvFromTransDb("BF37")
        //CurrentTxn.removeTlvFromTransDb("BF38") //BSN VOID CANNOT remove
        CurrentTxn.removeTlvFromTransDb("BF39")
        CurrentTxn.removeTlvFromTransDb("BF57")
        emvTag.removeTlvByT(tempBuffArray, "BF00")
        emvTag.removeTlvByT(tempBuffArray, "BF11")
        emvTag.removeTlvByT(tempBuffArray, "BF37")
        emvTag.removeTlvByT(tempBuffArray, "BF39")
        emvTag.removeTlvByT(tempBuffArray, "BF57")

        val desField = IntArray(30)
        val desFieldLen = isou.splitIntegerText(isoInfoModel.transactionDes!!, " ", desField)
        val encDesField = IntArray(30)
        val encDesFieldLen = isou.splitIntegerText(isoInfoModel.transactionDesSensitive!!, " ", encDesField)
        helperLog.appendLine(logClassName, "FormIsoMessage for Des ($desFieldLen) :: ${desField.joinToString { it.toString() }}")
        helperLog.appendLine(logClassName, "Sensitive Des Field ($encDesFieldLen) :: ${encDesField.joinToString { it.toString() }}")

        val refTag = "$schemeTag-tle${acqCode.lowercase().replaceFirstChar { it.uppercase() }}-${acqMid}"
        val strWakKey = CurrentStore.secureValue(context, "eWakKey", refTag) ?: ""
        val strWekKey = CurrentStore.secureValue(context, "eWekKey", refTag) ?: ""
        if(strWakKey.isEmpty() || strWekKey.isEmpty()) {
            CurrentTxn.transResult = TerminalConstants.iso.err.txnDeclined_emptySecureKey
            CurrentTxn.respCode = ByteOps.ASCIItoHexString("ZV")
            return variantMap
        }

        // Tle Version
        val de57 = ByteArray(1000)
        var iDe57Len = 0

        // Generate DE57
        var de57HexString = "123456"
        val encDe57 = ByteArray(1024)
        var iEncDe57Len = 0

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

                            var strTrack2 = HexUtil.bytesToHexString(ptrValue, 0, iThisLen)
                            if (byteLastTrack2Byte.toInt() and 0x0F == 0x0F) {
                                strTrack2 = strTrack2.substring(0, strTrack2.length - 1)
                            }
                            /*var tt = strTrack2
                            tt = tt.substring(0, tt.length - 1)
                            println("data :: $strTrack2")
                            println("tt :: $tt")*/
                            strTrack2 = StringUtils.paddingWith(iThisField.toString(), "0", 3, false) + "0" + StringUtils.paddingWith(strTrack2.length.toString(), "0", 3, false) + strTrack2
                            helperLog.appendLine(logClassName, "new strTrack2 :: ${LogRedact.track2(strTrack2)}")
                            de57HexString += strTrack2
                        }
                        2, 14, 45, 48, 52, 55, 58, 60, 63 -> {
                            /*
                            var de57EncMethod = 0
                            if(iThisField == 60 || iThisField == 63){
                                de57EncMethod = 1
                            }

                            var tempSenString = HexUtil.bytesToHexString(ptrValue, 0, iThisLen)
                            if (iThisField == 2 || iThisField == 14) {
                                tempSenString = tempSenString.replace("F", "")
                            }
                            val newSenString = StringUtils.paddingWith(iThisField.toString(), "0", 3, false) + de57EncMethod + StringUtils.paddingWith(tempSenString.length.toString(), "0", 3, false) + tempSenString
                            de57HexString += newSenString
                            * */
                            var de57EncMethod = 1
                            if(iThisField == 52 || iThisField == 55){
                                de57EncMethod = 0
                            }

                            var tempSenString = HexUtil.bytesToHexString(ptrValue, 0, iThisLen)
                            if (iThisField == 2 || iThisField == 14) {
                                tempSenString = tempSenString.replace("F", "")
                                tempSenString = ByteOps.ASCIItoHexString(tempSenString)
                            }
                            val newSenString = StringUtils.paddingWith(iThisField.toString(), "0", 3, false) + de57EncMethod + StringUtils.paddingWith(tempSenString.length.toString(), "0", 3, false) + tempSenString
                            de57HexString += newSenString
                        }
                        else -> {
                            helperLog.appendLine(logClassName, "ERR: Unable to obtain data for DE-$iThisField")
                        }
                    }
                }
            }

            if(de57HexString.length > 6){
                certLog { "CLEAR before padding [] :: $de57HexString" }

                if((de57HexString.length % 2) != 0){
                    de57HexString += 0
                    certLog { "NEW CLEAR before padding [] :: $de57HexString" }
                }

                val clearByteDe57 = ByteArray(1024)
                val tempDE57 = HexUtil.hexStringToByte(de57HexString)
                ByteOps.memcpy(clearByteDe57, tempDE57, tempDE57.size)
                var iClearByteDe57Len: Int = tempDE57.size

                // Add Padding
                val padRemaining = iClearByteDe57Len % 8
                var iPadlen = 7
                if (padRemaining != 0) {
                    iPadlen = 7 - padRemaining
                }
                ByteOps.memset(clearByteDe57, iClearByteDe57Len, 0.toByte(), iPadlen)
                iClearByteDe57Len += iPadlen

                // Appended bytes to match 8 bytes
                val t = iPadlen + 1
                clearByteDe57[iClearByteDe57Len++] = t.toByte()
                certLog { "CLEAR after padding [] :: ${HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)}" }

                var strClearDe57 = ""
                strClearDe57 = HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)
                certLog { "strlen(strClearDe57) :: ${ByteOps.strlen(strClearDe57)}" }
                certLog { "CLEAR after padding [] :: $strClearDe57" }

                helperLog.appendLine(logClassName, "=======DUKPT[START]=======")
                var TleKcvDe57 = ByteArray(3)
                var dukpt = ByteArray(16)
                try {
                    dukpt = dukptDeriv(ByteOps.hexStringToByteArray(strWekKey), ksn)
                    variantMap.put("dukpt", HexUtil.bytesToHexString(dukpt))
                    TleKcvDe57 = KCVDeriv(dukpt)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                var strEncDe57: String? = null
                try {
                    strEncDe57 = Encryption.encrypt(strClearDe57, Dukpt.toHex(dukpt), "DESede", "CBC")
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                }
                helperLog.appendLine(logClassName, "strEncDe57 :: $strEncDe57")
                val bEncDe57 = HexUtil.hexStringToByte(strEncDe57)
                val encodedBytes = Base64.encode(bEncDe57, Base64.DEFAULT or Base64.NO_WRAP)
                ByteOps.memcpy(encDe57, iEncDe57Len, encodedBytes, 0, encodedBytes.size)
                iEncDe57Len += encodedBytes.size
                helperLog.appendLine(logClassName, "ENCRYPTED[] :: ${HexUtil.bytesToHexString(encDe57, 0 , iEncDe57Len)}")
                helperLog.appendLine(logClassName, "bEncDe57.length :: ${bEncDe57.size}")
                helperLog.appendLine(logClassName, "iEncDe57Len :: $iEncDe57Len")
                helperLog.appendLine(logClassName, "=======DUKPT[END]=======")

                //TLE Header
                val applicationId = "0002"
                val keyId = "FFF000000000"
                val headerVer = "0001"
                val encAlgo = "01"
                val uniqueKeyType = "03"
                val macAlgo = "04"
                val delimiterChar = "01"
                val eMode = "01"
                val tidLength = AmountFormat.zeroPadding(acqTid.length.toString(), 2)
                val tleTid = StringUtils.paddingWith(acqTid, "0", 16, true)

                val hexTleHeader = ByteOps.ASCIItoHexString(applicationId + keyId + headerVer + encAlgo + uniqueKeyType + macAlgo + ksn + delimiterChar + eMode + tidLength + tleTid + Dukpt.toHex(TleKcvDe57))
                val tleHeader = HexUtil.hexStringToByte(hexTleHeader)
                //TLE Header

                ByteOps.memcpy(de57, 0, tleHeader, 0, tleHeader.size)
                iDe57Len += tleHeader.size
                helperLog.appendLine(logClassName, "tleHeaderLen :: ${tleHeader.size}")

                ByteOps.memcpy(de57, tleHeader.size, encDe57, 0, iEncDe57Len)
                iDe57Len += iEncDe57Len
                helperLog.appendLine(logClassName, "iDe57Len :: $iDe57Len")
            }
        }
        emvTag.addTlvByTv("DF57", de57, 0, iDe57Len, tempBuffArray)

        // Form Message from MTI to DE63 to sha1
        val macIsoByte = ByteArray(6000)
        var macIsoByteLen = 2
        var isoByteLen = 2

        // TPDU
        ptrLen = emvTag.getValueFrom(tempBuffArray, TerminalConstants.iso.tag.TPDU, ptrValue)
        //if (ptrLen < 0) {
        //IsoSteps.sysPrint("ISOS:ERR: Failed to retrieve TPDU header")
        //return TerminalConstants.iso.err.fileNotFound
        //}
        ByteOps.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
        macIsoByteLen += ptrLen
        ByteOps.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
        isoByteLen += ptrLen

        // MTI
        ptrLen = emvTag.getValueFrom(tempBuffArray, TerminalConstants.iso.tag.MTI, ptrValue)
        //if (ptrLen < 0) {
        //IsoSteps.sysPrint("ISOS:ERR: Failed to retrieve MTI")
        //return TerminalConstants.iso.err.fileNotFound
        //}
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
        isou.bmpAlterBit(57, 0.toByte(), macIsoByte, iCacheLen)
        isou.bmpAlterBit(64, 0.toByte(), macIsoByte, iCacheLen)
        ByteOps.memcpy(isoByte, isoByteLen, bBmp, 0, 8)
        isoByteLen += 8

        try {
            helperLog.appendLine(logClassName, "ENCODE-ISO :: ")
            repeat(desFieldLen){
                val iFieldId: Int = desField[it]

                val tempValue = ByteArray(2000)
                val tempLen = 0

                val indexOfEnc = encDesField.indexOf(iFieldId)
                val iThisFieldLen = setField(iFieldId, tempBuffArray, tempValue, tempLen, true)
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

        certLog { "Mac Iso :: " }
        certLog { "\t${HexUtil.bytesToHexString(macIsoByte, 0,  macIsoByteLen)}" }
        helperLog.appendLine(logClassName, "Iso Byte :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")

        // Generate Mac
        val mac: ByteArray
        val hash = ByteArray(1000)
        ByteOps.memset(hash, 0.toByte(), hash.size)
        var strHash = DataHash.hashDataWithClearText(HexUtil.bytesToHexString(macIsoByte, 7, macIsoByteLen - 7))
        helperLog.appendLine(logClassName, "hash :: $strHash")
        val tempHash = HexUtil.hexStringToByte(strHash)
        ByteOps.memcpy(hash, 0, tempHash, 0, tempHash.size)

        // Add Padding
        var hashLen = tempHash.size
        var iHashPadlen = hashLen % 8
        if (iHashPadlen != 0) {
            hash[hashLen++] = 0x80.toByte()
            iHashPadlen = 7 - iHashPadlen
            ByteOps.memset(hash, hashLen, 0.toByte(), iHashPadlen)
            hashLen += iHashPadlen
            strHash = HexUtil.bytesToHexString(hash, 0, hashLen)
        }
        helperLog.appendLine(logClassName, "hash[] :: ${HexUtil.bytesToHexString(hash)}")
        helperLog.appendLine(logClassName, "strHash After :: $strHash")

        var strMac = ""
        try {
            val fullMac = Encryption.encrypt(strHash, strWakKey, "DESede", "CBC")
            val bFullMac = HexUtil.hexStringToByte(fullMac)
            val macByte = ByteArray(8)
            ByteOps.memcpy(macByte, 0, bFullMac, bFullMac.size - 8, 8)
            strMac = HexUtil.bytesToHexString(macByte)
            certLog { "MAC :: $strMac" }

            mac = ByteOps.hexStringToByteArray(strMac)
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
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            helperLog.appendLine(logClassName, "MAC Generation Error")
            isoByteLen = 0
        }
        byteArrayLen[0] = isoByteLen
        helperLog.appendLine(logClassName, "Iso Byte after MAC ($isoByteLen) :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")
        return variantMap
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Throws(java.lang.Exception::class)
    fun dukptDeriv(ipek: ByteArray, ksnHexString: String): ByteArray {
        val ksn = Dukpt.toByteArray(ksnHexString)

        // Action
        val dukptVariant = DukptVariant(Dukpt.KEY_REGISTER_BITMASK, Dukpt.DATA_VARIANT_BITMASK)
        val derivedKey = dukptVariant.ipekComputeKey(ipek, ksn)
        val dataKey = dukptVariant.toDataKey(derivedKey)
        // D7 — the derived DUKPT data key must never be logged. `println` writes to logcat in
        // every build type (no BuildConfig.DEBUG gate applies to it), so this leaked a live key
        // on production terminals. KSN is not secret on its own but is dropped too: paired with a
        // leaked key elsewhere it identifies exactly which transactions are decryptable.
        return dataKey
    }

    @Throws(java.lang.Exception::class)
    fun KCVDeriv(dukpt: ByteArray): ByteArray {
        val binaryZeroes = ByteArray(64)
        val key: SecretKey = SecretKeySpec(dukpt, "DESede")
        val cipher = Cipher.getInstance("DESede")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val cipherText = cipher.doFinal(binaryZeroes)
        val kcv = ByteArray(3)
        ByteOps.memcpy(kcv, cipherText, 3)
        // D7 — KCV is a key check value; do not print it.
        return kcv
    }

    fun derivedFutureKey(context: Context, helperLog: HelperLog): Boolean {
        return true
    }
}