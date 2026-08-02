"""One warm Seon operator root shared by an Inspect model instance."""

from __future__ import annotations

import atexit
import base64
import json
import os
import re
import socket
import subprocess
import threading
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
EPISODE_SEMANTICS = "one Inspect completion is one seeded Seon agent episode"
HISTORY_ENABLED = True
_ADVERTISEMENT_FIELD = re.compile(
    r":seon\.boot/(?P<name>prepl-host|prepl-port)\s+(?P<value>\"(?:\\.|[^\"])*\"|\d+)"
)


class SeonHostError(RuntimeError):
    """The warm Seon operator root failed before producing an episode."""


@dataclass(frozen=True)
class StoreSnapshot:
    """Physical size of regular files in the shared Datahike directory."""

    logical_bytes: int
    allocated_bytes: int
    regular_files: int


def _store_snapshot(path: Path) -> StoreSnapshot:
    logical = allocated = files = 0
    pending = [path]
    while pending:
        directory = pending.pop()
        if not directory.is_dir():
            continue
        try:
            entries = os.scandir(directory)
        except FileNotFoundError:
            continue
        with entries:
            for entry in entries:
                try:
                    if entry.is_symlink():
                        continue
                    if entry.is_dir(follow_symlinks=False):
                        pending.append(Path(entry.path))
                    elif entry.is_file(follow_symlinks=False):
                        stat = entry.stat(follow_symlinks=False)
                        logical += stat.st_size
                        allocated += getattr(stat, "st_blocks", 0) * 512
                        files += 1
                except FileNotFoundError:
                    continue
    return StoreSnapshot(logical, allocated, files)


def _advertisement(path: Path) -> tuple[str, int]:
    try:
        text = path.read_text()
    except OSError as error:
        raise SeonHostError(f"Seon wrote no readable advertisement at {path}") from error
    fields = {match.group("name"): match.group("value")
              for match in _ADVERTISEMENT_FIELD.finditer(text)}
    try:
        host = json.loads(fields["prepl-host"])
        port = int(fields["prepl-port"])
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise SeonHostError("Seon's advertisement lacks a prepl coordinate") from error
    if host not in {"127.0.0.1", "::1", "localhost"} or not 0 < port < 65536:
        raise SeonHostError("Seon advertised a non-loopback or invalid prepl")
    return host, port


def _edn_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def _sample_form(request: dict[str, Any], token: str) -> str:
    fields = {
        ":seon.eval.drive/root": _edn_string(request["root"]),
        ":seon.eval.drive/sample-id": _edn_string(request["sample_id"]),
        ":seon.eval.drive/objective": _edn_string(request["objective"]),
        ":seon.eval.drive/run-cap": str(request["run_cap"]),
        ":seon.eval.drive/remote-timeout-ms": str(request["timeout_ms"]),
    }
    request_edn = "{" + " ".join(f"{key} {value}" for key, value in fields.items()) + "}"
    return (
        "(do (require 'seon.eval.drive) "
        f"(let [json# (seon.eval.drive/run-sample-json! {request_edn}) "
        "bytes# (.getBytes ^String json# java.nio.charset.StandardCharsets/UTF_8) "
        "payload# (.encodeToString (java.util.Base64/getEncoder) bytes#)] "
        f'(println (str "{token}<" payload# ">{token}")) nil))'
    )


class SeonHost:
    """Own one history-off operator root and its shared prepl."""

    def __init__(self, run_cap: int = 6, timeout_ms: int | None = None) -> None:
        self.run_cap = run_cap
        self.timeout_ms = timeout_ms or run_cap * 240_000
        self.run_id = uuid.uuid4().hex[:12]
        self.root = REPO_ROOT / "tmp" / "inspect-ai" / self.run_id
        self.cluster_root = self.root / "data" / "clusters"
        self.store_path = self.cluster_root / "store"
        self.summary_path = self.root / "store-growth.json"
        self._start_lock = threading.Lock()
        self._measurement_lock = threading.Lock()
        self._started = False
        self._closed = False
        self._active_samples: set[str] = set()
        self._completed_samples = 0
        self._errored_samples = 0
        self._baseline = StoreSnapshot(0, 0, 0)
        self._latest = self._baseline
        atexit.register(self.close)

    def _environment(self) -> dict[str, str]:
        return dict(os.environ)

    def _operator(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [str(REPO_ROOT / "bin" / "seon"), "--root", str(self.root), *arguments],
            cwd=REPO_ROOT,
            env=self._environment(),
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip()
            raise SeonHostError(f"bin/seon {' '.join(arguments)} failed: {detail}")
        return result

    def start(self) -> None:
        """Start the eval-host cluster exactly once."""
        with self._start_lock:
            if self._started:
                return
            if self._closed:
                raise SeonHostError("The Seon operator root is already closed")
            self.root.mkdir(parents=True, exist_ok=False)
            self._operator("init")
            self._operator("start", "eval-host")
            _advertisement(self.cluster_root / "eval-host" / "prepl.edn")
            self._baseline = _store_snapshot(self.store_path)
            self._latest = self._baseline
            self._started = True
            self._write_summary()

    def _write_summary(self) -> None:
        summary = {
            "seon_episode_semantics": EPISODE_SEMANTICS,
            "operator_root": str(self.root),
            "history_enabled": HISTORY_ENABLED,
            "completed_samples": self._completed_samples,
            "errored_samples": self._errored_samples,
            "baseline": asdict(self._baseline),
            "latest": asdict(self._latest),
            "aggregate_growth": {
                "logical_bytes": self._latest.logical_bytes - self._baseline.logical_bytes,
                "allocated_bytes": self._latest.allocated_bytes - self._baseline.allocated_bytes,
                "regular_files": self._latest.regular_files - self._baseline.regular_files,
            },
        }
        temporary = self.summary_path.with_suffix(".json.next")
        temporary.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
        os.replace(temporary, self.summary_path)

    def _eval_sample(self, request: dict[str, Any]) -> dict[str, Any]:
        host, port = _advertisement(self.cluster_root / "eval-host" / "prepl.edn")
        token = f"SEON-INSPECT-{uuid.uuid4().hex}"
        form = _sample_form(request, token)
        marker = re.compile(
            re.escape(token).encode() + rb"<([A-Za-z0-9+/=]+)>" + re.escape(token).encode()
        )
        raw = bytearray()
        socket_timeout = self.timeout_ms / 1000 + 60
        with socket.create_connection((host, port), timeout=socket_timeout) as connection:
            connection.settimeout(socket_timeout)
            connection.sendall(form.encode() + b"\n")
            connection.shutdown(socket.SHUT_WR)
            while True:
                chunk = connection.recv(65_536)
                if not chunk:
                    break
                raw.extend(chunk)
                if b"{:tag :ret" in raw:
                    break
        terminal = [line for line in bytes(raw).splitlines()
                    if line.startswith(b"{:tag :ret")]
        if not terminal:
            raise SeonHostError("The eval-host prepl closed before :ret")
        if b":exception true" in terminal[-1]:
            raise SeonHostError(terminal[-1].decode(errors="replace")[:4000])
        matches = marker.findall(bytes(raw))
        if len(matches) != 1:
            raise SeonHostError("The eval-host prepl returned no unique episode payload")
        try:
            return json.loads(base64.b64decode(matches[0], validate=True))
        except (ValueError, json.JSONDecodeError) as error:
            raise SeonHostError("The eval-host episode payload was not JSON") from error

    def run_sample(self, sample_id: str, objective: str) -> dict[str, Any]:
        """Run one sample and attach shared-store interval measurements."""
        self.start()
        with self._measurement_lock:
            before = _store_snapshot(self.store_path)
            overlaps = sorted(self._active_samples)
            self._active_samples.add(sample_id)
        succeeded = False
        try:
            report = self._eval_sample({
                "root": str(self.cluster_root),
                "sample_id": sample_id,
                "objective": objective,
                "run_cap": self.run_cap,
                "timeout_ms": self.timeout_ms,
            })
            succeeded = True
        finally:
            with self._measurement_lock:
                after = _store_snapshot(self.store_path)
                overlaps = sorted(
                    set(overlaps) | (self._active_samples - {sample_id})
                )
                self._active_samples.discard(sample_id)
                self._completed_samples += int(succeeded)
                self._errored_samples += int(not succeeded)
                self._latest = after
                self._write_summary()
        report["seon.store.growth/measurement"] = {
            "scope": "shared_store_interval",
            "overlapping_sample_ids": overlaps,
            "before": asdict(before),
            "after": asdict(after),
            "growth": {
                "logical_bytes": after.logical_bytes - before.logical_bytes,
                "allocated_bytes": after.allocated_bytes - before.allocated_bytes,
                "regular_files": after.regular_files - before.regular_files,
            },
            "aggregate_growth_through_sample": {
                "logical_bytes": after.logical_bytes - self._baseline.logical_bytes,
                "allocated_bytes": after.allocated_bytes - self._baseline.allocated_bytes,
                "regular_files": after.regular_files - self._baseline.regular_files,
            },
            "summary_path": str(self.summary_path),
        }
        return report

    def close(self) -> None:
        """Stop every cluster in this operator root."""
        with self._start_lock:
            if self._closed:
                return
            self._closed = True
            if self._started:
                try:
                    self._operator("down")
                except Exception:
                    pass
