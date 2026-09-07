package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelIsoBatchInfo
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration1313 {
	/*
		* Migration 1.3.13 Details
		* - Add New Table (bnplPayTable)
		* - Add New Table (printReceiptBnpl)
		* - Add SETTLEMENT_WITH_PIN column into TerminalConfiguration
		*
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

	fun startMigration(){
		sbLog = HelperLog.init("----- Start Running Migration 1313 -----")
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

		/* Step 1 return if Table Not Exist */
		HelperLog.appendLine(sbLog, "Check Table Exist -> TerminalConfiguration")
		if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTerminalConfigTable(DatabaseTables.TERMINAL_CONFIGURATION.id, false)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> bnplPayTable")
		if (!dbHandler.checkTableExist(DatabaseTables.BNPL_PAY_TABLE)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createBnplPayTables(DatabaseTables.BNPL_PAY_TABLE.id, false)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> printReceiptBnpl")
		if (!dbHandler.checkTableExist(DatabaseTables.PRINT_RECEIPT_BNPL)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createPrintReceiptBnplTables(DatabaseTables.PRINT_RECEIPT_BNPL.id, false)
		}
		/* Step 1 return if Table Not Exist */

		/*  Table Exist */
		HelperLog.appendLine(sbLog, "Check Column in (TerminalConfiguration) -> SETTLEMENT_WITH_PIN")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "SETTLEMENT_WITH_PIN")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			alterNewTerminalConfigurationTables(dbHandler);
		}

		HelperLog.appendLine(sbLog, "Finish Running Migration 1313")
		HelperLog.logToFile(sbLog, HelperLogFileName.TerminaLog)
	}

	private fun createBnplPayTables(tableName: String, isCreateTemp: Boolean) {
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
				"payBrandDesc VARCHAR(50)," +
				"txnDt VARCHAR(50)," +
				"seqNo VARCHAR(20)," +
				"mid VARCHAR(20)," +
				"tid VARCHAR(10)," +
				"txnAmt VARCHAR(50)," +
				"refId VARCHAR(50)," +
				"hostRefNo VARCHAR(50)," +
				"txnRefNo VARCHAR(50)," +
				"status TEXT," +
				"addInfo VARCHAR(100)," +
				"acqCode TEXT," +
				"txnApprCode TEXT," +
				"packageCode TEXT," +
				"paymentType TEXT," +
				"tenure TEXT," +
				"tenureDesc TEXT," +
				"bnplResp TEXT," +
				"PRIMARY KEY(id AUTOINCREMENT)" +
				")"

		dbHandler.runningCustomSql(query, arrayOf())
	}


	private fun createPrintReceiptBnplTables(tableName: String, isCreateTemp: Boolean) {
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
				"payBrandDesc TEXT," +
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
				"txnApprCode TEXT," +
				"tenure TEXT," +
				"tenureDesc TEXT," +
				"bnplResp TEXT," +
				"PRIMARY KEY(id AUTOINCREMENT)" +
				")";

		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun renameTempToOriTableName(dbHandler: DbHandler, tableName: String) {
		HelperLog.appendLine(sbLog, "Rename ${tableName}_temp to -> ", tableName)
		val query = "ALTER TABLE " + tableName + "_temp RENAME TO " + tableName
		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun renameExistingTableToBackup(dbHandler: DbHandler, tableName: String) {
		HelperLog.appendLine(sbLog, "Backup Table for -> ", tableName)
		val query = "ALTER TABLE " + tableName + " RENAME TO " + tableName + "_BKV1313"
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

	private fun createTerminalConfigTable(tableName: String, isCreateTemp: Boolean) {
		HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
		HelperLog.appendLine(sbLog, "IsTemporaryTable -> ", isCreateTemp.toString())
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
		var newTableName = tableName
		if (isCreateTemp) {
			newTableName = tableName + "_temp"
		}

		val query = "CREATE TABLE " + newTableName + " (" +
				"Contact TEXT," +
				"Contactless TEXT," +
				"MagStripe TEXT," +
				"ForcePin TEXT," +
				"IsoPrint TEXT," +
				"ReceiptPrint TEXT," +
				"Sale TEXT," +
				"Void TEXT," +
				"PreAuth TEXT," +
				"SaleCom TEXT," +
				"SaleComOnline TEXT," +
				"Refund TEXT," +
				"OptIn TEXT," +
				"TmsReceipt TEXT," +
				"TmsEnable TEXT," +
				"AutoSettle TEXT," +
				"QrPay TEXT," +
				"PoweredBy TEXT," +
				"PoweredByBW TEXT," +
				"HomeLogo TEXT," +
				"MC_VER TEXT," +
				"DEV_PROJECT TEXT," +
				"DEV_LOCATION TEXT," +
				"DEV_LANE_ID TEXT," +
				"VOID_WITH_PIN TEXT," +
				"MOTO TEXT," +
				"TIMEOUT_SECONDS TEXT," +
				"SETTLEMENT_WITH_PIN TEXT" +
				")";

		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun alterNewTerminalConfigurationTables(dbHandler: DbHandler) {
		createTerminalConfigTable(DatabaseTables.TERMINAL_CONFIGURATION.id, true)
		val tableCol = "Contact, Contactless, MagStripe, ForcePin, IsoPrint, ReceiptPrint, Sale, Void, PreAuth, SaleCom, SaleComOnline, Refund, OptIn, TmsReceipt, TmsEnable, AutoSettle, QrPay, PoweredBy, PoweredByBW, HomeLogo, MC_VER, DEV_PROJECT, DEV_LOCATION, DEV_LANE_ID, VOID_WITH_PIN, MOTO, TIMEOUT_SECONDS";
		if (!copyDataFromExisting(dbHandler, DatabaseTables.TERMINAL_CONFIGURATION.id, tableCol)) {
			HelperLog.appendLine(sbLog, "No Data to be Copy Drop temp Table")
			return dropTempTable(dbHandler, DatabaseTables.TERMINAL_CONFIGURATION.id)
		}

		renameExistingTableToBackup(
			dbHandler,
			DatabaseTables.TERMINAL_CONFIGURATION.id
		) //  TerminalConfiguration > TerminalConfiguration_BKV1313
		renameTempToOriTableName(
			dbHandler,
			DatabaseTables.TERMINAL_CONFIGURATION.id
		) //  TerminalConfiguration > TerminalConfiguration
	}
}