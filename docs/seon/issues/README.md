---
type: reference
status: active
tags: [reference, issue, index, wave/agent-context, wave/ai-provider-integrity, wave/ai-provider-protocol, wave/ai-retry-evidence, wave/artifact-startup, wave/background-settlement, wave/blob-staging, wave/blob-storage, wave/boot-velocity, wave/capability-surface, wave/causal-episode, wave/changed-test-selector, wave/class-kill-queue, wave/cluster-search-wiring, wave/config-application-contract, wave/config-cluster-identity, wave/config-derivation, wave/context-derivation, wave/context-fixes, wave/contract-gate, wave/contract-generator, wave/core, wave/database-codec, wave/datahike-fork-logging-seam, wave/dev-mcp, wave/dev-tooling-face-hygiene, wave/directory-claims, wave/docs-honesty, wave/effect-ordering-follow-up, wave/error-class-contract, wave/error-face-budget, wave/eval-driver-lifecycle, wave/eval-scale-economics, wave/evolving-session-phases, wave/evolving-session-prd, wave/exclusive-sweep, wave/explicit-environment-proof, wave/flow-join, wave/flow-protocol, wave/fresh-portability, wave/future-model-continuation, wave/future-program-graph-binding, wave/future-runtime-lint, wave/general, wave/generate-call-transition, wave/generated-receipts, wave/instrumentation-error-data, wave/live-drive-context, wave/live-drive-render, wave/load-time, wave/mcp-process-lifetime, wave/message-delivery, wave/message-transaction-data, wave/my-branch, wave/namespace-page-performance, wave/no-crash, wave/open-maps-accretion, wave/operator-artifact-follow-up, wave/operator-child-lifecycle, wave/operator-launch-concurrency, wave/operator-lock-contention, wave/operator-lock-scope-follow-up, wave/operator-process-identity, wave/operator-status-face, wave/oversight-test, wave/parallel-stress-triage, wave/per-cluster-live-graph, wave/per-run-fork-context, wave/post-gate-rename, wave/prefix-drift-bootstrap, wave/print-path, wave/program-graph-indexing, wave/program-index-proof, wave/provider-context, wave/publication-provenance, wave/publication-velocity, wave/rebirth-gap, wave/reconcile-evidence, wave/render-acquisition-performance, wave/render-arm, wave/render-connection-model, wave/render-context-cache, wave/render-oversight-event, wave/render-package-economics, wave/render-producers, wave/render-property-premise, wave/render-receipt-producer, wave/render-test, wave/reply-durability, wave/run-loop-velocity, wave/runtime-boundary-refactor, wave/schedule-fixture, wave/schema-admission, wave/schema-codec-deletion, wave/schema-form-extraction, wave/schema-key-ruling, wave/schema-lifecycle, wave/schema-population-deletion, wave/schema-projection-performance, wave/sci-base-context-derivation, wave/sci-eval-context-owner, wave/sci-eval-readiness, wave/sci-failure-face, wave/sci-reader-limit, wave/sci-static-admission, wave/seon-env-p3, wave/settlement, wave/shared-surface-scheduling, wave/store-perf, wave/strict-repl-display, wave/test-fixture, wave/transcript-deletion, wave/transcript-ordering-follow-up, wave/ui-watchability, wave/unreadable-reply, wave/upstream-delta, wave/verification-audit, wave/visual-qa, wave/whole-system-arc, wave/why-awake, wave/work-ordering-follow-up]
---

# Issues — indexed entities and Markdown evidence

One note records one problem. Publication indexes the note into `seon.issue`;
function, test, error and member refs connect it to the program graph.
Markdown retains the dated explanation and evidence.

## Lifecycle and severity

The lifecycle remains `open → resolved | superseded`. Open notes live directly
under `docs/seon/issues/`; closed notes live in `archive/`. Update the status
and move the note when closing it. Every issue declares one severity:
`blocker` prevents work or shipping, `friction` slows work, and `cleanup`
records tidiness or obsolete mechanisms.

## The index is a query

`bin/issues-index` reads current open issue entities from the running default
cluster. `bin/issues-index --check` prints the indexer's refusal report and
exits nonzero when citations or note metadata cannot be resolved. It no
longer validates a second hand-maintained schedule.

`bin/seon init` indexes the folder at source publication; development
adoption copies those exact published facts by identity. The source digest
includes the issue folder. Markdown-only changes are included at the next
publication; an explicit `bin/seon init --dev default` publishes them now.
Ordinary older clusters retain their chosen publication.

Query directly with:

```clojure
(seon.issue/issues {:seon.db/db (seon.db/db connection)
                    :seon.issue/status :open})
```

The indexer replaces current note facts and preserves an identity tombstone
when a note disappears. It reports unresolved qualified symbols rather than
storing them as function refs. Function and test identities must already be
installed. Nine-character Git citations are strings, not Datahike commit IDs.

## Classes and historical tags

A note carrying `class-kill` and a `class/<id>` tag references the member
issues carrying that class tag. `bin/issues-index --class class/n1` prints
the indexed open members. File tags remain useful historical metadata;
classification in the database follows function refs. Wave tags are not
copied into the database and do not define a second schedule.

## Authoring and assignment

`my.issue/add!` authors a database issue; `my.issue/tests!` adds success
tests. `seon.issue/start!` creates the worker, its plan and first turn in one
transaction. It requires tests or a detector and refuses an existing assignment. The
agent's issue unit links to the referenced entities and their render pairs.

Before plan settlement, the turn runs the worker's open-issue tests under
one configured evaluation deadline. Completion requires current verified
run facts when tests are present; otherwise the detector must no longer name
the issue's subject. Settlement records the step's completion and
`:seon.issue/resolved-tx` in the same transaction. The status read verifies
the current reach digest.

Database admission preserves a nonempty test set after first assignment,
including after unassignment and through nested transaction functions.
Only the issue's original creator may retract test members; source
reconciliation preserves worker assignment, creator authority and tests.
The [settlement landing note](../../prds/steward-platform/research/issue-settlement-2026-09-16.md)
records the live proofs and remaining verification boundaries.

## Frontmatter template

```yaml
---
type: issue
status: open
severity: cleanup
created: 2026-09-16
tags: [issue, database]
---

# State the problem

## Problem

One observed mismatch.

## Evidence

Current symbols and source paths, plus a failing test or live observation.

## Owner

The existing mechanism that should change.

## Acceptance

Observable behavior, preferably named deftests.
```
