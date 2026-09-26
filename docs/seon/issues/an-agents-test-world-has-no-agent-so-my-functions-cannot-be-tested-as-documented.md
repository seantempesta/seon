---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, test, my-test, call-preparation, dogfood]
---

# An agent's REPL `deftest` runs in a world with no agents, so `my.*` functions cannot be tested as their docstrings call them

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 10d6fdff5, branch `dogfood-repl-opus-5`, 2026-09-26)

`my.plan/current` had no reaching test (`(my.program/tests-reaching …)` answered no
`:seon.program/gating`). Its docstring example is `(my.plan/current)`, and at the REPL that call
answers `{}`. I defined the natural test:

```clojure
(in-ns 'my.plan-test)
(clojure.test/deftest current-is-an-empty-map-without-a-selected-step
  (clojure.test/is (= {} (my.plan/current))))
```

`(my.test/check {:seon.test/changed [that test]})` then gave:

1. `(my.plan/current)` → `my.plan/current refused argument count at []: expected the declared
   arglists, got an argument count of 0`. Call preparation supplies nothing inside the member.
2. With an explicit `{:seon.db/db (seon.db/db) :seon.agent/id "root"}` → `Plan request refused;
   an existing agent is required.`
3. `(seon.db/q '[:find [?id ...] :where [_ :seon.agent/id ?id]])` inside the member → `[]`. The
   member world has **no agent rows at all**, not even the agent the test runs as.

Only a refusal path can be tested. The test that landed on the branch asserts
`:my.plan/missing-agent-id` for an unknown agent, which passes with 2 assertions.

Each `my.test/check` of that single test took 2.0 to 3.1 s end to end, while its member timings
summed to about 130 ms (acquire 38–67 ms, run 26–41 ms, release 9–16 ms). So about 2 s of every
one-test request is outside the member, and that is over the one-second bound.

## Which model is wrong

"Run tests as agents run" says the member world is the agent's context forked onto a test branch.
Here the member world is a prepared commit that knows nothing of the agent: no agent row, no
namespace assignment, and no custody for call preparation to supply `:seon.db/db` and
`:seon.agent/id` from. The docstring's own example, which is the thing
`my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context` claims to prove,
cannot be a test body. The member world should fork from a commit that contains the running
agent, or seed that agent through the canonical writer. Call preparation should then supply its
declared defaults exactly as at the REPL. This is related to
[call preparation reading the head](call-preparation-snapshot-answers-an-older-database-with-a-newer-program.md)
(no connection in the environment, so no plan), but the missing agent is a separate cause:
supplying a database would still name no agent.

## Expected

A REPL `deftest` whose body is a `my.*` docstring example passes when the example passes at the
REPL.

## Regression

A branch agent defines `(deftest t (is (= {} (my.plan/current))))`, and `my.test/check` reports it
passing.
