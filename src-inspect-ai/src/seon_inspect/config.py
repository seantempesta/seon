"""Harness run parameters — the ONE config surface for /solve benchmarking.

Defaults here are CALIBRATION-DERIVED (see docs/prds/agent-ctx/research/
calibration-run-2026-07-02.md) and overridable PER-RUN via function arguments
(`run_bench(solve_timeout_s=…, max_samples=…)`, `seon_pod_solver(timeout_s=…)`,
or per-sample `metadata["timeout_ms"]`). Precedence: per-sample metadata >
per-run argument > these constants. The ONLY env var is `SEON_SOLVE_URL` — the
pod-INSTANCE selector (which pod), never behavior config; it supplies the
default endpoint when no `solve_url` argument is passed, read at CALL time
(never import time — a prior import-time read made `run_bench(solve_url=…)`
a no-op).
"""

from __future__ import annotations

import os

# Fallback pod endpoint when neither a solve_url argument nor SEON_SOLVE_URL
# is given (the default cluster). Benches are pod-agnostic: any pod that
# mounts POST /solve works.
DEFAULT_SOLVE_URL = "http://127.0.0.1:7890/solve"

# Per-sample wall-clock budget the pod may run its own multi-turn loop.
# Calibration 2026-07-02 (gsm8k via acme /solve, DeepSeek, n=14 completed):
# median 40.7s, p90 ~70s, max 74s; a 6-turn multi-turn smoke ran 97s. QA-class
# rows should pass solve_timeout_s=QA_SOLVE_TIMEOUT_S (>3× p90 AND >3× max) —
# nothing consumes it by default yet; QA rows opt in per-run.
# The GENERAL default stays 300s because the surveyed agentic rows
# (memory/planning) ranged 51→300s (flake taxonomy #1) and have no calibration
# pass yet — re-derive per row as their generators land.
DEFAULT_SOLVE_TIMEOUT_S = 300
QA_SOLVE_TIMEOUT_S = 240

# Host-side HTTP read budget = pod budget + margin (the POD owns the timeout
# and answers honestly at timeout_ms; the margin only covers response
# serialization + transit).
HTTP_MARGIN_S = 30

# Per-POD sample concurrency for /solve. Calibration 2026-07-02: the ceiling
# is 1 — solve-once! swaps the single root `seon.db/*conn*` to a per-sample
# scratch conn and restores it in `finally`, so two in-flight samples clobber
# each other's world. Observed live at effective concurrency 2 (~13 sample-
# executions): 2 hard collisions (datahike cas `:entity-id/missing` write-
# error → `halt superseded` → full timeout burn, turns=0) + 1 post-reply
# turn-close error — ~15% hard-fail, latency bimodal, pod-side errors ⇒ NOT
# graceful. Parallel scoring = MORE pods (one URL each), never more samples
# per pod.
POD_MAX_SAMPLES = 1


def solve_url(override: str | None = None) -> str:
    """Resolve the pod endpoint: argument > SEON_SOLVE_URL > default, at call time."""
    return override or os.environ.get("SEON_SOLVE_URL") or DEFAULT_SOLVE_URL


def total_run_bound_s(n_samples: int, epochs: int = 1,
                      timeout_s: int = DEFAULT_SOLVE_TIMEOUT_S) -> int:
    """Worst-case wall-clock bound for one serial (per-pod) row: every sample
    burns its full per-sample budget plus HTTP margin. Use as the outer watch
    bound when scripting runs; a healthy row finishes far under it."""
    return n_samples * epochs * (timeout_s + HTTP_MARGIN_S)
