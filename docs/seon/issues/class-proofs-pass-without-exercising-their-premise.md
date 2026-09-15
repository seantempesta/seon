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

## Shared property boundary — 2026-09-15

The fixtures-events slice makes `seon.test-support/assert-check!` require a
positive `:num-tests` as well as a true `:result`. The recurring test runs real
test.check properties, observes one pass and two failures (zero trials and a
falsified property), and retains seed `20260728` plus smallest failing input
`[3]`. The three-worker owned-path gate passed 36 tests / 327 assertions.
See [the landing note](../../prds/context-generation/research/fixtures-events-2026-09-15.md)
for the implementation and final platform boundary.

This closes successful-zero-trial reporting at the shared assertion seam.
It does not prove that every property covers its production subject; the
remaining class members below stay open.

## Routing harness continuation — 2026-09-15

Commit `e4f8bbe07` makes the agent routing property construct at least one agent through the
production creation path before generated interleavings. Its verdict includes
nonempty subjects, and `routing-proof-rejects-an-empty-production-subject`
retains `[:message]` as the counterexample: no agent exists, so an apparently
settled empty routing table cannot pass the conservation proof. Both agent
properties use the shared counterexample-preserving `assert-check!` seam.
The landing note records serial gate results; the remaining render-property
and live-drive members still prevent class closure.

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
