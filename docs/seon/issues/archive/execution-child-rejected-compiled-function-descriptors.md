---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow]
---

# Execution child rejected compiled function descriptors

## Problem

The execution artifact supplied one closed map from qualified symbols to
descriptors containing the compiled function and its database-pinning policy.
Child startup instead required every map value to be a bare function, so a real
Bun child rejected the only runtime composition before reading startup data or
opening its database session. Fake host-process tests did not execute this
entrypoint and therefore missed the mismatch.

## Owner

`seon.execution` owns the one child data contract. A compiled function has one
representation: a closed descriptor containing `:seon.execution/compiled-function`
and `:seon.execution/pin-database?`.

## Resolution

The child entrypoint now validates the same closed descriptor shape consumed by
invocation dispatch. Bare callables, missing pinning policy, and extra fields
are rejected. The focused execution contract proves the runtime descriptor is
accepted without adding another child map or compatibility form.
