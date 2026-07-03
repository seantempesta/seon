"""THE STANDING ALARM — the real ledger must not regress.

Reads `evals/scorecard.jsonl` (the committed shared truth) and fails the
suite when any capability row's LATEST dev-tier pass^1 (`mean`) drops more
than `scorecard.REGRESSION_DROP` below the median of that row's previous
<=REGRESSION_WINDOW dev entries (eval-design: "pass^k drop > 0.10 on any row
vs its 7-run median → investigate before merge"). First entries can't fail;
an absent ledger is green (pre-first-pass checkouts)."""

from seon_inspect.scorecard import SCORECARD_PATH, load_rows, regression_failures


def test_no_dev_pass_regression():
    rows = load_rows(SCORECARD_PATH)
    failures = regression_failures(rows)
    assert not failures, (
        "pass^k REGRESSION on the dev ledger (evals/scorecard.jsonl):\n  "
        + "\n  ".join(failures))


def test_ledger_rows_are_well_formed():
    """Every committed ledger row carries the required field set."""
    required = {"run_id", "row", "tier", "n", "k", "mean", "pass_at_k",
                "pass_hat_k", "flake_rate", "git_sha", "datasets_lock_sha",
                "elapsed_s", "timestamp"}
    for r in load_rows(SCORECARD_PATH):
        missing = required - r.keys()
        assert not missing, f"ledger row {r.get('run_id')!r} missing {missing}"
