package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration2009 {
    /*
	* Migration 2009 Details
	* - Add Marketing, Advertisement, AcquirerLogo, Action1, Action2, Action3, Action4 (Merchant Configuration)
	* - Add UnionPay QR in Table qrPayTable and printReceiptQr
	* - Add ForceSettlementDaily (Terminal Configuration)
	* - Add BATCHNO_PREAUTH, QrRefId and QrType in Receipt Upload Table
	* - Add TransactionQr Table
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
    )

    private val qrPayTableField = listOf(
        "id INTEGER NOT NULL UNIQUE",
        "postingDt VARCHAR(50) NOT NULL",
        "txnType VARCHAR(30)",
        "payBrand VARCHAR(20)",
        "txnDt VARCHAR(50)",
        "seqNo VARCHAR(20)",
        "mid VARCHAR(20)",
        "tid VARCHAR(10)",
        "txnAmt VARCHAR(50)",
        "refId VARCHAR(50)",
        "hostRefNo VARCHAR(50)",
        "txnRefNo VARCHAR(50)",
        "status VARCHAR(3) NOT NULL",
        "addInfo VARCHAR(100)",
        "acqCode TEXT",
        "txnApprCode TEXT",
        "type TEXT",
        "productCode TEXT",
        "productName TEXT",
        "isUnionPayTxn TEXT",
        "upiVoucherCode TEXT",
        "upiDiscountAmt TEXT",
        "upiMarkupFee TEXT",
        "PRIMARY KEY (id AUTOINCREMENT)",
    )

    private val printReceiptQrTableField = listOf(
        "id INTEGER NOT NULL UNIQUE",
        "postingDt VARCHAR(50) NOT NULL",
        "txnType VARCHAR(30)",
        "payBrand VARCHAR(20)",
        "txnDt VARCHAR(50)",
        "seqNo VARCHAR(20)",
        "mid VARCHAR(20)",
        "tid VARCHAR(10)",
        "txnAmt VARCHAR(50)",
        "refId VARCHAR(50)",
        "hostRefNo VARCHAR(50)",
        "txnRefNo VARCHAR(50)",
        "status VARCHAR(3) NOT NULL",
        "addInfo VARCHAR(100)",
        "acqCode TEXT",
        "txnApprCode TEXT",
        "type TEXT",
        "productCode TEXT",
        "productName TEXT",
        "isUnionPayTxn TEXT",
        "upiVoucherCode TEXT",
        "upiDiscountAmt TEXT",
        "upiMarkupFee TEXT",
        "PRIMARY KEY(id AUTOINCREMENT)"
    )

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
        "EPP_DETAIL TEXT",
        "CARD_LABEL TEXT",
        "BATCHNO_PREAUTH TEXT",
        "QrRefId TEXT",
        "QrType TEXT",
        "CreationDate TEXT",
        "LastUpdateDt TEXT",
        "IsProcessing TEXT",
        "IsSend TEXT"
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
        "FORCE_SETTLEMENT_DAILY TEXT"
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
        "PRIMARY KEY(id AUTOINCREMENT)"
    )

    fun startMigration() {
        sbLog = HelperLog.init("----- Start Running Migration 2009 -----")
        val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
        var merchantConfigExist = true
        var qrPayTableExist = true
        var printReceiptQrTableExist = true
        var receiptUploadExist = true
        var terminalConfigExist = true
        /* Check Table Existence */
        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.MERCHANT_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            merchantConfigExist = false
            createTables(DatabaseTables.MERCHANT_CONFIGURATION.id, merchantConfigurationField)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.QR_PAY_TABLE.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.QR_PAY_TABLE)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            qrPayTableExist = false
            createTables(DatabaseTables.QR_PAY_TABLE.id, qrPayTableField)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.PRINT_RECEIPT_QR.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.PRINT_RECEIPT_QR)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            printReceiptQrTableExist = false
            createTables(DatabaseTables.PRINT_RECEIPT_QR.id, printReceiptQrTableField)
        }

        HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
        if (!dbHandler.checkTableExist(DatabaseTables.RECEIPT_UPLOAD)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            receiptUploadExist = false
            createTables(DatabaseTables.RECEIPT_UPLOAD.id, receiptUploadField)
        }

        if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            terminalConfigExist = false
            createTables(DatabaseTables.TERMINAL_CONFIGURATION.id, terminalConfigurationField)
        }

        if (!dbHandler.checkTableExist(DatabaseTables.TRANSACTION_QR)) {
            HelperLog.appendLine(sbLog, "Table Not Exist")
            createTables(DatabaseTables.TRANSACTION_QR.id, transactionQrField)
        }
        /* Check Table Existence */

        /*  Table Exist [Start]*/
        if(merchantConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> Marketing")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "Marketing")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "Marketing Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> Advertisement")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "Advertisement")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "Advertisement Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> AcquirerLogo")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "AcquirerLogo")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "AcquirerLogo Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> Action1")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "Action1")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "Action1 Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> Action2")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "Action2")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "Action2 Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> Action3")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "Action3")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "Action3 Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.MERCHANT_CONFIGURATION.id} -> Action4")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.MERCHANT_CONFIGURATION, "Action4")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "Action4 Alter to -> ${DatabaseTables.MERCHANT_CONFIGURATION.id}")
            }
        }

        if (qrPayTableExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.QR_PAY_TABLE.id} -> isUnionPayTxn")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.QR_PAY_TABLE, "isUnionPayTxn")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.QR_PAY_TABLE.id}")
            }
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.QR_PAY_TABLE.id} -> upiVoucherCode")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.QR_PAY_TABLE, "upiVoucherCode")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.QR_PAY_TABLE.id}")
            }
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.QR_PAY_TABLE.id} -> upiDiscountAmt")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.QR_PAY_TABLE, "upiDiscountAmt")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.QR_PAY_TABLE.id}")
            }
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.QR_PAY_TABLE.id} -> upiMarkupFee")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.QR_PAY_TABLE, "upiMarkupFee")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.QR_PAY_TABLE.id}")
            }
        }

        if (printReceiptQrTableExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.PRINT_RECEIPT_QR.id} -> isUnionPayTxn")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.PRINT_RECEIPT_QR, "isUnionPayTxn")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.PRINT_RECEIPT_QR.id}")
            }
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.PRINT_RECEIPT_QR.id} -> upiVoucherCode")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.PRINT_RECEIPT_QR, "upiVoucherCode")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.PRINT_RECEIPT_QR.id}")
            }
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.PRINT_RECEIPT_QR.id} -> upiDiscountAmt")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.PRINT_RECEIPT_QR, "upiDiscountAmt")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.PRINT_RECEIPT_QR.id}")
            }
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.PRINT_RECEIPT_QR.id} -> upiMarkupFee")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.PRINT_RECEIPT_QR, "upiMarkupFee")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.PRINT_RECEIPT_QR.id}")
            }
        }

        if (receiptUploadExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> BATCHNO_PREAUTH")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "BATCHNO_PREAUTH")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> QrRefId")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "QrRefId")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "QrRefId Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
            }

            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> QrType")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "QrType")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "QrType Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
            }
        }

        if(terminalConfigExist) {
            HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> FORCE_SETTLEMENT_DAILY")
            if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "FORCE_SETTLEMENT_DAILY")) {
                HelperLog.appendLine(sbLog, "Column Not Exist")
                HelperLog.appendLine(sbLog, "FORCE_SETTLEMENT_DAILY Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
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