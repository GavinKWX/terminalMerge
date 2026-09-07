package com.sc.mf919.kotlin.database.model

import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.helper_common.ServiceHolder

data class DbModelTerminalConfig(
    var DEV_PROJECT: String? ,
    var DEV_LOCATION: String? ,
    var DEV_LANE_ID: String? ,
    var Contact: String? ,
    var Contactless: String? ,
    var MagStripe: String? ,
    var QrPay: String? ,
    var ForcePin: String? ,
    var IsoPrint: String? ,
    var ReceiptPrint: String? ,
    var OptIn: String? ,
    var SaleComOnline: String? ,
    var AutoSettle: String? ,
    var Sale: String? ,
    var Void: String? ,
    var PreAuth: String? ,
    var SaleCom: String? ,
    var Refund: String? ,
    var TmsReceipt: String? ,
    var TmsEnable: String? ,
    var PoweredBy: String? ,
    var PoweredByBW: String? ,
    var HomeLogo: String? ,
    var MC_VER: String? ,
    var VOID_WITH_PIN: String?,
    var MOTO: String?,
    var TIMEOUT_SECONDS: String?,
    var SETTLEMENT_WITH_PIN: String?,
    var REMOTE_DOWNLOAD_BSN_KEY: String?,
    var EWALLET_PRODUCT_LIST: String?,
    var FORCE_LOCK_HOME: String?,
    var FORCE_SETTLEMENT: String?,
    var UNATTENDED_MODE: String?,
    var CABLE_CONNECTION: String?,
    var FORCE_SETTLEMENT_DAILY: String?,
    var CASHOUT: String?,
    var ISO_WEBSOCKET: String?,
    var WEBSOCKET: String?,
    var SALES_CARD: String?,
    var SALES_EWALLET: String?,
    var DENOMINATION: String?,
) {
    companion object {
        fun getSafeValue(mcModel: DbModelTerminalConfig?, attr: String): String {
            var result = ""
            val attrName = "get$attr"

            mcModel?.let {
                try {
                    val tempResult = it.javaClass.getMethod(attrName).invoke(it)
                    tempResult?.let { obtainedValue ->
                        result = obtainedValue.toString()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return result
        }

        fun getBooleanValue(mcModel: DbModelTerminalConfig?, attr: String): Boolean {
            var result = "false"
            val attrName = "get$attr"

            mcModel?.let {
                try {
                    val tempResult = it.javaClass.getMethod(attrName).invoke(it)
                    tempResult?.let { obtainedValue ->
                        if(obtainedValue.toString() == "1"){
                            result = "true"
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return result.toBoolean()
        }

        fun setBooleanValue(_value: Boolean): String {
            var result = "0"
            if (_value) {
                result = "1"
            }
            return result
        }

    }
}