# RPM protocol v2 — per-interval statistics

A work order for the node firmware ([RPMMonitor](https://github.com/jusoen/RPMMonitor)).
Everything here is the node's side of the contract. The master's side is implemented
in this repository and is described at the end so the two can be checked against each
other.

## Why

The master can already measure how steadily the engine runs, but only the slow kind of
instability: wander, surge and hunting. It cannot see combustion-level variation,
misfires or a weak cylinder, and no amount of arithmetic at the receiving end will
recover them. One already-averaged `rpm` arrives per packet, at best every 100 ms, so
nothing above about 5 Hz survives, and whatever averaging the firmware applied is gone
before the datagram leaves the node.

**Sending packets faster is the wrong fix.** It costs airtime, battery and packet loss,
and it still cannot resolve per-firing behaviour without running far above the firing
frequency. The node already sees every hall pulse. It should reduce them where they
are, and send the reduction.

If each packet carries the **count, mean and standard deviation of the revolutions
measured within that interval**, the master can reconstruct the exact statistics over
any window from any subset of packets, and the two kinds of instability separate:

- **within-interval σ** is combustion roughness, cycle to cycle
- **variation between the interval means** is wander, surge and hunting

They compose exactly rather than approximately:

```
total variance = SUM( n[i] * (sd[i]^2 + mean[i]^2) ) / SUM( n[i] )
                 - ( SUM( n[i] * mean[i] ) / SUM( n[i] ) )^2
```

which is why the fields have to be exactly what is specified below and not something
close to it. Each interval's figures are self-contained, so a lost packet costs that
interval and corrupts nothing.

## Wire format

Magic and byte order are unchanged. The magic stays `"RPM1"` because it identifies the
protocol family, not the version, and the `version` field is what distinguishes them.
Little-endian, packed, no padding, as now.

```c
#define RPM_PROTO_MAGIC    0x314D5052u   /* "RPM1", 'R' lowest on the wire */
#define RPM_PROTO_VERSION  2u

typedef struct __attribute__((packed)) {
    uint32_t magic;        /* RPM_PROTO_MAGIC                       */
    uint8_t  version;      /* 2                                     */
    uint8_t  node_id;
    uint16_t seq;
    uint32_t uptime_ms;
    uint32_t rpm;          /* mean over this interval, see below    */
    uint32_t rpm_peak;     /* highest since boot, unchanged         */
    uint16_t rev_count;    /* revolutions measured in this interval */
    uint16_t rpm_sd_x10;   /* sd of those revolutions, 0.1 rpm      */
    uint16_t rpm_min;      /* lowest of those revolutions           */
    uint16_t rpm_max;      /* highest                               */
} rpm_packet_v2_t;         /* exactly 28 bytes */

_Static_assert(sizeof(rpm_packet_v2_t) == 28, "v2 packet must be 28 bytes");
```

Byte offsets: magic 0–3, version 4, node_id 5, seq 6–7, uptime_ms 8–11, rpm 12–15,
rpm_peak 16–19, rev_count 20–21, rpm_sd_x10 22–23, rpm_min 24–25, rpm_max 26–27.

The first 20 bytes are byte-for-byte a v1 packet apart from the version number. That
is deliberate, so a v2 capture can be read by v1 tooling with one field overridden.

## Field semantics

Define **per-revolution rpm** as the instantaneous figure from a single revolution's
period:

```
rpm_rev = 60000000 / period_us
```

All four new fields describe the set of per-revolution rpm values whose revolutions
**completed within this reporting interval**. A revolution that straddles the boundary
belongs to the interval it completed in, counted once and only once.

| Field | Meaning |
| --- | --- |
| `rpm` | Arithmetic mean of that set, rounded to nearest. **This is a change from v1**, where it was the live reading. |
| `rev_count` | How many values are in the set. May legitimately be 0. |
| `rpm_sd_x10` | Sample standard deviation of the set (divisor `n - 1`), in units of 0.1 rpm |
| `rpm_min` | Smallest value in the set |
| `rpm_max` | Largest value in the set |

`rpm_peak` keeps its v1 meaning exactly: the highest reading since boot, monotonic
until reboot. It is not reset by anything the master does, and the master does not ask
it to be. The master keeps its own resettable mark separately.

### Required behaviour at the edges

These are the cases that decide whether the master can trust the numbers, so they are
requirements and not suggestions.

- **`rev_count == 0`** (the engine is stopped, or turning too slowly for a revolution
  to complete inside the interval). Send `rpm = 0`, `rpm_sd_x10 = 0`, `rpm_min = 0`,
  `rpm_max = 0`. A zero `rpm` continues to mean "stalled or no signal" exactly as in
  v1.
- **`rev_count == 1`.** A standard deviation is undefined for one sample. Send
  `rpm_sd_x10 = 0`, and set `rpm_min` and `rpm_max` to that one value. The master
  keys off `rev_count`, not off a zero σ, so it will not mistake this for a perfectly
  steady interval.
- This is not a rare case. At 300 rpm a revolution takes 200 ms, so at the 100 ms
  cadence most intervals will contain 0 or 1 revolutions. The master is written to
  expect it.
- **Saturation, never wrapping.** Clamp every u16 field at 65535 rather than letting
  it wrap. A wrapped value is indistinguishable from a real one and would be believed.
  `rpm_sd_x10` therefore tops out at 6553.5 rpm, which is far beyond any real σ.
- **`rpm_min <= rpm <= rpm_max`** must hold whenever `rev_count >= 1`. The master
  treats a violation as a corrupt packet.

### Accumulation

Reset the accumulators at the start of every reporting interval. The statistics
describe that interval alone and never a rolling window: rolling is the master's job,
and an interval that overlapped its neighbours would break the composition identity
above.

**Do not accumulate a sum of squares in 32 bits.** At 9000 rpm and the 750 ms cadence
an interval holds about 112 revolutions, and `112 * 9000^2` is 9.1e9, which overflows
`uint32_t` at 4.29e9. Either accumulate in `uint64_t`, or use Welford's method, which
is what is recommended here:

```c
/* per revolution */
n++;
delta  = rpm_rev - mean;
mean  += delta / n;
m2    += delta * (rpm_rev - mean);
/* at the end of the interval, for n >= 2 */
sd = sqrtf(m2 / (n - 1));
```

Welford needs no wide accumulator and does not lose precision to cancellation when the
mean is large and the spread is small, which is exactly this signal. The RP2350's
Cortex-M33 has a single-precision FPU, so `float` here is cheap. If the build is ever
moved to a part without one, the `uint64_t` sum-of-squares form is the fallback and
the overflow bound above is the one to respect.

Update the accumulators in the pulse handler or from a queue it feeds. Whichever it
is, the read-and-reset at the interval boundary must be atomic with respect to that
handler, or one revolution will be counted in both intervals or in neither.

## Compatibility

- A v2 node sends only v2 packets. There is no mixed mode and no negotiation, because
  the link is one way and the node cannot know what is listening.
- A v1-only master receiving v2 counts it as an unknown version and surfaces the
  count rather than crashing or silently dropping it. That behaviour already exists
  and is tested.
- Please keep a compile-time switch (`RPM_PROTO_EMIT_V1` or similar) that reverts to
  the 20-byte v1 packet, so a node can still be pointed at older tooling.
- A **20-byte** payload carrying version 2 stays invalid. The master rejects it, and
  there is a test asserting so. Length and version must agree.

## Acceptance criteria

Testable without an engine, with a signal generator on the hall input.

1. **Fixed frequency, say 3000 rpm.** `rev_count` matches the expected count for the
   interval within one, `rpm` reads 3000 within rounding, `rpm_sd_x10` reads 0 or
   close to it, and `rpm_min` and `rpm_max` bracket 3000 tightly.
2. **Alternating periods**, one revolution at 2900 rpm and the next at 3100. `rpm`
   reads 3000, `rpm_sd_x10` reads about 1414 (σ of that set is 141.4 rpm), `rpm_min`
   is 2900 and `rpm_max` is 3100.
3. **Input removed.** Within one interval, `rev_count` is 0 and all four new fields
   plus `rpm` are 0.
4. **One revolution in the interval.** `rev_count` is 1, `rpm_sd_x10` is 0, and
   `rpm_min == rpm == rpm_max`.
5. **A step from 1000 to 5000 rpm.** No interval reports a mean outside the range its
   own `rpm_min` and `rpm_max` bracket, which is the check that catches a boundary
   revolution counted in the wrong interval.
6. **Sustained 9000 rpm at the 750 ms cadence** for a minute, watching for a `rpm_sd`
   or mean that collapses or jumps, which is what a 32-bit sum-of-squares overflow
   looks like.
7. `sizeof(rpm_packet_v2_t) == 28` and a captured datagram is 28 bytes with the field
   offsets above.

## What the master does with it

For anyone checking the two ends agree. Over the packets in the displayed window, with
`n[i]`, `mean[i]` and `sd[i]` from each:

- **Roughness**, the within-interval part, is the count-weighted mean of `sd[i]^2`,
  square-rooted. Intervals with `rev_count < 2` contribute nothing and are excluded.
- **Wander**, the between-interval part, is the spread of the `mean[i]` about the
  fitted trend, which is the figure the master already computes today.
- Both are also reported as a percentage of the mean, because 40 rpm is a fault at
  idle and nothing at 6000.

## Open questions for the firmware author

Answers to these may change the spec, so please confirm before implementing.

1. **How many hall pulses per revolution?** This document assumes one. If it is more,
   `rpm_rev` should be derived from a full revolution rather than a single pulse, or
   the figures will describe pulse-to-pulse variation within a revolution instead of
   cycle-to-cycle variation between them.
2. **Is the v1 `rpm` already filtered**, and if so with what time constant? If it is,
   the v2 `rpm` becomes a different quantity (an unfiltered interval mean) and the
   change should be called out in the release notes rather than discovered.
3. **Is the reporting interval free-running or locked to a timer?** The master derives
   its freshness thresholds from the observed interval, so jitter is tolerated, but
   the statistics assume each packet describes one whole interval with no gap or
   overlap between consecutive ones.

---

## Firmware author answers (2026-08-09)

Accepted. The spec is implementable as written, with the clarifications below. The
wire format is unchanged from what this document specifies — nothing here requires
a master-side layout change, but items A4–A6 do affect master-side arithmetic and
interpretation, so read them before finalising the statistics code.

### A1. Pulses per revolution: two, and it changes nothing on the wire

`MAGNETS_PER_REV` is 2 — two magnets roughly 180° apart with **unequal** spacing.
`rpm_rev` is therefore derived from the sum of two consecutive pulse intervals,
which is exactly one full revolution, so the spacing asymmetry cancels regardless
of which magnet starts the pair. Pairs are taken **non-overlapping**, so the
per-revolution samples are statistically independent and the reported σ is
cycle-to-cycle variation between revolutions, as this document intends — not
pulse-to-pulse variation within one.

One consequence worth knowing: the figures resolve per-revolution behaviour, not
per-firing. On a multi-cylinder engine a weak cylinder shows up as elevated σ, not
as an identified cylinder.

### A2. v1 `rpm` was unfiltered — and v2's mean really is a new quantity

The v1 `rpm` is the instantaneous figure from the most recently completed
revolution, sampled at transmit time, with no filtering of any kind. Two changes
follow in v2, both intended:

- `rpm` (interval mean) averages **every** revolution in the interval, where v1
  could only sample whichever revolution was newest each 100 ms.
- `rpm_peak` keeps its monotonic-since-boot meaning but is now sourced from every
  per-revolution value rather than a 10 ms sampling of the live reading, so it can
  read marginally higher than v1 firmware would have on the same signal. Treat
  peak as a convenience, not a validated measurement — a single electrical glitch
  can still inflate it until reboot (the firmware bounds this at roughly
  15000 rpm via its glitch floor).

### A3. The interval is due-time-locked, and tiling is exact

The transmit timer re-bases on the previous due time on a 100 ms grid
(`NET_TX_PERIOD_MS`), sampled by a 10 ms main-loop tick, so a boundary lands
within ≤10 ms of the grid and the jitter does not accumulate. Consecutive
intervals tile exactly: each boundary is one atomic take-and-reset, and a
revolution straddling the boundary carries over and is counted once, in the
interval its final pulse completes in, per the spec. If the node ever falls a
whole period behind it skips forward rather than bursting — the master sees one
long interval, never overlapping ones.

Two related behaviours the master should expect:

- **After a link outage or (re)join, the first packet describes a fresh, short
  interval** — the firmware discards the accumulator at link-up. Statistics never
  span an outage.
- **`seq` in v2 increments once per interval unconditionally**, including when the
  node fails to hand the datagram to its network stack (the interval is consumed
  either way). A seq gap therefore means "interval not received" — in-transit loss
  or node-side send failure, indistinguishably. This differs from v1, where seq
  only counted successfully handed-off packets. The v1-emit compatibility build
  (`RPM_PROTO_EMIT_V1`) keeps the old v1 behaviour.

### A4. Correction: the composition identity needs sample-variance weights

The identity in the "Why" section is exact for **population** variance, but the
wire field is mandated (correctly) as **sample** sd with divisor `n - 1`. As
written, reconstituting with `n[i] * sd[i]^2` overstates the within-interval sum
of squares by `n/(n-1)` — negligible at 112 revolutions, but at the real 100 ms
cadence `rev_count` is ~5 at 3000 rpm and the error is ~25% of the variance.

The exact reconstruction from the wire fields, over intervals `i` with
`n[i] >= 1`:

```
N  = SUM( n[i] )
GM = SUM( n[i] * mean[i] ) / N
SS = SUM( (n[i] - 1) * sd[i]^2 )  +  SUM( n[i] * (mean[i] - GM)^2 )

population variance of the union = SS / N
sample variance of the union     = SS / (N - 1)
```

The first SS term is the within-interval (roughness) part and the second is the
between-interval (wander) part, so the roughness/wander split described in "What
the master does with it" still falls out directly — just weight `sd[i]^2` by
`n[i] - 1`, not `n[i]`.

### A5. Stale cadence figures in this document

The 750 ms cadence this document references twice predates a firmware fix: the
broadcast interval is 100 ms in **every** build now. Consequences:

- §Accumulation's "about 112 revolutions" per interval is ~15 at 9000 rpm. (The
  firmware uses Welford in float regardless, so the overflow concern is moot.)
- Acceptance test 6 should read "at the 100 ms cadence". It will be run at a
  sustained high rpm all the same, watching for σ/mean collapse.
- Expect `rev_count` values of roughly 0–2 at idle and ~15 at redline. The
  `rev_count <= 1` cases the spec calls out as common are the dominant case below
  ~1200 rpm.

### A6. Measurement floor on σ

Pulse periods are quantised to 1 µs in the firmware. At 7500 rpm a revolution is
8000 µs, so quantisation alone contributes a σ floor of roughly 0.2–0.5 rpm at the
top of the range (`rpm_sd_x10` of ~2–5). Do not interpret a small nonzero σ at
high rpm as combustion roughness, and do not expect an exact 0 from a perfectly
steady signal.

### A7. Everything else: confirmed as specified

Edge semantics (`rev_count` 0 and 1), u16 saturation instead of wrap,
`rpm_min <= rpm <= rpm_max` (enforced against float rounding at the boundary),
accumulator reset per interval with no rolling window, magic unchanged with
`version = 2`, 28-byte layout with the stated offsets, no mixed mode, and the
`RPM_PROTO_EMIT_V1` compile switch for legacy tooling. The 20-byte-with-version-2
combination remains invalid and is never emitted.
