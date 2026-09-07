package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration1404 {
	/*
	* Migration 1404 Details
	* */

	lateinit var sbLog: StringBuilder
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
		"EWALLET_PRODUCT_LIST TEXT"
	)

	fun startMigration() {
		sbLog = HelperLog.init("----- Start Running Migration 1404 -----")
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

		/* Check Table Existence */
		HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.QR_PAY_TABLE.id}")
		if (!dbHandler.checkTableExist(DatabaseTables.QR_PAY_TABLE)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.QR_PAY_TABLE.id, qrPayTableField)
		}

		HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.PRINT_RECEIPT_QR.id}")
		if (!dbHandler.checkTableExist(DatabaseTables.PRINT_RECEIPT_QR)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.PRINT_RECEIPT_QR.id, printReceiptQrTableField)
		}

		HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
		if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.TERMINAL_CONFIGURATION.id, terminalConfigurationField)
		}
		/* Check Table Existence */

		/*  Table Exist */
		HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.QR_PAY_TABLE.id} -> type")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.QR_PAY_TABLE, "type")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.QR_PAY_TABLE.id}")
		}

		HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.QR_PAY_TABLE.id} -> productCode")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.QR_PAY_TABLE, "productCode")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(sbLog, "productCode Alter to -> ${DatabaseTables.QR_PAY_TABLE.id}")
		}

		HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.QR_PAY_TABLE.id} -> productName")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.QR_PAY_TABLE, "productName")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(sbLog, "productName Alter to -> ${DatabaseTables.QR_PAY_TABLE.id}")
		}


		HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.PRINT_RECEIPT_QR.id} -> type")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRINT_RECEIPT_QR, "type")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(sbLog, "type Alter to -> ${DatabaseTables.PRINT_RECEIPT_QR.id}")
		}

		HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.PRINT_RECEIPT_QR.id} -> productCode")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRINT_RECEIPT_QR, "productCode")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(sbLog, "productCode Alter to -> ${DatabaseTables.PRINT_RECEIPT_QR.id}")
		}

		HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.PRINT_RECEIPT_QR.id} -> productName")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRINT_RECEIPT_QR, "productName")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(sbLog, "productName Alter to -> ${DatabaseTables.PRINT_RECEIPT_QR.id}")
		}

		HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> EWALLET_PRODUCT_LIST")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "EWALLET_PRODUCT_LIST")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(sbLog, "EWALLET_PRODUCT_LIST Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
		}

		HelperLog.appendLine(sbLog, "Migration for enhancement in KSN and PinKSN")
		val strKsn = IsoBatchInfoRepo.getBatchInfo(ServiceHolder.getContext(), "ksn", "visam")?.value ?: ""
		val strPinKsn = IsoBatchInfoRepo.getBatchInfo(ServiceHolder.getContext(), "ksn", "visam-pin")?.value ?: ""

		val criteriaHM = hashMapOf<Any, Any>(
			"AcqCode" to "BSN",
		)
		val valueHM = hashMapOf<Any, Any>(
			"Ksn" to strKsn,
			"PinKsn" to strPinKsn
		)
		ProductListRepo.updateData(ServiceHolder.getContext(), valueHM, criteriaHM)

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