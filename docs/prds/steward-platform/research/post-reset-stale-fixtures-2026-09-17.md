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
