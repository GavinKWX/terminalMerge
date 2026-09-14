package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import enums.EnumLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder

class Migration2217 {
    /*
        * Migration 2217 Details
        * - Add DenominationList Table
        * - Add DenominationUpload Table
        * - Add DENOMINATION in (Terminal Configuration)
    * */

    lateinit var sbLog: StringBuilder
    /*private val denominationListField = listOf(
        "Id INTEGER NOT NULL UNIQUE",
        "PackageId TEXT",
        "Desc TEXT",
        "Amount TEXT",
        "Ref1 TEXT",
        "Ref2 TEXT",
        "Ref3 TEXT",
        "Ref4 TEXT",
        "Ref5 TEXT",
        "Remark TEXT",
        "MaintenanceSchedule TEXT",
        "DisplayMenutype TEXT",
        "PRIMARY KEY(Id AUTOINCREMENT)"
    )

    private val denominationUploadField = listOf(
        "Id INTEGER NOT NULL UNIQUE",
        "Type TEXT",
        "DataString TEXT",
        "CreationDate TEXT",
        "LastUpdateDt TEXT",
        "IsProcessing TEXT",
        "IsSend TEXT",
        "PRIMARY KEY(Id AUTOINCREMENT)"
    )

    private val terminalConfigurationField = listOf(
        "Contact TEXT",
        "Contactless TEXT",
        "MagStripe TEXT",
        "ForcePin TEXT",
        "IsoPrint TEXT",
        "ReceiptPrint TEXT",
        "Sale TEXT",
        "Void TEXT",
        "PreAuth TEXT",
        "SaleCom TEXT",
        "SaleComOnline TEXT",
        "Refund TEXT",
        "OptIn TEXT",
        "TmsReceipt TEXT",
        "TmsEnable TEXT",
        "AutoSettle TEXT",
        "QrPay TEXT",
        "PoweredBy TEXT",
        "PoweredByBW TEXT",
        "HomeLogo TEXT",
        "MC_VER TEXT",
        "DEV_PROJECT TEXT",
        "DEV_LOCATION TEXT",
        "DEV_LANE_ID TEXT",
        "VOID_WITH_PIN TEXT",
        "MOTO TEXT",
        "TIMEOUT_SECONDS TEXT",
        "SETTLEMENT_WITH_PIN TEXT",
        "REMOTE_DOWNLOAD_BSN_KEY TEXT",
        "EWALLET_PRODUCT_LIST TEXT",
        "FORCE_LOCK_HOME TEXT",
        "FORCE_SETTLEMENT TEXT",
        "UNATTENDED_MODE TEXT",
        "CABLE_CONNECTION TEXT",
        "FORCE_SETTLEMENT_DAILY TEXT",
        "CASHOUT TEXT",
        "ISO_WEBSOCKET TEXT",
        "WEBSOCKET TEXT",
        "SALES_CARD TEXT",
        "SALES_EWALLET TEXT",
        "DENOMINATION TEXT"
    )*/

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2217 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var terminalConfigExist = true

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.DENOMINATION_LIST.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.DENOMINATION_LIST)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            createTables(DatabaseTables.DENOMINATION_LIST.id, DatabaseTables.DENOMINATION_LIST.createSql)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.DENOMINATION_UPLOAD.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.DENOMINATION_UPLOAD)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            createTables(DatabaseTables.DENOMINATION_UPLOAD.id, DatabaseTables.DENOMINATION_UPLOAD.createSql)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            terminalConfigExist = false
            createTables(DatabaseTables.TERMINAL_CONFIGURATION.id, DatabaseTables.TERMINAL_CONFIGURATION.createSql)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        if(terminalConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> DENOMINATION")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "DENOMINATION")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "DENOMINATION Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
            }
        }
        /*  Table Exist [End]*/

        HelperLog.appendLine(sbLog, "----------------- Finish Running Migration -------------")
        HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
    }

    private fun createTables(tableName: String, tableField: List<String>) {
        HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        val querySeperator = ", "
        val query = "CREATE TABLE $tableName (${tableField.joinToString(querySeperator)})"
        dbHandler.runningCustomSql(query, arrayOf())
    }
}