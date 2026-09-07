package com.sc.mf919pro.kotlin.migration

import com.sc.mf919pro.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919pro.kotlin.helper_common.HelperLogFileName
import helpers.HelperLog
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder

class Migration1004 {
    /*
        * Migration 1004 Details
        * - Create DenominationList and DenominationUpload
        * - Add IsFoodLink (Merchant Configuration)
        * - Add CORRELATION_REF & ADDITIONAL_INFO (Receipt Upload)
        *
        * Denomination tables: both are declared in DatabaseTables but were never created
        * anywhere: they are absent from assets/isoengine_gobiz.db (17 user tables vs 19
        * declared) and no earlier migration creates them. DenominationList only ever existed on
        * a device by accident, via insertToDb's "no such table" self-heal on the first TMS
        * write; DenominationUpload never existed at all, and reads returned emptyList() so it
        * looked like "no data".
        *
        * FoodLink columns: folded in here rather than into a Migration1005 because 1.0.04 had
        * not shipped yet when FoodLink landed — no device has recorded 1004, so this still runs
        * for everyone. The ALTER TABLE itself is issued by DbHandler.checkTableColumnExist when
        * the column is missing, so the checks below are what actually add the columns. Fresh
        * installs get them from the preloaded assets/isoengine_gobiz.db instead.
        *
        * NOTE: numbered 1004, not 1003, on purpose. runMigrationFunction gates on
        * getAppVersion() ("1.0.03" -> 1003), so a Migration1003 would be skipped on every
        * device already running 1.0.03 — i.e. exactly the devices that need it. This runs once
        * versionName reaches 1.0.04. Until then DbHandler.onOpen's ensureAllTablesExist is what
        * actually creates the tables; this migration is the belt to that braces.
    * */

    lateinit var sbLog: StringBuilder

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 1004 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        for (table in listOf(DatabaseTables.DENOMINATION_LIST, DatabaseTables.DENOMINATION_UPLOAD)) {
            HelperLog.appendLine(sbLog, "Check Table Existence -> ${table.id}")
            if (!dbHandler.checkTableExist(table)) {
                HelperLog.appendLine(sbLog, "Table Not Exist")
                createTables(table.id, table.createSql)
            }
        }

        /* FoodLink [Start] */
        var merchantConfigExist = true
        var receiptUploadExist = true

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.MERCHANT_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            merchantConfigExist = false
            createTables(DatabaseTables.MERCHANT_CONFIGURATION.id, DatabaseTables.MERCHANT_CONFIGURATION.createSql)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.RECEIPT_UPLOAD)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            receiptUploadExist = false
            createTables(DatabaseTables.RECEIPT_UPLOAD.id, DatabaseTables.RECEIPT_UPLOAD.createSql)
        }

        if (merchantConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> IsFoodLink")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "IsFoodLink")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "IsFoodLink Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")

                val customUpdateSql = "UPDATE ${DatabaseTables.MERCHANT_CONFIGURATION.id} SET IsFoodLink = 'false' WHERE IsFoodLink IS NULL"
                dbHandler.runningCustomSql(customUpdateSql, arrayOf())
            }
        }

        if (receiptUploadExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> CORRELATION_REF")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "CORRELATION_REF")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "CORRELATION_REF Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")

                val customUpdateSql = "UPDATE ${DatabaseTables.RECEIPT_UPLOAD.id} SET CORRELATION_REF = '' WHERE CORRELATION_REF IS NULL"
                dbHandler.runningCustomSql(customUpdateSql, arrayOf())
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> ADDITIONAL_INFO")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "ADDITIONAL_INFO")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "ADDITIONAL_INFO Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")

                val customUpdateSql = "UPDATE ${DatabaseTables.RECEIPT_UPLOAD.id} SET ADDITIONAL_INFO = '' WHERE ADDITIONAL_INFO IS NULL"
                dbHandler.runningCustomSql(customUpdateSql, arrayOf())
            }
        }
        /* FoodLink [End] */

        HelperLog.appendLine(sbLog, "----------------- Finish Running Migration -------------")
        HelperLog.logToFile(sbLog, HelperLogFileName.TerminaLog)
    }

    private fun createTables(tableName: String, tableField: List<String>) {
        HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        val querySeperator = ", "
        val query = "CREATE TABLE $tableName (${tableField.joinToString(querySeperator)})"
        dbHandler.runningCustomSql(query, arrayOf())
    }
}
