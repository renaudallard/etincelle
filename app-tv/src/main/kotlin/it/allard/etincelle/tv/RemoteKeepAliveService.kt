// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.app.Service
import android.content.BroadcastReceiver
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
 * Keeps the app and its remote control (see [TvRemote]) running with nothing in front, so a phone can
 * still find and drive it: Android may freeze or end an app that shows nothing. MainActivity starts it as
 * it comes to the front and stops it once the remote control is no longer kept. When that is never, it
 * also starts with the TV and after an update of the app ([RemoteStarter]), and Android starts it again
 * should it end the app to free memory.
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
        // Android may refuse it to an app that is not in front.
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        if (started.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        val remote = (application as EtincelleTvApp).remote
        remote.start()
        return if (remote.always) START_STICKY else START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "remote"
        private const val NOTIFICATION_ID = 1

        /** Starts it; Android allows it while the app is in front, or as [RemoteStarter] is called. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RemoteKeepAliveService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteKeepAliveService::class.java))
        }
    }
}

/** Starts the remote control with the TV, and again after an update of the app, when it is kept at all times. */
class RemoteStarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (canComeBack(context)) RemoteKeepAliveService.start(context)
    }
}
