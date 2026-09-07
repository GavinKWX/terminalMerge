package com.sc.mf919.kotlin.migration

import com.google.gson.reflect.TypeToken
import com.library.terminal.Utility
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelIsoBatchInfo
import com.sc.mf919.kotlin.database.model.DbModelPrintReceipt
import com.sc.mf919.kotlin.database.model.DbModelSettlementSummary
import com.sc.mf919.kotlin.database.model.DbModelSettlementSummaryDistinct
import com.sc.mf919.kotlin.database.repo.SettlementSummaryRepo
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration2200 {
    /*
    * Migration 2200 Details
    * - Add revBatchTable to resend pending reversal before proceed settlement
    * - Add ISO_WEBSOCKET, CASHOUT in (Terminal Configuration)
    * */

    lateinit var sbLog: StringBuilder
    private val revBatchTableField = listOf(
        "id INTEGER NOT NULL UNIQUE",
        "postingDt TEXT",
        "txnType TEXT",
        "stan TEXT",
        "invNo TEXT",
        "batchData TEXT",
        "schemeTag TEXT",
        "schemeId TEXT",
        "refId TEXT",
        "status TEXT",
        "batchNo TEXT",
        "mid TEXT",
        "tid TEXT",
        "PRIMARY KEY(id AUTOINCREMENT)"
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
        "ISO_WEBSOCKET TEXT"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2200 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var terminalConfigExist = true

        //TODO MCCS Revamp
        val valueBsnContact = mutableMapOf<Any,Any>(
            "value" to "0261"
        )
        val criteriaBsnContact = mutableMapOf<Any,Any>(
            "tag" to "posEntryMode",
            "subtag" to "visam-98"
        )
        HelperLog.appendLine(sbLog, "Try run update to prevent multiple insert -> $criteriaBsnContact  -> value (0261)")
        val bsnContactResult = dbHandler.updateTableValue(DatabaseTables.ISO_BATCH_INFO, valueBsnContact, criteriaBsnContact).toString().toIntOrNull() ?: 0
        HelperLog.appendLine(sbLog, "Update Result -> $bsnContactResult")
        if(bsnContactResult <= 0) {
            HelperLog.appendLine(sbLog, "No Previous Data, Inserting New to ${DatabaseTables.ISO_BATCH_INFO}")
            val isoBatchInfoModel = DbModelIsoBatchInfo("posEntryMode", "visam-98", "0261")
            dbHandler.insertToDb(DatabaseTables.ISO_BATCH_INFO, isoBatchInfoModel)
        }

        val valueBsnContactless = mutableMapOf<Any,Any>(
            "value" to "0271"
        )
        val criteriaBsnContactless = mutableMapOf<Any,Any>(
            "tag" to "posEntryMode",
            "subtag" to "visam-81"
        )
        HelperLog.appendLine(sbLog, "Try run update to prevent multiple insert -> $criteriaBsnContactless  -> value (0271)")
        val bsnContactlessResult = dbHandler.updateTableValue(DatabaseTables.ISO_BATCH_INFO, valueBsnContactless, criteriaBsnContactless).toString().toIntOrNull() ?: 0
        HelperLog.appendLine(sbLog, "Update Result -> $bsnContactlessResult")
        if(bsnContactlessResult <= 0) {
            HelperLog.appendLine(sbLog, "No Previous Data, Inserting New to ${DatabaseTables.ISO_BATCH_INFO}")
            val isoBatchInfoModel = DbModelIsoBatchInfo("posEntryMode", "visam-81", "0271")
            dbHandler.insertToDb(DatabaseTables.ISO_BATCH_INFO, isoBatchInfoModel)
        }

        val valueBsnCzContact = mutableMapOf<Any,Any>(
            "value" to "0051"
        )
        val criteriaBsnCzContact = mutableMapOf<Any,Any>(
            "tag" to "posEntryMode",
            "subtag" to "visam-99"
        )
        HelperLog.appendLine(sbLog, "Try run update to prevent multiple insert -> $criteriaBsnCzContact  -> value (0051)")
        val bsnCzContactResult = dbHandler.updateTableValue(DatabaseTables.ISO_BATCH_INFO, valueBsnCzContact, criteriaBsnCzContact).toString().toIntOrNull() ?: 0
        HelperLog.appendLine(sbLog, "Update Result -> $bsnCzContactResult")
        if(bsnCzContactResult <= 0) {
            HelperLog.appendLine(sbLog, "No Previous Data, Inserting New to ${DatabaseTables.ISO_BATCH_INFO}")
            val isoBatchInfoModel = DbModelIsoBatchInfo("posEntryMode", "visam-99", "0051")
            dbHandler.insertToDb(DatabaseTables.ISO_BATCH_INFO, isoBatchInfoModel)
        }
        //TODO MCCS Revamp

        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.REVERSAL_BATCH_TABLE.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.REVERSAL_BATCH_TABLE)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            createTables(DatabaseTables.REVERSAL_BATCH_TABLE.id, revBatchTableField)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            terminalConfigExist = false
            createTables(DatabaseTables.TERMINAL_CONFIGURATION.id, terminalConfigurationField)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        if(terminalConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> ISO_WEBSOCKET")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "ISO_WEBSOCKET")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "ISO_WEBSOCKET Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> CASHOUT")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "CASHOUT")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "CASHOUT Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
            }
        }
        /*  Table Exist [End]*/

        /* Check Cashout Total in Settlement Summary [Start] */
        val type = object : TypeToken<List<DbModelSettlementSummaryDistinct>>() {}.type
        val distinctSettlementList = dbHandler.selectListData<DbModelSettlementSummaryDistinct>(type,"SELECT DISTINCT acq_code, mid, tid, is_settle FROM ${DatabaseTables.SETTLEMENT_SUMMARY.id}", arrayOf())
        distinctSettlementList.forEach { distinctProduct ->
            val sqlString = "SELECT count(*) FROM ${DatabaseTables.SETTLEMENT_SUMMARY.id} WHERE acq_code = ? AND mid = ? AND tid = ? AND tag = ? "
            val countScalar = dbHandler.executeScalar(sqlString, arrayOf(distinctProduct.acq_code, distinctProduct.mid, distinctProduct.tid, "cashOutTotal"))
            if(countScalar == 0){
                val subtagList = listOf("visam-visa", "visam-master", "visam-upi", "visam-mccs")
                subtagList.forEach{
                    val newModelSummary = DbModelSettlementSummary(distinctProduct.acq_code, distinctProduct.mid, distinctProduct.tid, "cashOutTotal", it, "0", distinctProduct.is_settle)
                    dbHandler.insertToDb(DatabaseTables.SETTLEMENT_SUMMARY, newModelSummary)
                }
            }
        }
        /* Check Cashout Total in Settlement Summary [End] */

        /* Modify Print Receipt Stan and Approval Code [Start]  */
        val printReceiptType = object : TypeToken<List<DbModelPrintReceipt>>() {}.type
		val allPrintReceipt = dbHandler.selectListData<DbModelPrintReceipt>(printReceiptType,"SELECT * FROM ${DatabaseTables.PRINT_RECEIPT.id}", arrayOf())
		allPrintReceipt.forEach { receiptProduct ->
            val receiptString = receiptProduct.receiptInfo.replace("[", "").replace("]", "").replace(", ", "\n")
            var detailArray = Utils.String2ArrayString(receiptString)
            val detailArrayMutable = detailArray.toMutableList()
            detailArrayMutable.add(12, "0")
            detailArray = detailArrayMutable.toTypedArray()

            val criteriaHM = HashMap<Any, Any>()
			criteriaHM["id"] = receiptProduct.id.toString()

			val valueHM = HashMap<Any, Any>()
			valueHM["invoiceNo"] = Utility.HexString2ASCII(receiptProduct.invoiceNo)
            valueHM["txnApprCode"] = Utility.HexString2ASCII(receiptProduct.txnApprCode)
            valueHM["receiptInfo"] = detailArray.contentToString()
			dbHandler.updateTableValue(DatabaseTables.PRINT_RECEIPT, valueHM, criteriaHM)
		}
        /* Modify Print Receipt Stan and Approval Code [End] */

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