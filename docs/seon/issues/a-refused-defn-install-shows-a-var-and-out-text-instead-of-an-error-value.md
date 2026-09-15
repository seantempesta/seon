---
type: issue
status: open
severity: blocker
tags: [sci, contracts, auto-check, errors, repl, live-test]
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
