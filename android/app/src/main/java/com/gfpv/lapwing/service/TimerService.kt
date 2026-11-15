package com.gfpv.lapwing.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.gfpv.lapwing.LapwingApplication
import com.gfpv.lapwing.R
import com.gfpv.lapwing.ble.ConnectionState
import com.gfpv.lapwing.race.formatSeconds
import com.gfpv.lapwing.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service held while a timer is connected, so laps keep arriving and being announced
 * when the screen is off or another app is in front.
 */
class TimerService : LifecycleService() {
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Connecting…"), type)

        if (observing) return START_NOT_STICKY
        observing = true
        val container = (application as LapwingApplication).container
        lifecycleScope.launch {
            combine(container.connection.state, container.race.state) { conn, race -> conn to race }.collect { (conn, race) ->
                val text = when (conn) {
                    ConnectionState.Disconnected -> {
                        stopSelf()
                        return@collect
                    }
                    is ConnectionState.Connecting -> "Connecting to ${conn.name}…"
                    is ConnectionState.Reconnecting -> "Waiting for ${conn.name}…"
                    is ConnectionState.Connected -> race.laps.lastOrNull()?.let {
                        if (it.isHoleShot) "Hole shot ${formatSeconds(it.timeMs)} s" else "Lap ${it.index}: ${formatSeconds(it.timeMs)} s"
                    } ?: "Connected to ${conn.name}"
                }
                getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, LapwingApplication.NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
        }
    }
}
