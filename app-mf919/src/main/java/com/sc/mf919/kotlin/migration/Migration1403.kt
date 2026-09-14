package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import enums.EnumLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration1403 {
	/*
	* Migration 1403 Details
	* - Add REMOTE_DOWNLOAD_BSN_KEY
	* - Move KSN, PIN_KSN for BSN from isoBatchInfo into productList
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
		"REMOTE_DOWNLOAD_BSN_KEY TEXT"
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
		"PRIMARY KEY(Id AUTOINCREMENT)"
	)

	fun startMigration() {
		sbLog = HelperLog.init("----- Start Running Migration 1403 -----")
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

		/* Check Table Existence */
		HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
		if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.TERMINAL_CONFIGURATION.id, terminalConfigurationField)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> ${DatabaseTables.PRODUCT_LIST.id}")
		if (!dbHandler.checkTableExist(DatabaseTables.PRODUCT_LIST)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.PRODUCT_LIST.id, productListField)
		}
		/* Check Table Existence */

		/*  Table Exist [Start]*/
		HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.TERMINAL_CONFIGURATION.id} -> REMOTE_DOWNLOAD_BSN_KEY")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "REMOTE_DOWNLOAD_BSN_KEY")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(sbLog, "REMOTE_DOWNLOAD_BSN_KEY Alter to -> ${DatabaseTables.TERMINAL_CONFIGURATION.id}")
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.PRODUCT_LIST.id}) -> Ksn")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRODUCT_LIST, "Ksn")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "Ksn Alter to -> ${DatabaseTables.PRODUCT_LIST.id}"
			)
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.PRODUCT_LIST.id}) -> PinKsn")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRODUCT_LIST, "PinKsn")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "PinKsn Alter to -> ${DatabaseTables.PRODUCT_LIST.id}"
			)
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