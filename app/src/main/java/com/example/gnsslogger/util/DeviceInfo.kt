package com.example.gnsslogger.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object DeviceInfo {

    fun model(): String = Build.MODEL ?: ""

    fun androidRelease(): String = Build.VERSION.RELEASE ?: ""

    fun packageVersionName(context: Context): String = try {
        val pm = context.packageManager
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        pi.versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
