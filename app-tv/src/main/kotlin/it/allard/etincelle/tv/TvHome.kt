// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.BaseColumns
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.model.HomeProgram
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app on the Android TV home screen: an "etincelle" row of the programmes the backend recommends,
 * and the programmes to continue watching in the system's Watch Next. Both mirror the backend's own
 * lists (as the official app does), so each sync replaces what was there; signed out, both are emptied.
 */
object TvHome {
    private const val CHANNEL_KEY = "etincelle-recommendations"

    // Syncs can overlap (the app starting while the periodic job runs); each replaces the whole row,
    // so they take turns rather than both inserting.
    private val lock = Mutex()

    /** Brings the row and Watch Next up to date. Runs off the main thread (it does disk and network IO). */
    suspend fun sync(context: Context, repo: MolotovRepository) = lock.withLock {
        if (repo.currentSession() == null) runCatching { repo.restoreSession() }
        if (repo.currentSession() == null) {
            replaceRow(context, emptyList())
            replaceWatchNext(context, emptyList())
            return@withLock
        }
        runCatching { repo.homeRecommendations() }.onSuccess { replaceRow(context, it) }
        runCatching { repo.continueWatching() }.onSuccess { replaceWatchNext(context, it) }
    }

    private fun replaceRow(context: Context, programs: List<HomeProgram>) {
        val helper = PreviewChannelHelper(context)
        val existing = helper.allChannels.firstOrNull { it.internalProviderId == CHANNEL_KEY }
        if (existing == null && programs.isEmpty()) return
        // A provider that refuses the channel answers no row, which the helper cannot parse.
        val channelId = existing?.id ?: runCatching {
            helper.publishChannel(
                PreviewChannel.Builder()
                    .setDisplayName("etincelle")
                    .setDescription("Recommandations")
                    .setInternalProviderId(CHANNEL_KEY)
                    .setAppLinkIntentUri(Uri.parse(launchIntent(context).toUri(Intent.URI_INTENT_SCHEME)))
                    .setLogo(logo(context))
                    .build(),
            )
        }.getOrNull()?.also {
            // Only the app's first channel may ask to be shown; the user can still hide it.
            runCatching { TvContractCompat.requestChannelBrowsable(context, it) }
        } ?: return
        idsOf(context, TvContractCompat.buildPreviewProgramsUriForChannel(channelId)).forEach { helper.deletePreviewProgram(it) }
        for (p in programs) {
            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(programType(p))
                .setTitle(p.title)
                .setDescription(p.episodeTitle ?: p.description)
                .setPosterArtUri(p.imageUrl?.let(Uri::parse))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(detailLink(p))
                .setInternalProviderId(p.assetId)
            p.durationSec?.let { builder.setDurationMillis((it * 1000).toInt()) }
            if (p.liveStartMs != null) builder.setLive(true)
            if (!insert(context, TvContractCompat.PreviewPrograms.CONTENT_URI, builder.build().toContentValues())) break
        }
    }

    private fun replaceWatchNext(context: Context, programs: List<HomeProgram>) {
        // The provider only returns this app's own Watch Next entries.
        idsOf(context, TvContractCompat.WatchNextPrograms.CONTENT_URI).forEach {
            context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null)
        }
        val now = System.currentTimeMillis()
        for (p in programs) {
            val position = p.positionSec
            val builder = WatchNextProgram.Builder()
                .setType(programType(p))
                .setWatchNextType(
                    if (position != null) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                    else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
                )
                .setLastEngagementTimeUtcMillis(p.watchedAtMs ?: now)
                .setTitle(p.title)
                .setEpisodeTitle(p.episodeTitle)
                .setDescription(p.description)
                .setPosterArtUri(p.imageUrl?.let(Uri::parse))
                .setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(detailLink(p))
                .setInternalProviderId(p.assetId)
            position?.let { builder.setLastPlaybackPositionMillis((it * 1000).toInt()) }
            p.durationSec?.let { builder.setDurationMillis((it * 1000).toInt()) }
            if (!insert(context, TvContractCompat.WatchNextPrograms.CONTENT_URI, builder.build().toContentValues())) break
        }
    }

    // Some launchers' providers refuse the rows (Google TV turns Watch Next inserts down as "database
    // full" and answers no row); stop there instead of failing on every remaining one.
    private fun insert(context: Context, uri: Uri, values: ContentValues): Boolean =
        runCatching { context.contentResolver.insert(uri, values) }.getOrNull() != null

    // Opens the programme's page in the app (etincelle://program/{asset id}).
    private fun detailLink(p: HomeProgram): Uri = Uri.parse("etincelle://program/${Uri.encode(p.assetId)}")

    private fun programType(p: HomeProgram) = when (p.kind) {
        HomeProgram.Kind.MOVIE -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        HomeProgram.Kind.EPISODE -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
        HomeProgram.Kind.OTHER -> TvContractCompat.PreviewPrograms.TYPE_EVENT
    }

    private fun idsOf(context: Context, uri: Uri): List<Long> =
        context.contentResolver.query(uri, arrayOf(BaseColumns._ID), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }.orEmpty()

    private fun launchIntent(context: Context): Intent =
        context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)

    // The row's logo: the app icon, drawn into a bitmap (the provider stores an image, not a resource).
    private fun logo(context: Context): Bitmap {
        val icon = requireNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_launcher))
        val size = 256
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(it))
        }
    }
}
