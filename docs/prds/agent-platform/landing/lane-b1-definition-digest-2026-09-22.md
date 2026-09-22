---
type: landing
status: landed with reset and cold-fixture proof pending orchestrator
created: 2026-09-22
tags: [agent-platform, b1, definition-digest]
---

# B1 definition digest

## Outcome

Commit `f27b96c19` writes `:seon.program/definition-digest` on function,
test, namespace and schema declaration rows. The attribute declaration,
function/test row-schema entries and schema-row constructor landed earlier in
the shared-file rename commit `6d84f27fa`; that commit explicitly attributes
those three hunks to this lane. Together they are the one additive schema and
consumer slice. `:seon.program/analyzed-source-digest` is unchanged.

`program/definition-digest` hashes the canonical data string for declaration
identity, exact authored definition, normalized namespace resolver context and
effective declaration metadata. It excludes admission authorship, file/span,
branch-local ids and derived call/reference/keyword/write observations.
`seon.test/definition-digests` now reads the stored fact instead of rebuilding
identity from eleven attributes.

**RESET NEEDED — RESET batch 1.** The live `default` database predates this
required attribute. This lane did not reset, stop, mutate or replace PID 38968.

## Dependency seam

The maintained Datahike fork is pinned at
`6dd49e5eab243a42ebd8d847830d037d0dae3c6a`.
`reference-code/datahike/src/datahike/writer.cljc:120-160` supplies one serial
writer loop carrying the prior database value. The final-report validator at
`reference-code/datahike/src/datahike/db/transaction.cljc:1206-1216` runs once
over the final report and attempted transaction data. Inputs are the complete
declaration transaction plus its carried projection; recomputation occurs on
publication, proportional to submitted changed rows and validation closure.
A required digest missing from any declaration therefore refuses the whole
publication rather than committing a partial population.

## Evidence

Running-system preflight:

- `bin/seon status`: `default` observed, PID 38968, start instant
  `2026-09-22T12:04:10.581Z`, no missing readiness layers.
- MCP `runtime_status`, explicit root `/Users/sean/src/seon`, cluster `default`:
  observed and alive.
- Read-only MCP JVM probe at basis 536871142: function 4462, test 2056,
  namespace 407, schema 3277, definition-digest count absent (`nil`). This is
  the expected pre-reset observation, not an after-publication pass.

The pure digest probe returned:

```clojure
{:length 64, :hex? true, :identical? true, :edited? true}
```

It exercised `seon.program/definition-digest` with identical function rows and
a one-token body edit. HEAD load after implementation commit `f27b96c19`:

```sh
clojure -M -e "(require 'seon.program 'seon.fn 'seon.schema 'seon.test)"
```

exited 0.

The canonical regression is
`seon.fn-test/publication-writes-one-definition-digest-per-declaration-family`.
It indexes one namespace containing one function and one test, adds one
canonical schema row, transacts through `seon.fn/reconcile-tx`, then pulls all
four identities. It checks 64 lowercase hexadecimal characters, equality for
identical definitions and inequality after a one-token edit.

Focused run `9c8c610e43f5` executed 103 tests and 420 assertions. It exposed one
relevant pre-fix refusal: `program/declaration-row` returned a declaration
missing the now-required digest. Fixing that producer made reader-stage rows
total. The run otherwise used cached overlay graph
`277ae2d23e467557e97a6d2a832bfd23a95551bf21dc3b0463b24914266aeead`,
eight commits behind HEAD, whose fixture projection still referenced retired
`seon.error/facet-counts-agree?`.

Focused run `1c2e5d4bd471` used the same graph, now nine commits behind HEAD:
103 executed, 436 assertions, 8 failures and 60 errors. The canonical digest
regression reached its four pure digest assertions successfully, then the
canonical database fixture refused before its transaction for that same stale
predicate. Under README §6 this is a retired-assumption/foreign fixture-base
failure, not green proof and not a digest-owner defect. The after-publication
count remains unavailable until the orchestrator refreshes the canonical base
and performs RESET batch 1. The required acceptance query is:

```clojure
(d/q '[:find (count ?e) .
       :where [?e :seon.program/definition-digest]]
     (d/db conn))
```

Its expected value is function + test + namespace + schema row count. No value
is fabricated here.

The committed publication clock script was inspected but not executed: it
creates a git worktree, while this assignment explicitly prohibited creating
one. No replacement timing claim is made. This slice adds only per-row
canonical encoding and SHA-256 work; no full-program scan or second cache.

## Changed paths

Implementation commit `f27b96c19`:

- `resources/seon/schemas/seon.ns.edn`
- `resources/seon/schemas/seon.schema.edn`
- `resources/seon/schemas/seon.test.edn`
- `src/seon/fn.clj`
- `src/seon/program.cljc`
- `src/seon/test.clj`
- `test/seon/fn_test.clj`

Shared-file commit `6d84f27fa`, explicitly attributed to this lane:

- `resources/seon/schemas/seon.program.edn`
- `resources/seon/schemas/seon.fn.edn`
- `src/seon/schema.clj`

Implementation delta in `f27b96c19`: 146 insertions, 33 deletions across seven
files. Foreign dirty files and all untracked files were preserved.

## Proof boundary

Proved: namespace/function/test index constructors, schema constructor,
reader-stage canonicalization, agent-form reanalysis, stored B4 reader,
deterministic 64-hex derivation, one-token sensitivity, and loadability.

Not proved in this lane: a successful canonical fixture transaction, fresh
publication population count, reset boot, live adoption, browser paint, cold
platform gate, or publication clock. The orchestrator owns RESET batch 1,
canonical-base refresh and platform proof.
