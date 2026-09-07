package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelIsoBatchInfo
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration2101 {
    /*
	* Migration 2101 Details
	* - Add MCCS SchemeTag & PosEntryMode into IsoBatchInfo for Cardzone
	* - Delete redundant PosEntryMode for subtag "visam-92"
	* - Drop ReceiptUploadTable and Reinsert Back
	* */
    lateinit var sbLog: StringBuilder
    private val receiptUploadField = listOf(
        "SEQ_NO TEXT",
        "TXN_DT TEXT",
        "TXN_TYPE TEXT",
        "MID TEXT",
        "TID TEXT",
        "MTI TEXT",
        "NII TEXT",
        "SCHEME_ID TEXT",
        "AID TEXT",
        "CARD_MASKED TEXT",
        "CARD_HASHED TEXT",
        "RRN TEXT",
        "APPR_CODE TEXT",
        "RRN_ORI TEXT",
        "APPR_CODE_ORI TEXT",
        "TXN_AMT TEXT",
        "INV_NO TEXT",
        "STAN TEXT",
        "BATCH_NO TEXT",
        "RESP_CODE TEXT",
        "APP_VER TEXT",
        "SN TEXT",
        "ENTRY_TYPE TEXT",
        "ARQC TEXT",
        "TVR TEXT",
        "POS_REF_NO TEXT",
        "PAYMENT_PRODUCT_ID TEXT",
        "CVM TEXT",
        "CARD_LABEL TEXT",
        "EPP_DETAIL TEXT",
        "BATCHNO_PREAUTH TEXT",
        "CASHOUT_AMT TEXT",
        "QrRefId TEXT",
        "QrType TEXT",
        "CreationDate TEXT",
        "LastUpdateDt TEXT",
        "IsProcessing TEXT",
        "IsSend TEXT"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2101 -----")

        /*  Table Exist [Start]*/
        //Delete redundant records
        deleteRecords(DatabaseTables.ISO_BATCH_INFO.id, "posEntryMode", "visam-92", "0081")

        val schemeTag82Entry = IsoBatchInfoRepo.getBatchInfo(ServiceHolder.getContext(), "schemeTag", "82")
        HelperLog.appendLine(sbLog, "Check data in Table IsoBatchInfo -> tag:schemeTag, subtag: 82")
        if (schemeTag82Entry == null) {
            HelperLog.appendLine(sbLog, "schemeTag 82 not exist")
            HelperLog.appendLine(sbLog, "Adding value tag=>schemeTag, subtag=>82, value=>mccs")
            val schemeTag82Insert = DbModelIsoBatchInfo("schemeTag", "82", "mccs")
            IsoBatchInfoRepo.insertToBatchInfo(ServiceHolder.getContext(), schemeTag82Insert)
        }

        val schemeTag99Entry = IsoBatchInfoRepo.getBatchInfo(ServiceHolder.getContext(), "schemeTag", "99")
        HelperLog.appendLine(sbLog, "Check data in Table IsoBatchInfo -> tag:schemeTag, subtag: 99")
        if (schemeTag99Entry == null) {
            HelperLog.appendLine(sbLog, "schemeTag 99 not exist")
            HelperLog.appendLine(sbLog, "Adding value tag=>schemeTag, subtag=>99, value=>mccs")
            val schemeTag99Insert = DbModelIsoBatchInfo("schemeTag", "99", "mccs")
            IsoBatchInfoRepo.insertToBatchInfo(ServiceHolder.getContext(), schemeTag99Insert)
        }

        val posEntryMode82Entry = IsoBatchInfoRepo.getBatchInfo(ServiceHolder.getContext(), "posEntryMode", "mccs-82")
        HelperLog.appendLine(sbLog, "Check data in Table IsoBatchInfo -> tag:posEntryMode, subtag: mccs-82")
        if (posEntryMode82Entry == null) {
            HelperLog.appendLine(sbLog, "posEntryMode mccs-82 not exist")
            HelperLog.appendLine(sbLog, "Adding value tag=>posEntryMode, subtag=>mccs-82, value=>0071")
            val posEntryMode82Insert = DbModelIsoBatchInfo("posEntryMode", "mccs-82", "0071")
            IsoBatchInfoRepo.insertToBatchInfo(ServiceHolder.getContext(), posEntryMode82Insert)
        }

        val posEntryMode99Entry = IsoBatchInfoRepo.getBatchInfo(ServiceHolder.getContext(), "posEntryMode", "mccs-99")
        HelperLog.appendLine(sbLog, "Check data in Table IsoBatchInfo -> tag:posEntryMode, subtag: mccs-99")
        if (posEntryMode99Entry == null) {
            HelperLog.appendLine(sbLog, "posEntryMode mccs-99 not exist")
            HelperLog.appendLine(sbLog, "Adding value tag=>posEntryMode, subtag=>mccs-99, value=>0051")
            val posEntryMode99Insert = DbModelIsoBatchInfo("posEntryMode", "mccs-99", "0051")
            IsoBatchInfoRepo.insertToBatchInfo(ServiceHolder.getContext(), posEntryMode99Insert)
        }

        // Was: dropTables(RECEIPT_UPLOAD) + createTables(...) -- an unconditional wipe of the table
        // that holds receipts NOT yet uploaded to TMS (it carries IsSend / IsProcessing).
        //
        // Why this had to change now: the Aisino fix set pairs the WAL removal with F9, which resets
        // setMigrationVersion(0) on a forced DB recopy. MIGRATE_VER lives in SharedPreferences and so
        // survives a DB wipe, which is why F9 exists -- but on MF919 a reset replays THIS migration,
        // and the drop above would then destroy un-uploaded receipts on a live terminal. Making the
        // rebuild non-destructive is the prerequisite for ever porting F9 here.
        //
        // Uses the temp-table copy idiom already used by Migration135 / 1312 / 1313 in this package.
        // Columns are read from the live table rather than hardcoded: terminals in the field arrived
        // here through different earlier migrations, so the pre-existing column set is not knowable
        // from source. New columns simply stay empty.
        // See obsidian ADR-005 and FIX-2026-08-05 (MF919 WAL removal).
        rebuildReceiptUploadPreservingData()

        /*  Table Exist [End]*/
        HelperLog.appendLine(sbLog, "----------------- Finish Running Migration -------------")
        HelperLog.logToFile(sbLog, HelperLogFileName.TerminaLog)
    }

    private fun deleteRecords(tableName: String, tag: String, subtag: String, value: String) {
        HelperLog.appendLine(sbLog, "Delete record from Table $tableName tag=>$tag, subtag=>$subtag, value=>$value")

        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        val sqlString = "DELETE FROM ${DatabaseTables.ISO_BATCH_INFO.id} WHERE tag = ? AND subtag = ? AND value = ? "
        dbHandler.runningCustomSql(sqlString, arrayOf(tag, subtag, value))
    }

    private fun createTables(tableName: String, tableField: List<String>) {
        HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        val querySeperator = ", "
        val query = "CREATE TABLE $tableName (${tableField.joinToString(querySeperator)})"
        dbHandler.runningCustomSql(query, arrayOf())
    }

    private fun dropTables(tableName: String) {
        HelperLog.appendLine(sbLog, "Drop Table -> ", tableName)
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        val query = "DROP TABLE IF EXISTS $tableName"
        dbHandler.runningCustomSql(query, arrayOf())
    }

    /**
     * Brings ReceiptUpload to the schema above WITHOUT discarding rows.
     *
     * The original code dropped and recreated the table. That was deliberate — it healed a
     * missing/corrupt ReceiptUpload, written before DbHandler.insertToDb had its own
     * "no such table" / "has no column named" self-heal — but it also destroyed any receipt that had
     * not yet uploaded to TMS.
     *
     * The heal guarantee is preserved and still unconditional: after this returns, a usable table
     * exists at the schema above. Data is preserved whenever it is readable:
     *
     *   1. table missing / schema unreadable -> create fresh (nothing recoverable)
     *   2. schema differs                    -> copy the intersection of live and target columns
     *   3. end state still wrong             -> hard reset to canonical schema (the old behaviour)
     *
     * Safe to re-run, which is what makes a migration-counter reset survivable.
     *
     * Two traps in DbHandler that this must NOT use:
     *   - checkTableColumnExist() is not a predicate: it ALTER TABLE ADD COLUMNs as a side effect and
     *     returns false whenever it had to add. Columns are read here with a read-only PRAGMA.
     *   - executeScalar() / runningCustomSql() swallow exceptions and return 0, so through them
     *     "empty table" and "unreadable table" are indistinguishable. Both are read directly here,
     *     because that difference decides whether the old table is worth keeping as a backup.
     */
    private fun rebuildReceiptUploadPreservingData() {
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        val name = DatabaseTables.RECEIPT_UPLOAD.id
        val tempName = name + "_temp"
        val backupName = name + "_BKV2101"
        val targetColumns = receiptUploadField.map { it.substringBefore(' ') }

        if (!tableExists(dbHandler, name)) {
            HelperLog.appendLine(sbLog, "$name absent - creating fresh, nothing to preserve")
            createTables(name, receiptUploadField)
            ensureReceiptUploadUsable(dbHandler, name, targetColumns)
            return
        }

        val liveColumns = readColumns(dbHandler, name)
        val rowsBefore = countRows(dbHandler, name)
        val readable = liveColumns != null && rowsBefore >= 0
        HelperLog.appendLine(sbLog, "$name state :: readable=$readable " +
            "columns=${liveColumns?.size ?: -1} rows=$rowsBefore")

        dropTables(tempName)
        createTables(tempName, receiptUploadField)

        val carried = (liveColumns ?: targetColumns).filter { targetColumns.contains(it) }
        var copied = 0
        if (carried.isEmpty()) {
            HelperLog.appendLine(sbLog, "No usable columns to carry")
        } else {
            val cols = carried.joinToString(", ")
            dbHandler.runningCustomSql("INSERT INTO $tempName ($cols) SELECT $cols FROM $name", arrayOf())
            copied = countRows(dbHandler, tempName).coerceAtLeast(0)
            val expected = if (rowsBefore < 0) "unknown" else rowsBefore.toString()
            HelperLog.appendLine(sbLog, "Receipts preserved -> ", "$copied of $expected")
            if (rowsBefore > copied) {
                HelperLog.appendLine(sbLog, "RECEIPT ROWS LOST :: ${rowsBefore - copied} unreadable " +
                    "row(s) in $name - reconcile receipt uploads against TMS")
                HelperLog.logToFile(sbLog, HelperLogFileName.TerminaDbException)
            }
        }

        // Keep the original only if it was actually readable. An unreadable table is worthless as a
        // backup and would leave malformed pages in the DB file, so drop it as the old code did.
        if (readable) {
            dropTables(backupName)
            dbHandler.runningCustomSql("ALTER TABLE $name RENAME TO $backupName", arrayOf())
            if (tableExists(dbHandler, backupName)) {
                HelperLog.appendLine(sbLog, "Previous rows retained in $backupName")
            } else {
                HelperLog.appendLine(sbLog, "Backup rename failed - dropping $name instead")
                dropTables(name)
            }
        } else {
            HelperLog.appendLine(sbLog, "$name unreadable (salvaged $copied row(s)) - dropping it")
            dropTables(name)
        }

        dbHandler.runningCustomSql("ALTER TABLE $tempName RENAME TO $name", arrayOf())
        ensureReceiptUploadUsable(dbHandler, name, targetColumns)
    }

    /** Honest existence check: reads sqlite_master and mutates nothing. */
    private fun tableExists(dbHandler: DbHandler, tableName: String): Boolean = try {
        dbHandler.readableDatabase.rawQuery(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)
        ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
    } catch (e: Exception) {
        HelperLog.appendLine(sbLog, "tableExists($tableName) failed", e.toString())
        false
    }

    /** Read-only column list. null means the schema could not be read, which is not "empty". */
    private fun readColumns(dbHandler: DbHandler, tableName: String): List<String>? = try {
        val names = mutableListOf<String>()
        dbHandler.readableDatabase.rawQuery("PRAGMA table_info($tableName)", null).use { c ->
            val idx = c.getColumnIndex("name")
            if (idx >= 0) {
                while (c.moveToNext()) names.add(c.getString(idx))
            }
        }
        if (names.isEmpty()) null else names
    } catch (e: Exception) {
        HelperLog.appendLine(sbLog, "readColumns($tableName) failed", e.toString())
        null
    }

    /** -1 on failure, so "unreadable" stays distinguishable from "empty". */
    private fun countRows(dbHandler: DbHandler, tableName: String): Int = try {
        dbHandler.readableDatabase.rawQuery("SELECT count(*) FROM $tableName", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
    } catch (e: Exception) {
        HelperLog.appendLine(sbLog, "countRows($tableName) failed", e.toString())
        -1
    }

    /**
     * Unconditional heal, preserving this step's original contract: whatever happened above, the app
     * must find a ReceiptUpload table at the canonical schema afterwards.
     */
    private fun ensureReceiptUploadUsable(
        dbHandler: DbHandler,
        name: String,
        expectedColumns: List<String>
    ) {
        val present = tableExists(dbHandler, name)
        val columns = if (present) readColumns(dbHandler, name) else null
        val missing = expectedColumns.filterNot { columns != null && columns.contains(it) }
        if (present && missing.isEmpty()) {
            HelperLog.appendLine(sbLog, "$name verified usable at target schema")
            return
        }

        HelperLog.appendLine(sbLog, "HEAL $name :: present=$present missingColumns=${missing.size} " +
            "- resetting to canonical schema")
        HelperLog.logToFile(sbLog, HelperLogFileName.TerminaDbException)
        dropTables(name + "_temp")
        dropTables(name)
        createTables(name, receiptUploadField)
    }
}
