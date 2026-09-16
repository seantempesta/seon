---
type: research
status: active
tags: [test, database, schema, render]
---

# Reach closure and structured test failures — 2026-09-16

Bounded lane `reach-closure-facts`, implementing the owner's A/A/A decisions in
[test-failure-facts-2026-09-16.md](test-failure-facts-2026-09-16.md), read end to
end. Bases `f2d537187` and `3402913f3` are ancestors of the inherited HEAD.
No test JVM is launched and this lane never restarts default.

## Dependency ledger

- `src/seon/test/runner.clj`: the incremental reach index owns both membership
  and digest; the completion carries portable lookup refs across stores.
- `reference-code/datahike/src/datahike/db/transaction.cljc`: `:db.fn/call`
  supplies the writer's database. Latest result replacement belongs there.
- `reference-code/datahike/src/datahike/db.cljc`: history/as-of/since supply
  temporal evidence; since excludes its basis.
- `src/seon/cluster/source.clj`: publication transports result facts through
  a private branch and preserves them on full builds. New result attributes
  must survive that existing seam too.
- `src/seon/blob.clj` stage!/commit-staged!, and `src/seon/turn.clj`
  stage-reply!: staged payloads are committed outside the transaction function.
- `clojure.test` reporter events carry assertion claims and source position;
  the canonical `seon.test-support/with-database` fixture owns test databases.

## Slice 1

`:seon.test/reach` is the latest tested function closure, many refs, replaced
atomically with every result. `:seon.test/reach-digest` remains the equality
key. Both derive from the same incremental index of the tested database;
cross-JVM completions carry lookup refs rather than foreign entity numbers.
Full publication preserves the membership. Regression:
`seon.test-failure-facts-test/recorded-reach-belongs-to-the-tested-value-and-is-replaced`.

Verification pending in-process adoption. Initial default: PID 53378,
basis 536871483, branch `cluster-default`. MCP answered; the new reach
attribute was absent. First hook publication met a concurrent head change
(`:stale-branch-head`); a subsequent publication is queued. This is not a
test verdict. Foreign issue-lane edits remain untouched.

## Slice 2

`seon.test/changed-since-green` derives run transitions from result-row
history and counts in force at each transition. Repeated green runs need
not reassert unchanged zero counts. Source and spec changes include
retractions and are intersected with recorded function refs. Missing test,
closure, or green history returns a typed unknown. The test pair offers the
query in AI source and links the named functions in HTML.

Publication exposed a prerequisite defect: `exact-replacement-tx` supplied
an entire cardinality-many tuple set to Datahike's tuple validator before
`retractAttribute` dispatch. It now supplies one existing value; the operation
still retracts the complete attribute. Regression covers a three-tuple set
replaced by one tuple on the canonical database. This extends owned files to
`src/seon/program.cljc`; no protected file was edited.

## Slice 3

The result writer records its destination branch; `:seon.test.run/tested-branch`
retains a different execution branch. `basis-t` still identifies the tested
value. The source publisher explicitly hands the durable `:current-src`
destination while it commits on a disposable branch. Direct cluster writes
derive their destination from the writer's database. The schema states this
rule, and retries compare normalized immutable provenance.
