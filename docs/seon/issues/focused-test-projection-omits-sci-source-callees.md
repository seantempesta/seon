---
type: issue
status: open
severity: friction
tags: [issue, test, sci, program-graph, class/p1]
---

# Focused test projection omits calls that exist only in SCI source

## Problem

A focused test can evaluate a first-party function named only inside an SCI
source string while its reduced program projection omits that function's
contract facts. The acquired SCI context still binds the host Var, but call
preparation has no `:seon.fn` arity to plan from and the call reaches the Var
with its database argument absent.

This makes the result depend on prior worker load state: a pooled run whose
database already contains the callee passes, while isolated confirmation
fails.

## Evidence

On 2026-09-06,
`seon.call-preparation-test/an-acquired-context-supplies-a-leading-database-to-plan`
called `(my.plan/plan "missing")` through a fresh acquired context. The pooled
worker passed. The runner's isolated confirmation then failed before the body:

```text
Wrong number of args (1) passed to: my.plan/plan
```

Adding an ordinary direct `my.plan/plan` call to the test creates the program
graph edge and makes the same acquired-context SCI call pass. This falsifies a
hook or schema-fingerprint defect: with the function row present, the existing
positional query derives the leading `:seon.db/database-value` insertion.

## Owner

The focused test program projection owns reachability. A source-producing
function's declared calls must make source-only callees reachable without
parsing or guessing from generated strings. Until that fact exists, a focused
regression must carry an ordinary direct call edge for every callee it invokes
only through SCI text.

## Acceptance

- A source-only first-party SCI call has its complete function and arity facts
  in both the pooled test database and isolated confirmation database.
- The test passes from a clean worker without an artificial direct call.
- No runtime fallback reconstructs contracts from host Var metadata.
