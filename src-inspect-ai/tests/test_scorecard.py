"""scorecard.py — reducer math, ledger append discipline, regression check."""

import json

import pytest

from seon_inspect import scorecard
from seon_inspect.scorecard import (FAIL, PASS, append_row, compute_metrics,
                                    execution, load_rows, regression_failures)


def _row(run_id, row, mean, tier="dev"):
    return {"run_id": run_id, "row": row, "tier": tier, "n": 8, "k": 1,
            "mean": mean, "pass_at_k": mean, "pass_hat_k": mean,
            "flake_rate": 0.0, "flakes_by_class": {}, "attribution": {},
            "git_sha": "abc", "datasets_lock_sha": "def", "model": "m",
            "elapsed_s": 1.0, "timestamp": "2026-07-03T00:00:00Z"}


# ---------------------------------------------------------------------------
# compute_metrics
# ---------------------------------------------------------------------------


def test_metrics_k1_all_reducers_coincide():
    ex = [execution("a", 1, PASS), execution("b", 1, FAIL),
          execution("c", 1, PASS), execution("d", 1, PASS)]
    m = compute_metrics(ex)
    assert m["n"] == 4 and m["k"] == 1
    assert m["mean"] == m["pass_at_k"] == m["pass_hat_k"] == 0.75
    assert m["flake_rate"] == 0.0


def test_metrics_pass_hat_k_vs_pass_at_k_gap():
    # sample a: 3/3 pass; sample b: 1/3 pass — the stability gap.
    ex = ([execution("a", e, PASS) for e in (1, 2, 3)]
          + [execution("b", 1, PASS), execution("b", 2, FAIL),
             execution("b", 3, FAIL)])
    m = compute_metrics(ex)
    assert m["k"] == 3
    assert m["pass_at_k"] == 1.0          # both can
    assert m["pass_hat_k"] == 0.5         # only a always does
    assert m["mean"] == pytest.approx(4 / 6)


def test_metrics_flakes_excluded_from_means():
    # The calibration lesson: one timeout must not drag the mean.
    ex = [execution("a", 1, PASS), execution("b", 1, PASS),
          execution("c", 1, "solve_timeout")]
    m = compute_metrics(ex)
    assert m["mean"] == 1.0
    assert m["flake_rate"] == pytest.approx(1 / 3)
    assert m["flakes_by_class"] == {"solve_timeout": 1}


def test_metrics_sample_with_flaked_epoch_excluded_from_pass_hat_k():
    ex = ([execution("a", e, PASS) for e in (1, 2)]
          + [execution("b", 1, PASS), execution("b", 2, "solve_timeout")])
    m = compute_metrics(ex)
    assert m["pass_hat_k"] == 1.0  # only sample a is clean over k=2
    assert m["flakes_by_class"] == {"solve_timeout": 1}


def test_metrics_empty_raises():
    with pytest.raises(ValueError):
        compute_metrics([])


# ---------------------------------------------------------------------------
# ledger append discipline
# ---------------------------------------------------------------------------


def test_append_and_load_roundtrip(tmp_path):
    p = tmp_path / "scorecard.jsonl"
    append_row(_row("r1", "gsm8k", 0.9), path=p)
    append_row(_row("r2", "gsm8k", 0.8), path=p)
    rows = load_rows(p)
    assert [r["run_id"] for r in rows] == ["r1", "r2"]


def test_append_refuses_duplicate_run_id(tmp_path):
    p = tmp_path / "scorecard.jsonl"
    append_row(_row("r1", "gsm8k", 0.9), path=p)
    with pytest.raises(ValueError, match="append-only"):
        append_row(_row("r1", "gsm8k", 0.9), path=p)


def test_append_refuses_missing_fields(tmp_path):
    r = _row("r1", "gsm8k", 0.9)
    del r["datasets_lock_sha"]
    with pytest.raises(ValueError, match="missing"):
        append_row(r, path=tmp_path / "scorecard.jsonl")


def test_load_missing_ledger_is_empty(tmp_path):
    assert load_rows(tmp_path / "nope.jsonl") == []


# ---------------------------------------------------------------------------
# model provenance — rows self-describe (2026-07-04)
# ---------------------------------------------------------------------------


def test_append_fills_model_provenance(tmp_path):
    # A runner that doesn't know the runtime config still writes a
    # self-describing row: provider + doc-referenced defaults, with the
    # source field saying they are NOT runtime-reported.
    p = tmp_path / "scorecard.jsonl"
    r = _row("r1", "gsm8k", 0.9)
    del r["model"]
    append_row(r, path=p)
    got = load_rows(p)[0]
    assert got["model"] == "deepseek"
    assert got["model_id"] == "deepseek-v4-pro"
    assert got["model_thinking"] == "disabled"
    assert got["model_temperature"] == 0.7
    assert "NOT" in got["model_config_source"]


def test_append_caller_provenance_wins(tmp_path):
    # A runner that DOES learn the runtime config records the real values.
    p = tmp_path / "scorecard.jsonl"
    r = _row("r1", "gsm8k", 0.9)
    r.update({"model": "deepseek", "model_id": "deepseek-v4-flash",
              "model_thinking": "high", "model_temperature": 1.0,
              "model_config_source": "pod /agents/run response"})
    append_row(r, path=p)
    got = load_rows(p)[0]
    assert got["model_id"] == "deepseek-v4-flash"
    assert got["model_thinking"] == "high"
    assert got["model_temperature"] == 1.0
    assert got["model_config_source"] == "pod /agents/run response"


def test_model_provenance_from_run_maps_pod_fields(tmp_path):
    # The pod's runtime-reported model_config → ledger-row fields, and an
    # append with them carries the runtime-reported source (the override
    # chain: caller values win over the assumed-defaults constant).
    from seon_inspect.scorecard import (RUNTIME_SOURCE,
                                        model_provenance_from_run)
    cfg = {"provider": "deepseek", "model": "deepseek-v4-pro",
           "temperature": 0.7, "max_tokens": 4096, "thinking": "false"}
    prov = model_provenance_from_run(cfg)
    assert prov == {"model": "deepseek", "model_id": "deepseek-v4-pro",
                    "model_thinking": "false", "model_temperature": 0.7,
                    "model_config_source": RUNTIME_SOURCE}
    p = tmp_path / "scorecard.jsonl"
    append_row({**_row("r1", "gsm8k", 0.9), **prov}, path=p)
    got = load_rows(p)[0]
    assert got["model_config_source"] == RUNTIME_SOURCE
    assert got["model_thinking"] == "false"
    # no pod-reported config → {} → the honest fallback defaults apply
    assert model_provenance_from_run(None) == {}
    assert model_provenance_from_run({}) == {}


def test_append_does_not_touch_existing_lines(tmp_path):
    # Append-only: enrichment applies to NEW rows; prior lines stay
    # byte-identical (the first-dev-pass rows are never rewritten).
    p = tmp_path / "scorecard.jsonl"
    legacy = json.dumps({**_row("legacy", "gsm8k", 0.73)}, sort_keys=True)
    p.write_text(legacy + "\n")
    append_row(_row("r2", "gsm8k", 0.9), path=p)
    lines = p.read_text().splitlines()
    assert lines[0] == legacy
    assert "model_id" not in json.loads(lines[0])
    assert json.loads(lines[1])["model_id"] == "deepseek-v4-pro"


# ---------------------------------------------------------------------------
# regression alarm logic
# ---------------------------------------------------------------------------


def test_regression_first_entry_cannot_fail():
    assert regression_failures([_row("r1", "gsm8k", 0.2)]) == []


def test_regression_detects_drop_beyond_threshold():
    rows = [_row("r1", "gsm8k", 0.9), _row("r2", "gsm8k", 0.9),
            _row("r3", "gsm8k", 0.75)]
    fails = regression_failures(rows)
    assert len(fails) == 1 and "gsm8k" in fails[0]


def test_regression_tolerates_drop_within_threshold():
    rows = [_row("r1", "gsm8k", 0.9), _row("r2", "gsm8k", 0.82)]
    assert regression_failures(rows) == []


def test_regression_uses_median_of_window_not_best():
    # best previous = 1.0 but median of the window = 0.6 → 0.55 is fine.
    rows = ([_row(f"r{i}", "shell_use", m)
             for i, m in enumerate([1.0, 0.6, 0.6, 0.6, 0.6, 0.6, 0.6])]
            + [_row("latest", "shell_use", 0.55)])
    assert regression_failures(rows) == []


def test_regression_ignores_non_dev_tiers():
    rows = [_row("r1", "gsm8k", 0.9, tier="milestone"),
            _row("r2", "gsm8k", 0.2)]
    assert regression_failures(rows) == []


def test_ledger_rows_parse_as_json_lines(tmp_path):
    p = tmp_path / "scorecard.jsonl"
    append_row(_row("r1", "gsm8k", 0.9), path=p)
    line = p.read_text().splitlines()[0]
    assert json.loads(line)["row"] == "gsm8k"


# ---------------------------------------------------------------------------
# Regression alarm keys on (row, ARM) — armD-thinking extension (2026-07-04)
# ---------------------------------------------------------------------------


def _dev_row(row, mean, run_id, arm=None):
    r = {"row": row, "tier": "dev", "mean": mean, "run_id": run_id}
    if arm is not None:
        r["attribution"] = {"arm": arm}
    return r


def test_alarm_thinking_arm_does_not_trip_nonthink_history():
    # a new arm's FIRST entry is its own group — it must neither trip
    # against the baseline median nor become the baseline's "latest".
    rows = [_dev_row("gpqa", 0.7, "r1"), _dev_row("gpqa", 0.72, "r2"),
            _dev_row("gpqa", 0.4, "r3", arm="armD-thinking")]
    assert scorecard.regression_failures(rows) == []


def test_alarm_still_fires_within_baseline_after_arm_rows():
    rows = [_dev_row("gpqa", 0.7, "r1"),
            _dev_row("gpqa", 0.3, "r2"),  # baseline regression
            _dev_row("gpqa", 0.9, "r3", arm="armD-thinking")]
    failures = scorecard.regression_failures(rows)
    assert len(failures) == 1 and "gpqa" in failures[0]


def test_alarm_fires_within_an_arm():
    rows = [_dev_row("gpqa", 0.9, "r1", arm="armD-thinking"),
            _dev_row("gpqa", 0.5, "r2", arm="armD-thinking")]
    failures = scorecard.regression_failures(rows)
    assert len(failures) == 1 and "gpqa[armD-thinking]" in failures[0]
