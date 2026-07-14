---
type: research
status: completed
tags: [research, prd, flow, database, web, agent]
---

# Local performance graduation source audit — 2026-07-14

## Decision

Unit 9 is a final admission and evidence unit, not a place to finish subsystem
behavior. It must not run its destructive matrix until units 1–8 have graduated
their own correctness matrices and every open blocker has a disposition. The
green unit-0 reset and 2026-07-12/13 profiles are useful comparisons, but none
is a current unit-9 performance result.

The default runtime is materially better bounded than those oldest profiles:
the CLJS JVM has one `-Xms256m -Xmx3g` policy, the operator owns the canonical
watcher and test artifact, the transcript is bounded, Datahike reports effective
datoms and has query/pull/result/cache budgets, retained results are admitted by
weight, and the Datastar feed has normalized subscriptions plus one-event
latest-wins backpressure. Those are regression cases, not open findings.

The final measurement surface remains incomplete. `bin/seon status --edn`
proves identity, ownership, endpoints, artifact digests, liveness, and
readiness, but not CPU, RSS, V8/JVM heap, event-loop delay, feed pressure, or
phase durations. Unit 9 needs bounded, on-demand measurement around the
existing operator/feed. It must not add a benchmark runtime, polling daemon, or
rolling telemetry database.

## Scope and method

This audit read the architecture, program roadmap, all eight predecessor
roadmaps and current audits, older compiler/render/database-memory/lane reports,
current source/tests, and exact dependency source where present. Read-only
probes used the public operator, writer REPL, HTTP/feed doors, `ps`, `jcmd`, and
filesystem allocation. No reset, restart, write, paid model, browser mutation,
ACME checkout access, or cleanup occurred.

The snapshot began at repository commit
`92d0d4c043da69caa3b5d95a0b47934b04b543c3` on 2026-07-14. Concurrent work may
advance it; every final sample records its own identities.

## Exact dependency and tool ledger

| Boundary | Selected identity and exact source | Measurement constraint |
|---|---|---|
| Node/V8 | Node `26.4.0`, V8 `14.6.202.34-node.21`, libuv `1.52.1`, zlib `1.2.12`; exact embedded JavaScript is exposed by `process.binding("natives")` for `internal/process/per_thread`, `internal/perf/event_loop_delay`, `perf_hooks`, and `v8`; installed headers are under `/opt/homebrew/Cellar/node/26.4.0/include/node/` | Use `process.memoryUsage`, `process.cpuUsage`, `monitorEventLoopDelay`, `eventLoopUtilization`, and `v8.getHeapStatistics` in one explicitly activated sampler. Retain RSS and V8 heap separately. No exact Node checkout exists in `reference-code/`, so unit 8's manifest must bind the runtime before portable final evidence. |
| Datahike | maintained SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc`; exact `reference-code/datahike` | Immutable values, effective datoms, work/result budgets, weighted query-cache admission, history, and indexed traversal are library-owned. Benchmark through `seon.db`/writer protocol at a complete coordinate, never raw storage or uncapped private APIs. |
| Konserve | maintained SHA `df6818d43ea3363a808cd051c0d68917f1b987a9`; exact `reference-code/konserve` | Measure reopened databases through Datahike plus closed directory allocation/write amplification. Raw files do not establish database identity. |
| Shadow | selected `3.4.10`; exact release commit `d3c04691952aa9ea33f7287ffe9a2b3109c1e510` exists in `reference-code/shadow-cljs` history | One watcher owns default `client` and canonical `test`. Inspect exact-release worker/build state and JVM heap; do not start another watcher or use later checkout head `8236315a…` as authority. |
| Datastar client | shipped `resources/public/js/datastar.js` identifies `1.0.0-RC.7`, SHA-256 `c9c8b99715d759df4543d4e01d6e6fe4b3940e4dee57ec9cde7eb344e86c61e2`; upstream history is in `reference-code/datastar` | The shipped immutable asset, not working head `bb9ed6fb…`, is the browser identity. Measure browser parse/morph and long tasks. |
| Datastar Clojure reference | RC7, `reference-code/datastar-clojure` SHA `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | Reference framing/redirect idioms only. Seon's CLJS server remains the active mechanism. |
| gzip/SSE | Node zlib above; `src/seon/web/datastar.cljs` uses `createGzip`, `Z_SYNC_FLUSH`, normalized subscriptions, per-socket pending event, and one heartbeat timer | Retain compressed wire bytes, decoded event bytes, first-frame/render/write time, drain transitions, and pending replacements separately. Server-side gunzip is required because browser bridges can fail long-lived SSE. |
| Browser | Chrome `150.0.7871.115`; workflow in `.agents/skills/browser-automation/SKILL.md` | Use fresh agent-owned tabs/profiles, exact viewport/cache/profile metadata, finite browser traces, and separate server gzip proof. |
| Babashka/operator | Babashka `1.12.212`; `reference-code/babashka-process` is tag `v0.6.25`, SHA `16a84e0a…`, but is not proven to be the binary's embedded revision; first-party authority is `bin/seon` plus `script/seon/dev/{cli,process,state,artifact,config}.clj` | `bin/seon` is the only lifecycle door. `bb.edn` does not pin the executable/embedded process library, so retain `bb --version`, bind the binary in unit 8's manifest, and resolve the embedded-library identity before claiming exact-source reproduction. Do not add a shell startup path. |
| HTTP/JVM tools | curl `8.7.1`; OpenJDK `26.0.1`; writer `-Xmx2g`; compiler `-Xms256m -Xmx3g`; `jcmd`, `jstat`, `vmmap`, `ps` | curl measures finite requests/server first frames, not browser interaction. Sample JVM/process diagnostics only at explicit checkpoints; RSS alone is not a leak test. |

Captured root dependency hashes were `deps.edn` `85e63ca…`, `bb.edn`
`646f672d…`, `shadow-cljs.edn` `ba5422e…`, and `package-lock.json`
`2bbbbcf8…`. Final manifests retain full hashes.

## Predecessor admission ledger

Unit 9 opens only when every row is green. “Audited” is not admission.

| Unit | Current state | Required handoff |
|---|---|---|
| 1 database lifecycle/recovery | Grounded, not graduated | Fresh/converged/config-free reopen, complete coordinate, fork/as-of/restore/undo, restart/crash/replay, and canonical receipt/schema evidence. |
| 2 reactive render units | Grounded, not graduated | One observed unit transition, helper-indirected/lazy-read correctness, unrelated-write zero work, equivalent-tab reuse, and per-unit timing/weight evidence. |
| 3 database browser | Grounded, not graduated | Bounded index cursors and closed-detail zero work for entity/ref/transaction/provenance/as-of/history. |
| 4 root workspace sessions | Grounded, not graduated | Dedicated root layout and two-tab database-backed location/provenance through reload/reconnect/deletion/restart/reset. |
| 5 canvas interaction | Grounded, not graduated | Full controls, errors, duplicate-submit, focus/pin/clear, reactive morph, and narrow/wide browser matrix through one path. |
| 6 agent runtime correctness | Grounded, not graduated | Raw reply, complete-form, awaited instrumentation, cancellation, plan authority/evidence, and hard eval containment. |
| 7 Inspect/autocomplete | Grounded, not graduated | Pinned sources, operator lease, canonical export, reviewed ACME handback, and reproducible deterministic/local-model/reference evidence. |
| 8 independent distribution | Audited, not graduated | Reproducible no-source writer/runtime/SDK/operator and simultaneous default/downstream proof with compatibility manifest. |

The admission projection verifies each handoff's source, artifact, config,
database-coordinate, test, and evidence hashes. A copied prose status is not
enough.

## Current read-only smoke baseline

These already-warm small-database numbers neither set budgets nor graduate
anything.

| Observation | Value |
|---|---:|
| Operator | ready; watcher PID 21277, writer 21496, pod 21498 |
| Artifacts | application `6657ae10…`, writer `c89b0be…`, client `d7bf5e69…` |
| Database | branch `:db`, basis `536870929`, commit UUID `6a56b426-c836-5817-9f6b-20584f2e81d5`; 15,851 datoms |
| Database allocation | about 547 MiB |
| Warm `/`, five samples | 1.65–2.73 ms; 1,405 decoded bytes |
| Warm `/data`, five samples | 60.1–87.0 ms; 2,908 bytes |
| Warm `/agent/root/debug`, five samples | 2.73–3.47 ms; 3,811 bytes |
| Root/data first decoded feed frame | 246.7 ms / 9.1 ms; 3,089 / 451 bytes in two-second windows |
| Pod RSS | about 428 MiB |
| Writer RSS / committed / used heap | about 542 MiB / 324 MiB / 35 MiB |
| Watcher RSS / committed / used / old heap | about 2.20 GiB / 1.65 GiB / 1.47 GiB / 432 MiB |
| Five one-second idle CPU samples | watcher 0–0.1%; writer/pod 0% |

The watcher also held 254 MiB of humongous regions and 153 MiB metaspace. One
point cannot establish a leak. It only confirms that the former host-sized
30 GiB maximum is gone while retained-floor measurement remains necessary.

## Reconcile older profiles against current source

### Fixed; regression-test only

- The unbounded compiler JVM policy is replaced by the one `:cljs` alias cap.
- Default/downstream flavors own distinct builds/caches; default alone owns
  the canonical test artifact.
- Reasserted identity no-ops no longer manufacture changed facts.
- Normal transcript rows are computed bounded summaries, not hidden expanded
  technical bodies.
- Datahike query/pull work/result, weighted cache, and retained eval values have
  hard bounds.
- Equivalent feeds share normalized subscription render authority and each
  socket retains only its newest pending event.

### Comparison evidence, not current results

- Repeat the old Shadow retained-floor protocol at exact 3.4.10/current source;
  do not copy its 533-source or 2.6 GiB values.
- The old grown-store 134–208 ms broadcasts and earlier 330–365 ms paths retain
  useful workload shapes, but unit 2 changes granularity/ownership.
- The unit-0 313 ms root render and 657 debug observations are pre-unit-2
  falsification cases, not target baselines.
- Preserved databases establish realistic capacity obligations, but current
  dependencies must not open them until historical archive/read-back gates pass.

### Current unit-9 gaps

- Operator status lacks CPU/RSS, JVM/V8 heap, event-loop delay/utilization,
  feed pending/drain pressure, and uptime.
- Broadcast logs do not join render, serialization, gzip/write, browser morph,
  payload weight, and full database coordinate.
- No final fixture manifest defines fresh/grown/history/simultaneous scale
  without an old worktree.
- No machine-readable distribution joins metrics to complete source, artifact,
  config, dependency, browser, host, and database identity.

## Cold/warm definitions and budget protocol

- **Build-cold:** pinned dependencies installed; selected flavor's Shadow cache
  and outputs absent. Five independent samples; retain every phase, median,
  maximum. Do not flush OS/shared dependency caches implicitly.
- **Process-cold:** immutable artifacts exist; target-owned processes start via
  the public operator. Twenty samples; nearest-rank p50/p95/max.
- **Restart-warm:** same artifact/database ready ≥30 seconds, then public
  restart through identity-preserving read-back. Two throwaways, 20 samples.
- **Request-warm:** same ready coordinate class; ten throwaways, 100 samples;
  p50/p95/p99/max. Write samples retain pre/post coordinates.
- **Journey-warm:** warmed assets and a fresh tab/session; 30 full journeys;
  p50/p95/max. Cold browser uses ten fresh profiles and retains all samples.
- **Retained-floor:** before work, fixed work counts, and declared natural idle.
  Never force GC in acceptance; label any diagnostic forced-GC arm separately.

Use monotonic clocks. Keep first byte, first decoded event, server render,
serialization, gzip/write, browser morph, and full interaction separate. CPU is
process CPU/wall time with core count. Disk has allocated/logical bytes. Payload
has compressed/decompressed/HTML bytes, DOM nodes, and estimated visible tokens.

### Provisional guardrails and freeze rule

After units 1–8 pass, retain one clean reference-host characterization and
freeze final budgets. Guardrails below may be tightened automatically; loosening
requires an issue, raw before/after evidence, and owner approval.

| Surface | Provisional guardrail |
|---|---|
| Packaged writer+pod process-cold readiness | p95 ≤15 s; max ≤30 s, build excluded |
| Development watcher+writer+pod readiness | p95 ≤60 s; max ≤120 s, cache class recorded |
| Warm shell/root/debug GET | p95 ≤50 ms; p99 ≤100 ms |
| Warm `/data` shell/catalog on grown fixture | p95 ≤150 ms; p99 ≤250 ms |
| Warm first decoded feed event | p95 ≤500 ms; max ≤1 s |
| Normal transaction to browser morph | p95 ≤500 ms; max ≤1 s; structural p95 ≤750 ms/max ≤1.5 s |
| Unrelated transaction | zero renderer invocation, element bytes, and unaffected DOM morph |
| Warm CLJ / CLJS MCP scalar/read | CLJ p95 ≤100 ms/p99 ≤250 ms; CLJS p95 ≤250 ms/p99 ≤500 ms |
| Grown bounded database page/query/pull | p95 ≤100 ms; p99 ≤250 ms; budget error within same max |
| Mock-provider local turn overhead | p95 ≤250 ms; model/network/provider time separate |
| Pod event-loop delay at 10 ms resolution | idle p99 ≤20 ms; workload p99 ≤100 ms; max ≤500 ms |
| Idle CPU after 60 s | median <1% and p95 <2% per process; no periodic render/db work |
| Packaged steady RSS on grown fixture | pod <768 MiB, writer <768 MiB, combined <1.5 GiB |
| Development steady RSS | watcher <2.75 GiB; total <4 GiB |
| JVM retained floor | watcher old <70% of 3 GiB, writer used <70% of 2 GiB; no positive 10/50-work slope |
| Pod V8 retained floor | used/external/array-buffer return to ±10% band after three equivalent cycles; no monotonic RSS rise |
| Feed pressure | ≤1 pending event/socket, drains to zero, no stale push or unbounded buffer |
| Ordinary unit patch | decoded ≤256 KiB and wire ≤64 KiB unless accepted content itself exceeds it |
| Idle disk | zero database/blob/log growth over ten minutes except declared bounded rotating heartbeat logs |

The host manifest records hardware, OS, power mode, free memory/disk, concurrent
processes, all runtime versions, and separately owned clusters. Retain noisy
samples with a marker; never silently delete them.

## Retained raw evidence

```text
evidence/local-performance/<run-id>/
  manifest.edn
  samples.jsonl
  journeys.jsonl
  processes.jsonl
  feeds.jsonl
  browser/
  logs/
  checksums.sha256
```

The manifest carries run/timezone, Git status, predecessor handoff hashes,
artifact/config/source/dependency/lock digests, host/runtime versions, fixture
hashes, full initial/final database coordinates, operator command, cleanup
authorization, and every raw digest.

JSONL rows use namespaced keys and carry run/sample id, metric/unit, monotonic
times, phase/class/repetition, process/cluster/artifact identity, database
coordinate, value, budget, pass?, and error data. Feed rows add
subscription/unit/view, render/serialize/compress/write/browser times,
compressed/decompressed bytes, pending/drain counts, elements, and coordinates.
Browser rows add Chrome/profile/viewport/session/route/action, long tasks, DOM
nodes, console/network, and trace/screenshot hashes.

Retain raw logs, finite HTTP output, decoded feed frames, browser
trace/screenshot/console/network, native Inspect logs, process/JVM snapshots,
filesystem manifests, and test reports. Close and SHA-256 the directory, verify
from a second extraction, then derive summaries from JSONL.

## Destructive acceptance matrix

Run only after predecessor admission, one slice at a time, without overlapping
CLJS suites or benchmark processes.

| Order | Fixture/failure | Required proof |
|---:|---|---|
| 0 | Admission | Verify unit 1–8 manifests, blockers, host capacity, cleanup prohibition, and no unmanaged listeners. |
| 1 | Producer build | Five build-cold default/release assemblies; reproducible hashes, phases, peak resources, no mutable latest. |
| 2 | Fresh default | Public destructive reset; root+ordinary agent, convergence, both MCPs, routes, gzip frames, fresh budgets. |
| 3 | Converged/config-free | Identical apply writes nothing; stop/reopen without config; exact coordinate/fact read-back. |
| 4 | Deterministic grown fixture | Through public bounded doors, content-addressed seed reaches at least 1M datoms, 100 agents, 10K turns, 100K eval summaries, wide refs/history/large bounded blobs; retain achieved scale/disk. |
| 5 | Grown read/UI | Database budgets/cursors/history, root fleet, agent, debug laziness, and related/unrelated render units pass without scans. |
| 6 | Restart | Twenty public restarts preserve database/artifact, MCP discovery, session/plan/canvas/feed/result read-back within budgets. |
| 7 | Crash/fault | Unit-1-approved writer/pod/watcher and commit/feed injection proves repair, no duplicate effects, honest faults, bounded recovery. |
| 8 | History | As-of/fork/diverge/undo/restore/delete and coordinate URLs round-trip within budgets. |
| 9 | Browser | Real Chrome root→agent→canvas→two tabs→data/history→reconnect→restart→recovery; server gunzip proves feeds. |
| 10 | Agent/runtime | Malformed/incomplete/async/timeout/retry/plan cases and hostile eval containment; next work proceeds. |
| 11 | Inspect/model | Deterministic/offline Inspect, local simple-model ladder, and unit-7 owner-authorized bounded reference arm; native logs retained, provider latency separate. |
| 12 | Released downstream | Seon source inaccessible; clean ACME build/customize/start/MCP/restart/upgrade/read-back while default runs; mixed manifest rejects pre-write. |
| 13 | Soak/floor | Three grown workload/idle cycles, 50 ordinary reloads, macro reload, reconnect/backpressure storm, ten-minute idle; stable queues/resources/disk. |
| 14 | Full gates | One non-overlapping operator/writer/pod/offline-Inspect/package/browser checkpoint on exact artifacts. |
| 15 | Evidence close | Stop only run fixtures; close/hash/verify package and read back representative database/evidence facts. |

Seed grown data in bounded batches with receipts and stop on a resource error.
Fixture dimensions may change before freeze only from retained representative
evidence, never to make a failing run easier.

## Cleanup authorization and preservation gates

Cleanup is separate from a green matrix. For every candidate:

1. Re-run worktree/status/ignored-file, process working-directory/open-file,
   port, data/blob size, and commit-divergence inventory immediately before it.
2. Match the preservation manifest and classify every unique commit, patch,
   ignored artifact, database/blob, scorer/output, and dependency identity.
3. Quiesce only that lane in a maintenance window and capture final database
   identity after producers stop and before its historical writer stops.
4. Package closed bytes in owner-approved durable storage, verify a second
   extraction, and read back with exact historical dependencies/network denied.
5. Record archive URI/digest, restore command, fact/blob read-back,
   replacement/supersession, and owner acceptance.
6. Obtain destructive authorization naming the candidate and command. One
   authorization never covers another.
7. Remove one worktree before its branch, never by reset/discard/port-wide kill;
   recheck current clusters and archive hashes after each action.

Only detached `seon-plan-fix` was technically eligible for later authorization;
none was granted here. Every other legacy lane retains a preservation or
replacement gate. The separately owned active ACME lane stays outside unit-9
control until accepted unit-7 handback and unit-8 packaging evidence.

## Ordered implementation slices

1. **Admission compiler:** fail closed over predecessor manifests/issues before
   destructive or model work.
2. **Measurement envelope:** add bounded on-demand process/V8/JVM/event-loop/
   payload/feed/phase observations to the existing operator/feed only.
3. **Fixture/evidence schemas:** register namespaced run/sample/fixture shapes,
   content-addressed layout, summarizer, checksum, and read-back.
4. **Characterize/freeze:** after units 1–8, retain the reference-host run and
   freeze budgets or record owner-approved evidence-backed exceptions.
5. **Run matrix:** fresh→grown→restart→crash→history→browser→runtime→Inspect→
   downstream; regressions return to their predecessor owner.
6. **Final checkpoint:** one exact-artifact gate, then close, verify, and read
   back the evidence package.
7. **Authorized retirement:** re-audit and remove one approved lane at a time,
   proving current clusters and archives after each.

## Graduation rule

Unit 9 graduates only when every predecessor handoff is accepted, final budgets
are frozen and green across defined distributions, the whole destructive matrix
passes one exact artifact set, raw evidence is independently verified, the
no-source journey passes beside default, and each cleanup has its own
authorization and preservation proof. A fast small database, unit-0 reset, or
manually viewed page cannot substitute.
