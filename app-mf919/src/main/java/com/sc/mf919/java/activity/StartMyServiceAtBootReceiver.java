package com.sc.mf919.java.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.sc.mf919.java.MF919;
import com.sc.mf919.kotlin.activity.MainActivity;
import com.sc.mf919.kotlin.helper_common.ServiceHolder;

/**
 * Boot path for Android 7. Must stay.
 *
 * The two boot mechanisms are complementary, not redundant -- verified on device 2026-09-30:
 *  - Android 7.1.2 (MF919, YSDK 6.05.05): the ROM auto-start property set by
 *    DeviceHelper.enableAutoStartOnBoot() is accepted (ret 0) but IGNORED. With this receiver
 *    disabled the terminal boots to the Morefun launcher. This receiver is the only launcher.
 *  - Android 10+: startActivity() from here is a background activity start and is dropped, so the
 *    ROM property is what brings the app up. BUT the drop only applies while the app has no
 *    window: on the SR800 (Android 13) BOOT_COMPLETED arrived ~17 s after the ROM had already
 *    started us, the start was allowed, and the app ran its startup twice.
 *
 * Hence the guard: if any activity already exists in this process, someone else started the app
 * and this launch is a duplicate.
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

        if (MF919.hasActivityCreated()) {
            Utils.debugLogPrint("StartMyServiceAtBootReceiver", "Boot startActivity skipped -> app already started");
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
