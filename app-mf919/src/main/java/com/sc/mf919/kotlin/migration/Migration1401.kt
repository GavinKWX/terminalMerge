package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import helpers.HelperLog
import enums.EnumLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration1401 {
	/*
	* Migration 1401 Details
	* - Add POS_REF_NO (columns) in receiptUpload
	* */

	lateinit var sbLog: StringBuilder
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
		"CreationDate TEXT",
		"LastUpdateDt TEXT",
		"IsProcessing TEXT",
		"IsSend TEXT"
	)

	fun startMigration() {
		sbLog = HelperLog.init("----- Start Running Migration Migration1401 -----")
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

		/* Step 1 return if Table Not Exist */
		HelperLog.appendLine(sbLog, "Check Table Existence -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
		if (!dbHandler.checkTableExist(DatabaseTables.RECEIPT_UPLOAD)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.RECEIPT_UPLOAD.id, receiptUploadField)
		}
		/* Step 1 return if Table Not Exist */

		/*  Table Exist */
		HelperLog.appendLine(sbLog, "Check Column in ${DatabaseTables.RECEIPT_UPLOAD.id} -> POS_REF_NO")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "POS_REF_NO")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(sbLog, "POS_REF_NO Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
		}

		HelperLog.appendLine(sbLog, "Finish Running Migration Migration1401")
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