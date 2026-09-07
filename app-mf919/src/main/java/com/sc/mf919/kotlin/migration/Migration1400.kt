package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelIsoBatchInfo
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration1400 {
	/*
	* - Initial Version after Add In BSN
	* - KSN, PIN_KSN for BSN in isoBatchInfo
	* - BSN EPP (EppProductCode, EppTenure, EppTenureCode) in productList Table
	* - Add Settlement Summary Table for multiple Settlement
	* - Add mid, tid (columns) in batchTable
	* - Add PAYMENT_PRODUCT_ID (columns) in receiptUpload
	* */
	lateinit var sbLog: StringBuilder
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
		"PRIMARY KEY(Id AUTOINCREMENT)"
	)

	private val settlementSummaryField = listOf(
		"id INTEGER NOT NULL UNIQUE",
		"acq_code TEXT",
		"mid TEXT",
		"tid TEXT",
		"tag TEXT",
		"subtag TEXT",
		"value TEXT",
		"is_settle TEXT",
		"PRIMARY KEY(id AUTOINCREMENT)"
	)

	private val batchTableField = listOf(
		"id INTEGER NOT NULL UNIQUE",
		"postingDt vchar(50) NOT NULL",
		"txnType vchar(25) NOT NULL",
		"stan vchar(25) NOT NULL",
		"invNo vchar(25) NOT NULL",
		"batchData TEXT NOT NULL",
		"schemeTag vchar(25)",
		"schemeId vchar(10)",
		"refId INTEGER NOT NULL",
		"status vchar(3) NOT NULL",
		"batchNo vchar(25)",
		"mid TEXT NOT NULL",
		"tid TEXT NOT NULL",
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
		"PAYMENT_PRODUCT_ID TEXT",
		"CreationDate TEXT",
		"LastUpdateDt TEXT",
		"IsProcessing TEXT",
		"IsSend TEXT"
	)

	fun startMigration() {
		sbLog = HelperLog.init("----- Start Running Migration 1400 -----")
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

		/* Step 1 Check Table Existence */
		HelperLog.appendLine(sbLog, "Check Table Exist -> ${DatabaseTables.PRODUCT_LIST.id}")
		if (!dbHandler.checkTableExist(DatabaseTables.PRODUCT_LIST)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.PRODUCT_LIST.id, productListField)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> ${DatabaseTables.SETTLEMENT_SUMMARY.id}")
		if (!dbHandler.checkTableExist(DatabaseTables.SETTLEMENT_SUMMARY)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.SETTLEMENT_SUMMARY.id, settlementSummaryField)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> ${DatabaseTables.BATCH_TABLE.id}")
		if (!dbHandler.checkTableExist(DatabaseTables.BATCH_TABLE)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.BATCH_TABLE.id, batchTableField)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> ${DatabaseTables.RECEIPT_UPLOAD.id}")
		if (!dbHandler.checkTableExist(DatabaseTables.RECEIPT_UPLOAD)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTables(DatabaseTables.RECEIPT_UPLOAD.id, receiptUploadField)
		}
		/* Step 1 Check Table Existence */

		val ksnCardEntry = IsoBatchInfoRepo.getBatchInfo(ServiceHolder.getContext(), "ksn", "visam")
		HelperLog.appendLine(sbLog, "Check data in Table IsoBatchInfo -> tag:ksn, subtag: visam")
		if (ksnCardEntry == null) {
			HelperLog.appendLine(sbLog, "KSN for BSN not exist")
			HelperLog.appendLine(
				sbLog, "Adding value tag=>ksn, subtag=>visam, value=>FFF00000000000000000"
			)
			val ksnCardInsert = DbModelIsoBatchInfo("ksn", "visam", "FFF00000000000000000")
			IsoBatchInfoRepo.insertToBatchInfo(ServiceHolder.getContext(), ksnCardInsert)
		}

		val ksnCardPinEntry = IsoBatchInfoRepo.getBatchInfo(
			ServiceHolder.getContext(), "ksn", "visam-pin"
		)
		HelperLog.appendLine(
			sbLog, "Check data in Table IsoBatchInfo -> tag:ksn, subtag: visam-pin"
		)
		if (ksnCardPinEntry == null) {
			HelperLog.appendLine(sbLog, "PIN KSN for BSN not exist")
			HelperLog.appendLine(
				sbLog, "Adding value tag=>ksn, subtag=>visam-pin, value=>FFFF0000000000000000"
			)
			val ksnCardPinInsert = DbModelIsoBatchInfo("ksn", "visam-pin", "FFFF0000000000000000")
			IsoBatchInfoRepo.insertToBatchInfo(ServiceHolder.getContext(), ksnCardPinInsert)
		}

		/*  Table Exist */
		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.PRODUCT_LIST.id}) -> EppProductCode")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRODUCT_LIST, "EppProductCode")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "EppProductCode Alter to -> ${DatabaseTables.PRODUCT_LIST.id}"
			)
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.PRODUCT_LIST.id}) -> EppTenure")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRODUCT_LIST, "EppTenure")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(sbLog, "EppTenure Alter to -> ${DatabaseTables.PRODUCT_LIST.id}")
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.PRODUCT_LIST.id}) -> EppTenureCode")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRODUCT_LIST, "EppTenureCode")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "EppTenureCode Alter to -> ${DatabaseTables.PRODUCT_LIST.id}"
			)
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.PRODUCT_LIST.id}) -> IsSettlement")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRODUCT_LIST, "IsSettlement")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "IsSettlement Alter to -> ${DatabaseTables.PRODUCT_LIST.id}"
			)
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.PRODUCT_LIST.id}) -> IsSettled")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRODUCT_LIST, "IsSettled")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "IsSettled Alter to -> ${DatabaseTables.PRODUCT_LIST.id}"
			)
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.PRODUCT_LIST.id}) -> BatchNo")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRODUCT_LIST, "BatchNo")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "BatchNo Alter to -> ${DatabaseTables.PRODUCT_LIST.id}"
			)
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.PRODUCT_LIST.id}) -> IsActive")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.PRODUCT_LIST, "IsActive")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "IsActive Alter to -> ${DatabaseTables.PRODUCT_LIST.id}"
			)
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.BATCH_TABLE.id}) -> mid")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.BATCH_TABLE, "mid")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "mid Alter to -> ${DatabaseTables.BATCH_TABLE.id}"
			)
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.BATCH_TABLE.id}) -> tid")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.BATCH_TABLE, "tid")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "tid Alter to -> ${DatabaseTables.BATCH_TABLE.id}"
			)
		}

		HelperLog.appendLine(sbLog, "Check Column in (${DatabaseTables.RECEIPT_UPLOAD.id}) -> PAYMENT_PRODUCT_ID")
		if (!dbHandler.checkTableColumnExist(DatabaseTables.RECEIPT_UPLOAD, "PAYMENT_PRODUCT_ID")) {
			HelperLog.appendLine(sbLog, "Column Not Exist")
			HelperLog.appendLine(
				sbLog, "PAYMENT_PRODUCT_ID Alter to -> ${DatabaseTables.RECEIPT_UPLOAD.id}"
			)
		}
		/*  Table Exist */

		HelperLog.appendLine(sbLog, "Finish Running Migration 1400")
		HelperLog.logToFile(sbLog, HelperLogFileName.TerminaLog)
	}

	private fun createTables(tableName: String, tableField: List<String>) {
		HelperLog.appendLine(sbLog, "Creating Table -> ", tableName)
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!

		val querySeparator = ", "
		val query = "CREATE TABLE $tableName (${tableField.joinToString(querySeparator)})"
		dbHandler.runningCustomSql(query, arrayOf())
	}
}