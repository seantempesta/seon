"""The ledger — `evals/scorecard.jsonl` append + pass^k math + regression alarm.

One JSON line per (capability row, run) — the shared truth every context A/B
is decided against (eval-design "Metrics"). Rows are APPEND-ONLY: a re-run is
a NEW line (fresh run_id); history is never rewritten.

Row shape (eval-design's field set, plus provenance/attribution extensions):

    {"run_id": "2026-07-03:shell_use:dev:k3",   # deterministic: date:row:tier:k
     "row": "shell_use", "tier": "dev",
     "n": 8, "k": 3,
     "mean": 0.875,            # pass rate over NON-FLAKE executions == pass^1
     "pass_at_k": 1.0,         # >=1 passing epoch, per sample (can it ever)
     "pass_hat_k": 0.75,       # ALL k epochs pass, per sample (does it always)
     "flake_rate": 0.04,       # flake executions / total executions
     "flakes_by_class": {"solve_timeout": 1},
     "attribution": {"model_miss": 2},   # failing samples, classified
     "git_sha": "…", "datasets_lock_sha": "…",
     "model": "deepseek", "elapsed_s": 1234.5, "timestamp": "…Z"}

Flake discipline (eval-design): flakes are CLASSIFIED and EXCLUDED from
capability means — a timeout is not a wrong answer. `mean` is therefore
pass^1 by construction. pass_at_k / pass_hat_k are computed over samples
whose k epochs are ALL non-flake; samples with any flaked epoch are excluded
from those reducers (their flakes still count in flake_rate). With k=1 the
three reducers coincide.

Regression alarm (eval-design proposal, adopted): the LATEST dev-tier entry
for a row fails when its `mean` (pass^1) drops more than REGRESSION_DROP
below the median of that row's previous <=REGRESSION_WINDOW dev entries.
First entries can't fail (no history). Wired into pytest via
tests/test_scorecard_alarm.py — the standing alarm.
"""

from __future__ import annotations

import json
import statistics
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
SCORECARD_PATH = REPO_ROOT / "evals" / "scorecard.jsonl"

# eval-design: "pass^k drop > 0.10 on any row vs its 7-run median →
# investigate before merge".
REGRESSION_DROP = 0.10
REGRESSION_WINDOW = 7

# ---------------------------------------------------------------------------
# Model provenance — rows must self-describe (audit finding, 2026-07-04)
# ---------------------------------------------------------------------------
# The pod now RUNTIME-DERIVES the resolved model config: the /agents/run
# response carries `model_config`, COMPUTED at response time by the pod's
# pure config resolver (seon.ai/resolved-config — the agent's own override
# datoms → the global config row → shipped defaults; derive-don't-store,
# owner correction 2026-07-04). Runners capture it via solver._record_result
# ("pod_model_config" metadata) and pass `model_provenance_from_run(...)`
# into their row — caller-supplied values win over these fallback defaults
# in append_row. The constant below remains ONLY as the fallback for rows
# whose run predates the surface, and it says so.
MODEL_PROVENANCE: dict[str, Any] = {
    "model": "deepseek",
    "model_id": "deepseek-v4-pro",
    "model_thinking": "disabled",
    "model_temperature": 0.7,
    "model_config_source": (
        "pod defaults per src/seon/ai/openai_compat.cljs (default-model / "
        "default-temperature / thinking disabled-for-:deepseek) — NOT "
        "runtime-reported (no model_config captured for this run)"),
}

RUNTIME_SOURCE = "runtime-derived (pod resolver via /agents/run model_config)"


def model_provenance_from_run(model_config: dict[str, Any] | None,
                              ) -> dict[str, Any]:
    """Ledger-row provenance fields from a pod run's `model_config`.

    `model_config` is the /agents/run response field (or the recorded
    "pod_model_config" metadata): {"provider", "model", "temperature",
    "max_tokens", "thinking"} — derived by the pod's config resolver at
    response time. Returns the row-field mapping with `model_config_source` =
    RUNTIME_SOURCE, for the caller to merge into its `append_row` row
    (caller values win over the assumed-defaults constant). {} when the
    pod reported none — the fallback defaults then apply, honestly marked."""
    if not model_config:
        return {}
    out: dict[str, Any] = {"model_config_source": RUNTIME_SOURCE}
    if model_config.get("provider") is not None:
        out["model"] = model_config["provider"]
    if model_config.get("model") is not None:
        out["model_id"] = model_config["model"]
    if model_config.get("thinking") is not None:
        out["model_thinking"] = model_config["thinking"]
    if model_config.get("temperature") is not None:
        out["model_temperature"] = model_config["temperature"]
    return out

# An execution's outcome: "pass" | "fail" | a flake-taxonomy class string
# (anything else). Taxonomy classes used by the runners:
#   solve_timeout          pod reported timed_out (taxonomy: latency variance)
#   agent_run_refused      HTTP 422 from the door (wiring defect, never a score)
#   cluster_boot_timeout   ephemeral cluster never came ready
#   frozen_bundle_changed  the pinned bench bundle changed mid-run (the
#                          end-of-run identity assertion — cluster.
#                          FrozenBundleChanged); the run is contaminated
#   harness_error          any other harness-side exception (bin/seon, wire…)
PASS = "pass"
FAIL = "fail"


def execution(sample_id: str, epoch: int, outcome: str,
              **extra: Any) -> dict[str, Any]:
    """One execution record: (sample, epoch) → outcome (+ evidence extras)."""
    return {"sample_id": sample_id, "epoch": epoch, "outcome": outcome, **extra}


def compute_metrics(executions: list[dict[str, Any]]) -> dict[str, Any]:
    """The eval-design reducers over one row's execution records.

    Returns mean / pass_at_k / pass_hat_k / flake_rate / flakes_by_class /
    n / k, applying the flake-exclusion discipline documented above."""
    if not executions:
        raise ValueError("no executions — nothing to reduce")
    scored = [e for e in executions if e["outcome"] in (PASS, FAIL)]
    flakes = [e for e in executions if e["outcome"] not in (PASS, FAIL)]
    by_sample: dict[str, list[str]] = {}
    for e in executions:
        by_sample.setdefault(e["sample_id"], []).append(e["outcome"])
    k = max(len(v) for v in by_sample.values())
    clean = {s: outs for s, outs in by_sample.items()
             if len(outs) == k and all(o in (PASS, FAIL) for o in outs)}
    flakes_by_class: dict[str, int] = {}
    for e in flakes:
        flakes_by_class[e["outcome"]] = flakes_by_class.get(e["outcome"], 0) + 1
    return {
        "n": len(by_sample),
        "k": k,
        "mean": (sum(e["outcome"] == PASS for e in scored) / len(scored)
                 if scored else 0.0),
        "pass_at_k": (sum(any(o == PASS for o in outs)
                          for outs in clean.values()) / len(clean)
                      if clean else 0.0),
        "pass_hat_k": (sum(all(o == PASS for o in outs)
                           for outs in clean.values()) / len(clean)
                       if clean else 0.0),
        "flake_rate": len(flakes) / len(executions),
        "flakes_by_class": flakes_by_class,
    }


def executions_from_eval_log(log: Any) -> list[dict[str, Any]]:
    """Execution records from ONE inspect EvalLog (run_bench path).

    Previously each run hand-rolled this conversion in an ad-hoc script
    (evidence jsonls existed, the code didn't) — this is the one shared
    reducer. Per (sample, epoch): pod-reported timeout → `solve_timeout`;
    a crashed run (closed_reason ":error") → `run_error`; an inspect-side
    sample error → `harness_error`; otherwise pass/fail from the sample's
    first scorer value ("C"/1 = pass). Each record carries the reply text
    (completion) + the pod summary metadata as evidence."""
    out: list[dict[str, Any]] = []
    for s in (getattr(log, "samples", None) or []):
        md = s.metadata or {}
        pod = {k.removeprefix("pod_"): md.get(k) for k in
               ("pod_agent_id", "pod_turns", "pod_evals", "pod_closed_reason",
                "pod_timed_out", "pod_elapsed_ms", "pod_model_config")
               if k in md}
        base = {"pod": pod, "reply": getattr(s.output, "completion", "")}
        if md.get("pod_evidence_blobs") is not None:
            base["evidence_blobs"] = md["pod_evidence_blobs"]
        if getattr(s, "error", None) is not None:
            out.append(execution(str(s.id), s.epoch, "harness_error",
                                 error=str(s.error), **base))
            continue
        if md.get("pod_timed_out"):
            out.append(execution(str(s.id), s.epoch, "solve_timeout", **base))
            continue
        if str(md.get("pod_closed_reason") or "") == ":error":
            out.append(execution(str(s.id), s.epoch, "run_error", **base))
            continue
        score = next(iter((s.scores or {}).values()), None)
        val = getattr(score, "value", None)
        passed = val in ("C", 1, 1.0, True)
        out.append(execution(str(s.id), s.epoch, PASS if passed else FAIL,
                             score_value=val, **base))
    return out


def load_rows(path: Path = SCORECARD_PATH) -> list[dict[str, Any]]:
    """All ledger rows in file (append) order; [] when the ledger is absent."""
    if not path.is_file():
        return []
    return [json.loads(line) for line in path.read_text().splitlines() if line]


def append_row(row: dict[str, Any], path: Path = SCORECARD_PATH) -> None:
    """Append ONE row; refuses a duplicate run_id (append-only, no rewrites).

    Every appended row self-describes its model: any MODEL_PROVENANCE key the
    caller omits is filled from that constant (caller-supplied values win —
    e.g. a runner that DOES learn the runtime config passes the real values).
    Existing ledger lines are never touched."""
    row = {**MODEL_PROVENANCE, **row}
    required = {"run_id", "row", "tier", "n", "k", "mean", "pass_at_k",
                "pass_hat_k", "flake_rate", "git_sha", "datasets_lock_sha",
                "elapsed_s", "timestamp"} | MODEL_PROVENANCE.keys()
    missing = required - row.keys()
    if missing:
        raise ValueError(f"scorecard row missing fields: {sorted(missing)}")
    if any(r["run_id"] == row["run_id"] for r in load_rows(path)):
        raise ValueError(
            f"run_id {row['run_id']!r} already in the ledger — rows are "
            "append-only; a re-run gets a fresh run_id")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as f:
        f.write(json.dumps(row, sort_keys=True) + "\n")


def regression_failures(rows: list[dict[str, Any]],
                        drop: float = REGRESSION_DROP,
                        window: int = REGRESSION_WINDOW) -> list[str]:
    """The standing alarm: latest dev pass^1 vs the row's recent median.

    For each capability row with >=2 dev-tier entries, compare the LATEST
    entry's `mean` (pass^1) against the median of the previous <=`window`
    dev entries; a drop greater than `drop` is a failure string. [] = green.
    """
    dev = [r for r in rows if r.get("tier") == "dev"]
    by_row: dict[str, list[dict[str, Any]]] = {}
    for r in dev:
        by_row.setdefault(r["row"], []).append(r)
    failures = []
    for name, entries in sorted(by_row.items()):
        if len(entries) < 2:
            continue
        latest = entries[-1]
        history = [e["mean"] for e in entries[:-1][-window:]]
        baseline = statistics.median(history)
        if baseline - latest["mean"] > drop:
            failures.append(
                f"{name}: latest dev pass^1 {latest['mean']:.3f} "
                f"(run {latest['run_id']}) dropped {baseline - latest['mean']:.3f} "
                f"below the {len(history)}-run median {baseline:.3f} "
                f"(threshold {drop}) — investigate before merge")
    return failures
