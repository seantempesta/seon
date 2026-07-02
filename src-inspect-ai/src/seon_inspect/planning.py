"""Long-term-planning oracle + two-phase run wiring (the headline row).

The capability under measurement is CONTINUITY: the agent lays down a durable
plan, lands part of it, survives a pod restart, and RESUMES from what's still
open instead of re-planning from scratch. No public benchmark measures
plan-survives-restart, so this row is bespoke — and therefore oracle-scored
against generation-time ground truth plus the world's plan entities, never an
invented gate over the agent's narration. Reading the agent's plan entities
IS legitimate oracle input here (the one row where "did the plan survive" is
the measurement — analogous to skill-lift, where idiom adoption is the
metric). Every check below is stated verbatim in the generated task text
(`seon_inspect.generators._LTP_CONTRACT` — the load-bearing finding).

Two-part oracle, both pure data-in/data-out and offline-testable:

  1. `check_answer` (reused from `tool_scorers`) — the phase-2 reply must
     carry the synthesis value over BOTH data batches, computed at generation
     time. Only an agent whose phase-1 work survived can answer it.
  2. `check_plan_trajectory` — resumption evidence read from a PLAN SNAPSHOT
     (the agent's `:my.plan` step entities, exported as plain rows) against
     the interruption timestamp:
       - a durable plan existed BEFORE the restart (>= min_pre_steps
         agent-authored steps created pre-interrupt);
       - >= 1 pre-restart step was COMPLETED after the restart (resumed, not
         redone — the core criterion);
       - no NEW ROOT plan was created after the restart (re-planning from
         scratch fails; adding leaf steps under the existing plan is fine);
       - no pre-restart LEAF step is left unfinished (parents are excluded:
         `my.plan` derives parent done-ness, stored parent status stays
         :open by design).

Plan snapshot row (plain dict, one per `:my.plan` step of the solve agent):

    {"id": str, "title": str,
     "status": "open" | "active" | "done" | "blocked",
     "created_at_ms": int,                 # epoch ms (:my.plan/created-at)
     "completed_at_ms": int | None,        # epoch ms (:my.plan/completed-at)
     "parent_id": str | None,              # :my.plan/parent's :my.plan/id
     "from_message": bool}                 # :my.plan/message present
                                           # (auto-minted, not agent planning)

Run wiring: `run_planning_sample` is the phase-1 → restart → phase-2 →
snapshot choreography with every effect INJECTED as a callable, so the
sequencing + metadata assembly are unit-tested offline with fakes. The live
driver (`pod_planning_driver`) is a documented stub for the first dev pass —
it needs two /solve-door extensions that cannot be built offline (see its
docstring). The restart boundary runs against an ISOLATED planning cluster
(eval-design: this row restarts its pod by design).
"""

from __future__ import annotations

import json
import time
from typing import Any, Callable

from seon_inspect.tool_scorers import check_answer

# ---------------------------------------------------------------------------
# Pure checks — data in, data out
# ---------------------------------------------------------------------------


def _leaf_ids(steps: list[dict[str, Any]]) -> set[str]:
    parents = {s["parent_id"] for s in steps if s.get("parent_id")}
    return {s["id"] for s in steps if s["id"] not in parents}


def check_plan_trajectory(snapshot: list[dict[str, Any]],
                          t_interrupt_ms: int,
                          oracle: dict[str, Any]) -> dict[str, Any]:
    """Resumption evidence from the plan snapshot across the restart boundary.

    Returns `{"ok": bool, "failures": [...]}` plus the derived counts, so a
    failing sample is attributable (planned-but-lost vs never-planned vs
    re-planned). Auto-minted message steps (`from_message`) never count as
    agent planning."""
    steps = [s for s in snapshot if not s.get("from_message")]
    pre = [s for s in steps if s["created_at_ms"] < t_interrupt_ms]
    leaf = _leaf_ids(steps)
    failures: list[str] = []

    min_pre = oracle["resume"]["min_pre_steps"]
    if len(pre) < min_pre:
        failures.append(
            f"no durable pre-restart plan: {len(pre)} step(s) created before "
            f"the restart (task requires a plan of >= {min_pre} steps)")

    resumed = [s for s in pre
               if s["status"] == "done"
               and s.get("completed_at_ms") is not None
               and s["completed_at_ms"] >= t_interrupt_ms]
    if pre and not resumed:
        failures.append(
            "no pre-restart step was completed after the restart — the plan "
            "did not carry the resumption (state lost or work redone from "
            "scratch)")

    new_roots = [s for s in steps
                 if s.get("parent_id") is None
                 and s["created_at_ms"] >= t_interrupt_ms]
    if new_roots:
        failures.append(
            "re-planned from scratch: new root plan(s) created after the "
            f"restart ({', '.join(repr(s['title']) for s in new_roots)})")

    open_pre = [s for s in pre
                if s["id"] in leaf and s["status"] != "done"]
    if open_pre:
        failures.append(
            "pre-restart step(s) left unfinished: "
            + ", ".join(f"{s['title']!r} ({s['status']})" for s in open_pre))

    return {"ok": not failures,
            "failures": failures,
            "pre_steps": len(pre),
            "resumed_steps": len(resumed),
            "post_roots": len(new_roots)}


def check_planning(reply: str,
                   snapshot: list[dict[str, Any]],
                   t_interrupt_ms: int,
                   oracle: dict[str, Any]) -> dict[str, Any]:
    """The full two-part planning oracle: final answer AND trajectory.

    CORRECT only when the phase-2 reply carries the generation-time synthesis
    answer (`oracle["final"]`, via `tool_scorers.check_answer`) AND the plan
    trajectory shows resumption (`check_plan_trajectory`). Both sub-results
    are returned for per-part attribution."""
    final = check_answer(reply, oracle["final"])
    trajectory = check_plan_trajectory(snapshot, t_interrupt_ms, oracle)
    return {"ok": bool(final["ok"] and trajectory["ok"]),
            "final": final,
            "trajectory": trajectory}


# ---------------------------------------------------------------------------
# Run wiring — phase 1 → restart → phase 2 → snapshot (effects injected)
# ---------------------------------------------------------------------------


def run_planning_sample(
    phase1_input: str,
    phase2_input: str,
    *,
    solve_phase1: Callable[[str], dict[str, Any]],
    restart_pod: Callable[[], None],
    solve_phase2: Callable[[str, dict[str, Any]], dict[str, Any]],
    fetch_snapshot: Callable[[dict[str, Any]], list[dict[str, Any]]],
    clock_ms: Callable[[], int] = lambda: int(time.time() * 1000),
) -> dict[str, Any]:
    """One two-phase drive; every effect is an injected callable.

    Sequence (the interruption timestamp is taken AFTER phase 1 returns and
    BEFORE the restart, so every phase-1 write is strictly pre-interrupt and
    every phase-2 write strictly post — pod and harness share the machine
    clock over loopback):

        r1 = solve_phase1(phase1_input)     # pod /solve, keep-world
        t  = clock_ms()                     # the interruption boundary
        restart_pod()                       # the planning cluster's pod
        r2 = solve_phase2(phase2_input, r1) # SAME agent id, resumed world
        snapshot = fetch_snapshot(r1)       # the agent's plan-step rows

    Returns the scorer's inputs: the phase-2 reply, `t_interrupt_ms`, the
    snapshot, and both raw phase results (attribution evidence)."""
    r1 = solve_phase1(phase1_input)
    t_interrupt_ms = clock_ms()
    restart_pod()
    r2 = solve_phase2(phase2_input, r1)
    snapshot = fetch_snapshot(r1)
    return {"reply": r2.get("reply", ""),
            "t_interrupt_ms": t_interrupt_ms,
            "plan_snapshot": snapshot,
            "phase1": r1,
            "phase2": r2}


# The read-back recipe the live snapshot fetcher implements (documentation —
# executed against the planning cluster's world, e.g. via the wire-server
# socket REPL). One pull per solve-agent step entity → the snapshot rows.
SNAPSHOT_QUERY_NOTE = """
;; steps of the solve agent (eid via [:seon.agent/id <agent_id>]):
[:find [?t ...] :in $ ?a :where [?t :my.plan/agent ?a]]
;; per step, pull → snapshot row (times as epoch ms, keyword name as string):
[:my.plan/id :my.plan/title :my.plan/status :my.plan/created-at
 :my.plan/completed-at {:my.plan/parent [:my.plan/id]} :my.plan/message]
"""


def pod_planning_driver(*_args: Any, **_kwargs: Any) -> None:
    """STUB — the live two-phase /solve driver lands with the first dev pass.

    Cannot be built offline: today's `POST /solve` (`solve-once!` in
    `seon.web.serve`) mints a per-sample SCRATCH agent on an isolated
    `:memory` conn and restores the live world afterwards — nothing survives
    the call, so a restart boundary is meaningless against it. The dev pass
    needs, on the ISOLATED planning cluster only:

      1. a durable-world solve variant: run the sample against the cluster's
         real store (no scratch swap) and accept an existing `agent_id` so
         phase 2 resumes the SAME agent after `bin/seon restart pod`;
      2. a plan read-back: fetch the solve agent's `:my.plan` step rows as
         the snapshot (SNAPSHOT_QUERY_NOTE — e.g. via the wire-server socket
         REPL, which survives the pod restart).

    Wire those into `run_planning_sample`'s injected callables; the scorer
    and choreography here are already final."""
    raise NotImplementedError(
        "live two-phase planning driver — first dev pass; see docstring")


# ---------------------------------------------------------------------------
# inspect @scorer wrapper
# ---------------------------------------------------------------------------

from inspect_ai.scorer import (CORRECT, INCORRECT, Score, Scorer,  # noqa: E402
                               Target, accuracy, scorer)
from inspect_ai.solver import TaskState  # noqa: E402


@scorer(metrics=[accuracy()])
def planning_scorer() -> Scorer:
    """Score a long_term_planning sample: final answer + resumption evidence.

    Requires the run wiring to have set `state.metadata["plan_snapshot"]` and
    `state.metadata["t_interrupt_ms"]` (from `run_planning_sample`) and the
    phase-2 reply as the completion. CORRECT iff both oracle parts hold."""

    async def score(state: TaskState, target: Target) -> Score:
        meta = state.metadata or {}
        res = check_planning(
            state.output.completion,
            meta["plan_snapshot"],
            meta["t_interrupt_ms"],
            meta["oracle"],
        )
        return Score(
            value=CORRECT if res["ok"] else INCORRECT,
            explanation=json.dumps({
                "final_ok": res["final"]["ok"],
                "trajectory_failures": res["trajectory"]["failures"],
            }),
            metadata=res,
        )

    return score
