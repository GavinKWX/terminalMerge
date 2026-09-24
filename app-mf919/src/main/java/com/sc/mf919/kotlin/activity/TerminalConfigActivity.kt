package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.device.DeviceHelper
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.repo.TerminalConfigurationRepo
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import com.sc.mf919.kotlin.helper_common.MfHelper

@SuppressLint("UseSwitchCompatOrMaterialCode")
class TerminalConfigActivity: ActivityBase() {
	lateinit var Contact: Switch
	lateinit var Contactless: Switch
	lateinit var MagStripe: Switch
	lateinit var QrPay: Switch
	lateinit var ForcePin: Switch
	lateinit var IsoPrint: Switch
	lateinit var ReceiptPrint: Switch
	lateinit var OptIn: Switch
	lateinit var SaleComOnline: Switch
	lateinit var AutoSettle: Switch
	lateinit var Sale: Switch
	lateinit var Void: Switch
	lateinit var PreAuth: Switch
	lateinit var SaleCom: Switch
	lateinit var Refund: Switch
	lateinit var CashOut: Switch
	lateinit var TmsReceipt: Switch
	lateinit var TmsEnable: Switch
	lateinit var ForceSettlementCheck: Switch
	lateinit var ForceSettlementDaily: Switch
	lateinit var ForceLockHome: Switch
	lateinit var BypassPin: Switch

	
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_terminal_configuration)

		//TODO Dynamic Layout For Small Terminal
		val displayMetrics = resources.displayMetrics
		val screenHeightPx = displayMetrics.heightPixels
		val screenHeightDp = screenHeightPx / displayMetrics.density
		println("screenHeightDp :: $screenHeightDp")

		if (screenHeightDp < 500) {
			val termConfScroll = findViewById<ScrollView>(R.id.terminalConfigScroll)
			val params = termConfScroll.layoutParams
			params.height = Helper.getInstance().dpToPx(350)
			termConfScroll.layoutParams = params
		}
		//TODO Dynamic Layout For Small Terminal

		Contact = findViewById(R.id.contact)
		Contactless = findViewById(R.id.contactless)
		MagStripe = findViewById(R.id.megStripe)
		QrPay = findViewById(R.id.qrPay)
		ForcePin = findViewById(R.id.forcePin)
		IsoPrint = findViewById(R.id.printIso)
		ReceiptPrint = findViewById(R.id.printReceipt)
		OptIn = findViewById(R.id.optIn)
		SaleComOnline = findViewById(R.id.SaleCompOnline)
		AutoSettle = findViewById(R.id.autoSettle)
		Sale = findViewById(R.id.sale)
		Void = findViewById(R.id.void1)
		PreAuth = findViewById(R.id.preauth)
		SaleCom = findViewById(R.id.SaleComp)
		Refund = findViewById(R.id.refund)
		CashOut = findViewById(R.id.cashout)
		TmsReceipt = findViewById(R.id.receiptTms)
		TmsEnable = findViewById(R.id.deviceTms)
		ForceSettlementCheck = findViewById(R.id.forceSettlementCheck)
		ForceSettlementDaily = findViewById(R.id.forceSettlementDaily)
		ForceLockHome = findViewById(R.id.forceLockHome)
		BypassPin = findViewById(R.id.bypassPin)

		val terminalConfig = ServiceHolder.getTerminalConfig()

		Contact.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "Contact")
		Contactless.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "Contactless")
		MagStripe.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "MagStripe")
		QrPay.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "QrPay")
		ForcePin.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "ForcePin")
		IsoPrint.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "IsoPrint")
		ReceiptPrint.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "ReceiptPrint")
		OptIn.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "OptIn")
		SaleComOnline.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "SaleComOnline")
		AutoSettle.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "AutoSettle")
		Sale.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "Sale")
		Void.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "Void")
		PreAuth.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "PreAuth")
		SaleCom.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "SaleCom")
		Refund.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "Refund")
		CashOut.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "CASHOUT")
		TmsReceipt.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "TmsReceipt")
		TmsEnable.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "TmsEnable")
		ForceSettlementCheck.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "FORCE_SETTLEMENT")
		ForceSettlementDaily.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "FORCE_SETTLEMENT_DAILY")
		ForceLockHome.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "FORCE_LOCK_HOME")
		BypassPin.isChecked = DbModelTerminalConfig.getBooleanValue(terminalConfig, "BYPASS_PIN")

		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
	}

	fun customOnBackPress() {
		object : Thread() {
			override fun run() {
				super.run()
				save()
				DeviceHelper.resetAID()
			}
		}.start()
		val intent = Intent(applicationContext, AdminActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	private fun save() {
		if (ForceLockHome.isChecked) {
			MfHelper.lockStatusBarAndNavigation(true)
		} else {
			MfHelper.lockStatusBarAndNavigation(false)
		}

		val updateMap = mutableMapOf<Any,Any>(
			"Contact" to DbModelTerminalConfig.setBooleanValue(Contact.isChecked),
			"Contactless" to DbModelTerminalConfig.setBooleanValue(Contactless.isChecked),
			"MagStripe" to DbModelTerminalConfig.setBooleanValue(MagStripe.isChecked),
			"ForcePin" to DbModelTerminalConfig.setBooleanValue(ForcePin.isChecked),
			"IsoPrint" to DbModelTerminalConfig.setBooleanValue(IsoPrint.isChecked),
			"ReceiptPrint" to DbModelTerminalConfig.setBooleanValue(ReceiptPrint.isChecked),
			"Sale" to DbModelTerminalConfig.setBooleanValue(Sale.isChecked),
			"Void" to DbModelTerminalConfig.setBooleanValue(Void.isChecked),
			"PreAuth" to DbModelTerminalConfig.setBooleanValue(PreAuth.isChecked),
			"SaleCom" to DbModelTerminalConfig.setBooleanValue(SaleCom.isChecked),
			"SaleComOnline" to DbModelTerminalConfig.setBooleanValue(SaleComOnline.isChecked),
			"Refund" to DbModelTerminalConfig.setBooleanValue(Refund.isChecked),
			"OptIn" to DbModelTerminalConfig.setBooleanValue(OptIn.isChecked),
			"TmsReceipt" to DbModelTerminalConfig.setBooleanValue(TmsReceipt.isChecked),
			"TmsEnable" to DbModelTerminalConfig.setBooleanValue(TmsEnable.isChecked),
			"AutoSettle" to DbModelTerminalConfig.setBooleanValue(AutoSettle.isChecked),
			"QrPay" to DbModelTerminalConfig.setBooleanValue(QrPay.isChecked),
			"FORCE_SETTLEMENT" to DbModelTerminalConfig.setBooleanValue(ForceSettlementCheck.isChecked),
			"FORCE_SETTLEMENT_DAILY" to DbModelTerminalConfig.setBooleanValue(ForceSettlementDaily.isChecked),
			"FORCE_LOCK_HOME" to DbModelTerminalConfig.setBooleanValue(ForceLockHome.isChecked),
			"CASHOUT" to DbModelTerminalConfig.setBooleanValue(CashOut.isChecked),
			"BYPASS_PIN" to DbModelTerminalConfig.setBooleanValue(BypassPin.isChecked),
		)

		TerminalConfigurationRepo.updateTerminalConfig(applicationContext, updateMap)
		ServiceHolder.clearTerminalConfigInformation()
	}
}