package com.sc.mf919.kotlin.data_enum

enum class EnumWebsocket(val socketCommand : String) {
    //Socket Handler
    ValidateSale("ValidateSales"),
    Signon("SignOnService"),
    TerminalDMDispense("TerminalDMDispense"),
    UpdatePrice("UpdatePrice"),
}