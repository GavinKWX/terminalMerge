package com.sc.mf919pro.kotlin.helper_common.iso

enum class IsoHelperNew {
    UNKNOWN,
    GOBIZ(
        IsoInfoModel("SignOn", "0800", "920000", "", "3 11 12 13 24 41 42 57 64",""),
        IsoInfoModel("Sale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35"),
        IsoInfoModel("CtSale", "0200", "000000", "0011", "", ""),//No use
        IsoInfoModel("VoidSale", "0200", "020000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        IsoInfoModel("TcUpload", "0320", "940000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14"),
        IsoInfoModel("Settle", "0500", "920000", "", "3 11 24 41 42 57 60 63 64",""),
        IsoInfoModel("SettleTrailer", "0500", "960000", "", "3 11 24 41 42 57 60 63 64",""),
        IsoInfoModel("BatchUpload", "0320", "000000", "", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14"),
        IsoInfoModel("Reversal", "0400", "000000",  "", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64", "2 14"),
        IsoInfoModel("Preauth", "0100", "300000", "0611", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35"),
        IsoInfoModel("VoidPreauth", "0100", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        IsoInfoModel("SaleComp", "0220", "000000", "0011", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64","2 14"),
        IsoInfoModel("VoidSaleComp", "0220", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        null,
        null,
        null,
        IsoInfoModel("Moto", "0200", "000000", "0811", "2 3 4 11 12 13 14 22 24 25 41 42 57 62 63 64","2 14"),
        ),
    PAYDEE(
        IsoInfoModel("SignOn", "0800", "920000", "", "3 11 12 13 24 41 42 57 64",""),
        IsoInfoModel("Sale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35"),
        IsoInfoModel("CtSale", "", "",  "", "",""),//No use
        IsoInfoModel("VoidSale", "0200", "020000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        IsoInfoModel("TcUpload", "0320", "940000",  "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14"),
        IsoInfoModel("Settle", "0500", "920000", "", "3 11 24 41 42 57 60 63 64",""),
        IsoInfoModel("SettleTrailer", "0500", "960000",  "", "3 11 24 41 42 57 60 63 64",""),
        IsoInfoModel("BatchUpload", "0320", "000000",  "", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64",""),
        IsoInfoModel("Reversal", "0400", "000000",  "", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64","2 14",),
        IsoInfoModel("Preauth", "0100", "300000", "0611", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35"),
        IsoInfoModel("VoidPreauth", "0100", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        IsoInfoModel("SaleComp", "0220", "000000", "0011", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64","2 14"),
        IsoInfoModel("VoidSaleComp", "0220", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        null,
        null,
        null,
        IsoInfoModel("Moto", "0200", "000000", "0811", "2 3 4 11 12 13 14 22 24 25 41 42 57 62 63 64","2 14"),
        ),
    BSN(
        null,
        IsoInfoModel("Sale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 53 55 57 62 64","35 52 55"),
        IsoInfoModel("CtSale", "0200", "000000", "0011", "",""),//No use
        IsoInfoModel("VoidSale", "0200", "020000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        IsoInfoModel("TcUpload", "0320", "940000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14 55"),
        IsoInfoModel("Settle", "0500", "920000", "", "3 11 24 41 42 57 60 63 64","60 63"),
        IsoInfoModel("SettleTrailer", "0500", "960000", "", "3 11 24 41 42 57 60 63 64","60 63"),
        IsoInfoModel("BatchUpload", "0320", "000000", "", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14 55"),
        IsoInfoModel("Reversal", "0400", "000000", "", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64","2 14 55"),
        IsoInfoModel("Preauth", "0100", "300000", "0611", "3 4 11 22 23 24 25 35 41 42 52 53 55 57 62 64","35 52 55"),
        IsoInfoModel("VoidPreauth", "0100", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        IsoInfoModel("SaleComp", "0220", "000000", "0011", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64","2 14"),
        IsoInfoModel("VoidSaleComp", "0220", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        null,
        null,
        IsoInfoModel("EppSale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 53 55 57 60 62 64","35 52 55"),
        null
    ),
    BSN_CARDZONE(
        IsoInfoModel("SignOn", "0800", "920000", "", "3 11 24 41 42",""),
        IsoInfoModel("Sale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 55 58 59 62 64","35"),
        IsoInfoModel("CtSale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 55 58 59 62 64","35"), // No use
        IsoInfoModel("VoidSale", "0200", "020000", "0011", "2 3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64","2"),
        IsoInfoModel("TcUpload", "0320", "940000", "0011", "3 4 11 12 13 22 23 24 25 35 37 38 39 41 42 55 58 59 60 62 64","35"),
        IsoInfoModel("Settle", "0500", "920000", "", "3 11 24 41 42 58 60 63 64",""),
        IsoInfoModel("SettleTrailer", "0500", "960000", "", "3 11 24 41 42 58 60 63 64",""),
        IsoInfoModel("BatchUpload", "0320", "000000", "", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 58 59 60 62 64","2"),
        IsoInfoModel("Reversal", "0400", "000000", "", "2 3 4 11 14 22 23 24 25 41 42 55 58 59 62 64","2"),
        IsoInfoModel("Preauth", "0100", "300000", "0611", "3 4 11 22 23 24 25 35 41 42 52 55 58 59 62 64","35"),
        IsoInfoModel("VoidPreauth", "0100", "020000", "0611", "2 3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64","2"),
        IsoInfoModel("SaleComp", "0220", "000000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 41 42 58 59 62 64","2"),
        null,
        /* cardzone not support void sale compl*/ //IsoInfoModel("VoidSaleComp", "0220", "020000", "0611", "2 3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64","2"),
        IsoInfoModel("SaleCashOut", "0200", "090000", "0011", "3 4 11 22 23 24 25 35 41 42 52 54 55 58 59 62 64","35"),
        IsoInfoModel("VoidSaleCashOut", "0200", "100000", "0011", "2 3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64","2"),
        IsoInfoModel("EppSale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 53 55 58 59 61 62 64","35"),
        null, /* cardzone not support MOTO due to security reason */
        ),
    FINEXUS(
        IsoInfoModel("SignOn", "0800", "920000", "", "3 11 12 13 24 41 42 57 64",""),
        IsoInfoModel("Sale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35"),
        IsoInfoModel("CtSale", "0200", "000000", "0011", "", ""),//No use
        IsoInfoModel("VoidSale", "0200", "020000", "0011", "2 3 4 11 12 13 14 22 24 25 37 38 39 41 42 57 62 64","2 14"),
        IsoInfoModel("TcUpload", "0320", "940000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14"),
        IsoInfoModel("Settle", "0500", "920000", "", "3 11 24 41 42 57 60 63 64",""),
        IsoInfoModel("SettleTrailer", "0500", "960000", "", "3 11 24 41 42 57 60 63 64",""),
        IsoInfoModel("BatchUpload", "0320", "000000", "", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14"),
        IsoInfoModel("Reversal", "0400", "000000",  "", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64", "2 14"),
        IsoInfoModel("Preauth", "0100", "300000", "0611", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35"),
        IsoInfoModel("VoidPreauth", "0100", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        IsoInfoModel("SaleComp", "0220", "000000", "0611", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64","2 14"),
        IsoInfoModel("VoidSaleComp", "0220", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14"),
        null,
        null,
        null,
        IsoInfoModel("Moto", "0200", "000000", "0011", "2 3 4 11 12 13 14 22 24 25 41 42 57 62 63 64","2 14"),
    );

    var SignOn: IsoInfoModel?
    var Sale: IsoInfoModel?
    var ContactSale: IsoInfoModel?
    var VoidSale: IsoInfoModel?
    var TcUpload: IsoInfoModel?
    var Settle: IsoInfoModel?
    var SettleTrailer: IsoInfoModel?
    var BatchUpload: IsoInfoModel?
    var Reversal: IsoInfoModel?
    var Preauth: IsoInfoModel?
    var VoidPreauth: IsoInfoModel?
    var SaleComp: IsoInfoModel?
    var VoidSaleComp: IsoInfoModel?
    var SaleCashOut: IsoInfoModel?
    var VoidSaleCashOut: IsoInfoModel?
    var EppSale: IsoInfoModel?
    var Moto: IsoInfoModel?
    constructor(signOn: IsoInfoModel?, sale: IsoInfoModel?, contactSale: IsoInfoModel?, voidSale: IsoInfoModel?, tcUpload: IsoInfoModel?, settle: IsoInfoModel?, settleTrailer: IsoInfoModel?, batchUpload: IsoInfoModel?, reversal: IsoInfoModel?, preauth: IsoInfoModel?, voidPreauth: IsoInfoModel?,
                saleComp: IsoInfoModel?, voidSaleComp: IsoInfoModel?, saleCashOut: IsoInfoModel?, voidSaleCashOut: IsoInfoModel?, eppSale: IsoInfoModel?, moto: IsoInfoModel?) {
        this.SignOn = signOn
        this.Sale = sale
        this.ContactSale = contactSale
        this.VoidSale = voidSale
        this.TcUpload = tcUpload
        this.Settle = settle
        this.SettleTrailer = settleTrailer
        this.BatchUpload = batchUpload
        this.Reversal = reversal
        this.Preauth = preauth
        this.VoidPreauth = voidPreauth
        this.SaleComp = saleComp
        this.VoidSaleComp = voidSaleComp
        this.SaleCashOut = saleCashOut
        this.VoidSaleCashOut = voidSaleCashOut
        this.EppSale = eppSale
        this.Moto = moto
    }

    constructor() {
        this.SignOn = null
        this.Sale = null
        this.ContactSale = null
        this.VoidSale = null
        this.TcUpload = null
        this.Settle = null
        this.SettleTrailer = null
        this.BatchUpload = null
        this.Reversal = null
        this.Preauth = null
        this.VoidPreauth = null
        this.SaleComp = null
        this.VoidSaleComp = null
        this.SaleCashOut = null
        this.VoidSaleCashOut = null
        this.EppSale = null
        this.Moto = null
    }

    companion object{
        fun getIsoHelperObject(acquirer: String, transactionType: String): IsoInfoModel? {
            val acquirer = when(acquirer.uppercase()){
                "GOBIZ" -> GOBIZ
                "PAYDEE" -> PAYDEE
                "BSN" -> BSN
                "BSN_CARDZONE" -> BSN_CARDZONE
                "FINEXUS" -> FINEXUS
                else -> UNKNOWN
            }

            return when (transactionType.lowercase()){
                "sign_on" -> acquirer.SignOn
                "sale" -> acquirer.Sale
                "void" -> acquirer.VoidSale
                "settlement" -> acquirer.Settle
                "reversal" -> acquirer.Reversal
                "tc_upload" -> acquirer.TcUpload
                "batch_upload" -> acquirer.BatchUpload
                "settle_trailer" -> acquirer.SettleTrailer
                "preauth" -> acquirer.Preauth
                "void_preauth" -> acquirer.VoidPreauth
                "salecomp" -> acquirer.SaleComp
                "void_salecomp" -> acquirer.VoidSaleComp
                "sale_cashout" -> acquirer.SaleCashOut
                "void_sale_cashout" -> acquirer.VoidSaleCashOut
                "epp_sale" -> acquirer.EppSale
                "moto" -> acquirer.Moto
                else -> null
            }
        }
    }
}

data class IsoInfoModel(
    var name: String,
    var mti: String,
    var processCode: String,
    var posCondition: String,
    var transactionDes: String?,
    //var transactionDesTle: String?,
    var transactionDesSensitive: String?,
    //var reversalDes: String?,
    //var reversalDesTle: String?,
    //var reversalDesSensitive: String?
)