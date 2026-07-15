---
type: prd
status: planned
tags: [prd, agent, research]
---

# Inspect and autocomplete evidence roadmap

> Detailed source evidence and the current reconciliation/acceptance matrix
> live in [[research/inspect-autocomplete-source-evidence-audit-2026-07-14]]
> and [[research/agentic-inspect-autocomplete-reconciliation-2026-07-14]].

## Outcome

Inspect produces reproducible evidence for ordinary system work, long-term
planning, autocomplete/data quality, and large-planner/small-executor tool use;
reviewed ACME refinements improve the canonical `my.*` surface rather than
creating a benchmark-only context or runtime.

## Current state

The synchronized Inspect environment currently passes **321 tests with eight
expected skips**, and current source contains standard `inspect_evals` task
adapters, BFCL and SWE-bench arms, deterministic oracles, frozen dataset/image
metadata, append-only scorecards, planning trajectory scorers, a historical
typeahead replay corpus, and a real as-of ClojureScript autocomplete exporter.

The reviewed ACME lane is handed back and reconciled. Current source retains
exact database/turn evidence in native Inspect metadata, aligns BFCL with one
ordinary `complete` form while preserving its upstream scorer, and derives
agent-callable tools from positive `:seon.fn/agent-facing?` program facts.
The dedicated worktree has no missing tracked source or run artifact; its
ignored databases remain a preservation/retirement concern.

These mechanisms are not yet one reproducible evidence path:

- source/run admission is implemented: one reviewed lock selects root Gitlink
  revisions `05322696...` and `97c99f5...`, synchronized installed Inspect and
  Inspect Evals, exact Python OpenAI `2.45.0`, admitted Python/task/scorer paths,
  Python/dataset locks, and committed Seon harness source. A mismatch rejects
  task construction or a prebuilt run before model/pod work;
- per-sample create/restart/release correctly fail because the operator has no
  ownership-fenced lease; static URL mode is not isolation;
- static runs retain exact turn bytes and complete database coordinates, and
  every accepted catalog run now requires a readable native `.eval` carrying
  the complete admitted source map; evidence-directory copies are digest-
  verified. Scorecard summaries still need a stable correlation to that native
  authority, and static URL mode is not lifecycle isolation; and
- canonical observed autocomplete export is implemented: the existing CLJS
  exporter now emits a byte-stable, content-addressed v1 manifest with complete
  coordinates, source/runtime/config/profile identities, serving cards,
  deduplicated referenced-schema closures, stable row ids/splits, and
  addressable rejection rows. Inspect verifies the envelope and consumes frozen
  split rows without rebuilding projections. Counterfactual/substantive targets
  and current-world replay verdicts remain scorer/replay work.

Historical stable/display/pin/plan-pilot evidence is classified and hash-backed
in the runtime-reliability preservation audits. It remains evidence to replay,
not code to cherry-pick. Training and paid model trials remain paused.

## Dependency order

1. Source/run admission is complete. In parallel, freeze the ordinary-work
   development/milestone/blind battery and define the schema-registered,
   content-addressed autocomplete manifest.
2. Publish one token-fenced operator lease returning artifact/config/source and
   dynamic web/CLJ/CLJS/database coordinates.
3. After their respective prerequisites, migrate live Inspect/typeahead callers
   through the lease while implementing canonical export, replay, and layered
   scoring through current render/eval/database boundaries.
4. Run deterministic fixtures, offline calibration, local simple-model rungs,
   then the bounded large-planner/small-executor reference.
5. In parallel under its own evidence gates, promote/read back accepted old-lane
   evidence and retire only owner-approved worktrees whose unique evidence no
   longer depends on them.

## Ordered work

1. **Source admission — complete.** A fresh `uv sync --extra test` resolves the
   reviewed framework/task/provider world; deterministic revision, dirty-
   source, installed-origin/version, provider, lock, and native-log checks gate
   task construction and run finalization. The native log carries the admitted
   source map. Focused proof is 27 tests; the complete offline gate is 321
   passed/eight skipped.
2. **Live ownership.** Implement the operator lease, then prove concurrent
   disjoint samples, dynamic CLJ/CLJS discovery, identity-preserving restart,
   cancellation, and token-fenced idempotent cleanup.
3. **Canonical export — observed artifact complete.** The existing
   ClojureScript exporter emits serving cards plus deduplicated referenced
   schemas, content-addressed rows/manifest, deterministic splits, and retained
   rejections. The Inspect reader verifies every digest/reference before split
   selection. Add counterfactual/substantive modes only with the replay/scorer
   implementation that can prove their semantics; observed rows declare their
   mode now and are never silently rewritten.
4. **Replay and scorer.** Stage facts, render through serving, eval through the
   current boundary, derive database outcomes, and port historical fair-scoring,
   LoRA failure, and continuation scanner acceptance cases into Inspect.
5. **Measurement contract.** Freeze representative database, schema, namespace,
   filesystem/shell/web, plan/restart, recovery, and evidence-report tasks with
   deterministic development/milestone/blind membership and category floors.
6. **Model ladder.** Compare large-plan/small-execute, small-alone, large-alone,
   and pretransacted-plan diagnostic arms on read/process/write/restart/report
   tasks. Begin with mocks and local simple models. Change schemas/functions
   only for general discoverability/contract failures and rerun the whole frozen
   battery after every accepted refinement.

## Parallelizable research

- Source admission, canonical export, frozen-battery definition, and durable
  old-lane/database disposition may proceed in parallel.
- Live caller migration waits for the lease. Scorer migration waits for the
  canonical export. Comparative model trials wait for source admission, lease,
  export, replay/scorer, and frozen-battery gates.

## Graduation

- A fresh environment reproduces every accepted score from content-pinned
  sources and complete provenance.
- Live samples acquire/release one fenced cluster lease and exercise both CLJ
  and CLJS through current MCP/operator boundaries.
- Autocomplete and tool-use evidence comes from one canonical export; no
  scratch scorer or hidden context is required.
- A large planner can encode a durable plan for a smaller executor, and the
  executor completes representative read/process/write/user tasks with honest
  evidence and recovery.
- Simpler-model failures result in clearer schemas/functions; the default
  context remains generated from the real tool surface.
- Every accepted run retains native Inspect logs plus framework, task, scorer,
  model/provider, operator artifact/config/source, and complete database
  coordinate identities.
- Historical evidence needed for comparison is reproducible without entering
  an old worktree; cleanup remains separately owner-authorized.
