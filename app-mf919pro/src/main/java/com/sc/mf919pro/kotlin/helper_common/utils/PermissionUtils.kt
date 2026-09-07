package com.sc.mf919pro.kotlin.helper_common.utils

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

object PermissionUtils {
    /**
     * Main checkPermissions entry point — Android 13 compatible.
     */
    @JvmStatic
    fun checkPermissions(activity: Activity, permissions: Array<String>?, requestCode: Int): Boolean {
        if (permissions.isNullOrEmpty()) return true

        // Convert old permissions → Android 13 media permissions
        val upgraded = upgradeStoragePermissions(permissions)

        val missing = ArrayList<String>()

        for (permission in upgraded) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                println(permission)
                missing.add(permission)
            }
        }

        return if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), requestCode)
            false
        } else {
            true
        }
    }

    /**
     * Android 13 media permission mapper
     */
    private fun upgradeStoragePermissions(permissions: Array<String>): Array<String> {
        val updated = ArrayList<String>()

        for (p in permissions) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                p == Manifest.permission.READ_EXTERNAL_STORAGE
            ) {
                // Android 13 splits this into 3
                updated.add(Manifest.permission.READ_MEDIA_IMAGES)
                updated.add(Manifest.permission.READ_MEDIA_VIDEO)
                updated.add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                // keep original if not upgraded
                updated.add(p)
            }
        }

        return updated.toTypedArray()
    }

    /**
     * Permission result checker
     */
    @JvmStatic
    fun checkPermissionResult(permissions: Array<String>?, grantResults: IntArray?): Boolean {
        if (permissions.isNullOrEmpty() || grantResults == null || grantResults.isEmpty()) return false

        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }
}