---
type: research
status: complete
tags: [research, pod, health, capability]
---

# Bun subprocess live resource usage audit

## Question and conclusion

Can Bun expose enough parent-owned child-process evidence to distinguish a
healthy child, a CPU-bound JavaScript loop, a blocking native call, and a dead
process without requiring the child to cooperate?

Yes, with one small upstream addition. Bun already owns the process identity,
exit observation, timeout, signal, and final resource usage. Windows also
samples a live child through its process handle. Unix currently waits for the
child to exit before `Subprocess.resourceUsage()` returns anything. The useful
upstream cut is to add an explicit live form of that existing operation on
Linux, macOS, and Windows:

```ts
interface LiveResourceUsage {
  cpuTime: {
    user: bigint;
    system: bigint;
    total: bigint;
  };
  rss: number;
  maxRSS?: number;
}

interface Subprocess {
  resourceUsage(options: { live: true }): LiveResourceUsage | undefined;
  resourceUsage(): ResourceUsage | undefined;
}

```

The no-argument operation retains its current post-exit contract and exact
`wait4` result. The live overload is deliberately smaller: cumulative CPU,
current resident memory, and peak resident memory where the operating system
provides it cheaply. It returns `undefined` when the process has exited or the
OS refuses the sample. This avoids assigning false zeroes to the many
historical `rusage` counters that are not uniformly available for another live
process.

This is an upstream Bun primitive, not a Seon protocol. Seon can combine its
samples with the existing timeout, exit, and IPC mechanisms. Heap statistics,
event-loop progress, current agent work, and database state remain optional
child reports over Bun IPC because only the child knows them.

## Dependency ledger

- Vendored Bun revision:
  `be77b652884b16a103cfaa4af3c1102f72f2dcd3` in `reference-code/bun`.
- Installed runtime used for one behavioral probe: Bun 1.3.14, revision
  `0d9b296af33f2b851fcbf4df3e9ec89751734ba4`. This is evidence about the
  shipped runtime, not a substitute for the vendored source audit.
- Public types: `reference-code/bun/packages/bun-types/bun.d.ts`.
- Subprocess binding and exit ownership:
  `reference-code/bun/src/runtime/api/bun/subprocess.rs` and
  `reference-code/bun/src/runtime/api/bun/subprocess/ResourceUsage.rs`.
- Platform process data:
  `reference-code/bun/src/spawn_sys/spawn_process.rs`,
  `reference-code/bun/src/spawn/process.rs`, and
  `reference-code/bun/src/sys/lib.rs`.
- Existing process containment:
  `reference-code/bun/src/io/ParentDeathWatchdog.rs`.
- Self-process/JSC evidence:
  `reference-code/bun/src/jsc/bindings/BunProcess.cpp`,
  `reference-code/bun/src/jsc/bindings/c-bindings.cpp`,
  `reference-code/bun/src/jsc/modules/BunJSCModule.h`, and
  `reference-code/bun/packages/bun-types/jsc.d.ts`.
- Worker and watchdog evidence:
  `reference-code/bun/src/jsc/bindings/webcore/JSWorker.cpp`,
  `reference-code/bun/src/js/node/worker_threads.ts`,
  `reference-code/bun/src/jsc/VM.rs`, and
  `reference-code/bun/src/jsc/bindings/NodeVMScript.cpp`.
- Existing tests:
  `reference-code/bun/test/js/bun/spawn/spawn_waiter_thread.test.ts` and
  `reference-code/bun/test/js/node/worker_threads/worker_threads.test.ts`.

## What Bun does now

`bun.d.ts:7318` declares `Subprocess.resourceUsage()` and documents that it is
undefined until exit. `subprocess.rs:135` stores final `pid_rusage` on the
subprocess. `subprocess.rs:367-400` returns that value; its only live fallback
is the Windows `uv_getrusage` call. `subprocess.rs:904-924` records the final
usage during exit. The spawn waiter obtains the Unix value with `wait4`, so it
is necessarily an exit result. The existing spawn waiter test calls
`resourceUsage()` only after awaiting `proc.exited`.

The installed macOS runtime confirms the contract: a one-second live child
returned `undefined`; after `await proc.exited`, it returned CPU and maximum RSS.
The platform difference is accidental API behavior rather than a fundamental
constraint. `spawn_process.rs:85` already uses `GetProcessTimes`,
`GetProcessIoCounters`, and `GetProcessMemoryInfo` through Bun's retained
Windows process handle while the child is alive.

Bun's spawn timeout is also parent-owned. The option is parsed and scheduled
in `js_bun_spawn_bindings.rs`; `subprocess.rs:662-674` signals the child from
the timeout callback. A child stuck in synchronous JavaScript or a native call
does not stop the parent's timer. A synchronously blocked *parent* would delay
its own callback, which is why each execution child must remain supervised by
a separate parent process.

`ParentDeathWatchdog.rs` supplies the complementary no-orphans behavior:
Linux parent-death signals and macOS process observation/descendant cleanup.
It solves parent death, not child health.

## Portable live evidence

The smallest common and useful sample is cumulative user/system CPU plus
current RSS. It is available without executing code in the child:

- Linux: read `/proc/<pid>/stat` for CPU ticks and RSS, optionally
  `/proc/<pid>/status` for `VmRSS` and `VmHWM`. Bun's Linux subprocess owner
  already uses pidfds where available, so the sample can remain tied to the
  owned child rather than an arbitrary reused PID. `/proc/<pid>/io` and
  `smaps_rollup` expose richer evidence but are costlier or permission-sensitive
  and do not belong in the minimum interface.
- macOS: add the maintained libproc declaration for `proc_pid_rusage` beside
  the existing `proc_pidinfo` and `proc_listchildpids` declarations in
  `sys/lib.rs:5417-5426`. `RUSAGE_INFO_V4` supplies cumulative CPU and resident
  footprint fields for a target PID. Bun already uses libproc safely in
  `ParentDeathWatchdog.rs:577-647`.
- Windows: reuse the existing retained process handle and resource query in
  `spawn_process.rs:85`; include `WorkingSetSize` as current RSS rather than
  exposing only `PeakWorkingSetSize`.
- FreeBSD: the existing self-RSS code in `BunProcess.cpp` demonstrates the
  `sysctl`/`kinfo_proc` seam. It should receive a platform implementation or
  return `undefined`; the TypeScript contract must not manufacture data.

The sample should be queried through the `Subprocess` instance, never a new
PID-only global API. That preserves Bun's process ownership and reduces PID
reuse races. Sampling around exit may validly produce a live sample, the final
no-argument result, or `undefined`; it must never access a released handle.

## Cooperative child evidence

Cooperation adds meaning that the operating system cannot supply. A Bun child
can send an IPC message containing event-loop delay, current work identity,
`process.resourceUsage()`, `process.memoryUsage()`, and selected `bun:jsc`
heap statistics. Bun's spawn IPC uses JSC serialization and already has a
parent callback plus `Subprocess.send`.

Those reports are not a health authority. An infinite synchronous JavaScript
loop or a blocking native call prevents the IPC handler from running. The same
limitation applies to worker inspection. `JSWorker.cpp:764-915` implements
heap and CPU requests by posting work into the worker VM, and
`worker_threads.ts:1205-1242` wraps those operations in promises. The tests at
`worker_threads.test.ts:1347` correctly prove that pending requests settle on
termination, but they cannot make a non-yielding worker respond. Workers also
share one OS process and therefore do not provide per-agent RSS containment.

JSC's watchdog is narrower than a process supervisor. `VM.rs:36-138` exposes
execution limits, while `NodeVMScript.cpp:308-434` applies them around
`vm.Script` evaluation. It can interrupt JavaScript at JSC safepoints; it does
not guarantee interruption while native code or a system call is blocking.
The parent's OS sample plus signal escalation remains the reliable backstop.

The sampling profiler is useful forensic tooling, not an always-on health
protocol. `BunJSCModule.h:975-1015` and `jsc.d.ts:217` expose profiler startup,
but starting, stopping, or delivering the result through ordinary child
JavaScript is cooperative. Inspector attachment adds a port, protocol,
security surface, and failure modes. Neither is needed to decide whether to
terminate and replace a child.

## Proposed Bun implementation

Keep the implementation inside the current subprocess owner:

1. Add the overload and `LiveResourceUsage` type beside the existing
   `ResourceUsage` declaration in `bun.d.ts`.
2. Parse one optional `{ live: true }` argument in
   `Subprocess.resource_usage`.
3. Add one platform-dispatched `sample_live_resource_usage(Process)` helper in
   the spawn platform layer. It returns ordinary CPU microseconds, RSS bytes,
   and optional peak RSS; it does not expose a process handle or JSC value.
4. Construct a dedicated immutable JS object in `ResourceUsage.rs`. Do not
   cache live samples: sampling is explicitly demanded, cumulative CPU changes,
   and a cached value would hide a wedged process.
5. Preserve the current no-argument path and post-exit `wait4` result exactly.
6. Keep heartbeat policy, polling interval, restart thresholds, database facts,
   and profiler control outside Bun.

An alternative is to make no-argument `resourceUsage()` live everywhere, as
Windows already does. That is a smaller implementation diff but a weaker
contract: callers cannot tell a partial live OS snapshot from the complete
post-exit `rusage`, and unsupported historical counters look authoritative.
An unrelated `metrics()` method avoids that ambiguity but invents a second
name for operating-system resource usage. The explicit overload preserves the
existing Bun term and makes the semantic choice visible.

## Upstream test plan

- Add a live child test that samples before exit and observes cumulative CPU
  growth while the child executes a synchronous busy loop.
- Add a memory test that faults allocated pages and observes current RSS growth;
  allow platform tolerance and make peak RSS optional.
- Preserve the existing post-exit test unchanged to prove no-argument
  compatibility.
- Repeatedly sample across the exit boundary to prove handle lifetime and race
  safety.
- Use a blocking sleep/native fixture to prove the parent can sample and kill a
  child that cannot answer IPC.
- Add platform unit tests for Linux proc parsing and Windows/macOS conversion
  units. Run integration coverage on Linux, macOS, and Windows.
- Separately demonstrate the boundary with an IPC fixture: a yielding child
  returns heap/application evidence; a synchronous loop misses that report
  while parent-observed CPU increases. This is valuable documentation evidence,
  not a reason to add heartbeat policy to Bun core.

## Implications for Seon

The architecture can remain simple. Each execution child is an OS process.
The pod owns its Bun `Subprocess`, timeout, TERM-to-KILL escalation, exit, and
periodic live resource sample. Optional child reports add event-loop and
application facts when available. Missing child reports plus increasing CPU,
stable CPU, memory growth, exit, and timeout are different observations; the
supervisor records them and applies one bounded lifecycle policy.

This does not require workers, shared memory, inspector attachment, a second
monitor process, or child access to the parent's internals. It strengthens the
existing process seam and keeps failure isolation effective when the code being
observed is precisely the code that has stopped cooperating.

## Implemented proof state

Vendored Bun commit `d8ecf098572e2b8265b23e40c04efb4067e516cc`
implements the explicit `resourceUsage({ live: true })` overload while leaving
the no-argument post-exit path unchanged. Linux reads CPU and current RSS from
`/proc/<pid>/stat` and optional peak RSS from `/proc/<pid>/status`; macOS uses
`proc_pid_rusage` with the SDK's `rusage_info_v0`; Windows reuses the retained
process handle and obtains CPU plus current/peak working set in one sample.
Other Unix targets return `undefined` rather than fabricated counters.

The `bun_spawn_sys` and `bun_spawn` crates pass `cargo check --locked` on the
host `aarch64-apple-darwin`, `aarch64-unknown-linux-gnu`, and
`aarch64-pc-windows-msvc` targets. Rustfmt and `git diff --check` pass. The
focused JavaScript test covers a synchronous busy loop, current RSS, CPU
growth, invalid options, post-exit compatibility, and ten repeated samples
through process exit. Bun 1.3.14 fails that test at the intended live-sample
assertion because it returns `undefined`.

This checkout cannot yet execute the changed native Bun binary: `bun bd`
rejects the installed LLVM 22.1.8 and Apple clang 17 because this revision
requires LLVM `>=21.1.0 <21.1.99`. A raw Cargo unit-test binary also cannot
link through Bun's unsupported direct path because it injects Windows system
libraries on macOS. The supported debug build plus focused JavaScript test
remains the final execution gate on a host with Bun's pinned LLVM toolchain.
