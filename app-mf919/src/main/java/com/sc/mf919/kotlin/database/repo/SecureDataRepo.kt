package com.sc.mf919.kotlin.database.repo

import android.content.Context
import crypto.Encryption
import com.sc.mf919.java.activity.Utils
import utils.HexUtil
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelProductList
import com.sc.mf919.kotlin.database.model.DbModelSecureData
import kotlin.toString

/**
 * NOTE: do not add println/Log calls that echo `value`, the clear data, or the encrypted blob.
 * Four such lines used to print DES key material to logcat here -- System.out is redirected to
 * logcat on Android, so any process with adb access could read injected keys off a terminal.
 * Pro removed them; MF919 kept them until 2026-09-07. LogRedact does not cover this file, and
 * key material is not something to redact-and-log -- it simply must not be logged.
 */
class SecureDataRepo(){
    companion object{
        val secureKey = "aforapplebforboy"

        fun truncateTable(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.truncateTables(DatabaseTables.SECURE_DATA)
        }

        fun insertToDb(mContext: Context, dbModelSecureData: DbModelSecureData): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.insertToDb(DatabaseTables.SECURE_DATA, dbModelSecureData)

            return true
        }

        fun getSingle(mContext: Context, fieldList: List<String>, valueList: Array<String>): DbModelSecureData? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM secureData WHERE"

            for (a in fieldList.indices) {
                val addAnd = if (a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            return dbHandler.selectSingleData<DbModelSecureData>(sqlString, valueList)
        }

        fun getDecryptedSingle(mContext: Context, fieldList: List<String>, valueList: List<String>): DbModelSecureData? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM secureData WHERE"

            for (a in fieldList.indices) {
                val addAnd = if (a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            val dbModel = dbHandler.selectSingleData<DbModelSecureData>(sqlString, valueList.toTypedArray())
            dbModel?.let {
                val decryptedKey = Encryption.decrypt(it.value, Utils.ASCIItoHexString(secureKey), "DESede", "CBC")
                //Remove Padding
                val bClearData = HexUtil.hexStringToByte(decryptedKey)
                var iClearDataLen = bClearData.size

                while (bClearData[iClearDataLen - 1].toInt() == 0x00) {
                    iClearDataLen -= 1
                }
                iClearDataLen -= 1 //remove 80 from byte
                dbModel.value = HexUtil.bytesToHexString(bClearData, 0, iClearDataLen)
            }

            return dbModel
        }

        fun setSecureData(mContext: Context, tag: String, subTag: String, data: ByteArray, dataLen: Int) {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val clearData = ByteArray(200)
            var iClearDataLen = 0
            Utils.memcpy(clearData, 0, data, 0, dataLen)
            iClearDataLen = dataLen

            //Padding
            clearData[iClearDataLen++] = 0x80.toByte()
            while (iClearDataLen % 8 != 0) clearData[iClearDataLen++] = 0x00

            val strClearData = HexUtil.bytesToHexString(clearData, 0, iClearDataLen)

            try{
                val strEncData = Encryption.encrypt(strClearData, Utils.ASCIItoHexString(secureKey), "DESede", "CBC")
                val updateMap = mutableMapOf<Any,Any>(
                    "value" to strEncData
                )
                val criteriaMap = mutableMapOf<Any,Any>(
                    "tag" to tag,
                    "subtag" to subTag
                )

                val updateResult = dbHandler.updateTableValue(DatabaseTables.SECURE_DATA, updateMap, criteriaMap).toString().toIntOrNull() ?: 0
                println("Update Secure Data Result >> $updateResult")
                if(updateResult <= 0) {
                    println("Update Key failed, Insert the new key to DB")
                    val secureDataModel = DbModelSecureData(tag, subTag, strEncData)
                    dbHandler.insertToDb(DatabaseTables.SECURE_DATA, secureDataModel)
                }
            }catch (ex: Exception){
                ex.printStackTrace()
            }
        }

        fun updateOrInsert(mContext: Context, tag: String, subTag: String, data: String) {
            val dbHandler = DbHandler.getInstance(mContext)!!
            try{
                val updateMap = mutableMapOf<Any,Any>(
                    "value" to data
                )
                val criteriaMap = mutableMapOf<Any,Any>(
                    "tag" to tag,
                    "subtag" to subTag
                )

                val updateResult = dbHandler.updateTableValue(DatabaseTables.SECURE_DATA, updateMap, criteriaMap).toString().toIntOrNull() ?: 0
                println("Update Secure Data Result >> $updateResult")
                if(updateResult <= 0) {
                    println("Update Key failed, Insert the new key to DB")
                    val secureDataModel = DbModelSecureData(tag, subTag, data)
                    dbHandler.insertToDb(DatabaseTables.SECURE_DATA, secureDataModel)
                }
            }catch (ex: Exception){
                ex.printStackTrace()
            }
        }
    }
}