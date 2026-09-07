package com.sc.mf919.kotlin.migration

import com.sc.mf919.kotlin.database.infrastructure.DatabaseTables
import database.DbHandler
import com.sc.mf919.kotlin.database.model.DbModelIsoBatchInfo
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.HelperLogFileName
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import java.lang.StringBuilder

class Migration1310 {
	/*
		* Migration 1.3.10 Details
		* - Add iso value and code into databases for Moto
		* - Add Moto to TerminalConfiguration
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
		val dbHandler = DbHandler.getInstance(ServiceHolder.getContext())!!
		sbLog = HelperLog.init("----- Start Running Migration 1310 -----")

		val posEntry = IsoBatchInfoRepo.getBatchInfo(ServiceHolder.getContext(), "posEntryMode", "visam-Moto")
		HelperLog.appendLine(sbLog, "Check data in Table IsoBatchInfo -> tag:posEntryMode, subtag: visam-Moto")
		if(posEntry == null){
			HelperLog.appendLine(sbLog, "Pos Entry Mode for Moto not exist")
			HelperLog.appendLine(sbLog, "Adding value tag=>posEntryMode, subtag=>visam-Moto, value=>0010")
			val motoPosEntry = DbModelIsoBatchInfo("posEntryMode", "visam-Moto", "0010")
			IsoBatchInfoRepo.insertToBatchInfo(ServiceHolder.getContext(), motoPosEntry)
		}

		val posCondition = IsoBatchInfoRepo.getBatchInfo(ServiceHolder.getContext(), "posCondition", "pos-Moto")
		HelperLog.appendLine(sbLog, "Check data in Table IsoBatchInfo -> tag:posCondition, subtag: pos-Moto")
		if(posCondition == null){
			HelperLog.appendLine(sbLog, "Pos Condition for Moto not exist")
			HelperLog.appendLine(sbLog, "Adding value tag=>posCondition, subtag=>pos-Moto, value=>0811")
			val motoPosCondition = DbModelIsoBatchInfo("posCondition", "pos-Moto", "0811")
			IsoBatchInfoRepo.insertToBatchInfo(ServiceHolder.getContext(), motoPosCondition)
		}

		HelperLog.appendLine(sbLog, "Check Table Exist -> TerminalConfiguration")
		if (!dbHandler.checkTableExist(DatabaseTables.TERMINAL_CONFIGURATION)) {
			HelperLog.appendLine(sbLog, "Table Not Exist")
			createTerminalConfigTable(DatabaseTables.TERMINAL_CONFIGURATION.id, false)
		} else {
			HelperLog.appendLine(sbLog, "Check Column in (TerminalConfiguration) -> MOTO")
			if (!dbHandler.checkTableColumnExist(DatabaseTables.TERMINAL_CONFIGURATION, "MOTO")) {
				HelperLog.appendLine(sbLog, "Column Not Exist")
			}
		}

		HelperLog.appendLine(sbLog, "Finish Running Migration 1310")
		HelperLog.logToFile(sbLog, HelperLogFileName.TerminaLog)
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
				"VOID_WITH_PIN TEXT" +
				"MOTO TEXT" +
				")";

		dbHandler.runningCustomSql(query, arrayOf())
	}
}