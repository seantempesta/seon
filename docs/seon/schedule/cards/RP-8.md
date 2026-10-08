---
title: "Card RP-8: An alias retarget keeps both calls right and notes the reload change"
type: reference
status: active
created: 2026-10-08
---

# RP-8 An alias retarget keeps both calls right and notes the reload change

Ledger row RP-8 (alias S5-04c, Astra D9; group B). Design: `redo/pass-and-recording.md` §2 "Source fidelity under a
retarget". Lane: Sonnet. Cited at HEAD `e7f120672`. **Q-R1 is answered:** reload reads the stored source under the
retargeted alias, as Clojure does; calling the earlier definition may fail, and merge refuses the broken
definition (owner-rulings §1; agent-loop.md §3.3). The retarget finding warns the agent before reload.

## What was measured (default, agent `cards-recording`, 2026-10-08)

This historical probe predates FK-SCI. The proof below explicitly unaliases before retargeting, as Clojure requires.
```clojure
(ns rp3probe.a (:require [clojure.string :as s]))
(defn g "first" [] (s/join [1]))   ; value #'rp3probe.a/g, reply diagnostic: "Not admitted to the program … The stored
                                   ; definition digest of [:seon.fn/sym rp3probe.a/g] is not the one its definition produces"
(require '[clojure.set :as s])     ; the alias retarget, later in the same eval request
```
`g`'s row digest was computed under the row its form ran with (`analyzed-entities` digests with the request's
`:seon.fn/namespace-row`, `src/seon/turn/batch.clj:91-94`), while the writer's check (`write-digest-error`,
`src/seon/db.clj`, through `seon.db/definition-digest-of` `:5234`) recomputes it with the namespace row the same
transaction stores, which is the final one. They differ, so the writer refuses the program.

## The change
- **Digest under the final row.** `analyzed-entities` digests every row with the namespace row the recording will
  store for that namespace: the final resolver row, `(seon.sci.eval/namespace-row ctx ns)` (RP-3) taken once per
  namespace after the last form. The writer then agrees.
- **Edges under the captured row** (already true after RP-3/RP-5: each request is analyzed under the row captured at
  its write, so `g`'s `:seon.fn/calls` holds `clojure.string/join`).
- **Finding.** A definition whose **used** aliases resolve differently in the captured row and the final row gets
  `{:seon.program.finding/type :seon.program.finding/alias-retargeted :seon.batch/sym g :seon.ns.alias/local s}`
  (one per alias). "Used" = the aliases of the definition's var-usages in its analysis
  (`:seon.fn.analyzer/alias` on usage entries, `src/seon/fn/analyzer.clj:173`), so the check is O(aliases used).

## Files
- `src/seon/turn/batch.clj` (`analyzed-entities`, `derive-form`)
- `src/seon/batch.clj` (`derive-all` passes the final rows)
- `resources/seon/schemas/seon.program.finding.edn` (`:alias-retargeted`)
- `test/seon/repl_differential_p8_test.clj`

## Functions
- CHANGE `seon.turn.batch/analyzed-entities` (`:66`): arity `[db requests analysis final-rows]`, `final-rows` a
  `[:map-of :symbol :seon.batch/resolver]`; the digest call (`:93-94`) uses
  `(get final-rows (:namespace-name (nth requests i)) ns-row)`. Contract:
  `[:=> [:cat :seon.db/database-value [:vector :map] :map [:map-of :symbol :map]] :map]`.
- ADD `seon.turn.batch/final-rows`
  `{:malli/schema [:=> [:cat :seon.sci.eval/ctx [:set :symbol]] [:map-of :symbol :seon.batch/resolver]]}`
  `[ctx namespaces]` = `(into {} (map (juxt identity #(eval/namespace-row ctx %))) namespaces)`.
- ADD `seon.turn.batch/retargeted-aliases`
  `{:malli/schema [:=> [:cat :map :seon.batch/resolver :seon.batch/resolver [:tuple int? int?]] [:set :symbol]]}`
  `[analysis captured final [first-row last-row]]`: the aliases of usages within the rows whose target namespace
  differs between `captured` and `final` (`:seon.ns/aliases` as local → target maps).
- CHANGE `seon.turn.batch/derive-form`: for each joined row, add one `:alias-retargeted` finding per retargeted alias to
  the change's `:seon.batch/findings` (RP-6d's key).
- CHANGE `seon.batch/derive-all` (RP-6a): compute `final-rows` once for the namespaces of all requests, after the last
  form, and pass them to `analyze-all`.

## Proof
`seon.repl-differential-p8-test/an-alias-retarget-keeps-both-calls-and-notes-the-reload-change` (new): evaluate
`"(ns rp8.a (:require [clojure.string :as s])) (defn g [] (s/join [1 2])) (ns-unalias *ns* 's) (require '[clojure.set :as s]) (defn u [] (s/union #{1} #{2})) [(g) (u)]"`;
assert the last value is `["12" #{1 2}]`, the `g` envelope carries no diagnostic and one `:alias-retargeted` finding
naming `s`, `u` none, and both rows are stored (pull each `[:seon.fn/sym …]`), with `g`'s `:seon.fn/calls` containing
`clojure.string/join`. Also prove the answered reload rule: reload the stored definition under the final alias,
then call `g`. The call fails because `clojure.set/join` requires two or three arguments and this call supplies
one; merge refuses the broken definition. Name the reaching reload and merge tests before launching this slice.

## Reaching tests
`seon.repl-differential-p8-test`, `seon.turn.batch-test`, `seon.turn-test`, `seon.fn-test`.

## Cost
One `namespace-row` per namespace touched per eval request; the alias comparison follows the aliases used.

## Deleted
The digest refusal for this case.

## Commit
`bin/lane commit <lane> -m "RP-8: digests under the final namespace row; a retargeted alias is a finding" --test seon.repl-differential-p8-test --test seon.turn.batch-test --test seon.turn-test src/seon/turn/batch.clj src/seon/batch.clj resources/seon/schemas/seon.program.finding.edn test/seon/repl_differential_p8_test.clj`

## Dependencies
RP-6a (and RP-3, RP-5 through it). Group B.

## Reset needs
None.
