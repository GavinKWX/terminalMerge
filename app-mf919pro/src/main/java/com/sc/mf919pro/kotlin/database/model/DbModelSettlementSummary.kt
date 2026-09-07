package com.sc.mf919pro.kotlin.database.model

data class DbModelSettlementSummary(
	var acq_code: String,
	var mid: String,
	var tid: String,
	var tag: String,
	var subtag: String,
	var value: String,
	var is_settle: String
)

data class DbModelSettlementSummaryDistinct(
	var acq_code: String,
	var mid: String,
	var tid: String,
	var is_settle: String
)