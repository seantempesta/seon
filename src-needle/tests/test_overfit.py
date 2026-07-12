"""Overfit smoke as a test: gradients flow, loss drops, pairs memorize.

Short run (60 steps, 4 pairs) to keep the suite quick; the full 10-pair
report is `python -m seon_needle.overfit`.
"""

import pytest

from seon_needle import config as cfg

pytestmark = pytest.mark.skipif(
    not cfg.weights_path().exists(),
    reason="needs converted checkpoint (python -m seon_needle.convert)")


def test_overfit_smoke():
    from seon_needle.overfit import SYNTHETIC_PAIRS, run

    pairs = SYNTHETIC_PAIRS[:4]
    r = run(steps=60, pairs=pairs, verbose=False)
    assert r["losses"][-1] < r["losses"][0] * 0.05, "loss did not collapse"
    assert r["exact"] >= 3, f"memorized only {r['exact']}/{r['total']}"
