"""EmbeddingGemma 2 text-only embedding server (MLX). One command:
  ~/src/embed-models/embeddinggemma-2/.venv/bin/python -I script/embed/server.py ~/src/embed-models/embeddinggemma-2/weights/eg2-text-bf16 [--port 8701]
POST /v1/embed  {"texts": [...], "task": "code"|"document"|"query"|"classification"|"clustering"|"similarity"|"qa"|"fact"|null,
                 "dim": 768|512|256|128, "encoding": "list"|"f32"|"f16", "max_tokens": 8192}
  -> {"model", "dim", "n", "input_tokens", "ms", "encoding", "vectors"}; f32/f16 = base64 of little-endian row-major [n, dim].
Vectors are L2-normalised (re-normalised after Matryoshka truncation). `task` prepends the model's prefix; a document with a
title: send `title: T | text: C` with task null.  GET /healthz (503 with ok false once the GPU worker has stopped; MLX memory
figures), /v1/models. MLX's Metal buffer cache is capped (--cache-limit-mb); a failed round is logged to stderr and answered 500.
Concurrent requests are merged: one GPU worker thread drains the queue (taking every request queued while the GPU was busy), embeds every
pending text in length-bucketed batches and answers each request with its rows."""
import sys, json, time, base64, queue, threading, argparse
from concurrent.futures import Future
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))

ap = argparse.ArgumentParser(); ap.add_argument("weights"); ap.add_argument("--port", type=int, default=8701); ap.add_argument("--host", default="127.0.0.1")
ap.add_argument("--budget", type=int, default=16384, help="padded tokens per GPU batch"); ap.add_argument("--linger-ms", type=float, default=0.0, help="extra wait for more requests; 0 = merge whatever queued while the GPU was busy")
ap.add_argument("--compile", action="store_true", help="mx.compile per (batch, length) shape: no end-to-end gain measured, 10-400 ms per new shape")
ap.add_argument("--max-pending-tokens", type=int, default=262144, help="tokens merged into one worker round")
ap.add_argument("--cache-limit-mb", type=int, default=1024, help="MLX Metal buffer cache cap; uncapped it grows with every batch shape (84 GB peak measured)")
a = ap.parse_args(); Q = queue.Queue(); READY = threading.Event(); STATE = {}

def worker():  # owns MLX: load, compile and every evaluation happen on this thread
    from gemma_text import Encoder, PREFIX, bound_memory
    bound_memory(a.cache_limit_mb * 1024 * 1024)
    t = time.perf_counter(); enc = Encoder.load(a.weights, compiled=a.compile, token_budget=a.budget)
    enc.embed(["warm up"] * 4); STATE.update(enc=enc, prefix=PREFIX, load_s=time.perf_counter() - t); READY.set()
    while True:
        jobs = [Q.get()]; ntok = jobs[0][0]["ntok"]; deadline = time.perf_counter() + a.linger_ms / 1000
        while ntok < a.max_pending_tokens:
            try: j = Q.get(timeout=max(0.0, deadline - time.perf_counter()))
            except queue.Empty: break
            jobs.append(j); ntok += j[0]["ntok"]
        for dim in {j[0]["dim"] for j in jobs}:  # one merged embed per requested dim
            group = [j for j in jobs if j[0]["dim"] == dim]
            try:
                ids = [x for r, _ in group for x in r["ids"]]; V = enc.embed_ids(ids, dim); o = 0
                for r, fut in group: fut.set_result(V[o:o + len(r["ids"])]); o += len(r["ids"])
            except Exception as e:
                STATE["errors"] = STATE.get("errors", 0) + 1; STATE["last_error"] = repr(e)
                print(f"embed round failed: {e!r}", file=sys.stderr, flush=True)
                for _, fut in group: fut.set_exception(e)
        STATE["rounds"] = STATE.get("rounds", 0) + 1

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def reply(self, code, obj):
        b = json.dumps(obj).encode(); self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def log_message(self, *x): pass
    def do_GET(self):
        enc = STATE.get("enc")
        if self.path == "/healthz":
            from gemma_text import memory
            alive = WORKER.is_alive()
            return self.reply(200 if alive else 503, {"ok": READY.is_set() and alive, "worker": alive, "model": enc and enc.name,
                                                      "load_s": STATE.get("load_s"), "rounds": STATE.get("rounds", 0),
                                                      "errors": STATE.get("errors", 0), "last_error": STATE.get("last_error"),
                                                      "memory": memory(), "cache_limit_mb": a.cache_limit_mb})
        if self.path == "/v1/models": return self.reply(200, {"models": [{"id": enc and enc.name, "dim": 768, "mrl": [128, 256, 512, 768], "max_tokens": 8192, "tasks": sorted(STATE.get("prefix", {}))}]})
        self.reply(404, {"error": "not found"})
    def do_POST(self):
        if self.path != "/v1/embed": return self.reply(404, {"error": "not found"})
        t0 = time.perf_counter(); READY.wait()
        try:
            r = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            texts, task, dim = r["texts"], r.get("task"), int(r.get("dim") or 768); enc = STATE["enc"]
            if task and task not in STATE["prefix"]: return self.reply(400, {"error": f"unknown task {task}", "tasks": sorted(STATE["prefix"])})
            if dim not in (128, 256, 512, 768) and not 1 <= dim <= 768: return self.reply(400, {"error": "dim must be 1..768"})
            p = STATE["prefix"][task] if task else ""
            ids = enc.tokenize([p + x for x in texts], int(r.get("max_tokens", 8192)))
        except (KeyError, ValueError, TypeError) as e:
            return self.reply(400, {"error": f"bad request: {e}"})
        if not WORKER.is_alive(): return self.reply(503, {"error": "the embedding worker has stopped"})
        fut = Future(); Q.put(({"ids": ids, "dim": dim, "ntok": sum(map(len, ids))}, fut))
        try: V = fut.result()
        except Exception as e: return self.reply(500, {"error": f"embedding failed: {e!r}"})
        enc_ = r.get("encoding", "list")
        vec = (V.tolist() if enc_ == "list" else
               base64.b64encode(V.astype("<f2" if enc_ == "f16" else "<f4").tobytes()).decode())
        self.reply(200, {"model": STATE["enc"].name, "dim": dim, "n": len(texts), "input_tokens": sum(map(len, ids)),
                         "ms": round((time.perf_counter() - t0) * 1000, 2), "encoding": enc_, "vectors": vec})

WORKER = threading.Thread(target=worker, daemon=True); WORKER.start()
ThreadingHTTPServer.request_queue_size = 1024; ThreadingHTTPServer.daemon_threads = True
srv = ThreadingHTTPServer((a.host, a.port), H)
print(f"embedding server on http://{a.host}:{a.port} ({Path(a.weights).name})", flush=True); srv.serve_forever()
