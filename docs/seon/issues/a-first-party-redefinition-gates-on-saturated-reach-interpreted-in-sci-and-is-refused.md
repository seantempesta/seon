---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, gate, test, sci, program-graph, dogfood]
---

# Redefining a first-party function gates on 1,495 tests interpreted in SCI, and 1,482 fail to resolve their helpers

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive a1b01c92a, branch `dogfood-repl-opus-3`, 2026-09-26)

This was a docstring-only improvement: an `Example:` line added to `my.issue/status`, with the
body and contract unchanged.

```clojure
(in-ns 'my.issue)
(defn status "Read an issue and its current test outcomes. … Example: (my.issue/status {…})"
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/id :seon.issue/id]]] :map]}
  [request]
  (issue/status request))
```

The run shown was one 40 KB string:

```
seon.test.accretion/install-refusal
Fix the contract or the function and re-evaluate the defn.

Gate: 1495 tests · 13 passed · 1482 failed · auto-check 1/25 · install refused
…
Failure group (433)
Test my.agent-test/settings-are-one-owned-component-with-a-derived-render-pair
Unable to resolve symbol: support/with-database
…
Failure group (408)  … Unable to resolve symbol: test-support/with-database
Failure group (48)   … Unable to resolve symbol: with-cluster
Failure group (35)   … Unable to resolve symbol: testing
Failure group (18)   … Unable to resolve symbol: io/file
Failure group (9)    … Unable to resolve symbol: tc/quick-check
… (System/nanoTime, future, slurp, m/children, sci/init, d/create-database, …)
```

- `(my.program/tests-reaching {:seon.program/subject 'my.issue/status})` answered
  `:seon.program/gating` with 1,495 tests, although `my.program/callers` for the same subject
  answered **no callers**. This is the saturation already recorded in
  [call-graph fidelity](call-graph-fidelity-selection-awaits-adopted-proof.md).
- The same `defn` on archive f0efd9cc8 (branch `dogfood-repl-opus-4`) ran past the MCP 30 s
  bound. The next run on that branch was refused with `agent-already-running`, and default's JVM
  was replaced before the gate finished. I did not establish whether the gate caused the
  replacement.

Any first-party function the analyzer links to the shared test fixtures reaches about 1,500
tests. So an agent cannot change even a docstring on one. The milestone step
"`defn` → the tests reaching the change" fails for every first-party subject.

## Which model is wrong

Two models, compounded:

1. **Selection.** Reach through shared fixtures and keyword joins is treated as behavioral
   reach. A test is selected because it and `my.issue/status` both touch the fixture graph, not
   because it calls the subject. With no caller at all, 1,495 gating tests is the saturation
   signal.
2. **Execution.** The gate runs file-authored tests by *interpreting their bodies in the agent's
   SCI context*. Their `support/`, `test-support/`, `io/`, `tc/`, `sci/`, `d/` aliases, and host
   interop such as `System/nanoTime` and `future`, are not in that context, so the failures
   measure the SCI world, not the change. An unchanged file test body is host code. It should
   run as its compiled Var, with the branch's changed definition bound, the way ordinary tests
   run as agents run. SCI interpretation belongs only to rows that differ.

A third, smaller point: a docstring-only redefinition has no behavior change, yet it gated. If
the definition digest includes the docstring, the gate needs a behavior identity that excludes
prose.

## Expected

- The gate for a redefinition runs the tests that reach it through calls, as compiled Vars where
  their bodies are unchanged. A test that cannot load is refused by name with its file, and it is
  never counted as a failure of the change.
- The install report is bounded and says which tests reach the change and why.

## Regression

On a branch, redefine `my.issue/status` with a docstring-only change. The gate selects only tests
that call it (none today), and the definition installs.
