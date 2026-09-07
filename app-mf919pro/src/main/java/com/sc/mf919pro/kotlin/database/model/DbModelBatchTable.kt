package com.sc.mf919pro.kotlin.database.model

class DbModelBatchTable (
	val id: String,
	val postingDt: String,
	val txnType: String,
	val stan: String,
	val invNo: String,
	val batchData: String,
	val schemeTag: String,
	val schemeId: String,
	val refId: String,
	val status: String,
	val batchNo: String,
	val mid: String,
	val tid: String,
	val posRefNo: String
)

data class DbModelBatchTableInsert (
	val postingDt: String,
	val txnType: String,
	val stan: String,
	val invNo: String,
	val batchData: String,
	val schemeTag: String,
	val schemeId: String,
	val refId: String,
	val status: String,
	val batchNo: String,
	val mid: String,
	val tid: String,
	val posRefNo: String
)