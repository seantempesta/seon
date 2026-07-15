---
type: research
status: active
tags: [agent, research]
---

# Qwen2.5 Coder 0.5B database diagnostic

## Decision

The first sub-1B database workflow is useful diagnostic evidence, not an
accepted P0 result. Preserve it as the pre-context-refinement baseline, then
repeat the exact sample only after ACME has a coherent admitted artifact. Do
not run the ten-member development slice or tune a broad model matrix from
this result.

## Dependency and identity ledger

- Inspect AI: `0.3.247.dev0+g05322696a.d20260715`, from the pinned source
  boundary in [[inspect-reproducibility-boundary-2026-07-15]].
- Task: native `milestone_lift`, database workflow seed 1 position 0,
  `database_workflow-seed1-000`.
- Static target: `http://127.0.0.1:7994/agents/run`, ACME database
  `6813d1c2-4feb-3272-9b74-4c6769142514`, branch `db`.
- Model: `mlx-community/Qwen2.5-Coder-0.5B-Instruct-4bit`, served by
  `mlx_lm.server` on port 18081; temperature 0.2, maximum output 1,024 tokens,
  thinking disabled.
- Seon revision reported by Inspect: `f05b4911`, dirty. That dirty identity and
  direct task invocation are why this is not an admitted reproducible result.
- Native artifact:
  `evals/runs/2026-07-15-p0-db-qwen25coder05b/inspect-logs/2026-07-15T04-13-32-00-00_milestone-lift_ZvM596Xgc2X84PQCKV9cXk.eval`.

## Observed result

Inspect completed successfully and the sample itself had no infrastructure
error. Agent `shy-ways-pull` ran for 56,014 ms, produced three replies and
three evaluation records, then closed `:no-forms`. The unchanged milestone
scorer returned incorrect with all four checks absent: schema registration,
transaction, later query, and final answer. Fabrication checks remained zero.

The prompt grew from 21,947 to 22,487 to 23,201 estimated tokens. Replies were
545, 719, and 719 tokens. The first reply repeated the dynamic runtime status
line as comments. The later replies echoed large canvas, plan, and runtime
fragments inside fenced Clojure text. None contained an executable form.

The forgiving reader behaved correctly at its boundary: each prose/fenced
reply became an `ok` evaluation record with empty source and retained
narration. It emitted no stack trace and did not crash the pod. The run then
ended through the configured no-forms policy rather than an exception.

## Interpretation

This sample falsifies the idea that merely exposing complete contracts makes
the current context usable by the smallest installed coder. It does not yet
separate three causes:

- the roughly 22k-token initial prompt and schema-heavy namespace block;
- repetition or ghost narration re-entering later prompt context; and
- the model's intrinsic ability to follow Seon's executable-form contract.

The increasing prompt and repeated runtime line make transcript provenance an
immediate correctness question, not a cosmetic model failure. Diagnose the
exact bytes and database refs that admitted the repeated narration before
changing context prose. The open finding is tracked in
[[../../../seon/issues/narration-ghost-echo-not-neutralized]].

## Exact next experiment

1. Let the concurrent context/transcript owner finish or commit; do not stage
   its dirty files from this lane.
2. Rebuild/restart only the isolated ACME cluster and require a ready,
   ownership-coherent status.
3. Invoke this same native sample through run-level source admission and
   mandatory log finalization. Reject a dirty Seon source identity.
4. Inspect the resulting prompt/reply/eval bytes before running another
   sample. If repetition remains, localize its database-derived owner first.
5. After one admitted baseline, compare the exact sample across the measured
   shared-schema projection and the next model size. Record outcome and bytes;
   token reduction alone is not success.
