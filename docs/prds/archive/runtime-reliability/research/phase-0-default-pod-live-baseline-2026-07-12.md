---
type: research
status: completed
tags: [research, database, web, agent]
---

# Phase 0 default-pod live baseline — 2026-07-12

## TL;DR

The default pod is healthy at `http://127.0.0.1:7890`, but warm agent creation
is a cluster boot in disguise. Five sequential `/agents/new` requests took
8.98–9.57 seconds (mean 9.33 seconds). A converged mint committed eight
transactions, rebuilt the complete program graph twice, replayed every stored
namespace, globally instrumented 700 functions, and reinstalled the ticker.

The duplicate program scan is the main Node event-loop blocker. A mint with no
extra benchmark feed produced a 6.51-second maximum event-loop delay and two
consecutive two-second HTTP probe timeouts. An extra roster feed added about
one second of full-roster render work, but a control without that feed still
stalled longer. The feed fan-out is real scaling work; it is not the primary
cause of slow minting.

The CPU profile identifies the exact duplicate path. `core-index-tx` calls
`index-core!`, whose `var->fn-row` calls `extract-form-at-line`; that function
splits and rejoins a complete source file for each var. `prune-core-ghosts!`
then invokes the same builders again. The two `clojure.string/split` stacks
dominated the sample.

Two correctness defects also surfaced:

- every replay attempts to load stale database namespace
  `:seon.render.live-tile` and persists a Malli registration failure; and
- Datahike's persistent temporal-set implementation treats an existing `false`
  value as absent, so reasserting the converged
  `:seon.config.render/line-numbers false` fact creates a fresh history datom on
  every mint.

No production source was changed during this baseline. All created agents were
preserved. The ACME pod/store was left alone.

## Scope and measurement constraints

This drive used only the default pod, writer, store, logs, HTTP routes, gzip SSE
clients, Datahike history queries, Node process metrics, and a temporary Node
inspector profile. Runtime-only profiler and event-loop globals were removed
after each drive.

A separately owned research lane used the same default pod before this drive,
created `NvN-2607121145` and `gnL-2607121145`, and restarted the pod at
15:47:50Z. Its separate high-CPU processes also contaminated host-wide
wall-clock/load figures. Process-local event-loop and CPU measurements remain
useful, but acceptance timing needs one later clean-host repetition.

The wire server was not deliberately cold-restarted because the other lane was
active. Its existing process began at 11:04 local time and its log has no
timestamp on the Datahike-ready line, so a cold writer-open decomposition was
not inferred. The in-app browser automation backend returned no browser tabs;
UI proof here is HTTP plus decoded server-side gzip SSE, not a visual browser
acceptance pass.

## Store and process state

Immediately before the controlled mint series, the externally restarted pod
was PID 27394 and the existing wire server was PID 18949. The pod answered on
port 7890. The restart opened a store with 3,031 Konserve keys and roughly 77 MB
of store files.

The observed database moved from basis 536871063 to 536871129 over the eight
controlled mints in this document. Final state was:

- 17,948 live datoms;
- 18,702 history events;
- roughly 135 MB of store files;
- roughly 504 MB of blobs; and
- 5,059 store files.

The eight mints therefore added exactly 66 transactions and approximately 58
MB / 2,028 store files. These storage figures are intentionally approximate:
the before/after filesystem measurements include Konserve persistence details,
not a claim that every transaction has a fixed storage cost.

## Converged pod restart

The restart was initiated by the other lane, but its timestamps provide a
usable warm-pod decomposition:

| Stage | Timestamp | Elapsed from `-main` |
|---|---:|---:|
| Router installed | 15:47:51.726Z | before `-main` |
| `-main` boot | 15:47:52.015Z | 0.000 s |
| In-process Datahike smoke pass | 15:47:52.242Z | 0.227 s |
| Store open logged | 15:47:52.266Z | 0.251 s |
| Cluster connection logged | 15:47:52.285Z | 0.270 s |
| Wire feed live | 15:47:52.489Z | 0.474 s |
| Crash recovery closed two runs | 15:47:53.440Z | 1.425 s |
| Agent roster derived | 15:47:53.556Z | 1.541 s |
| Ghost set identified and logged | 15:48:03.431Z | 11.416 s |
| Ghost retraction complete | 15:48:03.986Z | 11.971 s |
| Replay complete | 15:48:04.184Z | 12.169 s |
| Global instrumentation complete | 15:48:04.355Z | 12.340 s |
| HTTP listening | 15:48:04.477Z | 12.462 s |
| Auto-boot ready | 15:48:04.487Z | 12.472 s |

Supervisor start-to-ready was approximately 14.49 seconds. A prior captured
restart on the same day took 9.40 seconds from `-main` to ready with no ghosts,
so ghost roster drift materially changes restart work.

This restart pruned 13 core-seeded rows. The first controlled mint then pruned
six more `seon.error/*` rows despite no deliberate source change in this lane.
That is direct evidence that the hot-reload var roster and the stored program
graph can disagree; pruning is compensating for unstable inputs rather than
representing a deterministic lifecycle transition.

## Five sequential warm agent mints

All five requests returned HTTP 200 and their agent entities remain in the
default store.

| Mint | Agent | Wall time | Basis delta | Transactions | Pod RSS sample |
|---:|---|---:|---:|---:|---:|
| 1 | `dfD-2607121151` | 9.568800 s | 063 → 073 | 10 | 531.9 MiB |
| 2 | `wOR-2607121151` | 9.088402 s | 073 → 081 | 8 | 705.2 MiB |
| 3 | `hJW-2607121151` | 8.980020 s | 081 → 089 | 8 | 839.6 MiB |
| 4 | `hVA-2607121151` | 9.528380 s | 089 → 097 | 8 | 967.5 MiB |
| 5 | `yOq-2607121151` | 9.486247 s | 097 → 105 | 8 | 1,099.1 MiB |

The first mint's two extra transactions were one six-row ghost retraction and
one lazy native-schema installation for `:seon.agent/purpose`. The monotonic
RSS samples later partially reclaimed through GC; they demonstrate heavy
allocation/sawtoothing, not by themselves a retained leak.

Across the five requests:

- 42 transactions committed;
- five complete program replays ran, each reporting three namespaces, two
  successes, and one failure;
- five global instrumentation passes ran, each ending at 700 instrumented
  functions;
- the ticker was reinstalled on every request;
- the already-open agent feed rendered 16 broadcasts totaling at least 1.115
  seconds, with a 102 ms maximum individual render; and
- no SCI deadline/budget warning was logged.

The current logs do not count successful SCI invocations. The store had no
pinned `:seon.render.canvas/content`, so this roster-only drive cannot infer a
zero or nonzero SCI call count. Phase 0 observability must add the counter
before that acceptance claim is possible.

## Transaction decomposition

Datahike history from basis 536871063 through 536871129 contains exactly 66
transactions and 369 history events:

| Transaction origin | Count |
|---|---:|
| `:agent` | 32 |
| `:core-seed` | 25 |
| `:config` | 8 |
| No application origin | 1 |

A converged mint still creates eight transactions:

1. entity-schema seed: no domain delta, only transaction metadata;
2. core singleton seed: no domain delta, only transaction metadata;
3. program-index seed: no domain delta, only transaction metadata;
4. declarative config reconcile;
5. one persisted replay error for stale `:seon.render.live-tile`;
6. the new agent entity;
7. its context/render component graph; and
8. its home namespace require edges.

The first three are especially important: `boot-seed!` always calls three
separate `db/transact!` operations even when their derived `tx-data` is empty or
already converged. Transaction metadata makes each call a real commit. A
general Datahike diff is unnecessary here; each builder already knows whether
it produced a delta and should avoid calling transact when it did not.

`seon.state/reconcile!` has the same shape at a larger granularity: it always
submits every desired entity plus stale retractions. The target exact compiler
must compare managed facts first and submit only the needed assertion and
retraction operations. A converged desired set must return without a
transaction.

### Datahike `false` temporal-history bug

The config history query found 19 separate assertions of
`[2736 :seon.config.render/line-numbers false]`, one at each config reconcile,
while other unchanged cardinality-one values have only their original
assertion.

The cause is in
`reference-code/datahike/src/datahike/index/persistent_set.cljc` in
`temporal-upsert`. It destructures the existing datom value into `old-val` and
then tests `(if old-val ...)`. An existing boolean `false` therefore takes the
"no old value" branch and is inserted into temporal history again. Both the add
and retract branches use the same truthiness test.

The source fix is to distinguish presence of an old datom from truthiness of
its value. That Datahike fix stops false-value history corruption, but it does
not make Seon's unconditional reconcile transaction free: the Seon caller must
still compile and transact only the actual delta.

## Event-loop and feed isolation

An in-process `perf_hooks.monitorEventLoopDelay` monitor used 10 ms resolution.
Its idle floor is therefore close to 10 ms and should not be interpreted as
application latency.

### Idle control

Over roughly 20 seconds with only the pre-existing human agent feed and 20 root
HTTP probes:

- mean delay: 10.915 ms;
- maximum delay: 33.751 ms;
- p95: 11.051 ms;
- process CPU: 190.357 ms user + 37.138 ms system; and
- RSS at readout: approximately 166.8 MiB.

Most root probes completed in 3–6 ms; one took 17 ms.

### Mint without an extra benchmark feed

Mint `Bsf-2607121154` took 13.352744 seconds under a noisy host. Process-local
measurements were:

- maximum event-loop delay: 6,505.366 ms;
- mean delay: 15.274 ms;
- process CPU: 13,182.845 ms user + 328.482 ms system;
- two consecutive two-second root probe timeouts; and
- three renders for the existing agent feed totaling 243 ms.

This proves the multi-second stall exists without an extra roster feed.

### Mint with an extra roster gzip feed

The initial `/agents/feed` frame for 11 agents arrived in 140 ms and represented
approximately 3,894 tokens of decoded HTML. Mint `uzz-2607121153` then took
11.133688 seconds and produced:

- maximum event-loop delay: 5,167.383 ms;
- process CPU: 12,714.323 ms user + 234.767 ms system;
- eight roster update frames, approximately 35,755 decoded tokens total;
- eight full-roster renders totaling 956 ms, maximum 146 ms;
- three existing-agent renders totaling 202 ms; and
- a two-second root probe timeout followed by 0.784 s, 0.201 s, and 0.091 s
  responses as the event loop recovered.

The host and store were not identical between these sequential controls, so
their wall times are not a feed-on/feed-off benchmark. The falsifiable result
is narrower and sufficient: the no-extra-feed control still suffered the
larger event-loop stall, while the roster feed added measurable full-page
render fan-out.

## CPU profile and exact source path

A runtime Node inspector profile wrapped mint `tJF-2607121155`. The profile ran
for 27.34 seconds and adds its own overhead, so its absolute wall time is not an
acceptance metric. Inclusive sampled time identifies the call graph:

| Call site | Inclusive sampled time |
|---|---:|
| `seon.client/core-index-tx` | 7.919 s |
| first `seon.client/index-core!` | 7.006 s |
| first `seon.client/var->fn-row` | 6.538 s |
| first `seon.client/extract-form-at-line` | 5.826 s |
| `seon.client/prune-core-ghosts!` | 5.776 s |
| second `seon.client/index-core!` | 4.798 s |
| second `seon.client/var->fn-row` | 4.428 s |
| second `seon.client/extract-form-at-line` | 3.847 s |

The dominant self stacks were the two `clojure.string/split` calls reached by
`split-lines`, approximately 4.089 seconds and 2.431 seconds. Regex newline
splitting, `arglists-from-source`, repeated file reads, and GC were secondary.

The implementation explains the profile:

- `extract-form-at-line` splits the complete source text into a vector and
  rejoins the suffix for every requested var;
- `var->fn-row` reads that source through the per-var path;
- `index-core!` maps the operation over the complete var roster;
- `core-index-tx` builds the complete current graph before comparing it with
  Datahike; and
- `prune-core-ghosts!` independently rebuilds the complete graph to discover
  absence.

The replacement should produce one immutable program snapshot per build/runtime
generation, derive both additions and removals from that snapshot, and share
file text/form extraction across every var in a source file. Agent mint must not
invoke that path at all.

## Reactive UI call sites

The live behavior matches the source:

- `seon.web.datastar/open-roster-feed!` renders `roster-view` for every
  transaction delivered to the view;
- `roster-view` iterates every agent and calls `tile-preview`, which can render
  each canvas;
- the agent feed has changed-attribute targeting, but
  `agent-view-changes` still recomputes surface specifications and dependency
  queries for candidate surfaces; and
- `agent-view-dependencies`, `context-surface-specs`, `renderer-touch`, and
  `canvas-touch` perform repeated database/history reads.

This confirms the roadmap split: first remove unrelated mint transactions,
then make one subscription owner batch committed transactions, then cache
stable render units and use runtime-observed reads for exact invalidation.
Provenance should never be used as a UI dependency proxy.

## HTTP and SSE live proof

After the drive, the default pod remained responsive:

| Route | Status | Wall time |
|---|---:|---:|
| `/` | 200 | 0.005 s |
| `/agents` | 200 | 0.002 s |
| `/agent/root` | 200 | 0.003 s |
| `/agent/tJF-2607121155` | 200 | 0.003 s |
| `/agent/root/debug` | 200 | 0.282 s |
| `/data` | 200 | 0.096 s |

A server-side gunzip client opened `/agent/root/feed` with HTTP 200 and gzip,
received its first patch in 41 ms, and verified the application root, agent
layout, primary stage, rail, and debug link in the decoded morph. The initial
payload represented approximately 2,045 tokens. This proves the route and
Datastar wire shape, not visual layout; browser acceptance remains outstanding.

## Defects and implementation consequences

1. **Mint calls boot.** `/agents/new` re-enters `start-agent!`; cluster seed,
   graph reconciliation, replay, instrumentation, service/ticker setup, and
   mint are not separate lifecycle operations.
2. **Program discovery is duplicated.** The same expensive snapshot is built
   independently for drift and deletion.
3. **Converged seed steps commit.** Empty/no-change work still becomes real
   transactions because metadata is attached.
4. **Config reconciliation is not a delta.** It submits the complete desired
   population on every mint.
5. **Replay is not clean.** Stale `:seon.render.live-tile` fails every restart
   and mint and persists a new error.
6. **Ghost pruning observes an unstable roster.** Two adjacent starts pruned
   different core var sets without an intentional source transition in this
   lane.
7. **Global instrumentation repeats.** Every mint rescans/reinstruments the
   complete graph.
8. **Roster SSE rerenders the complete roster per transaction.** Transaction
   amplification directly becomes render amplification.
9. **Datahike temporal upsert mishandles `false`.** Presence must be tested
   independently from truthiness and covered in the pinned Datahike source.
10. **Normal boot runs a scratch smoke database.** It added 227 ms on this
    restart and belongs in tests/diagnostics with explicit connection cleanup,
    not the production startup path.

These findings support the existing dependency order. The first implementation
milestone should split `boot-cluster!`, `mint-agent!`, and `resume-agent!`; the
mint path then has no reason to seed, replay, prune, instrument globally, start
services, or reinstall the ticker. Program/schema/config exactness can be fixed
behind their own lifecycle entry points without preserving the old combined
path.

## Preserved agent IDs

Required five-mint series:

- `dfD-2607121151`
- `wOR-2607121151`
- `hJW-2607121151`
- `hVA-2607121151`
- `yOq-2607121151`

Additional controls:

- `uzz-2607121153` — roster-feed/event-loop control
- `Bsf-2607121154` — no-extra-feed/event-loop control
- `tJF-2607121155` — Node CPU-profile control

## Remaining clean-baseline work

- measure a deliberate cold writer + pod start with stage timestamps;
- run the concurrent mint fence once no other lane owns the default pod;
- measure an isolated core reload and explicit config apply;
- add runtime-only counters for SCI/setup/body, serialization, gzip,
  subscriptions, candidate/compared reads, dirty units, and suppressed output;
- rerun the grown-store feed matrix with those counters on a stable store copy;
  and
- perform visual/browser interaction acceptance when a browser backend is
  available.

Those are bounded measurement gaps. The cause of warm mint latency and the
current transaction/render amplification are no longer unknown.
