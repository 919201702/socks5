#!/usr/bin/env python3
"""Simple TCP echo server for benchmark target."""

import argparse
import socket
import threading


def handle(conn: socket.socket) -> None:
    try:
        while True:
            data = conn.recv(65535)
            if not data:
                break
            conn.sendall(data)
    finally:
        conn.close()


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=9000)
    args = p.parse_args()

    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((args.host, args.port))
    s.listen(1024)
    print(f"echo server listening on {args.host}:{args.port}")
    while True:
        conn, _ = s.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
