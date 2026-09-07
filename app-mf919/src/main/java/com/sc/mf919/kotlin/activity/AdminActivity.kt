package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import com.sc.mf919.BuildConfig
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.kotlin.data_enum.OxpayIntentModel
import com.sc.mf919.kotlin.database.repo.BatchTableRepo
import com.sc.mf919.kotlin.database.repo.IsoBatchLongInfoRepo
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.kotlin.database.repo.PrintReceiptRepo
import com.sc.mf919.kotlin.database.repo.ReversalBatchTableRepo
import com.sc.mf919.kotlin.database.repo.SettlementSummaryRepo
import com.sc.mf919.kotlin.datastore.DataStoreManager
import com.sc.mf919.kotlin.datastore.PrefKeys
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdminActivity : ActivityBase() {
	private val TAG = "Admin"
	var dialogView: View? = null
	var homeBtn: LinearLayout? = null
	var moreBtn: LinearLayout? = null
	var cube: CubeActivity? = null
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_admin)
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"Admin / engineer settings screen"
		)
		helperLog.appendLine(helperLogClassName, "Admin screen opened")
		homeBtn = findViewById(R.id.homeBtn)
		moreBtn = findViewById(R.id.moreBtn)
		homeBtn!!.isSelected = false
		moreBtn!!.isSelected = true
		cube = CubeActivity()
		val runningFlavor = BuildConfig.FLAVOR
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				var newIntent = Intent(applicationContext, SettingsActivity::class.java)
				if(runningFlavor == "oxpay") {
					newIntent = Intent(applicationContext, SettingsActivityOxpay::class.java)
				}
				helperLog.appendLine(helperLogClassName, "User Cancel :: back pressed, leaving Admin screen")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
				newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
				startActivity(newIntent)
				finish()
			}
		})
		val oxpayIntegrationButton = findViewById<LinearLayout>(R.id.integrationAppBtn).apply {
			setOnClickListener {
				val apps = listOf(
					OxpayIntentModel("Oxpay Lite", "oxpay.ffastpay", OxpayIntentModel.isAppInstalled("oxpay.ffastpay", this@AdminActivity)),
					OxpayIntentModel("Ffastpay", "com.mcpayment.sunmi.ffastpay", OxpayIntentModel.isAppInstalled("com.mcpayment.sunmi.ffastpay", this@AdminActivity)),
				)

				val preset = ServiceHolder.getOxpayIntent()
				helperLog.appendLine(helperLogClassName, "Dialog opened :: [SELECT PREFERRED APP]")
				var selectedIndex = -1
				if(preset.isNotEmpty()) {
					selectedIndex = apps.indexOfFirst { it.packageName == preset }
				}

				val adapter = CustomCheckboxAdapter(this@AdminActivity, apps)
				val dialog = AlertDialog.Builder(this@AdminActivity)
					.setTitle("Select Preferred App")
					.setAdapter(adapter, null)
					.setPositiveButton("OK") { d, _ ->
						if (selectedIndex != -1) {
							val selected = apps[selectedIndex]
							ServiceHolder.setOxpayIntent(selected.packageName)
							helperLog.appendLine(helperLogClassName, "Setting changed :: OxpayIntent $preset -> ${selected.packageName}")
							helperLog.logToFile(EnumLogFileName.TerminaLog)
						}
						d.dismiss()
					}
					.setNegativeButton("Cancel") { d, _ ->
						helperLog.appendLine(helperLogClassName, "User Cancel :: dismissed [SELECT PREFERRED APP]")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						d.dismiss()
					}
					.create()
				dialog.show()

				val listView = dialog.listView
				listView.choiceMode = ListView.CHOICE_MODE_SINGLE
				if (selectedIndex != -1) {
					listView.setItemChecked(selectedIndex, true)
				}
				listView.setOnItemClickListener { _, _, position, _ ->
					val item = apps[position]
					if (!item.isInstalled) {
						helperLog.appendLine(helperLogClassName, "REJECT :: ${item.name} not installed [SELECT PREFERRED APP]")
						if (selectedIndex == -1) {
							// ❌ prevent selection
							listView.setItemChecked(position, false)
							return@setOnItemClickListener
						}
						listView.setItemChecked(selectedIndex, true)
						return@setOnItemClickListener
					}

					selectedIndex = position
					helperLog.appendLine(helperLogClassName, "Selected :: ${item.name} [SELECT PREFERRED APP]")
					listView.setItemChecked(position, true)
				}
			}
		}
		if(runningFlavor == "oxpay") {
			findViewById<LinearLayout>(R.id.bottomLinearGroup).visibility = View.INVISIBLE
			oxpayIntegrationButton.visibility = View.VISIBLE
		}
	}

	fun more_pressed(view: View?) {
		helperLog.appendLine(helperLogClassName, "Selected :: More tab [ADMIN]")
		homeBtn!!.isSelected = false
		moreBtn!!.isSelected = true
	}

	fun home_pressed(view: View?) {
		val intent = Intent(this, AboutActivity::class.java)
		helperLog.appendLine(helperLogClassName, "Selected :: Home tab [ADMIN]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> AboutActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		homeBtn!!.isSelected = true
		moreBtn!!.isSelected = false
		finish()
	}

	fun btnFunTerminalConfig(view: View?) {
		val intent = Intent(this, TerminalConfigActivity::class.java)
		helperLog.appendLine(helperLogClassName, "Selected :: btnFunTerminalConfig [ADMIN]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> TerminalConfigActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	fun btnFunMerchConfig(view: View?) {
		val intent = Intent(this, MerchantConfigActivity::class.java)
		helperLog.appendLine(helperLogClassName, "Selected :: btnFunMerchConfig [ADMIN]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> MerchantConfigActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	fun btnFunHostConfig(view: View?) {
		val intent = Intent(this, HostConfigActivity::class.java)
		helperLog.appendLine(helperLogClassName, "Selected :: btnFunHostConfig [ADMIN]")
		helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> HostConfigActivity")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		startActivity(intent)
		finish()
	}

	fun btnFuncClearBatch(view: View) {
		val builder = AlertDialog.Builder(this)
		builder.setTitle("Clear Batch")
		builder.setMessage("Are you confirm to Clear Current Batch?")
		helperLog.appendLine(helperLogClassName, "Dialog opened :: [CLEAR BATCH]")

		// Set the positive button and its action
		builder.setNegativeButton("OK") { dialog, _ ->
			helperLog.appendLine(helperLogClassName, "Selected :: OK [CLEAR BATCH]")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			funcClearBatch()
			dialog.dismiss()
		}
		// Set the negative button and its action
		builder.setPositiveButton("Cancel") { dialog, _ ->
			helperLog.appendLine(helperLogClassName, "User Cancel :: abandoned [CLEAR BATCH]")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			dialog.dismiss()
		}

		// Create and show the alert dialog
		val alertDialog: AlertDialog = builder.create()
		alertDialog.show()
	}

	fun funcClearBatch() {
		object : Thread() {
			override fun run() {
				super.run()
				val store = DataStoreManager(this@AdminActivity)
				helperLog.appendLine(helperLogClassName, "Clear All Batch started")
				startProgressDialog(this@AdminActivity, "Clear All Batch", "Clearing... ")
				BatchTableRepo.truncateTable(this@AdminActivity)
				// The batchNo roll is a read-modify-write like every other counter site, so it
				// takes the same lock -- without it a sale in flight can be issued a number out of
				// the batch this is closing. allocateCounter does not fit here because the flow
				// needs the PREVIOUS value too, to delete that batch's records.
				//
				// Only the roll is inside the lock. The deletes and settlement cleanup below touch
				// no counters, and there is a 5s delay further down; holding the monitor across all
				// of that would stall a card tap.
				val strBatchNoToRemove = IsoBatchInfoRepo.withCounterLock {
					IsoBatchInfoRepo.getBatchInfo(this@AdminActivity, "batchNo", "visam")?.let { row ->
						var newDbBatchNo = Utils.atoi(row.value) + 1
						if (newDbBatchNo > 999999) newDbBatchNo = 1
						val finalBatchNo = String.format("%06d", newDbBatchNo)
						IsoBatchInfoRepo.updateBatchInfo(this@AdminActivity, finalBatchNo, "batchNo", "visam")
						helperLog.appendLine(helperLogClassName, "Setting changed :: batchNo ${row.value} -> $finalBatchNo")
						row.value
					}
				}
				strBatchNoToRemove?.let {
					//TODO Reset All Product First

					IsoBatchInfoRepo.updateBatchInfo(this@AdminActivity, "0", "txnTotal", "visam")
					IsoBatchInfoRepo.updateBatchInfo(this@AdminActivity, "0", "txnCount", "visam")
					IsoBatchInfoRepo.updateBatchInfo(this@AdminActivity, "0", "voidTxnTotal", "visam")
					IsoBatchInfoRepo.updateBatchInfo(this@AdminActivity, "0", "voidTxnCount", "visam")
					IsoBatchInfoRepo.updateBatchInfo(this@AdminActivity, "0", "tcCount", "visam")

					// Clear txn Count and Amount
					IsoBatchInfoRepo.updateBatchInfo(this@AdminActivity, "0", "buTxnTotal", "visam")
					IsoBatchInfoRepo.updateBatchInfo(this@AdminActivity, "0", "buTxnCount", "visam")

					// Delete Batch
					BatchTableRepo.deleteBatchRecord(this@AdminActivity, strBatchNoToRemove)
					PrintReceiptRepo.deleteAllData(this@AdminActivity)

					//All Product current batch are settled, clean up settlement record
					val valueHM = HashMap<Any, Any>()
					valueHM["value"] = "0"
					valueHM["is_settle"] = "false"

					val criteriaHM = HashMap<Any, Any>()
					SettlementSummaryRepo.updateData(this@AdminActivity, valueHM, criteriaHM)

					CoroutineScope(Dispatchers.IO).launch {
						store.putBoolean(PrefKeys.settlementBlock, false)
					}
					println("Settlement BLOCK = FALSE (END)")
				}
				Utils.DelayMili(5000)
				ToastMake(this@AdminActivity, "Successfully Cleared...", Toast.LENGTH_SHORT)
				Utils.DelayMili(1000)
				closeProgressDialog()
				helperLog.appendLine(helperLogClassName, "Clear All Batch finished")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
			}
		}.start()
	}

	private fun clearReversal(): Int {
		val revBatchCleared = ReversalBatchTableRepo.truncateTable(applicationContext)
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "Deleted revBatch:$revBatchCleared")
		} else {
			Utils.debugLogPrint(TAG, "Deleted revBatch:$revBatchCleared")
		}
		var iAFfectedCount = 0
		val refTag = "visam"
		for (tag in listOf("revDes", "revType", "revIsoDb", "revIsoOri", "revSchemeTag", "revSchemeId")) {
			if (IsoBatchLongInfoRepo.updateBatchLongInfo(applicationContext, "", tag, refTag)) iAFfectedCount++
		}
		val reversalResultMsg = if (iAFfectedCount <= 0) "ERR+WARNING: Failed to clear reversal" else "Reversal cleared successfully"
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, reversalResultMsg)
		} else {
			Utils.debugLogPrint(TAG, reversalResultMsg)
		}
		return iAFfectedCount
	}

	fun btnFunClearReversal(view: View) {
		object : Thread() {
			override fun run() {
				super.run()
				helperLog.appendLine(helperLogClassName, "Selected :: Clear Reversal [ADMIN]")
				startProgressDialog(view.context, "Clear reversal", "Clearing... Please wait")
				Utils.DelayMili(1000)
				val resp = clearReversal()
				if (resp > 0) {
					ToastMake(
						this@AdminActivity, "Successfully Clear Reversal", Toast.LENGTH_SHORT
					)
				} else {
					ToastMake(this@AdminActivity, "Fail To Clear Reversal", Toast.LENGTH_SHORT)
				}
				Utils.DelayMili(1000)
				closeProgressDialog()
				helperLog.appendLine(helperLogClassName, "Clear Reversal finished :: affected=$resp")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
			}
		}.start()
	}

	fun btnFuncClearSettlementReversal(view: View) {
		object : Thread() {
			override fun run() {
				super.run()
				helperLog.appendLine(helperLogClassName, "Selected :: Clear Settlement Reversal [ADMIN]")
				startProgressDialog(view.context, "Clear Settlement Reversal", "Clearing... ")
				ReversalBatchTableRepo.truncateTable(this@AdminActivity)
				ToastMake(this@AdminActivity, "Successfully Cleared...", Toast.LENGTH_SHORT)
				Utils.DelayMili(1000)
				closeProgressDialog()
				helperLog.appendLine(helperLogClassName, "Clear Settlement Reversal finished")
				helperLog.logToFile(EnumLogFileName.TerminaLog)
			}
		}.start()
	}

	override fun onDestroy() {
		super.onDestroy()
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, "AdminActivity OnDestroy :: admin screen ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}
}

class CustomCheckboxAdapter(
	context: Context,
	private val items: List<OxpayIntentModel>
) : ArrayAdapter<OxpayIntentModel>(context, android.R.layout.simple_list_item_single_choice, items) {

	override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
		val view = super.getView(position, convertView, parent) as CheckedTextView
		val item = items[position]

		view.text = item.name

		if (!item.isInstalled) {
			view.isEnabled = false
			view.setTextColor(Color.GRAY)
			view.isChecked = false
			view.isClickable = false
		} else {
			view.isEnabled = true
			view.setTextColor(Color.BLACK)
		}

		return view
	}
}
