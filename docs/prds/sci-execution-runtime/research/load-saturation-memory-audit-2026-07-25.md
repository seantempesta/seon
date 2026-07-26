---
type: research
status: active
tags: [research, runtime]
---

# Load-saturation memory and thread audit, 2026-07-25

This is the source-grounded measurement plan for the memory and thread part of
the load-saturation gate. It does not contain a load result. It establishes
which process owns each quantity, the instruments available on the selected
JDK, and the distinctions the final measurement must preserve.

The rules in [[measurements-2026-07-25]] §0 travel with every value produced
from this plan. In particular, every memory value carries `-Xmx`; byte-valued
events stay bytes through extraction; a rounded human display is never treated
as the underlying value; and measured, lower-bound, artifact, verified, and
unverified claims remain distinct.

## Findings that change the attribution

The current default runtime still has two relevant JVMs, not one:

- the writer JVM owns Datahike, the file database, and the commit path;
- the host JVM owns the real-message run driver, the LLM HTTP call, transient
  SCI evaluation, and the separate retained context map used by UDS authored
  invocations.

Both live process records inspected on 2026-07-25 selected OpenJDK 26.0.1,
G1, AOT plus AppCDS, and `-Xmx4096m`. The writer and host curves must therefore
be reported separately. Summing their RSS is useful as a cluster total, but it
does not identify whether database facts/commits or agent execution grew.

There are also three different meanings of "an agent in memory":

| population | durable/process-local representation | thread consequence |
|---|---|---|
| database-only idle agent | agent facts in the writer | none per agent |
| real agent processing an inbound message | one transient host virtual thread; transient SCI fork on a bounded platform pool | virtual thread exists only for the run; no retained agent context is installed by this path |
| agent whose UDS host session has started | one retained `sci/fork` in the host's `contexts` atom | context persists after the session; the platform session thread exists only while the socket session is open |

This distinction is load-bearing. A curve made by creating idle agents measures
writer-side database retention. A curve made by driving real messages measures
transient run and evaluation work. A curve intended to measure retained
per-agent SCI contexts must explicitly warm the UDS context path and count those
successful startups. None is a substitute for another.

Most importantly, the current real-message driver does **not** run against the
retained per-agent context map:

- `src/seon/agent/driver.clj:440-493` calls `evaluate!` without an agent
  `base-ctx`;
- `src/seon/agent/driver.clj:278-283` delegates directly to
  `seon.sci.eval/evaluate`;
- `src/seon/sci/ctx.clj:35-40` consequently forks the small process-shared base
  for that evaluation and does not retain it.

Thus a successful real-agent load curve on this revision cannot establish the
target claim that N real agents retain N private contexts. That is a current
implementation gap, not a measurement limitation.

## Dependency and source ledger

The exact checked-out dependency revisions at audit time were:

| dependency | selected source | git SHA | relevant source |
|---|---|---|---|
| SCI | `reference-code/sci` through `deps.edn` `:local/root` | `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | `src/sci/core.cljc:318-323` |
| Datahike | `reference-code/datahike` through `deps.edn` `:local/root` | `caf526850084a9d5846ccd9ea34251fe411e0d6b` | `src/datahike/writer.cljc:85-269` |
| core.async | `reference-code/core.async` | `b871f3519de6843a9f5ce66cf8d5c6cbe44d3222` | Datahike's `go-try` processing and commit loops |
| Clojure | `reference-code/clojure` | `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` | language/runtime reference |

SCI's `fork` is one persistent-data copy plus a new `:env` atom
(`reference-code/sci/src/sci/core.cljc:318-323`). It starts no thread. This is
source proof of mechanism, not a retained-byte measurement.

The localized runbook still names SCI SHA `be4021d`. That is stale relative to
the actual clean gitlink and `deps.edn` selection above. Load evidence must
record `8fac6e88...`, not silently inherit the runbook SHA.

The selected JDK is the Homebrew OpenJDK 26.0.1 build:

```text
openjdk version "26.0.1" 2026-04-21
OpenJDK Runtime Environment Homebrew (build 26.0.1)
OpenJDK 64-Bit Server VM Homebrew (build 26.0.1, mixed mode, sharing)

```

Its maintained installed source is
`$JAVA_HOME/lib/src.zip`; it is a release artifact, not a pinned repository
gitlink, so there is no honest JDK git SHA to report. Record the complete
`java -version` output instead.

## Process identity and flags

Use the operator's process record, never a `ps | grep` match. The workload PID
is distinct from the containment owner PID. For the default host:

```bash
seon_host_pid=$(
  bb -e '(require (quote [clojure.edn :as edn]))
         (let [r (edn/read-string
                  (slurp "tmp/seon-operator/processes/host.edn"))]
           (println
            (get-in r
                    [:seon.dev.process/containment
                     :seon.dev.process.containment/workload-pid])))'
)

```

Use the corresponding throwaway cluster process directory during load. Preserve
the complete `:seon.dev.process/argv`, workload PID, workload start instant,
artifact digest, and generation from both `host.edn` and `writer.edn`. PID alone
is not an identity.

Source establishes the launch:

- `script/seon/dev/process.clj:463-489` composes the JVM family flags, heap,
  AppCDS archive, artifact classpath, and main;
- `script/seon/dev/process.clj:706-745` selects the host heap from the resolved
  configuration;
- `script/seon/dev/process.clj:808-834` selects the writer heap;
- `script/seon/dev/artifact.clj:19-24` defines the common incubator-vector,
  native-access, unsafe-memory, and G1 flags;
- `config/system.edn:180-188` currently selects a 4,096 MiB host heap.

The audited live default host and writer both had:

```text
--add-modules jdk.incubator.vector
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
-XX:+UseG1GC
-Xmx4096m
-Xshare:on
-XX:SharedArchiveFile=<digest>/seon-jvm-aot.jsa
-cp <same-digest>/seon-jvm-aot.jar

```

The audited machine reported 18 available processors. The default watcher, Bun
pod, and web-render JVM were also running. Those are conditions, not ignorable
background detail. The final load run should use a named throwaway cluster and
record every other live Seon cluster and material host workload at each run.

## Why an idle agent starts no thread

The proof is a chain, not an inference from a quiet thread count:

1. `src/seon/agent.cljs:833-856` implements child birth as
   `allocate-agent!`, a database transaction.
2. `src/seon/agent.cljs:997-1019` calls that path with no initial message and
   explicitly states that the child remains idle until a message commits.
3. The JVM driver only starts work from pending human-message facts:
   `src/seon/agent/driver.clj:285-294`.
4. It starts one virtual thread for each returned pending message and one
   transient scan virtual thread on a database-interest wake:
   `src/seon/agent/driver.clj:495-531`.
5. A retained context is created only after a UDS startup frame identifies an
   agent, at `src/seon/host.clj:133-161`.
6. SCI itself merely copies persistent context data into a new `:env` atom at
   `reference-code/sci/src/sci/core.cljc:318-323`.

Therefore database-only idle-agent count may rise while host context count,
host virtual-thread count, carrier count, and platform-thread count remain
flat. Measure all of them; do not use a flat total thread count as the only
proof.

## Host thread owners

The host has fixed and demand-created thread populations that must not be
misattributed to agents:

- `src/seon/host.clj:333-338`: a fixed invocation pool of 10 and a scheduled
  watchdog pool of 2. Fixed executors create workers on demand.
- `src/seon/sci/eval.clj:33-50`: a cached **platform-thread** SCI pool, bounded
  by a semaphore opened at concurrency 10.
- `src/seon/db/host.clj:16-22,38-53`: a lazy database-call fixed pool sized to
  `availableProcessors - 1`, hence 17 on the audited machine.
- `src/seon/db/host.clj:292-303`: one virtual database-interest reader.
- `src/seon/host.clj:237-241`: one platform daemon per open UDS host session.
- `src/seon/host.clj:363-385`: one platform acceptor.
- `src/seon/agent/driver.clj:503-522`: transient virtual message/scan threads.
- `src/seon/ai/http.clj:107-120,172-207`: the real LLM call blocks on the
  calling run virtual thread; it does not create one application thread per
  idle agent.

At high concurrency, platform-thread growth can therefore come from the
database-call pool, invocation pool, SCI cached pool, open UDS sessions, JDK
HTTP internals, GC, JIT, or virtual-thread carriers. Name the thread by stack
and owner before calling it an agent cost.

## Supported instruments on JDK 26.0.1

The installed JDK provides `jcmd`, `jstat`, `jmap`, `jstack`, `jps`, `jhsdb`,
and `jfr`. macOS provides `/bin/ps` and `/usr/bin/vmmap`.

### Heap and RSS in bytes

JFR can sample used heap, committed heap, heap maximum, RSS, and RSS peak as
integer bytes without a restart. Use a selective recording so the measurement
conditions are explicit:

```bash
jcmd "$seon_host_pid" JFR.start \
  name=seon-agent-load \
  settings=none \
  filename=tmp/seon-agent-load-host.jfr \
  maxsize=256M \
  '+jdk.GCHeapMemoryUsage#enabled=true' \
  '+jdk.GCHeapMemoryUsage#period=100ms' \
  '+jdk.ResidentSetSize#enabled=true' \
  '+jdk.ResidentSetSize#period=100ms' \
  '+jdk.GarbageCollection#enabled=true' \
  '+jdk.GCHeapSummary#enabled=true' \
  '+jdk.GCPhasePause#enabled=true'

```

Start an equivalent, separately named recording on the writer. Dump or stop it
after the load interval:

```bash
jcmd "$seon_host_pid" JFR.dump \
  name=seon-agent-load \
  filename=tmp/seon-agent-load-host-final.jfr
jcmd "$seon_host_pid" JFR.stop name=seon-agent-load

```

Extract JSON, not the rounded human display:

```bash
jfr print --json \
  --events jdk.GCHeapMemoryUsage,jdk.ResidentSetSize,jdk.GarbageCollection,jdk.GCHeapSummary,jdk.GCPhasePause \
  tmp/seon-agent-load-host-final.jfr \
  > tmp/seon-agent-load-host.json

```

On this JDK, the JSON fields `used`, `committed`, `max`, `size`, and `peak`
are integer bytes. The ordinary `jfr print` renderer displayed the same RSS as
`834.0 MB`; that rendering is an artifact for sub-megabyte deltas. The JSON
value was `874528768`. Retain the integer and convert only in the final table.

For an immediate cross-check:

```bash
jcmd "$seon_host_pid" GC.heap_info
jstat -gc "$seon_host_pid" 1000
/bin/ps -p "$seon_host_pid" -o pid=,rss=,vsz=

```

`jcmd GC.heap_info` and `jstat` report KiB-scale values; macOS `ps` RSS is also
KiB. They are checks, not the fine-grained primary series.

### Retained heap versus live allocation

Periodic used heap includes garbage awaiting collection. It answers "how high
did live execution push the heap?" but does not by itself answer "what remains
per idle agent/context?"

Use a separate retained-memory run:

1. reach a population plateau and stop submissions;
2. wait for all known runs, sessions, and database commits to settle;
3. record pre-GC heap/RSS;
4. run `jcmd <pid> GC.run`;
5. record a short byte-valued post-GC JFR window;
6. repeat from fresh reset clusters, then report median and range.

Forced GC materially changes the runtime. Never mix this retained-memory series
into the throughput or latency run, and label its full collections as
measurement-induced.

For sub-megabyte marginal costs, amplify before dividing. N=1/5/10/25 is useful
for the whole-process curve but not for a per-agent byte claim. Create a large
enough database-only cohort or explicitly warmed-context cohort that the
post-GC delta is comfortably above background variation, then divide the
integer-byte delta by the verified cohort size. Three fresh-cluster replicates
are the minimum honest check against GC/layout noise.

`GC.class_histogram` is available but `jcmd` labels it high impact. It reports
shallow class totals, not retained graph size. Use it only at a stopped plateau
to identify a growing class family; do not use it during the timing run.

### Virtual, platform, native, and carrier threads

No one counter covers all thread classes.

For an instantaneous Java thread census:

```bash
jcmd "$seon_host_pid" Thread.dump_to_file \
  -overwrite -format=json \
  tmp/seon-agent-load-host-threads.json

jq '
  [.threadDump.threadContainers[].threads[]]
  | {
      total_java_threads: length,
      virtual_threads: (map(select(.virtual == true)) | length),
      platform_threads: (map(select(.virtual != true)) | length)
    }
' tmp/seon-agent-load-host-threads.json

```

The JSON also preserves name, state, container, and stack for owner
attribution. `Thread.dump_to_file` is medium impact and should be taken at
named plateaus, not at high frequency.

For native OS threads, including GC/JIT workers absent from ordinary Java
thread management:

```bash
/bin/ps -M "$seon_host_pid" | awk 'NR > 1 {n++} END {print n + 0}'

```

For the default virtual-thread scheduler:

```bash
jcmd "$seon_host_pid" Thread.vthread_scheduler
jcmd "$seon_host_pid" Thread.vthread_pollers

```

`Thread.vthread_scheduler` reports target parallelism, current pool size,
active/running carriers, queued tasks, and submissions. JDK 26 also exposes the
standard `jdk.management:type=VirtualThreadScheduler` MXBean. Its installed
source at
`$JAVA_HOME/lib/src.zip!/jdk.management/jdk/management/VirtualThreadSchedulerMXBean.java:53-116`
defines:

- target parallelism;
- current scheduler platform-thread pool size;
- estimated mounted virtual-thread count, equal to the carriers currently
  carrying virtual threads;
- estimated queued virtual-thread count.

The source explicitly warns that mounted and queued counts are estimates.

For virtual-thread lifetimes, pinning, and scheduler refusal across the whole
load interval, extend the selective JFR recording:

```bash
  '+jdk.VirtualThreadStart#enabled=true' \
  '+jdk.VirtualThreadEnd#enabled=true' \
  '+jdk.VirtualThreadPinned#enabled=true' \
  '+jdk.VirtualThreadPinned#threshold=0ms' \
  '+jdk.VirtualThreadSubmitFailed#enabled=true'

```

Start/end events are disabled in both shipped default and profile JFC files on
this JDK; they must be enabled explicitly. A zero pinned-event count is usable
only when the event and zero threshold are recorded in the conditions.

JDK `ThreadMXBean` is platform-only. The installed
`java.management/java/lang/management/ThreadMXBean.java:31-38,131-173`
explicitly excludes virtual threads. Likewise,
`java.management/sun/management/ThreadImpl.java:346-368` returns `-1` for
current/by-ID virtual-thread allocation. Do not report a `ThreadMXBean` count
as total Java threads, and do not interpret `-1` allocated bytes as zero.

### GC and native memory

Use the JFR GC events above for pause timing and `jstat -gcutil <pid> 1000` as a
live counter cross-check. Report young/full/concurrent collection counts and
time deltas over the exact load window, not lifetime totals without subtraction.

Native Memory Tracking is **not enabled** in the audited live JVMs:

```text
jcmd <pid> VM.native_memory summary
Native memory tracking is not enabled

```

The operator has no supported extra-JVM-option field. It filters the child
environment to the keys at `script/seon/dev/process.clj:120-178`, excluding
`JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS`, and builds the JVM argv at
`script/seon/dev/process.clj:463-489`. Therefore NMT category baselines/diffs
require an explicit operator change or another owner-approved instrumented
launch; they cannot be claimed from today's managed cluster.

JFR `jdk.NativeMemoryUsage` and `jdk.NativeMemoryUsageTotal` events produced
zero events in a verified two-second selective recording while NMT was
disabled. That is absent instrumentation, not zero native memory.

Available macOS fallback:

```bash
/usr/bin/vmmap -summary "$seon_host_pid"

```

`vmmap` can separate stacks, malloc zones, mapped files, and VM allocations and
reports physical footprint. Physical footprint is not RSS, and these are macOS
region categories rather than JVM NMT subsystems. Use it to explain a widening
RSS-minus-used-heap gap, not to relabel that gap as a precise JVM category.

## Required plateau matrix

At each population point, preserve one row per JVM and one thread census for
the host:

| field | writer | host |
|---|---|---|
| workload PID + start instant + generation | yes | yes |
| complete JVM flags and `-Xmx` | yes | yes |
| heap used / committed / max, integer bytes | yes | yes |
| RSS / peak RSS, integer bytes | yes | yes |
| GC counts and pause time over interval | yes | yes |
| Java platform / virtual threads | optional diagnostic | yes |
| native OS threads | yes | yes |
| virtual scheduler pool / mounted / queued | if virtual work appears | yes |
| successful database-only idle agents | shared database fact | shared database fact |
| successful real runs in flight / settled | shared database fact | shared database fact |
| successful UDS-warmed retained contexts | not applicable | explicit operation count; no external context-count surface exists |

Run three distinct series rather than blending populations:

1. database-only idle agents, no messages;
2. real concurrent turns at 1/5/10/25/...;
3. explicitly UDS-warmed retained contexts, with no active sessions at the
   retained-memory plateau.

For series 1, host heap/thread movement above noise is not an idle-agent cost;
the creation path never calls the host. For series 2, a rising virtual-thread
count is expected only while work is live. For series 3, the retained-context
delta belongs to the host, while per-session platform threads must return to
baseline after sockets close.

## Limitations and things this audit did not measure

- No load was generated and no agent-count ceiling was found in this lane.
- No LLM, capability, streaming, or agent-authored database call was measured.
- No retained-byte cost was measured against Seon's real host base.
- No NMT category measurement is possible from the current managed launch.
- No external host metric exposes `count @contexts`; the warmed-context cohort
  must be established by successful, distinct startup operations unless an
  existing REPL/admin surface is added by its owner.
- The real-message driver currently uses transient SCI forks, so its agent
  curve cannot prove retained per-agent contexts.
- JFR allocation sampling is useful for finding allocation sites, not exact
  allocated-byte accounting. On this JDK exact per-thread allocation is
  unavailable for virtual threads.
- Heap/RSS deltas at 1/5/10/25 agents may be below runtime variation. Those
  totals remain measurements, but a per-agent quotient from them would be an
  artifact.
- `vmmap` physical footprint, macOS `ps` RSS, JFR RSS, JVM committed heap, and
  JVM used heap are different quantities. Never substitute one label for
  another.
