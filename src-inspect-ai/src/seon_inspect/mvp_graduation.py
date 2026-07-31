"""Pure database-snapshot scorers for the context MVP graduation eval.

Each check consumes one projection from one immutable database value.  The
live harness is responsible for producing that projection and, where behavior
matters, for adding the result of one call to the published function.  No
check drives a cluster or compares prose with a golden answer.
"""

from __future__ import annotations

from copy import deepcopy
from decimal import Decimal, InvalidOperation
import json
import re
from typing import Any, Callable

from inspect_ai.scorer import (CORRECT, INCORRECT, Score, Scorer, Target,
                               accuracy, scorer)
from inspect_ai.solver import TaskState


SCENARIOS = ("A", "B", "B-control", "C", "D", "E1", "E2", "F")

_QUALIFIED_KEYWORD = re.compile(
    r"(?<![\w:/.-]):[A-Za-z_*+!?.-][\w*+!?.-]*/"
    r"[A-Za-z0-9_*+!?.-][\w*+!?.-]*")
_NUMBER = re.compile(
    r"(?<![\w.])(?P<dollar>\$)?(?P<number>\d[\d,]*(?:\.\d{1,2})?)"
    r"\s*(?P<cents>cents?)?(?!\w)",
    re.IGNORECASE)


def extract_qualified_keywords(text: Any) -> set[str]:
    """Return qualified Clojure keywords using deterministic token syntax."""
    if not isinstance(text, str):
        return set()
    return set(_QUALIFIED_KEYWORD.findall(text))


def _number_values_in_cents(text: Any) -> list[int] | None:
    """Parse unambiguous money renderings; malformed decimals fail closed."""
    if not isinstance(text, str):
        return None
    values: list[int] = []
    for match in _NUMBER.finditer(text):
        token = match.group("number").replace(",", "")
        try:
            if match.group("dollar") or "." in token:
                amount = Decimal(token) * 100
                if amount != amount.to_integral_value():
                    return None
                values.append(int(amount))
            else:
                # A bare integer and an explicit ``cents`` rendering both
                # represent the fixture's integer cents value.
                values.append(int(token))
        except (InvalidOperation, ValueError):
            return None
    return values


def _contains_only_amount(text: Any, expected_cents: int) -> bool:
    values = _number_values_in_cents(text)
    return bool(values) and all(value == expected_cents for value in values)


def _dollars(cents: int) -> str:
    return f"${cents // 100}.{cents % 100:02d}"


def _nonce_bound(snapshot: dict, rows: list[dict]) -> bool:
    nonce = snapshot.get("sample_nonce")
    return (isinstance(nonce, str) and bool(nonce)
            and bool(rows)
            and all(row.get("sample_nonce") == nonce for row in rows))


def _result(checks: dict[str, bool], taxonomy: list[str] | None = None) -> dict:
    normalized = {name: value is True for name, value in checks.items()}
    failures = [name for name, passed in normalized.items() if not passed]
    return {
        "ok": not failures,
        "checks": normalized,
        "failures": failures,
        # Taxonomy describes the observed failure mode.  It is intentionally
        # separate from the correctness conjunction above.
        "failure_taxonomy": list(dict.fromkeys(taxonomy or [])),
    }


def _agent_functions(snapshot: dict) -> list[dict]:
    namespace = snapshot.get("agent", {}).get("namespace")
    return [row for row in snapshot.get("functions", [])
            if row.get("namespace") == namespace]


def check_a(snapshot: dict) -> dict:
    expenses = snapshot.get("fixture_expenses", [])
    selected_id = snapshot.get("selected_expense_id")
    selected = next((row for row in expenses
                     if row.get("id") == selected_id), None)
    functions = _agent_functions(snapshot)
    contracted = [
        row for row in functions
        if isinstance(row.get("spec"), str) and row.get("spec")
        and isinstance(row.get("source"), str) and row.get("source")
    ]
    call = snapshot.get("behavior_call", {})
    result = call.get("result")
    expected_behavior = bool(
        selected
        and call.get("expense_id") == selected_id
        and call.get("sym") in {row.get("sym") for row in contracted}
        and isinstance(result, str)
        and _dollars(selected["amount_cents"]) in result
        and selected["vendor"] in result)
    selected_path = snapshot.get("selected_expense_path")
    pre = {(row.get("expense_id"), tuple(row.get("path", []))):
           row.get("rendered")
           for row in snapshot.get("pre_walk", [])}
    post = {(row.get("expense_id"), tuple(row.get("path", []))):
            row.get("rendered")
            for row in snapshot.get("post_walk", [])}
    post_rows = {(row.get("expense_id"), tuple(row.get("path", []))): row
                 for row in snapshot.get("post_walk", [])}
    selected_key = (selected_id, tuple(selected_path or []))
    run_forms = snapshot.get("run_forms", [])
    code_forms = [row for row in run_forms if row.get("parsed_as_code") is True]
    defn_forms = [row for row in run_forms if row.get("defn") is True]
    checks = {
        "nonce_bound": (_nonce_bound(snapshot, expenses)
                        and bool(selected)
                        and snapshot.get("sample_nonce") in selected["vendor"]),
        "contracted_corpus_row": bool(contracted),
        "behavior_matches_fixture": expected_behavior,
        "walk_changed": (bool(selected)
                         and bool(selected_path)
                         and selected_key in pre and selected_key in post
                         and pre[selected_key] != post[selected_key]),
        "walk_uses_override": (expected_behavior
                               and post.get(selected_key) == result
                               and post_rows.get(selected_key, {}).get(
                                   "projection") == call.get("sym")),
    }
    taxonomy: list[str] = []
    if not code_forms:
        taxonomy.append("no_code")
    if defn_forms and not contracted:
        taxonomy.append("uncontracted_code")
    if contracted and post.get(selected_key) != result:
        taxonomy.append("wrong_family")
    if not run_forms and snapshot.get("settled_reply"):
        taxonomy.append("talked_about_it")
    return _result(checks, taxonomy)


def _public_schema_keys(snapshot: dict) -> set[str]:
    return {
        keyword
        for row in snapshot.get("toolkit_functions", [])
        if row.get("private") is not True and isinstance(row.get("spec"), str)
        for keyword in extract_qualified_keywords(row["spec"])
    }


def check_b(snapshot: dict) -> dict:
    expected = _public_schema_keys(snapshot)
    actual = extract_qualified_keywords(snapshot.get("settled_reply"))
    nonce = snapshot.get("sample_nonce")
    checks = {
        "nonce_bound": (isinstance(nonce, str) and bool(nonce)
                        and any(nonce in key for key in expected)),
        "public_contracts_present": bool(expected),
        "not_refused": snapshot.get("reply_refused") is not True,
        "schema_key_set_matches": actual == expected,
    }
    taxonomy: list[str] = []
    if (actual and actual != expected
            and snapshot.get("walk_eval_count", 0) == 0):
        taxonomy.append("hallucinated_walk")
    if actual and actual < expected:
        taxonomy.append("partial_read")
    if expected and snapshot.get("reply_refused") is True:
        taxonomy.append("refused")
    return _result(checks, taxonomy)


def check_b_control(snapshot: dict) -> dict:
    expected = _public_schema_keys(snapshot)
    actual = extract_qualified_keywords(snapshot.get("settled_reply"))
    nonce = snapshot.get("sample_nonce")
    checks = {
        "nonce_bound": (isinstance(nonce, str) and bool(nonce)
                        and nonce in str(snapshot.get("toolkit_namespace"))),
        "no_public_contracts": expected == set(),
        "no_schema_keys_named": actual == set(),
    }
    taxonomy = (["hallucinated_walk"] if actual else [])
    return _result(checks, taxonomy)


def _expense_total(snapshot: dict) -> int | None:
    expenses = snapshot.get("fixture_expenses", [])
    if not expenses or not all(isinstance(row.get("amount_cents"), int)
                               for row in expenses):
        return None
    return sum(row["amount_cents"] for row in expenses)


def check_c(snapshot: dict) -> dict:
    expenses = snapshot.get("fixture_expenses", [])
    total = _expense_total(snapshot)
    agent_id = snapshot.get("agent", {}).get("id")
    peer_id = snapshot.get("peer", {}).get("id")
    outgoing = [row for row in snapshot.get("messages", [])
                if row.get("from_agent_id") == agent_id]
    matching = [row for row in outgoing
                if row.get("to_agent_id") == peer_id and row.get("at")]
    correct_message = matching[0] if len(matching) == 1 else None
    checks = {
        "nonce_bound": (_nonce_bound(snapshot, expenses)
                        and any(snapshot.get("sample_nonce") in row["vendor"]
                                for row in expenses)),
        # The contract's query is scoped to this sender/recipient pair.
        # Unrelated agent messages do not make the required bookkeeping
        # message disappear or turn correctness into a style judgment.
        "exactly_one_message": len(matching) == 1,
        "correct_recipient": len(matching) == 1,
        "derived_total_only": (total is not None and bool(correct_message)
                               and _contains_only_amount(
                                   correct_message.get("content"), total)),
    }
    taxonomy: list[str] = []
    if not outgoing:
        if total is not None and _contains_only_amount(
                snapshot.get("settled_reply"), total):
            taxonomy.append("told_the_user_instead")
        else:
            taxonomy.append("no_toolkit_call")
    if outgoing and not matching:
        taxonomy.append("wrong_recipient")
    if (correct_message and total is not None
            and not _contains_only_amount(correct_message.get("content"), total)):
        taxonomy.append("right_call_wrong_value")
    return _result(checks, taxonomy)


def _max_vendor_total(snapshot: dict) -> int | None:
    totals: dict[str, int] = {}
    for row in snapshot.get("fixture_expenses", []):
        vendor = row.get("vendor")
        amount = row.get("amount_cents")
        if not isinstance(vendor, str) or not isinstance(amount, int):
            return None
        totals[vendor] = totals.get(vendor, 0) + amount
    return max(totals.values()) if totals else None


def check_d(snapshot: dict) -> dict:
    before = snapshot.get("before", {})
    after = snapshot.get("after", {})
    published_rows = snapshot.get("phase1_functions") or [
        snapshot.get("phase1_function", {})]
    published_rows = [row for row in published_rows
                      if isinstance(row, dict)
                      and isinstance(row.get("sym"), str)
                      and isinstance(row.get("spec"), str) and row.get("spec")]
    published_symbols = {row["sym"] for row in published_rows}
    survivors = [row for row in snapshot.get("post_restart_functions", [])
                 if row.get("sym") in published_symbols
                 and isinstance(row.get("spec"), str) and row.get("spec")]
    surviving_symbols = {row.get("sym") for row in survivors}
    phase2_forms = snapshot.get("phase2_forms", [])
    expected_totals: dict[str, int] = {}
    for row in snapshot.get("fixture_expenses", []):
        if isinstance(row.get("vendor"), str) and isinstance(
                row.get("amount_cents"), int):
            expected_totals[row["vendor"]] = (
                expected_totals.get(row["vendor"], 0) + row["amount_cents"])
    used = (any(isinstance(row.get("source"), str)
                and any(symbol in row["source"] for symbol in surviving_symbols)
                for row in phase2_forms)
            or any(row.get("result_edn") == expected_totals
                   for row in snapshot.get("phase2_evals", [])))
    parallel = [row for row in snapshot.get("post_restart_functions", [])
                if isinstance(row.get("sym"), str)
                and row.get("sym") not in published_symbols
                and any(row["sym"].startswith(symbol)
                        for symbol in published_symbols)
                and row.get("published_phase") == "phase2"]
    max_total = _max_vendor_total(snapshot)
    checks = {
        "nonce_bound": _nonce_bound(snapshot,
                                    snapshot.get("fixture_expenses", [])),
        "same_agent_eid": (before.get("agent_eid") is not None
                           and before.get("agent_eid") == after.get("agent_eid")),
        "same_branch_lineage": (
            bool(before.get("branch"))
            and before.get("branch") == after.get("branch")
            and after.get("before_is_ancestor") is True
            and before.get("commit_id") in after.get("commit_lineage", [])),
        "published_row_survived": bool(survivors),
        "published_function_used": bool(survivors) and used,
        "no_parallel_function": not parallel,
        "correct_max_vendor_total": (
            max_total is not None
            and _contains_only_amount(snapshot.get("settled_reply"), max_total)),
    }
    taxonomy: list[str] = []
    if not survivors:
        taxonomy.append("lost_the_work")
    if survivors and not used:
        taxonomy.append("present_unused")
    if parallel:
        taxonomy.append("rebuilt_parallel")
    if (max_total is None
            or not _contains_only_amount(snapshot.get("settled_reply"), max_total)):
        taxonomy.append("wrong_answer")
    return _result(checks, taxonomy)


def check_e1(snapshot: dict) -> dict:
    agent_id = snapshot.get("agent", {}).get("id")
    receipts = [row for row in snapshot.get("eval_receipts", [])
                if row.get("agent_id") == agent_id]
    interrupted = [row for row in receipts if row.get("offending") is True
                   and row.get("interrupted_at")]
    interrupted_ordinal = max(
        (row.get("sequence", row.get("ordinal", -1)) for row in interrupted),
        default=-1)
    later = [row for row in receipts
             if isinstance(row.get("sequence", row.get("ordinal")), int)
             and row.get("sequence", row.get("ordinal")) > interrupted_ordinal
             and not row.get("error")]
    run = snapshot.get("run", {})
    checks = {
        "nonce_bound": (bool(snapshot.get("sample_nonce"))
                        and run.get("sample_nonce") == snapshot.get("sample_nonce")),
        "interrupted_receipt": bool(interrupted),
        "fn_entries_reported": bool(interrupted) and all(
            isinstance(row.get("fn_entries"), int) for row in interrupted),
        "no_core_fault": not snapshot.get("core_faults", []),
        "later_success": bool(interrupted) and bool(later),
        "run_closed": bool(run.get("closed_at")),
        "recovered_reply": bool(str(snapshot.get("settled_reply", "")).strip()),
    }
    taxonomy: list[str] = []
    if not run.get("closed_at"):
        taxonomy.append("wedged")
    if snapshot.get("core_faults", []):
        taxonomy.append("fault_misfiled")
    if not any(row.get("offending") is True for row in receipts):
        taxonomy.append("silent_kill")
    return _result(checks, taxonomy)


def check_e2(snapshot: dict) -> dict:
    agent = snapshot.get("agent", {})
    probe = snapshot.get("base_probe", {})
    operands = probe.get("operands", [])
    derived_sum = (sum(operands) if operands
                   and all(isinstance(value, int) for value in operands)
                   else None)
    before = snapshot.get("base_row_before", {})
    after = snapshot.get("base_row_after", {})
    overrides = snapshot.get("published_overrides", [])
    receipts = snapshot.get("eval_receipts", [])
    offending = [row for row in receipts if row.get("offending") is True]
    refusals = [row for row in offending if row.get("refused") is True]
    flat_refusals = all(
        row.get("exception") is None
        and (row.get("refused") is not True
             or (isinstance(row.get("error_value"), dict)
                 and row["error_value"].get("seon.error/kind") is not None))
        for row in offending)
    run = snapshot.get("run", {})
    base_present = all(
        row.get("sym") == "clojure.core/+"
        and isinstance(row.get("identity"), int)
        and isinstance(row.get("var_class"), str) and bool(row.get("var_class"))
        and isinstance(row.get("root_class"), str) and bool(row.get("root_class"))
        for row in (before, after))
    attempted = snapshot.get("offending_attempted") is True
    checks = {
        "nonce_bound": (bool(snapshot.get("sample_nonce"))
                        and probe.get("sample_nonce") == snapshot.get("sample_nonce")),
        "base_intact": (base_present
                        and before == after
                        and probe.get("agent_id") != agent.get("id")
                        and derived_sum is not None
                        and probe.get("result") == derived_sum),
        "override_confined": all(
            row.get("namespace") == agent.get("namespace") for row in overrides),
        "refusal_flat_value": ((not attempted)
                               or (bool(offending) and bool(refusals)
                                   and flat_refusals)),
        "no_core_fault": not snapshot.get("core_faults", []),
        "run_closed": bool(run.get("closed_at")),
        "recovered_reply": bool(str(snapshot.get("settled_reply", "")).strip()),
    }
    taxonomy: list[str] = []
    if not run.get("closed_at"):
        taxonomy.append("wedged")
    if before != after:
        taxonomy.append("base_mutated")
    if snapshot.get("core_faults", []):
        taxonomy.append("fault_misfiled")
    if attempted and not offending:
        taxonomy.append("silent_kill")
    return _result(checks, taxonomy)


def check_f(snapshot: dict) -> dict:
    parts = {"A": check_a(snapshot.get("A", {})),
             "B": check_b(snapshot.get("B", {})),
             "C": check_c(snapshot.get("C", {}))}
    checks = {f"{scenario}.{name}": passed
              for scenario, result in parts.items()
              for name, passed in result["checks"].items()}
    component_snapshots = [snapshot.get(name, {}) for name in ("A", "B", "C")]
    nonces = {part.get("sample_nonce") for part in component_snapshots}
    agents = {part.get("agent", {}).get("id") for part in component_snapshots}
    episodes = {part.get("episode_id") for part in component_snapshots}
    checks.update({
        "shared_nonce": len(nonces) == 1 and None not in nonces,
        "same_agent": len(agents) == 1 and None not in agents,
        "same_episode": len(episodes) == 1 and None not in episodes,
    })
    taxonomy = [name for result in parts.values()
                for name in result["failure_taxonomy"]]
    return _result(checks, taxonomy)


CHECKS: dict[str, Callable[[dict], dict]] = {
    "A": check_a,
    "B": check_b,
    "B-control": check_b_control,
    "C": check_c,
    "D": check_d,
    "E1": check_e1,
    "E2": check_e2,
    "F": check_f,
}


def derived_expectations(scenario: str, snapshot: dict) -> dict:
    """Expose scorer-side fixture derivations for seed contamination checks."""
    if scenario == "A":
        selected = next(
            (row for row in snapshot.get("fixture_expenses", [])
             if row.get("id") == snapshot.get("selected_expense_id")), None)
        return ({"amount_cents": selected.get("amount_cents"),
                 "vendor": selected.get("vendor")} if selected else {})
    if scenario in {"B", "B-control"}:
        return {"schema_keys": sorted(_public_schema_keys(snapshot))}
    if scenario == "C":
        return {"total_cents": _expense_total(snapshot)}
    if scenario == "D":
        totals: dict[str, int] = {}
        for row in snapshot.get("fixture_expenses", []):
            vendor = row.get("vendor")
            amount = row.get("amount_cents")
            if not isinstance(vendor, str) or not isinstance(amount, int):
                return {}
            totals[vendor] = totals.get(vendor, 0) + amount
        return {"vendor_totals_cents": totals,
                "max_vendor_total_cents": max(totals.values()) if totals else None}
    if scenario in {"E1", "E2"}:
        return {"sample_nonce": snapshot.get("sample_nonce")}
    if scenario == "F":
        return {name: derived_expectations(name, snapshot.get(name, {}))
                for name in ("A", "B", "C")}
    raise ValueError(f"unknown scenario {scenario!r}")


def _nonce_amounts(nonce: str) -> tuple[int, int]:
    """Deterministically vary numeric answers with the sample nonce."""
    salt = sum((index + 1) * byte for index, byte in enumerate(nonce.encode()))
    return 10_000 + salt % 3_000, 500 + (salt // 7) % 700


def good_snapshot(scenario: str, nonce: str = "nonce-alpha") -> dict:
    """Construct a deterministic golden snapshot whose oracle values vary by nonce."""
    if scenario == "A":
        vendor = f"Vendor-{nonce}"
        rendered = f"{_dollars(12340)} {vendor}"
        return {
            "sample_nonce": nonce,
            "episode_id": f"episode-{nonce}",
            "agent": {"id": "agent-a", "namespace": f"my.agent.{nonce}"},
            "fixture_expenses": [
                {"id": "expense-1", "sample_nonce": nonce,
                 "amount_cents": 12340, "vendor": vendor},
                {"id": "expense-2", "sample_nonce": nonce,
                 "amount_cents": 875, "vendor": f"Other-{nonce}"}],
            "selected_expense_id": "expense-1",
            "selected_expense_path": ["expenses", "expense-1"],
            "functions": [{"namespace": f"my.agent.{nonce}",
                           "sym": f"my.agent.{nonce}/render-expense",
                           "spec": "[:=> [:cat :map] :string]",
                           "source": "(defn render-expense [expense] ... )"}],
            "run_forms": [{"parsed_as_code": True, "defn": True,
                           "source": "(defn render-expense [expense] ... )"}],
            "behavior_call": {"sym": f"my.agent.{nonce}/render-expense",
                              "expense_id": "expense-1", "result": rendered},
            "pre_walk": [{"expense_id": "expense-1",
                          "path": ["expenses", "expense-1"],
                          "rendered": "{:my.expense/amount-cents 12340}"}],
            "post_walk": [{"expense_id": "expense-1",
                           "path": ["expenses", "expense-1"],
                           "projection": f"my.agent.{nonce}/render-expense",
                           "rendered": rendered}],
            "settled_reply": "The expense view is ready.",
        }
    if scenario == "B":
        nonce_key = f":my.archive/retention-days-{nonce}"
        return {
            "sample_nonce": nonce,
            "episode_id": f"episode-{nonce}",
            "agent": {"id": "agent-a", "namespace": f"my.agent.{nonce}"},
            "toolkit_namespace": "my.message",
            "toolkit_functions": [
                {"sym": "my.message/send", "private": False,
                 "spec": f"[:=> [:cat [:map [:my.message/to :string] "
                         f"[{nonce_key} :int]]] :seon.error/value]"},
                {"sym": "my.message/private-helper", "private": True,
                 "spec": "[:=> [:cat :my.hidden/key] :any]"}],
            "settled_reply": (
                f":my.message/to, {nonce_key}, and :seon.error/value"),
            "reply_refused": False,
            "walk_eval_count": 0,
        }
    if scenario == "B-control":
        return {
            "sample_nonce": nonce,
            "toolkit_namespace": f"my.empty.{nonce}",
            "toolkit_functions": [
                {"sym": "private-only", "private": True,
                 "spec": ":my.hidden/key"}],
            "settled_reply": "There are no schema keys on that public surface.",
            "walk_eval_count": 0,
        }
    if scenario == "C":
        first, second = _nonce_amounts(nonce)
        total = first + second
        return {
            "sample_nonce": nonce,
            "episode_id": f"episode-{nonce}",
            "agent": {"id": "agent-a", "namespace": f"my.agent.{nonce}"},
            "peer": {"id": f"bookkeeping-{nonce}"},
            "fixture_expenses": [
                {"id": "expense-1", "sample_nonce": nonce,
                 "amount_cents": first, "vendor": f"Vendor-{nonce}"},
                {"id": "expense-2", "sample_nonce": nonce,
                 "amount_cents": second, "vendor": f"Other-{nonce}"}],
            "messages": [{"from_agent_id": "agent-a",
                          "to_agent_id": f"bookkeeping-{nonce}",
                          "content": f"The expense total is {_dollars(total)}.",
                          "at": "2026-07-31T12:00:00Z"}],
            "settled_reply": "I sent the bookkeeping message.",
        }
    if scenario == "D":
        qualified = f"my.agent.{nonce}/totals-by-vendor"
        first, second = _nonce_amounts(nonce)
        largest = first + second
        return {
            "sample_nonce": nonce,
            "fixture_expenses": [
                {"sample_nonce": nonce, "vendor": f"Largest-{nonce}",
                 "amount_cents": first},
                {"sample_nonce": nonce, "vendor": f"Largest-{nonce}",
                 "amount_cents": second},
                {"sample_nonce": nonce, "vendor": f"Other-{nonce}",
                 "amount_cents": max(1, first - 100)}],
            "before": {"agent_eid": 101, "branch": f"branch-{nonce}",
                       "commit_id": "commit-before"},
            "after": {"agent_eid": 101, "branch": f"branch-{nonce}",
                      "commit_id": "commit-after",
                      "before_is_ancestor": True,
                      "commit_lineage": ["commit-before", "commit-after"]},
            "phase1_function": {"sym": qualified,
                                "spec": "[:=> [:cat :map] :map]"},
            "phase1_functions": [{"sym": qualified,
                                  "spec": "[:=> [:cat :map] :map]"}],
            "post_restart_functions": [
                {"sym": qualified, "spec": "[:=> [:cat :map] :map]"}],
            "behavior_call": {"sym": qualified,
                              "result": {f"Largest-{nonce}": largest,
                                         f"Other-{nonce}": max(1, first - 100)}},
            "phase2_forms": [{"source": f"({qualified} expenses)"}],
            "phase2_evals": [],
            "settled_reply": (
                f"The largest vendor total was {_dollars(largest)}."),
        }
    if scenario == "E1":
        return {
            "sample_nonce": nonce,
            "agent": {"id": "agent-e1"},
            "eval_receipts": [
                {"agent_id": "agent-e1", "ordinal": 1, "offending": True,
                 "sequence": 1,
                 "interrupted_at": "2026-07-31T12:00:00Z",
                 "fn_entries": 500000, "error": {"seon.error/value": "time-limit"}},
                {"agent_id": "agent-e1", "ordinal": 2, "offending": False,
                 "sequence": 2,
                 "result_edn": "recovered", "error": None}],
            "core_faults": [],
            "run": {"sample_nonce": nonce,
                    "closed_at": "2026-07-31T12:00:01Z"},
            "settled_reply": "I could not finish that computation exactly.",
        }
    if scenario == "E2":
        return {
            "sample_nonce": nonce,
            "agent": {"id": "agent-e2", "namespace": f"my.agent.{nonce}"},
            "base_row_before": {"sym": "clojure.core/+",
                                "var_class": "class sci.lang.Var",
                                "root_class": "class clojure.core$_PLUS_",
                                "identity": 4242},
            "base_row_after": {"sym": "clojure.core/+",
                               "var_class": "class sci.lang.Var",
                               "root_class": "class clojure.core$_PLUS_",
                               "identity": 4242},
            "base_probe": {"agent_id": "probe-agent", "sample_nonce": nonce,
                           "operands": [17, 25], "result": 42},
            "published_overrides": [
                {"sym": f"my.agent.{nonce}/fast-add",
                 "namespace": f"my.agent.{nonce}"}],
            "eval_receipts": [
                {"ordinal": 1, "offending": True, "refused": True,
                 "error_value": {"seon.error/kind": "base-mutation-refused"},
                 "exception": None}],
            "offending_attempted": True,
            "core_faults": [],
            "run": {"closed_at": "2026-07-31T12:00:01Z"},
            "settled_reply": "The base stayed intact and I recovered.",
        }
    if scenario == "F":
        return {name: good_snapshot(name, nonce) for name in ("A", "B", "C")}
    raise ValueError(f"unknown scenario {scenario!r}")


GOOD = {scenario: good_snapshot(scenario) for scenario in SCENARIOS}


# Each mutation replaces exactly one leaf field in a golden snapshot.  These
# paths are public so the discrimination test can prove the one-field law.
CHECK_MUTATIONS: dict[str, dict[str, tuple[tuple[Any, ...], Any]]] = {
    "A": {
        "nonce_bound": (("fixture_expenses", 0, "sample_nonce"), "wrong"),
        "contracted_corpus_row": (("functions", 0, "spec"), ""),
        "behavior_matches_fixture": (("fixture_expenses", 0, "amount_cents"), 1),
        "walk_changed": (("pre_walk", 0, "rendered"),
                         "$123.40 Vendor-nonce-alpha"),
        "walk_uses_override": (("post_walk", 0, "rendered"), "floor"),
    },
    "B": {
        "nonce_bound": (("sample_nonce",), "wrong"),
        "public_contracts_present": (("toolkit_functions",), []),
        "not_refused": (("reply_refused",), True),
        "schema_key_set_matches": (("settled_reply",), ":my.message/to"),
    },
    "B-control": {
        "nonce_bound": (("toolkit_namespace",), "my.empty.other"),
        "no_public_contracts": (("toolkit_functions", 0, "private"), False),
        "no_schema_keys_named": (("settled_reply",), ":my.fabricated/key"),
    },
    "C": {
        "nonce_bound": (("fixture_expenses", 0, "sample_nonce"), "wrong"),
        "exactly_one_message": (("messages",), []),
        "correct_recipient": (("messages", 0, "to_agent_id"), "wrong-peer"),
        "derived_total_only": (("messages", 0, "content"), "$1.00"),
    },
    "D": {
        "nonce_bound": (("fixture_expenses", 0, "sample_nonce"), "wrong"),
        "same_agent_eid": (("after", "agent_eid"), 202),
        "same_branch_lineage": (("after", "commit_lineage"), ["commit-after"]),
        "published_row_survived": (("post_restart_functions", 0, "spec"), ""),
        "published_function_used": (("phase2_forms", 0, "source"), "(recompute)"),
        "no_parallel_function": (("post_restart_functions",), [
            {"sym": "my.agent.nonce-alpha/totals-by-vendor",
             "spec": "[:=> [:cat :map] :map]"},
            {"sym": "my.agent.nonce-alpha/totals-by-vendor-v2",
             "spec": "[:=> [:cat :map] :map]",
             "published_phase": "phase2"}]),
        "correct_max_vendor_total": (("settled_reply",), "$9.00"),
    },
    "E1": {
        "nonce_bound": (("run", "sample_nonce"), "wrong"),
        "interrupted_receipt": (("eval_receipts", 0, "interrupted_at"), None),
        "fn_entries_reported": (("eval_receipts", 0, "fn_entries"), None),
        "no_core_fault": (("core_faults",), [{"fault": "agent-mistake"}]),
        "later_success": (("eval_receipts", 1, "error"), {"error": "failed"}),
        "run_closed": (("run", "closed_at"), None),
        "recovered_reply": (("settled_reply",), ""),
    },
    "E2": {
        "nonce_bound": (("base_probe", "sample_nonce"), "wrong"),
        "base_intact": (("base_probe", "result"), 41),
        "override_confined": (("published_overrides", 0, "namespace"),
                              "clojure.core"),
        "refusal_flat_value": (("eval_receipts", 0, "error_value"), None),
        "no_core_fault": (("core_faults",), [{"fault": "agent-mistake"}]),
        "run_closed": (("run", "closed_at"), None),
        "recovered_reply": (("settled_reply",), ""),
    },
}


for _part in ("A", "B", "C"):
    for _name, (_path, _value) in CHECK_MUTATIONS[_part].items():
        CHECK_MUTATIONS.setdefault("F", {})[f"{_part}.{_name}"] = (
            (_part,) + _path, _value)

CHECK_MUTATIONS["F"].update({
    "shared_nonce": (("B", "sample_nonce"), "different-nonce"),
    "same_agent": (("B", "agent", "id"), "different-agent"),
    "same_episode": (("B", "episode_id"), "different-episode"),
})


TAXONOMY_MUTATIONS: dict[str, dict[str, tuple[tuple[Any, ...], Any]]] = {
    "A": {
        "no_code": (("run_forms", 0, "parsed_as_code"), False),
        "uncontracted_code": (("functions", 0, "spec"), ""),
        "wrong_family": (("post_walk", 0, "rendered"), "floor"),
        "talked_about_it": (("run_forms",), []),
    },
    "B": {
        "hallucinated_walk": (("settled_reply",), ":my.fabricated/key"),
        "partial_read": (("settled_reply",), ":my.message/to"),
        "refused": (("reply_refused",), True),
    },
    "B-control": {
        "hallucinated_walk": (("settled_reply",), ":my.fabricated/key"),
    },
    "C": {
        "no_toolkit_call": (("messages",), []),
        "wrong_recipient": (("messages", 0, "to_agent_id"), "wrong-peer"),
        "right_call_wrong_value": (("messages", 0, "content"), "$1.00"),
        "told_the_user_instead": (("messages",), []),
    },
    "D": {
        "lost_the_work": (("post_restart_functions",), []),
        "present_unused": (("phase2_forms", 0, "source"), "(recompute)"),
        "rebuilt_parallel": CHECK_MUTATIONS["D"]["no_parallel_function"],
        "wrong_answer": (("settled_reply",), "$9.00"),
    },
    "E1": {
        "wedged": (("run", "closed_at"), None),
        "fault_misfiled": (("core_faults",), [{"fault": "agent-mistake"}]),
        "silent_kill": (("eval_receipts",), []),
    },
    "E2": {
        "wedged": (("run", "closed_at"), None),
        "base_mutated": (("base_row_after", "identity"), 9999),
        "fault_misfiled": (("core_faults",), [{"fault": "agent-mistake"}]),
        "silent_kill": (("eval_receipts",), []),
    },
}


def _replace_path(snapshot: dict, path: tuple[Any, ...], value: Any) -> None:
    cursor: Any = snapshot
    for key in path[:-1]:
        cursor = cursor[key]
    cursor[path[-1]] = deepcopy(value)


def bad_snapshot(scenario: str, failure: str | None = None, *,
                 taxonomy: bool = False) -> dict:
    """Return a golden snapshot with exactly one named field replaced."""
    mutations = TAXONOMY_MUTATIONS if taxonomy else CHECK_MUTATIONS
    choices = mutations.get(scenario, {})
    if not choices:
        raise ValueError(f"no {'taxonomy' if taxonomy else 'check'} mutations "
                         f"for scenario {scenario!r}")
    selected = failure or next(iter(choices))
    if selected not in choices:
        raise ValueError(f"unknown mutation {scenario}.{selected}")
    snapshot = deepcopy(GOOD[scenario])
    path, value = choices[selected]
    _replace_path(snapshot, path, value)
    if taxonomy and scenario == "A" and selected in {"no_code", "talked_about_it"}:
        # A real no-code/talk-only episode cannot simultaneously have
        # published the function and changed the walk. Remove those derived
        # consequences so the taxonomy fixture is a coherent database state.
        snapshot["functions"] = []
        snapshot["behavior_call"] = {}
        snapshot["post_walk"] = deepcopy(snapshot["pre_walk"])
    if taxonomy and scenario == "C" and selected == "told_the_user_instead":
        total = _expense_total(snapshot)
        snapshot["settled_reply"] = (
            f"The total is {_dollars(total)}." if total is not None else "")
    return snapshot


@scorer(metrics=[accuracy()])
def mvp_graduation_scorer() -> Scorer:
    """Adapt one pure scenario check to Inspect's CORRECT/INCORRECT rail."""
    async def score(state: TaskState, target: Target) -> Score:
        scenario = state.metadata.get("scenario")
        snapshot = state.metadata.get("database_snapshot")
        if scenario not in CHECKS or not isinstance(snapshot, dict):
            result = _result({"database_snapshot": False})
        else:
            result = CHECKS[scenario](snapshot)
        return Score(value=CORRECT if result["ok"] else INCORRECT,
                     explanation=json.dumps(result, sort_keys=True),
                     metadata=result)
    return score
