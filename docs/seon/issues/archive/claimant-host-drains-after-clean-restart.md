---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, operator]
---

# Claimant host drains after clean restart

## Problem

A clean `bin/seon restart` reconstructed the claimant host against an existing
paged database, but the host then drained. The recovered report appeared to
contain successful schema rows and made an opaque forward-reference failure
look likely.

## Evidence

The full isolated reproduction is retained at
`tmp/orchestrator/hostdrain-report.edn`. Its two logged member results contain
2,377 successful schema rows and 927 successful contract rows. Replaying those
rows directly through `seon.schema/projection-from-rows` succeeded, disproving
schema reconstruction and declaration order as the failing boundary.

The old host acquisition actually sent three `execute-many` members but
reported only the first two on member failure. The omitted member read every
`:seon.fn/source` value. That population exceeded the batch's aggregate result
weight on restart even though the schema and contract reads succeeded.

Commit `c2c5faeff` replaces the batch with the existing bounded acquisition
shape: freeze one immutable database value, page identity datoms through AEVT,
then query each variable-size form row separately under a 60,000-byte
per-request bound. The total committed population is no longer capped or
required to fit one response. Any failed read now names its stage, identity
attribute, form attribute, and nested database error.

The same commit makes a genuine missing Malli reference name the registering
schema key, missing reference key, and missing reference namespace in both the
message and error data.

Focused proof:

- JVM: 23 tests, 187 assertions, zero failures/errors.
- CLJS: 21 tests, 148 assertions, zero failures/errors.
- Live reset boundary: fresh `hostdrain` reset/start, clean restart, and
  `bin/seon status` reported `Seon ready` with watcher, writer, host, pod, and
  web-render all alive. The host was not drained. The full transcript is
  `tmp/orchestrator/hostdrain-gate.log`; the isolated cluster then shut down
  cleanly.

## Owner

Claimant host admission in `seon.host.context`, using the one portable
`seon.db` query and index-page mechanism, and complete-projection validation in
`seon.schema`.

## Acceptance

- A clean restart keeps the claimant host alive and ready.
- Committed acquisition is complete at one immutable database value without
  an aggregate corpus-size response.
- Read failures name their exact acquisition population and stage.
- Genuine missing schema references name the owner, missing key, and
  namespace.
- The isolated five-process live gate passes and the cluster shuts down
  cleanly.
