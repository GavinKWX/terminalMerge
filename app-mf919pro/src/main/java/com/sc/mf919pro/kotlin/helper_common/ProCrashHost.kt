package com.sc.mf919pro.kotlin.helper_common

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.morefun.yapi.engine.DeviceInfoConstrants
import com.sc.mf919pro.BuildConfig
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.device.DeviceHelper
import com.sc.mf919pro.kotlin.activity.CrashRecoveryActivity
import helpers.CrashHost

/**
 * This app's side of [CrashHost].
 *
 * Every method here runs inside a crash, so each one swallows its own failures: the handler still
 * has a crash record to write and a restart to schedule, and must not be stopped by a second
 * problem in here. Registered once per process in MF919.onCreate.
 */
object ProCrashHost : CrashHost {

	override fun ipAddress(): String = try {
		Utils.getIPAddress()
	} catch (t: Throwable) {
		// getConnectionInfo() is nullable on Android 10+; this used to escape the handler.
		Utils.UNKNOWN_IP
	}

	override fun isWifi(context: Context): Boolean = try {
		TmsHelper.checkIsConnectedWifi(context)
	} catch (t: Throwable) {
		false
	}

	override fun buildLabel(): String = "${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE}"

	override fun callerChannel(): String = when {
		ServiceHolder.appHTTP -> "http"
		ServiceHolder.appIntent -> "app2app"
		else -> "none"
	}

	override fun notifyCallerBusy(payload: String) {
		// Only the HTTP transport has a caller parked on a socket waiting for a body.
		if (!ServiceHolder.appHTTP) return
		HTTPServer.getInstance().setResponseMessage(payload)
	}

	override fun launchRecovery(
		context: Context,
		crashId: String,
		attempt: Int,
		delayMs: Long,
		mainPid: Int,
	) {
		val intent = Intent(context, CrashRecoveryActivity::class.java).apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
			putExtra(CrashRecoveryActivity.EXTRA_CRASH_ID, crashId)
			putExtra(CrashRecoveryActivity.EXTRA_ATTEMPT, attempt)
			putExtra(CrashRecoveryActivity.EXTRA_DELAY_MS, delayMs)
			putExtra(CrashRecoveryActivity.EXTRA_MAIN_PID, mainPid)
		}
		context.startActivity(intent)
	}

	override fun requestReboot(): Boolean = try {
		val bundle = Bundle()
		bundle.putBoolean(DeviceInfoConstrants.REBOOT, true)
		DeviceHelper.getDeviceService().setProperties(bundle) == 0
	} catch (t: Throwable) {
		// Binder gone, or the ROM does not allow it. Fall through to a normal restart.
		false
	}
}
