package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration2202 {
    /*
    * Migration 2202 Details
    * - Add SkipTxnValidation (Merchant Configuration)
    * - Add WEBSOCKET (Terminal Configuration)
    * */
    lateinit var sbLog: StringBuilder
    private val merchantConfigurationField = listOf(
        "MerchantName TEXT",
        "MerchantAddress TEXT",
        "AutoSettleT1 TEXT",
        "AutoSettleT2 TEXT",
        "AutoSettleT3 TEXT",
        "WaitCardMs TEXT",
        "QrMid TEXT",
        "QrTid TEXT",
        "McVer TEXT",
        "AcqCode TEXT",
        "AcqMid TEXT",
        "AcqTid TEXT",
        "PrimaryHostIp TEXT",
        "PrimaryHostPort TEXT",
        "SecondaryHostIp TEXT",
        "SecondaryHostPort TEXT",
        "TPDU TEXT",
        "NII TEXT",
        "HostTimeoutMs TEXT",
        "LastSettlementBatchNo TEXT",
        "LastStan TEXT",
        "LastInvoiceNo TEXT",
        "Customization TEXT",
        "Marketing TEXT",
        "Advertisement TEXT",
        "AcquirerLogo TEXT",
        "Action1 TEXT",
        "Action2 TEXT",
        "Action3 TEXT",
        "Action4 TEXT",
        "SkipTxnValidation TEXT"
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
        "WEBSOCKET TEXT"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2202 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var merchantConfigExist = true
        var terminalConfigExist = true

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.MERCHANT_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            merchantConfigExist = false
            createTables(DatabaseTables.MERCHANT_CONFIGURATION.id, merchantConfigurationField)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            terminalConfigExist = false
            createTables(DatabaseTables.TERMINAL_CONFIGURATION.id, terminalConfigurationField)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        if(merchantConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> SkipTxnValidation")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "SkipTxnValidation")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "SkipTxnValidation Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }
        }

        if(terminalConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> WEBSOCKET")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "WEBSOCKET")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "WEBSOCKET Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
            }
        }
        /*  Table Exist [End]*/
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