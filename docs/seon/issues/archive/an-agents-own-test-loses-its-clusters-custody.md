---
type: issue
status: resolved
severity: blocker
created: 2026-09-17
tags: [test, custody, agent, class]
---

# An agent's own test loses its cluster's custody

`(my.test/run)` inside an agent evaluation now fails every test that uses an
elided `seon.db` arity. Measured in process on `default` (pid 88182) inside
`seon.loop-proof-test/virtual-loop-end-to-end`, where the Juniper scenario's
plan step runs the agent's own `deftest`:

```clojure
expected: (= 155 (seon.db/q '[:find (sum ?amount) . :where
                              [?order :example/customer "Ada"]
                              [?order :example/amount ?amount]]))
actual:   (not (= 155 {:seon.db/missing-connection-binding true
                       :seon.error/data #:seon.db{:binding seon.db/*conn*
                                                  :needed "a database value"}}))
```

and the stored result row is `[0 1 0]` where the regression expects
`[1 0 0]`.

Resolved 2026-09-17 by making the custody a value the caller hands the one
run seam: `seon.db/call-with-custody`, `seon.test.runner/run-var!`'s second
arity, `:seon.db/connection` in `:seon.test/run-options`, and
`seon.test/run-owned` behind `my.test/run`. Landing note:
[agent-tests-keep-custody-2026-09-17](../../prds/steward-platform/research/agent-tests-keep-custody-2026-09-17.md).
The same class in `seon.sci.eval/run-candidate-test!` — an agent's ACCRETION
GATE tests — was closed the same way in `57a77642b`, once that file was
released, with
`seon.test.accretion-test/a-gate-test-runs-with-its-authors-cluster-custody`
as its regression.

## Cause

`seon.test.runner/run-var!` runs every test Var inside
`seon.db/call-without-custody` (`src/seon/test/runner.clj:600`). That is right
for an in-process HOST test, which must not inherit the live cluster's
connection — the 2026-09-17 write storm's class 1. It is wrong for a test an
AGENT runs in its own cluster: the path is
agent evaluation → `my.test/run` (`src/my/test.clj:36`) → `seon.test/run` →
`bounded-result` → `run-var!`, and the agent's evaluation is exactly the
context where AGENTS §3 says the elided arity is legitimate ("agent calls can
elide db/conn to the calling agent's cluster's current database").

So the fix is not to widen the agent's test but to make the strip name its
own condition: custody is dropped for a test the HOST runs in process, not
for a test whose caller is an agent evaluation that legitimately holds
custody. The evaluation already carries the connection it bound
(`src/seon/sci/eval.clj:2300`), so the decision can be derived rather than
guessed.

## Evidence it is this change, not the fixture

The same test run with the pre-`5553725d3` fixture bytes produces the
identical failure, so it is not the juniper-installer change; and
`seon.test-support-test/a-test-body-inherits-no-ambient-cluster-custody`
(the regression that pins the wanted host behaviour) stays green, so the two
cases genuinely need distinguishing rather than trading off.

Related: `a-failing-turn-write-refires-without-bound-and-fills-the-store`
(where the custody strip landed), and
`virtual-loop-fixture-submission-can-race-an-armed-turn` (the other cause of
the same test's cold red).
