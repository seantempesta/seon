"""Local DiffusionGemma worker — the RunPod wire contract on MLX.

Speaks the same protocol as the RunPod serverless endpoint that
seon.ai.diffusiongemma targets, so seon points at it by setting
SEON_DG_ENDPOINT=http://127.0.0.1:17860 (no other seon change):

    POST /run            {"input": {mode, prompt, max_new_tokens, ...}}
                         -> {"id": "...", "status": "IN_QUEUE"}
    GET  /status/{id}    -> {"id": ..., "status": "COMPLETED", "output": {...}}
    GET  /health         -> liveness + model state

Output fields mirror gpu_worker.py mode=generate: text, prompt_tokens,
completion_tokens, gen_s, tok_per_s, tokens_per_forward, worker_sha —
plus backend="mlx". Failures are IN-BAND (`gen_error` on a COMPLETED
job), matching the GPU worker; the HTTP layer never 500s for a
generation failure.

Run:  bin/seon start dg-worker   (or: python -m seon_diffusion.worker [--port 17860])
"""

import argparse
import hashlib
import json
import os
import queue
import threading
import time
import traceback
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import config


def _worker_sha():
    """Content hash of ALL steering source in this package (model, loop,
    oracle clients, repair — everything that can change a result), the
    stale-measurement guard: proves which code produced a result."""
    h = hashlib.sha256()
    d = os.path.dirname(os.path.abspath(__file__))
    for name in sorted(f for f in os.listdir(d) if f.endswith(".py")):
        with open(os.path.join(d, name), "rb") as fh:
            h.update(fh.read())
    return h.hexdigest()[:12]


WORKER_SHA = _worker_sha()

_CACHE = {}          # warm model + tokenizer, loaded once
_JOBS = {}           # job id -> {"status": ..., "output": ...}
_STATE = {"last_activity": None, "unloads": 0, "gens": 0}  # for idle-unload + status

# MLX streams are thread-bound — ALL MLX work (load + generate + unload) runs
# on this one executor thread; HTTP handler threads only enqueue and read.
_QUEUE = queue.Queue()

# Sentinel job the watchdog enqueues so the UNLOAD also runs on the executor
# thread (dropping MLX arrays off-thread would hit the same Stream(gpu) error).
_UNLOAD = ("__unload__", None)


def _executor():
    while True:
        jid, payload = _QUEUE.get()
        try:
            if jid == "__unload__":
                _do_unload()
            else:
                _STATE["last_activity"] = time.time()   # reset idle clock on real work
                _JOBS[jid]["status"] = "IN_PROGRESS"
                output = diffgemma(payload)
                _JOBS[jid].update({"status": "COMPLETED", "output": output})
                _STATE["last_activity"] = time.time()   # again: cover long gens
        finally:
            _QUEUE.task_done()


def _do_unload():
    """Drop the model + Metal cache so an idle worker returns to a small RSS.
    Runs ON the executor thread. Next job reloads (mmap'd 8-bit weights, fast)."""
    import gc
    import mlx.core as mx
    if "model" in _CACHE:
        _CACHE.clear()
        gc.collect()
        try:
            mx.clear_cache()
        except Exception:
            pass
        _STATE["unloads"] += 1
        print(f"[idle-unload] model released after inactivity "
              f"(unload #{_STATE['unloads']})", flush=True)


def _idle_watchdog(idle_timeout):
    """Enqueue an unload once the model has sat idle past `idle_timeout` s.
    Kept simple: poll every 30s; only unload when a model is actually loaded
    and no jobs are queued/running."""
    import mlx.core as mx  # noqa: F401  (import here so import-time is cheap)
    while True:
        time.sleep(30)
        la = _STATE["last_activity"]
        if ("model" in _CACHE and la is not None
                and (time.time() - la) > idle_timeout
                and _QUEUE.unfinished_tasks == 0):
            _QUEUE.put(_UNLOAD)


def _load():
    if "model" not in _CACHE:
        from transformers import AutoTokenizer
        from .model import load_model
        snap = config.model_snapshot()
        t0 = time.time()
        _CACHE["tok"] = AutoTokenizer.from_pretrained(snap)
        _CACHE["model"] = load_model(snap)
        _CACHE["load_s"] = round(time.time() - t0, 1)
    return _CACHE["tok"], _CACHE["model"]


def _info(mode):
    import mlx.core as mx
    return {
        "mode": mode,
        "worker_sha": WORKER_SHA,
        "backend": "mlx",
        "mlx": mx.__version__,
        "device": mx.device_info().get("device_name"),
    }


def diffgemma(payload):
    """Handle one job payload — the same entry contract as gpu_worker.diffgemma."""
    mode = payload.get("mode", "generate")
    info = _info(mode)

    if mode == "probe":
        try:
            snap = config.model_snapshot()
            info["config_ok"] = True
            info["model_type"] = "diffusion_gemma"
            info["snapshot"] = os.path.basename(snap)
        except Exception as e:
            info["config_ok"] = False
            info["config_err"] = f"{type(e).__name__}: {e}"[:200]
        return info

    if mode == "guided":
        return _guided(payload, info)

    if mode in ("fill", "rank", "step"):
        return _cursor(mode, payload, info)

    if mode != "generate":
        info["gen_error"] = (f"mode {mode!r} not supported by the MLX worker "
                             "(generate/probe/guided/fill/rank/step)")
        return info

    try:
        from .generate import generate, GenConfig
        tok, model = _load()
        info["load_s"] = _CACHE["load_s"]

        enc = tok.apply_chat_template(
            [{"role": "user", "content": payload["prompt"]}],
            tokenize=True, add_generation_prompt=True)
        ids = enc["input_ids"] if hasattr(enc, "keys") else enc
        if ids and isinstance(ids[0], list):
            ids = ids[0]

        gen = GenConfig(
            max_new_tokens=int(payload.get("max_new_tokens", 256)),
            max_denoising_steps=int(payload.get("max_denoising_steps", 48)),
            entropy_bound=float(payload.get("entropy_bound", 0.1)),
            t_min=float(payload.get("t_min", 0.4)),
            t_max=float(payload.get("t_max", 0.8)),
            stability_threshold=int(payload.get("stability_threshold", 1)),
            confidence_threshold=float(payload.get("confidence_threshold", 0.005)),
        )
        r = generate(model, tok, ids, gen)
        _STATE["gens"] += 1
        info.update({
            "text": r["text"],
            "prompt_tokens": len(ids),
            "completion_tokens": r["num_tokens"],
            "gen_s": round(r["generate_s"], 3),
            "tok_per_s": round(r["tok_per_s"], 1),
            "tokens_per_forward": [round(r["tokens_per_forward"], 2)],
        })
    except Exception as e:
        info["gen_error"] = f"{type(e).__name__}: {e}"[:300]
        info["trace_err"] = traceback.format_exc()[-1200:]
    return info


_ORACLE = {}         # persistent bb oracle, spawned once per worker process


def _guided(payload, info):
    """One guided verified code-buffer build: fresh EvalSession per job (torn
    down on any exit), persistent bb oracle, failures IN-BAND as gen_error.
    Perf fields in tokens/second."""
    from .control import generate_guided
    from .generate import GenConfig
    from .oracle import EvalSession, Oracle
    try:
        tok, model = _load()
        info["load_s"] = _CACHE["load_s"]
        if "o" not in _ORACLE:
            _ORACLE["o"] = Oracle()
        enc = tok.apply_chat_template(
            [{"role": "user", "content": payload["prompt"]}],
            tokenize=True, add_generation_prompt=True)
        ids = enc["input_ids"] if hasattr(enc, "keys") else enc
        if ids and isinstance(ids[0], list):
            ids = ids[0]
        gen = GenConfig(
            entropy_bound=float(payload.get("entropy_bound", 0.5)),
            max_denoising_steps=int(payload.get("max_denoising_steps", 48)),
            seed=int(payload["seed"]) if payload.get("seed") is not None else None,
        )
        prelude = payload.get("prelude") or None
        ses = EvalSession()
        try:
            if prelude:
                ses.eval(prelude)
            r = generate_guided(
                model, tok, ids, _ORACLE["o"], eval_session=ses, gen=gen,
                phase=payload.get("phase") or None,
                hints=bool(payload.get("hints", True)),
                repair=bool(payload.get("repair", True)),
                checks=payload.get("checks") or None,
                prelude=prelude,
                max_rounds=int(payload.get("max_rounds", 8)),
                max_attempts=int(payload.get("max_attempts", 3)))
        finally:
            ses.close()
        _STATE["gens"] += 1
        info.update({
            "text": r["text"],
            "done": r["done"],
            "attempts": r["attempts"],
            "rounds": r["rounds"],
            "locked_forms": r["locked_forms"],
            "repairs": r["repairs"],
            "checks_passed": r["checks_passed"],
            "decoder_forwards": r["decoder_forwards"],
            "prompt_tokens": len(ids),
            "completion_tokens": len(tok(r["text"], add_special_tokens=False)["input_ids"]) if r["text"] else 0,
            "gen_s": round(r["generate_s"], 3),
            "tok_per_s": round(r["tok_per_s"], 1),
            "events": r["events"][:40],
        })
    except Exception as e:
        info["gen_error"] = f"{type(e).__name__}: {e}"[:300]
        info["trace_err"] = traceback.format_exc()[-1200:]
    return info


def _cursor(mode, payload, info):
    """Typeahead wire modes (typeahead-design.md P2), same in-band-error
    contract, perf fields in tokens/second:

      fill  {prompt, segments:[["clamp",txt]|["free",n]], candidates?, seed?}
            → holes + per-hole worst-token confidence + trims + CAL probes
      rank  {prompt, prefix, candidates, suffix, null_prompt?, seed?}
            → calibrated ranked list
      step  {prompt (the context render), committed?, draft?, offers?,
             policy?, null_render?, seed?}
            → {transition, arm, new_draft, locked, glyph, posteriors,
               readouts, hints, events}

    step is STATELESS per call: the driver re-encodes the render every
    step (encoder prefill measured ~5ms/4k tokens — free next to a
    ~114ms forward); no session or KV is retained between calls in P2.
    Locking is parse-gated (bb oracle); the eval-proven lock is the
    guided-loop path."""
    from .cursor import CursorDriver, Policy
    from .oracle import Oracle
    try:
        tok, model = _load()
        info["load_s"] = _CACHE["load_s"]
        if "o" not in _ORACLE:
            _ORACLE["o"] = Oracle()
        if "cursor" not in _CACHE:
            _CACHE["cursor"] = CursorDriver(model, tok, _ORACLE["o"])
        drv = _CACHE["cursor"]
        drv.policy = Policy(**(payload.get("policy") or {}))
        seed = int(payload["seed"]) if payload.get("seed") is not None else None
        if mode == "fill":
            r = drv.fill(payload["prompt"], payload["segments"],
                         candidates=payload.get("candidates"), seed=seed)
        elif mode == "rank":
            r = drv.rank(payload["prompt"], payload["prefix"],
                         payload["candidates"], payload["suffix"],
                         null_prompt=payload.get("null_prompt"), seed=seed)
        else:
            r = drv.step(payload["prompt"],
                         committed=payload.get("committed", ""),
                         draft=payload.get("draft", ""),
                         offers=payload.get("offers"),
                         null_render=payload.get("null_render"), seed=seed)
            r["events"] = r["events"][:40]
        _STATE["gens"] += 1
        info.update(r)
    except Exception as e:
        info["gen_error"] = f"{type(e).__name__}: {e}"[:300]
        info["trace_err"] = traceback.format_exc()[-1200:]
    return info


def _submit_job(jid, payload):
    _QUEUE.put((jid, payload))


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):  # quiet request lines; errors still surface
        pass

    def do_GET(self):
        if self.path == "/health":
            la = _STATE["last_activity"]
            self._send(200, {"ok": True, "worker_sha": WORKER_SHA,
                             "model_loaded": "model" in _CACHE,
                             "idle_s": round(time.time() - la, 1) if la else None,
                             "idle_timeout_s": _STATE.get("idle_timeout"),
                             "gens": _STATE["gens"], "unloads": _STATE["unloads"],
                             "jobs": len(_JOBS)})
        elif self.path.startswith("/status/"):
            jid = self.path.split("/status/", 1)[1]
            job = _JOBS.get(jid)
            if job is None:
                self._send(404, {"error": f"unknown job {jid}"})
            else:
                self._send(200, {"id": jid, **job})
        else:
            self._send(404, {"error": f"unknown path {self.path}"})

    def do_POST(self):
        if self.path not in ("/run", "/runsync"):
            self._send(404, {"error": f"unknown path {self.path}"})
            return
        try:
            n = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(n) or b"{}")
            payload = body.get("input") or {}
        except Exception as e:
            self._send(400, {"error": f"bad request body: {e}"})
            return
        jid = uuid.uuid4().hex[:16]
        _JOBS[jid] = {"status": "IN_QUEUE"}
        _submit_job(jid, payload)
        if self.path == "/runsync":
            while _JOBS[jid]["status"] != "COMPLETED":
                time.sleep(0.1)
            self._send(200, {"id": jid, **_JOBS[jid]})
        else:
            self._send(200, {"id": jid, "status": "IN_QUEUE"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=17860)
    ap.add_argument("--preload", action="store_true",
                    help="load the model at startup instead of on first job")
    ap.add_argument("--idle-timeout", type=int,
                    default=int(os.environ.get("DG_IDLE_TIMEOUT", "900")),
                    help="release the model after N seconds idle (0 = never). "
                         "Default 900 (15 min) or $DG_IDLE_TIMEOUT.")
    args = ap.parse_args()
    _STATE["idle_timeout"] = args.idle_timeout
    threading.Thread(target=_executor, daemon=True).start()
    if args.idle_timeout > 0:
        threading.Thread(target=_idle_watchdog, args=(args.idle_timeout,),
                         daemon=True).start()
    if args.preload:
        # preload ON the executor thread (MLX streams are thread-bound)
        jid = "preload"
        _JOBS[jid] = {"status": "IN_QUEUE"}
        _submit_job(jid, {"mode": "probe"})
        while _JOBS[jid]["status"] != "COMPLETED":
            time.sleep(0.1)
        print("probe ok; model loads on first generate", flush=True)
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    idle = f"{args.idle_timeout}s idle-unload" if args.idle_timeout else "no idle-unload"
    print(f"seon_diffusion worker on http://127.0.0.1:{args.port}  sha={WORKER_SHA}  ({idle})",
          flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
