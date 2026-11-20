#!/usr/bin/env python3
"""Thin Harbor agent adapter, host-side Container Exec Bridge, & container driver for compiled `target/seon`.

Host-Bridge Mode (default on macOS & Linux — zero Linux cross-compilation, works on any Docker image):
    PYTHONPATH=. uvx --from harbor harbor run -d terminal-bench@2.0 -l 2 \
      --agent-import-path bench.harbor.seon_agent:SeonAgent \
      -m deepseek-flash

Container-Side Mode (when SEON_HARBOR_BUNDLE points at a Linux target/seon bundle):
    SEON_HARBOR_BUNDLE=/path/to/linux-seon-bundle \
    PYTHONPATH=. harbor run -d terminal-bench@2.0 \
      --agent-import-path bench.harbor.seon_agent:SeonAgent \
      -m deepseek-flash

Direct local driver smoke test (Python stdlib only):
    python3 bench/harbor/seon_agent.py --drive \
      --bin ./target/seon --repo . --seed-store ./runs/store --model-stub \
      --instruction "Return (+ 20 22)" --out /tmp/seon-harbor-out.json
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Optional

try:
    from harbor.agents.base import BaseAgent as _HarborBaseAgent
    from harbor.environments.base import BaseEnvironment
    from harbor.models.agent.context import AgentContext
except ImportError:
    class _HarborBaseAgent:  # type: ignore[no-redef]
        def __init__(self, logs_dir: Path, model_name: Optional[str] = None, **kwargs: Any) -> None:
            self.logs_dir = Path(logs_dir)
            self.model_name = model_name
            self.kwargs = kwargs

    BaseEnvironment = Any  # type: ignore[misc, assignment]
    AgentContext = Any  # type: ignore[misc, assignment]


LISTENING_RE = re.compile(r"SEON LISTENING https?://[^:\s]+:(\d+)")
FILE_MARKER = "---SEON-FILE---\n"


def _edn_str(s: str) -> str:
    """Encode a Python string as a valid Clojure/EDN string literal."""
    return json.dumps(s)


def _post_edn(base_url: str, edn_body: str, timeout: float = 35.0) -> str:
    req = urllib.request.Request(
        f"{base_url}/op",
        data=edn_body.encode("utf-8"),
        headers={"Content-Type": "application/edn"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="replace")


def _fetch_proxy_stats(model_base_url: Optional[str]) -> Optional[dict[str, Any]]:
    if not model_base_url:
        return None
    url = model_base_url.rstrip("/") + "/stats"
    try:
        with urllib.request.urlopen(url, timeout=3.0) as resp:
            if resp.status == 200:
                return json.loads(resp.read().decode("utf-8", errors="replace"))
    except Exception:
        return None
    return None


def _agent_settled_from_status_edn(status_edn: str, agent_kw: str = ":bench/harbor") -> tuple[bool, dict[str, Any]]:
    """Inspect `:agents [...]` in a `/op {:op :status}` EDN snapshot for `agent_kw`."""
    idx = status_edn.find(f":id {agent_kw}")
    if idx < 0:
        return False, {}
    end = status_edn.find("}", idx)
    chunk = status_edn[idx : (end + 1 if end > idx else idx + 500)]

    def _bool(key: str) -> bool:
        m = re.search(rf":{key}\s+(true|false)", chunk)
        return bool(m and m.group(1) == "true")

    def _int(key: str) -> int:
        m = re.search(rf":{key}\s+(\d+)", chunk)
        return int(m.group(1)) if m else 0

    m_status = re.search(r":status\s+(:[a-zA-Z0-9._/-]+)", chunk)
    status_kw = m_status.group(1) if m_status else ":unknown"
    info = {
        "evals": _int("evals"),
        "unread_count": _int("unread-count"),
        "awaiting_count": _int("awaiting-count"),
        "busy": _bool(r"busy\?"),
        "needs_turn": _bool(r"needs-turn\?"),
        "needs_test": _bool(r"needs-test\?"),
        "needs_merge": _bool(r"needs-merge\?"),
        "status": status_kw,
    }
    settled = (
        not info["busy"]
        and info["unread_count"] == 0
        and info["awaiting_count"] == 0
        and not info["needs_turn"]
        and not info["needs_test"]
        and not info["needs_merge"]
        and (status_kw == ":merged" or info["evals"] > 0)
    )
    return settled, info


def _build_env(bin_path: Path, repo_dir: Path, extra_env: Optional[dict[str, str]] = None) -> dict[str, str]:
    env = os.environ.copy()
    env["SEON_BINARY"] = "1"
    candidate_dirs = [bin_path.parent / "lib", repo_dir / "target" / "lib"]
    for env_key, base in (
        ("JOLT_LZ4_LIB", "liblz4"),
        ("JOLT_ZSTD_LIB", "libzstd"),
        ("JOLT_LMDB_LIB", "liblmdb"),
    ):
        if env.get(env_key):
            continue
        for d in candidate_dirs:
            for ext in (".so", ".dylib"):
                cand = d / f"{base}{ext}"
                if cand.is_file():
                    env[env_key] = str(cand.resolve())
                    break
            if env.get(env_key):
                break
    if extra_env:
        env.update(extra_env)
    return env


def _eval_json(base_url: str, branch_kw: str, code: str) -> Optional[dict[str, Any]]:
    """Run a 1-line JSON-returning Clojure eval against `base_url` and decode the JSON object."""
    edn_req = f"{{:op :eval :agent :repl/harbor-probe :branch {branch_kw} :code {_edn_str(code)}}}"
    try:
        raw = _post_edn(base_url, edn_req, timeout=12.0)
    except Exception:
        return None
    m = re.search(r":value\s+\"((?:[^\"\\]|\\.)*)\"", raw)
    if not m:
        return None
    try:
        val: Any = json.loads('"' + m.group(1) + '"')
        for _ in range(3):
            if isinstance(val, str):
                val = json.loads(val)
        return val if isinstance(val, dict) else None
    except Exception:
        return None


def _poll_call_result_json(base_url: str, call_uuid: str) -> Optional[dict[str, Any]]:
    """Read typed call status/result/error from Datahike via a 1-line JSON eval (zero prose scraping!)."""
    code = (
        "(let [c (datahike.api/pull (user/main-db) "
        "'[:seon.agent.call/status :seon.agent.call/result :seon.agent.call/error] "
        f"[:seon.agent.call/id #uuid \"{call_uuid}\"])] "
        "(clojure.data.json/write-str "
        "{:status (some-> (:seon.agent.call/status c) name) "
        ":result (:seon.agent.call/result c) "
        ":error (:seon.agent.call/error c)}))"
    )
    return _eval_json(base_url, ":branch/harbor-probe", code)


def _fetch_eval_telemetry(
    base_url: str,
    agent_kw: str = ":bench/harbor",
    branch_kw: str = ":branch/harbor",
    include_trace: bool = False,
) -> Optional[dict[str, Any]]:
    """Read exact token counts, KV-cache hits, and latest eval snippet/error read-only from `branch_kw`."""
    trace_expr = (
        ":trace (mapv (fn [e] {:seq (:idx e) "
        ":ms (:seon.agent.eval/ms e) "
        ":bytes (:seon.agent.eval/bytes e) "
        ":code (clip (:seon.agent.eval/code e) 140) "
        ":err (when (:seon.agent.eval/error e) (clip (:seon.agent.eval/error e) 160))}) es) "
        if include_trace
        else ""
    )
    code = (
        f"(let [db (or (try (datahike.api/branch-as-db (:store @(:conn seon.fork.calls/*agent-ctx*)) {branch_kw}) "
        "(catch Throwable _ nil)) (user/db)) "
        f"es (->> (datahike.api/datoms db :avet :seon.agent.eval/agent {agent_kw}) "
        "(sort-by (fn [dt] [(:tx dt) (:e dt)])) "
        "(map-indexed (fn [i dt] (assoc (datahike.api/pull db '[:seon.agent.eval/code :seon.agent.eval/error :seon.agent.eval/ms :seon.agent.eval/bytes "
        ":seon.agent.eval/prompt-tokens :seon.agent.eval/completion-tokens :seon.agent.eval/cache-hit-tokens] (:e dt)) :idx (inc i)))) vec) "
        "last-e (peek es) "
        "last-err (last (filter :seon.agent.eval/error es)) "
        "clip (fn [s n] (let [t (clojure.string/replace (str s) #\"\\s+\" \" \")] "
        "(if (> (count t) n) (str (subs t 0 n) \"…\") t)))] "
        "(clojure.data.json/write-str "
        "{:evals (count es) "
        ":prompt_tokens (reduce + 0 (keep :seon.agent.eval/prompt-tokens es)) "
        ":completion_tokens (reduce + 0 (keep :seon.agent.eval/completion-tokens es)) "
        ":cache_hit_tokens (reduce + 0 (keep :seon.agent.eval/cache-hit-tokens es)) "
        f"{trace_expr}"
        ":last_snippet (when last-e (str \"result/e\" (:idx last-e) \": \" (clip (:seon.agent.eval/code last-e) 110))) "
        ":last_error (when last-err (str \"result/e\" (:idx last-err) \" ERR: \" (clip (:seon.agent.eval/error last-err) 140)))}))"
    )
    return _eval_json(base_url, ":branch/harbor-probe", code)


def _publish_monitor_snapshot(
    monitor_url: Optional[str],
    *,
    run_uuid: str,
    title: str,
    status_kw: str,
    started_ms: int,
    trial: dict[str, Any],
    proxy_stats: Optional[dict[str, Any]] = None,
    finished_ms: Optional[int] = None,
) -> None:
    """Publish a live `:seon.bench.run/entity` snapshot to a standing Seon coordinator (best-effort)."""
    if not monitor_url or monitor_url.lower() in ("off", "none", "false"):
        return
    now_ms = int(time.time() * 1000)
    passed = 1 if status_kw == ":passed" else 0
    failed = 1 if status_kw == ":failed" else 0

    def _edn_val(v: Any) -> str:
        if v is None:
            return "nil"
        if isinstance(v, bool):
            return "true" if v else "false"
        if isinstance(v, (int, float)):
            return str(v)
        if isinstance(v, str):
            if v.startswith(":"):
                return v
            return _edn_str(v)
        return _edn_str(str(v))

    t_parts = [f":{k} {_edn_val(v)}" for k, v in trial.items() if v is not None]
    parts = [
        f":seon.bench.run/id #uuid \"{run_uuid}\"",
        f":seon.bench.run/title {_edn_str(title)}",
        f":seon.bench.run/status {status_kw}",
        f":seon.bench.run/started-ms {started_ms}",
        f":seon.bench.run/updated-ms {now_ms}",
        f":seon.bench.run/total 1",
        f":seon.bench.run/passed {passed}",
        f":seon.bench.run/failed {failed}",
        ":seon.bench.run/trials [{" + " ".join(t_parts) + "}]",
    ]
    if finished_ms is not None:
        parts.append(f":seon.bench.run/finished-ms {finished_ms}")
    if proxy_stats:
        ps_parts = [f":{k} {_edn_val(v)}" for k, v in proxy_stats.items() if v is not None]
        parts.append(":seon.bench.run/proxy-stats {" + " ".join(ps_parts) + "}")
    tx_edn = "{:op :transact :tx-data [{" + " ".join(parts) + "}] :reason [:harbor-bench #uuid \"" + run_uuid + "\"]}"
    try:
        _post_edn(monitor_url.rstrip("/"), tx_edn, timeout=3.0)
    except Exception:
        pass


class _ContainerBridgeServer:
    """Tiny stdlib HTTP bridge forwarding `/exec`, `/read`, `/write`, `/patch` to Harbor's `environment.exec`."""

    def __init__(self, environment: BaseEnvironment, loop: asyncio.AbstractEventLoop) -> None:
        self.environment = environment
        self.loop = loop
        self._server: Optional[ThreadingHTTPServer] = None
        self._thread: Optional[threading.Thread] = None
        self.port: int = 0
        self.exec_calls: int = 0

    def _run_exec(self, command: str, cwd: Optional[str] = None, timeout_sec: Optional[int] = 60) -> Any:
        self.exec_calls += 1
        fut = asyncio.run_coroutine_threadsafe(
            self.environment.exec(command=command, cwd=cwd, timeout_sec=timeout_sec),
            self.loop,
        )
        wait_s = (timeout_sec + 15) if timeout_sec else 120
        return fut.result(timeout=wait_s)

    def _read_file(self, path: str, start_line: Optional[int] = None, end_line: Optional[int] = None) -> dict[str, Any]:
        qpath = shlex.quote(path)
        cmd = (
            f"if [ ! -f {qpath} ]; then exit 44; fi; "
            f"wc -l < {qpath}; printf '{FILE_MARKER}'; cat {qpath}"
        )
        res = self._run_exec(cmd, timeout_sec=30)
        rc = getattr(res, "return_code", 1)
        out = getattr(res, "stdout", "") or ""
        err = getattr(res, "stderr", "") or ""
        if rc == 44:
            return {"ok": False, "error": "file-not-found", "message": f"File not found: {path}"}
        if rc != 0:
            return {"ok": False, "error": "read-failed", "exit": rc, "message": err or out}
        idx = out.find(FILE_MARKER)
        content = out[idx + len(FILE_MARKER) :] if idx >= 0 else out
        all_lines = content.splitlines(keepends=True)
        total_lines = len(all_lines)
        if start_line is not None or end_line is not None:
            s_idx = max(0, (int(start_line) - 1) if start_line is not None else 0)
            e_idx = min(total_lines, int(end_line)) if end_line is not None else total_lines
            content = "".join(all_lines[s_idx:e_idx])
        return {"ok": True, "lines": total_lines, "content": content}

    def _write_file(self, path: str, content: str) -> dict[str, Any]:
        raw = content.encode("utf-8")
        b64 = base64.b64encode(raw).decode("ascii")
        qpath = shlex.quote(path)
        cmd = (
            f"mkdir -p \"$(dirname {qpath})\" && "
            f"printf '%s' {shlex.quote(b64)} | base64 -d > {qpath}"
        )
        res = self._run_exec(cmd, timeout_sec=30)
        rc = getattr(res, "return_code", 1)
        if rc != 0:
            return {"ok": False, "error": "write-failed", "exit": rc, "message": getattr(res, "stderr", "") or ""}
        return {"ok": True, "bytes": len(raw)}

    def _patch_file(self, path: str, old: str, new: str, replace_all: bool = False) -> dict[str, Any]:
        rd = self._read_file(path)
        if not rd.get("ok"):
            return rd
        text = rd["content"]
        if not old:
            return {"ok": False, "error": "empty-old-string", "message": "old string cannot be empty"}
        count = text.count(old)
        if count == 0:
            return {"ok": False, "error": "not-found", "message": f"Exact match not found in {path}"}
        if count > 1 and not replace_all:
            return {
                "ok": False,
                "error": "ambiguous-match",
                "matches": count,
                "message": f"Matched {count} times in {path}; pass more surrounding lines or {{:all? true}}",
            }
        updated = text.replace(old, new) if replace_all else text.replace(old, new, 1)
        wr = self._write_file(path, updated)
        if not wr.get("ok"):
            return wr
        return {"ok": True, "replacements": count if replace_all else 1}

    def start(self) -> str:
        bridge = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
                pass

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length", "0") or "0")
                raw = self.rfile.read(length) if length > 0 else b"{}"
                try:
                    payload = json.loads(raw.decode("utf-8"))
                except Exception as exc:
                    self._send(400, {"ok": False, "error": "bad-json", "message": str(exc)})
                    return
                try:
                    if self.path == "/exec":
                        res = bridge._run_exec(
                            str(payload.get("command", "")),
                            cwd=payload.get("cwd"),
                            timeout_sec=int(payload.get("timeout_sec") or 60),
                        )
                        self._send(
                            200,
                            {
                                "exit": int(getattr(res, "return_code", -1)),
                                "out": getattr(res, "stdout", "") or "",
                                "err": getattr(res, "stderr", "") or "",
                            },
                        )
                    elif self.path == "/read":
                        self._send(
                            200,
                            bridge._read_file(
                                str(payload.get("path", "")),
                                start_line=payload.get("start_line"),
                                end_line=payload.get("end_line"),
                            ),
                        )
                    elif self.path == "/write":
                        self._send(
                            200,
                            bridge._write_file(str(payload.get("path", "")), str(payload.get("content", ""))),
                        )
                    elif self.path == "/patch":
                        self._send(
                            200,
                            bridge._patch_file(
                                str(payload.get("path", "")),
                                str(payload.get("old", "")),
                                str(payload.get("new", "")),
                                replace_all=bool(payload.get("replace_all", False)),
                            ),
                        )
                    else:
                        self._send(404, {"ok": False, "error": "unknown-endpoint", "path": self.path})
                except Exception as exc:
                    self._send(200, {"ok": False, "error": "bridge-exception", "message": str(exc)})

            def _send(self, code: int, obj: dict[str, Any]) -> None:
                body = json.dumps(obj).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = int(self._server.server_address[1])
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return f"http://127.0.0.1:{self.port}"

    def stop(self) -> None:
        if self._server is not None:
            try:
                self._server.shutdown()
                self._server.server_close()
            except Exception:
                pass
            self._server = None


def drive_seon_once(
    bin_path: Path,
    repo_dir: Path,
    instruction: str,
    *,
    model: Optional[str] = None,
    model_base_url: Optional[str] = None,
    model_stub: bool = False,
    seed_store: Optional[Path] = None,
    commit_git: bool = False,
    answer_schema: Optional[str] = None,
    exec_bridge_url: Optional[str] = None,
    container_mode: bool = False,
    monitor_url: Optional[str] = None,
    run_title: Optional[str] = None,
    trial_id: Optional[str] = None,
    timeout_s: float = 600.0,
    boot_timeout_s: float = 25.0,
) -> dict[str, Any]:
    """Boot an isolated headless `target/seon`, execute `instruction` (container-bridge, env-state,
    or typed-call `--answer-schema` mode), optionally stream live updates to `:9998`, and stop cleanly."""
    t0 = time.monotonic()
    started_ms = int(time.time() * 1000)
    run_uuid = str(uuid.uuid4())
    tid_kw = f":{trial_id}" if trial_id and not trial_id.startswith(":") else (trial_id or ":harbor-task")
    title = run_title or f"Harbor · {tid_kw.lstrip(':')}"

    work_dir = Path(tempfile.mkdtemp(prefix="seon-harbor-"))
    store_dir = work_dir / "store"
    cmd = [
        str(bin_path),
        "--port", "0",
        "--headless",
        "--store", str(store_dir),
        "--repo", str(repo_dir),
        "--commit-git", "true" if commit_git else "false",
        "--auto-switch-over", "false",
        "--max-os-procs", "2",
        "--max-test-workers", "2",
    ]
    if seed_store:
        cmd.extend(["--seed-store", str(seed_store)])
    if model_base_url:
        cmd.extend(["--model-base-url", model_base_url])
    if model:
        cmd.extend(["--model", model])
    if model_stub:
        cmd.append("--model-stub")

    extra_env = {"SEON_EXEC_BRIDGE_URL": exec_bridge_url} if exec_bridge_url else None
    proc = subprocess.Popen(
        cmd,
        cwd=str(repo_dir),
        env=_build_env(bin_path, repo_dir, extra_env=extra_env),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    logs: list[str] = []
    port_holder: dict[str, Optional[int]] = {"port": None}
    ready_ev = threading.Event()

    def _reader() -> None:
        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.rstrip("\r\n")
            logs.append(line)
            if len(logs) > 250:
                del logs[0]
            if port_holder["port"] is None:
                m = LISTENING_RE.search(line)
                if m:
                    port_holder["port"] = int(m.group(1))
                    ready_ev.set()
        ready_ev.set()

    reader_t = threading.Thread(target=_reader, daemon=True)
    reader_t.start()

    try:
        ready_ev.wait(timeout=boot_timeout_s)
        port = port_holder["port"]
        if port is None:
            return {
                "ok": False,
                "error": f"Seon did not listen within {boot_timeout_s}s",
                "elapsed_s": round(time.monotonic() - t0, 3),
                "logs_tail": logs[-30:],
            }
        base_url = f"http://127.0.0.1:{port}"
        boot_s = round(time.monotonic() - t0, 3)

        if answer_schema:
            goal = f"Answer the incoming call via (user/answer! call-id <value>) matching {answer_schema}."
        elif container_mode:
            goal = (
                "Complete the task inside the Linux container using (user/sh! cmd), "
                "(user/fs-read path), (user/fs-write path content), and (user/fs-patch path old new). "
                "Verify your solution thoroughly inside the container, then stop cleanly (do NOT call user/ready!)."
            )
        else:
            goal = "Complete the benchmark task accurately and concisely."

        cfg_edn = (
            " :config {:seon.agent/context-prefs {:mode :container} :seon.config/max-tool-turns 48}"
            if container_mode
            else ""
        )
        spawn_edn = (
            "{:op :spawn-agent :id :bench/harbor :branch :branch/harbor "
            f":role :worker :auto-merge? false{cfg_edn} :goal " + _edn_str(goal) + "}"
        )
        spawn_resp = _post_edn(base_url, spawn_edn)

        call_uuid: Optional[str] = None
        call_data: Optional[dict[str, Any]] = None
        msg_ok = False
        if answer_schema:
            schema_kw = answer_schema if answer_schema.startswith(":") else f":{answer_schema}"
            call_edn = (
                "{:op :create-call :caller :agent/root :callee :bench/harbor "
                f":result-schema {schema_kw} :prompt " + _edn_str(instruction) + "}"
            )
            call_resp = _post_edn(base_url, call_edn)
            msg_ok = ":ok? true" in call_resp
            m_uuid = re.search(r"#uuid\s+\"([0-9a-fA-F-]+)\"", call_resp)
            if m_uuid:
                call_uuid = m_uuid.group(1)
        else:
            msg_edn = (
                "{:op :send-message :from :user :to :bench/harbor :type :chat :body "
                + _edn_str(instruction) + "}"
            )
            msg_resp = _post_edn(base_url, msg_edn)
            msg_ok = ":ok? true" in msg_resp

        _publish_monitor_snapshot(
            monitor_url,
            run_uuid=run_uuid,
            title=title,
            status_kw=":running",
            started_ms=started_ms,
            trial={"id": tid_kw, "status": ":booted", "port": port, "evals": 0, "elapsed-ms": int(boot_s * 1000), "boot-ms": int(boot_s * 1000), "alive?": True},
        )

        deadline = time.monotonic() + timeout_s
        settled = False
        agent_info: dict[str, Any] = {}
        last_tel: dict[str, Any] = {}
        polls = 0
        last_pub = time.monotonic()
        while time.monotonic() < deadline:
            polls += 1
            if call_uuid:
                call_data = _poll_call_result_json(base_url, call_uuid)
                if call_data and call_data.get("status") in ("resolved", "failed", "cancelled"):
                    settled = call_data.get("status") == "resolved"
                    break
            else:
                st = _post_edn(base_url, "{:op :status}")
                settled, agent_info = _agent_settled_from_status_edn(st)
                if settled:
                    break
            if monitor_url and (time.monotonic() - last_pub) >= 2.0:
                last_pub = time.monotonic()
                tel = _fetch_eval_telemetry(base_url) or last_tel
                last_tel = tel
                p_stats = _fetch_proxy_stats(model_base_url) or {
                    "requests": int(tel.get("evals", 0)),
                    "in-flight": 1 if agent_info.get("busy") else 0,
                    "prompt-tokens": int(tel.get("prompt_tokens", 0)),
                    "completion-tokens": int(tel.get("completion_tokens", 0)),
                    "cache-hit-tokens": int(tel.get("cache_hit_tokens", 0)),
                }
                _publish_monitor_snapshot(
                    monitor_url,
                    run_uuid=run_uuid,
                    title=title,
                    status_kw=":running",
                    started_ms=started_ms,
                    trial={
                        "id": tid_kw,
                        "status": agent_info.get("status", ":runnable"),
                        "port": port,
                        "evals": int(agent_info.get("evals") or tel.get("evals") or 0),
                        "elapsed-ms": int((time.monotonic() - t0) * 1000),
                        "boot-ms": int(boot_s * 1000),
                        "alive?": True,
                        "last-snippet": tel.get("last_snippet"),
                        "last-error": tel.get("last_error"),
                    },
                    proxy_stats=p_stats,
                )
            time.sleep(0.25)

        final_tel = _fetch_eval_telemetry(base_url, include_trace=True) or last_tel
        proxy_stats = _fetch_proxy_stats(model_base_url)
        if not proxy_stats and final_tel:
            proxy_stats = {
                "requests": int(final_tel.get("evals", 0)),
                "in-flight": 0,
                "prompt-tokens": int(final_tel.get("prompt_tokens", 0)),
                "completion-tokens": int(final_tel.get("completion_tokens", 0)),
                "cache-hit-tokens": int(final_tel.get("cache_hit_tokens", 0)),
            }

        fin_ms = int(time.time() * 1000)
        _publish_monitor_snapshot(
            monitor_url,
            run_uuid=run_uuid,
            title=title,
            status_kw=":passed" if settled else ":failed",
            started_ms=started_ms,
            finished_ms=fin_ms,
            trial={
                "id": tid_kw,
                "status": ":passed" if settled else ":timeout",
                "port": port,
                "evals": int(agent_info.get("evals") or final_tel.get("evals") or 0),
                "elapsed-ms": int((time.monotonic() - t0) * 1000),
                "boot-ms": int(boot_s * 1000),
                "alive?": False,
                "last-snippet": final_tel.get("last_snippet"),
                "last-error": final_tel.get("last_error") or (None if settled else f"Timed out after {timeout_s}s"),
            },
            proxy_stats=proxy_stats,
        )

        try:
            _post_edn(base_url, "{:op :hand-over}", timeout=8.0)
        except Exception:
            pass

        out: dict[str, Any] = {
            "ok": settled,
            "mode": "typed-call" if answer_schema else ("container-bridge" if container_mode else "env-state"),
            "port": port,
            "boot_s": boot_s,
            "elapsed_s": round(time.monotonic() - t0, 3),
            "polls": polls,
            "agent": agent_info,
            "telemetry": final_tel,
            "spawn_ok": ":ok? true" in spawn_resp,
            "message_ok": msg_ok,
            "proxy_stats": proxy_stats,
            "logs_tail": logs[-30:],
        }
        if call_uuid:
            out["call_id"] = call_uuid
            out["answer_schema"] = answer_schema
            out["call"] = call_data
            if call_data and call_data.get("status") == "resolved":
                out["answer"] = call_data.get("result")
        return out
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=3.0)
            except subprocess.TimeoutExpired:
                proc.kill()
        shutil.rmtree(work_dir, ignore_errors=True)


class SeonAgent(_HarborBaseAgent):
    """Thin Harbor `BaseAgent` wrapper supporting BOTH Host-Bridge Mode (default — works on macOS/Linux
    Docker with any container image) and Container-Side Mode (when `SEON_HARBOR_BUNDLE` is set)."""

    @staticmethod
    def name() -> str:
        return "seon"

    def version(self) -> Optional[str]:
        return "0.2.0"

    def _repo_root(self) -> Path:
        return Path(__file__).resolve().parents[2]

    def _use_container_bundle(self) -> bool:
        return bool(os.environ.get("SEON_HARBOR_BUNDLE"))

    async def setup(self, environment: BaseEnvironment) -> None:
        if self._use_container_bundle():
            bundle_dir = Path(os.environ["SEON_HARBOR_BUNDLE"])
            bin_file = bundle_dir / "seon"
            if not bin_file.is_file():
                raise FileNotFoundError(
                    f"Compiled target/seon not found at {bin_file}. Build a Linux binary or unset SEON_HARBOR_BUNDLE for Host-Bridge Mode."
                )
            await environment.exec(command="mkdir -p /opt/seon/lib /logs/agent")
            await environment.upload_file(source_path=bin_file, target_path="/opt/seon/seon")
            await environment.upload_file(source_path=Path(__file__).resolve(), target_path="/opt/seon/seon_agent.py")
            lib_dir = bundle_dir / "lib"
            if lib_dir.is_dir():
                for lib in sorted(lib_dir.iterdir()):
                    if lib.is_file() and (lib.suffix in (".so", ".dylib") or ".so." in lib.name):
                        await environment.upload_file(source_path=lib, target_path=f"/opt/seon/lib/{lib.name}")
            await environment.exec(command="chmod +x /opt/seon/seon /opt/seon/seon_agent.py")
        else:
            bin_file = Path(os.environ.get("SEON_HOST_BIN", self._repo_root() / "target" / "seon"))
            if not bin_file.is_file():
                raise FileNotFoundError(f"Host target/seon binary not found at {bin_file}.")

    async def run(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        if self._use_container_bundle():
            await self._run_container_side(instruction, environment, context)
            return

        repo_root = self._repo_root()
        bin_file = Path(os.environ.get("SEON_HOST_BIN", repo_root / "target" / "seon"))
        seed_store_env = os.environ.get("SEON_HARBOR_SEED_STORE")
        seed_store = Path(seed_store_env) if seed_store_env else (repo_root / "runs" / "store")
        if not seed_store.is_dir():
            seed_store = None

        model_name = self.model_name
        if model_name and "/" in model_name:
            model_name = model_name.split("/", 1)[1]
        monitor_url = os.environ.get("SEON_MONITOR_URL", "http://127.0.0.1:9998")
        task_label = self.logs_dir.parent.name if self.logs_dir else "harbor-task"
        safe_tid = re.sub(r"[^a-zA-Z0-9._-]+", "-", task_label).strip("-") or "harbor-task"

        loop = asyncio.get_running_loop()
        bridge = _ContainerBridgeServer(environment, loop)
        bridge_url = bridge.start()
        try:
            data = await asyncio.to_thread(
                drive_seon_once,
                bin_file,
                repo_root,
                instruction,
                model=model_name,
                model_base_url=os.environ.get("SEON_AI_BASE_URL"),
                seed_store=seed_store,
                commit_git=False,
                answer_schema=os.environ.get("SEON_ANSWER_SCHEMA"),
                exec_bridge_url=bridge_url,
                container_mode=True,
                monitor_url=monitor_url,
                run_title=f"Harbor · {task_label}",
                trial_id=safe_tid,
                timeout_s=float(os.environ.get("SEON_HARBOR_TIMEOUT_S", "300")),
            )
            data["bridge_exec_calls"] = bridge.exec_calls
        finally:
            bridge.stop()

        self.logs_dir.mkdir(parents=True, exist_ok=True)
        (self.logs_dir / "seon-result.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        if data.get("logs_tail"):
            (self.logs_dir / "seon.log").write_text("\n".join(data["logs_tail"]) + "\n", encoding="utf-8")
        self.populate_context_post_run(context, data=data)

    async def _run_container_side(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        env: dict[str, str] = {}
        for k in ("DEEPSEEK_API_KEY", "OPENAI_API_KEY", "SEON_AI_BASE_URL", "SEON_AI_MODEL", "SEON_ANSWER_SCHEMA"):
            if os.environ.get(k):
                env[k] = os.environ[k]
        parts = [
            "python3", "/opt/seon/seon_agent.py", "--drive",
            "--bin", "/opt/seon/seon",
            "--repo", "/app",
            "--instruction", instruction,
            "--out", "/logs/agent/seon-result.json",
        ]
        if self.model_name:
            parts.extend(["--model", self.model_name])
        if os.environ.get("SEON_AI_BASE_URL"):
            parts.extend(["--model-base-url", os.environ["SEON_AI_BASE_URL"]])
        if os.environ.get("SEON_ANSWER_SCHEMA"):
            parts.extend(["--answer-schema", os.environ["SEON_ANSWER_SCHEMA"]])
        cmd = " ".join(shlex.quote(p) for p in parts) + " 2>&1 | tee /logs/agent/seon.log"
        await environment.exec(command=cmd, env=env)
        self.populate_context_post_run(context)

    def populate_context_post_run(self, context: AgentContext, data: Optional[dict[str, Any]] = None) -> None:
        if data is None:
            result_file = Path(self.logs_dir) / "seon-result.json"
            if not result_file.is_file():
                return
            data = json.loads(result_file.read_text(encoding="utf-8"))
        pstats = data.get("proxy_stats") or {}
        if hasattr(context, "n_input_tokens") and pstats.get("prompt-tokens") is not None:
            context.n_input_tokens = int(pstats["prompt-tokens"])
        if hasattr(context, "n_output_tokens") and pstats.get("completion-tokens") is not None:
            context.n_output_tokens = int(pstats["completion-tokens"])
        if hasattr(context, "n_cache_tokens") and pstats.get("cache-hit-tokens") is not None:
            context.n_cache_tokens = int(pstats["cache-hit-tokens"])
        if hasattr(context, "metadata"):
            meta = {**(context.metadata or {}), "seon": data}
            if "answer" in data:
                meta["answer"] = data["answer"]
            context.metadata = meta


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Seon Harbor driver")
    parser.add_argument("--drive", action="store_true", help="Boot isolated target/seon and run one instruction")
    parser.add_argument("--bin", default="/opt/seon/seon", help="Path to compiled target/seon binary")
    parser.add_argument("--repo", default="/app", help="Target working tree / repo path")
    parser.add_argument("--instruction", required=True, help="Benchmark task prompt")
    parser.add_argument("--model", default=None)
    parser.add_argument("--model-base-url", default=None)
    parser.add_argument("--model-stub", action="store_true")
    parser.add_argument("--seed-store", default=None)
    parser.add_argument("--commit-git", action="store_true")
    parser.add_argument("--answer-schema", default=None, help="Optional Malli schema kw for typed call! submission")
    parser.add_argument("--exec-bridge-url", default=None, help="Optional container exec bridge URL")
    parser.add_argument("--container-mode", action="store_true", help="Enable container REPL prompt guidance")
    parser.add_argument("--monitor-url", default=None, help="Optional standing Seon URL for live UI monitoring")
    parser.add_argument("--timeout", type=float, default=600.0)
    parser.add_argument("--out", default="/logs/agent/seon-result.json")
    args = parser.parse_args(argv)
    res = drive_seon_once(
        Path(args.bin),
        Path(args.repo),
        args.instruction,
        model=args.model,
        model_base_url=args.model_base_url,
        model_stub=args.model_stub,
        seed_store=Path(args.seed_store) if args.seed_store else None,
        commit_git=args.commit_git,
        answer_schema=args.answer_schema,
        exec_bridge_url=args.exec_bridge_url,
        container_mode=args.container_mode,
        monitor_url=args.monitor_url,
        timeout_s=args.timeout,
    )
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(res, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(res))
    return 0 if res.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
