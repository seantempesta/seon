---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, test, accretion, auto-check, isolation, dogfood]
---

# An agent's auto-check (and install-gate test bodies) commit their writes to the agent's own branch

Found by dogfood lane dogfood-author-opus through MCP only (branch `dogfood-author-opus-1`, default pid 19859,
started 16:02Z).

## Observed

I defined a function that writes one entity:

```clojure
(ns notes-index)
(defn add-note!
  "Store one note on my branch and return it as stored. The caller supplies created-at."
  {:malli/schema [:=> [:cat [:map [:notes-index.note/id :notes-index.note/id]
                                  [:notes-index.note/text :notes-index.note/text]
                                  [:notes-index.note/tags [:sequential :keyword]]
                                  [:notes-index.note/created-at :notes-index.note/created-at]]]
                  [:or :notes-index/note :seon.error/base]]}
  [{:notes-index.note/keys [id text tags created-at]}]
  (let [note {...} result (seon.db/transact! [note])]
    (if (:seon.error/operation result) result note)))
```

The install answered `#'notes-index/add-note!`. I had added three notes by hand (`n1`, `n2`, `n3`). Then:

```clojure
(seon.db/q '[:find [?id ...] :where [_ :notes-index.note/id ?id]])
;; => ["yIEXMwM170LgT0" "bWlqs" "nGb4w8" "K781mW3F58ntJI0P0y3A7u" "i" "n3" "4" … 45 in all]
```

42 of the 45 notes were written by the auto-check's generated calls, about 21 for each of the two `defn`s of
`add-note!`. They are ordinary committed facts on the agent's live branch. Every later `by-tag`, `all-notes`
and `summary` answer counts them. I removed them with one `retractEntity` transaction (tx `536871457`).

**Reproduced on HEAD 6a8e46ec6** (default pid 27723, branch `dogfood-author-opus-2`): the first `defn add-note!`
left 22 generated notes and its redefinition left 23 more. I removed them twice.

**Install-gate test bodies write too.** After a gated redefinition of `tagged` whose gating test calls
`(notes-index/add-note! {… :notes-index.note/id "t1" …})`, the note `t1` was on my live branch at tx
`536871459`. The gate had run that test body in my world, not in a fork.

## Expected

At `clojure.main`, defining a function never calls it. If the system calls a new function with generated
inputs to check its contract, the calls must not change the agent's world. The law "Test the real system with
injected worlds … branch writes from a captured commit" applies to the auto-check as to any test. The gate for
tests already runs "each reaching test on its own branch off my current commit" (`my.test/check` doc).

## Model that is wrong

The auto-check runs the generated calls in the agent's own custody (its connection), not in a fork of the
agent's commit, or in `d/with`, whose result is thrown away. The fix belongs to the auto-check's world
construction, not to agents avoiding `!` functions.

## Reproduce

On a fresh branch: register an entity schema with a string identity, `defn` a function that
`seon.db/transact!`s one entity built from its argument, then query the identity attribute.
