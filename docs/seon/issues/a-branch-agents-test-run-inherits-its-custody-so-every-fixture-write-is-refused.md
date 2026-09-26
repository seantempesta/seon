---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, test, custody, my-test, dogfood]
---

# A branch agent's test run keeps the agent's custody, so every fixture member's write is refused

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 10d6fdff5, branch `dogfood-repl-opus-5`, 2026-09-26)

The hosted agent added one test, `my.plan-test/current-refuses-an-unknown-agent-by-name`, then ran
the tests reaching its change:

```clojure
(my.test/check {})
;; => #:seon.test{:passed? false, :elapsed-ms 5873.3, :executed-count 23}
```

22 of the 23 members are the file-authored `my.plan-test` tests. Each one errored before its
first assertion with:

```
This write names branch :agent-7b8e8242ff64, which is not the writing cluster's branch
:dogfood-repl-opus-5. A cluster writes only its own branch: send the work …
```

Each member names a different `:agent-…` branch, which is its own member world. The one test
that writes nothing, the agent's new one, passed (2 assertions).

## Which model is wrong

Custody is an **ambient dynamic binding** (`seon.db/*conn*`). The test member inherits it from the
calling agent's run. `seon.db/foreign-connection-error` (`src/seon/db.clj:485-517`) compares the
member fixture's explicit connection with the inherited ambient one, `:dogfood-repl-opus-5`, and
refuses. The law says "fork the context onto the test branch, **inject custody**, run one body".
The member world must carry its own custody explicitly, so a nested world never sees its caller's
custody. This is the same class as the receipt-custody slice (9a21c6cc1): custody must travel
with the connection it belongs to, not through the dynamic extent.

A root run (no branch) may pass the same tests, because root's custody is the cluster's own
branch and the member fixtures' checks are shaped around it. So green from root does not show
that agents can run the tests.

## Expected

`(my.test/check {})` from a branch agent runs each file test member in its own world, under its
own custody, and reports the same outcomes as the same request from root.

## Regression

A hosted agent on a branch runs `(my.test/check {:seon.test/changed ['my.plan-test/a-changed-title-is-an-ordinary-fact-update]})`
and gets that member's real outcome, never a foreign-branch write refusal.
