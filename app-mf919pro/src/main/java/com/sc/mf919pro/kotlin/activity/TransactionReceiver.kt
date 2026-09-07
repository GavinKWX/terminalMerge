package com.sc.mf919pro.kotlin.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sc.mf919pro.R
import com.sc.mf919pro.kotlin.helper_common.intent_helper.Route
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TransactionParser
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TransactionRouter
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.intent_helper.EnquiryUseCase
import com.sc.mf919pro.kotlin.helper_common.intent_helper.PreAuthUseCase
import com.sc.mf919pro.kotlin.helper_common.intent_helper.SaleUseCase
import com.sc.mf919pro.kotlin.helper_common.intent_helper.SettlementUseCase
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnRequest
import com.sc.mf919pro.kotlin.helper_common.intent_helper.VoidUseCase
import kotlinx.coroutines.launch

class TransactionReceiver : AppCompatActivity() {
    private val parser = TransactionParser()

    // Wire these with DI later; for now create directly
    private val router = TransactionRouter(
        saleUseCase = SaleUseCase(),
        voidUseCase = VoidUseCase(),
        settlementUseCase = SettlementUseCase(),
        preauthUseCase = PreAuthUseCase(),
        enquiryUseCase = EnquiryUseCase()
    )

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
                loading.dismiss()
                finish()
                return@launch
            }

            currentReq = reqRes.getOrNull()

            loading.dismiss()
            dispatch(reqRes.getOrThrow())
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
        when (val route = router.route(this, req)) {
            is Route.Navigate -> {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra("nav_action_id", route.actionId)
                    putExtra("nav_bundle", route.bundle)
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
}