package com.sc.mf919pro.kotlin.activity

import android.R.drawable
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Window


class UpdateAppAlertDialog : Activity() {
    var context: Context = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) //hide activity title
        val Builder = AlertDialog.Builder(context)
                .setTitle("Comfirmation")
                .setMessage("Do you want to update to latest application?")
                .setIcon(drawable.ic_dialog_alert)
                .setNegativeButton("No") { _, _ ->
                    finish()
                }
                .setPositiveButton("Yes") { _, _ ->
                    val newIntent = Intent(this, MainActivity::class.java)
                    newIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    newIntent.setAction("UpdateApp");
                    context.startActivity(newIntent);
                    finish()
                }
        val alertDialog = Builder.create()
        alertDialog.show()
    }
}