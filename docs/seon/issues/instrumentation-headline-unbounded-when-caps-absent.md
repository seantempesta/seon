---
type: issue
status: open
severity: friction
tags: [issue, instrumentation, schema, sci]
---

# Require the general printer bound for every contract headline

## Problem

Contract arguments are correctly omitted when admission caps are unavailable,
but the humanized problem set in the headline falls back to raw `pr-str`.
Because the public instrumentation request declares caps optional, a valid
`:panic` caller can generate an unbounded exception message even though the
reporter's docstring describes a flat, bounded value.

## Evidence

- The INSTRUMENT section of `resources/seon/schema.edn` declares
  `:seon.sci.admit/caps` optional.
- `src/seon/instrument.clj:156-188` sends problems through admission only when
  caps exist and otherwise calls `pr-str` without a bound.
- `test/seon/instrument_test.clj:93-113` checks that args are omitted without
  caps, while `:115-145` checks headline size only with caps supplied.
- `tmp/audit-20260801b/src/contract_message_probe.clj` invoked the public
  no-caps path with 200 closed-map problems. The message was 5,002 characters;
  the problem count was 200 and args were absent.

## Owner

The one contract-violation projector in `seon.instrument`.

## Acceptance

- Every valid `apply!` request gives the violation projector admission caps,
  or the no-caps request shape is removed.
- No contract headline uses raw unbounded printing.
- The many-problem regression covers both public request arms and preserves the
  full problem count as the diagnostic fact.
