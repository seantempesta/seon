---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, contracts, auto-check, errors, repl, live-test]
created: 2026-09-15
---

# A refused defn install shows `#'ns/fn` as the value with the refusal in `:out`, so the agent believes the function exists

## Observed (run 4, default, 2026-09-15 04:27Z)

`(defn largest-customer {:malli/schema [:=> [:cat [:vector :example/order-row]] [:map [:customer :string] [:total :int]]]} [rows] …)`
evaluated to shown text `#'my.agents.juniper/largest-customer` with `:out`:

```
Gate: 0 tests · 0 passed · 0 failed · auto-check 1/25 · install refused

Failure group (1)
Auto-check seed 7064958232951744
arguments: [[]]
expected: [:map [:customer :string] [:total :int]]
actual: {:seon.error/message "my.agents.juniper/largest-customer violated its contract (invalid-output): …
```

The checker was RIGHT (an empty vector of rows yields nil, not a map) and
the install was refused — but the value the model read was a var, `dir`
listed the function in its namespace (the SCI var exists), and no
`:seon.fn` row was written. The model spent ~20 of 30 turns querying for
the missing row ("My defn did not get admitted with a :seon.fn row",
"Admission may be asynchronous"), never fixing the contract, and ended
with `:reset`.

## Wanted

- A refused install is the agent's mistake: the evaluation's result is a
  flat `:seon.error` value (kind, the failing generated argument, expected
  vs actual, and the one sentence "fix the contract or the function and
  re-evaluate the defn"), never a var plus prose in `:out`.
- A refused defn does not leave a callable SCI var behind that `dir` then
  lists as if installed; either the var is not interned or `dir` marks it
  "not installed (contract refused)".
- Regression: the exact run-4 defn on the canonical harness yields one
  `:error` evaluation and no `:seon.fn` row; `(dir ns)` does not list it as
  installed.

## Run4-blockers ownership boundary, 2026-09-15

`src/seon/turn.clj:3072` owns `gate-function-install`; its caller gates
already evaluated forms at line 4502. `seon.sci.eval/evaluate` has already
interned the definition and populated `:seon.eval/shown`. The gate merges
legacy admission output while retaining that shown Var text. Correcting the
flat error at the accretion constructor alone cannot replace those saved
bytes or prevent the earlier interning.

The requested candidate seam is already available in
`seon.sci.eval/evaluate-candidate`. Moving the one existing gate before
retained-context installation needs a narrow `turn.clj` caller change;
that path is explicitly excluded from this lane. Owner clarification was
requested while reader and provider-stop work continued. No second gate
or workaround has been introduced.

The bounded follow-up is to move that existing decision before retained
context mutation, use its existing candidate evaluation as the only trial
definition, render the refusal through the ordinary evaluation value path,
and install only accepted declarations. Rejected redefinitions must also
preserve the previously accepted callable. The regression source is
`faef54087471` in `test/seon/run4_replies.edn`; it must assert the stored
error, absent program row, and the same retained context's `dir` result.
The turn-completion backstop is unrelated and remains outside this change.

## Resolution — authorized continuation, 2026-09-15

The owner authorized the narrow `turn.clj` caller change. Function
declarations now evaluate once in the existing candidate context; the gate
checks that evaluation before the next form and transfers only an accepted
function root. Refusal renders and binds one flat error result and discards
candidate bindings. Its kind, generated arguments, expected/actual evidence
and actionable message are available directly on the result value.

The canonical real-agent-graph regression replays `faef54087471` and checks
one error, no function row, no retained callable, and a successful directory
read without the function. It also accepts a valid replacement and verifies
that another refused replacement preserves its callable identity and source.
The focused armed gate passed 11 tests / 55 assertions; the final isolated
gate passed 16 tests / 434 assertions and the platform gate passed 84 tests /
505 assertions, all green. The live hot-reloaded JVM independently
returned one error, arguments `[[]]`, no callable and no directory entry.

Full commands, final gate results, live source and the separate continuation
test expectation boundary are recorded in
[the landing note](../../../prds/context-generation/research/run4-blockers-landing-2026-09-15.md).
