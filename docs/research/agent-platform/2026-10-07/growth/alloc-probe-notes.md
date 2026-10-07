# Allocation per ordinary form (root probe, 2026-10-07 ~05:30Z, default on 42e569d9d, quiet system)

Measured from a JVM-mode call wrapping `seon.mcp/call` for agent `alloc-probe` (namespace `alloc-probe.scratch`,
warm: the agent's first use was a separate earlier call). Allocation is
`com.sun.management.ThreadMXBean/getTotalThreadAllocatedBytes` before and after: JVM-wide, all threads.

| form | ms | allocated MB | reply chars |
|---|---|---|---|
| `(+ 1 2)` | 145 | 151 | 114 |
| `(defn plain-a [x] (inc x))` | 537 | 843 | 178 |
| `(defn contracted-a {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))` | 407 | 523 | 185 |
| `(defn contracted-b {:malli/schema [:=> [:cat :int] :int]} [x] (dec x))` | 408 | 472 | 185 |
| `(+ 1 2)` again | 176 | 206 | 114 |

The post-restart notes' "contracted defn: 982 ms, 2.49 GB" is the same class, inflated by concurrent work.
A trivial form allocates ~150 MB and a one-line defn ~0.5-0.8 GB: the work is not proportional to the change.

JFR `jdk.ObjectAllocationSample` (throttle 5000/s) during `(defn plain-b [x] (inc x))`: 323 MB sampled weight,
1,785 samples. By first seon frame: `seon.db/vector-query-call` 68 MB (Long boxing, PersistentHashMap, datahike
persistent-set cursors), `seon.schema/deleted-predicate-functions` 24 MB, `seon.db/declaration-content-key` 9 MB,
`seon.schema/projection-entities` 5 MB; 154 MB with no seon frame, of which one sample weighted 116 MB in
`konserve.filestore.BackingFilestore/-create-blob` -> `core.async/chan` (a sampling artifact: the weight is the
thread's allocation since its last sample, so it is not attributable). JFR stack depth is 64, so deep stacks lose
their seon frames. Next: exact per-thread deltas (`getThreadAllocatedBytes` per thread id) and seon.profile cells
around one defn, then the callers of vector-query-call and deleted-predicate-functions.

Also seen: the value printer cuts 30-character identifier strings nested 3 deep in a small vector (task
`seon.render.value/a-short-identifier-string-is-cut-at-its-first-chunk`), making small diagnostic data unreadable.
