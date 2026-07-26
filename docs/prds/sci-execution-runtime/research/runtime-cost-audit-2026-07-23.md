---
type: research
status: active
tags: [research, runtime]
---

# Runtime steady-state cost audit — stopped at invalid measurement precondition (2026-07-23)

## Verdict

| Question | Result |
|---|---|
| Did the isolated `runtimecost` cluster reach a runnable all-JVM topology? | No. The fresh reset did not complete within the bounded 286-second observation window. It remained in artifact construction and never launched a writer, claimant, web-render JVM, or steady-state Bun pod. |
| Was the artifact a stable source snapshot? | No. At least two source files became newly modified while the reset was running, and the watcher repeatedly compiled warnings characteristic of an in-progress cross-file refactor. |
| Was a 10–15-agent load started? | No. Zero agents were launched because there was no ready runtime against which a result would be valid. |
| Are the requested latency, throughput, queue, GC, allocation, runtime RSS, post-load, and 100-agent figures available? | No. They are **unmeasured**, not zero. |
| Why stop? | A cost ledger from a build-only Bun process or a mixed-source artifact would not measure the owner’s question. Stopping at the failed prerequisite avoids manufacturing a benchmark from unrelated work. |
| Cleanup | Complete. `bin/seon down` reports writer, claimant host, web-render, and pod absent; the stale watcher containment was forced down. Final target state is `down` with `missing-artifact`. |

This report records the attempted measurement, the raw evidence, the intended
database derivation, and the exact retry gate. It does not make steady-state
cost or heap recommendations without a steady-state observation.

## Scope and dependency ledger

| Item | Selected value | Evidence or owning source |
|---|---:|---|
| Branch | `codex/runtime-reliability-refactor` | Owner scope |
| Source commit at capture start | `ddb3b9ea0933e933123a44d799e71aabe1b33eda` | `tmp/orchestrator/runtimecost-environment.log` |
| Source state | Dirty before reset; changed again during reset | Pre/post status evidence below |
| Datahike | `9c356e32a0f2b0afcd41ce5000cba2a575a59a8a` | `reference-code/datahike` |
| SCI | `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | `reference-code/sci` |
| JDK | OpenJDK `26.0.1`, 64-bit Server VM | Environment log |
| Operator-selected Bun during build | `1.4.0-canary.1`, revision `d8ecf0985` | Reset log |
| Interactive-shell Bun | `1.3.14` | Environment log; not the selected runtime binary |
| Host | macOS `26.5.2` build `25F84`, 128 GiB physical memory | Environment log plus `hw.memsize=137438953472` |
| Cluster | `runtimecost` | Isolated cluster directory and final status |
| Cluster database path | `data/clusters/runtimecost/db` | Final status |
| Process directory | `tmp/seon-operator-runtimecost` | Reset command |
| Runtime logs | `logs/runtimecost` | Reset command |
| Writer request socket | `tmp/runtimecost-req.sock` | Reset command |
| Host eval socket | `tmp/runtimecost-host.sock` | Reset command |
| Web port file | `tmp/runtimecost-http.port` | Reset command |
| Writer REPL port file | `tmp/runtimecost-writer.port` | Reset command |

The current configuration would request the following ceilings if those
processes launched. These are configuration facts, not measured usage and not
recommendations:

| Process | Current requested heap ceiling | Derivation |
|---|---:|---|
| Writer JVM | 4096 MiB | The writer default is `min(4096, max(512, system-MiB / 16))` in `src/seon/config/resolve.cljc`; this 128-GiB host resolves to 4096 MiB. |
| Claimant host JVM | 4096 MiB | `:seon.config.claim-driver/jvm-heap-mb` in `config/system.edn`; passed by `script/seon/dev/process.clj`. |
| Web-render JVM | 512 MiB | It starts with `-M:writer:host`, whose base `:jvm-opts` has `-Xmx512m` in `deps.edn`; no process launched to confirm the effective command. |
| Bun pod | Not a JVM heap | Bun/JavaScriptCore needs RSS and `process.memoryUsage()` measurements rather than `jstat`. |

## Intended phase-latency derivation

The phase cursor order is the source-defined vector in
`src/seon/agent/turn/core.cljc`:
`[:rendered :attempt-open :reply-ready :evaling :evaled :published]`.
For each phase change, the audit would join the phase attribute’s history datom
to its transaction entity’s `:db/txInstant`. The eval receipt independently
provides `:seon.eval/at` and `:seon.eval/duration-ms` from
`src/seon/eval/receipt.cljc`.

| Requested column | Start | End | Computation |
|---|---|---|---|
| Initial claim-to-render | transaction that installs claimant and epoch | first `:rendered` phase transaction | First-turn-only acquisition cost; report separately from recurring turn cost |
| Render/open | `:rendered` | `:attempt-open` | Receipt/phase transaction timestamp difference |
| LLM API | `:attempt-open` | `:reply-ready` | Provider wait isolated as its own column |
| Parse and eval-dispatch | `:reply-ready` | `:evaling` | Receipt/phase transaction timestamp difference |
| Eval | `:evaling` | `:evaled` | Phase timestamp difference, cross-checked with eval receipt duration |
| Write-back | `:evaled` | `:published` | Receipt/phase transaction timestamp difference |
| Advance | `:published` | next turn’s `:rendered`, or run close | Cross-turn phase timestamp difference |
| End-to-end turn | current `:rendered` | next turn’s `:rendered`, or run close | Sum of recurring phase intervals |
| Coordination/wire overhead | end-to-end turn | N/A | End-to-end minus LLM API minus eval |

The intended load was 12 agents, each running a real multi-step case-file
synthesis: establish a namespaced schema, store distinct evidence as database
facts, query and pull those facts in a later turn, then derive and publish a
conclusion. This exercises database-backed memory across turns without the
retired workout or trading scenarios.

## Fresh-reset attempt

The reset used only cluster-qualified paths:

```text
SEON_CLUSTER_DIR=/Users/sean/src/seon/data/clusters/runtimecost
SEON_PROC_DIR=/Users/sean/src/seon/tmp/seon-operator-runtimecost
SEON_WRITER_PROC_DIR=/Users/sean/src/seon/tmp/seon-operator-runtimecost
SEON_LOG_DIR=/Users/sean/src/seon/logs/runtimecost
SEON_REQ_SOCK=/Users/sean/src/seon/tmp/runtimecost-req.sock
SEON_WRITER_REPL_PORT_FILE=/Users/sean/src/seon/tmp/runtimecost-writer.port
SEON_PORT=0
SEON_PORT_FILE=/Users/sean/src/seon/tmp/runtimecost-http.port
SEON_HOST_EVAL_SOCK=/Users/sean/src/seon/tmp/runtimecost-host.sock
bin/seon cluster reset runtimecost

```

| Local time (EDT) | Elapsed from reset start | Observed event | Raw value |
|---|---:|---|---:|
| 18:29:44 | 0 s | Fresh reset command starts | Reset-log birth time |
| 18:30:04 | 20 s | Managed watcher starts | Owner start `22:30:04.080Z`; workload start `22:30:04.137Z` |
| During reset | N/A | Self-host bootstrap build completes | 4779 ms |
| During reset | N/A | Bootstrap metadata repair completes | 74 ms |
| During reset | N/A | Frozen Bun dependencies checked | 22 ms |
| During reset | N/A | Web CSS build completes | 156 ms |
| 18:30:23 | 39 s | Program-row Bun child launches | PID 30985, parent watcher workload PID 30499 |
| 18:32:38 | 174 s | Build child sampled | Current physical footprint 427.6 MiB; peak 1.1 GiB |
| 18:34:30 | 286 s | Bounded observation stopped by operator signal | Program-row child subsequently reports exit 143 |
| 18:34:39–18:34:44 | 295–300 s | Isolated `bin/seon down` | Completed; watcher stale containment forced down |
| 18:35:00–18:35:05 | 316–321 s | Final isolated status | `down`, `missing-artifact` |

The 427.6-MiB current and 1.1-GiB peak figures belong to the one-off
**program-row derivation build child**, not the steady-state Bun pod. Its stack
sample was predominantly parked in `kevent64` and condition waits. Those
figures are retained as raw build evidence and are explicitly excluded from
the runtime fleet RSS ledger.

The watcher’s terminal hook failure contains
`:seon.dev.artifact/exit 143`. That exit was caused by the deliberate stop, so
it is not evidence of an organic program-row failure. The reset’s inability to
produce a ready artifact within this bounded window and the concurrent source
changes are the measurement blockers.

## Source-freeze failure

The reset began from an already dirty shared checkout. A diff between status
captured immediately before reset and status captured after cleanup found two
newly modified source paths:

| Path | Before reset | After cleanup | Measurement consequence |
|---|---|---|---|
| `src/seon/agent/ctx/namespaces.cljc` | Not listed as modified | Modified | Source-input set changed while artifact work was active |
| `src/seon/host/guard.cljc` | Not listed as modified | Modified | Source-input set changed while artifact work was active |

The watcher also repeated the following cross-file compiler warnings while the
shared tree was changing. They are evidence of a mid-refactor build, not a
diagnosis assigned to this measurement lane:

| Source location in watcher output | Raw warning |
|---|---|
| `src/seon/agent/ctx/canvas.cljc:229,365` | Use of undeclared var `schema/current-projection` |
| Shell facade around line 24 | Use of undeclared var `seon.agent.shell.core/default-timeout-ms` |
| Shell facade around line 120 | Wrong number of arguments, 2, to `seon.agent.shell.core/run-request` |
| Test source around line 1073 | Wrong number of arguments, 1, to `seon.error/fault-for` |

A build/restart checkpoint requires a coherent source-input digest. Even if
this reset had eventually reached readiness, measuring an artifact constructed
while its inputs changed would not provide attributable costs for a named
revision.

## Requested cost ledger

| Requested measure | Raw sample count | Result | Reason |
|---|---:|---|---|
| Agents launched | 0 | No workload started | Runtime never ready |
| Completed measured turns | 0 | Unmeasured | No workload started |
| Claim-to-render latency | 0 transitions | Unmeasured | No runtime database receipts |
| Render/open latency | 0 transitions | Unmeasured | No runtime database receipts |
| LLM API latency | 0 transitions | Unmeasured | No attempts opened |
| Parse and eval-dispatch latency | 0 transitions | Unmeasured | No replies |
| Eval latency | 0 receipts | Unmeasured | No evals |
| Write-back latency | 0 transitions | Unmeasured | No eval completions |
| Advance latency | 0 transitions | Unmeasured | No published turns |
| Coordination overhead per turn | 0 turns | Unmeasured | No end-to-end turn intervals |
| Writer transaction rate | 0 runtime windows | Unmeasured | Writer never launched |
| Writer queue depth | 0 samples | Unmeasured | Writer never launched |
| Writer JVM GC/allocation | 0 `jstat` samples | Unmeasured | Writer never launched |
| Claimant JVM GC/allocation | 0 `jstat` samples | Unmeasured | Claimant never launched |
| Web-render JVM GC/allocation | 0 `jstat` samples | Unmeasured | Web-render never launched |
| Bun steady-state memory and GC proxy | 0 runtime samples | Unmeasured | Steady-state pod never launched |
| Fleet RSS at idle | 0 ready-process snapshots | Unmeasured | No ready fleet |
| Fleet RSS under load | 0 snapshots | Unmeasured | No load |
| Fleet RSS after load | 0 snapshots | Unmeasured | No load |
| Leak-smell delta | 0 paired snapshots | Unmeasured | No pre/post pair |
| 100-agent GC/RSS projection | 0 admissible observations | Not extrapolated | No per-agent slope or base fleet measurement |
| R27 `-Xmx` recommendations | 0 calibrated distributions | No recommendation | P99.9 usage and GC headroom were not observed |

The zeroes in the sample-count column describe this audit’s evidence volume;
they do not assert zero runtime latency, throughput, queueing, allocation, GC,
or RSS.

## Cleanup ledger

| Managed process | Final `bin/seon down` result |
|---|---|
| Web-render JVM | `absent` |
| Bun pod | `absent` |
| Claimant host JVM | `absent` |
| Writer JVM | `absent` |
| Watcher | `forced reason=dead-stale` |
| Target | `status=down`, `failure=missing-artifact`, `branches=[]` |

## Raw evidence

| Evidence | Contents |
|---|---|
| `tmp/orchestrator/runtimecost-environment.log` | UTC capture time, source commit and initial diff summary, dependency SHAs, JDK, shell Bun, and OS |
| `tmp/orchestrator/runtimecost-pre-status.log` | Source status immediately before reset |
| `tmp/orchestrator/runtimecost-reset.log` | Complete isolated reset output and interrupted-startup envelope |
| `tmp/orchestrator/runtimecost-program-rows-sample.txt` | `/usr/bin/sample` output for the build-only Bun child |
| `logs/runtimecost/watcher/0b05dbf6-8bbb-4187-ab8c-606f3c4c5114.log` | Watcher warnings, program-row hook output, and signaled exit |
| `tmp/orchestrator/runtimecost-down.log` | Isolated cleanup result |
| `tmp/orchestrator/runtimecost-final-status.log` | Final target state |
| `tmp/orchestrator/runtimecost-final-ps.log` | Final process inspection; no managed runtime process remained |
| `tmp/orchestrator/runtimecost-post-status.log` | Source status after cleanup |

## Retry gate

| Gate | Required evidence before collecting costs |
|---|---|
| Frozen revision | Record one commit and source-input digest; no build input changes from reset start through the final post-load sample |
| Fresh cluster | Reset only `runtimecost`; retain cluster-qualified directories, sockets, ports, and logs |
| Ready topology | Writer, claimant host, web-render, and Bun pod each pass operator readiness |
| Workload | Launch 12 case-file agents with at least three turns and a later-turn database query/pull of earlier facts |
| Latency | Export every phase-history transaction timestamp and eval receipt; preserve both per-turn raw rows and percentile summaries |
| Writer pressure | Sample transaction-count delta and writer queue depth at one-second cadence |
| JVM pressure | Run `jstat -gc` or JFR at one-second cadence for writer, claimant, and web-render; report allocation estimate, collection counts, and pause durations |
| RSS | Sample every managed PID at idle, throughout load, and for a fixed cool-down interval after all runs close |
| Bun | Record `ps` RSS plus `process.memoryUsage()` for the steady-state pod surfaces only |
| Extrapolation | Separate fixed fleet cost from measured per-active-agent slope; label the 100-agent result as extrapolated and retain confidence limits |
| R27 calibration | Recommend heap ceilings only from observed high-water marks, GC headroom, and a documented multiplier relative to legitimate P99.9 behavior |
| Cleanup | Run isolated `bin/seon down` and prove every managed process absent |

Until those gates are satisfied, the all-JVM topology’s steady-state
coordination cost, GC headroom, fleet RSS, and appropriate R27 heap ceilings
remain open measurements.
