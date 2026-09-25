---
type: landing
lane: fix-writer-digest-opus
status: landed-unverified
date: 2026-09-25
issue: docs/seon/issues/the-writer-admits-a-definition-digest-its-row-does-not-produce.md
authority: docs/prds/agent-platform/landing/lane-fix-harness-fallout-2026-09-25.md (N4)
---

# Lane fix-writer-digest (2026-09-25)

The final-report validator `seon.db/write-report-error` now recomputes the definition digest of every function and
test row a report changes. `write-digest-error` reads the entity back as the row that wrote it
(`declaration-value`) and calls `seon.program/definition-digest` once per row, with the namespace's resolver context
from `seon.fn/stored-namespace-context`. A mismatch refuses by name.

## Broken or unproven first

- **Nothing here has run** (load rule). clj-kondo reports no new findings in the touched regions. The one error it
  reports, `db.clj:673 parser.type/->Variable`, predates this change and comes from the stale dependency cache. The
  probes were read-only JVM forms in the throwaway namespace `fix-writer-digest-scratch` on default (pid 35446). They
  used a textual copy of the committed functions evaluated there; db.clj was never reloaded on default. Root's runs
  below are the first proof of the installed writer.
- **Known reds.** These tests change a digested attribute of a program row but keep the stored digest, which is the
  defect class itself. They are now refused at the write. Each needs to write the row its canonical writer produces
  (`test-support/program-fn-row database sym source`, or the produced digest alongside the edit):
  - **Datom edits to `:seon.fn/doc`:**
    - `seon.db-test` at :723 and :756 (the read-evidence tests) and at :2418
      (`a-report-touching-no-function-reuses-the-committed-arity-comparison`)
    - `seon.test.admission-digest-test/an-admission-confirms-the-tested-digest-without-deriving-the-program` (:25, :38)
    - `seon.test.reach-test/program-digest-uses-the-databases-program-shapes` (:14–15)
    - `seon.test-test` (:268)
    - `seon.issue-test` (:368)
  - **Other datom edits:**
    - `seon.test.selection-test` :215 (`:seon.fn/spec`)
    - `my.program-test` :118 (`:seon.test/reach`)
    - `seon.test-provenance-test` :104 (`:seon.test/error-count` on the declaration)
    - `seon.test-reaching-test` :205 (`:seon.test/fixture-observation`)
  - **Rows associated after `program-fn-row`:**
    - with a new `:seon.fn/spec`:
      - `seon.db-test/arity-components-and-callers-are-checked-in-the-final-state` (:1943)
      - `seon.program-test/positional-and-map-entry-contracts-have-distinct-addresses` (:463)
      - `seon.program-test/function-contract-redefinition-replaces-component-facts-exactly` (:742, :750)
      - `seon.schema-usage-guard-test/generic-schema-deletion-refuses-committed-dependencies` (:232)
      - the `function-row` helper in `seon.call-preparation-test` (:179)
    - with a new `:seon.fn/source`: `seon.instrument-test/a-sovereign-sci-fork-acquires-its-own-recorder` (:404)
  - **Probably unaffected:** `a-write-seeds-its-final-values-arity-base` (db_test :1915) and
    `prepared-source-counts-…` (fn_test :2245) associate values equal to the analyzed row's. `sci/eval_test` :208
    and :256 go through `install-row!`, not the writer.
  - The list comes from a scripted search, so it may be incomplete. Any other red naming member
    `:seon.program/definition-digest` belongs to the same class.
- **Scope is function and test rows only.** The namespace and schema families do not reproduce from their final
  value:
  - `my.agents.root` gains `:seon.ns/steward` after its digest is computed.
  - 37 of 40 sampled schema rows gain `:seon.schema/shape` and `:seon.schema/generatable?` after theirs.

  Checking those families would refuse production writes, so this lane does not. That the digest omits attributes
  added after it is its own defect, filed out of scope below.
- **A namespace-only change is not checked.** If a report changes a namespace's requires, aliases, refers or
  imports but not its functions, those functions' stored digests go stale without a refusal. They refuse on their
  next write. Every indexer write re-analyzes the whole file, so production is unaffected.
- **Cross-namespace private call.** `seon.db` resolves the private `seon.fn/stored-namespace-context` through
  `requiring-resolve` under a `defonce` delay, the load-cycle pattern db.clj already uses. The owner of fn.clj
  should make it public.
- **The error is not a named schema.** The refusal is a flat `:seon.error/base` diagnostic with
  `:seon.db/transaction-refused`, the precedent the neighbouring arity refusal in the same validator sets. Naming it
  (for example `:seon.db.write/digest-refusal`) needs `resources/seon/schemas/seon.db.write.edn`, which this lane
  does not hold.
- RESET NEEDED: none.

## Evidence (read-only, default pid 35446)

- **The reconstruction is exact.** For all 7,022 live `:seon.fn/sym` and `:seon.test/sym` rows, the digest of
  `declaration-value` with the stored namespace context equals the stored digest. There were 0 mismatches and 0
  rows without a namespace.
- **The copy of the committed functions behaves as intended** (`d/with` on default's value, nothing written):
  - A canonical `program-fn-row` for `sample.digestprobe/target` with its `namespace-row` returns nil.
  - A `:seon.fn/calls` datom edit, an excluded attribute, returns nil.
  - The same row with its source changed refuses. The refusal carries member `:seon.program/definition-digest`,
    expected `219cc227…` (the produced digest), offending `c2c8dc10…` (the stored one) and entity
    `{:seon.fn/sym sample.digestprobe/target}`.
- **Cost, armed:**

  | report | time | per row |
  |---|---:|---:|
  | one function row | 0.32 ms | |
  | three probe reports | 2.0 ms | |
  | all 7,022 function and test rows | 1,752 ms | 249 µs |

  The first version, without a per-namespace context memo, took 2,052 ms for the full report (292 µs per row).
  About 80% of the time is the armed `seon.schema/canonical-value-string` (944,048 calls, 1,032 ms) inside
  `definition-digest` (7,023 calls, 786 ms).
- **Where the cost lands.** Only rows whose datoms are in `:tx-data` pay, so cost follows changed rows. Only a
  report that writes every program row, which is a from-zero index, crosses one second. That case adds about 1.75 s
  armed. Root: if that is unacceptable, the smaller next step is to check only entities whose `:tx-data` touches an
  attribute the digest hashes.

## Changes

| commit | files | + / − | what |
|---|---|---:|---|
| 79b6d9abd | `src/seon/db.clj` | +61 / −0 | `declaration-value`, `write-digest-error` and one call in `write-report-error` |
| 79b6d9abd | `test/seon/db_test.clj` | +27 / −0 | `a-program-row-keeps-only-the-definition-digest-it-produces` |

Net: +88 lines of source and tests.

## Root runs (serially, in a quiet window, after adopting 79b6d9abd)

```
bin/test-check default --test seon.db-test/a-program-row-keeps-only-the-definition-digest-it-produces
bin/test-check default --ns seon.db-test
bin/test-check default --ns seon.test.admission-digest-test
bin/test-check default --ns seon.test.reach-test
bin/test-check default --ns seon.program-test
bin/test-check default --ns seon.instrument-test
bin/test-check default --ns seon.call-preparation-test
bin/test-check default --ns seon.schema-usage-guard-test
bin/test-check default --ns seon.fn-test
```

Then run the platform tier, to show boot and reindex writes are admitted and to time the full index write.

## Out of scope, not filed (no issue path is owned here; root files it)

- **Namespace and schema definition digests omit attributes written after them.** `:seon.ns/steward`, and
  `:seon.schema/shape` with `:seon.schema/generatable?`, are stored on rows whose digest was computed before they
  were added. `definition-digest` does not list them as excluded, so the final value does not produce the stored
  digest. Probe: `my.agents.root` matches only without `:seon.ns/steward`. 37 of 40 sampled schema rows match only
  without shape and generatable?; the other 3 matched neither variant tried. Search: no existing issue names this.
- **`seon.fn/stored-namespace-context` should be public.** It is the resolver-context owner that `seon.db` now reads.
