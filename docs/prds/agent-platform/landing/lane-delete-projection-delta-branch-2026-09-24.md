---
type: landing
lane: delete-projection-delta-branch
status: landed-installed
created: 2026-09-24
---

# Lane delete-projection-delta-branch (2026-09-24)

Deletion slice named in
[lane-fix-schema-projection-rules](lane-fix-schema-projection-rules-2026-09-24.md)
item 7. `seon.schema/build-projection` had a first `cond` branch that took a
projection's pure data as `forms` plus a divergence delta as
`function-contracts` and composed them through `compose-projection-data`.
`projection-delta`, its only producer, was deleted in 53a038419.

## Broken or unproven

- `seon.schema-test` stays red on the same 9 tests it had before this slice
  (listed below); this slice fixes none of them.
- Adoption was refused for about 6 minutes: "Source changed during development
  adoption." `changed-paths` named foreign uncommitted test edits
  (render/value_test, schema_test, root_pull_test, turn_work_test,
  instrument_test, dev/mcp_bridge_test, config_test). This is
  `docs/seon/issues/a-db-adoption-refuses-while-another-lane-edits-a-dependent.md`.
  The adoption that succeeded (`bin/seon init --dev default --changed
  src/seon/schema.clj`, commit-id 6ab58abb, 14:40:30) took 20.8 s, and
  `seon.cluster/refresh-source!` accounted for 62 s inclusive. The cost
  belongs to that same issue, not to this deletion.
- Ownership collision: the lane harness-s12-deletions has uncommitted
  deletions in `test/seon/schema_test.clj`, a file this lane owns. I left
  them in place. This slice needed no test edit, so its commit does not
  include that file.

## Evidence the branch was dead

- `rg` over `src test script bin`: `compose-projection-data` appears only
  in `src/seon/schema.clj` (its declare, its definition, the one branch call).
  `:seon.schema/divergence-delta` is named only in its contract.
  No schema resource declares it.
- Stored reverse edges on default (JVM mode, `seon.db/q` over
  `:seon.fn/calls`): the only caller of `seon.schema/compose-projection-data`
  is `seon.schema/build-projection`. The only caller of the private
  `seon.schema/reverse-dependencies` is `compose-projection-data`.
- Every `build-projection` call in src (fn.clj 2222, 2480, 2748, 2932, 2959,
  3598; schema/edn.clj 605; program.cljc 285/290; schema.clj's own) passes a
  `{schema-key form}` population, never a projection.

## Deleted

`src/seon/schema.clj`: the `(contains? forms :seon.schema.projection/forms)`
branch, `compose-projection-data`, the private `reverse-dependencies` (only
it used it), and the `(declare compose-projection-data materialize-projection)`
line (the branch was its only forward reference). `materialize-projection` and
`projection-pure-data` stay: tests outside this lane (schema_test,
schema/datahike_test) call them. No test named the deleted functions, so
`test/seon/schema_test.clj` needed no change.

## Installed proof (default, pid 99265, JVM mode)

- After adoption, the stored `:seon.fn/calls` of `seon.schema/build-projection`
  no longer name `compose-projection-data`, and no `:seon.fn/sym` row exists
  for `compose-projection-data` or `reverse-dependencies`.
- `(build-projection (projection-pure-data p) {})` used to return a
  projection through the deleted branch (count 23). It now refuses loudly
  with `:malli.core/invalid-schema`.
- `(build-projection {:x/y :int} {})` takes 0.50 ms after the change. I did
  not time it before. The deleted branch was a `contains?` test ahead of the
  ordinary path, so removing it cannot add cost.

## Tests

`bin/test-check default --ns seon.schema-test`:

- Before: run c7ddf4f9737e (executed 9, reused 37, 12749 pass, 22 fail,
  1 error, 20.0 s).
- After: run 617a6eaad0e4 (executed 39, reused 4, 12702 pass, 23 fail,
  0 error, 45.6 s). The after run re-executed tests that reach the changed
  schema.clj, and the foreign sweep had deleted tests in between, so the
  counts differ.
- Both runs are red on the same 9 tests:
  a-component-bearing-row-validates-its-own-declared-shape,
  a-projection-with-no-bound-predicates-compiles-every-declared-shape,
  a-refused-projection-source-never-yields-a-projection-with-no-forms,
  an-incremental-projection-equals-its-full-build,
  error-declared-schemas-and-their-owned-members-are-storable,
  every-predicate-schema-declares-what-it-accepts,
  one-derivation-owns-the-projection-predicate-bindings,
  pulled-forms-derive-from-the-entity-schema-and-selector,
  render-declarations-require-a-contract-that-accepts-their-shape.
  No test went red.

## Size and lint

- Net lines: src/seon/schema.clj -69, +0.
- clj-kondo on schema.clj: 0 errors. Warnings went from 30 to 29.

## Verification limits

- Only seon.schema-test was run. The bulk tier and platform tier belong to
  root.
