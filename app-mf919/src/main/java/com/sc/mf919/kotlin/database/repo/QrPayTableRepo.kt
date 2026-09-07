package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelQrPayBrandGet
import com.sc.mf919.kotlin.database.model.DbModelQrPayTable

class QrPayTableRepo {
    companion object {
        fun getPayBrand(mContext: Context): List<DbModelQrPayBrandGet>? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val type = object : TypeToken<List<DbModelQrPayBrandGet>>() {}.type
            return dbHandler.selectListData<DbModelQrPayBrandGet>(type, "SELECT DISTINCT payBrand, productCode FROM qrPayTable GROUP BY payBrand ORDER BY payBrand", arrayOf())
        }

        fun getListTransactionQrDesc(mContext: Context, fieldList: List<String>, valueList: List<String>): List<DbModelQrPayTable> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            var sqlString = "SELECT * FROM ${DatabaseTables.PRINT_RECEIPT_QR.id} "
            for (a in fieldList.indices) {
                val addAnd = if (a == 0) "WHERE" else "AND"
                sqlString = "$sqlString $addAnd ${fieldList[a]} = ?"
            }
            sqlString = "$sqlString ORDER BY id DESC"

            val type = object : TypeToken<List<DbModelQrPayTable>>() {}.type
            return dbHandler.selectListData(type, sqlString, valueList.toTypedArray())
        }
    }
}