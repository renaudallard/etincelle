// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import it.allard.etincelle.core.remote.RemoteAuthException
import it.allard.etincelle.core.remote.RemoteDiscovery
import it.allard.etincelle.core.remote.RemoteLink
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Lets a phone signed in to the same account drive this TV app (see docs/tv-remote-design.md). It takes
 * connections on a port of its own, announces itself as it starts and answers the phones looking for TV apps
 * (see [RemoteDiscovery]) with the TV's name, and lets in the phones that prove the account key. One
 * drives the TV, the latest to have asked for something: its commands reach [onCommand] on the main
 * thread. The latest let in since is kept beside it and takes its place by asking for something, not by
 * connecting: a phone app coming back to this TV by itself as it is reopened must not take the TV from
 * the phone someone is driving it with. [send] tells both how playback goes. It may run for days (see
 * [TvRemote]), so nothing short of [stop] ends it. [start], [stop] and [send] are main-thread calls.
 */
class TvRemoteServer(
    private val context: Context,
    private val accountKey: suspend () -> ByteArray?,
    private val onCommand: (JSONObject) -> Unit,
) {
    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var discovery: DatagramSocket? = null
    // Messages to the phones leave off the main thread while the server runs, on a thread for each of
    // the two kept, so that one that stopped reading does not hold up the other's.
    private var writer: ExecutorService? = null

    // A phone let in, and whether a message to it is waiting to leave or leaving.
    private class Phone(val link: RemoteLink) {
        val sending = AtomicBoolean()
    }

    // The phone driving this TV, and the latest let in that has asked for nothing yet (main thread).
    private var driver: Phone? = null
    private var waiting: Phone? = null

    // The account key and until when it is taken as current (main thread). A connection must cost no call
    // to the backend, or any device on the network could flood the app's own calls with them; yet the
    // profiles it is made of may change while the server runs, so it is worked out again now and then.
    private var key: ByteArray? = null
    private var keyUntil = 0L
    private var keyAt = 0L

    // Counts the accounts signed out, so what was started for one (a key being worked out, a phone
    // proving it) is not kept once that account has left.
    private var accounts = 0
    private val keyLock = Mutex()

    // The addresses with a handshake in progress (main thread): each holds a thread, so a few at a time
    // is all phones need, and one per address, so a single device cannot keep them all busy.
    private val handshakes = HashSet<InetAddress?>()

    // The connections not yet through their handshake (main thread), to close should the server stop
    // meanwhile: nothing else would, as what waits on them is then cancelled.
    private val opening = HashSet<Socket>()

    /** A phone is connected. */
    val connected: Boolean get() = driver != null || waiting != null

    /** How many phones were let in so far: one more tells a new phone from the one before. */
    var phones = 0
        private set

    fun start() {
        if (scope != null) return
        // Bound here, so stop() always finds them to close (binding sends nothing on the network).
        // On a port that stays the same when it is free, so a phone that listed this TV before the server
        // last started still connects to it.
        val server = runCatching { ServerSocket(RemoteDiscovery.PORT) }.recoverCatching { ServerSocket(0) }.getOrNull() ?: return
        val discovery = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(RemoteDiscovery.PORT))
            }
        }.getOrNull()
        this.server = server
        this.discovery = discovery
        writer = Executors.newFixedThreadPool(2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        // Ready before the first phone connects.
        scope.launch { refreshKey() }
        discovery?.let { socket -> scope.launch(Dispatchers.IO) { answerDiscovery(socket, server.localPort) } }
        scope.launch {
            // A connection that fails as it comes in must not be the last one taken.
            while (isActive && !server.isClosed) {
                val socket = runCatching { withContext(Dispatchers.IO) { server.accept() } }.getOrNull()
                if (socket != null) launch { serve(socket) } else delay(RETRY_MS)
            }
        }
    }

    fun stop() {
        runCatching { discovery?.close() }
        discovery = null
        runCatching { server?.close() }
        server = null
        dropPhones()
        opening.forEach { runCatching { it.close() } }
        opening.clear()
        scope?.cancel()
        scope = null
        key = null
        keyUntil = 0
        writer?.shutdown()
        writer = null
    }

    /**
     * The account was signed out: its key no longer opens this TV, and the phones it let in are let go.
     * The key of the account signed in next is worked out as its first phone connects.
     */
    fun forgetAccount() {
        accounts++
        dropPhones()
        key = null
        keyUntil = 0
    }

    private fun dropPhones() {
        driver?.link?.close()
        driver = null
        waiting?.link?.close()
        waiting = null
    }

    /**
     * Sends [message] to the phones; false when it leaves for none, for want of a phone or with the
     * last one still leaving.
     */
    fun send(message: JSONObject): Boolean {
        val text = message.toString()
        var leaving = false
        for (phone in listOfNotNull(driver, waiting)) {
            // One at a time: each tells how playback goes now and makes the one before moot, and a phone
            // that stopped reading while keeping its connection must not have them pile up for days.
            if (!phone.sending.compareAndSet(false, true)) continue
            val queued = runCatching {
                writer?.execute {
                    try {
                        phone.link.send(text)
                    } catch (e: Exception) {
                        // The phone left: its reader ends the link.
                    } finally {
                        phone.sending.set(false)
                    }
                }
            }
            if (queued.getOrNull() != null) leaving = true else phone.sending.set(false)
        }
        return leaving
    }

    // One phone: the handshake, then its commands until it leaves. It waits beside the phone driving the
    // TV, in the place of the one that waited there, and drives the TV from the first thing it asks for.
    private suspend fun serve(socket: Socket) {
        opening.add(socket)
        val link = try {
            greet(socket)
        } finally {
            opening.remove(socket)
        } ?: return
        val phone = Phone(link)
        waiting?.link?.close()
        waiting = phone
        phones++
        try {
            while (true) {
                val message = withContext(Dispatchers.IO) { link.receive() }
                if (waiting === phone) {
                    driver?.link?.close()
                    driver = phone
                    waiting = null
                } else if (driver !== phone) {
                    // Another phone took its place while this command was on its way: it goes with it.
                    break
                }
                runCatching { JSONObject(message) }.getOrNull()?.let(onCommand)
            }
        } catch (e: Exception) {
            // The phone left, or broke the protocol: drop it.
        } finally {
            link.close()
            if (driver === phone) driver = null
            if (waiting === phone) waiting = null
        }
    }

    // The handshake with the phone behind [socket]: its link once it has proved the account key, or null
    // with the connection closed.
    private suspend fun greet(socket: Socket): RemoteLink? {
        // The phone waits a few seconds for the greeting: past a short wait for a newer key, the one in
        // hand serves, and the newer one the next connection.
        val refresh = scope?.launch { refreshKey() }
        withTimeoutOrNull(KEY_WAIT_MS) { refresh?.join() }
        val key = key
        val account = accounts
        if (key == null) {
            runCatching { socket.close() }
            return null
        }
        // Its read timeout only bounds each read: a device trickling bytes must not hold a thread for long.
        val address = socket.inetAddress
        if (handshakes.size >= MAX_HANDSHAKES || !handshakes.add(address)) {
            runCatching { socket.close() }
            return null
        }
        val deadline = scope?.launch {
            delay(HANDSHAKE_DEADLINE_MS)
            runCatching { socket.close() }
        }
        // Not cancellable, so the handshake under way is seen through. Its link is not handed back to a
        // server stopped meanwhile, which closed the connection itself.
        val phone = try {
            runCatching { withContext(Dispatchers.IO + NonCancellable) { RemoteLink.accept(socket, key) } }
                .onFailure { if (it is RemoteAuthException) keyRefused() }
                .getOrNull()
        } finally {
            handshakes.remove(address)
            deadline?.cancel()
        } ?: return null
        if (account != accounts) {
            phone.close()
            return null
        }
        return phone
    }

    // Works the account key out again once it is no longer current.
    private suspend fun refreshKey() = keyLock.withLock {
        if (SystemClock.elapsedRealtime() < keyUntil) return@withLock
        // A call that fails (no network yet) keeps the key in hand; signed out, there is none.
        val account = accounts
        val asked = runCatching { accountKey() }
        // Stopped or signed out meanwhile: the key was dropped, and is not to come back.
        currentCoroutineContext().ensureActive()
        if (account != accounts) return@withLock
        asked.onSuccess { key = it }
        keyAt = SystemClock.elapsedRealtime()
        keyUntil = keyAt + if (asked.isSuccess && key != null) KEY_CURRENT_MS else KEY_RETRY_MS
    }

    // A phone failed to prove the key. One of this account holds a newer key when a profile was added or
    // removed since this one was worked out: the next connection works it out again, yet a stranger
    // failing on purpose costs a call no more often than a failed one is retried.
    private fun keyRefused() {
        keyUntil = minOf(keyUntil, keyAt + KEY_RETRY_MS)
    }

    // Tells the phones already listening that this TV app opened, then each phone looking for TV apps where
    // to connect, on [socket], until the server stops: a request that cannot be answered, as the network
    // drops, must not be the last one heard.
    private fun answerDiscovery(socket: DatagramSocket, port: Int) {
        RemoteDiscovery.announce(socket, RemoteDiscovery.answer(port, installId(), deviceName()))
        val buffer = ByteArray(512)
        while (!socket.isClosed) {
            val served = runCatching {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (RemoteDiscovery.isRequest(packet.data, packet.length)) {
                    // The TV may have been renamed since the server started.
                    val answer = RemoteDiscovery.answer(port, installId(), deviceName())
                    socket.send(DatagramPacket(answer, answer.size, packet.socketAddress))
                }
            }
            if (served.isFailure && !socket.isClosed) Thread.sleep(RETRY_MS)
        }
    }

    // The name the TV goes by in its settings, else its model.
    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // A random id of this install, so the phone tells two TVs of the same name apart.
    private fun installId(): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ID, it).apply() }
    }

    private companion object {
        const val PREFS = "remote"
        const val KEY_ID = "install_id"
        const val MAX_HANDSHAKES = 4
        const val HANDSHAKE_DEADLINE_MS = 5_000L
        const val KEY_CURRENT_MS = 5 * 60_000L
        const val KEY_RETRY_MS = 10_000L
        const val KEY_WAIT_MS = 2_000L
        // Before taking the next connection or discovery request after one that failed.
        const val RETRY_MS = 1_000L
    }
}
