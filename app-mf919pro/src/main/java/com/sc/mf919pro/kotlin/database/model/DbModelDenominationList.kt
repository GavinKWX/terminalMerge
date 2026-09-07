package com.sc.mf919pro.kotlin.database.model

data class DbModelDenominationList (
    val Id: String,
    val PackageId: String,
    val Desc: String,
    val Amount: String,
    val Ref1: String,
    val Ref2: String,
    val Ref3: String,
    val Ref4: String,
    val Ref5: String,
    val Remark: String,
    val MaintenanceSchedule: String,
    val DisplayMenuType: String,
)

data class DbModelDenominationListInsert (
    val PackageId: String,
    val Desc: String,
    val Amount: String,
    val Ref1: String,
    val Ref2: String,
    val Ref3: String,
    val Ref4: String,
    val Ref5: String,
    val Remark: String,
    val MaintenanceSchedule: String,
    val DisplayMenuType: String,
)

data class ModelMaintenanceSchedule (
    val START_TIME: String,
    val END_TIME: String,
)