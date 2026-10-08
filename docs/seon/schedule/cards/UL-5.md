---
title: "UL-5: contract problem text comes from Malli"
type: reference
status: ready
created: 2026-10-08
---

# UL-5: Contract problem text comes from Malli

Ledger row UL-5 (group F). Sources: `reinvention/reinvention-opus.md` item 4, `reinvention-astra.md` ranks 10 and 29.
Model: Sonnet. Split: **UL-5a** (this card, the expectation prose) and **UL-5b** (the collection member re-validation,
below, not yet carded).

`seon.error/explain-problem` (`src/seon/error.clj:856`) builds `:seon.error/expected-description` from
`schema-expectation` (`:805`) and `bound-text` (`:794`): a per-type noun table ("an integer at least 1", "a string of
length at least 1"). Malli already ships that table with bounds, `malli.error/default-errors` (`malli/error.cljc:44`,
bounds through `-pred-min-max-error-fn` `:10`), read by `me/error-message` (`:288`); `explain-problem` already calls
`me/error-message` for the `:fn` fix (`:880-881`).

## UL-5a files

- `src/seon/error.clj`
- `test/seon/refusal_grammar_test.clj`, `test/seon/db_test.clj` (`:1795-1803`)

## Functions

- Delete `bound-text` (`:794-803`) and `schema-expectation` (`:805-835`).
- ADD in their place:

```clojure
(defn- expectation
  "Malli's message for what `problem`'s schema expects; `:and`/`:or` join their children's."
  {:malli/schema [:=> [:cat :map] :string]}
  [problem]
  (let [check (m/deref-all (:schema problem))
        problem (-> problem (assoc :schema check) (dissoc :type))]
    (or (me/error-message problem {:unknown false})
        (case (m/type check)
          :and (str/join " and " (map #(expectation (assoc problem :schema %)) (m/children check)))
          :or (str/join " or " (map #(expectation (assoc problem :schema %)) (m/children check)))
          "should satisfy the declared schema"))))
```

  `(dissoc :type)` keeps Malli from answering the failure category (`::m/missing-key`) instead of the schema, which
  the existing test `reference-expectations-describe-the-schema-not-the-failure-category` guards.
- CHANGE `explain-problem`: `expected (expectation described-problem)` (was `schema-expectation`); the
  `:expected-description` cases keep their structure: `missing?` → `(str "the required key " (pr-str (last path)) ", which " expected)`,
  `member?` → `(str "a collection member that " expected)`, else `expected`. The `:fix` text: `(str "Supply a value that " expected …)`.
- CHANGE `problem-parts` (`:913-930`): `": expected "` becomes `": "` (Malli's message already says "should be …").
- Keep `value-description` (`:775`, the "got" half, which Malli has no counterpart for).

## Callers converted

The exact strings in `test/seon/refusal_grammar_test.clj:16-57` and `test/seon/db_test.clj:1801-1802` become Malli's
words. Compute each new expected string at the REPL with `(me/error-message {:schema (m/schema S) :value v})` for the
table's schemas (`[:int {:min 1}]` → Malli's `"should be at least 1"`, etc.) and paste those values; the test names
stay. Assert on data first: where a test can compare `:seon.error/expected` (the schema form) instead of the sentence,
do that and drop the sentence assertion.

## Proof

New `seon.error-test/contract-problems-read-malli-explanations`:

```clojure
(deftest contract-problems-read-malli-explanations
  (doseq [[schema value] [[[:int {:min 1}] 0] [[:string {:min 1}] ""] [[:map [:a :int]] {}] [:keyword "x"]]]
    (let [problem (first (:errors (m/explain schema value)))
          description (error/explain-problem {:seon.error/problem problem :seon.error/path (:in problem)
                                              :seon.error/argument "argument 0"})]
      (is (= (vec (:in problem)) (:seon.error/path description)))
      (is (str/includes? (:seon.error/expected-description description)
                         (me/error-message (dissoc problem :type)))))))
```

`--test seon.error-test --test seon.refusal-grammar-test --test seon.db-test --test seon.instrument-test`

## Cost

Expected: one `me/error-message` per problem (was the same plus the table walk); about −40 lines.

## UL-5b (not yet carded)

`collection-member-problem` (`:837-854`) re-validates every member of a collection to find the failing one. The
problem `seon.instrument` passes (`instrument.clj:528`) is one entry of Malli's `:errors`, which already carries the
member's own path for element failures; the scan matters only for a collection-level error (e.g. a list where a vector
was declared). UL-5b first lists, at the REPL, which producers hand `explain-problem` a collection-level problem with
a valid collection type; if none, it deletes the scan and the `member?` branch.

## Commit

`bin/lane commit <lane> -m "UL-5a: contract expectations are Malli's messages; schema-expectation and bound-text deleted" --test seon.error-test --test seon.refusal-grammar-test --test seon.db-test --test seon.instrument-test src/seon/error.clj test/seon/error_test.clj test/seon/refusal_grammar_test.clj test/seon/db_test.clj`

## Dependencies

PB-07a (same file, smaller) first. `test/seon/db_test.clj` is touched by led-cacheb's area: claim it when free. Lands
before MS-08 (which then does not polish deleted prose).
