---
type: issue
status: resolved
tags: [issue, schema, sci, performance]
---

# Equal packaged schema populations missed the shape cache

## Problem

After packaged schema declarations became resource-derived values, each access
could return a new map identity. `seon.schema/shape-projection` still keyed its
cache by object identity, so identity-only admission rebuilt the complete Malli
projection for every admitted node. A composition test made no reporter
progress for five minutes while its main thread remained runnable inside
`malli.registry/fast-registry`.

## Evidence

The virtual-thread-aware dump retained at
`tmp/test-runs/run.z2kARu/tmp/test-liveness/17841-1786056095266.log` places the
main thread in `seon.schema/build-projection`, reached from
`identity-only-projection` while `seon.sci.eval/cluster-ctx` installs program
documentation. The stack never reaches the test's recurring `drive!` loop.

The packaged declaration population is immutable data. Equal maps therefore
describe the same cache generation even when resource reads do not preserve
object identity.

## Resolution

Acquisition binds its immutable database-derived projection around all schema
introspection, so query decoding does not reread packaged resources per nested
value. Outside acquisition, the shape cache compares declaration values with `=`, and
`equal-packaged-populations-reuse-the-shape-projection` supplies equal but
nonidentical maps while counting complete projection builds. Two admissions now
share one build. The recurring `seon.gen.loop-test` composition remains the
end-to-end liveness guard.
