package com.sc.mf919.kotlin.database.model

class DbModelPreAuthTable (
    val id: String,
    val postingDt: String,
    val cardData: String,
    val schemeTag: String,
    val apprCode: String,
    val rrn: String,
    val invNo: String,
    val status: String,
    val addInfo: String
)

class DbModelPreAuthTableInsert (
    val postingDt: String,
    val cardData: String,
    val schemeTag: String,
    val apprCode: String,
    val rrn: String,
    val invNo: String,
    val status: String,
    val addInfo: String
)