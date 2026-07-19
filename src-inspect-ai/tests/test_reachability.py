"""Discrimination and native wiring for experimental reachability rows."""

from __future__ import annotations

import copy

import pytest
from inspect_ai import eval as inspect_eval
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect.reachability import (
    ACME_LOCATION,
    ACME_TAGLINE,
    REPL_SKILL_MARKER,
    ROOT_PURPOSE,
    ROWS,
    check_reachability,
)
from seon_inspect.tasks import namespace_reachability as task_module


FINAL = {
    "db_name": "db-1",
    "commit_id": "final",
    "t": 100,
}
ADMISSION = {"schema_version": 2, "bench": {"name": "test"}}


def _home(name, requires):
    requires_text = "\n    ".join(f"[{target} :as x{index}]" for index, target in enumerate(requires))
    return (
        f";;; ┌─ namespace {name} ─\n"
        f"(ns {name}\n  (:require\n    {requires_text}))\n"
        f";;; └─ end namespace {name} ─\n"
    )


def _card(symbol):
    return f"; fn {symbol} — map-in {{:request :string}} -> map-out {{:ok? :boolean}}\n"


def _turn(turn_id, prompt, t):
    return {
        "turn_id": turn_id,
        "prompt": prompt,
        "rendered_transaction": t,
    }


def _eval(turn_id, tx, source):
    return {
        "turn_id": turn_id,
        "eval_transaction": tx,
        "source": source,
        "ok": True,
    }


def _root_fixture():
    child = "quiet-crows-count"
    first = _home("my.agent.root", ["seon.agent", "seon.db"])
    first += _card("seon.agent/start!") + _card("seon.agent/delegate!")
    turns = [
        _turn("root-1", first, 10),
        _turn("root-2", f"; result {{:seon.agent/id \"{child}\"}}", 20),
        _turn("root-3", f"; query result {child}", 30),
    ]
    rows = [
        _eval(
            "root-1",
            1,
            f'(agent/start! {{:seon.agent/purpose "{ROOT_PURPOSE}"}})',
        ),
        _eval(
            "root-2",
            2,
            f'(db/query \'[:find ?id . :in $ ?child-id ?purpose :where '
            f'[?child :seon.agent/id ?child-id] '
            f'[?child :seon.agent/id ?id] '
            f'[?child :seon.agent/purpose ?purpose] '
            f'[?child :seon.agent/parent ?parent] '
            f'[?parent :seon.agent/id "root"]] "{child}" "{ROOT_PURPOSE}")',
        ),
        _eval("root-3", 3, f'(message/user "{child}")'),
        _eval("root-3", 4, f'(complete "{child}")'),
    ]
    return turns, rows, child


def _discovery_fixture():
    first = _home("my.agent.calm-fox", ["my.ns", "seon.db"])
    first += _card("my.ns/functions")
    full = "\n".join(
        f"(defn {name} []\n  :body)" for name in ("fetch", "grants", "search")
    )
    turns = [
        _turn("discover-1", first, 10),
        _turn("discover-2", full, 20),
        _turn("discover-3", "; grants result", 30),
    ]
    rows = [
        _eval(
            "discover-1", 1,
            "(my.ns/functions {:my.ns/ns 'seon.agent.web})",
        ),
        _eval("discover-1", 2, "(in-ns 'seon.agent.web)"),
        _eval("discover-2", 3, "(grants)"),
        _eval("discover-2", 4, "(in-ns 'my.agent.calm-fox)"),
        _eval("discover-3", 5, '(message/user "fetch grants search")'),
        _eval("discover-3", 6, '(complete "fetch grants search")'),
    ]
    return turns, rows, "fetch grants search"


def _skills_fixture():
    first = _home("my.agent.calm-owl", ["my.skills", "seon.db"])
    first += "".join(_card(f"my.skills/{name}") for name in ("list", "load", "unload"))
    turns = [
        _turn("skill-1", first, 10),
        _turn("skill-2", f"; {REPL_SKILL_MARKER}\n; full skill body", 20),
        _turn("skill-3", "; skill body absent", 30),
    ]
    rows = [
        _eval("skill-1", 1, "(my.skills/list)"),
        _eval("skill-1", 2, "(my.skills/load :repl)"),
        _eval("skill-2", 3, "(my.skills/unload :repl)"),
        _eval("skill-3", 4, '(message/user "repl loaded and unloaded")'),
        _eval("skill-3", 5, '(complete "repl loaded and unloaded")'),
    ]
    return turns, rows, "repl loaded and unloaded"


def _acme_fixture():
    first = _home("my.agent.acme-fox", ["acme.brand", "acme.widget", "seon.db"])
    first += _card("acme.brand/tagline") + _card("acme.widget/set-location!")
    turns = [
        _turn("acme-1", first, 10),
        _turn("acme-2", f"; {ACME_TAGLINE}\n; {ACME_LOCATION}", 20),
    ]
    rows = [
        _eval("acme-1", 1, "(acme.brand/tagline)"),
        _eval("acme-1", 2, '(acme.widget/set-location! "Boston")'),
        _eval("acme-2", 3, f'(message/user "{ACME_TAGLINE} {ACME_LOCATION}")'),
        _eval("acme-2", 4, f'(complete "{ACME_TAGLINE} {ACME_LOCATION}")'),
    ]
    return turns, rows, f"{ACME_TAGLINE} {ACME_LOCATION}"


FIXTURES = {
    "root_orchestration": _root_fixture,
    "namespace_discovery": _discovery_fixture,
    "skill_lifecycle": _skills_fixture,
    "acme_product_tools": _acme_fixture,
}


@pytest.mark.parametrize("row", ROWS)
def test_each_golden_retained_trajectory_passes(row):
    turns, eval_rows, reply = FIXTURES[row]()
    result = check_reachability(row, turns, eval_rows, reply, FINAL)
    assert result["ok"], result
    assert all(result["checks"].values())


@pytest.mark.parametrize("row", ROWS)
def test_first_prompt_card_cannot_be_absent_or_delayed(row):
    turns, eval_rows, reply = FIXTURES[row]()
    delayed = copy.deepcopy(turns)
    fn_lines = "\n".join(
        line for line in delayed[0]["prompt"].splitlines() if "fn " in line
    )
    delayed[0]["prompt"] = "\n".join(
        line for line in delayed[0]["prompt"].splitlines() if "fn " not in line
    )
    delayed[-1]["prompt"] += "\n" + fn_lines
    result = check_reachability(row, delayed, eval_rows, reply, FINAL)
    assert not result["checks"]["surface"]


def test_root_surface_requires_delegate_alongside_start():
    turns, eval_rows, reply = _root_fixture()
    turns[0]["prompt"] = "\n".join(
        line for line in turns[0]["prompt"].splitlines()
        if "fn seon.agent/delegate!" not in line
    )
    result = check_reachability(
        "root_orchestration", turns, eval_rows, reply, FINAL
    )
    assert not result["checks"]["surface"]


@pytest.mark.parametrize(
    "excluded",
    [
        "armable-agent-ids",
        "resumable-agent-ids",
        "create!",
        "mint!",
        "ensure-initial-agent!",
        "spawn-depth",
        "resume!",
        "unhost!",
        "set-purpose!",
    ],
)
def test_root_surface_rejects_every_other_public_agent_row(excluded):
    turns, eval_rows, reply = _root_fixture()
    turns[0]["prompt"] += _card(f"seon.agent/{excluded}")
    result = check_reachability(
        "root_orchestration", turns, eval_rows, reply, FINAL
    )
    assert not result["checks"]["surface"]


def test_root_requires_exactly_one_start_without_delegate_or_child_message():
    turns, eval_rows, reply = _root_fixture()
    duplicate = copy.deepcopy(eval_rows[0])
    duplicate["eval_transaction"] = 2
    changed = [eval_rows[0], duplicate, *eval_rows[1:]]
    result = check_reachability(
        "root_orchestration", turns, changed, reply, FINAL
    )
    assert not result["checks"]["selection"]

    turns, eval_rows, reply = _root_fixture()
    eval_rows.insert(1, _eval(
        "root-1", 2,
        f'(agent/delegate! {{:seon.agent/purpose "{ROOT_PURPOSE}" '
        ':seon.agent.message/content "work"})',
    ))
    result = check_reachability(
        "root_orchestration", turns, eval_rows, reply, FINAL
    )
    assert not result["checks"]["selection"]

    turns, eval_rows, reply = _root_fixture()
    eval_rows.insert(1, _eval(
        "root-1", 2, '(message/agent "quiet-crows-count" "work")'
    ))
    result = check_reachability(
        "root_orchestration", turns, eval_rows, reply, FINAL
    )
    assert not result["checks"]["selection"]


def test_root_query_and_dynamic_prompt_must_join_created_child():
    turns, eval_rows, reply = _root_fixture()
    changed = copy.deepcopy(eval_rows)
    changed[1]["source"] = changed[1]["source"].replace(
        "quiet-crows-count", "older-same-purpose-child")
    result = check_reachability(
        "root_orchestration", turns, changed, reply, FINAL
    )
    assert not result["checks"]["verification"]

    turns, eval_rows, reply = _root_fixture()
    changed_turns = copy.deepcopy(turns)
    changed_turns[1]["prompt"] = "; generic :seon.agent/id schema only"
    result = check_reachability(
        "root_orchestration", changed_turns, eval_rows, reply, FINAL
    )
    assert not result["checks"]["dynamic_context"]


@pytest.mark.parametrize("row", ROWS)
def test_correct_final_prose_cannot_replace_execution(row):
    turns, _eval_rows, reply = FIXTURES[row]()
    result = check_reachability(row, turns, [], reply, FINAL)
    assert not result["ok"]
    assert not result["checks"]["selection"]
    assert not result["checks"]["execution"]


@pytest.mark.parametrize("row", ROWS)
def test_terminal_reports_must_follow_the_observation_prompt(row):
    turns, eval_rows, reply = FIXTURES[row]()
    changed = copy.deepcopy(eval_rows)
    report_rows = [
        evidence for evidence in changed
        if "message/user" in evidence["source"] or "(complete" in evidence["source"]
    ]
    for evidence in report_rows:
        evidence["turn_id"] = turns[0]["turn_id"]
    result = check_reachability(row, turns, changed, reply, FINAL)
    assert not result["checks"]["report"]


@pytest.mark.parametrize("row", ROWS)
def test_failed_or_replaced_selected_call_does_not_count(row):
    turns, eval_rows, reply = FIXTURES[row]()
    changed = copy.deepcopy(eval_rows)
    changed[0]["ok"] = False
    changed[0]["source"] = '(db/query "guessed direct result")'
    result = check_reachability(row, turns, changed, reply, FINAL)
    assert not result["ok"]
    assert not result["checks"]["selection"]


@pytest.mark.parametrize("row", ROWS)
def test_later_dynamic_prompt_is_required(row):
    turns, eval_rows, reply = FIXTURES[row]()
    changed = copy.deepcopy(turns)
    for turn in changed[1:]:
        turn["prompt"] = "; no dynamic result or context"
    result = check_reachability(row, changed, eval_rows, reply, FINAL)
    assert not result["checks"]["dynamic_context"]


def test_skill_body_must_be_absent_after_unload():
    turns, eval_rows, reply = _skills_fixture()
    turns[-1]["prompt"] += "\n" + REPL_SKILL_MARKER
    result = check_reachability("skill_lifecycle", turns, eval_rows, reply, FINAL)
    assert not result["checks"]["dynamic_context"]


def test_acme_fixture_requires_fail_replacement_surface():
    turns, eval_rows, reply = _acme_fixture()
    turns[0]["prompt"] = turns[0]["prompt"].replace(
        "[acme.brand :as x0]", "[acme.brand :as x0]\n    [acme.helpers :as fixture]"
    )
    result = check_reachability("acme_product_tools", turns, eval_rows, reply, FINAL)
    assert not result["checks"]["surface"]


def test_stale_database_value_or_reordered_evidence_fails_closed():
    turns, eval_rows, reply = _root_fixture()
    stale = {**FINAL, "t": 2}
    result = check_reachability(
        "root_orchestration", turns, eval_rows, reply, stale)
    assert not result["ok"]
    eval_rows = _root_fixture()[1]
    eval_rows[0], eval_rows[1] = eval_rows[1], eval_rows[0]
    result = check_reachability("root_orchestration", turns, eval_rows, reply, FINAL)
    assert not result["ok"]


def test_turn_rendered_transaction_must_not_exceed_final_database_value():
    turns, eval_rows, reply = _acme_fixture()
    turns[0]["rendered_transaction"] = FINAL["t"] + 1
    result = check_reachability("acme_product_tools", turns, eval_rows, reply, FINAL)
    assert not result["ok"]
    assert not any(result["checks"].values())


def test_task_is_separately_named_live_only_candidate():
    task = task_module.namespace_reachability(
        row="namespace_discovery",
        cluster_url="http://127.0.0.1:7994/agents/run",
        _admission=ADMISSION,
    )
    assert len(task.dataset) == 1
    sample = task.dataset[0]
    assert sample.id == "namespace-reachability-namespace_discovery"
    assert sample.metadata["seon_reachability_row"] == "namespace_discovery"
    assert task.metadata["seon_reachability_candidate"] is True
    assert "P0" not in sample.id


def test_task_rejects_unknown_row_and_absent_live_endpoint():
    with pytest.raises(ValueError, match="unknown reachability"):
        task_module.namespace_reachability(
            row="unknown", cluster_url="http://pod", _admission=ADMISSION
        )
    with pytest.raises(ValueError, match="cluster_url"):
        task_module.namespace_reachability(
            row="namespace_discovery", _admission=ADMISSION
        )



def test_root_row_routes_existing_agent_and_ordinary_row_preserves_absence(
    monkeypatch,
):
    calls = []

    def capture_solver(**kwargs):
        calls.append(kwargs)
        return _golden_pod("root_orchestration")

    monkeypatch.setattr(task_module, "seon_pod_solver", capture_solver)
    task_module.namespace_reachability(
        row="root_orchestration", cluster_url="http://pod",
        _admission=ADMISSION,
    )
    task_module.namespace_reachability(
        row="namespace_discovery", cluster_url="http://pod",
        _admission=ADMISSION,
    )

    assert calls[0]["agent_id"] == "root"
    assert calls[1]["agent_id"] is None


@solver
def _golden_pod(row):
    async def solve(state: TaskState, generate: Generate) -> TaskState:
        turns, eval_rows, reply = FIXTURES[row]()
        state.output.completion = reply
        state.metadata.update({
            "pod_turn_evidence": turns,
            "pod_eval_evidence": eval_rows,
            "pod_database_value": FINAL,
        })
        return state
    return solve


def test_pure_scorer_lands_in_native_inspect_log(monkeypatch, tmp_path):
    monkeypatch.setattr(
        task_module,
        "seon_pod_solver",
        lambda **_kwargs: _golden_pod("acme_product_tools"),
    )
    task = task_module.namespace_reachability(
        row="acme_product_tools",
        cluster_url="http://pod",
        _admission=ADMISSION,
    )
    log = inspect_eval(
        task,
        model="mockllm/model",
        display="none",
        log_dir=str(tmp_path),
    )[0]
    assert log.status == "success", log.error
    score = next(iter(log.samples[0].scores.values()))
    assert score.value == "C"
    assert all(score.metadata["checks"].values())
