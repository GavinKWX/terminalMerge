package com.sc.mf919.kotlin.database.model

data class DbModelLastSettlement(
    var mid: String,
    var tid: String,
    var settleInfo: String,
    var isTpaAccount: String,
    var settleDt: String
)