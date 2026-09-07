package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.*
import androidx.activity.OnBackPressedCallback
import com.sc.mf919.R
import com.sc.mf919.java.activity.ParameterValueDisplay
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.ServiceHolder


@SuppressLint("UseSwitchCompatOrMaterialCode")
class MerchantConfigActivity: ActivityBase() {
	var tms: DbModelTerminalConfig? = null
	private var tagTms = arrayOf(
		"DEV_PROJECT","DEV_LOCATION","DEV_LANE_ID"
	)
	var merchantInfo: DbModelMerchantConfig? = null
	private var tagMerchantInfo = arrayOf(
		"MerchantName","MerchantAddress","AutoSettleT1","AutoSettleT2","AutoSettleT3","WaitCardMs","QrMid","QrTid","McVer",
		"AcqCode","AcqMid","AcqTid","PrimaryHostIp","PrimaryHostPort","SecondaryHostIp","SecondaryHostPort","TPDU","NII",
		"HostTimeoutMs","LastSettlementBatchNo","LastStan","LastInvoiceNo"
	)

	private var rl: LinearLayout? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_parameterview_screen_1)
		rl = findViewById(R.id.layout_1PE)

		merchantInfo = ServiceHolder.getMerchantInfo()
		tms = ServiceHolder.getTerminalConfig()
		formLayout()
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
	}

	private fun formLayout() {
		rl!!.removeAllViews()
		for (i in tagTms.indices) {
			val pd = ParameterValueDisplay(this, i + 1, tagTms[i], DbModelTerminalConfig.getSafeValue(tms, tagTms[i]))
			rl!!.addView(pd.view)
		}
		for (j in tagMerchantInfo.indices) {
			val pd = ParameterValueDisplay(this, j + 1, tagMerchantInfo[j], DbModelMerchantConfig.getSafeValue(merchantInfo, tagMerchantInfo[j]))
			rl!!.addView(pd.view)
		}
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		return if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_MENU) true
		else super.onKeyDown(keyCode, event)
	}

	fun customOnBackPress() {
		val intent = Intent(applicationContext, AdminActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}
}