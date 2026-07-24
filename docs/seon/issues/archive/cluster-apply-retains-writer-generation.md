---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, flow]
---

# Release the apply writer before returning success

## Problem

A successful `bin/seon cluster apply default` returned while its temporary
writer generation remained alive, so immediate startup correctly refused an
uncoordinated replacement.

## Resolution

Resolved by `9e061cad6`. Apply records the exact writer process record returned
by startup ownership and, only when it acquired that writer, drains that exact
generation before publishing the applied manifest. A pre-existing converged
writer remains with its existing owner, and the strict managed-process
replacement refusal remains unchanged.

## Proof

On 2026-07-24, explicit apply completed in 38.61 seconds and the immediately
following `bin/seon up` admitted all five processes in 36.60 seconds without
an intervening `down` or managed-process conflict.
