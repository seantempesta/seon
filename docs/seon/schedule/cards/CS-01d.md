---
title: "Card CS-01d: The block shows broken tests"
type: reference
status: active
created: 2026-10-08
---

# CS-01d: The block shows broken tests

Depends on: CS-01b (`proof-status`, `:seon.context/proof`), F4b (reaching tests run on the branch). Group D. Model:
Sonnet.

## Files

- `src/seon/context.clj`
- `resources/seon/schemas/seon.context.edn`
- `test/seon/context_test.clj`

## Functions

```clojure
(defn broken-tests
  "Tests reaching the agent's change, not its proofs, that are red on its branch, with the pending count; a test already red at the merge base is marked."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value [:set :seon.test/sym]]
                  [:or :nil :seon.context/broken-tests :seon.error/value]]}
  [database base proofs] ...)
```
- `database`: the agent's branch value. `base`: the merge base, read as the task home's current value (`home` in
  `current-state`; the agent branched from it). `proofs`: CS-01b's set.
- Reaching set: `seon.test/reaching` (`test.clj:1091`) with
  `{:seon.db/db database :seon.test/changed changed}`, where `changed` is the vector of `[:seon.fn/sym s]` identities of
  this branch's definitions whose `:seon.program/definition-digest` differs from `base`'s (one `db/q` per value over
  `[:find ?s ?d :where [?e :seon.fn/sym ?s] [?e :seon.program/definition-digest ?d]]`, compared as maps). Minus `proofs`.
- Status: CS-01b's `proof-status` over one `reach-digests` + one `latest-results` batch on `database`. `:red` →
  listed as `:seon.context/proof` (same data as a red proof); `:pending` → counted.
- Marked at base: for each red one, `(seon.test/verified? base sym)` is not `true` and `latest-results` on `base`
  shows it red → `:seon.context/red-at-base true`.
- Nothing reaching and nothing pending → nil (section absent).

Change `current-state`: `(assoc :seon.context/broken-tests …)` when non-nil.

## Schemas

```clojure
:red-at-base :boolean
:broken-test [:merge :seon.context/proof [:map [:seon.context/red-at-base {:optional true} :seon.context/red-at-base]]]
:broken-tests [:map [:seon.context/broken [:vector :seon.context/broken-test]] [:seon.context/pending-count [:int {:min 0}]]]
```
Add `[:seon.context/broken-tests {:optional true} :seon.context/broken-tests]` to `:current-state` and the view.

## Proofs

`seon.context-test/a-test-the-change-broke-shows-as-a-blocker`: F4b's setup (a non-proof test reaching `tq.f4/area`
broken by the agent's pass); read the block through `seon.mcp/call`; assert `:seon.context/broken` holds
`tq.f4-test/perimeter-uses-area` with status `:red` and no `:seon.context/red-at-base`.

`seon.context-test/a-test-red-at-the-merge-base-is-marked-not-blocking`: the reaching test is made red on the home
first (root defines it red and runs it there), then the agent's change; assert the entry has
`:seon.context/red-at-base true`.

`--test seon.context-test seon.cluster.turn-test`.

## Cost

One reaching selection over the branch's differing definitions, one reach-digest batch and one latest-results batch
on each of two values: proportional to the change and the tests it reaches. The two definition-digest queries are
proportional to the program's functions: measure them in `seon.pass-probe/window`; over 1 s is a finding before it
lands, and the fix is reading the change from F4b's last reaching request instead.

## Commit

`bin/lane commit cs01d -m "CS-01d: the block shows tests the change broke" --test seon.context-test seon.cluster.turn-test src/seon/context.clj resources/seon/schemas/seon.context.edn test/seon/context_test.clj`
