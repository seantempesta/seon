---
type: issue
status: resolved
severity: blocker
tags: [issue, test, schema, class/p1]
---

# The canonical fixture base cannot be populated without a bound projection

## Resolution (2026-09-16, steward-platform lane)

Fixed at the population owner in `src/seon/cluster.clj`: `populate-source!`
and `accrete-schema-population!` now derive the declaration projection from
the forms they already resolve and HAND it to every transaction they make
(`schema/call-with-projection`), preferring a projection the caller already
handed (`refresh-source!`'s, or a cluster's advanceable projection state) so
publication and in-place development adoption keep the projection they own.
The fixture's sealing write moved after `db/carry-connection-projection-state!`
in `test/seon/test_support.clj`, so it validates against the projection its
connection carries rather than one bound around the call, and the base derives
that projection ONCE.

Live evidence (adopted `default` JVM, 2026-09-16): a fresh in-memory store
populated through `cluster/populate-source!` with no projection bound anywhere
completed in 26.8 s (2,661 schema rows, 4,753 program rows), the sealing
transaction committed, and the resulting database answers
`db/carried-projection`. Regression:
`seon.test-support-test/the-canonical-base-populates-from-an-empty-store`.

On 2026-09-16 at `52044b4f4`, in the adopted `default` JVM,
`seon.test-support/create-base` called with no published base refuses:

```
:seon.boot/offense
#:seon.boot{:population :seon.schema/declarations,
            :result #:seon.error{:kind :seon.schema/missing-projection,
                                 :message "This operation requires a carried schema projection."
                                 :data {:seon.db/operation seon.db/transact!
                                        :seon.schema/missing-projection true}}}
```

preceded by `WARN seon.db/projection-fallback caller= seon.db/transact!
missing-projection count=1`.

`create-base` runs `populate-database!`, which calls `cluster/populate-source!`
→ `accrete-schema-population!` (`src/seon/cluster.clj:1347`). That function
binds `schema/call-with-forms` but no projection, and its declarations
transaction now reaches a `seon.db/transact!` that requires a carried
projection. Wrapping the same call in
`(schema/call-with-projection (schema/declaration-projection
(schema.edn/packaged-forms)) ...)` builds the base in 18.8 s, which isolates
the missing input to the population path rather than to Datahike or the store.

`seon.test-support/database-base` is a `delay`, so an already-realized base in a
long-lived JVM hides this; a NEW test JVM realizes it during its first
`with-database` and would refuse at fixture setup. This observation is from the
hot-reloaded `default` JVM only — a bounded lane may not launch a test JVM, so
the fresh-process case is unverified and is the first thing to check.

Acceptance: the canonical fixture base populates through the same seam
production publication uses, with the projection handed to the operation rather
than bound ambiently by the caller, and one regression proves a base builds from
an empty store. Do not weaken the write validation.
