package com.example.cellularwanfailover.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the failover service after a reboot or an app update, so the phone is
 * available to the gateway without anyone opening the app.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Starting failover service (${intent.action})")
                try {
                    FailoverForegroundService.startService(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start failover service: ${e.message}", e)
                }
            }
        }
    }
}
