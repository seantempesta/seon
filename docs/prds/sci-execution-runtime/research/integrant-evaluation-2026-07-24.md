---
type: research
status: active
tags: [research, runtime]
---

# Integrant evaluation — dependency expression and lifecycle (2026-07-24)

Owner question: should Seon move (back) to integrant for managing
dependencies and controlling lifecycles? Owner goal (R50 corollary):
interfaces EXPRESS their dependencies and publish their own readiness;
timeouts are last resort. Evaluated against the timeout census
(`research/timeout-census-2026-07-24.md`: 81 constants, 21 class-C
"clock masking a missing signal", 17 class-B "poll where an event
exists") and the live wedge exhibit
(`tmp/orchestrator/lifecycle-redrive-gate.log:244-341`).

Bottom line, stated plainly: **integrant is a poor fit for the problem
the census actually found.** Integrant orders synchronous construction
and destruction of in-process singletons. Every one of the census's
top wedge classes is a missing *completion event* (vthread death, lease
expiry, receipt delivery, process exit, readiness publication) — a
domain integrant's source explicitly does not touch. Seon's operator
already owns the one thing integrant would add (a declared dependency
graph with topological start and reverse shutdown order), and
integrant's config-map-as-system-source directly conflicts with
config-through-DB. Seon also already removed integrant once (2026-07-13)
and the active plan says "Never resurrect: Integrant".

## 1. Dependency ledger — exact sources read

| Source | Revision | What was read |
|---|---|---|
| `reference-code/integrant/src/integrant/core.cljc` | submodule pinned `bcad6bc` (merge of PR #115) | the complete file, 703 lines |
| `src/seon/host.clj` | working tree at `fe89babf5`+ (branch `codex/runtime-reliability-refactor`) | complete (428 lines): `start!`, `stop!`, `serve-session!`, startup timeout |
| `src/seon/client.cljs` | same | `start-runtime!`/`start-runtime-impl!` region (2339-2544), quiescence family (2557-2715 per census) |
| `src/seon/agent/driver/host.clj` | same | `start!`/`dispatch!`/`stop!` (572-639), step dispatch (560-570) |
| `script/seon/dev/process.clj` | same | `start-order` (794-813), `shutdown-order` (815-844), `ready?` (1046-1063), `wait-ready!` (1089-1129), process-status/record region |
| `script/seon/dev/state.clj` | same | complete (107 lines): (pid, start-instant) identity, durable EDN records, kernel file lock with 50ms retry |
| `docs/prds/sci-execution-runtime/research/preprocessing-design-2026-07-23.md` | same | §1.2 apply, §1.3 start = verify+load+attach (R45) |
| `docs/prds/sci-execution-runtime/research/timeout-census-2026-07-24.md` | same | complete |
| `tmp/orchestrator/lifecycle-redrive-gate.log` | same | wedge exhibit lines 230-341 |
| `reference-code/superv.async/src/superv/async.cljc` | vendored submodule | supervisor protocol, TrackingSupervisor, simple-supervisor (first ~120 lines + protocol surface) |
| Prior-era evidence | `docs/seon/issues/archive/flow-pool-integrant-surgical-2026-06-09.md`; `docs/prds/archive/runtime-reliability/roadmap.md:2163,2790`; `docs/seon/architecture/archive/jvm-main-app.md`; `docs/seon/lineage/predecessors.md:27-78`; `docs/prds/sci-execution-runtime/unified-plan-2026-07-23.md` (U5: "Never resurrect: Integrant"); commit `6c1079c8d` "refactor(runtime): archive paused JVM application" | read |

## 2. What integrant is (from source, core.cljc cites)

One ~700-line file. Config is a plain map of qualified keywords to
values; `ref`/`refset` records (lines 77-103) mark dependency edges;
`dependency-graph` (182-195) builds a `weavejester.dependency` graph
from every ref found by tree-walking values; `key-comparator` (197-202)
gives a deterministic topological order. `init` (650-658) is
`build` (430-455): for each key in dependency order, resolve refs in
the value (`resolve-refs`, 351-354), run an optional `assert-key`
check (528-548), then call the `init-key` multimethod (472-494) and
put the result in the system map. `halt!` (660-666) applies
`halt-key!` (496-504) in reverse dependency order via `reverse-run!`
(391-397). `suspend!`/`resume` (677-702) reuse resources across
restarts; `init` with a key subset (654) gives partial init;
`derive`d/composite keys (29-75) give polymorphic key families;
`expand`/`profile`/`var`/`bind` (120-154, 557-648) are config
pre-processing.

Error handling: `build-key` wraps each `init-key` throw in a
`build-exception` (409-421) carrying `:system` — the partially built
system — so the *caller* can halt what was built. Core.cljc itself
performs **no automatic rollback** (that lives in integrant-repl, not
vendored). `assert-key` failures likewise throw with the partial
system (537-548).

What it does NOT provide, verifiably absent from the source:

- **No readiness concept.** A key is "ready" the instant `init-key`
  returns. `init-key` is a synchronous function; there is no async
  init, no readiness publication, no health probe, no event, no
  channel, no future anywhere in the file. The owner's goal —
  "interfaces publish their own readiness" — is not modeled at all.
- **No supervision or restart policy.** Nothing watches a component
  after init. A thread a component started can die and integrant will
  never know; that is exactly the wedge-exhibit failure shape.
- **No timeouts, no clocks** — also no replacement for them.
- **No cross-process anything.** Single process, single system map.

So integrant answers exactly one question: *in what order do I
construct and destruct in-process singletons, given dependency edges
declared as data in a config map.*

## 3. Seon's three lifecycle layers today

### (a) In-process component wiring

- **JVM host boot** — `seon.host/start!` (host.clj:255-392): a linear
  `let` that builds writer session → error hooks → base projection →
  identity verification → base contexts → artifact inventories →
  acquired projection → graduation → instrumentation → pools →
  acceptor thread → optional claim driver. Manual partial rollback in
  the outer catch (390-392: close server, delete socket) and in the
  projection-error branch (316-325: close writer session, clear
  hooks). `stop!` (394-407) is a hand-ordered reverse.
- **Pod boot** — `seon.client/start-runtime!` (client.cljs:2506-2544)
  with a process-local phase fence
  (`starting`/`running`/`cleanup-required`) and a serialized `await`
  chain in `start-runtime-impl!` (schemas → session → admission/
  publication → resume → schedulers → web serve → ticker). R45-S4 is
  currently stripping derivation out of this path: per
  `preprocessing-design-2026-07-23.md` §1.2/§1.3, seed/config/agent
  birth move into the operator's explicit `apply`, and `start` becomes
  verify identity → load caches → attach → advertise ready, never
  deriving, failing LOUD on mismatch.
- **Claim driver** — `driver.host/start!` (driver/host.clj:572-639):
  handles atom, one vthread per claimed run (`dispatch!`, 587-614),
  interest listener → `scan!`, `stop!` = unlisten + interrupt handles.

### (b) Cross-process supervision/readiness (the operator)

`script/seon/dev/process.clj` already implements integrant's core
value proposition at the process level, plus what integrant lacks:

- a **declared dependency graph** per process spec
  (`:seon.dev.process/dependencies`), **topological start order** with
  cycle detection (`start-order`, 794-813), and **reverse shutdown
  order** (`shutdown-order`, 815-844) — this IS "dependency graph as
  data" and "deterministic halt order";
- per-process **readiness probes** (`ready?`, 1046-1063: watcher
  outputs, writer socket, host UDS accept, pod HTTP) — but consumed by
  a 200ms polling loop with stall/total deadlines (`wait-ready!`,
  1089-1129; census class B/C rows);
- immutable **process records** with (pid, start-instant) identity and
  durable fsynced EDN state plus kernel file locks (state.clj).

### (c) Cross-process, crash-tolerant coordination (the database)

Runs are claimable database state: claimant + `:seon.agent.run/claim-epoch`
CAS, heartbeat lease (20-min `stale-ms`, census C), turn phase cursor,
durable attempt/eval receipts; any process may die and a survivor
resumes from receipts (CLAUDE.md §Current runtime; agent-runtime.md).
The exhibit shows this layer working as designed *as a backstop* — and
shows the missing local completion event in layer (a) that made the
backstop the primary detector.

## 4. Per-wedge-class fit table

For each census top wedge class: would integrant have **prevented** it,
**helped express** it, or been **irrelevant**?

| Wedge class (census) | Layer | Actual missing signal | Integrant verdict |
|---|---|---|---|
| Claimant vthread death (C rank 1; exhibit NPE detected only at 900s) | (a), but per-*run* task, not component | `dispatch!`'s vthread `finally` (driver/host.clj:601-606) removes the handle without joining completion or settling the phase; the NPE escaped before any turn error/release transacted | **Irrelevant.** Integrant manages singleton init/halt; it has no post-init observation of anything, let alone per-run virtual threads. The fix is a completion join + atomic phase settlement, which no integrant key expresses. |
| Claim lease 1,200,000ms as primary detector (C) | (c) | Local vthread completion / claimant `ProcessHandle.onExit` should trigger immediate scan/release; lease stays a distributed survivor backstop | **Irrelevant.** Explicitly cross-process; integrant's source has no cross-process concept. |
| Run/API settlement — 900s serve deadline + 10ms quiescence rereads (C) | (a)/(c) | Database interest over run/turn terminal datoms (`reactive/observe!`) instead of polling and outer clocks | **Irrelevant.** Request-scoped settlement, not component lifecycle. |
| DB receipt delivery — 250ms deadline scans, 10ms conflict loops, ambiguous-write retry (B/C) | (a) transport internals | Promise/receipt/disconnect delivery on every terminal transport path | **Irrelevant.** Integrant has no async or completion story at all. |
| Operator readiness polling — 200ms `wait-ready!`, 25ms/10ms process polls (B) | (b) | `ProcessHandle.onExit`, `WatchService`, selector readiness, readiness *publication* by the child | **Irrelevant** — and partially **redundant**: the ordering half of this layer (topo start, reverse halt, cycle detection) the operator already has; the missing half (event-driven readiness) integrant does not provide either. |

Result: zero of the five wedge classes would have been prevented; none
is even *expressed* better by an integrant system map. The census's 21
class-C rows are uniformly missing completion/readiness **events**;
integrant's vocabulary contains no events.

The one place integrant would "help express" something: host.clj
`start!` and (pre-S4) `start-runtime-impl!` are long linear lets whose
dependency order is implicit and whose partial-failure rollback is
manual. An integrant config would make that order declarative and give
free reverse halt. But no observed failure in the census or exhibit is
a construction-*order* bug, and R45-S4 is already shrinking `start` to
verify+load+attach — the lets get shorter, not longer.

## 5. Migration cost and conflicts

- **Config authority conflict (the decisive one).** Integrant's config
  map is the system's parameter source; Seon's config IS database
  facts (`config/system.edn` → reconcile → `:seon.config` singleton;
  runtime reads the db, never files/env — src/seon CLAUDE.md
  one-mechanism table). Adopting integrant means either (i) a second
  config authority (the ig map) — a direct one-mechanism violation, or
  (ii) an ig config of empty placeholder values whose `init-key`s read
  the database — at which point integrant contributes only ordering,
  which a `let` (or the operator's existing graph shape) already
  expresses. This is a **conflict**, not a bridge. The prior era's
  characteristic failure was exactly config/method drift:
  `flow-pool-integrant-surgical-2026-06-09.md` — four keys still in
  `system.edn` whose `init-key` methods had been `#_`-disabled, the
  `:default` method threw, Phase 2 silently ran degraded for weeks.
- **Hot reload / clj-reload.** Multimethod-keyed lifecycles interact
  poorly with namespace reload (stale `defmethod`s, re-registration);
  Seon's dev loop is hot reload with the database owning durable state
  and process restart as the cheap recovery (R45: start ≤10s,
  restart/resume free at any moment). Integrant's `suspend`/`resume`
  solves a problem (preserving in-process resources across code
  reload) that Seon deliberately solved differently: clusters RESET,
  processes restart, receipts resume.
- **R45 apply/start split.** `apply` is an operator verb writing
  database facts; `start` never derives. Integrant has no two-phase
  apply/start notion; grafting it on means `init-key`s that must not
  do the very thing integrant inits are usually written to do.
- **Namespace inventory.** A minimal adoption would touch
  `seon.host`, `seon.host.context`, `seon.db.host`, `seon.client`,
  `seon.agent.driver.host`, `seon.web.serve`, plus a resurrected
  system-config surface — the exact namespaces four active R50 lanes
  (W-R50-1..5) are queued to change for event-completion reasons.
  Two mechanisms would be landing in the same owners simultaneously.
- **Precedent.** Integrant came from `ml-options-trading` (2025-11,
  lineage/predecessors.md:27) and was deleted with the paused JVM
  application on 2026-07-13 (`6c1079c8d`,
  archive/runtime-reliability/roadmap.md:2163). The active plan
  (unified-plan-2026-07-23.md, U5) says "Never resurrect: Integrant".
  The removal reason discoverable in-tree is the application rewrite
  itself plus the degraded-boot drift above; a more specific ruling
  text was not found — UNVERIFIED beyond these citations.

## 6. Alternatives comparison

| Capability wanted | Integrant | superv.async | JDK structured tools + db | Operator today |
|---|---|---|---|---|
| Dependency graph as data, topo start, reverse halt | yes (in-process only) | no | no | **yes (cross-process, already built)** |
| Readiness publication / completion events | no | partial (exception tracking over core.async channels) | **yes** (vthread joins, `CompletableFuture` composition, `ProcessHandle.onExit`, `WatchService`, selector readiness) | probes exist; polling consumers (the fix target) |
| Supervision / restart of failed work | no | yes (restarting-supervisor), but core.async-shaped | policy stays in operator + db claims | operator restarts processes; db lease covers survivors |
| Crash-tolerant cross-process settlement | no | no | — | **db claims/epochs/receipts (built)** |

On **superv.async**: it is in the tree as a maintained coordinate
because Datahike depends on it, and its supervisor genuinely tracks
exceptions escaping go-blocks (`PSupervisor`, `TrackingSupervisor` —
async.cljc). But it is core.async-shaped end to end, and Seon
deliberately removed core.async from the application (CLJ path = plain
synchronous on virtual threads; CLJS leaf = native `^:async`/await).
Adopting it for app supervision would reintroduce the concurrency
substrate the port just retired. Not recommended as the supervision
answer; its *idea* — every spawned unit of work is registered and its
escaping exception is delivered somewhere that must handle it — is the
right invariant, and the JDK-native equivalent for Seon is: every
dispatched vthread's completion (normal, thrown, interrupted) is joined
by an owner that settles durable state before the handle disappears.
That is precisely census lane 1.

## 7. Options and recommendation

**Option A — adopt integrant for in-process wiring** (host + pod boot
as system maps; operator and db layers untouched). Rejected: prevents
none of the observed wedge classes, models no readiness, conflicts
with config-through-DB, collides with R45's apply/start split and with
the five active R50 lanes in the same files, and re-adds a mechanism
the program deleted with a standing "never resurrect".

**Option B — no framework: readiness-publishing interfaces + JDK
completion primitives + the existing operator graph + db coordination
(RECOMMENDED).** This is the R50 corollary executed literally, and it
is already the census's ranked fix queue (W-R50-1..5):

1. every `start!` returns a handle whose readiness is an *event the
   child publishes* (socket bind / readiness file / protocol READY
   frame) and whose dependencies are its explicit request arguments —
   host.clj's `::start-request` schema (host.clj:31-48) is already
   this shape; strengthen, don't replace;
2. every dispatched vthread's completion is joined and settles durable
   phase state atomically before handle removal (driver/host.clj
   `dispatch!` finally);
3. run/turn/receipt settlement is delivered by database interest, not
   polled;
4. the operator consumes `ProcessHandle.onExit`, `WatchService`, and
   readiness publications instead of 200ms loops; its existing
   dependency graph remains THE dependency-as-data mechanism;
5. every surviving clock is a named config fact whose firing records a
   fault datom (anchor rule).

**Option C — bounded integrant for the JVM host only** (formalize
`seon.host/start!`'s ~15-binding construction as a small system map;
nothing else). Technically viable — this is the one spot integrant's
shape fits — but it buys declarative ordering of ~200 lines that S4 is
shrinking anyway, at the price of a new dependency, multimethod
lifecycle in a hot-reloaded tree, and a second config idiom. Not worth
it; if declarative in-process ordering is ever wanted, mirror the
operator's own `{id, dependencies, start!, stop!}` spec shape (one
mechanism, zero new deps) rather than importing a framework.

### Falsifiable acceptance criteria for Option B

1. **Claimant settlement (W-R50-1/2):** re-inject the exhibit's
   planner NPE; the run reaches turn `:error` + claim release in the
   completion-join transaction, with no dependence on the 900s deadline
   or the 20-min lease; a fault datom names the thrown cause. Falsified
   if any clock is the detector.
2. **Operator readiness (W-R50-5):** `bin/seon up` on a warm cluster
   contains zero 200ms/25ms/10ms readiness sleeps on the success path
   (verified by source grep + strace-level absence of poll loops);
   readiness latency = event latency; the R42 stall ceiling remains and
   its firing writes structured fault evidence.
3. **Settlement via interest (W-R50-3/4):** the 10ms quiescence reread
   and 250ms transport deadline scan are gone; a driven run's API
   response is produced by a database-interest wake; the outer request
   deadline firing records the exact unsettled run/turn datoms.
4. **No second mechanism:** `rg integrant src/ script/` stays empty;
   config remains solely database facts; the operator graph remains the
   only dependency-ordering owner.

## 8. Honesty ledger

- The exact prose rationale for the 2026-07-13 integrant removal
  beyond "archive paused JVM application" was not found; UNVERIFIED
  whether a dedicated ruling text exists outside the roadmap lines
  cited.
- `start-runtime-impl!` was read around its tail and fence, not all
  ~200 lines; the serialized-await characterization matches the read
  region and the census's client.cljs rows. Low risk, flagged anyway.
- integrant-repl (which does add rollback-on-failure and
  reload-integration) was NOT vendored and NOT evaluated; conclusions
  cover integrant.core only, as scoped.
- superv.async was skimmed (protocol + simple-supervisor), not read
  exhaustively; the "core.async-shaped end to end" claim rests on its
  namespace requires and protocol surface.
