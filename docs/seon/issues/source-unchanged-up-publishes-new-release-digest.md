---
type: issue
status: open
severity: blocker
tags: [issue, operator, database]
---

# Keep the release digest stable across source-unchanged startup

## Problem

Successive `bin/seon up` invocations on one unchanged source tree publish
different release digests. The strict start gate correctly refuses each digest
that differs from the database's applied release, making five-process startup
unreachable by repeated apply.

## Evidence

- Fresh apply stamped release `119f1314a9afd547…`.
- The next source-unchanged startup composed `74d00961ae4e6a70…` and refused
  after 73.84 seconds.
- Re-apply converged config without recreating root and stamped
  `74d00961ae4e6a70…` in 18.88 seconds.
- The next source-unchanged startup composed `2da2a569d77a69f6…` and refused
  after 73.80 seconds.
- The config manifest digest stayed
  `adc1e407f04100f1…` throughout; no source file was edited between the latter
  apply and startup.

## Owner

The canonical artifact publication and release-digest derivation used by
cluster apply and startup. The digest must derive from stable selected inputs
and published bytes, not per-invocation output or process identity.

## Acceptance

- Two source-unchanged publications produce the same release digest.
- Apply stamps that exact digest and an immediate startup admits it.
- Any real source or selected artifact change still changes the digest and
  triggers the strict apply remedy.
