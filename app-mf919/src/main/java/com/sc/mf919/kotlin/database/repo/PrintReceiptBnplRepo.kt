package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.sc.mf919.java.utils.EmvUtil
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelPrintReceiptBnpl
import com.sc.mf919.kotlin.database.model.DbModelPrintReceiptBnplInsert

class PrintReceiptBnplRepo {
    companion object {
        private val TABLE = DatabaseTables.PRINT_RECEIPT_BNPL.id

        fun insertToDb(
            mContext: Context, txnType: String?, payBrand: String?, payBrandDesc: String?,
            txnDt: String?, mid: String?, tid: String?, txnAmt: String?, refId: String?, hostRefNo: String?,
            txnRefNo: String?, respCode: String?, status: String?, addInfo: String?, printInfo: String?,
            acqCode: String?, apprCode: String?, tenure: String?, tenureDesc: String?, bnplResp: String?
        ): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
            val model = DbModelPrintReceiptBnplInsert(
                postingDt, txnType, payBrand, payBrandDesc, txnDt, mid, tid, txnAmt, refId, hostRefNo,
                txnRefNo, respCode, status, addInfo, printInfo, acqCode, apprCode, tenure, tenureDesc, bnplResp
            )
            dbHandler.insertToDb(DatabaseTables.PRINT_RECEIPT_BNPL, model)
            return true
        }

        private fun query(mContext: Context, whereClause: String, args: Array<String>): List<DbModelPrintReceiptBnpl> {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val type = object : TypeToken<List<DbModelPrintReceiptBnpl>>() {}.type
            return dbHandler.selectListData<DbModelPrintReceiptBnpl>(type, "SELECT * FROM $TABLE$whereClause", args)
        }

        /** All rows ordered by refId, txnDt (matches legacy getMultiplePrintReceiptBnplInfo). */
        fun getAllOrdered(mContext: Context): List<DbModelPrintReceiptBnpl> =
            query(mContext, " ORDER BY refId, txnDt", arrayOf())

        /** All rows in natural (rowid) order — refId list and id list stay index-aligned. */
        fun getAll(mContext: Context): List<DbModelPrintReceiptBnpl> = query(mContext, "", arrayOf())

        /**
         * (refIds, ids) from a single scan, so the two lists are guaranteed index-aligned
         * (the UI pages by idList[pos] using the refId shown at the same index).
         */
        fun getRefIdAndIdList(mContext: Context): Pair<List<String>, List<String>> {
            val rows = getAll(mContext)
            return rows.map { it.refId } to rows.map { it.id.toString() }
        }

        fun getById(mContext: Context, id: String): DbModelPrintReceiptBnpl? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.selectSingleData<DbModelPrintReceiptBnpl>("SELECT * FROM $TABLE WHERE id = ?", arrayOf(id))
        }

        /** Positional row in DatabaseTables.PRINT_RECEIPT_BNPL column order (id..bnplResp), replacing the legacy String[]. */
        fun getRowArrayById(mContext: Context, id: String): Array<String?>? = toRowArray(getById(mContext, id))

        fun getRowArrayByRefId(mContext: Context, refId: String): Array<String?>? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val m = dbHandler.selectSingleData<DbModelPrintReceiptBnpl>("SELECT * FROM $TABLE WHERE refId = ?", arrayOf(refId))
            return toRowArray(m)
        }

        private fun toRowArray(m: DbModelPrintReceiptBnpl?): Array<String?>? {
            if (m == null) return null
            return arrayOf(
                m.id.toString(), m.postingDt, m.txnType, m.payBrand, m.payBrandDesc, m.txnDt, m.mid,
                m.tid, m.txnAmt, m.refId, m.hostRefNo, m.txnRefNo, m.respCode, m.status, m.addInfo,
                m.printInfo, m.acqCode, m.txnApprCode, m.tenure, m.tenureDesc, m.bnplResp
            )
        }

        fun countByTxnType(mContext: Context, txnType: String): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.executeScalar("SELECT count(*) FROM $TABLE WHERE txnType = ?", arrayOf(txnType))
        }

        fun sumTxnAmtByTxnType(mContext: Context, txnType: String): String? =
            sumTxnAmt(query(mContext, " WHERE txnType = ?", arrayOf(txnType)))

        /** Legacy getBnplPayTableTotalTxnCount_byTxnTypeAndPayBrand — filters on payBrandDesc. */
        fun countByPayBrandDescAndTxnType(mContext: Context, payBrandDesc: String, txnType: String): Int {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.executeScalar("SELECT count(*) FROM $TABLE WHERE payBrandDesc = ? AND txnType = ?", arrayOf(payBrandDesc, txnType))
        }

        /** Legacy getBnplPayTableTotalTxnAmt_byTxnTypeAndPayBrand — filters on payBrandDesc. */
        fun sumTxnAmtByPayBrandDescAndTxnType(mContext: Context, payBrandDesc: String, txnType: String): String? =
            sumTxnAmt(query(mContext, " WHERE payBrandDesc = ? AND txnType = ?", arrayOf(payBrandDesc, txnType)))

        // Matches legacy SQL SUM(txnAmt): null when no rows, else the numeric sum as a string.
        private fun sumTxnAmt(rows: List<DbModelPrintReceiptBnpl>): String? {
            if (rows.isEmpty()) return null
            return rows.sumOf { it.txnAmt.toLongOrNull() ?: 0L }.toString()
        }

        fun deleteAll(mContext: Context): Boolean {
            return DbHandler.getInstance(mContext)!!.truncateTables(DatabaseTables.PRINT_RECEIPT_BNPL)
        }
    }
}
