---
type: research
status: active
tags: [research, operator, database]
date: 2026-09-09
---

# Incremental publication provenance

Read AGENTS.md's verbatim §10 lane rules, the turn PRD §10, and
`docs/seon/issues/incremental-publication-refuses-missing-function-provenance.md`
end to end. Read the plan README and working edge and the data-oriented
Clojure, REPL, testing, and Datahike skills.

Dependency ledger: Datahike map upserts leave omitted facts untouched
(`reference-code/datahike/src/datahike/db/transaction.cljc:738`, `explode`,
and `:949`, `entity-map->op-vec`; gitlink
`cdcb5792db8bd599487f099437265d18a31164a5`); the first-party transaction
boundary validates the authored entity map (`src/seon/db.clj:2387`,
`write-map-error`). The schema bridge
already derives component and cardinality-many attributes
(`src/seon/fn.clj`, `many-or-component-attributes`, through
`src/seon/schema/datahike.clj`). Static artifacts already attach core
provenance (`src/seon/fn.clj`, `artifact`). Reuse those owners.

The live JVM probe on default returned an incremental row containing only
`{:seon.fn/doc "after", :seon.fn/sym "probe/value"}` from a complete
immutable input containing `:seon.fn/ns [:seon.ns/name 'probe]` and
`:seon.schema.admission/source :core`. Both omitted attributes are required
by `resources/seon/schemas/seon.fn.edn`. A first installed-row probe returned
nil and was inconclusive; the explicit immutable example established the
projection defect without mutating default.

The fix retains each changed row's scalar declaration, excluding attributes
the existing schema bridge identifies as components or cardinality-many.
This retains provenance for namespace, function, and test rows and all
required scalar keys without a second required-key roster. The existing
planner still sends structural edits to complete publication.

Verification:

- `bin/test-fast --paths src/seon/fn.clj test/seon/cluster/source_test.clj -- seon.cluster.source-test`:
  13 tests, 96 assertions, zero failures/errors. Contracts armed in panic mode:
  944 instrumented functions. The first iteration failed because this lane's
  new test lacked a `seon.program` require; corrected before this green run.
- `bin/test --paths src/seon/fn.clj test/seon/cluster/source_test.clj -- seon.cluster.source-test`:
  13 tests, 98 assertions, zero failures/errors; coordinator/tests 55 seconds.
  Snapshot basis `2b9ebe98c433a45c22ae3d8208b066b3ad4f5c24`.
- `bin/test --paths src/seon/fn.clj test/seon/cluster/source_test.clj --platform`:
  84 tests, 505 assertions, zero failures/errors; coordinator/tests 64 seconds.
  This gate includes the final component-presence assertions. Both successful
  isolated roots were removed by the gate. No worktree was needed.
- Final regression explicitly asserts the preexisting alias and function
  arity components are present before comparing their identities. Its
  first-party source bytes change `One identity entry:` to
  `The identity entry:` and `SHA-256 of (pr-str data), truncated` to
  `SHA-256 of (pr-str data), shortened` in a disposable copy of `seon/id.clj`.
  It runs authored-map validation on `with-database`, then publishes through
  `source/upsert!` over the canonical manifest populated by
  `seon.cluster/populate-source!`.

Foreign boundary: data-lane owns in-flight schema, turn, message, plan,
and other writer/test edits. None were edited, reverted, messaged, or operated.
Its protected `test/seon/fn_test.clj:993` still expects the invalid sparse row;
the integration follow-up is
`docs/seon/issues/incremental-planner-test-expects-incomplete-rows.md`.
The complete `seon.fn-test` namespace is outside this lane's verification.

Live: hot-reloaded only `seon.fn` through MCP JVM evaluation, with the
cluster-derived projection and instrumentation reapplied. The same immutable
probe now retains `:seon.fn/ns` and `:seon.schema.admission/source :core`.
The first explicit `init --dev default --changed src/seon/fn.clj` attempt
refused at the source-digest stability check while edits were ongoing;
it did not establish convergence. The next explicit command is queued behind
the edit hook's publication command; live completion will be recorded in a
follow-up commit. Default has never been stopped, restarted, or reforked.
