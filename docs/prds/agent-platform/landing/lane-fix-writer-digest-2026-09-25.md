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

## Round 2 (root's P1–P4 from fix-digest-fixtures-opus and census e WDIGEST)

### Broken or unproven first

- **P2 is only half fixed. The producer patches below are not applied.** fn.clj is held by impl-task-ingestion-opus,
  and my row allows fn.clj and sci/eval.clj only after asking root. SCI-settled definitions are refused until root
  sequences the patches.
  - The cause is broader than the namespace context. After `source-rows` digests a row, SCI's `definition-rows`
    replaces `:seon.fn/spec` with `(pr-str (accretion/data-contract! …))`, adds `:seon.fn/workload`, and merges
    `program/test-markers`. All three are digested attributes, so the digest goes stale even when the contexts
    agree.
  - Read-only evidence of the context half: `sci.eval/base-ctx` gives `seon.effect-test` no clojure.test refers,
    while its stored namespace row has deftest, is and testing. `my.agents.root` and `seon.id` agree.
- **Nothing has run as a test** (load rule).
  - Probes: `d/with` on default's value (pid 35446), using a textual copy of each committed function evaluated in
    the throwaway namespace `fix-writer-digest-scratch`.
  - Default reloads committed db.clj: by the time of the P4 probe it already held P1's three-argument
    `write-digest-error`. Publication is therefore live for this file; root, please confirm.
- **`test-support/revised-row` and `revision` (fix-digest-fixtures) must use the owner.** They digest the row
  without `canonical-row`, so a stored row that carries evidence attributes digests differently from the writer.
  Patch (test_support.clj, not held here): in `revised-row`, digest with
  `(db/definition-digest-of (db/carried-projection database) row <stored namespace row>)`.
- **A second producer of the context remains.** The indexer's `var-row` (fn.clj:~776, :823) digests with the
  analyzer's `namespace-context` in its select-keys form. This equals `stored-namespace-context` for all 7,040 live
  rows, but it is still a second producer. Converting it needs the namespace row in `analysis-rows-by-file`, and
  that is the fn.clj owner's decision.
- RESET NEEDED: none.

### Fixes (one commit each)

| commit | item | + / − (src; test) | what |
|---|---|---:|---|
| 58d953b7e | **P1** evidence refused | +27 / −9; +20 | The check runs only when `:tx-data` changes an attribute the family owns, and it digests `program/canonical-row` of the final value. |
| 3c300e5d5 | **P4** schemaless crash | +5 / −2; +19 | It considers only the families the written database installed. Datahike refuses a datom read naming an attribute its schema lacks. |
| 9bd944d45 | **P3** seeds refused | +28 / −17; +17 | `declaration-value` rebuilds each cardinality-many attribute in its declared collection, through the writer's own `::normalize-many`. Ordered `:seon.test/seed` components come back as the vector the indexer digested. |
| 40dae3514 | **P2** (db half) | +19 / −4; +19 | Public `seon.db/definition-digest-of [projection row namespace-row]` is the one digest owner, and `write-digest-error` calls it. |

- **P1:** the owned attributes come from `seon.program/shapes-in`, the one ownership answer, which is also what
  `canonical-row` hands the digest. Result attributes (`pass-count`, `failures`, `reach`, the counts) are owned by
  no family.
- **P3:** `declaration-value` keeps its three-argument form for test_support and adds a projection arity for the
  writer.
- **Net round 2:** +79 source lines and +75 test lines, with four class regressions:
  - `evidence-on-a-declaration-is-not-its-definition`
  - `a-store-without-program-schema-admits-its-writes`
  - `ordered-seed-occurrences-keep-the-digest-their-indexer-produced`
  - `one-owner-digests-a-definition-with-its-stored-namespace`

### Evidence (read-only, default pid 35446)

- **P1:**
  - `[:db/add [:seon.test/sym seon.id-test/data-shape-and-explicit-length-determine-identity] :seon.test/pass-count 7]`
    returns nil.
  - An owned edit of the same row (`:seon.test/long`) still refuses with member `:seon.program/definition-digest`.
  - No family owns any of pass-count, reach, failures, fail-count or error-count.
- **All live rows still reproduce.** A report touching every datom of the 7,040 live function and test rows
  refuses nothing: 1,898 ms armed after P1, and 2,415 ms after P3 while other lanes were running.
  `canonical-row` of the read-back value reproduces 7,040 of 7,040 stored digests.
- **P4:** a raw memory Datahike database holding only `:seon.config.eval.result/blob-threshold` gives nil. The
  crash is Datahike's "Bad entity attribute :seon.fn/sym … not defined in current schema".
- **P3:**
  - The indexer (`seon.fn/rows` → `reconcile-tx`) writes `sample.seeded-test/seeded` with seeds
    `[{ordinal 0 …} {ordinal 1 …} {ordinal 2}]`. The stored value was read back as a set, and 79b6d9abd refused it.
  - After the fix it is admitted.
  - For the `program-row` deftest with two literal seeds, the loaded (pre-P3) writer refuses and the new one
    admits.
- **P2 (db half):**
  - `definition-digest-of` with the stored namespace row equals `program-fn-row`'s digest.
  - A row digested with a namespace widened by one clojure.test refer is refused.
  - The owner's own digest is admitted.
- **Cost:** one row 0.32 ms, as in round 1. A result-only write now does no digest work.

### Exact producer patches (root sequences; not applied)

`src/seon/fn.clj` `source-rows` (about :1491–1499). Replace the local digest with the owner. This is behaviour-
equivalent for the supplied namespace row:

```clojure
-        ;; The analysis `ns` form is widened by interpreter refers; identity
-        ;; uses the namespace's own facts, as the indexer does.
-        resolver-context (stored-namespace-context namespace-row)]
+        projection (db/carried-projection database)]
 ...
-           (map #(assoc % :seon.program/definition-digest
-                        (program/definition-digest % resolver-context)))
+           ;; The one digest owner: the namespace's own facts, never the
+           ;; analysis `ns` form widened by interpreter refers.
+           (map #(assoc % :seon.program/definition-digest
+                        (db/definition-digest-of projection % namespace-row)))
```

`src/seon/sci/eval.clj` `definition-rows` (:489–536). Digest the FINAL row, after spec, workload, test markers and
contract facts, against the namespace row the transaction leaves stored:

```clojure
   [ctx database projection before source namespace-row]
-  (let [source-rows
+  (let [digest-namespace (or (db/pull database '[*] [:seon.ns/name (:seon.ns/name namespace-row)])
+                             namespace-row)
+        digested #(assoc % :seon.program/definition-digest
+                         (db/definition-digest-of projection % digest-namespace))
+        source-rows
 ...
-         (if (:seon.fn/spec row)
+         (some-> (if (:seon.fn/spec row)
            (let [definition (edn/read-string (:seon.fn/spec row))]
              ...
               (set (keys (:seon.schema.projection/forms projection)))}))
-           row))))
+           row)
+                 digested)))))
```

Open question for root: which namespace a definition digests with when one turn writes an `ns` or `require`
context row and then a definition in the same settlement. The patch uses the namespace row stored before the
evaluation, and falls back to SCI's only for a namespace not yet stored. Three options:

1. **Simplest (recommended):** the settlement digests after merging its own context row, so `digest-namespace` is
   `(or context-row stored supplied)`. This costs one argument through `evaluate`. It gives up nothing.
2. The writer re-derives the digest before transacting. This needs a pre-transaction expansion of
   `:db.fn/call` output: large, and it breaks "derive at the producer".
3. Accept refusals for same-turn namespace changes. This loses REPL workflows.

### Root runs (round 2, after adopting 40dae3514)

```
bin/test-check default --test seon.db-test/evidence-on-a-declaration-is-not-its-definition
bin/test-check default --test seon.db-test/a-store-without-program-schema-admits-its-writes
bin/test-check default --test seon.db-test/ordered-seed-occurrences-keep-the-digest-their-indexer-produced
bin/test-check default --test seon.db-test/one-owner-digests-a-definition-with-its-stored-namespace
bin/test-check default --ns seon.db-test
bin/test-check default --ns seon.blob-test
bin/test-check default --ns seon.test-failure-facts-test
bin/test-check default --ns seon.test.admission-digest-test
bin/test-check default --ns seon.test.reach-test
bin/test-check default --ns seon.fn.seed-admission-test
```

Expected to stay red until the producer patches land: `seon.fn-test/fixture-observations-survive-…`,
`seon.test.one-request-test` (×3) and the `seon.turn-test` settlement members.

# Round 1

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
