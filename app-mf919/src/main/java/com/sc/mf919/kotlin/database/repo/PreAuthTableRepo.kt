package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelPreAuthTable
import com.sc.mf919.kotlin.database.model.DbModelPreAuthTableInsert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PreAuthTableRepo {
    @JvmStatic
    fun getPreauthInfo(mContext: Context, approvalCode: String, rrn: String, invoiceNo: String): DbModelPreAuthTable? {
        val dbHandler = DbHandler.getInstance(mContext)!!
        println("approvalCode -> $approvalCode")
        println("rrn -> $rrn")
        println("invoiceNo -> $invoiceNo")
        return dbHandler.selectSingleData<DbModelPreAuthTable>("SELECT * FROM preauthTable WHERE apprCode = ? AND rrn = ? AND invNo = ? ", arrayOf(approvalCode, rrn, invoiceNo))
    }

    fun insertToDb(mContext: Context, dbModelPreAuthTableInsert: DbModelPreAuthTableInsert): Boolean {
        val dbHandler = DbHandler.getInstance(mContext)!!
        dbHandler.insertToDb(DatabaseTables.PREAUTH_TABLE, dbModelPreAuthTableInsert)
        return true
    }

    fun getSingle(mContext: Context, fieldList: List<String>, valueList: Array<String>): DbModelPreAuthTable? {
        val dbHandler = DbHandler.getInstance(mContext)!!
        var sqlString = "SELECT * FROM ${DatabaseTables.PREAUTH_TABLE.id} WHERE"

        for (a in fieldList.indices) {
            val addAnd = if (a != 0) "AND" else ""
            sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
        }
        return dbHandler.selectSingleData<DbModelPreAuthTable>(sqlString, valueList)
    }

    fun deleteVoidedInvoice(mContext: Context, invoiceNo: String): Boolean {
        val dbHandler = DbHandler.getInstance(mContext)!!

        val sqlString = "DELETE FROM ${DatabaseTables.PREAUTH_TABLE.id} WHERE invNo = ? "
        dbHandler.runningCustomSql(sqlString, arrayOf(invoiceNo))
        return true
    }

    fun cleanOldPreauthRecord(mContext: Context): Int  {
        val dbHandler = DbHandler.getInstance(mContext)!!
        val currDT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())

        val recordCount = dbHandler.executeScalar(
            "SELECT count(*) FROM ${DatabaseTables.PREAUTH_TABLE.id} WHERE CAST((JULIANDAY('$currDT') - JULIANDAY(SUBSTR(postingDt, 1, 4) || '-' || SUBSTR(postingDt, 5, 2) || '-' || SUBSTR(postingDt, 7, 2))) AS INTEGER) > 30",
            arrayOf()
        )

        dbHandler.runningCustomSql(
            "DELETE FROM ${DatabaseTables.PREAUTH_TABLE.id} WHERE CAST((JULIANDAY('$currDT') - JULIANDAY(SUBSTR(postingDt, 1, 4) || '-' || SUBSTR(postingDt, 5, 2) || '-' || SUBSTR(postingDt, 7, 2))) AS INTEGER) > 30",
            arrayOf()
        )

        return recordCount
    }
}