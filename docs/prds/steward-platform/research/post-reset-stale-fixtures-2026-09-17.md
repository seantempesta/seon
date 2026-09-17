---
type: research
status: open
created: 2026-09-17
tags: [testing, reset, symbols]
---

# Post-reset stale fixtures

## A. Schema dependency closure

`test/seon/cluster/registry_test.clj:60` treated each
`:seon.schema/references` member as a two-place edge. The canonical row now
stores the direct reference keys as a set (`src/seon/schema.clj:3055-3062`),
derived from `canonical-reference-graph` (`src/seon/schema.clj:545-574`). The
fixture now expands that set directly. No schema changed.

## B. Symbol-valued identities

`test/seon/cluster/source_test.clj:175-203`, `:327-340`, and `:515-536` now
use qualified symbols for function identities and activation executable
symbols. The source-deletion fixture now obtains its complete function row
through `seon.test-support/program-fn-row` (`test/seon/test_support.clj:1064-1075`)
instead of writing a partial declaration.

`test/seon/test/selection_test.clj:27-78` now supplies the manifest artifact
shape declared by `seon.test.selection/reaching-tests`
(`src/seon/test/selection.clj:136-150`): test identities and call/reference
values are qualified symbols, rather than the legacy strings and lookup-ref
vectors.

The activation refusal constructor is current: its contract requires a
`:seon.fn/sym` at `resources/seon/schemas/seon.activation.edn:70-72`; the stale
test data was the root cause, not the refusal constructor.

## C. Exact-HEAD fast snapshots

`test/seon/test_runner_test.clj:1264-1276` now proves both cases from
`25a705b4b`: a missing ready record admits an exact-HEAD fast snapshot, while
a dirty `src/probe/api.clj` still refuses with the required cold-publication
instruction. The settled rationale is
`docs/seon/issues/archive/a-clean-fast-snapshot-demands-a-cold-head-publication.md`.

## D. SCI arm carriage

No arming-owner change is justified. `seon.cluster.source/publish!` contains
no SCI kernel arm crossing (`src/seon/cluster/source.clj:474` onward), while
SCI evaluation owns `kernel/arm` inside its `try` and calls its returned stop
function in `finally` (`src/seon/sci/eval.clj:2660`, `:2857`). The platform
error follows the source publication return-contract refusal, but does not
establish an arm leak from publication.

## Fast verification

`bin/test-fast --paths test/seon/test/selection_test.clj
test/seon/test_runner_test.clj -- seon.test.selection-test
seon.test-runner-test` completed the five selection tests green before the
runner namespace reached an unrelated canonical-base failure:
`FAIL in (assertionless-test-is-an-attributed-failure) ... actual: ... {}`.
The prior combined run blocked in a Datahike transaction during the first
registry fixture for more than its 290-second test bound; its JVM was ended
after `jstack` showed `datahike.api.impl/transact` waiting. This is the known
foreign canonical-base boundary owned by `fabricated-symbol-edges`, whose
`src/seon/fn.clj` repair is explicitly outside this lane.

Remaining red: `seon.cluster.source-test/stale-incremental-upsert-preserves-the-newer-publication` first reports `seon.cluster.source/publish! refused return value at [:seon.program/unresolved-report :seon.db/basis-t]`; its source return shape is outside the stale-fixture changes above. The cold platform proof remains owed to the orchestrator.

## 2026-09-17 — post-edge-fixture follow-up

After `fabricated-symbol-edges` landed (`edd212afc`, `2a30b98b8`,
`ec13ec814`), the source fixture no longer assumes a single function row for
`seon.id`. `incremental-first-party-publication-retains-complete-scalar-rows`
now positively asserts the six declarations in `src/seon/id.clj`, the `str`
namespace alias, and canonical `:seon.fn/arities` components with their input
and return schema refs (`test/seon/cluster/source_test.clj:361-393`; schema
ownership: `resources/seon/schemas/seon.fn.edn:27` and
`resources/seon/schemas/seon.fn.arity.edn:15-40`). It no longer queries the
deleted `:seon.fn/ast` family.

`source-tombstone-provenance-does-not-prevent-live-removal` now hands
`program-fn-row` `(db/db connection)`, the immutable database value its
carried-projection read requires (`test/seon/cluster/source_test.clj:536-542`).
`latest-test-evidence-survives-rebuilding-from-an-older-base` now uses the
qualified test symbol in `reach-digests` and every test identity lookup
(`test/seon/cluster/source_test.clj:559-604`).

The requested source-only fast invocation started, acquired its projection,
but did not reach a test after more than its 290-second bound. Two JVM stacks
placed its main thread in repeated `seon.schema/canonical-value-string`
work during test setup, so the exact runner was terminated. Therefore this
turn has no honest post-edit test tally. The eight known foreign publication
reds remain separately classified: `stale-incremental-upsert-preserves-the-newer-publication`,
`incremental-publication-does-not-change-an-existing-cluster`,
`incremental-upsert-seals-one-activation-on-the-expected-commit`,
`failed-and-stale-builds-preserve-the-published-head`,
`existing-clusters-remain-on-their-chosen-source-commit`,
`publication-advances-one-branch-and-retires-scratch`,
`incremental-upsert-derives-scalar-safety-from-installed-schema`, and
`an-activation-closure-with-empty-member-collections-seals`; each first
reports `:seon.program/unresolved-report` missing `:seon.db/basis-t`.

## 2026-09-17 — exact-HEAD fast-base announcement

The second post-reset platform gate isolated
`selected-overlays-require-a-current-graph-and-every-changed-caller` to one
launcher omission: `bin/test`'s exact-HEAD fast branch skipped
`seon.test.cache/newest-manifest`, which is the owner of both stale-base
selection and its `overlay graph <digest> age= <n> commits behind HEAD`
announcement (`src/seon/test/cache.clj:249-279`).

`bin/test:791-811` now retains exact-HEAD admission when no ready record
exists, while invoking that owner when a published base does exist. Thus an
exact-HEAD fast snapshot remains free of overlay-closure admission, but a
stale base is never silently used.

The requested fast command emitted `overlay graph
68243553e5aa77047fed283d6871548cd3460ae8591d0a6abe5aec653eeeefa1 age=
3 commits behind HEAD` before its test JVM started. The JVM later waited in
`seon.test-support/retrying-base`'s `acquire-base!` for more than the
290-second declared bound; the recorded `jstack` placed the main thread on
that promise. It was terminated after the bound, so no namespace tally is
claimed. The pre-existing `assertionless-test-is-an-attributed-failure` also
failed before that wait with empty recorded counters; it is outside the
overlay-launcher assertion.
