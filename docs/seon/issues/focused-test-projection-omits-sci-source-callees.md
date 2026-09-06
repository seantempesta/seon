---
type: issue
status: open
severity: friction
tags: [issue, test, sci, program-graph, class/p1]
---

# Focused call-preparation fixture omits supplied-default rows

## Problem

A focused acquired-context test can contain the complete target function and
arity facts while omitting the initialization rows that declare supplied
defaults. The SCI hook is installed, but its snapshot then has no database
default and correctly leaves the short call unchanged.

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

An ordinary direct `my.plan/plan` edge proved the function and arity row were
present. Without `database-rows`, `seon.call-preparation/plan` returned no
insertion for supplied count one. The hook received the exact identity
`"my.plan/plan"`; the explicit two-argument call reached the body while the
implicit one failed. The pooled pass came from an earlier test transacting the
rows into the shared worker database. Isolated confirmation exposed the absent
fixture input.

A direct Malli probe also falsified the suspected `:catn` indexing defect:
`m/children` returns `[label nil compiled-child]` for a two-element authored
entry, and the program graph stores the database-value child's canonical
fingerprint.

## Owner

The focused fixture owns installing the same declared supplied-default rows as
the production initialization population before acquiring its context.

## Acceptance

- The isolated fixture contains the declared database supplied-default row.
- The isolated call-preparation confirmation derives insertion index zero for
  `my.plan/plan`.
- Implicit and explicit database calls reach the same function result.
- No runtime fallback reconstructs contracts from host Var metadata.
