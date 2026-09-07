package com.sc.mf919.kotlin.database.model

data class DbModelDenominationUpload (
    val Id: String,
    val Type: String,
    val DataString: String,
    var CreationDate: String?,
    var LastUpdateDt: String?,
    var IsProcessing: String?,
    var IsSend: String?
)

data class DbModelDenominationUploadInsert (
    val Type: String,
    val DataString: String,
    var CreationDate: String?,
    var LastUpdateDt: String?,
    var IsProcessing: String?,
    var IsSend: String?
)