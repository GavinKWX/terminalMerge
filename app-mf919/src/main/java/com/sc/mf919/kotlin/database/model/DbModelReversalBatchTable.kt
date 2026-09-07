package com.sc.mf919.kotlin.database.model

data class DbModelRevBatchTable (
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
    val tid: String
)

data class DbModelRevBatchTableInsert (
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
    val tid: String
)