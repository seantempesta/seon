---
type: prd
status: active
tags: [prd, agent, runtime]
---

# F1 — the agent-graph blueprint: contract package (2026-07-28)

The AGENTS-ARE-FLOWS rebuild's first rung (plan README §F, owner ruling
2026-07-28 afternoon): every agent is its own independent sequential
process hosted as a running flow — parked between episodes, kicked off
by the messages it receives, pausable/resumable, parallel across agents
by construction. No dispatcher, no active set, no scheduler entity.

This package fixes the ONE blueprint, its lifecycle, the episode dial,
the custody composition, the error/health surface, and the sealed
suite. `plan/README.md` remains the only ordering. Evidence authority:

- [../research/flow-mechanics-2026-07-28.md](../research/flow-mechanics-2026-07-28.md)
  — the measured substrate (idle graph ≈ one parked virtual thread +
  ~8.5 KB; pause lands BETWEEN transforms; 0.084 ms lifecycle; var
  step-fns or hot reload silently stops; per-graph ping/error).
- [../research/trigger-conservation-2026-07-28.md](../research/trigger-conservation-2026-07-28.md)
  — conservation proofs, the per-agent-mailbox delta (§5), THE EPISODE
  QUERY (§6, delivered, ~34 µs, zero new facts).
- [custody-revision-contracts-2026-07-28.md](custody-revision-contracts-2026-07-28.md)
  — F0(c)+(d): custody precedes work, custody IS presence, epochs and
  leases deleted, receipt identity `(run, ordinal)`. F1 is written
  against THAT model; nothing here reintroduces an epoch or a lease.
- [../research/flow-inventory-2026-07-28.md](../research/flow-inventory-2026-07-28.md)
  — the transport law and the buffer table are ADOPTED; its
  dispatcher-per-cluster recommendation (§3.1) is REJECTED by the
  afternoon ruling and does not enter this contract.
- [../research/workload-classification-2026-07-28.md](../research/workload-classification-2026-07-28.md)
  — the two-seam execution model: the proc hosts a chain, a classified
  segment hops executors explicitly.
- [context-blocks-contracts-2026-07-28.md](context-blocks-contracts-2026-07-28.md)
  — the sealed pre-provider capture (`seon.context/capture-tx`, §3.6)
  that composes into the turn proc unchanged.

Standing law carried forward UNCHANGED: the one-open-run agent-pointer
transaction fence; interrupted + adapt recovery (nothing re-executes,
nothing refires, no auto-retry of a paid call); errors are two classes,
never mixed; L7 (`listen!` fires on transact only), L8 (no wake
attribute the wake path's own work commits), L16 (the provider
dominates; interpreter speed justifies nothing).

## 1. The ONE blueprint

One blueprint — a pure function of `(agent-id, cluster-handle)`
returning a `create-flow` definition — stamps EVERY agent's graph.
There is no per-agent variation: two agents differ only in the
`agent-id` their procs carry and the mailbox channel routed to them.
`create-flow` allocates no threads (flow-mechanics §1), so a stamped
definition costs nothing until started.

Proposed owner namespace: `seon.cluster.agent` (blueprint, arm/disarm,
the routing entry) — review point R1. Every proc is built through
`seon.flow/var-process`, which already REFUSES a non-var step and a
missing/`:mixed` workload (`src/seon/flow.clj:84-95`, F0(a)) — the two
enforced conditions of the flow-mechanics verdict are construction-time
refusals, never review items.

### 1.1 Procs, conns, buffers, workloads

| proc | workload | step var | ins | outs |
|---|---|---|---|---|
| `::mailbox` | `:io` | `#'seon.cluster.agent/mailbox-step` | in-port: the agent's wake channel `(sliding-buffer 1)` | `::episode` |
| `::turn` | `:io` | `#'seon.cluster.agent/turn-step` | `::episode` | none — reports ride `::flow/report` |

| conn | buffer | loss semantics (inventory §1 buffer table) |
|---|---|---|
| `[::mailbox ::episode] → [::turn ::episode]` | `(sliding-buffer 1)` | newest-only payload-free signal — a wake says only "look"; the turn pass derives ALL of this agent's work from one fresh database value, so coalescing is free by the same argument that made the central pass's wake safe (conservation §1.2) |

Nothing else. Evals are NOT a proc in the agent graph: they hop to the
one bounded `:compute` door — `seon.flow/submit!!` (fixed-buffer
backpressure + the bounded platform executor) under the one
`:interrupt-fn` with `time-limit` — exactly as today
(`loop.cljc:774-806`, workload-classification §3). The agent graph is
the `:io` chain; the eval is the explicit `:compute` hop. `:mixed`
appears nowhere: both procs are pinned, and the F0(a) computed check
(no proc without a pinned workload) is the standing guard.

**Why two procs and not one.** Mechanically one proc would coalesce
wakes the same way (the in-port buffers during a transform). The
mailbox earns its place three ways, all substrate-honest:

1. **Responsiveness.** Control commands land only between transforms
   (flow-mechanics §2). The turn transform contains a multi-second
   provider call; the mailbox transform is one channel put. A graph
   with a mailbox answers ping/pause within microseconds at all times;
   a single-proc graph goes deaf for the length of a model call.
2. **The pause boundary IS the episode boundary.** Pausing the graph
   pauses the mailbox instantly, so no new episode can begin, while
   the in-flight turn (if any) runs to its durable terminal — which is
   exactly the honest semantics §3 promises.
3. **One wake entry per agent.** Listener routing, the arm prime, and
   the self-rewake all target the mailbox channel; the conservation
   invariant (§4) has one edge to fence instead of three.

`mailbox-step` is total and instant: forward one payload-free
`::episode` signal downstream; count deliveries in its ping map. It
never reads the database (the turn pass owns the basis) and never
blocks (its downstream conn is sliding-1, `offer!`-semantics via the
non-blocking put flow performs on a sliding buffer).

### 1.2 The turn proc — one episode pass

`turn-step`'s transform runs ONE pass, the same shape the central pass
proved live (`loop.cljc:455-487`) narrowed to one agent:

1. **Settle this agent's orphan** (`work/interruption` scoped to the
   agent) before deriving — the wedge fence, now per-agent
   (conservation §5: "per-agent graphs settle their own orphan").
2. **Pin one database value; derive `next-agent-work`** (§5.2) — this
   agent only, never a global fold.
3. **Execute the situation** under the pass-local custody law (§5.1):
   - `:open` — episode gate (§4) → open + claim (custody CAS-on-absence)
     in ONE transaction with the trigger as tx-meta, unchanged;
   - `:call` — verify custody in-pass → derive prompt → **commit the
     pre-provider capture** (`seon.context/capture-tx`, the sealed
     context-blocks contract §3.6 — a COMPOSITION EDGE: the capture is
     a turn-proc step and F1 does not redesign one byte of it) →
     provider call on this proc's own `:io` virtual thread → freeze
     plan (absent→digest CAS) → fall into the fold;
   - `:resume` — CLAIM BEFORE FOLDING (custody revision §Revisions 1),
     then reduce forms: running receipt → guarded eval through
     `submit!!` `:compute` under `:interrupt-fn` → terminal receipt +
     disposition + deliveries in ONE transaction (`terminal-tx`
     unchanged, minus its epoch field);
   - `:close` — takeover-then-close, the one recovery shape (custody
     revision §Revisions 3).
4. **Self-rewake**: when `more-agent-work?` (the per-agent counterpart
   of `more-work?`), `offer!` one wake into this agent's OWN mailbox
   channel. Coalescing on sliding-1 keeps this non-recursive exactly as
   today (`loop.cljc:28-32`).

Failures inside the pass stay VALUES: refused transitions commit one
error fact through the existing `refused!`/`error-tx` owners; nothing
throws into the loop; a Throwable that escapes anyway is a core fault
and rides the graph's error channel (§6). The proc's `:ping-map-fn`
exposes `{turns, current-run-id}` — the current run rides in
`::flow/state`, which retires the serial-dependent `attributed-run`
derivation (`cluster.clj:513-530` names its own expiry; the query dies
in F2's wave).

The turn proc's report map (`::flow/report`) stays observation-only —
flow's own sliding-100 channel, droppable, never a dependency.

## 2. Graph lifecycle

**Created with the agent; parked between episodes; re-stamped from
facts at boot.** The graph is derived state — its definition is a pure
function of facts (agent-id + the cluster handle's dials), so it is
never stored and always re-derivable.

- **Arm** (per agent): stamp the definition → `start` → `resume` →
  register the mailbox channel in the cluster's routing entry (§4) →
  **prime one wake** into the mailbox. The prime is the conservation
  idiom verbatim: the first pass derives that agent's work from facts,
  so anything committed before the graph existed is seen. Measured
  cost: ~22 µs to start, ~8.5 KB idle, one parked virtual thread per
  proc (flow-mechanics §1) — 1000 parked agents ≈ 8 MB and zero
  platform threads.
- **Parked between episodes** means the proc loops are parked in
  channel reads on their virtual threads — running, costing nothing.
  Flow-`:paused` is a different state reserved for the pause command
  (§3); procs are born `:paused` and armed to running once.
- **Boot re-stamp**: the cluster's arming step derives the agent set
  from facts and arms one graph per agent, after `recover-runs!` has
  stamped dead custody's receipts `interrupted-at` and retracted
  custody (the takeover/recovery one-shape). Boot order inside the
  tower is unchanged: recovery → config → root agent → arm (now: arm
  ALL agent graphs + the armer) → serve.
- **Disarm/stop** (orderly): `flow/stop` per graph, join each turn
  proc's completion (the existing promise-channel stop-transition
  idiom, `loop.cljc:441-453`) before releasing the branch connection.
  Stop drops conn contents — safe by the transport law.
- **Agent deletion** (when it exists): stop the graph, drop the
  routing entry. Buffered wakes die with it; triggers are rows and
  survive.

**Crash walk (kill -9 at the worst instant).** Everything on any
channel is losable by the transport law:

| lost on channels | re-derives |
|---|---|
| buffered wakes (mailbox, episode conns, armer) | boot re-stamps every agent graph and primes each mailbox once; the first pass derives from facts |
| in-flight turn's process state (current run, sci ctx) | recovery stamps that custody's running receipts `interrupted-at` and retracts custody; the agent's next prompt carries the one derived interrupted warning; NOTHING re-executes a form or refires a paid call (`ai/disposition` remains the choke point) |
| queued eval submissions in the `:compute` door | pointers to durable run facts; the re-armed pass re-derives admissible work under the settle-once presence fences |
| uncommitted faults in the fan-out | lost with the process — the honest ceiling for observing a dying process; committed fault facts survive |

No row depends on a channel for recovery; nothing re-executes.

## 3. Per-agent pause/resume — graph commands, honest semantics

Pause and resume are flow's own addressed commands on the agent's
graph: `flow/pause` / `flow/resume` (one buffered control put,
fire-and-forget, flow-mechanics §2).

**The honest semantics, stated so nobody oversells them:**

- Pause lands BETWEEN transforms. The mailbox pauses effectively
  instantly (its transform is one put), so no new episode begins. An
  in-flight turn — including a seconds-long provider call — runs to
  its durable terminal, its output lands in a buffer, and only then
  does the turn proc park paused. A blocked `:io` call is never
  interrupted by pause.
- The mid-eval stop is and remains the `:interrupt-fn` with
  `time-limit` at the `:compute` door — the ONE containment mechanism
  (L1). Pause does not reach inside an eval; the interrupt does.
- Wakes and triggers accumulate while paused: triggers as durable rows
  (always), the newest wake in the sliding-1 mailbox. Resume observes
  the buffered wake and the next pass answers everything from facts.
- Pause is a live ops control, NOT durable state: a re-stamped graph
  (boot, topology rebuild) comes up running. A durable per-agent
  pause — a fact `next-agent-work` respects — is a separate future
  accretion, deliberately out of F1 (review point R4).

## 4. Wake routing, the armer, and the mailbox conservation invariant

The cluster keeps ONE `listen!` registration (`wake/listen!`,
registered with the fan-out's fault channel exactly as today,
`cluster.clj:680-689`). What changes is the handler's delivery: instead
of one global channel, it ROUTES.

- **The routing entry** is a process-local map `agent-id → mailbox
  channel`, held on the cluster instance beside the connection — the
  sanctioned kind of process state (a disposable artifact, rebuilt by
  arming at boot). It is not a fact: it names live channels.
- **Routing rule**: for each committed datom in the wake set
  (`:seon.cluster.message/to`), resolve the recipient ref → agent id →
  `offer!` one payload-free wake into that agent's mailbox. The datom
  carries the ref, so this is a map lookup — no query, no park, still
  the four-line handler the inventory's "never a proc" row demands
  (`wake.cljc:12-32` prohibitions unchanged).
- **The armer**: the wake set GROWS by `:seon.cluster.agent/id` — a
  committed agent-creation datom is an arm wake. The cluster graph
  (the cluster keeps one small graph of its own: the armer proc now,
  the render proc at F2, a schedule proc later, per the inventory
  census) hosts `::armer` (`:io`, var step, in-port `(sliding-buffer
  1)`): its pass derives `(agents in facts) − (armed set)` and arms
  each — derive-all under a payload-free wake, so coalescing is safe
  by the standard argument. The listener also offers to the armer
  whenever it sees a `to`-ref with no routing entry (the belt for the
  created-and-messaged-in-one-commit window).
- **The invariant** (conservation §5, verbatim): *every mailbox arm
  primes once, and no commit path can observe an agent that has
  triggers but will never be armed.* The armer's own arm ends with the
  prime; the prime's pass derives from facts; a wake lost to any gap
  costs nothing the arm prime does not recover.
- **Arming-gap orphans**: the armer settles orphans of agents that
  have no graph yet as part of arming them (the arm prime's first pass
  does it — step 1 of the turn pass), so the wedge cannot return
  through the gap.

L8 holds by construction: the armer's own work commits no wake-set
attribute (arming writes nothing; the prime is an `offer!`).

## 5. Custody composition

### 5.1 The pass-local law, inside per-agent procs

**CUSTODY PRECEDES WORK** (custody revision §The law): a pass may act
on a run only under custody it verified or acquired IN THAT SAME PASS.
Custody IS presence: `:seon.agent.run/process` present = held, absent
= unheld; claiming is CAS-on-absence; takeover = recovery, one
`:db.fn/call` shape that stamps the dead custody's running receipts
`interrupted-at` before asserting the new holder.

Per-agent graphs change the TOPOLOGY of who runs passes, not the law.
Every situation branch in `turn-step` composes it:

- `:open` claims in the opening transaction (unchanged);
- `:call` verifies presence + process match at the pass basis before
  the paid call (revision 2 — with leases deleted, P2's scenario is a
  custody mismatch and derives no work);
- `:resume` claims before folding; a lost CAS is a quiet skip, never
  an error fact (revision 1 — the P1 livelock unrepresentable);
- `:close` takes over via the one recovery shape when unheld.

**F1 pins exactly the four kept fences and nothing else** (custody
revision §Kept fences):

1. `::process` custody presence;
2. `::not-the-holder` as the ONE loud custody refusal;
3. settle-once presence fences (terminal facts CAS from absence;
   recover CAS-on-absence);
4. the one-open-run agent-pointer fence.

These are database-level, enforced inside the one serial writer, and
no per-agent topology can un-enforce them (conservation §5's
"independent of the serial pass" list). Two agents' graphs can never
race each other into a duplicate answer: the agent-pointer fence
serializes opens per agent, and each agent has exactly one turn proc.

### 5.2 `next-agent-work` — the per-agent derivation

`seon.cluster.work` gains `next-agent-work`: the same derivation as
`next-work` scoped to ONE agent — `(next-agent-work db {agent-id,
process, now})` → the same work map or nil — plus `more-agent-work?`.
The global sorted `some` over all agents dies with the central pass
(F2); it is wrong the moment two agents run, and the fold's own
next-ordinal lookup (`loop.cljc:900-911` asks GLOBAL `next-work` for
the next ordinal — the conservation doc's verified defect) moves to
`next-agent-work` in the same change. The episode gate (§below) lives
inside `next-agent-work`'s `:open` arm, so a deferred trigger simply
derives no work — the turn proc never sees a decision to refuse.

## 6. Per-graph errors and health

- **Error channel**: each agent graph's `error-chan` feeds the
  cluster's ONE counted-dropping fault channel, tagged with the agent:
  `(async/pipeline 1 fault-chan (map #(assoc % :seon.cluster.agent/id
  id)) error-chan false)` — `close? false` so one agent's stop never
  closes the committer's inbox (flow-mechanics §4, demonstrated
  topology). `seon.flow/start-error-fanout!` generalizes to N source
  graphs feeding one fault channel + fault-committer proc; the
  committer, `commit-fault!`, the recurrence fence, and the
  record/panic dial are unchanged. Fault provenance is now structural:
  `{agent, pid, cid, msg, ex}` — "who should fix this" needs no
  `attributed-run` query.
- **Agent mistakes never touch these channels** — flat `:seon.error`
  values on receipts, exactly as today. The two classes stay unmixed.
- **Ping is the health surface**: `flow/ping` per agent graph returns
  per-proc status, transform counts, live buffer occupancy, and the
  ping maps (mailbox deliveries; turn count + current run). The
  monitor/problems derivations consume pings; nothing stores health.

## 7. THE EPISODE DIAL

**The derivation is delivered and cited, not redesigned**: conservation
§6's `episode-runs` — runs since (and including) the one answering the
last OUTSIDE trigger, derived purely from committed facts (the run's
opening tx names its trigger as tx-meta; a trigger with no
`:seon.cluster.message/from` is outside the agent population). Zero
new facts, no stored counter, ~34 µs on a 64-run history, REPL-proven
(probe P3). "Outside" today includes the error recorder; the human-only
refinement is one more `not`-clause if ever ruled (review point R3).

- **One config fact**: `:seon.config.run/max-episode-runs` — the max
  consecutive runs per idle→running episode. The ONE new attribute in
  this package (§9). It ships in the defaults document (16 proposed —
  owner decision #1, set at F4 from drive evidence); an ABSENT dial is
  FAIL-CLOSED for agent-sent triggers (the `max-chain` precedent,
  `message.cljc:239-248`): no agent-triggered run opens, outside
  triggers open normally.
- **The gate**: inside `next-agent-work`'s `:open` arm — derive `:open`
  for an agent-sent pending trigger only when
  `(< (episode-runs db agent-id) max-episode-runs)`. An outside
  trigger opens regardless: its own run becomes the new episode start,
  which IS the outside-trigger reset — no reset code exists.
- **Refusal shape — presence, no stored anything.** When the cap is
  hit, the run DOES NOT OPEN and NOTHING NEW is written: the trigger
  simply stays an unanswered row (conservation property (c): triggers
  are conserved facts). "Deferred by the episode cap" is itself a
  derivation — `(and (>= (episode-runs db agent) max) (seq
  (unanswered-agent-triggers db agent)))` — surfaced where derived
  states are surfaced: a `problems` family and a derived context line
  in the agent's next prompt ("N self-triggered runs since the last
  outside trigger; M triggers deferred until one arrives"). The state
  vanishes when the facts do: the next outside trigger's run resets
  the count, the following passes derive `:open` for the deferred
  triggers oldest-first, and the warning derives to nothing. No
  counter, no flag, no refusal message row — the audits proved the
  episode needs zero new facts, and this contract holds that line.
  **Seal correction (orchestrator, 2026-07-28 — deadlock found in
  review): trigger selection under the cap is NOT plain oldest-first.**
  With the cap hit, an older deferred self-trigger evaluated first
  derives nothing, and if selection stops there the newer OUTSIDE
  trigger never opens, the count never resets, and the agent deadlocks
  permanently. The derivation is: when
  `(>= (episode-runs db agent) max)`, `next-agent-work`'s `:open` arm
  selects only OUTSIDE triggers (oldest such first); below the cap it
  selects oldest-first over all. Equivalent statement: a deferred
  self-trigger is never a selection blocker — it is skipped, not
  waited on. The `episode-cap-refusal-test` oracle adds the ordering
  case: deferred self-trigger OLDER than the arriving outside trigger,
  and the outside trigger still opens on the next pass.
- **What the dial is NOT**: a safety net for a loop bug. Any race that
  could loop agents forever is a design defect to dissolve (P1/P2 are
  dissolved by the custody revision, not capped). The dial bounds
  legitimate self-continuation, nothing else.

## 8. The sealed suite

Continues the 20260728xx fixed-seed series (context-blocks ended at
2026072810). Per-trial in-memory databases through the canonical
fixture; a recorded stub provider (a ledger of calls — no paid call in
any test); every deftest names its oracle. F1 — not the custody wave —
owns the two audit probes as recurring regressions.

| deftest / property | seed | oracle |
|---|---|---|
| `n-agent-parallel-turns-property` | 2026072811 | generated N agents × outside triggers driven through per-agent graphs concurrently: every trigger answered exactly once (the tx-meta walk — a permanent datom, not a counter); provider-ledger count equals opened-run count (zero duplicate paid dispatches); receipts unique by `(run, ordinal)`; all four kept fences quiet (no refusal error facts); final facts equal the per-agent serial oracle's, order-independent |
| `park-wake-test` | 2026072812 | an armed idle agent graph: provider ledger stays 0 and no run opens over an observation window bounded by ping counts (event-driven, no sleep-as-proof); one committed trigger → exactly one run, one provider call; idle again with ping counts flat |
| `pause-during-in-flight-call-test` | 2026072813 | stub provider parks on a latch; `flow/pause` mid-call returns immediately (fire-and-forget); the call completes and its terminal facts COMMIT while paused; a second trigger committed while paused stays unanswered (row present, no run) until `flow/resume`, after which it is answered once — the fact timeline plus the provider ledger are the oracle |
| `episode-cap-refusal-test` | 2026072814 | a self-messaging chain under a small planted dial: run count per `episode-runs` (the cited query, asserted directly) stops exactly at the cap; the deferred trigger remains unanswered; the derived problems/prompt line is present under `get` and derives from facts alone (datom census: the refusal wrote NOTHING); a fresh outside trigger opens, resets the derivation to 1, and the deferred trigger is answered on the following pass |
| `hot-reload-var-test` | 2026072815 | `alter-var-root` on `turn-step` (composing F0(a)): a running agent graph's next pass observably runs v2 (report content), with no rebuild; a fn-value-built control proc still runs v1 |
| `restamp-recovery-test` | 2026072816 | mid-parallel-turns, graphs are dropped WITHOUT stop and channel contents discarded (the in-process kill -9 projection); boot-shape re-arm (recover → re-stamp → prime) yields: running receipts of dead custody carry `interrupted-at`, custody retracted, zero re-executed forms and zero provider re-dispatches (ledger), the one derived interrupted warning present, deferred triggers answered; the real process-death kill -9 proof stays owned by F4 |
| `unheld-resume-regression` (audit P1) | 2026072817 | the recovered unheld planned run with a disposition-bearing remaining form: resume CLAIMS, the disposition's terminal transaction commits, the run closes; no identical-work fixed point across derivations, no error-fact storm (error-fact count bounded at 0 for this path) |
| `custody-mismatch-regression` (audit P2, re-expressed post-leases) | 2026072818 | a run held by another process rewakes its agent: `next-agent-work` derives no `:call` for this process; zero duplicate provider dispatches across the interleaving (ledger) |
| `wake-routing-conservation-property` | 2026072819 | generated interleavings of agent-create / message-to-new-agent / arm: the invariant holds — every mailbox arm primes exactly once, no reachable state has an agent with unanswered triggers and no armed graph after quiescence, and a message committed before its recipient's graph existed is answered after the arm |
| `wait-closes-in-terminal-tx-test` | 2026072820 | the ruled `my.run/wait` revision folded into F1 (README owner-decisions #4): a `wait` disposition's terminal transaction settles the receipt AND closes the run in ONE commit — no unheld-open-planned intermediate state exists at any basis (the P1 feeder state unrepresentable); the agent's next trigger opens a NEW run |

Suite-wide rules: fixed seeds with shrunk counterexamples printed;
example tests only as call-shape documentation; `bin/test` green from
the custody-revision baseline; old tests pinning the central pass are
NOT touched here (they die with the pass in F2's commits, per
cut-first).

## 9. New attributes — count: 1

| attribute | owning code namespace | justification |
|---|---|---|
| `:seon.config.run/max-episode-runs` | consumed by `seon.cluster.work` (the gate) under the existing `:seon.config.<area>/*` config-singleton convention beside `:seon.config.message/max-chain` | the owner-ruled ONE per-agent dial; a config fact is the ruled shape for every dial; it is a NUMBER someone sets, not derivable |

Nothing else. The episode state, the refusal, the pause boundary, the
health surface, wake routing, and custody are all presence,
derivation, or process-local channel machinery — the audits proved the
episode needs zero stored facts and this package adds none. No stored
discriminator anywhere; state is presence (the 2026-07-28
presence-not-kinds ruling); attribute namespaces take the owning code
namespace (evening ruling — the one new attribute follows the config
convention already in force).

## 10. What F2 deletes — named, not implemented here

F1 proves the surviving mechanism; F2 is the cut wave in the same
surgery area, after F1 green (plan README §F2):

- the central pass: `loop.cljc`'s `step` and its serial global
  derivation (`next-work`'s sorted `some`), the in-pass sleep backoff,
  and the global-fold next-ordinal lookup (`loop.cljc:900-911`);
- `attributed-run` (`cluster.clj:513-530`) — attribution rides the
  error-channel tag + turn-proc state;
- the single-loop `arm-loop!`/`loop-graph-definition` shape — replaced
  by per-agent arming (F1 implements the replacement; F2 deletes the
  residue and re-grounds the tests that pinned it);
- `seon.ai.stream`'s database half and `render/web.clj`'s
  per-connection `listen!` + hand-rolled mailboxes (the transport
  conversions — same wave, different files).

Old tests pinning deleted paths die in the same F2 commits; survivors
assert the surviving mechanism.

## Orchestrator review points — judgment calls with alternatives

- **R1 — blueprint owner namespace.** Chosen: `seon.cluster.agent`
  (the agent's process shape lives with the agent). Alternatives:
  extend `seon.cluster.loop` (keeps the turn lineage but preserves a
  name F2 partially deletes), or `seon.cluster.graph` (mechanism-named,
  weaker colocation). Low risk either way; rename is cheap pre-N5.
- **R2 — two procs per agent vs one.** Chosen: mailbox + turn (§1.1's
  three justifications: control responsiveness during a model call,
  the pause boundary as the episode boundary, one wake entry).
  Alternative: one turn proc parking directly on the wake channel —
  half the threads/heap (still trivial at 1000 agents), but ping/pause
  go deaf for the length of a provider call and the pause boundary
  stops being "before the next episode". If the owner prefers maximal
  frugality, the suite is unchanged except the pause test's timing
  assertions.
- **R3 — SEAL CORRECTION (orchestrator, 2026-07-28): the error
  recorder does NOT reset the episode.** The draft kept recorder
  messages as episode-resetting "outside" input, but the error system
  delivers explanation messages to the failing agent — so an agent in
  an error loop would have its cap reset by its own failure
  notifications, making the pathological case the dial exists for
  immune to the dial. Adopt the conservation doc's refinement: the
  episode derivation excludes rows carrying
  `:seon.cluster.message/about` (recorder provenance) — "outside"
  means outside the population's AUTONOMOUS activity: a human message
  or a schedule fire. §7's derivation gains the one `not`-clause; the
  episode-cap suite adds one case (recorder message mid-episode does
  not reset the count).
- **R4 — pause is not durable.** Graph commands only, per the F1
  charter; a crash/re-stamp resumes a paused agent. The durable
  alternative (a presence fact `next-agent-work` respects, the
  inventory §3.1 shape) is a small later accretion if wanted — it
  composes with, rather than replaces, the graph command.
- **R5 — absent episode dial fails closed.** Chosen for consistency
  with `max-chain`'s fail-closed precedent; the alternative
  (absent = unbounded) matches "any loop is a design defect, the cap
  is not a safety net" but leaves a fresh misconfigured cluster with
  unbounded self-continuation. The shipped defaults document makes
  absence abnormal either way.
- **R6 — arm-all-at-boot vs lazy arming.** Chosen: arm every agent's
  graph at boot (uniform, measured-cheap, and the conservation
  invariant holds trivially). Alternative: arm on first trigger via
  the armer only — saves nothing measurable at realistic agent counts
  and adds a first-message latency edge to test.
- **R7 — the armer lives in a small cluster graph.** Chosen: the
  cluster keeps one graph of its own (armer now; render proc joins it
  at F2; schedule proc later), matching the inventory census's
  "one cluster graph per cluster". Alternative: the armer as a bare
  virtual-thread loop — fewer moving parts but loses ping/error/pause
  uniformity for a component that wants exactly those.

## Implementation note — landed 2026-07-28 (F1 lane)

The blueprint is live and the sealed suite is green. Commits, in
order: `d1ec4a019` (episode dial + `next-agent-work`/gate/deferred +
wait-closes-in-terminal-tx + the fold's per-agent next ordinal),
`af200d5d6` (`seon.cluster.agent` blueprint/arm/disarm/armer, public
`seon.flow/var-process`, `join-error-fanout!`, `wake/route!`, boot
rewire in `cluster.clj`, armed_test re-ground), `415a7f1f7`
(problems deferred family + prompt line), `11b2ee1a0` (the ten-test
suite, seeds 2026072811–2026072820), `d747cc7a9` (boot_test
orderly-stop + workload census re-grounds).

Deltas from the letter of the package, all recorded here so F2 reads
one truth:

- **R1 chosen as written**: `seon.cluster.agent` owns blueprint,
  arm/disarm, and the routing entry; the routing LISTENER's handler
  lives in `seon.cluster.wake/route!` (the namespace that owns the
  never-throw/never-park prohibitions), and `wake/listen!` survives
  unused until F2 cuts it with the central pass.
- **Boot arming is synchronous** (R6 sharpened): `arm-agents!` arms the
  fact-derived agent set before registering the listener, so a
  returned instance IS armed — readiness published, never awaited; the
  armer proc covers the created-and-messaged window via the boot
  prime, exactly the invariant's belt.
- **`episode-runs` counts every run for a never-outside agent**
  (review-caught): a purely agent-spawned agent has no outside tx, and
  returning 0 would have voided the cap for exactly the population it
  most concerns. The gate and the suite pin the corrected semantics.
- **The dial ships at 100** (owner ruling, superseding §7's proposed
  16) with unit+provenance in `config/default.edn`; it reads from the
  database value inside `next-agent-work`, so a live dial change
  applies at the next pass.
- **Fault attribution**: tagged faults attribute through the agent's
  one held run (`tagged-run`); `attributed-run` survives only for the
  cluster graph's untagged faults until F2 deletes it.
- The deferred-state prompt line rides `seon.problems/ai-prose` (the
  problems family's own ai projection); wiring a problems block into
  the default seed membership stays with the block-seed owner —
  `seon.context`/`render` were protected paths for this lane.

## Sequencing

F1 implementation dispatches AFTER the custody-revision wave lands
(same `loop.cljc`/`work.cljc`/`run.cljc` surgery area; the revision is
F1's substrate — no epoch/lease may appear in any F1 diff). The
context-blocks lane's capture composes in as the named turn-proc step;
its files are owned by that lane until it returns. F2 follows F1
green.
