// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import android.content.Context
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.size.Dimension

/**
 * The service's artwork is served whole, up to 1920 pixels wide and a couple of megabytes, for cards
 * a few hundred pixels wide. Its image hosts resize on request, as the official app asks them to: each
 * image is asked for as WebP at the width it is drawn at, rounded up to a step so that close sizes
 * share a download. Some ten kilobytes in place of hundreds.
 */
private class SizedArtwork : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed(chain.request)
        val width = (chain.size.width as? Dimension.Pixels)?.px
        val sized = sizedArtworkUrl(url, width) ?: return chain.proceed(chain.request)
        return chain.proceed(chain.request.newBuilder().data(sized).build())
    }
}

private val RESIZING_HOSTS = listOf("https://imgs.fubo.tv/", "https://molotov-imgx.fubo.tv/", "https://images-eu.fubo.tv/")
private const val WIDTH_STEP = 160
private const val MAX_WIDTH = 1920

/**
 * [url] asked for as WebP, [width] pixels wide when known (rounded up to [WIDTH_STEP]); null when its
 * host does not resize, or the address already says how it wants the image.
 */
internal fun sizedArtworkUrl(url: String, width: Int?): String? {
    if (RESIZING_HOSTS.none { url.startsWith(it) }) return null
    val query = url.substringAfter('?', "")
    if (query.split('&').any { it.startsWith("w=") || it.startsWith("fm=") }) return null
    val separator = if ('?' in url) '&' else '?'
    val step = width?.takeIf { it > 0 }?.let { ((it + WIDTH_STEP - 1) / WIDTH_STEP * WIDTH_STEP).coerceAtMost(MAX_WIDTH) }
    return if (step != null) "$url${separator}fm=webp&w=$step" else "$url${separator}fm=webp"
}

/** The image loader of both apps: Coil's own, with the artwork asked for at the size it is drawn. */
fun artworkImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context).components { add(SizedArtwork()) }.build()
