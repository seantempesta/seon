---
type: issue
status: resolved
severity: friction
tags: [issue, agent, flow]
---

# Retain effective composition-door timeout evidence

## Problem

The composition door correctly derived an absent timeout from database policy,
but its response did not say which effective duration or precedence source it
used. A retained native Inspect sample therefore could not distinguish a caller
override from the database-owned default using its own evidence.

## Owner and acceptance

`seon.web.serve` owns the derived response projection and
`seon_inspect.solver._record_result` owns native sample retention. The response
must report the effective milliseconds plus `request` or `database`; Inspect
must preserve present values without inventing absent ones; focused CLJS and
Python tests must pass.

## Resolution

The resolving commit adds `effective_timeout_ms` and `timeout_source` to the
door's response and retains them as `pod_effective_timeout_ms` and
`pod_timeout_source` in Inspect sample metadata. Absent fields remain absent.
Focused verification passes four CLJS tests/17 assertions and nine Python
solver tests.
