package com.sc.mf919pro.kotlin.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.helper_common.ProNewIntegrationHost
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import ecr.Route
import ecr.TransactionParser
import ecr.TransactionRouter
import ecr.TxnKeys
import ecr.TxnRequest
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import helpers.HelperNetwork
import helpers.HelperText
import kotlinx.coroutines.launch

class TransactionReceiver : AppCompatActivity() {
    private val parser = TransactionParser()

    // Router and use cases live in :core (audit item 98); ProNewIntegrationHost is registered in MF919.java.
    private val router = TransactionRouter()

    private lateinit var loading: AlertDialog
    private var currentReq: TxnRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(R.layout.activity_txn_receiver)

        loading = AlertDialog.Builder(this)
            .setCancelable(false)
            .setView(R.layout.activity_layoutloadingdialog)
            .create()
        loading.show()

        lifecycleScope.launch {
            val reqRes = parser.parse(intent)
            if (reqRes.isFailure) {
                logRequest("App-to-app request REJECTED :: ${reqRes.exceptionOrNull()?.message} :: extras=${intent.extras?.keySet()}")
                loading.dismiss()
                finish()
                return@launch
            }

            currentReq = reqRes.getOrNull()
            currentReq?.let { logRequest("App-to-app request <- ${it.returnPackage}/${it.returnActivity} :: ${HelperText.oneLine(it.toString())}") }

            loading.dismiss()
            dispatch(reqRes.getOrThrow())
        }
    }

    // One TerminaLog line per request received from a caller app; the reply is logged by TransactionTransmitter.
    private fun logRequest(line: String) {
        try {
            val log = HelperLog(
                HelperCommon.getSession(),
                HelperNetwork.isConnectedWifi(this),
                Utils.getIPAddress(),
                TAG,
                TAG,
                "App-to-App Request"
            )
            log.appendLine(TAG, line)
            log.logToFile(EnumLogFileName.TerminaLog)
        } catch (ex: Exception) {
            // Logging must never stop the request being handled.
            ex.printStackTrace()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // optionally re-run same flow
    }

    private fun dispatch(req: TxnRequest) {
        ServiceHolder.appIntent = true
        ServiceHolder.txnType = req.txnType
        ServiceHolder.packageName = req.returnPackage
        ServiceHolder.activityName = req.returnActivity
        ServiceHolder.ackCountDownSecond = if (req.hasAckCountdown) {
            Utils.atoi(req.ackCountdown ?: ServiceHolder.defaultAckCountdownSecond.toString())
        } else {
            ServiceHolder.defaultAckCountdownSecond
        }
        when (val route = router.route(req)) {
            is Route.Navigate -> {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra("nav_action_id", ProNewIntegrationHost.navId(route.destination))
                    putExtra("nav_bundle", ProNewIntegrationHost.bundle(route.args))
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            is Route.Return -> {
                startActivity(Intent(this, TransactionTransmitter::class.java).apply {
                    putExtra(TxnKeys.EXTRA_TXN_MAP, route.resultMap)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
                finish()
            }
        }
    }

    private companion object {
        const val TAG = "TransactionReceiver"
    }
}