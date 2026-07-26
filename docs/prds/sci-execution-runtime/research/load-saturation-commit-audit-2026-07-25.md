---
type: research
status: active
tags: [research, database, runtime]
---

# Commit-path load saturation source audit, 2026-07-25

## 0. Scope and verdict

This is a source-only grounding lane. It did not start a cluster, create a
database, or run a benchmark. It defines the shortest reproducible probe that
can extend the trustworthy curve past 200 and distinguish commit-loop CPU,
file forcing, heap/GC, and virtual-thread carriers.

The key correction before measuring is that the old numbers and a production
Seon run do not have the same database conditions:

- the old raw transaction harness used a Datahike file database with
  `:keep-history? false` and no fused-index-root setting
  (`8687b4424:src-flow-prototype/src/flow/store.clj:6-10`);
- current Seon uses `:keep-history? true`, `:fuse-index-roots? true`, and
  `:schema-flexibility :write`
  (`src/seon/db/backend.clj:120-125`);
- the pinned Datahike, Konserve, and persistent-sorted-set revisions are
  unchanged since the prototype rescue, but the writer alias moved from
  Clojure 1.12.0 to 1.12.5.

Therefore a new production-shape curve may disagree with the old
`30.25 / 6.73 / 2.04 / 0.73 ms/tx` curve without invalidating either run. The
new measurement wins for the current runtime, and the disagreement and both
condition sets must remain visible.

## 1. Dependency ledger

### 1.1 Selected revisions and runtime

| Dependency | Selected source/version | Grounding |
|---|---|---|
| Datahike | `reference-code/datahike` at `caf526850084a9d5846ccd9ea34251fe411e0d6b` | root gitlink and `deps.edn:25-29`; same gitlink at prototype rescue commit `8687b4424` |
| Konserve | `b5c99bc02a7175652a610324215288b78551801f` | `deps.edn:30-37`; matching `reference-code/konserve` gitlink |
| persistent-sorted-set | 0.4.137, source gitlink `e1a17bbe767c7801e67407c81f64efabfd2f1601` | `reference-code/datahike/deps.edn:20`; root gitlink |
| superv.async | 0.3.50 | `reference-code/datahike/deps.edn:9` |
| core.async on the direct `:writer` basis | 1.6.681 | `clojure -Stree -M:writer`; it enters through Konserve/superv.async |
| Clojure | 1.12.5 | root `deps.edn:6,19`; `clojure -Sdescribe` reports CLI 1.12.5.1654 |
| JDK at audit time | OpenJDK 26.0.1, Homebrew | `java -version` |
| Default writer JVM flags | vector module, native access, unsafe-memory access, G1, `-Xmx512m` | `deps.edn:42-45` |

The historical prototype document states JDK 26.0.1 and Clojure 1.12.0
(`flow-prototype-2026-07-25.md:34-39`). Its root `deps.edn` at `8687b4424`
also pins Clojure 1.12.0. Do not label a new 1.12.5 run a reproduction without
recording that difference.

At audit time the machine was macOS 26.5.2 build 25F84, Apple M5 Max, 18
logical/physical processors, 128 GiB RAM, on local journaled APFS over
Apple Fabric solid-state storage. These are audit-time facts, not future
benchmark conditions; capture them again with the benchmark.

### 1.2 The exact commit mechanism

Datahike's self writer has two stages:

1. One processing go-loop threads the uncommitted database value from
   `old` to each transaction report's `:db-after`
   (`reference-code/datahike/src/datahike/writer.cljc:95-188`, especially
   the call at 115-117 and recur at 181-182).
2. One commit go-loop drains every report currently in the commit queue into
   `txs`, persists only the newest `:db-after`, then returns one report to
   every caller with the same commit ID
   (`writer.cljc:201-268`, drain at 211, commit at 224-226, callback delivery
   at 240-245).

Both transaction and commit queues default to 120,000 entries, while
`commit-wait-time` defaults to zero
(`writer.cljc:78-83,286-306`). Queue-pressure warnings begin above 90% of the
transaction queue and 50% of the commit queue
(`writer.cljc:103-104,173-175`).

The file commit is not one write. `datahike.writing/commit!` flushes pending
index nodes, schema metadata when changed, the immutable commit record, and
the mutable branch head in causal order
(`reference-code/datahike/src/datahike/writing.cljc:423-565`, especially
486-550). Konserve's file backend defaults to `:sync-blob? true` and
`:in-place? false` (`reference-code/konserve/src/konserve/filestore.clj:685-696`).
Each key write forces the blob and, after its atomic move, forces the containing
directory (`reference-code/konserve/src/konserve/impl/defaults.cljc:96-116`;
`filestore.clj:39-49,219-223`). JDK 26 exposes both as `jdk.FileForce` JFR
events.

### 1.3 Current Seon admission and first-party probe owners

The current database executor pipelines ordinary mutations. Only delivery is
intrinsically serialized; allocation transactions opt into serialization
(`src/seon/db/executor.clj:118-123`;
`src/seon/db/writer.clj:4367-4394`). Production configuration raises mutation
active/queued/per-database bounds to 32,768
(`config/system.edn:134-144`), specifically to protect Datahike's 120,000
entry queue. The unconfigured capacity constructor would allow only four
active mutations on this 18-processor machine
(`src/seon/db/executor.clj:125-164`), so any in-process fixture must receive
the production override explicitly.

Existing first-party patterns to reuse:

- `test/seon/db/writer_mutation_concurrency_test.clj:23-55` owns request-path,
  complete writer-boundary transaction, and overridden-capacity helpers;
- its `ordinary-mutations-pipeline-while-allocation-remains-exclusive` test
  proves ordinary same-database writes pipeline while an allocation waits
  (`:57-148`);
- `test/seon/db/writer_crash_fixture.clj:17-34` is already a file-backed writer
  process with the production 32,768 mutation capacity;
- `test/seon/db/writer_mutation_recovery_test.clj:252-309` is the closest
  multi-session burst: 64 UDS sessions, one simultaneous transaction each;
- direct Datahike's original raw benchmark is recoverable without restoring
  deleted code at
  `git show 8687b4424:src-flow-prototype/src/flow/demo.clj`, lines 131-148;
- the old four-transaction-per-run curve is at
  `git show 8687b4424:src-flow-prototype/attack-concurrency/attack/starve.clj`,
  lines 93-114.

The UDS boundary is a separate ceiling: defaults are 256 authority response
slots, 64 response slots per session, and 256 connections
(`src/seon/db/transport/uds.cljc:186-195`). Do not call a UDS rejection the
Datahike wall.

## 2. What the historical numbers actually measured

The two rows in measurements §7.1 are different workloads:

- **Run A**, `flow.demo/demo-concurrency`, timed 200 synchronous raw
  `d/transact` calls serially, then 200 raw calls started through virtual
  threads on the same already-used database. It reported capacity
  milliseconds per transaction, not per-call latency. Source:
  `8687b4424:src-flow-prototype/src/flow/demo.clj:131-148`.
- **Run B**, `attack.starve` section 4d, created a fresh database at each
  `n = 1/10/50/200`, then raced `n` one-step prototype runs. The denominator
  was estimated as `4*n` transactions: claim, running receipt, step/terminal
  transaction, and close. Source:
  `8687b4424:src-flow-prototype/attack-concurrency/attack/starve.clj:93-114`.

Neither run recorded batch sizes, commit IDs, per-call latency percentiles,
JFR, RSS, GC, or file-force duration. Run B's `n` is concurrent prototype
runs, not concurrent single-datom transactions. Preserve those labels in the
appendix; do not splice either old row into the new raw-transaction curve.

## 3. Shortest executable wall-finding sweep

### 3.1 One small project-local runner

Add one source-grounded runner under
`tmp/load-saturation/commit_sweep.clj`; never use a session scratchpad or
system temporary directory. Start from the 18-line
`flow.demo/demo-concurrency` body, with these required changes:

1. Build every fresh database config through
   `seon.db.backend/datahike-config`, so the timed database has today's
   production `keep-history`, fused-root, schema-flexibility, branch, and
   deterministic store-ID shape (`src/seon/db/backend.clj:101-154`).
2. Use a unique path below `tmp/load-saturation/stores/` and a unique database
   name for every point/repetition. Never point at `data/clusters/default` or
   any configured cluster path.
3. Install one fixed schema outside the timed interval. Use one single-datom
   transaction shape throughout. Report the exact transaction EDN and whether
   it allocates new entities or updates pre-created entities.
4. Warm the JVM and transaction path once on a separate fresh database before
   the recorded points. Fresh databases per point prevent store growth from
   becoming the concurrency variable.
5. For each offered concurrency `c`, create `c` virtual workers behind one
   `CountDownLatch`, but keep total timed transactions fixed. Each worker
   submits sequential calls until the shared immutable work vector is
   exhausted. A burst where total transactions equals `c` is useful for
   admission, not a stable throughput ceiling.
6. Record every call's start/end, error or report, and
   `[:tx-meta :db/commitId]`. Grouping successful reports by commit ID yields
   the actual batch-size distribution directly from Datahike's contract
   (`writer.cljc:240-245`); no trace-log inference is needed.
7. Release the connection after every point. Preserve the raw EDN/CSV and JFR
   under `tmp/load-saturation/results/<run-id>/`.

The cheap reconnaissance sequence is:

```text
c = 1, 10, 50, 200, 400, 800, 1600, 3200, 6400, 12800, 25600

```

Use 8,192 timed transactions per point through `c=3,200`, then at least
`4*c` transactions for larger points. Stop upward expansion only after
throughput degrades at two consecutive points or a named limit fires. The
first pass is reconnaissance, because its total work changes above 3,200.

Confirm the apparent knee and both neighboring points with three fresh-store
repetitions using one identical total transaction count large enough for the
highest point. Report medians plus the three raw values. If the knee lies above
25,600, the next point must respect both the production admission bound
32,768 and Datahike queue depth 120,000; crossing one of those is a different
named ceiling.

Each output row should contain at least:

```clojure
{:load/concurrency c
 :load/transactions total
 :load/wall-ms wall
 :load/throughput-tx-s throughput
 :load/capacity-ms-per-tx (/ wall total)
 :load/call-latency-ms {:p50 _ :p95 _ :p99 _ :max _}
 :load/commit-ids _
 :load/batch-size {:p50 _ :p95 _ :max _}
 :load/errors _
 :load/heap-used-bytes {:before _ :after _ :peak _}
 :load/rss-kib {:before _ :after _ :peak _}}

```

Capacity ms/tx is `wall / completed transactions`; call latency is each
caller's elapsed time. They are different quantities and must never share one
column.

### 3.2 Exact launch conditions

Generate a JFR configuration once:

```bash
jfr configure --input profile.jfc \
  --output tmp/load-saturation/load.jfc \
  gc=high allocation-profiling=high method-profiling=high thread-dump=1s \
  'jdk.FileForce#enabled=true' 'jdk.FileForce#threshold=0ns' \
  'jdk.FileForce#stackTrace=true' \
  'jdk.FileWrite#enabled=true' 'jdk.FileWrite#threshold=0ns' \
  'jdk.FileWrite#stackTrace=true' \
  'jdk.VirtualThreadPinned#enabled=true' \
  'jdk.VirtualThreadPinned#threshold=0ns' \
  'jdk.VirtualThreadSubmitFailed#enabled=true'

```

Launch through the exact writer alias, which supplies G1 and `-Xmx512m`:

```bash
clojure \
  -J-XX:FlightRecorderOptions=stackdepth=256 \
  -J-XX:StartFlightRecording:settings=tmp/load-saturation/load.jfc,filename=tmp/load-saturation/results/commit-wall-512m.jfr,dumponexit=true,disk=true \
  -M:writer tmp/load-saturation/commit_sweep.clj

```

The runner must print its effective JVM input arguments, max heap,
`availableProcessors`, JDK/runtime version, Clojure version, git HEAD and
dependency SHAs, backend config with secrets absent, filesystem/device facts,
and the output of `bin/seon status` captured immediately before the run. If
another writer, test suite, build, browser verification, or indexer was active,
name it. “Nothing else was running” is a measured condition only after this
snapshot.

Run the confirmed knee again at `-Xmx2g`; pass
`-J-Xmx2g` and print `Runtime/maxMemory` to prove which duplicate `-Xmx` won.
Every heap and RSS row must say `-Xmx512m` or `-Xmx2g`.

## 4. Naming the saturated resource

One curve cannot name a cause. Use the primary JFR plus the smallest controlled
perturbation for each candidate:

| Candidate | Evidence required before naming it | Shortest falsifier |
|---|---|---|
| Processing/commit-loop CPU | throughput plateaus while JVM CPU approaches one logical core; `jfr view cpu-time-hot-methods` and `hot-methods` place samples in Datahike transaction reduction/persistent-set or writer commit work; file-force and GC time are not coextensive with wall time | repeat knee on `:memory` with the same Datahike config shape; a similar one-core knee isolates transaction/index CPU from file durability |
| Disk/file forcing | `jdk.FileForce` count and total/long-tail duration grow with commits and account for the serial commit interval; commit thread is parked/native during force; JFR file writes name only the throwaway store | repeat only the knee with `[:store :config :sync-blob?] = false`; a large shift names forcing as the cause, but this diagnostic run is non-durable and never a production performance claim |
| Heap/GC | JFR heap summaries show occupancy approaching the named `-Xmx`; allocation rate and GC pause/concurrent-cycle time rise at the knee; lowering/raising `-Xmx` moves it | compare the identical knee at `-Xmx512m` and `-Xmx2g`; unchanged throughput with low GC falsifies heap/GC |
| Virtual-thread carriers | `jdk.VirtualThreadPinned` or submit-failure events occur, runnable virtual work queues while CPU remains available, and the knee moves with scheduler parallelism | repeat only the knee with `-Djdk.virtualThreadScheduler.parallelism=1`, default 18, and 36; an invariant knee plus zero pinned events falsifies carriers |
| Queue/admission | explicit Seon rejection names a governing mutation capacity key, or Datahike emits queue-pressure and reaches its 120,000 slots | report the named queue and offered concurrency; do not call it commit CPU or disk |

Useful JFR views:

```bash
jfr summary tmp/load-saturation/results/commit-wall-512m.jfr
jfr view --width 180 cpu-load tmp/load-saturation/results/commit-wall-512m.jfr
jfr view --width 180 cpu-time-hot-methods tmp/load-saturation/results/commit-wall-512m.jfr
jfr view --width 180 hot-methods tmp/load-saturation/results/commit-wall-512m.jfr
jfr view --width 180 gc tmp/load-saturation/results/commit-wall-512m.jfr
jfr view --width 180 gc-pauses tmp/load-saturation/results/commit-wall-512m.jfr
jfr view --width 180 allocation-by-thread tmp/load-saturation/results/commit-wall-512m.jfr
jfr view --width 180 file-writes-by-path tmp/load-saturation/results/commit-wall-512m.jfr
jfr view --width 180 pinned-threads tmp/load-saturation/results/commit-wall-512m.jfr
jfr view --width 180 thread-count tmp/load-saturation/results/commit-wall-512m.jfr
jfr view --width 180 jdk.FileForce tmp/load-saturation/results/commit-wall-512m.jfr

```

JFR `jdk.FileForce` is the primary no-privilege force measurement. On macOS,
`fs_usage -w -f filesys -t <seconds> <pid>` can corroborate `fsync`/`F_FULLFSYNC`
latency but requires root privileges; lack of that optional trace is not a
reason to guess. `iostat -d -w 1` is system-wide and must name other disk users;
it cannot by itself attribute the wall to this writer.

For a live point, capture these without stopping the process:

```bash
jcmd <pid> GC.heap_info
jcmd <pid> Thread.dump_to_file -format=json tmp/load-saturation/results/threads.json
ps -o pid=,rss=,vsz=,%cpu=,nlwp=,etime=,command= -p <pid>

```

Sample RSS during the run; one `ps` after completion is not peak RSS. Report
RSS in KiB as the OS exposes it and heap in bytes. Do not round a sub-megabyte
delta to whole megabytes.

The resource name must follow convergent evidence. For example, “one Datahike
processing core” is justified only by a one-core process plateau, hot stacks
in that loop, low file-force/GC share, and a storage perturbation that does not
move the knee. “APFS file force” requires the inverse evidence. A batch-size
ceiling alone names neither.

## 5. Full Seon boundary companion

After the direct Datahike wall is known, repeat the confirmed points through
the complete Seon writer boundary. This is a separate curve:

- reuse the file-backed process and 32,768 capacity from
  `test/seon/db/writer_crash_fixture.clj`;
- reuse `writer-test/open-session!` and transaction construction from
  `writer_mutation_recovery_test.clj:267-297`;
- avoid UDS's 64-per-session slot ceiling by distributing in-flight calls
  across sessions, while staying below 256 connections; report session count;
- pre-create the target entities outside timing and issue ordinary updates so
  `allocation-transaction?` is false. Otherwise
  `start-transact-request!` deliberately serializes allocation and measures a
  different contract;
- retain request IDs, success/failure envelopes, executor evidence, transaction
  report commit IDs, and response latency.

Compare:

1. raw Datahike production-shape file curve;
2. full Seon writer boundary with embedding disabled and production mutation
   capacity;
3. real-agent turns in the throwaway cluster.

The delta between 1 and 2 is Seon preparation, idempotency, admission, Transit,
UDS, and response construction. The delta between 2 and 3 includes driver,
context, model, SCI, publish, and real capability work. This decomposition is
necessary before assigning a real-agent failure to “the commit path.”

## 6. Hazards and acceptance checklist

- Never open a second writer on a cluster file store. D1 already proved that
  two processes can both report successful commits and silently lose one
  writer's data.
- Never benchmark `data/clusters/default`; every database and socket belongs
  under a unique `tmp/load-saturation/` run directory or an explicitly named
  throwaway cluster.
- Do not change `commit-wait-time` during the baseline. A later dial experiment
  is a separate condition.
- Do not include database creation, schema install, JIT warmup, teardown, or
  JFR post-processing in timed transaction wall time.
- Do not use a changing total transaction count for the three knee-confirmation
  points.
- Count completed successes, errors, and timeouts separately. Dividing wall
  time by offered calls after failures is not throughput.
- Preserve raw point rows, JFR, exact command, git status, and effective config.
- State explicitly that the direct sweep does not measure LLM, agent context,
  SCI, capability calls, streaming, publish, or web-render.
- State explicitly that `:memory` and `sync-blob? false` are diagnostic
  counterfactuals, not production measurements.
- A satisfactory conclusion contains the curve, batch-size curve, knee,
  first degrading point, named resource, evidence that falsifies the other
  four candidates, and all §0 conditions from
  `measurements-2026-07-25.md`.
