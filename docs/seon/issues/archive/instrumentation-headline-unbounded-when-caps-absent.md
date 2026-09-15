---
type: issue
status: resolved
severity: friction
tags: [issue, schema, sci, class/n1, wave/verification-audit]
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
- `test/seon/instrument_test.clj` checks that args are omitted without caps,
  while the many-problem case checks headline size with caps supplied.
- `tmp/audit-20260801b/src/contract_message_probe.clj` invoked the public
  no-caps path with 200 contract problems. The message was 5,002 characters;
  the problem count was 200 and args were absent.

## Owner

The one contract-violation projector in `seon.instrument`.

## Acceptance

- Every valid `apply!` request gives the violation projector admission caps,
  or the no-caps request shape is removed.
- No contract headline uses raw unbounded printing.
- The many-problem regression covers both public request arms and preserves the
  full problem count as the diagnostic fact.

## N1 disposition — 2026-08-12

Still open outside this lane. Although `4bc8104d8` bounds output that reaches
`seon.render`, `seon.instrument/apply!` still admits a no-caps request and can
construct the headline before that boundary. Remove that request shape or
require caps in both public arms, then render the problem collection through
the one fitted floor while retaining the total count.

## Verified at HEAD (2026-09-16, N1 verification)

**RESOLVED.** The headline no longer renders the problem SET at all, so the
caps-present/caps-absent distinction can no longer change its size.

`seon.instrument/violation` now ignores caps outright — its first parameter
is `_caps` (`src/seon/instrument.clj:279`) — and the message is built from
ONE first problem (`src/seon/instrument.clj:373-380`):

```clojure
(str function-symbol " refused " (:seon.error/argument first-problem)
     " at " (pr-str (:seon.error/path first-problem))
     ": expected " (:seon.error/expected-description first-problem)
     ", got " (:seon.error/actual-description first-problem)
     ". Fix: " (:seon.error/fix first-problem)
     (when (qualified-keyword? expected) (str " Contract: " expected ".")))
```

The only remaining `pr-str` calls in the namespace are on an error PATH
(`:275`, `:375`) and on a declared predicate form (`:452`) — never on the
humanized problem collection that produced the filed 5,002-character
message. The total problem count survives as diagnostic data rather than as
message bytes.

Live, on `default` (pid 69622), an armed contract violation:

```text
(seon.db/pull database :not-a-pattern 1)
=> "seon.db/pull refused selector at []: expected a vector, got a keyword.
    Fix: Supply a vector at []."          ; 106 characters
```

A 200-problem violation was not constructed live — no first-party public
function on this cluster takes an input whose contract yields 200 problems
without a test JVM. The closure rests on the constructor, which is where the
unbounded printing lived; the note's third acceptance line (a many-problem
regression over both public arms) is test work for the owning lane, not a
live defect.
