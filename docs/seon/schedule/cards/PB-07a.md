---
title: "PB-07a: an empty error window derives nothing"
type: reference
status: ready
created: 2026-10-08
---

# PB-07a: An empty error window derives nothing

Ledger row PB-07a (group F). Task `seon.error/fault-window-derives-the-error-selector-when-no-error-occurred`
(`resources/seon/tasks/seon.error.edn`). Source: `defn-why/defn-why.md` finding 3. Model: Sonnet.

`seon.error/occurred-since` (`src/seon/error.clj:1547`) builds `observation-selector` whenever its query answers a
vector, even an empty one (`:1573-1574`: `(when-not (map? entities) (observation-selector …))`). The selector is held
per projection (`seon.schema.internal/projection-cache-value`, `src/seon/schema.clj:432`), and every contracted `defn` makes a
new projection, so the first pass after it rederives the selector over the whole registry: 12.8 MB, 13.5 ms of the
94 MB pass after a `defn`, with no error in the window. The settlement calls it every pass
(`seon.turn` `:3493`).

## Files

- `src/seon/error.clj`
- `test/seon/error/branch_test.clj`

## Function changed

`seon.error/occurred-since` `[database since]`, contract unchanged
(`[:=> [:cat :seon.db/database-value :seon.db/basis-t] [:or [:vector …] :seon.db/error-result]]`):

```clojure
(cond
  (map? entities) entities          ; a read refusal, returned whole
  (empty? entities) []              ; nothing in the window: no selector, no pull
  :else (let [selector (observation-selector (db/carried-projection database))] (reduce … as today)))
```

The `:seon/tasks` metadata of `occurred-since` names the task. Holding the selector by schema forms (so a
contract-only change reuses it even when the window is not empty) is PB-07b.

## Proof

New `seon.error.branch-test/an-empty-error-window-derives-no-selector`, beside the existing window test
(`test/seon/error/branch_test.clj:35`), tagged `^{:seon/tasks #{'seon.error/fault-window-derives-the-error-selector-when-no-error-occurred}}`:

```clojure
(deftest ^{:seon/tasks #{'seon.error/fault-window-derives-the-error-selector-when-no-error-occurred}}
  an-empty-error-window-derives-no-selector
  (test-support/with-database
   (fn [a]
     ;; a contracted function write gives this value a projection no earlier read derived from
     (test-support/transacted! a [(test-support/namespace-entity (db/db a) 'seon.error.branch-test.fresh)
                                  (test-support/program-fn-entity (db/db a) 'seon.error.branch-test.fresh/f
                                                                  "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)")])
     (let [database (db/db a)
           compiled (:seon.schema.projection/compiled (db/carried-projection database))]
       (is (= [] (error/occurred-since database (db/basis-t database))))
       (is (not (clojure.core.cache/has? compiled :seon.error/observation-selector))
           "an empty window leaves the selector underived")))))
```

Check first that `program-fn-entity` accepts a contracted source (read its docstring at `test/seon/test_support.clj`);
if it does not, any declaration write the file's other tests use is enough, as long as `compiled` is a holder no
earlier `occurred-since` filled. The existing `an-error-is-seen-on-its-own-branch-once-per-window` stays green (it
covers the non-empty window).

`--test seon.error.branch-test --test seon.fault-test`

## Cost

Expected: an empty window is one query; measured parent 12.8 MB, 13.5 ms on the pass after a `defn` (defn-why).
Measure the pass after a `defn` with `seon.pass-probe/window` on parent and change; report both.

## Deleted

Nothing.

## Commit

`bin/lane commit <lane> -m "PB-07a: an empty error window derives no selector" --test seon.error.branch-test --test seon.fault-test src/seon/error.clj test/seon/error/branch_test.clj`

## Dependencies

None at `9d69253e1` (fault-data-edn has released `error.clj`). Lands before PB-07b and UL-5 (same file).
