package com.sc.mf919pro.kotlin.activity

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
import com.library.terminal.UrlDownload
import com.library.terminal.Utility
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.device.DeviceHelper
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) //hide activity title

        val builder = VmPolicy.Builder()
        StrictMode.setVmPolicy(builder.build())
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
        alertBuilder = AlertDialog.Builder(this, com.sc.mf919pro.R.style.DialogThemeColor)
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

            val appName = apiResp.FIRM_FILENAME

            if (appName.isNullOrEmpty()) {
                Toast.makeText(context, "Latest App is Using", Toast.LENGTH_SHORT).show()
                dismissProgressDialog()
            } else {
                mFirmId = apiResp.FIRM_ID.toString()
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
                            mHandler.post {
                                Toast.makeText(context, "Successfully Downloaded App", Toast.LENGTH_SHORT).show()
                            }
                            runUpdate()
                        } else {
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
            Utils.debugLogPrint("TAG", "Start Installation")
            DeviceHelper.getDeviceService().installApp(
                "${Environment.getExternalStorageDirectory().path}${File.separator}app-debug.apk",
                "",
                ""
            )
            doRestart()
            dismissProgressDialog()
            Utils.debugLogPrint("TAG", "End Installation")
        } catch (exception: RemoteException) {
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