package com.sc.mf919.kotlin.helper_common.iso

enum class IsoHelperNew {
    UNKNOWN,
    GOBIZ(
        IsoInfoModel("SignOn", "0800", "920000", "", "3 11 12 13 24 41 42 57 64","", null),
        IsoInfoModel("Sale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35", null),
        IsoInfoModel("CtSale", "0200", "000000", "0011", "", "", null),//No use
        IsoInfoModel("VoidSale", "0200", "020000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        IsoInfoModel("TcUpload", "0320", "940000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14", null),
        IsoInfoModel("Settle", "0500", "920000", "", "3 11 24 41 42 57 60 63 64","", null),
        IsoInfoModel("SettleTrailer", "0500", "960000", "", "3 11 24 41 42 57 60 63 64","", null),
        IsoInfoModel("BatchUpload", "0320", "", "", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14", null),
        IsoInfoModel("Reversal", "0400", "000000",  "", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64", "2 14", null),
        IsoInfoModel("Preauth", "0100", "300000", "0611", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35", null),
        IsoInfoModel("VoidPreauth", "0100", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        null,
        IsoInfoModel("SaleComp", "0220", "000000", "0611", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64","2 14", null),
        IsoInfoModel("VoidSaleComp", "0220", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        null,
        null,
        null,
        IsoInfoModel("Moto", "0200", "000000", "0811", "2 3 4 11 12 13 14 22 24 25 41 42 57 62 63 64","2 14", null),
        null, null,
    ),
    PAYDEE(
        IsoInfoModel("SignOn", "0800", "920000", "", "3 11 12 13 24 41 42 57 64","", null),
        IsoInfoModel("Sale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35", null),
        IsoInfoModel("CtSale", "", "",  "", "","", null),//No use
        IsoInfoModel("VoidSale", "0200", "020000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        IsoInfoModel("TcUpload", "0320", "940000",  "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14", null),
        IsoInfoModel("Settle", "0500", "920000", "", "3 11 24 41 42 57 60 63 64","", null),
        IsoInfoModel("SettleTrailer", "0500", "960000",  "", "3 11 24 41 42 57 60 63 64","", null),
        IsoInfoModel("BatchUpload", "0320", "",  "", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","", null),
        IsoInfoModel("Reversal", "0400", "000000",  "", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64","2 14", null),
        IsoInfoModel("Preauth", "0100", "300000", "0611", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35", null),
        IsoInfoModel("VoidPreauth", "0100", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        null,
        IsoInfoModel("SaleComp", "0220", "000000", "0611", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64","2 14", null),
        IsoInfoModel("VoidSaleComp", "0220", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        null,
        null,
        null,
        IsoInfoModel("Moto", "0200", "000000", "0811", "2 3 4 11 12 13 14 22 24 25 41 42 57 62 63 64","2 14", null),
        null, null,
    ),
    BSN(
        null,
        IsoInfoModel("Sale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 53 55 57 62 64","35 52 55", null),
        IsoInfoModel("CtSale", "0200", "000000", "0011", "","", null),//No use
        IsoInfoModel("VoidSale", "0200", "020000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        IsoInfoModel("TcUpload", "0320", "940000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14 55", null),
        IsoInfoModel("Settle", "0500", "920000", "", "3 11 24 41 42 57 60 63 64","60 63", null),
        IsoInfoModel("SettleTrailer", "0500", "960000", "", "3 11 24 41 42 57 60 63 64","60 63", null),
        IsoInfoModel("BatchUpload", "0320", "", "", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14 55", null),
        IsoInfoModel("Reversal", "0400", "000000", "", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64","2 14 55", null),
        IsoInfoModel("Preauth", "0100", "300000", "0611", "3 4 11 22 23 24 25 35 41 42 52 53 55 57 62 64","35 52 55", null),
        IsoInfoModel("VoidPreauth", "0100", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        null,
        IsoInfoModel("SaleComp", "0220", "000000", "0611", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64","2 14", null),
        IsoInfoModel("VoidSaleComp", "0220", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        null,
        null,
        IsoInfoModel("EppSale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 53 55 57 60 62 64","35 52 55", null),
        null,null, null,
    ),
    BSN_CARDZONE(
        IsoInfoModel("SignOn", "0800", "920000", "", "3 11 24 41 42","", null),
        IsoInfoModel("Sale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 55 58 59 62 64","35", null),
        IsoInfoModel("CtSale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 55 58 59 62 64","35", null), // No use
        IsoInfoModel("VoidSale", "0200", "020000", "0011", "2 3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64","2", null),
        IsoInfoModel("TcUpload", "0320", "940000", "0011", "3 4 11 12 13 22 23 24 25 35 37 38 39 41 42 55 58 59 60 62 64","35", null),
        IsoInfoModel("Settle", "0500", "920000", "", "3 11 24 41 42 58 60 63 64","", null),
        IsoInfoModel("SettleTrailer", "0500", "960000", "", "3 11 24 41 42 58 60 63 64","", null),
        IsoInfoModel("BatchUpload", "0320", "", "", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 58 59 60 61 62 64","2", null),
        //IsoInfoModel("Reversal", "0400", "000000", "", "2 3 4 11 14 22 23 24 25 37 38 41 42 55 58 59 62 64","2"),
        IsoInfoModel("Reversal", "0400", "000000", "", "2 3 4 11 14 22 23 24 25 41 42 55 58 59 62 64","2", null),
        IsoInfoModel("Preauth", "0100", "300000", "0611", "3 4 11 22 23 24 25 35 41 42 52 55 58 59 62 64","35", null),
        IsoInfoModel("VoidPreauth", "0100", "020000", "0611", "2 3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64","2", null),
        IsoInfoModel("SaleCompCard", "0220", "000000", "0611", "3 4 11 12 13 22 23 24 25 35 37 38 41 42 55 58 59 62 64","35", null),
        IsoInfoModel("SaleComp", "0220", "000000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 41 42 58 59 62 64","2", null),
        //null,
        /* cardzone not support void sale compl*/
        IsoInfoModel("VoidSaleComp", "0220", "020000", "0611", "2 3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64","2", null),
        IsoInfoModel("SaleCashOut", "0200", "090000", "0011", "3 4 11 22 23 24 25 35 41 42 52 54 55 58 59 62 64","35", null),
        IsoInfoModel("VoidSaleCashOut", "0200", "100000", "0011", "2 3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64","2", null),
        IsoInfoModel("EppSale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 53 55 58 59 61 62 64","35", null),
        null, /* cardzone not support MOTO due to security reason */
        IsoInfoModel("CreditAdjust", "0200", "110000", "0011", "2 3 4 11 14 22 23 24 25 37 38 41 42 58 59 62 64","2", "37 38"),
        IsoInfoModel("DebitAdjust", "0200", "120000", "0011", "2 3 4 11 14 22 23 24 25 37 38 41 42 58 59 62 64","2", "37 38"),
    ),
    FINEXUS(
        IsoInfoModel("SignOn", "0800", "920000", "", "3 11 12 13 24 41 42 57 64","", null),
        IsoInfoModel("Sale", "0200", "000000", "0011", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35", null),
        IsoInfoModel("CtSale", "0200", "000000", "0011", "", "", null),//No use
        IsoInfoModel("VoidSale", "0200", "020000", "0011", "2 3 4 11 12 13 14 22 24 25 37 38 39 41 42 57 62 64","2 14", null),
        IsoInfoModel("TcUpload", "0320", "940000", "0011", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14", null),
        IsoInfoModel("Settle", "0500", "920000", "", "3 11 24 41 42 57 60 63 64","", null),
        IsoInfoModel("SettleTrailer", "0500", "960000", "", "3 11 24 41 42 57 60 63 64","", null),
        IsoInfoModel("BatchUpload", "0320", "", "", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64","2 14", null),
        IsoInfoModel("Reversal", "0400", "000000",  "", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64", "2 14", null),
        IsoInfoModel("Preauth", "0100", "300000", "0611", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64","35", null),
        IsoInfoModel("VoidPreauth", "0100", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        null,
        IsoInfoModel("SaleComp", "0220", "000000", "0611", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64","2 14", null),
        IsoInfoModel("VoidSaleComp", "0220", "020000", "0611", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64","2 14", null),
        null,
        null,
        null,
        IsoInfoModel("Moto", "0200", "000000", "0011", "2 3 4 11 12 13 14 22 24 25 41 42 57 62 63 64","2 14", null),
        null, null,
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
    var SaleCompCard: IsoInfoModel?
    var SaleComp: IsoInfoModel?
    var VoidSaleComp: IsoInfoModel?
    var SaleCashOut: IsoInfoModel?
    var VoidSaleCashOut: IsoInfoModel?
    var EppSale: IsoInfoModel?
    var Moto: IsoInfoModel?
    var debitAdjustment: IsoInfoModel?
    var creditAdjustment: IsoInfoModel?
    constructor(signOn: IsoInfoModel?, sale: IsoInfoModel?, contactSale: IsoInfoModel?, voidSale: IsoInfoModel?, tcUpload: IsoInfoModel?, settle: IsoInfoModel?, settleTrailer: IsoInfoModel?, batchUpload: IsoInfoModel?, reversal: IsoInfoModel?, preauth: IsoInfoModel?, voidPreauth: IsoInfoModel?,
                saleCompCard: IsoInfoModel?, saleComp: IsoInfoModel?, voidSaleComp: IsoInfoModel?, saleCashOut: IsoInfoModel?, voidSaleCashOut: IsoInfoModel?, eppSale: IsoInfoModel?, moto: IsoInfoModel?, creditAdjustment: IsoInfoModel?, debitAdjustment: IsoInfoModel?) {
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
        this.SaleCompCard = saleCompCard
        this.SaleComp = saleComp
        this.VoidSaleComp = voidSaleComp
        this.SaleCashOut = saleCashOut
        this.VoidSaleCashOut = voidSaleCashOut
        this.EppSale = eppSale
        this.Moto = moto
        this.debitAdjustment = debitAdjustment
        this.creditAdjustment = creditAdjustment
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
        this.SaleCompCard = null
        this.SaleComp = null
        this.VoidSaleComp = null
        this.SaleCashOut = null
        this.VoidSaleCashOut = null
        this.EppSale = null
        this.Moto = null
        this.debitAdjustment = null
        this.creditAdjustment = null
    }

    companion object{
        /**
         * Merges [additionalDes] into [baseDes] and returns the fields sorted ascending by field
         * number, duplicates removed. ISO 8583 data elements must be packed in ascending order, so
         * extra fields cannot simply be appended at the end of the list.
         * Non numeric tokens (if any) are kept at the end in their original order.
         */
        fun mergeTransactionDes(baseDes: String?, additionalDes: String?): String {
            return "${baseDes.orEmpty()} ${additionalDes.orEmpty()}"
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .distinct()
                .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                .joinToString(" ")
        }

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
                "salecompcard" -> acquirer.SaleCompCard
                "salecomp" -> acquirer.SaleComp
                "void_salecomp" -> acquirer.VoidSaleComp
                "sale_cashout" -> acquirer.SaleCashOut
                "void_sale_cashout" -> acquirer.VoidSaleCashOut
                "epp_sale" -> acquirer.EppSale
                "moto" -> acquirer.Moto
                "debit_adjustment" -> acquirer.debitAdjustment
                "credit_adjustment" -> acquirer.creditAdjustment
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
    var reversalDes: String?,
    //var reversalDesTle: String?,
    //var reversalDesSensitive: String?
)