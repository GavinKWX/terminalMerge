package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration1410 {
    /*
	* Migration 1410 Details
	* - Add FORCE_LOCK_HOME, FORCE_SETTLEMENT, UNATTENDED_MODE (Terminal Configuration)
	* - Add Customization (Merchant Configuration)
	* */
    lateinit var sbLog: StringBuilder
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
        "UNATTENDED_MODE TEXT"
    )


    private val merchantConfigurationField = listOf(
        "MerchantName TEXT,",
        "MerchantAddress TEXT,",
        "AutoSettleT1 TEXT,",
        "AutoSettleT2 TEXT,",
        "AutoSettleT3 TEXT,",
        "WaitCardMs TEXT,",
        "QrMid TEXT,",
        "QrTid TEXT,",
        "McVer TEXT,",
        "AcqCode TEXT,",
        "AcqMid TEXT,",
        "AcqTid TEXT,",
        "PrimaryHostIp TEXT,",
        "PrimaryHostPort TEXT,",
        "SecondaryHostIp TEXT,",
        "SecondaryHostPort TEXT,",
        "TPDU TEXT,",
        "NII TEXT,",
        "HostTimeoutMs TEXT,",
        "LastSettlementBatchNo TEXT,",
        "LastStan TEXT,",
        "LastInvoiceNo TEXT",
        "Customization TEXT"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 1410 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            createTables(DatabaseTables.TERMINAL_CONFIGURATION.id, terminalConfigurationField)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.MERCHANT_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            createTables(DatabaseTables.MERCHANT_CONFIGURATION.id, merchantConfigurationField)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        /* Terminal Config */
        HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> EWALLET_PRODUCT_LIST")
        if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "EWALLET_PRODUCT_LIST")) {
            HelperLog.appendLine(sbLog, "Column Not Exist")
            HelperLog.appendLine(sbLog, "EWALLET_PRODUCT_LIST Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
        }

        HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> FORCE_LOCK_HOME")
        if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "FORCE_LOCK_HOME")) {
            HelperLog.appendLine(sbLog, "Column Not Exist")
            HelperLog.appendLine(sbLog, "FORCE_LOCK_HOME Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
        }

        HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> FORCE_SETTLEMENT")
        if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "FORCE_SETTLEMENT")) {
            HelperLog.appendLine(sbLog, "Column Not Exist")
            HelperLog.appendLine(sbLog, "FORCE_SETTLEMENT Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
        }

        HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> UNATTENDED_MODE")
        if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "UNATTENDED_MODE")) {
            HelperLog.appendLine(sbLog, "Column Not Exist")
            HelperLog.appendLine(sbLog, "UNATTENDED_MODE Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
        }
        /* Terminal Config */

        /* Merchant Config */
        HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> Customization")
        if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "Customization")) {
            HelperLog.appendLine(sbLog, "Column Not Exist")
            HelperLog.appendLine(sbLog, "Customization Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
        }
        /* Merchant Config */
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