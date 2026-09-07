package com.sc.mf919pro.kotlin.data_enum

import com.sc.mf919pro.R

data class AcquirerLogoEnumModel(
    var HeaderLogoPng: Int,
    var HeaderLogoBmp: String,
    var AboutLogo: Int,
    var AcquirerName: String,
    var DisplayName: String
)

data class AcquirerSettingModel(
    val acqName: String,
    val requireSignOn: Boolean,
    val isSSL: Boolean,
    val sslCert: Int,
    val retryFailBatchUpload: Boolean,
    val settleBlock: Boolean,
)

enum class AcquirerLogoDataEnum(val data: AcquirerLogoEnumModel) {
    GOBIZ(AcquirerLogoEnumModel(R.mipmap.logo_1, "image/gobiz_logo.bmp", R.mipmap.ic_launcher, "GoBiz", "GoBiz")),
    PAYDEE(AcquirerLogoEnumModel(R.mipmap.paydee, "image/paydee_logo.bmp", R.mipmap.ic_launcher, "Paydee", "Paydee")),
    IPAY88(AcquirerLogoEnumModel(R.mipmap.ipaylogo, "image/ipaylogo.bmp", R.mipmap.ic_launcher, "IPay88", "IPay88")),
    RAZERPAY(AcquirerLogoEnumModel(R.mipmap.fiuu_logo, "image/fiuu_logo.bmp", R.mipmap.ic_launcher, "RazerPay", "RazerPay")),
    RM(AcquirerLogoEnumModel(R.mipmap.rm_logo, "image/rm_logo.bmp", R.mipmap.ic_launcher, "Revenue Monster", "Revenue Monster")),
    PAYDIBS(AcquirerLogoEnumModel(R.mipmap.paydibs_logo, "image/paydibs_logo.bmp", R.mipmap.ic_launcher, "Paydibs", "Paydibs")),
    ATOME(AcquirerLogoEnumModel(R.mipmap.paydibs_logo, "image/paydibs_logo.bmp", R.mipmap.ic_launcher, "ATOME", "ATOME")),
    IOUPAY(AcquirerLogoEnumModel(R.mipmap.ioupay_logo, "image/ioupay_logo.png", R.mipmap.ic_launcher, "IOUPAY", "IOUPAY")),
    BSN(AcquirerLogoEnumModel(R.mipmap.bsn_logo, "image/bsn_logo.bmp", R.mipmap.ic_launcher, "BSN", "BSN")),
    BSN_CARDZONE(AcquirerLogoEnumModel(R.mipmap.bsn_logo, "image/bsn_logo.bmp", R.mipmap.ic_launcher, "BSN_Cardzone", "BSN")),
    PAYEX_GOBIZ(AcquirerLogoEnumModel(R.mipmap.payex_logo, "image/payex_logo.bmp", R.mipmap.ic_launcher, "Payex_Gobiz", "Payex")),
    FINEXUS(AcquirerLogoEnumModel(R.mipmap.finexus_logo, "image/finexus_logo.bmp", R.mipmap.ic_launcher, "Finexus", "Finexus"));
}