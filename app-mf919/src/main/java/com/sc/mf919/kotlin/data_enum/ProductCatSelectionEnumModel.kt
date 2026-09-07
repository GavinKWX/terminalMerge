package com.sc.mf919.kotlin.data_enum

import com.sc.mf919.R
import com.sc.mf919.kotlin.activity.*

data class ProductCatSelectionEnumModel(
    var ProductLogo: Int,
    var ProductTitle: String,
    var SalesType: Int,
    var Active: Boolean,
    var SubProduct: Boolean,
    var NextClass: Class<*>?,
    var PrintNextClass: Class<*>?,
    var CustomPrintNextClass: Class<*>?,
    var SettlementNextClass: Class<*>?,
    var VoidNextClass: Class<*>?
)

enum class ProductCatSelectionDataEnum(val data: ProductCatSelectionEnumModel) {
    CARD_SETTINGS(ProductCatSelectionEnumModel(R.drawable.credit_card_icon, "CREDIT / DEBIT CARD", 1, true, false, KeypadActivity::class.java, TransactionViewListActivity::class.java, TransactionViewListActivity::class.java, SettlementActivity::class.java, VoidSaleActivity::class.java)),
    EWALLET_MERCHANT_SCANS(ProductCatSelectionEnumModel(R.drawable.qr_icon, "E-WALLET", 10,true, false, KeypadActivity::class.java, TransactionViewListQrActivity::class.java, TransactionViewListQrActivity::class.java, SettlementQrActivity::class.java, VoidQrActivity::class.java)),
    GENERATE_QR(ProductCatSelectionEnumModel(R.drawable.qr_icon, "GENERATE QR", 20, true, true, GenerateQrSubProductActivity::class.java, TransactionViewListQrActivity::class.java, TransactionViewListQrActivity::class.java, SettlementQrActivity::class.java, VoidQrActivity::class.java)),
    EPP(ProductCatSelectionEnumModel(R.drawable.epp_icon, "EPP", 30, true, false, EppAcquirerActivity::class.java, null, null, null, null)),
    BNPL(ProductCatSelectionEnumModel(R.drawable.paylate_icon, "BNPL", 40, true, false, BnplAcquirerActivity::class.java, TransactionViewBnplActivity::class.java, TransactionViewBnplActivity::class.java, SettlementBnplActivity::class.java, null)),
    MOTO(ProductCatSelectionEnumModel(R.drawable.credit_card_icon, "MOTO", 50, true, false, null, null, null,null, null)),
    CASH_OUT(ProductCatSelectionEnumModel(R.drawable.credit_card_icon, "CASH OUT", 5, true, false, null, null, null,null, null));

    companion object {
        // Get Type by code with check existing codes and default
        fun getProductCatForHttp(code: Int): String {
            return when(code){
                1, 2, 3, 4, 5, 6, 10 -> CARD_SETTINGS.name
                7, 8 -> EWALLET_MERCHANT_SCANS.name
                11 -> MOTO.name
                12 -> EPP.name
                else -> throw Exception("Invalid Category Type")
            }
        }

        fun getProductCatEnumForHttp(code: Int): ProductCatSelectionEnumModel {
            return when(code){
                1, 2, 3 -> CARD_SETTINGS.data
                7, 8 -> EWALLET_MERCHANT_SCANS.data
                11 -> MOTO.data
                else -> throw Exception("Invalid Category Type")
            }
        }
    }
}
