---
type: issue
status: open
severity: medium
created: 2026-10-20
tags: [issue, cache, memory, projection, review3-astra]
---

# Projection query derivations grow outside the cache budget

`seon.schema/with-compiled-cache` installs `clojure.core.cache.wrapped/basic-cache-factory` on every projection. `seon.schema.internal/projection-cache-value` retains values there; `seon.db/lowered-where` keys that holder by query clauses and normalized bound inputs. Those keys are not a finite schema population. `clojure.core.cache/BasicCache` adds each miss to its map and has no automatic eviction or byte budget. The holder's growth does not update the central cache's weight.

On `06614f6a8`, take the real carried projection of a fixture database. Call `seon.db/lowered-where` on two batches of 1,000 valid clauses, varying only a variable symbol. No database writes or projection replacements are involved:

| Observation | Before | After 1,000 | After 2,000 |
|---|---:|---:|---:|
| Projection-holder entries | 891 | 1,891 | 2,891 |
| Central cache weighed bytes | 312,188,873 | 312,188,873 | 312,188,873 |
| Configured central cap | 512,000,000 | 512,000,000 | 512,000,000 |

The batches took 14.52 and 10.37 ms and allocated 10,759,816 and 10,769,960 bytes. Allocation is not retained size; this probe establishes retained entry growth and missing accounting, not a measured retained-byte total or an actual OOM. A long-lived projection can retain an unbounded number of query identities regardless of the 512 MB Caffeine cap.

Candidate `1990f95ed0559d663c3e789f62c9a787f3bcadb2`, run `2dbfa3faf8a1`, observational test `seon.runtime-review3-astra-capacity-test/projection-query-cache-retention-is-observable`. Its two assertions confirm the measured growth, not a desired retention bound. The source is preserved in the [review probe patch](../../research/agent-platform/2026-10-20/runtime-review3-astra-probes.patch).

The basic holder already exists on `716cc7527`; this is a newly confirmed pre-existing memory risk, not attributed to P7. It is distinct from the wrong-program identity defect in [shared projection derivations](a-branch-and-compiled-code-share-projection-derivations.md). Retention and weighing belong at the cache owner, under the existing configured budget; a second independent unbounded holder cannot establish that budget's guarantee. No default store or JVM was modified by this probe.
