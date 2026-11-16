// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app running while the TV's screensaver is up, so a phone can still find and drive it (see
 * TvRemoteServer): the screensaver stops the activity, and Android may freeze an app with nothing in
 * front. A foreground service can only be started while the app is in front, so MainActivity starts it
 * as it comes to the front and stops it once the remote control is no longer kept.
 */
class RemoteKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_MIN).setName(getString(R.string.app_name)).build(),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Télécommande du téléphone prête")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "remote"
        private const val NOTIFICATION_ID = 1

        /** Starts it; only while the app is in front, where Android allows it. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RemoteKeepAliveService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteKeepAliveService::class.java))
        }
    }
}
