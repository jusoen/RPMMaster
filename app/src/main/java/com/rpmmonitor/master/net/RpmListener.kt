package com.rpmmonitor.master.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.rpmmonitor.master.proto.ParseResult
import com.rpmmonitor.master.proto.RpmCodec
import com.rpmmonitor.master.state.NodeRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

private const val TAG = "RpmListener"

/** What the listener is doing, for the diagnostics screen. */
sealed interface ListenerState {
    data object Stopped : ListenerState
    data object Listening : ListenerState
    /** The socket could not be opened or died. [message] is for display, not parsing. */
    data class Failed(val message: String) : ListenerState
}

/** One received datagram, already parsed, with the sender it came from. */
data class Received(val result: ParseResult, val senderIp: String)

/**
 * UDP broadcast listener on port 4210.
 *
 * Receive-only, always. The node has no receive path, so nothing here ever opens a
 * send path to it.
 */
class RpmListener(
    context: Context,
    private val port: Int = RpmCodec.UDP_PORT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // The application context: a MulticastLock outlives any one Activity, and
    // holding an Activity here would leak it.
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<ListenerState>(ListenerState.Stopped)
    val state: StateFlow<ListenerState> = _state.asStateFlow()

    /**
     * A cold flow that owns a socket for the duration of one collection.
     *
     * **Cancellation.** A blocking `receive()` does not observe coroutine
     * cancellation — the thread stays parked in the syscall. So the socket gets a
     * [SO_TIMEOUT_MS] timeout and the loop re-checks [isActive] on every expiry.
     * Worst-case teardown latency is therefore one timeout period.
     */
    fun packets(): Flow<Received> = flow {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // Broadcast delivery usually works without the lock when the phone is the
        // hotspot, but some devices filter non-unicast frames to save power and this
        // is the documented way to opt out. A null WifiManager is not fatal.
        val lock = wifi?.createMulticastLock("rpm-master")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        if (lock == null) Log.w(TAG, "no WifiManager: running without a MulticastLock")

        // Bound outside the apply below: inside it, `port` would resolve to
        // DatagramSocket.port — which is -1 until the socket is bound.
        val bindPort = port

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = SO_TIMEOUT_MS
                bind(InetSocketAddress("0.0.0.0", bindPort))
            }
            _state.value = ListenerState.Listening
            Log.i(TAG, "listening on 0.0.0.0:$bindPort")

            // Oversized on purpose: a 20-byte read would silently truncate a longer
            // datagram into something that looks like ours. Reading it whole lets the
            // length check reject it.
            val buf = ByteArray(RECV_BUFFER_BYTES)
            val dgram = DatagramPacket(buf, buf.size)

            while (currentCoroutineContext().isActive) {
                dgram.setData(buf, 0, buf.size)
                try {
                    socket.receive(dgram)
                } catch (e: SocketTimeoutException) {
                    continue    // expected: the timeout is only there to check isActive
                }
                emit(Received(RpmCodec.parse(dgram.data, dgram.length), dgram.address.hostAddress ?: "?"))
            }
        } catch (e: CancellationException) {
            // A normal teardown. Must be rethrown or the coroutine machinery breaks.
            throw e
        } catch (e: Throwable) {
            // A genuine failure — a port already in use, or the interface going away.
            // Recorded and surfaced, never rethrown: an unhandled throw out of this
            // flow would take the whole process down over a socket the user can
            // simply restart.
            Log.e(TAG, "listener failed", e)
            _state.value = ListenerState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            // Both resources released on every path, including the exceptional one.
            socket?.close()
            lock?.takeIf { it.isHeld }?.release()
            if (_state.value is ListenerState.Listening) _state.value = ListenerState.Stopped
            Log.i(TAG, "listener stopped")
        }
    }.flowOn(dispatcher)

    companion object {
        /** Teardown latency after cancellation is bounded by this. */
        const val SO_TIMEOUT_MS = 500

        /** Anything longer than a packet is rejected, so this only needs to see it. */
        const val RECV_BUFFER_BYTES = 2048
    }
}

/** Route one received datagram into the registry. */
fun NodeRegistry.accept(received: Received, nowElapsedMs: Long) {
    when (val r = received.result) {
        is ParseResult.Ok -> onPacket(r.packet, received.senderIp, nowElapsedMs)
        is ParseResult.UnknownVersion -> onUnknownVersion(r.version)
        ParseResult.NotOurs -> onIgnored()
    }
}
