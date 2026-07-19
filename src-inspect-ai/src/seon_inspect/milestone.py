"""Capability-milestone oracles — the minimal-context build-up drives, ported.

The context-rebuild milestone drives (`evals/runs/2026-07-10-minimal-buildup/`)
were run by a bespoke shell lineage (`tools/min-drive.sh` + Python transcript
oracles) that this module RETIRES into the standard bench. Each drive POSTed a
contract to a cluster pod, then scored the RENDERED transcript with a regex
scanner. That scanner is replaced here by a pure oracle over the agent's
STRUCTURED eval rows — the same `{source, ok, at, narration}` projection the
pod composition door returns from its final immutable database value.
Structured rows, not scraped text, are the bench's native data.

Milestones covered here (the `plan` milestone is `seon_inspect.planning` —
already first-class; do NOT duplicate it):

  - `namespaces` (ns-move drive) — `check_ns_movement`: move to new nses,
    register a schema after moving, add a dep with a bare REPL `require`,
    redefine a fn IN PLACE (no parallel `-v2` fork), report the computed
    values. Ported from `tools/ns-move-oracle.py`.
  - `db` (db-memory drive) — `check_store_recall`: register+transact in one
    eval, then `db/query` it back in a LATER eval, then report the recalled
    answer. Ported from `tools/db-memory-oracle.py`.

`count_fabrication` ports the fabrication-analysis logic (`tools/fab-analyze.py`
+ `fab-summary.py`) — a REPLY-ONLY signature scan. Per the scorers-gate-
correctness rule (and the anti-fabrication no-hacks history), fabrication is a
REPORTED METRIC / attribution signal, never a correctness gate: a milestone is
CORRECT iff its capability oracle holds; the fab count rides in metadata.

Every check is pure data-in/data-out and offline-testable; the live driver
(`pod_milestone_driver`) binds the effects to one explicitly provisioned
static cluster through `pod_run`; the same response carries eval evidence
(no writer-REPL or cluster lifecycle side path).
"""

from __future__ import annotations

import re
import time
from typing import Any, Callable

# ---------------------------------------------------------------------------
# The milestone contracts (goal-stated task text — the same wording the drives
# used, MINUS the run-canary comment: fixed-dataset bench tasks like this one
# and `ladder_lift` do not carry freeze test-tier canaries, and a lock canary
# in package source would (rightly) trip tests/test_canary_guard.py).
# ---------------------------------------------------------------------------

NS_MOVEMENT_CONTRACT = (
    "You are going to organize a small unit-conversion library across "
    "namespaces, then use it from your home namespace. Work through these "
    "phases IN ORDER, and narrate each step briefly in `;` comments.\n\n"
    "Phase 1 — a SCHEMA namespace. Move to a new namespace `my.units` (it "
    "does not exist yet — moving there creates it). There, register this "
    "schema:\n\n"
    "- `(schema/register! :my.units/name [:string {:seon.db/identity true}])`\n"
    "- `(schema/register! :my.units/meters :double)`\n\n"
    "Phase 2 — a FUNCTIONS namespace. Move to a new namespace `my.convert`. "
    "Define:\n\n"
    "- `(defn to-feet [m] (* m 3.28))` — a first draft; you will refine it "
    "later.\n"
    "- A helper `label` that upper-cases a unit name. For this you need "
    "`clojure.string`: add the dependency FROM THE REPL with a bare "
    "`(require '[clojure.string :as str])` while you are in `my.convert`, then "
    "`(defn label [s] (str/upper-case s))`.\n\n"
    "Phase 3 — USE from home. Return to your home namespace. Transact these "
    "three rows (`:my.units/name` + `:my.units/meters`): span-a 10.0, span-b "
    "25.5, span-c 7.0. Query the meters back with a `db/query` and sum them "
    "FROM the query result.\n\n"
    "Phase 4 — REFINE in place. The draft factor is too coarse: the precise "
    "factor is 3.28084. Go BACK to `my.convert`, redefine `to-feet` in place "
    "with the precise factor (redefining IS updating — do not create a "
    "differently-named function or namespace), and return home.\n\n"
    "Phase 5 — REPORT. From your home namespace, convert the summed meters "
    "with `my.convert/to-feet` (call it fully qualified or via a require — "
    "your choice), then report to your human with `message/user` stating the "
    "total meters and the total feet (round to 2 decimals), and end with "
    "`complete` stating both numbers again."
)

DB_MEMORY_CONTRACT = (
    "You are keeping a small expedition knowledge base in your database. Work "
    "in TWO phases, in SEPARATE turns — do not do both in one turn.\n\n"
    "Phase 1 — STORE (this turn). Design a schema for supply caches and "
    "register each attribute with `schema/register!`, e.g.:\n\n"
    "- `(schema/register! :my.cache/name [:string {:seon.db/identity true}])`\n"
    "- `(schema/register! :my.cache/contents :string)`\n"
    "- `(schema/register! :my.cache/weight-kg :double)`\n\n"
    "Then persist these four facts with ONE `db/transact!` call, e.g. "
    "`(db/transact! [{:my.cache/name \"...\" :my.cache/contents \"...\" "
    ":my.cache/weight-kg 0.0} ...])`:\n\n"
    "- cache KESTREL holds 42.5 kg of dried fish\n"
    "- cache MARMOT holds 17.0 kg of pemmican\n"
    "- cache TERN holds 8.25 kg of tea\n"
    "- cache PLOVER holds 3.75 kg of salt\n\n"
    "Phase 2 — RECALL (a LATER turn, only after you have SEEN the real "
    "interleaved result line for your transact). Answer this question by "
    "running a `db/query` Datalog query against the database — compute the "
    "answer FROM the query result, do not re-derive it from this prompt: "
    "**what is the TOTAL weight in kg of all caches strictly heavier than 10 "
    "kg?** For example `(db/query '[:find (sum ?w) . :with ?e :where "
    "[?e :my.cache/weight-kg ?w] ...])`. Then report the total to your human "
    "with `message/user` and end the task with `complete`, stating the total "
    "in both."
)

# ---------------------------------------------------------------------------
# Structured eval-row helpers (rows = the fetch_eval_rows shape:
#   {"source": str, "ok": bool, "at_ms": int, "narration": str}, eid-ordered)
# ---------------------------------------------------------------------------


def _ok_indices(rows: list[dict[str, Any]], pat: str) -> list[int]:
    """Indices of OK eval rows whose source matches `pat` (in eval order)."""
    rx = re.compile(pat)
    return [i for i, r in enumerate(rows)
            if r.get("ok") and rx.search(r.get("source") or "")]


class EvidenceError(ValueError):
    """A bounded door evidence value is absent or structurally invalid."""


def _decode_evidence_value(node: Any) -> Any:
    """Decode the door's lossless tagged JSON tree, failing closed."""
    if not isinstance(node, dict) or not isinstance(node.get("kind"), str):
        raise EvidenceError("evidence value is not tagged")
    kind = node["kind"]
    if kind in {"keyword", "symbol", "scalar"}:
        if set(node) != {"kind", "value"}:
            raise EvidenceError("scalar evidence shape is invalid")
        return node["value"]
    if kind in {"vector", "list", "set"}:
        if set(node) != {"kind", "items"} or not isinstance(node["items"], list):
            raise EvidenceError("collection evidence shape is invalid")
        return [_decode_evidence_value(item) for item in node["items"]]
    if kind == "map":
        if set(node) != {"kind", "entries"} or not isinstance(node["entries"], list):
            raise EvidenceError("map evidence shape is invalid")
        result: dict[Any, Any] = {}
        for entry in node["entries"]:
            if not isinstance(entry, dict) or set(entry) != {"key", "value"}:
                raise EvidenceError("map entry evidence shape is invalid")
            key = _decode_evidence_value(entry["key"])
            try:
                duplicate = key in result
            except TypeError as exc:
                raise EvidenceError("map evidence key is not scalar") from exc
            if duplicate:
                raise EvidenceError("map evidence contains a duplicate key")
            result[key] = _decode_evidence_value(entry["value"])
        return result
    raise EvidenceError(f"unsupported evidence kind {kind!r}")


def _coordinate_at_or_before(point: Any, final: Any) -> bool:
    keys = ("database_id", "branch", "commit_id", "t")
    return (isinstance(point, dict) and isinstance(final, dict)
            and all(k in point and k in final for k in keys)
            and point["database_id"] == final["database_id"]
            and point["branch"] == final["branch"]
            and isinstance(point["commit_id"], str)
            and bool(point["commit_id"])
            and isinstance(final["commit_id"], str)
            and bool(final["commit_id"])
            and isinstance(point["t"], int) and isinstance(final["t"], int)
            and point["t"] <= final["t"])


def _ordered_proof_rows(eval_rows: list[dict[str, Any]],
                        final_coordinate: Any,
                        turn_ids: set[str] | None) -> list[dict[str, Any]]:
    if not isinstance(final_coordinate, dict) or not turn_ids:
        raise EvidenceError("final coordinate or request turn membership absent")
    if any(not isinstance(row.get("eval_transaction"), int)
           or row.get("turn_id") not in turn_ids for row in eval_rows):
        raise EvidenceError("eval order or request turn membership invalid")
    ordered = sorted(eval_rows, key=lambda row: row["eval_transaction"])
    if len({row["eval_transaction"] for row in ordered}) != len(ordered):
        raise EvidenceError("eval transaction order is not unique")
    if ordered != eval_rows:
        raise EvidenceError("eval evidence is not in transaction order")
    return ordered


def _operations(row: dict[str, Any], final_coordinate: dict[str, Any]) -> list[dict[str, Any]]:
    evidence = row.get("operation_evidence")
    if not isinstance(evidence, dict) or evidence.get("status") != "inline":
        raise EvidenceError("operation evidence is absent or not inline")
    operations = evidence.get("operations")
    if not isinstance(operations, list):
        raise EvidenceError("operation vector absent")
    for position, operation in enumerate(operations):
        if (not isinstance(operation, dict)
                or operation.get("position") != position
                or operation.get("source") != ":seon.db.read.source/captured"
                or operation.get("coordinate_valid") is not True
                or not _coordinate_at_or_before(
                    operation.get("coordinate"), final_coordinate)):
            raise EvidenceError("operation position/source/coordinate invalid")
    return operations


# ---------------------------------------------------------------------------
# namespaces milestone — ported from tools/ns-move-oracle.py
# ---------------------------------------------------------------------------


def _reply_has_number(reply: str, expected: str) -> bool:
    """Whether reply contains a numeric token equal to `expected`."""
    try:
        value = float(expected)
    except (TypeError, ValueError):
        return str(expected) in (reply or "")
    return any(abs(float(token) - value) < 1e-9
               for token in re.findall(r"(?<![\w.])-?\d+(?:\.\d+)?", reply or ""))


def check_ns_movement(eval_rows: list[dict[str, Any]],
                      reply: str,
                      oracle: dict[str, Any] | None = None) -> dict[str, Any]:
    """The `namespaces` milestone oracle: move / register / require / redefine.

    OK iff ALL checks hold (each returned for per-part attribution):
      - movement           moved into BOTH my.units and my.convert (in-ns or
                           an (ns …) declare-and-move — the observer reads
                           which off the source);
      - schema_after_move  an ok `register!` of :my.units/name AFTER first
                           entering my.units;
      - bare_require       an ok bare `(require '[clojure.string …])`;
      - redefine_in_place  >= 2 ok `(defn to-feet …)`; the LAST carries the
                           precise 3.28084 factor;
      - no_parallel_fork   no `-v2`/`2` fork of the fn or ns anywhere;
      - report_values      the delivered reply states 42.5 m and 139.4x ft
                           (runtime-computed, never model-typed).
    """
    oracle = oracle or {}
    schema_ns = oracle.get("schema_namespace", "my.units")
    function_ns = oracle.get("function_namespace", "my.convert")
    schema_attr = oracle.get("schema_attr", ":my.units/name")
    function_name = oracle.get("function_name", "to-feet")
    dependency_ns = oracle.get("dependency_namespace", "clojure.string")
    precise = oracle.get("precise_literal", "3.28084")
    source_total = oracle.get("source_total", "42.5")
    converted_total = oracle.get("converted_total", "139.44")

    schema_move = re.escape(schema_ns)
    function_move = re.escape(function_ns)
    units_moves = _ok_indices(
        eval_rows, rf"\(in-ns\s+'{schema_move}\)|\(ns\s+{schema_move}[\s)]")
    convert_moves = _ok_indices(
        eval_rows, rf"\(in-ns\s+'{function_move}\)|\(ns\s+{function_move}[\s)]")

    regs = _ok_indices(eval_rows, rf"register!\s+{re.escape(schema_attr)}")
    defs = _ok_indices(eval_rows, rf"\(defn\s+{re.escape(function_name)}(?:\s|\[)")
    last_def_src = eval_rows[defs[-1]]["source"] if defs else ""

    all_src = "\n".join(r.get("source") or "" for r in eval_rows)
    reply = reply or ""

    checks = {
        "movement": bool(units_moves) and bool(convert_moves),
        "schema_after_move": (bool(regs) and bool(units_moves)
                              and regs[0] >= units_moves[0]),
        "bare_require": bool(_ok_indices(
            eval_rows, rf"\(require\s+'\[{re.escape(dependency_ns)}")),
        "redefine_in_place": len(defs) >= 2 and precise in last_def_src,
        "no_parallel_fork": not re.search(
            rf"{re.escape(function_name)}(?:-v?2|2)|"
            rf"{re.escape(function_ns)}(?:-v?2|2)", all_src),
        "report_values": (_reply_has_number(reply, source_total)
                          and _reply_has_number(reply, converted_total)),
    }
    failures = [k for k, v in checks.items() if not v]
    return {"ok": not failures, "checks": checks, "failures": failures}


# ---------------------------------------------------------------------------
# db milestone — ported from tools/db-memory-oracle.py
# ---------------------------------------------------------------------------

def check_store_recall(eval_rows: list[dict[str, Any]],
                       reply: str,
                       oracle: dict[str, Any] | None = None,
                       final_coordinate: dict[str, Any] | None = None,
                       turn_ids: set[str] | None = None) -> dict[str, Any]:
    """The `db` milestone oracle: store, then RECALL it in a later eval.

    OK iff the retained eval evidence proves the requested workflow shape:
      - schema_register both declared schemas have the requested types and the
                        identity is unique, before storage;
      - transact       one ok transaction source contains every requested row;
      - query_later    a later ok query names the measure, strict predicate,
                       and threshold;
      - report_human   a later human report states the answer;
      - complete       a later completion states the answer; and
      - answer         the delivered reply states the answer.
    The reply IS the agent's delivered message/complete content (pod_run
    surfaces it), so the answer check is over the reply text directly.
    """
    proof_required = bool(oracle)
    oracle = oracle or {}
    identity_attr = oracle.get("identity_attr", ":my.cache/name")
    measure_attr = oracle.get("measure_attr", ":my.cache/weight-kg")
    measure_type = oracle.get(
        "measure_type",
        ":int" if oracle and all(
            isinstance(record.get("measure"), int)
            and not isinstance(record.get("measure"), bool)
            for record in oracle.get("records", [])) else ":double")
    records = oracle.get("records", [
        {"identity": "KESTREL", "measure": 42.5},
        {"identity": "MARMOT", "measure": 17.0},
        {"identity": "TERN", "measure": 8.25},
        {"identity": "PLOVER", "measure": 3.75},
    ])
    threshold = str(oracle.get("threshold", "10"))
    expected = oracle.get("answer", "59.5")
    identity_regs = _ok_indices(
        eval_rows, rf"register!\s+{re.escape(identity_attr)}")
    measure_regs = _ok_indices(
        eval_rows, rf"register!\s+{re.escape(measure_attr)}")
    identity_schema = [
        i for i in identity_regs
        if (":string" in (eval_rows[i].get("source") or "")
            and re.search(
                r":seon\.db/identity\s+true|:db\.unique/identity",
                eval_rows[i].get("source") or ""))
    ]
    measure_schema = [
        i for i in measure_regs
        if measure_type in (eval_rows[i].get("source") or "")
    ]
    tx = []
    for i in _ok_indices(eval_rows, r"db/transact!|seon\.db/transact!"):
        source = eval_rows[i].get("source") or ""
        complete = (identity_attr in source and measure_attr in source
                    and all(str(record["identity"]) in source
                            and _reply_has_number(
                                source, str(record["measure"]))
                            for record in records))
        if complete:
            tx.append(i)
    transact_idx = tx[0] if tx else None
    query_idx = None
    if transact_idx is not None:
        later = [i for i in _ok_indices(eval_rows, r"db/query|seon\.db/query")
                 if (i > transact_idx
                     and measure_attr in (eval_rows[i].get("source") or "")
                     and ">" in (eval_rows[i].get("source") or "")
                     and _reply_has_number(
                         eval_rows[i].get("source") or "", threshold))]
        query_idx = later[0] if later else None
    schema_indices = identity_schema + measure_schema
    schema_before = (bool(identity_schema) and bool(measure_schema)
                     and transact_idx is not None
                     and max(schema_indices) < transact_idx)
    report_indices = ([] if query_idx is None else [
        i for i in _ok_indices(
            eval_rows, r"(?:seon\.agent\.)?message/user")
        if (i > query_idx and _reply_has_number(
            eval_rows[i].get("source") or "", expected))
    ])
    complete_indices = ([] if query_idx is None else [
        i for i in _ok_indices(
            eval_rows, r"(?:seon\.agent\.lifecycle/)?complete\b")
        if (i > query_idx and _reply_has_number(
            eval_rows[i].get("source") or "", expected))
    ])
    answer = _reply_has_number(reply, expected)

    operation_proof = not proof_required
    if proof_required:
        try:
            ordered = _ordered_proof_rows(
                eval_rows, final_coordinate, turn_ids)
            tx_row = ordered[transact_idx] if transact_idx is not None else None
            query_row = ordered[query_idx] if query_idx is not None else None
            if tx_row is None or query_row is None:
                raise EvidenceError("source workflow rows absent")
            tx_ops = _operations(tx_row, final_coordinate or {})
            query_ops = _operations(query_row, final_coordinate or {})
            tx_op = next(
                operation for operation in tx_ops
                if operation.get("operation") ==
                ":seon.db.read.operation/transact")
            query_op = next(
                operation for operation in query_ops
                if operation.get("operation") ==
                ":seon.db.read.operation/query")
            tx_request = _decode_evidence_value(tx_op.get("request"))
            tx_result = _decode_evidence_value(tx_op.get("result"))
            query_request = _decode_evidence_value(query_op.get("request"))
            query_result = _decode_evidence_value(query_op.get("result"))
            tx_rows = tx_request.get(":seon.db/tx-data", [])
            expected_pairs = {
                (str(record["identity"]), record["measure"])
                for record in records
            }
            actual_pairs = {
                (str(row.get(identity_attr)), row.get(measure_attr))
                for row in tx_rows if isinstance(row, dict)
            }
            query_form = query_request.get(":seon.db/query", [])
            query_text = " ".join(str(item) for item in query_form)
            operation_proof = (
                tx_op.get("ok") is True
                and isinstance(tx_result, dict)
                and tx_result.get(":seon.db/ok?") is True
                and len(tx_rows) == len(records)
                and actual_pairs == expected_pairs
                and query_op.get("ok") is True
                and measure_attr in query_text
                and ">" in query_text
                and _reply_has_number(query_text, threshold)
                and query_result == expected
                and tx_op["coordinate"]["t"]
                <= query_op["coordinate"]["t"]
                and tx_row["eval_transaction"]
                < query_row["eval_transaction"])
        except (EvidenceError, KeyError, StopIteration, TypeError, ValueError):
            operation_proof = False

    checks = {"schema_register": schema_before,
              "transact": transact_idx is not None,
              "query_later": query_idx is not None,
              "operation_evidence": operation_proof,
              "report_human": bool(report_indices),
              "complete": bool(complete_indices),
              "answer": answer}
    failures = [k for k, v in checks.items() if not v]
    return {"ok": not failures, "checks": checks, "failures": failures,
            "transact_idx": transact_idx, "query_idx": query_idx}


# One capability oracle per milestone id — the driver/scorer dispatch table.
MILESTONE_ORACLES: dict[
    str, Callable[..., dict[str, Any]]
] = {
    "namespaces": check_ns_movement,
    "db": check_store_recall,
}


def check_milestone(milestone: str, eval_rows: list[dict[str, Any]],
                    reply: str,
                    oracle: dict[str, Any] | None = None,
                    final_coordinate: dict[str, Any] | None = None,
                    turn_ids: set[str] | None = None) -> dict[str, Any]:
    """Score a milestone by id; raises on an unknown id (never a silent pass)."""
    try:
        oracle_fn = MILESTONE_ORACLES[milestone]
    except KeyError:
        raise ValueError(
            f"unknown milestone {milestone!r} (known: "
            f"{sorted(MILESTONE_ORACLES)}; the `plan` milestone is "
            "seon_inspect.planning)")
    if milestone == "db":
        return oracle_fn(eval_rows, reply, oracle=oracle,
                         final_coordinate=final_coordinate,
                         turn_ids=turn_ids)
    return oracle_fn(eval_rows, reply, oracle=oracle)


# ---------------------------------------------------------------------------
# Fabrication metric — REPLY-ONLY signature scan (report-only, NEVER a gate)
# ported from tools/fab-analyze.py + fab-summary.py
# ---------------------------------------------------------------------------

_GLYPHS = ("⟹", "⟸")  # ⟹ ⟸ — reserved runtime result markers
_FAKE_ENV = re.compile(r";;?=>\s*[\{\"]")
_FAKE_OK = re.compile(r":seon\.agent\.\w+/ok\?")
_PYTEST = re.compile(
    r"\b\d+\s+passed\b|\b\d+\s+failed\b|collected\s+\d+\s+item|"
    r"passed in \d|failed in \d|PASSED|FAILED|test session starts")
_PASS_CLAIM = re.compile(
    r"(?i)(all\s+\d*\s*tests?\s+pass|tests?\s+(are\s+)?(now\s+)?green|"
    r"\d+\s*/\s*\d+\s+(tests?\s+)?pass|all\s+pass)")


def count_fabrication(reply: str) -> dict[str, Any]:
    """Fabrication signatures in ONE model-authored reply (report-only).

    A reply FABRICATES a result when it types a reserved runtime glyph
    (⟹/⟸) or a fake result-envelope echo (`;;=> {…}` / a `:seon.agent.*/ok?`
    key) — the model narrating a result that only the runtime may emit. The
    pytest-output and pass-claim counts are softer tells (a reply MAY discuss
    tests legitimately) — surfaced for attribution, not part of `fabricated`.
    """
    reply = reply or ""
    glyph = sum(reply.count(g) for g in _GLYPHS)
    fake_env = len(_FAKE_ENV.findall(reply)) + len(_FAKE_OK.findall(reply))
    return {
        "glyph": glyph,
        "fake_env": fake_env,
        "pytest_text": len(_PYTEST.findall(reply)),
        "pass_claim": len(_PASS_CLAIM.findall(reply)),
        "fabricated": glyph > 0 or fake_env > 0,
    }


def fabrication_summary(replies: list[str]) -> dict[str, Any]:
    """Per-drive fabrication rollup over an ordered list of turn replies.

    `fab_turns` = replies that fabricate; `rate` = fab_turns / turns — the
    same directional signal `fab-summary.py` printed, now a structured value
    a ledger row can carry in `attribution`."""
    per = [count_fabrication(r) for r in replies]
    fab_turns = sum(1 for c in per if c["fabricated"])
    n = len(replies)
    return {"turns": n, "fab_turns": fab_turns,
            "rate": (fab_turns / n) if n else 0.0,
            "per_turn": per}


# ---------------------------------------------------------------------------
# Run wiring — one milestone sample (single phase), effects injected
# ---------------------------------------------------------------------------


def run_milestone_sample(
    contract: str,
    milestone: str,
    *,
    run: Callable[[str], dict[str, Any]],
    fetch_evals: Callable[[dict[str, Any]], list[dict[str, Any]]],
    clock_ms: Callable[[], int] = lambda: int(time.time() * 1000),
) -> dict[str, Any]:
    """One milestone drive; every effect is an injected callable (offline-testable).

        r = run(contract)                 # POST /agents/run on the cluster pod
        eval_rows = fetch_evals(r)        # the agent's ok?/source rows, eid-order

    Returns the scorer's inputs (reply + eval_rows + the milestone id) plus the
    raw run result for attribution."""
    r = run(contract)
    eval_rows = fetch_evals(r)
    reply = r.get("reply", "")
    return {"milestone": milestone,
            "reply": reply,
            "eval_rows": eval_rows,
            "fabrication": count_fabrication(reply),
            "run": r,
            "agent_id": r.get("agent_id"),
            "at_ms": clock_ms()}


def pod_milestone_driver(
    contract: str,
    milestone: str,
    *,
    cluster_url: str,
    timeout_ms: int | None = None,
) -> dict[str, Any]:
    """Drive one milestone on an explicitly provisioned static cluster.

    P0 is serial. The caller supplies the pod coordinate from the current
    operator status; this function never opens a writer REPL or creates,
    restarts, or releases a cluster. The response's database-derived evidence
    keeps the useful static ACME path live while the ownership-fenced
    per-sample lease remains unavailable."""
    from seon_inspect.solver import pod_run

    def run(text: str) -> dict[str, Any]:
        return pod_run(text, timeout_ms, cluster_url)

    def fetch(r: dict[str, Any]) -> list[dict[str, Any]]:
        rows = r.get("eval_evidence")
        if not isinstance(rows, list):
            raise RuntimeError(
                "pod response omitted database-derived eval_evidence")
        return rows

    return run_milestone_sample(contract, milestone,
                                run=run, fetch_evals=fetch)


# ---------------------------------------------------------------------------
# inspect @scorer wrappers
# ---------------------------------------------------------------------------

from inspect_ai.scorer import (CORRECT, INCORRECT, Score, Scorer,  # noqa: E402
                               Target, accuracy, mean, scorer)
from inspect_ai.solver import TaskState  # noqa: E402


@scorer(metrics=[accuracy()])
def milestone_scorer() -> Scorer:
    """Score a milestone sample: CORRECT iff its capability oracle holds.

    Requires the solver to have set `state.metadata["milestone"]` and
    `state.metadata["eval_rows"]`; the delivered reply is the completion. The
    fabrication metric rides in `metadata` (report-only, never gates)."""

    async def score(state: TaskState, target: Target) -> Score:
        import json
        meta = state.metadata or {}
        turns = meta.get("pod_turn_evidence")
        turn_ids = ({turn.get("turn_id") for turn in turns
                     if isinstance(turn, dict) and turn.get("turn_id")}
                    if isinstance(turns, list) else None)
        res = check_milestone(
            meta["milestone"], meta["eval_rows"], state.output.completion,
            meta.get("oracle"), meta.get("pod_database_coordinate"), turn_ids)
        fab = count_fabrication(state.output.completion)
        return Score(
            value=CORRECT if res["ok"] else INCORRECT,
            explanation=json.dumps({"failures": res["failures"],
                                    "fabricated": fab["fabricated"]}),
            metadata={**res, "fabrication": fab})

    return score


@scorer(metrics=[mean()])
def fabrication_metric() -> Scorer:
    """Report-only companion: 1.0 when the reply fabricated a result, else 0.0.

    Runs BESIDE `milestone_scorer` so a run's fabrication RATE lands in the
    same eval log as its correctness — an attribution signal, never a gate."""

    async def score(state: TaskState, target: Target) -> Score:
        fab = count_fabrication(state.output.completion)
        return Score(value=1.0 if fab["fabricated"] else 0.0,
                     metadata=fab)

    return score
