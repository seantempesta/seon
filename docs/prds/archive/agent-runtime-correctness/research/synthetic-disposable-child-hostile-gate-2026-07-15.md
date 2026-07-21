---
type: research
status: complete
tags: [research, agent, flow, pod]
---

# Synthetic disposable-child hostile gate — 2026-07-15

## Decision

Unit 6 can begin after the coordinated clean-restart and old-subtree gate with
one non-production disposable-child artifact plus one parent harness. It must
not move application eval, expose `my.*`, add a writer path, or share a worker
thread with the pod. The fixture exists to falsify the selected process/backend
boundary before receipts, capabilities, provider attempts, or eval batches
depend on it.

The direct macOS arm measures launch, TERM/KILL/`close`, output bounds, and
Node permission behavior. It is never hard-memory or hostile-code evidence.
The hard arm runs the same generated mode in a fresh non-root container with a
read-only root, no network, dropped capabilities, no-new-privileges, bounded
PIDs, and equal memory/swap limits. Only that kernel boundary may claim total
memory or network containment.

The current `seon:slice1` image is not yet an admissible production child
artifact. It contains Node `22.23.1` at `/opt/seon/node/bin/node`, omits that
binary from `PATH`, and differs from the selected/audited host Node `26.4.0`.
Its permission model denied filesystem, child-process, and worker probes but
allowed a network listener. Docker `network=none`, not Node 22 permission
flags, is the measured network boundary for that image.

## Scope and safety

This audit read the agent-runtime architecture, PRD and localized authority,
the existing process-death and parent-capability audits, current admission,
eval receipt, eval-batch, and recovery source, exact Node/ClojureScript/Piscina
references, and the existing Inspect Docker arm. It did not edit application
source, run or restart a Seon pod, connect to a writer, mutate a database, or
touch operator/process owners.

Measurements used only short disposable host Node children and isolated Docker
containers. The heap probe used a 24 MiB V8 old-space cap. The external-memory
probe used a 96 MiB cgroup and 4 MiB buffer increments; it did not allocate at
host scale. Every container created by this audit was removed.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source and consequence |
|---|---|---|
| Host Node.js | `v26.4.0`, V8 `14.6.202.34-node.21`, official release commit `2022edf3e32ce28ee08b17f8566243a090dacd95` | `tmp/reference-node-v26.4.0/doc/api/child_process.md`, `worker_threads.md`, `permissions.md`, `cli.md`, `lib/child_process.js`, and `lib/internal/child_process.js`. `kill()` sends a signal; `killed` is not death; PID reuse makes delayed naked-PID signaling unsafe; `close` follows exit and stdio closure; worker limits exclude external data. |
| Packaged Node.js | `seon:slice1` image `sha256:63db32776190f88411542a1415a6eb44bdb17c6b809f2d1fdab39b6a2c0a0557`; Node `v22.23.1` | The executable is `/opt/seon/node/bin/node`; image `PATH` omits it. `--permission` has no `--allow-net` option and allowed `net.createServer().listen(0)` in the measured image. The image/runtime descriptor must converge before Node permission results can be promoted. |
| ClojureScript self-host | `1.12.145`, official tag commit `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` | `reference-code/clojurescript/src/main/cljs/cljs/js.cljs` plus `src/seon/eval.cljs`. Compile/analyzer/result state is process-local; later batches reconstruct committed program facts and never replay effectful forms. A Promise timeout does not preempt synchronous JavaScript. |
| Datahike and writer boundary | maintained `417649383c65e13f15ea41d394fb1ed742477965` | `src/seon/db.cljs`, `src/seon/eval/internal.cljs`, and `src/seon/runtime/recovery.cljs`. The parent retains the immutable database value, commits the positioned running receipt, stamps provenance, and performs terminal CAS/recovery. No connection or writer/feed socket enters the child. |
| Runtime admission | current `seon.runtime.admission` | `available?` is a process-local generation door; `eval-batch!` checks it before the batch and between entries. Child launch admission must serialize with handle publication and shutdown rather than add another durable registry. |
| Eval receipts | current `seon.eval.internal` | Closed `start-tx-data` and terminal CAS builders exist, and recovery interrupts running receipts. They are not wired into `eval-batch!`, and start data lacks contiguous per-turn position and complete run/coordinate fences. |
| Piscina | reference `23a6c2e94735216c6978679fe7b8ea0b5666683b`, not selected | `reference-code/piscina/src/worker_pool/index.ts`, `abort-task.test.ts`, and `test/test-uncaught-exception-from-handler.test.ts` contribute listener removal, task rejection, and replacement patterns only. Worker threads cannot provide hard external-memory containment. |
| Inspect Docker arm | current `src-inspect-ai/src/seon_inspect/swebench_arm.py` | The existing arm supplies `network_mode: none`, read-only artifact mounting, and a memory limit, but its boot arm uses 6 GiB and lacks the complete per-task non-root/cap-drop/no-new-privileges/PID/total-memory evidence required here. Inspect orchestrates proof; it does not own Seon child disposal. |

## Current containment boundary

- `seon.eval/eval-batch!` performs compile/eval, Promise settlement,
  analyzer/schema diff, result admission, durable recording, and program
  publication in the pod. Admission can stop between entries but cannot
  preempt an entry already executing.
- `seon.eval/race-timeout` selects a timeout value for cooperative async work.
  A synchronous loop blocks the same event loop, so its timer cannot fire.
- `seon.eval.internal/start-tx-data` and `terminal-tx-data` supply the one
  receipt state machine, but production eval still allocates/records after
  computation. A child must never create a second receipt mechanism.
- `seon.runtime.recovery/recover!` already terminalizes running eval receipts
  inside the fenced run/turn recovery transaction. It can repair only durable
  starts; it cannot reconstruct a child handle or prove an old child absent.
- `result/<id>`, bootstrap compiler state, unresolved Promises, and accepted
  same-batch definitions are process-local. They are disposable after child
  death; only acknowledged program facts reconstruct later work.
- `seon.db/with-agent` and explicit ids are ergonomics, not authority. The
  synthetic fixture may request a harmless read/refused write, but the parent
  must derive actor, receipt, coordinate, and grants from its retained closure.

## Exact synthetic artifact

The artifact is a generated single-task Node program. It accepts exactly one
closed startup frame, emits bounded newline-delimited frames, runs one selected
mode, and exits. Mode is fixture selection, not a production task taxonomy.
The parent selects it; child input cannot change artifact, backend, deadline,
actor, receipt, coordinate, or grants.

| Mode | Generated child action | Required parent observation |
|---|---|---|
| `ordinary` | Emit baseline identity/memory, answer one pure request, close input, exit zero. | One ready frame, one bounded result, `close(0)`, no residual process/pipe/timer. |
| `sync-loop` | Emit ready, then execute a synchronous infinite loop. | Absolute deadline remains live in parent; TERM then KILL; `close` inside the outer bound. |
| `promise-hang` | Await a never-settling Promise while event loop remains responsive. | Cooperative close is attempted, then the same bounded disposal path; no special watchdog. |
| `term-refusal` | Install a TERM handler that does not exit, then loop. | TERM is observed as insufficient; KILL uses the retained handle; `close` proves reap. |
| `heap` | Allocate object graphs under a small `--max-old-space-size`. | Child fatal exit only; heap flag is labeled V8 diagnostic, never total-memory containment. |
| `array-buffer` | Allocate/touch `ArrayBuffer` increments and report `heapUsed`, `external`, `arrayBuffers`, RSS, and backend counters. | Hard backend kills only child at the configured total-memory ceiling. |
| `external-buffer` | Allocate/touch `Buffer` increments. | Same hard-backend gate; V8 heap remains small while cgroup memory reaches its limit. |
| `uncaught` | Throw one uncaught exception after ready. | Bounded stderr/exit diagnostic; no fabricated structured child result. |
| `explicit-exit` | Exit with a generated nonzero code. | Code is diagnostic; one parent terminal decision wins. |
| `output-flood` | Write frames and stderr beyond independent byte/count caps. | Decoder/output admission closes before parent memory grows without bound; child is killed/reaped. |
| `foreign-authority` | Add actor/run/turn/eval/coordinate/grant fields to an operation frame. | Closed schema rejects before any owner/writer call; child is disposed as protocol-invalid. |
| `stale-write` | Request an allowed write after parent supersedes the receipt/run fence. | Existing parent capability owner returns refusal; no coordinate advance. |
| `ambient-probes` | Attempt filesystem read/write, TCP/UDP/listener, environment secret, child process, worker, native addon, inspector, sqlite/symlink bypass, and `_debugProcess`. | Direct arm records Node denials without claiming sandboxing; hard arm proves OS/container denial. |
| `parent-eof` | Continue synchronous work after parent transport disappears. | Backend/operator parent-death mechanism proves the child/container absent; child cooperation is not assumed. |

Every evidence frame carries fixture version, Node/V8 identity, artifact and
backend digest, mode, monotonic sample index, child-local `memoryUsage`, and
bounded diagnostic text. Parent evidence additionally carries exact argv,
non-secret environment-key names, immutable database coordinate, absolute
deadline, output/frame/PID/CPU/memory bounds, retained-handle terminal event,
and backend resource identity. None of those diagnostic fields grants
authority.

## Measured isolated preflight

These measurements validate fixture shape and expose false claims. They are not
live Seon proof and do not set production timeouts. Sample counts are too small
for a reviewed p99.

### Direct TERM refusal and KILL

Ten sequential host Node `26.4.0` children printed baseline memory, installed a
TERM handler, and entered a synchronous loop. The parent sent TERM at about 70
ms, KILL about 70 ms later, and waited for `close`.

| Measure | Minimum | Median | Maximum |
|---|---:|---:|---:|
| Spawn event from launch | 0.379 ms | 0.423 ms | 1.263 ms |
| Sampled RSS | 48,656 KiB | 48,704 KiB | 48,784 KiB |
| KILL to `close` | 1.190 ms | 1.261 ms | 1.412 ms |
| Launch to `close` | 142.150 ms | 142.678 ms | 143.491 ms |

All ten terminal observations were `signal=SIGKILL`, `code=nil`. Child-local
baseline RSS was 43.9–44.1 MB. This proves the retained direct-child shape can
reap TERM-refusing CPU loops promptly on this host. It does not prove a safe
production grace, parent-death cleanup, or pod responsiveness.

### V8 heap exhaustion

Five host Node `26.4.0` children used `--max-old-space-size=24` and allocated
object graphs until fatal termination.

| Measure | Minimum | Median | Maximum |
|---|---:|---:|---:|
| Fatal-exit latency | 143.040 ms | 143.404 ms | 147.505 ms |
| Sampled peak RSS | 78,384 KiB | 78,512 KiB | 79,184 KiB |

All five ended with `SIGABRT`, not a structured JS result. A 24 MiB old-space
flag allowed roughly 79 MiB RSS. This is useful fatal-exit evidence and direct
proof that old-space size is not a total-memory ceiling.

### Non-root hard external-memory kill

One `seon:slice1` Node `22.23.1` container ran as uid/gid `65532`, with
`network=none`, read-only root, all capabilities dropped,
`no-new-privileges=true`, PID limit 32, memory 96 MiB, and total
memory-plus-swap equal to memory (zero swap allowance).
It allocated/touched 4 MiB `Buffer` increments.

The child began with about 41.8 MB RSS, 3.6 MB heap used, and 1.3 MB external.
At 20 chunks it reported about 128.5 MB RSS, 3.8 MB heap used, 85.5 MB external,
83.9 MB `arrayBuffers`, and cgroup current/peak 94.2 MB. The kernel then ended
only the container with exit 137 and `OOMKilled=true`; total elapsed time was
610 ms. Docker recorded exactly 100,663,296-byte memory/swap limits.

This is the required qualitative hard-boundary signal: external memory crossed
the cgroup while V8 heap stayed small, and the kernel killed the disposable
container. It does not graduate Seon because no pod/writer was present, the
artifact version is mismatched, parent transport was absent, and cleanup was
not joined to a durable receipt.

### Permission/version falsifier

Host Node `26.4.0 --permission` returned `ERR_ACCESS_DENIED` for `/etc/hosts`
read, workspace write, child spawn, worker creation, and network listen.
Packaged Node `22.23.1 --permission` inside the hard container returned the same
denials for filesystem, child, and worker probes but allowed the network
listener. Its environment still contained `HOME`, `HOSTNAME`, `PATH`, and
`SEON_RUNTIME_ROOT`; no seeded secret was present.

Consequences:

- the packaged artifact cannot borrow Node 26 permission claims;
- Node 22's lack of `--allow-net` is compatible with inherited capability
  descriptors but does not enforce network denial;
- `network=none` is the only measured egress boundary in the current image;
- the future launch descriptor must record the executable path because the
  image does not expose Node on `PATH`; and
- environment is constructed from an explicit allowlist, never inherited and
  filtered after spawn.

## Hard-kill falsifiers

The gate fails if any row below is absent, inferred, or measured against a
different artifact/backend identity.

1. **Deadline owner:** one parent absolute deadline selects timeout; the child
   cannot renew it. TERM/KILL grace remains cleanup inside that bound.
2. **Retained handle:** signals use the exact retained `ChildProcess`/backend
   handle. PID polling is diagnostic and never authorizes a signal.
3. **Close, not kill-call:** success requires `close` after stdio closure.
   `child.killed`, signal-send success, `exit`, or exit code alone fails.
4. **Bounded escalation:** cooperative close and TERM are attempted once, KILL
   occurs only after the measured grace, and KILL-to-close plus total reap stay
   inside the owning bound.
5. **No residual resource:** post-close inspection finds zero child/container,
   descendants, pipes, listeners, timers, decoder buffers, capability socket,
   queue entries, ports, or readiness artifacts.
6. **Hard total memory:** ordinary heap, `ArrayBuffer`, and `Buffer` each cross
   a small backend limit. Only the child/backend dies. Old-space flags and RSS
   polling cannot satisfy this row.
7. **Parent responsiveness:** event-loop delay, web readiness, writer ping, and
   normal REPL work remain within recorded bounds during every hostile arm.
8. **Authority refusal:** foreign, stale, duplicate, late, oversized, and
   unknown-field frames cause no writer call and no database coordinate
   advance.
9. **Durable honesty:** a started hostile form becomes interrupted exactly
   once; undispatched later forms have no receipt, tee, result slot, or error.
10. **Parent death:** old subtree/backend absence is proven before replacement
    readiness; recovery then interrupts running receipts once and a second pass
    writes nothing.

## Measurement protocol after clean restart

Run direct and hard arms from one source-frozen child artifact. Retain every raw
sample rather than only aggregates. Use at least 30 sequential samples per
ordinary/TERM/KILL mode and at least 10 per destructive allocator/protocol mode
before proposing limits. Record minimum, median, p95, p99 where sample size
supports it, and maximum. Freeze a cleanup bound only after review: measured
p99 plus an explicit margin, strictly inside the run/operator deadline.

For each mode record launch-to-spawn, spawn-to-ready, reconstruction-to-ready,
ready-to-first-result, timeout-to-TERM, TERM-to-close, TERM-to-KILL,
KILL-to-close, total reap, peak child RSS/heap/external/arrayBuffers, backend
current/peak/event counters, parent RSS/event-loop delay, writer ping, web
status, post-reap fd/process/container inspection, and subsequent normal-turn
latency. One anecdotal fast run never sets a timeout.

## Smallest dependency-ready implementation slice

After the clean source-frozen restart and unit-1 proof publish a stable child
backend descriptor plus old-subtree absence, one coherent owner may implement
only this slice:

1. Add one private disposable-child lifecycle adapter under the existing
   `seon.eval` owner: closed frame schemas, exact spawn descriptor, retained
   handle publication, bounded decoder/output, one deadline close path,
   TERM/KILL/`close`, and complete disposal.
2. Add one repository-owned non-production child artifact implementing only
   `ordinary`, `sync-loop`, `term-refusal`, `heap`, `array-buffer`,
   `external-buffer`, `output-flood`, and `ambient-probes`.
3. Run it through direct and hard backends with no database connection,
   provider credentials, `my.*`, application eval, receipt mutation, or warm
   pool. Prove the measurement/falsifier matrix and pod/writer health.
4. Only after those gates pass, add the harmless frozen read and refused stale
   write through the parent-held capability. The parent supplies all authority
   fields; the child frame supplies only operation data.

Do not combine this first slice with positioned receipt wiring, actor authority,
provider attempts, eval cutover, Inspect model trials, or warm-slot
optimization. Those consume a proven lifecycle; they cannot help prove it.

## Graduation boundary

This audit closes design uncertainty about the exact synthetic gate and proves
that a small hard cgroup can contain external memory independently of V8 heap.
Unit 6 remains blocked on coordinated live/source-frozen restart evidence,
stable backend/artifact identity, dead-parent subtree absence, positioned eval
receipts, capability framing, pod/writer responsiveness, and subsequent normal
agent work. No production containment claim has graduated.
