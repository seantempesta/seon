---
type: research
status: draft
tags: [research, agent, cljs]
---

# Porting `clojure.core.async.flow` to ClojureScript — feasibility

**Date:** 2026-05-25
**Branch:** `feature/agent-runtime`
**Substrate:** V0 CLJS pod (Node now; `wasm32-wasip2` later via wasm-rquickjs inside wasmtime/Tauri)
**Submodule pin:** `reference-code/core.async` @ `b871f35` (2026-01-12), flow added in `03b97e0` ("added core.async.flow")
**Flow LOC inventoried:** `flow.clj` 341, `flow/impl.clj` 320, `flow/spi.clj` 95, `flow/impl/graph.clj` 28 — total **784 lines** of flow code, all JVM-only as shipped.

## TL;DR

**Recommendation: Option B — port flow's design to CLJS with primitive substitution.**
Replace the proc loop's `alts!!` with a Promise-based pull loop in `^:async` fns; reuse CLJS `chan` / `alts!` / `mult` (which already work, no go-block required for `put!`/`take!`/`offer!`/`poll!`); replace `ExecutorService` workload tiers with a no-op tier (single-threaded JS event loop) plus an explicit `:compute` yield helper. **Estimated effort: ~5–8 agent-days for v0.1** producing a single-file `seon.flow.cljs` covering `create-flow`/`start`/`stop`/`pause`/`resume`/`ping`/`inject`/`process` (the API actually used in Seon today). All four 784 LOC are well-factored, no JVM concurrency cleverness, no `tools.analyzer.jvm` dependency (the analyzer dep is for the IOC machinery in core.async proper, not flow).

Option A ("port as-is") is blocked: flow's main proc loop is an unrelenting `loop`+`alts!!`+`>!!` machine with no asynchronous variant. Even if go-blocks worked perfectly under wstd, the loop runs in a `Thread`/`ExecutorService` it owns — there is no thread to own in CLJS.

Option C (use missionary) is viable for a greenfield runtime but loses our spec-01 `topology/request!` design alignment with the JVM side.

Option D (don't port; use `d/listen!` + dispatcher) remains tempting for the single-agent loop, but **multi-agent + cross-process IPC + pause/resume + observability via ping/datafy are exactly what flow gives you for free**, and re-deriving those in `d/listen!` land is ≥ the cost of porting flow.

---

## 1. core.async.flow API surface — what we'd actually need

Reading `flow.clj` end-to-end, the **user-facing** public surface is small:

| Fn | Purpose | LOC in `flow.clj` |
|---|---|---|
| `create-flow` | take `{:procs :conns}` map, return graph object | 30 |
| `start` / `stop` | lifecycle | 4 / 4 |
| `pause` / `resume` | flip all procs | 2 / 2 |
| `ping` / `ping-proc` | datafy live state | 4 / 4 |
| `pause-proc` / `resume-proc` | per-proc | 2 / 2 |
| `inject` | shove msgs into any in/out channel; supports `[::flow/cast sig-id]` | 5 |
| `process` | wrap a 4-arity step-fn into a `ProcLauncher` | 4 |
| `map->step` / `lift*->step` / `lift1->step` | step-fn convenience builders | trivial — pure data, port verbatim |
| `futurize` | wrap fn → returns Future (workload-tiered) | 4 |

That's the whole API. There is **one protocol the impl depends on internally** — `spi/ProcLauncher` (`describe`, `start`) plus `spi/Resolver` (`get-write-chan`, `get-exec`). 95 LOC, **no JVM imports**, ports as-is to `.cljc`.

### Concepts that survive translation (data + protocol, no platform deps)

- The flow config map shape `{:procs {pid {:proc ... :args ... :chan-opts ...}} :conns [[[from-pid outid] [to-pid inid]] ...]}` is pure data.
- The 4-arity step-fn contract (describe / init / transition / transform) is just a function; nothing JVM about it.
- Control message envelopes (`#::flow{:command ::flow/ping :to ::flow/all :reply-chan c}`) are maps.
- Status states `:paused | :running | :exit` and transitions `::flow/pause | ::flow/resume | ::flow/stop` are keywords.
- `inject` returning a Future translates to: returning a Promise. Trivial.
- `cast` (broadcast to signal-selecting procs) is pure channel routing on already-built `castees` map.

### Concepts that need platform substitution

- `ExecutorService` for workload tiers (`:mixed`, `:io`, `:compute`) — see §4.
- `ReentrantLock` around start/stop — replace with atom CAS or a Promise serialization queue (Node is single-threaded; the lock only matters to prevent concurrent `start`/`stop` calls overlapping, which in JS only happens if both are awaited concurrently).
- `Future` returned by `inject` and `futurize` — replace with `js/Promise`.
- `(.get ^Future ... timeout TimeUnit/MILLISECONDS)` for `:compute` workload — replace with `Promise.race([f, timeout])`.
- The blocking `alts!!`/`>!!` calls in the proc loop — see §4.

### Hard blockers

- **None at the design level.** Flow does not depend on JVM-specific guarantees (thread monitors, intrinsic locks, `Var` bindings across thread boundaries, `volatile!` semantics, finalization). It does depend on **"there exists a thread that can block"**, which CLJS doesn't have — but this is solvable by replacing the blocking loop with an async pull loop. See §4 detailed walkthrough.

---

## 2. Is core.async kept in sync with latest CLJS?

**Mostly yes, with one important caveat.**

Current state from the submodule:
- Latest core.async commit on master: `b871f35` (2026-01-12), version still `1.9.GENERATED_VERSION-alpha2` per `VERSION_TEMPLATE`. Recent tag `v1.9.829-alpha2` (commit `54e4e86`).
- The `:cljs-test` alias in `deps.edn` pins **ClojureScript 1.11.132** — i.e. **pre-native-`^:async`**. CLJS 1.12.145's native async support is NOT exercised by core.async's test suite.
- Last meaningful change to `cljs/core/async.cljs`: `bcd35a6` (2025-01-21) — a 2-line guard against nil messages in alts. Before that, the last meaningful CLJS change was 2022. **The CLJS surface has been in maintenance mode for ~4 years.**
- Flow was added `03b97e0` (~Jan 2025 per the doc by Hickey). **Flow is JVM-only** — the directory `src/main/clojure/clojure/core/async/flow/` has no CLJS sibling, no `.cljc` files. No PRs or commits in the repo's history mention CLJS flow.

What still works in CLJS as-is:
- `chan`, `put!`, `take!`, `offer!`, `poll!`, `close!`, `mult`, `tap`, `pub`, `sub`, `mix`, `pipe`, `pipeline-async`, `promise-chan`, `timeout` — all the channel primitives and their async (callback-style) operations.
- `alts!` (parking variant — only inside `go`).
- The `go` macro and its IOC state machine, which uses the same `cljs.core.async.impl.dispatch/run` scheduler.

What does NOT exist in CLJS:
- `>!!`, `<!!`, `alts!!` — the blocking variants. **Flow's proc loop uses `alts!!` 4 times and `>!!` 8 times.** This is the single biggest porting question.
- `Thread`, `ExecutorService`, `Future`, `ReentrantLock` — JVM concurrency primitives flow imports.

### Critical clarification on the "go-blocks hang under wstd" claim

`MEMORY.md` and `agent-loop-pattern-survey-2026-05-25.md:61` state core.async go-blocks hang under wstd because they park on `setTimeout`. **This is half-true, and the half that matters here is different.**

Read `cljs/core/async/impl/dispatch.cljs` (the whole file is 45 lines):

```clojure
(defn queue-dispatcher []
  (when-not (and queued? running?)
    (set! queued? true)
    (goog.async.nextTick process-messages)))

(defn run [f]
  (.unbounded-unshift tasks f)
  (queue-dispatcher))

(defn queue-delay [f delay]
  (js/setTimeout f delay))

```

The **main scheduler** (`run`, used by every `put!`/`take!`/`go`) uses `goog.async.nextTick`, which under Node compiles to `process.nextTick` → a **microtask**, not a macrotask. Microtasks pump on any I/O return, and wasmtime/wstd executes them at the same drain points it executes Promise `.then` callbacks. **There is no setTimeout dependency in the main path.**

`queue-delay` IS setTimeout-based, but it's used in exactly one place: `timers.cljs:175` (the `timeout` channel implementation). So core.async-in-CLJS **only hangs under wstd if you use `(async/timeout ms)`**. That's significant for flow because:

- `flow/impl.clj` uses `(async/timeout timeout-ms)` exactly once — in `handle-ping` (line 77). Ping replies have a 1000ms default timeout.
- The proc main loop does **not** use timeouts.
- The `:compute` workload uses `.get future timeout-ms TimeUnit/MILLISECONDS` — but that's JVM-only Future code we're replacing with Promise.race anyway.

So the "wstd is incompatible with core.async" framing is overstated. **The actual constraint is: don't use `(async/timeout)` under wstd; use `Promise.race([p, hostTimeoutCapability(...)])` instead.** Even that becomes a non-issue once the wasmtime epoch-interruption work lands (see `multi-runtime-architecture-2026-05-24.md:310`) because the host can provide a real timer capability via WIT.

A pre-existing patch path: if we ever need `(async/timeout)` in the pod, replacing `queue-delay` to use a host-provided sleep capability is a 1-line change.

---

## 3. What's already in CLJS for core.async — implementation walkthrough

Files in `src/main/clojure/cljs/core/async/`:

```
async.cljs               953 lines  — public API, all the channel ops, mult/pub/mix, pipelines
impl/buffers.cljs                   — RingBuffer, FixedBuffer, DroppingBuffer, SlidingBuffer
impl/channels.cljs       195 lines  — ManyToManyChannel, the workhorse
impl/dispatch.cljs        45 lines  — the scheduler (above)
impl/ioc_helpers.cljs               — state machine runtime
impl/ioc_macros.clj                 — compile-time IOC macro (in .clj because macros)
impl/protocols.cljs                 — Channel, Buffer, ReadPort, WritePort, etc.
impl/timers.cljs         180 lines  — skiplist-backed timeout channels
async.clj                           — `go`, `go-loop`, `alt!` macros
macros.clj                          — old `cljs.core.async.macros` shim
interop.{clj,cljs}                  — `<p!` for awaiting Promises inside `go`

```

The CLJS `go` macro (in `cljs/core/async.clj:12`) expands to:

```clojure
(defmacro go [& body]
  `(let [c# (cljs.core.async/chan 1)]
     (cljs.core.async.impl.dispatch/run
      (fn []
        (let [f# ~(ioc/state-machine body 1 &env ioc/async-custom-terminators)
              state# (-> (f#)
                         (ioc/aset-all! ...impl.ioc-helpers/USER-START-IDX c#))]
          (cljs.core.async.impl.ioc-helpers/run-state-machine-wrapped state#))))
     c#))

```

**It does NOT use native `^:async`.** It uses the same IOC state machine as the JVM, scheduled via `dispatch/run` → `goog.async.nextTick`. This works today in Node and would work under wasmtime-wstd modulo the timeout caveat above.

**Has anyone hooked it to native `^:async` / `queueMicrotask`?** No PRs in the repo. The dispatcher already uses microtasks via `goog.async.nextTick`, so the value-add of a `queueMicrotask` rewrite is small (perf, not correctness). Native `^:async` would be a different rewrite of the *user-level* go-block, but the CLJS authors have not moved in that direction publicly.

**Direct quote (`cljs/core/async.clj:13–21`):**

> "Asynchronously executes the body, returning immediately to the calling thread. Additionally, any visible calls to <!, >! and alt!/alts! channel operations within the body will block (if necessary) by 'parking' the calling thread rather than tying up an OS thread (or the only JS thread when in ClojureScript)."

The intent is preserved in CLJS. The mechanism (IOC state machine) is preserved. Only the *underlying scheduler* differs — and the CLJS scheduler is microtask-based, which is exactly what we want under wstd.

---

## 4. Flow's design — categorized for porting

Reading `flow/impl.clj` line by line, every JVM dependency and its replacement:

### (a) Pure data / protocol — translates as-is via `.cljc`

- `prep-proc` (lines 36–46): pure validation/zipmap. Port as-is.
- `create-flow`'s channel-wiring logic (lines 100–139): builds `in-chans`, `out-chans`, `castees`, sets up `mult`s. **Uses only `async/chan`, `async/mult`, `async/tap`, `async/sliding-buffer`, all of which exist in CLJS.** Port as-is.
- `send-outputs` (lines 217–239): pure routing logic, but uses `alts!!` — see (b).
- The `Datafiable` extension: trivial swap for `IPrintWithWriter` or our own `seon.render` discoverable shape.
- `spi/ProcLauncher` and `spi/Resolver` protocols (`spi.clj`): port verbatim.
- `flow/impl/graph.clj` (28 lines): defines `Graph` protocol — port verbatim.
- `flow.clj` top-level public API: thin wrappers over `impl/graph` — port verbatim.

### (b) JVM-only mechanisms requiring substitution

| Location | JVM mechanism | CLJS substitute |
|---|---|---|
| `impl.clj:51` | `ReentrantLock` around start/stop | Atom CAS `(compare-and-set! state :stopped :starting)` — Node is single-threaded; the lock prevents *logical* re-entry, not OS-thread races |
| `impl.clj:72,162,194` | `(async/>!! control cmap)` outside a go-block | `(async/put! control cmap)` — fire-and-forget; control chan should have ample buffer (it does: `async/chan 10`) |
| `impl.clj:80` | `(async/alts!! [ret-chan timeout])` in `handle-ping` | `(js-await (alts-promise [ret-chan timeout]))` — see helper below |
| `impl.clj:230` | `(async/alts!! [control [outc msg]] :priority true)` in `send-outputs` | Rewrite `send-outputs` as `^:async` returning Promise; use `(<p! (alts-promise ...))` |
| `impl.clj:281,292` | Proc main `loop` with `<!!` and `alts!!` | **Rewrite as `^:async` JS-async-fn**, with `^:async-recur`-style trampoline — see §4.3 |
| `impl.clj:29–34` | `futurize` via `ExecutorService.submit` | Return `js/Promise` immediately, run fn synchronously inside (single-threaded), or `await` if `^:async` |
| `impl.clj:256–258` | `:compute` workload `.get future timeoutMs` | `Promise.race([fnPromise, sleep(timeoutMs)])`; on timeout, surface error envelope. No real preemption (matches the wstd reality — only the wasmtime epoch can preempt) |
| `impl.clj:53` | `(every? (instance? ExecutorService %) (vals execs))` | Drop the workload tier types; workload becomes a *hint* only, since there's only one thread |

### (c) Hard blockers

**None** — every JVM-ism above has a direct CLJS analog that preserves the semantics flow's users care about (FIFO ordering on control, priority of control over data, lifecycle transitions, error propagation to `::flow/error`).

### 4.3 The proc loop — the core porting question

The JVM proc loop (`impl.clj:268–319`) is one big `(loop [status :paused, state state, count 0, read-ins read-ins] ...)` that:

1. Reads control + data channels via `alts!!`.
2. Dispatches to `step` (the user transform).
3. Routes outputs via `send-outputs` (which itself loops on `alts!!`).
4. On status `:exit`, falls out of the loop.

Translation to CLJS as `^:async`:

```clojure
(defn ^:async run-proc-loop [pid step transform exs control casts read-ins outs ...]
  (loop [status :paused, state state, count 0, read-ins read-ins]
    (let [[nstatus nstate ncount nread-ins]
          (if (= status :paused)
            (let [msg (await (chan->promise control))
                  nstatus (handle-command status msg)
                  nstate (handle-transition step status nstatus state)]
              [nstatus nstate count read-ins])
            (let [read-chans (build-read-chans state control casts read-ins)
                  [msg c]    (await (alts->promise read-chans {:priority true}))
                  cid        (io-id c)]
              (if (= c control)
                ...
                (let [[nstate outputs] (try-or-error transform state cid msg)
                      [nstatus nstate] (await (send-outputs-async ...))]
                  [nstatus nstate (inc count) ...]))))]
      (when-not (= nstatus :exit)
        (recur nstatus nstate ncount nread-ins)))))

```

This works because:
- CLJS 1.12.145 supports `^:async` on `defn` and named `fn`, and `await` is a function call inside such fns.
- `loop`/`recur` work inside `^:async` fns (CLJS `^:async` compiles to a JS `async function`; `loop` becomes a JS `while(true)` with a `recur` rebinding — verified in `agent.cljs:663-706` `run-agentic-loop!`, the canonical existing pattern).
- `chan->promise` and `alts->promise` are 10-line helpers that use `take!` and `do-alts` (both exist in CLJS) with a Promise resolver as the callback.
- Each `await` yields to the microtask queue, allowing other procs to run. Perfect cooperative multitasking on the single JS thread.

This is the **same shape** as Seon's existing agent loop in `agent.cljs:663` (`run-agentic-loop!`). Pattern is proven; we'd be applying it generically.

### Effort breakdown for Option B (port-with-substitution)

| Task | Days |
|---|---|
| Port `spi.clj`, `flow/impl/graph.clj`, `flow.clj` public API as `.cljc` | 0.5 |
| Write `chan->promise`, `alts->promise`, `put-promise` helpers (~30 LOC) | 0.5 |
| Rewrite `impl/futurize` for JS (Promise-returning, workload-as-hint) | 0.25 |
| Rewrite `impl/create-flow`'s channel wiring + lock as atom CAS | 0.5 |
| Rewrite proc main loop as `^:async run-proc-loop` | 1.5 |
| Rewrite `send-outputs` as `^:async send-outputs-async` | 0.75 |
| Rewrite `handle-ping` as `^:async`, return Promise | 0.25 |
| Port `cast`, `inject` (using `put!` callbacks + Promise resolvers) | 0.5 |
| Tests: port flow test suite from `test/clojure/clojure/core/async/flow/` to CLJS, run under shadow-cljs against Node | 1.5 |
| Smoke test under `wasmtime` CLI with wasm-rquickjs (the Phase 3 target) | 1.0 |
| Documentation in `docs/seon/concepts/cljs-flow.md` | 0.25 |
| **Total** | **~7.5 agent-days** |

This assumes one agent owns the work end-to-end. Cuts in half if we **only** port the surface Seon actually uses (no `:compute` workload, no `inject ::flow/cast`, no `futurize` as a public fn).

---

## 5. Alternatives — what already exists in CLJS-land

### missionary (https://github.com/leonoel/missionary)

Active, CLJ+CLJS+JS, written by Leonardo Borges. CLJS support is first-class and maintained.

**Model:** Two primitives — `Task` (one-shot async value, like a lazy Promise) and `Flow` (continuous reactive sequence, like an RxJS Observable). All operators are functions; no macros. Cancellation is propagated through every task automatically. Has its own scheduler.

**Concrete API (~20 ops):** `m/?`, `m/sp`, `m/ap`, `m/?>`, `m/?<`, `m/zip`, `m/race`, `m/sleep`, `m/timeout`, `m/observe`, `m/relieve`, `m/reductions`, `m/buffer`, `m/eduction`, etc.

**Maps onto our needs:**
- Agent main loop = an `m/sp` (sequential process) that awaits DB tx events via `m/observe` wrapping `d/listen!`.
- Process lifecycle = task cancellation (cancel the `m/?` of the loop; missionary unwinds cleanly).
- Pause/resume = a control `m/mbx` (mailbox); the loop `m/?>`'s on it.
- Backpressure = built-in (`m/relieve`, `m/buffer`).
- Multi-process = multiple `m/sp` running concurrently, coordinated via mailboxes.

**What we lose vs flow:**
- The declarative `{:procs ... :conns ...}` topology data structure. We'd hand-wire processes.
- The `ping`/`datafy` introspection contract — we'd build our own observability.
- The `inject` admin operation — missionary tasks aren't named/addressable.
- Symmetry with the JVM side of Seon, which is heavily flow-invested already (`seon.db/datahike/flow`, `seon.web/sse/flow`, `seon.db/datahike/tx_bus`, `seon.system`).

**Survey verdict:** missionary is what you'd reach for if you were starting cold. Given that we're not, the symmetry argument dominates.

### promesa

Mature, widely used, CLJ+CLJS. **Stays at the Promise/Future layer** — does not provide higher-level process topology. We'd use it as a building block (it's what our `await` helpers already kind of duplicate) but not as a flow replacement.

### Hyperfiddle Electric

DAG-of-reactive-views. Too far from "long-lived state machine process". Useful for the agent's *UI* projection, not its runtime.

### funcool/cats

Monadic effect plumbing. Adds vocabulary without removing concurrency complexity. Skip.

### JS-side: RxJS, xstate

- **xstate** is interesting: it models statecharts (`:paused`/`:running` is literally an xstate machine). But it has no concept of inter-process channels, no datafy, no Clojure idiom, and pulling in a JS dependency we don't need is a step backwards.
- **RxJS** = missionary's Flow with more ceremony. Worse than missionary for our purposes.

---

## 6. Recommendation

**Option B: port flow's design to CLJS with primitive substitution.**

### Why

1. **Symmetry with the JVM side.** Seon already uses flow as the topology backbone on the JVM (`seon.db/datahike/flow`, `seon.web/sse/flow`, `seon.system`'s flow components, `seon.db/datahike/tx_bus`). If the V0 pod uses a *different* substrate, every cross-process abstraction we want to share — including the spec-01 `topology/request!` model — needs two implementations and a bridge. One substrate, two backends (one with real threads, one cooperative) gives us a `.cljc` future.
2. **The hard blocker is mythical.** The "core.async hangs under wstd" claim turns out to be specifically about `(async/timeout)` and the workload-tiered `ExecutorService`-based proc loop. Both are surgical to replace, and the second one we'd replace *anyway* because flow's proc loop has no async variant.
3. **Pattern is already proven in Seon.** `agent.cljs:663` `run-agentic-loop!` is a working example of the exact `^:async loop`/`await`/`recur` shape the proc loop needs. We're not inventing.
4. **Effort is bounded.** 784 LOC of flow, ~250 LOC of which is the proc loop. v0.1 ships in a week of agent-time; v0.2 (handle `:compute` workload via host-provided thread/Worker capability) ships later if needed.

### What v0.1 ships

- `src/seon/flow.cljc` — public API mirroring `clojure.core.async.flow` exactly (`create-flow`, `start`, `stop`, `pause`, `resume`, `ping`, `inject`, `process`, `map->step`, `lift1->step`).
- `src/seon/flow/impl.cljs` — CLJS-only proc impl using `^:async` loop.
- `src/seon/flow/impl.clj` — JVM-only impl that just delegates to `clojure.core.async.flow/*` (zero-cost passthrough).
- One workload tier (`:mixed`), with `:compute` accepted but mapped to `:mixed` with a warning.
- No `(async/timeout)` usage anywhere in the impl — ping deadline uses `js/setTimeout`/host timer capability directly.
- Tests adapted from `test/clojure/clojure/core/async/flow/` running under shadow-cljs+Node.

### What v0.1 does NOT ship

- `:compute` workload with real preemption (waits for wasmtime epoch interruption — `multi-runtime-architecture-2026-05-24.md:310`).
- The `out-ports`/`in-ports` external-channel facility (we don't use it yet).
- Worker-thread offload (one JS thread is fine until profiling says otherwise).

### Standing back: is this the right time?

Two existing research deliverables push back on adopting flow now:

- `agent-loop-pattern-survey-2026-05-25.md:259`: "Wake mechanism: tx-listener → per-agent dispatcher → conditional `run-agentic-loop!`. NOT a wake-queue table, NOT a channel block." This recommends Option D for the single-agent wake path.
- `turn-as-unit-2026-05-25.md:29`: the current `^:async run-agentic-loop!` already does the long-running-state-machine job.

These are correct for the *single-agent wake-on-DB-tx* problem. Flow's value-add is not the wake mechanism — it's:
- **Multi-process topology data** (declarative `:procs`/`:conns`, one place to see the whole graph).
- **Centralized lifecycle** (pause all of them, resume one).
- **Free observability** (`(flow/ping g)` returns a live snapshot of every process).
- **Cross-process channels with priority + backpressure** (the `[control [outc msg]] :priority true` pattern).

If you only ever have one agent with one loop, you don't need flow. As soon as you have **multiple agents that must coordinate** or **the pod hosts both the agent loop and the SSE broadcaster and the DB tx-bus**, the case strengthens. The PRD is `feature/agent-runtime`, so the trajectory is multi-agent. The right time to port flow is *before* the second long-lived process lands, not *after*.

### What to do this week

1. Get sign-off on Option B.
2. One agent ports the surface incrementally: spi + graph + create-flow first, then proc-loop, then ping/inject, with REPL smoke tests at each step.
3. Convert `agent.cljs`'s `run-agentic-loop!` to a flow process as the first real consumer. The behavior must be byte-identical; if it isn't, the port is wrong.
4. Validate under wasmtime CLI before merging.

---

## Appendix: file path index

Flow source (read for this report):
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj`
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj`
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl/graph.clj`
- `reference-code/core.async/doc/flow.md`

CLJS core.async source (read for this report):
- `reference-code/core.async/src/main/clojure/cljs/core/async.cljs`
- `reference-code/core.async/src/main/clojure/cljs/core/async.clj` (macros)
- `reference-code/core.async/src/main/clojure/cljs/core/async/impl/dispatch.cljs`
- `reference-code/core.async/src/main/clojure/cljs/core/async/impl/timers.cljs`
- `reference-code/core.async/src/main/clojure/cljs/core/async/impl/channels.cljs`

Seon CLJS pod (referenced for canonical patterns):
- `src/seon/agent.cljs` (`run-agentic-loop!` at line 663 — proof of `^:async loop`/`await`/`recur`)
- `src/seon/db.cljs` (`^:async transact!` at line 801; AsyncLocalStorage context propagation pattern)

Seon JVM flow consumers (the symmetry argument):
- `src/seon/db/datahike/flow.clj`
- `src/seon/db/datahike/tx_bus.clj`
- `src/seon/web/sse/flow.clj`
- `src/seon/system.clj`
- `src/seon/runtime.clj`
- `src/seon/repl.clj`

Prior research deliverables consulted:
- `docs/prds/agent-runtime/research/agent-loop-pattern-survey-2026-05-25.md`
- `docs/prds/agent-runtime/research/turn-as-unit-2026-05-25.md`
- `docs/prds/agent-runtime/research/multi-runtime-architecture-2026-05-24.md`
- `docs/prds/agent-runtime/research/impl-finding-tx-context-promise-2026-05-22.md`
- `docs/prds/agent-runtime/research/wasm-spike-2026-05-20.md`
