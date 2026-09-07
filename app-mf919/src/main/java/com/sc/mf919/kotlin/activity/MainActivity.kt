package com.sc.mf919.kotlin.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sc.mf919.BuildConfig
import com.sc.mf919.R
import com.sc.mf919.java.activity.CubeActivity
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919.java.utils.PermissionUtils
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919.kotlin.database.repo.DenominationListRepo
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getAcquirerSetting
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getAppVersion
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getExternalStoragePaths
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getMerchantInfo
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getMigrationVersion
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.initialFiles
import com.sc.mf919.kotlin.helper_common.ServiceHolder.Companion.setMigrationVersion
import com.sc.mf919.kotlin.helper_common.TmsHelper
import com.sc.mf919.kotlin.helper_common.TmsHelper.checkIsConnectedWifi
import com.sc.mf919.kotlin.helper_common.TmsHelper.getInjectionKey
import com.sc.mf919.kotlin.helper_common.TmsHelper.getMerchantConfiguration
import com.sc.mf919.kotlin.helper_common.TmsHelper.getTerminalConfiguration
import com.sc.mf919.kotlin.helper_common.TmsHelper.sendDeviceInfo
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919.kotlin.migration.Migration123
import com.sc.mf919.kotlin.migration.Migration1310
import com.sc.mf919.kotlin.migration.Migration1312
import com.sc.mf919.kotlin.migration.Migration1313
import com.sc.mf919.kotlin.migration.Migration135
import com.sc.mf919.kotlin.migration.Migration137
import com.sc.mf919.kotlin.migration.Migration139
import com.sc.mf919.kotlin.migration.Migration1400
import com.sc.mf919.kotlin.migration.Migration1401
import com.sc.mf919.kotlin.migration.Migration1403
import com.sc.mf919.kotlin.migration.Migration1404
import com.sc.mf919.kotlin.migration.Migration1410
import com.sc.mf919.kotlin.migration.Migration1413
import com.sc.mf919.kotlin.migration.Migration1414
import com.sc.mf919.kotlin.migration.Migration2007
import com.sc.mf919.kotlin.migration.Migration2009
import com.sc.mf919.kotlin.migration.Migration2101
import com.sc.mf919.kotlin.migration.Migration2200
import com.sc.mf919.kotlin.migration.Migration2202
import com.sc.mf919.kotlin.migration.Migration2204
import com.sc.mf919.kotlin.migration.Migration2205
import com.sc.mf919.kotlin.migration.Migration2208
import com.sc.mf919.kotlin.migration.Migration2212
import com.sc.mf919.kotlin.migration.Migration2213
import com.sc.mf919.kotlin.migration.Migration2215
import com.sc.mf919.kotlin.migration.Migration2216
import com.sc.mf919.kotlin.migration.Migration2217
import com.sc.mf919.kotlin.migration.Migration2221
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.bottomActionBarEvent
import helpers.HelperCommon.Companion.getSession
import helpers.HelperLog
import helpers.LogSessionMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import com.sc.mf919.kotlin.helper_common.CounterGuard
import helpers.StorageGuard


class MainActivity : AppCompatActivity() {
	private val TAG = "MainActivity"
	lateinit var helperLogClassName: String
	lateinit var helperLog: HelperLog
	private var doubleBackToExitPressedOnce = false
	private var prefs: SharedPreferences? = null
	var cube: CubeActivity? = null

	/**
	 * MainActivity is the app's first screen, so a good part of onCreate runs before the helper
	 * layer (HelperCommon.context / Helper.Initialize) is ready and therefore before [helperLog]
	 * can be constructed. Calls from those points, and from the static
	 * [Companion.runMigrationFunction], fall back to the old Timber path rather than throwing on
	 * an uninitialised lateinit. Same shape as EmvActivity.logEmv.
	 */
	private fun logMain(msg: String) {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperLogClassName, msg)
		} else {
			Utils.debugLogPrint(TAG, msg)
		}
	}

	private fun flushMainLog() {
		if (this::helperLog.isInitialized) {
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
	}

	companion object {
		private const val TAG = "Main"

		/**
		 * Guards the one-shot startup task in [loadTask]: the first caller claims it, a concurrent
		 * caller waits for the claim to clear. Written on the UI thread and on the startup IO
		 * coroutine, and also cleared by AttendActivity / AttendDenominationActivity /
		 * AttendActivityOxpay once the home screen is up, so it needs @Volatile.
		 */
		@Volatile
		var isFirstRun = false

		/**
		 * Ceiling on how long a second caller will wait for the claim above. The claimant can fail
		 * to clear it (activity destroyed mid-load), and an unbounded wait meant that MainActivity
		 * never navigated at all. Sized above the worst realistic startup: 30s migration latch plus
		 * config download, key injection and sign-on on a slow link.
		 */
		private const val STARTUP_WAIT_TIMEOUT_MS = 120_000L

		//lateinit var instance: MainActivity
		//	private set

		fun runMigrationFunction(migrationLatch: CountDownLatch?) {
			var currVersion = getMigrationVersion()
			val latestVersion = getAppVersion().replace(".", "").toInt()
			runBlocking {
				launch(Dispatchers.IO) {
					if (currVersion < latestVersion) {
						if (currVersion < 123) {
							val m123 = Migration123()
							m123.startMigrate123()
							currVersion = 123
							setMigrationVersion(currVersion)
						}

						if (currVersion < 135) {
							val m135 = Migration135()
							m135.startMigrate135()
							currVersion = 135
							setMigrationVersion(currVersion)
						}

						if (currVersion < 137) {
							val m137 = Migration137()
							m137.startMigrate137()
							currVersion = 137
							setMigrationVersion(currVersion)
						}

						if (currVersion < 139) {
							val m139 = Migration139()
							m139.startMigrate139()
							currVersion = 139
							setMigrationVersion(currVersion)
						}
						if (currVersion < 1310) {
							val m1310 = Migration1310()
							m1310.startMigration()
							currVersion = 1310
							setMigrationVersion(currVersion)
						}
						if (currVersion < 1312) {
							val m1312 = Migration1312()
							m1312.startMigration()
							currVersion = 1312
							setMigrationVersion(currVersion)
						}
						if (currVersion < 1313) {
							val m1313 = Migration1313()
							m1313.startMigration()
							currVersion = 1313
							setMigrationVersion(currVersion)
						}
						if (currVersion < 1400) {
							val m1400 = Migration1400()
							m1400.startMigration()
							currVersion = 1400
							setMigrationVersion(currVersion)
						}
						if (currVersion < 1401) {
							val m1401 = Migration1401()
							m1401.startMigration()
							currVersion = 1401
							setMigrationVersion(currVersion)
						}
						if (currVersion < 1403) {
							val m1403 = Migration1403()
							m1403.startMigration()
							currVersion = 1403
							setMigrationVersion(currVersion)
						}
						if (currVersion < 1404) {
							val m1404 = Migration1404()
							m1404.startMigration()
							currVersion = 1404
							setMigrationVersion(currVersion)
						}
						if (currVersion < 1410) {
							val m1410 = Migration1410()
							m1410.startMigration()
							currVersion = 1410
							setMigrationVersion(currVersion)
						}
						if (currVersion < 1413) {
							val m1413 = Migration1413()
							m1413.startMigration()
							currVersion = 1413
							setMigrationVersion(currVersion)
						}
						if (currVersion < 1414) {
							val m1414 = Migration1414()
							m1414.startMigration()
							currVersion = 1414
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2007) {
							val m2007 = Migration2007()
							m2007.startMigration()
							currVersion = 2007
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2009) {
							val m2009 = Migration2009()
							m2009.startMigration()
							currVersion = 2009
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2101) {
							val m2101 = Migration2101()
							m2101.startMigration()
							currVersion = 2101
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2200) {
							val m2200 = Migration2200()
							m2200.startMigration()
							currVersion = 2200
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2202) {
							val m2202 = Migration2202()
							m2202.startMigration()
							currVersion = 2202
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2204) {
							val m2204 = Migration2204()
							m2204.startMigration()
							currVersion = 2204
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2205) {
							val m2205 = Migration2205()
							m2205.startMigration()
							currVersion = 2205
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2208) {
							val m2208 = Migration2208()
							m2208.startMigration()
							currVersion = 2208
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2212) {
							val m2212 = Migration2212()
							m2212.startMigration()
							currVersion = 2212
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2213) {
							val m2213 = Migration2213()
							m2213.startMigration()
							currVersion = 2213
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2215) {
							// Special Handling for 2215 to take previous Migration 2212 and 2213 quick fix on Production Issue
							val m2215Step1 = Migration2212()
							m2215Step1.startMigration()
							val m2215Step2 = Migration2213()
							m2215Step2.startMigration()

							val m2215 = Migration2215()
							m2215.startMigration()
							currVersion = 2215
							setMigrationVersion(currVersion)
						}
						if(currVersion < 2216) {
							val m2216 = Migration2216()
							m2216.startMigration()
							currVersion = 2216
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2217) {
							val m2217 = Migration2217()
							m2217.startMigration()
							currVersion = 2217
							setMigrationVersion(currVersion)
						}
						if (currVersion < 2221) {
							val m2221 = Migration2221()
							m2221.startMigration()
							currVersion = 2221
							setMigrationVersion(currVersion)
						}
						setMigrationVersion(latestVersion)
					}
					// new features
					migrationLatch?.countDown()
				}
			}
		}
	}


	@RequiresApi(Build.VERSION_CODES.O)
	override fun onCreate(savedInstanceState: Bundle?) {
		// Marks the app as starting up, which makes HTTPServer refuse incoming ECR requests until
		// startup finishes. Set as the first statement because the HTTP server keeps running
		// across a MainActivity relaunch and can be asked for a transaction immediately.
		ServiceHolder.appFreshLoad = true
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			setTurnScreenOn(true)
			setShowWhenLocked(true)
			val myKM = this.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				myKM.requestDismissKeyguard(this,null)
			} else {
				getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
			}
		}
		setTheme(R.style.splashScreenTheme)
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		val granted = checkPermission()
		helpers.HelperCommon.context = applicationContext
		prefs = getSharedPreferences(packageName, MODE_PRIVATE)
		Helper.getInstance().Initialize(applicationContext)

		// Earliest point the helper layer is ready, so this is the earliest point a HelperLog can
		// be built. Anything above this line has no instance to log to and stays on Timber.
		helperLogClassName = this::class.java.simpleName
		helperLog = HelperLog(
			HelperCommon.getSession(),
			checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			helperLogClassName,
			helperLogClassName,
			"App Startup"
		)
		helperLog.appendLine(helperLogClassName, "App startup :: MainActivity opened")

		// Once-per-start low-space notice, so a filling disk is visible in the logs BEFORE it starts
		// refusing transactions. In the Aisino incident the OS had been warning for 40 minutes and the
		// app had no way to see it. See obsidian FIX-2026-08-04 (D2).
		StorageGuard.warnIfLow(applicationContext)

		val closeFlag = intent.getBooleanExtra("closeFlag", false)
		if (closeFlag) {
			closeApplication()
		}

		cube = CubeActivity()
		Utils.cpAssetFile("termInfo.txt")
		Utils.cpAssetFile("merchantInfo.txt")
		Utils.cpAssetFile("isoengine.ini")
		Utils.cpAssetFile("tms.txt")
		Utils.cpAssetFile("tmsUrl.ini")
		initialFiles()
		val intent2 = Intent("com.morefun.homekey")
		intent2.setPackage("com.morefun.MFFramework")
		intent2.putExtra("value", "0")
		sendBroadcast(intent2)

		if (Utils.getInstallApk() != null) {
			val filename = Utils.getInstallApk()
			Utils.removeInstallApk(filename)
			updateFile(filename)
		} else {
			helperLog.appendLine(helperLogClassName, "No pending APK install :: normal startup")
		}

		println("granted :: $granted")
		helperLog.appendLine(helperLogClassName, "Runtime permissions granted :: $granted")
		if (granted) {
			startAttended()
		} else {
			// loadTask(), which clears appFreshLoad when startup finishes, only runs once
			// permissions are granted. Clear it here so the ECR guard does not refuse every
			// request while the permission dialog is unanswered.
			helperLog.appendLine(helperLogClassName, "Permissions not granted :: clearing appFreshLoad so ECR is not gated indefinitely")
			ServiceHolder.appFreshLoad = false
		}
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})
	}

	//private boolean isMyServiceRunning(Class<?> serviceClass) {
	private fun isMyServiceRunning() {
		val intent = Intent(applicationContext, AppServices::class.java)
		val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
		for (service in manager.getRunningServices(Int.MAX_VALUE)) {
			if (AppServices::class.java.name == service.service.className) {
				// appFreshLoad is set at the top of onCreate, which runs well before this.
				ServiceHolder.uploadingReceipt = false
				ServiceHolder.autoSettlementIsRunning = false
				stopService(intent)
			}
		}
		startService(intent)
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
		return if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_MENU) {
			true
		} else super.onKeyDown(keyCode, event)
	}

	private fun passWordActivity() {
		closeApplication()
	}

	private fun closeApplication() {
		logMain("User Cancel :: closing application")
		// Marks the session as a clean exit, so the next startup does not report this as a kill.
		LogSessionMarker.closeSession("USER EXIT")
		// exitProcess() below kills the process, so the async TerminaLog queue may never drain.
		// TerminaLogException is written synchronously, so the segment survives the kill.
		if (this::helperLog.isInitialized) {
			helperLog.logToFile(EnumLogFileName.TerminaLogException)
		}
		val dbModelTerminalConfig = getTerminalConfig()
		if (getBooleanValue(dbModelTerminalConfig, "FORCE_LOCK_HOME")) {
			bottomActionBarEvent(this, "0")
		}
		finishAffinity()
		exitProcess(0)
	}

	@RequiresApi(Build.VERSION_CODES.O)
	fun goAttend(view: View?) {
		startAttended()
	}

	@RequiresApi(Build.VERSION_CODES.O)
    private fun startAttended() {
		lifecycleScope.launch {
			loadTask()
		}
	}


	fun checkPermission(): Boolean {
		val a: Boolean
		val clickId = 2
		val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
		a = PermissionUtils.checkPermissions(this@MainActivity, permissions, clickId)
		// Runs before helperLog exists on the first onCreate pass, so go through logMain.
		logMain("Permission check result :: $a")
		return a
	}

	@RequiresApi(Build.VERSION_CODES.O)
	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<String>,
		grantResults: IntArray
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (PermissionUtils.checkPermissionResult(permissions, grantResults)) {
			// onCreate cleared appFreshLoad (see comment there) so the ECR guard wouldn't refuse
			// requests indefinitely while the dialog sat unanswered. Restore it here so loadTask()
			// actually runs the fresh-load block (TMS config download, sign-on) instead of skipping
			// it -- otherwise a device that needs this dialog on first boot never configures itself.
			ServiceHolder.appFreshLoad = true
			startAttended()
		}
	}

	override fun onResume() {
		super.onResume()
		val intent = intent
		val intentAction = intent.action
		if (prefs!!.getBoolean("firstrun", true)) {
			logMain("App Start")
		}
		if (intentAction == "UpdateApp") {
			val newIntent = Intent(this, UpdateAppInitialDialog::class.java)
			newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			logMain("Validation passed :: navigate -> UpdateAppInitialDialog")
			flushMainLog()
			startActivity(newIntent)
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		logMain("onNewIntent :: action=${intent.action}")
	}

	fun customOnBackPress() {
		if (doubleBackToExitPressedOnce) {
			logMain("User Cancel :: back pressed twice, exiting app")
			passWordActivity()
			return
		}
		doubleBackToExitPressedOnce = true
		logMain("Back pressed once :: waiting for confirmation")
		Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show()
		Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
	}

	@SuppressLint("SdCardPath")
	private fun updateFile(filename: String) {
		val intent = Intent(Intent.ACTION_VIEW)
		val path = getExternalStoragePaths() + "/files/" + filename
		intent.setDataAndType(Uri.fromFile(File(path)), "application/vnd.android.package-archive")
		intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK // without this flag android returned a intent error!
		logMain("Validation passed :: navigate -> APK installer for $filename")
		flushMainLog()
		startActivity(intent)
		finish()
	}

	@RequiresApi(Build.VERSION_CODES.O)
    suspend fun loadTask() {
		val builder = AlertDialog.Builder(this@MainActivity)
		builder.setCancelable(false)
		builder.setView(R.layout.activity_layoutloadingdialog)
		val dialog: AlertDialog = builder.create()
		dialog.show()

		// Set only by the caller that actually claims the startup task, so the finally below
		// releases the claim it took rather than one belonging to another caller.
		var ownsStartupClaim = false
		var startupJob: Job? = null
		try {
			if(!isFirstRun) {
				isFirstRun = true
				ownsStartupClaim = true

				val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
					isMyServiceRunning()
					val migrationLatch = CountDownLatch(1)
					runMigrationFunction(migrationLatch)
					migrationLatch.await(30, TimeUnit.SECONDS)

					// After the schema is healed and migrations have run, but before any
					// transaction can start: if the DB was recopied from the asset its counters are
					// back at 000001, which means reusing invoice/STAN numbers already sent to the
					// host. Reapply the SharedPreferences high-water marks, which survive a DB wipe.
					CounterGuard.restore(applicationContext)

					if (ServiceHolder.appFreshLoad) {
						val log = HelperLog(
							getSession(),
							checkIsConnectedWifi(applicationContext),
							Utils.getIPAddress(),
							"Main Activity Fresh Load",
							this@MainActivity.javaClass.simpleName,
							this@MainActivity.javaClass.name
						)

						log.appendLine(this@MainActivity.javaClass.simpleName, "run: AutoTmsUpload")
						DenominationListRepo.truncateTable(applicationContext)
						TmsHelper.checkServerDateTime(log, applicationContext)
						sendDeviceInfo(log, applicationContext)
						var configurationMessage = "Failed to download configuration."
						var proceedSignOn = getTerminalConfiguration(log, applicationContext)
						var remoteDownloadKey = false
						if (proceedSignOn) {
							val dbModelTerminalConfig = getTerminalConfig()
							remoteDownloadKey = getBooleanValue(dbModelTerminalConfig, "REMOTE_DOWNLOAD_BSN_KEY")
						}

						if (getMerchantConfiguration(log, applicationContext)) {
							val dbModelMerchantConfig = getMerchantInfo()
							val acqMid = getSafeValue(dbModelMerchantConfig, "AcqMid")
							val acqTid = getSafeValue(dbModelMerchantConfig, "AcqTid")
							val tpdu = getSafeValue(dbModelMerchantConfig, "TPDU")
							val nii = getSafeValue(dbModelMerchantConfig, "NII")
							//VISAM
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqMid, "mid", "visam")
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqTid, "tid", "visam")
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpdu, "isoTpduHeader", "visam")
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpdu, "isoTpduHeaderTle", "visam")
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, nii, "nii", "visam")
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, nii, "niiTle", "visam")
							//MCCS
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqMid, "mid", "mccs")
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, acqTid, "tid", "mccs")
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpdu, "isoTpduHeader", "mccs")
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, tpdu, "isoTpduHeaderTle", "mccs")
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, nii, "nii", "mccs")
							IsoBatchInfoRepo.updateBatchInfo(applicationContext, nii, "niiTle", "mccs")
						} else proceedSignOn = false
						val (acqName, requireSignOn) = getAcquirerSetting()
						if (proceedSignOn && (remoteDownloadKey || requireSignOn)) {
							proceedSignOn = getInjectionKey(log, applicationContext)
						}

						if(proceedSignOn){
							if(requireSignOn){
								TransData.reset(applicationContext)
								TransData.acqCode = acqName
								//val iSignOnResp = cube!!.proceedSignOn(applicationContext)
								val iSignOnResp = IsoActivity.processSignOn(applicationContext, log)
								if (iSignOnResp) {
									configurationMessage = "Successfully download configuration"
								}
							} else {
								configurationMessage = "Successfully download configuration"
							}
						}
						ServiceHolder.appFreshLoad = false
						val finalMessage = configurationMessage
						runOnUiThread {
							Toast.makeText(applicationContext, finalMessage, Toast.LENGTH_LONG).show()
						}
						isFirstRun = false

						log.logToFile(EnumLogFileName.TerminaLog)
					}
				}
				startupJob = startCoroutine
				startCoroutine.join()
			} else {
				// Bounded: the claimant can die without clearing isFirstRun, and this used to spin
				// forever when that happened, leaving the terminal on the loading dialog.
				val completed = withTimeoutOrNull(STARTUP_WAIT_TIMEOUT_MS) {
					while (isFirstRun) {
						delay(500)
					}
					true
				}
				if (completed == null) {
					logMain("Startup wait timed out after ${STARTUP_WAIT_TIMEOUT_MS}ms :: proceeding without the other load")
				}
			}
			// Backstop: fresh start is over here, however the block above ended. The early clear inside
			// the config block only runs if that block completes -- and appFreshLoad now gates the ECR
			// entry point, so a flag left set would refuse every HTTP transaction indefinitely rather
			// than just leaving the terminal unconfigured. Cheap to set twice; expensive to never set.
			// Kept here as well as in the finally so the flag is already clear before the handoff below.
			ServiceHolder.appFreshLoad = false
			dialog.dismiss()
			val dbModelTerminalConfig = getTerminalConfig()
			var newIntent = HelperCommon.getHomeScreenIntent(this@MainActivity, dbModelTerminalConfig)
			val runningFlavor = BuildConfig.FLAVOR
			if(runningFlavor == "oxpay") {
				newIntent = Intent(this@MainActivity, AttendActivityOxpay::class.java)
			}
			newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
			logMain("Validation passed :: navigate -> ${newIntent.component?.shortClassName}")
			flushMainLog()
			startActivity(newIntent)
			finish()
		} finally {
			// Reached on the happy path, on a throw, and on cancellation -- loadTask runs in
			// lifecycleScope, so destroying MainActivity mid-load cancels it at the join() above and
			// none of the straight-line clears run. appFreshLoad gates the ECR entry point and has
			// no staleness ceiling, so a flag left set here refuses every transaction for the life
			// of the process.
			ServiceHolder.appFreshLoad = false
			if (ownsStartupClaim) {
				// The startup coroutine is not a child of lifecycleScope, so it outlives this
				// activity. Cancel it before releasing the claim, otherwise the next MainActivity
				// takes the claim and runs a second TMS download and sign-on alongside the orphan.
				// A no-op when the job already completed normally.
				startupJob?.cancel()
				isFirstRun = false
			}
			runCatching { if (dialog.isShowing) dialog.dismiss() }
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		logMain("MainActivity OnDestroy :: startup screen ended")
		flushMainLog()
	}

//	companion object {
//		private const val TAG = "Main"
//		var isFirstRun = false
//	}
}
