"""A/B driver: bfcl_ast dev split through the FORM-surface adapter.

The surface A/B (2026-07-05): the reworked `bfcl_adapter` asks the agent for
its NATIVE Clojure form (a call `(fn {:kw v})`) instead of a JSON array — the
SAME frozen 10 dev samples, k=1, frozen ephemeral clusters — to measure whether
aligning the answer surface with the agent's form-oriented context changes the
score vs the committed JSON row (`2026-07-04:bfcl_ast:dev:k1:armD-full`, .700).

Not maintained harness code — a run recipe. Writes evidence + appends the
scorecard row `2026-07-05:bfcl_ast:dev:k1:form-surface`.
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from seon_inspect import freeze
from seon_inspect.bfcl_adapter import render_bfcl_prompt
from seon_inspect.catalog import load_bench_task
from seon_inspect.scorecard import (
    append_row,
    compute_metrics,
    executions_from_eval_log,
    model_provenance_from_run,
)

REPO = Path(__file__).resolve().parents[3]
RUN_DIR = REPO / "evals" / "runs" / "2026-07-05-bfcl-ast-dev-form"
ARM = "form-surface"


def _sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main() -> None:
    RUN_DIR.mkdir(parents=True, exist_ok=True)

    # Render ALL 10 dev prompts (the SAME render_bfcl_prompt the live chain
    # applies) as the native-form-check evidence.
    dev = freeze.load_split("bfcl_ast", "dev")
    task = load_bench_task("bfcl_ast")
    by_id = {str(s.id): s for s in task.dataset}
    prompts = {}
    for sid in dev.sample_ids:
        s = by_id[sid]
        q = s.input[-1].text if not isinstance(s.input, str) else s.input
        prompts[sid] = render_bfcl_prompt(q, s.metadata["tools"],
                                          s.metadata["scorer"])
    (RUN_DIR / "sample-prompts.txt").write_text(
        "\n\n" + ("=" * 78 + "\n").join(
            f"SAMPLE {sid}  (category={by_id[sid].metadata['category_name']})\n\n"
            f"{p}\n" for sid, p in prompts.items()))

    t0 = time.time()
    logs = freeze.run_split(
        "bfcl_ast", "dev",
        per_sample_cluster=True, cluster_parallelism=2,
        run_timeout_s=180, epochs=1,
        evidence_dir=RUN_DIR,
    )
    elapsed = time.time() - t0
    log = logs[0] if isinstance(logs, list) else logs

    executions = executions_from_eval_log(log)
    parse_miss_ids = []
    for s in (getattr(log, "samples", None) or []):
        if (s.metadata or {}).get("bfcl_parse_error"):
            parse_miss_ids.append(str(s.id))
    (RUN_DIR / "executions.jsonl").write_text(
        "\n".join(json.dumps(e) for e in executions) + "\n")

    metrics = compute_metrics(executions)
    mc = next((e["pod"].get("model_config") for e in executions
               if e.get("pod", {}).get("model_config")), None)
    prov = model_provenance_from_run(mc)

    n_model_miss = sum(1 for e in executions
                       if e["outcome"] == "fail"
                       and str(e["sample_id"]) not in parse_miss_ids)
    row = {
        "run_id": "2026-07-05:bfcl_ast:dev:k1:form-surface",
        "row": "tool_calling", "tier": "dev",
        **metrics,
        "attribution": {
            "arm": ARM,
            "bench": "bfcl (Berkeley Function-Calling Leaderboard), single-turn "
                     "AST subset (simple_python/multiple/parallel/"
                     "parallel_multiple), scorer=inspect_evals.bfcl ast_match",
            "adapter": "form->tool_call bridge (seon_inspect.bfcl_adapter): "
                       "pod emits a Clojure call form (its native surface), "
                       "read into synthesized ToolCalls the bench's own "
                       "ast_match harvests",
            "ab_against": "2026-07-04:bfcl_ast:dev:k1:armD-full (JSON surface, "
                          "mean .700) — SAME frozen 10 dev samples",
            "leaderboard_band": "report-only vs the PUBLIC BFCL leaderboard "
                                "(no DeepSeek non-think anchor exists for any "
                                "door-fitting agentic bench); NOT a fabricated "
                                "anchor column",
            "parse_miss": parse_miss_ids,
            "model_miss": n_model_miss,
        },
        "git_sha": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=REPO).decode().strip(),
        "datasets_lock_sha": _sha256(REPO / "evals" / "datasets.lock"),
        "elapsed_s": round(elapsed, 1),
        "timestamp": datetime.now(timezone.utc).isoformat(),
        **prov,
    }
    (RUN_DIR / "row.json").write_text(json.dumps(row, indent=2) + "\n")

    print("=== bfcl_ast dev metrics (FORM surface) ===")
    print(json.dumps(metrics, indent=2))
    print("parse_miss:", parse_miss_ids, "| model_miss:", n_model_miss)
    print("=== per-execution ===")
    for e in executions:
        print(f"  {e['sample_id']:<24} {e['outcome']:<14} "
              f"reply={e.get('reply','')[:80]!r}")
    append_row(row)
    print("\nappended scorecard row:", row["run_id"])


if __name__ == "__main__":
    main()
