package com.sc.mf919.kotlin.activity

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.helper_common.Helper.Companion.getInstance
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getAppVersion
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getInternalFilesPaths
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getMerchantInfo
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalSerialNumber
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.selectedCacheModel
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.bottomActionBarEvent
import helpers.HelperCommon.Companion.disableKey
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import updateConnectionViews
import java.io.File

class AboutActivity: ActivityBase() {
	lateinit var main: LinearLayout
	lateinit var tvWifi: TextView
	lateinit var tvSim: TextView
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

    override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_about)
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"About / terminal information screen"
		)
		helperLog.appendLine(helperLogClassName, "About screen opened")
		val toolbar = findViewById<Toolbar>(R.id.toolbarAbout)
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener { customOnBackPress() }
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() { customOnBackPress() }
		})

		(findViewById<View>(R.id.textView2) as TextView).text = getAppVersion()
		main = findViewById(R.id.moreDetails)

		val dbModelMerchantConfig = getMerchantInfo()
		renderSimpleLinearView("MODEL", ServiceHolder.getDeviceModel())
		renderSimpleLinearView("IP", ServiceHolder.getCurrentLocalIpAddress())
		renderSimpleLinearView("", getTerminalSerialNumber())
		renderSimpleLinearView("", getSafeValue(dbModelMerchantConfig, "MerchantName", "-"))

		val isTpa = getSafeValue(dbModelMerchantConfig, "IsTpaAccount", "false").toBoolean()
		if (isTpa){
			renderSimpleLinearView("MID", getSafeValue(dbModelMerchantConfig, "ScMid", "-"))
			renderSimpleLinearView("TID", getSafeValue(dbModelMerchantConfig, "ScTid", "-"))
		} else {
			renderSimpleLinearView("MID", getSafeValue(dbModelMerchantConfig, "AcqMid", "-"))
			renderSimpleLinearView("TID", getSafeValue(dbModelMerchantConfig, "AcqTid", "-"))
		}

		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val connMethod = DbModelTerminalConfig.getSafeValue(dbModelTerminalConfig, "CABLE_CONNECTION")
		if(connMethod.isNotEmpty() && !connMethod.equals("None", true)) {
			renderSimpleLinearView("Connection Mode :", connMethod)
		}

		val footer = LinearLayout(applicationContext)
		val params = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
		)
		params.setMargins(0, 5, 0, 0)
		params.gravity = Gravity.CENTER
		footer.orientation = LinearLayout.VERTICAL
		footer.layoutParams = params

		val power = TextView(applicationContext)
		power.textSize = 9f
		power.textAlignment = View.TEXT_ALIGNMENT_CENTER
		power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
		power.setTextColor(resources.getColor(R.color.black))
		power.text = "POWERED BY"
		footer.addView(power)

		val im = ImageView(applicationContext)
		val params1 = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, getInstance().getDpValue(30)
		)
		im.layoutParams = params1
		im.adjustViewBounds = true
		//im.setImageResource(R.mipmap.logo)
		im.setImageResource(R.mipmap.logo)
		val paths = getInternalFilesPaths() + "powerLogo.png"
		val imgFile = File(paths)
		if (imgFile.exists()) {
			val myBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
			im.setImageBitmap(myBitmap)
			helperLog.appendLine(helperLogClassName, "onCreate: image")
		}

		footer.addView(im)
		main.addView(footer)

		tvWifi = findViewById(R.id.tvWifi)
		tvWifi.setDebouncedOnClickListener {
			helperLog.appendLine(helperLogClassName, "Selected :: WiFi status [ABOUT]")
			helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> Android network settings")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				val dbModelTerminalConfig = getTerminalConfig()
				if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_LOCK_HOME")) {
					bottomActionBarEvent(this, "0") //Temp Unlocked
					disableKey(this, "1")
				}
				startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
			} else {
				startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
			}
		}

		tvSim = findViewById(R.id.tvSim)
		tvSim.setDebouncedOnClickListener {
			helperLog.appendLine(helperLogClassName, "Selected :: SIM status [ABOUT]")
			helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> Android network settings")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				val dbModelTerminalConfig = getTerminalConfig()
				if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_LOCK_HOME")) {
					bottomActionBarEvent(this, "0") //Temp Unlocked
					disableKey(this, "1")
				}
				startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
			} else {
				startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
			}
		}

		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving About screen")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
				val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
				newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
				startActivity(newIntent)
			}
		})

		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	override fun onResume() {
		super.onResume()
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "FORCE_LOCK_HOME")) {
			bottomActionBarEvent(this, "1")
			disableKey(this, "0")
		}
		updateConnectionViews(this, tvWifi, tvSim)
	}

	private fun renderSimpleLinearView(label: String?, value: String?) {
		val ll = LinearLayout(applicationContext)
		val params = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
		)
		ll.orientation = LinearLayout.HORIZONTAL
		ll.gravity = Gravity.CENTER
		val params1 = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
		)
		params1.setMargins(5, 0, 5, 0)
		val tv = TextView(applicationContext)
		tv.layoutParams = params1
		tv.textSize = 14f
		tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
		tv.setTextColor(resources.getColor(R.color.black))
		tv.text = label
		val tv1 = TextView(applicationContext)
		tv1.layoutParams = params1
		tv1.textSize = 14f
		tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
		tv1.setTextColor(resources.getColor(R.color.black))
		tv1.text = value
		ll.addView(tv)
		ll.addView(tv1)
		main.addView(ll)
	}

	fun customOnBackPress() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving About screen")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		val dbModelTerminalConfig = getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "AboutActivity OnDestroy :: about screen ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}
}