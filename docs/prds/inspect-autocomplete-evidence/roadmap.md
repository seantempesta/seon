---
type: prd
status: planned
tags: [prd, agent, research]
---

# Inspect and autocomplete evidence roadmap

> Detailed source evidence and the implementation/acceptance matrix live in
> [[research/inspect-autocomplete-source-evidence-audit-2026-07-14]].

## Outcome

Inspect produces reproducible evidence for ordinary system work, long-term
planning, autocomplete/data quality, and large-planner/small-executor tool use;
reviewed ACME refinements improve the canonical `my.*` surface rather than
creating a benchmark-only context or runtime.

## Current state

The installed Inspect environment currently passes **311 tests with eight
expected skips**, and current source contains standard `inspect_evals` task
adapters, BFCL and SWE-bench arms, deterministic oracles, frozen dataset/image
metadata, append-only scorecards, planning trajectory scorers, a historical
typeahead replay corpus, and a real as-of ClojureScript autocomplete exporter.

These mechanisms are not yet one reproducible evidence path:

- installed Inspect is `0.1.dev1+g92dd737b9`, while the inspected reference
  checkout is dirty `05322696...` / `0.3.246`; the local-path dependency pins
  neither, and installed `inspect_evals` also differs from its reference source;
- per-sample create/restart/release correctly fail because the operator has no
  ownership-fenced lease; static URL mode is not isolation;
- scorecard rows omit framework/task/scorer and complete cluster/database
  source identities, while native `.eval` log retention is best-effort;
- autocomplete rows use database name + basis `t` + Git SHA rather than the
  complete coordinate, runtime/config/profile identities, referenced-schema
  closure, frozen row/split manifest, current-world verdict, and retained
  rejection rows; and
- the active ACME tool-refinement lane has not been handed back and remains
  outside this branch's review or cleanup authority.

Historical stable/display/pin/plan-pilot evidence is classified and hash-backed
in the runtime-reliability preservation audits. It remains evidence to replay,
not code to cherry-pick. Training and paid model trials remain paused.

## Dependency order

1. Content-pin Inspect, Inspect Evals, Python provider/task bytes, and run
   admission/provenance.
2. Publish one token-fenced operator lease returning artifact/config/source and
   dynamic web/CLJ/CLJS/database coordinates.
3. Migrate live Inspect and typeahead capture off raw writer forms, direct blob
   paths, private endpoint files, and best-effort evidence finalization.
4. Produce one schema-registered, content-addressed autocomplete manifest with
   complete coordinates, explicit projection semantics, schema closure, stable
   row ids/splits, and retained rejection evidence.
5. Replay/stage candidates through current render/eval/database boundaries and
   rebuild layered fair scoring in Inspect.
6. Review the ACME refinement lane only after explicit handback, commit by
   commit; land canonical `my.*`/generated-context changes after default proof.
7. Run deterministic fixtures, offline calibration, local simple-model rungs,
   then the bounded large-planner/small-executor reference.
8. Promote/read back accepted old-lane evidence and retire only owner-approved
   worktrees whose unique evidence no longer depends on them.

## Ordered work

1. **Source admission.** Make a fresh environment resolve the exact clean
   reviewed framework/provider/task bytes and reject a mismatch before task
   construction. Require native Inspect logs and complete provenance for an
   accepted run.
2. **Live ownership.** Implement the operator lease, then prove concurrent
   disjoint samples, dynamic CLJ/CLJS discovery, identity-preserving restart,
   cancellation, and token-fenced idempotent cleanup.
3. **Canonical export.** Strengthen the existing ClojureScript exporter in
   place. Emit the same runtime cards plus referenced schemas once; distinguish
   observed/counterfactual/substantive targets; content-address rows, manifest,
   splits, and rejections at one complete database coordinate.
4. **Replay and scorer.** Stage facts, render through serving, eval through the
   current boundary, derive database outcomes, and port historical fair-scoring,
   LoRA failure, and continuation scanner acceptance cases into Inspect.
5. **ACME handback.** Record base/tip/evidence, review commits in order, reject
   benchmark-only context and parallel protocols, prove default, then rebuild
   and prove ACME.
6. **Model ladder.** Compare large-plan/small-execute, small-alone, large-alone,
   and pretransacted-plan diagnostic arms on read/process/write/restart/report
   tasks. Begin with mocks and local simple models. Change schemas/functions
   only for general discoverability/contract failures and rerun the whole frozen
   battery after every accepted refinement.

## Parallelizable research

- Source pin/admission and canonical export schema design may proceed in
  parallel.
- Durable old-lane packaging/read-back may proceed independently under its
  existing maintenance/owner gates.
- Live caller migration waits for the lease. Scorer migration waits for the
  canonical export. Model trials wait for source, lease, export, replay, and
  ACME handback gates.

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
