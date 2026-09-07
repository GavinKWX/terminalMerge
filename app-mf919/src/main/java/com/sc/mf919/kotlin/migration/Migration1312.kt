package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration1312 {
	/*
	* Migration 1.3.12 Details
	* - Add TimeoutSeconds column into TerminalConfiguration
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
		sbLog = HelperLog.init("----- Start Running Migration 1312 -----")
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

		/* Step 1 return if Table Not Exist */
		HelperLog.appendLine(sbLog, "Check Table Exist -> TerminalConfiguration")
		if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTerminalConfigTable(DatabaseTables.TERMINAL_CONFIGURATION.id, false)
		}
		/* Step 1 return if Table Not Exist */

		/*  Table Exist */
		HelperLog.appendLine(sbLog, "Check Column in (TerminalConfiguration) -> TIMEOUT_SECONDS")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "TIMEOUT_SECONDS")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			alterNewTerminalConfigurationTables(dbHandler);
		}

		HelperLog.appendLine(sbLog, "Finish Running Migration 1312")
		HelperLog.logToFile(sbLog, HelperLogFileName.TerminaLog)
	}

	private fun renameTempToOriTableName(dbHandler: DbHandler, tableName: String) {
		HelperLog.appendLine(sbLog, "Rename ${tableName}_temp to -> ", tableName)
		val query = "ALTER TABLE " + tableName + "_temp RENAME TO " + tableName
		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun renameExistingTableToBackup(dbHandler: DbHandler, tableName: String) {
		HelperLog.appendLine(sbLog, "Backup Table for -> ", tableName)
		val query = "ALTER TABLE " + tableName + " RENAME TO " + tableName + "_BKV1312"
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
				"TIMEOUT_SECONDS TEXT" +
				")";

		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun alterNewTerminalConfigurationTables(dbHandler: DbHandler) {
		createTerminalConfigTable(DatabaseTables.TERMINAL_CONFIGURATION.id, true)
		val tableCol = "Contact, Contactless, MagStripe, ForcePin, IsoPrint, ReceiptPrint, Sale, Void, PreAuth, SaleCom, SaleComOnline, Refund, OptIn, TmsReceipt, TmsEnable, AutoSettle, QrPay, PoweredBy, PoweredByBW, HomeLogo, MC_VER, DEV_PROJECT, DEV_LOCATION, DEV_LANE_ID, VOID_WITH_PIN, MOTO";
		if (!copyDataFromExisting(dbHandler, DatabaseTables.TERMINAL_CONFIGURATION.id, tableCol)) {
			HelperLog.appendLine(sbLog, "No Data to be Copy Drop temp Table")
			return dropTempTable(dbHandler, DatabaseTables.TERMINAL_CONFIGURATION.id)
		}

		renameExistingTableToBackup(
			dbHandler,
			DatabaseTables.TERMINAL_CONFIGURATION.id
		) //  TerminalConfiguration > TerminalConfiguration_BKV1312
		renameTempToOriTableName(
			dbHandler,
			DatabaseTables.TERMINAL_CONFIGURATION.id
		) //  TerminalConfiguration > TerminalConfiguration
	}
}