#!/usr/bin/env python3
"""Record local model requests and optionally hold selected responses."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import signal
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Recorder:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.sequence = 0

    def next_sequence(self) -> int:
        with self.lock:
            self.sequence += 1
            return self.sequence

    def append(self, row: dict[str, object]) -> None:
        complete = {
            "wall_time": time.time(),
            "monotonic_ns": time.monotonic_ns(),
            **row,
        }
        encoded = json.dumps(complete, sort_keys=True) + "\n"
        with self.lock:
            with self.path.open("a", encoding="utf-8") as stream:
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())


class ProxyHandler(BaseHTTPRequestHandler):
    server: "RecordingServer"

    def log_message(self, format: str, *args: object) -> None:
        return

    def do_GET(self) -> None:
        if self.path == "/health":
            payload = b'{"ok":true}\n'
            self.send_response(200)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_error(404)

    def do_POST(self) -> None:
        length = int(self.headers.get("content-length", "0"))
        body = self.rfile.read(length)
        sequence = self.server.recorder.next_sequence()
        decoded = json.loads(body.decode("utf-8"))
        prompt = "\n".join(
            str(message.get("content", ""))
            for message in decoded.get("messages", [])
        )
        if "F4_RECOVERY_REWAKE" in prompt:
            marker = "recovery-rewake"
            delay_seconds = 0.0
        elif "F4_SLOW_CALL" in prompt:
            marker = "slow-call"
            delay_seconds = self.server.slow_delay_seconds
        elif "F4_FAST_EVAL" in prompt:
            marker = "fast-eval"
            delay_seconds = 0.0
        else:
            marker = "other"
            delay_seconds = 0.0
        shared = {
            "request_sequence": sequence,
            "marker": marker,
            "prompt_sha256": hashlib.sha256(
                prompt.encode("utf-8")
            ).hexdigest(),
            "request_bytes": len(body),
            "authorization_present": "authorization" in {
                key.lower() for key in self.headers.keys()
            },
        }
        self.server.recorder.append({"event": "received", **shared})
        request = urllib.request.Request(
            self.server.upstream,
            data=body,
            method="POST",
            headers={"content-type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                response_body = response.read()
                status = response.status
                content_type = response.headers.get(
                    "content-type", "application/json"
                )
            self.server.recorder.append(
                {
                    "event": "upstream-complete",
                    "status": status,
                    "response_bytes": len(response_body),
                    **shared,
                }
            )
            if delay_seconds:
                time.sleep(delay_seconds)
            try:
                self.send_response(status)
                self.send_header("content-type", content_type)
                self.send_header("content-length", str(len(response_body)))
                self.end_headers()
                self.wfile.write(response_body)
                self.wfile.flush()
                self.server.recorder.append(
                    {"event": "client-response", **shared}
                )
            except (BrokenPipeError, ConnectionResetError):
                self.server.recorder.append(
                    {"event": "client-disconnected", **shared}
                )
        except Exception as error:  # evidence path: record, then fail loudly
            self.server.recorder.append(
                {
                    "event": "proxy-error",
                    "error_class": type(error).__name__,
                    "error": str(error),
                    **shared,
                }
            )
            self.send_error(502, str(error))


class RecordingServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        address: tuple[str, int],
        recorder: Recorder,
        upstream: str,
        slow_delay_seconds: float,
    ) -> None:
        super().__init__(address, ProxyHandler)
        self.recorder = recorder
        self.upstream = upstream
        self.slow_delay_seconds = slow_delay_seconds


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18090)
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--slow-delay-seconds", type=float, default=30.0)
    arguments = parser.parse_args()
    recorder = Recorder(arguments.log)
    server = RecordingServer(
        ("127.0.0.1", arguments.port),
        recorder,
        arguments.upstream,
        arguments.slow_delay_seconds,
    )

    def stop(_signum: int, _frame: object) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    recorder.append(
        {
            "event": "proxy-ready",
            "port": arguments.port,
            "upstream": arguments.upstream,
        }
    )
    server.serve_forever()
    server.server_close()
    recorder.append({"event": "proxy-stopped"})


if __name__ == "__main__":
    main()
