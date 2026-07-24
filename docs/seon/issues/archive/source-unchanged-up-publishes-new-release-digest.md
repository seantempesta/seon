---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, database]
---

# Keep the release digest stable across source-unchanged startup

## Problem

Successive `bin/seon up` invocations on one unchanged tree entered fresh
Shadow compiler sessions and published different client bytes. Separately,
per-invocation launch-envelope generation leaked into process convergence.

## Resolution

Resolved by `9e061cad6`, `965667f4e`, `e37d44f9f`, and `b24b41ae7`.
Startup consumes `artifact/current-manifest` whenever its inputs and outputs
verify, independent of watcher liveness. Reset preserves that byte-verified
artifact owner. Process identity compares launch-envelope data after removing
only its lifecycle generation, both in argv and the serialized launch
descriptor; every material envelope or artifact change still differs.

## Proof

On 2026-07-24, first startup admitted all five processes in 36.60 seconds.
The unchanged second startup reused every ready generation in 9.07 seconds.
The artifact digest before and after was byte-identical:
`885395a2775ed87e1a819689af38d4d6e7c9001e905b360917a3de1478e7570b`.
Both subsequent status checks reported all five processes alive and Seon
ready.
