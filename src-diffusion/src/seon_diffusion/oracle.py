"""Seon oracle clients — persistent JSON-line pipe servers, spawned once.

Two co-located oracles back the guided loop:

  Oracle       — babashka `bin/oracle-server`. op:"refine" gives the CHEAP
                 tiers in one ~0.1ms call: parse (broken-syntax spans),
                 structural lint (def-vs-defn), phase grammar, plus clamp
                 spans for the good forms. Spans are char offsets
                 [start end) into the exact code string sent (raw basis).

  EvalSession  — node `out/worker-oracle-eval/main.js --serve`. op:"eval"
                 against a PERSISTENT self-host compile state: defs
                 accumulate across calls, so one EvalSession per build IS
                 the session environment ("lock and execute"). It is also
                 the PROOF engine for auto-repair: undeclared-var and
                 fn-arity surface as errors, so a substituted candidate
                 either compiles or is rejected.

Both speak one JSON object per line, both directions, `id` echoed. Both
carry a liveness gate (the voided-E1 lesson: a dead oracle must fail
loud, never silently zero).
"""

import json
import subprocess
import threading

from . import config


class _LineServer:
    def __init__(self, argv, ready_line=None, cwd=None):
        # cwd matters: the eval bundle loads out/bootstrap relative to cwd
        self.proc = subprocess.Popen(
            argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE if ready_line else subprocess.DEVNULL,
            text=True, bufsize=1, cwd=cwd)
        self._id = 0
        if ready_line is not None:
            line = self.proc.stderr.readline().strip()   # sentinel is on STDERR
            if line != ready_line:
                raise RuntimeError(f"{argv[0]}: expected {ready_line!r}, got {line!r}")
            t = threading.Thread(target=self.proc.stderr.read, daemon=True)
            t.start()                       # drain stderr so it can't block

    def call(self, req):
        self._id += 1
        req = dict(req, id=self._id)
        self.proc.stdin.write(json.dumps(req) + "\n")
        self.proc.stdin.flush()
        line = self.proc.stdout.readline()
        if not line:
            raise RuntimeError(f"oracle server died (req op={req.get('op')})")
        resp = json.loads(line)
        if resp.get("id") not in (None, self._id):
            raise RuntimeError(f"oracle id mismatch: sent {self._id}, got {resp.get('id')}")
        return resp

    def close(self):
        try:
            self.proc.stdin.close()
            self.proc.terminate()
        except Exception:
            pass


class Oracle(_LineServer):
    """bb parse/structural/phase oracle (stateless, pure)."""

    def __init__(self):
        super().__init__([config.oracle_bb()])
        # liveness gate: a dead oracle must fail loud, never silently zero.
        good = self.refine("(defn f [x] x)")
        bad = self.refine("(def f [x] x)")
        if good["renoise_spans"] or not bad["renoise_spans"]:
            raise RuntimeError("bb oracle liveness gate FAILED — refusing to steer")

    def refine(self, code, phase=None):
        req = {"op": "refine", "code": code}
        if phase:
            req["phase"] = phase
        return self.call(req)


class EvalSession(_LineServer):
    """node cljs.js eval server — STATEFUL: defs accumulate across calls."""

    def __init__(self):
        super().__init__(
            ["node", config.eval_bundle(), "--serve"],
            ready_line="ready", cwd=str(config.repo_root()))
        r = self.eval("(+ 1 2)")
        if not r.get("ok") or r.get("value") != "3":
            raise RuntimeError(f"eval oracle liveness gate FAILED: {r} — refusing to gate")

    def eval(self, code, budget_ms=3000):
        return self.call({"op": "eval", "code": code, "budget-ms": budget_ms})

    _DEMUNGE = [("_QMARK_", "?"), ("_BANG_", "!"), ("_GT_", ">"), ("_LT_", "<"),
                ("_EQ_", "="), ("_STAR_", "*"), ("_PLUS_", "+"), ("_SLASH_", "/"),
                ("_AMPERSAND_", "&"), ("_TILDE_", "~"), ("_APOS_", "'"), ("_", "-")]

    def core_names(self):
        """Demunged `cljs.core` public names (cached) — the did-you-mean pool
        for undeclared-var repair (a mini retrieval leg; the program-graph
        retrieval leg supersedes this for project fns)."""
        if not hasattr(self, "_core_names"):
            r = self.eval("(pr-str (vec (js-keys js/cljs.core)))")
            names = []
            raw = r.get("value", "") if r.get("ok") else ""
            for m in raw.replace("[", " ").replace("]", " ").replace('\\"', " ").replace('"', " ").split():
                if "$" in m or m.startswith("-") or m.startswith("_"):
                    continue
                for a, b in self._DEMUNGE:
                    m = m.replace(a, b)
                names.append(m)
            self._core_names = names
        return self._core_names
