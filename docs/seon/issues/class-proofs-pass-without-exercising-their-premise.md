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

The assertionless-test member was closed by `ad3d13e9b`. At committed HEAD,
`seon.test.runner/assertionless-failure` turns every `:end-test-var` event with
zero pass, fail, and error reports into an attributed failure. The shared
`capture-and-report-event!` path is used by direct Var runs, namespace runs,
and worker tasks, and the retained fixture proves that a `deftest` whose body
returns without an assertion is red rather than vacuously green.

The class remains open because this enforcement does not prove nonempty
production-derived subject sets, honest generator reachability, or live-drive
semantic exits; those surviving members are still returned by the class
query.

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
