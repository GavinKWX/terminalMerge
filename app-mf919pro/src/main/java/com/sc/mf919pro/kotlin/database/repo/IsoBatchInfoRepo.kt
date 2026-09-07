package com.sc.mf919pro.kotlin.database.repo

import android.content.Context
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelIsoBatchInfo
import com.sc.mf919pro.kotlin.helper_common.CounterGuard

class IsoBatchInfoRepo(){
    companion object{

        fun getBatchInfo(mContext: Context, tag: String, subtag: String): DbModelIsoBatchInfo? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.selectSingleData<DbModelIsoBatchInfo>("SELECT * FROM ${DatabaseTables.ISO_BATCH_INFO.id} Where tag = ? and subtag = ? ", arrayOf<String>(tag, subtag))
        }

        fun updateBatchInfo(mContext: Context, value: String, tag: String, subtag: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!
            if(value == ""){
                return false
            }

            // Mirror monotonic counters outside the DB. This is the single choke point
            // all 41 counter writers go through, which is why the mark is taken here rather than
            // at the call sites.
            CounterGuard.record(mContext, tag, subtag, value)

            val preSelect = dbHandler.selectSingleData<DbModelIsoBatchInfo>("SELECT * FROM ${DatabaseTables.ISO_BATCH_INFO.id} Where tag = ? and subtag = ? ", arrayOf<String>(tag, subtag))
            if(preSelect == null) {
                val dbModelIsoBatchInfo = DbModelIsoBatchInfo(tag, subtag, value)
                // This table holds stan/invoiceNo/batchNo — a swallowed failure here is a counter
                // that never advanced, so report it rather than always answering true.
                return dbHandler.insertToDb(DatabaseTables.ISO_BATCH_INFO, dbModelIsoBatchInfo)
            } else {
                val updateMap = mutableMapOf<Any,Any>(
                    "value" to value
                )
                val criteriaMap = mutableMapOf<Any,Any>(
                    "tag" to tag,
                    "subtag" to subtag
                )
                // updateTableValue returns 0 on failure; do not launder that into true.
                return dbHandler.updateTableValue(DatabaseTables.ISO_BATCH_INFO, updateMap, criteriaMap) > 0
            }
        }

        /**
         * Serialises access to the read-modify-write below. A plain object monitor is enough:
         * there is exactly one process, and every counter allocation in the app funnels here.
         */
        private val counterLock = Any()

        /**
         * Atomically allocate the next value of a counter (`stan`, `invoiceNo`, `batchNo`).
         *
         * Replaces the get-then-increment-then-put sequence that was open-coded at **34 sites**.
         * That sequence has no locking, so two concurrent flows — an App2App sale and a void, a
         * settlement reversal loop and a card tap — can read the same current value and both
         * compute the same "next" one, **issuing the same STAN or invoice number to two different
         * transactions**. The acquirer keys reconciliation on those numbers, so a duplicate is not
         * a local cosmetic problem.
         *
         * This is the other half of the counter story: [[CounterGuard]] stops counters going
         * *backwards* after a DB recopy; this stops two callers getting the *same* number forward.
         *
         * Wraps at [max] back to 1, matching the behaviour of the code it replaces. Returns the
         * value zero-padded to [width] and already persisted, so the caller just uses it.
         */
        fun allocateCounter(
            mContext: Context,
            tag: String,
            subtag: String,
            max: Int = 999999,
            width: Int = 6
        ): String = synchronized(counterLock) {
            val current = getBatchInfo(mContext, tag, subtag)?.let { Utils.atoi(it.value) } ?: 0
            var next = current + 1
            if (next > max) next = 1
            val formatted = String.format("%0${width}d", next)
            updateBatchInfo(mContext, formatted, tag, subtag)
            formatted
        }

        fun insertToBatchInfo(mContext: Context, dbModelIsoBatchInfo: DbModelIsoBatchInfo) {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.insertToDb(DatabaseTables.ISO_BATCH_INFO, dbModelIsoBatchInfo)
        }
    }
}