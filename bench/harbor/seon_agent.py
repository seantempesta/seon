#!/usr/bin/env python3
"""Thin Harbor agent adapter & zero-dependency container driver for compiled `target/seon`.

Host usage (with Harbor installed):
    harbor run -d terminal-bench@2.0 \
      --agent-import-path bench.harbor.seon_agent:SeonAgent \
      -m deepseek/deepseek-chat

Direct local / container driver smoke test (Python stdlib only):
    python3 bench/harbor/seon_agent.py --drive \
      --bin ./target/seon --repo . --model-stub \
      --instruction "Return (+ 20 22)" --out /tmp/seon-harbor-out.json
"""

from __future__ import annotations

import argparse
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


def _build_env(bin_path: Path, repo_dir: Path) -> dict[str, str]:
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
    return env


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
    edn_req = "{:op :eval :agent :repl/harbor-probe :branch :branch/harbor-probe :code " + _edn_str(code) + "}"
    raw = _post_edn(base_url, edn_req)
    m = re.search(r":value\s+\"((?:[^\"\\]|\\.)*)\"", raw)
    if not m:
        return None
    try:
        inner = json.loads('"' + m.group(1) + '"')
        return json.loads(inner)
    except Exception:
        return None


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
    timeout_s: float = 600.0,
    boot_timeout_s: float = 25.0,
) -> dict[str, Any]:
    """Boot an isolated headless `target/seon`, execute `instruction` (either environment-state mode
    or typed-call `--answer-schema` mode backed by Malli validation + `:protocol-nudge`), and stop cleanly."""
    t0 = time.monotonic()
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
    ]
    if seed_store:
        cmd.extend(["--seed-store", str(seed_store)])
    if model_base_url:
        cmd.extend(["--model-base-url", model_base_url])
    if model:
        cmd.extend(["--model", model])
    if model_stub:
        cmd.append("--model-stub")

    proc = subprocess.Popen(
        cmd,
        cwd=str(repo_dir),
        env=_build_env(bin_path, repo_dir),
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

        goal = (
            f"Answer the incoming call via (user/answer! call-id <value>) matching {answer_schema}."
            if answer_schema
            else "Complete the benchmark task accurately and concisely."
        )
        spawn_edn = (
            "{:op :spawn-agent :id :bench/harbor :branch :branch/harbor "
            ":role :worker :goal " + _edn_str(goal) + "}"
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

        deadline = time.monotonic() + timeout_s
        settled = False
        agent_info: dict[str, Any] = {}
        polls = 0
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
            time.sleep(0.2)

        proxy_stats = _fetch_proxy_stats(model_base_url)
        try:
            _post_edn(base_url, "{:op :hand-over}", timeout=8.0)
        except Exception:
            pass

        out: dict[str, Any] = {
            "ok": settled,
            "mode": "typed-call" if answer_schema else "env-state",
            "port": port,
            "boot_s": boot_s,
            "elapsed_s": round(time.monotonic() - t0, 3),
            "polls": polls,
            "agent": agent_info,
            "spawn_ok": ":ok? true" in spawn_resp,
            "message_ok": msg_ok,
            "proxy_stats": proxy_stats,
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
    """Thin Harbor `BaseAgent` wrapper that installs & runs compiled `target/seon` inside the task container."""

    @staticmethod
    def name() -> str:
        return "seon"

    def version(self) -> Optional[str]:
        return "0.1.0"

    async def setup(self, environment: BaseEnvironment) -> None:
        bundle_dir = Path(os.environ.get("SEON_HARBOR_BUNDLE", Path(__file__).resolve().parents[2] / "target"))
        bin_file = bundle_dir / "seon"
        if not bin_file.is_file():
            raise FileNotFoundError(
                f"Compiled target/seon not found at {bin_file}. Build a Linux binary for Harbor containers or set SEON_HARBOR_BUNDLE."
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

    async def run(
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

    def populate_context_post_run(self, context: AgentContext) -> None:
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
    parser = argparse.ArgumentParser(description="Seon Harbor container driver")
    parser.add_argument("--drive", action="store_true", help="Boot isolated target/seon and run one instruction")
    parser.add_argument("--bin", default="/opt/seon/seon", help="Path to compiled target/seon binary")
    parser.add_argument("--repo", default="/app", help="Target working tree / repo path")
    parser.add_argument("--instruction", required=True, help="Benchmark task prompt")
    parser.add_argument("--model", default=None)
    parser.add_argument("--model-base-url", default=None)
    parser.add_argument("--model-stub", action="store_true")
    parser.add_argument("--seed-store", default=None)
    parser.add_argument("--commit-git", action="store_true")
    parser.add_argument("--answer-schema", default=None, help="Optional Malli schema kw (e.g. :seon.bench/typed-answer) for typed call! submission")
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
        timeout_s=args.timeout,
    )
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(res, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(res))
    return 0 if res.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
