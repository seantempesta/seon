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
