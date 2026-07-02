"""Oracle-backed inspect-ai scorers for the diffusion measurements.

THE anti-dead-bundle layer: every task in this bench scores through the REAL
Seon oracles — the bb parse/structural/phase server (`bin/oracle-server`) and
the node cljs.js eval bundle (`out/worker-oracle-eval/main.js`) — held as ONE
persistent process each per eval run, and gated by `assert_oracle_live()`:
golden known-good AND known-bad samples must score correctly before any task
constructs, else we raise (the voided 06-29 E1 run scored a dead eval bundle;
see research/e1-behavioral-zero-audit-2026-07-02.md).

Scoring semantics are a faithful port of the fixed kill-gate harness
(tmp/flash-diffgemma/e1_kill_gate.py `score_attempt` — gitignored scratch, so
the committed port lives here; this bench is the standard home going forward):
parse (bb parse-raw) -> structural (register!/:malli/schema/map-in-out/
namespaced-kw/hallucination) -> eval (does it compile/run) -> BEHAVIORAL
(define + CALL with (input -> expected) cases) -> vacuity (F2). `faithful` =
parse AND structural AND correctness AND not vacuous.

Env knobs:
  SEON_EVAL_BUNDLE  override the node bundle path (point at a bogus path to
                    prove the liveness gate aborts)
  SEON_ORACLE_BB    override the bb server argv (space-split)
"""

from __future__ import annotations

import atexit
import json
import os
import re
import subprocess
import threading


# --------------------------------------------------------------------------- #
# Repo root — walk up from this file until bin/oracle-server appears.          #
# --------------------------------------------------------------------------- #
def _repo_root() -> str:
    d = os.path.dirname(os.path.abspath(__file__))
    while d != "/":
        if os.path.exists(os.path.join(d, "bin", "oracle-server")):
            return d
        d = os.path.dirname(d)
    raise RuntimeError("repo root with bin/oracle-server not found above " + __file__)


REPO = _repo_root()
EVAL_BUNDLE = os.environ.get(
    "SEON_EVAL_BUNDLE", os.path.join(REPO, "out/worker-oracle-eval/main.js")
)
BB_ARGV = os.environ.get("SEON_ORACLE_BB", os.path.join(REPO, "bin/oracle-server")).split()
EVAL_BUDGET_MS = int(os.environ.get("SEON_EVAL_BUDGET_MS", "1500"))


class _LineServer:
    """One persistent line-protocol child: write one JSON line, read one back."""

    def __init__(self, argv, ready_sentinel=None):
        # inspect runs samples/epochs CONCURRENTLY; the pipe is one-in-flight —
        # serialize request/response pairs or threads steal each other's replies.
        self.lock = threading.Lock()
        self.proc = subprocess.Popen(
            argv, cwd=REPO, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, bufsize=1,
        )
        if ready_sentinel:
            ok = False
            for _ in range(400):
                line = self.proc.stderr.readline()
                if not line:
                    raise RuntimeError(f"{argv[0]} exited before '{ready_sentinel}'")
                if ready_sentinel in line:
                    ok = True
                    break
                if "init-failed" in line:
                    raise RuntimeError(f"{argv[0]} init-failed: {line.strip()}")
            if not ok:
                raise RuntimeError(f"{argv[0]} never signaled '{ready_sentinel}'")
        threading.Thread(target=self._drain_stderr, daemon=True).start()
        atexit.register(self.close)

    def _drain_stderr(self):
        try:
            for _ in self.proc.stderr:
                pass
        except Exception:
            pass

    def call(self, obj: dict) -> dict | None:
        with self.lock:
            try:
                self.proc.stdin.write(json.dumps(obj) + "\n")
                self.proc.stdin.flush()
                line = self.proc.stdout.readline()
                return json.loads(line) if line.strip() else None
            except Exception:
                return None

    def close(self):
        try:
            self.proc.kill()
        except Exception:
            pass


_BB: _LineServer | None = None
_EVAL: _LineServer | None = None


def bb() -> _LineServer:
    """The persistent bb oracle (parse-raw / refine / structural / phase tiers)."""
    global _BB
    if _BB is None:
        _BB = _LineServer(BB_ARGV)
    return _BB


def evalsrv() -> _LineServer:
    """The persistent node cljs.js eval server (compile/run + behavioral tiers)."""
    global _EVAL
    if _EVAL is None:
        if not os.path.exists(EVAL_BUNDLE):
            raise RuntimeError(
                f"eval bundle missing: {EVAL_BUNDLE} — rebuild with "
                "`clj -M:cljs compile worker-oracle-eval` (dead-oracle guard)"
            )
        _EVAL = _LineServer(["node", EVAL_BUNDLE, "--serve"], ready_sentinel="ready")
    return _EVAL


def oracle_parse(code: str) -> dict:
    """parse-raw over the EXACT string: {forms, errors:[{error-kind, span}]}."""
    r = bb().call({"op": "parse-raw", "code": code})
    return r or {"forms": 0, "errors": [{"error-kind": "no-output"}]}


def oracle_refine(code: str, phase: str | None = None) -> dict:
    """bb op:refine — parse + structural(def-vs-defn) + phase tiers in one call."""
    req = {"op": "refine", "code": code}
    if phase:
        req["phase"] = phase
    return bb().call(req) or {"clamps": [], "renoise_spans": [{"error-kind": "no-output"}]}


# --------------------------------------------------------------------------- #
# Pure helpers (ported: e1_kill_gate.py — the fixed 2026-07-02 harness).       #
# --------------------------------------------------------------------------- #
FENCE = re.compile(r"```(?:clojure|clj)?\s*(.*?)```", re.S)
NS_KW = re.compile(r"::?[a-z][\w.-]*/[\w-]+")
HALLUCINATED = ("s/def", "spec/", "schema/string?", "schema/keys", "schema/map",
                "validate!", "clojure.spec", "(spec ")
_REGISTER_HEAD = re.compile(r"^\(\s*schema/register!")
_NUM = re.compile(r"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?")


def strip_fence(text: str) -> str:
    m = FENCE.search(text or "")
    return (m.group(1) if m else (text or "")).strip()


def _top_forms(code: str) -> list[str]:
    """Split into top-level forms (delimiter scan respecting strings/chars/`;`)."""
    forms, depth, start, i, n = [], 0, None, 0, len(code)
    in_str = esc = False
    while i < n:
        c = code[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == ";":
            j = code.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "\\":
            i += 2
            continue
        if c == '"':
            in_str = True
            i += 1
            continue
        if c in "([{":
            if depth == 0:
                start = i
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0 and start is not None:
                forms.append(code[start:i + 1])
                start = None
        i += 1
    return forms


def fn_form(code: str) -> str:
    """The runnable artifact: the code with `(schema/register! …)` forms stripped
    (the eval bundle is pod-free; register! is the STRUCTURAL tier's job)."""
    kept = [f for f in _top_forms(code) if not _REGISTER_HEAD.match(f.strip())]
    joined = "\n".join(kept).strip()
    return joined or code


def is_vacuous(code: str) -> bool:
    """F2: a PRESENT spec that rejects nothing ([:map] empty or :any slots)."""
    if ":malli/schema" not in code:
        return False
    return bool(re.search(r"\[:map\s*\]", code) or re.search(r":any\b", code))


def _behavioral_harness(spec: dict) -> str:
    fn = spec["fn_name"]
    slots = [f'(let [r ({fn} {c["in"]})] (when (map? r) ({c["key"]} r)))'
             for c in spec["cases"]]
    return "[" + " ".join(slots) + "]"


def _parse_value_vec(s: str) -> list[float | None]:
    inner = (s or "").strip()
    if inner.startswith("["):
        inner = inner[1:-1]
    out: list[float | None] = []
    for tok in inner.split():
        if tok == "nil":
            out.append(None)
        elif _NUM.fullmatch(tok):
            out.append(float(tok))
        else:
            out.append(None)
    return out


def eval_behavioral(code: str, spec: dict) -> dict | None:
    """Define the fn (register! stripped), CALL each case, compare to expected."""
    cases = spec.get("cases")
    if not cases:
        return None
    srv = evalsrv()
    form = fn_form(code)
    dv = srv.call({"op": "eval", "code": form, "budget-ms": EVAL_BUDGET_MS})
    defines = bool(dv and dv.get("ok"))
    got: list[float | None] = []
    if defines:
        ev = srv.call({"op": "eval", "code": form + "\n" + _behavioral_harness(spec),
                       "budget-ms": EVAL_BUDGET_MS})
        got = _parse_value_vec(ev.get("value")) if ev and ev.get("ok") else []
    results = []
    for i, c in enumerate(cases):
        g = got[i] if i < len(got) else None
        tol = c.get("tol", 1e-6)
        results.append({"in": c["in"], "expect": c["expect"], "got": g,
                        "match": (g is not None and abs(g - c["expect"]) <= tol)})
    return {"defines": defines, "cases": results,
            "behavioral_pass": defines and all(r["match"] for r in results)}


def score_code(text: str, spec: dict) -> dict:
    """ONE scoring fn for every task: the full tier dict for a generation.

    `spec` carries `expects` (structural demands), and optionally `fn_name` +
    `cases` (behavioral). Ported from the fixed e1 `score_attempt`.
    """
    code = strip_fence(text)
    pr = oracle_parse(code)
    parses = pr.get("forms", 0) >= 1 and not pr.get("errors")

    ev = None
    if parses:
        ev = evalsrv().call({"op": "eval", "code": fn_form(code),
                             "budget-ms": EVAL_BUDGET_MS})
    eval_ok = None if ev is None else bool(ev.get("ok"))

    beh = eval_behavioral(code, spec) if parses else (
        {"defines": False, "cases": [], "behavioral_pass": False}
        if spec.get("cases") else None)

    exp = spec.get("expects", {})
    has_register = "schema/register!" in code
    has_malli = ":malli/schema" in code
    namespaced_kw = bool(NS_KW.search(code))
    map_in_out = ("-request" in code and "-response" in code)
    hallucinated = any(h in code for h in HALLUCINATED)

    structural = not hallucinated
    if exp.get("register"):
        structural = structural and has_register
    if exp.get("malli_schema"):
        structural = structural and has_malli
    if exp.get("map_in_out"):
        structural = structural and map_in_out
    if exp.get("namespaced_kw", True):
        structural = structural and namespaced_kw

    vacuous = is_vacuous(code)
    instrumentable = parses and has_malli and not hallucinated
    if eval_ok is not None:
        instrumentable = instrumentable and eval_ok

    behavioral_pass = None if beh is None else beh["behavioral_pass"]
    correctness = behavioral_pass if behavioral_pass is not None else instrumentable
    faithful = parses and structural and bool(correctness) and not vacuous
    scalar = (0.15 * parses + 0.20 * structural + 0.15 * bool(correctness)
              + 0.50 * (not vacuous))
    return {
        "parses": parses, "structural": structural, "instrumentable": instrumentable,
        "behavioral_pass": behavioral_pass,
        "behavioral_cases": (beh["cases"] if beh else None),
        "vacuous": vacuous, "hallucinated": hallucinated, "faithful": faithful,
        "faithfulness": round(scalar, 3), "eval_ok": eval_ok,
        "has_register": has_register, "has_malli": has_malli,
        "map_in_out": map_in_out, "namespaced_kw": namespaced_kw,
        "code_head": code[:80],
    }


# --------------------------------------------------------------------------- #
# The oracle-liveness gate (anti-dead-bundle; fail-loud at task construction). #
# --------------------------------------------------------------------------- #
_GOLDEN_GOOD = """(schema/register! ::celsius->fahrenheit-request [:map [::celsius :double]])
(schema/register! ::celsius->fahrenheit-response [:map [::fahrenheit :double]])
(defn celsius->fahrenheit
  {:malli/schema [:=> [:cat ::celsius->fahrenheit-request] ::celsius->fahrenheit-response]}
  [{::keys [celsius]}]
  {::fahrenheit (+ 32 (* celsius 1.8))})"""

_GOLDEN_SPEC = {
    "fn_name": "celsius->fahrenheit",
    "expects": {"register": True, "malli_schema": True, "map_in_out": True,
                "namespaced_kw": True},
    "cases": [{"in": "{::celsius 0.0}", "key": "::fahrenheit", "expect": 32.0},
              {"in": "{::celsius 100.0}", "key": "::fahrenheit", "expect": 212.0}],
}

# def-vs-defn: parses clean but MUST fail the eval tier ("Too many arguments to
# def"). A dead/lenient eval tier fails this golden in the other direction.
_GOLDEN_BAD = "(def mean [v] (/ (reduce + v) (count v)))"

_LIVENESS_OK = False


def assert_oracle_live() -> None:
    """Golden-sample gate: known-good scores faithful, known-bad fails eval.

    Raises RuntimeError when either oracle is dead, stale, or lenient — the
    exact defect that voided the 06-29 E1 run. Idempotent per process.
    """
    global _LIVENESS_OK
    if _LIVENESS_OK:
        return
    good = score_code(_GOLDEN_GOOD, _GOLDEN_SPEC)
    if not (good["faithful"] and good["behavioral_pass"] is True):
        raise RuntimeError(
            "ORACLE LIVENESS FAILED: the known-good golden did not score "
            f"faithful+behavioral: {json.dumps(good)} — the oracle stack is "
            "dead/degraded; a run now would measure the harness, not the model."
        )
    bad = evalsrv().call({"op": "eval", "code": _GOLDEN_BAD,
                          "budget-ms": EVAL_BUDGET_MS})
    if bad is None or bad.get("ok") is not False:
        raise RuntimeError(
            "ORACLE LIVENESS FAILED: the known-bad golden (def-vs-defn) did not "
            f"FAIL the eval tier: {json.dumps(bad)} — the eval tier is dead or "
            "lenient; refusing to score."
        )
    _LIVENESS_OK = True


# --------------------------------------------------------------------------- #
# The inspect @scorer.                                                         #
# --------------------------------------------------------------------------- #
from inspect_ai.scorer import CORRECT, INCORRECT, Score, Scorer, Target, accuracy, scorer  # noqa: E402
from inspect_ai.solver import TaskState  # noqa: E402


@scorer(metrics=[accuracy()])
def ladder_scorer() -> Scorer:
    """Score a generation through the full oracle ladder.

    CORRECT iff `faithful` (parse AND structural AND behavioral/instrumentable
    AND not vacuous). The per-sample spec (expects / fn_name / cases) rides in
    Sample.metadata["spec"]; the full tier dict lands in Score.metadata so the
    eval log carries parse/structural/eval/behavioral/vacuity per sample.
    """

    async def score(state: TaskState, target: Target) -> Score:
        import anyio

        spec = (state.metadata or {}).get("spec", {})
        sc = await anyio.to_thread.run_sync(score_code, state.output.completion, spec)
        return Score(
            value=CORRECT if sc["faithful"] else INCORRECT,
            answer=sc["code_head"],
            explanation=json.dumps({k: sc[k] for k in (
                "parses", "structural", "eval_ok", "behavioral_pass", "vacuous")}),
            metadata=sc,
        )

    return score
