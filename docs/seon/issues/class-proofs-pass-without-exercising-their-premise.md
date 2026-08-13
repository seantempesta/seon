---
type: issue
status: open
severity: friction
tags: [issue, test, class/n2, class-kill, wave/class-kill-queue]
---

# Make proofs unable to pass without exercising their premise

## Problem

Tests and live drives can derive no subjects, construct a weaker fixture than
production, omit the failing generator branch, or declare success before the
semantic exit. The assertion can then be green while the claimed mechanism was
never exercised.

## Evidence

Current open members carry `class/n2` and are derived with
`bin/issues-index --class class/n2`.

## Owner

`bin/test`, fixture constructors, and the program-graph subject discovery used
by each recurring proof.

## Acceptance

- Proofs obtain subjects through the same constructor/query as production and
  refuse an empty or incomplete subject set before semantic assertions run.
- Every property carries one retained counterexample that demonstrably makes
  it fail, and every generator creates fresh honest values.
- A live drive closes only on the requested durable semantic exit, not handoff
  or an intermediate transition.
