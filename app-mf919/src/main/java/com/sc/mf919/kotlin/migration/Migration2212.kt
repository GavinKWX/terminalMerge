package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import enums.EnumLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder

class Migration2212 {
    /*
    * Migration 2212 Details
    * - Add PrimaryHostSSL, SecondaryHostSSL (MerchantConfiguration)
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
        "SkipTxnValidation TEXT",
        "IsTpaAccount TEXT",
        "TpaMerchantLogoUrl TEXT",
        "ScMid TEXT",
        "ScTid TEXT",
        "PrimaryHostSSL TEXT",
        "SecondaryHostSSL TEXT",
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2212 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var merchantConfigExist = true

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.MERCHANT_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            merchantConfigExist = false
            createTables(DatabaseTables.MERCHANT_CONFIGURATION.id, merchantConfigurationField)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        if(merchantConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> PrimaryHostSSL")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "PrimaryHostSSL")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "PrimaryHostSSL Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> SecondaryHostSSL")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "SecondaryHostSSL")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "SecondaryHostSSL Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
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