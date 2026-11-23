// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice as PlayServicesCastDevice
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import it.allard.etincelle.core.remote.RemoteDiscovery
import it.allard.etincelle.core.remote.RemoteTv
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Route id prefix of an etincelle TV app found on the local network (see core/remote). */
const val TV_ROUTE_PREFIX = "tv:"

/** A reachable Cast device: a Chromecast or Cast-enabled TV, or an etincelle TV app when [tvApp]. */
data class CastDevice(val routeId: String, val name: String, val tvApp: Boolean = false) {
    /** The name with what the device is, as the list shows it: a TV app and a Chromecast often share a name. */
    val label: String get() = if (tvApp) "$name (appli TV)" else "$name (Chromecast)"
}

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
    // The connected device is an etincelle TV app (driven by etincelle's own remote control), which
    // plays the next episode itself and can be navigated from the phone.
    val toTvApp: Boolean get() = connectedRouteId?.startsWith(TV_ROUTE_PREFIX) == true
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
    protected val receiverAppId: String = CastReceiver.appId(context)
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    // Chromecasts found by our own scan (see CastScanner), by device id, with the number of scans in
    // a row that missed them. They are listed only when Play Services does not see them itself.
    private class ScannedDevice(val device: DirectCastDevice, var missed: Int = 0)
    private val scannedDevices = LinkedHashMap<String, ScannedDevice>()

    // etincelle TV apps open on the local network (see RemoteDiscovery), by id, likewise counted.
    private class ScannedTv(val tv: RemoteTv, var missed: Int = 0)
    private val scannedTvs = LinkedHashMap<String, ScannedTv>()

    // The Wi-Fi network both lists were found on: nothing of them can be reached from another one.
    private var scannedOn: Network? = null

    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    // A real Cast session is up right now. Stricter than [CastUiState.isCasting], which can briefly lag
    // a session that has already torn down: used to gate volume-key interception so a press is never
    // swallowed once the receiver is gone.
    open val isCastSessionActive: Boolean get() = castContext.sessionManager.currentCastSession != null

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

    // Our own scan while the list of devices is looked at (see [setActiveScan]).
    private var listScan: Job? = null

    // One scan of ours at a time: two counting the same answers would drop a device twice as fast.
    private val scanLock = Mutex()

    private var listening = false
    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onSessionConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onSessionConnected(session)
        override fun onSessionEnded(session: CastSession, error: Int) = onSessionDisconnected()
        // A suspend is transient (brief network drop); keep the cast player current and wait for
        // onSessionResumed/onSessionEnded, rather than bouncing playback to the phone and back.
        override fun onSessionSuspended(session: CastSession, reason: Int) = onSessionSuspendedInternal(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) = onSessionStartFailed()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = onSessionDisconnected()
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionEnding(session: CastSession) = onSessionEndingInternal(session)
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
    }

    open fun start() {
        mediaRouter.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        if (!listening) {
            castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            listening = true
        }
        refreshDevices()
        val session = castContext.sessionManager.currentCastSession
        when {
            session == null -> onSessionDisconnected()
            // One that is still starting is not yet one to load anything on, and what was sent to it
            // would be lost: its listener says how the start ends.
            session.isConnected -> onSessionConnected(session)
        }
        discoveryScope?.cancel()
        discoveryScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).also { scope ->
            scope.launch { rediscoverWhileIdle() }
            scope.launch { listenForTvs() }
        }
        // The list of devices may have stayed open while the app was away.
        scanForList()
    }

    // Discovery pauses while the app is away, but the session keeps being followed: a cast that ends
    // meanwhile (TV off, stopped on the TV, a recovery giving up) would otherwise leave the phone
    // showing it as playing, with its notification, until the app came back.
    open fun stop() {
        discoveryScope?.cancel()
        discoveryScope = null
        scanForList()
        mediaRouter.removeCallback(routerCallback)
    }

    /** Stops following the session; the controller is not used afterwards. */
    open fun release() {
        if (listening) castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
        listening = false
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
     * Our own scan runs at once too and keeps running meanwhile, also during a cast, when nothing else
     * looks for devices: the list would otherwise show what was there before the cast started.
     */
    fun setActiveScan(enabled: Boolean) {
        if (enabled == activeScanning) return
        activeScanning = enabled
        scanForList()
        refreshDevices()
    }

    // Scans as [activeScanning] asks, for as long as discovery runs (between [start] and [stop]).
    private fun scanForList() {
        listScan?.cancel()
        listScan = null
        val scope = discoveryScope
        if (activeScanning && scope != null) {
            mediaRouter.addCallback(selector, activeScanCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            listScan = scope.launch {
                var passes = 0
                while (true) {
                    // The whole subnet on opening, then now and then: a TV app found stays asked.
                    scanDirectly(everyHost = passes++ % LIST_EVERY_HOST_PASSES == 0)
                    refreshDevices()
                    delay(LIST_SCAN_MS)
                }
            }
        } else {
            mediaRouter.removeCallback(activeScanCallback)
        }
    }

    /** A Play Services session could not be started. */
    protected open fun onSessionStartFailed() = onSessionDisconnected()

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

    /** The session dropped transiently (kept current, riding out the network blip). Subclasses use this
     *  to suppress stall recovery while suspended, since the receiver state freezes across a suspend. */
    protected open fun onSessionSuspendedInternal(session: CastSession) = Unit

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
        // The device a direct cast is on stays listed as our own find of it: the menu marks what is
        // connected by its entry, and its Play Services route, picked there, would start the cast over.
        val casting = scannedDevice(_state.value.connectedRouteId.orEmpty())
        val routes = mediaRouter.routes
            .filter { it.matchesSelector(selector) && !it.isDefault && !it.isBluetooth }
            .filter { casting?.isBehind(it) != true }
        // Any other device Play Services sees is used through it; match by Cast device id, or by name in
        // case the two ids differ.
        val scanned = scannedDevices.values.map { it.device }
            .filter { device -> routes.none { device.isBehind(it) } }
            .map { CastDevice(DIRECT_ROUTE_PREFIX + it.id, it.name) }
        val tvs = scannedTvs.values.map { CastDevice(TV_ROUTE_PREFIX + it.tv.id, it.tv.name, tvApp = true) }
        _state.value = _state.value.copy(devices = routes.map { CastDevice(it.id, it.name) } + scanned + tvs)
    }

    // Matched by Cast device id, or by name in case the two ids differ. By name only at the same
    // address when the route gives one: two Chromecasts given one name are two devices, and taken for
    // one, the second was hidden from the list, or cast onto in place of the first.
    private fun DirectCastDevice.isBehind(route: MediaRouter.RouteInfo): Boolean {
        val cast = PlayServicesCastDevice.getFromBundle(route.extras)
        if (id == cast?.deviceId) return true
        // An IPv6 address says nothing against our IPv4 one.
        val address = cast?.inetAddress?.hostAddress?.takeUnless { ':' in it }
        return name == route.name && (address == null || address == host)
    }

    /** The Chromecast from our own scan that is behind the Play Services route [routeId], if any. */
    protected fun scannedTwin(routeId: String): DirectCastDevice? {
        val route = mediaRouter.routes.firstOrNull { it.id == routeId } ?: return null
        return scannedDevices.values.map { it.device }.firstOrNull { it.isBehind(route) }
    }

    /** The Chromecast our own scan found under [routeId] (see [DIRECT_ROUTE_PREFIX]), if any. */
    protected fun scannedDevice(routeId: String): DirectCastDevice? =
        routeId.takeIf { it.startsWith(DIRECT_ROUTE_PREFIX) }?.let { scannedDevices[it.removePrefix(DIRECT_ROUTE_PREFIX)]?.device }

    /** The etincelle TV app found under [routeId] (see [TV_ROUTE_PREFIX]), if any. */
    protected fun scannedTv(routeId: String): RemoteTv? =
        routeId.takeIf { it.startsWith(TV_ROUTE_PREFIX) }?.let { scannedTvs[it.removePrefix(TV_ROUTE_PREFIX)]?.tv }

    /** The phone now drives the etincelle TV app [tv]. */
    protected fun markTvConnected(tv: RemoteTv) {
        _state.value = _state.value.copy(connectedDeviceName = tv.name, connectedRouteId = TV_ROUTE_PREFIX + tv.id)
    }

    /** A session with a Chromecast from our own scan is up (it does not go through Play Services). */
    protected fun markDirectConnected(device: DirectCastDevice) {
        _state.value = _state.value.copy(connectedDeviceName = device.name, connectedRouteId = DIRECT_ROUTE_PREFIX + device.id)
        refreshDevices()
    }

    // Our own scan, run with each active scan pulse; folds the answers into [scannedDevices]. The TV
    // apps are asked at each address of the subnet when [everyHost], else only those already found.
    private suspend fun scanDirectly(everyHost: Boolean = true) = scanLock.withLock {
        val network = wifiNetwork()
        // Off Wi-Fi no scan runs, so nothing would ever count as missed: the list kept offering the
        // devices of a network left an hour ago. Forget them as soon as that network is gone or replaced.
        if (network != scannedOn) {
            scannedOn = network
            scannedDevices.clear()
            scannedTvs.clear()
            refreshDevices()
        }
        if (network == null) return@withLock
        val found = withContext(Dispatchers.IO) { CastScanner.scan(receiverAppId, network) }
        val foundIds = found.map { it.id }.toSet()
        for (entry in scannedDevices.values) if (entry.device.id !in foundIds) entry.missed++
        scannedDevices.values.removeAll { it.missed >= MAX_MISSED_SCANS }
        for (device in found) scannedDevices[device.id] = ScannedDevice(device)
        val hosts = if (everyHost) {
            connectivity?.getLinkProperties(network)?.linkAddresses.orEmpty()
                .firstOrNull { it.address.address.size == 4 }
                ?.let { RemoteDiscovery.subnetHosts(it.address.address, it.prefixLength) }.orEmpty()
        } else {
            // Numeric addresses, never looked up.
            scannedTvs.values.mapNotNull { runCatching { InetAddress.getByName(it.tv.host).address }.getOrNull() }
        }
        val tvs = withContext(Dispatchers.IO) { RemoteDiscovery.scan(network, hosts) }
        val tvIds = tvs.map { it.id }.toSet()
        for (entry in scannedTvs.values) if (entry.tv.id !in tvIds) entry.missed++
        scannedTvs.values.removeAll { it.missed >= MAX_MISSED_SCANS }
        for (tv in tvs) scannedTvs[tv.id] = ScannedTv(tv)
    }

    // A TV app announcing itself as it opens joins the list at once, even while this phone is connected and
    // so not scanning. Until the discovery stops, while the app is in the foreground.
    private suspend fun listenForTvs() = coroutineScope {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(RemoteDiscovery.PORT))
            }
        }.getOrNull() ?: return@coroutineScope
        launch(Dispatchers.IO) {
            RemoteDiscovery.listen(socket) { tv ->
                launch(Dispatchers.Main) {
                    // Nothing ages these out while this phone is connected and not scanning.
                    if (scannedTvs.size < RemoteDiscovery.MAX_TVS || tv.id in scannedTvs) {
                        scannedTvs[tv.id] = ScannedTv(tv)
                        refreshDevices()
                    }
                }
            }
        }
        // The listener waits in a read that cancelling does not end: only closing its socket does, or
        // each stop of the discovery would leave its thread and its socket behind.
        try {
            awaitCancellation()
        } finally {
            socket.close()
        }
    }

    // The Wi-Fi network, so the scan never goes out over mobile data or into a VPN. allNetworks is the
    // only way to list the networks on API 23 to 30.
    @Suppress("DEPRECATION")
    protected fun wifiNetwork(): Network? = connectivity?.allNetworks?.firstOrNull { network ->
        val caps = connectivity.getNetworkCapabilities(network)
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    // While the app is foregrounded and not casting, a Chromecast that briefly dropped off WiFi is
    // gone from the route list, and the single active scan in start() will not bring it back, so the
    // cast button vanishes until the app is restarted. Run a short active scan on a timer so a device
    // that reappears is rediscovered on its own, without the battery cost of scanning continuously.
    // The TV apps are asked at each address of the subnet on the first pulse and every
    // [EVERY_HOST_PULSES] after it, a few hundred packets each time; in between, by broadcast and at
    // the addresses of those already found, which keeps them listed.
    private suspend fun rediscoverWhileIdle() {
        var pulses = 0
        while (true) {
            // Scan first, then wait, so the cast button appears as fast as the platform chooser would on
            // app open / return-to-foreground, instead of only after the first idle interval elapses.
            if (state.value.connectedDeviceName == null) activeScanPulse(everyHost = pulses++ % EVERY_HOST_PULSES == 0)
            delay(REDISCOVERY_INTERVAL_MS)
        }
    }

    // One short active scan, then back to passive discovery, refreshing the device list afterwards.
    // Our own scan runs alongside, for the Chromecasts Play Services cannot see.
    private suspend fun activeScanPulse(everyHost: Boolean) {
        mediaRouter.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        scanDirectly(everyHost)
        refreshDevices()
        delay(ACTIVE_SCAN_MS)
        mediaRouter.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refreshDevices()
    }

    protected companion object {
        // Idle re-discovery cadence: a short active scan every interval brings a dropped Chromecast
        // back within roughly fifteen seconds, without scanning continuously.
        const val REDISCOVERY_INTERVAL_MS = 12_000L
        // An idle pulse in this many asks each address of the subnet for TV apps, about once a minute.
        const val EVERY_HOST_PULSES = 5
        // A Chromecast our own scan misses this many times in a row is dropped from the list.
        const val MAX_MISSED_SCANS = 3
        // Route id prefix of a Chromecast found by our own scan rather than by Play Services.
        const val DIRECT_ROUTE_PREFIX = "direct:"
        const val ACTIVE_SCAN_MS = 4_000L
        // Between two of our own scans while the list of devices is looked at.
        const val LIST_SCAN_MS = 2_000L
        // A pass in this many, the first, asks each address of the subnet for TV apps: about every 20 s.
        const val LIST_EVERY_HOST_PASSES = 5
    }
}
