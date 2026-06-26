---
type: research
status: active
tags: [research, agent, architecture, flow, database]
---

# Abstractions Review — Are We Using the Wrong Pattern? (2026-06-26)

A "find the right pattern and the complexity dissolves" review of the CLJS
agent runtime, through a Clojure/Datomic-idioms lens. Companion to the
[[design-soundness-audit-2026-06-26]] (which named the 6 root design errors).
This doc pressure-tests the owner's hypothesis that DE-1 (run-model
race/fencing) and DE-3 (derived-state proliferation) are the WRONG
ABSTRACTIONS, not bugs to patch with a guard + a cache + a central owner.

Verdict up front: **the reframe is right about the mechanism and right about
DE-3 wholesale; it is two-thirds right about DE-1.** Value-threading dissolves
the intra-turn staleness and the pure-derivation duplication completely. It
does NOT dissolve the cross-writer commit race or the "fence the work" problem
— but those collapse too, into a DIFFERENT idiomatic primitive (an in-tx
`:db.fn/cas`), not the pre-read `owns-run?` fencing token we have now. One
sub-invariant (single-driver-per-open-run) survives as genuinely-ephemeral
process state. The net: replace a place/mutable mental model (re-read `@*conn*`
with guard-by-predicate) using a value/transform model (thread one db value,
fence-by-constraint) — and ~5 of the 6 listed DE-1 symptoms and all 7 DE-3
symptoms stop being separate problems.

## 1. The question + the owner's reframe

The runtime accumulated a run-model with race/fencing complexity (DE-1) and the
SAME derived state re-implemented 5+ times across namespaces (DE-3), plus
scattered runtime atoms. The owner's hypothesis: this proliferation is the
WRONG ARCHITECTURE, not something to patch.

The reframe to test (the owner's, paraphrased):

- A `db` is a **VALUE** — an immutable point-in-time snapshot, referentially
  transparent. `(d/q q db)` cannot change under you. Thread ONE db value
  through a turn (the basis-t the agent renders against) and the "DB moved
  between decide and act" race **does not exist** — there is nothing to fence.
  DE-1's race may be an artifact of re-reading `@*conn*` mid-computation
  instead of threading a value.
- Derivations are **pure functions of a db value** — ONE fn, called everywhere
  with the value, memoizable on the immutable value / basis-t (that IS the
  central cache, done idiomatically). DE-3's 5 re-implementations + the
  require-cycle that "justified" them dissolve: a pure `(derive-x db ...)` in a
  leaf ns needs no ns to require `seon.agent` — callers pass the value.
- Genuinely-ephemeral runtime state (an llm-fn closure, a setInterval handle)
  lives in ONE atom, updated by pure fns via `swap!`; DB-derivable state should
  NOT be an atom.
- React = recompute the derivation over the NEW db value from the tx-log; the
  single writer (wire-server) provides ordering.

## 2. Datahike/Clojure model facts that matter (verified in our fork)

Verified against `reference-code/datahike` and `src/seon/db.cljs` /
`src/seon/store/wire.cljs`:

- **A db is a value; `query`/`pull`/`entity` are referentially transparent over
  it.** `seon.db` already exposes every read with an explicit-db arity:
  `(db/query {:seon.db/db db :seon.db/query …})`, `(db/entity {:seon.db/db db
  :seon.db/ref …})`, `(db/entity-lazy {:seon.db/db db …})`,
  positional `(db/query q db & inputs)`. So **the read path can already thread a
  stable value** — the machinery exists; most callers just don't use it
  (`src/seon/db.cljs:478-564`, `900-927`).
- **`as-of` / `since` / `history` are exposed** (`src/seon/db.cljs:941-983`,
  wrapping `d/as-of`/`d/since`/`d/history`). Confirmed real in
  `reference-code/datahike/src/datahike/api/specification.cljc:467-489`.
- **`d/with` / `db-with` (speculative db — apply a tx to a value, get a new
  value, no commit) EXIST in datahike** (`datahike/core.cljc:126`,
  `api/specification.cljc:244-271`) but are **NOT surfaced in `seon.db`** today.
  Available if we want optimistic "what would this tx produce" without a commit.
- **`:db.fn/cas` and `:db/cas` are supported and are PURE DATA** — they cross
  the write wire fine (`datahike/db/transaction.cljc:766-768,1048`). `open-run!`
  already CASes over the wire (`src/seon/agent/run.cljs:278-280`). A CAS with
  `old == new` is a pure in-transaction ASSERTION ("this value is STILL X").
- **`:db.fn/call` (inline tx fn) and `:db/fn` (ident-registered db fn) exist**
  (`transaction.cljc:1052-1068`). BUT an inline `:db.fn/call` carries a fn
  object → it **cannot serialize across our UDS write wire**. A server-side
  `:db/fn` registered by `:db/ident` could (fn lives JVM-side). For the pod,
  the wire-crossable fencing primitive is `:db.fn/cas` (data), not a closure.
- **Every db value carries `max-tx` (basis-t)** via `dbi/-max-tx`, implemented on
  EVERY db type — `DB`, `FilteredDB`, `AsOfDB`, `SinceDB`, `HistoricalDB`
  (`datahike/db.cljc:336,424,493,559,626`). It is a cheap, stable value. The
  RYOW path already reads `(:max-tx db)` (`store/wire.cljs:201-206`). So
  basis-t is a viable memo key.
- **The pod is a follow-the-store replica: each `@*conn*` deref reconstitutes a
  FRESH db value from konserve with lazy LRU node fetch** (`store/wire.cljs`
  ns docstring, `:streaming? false`). Consequence #1: two derefs at the same
  basis-t are EQUAL-by-value but NOT identical objects — object-identity
  memoization will never hit; basis-t is the only stable key. Consequence #2:
  **re-reading `@*conn*` in each leaf fn is not free** — threading one value
  through a turn is a correctness AND a perf win.
- **Writes return a `db-after`.** The wire writer's synthesized report carries
  `:db-after` (the RYOW-resolved post-tx value) and the compact envelope
  carries `:seon.db/tx` (the basis-t) (`store/wire.cljs:262-279`,
  `db.cljs:153-172`). So a loop CAN drive its own snapshot forward from each
  commit instead of re-reading the conn — but the compact envelope strips the
  db value by default (only under `:seon.db/return-report? true`). That is a
  real, small adoption cost (see §6).
- **Single writer = total order.** The JVM wire-server is the sole writer and
  serializes all txs; the pod never writes locally. This is what makes the
  CAS-at-the-writer story sound — concurrency is resolved at one point.

## 3. Current-abstraction map — exactly where we deviate from idiomatic

### 3a. Re-read-the-conn (place model) instead of thread-a-value

- The loop's `next-event` re-derives the event by RE-READING the live conn
  every iteration: `run/snapshot` → `db/entity` (no db arg → `@*conn*`),
  `run/owns-run?` → `@*conn*`, plus a fresh turn-count datalog query
  (`src/seon/agent/loop.cljs:97-140`). Each of the three reads reconstitutes a
  fresh db value. There is no single basis-t the iteration is pinned to.
- The wake handler computes state from the listener's post-commit db snapshot
  (`loop.cljs:255-284`, the db value is RIGHT THERE), then **discards it**: the
  `:idle`/`:running` action runs on a `js/setTimeout 0` macrotask that
  re-derives from `@*conn*` (`loop.cljs:295-338`). The decide/act window is
  exactly this defer. (`setTimeout` also breaks the AsyncLocalStorage agent
  scope, forcing a `with-agent` re-entry — a second symptom of the same "we
  threw away our context and have to re-acquire it" pattern.)
- `derive-status`'s `run-turn-count` reads `@db/*conn*` directly inside an
  otherwise db-parameterized read (`src/seon/agent.cljs:401-413`).

### 3b. The fencing token guards the wrong thing (pre-read predicate, not a constraint)

- `owns-run?` is a **read predicate** evaluated BEFORE the write
  (`src/seon/agent/run.cljs:172-181`). `beat!`/`renew!`/`pause!`/`resume!` each
  call it and bail with a hand-built `fencing-error`
  (`run.cljs:206-214,322-356,363-412`). This is classic
  time-of-check/time-of-use: the check reads one db value, the write commits
  against a later one.
- The actual UNIT OF WORK is unfenced: `open-turn!` and `eval-batch!` take **no
  run-id** and write the corpus with no ownership assertion (audit F14;
  `turn.cljs:176`, `eval.cljs:2656`). So a watchdog-closed or superseded run
  still lands its full eval batch.
- `open-run!`'s CAS, by contrast, IS idiomatic — `:db.fn/cas` on
  `:seon.agent/run` being absent (`run.cljs:264-280`). The OPEN race is already
  solved correctly; the problem is that this good pattern was used in exactly
  ONE place and the rest of the lifecycle reverted to pre-read guards.

### 3c. The five re-implementations + the require cycle (DE-3)

`current-run` + `derive-state` exist in 5 places, each justified by dodging the
`agent → ctx → render` require edge:

- `seon.agent.run/current-run` + `owns-run?` (`run.cljs:155-181`) — reads
  `@*conn*`, no db arg.
- `seon.ctx/current-run` + `seon.ctx/derived-state` (`ctx.cljs:286-312`) —
  db-parameterized (correct shape), the de-facto "one reader" per its docstring.
- `seon.render.default/derived-state` + `agent-turn-count`
  (`render/default.cljs:191-232`) — "Local here to keep the seon.agent cycle
  open."
- `seon.agent/derive-agent-state` delegates to `ctx/derived-state`
  (`agent.cljs:329-337`) — good — but `derive-status` re-inlines the
  primitives-cond AGAIN (`agent.cljs:472-519`) and hand-writes `total-turns` /
  `run-turn-count` (`agent.cljs:376-413`).
- `seon.agent.schedule/agent-idle?` re-derives idle-ness from primitives "so
  this ns need not require seon.agent" (`schedule.cljs:266-274`).
- Turn-count datalog is hand-written in ≥4 sites — `loop.cljs:88-95`,
  `agent.cljs:401-413`, `render/default.cljs:191-206`, `ctx/transcript.cljs`
  loop-k (`transcript.cljs:291-299`) — and two have already diverged (the
  agent.cljs copy gates on `installed-schema`, the loop.cljs copy does not:
  audit F5).
- The `activity-log` re-derives state with a partial `cond` that omits `:paused`
  (audit F11; `loop.cljs:529-532`).

Every copy is justified by "avoid the require cycle." The cycle is real ONLY
because the derivations were written to need entity/constant knowledge from
`seon.agent`. A pure `(derive-state db agent-id)` needs nothing from
`seon.agent` — only `seon.db` + `seon.agent.fsm` (already a pure cljc leaf).

### 3d. Runtime atoms — ephemeral vs DB-derivable

Inventoried across the pod (`grep defonce/(atom`):

- **Genuinely ephemeral (legit runtime artifacts):** `!loop-input`
  (agent-id → llm-fn closure + compile-state, `loop.cljs:69`), `!ticker`
  (setInterval handle, `loop.cljs:420`), `!adapter` (feed subscription handle,
  `store/wire.cljs:308`), `!agent-conn` (`client.cljs:303`),
  `repl/!compile-state` + `!conn` (`repl.cljs:76-85`). These are not derivable
  and belong in ONE runtime-state holder (audit F24 organizational half).
- **DB-derivable masquerading as state — should be deleted:**
  `!runs-this-process` (`run.cljs:113-120`) — "which runs did THIS boot open" is
  derivable from a per-boot marker on each run (or a boot-id in tx-meta). Audit
  F24 derivable half.
- **The leaking dedup set:** `!own-write-ids` (`store/wire.cljs:215-223`) — the
  echo-suppression set that stitches two listener-firing paths; the source of
  DE-2's own/foreign branch. Out of scope here but the same place-model smell
  (a stored set that must be cleared) the reframe targets.

## 4. Assessment — does value-threading + pure-derivation collapse DE-1 and DE-3?

### DE-3: collapses completely. This is the clean win.

`current-run`, `derive-state`, `agent-turn-count`, `run-turn-count`,
`last-beat`, `armable-agent-ids` are all pure functions of `(db, agent-id)`.
Put them in ONE acyclic leaf — call it `seon.derive` (or fold into the existing
`seon.agent.run`, which already requires only `seon.db` + `seon.schema`, plus
the pure `seon.agent.fsm` cljc) — each taking an explicit db value. Every
consumer (loop, ctx, render, schedule, inspector, agent) requires that leaf and
PASSES the value it already holds. The require cycle (`agent → ctx → render`)
**evaporates** because the leaf depends on nobody. `armable-agent-ids` /
`agent-idle?` / `activity-log` become FILTERS over the one `derive-state`, not
re-encodings of the rule, so F11's missing-`:paused` and F5's diverged
turn-count become structurally impossible (there is one rule). This is not a
new direction — it is the [[reactive-context]] doctrine the codebase already
claims, finally with a single owner.

### DE-1: collapses in three distinct pieces — and ONLY value-threading is not enough.

Decomposing the DE-1 "race" into the three sub-problems the owner asked about:

- **(a) Intra-turn decide/act staleness — DISSOLVES under value-threading.**
  If `run-loop!`/`next-event` threads ONE db value per iteration (pin to a
  basis-t, derive event + render prompt + count turns all against it), the
  "DB moved between decide and act WITHIN a step" race is gone — it was an
  artifact of three independent `@*conn*` re-reads. The wake-handler keeps the
  listener's db value across the defer instead of re-deriving from `@*conn*`.
- **(b) Inter-trigger concurrent-open race — ALREADY solved, not by threading.**
  Two triggers (a message + a schedule fire) both see `:idle` and both submit an
  open-tx; the single writer serializes them, the first CAS wins, the second
  CAS fails and the loser renews. This is `open-run!` TODAY
  (`run.cljs:264-284`) and it is correct. Value-threading neither helps nor
  hurts here; the audit slightly over-states this as a "race" — the
  open-or-renew CAS is the idiomatic answer and it is in place.
- **(c) Fencing the WORK of a superseded/watchdog-closed run — does NOT dissolve
  under threading; needs an in-tx constraint.** Threading makes the
  *computation* consistent but does not stop the *submission* of a zombie: if
  the LLM takes 10s and the watchdog closes the run at 9s, the pod still tries
  to commit the eval batch at 10s. The fix is to move the fence FROM a pre-read
  predicate (`owns-run?`) INTO the work-tx itself: every work-tx (beat,
  open-turn, eval-batch) begins with `[:db.fn/cas [:seon.agent/id id]
  :seon.agent/run [:seon.agent.run/id run-id] [:seon.agent.run/id run-id]]` —
  a pure-data assertion "I am STILL the agent's current run." If the pointer
  moved, the whole tx fails atomically at the writer, the loop sees the failure
  value and terminates. **No pointer = no work.** This deletes every
  `owns-run?` pre-read AND fences the unit of work that was never fenced —
  collapsing F6 (hand-built fencing errors), F14 (unfenced work), and the
  five guarded writes into one constraint.

So: **(a) + (c) collapse into ONE mechanism** — thread the value for reads,
constrain the commit for writes (Gemini's "Snap-to-Tx Turn"). **(b) is already
done.** The "fencing token threaded everywhere" was the wrong abstraction; the
right one is the constraint datahike already supports and we already use once.

### Where DE-1 does NOT fully collapse (be honest):

- **Single-driver-per-open-run.** The work-CAS asserts ownership by run-id, but
  TWO loops driving the SAME open run both carry the same run-id, so both pass
  the CAS and double-drive (interleaved turns — wasteful, not corrupting). The
  CAS fences SUPERSEDED runs, not duplicate drivers of a LIVE one. This needs
  either a single entry-point primitive that won't start a second loop, or a
  small process-local "runs I'm currently driving" set — which is
  **genuinely-ephemeral state** (same class as `!loop-input`), not a DB
  derivation. The audit's "driver-lease" intuition is right; it just isn't the
  CAS.
- **No preemption of an in-flight turn.** Optimistic concurrency rejects the
  COMMIT after the fact; it does not interrupt a running LLM call or a sync
  runaway. A human "STOP" during a 30s generation does not abort the
  generation — it only guarantees the resulting tx fails the CAS. Hard
  termination of a sync runaway remains Phase-2 worker-kill (already noted in
  `run.cljs:419-423`), orthogonal to this collapse.
- **Wake catch-up after a feed gap** is DE-2, not DE-1 — value-threading does
  not address a DROPPED wakeup. Out of scope here.

## 5. Gemini consultation (verbatim)

Consulted via `(user/search …)` (Gemini, web access) with the full brief above
plus the six key source files. Response preserved verbatim:

> This architecture is currently suffering from a **Place-Oriented Programming (PLOP)** hangover. You are treating an immutable database as a mutable shared variable that you "guard" with predicates, rather than treating it as a value that you "transform" into other values.
>
> Here is the rigorous critique of your audit and the proposed reframe through the Datomic-idiom lens.
>
> ---
>
> ### 1. The Minimal Mechanism: The Snap-to-Tx Turn
> The single mechanism that collapses DE-1 and DE-3 is **The Snap-to-Tx Turn**.
>
> In Datomic, a process doesn't "loop and check things." A process **projects a snapshot into a transaction**.
>
> *   **The Pattern:** A Turn is a function: `(fn [db context] tx-data)`.
> *   **The Guard:** Every `tx-data` produced by a Turn must begin with a `:db.fn/cas` on the Agent's `:seon.agent/run` pointer.
>
> This collapses the "race" because the fencing isn't a check you do *before* you work; it is a **precondition of the work itself**.
>
> ### 2. Collapsing DE-1: Atomicity and Fencing
> You asked which races dissolve under value-threading. Let's be precise:
>
> *   **(a) Intra-turn staleness (Decide/Act):** **DISSOLVES.**
>     If you thread one `db` value, your LLM prompt, your turn-count check, and your logic are all looking at the same frozen universe. The "intra-turn race" is currently an artifact of your `owns-run?` predicate being a *Read* instead of a *Constraint*.
> *   **(b) Inter-trigger concurrent open:** **DISSOLVES at the Writer.**
>     Threading a value doesn't fix this, but your existing `:db.fn/cas` on `:seon.agent/run` does. If two messages trigger, both see `:idle` (run = nil). Both submit a tx. The first wins. The second's CAS fails at the Wire-Server. The second trigger then receives a "Failure" value, realizes it lost, and switches to `renew!`. This is correct.
> *   **(c) Fencing the WORK of a superseded run:** **STILL WRONG in your current model.**
>     Value-threading makes the *computation* consistent, but it doesn't stop the *submission* of a zombie. If an LLM takes 10 seconds to respond, and a Watchdog closes the run at 9 seconds, the Pod will still try to `db/transact!` the results at 10 seconds.
>     *   **The Fix:** Your "Work-Tx" (the one carrying the eval results) **must** include the CAS: `[:db.fn/cas agent-eid :seon.agent/run run-eid run-eid]`. If the watchdog moved the pointer, the eval-tx simply fails to commit. **No pointer = No work.**
>
> ### 3. Collapsing DE-3: Derivation Proliferation
> The "require cycle" excuse is a classic symptom of failing to separate **Entity Logic** from **Database Logic**.
>
> *   **The Error:** You are likely requiring `seon.agent` because you want to know "What is an Agent?" or you need its constants.
> *   **The Reframe:** A pure derivation fn `(get-current-run db agent-id)` belongs in a leaf namespace (e.g., `seon.db.projections` or just `seon.agent.run`).
> *   **Basis-t Memoization:** Keying a memo on `(d/basis db)` (the basis-t) is the **standard Datomic central cache**.
>     *   **Footgun 1 (Unbounded growth):** Use `clojure.core.memoize` with an LRU cache or a TTL. Never use a raw `memoize`.
>     *   **Footgun 2 (Filtered/As-of DBs):** In Datahike, `(d/basis db)` on an `as-of` value returns the `t` of the as-of, not the latest tx. This is actually exactly what you want. The memoization is valid for that specific "point in time."
>     *   **The One Mechanism:** One leaf namespace `seon.derive` that takes a `db` value and an `agent-id`. Every other ns (render, turn, loop) requires `seon.derive`. Since `seon.derive` only requires `seon.db` (or nothing), you have zero cycles.
>
> ### 4. Critiquing the Proposed Reframe (Pressure Test)
>
> #### Reframe Point 3: Genuinely-ephemeral state in one atom.
> **Half-Correct.** While consolidating into one atom is better than four, you should distinguish between **Environment** (the `llm-fn`) and **Process Control** (the `setInterval` handle).
> *   The `llm-fn` shouldn't even be in an atom; it should be part of the `context` passed into the loop.
> *   The `!runs-this-process` set is a "Local Index." It's okay, but verify: why do you need it? If it's for echo-suppression, Datomic-idiom agents usually use a `tx-id` or a `client-side-nonce` (v-tide) stored in the DB to recognize their own work.
>
> #### Reframe Point 5: Staleness in an ASYNC turn.
> **This is your biggest risk.** If a turn is "frozen" on Basis-t 1000, and the LLM takes 30 seconds to generate code, and during those 30 seconds a human sends a "STOP" message (Basis-t 1005):
> *   The Turn *will* complete its work against Basis-t 1000.
> *   The Turn *will* try to commit its work.
> *   If you use the CAS fencing described in Point 2, the commit will **fail** because the watchdog or the human moved the pointer.
>
> **Is this "correct"?** Yes. It's **Optimistic Concurrency Control**. The alternative (re-reading the DB mid-turn) leads to the **Internal Consistency Paradox**: the LLM generates code based on a "world" that changed halfway through the generation. The code it produces might be nonsensical (or dangerous) relative to the new DB state.
>
> **Verdict:** In an autonomous agent, **Consistency > Liveness**. Thread the frozen value. If the world changed enough to invalidate the turn, the Turn's final transaction should fail at the CAS boundary.
>
> ---
>
> ### The Sharp Version: The "Mechanism Collapse"
>
> You have too many "Active" parts (watchdogs, tickers, listeners, loops). In a Datomic architecture, there is only **The Snapshot** and **The Pulse**.
>
> 1.  **The Snapshot:** The `wake-handler` takes a `db` value. It calls `(seon.derive/agent-state db id)`.
> 2.  **The Pulse:** If `:idle`, it transacts an `open-run` with a CAS.
> 3.  **The Transformation:** `run-loop!` is a recursive function: `(db, run-id) -> db'`.
>     *   It renders a prompt from the current `db`.
>     *   It gets LLM results.
>     *   It produces a `tx-data` bundle.
>     *   **CRUCIAL:** That `tx-data` includes `[:db.fn/cas agent-eid :seon.agent/run run-id run-id]`.
>     *   It `transact!`s. The **post-commit DB** from that transaction becomes the `db` for the next iteration of the loop.
> 4.  **The Exit:** If the `transact!` returns a CAS failure (Ok? false), the loop **immediately terminates**. It doesn't need to check `owns-run?`. The database already told it: "You are no longer the authority."
>
> **Minimalism Checklist:**
> *   Delete `owns-run?` calls in the Pod.
> *   Inject `[:db.fn/cas ...]` into the `tx-data` of every `beat!`, `open-turn!`, and `eval-batch!`.
> *   Replace all 5 re-implementations of `derive-state` with one call to a memoized function in a leaf `seon.derive` ns.
> *   Thread the `db` value through `run-loop!`. Each `recur` uses the `db-after` from the previous transaction's report.
>
> **Where the reframe is wrong:**
> The owner says: *"React = recompute the derivation over the NEW db value from the tx feed."*
> **Critique:** This is only for the *Wake Trigger*. Within a `run-loop!`, you should react to your **own** `tx-reports` to move your internal `db` value forward. Re-reading `@*conn*` is a "follow-the-store" smell; in a turn, you should "drive-the-snapshot."

## 6. Proposed idiomatic collapse — the concrete pattern

The one mechanism, named: **Snap-to-Tx Turn** — thread a db VALUE for reads,
constrain the COMMIT for writes. Combined with **one acyclic derive leaf**.

### 6a. The turn becomes a value-transform

`run-loop!` becomes a recursion over a db value, not over `@*conn*`:

```
run-loop! (db, run-id, streak) ->
  event   = (derive/next-event db run-id streak)     ; pure, one basis-t
  if :turn-ok:
    tx      = (turn->tx-data db input run-id)         ; render + LLM + forms
    tx*     = (into [[:db.fn/cas [:seon.agent/id id]
                       :seon.agent/run
                       [:seon.agent.run/id run-id]
                       [:seon.agent.run/id run-id]]]   ; "still mine" assertion
                    tx)
    report  = (db/transact! {::db/tx-data tx* ::db/return-report? true})
    if (false? (::db/ok? report))  -> terminate (CAS lost; DB says not yours)
    else recur with (:db-after (::db/tx-report report)) as the next db
    ;; NB: the COMPACT envelope omits db-after — the db value rides the raw
    ;; report at ::db/tx-report, gated by ::db/return-report? true (see §6e).

```

- The prompt render, the turn-count, the bound checks, and the FSM event all
  read the SAME threaded `db` — intra-turn staleness (a) is gone.
- The `:db.fn/cas` is pure data, crosses the wire, and fences the WORK (c).
  `beat!` / `open-turn!` / `eval-batch!` all gain the same leading CAS instead
  of a pre-read `owns-run?`. Delete `owns-run?` and `fencing-error` from the
  pod write path.
- Each iteration advances on the commit's `db-after`, not a fresh `@*conn*`
  deref — fewer reconstitutions, exact basis-t (Gemini's "drive-the-snapshot").

### 6b. The derivations become one leaf

A new `seon.derive` (or fold into `seon.agent.run`, already an acyclic leaf):
`(current-run db id)`, `(derive-state db id)`, `(next-event db run-id streak)`,
`(run-turn-count db run-id)`, `(agent-turn-count db id)`, `(last-beat db
run-id)`, `(armable-agent-ids db)`, `(agent-idle? db id)`. Each takes an
explicit db value; depends only on `seon.db` + pure `seon.agent.fsm`. Every
consumer requires it and passes the value it holds. Deletes all five
re-implementations, the diverged turn-count copies, and the partial-`cond`
activity-log — the require cycle is gone because the leaf needs nobody.

### 6c. Memoization is the escape hatch, not the collapse

Threading already computes each derivation ONCE per turn, so the memo only
matters ACROSS independent consumers at the same basis-t (loop + inspector
rendering together). Per house doctrine ("measure before caching";
`:memory`/local datahike reads are sub-ms on small datom counts), do NOT lead
with a memo. If profiling demands it: key on `(:max-tx db)` (NOT object
identity — replica derefs are fresh objects). Footguns to respect:

- **No `clojure.core.memoize` in CLJS.** Gemini's LRU/TTL advice is JVM; the
  pod needs a small bounded memo (cap N basis-t entries, evict oldest) — a
  ~15-line helper, not a dep.
- **basis-t collisions across db TYPES.** A `FilteredDB` (the inspector's
  per-agent view) and an `as-of` value can share a basis-t with the current db
  but answer queries DIFFERENTLY. A basis-t-only key is correct ONLY for
  current-db derivations; a memo shared with filtered/as-of reads must key on
  the db type too (or not be shared). Gemini's "exactly what you want" holds
  only for same-shape db values.

### 6d. Runtime state consolidates

- Move the llm-fn + compile-state OUT of `!loop-input` and INTO the `input`
  context already threaded through `run-loop!`/`run-turn!` (it is already a
  parameter — `loop.cljs:154`). The atom existed only so `drive-run!` (resume)
  could re-find it; with a single open-or-drive entry point holding the context,
  the registry shrinks to "what am I currently driving" (the single-driver set).
- Delete `!runs-this-process`; derive "this boot's runs" from a per-boot marker
  (a boot-id in run tx-meta, joinable in datalog) — it is DB-derivable.
- Keep `!ticker` / `!adapter` (genuine OS handles) in ONE named runtime-state
  holder. These are the legitimate `swap!`-updated ephemeral artifacts.

### 6e. What it takes to adopt (honest cost)

- Thread `db` through `next-event` + the `run-loop!` recur, and through the
  wake handler's deferred open (carry the listener's db value across the
  `setTimeout`). Medium edit, contained to `loop.cljs` + `run.cljs`.
- Add the leading `:db.fn/cas` to every work-tx and DELETE `owns-run?` +
  `fencing-error`. Verify a no-op CAS (`old == new`) behaves as a pure assertion
  in our datahike fork (it should — `compare-and-swap` raises iff current !=
  expected; assert with `old == new == current` is a successful no-change op).
  This is the one thing to live-prove before committing.
- Create the `seon.derive` leaf; repoint the 5 consumers; delete the copies.
  Mechanical but touches many namespaces (render, ctx, schedule, inspector,
  agent, loop).
- Surface the post-tx db value in the loop path: either pass
  `:return-report? true` (the existing escape hatch carries `:db-after`) or add
  a small `seon.db` helper that returns the compact envelope PLUS the db-after
  for the infra loop. Tiny.
- Optionally surface `d/with`/`db-with` in `seon.db` if we want speculative
  "what would this tx produce" for the agent — not required for the collapse.

### 6f. Genuinely-hard bits that remain

- **Single-driver-per-open-run** (§4) — the CAS does not prevent two loops on a
  LIVE run; needs the one entry point + a process-local driving-set. Small, but
  it is real ephemeral state, not a derivation.
- **No mid-turn preemption** — optimistic-concurrency rejects the commit; it
  does not abort a 30s LLM call or a sync runaway. Hard kill is Phase-2.
- **Cross-process write latency** — every work-tx now round-trips a CAS to the
  wire-server. open-run! already does this with acceptable latency; adding a CAS
  to every beat/turn tx adds no extra round-trips (it rides the existing tx) but
  does make a superseded turn's wasted LLM work visible only at commit time.
  That is inherent to optimistic concurrency and is the correct trade
  (Consistency > Liveness for an autonomous agent).
- **DE-2 (the tx-feed bus)** is untouched by this collapse — a dropped wakeup is
  still dropped. That is a separate (and arguably more urgent) fix.

## 7. Recommendation

**Adopt the Snap-to-Tx collapse.** The owner's reframe is correct in
substance: we have been programming the immutable DB as a mutable place
(re-read `@*conn*`, guard-by-predicate), and the proliferation is the symptom.
The single mechanism is: **(1) thread one db value per turn** (reads become
referentially-transparent over a pinned basis-t; the loop advances on each
commit's `db-after`); **(2) fence the WORK with an in-tx `:db.fn/cas` on
`:seon.agent/run`** (delete every `owns-run?` pre-read — the database, not a
predicate, tells the loop when it has lost authority); **(3) one acyclic
`seon.derive` leaf** for every DB derivation (the require cycle that "justified"
five copies dissolves). This collapses ~5 of the 6 DE-1 symptoms and all 7 DE-3
symptoms into two primitives we already have (value-threading is free; CAS is
already used once). Memoization is a later perf escape hatch, not part of the
collapse — do not lead with it.

Carry forward as NOT-collapsed-by-this: single-driver-per-open-run (small
ephemeral set), mid-turn preemption (Phase-2 worker kill), and DE-2's feed
catch-up (separate fix). Live-prove the no-op-CAS-as-assertion in the fork
before committing the work-fence.

## 8. Locked model (agreed with owner, 2026-06-26) — what we are building

The collapse, refined through the owner conversation into the model we are
implementing. The companion mindset doc every implementing agent reads is
[[datahike-primer]].

### 8a. Thread one frozen db value per TURN — not per loop

The owner asked whether one db value could be carried through the whole loop.
**No — freeze per *turn*, not per *loop*.** The db value is a chain that advances
at every commit:

```
turn 1:  db₀ (frozen) → render → LLM → tx → COMMIT → db-after₁
turn 2:  re-read latest (frozen) → render → LLM → tx → COMMIT → db-after₂
turn 3:  …
```

- **Within a turn:** one frozen value threads through `next-event` + render +
  bound-checks, so the LLM reasons about a consistent world (intra-turn
  decide/act staleness dissolves). Implementation: re-read `@*conn*` ONCE at the
  top of each `run-loop!` iteration and pass that value down.
- **Between turns:** re-read the latest value at the next turn's top. Single
  writer ⇒ "latest" is the **global** store including every other agent's writes
  and the human's messages up to that point — never a private "little world."
- We re-read latest each turn rather than driving `db-after` forward: the gap is
  microseconds and re-reading maximizes responsiveness to external stops (below).
  `db-after` driving is a discarded micro-optimization, not the model.

### 8b. Fence the WORK with an in-tx `:db.fn/cas` — LIVE-PROVEN

Every work-tx (`beat!`, `open-turn!`, `eval-batch!`) LEADS with
`[:db.fn/cas [:seon.agent/id id] :seon.agent/run [:seon.agent.run/id R]
[:seon.agent.run/id R]]`. Delete every `owns-run?` pre-read and `fencing-error`
from the pod write path. `open-turn!`/`eval-batch!` must take the run-id and
carry the fence (closes F14 — the previously-unfenced unit of work). The
keystone *buffer-worker-writes-commit-atomically* fix folds into this single
fenced write path.

Proven on the live pod (2026-06-26, agent `1115`): a no-op CAS commits its
bundled work when the pointer still matches (Proof A) and **aborts the whole tx,
rejecting the bundled work, when the pointer moved/was retracted** (Proof B,
`:transact/cas` error). The database — not a predicate — tells the loop it lost
authority. Recorded in `night-loop-log.md`.

### 8c. Stopping an agent (the owner's requirement) — two paths, both covered

| Stop lands | Mechanism | Result |
|---|---|---|
| **Between turns** | next turn re-reads latest; `next-event` sees the run closed/superseded/terminated | clean exit (≤1-turn latency) |
| **During a turn** (mid-LLM) | the turn's work-tx CAS finds the pointer moved | commit aborts; no zombie work lands; loop terminates |

Hard-aborting an in-flight LLM call or a sync runaway is Phase-2 worker-kill —
orthogonal to this collapse. Within a run the loop reads the store itself each
turn, so a stop to a *running* agent arrives via the per-turn re-read, NOT the
feed.

### 8d. One acyclic `seon.derive` leaf (DE-3) + kill `seon.agent.fsm`

Pure fns of `(db, agent-id)`: `current-run`, `derive-state`, `run-turn-count`,
`agent-turn-count`, `last-beat`, `armable-agent-ids`, `agent-idle?`, plus the
`derive-status` fingerprint. Each takes an explicit db value; the leaf depends on
nobody above it, so the `agent → ctx → render` cycle that "justified" the five
copies evaporates. The pure transition table (`transitions`/`transition`) moves
to `seon.agent.loop` (owner's stated preference); `seon.agent.fsm.cljc` is
deleted. `armable-agent-ids`/`agent-idle?`/`activity-log` become FILTERS over the
one `derive-state` — F5 (diverged turn-count) and F11 (missing-`:paused`) become
structurally impossible.

### 8e. Caching: none by default; basis-t-keyed only if measured

Threading computes each derivation once per turn, so do NOT lead with a memo. Do
NOT memoize on the db value — `equiv-db` walks the EAVT index and faults konserve
nodes in on every hit (see [[datahike-primer]] §5). If profiling shows a hot
cross-consumer derivation, add a ~15-line bounded map keyed on `[basis-t
db-type]` inside the leaf. (`as-of`/filtered dbs report the ORIGIN's basis-t —
db-type MUST be in the key.)

### 8f. DE-2 is a FEED-correctness gap, not an RPC gap — since-t wake replay

The wire has two channels: request/reply RPC (reliable by construction —
`transact`/`q`/`pull`/`knn-search`; "only values cross," already in production
incl. embeddings) and the tx FEED (a polled per-handle bounded queue). DE-2 lives
only in the feed: it was built on "a dropped event is harmless, re-read latest" —
true for rendering, false for the WAKE edge (the event IS the trigger). Fix:
`since-t` replay on `subscribe-tx` (the JVM writer replays from its tx-log on
reconnect; the pod tracks last-applied basis-t and re-subscribes with it). The
feed's only jobs are waking idle agents + the inspector SSE.

### 8g. Build order

1. **Unit 1 — `seon.derive` leaf** (§8d). Unblocks the `fsm` kill; foundation for
   the threading.
2. **Unit 2 — per-turn threading + CAS work-fence** (§8a/8b/8c). Depends on Unit 1.
3. **Unit 3 — feed since-t wake replay** (§8f). Largely file-independent (JVM
   `boot.clj`/`wire.clj` + pod `wire.cljs`); can proceed in parallel, live-proved
   serially on the one cluster.

Not in this pass (captured, not built): generalizing the wire op-surface into a
named "function-call" RPC registry (the op-RPC already exists and is values-only;
a registry is ergonomic, not correctness — follow-on); mid-turn hard preemption
(Phase-2 worker-kill); single-driver-per-open-run (a small process-local "runs
I'm driving" set, added with Unit 2 if a second driver is observed).
