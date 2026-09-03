---
type: issue
status: open
severity: friction
tags: [issue, operator, maintenance, schedule]
---

# `root/maintenance/reap-dead-roots` fails: `delete-recursively!` receives a nil path

## Problem

Scheduled task `root/maintenance/reap-dead-roots` (handler
`seon.operator/reap-dead-roots!`, fired by `seon.schedule/fire-due!` →
`invoke-handler` → settled by `settle!`) failed on cluster `ctxprobe` at
2026-09-03T02:15:00Z with `seon.fs/delete-recursively! violated its
contract (invalid-input): invalid type`, `:seon.error/diagnostic-offending
[nil]`, cause `:malli.core/invalid-input`. One of the two string
arguments was nil. The failure became a message to root, which opened a
paid run (see the analyze-form issue). Every `delete-recursively!` call
site takes two paths (`src/seon/operator.clj:294`, `:571`,
`src/seon/cluster/export.clj:185,211,330`, `src/seon/cluster/store.clj:274`);
the reap path reaches one of them with a claim or cluster whose root/dir
is absent. Payload: `tmp/ctxprobe-reap-error.edn` (print-node EDN, 26 KB,
frames `seon.schedule$fire_due_BANG_` → `settle_BANG_`).

## Owner

`seon.operator/reap-dead-roots!` and the cluster-cleanup helper it calls
(`src/seon/operator.clj` ~283-300 and ~555-580); `seon.schedule` only if
the handler's request lacks a declared input.

## Acceptance

A claim/cluster without a resolvable root is a typed refusal in the reap
RESULT (named, counted), never a nil handed to the deletion owner; the
task settles `:result` on a scratch root with one dead ephemeral root and
one malformed claim; regression in the operator test namespace. Also
decide: should a maintenance failure open a MODEL run for root at all
(R41 dial) — record the answer in the note if it is a design question.
