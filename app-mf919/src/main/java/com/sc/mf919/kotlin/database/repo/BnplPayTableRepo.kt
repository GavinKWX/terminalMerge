package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.sc.mf919.java.utils.EmvUtil
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelBnplPayTable
import com.sc.mf919.kotlin.database.model.DbModelBnplPayTableInsert

class BnplPayTableRepo {
    companion object {
        fun insertToDb(
            mContext: Context, txnType: String?, payBrand: String?, payBrandDesc: String?,
            txnDt: String?, seqNo: String?, mid: String?, tid: String?, txnAmt: String?, refId: String?,
            hostRefNo: String?, txnRefNo: String?, status: String?, addInfo: String?, acqCode: String?,
            apprCode: String?, packageCode: String?, paymentType: String?, tenure: String?,
            tenureDesc: String?, bnplResp: String?
        ): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val postingDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
            val model = DbModelBnplPayTableInsert(
                postingDt, txnType, payBrand, payBrandDesc, txnDt, seqNo, mid, tid, txnAmt, refId,
                hostRefNo, txnRefNo, status, addInfo, acqCode, apprCode, packageCode, paymentType,
                tenure, tenureDesc, bnplResp
            )
            dbHandler.insertToDb(DatabaseTables.BNPL_PAY_TABLE, model)
            return true
        }

        fun updateByRefId(mContext: Context, tag: String, value: String, refId: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            val updateMap = mutableMapOf<Any, Any>(tag to value)
            val criteriaMap = mutableMapOf<Any, Any>("refId" to refId)
            dbHandler.updateTableValue(DatabaseTables.BNPL_PAY_TABLE, updateMap, criteriaMap)
            return true
        }

        fun getByRefId(mContext: Context, refId: String): DbModelBnplPayTable? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.selectSingleData<DbModelBnplPayTable>(
                "SELECT * FROM ${DatabaseTables.BNPL_PAY_TABLE.id} WHERE refId = ?", arrayOf(refId)
            )
        }

        /** Positional row in DatabaseTables.BNPL_PAY_TABLE column order (id..bnplResp), replacing the legacy String[]. */
        fun getRowArrayByRefId(mContext: Context, refId: String): Array<String?>? {
            val m = getByRefId(mContext, refId) ?: return null
            return arrayOf(
                m.id.toString(), m.postingDt, m.txnType, m.payBrand, m.payBrandDesc, m.txnDt,
                m.seqNo, m.mid, m.tid, m.txnAmt, m.refId, m.hostRefNo, m.txnRefNo, m.status,
                m.addInfo, m.acqCode, m.txnApprCode, m.packageCode, m.paymentType, m.tenure,
                m.tenureDesc, m.bnplResp
            )
        }

        fun deleteAll(mContext: Context): Boolean {
            return DbHandler.getInstance(mContext)!!.truncateTables(DatabaseTables.BNPL_PAY_TABLE)
        }
    }
}
