package com.sc.mf919pro.kotlin.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys

class TransactionTransmitter : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("UNCHECKED_CAST")
        val map = intent.getSerializableExtra(TxnKeys.EXTRA_TXN_MAP) as? HashMap<String, String>
        val settleMap = intent.getSerializableExtra(TxnKeys.SETTLE_TXN_MAP) as? ArrayList<*>
        //println("TransactionTransmitter :: $map")
        if (map == null && settleMap == null) { finish(); return }

        val pkg = ServiceHolder.packageName.orEmpty()
        val act = ServiceHolder.activityName.orEmpty()
        ServiceHolder.appIntent = false
        ServiceHolder.packageName = ""
        ServiceHolder.activityName = ""
        if (pkg.isBlank() || act.isBlank()) { finish(); return }
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
}