package com.sc.mf919.kotlin.database.repo

import android.content.Context
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.helper_common.CounterGuard
import com.sc.mf919.kotlin.database.model.DbModelIsoBatchInfo
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig

class IsoBatchInfoRepo(){
    companion object{

        fun getBatchInfo(mContext: Context, tag: String, subtag: String): DbModelIsoBatchInfo? {
            val dbHandler = DbHandler.getInstance(mContext)!!
            return dbHandler.selectSingleData<DbModelIsoBatchInfo>("SELECT * FROM IsoBatchInfo Where tag = '${tag}' and subtag = '${subtag}' ", arrayOf<String>())
        }

        fun updateBatchInfo(mContext: Context, value: String, tag: String, subtag: String): Boolean {
            val dbHandler = DbHandler.getInstance(mContext)!!

            if(value == ""){
                return false
            }

            // Mirror monotonic counters outside the DB. This is the single choke point every
            // counter write goes through -- allocateCounter, the clear-batch roll, and the
            // settlement resets all land here -- which is why the mark is taken here rather than
            // at the call sites. See CounterGuard for what it protects against.
            CounterGuard.record(mContext, tag, subtag, value)

            val updateMap = mutableMapOf<Any,Any>(
                "value" to value
            )
            val criteriaMap = mutableMapOf<Any,Any>(
                "tag" to tag,
                "subtag" to subtag
            )

            dbHandler.updateTableValue(DatabaseTables.ISO_BATCH_INFO, updateMap, criteriaMap)

            return true
        }

        /**
         * Serialises the read-modify-write in [allocateCounter] and [withCounterLock].
         *
         * A plain object monitor is enough: there is one process, and every counter allocation in
         * the app funnels through one of those two entry points.
         */
        private val counterLock = Any()

        /**
         * Atomically allocate the next value of a counter (`stan`, `invoiceNo`, `batchNo`).
         *
         * Replaces a get-then-increment-then-put sequence that was open-coded at 37 sites across
         * the activities and all four ISO acquirer variants. That sequence holds no lock, and this
         * table is written from the nanohttpd worker thread, the ISO thread, the schedulers and the
         * UI thread -- so two concurrent flows (an App2App sale and a void; a settlement reversal
         * loop and a card tap) can read the same current value and both compute the same "next"
         * one, **issuing the same STAN or invoice number to two different transactions**. The
         * acquirer keys reconciliation on those numbers, so a duplicate is not a local cosmetic
         * problem.
         *
         * Wraps at [max] back to 1 and pads to [width], matching the code it replaces exactly.
         * Returns the value already persisted, so the caller just uses it.
         *
         * Deliberately does NOT insert a missing row, because the sequence it replaces did not
         * either: these rows are created by the migrations, and adding an insert here would change
         * behaviour on a path this change is meant to leave alone. If the row is absent the result
         * is "000001" unpersisted -- same as before.
         */
        fun allocateCounter(
            mContext: Context,
            tag: String,
            subtag: String,
            max: Int = 999999,
            width: Int = 6
        ): String = synchronized(counterLock) {
            val current = getBatchInfo(mContext, tag, subtag)?.let { Utils.atoi(it.value) }
            if (current == null) return@synchronized String.format("%0${width}d", 1)
            var next = current + 1
            if (next > max) next = 1
            val formatted = String.format("%0${width}d", next)
            updateBatchInfo(mContext, formatted, tag, subtag)
            formatted
        }

        /**
         * Run a counter read-modify-write that [allocateCounter] does not fit, under the same lock.
         *
         * The clear-batch flow needs the *previous* value as well as the next one, so it cannot use
         * allocateCounter -- but it must still exclude concurrent allocations, or a sale can take a
         * number out of the batch being closed. Sharing one monitor is what makes the exclusion
         * hold across both shapes.
         */
        fun <T> withCounterLock(block: () -> T): T = synchronized(counterLock) { block() }

        fun insertToBatchInfo(mContext: Context, dbModelIsoBatchInfo: DbModelIsoBatchInfo) {
            val dbHandler = DbHandler.getInstance(mContext)!!
            dbHandler.insertToDb(DatabaseTables.ISO_BATCH_INFO, dbModelIsoBatchInfo)
        }
    }
}