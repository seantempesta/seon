---
type: issue
status: open
severity: friction
tags: [issue, test, runtime]
created: 2026-09-15
---

# Turn-loop regressions still expect retired state and time shapes

## Evidence

An untouched checkout at `93883543d` ran `bin/test-fast seon.turn-loop-test`:
26 tests, 116 assertions, 20 failures and one error. The run7 message change
produces the same 20 failures and one error (118 assertions because its
reply-precedence regression now follows immediate sends).

Examples: `a-private-def-settles-without-staging-a-blob-and-closes` checks
`inst?` on `:seon.turn/closed-tx`, whose actual value is a transaction ref
`#:db{:id 536870926}`; `one-wake-cannot-open-a-second-turn-after-the-first-closes`
expects the old wake-answering behavior; the provider-overlay test expects
three overlay resolutions and records five. These observations do not prove
every failure has the same cause.

## Follow-up

Reconcile these tests with the binding turn model on the canonical fixture.
Keep transaction-time assertions through `:db/txInstant`. The run7 wave's
required loop proof, continuation proof, and platform gate remain separate
evidence; it does not take ownership of this older namespace's rewrite.

Full run7 evidence:
[landing](../../prds/context-generation/research/run7-wave-landing-2026-09-15.md).
