---
type: landing
status: re-landed with the analyzer identity fix; awaiting root tests and adoption
lane: impl-index-point-free-defs-opus
created: 2026-09-25
---

# Lane impl-index-point-free-defs (P2-A): every def clj-kondo reports is a declaration

`seon.fn/var-row` turns clj-kondo `var-definitions` into program rows. Until now
`function-definition?` admitted only entries with arglists, literal defs and
`defmulti`. A computed or point-free `def`/`defonce` got no row, while its
referrers kept `:seon.fn/references` to it. The fix widens that one predicate.
The parser's own `:defined-by->lint-as` decides, so no second classifier is added.

## Broken or changed first

- **The first landing (1a7029d81) broke boot on the existing store; root reverted it (8fa430cc6).**
  The refusal was `Nothing found for entity id [:seon.fn/sym seon.cluster/*boot-progress!*]`.
  - It reproduced on `d/with` against default: `index!`'s caller-findings step
    reconciles only the lint rows of an unchanged caller file (`cluster.clj`).
    The new analysis attributes `unused-private-var` to the value def, whose row
    was never derived.
  - Cause: `seon.cluster/loaded-analyzer-digest` took the loaded analyzer's
    identity from the prior head's rows. A boot on changed analyzer files
    therefore compared the old analyzer with itself and published incrementally.
  - Issue: `docs/seon/issues/an-analyzer-change-publishes-incrementally-against-the-prior-analyzer.md`.
  - Root ruled option 2: the loaded analyzer's identity comes from what the JVM
    loaded. That fix lands first, then P2-A is re-landed as one slice.
- **The entry-gate move is re-landed here (was 43d3953ee).**
  `(def point-free (comp inc inc))` used to refuse as `:seon.sci.eval/unindexed`.
  It now matches its row by provenance and refuses as
  `:seon.sci.eval/missing-declaration :seon.fn/spec`, exactly like
  `(def nested (fn [x] x))` at :96. The source moves from the unindexed list to
  the missing-contract list. `(make generated)`, a macro-generated defn with no
  `:lint-as`, stays unindexed because clj-kondo reports no var for it.
- **A point-free function def cannot declare a contract.**
  `seon.program/contract-facts` finds no source arities. Filed as
  `docs/seon/issues/a-point-free-function-def-cannot-declare-a-contract.md`.
- **78 public computed defs now reach the function docstring and contract detectors.**
  `seon.issue.detect/declarations` excludes only `:seon.fn/constant?`. Filed as
  `docs/seon/issues/public-value-defs-join-the-function-docstring-and-contract-detectors.md`.
- **Macro-generated defns are indexed only when the macro is `:lint-as` a def form.**
  - With `:lint-as {m clojure.core/def}`, clj-kondo reports the var with
    `:defined-by m` and `:defined-by->lint-as clojure.core/def`, and it becomes a
    row (parser-proves-the-spec T8).
  - Without `:lint-as`, clj-kondo reports no var. The referrer gets a blocking
    `:unresolved-symbol`, so indexing refuses loudly; the row is never silently
    deleted.
  - The repo's only `:lint-as` entry, `def-catch-all` for a test macro, defines
    no var today.

## Analyzer identity (src/seon/cluster.clj)

- **The loaded code decides the analyzer's identity** (`loaded-analyzer-digest`):
  - after an adoption in this process, the commit `sci.eval/loaded-identity` names;
  - before any adoption, the published head read through the bytes launch loaded.
    `launched-source-digests` is a defonce of `source/path-digests` over every
    loaded first-party file; it took 9 ms for 103 files.
  - A reached declaration whose file launch loaded as other bytes contributes those
    bytes' digest (`launched-analyzer`). That digest matches no seal, so the
    publication reanalyzes every file.
  - After a failed mid-reload adoption, the digest also matches no seal.
- **Exactly once.** The `:all` publication caused by launch bytes seals
  `published-analyzer-digest` over the rows it analyzed, but only when its inputs
  are those launched bytes. The adopted commit then names the same digest, so the
  next publication stays incremental.
- **The instance/cluster-entity lookup is gone.** It read an adopted commit that
  could predate this JVM.
- **The digest scheme is unchanged.** On default (pid 76020), `analyzer-digest` of
  the head gave `ed74b0a9…` under both the old and the new code.
  `published-analyzer-digest` over the head's pulled rows (5251) equals
  `analyzer-digest` of the head (2.8 ms).
- **Probes** (scratch copy `tmp/impl-index-point-free-defs/identity.clj`, head of
  pid 84287, all returns validated against their declared schemas):
  - launch bytes equal to the head, or a non-analyzer file (`cluster.clj`)
    changed: the digest equals the head's, so the publication stays incremental;
  - `src/seon/fn.clj` loaded as other bytes: a different digest, so it reanalyzes
    everything, and the returned files name it;
  - `loaded-analyzer-digest`: 17–95 ms.
- **The analyzer's files** (reach from `seon.fn/analyze-rows`, 353 declarations):
  `db.clj`, `error/refusal.clj`, `fn.clj`, `fn/analyzer.clj`, `fn/signature.cljc`,
  `fs.clj`, `id.clj`, `program.cljc`, `schema.clj`, `schema/datahike.clj`,
  `schema/edn.clj` and `schema/internal.cljc`.
- **One full reanalysis costs 10.8 s** (`seon.fn/analyze-rows` over `src` and
  `test`, 8877 rows, 3.9 GB allocated, while a root test run was executing).
  - This is over 10 s. The armed functions responsible are
    `seon.fn/analyzed-files` → `seon.fn/analyzed-artifacts` (10.8 s inclusive),
    `seon.fn.analyzer/analyze` (6.3 s) and `seon.schema/call-with-projection` (7.9 s,
    shared with the concurrent test run).
  - Existing issue: `docs/seon/issues/a-full-reanalysis-publication-takes-twenty-five-seconds.md`.
  - An analyzer change pays this once.
- **Size:** +105/−30 in `cluster.clj`, over the ~60 target. The reach helper
  (`analyzer-reach`) is shared by the stored and the published digest. The
  launch capture and the seal-once branch are the added mechanism; nothing
  smaller distinguishes "loaded" from "published".

## Rule (src/seon/fn.clj `function-definition?`)

- **Is a row:** arglists present; a literal def; or `:defined-by->lint-as` is
  one of `defmulti`, `def` or `defonce`.
- **Stays out:** `declare`, and the class-bound names of `deftype`,
  `defrecord` and `defprotocol`. The exact-facts assertion in
  `host-binding-is-a-declaration-fact-not-a-caller-closure` holds this.
- **Shape of a value def's row:**
  - `:seon.fn/arglists "()"`, the shape a literal constant already has;
  - no `:seon.fn/constant?`, no `:seon.fn/spec` and no arities;
  - `:seon.fn/defined-by` set to `def` or `defonce`;
  - the initializer's calls and references as its edges.
- **Side effect:** calls inside `def`/`defonce` initializers now become
  `:seon.fn/calls`. This closes the "SELECTION GAP (def/defonce/memoize bodies)"
  row in the ownership ledger for defs.

## Evidence (REPL on default, pid 39484)

- **clj-kondo output** (`seon.fn.analyzer/analyze` on
  `tmp/impl-index-point-free-defs/probe.clj`, 9.9 ms):
  - `handle`, `point-free` and `with-doc` report `:defined-by clojure.core/def`;
    `registry` reports `clojure.core/defonce`.
  - None of them has `:arglist-strs` or `:fixed-arities`. `lit` has `:literal-def? true`.
  - Their usages are attributed with `:from-var`.
  - The macro-made var is absent, with the finding
    `Unresolved symbol: macro-made`.
- **Program-wide count** (`analyze` over `src` and `test`, 6.4 s; that is the
  cost of a whole-program analysis without cache, not something this change adds):
  - 7622 var definitions; 554 were dropped.
  - Of those, 403 are computed `def` (317) and `defonce` (86): 78 public, and
    none with `:malli/schema`.
  - The remaining 151 stay out: 138 `declare`, 9 `deftype`, 3 `defrecord`,
    1 `defprotocol`.
- **Rows before and after.** The "after" side is a renamed scratch copy
  `impl-pfd.fn`, which is **unarmed**, then removed.
  - Before: only `lit` and `user-of`. `user-of` referenced `pp.probe/handle` and
    `registry` and called `point-free` and `with-doc`, none of which had a row.
  - After: all six have rows. `handle` has `:calls #{clojure.string/join}`;
    `with-doc` keeps `:doc "Doc."`.
  - Validation: `(m/validate :seon.program/rows rows {:registry <default projection registry>})`
    returned true. The predicate's contract `[:=> [:cat :map] :boolean]` was
    checked with `m/validate`, and its inputs are the analyzer's entry maps.
- **Adoption round trip on default's database value**
  (`seon.fn/reconcile-tx`, then `d/with`, literal rows, then computed rows,
  retracting identities of the literal rows):
  - Old code: `handle` is **retracted**, and `user-of` still references
    `sample.value-def/handle`. That is the dangling reference.
  - New code: `handle` is kept with source `(def ^:private handle (assoc {} :a 1))`,
    `:calls [clojure.core/assoc]` and `constant?` retracted.
  - The stored source equals the row. `user-of` references are unchanged.
- **SCI definition authority.** `seon.fn/source-rows` for
  `"(def pfd-probe (comp inc inc))"`:
  - old code: `[]`, which `program-change` classifies as unmatched;
  - new code: one row with `:form-span [0 30]`, at the Var's position, so
    `program-change` matches it.
  - The armed old owner refused my first probe's vector `:seon.ns/requires`,
    while the unarmed copy accepted it. I then used a schema-valid namespace row
    (`m/explain :seon.ns/ns` returned nil).
- **Analysis cost.** `build-artifact` on `fn.clj`, `sci/eval.clj` and `db.clj`,
  4 interleaved pairs:
  - before: 1219/1384/1199/1391 ms;
  - after: 1233/1225/1213/1229 ms (unarmed copy, so this is an upper bound on any regression).
  - Row counts went from 194/220/231 to 196/230/252.
  - The kondo run is unchanged; only the predicate moved. The time over one
    second is `seon.schema/build-projection`, about 130 ms × 4 per artifact. It
    predates this change; see
    `docs/seon/issues/the-indexer-resolves-its-declaration-world-per-file-and-per-row.md`.
  - `reconcile-tx` plus the rows for the fixture: 229 ms before, 185 ms after.

## Changes

- `src/seon/fn.clj`: `function-definition?` gains a docstring, a contract and
  the def/defonce clause (+12/−3).
- `test/seon/fn_test.clj`: `computed-and-point-free-defs-are-declarations-their-referrers-keep`
  (+50). It covers `declare`, a private computed def, a point-free def,
  `defonce`, and literal-to-computed adoption through the writer on a branch
  of the live store (`test-support/with-database`, `transacted!`).
- Two issues and this note.
- Nothing was deleted. The one predicate is the only owner, and there was no
  duplicate path to delete.

## Regressions added in the re-landing (test/seon/fn_test.clj)

- `an-analyzer-launch-loaded-as-other-bytes-reanalyzes-everything` (identity fix).
- `a-caller-files-findings-name-its-value-defs-rows`: the `*boot-progress!*` class.
  A callee's signature change selects an unchanged caller file, and that file's
  reanalyzed `unused-private-var` finding reconciles against its computed def's
  row. It uses two sample-root analyses and one incremental `index!`, and its
  time is not yet measured against the 5 s default bound.

## Root runs (serially)

```
bin/test-check default --ns seon.fn-test
bin/test-check default --ns seon.cluster-test               # the-analyzer-digest-follows-the-analyzing-declarations-only
bin/test-check default --ns seon.sci.entry-gate-test      # expect :129 red until its owner moves point-free to the missing-contract list
bin/test-check default --ns seon.sci.restoration-test
bin/test-check default --test seon.fn-test/computed-and-point-free-defs-are-declarations-their-referrers-keep
bin/test-check default --ns seon.render.walk-graph-test
bin/test-check default --ns seon.fn.publication-test
```

Adoption: the first publication from the checkout after this lands reanalyzes
every file once. That covers the 403 new declarations, which take about 11 s.
The publication after it stays incremental. No reset is needed.
