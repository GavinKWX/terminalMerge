package com.sc.mf919pro.kotlin.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import helpers.HelperNetwork
import helpers.HelperText

class TransactionTransmitter : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("UNCHECKED_CAST")
        val map = intent.getSerializableExtra(TxnKeys.EXTRA_TXN_MAP) as? HashMap<String, String>
        val settleMap = intent.getSerializableExtra(TxnKeys.SETTLE_TXN_MAP) as? ArrayList<*>
        if (map == null && settleMap == null) {
            logResponse("App-to-app response NOT sent :: no result map in the intent")
            finish(); return
        }

        val pkg = ServiceHolder.packageName.orEmpty()
        val act = ServiceHolder.activityName.orEmpty()
        ServiceHolder.appIntent = false
        ServiceHolder.packageName = ""
        ServiceHolder.activityName = ""
        val kind = if (settleMap != null) "settlement" else "transaction"
        if (pkg.isBlank() || act.isBlank()) {
            logResponse("App-to-app $kind response NOT sent :: no caller package/activity recorded")
            finish(); return
        }
        logResponse("App-to-app $kind response -> $pkg/$act :: ${HelperText.oneLine((settleMap ?: map).toString())}")
        val back = Intent().apply {
            setClassName(pkg, act)
            if(settleMap != null) {
                putExtra(TxnKeys.SETTLE_TXN_MAP, settleMap)
            } else {
                putExtra(TxnKeys.EXTRA_TXN_MAP, map)
            }

            addFlags(
                //Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(back)
    }

    // One TerminaLog line per result handed back to the caller app, like HTTPServer's "Set Response Msg".
    private fun logResponse(line: String) {
        try {
            val log = HelperLog(
                HelperCommon.getSession(),
                HelperNetwork.isConnectedWifi(this),
                Utils.getIPAddress(),
                TAG,
                TAG,
                "App-to-App Response"
            )
            log.appendLine(TAG, line)
            log.logToFile(EnumLogFileName.TerminaLog)
        } catch (ex: Exception) {
            // Logging must never stop the result reaching the caller.
            ex.printStackTrace()
        }
    }

    private companion object {
        const val TAG = "TransactionTransmitter"
    }
}