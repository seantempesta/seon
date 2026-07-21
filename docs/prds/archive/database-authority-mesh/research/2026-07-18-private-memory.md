---
type: research
status: active
tags: [research, database, pod, cljs]
---

# Private-memory baseline and laptop budgets

## Decision

Seon is not currently paying a 500--900 MiB private-memory cost for every
agent. That conclusion came from adding macOS RSS, which charges every process
for resident shared executable pages and also includes reclaimable pages.
The source-frozen execution package measured here retained 201--221 MiB of
physical footprint per live child; the established four-child load on the same
architecture measured 231--238 MiB per child. Dormant agents retain no child.

The larger laptop concern is now the fixed database-authority plus pod cost.
After an explicit JVM full collection, the initialized database authority used
548 MiB and the Bun pod used 316 MiB of macOS physical footprint: 864 MiB before
any execution child. The JVM temporarily reached 1,190 MiB while its G1 heap
expanded under startup and work, despite retaining only 71--83 MiB of live Java
heap after full collection. Reducing and bounding that fixed JVM expansion is
higher impact than weakening exact ClojureScript semantics in children.

## Dependency ledger

| Dependency | Selected source/runtime | Relevant evidence |
|---|---|---|
| Seon runtime | source-frozen development artifact started 2026-07-18 13:39 UTC | `bin/seon status` reported watcher, writer, and pod ready; writer PID 59665 and pod PID 60336 were the workloads, not their Python containment owners |
| Bun used by Seon | release 1.3.14 at `/Users/sean/.bun/bin/bun` | pod and execution children in the process table |
| Vendored Bun | `reference-code/bun` at `d8ecf098572e2b8265b23e40c04efb4067e516cc`, debug version 1.4.0 | `src/spawn_sys/spawn_process.rs` uses Darwin `proc_pid_rusage(..., RUSAGE_INFO_V0, ...)`; `src/jsc/bindings/c-bindings.cpp` documents `TASK_VM_INFO.phys_footprint` |
| JVM | OpenJDK 26.0.1, G1, `-Xmx2g` | ordinary database-authority launch descriptor and `jcmd GC.heap_info` |
| macOS accounting | `/usr/bin/footprint`, `/usr/bin/vmmap`, `/bin/ps` | kernel physical-footprint, dirty, compressed/swapped, clean, reclaimable, RSS, and region summaries |

The vendored Bun source makes the metric distinction explicit. Its live child
API currently exposes `rusage_info_v0.ri_resident_size`, which is RSS. Bun's
own self-memory accessor instead uses `TASK_VM_INFO.phys_footprint`, described
in source as Activity Monitor's value: dirty plus compressed, excluding
reusable pages. On Linux the analogous accessor uses proportional set size
from `smaps_rollup`; on Windows it uses private commit. Therefore
`Subprocess.resourceUsage({live: true})` is excellent transient failure
evidence, but its macOS RSS must not be used as a per-agent capacity charge.

## Method

The running operator was not restarted or modified by this lane. Another
coordinated source-frozen `bin/seon up` completed while measurement began. Once
it reported ready, measurements targeted the workload PIDs recorded in the
operator descriptors:

```text
watcher workload 58643  java ... shadow.cljs.devtools.cli watch
writer workload  59665  java ... seon-database-server-standalone.jar
pod workload     60336  bun out/client/main.js

```

The watcher is reported separately because it is a development compiler, not
a production resident. `ps rss` supplied the deliberately non-additive RSS
comparison. `footprint -p PID --swapped` supplied physical footprint and its
dirty/clean/reclaimable categories; `vmmap -summary PID` supplied the region
breakdown. `jcmd 59665 GC.heap_info` measured JVM reserved, committed, and used
heap. One `jcmd 59665 GC.run` established retained idle heap after startup and
again after a real agent request; it changes collection timing, so both the
natural pre-collection high water and post-collection retained values are
reported.

One ordinary public `POST /agents/run` request created a real agent, compiled
its prompt, evaluated two forms, committed its message and completion, and
returned `memory measurement green` in 10.789 seconds. While the request was
live, children were discovered only as direct children of the pod and measured
with the same kernel tool. All disappeared through ordinary idle retirement;
no process was killed and no lifecycle owner was bypassed.

## Measurements

### Fixed development and production-relevant processes

| Process/state | RSS | Physical footprint | Peak footprint | Material retained regions |
|---|---:|---:|---:|---|
| Shadow watcher after build | 2,217 MiB | not sampled | not sampled | development-only compiler; exclude from production |
| Writer, natural startup high water | 1,211 MiB | 1,189 MiB | 1,189 MiB | G1 committed 789 MiB, used 523 MiB |
| Writer, first full collection | 674 MiB RSS | 652 MiB | 1,190 MiB | G1 committed 260 MiB, used 71 MiB |
| Writer, post-agent full collection | 677 MiB RSS | 548 MiB | 1,190 MiB | G1 committed 287 MiB, used 81 MiB; 416 MiB dirty VM allocation and 104 MiB dirty malloc-small |
| Bun pod, ready | 1,129 MiB RSS | 350 MiB | 845 MiB | 298 MiB dirty WebKit/JSC malloc; 733 MiB reclaimable |
| Bun pod, after agent request | 1,143 MiB RSS | 316 MiB | 845 MiB | 262 MiB dirty WebKit/JSC malloc; 769 MiB reclaimable |

The striking Bun row is why RSS cannot be added. The ready pod had about
1.1 GiB RSS, but only 350 MiB physical footprint; its `vmmap` included roughly
408 MiB resident `__LINKEDIT` and hundreds of MiB of reclaimable WebKit/JSC
pages. The writer-plus-pod post-collection fixed charge was 864 MiB, while
their summed RSS was roughly 1.8 GiB.

The JVM result also needs care. Full collection reduced live heap from 523 MiB
to 71 MiB and later from 199 MiB to 81 MiB. Physical footprint did not update
instantaneously; a subsequent kernel sample fell from 1,190 to 652 MiB and,
after the real request and collection, to 548 MiB. This is allocator/GC
expansion and reclamation behavior, not 1.2 GiB of live Datahike data.

### Incremental execution children

| Child observation | RSS | Physical footprint | Peak footprint |
|---|---:|---:|---:|
| ready child | 423 MiB | 201 MiB | 354 MiB |
| second retained child | 480 MiB | 216 MiB | 350 MiB |
| real request child after prompt/eval | 578 MiB | 221 MiB | 347 MiB |
| established four-agent concurrent load | 851--859 MiB | 231--238 MiB | 423--424 MiB |

The first two children were other ordinary live work sharing the pod during
the sample; they are useful ready/retained observations, not asserted as fresh
empty processes. The third was the request owned by this measurement. The
four-agent row is the immediately preceding source-frozen load recorded in the
roadmap after the same two architectural memory cuts. All values agree on the
important scale: approximately 0.2--0.24 GiB private physical cost per active
agent, not 0.5--0.9 GiB.

At the instant three children were present, the aggregate footprint of writer,
pod, and children was 1,763 MiB. After children retired and the writer
collected, the fixed footprint settled to 864 MiB. A representative four-agent
steady charge is therefore about 1.8 GiB; simultaneous measured child peaks
and JVM expansion can temporarily push the application materially higher.

## Laptop budgets

These are admission budgets, not marketing claims. They should become measured
graduation assertions on macOS physical footprint, Linux PSS, and Windows
private commit rather than RSS.

| Budget | Target | Current evidence | Status |
|---|---:|---:|---|
| Fixed writer + pod, collected idle | at most 768 MiB | 864 MiB | misses by 96 MiB |
| Writer collected idle | at most 448 MiB | 548 MiB | misses by 100 MiB |
| Pod collected/settled | at most 320 MiB | 316 MiB | meets |
| Ready execution child | at most 210 MiB | 201--216 MiB | borderline |
| Active child retained | at most 250 MiB | 221--238 MiB | meets |
| Active child peak | at most 450 MiB | 347--424 MiB | meets |
| Four active agents, steady application | at most 2.0 GiB | about 1.8 GiB | meets |

Recommended defaults until memory-pressure graduation exists:

- an 8 GiB laptop admits two simultaneously active execution children;
- a 16 GiB laptop admits four;
- a 32 GiB machine admits eight;
- dormant and idle-retired agents do not count because they retain no process;
  and
- admission should respond to OS memory pressure and measured physical/private
  charge, queueing new turns rather than allowing swap to destroy latency.

These caps preserve real parallelism while leaving headroom for the browser,
model runtime, editor, filesystem cache, and short-lived child/JVM peaks. They
are intentionally conservative; load evidence can raise them.

## Highest-impact next measurements

1. Run the unchanged writer under measured 512, 768, and 1,024 MiB maximum
   heaps against initialization, four-agent load, query reuse, and complete
   writer tests. The live retained heap was only 81 MiB, while the current
   2 GiB maximum allowed a 1.19 GiB footprint high water. Select the smallest
   limit that does not increase GC pause or reject legitimate heavy queries.
2. Record JVM allocation rate, GC pause quantiles, committed heap, live heap,
   and physical footprint over a 30-minute repeated agent/query workload. A
   single forced collection cannot establish a leak slope or production GC
   cadence.
3. Add platform-specific capacity telemetry at the parent: physical footprint
   or task footprint on macOS, PSS on Linux, private commit on Windows. Keep
   the existing Bun live RSS as crash evidence, but do not use it alone for
   admission.
4. Measure the production package without Shadow. The watcher costs over 2 GiB
   and makes development look much heavier than the application users ship.
5. Retain the exact ClojureScript compiler and process-isolation contract.
   Current children already meet the active budget after removing duplicated
   pod control-plane dependencies; SCI or split-brain compilation would trade
   semantic safety for a now-secondary memory gain.

The current architecture is viable on ordinary 16 GiB laptops and plausibly
viable on 8 GiB laptops with two-way active concurrency, but the fixed JVM high
water is not yet disciplined enough to call the modest-hardware gate graduated.
The next large win is a measured database-authority heap/capacity policy, not
micro-optimizing package bytes or replacing the child evaluator.
