package com.sc.mf919.java.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.sc.mf919.kotlin.activity.MainActivity;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

/**
 * Boot fallback. Android 7 only, in practice.
 *
 * startActivity() from a BOOT_COMPLETED broadcast is a background activity start: allowed on
 * Android 7, silently dropped from Android 10 onward -- no exception, nothing in logcat, the app
 * just never appears. The fleet confirms it, so the real boot path is now the ROM-level property
 * set by DeviceHelper.enableAutoStartOnBoot(); this receiver stays for the Android 7 units and for
 * any ROM that does not implement that property.
 */
public class StartMyServiceAtBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ServiceHolder.Companion.setMContext(context);
        // getAction() is nullable, and this receiver is also registered for PACKAGE_ADDED /
        // PACKAGE_INSTALL / ACTION_REBOOT -- an NPE here would crash the app on the boot path,
        // which is the one path that has no operator to recover it.
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, MainActivity.class);
            serviceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(serviceIntent);
        } catch (Exception e) {
            // Android 10+ throws or no-ops here depending on ROM. Either way the ROM auto-start
            // property is what brings the app up, so this must never take the process down.
            Utils.debugLogPrint("StartMyServiceAtBootReceiver", "Boot startActivity failed -> " + e);
        }
    }
}