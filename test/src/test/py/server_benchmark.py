#!/usr/bin/env python3
"""Server-only benchmark for socks5 project.

Scenarios:
1) auth: TLS connect + AUTH frame only.
2) tunnel-echo: AUTH + CONNECT + DATA/echo RTT loop (requires reachable echo target).

Protocol format: [len:4 bytes big-endian][type:1 byte][payload:N bytes]
"""

from __future__ import annotations
import argparse
import os
import socket
import ssl
import struct
import threading
import time
from dataclasses import dataclass

TYPE_AUTH = 1
TYPE_CONNECT = 2
TYPE_DATA = 3
TYPE_DISCONNECT = 4
TYPE_CONNECT_SUCCESS = 5
TYPE_CONNECT_FAIL = 6


@dataclass
class Stats:
    ok: int = 0
    fail: int = 0
    sent_bytes: int = 0
    recv_bytes: int = 0
    total_rtt_ms: float = 0.0
    rtt_samples: int = 0


stats = Stats()
stats_lock = threading.Lock()


def send_frame(sock: ssl.SSLSocket, msg_type: int, payload: bytes = b"") -> None:
    body = bytes([msg_type]) + payload
    sock.sendall(struct.pack(">I", len(body)) + body)


def recv_exact(sock: ssl.SSLSocket, n: int) -> bytes:
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("socket closed")
        data += chunk
    return data


def recv_frame(sock: ssl.SSLSocket) -> tuple[int, bytes]:
    length = struct.unpack(">I", recv_exact(sock, 4))[0]
    body = recv_exact(sock, length)
    return body[0], body[1:]


def one_auth_round(args: argparse.Namespace, tls_ctx: ssl.SSLContext) -> None:
    s = socket.create_connection((args.server_host, args.server_port), timeout=args.timeout)
    try:
        with tls_ctx.wrap_socket(s, server_hostname=args.sni or args.server_host) as ssock:
            send_frame(ssock, TYPE_AUTH, args.token.encode())
            if args.wait_response:
                try:
                    recv_frame(ssock)
                except Exception:
                    pass
    finally:
        s.close()


def one_tunnel_round(args: argparse.Namespace, tls_ctx: ssl.SSLContext, payload: bytes) -> None:
    s = socket.create_connection((args.server_host, args.server_port), timeout=args.timeout)
    try:
        with tls_ctx.wrap_socket(s, server_hostname=args.sni or args.server_host) as ssock:
            send_frame(ssock, TYPE_AUTH, args.token.encode())
            send_frame(ssock, TYPE_CONNECT, f"{args.target_host}:{args.target_port}".encode())
            mtype, _ = recv_frame(ssock)
            if mtype != TYPE_CONNECT_SUCCESS:
                raise RuntimeError(f"connect failed, msg_type={mtype}")

            for _ in range(args.messages_per_conn):
                begin = time.perf_counter()
                send_frame(ssock, TYPE_DATA, payload)
                mtype, data = recv_frame(ssock)
                if mtype != TYPE_DATA:
                    raise RuntimeError(f"unexpected msg_type={mtype}")
                if args.expect_echo and data != payload:
                    raise RuntimeError("echo payload mismatch")
                elapsed = (time.perf_counter() - begin) * 1000
                with stats_lock:
                    stats.sent_bytes += len(payload)
                    stats.recv_bytes += len(data)
                    stats.total_rtt_ms += elapsed
                    stats.rtt_samples += 1

            send_frame(ssock, TYPE_DISCONNECT)
    finally:
        s.close()


def worker(args: argparse.Namespace, tid: int) -> None:
    tls_ctx = ssl.create_default_context()
    if args.insecure:
        tls_ctx.check_hostname = False
        tls_ctx.verify_mode = ssl.CERT_NONE
    else:
        tls_ctx.check_hostname = bool(args.sni)
        if args.ca_cert:
            tls_ctx.load_verify_locations(cafile=args.ca_cert)

    payload = os.urandom(args.payload_bytes)

    for _ in range(args.iterations):
        try:
            if args.mode == "auth":
                one_auth_round(args, tls_ctx)
            else:
                one_tunnel_round(args, tls_ctx, payload)
            with stats_lock:
                stats.ok += 1
        except Exception:
            with stats_lock:
                stats.fail += 1


def monitor(stop_event: threading.Event, start: float) -> None:
    last_ok = 0
    while not stop_event.is_set():
        time.sleep(1)
        with stats_lock:
            ok, fail = stats.ok, stats.fail
            sent, recv_ = stats.sent_bytes, stats.recv_bytes
            samples = stats.rtt_samples
            avg_rtt = (stats.total_rtt_ms / samples) if samples else 0.0
        qps = ok - last_ok
        last_ok = ok
        print(
            f"[1s] ok={ok} fail={fail} qps={qps} sent={sent/1024/1024:.2f}MB recv={recv_/1024/1024:.2f}MB avg_rtt={avg_rtt:.2f}ms"
        )

    elapsed = time.perf_counter() - start
    with stats_lock:
        ok, fail = stats.ok, stats.fail
        sent, recv_ = stats.sent_bytes, stats.recv_bytes
        samples = stats.rtt_samples
        avg_rtt = (stats.total_rtt_ms / samples) if samples else 0.0
    print("\n===== summary =====")
    print(f"elapsed={elapsed:.2f}s ok={ok} fail={fail} success_rate={(ok / max(ok + fail, 1)) * 100:.2f}%")
    print(f"throughput_up={sent / max(elapsed, 1e-9) / 1024 / 1024:.2f}MB/s throughput_down={recv_ / max(elapsed, 1e-9) / 1024 / 1024:.2f}MB/s")
    print(f"avg_rtt={avg_rtt:.2f}ms samples={samples}")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Server-only pressure benchmark")
    p.add_argument("--mode", choices=["auth", "tunnel-echo"], default="auth")
    p.add_argument("--server-host", default="127.0.0.1")
    p.add_argument("--server-port", type=int, default=8001)
    p.add_argument("--token", default="token-01")
    p.add_argument("--threads", type=int, default=50)
    p.add_argument("--iterations", type=int, default=100)
    p.add_argument("--timeout", type=float, default=10)
    p.add_argument("--insecure", action="store_true", help="disable TLS cert verification")
    p.add_argument("--ca-cert", default="", help="CA cert path if verification enabled")
    p.add_argument("--sni", default="", help="TLS SNI hostname")
    p.add_argument("--wait-response", action="store_true", help="auth mode: wait one response frame")

    p.add_argument("--target-host", default="127.0.0.1", help="tunnel-echo mode")
    p.add_argument("--target-port", type=int, default=9000, help="tunnel-echo mode")
    p.add_argument("--messages-per-conn", type=int, default=10)
    p.add_argument("--payload-bytes", type=int, default=1024)
    p.add_argument("--expect-echo", action="store_true", help="assert echoed payload is identical")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    print(f"start mode={args.mode} server={args.server_host}:{args.server_port} threads={args.threads} iterations={args.iterations}")
    start = time.perf_counter()
    stop = threading.Event()
    tmon = threading.Thread(target=monitor, args=(stop, start), daemon=True)
    tmon.start()

    threads = [threading.Thread(target=worker, args=(args, i), daemon=True) for i in range(args.threads)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    stop.set()
    tmon.join(timeout=2)


if __name__ == "__main__":
    main()
