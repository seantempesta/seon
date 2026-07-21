---
type: research
status: draft
tags: [research, agent, database, wasm]
---

# Reactive DB access for wasm-sandboxed agents — design (2026-05-29)

Research + design for giving wasm-sandboxed Seon agents complete, fast, working,
reactive database access. The one-shot ops (q/pull/transact) are protocol-tested
green; the reactive path (listen/subscribe) is architected-but-untested and uses
a busy-poll model that is suspect under the wasm execution constraints. This doc
reads the actual code, the benchmarks, and one Gemini consultation, and lands a
direction + a phased plan.

## TL;DR

- The reactive path should **stay** pub-socket → tokio broadcast, but the guest
  loop should change from a `setTimeout(25ms)` busy-poll to a **bounded-blocking
  `next-tx-event`** (host `wstd::block_on` on `recv()` with a ~100ms timeout,
  cooperating with the QuickJS microtask loop exactly like the proven wasi:http
  path) chained by `.then` recursion. This kills the setTimeout dependency (which
  is the SAME wstd timer that hangs the guest), removes the up-to-25ms latency
  floor, and drops idle CPU to ~zero. **Async WIT imports are not available**
  (wasm-rquickjs 0.2.4 hard-errors), so "blocking" means host-side `block_on`,
  not an async import surfacing a JS Promise.
- The single most important correctness risk is **silent event loss on broadcast
  Lagged** (capacity-256 ring, permanent drop). The fix is self-healing **catch-up
  by basis-t**: events carry monotonic `basis-t`; on Lagged (or reconnect) the
  guest re-derives missed state by querying at the gap range instead of relying on
  never dropping. Treat the event stream as a lossy wake-up signal, never the
  source of truth.
- The **124ms tx p50 is durable-commit + IPC, not the architecture** — it was
  measured against the `:file` konserve backend. It is reducible (`:memory`
  backend, async-commit ack) but the right framing is: writes don't need to be
  fast for reactivity; only the commit→event→handler latency does, and that path
  is sub-millisecond on the JVM/host side once the poll interval is removed.
- Build the **Rust host bridge integration test first** (push→pull: ordering,
  lag, dedup, blocking-recv) — it is self-contained, no wasm, and exercises the
  one untested layer with the highest correctness risk.
- Surprise: the guest's `ensure-listener-loop!` uses `js/setTimeout` to pace the
  poll — the exact primitive the verification doc proved hangs the guest under
  wstd. The reactive loop as written is very likely **Node-only** and has never
  been demonstrated working in wasm. This is the gap to close.

---

## The three DB-access contexts compared

| Aspect | V0 CLJS pod (`src/seon/db.cljs`) | Wasm guest overlay (`guest-cljs/.../db.cljs`) | JVM wire-server (`src/seon/server/`) |
| --- | --- | --- | --- |
| Where the DB lives | in-process datahike-cljs | NONE — wire to JVM | in-process datahike (the master) |
| `query`/`pull`/`entity` | sync against `@*conn*` (a db value) | sync WIT call → host snapshot-cache / JVM `as-of basis-t` | direct datahike on its own thread |
| `transact!` | `^:async` → local datahike writer | WIT `transact` → host batcher → JVM commit → broadcast | `d/transact` then `bcast/broadcast!` (wire.clj:276) |
| `listen!` | datahike NATIVE tx-listener, fires **synchronously** on the local commit, with the full datahike report (`:db-before`/`:db-after` real db values) | guest `listen!` → `ensure-listener-loop!` polls `next-tx-event` over WIT; fan-out to local callbacks | n/a (the broadcast IS the listen primitive) |
| Handler input | `:db` = real post-commit db value; `:db-before` = real pre-commit db value; `:datoms`; `:attr-index` | `:db` = the wrapped conn (queried at "now"); `:db-before` = **nil** (wire event ships no pre-commit handle); `:datoms` decoded from `[e a v t op]`; `:attr-index` | n/a |
| Concurrency primitive | `node:async_hooks` ALS (real fiber-local) | userland `seon.client-runtime.als` (snapshot/restore atom; single QuickJS fiber) | JVM threads |
| Timer availability | full (Node) | **broken** — wstd parks forever on wasi:clocks timer pollables | full (JVM) |
| core.async | available | **banned** (transitively pulls the timer-parking dispatcher) | available |

The load-bearing differences for reactivity:

1. **V0 listen is synchronous and local; wire listen is asynchronous and polled.**
   In V0 the handler runs *inside* the transacting call's stack, with
   back-pressure. In the wire path the handler runs later, when the guest's poll
   loop happens to dequeue the event from the host broadcast channel. Same API
   shape; different timing and different delivery guarantees.

2. **`:db-before` is nil on the wire path.** Any V0 handler that does
   change-detection by diffing `:db-before` vs `:db` breaks silently under the
   wire path. The canonical kick-the-loop reaction (`db.cljs` docstring) does NOT
   use `:db-before` — it reads `:attr-index` for newly-added `:user`-role datoms
   — so it survives. But this is a real semantic divergence, not a no-op.

3. **The wire `:db` value is "now", not "the post-commit snapshot of this exact
   tx".** V0's `:db` is `:db-after` of the firing tx; the wire overlay passes the
   conn and queries at call time. If two txes land between event delivery and
   handler execution, a wire handler queries the later state. V0 cannot have this
   race. For most reactions this is harmless (you re-derive from current state
   anyway — the reactive-context principle), but it is not byte-identical.

---

## The reactive architecture (current)

```
JVM writer (wire.clj handle-op "transact")
  d/transact conn  →  tx-report→ok-map  →  bcast/broadcast! event
                                              │  (one frame per commit)
                                              ▼
                              pub Unix socket (broadcast.clj, PUSH)
                                              │
                                              ▼
Rust host (main.rs run_pub_subscriber)  reads frames  →  tokio broadcast::Sender (cap 256), one per DB
                                              │  tx.send(evt)  (best-effort, lossy on full)
                                              ▼
guest subscribe_tx (guest.rs:462)  →  broadcast::Receiver stored in self.subs
guest next_tx_event (guest.rs:481)  →  rx.try_recv()  [NON-BLOCKING; Empty→"no-event", Lagged→error]
                                              ▲
                                              │  WIT (sync to JS)
guest ensure-listener-loop! (client_runtime/db.cljs:225)
  Promise loop: next-tx-event-call → if :no-event, (js/setTimeout 25ms) → retry
                                   → if event, fan out to (:listeners conn), retry immediately

```

Layer status:

- **L1 JVM pub — TESTED, green.** `protocol_integration_test.clj`: 3 transacts →
  exactly 3 ordered events with strictly-monotonic `basis-t`; full `[e a v t op]`
  datoms; reads fire zero events; request-id round-trips on both response and
  event; tx-meta (`:db/txInstant`, `:db/commitId`) identical on response + event.
- **L2 Rust host bridge — UNTESTED.** `run_pub_subscriber` → broadcast →
  `subscribe_tx` / `next_tx_event`. No Rust test exists for ordering under
  concurrent senders, Lagged behavior, dedup, or the blocking variant.
- **L3 guest poll loop — UNTESTED, and likely Node-only.** `ensure-listener-loop!`
  paces with `js/setTimeout` — the wstd-parking primitive. Never demonstrated in
  wasm.

---

## Answers to the research questions

### Q1 — Is busy-poll `next-tx-event` correct AND fast enough? Should it block?

**Poll interval:** the guest loop sleeps `25ms` between empty polls
(`client_runtime/db.cljs:236`, `sleep-tick` → `js/setTimeout … 25`). The host
comment (`guest.rs:495`) documents the 25ms choice as a latency/CPU balance.

**Latency:** JVM commit → pub frame → host broadcast send is sub-millisecond (L1
test asserts `<1µs` commit→sub on the JVM side; the host fanout is a `tx.send`).
But the guest only *observes* the event on its next poll, so the
**commit→handler latency is uniformly distributed in `[0, 25ms]`, ~12.5ms mean**,
purely from the poll interval. For a reactive agent that "kicks on user message"
this is fine; for tight derived-view updates it is a needless floor.

**Lag / dropped events:** `try_recv` returns `Lagged(n)` when the capacity-256
ring overflows because the guest didn't drain fast enough. The current host code
(`guest.rs:510`) logs a warning and returns an `Internal` error string — the
**events are permanently lost** and the guest gets a generic error it does not
specifically handle (`ensure-listener-loop!` only special-cases the `"no-event"`
substring; a Lagged error falls into the `js/console.warn` + `:no-event` branch,
so it is swallowed and the gap is never recovered). **This is the correctness
hole.** Under a burst of >256 commits faster than a 25ms-paced poll can drain
(256 × any tx ≈ trivially exceeded by a bulk-load), the agent silently misses
datoms.

**The setTimeout problem (the real blocker):** `js/setTimeout` is the exact wstd
timer path the verification doc (2026-05-28) proved hangs the guest forever under
wasmtime 44 / wstd 0.6.5. The whole point of the wasm sandbox section in MEMORY.md
is "no timer-based parking." So the 25ms `setTimeout` pacing **probably does not
work in wasm at all** — it works in the Node REPL where the overlay is unit-tested,
which is why no one has caught it. This loop has never been shown to fire a single
event inside wasmtime.

**Should it block?** Yes — bounded-blocking, with an important caveat about the
mechanism:

- **Async WIT imports are NOT available.** wasm-rquickjs 0.2.4 hard-errors on
  async imported functions (`imports.rs:240-244`: `"Async imported functions are
  not supported yet"`, per `capability-surface-2026-05-22.md:44-53`). So the
  clean "import returns a JS Promise that the guest `await`s while the fiber
  yields" pattern Gemini describes is **not directly reachable** on the current
  toolchain. To JS, every WIT import is a synchronous blocking call.

- **But the host fn can still block cooperatively.** The host is wasmtime-`async`
  - `#[tokio::main]`, and every db import is already an `async fn` (guest.rs).
  The proven pattern (wasi:http, `capability-surface-2026-05-22.md:166`) is: the
  host fn runs `wstd::block_on` over a bounded future that **cooperates with
  QuickJS's microtask loop** — the wasm fiber suspends, tokio runs other work,
  and to JS it looks like one blocking call that eventually returns. So
  `next_tx_event` should `recv()` with a `tokio::time::timeout` of ~100ms instead
  of `try_recv()`: it returns immediately on an event, or after 100ms with a
  `no-event` sentinel (so the guest can check for cancellation/stop). The guest
  loop then becomes a pure `.then`-recursive Promise chain with **no setTimeout**.

Trade-off table:

| Model | Latency | Idle CPU | Works in wasm? | Event loss |
| --- | --- | --- | --- | --- |
| Current: `try_recv` + `setTimeout(25ms)` | 0–25ms | spins every 25ms | **No** (setTimeout hangs under wstd) | yes, on Lagged (unhandled) |
| Proposed: host `recv()` w/ 100ms timeout, `.then` recursion | ~commit latency (sub-ms host-side) | ~zero (fiber parked) | **Yes** (microtask-cooperative block_on, like wasi:http) | recoverable via basis-t catch-up |

The 100ms timeout exists only as a liveness check (lets the guest notice
`stop!`/cancellation between events); it is not a poll interval and adds no
steady-state latency.

### Q2 — Own-tx dedup, ordering, delivery guarantees

- **Own-tx is NOT filtered today.** A subscriber receives its own transacts back.
  The plumbing for dedup exists: `transact!` generates a `request-id`
  (`client_runtime/db.cljs:106`), the JVM echoes it on both response and pub event
  (`protocol_integration_test.clj/test-request-id-round-trips`), the host forwards
  it on the WIT `tx-event`, and `next-tx-event-call` decodes it
  (`wit.cljs:238`). But **no layer compares the event's `request-id` to the
  guest's outstanding ones**, so a guest's listener fires on its own writes. For
  the canonical kick-the-loop reaction this is harmless because the trigger is
  asymmetric (`:user`-role only; the agent writes `:assistant`), but any
  symmetric reaction would self-trigger. Dedup is a guest-side filter:
  `next-tx-event` event whose `request-id` is in a small "my recent request-ids"
  set → skip the handler (but still bump basis-t).

- **Ordering:** strictly ordered. Single JVM writer commits sequentially
  (monotonic `basis-t`, asserted in L1). The pub socket is a single ordered byte
  stream. `run_pub_subscriber` reads frames in order and `tx.send`s in order.
  tokio broadcast preserves send order per receiver. So events arrive in commit
  order **unless Lagged drops a contiguous middle range** (the ring drops oldest;
  the receiver then sees a gap then resumes in order).

- **Delivery:** **at-most-once** today (best-effort `tx.send`; Lagged drops). It
  should be **at-least-once-with-self-heal**: the broadcast is at-most-once, and
  the basis-t catch-up (Q4) restores at-least-once *effective* delivery by
  re-querying the gap.

- **The subscribe→first-poll window:** between `subscribe_tx` (registers a fresh
  `broadcast::Receiver`) and the first `next_tx_event`, any commit is buffered in
  the receiver's slice of the ring (tokio broadcast delivers to a receiver every
  message sent after it subscribed). So no event is missed in that window *unless*
  >256 commits land before the first drain. The real gap is **before** subscribe:
  events committed before `subscribe_tx` are never delivered — which is exactly
  why a guest must seed `last-seen-t` from the current basis-t at subscribe time
  and catch up the `(seed, current]` range if it cares about pre-existing state.

### Q3 — Can we close the 124ms write latency?

**Where it goes.** The 124ms p50 (and the 86ms post-batcher p50) was measured
against the **`:file` konserve backend** — the bench workload used the durable
store, and `tx p99 ~217ms` is explicitly attributed to "JVM commit + GC"
(`tx-batcher-and-cache-fix-2026-05-25.md`). Decomposition:

- **Dominant: datahike durable commit.** konserve `:file` flushes index nodes to
  disk on commit. This is the bulk of the 86–124ms.
- **2ms batch window** (`BATCH_MAX_WINDOW`, main.rs:460) — only added when
  coalescing; under typical cadence batches stay singletons (avg 1.01) so this is
  ~0 in practice.
- **UDS round-trip + Transit encode** — microseconds to low-ms; the bench notes
  the batcher's channel path is actually *faster* than the old direct call.

**Is it reducible?** Yes, several independent levers:

1. **`:memory` backend** → commit is an in-memory index update, no fsync.
   `store.clj` already supports `:enum :memory :file :sqlite`; the protocol tests
   run `:memory` and L1 commit→sub is `<1µs`. A `:memory` writer with periodic
   snapshotting (or `:tiered` mem-over-file) would put tx p50 in the **single-ms**
   range. This is the biggest lever and matches the three-tier-storage model
   (DB datoms = renderer projections; durable blobs separate).
2. **Async-commit ack** — return the basis-t to the guest as soon as the in-memory
   index advances, flush to disk behind it. Datahike's commit is the sync point;
   decoupling ack from durability is a writer change, not a wire change.
3. **Batch from the guest** — `transact-batch!` already exists end-to-end
   (`client_runtime/db.cljs:112`, WIT `transact-batch`); an agent emitting N
   section-writes in one logical step amortizes the per-tx JVM overhead.

**What do reactive agents actually need?** Reactivity latency ≠ write latency.
The reactive contract is *commit → event → handler*. The write *ack* can take
86ms and the reaction can still fire ~immediately after commit, because the
broadcast happens on the JVM right after `d/transact` returns (wire.clj:276),
independent of when the writing guest's `transact!` Promise resolves. So **for
reactivity, the 124ms is irrelevant**; it only matters for an agent that
sequentially `await`s its own writes before continuing. For that, ship `:memory`
(or async-ack) and the number drops to single-digit ms.

### Q4 — V0 native listen vs wire listen: faithful reproduction?

Handler input comparison:

| Key | V0 native | Wire overlay | Faithful? |
| --- | --- | --- | --- |
| `:seon.db/tx-report` | raw datahike report | the wire event map | partial — different shape (escape hatch, agents shouldn't depend on internals) |
| `:seon.db/db` | `:db-after` real db value | the wrapped conn (queried at "now") | **semantic drift** — see context table point 3 |
| `:seon.db/db-before` | real pre-commit db value | **nil** | **NOT reproduced** |
| `:seon.db/datoms` | decoded from report `:tx-data` | decoded from wire `[e a v t op]` | faithful (same `::e ::a ::v ::tx ::added?` shape) |
| `:seon.db/attr-index` | group-by `::a` | group-by `::a` | faithful |

**Will V0 listen handlers run unchanged on the wire path?** Mostly yes, with two
caveats:

- Handlers that only read `:attr-index` / `:datoms` and re-query via `:db` (the
  reactive-context idiom, and what the canonical kick-the-loop reaction does)
  work unchanged.
- Handlers that diff `:db-before` against `:db` **silently break** (`:db-before`
  is nil). This is the one place "complete" is currently violated. Options:
  (a) ship the pre-commit basis-t on the event (the JVM has `basis-t-before`
  already — it's on the event!) and let the overlay synthesize `:db-before` as a
  db value at `basis-t-before` (queryable via `as-of`); (b) document the
  divergence and forbid `:db-before` reliance. (a) is the "complete + faithful"
  answer and is cheap — `basis-t-before` is already transported
  (`wit.cljs:226`); the overlay just needs to wrap it as a db value the overlay
  `query` understands, identical to how `:db` is "a basis-t snapshot."

### Q5 — Sandbox safety model + WIT completeness

**What the boundary enforces.** The wasm component imports exactly the WIT
`seon:client-runtime/db` interface plus the standard WASI set declared in the
`client-runtime-guest` world (`db.wit:123-140`): clocks, filesystem/preopens+types,
io, random, sockets, logging. The guest's CLJS `cljs.js` eval cannot reach `fs`,
net, or the JVM except through these typed imports — wasmtime's component model
enforces this at the linker (an import not satisfied by the host is a link error;
there is no ambient capability). The pod-host grants only what `PodBuilder` wires.
This is the genuine security boundary the V0 pod lacks (V0's `cljs.js` can
`js/require("node:fs")` directly).

**Caveat:** the WIT world imports `wasi:sockets/tcp` and `wasi:filesystem/types`.
Those are capabilities the host *can* deny (no preopen → no fs reach; no network
config → no socket), but they are in the world because wasm-rquickjs's bundled
builtins (node-http, fs shims) declare them. The DB-access surface itself is the
`db` interface; sockets/fs are orthogonal capabilities the host should scope to
empty for a pure-DB agent. Flag: confirm the production `PodBuilder` does not
grant a TCP allow-list or a writable preopen to DB-only agents.

**Is the `db` WIT the complete op set?** Cross-checking `db.wit` against
`src/seon/db.cljs`'s public API:

| V0 public fn | WIT coverage | Gap? |
| --- | --- | --- |
| `query` | `q(query, args, basis-t)` | covered |
| `pull` | `pull(selector, eid, basis-t)` | covered |
| `entity` | `entity-pull(reference, selector, depth, basis-t)` | covered (eager) |
| `transact!` | `transact(tx-data, tx-meta, request-id)` | covered |
| (batch) | `transact-batch(...)` | covered (bonus) |
| `listen!`/`unlisten!` | `subscribe-tx`/`unsubscribe-tx`/`next-tx-event` | covered (poll model) |
| `with-tx-context`/`with-agent` | n/a (guest-local ALS) | n/a — tx-meta rides on `transact` |
| `assert-preconditions!`/bridge | n/a (JVM owns schema install) | intentional stub |
| schema introspection | `schema`/`reverse-schema` | covered (bonus) |
| filtered db | `db-filter`/`q-filtered`/`filter-release` | covered (bonus) |

**No V0 op an agent relies on is missing from the WIT.** The one functional gap
is not a missing op but a missing *field*: the reactive event has no `db-before`
db handle (it has `basis-t-before` — see Q4). The WIT is complete for the op
surface; reactivity completeness needs the basis-t-before → db-value synthesis in
the overlay and the dedup/catch-up logic, all guest/host-side, no new WIT op.

### Q6 — Testing strategy across the three layers

**(a) Rust host bridge integration test (build FIRST — self-contained, no wasm).**
A `#[tokio::test]` in the host crate that constructs a `broadcast::channel`, the
`Guest`/`DbHandle` subscription machinery, and drives it directly:

- *ordering*: send events with basis-t 1..N; assert `next_tx_event` returns them
  in order.
- *empty*: with the blocking variant, assert `recv` returns the `no-event`
  sentinel after the timeout, not immediately.
- *lag*: send >256 without draining; assert the Lagged path reports the dropped
  count (and, post-fix, surfaces a `Lagged`-tagged error the guest can branch on,
  distinct from `no-event`).
- *dedup primitive*: send an event carrying `request-id "r1"`; assert it is
  delivered with `request-id` intact (the host doesn't filter; the guest does —
  but the test pins that the id survives the host transform `tx_event_to_wit`).
- *blocking-recv*: spawn a task that sends after 30ms; assert the blocking
  `next_tx_event` returns the event at ~30ms, not after the full timeout.

This is ~a day, exercises the highest-risk untested layer, and needs no JVM and
no wasm.

**(b) Level-1 guest listener-loop test on Node against a real JVM.** Run the
overlay (`guest-cljs`) under Node with the WIT module mocked/bridged to a real
wire-server subprocess (the protocol test's `with-fresh-writer` already spawns
one). Register a `seon.db/listen!`, transact from another conn, assert the handler
fires with the right `:attr-index`. This proves the fan-out + decode + handler
contract *in JS* (where setTimeout works), independent of the wasm timer issue.

**(c) Level-2 same test in wasm.** Build the guest into the component, run under
wasmtime, register a listener, drive a commit from the JVM, assert the handler
fires. This is the end-to-end proof and the one that will expose the
setTimeout-hang (and validate the blocking-recv fix). Requires the
reproducible-build work from the verification doc to be settled first.

**Minimal set that proves reactive works end-to-end:** (a) + (c). (a) proves the
host bridge in isolation with full control over lag/ordering; (c) proves the only
thing (a) can't — that the loop actually pumps inside wasm. (b) is valuable for
fast iteration but is not load-bearing for the correctness claim (it can't catch
the wasm-specific timer hang).

### Q7 — The elegant recommendation

**Keep the topology, fix the loop, make the stream self-healing.** The
pub-socket → tokio broadcast → polled-WIT shape is right: it is the minimal
mechanism that respects "JVM owns datahike, guest is wire-only, single fiber, no
timers." Three changes turn it from architected-but-broken into
fast+complete+working:

1. **Blocking `next-tx-event` (host-side `block_on` recv with ~100ms timeout),
   guest `.then`-recursive loop with NO setTimeout.** Removes the wstd-hang
   primitive, the 12.5ms latency floor, and the idle spin. This is the wasi:http
   cooperative-block pattern, already proven in this codebase.
2. **Self-healing catch-up by basis-t.** Treat the broadcast as a lossy wake-up.
   On `Lagged` (and at subscribe time), the guest queries the `(last-seen-t,
   current-basis-t]` range and replays, then resumes. Events already carry
   `basis-t`/`basis-t-before`; the overlay already tracks a `basis-t` atom. This
   makes the reactive stream resilient to the 256-ring overflow that is otherwise
   silent data loss.
3. **Own-tx dedup + `db-before` synthesis.** Filter events whose `request-id`
   matches a recent outstanding write (the round-trip already works); synthesize
   `:db-before` from the transported `basis-t-before` so V0 change-detection
   handlers run unchanged. Both close the V0-fidelity gap.

A host→guest *push* (the host calling a guest export) was considered and rejected:
the component model forbids re-entrancy while the guest is inside a host import,
and even when idle it turns the agent into a passive RPC target with messy global
state (per the Gemini analysis). Pull-with-cooperative-block is cleaner and works
within the actual toolchain (no async-import support needed).

---

## Performance analysis (summary)

- **Reads:** cache-hit p50 = 0µs, p95 = 0µs (host basis-t snapshot cache);
  cache-miss p50 ~0.9–1.4ms. Reactivity does not gate on reads.
- **Writes:** p50 86–124ms against `:file`; dominated by durable konserve commit
  - JVM GC. Reducible to single-digit ms with `:memory`/`:tiered` or async-ack.
  **Not on the reactive critical path** (broadcast fires right after `d/transact`).
- **Reactive (current):** commit→handler = `[0, 25ms]` poll-interval-bound,
  ~12.5ms mean — *if it ran in wasm at all* (it relies on setTimeout, which hangs).
- **Reactive (proposed):** commit→handler ≈ JVM broadcast + UDS + host recv +
  microtask drain = sub-millisecond steady state; idle CPU ~zero.

---

## Recommended approach + phased plan

Ordered by "proves the most risk per unit effort." Each phase ships something
verifiable.

**Phase 0 — Rust host bridge integration test (no wasm). ~1 day.**
Write the `#[tokio::test]` battery from Q6(a): ordering, empty/timeout, lag,
dedup-passthrough, blocking-recv timing. Ships: a green test that pins the host
bridge contract and becomes the regression net for Phase 1. Proves: the one
untested layer, in isolation, with full control.

**Phase 1 — Blocking `next-tx-event` + setTimeout-free guest loop. ~1–2 days.**
Change `guest.rs next_tx_event` from `try_recv()` to `recv()` under a
`tokio::time::timeout(~100ms)` (returning a distinct `no-event` vs `lagged`
sentinel). Rewrite `ensure-listener-loop!` as a `.then`-recursive Promise chain
with no `js/setTimeout`. Ships: a reactive loop that (per Phase 0) delivers in
order with sub-ms latency and zero idle spin. Proves: the loop works without the
wstd-hang primitive — verified first on Node (Q6b), gated on wasm by Phase 4.

**Phase 2 — Self-healing catch-up by basis-t. ~1–2 days.**
On the `lagged` sentinel (and at subscribe), the overlay queries the gap range and
replays before resuming. Seed `last-seen-t` from current basis-t at subscribe.
Ships: no-silent-loss guarantee. Proves: the stream is at-least-once-effective
under burst, tested by Phase 0's lag case driving a guest-side replay.

**Phase 3 — Own-tx dedup + `:db-before` synthesis. ~1 day.**
Guest filters self-`request-id` events; overlay builds `:db-before` from
`basis-t-before`. Ships: V0 listen handlers run unchanged (incl. change-detection).
Proves: "complete" — API + semantics parity with V0 native listen.

**Phase 4 — End-to-end reactive in wasm (Q6c). ~1–2 days, gated on reproducible
build.** Build the guest component, run under wasmtime, drive a JVM commit, assert
the handler fires. This is the go/no-go for the whole reactive path under the real
runtime and the only test that catches the wasm-specific timer behavior. Depends
on the verification doc's reproducible-build resolution (core.async already
removed from eval-smoke; the guest bundle must likewise be core.async-free, which
it is — wire-only).

**Phase 5 (optional, perf) — `:memory`/async-ack writer for low write latency.
~1–2 days.** Only if an agent's sequential write-then-read latency proves to be a
bottleneck. Orthogonal to reactivity. Ships: single-digit-ms tx ack.

Total to "fast + complete + working + verified reactive": **~5–8 agent-days**
(Phases 0–4), with Phase 5 deferred until measured need.

---

## Appendix — Gemini consultation (verbatim)

Prompt: design review of the reactive eventing/IPC model (single QuickJS fiber,
no timers, WIT imports sync-to-JS, tokio broadcast cap 256, busy-poll). Response
preserved verbatim. **Note:** Gemini assumes async WIT imports surfacing a JS
Promise are available; on the current wasm-rquickjs 0.2.4 they are NOT
(`imports.rs:240-244` hard-errors), so the recommended pattern is realized via
host-side cooperative `block_on` (the wasi:http pattern) rather than a true async
import. The architectural conclusions (Promise-chain loop, lossy-stream +
basis-t catch-up, no host→guest push) hold regardless.

> # Design Review: Reactive DB Eventing & IPC for WASM Guests
>
> ## 1. Wasmtime Async Host Import Semantics & Fiber Suspension
>
> When compiling host imports with async support in Wasmtime (via
> `Config::async_support(true)`), Wasmtime runs the WebAssembly guest inside a
> **stackful coroutine (fiber)**.
>
> ### How Suspension Behaves
> * **At the Host Level (Tokio/Executor):** When the guest calls an `async` Rust
>   host function (e.g., one that awaits a Tokio channel receiver), Wasmtime
>   yields execution of the guest fiber back to the host's executor. The **host
>   thread is not blocked**, and the CPU usage of this WASM instance drops to
>   zero. The executor is free to run other tasks. When the underlying Rust
>   future resolves, Wasmtime swaps the guest fiber's stack back in, and
>   execution resumes.
> * **At the Guest Level (QuickJS/JS VM):** The guest WASM instance is **entirely
>   suspended**. To the QuickJS runtime, the imported function behaves like a
>   blocking, synchronous call. No JavaScript code, microtasks, or event loop
>   ticks can execute in this specific instance while the fiber is suspended.
>
> ### The Synchronous Loop Hazard
> If you implement a synchronous infinite loop in JavaScript/ClojureScript to
> poll a blocking host function:
> ```clojure
> ;; WARNING: Memory leak/microtask starvation hazard
> (defn poll-loop []
>   (let [event (next-tx-event)] ; blocks WASM fiber
>     (process-event event)
>     (recur)))
> ```
> The JavaScript call stack **never unwinds**. QuickJS executes the loop
> synchronously, blocking the fiber inside `next-tx-event`. As a result, the
> QuickJS **microtask queue is never drained**. Any native Promises,
> `queueMicrotask` calls, or ClojureScript callbacks resolved during
> `process-event` will accumulate in memory indefinitely, causing a slow memory
> leak and starving your reactive application.
>
> ### The Right Pattern: Promise-Based Pull
> The host-side WIT import should return a JavaScript `Promise` to the guest
> (implemented in Rust using `rquickjs::promise::wrap_future`).
>
> When the guest `await`s this Promise, the QuickJS execution stack unwinds
> completely, allowing the interpreter to drain all pending microtasks and enter
> an idle state. Only then does the host suspend the WASM fiber while awaiting the
> Tokio broadcast channel. When the event arrives, the host resolves the Promise,
> placing the callback in the QuickJS microtask queue, which is then drained by
> the runner's idle loop.
>
> ## 2. Wait-for-Event Loop Structure (No-Timer Constraint)
>
> Given that `setTimeout` and `wasi:clocks` timer-based parking are broken or hang
> under your current `wstd` and Wasmtime configuration, you must structure the
> loop to yield control via Promises rather than timers.
>
> ### Evaluation of Options
> * **Option A (Blocking sync call + microtask yield):** Highly discouraged. As
>   explained in (1), a synchronous blocking call prevents the execution stack
>   from unwinding, meaning you cannot cleanly yield to the microtask queue
>   without returning from the outer JS execution.
> * **Option B (Host-to-guest callbacks / Push model):** In the WASM Component
>   Model (WIT), re-entrancy (the host calling a guest export while the guest is
>   suspended inside a host import) is forbidden and will trap. If the guest has
>   returned and is idle, the host *can* call a guest export, but this makes
>   ClojureScript state management extremely messy (forcing you to store state in
>   global/atom registers) and turns your guest into a passive RPC target rather
>   than an active agent.
> * **Option C (Promise-based Pull model - RECOMMENDED):** Expose `next-tx-event`
>   as an async host function returning a JS `Promise` wrapped around a Rust
>   `Future` (via `rquickjs::promise::wrap_future`). In ClojureScript, this is
>   driven via tail-recursive promise chaining.
>
> This model is clean, works without timers, and guarantees that the microtask
> queue is fully flushed after processing every event.
>
> ## 3. Broadcast Lag, Bounded Buffers, and Durability
>
> Managing the "slow consumer" problem is critical when hosting multiple
> single-threaded agents in WASM sandboxes.
>
> ### The Backpressure Trap
> * **Blocking the Sender:** In a publish-subscribe architecture, blocking the
>   sender when a consumer is slow is a critical failure mode (head-of-line
>   blocking). One stalled guest will block the Rust host, which in turn blocks
>   the Unix socket to the JVM, halting all transactions system-wide.
> * **Unbounded Queues:** Providing a per-subscriber unbounded host-side queue is
>   a reliability hazard. If a guest agent hangs or slows down, the host queue
>   will grow indefinitely until the host runs out of memory (OOM).
>
> ### The Recommended Design: Ephemeral Bounded Notification + Durable Replay
> Treat the transaction event stream strictly as a **lossy, ephemeral wake-up
> signal**.
> 1. Maintain a small, bounded Tokio broadcast channel (e.g., capacity 256) on
>    the host.
> 2. If a guest lags, the host immediately returns a `Lagged(n)` error.
> 3. The guest resolves this error by executing a **durable catch-up query**
>    against the database.
>
> Because ClojureScript/Datahike databases use a monotonically increasing
> transaction ID (the basis transaction, or `basis-t`), the guest can track its
> last processed transaction (`last-seen-t`). If it lags, it queries the database
> for all transaction reports in the range `(last-seen-t, current-db-basis-t]`,
> processes them sequentially to catch up, updates its offset, and resumes
> listening to the event stream.
>
> This model is resilient, memory-safe, and self-healing.
>
> ## 4. The Self-Healing Catch-Up Pattern
>
> Below is the concrete, tail-recursive ClojureScript pattern implementing this
> self-healing event loop. It relies entirely on native JS Promises (bypassing
> `core.async` and `setTimeout`) and handles startup, normal processing,
> timeouts, and lag recovery identically.
>
> ```clojure
> (ns agent.db.reactive
>   (:require [promesa.core :as p]))
>
> (defn process-tx-event! [db-conn event]
>   (let [tx-t (.-t event)]
>     ;; 1. Apply transaction assertions to local state
>     ;; 2. Run local reactive callbacks / view updates
>     ;; 3. Update the tracking atom
>     (swap! db-conn assoc :last-seen-t tx-t)))
>
> (defn catch-up! [db-conn host-api last-t]
>   (-> (.getDatabaseBasisT host-api)
>       (p/then (fn [current-t]
>                 (if (> current-t last-t)
>                   ;; Query the transaction log range asynchronously
>                   (-> (.getTxRange host-api last-t current-t)
>                       (p/then (fn [tx-reports]
>                                 (doseq [report tx-reports]
>                                   (process-tx-event! db-conn report)))))
>                   (p/resolved nil))))))
>
> (defn run-event-loop! [db-conn host-api]
>   (let [last-t (:last-seen-t @db-conn)]
>     (-> (.nextTxEvent host-api) ; Returns a JS Promise wrapping the host Future
>         (p/then (fn [event]
>                   (cond
>                     ;; Normal Case: An event was received
>                     (some? event)
>                     (do (process-tx-event! db-conn event)
>                         ;; Tail-recursive promise chain unwinds stack
>                         (run-event-loop! db-conn host-api))
>
>                     ;; Timeout Case: Host returned nil (e.g. 100ms timeout expired with no events)
>                     ;; Yields control back to the event loop, then retries
>                     :else
>                     (run-event-loop! db-conn host-api))))
>         (p/catch (fn [err]
>                    (let [err-type (.-type err)]
>                      (cond
>                        ;; Lagged Case: Bounded ring buffer overflowed
>                        (= err-type "lagged")
>                        (-> (catch-up! db-conn host-api last-t)
>                            (p/then (fn []
>                                      (run-event-loop! db-conn host-api))))
>
>                        ;; Connection Closed Case: DB disconnect or host teardown
>                        (= err-type "closed")
>                        (println "Event loop terminated: connection closed by host.")
>
>                        ;; Fatal Case: Unexpected system error
>                        :else
>                        (do (println "Fatal error in event loop:" err)
>                            (throw err)))))))))
> ```
>
> ### Why This Works Under Your Constraints
> 1. **Unwinds the Stack:** Because `run-event-loop!` returns immediately after
>    chaining the Promise handlers (`p/then` and `p/catch`), the JavaScript call
>    stack is fully cleared at each iteration. This allows QuickJS to run the
>    microtask checkpoint and garbage collect intermediate objects.
> 2. **Zero CPU Idle:** If there are no transaction events, the host-side future
>    blocks on the Tokio channel. The Wasm fiber is suspended, consuming zero CPU
>    cycles.
> 3. **Periodic Check-Ins:** The host implementation of `.nextTxEvent` should
>    include a timeout (e.g., 100ms or 500ms) that resolves to `nil` rather than
>    blocking forever. This allows the guest to periodically wake up, confirm that
>    the host is still alive, and check for runtime cancellations.
> 4. **Unified Recovery Path:** On cold boot, you simply set `last-seen-t` to the
>    current database state (or your cached offset) and run the loop. If the
>    network drops or the buffer overflows, the loop hits the exact same
>    `catch-up!` path.

### Reconciliation note (author, not Gemini)

Gemini's Option C (async import → JS Promise via `wrap_future`) is the *ideal*
but is blocked on wasm-rquickjs 0.2.4 (`"Async imported functions are not
supported yet"`). The realizable equivalent on this toolchain is the wasi:http
pattern: a synchronous-to-JS WIT import whose host body runs `wstd::block_on`
over the bounded `recv()` future, cooperating with the QuickJS microtask loop so
the fiber yields and resumes. The guest-side `.then`-recursive loop, the
lossy-stream + basis-t catch-up, and the rejection of host→guest push all apply
unchanged. If/when wasm-rquickjs gains async imports (or the host adds a
two-call `subscribe`/`await` resource), the loop body is unchanged — only the
import's JS-visible signature becomes a real Promise instead of a
cooperative-block.
