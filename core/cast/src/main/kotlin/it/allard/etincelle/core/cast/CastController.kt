// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    // Drives the idle re-discovery loop; recreated on each start() so a fresh start gets a fresh
    // loop and stop() can cancel it cleanly.
    private var discoveryScope: CoroutineScope? = null

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onSessionConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onSessionConnected(session)
        override fun onSessionEnded(session: CastSession, error: Int) = onSessionDisconnected()
        // A suspend is transient (brief network drop); keep the cast player current and wait for
        // onSessionResumed/onSessionEnded, rather than bouncing playback to the phone and back.
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
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
        discoveryScope?.cancel()
        discoveryScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).also { scope ->
            scope.launch { rediscoverWhileIdle() }
        }
    }

    open fun stop() {
        discoveryScope?.cancel()
        discoveryScope = null
        mediaRouter.removeCallback(routerCallback)
        castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
    }

    /** Route playback to the given Cast device. Returns true only when a matching route was selected. */
    open fun connectTo(routeId: String): Boolean {
        val route = mediaRouter.routes.firstOrNull { it.id == routeId } ?: return false
        mediaRouter.selectRoute(route)
        return true
    }

    /** End the Cast session and stop the receiver (returns playback to the phone). */
    open fun disconnect() = castContext.sessionManager.endCurrentSession(true)

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

    // While the app is foregrounded and not casting, a Chromecast that briefly dropped off WiFi is
    // gone from the route list, and the single active scan in start() will not bring it back, so the
    // cast button vanishes until the app is restarted. Run a short active scan on a timer so a device
    // that reappears is rediscovered on its own, without the battery cost of scanning continuously.
    private suspend fun rediscoverWhileIdle() {
        while (true) {
            delay(REDISCOVERY_INTERVAL_MS)
            if (state.value.connectedDeviceName != null) continue
            mediaRouter.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            delay(ACTIVE_SCAN_MS)
            mediaRouter.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            refreshDevices()
        }
    }

    private companion object {
        // Idle re-discovery cadence: a short active scan every interval brings a dropped Chromecast
        // back within roughly fifteen seconds, without scanning continuously.
        const val REDISCOVERY_INTERVAL_MS = 12_000L
        const val ACTIVE_SCAN_MS = 4_000L
    }
}
