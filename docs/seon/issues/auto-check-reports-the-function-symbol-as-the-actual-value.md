---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, test, accretion, auto-check, error-report]
---

# The auto-check reports the function's symbol as "actual" when the call threw

## Observed (both archives, 052552039 and fbd0897df, 2026-09-26)

```
user=> (defn dre-sq "Square x." {:malli/schema [:=> [:cat :int] :int]} [x] (* x x x))
seon.test.accretion/install-refusal
…
Auto-check seed 733254833444966175
arguments: [2097152]
expected: :int
actual: user/dre-sq
why: ["should be an integer"]
```

On fbd0897df, `(* x x x x)` gave the same report with `arguments: [55109]`.

2097152³ and 55109⁴ both exceed `Long/MAX_VALUE`, so the call threw `ArithmeticException:
integer overflow`. The refusal itself is right. The report is wrong: it names the function where
the thrown exception belongs, and the `why` claims an output-schema failure. An agent reading it
looks for a non-integer return and finds none.

## Expected

A throwing auto-check call reports the exception's class, message and `ex-data` ("Keep the whole
cause"). It never reports the callee symbol as a value.

## Regression

Contract `[:=> [:cat :int] :int]` with body `(* x x x)`: the refusal names `ArithmeticException`
and the generated argument.
