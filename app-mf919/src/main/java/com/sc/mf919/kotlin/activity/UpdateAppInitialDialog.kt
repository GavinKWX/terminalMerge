package com.sc.mf919.kotlin.activity

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.*
import android.os.StrictMode.VmPolicy
import android.text.Html
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.library.terminal.UrlDownload
import com.library.terminal.Utility
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.device.DeviceHelper
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import tms.handlers.CheckUpdateHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


class UpdateAppInitialDialog : Activity() {
    var context: Context = this
    var mFirmId = ""

    private lateinit var alertBuilder: AlertDialog.Builder
    private lateinit var mHandler: Handler
    lateinit var alertDialog: AlertDialog
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) //hide activity title

        val builder = VmPolicy.Builder()
        StrictMode.setVmPolicy(builder.build())
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Initial app update check / APK download"
        )
        helperLog.appendLine(helperLogClassName, "Update app dialog opened")
        mHandler = Handler(Looper.getMainLooper())
        mHandler.post { showProgressDialog() }
        mHandler.postDelayed({
            callTmsUpdateApp()
        }, 1000)
    }

    private fun showProgressDialog() {
        val title = "Download APK File"
        val msg = "Checking... Please wait"

        val keyListener =
            DialogInterface.OnKeyListener { dialog, keyCode, KEvent -> keyCode == KeyEvent.KEYCODE_HOME }
        alertBuilder = AlertDialog.Builder(this, com.sc.mf919.R.style.DialogThemeColor)
        alertBuilder.setOnKeyListener(keyListener)

        val inflater = this.layoutInflater
        val dialogView: View = inflater.inflate(R.layout.activity_progressdialogcircle, null)
        (dialogView.findViewById<View>(R.id.message) as TextView).text =
            Html.fromHtml("<big>$msg</big>")

        alertBuilder.setView(dialogView)
        alertBuilder.setCancelable(false)
        alertBuilder.setTitle(Html.fromHtml("<b>$title<b>"))

        alertDialog = alertBuilder.create()
        alertDialog.show()
    }

    private fun dismissProgressDialog() {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "UpdateAppInitialDialog OnDestroy :: update check ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        alertDialog.dismiss()
        finish()
    }

    private fun callTmsUpdateApp() {
        val currentTime = Date()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        val timeStamp = sdf.format(currentTime)
        var firmID = ""
        if (Utility.exist_file(ServiceHolder.getInternalFilesPaths() + "firmID.txt")) {
            val file = Utility.read_file(ServiceHolder.getInternalFilesPaths() + "firmID.txt")
            firmID = file[0]
        }

        val log = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            "Update App Dialog",
            UpdateAppInitialDialog::class.java.simpleName,
            UpdateAppInitialDialog::class.java.name,
        )
        val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
        val checkUpdateHandler = CheckUpdateHandler(environmentManager)
        try {
            val terminalConfig = ServiceHolder.getTerminalConfig()
            val apiResp = checkUpdateHandler.invoke(
                log,
                ServiceHolder.getSqnNum(),
                ServiceHolder.getTerminalSerialNumber(),
                ServiceHolder.getDeviceModel(),
                applicationContext.getString(R.string.app_name),
                terminalConfig?.DEV_PROJECT ?: "",
                terminalConfig?.DEV_LOCATION ?: "",
                terminalConfig?.DEV_LANE_ID ?: "",
                firmID,
                ServiceHolder.getAppVersion(),
                "-",
                "-",
                ServiceHolder.getMcVersion(),
            )
            log.appendLine("", "CheckUpdateHandler Response -> ", apiResp.toString())
            log.logToFile(EnumLogFileName.TerminaLog)

            val appName = apiResp.FIRM_FILENAME

            if (appName.isNullOrEmpty()) {
                helperLog.appendLine(helperLogClassName, "No update available :: latest app already installed")
                Toast.makeText(context, "Latest App is Using", Toast.LENGTH_SHORT).show()
                dismissProgressDialog()
            } else {
                mFirmId = apiResp.FIRM_ID.toString()
                helperLog.appendLine(helperLogClassName, "Update available :: firmId=$mFirmId file=$appName")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
                val url = apiResp.FIRM_URL

                object : Thread() {
                    override fun run() {
                        val download = UrlDownload(
                            appName,
                            url,
                            "${Environment.getExternalStorageDirectory().path}${File.separator}app-debug.apk"
                        )
                        download.getFile()

                        if (download.isDownloadSuccessfully) {
                            Utils.write2File(
                                arrayOf(mFirmId, ServiceHolder.getAppVersion()),
                                "updateApk.txt"
                            )
                            Utils.write2File(arrayOf(mFirmId), "firmID.txt")
                            helperLog.appendLine(helperLogClassName, "Setting changed :: firmID -> $mFirmId")
                            helperLog.logToFile(EnumLogFileName.TerminaLog)
                            mHandler.post {
                                Toast.makeText(context, "Successfully Downloaded App", Toast.LENGTH_SHORT).show()
                            }
                            runUpdate()
                        } else {
                            helperLog.appendLine(helperLogClassName, "REJECT :: APK download failed")
                            helperLog.logToFile(EnumLogFileName.TerminaLogException)
                            mHandler.post {
                                Toast.makeText(context, "Download App Failed", Toast.LENGTH_SHORT).show()
                                dismissProgressDialog()
                            }
                        }
                    }
                }.start()
            }
        }catch (ex: Exception) {
            log.appendLine("", "CheckUpdateHandler Response (Exception)", ex.toString())
            log.logToFile(EnumLogFileName.TerminaLogException)
            helperLog.appendLine(helperLogClassName, "REJECT :: update check failed")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
            Toast.makeText(context, "Fail to Check Update", Toast.LENGTH_SHORT).show()
            dismissProgressDialog()
        }


//        val apiBody = CheckUpdateRequestModel(
//            ServiceHolder.getSqnNum(),
//            timeStamp,
//            ServiceHolder.getTerminalSerialNumber(),
//            ServiceHolder.getDeviceModel(),
//            getString(R.string.app_name),
//            ServiceHolder.getStringValue("tms.txt", "DeviceProject"),
//            ServiceHolder.getStringValue("tms.txt", "DeviceLocation"),
//            ServiceHolder.getStringValue("tms.txt", "DeviceLaneId"),
//            firmID,
//            ServiceHolder.getAppVersion(),
//            "-",
//            "-",
//            //ServiceHolder.getStringValue("merchantInfo.txt", "MC_VER")
//            ServiceHolder.getMcVersion()
//        )
//        val apiClient = Api.getClient()
//        apiClient.checkAppUpdate(apiBody).enqueue(object : Callback<CheckUpdateResponseModel> {
//            @RequiresApi(Build.VERSION_CODES.R)
//            override fun onResponse(
//                call: Call<CheckUpdateResponseModel>,
//                response: Response<CheckUpdateResponseModel>
//            ) {
//                val appName = response.body()?.FIRM_FILENAME
//
//                if (appName.isNullOrEmpty()) {
//                    Toast.makeText(context, "Latest App is Using", Toast.LENGTH_SHORT).show()
//                    dismissProgressDialog()
//                } else {
//                    mFirmId = response.body()?.FIRM_ID.toString()
//                    val url = response.body()?.FIRM_URL
//
//                    object : Thread() {
//                        override fun run() {
//                            val download = UrlDownload(
//                                appName,
//                                url,
//                                "${Environment.getExternalStorageDirectory().path}${File.separator}app-debug.apk"
//                            )
//                            download.getFile()
//
//                            if (download.isDownloadSuccessfully) {
//                                Utils.write2File(
//                                    arrayOf(mFirmId, ServiceHolder.getAppVersion()),
//                                    "updateApk.txt"
//                                )
//                                Utils.write2File(arrayOf(mFirmId), "firmID.txt")
//                                mHandler.post {
//                                    Toast.makeText(context, "Successfully Downloaded App", Toast.LENGTH_SHORT).show()
//                                }
//                                runUpdate()
//                            } else {
//                                mHandler.post {
//                                    Toast.makeText(context, "Download App Failed", Toast.LENGTH_SHORT).show()
//                                    dismissProgressDialog()
//                                }
//                            }
//                        }
//                    }.start()
//                }
//            }
//
//            override fun onFailure(call: Call<CheckUpdateResponseModel>, t: Throwable) {
//                Toast.makeText(context, "Fail to Check Update", Toast.LENGTH_SHORT).show()
//                dismissProgressDialog()
//            }
//        })
    }

    @Synchronized
    private fun runUpdate() {
        try {
            helperLog.appendLine(helperLogClassName, "Start Installation")
            DeviceHelper.getDeviceService().installApp(
                "${Environment.getExternalStorageDirectory().path}${File.separator}app-debug.apk",
                "",
                ""
            )
            doRestart()
            helperLog.appendLine(helperLogClassName, "End Installation")
            dismissProgressDialog()
        } catch (exception: RemoteException) {
            helperLog.appendLine(helperLogClassName, "Installation failed :: ${exception.message}")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
            exception.printStackTrace()
        }
    }

    private fun doRestart() {
        val mStartActivity = Intent(applicationContext, MainActivity::class.java)
        val mPendingIntentId = 123456
        val mPendingIntent = PendingIntent.getActivity(
            applicationContext,
            mPendingIntentId,
            mStartActivity,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mgr = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
        mgr[AlarmManager.RTC, System.currentTimeMillis() + 20 * 1000] = mPendingIntent
    }
}