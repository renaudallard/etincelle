// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the home screen row, Watch Next ([TvHome]) and the system guide's channels ([TvLive]) fresh:
 * every few hours, and at once when the app leaves the screen, where they are seen, or when the
 * system asks for the app's channels.
 */
class TvHomeJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val repo = (application as EtincelleTvApp).container.repository
            runCatching { TvHome.sync(applicationContext, repo) }
            runCatching { TvLive.sync(applicationContext, repo) }
            jobFinished(params, false)
        }
        return true
    }

    // The system stopped the job (no network any more…); the next run catches up.
    override fun onStopJob(params: JobParameters): Boolean = false

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PERIODIC_ID = 1
        private const val NOW_ID = 2
        private const val PERIOD_MS = 3L * 60 * 60 * 1000

        /** Schedules the periodic sync if it is not already, and a sync [now]. */
        fun schedule(context: Context, now: Boolean = true) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val service = ComponentName(context, TvHomeJob::class.java)
            if (scheduler.allPendingJobs.none { it.id == PERIODIC_ID }) {
                scheduler.schedule(
                    JobInfo.Builder(PERIODIC_ID, service)
                        .setPeriodic(PERIOD_MS)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build(),
                )
            }
            if (!now) return
            scheduler.schedule(
                JobInfo.Builder(NOW_ID, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(0)
                    .build(),
            )
        }
    }
}

/** The system asks a newly installed app for its home screen channels. */
class TvHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = TvHomeJob.schedule(context)
}
