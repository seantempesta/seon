---
type: issue
status: open
severity: friction
tags: [issue, flow, contracts, testing]
---

# Generate fresh Flow contract values

## Problem

Flow's opaque predicate generators return one delayed mutable object forever.
They satisfy the predicate once but cannot explore lifecycle, freshness, or
state-dependent failures, so generative contract checks are green for the
wrong reason.

## Evidence

`src/seon/flow.clj:56-66` creates one executor, atom, `FutureTask`, proc
launcher, graph, and channel in a delayed map. Lines 68-79 define every
generator as `gen/fmap` over `(gen/return nil)`, returning that same object on
every generation. A direct probe generated two channel values and
`identical?` returned true.

`test/seon/public_contract_test.clj:83-93` checks only that one generated value
passes its predicate. Its freshness regression at lines 95-111 covers store
connections and file locks, not the Flow resources. In the same boundary,
`src/seon/flow.clj:94-107` gives `var-process` an input contract of `:any` and
then immediately requires `var?` at runtime.

## Owner

The registered opaque Flow predicates and generators used by schema-driven
tests.

## Acceptance

- Each generation creates a fresh lifecycle-independent value or the schema is
  split so a non-generative opaque handle is not advertised as an honest open
  generator domain.
- Generative checks prove variation/freshness and safely close every created
  executor, graph, and channel.
- `var-process` names and registers its actual Var predicate contract instead
  of accepting `:any`.
- A regression invalidates one generated object and proves the next sample is
  unaffected.
