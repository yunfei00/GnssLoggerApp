package com.example.gnsslogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class GnssCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        when (intent?.action) {
            AppActions.ACTION_UPDATE_SCENE -> {
                val scene = intent.getStringExtra(AppActions.EXTRA_SCENE_NAME)
                GnssLoggerService.updateSceneName(appContext, scene)
            }

            AppActions.ACTION_START_LOGGING -> {
                val starter = Intent(appContext, GnssLoggerService::class.java)
                    .setAction(AppActions.ACTION_START_LOGGING)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(appContext, starter)
                } else {
                    appContext.startService(starter)
                }
            }

            AppActions.ACTION_STOP_LOGGING -> {
                val stopper = Intent(appContext, GnssLoggerService::class.java)
                    .setAction(AppActions.ACTION_STOP_LOGGING)
                appContext.startService(stopper)
            }
        }
    }
}
