// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A reachable Cast device (a Chromecast / Cast-enabled TV). */
data class CastDevice(val routeId: String, val name: String)

/** What the UI needs to know about casting: discovered devices and the connected one (if any). */
data class CastUiState(
    val devices: List<CastDevice> = emptyList(),
    val connectedDeviceName: String? = null,
) {
    val isCasting: Boolean get() = connectedDeviceName != null
    val available: Boolean get() = devices.isNotEmpty() || isCasting
}

/**
 * Tracks Cast device discovery and the connected session and connects/disconnects routes. Create
 * one per Activity; call [start]/[stop] from the Activity's start/stop. Main-thread only.
 */
open class CastController(
    context: Context,
    protected val castContext: CastContext,
) {
    private val mediaRouter = MediaRouter.getInstance(context.applicationContext)
    private val selector: MediaRouteSelector = castContext.mergedSelector ?: MediaRouteSelector.EMPTY

    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onSessionConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onSessionConnected(session)
        override fun onSessionEnded(session: CastSession, error: Int) = onSessionDisconnected()
        override fun onSessionSuspended(session: CastSession, reason: Int) = onSessionDisconnected()
        override fun onSessionStartFailed(session: CastSession, error: Int) = onSessionDisconnected()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = onSessionDisconnected()
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionEnding(session: CastSession) = onSessionEndingInternal(session)
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
    }

    fun start() {
        mediaRouter.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        refreshDevices()
        castContext.sessionManager.currentCastSession?.let(::onSessionConnected) ?: onSessionDisconnected()
    }

    fun stop() {
        mediaRouter.removeCallback(routerCallback)
        castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
    }

    /** Route playback to the given Cast device. */
    open fun connectTo(routeId: String) {
        mediaRouter.routes.firstOrNull { it.id == routeId }?.let(mediaRouter::selectRoute)
    }

    /** End the Cast session and stop the receiver (returns playback to the phone). */
    fun disconnect() = castContext.sessionManager.endCurrentSession(true)

    /** Subclass hooks for the player swap (overridden by the M2 cast-player controller). */
    protected open fun onSessionConnected(session: CastSession) {
        _state.value = _state.value.copy(connectedDeviceName = session.castDevice?.friendlyName)
    }

    protected open fun onSessionDisconnected() {
        _state.value = _state.value.copy(connectedDeviceName = null)
    }

    protected open fun onSessionEndingInternal(session: CastSession) = Unit

    private fun refreshDevices() {
        val devices = mediaRouter.routes
            .filter { it.matchesSelector(selector) && !it.isDefault && !it.isBluetooth }
            .map { CastDevice(it.id, it.name) }
        _state.value = _state.value.copy(devices = devices)
    }
}
