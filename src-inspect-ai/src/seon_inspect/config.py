"""Harness run parameters — the ONE config surface for cluster benchmarking.

Defaults here are CALIBRATION-DERIVED (see docs/prds/agent-ctx/research/
calibration-run-2026-07-02.md) and overridable PER-RUN via function arguments
(`run_bench(run_timeout_s=…, max_samples=…)`, `seon_pod_solver(timeout_s=…)`,
or per-sample `metadata["timeout_ms"]`). Precedence: per-sample metadata >
per-run argument > these constants. The ONLY env var is `SEON_CLUSTER_URL` —
the cluster-INSTANCE selector (which cluster's pod door), never behavior
config; it supplies the default endpoint when no `cluster_url` argument is
passed, read at CALL time (never import time — a prior import-time read made
`run_bench(cluster_url=…)` a no-op).
"""

from __future__ import annotations

import os

# Fallback pod door when neither a cluster_url argument nor SEON_CLUSTER_URL
# is given (the default cluster). Benches are cluster-agnostic: any pod that
# mounts POST /agents/run works.
DEFAULT_CLUSTER_URL = "http://127.0.0.1:7890/agents/run"

# Per-sample wall-clock budget the pod may run its own multi-turn loop.
# Calibration 2026-07-02 (gsm8k via acme, DeepSeek, n=14 completed):
# median 40.7s, p90 ~70s, max 74s; a 6-turn multi-turn smoke ran 97s. QA-class
# rows should pass run_timeout_s=QA_RUN_TIMEOUT_S (>3× p90 AND >3× max) —
# nothing consumes it by default yet; QA rows opt in per-run.
# The GENERAL default stays 300s because the surveyed agentic rows
# (memory/planning) ranged 51→300s (flake taxonomy #1) and have no calibration
# pass yet — re-derive per row as their generators land.
DEFAULT_RUN_TIMEOUT_S = 300
QA_RUN_TIMEOUT_S = 240

# Host-side HTTP read budget = pod budget + margin (the POD owns the timeout
# and answers honestly at timeout_ms; the margin only covers response
# serialization + transit).
HTTP_MARGIN_S = 30

# Per-sample ephemeral-cluster boot budget: `bin/seon cluster create` measured
# create→ready 9.3s warm / 23.5s cold (cluster build, 2026-07-03). 60s covers
# a cold boot with margin; the supervisor's own ready bound (120s) is the hard
# backstop. Only the per-sample-cluster mode pays this.
CLUSTER_BOOT_BUDGET_S = 60

# Per-POD sample concurrency. One pod per cluster, one cluster = one sample's
# isolation unit — the ceiling is 1 BY CONSTRUCTION (owner-locked; see the
# agent-ctx CLAUDE.md "Parallelism/swarms"). Calibration 2026-07-02 measured
# the old shared-pod door failing at effective concurrency 2 (~15% hard-fail);
# parallel scoring = MORE clusters (bench-cluster-N, one URL each), never more
# samples per pod.
POD_MAX_SAMPLES = 1


def cluster_url(override: str | None = None) -> str:
    """Resolve the pod door: argument > SEON_CLUSTER_URL > default, at call time."""
    return override or os.environ.get("SEON_CLUSTER_URL") or DEFAULT_CLUSTER_URL


def total_run_bound_s(n_samples: int, epochs: int = 1,
                      timeout_s: int = DEFAULT_RUN_TIMEOUT_S,
                      per_sample_cluster: bool = False) -> int:
    """Worst-case wall-clock bound for one serial (per-pod) row: every sample
    burns its full per-sample budget plus HTTP margin — plus, in per-sample-
    cluster mode, one cluster boot per sample. Use as the outer watch bound
    when scripting runs; a healthy row finishes far under it."""
    boot = CLUSTER_BOOT_BUDGET_S if per_sample_cluster else 0
    return n_samples * epochs * (timeout_s + HTTP_MARGIN_S + boot)
