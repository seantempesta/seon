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

## Core-functions work — 2026-09-14

Help now says to use `(dir ns)` first, copy exact names including `!`, and call
`(doc my.note/add!)` for the worked example. The real canonical SCI regression
walks every help Tools namespace and requires every `dir` function to resolve.
Juniper's retained default context returns the new note docs without errors;
exact bytes are in
`docs/prds/context-generation/research/core-functions-landing-2026-09-14.md`.

Bootstrap seed namespace rows now refer `clojure.test/deftest` and `is`.
The universal core referral still belongs to `src/seon/sci/eval.clj`, explicitly
excluded from this lane. The retained context reports both bare symbols absent.
The requested narrow scope extension has not yet been approved; this issue
remains open and the bootstrap-only change is not claimed as the full repair.
