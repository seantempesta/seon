"""E1 — the three-arm spec'd-fn measurement as an inspect-ai task (runbook step 3).

Port of the FIXED kill-gate harness (contract-stating prompts, oracle-liveness
gate, raw persistence — see research/e1-behavioral-zero-audit-2026-07-02.md for
why each exists) onto the standard harness. One arm per eval run; compare the
arms' `accuracy` / pass^k across runs:

  arm1_guided_refine   clamp the register!/defn/:malli-schema frame, run the
                       unified-refine loop over the body (denoise->K -> parse+
                       eval -> renoise broken BODY spans -> iterate).
  arm2_naked           plain generation, no guidance, no oracle.
  arm3_naked_oracle    plain generation + the IDENTICAL post-hoc parse/eval/
                       repair pass (the honest competitor).

Scored by `ladder_scorer` (parse -> structural -> eval -> BEHAVIORAL -> vacuity;
CORRECT iff faithful). Epochs give pass^k. The oracle-liveness gate runs at task
construction and refuses a dead/lenient oracle stack.

RUN (offline, canned worker, REAL oracles — from src-inspect-ai/, package installed):
    inspect eval seon_inspect/tasks/e1_spec_fn.py@e1_spec_fn \
      -T arm=arm1_guided_refine -T endpoint=mock:guided_wins \
      --model mockllm/model --display plain
GPU: -T endpoint=runpod (env DIFFGEMMA_EP + RUNPOD_API_KEY; verify_fresh first —
runbook step 0). Raw generations persist to $SEON_E1_SAMPLES (default
./e1_inspect_samples.jsonl — auditable).
"""

from __future__ import annotations

import json
import os
import time

from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.scorer import pass_at
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect.oracle_scorers import (assert_oracle_live, evalsrv, fn_form,
                                         idiom_scorer, ladder_scorer, oracle_parse,
                                         strip_fence)
from seon_inspect.worker_endpoints import resolve_endpoint

SAMPLES_LOG = os.environ.get("SEON_E1_SAMPLES", "e1_inspect_samples.jsonl")
REFINE_K = 28
REFINE_MAX_ITERS = 4
RENOISE_STEPS = 12


def scaffold_frame(fn_name, request_spec, response_spec, arg):
    """The clamp skeleton arm1 HOLDS (mirror of scaffold/build-scaffold)."""
    prefix = ("(schema/register! ::{n}-request [:map {req}])\n"
              "(schema/register! ::{n}-response [:map {resp}])\n"
              "(defn {n}\n"
              "  {{:malli/schema [:=> [:cat ::{n}-request] ::{n}-response]}}\n"
              "  [{{::keys [{arg}]}}]\n  ").format(n=fn_name, req=request_spec,
                                                   resp=response_spec, arg=arg)
    return prefix, ")"


_CEL_PREFIX, _CEL_SUFFIX = scaffold_frame(
    "celsius->fahrenheit", "[::celsius :double]", "[::fahrenheit :double]", "celsius")

# Contract-stating prompt (the 2026-07-02 audit's context fix): the behavioral
# harness's CALLING convention is IN the prompt (load-bearing — the omission
# control scored 0/2), fair to ALL arms. It does NOT dictate the naming idiom
# (owner correction: named -request/-response is preferred, never required —
# `ladder_scorer` gates correctness, `idiom_scorer` reports style).
CELSIUS = {
    "name": "celsius->fahrenheit",
    "prompt": ("Write `celsius->fahrenheit` with a :malli/schema. It will be "
               "called as (celsius->fahrenheit {::celsius 20.0}) — ONE map "
               "argument, the `::` keyword auto-resolving to the current "
               "namespace — and must return a map like {::fahrenheit 68.0}. "
               "Use only clojure.core (no requires). "
               "Reply with ONLY a ```clojure``` block."),
    "prefix": _CEL_PREFIX,
    "suffix": _CEL_SUFFIX,
    "max_hole_tokens": 48,
    "spec": {
        "fn_name": "celsius->fahrenheit",
        # CORRECTNESS expectation only: a spec must be present (either idiom).
        "expects": {"malli_schema": True},
        "cases": [{"in": "{::celsius 0.0}", "key": "::fahrenheit", "expect": 32.0},
                  {"in": "{::celsius 100.0}", "key": "::fahrenheit", "expect": 212.0}],
    },
}

TASKS = {"celsius": CELSIUS}


def _samples():
    return [Sample(id=name, input=t["prompt"], target="faithful",
                   metadata={"task": t, "spec": t["spec"]})
            for name, t in TASKS.items()]


# --------------------------------------------------------------------------- #
# Worker plumbing (ports of the fixed e1 harness, one mechanism per concept).  #
# --------------------------------------------------------------------------- #
def worker_text(result):
    return (result.get("assembled") or result.get("text")
            or result.get("canvas_text") or result.get("middle_text") or "")


def worker_middle(result):
    return (result.get("middle_text") or result.get("text")
            or result.get("canvas_text") or "")


def assemble_clamped(t, middle):
    return t["prefix"] + strip_fence(middle).strip() + t["suffix"]


def _eval_ok(code):
    ev = evalsrv().call({"op": "eval", "code": fn_form(code), "budget-ms": 1500})
    return ev is None or bool(ev.get("ok"))


def _clean(code):
    pr = oracle_parse(code)
    return pr.get("forms", 0) >= 1 and not pr.get("errors")


def oracle_fix(ep, text, t, idx):
    """The SHARED post-hoc repair pass (arm3; identical for fairness)."""
    code = strip_fence(text)
    pr = oracle_parse(code)
    if pr.get("forms", 0) >= 1 and not pr.get("errors") and _eval_ok(code):
        return text, False
    errs = pr.get("errors") or []
    err_msg = errs[0].get("error-kind", "syntax") if errs else "eval"
    r2 = ep.call({"mode": "generate",
                  "prompt": t["prompt"] + f"\n\nThe previous attempt had a {err_msg} "
                            "error. Return a corrected, complete ```clojure``` block.",
                  "max_new_tokens": t.get("max_new_tokens", 320),
                  "_arm_hint": "repair", "_idx": idx})
    return worker_text(r2), True


def _body_region_spans(canvas, body_span):
    pr = oracle_parse(canvas)
    errs = pr.get("errors") or []
    if not body_span:
        return [e["span"] for e in errs if "span" in e]
    lo, hi = body_span
    return [e["span"] for e in errs if "span" in e and lo <= e["span"][0] < hi]


def arm1_guided_refine(ep, t, idx):
    """The unified-refine loop over the clamped scaffold (the exp6 winner)."""
    r = ep.call({"mode": "denoise_to_step", "prefix": t["prefix"],
                 "suffix": t["suffix"], "prompt": t["prompt"],
                 "denoise_steps": REFINE_K,
                 "max_new_tokens": t.get("max_new_tokens", 128),
                 "_arm_hint": "arm1", "_idx": idx})
    seed = r.get("argmax_per_position")
    canvas = r.get("canvas_text") or ""
    body_span = r.get("body_span")
    middle = worker_middle(r)
    for _ in range(REFINE_MAX_ITERS):
        code = strip_fence(assemble_clamped(t, middle))
        if _clean(code) and _eval_ok(code):
            break
        if not seed:
            break
        spans = _body_region_spans(canvas, body_span) if not _clean(code) else []
        if not spans:
            spans = [body_span] if body_span else []
        if not spans:
            break
        r = ep.call({"mode": "resume_renoise", "seed_canvas": seed,
                     "renoise_spans": spans, "prefix": t["prefix"],
                     "suffix": t["suffix"], "prompt": t["prompt"],
                     "denoise_steps": RENOISE_STEPS, "_arm_hint": "arm1", "_idx": idx})
        seed = r.get("argmax_per_position") or seed
        canvas = r.get("canvas_text") or canvas
        body_span = r.get("body_span") or body_span
        middle = worker_middle(r)
    return assemble_clamped(t, middle), r


def arm2_naked(ep, t, idx):
    r = ep.call({"mode": "generate", "prompt": t["prompt"],
                 "max_new_tokens": t.get("max_new_tokens", 320),
                 "_arm_hint": "arm2", "_idx": idx})
    return worker_text(r), r


def arm3_naked_oracle(ep, t, idx):
    r = ep.call({"mode": "generate", "prompt": t["prompt"],
                 "max_new_tokens": t.get("max_new_tokens", 320),
                 "_arm_hint": "arm3", "_idx": idx})
    text, _ = oracle_fix(ep, worker_text(r), t, idx)
    return text, r


ARMS = {"arm1_guided_refine": arm1_guided_refine,
        "arm2_naked": arm2_naked,
        "arm3_naked_oracle": arm3_naked_oracle}


@solver
def e1_arm_solver(arm: str, endpoint: str):
    """Run one E1 arm against the worker endpoint for each sample/epoch."""
    ep = resolve_endpoint(endpoint)
    arm_fn = ARMS[arm]

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        t = state.metadata["task"]
        idx = max(0, (getattr(state, "epoch", 1) or 1) - 1)
        text, raw = await anyio.to_thread.run_sync(arm_fn, ep, t, idx)
        state.output.completion = text
        state.metadata.update({"worker_sha": raw.get("worker_sha"),
                               "tok_per_s": raw.get("tok_per_s"), "arm": arm})
        with open(SAMPLES_LOG, "a") as f:  # raw persistence — auditable
            f.write(json.dumps({"ts": time.time(), "task": t["name"], "arm": arm,
                                "idx": idx, "text": text}) + "\n")
        return state

    return solve


@task
def e1_spec_fn(arm: str = "arm1_guided_refine",
               endpoint: str = "mock:guided_wins",
               epochs: int = 4):
    """One E1 arm x pass^k epochs; correctness gates, idiom reports beside it."""
    assert_oracle_live()
    return Task(
        dataset=MemoryDataset(_samples()),
        solver=e1_arm_solver(arm, endpoint),
        scorer=[ladder_scorer(), idiom_scorer()],
        epochs=Epochs(epochs, ["mean", pass_at(epochs)]),
    )
