package com.example.cellularwanfailover.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.cellularwanfailover.MainActivity
import com.example.cellularwanfailover.controller.FailoverController
import com.example.cellularwanfailover.model.FailoverState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FailoverForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "failover_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.cellularwanfailover.action.START"
        const val ACTION_STOP = "com.example.cellularwanfailover.action.STOP"
        const val ACTION_START_FAILOVER = "com.example.cellularwanfailover.action.START_FAILOVER"
        const val ACTION_STOP_FAILOVER = "com.example.cellularwanfailover.action.STOP_FAILOVER"

        fun startService(context: Context) {
            val intent = Intent(context, FailoverForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FailoverForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObservationJob: Job? = null
    private lateinit var controller: FailoverController

    override fun onCreate() {
        super.onCreate()
        controller = FailoverController.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_FAILOVER -> {
                scope.launch {
                    controller.startFailover()
                }
            }
            ACTION_STOP_FAILOVER -> {
                scope.launch {
                    controller.stopFailover()
                }
            }
        }

        startInForeground()
        controller.startServiceComponents()
        observeState()

        return START_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification(controller.failoverState.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeState() {
        stateObservationJob?.cancel()
        stateObservationJob = scope.launch {
            controller.failoverState.collect { state ->
                val notification = buildNotification(state)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(state: FailoverState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusSubtitle = when (state) {
            FailoverState.IDLE -> "State: Standby (Wi-Fi active)"
            FailoverState.CONNECTING -> "State: Connecting cellular..."
            FailoverState.ACTIVE -> "State: Active (4G/5G failover running)"
            FailoverState.ERROR -> "State: Cellular connection error"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Cellular WAN Failover Gateway")
            .setContentText(statusSubtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)

        // Add action buttons
        if (state == FailoverState.ACTIVE) {
            val stopFailoverIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, FailoverForegroundService::class.java).apply {
                    action = ACTION_STOP_FAILOVER
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Stop Failover", stopFailoverIntent)
        } else {
            val startFailoverIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, FailoverForegroundService::class.java).apply {
                    action = ACTION_START_FAILOVER
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Start Failover", startFailoverIntent)
        }

        val stopServiceIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, FailoverForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopServiceIntent)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cellular WAN Failover",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status notifications for Cellular WAN Failover service"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stateObservationJob?.cancel()
        controller.stopServiceComponents()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
