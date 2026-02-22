#!/usr/bin/env python3
"""Client+Server benchmark via local proxy endpoint.

Supports socks5/http proxy benchmarking with mixed GET/CONNECT-like traffic by regular HTTPS GET.
"""

from __future__ import annotations
import argparse
import concurrent.futures
import socket
import threading
import time
import urllib.parse

import socks  # PySocks


class Stats:
    def __init__(self) -> None:
        self.ok = 0
        self.fail = 0
        self.bytes = 0
        self.lat_total_ms = 0.0
        self.lat_samples = 0
        self.lock = threading.Lock()

    def record_ok(self, nbytes: int, latency_ms: float) -> None:
        with self.lock:
            self.ok += 1
            self.bytes += nbytes
            self.lat_total_ms += latency_ms
            self.lat_samples += 1

    def record_fail(self) -> None:
        with self.lock:
            self.fail += 1


def build_http_request(url: str, host: str) -> bytes:
    path = urllib.parse.urlsplit(url).path or "/"
    if urllib.parse.urlsplit(url).query:
        path += "?" + urllib.parse.urlsplit(url).query
    req = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}\r\n"
        "User-Agent: socks5-bench/1.0\r\n"
        "Connection: close\r\n\r\n"
    )
    return req.encode()


def do_one_request(args: argparse.Namespace, stats: Stats) -> None:
    begin = time.perf_counter()
    parsed = urllib.parse.urlsplit(args.url)
    host = parsed.hostname
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    if not host:
        stats.record_fail()
        return

    s = socks.socksocket() if args.proxy_type == "socks5" else socket.socket()
    try:
        s.settimeout(args.timeout)
        if args.proxy_type == "socks5":
            s.set_proxy(socks.SOCKS5, args.proxy_host, args.proxy_port, rdns=True)
            s.connect((host, port))
        else:
            s.connect((args.proxy_host, args.proxy_port))
            connect = f"CONNECT {host}:{port} HTTP/1.1\r\nHost: {host}:{port}\r\n\r\n".encode()
            s.sendall(connect)
            _ = s.recv(4096)

        if parsed.scheme == "https":
            import ssl
            ctx = ssl.create_default_context()
            if args.insecure:
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
            s = ctx.wrap_socket(s, server_hostname=host)

        req = build_http_request(args.url, host)
        s.sendall(req)
        total = 0
        while True:
            data = s.recv(8192)
            if not data:
                break
            total += len(data)

        latency = (time.perf_counter() - begin) * 1000
        stats.record_ok(total, latency)
    except Exception:
        stats.record_fail()
    finally:
        try:
            s.close()
        except Exception:
            pass


def main() -> None:
    p = argparse.ArgumentParser(description="Client+Server benchmark through proxy")
    p.add_argument("--proxy-type", choices=["socks5", "http"], default="socks5")
    p.add_argument("--proxy-host", default="127.0.0.1")
    p.add_argument("--proxy-port", type=int, default=9090)
    p.add_argument("--url", default="https://example.com/")
    p.add_argument("--requests", type=int, default=1000)
    p.add_argument("--concurrency", type=int, default=100)
    p.add_argument("--timeout", type=float, default=10)
    p.add_argument("--insecure", action="store_true")
    args = p.parse_args()

    stats = Stats()
    print(f"start proxy={args.proxy_type}://{args.proxy_host}:{args.proxy_port} url={args.url} req={args.requests} c={args.concurrency}")

    start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        futures = [ex.submit(do_one_request, args, stats) for _ in range(args.requests)]
        for i, f in enumerate(concurrent.futures.as_completed(futures), start=1):
            _ = f.result()
            if i % max(args.requests // 10, 1) == 0:
                with stats.lock:
                    done = stats.ok + stats.fail
                    avg = (stats.lat_total_ms / stats.lat_samples) if stats.lat_samples else 0
                print(f"progress {done}/{args.requests} ok={stats.ok} fail={stats.fail} avg={avg:.2f}ms")

    elapsed = time.perf_counter() - start
    with stats.lock:
        avg = (stats.lat_total_ms / stats.lat_samples) if stats.lat_samples else 0
        p95_note = "(如需 p95 建议导出明细后用 numpy 统计)"
        print("\n===== summary =====")
        print(f"elapsed={elapsed:.2f}s ok={stats.ok} fail={stats.fail} success_rate={(stats.ok / max(stats.ok + stats.fail, 1)) * 100:.2f}%")
        print(f"qps={stats.ok / max(elapsed, 1e-9):.2f} avg_latency={avg:.2f}ms {p95_note}")
        print(f"recv_bytes={stats.bytes} ({stats.bytes / max(elapsed, 1e-9) / 1024 / 1024:.2f} MB/s)")


if __name__ == "__main__":
    main()
