---
type: prd
status: active
tags: [prd, agent, runtime]
---

# F2 — transport conversions + the central-loop deletion: contract package (2026-07-28)

**SEAL REVISION (2026-07-29).** The stream clear signal is DELETED.
Every partial carries its run id, and the render pass admits it only while
that run has no frozen-plan, error, or closed fact at the pass's immutable
database value. A database/reconnect interest is a fact-only repaint, so it
cannot restore process-local partial memory. The settled terminal fact is the
stream terminal: its ordinary render wake replaces the temporary projection;
no channel value means "done". This supersedes §2.1's original clear design
and the corresponding 2026072821 oracle below.

**IMPLEMENTATION NOTE (landed 2026-07-28).** Four path-limited commits in
the sequenced order: `5daf05e24` (stream conversion + the `seon.ai.stream`
funeral), `2e372027d` (render proc + `web.clj` conversion + web_test
re-ground), `a468a92b1` (the central-pass cut + its suite re-grounds),
`96a2ddfaf` (the sealed suite, seeds 2026072821-2026072829). Live proof:
`../research/f2-live-render-proof-2026-07-28.md`, harness committed at
`script/seon/dev/live_render_proof.clj`. Gate at close: `bin/test`
396 tests / 1564 assertions, 0 failures, 0 errors (from 387/1503 at the
F1 baseline).

Two deviations from the drafted package, both narrower than the text:
R3's block move landed in `seon.render.root` rather than a new render
namespace (the blocks are ordinary root blocks and the seed owner already
lives there), and seed 2026072827 is a RE-SEAL of `loop_test`'s existing
crash walk rather than a second copy of it — one regression per class.
One seam was closed in passing: a nil in-port leaves a Flow proc
`:running` but unreadable AND its stop transition never runs, so the
render proc now refuses construction without the channels it reads.

The AGENTS-ARE-FLOWS rebuild's cut wave (plan README §F2, after F1
green): streamed tokens become one `(sliding-buffer 1)` conn into a
render proc; render fan-out becomes one derivation → equality
suppression → `mult` → per-tab sliding-1 taps; the central loop pass
dies. Same surgery area as F1, one wave, cut-first: every deletion
below lands before any individual seam is polished, and a discovered
seam defect gets a one-line issue while the cutting continues.

This package fixes the render proc's shape, the streaming conversion,
the exact deletion list, the test funeral/re-ground inventory, and the
sealed suite. `plan/README.md` remains the only ordering. Evidence
authority:

- [f1-agent-graph-contracts-2026-07-28.md](f1-agent-graph-contracts-2026-07-28.md)
  — the landed blueprint (implementation note: commits `d1ec4a019` …
  `d747cc7a9`); its §10 names this wave's deletions and is superseded
  by the exact inventory here where the two differ in detail.
- [../research/flow-inventory-2026-07-28.md](../research/flow-inventory-2026-07-28.md)
  — the transport law and buffer table (ADOPTED; the dispatcher §3.1
  stays REJECTED); §3.2 render pipeline, §3.3 token streaming, §6 the
  deletion census this package makes exact.
- [../research/flow-mechanics-2026-07-28.md](../research/flow-mechanics-2026-07-28.md)
  — measured: sliding-1 absorbs ~4.6 M puts/s producer-never-parked
  (§5); an 8 MB value crosses a channel in 0.01 ms vs 74–88 ms durable
  transact (~7,000×); pause lands between transforms.
- The sources as built: `src/seon/cluster/agent.clj` (F1 landed),
  `src/seon/cluster/loop.cljc`, `src/seon/cluster/work.cljc`,
  `src/seon/cluster/wake.cljc`, `src/seon/cluster.clj`,
  `src/seon/ai.cljc` + `src/seon/ai/stream.clj`,
  `src/seon/render/web.clj`.

Standing law carried forward UNCHANGED: the transport law (facts are
durable, channels carry only what is free to lose — re-derivable or
superseded-by-newer); nothing re-executes, no refire of a paid call;
errors two classes never mixed; L7/L8; the UI is a pure function of
the database value — reconnect = repaint.

**Ripeness evidence (verified by rg, 2026-07-28):** `loop/step` has
ZERO callers in `src/` and `test/` since F1's boot rewire;
`wake/listen!` survives only in `test/seon/cluster/wake_test.clj`;
`seon.ai.stream/publisher` is invoked by NO production caller — the
turn never wired the sink, so the database half of streaming is dark
machinery deleted before it ever ran live. The blocks
(`tokens-html`/`text-html`) are seeded by nothing (`render/root.clj`
never references them).

## 1. The render proc — the cluster graph's second proc

One render proc per cluster, joining the cluster's own graph beside
`::armer` (F1 R7 as ruled: armer now, render at F2, schedule later).
Built through `seon.flow/var-process`, pinned `:io` (it reads the
database and serializes; per-block evals reach `:compute` through the
existing door). `cluster-graph-definition` in `cluster.clj` gains the
proc; nothing else about the cluster graph changes.

### 1.1 Shape

| proc | workload | step var | in-ports | outs |
|---|---|---|---|---|
| `::render` | `:io` | `#'seon.render.web/render-step` (owner: review point R2) | `::interest` — the render wake channel `(sliding-buffer 1)`; `::stream` — the stream conn `(sliding-buffer 1)` (§2) | `::pages` → one `mult` |

| channel | buffer | loss semantics (buffer-table row) |
|---|---|---|
| render wake (`::interest`) | `(sliding-buffer 1)` | newest-only payload-free signal — a wake says only "look"; the pass derives from one fresh database value |
| stream conn (`::stream`) | `(sliding-buffer 1)` | newest-only complete value — every snapshot is complete and the next supersedes it |
| `::pages` out → `mult` → per-tab taps | `(sliding-buffer 1)` per tap | newest-only complete value — a slow browser gets the newest page and nothing upstream ever waits |

### 1.2 The pass

One pass per wake, over ONE database value:

1. read the **watched-agent registration** (§1.4) — the set of agent
   ids with at least one open tab;
2. for each watched agent, derive its surfaces
   (`block/surfaces`, `:seon.render/html`), passing the agent's
   current stream snapshot (if any, §2) into the render unit as the
   transient `:seon.ai/partial` value;
3. serialize each surface (`surface-html` unchanged — error card,
   empty wrapper, one id per block);
4. **equality-suppress at the proc**: compare against the last value
   PRODUCED (proc state, `{agent-id → {surface-id → html}}` —
   disposable, rebuilt by one re-render after any restart);
5. when anything changed, put ONE complete value to `::pages`: the
   full current `{agent-id → {surface-id → html}}` map. The `mult`
   fans it to every tap.

**Complete snapshots on the mult, never incremental patches** — this
is load-bearing, not a style choice. A `(sliding-buffer 1)` tap holds
exactly one pending value; incremental `[surface-id html]` patches on
that buffer mean a slow tab whose pending patch for surface A is
displaced by a patch for surface B PERMANENTLY loses A's morph until A
next changes. The buffer table has no row for "increments on sliding-1"
because that combination is a lost-update bug by construction; the
newest-only row demands a complete value. Morph granularity is
preserved at the socket (§1.3): the per-tab writer diffs the snapshot
against what IT last delivered and sends only changed blocks — the
same 287-bytes-not-82,893 wire economy `web.clj` proved, now computed
per tab from one shared derivation instead of derived per tab.

**The coalesce floor** (`:seon.config.render/coalesce-ms`, unchanged,
still a config fact) is honored at the PROC: after a pass, a wake
arriving inside the floor waits out the remainder before the next
derivation. One place, so a burst of commits costs one derivation for
the whole cluster rather than one per tab. The per-tab writer just
writes. This is the one surviving use of the render clock and it
remains a coalescing floor over an observed event (the commit,
delivered by `listen!`), never a poll.

The proc's `:ping-map-fn` exposes `{passes, watched-agents,
tap-count, streaming-agents}` — the monitor/problems derivations
consume pings; nothing stores health.

### 1.3 Per tab: a tap and a virtual thread — never a graph, never a listener

`feed`'s `on-open` becomes: register interest for the agent (§1.4),
tap the mult with `(sliding-buffer 1)`, paint once from the current
database value (the initial full paint — every block, at its own id),
then loop on the tap: take the newest complete snapshot, select this
agent's entry, diff against the connection's own last-delivered map,
`patch-elements!` only the changed blocks. `on-close`: untap,
deregister interest. The connection owns exactly one virtual thread
and one map; nothing outlives the socket.

DELETED from `feed`: the per-connection `d/listen` registration, the
hand-rolled `ArrayBlockingQueue` latest-wins mailbox, the per-tab
`poll`/coalesce loop, and the per-tab full re-derivation. The `not-yet`
census is rewritten in the same commit: `::shared-registration` and
`::isolated-sink` LAND as this change; `::per-tab-graph` is REJECTED
(not deferred) — the inventory's ruling, and `ui.md`'s per-tab-graph
paragraph is reconciled in the same wave; `::interest-matching`
remains open as a computed-rule accretion (review point R6).

Backpressure walk: the proc's put into the mult parks only if every
tap is full AND unbuffered — with sliding-1 taps it never parks; each
tab's socket backpressure is absorbed by that tab's own loop,
invisible to the proc (inventory §3.2, measured §5).

### 1.4 The watched-agent registration and the render wake

- **Registration**: a process-local atom `{agent-id → open-tab
  count}`, held on the web service value, written by
  `on-open`/`on-close`, read by the render pass. Disposable by the
  transport law: a reconnecting tab re-registers, and reconnect =
  repaint. It is not a fact — it names live sockets. `on-open` also
  offers one render wake so a freshly watched agent is derived
  without waiting for the next commit.
- **The wake source**: `wake/route!` — the cluster's ONE listener —
  gains a third delivery: after the per-datom routing, offer ONE
  payload-free wake into the render channel per transaction report,
  unconditionally. Every commit is render interest (receipts, replies,
  and problems are page content), the offer is one line under the same
  two prohibitions (never throw, never park), and this keeps one
  listener per cluster instead of resurrecting a second registration.
  A closed render channel refusing delivery is a fault fact, exactly
  as for a mailbox.

### 1.5 What this deletes and adds — render side

DELETE (in `src/seon/render/web.clj`): the `d/listen`/`d/unlisten`
pair in `feed`, the `ArrayBlockingQueue` mailbox, the per-tab
paint-loop re-derivation, the `::per-tab-graph` and stale not-yet
prose. `paint!`/`changed` reshape into the proc pass + per-tab diff
(the equality-suppression comparison — bytes, deterministic
serialization — is UNCHANGED in kind, relocated in owner). `shell`,
routes, `/data`, ports, `start!`/`stop!` survive byte-for-byte in
intent.

ADD: `render-step` (Flow's four arities, var-backed, `:io`), the mult
+ registration on the service value, the render channel + stream
channel created in `arm-agents!` and carried on the handle. New
DATABASE attributes: **zero**. All new state is presence, derivation,
or process-local channel machinery.

## 2. Streamed tokens — provider fold → sliding-1 conn → render proc

### 2.1 The conversion

`seon.ai/stream-fold` and `stream-event` (the pure wire fold) survive
untouched. The sink becomes one line built by the turn: `(fn
[snapshot] (async/offer! stream-channel {agent-id + run-id +
snapshot}))` onto the cluster's ONE stream conn — a `(sliding-buffer
1)` in-port of the render proc. The turn's `:call` arm passes
`:seon.ai/stream? true` and that sink in the provider request; the handle
(`seon.cluster.loop/cluster`, via `loop-handle`) gains the stream
channel. Streaming is ON by construction: a streamed call and a
one-shot call already return the same completion value, and the sink
is one `offer!` — there is no dial to add (the old
`:seon.config.ai.stream/publish-ms` cadence dies; the buffer IS the
coalescing, and the render proc's coalesce floor is the only repaint
clock).

Channel value: the agent id, run id, and complete `:seon.ai/partial`
snapshot `{text, tokens}` — complete, never a delta, so a consumer
that misses one is briefly behind and the next repairs it. The render
proc holds `{agent-id → {run-id + snapshot}}` in proc state beside its
produced memory. At the pass's ONE database value it retains only
entries whose run has no `:seon.cluster.run/plan-digest`,
`:seon.cluster.run/error`, or `:seon.cluster.run/closed-at` fact, then
passes the admitted snapshot into the render unit; the two blocks read
`:seon.ai/partial` from the unit instead of running a query.

There is NO clear channel value. The frozen-plan/error/close fact is
the stream terminal. Its ordinary render interest runs a fact-only
pass, replaces the temporary projection, and drops cached partials.
Reconnect uses that same fact-only repaint, so it renders the settled
fact or nothing and can never restore a partial.

**The database commits only the settled reply.** The attempt row and
the settled reply were already the durable path's facts; a 20 KB
partial fifty times a second was never a fact. Measured margin: a
channel hand-off is 0.01 ms where the durable transact of the same
value is 74–88 ms (flow-mechanics §5) — the partials were paying
~7,000× to be facts nobody could ever need after the reply settled.

### 2.2 DELETE — the entire database half of `seon.ai.stream`

- `snapshot-tx`, `settle-tx`, `publisher` (virtual thread + mailbox +
  atom + volatile + cadence loop), the private `row` query;
- `src/seon/schema/stream.edn` WHOLE: the attributes
  `:seon.ai.stream/id`, `/agent`, `/text`, `/tokens`, `/at` (with
  their `:seon.db/no-history?` registrations — the facet's one use
  dies with them), the `:seon.ai.stream/stream` entity,
  `:seon.config.ai.stream/publish-ms`, and the
  `publisher-request`/`publisher` shapes;
- the settle-rides-the-terminal-transaction coupling (it existed only
  in prose — the turn never wired it; nothing to unwire);
- the namespace `seon.ai.stream` itself. The two exercise blocks
  survive re-pointed at the unit's `:seon.ai/partial` and move to the
  render owner (review point R3); they remain ordinary blocks — the
  claim they exist to test is unchanged and now stronger: the
  highest-churn thing in the system needs no render machinery AND no
  facts.

`seon.ai`'s wire half (`stream-fold`, `stream-event`,
`streamed-completion`, the `:seon.ai/sink` / `:seon.ai/stream?`
request keys) survives unchanged.

### 2.3 Crash walk — kill -9 at the worst instant

| lost | re-derives / why correct |
|---|---|
| the newest snapshot(s) on the stream conn, the proc's snapshot state | nothing needs to: the attempt row committed receipt-before-terminal, and the settled reply either committed or the run recovers as interrupted. **Partials lost = correct** — they were presentation |
| pending page snapshots on the mult/taps, produced-memory, the registration | every tab reconnects and repaints from current facts; reconnect = repaint, and the registration re-fills from `on-open` |
| the render wake | the next commit re-offers; a freshly opened tab offers its own wake |

**The stale-partial crash row is DELETED, not handled**: today's walk
("a kill mid-stream leaves a partial row… `settle-tx` retracts it
whenever it next runs") described repair machinery for a fact that
should never have existed. After F2 no partial row CAN exist at any
basis, so there is nothing to retract, nothing to mistake for a
settled reply, and no repair path to test — the class is
unrepresentable, which is the construction the testing doctrine asks
for.

Concurrent streams share the one sliding-1 conn: agent A's offer can
displace agent B's newest snapshot. This is a transient presentation
lag repaired by B's next chunk (~one token later), and the TERMINAL
text never depends on the channel — facts own it. Review point R4
records the rejected alternative.

## 3. The central-pass deletion — exact inventory

All dead paths verified caller-free or fenced before the cut; each
line names what survives in its place.

### 3.1 `src/seon/cluster/loop.cljc`

| dies | survivor |
|---|---|
| `step` — all four arities: the serial pass (settle-all → global `next-work` → turn → global `more-work?` rewake), its `::wake` in-port wiring, its stop-transition `wake/unlisten!`, its turn counter | `seon.cluster.agent/turn-step` (F1, landed): the same pass narrowed to one agent — per-agent orphan settle, `next-agent-work`, `cluster.loop/turn`, self-rewake into its own mailbox |
| the namespace docstring's one-proc/serial-turns contract prose (lines 2–58) | the F1 blueprint docstring in `cluster/agent.clj`; `loop.cljc`'s docstring re-grounds on what it still owns: the turn |
| schema shapes `:seon.cluster.loop/state`, `:seon.cluster.loop/turns` (`schema/loop.edn`) | the turn proc's own state keys live in `agent.edn` |

SURVIVES in `loop.cljc`, explicitly: `turn` (open/call/resume/close —
the custody law, the pre-provider capture, `terminal-tx`'s one
commit), `settle-interruption!`, `committed-attributes`,
`disposition`, `messages`, `error-tx`/`refused!`/`record-attempt!`,
and the handle/`turn-request`/`turn-report`/`completion` schemas. The
namespace keeps its name in this wave — cut first; the
`loop`→`turn`-flavored rename is a separate orchestrator-owned atomic
wave (review point R5).

**The in-pass sleep backoff** (`loop.cljc:776`, the only
`Thread/sleep` in `src/`): the charter lists it with the pass, but the
line lives inside `turn`'s `:call` arm, which survives. Ruling
proposed (review point R1, the wave's one genuine judgment call):
the SLEEP SURVIVES inside the per-agent turn proc. What the charter's
objection named — one sleeping thread freezing the entire cluster's
only pass — is dissolved by topology: the wait now delays exactly the
one agent whose provider is failing, on that agent's own `:io`
virtual thread, while its mailbox stays responsive and every other
agent proceeds. The clock itself is the one PERMITTED class (a
genuinely unobservable external state — remote provider recovery),
bounded by the derived finite schedule, empty whenever a backup
exists. Deleting the arm instead would delete real resilience
(bounded retry of conclusively-unpaid transient failures) to satisfy
a sentence whose target no longer exists.

### 3.2 `src/seon/cluster/work.cljc`

| dies | survivor |
|---|---|
| `next-work` — the global sorted `some` (wrong the moment two agents run; F1 already re-implemented it AS that `some` so the two could not disagree during the overlap) | `next-agent-work` — the one derivation, agent-scoped (landed, F1 §5.2) |
| `more-work?` | `more-agent-work?` (landed) |
| `interruptions` (the global plural) | `interruption` (db, agent-id) — each turn proc settles its own orphan; the armer's arm-prime pass covers agents with no graph yet (F1 §4) |
| `agents-with-work` (private, `next-work`'s only feeder) | nothing — no consumer remains |
| schema shape `:seon.cluster.work/request` (`schema/work.edn`) | `:seon.cluster.work/agent-request` |

The fold's global next-ordinal lookup — F1 §10's third item — was
already fixed in F1 (`d1ec4a019`; `loop.cljc:949-961` now calls
`next-agent-work`): nothing left to cut, recorded here so nobody hunts
for it.

### 3.3 `src/seon/cluster/wake.cljc` and `src/seon/cluster.clj`

| dies | survivor |
|---|---|
| `wake/listen!` (the one-global-channel delivery; zero production callers since F1) and `wake?` (its only predicate consumer) | `wake/route!` — the per-agent routing listener, which gains the §1.4 render-wake delivery in this same wave |
| `wake-attributes` as `listen!`'s input | `wake-attributes` SURVIVES re-grounded as the ONE derivation of route!'s set — `#{:seon.cluster.message/to :seon.cluster.agent/id}` — so the disjointness property (C2) keeps two computed sets to compare and route!'s `case` cannot drift from the property silently |
| `cluster.clj/attributed-run` (`cluster.clj:508-526`) — the serial-era "the one run this process holds" query, exact only under serial turns | fault attribution is structural: tagged faults attribute through `tagged-run` (landed); an UNTAGGED fault (the cluster graph's own — armer, render) attributes to no run, correctly, because it is not a run's fault. `commit-fault!` loses its fallback branch |
| the docstring/comment references to the wake/commit-disjointness set as `#{:seon.cluster.message/to}` alone | updated to the route! set in the same commit |

### 3.4 `src/seon/render/web.clj` and `src/seon/ai/stream.clj`

Named in §1.5 and §2.2; listed here so this section is the complete
deletion inventory. Totals: **13 named functions** (`step`,
`next-work`, `more-work?`, `interruptions`, `agents-with-work`,
`attributed-run`, `listen!`, `wake?`, `snapshot-tx`, `settle-tx`,
`publisher`, `row`, plus `feed`'s listener/mailbox internals counted
as one), **6 registered attributes** (5 × `:seon.ai.stream/*` database
attributes + 1 config attribute), **1 entity**, **5 schema shapes**
(`stream.edn`'s two request/handle shapes, `loop/state`, `loop/turns`,
`work/request`), **1 whole namespace** (`seon.ai.stream`), **1 whole
schema file** (`schema/stream.edn`). New database attributes: **0**.

## 4. The test funeral — old tests die in the same commits

Enumerated by rg against the suites as they stand; each row names the
commit family it dies or re-grounds in (same commit as its mechanism,
per cut-first), and the surviving regression that replaces it.

| suite | pins | disposition |
|---|---|---|
| `test/seon/ai/stream_test.clj` — `the-partial-attributes-carry-no-history`, `snapshots-upsert-one-row`, `settling-retracts-the-partial-and-is-idempotent`, `the-sink-does-no-work-on-the-callers-thread`, `publishing-coalesces-and-stop-commits-the-complete-value` | `snapshot-tx`/`settle-tx`/`publisher`/the attributes | DIE with §2.2's commit. Their one durable lesson (presentation may lag/drop, never slow the producer) is re-asserted by the sealed suite's zero-datom + never-parked oracles |
| same file — the fold/wire tests (`the-fold-accumulates-text…` through `a-broken-sink-cannot-fail-a-real-call`, the real-socket SSE stub tests) | `seon.ai` wire half | SURVIVE unchanged; the file renames with the block move (R3) or splits into `test/seon/ai_stream_fold_test` — mechanical |
| same file — `the-exercises-are-ordinary-blocks` | the two blocks querying facts | RE-GROUNDS: blocks read `:seon.ai/partial` from the unit; the assertion (ordinary blocks, no streaming-specific render path) is unchanged |
| `test/seon/render/web_test.clj` — `the-initial-paint-sends-every-block-once`, `only-the-block-that-changed-goes-on-the-wire`, `a-broken-block-paints-its-card…`, `reconnect-is-repaint`, `two-tabs-each-get-their-own-complete-paint`, `suppression-compares-bytes`, `a-nil-projection…`, `a-later-non-nil-render…` | the per-connection listener + mailbox + per-tab derivation | RE-GROUND onto the render proc + mult + taps in §1's commit, keeping the REAL-SOCKET pattern verbatim (real http-kit, real SSE reads, counted events, loud-backstop futures). The oracles are wire-level and survive word-for-word; only the machinery under them changes. `two-tabs…` gains the one-derivation ping oracle |
| same file — shell/opener-sibling/routes/404/drill/port tests | surviving mechanisms | SURVIVE untouched |
| `test/seon/cluster/wake_test.clj` — all six (`a-wake-attribute-wakes…`, `the-loop-never-wakes-itself`, `a-committed-wake-attribute-delivers-one-wake`, `a-saturated-channel-drops-and-never-parks`, `a-throwing-handler-delivers-a-fault…`, `unlisten-is-idempotent…`) | `wake/listen!`/`wake?` | RE-GROUND onto `route!` in §3.3's commit: same six classes (wake-on-set-only, C2 disjointness against the re-grounded `wake-attributes`, one-delivery, drop-never-park, fault-never-hang, unlisten idempotence), now driven through routing delivery to a mailbox/armer/render channel |
| `test/seon/cluster/loop_test.clj` — `every-kill-position-has-one-next-action`, `an-interrupted-form-is-never-re-executed` | `work/next-work` (global) | RE-GROUND: same rows, same expected situations, derived through `next-agent-work` with the agent-scoped request — the kill positions are per-agent facts and always were |
| same file — `the-committed-set…`, `a-disposition-is-read…`, `the-terminal-transaction…`, boot-installability pair | survivors | SURVIVE untouched |
| `test/seon/cluster/work_test.clj` — `the-derivation-is-total-over-every-state` (+ `more-work?` agreement clause), `an-unplanned-orphan-run-is-settled-not-resumed`, `a-planned-orphan-run-is-work-not-an-interruption` | global `next-work`/`more-work?`/`interruptions` | RE-GROUND to `next-agent-work`/`more-agent-work?`/`interruption` — the totality property is the standing choke-point regression for the situation enum and MUST keep one seeded generative form |
| same file — `answeredness-is-transaction-metadata`, `triggers-come-back-oldest-first` | survivors | SURVIVE untouched |
| `test/seon/cluster/turn_test.clj` — ~14 call sites of `work/next-work`/`work/interruptions` used as fixture plumbing | the global derivations as conveniences | RE-POINT mechanically to the agent-scoped forms (each site already knows its agent); zero oracle changes — this suite asserts the TURN, which survives whole |
| `test/seon/flow_configuration_test.clj` — the workload census | every proc pinned | SURVIVES; the render proc joins the census automatically (it is built through `var-process`, which refuses `:mixed` at construction) |

Rule restated from the charter: each dying test is deleted in the SAME
commit as its mechanism, and no survivor is green-washed — a
re-grounded test keeps its oracle or gets a stronger one, never a
looser one.

## 5. The sealed suite — seeds 2026072821+

Continues the fixed-seed series (F1 ended at 2026072820). Per-trial
in-memory databases through the canonical fixture; recorded stub
providers (a ledger of calls, an SSE stub server where the claim is
wire-level — the landed `web_test`/`stream_test` real-socket
patterns); every deftest names its oracle; event-driven waits bounded
by loud-backstop futures, no sleep-as-proof.

| deftest / property | seed | oracle |
|---|---|---|
| `streaming-writes-zero-datoms-test` | 2026072821 | a stubbed streamed `:call` through the real turn with the channel sink: the datom census between the capture commit and the terminal transaction contains ONLY the attempt row and the terminal facts — zero streaming datoms (and the registry no longer contains any `:seon.ai.stream/*` attribute to write); a payload-free fact interest supersedes the partial after settlement, with no clear channel value; the settled reply's text equals the fold's final snapshot text |
| `render-proc-one-derivation-many-tabs-test` | 2026072822 | N real SSE tabs on one agent, one committed change: each tab receives exactly the changed block's morph (counted SSE events, byte-compared); the proc ping pass-count advanced by ONE for the commit, not by N; an untouched block's id appears on no socket |
| `slow-tab-newest-complete-page-test` | 2026072823 | one tap deliberately unread while K distinct commits land: on read it yields ONE value equal to the newest complete page (every block current — no lost morph, the §1.2 displacement class dead by construction); the proc's pass count advanced K′ ≤ K times (coalescing) and never parked (a fast sibling tab observed every suppressed-distinct repaint) |
| `reconnect-is-repaint-wire-test`; `reconnect-mid-stream-is-a-fact-only-repaint` | 2026072824 + seal revision | the in-process kill projection and a retained-proc reconnect: drop taps/channel contents or disconnect mid-stream, reopen the feed — the initial paint derives every block from current facts and no cached partial is restored; the database holds NO partial text at any basis (as-of walk over the call window); nothing is retracted because nothing was written |
| `concurrent-streams-share-one-conn-test`; `a-terminal-fact-supersedes-a-partial-after-the-lost-clear-ordering` | 2026072825 + seal revision | two agents' streams share the one sliding-1 conn and both settle at their exact fact-backed texts; the audit's A-partial → B-displacement → A-terminal ordering proves A's terminal fact replaces its retained half-reply, and a delayed A partial cannot repaint over that fact; every channel value carries a run id and complete partial, never a clear; provider folds never park |
| `route-render-wake-and-disjointness-property` | 2026072826 | generated commit batches: the render channel receives a wake for EVERY report; mailbox routing is unchanged by the added delivery; the C2 property holds over the re-grounded computed sets — `(wake-attributes)` vs `(committed-attributes)` — with the message/to delivery intersection asserted from the other direction, exactly as today |
| `kill-positions-per-agent-test` | 2026072827 | the loop_test crash-walk rows 1–10 re-grounded: `next-agent-work` derives the same expected situation per row under the agent-scoped request; the interrupted ordinal is never re-derived for execution |
| `coalesce-floor-one-derivation-test` | 2026072828 | M commits inside one floor window: the proc runs exactly one derivation pass after the floor, and each tab receives at most one morph per actually-changed block (wire-level counts against the config fact planted per-trial) |
| `situation-totality-property` (re-seal of the work_test property, agent-scoped) | 2026072829 | generated run/receipt/trigger states: `next-agent-work` is total, returns only the four situations or nil, `more-agent-work?` never disagrees, and `:resume` always carries the first unsettled ordinal — the one choke-point regression for the situation class |

Suite-wide rules: fixed seeds with shrunk counterexamples printed;
`bin/test` green from the F1 baseline before the wave starts and after
every commit in it; the funeral table (§4) executes inside the same
commits as its mechanisms; the real kill -9 process-death drive stays
owned by F4.

## 6. New attributes — count: 0

Nothing in this wave stores anything. The stream snapshot, the page
snapshot, the produced-memory, the delivered-memory, the watched
registration, the mult, and both wake channels are all channel
contents or process-local disposable state, free to lose by the
transport law; the settled reply and the attempt row were already
facts. State is presence; the one deleted no-history family is
replaced by NO family. Attribute namespaces take owning code
namespaces — vacuously satisfied.

**Seal (orchestrator, 2026-07-28 night): accepted as drafted, all three
risk points.** R1 — the backoff sleep survives: it guards a remote
provider, the timeout doctrine's one permitted unobservable, and the
per-agent topology already dissolved the cluster-freeze objection. R7 —
complete newest-page snapshots on the mult are REQUIRED by the transport
law itself (latest-wins loss is only free for a COMPLETE value; a patch
displaced by a patch is permanent loss), with the byte-diff at the
per-tab writer preserving block-targeted morphs on the socket. R4 — the
shared sliding-1 stream conn's transient displacement under concurrent
streams is accepted at token cadence (repair next chunk, terminal from
facts); the sealed suite must include the concurrent-streams repair
case. The route! third delivery (one unconditional render wake per
report) is load-bearing and named here so review never mistakes it for
incidental. IMPLEMENTATION SEQUENCING: dispatch AFTER the
test-constructions lane returns units 4/5/8 — it holds wake_test and
cluster suites this wave deletes/re-grounds.

## Orchestrator review points — judgment calls with alternatives

- **R1 — the backoff sleep survives the charter's sentence.** Chosen:
  keep the bounded backoff wait inside `turn`'s `:call` arm (§3.1
  reasoning: per-agent topology dissolves the freeze; the clock guards
  a genuinely unobservable remote state, the one permitted class; the
  schedule is finite, derived, and empty under a configured backup).
  Alternative A: delete the `:backoff` arm, `ai/delays`, and the
  `:seon.ai.retry/*` dials — a conclusively-unpaid transient failure
  closes the run and the agent adapts; maximal deletion, but it
  removes real resilience and makes a provider hiccup cost a whole
  agent turn. Alternative B: commit a retry-at fact and build a timer
  wake — MORE machinery around the same clock, rejected on sight.
  This is the wave's one place where the charter's letter and the
  surviving code disagree; the cut proceeds either way (the pass dies
  regardless), so ruling this does not gate the wave.
- **R2 — render-step owner namespace.** Chosen: `seon.render.web`
  (the proc produces exactly the bytes the socket delivers; one owner
  for serialization, suppression, and the wire — and the per-tab diff
  must agree byte-for-byte with the proc's serialization, which
  colocation makes structural). Alternative: a new
  `seon.render.feed`/`seon.render.proc` namespace — cleaner
  socket/derivation split, but it would split the byte contract across
  two files whose drift is exactly the bug class suppression exists to
  kill.
- **R3 — the two stream blocks' new home.** Chosen: move
  `tokens-html`/`text-html` into the render owner beside the other
  block families (with `seon.ai.stream` deleted whole), reading
  `:seon.render/unit`'s transient `:seon.ai/partial`. Alternative:
  keep a slimmed `seon.ai.stream` holding only two blocks — preserves
  git lineage but keeps a namespace whose name promises a mechanism
  that no longer exists. Also fold: they are currently seeded by
  NOTHING (`root.clj` never references them); the block-seed owner
  should decide in this wave whether they enter the default seed or
  stay suite-only exercises.
- **R4 — one shared stream conn, not per-agent channels.** Chosen: one
  sliding-1 conn for the cluster; concurrent streams can transiently
  displace each other's newest snapshot, repaired by the next chunk,
  and terminal text never depends on the channel. Alternative:
  per-agent stream channels as render-proc in-ports — impossible
  without a graph rebuild per agent create (flow freezes in-ports at
  describe/start), i.e. it reintroduces exactly the
  topology-per-agent-datum coupling the F-series killed. Second
  alternative: a fixed-buffer shared conn — changes the loss semantics
  from newest-only to park-the-producer, which would let a slow render
  pass backpressure a provider fold: forbidden by the streaming
  invariant.
- **R5 — no rename in the cut wave.** `seon.cluster.loop` keeps its
  name while its loop dies (the survivor is the TURN family). The
  rename (`seon.cluster.turn`, with `loop.edn`'s shapes) is a separate
  orchestrator-owned atomic wave per the shared-tree rename rule —
  cheap pre-N5, and mixing it into the cuts would blur every diff the
  review depends on.
- **R6 — interest matching stays a computed-rule accretion.** The
  render wake fires on EVERY commit; the pass is bounded (~0.5 ms per
  watched page, watched-only by §1.4) and the wire is already exact.
  Matching commits against block read-sets needs the program graph's
  `:seon.fn/calls`-era facts (N5) to be a COMPUTED rule; hand-listing
  read attributes per block now would be the hand-list class. Named
  here so its absence is a decision, not a silence.
- **R7 — complete snapshots on the mult.** Chosen and argued in §1.2
  (sliding-1 + increments = permanently lost morphs). Alternative —
  per-tab unbounded/fixed patch queues — restores increments at the
  cost of either unbounded memory on a dead tab or producer parking on
  a slow one; both violate the table. Flagged because it reverses the
  landed `web_test` patch vocabulary at the proc boundary while
  preserving it at the socket, and a reviewer diffing the suite will
  see patch assertions move from the channel to the wire.

## Sequencing

Dispatches after F1 green (satisfied — F1's implementation note
records the landed baseline and ten-test suite). Same surgery area as
F1's files; the context-blocks lane owns `seon.context`/`seon.render`
protected paths until it returns — the block MOVE in R3 touches
`seon.render.*`: if that lane is still holding those paths when this
wave dispatches, land §2.2's deletion with the blocks parked in a
suite-support namespace and complete R3 on the lane's return, rather
than waiting. Commits are path-limited per file family: (1) stream
conversion + `seon.ai.stream` funeral, (2) render proc + `web.clj`
conversion + web_test re-ground, (3) central-pass cut
(`loop.cljc`/`work.cljc`/`wake.cljc`/`cluster.clj`) + its suite
re-grounds, (4) the sealed suite. `bin/test` green after each.
