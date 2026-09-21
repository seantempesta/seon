---
type: issue
status: open
severity: friction
tags: [issue, test, schema, publication]
---

# Platform fixtures refuse function-schema acquisition

The test-system duration baseline, recorded run `7aa326277331`, used HEAD
`9c02384c8` plus the duration reporter and schema declaration. Its published
base was 15 commits behind HEAD. Fourteen tests errored with
`:malli.core/invalid-schema`, including publication in
`seon.cluster.source-test`, `seon.cluster.source-lineage-test`, and
`seon.cluster.source-evidence-test`, and nested runner execution/recording.

The publication stack enters `seon.schema/projection-registry` at its
function-contract lookup (`src/seon/schema.clj:502`), through
`malli.registry/schema`, `malli.core/function-schema`, and Malli's schema
lookup. Nested execution also reaches `seon.instrument/compiled-wrapper`
while acquiring a projection value. The printed exception lacks the
unresolved schema identity. Neither the stale export nor the duration
change is established as the cause by that stack.

Acceptance: retain the failing identity and supplied definition map at the
Malli lookup, fix the mismatched declaration at its owner, and run the named
publication and recorder regressions under contracts. The small publication
fixture work is separately assigned to the test-system lane. See the
[landing note](../../prds/steward-platform/research/test-system-fork-2026-09-23.md)
for the independent duration failures and exact baseline selection.

## Nested runner identity established — 2026-09-23

The complete exception in both `no-double-execution` and
`platform-claims-and-original-bounds-govern-bulk` identifies
`:seon.test/time-limit-ms` as both `:schema` and `:form` in Malli's
`:malli.core/invalid-schema` data. The failing function is the loaded
`seon.test.runner/duration-failures`; its named input declaration is absent
from the older fixture projection. This is not an unresolved identity inferred
from a stack.

The reporter now carries its projection in its existing report options. Host
nested-run fixtures retain the entering host projection, while the SCI test
carries reporter options separately from database custody. The named schema
error disappeared in `ac51f1a8b816`, and both duration regressions passed.
The publication members of this issue remain open and are not modified by
this correction.
