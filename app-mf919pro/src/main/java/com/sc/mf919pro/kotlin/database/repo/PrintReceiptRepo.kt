package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919pro.kotlin.database.model.DbModelPrintReceiptInsert

class PrintReceiptRepo {
    companion object {
        fun insertToDb(mContext: Context, dbModelPrintReceiptInsert: DbModelPrintReceiptInsert): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.insertToDb(DatabaseTables.PRINT_RECEIPT, dbModelPrintReceiptInsert)
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
            // D3 — report the real outcome; runningCustomSql returns 0 on failure.
            return dbHandler.runningCustomSql(sqlString, arrayOf(strAmount, invoiceNo, stan)) > 0
        }

        fun deleteVoidedPreAuth(mContext: Context, /*strAmount: String,*/ invoiceNo: String, approvalCode: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "DELETE FROM ${DatabaseTables.PRINT_RECEIPT.id} WHERE txnType = ? AND invoiceNo = ? AND txnApprCode = ? "
            // D3 — report the real outcome; runningCustomSql returns 0 on failure.
            return dbHandler.runningCustomSql(sqlString, arrayOf("Pre Authorization", invoiceNo, approvalCode)) > 0
        }

        fun deleteVoidedSaleCompInvoice(mContext: Context, strAmount: String, invoiceNo: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "DELETE FROM ${DatabaseTables.PRINT_RECEIPT.id} WHERE txnType = ? AND txnAmt = ? AND invoiceNo = ? "
            // D3 — report the real outcome; runningCustomSql returns 0 on failure.
            return dbHandler.runningCustomSql(sqlString, arrayOf("Sale Completion", strAmount, invoiceNo)) > 0
        }

        fun deleteAllData(mContext: Context): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            val sqlString = "DELETE FROM ${DatabaseTables.PRINT_RECEIPT.id}"
            // D3 — report the real outcome; runningCustomSql returns 0 on failure.
            return dbHandler.runningCustomSql(sqlString, arrayOf()) > 0
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

            sqlString = "$sqlString ORDER BY invoiceNo DESC"

            val type = object : TypeToken<List<DbModelPrintReceipt>>() {}.type
            return dbHandler.selectListData<DbModelPrintReceipt>(type, sqlString, valueList.toTypedArray())

        }

        fun getMultipleRecordPaged(
            mContext: Context,
            invoiceNoFilter: String,
            limit: Int,
            offset: Int,
        ): List<DbModelPrintReceipt> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val sqlString = """
                SELECT * FROM ${DatabaseTables.PRINT_RECEIPT.id}
                WHERE invoiceNo LIKE ?
                ORDER BY invoiceNo DESC
                LIMIT ? OFFSET ?
            """.trimIndent()

            val type = object : TypeToken<List<DbModelPrintReceipt>>() {}.type
            val args = arrayOf("%$invoiceNoFilter%", limit.toString(), offset.toString())
            return dbHandler.selectListData(type, sqlString, args)
        }

        fun countSaleSuccess(mContext: Context): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val sql = """
                SELECT COUNT(*) FROM ${DatabaseTables.PRINT_RECEIPT.id}
                WHERE respCode = '00'
                AND LOWER(txnType) NOT LIKE '%void%'
                AND LOWER(txnType) NOT LIKE '%cancel%'
            """.trimIndent()
            return dbHandler.executeScalar(sql, arrayOf())
        }

        fun sumSaleSuccessAmount(mContext: Context): Long {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val sql = """
                SELECT COALESCE(SUM(CAST(txnAmt AS INTEGER)), 0) FROM ${DatabaseTables.PRINT_RECEIPT.id}
                WHERE respCode = '00'
                AND LOWER(txnType) NOT LIKE '%void%'
                AND LOWER(txnType) NOT LIKE '%cancel%'
            """.trimIndent()
            return dbHandler.executeScalar(sql, arrayOf()).toLong()
        }

        fun countVoidOrCancel(mContext: Context): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val sql = """
                SELECT COUNT(*) FROM ${DatabaseTables.PRINT_RECEIPT.id}
                WHERE LOWER(txnType) LIKE '%void%'
                OR LOWER(txnType) LIKE '%cancel%'
            """.trimIndent()
            return dbHandler.executeScalar(sql, arrayOf())
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

            sqlString = "$sqlString ORDER BY invoiceNo DESC"

            val type = object : TypeToken<List<DbModelPrintReceiptInsert>>() {}.type
            return dbHandler.selectListData<DbModelPrintReceiptInsert>(type, sqlString, valueList)
        }

        fun getMultipleRecordBySchemeIds(mContext: Context, schemeId: String): List<DbModelPrintReceipt>  {
            val dbHandler = DbHandler.getInstance(mContext)!!

            var sqlString = "SELECT * FROM ${DatabaseTables.PRINT_RECEIPT.id}"
            if(schemeId.isNotEmpty()) {
                val ids = schemeId.replace('"', ' ')
                sqlString = "$sqlString WHERE schemeId IN ($ids) "
            }
            sqlString = "$sqlString ORDER BY invoiceNo, txnDt"

            println("SQL String >> $sqlString")
            val type = object : TypeToken<List<DbModelPrintReceipt>>() {}.type
            return dbHandler.selectListData<DbModelPrintReceipt>(type, sqlString, arrayOf())
        }

        fun getMultipleSuccessRecordBySchemeIds(mContext: Context, schemeId: String): List<DbModelPrintReceipt>  {
            val dbHandler = DbHandler.getInstance(mContext)!!

            var sqlString = "SELECT * FROM ${DatabaseTables.PRINT_RECEIPT.id} WHERE respCode = '00'"
            if(schemeId.isNotEmpty()) {
                val ids = schemeId.replace('"', ' ')
                sqlString = "$sqlString and schemeId IN ($ids) "
            }
            sqlString = "$sqlString ORDER BY invoiceNo, txnDt"

            println("SQL String >> $sqlString")
            val type = object : TypeToken<List<DbModelPrintReceipt>>() {}.type
            return dbHandler.selectListData<DbModelPrintReceipt>(type, sqlString, arrayOf())
        }
    }
}

