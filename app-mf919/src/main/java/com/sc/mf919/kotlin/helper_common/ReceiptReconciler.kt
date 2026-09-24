package com.sc.mf919.kotlin.helper_common

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.reflect.TypeToken
import emv.EmvTag
import constants.TerminalConstants
import com.sc.mf919.java.activity.Utils
import utils.HexUtil
import com.sc.mf919.kotlin.activity.AppServices
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelReceiptUpload
import com.sc.mf919.kotlin.database.repo.BatchTableRepo
import com.sc.mf919.kotlin.database.repo.PreAuthTableRepo
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.database.repo.ReversalBatchTableRepo
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import enums.EnumLogFileName
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recovers ReceiptUpload rows that were left unresolved.
 *
 * `insertPendingTransactionInto()` writes the receipt row BEFORE the host request goes out, with
 * RRN / APPR_CODE / RESP_CODE = "-". `IsoActivity.updateReceiptInfo()` is what fills those in once
 * the host has answered. Both are detached `persistenceScope(...).launch` blocks, so if the process
 * dies in between - user kills the app, watchdog restart, crash, power loss - nothing ever repairs
 * the row. `TmsReceiptUploadScheduler` then ships it unchanged after 30 minutes and the backend
 * records a paid transaction as a failure.
 *
 * The outcome survives the kill: the ISO TLV in `batchTable` / `preauthTable` (both written on
 * approval) or a row in `revBatchTable` (written on communication timeout). This reconciles the
 * stranded rows against those tables, on app start and before every upload pass.
 *
 * Ported from the Aisino A99 implementation (see FIX-2026-08-10 in the terminal fix registry),
 * device-verified there 2026-09-11. **Deliberately NOT a copy** - three things differ on MF919:
 *  - this schema has **no `PROCESSING_DATE_FROM` / `PROCESSING_DATE_TO` columns**, so the A99
 *    timestamp recovery (and the format-conversion bug it carried) has no equivalent here;
 *  - `updateReceiptInfo()` here also resets `IsProcessing` / `IsSend`, which this pass leaves
 *    alone - it only ever touches rows that are already `false`/`false`;
 *  - logging goes through `EnumLogFileName` (upstream MF919 uses `HelperLogFileName`).
 */
object ReceiptReconciler {
    private const val LOG_CLASS_NAME = "ReceiptReconciler"

    /** Response code for a transaction that timed out and is sitting in the reversal table. */
    private const val RESP_CODE_TIMEOUT = "ZU"

    /**
     * App-start entry point: reconciles, then kicks an upload if anything was repaired.
     * TmsReceiptUploadScheduler calls [reconcile] directly - it is about to run the upload pass
     * itself and does not need the extra trigger.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun reconcileOrphanReceipts(context: Context) = withContext(Dispatchers.IO) {
        if (reconcile(context) > 0) {
            AppServices.receiptUploadToTms(context)
        }
    }

    /**
     * Repairs every stranded row it can and returns how many were repaired. Blocking; call it from
     * a worker thread.
     *
     * @param minAgeMinutes ignore rows younger than this. A transaction still talking to the host
     *        has a perfectly healthy unresolved row in the table - it is seconds old and about to
     *        be filled in. `insertPendingTransactionInto()` triggers an immediate upload job, so
     *        the scheduler's pass runs *while the host request is still in flight*; on A99 that was
     *        measured at 234 ms after the ISO request went out. Callers that can overlap a live
     *        transaction must pass a grace period; the app-start path cannot overlap one.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun reconcile(context: Context, minAgeMinutes: Int = 0): Int {
        val sbLog = HelperLog.init("$LOG_CLASS_NAME - Orphan Receipt Reconcile")
        var reconciled = 0
        var unresolved = 0

        try {
            val dbHandler = DbHandler.getInstance(context)
            val listType = object : TypeToken<List<DbModelReceiptUpload>>() {}.type

            // Two shapes of unresolved row, not one:
            //
            //  - RESP_CODE = "-"  the marker insertPendingTransactionInto() leaves behind, and the
            //    one TmsReceiptUploadScheduler holds back for 30 minutes.
            //  - RESP_CODE = ""   updateReceiptInfo() ran but had nothing to write. It writes
            //    Utility.HexString2ASCII(TransData.respCode) unconditionally, so when the host was
            //    never reached and respCode is still empty it *overwrites* the "-" placeholder with
            //    an empty string. Device-verified on A99 2026-09-11 by pulling the network
            //    mid-sale: transResult came back -10001 rather than the communicationTimeout
            //    constant that would have produced "ZU", and the row landed with RESP_CODE / RRN /
            //    APPR_CODE all blank. MF919 carries the identical unconditional write.
            //
            // The empty shape is the more dangerous of the two: the uploader's filter is
            // RESP_CODE != '-', so a blank row sails straight through to TMS with no 30-minute
            // hold, whereas a "-" row is at least held back.
            //
            // Rows already sent, or claimed by the upload worker right now, are left alone - as is
            // anything younger than minAgeMinutes, which excludes a still-in-flight transaction.
            val currDT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
            val orphans = dbHandler.selectListData<DbModelReceiptUpload>(
                listType,
                "SELECT * FROM ReceiptUpload WHERE IsSend = ? AND IsProcessing = ? " +
                    "AND (RESP_CODE = ? OR RESP_CODE IS NULL OR TRIM(RESP_CODE) = '') " +
                    "AND Cast((JULIANDAY('$currDT') - JULIANDAY(CreationDate)) * 24 * 60 As Integer) >= ? " +
                    "ORDER BY CreationDate ASC",
                arrayOf("false", "false", "-", minAgeMinutes.toString())
            )

            HelperLog.appendLine(sbLog, "Pending receipt rows found", orphans.size.toString())
            if (orphans.isEmpty()) return 0

            for (orphan in orphans) {
                val stan = orphan.STAN.orEmpty()
                val invNo = orphan.INV_NO.orEmpty()
                val batchNo = orphan.BATCH_NO.orEmpty()
                val rowId = "stan=$stan invNo=$invNo batchNo=$batchNo type=${orphan.TXN_TYPE}"

                if (stan.isEmpty() || invNo.isEmpty()) {
                    HelperLog.appendLine(sbLog, "Skip - no key to match on", rowId)
                    unresolved++
                    continue
                }

                val outcome = resolveOutcome(context, stan, invNo, batchNo)
                if (outcome == null) {
                    // Nothing on the terminal says this transaction ever completed. It may simply
                    // have been abandoned before the host answered, so the row is NOT forced to a
                    // response code - "no record" must mean unknown, never failed. It keeps the
                    // existing behaviour and is logged so a genuinely lost approval stays visible.
                    HelperLog.appendLine(sbLog, "Unresolved pending receipt", rowId)
                    unresolved++
                    continue
                }

                val updateMap = buildUpdateMap(orphan, outcome)
                if (updateMap.isEmpty()) {
                    HelperLog.appendLine(sbLog, "Nothing recoverable from stored record", rowId)
                    unresolved++
                    continue
                }

                val criteriaMap = java.util.HashMap<Any, Any>()
                criteriaMap["SEQ_NO"] = orphan.SEQ_NO.orEmpty()
                criteriaMap["TXN_DT"] = orphan.TXN_DT.orEmpty()
                criteriaMap["TXN_TYPE"] = orphan.TXN_TYPE.orEmpty()
                criteriaMap["CreationDate"] = orphan.CreationDate.orEmpty()

                ReceiptUploadRepo.updateData(context, updateMap, criteriaMap)
                HelperLog.appendLine(sbLog, "Reconciled [$rowId]", updateMap.toString())
                reconciled++
            }
        } catch (e: Exception) {
            e.printStackTrace()
            HelperLog.appendLine(sbLog, "Exception", e.toString())
            HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLogException)
        } finally {
            HelperLog.appendLine(sbLog, "Reconciled", reconciled.toString())
            HelperLog.appendLine(sbLog, "Unresolved", unresolved.toString())
            HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
            // HelperLog's StringBuilder overloads only reach the log file. Mirror the summary to
            // Timber so a repair (or a row that could not be repaired) is visible in logcat on a
            // debug build without pulling the file.
            if (reconciled > 0 || unresolved > 0) {
                Utils.debugLogPrint(
                    LOG_CLASS_NAME,
                    "orphan receipt reconcile: repaired=$reconciled unresolved=$unresolved"
                )
            }
        }

        return reconciled
    }

    /** What the host actually answered, recovered from whichever table holds the completed txn. */
    private data class Outcome(val batchData: String?, val respCode: String?)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun resolveOutcome(
        context: Context,
        stan: String,
        invNo: String,
        batchNo: String
    ): Outcome? {
        // Approved sale / sale completion / EPP / MOTO - full response TLV kept in batchTable.
        BatchTableRepo.getSingle(
            context,
            listOf("stan", "invNo", "batchNo"),
            arrayOf(stan, invNo, batchNo)
        )?.let { return Outcome(it.batchData, null) }

        // Approved pre-auth - same TLV, different table, keyed by invoice only.
        PreAuthTableRepo.getSingle(context, listOf("invNo"), arrayOf(invNo))
            ?.let { return Outcome(it.addInfo, null) }

        // Communication timeout - a reversal is queued. The stored TLV is the request (there was no
        // response), so the code is the timeout marker checkTransactionStatus would have set.
        ReversalBatchTableRepo.getBatchData(
            context,
            listOf("stan", "invNo", "batchNo"),
            arrayOf(stan, invNo, batchNo)
        ).firstOrNull()?.let { return Outcome(null, RESP_CODE_TIMEOUT) }

        return null
    }

    /**
     * Mirrors the value map `updateReceiptInfo()` would have written, rebuilt from the stored TLV.
     * Only fields that could actually be recovered are written, so a tag that is absent leaves the
     * existing placeholder rather than blanking it.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildUpdateMap(
        orphan: DbModelReceiptUpload,
        outcome: Outcome
    ): java.util.HashMap<Any, Any> {
        val valueHM = java.util.HashMap<Any, Any>()
        val buf = outcome.batchData?.takeIf { it.isNotEmpty() }?.let { HexUtil.hexStringToByte(it) }

        val respCode = outcome.respCode
            ?: buf?.let { tagAscii(it, TerminalConstants.cube.CUBE_TAG_RESPCODE) }.orEmpty()
        if (respCode.isEmpty()) return valueHM
        valueHM["RESP_CODE"] = respCode

        if (buf != null) {
            val rrn = tagAscii(buf, TerminalConstants.cube.CUBE_TAG_RRN)
            if (rrn.isNotEmpty()) {
                valueHM["RRN"] = rrn
                valueHM["RRN_ORI"] = rrn
            }
            val apprCode = tagAscii(buf, TerminalConstants.cube.CUBE_TAG_APPRCODE)
            if (apprCode.isNotEmpty()) {
                valueHM["APPR_CODE"] = apprCode
                valueHM["APPR_CODE_ORI"] = apprCode
            }
            tagHex(buf, TerminalConstants.iso.tag.MTI).takeIf { it.isNotEmpty() }?.let { valueHM["MTI"] = it }
            tagHex(buf, TerminalConstants.cube.CUBE_TAG_CARD_ARQC).takeIf { it.isNotEmpty() }?.let { valueHM["ARQC"] = it }
            tagHex(buf, TerminalConstants.cube.CUBE_TAG_CARD_TVR).takeIf { it.isNotEmpty() }?.let { valueHM["TVR"] = it }

            if (orphan.TXN_TYPE.equals("EPP", true)) {
                val eppDetails = tagAscii(buf, TerminalConstants.cube.CUBE_TAG_EPP_DETAILS).trim()
                valueHM["EPP_DETAIL"] = IsoActivity.parseEppDetailsJson(eppDetails)
            }
        }

        return valueHM
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun tagHex(buf: ByteArray, tag: String): String {
        val out = ByteArray(1024)
        val len = EmvTag().getValueFrom(buf, tag, out)
        return if (len <= 0) "" else HexUtil.bytesToHexString(out, 0, len)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun tagAscii(buf: ByteArray, tag: String): String {
        val out = ByteArray(1024)
        val len = EmvTag().getValueFrom(buf, tag, out)
        return if (len <= 0) "" else Utils.byteArrayToAsciiString(out, 0, len)
    }
}
