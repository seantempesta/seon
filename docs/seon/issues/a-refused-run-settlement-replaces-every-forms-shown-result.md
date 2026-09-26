---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, turn, settlement, program-graph, dogfood]
---

# A refused run settlement replaces every form's shown result, including read-only forms, and is stored as a core fault

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 10d6fdff5, branch `dogfood-repl-opus-8`, 2026-09-26)

`user/caller` calls `user/helper`. One run of five forms:

```clojure
(select-keys (doc my.program/ns-unmap!) [:summary :body :example])
(my.program/breaks {:seon.program/subject 'user/helper})
(my.program/ns-unmap! {:seon.program/subject 'user/helper})
(ns-unmap 'user 'helper)
(caller 1)
```

- **All five** events showed the same text: `Program deletion leaves surviving referrers:
  [{:seon.program/subject user/helper, :seon.program/relation :seon.fn/calls, :seon.program/referrer
  #:seon.fn{:sym user/caller}, :db/id 34199}]. Repair or retract them in the same transaction.`
- The answers the agent asked for were lost: the `doc`, and `breaks`, which is the refactoring data
  the deletion law exists to produce. Re-running the first two forms alone showed them.
- The reply also carried a 3 KB `datahike.writer :error … Transaction report validation rejected.`
  stack trace as an `out` event.
- The branch's error window recorded the refusal as a stored fault (`seon.db/transact!`, signature
  `6e0c6d4e…`).

The deletion refusal is correct: a severed caller must be refused at the write.

## Which model is wrong

The run is settled as **one transaction** that carries every form's evaluation facts together
with the program change. When that transaction is refused, the refusal is copied onto every
evaluation's shown result. But the forms were already evaluated, and their shown values exist.
Only the program change is refused. The model should be: evaluations settle (they are data about
what happened), and the program change that failed is that one form's error. The run records one
refusal on the form whose effect was refused.

The refusal is also the agent's mistake, a declared refusal, so it returns to the agent. It is
not a core fault to store and deliver to root. The Datahike writer's `:error` log of a validation
rejection is expected behavior, not an error, and must not reach the reply.

## Expected

Form 0 shows the `doc` map, form 1 shows the `breaks` plan, form 3 shows the deletion refusal,
and form 4 shows `3`. No fault is stored and no writer trace appears.

## Regression

A run whose third of five forms is refused by the program graph shows the other four forms'
values and one refusal.
