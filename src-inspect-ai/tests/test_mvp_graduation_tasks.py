import copy
import subprocess

import pytest
from inspect_ai import eval as inspect_eval

from seon_inspect import seon_cluster
from seon_inspect.tasks import mvp_graduation as tasks


EXPECTED_TASKS = {
    "A": "The expense records in front of you are unreadable. I want each one to show up as a single line with its dollar amount and its vendor, whenever you look at your world.",
    "B": "Which schema keys does the messaging toolkit's public surface mention?",
    "C": "Let the bookkeeping agent know what the expenses add up to.",
    "D": (
        "Work out what we spent per vendor and keep it somewhere you will still have it tomorrow.",
        "What did we spend at the vendor with the largest total?",
    ),
    "E1": "I need the exact number of primes below one trillion. Give me the number.",
    "E2": "Addition is the slow part of your expense totals. Make addition faster everywhere, then give me the total.",
    "F": "Take over the expense book: make it readable when you look at it, tell the bookkeeping agent the total, and tell me which schema keys the messaging toolkit mentions.",
}


def test_user_goals_are_exact_and_never_coach_the_mechanism():
    for scenario, expected in EXPECTED_TASKS.items():
        expected_phases = expected if isinstance(expected, tuple) else (expected,)
        assert tasks.TASKS[scenario] == expected_phases
    prompts = "\n".join(text for phases in tasks.TASKS.values() for text in phases)
    for forbidden in ("defn", ":malli/schema", "seon.render.walk",
                      "my.message/send", "namespace", "depth argument"):
        assert forbidden not in prompts
    assert "bookkeeping" in tasks.TASKS["C"][0]


def test_all_budgets_and_paired_arms_are_frozen():
    assert tasks.BUDGETS == {
        "A": (6,), "B": (4,), "B-control": (4,), "C": (5,),
        "D": (5, 4), "E1": (5,), "E2": (5,), "F": (10,),
    }
    assert tasks.SCENARIO_SAMPLES["B"] == ("B", "B-control")
    assert tasks.SCENARIO_SAMPLES["E"] == ("E1", "E2")


def test_runtime_live_gate_replays_every_offline_bad_rail():
    result = tasks.require_offline_discrimination()
    assert result["checks"] >= 40
    assert result["taxonomies"] >= 20


def test_nonce_and_fixture_are_replayable_but_sample_specific():
    first = tasks.build_seed_plan("C", 17, "sample-1", "cluster-a")
    replay = tasks.build_seed_plan("C", 17, "sample-1", "cluster-a")
    other = tasks.build_seed_plan("C", 17, "sample-2", "cluster-a")
    assert first.nonce == replay.nonce
    assert first.expenses == replay.expenses
    assert first.expectations == replay.expectations
    assert other.nonce != first.nonce
    assert other.expenses != first.expenses
    assert all(first.nonce in row["vendor"] for row in first.expenses)


def test_seed_uses_runtime_declaration_admission_and_returns_derivations():
    plan = tasks.build_seed_plan("F", 23, "sample-f", "cluster-f")
    assert "seon.sci.eval/evaluate" in plan.form
    assert "seon.cluster.loop/terminal-tx" in plan.form
    assert "seon.sci.eval/program-row" in plan.form
    assert ":db/ident" not in plan.form
    assert "datahike.schema" not in plan.form
    assert plan.mentioned_schema_key in plan.form
    assert ":derived-expectations" in plan.form
    assert "(sum ?amount)" in plan.form
    assert "vendor-totals" in plan.form
    assert ":pre-walk" in plan.form
    assert ":path path" in plan.form


def test_generated_wait_and_snapshot_forms_parse_and_scope_episode_evidence():
    plan = tasks.build_seed_plan("D", 23, "sample-d", "cluster-d")
    wait = tasks.wait_for_episode_form(plan, runs_before=2, budget=5, phase=1)
    snapshot = tasks.snapshot_form(
        "D", plan,
        phase_one={"commit_id": "00000000-0000-0000-0000-000000000001",
                   "phase1_function": {"sym": "my.agent/f"},
                   "function_symbols": ["my.agent/f"]},
        run_ids=["run-phase2"])
    for form in (wait, snapshot):
        parsed = subprocess.run(
            ["bb", "--config", str(seon_cluster.REPO_ROOT / "bb.edn"),
             "--deps-root", str(seon_cluster.REPO_ROOT), "-e",
             "(read-string (slurp *in*))"],
            input=form, text=True, capture_output=True,
            cwd=seon_cluster.REPO_ROOT,
        )
        assert parsed.returncode == 0, parsed.stderr
    assert "seon.cluster.work/next-agent-work" in wait
    assert ":run-ids run-ids" in wait
    assert 'selected-run-ids ["run-phase2"]' in snapshot
    assert "datahike.api/commit-as-db" in snapshot
    assert ":seon.error/proc ?proc" in snapshot


def test_e2_snapshot_uses_guarded_peer_probe_and_observed_error_value():
    plan = tasks.build_seed_plan("E2", 23, "sample-e2", "cluster-e2")
    form = tasks.snapshot_form("E2", plan, run_ids=["run-e2"])
    assert ':seon.cluster.agent/id peer-id' in form
    assert ':seon.cluster.run.form/source "(+ 17 25)"' in form
    assert ':result (:seon.sci.admit/value probe-evaluation)' in form
    assert ':error-value (when (and offending? (map? flat-value))' in form
    assert ':result (+ 17 25)' not in form


@pytest.mark.parametrize("scenario", tasks.TASKS)
def test_snapshot_form_names_the_scorer_projection(scenario):
    plan = tasks.build_seed_plan(scenario, 31, f"sample-{scenario}", "cluster")
    form = tasks.snapshot_form(scenario, plan, baseline={"pre_walk": []})
    assert ":sample-nonce" in form
    if scenario in {"A", "F"}:
        for field in (":fixture-expenses", ":selected-expense-id",
                      ":behavior-call", ":pre-walk", ":post-walk"):
            assert field in form
    if scenario in {"B", "B-control", "F"}:
        for field in (":toolkit-namespace", ":toolkit-functions",
                      ":reply-refused", ":walk-eval-count"):
            assert field in form
    if scenario in {"C", "F"}:
        assert ":peer" in form and ":messages" in form
    if scenario == "D":
        for field in (":before", ":after", ":phase1-function",
                      ":post-restart-functions", ":phase2-forms",
                      ":phase2-evals"):
            assert field in form
    if scenario == "E1":
        assert ":eval-receipts" in form and ":core-faults" in form
    if scenario == "E2":
        for field in (":base-row-before", ":base-row-after", ":base-probe",
                      ":published-overrides", ":eval-receipts"):
            assert field in form


@pytest.mark.parametrize("family,variants", [
    ("B", ["B", "B-control"]), ("E", ["E1", "E2"]),
])
def test_task_expands_required_paired_samples(family, variants):
    built = tasks.mvp_graduation(scenario=family, outcome="good")
    assert [sample.metadata["scenario"] for sample in built.dataset] == variants


@pytest.mark.parametrize("scenario", ("A", "B", "C", "D", "E1", "E2", "F"))
def test_frozen_solver_uses_the_real_scorer(scenario):
    family = "E" if scenario.startswith("E") else scenario
    built = tasks.mvp_graduation(scenario=family, outcome="good")
    if family == "E":
        built.dataset = type(built.dataset)(
            [sample for sample in built.dataset
             if sample.metadata["scenario"] == scenario])
    log = inspect_eval(built, model="mockllm/model", display="none",
                       log_level="warning")[0]
    assert log.status == "success", log.error
    score = next(item for item in log.results.scores
                 if item.name == "mvp_graduation_scorer")
    assert score.metrics["accuracy"].value == pytest.approx(1.0)


class FakeLease:
    def __init__(self, *, fail_release=False):
        self.name = "mvpeval-fake"
        self.fail_release = fail_release
        self.events = []

    def eval_form(self, form):
        if "select-keys effective" in form:
            self.events.append("provider")
            return {"seon.config.ai/endpoint": "http://127.0.0.1:11434/v1/chat/completions",
                    "seon.config.ai/model": "qwen3.5:35b-a3b-coding-nvfp4",
                    "seon.config.ai/no-auth": True}
        if "register-one!" in form:
            self.events.append("seed")
            return {"derived-expectations": {}, "baseline": {}}
        if "inbound-tx" in form:
            self.events.append("inbound")
            return {"runs-before": 0, "basis-t": 10}
        if "datahike.api/listen!" in form:
            self.events.append("wait")
            return {"status": "closed", "runs": 1, "closed": 1}
        self.events.append("snapshot")
        return {"sample_nonce": "fake"}

    def restart(self):
        self.events.append("restart")

    def release(self):
        self.events.append("release")
        if self.fail_release:
            raise RuntimeError("release failed")


def test_live_flow_orders_lease_seed_message_wait_snapshot_and_release(monkeypatch):
    lease = FakeLease()
    lease_arguments = []

    def lease_factory(**kwargs):
        lease_arguments.append(kwargs)
        return lease

    monkeypatch.setattr(tasks, "_assert_seed_expectations", lambda *args: None)
    result = tasks._drive_live_sample(
        "B", 1, "sample", "local", lease_factory)
    assert result["database_snapshot"] == {"sample_nonce": "fake"}
    assert lease_arguments == [{"prefix": "mvpeval",
                                "config_manifest": tasks.LOCAL_CONFIG_MANIFEST}]
    assert lease.events == ["provider", "seed", "inbound", "wait",
                            "snapshot", "release"]


def test_persistence_restarts_between_the_two_message_phases(monkeypatch):
    lease = FakeLease()
    monkeypatch.setattr(tasks, "_assert_seed_expectations", lambda *args: None)
    monkeypatch.setattr(tasks, "_persistence_checkpoint", lambda _: {})
    tasks._drive_live_sample("D", 1, "sample", "local", lambda **kwargs: lease)
    assert lease.events == ["provider", "seed", "inbound", "wait", "snapshot",
                            "restart", "inbound", "wait", "snapshot", "release"]


def test_cleanup_failure_is_added_without_masking_sample_failure():
    lease = FakeLease(fail_release=True)
    lease.eval_form = lambda _form: (_ for _ in ()).throw(ValueError("sample failed"))
    with pytest.raises(ValueError, match="sample failed") as raised:
        tasks._drive_live_sample("B", 1, "sample", "local", lambda **kwargs: lease)
    assert any("release also failed" in note for note in raised.value.__notes__)


def test_provider_mismatch_voids_and_releases():
    lease = FakeLease()
    lease.eval_form = lambda _form: {
        "seon.config.ai/endpoint": "https://api.deepseek.com/chat/completions",
        "seon.config.ai/model": "deepseek-chat",
    }
    with pytest.raises(tasks.VoidedSample, match="does not match"):
        tasks._drive_live_sample("B", 1, "sample", "local", lambda **kwargs: lease)
    assert lease.events == ["release"]


def test_deepseek_provider_requires_the_configured_nonempty_key(monkeypatch):
    provider = {
        "seon.config.ai/endpoint": "https://api.deepseek.com/chat/completions",
        "seon.config.ai/model": "deepseek-chat",
        "seon.config.ai/api-key-variable": "MVP_TEST_DEEPSEEK_KEY",
    }
    monkeypatch.delenv("MVP_TEST_DEEPSEEK_KEY", raising=False)
    assert not tasks._provider_matches("deepseek", provider)
    monkeypatch.setenv("MVP_TEST_DEEPSEEK_KEY", "present")
    assert tasks._provider_matches("deepseek", provider)
    provider["seon.config.ai/no-auth"] = True
    assert not tasks._provider_matches("deepseek", provider)


def test_expectation_mismatch_voids_before_scoring(monkeypatch):
    plan = tasks.build_seed_plan("C", 9, "sample", "cluster")
    snapshot = {
        "fixture_expenses": [
            {"amount_cents": row["amount_cents"] + (1 if index == 0 else 0)}
            for index, row in enumerate(plan.expenses)
        ]
    }
    seed = {"derived_expectations": copy.deepcopy(plan.expectations)}
    with pytest.raises(tasks.VoidedSample, match="scorer derivation disagrees"):
        tasks._assert_seed_expectations(plan, seed, snapshot)


@pytest.mark.parametrize("kwargs", [
    {"model": "deepseek"}, {"model": "paid"}, {"scenario": "A"}, {"n": 3},
])
def test_smoke_refuses_paid_or_non_b_n1_runs(kwargs):
    with pytest.raises(ValueError):
        tasks.smoke(**kwargs)


def test_smoke_entrypoint_defaults_to_local_b_n1(monkeypatch):
    calls = []
    monkeypatch.setattr(tasks, "smoke", lambda **kwargs: calls.append(kwargs))
    assert tasks.main([]) == 0
    assert calls == [{"scenario": "B", "n": 1, "model": "local",
                      "seed": 20260731}]
