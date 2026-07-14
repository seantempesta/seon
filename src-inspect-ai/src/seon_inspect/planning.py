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

Plan snapshot row (plain dict, one per `:my.plan` step of the driven agent):

    {"id": str, "title": str,
     "status": "open" | "active" | "done" | "blocked",
     "created_at_ms": int,                 # epoch ms (:my.plan/created-at)
     "completed_at_ms": int | None,        # epoch ms (:my.plan/completed-at)
     "parent_id": str | None,              # :my.plan/parent's :my.plan/id
     "from_message": bool}                 # :my.plan/message present
                                           # (auto-minted, not agent planning)

Run wiring: `run_planning_sample` is the phase-1 → restart → phase-2 →
snapshot choreography with every effect injected as a callable, so the
sequencing and metadata assembly stay offline-testable. The live driver is
paused at the cluster boundary until the operator publishes an ownership-
fenced lease with pinned config/artifact identity and dynamic endpoints. It
fails before invoking any removed create, per-pod restart, or destroy command.
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


# Eval-source classification for the rung-2 process checks (2026-07-10).
# Plan-AUTHORING = minting the plan; plan-CLOSE = a PURE done! form (anchored
# — a form that FUSES work with its close, e.g. `(do (compute …)
# (my.plan/done! …))`, is WORK: it is the strictest possible adjacency and
# must BREAK a close run, not extend it — live finding, rung-2 d1);
# everything that is not a plan function / movement / require / lifecycle
# chatter counts as WORK.
_PLAN_AUTHOR_RE = r"(?:my\.)?plan/(?:plan!|step!)"
_PURE_CLOSE_RE = r"^\s*\((?:my\.)?plan/done!"
_NON_WORK_RE = (r"^\s*\((?:(?:my\.)?plan/\w+!?|in-ns\b|ns\b|require\b|"
                r"(?:seon\.agent\.)?message/|(?:seon\.agent\.lifecycle/)?"
                r"(?:complete|wait|pause|resume)\b)")


def _classify(row: dict[str, Any] | str) -> str:
    import re
    if isinstance(row, str):        # convenience for tests/back-compat
        row = {"source": row}
    # a PROSE-demoted eval (the reply parser recorded commentary, nothing
    # ran — its narration carries the demotion note) is neither work nor a
    # close-run breaker: pure transcript chatter.
    if "PROSE, not code" in (row.get("narration") or ""):
        return "prose"
    src = row["source"]
    if re.match(_PURE_CLOSE_RE, src):
        return "close"
    if re.search(_PLAN_AUTHOR_RE, src):
        return "author"
    if re.match(_NON_WORK_RE, src):
        return "other"
    return "work"


def check_decompose_first(evals: list[dict[str, Any]]) -> dict[str, Any]:
    """Decomposition happened BEFORE the work (rung-2 process check).

    `evals` = the agent's eval rows `{at_ms, source, ok, narration}` in
    eval order. OK iff the first successful plan-AUTHORING eval precedes
    the first successful WORK eval. No plan-authoring eval at all fails;
    work-free runs (nothing but planning) pass vacuously."""
    kinds = [(_classify(e), e) for e in evals if e.get("ok")]
    first_author = next((i for i, (k, _) in enumerate(kinds) if k == "author"),
                        None)
    first_work = next((i for i, (k, _) in enumerate(kinds) if k == "work"),
                      None)
    ok = first_author is not None and (first_work is None
                                       or first_author < first_work)
    return {"ok": ok, "first_author_idx": first_author,
            "first_work_idx": first_work}


def check_close_adjacency(evals: list[dict[str, Any]],
                          *, max_close_run: int = 2) -> dict[str, Any]:
    """Closes land as the work lands — never dumped in one batch.

    The rung-2 headline metric, measured as the batch-dump SIGNATURE: a
    run of >`max_close_run` STRICTLY CONSECUTIVE successful pure-`done!`
    evals (any other successful eval between closes — work, an `active!`,
    a `tree` check — breaks the run: the agent was doing something between
    them). The allowance of 2 covers the legitimate last-step + wrap-up
    pair. Also fails when there are no closes at all. (First cut counted
    `other`/`prose` as non-breaking and false-failed a live drive whose
    completion timestamps were spread over two minutes — the run rule now
    matches the temporal truth; rung-2 d1, 2026-07-10.)"""
    runs: list[int] = []
    cur = 0
    for e in evals:
        if not e.get("ok"):
            continue
        if _classify(e) == "close":
            cur += 1
        else:
            if cur:
                runs.append(cur)
            cur = 0
    if cur:
        runs.append(cur)
    n_closes = sum(runs)
    longest = max(runs, default=0)
    return {"ok": bool(runs) and longest <= max_close_run,
            "n_closes": n_closes,
            "longest_close_run": longest}


def check_planning(reply: str,
                   snapshot: list[dict[str, Any]],
                   t_interrupt_ms: int,
                   oracle: dict[str, Any],
                   eval_rows: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    """The full planning oracle: answer + trajectory + process checks.

    CORRECT only when the phase-2 reply carries the generation-time synthesis
    answer (`oracle["final"]`, via `tool_scorers.check_answer`), the plan
    trajectory shows resumption (`check_plan_trajectory`), AND — when
    `eval_rows` are provided (the rung-2 wiring always provides them) — the
    process checks hold: decompose-first + close-adjacency. All sub-results
    are returned for per-part attribution."""
    final = check_answer(reply, oracle["final"])
    trajectory = check_plan_trajectory(snapshot, t_interrupt_ms, oracle)
    parts = {"final": final, "trajectory": trajectory}
    if eval_rows is not None:
        parts["decompose_first"] = check_decompose_first(eval_rows)
        parts["close_adjacency"] = check_close_adjacency(eval_rows)
    return {"ok": all(bool(p["ok"]) for p in parts.values()), **parts}


# ---------------------------------------------------------------------------
# Three-arm plan-preload experiment (pilot hardening, 2026-07-14)
# ---------------------------------------------------------------------------

PLAN_ARMS = ("pretransacted", "model_authored", "no_plan")


def check_database_outcome(outcome: dict[str, Any],
                           oracle: dict[str, Any]) -> dict[str, Any]:
    """Score the staged world's result, never the plausibility of the reply.

    The live adapter is expected to derive ``outcome["answer"]`` from the
    database after the run. Keeping this check over plain data makes the
    wrong-but-plausible ferry total from the pilot an offline regression.
    """
    observed = str(outcome.get("answer", ""))
    checked = check_answer(observed, oracle)
    return {**checked, "observed": observed}


def check_plan_integrity(closes: list[dict[str, Any]],
                         *, required: bool) -> dict[str, Any]:
    """Every plan close must have its step expectation verified at close.

    The live adapter derives ``expect_verified`` from an outcome-oracle read at
    the close basis. The no-plan control has no closes and reports this metric
    as not applicable instead of receiving free plan credit.
    """
    verified = [c for c in closes if c.get("expect_verified") is True]
    applicable = bool(closes) or required
    ok = ((not closes) if not required else
          (bool(closes) and len(verified) == len(closes)))
    return {"ok": ok, "applicable": applicable,
            "verified_closes": len(verified), "closes": len(closes)}


def check_report_delivery(events: list[dict[str, Any]],
                          oracle: dict[str, Any]) -> dict[str, Any]:
    """The oracle answer reached ``message/user`` before the run closed."""
    close_indices = [i for i, e in enumerate(events)
                     if e.get("kind") == "run_closed"]
    close_idx = close_indices[0] if close_indices else None
    reports = [(i, e) for i, e in enumerate(events)
               if e.get("kind") == "message_user"]
    matching = [(i, e) for i, e in reports
                if close_idx is not None and i < close_idx
                and check_answer(str(e.get("content", "")), oracle)["ok"]]
    return {"ok": bool(close_indices) and bool(matching),
            "evidence_sufficient": bool(close_indices),
            "run_closed_observed": bool(close_indices),
            "reports": len(reports),
            "matching_before_close": len(matching)}


def check_address_step_discipline(
        evidence: dict[str, Any],
        *, plan_required: bool, plan_absent_observed: bool) -> dict[str, Any]:
    """No address step is active while an authored plan step remains open."""
    observations = evidence.get("observations")
    observations = observations if isinstance(observations, list) else []
    violations = [o for o in observations
                  if o.get("address_active") and o.get("authored_open")]
    coverage_complete = evidence.get("coverage_complete") is True
    evidence_sufficient = ((coverage_complete and bool(observations))
                           if plan_required
                           else plan_absent_observed)
    return {"ok": evidence_sufficient and not violations,
            "applicable": plan_required,
            "evidence_sufficient": evidence_sufficient,
            "coverage_complete": coverage_complete,
            "observations": len(observations),
            "violations": len(violations)}


def check_plan_arm_evidence(arm: str,
                            evidence: dict[str, Any]) -> dict[str, Any]:
    """Derive plan presence, provenance, and timing from database evidence.

    Live adapters must supply this plain-data record from one bounded database
    read after the run:

    ``observed``
        True only when the plan-root query completed.
    ``observed_at_t`` / ``first_turn_t``
        Database transaction coordinates for the observation and first turn.
    ``plan_present``
        Explicit result of the root-existence query; must agree with ``roots``.
    ``agent_eid``
        Driven agent entity id.
    ``harness_plan_tx_ids``
        Transaction ids returned by the harness's pre-turn plan transaction.
    ``roots``
        Every observed root as ``{id, creation_t, creation_tx_id,
        creation_user_eid}``, where creation provenance comes from transaction
        metadata rather than an arm label.
    ``history_observed`` / ``run_historical_root_ids``
        True only when a Datahike history read covered the inclusive interval
        ``[first_turn_t, observed_at_t]``; ids are the union of roots present in
        the as-of database at ``first_turn_t`` and roots asserted or retracted
        through ``observed_at_t``.
    ``run_root_creation_count`` / ``run_root_creation_tx_ids``
        Count and transaction ids of root creations asserted inside that same
        interval. Creation transaction provenance is the root row's
        ``creation_user_eid`` plus transaction id; retraction cannot erase it.

    Source is derived here: a root created by a recorded harness transaction is
    ``harness``; otherwise a root whose creation transaction user is the driven
    agent is ``model``. No caller-supplied ``plan_source`` is accepted.
    """
    roots = evidence.get("roots")
    roots = roots if isinstance(roots, list) else []
    observed = evidence.get("observed") is True
    explicit_present = evidence.get("plan_present")
    presence_consistent = (isinstance(explicit_present, bool)
                           and explicit_present == bool(roots))
    first_turn_t = evidence.get("first_turn_t")
    observed_at_t = evidence.get("observed_at_t")
    coordinates_valid = (isinstance(first_turn_t, int)
                         and isinstance(observed_at_t, int)
                         and observed_at_t >= first_turn_t)
    harness_tx_rows = evidence.get("harness_plan_tx_ids")
    harness_valid = (isinstance(harness_tx_rows, list)
                     and all(isinstance(tx, int) for tx in harness_tx_rows))
    harness_txs = set(harness_tx_rows) if harness_valid else set()
    agent_eid = evidence.get("agent_eid")
    agent_valid = isinstance(agent_eid, int)
    history_observed = evidence.get("history_observed") is True
    historical_root_ids = evidence.get("run_historical_root_ids")
    historical_ids_valid = (isinstance(historical_root_ids, list)
                            and all(isinstance(root_id, str)
                                    for root_id in historical_root_ids)
                            and len(set(historical_root_ids))
                            == len(historical_root_ids))
    run_creation_count = evidence.get("run_root_creation_count")
    run_creation_tx_ids = evidence.get("run_root_creation_tx_ids")
    run_creation_valid = (isinstance(run_creation_count, int)
                          and run_creation_count >= 0
                          and isinstance(run_creation_tx_ids, list)
                          and all(isinstance(tx, int)
                                  for tx in run_creation_tx_ids)
                          and len(set(run_creation_tx_ids))
                          == len(run_creation_tx_ids)
                          and run_creation_count
                          == len(run_creation_tx_ids))

    def source(root: dict[str, Any]) -> str:
        if root.get("creation_tx_id") in harness_txs:
            return "harness"
        if root.get("creation_user_eid") == agent_eid:
            return "model"
        return "unknown"

    root_maps = all(isinstance(root, dict) for root in roots)
    sources = [source(root) for root in roots] if root_maps else ["unknown"]
    creation_ts = ([root.get("creation_t") for root in roots]
                   if root_maps else [])
    roots_valid = (root_maps
                   and all(isinstance(root.get("id"), str)
                           and isinstance(root.get("creation_t"), int)
                           and isinstance(root.get("creation_tx_id"), int)
                           and isinstance(root.get("creation_user_eid"), int)
                           for root in roots))
    roots_observed_by_basis = (coordinates_valid and roots_valid
                               and all(t <= observed_at_t for t in creation_ts))
    final_interval_creation_tx_ids = (
        {root["creation_tx_id"] for root in roots
         if first_turn_t <= root["creation_t"] <= observed_at_t}
        if roots_valid and coordinates_valid else set())
    interval_creations_consistent = (
        run_creation_valid
        and final_interval_creation_tx_ids.issubset(set(run_creation_tx_ids)))
    evidence_sufficient = (observed and presence_consistent
                           and coordinates_valid and roots_valid
                           and harness_valid and agent_valid
                           and history_observed and historical_ids_valid
                           and run_creation_valid and roots_observed_by_basis
                           and interval_creations_consistent)
    root_ids = {root["id"] for root in roots} if roots_valid else set()
    history_covers_final = (historical_ids_valid
                            and root_ids.issubset(set(historical_root_ids)))

    if not evidence_sufficient:
        contract = False
    elif arm == "pretransacted":
        contract = (bool(roots) and all(s == "harness" for s in sources)
                    and all(t < first_turn_t for t in creation_ts)
                    and all(t <= observed_at_t for t in creation_ts)
                    and history_covers_final)
    elif arm == "model_authored":
        contract = (bool(roots) and all(s == "model" for s in sources)
                    and all(first_turn_t <= t <= observed_at_t
                            for t in creation_ts)
                    and history_covers_final
                    and set(root["creation_tx_id"] for root in roots)
                    .issubset(set(run_creation_tx_ids)))
    else:
        contract = (explicit_present is False and not roots
                    and historical_root_ids == []
                    and run_creation_count == 0
                    and run_creation_tx_ids == [])
    return {"ok": evidence_sufficient and contract,
            "evidence_sufficient": evidence_sufficient,
            "plan_present": explicit_present,
            "presence_consistent": presence_consistent,
            "sources": sources,
            "creation_ts": creation_ts,
            "first_turn_t": first_turn_t,
            "observed_at_t": observed_at_t,
            "history_observed": history_observed,
            "run_historical_root_ids": historical_root_ids,
            "run_root_creation_count": run_creation_count,
            "run_root_creation_tx_ids": run_creation_tx_ids,
            "roots_observed_by_basis": roots_observed_by_basis,
            "interval_creations_consistent": interval_creations_consistent}


def check_plan_experiment(
    arm: str,
    database_outcome: dict[str, Any],
    oracle: dict[str, Any],
    *,
    plan_closes: list[dict[str, Any]],
    report_events: list[dict[str, Any]],
    address_evidence: dict[str, Any],
    plan_evidence: dict[str, Any],
) -> dict[str, Any]:
    """Mechanical verdict for the measured preloaded/authored/control arms.

    ``pretransacted`` requires a harness-authored plan already present before
    turn one; ``model_authored`` requires the model to author it; ``no_plan``
    requires the absence of a plan. Outcome and report delivery gate every arm.
    Plan integrity and address-step discipline gate only the two plan arms.
    """
    if arm not in PLAN_ARMS:
        raise ValueError(f"unknown planning arm {arm!r}; expected {PLAN_ARMS}")
    required_plan = arm != "no_plan"
    arm_contract = check_plan_arm_evidence(arm, plan_evidence)
    plan_absent_observed = (arm_contract["evidence_sufficient"]
                            and arm_contract["plan_present"] is False)
    parts = {
        "database_outcome": check_database_outcome(database_outcome, oracle),
        "plan_integrity": check_plan_integrity(plan_closes,
                                                required=required_plan),
        "report_delivery": check_report_delivery(report_events, oracle),
        "address_step_discipline": check_address_step_discipline(
            address_evidence, plan_required=required_plan,
            plan_absent_observed=plan_absent_observed),
        "arm_contract": arm_contract,
    }
    gates = [parts["database_outcome"], parts["plan_integrity"],
             parts["report_delivery"], parts["arm_contract"]]
    if required_plan:
        gates.append(parts["address_step_discipline"])
    return {"ok": all(bool(part["ok"]) for part in gates),
            "arm": arm, **parts}


# ---------------------------------------------------------------------------
# Run wiring — phase 1 → restart → phase 2 → snapshot (effects injected)
# ---------------------------------------------------------------------------


def run_planning_sample(
    phase1_input: str,
    phase2_input: str,
    *,
    run_phase1: Callable[[str], dict[str, Any]],
    restart_pod: Callable[[], None],
    run_phase2: Callable[[str, dict[str, Any]], dict[str, Any]],
    fetch_snapshot: Callable[[dict[str, Any]], list[dict[str, Any]]],
    fetch_evals: Callable[[dict[str, Any]], list[dict[str, Any]]] | None = None,
    clock_ms: Callable[[], int] = lambda: int(time.time() * 1000),
) -> dict[str, Any]:
    """One two-phase drive; every effect is an injected callable.

    Sequence (the interruption timestamp is taken AFTER phase 1 returns and
    BEFORE the restart, so every phase-1 write is strictly pre-interrupt and
    every phase-2 write strictly post — pod and harness share the machine
    clock over loopback):

        r1 = run_phase1(phase1_input)     # POST /agents/run, durable cluster
        t  = clock_ms()                     # the interruption boundary
        restart_pod()                       # the planning cluster's pod
        r2 = run_phase2(phase2_input, r1) # SAME agent id, resumed cluster
        snapshot = fetch_snapshot(r1)       # the agent's plan-step rows

    Returns the scorer's inputs: the phase-2 reply, `t_interrupt_ms`, the
    snapshot, and both raw phase results (attribution evidence)."""
    r1 = run_phase1(phase1_input)
    t_interrupt_ms = clock_ms()
    restart_pod()
    r2 = run_phase2(phase2_input, r1)
    snapshot = fetch_snapshot(r1)
    eval_rows = fetch_evals(r1) if fetch_evals is not None else None
    return {"reply": r2.get("reply", ""),
            "t_interrupt_ms": t_interrupt_ms,
            "plan_snapshot": snapshot,
            "eval_rows": eval_rows,
            "phase1": r1,
            "phase2": r2}


# The read-back recipe `fetch_plan_snapshot` implements (executed against the
# planning cluster's db via the wire-server socket REPL, which survives the
# pod restart). One pull per agent step entity → the snapshot rows.
SNAPSHOT_QUERY_NOTE = """
;; steps of the driven agent (eid via [:seon.agent/id <agent_id>]):
[:find [?t ...] :in $ ?a :where [?t :my.plan/agent ?a]]
;; per step, pull → snapshot row (times as epoch ms, keyword name as string):
[:my.plan/id :my.plan/title :my.plan/status :my.plan/created-at
 :my.plan/completed-at {:my.plan/parent [:my.plan/id]} :my.plan/message]
"""

# The one-line Clojure form the wire REPL evaluates — SNAPSHOT_QUERY_NOTE made
# executable. Prints the rows as one WIRE-JSON sentinel line (cheshire is on
# the wire-server classpath). %s slots: db-name keyword, agent-id string.
_SNAPSHOT_FORM = (
    "(do (require (quote [cheshire.core :as json]) (quote [datahike.api :as d]))"
    " (let [conn (:seon.db.registry/conn (seon.db.registry/lookup-connection"
    " {:seon.db.registry/database-name :%s}))"
    " db (deref conn)"
    " a (d/q (quote [:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]]) db %s)"
    " ts (if a (d/q (quote [:find [?t ...] :in $ ?a :where"
    " [?t :my.plan/agent ?a]]) db a) [])"
    " rows (mapv (fn [t] (let [p (d/pull db (quote [:my.plan/id :my.plan/title"
    " :my.plan/status :my.plan/created-at :my.plan/completed-at"
    " {:my.plan/parent [:my.plan/id]} :my.plan/message]) t)]"
    " {\"id\" (:my.plan/id p)"
    "  \"title\" (:my.plan/title p)"
    "  \"status\" (name (:my.plan/status p))"
    "  \"created_at_ms\" (.getTime (:my.plan/created-at p))"
    "  \"completed_at_ms\" (some-> (:my.plan/completed-at p) (.getTime))"
    "  \"parent_id\" (get-in p [:my.plan/parent :my.plan/id])"
    "  \"from_message\" (contains? p :my.plan/message)})) ts)]"
    " (println (str \"WIRE-JSON<\" (json/generate-string rows) \">WIRE-JSON\"))))")


# The agent's eval rows for the process checks (rung 2, 2026-07-10) —
# ordered by eid (monotonic), source + ok?. Same wire-REPL door as the plan
# snapshot; survives the pod restart.
_EVALS_FORM = (
    "(do (require (quote [cheshire.core :as json]) (quote [datahike.api :as d]))"
    " (let [conn (:seon.db.registry/conn (seon.db.registry/lookup-connection"
    " {:seon.db.registry/database-name :%s}))"
    " db (deref conn)"
    " a (d/q (quote [:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]]) db %s)"
    " es (if a (d/q (quote [:find [?e ...] :in $ ?a :where"
    " [?e :seon.eval/agent ?a] [?e :seon.eval/source _]]) db a) [])"
    " rows (mapv (fn [e] (let [p (d/pull db (quote [:seon.eval/source"
    " :seon.eval/ok? :seon.eval/at :seon.eval/narration]) e)]"
    " {\"source\" (:seon.eval/source p)"
    "  \"ok\" (:seon.eval/ok? p)"
    "  \"at_ms\" (some-> (:seon.eval/at p) (.getTime))"
    "  \"narration\" (:seon.eval/narration p)}))"
    " (sort es))]"
    " (println (str \"WIRE-JSON<\" (json/generate-string rows) \">WIRE-JSON\"))))")


def fetch_eval_rows(cluster_name: str, agent_id: str) -> list[dict[str, Any]]:
    """The agent's eval rows `{source, ok, at_ms}` in eval (eid) order.

    Read over the wire-server socket REPL like the plan snapshot — the
    process-check input for `check_decompose_first` / `check_close_adjacency`."""
    from seon_inspect.cluster import wire_repl_json

    form = _EVALS_FORM % (cluster_name, json.dumps(agent_id))
    rows = wire_repl_json(form)
    if not isinstance(rows, list):
        raise RuntimeError(f"eval-rows read-back returned non-list: {rows!r}")
    return rows


def fetch_plan_snapshot(cluster_name: str, agent_id: str) -> list[dict[str, Any]]:
    """The agent's `:my.plan` step rows from the planning cluster's db.

    Read over the wire-server socket REPL (registry `get-conn` by db-name =
    the cluster name), so it works while the cluster's pod is up, restarting,
    or already stopped — the JVM registry holds the conn. Rows are the plain
    snapshot dicts `check_plan_trajectory` consumes."""
    from seon_inspect.cluster import wire_repl_json

    form = _SNAPSHOT_FORM % (cluster_name, json.dumps(agent_id))
    rows = wire_repl_json(form)
    if not isinstance(rows, list):
        raise RuntimeError(f"plan snapshot read-back returned non-list: {rows!r}")
    return rows


def pod_planning_driver(
    phase1_input: str,
    phase2_input: str,
    *,
    timeout_ms: int | None = None,
    cluster_name: str | None = None,
    evidence_root: Any = None,
    seon_config: str | None = None,
) -> dict[str, Any]:
    """The lease-dependent two-phase driver for one planning sample.

    Its first operation requests a cluster through `seon_inspect.cluster`.
    Until the operator lease exists that request raises
    `ClusterLeaseUnavailable` before a subprocess or model call.

    Returns `run_planning_sample`'s result dict plus `"cluster"` and
    `"agent_id"` (attribution evidence). Feed it to `check_planning` with the
    row's generation-time oracle."""
    from seon_inspect import cluster as cl
    from seon_inspect.config import DEFAULT_RUN_TIMEOUT_S
    from seon_inspect.solver import pod_run

    budget_ms = timeout_ms or DEFAULT_RUN_TIMEOUT_S * 1000
    # `seon_config` (e.g. config/minimal-plan.edn — the rung-2 minimal
    # context) rides BOTH the create and the mid-sample restart: a bare
    # restart would re-seed the default manifest and swap the context.
    env = {"SEON_CONFIG": seon_config} if seon_config else None
    holder: dict[str, Any] = {
        "cluster": cl.create_cluster(
            cluster_name or cl.bench_cluster_name("plan"), ephemeral=True,
            extra_env=env)}
    try:
        def run_phase1(text: str) -> dict[str, Any]:
            return pod_run(text, budget_ms, holder["cluster"].url)

        def restart() -> None:
            holder["cluster"] = cl.restart_pod(holder["cluster"],
                                               extra_env=env)

        def run_phase2(text: str, r1: dict[str, Any]) -> dict[str, Any]:
            return pod_run(text, budget_ms, holder["cluster"].url,
                           agent_id=r1["agent_id"])

        def fetch(r1: dict[str, Any]) -> list[dict[str, Any]]:
            return fetch_plan_snapshot(holder["cluster"].name, r1["agent_id"])

        def fetch_evals(r1: dict[str, Any]) -> list[dict[str, Any]]:
            return fetch_eval_rows(holder["cluster"].name, r1["agent_id"])

        result = run_planning_sample(
            phase1_input, phase2_input,
            run_phase1=run_phase1,
            restart_pod=restart,
            run_phase2=run_phase2,
            fetch_snapshot=fetch,
            fetch_evals=fetch_evals,
        )
        result["cluster"] = holder["cluster"].name
        result["agent_id"] = result["phase1"].get("agent_id")
        return result
    finally:
        if evidence_root is not None:
            # Evidence retention (2026-07-04): keep the cluster's blob store
            # (rendered prompts + verbatim replies) before it is destroyed —
            # same rule as the tool rows; a trajectory fail stays auditable.
            from pathlib import Path

            from seon_inspect.tool_rows import preserve_cluster_evidence
            preserve_cluster_evidence(
                holder["cluster"].name,
                Path(evidence_root) / holder["cluster"].name)
        cl.destroy_cluster(holder["cluster"].name)


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
        if "plan_experiment" in meta:
            experiment = meta["plan_experiment"]
            res = check_plan_experiment(
                experiment["arm"], experiment["database_outcome"],
                meta["oracle"]["final"],
                plan_closes=experiment["plan_closes"],
                report_events=experiment["report_events"],
                address_evidence=experiment["address_evidence"],
                plan_evidence=experiment["plan_evidence"],
            )
            return Score(
                value=CORRECT if res["ok"] else INCORRECT,
                explanation=json.dumps({
                    "arm": res["arm"],
                    "database_outcome_ok": res["database_outcome"]["ok"],
                    "plan_integrity": res["plan_integrity"],
                    "report_delivery": res["report_delivery"],
                    "address_step_discipline":
                        res["address_step_discipline"],
                    "arm_contract": res["arm_contract"],
                }),
                metadata=res,
            )
        res = check_planning(
            state.output.completion,
            meta["plan_snapshot"],
            meta["t_interrupt_ms"],
            meta["oracle"],
            eval_rows=meta.get("eval_rows"),
        )
        return Score(
            value=CORRECT if res["ok"] else INCORRECT,
            explanation=json.dumps({
                "final_ok": res["final"]["ok"],
                "trajectory_failures": res["trajectory"]["failures"],
                "decompose_first_ok": res.get("decompose_first", {}).get("ok"),
                "close_adjacency": {
                    k: res["close_adjacency"][k]
                    for k in ("ok", "longest_close_run")}
                if "close_adjacency" in res else None,
            }),
            metadata=res,
        )

    return score
