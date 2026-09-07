package com.sc.mf919.kotlin.migration

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelBatchTable
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration2204 {
    /*
   * Migration 2204 Details
   * - Add TpaMerchantLogoUrl, IsTpaAccount, ScMid, ScTid (Merchant Configuration)
   * - Add SALES_CARD, SALES_EWALLET (Terminal Configuration)
   * - Add isTpaAccount (Product List)
   * - Add isTpaAccount (Print Receipt)
   * - Add isTpaAccount (Last Settlement)
   * - Add isTpaAccount (Transaction QR)
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
        "SALES_EWALLET TEXT"
    )

    private val productListField = listOf(
        "Id INTEGER NOT NULL UNIQUE",
        "Product TEXT",
        "AcqCode TEXT",
        "AcqMid TEXT",
        "AcqTid TEXT",
        "QrProductCode TEXT",
        "ProductName TEXT",
        "EppProductCode TEXT",
        "EppTenure TEXT",
        "EppTenureCode TEXT",
        "IsSettlement TEXT",
        "IsSettled TEXT",
        "BatchNo TEXT",
        "IsActive TEXT",
        "Ksn TEXT",
        "PinKsn TEXT",
        "IsTpaAccount TEXT",
        "PRIMARY KEY(Id AUTOINCREMENT)"
    )

    private val printReceiptField = listOf(
        "id INTEGER NOT NULL UNIQUE",
        "postingDt VARCHAR(30) NOT NULL",
        "txnDt VARCHAR(30) NOT NULL",
        "txnType VARCHAR(20) NOT NULL",
        "cardMasked VARCHAR(30)",
        "schemeId VARCHAR(5)",
        "txnAmt VARCHAR(15)",
        "invoiceNo VARCHAR(8)",
        "stan VARCHAR(8)",
        "txnApprCode VARCHAR(8)",
        "receiptInfo VARCHAR(1000)",
        "isTpaAccount TEXT",
        "PRIMARY KEY(id AUTOINCREMENT)"
    )

    private val lastSettlementField = listOf(
        "mid TEXT",
        "tid TEXT",
        "settleInfo TEXT",
        "isTpaAccount TEXT"
    )

    private val transactionQrField = listOf(
        "id INTEGER NOT NULL UNIQUE",
        "txnType TEXT",
        "txnDateTime TEXT",
        "voidDateTime TEXT",
        "txnAmount TEXT",
        "payChannel TEXT",
        "payBrand TEXT",
        "productCode TEXT",
        "productName TEXT",
        "mid TEXT",
        "tid TEXT",
        "acqCode TEXT",
        "refId TEXT",
        "hostRefNo TEXT",
        "txnRefNo TEXT",
        "approvalCode TEXT",
        "respCode TEXT",
        "respDesc TEXT",
        "isUnionPayTxn TEXT",
        "upiVoucherCode TEXT",
        "upiDiscountAmt TEXT",
        "upiMarkupFee TEXT",
        "isTpaAccount TEXT",
        "PRIMARY KEY(id AUTOINCREMENT)"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2204 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var merchantConfigExist = true
        var terminalConfigExist = true
        var productListExist = true
        var printReceiptExist = true
        var lastSettlementExist = true
        var transactionQrExist = true

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

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.PRODUCT_LIST.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.PRODUCT_LIST)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            productListExist = false
            createTables(DatabaseTables.PRODUCT_LIST.id, productListField)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.PRINT_RECEIPT.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.PRINT_RECEIPT)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            printReceiptExist = false
            createTables(DatabaseTables.PRINT_RECEIPT.id, printReceiptField)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.LAST_SETTLEMENT.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.LAST_SETTLEMENT)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            lastSettlementExist = false
            createTables(DatabaseTables.LAST_SETTLEMENT.id, lastSettlementField)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.TRANSACTION_QR.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.TRANSACTION_QR)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            transactionQrExist = false
            createTables(DatabaseTables.TRANSACTION_QR.id, transactionQrField)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        if(merchantConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> IsTpaAccount")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "IsTpaAccount")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "IsTpaAccount Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> TpaMerchantLogoUrl")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "TpaMerchantLogoUrl")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "TpaMerchantLogoUrl Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> ScMid")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "ScMid")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "ScMid Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> ScTid")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "ScTid")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "ScTid Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }
        }

        if(terminalConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> SALES_CARD")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "SALES_CARD")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "SALES_CARD Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> SALES_EWALLET")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "SALES_EWALLET")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "SALES_EWALLET Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
            }
        }

        if(productListExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.PRODUCT_LIST.id} -> IsTpaAccount")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.PRODUCT_LIST, "IsTpaAccount")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "IsTpaAccount Alter to -> ${DatabaseTables.PRODUCT_LIST.id}")
            }
            val customUpdateSql = "UPDATE ${DatabaseTables.PRODUCT_LIST.id} SET isTpaAccount = 'false' WHERE isTpaAccount IS NULL"
            dbHandler.runningCustomSql(customUpdateSql, arrayOf())
        }

        if(printReceiptExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.PRINT_RECEIPT.id} -> isTpaAccount")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.PRINT_RECEIPT, "isTpaAccount")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "isTpaAccount Alter to -> ${DatabaseTables.PRINT_RECEIPT.id}")
            }
            val customUpdateSql = "UPDATE ${DatabaseTables.PRINT_RECEIPT.id} SET isTpaAccount = 'false' WHERE isTpaAccount IS NULL"
            dbHandler.runningCustomSql(customUpdateSql, arrayOf())
        }

        if(lastSettlementExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.LAST_SETTLEMENT.id} -> isTpaAccount")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.LAST_SETTLEMENT, "isTpaAccount")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "isTpaAccount Alter to -> ${DatabaseTables.LAST_SETTLEMENT.id}")
            }
            val customUpdateSql = "UPDATE ${DatabaseTables.LAST_SETTLEMENT.id} SET isTpaAccount = 'false' WHERE isTpaAccount IS NULL"
            dbHandler.runningCustomSql(customUpdateSql, arrayOf())
        }

        if(transactionQrExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TRANSACTION_QR.id} -> isTpaAccount")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TRANSACTION_QR, "isTpaAccount")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "isTpaAccount Alter to -> ${DatabaseTables.TRANSACTION_QR.id}")
            }
            val customUpdateSql = "UPDATE ${DatabaseTables.TRANSACTION_QR.id} SET isTpaAccount = 'false' WHERE isTpaAccount IS NULL"
            dbHandler.runningCustomSql(customUpdateSql, arrayOf())
        }

        // BatchTable Checking of SchemeTag for Revamp
        val batchTableType = object : TypeToken<List<DbModelBatchTable>>() {}.type
        val allBatchTable = dbHandler.selectListData<DbModelBatchTable>(batchTableType,"SELECT * FROM ${DatabaseTables.BATCH_TABLE.id} WHERE schemeTag = ?", arrayOf("mccs"))
        allBatchTable.forEach { forData ->
            println("forData : ${Gson().toJson(forData)}")
            val criteriaHM = HashMap<Any, Any>()
            criteriaHM["id"] = forData.id
            criteriaHM["schemeTag"] = "mccs"

            val valueHM = HashMap<Any, Any>()
            valueHM["schemeTag"] = "visam"
            dbHandler.updateTableValue(DatabaseTables.BATCH_TABLE, valueHM, criteriaHM)
        }
        // BatchTable Checking of SchemeTag for Revamp



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