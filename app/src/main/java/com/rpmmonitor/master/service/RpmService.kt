package com.rpmmonitor.master.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.rpmmonitor.master.MainActivity
import com.rpmmonitor.master.R
import com.rpmmonitor.master.net.ListenerState
import com.rpmmonitor.master.net.RpmListener
import com.rpmmonitor.master.net.accept
import com.rpmmonitor.master.state.Freshness
import com.rpmmonitor.master.state.NodeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "RpmService"
private const val CHANNEL_ID = "rpm_monitor"
private const val NOTIFICATION_ID = 1

/**
 * Foreground service that owns the listener and the registry, so monitoring
 * survives screen-off and Doze.
 *
 * Its lifetime is an explicit start/stop from the UI — it is never started
 * implicitly, and stopping it is the only thing that closes the socket.
 *
 * The type is `connectedDevice`, not `dataSync`: Android 15 caps `dataSync` at six
 * hours per 24, which a monitoring session can plausibly reach, and the
 * `connectedDevice` permission precondition is already met by
 * `CHANGE_WIFI_MULTICAST_STATE`.
 */
class RpmService : Service() {

    /** Handed to the Activity so it reads exactly the state the service owns. */
    inner class LocalBinder : Binder() {
        val registry: NodeRegistry get() = this@RpmService.registry
        val listenerState: StateFlow<ListenerState> get() = listener.state
        val running: StateFlow<Boolean> get() = this@RpmService.running.asStateFlow()
        val startedAtElapsedMs: StateFlow<Long?> get() = this@RpmService.startedAt.asStateFlow()

        /** Which node the main readout and the notification follow. */
        val selectedNodeId: StateFlow<Int?> get() = this@RpmService.selectedNodeId.asStateFlow()
        fun selectNode(id: Int?) { this@RpmService.selectedNodeId.value = id }

        fun stop() = stopListening()
    }

    private val binder = LocalBinder()
    private val registry = NodeRegistry()
    private lateinit var listener: RpmListener

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Serialises start against stop. Without it a quick stop-then-start could have
     * the new socket bind while the old one is still winding down, and only one of
     * two sockets on the same UDP port receives the broadcasts.
     */
    private val sessionLock = Mutex()
    private var receiveJob: Job? = null
    private var freshnessJob: Job? = null
    private var notifyJob: Job? = null

    private val running = MutableStateFlow(false)
    private val startedAt = MutableStateFlow<Long?>(null)
    private val selectedNodeId = MutableStateFlow<Int?>(null)

    override fun onCreate() {
        super.onCreate()
        listener = RpmListener(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startListening()
        }
        // Not sticky: the user asked for a monitoring session, and silently
        // resurrecting a socket after the system killed us would be a surprise.
        return START_NOT_STICKY
    }

    private fun startListening() {
        // startForeground must happen promptly on this call, or the platform kills
        // us for taking too long — so it is done here, not inside the coroutine.
        startForegroundCompat(buildNotification("Starting…", null))
        if (running.value) return
        running.value = true
        startedAt.value = SystemClock.elapsedRealtime()

        scope.launch {
            sessionLock.withLock {
                // Wait out any previous session's teardown before binding again.
                joinSessionJobs()
                if (!running.value) return@withLock    // stopped while we waited
                registry.clear()

                receiveJob = scope.launch {
                    listener.packets().collect { received ->
                        registry.accept(received, SystemClock.elapsedRealtime())
                        // First node seen becomes the selection, so the readout is
                        // never blank while a node is broadcasting.
                        if (selectedNodeId.value == null) {
                            registry.nodes.value.keys.minOrNull()?.let { selectedNodeId.value = it }
                        }
                    }
                }

                // A node that stops sending generates no event, so freshness has to
                // be re-evaluated on a timer or a dead node stays "live" forever.
                freshnessJob = scope.launch {
                    while (true) {
                        delay(FRESHNESS_TICK_MS)
                        registry.evaluateFreshness(SystemClock.elapsedRealtime())
                    }
                }

                // Rate-limited on purpose. Posting at the packet rate is pure waste
                // and the shade throttles it anyway.
                notifyJob = scope.launch {
                    while (true) {
                        delay(NOTIFICATION_TICK_MS)
                        postNotification()
                    }
                }
            }
        }
    }

    private fun stopListening() {
        if (!running.value) return
        running.value = false
        startedAt.value = null
        stopForegroundCompat()
        // Teardown is off the main thread: cancelling the receive loop can take up to
        // one socket timeout, and the listener's finally block is what releases the
        // MulticastLock and closes the socket.
        scope.launch { sessionLock.withLock { joinSessionJobs() } }
        Log.i(TAG, "listening stopped")
    }

    private suspend fun joinSessionJobs() {
        receiveJob?.cancelAndJoin()
        freshnessJob?.cancelAndJoin()
        notifyJob?.cancelAndJoin()
        receiveJob = null
        freshnessJob = null
        notifyJob = null
    }

    /**
     * The user swiped the app out of recents.
     *
     * Surviving screen-off and Doze is the point of the foreground service, but
     * being dismissed from recents is an implicit stop — coming back to a session
     * already running, that the user never restarted, reads as a bug. Screen-off
     * and backgrounding are unaffected: neither removes the task.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopListening()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running.value = false
        startedAt.value = null
        // Cancelling the scope runs each flow's finally block on its own thread,
        // which closes the socket and releases the lock. No join is needed here —
        // the process is not required to wait for it.
        scope.cancel()
        super.onDestroy()
    }

    // ---- notification -----------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,   // silent: it updates constantly
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun postNotification() {
        val id = selectedNodeId.value
        val node = id?.let { registry.nodes.value[it] }
        val title = when {
            node == null -> "Waiting for a node…"
            else -> "${node.last.rpm} RPM"
        }
        val detail = node?.let {
            val fresh = when (it.freshness) {
                Freshness.LIVE -> "live"
                Freshness.STALE -> "STALE"
                Freshness.OFFLINE -> "OFFLINE"
            }
            "node ${it.nodeId} · peak ${it.peakRpm} · $fresh"
        }
        // If POST_NOTIFICATIONS was refused the post is dropped silently by the
        // platform. That is why the UI asks for it — otherwise the service looks dead.
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(title, detail))
    }

    private fun buildNotification(title: String, detail: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RpmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .apply { detail?.let { setContentText(it) } }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(stopIcon, "Stop", stop).build())
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() = stopForeground(STOP_FOREGROUND_REMOVE)

    companion object {
        const val ACTION_STOP = "com.rpmmonitor.master.STOP"

        /**
         * 250 ms: fast enough that a node going quiet is noticed well inside its own
         * threshold, cheap enough to be invisible. Freshness is a comparison against
         * a timestamp, so a missed tick only delays the transition.
         */
        const val FRESHNESS_TICK_MS = 250L
        const val NOTIFICATION_TICK_MS = 500L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RpmService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RpmService::class.java).setAction(ACTION_STOP))
        }
    }
}
