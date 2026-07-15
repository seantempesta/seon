"""Offline tests for the capability-milestone oracles (ported minimal-buildup).

Proves, with no pod and no network: the `namespaces` and `db` oracles pass a
known-good structured eval-row sequence and fail each known-bad shape; the
fabrication metric flags a fabricated reply and not a clean one; the contract
texts carry no leaked lock canary; and the single-phase run wiring sequences
its injected effects. These are the discrimination proofs that replace the old
`tools/ns-move-oracle.py` / `db-memory-oracle.py` transcript scanners.
"""

from __future__ import annotations

import pytest

from seon_inspect.milestone import (DB_MEMORY_CONTRACT, NS_MOVEMENT_CONTRACT,
                                    check_milestone, check_ns_movement,
                                    check_store_recall, count_fabrication,
                                    fabrication_summary,
                                    pod_milestone_driver,
                                    run_milestone_sample)


def _ev(*sources_ok):
    return [{"source": s, "ok": ok} for s, ok in sources_ok]


# ---------------------------------------------------------------------------
# namespaces milestone
# ---------------------------------------------------------------------------

_NS_GOOD_ROWS = _ev(
    ("(in-ns 'my.units)", True),
    ("(schema/register! :my.units/name [:string {:seon.db/identity true}])", True),
    ("(in-ns 'my.convert)", True),
    ("(defn to-feet [m] (* m 3.28))", True),
    ("(require '[clojure.string :as str])", True),
    ("(defn to-feet [m] (* m 3.28084))", True),
)
_NS_GOOD_REPLY = "Total meters 42.5, total feet 139.44."


def test_ns_movement_good():
    r = check_ns_movement(_NS_GOOD_ROWS, _NS_GOOD_REPLY)
    assert r["ok"], r["failures"]
    assert all(r["checks"].values())


def test_ns_movement_ns_form_counts_as_movement():
    rows = _ev(
        ("(ns my.units)", True),
        ("(schema/register! :my.units/name :string)", True),
        ("(ns my.convert)", True),
        ("(defn to-feet [m] (* m 3.28))", True),
        ("(require '[clojure.string :as str])", True),
        ("(defn to-feet [m] (* m 3.28084))", True),
    )
    assert check_ns_movement(rows, _NS_GOOD_REPLY)["checks"]["movement"]


def test_ns_movement_no_parallel_fork_fails():
    # never moved into my.convert, and a PARALLEL -v2 fork instead of an
    # in-place redefine — the fork is the specific failure signal.
    rows = _ev(
        ("(in-ns 'my.units)", True),
        ("(schema/register! :my.units/name :string)", True),
        ("(defn to-feet-v2 [m] (* m 3.28084))", True),
    )
    r = check_ns_movement(rows, _NS_GOOD_REPLY)
    assert not r["ok"]
    assert "no_parallel_fork" in r["failures"]
    assert "movement" in r["failures"]  # my.convert never entered


def test_ns_movement_schema_before_move_fails():
    rows = _ev(
        ("(schema/register! :my.units/name :string)", True),  # BEFORE any move
        ("(in-ns 'my.units)", True),
        ("(in-ns 'my.convert)", True),
        ("(defn to-feet [m] (* m 3.28))", True),
        ("(require '[clojure.string :as str])", True),
        ("(defn to-feet [m] (* m 3.28084))", True),
    )
    assert "schema_after_move" in check_ns_movement(rows, _NS_GOOD_REPLY)["failures"]


def test_ns_movement_failed_eval_does_not_count():
    # the require eval FAILED — bare_require must miss
    rows = _NS_GOOD_ROWS[:4] + _ev(
        ("(require '[clojure.string :as str])", False)) + _NS_GOOD_ROWS[5:]
    assert "bare_require" in check_ns_movement(rows, _NS_GOOD_REPLY)["failures"]


def test_ns_movement_report_values_needs_both_numbers():
    assert "report_values" in check_ns_movement(
        _NS_GOOD_ROWS, "Total meters 42.5.")["failures"]  # feet missing


# ---------------------------------------------------------------------------
# db milestone (store-then-recall)
# ---------------------------------------------------------------------------

_DB_GOOD_ROWS = _ev(
    ("(schema/register! :my.cache/weight-kg :double)", True),
    ("(db/transact! [{:my.cache/name \"KESTREL\" :my.cache/weight-kg 42.5}])", True),
    ("(db/query '[:find (sum ?w) . :where [?e :my.cache/weight-kg ?w] [(> ?w 10)]])",
     True),
)
_DB_GOOD_REPLY = "The total weight of caches over 10 kg is 59.5 kg."


def test_store_recall_good():
    r = check_store_recall(_DB_GOOD_ROWS, _DB_GOOD_REPLY)
    assert r["ok"], r["failures"]
    assert r["transact_idx"] == 1 and r["query_idx"] == 2


def test_store_recall_query_before_transact_fails():
    rows = _ev(
        ("(db/query '[:find (sum ?w) . :where [?e :my.cache/weight-kg ?w]])", True),
        ("(db/transact! [{:my.cache/name \"KESTREL\" :my.cache/weight-kg 42.5}])",
         True),
    )
    r = check_store_recall(rows, _DB_GOOD_REPLY)
    assert not r["ok"]
    assert "query_later" in r["failures"]


def test_store_recall_answer_absent_fails():
    assert "answer" in check_store_recall(
        _DB_GOOD_ROWS, "I stored the caches.")["failures"]


def test_generated_database_workflow_uses_structured_oracle():
    from seon_inspect.generators import generate_rows

    sample = generate_rows("database_workflow", 1, 1)[0]
    oracle = sample["metadata"]["oracle"]
    identity = oracle["identity_attr"]
    measure = oracle["measure_attr"]
    rows = _ev(
        (f"(schema/register! {identity} [:string {{:seon.db/identity true}}])", True),
        (f"(schema/register! {measure} :int)", True),
        (f"(db/transact! [{{{identity} \"a\" {measure} 12}}])", True),
        (f"(db/query '[:find (sum ?v) . :where [?e {measure} ?v]])", True),
    )
    result = check_milestone("db", rows, oracle["answer"], oracle)
    assert result["ok"], result["failures"]
    assert all(result["checks"].values())
    assert "schema_register" in result["checks"]


def test_generated_namespace_workflow_uses_structured_oracle():
    from seon_inspect.generators import generate_rows

    sample = generate_rows("namespace_workflow", 2, 1)[0]
    oracle = sample["metadata"]["oracle"]
    schema_ns = oracle["schema_namespace"]
    function_ns = oracle["function_namespace"]
    function_name = oracle["function_name"]
    rows = _ev(
        (f"(in-ns '{schema_ns})", True),
        (f"(schema/register! {oracle['schema_attr']} "
         "[:string {:seon.db/identity true}])", True),
        (f"(in-ns '{function_ns})", True),
        (f"(defn {function_name} [x] (* x 2.20))", True),
        ("(require '[clojure.string :as str])", True),
        (f"(defn {function_name} [x] (* x {oracle['precise_literal']}))", True),
    )
    reply = f"{oracle['source_total']} and {oracle['converted_total']}"
    result = check_milestone("namespaces", rows, reply, oracle)
    assert result["ok"], result["failures"]
    forked = rows + _ev((f"(defn {function_name}-v2 [x] x)", True))
    assert "no_parallel_fork" in check_milestone(
        "namespaces", forked, reply, oracle)["failures"]


def test_check_milestone_dispatch_and_unknown():
    assert check_milestone("db", _DB_GOOD_ROWS, _DB_GOOD_REPLY)["ok"]
    assert check_milestone("namespaces", _NS_GOOD_ROWS, _NS_GOOD_REPLY)["ok"]
    with pytest.raises(ValueError):
        check_milestone("plan", [], "")  # plan is seon_inspect.planning


# ---------------------------------------------------------------------------
# fabrication metric (report-only)
# ---------------------------------------------------------------------------

def test_fabrication_flags_glyph_and_fake_env():
    assert count_fabrication("Result ⟹ {:ok? true}")["fabricated"]
    assert count_fabrication(";;=> {:seon.agent.eval/ok? true}")["fabricated"]
    assert count_fabrication("All 37 tests pass now.")["pass_claim"] >= 1
    # a pass-claim alone is a soft tell, NOT the hard `fabricated` gate
    assert not count_fabrication("All 37 tests pass now.")["fabricated"]


def test_fabrication_clean_reply():
    c = count_fabrication("The total is 59.5 kg, computed from the query.")
    assert not c["fabricated"] and c["glyph"] == 0 and c["fake_env"] == 0


def test_fabrication_summary_rate():
    s = fabrication_summary(["clean", "⟹ fake", "clean"])
    assert s["turns"] == 3 and s["fab_turns"] == 1
    assert s["rate"] == pytest.approx(1 / 3)


# ---------------------------------------------------------------------------
# contracts + run wiring
# ---------------------------------------------------------------------------

def test_contracts_carry_no_leaked_canary():
    # lock canaries never live in package source (tests/test_canary_guard.py);
    # the ported contract text must be canary-free.
    for txt in (NS_MOVEMENT_CONTRACT, DB_MEMORY_CONTRACT):
        assert "canary" not in txt.lower()
        assert "SEON-CANARY-" not in txt


def test_run_milestone_sample_wires_effects():
    calls = {}

    def run(text):
        calls["contract"] = text
        return {"reply": _DB_GOOD_REPLY, "agent_id": "agent-1"}

    def fetch(r):
        calls["agent"] = r["agent_id"]
        return _DB_GOOD_ROWS

    res = run_milestone_sample(DB_MEMORY_CONTRACT, "db",
                               run=run, fetch_evals=fetch,
                               clock_ms=lambda: 42)
    assert calls["contract"] == DB_MEMORY_CONTRACT
    assert calls["agent"] == "agent-1"
    assert res["milestone"] == "db"
    assert res["eval_rows"] == _DB_GOOD_ROWS
    assert not res["fabrication"]["fabricated"]
    assert check_store_recall(res["eval_rows"], res["reply"])["ok"]


def test_pod_driver_uses_explicit_static_coordinates(monkeypatch):
    calls = {}

    def fake_run(text, timeout_ms, url):
        calls["run"] = (text, timeout_ms, url)
        return {"reply": _DB_GOOD_REPLY, "agent_id": "a-static"}

    def fake_fetch(cluster, agent_id, *, port=None):
        calls["fetch"] = (cluster, agent_id, port)
        return _DB_GOOD_ROWS

    monkeypatch.setattr("seon_inspect.solver.pod_run", fake_run)
    monkeypatch.setattr("seon_inspect.planning.fetch_eval_rows", fake_fetch)
    monkeypatch.setattr("seon_inspect.cluster.assert_llm_live",
                        lambda cluster: calls.setdefault("live", cluster))
    result = pod_milestone_driver(
        DB_MEMORY_CONTRACT, "db",
        cluster_url="http://127.0.0.1:7994/agents/run",
        cluster_name="acme", writer_port=7991, timeout_ms=1234)

    assert calls["run"] == (DB_MEMORY_CONTRACT, 1234,
                            "http://127.0.0.1:7994/agents/run")
    assert calls["fetch"] == ("acme", "a-static", 7991)
    assert calls["live"] == "acme"
    assert result["cluster"] == "acme"


def test_generated_milestone_task_requires_and_records_static_target():
    from seon_inspect.tasks.milestone_lift import milestone_lift

    with pytest.raises(ValueError, match="explicit cluster_url"):
        milestone_lift(milestone="db", endpoint="pod", seed=1)

    task = milestone_lift(
        milestone="db", endpoint="pod", seed=1, positions=[0], epochs=1,
        cluster_url="http://127.0.0.1:7994/agents/run",
        cluster_name="acme", writer_port=7991)
    assert len(task.dataset) == 1
    sample = task.dataset[0]
    assert sample.id == "database_workflow-seed1-000"
    assert sample.metadata["milestone"] == "db"
    assert sample.metadata["oracle"]["measure_attr"].startswith(":my.")
