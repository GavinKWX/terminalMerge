package com.sc.mf919.kotlin.helper_common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.sc.mf919.kotlin.activity.UpdateAppAlertDialog

class NotificationUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val newIntent = Intent(context.applicationContext, UpdateAppAlertDialog::class.java)
        newIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(newIntent)

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancel(2)
    }
}