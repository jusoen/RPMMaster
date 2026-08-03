# Brief: Android master app for the RPM telemetry network

This document is a self-contained work order for an agent building the **master**
side of a two-party telemetry system. Everything you need is in here — the slave
firmware repo is not required reading, though the authoritative wire-format header
(`rpm_proto.h`) and slave network config (`config.h`) live at
`p:\Projects\Pico\RPMMonitor` if you need to cross-check.

## 1. System overview

The **slave** is existing, working firmware on a Raspberry Pi Pico 2 W. It measures
engine RPM from a hall-effect sensor and, as a Wi-Fi **station**, joins an access
point with compile-time credentials, takes a DHCP lease, and sends a 20-byte binary
packet by UDP **limited broadcast** (`255.255.255.255`) to **port 4210** every
**100 ms** while the link is up. It is strictly TX-only — it never listens, and
nothing should ever be sent to it.

The **master** — what you are building — is an Android app. The phone provides the
access point and the app consumes the broadcasts:

```
Android phone                            Pico 2 W slave node(s)
+---------------------------+            +--------------------------+
| Wi-Fi hotspot (AP + DHCP) | <--join--- | STA, compile-time creds  |
| App: UDP socket on 4210   | <--bcast-- | rpm_packet_t every 100ms |
+---------------------------+            +--------------------------+
```

Using the phone's built-in hotspot means the app does **not** implement AP or DHCP
functionality — Android's tethering stack does both. The app is purely a UDP
listener with a display.

## 2. Hotspot strategy (decide this first)

Recommended: **the user configures the ordinary Android hotspot manually** in
system settings, and the app only documents what to set. Do not use
`startLocalOnlyHotspot` — on modern Android it generates random credentials the
app cannot choose, and the slave's credentials are compiled in, so they must be
knowable and stable.

The hotspot settings the user must apply (surface these in an in-app setup screen
or first-run instructions):

| Setting | Required value | Why |
| --- | --- | --- |
| Network name (SSID) | must match the slave's `WIFI_SSID` | compiled into the firmware |
| Password | must match the slave's `WIFI_PASS` | compiled into the firmware |
| Security | WPA2-PSK (or WPA2/WPA3 transition) | slave authenticates with `CYW43_AUTH_WPA2_AES_PSK` — a WPA3-only hotspot will refuse it |
| Band | **2.4 GHz** | the Pico 2 W radio (CYW43439) is 2.4 GHz only — many phones default to 5 GHz, and the node will never appear |

The current placeholder credentials in the slave are `rpm-master` / `changeme`.
Treat the pair as coordinated configuration: whatever the user sets in the hotspot
must be flashed into the slave, or vice versa.

The app should detect and display whether the hotspot is active if practical
(`WifiManager` gives limited visibility to non-system apps), but a simpler and
robust signal is: "receiving packets" vs "not receiving packets". Do not block
any functionality on hotspot-state detection.

## 3. Wire protocol

One packet type. 20 bytes, packed, **little-endian is the wire byte order** (the
sender is a Cortex-M and performs no network-byte-order conversion — remember that
Java's `ByteBuffer` defaults to big-endian, so set `ByteOrder.LITTLE_ENDIAN`
explicitly).

Authoritative C definition (from `rpm_proto.h`):

```c
#define RPM_PROTO_MAGIC    0x314D5052u   // "RPM1" - 'R' is the lowest byte on the wire
#define RPM_PROTO_VERSION  1u

typedef struct __attribute__((packed)) {
    uint32_t magic;       // RPM_PROTO_MAGIC
    uint8_t  version;     // RPM_PROTO_VERSION
    uint8_t  node_id;     // which engine node sent this
    uint16_t seq;         // wraps naturally, resets on reboot
    uint32_t uptime_ms;   // sender uptime, wraps every ~49.7 days
    uint32_t rpm;         // current measurement (0 = stalled / no signal)
    uint32_t rpm_peak;    // peak measurement since boot
} rpm_packet_t;           // exactly 20 bytes
```

Byte offsets: magic 0–3, version 4, node_id 5, seq 6–7, uptime_ms 8–11,
rpm 12–15, rpm_peak 16–19.

Validation — silently drop (with a debug-level log) any datagram that fails:
1. payload length is exactly 20
2. `magic == 0x314D5052`
3. `version == 1` — count and surface unknown-version packets rather than crashing,
   the field exists so the protocol can evolve

Field semantics:
- **rpm**: the live reading. `0` is meaningful — engine stalled or no sensor
  signal — not an error. Plausible non-zero range is 300–7500 (the sensor design
  window). Values outside it may be displayed but flag them visually.
- **rpm_peak**: highest reading since the node booted. Monotonic until reboot.
- **seq**: u16, increments per packet actually sent, wraps 65535 → 0 (roughly every
  109 minutes at 10 Hz — handle the wrap in loss math, a gap of ~65000 is a wrap,
  not catastrophic loss). A backwards jump plus an `uptime_ms` drop means the node
  rebooted — reset per-node statistics when you see that.
- **uptime_ms**: sender-side milliseconds since boot. Use it for reboot detection
  and display, never as a synchronized clock.
- **node_id**: distinguishes slaves if more than one broadcasts on the same
  network. Key all per-node state by this. Currently one node exists (`node_id`
  = 1) but do not hardcode that — design the model as a map of nodes.

Timing model:
- Nominal cadence is one packet per 100 ms per node.
- The node drops packets freely (send-buffer pressure, Wi-Fi retries) — gaps are
  normal, the payload is idempotent, and the next packet fully supersedes the last.
- Recommended freshness states per node: **live** (packet within 500 ms),
  **stale** (0.5–3 s — show the last value greyed/marked), **offline** (over 3 s —
  the node left, lost the link, or the hotspot dropped).

## 4. Android implementation requirements

Stack: Kotlin, Jetpack Compose, coroutines. Minimum SDK 26 or higher is fine —
pick what makes the networking simplest, nothing here needs new APIs.

### Receiving

- A `DatagramSocket` bound to `0.0.0.0:4210` (`reuseAddress = true`), blocking
  `receive()` on a dedicated IO dispatcher, parse, push into a `StateFlow` of
  per-node state. 20-byte packets at 10 Hz is negligible load — no batching
  needed.
- Acquire a `WifiManager.MulticastLock` while receiving. When the phone is the
  hotspot, broadcast delivery generally works without it, but some devices filter
  non-unicast frames to save power and the lock is the documented way to opt out.
  Release it when listening stops.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`.
  No location permission is needed for plain socket listening — avoid any API
  that drags it in.
- Run reception in a **foreground service** (type `connectedDevice` or `dataSync`)
  so monitoring survives screen-off and Doze while the user is actually using it.
  Tie the service lifetime to an explicit start/stop in the UI, show the current
  RPM in the service notification.
- Record the sender address from each datagram per node — useful diagnostics
  (shows the DHCP lease worked) and distinguishes two misconfigured nodes sharing
  a `node_id`.

### Parsing sketch

```kotlin
fun parse(buf: ByteArray, len: Int): RpmPacket? {
    if (len != 20) return null
    val b = ByteBuffer.wrap(buf, 0, 20).order(ByteOrder.LITTLE_ENDIAN)
    if (b.int != 0x314D5052) return null
    val version = b.get().toInt() and 0xFF
    if (version != 1) { unknownVersionCount++; return null }
    return RpmPacket(
        nodeId   = b.get().toInt() and 0xFF,
        seq      = b.short.toInt() and 0xFFFF,
        uptimeMs = b.int.toLong() and 0xFFFFFFFFL,
        rpm      = b.int.toLong() and 0xFFFFFFFFL,
        rpmPeak  = b.int.toLong() and 0xFFFFFFFFL,
    )
}
```

(The `and 0xFF...` masks matter — every wire field is unsigned and Kotlin/Java
integer types are signed.)

### Per-node statistics to maintain

- last packet wall-clock time → freshness state
- packets received, packets lost (from seq deltas, wrap-aware), loss percentage
  over a sliding window
- reboot count (seq/uptime reset events)
- sender IP, node uptime

## 5. UI requirements

Keep it instrument-like and readable at a glance — this gets used next to a
running engine.

- **Main readout**: the current RPM of the selected node, very large, with the
  peak beneath it. Show `0` distinctly as "STALL/0" rather than as a live-looking
  number. A simple analog-style gauge or bar to 8000 RPM is welcome but the number
  is the requirement.
- **Freshness**: unmistakable live/stale/offline indication (color + label, not
  color alone). When stale or offline, keep the last values visible but visibly
  demoted.
- **Node list**: if more than one node_id has been seen, a list to pick the main
  readout from, each row showing id, rpm, freshness.
- **Diagnostics screen**: packet counters, loss %, reboots, sender IP, node
  uptime, listener state, and the hotspot setup instructions from section 2.
- Dark theme default. Keep the screen on while the main readout is visible.

No history/graphing, persistence, or export is required in the first version —
structure the state layer so a ring buffer of recent samples could be added, but
do not build it now.

## 6. Test plan

**Without hardware** — a desktop simulator on any machine on the same network as
the listener (run it on a laptop joined to the phone's hotspot):

```python
#!/usr/bin/env python3
# Simulates one RPM slave node. Usage: python sim_node.py [node_id]
import socket, struct, sys, time, math

node_id = int(sys.argv[1]) if len(sys.argv) > 1 else 1
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
seq, peak, t0 = 0, 0, time.monotonic()
while True:
    t = time.monotonic() - t0
    rpm = 0 if t % 30 < 3 else int(3000 + 2500 * math.sin(t / 5))  # stall spells
    peak = max(peak, rpm)
    pkt = struct.pack('<IBBHIII', 0x314D5052, 1, node_id, seq & 0xFFFF,
                      int(t * 1000) & 0xFFFFFFFF, rpm, peak)
    s.sendto(pkt, ('255.255.255.255', 4210))
    seq += 1
    time.sleep(0.1)
```

Verify: live readout tracks the sine, the periodic stall shows as `0`/STALL,
killing the script walks the UI through live → stale → offline, restarting it
registers a reboot (seq reset), and running two instances with different node ids
populates the node list. Also fuzz the listener with short, garbage, and
wrong-version datagrams — all must be ignored without a crash.

**With hardware**: flash the Pico slave with `WIFI_SSID`/`WIFI_PASS` matching the
hotspot (2.4 GHz, WPA2 — see section 2), power it, and expect packets within
about 15 seconds of hotspot availability (the node retries joining with backoff
up to 30 s, so worst case after a long outage is one backoff period). Turn the
hotspot off and on — the node rejoins by itself, and the app must recover with no
user action.

## 7. Non-goals and constraints

- **Never transmit to the node.** It has no receive path — the protocol is
  one-way by design.
- No AP/DHCP implementation in the app — the OS hotspot owns that.
- No protocol changes on the app side alone. The wire struct is shared with the
  firmware (`rpm_proto.h`) — any evolution bumps `RPM_PROTO_VERSION` and changes
  both ends together. Build the parser so version 1 keeps working when a version
  2 appears.
- Port 4210 and the 20-byte layout are fixed contract values unless both sides
  change in lockstep.

---

## Appendix A — amendment after firmware cross-check (2026-08-02)

Sections 1–7 above were written ahead of a line-by-line check against the slave
firmware. That check has now been done against `rpm_proto.h`, `config.h`,
`net_node.cpp` and `RPMMonitor.cpp`. This appendix records what it found.

**How to read this appendix.** The wire format is confirmed correct — struct
layout, byte offsets, magic value, little-endian order and the parsing sketch all
match the firmware exactly, so nothing in section 3's protocol definition changes.
What follows is about *timing and platform*, and it splits two ways:

- **Firmware observations (A.1–A.3)** are places where the slave's current behaviour
  falls short of the design intent stated above. **The intent in sections 1–6 stands
  and is what the app should be built to.** Do not redesign the app around the
  present firmware behaviour and do not treat these numbers as the new contract —
  build to the intent, but make the app *tolerant* of the current reality so it works
  today and needs no change when the firmware is fixed. Each item says what
  tolerance means concretely.
- **App requirements (A.4–A.5)** are genuine gaps in this document. These are
  additive requirements — treat them as part of section 4 and 5.

### A.1 Broadcast cadence is slower than the nominal 100 ms

**Intent (unchanged): one packet per 100 ms per node.**

Current firmware behaviour: `net_poll()` is called once per main-loop iteration and
the loop is paced by `sleep_ms(REPORT_PERIOD_MS)`, so the transmit timer is only
sampled at the report rate. `NET_TX_PERIOD_MS` (100 ms) is therefore an upper bound
the node cannot beat, and the observed cadence is whichever is slower:

| Firmware build | Report period | Observed cadence |
| --- | --- | --- |
| `TEST_REAL_HW 1` (current bring-up build) | 50 ms | ~100 ms — as intended |
| Production (`TEST_REAL_HW 0`) | 250 ms | ~250 ms |
| `TEST_HARNESS 1` (loopback sweep) | 750 ms per step | ~750 ms, and `rpm_peak` is sent as 0 |

A second, smaller effect: the node re-bases its next-send time on the poll instant
rather than on the previous due time, so the cadence always quantises up to the poll
period instead of averaging back to 100 ms.

**What the app should do.** Nothing that hardcodes 100 ms.

- Do not derive freshness from a compiled-in 100 ms assumption. Measure the observed
  inter-packet interval per node (a short rolling median is enough) and set the
  live/stale boundary at a multiple of it — roughly 4x observed for **stale** and
  20x for **offline**, with a floor so a fast node still gets sensible absolute
  numbers. The section 3 values (500 ms / 3 s) are then the *fastest* case rather
  than fixed constants.
- If an adaptive scheme is more machinery than wanted for v1, use fixed thresholds
  of **1 s** (live) and **5 s** (offline) instead. That covers every build above
  without flapping, at the cost of a slightly slower offline indication.
- Show the measured packet rate on the diagnostics screen (section 5). It is the
  single most useful number for telling "node is slow" from "link is dropping".
- The section 6 simulator hardcodes `time.sleep(0.1)`. Make the rate a command-line
  argument and test at 0.1 s, 0.25 s and 0.75 s.

Note also that section 3's "seq wraps roughly every 109 minutes" is the 10 Hz
figure. At 250 ms it is about 4.5 hours. The wrap-aware loss maths is unaffected —
only the interval changes.

### A.2 `seq` gaps indicate link loss only, not node-side drops

Section 3 says the node "drops packets freely (send-buffer pressure, Wi-Fi
retries)" and implies those drops show up as sequence gaps. They do not. The
firmware increments `seq` only after a successful hand-off to the stack, and an
allocation failure skips the increment entirely, so a node-side drop advances
nothing and is invisible to the master.

**Intent is unaffected** — this is arguably the better behaviour, and no firmware
change is proposed. But it changes what the app's loss counter *means*:

- A sequence gap means the datagram was lost **on the air or at the receiver**.
- Node-side drops are undetectable by design.
- Label the diagnostics figure accordingly — "link loss" or "packets lost in
  transit", not "node dropped". Getting this wrong sends someone debugging the
  wrong end of the system.

Everything else in the `seq` bullet holds: it is a u16, it wraps, a backwards jump
together with an `uptime_ms` drop still means a reboot, and per-node statistics
should still reset on that.

### A.3 Cold-start recovery can take ~45 s, not ~15 s

Section 6 tells the tester to "expect packets within about 15 seconds of hotspot
availability" and calls one backoff period the worst case. Two firmware timers
compose here: a 15 s per-attempt join timeout and a retry backoff that doubles to a
30 s cap. A node that has been retrying into an absent AP can be part-way through a
30 s backoff at the moment the hotspot appears, then take up to 15 s more to
associate and get a lease.

Two distinct cases, and the app should be tested against both:

| Case | Expected time to first packet |
| --- | --- |
| Cold start, or hotspot appearing after a long node-side outage | typically ~15 s, **worst case ~45 s** |
| Hotspot cycled off and on while the node was already connected | a few seconds — the firmware resets its backoff on a lost link and retries at once |

**What the app should do.** No behaviour change — the app already must never block
on node presence. This matters for the *test plan*: set the tester's patience to
60 s for a cold start so a normal 40 s join is not written up as a failure, and keep
the fast expectation for the hotspot off/on test, because a slow recovery there
would be a real defect.

### A.4 Missing Android platform requirements (additive to section 4)

Section 4 mandates a foreground service but lists only `INTERNET`,
`ACCESS_NETWORK_STATE` and `CHANGE_WIFI_MULTICAST_STATE`. That set will not build a
working service on a current target SDK. Add:

- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` in the manifest,
  plus `android:foregroundServiceType="connectedDevice"` on the `<service>` element.
- `POST_NOTIFICATIONS` — a runtime permission from API 33. Without it the service
  notification is suppressed silently, which reads as "the service died".
- **Use `connectedDevice`, not `dataSync`.** Section 4 offered either. `dataSync` is
  capped at six hours per 24 on Android 15, which a monitoring session can plausibly
  reach. `connectedDevice` has no such cap and its permission precondition is
  already satisfied by `CHANGE_WIFI_MULTICAST_STATE`, which is on the list.
- Section 4's "nothing here needs new APIs" is true of the networking and false of
  the service. Foreground service types and the notification permission are
  target-SDK obligations regardless of how low `minSdk` goes.

Two lifecycle points that follow from the same section:

- **Cancellation.** A blocking `DatagramSocket.receive()` does not observe coroutine
  cancellation — cancelling the scope leaves the thread parked in the syscall. Set a
  socket timeout and loop with an explicit active check, or wrap the call so that
  teardown closes the socket to break it. Whichever way, release the
  `MulticastLock` on the same path, including the exceptional one.
- **Keeping the screen on** (section 5) is the Compose `KeepScreenOn` flag on the
  main readout, scoped to that screen so it is dropped when the user navigates away.

### A.5 Smaller corrections

- The cross-check path in the header, `p:\Projects\Pico\RPMMonitor`, does not resolve
  on the machine this review was run on — the repository is at
  `c:\Users\jmitchell\Source\RPPico\RPMMonitor`. Both may be valid via a mapped
  drive, but do not rely on the `p:` form.
- The section 6 simulator's `rpm` never leaves roughly 500–5500, so the out-of-range
  visual flag required by section 3 is never exercised. Inject an occasional
  out-of-band value (say 9000) so that path is covered.
- Section 6's verification list has no case for two nodes sharing one `node_id`, even
  though section 4 asks the app to record the sender address specifically to detect
  it. Add one: run two simulator instances with the same id and confirm the app
  surfaces the collision rather than interleaving them into one node's statistics.
- Add a short acceptance-criteria checklist at the end of the document so "done" is
  something that can be checked rather than judged.

### A.6 Summary

Nothing here changes the wire protocol, the port, the packet layout, the one-way
constraint, or the UI requirements. Sections 1–7 remain the specification. Build the
app to the intent stated there — 10 Hz nominal, a node that may appear at any time
and vanish without warning — and add the tolerances in A.1, the labelling in A.2,
the test patience in A.3 and the platform requirements in A.4 and A.5.
