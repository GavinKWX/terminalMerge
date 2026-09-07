package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import com.sc.mf919.R
import com.sc.mf919.java.activity.ParameterValueEditor
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.MerchantConfigurationRepo
import com.sc.mf919.kotlin.helper_common.ServiceHolder

@SuppressLint("UseSwitchCompatOrMaterialCode")
class HostConfigActivity: ActivityBase() {
	private val hostEditors = mutableListOf<ParameterValueEditor>()
	var hostInfo: DbModelMerchantConfig? = null
	private var tagHostInfo = arrayOf(
		"PrimaryHostIp","PrimaryHostPort","SecondaryHostIp","SecondaryHostPort","TPDU","NII","HostTimeoutMs"
	)
	private var rl: LinearLayout? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_parameterview_screen_2)
		rl = findViewById(R.id.layout_1PE)

		hostInfo = ServiceHolder.getMerchantInfo()
		formLayout()
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
	}

	private fun formLayout() {
		rl?.removeAllViews()
		hostEditors.clear()

		for (j in tagHostInfo.indices) {
			val pd = ParameterValueEditor(
				this,
				j + 1,
				tagHostInfo[j],
				DbModelMerchantConfig.getSafeValue(hostInfo, tagHostInfo[j])
			)

			hostEditors.add(pd)
			rl?.addView(pd.view)
		}
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		return if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_MENU) true
		else super.onKeyDown(keyCode, event)
	}

	fun customOnBackPress() {
		object : Thread() {
			override fun run() {
				super.run()
				save()
			}
		}.start()
		val intent = Intent(applicationContext, AdminActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	private fun save() {
		val updateMap = mutableMapOf<Any,Any>()
		hostEditors.forEach {
			updateMap[it.tag] = it.value
			when (it.tag) {
				"TPDU" -> {
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.setSafeValue(it.value), "isoTpduHeader", "visam")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.setSafeValue(it.value), "isoTpduHeader", "mccs")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.setSafeValue(it.value), "isoTpduHeaderTle", "visam")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.setSafeValue(it.value), "isoTpduHeaderTle", "mccs")
				}
				"NII" -> {
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.setSafeValue(it.value), "nii", "visam")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.setSafeValue(it.value), "nii", "mccs")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.setSafeValue(it.value), "niiTle", "visam")
					IsoBatchInfoRepo.updateBatchInfo(applicationContext, DbModelMerchantConfig.setSafeValue(it.value), "niiTle", "mccs")
				}
			}
		}

		MerchantConfigurationRepo.updateMerchantConfig(applicationContext, updateMap)
		ServiceHolder.clearMerchantInformation()
	}
}