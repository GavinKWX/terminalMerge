package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getMerchantInfo
import java.lang.StringBuilder

class Migration135 {
	/*
	* Migration 1.3.0 Details
	* - Add AcqCode column into qrPayTable, printReceiptQr
	* */
	/*
	* 1. Check table exists
	* 2. Check column exists
	* 3. Create new Temp table
	* 4. Move existing records to new temp table
	* 5. rename existing table to BK
	* 6. rename new temp table to current table name
	* */
	lateinit var sbLog: StringBuilder

	fun startMigrate135() {
		sbLog = HelperLog.init("----- Start Running Migration 135 -----")
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

		/* Step 1 return if Table Not Exist */
		HelperLog.appendLine(sbLog, "Check Table Exist -> printReceiptQr")
		if (!dbHandler.checkTableExist(DatabaseTables.PRINT_RECEIPT_QR)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createPrintReceiptQrTables(DatabaseTables.PRINT_RECEIPT_QR.id, false)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> qrPayTable")
		if (!dbHandler.checkTableExist(DatabaseTables.QR_PAY_TABLE)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createQrPayTables(DatabaseTables.QR_PAY_TABLE.id, false)
		}
		/* Step 1 return if Table Not Exist */


		/*  Table Exist */
		HelperLog.appendLine(sbLog, "Check Column in (printReceiptQr) -> acqCode")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRINT_RECEIPT_QR, "acqCode")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			alterNewPrintReceiptQrTables(dbHandler);
		}

		HelperLog.appendLine(sbLog, "Check Column in (qrPayTable) -> acqCode")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.QR_PAY_TABLE, "acqCode")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			alterNewQrPayTables(dbHandler);
		}
		HelperLog.appendLine(sbLog, "Finish Running Migration 135")
		HelperLog.logToFile(sbLog, HelperLogFileName.TerminaLog)
	}

	private fun renameTempToOriTableName(dbHandler: DbHandler, tableName: String) {
		HelperLog.appendLine(sbLog, "Rename ${tableName}_temp to -> ", tableName)
		val query = "ALTER TABLE " + tableName + "_temp RENAME TO " + tableName
		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun renameExistingTableToBackup(dbHandler: DbHandler, tableName: String) {
		HelperLog.appendLine(sbLog, "Backup Table for -> ", tableName)
		val query = "ALTER TABLE " + tableName + " RENAME TO " + tableName + "_BKV135"
		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun copyDataFromExisting(
		dbHandler: DbHandler,
		tableName: String,
		tableCol: String
	): Boolean {
		HelperLog.appendLine(sbLog, "Copy Data to Temp Table....")
		val query =
			"INSERT INTO " + tableName + "_temp (" + tableCol + ") " +
					"SELECT " + tableCol +
					" FROM " + tableName;

		val result = dbHandler.runningCustomSql(query, arrayOf())
		return result > 0
	}

	private fun dropTempTable(dbHandler: DbHandler, tableName: String) {
		HelperLog.appendLine(sbLog, "Drop Temp Table -> ", tableName)
		val query = "DROP TABLE IF EXISTS ${tableName}_temp"
		dbHandler.runningCustomSql(query, arrayOf())
	}


	private fun createQrPayTables(tableName: String, isCreateTemp: Boolean) {
		HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
		HelperLog.appendLine(sbLog, "IsTemporaryTable -> ", isCreateTemp.toString())
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
		var newTableName = tableName
		if (isCreateTemp) {
			newTableName = tableName + "_temp"
		}

		val query = "CREATE TABLE " + newTableName + " (" +
				"id INTEGER NOT NULL UNIQUE," +
				"postingDt VARCHAR(50) NOT NULL," +
				"txnType VARCHAR(30)," +
				"payBrand VARCHAR(20)," +
				"txnDt VARCHAR(50)," +
				"seqNo VARCHAR(20)," +
				"mid VARCHAR(20)," +
				"tid VARCHAR(10)," +
				"txnAmt VARCHAR(50)," +
				"refId VARCHAR(50)," +
				"hostRefNo VARCHAR(50)," +
				"txnRefNo VARCHAR(50)," +
				"status VARCHAR(3) NOT NULL," +
				"addInfo VARCHAR(100)," +
				"acqCode TEXT," +
				"PRIMARY KEY(Id AUTOINCREMENT)" +
				")";

		dbHandler.runningCustomSql(query, arrayOf())
	}


	private fun createPrintReceiptQrTables(tableName: String, isCreateTemp: Boolean) {
		HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
		HelperLog.appendLine(sbLog, "IsTemporaryTable -> ", isCreateTemp.toString())
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
		var newTableName = tableName
		if (isCreateTemp) {
			newTableName = tableName + "_temp"
		}

		val query = "CREATE TABLE " + newTableName + " (" +
				"id INTEGER NOT NULL UNIQUE," +
				"postingDt TEXT," +
				"txnType TEXT," +
				"payBrand TEXT," +
				"txnDt TEXT," +
				"mid TEXT," +
				"tid TEXT," +
				"txnAmt TEXT," +
				"refId TEXT," +
				"hostRefNo TEXT," +
				"txnRefNo TEXT," +
				"respCode TEXT," +
				"status TEXT," +
				"addInfo TEXT," +
				"printInfo TEXT," +
				"acqCode TEXT," +
				"PRIMARY KEY(Id AUTOINCREMENT)" +
				")";

		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun alterNewPrintReceiptQrTables(dbHandler: DbHandler) {
		createPrintReceiptQrTables(DatabaseTables.PRINT_RECEIPT_QR.id, true)
		val tableCol = "id, postingDt, txnType, payBrand, txnDt, mid, tid, txnAmt, refId, hostRefNo, txnRefNo, respCode, status, addInfo, printInfo";
		if (!copyDataFromExisting(dbHandler, DatabaseTables.PRINT_RECEIPT_QR.id, tableCol)) {
			HelperLog.appendLine(sbLog, "No Data to be Copy Drop temp Table")
			return dropTempTable(dbHandler, DatabaseTables.PRINT_RECEIPT_QR.id)
		}

		renameExistingTableToBackup(
			dbHandler,
			DatabaseTables.PRINT_RECEIPT_QR.id
		) //  PrintReceiptQr > PrintReceiptQr_BKV135
		renameTempToOriTableName(
			dbHandler,
			DatabaseTables.PRINT_RECEIPT_QR.id
		) //  PrintReceiptQr > PrintReceiptQr
	}

	private fun alterNewQrPayTables(dbHandler: DbHandler) {
		createQrPayTables(DatabaseTables.QR_PAY_TABLE.id, true)
		val tableCol = "id, postingDt, txnType, payBrand, txnDt, seqNo, mid, tid, txnAmt, refId, hostRefNo, txnRefNo, status, addInfo";
		if (!copyDataFromExisting(dbHandler, DatabaseTables.QR_PAY_TABLE.id, tableCol)) {
			HelperLog.appendLine(sbLog, "No Data to be Copy Drop temp Table")
			return dropTempTable(dbHandler, DatabaseTables.QR_PAY_TABLE.id)
		}

		renameExistingTableToBackup(
			dbHandler,
			DatabaseTables.QR_PAY_TABLE.id
		) //  QrPayTable > QrPayTable_BKV135
		renameTempToOriTableName(
			dbHandler,
			DatabaseTables.QR_PAY_TABLE.id
		) //  QrPayTable > QrPayTable
	}
}