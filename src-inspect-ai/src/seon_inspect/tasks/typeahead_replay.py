"""Typeahead replay — the P4 three-arm bench (typeahead-design.md §Evaluation).

Replays the acme-captured corpus (`seon_inspect.typeahead_corpus` →
evals/typeahead_replay.corpus.json) against the DiffusionGemma worker in
three arms over the SAME intents + contracts:

  arm1_guided     free guided generation (mode=guided) — NO menus in the
                  render. The shipped verified code-buffer baseline.
  arm2_typeahead  the full surface: menus + plan ledger IN the render,
                  mode=step FSM loop (the Python mirror of the shipped
                  seon.ai.typeahead loop: committed/draft threaded, offers
                  glyph-aligned with the rendered menu, stop on done /
                  stuck×2 / max-rounds), offers ON, null-render
                  calibration ON (P5 — the null-intent baseline rides
                  every step so calibrated auto-offers can fire).
  arm3_degraded   menus RENDERED (same render as arm2) but the machinery
                  INERT: mode=guided, no offers — the always-works
                  invariant arm. 1 vs 3 isolates render-TEXT effects;
                  2 vs 3 isolates the step/offer machinery.

Renders are built at ≤4k tokens (round-10 protocol: sub-2s steps) from the
corpus's VERBATIM captured sections — orientation + contract ns cards
(all arms) + the menu sections (arms 2/3) + the task.

Scoring (scorers gate CORRECTNESS, style only reported): eval-answer rows
pass when the RIGHT ANSWER is delivered — last pure-form value (node
oracle) OR the answer standing in the reply text (the production
message-function/prose convention; cross-arm fairness, measured on arm0);
verb-call rows (the frozen corpus's predicate-kind literal) pass when the
reply parses (bb oracle) AND calls a task-required function. Form validity is
always reported separately. Metrics per execution
(metadata, reduced by `run_typeahead`): form validity, function-calling accuracy,
tokens-to-valid-form (chars/4 estimate — the seon convention), wall-clock,
uptake rate + rounds-to-lock (arm2).

Kill criteria (evaluated by the run report, honestly): degradation arm
regresses baseline → protocol leak; uptake ≈ 0 with no accuracy gain →
dead weight.

RUN (all three arms, local worker, evidence + ledger rows):

    cd src-inspect-ai
    .venv/bin/python -c "from seon_inspect.tasks.typeahead_replay import \
        run_typeahead; run_typeahead(epochs=3)"

Freshness discipline: restart the local worker (`bin/seon restart
dg-worker`) and check /health worker_sha before any measured run —
run_typeahead records the sha it saw.
"""

from __future__ import annotations

import json
import re
import statistics
import subprocess
import time
from pathlib import Path

from inspect_ai import Epochs, Task, eval as inspect_eval, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.scorer import (CORRECT, INCORRECT, Score, Scorer, Target,
                               accuracy, pass_at, scorer)
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect.oracle_scorers import (_top_forms, assert_oracle_live,
                                         evalsrv, oracle_parse, oracle_refine,
                                         strip_fence)
from seon_inspect.typeahead_corpus import (CORPUS_PATH, corpus_sha,
                                           load_corpus)
from seon_inspect.worker_endpoints import resolve_endpoint

REPO_ROOT = Path(__file__).resolve().parents[4]
DEFAULT_ENDPOINT = "http://127.0.0.1:17860"
RENDER_BUDGET_TOKENS = 4096
EVAL_BUDGET_MS = 3000
BASE_SEED = 100

# The shipped seon defaults, as policy->wire maps them (seon.agent.ctx.menu/
# default-policy → seon.ai.typeahead/policy->wire). max_rounds also bounds
# the Python loop below, mirroring the provider.
POLICY_WIRE = {"auto_offer_margin": 3.0, "probe_lengths": 3,
               "glyph_page_size": 8, "max_rounds": 8}
MAX_ROUNDS = 8

ARMS = ("arm1_guided", "arm2_typeahead", "arm3_degraded")


# ---------------------------------------------------------------------------
# Render building (pure).
# ---------------------------------------------------------------------------
ORIENTATION = (
    "; seon agent — live ClojureScript REPL.\n"
    "; Write Clojure forms to act; each form you write is evaluated in\n"
    "; order. Results arrive on your next turn — never write a result\n"
    "; yourself.")

CONTRACT_LINES = {
    # The scorer's calling convention, STATED in the task (the e1 audit's
    # context fix) — identical across arms. NOTE: "verb-call" is the FROZEN
    # corpus predicate-kind literal (evals/typeahead_replay.corpus.json) —
    # the data key keeps its historical name; code identifiers say fn_*
    # (functions-not-verbs ruling, 2026-07-11).
    "eval-answer": ("; Finish with ONE expression whose value is the "
                    "answer."),
    "verb-call": ("; Act by CALLING the appropriate function(s) — a "
                  "correct call is the answer."),
}


def token_estimate(s: str) -> int:
    """The seon convention: chars/4 (seon.ai.tokens/estimate)."""
    return len(s) // 4


# The teaching is CODE (seon.agent.ctx.menu/recent-verbs-header, P5's
# additive example lines); the entries are DATA (the corpus's verbatim
# capture). The bench renders the shipped teaching over the frozen
# entries — same stance as POLICY_WIRE mirroring the shipped defaults.
_MENU_HEADER_LAST = ("; alone (e.g. ①), or ignore this and write any "
                     "Clojure — both work.")
MENU_TEACHING_ADDENDUM = (
    "; Example: to select entry ①, output the single character ① and\n"
    "; nothing else — its call template is expanded for you to fill.")


def refresh_menu_teaching(section: str) -> str:
    """The captured `recent-verbs` section with the CURRENT colocated
    teaching (the P5 example lines appended after the captured header —
    purely additive; the glyph entries stay byte-verbatim). Idempotent;
    a section without the known header line rides unchanged."""
    if MENU_TEACHING_ADDENDUM.splitlines()[0] in section:
        return section
    return section.replace(
        _MENU_HEADER_LAST,
        _MENU_HEADER_LAST + "\n" + MENU_TEACHING_ADDENDUM, 1)


def _render_parts(sample: dict, arm: str, *, null: bool = False) -> list[str]:
    """The front of one arm's render: orientation + contract ns cards
    (+ the menu sections on arms 2/3, current teaching). `null` drops
    the plan-ledger (intent-DERIVED — its captured steps restate the
    task; the seon-side mirror is null-render's plan/plan-ledger
    strip)."""
    parts = [ORIENTATION]
    for ns in sample["contract_nses"]:
        sec = sample["sections"].get(f"namespace {ns}")
        if sec:
            parts.append(sec)
    if arm in ("arm2_typeahead", "arm3_degraded"):
        names = ("recent-verbs",) if null else ("recent-verbs", "plan-ledger")
        for name in names:
            sec = sample["sections"].get(name)
            if sec:
                parts.append(refresh_menu_teaching(sec)
                             if name == "recent-verbs" else sec)
    return parts


def build_render(sample: dict, arm: str) -> str:
    """One arm's encoder context render from the corpus sample's VERBATIM
    captured sections. Arms 2/3 additionally carry the menu sections; the
    contract cards + task are byte-identical across arms."""
    contract = CONTRACT_LINES[sample["predicate"]["kind"]]
    parts = _render_parts(sample, arm)
    parts.append(";;; ◀ from user (NEW — unanswered; respond to this) — "
                 + json.dumps(sample["intent"]) + "\n" + contract
                 + "\nmy.agent=> ")
    render = "\n\n".join(parts)
    est = token_estimate(render)
    if est > RENDER_BUDGET_TOKENS:
        raise ValueError(f"render for {sample['id']}/{arm} is ~{est} tokens "
                         f"(> {RENDER_BUDGET_TOKENS} budget)")
    return render


def build_null_render(sample: dict, arm: str) -> str:
    """The null-intent calibration render: the SAME sections as
    [[build_render]] minus the task/message content (the design's
    calibration rule — the seon-side mirror is
    seon.ai.typeahead/null-render, which drops the transcript event log
    + the intent-derived plan sections; here the task part IS the event
    log, the captured plan-ledger restates the task and is dropped, and
    the null render ends at the bare cursor)."""
    return "\n\n".join(_render_parts(sample, arm, null=True)
                       + ["my.agent=> "])


# ---------------------------------------------------------------------------
# Reply analysis (pure over the oracle servers).
# ---------------------------------------------------------------------------
_STRING = re.compile(r'"(?:\\.|[^"\\])*"')
_COMMENT = re.compile(r";[^\n]*")
_HEAD = re.compile(r"\(\s*([A-Za-z][\w.*+!?<>=/-]*)")


def call_heads(code: str) -> list[str]:
    """Every symbol in call position (strings/comments stripped) — the
    function-choice read, nested calls included."""
    clean = _COMMENT.sub("", _STRING.sub('""', code))
    return _HEAD.findall(clean)


def fn_match(code: str, predicate: dict) -> bool:
    """True when the reply calls a task-required function (exact head or
    head-namespace match against the sample's predicate)."""
    heads = set(call_heads(code))
    if heads & set(predicate.get("heads") or []):
        return True
    nses = set(predicate.get("head_nses") or [])
    return any("/" in h and h.split("/", 1)[0] in nses for h in heads)


def eval_answer(code: str, expect: list[str]) -> tuple[bool, str | None]:
    """Node-oracle-eval the reply's pure forms (unresolvable seon-fn forms
    are skipped, accumulating the rest); the LAST value must match one of
    `expect` (printed-value strings, whitespace-trimmed)."""
    kept: list[str] = []
    last = None
    for form in _top_forms(code):
        # (do …)-wrapped: the eval bundle mis-evals a bare top-level `def`
        # followed by another form in one eval-str call (probed 2026-07-10);
        # do-wrapping is semantics-preserving for this value read.
        trial = kept + [form]
        r = evalsrv().call({"op": "eval",
                            "code": "(do " + "\n".join(trial) + ")",
                            "budget-ms": EVAL_BUDGET_MS})
        if r and r.get("ok"):
            kept = trial
            last = r.get("value")
    got = (last or "").strip()
    return got in [e.strip() for e in expect], got


def answer_in_text(text: str, expect: list[str]) -> bool:
    """The expected answer as a standalone token anywhere in the reply.

    The cross-arm fairness rule for eval-answer rows: a production-shaped
    reply delivers the answer via a message function or prose (measured on the
    arm0 DeepSeek turns: `(message/user \"44\")`), not as a bare
    value-bearing expression — the OUTCOME is right either way. None of
    the corpus intents contain their own answers (checked), so a text
    match cannot pass on echo."""
    return any(re.search(rf"(?<![\w.]){re.escape(e)}(?![\w.])", text or "")
               for e in expect)


def tokens_to_valid_form(code: str) -> int | None:
    """~Tokens (chars/4) of reply consumed up to the end of the FIRST
    parse-clean top-level form; None when nothing parses."""
    clamps = oracle_refine(code).get("clamps") or []
    if not clamps:
        return None
    first_end = min(c["span"][1] for c in clamps)
    return max(first_end, 1) // 4


def analyze_reply(text: str, predicate: dict) -> dict:
    """The correctness gate + per-execution metrics for one reply."""
    code = strip_fence(text)
    pr = oracle_parse(code)
    parses = bool(pr.get("forms", 0) >= 1 and not pr.get("errors"))
    out = {"parses": parses,
           "tokens_to_valid_form": tokens_to_valid_form(code),
           "reply_tokens": token_estimate(code)}
    if predicate["kind"] == "eval-answer":
        # outcome = the RIGHT ANSWER delivered: the last pure-form value
        # matches, OR the answer stands in the reply text (message-fn /
        # prose delivery — the production convention). Form validity is
        # the separate `parses` metric, reported, not gating the answer.
        ok, got = eval_answer(code, predicate["expect"])
        out.update({"outcome_pass": ok or answer_in_text(code,
                                                         predicate["expect"]),
                    "eval_match": ok, "got": got,
                    "fn_applicable": False, "fn_match": None})
    else:
        vm = fn_match(code, predicate)
        out.update({"outcome_pass": parses and vm,
                    "fn_applicable": True, "fn_match": vm})
    return out


# ---------------------------------------------------------------------------
# Arm drivers (worker calls; sync — run in a thread by the solver).
# ---------------------------------------------------------------------------
def run_guided(ep, render: str, seed: int) -> dict:
    r = ep.call({"mode": "guided", "prompt": render, "seed": seed,
                 "max_rounds": MAX_ROUNDS, "max_attempts": 2})
    return {"text": r.get("text", ""), "raw": r,
            "gen_s": r.get("gen_s"), "worker_sha": r.get("worker_sha"),
            "gen_error": r.get("gen_error"),
            "rounds": r.get("rounds"), "forwards": r.get("decoder_forwards"),
            # the guided worker reports locked_forms as a COUNT (int)
            "locked_n": r.get("locked_forms"),
            "steps": []}


def run_step_loop(ep, render: str, offers: list[dict], seed: int,
                  null_render: str | None = None) -> dict:
    """The shipped seon.ai.typeahead step loop, mirrored host-side:
    committed/draft threaded, stop on done / stuck×2 / MAX_ROUNDS.
    `null_render` (the null-intent baseline render) rides every step so
    the worker calibrates glyph posteriors — without it auto-offers
    structurally cannot fire (the P4 uptake-0.0 finding). P6: an offer
    whose expansion locked NOTHING is suppressed for the rest of the
    call — the step trace is the driver's memory (the P5 p1 trace shows
    the identical failed offer re-firing 4x at the same margin; the
    worker is stateless by design, so the memory lives in this loop)."""
    committed, draft, locked_all, stuck = "", "", [], 0
    steps, t0 = [], time.time()
    outcome = "round-cap"
    failed_glyphs: set[str] = set()
    for rnd in range(MAX_ROUNDS):
        live_offers = [o for o in offers
                       if o.get("glyph") not in failed_glyphs]
        payload = {"mode": "step", "prompt": render,
                   "committed": committed, "draft": draft,
                   "offers": live_offers, "policy": POLICY_WIRE, "seed": seed}
        if null_render:
            payload["null_render"] = null_render
        r = ep.call(payload)
        if r.get("gen_error") or r.get("_timeout") or r.get("_failed"):
            return {"text": "", "gen_error": r.get("gen_error")
                    or "worker timeout/failure", "steps": steps,
                    "raw": r, "gen_s": round(time.time() - t0, 3)}
        locked = [str(f) for f in (r.get("locked") or [])]
        locked_all += locked
        ro = r.get("readouts") or {}
        steps.append({
            "idx": rnd, "transition": r.get("transition"),
            "glyph": r.get("glyph"),
            "auto": any(e.get("event") == "auto-offer"
                        for e in (r.get("events") or [])),
            "locked_n": len(locked), "forwards": r.get("forwards"),
            "gen_s": r.get("gen_s"),
            "eos_logprob": ro.get("eos_logprob_tail"),
            # the C-lane evidence: calibrated margins per step, so the
            # auto_offer_margin policy default is tuned on data, not vibes
            "margin": ro.get("glyph_margin"),
            "posteriors_cal": ro.get("glyph_posteriors_calibrated"),
        })
        committed = "\n".join(x for x in [committed, *locked] if x.strip())
        draft = str(r.get("new_draft") or "")
        if r.get("transition") == "expand" and not locked and r.get("glyph"):
            failed_glyphs.add(r["glyph"])
        stuck = stuck + 1 if r.get("transition") == "stuck" else 0
        if r.get("transition") == "done":
            outcome, draft = "done", ""
            break
        if stuck >= 2:
            outcome = "gave-up"
            break
    text = "\n\n".join(x for x in [*locked_all, draft.strip()] if x.strip())
    return {"text": text, "steps": steps, "outcome": outcome,
            "gen_s": round(time.time() - t0, 3),
            "worker_sha": None, "gen_error": None,
            "locked_n": len(locked_all)}


def step_metrics(steps: list[dict]) -> dict:
    """Uptake + rounds-to-lock over one execution's step trace."""
    n = len(steps)
    selections = sum(1 for s in steps if s["transition"] == "expand")
    first_lock = next((s["idx"] + 1 for s in steps if s["locked_n"]), None)
    margins = [s["margin"] for s in steps if s.get("margin") is not None]
    return {"n_steps": n,
            "uptake": (selections / n) if n else None,
            "glyph_selections": selections,
            "auto_offers": sum(1 for s in steps if s.get("auto")),
            "margins": margins,
            "rounds_to_lock": first_lock,
            "step_s_mean": (round(statistics.mean(
                [s["gen_s"] for s in steps if s.get("gen_s") is not None]), 3)
                if any(s.get("gen_s") is not None for s in steps) else None)}


# ---------------------------------------------------------------------------
# inspect wiring.
# ---------------------------------------------------------------------------
def _samples() -> list[Sample]:
    corpus = load_corpus()
    return [Sample(id=s["id"], input=s["intent"], target="pass",
                   metadata={"sample": s})
            for s in corpus["samples"]]


@solver
def typeahead_arm_solver(arm: str, endpoint: str):
    """Drive one arm for each sample/epoch; metrics land in metadata."""
    ep = resolve_endpoint(endpoint)

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        s = state.metadata["sample"]
        seed = BASE_SEED + max(0, (getattr(state, "epoch", 1) or 1) - 1)
        render = build_render(s, arm)

        def drive():
            if arm == "arm2_typeahead":
                return run_step_loop(ep, render, s["offers"], seed,
                                     null_render=build_null_render(s, arm))
            return run_guided(ep, render, seed)

        t0 = time.time()
        r = await anyio.to_thread.run_sync(drive)
        state.output.completion = r.get("text") or ""
        state.metadata.update({
            "arm": arm, "seed": seed,
            "render_tokens": token_estimate(render),
            "wall_s": round(time.time() - t0, 3),
            "gen_s": r.get("gen_s"),
            "gen_error": r.get("gen_error"),
            "worker_sha": r.get("worker_sha"),
            "locked_n": r.get("locked_n"),
            "guided_rounds": r.get("rounds"),
            "steps": r.get("steps") or [],
            **({"step_metrics": step_metrics(r["steps"])}
               if arm == "arm2_typeahead" and r.get("steps") else {}),
        })
        return state

    return solve


@scorer(metrics=[accuracy()])
def outcome_scorer() -> Scorer:
    """CORRECTNESS gate: parses ∧ evals ∧ the sample's outcome predicate.
    A worker-side gen_error is stamped as a flake in metadata (excluded
    from capability means by the run reducer), never a model miss."""

    async def score(state: TaskState, target: Target) -> Score:
        import anyio

        if state.metadata.get("gen_error"):
            state.metadata["flake"] = "worker_error"
            return Score(value=INCORRECT, answer="",
                         explanation=f"flake: {state.metadata['gen_error']}")
        pred = state.metadata["sample"]["predicate"]
        text = state.output.completion
        a = await anyio.to_thread.run_sync(analyze_reply, text, pred)
        state.metadata["analysis"] = a
        return Score(value=CORRECT if a["outcome_pass"] else INCORRECT,
                     answer=(text or "")[:400],
                     explanation=json.dumps(a))

    return score


@task
def typeahead_replay(arm: str = "arm1_guided",
                     endpoint: str = DEFAULT_ENDPOINT,
                     epochs: int = 3):
    """One arm × pass^k epochs over the frozen replay corpus."""
    assert arm in ARMS, f"unknown arm {arm!r}"
    assert_oracle_live()
    return Task(
        dataset=MemoryDataset(_samples()),
        solver=typeahead_arm_solver(arm, endpoint),
        scorer=outcome_scorer(),
        epochs=Epochs(epochs, ["mean", pass_at(epochs)]),
    )


# ---------------------------------------------------------------------------
# The run driver — evidence + ledger rows (append-only discipline).
# ---------------------------------------------------------------------------
def _git_sha() -> str:
    return subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT,
                          capture_output=True, text=True).stdout.strip()


def _executions(log) -> list[dict]:
    """Execution records from one arm's EvalLog (this task's shape)."""
    out = []
    for s in (getattr(log, "samples", None) or []):
        md = s.metadata or {}
        base = {"sample_id": str(s.id), "epoch": s.epoch,
                "reply": getattr(s.output, "completion", ""),
                "analysis": md.get("analysis"),
                "wall_s": md.get("wall_s"), "gen_s": md.get("gen_s"),
                "render_tokens": md.get("render_tokens"),
                "steps": md.get("steps"),
                "step_metrics": md.get("step_metrics"),
                "seed": md.get("seed"), "worker_sha": md.get("worker_sha")}
        if getattr(s, "error", None) is not None:
            out.append({**base, "outcome": "harness_error",
                        "error": str(s.error)})
            continue
        if md.get("gen_error") or md.get("flake"):
            # worker-side failure — a flake class, never a model score
            # (read from the SOLVER-set gen_error: scorer-time metadata
            # mutation is not guaranteed to persist into the log)
            out.append({**base, "outcome": "worker_error",
                        "gen_error": md.get("gen_error")})
            continue
        score = next(iter((s.scores or {}).values()), None)
        passed = getattr(score, "value", None) in ("C", 1, 1.0, True)
        out.append({**base, "outcome": "pass" if passed else "fail"})
    return out


def _arm_summary(execs: list[dict]) -> dict:
    """The report metrics for one arm over its execution records."""
    scored = [e for e in execs if e["outcome"] in ("pass", "fail")]
    an = [e["analysis"] for e in scored if e.get("analysis")]
    fns = [a for a in an if a.get("fn_applicable")]
    ttv = [a["tokens_to_valid_form"] for a in an
           if a.get("tokens_to_valid_form") is not None]
    sm = [e["step_metrics"] for e in scored if e.get("step_metrics")]
    up = [m["uptake"] for m in sm if m.get("uptake") is not None]
    rtl = [m["rounds_to_lock"] for m in sm if m.get("rounds_to_lock")]
    return {
        "n_exec": len(execs), "n_scored": len(scored),
        "outcome_mean": (sum(e["outcome"] == "pass" for e in scored)
                         / len(scored) if scored else 0.0),
        "form_validity": (sum(a["parses"] for a in an) / len(an)
                          if an else 0.0),
        "fn_call_accuracy": (sum(a["fn_match"] for a in fns) / len(fns)
                             if fns else None),
        "tokens_to_valid_form_median": (statistics.median(ttv)
                                        if ttv else None),
        "wall_s_median": (statistics.median(walls) if (walls := [
            e["wall_s"] for e in scored if e.get("wall_s") is not None])
            else None),
        "uptake_mean": (statistics.mean(up) if up else None),
        "rounds_to_lock_median": (statistics.median(rtl) if rtl else None),
    }


def oneshot_reference(corpus: dict, *, name: str, base_url: str,
                      model: str, api_key: str,
                      extra_body: dict | None = None) -> tuple[list, dict]:
    """A text-LLM one-shot reference arm over the SAME arm1 renders (no
    menus): one chat completion per sample, reply scored by the same
    predicates. The apples-to-apples frontier-model comparison the local
    arms are measured against (k=1; temperature as sent)."""
    import urllib.request

    execs = []
    for s in corpus["samples"]:
        render = build_render(s, "arm1_guided")
        body = {"model": model, "temperature": 0.7,
                "messages": [{"role": "user", "content": render}],
                **(extra_body or {})}
        req = urllib.request.Request(
            base_url.rstrip("/") + "/chat/completions",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {api_key}"})
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                out = json.loads(resp.read().decode())
            reply = out["choices"][0]["message"]["content"] or ""
        except Exception as e:
            execs.append({"sample_id": s["id"], "epoch": 1,
                          "outcome": "worker_error", "error": str(e)[:300],
                          "wall_s": round(time.time() - t0, 3)})
            continue
        a = analyze_reply(reply, s["predicate"])
        execs.append({"sample_id": s["id"], "epoch": 1,
                      "outcome": "pass" if a["outcome_pass"] else "fail",
                      "reply": reply, "analysis": a,
                      "wall_s": round(time.time() - t0, 3),
                      "render_tokens": token_estimate(render)})
        print(f"[typeahead] {name} {s['id']}: "
              f"{execs[-1]['outcome']} {execs[-1]['wall_s']}s", flush=True)
    return execs, _arm_summary(execs)


def deepseek_reference(corpus: dict) -> tuple[list[dict], dict]:
    """arm0: the corpus's OWN DeepSeek-driven replay turns, scored by the
    SAME predicates (the raw LLM reply of the replayed turn — enriched via
    typeahead_corpus.enrich_replay_replies). k=1 by construction; no
    wall-clock (the pod turn's LLM latency was not captured per-turn —
    reported as None, honestly)."""
    execs = []
    for s in corpus["samples"]:
        reply = s.get("replay_reply")
        if reply is None:
            continue
        a = analyze_reply(reply, s["predicate"])
        execs.append({"sample_id": s["id"], "epoch": 1,
                      "outcome": "pass" if a["outcome_pass"] else "fail",
                      "reply": reply, "analysis": a,
                      "wall_s": None, "render_tokens": None})
    return execs, _arm_summary(execs)


def run_typeahead(arms: tuple[str, ...] = ARMS,
                  endpoint: str = DEFAULT_ENDPOINT,
                  epochs: int = 3,
                  run_dir: str | None = None,
                  ledger: bool = True,
                  deepseek_oneshot: bool = True) -> dict:
    """Run the arms serially, write dated evidence + one ledger row per arm.

    Returns {arm: summary} (+ kill-criteria verdicts)."""
    import hashlib

    from seon_inspect import scorecard

    date = time.strftime("%Y-%m-%d")
    out_dir = REPO_ROOT / "evals" / "runs" / (run_dir or f"{date}-typeahead")
    out_dir.mkdir(parents=True, exist_ok=True)
    ep = resolve_endpoint(endpoint)
    health = ep.health() if hasattr(ep, "health") else {}
    lock = REPO_ROOT / "evals" / "datasets.lock"
    lock_sha = (hashlib.sha256(lock.read_bytes()).hexdigest()
                if lock.is_file() else "absent")
    c_sha = corpus_sha()
    summaries: dict = {}
    t_run0 = time.time()
    # arm0 — the DeepSeek reference over the SAME turns (free: already
    # captured); absent replay_reply enrichment → skipped with a note.
    corpus = load_corpus()
    if any("replay_reply" in s for s in corpus["samples"]):
        ds_execs, ds_summary = deepseek_reference(corpus)
        (out_dir / "arm0_deepseek.jsonl").write_text(
            "".join(json.dumps(e, ensure_ascii=False) + "\n"
                    for e in ds_execs))
        summaries["arm0_deepseek"] = ds_summary
        print(f"[typeahead] arm0_deepseek: {json.dumps(ds_summary)}",
              flush=True)
    # arm0b — DeepSeek one-shot over the SAME arm1 renders (the clean
    # apples-to-apples same-context reference; arm0 above ran under the
    # full ~36k production render).
    import os
    if deepseek_oneshot and os.environ.get("DEEPSEEK_API_KEY"):
        os_execs, os_summary = oneshot_reference(
            corpus, name="arm0b_deepseek_oneshot",
            base_url="https://api.deepseek.com/v1",
            model="deepseek-v4-pro",
            api_key=os.environ["DEEPSEEK_API_KEY"])
        (out_dir / "arm0b_deepseek_oneshot.jsonl").write_text(
            "".join(json.dumps(e, ensure_ascii=False) + "\n"
                    for e in os_execs))
        summaries["arm0b_deepseek_oneshot"] = os_summary
        print(f"[typeahead] arm0b_deepseek_oneshot: "
              f"{json.dumps(os_summary)}", flush=True)
    for arm in arms:
        t0 = time.time()
        logs = inspect_eval(typeahead_replay(arm=arm, endpoint=endpoint,
                                             epochs=epochs),
                            model="mockllm/model", max_samples=1,
                            display="plain", log_dir=str(out_dir / "logs"))
        execs = _executions(logs[0])
        (out_dir / f"{arm}.jsonl").write_text(
            "".join(json.dumps(e, ensure_ascii=False) + "\n" for e in execs))
        summaries[arm] = _arm_summary(execs)
        if ledger:
            m = scorecard.compute_metrics(
                [{"sample_id": e["sample_id"], "epoch": e["epoch"],
                  "outcome": e["outcome"]} for e in execs])
            scorecard.append_row({
                # run_id carries the run LABEL (run_dir), not just the
                # date — two same-day runs (P5 + P6, live-hit 2026-07-11)
                # must not collide in the append-only ledger.
                "run_id": (f"{run_dir or f'{date}-typeahead'}:"
                           f"typeahead_replay:dev:k{epochs}:{arm}"),
                "row": "typeahead_replay", "tier": "dev", **m,
                "attribution": {"arm": arm, "corpus_sha": c_sha,
                                "worker_sha": health.get("worker_sha")},
                "git_sha": _git_sha(), "datasets_lock_sha": lock_sha,
                "model": "diffusiongemma",
                "model_id": "diffusiongemma-26B-A4B-it-8bit (local MLX)",
                "model_thinking": "n/a", "model_temperature": "n/a",
                "model_config_source": ("local worker /health worker_sha="
                                        + str(health.get("worker_sha"))),
                "elapsed_s": round(time.time() - t0, 1),
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ",
                                           time.gmtime()),
            })
        print(f"[typeahead] {arm}: {json.dumps(summaries[arm])}", flush=True)
    verdicts = kill_criteria(summaries)
    report = {"endpoint": endpoint, "worker_health": health,
              "corpus_sha": c_sha, "epochs": epochs,
              "elapsed_s": round(time.time() - t_run0, 1),
              "summaries": summaries, "kill_criteria": verdicts}
    (out_dir / "summary.json").write_text(json.dumps(report, indent=1))
    print(json.dumps(verdicts, indent=1), flush=True)
    return report


def kill_criteria(summaries: dict) -> dict:
    """The two design kill criteria, evaluated honestly (report-only)."""
    out: dict = {}
    a1 = summaries.get("arm1_guided", {}).get("outcome_mean")
    a2 = summaries.get("arm2_typeahead", {}).get("outcome_mean")
    a3 = summaries.get("arm3_degraded", {}).get("outcome_mean")
    if a1 is not None and a3 is not None:
        out["protocol_leak"] = {
            "degraded_minus_baseline": round(a3 - a1, 3),
            "verdict": "LEAK (menus-as-text regress baseline)"
                       if a3 < a1 - 1e-9 else "clean"}
    if a2 is not None:
        up = summaries.get("arm2_typeahead", {}).get("uptake_mean")
        gain = (a2 - max(x for x in (a1, a3) if x is not None)
                if (a1 is not None or a3 is not None) else None)
        out["dead_weight"] = {
            "uptake_mean": up, "accuracy_gain": (round(gain, 3)
                                                 if gain is not None else None),
            "verdict": "DEAD WEIGHT (uptake ~0, no accuracy gain)"
                       if ((up or 0.0) < 0.05 and (gain or 0.0) <= 0.0)
                       else "earns its render"}
    return out
