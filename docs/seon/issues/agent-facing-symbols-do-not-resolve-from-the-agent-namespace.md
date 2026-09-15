---
type: issue
status: open
severity: blocker
tags: [sci, help, dir, my.note, deftest, live-test]
created: 2026-09-14
---

# `my.note/add!` and bare `deftest` do not resolve from the agent's namespace although help and `dir` name them

## Observed (run 2, and the model's own account in `research/explain_probe_turn40_2026_09_14.edn`)

The help line says "Tools: my.agent, my.background, my.edit, my.fs,
my.message, my.note, my.plan, …" and "A deftest becomes a durable test".
In the session, `(my.note/add! …)` and `(my.note/create! …)` were both
unresolved; `(require '[my.note])` and `(ns-publics 'my.note)` did not help;
the agent fell back to `seon.db/transact!` on `:my.note/*` attributes.
Bare `(deftest …)` was unresolved; `clojure.test/deftest` worked.
The model: "the mismatch between the tools my agent has (help named
`my.note`) and the symbols the REPL will accept is a real gap."

## Wanted

- Every namespace help lists under Tools resolves by its qualified symbol
  from the agent's namespace, and `dir` on it lists exactly the symbols that
  resolve. A regression on the canonical SCI context: for every namespace
  in the help's Tools line, every `dir` entry resolves.
- Either bare `deftest` resolves in the agent namespace (referred like
  `dir`/`doc`) or help says `clojure.test/deftest`. Same for `defn`
  contracts: help names the exact schema shape `[:=> [:cat …] …]`.
