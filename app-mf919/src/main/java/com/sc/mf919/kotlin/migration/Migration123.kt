package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.helper_common.Helper
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder
import java.text.SimpleDateFormat
import java.util.*

class Migration123 {
	/*
	* Migration 1.2.3 Details
	* - Add LastUpdateDt column into ReceiptUpload
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

	fun startMigrate123() {
		sbLog = HelperLog.init("----- Start Running Migration 123 -----")
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

		/* Step 1 return if Table Not Exist */
		HelperLog.appendLine(sbLog, "Check Table Exist -> MerchantConfiguration")
		if (!dbHandler.checkTableExist(DatabaseTables.MERCHANT_CONFIGURATION)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createMerchantConfigTables(DatabaseTables.MERCHANT_CONFIGURATION.id, false)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> ProductList")
		if (!dbHandler.checkTableExist(DatabaseTables.PRODUCT_LIST)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createProductListTable(DatabaseTables.PRODUCT_LIST.id, false)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> TerminalConfiguration")
		if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTerminalConfigTable(DatabaseTables.TERMINAL_CONFIGURATION.id, false)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> ReceiptUpload")
		if (!dbHandler.checkTableExist(DatabaseTables.RECEIPT_UPLOAD)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.RECEIPT_UPLOAD.id, false)
			HelperLog.appendLine(sbLog, "Finish Running Migration 123")
			HelperLog.logToFile(sbLog, HelperLogFileName.TerminaLog)
			return
		}
		/* Step 1 return if Table Not Exist */


		/*  Table Exist */
		HelperLog.appendLine(sbLog, "Check Column in (TerminalConfiguration) -> LastUpdateDt")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "LastUpdateDt")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			createTables(DatabaseTables.RECEIPT_UPLOAD.id, true)
			if (!copyDataFromExisting(dbHandler, DatabaseTables.RECEIPT_UPLOAD.id)) {
				return dropTempTable(dbHandler, DatabaseTables.RECEIPT_UPLOAD.id)
			}
			renameExistingTableToBackup(
				dbHandler,
				DatabaseTables.RECEIPT_UPLOAD.id
			) //  ReceiptUpload > ReceiptUpload_BKV123
			renameTempToOriTableName(
				dbHandler,
				DatabaseTables.RECEIPT_UPLOAD.id
			) //  ReceiptUpload_temp > ReceiptUpload
		}

		HelperLog.appendLine(sbLog, "Finish Running Migration 123")
		HelperLog.logToFile(sbLog, HelperLogFileName.TerminaLog)
	}

	private fun renameTempToOriTableName(dbHandler: DbHandler, tableName: String) {
		HelperLog.appendLine(sbLog, "Rename ${tableName}_temp to -> ", tableName)
		val query = "ALTER TABLE " + tableName + "_temp RENAME TO " + tableName
		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun renameExistingTableToBackup(dbHandler: DbHandler, tableName: String) {
		HelperLog.appendLine(sbLog, "Backup Table for -> ", tableName)
		val query = "ALTER TABLE " + tableName + " RENAME TO " + tableName + "_BKV123"
		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun copyDataFromExisting(dbHandler: DbHandler, tableName: String): Boolean {
		HelperLog.appendLine(sbLog, "Copy Data to Temp Table....")
		val query =
			"INSERT INTO " + tableName + "_temp (SEQ_NO, TXN_DT, TXN_TYPE, MID, TID, MTI, NII, SCHEME_ID, AID, CARD_MASKED, CARD_HASHED, RRN, APPR_CODE, TXN_AMT, INV_NO, STAN, BATCH_NO, RESP_CODE, APP_VER, SN, ENTRY_TYPE, ARQC, TVR, CreationDate, LastUpdateDt, IsProcessing, IsSend) " +
					"SELECT SEQ_NO, TXN_DT, TXN_TYPE, MID, TID, MTI, NII, SCHEME_ID, AID, CARD_MASKED, CARD_HASHED, RRN, APPR_CODE, TXN_AMT, INV_NO, STAN, BATCH_NO, RESP_CODE, APP_VER, SN, ENTRY_TYPE, ARQC, TVR, CreationDate" +
					", " + SimpleDateFormat(
				"yyyy-MM-dd HH:mm:ss",
				Locale.ENGLISH
			).format(Date())   //LastUpdateDt
		", IsProcessing, IsSend " +
				"FROM " + tableName;

		val result = dbHandler.runningCustomSql(query, arrayOf())
		return result > 0
	}

	private fun dropTempTable(dbHandler: DbHandler, tableName: String) {
		HelperLog.appendLine(sbLog, "Drop Temp Table -> ", tableName)
		val query = "DROP TABLE IF EXISTS ${tableName}_temp"
		dbHandler.runningCustomSql(query, arrayOf())
	}


	private fun createTables(tableName: String, isCreateTemp: Boolean) {
		HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
		HelperLog.appendLine(sbLog, "IsTemporaryTable -> ", isCreateTemp.toString())
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
		var newTableName = tableName
		if (isCreateTemp) {
			newTableName = tableName + "_temp"
		}

		val query = "CREATE TABLE " + newTableName + " (" +
				"SEQ_NO TEXT," +
				"TXN_DT TEXT," +
				"TXN_TYPE TEXT," +
				"MID TEXT," +
				"TID TEXT," +
				"MTI TEXT," +
				"NII TEXT," +
				"SCHEME_ID TEXT," +
				"AID TEXT," +
				"CARD_MASKED TEXT," +
				"CARD_HASHED TEXT," +
				"RRN TEXT," +
				"APPR_CODE TEXT," +
				"TXN_AMT TEXT," +
				"INV_NO TEXT," +
				"STAN TEXT," +
				"BATCH_NO TEXT," +
				"RESP_CODE TEXT," +
				"APP_VER TEXT," +
				"SN TEXT," +
				"ENTRY_TYPE TEXT," +
				"ARQC TEXT," +
				"TVR TEXT," +
				"CreationDate TEXT," +
				"LastUpdateDt TEXT," +
				"IsProcessing TEXT," +
				"IsSend TEXT" +
				")";

		dbHandler.runningCustomSql(query, arrayOf())
	}

	private fun createMerchantConfigTables(tableName: String, isCreateTemp: Boolean) {
		HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
		HelperLog.appendLine(sbLog, "IsTemporaryTable -> ", isCreateTemp.toString())
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
		var newTableName = tableName
		if (isCreateTemp) {
			newTableName = tableName + "_temp"
		}

		val query = "CREATE TABLE " + newTableName + " (" +
				"MerchantName TEXT," +
				"MerchantAddress TEXT," +
				"AutoSettleT1 TEXT," +
				"AutoSettleT2 TEXT," +
				"AutoSettleT3 TEXT," +
				"WaitCardMs TEXT," +
				"QrMid TEXT," +
				"QrTid TEXT," +
				"McVer TEXT," +
				"AcqCode TEXT," +
				"AcqMid TEXT," +
				"AcqTid TEXT," +
				"PrimaryHostIp TEXT," +
				"PrimaryHostPort TEXT," +
				"SecondaryHostIp TEXT," +
				"SecondaryHostPort TEXT," +
				"TPDU TEXT," +
				"NII TEXT," +
				"HostTimeoutMs TEXT," +
				"LastSettlementBatchNo TEXT," +
				"LastStan TEXT," +
				"LastInvoiceNo TEXT" +
				")";

		dbHandler.runningCustomSql(query, arrayOf())
	}


	private fun createProductListTable(tableName: String, isCreateTemp: Boolean) {
		HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
		HelperLog.appendLine(sbLog, "IsTemporaryTable -> ", isCreateTemp.toString())
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
		var newTableName = tableName
		if (isCreateTemp) {
			newTableName = tableName + "_temp"
		}

		val query = "CREATE TABLE " + newTableName + " (" +
				"Id INTEGER NOT NULL UNIQUE," +
				"Product TEXT," +
				"AcqCode TEXT," +
				"AcqMid TEXT," +
				"AcqTid TEXT," +
				"QrProductCode TEXT," +
				"ProductName TEXT," +
				"PRIMARY KEY(Id AUTOINCREMENT)" +
				")";

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
				"DEV_LANE_ID TEXT" +
				")";

		dbHandler.runningCustomSql(query, arrayOf())
	}
}