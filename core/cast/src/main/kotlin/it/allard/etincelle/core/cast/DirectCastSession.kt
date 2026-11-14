// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.net.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Cast session with a Chromecast found by [CastScanner], driven over our own [CastChannel] instead of
 * Play Services: it starts (or joins) the receiver app [appId] and talks to it on the media namespace.
 * [onMediaStatus] and [onEnded] are called on the main thread.
 */
internal class DirectCastSession(
    val device: DirectCastDevice,
    private val appId: String,
    private val network: Network?,
    private val onMediaStatus: (JSONObject) -> Unit,
    private val onEnded: (Throwable?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val senderId = "sender-etincelle-" + ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val requestIds = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val receiverStatus = MutableStateFlow<JSONObject?>(null)

    @Volatile
    private var channel: CastChannel? = null

    @Volatile
    private var transportId: String? = null

    @Volatile
    private var appSessionId: String? = null

    @Volatile
    private var mediaSessionId: Int? = null

    @Volatile
    private var ended = false

    /** The receiver's volume (0..1) and mute, as last reported. */
    var volumeLevel = 1.0
        private set
    var muted = false
        private set

    /**
     * Connects, authenticates the device and starts or joins the receiver; with [joinOnly], only joins
     * a receiver already running (to reconnect to a cast without restarting it). Throws on failure.
     */
    suspend fun start(joinOnly: Boolean = false) {
        val ch = withContext(Dispatchers.IO) { CastChannel.open(device.host, device.port, network) }
        channel = ch
        ch.start(::onMessage) { error -> scope.launch { end(error) } }
        ch.send(CastChannel.NS_CONNECTION, """{"type":"CONNECT"}""")
        // Join the receiver if another sender already started it; otherwise launch it. Newer firmware
        // first answers a launch with a status that does not list the app yet, so wait for one that does.
        val app = withTimeout(LAUNCH_TIMEOUT_MS) {
            requestReceiver(JSONObject().put("type", "GET_STATUS"))
            runningApp(receiverStatus.filterNotNull().first()) ?: run {
                if (joinOnly) throw IOException("the receiver is not running")
                requestReceiver(JSONObject().put("type", "LAUNCH").put("appId", appId))
                receiverStatus.filterNotNull().first { runningApp(it) != null || it.has(LAUNCH_ERROR_KEY) }
                    .let { runningApp(it) ?: throw IOException("the receiver did not start") }
            }
        }
        transportId = app.getString("transportId")
        appSessionId = app.optString("sessionId")
        ch.send(CastChannel.NS_CONNECTION, """{"type":"CONNECT"}""", source = senderId, destination = app.getString("transportId"))
    }

    /** Loads [media] (see [CastLoadData.mediaJson]); [currentTime] in seconds, or null for the default. */
    suspend fun load(media: JSONObject, currentTime: Double?) {
        val request = JSONObject().put("type", "LOAD").put("media", media).put("autoplay", true)
        currentTime?.let { request.put("currentTime", it) }
        val reply = requestMedia(request, LOAD_TIMEOUT_MS)
        if (reply.optString("type") != "MEDIA_STATUS") throw IOException("load refused: ${reply.optString("type")}")
    }

    fun play() = mediaCommand(JSONObject().put("type", "PLAY"))

    fun pause() = mediaCommand(JSONObject().put("type", "PAUSE"))

    fun seek(seconds: Double) = mediaCommand(JSONObject().put("type", "SEEK").put("currentTime", seconds))

    fun setVolume(level: Double) = receiverCommand(JSONObject().put("type", "SET_VOLUME").put("volume", JSONObject().put("level", level)))

    fun setMuted(muted: Boolean) = receiverCommand(JSONObject().put("type", "SET_VOLUME").put("volume", JSONObject().put("muted", muted)))

    /** Asks the receiver for a MEDIA_STATUS (statuses otherwise only come on changes). */
    fun requestStatus() {
        val transport = transportId ?: return
        val request = JSONObject().put("type", "GET_STATUS").put("requestId", nextRequestId())
        channel?.send(CastChannel.NS_MEDIA, request.toString(), source = senderId, destination = transport)
    }

    /** Stops the receiver app (the TV leaves the stream) and closes the session. */
    fun stop() {
        appSessionId?.let { id ->
            channel?.send(CastChannel.NS_RECEIVER, JSONObject().put("type", "STOP").put("sessionId", id).put("requestId", nextRequestId()).toString())
        }
        close()
    }

    /** Closes the connection; the receiver keeps playing whatever it plays. */
    fun close() {
        ended = true
        channel?.let { ch ->
            transportId?.let { ch.send(CastChannel.NS_CONNECTION, """{"type":"CLOSE"}""", source = senderId, destination = it) }
            ch.close()
        }
        failPending(IOException("session closed"))
        scope.cancel()
    }

    private fun mediaCommand(command: JSONObject) {
        val id = mediaSessionId ?: return
        val transport = transportId ?: return
        command.put("mediaSessionId", id).put("requestId", nextRequestId())
        channel?.send(CastChannel.NS_MEDIA, command.toString(), source = senderId, destination = transport)
    }

    private fun receiverCommand(command: JSONObject) {
        if (transportId == null) return
        requestReceiver(command)
    }

    private fun requestReceiver(request: JSONObject) {
        request.put("requestId", nextRequestId())
        channel?.send(CastChannel.NS_RECEIVER, request.toString())
    }

    private suspend fun requestMedia(request: JSONObject, timeoutMs: Long): JSONObject {
        val transport = transportId ?: throw IOException("not connected")
        val id = nextRequestId()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            request.put("requestId", id)
            if (channel?.send(CastChannel.NS_MEDIA, request.toString(), source = senderId, destination = transport) != true) {
                throw IOException("connection lost")
            }
            return withTimeout(timeoutMs) { reply.await() }
        } finally {
            pending.remove(id)
        }
    }

    // Reader thread: decode, then hand over to the main thread.
    private fun onMessage(message: CastMessage) {
        val payload = message.payload?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        scope.launch { handle(message, payload) }
    }

    private fun handle(message: CastMessage, payload: JSONObject) {
        val type = payload.optString("type")
        when (message.namespace) {
            CastChannel.NS_RECEIVER -> when (type) {
                "RECEIVER_STATUS" -> {
                    val status = payload.optJSONObject("status") ?: return
                    receiverStatus.value = status
                    status.optJSONObject("volume")?.let {
                        volumeLevel = it.optDouble("level", volumeLevel)
                        muted = it.optBoolean("muted", muted)
                    }
                    // The app was stopped from elsewhere (the TV, another phone): the session is over.
                    if (transportId != null && runningApp(status) == null) end(null)
                }
                "LAUNCH_ERROR" -> receiverStatus.value = JSONObject().put(LAUNCH_ERROR_KEY, payload.optString("reason"))
            }
            CastChannel.NS_MEDIA -> {
                if (type == "MEDIA_STATUS") {
                    // An empty list means nothing is loaded on the receiver.
                    val status = (payload.optJSONArray("status") ?: JSONArray()).optJSONObject(0) ?: JSONObject()
                    mediaSessionId = status.optInt("mediaSessionId").takeIf { it > 0 } ?: mediaSessionId
                    onMediaStatus(status)
                }
                pending[payload.optInt("requestId")]?.complete(payload)
            }
            CastChannel.NS_CONNECTION -> if (type == "CLOSE" && message.source == transportId) end(null)
        }
    }

    private fun end(error: Throwable?) {
        if (ended) return
        ended = true
        channel?.close()
        failPending(error ?: IOException("session ended"))
        onEnded(error)
        scope.cancel()
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private fun runningApp(status: JSONObject): JSONObject? {
        val apps = status.optJSONArray("applications") ?: return null
        return (0 until apps.length()).mapNotNull { apps.optJSONObject(it) }
            .firstOrNull { it.optString("appId") == appId && it.optString("transportId").isNotEmpty() }
    }

    private fun nextRequestId() = requestIds.incrementAndGet()

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 20_000L
        const val LOAD_TIMEOUT_MS = 30_000L
        const val LAUNCH_ERROR_KEY = "launchError"
    }
}
