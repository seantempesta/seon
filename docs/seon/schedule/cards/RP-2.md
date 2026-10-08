---
title: "Card RP-2: Analysis per namespace row"
type: reference
status: active
created: 2026-10-08
---

# RP-2 Analysis per namespace row

Ledger row RP-2 (alias S5-04a, group B′: `src/seon/fn.clj` only). Design: `redo/pass-and-recording.md` §2 ANALYZE
("each under its captured resolver row") and "Source fidelity under a retarget". Lane: Sonnet. Cited at HEAD
`22910a03a` (`src/seon/fn.clj` carries led-nk9's uncommitted edits; its line numbers below are HEAD's).

## What changes, in one paragraph

`seon.fn/runtime-analysis-batch` (`src/seon/fn.clj:945`) writes one synthetic `ns` form per run of consecutive
requests in one namespace: `(partition-by :namespace-name requests)` (`:1021`), and each run's resolver context is the
**first** request's `:seon.fn/namespace-row` (`:965-975`). Two consecutive forms of one namespace written under
different resolver rows (an alias required or retargeted between them) are therefore analyzed under the first form's
aliases. The partition key becomes the pair `[namespace-name namespace-row]`, so a request carrying a different row
starts its own `ns` form. Requests that carry no row (`nil`) still group by name, so the existing behavior "an alias an
earlier form requires resolves in later forms" (requests read the stored row) is unchanged.

## Files

- `src/seon/fn.clj`
- `test/seon/fn_test.clj`

## Functions

- CHANGE `seon.fn/runtime-analysis-batch` (private, `:945`), contract unchanged
  (`[:function [:=> [:cat :seon.db/database-value [:vector :map]] :map] [:=> [:cat :seon.db/database-value [:vector :map] [:maybe [:map-of :symbol :map]]] :map]]`):
  replace `(partition-by :namespace-name requests)` with
  `(partition-by (juxt :namespace-name :seon.fn/namespace-row) requests)`. Nothing else in the reduce changes: each
  group already reads `(first group)`'s row (`:966-975`) and appends its own `ns` form text before its forms, so spans
  stay exact. The `:namespaces` map (`:1015-1018`) keeps the first row's `ns` text per name (`#(or % …)`); that is
  what `:seon.fn/runtime-namespaces` holds today and its consumers do not change here (RP-8 decides the digest row).
- Update the docstring sentence "One namespace form per run of consecutive requests in one namespace" (the comment at
  `:1000-1002`) to "per run of consecutive requests with one namespace row".

Cost of the equality in `partition-by`: rows are small maps compared structurally once per adjacent pair, O(forms ×
row size).

## Data (request, as the pass's ANALYZE stage sends it after RP-3)

```edn
[{:namespace-name rp2.a :form-source "(defn j [] (s/join [1 2]))"
  :seon.fn/namespace-row {:seon.ns/name rp2.a
                          :seon.ns/aliases [{:seon.ns.alias/local s :seon.ns.alias/target-ns clojure.string}]}}
 {:namespace-name rp2.a :form-source "(defn u [] (s/union #{1} #{2}))"
  :seon.fn/namespace-row {:seon.ns/name rp2.a
                          :seon.ns/aliases [{:seon.ns.alias/local s :seon.ns.alias/target-ns clojure.set}]}}]
```

## Proof

`seon.fn-test/a-batch-analyzes-each-request-under-its-own-namespace-row` (new), in `test/seon/fn_test.clj`, with
`test-support/with-database` as `an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch`
(`test/seon/fn_test.clj:80`) does:

```clojure
(test-support/with-database
 (fn [connection]
   (test-support/transacted! connection {:tx-data [(test-support/namespace-entity @connection 'rp2.a)]})
   (let [row (fn [target] {:seon.ns/name 'rp2.a
                           :seon.ns/aliases [{:seon.ns.alias/local 's :seon.ns.alias/target-ns target}]})
         {analysis :seon.fn/analysis spans :seon.fn/source-spans}
         (#'seon.fn/runtime-analysis-batch
          @connection
          [{:namespace-name 'rp2.a :form-source "(defn j [] (s/join [1 2]))" :seon.fn/namespace-row (row 'clojure.string)}
           {:namespace-name 'rp2.a :form-source "(defn u [] (s/union #{1} #{2}))" :seon.fn/namespace-row (row 'clojure.set)}])
         targets (fn [[first-row last-row]]
                   (into #{} (comp (filter #(<= first-row (:seon.fn.analyzer/row %) last-row))
                                   (map (juxt :seon.fn.analyzer/to :seon.fn.analyzer/name)))
                         (:seon.fn.analyzer/var-usages analysis)))]
     (is (contains? (targets (first spans)) '[clojure.string join]))
     (is (contains? (targets (second spans)) '[clojure.set union])))))
```

Usages carry `:seon.fn.analyzer/row` (`src/seon/fn/analyzer.clj:195`). On HEAD the second assertion fails (both forms read
`clojure.string`), which is what makes it a proof.

## Reaching tests (run in `--test`)

`seon.fn-test` (it holds `an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch` and
`defining-forms-share-one-form-local-kondo-batch` `:140`), `seon.turn.batch-test` (`analyze-form`,
`src/seon/turn/batch.clj:238`, calls the batch with one request), `seon.repl-differential-p7-test`.

## Cost

Expected from the work: one extra `ns` form (a few hundred bytes of text) per row change inside a batch, which a
retarget makes rare; clj-kondo's time follows the text. Measured parent (redesign §6): one call 11.5 / 13.9 / 16.3 ms at
N = 1 / 5 / 20 defining forms. No change expected for batches with one row per namespace.

## Deleted

Nothing.

## Commit

`bin/lane commit <lane> -m "RP-2: the analysis batch writes one ns form per namespace row" --test seon.fn-test --test seon.turn.batch-test --test seon.repl-differential-p7-test src/seon/fn.clj test/seon/fn_test.clj`

## Dependencies

None in the ledger. `src/seon/fn.clj` is held by led-nk9 (uncommitted): wait for its release.

## Reset needs

None.
