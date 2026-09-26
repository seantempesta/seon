---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, sci, agent, dogfood, repl]
---

# A refused run leaves the agent's context unavailable, and the agent has no entrance to reacquire it

Found by the standing dogfood lane dogfood-repl-opus-2 (cycle 10), branch `dogfood-repl-opus-2-c10c`, default archive
5bed888d0, working only through MCP SCI.

## Observed

One run redefined a callee and then removed three declarations in order:

```clojure
(in-ns 'dogfood.wc)
(defn tokens "…" {:malli/schema [:=> [:cat :string] [:vector :string]]} [text] …)
(my.program/ns-unmap! 'dogfood.wc/blank-word?)
(my.program/ns-unmap! 'dogfood.wc/top-words-orders-by-count)
(my.program/ns-unmap! 'dogfood.wc/top-words)
(sort (keys (ns-publics 'dogfood.wc)))
```

- The run's settlement was refused by the writer: "Nothing found for entity id
  [:seon.test/sym dogfood.wc/top-words-orders-by-count]" (the test row was already gone). Every form, including the
  `defn` and the read-only `ns-publics`, showed that one refusal (known class:
  [a-refused-run-settlement-replaces-every-forms-shown-result](a-refused-run-settlement-replaces-every-forms-shown-result.md)),
  and the reply carried the `datahike.writer` stack trace as two `out` events.
- **Every later run on the branch**, including `(+ 1 1)`, answered only:
  "This execution is unavailable: its program was reacquired before a refused change could be restored; release and
  reacquire the context." The agent could not do anything else; retiring the branch was the only way out.

## Which model is wrong

`restore-or-unavailable!` (`src/seon/sci/eval.clj:720-740`) stores `::unavailable-execution` in the context's env when a
refused program change cannot be restored because the program was reacquired meanwhile, and every later form receives
it. The message tells the agent to "release and reacquire the context", but no `my.*` function, REPL form or MCP tool
does that; the refusal names an action the agent cannot take (ONE PERSPECTIVE: a missing entrance is the defect).

The context is a value derived from the branch's rows (laws: "Reacquire from the branch head at turn start"). A refused
change leaves the rows unchanged, so the next run's acquisition from the head is already the restoration. Making the
unavailability sticky beyond the run that failed keeps a stale mutable state instead of re-deriving it.

## Expected

The run that failed reports its refusal. The next run acquires from the branch head and works; nothing the agent
cannot call is ever required. If some state truly cannot be re-derived, the refusal names a callable entrance.

## Regression

On a fresh branch, a run whose settlement is refused (for example `ns-unmap!` of a test that an earlier form in the
same run already removed) is followed by `(+ 1 1)`, which answers `2`.
