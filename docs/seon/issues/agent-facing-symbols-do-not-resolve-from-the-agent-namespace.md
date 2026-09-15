---
type: issue
status: open
severity: blocker
tags: [sci, help, dir, my.note, deftest, live-test]
created: 2026-09-14
---

# Bare `deftest` does not resolve, and the model never found `my.note/add!` although `dir` listed it

## Observed (run 2, and the model's own account in `research/explain_probe_turn40_2026_09_14.edn`)

The help line says "Tools: my.agent, my.background, my.edit, my.fs,
my.message, my.note, my.plan, …" and "A deftest becomes a durable test".
Checked against the stored evaluations (not the model's memory): the
agent called `(my.note/add …)` and `(my.note/create! …)` — both unresolved,
both names it guessed; `(ns-publics 'my.note)` returned
`{add! forget! notes}` and it still never tried `my.note/add!`, falling
back to `seon.db/transact!` on `:my.note/*`. Bare `(deftest …)` was
"Unable to resolve symbol: deftest"; `clojure.test/deftest` worked. The
model's account ("neither resolves") is wrong about `add!` but right about
the experience: the `dir` listing it saw was elided by the profile
("Retur…", "vector 12 items … requery refused") so the names with their
bangs never reached it whole, and help promises a bare `deftest`.

## Wanted

- Every namespace help lists under Tools resolves by its qualified symbol
  from the agent's namespace, and `dir` on it lists exactly the symbols that
  resolve. A regression on the canonical SCI context: for every namespace
  in the help's Tools line, every `dir` entry resolves.
- Either bare `deftest` resolves in the agent namespace (referred like
  `dir`/`doc`) or help says `clojure.test/deftest`. Same for `defn`
  contracts: help names the exact schema shape `[:=> [:cat …] …]`.
