package com.rpmmonitor.master

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rpmmonitor.master.net.ListenerState
import com.rpmmonitor.master.service.RpmService
import com.rpmmonitor.master.state.ListenerStats
import com.rpmmonitor.master.state.NodeState
import com.rpmmonitor.master.ui.Diagnostics
import com.rpmmonitor.master.ui.MainReadout
import com.rpmmonitor.master.ui.NodeList
import com.rpmmonitor.master.ui.theme.InstrumentAmber
import com.rpmmonitor.master.ui.theme.InstrumentRed
import com.rpmmonitor.master.ui.theme.RPMMasterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hosts the three screens and owns the binding to [RpmService].
 *
 * The Activity never touches a socket. Everything it displays is read from the
 * service's flows, so rotating the phone or backgrounding the app changes nothing
 * about what is being received.
 */
class MainActivity : ComponentActivity() {

    private val binder = MutableStateFlow<RpmService.LocalBinder?>(null)
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder.value = service as? RpmService.LocalBinder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder.value = null
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* advisory only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // From API 33 the service notification is suppressed silently without this,
        // which reads as "the service died". Asked for once, never blocking.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            RPMMasterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(binder)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // BIND_AUTO_CREATE, despite binding not being what starts monitoring.
        //
        // Without it, bindService fails outright when the service is not already
        // running and the connection is discarded — so pressing Start would launch
        // the service while the UI sat there with no binder to observe, still
        // showing "Start". Creating the service on bind is harmless: it opens no
        // socket and posts no notification until onStartCommand is given one. The
        // listening lifecycle is the explicit start/stop, not the service's
        // existence.
        bound = bindService(Intent(this, RpmService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Symmetric with the bind above. Unbinding when the bind failed throws, so
        // the flag is the guard rather than a swallowed exception.
        if (bound) {
            unbindService(connection)
            bound = false
        }
        binder.value = null
    }
}

private enum class Screen(val title: String) { READOUT("Readout"), NODES("Nodes"), DIAGNOSTICS("Diagnostics") }

@Composable
private fun AppScreen(binderFlow: StateFlow<RpmService.LocalBinder?>) {
    val context = LocalContext.current
    val binder by binderFlow.collectAsStateWithLifecycle()

    // Stand-ins for the window before the service binds. Remembered unconditionally
    // — a remember inside an elvis is only reached on one branch, so the slot table
    // would shift the moment the binder arrives.
    val noRunning = remember { MutableStateFlow(false) }
    val noNodes = remember { MutableStateFlow(emptyMap<Int, NodeState>()) }
    val noStats = remember { MutableStateFlow(ListenerStats()) }
    val noListener = remember { MutableStateFlow<ListenerState>(ListenerState.Stopped) }
    val noSelection = remember { MutableStateFlow<Int?>(null) }
    val noStartedAt = remember { MutableStateFlow<Long?>(null) }

    val running by (binder?.running ?: noRunning).collectAsStateWithLifecycle()
    val nodes: Map<Int, NodeState> by (binder?.registry?.nodes ?: noNodes).collectAsStateWithLifecycle()
    val stats: ListenerStats by (binder?.registry?.stats ?: noStats).collectAsStateWithLifecycle()
    val listenerState: ListenerState by (binder?.listenerState ?: noListener).collectAsStateWithLifecycle()
    val selectedId: Int? by (binder?.selectedNodeId ?: noSelection).collectAsStateWithLifecycle()
    val startedAt: Long? by (binder?.startedAtElapsedMs ?: noStartedAt).collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(Screen.READOUT) }

    // The node list is offered only once a second node_id has been seen.
    val multiNode = nodes.size > 1
    LaunchedEffect(multiNode) {
        if (!multiNode && screen == Screen.NODES) screen = Screen.READOUT
    }

    val selected = selectedId?.let { nodes[it] } ?: nodes.values.minByOrNull { it.nodeId }

    // targetSdk 36 means the window is edge-to-edge whether we ask for it or not, so
    // the content has to keep itself clear of the status and navigation bars.
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ControlBar(
            running = running,
            onStart = { RpmService.start(context) },
            onStop = { RpmService.stop(context) },
            listenerState = listenerState,
        )

        val visible = Screen.entries.filter { it != Screen.NODES || multiNode }
        TabRow(selectedTabIndex = visible.indexOf(screen).coerceAtLeast(0)) {
            visible.forEach { s ->
                Tab(
                    selected = s == screen,
                    onClick = { screen = s },
                    text = { Text(s.title) },
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.READOUT -> {
                    // Scoped to this screen only, so it is dropped as soon as the
                    // user navigates away.
                    KeepScreenOn()
                    MainReadout(selected, listening = running)
                }
                Screen.NODES -> NodeList(
                    nodes = nodes.values.sortedBy { it.nodeId },
                    selectedId = selected?.nodeId,
                    onSelect = { binder?.selectNode(it) },
                )
                Screen.DIAGNOSTICS -> Diagnostics(
                    node = selected,
                    stats = stats,
                    listenerState = listenerState,
                    sessionUptimeMs = startedAt?.let { SystemClock.elapsedRealtime() - it },
                )
            }
        }
    }
}

@Composable
private fun ControlBar(
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    listenerState: ListenerState,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                "RPM MASTER",
                color = InstrumentAmber,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                fontSize = 15.sp,
            )
            val sub = when (listenerState) {
                ListenerState.Listening -> "UDP 4210"
                ListenerState.Stopped -> "idle"
                is ListenerState.Failed -> "socket failed"
            }
            Text(
                sub,
                color = if (listenerState is ListenerState.Failed) InstrumentRed
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Button(
            onClick = if (running) onStop else onStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) InstrumentRed else InstrumentAmber,
                contentColor = androidx.compose.ui.graphics.Color.Black,
            ),
        ) {
            Text(if (running) "Stop" else "Start", fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Holds the screen on for as long as this composable is in the tree, and releases
 * the flag when it leaves.
 */
@Composable
private fun KeepScreenOn() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
