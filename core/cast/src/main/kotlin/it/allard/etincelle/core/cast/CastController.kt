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
    // The route id of the connected device, so the picker marks the right row even when two devices
    // share a friendly name (a name match alone would select both).
    val connectedRouteId: String? = null,
    // True from the moment a device is tapped until the receiver actually starts playing; with
    // connectingDeviceName it drives the "filling" connect animation before the session is up.
    val connecting: Boolean = false,
    val connectingDeviceName: String? = null,
    // True once the receiver is actually playing the stream (the animation then snaps full).
    val playing: Boolean = false,
    // Last cast-device volume (0..1) and a counter bumped on each change, so the phone can flash a
    // brief volume overlay while the system volume bar is suppressed.
    val volumeLevel: Float = 1f,
    val volumeNonce: Int = 0,
) {
    val isCasting: Boolean get() = connectedDeviceName != null
    val available: Boolean get() = devices.isNotEmpty() || isCasting
    // Whether to show the persistent cast bar: a session is up, or one is being established.
    val showStatus: Boolean get() = isCasting || connecting
    // The device to name in the bar. While a connect is in flight (including a device-to-device
    // switch where the old device is still connected) the target is what matters; otherwise the
    // connected one.
    val statusDeviceName: String? get() = if (connecting) connectingDeviceName ?: connectedDeviceName else connectedDeviceName
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

    // A real Cast session is up right now. Stricter than [CastUiState.isCasting], which can briefly lag
    // a session that has already torn down: used to gate volume-key interception so a press is never
    // swallowed once the receiver is gone.
    val isCastSessionActive: Boolean get() = castContext.sessionManager.currentCastSession != null

    // Drives the idle re-discovery loop; recreated on each start() so a fresh start gets a fresh
    // loop and stop() can cancel it cleanly.
    private var discoveryScope: CoroutineScope? = null

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
    }

    // A separate callback record carrying an active-scan request, toggled while the cast picker is open
    // (see [setActiveScan]). Kept distinct from [routerCallback] so toggling the active scan does not
    // disturb the passive/idle discovery flags on the shared record.
    private val activeScanCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
    }
    private var activeScanning = false

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
        setActiveScan(false)
        mediaRouter.removeCallback(routerCallback)
        castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
    }

    /** Route playback to the given Cast device. Returns true only when a matching route was selected. */
    open fun connectTo(routeId: String): Boolean {
        val route = mediaRouter.routes.firstOrNull { it.id == routeId } ?: return false
        mediaRouter.selectRoute(route)
        // Start the connect animation immediately: the bar shows "Connexion à <device>…" with the
        // glyph filling until the receiver reports playback (or the session fails and clears it).
        markConnecting(_state.value.devices.firstOrNull { it.routeId == routeId }?.name)
        return true
    }

    /** Begin (or keep) the connect animation toward [deviceName]. */
    protected fun markConnecting(deviceName: String?) {
        _state.value = _state.value.copy(connecting = true, connectingDeviceName = deviceName)
    }

    /** Clear the connect animation (the session is up and playing, or it gave up). */
    protected fun clearConnecting() {
        if (_state.value.connecting) _state.value = _state.value.copy(connecting = false)
    }

    /** End the Cast session and stop the receiver (returns playback to the phone). */
    open fun disconnect() = castContext.sessionManager.endCurrentSession(true)

    /**
     * Request an aggressive active scan while [enabled] (e.g. the cast picker is open). Mirrors what the
     * platform's own chooser does: the moment the user signals intent to cast, scan hard so a device
     * that briefly dropped off (or a second device for a device-to-device switch) reappears in ~1s
     * instead of waiting for the idle re-discovery tick. Cleared (back to passive discovery) when closed.
     */
    fun setActiveScan(enabled: Boolean) {
        if (enabled == activeScanning) return
        activeScanning = enabled
        if (enabled) {
            mediaRouter.addCallback(selector, activeScanCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        } else {
            mediaRouter.removeCallback(activeScanCallback)
        }
        refreshDevices()
    }

    /** Subclass hooks for the player swap (overridden by the M2 cast-player controller). */
    protected open fun onSessionConnected(session: CastSession) {
        // Record the route id only when selectedRoute is a real cast route (the same filter the device
        // list uses). On an externally resumed session the framework may not have re-selected the cast
        // route yet, leaving the system default here; null then, so the picker falls back to the name.
        val route = mediaRouter.selectedRoute
        _state.value = _state.value.copy(
            connectedDeviceName = session.castDevice?.friendlyName,
            connectedRouteId = route.takeIf { !it.isDefault && !it.isBluetooth }?.id,
        )
    }

    protected open fun onSessionDisconnected() {
        _state.value = _state.value.copy(
            connectedDeviceName = null,
            connectedRouteId = null,
            connecting = false,
            connectingDeviceName = null,
            playing = false,
        )
    }

    protected open fun onSessionEndingInternal(session: CastSession) = Unit

    /** Set by the cast player once the receiver actually plays (clears the connecting animation). */
    protected fun setReceiverPlaying(playing: Boolean) {
        val s = _state.value
        if (s.playing == playing) return
        _state.value = s.copy(playing = playing, connecting = if (playing) false else s.connecting)
    }

    /** Publish a new cast-device volume level (0..1) so the UI can flash a brief overlay. */
    protected fun publishVolume(level: Float) {
        val s = _state.value
        _state.value = s.copy(volumeLevel = level.coerceIn(0f, 1f), volumeNonce = s.volumeNonce + 1)
    }

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
            // Scan first, then wait, so the cast button appears as fast as the platform chooser would on
            // app open / return-to-foreground, instead of only after the first idle interval elapses.
            if (state.value.connectedDeviceName == null) activeScanPulse()
            delay(REDISCOVERY_INTERVAL_MS)
        }
    }

    // One short active scan, then back to passive discovery, refreshing the device list afterwards.
    private suspend fun activeScanPulse() {
        mediaRouter.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        delay(ACTIVE_SCAN_MS)
        mediaRouter.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refreshDevices()
    }

    private companion object {
        // Idle re-discovery cadence: a short active scan every interval brings a dropped Chromecast
        // back within roughly fifteen seconds, without scanning continuously.
        const val REDISCOVERY_INTERVAL_MS = 12_000L
        const val ACTIVE_SCAN_MS = 4_000L
    }
}
