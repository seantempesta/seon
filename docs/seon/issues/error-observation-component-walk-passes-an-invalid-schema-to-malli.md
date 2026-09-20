---
type: issue
status: open
severity: blocker
created: 2026-09-21
tags: [testing, schema, errors]
---

# Error observation component walk passes an invalid schema to Malli

The normally admitted recurrence test
`seon.error-test/recurrence-counting-does-not-require-a-notification-threshold`
records one error before reaching its recurrence/message assertions.
`seon.error/stored-observation` calls `malli.core/properties` at
`src/seon/error.clj:287`; Malli throws `:malli.core/invalid-schema`.

Evidence: `tmp/gate-restructure/recurrence-diagnosis.edn`, including the full
captured task output and terminal result. Durable run `3d58e4a63df7` records
one executed, zero unchanged, zero failures, one error, program digest
`48a85275b5eafb48e31cf247711a72116a4528c61c83e8b1fba6077a9c9967d1`.
This is a shared-tree measurement with foreign edits, not an isolated overlay
proof. The schema value itself is not retained by this reporter, so the
specific member has not been identified. `src/seon/error.clj` has foreign
staged edits and the gate-restructure lane did not modify it.

The same JVM's ten ordinary canonical fixture branches met the structural
budget, and the recurrence fixture applied cluster configuration once.
This error belongs to the observation reader, not fixture rebuilding.
Resolve at the error/schema owner, then run the normally admitted recurrence
test and verify both occurrence and message assertions plus durable recording.
