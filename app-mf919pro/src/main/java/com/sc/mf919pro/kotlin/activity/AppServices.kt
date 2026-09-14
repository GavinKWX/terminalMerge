package com.sc.mf919pro.kotlin.activity

import android.app.*
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.database.model.DbModelIsoBatchInfo
import com.sc.mf919pro.kotlin.database.model.DbModelLastSettlement
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.model.DbModelQrPayBrandGet
import com.sc.mf919pro.kotlin.database.model.DbModelSettlementSummary
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.LastSettlementRepo
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.database.repo.SettlementSummaryRepo
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import com.sc.mf919pro.kotlin.helper_common.*
import env.EnvironmentManager
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
import com.sc.mf919pro.kotlin.scheduler.AutoTmsUploadScheduler
import com.sc.mf919pro.kotlin.scheduler.HouseKeepingReceiptUploadScheduler
import com.sc.mf919pro.kotlin.scheduler.SettlementRecoveryScheduler
import com.sc.mf919pro.kotlin.scheduler.TmsReceiptUploadScheduler
import com.sc.mf919pro.kotlin.scheduler.TmsUploadLogScheduler
import enums.EnumLogFileName
import helpers.AsyncLogWriter
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AppServices: Service() {
    companion object {
        const val TAG = "AppService"
        private const val CHANNEL_ID = "BSCHANNEL_ID"
        const val UPLOAD_LOG_TAG = "SCHEDULER_UPLOAD_LOG"
        const val RECEIPT_UPLOAD_TAG = "SCHEDULER_RECEIPT_UPLOAD"
        const val HOUSEKEEP_JOB_TAG = "SCHEDULER_HOUSE_KEEPING_JOB"
        const val AUTO_TMS_UPLOAD_TAG = "SCHEDULER_AUTO_TMS_UPLOAD"
        const val LAST_SETTLE_JOB_TAG = "SCHEDULER_LAST_SETTLEMENT_JOB"
        const val LAST_SETTLE_TAG = "LAST_SUCCESS_SETTLEMENT"

        fun receiptUploadToTms(mContext: Context) {
            val sbLog = HelperLog.init("Starting Immediate Job - Receipt Upload to TMS")
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val uploadWorkRequest = OneTimeWorkRequest.Builder(TmsReceiptUploadScheduler::class.java)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(mContext).enqueue(uploadWorkRequest)

            HelperLog.appendLine(sbLog, "Immediate Receipt Upload Successfully")
            HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
        }

        const val ACTION_RUN_AUTO_SETTLE = "RUN_AUTO_SETTLE"
        const val ACTION_RESTART_AUTO_SETTLE_TICKER = "RUN_AUTO_SETTLE_TICKER"
        fun triggerAutoSettle(context: Context, actionString: String) {
            val intent = Intent(context, AppServices::class.java).apply {
                action = actionString
            }
            context.startService(intent)
        }
    }

    //REVAMP
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var autoSettleJob: Job? = null
    private var httpServerStarted = false
    private var workScheduled = false
    //private var autoSettleTicker = 30
    //REVAMP

    private lateinit var mContext: Context
    private var wifiLock: WifiManager.WifiLock? = null
    private var autoSettleTicker = 30
    private var defaultSettleCountdownSecond = 60
    private var mDate: Date? = null
    private var timerStopper = false
    private var environmentManager: EnvironmentManager? = null

    override fun onBind(intent: Intent?): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun onCreate() {
        super.onCreate()
        mContext = this@AppServices
        acquireWifiLock()
        Utils.debugLogPrint(TAG, "Service is created")
    }

    // Keeps the Wi-Fi radio out of 802.11 power-save while the HTTP/WebSocket
    // servers must stay reachable. Without this, idle/screen-off puts the radio
    // to sleep and inbound/outbound calls are buffered at the AP until the user
    // wakes the screen. FULL_HIGH_PERF (not FULL_LOW_LATENCY) is required here
    // because low-latency mode only applies while the screen is on.
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                return
            }
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Utils.debugLogPrint(TAG, "WifiLock acquired")
        } catch (ex: Exception) {
            Utils.printErrorLog(TAG, "acquireWifiLock", ex.message)
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Utils.debugLogPrint(TAG, "WifiLock released")
            }
        } catch (ex: Exception) {
            Utils.printErrorLog(TAG, "releaseWifiLock", ex.message)
        }
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = getNotification(CHANNEL_ID)
        startForeground(1, notification)

        if (environmentManager == null) {
            val sharedPreferences: SharedPreferences = Helper.getInstance().getPrefs(mContext)
            environmentManager = EnvironmentManager(sharedPreferences)
        }

        if (!httpServerStarted) {
            HTTPServer.getInstance().startHttpServer()
            httpServerStarted = true
        }

        // On app relaunch the OLD service instance's onDestroy can run after the new
        // session already started the listeners (its stopWebSocketServer kills the
        // freshly started WebSocket server). Re-arm here; both calls no-op when the
        // configured connection method does not match or the server is already running.
        HTTPServer.startServeCable()
        HTTPServer.startWebSocketServer()

        if (!workScheduled) {
            // Avoid cancelAllWork() here unless you really want to cancel every work in app
            scheduleUploadLogToTms()
            scheduleReceiptUploadToTms()
            scheduleHouseKeepReceiptUpload()
            scheduleAutoTmsUpload()
            scheduleAutoSettlementTimeCheck()
            workScheduled = true
        }

        when (intent?.action) {
            ACTION_RUN_AUTO_SETTLE -> {
                runAutoSettleIfNeeded()
            }
            ACTION_RESTART_AUTO_SETTLE_TICKER -> {
                restartTicker()
            }
            else -> {
                startTickerIfNeeded()
            }
        }
        Utils.debugLogPrint(TAG, "Service is started")
        return START_STICKY
    }

    private fun getNotification(channelId: String): Notification {
        val channelId = createChannel(channelId)
        /*val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )*/

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Payment Services")
            .setContentText("Payment Service is running")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            //.setContentIntent(pendingIntent)
            .build()
    }

    private fun createChannel(channelId: String): String {
        val channelName = "BindService"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val importance = NotificationManager.IMPORTANCE_LOW

        val channel = NotificationChannel(channelId, channelName, importance).apply {
            enableLights(true)
            lightColor = Color.BLUE
        }
        manager.createNotificationChannel(channel)
        return channelId
    }

    private fun restartTicker() {
        Utils.debugLogPrint(TAG, "Restarting ticker job")
        tickerJob?.cancel()
        tickerJob = null
        autoSettleTicker = defaultSettleCountdownSecond
        startTickerIfNeeded()
    }

    private fun startTickerIfNeeded() {
        if (tickerJob != null && tickerJob?.isActive == true) return
        Utils.debugLogPrint(TAG, "Starting ticker job")

        tickerJob = serviceScope.launch {
            while (isActive) {
                try {
                    mDate = Calendar.getInstance().time
                    autoSettleTicker--
                    if (autoSettleTicker == 0) {
                        autoSettleTicker = defaultSettleCountdownSecond
                        ServiceHolder.autoSettlementTimeStamp = System.currentTimeMillis()
                        Utils.debugLogPrint(TAG, "Auto Settle Countdown End.")
                        val terminalConfig = ServiceHolder.getTerminalConfig()
                        if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "AutoSettle")) {
                            Utils.debugLogPrint(TAG, "Auto Settle Check Start")
                            val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
                            val date = mDate ?: Date()
                            val timeString = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(date)
                            val currentTime = date.time

                            val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH)
                            val date1 = sdf.parse(timeString + DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AutoSettleT1"))
                            //Utils.debugLogPrint(TAG, "Parsed Date 1 :: $date1")
                            val date2 = sdf.parse(timeString + DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AutoSettleT2"))
                            //Utils.debugLogPrint(TAG, "Parsed Date 2 :: $date2")
                            val date3 = sdf.parse(timeString + DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AutoSettleT3"))
                            //Utils.debugLogPrint(TAG, "Parsed Date 3 :: $date3")

                            val sharedPreferences = Helper.getInstance().getPrefs(mContext)
                            val lastSettleDate = sharedPreferences.getString(LAST_SETTLE_TAG, "")
                            val alreadySettledToday = lastSettleDate == timeString
                            //Only allow to run within one hour
                            //val autoSettleDate1 = date1 != null && currentTime >= date1.time
                            val autoSettleDate1 = date1 != null && currentTime >= date1.time && currentTime < (date1.time + 3600000)
                            val autoSettleDate2 = date2 != null && currentTime >= date2.time && currentTime < (date2.time + 3600000)
                            val autoSettleDate3 = date3 != null && currentTime >= date3.time && currentTime < (date3.time + 3600000)
                            val shouldAutoSettleToday = !alreadySettledToday && (autoSettleDate1 || autoSettleDate2 || autoSettleDate3)

                            if (ServiceHolder.autoSettlementQueue || shouldAutoSettleToday) {
                                if(HTTPServer.getInstance().isActive) {
                                    if (!ServiceHolder.autoSettlementIsRunning || ServiceHolder.autoSettlementQueue) {
                                        Utils.debugLogPrint(TAG, "Start Running Auto Settlement :: $timeString $currentTime")
                                        runAutoSettleIfNeeded()
                                    }
                                } else {
                                    Utils.debugLogPrint(TAG, "Not in Idle Screen, auto settlement will not run")
                                    ServiceHolder.autoSettlementQueue = true
                                }
                            } else {
                                Utils.debugLogPrint(TAG, "Not in Auto Settlement Time Range, Exit Auto Settlement")
                            }

                            //Enhance
                            /*val autoSettleDate1 = date1 != null && currentTime >= date1.time && currentTime < (date1.time + 60000)
                            val autoSettleDate2 = date2 != null && currentTime >= date2.time && currentTime < (date2.time + 60000)
                            val autoSettleDate3 = date3 != null && currentTime >= date3.time && currentTime < (date3.time + 60000)
                            if (ServiceHolder.autoSettlementQueue || autoSettleDate1 || autoSettleDate2 || autoSettleDate3) {
                                if(HTTPServer.getInstance().isActive) {
                                    if (!ServiceHolder.autoSettlementIsRunning || ServiceHolder.autoSettlementQueue) {
                                        Utils.debugLogPrint(TAG, "Start Running Auto Settlement :: $timeString $currentTime")
                                        runAutoSettleIfNeeded()
                                    }
                                } else {
                                    Utils.debugLogPrint(TAG, "Not in Idle Screen, auto settlement will not run")
                                    ServiceHolder.autoSettlementQueue = true
                                }
                            } else {
                                Utils.debugLogPrint(TAG, "Not in Auto Settlement Time Range, Exit Auto Settlement")
                            }*/
                        }
                    }
                    delay(1000)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    delay(1000)
                }
            }
        }
    }

    private fun runAutoSettleIfNeeded() {
        if (autoSettleJob?.isActive == true) {
            Utils.debugLogPrint(TAG, "AutoSettleJobStatus :: ${autoSettleJob?.isActive}")
            Utils.debugLogPrint(TAG, "Auto Settle Job Running...")
            return
        }

        autoSettleJob = serviceScope.launch {
            val mainHelperLog = helpers.HelperLog(
                HelperCommon.getSession(),
                TmsHelper.checkIsConnectedWifi(mContext),
                Utils.getIPAddress(),
                TAG,
                TAG,
                "AppServices Auto Settlement Module"
            )
            mainHelperLog.appendLine(TAG, "--------- Auto Settlement Start ---------- ")
            try {
                val isPastDateSettlement = ServiceHolder.autoSettlementPastDate
                ServiceHolder.autoSettlementQueue = false
                ServiceHolder.autoSettlementIsRunning = true
                try {
                    settlementReceiptDetails(mainHelperLog)
                    autoQrSettlement(mainHelperLog)

                    val sharedPreferences: SharedPreferences = Helper.getInstance().getPrefs(mContext)
                    val timeString = if(isPastDateSettlement) {
                        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                            .let { SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(it.time) }
                    } else {
                        SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date())
                    }
                    sharedPreferences.edit { putString(LAST_SETTLE_TAG, timeString) }
                    ServiceHolder.autoSettlementPastDate = false
                } catch(_: Exception) {
                    //TODO
                } finally {
                    ServiceHolder.autoSettlementIsRunning = false
                }
                mainHelperLog.logToFile(EnumLogFileName.TerminaLog)
            } catch (e: ParseException) {
                e.printStackTrace()
                mainHelperLog.appendLine(TAG, "ParseException: ", e.toString())
                mainHelperLog.logToFile(EnumLogFileName.TerminaLogException)
                ServiceHolder.autoSettlementIsRunning = false
            } catch (e: Exception) {
                e.printStackTrace()
                mainHelperLog.appendLine(TAG, "Exception: ", e.toString())
                mainHelperLog.logToFile(EnumLogFileName.TerminaLogException)
                ServiceHolder.autoSettlementIsRunning = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerStopper = false

        releaseWifiLock()
        tickerJob?.cancel()
        autoSettleJob?.cancel()
        serviceScope.cancel()
        HTTPServer.stopWebSocketServer()
        try {
            WorkManager.getInstance(mContext).cancelAllWork()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

        // Last chance to get queued lines onto disk: this is often the only callback we get
        // before the process is reclaimed, and MainActivity re-creates this service on every
        // fresh load, so it fires on the restart path too. Blocking is fine here.
        AsyncLogWriter.drain()
    }

    /**
     * TRIM_MEMORY_COMPLETE means we are next in line to be killed. Flush before that happens --
     * a silent low-memory kill leaves no callback at all, and is the most likely explanation for
     * the holes where TerminaLog jumps over a completed transaction.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Kotlin does not inherit Java interface constants, so this must be qualified.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            Utils.debugLogPrint(TAG, "onTrimMemory($level) - draining log before possible kill")
            AsyncLogWriter.drain()
        }
    }

    private fun scheduleUploadLogToTms() {
        val sbLog = HelperLog.init("Starting Job Scheduler - Upload Log to TMS")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val uploadLogRequest = PeriodicWorkRequest.Builder(TmsUploadLogScheduler::class.java, 3, TimeUnit.HOURS)
            .setInitialDelay(3, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(UPLOAD_LOG_TAG, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, uploadLogRequest)
        HelperLog.appendLine(sbLog, "Successfully created upload Job")
        HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
    }

    private fun scheduleReceiptUploadToTms() {
        val sbLog = HelperLog.init("Starting Job Scheduler - Receipt Upload to TMS")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val receiptUploadSche = PeriodicWorkRequest.Builder(TmsReceiptUploadScheduler::class.java, 15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(RECEIPT_UPLOAD_TAG, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, receiptUploadSche)
        HelperLog.appendLine(sbLog, "Successfully created Receipt Upload Job")
        HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
    }

    private fun scheduleHouseKeepReceiptUpload() {
        val sbLog = HelperLog.init("Starting Job Scheduler - House Keeping Job Initiate")
        val houseKeepingSche = PeriodicWorkRequest
            .Builder(HouseKeepingReceiptUploadScheduler::class.java, 2, TimeUnit.HOURS).build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(HOUSEKEEP_JOB_TAG, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, houseKeepingSche)
        HelperLog.appendLine(sbLog, "Successfully created HouseKeep Job")
        HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
    }

    private fun scheduleAutoTmsUpload() {
        val sbLog = HelperLog.init("Starting Job Scheduler - Auto TMS Upload")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val autoTmsUploadRequest = PeriodicWorkRequest.Builder(AutoTmsUploadScheduler::class.java, 1, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(AUTO_TMS_UPLOAD_TAG, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, autoTmsUploadRequest)
        HelperLog.appendLine(sbLog, "Successfully created Auto TMS Upload Job")
        HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
    }

    private fun scheduleAutoSettlementTimeCheck() {
        val sbLog = HelperLog.init("Starting Job Scheduler - Auto Settlement Time Check")
        val scheduleAutoSettleCheck = PeriodicWorkRequest.Builder(SettlementRecoveryScheduler::class.java, 6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(getApplicationContext())
            .enqueueUniquePeriodicWork(LAST_SETTLE_JOB_TAG, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, scheduleAutoSettleCheck)
        HelperLog.appendLine(sbLog, "Successfully created Auto Settlement Time Check")
        HelperLog.logToFile(sbLog, EnumLogFileName.TerminaLog)
    }

    private fun settlementReceiptDetails(mainHelperLog: helpers.HelperLog) {
        val settlementProduct: List<DbModelProductList> = ProductListRepo.getUnSettledProduct(ServiceHolder.mContext)
        mainHelperLog.appendLine(TAG, "Settlement Product Count :: ${settlementProduct.size}")
        mainHelperLog.appendLine(TAG, "Settlement Product :: ${Gson().toJson(settlementProduct)}")

        if (settlementProduct.isEmpty()) return
        mainHelperLog.appendLine(TAG, "Checking on unsettle product")
        val timeStamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH)
            .format(Calendar.getInstance().time)

        val dbModelIsoBatchNo: DbModelIsoBatchInfo? = IsoBatchInfoRepo.getBatchInfo(ServiceHolder.mContext, "batchNo", "visam")
        val settleBatchNo = dbModelIsoBatchNo?.value ?: ""
        val cardTypeMap = hashMapOf(
            "visa" to "Visa",
            "master" to "Master",
            "upi" to "UnionPay",
            "mccs" to "MyDebit"
        )

        val settlementTypeMap = hashMapOf(
            "txnCount" to "SALE COUNT",
            "txnTotal" to "SALE TOTAL",
            "voidTxnCount" to "VOID COUNT",
            "voidTxnTotal" to "VOID TOTAL"
        )

        for (productItem in settlementProduct) {
            var txnCount = 0
            val settleTid = productItem.AcqTid
            val settleMid = productItem.AcqMid
            val settleInfo = arrayListOf(timeStamp, settleTid, settleMid, settleBatchNo)

            val recordSummary: List<DbModelSettlementSummary> =
                SettlementSummaryRepo.getSelectiveData(
                    ServiceHolder.mContext,
                    arrayListOf("acq_code", "mid", "tid"),
                    arrayOf(productItem.AcqCode, settleMid, settleTid)
                )

            if (recordSummary.isNotEmpty()) {
                for ((cardKey, cardLabel) in cardTypeMap) {
                    settleInfo.add("")
                    settleInfo.add(cardLabel)

                    for ((settleKey, _) in settlementTypeMap) {
                        val filtered = recordSummary.firstOrNull { elem ->
                            elem.tag.equals(settleKey, ignoreCase = true) &&
                                    elem.subtag.equals("visam-$cardKey", ignoreCase = true)
                        }

                        if (filtered != null) {
                            var summaryValue = filtered.value

                            if (settleKey.contains("Total", ignoreCase = true)) {
                                summaryValue = Utils.getActualAmount(summaryValue)
                            } else if (settleKey.contains("Count", ignoreCase = true)) {
                                txnCount += Utils.atoi(summaryValue)
                            }

                            settleInfo.add(summaryValue)
                        }
                    }
                }
            }
            mainHelperLog.appendLine(TAG, "Transaction Count for current product :: $txnCount")
            mainHelperLog.appendLine(TAG, "Settlement Receipt Info :: $settleInfo")
            // Proceed Transaction if have transaction
            // Currently no skip settlement for BSN
            if (txnCount > 0) {
                mainHelperLog.appendLine(TAG, "---------------Auto Settlement [START]---------------")
                val result = IsoActivity.processAutoSettlement(
                    mainHelperLog,
                    ServiceHolder.mContext,
                    productItem,
                    settleBatchNo
                )
                mainHelperLog.appendLine(TAG, "---------------Auto Settlement [END]---------------")
                mainHelperLog.appendLine(TAG, "Settlement Result :: $result")
                if (result) {
                    val toPrintInfo = constructPrintRecord(settleBatchNo, productItem, recordSummary)
                    val stringInfo = toPrintInfo.joinToString("|") { pair ->
                        "(${pair.first}, ${pair.second})"
                    }
                    val dbModelLastSettlement = DbModelLastSettlement(
                        settleMid,
                        settleTid,
                        stringInfo,
                        productItem.IsTpaAccount
                    )
                    LastSettlementRepo.addOrInsert(ServiceHolder.mContext, dbModelLastSettlement)
                    mainHelperLog.appendLine(TAG, "Finish Auto Settlement :: ($settleMid) ($settleTid)")
                } else {
                    mainHelperLog.appendLine(TAG, "Auto Settlement Fail Throwing Exception")
                    throw Exception("Auto Settlement Fail")
                }
            }
        }
        mainHelperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    fun constructPrintRecord(
        batchNo: String,
        productModel: DbModelProductList,
        settlementSummary: List<DbModelSettlementSummary>
    ): List<Pair<String, String>> {
        try {
            Thread.sleep(200)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val timeStamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH)
            .format(Calendar.getInstance().time)

        val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        var tid = productModel.AcqTid
        var mid = productModel.AcqMid
        if(productModel.IsTpaAccount.equals("true", true)) {
            mid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScMid")
            tid = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "ScTid")
        }

        val acqName = ServiceHolder.getAcquirerSetting().acqName
        if (acqName.uppercase(Locale.ENGLISH) == "GOBIZ") {
            tid = Utils.maskString(tid, 4)
            mid = Utils.maskString(mid, 4)
        }

        val headerText = "SETTLEMENT REPORT"

        val toPrintInfo = ArrayList<Pair<String, String>>()
        toPrintInfo.add(Pair("Date/Time", timeStamp))
        toPrintInfo.add(Pair("TERMINAL ID", tid))
        toPrintInfo.add(Pair("MERCHANT ID", mid))
        toPrintInfo.add(Pair("BATCH NO", batchNo))
        toPrintInfo.add(Pair("HEADER", headerText))

        val cardTypeMap: Map<String, String> = getStringStringMap(acqName)

        val settlementType = linkedMapOf(
            "txnCount" to "SALE COUNT",
            "txnTotal" to "SALE TOTAL",
            "voidTxnCount" to "VOID COUNT",
            "voidTxnTotal" to "VOID TOTAL"
        )

        val summaryMap = linkedMapOf(
            "txnCount" to 0,
            "txnTotal" to 0,
            "voidTxnCount" to 0,
            "voidTxnTotal" to 0
        )

        val prioritizedOrder = listOf("visa", "master")
        val remainingCardTypes = cardTypeMap.keys.toMutableList().apply {
            removeAll(prioritizedOrder.toSet())
        }

        // Prioritized: Visa, Master first
        for (cardTypeKey in prioritizedOrder) {
            if (cardTypeMap.containsKey(cardTypeKey)) {
                toPrintInfo.add(Pair("SCHEME", cardTypeMap.getValue(cardTypeKey)))

                for (settleTypeKey in settlementType.keys) {
                    val filteredData = settlementSummary.firstOrNull {
                        it.tag == settleTypeKey && it.subtag == "visam-$cardTypeKey"
                    }

                    var summaryValue = filteredData?.value ?: "0"

                    val temp = (summaryMap[settleTypeKey] ?: 0) + Utils.atoi(summaryValue)
                    summaryMap[settleTypeKey] = temp

                    if (settleTypeKey.contains("Total")) {
                        summaryValue = Utils.getActualAmount(summaryValue)
                    }

                    toPrintInfo.add(Pair(settlementType.getValue(settleTypeKey), summaryValue))
                }

                toPrintInfo.add(Pair("br", ""))
            }
        }

        // Remaining: MyDebit, UnionPay, etc.
        for (cardTypeKey in remainingCardTypes) {
            toPrintInfo.add(Pair("SCHEME", cardTypeMap.getValue(cardTypeKey)))

            for (settleTypeKey in settlementType.keys) {
                val filteredData = settlementSummary.firstOrNull {
                    it.tag == settleTypeKey && it.subtag == "visam-$cardTypeKey"
                }

                var summaryValue = filteredData?.value ?: "0"

                val temp = (summaryMap[settleTypeKey] ?: 0) + Utils.atoi(summaryValue)
                summaryMap[settleTypeKey] = temp

                if (settleTypeKey.contains("Total")) {
                    summaryValue = Utils.getActualAmount(summaryValue)
                }

                toPrintInfo.add(Pair(settlementType.getValue(settleTypeKey), summaryValue))
            }

            toPrintInfo.add(Pair("br", ""))
        }

        toPrintInfo.add(Pair("hr", ""))
        toPrintInfo.add(Pair("br", ""))
        toPrintInfo.add(Pair("HEADER", "TOTAL SETTLEMENT"))

        val orderedSummaryKeys = listOf("txnCount", "txnTotal", "voidTxnCount", "voidTxnTotal")
        for (summaryTypeKey in orderedSummaryKeys) {
            var summaryValue = (summaryMap[summaryTypeKey] ?: 0).toString()
            if (summaryTypeKey.contains("Total")) {
                summaryValue = Utils.getActualAmount(summaryValue)
            }
            toPrintInfo.add(Pair(settlementType.getValue(summaryTypeKey), summaryValue))
        }

        toPrintInfo.add(Pair("br", ""))
        toPrintInfo.add(Pair("hr", ""))

        return toPrintInfo
    }

    private fun getStringStringMap(acqName: String): Map<String, String> {
        val terminalConfig = ServiceHolder.getTerminalConfig()
        val optIn = DbModelTerminalConfig.getBooleanValue(terminalConfig, "OptIn")
        val cardTypeMap = HashMap<String, String>()
        val upper = acqName.uppercase(Locale.ENGLISH)
        cardTypeMap["visa"] = "Visa"
        cardTypeMap["master"] = "Master"

        if(upper.equals("GOBIZ", true)) {
            cardTypeMap["upi"] = "UnionPay"
        }

        if(optIn) {
            cardTypeMap["mccs"] = "MyDebit"
        }
        return cardTypeMap
    }

    private fun getTxnTotalCount(productCode: String, txnType: String): Int {
        val totalCount = TransactionQrRepo.countTransactionCountByType(ServiceHolder.mContext, productCode, txnType)
        return maxOf(totalCount, 0)
    }

    private fun getTxnTotalAmount(productCode: String, txnType: String): String {
        val totalTxnAmt = TransactionQrRepo.countTransactionAmountByType(ServiceHolder.mContext, productCode, txnType)
        return if (totalTxnAmt <= 0) "000" else totalTxnAmt.toString()
    }

    //TODO write sbLog
    @Synchronized
    fun autoQrSettlement(mainHelperLog: helpers.HelperLog) {
        try {
            mainHelperLog.appendLine(TAG, "Starting Auto QR Settlement")
            val qrPayBrandV2: List<DbModelQrPayBrandGet> = TransactionQrRepo.getDistinctProduct(ServiceHolder.mContext)
            val requireSettleQr = qrPayBrandV2.isNotEmpty()
            mainHelperLog.appendLine(TAG, "Qr Pay Brand V2 :: ${qrPayBrandV2.size}")
            if (!requireSettleQr) return

            val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
            val txnDt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH).format(Calendar.getInstance().time)

            val qrMid = dbModelMerchantConfig?.QrMid ?: ""
            val settlementInfoQr = arrayOf(txnDt, qrMid, "")
            val tags2 = arrayOf("SCHEME", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL")
            val tags3 = arrayOf("", "", "TOTAL SETTLEMENT", "SALE COUNT", "SALE TOTAL", "VOID COUNT", "VOID TOTAL", "", "")

            val settleInfoQr = arrayOfNulls<String>(settlementInfoQr.size + (qrPayBrandV2.size * tags2.size) + tags3.size)

            // copy header
            for (i in settlementInfoQr.indices) {
                settleInfoQr[i] = settlementInfoQr[i]
            }
            var count = settlementInfoQr.size

            var saleA = 0L
            var saleC = 0L
            var voidA = 0L
            var voidC = 0L

            for (i in qrPayBrandV2.indices) {
                val modelData = qrPayBrandV2[i]
                mainHelperLog.appendLine(TAG, "Looping through QR Pay Brand V2: ${modelData.productCode}")

                val tempSaleA = getTxnTotalAmount(modelData.productCode, "Sale").toLongOrNull() ?: 0L
                val tempSaleC = getTxnTotalCount(modelData.productCode, "Sale")
                val tempVoidA = getTxnTotalAmount(modelData.productCode, "Void").toLongOrNull() ?: 0L
                val tempVoidC = getTxnTotalCount(modelData.productCode, "Void")

                saleA += tempSaleA
                saleC += tempSaleC.toLong()
                voidA += tempVoidA
                voidC += tempVoidC.toLong()

                // label-equivalent skip: if both counts are 0, skip this brand entirely
                if (tags2.isNotEmpty() && tempSaleC == 0 && tempVoidC == 0) {
                    continue
                }

                for (i in tags2.indices) {
                    val res: String = when (i) {
                        0 -> modelData.productCode
                        1 -> tempSaleC.toString().ifEmpty { "0" }
                        2 -> {
                            val amt = tempSaleA.toString().ifEmpty { "000" }
                            Utils.getActualAmount(amt)
                        }
                        3 -> tempVoidC.toString().ifEmpty { "0" }
                        4 -> {
                            val amt = tempVoidA.toString().ifEmpty { "000" }
                            Utils.getActualAmount(amt)
                        }
                        else -> "0"
                    }

                    settleInfoQr[count] = res
                    count++
                }
            }

            // TOTAL SETTLEMENT block (tags3)
            for (j in tags3.indices) {
                val res: String = when (j) {
                    2 -> tags3[j]
                    3 -> saleC.toString()
                    4 -> Utils.getActualAmount(saleA.toString())
                    5 -> voidC.toString()
                    6 -> Utils.getActualAmount(voidA.toString())
                    else -> ""
                }

                settleInfoQr[count] = res
                count++
            }

            mainHelperLog.appendLine(TAG, "Finish Auto QR Settlement")
            // Remove nulls
            val finalArray = settleInfoQr.filterNotNull().toTypedArray()
            Utils.write2File(finalArray, "qrlastsettlement.txt")
            TransactionQrRepo.truncateTable(ServiceHolder.mContext)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

}