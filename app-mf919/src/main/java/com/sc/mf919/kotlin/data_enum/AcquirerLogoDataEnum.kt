package com.sc.mf919.kotlin.data_enum

import com.sc.mf919.R

data class AcquirerLogoEnumModel(
    var HeaderLogoPng: Int,
    var HeaderLogoBmp: String,
    var AboutLogo: Int,
    var AcquirerName: String
)

data class AcquirerSettingModel(
    val acqName: String,
    val requireSignOn: Boolean,
    val isSSL: Boolean,
    val sslCert: Int,
    val settleBlock: Boolean,
)


enum class AcquirerLogoDataEnum(val data: AcquirerLogoEnumModel) {
    BLANK(AcquirerLogoEnumModel(R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, "")),
    GOBIZ(AcquirerLogoEnumModel(R.mipmap.logo_1, "image/gobiz_logo.bmp", R.mipmap.ic_launcher, "GoBiz")),
    PAYDEE(AcquirerLogoEnumModel(R.mipmap.paydee, "image/paydee_logo.bmp", R.mipmap.ic_launcher, "Paydee")),
    IPAY88(AcquirerLogoEnumModel(R.mipmap.ipaylogo, "image/ipaylogo.bmp", R.mipmap.ic_launcher, "IPay88")),
    RAZERPAY(AcquirerLogoEnumModel(R.mipmap.fiuu_logo, "image/fiuu_logo.bmp", R.mipmap.ic_launcher, "RazerPay")),
    RM(AcquirerLogoEnumModel(R.mipmap.rm_logo, "image/rm_logo.bmp", R.mipmap.ic_launcher, "Revenue Monster")),
    PAYDIBS(AcquirerLogoEnumModel(R.mipmap.paydibs_logo, "image/paydibs_logo.bmp", R.mipmap.ic_launcher, "Paydibs")),
    ATOME(AcquirerLogoEnumModel(R.mipmap.paydibs_logo, "image/paydibs_logo.bmp", R.mipmap.ic_launcher, "ATOME")),
    IOUPAY(AcquirerLogoEnumModel(R.mipmap.ioupay_logo, "image/ioupay_logo.png", R.mipmap.ic_launcher, "IOUPAY")),
    BSN(AcquirerLogoEnumModel(R.mipmap.bsn_logo, "image/bsn_logo.bmp", R.mipmap.ic_launcher, "BSN")),
    BSN_CARDZONE(AcquirerLogoEnumModel(R.mipmap.bsn_logo, "image/bsn_logo.bmp", R.mipmap.ic_launcher, "BSN_Cardzone")),
    PAYEX_GOBIZ(AcquirerLogoEnumModel(R.mipmap.payex_logo, "image/payex_logo.bmp", R.mipmap.ic_launcher, "Payex_Gobiz")),
    FINEXUS(AcquirerLogoEnumModel(R.mipmap.finexus_logo, "image/finexus_logo.bmp", R.mipmap.ic_launcher, "Finexus"));

    companion object {
        fun from(acqCode: String?): AcquirerLogoDataEnum? {
            return values().find {
                it.name.equals(acqCode, ignoreCase = true)
            }
        }
    }
}