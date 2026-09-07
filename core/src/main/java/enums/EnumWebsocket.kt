package enums

//id is unsued, previous mistake
enum class EnumWebsocket(val socketCommand : String) {
    //Socket Handler
    ValidateSale("ValidateSales"),
    Signon("SignOnService"),
    UpdatePrice("UpdatePrice"),
}