"""Pure evidence oracle for the experimental namespace-reachability rows.

The live pod remains the only execution mechanism.  This module scores the
request-scoped facts that mechanism already retains: exact turn prompts,
ordered eval rows, captured database operations, and the delivered reply.
It never reconstructs a tool trajectory from narration.
"""

from __future__ import annotations

import json
import re
from typing import Any

from inspect_ai.scorer import CORRECT, INCORRECT, Score, Scorer, Target, accuracy, scorer
from inspect_ai.solver import TaskState

from seon_inspect.milestone import (
    EvidenceError,
    _coordinate_at_or_before,
    _decode_evidence_value,
    _operations,
    _ordered_proof_rows,
)


ROWS = (
    "root_orchestration",
    "namespace_discovery",
    "skill_lifecycle",
    "acme_product_tools",
)

ROOT_PURPOSE = "audit invoices reachability"
WEB_FUNCTIONS = ("fetch", "grants", "search")
REPL_SKILL_MARKER = "# REPL — how your forms are read, repaired, and run"
ACME_TAGLINE = "Acme — the third-party harness."
ACME_LOCATION = "acme location set: Boston"


def _card(prompt: str, symbol: str) -> bool:
    """A complete compact callable row, not a later bare-name mention."""
    pattern = rf"(?m)^\s*;+\s*fn\s+{re.escape(symbol)}\s+—\s+.+\s+->\s+.+$"
    return re.search(pattern, prompt or "") is not None


def _current_home_source(prompt: str) -> str:
    """The full current ``my.agent.*`` namespace block in one prompt."""
    match = re.search(
        r";;; ┌─ namespace (my\.agent\.[^\s]+) ─\s*\n(.*?)"
        r";;; └─ end namespace \1 ─",
        prompt or "",
        re.S,
    )
    return match.group(2) if match else ""


def _call(source: str, *symbols: str) -> bool:
    alternatives = "|".join(re.escape(symbol) for symbol in symbols)
    return re.search(rf"\((?:{alternatives})(?=[\s\)])", source or "") is not None


def _successful(rows: list[dict[str, Any]], *symbols: str) -> list[int]:
    return [
        index
        for index, row in enumerate(rows)
        if row.get("ok") is True and _call(row.get("source") or "", *symbols)
    ]


def _turn_index(turns: list[dict[str, Any]]) -> dict[str, int]:
    return {turn["turn_id"]: index for index, turn in enumerate(turns)}


def _later_prompt_indices(
    turns: list[dict[str, Any]], row: dict[str, Any]
) -> list[int]:
    positions = _turn_index(turns)
    origin = positions.get(row.get("turn_id"), -1)
    return list(range(origin + 1, len(turns))) if origin >= 0 else []


def _prompt(turns: list[dict[str, Any]], index: int) -> str:
    value = turns[index].get("prompt")
    return value if isinstance(value, str) else ""


def _text_tree(value: Any) -> str:
    if isinstance(value, dict):
        return " ".join(
            f"{_text_tree(key)} {_text_tree(item)}" for key, item in value.items()
        )
    if isinstance(value, (list, tuple, set)):
        return " ".join(_text_tree(item) for item in value)
    return str(value)


def _maps_in(value: Any):
    """Yield every mapping nested in one decoded retained value."""
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from _maps_in(item)
    elif isinstance(value, (list, tuple)):
        for item in value:
            yield from _maps_in(item)


def _created_root_child(request: Any) -> str:
    """The sole idle root child created by the retained transaction request."""
    parent = [":seon.agent/id", "root"]
    matches = [
        row for row in _maps_in(request)
        if row.get(":seon.agent/purpose") == ROOT_PURPOSE
        and row.get(":seon.agent/parent") == parent
        and isinstance(row.get(":seon.agent/id"), str)
    ]
    if len(matches) != 1 or ":seon.agent/run" in matches[0]:
        return ""
    return matches[0][":seon.agent/id"]


def _decoded_operations(
    row: dict[str, Any], final_coordinate: dict[str, Any]
) -> list[tuple[dict[str, Any], Any, Any]]:
    decoded = []
    for operation in _operations(row, final_coordinate):
        if operation.get("ok") is not True:
            raise EvidenceError("captured database operation failed")
        decoded.append(
            (
                operation,
                _decode_evidence_value(operation.get("request")),
                _decode_evidence_value(operation.get("result")),
            )
        )
    return decoded


def _operation(
    decoded: list[tuple[dict[str, Any], Any, Any]], suffix: str
) -> tuple[dict[str, Any], Any, Any]:
    return next(
        item for item in decoded if str(item[0].get("operation", "")).endswith(suffix)
    )


def _reports(
    rows: list[dict[str, Any]], values: tuple[str, ...], after: int,
    turns: list[dict[str, Any]], min_turn_index: int,
) -> bool:
    turn_positions = _turn_index(turns)

    def observed_after_prompt(index: int) -> bool:
        return turn_positions.get(rows[index].get("turn_id"), -1) >= min_turn_index

    messages = [
        index
        for index in _successful(rows, "message/user", "seon.agent.message/user")
        if index > after
        and observed_after_prompt(index)
        and all(value in (rows[index].get("source") or "") for value in values)
    ]
    completions = [
        index
        for index in _successful(rows, "complete", "seon.agent.lifecycle/complete")
        if index > after
        and observed_after_prompt(index)
        and all(value in (rows[index].get("source") or "") for value in values)
    ]
    return bool(messages and completions and messages[0] < completions[-1])


def _ordered_evidence(
    turns: Any, eval_rows: Any, final_coordinate: Any
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if not isinstance(turns, list) or not turns:
        raise EvidenceError("turn evidence is absent")
    if any(
        not isinstance(turn, dict)
        or not isinstance(turn.get("turn_id"), str)
        or not turn["turn_id"]
        or not isinstance(turn.get("prompt"), str)
        or not _coordinate_at_or_before(
            turn.get("rendered_coordinate"), final_coordinate
        )
        for turn in turns
    ):
        raise EvidenceError("turn evidence is malformed")
    turn_ids = [turn["turn_id"] for turn in turns]
    if len(set(turn_ids)) != len(turn_ids):
        raise EvidenceError("turn ids are not unique")
    if not isinstance(eval_rows, list):
        raise EvidenceError("eval evidence is absent")
    ordered = _ordered_proof_rows(eval_rows, final_coordinate, set(turn_ids))
    return turns, ordered


def _root(
    turns: list[dict[str, Any]], rows: list[dict[str, Any]], reply: str,
    final: dict[str, Any]
) -> dict[str, bool]:
    first = _prompt(turns, 0)
    home = _current_home_source(first)
    agent_rows = set(re.findall(
        r"(?m)^\s*;+\s*fn\s+seon\.agent/([^\s]+)\s+—", first
    ))
    surface = (
        "seon.agent" in home
        and _card(first, "seon.agent/start!")
        and _card(first, "seon.agent/delegate!")
        and agent_rows == {"start!", "delegate!"}
    )
    starts = _successful(rows, "agent/start!", "seon.agent/start!")
    delegates = _successful(rows, "agent/delegate!", "seon.agent/delegate!")
    child_messages = _successful(
        rows, "message/agent", "seon.agent.message/agent"
    )
    queries = _successful(rows, "db/query", "seon.db/query")
    selection = bool(
        len(starts) == 1
        and not delegates
        and not child_messages
        and queries
        and ROOT_PURPOSE in (rows[starts[0]].get("source") or "")
        and starts[0] < queries[-1]
    )
    execution = verification = False
    child_id = ""
    if selection:
        try:
            start_ops = _decoded_operations(rows[starts[0]], final)
            query_ops = _decoded_operations(rows[queries[-1]], final)
            tx, tx_request, tx_result = _operation(start_ops, "/transact")
            query, query_request, query_result = _operation(query_ops, "/query")
            tx_text = _text_tree(tx_request)
            query_text = _text_tree(query_request)
            child_id = _created_root_child(tx_request)
            execution = (
                bool(child_id)
                and ROOT_PURPOSE in tx_text
                and isinstance(tx_result, dict)
                and tx_result.get(":seon.db/ok?") is True
                and tx["coordinate"]["t"] <= query["coordinate"]["t"]
            )
            verification = (
                bool(child_id)
                and query_result == child_id
                and child_id in query_text
                and ROOT_PURPOSE in query_text
                and ":seon.agent/parent" in query_text
                and ":seon.agent/purpose" in query_text
                and ":seon.agent/id" in query_text
            )
        except (EvidenceError, KeyError, StopIteration, TypeError, ValueError):
            pass
    later = _later_prompt_indices(turns, rows[starts[0]]) if starts else []
    query_turn_index = (
        _turn_index(turns).get(rows[queries[-1]].get("turn_id"), -1)
        if queries else -1
    )
    dynamic = bool(
        later
        and child_id
        and any(
            index <= query_turn_index and child_id in _prompt(turns, index)
            for index in later
        )
    )
    query_prompts = (
        [
            index for index in _later_prompt_indices(turns, rows[queries[-1]])
            if child_id and child_id in _prompt(turns, index)
        ]
        if queries else []
    )
    report_after = queries[-1] if queries else len(rows)
    report = bool(
        child_id
        and query_prompts
        and _reports(
            rows, (child_id,), report_after, turns, query_prompts[0]
        )
        and child_id in (reply or "")
    )
    return {
        "surface": surface,
        "selection": selection,
        "execution": execution,
        "dynamic_context": dynamic,
        "verification": verification,
        "report": report,
    }


def _discovery(
    turns: list[dict[str, Any]], rows: list[dict[str, Any]], reply: str,
    final: dict[str, Any]
) -> dict[str, bool]:
    first = _prompt(turns, 0)
    surface = (
        "my.ns" in _current_home_source(first)
        and len(re.findall(r"(?m)^\s*;+\s*fn\s+my\.ns/functions\s+—", first)) == 1
        and _card(first, "my.ns/functions")
    )
    functions = _successful(rows, "my.ns/functions", "ns/functions")
    moves = [
        index for index, row in enumerate(rows)
        if row.get("ok") is True
        and re.search(r"\(in-ns\s+'seon\.agent\.web\)", row.get("source") or "")
    ]
    grants = _successful(rows, "grants")
    returns = [
        index for index, row in enumerate(rows)
        if row.get("ok") is True
        and re.search(r"\(in-ns\s+'my\.agent\.[^\s)]+\)", row.get("source") or "")
    ]
    selection = bool(
        functions
        and "seon.agent.web" in (rows[functions[0]].get("source") or "")
        and moves
        and functions[0] < moves[0]
    )
    execution = False
    if selection:
        try:
            decoded = _decoded_operations(rows[functions[0]], final)
            kinds = {str(item[0].get("operation")) for item in decoded}
            evidence_text = _text_tree([item[1] for item in decoded])
            execution = (
                any(kind.endswith("/query") for kind in kinds)
                and any(kind.endswith("/pull") for kind in kinds)
                and ":seon.ns/name" in evidence_text
                and "seon.agent.web" in evidence_text
                and ":seon.fn/agent-facing?" in evidence_text
            )
        except (EvidenceError, KeyError, TypeError, ValueError):
            pass
    dynamic_prompts = []
    if moves:
        dynamic_prompts = [
            index for index in _later_prompt_indices(turns, rows[moves[0]])
            if all(
                re.search(rf"\(defn[^\n]*\b{re.escape(name)}\b", _prompt(turns, index))
                for name in WEB_FUNCTIONS
            )
        ]
    dynamic = bool(dynamic_prompts)
    verification = bool(
        dynamic_prompts
        and grants
        and _turn_index(turns).get(rows[grants[0]].get("turn_id"), -1)
        >= dynamic_prompts[0]
        and returns
        and grants[0] < returns[-1]
    )
    values = WEB_FUNCTIONS
    report_after = returns[-1] if returns else len(rows)
    report = (
        bool(dynamic_prompts)
        and _reports(rows, values, report_after, turns, dynamic_prompts[0])
        and all(value in (reply or "") for value in values)
    )
    return {
        "surface": surface,
        "selection": selection,
        "execution": execution,
        "dynamic_context": dynamic,
        "verification": verification,
        "report": report,
    }


def _skills(
    turns: list[dict[str, Any]], rows: list[dict[str, Any]], reply: str,
    final: dict[str, Any]
) -> dict[str, bool]:
    first = _prompt(turns, 0)
    surface = (
        "my.skills" in _current_home_source(first)
        and all(_card(first, f"my.skills/{name}") for name in ("list", "load", "unload"))
    )
    lists = _successful(rows, "my.skills/list", "skills/list")
    loads = _successful(rows, "my.skills/load", "skills/load")
    unloads = _successful(rows, "my.skills/unload", "skills/unload")
    selection = bool(
        lists and loads and unloads
        and ":repl" in (rows[loads[0]].get("source") or "")
        and ":repl" in (rows[unloads[-1]].get("source") or "")
        and lists[0] < loads[0] < unloads[-1]
    )
    execution = False
    if selection:
        try:
            load_ops = _decoded_operations(rows[loads[0]], final)
            unload_ops = _decoded_operations(rows[unloads[-1]], final)
            load_tx, load_request, _ = _operation(load_ops, "/transact")
            unload_tx, unload_request, _ = _operation(unload_ops, "/transact")
            execution = (
                ":skill/repl" in _text_tree(load_request)
                and ":skill/repl" in _text_tree(unload_request)
                and load_tx["coordinate"]["t"] <= unload_tx["coordinate"]["t"]
            )
        except (EvidenceError, KeyError, StopIteration, TypeError, ValueError):
            pass
    load_prompts = [
        index for index in (_later_prompt_indices(turns, rows[loads[0]]) if loads else [])
        if REPL_SKILL_MARKER in _prompt(turns, index)
    ]
    unload_prompts = [
        index for index in (_later_prompt_indices(turns, rows[unloads[-1]]) if unloads else [])
        if REPL_SKILL_MARKER not in _prompt(turns, index)
    ]
    dynamic = bool(load_prompts and unload_prompts and load_prompts[0] < unload_prompts[-1])
    verification = bool(
        dynamic
        and _turn_index(turns).get(rows[unloads[-1]].get("turn_id"), -1) >= load_prompts[0]
    ) if unloads else False
    report_after = unloads[-1] if unloads else len(rows)
    report = bool(
        unload_prompts
        and _reports(rows, (), report_after, turns, unload_prompts[0])
        and isinstance(reply, str)
        and bool(reply.strip())
    )
    return {
        "surface": surface,
        "selection": selection,
        "execution": execution,
        "dynamic_context": dynamic,
        "verification": verification,
        "report": report,
    }


def _acme(
    turns: list[dict[str, Any]], rows: list[dict[str, Any]], reply: str,
    _final: dict[str, Any]
) -> dict[str, bool]:
    first = _prompt(turns, 0)
    home = _current_home_source(first)
    surface = (
        "acme.brand" in home
        and "acme.widget" in home
        and "acme.helpers" not in home
        and "acme.notes" not in home
        and _card(first, "acme.brand/tagline")
        and _card(first, "acme.widget/set-location!")
    )
    taglines = _successful(rows, "acme.brand/tagline", "brand/tagline")
    locations = _successful(
        rows, "acme.widget/set-location!", "widget/set-location!"
    )
    selection = bool(
        taglines and locations
        and "Boston" in (rows[locations[0]].get("source") or "")
    )
    execution = selection
    last_call = max(taglines[0], locations[0]) if selection else len(rows)
    later = _later_prompt_indices(turns, rows[last_call]) if selection else []
    result_prompts = [
        index for index in later
        if ACME_TAGLINE in _prompt(turns, index)
        and ACME_LOCATION in _prompt(turns, index)
    ]
    dynamic = bool(result_prompts)
    verification = dynamic
    report = bool(
        result_prompts
        and _reports(
            rows, (ACME_TAGLINE, ACME_LOCATION), last_call,
            turns, result_prompts[0],
        )
        and ACME_TAGLINE in (reply or "")
        and ACME_LOCATION in (reply or "")
    )
    return {
        "surface": surface,
        "selection": selection,
        "execution": execution,
        "dynamic_context": dynamic,
        "verification": verification,
        "report": report,
    }


_CHECKERS = {
    "root_orchestration": _root,
    "namespace_discovery": _discovery,
    "skill_lifecycle": _skills,
    "acme_product_tools": _acme,
}


def check_reachability(
    row: str,
    turn_evidence: Any,
    eval_evidence: Any,
    completion: str,
    final_coordinate: Any,
) -> dict[str, Any]:
    """Score one row from retained facts, failing closed on malformed evidence."""
    if row not in _CHECKERS:
        raise ValueError(f"unknown reachability row {row!r}; expected one of {ROWS}")
    try:
        turns, eval_rows = _ordered_evidence(
            turn_evidence, eval_evidence, final_coordinate
        )
        checks = _CHECKERS[row](
            turns, eval_rows, completion or "", final_coordinate
        )
    except (EvidenceError, KeyError, TypeError, ValueError):
        checks = {name: False for name in (
            "surface", "selection", "execution", "dynamic_context",
            "verification", "report",
        )}
    failures = [name for name, passed in checks.items() if not passed]
    return {"ok": not failures, "checks": checks, "failures": failures}


@scorer(metrics=[accuracy()])
def reachability_scorer() -> Scorer:
    """Inspect adapter over the pure retained-evidence oracle."""

    async def score(state: TaskState, target: Target) -> Score:
        metadata = state.metadata or {}
        result = check_reachability(
            metadata["seon_reachability_row"],
            metadata.get("pod_turn_evidence"),
            metadata.get("pod_eval_evidence"),
            state.output.completion,
            metadata.get("pod_database_coordinate"),
        )
        return Score(
            value=CORRECT if result["ok"] else INCORRECT,
            explanation=json.dumps(result["failures"]),
            metadata=result,
        )

    return score
