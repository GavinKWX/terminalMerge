package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919.kotlin.database.model.DbModelPrintReceiptInsert

class PrintReceiptRepo {
    companion object {
        fun insertToDb(mContext: Context, dbModelPrintReceiptInsert: DbModelPrintReceiptInsert): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.insertToDb(DatabaseTables.PRINT_RECEIPT, dbModelPrintReceiptInsert)
            return true
        }

        fun getSingle(mContext: Context, fieldList: List<String>, valueList: List<String>): DbModelPrintReceipt? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ${DatabaseTables.PRINT_RECEIPT.id}"

            if (fieldList.isNotEmpty()) {
                sqlString = "$sqlString WHERE "
            }

            for (a in fieldList.indices) {
                val addAnd = if (a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            return dbHandler.selectSingleData<DbModelPrintReceipt>(sqlString, valueList.toTypedArray())
        }

        fun deleteVoidedInvoice(mContext: Context, strAmount: String, invoiceNo: String, stan: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "DELETE FROM ${DatabaseTables.PRINT_RECEIPT.id} WHERE txnAmt = ? AND invoiceNo = ? AND stan = ? "
            dbHandler.runningCustomSql(sqlString, arrayOf(strAmount, invoiceNo, stan))
            return true
        }

        fun deleteVoidedPreAuth(mContext: Context, /*strAmount: String,*/ invoiceNo: String, approvalCode: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "DELETE FROM ${DatabaseTables.PRINT_RECEIPT.id} WHERE txnType = ? AND invoiceNo = ? AND txnApprCode = ? "
            dbHandler.runningCustomSql(sqlString, arrayOf("Pre Authorization", invoiceNo, approvalCode))
            return true
        }

        fun deleteVoidedSaleCompInvoice(mContext: Context, strAmount: String, invoiceNo: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "DELETE FROM ${DatabaseTables.PRINT_RECEIPT.id} WHERE txnType = ? AND txnAmt = ? AND invoiceNo = ? "
            dbHandler.runningCustomSql(sqlString, arrayOf("Sale Completion", strAmount, invoiceNo))
            return true
        }

        fun deleteAllData(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "DELETE FROM ${DatabaseTables.PRINT_RECEIPT.id}"
            dbHandler.runningCustomSql(sqlString, arrayOf())
            return true
        }

        fun getMultipleRecord(mContext: Context, fieldList: List<String>, valueList: List<String>): List<DbModelPrintReceipt> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ${DatabaseTables.PRINT_RECEIPT.id}"

            if (fieldList.isNotEmpty()) {
                sqlString = "$sqlString WHERE "
            }

            for (a in fieldList.indices) {
                val addAnd = if (a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            //sqlString = "$sqlString ORDER BY txnDt DESC"
            sqlString = "$sqlString ORDER BY invoiceNo DESC"

            val type = object : TypeToken<List<DbModelPrintReceipt>>() {}.type
            return dbHandler.selectListData<DbModelPrintReceipt>(type, sqlString, valueList.toTypedArray())

        }

        fun getMultipleRecordInfoV2(mContext: Context, fieldList: List<String>, valueList: Array<String>): List<DbModelPrintReceiptInsert> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ${DatabaseTables.PRINT_RECEIPT.id}"

            if (fieldList.isNotEmpty()) {
                sqlString = "$sqlString WHERE "
            }

            for (a in fieldList.indices) {
                val addAnd = if (a != 0) "AND" else ""
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }

            //sqlString = "$sqlString ORDER BY txnDt DESC"
            sqlString = "$sqlString ORDER BY invoiceNo DESC"

            val type = object : TypeToken<List<DbModelPrintReceiptInsert>>() {}.type
            return dbHandler.selectListData<DbModelPrintReceiptInsert>(type, sqlString, valueList)
        }

        fun getMultipleRecordBySchemeIds(mContext: Context, schemeId: String): List<DbModelPrintReceipt>  {
            val dbHandler = DbHandler.getInstance(mContext)!!

            var sqlString = "SELECT * FROM ${DatabaseTables.PRINT_RECEIPT.id} WHERE respCode = '00' "
            if(schemeId.isNotEmpty()) {
                val ids = schemeId.replace('"', ' ')
                sqlString = "$sqlString AND schemeId IN ($ids) "
            }
            sqlString = "$sqlString ORDER BY invoiceNo, txnDt"

            val type = object : TypeToken<List<DbModelPrintReceipt>>() {}.type
            return dbHandler.selectListData<DbModelPrintReceipt>(type, sqlString, arrayOf())
        }
    }
}