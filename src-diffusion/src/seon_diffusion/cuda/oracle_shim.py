# FROZEN (2026-07-05): quarantined RunPod CUDA artifact — superseded by
# seon_diffusion.control + the MLX worker. Revive by need; see cuda/__init__.py.
"""oracle_shim.py — the Python `Oracle` the diffusion worker calls between
denoise steps to validate a partial canvas LOCALLY (co-located parse tier).

Design: docs/prds/diffusion-dynamic-context/research/
        colocated-oracle-package-design-2026-06-28.md (§4 Python<->CLJS API).

The win: over the internet a per-checkpoint parse validate is a ~100ms
round-trip; spawned ONCE and co-located on the GPU worker it is a
~0.1ms local pipe call. This class spawns the parse-validator server ONCE
(at worker warm-up, alongside the model load), reuses it for every
checkpoint, and terminates it on shutdown.

RUNTIME-AGNOSTIC by construction: the wire is one JSON object per line
({"op","code",...} in, {"forms","tier","errors":[{"error-kind","span",
"source"}]} out). The same shim drives EITHER the babashka parse server
(bin/oracle-server) OR the Node :worker-validator bundle — only the spawn
argv changes. That is the design's key property: swap bb<->Node without
touching this file.

  bb (parse tier, today):   ["bb", "<repo>/bin/oracle-server"]
  Node (faithful eval tier): ["node", "/opt/seon/oracle.js", "--serve"]
"""

import json
import subprocess
import time


class Oracle:
    """A spawn-ONCE persistent line-server client. RUNTIME-AGNOSTIC: the same
    class drives EITHER the bb parse server (`bin/oracle-server`, synchronous on
    first call, no readiness signal) OR the Node faithful-eval server
    (`worker_eval.cljs --serve`, which writes `"ready\\n"` to STDERR after the
    ~276ms self-host bootstrap-cache load — `worker_eval.cljs:381`).

    `argv` is the runtime; `ready_sentinel` (default None) is the stderr line a
    runtime emits when it is ready to accept requests. For the Node eval server
    pass `ready_sentinel="ready"` and call `ready_after()` (or just `.call()`,
    which auto-waits) so the FIRST eval lands on the warm server, not the
    JIT-cold async-init path. The bb parse server emits nothing (it is
    synchronous on first call) — leave `ready_sentinel=None`.
    """

    def __init__(self, argv, ready_sentinel=None):
        """Spawn the line-server ONCE. `argv` is the runtime to run, e.g.
        ["bb", "/path/to/bin/oracle-server"] (bb parse tier) or
        ["node", "/opt/seon/oracle-eval.js", "--serve"] (Node eval bundle).

        stderr is PIPED (the Node server's `"ready\\n"` sentinel + any init
        failure ride it). It is line-buffered; `ready_after()` blocks reading
        it until the sentinel appears. The bb server writes nothing to stderr in
        the steady state, so its stderr pipe simply stays empty — harmless."""
        self.argv = list(argv)
        self.ready_sentinel = ready_sentinel
        self._ready = ready_sentinel is None  # bb: ready on first call; eval: wait
        self.p = self._spawn()

    def _spawn(self):
        return subprocess.Popen(
            self.argv,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,  # line-buffered
        )
        # Cold-start barrier: the bb script `(require 'seon.repl.internal)` +
        # rewrite-clj load happens on first use; the Node server loads the
        # ~15MB bootstrap cache before printing "ready\n" to stderr. bb does NOT
        # block here (first .call() pays it); the Node server is gated by
        # ready_after().

    def alive(self):
        """Liveness flag: the child is up iff `Popen.poll()` is None (no exit
        code yet). A dead child (crashed eval bundle, killed bb) returns False;
        `_ensure()` then lazily respawns on the next call."""
        return self.p.poll() is None

    def _ensure(self):
        """LAZY RESPAWN: if the child died (poll() not None), respawn it and
        re-arm the ready-wait. A 21ms (bb) / 276ms (node) re-warm, rare and
        isolated from the model — beats letting a dead pipe wedge the loop."""
        if not self.alive():
            self.p = self._spawn()
            self._ready = self.ready_sentinel is None
            if self.ready_sentinel is not None:
                self.ready_after()

    def ready_after(self, timeout_s=120.0):
        """Block until the server prints `ready_sentinel` on STDERR (the Node
        eval server's `"ready\\n"`), then return the spawn->ready latency in ms.
        No-op (returns 0.0) when `ready_sentinel` is None (the bb parse server,
        which is synchronous-on-first-call and emits no sentinel).

        Reads stderr line by line: the sentinel ends the wait; an `init-failed:`
        line (the Node server's init-error path, `worker_eval.cljs:395`) or the
        child exiting is raised as a RuntimeError so a broken bundle fails LOUD
        rather than hanging the loop."""
        if self.ready_sentinel is None or self._ready:
            return 0.0
        t0 = time.perf_counter()
        deadline = t0 + timeout_s
        while time.perf_counter() < deadline:
            line = self.p.stderr.readline()
            if line == "":  # EOF: the child exited before signalling ready
                code = self.p.poll()
                raise RuntimeError(
                    f"oracle {self.argv[0]} exited (code={code}) before ready")
            line = line.strip()
            if line == self.ready_sentinel:
                self._ready = True
                return round((time.perf_counter() - t0) * 1000.0, 2)
            if line.startswith("init-failed:"):
                raise RuntimeError(f"oracle {self.argv[0]} init failed: {line}")
            # any other stderr chatter (warnings) — ignore, keep waiting
        raise RuntimeError(
            f"oracle {self.argv[0]} not ready after {timeout_s}s")

    def call(self, op, code, **opts):
        """Send one request, read one response line. Sequential (one
        in-flight) — matches the strictly-sequential denoise loop, so
        head-of-line blocking is a non-issue. Lazily respawns a dead child and
        (for the eval server) auto-waits readiness before the first send."""
        self._ensure()
        if not self._ready:
            self.ready_after()
        req = {"op": op, "code": code, **opts}
        self.p.stdin.write(json.dumps(req) + "\n")
        self.p.stdin.flush()
        line = self.p.stdout.readline()
        if line == "":  # child died mid-call — surface it, next call respawns
            code_ = self.p.poll()
            raise RuntimeError(
                f"oracle {self.argv[0]} closed stdout mid-call (code={code_})")
        return json.loads(line)

    def parse(self, code, **opts):
        """Convenience: the parse-tier call. Returns the {forms, tier,
        errors:[{error-kind, span, source}]} map. `errors` drives the
        renoise (span -> canvas token positions via span_to_positions)."""
        return self.call("parse", code, **opts)

    def warmup(self, op="parse", code="(+ 1 2)"):
        """V8 / native HOT-PATH primer: send one throwaway request so the JIT
        compile+eval path is hot before the first REAL checkpoint. For the Node
        eval server this turns the first real eval from a JIT-cold outlier into
        the steady ~2.6ms; for bb it folds the rewrite-clj classpath load. Idem-
        potent and cheap — call it once right after spawn (the worker warm-up
        does this in `_oracle`). Returns the warmup round-trip ms."""
        t0 = time.perf_counter()
        self.call(op, code)
        return round((time.perf_counter() - t0) * 1000.0, 3)

    def close(self):
        try:
            self.p.stdin.close()
        except Exception:
            pass
        self.p.terminate()
        try:
            self.p.wait(timeout=2)
        except Exception:
            self.p.kill()


# --------------------------------------------------------------------------
# Offline self-test (NO GPU, NO image): drive the bb server over the real
# stdin/stdout pipe, prove round-trips, and MEASURE cold + warm latency.
# --------------------------------------------------------------------------
if __name__ == "__main__":
    import os

    repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    argv = ["bb", os.path.join(repo, "bin", "oracle-server")]
    print("spawn:", " ".join(argv))

    t0 = time.perf_counter()
    oracle = Oracle(argv)
    spawned = time.perf_counter()

    # First call pays the bb cold start (jvm-less bb boot + require +
    # rewrite-clj load). Measure spawn->first-response as "cold".
    r = oracle.parse("(defn mean [v] (/ (reduce + v) (count v)))")
    cold_ready = time.perf_counter()
    print("cold round-trip 1:", r)
    assert r["forms"] == 1 and r["errors"] == [], r

    # Warm calls — the persistent hot path.
    r2 = oracle.parse("(def mean [[v] ...)", id=7)
    print("warm round-trip 2:", r2)
    assert r2["forms"] == 0, r2
    assert r2["errors"][0]["error-kind"] == "unmatched-delimiter", r2
    assert r2["errors"][0]["span"] == [0, 19], r2

    r3 = oracle.parse("(+ 1 2)\n(- 3 1)\n(foo")
    print("warm round-trip 3 (multi-form + trailing eof):", r3)
    assert r3["forms"] == 2, r3
    assert r3["errors"][0]["error-kind"] == "eof", r3

    # Warm latency: many calls, report mean/median.
    N = 500
    durs = []
    for _ in range(N):
        s = time.perf_counter()
        oracle.parse("(defn f [x] (* x x))")
        durs.append((time.perf_counter() - s) * 1000.0)
    durs.sort()
    warm_mean = sum(durs) / len(durs)
    warm_p50 = durs[len(durs) // 2]
    warm_min = durs[0]

    oracle.close()

    print()
    print("=== LATENCIES (offline, bb parse server) ===")
    print(f"spawn (Popen return):     {(spawned - t0) * 1000:.2f} ms")
    print(f"cold (spawn->1st resp):   {(cold_ready - spawned) * 1000:.1f} ms")
    print(f"warm per-call mean:       {warm_mean:.3f} ms  (n={N})")
    print(f"warm per-call p50:        {warm_p50:.3f} ms")
    print(f"warm per-call min:        {warm_min:.3f} ms")
    print("ALL ASSERTIONS PASSED")
