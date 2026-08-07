---
type: issue
status: open
severity: major
tags: [issue, schema, performance]
---

# `packaged-forms` re-reads and re-merges every schema resource on every call

## Problem

`seon.schema/candidate-forms` falls through to
`seon.schema.edn/packaged-forms` whenever no projection, projection state, or
candidate overlay is bound (`src/seon/schema.clj:590-599`). That fallback is
`(::forms (resource-population default-resource))`
(`src/seon/schema/edn.clj:337-341`), which for EVERY call re-lists the schema
resource directory, re-reads all ~100 EDN files, re-merges them with the
duplicate-attribute check, and re-derives the config forms. Nothing memoizes
it.

So `schema/schema-definition` — a function whose name and docstring read like
a map lookup — is a full filesystem-and-merge pass whenever it is called
outside a projection binding. Any caller that asks per item pays that cost per
item.

## Evidence

Found 2026-08-07 by seon.env Phase 1 lane W1 while diagnosing a wedged
`seon.cluster.loop-test`. The namespace normally takes 91-161 seconds
(`tmp/test-runs/bare-run-1634.log:616`,
`tmp/test-runs/bare-verify-1710.log:615`); it was still running at 15 minutes.
A virtual-thread-aware `jcmd Thread.dump_to_file` caught the responsible
virtual thread mid-merge:

```
#81 "" virtual RUNNABLE
    at clojure.lang.PersistentHashMap.assoc
    at clojure.core$merge …
    at seon.schema.edn$merge_schema_resources …
    at seon.schema.edn$resource_population …
    at seon.schema.edn$packaged_forms …
    at seon.schema$candidate_forms …
    at seon.schema$schema_definition …
    at seon.config$registration_defaults$fn__73981.invoke(config.clj:143)
    at clojure.core$keep$fn__8695 …
```

Note the caller: `seon.config/registration-defaults` calls
`schema-definition` inside a `keep` over config keys, so ONE call to it is
`(count keys)` complete resource merges. W1's own `seon.env/members` had the
same shape and was the trigger; W1 fixed its own caller by reading the
declaration once (`204e94421`), which returned the namespace to seconds. The
underlying owner is untouched.

## Owner

`seon.schema.edn/resource-population` / `packaged-forms`, with
`seon.schema/candidate-forms`.

## Suggested repair

The resource population is a pure function of the schema resource directory's
bytes, which do not change while the process lives (a source edit publishes
through `bin/seon init`, not by mutating a running JVM's classpath
resources). Derive it once per resource location and hang the derivation off
that value, keyed by the complete resource identity — never a "current forms"
slot, and never a per-call read.

Also worth deciding: whether a bare `schema-definition` with no projection
bound should fall back at all, or say plainly that the caller must supply a
projection. A silent fallback that costs a full disk merge is the kind of
quiet wrong answer the accretion rules warn about.

## Acceptance criteria

- `schema/schema-definition` called N times with no projection bound performs
  one resource population, not N.
- A recorded measurement in the owning PRD showing the before/after cost, so
  the regression is detectable.
- `seon.config/registration-defaults` no longer performs a resource merge per
  config key.
