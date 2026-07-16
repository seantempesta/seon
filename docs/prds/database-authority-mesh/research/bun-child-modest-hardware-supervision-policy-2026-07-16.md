---
type: research
status: complete
tags: [research, prd, agent, flow]
---

# Bun child modest-hardware supervision policy — 2026-07-16

## Decision

Keep the agent supervisor event-driven. Add one absolute invocation-deadline
timer in the Bun cluster host, retain `proc.exited` as terminal process truth,
enable Bun no-orphans mode, and use Bun's operating-system memory-pressure
event to retire idle children and skip new starts in that scheduling pass. Do
not add a recurring child heartbeat or production RSS poller.

The host deadline is necessary even though the child already has a timer. The
child timer runs on the same event loop as compiled agent code, so a synchronous
loop can prevent its own timeout from firing. The host is a separate Bun
process and its timer still fires. At the deadline it records its decision,
sends the existing cancel message, then bounds termination with TERM and KILL.
This is the smallest reliable synchronous-wedge boundary.

Start no child merely to keep a durable agent available. Admit a child only for
current database work, keep one active invocation per child, and retire it as
soon as its durable work is idle in the first density release. A warm-child
set remains a measured product choice, not the default. When all child slots
are occupied, the database facts remain the work source; the host starts
nothing and re-queries current work when a slot is released.

Do not automatically replay a run after an abnormal child exit. Apply the
existing fenced recovery transition to that agent's exact run, then let a later
message or explicit resume create new work. A failure before ready may retry
once for the same work request after a short delay because no agent-visible work
has run. Further same-request failures stop; they do not form a restart loop.

## Dependency ledger

- Seon `72bcf3ba9313`: `src/seon/execution/host.cljs`,
  `src/seon/execution.cljs`, `src/seon/runtime/recovery.cljs`,
  `src/seon/agent.cljs`, `src/seon/agent/run.cljs`,
  `script/seon/dev/process.clj`, and `script/seon/dev/detach.py`.
- Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3`:
  `packages/bun-types/bun.d.ts`, `packages/bun-types/overrides.d.ts`,
  `src/runtime/api/bun/subprocess.rs`,
  `src/runtime/api/bun/subprocess/ResourceUsage.rs`,
  `src/spawn/process.rs`, `src/spawn_sys/spawn_process.rs`,
  `src/spawn_sys/posix_spawn.rs`, `src/runtime/node/memory_pressure.rs`,
  `src/jsc/ProcessAutoKiller.rs`, `src/io/ParentDeathWatchdog.rs`, and
  `test/cli/run/no-orphans.test.ts`.
- macOS 26.5 SDK `sys/resource.h` and the local `setrlimit(2)` manual.
- Linux kernel cgroup v2 documentation and Linux `getrlimit(2)` manual.
- Prior seam decisions: [[bun-child-supervision-seam-2026-07-16]] and
  [[unit-8-authored-source-loading-seam-2026-07-16]].

This was a read-only audit. No lifecycle, build, test, production source, or
roadmap file was changed.

## What the current host already gets right

The current host uses native `Bun.spawn`, one child per agent, one active
invocation per child, bounded diagnostic tails, exact artifact and database
attachment checks, and `proc.exited` for cleanup
(`src/seon/execution/host.cljs:267-317,382-436`). It compares the actual
Subprocess object plus host generation before accepting callbacks, so a late
exit cannot remove a replacement child (`src/seon/execution/host.cljs:106-150`).
Cancellation records the caller result first, sends the existing request ID,
marks the child retiring, and bounds a non-cooperative child with KILL
(`src/seon/execution/host.cljs:438-471`).

The current execution child has one invocation-local timeout and closes its one
authority session before returning a timeout value
(`src/seon/execution.cljs:251-334`). That timer remains useful for cooperative
asynchronous cancellation. It is not a hard deadline for compiled code because
the function is invoked on the same event loop at
`src/seon/execution.cljs:300-326`.

The host currently lacks three density controls:

- no parent-owned timer is installed when an invocation is claimed;
- `ensure-child!` has no host-wide active-child admission bound; and
- the fixed 30-second idle timer retains every recently used child
  (`src/seon/execution/host.cljs:10-12,152-161,342-348`).

Those are the implementation gaps. A heartbeat is not.

## Bun's live and terminal signals

| Signal | Available while running? | Exact information | Supervisor use |
|---|---:|---|---|
| Host absolute-deadline timer | Yes | The accepted invocation exhausted its wall-clock budget even if the child event loop is stuck. | Cancel, TERM, KILL, and classify the exact invocation. |
| `process.on("memoryPressure")` in the host | Yes, when its OS backend is available | The operating system reports host/cgroup memory pressure. It does not name a child or report bytes. | Retire idle children and skip new starts in that scheduling pass. |
| Bun IPC message | Yes, while both event loops and the pipe make progress | Bounded cooperative ready/result/cancel evidence. | Control only; never process liveness truth. |
| `onDisconnect` | Yes, once the IPC pipe closes | No more IPC will arrive. It may precede or follow exit. | Early cleanup hint; never crash classification. |
| `proc.exited` | No; resolves at exit | Direct child has been reaped and yields its exit convention. | Terminal authority and child-slot release. |
| `exitCode` / `signalCode` | No; null until applicable | Actual normal exit or signal. | Combine with the supervisor's already-recorded action. |
| `resourceUsage()` | No on POSIX; undefined until exit | Post-exit CPU, maximum RSS, I/O, signals, and context switches. | Terminal evidence and density measurement, not enforcement. |
| stdout/stderr stream | Yes | Diagnostic bytes, not health. | Drain continuously and retain one bounded tail. |

Bun documents `onExit` as able to run before `Bun.spawn` returns and recommends
the `exited` Promise as the alternative
(`reference-code/bun/packages/bun-types/bun.d.ts:6831-6859`). It documents
disconnect as exactly once, not itself an error, and unordered with exit
(`reference-code/bun/packages/bun-types/bun.d.ts:6861-6901`). The native exit
path stores `rusage` before resolving `exited` and then disconnects IPC
(`reference-code/bun/src/runtime/api/bun/subprocess.rs:904-924,1056-1136`).

On POSIX, `resourceUsage()` returns undefined until `wait4` has reaped the
process. The implementation exposes only the cached result
(`reference-code/bun/src/runtime/api/bun/subprocess.rs:366-401`), and the waiter
collects `rusage` with `wait4(..., WNOHANG, ...)`
(`reference-code/bun/src/spawn/process.rs:1174-1208`). This rules out a design
that treats Bun resource usage as a live memory or CPU monitor.

Maximum RSS also needs one normalization owner. Bun returns raw Unix
`ru_maxrss` without platform scaling
(`reference-code/bun/src/spawn_sys/spawn_process.rs:177-200` and
`reference-code/bun/src/runtime/api/bun/subprocess/ResourceUsage.rs:40-43`).
Darwin reports bytes and Linux reports KiB. Density evidence must normalize by
platform before comparing it.

Terminal evidence remains one ordinary namespaced value. Bun objects, timers,
and streams stay inside the host:

```clojure
{:seon.execution/agent-id "root/task"
 :seon.execution/invocation-id "existing-request-id"
 :seon.execution/artifact-digest "sha256"
 :seon.execution.host/pid 1234
 :seon.execution.host/decision :deadline-exceeded
 :seon.execution.host/exit-code 137
 :seon.execution.host/signal-code "SIGKILL"
 :seon.execution.host/stdout-tail "bounded text"
 :seon.execution.host/stderr-tail "bounded text"
 :seon.execution.host/resource-usage
 {:seon.execution.host/cpu-user-microseconds 1000
  :seon.execution.host/cpu-system-microseconds 200
  :seon.execution.host/maximum-rss-bytes 32000000
  :seon.execution.host/voluntary-context-switches 4
  :seon.execution.host/involuntary-context-switches 2}}
```

The invocation ID is the existing request identity; PID is evidence only. The
host records its decision before signaling, then combines it with actual
exit/signal/resource fields after `proc.exited`. Missing fields remain absent.

## The new memory-pressure seam

Bun's `process.on("memoryPressure")` is materially better than polling every
child. It arms lazily on the first listener, disarms with the last, and does not
keep the event loop alive
(`reference-code/bun/packages/bun-types/overrides.d.ts:97-117`). On macOS it
uses `EVFILT_MEMORYSTATUS` and distinguishes warning from critical. On Linux it
uses a PSI trigger, first system-wide and then for the process's cgroup; kernels
before 6.6 may require `CAP_SYS_RESOURCE`, and the watcher silently remains
inactive when neither path works
(`reference-code/bun/src/runtime/node/memory_pressure.rs:1-26,109-160`).

Install one listener in the cluster host, not one in every child. On warning or
critical pressure, retire any idle children and skip new starts in that
scheduling pass. Do not kill active agent work from this global signal: it
neither identifies the large child nor proves that killing that child is
correct. The event is a pressure response seam, not a byte limit. It also runs
on the host event loop, which is another reason agent compute never belongs in
that process.

This signal still helps when immediate idle retirement is the default: it is
the right hook if a measured warm set is added later, and it can cause a host
that is between work selections to choose zero new starts. The Linux release
gate must prove that PSI actually armed in the deployment environment. Absence
is a supported state and must be visible in operator evidence.

## Why there is no process heartbeat

A recurring child heartbeat adds no useful information to the default policy:

- During active work, a synchronous event-loop wedge prevents both the child
  deadline timer and a child heartbeat. The parent deadline still fires. A
  missed heartbeat before the accepted deadline would terminate valid slow
  work early; one at or after the deadline is redundant.
- During asynchronous active work, the child deadline, cancel/result IPC, and
  parent deadline already distinguish progress from an exceeded budget.
- With immediate idle retirement, there is no indefinitely warm idle process
  whose event loop needs periodic proof. If a small warm set is later selected,
  the next invocation is the useful liveness probe; a failed send or its own
  deadline retires that child.
- A heartbeat cannot protect a child from parent SIGKILL when the child event
  loop is itself stuck. Bun no-orphans mode and the outer process group can.

A heartbeat adds unique information only if the product deliberately keeps
idle children warm indefinitely and requires detection of an idle-only event
loop wedge before either another invocation or idle retirement. That is not the
selected design. Seon's durable run beat remains a database fact used to detect
stale run ownership (`src/seon/agent/run.cljs:729-813`); it is not a reason to
duplicate process liveness over IPC.

## Default supervision policy

### Deadline and synchronous wedge

When the host claims an invocation, install one timer for its existing absolute
`:seon.execution/deadline-ms`. On an ordinary result, error, cancellation, or
exit, clear it with the other child timers. When it fires:

1. atomically record that the host selected `:deadline-exceeded` for the exact
   invocation and mark that child retiring;
2. resolve the caller with the existing timeout error shape;
3. send cancel with the same invocation/request ID;
4. send TERM after the existing short cancellation grace if the process has
   not exited; and
5. send KILL after one final short bound, then wait for `proc.exited` before
   releasing the child slot.

Do not use Bun spawn `timeout` as this policy. It sends one configured signal
when elapsed (`reference-code/bun/packages/bun-types/bun.d.ts:6977-7011`) and
does not express cooperative database cancellation, TERM-to-KILL escalation,
or Seon's terminal classification.

### Crash and recovery

An exit before ready may retry once for the same work request after 250 ms.
This is a transient-start accommodation, not general restart. A second
pre-ready failure returns one bounded core error and leaves the durable work
queryable. A later distinct database trigger may try again. Record only the
existing agent ID, work/request ID, artifact digest, attempt count, and next
eligible time as process-local supervisor data; add no database status flag.

An abnormal exit after a run opens never replays it. The later implementation
must parameterize `seon.runtime.recovery/recover!` by exact agent and run. Its
existing transaction already performs the required old-to-old CAS fence,
pointer retract, `:crashed` run close, and `:interrupted` turn/eval update
atomically (`src/seon/runtime/recovery.cljs:122-227`), but it currently scans
and repairs every pointed-at run (`src/seon/runtime/recovery.cljs:68-82`). The
cluster host calls that scoped operation once after `proc.exited`.

Repeated post-ready crashes are not an infrastructure restart loop. Each open
run is recovered once, the agent becomes durably idle, and only new work can
start another child. Repeated pre-ready failures for one trigger stop after the
one retry. These two rules remove the need for a second durable failed-child
registry while preventing a bad artifact or bad source from consuming a core
forever.

### Admission and idle retirement

Admission has two independent limits:

- one active invocation per agent child; and
- one configured maximum of active agent children per cluster host.

The shipped numeric maximum must come from the 1/4/16/32 matrix below. It must
not be guessed from logical CPU count alone because each child owns a separate
JSC heap and the current artifact carries avoidable database code. Until that
measurement is retained, a configuration may select the experimental cap but
the architecture makes no density claim.

When the cap is full, start nothing and store no process-local work queue. On
exit, re-query current waking database facts and select the oldest current
trigger, as already decided in [[bun-child-supervision-seam-2026-07-16]]. This
allows all admitted children to run on separate cores without making the JVM a
global work gate.

The first release retires a child immediately when the database says its agent
has no active durable work and its direct database requests have settled. This
replaces the current unconditional 30-second retention. If cold compiler startup
misses the interactive latency target, Sean may choose a small measured warm
set later. Memory pressure always retires that set first.

### Parent loss and process trees

Run the packaged cluster host and execution children with
`BUN_FEATURE_FLAG_NO_ORPHANS=1` or the equivalent shipped flag. Bun implements
Linux parent loss with `PR_SET_PDEATHSIG(SIGKILL)` and macOS parent loss with an
event-loop `EVFILT_PROC` watch, and it kills descendants on clean exit
(`reference-code/bun/src/io/ParentDeathWatchdog.rs:1-33,202-287`). Its retained
fixtures cover Bun and non-Bun grandchildren after parent SIGKILL
(`reference-code/bun/test/cli/run/no-orphans.test.ts:194-224`).

Also install the ordinary child-side `process.on("disconnect")` handler to close
its authority session and exit when the parent IPC pipe closes. Bun emits that
event from the native child IPC close path
(`reference-code/bun/src/jsc/VirtualMachine.rs:2889-2904`). This improves clean
loss but is not the hard guarantee: a wedged child cannot run the handler.

Keep every child referenced and non-detached. Bun waits for referenced children
by default (`reference-code/bun/packages/bun-types/bun.d.ts:7284-7297`). The
Babashka operator remains the outer inverse: it owns a generation-fenced process
group and drains TERM then KILL
(`script/seon/dev/process.clj:750-850,1183-1219` and
`script/seon/dev/detach.py:186-255`). The release proof must kill the host with
both TERM and KILL on macOS and Linux and observe zero remaining children and
grandchildren.

## Memory and CPU containment by platform

### Portable layer

Portable policy can bound child count, active work, wall-clock duration,
inference calls, authority requests, output, and result bytes. It can react to
Bun memory-pressure events. It cannot enforce live RSS through Bun's public
spawn API, and post-exit `resourceUsage()` is evidence rather than a guard.

`RLIMIT_CPU` is cumulative CPU seconds for the lifetime of a process, not an
invocation wall deadline. It is a useful final runaway backstop only after the
warm-child policy is known; reusing a child across invocations makes a fixed CPU
limit expire on healthy accumulated work. The parent wall deadline remains the
correct default.

### Linux

For a managed Linux deployment, cgroup v2 is the strongest outer interface.
Use one cgroup per cluster host subtree so the host and all of its agent
children share explicit `memory.high`, `memory.max`, `cpu.max`, and `pids.max`
policy. The kernel defines `memory.max` as the cgroup hard limit,
`memory.events` as pressure/OOM evidence, `cpu.max` as bandwidth per period,
and `pids.max` as a hard process-count limit. See the
[Linux cgroup v2 documentation](https://docs.kernel.org/admin-guide/cgroup-v2.html).

This is aggregate cluster containment, not ordinary per-child scheduling. It
protects modest hardware and neighboring clusters if one cluster's admission
estimate is wrong. Per-child cgroups are not the first cut: Bun's public spawn
API does not atomically place a new child into one, and moving a PID afterward
adds a startup race. If measurement proves one child must have a hard RSS
ceiling independent of its cluster, the dependency improvement should expose
atomic cgroup placement in vendored Bun rather than add a shell wrapper.

Linux `RLIMIT_AS` bounds virtual address space, not resident memory, and modern
Linux effectively does not enforce `RLIMIT_RSS`; the exact behavior is in
[Linux getrlimit(2)](https://man7.org/linux/man-pages/man2/getrlimit.2.html).
Therefore neither is a substitute for cgroup `memory.max`.

### macOS

macOS has no cgroup equivalent. The SDK exposes `RLIMIT_CPU` and aliases
`RLIMIT_RSS` to `RLIMIT_AS`
(`/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk/usr/include/sys/resource.h:443-450`).
The macOS manual describes the RSS value as pressure preference when physical
memory is tight, not an unconditional kill-at-bytes contract. Do not claim a
hard macOS RSS limit without an executable allocation proof on the shipped OS
and Bun.

The first macOS policy is therefore active-child admission, immediate idle
retirement, host-owned deadlines, the native memory-pressure event, and outer
process-group/no-orphans cleanup. A live per-child RSS sampler using `libproc`
would add polling, native bindings, PID-reuse fencing, and threshold policy. Add
it only if the 1/4/16/32 matrix shows that pressure notification plus admission
cannot keep the product within its measured budget.

## Current execution artifact is not the density target

The `:execution` build currently starts at `seon.execution/-main`
(`shadow-cljs.edn:125-139`), and `seon.execution` requires the monolithic
`seon.db` namespace (`src/seon/execution.cljs:1-10`). `seon.db` in turn requires
Datahike APIs and implementation types (`src/seon/db.cljs:60-67`), so every
agent child currently packages Datahike, persistent-sorted-set, superv.async,
and partial-cps even though its database operations are remote.

That dependency is an immediate correctness dependency: the child uses the
existing public `seon.db` interface and must not bypass it. It is not an
acceptable permanent per-child cost. The later owner should split the existing
`seon.db` implementation by build role so the execution artifact reaches only
the remote authority client while callers keep the same `seon.db` functions.
Do not create a parallel public database API.

Measure both the current artifact and that remote-only artifact. Current RSS is
a baseline and regression guard, not the number used to select final modest
hardware capacity.

## Required 1/4/16/32 measurement matrix

Run the same release artifact, packaged Bun, database, source population, and
workload at 1, 4, 16, and 32 simultaneously active children. Repeat at least
five cold trials and retain distributions, not one best run.

| Measure | 1 | 4 | 16 | 32 | Required interpretation |
|---|---:|---:|---:|---:|---|
| Spawn-to-ready p50/p95/p99 | — | — | — | — | Cold interactive cost and startup contention. |
| Host RSS before/peak/after | — | — | — | — | Supervisor overhead and cleanup convergence. |
| Sum current child RSS peak | — | — | — | — | Live density; use one platform measurement owner. |
| Post-exit normalized max RSS p95 | — | — | — | — | Cross-check live sampling and retain Bun terminal evidence. |
| Child user/system CPU and context switches | — | — | — | — | Oversubscription and scheduler cost. |
| Same-source compile p50/p95 | — | — | — | — | Shared authority acquisition versus per-child compile cost. |
| CPU-bound invocation throughput and p99 | — | — | — | — | Useful parallel speedup and saturation point. |
| Authority query p99 and web UI p99 | — | — | — | — | Agent concurrency must not starve control/UI work. |
| Deadline-to-exit p95 for a synchronous loop | — | — | — | — | Parent timer and escalation effectiveness. |
| Memory-pressure-to-idle-retirement p95 | — | — | — | — | Event is armed and produces bounded cleanup. |
| Parent TERM/KILL remaining descendants | — | — | — | — | Must be zero on macOS and Linux. |
| File descriptors and direct UDS sessions after idle | — | — | — | — | Must return to the host-only baseline. |

Use four workload shapes: ready then idle; same-source compile and ordinary
result; CPU-bound synchronous loop; and bounded memory growth. Add one authority
query/pull workload to show that database work stays parallel without passing
through host IPC. Inject normal exit, thrown error, abort, ignored TERM, parent
TERM, and parent KILL.

Run these comparison arms in order:

1. current execution artifact with immediate idle exit;
2. remote-only execution artifact with immediate idle exit;
3. remote-only artifact with Bun `--smol`; and
4. only if cold ready latency misses the product target, remote-only artifact
   with a bounded warm set.

Select the shipped active-child maximum at the last row where total memory
stays inside the declared cluster budget, authority and web p99 remain bounded,
and CPU-bound throughput still improves materially. This makes the default a
retained measurement result rather than a formula that mistakes logical CPUs
for available memory.

## Decisions Sean retains

1. **Linux aggregate hard containment.** Ship cgroup v2 integration with the
   first production supervisor, or initially rely on deployment-level cgroups.
   The former gives Seon direct evidence and isolation; the latter is less code
   but less portable across installations.
2. **Immediate idle exit or a warm set.** Immediate exit is the lowest-memory
   and simplest default. A warm set is justified only by measured compiler
   startup and is always the first resource retired under pressure.
3. **macOS live RSS sampling.** Add a libproc sampler only if the density matrix
   shows admission plus native pressure events are insufficient. It adds
   observability and possible targeted retirement, but also polling and native
   policy code.
4. **Bun dependency improvement for atomic containment.** If per-child Linux
   limits or child-owned grandchild groups become necessary, expose Bun's
   existing lower-level process-group/cgroup controls. Do not use process-name
   scans or shell wrappers.
5. **Final active-child maximum.** Choose it from the remote-only artifact's
   1/4/16/32 evidence for the modest-hardware profile; do not freeze the current
   monolithic artifact's memory cost into the product.

## Exact later implementation owner and proof order

The implementation owner is the existing `seon.execution.host` supervisor,
with narrow supporting changes in existing owners:

1. `seon.execution.host` owns parent deadlines, host-wide admission, immediate
   idle retirement, memory-pressure response, exit resource evidence, and the
   one pre-ready retry.
2. `seon.execution` owns cooperative cancellation and the child-side parent
   disconnect cleanup. It retains no recurring heartbeat.
3. `seon.runtime.recovery/recover!` is narrowed to the exact agent/run supplied
   by the host while preserving its existing one-transaction CAS fence.
4. The existing `seon.db` owner separates remote execution-build dependencies
   from local Datahike implementation dependencies without changing the public
   function interface.
5. `script/seon/dev/process.clj` remains outer containment and adds Linux
   cgroup evidence only if Sean selects direct cgroup ownership.

Implement and prove in that order: parent deadline against a synchronous loop;
active admission and zero process-local queue; exact-run crash recovery;
parent-loss cleanup; memory-pressure retirement; remote-only artifact reachability;
then the complete 1/4/16/32 matrix. No heartbeat, live RSS poller, warm set, or
per-child cgroup enters before a named measurement falsifies the simpler seam.
