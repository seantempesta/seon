---
type: issue
status: open
severity: blocker
tags: [issue, test, schema, class/p1]
---

# The canonical fixture base cannot be populated without a bound projection

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
