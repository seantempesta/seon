---
type: prd
status: planned
tags: [prd, flow, database, web, agent]
---

# Local performance graduation roadmap

## Outcome

The complete local system passes one destructive, reproducible acceptance
matrix with explicit latency/resource budgets, then safely retires every
superseded lane whose evidence and data have been preserved and read back.

## Current state

Unit 0 has a green default reset, full operator/writer/pod/offline-Inspect gates,
CLJ+CLJS MCP read-back, browser/static checks, gzip frames, restart, database
budgets, and retained-result proof. Those are a checkpoint, not final
graduation: successor behavior, grown-database budgets, full interaction
journeys, paid/simple-model evidence, downstream packaging, and authorized lane
retirement remain.

The grounded audit
[[research/local-performance-source-audit-2026-07-14]] reconciles current
source with the older
[[research/shadow-compiler-memory-profile-2026-07-13]]. The CLJS JVM cap,
canonical build ownership,
bounded transcript, Datahike work/result/cache budgets, retained-result
admission, effective transaction datoms, normalized subscriptions, and
one-event feed backpressure are landed and must be regression-tested rather
than reopened as current findings. Unit 9 still lacks a retained operator
measurement envelope for V8/JVM heap, event-loop delay, CPU/RSS, feed pressure,
phase/payload/browser timings, and complete source/artifact/database identity.

The Bun source audit
[[research/bun-production-runtime-integration-audit-2026-07-15]] establishes a
candidate full JavaScript-runtime migration after exact-artifact parity: Shadow
remains the JVM compiler while Bun may run development pods, test/worker
artifacts, and packaged pods. Shadow 3.4.10 already declares Bun support and its
`:node-script` output needs no new compiler target. The first implementation
boundary is one manifest/launch runtime coordinate replacing Seon's hard-coded
Node execution doors. Bun-native HTTP is now a measured priority: direct
`Bun.serve` improved sequential throughput by 24% and bounded-concurrency
throughput by 62%, while plain idle SSE used 43.09 MiB at 100 feeds. Per-feed
gzip is the dominant avoidable feed cost; shared encoding reduced isolated
100-event by 100-feed fanout CPU from 269.4 ms to 0.99 ms, subject to browser
and proxy proof. Native Bun UDS produced about 3 times compact-frame throughput;
native spawn was neutral. A real isolated Bun pod reached database,
instrumentation, HTTP, agent, and Datastar-feed readiness before an overlapping
external Shadow owner caused its supervisor to drain it. JSC diagnostics,
bounded execution cells, shared-writer families, and idle pods remain candidates
rather than graduation evidence. The audit also records the independent UDS fragmentation cost in
[[../../seon/issues/uds-fragment-accumulation-recopies-complete-prefix]].
The removal-first implementation and exclusive source-freeze cutover are carved
in [[../bun-native-runtime-simplification/roadmap]]. That successor assumes all
other build/lifecycle lanes are paused, replaces every host boundary in one
coordinated window, deletes the Node compatibility tail before the first final
artifact, and returns here for the integrated browser/load/cluster graduation.

The audit defines cold/warm sample classes, provisional guardrails and their
post-unit-8 freeze rule, a content-addressed raw evidence schema, the ordered
fresh/grown/restart/crash/history/browser/agent/Inspect/downstream/soak matrix,
and candidate-specific cleanup preservation/authorization gates. Its read-only
warm smoke baseline is comparison evidence only, not graduation.

## Ordered work

1. Implement one fail-closed admission projection over units 1–8 manifests and
   open-blocker dispositions; do not start destructive/model work before it is
   green.
2. Finish authority-consumer migration and prove fresh/converged initialization,
   publication, restart, and recovery without races. Do not collect the final
   startup or complete-render baseline while those dependencies are changing.
3. Add bounded on-demand measurements to the existing operator/feed for
   program construction/encoding/transfer/diff/transaction, child spawn and
   bootstrap, CLJ/CLJS MCP, database hop/cache work, local turn overhead,
   render/serialize/gzip/socket/browser phases, rerender counts, event-loop
   delay, CPU, JVM/V8 heap, RSS, disk, and feed pressure. Do not add a benchmark
   runtime or telemetry store.
4. Measure the existing complete renderer and single Datastar feed. Only when
   the evidence identifies material waste may closed transcript, debug, and
   plan bodies become independently demanded within that same renderer/feed.
   Program-admission optimization likewise follows measured phase evidence and
   must retain exact, idempotent, race-free publication.
5. Treat native `Bun.serve` as a separate outer-transport replacement. It may
   replace HTTP/SSE hosting after semantic parity, but it does not introduce a
   second router, renderer, feed, or cache.
6. Register the content-addressed fixture/run/sample evidence shapes, then run
   the post-unit-8 reference-host characterization and freeze the budgets.
7. Run the ordered destructive fresh/grown/restart/crash/history/browser/
   agent/Inspect/no-source-downstream/soak matrix through production doors.
8. Investigate every regression at its predecessor owner; rerun only affected
   slices, then one non-overlapping exact-artifact checkpoint and independently
   verify/read back the raw evidence package.
9. With candidate-specific owner authorization, preserve, verify, read back,
   and remove one eligible legacy process/worktree/data lane at a time; prove
   current clusters and retained archives remain unaffected after each action.

## Graduation

- Units 1–8 are complete with no deferred blocker hidden in this PRD.
- Every defined correctness and performance budget is green across retained
  cold/warm samples, including grown database and simultaneous cluster use.
- The real-browser journey covers root, ordinary agent, canvas controls,
  sessions, database history, reconnect, restart, and failure recovery.
- Released artifacts support the downstream no-source journey.
- Authorized cleanup preserves all required evidence/data and leaves only the
  intended current processes, worktrees, branches, and artifacts.
