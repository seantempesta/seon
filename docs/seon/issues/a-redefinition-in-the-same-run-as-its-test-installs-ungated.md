---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, gate, test, program-graph, dogfood]
---

# A test defined earlier in the same run neither reaches nor gates a later redefinition

## Observed (MCP SCI, branch of default, archive fbd0897df, 2026-09-26)

In one run:

```
user=> (defn dre-sq "Square x." {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))
#object[sci.lang.Var]
user=> (clojure.test/deftest dre-sq-test (clojure.test/is (= 16 (dre-sq 4))))
#object[sci.lang.Var]
user=> (my.program/tests-reaching {:seon.program/subject 'user/dre-sq})
my.program/locate refused  at []: … Fix: No program declaration names user/dre-sq. …
user=> (defn dre-sq "Square x." {:malli/schema [:=> [:cat :int] :int]} [x] (* x x x))
#object[sci.lang.Var]
```

That redefinition breaks `dre-sq-test`, and it was **installed**: in the next run,
`(dre-sq 4)` answered `64`.

In the next run, after settlement, the link exists and the gate works:
`tests-reaching` answered `:seon.program/gating [user/dre-sq-test]`, and a further redefinition
`(* x x x x)` was refused with `Gate: 1 tests · 0 passed · 1 failed … expected: (= 16 (dre-sq 4))
actual: (not (= 16 256))`.

On archive 052552039, `tests-reaching` answered no gating tests even across runs.

## Class

A run's earlier forms are not yet program rows when its later forms are gated and read. So
within one run:

- a function defined earlier is "not found";
- a test defined earlier gates nothing;
- a breaking redefinition installs.

This is related to task `132fee0467c8` "definitions-bypass-entry-gate"
(`resources/seon/tasks/seon.sci.eval.edn`), which covers definition forms the reader does not
match. The within-run order is a separate path to the same ungated install.

## Regression

In one run: define `f`, define a test asserting `f`, redefine `f` so that the test fails. The
redefinition is refused by name, and `f` keeps its first body.
