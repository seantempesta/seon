"""Offline tests for the long_term_planning row — generator + oracle + wiring.

Proves, with no pod and no network: the two-phase task texts state every
oracle check (contract-in-text), the synthesis targets are consistent, the
trajectory oracle passes a known-good planned→interrupted→resumed snapshot
and fails the known-bad shapes (re-planned from scratch / lost state /
unfinished pre-restart steps / no plan / wrong final answer), and the
two-phase choreography sequences its injected effects correctly.
"""

from __future__ import annotations

import pytest

from seon_inspect import freeze
from seon_inspect.generators import MIN_PRE_STEPS, generate_rows
from seon_inspect.planning import (check_plan_trajectory, check_planning,
                                   run_planning_sample)

ROW = "long_term_planning"
DEV_N = freeze.BESPOKE_ROWS[ROW]["dev_n"]

T = 5_000  # the interruption boundary (epoch ms) in every synthetic fixture


def _samples():
    return generate_rows(ROW, 1, DEV_N) + generate_rows(ROW, 2, DEV_N)


def _step(sid, title, status, created, completed=None, parent=None,
          msg=False):
    return {"id": sid, "title": title, "status": status,
            "created_at_ms": created, "completed_at_ms": completed,
            "parent_id": parent, "from_message": msg}


def _good_snapshot():
    """Planned pre-restart, partially done pre, RESUMED and finished post."""
    return [
        _step("r", "ship the tally", "open", 1_000),           # root, derived
        _step("a", "record batch one", "done", 1_001, 2_000, "r"),
        _step("b", "record batch two", "done", 1_002, 6_000, "r"),  # resumed
        _step("c", "compute and report", "done", 1_003, 6_500, "r"),
    ]


# --- generator: two-phase shape + contract stated in the text ----------------


def test_planning_rows_have_two_phases_and_consistent_targets():
    for s in _samples():
        meta = s["metadata"]
        oracle = meta["oracle"]
        assert meta["phase2_input"], s["id"]
        assert meta["setup"] == {}
        assert s["target"] == oracle["final"]["answer"]
        assert oracle["resume"]["min_pre_steps"] == MIN_PRE_STEPS
        assert oracle["final"]["kind"] in ("integer", "text")
        if oracle["final"]["kind"] == "integer":
            int(oracle["final"]["answer"])  # a real number
        else:
            assert oracle["final"]["distractors"]


def test_planning_contract_is_stated_in_both_phases():
    """Every trajectory check the oracle makes is stated to the agent (the
    load-bearing finding) — in phase 1 the full contract, in phase 2 the
    resume restatement."""
    for s in _samples():
        p1 = s["input"]
        p2 = s["metadata"]["phase2_input"]
        # phase 1: durable plan + restart survival + resume-not-replan +
        # nothing left open + answer only when done
        assert "record a durable plan" in p1
        assert "runtime will be restarted" in p1
        assert "do not start a new plan from scratch" in p1
        assert "no step of your plan may be left open" in p1
        assert "when every step of your plan is done" in p1
        # phase 2: the restart happened + resume + finish + answer
        assert "runtime has been restarted" in p2
        assert "Resume from the plan you recorded before the restart" in p2
        assert "do not start a new plan from scratch" in p2
        assert "reply with only" in p2


def test_planning_phase2_carries_data():
    """Phase 2 must deliver real remaining data (>= 2 facts) — the synthesis
    is only computable if both phases' work landed."""
    for s in _samples():
        p2 = s["metadata"]["phase2_input"]
        segment = p2.split("remaining data: ", 1)[1].split(". Resume", 1)[0]
        assert segment and ";" in segment, s["id"]


# --- trajectory oracle: known good --------------------------------------------


def test_trajectory_known_good():
    oracle = {"resume": {"min_pre_steps": MIN_PRE_STEPS}}
    res = check_plan_trajectory(_good_snapshot(), T, oracle)
    assert res["ok"] is True, res["failures"]
    assert res["resumed_steps"] >= 1


def test_trajectory_open_root_is_fine():
    """The root's stored status stays :open by design (my.plan derives
    parent done-ness) — only unfinished pre-restart LEAVES fail."""
    snap = _good_snapshot()
    assert snap[0]["status"] == "open" and snap[0]["parent_id"] is None
    res = check_plan_trajectory(
        snap, T, {"resume": {"min_pre_steps": MIN_PRE_STEPS}})
    assert res["ok"] is True, res["failures"]


def test_trajectory_post_restart_leaf_under_old_root_is_fine():
    """Adding a new step under the EXISTING plan after the restart is normal
    re-scoping, not re-planning."""
    snap = _good_snapshot() + [
        _step("d", "double-check the total", "done", 6_100, 6_200, "r")]
    res = check_plan_trajectory(
        snap, T, {"resume": {"min_pre_steps": MIN_PRE_STEPS}})
    assert res["ok"] is True, res["failures"]


# --- trajectory oracle: known bad ---------------------------------------------


def _fails(snap, needle):
    res = check_plan_trajectory(
        snap, T, {"resume": {"min_pre_steps": MIN_PRE_STEPS}})
    assert res["ok"] is False
    assert any(needle in f for f in res["failures"]), res["failures"]
    return res


def test_trajectory_replanned_from_scratch_fails():
    snap = [
        _step("r", "old plan", "open", 1_000),
        _step("a", "old step one", "open", 1_001, parent="r"),
        _step("b", "old step two", "open", 1_002, parent="r"),
        # abandoned the old plan, minted a fresh one post-restart
        _step("r2", "the tally, again", "open", 6_000),
        _step("x", "record everything", "done", 6_001, 6_500, "r2"),
    ]
    _fails(snap, "re-planned from scratch")
    _fails(snap, "left unfinished")


def test_trajectory_lost_state_fails():
    """Pre-restart steps exist but none is completed after the restart —
    the work was redone (or dropped), not resumed."""
    snap = [
        _step("r", "the tally", "open", 1_000),
        _step("a", "record batch one", "done", 1_001, 2_000, "r"),
        _step("b", "record batch two", "open", 1_002, parent="r"),
    ]
    _fails(snap, "no pre-restart step was completed after the restart")


def test_trajectory_no_plan_fails():
    res = check_plan_trajectory(
        [], T, {"resume": {"min_pre_steps": MIN_PRE_STEPS}})
    assert res["ok"] is False
    assert "no durable pre-restart plan" in res["failures"][0]


def test_trajectory_message_minted_steps_do_not_count_as_planning():
    """The runtime auto-mints a step from the inbound message — that is not
    agent planning."""
    snap = [
        _step("m", "address the message", "done", 1_000, 6_000, msg=True)]
    res = check_plan_trajectory(
        snap, T, {"resume": {"min_pre_steps": MIN_PRE_STEPS}})
    assert res["ok"] is False
    assert "no durable pre-restart plan" in res["failures"][0]


def test_trajectory_unfinished_pre_leaf_fails():
    snap = _good_snapshot()
    snap[3] = _step("c", "compute and report", "open", 1_003, parent="r")
    _fails(snap, "left unfinished")


# --- the combined oracle --------------------------------------------------------


def test_check_planning_requires_both_parts():
    sample = generate_rows(ROW, 1, 1)[0]
    oracle = sample["metadata"]["oracle"]
    answer = oracle["final"]["answer"]

    good = check_planning(f"All steps done. The answer is {answer}",
                          _good_snapshot(), T, oracle)
    assert good["ok"] is True, good

    wrong_answer = check_planning("The answer is 999999",
                                  _good_snapshot(), T, oracle)
    assert wrong_answer["ok"] is False
    assert wrong_answer["trajectory"]["ok"] is True  # attribution preserved

    bad_plan = check_planning(f"The answer is {answer}", [], T, oracle)
    assert bad_plan["ok"] is False
    assert bad_plan["final"]["ok"] is True  # attribution preserved


# --- run wiring: choreography with injected fakes --------------------------------


def test_run_planning_sample_sequences_and_assembles():
    order = []
    clock = iter([4_000, 5_000, 8_000])
    snap = _good_snapshot()

    def phase1(text):
        order.append(("phase1", text))
        return {"agent_id": "a-1", "reply": "planned and recorded batch one"}

    def restart():
        order.append(("restart",))

    def phase2(text, r1):
        order.append(("phase2", text, r1["agent_id"]))
        return {"agent_id": "a-1", "reply": "resumed; the answer is 42"}

    def fetch(r1):
        order.append(("snapshot", r1["agent_id"]))
        return snap

    out = run_planning_sample(
        "p1 text", "p2 text",
        run_phase1=phase1, restart_pod=restart, run_phase2=phase2,
        fetch_snapshot=fetch, clock_ms=lambda: next(clock))

    assert [o[0] for o in order] == ["phase1", "restart", "phase2",
                                     "snapshot"]
    assert order[2][2] == "a-1"  # phase 2 resumes the SAME agent
    # the interruption timestamp is taken between phase 1 and the restart
    assert out["t_interrupt_ms"] == 4_000
    assert out["reply"] == "resumed; the answer is 42"
    assert out["plan_snapshot"] is snap
    assert out["phase1"]["agent_id"] == "a-1"


def test_live_driver_choreography_with_fakes(monkeypatch):
    # the LIVE driver's wiring, effects faked: create → phase1 → restart
    # (fresh port) → phase2 with the SAME agent_id → snapshot via the wire
    # REPL against the CLUSTER db → destroy ALWAYS
    import seon_inspect.cluster as cl
    import seon_inspect.planning as planning
    import seon_inspect.solver as solver

    order = []
    snap = _good_snapshot()

    monkeypatch.setattr(cl, "create_cluster",
                        lambda name, ephemeral, **kw: (order.append(("create", name, ephemeral))
                                                       or cl.Cluster(name, 40001)))
    monkeypatch.setattr(cl, "restart_pod",
                        lambda c, **kw: (order.append(("restart", c.name))
                                         or cl.Cluster(c.name, 40002)))
    monkeypatch.setattr(cl, "destroy_cluster",
                        lambda name: order.append(("destroy", name)))
    monkeypatch.setattr(planning, "fetch_plan_snapshot",
                        lambda cname, aid: (order.append(("snapshot", cname, aid))
                                            or snap))
    monkeypatch.setattr(planning, "fetch_eval_rows",
                        lambda cname, aid: (order.append(("evals", cname, aid))
                                            or []))

    def fake_run(text, timeout_ms, url, agent_id=None):
        order.append(("run", url, agent_id))
        return {"agent_id": agent_id or "a-9", "reply": f"ran: {text[:6]}"}

    monkeypatch.setattr(solver, "pod_run", fake_run)

    out = planning.pod_planning_driver("p1 text", "p2 text",
                                       cluster_name="plan-t")
    kinds = [o[0] for o in order]
    assert kinds == ["create", "run", "restart", "run", "snapshot", "evals", "destroy"]
    assert order[0] == ("create", "plan-t", True)
    assert order[1][1].endswith(":40001/agents/run")  # phase 1: pre-restart port
    assert order[3][1].endswith(":40002/agents/run")  # phase 2: the NEW port
    assert order[3][2] == "a-9"                       # SAME agent resumed
    assert order[4] == ("snapshot", "plan-t", "a-9")
    assert out["plan_snapshot"] is snap
    assert out["cluster"] == "plan-t" and out["agent_id"] == "a-9"


def test_live_driver_destroys_on_failure(monkeypatch):
    import seon_inspect.cluster as cl
    import seon_inspect.planning as planning
    import seon_inspect.solver as solver

    destroyed = []
    monkeypatch.setattr(cl, "create_cluster",
                        lambda name, ephemeral, **kw: cl.Cluster(name, 40001))
    monkeypatch.setattr(cl, "destroy_cluster", destroyed.append)

    def boom(*a, **k):
        raise RuntimeError("phase 1 blew up")

    monkeypatch.setattr(solver, "pod_run", boom)
    with pytest.raises(RuntimeError, match="phase 1 blew up"):
        planning.pod_planning_driver("p1", "p2", cluster_name="plan-x")
    assert destroyed == ["plan-x"]


def test_snapshot_form_is_one_line_with_sentinels():
    # the wire REPL reads ONE form per connection and the extractor keys on
    # the sentinel — the interpolated form must keep both properties
    from seon_inspect.planning import _SNAPSHOT_FORM
    form = _SNAPSHOT_FORM % ("plan-t", '"a-9"')
    assert "\n" not in form
    assert form.count("WIRE-JSON") == 2
    assert ":seon.db.registry/database-name :plan-t" in form
    assert '"a-9"' in form


# --- rung-2 process checks (2026-07-10): decompose-first + close-adjacency ----


def _ev(source, ok=True, at=1_000):
    return {"source": source, "ok": ok, "at_ms": at}


_WORK = '(db/transact! {:seon.db/tx-data [{:my.cargo/name "x"}]})'


def test_decompose_first_good():
    evals = [_ev("(in-ns 'my.agent.a)"),
             _ev('(my.plan/plan! {:my.plan/title "t" :my.plan/children []})'),
             _ev(_WORK)]
    from seon_inspect.planning import check_decompose_first
    assert check_decompose_first(evals)["ok"] is True


def test_decompose_first_work_before_plan_fails():
    from seon_inspect.planning import check_decompose_first
    evals = [_ev(_WORK),
             _ev('(my.plan/plan! {:my.plan/title "t"})')]
    assert check_decompose_first(evals)["ok"] is False


def test_decompose_first_no_plan_fails():
    from seon_inspect.planning import check_decompose_first
    assert check_decompose_first([_ev(_WORK)])["ok"] is False


def test_decompose_first_failed_plan_eval_does_not_count():
    from seon_inspect.planning import check_decompose_first
    evals = [_ev('(my.plan/plan! {:bad "shape"})', ok=False),
             _ev(_WORK),
             _ev('(my.plan/plan! {:my.plan/title "t"})')]
    assert check_decompose_first(evals)["ok"] is False


def test_close_adjacency_interleaved_good():
    from seon_inspect.planning import check_close_adjacency
    evals = [_ev('(my.plan/plan! {:my.plan/title "t"})'),
             _ev(_WORK), _ev('(my.plan/done! {:my.plan/id "s1"})'),
             _ev(_WORK), _ev('(my.plan/done! {:my.plan/id "s2"})'),
             _ev('(my.plan/done! {:my.plan/id "s3"})')]  # final pair ok
    res = check_close_adjacency(evals)
    assert res["ok"] is True, res
    assert res["n_closes"] == 3


def test_close_adjacency_batch_close_fails():
    from seon_inspect.planning import check_close_adjacency
    evals = [_ev('(my.plan/plan! {:my.plan/title "t"})'),
             _ev(_WORK), _ev(_WORK),
             _ev('(my.plan/done! {:my.plan/id "s1"})'),
             _ev('(my.plan/done! {:my.plan/id "s2"})'),
             _ev('(my.plan/done! {:my.plan/id "s3"})')]
    res = check_close_adjacency(evals)
    assert res["ok"] is False
    assert res["longest_close_run"] == 3


def test_close_adjacency_no_closes_fails():
    from seon_inspect.planning import check_close_adjacency
    assert check_close_adjacency([_ev(_WORK)])["ok"] is False


def test_check_planning_gates_on_process_checks():
    # answer + trajectory good, but batch-closing evals → INCORRECT overall
    from seon_inspect.planning import check_planning
    snap = _good_snapshot()
    oracle = {"final": {"answer": "42", "kind": "exact"},
              "resume": {"min_pre_steps": MIN_PRE_STEPS}}
    batch = [_ev('(my.plan/plan! {:my.plan/title "t"})'),
             _ev(_WORK),
             _ev('(my.plan/done! {:my.plan/id "a"})'),
             _ev('(my.plan/done! {:my.plan/id "b"})'),
             _ev('(my.plan/done! {:my.plan/id "c"})')]
    res = check_planning("the total is 42", snap, T, oracle, eval_rows=batch)
    assert res["final"]["ok"] and res["trajectory"]["ok"]
    assert res["close_adjacency"]["ok"] is False
    assert res["ok"] is False


def test_run_planning_sample_wires_eval_rows():
    from seon_inspect.planning import run_planning_sample
    out = run_planning_sample(
        "p1", "p2",
        run_phase1=lambda t: {"agent_id": "a-1", "reply": "r1"},
        restart_pod=lambda: None,
        run_phase2=lambda t, r1: {"reply": "r2"},
        fetch_snapshot=lambda r1: [],
        fetch_evals=lambda r1: [_ev(_WORK)],
        clock_ms=lambda: 5_000,
    )
    assert out["eval_rows"] == [_ev(_WORK)]


def test_evals_form_is_one_line_with_sentinels():
    from seon_inspect.planning import _EVALS_FORM
    form = _EVALS_FORM % ("plan-t", '"a-9"')
    assert "\n" not in form
    assert form.count("WIRE-JSON") == 2


def test_close_adjacency_fused_work_and_close_breaks_the_run():
    # a `(do (compute …) (my.plan/done! …))` form is WORK (the strictest
    # adjacency) — it must break a close run, never extend it (rung-2 d1)
    from seon_inspect.planning import check_close_adjacency
    evals = [_ev('(my.plan/done! {:my.plan/id "a"})'),
             _ev('(do (println (+ 1 2)) (my.plan/done! {:my.plan/id "b"}))'),
             _ev('(my.plan/done! {:my.plan/id "root"})')]
    res = check_close_adjacency(evals)
    assert res["ok"] is True, res
    assert res["longest_close_run"] == 1


def test_close_adjacency_activity_between_closes_is_fine():
    # active!/tree/prose between closes = the agent was doing something —
    # only STRICTLY consecutive done!s form a batch-dump run
    from seon_inspect.planning import check_close_adjacency
    evals = [_ev('(my.plan/done! {:my.plan/id "a"})'),
             _ev('(my.plan/active! {:my.plan/id "b"})'),
             _ev('(my.plan/done! {:my.plan/id "b"})'),
             {"source": "(all recorded)", "ok": True, "at_ms": 1,
              "narration": "demoted: PROSE, not code"},
             _ev('(my.plan/done! {:my.plan/id "c"})')]
    res = check_close_adjacency(evals)
    assert res["ok"] is True, res
    assert res["longest_close_run"] == 1


def test_decompose_first_prose_is_not_work():
    from seon_inspect.planning import check_decompose_first
    evals = [{"source": "(thinking out loud)", "ok": True, "at_ms": 1,
              "narration": "demoted: PROSE, not code"},
             _ev('(my.plan/plan! {:my.plan/title "t"})'),
             _ev(_WORK)]
    assert check_decompose_first(evals)["ok"] is True


# --- three-arm plan-preload experiment --------------------------------------


def _plan_evidence(arm):
    base = {"observed": True, "observed_at_t": 500,
            "first_turn_t": 100, "agent_eid": 42,
            "harness_plan_tx_ids": [], "roots": [],
            "history_observed": True, "run_historical_root_ids": [],
            "run_root_creation_count": 0, "run_root_creation_tx_ids": []}
    if arm == "pretransacted":
        return {**base, "plan_present": True, "harness_plan_tx_ids": [90],
                "run_historical_root_ids": ["r"],
                "roots": [{"id": "r", "creation_t": 90,
                           "creation_tx_id": 90,
                           "creation_user_eid": 7}]}
    if arm == "model_authored":
        return {**base, "plan_present": True,
                "run_historical_root_ids": ["r"],
                "run_root_creation_count": 1,
                "run_root_creation_tx_ids": [111],
                "roots": [{"id": "r", "creation_t": 110,
                           "creation_tx_id": 111,
                           "creation_user_eid": 42}]}
    return {**base, "plan_present": False}


def _experiment(arm="pretransacted", answer="25", verified=True,
                report="island-loop: 25", address=False,
                observations=None, address_coverage=True,
                plan_evidence=None, report_events=None):
    from seon_inspect.planning import check_plan_experiment
    if observations is None:
        observations = ([] if arm == "no_plan" else [
            {"address_active": address, "authored_open": True}])
    if report_events is None:
        report_events = [{"kind": "message_user", "content": report},
                         {"kind": "run_closed"}]
    return check_plan_experiment(
        arm, {"answer": answer}, {"kind": "integer", "answer": "25"},
        plan_closes=[] if arm == "no_plan" else [
            {"step_id": "s1", "expect_verified": verified}],
        report_events=report_events,
        address_evidence={"coverage_complete": address_coverage,
                          "observations": observations},
        plan_evidence=(_plan_evidence(arm)
                       if plan_evidence is None else plan_evidence),
    )


def test_plan_experiment_pretransacted_arm_passes_all_mechanical_oracles():
    res = _experiment()
    assert res["ok"] is True, res
    assert res["database_outcome"]["ok"] is True
    assert res["plan_integrity"]["verified_closes"] == 1
    assert res["report_delivery"]["matching_before_close"] == 1
    assert res["address_step_discipline"]["violations"] == 0


def test_plan_experiment_model_authored_arm_requires_model_plan_source():
    assert _experiment("model_authored")["ok"] is True
    evidence = _plan_evidence("model_authored")
    evidence["source"] = "model"  # self-asserted and deliberately ignored
    evidence["roots"][0]["creation_user_eid"] = 7
    res = _experiment("model_authored", plan_evidence=evidence)
    assert res["ok"] is False
    assert res["arm_contract"]["ok"] is False


def test_plan_experiment_database_oracle_catches_plausible_wrong_report():
    res = _experiment("no_plan", answer="26", report="island-loop: 26")
    assert res["ok"] is False
    assert res["database_outcome"]["ok"] is False
    assert res["plan_integrity"]["applicable"] is False


def test_plan_experiment_no_plan_control_rejects_hidden_plan():
    evidence = _plan_evidence("no_plan")
    evidence["roots"] = [{"id": "hidden", "creation_t": 110,
                          "creation_tx_id": 111,
                          "creation_user_eid": 42}]
    res = _experiment("no_plan", plan_evidence=evidence)
    assert res["ok"] is False
    assert res["arm_contract"]["presence_consistent"] is False
    assert res["arm_contract"]["ok"] is False


def test_plan_experiment_no_plan_rejects_close_after_deleted_plan():
    from seon_inspect.planning import check_plan_experiment
    res = check_plan_experiment(
        "no_plan", {"answer": "25"}, {"kind": "integer", "answer": "25"},
        plan_closes=[{"step_id": "deleted", "expect_verified": True}],
        report_events=[{"kind": "message_user", "content": "25"},
                       {"kind": "run_closed"}],
        address_evidence={"coverage_complete": False, "observations": []},
        plan_evidence=_plan_evidence("no_plan"))
    assert res["ok"] is False
    assert res["plan_integrity"]["ok"] is False


def test_plan_experiment_no_plan_rejects_created_then_retracted_root():
    evidence = _plan_evidence("no_plan")
    evidence.update({"run_historical_root_ids": ["retracted"],
                     "run_root_creation_count": 1,
                     "run_root_creation_tx_ids": [120]})
    res = _experiment("no_plan", plan_evidence=evidence)
    assert res["ok"] is False
    assert res["arm_contract"]["plan_present"] is False
    assert res["arm_contract"]["run_historical_root_ids"] == ["retracted"]
    assert res["arm_contract"]["ok"] is False


def test_plan_experiment_no_plan_consistent_never_had_plan_passes():
    res = _experiment("no_plan")
    assert res["ok"] is True, res
    assert res["plan_integrity"]["closes"] == 0
    assert res["arm_contract"]["run_root_creation_count"] == 0


def test_plan_experiment_pretransacted_plan_must_predate_first_turn():
    evidence = _plan_evidence("pretransacted")
    evidence["roots"][0]["creation_t"] = evidence["first_turn_t"] + 1
    res = _experiment(plan_evidence=evidence)
    assert res["ok"] is False
    assert res["arm_contract"]["ok"] is False


@pytest.mark.parametrize("creation_t", [100, 500])
def test_plan_experiment_model_plan_accepts_inclusive_run_boundaries(creation_t):
    evidence = _plan_evidence("model_authored")
    evidence["roots"][0]["creation_t"] = creation_t
    res = _experiment("model_authored", plan_evidence=evidence)
    assert res["ok"] is True, res


def test_plan_experiment_rejects_root_created_after_observation_basis():
    evidence = _plan_evidence("model_authored")
    evidence["roots"][0]["creation_t"] = evidence["observed_at_t"] + 1
    res = _experiment("model_authored", plan_evidence=evidence)
    assert res["ok"] is False
    assert res["arm_contract"]["roots_observed_by_basis"] is False


def test_plan_experiment_requires_final_interval_creation_in_history_ids():
    evidence = _plan_evidence("model_authored")
    evidence["run_root_creation_count"] = 0
    evidence["run_root_creation_tx_ids"] = []
    res = _experiment("model_authored", plan_evidence=evidence)
    assert res["ok"] is False
    assert res["arm_contract"]["interval_creations_consistent"] is False


def test_plan_experiment_missing_plan_evidence_fails():
    res = _experiment(plan_evidence={"observed": False})
    assert res["ok"] is False
    assert res["arm_contract"]["evidence_sufficient"] is False


def test_plan_experiment_fails_unverified_close_and_address_capture():
    res = _experiment(verified=False, address=True)
    assert res["ok"] is False
    assert res["plan_integrity"]["ok"] is False
    assert res["address_step_discipline"]["violations"] == 1


def test_plan_experiment_plan_arm_requires_address_observation_coverage():
    res = _experiment(observations=[])
    assert res["ok"] is False
    assert res["address_step_discipline"]["evidence_sufficient"] is False
    incomplete = _experiment(address_coverage=False)
    assert incomplete["ok"] is False
    assert incomplete["address_step_discipline"]["coverage_complete"] is False


def test_plan_experiment_no_plan_address_metric_requires_derived_absence():
    good = _experiment("no_plan")
    assert good["address_step_discipline"]["applicable"] is False
    assert good["address_step_discipline"]["evidence_sufficient"] is True
    missing = _experiment("no_plan", plan_evidence={"observed": False})
    assert missing["address_step_discipline"]["ok"] is False


def test_plan_experiment_requires_human_report_before_close():
    from seon_inspect.planning import check_plan_experiment
    res = check_plan_experiment(
        "pretransacted", {"answer": "25"},
        {"kind": "integer", "answer": "25"},
        plan_closes=[{"step_id": "s1", "expect_verified": True}],
        report_events=[{"kind": "run_closed"},
                       {"kind": "message_user", "content": "25"}],
        address_evidence={"coverage_complete": True,
                          "observations": [{"address_active": False,
                                            "authored_open": True}]},
        plan_evidence=_plan_evidence("pretransacted"))
    assert res["ok"] is False
    assert res["report_delivery"]["ok"] is False


def test_plan_experiment_report_without_run_closed_is_insufficient():
    res = _experiment(report_events=[
        {"kind": "message_user", "content": "island-loop: 25"}])
    assert res["ok"] is False
    assert res["report_delivery"]["evidence_sufficient"] is False
    assert res["report_delivery"]["run_closed_observed"] is False
