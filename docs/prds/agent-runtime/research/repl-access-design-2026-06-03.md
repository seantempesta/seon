---
type: research
status: draft
tags: [research, agent, database, flow]
---

# REPL access design — JVM DB-server + wasm-sandboxed agents (2026-06-03)

Authoritative expansion of the "REPL access" cross-cutting section in
[[clusters-and-multi-db-wiring-2026-06-03]]. Designs live REPL access into the
two runtime surfaces of a V2 **cluster** (one datahike DB + N wasm-sandboxed
CLJS agents): (1) the JVM "DB clj server" / wire-server, and (2) the wasm
agents. Grounded in the actual code (`client-runtime/host/src/{guest,main}.rs`,
`client-runtime/host/wit/db.wit`, `guest-cljs/src/seon/client_runtime/*.cljs`,
`src/seon/server/{wire,session,broadcast}.clj`) and one Wasmtime-44 Gemini
consultation (verbatim in the appendix).

## TL;DR

- **The spine:** *a diagnostic tool must not share a failure domain with the
  thing it diagnoses.* The guest is a **single QuickJS fiber** — confirmed:
  the agent's whole life runs inside ONE export call (`run-agent`), and the
  listener loop is a detached Promise chain on the *same* fiber's microtask
  queue. So **any** in-guest REPL (whatever channel it rides) shares fate with
  whatever that fiber is doing. Independence MUST come from outside the fiber.
- **The real diagnostic plane is the DB + eval log, queried from the JVM REPL —
  a different OS process from every guest.** Every agent eval is already
  persisted as a `:seon.eval` entity (`src/seon/eval.cljs`), tagged with
  `:seon.db/origin` (enum already includes `:user :agent :system :replay
  :test-run`). A human at the JVM REPL inspects "what did agent X do / is doing"
  by querying datoms, *never touching the possibly-wedged guest*. This answers
  "how do we know it's stuck" — add a per-turn **heartbeat datom** so "stuck" is
  a query, not a guess.
- **P1 (do now, small):** a localhost-bound, flag-gated **socket-REPL** (not
  nREPL) inside the wire-server. One REPL reaches every cluster via
  `seon.server.session/!registry` (`{db-name → conn}`). This is the
  always-available external diagnostic and it unblocks REPL-driven dev of
  everything else. Caveat surfaced: the `:writer` alias the host shells out to
  (`clojure -M:writer`) **does not currently exist in `deps.edn`** — it was
  folded out (see verification-jvm-wireserver-itemE). The REPL flag lands
  wherever that alias / in-process `wire/start!` is re-introduced.
- **In-guest agent REPL = convenience, not last-resort diagnostic.** It must be
  a message kind on the **same host→guest wake channel** the reactive track owns
  (`next-tx-event` → a generalized `next-control-event`), because the
  single-export, single-fiber, no-timer, re-entrancy-forbidden reality leaves no
  other clean seam. A dedicated second export is impossible while the agent is
  busy (re-entrancy traps — confirmed by Gemini Q5).
- **Detect + fix a stuck agent from OUTSIDE the fiber (the heart of the worry):**
  two wedge types, two mechanisms, both host-side and both confirmed:
  - **Wedge A — infinite pure-JS loop (e.g. the measured 121s regex):**
    **wasmtime epoch interruption** (`Config::epoch_interruption(true)` +
    `Store::set_epoch_deadline` + a background task calling
    `Engine::increment_epoch()`). Traps out of `call_run_agent(...).await` as an
    `Err`. Use `epoch_deadline_async_yield_and_update` so a tight-loop guest also
    *yields* and can't starve siblings (they're co-scheduled on one tokio task —
    see Risk note).
  - **Wedge B — suspended inside a host import that never returns:** epoch does
    **nothing** (wasm isn't executing). The host owns that future, so the fix is
    **`tokio::time::timeout` on every blocking import** (the blocking
    `next-control-event` recv especially).
  - **Recovery:** the host already does the right thing — wrap the export in
    `tokio::time::timeout(bound, …)` and on expiry **`drop` the `Store`**.
    Gemini confirms dropping a Store whose fiber is suspended mid-host-call is
    **sound** in wasmtime 44, reclaims the fiber stack, and **does not affect
    sibling agents** (separate Stores; dropping a `broadcast::Receiver` only
    deregisters it). Because agent state is durable in the DB, restart resumes.
- **Provenance:** route human REPL forms through the agent's normal eval path so
  they become `:seon.eval` datoms tagged `:seon.db/origin :user` — human
  interaction lands in the same reactive substrate + training log as LLM evals.
  Worth doing; nearly free (the enum value already exists).

---

## 0. The single load-bearing requirement

> "The REPL is usually an EXTERNAL tool we use to DIAGNOSE issues. If the
> multiplexed path gets stuck, how do we know and fix it?"

Reframed as a design rule: **the diagnostic plane and the data/agent plane must
have independent failure domains.** A floated proposal multiplexes REPL-eval
onto the host→guest wake channel (the cooperative-blocking `next-tx-event`).
The worry is correct but the multiplex-vs-dedicated-channel question is a *red
herring* for the diagnostic requirement, because of a deeper fact:

**The guest is one QuickJS fiber.** Verified in code:

- The agent's entire lifetime is inside a single export: `run-agent` →
  `run-agent!` (`agent.cljs:408`) runs an unbounded `loop/recur` with `await`s.
  The host calls it once via `call_run_agent(&mut store, …).await`
  (`guest.rs:629`). There is no second entry point that runs concurrently.
- `listen!`'s fan-out loop (`db.cljs:225 ensure-listener-loop!`) is **not** a
  separate thread — it's a `.then`-recursive `js/Promise` chain scheduled on the
  same fiber's microtask queue. It interleaves with the agent loop only when the
  agent `await`s and unwinds the JS stack.

Consequence: if that fiber wedges — a tight JS loop, or a host import that never
returns — *everything* in the guest dies with it, **including any in-guest REPL,
regardless of which channel the REPL uses.** A "dedicated REPL channel" gives
zero extra independence because there is no second executor to service it. So:

- The **primary** diagnostic must live in a *different* process/fiber. That is
  the JVM wire-server REPL + the DB/eval-log as a telemetry plane (§1, §2).
- The **in-guest** REPL is a convenience for interactive agent dev when the
  agent is healthy; it is explicitly NOT the tool you reach for when the agent
  is wedged (§3).
- "Wedged" detection and forced recovery come from the **host**, outside the
  fiber, via epoch interruption + import timeouts + Store teardown (§4).

---

## 1. JVM / DB-server REPL — the easy, do-first half (P1)

### 1.1 What the wire-server is today

`src/seon/server/wire.clj` is a `-gen-class -main`: it opens a datahike conn,
binds a req UDS socket + a pub UDS socket, and parks on
`(.. (Thread/currentThread) join)`. The Rust host spawns it as a child:
`Command::new("clojure").arg("-M:writer")…` (`main.rs:1039-1055`), one child per
session today (the cluster doc's P1 collapses this to one writer hosting many
DBs via `seon.server.session/!registry`).

The registry already exists and is tested (`session.clj`): `!registry` is
`{db-name → {::conn ::backend ::path …}}` and `!agents` is `{agent-id →
db-name}`, with `ensure-db!`, `get-conn`, `resolve-agent`, `list-sessions`,
`list-agents`. **This is the multiplexer that lets one REPL reach every
cluster.** (It is currently unwired from `wire.clj` — fixing that is the P1
cluster work, not this doc.)

### 1.2 socket-REPL, not nREPL

Use Clojure's built-in **socket server REPL** (`clojure.core.server`), not nREPL.

- **Zero deps.** nREPL/cider-nrepl exist in `deps.edn` but only under `:dev` /
  `:nrepl` aliases, not on the writer's classpath; the `:writer` alias doesn't
  even exist yet. socket-REPL is in core — nothing to add to the writer.
- **Started by a JVM-opt, no code.** `-Dclojure.server.repl="{:port 7899
  :accept clojure.core.server/repl}"` starts a socket REPL at boot with no
  source change. The flag is added wherever the writer is launched
  (re-introduced `:writer` alias's `:jvm-opts`, or the in-process
  `wire/start!`).
- **Plain TCP.** Any client (`nc`, `inf-clojure`, an editor's socket-REPL mode)
  connects. This session's `mcp__seon__eval` MCP tool can target it by pointing
  the MCP server's eval transport at the socket; no new bridge needed because
  the socket REPL speaks plain Clojure forms in / printed values out.

If editor tooling (CIDER) is later wanted, add an nREPL server *under a dev
profile only* — never in the production writer (see security, §1.5).

### 1.3 One REPL, every cluster

A human at the writer REPL has the whole JVM in scope, so reaching any cluster
is a function call:

```clojure
;; what clusters exist?
(seon.server.session/list-sessions {})
;; => {::session [{::db-name :cluster/alpha ::backend :file} ...]}

;; query cluster alpha's DB directly (read-only inspection)
(let [{:keys [seon.server.session/conn]}
      (seon.server.session/get-conn {:seon.server.session/db-name :cluster/alpha})]
  (datahike.api/q '[:find ?id ?st :where [?e :task/id ?id] [?e :task/status ?st]]
                  (datahike.api/db conn)))

;; what is agent X bound to?
(seon.server.session/resolve-agent {:seon.server.session/agent-id "a1b2c3…"})

```

No special API is needed — `get-conn` / `resolve-agent` hand back the live
datahike conn and the operator uses `datahike.api` (or a thin
`seon.server.repl` helper ns of read-only convenience fns) directly. This is the
external observation surface for §2.

### 1.4 How it connects to existing tooling

The current session already evals into the seon JVM via `mcp__seon__eval` and
the CLJS pod via `mcp__seon_cljs__eval`. For V2 the wire-server is a *new* JVM;
its socket REPL is a third endpoint. Wiring options, cheapest first:

1. **`nc`/raw socket** for ad-hoc operator use (P1, free).
2. **A new MCP eval target** pointed at the writer's socket REPL port, mirroring
   the existing `mcp__seon__eval` shape, so orchestrator/agents diagnose a
   cluster the same way they diagnose the seon JVM today (P2, small).

### 1.5 Dev-vs-prod gating + security surface

**An open REPL on the writer is full code-exec on the JVM that holds ALL
clusters' DBs.** This is the single biggest security note in the whole design:
the writer is the datahike master for every cluster in the process; a REPL there
can read/transact/`release` any cluster's data and run arbitrary code.

Controls (all P1):

- **Off by default.** No `-Dclojure.server.repl` unless a `--repl` flag (or
  `SEON_WRITER_REPL=1`) is passed. Production launches omit it.
- **Localhost only.** socket-REPL binds `127.0.0.1` by default; never `0.0.0.0`.
  For cross-host dev, tunnel over SSH — do not expose the port.
- **One port per writer process**, recorded to a project-local file
  (`tmp/seon-writer-repl-port`, mirroring `tmp/seon-port`) so the operator/host
  can find it; never a fixed well-known port that another process could squat.
- **No nREPL in prod.** nREPL's richer protocol is a larger attack surface; keep
  it dev-profile-only.

Because P1 isolation is "one JVM many DBs," the REPL's blast radius is every
cluster in that JVM. The later process-per-cluster split (cluster doc "Isolation
later") *also* shrinks the REPL blast radius to one cluster — a second reason
that split is worth keeping free.

---

## 2. The DB + eval log AS the external diagnostic plane (the real answer)

This is the substantive answer to "how do we know an agent is stuck," and it
falls directly out of the reactive-context + code-as-data principles.

### 2.1 Eval is already telemetry

`src/seon/eval.cljs` already persists **every** agent eval as a `:seon.eval`
entity: `:seon.eval/id`, `:seon.eval/at`, `:seon.eval/duration-ms`,
`:seon.eval/source`, `:seon.eval/ok?`, `:seon.eval/ns`, `:seon.eval/result-edn`
/ `:seon.eval/error`, attached as a component child of its owning turn, and
tagged with `:seon.db/origin` (`db.cljs:422`, enum `:user :agent :system :replay
:substrate-seed :test-run`). All of this lands in the **shared cluster DB**.

Therefore the **JVM wire-server REPL — a different OS process from any guest —
can reconstruct exactly what any agent did or is doing by querying datoms,
without sending a single byte to the (possibly wedged) guest.** This is the
independent failure domain the requirement demands: the guest can be a smoking
crater and the operator still has the full, ordered, queryable record of its
behavior up to the moment it wedged.

```clojure
;; last 10 evals by agent a1, newest first — run from the JVM REPL
(let [{:keys [seon.server.session/conn]} (session/resolve-agent {…})
      db (d/db conn)]
  (->> (d/q '[:find ?at ?ok ?src :where
              [?e :seon.eval/at ?at] [?e :seon.eval/ok? ?ok]
              [?e :seon.eval/source ?src]]
            db)
       (sort-by first) (take-last 10)))

```

### 2.2 Liveness signal — make "stuck" a query

The eval log tells you what *completed*; it cannot by itself tell you an agent is
*hung mid-form* (no datom is written until the eval returns). Add a cheap
**heartbeat datom** so liveness is derivable:

- **Per-turn / per-loop-iteration heartbeat.** At the top of each agent loop
  iteration (and/or before each potentially-long eval) the agent transacts a
  tiny `:seon.agent/heartbeat` entity: `{:seon.agent/id … :seon.agent/heartbeat
  <now-ms> :seon.agent/last-basis-t <bt> :seon.agent/phase :evaluating}`.
  Single-attr upsert on `:seon.agent/id` (identity), so it's one small datom
  overwrite, not log growth.
- **Detection is a query, evaluated anywhere with DB access** (JVM REPL, a
  render section, the host via a `q` call): an agent whose
  `now - :seon.agent/heartbeat > threshold` is **stuck**. This is the
  reactive-context idiom — a "stuck agents" section function is just
  `[:find ?id :where [?e :seon.agent/heartbeat ?h] [(< ?h ?cutoff)]]`; it
  renders only while something is wrong and vanishes when healed. No stored
  "stuck" flag to clear.
- **`:seon.agent/phase`** turns the heartbeat into a *narration of where* the
  agent is (`:idle :waiting-llm :evaluating :transacting`), so a human sees not
  just "stuck" but "stuck in `:evaluating`" → almost certainly Wedge A (runaway
  JS), vs "stuck in `:waiting-llm`" → Wedge B (a hung host import / network).

**Is DB-as-telemetry the real answer to "how do we know it's stuck"?** Yes —
with the heartbeat addition. The eval log answers "what did it do"; the
heartbeat answers "is it still alive and where." Both are queryable from a
process that does not share the guest's fiber. This is strictly better than any
in-guest health endpoint, which would itself wedge with the fiber.

Important honesty note: the heartbeat detects a wedge only if the agent reaches
the next heartbeat write. A guest wedged *between* heartbeats (e.g. inside a
121s regex right after a heartbeat) is detected at `heartbeat + threshold`, not
instantly. That's fine — detection feeds the host watchdog (§4), which also has
its own wall-clock `tokio::time::timeout` independent of any guest cooperation.
Two independent detectors (DB-derived + host wall-clock) is the belt-and-braces
the requirement wants.

---

## 3. Agent (in-guest) REPL — convenience, co-designed with the reactive track

### 3.1 The constraints rule out most options

- **No timers** (`setTimeout`/wasi:clocks parking hangs the guest — proven).
- **WIT imports are synchronous-to-JS** (wasm-rquickjs 0.2.4); "blocking" means
  the host fn runs `wstd::block_on`/`.await` cooperating with the QuickJS
  microtask loop (the wasi:http pattern).
- **Single export, single fiber.** `run-agent` hosts the whole agent life.
- **Re-entrancy forbidden** (Gemini Q5, confirmed `CannotEnterComponent`): while
  the guest is suspended inside a host import the host **cannot** call a second
  export; it traps. A long-running agent is ~always either executing JS or
  suspended in an import, i.e. never idle — so a host-initiated `eval-form`
  export is **not reachable** for a live agent.

### 3.2 The three options, with failure modes

| Option | Mechanism | Works while agent busy? | Failure mode when fiber wedged |
| --- | --- | --- | --- |
| (a) **Multiplex on the wake channel** | REPL-eval is another message kind returned by the generalized `next-tx-event` → `next-control-event` the agent loop already pumps | **Yes** — the agent's existing `.then`-recursive loop dequeues it and runs it through the same eval path | Dies with the fiber. No worse than the agent itself; this is accepted because it's a convenience, not the diagnostic. |
| (b) **Dedicated control import** | A separate WIT import `next-control-event` distinct from `next-tx-event`, on its own subscription | Yes, *if* the agent loop polls it | Same fate-sharing — still the same fiber. Buys nothing for independence; adds a second blocking-recv to time out. |
| (c) **Host-initiated export when idle** | Host calls a `eval-form` export between agent turns | **No** — traps with `CannotEnterComponent` while the agent is mid-export (the normal state) | Only usable on an idle instance; our agent is never idle. Rejected. |

### 3.3 Recommendation

**Adopt (a): the in-guest REPL is one more message kind on the host→guest wake
channel, run through the agent's normal `seon.eval` eval primitive.** Rationale:

- It composes with the proven eval path. A real Seon agent must bundle `cljs.js`
  to eval LLM-produced code (the eval-smoke proves `cljs.js` runs in wasm:
  sub-second eval, no OOM — `verification-cljs-wasm-2026-05-28`, post-core.async
  fix). **The agent's own `eval` primitive IS the REPL eval primitive** — a REPL
  form is just LLM-output-shaped code with a different origin. Confirmed: this is
  exactly `seon.eval`'s job (it already records origin); no new evaluator.
- It is co-owned with the reactive track. The cluster doc gives the reactive
  engine the `listen!` hook + the "changed-scouts" event on the host→guest path.
  REPL-eval is simply **another event variant** on that same path. Concretely,
  generalize the wire/WIT event so a control frame can carry either a tx event
  *or* a `{:control :eval :form "…" :reply-id …}` payload; the agent loop's
  dispatch already fans tx events to listeners — it gains one branch that evals
  the form and ships the result back via a `transact!` of a `:seon.eval` entity
  (origin `:user`) which the operator reads from the DB (closing the loop
  through the telemetry plane, no separate reply channel needed).

So: **don't build a parallel REPL transport.** The wake channel + the eval
primitive + the eval log already exist; the in-guest REPL is their composition.
Because it shares the fiber, it is documented as a convenience for poking a
*healthy* agent interactively — the moment an agent is suspect, you use the JVM
REPL + DB queries (§2) and the host watchdog (§4), which do not need the guest's
cooperation.

### 3.4 The blocking-recv tension to resolve (flagged)

The code today (`guest.rs:481 next_tx_event`) is **non-blocking** (`try_recv` +
guest-side `setTimeout(25ms)` pacing) — and that 25ms `setTimeout` is the exact
wstd-hang primitive (the reactive doc's central finding). The reactive track's
plan is to make `next_tx_event` **blocking** (host `recv()` under
`tokio::time::timeout(~100ms)`, guest `.then`-recursion, no `setTimeout`).
**REPL-eval delivery rides whatever that decision lands on** — it must be the
blocking, timer-free variant, because a polled REPL would reintroduce the
banned timer. This is a hard coordination point: the in-guest REPL **cannot be
built before** the reactive track converts the wake channel to the blocking
model. (P3 dependency, §6.)

---

## 4. Detection + recovery from OUTSIDE the fiber (the heart of the worry)

Two wedge types; each needs a different host-side mechanism. Both are confirmed
by the Gemini Wasmtime-44 consultation (appendix).

### 4.1 Wedge A — runaway pure-JS computation (the 121s regex)

The guest is executing JS in a tight loop (no `await`, never unwinds). The
listener loop, any REPL, and any heartbeat all starve.

**Mechanism: wasmtime epoch interruption.**

- Build the engine with `Config::epoch_interruption(true)` (currently the host
  uses only `wasm_component_model(true)` — this is a one-line add).
- Before each `call_run_agent`, `Store::set_epoch_deadline(store, ticks)`.
- A single background tokio task ticks `Engine::increment_epoch()` on a wall
  clock (e.g. every 100ms). The engine is `Send + Sync`; one ticker serves all
  agents.
- On deadline, the next wasm loop-backedge/function-entry **traps**, propagating
  out of `call_run_agent(...).await` as `Err(wasmtime::Trap "wasm execution
  interrupted")` — the host catches it, logs, and tears the agent down (§4.3).
- **Use `Store::epoch_deadline_async_yield_and_update` instead of a hard trap
  for the *routine* deadline**, reserving the hard kill for a much larger
  budget. Why: the host runs the three agents via `futures::join_all` on **one
  tokio task** (`main.rs:1690`, deliberately, to avoid `Send` issues with the
  Store across `tokio::spawn`). A tight-loop guest with no yield would **starve
  its siblings** on that task. `async_yield_and_update` forces the runaway guest
  to periodically yield back to tokio so siblings keep running, while the
  watchdog decides whether to kill it. (Risk note in §7.)

Epoch only fires while wasm is executing — which is exactly Wedge A. It does
nothing for Wedge B.

### 4.2 Wedge B — suspended inside a host import that never returns

The guest is parked inside a host `.await` (a DB call, or the blocking
`next-control-event` recv). The wasm isn't running, so **epoch interruption can
never fire** (Gemini Q2). The host owns that future, so the host fixes it:

**Mechanism: `tokio::time::timeout` on every blocking host import.** Especially
the blocking `recv()` in the generalized wake call — it must be
`tokio::time::timeout(~100ms, rx.recv())` returning a `no-event` sentinel, never
an unbounded `recv()`. Then the import always returns, the wasm resumes, and (if
the agent itself is also looping badly) epoch takes over. Any DB import that
could hang gets the same bounded-timeout treatment. This is the realizable form
of the reactive doc's "bounded-blocking" recv.

### 4.3 Recovery — tear down one agent's Store, siblings unaffected

The host **already** does the structurally-correct thing: it wraps the export in
`tokio::time::timeout(bound, g.run_agent(…))` and on expiry `drop(g)`
(`main.rs:1723-1732`, `1880-1889`). Gemini confirms (Q4, Q6):

- **Dropping a `Store` whose fiber is suspended deep inside a host call is
  sound** in wasmtime 44. Dropping the pending `call_run_agent` future releases
  the Store borrow; dropping the Store frees the fiber stack + instance memory.
  (Caveat: never *reuse* a Store after cancelling a call — it's tainted and
  re-entry traps. We don't reuse: one Store per agent, drop-and-respawn.)
- **Sibling agents are unaffected.** Each agent owns its own
  `Store<GuestStore>`. Dropping one agent's `broadcast::Receiver` (held in its
  `GuestStore.subs`) only deregisters it from the shared sender; the JVM
  `DbHandle` (cloned `broadcast::Sender` + mpsc to the writer actor) and all
  other receivers are untouched.

So **recovery = drop the wedged agent's Store, then re-spawn it.** Because agent
state (its turn log, its facts, its task progress) is durable in the shared
cluster DB, a restarted agent reconstitutes from the DB — the same
"resume = same seed + reconstruct from datoms" mechanism the rest of Seon uses
(code-as-data). The restart loses only in-fiber volatile state (the QuickJS
heap), which is exactly the state that was wedged.

### 4.4 The host watchdog — putting it together

A per-process watchdog task (one, not per-agent), driven by two independent
signals so no single failure-domain gates recovery:

1. **Host wall-clock** (already present): `tokio::time::timeout` per
   `call_run_agent`. Catches *everything* eventually, including total guest
   death, with zero guest cooperation. This is the floor.
2. **DB-derived liveness** (new, §2.2): the watchdog (or an operator at the JVM
   REPL) periodically queries `:seon.agent/heartbeat`. An agent past its
   threshold is flagged; the watchdog then (a) bumps that agent's epoch deadline
   hard to force a trap if it's in Wedge A, and (b) if it doesn't die within a
   grace window (Wedge B — parked in an import), cancels the call future + drops
   the Store.

The watchdog never asks the guest anything. Detection is external (DB query +
host clock); the kill levers (epoch, future-cancel, Store-drop) are all
host-side. This is the concrete, end-to-end answer to "if it gets stuck, how do
we know and fix it?"

### 4.5 Engine config delta (small, P-watchdog milestone)

```rust
let mut config = Config::new();
config.wasm_component_model(true);
config.epoch_interruption(true);          // NEW
let engine = Engine::new(&config)?;
// one ticker for the whole process:
{ let e = engine.clone();
  tokio::spawn(async move { loop {
    tokio::time::sleep(Duration::from_millis(100)).await; e.increment_epoch(); }}); }
// per agent, before call_run_agent:
store.set_epoch_deadline(routine_ticks);
store.epoch_deadline_async_yield_and_update(routine_ticks); // yield, don't starve siblings

```

Fuel was considered and rejected (Gemini Q3): per-instruction fuel decrement
imposes ~2–5x overhead on a bytecode interpreter's hot dispatch loop; epoch's
backedge-only check is ~1–3%. Epoch is the right preemption primitive here.

---

## 5. Capture / provenance

Route human REPL forms through the agent's normal `seon.eval` path so each
becomes a `:seon.eval` datom tagged `:seon.db/origin :user` (the enum value
already exists — `db.cljs:422`). Benefits, all nearly free:

- **Same reactive substrate.** A REPL-driven write triggers the same `listen!` /
  changed-scout reactions an agent write would — the human poking the agent and
  the agent itself are indistinguishable to the reactive engine, which is the
  point of "one mechanism."
- **Training log.** Human REPL interactions sit in the same ordered eval log as
  LLM evals, distinguishable by origin. This is corpus for later.
- **Cross-agent visibility.** Because the eval log isn't filtered by agent in a
  shared cluster DB, a REPL form run "as" the operator shows up in any agent's
  derived view that scans recent evals — the same cross-agent surfacing the
  reactive-context principle gives for free.

**Worth doing: yes.** It's a one-keyword origin tag on an existing path, and it
keeps the "REPL is just eval with a different origin" framing honest end to end.
The JVM-side REPL (§1) is *not* captured this way (it's raw Clojure on the
writer JVM, outside any agent's eval); only the in-guest agent REPL forms are —
which is correct, since those are the ones that belong in the agent's narrative.

---

## 6. Phasing

| Phase | Scope | Depends on | Why here |
| --- | --- | --- | --- |
| **P1 — JVM writer socket-REPL** | `-Dclojure.server.repl` flag-gated, localhost, port-file; `seon.server.repl` read-only helper ns over `session/get-conn` + `resolve-agent`; off by default. | The re-introduced `:writer` alias / in-process `wire/start!` (currently missing — flag it). The cluster doc's `!registry` wiring (P1) makes "one REPL → every cluster" real. | Small, unblocks REPL-driven dev of *everything else*; the external diagnostic from day one. |
| **P1.5 — Heartbeat + eval-log liveness** | `:seon.agent/heartbeat` + `:seon.agent/phase` datoms written per agent loop turn; a "stuck agents" query/section. | Real agent loop in guest (rides P3) for the *write* side, but the *query* side works against any agent that writes it. | Makes "stuck" a query; feeds the watchdog. Can land the schema + query before the guest writes it. |
| **P-watchdog — host epoch + import timeouts + teardown** | `epoch_interruption(true)` + epoch ticker + `set_epoch_deadline`/`async_yield_and_update`; `tokio::time::timeout` on every blocking import; formalize drop-and-respawn. | Nothing in the guest — pure host work. Pairs with P1.5 for DB-derived detection. | The independent-failure-domain recovery. Can land mostly independent of the in-guest REPL. |
| **P3 — In-guest agent REPL** | REPL-eval as a control message on the generalized `next-control-event` wake channel; run through `seon.eval`; result returned as a `:seon.eval` origin `:user` datom. | **Hard dep on the reactive track converting the wake channel to the blocking, timer-free model** (§3.4) and on the real agent (cljs.js + `seon.eval`) running in the guest (the eval-smoke proves it can; the full agent bundle is the remaining build work). | Convenience; co-designed with reactive (same host→guest path, same `listen!` hook). |

**Coordination (called out in the cluster doc and here):** the in-guest REPL
shares the wake channel with the reactive track. The reactive engine owns the
`listen!` hook + the changed-scout event kind; REPL-eval is **another event kind
on the same host→guest path**. They MUST be co-designed: generalize
`next-tx-event` → `next-control-event` once, with a tagged-union payload
(tx-event | changed-scouts | repl-eval), rather than three parallel channels.
This is the "turtles all the way down" / one-mechanism rule applied to the wake
channel.

---

## 7. Risks, smells, and unknowns needing a REPL/wasm probe

- **SMELL — missing `:writer` alias. RESOLVED 2026-06-03.** The host shelled out
  to `clojure -M:writer` (`main.rs:1041`) but that alias was **not in `deps.edn`**
  — the launch path was broken. **Fixed:** a root-level `:writer` alias
  (`-m seon.server.wire` + datahike/transit-clj/jackson-cbor deps) now boots the
  writer from the repo root, and the dev socket-REPL landed as the opt-in
  `--repl-port N` flag on `seon.server.wire/-main` (localhost, port-file). Boot +
  live socket-REPL eval verified; server tests 68/268 green. (In-process
  `wire/start!` remains a viable future refactor but was not needed for the fix.)
- **RISK — sibling starvation under `join_all`.** The host runs co-agents on one
  tokio task via `futures::join_all` (not `tokio::spawn`), justified by Store
  `Send`-bound concerns (`main.rs:1685-1690`). A tight-loop guest with no
  cooperative yield will block that task and starve siblings until the watchdog
  fires. **Mitigation is in the design** (`epoch_deadline_async_yield_and_update`
  forces periodic yields), but this must be **verified under wasmtime** — it's
  the kind of thing the verification doc warns "works in Node, untested in wasm."
- **UNKNOWN (needs wasm probe) — does epoch interruption actually trap a
  QuickJS-in-wasm tight loop here?** Gemini says yes in general, but this exact
  wstd+wasm-rquickjs+wasmtime-44 stack has surprised us before (the timer hang).
  **Probe:** build a guest that runs the known 121s regex, set a 2s epoch
  deadline, confirm `call_run_agent` returns `Err` at ~2s. This is the go/no-go
  for the whole Wedge-A recovery story and cannot be settled from source alone.
- **UNKNOWN (needs wasm probe) — does `async_yield_and_update` keep siblings
  alive?** Probe: one runaway agent + two healthy ones under `join_all`; confirm
  the healthy two keep transacting (visible as DB datoms) while the runaway is
  yielding pre-kill.
- **UNKNOWN — clean Store drop while suspended in a *DB* import.** Gemini says
  sound; verify empirically that dropping a Store mid-`q`-import doesn't wedge
  the shared `DbHandle` mpsc/broadcast for siblings (the one place a shared
  resource could leak a half-finished request).
- **DEPENDENCY — in-guest REPL gated on the blocking wake channel.** P3 cannot
  start until the reactive track lands the timer-free blocking
  `next-control-event`. If that slips, P3 slips; P1 + P1.5 + P-watchdog do not.
- **Scope honesty:** the JVM REPL (P1) is genuinely small and unblocks
  everything; the in-guest REPL (P3) is the largest and most coupled piece and
  is explicitly a *convenience*. If forced to choose, ship P1 + P1.5 +
  P-watchdog and defer P3 — the diagnostic requirement is fully met without an
  in-guest REPL at all.

---

## Appendix — Gemini consultation (verbatim)

Prompt: Wasmtime-44 epoch interruption / fuel / forced teardown / re-entrancy /
sibling-isolation for a single-fiber QuickJS-in-wasm guest with one long-running
export and async host imports exposed sync-to-JS. Full context as in §0–§4.
Response preserved verbatim.

> Here are the precise, version-accurate answers for designing your host-side watchdog in Wasmtime v44.
>
> ---
>
> ### 1. Epoch Interruption
> Epoch-based interruption in Wasmtime v44 works by compiling loop backedges and function prologues with checks that compare a local value against a global epoch counter maintained by the `Engine`.
>
> * **Initialization & API calls:**
>   1. **Configure Engine:** Call `Config::epoch_interruption(true)` when building your `Config`.
>   2. **Set Store Deadline:** Before calling the guest export, you must call `Store::set_epoch_deadline(&mut store, ticks)`. If epoch interruption is enabled and this is omitted (or set to `0`), execution will trap immediately on entry. The `ticks` parameter is relative to the *current* engine epoch.
>   3. **Increment Engine Epoch:** Call `Engine::increment_epoch(&self)`. Since `Engine` is thread-safe (`Send` + `Sync`), you must call this from a separate background task or OS thread ticking on a timer (e.g. `tokio::spawn(async move { loop { tokio::time::sleep(duration).await; engine.increment_epoch(); } })`).
> * **Behavior in a Tight Pure-Wasm Loop:** When the global epoch exceeds the deadline, the next loop backedge or function entry executed by the guest triggers the configured interrupt action.
> * **Trap & Propagation:** By default, reaching the deadline triggers a trap. This aborts execution and propagates out of `call_run_agent(...).await` as a `Result::Err(wasmtime::Error)`. The root cause contains `wasmtime::Trap` with the message `"wasm execution interrupted"`.
> * **Alternative (Async Yield):** Alternatively, you can configure the store to yield instead of trapping:
>   * `Store::epoch_deadline_async_yield_and_update(&mut store, delta)`
>   When the deadline is hit, execution yields back to the executor (returning `Poll::Pending` to Tokio). Upon being polled again, it automatically resets the deadline to the current epoch + `delta`.
>
> ---
>
> ### 2. Epoch + Host Import
> * **Suspended State Behavior:** If the guest is suspended inside an `async` host import (awaiting a Tokio task), **epoch interruption does not fire**. The JIT-compiled check instructions are not running.
> * **Resuming:** The deadline check is executed only when the host import returns and control resumes in the Wasm guest.
> * **Wedged Host Imports:** If the host import *never* returns, epoch interruption is useless.
> * **Wedge-to-Mechanism Mapping:**
>   * **Wedge Type A (Infinite pure-JS loop / catastrophic regex):** Solved by **Epoch Interruption** (trapping or yielding).
>   * **Wedge Type B (Buggy host import / broadcast recv hang):** Solved by **Host-side timeouts**. You must wrap the pending host-side future inside your import implementation with `tokio::time::timeout(duration, future).await` and return an error (or a trappable error) to the guest if it times out.
>
> ---
>
> ### 3. Fuel vs Epoch
> For a single-fiber QuickJS-in-wasm interpreter, **Epoch Interruption is highly recommended** over Fuel.
>
> * **Trade-offs:**
>   * **Overhead:** Fuel tracking requires decrementing a local counter for virtually every WebAssembly instruction. In a bytecode interpreter (like QuickJS) which already runs a hot dispatch loop, this causes a major performance penalty (often 2x to 5x slowdown). Epoch interruption only checks a thread-safe memory location at function entry/backedges, introducing negligible overhead (~1-3%).
>   * **Determinism:** Fuel is fully deterministic. If you need strict resource billing or identical behavior across identical inputs, use Fuel. For a watchdog/timeout mechanism, Epoch is far superior.
>   * **Async Yielding:** Both mechanisms can yield control back to the executor via `Store::fuel_async_yield_interval` or `Store::epoch_deadline_async_yield_and_update`.
>
> ---
>
> ### 4. Forced Teardown
> * **Soundness:** **Yes, this is completely sound.**
> * **What happens:** In Wasmtime v44, the fiber stack, execution context, and instances are owned by the `Store`. Dropping the future returned by `call_run_agent(...).await` releases the borrow on the `Store`. Once the `Store` is dropped, all allocated stacks (including the cached fiber stack) and instance memories are safely freed.
> * **Critical Caveat:** Dropping a pending async call future leaves the component instance in a tainted, non-reenterable state. If you reuse that same `Store`/instance for a subsequent call, it will trap (re-entrance violation). However, because your design allocates **one Store per agent session**, dropping the entire `Store` along with the future is perfectly safe, avoids leaks, and reclaims all resources cleanly.
>
> ---
>
> ### 5. Re-entrancy
> * **Suspended Host Import Case:** **No.** Under the WebAssembly Component Model (and Wasmtime v44), re-entrancy is strictly forbidden. Attempting to call another export on the same instance while it is suspended inside a host import will immediately fail with a `CannotEnterComponent` trap.
> * **Idle Instance Case:** **Yes.** If the guest has returned from its export and is completely idle, the host can safely call a different export.
> * **Implication for REPL-eval:** Since your `run-agent` export is a long-running call that hosts the entire lifetime loop of the agent, the instance is never idle. Therefore, you cannot concurrently push a REPL-eval into a busy agent via a second export call. You must instead implement REPL command polling inside the guest's main loop using a host-imported async stream/channel.
>
> ---
>
> ### 6. Isolation of Teardown
> * **Sibling Isolation:** Dropping one agent's `Store` has no impact on other active sibling agents. Their JIT memories, instance states, and fiber stacks are completely isolated.
> * **Tokio Broadcast Channels:** Dropping a `broadcast::Receiver` held in the dropped store's state only deregisters that receiver from the sender. Sibling receivers and the sender are unaffected.
> * **Shared-Runtime Gotchas:**
>   * **Tokio Worker Starvation:** If an agent gets wedged in a tight loop in Wasm without cooperative yielding, it will hog its assigned Tokio worker thread. This can starve sibling tasks on that thread until the watchdog task fires and drops the store. To prevent this, use `Store::epoch_deadline_async_yield_and_update` to force Wasm execution to periodically yield back to Tokio.
>
> ---
>
> ### Summary of Recommended Architecture
> 1. Spawning a global background task calling `engine.increment_epoch()` on a timer.
> 2. Building the watchdog using `tokio::time::timeout(bound, call_run_agent(...))` on the host.
> 3. Upon timeout, **dropping both the future and the Store** for that agent.
> 4. Setting host-side timeouts inside any async host imports to prevent guest hangs.
