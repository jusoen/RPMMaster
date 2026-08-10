#!/usr/bin/env python3
"""Simulates one RPM slave node broadcasting to the master app.

Run this on a laptop joined to the phone's hotspot. It is the section 6 simulator
from the work order, amended per Appendix A.1 (the cadence is an argument, because
the real firmware sends at 100 ms, 250 ms or 750 ms depending on its build switches)
and A.5 (an occasional out-of-range value, so the visual flag actually gets
exercised).

This tool is one-way, exactly like the firmware. Nothing here ever listens.

Examples
--------
    python sim_node.py                          # node 1, 10 Hz, the default profile
    python sim_node.py --node-id 2 --rate 0.25  # a second node at the production cadence
    python sim_node.py --rate 0.75              # the loopback-harness cadence
    python sim_node.py --node-id 1 --host 192.168.1.50   # a second instance, same id
    python sim_node.py --fuzz                   # malformed datagrams interleaved
    python sim_node.py --v2 --jitter-within 80  # protocol v2, rough combustion
    python sim_node.py --v2 --steady 3000 --jitter 40 --jitter-within 80
"""

import argparse
import math
import random
import socket
import statistics
import struct
import sys
import time

MAGIC = 0x314D5052       # "RPM1"
VERSION = 1
VERSION_2 = 2
PORT = 4210
PACKET_FMT = "<IBBHIII"      # little-endian, packed, 20 bytes
PACKET_FMT_V2 = "<IBBHIII4H"  # the same header plus four u16, 28 bytes

U16_MAX = 65535

# The sensor design window. Anything outside it should be flagged by the app.
PLAUSIBLE_MIN = 300
PLAUSIBLE_MAX = 7500


def build(node_id, seq, uptime_ms, rpm, peak, version=VERSION, magic=MAGIC):
    """Pack one 20-byte packet. Fields are masked to their wire widths."""
    return struct.pack(
        PACKET_FMT,
        magic & 0xFFFFFFFF,
        version & 0xFF,
        node_id & 0xFF,
        seq & 0xFFFF,
        uptime_ms & 0xFFFFFFFF,
        rpm & 0xFFFFFFFF,
        peak & 0xFFFFFFFF,
    )


def saturate(value):
    """Clamp to the u16 range. The firmware saturates rather than wrapping, because a
    wrapped value is indistinguishable from a real one and would be believed."""
    return max(0, min(int(value), U16_MAX))


def build_v2(node_id, seq, uptime_ms, revs, peak, version=VERSION_2, magic=MAGIC):
    """Pack one 28-byte v2 packet from the revolutions measured in this interval.

    `revs` is the list of per-revolution rpm values that *completed* in the interval.
    An empty list is the legitimate rev_count == 0 case (stopped, or turning too
    slowly for a revolution to close inside the cadence) and every field goes to zero
    with it, including rpm.
    """
    if revs:
        count = len(revs)
        mean = round(sum(revs) / count)
        # Sample standard deviation, divisor n - 1, which is what the wire field is
        # specified as. Undefined for a single revolution, and sent as 0 there.
        sd_x10 = round(statistics.stdev(revs) * 10) if count >= 2 else 0
        lo, hi = min(revs), max(revs)
    else:
        count = mean = sd_x10 = lo = hi = 0

    return struct.pack(
        PACKET_FMT_V2,
        magic & 0xFFFFFFFF,
        version & 0xFF,
        node_id & 0xFF,
        seq & 0xFFFF,
        uptime_ms & 0xFFFFFFFF,
        mean & 0xFFFFFFFF,
        peak & 0xFFFFFFFF,
        saturate(count),
        saturate(sd_x10),
        saturate(lo),
        saturate(hi),
    ), mean


def revolutions_in(phase, rpm, dt, sigma_within):
    """The per-revolution rpm values completing in one interval, and the new phase.

    Revolutions are counted through a phase accumulator rather than by rounding, so
    the fractional part carries over and the long-run count is right. This is what
    produces the rev_count of 0, 1 or 2 that dominates below about 1200 rpm at the
    100 ms cadence — the case the master has to handle and cannot be shown otherwise.
    """
    if rpm <= 0:
        # Not turning. The phase is dropped rather than held: a stall is not a pause
        # in the middle of a revolution that later completes.
        return [], 0.0
    phase += rpm / 60.0 * dt
    count = int(phase)
    phase -= count
    if sigma_within <= 0:
        return [rpm] * count, phase
    # Each revolution independently, around the interval's own mean. This is the
    # variation the master calls roughness, and it is deliberately separate from
    # --jitter, which moves the mean itself and reads as wander.
    return [max(1, round(random.gauss(rpm, sigma_within))) for _ in range(count)], phase


def rpm_at(t, args):
    """The engine profile at t seconds.

    A sine through the middle of the design window unless --steady overrides it, with
    a periodic stall spell so
    the zero path is exercised, and an occasional excursion to ~9000 so the
    out-of-range flag is exercised too (A.5).

    A run opens in a stall spell, so the peak starts at 0 and builds. The overrange
    excursion is held off until a full period has elapsed for the same reason:
    firing it at t=0 would put the run's peak at its extreme within the first packet,
    which is exactly the case the master's high-water mark exists to show building up.
    """
    if args.stall_period > 0 and (t % args.stall_period) < args.stall_length:
        return 0
    if args.steady is not None:
        return args.steady
    if (args.overrange_period > 0 and t >= args.overrange_period
            and (t % args.overrange_period) < args.overrange_length):
        return args.overrange_rpm
    return int(3000 + 2500 * math.sin(t / 5.0))


def fuzz_datagrams(node_id, seq, uptime_ms):
    """Malformed datagrams the listener must ignore without crashing.

    Each must be dropped silently, except the two version disagreements, which the app
    is required to count and surface rather than discard.
    """
    good = build(node_id, seq, uptime_ms, 3000, 5000)
    return [
        ("short (8 bytes)", good[:8]),
        ("oversized (64 bytes, valid prefix)", good + b"\x00" * 44),
        ("bad magic", build(node_id, seq, uptime_ms, 3000, 5000, magic=0x314D5053)),
        ("big-endian magic", struct.pack(">IBBHIII", MAGIC, 1, node_id, seq, uptime_ms, 3000, 5000)),
        # Length and version have to agree, so both disagreements are exercised: a
        # v2 claim in 20 bytes, and a v1 claim in 28.
        ("version 2 in 20 bytes", build(node_id, seq, uptime_ms, 3000, 5000, version=2)),
        ("version 1 in 28 bytes",
         build_v2(node_id, seq, uptime_ms, [3000] * 5, 5000, version=VERSION)[0]),
        ("empty", b""),
        ("random 20 bytes", bytes(random.getrandbits(8) for _ in range(20))),
        ("random 137 bytes", bytes(random.getrandbits(8) for _ in range(137))),
    ]


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Simulate an RPM slave node.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    ap.add_argument("node_id_pos", nargs="?", type=int, default=None,
                    help="node id, positional form kept for compatibility with the brief")
    ap.add_argument("--node-id", type=int, default=1, help="node id, 0-255")
    ap.add_argument("--rate", type=float, default=0.1,
                    help="seconds between packets. Test 0.1, 0.25 and 0.75 (A.1)")
    ap.add_argument("--host", default="255.255.255.255",
                    help="destination address. Use the phone's hotspot address if "
                         "limited broadcast is filtered on your network")
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--loss", type=float, default=0.0,
                    help="fraction of packets to drop after incrementing seq, "
                         "simulating link loss. 0.02 gives a visible loss figure")
    ap.add_argument("--seq-start", type=int, default=0,
                    help="starting seq. Try 65500 to exercise the u16 wrap within a minute")
    ap.add_argument("--stall-period", type=float, default=30.0, help="seconds between stall spells")
    ap.add_argument("--stall-length", type=float, default=3.0, help="seconds each stall spell lasts")
    ap.add_argument("--overrange-period", type=float, default=17.0,
                    help="seconds between out-of-range excursions, 0 to disable")
    ap.add_argument("--overrange-length", type=float, default=1.0, help="seconds each excursion lasts")
    ap.add_argument("--overrange-rpm", type=int, default=9000, help="the out-of-range value to send")
    ap.add_argument("--steady", type=int, default=None,
                    help="hold this rpm instead of sweeping the sine. The sweep is a "
                         "500-5500 swing every 31 s, which is real instability and "
                         "swamps anything --jitter adds, so a steady engine is what "
                         "the master's stability figure has to be judged against")
    ap.add_argument("--jitter", type=float, default=0.0,
                    help="one sigma of gaussian noise added to rpm, so the master's "
                         "stability figure has something to measure. The profile is "
                         "otherwise perfectly smooth and reads as 0.00%% instability")
    ap.add_argument("--v2", action="store_true",
                    help="emit the 28-byte version-2 packet with per-interval "
                         "statistics instead of the 20-byte version-1 one")
    ap.add_argument("--jitter-within", type=float, default=0.0,
                    help="one sigma of gaussian noise applied to each revolution "
                         "separately, which is the combustion roughness the master "
                         "reports from v2 statistics. Distinct from --jitter, which "
                         "moves the interval mean and reads as wander. Requires --v2")
    ap.add_argument("--fuzz", action="store_true",
                    help="interleave malformed datagrams once per second")
    ap.add_argument("--duration", type=float, default=0.0, help="seconds to run, 0 for forever")
    ap.add_argument("--quiet", action="store_true", help="suppress the per-second status line")
    args = ap.parse_args(argv)

    if args.node_id_pos is not None:
        args.node_id = args.node_id_pos
    if not 0 <= args.node_id <= 255:
        ap.error("--node-id must be 0-255")
    if args.rate <= 0:
        ap.error("--rate must be positive")
    if not 0.0 <= args.loss < 1.0:
        ap.error("--loss must be in [0, 1)")
    if args.jitter < 0:
        ap.error("--jitter must not be negative")
    if args.jitter_within < 0:
        ap.error("--jitter-within must not be negative")
    if args.jitter_within > 0 and not args.v2:
        # Silently discarding it would look like the master failing to report
        # roughness, which is the one thing this option exists to exercise.
        ap.error("--jitter-within has no effect without --v2: version 1 carries no "
                 "per-revolution statistics")

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    dest = (args.host, args.port)

    seq = args.seq_start & 0xFFFF
    peak = 0
    sent = 0
    dropped = 0
    phase = 0.0
    rev_count = 0
    t0 = time.monotonic()
    next_due = t0
    next_status = t0 + 1.0
    next_fuzz = t0 + 1.0

    print(f"node {args.node_id} -> {args.host}:{args.port} every {args.rate}s, "
          f"protocol v{VERSION_2 if args.v2 else VERSION} "
          f"(seq from {seq}{', fuzzing' if args.fuzz else ''})", file=sys.stderr)

    try:
        while True:
            now = time.monotonic()
            t = now - t0
            if args.duration > 0 and t >= args.duration:
                break

            rpm = rpm_at(t, args)
            # Never applied to a stall. 0 means "not turning" and a jittered zero
            # would be a different claim, one the app is entitled to treat as a
            # running engine.
            if args.jitter > 0 and rpm > 0:
                rpm = max(0, int(round(random.gauss(rpm, args.jitter))))

            if args.v2:
                revs, phase = revolutions_in(phase, rpm, args.rate, args.jitter_within)
                rev_count = len(revs)
                # v2 sources rpm_peak from every per-revolution value rather than from
                # a sampling of the live reading, so it can read marginally higher than
                # v1 firmware would on the same signal.
                if revs:
                    peak = max(peak, max(revs))
                # rpm on the wire is the interval mean, which is not quite the profile
                # value that generated it. Rebound here so the status line reports what
                # was actually sent.
                pkt, rpm = build_v2(args.node_id, seq, int(t * 1000), revs, peak)
            else:
                # rpm_peak is monotonic since boot, exactly as the firmware reports it.
                peak = max(peak, rpm)
                pkt = build(args.node_id, seq, int(t * 1000), rpm, peak)

            # v1 firmware increments seq only on a successful hand-off to the stack, so
            # a node-side drop leaves no gap (A.2). v2 increments once per interval
            # regardless, because the interval is consumed either way. Losing the
            # datagram *after* the increment is what a link loss looks like on both,
            # and that is what --loss models here.
            seq = (seq + 1) & 0xFFFF
            if args.loss > 0 and random.random() < args.loss:
                dropped += 1
            else:
                try:
                    s.sendto(pkt, dest)
                    sent += 1
                except OSError as e:
                    # A send failure is not fatal — the network may not be up yet.
                    print(f"send failed: {e}", file=sys.stderr)

            if args.fuzz and now >= next_fuzz:
                next_fuzz += 1.0
                for name, data in fuzz_datagrams(args.node_id, seq, int(t * 1000)):
                    try:
                        s.sendto(data, dest)
                    except OSError as e:
                        print(f"fuzz send failed ({name}): {e}", file=sys.stderr)

            if not args.quiet and now >= next_status:
                next_status += 1.0
                label = "STALL" if rpm == 0 else ("OUT-OF-RANGE" if
                        rpm < PLAUSIBLE_MIN or rpm > PLAUSIBLE_MAX else "")
                revs_note = f"revs={rev_count:3d} " if args.v2 else ""
                print(f"t={t:7.1f}s seq={seq:5d} rpm={rpm:5d} peak={peak:5d} "
                      f"{revs_note}sent={sent} dropped={dropped} {label}", file=sys.stderr)

            # Re-base on the due time, not on "now", so the cadence averages out
            # rather than drifting by however long the loop body took.
            next_due += args.rate
            sleep_for = next_due - time.monotonic()
            if sleep_for > 0:
                time.sleep(sleep_for)
            else:
                # Fell behind (a long stall on the socket, or a very small --rate).
                # Resynchronise rather than spinning to catch up.
                next_due = time.monotonic()
    except KeyboardInterrupt:
        print(file=sys.stderr)
    finally:
        s.close()
        print(f"stopped: {sent} sent, {dropped} dropped", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
