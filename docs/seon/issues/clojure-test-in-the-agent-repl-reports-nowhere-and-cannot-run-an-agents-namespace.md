---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, sci, repl, test, dogfood]
---

# `clojure.test` in the agent REPL reports nowhere and cannot run an agent's namespace

Found by the standing dogfood lane dogfood-repl-opus-2 (cycle 10), working only through MCP SCI on branches
`dogfood-repl-opus-2-c10` (archive 6ccf12a5a) and `-c10d` (5bed888d0).

## Observed, beside `clojure.main` 1.12

| Form, in the agent's namespace `dogfood.wc2` with two passing `deftest`s | `clojure.main` | Seon agent REPL |
|---|---|---|
| `(clojure.test/deftest zz-deliberately-fails (clojure.test/is (= 1 2)))` then `(zz-deliberately-fails)` | prints `FAIL in (zz-deliberately-fails) …` with expected/actual, returns `nil` | returns `nil`, prints nothing |
| `(clojure.test/run-tests)` in `dogfood.wc` | `Ran 1 tests containing 2 assertions. 0 failures, 0 errors.` and `{:test 1, :pass 2, …}` | `{:test 0, :pass 0, :fail 0, :error 0, :type :summary}` |
| `(clojure.test/run-tests 'dogfood.wc2)` | as above | `Execution error at (REPL:1:1). No namespace: dogfood.wc2 found` |
| `(clojure.test/test-vars [#'d-top-words-orders-by-count])` | prints failures, returns `nil` | returns `nil`, prints nothing (the test passes, so nothing is lost here, but a failure would be) |
| `(my.test/run)` from `(in-ns 'dogfood.wc)` | not applicable | `[]`: it runs the agent's assigned namespace (`user`), not `*ns*`; `(help)` says "(my.test/run) runs yours" |

The only working path is `(my.test/check {:seon.test/changed [...]})`, which runs the tests reaching named symbols in a
fresh test world (2.0–3.6 s for one or two tests; see
[test-world-preparation-spends-seconds…](test-world-preparation-spends-seconds-in-config-apply-and-unmemoized-reads.md)).
There is no entrance to run one named test, or the tests of the namespace I am in, and see its report.

## Which model is wrong

The agent's `clojure.test/run-tests` is the host function running over the host's namespaces: "No namespace: dogfood.wc2
found" is `clojure.core/the-ns`'s message, and the agent's namespace exists only in its SCI context, so the host finds
no tests. Reports go to the host's `clojure.test/*test-out*`/`report`, not to the evaluation's recorded `*out*`
(`seon.sci.eval` binds `clojure.test/report` to `(constantly nil)` for gate tests, `src/seon/sci/eval.clj:4617`, and the
agent's direct calls print nothing either). Hypothesis, not traced: `deftest`/`is` are the host's macros resolved into
SCI, while the functions that find and report tests are not SCI-aware. Clojure's model is that `deftest` interns a Var
with `:test` metadata in the current namespace, and `run-tests`/`test-var` find it there and report through
`*test-out*` (bound to `*out*`). SCI itself ships no `clojure.test`; babashka's SCI-side copy of `clojure.test` is the
precedent to study before writing anything.

## Expected

In the agent REPL, `deftest`, `is`, `run-tests`, `test-var` and a direct call of a test behave as at `clojure.main`,
reporting into the evaluation's printed output. `my.test/run` runs `*ns*`'s tests (or says which namespace it ran).
`my.test/check` stays the gate for merges.

## Regression

One parity row per table line, through the agent entrance.
