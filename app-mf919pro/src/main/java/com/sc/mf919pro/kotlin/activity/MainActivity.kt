package com.sc.mf919pro.kotlin.activity
import helpers.CrashHandler

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Service
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.device.DeviceHelper
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.data_enum.variables.TransDataViewModel
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import database.DbHandler
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig.Companion.getBooleanValue
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.fragment.AdminFragment
import com.sc.mf919pro.kotlin.fragment.AttendFragment
import com.sc.mf919pro.kotlin.fragment.MerchantConfigFragment
import com.sc.mf919pro.kotlin.fragment.SettingsFragment
import com.sc.mf919pro.kotlin.fragment.TerminalConfigFragment
import com.sc.mf919pro.kotlin.fragment.VoidOptionFragment
import com.sc.mf919pro.kotlin.helper_common.AppBus
import com.sc.mf919pro.kotlin.helper_common.CounterGuard
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.Helper
import tms.ReceiptReconciler
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getAcquirerSetting
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getAppVersion
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getMerchantInfo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getMigrationVersion
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder.Companion.getTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.TTSManager
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.UiEvent
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919pro.kotlin.helper_common.utils.PermissionUtils
import helpers.StorageGuard
import com.sc.mf919pro.kotlin.migration.Migration1001
import com.sc.mf919pro.kotlin.migration.Migration1002
import com.sc.mf919pro.kotlin.migration.Migration1004
import com.sc.mf919pro.kotlin.migration.Migration1005
import enums.EnumLogFileName
import helpers.AsyncLogWriter
import helpers.CrashState
import helpers.HelperCommon
import helpers.HelperCommon.Companion.getSession
import helpers.HelperLog
import helpers.LogSessionMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import com.sc.mf919pro.kotlin.database.repo.DenominationListRepo

class MainActivity : ActivityBase() {
    private val TAG = "MainActivity"
    private var prefs: SharedPreferences? = null
    private var pendingNav: Pair<Int, Bundle?>? = null

    val transDataViewModel: TransDataViewModel by viewModels()
    private lateinit var bottomGroup: View  // container for your buttons

    val transData: TransData
        get() = transDataViewModel.data

    companion object {
        private const val TAG = "Main"

        /**
         * The destinations that mean "the terminal is up and usable". Reaching any of them clears
         * the consecutive-crash counter -- see CrashState. Mirrors the three start destinations
         * setupNavigation can choose between.
         */
        /** Id of the destination currently shown, or 0 before the graph is set. */
        @Volatile
        @JvmStatic
        var currentDestinationId: Int = 0
            internal set

        private val HOME_DESTINATIONS = setOf(
            R.id.attendFragment,
            R.id.unAttendFragment,
            R.id.attendDenominationFragment
        )

        fun runMigrationFunction(migrationLatch: CountDownLatch?) {
            // Ordering: open the DB FIRST. copyPreloadedDatabaseIfNeeded runs inside
            // getInstance and is what resets MIGRATE_VER after a recopy; reading the version
            // before that returns the pre-wipe value, so the chain is skipped on exactly the
            // launch that needed it and only replays on the next one. Measured on device:
            // "MIGRATE_VER reset 1003 -> 0" logged, yet zero migrations ran that launch.
            DbHandler.getInstance(ServiceHolder.getContext())
            var currVersion = getMigrationVersion()
            val latestVersion = getAppVersion().replace(".", "").toInt()
            runBlocking {
                launch(Dispatchers.IO) {
                    if (currVersion < latestVersion) {
                        if (currVersion < 1001) {
                            val m1001 = Migration1001()
                            m1001.startMigration()
                            currVersion = 1001
                            ServiceHolder.setMigrationVersion(currVersion)
                        }
                        if (currVersion < 1002) {
                            val m1002 = Migration1002()
                            m1002.startMigration()
                            currVersion = 1002
                            ServiceHolder.setMigrationVersion(currVersion)
                        }
                        if (currVersion < 1004) {
                            val m1004 = Migration1004()
                            m1004.startMigration()
                            currVersion = 1004
                            ServiceHolder.setMigrationVersion(currVersion)
                        }
                        if (currVersion < 1005) {
                            val m1005 = Migration1005()
                            m1005.startMigration()
                            currVersion = 1005
                            ServiceHolder.setMigrationVersion(currVersion)
                        }
                        ServiceHolder.setMigrationVersion(latestVersion)
                    }
                    // new features
                    migrationLatch?.countDown()
                }
            }
        }
    }


    /*
     * Lifecycle logging for the app entry point. MainActivity's onCreate/onDestroy bracket the
     * whole session, so these lines are what a restart post-mortem reads first alongside
     * LogSessionMarker's "Previous run ::" verdict.
     *
     * by lazy rather than lateinit: this is touched from lifecycle callbacks and permission
     * results, and a lazy value can never throw UninitializedPropertyAccessException.
     */
    private val helperLogClassName: String = MainActivity::class.simpleName.toString()
    private val helperLog: HelperLog by lazy {
        HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Main Activity"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ECR stays closed until this load has finished. The HTTP listener is process-scoped and
        // outlives this activity, so isActive can still hold the previous screen's value here.
        ServiceHolder.activityLoading = true
        ServiceHolder.markStartupBegun()
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        val myKM = this.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        myKM.requestDismissKeyguard(this,null)

        super.onCreate(savedInstanceState)
        setTheme(R.style.AppTheme)
        setContentView(R.layout.activity_main)
        helperLog.appendLine(helperLogClassName, "MainActivity OnCreate :: app session starting")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val granted = checkPermission()
        HelperCommon.context = applicationContext
        prefs = getSharedPreferences(packageName, MODE_PRIVATE)
        Helper.getInstance().Initialize(applicationContext)
        requestDozeExemption()

        // IMPORTANT: cache intent nav request (don’t navigate yet)
        pendingNav = extractPendingNav(intent)
        // Force NavHostFragment to be created now (cold start fix)
        supportFragmentManager.executePendingTransactions()
        TTSManager.init(applicationContext)

        val closeFlag = intent.getBooleanExtra("closeFlag", false)
        if (closeFlag) {
            closeApplication()
        }

        val intent2 = Intent("com.morefun.homekey")
        intent2.setPackage("com.morefun.MFFramework")
        intent2.putExtra("value", "0")
        sendBroadcast(intent2)

        bottomGroup = findViewById(R.id.bottomNavWrapper)

        if (granted) {
            startAttended()
        } else {
            // loadTask() never runs on this path, so clear the flag here or ECR stays closed.
            ServiceHolder.activityLoading = false
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppBus.uiEvents.collect { event ->
                    when(event) {
                        is UiEvent.FragmentNavigation -> {
                            val navController = navController()
                            try {
                                navController.navigate(event.actionId, event.bundle)
                                true
                            } catch (e: IllegalArgumentException) {
                                // action not found from current destination, etc.
                                e.printStackTrace()
                                false
                            }
                        }
                        else -> {
                            // not required
                        }
                    }
                }
            }
        }
    }

    // Doze defers background network access for non-exempt apps, which delays
    // TMS calls and inbound ECR requests while the terminal idles. Asks once;
    // no-op when already exempt.
    @SuppressLint("BatteryLife")
    private fun requestDozeExemption() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (ex: Exception) {
            Utils.printErrorLog("MainActivity", "requestDozeExemption", ex.message)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        pendingNav = extractPendingNav(intent)
        // If nav already set up, you can apply immediately
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val navController = navHost?.navController
        val canNavigateNow = navController?.currentDestination != null

        if (canNavigateNow) {
            pendingNav?.let { (actionId, bundle) ->
                try {
                    navController?.navigate(actionId, bundle)
                    pendingNav = null
                } catch (e: Exception) {
                    e.printStackTrace()
                    // returnToCallerError(intent, "SHC999", "Navigation failed")
                    pendingNav = null
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        helperLog.appendLine(helperLogClassName, "MainActivity OnDestroy :: app session ending")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    override fun onStop() {
        super.onStop()
        MfHelper.closeAuxLcd()
        MfHelper.closeNfcUrlInterface()
    }

    override fun onResume() {
        super.onResume()
        if(DeviceHelper.application != null) {
            val bitmap = BitmapFactory.decodeResource(applicationContext.resources, R.mipmap.sharecomm_logo)
            MfHelper.showAuxLcdImg(bitmap)
        }
    }

    //private boolean isMyServiceRunning(Class<?> serviceClass) {
    private fun isMyServiceRunning() {
        val intent = Intent(applicationContext, AppServices::class.java)
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (AppServices::class.java.name == service.service.className) {
                ServiceHolder.appFreshLoad = true
                ServiceHolder.uploadingReceipt = false
                ServiceHolder.autoSettlementIsRunning = false
                stopService(intent)
            }
        }
        startService(intent)
    }

    private fun closeApplication() {
        // A genuine app exit: record it so the next startup does not report an unexplained kill.
        // Drain first -- exitProcess takes the daemon writer thread with it.
        helperLog.appendLine(helperLogClassName, "User Cancel :: closing application")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        LogSessionMarker.closeSession("MainActivity.closeApplication")
        AsyncLogWriter.drain()
        finishAffinity()
        exitProcess(0)
    }

    private fun startAttended() {
        lifecycleScope.launch {
            loadTask()
        }
    }

    fun checkPermission(): Boolean {
        val clickId = 2

        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        // Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val a = PermissionUtils.checkPermissions(this@MainActivity, permissions.toTypedArray(), clickId)
        Utils.debugLogPrint("TAG", "a=$a")
        return a
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (i in permissions.indices) {
            Utils.debugLogPrint(
                "PERM",
                "${permissions[i]} = ${grantResults[i]}"
            )
        }

        if (requestCode == 2) {
            if (PermissionUtils.checkPermissionResult(permissions, grantResults)) {
                Utils.debugLogPrint("MainActivity", "Permission Granted")
                startAttended()
            } else {
                Utils.debugLogPrint("MainActivity", "Permission Denied")
                ServiceHolder.activityLoading = false
            }
        }
    }

    suspend fun loadTask() {
        try {
            loadTaskInner()
        } finally {
            // Cleared only after setupNavigation(), which is what sets HTTPServer.isActive.
            ServiceHolder.activityLoading = false
        }
    }

    private suspend fun loadTaskInner() {
        val builder = AlertDialog.Builder(this@MainActivity)
        var dialog: AlertDialog? = null

        builder.setCancelable(false)
        builder.setView(R.layout.activity_layoutloadingdialog)
        dialog = builder.create()
        dialog.show()
        // A POS-driven launch carries a nav request and must not stop for a TMS refresh.
        val launchedByPos = pendingNav != null
        if(!ServiceHolder.startupTaskRunning && !launchedByPos) {
            ServiceHolder.startupTaskRunning = true
            ServiceHolder.markStartupBegun()
            val startCoroutine = CoroutineScope(Dispatchers.IO).launch {
                // Surface a filling disk before it starts refusing sales.
                StorageGuard.warnIfLow(applicationContext)
                isMyServiceRunning()
                val migrationLatch = CountDownLatch(1)
                runMigrationFunction(migrationLatch)
                migrationLatch.await(30, TimeUnit.SECONDS)

                // After the schema is healed and migrations have run, but before any
                // transaction can start: if the DB was recopied from the asset its counters are
                // back at 000001, which means reusing invoice/STAN numbers already sent to the
                // host. Reapply the SharedPreferences high-water marks, which survive a DB wipe.
                CounterGuard.restore(applicationContext)

                // Same as MF919: repair ReceiptUpload rows left unresolved when the process died
                // between the host response and updateReceiptInfo(). After the migration latch so the
                // DB is ready, and outside the appFreshLoad branch so it runs on every start.
                ReceiptReconciler.reconcileOrphanReceipts(applicationContext)

                if (ServiceHolder.appFreshLoad) {
                    val log = HelperLog(
                        getSession(),
                        TmsHelper.checkIsConnectedWifi(applicationContext),
                        Utils.getIPAddress(),
                        "Main Activity Fresh Load",
                        this@MainActivity.javaClass.simpleName,
                        this@MainActivity.javaClass.name
                    )

                    Utils.debugLogPrint(TAG, "run: AutoTmsUpload")
                    // The denomination list is only fetched from TMS when the local copy is empty, so
                    // without clearing it first a price or AUTO_SESSION change made on TMS never
                    // reaches a terminal that already has a list -- it keeps serving the cached one
                    // indefinitely. MF919 does this in the same place (MainActivity fresh load) and
                    // on the websocket UpdatePrice command; the port dropped both.
                    DenominationListRepo.truncateTable(applicationContext)
                    TmsHelper.checkServerDateTime(log, applicationContext)
                    TmsHelper.sendDeviceInfo(log, applicationContext)
                    var configurationMessage = "Failed to download configuration."
                    var proceedSignOn = TmsHelper.getTerminalConfiguration(log, applicationContext)
                    var remoteDownloadKey = false
                    if (proceedSignOn) {
                        val dbModelTerminalConfig = getTerminalConfig()
                        remoteDownloadKey = getBooleanValue(dbModelTerminalConfig, "REMOTE_DOWNLOAD_BSN_KEY")
                    }

                    if (TmsHelper.getMerchantConfiguration(log, applicationContext)) {
                        val dbModelMerchantConfig = getMerchantInfo()
                        //VISAM
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "AcqMid"), "mid", "visam")
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "AcqTid"), "tid", "visam")
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeader", "visam")
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeaderTle", "visam")
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "NII"), "nii", "visam")
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "NII"), "niiTle", "visam")

                        //MCCS
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "AcqMid"), "mid", "mccs")
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "AcqTid"), "tid", "mccs")
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeader", "mccs")
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "TPDU"), "isoTpduHeaderTle", "mccs")
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "NII"), "nii", "mccs")
                        IsoBatchInfoRepo.updateBatchInfo(applicationContext, getSafeValue(dbModelMerchantConfig, "NII"), "niiTle", "mccs")
                    } else proceedSignOn = false
                    val (acqName, requireSignOn) = getAcquirerSetting()
                    if (proceedSignOn && (remoteDownloadKey || requireSignOn)) {
                        proceedSignOn = TmsHelper.getInjectionKey(log, applicationContext)
                    }

                    if(proceedSignOn){
                        if(requireSignOn){
                            transData.reset()
                            transData.acqCode = acqName
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
                    log.logToFile(EnumLogFileName.TerminaLog)
                }
            }
            // Clears on completion, on a throw and on cancellation, so the flag cannot stay set.
            startCoroutine.invokeOnCompletion { ServiceHolder.startupTaskRunning = false }
            startCoroutine.join()
        }

        dialog.dismiss()

        setupNavigation()
        setupBottomNav()
    }

    private fun setupNavigation() {
        val navController = navController()
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)

        val dbTerminalConfig = ServiceHolder.getTerminalConfig()
        if(getBooleanValue(dbTerminalConfig, "DENOMINATION")) {
            navGraph.setStartDestination(R.id.attendDenominationFragment)
        } else if (getBooleanValue(dbTerminalConfig, "UNATTENDED_MODE")) {
            navGraph.setStartDestination(R.id.unAttendFragment)
        } else {
            navGraph.setStartDestination(R.id.attendFragment)
        }
        navController.graph = navGraph

        // This app is single-activity, so "which screen was showing" is a nav destination, not an
        // activity class. Recording it here is what makes a crash report actionable, and reaching
        // a home destination is the signal that this start worked -- see CrashState.markHealthy.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Published for mdb.MdbController via ProMdbHost.isVendingScreenReady(): a VMC vend
            // price has nowhere to go unless the denomination screen is the one on top.
            currentDestinationId = destination.id
            val name = try {
                resources.getResourceEntryName(destination.id)
            } catch (e: Exception) {
                "destination-${destination.id}"
            }
            CrashHandler.foregroundActivity = name
            if (destination.id in HOME_DESTINATIONS) {
                CrashState.markHealthy()
            }
        }

        // ✅ NOW graph has a current destination
        pendingNav?.let { (actionId, bundle) ->
            try {
                navController.navigate(actionId, bundle)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            pendingNav = null
            //if (!ok) { returnToCallerError(intent, "SHC999", "Navigation failed") }
        }

        //Set which fragment to enable BottomNav
        //val httpServer = HTTPServer.getInstance()
        val bottomVisibleDestinations = setOf(
            R.id.attendFragment,
            R.id.settingsFragment,
            R.id.adminFragment,
            R.id.merchantConfig,
            R.id.terminalConfig,
            R.id.voidOptionFragment,
            R.id.contactUsFragment,
        )
        val httpActiveDestinations = setOf(
            R.id.attendFragment,
            R.id.unAttendFragment,
            R.id.transactionResultFragment,
            R.id.transactionResultQrFragment
        )
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val destinationId = destination.id
            /*when (destination.id) {
                R.id.attendFragment,
                R.id.settingsFragment,
                R.id.adminFragment,
                R.id.merchantConfig,
                R.id.terminalConfig,
                R.id.voidOptionFragment -> {
                    bottomGroup.visibility = View.VISIBLE
                }
                else -> {
                    bottomGroup.visibility = View.GONE
                }
            }*/
            CoroutineScope(Dispatchers.Main).launch {
                delay(50)
                bottomGroup.visibility = if (destinationId in bottomVisibleDestinations) View.VISIBLE else View.GONE
            }
            /*when (destination.id) {
                R.id.attendFragment,
                R.id.transactionResultFragment -> {
                    httpServer.isActive = true
                }
                else -> {
                    httpServer.isActive = false
                }
            }*/
            HTTPServer.getInstance().isActive = destinationId in httpActiveDestinations
        }

        //REVAMP
        /*supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    if (f is androidx.fragment.app.DialogFragment) return
                    when (f) {
                        is AttendFragment,
                        is SettingsFragment,
                        is AdminFragment,
                        is MerchantConfigFragment,
                        is TerminalConfigFragment,
                        is VoidOptionFragment -> bottomGroup.visibility = View.VISIBLE
                        else -> bottomGroup.visibility = View.GONE
                    }
                }
            }, true
        )*/
        //REVAMP
    }

    private fun setupBottomNav() {
        val navController = navController()
        val termInfoText = "Serial No. ${ServiceHolder.getTerminalSerialNumber()}\nVersion ${getAppVersion()} "
        findViewById<TextView>(R.id.termInfoTextView).text = termInfoText

        // Home button → AttendFragment
        findViewById<View>(R.id.homeBtnContainer).setOnClickListener {
            val dbTerminalConfig = ServiceHolder.getTerminalConfig()
            var homeFragment =  R.id.attendFragment
            if(getBooleanValue(dbTerminalConfig, "DENOMINATION")) {
                homeFragment = R.id.attendDenominationFragment
            } else if (getBooleanValue(dbTerminalConfig, "UNATTENDED_MODE")) {
                homeFragment = R.id.unAttendFragment
            }

            navController.navigate(
                homeFragment,
                null,
                NavOptions.Builder()
                    .setPopUpTo(homeFragment, inclusive = false) // clear back stack
                    .setLaunchSingleTop(true) // don’t reload if already there
                    .build()
            )
        }

        // MF Desk button
        findViewById<View>(R.id.mfDeskBtn).setOnClickListener {
            val deskIntent = applicationContext.packageManager.getLaunchIntentForPackage("com.morefun.desk")
            if(deskIntent != null) {
                //MfHelper.lockStatusBarAndNavigation(false)
                //deskIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // important if using context
                startActivity(deskIntent)
            }
        }

        // Settings button
        findViewById<View>(R.id.settingBtn).setOnClickListener {
            navController.navigate(
                R.id.settingsFragment,
                null,
                NavOptions.Builder()
                    .setPopUpTo(R.id.settingsFragment, false)
                    .setLaunchSingleTop(true)
                    .build()
            )
        }
    }

    private fun extractPendingNav(intent: Intent): Pair<Int, Bundle?>? {
        val actionId = intent.getIntExtra("nav_action_id", 0)
        if (actionId == 0) return null

        val bundle = intent.getBundleExtra("nav_bundle")
        // avoid double-processing
        intent.removeExtra("nav_action_id")
        intent.removeExtra("nav_bundle")

        return actionId to bundle
    }

    private fun navController(): androidx.navigation.NavController {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return navHost.navController
    }

    fun Bundle.toReadableString(): String {
        if (isEmpty) return "Bundle{}"

        return buildString {
            append("Bundle{\n")
            for (key in keySet()) {
                append("  $key = ${get(key)}\n")
            }
            append("}")
        }
    }
}