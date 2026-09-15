---
type: research
status: complete
tags: [issue, runtime, docs]
---

# Slice A blocker triage — 2026-09-15

**Counts: 9 resolved / 0 superseded / 1 confirmed / 15 unverifiable.**
Six open notes downgraded from blocker to friction. These are triage verdicts,
not a claim that the unverified defects are fixed.

## Verification boundary

Read AGENTS.md, docs/seon/issues/README.md, and
docs/prds/steward-platform/README.md end to end, plus the requested last five
dated sections of docs/prds/context-generation/plan/unsettled.md
(2026-09-15 14:45Z through 17:25Z). Read every assigned issue in full.
Applied the REPL, data-oriented Clojure, Datahike, and testing skills.

Source evidence is pinned to session-start HEAD
`7e35df2131c71f476a85c6a38bfc8eb292cb36f5`, inspected using git show/log and
source searches. Concurrent edits and later commits were preserved.
The one test snapshot used later HEAD `ad5780df5856f1adf30fd1995706c0564a716129`;
its runner reported no snapshot differences. No production or test files
were edited by this assignment.

Default was alive at PID 23729. MCP runtime_status returned health and Flow
unknown with “Read timed out”; JVM `(+ 1 2)` returned 3 in 2 ms.
The two read-evidence probes timed out at 10000 ms. Analyzer probes refused
their supplied program-row shape before reaching the intended analysis.
A disposable SCI pull returned an opaque projection-error string.
Those observations are recorded in the relevant issue notes; none is
attributed to another lane or treated as confirmation of the historical cause.
The default JVM's exact loaded generation was not established during concurrent
adoption. The confirmed transaction-query discrepancy uses the unchanged
Datahike gitlink `cdcb5792db8bd599487f099437265d18a31164a5`.

Before the owner's no-JVM correction, the armed fast test
`seon.background-blob-test` ran: **1 test / 1 assertion, 0 failures / 1 error**.
Its setup called dec on a nil threshold before any background submission.
It does not reproduce the old missing terminal event. The process exited
(status 1); PID 7524 is absent and its automatically cleaned snapshot
`tmp/test-runs/run.ZQyDo4` is absent. No owned background shell or scratch
cluster remains. No additional JVM, gate, or platform run was launched after
the correction. Checks requiring a gate are explicitly
UNVERIFIABLE-WITHOUT-GATE with the namespace in each note.

## Issue table

Every evidence link targets the new dated section containing the source
owner, commit or exact probe, observed result, and limits.
Resolved notes were moved to archive under the issues README lifecycle.

| # | Issue | Verdict | Surface | Evidence pointer |
|---:|---|---|---|---|
| 1 | [a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently](../../../seon/issues/a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently.md) | unverifiable | adoption-publication | [Dated evidence](../../../seon/issues/a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently.md#re-verified-at-head-2026-09-15) |
| 2 | [a-mid-stream-provider-disconnect-discards-the-whole-turn](../../../seon/issues/archive/a-mid-stream-provider-disconnect-discards-the-whole-turn.md) | resolved | turn-loop | [Dated evidence](../../../seon/issues/archive/a-mid-stream-provider-disconnect-discards-the-whole-turn.md#resolution-2026-09-15-triage) |
| 3 | [a-render-exception-stops-the-cluster-render-proc-and-every-page-hangs](../../../seon/issues/archive/a-render-exception-stops-the-cluster-render-proc-and-every-page-hangs.md) | resolved | render-debug-page | [Dated evidence](../../../seon/issues/archive/a-render-exception-stops-the-cluster-render-proc-and-every-page-hangs.md#resolution-2026-09-15-triage) |
| 4 | [a-run-history-entry-can-name-a-different-run-than-its-form-pulled](../../../seon/issues/archive/a-run-history-entry-can-name-a-different-run-than-its-form-pulled.md) | resolved | context-generation | [Dated evidence](../../../seon/issues/archive/a-run-history-entry-can-name-a-different-run-than-its-form-pulled.md#resolution-2026-09-15-triage) |
| 5 | [a-runs-last-form-can-close-without-a-receipt](../../../seon/issues/archive/a-runs-last-form-can-close-without-a-receipt.md) | resolved | turn-loop | [Dated evidence](../../../seon/issues/archive/a-runs-last-form-can-close-without-a-receipt.md#resolution-2026-09-15-triage) |
| 6 | [a-schema-resource-edit-bricks-value-admission-in-every-running-cluster](../../../seon/issues/archive/a-schema-resource-edit-bricks-value-admission-in-every-running-cluster.md) | resolved | adoption-publication | [Dated evidence](../../../seon/issues/archive/a-schema-resource-edit-bricks-value-admission-in-every-running-cluster.md#resolution-2026-09-15-triage) |
| 7 | [adopted-default-no-provider-turn-does-not-settle](../../../seon/issues/adopted-default-no-provider-turn-does-not-settle.md) | unverifiable | turn-loop | [Dated evidence](../../../seon/issues/adopted-default-no-provider-turn-does-not-settle.md#re-verified-at-head-2026-09-15) |
| 8 | [adoption-refuses-a-local-plan-registry-reference](../../../seon/issues/archive/adoption-refuses-a-local-plan-registry-reference.md) | resolved | adoption-publication | [Dated evidence](../../../seon/issues/archive/adoption-refuses-a-local-plan-registry-reference.md#resolution-2026-09-15-triage) |
| 9 | [agent-form-calls-to-core-namespaces-are-not-indexed](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md) | unverifiable | other | [Dated evidence](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md#re-verified-at-head-2026-09-15) |
| 10 | [agent-html-still-uses-the-retired-transcript-assembler](../../../seon/issues/agent-html-still-uses-the-retired-transcript-assembler.md) | unverifiable | render-debug-page | [Dated evidence](../../../seon/issues/agent-html-still-uses-the-retired-transcript-assembler.md#re-verified-at-head-2026-09-15) |
| 11 | [an-entity-pull-returns-a-sentence-instead-of-its-attributes](../../../seon/issues/an-entity-pull-returns-a-sentence-instead-of-its-attributes.md) | unverifiable | context-generation | [Dated evidence](../../../seon/issues/an-entity-pull-returns-a-sentence-instead-of-its-attributes.md#re-verified-at-head-2026-09-15) |
| 12 | [background-binary-settlement-does-not-publish-required-event](../../../seon/issues/background-binary-settlement-does-not-publish-required-event.md) | unverifiable | runner-gate | [Dated evidence](../../../seon/issues/background-binary-settlement-does-not-publish-required-event.md#re-verified-at-head-2026-09-15) |
| 13 | [bootstrap-o4-stops-before-causal-delegation-settles](../../../seon/issues/bootstrap-o4-stops-before-causal-delegation-settles.md) | unverifiable | other | [Dated evidence](../../../seon/issues/bootstrap-o4-stops-before-causal-delegation-settles.md#re-verified-at-head-2026-09-15) |
| 14 | [bound-pull-selector-evidence-retains-all-attributes](../../../seon/issues/bound-pull-selector-evidence-retains-all-attributes.md) | unverifiable | context-generation | [Dated evidence](../../../seon/issues/bound-pull-selector-evidence-retains-all-attributes.md#re-verified-at-head-2026-09-15) |
| 15 | [bound-transaction-input-selects-an-older-turn](../../../seon/issues/bound-transaction-input-selects-an-older-turn.md) | confirmed | context-generation | [Dated evidence](../../../seon/issues/bound-transaction-input-selects-an-older-turn.md#re-verified-at-head-2026-09-15) |
| 16 | [class-accepted-work-can-end-without-terminal-evidence](../../../seon/issues/class-accepted-work-can-end-without-terminal-evidence.md) | unverifiable | turn-loop | [Dated evidence](../../../seon/issues/class-accepted-work-can-end-without-terminal-evidence.md#re-verified-at-head-2026-09-15) |
| 17 | [class-destructive-reachability-changes-are-not-atomic](../../../seon/issues/class-destructive-reachability-changes-are-not-atomic.md) | unverifiable | store-process | [Dated evidence](../../../seon/issues/class-destructive-reachability-changes-are-not-atomic.md#re-verified-at-head-2026-09-15) |
| 18 | [class-loaded-artifacts-lack-source-identity](../../../seon/issues/class-loaded-artifacts-lack-source-identity.md) | unverifiable | adoption-publication | [Dated evidence](../../../seon/issues/class-loaded-artifacts-lack-source-identity.md#re-verified-at-head-2026-09-15) |
| 19 | [class-mutable-resources-lack-explicit-root-and-lifetime](../../../seon/issues/class-mutable-resources-lack-explicit-root-and-lifetime.md) | unverifiable | store-process | [Dated evidence](../../../seon/issues/class-mutable-resources-lack-explicit-root-and-lifetime.md#re-verified-at-head-2026-09-15) |
| 20 | [class-outward-values-bypass-total-render-contract](../../../seon/issues/class-outward-values-bypass-total-render-contract.md) | unverifiable | context-generation | [Dated evidence](../../../seon/issues/class-outward-values-bypass-total-render-contract.md#re-verified-at-head-2026-09-15) |
| 21 | [cohosted-clusters-share-one-unbounded-agent-heap](../../../seon/issues/cohosted-clusters-share-one-unbounded-agent-heap.md) | unverifiable | store-process | [Dated evidence](../../../seon/issues/cohosted-clusters-share-one-unbounded-agent-heap.md#re-verified-at-head-2026-09-15) |
| 22 | [cohosted-second-boot-is-slow-and-trips-the-silence-backstop](../../../seon/issues/archive/cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md) | resolved | store-process | [Dated evidence](../../../seon/issues/archive/cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md#resolution-2026-09-15-triage) |
| 23 | [debug-feed-waits-fail-only-in-pooled-worker](../../../seon/issues/debug-feed-waits-fail-only-in-pooled-worker.md) | unverifiable | runner-gate | [Dated evidence](../../../seon/issues/debug-feed-waits-fail-only-in-pooled-worker.md#re-verified-at-head-2026-09-15) |
| 24 | [debug-source-execution-invalidates-its-own-preview](../../../seon/issues/archive/debug-source-execution-invalidates-its-own-preview.md) | resolved | render-debug-page | [Dated evidence](../../../seon/issues/archive/debug-source-execution-invalidates-its-own-preview.md#resolution-2026-09-15-triage) |
| 25 | [debug-system-turn-elision-hides-generated-forms](../../../seon/issues/archive/debug-system-turn-elision-hides-generated-forms.md) | resolved | render-debug-page | [Dated evidence](../../../seon/issues/archive/debug-system-turn-elision-hides-generated-forms.md#resolution-2026-09-15-triage) |

## Ranked confirmed-open blockers

1. **Bound transaction input selects an older turn** —
   [issue](../../../seon/issues/bound-transaction-input-selects-an-older-turn.md).
   The complete joined query returned older entity 106106 while the
   explicit equality form returned nil for latest turn 106420 / transaction
   536879236 (24 ms). This threatens arbitrary database reads used to generate
   context; the continuing-reply caller already has an equality workaround.
   **Fix sketch:** reduce and repair transaction-input binding in
   [reference-code/datahike/src/datahike/query.cljc](../../../../reference-code/datahike/src/datahike/query.cljc),
   preserving read-evidence semantics; retain the workaround in
   [src/seon/turn.clj](../../../../src/seon/turn.clj) until the dependency
   regression passes.

There are no other confirmed-open blockers in this slice. The remaining
blocker-severity notes are explicitly unverified, not silently promoted into
this ranking. A failed setup or timed-out observation is not a reproduction
of the defect it was intended to test.

## Landing and owner follow-through

Issue batches committed, five at a time:

- 1–5: `ea2044ca1`
- 6–10: `03976706c`
- 11–15: `ddc0a4a0e`
- 16–20: `adbe9f5f1`
- 21–25: `a5f3d7565`

The finishing commit archives nine resolved notes, repairs links inside the
owned notes, adds the gate restriction to early unverified notes, and adds
this report. All touched paths are these 25 issue notes (including their
nine archive destinations) and this landing note.

The owner's ranked `docs/seon/issues/index.md` was not edited: AGENTS.md
reserves that schedule to the orchestrator. It needs the nine archived rows
removed and the six severity downgrades reflected. This is an explicit
documentation integration boundary, not a claim that the index checker is
green. No index checker was launched after the no-JVM correction.

An early Markdown hook also reported dependency-pin validation could not
read `docs/seon/issues/plan-renderer-arity-change-blocks-development-publication.md`.
That path is outside this slice; no cause is assigned. The final local
checks use Python and git only.
