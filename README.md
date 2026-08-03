# RPMMaster

Android master for the RPM telemetry network. It listens for the UDP broadcasts
from a Raspberry Pi Pico 2 W engine node and displays the reading on a period
tachometer.

The node firmware is a separate repository,
[RPMMonitor](https://github.com/jusoen/RPMMonitor). The binding requirements for
this app are in [docs/android-master-app.md](docs/android-master-app.md),
sections 1–7 plus **Appendix A** — the appendix corrects four timing and platform
claims after a cross-check against the firmware and is part of the specification,
not commentary on it.

## The system

The phone is the access point. The node is a station that joins it, takes a DHCP
lease and broadcasts. Nothing is ever sent to the node — it has no receive path.

```
Android phone                            Pico 2 W node
+---------------------------+            +--------------------------+
| Wi-Fi hotspot (AP + DHCP) | <--join--- | STA, compile-time creds  |
| RPMMaster: UDP 4210       | <--bcast-- | rpm_packet_t, 20 bytes   |
+---------------------------+            +--------------------------+
```

The app implements no AP or DHCP functionality — Android's tethering stack owns
both. It is a UDP listener with a display.

## Setting it up

### Hotspot

Configure the **ordinary Android hotspot** in system settings. The app
deliberately does not use `startLocalOnlyHotspot`, whose randomly generated
credentials cannot be made to match the credentials compiled into the node.

| Setting | Required value | Why |
| --- | --- | --- |
| Network name | must match the node's `WIFI_SSID` | compiled into the firmware |
| Password | must match the node's `WIFI_PASS` | compiled into the firmware |
| Security | WPA2-PSK, or WPA2/WPA3 transition | the node authenticates with `CYW43_AUTH_WPA2_AES_PSK`, so a WPA3-only hotspot refuses it |
| Band | **2.4 GHz** | the CYW43439 has no 5 GHz radio, and phones commonly default to 5 |

The firmware's placeholder credentials are `rpm-master` / `changeme`. Treat the
pair as coordinated configuration: whatever the hotspot is set to must be flashed
into the node, or the other way round.

Everything else on the node side is already configured — `UDP_BROADCAST 1`,
`WIFI_AUTH`, port 4210 and the node id all live in the firmware's `config.h`, and
the DHCP lease is automatic.

### Running it

Press **Start**. The listener opens a socket and a foreground service keeps it
alive through screen-off. Press **Stop**, or dismiss the app from recents, to end
the session.

Expected timings, which matter when deciding whether something has actually gone
wrong:

| Case | Time to first packet |
| --- | --- |
| Cold start, or the hotspot appearing after a long node outage | typically ~15 s, **worst case ~45 s** |
| Hotspot cycled off and on while the node was already connected | a few seconds |

The worst case composes the firmware's 15 s per-attempt join timeout with a retry
backoff that doubles to a 30 s cap. Allow **60 s** before calling a cold start a
failure. The fast expectation for the off-and-on case is firm, though — the
firmware resets its backoff on a lost link, so a slow recovery there is a real
defect.

## Building

The `java` on `PATH` is Java 8 and the Android Gradle Plugin cannot use it. Point
`JAVA_HOME` at Android Studio's bundled JBR first.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

`compileSdk` and `targetSdk` 36, `minSdk` 26. AGP 8.13.2, Kotlin 2.0.21, Gradle
8.13, Compose BOM 2024.09.03.

There is no `local.properties` in the repository — Android Studio writes one, or
create it with `sdk.dir` pointing at your SDK.

## Structure

Four layers with one direction of dependency: `proto` ← `net` ← `state` ←
`ui`/`service`. The parser and the statistics hold no Android types, which is what
makes the only genuinely fiddly logic in the app testable on the JVM without an
emulator.

```
app/src/main/java/com/rpmmonitor/master/
  proto/RpmPacket.kt        wire format, parser, sealed parse result
  net/RpmListener.kt        socket, MulticastLock, receive loop -> Flow<Received>
  state/NodeState.kt        per-node snapshot and freshness thresholds
  state/NodeRegistry.kt     StateFlow<Map<Int, NodeState>>, loss/reboot/rate maths
  service/RpmService.kt     foreground service, notification, owns listener + registry
  ui/RetroTachometer.kt     Canvas-drawn dial, no image assets
  ui/MainReadout.kt         dial plus the digital value, peak and freshness
  ui/NodeList.kt            shown only once a second node_id has been seen
  ui/Diagnostics.kt         counters, measured rate, hotspot setup
  MainActivity.kt           binds the service, hosts the three screens
app/src/test/java/...       JVM unit tests for proto and state
tools/sim_node.py           node simulator, no hardware needed
```

### Wire protocol

One packet type, 20 bytes, packed, **little-endian** — the sender is a Cortex-M
and performs no byte-order conversion, so `ByteOrder.LITTLE_ENDIAN` is set
explicitly. Magic `0x314D5052` ("RPM1"), version 1, port 4210. Every field is
unsigned.

| Offset | Field | Notes |
| --- | --- | --- |
| 0–3 | `magic` | `0x314D5052` |
| 4 | `version` | unknown versions are counted and surfaced, never dropped silently |
| 5 | `node_id` | all per-node state is keyed by this |
| 6–7 | `seq` | u16, wraps, gaps mean link loss |
| 8–11 | `uptime_ms` | sender uptime, not a synchronised clock |
| 12–15 | `rpm` | `0` means stalled or no signal, which is meaningful rather than an error |
| 16–19 | `rpm_peak` | monotonic until the node reboots |

The layout is shared with the firmware's `rpm_proto.h`. Any change bumps
`RPM_PROTO_VERSION` and changes both ends together.

### Things worth knowing about the state layer

- **Link loss is wrap-aware.** `delta = (seq - lastSeq) and 0xFFFF`, so 65535 → 0
  is a delta of one rather than a 65000-packet catastrophe.
- **It really is link loss.** The firmware increments `seq` only after a
  successful hand-off to its network stack, so a node-side drop advances nothing
  and is invisible here. This figure measures the air link and this receiver, never
  the node. Getting the label wrong sends someone debugging the wrong end.
- **Freshness is derived, not hardcoded.** The firmware's real cadence is 100 ms,
  250 ms or 750 ms depending on its build switches, so nothing may assume 100 ms.
  The registry keeps a rolling median of the last ten inter-arrival intervals,
  normalised by the sequence delta so one lost packet does not read as a halved
  rate, and sets stale at 4× and offline at 20× that, floored at 1 s / 5 s and
  capped at 5 s / 20 s. The measured rate is on the diagnostics screen — it is the
  single most useful number for telling "node is slow" from "link is dropping".
- **Freshness is re-evaluated on a timer**, every 250 ms, not only on arrival. A
  node that stops sending generates no event to trigger a recompute.
- **A reboot is a backwards `seq` jump together with an `uptime_ms` drop.** Both
  conditions are required, so the u16 wrap is not mistaken for one. Per-node
  statistics reset, the reboot count does not.
- **An id collision is flagged, not merged.** Two sender addresses under one
  `node_id` suppresses the loss figure rather than interleaving two independent
  sequence streams into a number that would be a lie.

## Testing

### Without hardware

`tools/sim_node.py` sends the same packets the node does. Run it on a machine
joined to the phone's hotspot.

```sh
python tools/sim_node.py                          # node 1, 10 Hz
python tools/sim_node.py --node-id 2 --rate 0.25  # a second node, production cadence
python tools/sim_node.py --rate 0.75              # the loopback-harness cadence
python tools/sim_node.py --fuzz --loss 0.03       # malformed datagrams and link loss
python tools/sim_node.py --seq-start 65500        # exercise the u16 wrap within a minute
```

`--help` lists the rest. The profile injects periodic stalls and an occasional
excursion to 9000 so the STALL/0 and out-of-range paths are both exercised.

Worth checking: the readout tracks the sine, a stall shows as STALL/0 with the
needle at the zero stop, 9000 pins the needle at the end stop rather than wrapping
it, killing the script walks live → stale → offline while the needle holds its last
angle, restarting registers a reboot, two instances with different ids populate the
node list, and `--rate 0.25` and `0.75` do not make freshness flap.

Two cases the simulator cannot cover over an emulator's NAT, because every instance
appears to come from one address: real limited broadcast, and the id collision
check. Both need a real network.

### Unit tests

```powershell
.\gradlew.bat testDebugUnitTest
```

44 JVM tests over the parser and the registry — loss maths, the wrap, reboot
detection, rate measurement at all three firmware cadences, freshness boundaries,
collisions, and fuzzed input. No emulator required.

## Non-goals

- **Never transmit to the node.** It has no receive path and the protocol is one-way
  by design. Nothing here opens a send path to it.
- No AP or DHCP implementation — the OS hotspot owns both.
- No history, graphing, persistence or export. `NodeState` is shaped so a ring
  buffer of recent samples could be added without reshaping the model.
- No protocol change on the app side alone.
