package com.sc.mf919.kotlin.helper_common.iso.paydee

import android.util.Log
import com.sc.mf919.BuildConfig

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.library.terminal.Cryptography
import com.sc.mf919.java.activity.EmvTag
import com.sc.mf919.java.activity.Encryption
import com.sc.mf919.java.activity.Global
import com.sc.mf919.java.activity.IsoUtil
import com.sc.mf919.java.activity.Tlv
import com.sc.mf919.java.activity.Utils
import utils.HexUtil
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.database.repo.SecureDataRepo
import com.sc.mf919.kotlin.helper_common.iso.IsoInfoModel
import com.sc.mf919.kotlin.helper_common.iso.bsn.IsoStepsBSNNew
import helpers.HelperLog

object IsoStepsNew {
    var ptrValue = ByteArray(1024)
    var secureLabel = ""
    private val logClassName: String = this::class.java.simpleName

    /**
     * Certification-only logging. These lines carry the PRE-ENCRYPTION plaintext of DE57/DE59
     * (track2) and per-message MACs -- exactly what acquirer certification needs to debug field
     * construction, and exactly what must never leave a production terminal. appendLine would put
     * them in TerminaLog.txt, which uploadAllTerminalLog ships to TMS.
     *
     * Inline + lambda so a release build compiles this away entirely: the string is never even
     * built, nothing reaches logcat, nothing reaches the file. Debug builds get logcat only.
     */
    private inline fun certLog(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(logClassName, msg())
    }

    /** Track2 is PAN + expiry + service code. Keep the masked PAN, drop the rest. */
    private fun maskTrack2(track2: String): String {
        val sep = track2.indexOfFirst { it == 'D' || it == 'd' }
        val pan = if (sep > 0) track2.substring(0, sep) else track2
        return Utils.hideCardDetails(pan) + "D<redacted> (len=" + track2.length + ")"
    }


    @RequiresApi(Build.VERSION_CODES.O)
    fun transformBatchData(context: Context){
        val new60 = StringBuilder()
        // Backup previous value
        val df60 = TransData.getFromTransactionDb("DF60", 16)
        if(df60.isNotEmpty()){
            println("df60 :: $df60")
            new60.append(df60)
            TransData.addHexStrIntoTransDB("BF60", df60)
        }

        // Form DE60 = MTI[2] + DE11[3] + BE37-RRN[6]
        val strMti = TransData.getFromTransactionDb(Global.iso.tag.MTI, 16)
        new60.append(strMti)
        val strStan = TransData.getFromTransactionDb("DF11", 16)
        new60.append(strStan)
        val strRRN = TransData.getFromTransactionDb("BF37", 16)
        new60.append(strRRN)
        println("new60 :: $new60")
        // Form DE60 = MTI[2] + DE11[3] + BE37-RRN[6]

        //TransData.removeTlvFromTransDb(Global.iso.tag.TPDU)
        TransData.removeTlvFromTransDb(Global.iso.tag.MTI)
        TransData.removeTlvFromTransDb("DF03")
        TransData.removeTlvFromTransDb("DF11")
        TransData.removeTlvFromTransDb("DF57")
        TransData.removeTlvFromTransDb("DF59")
        TransData.removeTlvFromTransDb("DF64")

        // Copy DE37, 38, 39 from RESP TO CMD
        TransData.removeTlvFromTransDb("DF37")
        //val strRRN = TransData.getFromTransactionDb("BF37", 16)
        TransData.addHexStrIntoTransDB("DF37", strRRN)

        if (strMti != "0220") {
            TransData.removeTlvFromTransDb("DF38")
            val strAuthId = TransData.getFromTransactionDb("BF38", 16)
            TransData.addHexStrIntoTransDB("DF38", strAuthId)
        }

        TransData.removeTlvFromTransDb("DF39")
        val strRespCode = TransData.getFromTransactionDb("BF39", 16)
        TransData.addHexStrIntoTransDB("DF39", strRespCode)
        // Copy DE37, 38, 39 from RESP TO CMD

        val strCardPan = TransData.getFromTransactionDb("DF02", 16)
        if(strCardPan.isEmpty()){
            println("DF02 is null. Get from D5 now")
            val d5Pan = TransData.getFromTransactionDb(Global.iso.tag.PANSTRING, 16)
            TransData.removeTlvFromTransDb("DF02")
            TransData.addHexStrWithPadIntoTransDB("DF02", d5Pan, "F")
        }

        // Remove Response DB that required updated from new response
        TransData.removeTlvFromTransDb("BF00")
        TransData.removeTlvFromTransDb("BF11")
        TransData.removeTlvFromTransDb("BF37")
        TransData.removeTlvFromTransDb("BF38")
        TransData.removeTlvFromTransDb("BF39")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun formIsoMessage(context: Context, helperLog: HelperLog, isoInfoModel: IsoInfoModel, destByteOffset: Int, isoByte: ByteArray, byteArrayLen: IntArray) {
        val etag = EmvTag()
        val isou = IsoUtil()
        val desField = IntArray(30)
        val desFieldLen = isou.splitIntegerText(isoInfoModel.transactionDes!!, " ", desField)
        val encDesField = IntArray(30)
        val encDesFieldLen = isou.splitIntegerText(isoInfoModel.transactionDesSensitive!!, " ", encDesField)
        helperLog.appendLine(logClassName, "FormIsoMessage for Des ($desFieldLen) :: ${desField.joinToString { it.toString() }}")
        helperLog.appendLine(logClassName, "Sensitive Des Field ($encDesFieldLen) :: ${encDesField.joinToString { it.toString() }}")

        val refTag = "${TransData.schemeTag}-tle${TransData.acqCode.lowercase().replaceFirstChar { it.uppercase() }}-${TransData.mid}"
        val strWakKey = SecureDataRepo.getDecryptedSingle(context, listOf("tag", "subtag"), listOf("eWakKey", refTag))?.value ?: run { "" }
        val strWekKey = SecureDataRepo.getDecryptedSingle(context, listOf("tag", "subtag"), listOf("eWekKey", refTag))?.value ?: run { "" }
        if(strWakKey.isEmpty() || strWekKey.isEmpty()) {
            TransData.transResult = Global.iso.err.txnDeclined_emptySecureKey
            TransData.respCode = Utils.ASCIItoHexString("ZV")
            return
        }

        // Tle Version
        val encVersion = byteArrayOf(0x00, 0x04)
        val de57 = ByteArray(1000)
        Utils.memcpy(de57, encVersion, 2)
        var iDe57Len = 2

        // Generate DE57
        val encDe57 = ByteArray(1024)
        val clearByteDe57 = ByteArray(1024)
        var iClearByteDe57Len = 8 //first 8 byte is bitmap
        var iEncDe57Len = 0

        if(encDesField[0] != 0){
            val bBmp = ByteArray(8)
            isou.bmpEncode(isoInfoModel.transactionDesSensitive!!, bBmp)

            // DEs
            repeat(encDesFieldLen){
                //val iThisField: Byte = encDesField[it].toByte()
                val iThisField = encDesField[it]
                val iThisTag = "DF" + String.format("%02d", iThisField)
                val iThisLen = etag.getValueFrom(TransData.transactionDb, iThisTag, ptrValue)
                if(iThisLen > 0){
                    helperLog.appendLine(logClassName, "$iThisTag :: ${HexUtil.bytesToHexString(TransData.transactionDb, 0, iThisLen)}")
                    helperLog.appendLine(logClassName, "$iThisTag Length :: $iThisLen")
                    val bufTemp = ByteArray(256)
                    Utils.memset(bufTemp, 0.toByte(), bufTemp.size)
                    when(iThisField){
                        35 -> {
                            val byteLastTrack2Byte = ptrValue[iThisLen - 1]
                            if (byteLastTrack2Byte.toInt() and 0x0F == 0x0F) {
                                //bufTemp[iThisLen - 1] = 0x0F

                                val strTrack2 = HexUtil.bytesToHexString(ptrValue, 0, iThisLen)
                                val newTrack2 = strTrack2.replace("F", "0")
                                helperLog.appendLine(logClassName, "strTrack2 :: ${maskTrack2(strTrack2)}")
                                helperLog.appendLine(logClassName, "newTrack2 :: ${maskTrack2(newTrack2)}")

                                val bTrack2 = HexUtil.hexStringToByte(newTrack2)
                                Utils.memcpy(clearByteDe57, iClearByteDe57Len, bTrack2, 0, bTrack2.size)
                                iClearByteDe57Len += bTrack2.size
                            } else {
                                Utils.memcpy(clearByteDe57, iClearByteDe57Len, ptrValue, 0, iThisLen)
                                iClearByteDe57Len += iThisLen
                            }
                        }
                        2, 14, 45, 48, 58 -> {
                            Utils.memcpy(clearByteDe57, iClearByteDe57Len, ptrValue, 0, iThisLen)
                            iClearByteDe57Len += iThisLen
                        }
                        else -> {
                            isou.bmpAlterBit(iThisField, 0.toByte(), bBmp, 0)
                            helperLog.appendLine(logClassName, "ERR: Unable to obtain data for DE-$iThisField")
                            certLog { "CLEAR BIT, New Sensitive Field BMP :: ${HexUtil.bytesToHexString(bBmp, 0 , 8)}" }
                        }
                    }
                }
            }

            if (iClearByteDe57Len > 8) {
                //Put bitmap back to offset 0
                Utils.memcpy(clearByteDe57, bBmp, 8)
                certLog { "CLEAR before padding [] :: ${HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)}" }

                // Add Padding
                var iPadlen = iClearByteDe57Len % 8
                if (iPadlen != 0) {
                    clearByteDe57[iClearByteDe57Len++] = 0x80.toByte()
                    iPadlen = 7 - iPadlen
                    Utils.memset(clearByteDe57, iClearByteDe57Len, 0.toByte(), iPadlen)
                    iClearByteDe57Len += iPadlen
                }
                //println("CLEAR before padding [] :: ${HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)}")

                var strClearDe57 = ""
                strClearDe57 = HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)
                helperLog.appendLine(logClassName, "strlen(strClearDe57) :: ${Utils.strlen(strClearDe57)}")
                certLog { "CLEAR after padding [] :: $strClearDe57" }

                var strEncDe57: String? = null
                try {
                    strEncDe57 = Encryption.encrypt(Utils.ASCIItoHexString(strClearDe57), strWekKey, "DESede", "CBC")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                helperLog.appendLine(logClassName, "strEncDe57 :: $strEncDe57")

                val bEncDe57 = HexUtil.hexStringToByte(strEncDe57)
                Utils.memcpy(encDe57, iEncDe57Len, bEncDe57, 0, bEncDe57.size)
                iEncDe57Len += bEncDe57.size
                helperLog.appendLine(logClassName, "ENCRYPTED[] :: ${HexUtil.bytesToHexString(encDe57, 0 , iEncDe57Len)}")
                helperLog.appendLine(logClassName, "bEncDe57.length :: ${bEncDe57.size}")
                helperLog.appendLine(logClassName, "iEncDe57Len :: $iEncDe57Len")
                Utils.memcpy(de57, 2, encDe57, 0, iEncDe57Len)
                iDe57Len += iEncDe57Len
            }
        } else {
            // DE57 withouth value
            val bBmp = ByteArray(8)
            iClearByteDe57Len = 8
            Utils.memcpy(clearByteDe57, bBmp, 8)
            certLog { "CLEAR before padding [] :: ${HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)}" }

            // Add Padding
            var iPadlen = iClearByteDe57Len % 8
            if (iPadlen != 0) {
                clearByteDe57[iClearByteDe57Len++] = 0x80.toByte()
                iPadlen = 7 - iPadlen
                Utils.memset(clearByteDe57, iClearByteDe57Len, 0.toByte(), iPadlen)
                iClearByteDe57Len += iPadlen
            }
            certLog { "CLEAR after padding [] :: ${HexUtil.bytesToHexString(clearByteDe57, 0 , iClearByteDe57Len)}" }

            var strClearDe57: String? = ""
            strClearDe57 = HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)
            helperLog.appendLine(logClassName, "strlen(strClearDe57) :: ${Utils.strlen(strClearDe57)}")
            certLog { "CLEAR[] :: $strClearDe57" }

            var strEncDe57: String? = null
            try {
                strEncDe57 = Encryption.encrypt(Utils.ASCIItoHexString(strClearDe57), strWekKey, "DESede", "CBC")
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
            helperLog.appendLine(logClassName, "strEncDe57 :: $strEncDe57")
            val bEncDe57 = HexUtil.hexStringToByte(strEncDe57)

            Utils.memcpy(encDe57, iEncDe57Len, bEncDe57, 0, bEncDe57.size)
            iEncDe57Len += bEncDe57.size
            helperLog.appendLine(logClassName, "ENCRYPTED[] :: ${HexUtil.bytesToHexString(encDe57, 0 , iEncDe57Len)}")
            helperLog.appendLine(logClassName, "bEncDe57.length :: ${bEncDe57.size}")
            helperLog.appendLine(logClassName, "iEncDe57Len :: $iEncDe57Len")

            Utils.memcpy(de57, 2, encDe57, 0, iEncDe57Len)
            iDe57Len += iEncDe57Len
        }
        TransData.addTlvIntoTransDB("DF57", de57, 0 , iDe57Len)

        // Form Message from MTI to DE63 to sha1
        val macIsoByte = ByteArray(6000)
        var macIsoByteLen = destByteOffset
        var isoByteLen = destByteOffset

        var ptrLen = 0
        //var iRespLen = destByteOffset
        // TPDU
        ptrLen = etag.getValueFrom(TransData.transactionDb, Global.iso.tag.TPDU, ptrValue)
        //if (ptrLen < 0) {
        //IsoSteps.sysPrint("ISOS:ERR: Failed to retrieve TPDU header")
        //return Global.iso.err.fileNotFound
        //}
        Utils.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
        macIsoByteLen += ptrLen
        Utils.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
        isoByteLen += ptrLen

        // MTI
        ptrLen = etag.getValueFrom(TransData.transactionDb, Global.iso.tag.MTI, ptrValue)
        //if (ptrLen < 0) {
        //IsoSteps.sysPrint("ISOS:ERR: Failed to retrieve MTI")
        //return Global.iso.err.fileNotFound
        //}
        Utils.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
        macIsoByteLen += ptrLen
        Utils.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
        isoByteLen += ptrLen

        // BITMAPS
        val bBmp = ByteArray(8)
        isou.bmpEncode(isoInfoModel.transactionDes!!, bBmp)
        helperLog.appendLine(logClassName, "ISO:BMP :: ${HexUtil.bytesToHexString(bBmp, 0, 8)}")

        //Cache Len for know the BMP location
        val iCacheLen = macIsoByteLen
        Utils.memcpy(macIsoByte, macIsoByteLen, bBmp, 0, 8)
        macIsoByteLen += 8
        Utils.memcpy(isoByte, isoByteLen, bBmp, 0, 8)
        isoByteLen += 8

        try {
            helperLog.appendLine(logClassName, "ENCODE-ISO :: ")
            repeat(desFieldLen){
                val iFieldId: Int = desField[it]

                val tempValue = ByteArray(2000)
                val tempLen = 0

                val indexOfEnc = encDesField.indexOf(iFieldId)
                val iThisFieldLen = setField(iFieldId, TransData.transactionDb, tempValue, tempLen, false)
                if(iThisFieldLen > 0) {
                    helperLog.appendLine(logClassName, "\tDE$iFieldId($iThisFieldLen) :: ${HexUtil.bytesToHexString(tempValue, 0, iThisFieldLen)}")
                    //Utils.memcpy(macIsoByte, macIsoByteLen, tempValue, 0, iThisFieldLen)
                    //macIsoByteLen += iThisFieldLen
                    if(indexOfEnc == -1){
                        Utils.memcpy(isoByte, isoByteLen, tempValue, 0, iThisFieldLen)
                        isoByteLen += iThisFieldLen
                        Utils.memcpy(macIsoByte, macIsoByteLen, tempValue, 0, iThisFieldLen)
                        macIsoByteLen += iThisFieldLen
                    } else {
                        //Amending Sensitive Field to 0
                        val buffArray = ByteArray(512)
                        buffArray.fill(0, 0, buffArray.size)
                        val affectedLen = IntArray(2)
                        Tlv().decodeLen(tempValue, 0, affectedLen)
                        Utils.memcpy(buffArray, 0, tempValue, 0, affectedLen[0])

                        Utils.memcpy(isoByte, isoByteLen, buffArray, 0, iThisFieldLen)
                        isoByteLen += iThisFieldLen
                        Utils.memcpy(macIsoByte, macIsoByteLen, buffArray, 0, iThisFieldLen)
                        macIsoByteLen += iThisFieldLen
                    }
                }

                if(iThisFieldLen <= 0){
                    helperLog.appendLine(logClassName, "ISOS:ERR: Unable to obtain data for DE-$iFieldId")
                    if (iFieldId != 64) {
                        isou.bmpAlterBit(iFieldId, 0.toByte(), macIsoByte, iCacheLen)
                        isou.bmpAlterBit(iFieldId, 0.toByte(), isoByte, iCacheLen)
                    }
                }
                /*
                // Remove BMP tag for 0 value or sensitive element
                if (iThisFieldLen <= 0 || indexOfEnc >= 0) {
                    if (iFieldId != 64) {
                        isou.bmpAlterBit(iFieldId, 0.toByte(), isoByte, iCacheLen)
                    }
                }
                * */
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            helperLog.appendLine(logClassName, "Exception::${ex.message}")
            helperLog.appendLine(logClassName, "Exception::$ex")
        }

        val macFullIsoLen = macIsoByteLen - 2
        if(macFullIsoLen > 0){
            Utils.set_ushort(macFullIsoLen.toShort(), macIsoByte, 0)
        }
        val macPartialIsoLen = isoByteLen - 2
        if(macPartialIsoLen > 0){
            Utils.set_ushort(macPartialIsoLen.toShort(), isoByte, 0)
        }
        helperLog.appendLine(logClassName, "Mac Iso :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(macIsoByte, 0,  macIsoByteLen)}")
        helperLog.appendLine(logClassName, "Iso Byte :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")

        // Generate Mac
        val mac: ByteArray
        val hash = ByteArray(1000)
        Utils.memset(hash, 0.toByte(), hash.size)
        val strHash = Cryptography.hashData(HexUtil.bytesToHexString(macIsoByte, 7, macIsoByteLen - 7), Cryptography.hashAlgorithm.SHA_1)
        helperLog.appendLine(logClassName, "hash :: $strHash")
        val strMac = Cryptography.retailMac(strWakKey, strHash)
        certLog { "MAC :: $strMac" }

        mac = Utils.hexStringToByteArray(strMac)
        //mac only required left 4 byte, right 4 byte pad with 0
        Utils.memset(mac, 4, 0.toByte(), 4)
        helperLog.appendLine(logClassName, "Masked MAC :: ${HexUtil.bytesToHexString(mac, 0, 8)}")

        //Utils.memcpy(macIsoByte, macIsoByteLen, mac, 0, 8)
        //macIsoByteLen += 8
        Utils.memcpy(isoByte, isoByteLen, mac, 0, 8)
        isoByteLen += 8

        val actualMacIsoLen = macIsoByteLen - 2
        if(actualMacIsoLen > 0){
            Utils.set_ushort(actualMacIsoLen.toShort(), macIsoByte, 0)
        }
        val actualIsoLen = isoByteLen - 2
        if(actualIsoLen > 0){
            Utils.set_ushort(actualIsoLen.toShort(), isoByte, 0)
        }
        byteArrayLen[0] = isoByteLen
        helperLog.appendLine(logClassName, "Iso Byte after MAC ($isoByteLen) :: ")
        helperLog.appendLine(logClassName, HexUtil.bytesToHexString(isoByte, 0,  isoByteLen))
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
                dataout[dataoutOffset] = Utils.hex2bcd(iTrack2Length) //BCD need to times 2
                Utils.memcpy(dataout, dataoutOffset + iLenSize, ptrValue, 0, iResp)
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
            dataout[dataoutOffset] = Utils.hex2bcd(iTrack2Length) //BCD need to times 2
            Utils.memcpy(dataout, dataoutOffset + iLenSize, ptrValue, 0, iResp)
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
                Utils.memset(dataout, dataoutOffset + iLenSize, 0.toByte(), iFixFieldSize)
                iLenSize = 0
            }
            var paddingLen = 0
            iResp = etag.getValueFrom(dataByte, isoTag, ptrValue)
            if (iResp <= 0) {
                println("\tERR: DE%2d NOT FOUND$fieldId")
                iResp = 0
                return -2
            } else {
                Utils.memcpy(dataout, dataoutOffset + iLenSize, ptrValue, 0, iResp)
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
                if (fieldType == 'n') Utils.bin2bcd(iResp * 2 - paddingLen, 1, dataout, dataoutOffset) else Utils.bin2bcd(iResp, 1, dataout, dataoutOffset)
            }
            if (iLenSize == 2) {
                if (fieldType == 'n') Utils.bin2bcd(iResp * 2 - paddingLen, 2, dataout, dataoutOffset) else Utils.bin2bcd(iResp, 2, dataout, dataoutOffset)
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
        helperLog.appendLine(logClassName, "parseIsoResp")
        helperLog.appendLine(logClassName, "isoResp($isoRespByteLen) :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoRespByte, isoRespByteOffset, isoRespByteLen)}")

        val isou = IsoUtil()
        val destByteLen = IntArray(2)
        destByteLen[0] = TransData.transactionDbLen

        val iResp = isou.msgDecode(isoRespByte, isoRespByteOffset, isoRespByteLen, TransData.transactionDb, destByteLen)
        if(iResp < 0){
            helperLog.appendLine(logClassName, "ISOE:ERR: Failed to parse ISO Resp. Handle as Timeout")
        } else {
            TransData.transactionDbLen = destByteLen[0]
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun parseIsoRespIsolate(helperLog: HelperLog, isoRespByte: ByteArray, isoRespByteOffset: Int, isoRespByteLen: Int, returnByte: ByteArray, returnByteLenArr: IntArray, mapVariable: MutableMap<String, String>) {
        helperLog.appendLine(logClassName, "parseIsoRespIsolate")
        helperLog.appendLine(logClassName, "isoResp($isoRespByteLen) :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoRespByte, isoRespByteOffset, isoRespByteLen)}")

        val isou = IsoUtil()
        val destByteLen = IntArray(2)

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
        Utils.memcpy(tempBuffArray, 0, previousIsoByte, 0, previousIsoByte.size)

        var acqCode = ""
        var acqMid = ""
        var acqTid = ""

        val stan = IsoBatchInfoRepo.allocateCounter(context, "stan", schemeTag)
        val invoiceNo = IsoBatchInfoRepo.allocateCounter(context, "invoiceNo", "pos")

        //TID
        ptrLen = emvTag.getValueFrom(previousIsoByte, "DF41", ptrValue)
        if(ptrLen > 0){
            acqTid = Utils.byteArrayToAsciiString(ptrValue, 0 , ptrLen)
        }

        // MID
        ptrLen = emvTag.getValueFrom(previousIsoByte, "DF42", ptrValue)
        if(ptrLen > 0){
            acqMid = Utils.byteArrayToAsciiString(ptrValue, 0 , ptrLen)
        }

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

        val productModel = ProductListRepo.getSingle(context, listOf("AcqMid", "AcqTid"), arrayOf(acqMid, acqTid))
        productModel?.let {
            acqCode = it.AcqCode
        }

        emvTag.removeTlvByT(tempBuffArray, Global.iso.tag.MTI)
        emvTag.addTlvByTvHexString(Global.iso.tag.MTI, isoInfoModel.mti, 0, isoInfoModel.mti.length, tempBuffArray)
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
        emvTag.removeTlvByT(tempBuffArray, "DF64")

        // Remove Response DB that required updated from new response
        TransData.removeTlvFromTransDb("BF00")
        TransData.removeTlvFromTransDb("BF11")
        TransData.removeTlvFromTransDb("BF37")
        TransData.removeTlvFromTransDb("BF38")
        TransData.removeTlvFromTransDb("BF39")
        TransData.removeTlvFromTransDb("BF57")
        emvTag.removeTlvByT(tempBuffArray, "BF00")
        emvTag.removeTlvByT(tempBuffArray, "BF11")
        emvTag.removeTlvByT(tempBuffArray, "BF37")
        emvTag.removeTlvByT(tempBuffArray, "BF38")
        emvTag.removeTlvByT(tempBuffArray, "BF39")
        emvTag.removeTlvByT(tempBuffArray, "BF57")

        val desField = IntArray(30)
        val desFieldLen = isou.splitIntegerText(isoInfoModel.transactionDes!!, " ", desField)
        val encDesField = IntArray(30)
        val encDesFieldLen = isou.splitIntegerText(isoInfoModel.transactionDesSensitive!!, " ", encDesField)
        helperLog.appendLine(logClassName, "FormIsoMessage for Des ($desFieldLen) :: ${desField.joinToString { it.toString() }}")
        helperLog.appendLine(logClassName, "Sensitive Des Field ($encDesFieldLen) :: ${encDesField.joinToString { it.toString() }}")

        val refTag = "$schemeTag-tle${acqCode.lowercase().replaceFirstChar { it.uppercase() }}-${acqMid}"
        val strWakKey = SecureDataRepo.getDecryptedSingle(context, listOf("tag", "subtag"), listOf("eWakKey", refTag))?.value ?: run { "" }
        val strWekKey = SecureDataRepo.getDecryptedSingle(context, listOf("tag", "subtag"), listOf("eWekKey", refTag))?.value ?: run { "" }

        if(strWakKey.isEmpty() || strWekKey.isEmpty()) {
            TransData.transResult = Global.iso.err.txnDeclined_emptySecureKey
            TransData.respCode = Utils.ASCIItoHexString("ZV")
            return mutableMapOf()
        }
        // Tle Version
        val encVersion = byteArrayOf(0x00, 0x04)
        val de57 = ByteArray(1000)
        Utils.memcpy(de57, encVersion, 2)
        var iDe57Len = 2

        // Generate DE57
        val encDe57 = ByteArray(1024)
        val clearByteDe57 = ByteArray(1024)
        var iClearByteDe57Len = 8 //first 8 byte is bitmap
        var iEncDe57Len = 0

        if(encDesField[0] != 0){
            val bBmp = ByteArray(8)
            isou.bmpEncode(isoInfoModel.transactionDesSensitive!!, bBmp)

            // DEs
            repeat(encDesFieldLen){
                //val iThisField: Byte = encDesField[it].toByte()
                val iThisField = encDesField[it]
                val iThisTag = "DF" + String.format("%02d", iThisField)
                val iThisLen = emvTag.getValueFrom(tempBuffArray, iThisTag, ptrValue)
                if(iThisLen > 0){
                    helperLog.appendLine(logClassName, "$iThisTag :: ${HexUtil.bytesToHexString(ptrValue, 0, iThisLen)}")
                    helperLog.appendLine(logClassName, "$iThisTag Length :: $iThisLen")
                    val bufTemp = ByteArray(256)
                    Utils.memset(bufTemp, 0.toByte(), bufTemp.size)
                    when(iThisField){
                        35 -> {
                            val byteLastTrack2Byte = ptrValue[iThisLen - 1]
                            if (byteLastTrack2Byte.toInt() and 0x0F == 0x0F) {
                                //bufTemp[iThisLen - 1] = 0x0F
                                val strTrack2 = HexUtil.bytesToHexString(ptrValue, 0, iThisLen)
                                val newTrack2 = strTrack2.replace("F", "0")
                                helperLog.appendLine(logClassName, "strTrack2 :: ${maskTrack2(strTrack2)}")
                                helperLog.appendLine(logClassName, "newTrack2 :: ${maskTrack2(newTrack2)}")

                                val bTrack2 = HexUtil.hexStringToByte(newTrack2)
                                Utils.memcpy(clearByteDe57, iClearByteDe57Len, bTrack2, 0, bTrack2.size)
                                iClearByteDe57Len += bTrack2.size
                            } else {
                                Utils.memcpy(clearByteDe57, iClearByteDe57Len, ptrValue, 0, iThisLen)
                                iClearByteDe57Len += iThisLen
                            }
                        }
                        2, 14, 45, 48, 58 -> {
                            Utils.memcpy(clearByteDe57, iClearByteDe57Len, ptrValue, 0, iThisLen)
                            iClearByteDe57Len += iThisLen
                        }
                        else -> {
                            helperLog.appendLine(logClassName, "ERR: Unable to obtain data for DE-$iThisField")
                            isou.bmpAlterBit(iThisField, 0.toByte(), bBmp, 0)
                            certLog { "CLEAR BIT, New Sensitive Field BMP :: ${HexUtil.bytesToHexString(bBmp, 0 , 8)}" }
                        }
                    }
                }
            }

            if (iClearByteDe57Len > 8) {
                //Put bitmap back to offset 0
                Utils.memcpy(clearByteDe57, bBmp, 8)
                certLog { "CLEAR before padding [] :: ${HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)}" }

                // Add Padding
                var iPadlen = iClearByteDe57Len % 8
                if (iPadlen != 0) {
                    clearByteDe57[iClearByteDe57Len++] = 0x80.toByte()
                    iPadlen = 7 - iPadlen
                    Utils.memset(clearByteDe57, iClearByteDe57Len, 0.toByte(), iPadlen)
                    iClearByteDe57Len += iPadlen
                }
                //println("CLEAR before padding [] :: ${HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)}")

                var strClearDe57 = ""
                strClearDe57 = HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)
                helperLog.appendLine(logClassName, "strlen(strClearDe57) :: ${Utils.strlen(strClearDe57)}")
                certLog { "CLEAR after padding [] :: $strClearDe57" }

                var strEncDe57: String? = null
                try {
                    strEncDe57 = Encryption.encrypt(Utils.ASCIItoHexString(strClearDe57), strWekKey, "DESede", "CBC")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                helperLog.appendLine(logClassName, "strEncDe57 :: $strEncDe57")

                val bEncDe57 = HexUtil.hexStringToByte(strEncDe57)
                Utils.memcpy(encDe57, iEncDe57Len, bEncDe57, 0, bEncDe57.size)
                iEncDe57Len += bEncDe57.size
                helperLog.appendLine(logClassName, "ENCRYPTED[] :: ${HexUtil.bytesToHexString(encDe57, 0 , iEncDe57Len)}")
                helperLog.appendLine(logClassName, "bEncDe57.length :: ${bEncDe57.size}")
                helperLog.appendLine(logClassName, "iEncDe57Len :: $iEncDe57Len")
                Utils.memcpy(de57, 2, encDe57, 0, iEncDe57Len)
                iDe57Len += iEncDe57Len
            }
        } else {
            // DE57 withouth value
            val bBmp = ByteArray(8)
            iClearByteDe57Len = 8
            Utils.memcpy(clearByteDe57, bBmp, 8)
            certLog { "CLEAR before padding [] :: ${HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)}" }

            // Add Padding
            var iPadlen = iClearByteDe57Len % 8
            if (iPadlen != 0) {
                clearByteDe57[iClearByteDe57Len++] = 0x80.toByte()
                iPadlen = 7 - iPadlen
                Utils.memset(clearByteDe57, iClearByteDe57Len, 0.toByte(), iPadlen)
                iClearByteDe57Len += iPadlen
            }
            certLog { "CLEAR after padding [] :: ${HexUtil.bytesToHexString(clearByteDe57, 0 , iClearByteDe57Len)}" }

            var strClearDe57: String? = ""
            strClearDe57 = HexUtil.bytesToHexString(clearByteDe57, 0, iClearByteDe57Len)
            helperLog.appendLine(logClassName, "strlen(strClearDe57) :: ${Utils.strlen(strClearDe57)}")
            certLog { "CLEAR[] :: $strClearDe57" }

            var strEncDe57: String? = null
            try {
                strEncDe57 = Encryption.encrypt(Utils.ASCIItoHexString(strClearDe57), strWekKey, "DESede", "CBC")
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
            helperLog.appendLine(logClassName, "strEncDe57 :: $strEncDe57")
            val bEncDe57 = HexUtil.hexStringToByte(strEncDe57)

            Utils.memcpy(encDe57, iEncDe57Len, bEncDe57, 0, bEncDe57.size)
            iEncDe57Len += bEncDe57.size
            helperLog.appendLine(logClassName, "ENCRYPTED[] :: ${HexUtil.bytesToHexString(encDe57, 0 , iEncDe57Len)}")
            helperLog.appendLine(logClassName, "bEncDe57.length :: ${bEncDe57.size}")
            helperLog.appendLine(logClassName, "iEncDe57Len :: $iEncDe57Len")

            Utils.memcpy(de57, 2, encDe57, 0, iEncDe57Len)
            iDe57Len += iEncDe57Len
        }
        emvTag.addTlvByTv("DF57", de57, 0, iDe57Len, tempBuffArray)

        // Form Message from MTI to DE63 to sha1
        val macIsoByte = ByteArray(6000)
        var macIsoByteLen = 2
        var isoByteLen = 2

        // TPDU
        ptrLen = emvTag.getValueFrom(tempBuffArray, Global.iso.tag.TPDU, ptrValue)
        Utils.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
        macIsoByteLen += ptrLen
        Utils.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
        isoByteLen += ptrLen

        // MTI
        ptrLen = emvTag.getValueFrom(tempBuffArray, Global.iso.tag.MTI, ptrValue)
        Utils.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
        macIsoByteLen += ptrLen
        Utils.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
        isoByteLen += ptrLen

        // BITMAPS
        val bBmp = ByteArray(8)
        isou.bmpEncode(isoInfoModel.transactionDes!!, bBmp)
        helperLog.appendLine(logClassName, "ISO:BMP :: ${HexUtil.bytesToHexString(bBmp, 0, 8)}")

        //Cache Len for know the BMP location
        val iCacheLen = macIsoByteLen
        Utils.memcpy(macIsoByte, macIsoByteLen, bBmp, 0, 8)
        macIsoByteLen += 8
        Utils.memcpy(isoByte, isoByteLen, bBmp, 0, 8)
        isoByteLen += 8

        try {
            helperLog.appendLine(logClassName, "ENCODE-ISO :: ")
            repeat(desFieldLen){
                val iFieldId: Int = desField[it]

                val tempValue = ByteArray(2000)
                val tempLen = 0

                val indexOfEnc = encDesField.indexOf(iFieldId)
                val iThisFieldLen = setField(iFieldId, tempBuffArray, tempValue, tempLen, false)
                if(iThisFieldLen > 0) {
                    helperLog.appendLine(logClassName, "\tDE$iFieldId($iThisFieldLen) :: ${HexUtil.bytesToHexString(tempValue, 0, iThisFieldLen)}")
                    //Utils.memcpy(macIsoByte, macIsoByteLen, tempValue, 0, iThisFieldLen)
                    //macIsoByteLen += iThisFieldLen
                    if(indexOfEnc == -1){
                        Utils.memcpy(isoByte, isoByteLen, tempValue, 0, iThisFieldLen)
                        isoByteLen += iThisFieldLen
                        Utils.memcpy(macIsoByte, macIsoByteLen, tempValue, 0, iThisFieldLen)
                        macIsoByteLen += iThisFieldLen
                    } else {
                        //Amending Sensitive Field to 0
                        val buffArray = ByteArray(512)
                        buffArray.fill(0, 0, buffArray.size)
                        val affectedLen = IntArray(2)
                        Tlv().decodeLen(tempValue, 0, affectedLen)
                        Utils.memcpy(buffArray, 0, tempValue, 0, affectedLen[0])

                        Utils.memcpy(isoByte, isoByteLen, buffArray, 0, iThisFieldLen)
                        isoByteLen += iThisFieldLen
                        Utils.memcpy(macIsoByte, macIsoByteLen, buffArray, 0, iThisFieldLen)
                        macIsoByteLen += iThisFieldLen
                    }
                }

                if(iThisFieldLen <= 0){
                    helperLog.appendLine(logClassName, "ISOS:ERR: Unable to obtain data for DE-$iFieldId")
                    if (iFieldId != 64) {
                        isou.bmpAlterBit(iFieldId, 0.toByte(), macIsoByte, iCacheLen)
                        isou.bmpAlterBit(iFieldId, 0.toByte(), isoByte, iCacheLen)
                    }
                }
                /*
                // Remove BMP tag for 0 value or sensitive element
                if (iThisFieldLen <= 0 || indexOfEnc >= 0) {
                    if (iFieldId != 64) {
                        isou.bmpAlterBit(iFieldId, 0.toByte(), isoByte, iCacheLen)
                    }
                }
                * */
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            helperLog.appendLine(logClassName, "Exception::${ex.message}")
            helperLog.appendLine(logClassName, "Exception::$ex")
        }

        val macFullIsoLen = macIsoByteLen - 2
        if(macFullIsoLen > 0){
            Utils.set_ushort(macFullIsoLen.toShort(), macIsoByte, 0)
        }
        val macPartialIsoLen = isoByteLen - 2
        if(macPartialIsoLen > 0){
            Utils.set_ushort(macPartialIsoLen.toShort(), isoByte, 0)
        }
        helperLog.appendLine(logClassName, "Mac Iso :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(macIsoByte, 0,  macIsoByteLen)}")
        helperLog.appendLine(logClassName, "Iso Byte :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")

        // Generate Mac
        val mac: ByteArray
        val hash = ByteArray(1000)
        Utils.memset(hash, 0.toByte(), hash.size)
        val strHash = Cryptography.hashData(HexUtil.bytesToHexString(macIsoByte, 7, macIsoByteLen - 7), Cryptography.hashAlgorithm.SHA_1)
        helperLog.appendLine(logClassName, "hash :: $strHash")
        val strMac = Cryptography.retailMac(strWakKey, strHash)
        certLog { "MAC :: $strMac" }

        mac = Utils.hexStringToByteArray(strMac)
        //mac only required left 4 byte, right 4 byte pad with 0
        Utils.memset(mac, 4, 0.toByte(), 4)
        helperLog.appendLine(logClassName, "Masked MAC :: ${HexUtil.bytesToHexString(mac, 0, 8)}")

        //Utils.memcpy(macIsoByte, macIsoByteLen, mac, 0, 8)
        //macIsoByteLen += 8
        Utils.memcpy(isoByte, isoByteLen, mac, 0, 8)
        isoByteLen += 8

        val actualMacIsoLen = macIsoByteLen - 2
        if(actualMacIsoLen > 0){
            Utils.set_ushort(actualMacIsoLen.toShort(), macIsoByte, 0)
        }
        val actualIsoLen = isoByteLen - 2
        if(actualIsoLen > 0){
            Utils.set_ushort(actualIsoLen.toShort(), isoByte, 0)
        }
        byteArrayLen[0] = isoByteLen
        helperLog.appendLine(logClassName, "Iso Byte after MAC ($isoByteLen):: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")
        return mutableMapOf()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun formIsoSignOn(context: Context, helperLog: HelperLog, isoInfoModel: IsoInfoModel, prevIsoString: String, isoByte: ByteArray, byteArrayLen: IntArray): MutableMap<String, String>  {
        val emvTag = EmvTag()
        val isou = IsoUtil()
        var ptrLen = 0
        val schemeTag = "visam"

        val tempBuffArray = ByteArray(4096)
        val previousIsoByte = HexUtil.hexStringToByte(prevIsoString)
        Utils.memcpy(tempBuffArray, 0, previousIsoByte, 0, previousIsoByte.size)
        val variantMap = mutableMapOf<String, String>()

        var acqCode = ""
        var acqMid = ""
        var acqTid = ""

        //TID
        ptrLen = emvTag.getValueFrom(previousIsoByte, "DF41", ptrValue)
        if(ptrLen > 0){
            acqTid = Utils.byteArrayToAsciiString(ptrValue, 0 , ptrLen)
        }
        // MID
        ptrLen = emvTag.getValueFrom(previousIsoByte, "DF42", ptrValue)
        if(ptrLen > 0){
            acqMid = Utils.byteArrayToAsciiString(ptrValue, 0 , ptrLen)
        }
        val productModel = ProductListRepo.getSingle(context, listOf("AcqMid", "AcqTid"), arrayOf(acqMid, acqTid))
        productModel?.let {
            acqCode = it.AcqCode
        }

        val stan = IsoBatchInfoRepo.allocateCounter(context, "stan", schemeTag)
        emvTag.addTlvByTvHexString("DF11", stan, 0, stan.length, tempBuffArray)
        helperLog.appendLine(logClassName, "Stan :: $stan")

        val invoiceNo = IsoBatchInfoRepo.allocateCounter(context, "invoiceNo", "pos")
        helperLog.appendLine(logClassName, "Invoice No :: $invoiceNo")

        IsoBatchInfoRepo.getBatchInfo(context, "isoTpduHeaderTle", schemeTag) ?.let {
            emvTag.addTlvByTvHexString(Global.iso.tag.TPDU, it.value, 0, it.value.length, tempBuffArray)
        } ?: run { "" }

        IsoBatchInfoRepo.getBatchInfo(context, "niiTle", schemeTag) ?.let {
            emvTag.addTlvByTvHexString("DF24", it.value, 0, it.value.length, tempBuffArray)
        } ?: run { "" }

        helperLog.appendLine(logClassName, "MTI :: ${isoInfoModel.mti}")
        emvTag.removeTlvByT(tempBuffArray, Global.iso.tag.MTI)
        emvTag.addTlvByTvHexString(Global.iso.tag.MTI, isoInfoModel.mti, 0, isoInfoModel.mti.length, tempBuffArray)
        emvTag.removeTlvByT(tempBuffArray, "DF03")
        emvTag.addTlvByTvHexString("DF03", isoInfoModel.processCode, 0, isoInfoModel.processCode.length, tempBuffArray)

        val desField = IntArray(30)
        val desFieldLen = isou.splitIntegerText(isoInfoModel.transactionDes!!, " ", desField)
        val encDesField = IntArray(30)
        val encDesFieldLen = isou.splitIntegerText(isoInfoModel.transactionDesSensitive!!, " ", encDesField)
        helperLog.appendLine(logClassName, "FormIsoMessage for Des ($desFieldLen) :: ${desField.joinToString { it.toString() }}")
        helperLog.appendLine(logClassName, "Sensitive Des Field ($encDesFieldLen) :: ${encDesField.joinToString { it.toString() }}")

        secureLabel = TransData.acqCode.substring(0, 1).uppercase() + TransData.acqCode.substring(1).lowercase()
        val refTag = "$schemeTag-tle$secureLabel-${acqMid}"
        val strTmkKey = SecureDataRepo.getDecryptedSingle(context, listOf("tag", "subtag"), listOf("eTmkId", refTag))?.value ?: run { "" }
        val hexTmkKey = HexUtil.hexStringToByte(strTmkKey)
        val strTakKey = SecureDataRepo.getDecryptedSingle(context, listOf("tag", "subtag"), listOf("eTakKey", refTag))?.value ?: run { "" }
        if(strTmkKey.isEmpty() || strTakKey.isEmpty()) {
            TransData.transResult = Global.iso.err.txnDeclined_emptySecureKey
            TransData.respCode = Utils.ASCIItoHexString("ZV")
            return mutableMapOf()
        }

        // Generate DE57
        val de57 = ByteArray(1024)
        var iDe57Len = 0
        // Tle Version
        val encVersion = byteArrayOf(0x00, 0x04)
        Utils.memcpy(de57, encVersion, 2)
        iDe57Len += 2
        Utils.memcpy(de57, iDe57Len, hexTmkKey, 0, hexTmkKey.size)
        iDe57Len += hexTmkKey.size
        emvTag.addTlvByTv("DF57", de57, 0, iDe57Len, tempBuffArray)

        // Form Message from MTI to DE63 to sha1
        val macIsoByte = ByteArray(6000)
        var macIsoByteLen = 2
        var isoByteLen = 2

        // TPDU
        ptrLen = emvTag.getValueFrom(tempBuffArray, Global.iso.tag.TPDU, ptrValue)
        //if (ptrLen < 0) {
        //IsoSteps.sysPrint("ISOS:ERR: Failed to retrieve TPDU header")
        //return Global.iso.err.fileNotFound
        //}
        Utils.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
        macIsoByteLen += ptrLen
        Utils.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
        isoByteLen += ptrLen

        // MTI
        ptrLen = emvTag.getValueFrom(tempBuffArray, Global.iso.tag.MTI, ptrValue)
        //if (ptrLen < 0) {
        //IsoSteps.sysPrint("ISOS:ERR: Failed to retrieve MTI")
        //return Global.iso.err.fileNotFound
        //}
        Utils.memcpy(macIsoByte, macIsoByteLen, ptrValue, 0, ptrLen)
        macIsoByteLen += ptrLen
        Utils.memcpy(isoByte, isoByteLen, ptrValue, 0, ptrLen)
        isoByteLen += ptrLen

        // BITMAPS
        val bBmp = ByteArray(8)
        isou.bmpEncode(isoInfoModel.transactionDes!!, bBmp)
        helperLog.appendLine(logClassName, "ISO:BMP :: ${HexUtil.bytesToHexString(bBmp, 0, 8)}")

        //Cache Len for know the BMP location
        val iCacheLen = macIsoByteLen
        Utils.memcpy(macIsoByte, macIsoByteLen, bBmp, 0, 8)
        macIsoByteLen += 8
        //isou.bmpAlterBit(57, 0.toByte(), macIsoByte, iCacheLen)
        //isou.bmpAlterBit(64, 0.toByte(), macIsoByte, iCacheLen)
        Utils.memcpy(isoByte, isoByteLen, bBmp, 0, 8)
        isoByteLen += 8

        helperLog.appendLine(logClassName, "ENCODE-ISO :: ")
        repeat(desFieldLen){
            val iFieldId: Int = desField[it]

            val tempValue = ByteArray(2000)
            val tempLen = 0

            val indexOfEnc = encDesField.indexOf(iFieldId)
            val iThisFieldLen = setField(iFieldId, tempBuffArray, tempValue, tempLen, false)
            if(iThisFieldLen > 0) {
                helperLog.appendLine(logClassName, "\tDE$iFieldId($iThisFieldLen) :: ${HexUtil.bytesToHexString(tempValue, 0, iThisFieldLen)}")
                //if (iFieldId != 57) {
                Utils.memcpy(macIsoByte, macIsoByteLen, tempValue, 0, iThisFieldLen)
                macIsoByteLen += iThisFieldLen
                //}

                if(indexOfEnc == -1){
                    Utils.memcpy(isoByte, isoByteLen, tempValue, 0, iThisFieldLen)
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
                if(iThisFieldLen <= 0 && iFieldId != 64){
                    isou.bmpAlterBit(iFieldId, 0.toByte(), macIsoByte, iCacheLen)
                }
            }
        }

        val macFullIsoLen = macIsoByteLen - 2
        if(macFullIsoLen > 0){
            Utils.set_ushort(macFullIsoLen.toShort(), macIsoByte, 0)
        }
        val macPartialIsoLen = isoByteLen - 2
        if(macPartialIsoLen > 0){
            Utils.set_ushort(macPartialIsoLen.toShort(), isoByte, 0)
        }

        helperLog.appendLine(logClassName, "Mac Iso :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(macIsoByte, 0,  macIsoByteLen)}")
        helperLog.appendLine(logClassName, "Iso Byte :: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")

        //------------------------------------------------------------------------->
        // Generate Mac
        val mac: ByteArray
        val hash = ByteArray(1000)
        Utils.memset(hash, 0.toByte(), hash.size)
        val strHash = Cryptography.hashData(HexUtil.bytesToHexString(macIsoByte, 7, macIsoByteLen - 7), Cryptography.hashAlgorithm.SHA_1)
        val strMac = Cryptography.retailMac(strTakKey, strHash)
        certLog { "MAC :: $strMac" }

        mac = Utils.hexStringToByteArray(strMac)
        //mac only required left 4 byte, right 4 byte pad with 0
        Utils.memset(mac, 4, 0.toByte(), 4)
        helperLog.appendLine(logClassName, "Masked MAC :: ${HexUtil.bytesToHexString(mac, 0, 8)}")

        //Utils.memcpy(macIsoByte, macIsoByteLen, mac, 0, 8)
        //macIsoByteLen += 8
        Utils.memcpy(isoByte, isoByteLen, mac, 0, 8)
        isoByteLen += 8

        val actualMacIsoLen = macIsoByteLen - 2
        if(actualMacIsoLen > 0){
            Utils.set_ushort(actualMacIsoLen.toShort(), macIsoByte, 0)
        }
        val actualIsoLen = isoByteLen - 2
        if(actualIsoLen > 0){
            Utils.set_ushort(actualIsoLen.toShort(), isoByte, 0)
        }
        byteArrayLen[0] = isoByteLen
        helperLog.appendLine(logClassName, "Iso Byte after MAC ($isoByteLen):: ")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(isoByte, 0,  isoByteLen)}")
        return variantMap
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun parseRespSignOn(context: Context, helperLog: HelperLog, isoByte: ByteArray, byteArrayLen: IntArray): Boolean {
        var result = false
        secureLabel = TransData.acqCode.substring(0, 1).uppercase() + TransData.acqCode.substring(1).lowercase()
        val emvTag = EmvTag()
        var ptrLen = 0

        helperLog.appendLine(logClassName, "parseRespSignOn :: ${HexUtil.bytesToHexString(isoByte, 0, byteArrayLen[0])}")
        ptrLen = emvTag.getValueFrom(isoByte, "BF57", ptrValue)
        helperLog.appendLine(logClassName, "ptrLen :: $ptrLen")
        helperLog.appendLine(logClassName, "\t${HexUtil.bytesToHexString(ptrValue, 0, ptrLen)}")

        if (ptrLen < 58) {
            helperLog.appendLine(logClassName, "Invalid key length for downloaded EncKey")
            return result
        }
        val hexEncKey = ByteArray(100)
        Utils.memcpy(hexEncKey, 0, ptrValue, 10, ptrLen - 10)

        // Decrypt Encrypted TPK WAK WEK using Clear TMK
        val strSecureTleScheme = "visam-tle$secureLabel-${TransData.mid}"
        helperLog.appendLine(logClassName, "strSecureTleScheme :: $strSecureTleScheme")
        val strClearTmk = SecureDataRepo.getDecryptedSingle(context, listOf("tag", "subtag"), listOf("eTmkKey", strSecureTleScheme))?.value ?: run { "" }
        val clearTmk = HexUtil.hexStringToByte(strClearTmk)
        val iClearTmkLen = clearTmk.size

        val tpk = ByteArray(16)
        val wak = ByteArray(16)
        val wek = ByteArray(16)
        Utils.memcpy(tpk, hexEncKey, 16)
        Utils.memcpy(wak, 0, hexEncKey, 16, 16)
        Utils.memcpy(wek, 0, hexEncKey, 16 + 16, 16)
        var strClearTpk = ""
        var strClearWak = ""
        var strClearWek = ""
        val clearTpk: ByteArray
        val clearWak: ByteArray
        val clearWek: ByteArray
        try {
            strClearTpk = Encryption.decrypt(HexUtil.bytesToHexString(tpk), strClearTmk, "DESede", "ECB")
            clearTpk = Utils.hexStringToByteArray(strClearTpk)
            strClearWak = Encryption.decrypt(HexUtil.bytesToHexString(wak), strClearTmk, "DESede", "ECB")
            clearWak = Utils.hexStringToByteArray(strClearWak)
            strClearWek = Encryption.decrypt(HexUtil.bytesToHexString(wek), strClearTmk, "DESede", "ECB")
            clearWek = Utils.hexStringToByteArray(strClearWek)
        } catch (e: Exception) {
            e.printStackTrace()
            return result
        }

        SecureDataRepo.setSecureData(context, "eTpkKey", strSecureTleScheme, clearTpk, clearTpk.size)
        SecureDataRepo.setSecureData(context, "eWakKey", strSecureTleScheme, clearWak, clearWak.size)
        SecureDataRepo.setSecureData(context, "eWekKey", strSecureTleScheme, clearWek, clearWek.size)
        result = true
        return result
    }

    fun derivedFutureKey(context: Context, helperLog: HelperLog): Boolean {
        return true
    }
}