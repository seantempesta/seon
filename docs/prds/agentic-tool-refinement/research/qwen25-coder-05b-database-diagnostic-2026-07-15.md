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
the current context usable by the smallest installed coder. A byte-level trace
now separates the repetition mechanism:

- turn zero's prompt contained one legitimate live readline;
- Qwen's raw 2,275-byte reply contained eighteen byte-identical copies plus
  one truncated copy, before any parser or transcript stage ran;
- the forgiving parser preserved those comment lines once as narration and
  recorded one blank-source successful evaluation;
- the transcript faithfully reinserted that stored narration and added one
  newly derived live readline; and
- Qwen copied ten more old lines on each later turn, so turn two contained
  twenty-eight complete historical copies plus fragments.

The renderer did not manufacture the initial eighteen copies. The failure is
a feedback loop between model suffix imitation, correct comment recovery, and
durable narration reinsertion. The exact prompt/reply byte counts are
88,650/2,275, 90,897/2,959, and 93,836/2,957. This still does not isolate how
much the large schema-heavy prompt lowers the model's ability to escape the
attractor. The open finding is tracked in
[[../../../seon/issues/narration-ghost-echo-not-neutralized]].

## Exact next experiment

1. Let the concurrent context/transcript owner finish or commit; do not stage
   its dirty files from this lane.
2. Rebuild/restart only the isolated ACME cluster and require a ready,
   ownership-coherent status.
3. Invoke this same native sample through run-level source admission and
   mandatory log finalization. Reject a dirty Seon source identity.
4. Inspect the resulting prompt/reply/eval bytes before running another
   sample. Do not call a manifest-only `::readline?` toggle byte-controlled:
   context blocks are copied when an agent is born, separate agents/clusters
   change other database facts, and applying another manifest does not rewrite
   an existing agent. The `:autocomplete` profile is also not a valid arm
   because it changes several blocks, caps, and cache-boundary assembly.
5. After one admitted baseline, compare the exact sample across the measured
   shared-schema projection and the next model size. Record outcome and bytes;
   token reduction alone is not success.

## Paired readline experiment prerequisite

The setting is the colocated boolean
`:seon.agent.ctx.transcript/readline?`, consumed from a render-node patch, then
the stored transcript block, then its true default. False removes the complete
live transcript tail: status, namespace cursor, pressure steering when present,
root telemetry when applicable, and its clock. It is not a clock-only switch.

First prove the mechanical delta by rendering one immutable database value
twice through the one transcript renderer and asserting that the exact byte
difference is the returned tail. A scored pair waits for the operator's
ownership-fenced database fork/lease: two admitted serial native runs must
share frozen sample membership, source/artifact/model/sampling identity, and
starting database facts. Their pulled context trees differ only by the false
attribute, and their retained turn-zero prompt hashes and raw diff prove the
intervention. If one pair separates, replicate through Inspect epochs; do not
create another runner or normalize model output.
