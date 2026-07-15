---
type: research
status: completed
tags: [research, prd, database, flow, agent]
---

# Gemini isolation and IPC audit — 2026-07-15

## Verdict

The attachment correctly identifies the failure-domain value of OS processes,
but its recommended per-database native process architecture conflicts with
Seon's primary optimization: compute indexes, query results, encoded values,
runtime infrastructure, and JVM overhead once across many databases and
readers. Its numeric memory/startup/IPC table has no reproducible provenance and
must not drive the PRD.

Retain three concepts:

- process isolation is a real optional containment boundary;
- Linux process groups/cgroups can enforce limits unavailable inside one JVM;
  and
- the authority protocol must remain neutral enough to move a database between
  authority processes later.

Do not add Chronicle/cues, ZeroMQ/JNI, gRPC/protojure, a Clojure master process,
or one Graal native process per database without a measured failure that the
current direct UDS authority cannot solve.

## Evidence basis

- Attachment audited:
  `/Users/sean/.codex/attachments/7a891eb8-2e9c-4ef2-97ca-d115380898ac/pasted-text.txt`.
- Seon pins Datahike `9ada755087228e10cfb179fa5779ce227a6ed220`,
  Konserve `b5c99bc02a7175652a610324215288b78551801f`, Proximum
  `9846d3e79e1aee48474bc876d3d563d7137209c6`, Transit CLJ 1.0.333,
  Clojure 1.12.0, and OpenJDK 26 launch flags in `deps.edn:13-54`.
- Datahike has an upstream-style `:native-cli` alias at
  `reference-code/datahike/deps.edn:133-151`. It builds `datahike.cli`; it is not
  proof that Seon's current writer, registry, UDS, Konserve backends, Proximum
  secondary index, and shutdown/conformance path build or behave correctly as a
  native image.
- Proximum uses Java FFM `MemorySegment`, mapped buffers, explicit close, and
  branch mmap files (`reference-code/proximum/src/proximum/hnsw.clj` and
  `reference-code/proximum/src/proximum/yggdrasil.clj`). Native-image support for
  the exact dependency closure therefore requires a real build and behavioral
  proof, not inference from Datahike's CLI alias.
- `babashka.process` is already Seon's operator primitive in
  `script/seon/dev/{config,cli,process,artifact,changed_test,restore_state}.clj`.
  The locally selected library is 0.4.14 and wraps `ProcessBuilder`, streaming,
  and JDK exit callbacks in
  `reference-code/babashka-process/src/babashka/process.cljc:245-504`.
- Bun source and prior Seon measurements establish `Bun.spawn` as the runtime
  owner for Bun agent children. Packaging the database as JVM/native and
  supervising children are separate decisions.
- None of cues/Chronicle Queue, ZeroMQ/JeroMQ/JNI, protojure, or gRPC is in the
  Seon dependency graph. Bun's source repository contains gRPC compatibility
  tests, not a selected Seon gRPC runtime.

GraalVM documents that Native Image's closed-world analysis needs reachability
metadata for reflection, resources, JNI, and other dynamic features
([dynamic features](https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/)).
It also states that native executables retain a managed heap and GC, with memory
outside the heap; low footprint is a tradeoff, not a fixed process size
([memory management](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/MemoryManagement/)).

## Quantitative claims audit

The attachment claims 100–150 MiB per JVM, 15–20 MiB per native process,
5–10 ms native startup, and microsecond IPC ranges. It provides no command,
hardware, dependency closure, allocation state, database size, GC, distribution,
or source supporting those values.

One isolated cold-process probe on the current machine used:

```bash
/usr/bin/time -l clj -M:writer -e '<form>'
```

| Loaded form | Wall | Maximum RSS |
|---|---:|---:|
| Print only on writer classpath | 0.27 s | 100.1 MiB |
| Require `datahike.api` | 7.39 s | 1,114.4 MiB |
| Require `seon.db.writer` | 7.16 s | 1,152.5 MiB |

These are single cold compiler/class-loading maxima under Seon's `-Xmx2g` G1
policy, not steady-state database-server RSS. They neither validate Gemini's JVM
number nor predict native-image footprint. They do demonstrate why only the
exact built writer with empty, loaded, and stressed databases can answer the
question. `native-image` is not installed in this environment, so no native
number was fabricated.

## Useful, reject, or experiment

| Proposal | Classification | Reason |
|---|---|---|
| Process containment as an available authority boundary | Useful | Contains native fatal faults, JVM OOM, and GC pauses to a shard; protocol neutrality should preserve it. |
| One process per database | Reject as default | Duplicates runtime, query/cache owners, connection machinery, encoded values, and shared computation; modest-hardware density gets worse. |
| A small number of authority shards | Experiment | May trade some shared efficiency for bounded failure domains without paying one runtime per cluster. |
| Graal native authority | Experiment | Existing Datahike CLI alias is encouraging but not the Seon writer; exact closure, behavior, memory, throughput, FFM/mmap, and GC remain unproved. |
| `babashka.process` as Clojure master architecture | Reject | It already correctly belongs to the operator. Adding a master JVM would duplicate Bun supervision and confuse orchestration with packaging. |
| `Bun.spawn` for agent children | Useful | Native runtime lifecycle owner with measured child isolation; unrelated to how the authority is packaged. |
| Chronicle Queue/cues for query IPC | Reject | An append-only persisted mmap queue adds another journal, reclamation protocol, serialization, filesystem/page-fault behavior, and JS reader implementation. It does not expose Datahike indexes zero-copy. |
| ZeroMQ/JNI over `ipc://` | Reject | Official ZeroMQ IPC is implemented over Unix-domain sockets; it adds ZMTP queues/patterns and native/JNI failure surface over Seon's already measured native UDS. |
| gRPC/protojure over UDS | Reject locally | Adds HTTP/2, protobuf/code generation, adapters, dependencies, and another schema authority without a demonstrated need. Reconsider only for a remote public API whose ecosystem benefit outweighs cost. |
| Direct framed native UDS | Useful baseline | Already supports the data protocol, lowest measured local overhead, direct child isolation, explicit backpressure, cancellation, and shared encoded results. |
| OS resource enforcement | Useful but platform-specific | Linux cgroup v2 controls CPU and memory by process; there is no equivalent thread-level enforcement inside one JVM. macOS/iOS require different containment and entitlement work. |

Chronicle Queue is a memory-mapped append-only journal, not a free shared-object
heap; its official material describes mapped files, length-prefixed documents,
and persisted sequential access
([Chronicle Queue](https://chronicle.software/tech-hub/technical-information/chronicle-queue/advanced)).
ZeroMQ's official IPC transport itself depends on Unix-domain-socket-capable
operating systems ([ZeroMQ IPC](https://api.zeromq.org/4-2%3Azmq-ipc)). Those
facts remove the claimed categorical advantage over Seon's direct native UDS.

## Shared-JVM risks, stated honestly

One JVM is a common fatal-fault domain:

- an unrecoverable process OOM can terminate or destabilize every hosted
  database;
- GC work and stop-the-world phases are process-wide;
- a fatal FFM/JNI/native fault can terminate the authority; and
- OS schedulers cannot hard-cap one database's threads or heap independently.

That does not imply one process per database. First bound per-database admitted
work, query/result/cache weight, response bytes, write queues, Proximum capacity,
and fair scheduling. Fail ordinary query/transaction errors as data. Then
measure the residual fatal risk and choose an authority shard count. Each shard
can still share computation across many databases and readers.

Linux cgroup v2 provides hierarchical CPU and stateful memory controllers over
process groups ([kernel documentation](https://docs.kernel.org/admin-guide/cgroup-v2.html)).
It is deployment-specific, not portable Clojure policy. macOS App Sandbox limits
resource and data access through entitlements but is not a general per-helper
CPU/RAM quota system; Apple recommends XPC for privilege separation in sandboxed
apps ([App Sandbox](https://developer.apple.com/documentation/security/app-sandbox),
[helper isolation](https://developer.apple.com/library/archive/documentation/Miscellaneous/Reference/EntitlementKeyReference/Chapters/EnablingAppSandbox.html)).
iOS also controls app memory dynamically and may terminate processes; available
memory is advisory and changes over time
([Apple memory API](https://developer.apple.com/documentation/os/os_proc_available_memory)).

## Only experiments worth retaining

### Exact native-image feasibility gate

Build the exact Seon authority closure with a pinned GraalVM version. It must
pass protocol conformance, Datahike query/transaction/history/branch/release,
Konserve memory and file backends, Proximum FFM/mmap lifecycle, listener,
shutdown, and corruption/recovery tests. Compare cold startup, idle/loaded/peak
RSS, p50/p99 query and transaction latency, throughput, allocation, binary size,
build time, and diagnostic quality against the pinned JVM. Stop immediately if
the exact image cannot pass semantics; do not design around generic CLI demos.

### Authority-shard density and blast-radius gate

Compare one JVM against 2 and 4 authority processes over the same 8/32 database
workload. Preserve one writer per database and parallel reads. Measure aggregate
RSS/CPU, cache reuse, shared encodings, p99, GC interference, restart recovery,
and how many databases fail under an isolated authority crash. This directly
prices containment without assuming one process per database.

### Resource-pressure gate

On Linux, place an authority shard in cgroup v2 and verify CPU weight/max,
memory.high/max, OOM behavior, mmap/page-cache accounting, and supervisor
recovery. Separately prove application-level per-database admission inside a
shared authority. On macOS/Tauri, research signed helper/XPC and sandbox
entitlements as packaging work; do not promise Linux cgroup semantics.

### IPC stop rule

Complete native UDS linear framing, paging, cancellation, direct connections,
and shared encoded bytes first. Retain another IPC experiment only if profiling
shows transport—not Datahike query, encoding, scheduling, or result volume—is a
material bottleneck. Chronicle, ZeroMQ, and gRPC currently fail this stop rule.

## PRD consequence

The target remains one protocol-defined authority service capable of hosting
many databases, with direct Bun children and shared Datahike computation. The
protocol must allow databases to be assigned to a small number of authority
shards later. Sharding is a deployment/failure-domain mapping, not a different
database API, cache, coordinate, or subscription system.

Sean's future decision is therefore not “single JVM or one process per DB.” It
is the measured shard count and packaging—JVM or proven native image—that gives
acceptable blast radius without throwing away density and shared work.
