---
type: landing
status: committed; awaiting root tests
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

- **`test/seon/sci/entry_gate_test.clj:129` will go red. That file is held by impl-repl-m1.**
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

## Root runs (serially)

```
bin/test-check default --ns seon.fn-test
bin/test-check default --ns seon.sci.entry-gate-test      # expect :129 red until its owner moves point-free to the missing-contract list
bin/test-check default --ns seon.sci.restoration-test
bin/test-check default --test seon.fn-test/computed-and-point-free-defs-are-declarations-their-referrers-keep
bin/test-check default --ns seon.render.walk-graph-test
bin/test-check default --ns seon.fn.publication-test
```

Adopting the new rows means default indexes 403 new declarations; publication
then reads them. No reset is needed.
