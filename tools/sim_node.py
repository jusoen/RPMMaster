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
"""

import argparse
import math
import random
import socket
import struct
import sys
import time

MAGIC = 0x314D5052       # "RPM1"
VERSION = 1
PORT = 4210
PACKET_FMT = "<IBBHIII"  # little-endian, packed, 20 bytes

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


def rpm_at(t, args):
    """The engine profile at t seconds.

    A sine through the middle of the design window, with a periodic stall spell so
    the STALL/0 path is exercised, and an occasional excursion to ~9000 so the
    out-of-range flag is exercised too (A.5).
    """
    if args.stall_period > 0 and (t % args.stall_period) < args.stall_length:
        return 0
    if args.overrange_period > 0 and (t % args.overrange_period) < args.overrange_length:
        return args.overrange_rpm
    return int(3000 + 2500 * math.sin(t / 5.0))


def fuzz_datagrams(node_id, seq, uptime_ms):
    """Malformed datagrams the listener must ignore without crashing.

    Each must be dropped silently, except the version-2 one, which the app is
    required to count and surface rather than discard.
    """
    good = build(node_id, seq, uptime_ms, 3000, 5000)
    return [
        ("short (8 bytes)", good[:8]),
        ("oversized (64 bytes, valid prefix)", good + b"\x00" * 44),
        ("bad magic", build(node_id, seq, uptime_ms, 3000, 5000, magic=0x314D5053)),
        ("big-endian magic", struct.pack(">IBBHIII", MAGIC, 1, node_id, seq, uptime_ms, 3000, 5000)),
        ("version 2", build(node_id, seq, uptime_ms, 3000, 5000, version=2)),
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

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    dest = (args.host, args.port)

    seq = args.seq_start & 0xFFFF
    peak = 0
    sent = 0
    dropped = 0
    t0 = time.monotonic()
    next_due = t0
    next_status = t0 + 1.0
    next_fuzz = t0 + 1.0

    print(f"node {args.node_id} -> {args.host}:{args.port} every {args.rate}s "
          f"(seq from {seq}{', fuzzing' if args.fuzz else ''})", file=sys.stderr)

    try:
        while True:
            now = time.monotonic()
            t = now - t0
            if args.duration > 0 and t >= args.duration:
                break

            rpm = rpm_at(t, args)
            # rpm_peak is monotonic since boot, exactly as the firmware reports it.
            peak = max(peak, rpm)
            pkt = build(args.node_id, seq, int(t * 1000), rpm, peak)

            # The firmware increments seq only on a successful hand-off to the stack,
            # so a node-side drop leaves no gap (A.2). Losing the datagram *after*
            # the increment is what a link loss looks like, and that is what --loss
            # models here.
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
                print(f"t={t:7.1f}s seq={seq:5d} rpm={rpm:5d} peak={peak:5d} "
                      f"sent={sent} dropped={dropped} {label}", file=sys.stderr)

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
