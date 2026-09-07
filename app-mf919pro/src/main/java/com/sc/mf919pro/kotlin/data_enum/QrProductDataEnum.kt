package com.sc.mf919pro.kotlin.data_enum

import com.sc.mf919pro.R

data class QrProductEnumModel(
	var LayoutFragmentId: Int,
	var logoImage: Int,
	var qrHeaderImage: Int?,
	var qrOverlay: Int?,
	var qrOverlaySizeWidth: Int?,
	var qrOverlaySizeHeight: Int?,
	var qrColor: Int?
)

enum class QrProductDataEnum(val data: QrProductEnumModel) {
	QR_ALIPAYPLUS(QrProductEnumModel(R.layout.fragment_dynamicqr, R.mipmap.alipayplus_logo, R.mipmap.alipayplus_logo, R.mipmap.overlay_alipayplus, 35, 35, null)),
	QR_DUITNOW(QrProductEnumModel(R.layout.fragment_duitnowqr, R.mipmap.duitnowqr_logo, null, null, null, null, R.color.colorRuby)),
	QR_MCASH(QrProductEnumModel(R.layout.fragment_dynamicqr, R.mipmap.mcashqr_logo, null, R.mipmap.mcashqr_logo, 50, 50,null)),
	QR_BOOST(QrProductEnumModel(R.layout.fragment_dynamicqr, R.mipmap.boostqr_logo, null, R.mipmap.boostqr_logo, 50, 50,null)),
	QR_NETSPAY(QrProductEnumModel(R.layout.fragment_dynamicqr, R.mipmap.netspayqr_logo, null, R.mipmap.netspayqr_logo,50, 50,null)),
	QR_REDPAY(QrProductEnumModel(R.layout.fragment_dynamicqr, R.mipmap.redpayqr_logo, null, R.mipmap.redpayqr_logo,50, 50,null)),
	QR_UNIONPAY(QrProductEnumModel(R.layout.fragment_dynamicqr, R.mipmap.unionpayqr_logo, R.mipmap.upiqr_logo, R.mipmap.unionpayqr_logo, 50, 30,null)),
	QR_GRABPAY(QrProductEnumModel(R.layout.fragment_dynamicqr, R.mipmap.grabpayqr_logo, R.mipmap.grabpayqr_logo, R.mipmap.grabpayqr_logo, 50, 50,null)),
	QR_AHAPAY(QrProductEnumModel(R.layout.fragment_dynamicqr, R.mipmap.ahapayqr_logo, R.mipmap.ahapayqr_logo, R.mipmap.ahapayqr_logo, 60, 25,null)),
	BNPL_AHAPAY(QrProductEnumModel(R.layout.fragment_dynamicqr, R.mipmap.ahapayqr_logo, R.mipmap.ahapayqr_logo, R.mipmap.ahapayqr_logo, 60, 25,null)),
	BNPL_ATOME(QrProductEnumModel(-1, R.mipmap.atomeqr_logo, R.mipmap.atomeqr_logo, null, null, null,null)),
	BNPL_GRAB(QrProductEnumModel(-1, R.mipmap.grablaterqr_logo, R.mipmap.grablaterqr_logo, null, null, null,null)),
	BNPL_SHOPEE(QrProductEnumModel(-1, R.mipmap.spaylaterqr_logo, R.mipmap.spaylaterqr_logo, null, null, null,null)),
	QR_EMPTY(QrProductEnumModel(R.layout.fragment_dynamicqr, R.mipmap.blank, null, R.mipmap.redpayqr_logo,50, 50, null)),
}