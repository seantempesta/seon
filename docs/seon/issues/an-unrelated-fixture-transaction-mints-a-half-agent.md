---
type: issue
status: open
severity: friction
tags: [test, fixture, render, analysis]
---

# The "unrelated" transaction in two render tests mints a half-agent

## Observed

`d7e5a0268` ("tests: an unrelated transaction is unrelated") replaced the
neutral transaction in two tests, correctly noting that
`:seon.message/inbox` is a listened attribute and so was a wake. The
replacement addresses a bystander agent that the fixture mints inline:

- `test/seon/render/web_context_test.clj:99` — `{:seon.agent/id "context-bystander"}`
- `test/seon/render/web_test.clj:1056` — `{:seon.agent/id "debug-cache-bystander"}`

Both also re-point `:seon.message/to` away from the agent under test, a
cardinality-one ref, so the transaction RETRACTS an edge that pointed at the
subject while `:seon.message/inbox` still points at it
(`web_context_test.clj:24`).

The sibling test measures the consequence on a page whose subject is
`[:seon.ns/name seon.flow]` (batch 75,
`tmp/orchestrator/gate-results/batch-75/named.md:265`): `:discovery` 28 → 29
and `:invocation` 86 → 131, with `:observation` unchanged at 1.

## Why it is wrong

A bare `:seon.agent/id` is a half-agent. The one real creation path
(`src/seon/cluster/agent.clj:122-148`) asserts namespace, plan, settings,
runtime and a steward call. A fixture that asserts the identity alone puts a
partially populated agent in front of every derivation that enumerates
agents, so a transaction the test calls "unrelated" is observable by
construction — the opposite of what both tests claim to measure.

## What a repair must preserve

`test/seon/render/web_test.clj:1063` asserts that the same transaction STILL
advances a render pass, so the replacement cannot simply be a datom no
interest intersects (a bare `{:db/doc "..."}` may wake nothing). The
replacement needs one datom that offers a render wake and changes nothing the
inspected namespace page renders — chosen with the test JVM, which the
triage assignment that found this excluded.

## Related

The production defect found in the same triage — an unrelated commit
re-walking every agent history because an ordinary read result containing an
instant could not be digested — is fixed in `dfd2aae54`, with the measurement
in
[docs/prds/context-generation/research/web-context-rewalk-2026-09-16.md](../../prds/context-generation/research/web-context-rewalk-2026-09-16.md).
Re-gate `seon.render.web-context-test` and `seon.render.web-test` on top of
that fix before deciding how much of these two failures the fixture still
owns.
