---
type: research
status: draft
tags: [research, agent, architecture]
---

> **SUPERSEDED for design decisions** by `architecture/ctx-render-strategies-prd.md` (2026-05-26 revision). Retained for history; do not use as the current spec.

# Agent-loop pattern survey — what well-studied primitive fits Seon's needs?

## TL;DR

Seon's agent loop is **the Elm Architecture re-discovered against Datahike**, and the right primitive is already in the house: `d/listen!` on the tx-report queue, plus a tiny per-agent dispatcher that **owns the agent's `:idle ↔ :running` state machine and re-enters `run-agentic-loop!`** when wake conditions hold. The "trigger as DB entity" sketch was reaching for the right shape — *handler indirection through a DB-resident registry so the substrate and the agent share one mechanism* — but conflated two things that need to stay separate: **derivation (section render = pure fn of db, no entity needed)** and **dispatch (wake/effect = side-effect, needs a handler entity)**. Collapsing them produces the recursive-transact loop that has been tying us in knots.

The recommendation in one sentence: **keep section composition derivation-only (status quo, per `concepts/reactive-context`); model wake/effect as a thin tx-report → reducer pattern (`(state, tx-report) -> {:wake? :effects [...]}`) where the reducer is a normal CLJS fn**, registered per-agent in process memory (rebuilt at boot from `:seon.fn` entities via the existing code-as-data mechanism), with the agent's `:seon.agent/state` enum as the single source of truth for "is this fiber alive right now." External async results land on the bus the same way user messages do: by transacting a `:seon.message` (or `:seon.async-result`) entity. The DB IS the queue.

This deliverable maps the eight candidate patterns onto our actual constraints, then sketches the recommended design.

---

## 1. The problem, restated against the existing code

Today's loop (`src/seon/agent.cljs:336-358, 663-706`) already implements 80% of what we need; the question is how to extend it cleanly. The existing wire:

1. `install-user-trigger!` registers a `db/listen!` handler keyed `[::user-message-trigger <agent-id>]` per agent (agent.cljs:360–375). Hot-reload-safe via unlisten-then-listen.
2. `user-message-handler` (agent.cljs:336–358) is a tx-report callback that scans `:seon.db/attr-index` for added `:seon.message/role` datoms, filters to `:user`, checks the agent's `:seon.agent/state`, and if not `:running`, schedules `(run-agentic-loop! input)` via `js/setTimeout 0` inside a `db/with-agent` scope to re-establish ALS.
3. `run-agentic-loop!` is a tight `loop`/`await`/`recur` over `run-turn!`. State flips `:running` on the turn's open-tx and `:idle` on close-tx. The next tx-report fan-out finds the agent `:idle` again and re-enters if there are new user messages.

What this misses (the actual problem statement):

- **External async completions** (LLM call resolves 3s later, MCP tool returns, agent-spawn finishes). Today these are inside `await` chains rooted at the original `run-turn!`. They never go through `db/listen!`. When the model gets the ability to spawn fire-and-forget work that resolves AFTER the originating turn closes, we have no story.
- **Cross-agent wake** (agent B's tx should wake agent A). Today every agent's listener sees every tx (agent.cljs:339-341 filters by message role, not agent), but no test verifies it nor a use case demands it. The cross-agent visibility for *rendering* already works (`reactive-context` shows the warnings-section sees all agents). For *wake*, untested.
- **Non-message wake events** (a `:seon.async-result` row, a `:seon.tool-call/done`, a `:seon.system/notification`). Today the only wake source is "new user message"; the handler hard-codes `:seon.message/role :user`.
- **Resume mid-turn after pod crash** (v1.md §7.4 plan item: flip `:status :running` turns to `:interrupted`, transact a system message, kick). Designed but the kick path through the same `db/listen!` handler is fine; the *interrupted-turn detection* is what's missing.
- **Recursive transact cycle prevention.** If wake handlers can transact, and transacts trigger wake handlers, you have to bound the recursion.

The `:seon.trigger` sketch was trying to handle (1)–(4) uniformly by **lifting handler identity into the DB** (`:seon.trigger/fn` symbol + `:seon.trigger/on` match-spec). That instinct is good — handlers should be data so resume works and so the agent can author/customize them. The misstep was making each trigger ALSO compute a `:ctx <data>` value for rendering, which then needed a `:slot` and now we have section composition tangled with wake dispatch.

---

## 2. The candidates

### 2.1 Actor model (Erlang/OTP, Akka, Pony)

**What it gives us:** each actor has a mailbox; a single fiber consumes messages; failures isolated; supervisors restart. The model is built around "agent = actor, state = per-actor, messages drive everything."

**Cost / mismatch:** Actors are *the* state. Seon's state lives in Datahike. We don't have per-actor mailboxes; we have a global tx log. An actor process per agent on top of Datahike duplicates ownership — the mailbox would be N tx-rows tagged by agent, with the actor pulling from it. That IS Datahike + a per-agent listener, just with extra OTP vocabulary.

Resumability requires actor state to be serializable + a deterministic re-create story. OTP does this with `init/1`. We get it for free from `:seon.agent` rows + `replay-program-graph!` (v1.md §7.4) — but the actor model adds nothing on top.

Where it earns its keep: **supervisor trees** as a vocabulary for "agent spawns sub-agent, sub-agent dies, parent decides retry/escalate." Worth borrowing the *naming* (supervisor, child spec, restart strategy) when v2/v3 adds sub-agents. Not the primitive itself.

**Verdict:** vocabulary contribution only. Don't model around actors.

### 2.2 CSP / core.async / core.async.flow

**What it gives us:** channels as the wake primitive. The agent fiber blocks on `<!`; external code `>!`s; agent un-parks. Flow adds graph topology.

**The JVM side already uses this.** `topology/request!` routes every cross-namespace call through a flow graph (per CLAUDE.md). The flow PRD (`docs/prds/unified-flow/design.md:8-23`) names the five primitives: Process, Connection, Flow, Inject, Ping. The pattern is mature.

**Cost / mismatch in the CLJS pod:**

- **No flow infra in the pod.** Today the CLJS pod uses native `^:async`/`await` for Promise interop; core.async go-blocks hang under `wstd` (per MEMORY.md `reference_cljs_async_await.md`). Adopting flow in the pod is a port project on its own — and the WASM target makes it worse (no Node `setTimeout` parking that the JVM go-block scheduler depends on).
- **Channels are volatile.** Pod restart drops the channel. We'd still need the DB as the authoritative queue, with channels as a wake-up signal — which means the *channel layer adds nothing over `db/listen!` notifying a per-agent dispatcher*.
- **The flow PRD targets cross-namespace JVM routing.** The agent-runtime problem is different scale: one-process, per-agent fiber loops over shared DB. Flow's "process per ns" model doesn't map cleanly.

Where it earns its keep: if/when the pod talks to OUT-of-pod services (Tauri host capabilities, JVM sidecar), a flow-shaped envelope (`::msg/fn`, `::msg/args`, ::msg/correlation-id`) is the right wire format. Already mostly there. Doesn't change the loop design.

**Verdict:** the wire format is already flow-shaped; don't reach for the flow runtime in-pod.

### 2.3 Reactive streams / signals (Solid, Vue, signals proposal)

**What it gives us:** fine-grained dependency tracking. Observer functions auto-re-run when read sources change.

**Where it overlaps Seon:** the section composer (v1.md §5.3 `assemble-ctx`) is **morally a reactive computation** — each section is a pure fn of the DB; we re-run them every turn. We don't have fine-grained dep tracking (each section re-reads everything), but Datahike queries against `:memory` are sub-ms (per `concepts/reactive-context:80`) so we don't need it.

**The critical insight from signals work:** *derivations and effects are different primitives*. A derivation (computed value) re-runs purely; an effect (DOM update, RPC, db write) runs when its sources change AND has to be scheduled / debounced / batched. Seon section render = derivation. Agent wake = effect. **Conflating them is the bug in the `:seon.trigger` sketch.**

**Cost / mismatch:** running real fine-grained reactivity in CLJS (Reagent-style atom tracking, or a Solid port) for the agent loop is massive overkill. The bookkeeping cost of dep-graph maintenance exceeds the savings vs. "re-run every section every turn." Datahike's tx-report is already a coarse-grained signal — we don't need finer.

**Verdict:** borrow the conceptual split (derivation vs effect, separate primitives, separate APIs) — already implicit in our architecture, just hadn't named it. Don't adopt reactive-stream libs.

### 2.4 Datomic tx-report-queue + materialized views

**What it gives us:** the database itself is the event log. A listener on the tx-report sees every commit. Effects fan out from there.

**Map to Seon:** this is exactly `d/listen!` (`seon.db/listen!`), already in use at agent.cljs:373. Each callback receives `{:seon.db/db <db-after> :seon.db/attr-index ...}` — the agent's wake handler today is exactly this pattern.

**Strengths:**

- **One source of truth** for "what happened." The DB IS the queue; replayability is free (`d/history`).
- **Survives pod restart.** Pod boots, runs `replay-program-graph!`, then opens `db/listen!`. Past events are visible via the DB itself; future events stream via the listener.
- **Multi-consumer fan-out is free.** N agents, N listeners (or 1 listener that dispatches to N handlers — see Lambda below).
- **Tx-meta is the dispatch context.** `:seon.db/agent-id`, `:seon.db/origin :user|:agent|:system|:replay` (v1.md §2.3 lines 464-470) is already there, already tells handlers where each tx came from. Cycle detection becomes: "skip handlers if `:seon.db/origin :replay`" — already designed.

**Cost / mismatch:**

- **Listener callbacks run on the transactor thread.** Long work in a callback blocks the next tx. Our existing handler hands off via `setTimeout 0` — correct.
- **Listener fan-out is in-process.** Cross-pod = future problem (sidecar/JVM split per `kabel-vs-sidecar-2026-05-24.md`).
- **No built-in backpressure.** If transacts arrive faster than handlers can dispatch, queue grows. Not a near-term concern (single-user, single-pod) but worth knowing.

**Verdict:** **this is the load-bearing primitive.** Everything else builds on it.

### 2.5 Elm Architecture / Redux

**What it gives us:** `(state, msg) -> (state, effects)`. Pure update fn. Effects executed by an interpreter, not by update. Replay = re-run the message log.

**Map to Seon — almost too cleanly:**

| Elm | Seon |
|---|---|
| `Model` | the Datahike DB |
| `Msg` | a transacted entity (`:seon.message`, `:seon.eval`, `:seon.async-result`, `:seon.trigger-fire`) |
| `update : Msg -> Model -> (Model, Cmd Msg)` | a handler fn that reads tx-report, returns `{:tx [...] :effects [...]}` |
| `Cmd` (side-effect descriptor) | a queued effect: LLM call, fs read, agent spawn, run-loop kick |
| message log | the tx log (free, bitemporal) |
| time-travel debugger | `d/as-of` over the same tx log (free) |

**Strengths:**

- **Effects are data.** `{:seon.effect/kind :llm-call :seon.effect/agent agent-id :seon.effect/prompt ...}` is a map a handler returns. An interpreter consumes the map and runs it. Async result becomes a transact of a new `:seon.async-result` entity → triggers the next update cycle. The loop is closed without the handler ever having to know about Promises directly.
- **Replay = re-run handlers over historical tx-reports.** This is what `replay-program-graph!` is gesturing at, but applied to the message stream instead of the def stream.
- **Pure update fns are testable.** No DB needed; pass a fake tx-report, assert on the returned `{:tx :effects}` map.

**Cost / mismatch:**

- **Strict purity is too strong.** Our handlers want to query the current DB to make decisions (turn cap, current ns, etc.). Elm-pure would mean stuffing all relevant state into the Msg envelope. Datahike makes that unnecessary: the handler gets `{:seon.db/db <db-after>}` and can query freely. We get the Elm shape without the FP rigor.
- **Effect interpreter is real code.** Has to handle async, error propagation, correlation IDs. Not free.

**Verdict:** **this is the right *shape* for the loop**: tx-report-as-msg → reducer → `{:tx :effects}` → interpreter executes effects → effects transact more rows → next tx-report. Adopt the *shape*; don't adopt Elm's strict purity.

### 2.6 Database triggers (Postgres LISTEN/NOTIFY, SQL triggers)

**What it gives us:** triggers are first-class DB objects; events fan out via a notification channel. Closest "DB-native" pattern to the `:seon.trigger` sketch.

**Strengths:**

- **Handler identity lives in the DB.** Restart-safe, queryable, mutable from inside the same SQL language that defines schemas. Maps to our `:seon.fn` entities (handler = a registered fn the agent has authored).
- **Match-spec is data.** Postgres triggers fire on `INSERT/UPDATE/DELETE` of specific tables; the match is declarative.

**Cost / mismatch:**

- **SQL triggers run inside the transaction.** Synchronous, blocking. We don't want that (handler might do an LLM call). The Datahike analog is `d/listen!` which is async post-commit — better.
- **Match-spec language has to be invented.** "Match on tx that adds `:seon.message/role :user` for agent X" is not a standard predicate language. The sketched `:seon.trigger/on` would become an ad-hoc DSL with all the maintenance cost.
- **The match cost runs on every tx.** With N triggers, every tx pays N match evaluations.

**The deeper issue:** the sketched trigger entity is doing handler indirection (good) AND match-spec dispatch (questionable). **Cleaner:** ONE listener per agent does the matching in plain CLJS (today's `user-message-handler` is exactly this — a `(filter)` over attr-index); the *handler* it dispatches to is identified by a fn symbol (look up via the existing `seon.fn` graph). No match-spec DSL needed.

**Verdict:** the goal (handler identity in DB) is right. The mechanism (match-spec entity) is over-engineered. **Use CLJS code for the match; use `:seon.fn` for the handler identity.**

### 2.7 Lambda / FaaS event sources (DynamoDB streams, Kinesis, SNS)

**What it gives us:** events fan out from a stream to handler functions. Handlers are stateless; state lives in the store. *Exactly Sean's trigger design, restated in AWS vocabulary.*

**Strengths:**

- **Pattern is mature, well-documented.** "Event source mapping" is the name for what we want.
- **Handler stateless-ness is enforced by the platform.** Forces good design.
- **Multiple handlers can subscribe to the same stream.** Fan-out is the default.

**Cost / mismatch:**

- **Cold-start / restart model is wrong scale.** Lambda handlers are JIT-instantiated per event; our agent is a long-running fiber.
- **Filter syntax (AWS EventBridge rule) is JSON match-spec.** Same critique as Postgres triggers: invented DSL we don't need.

**Verdict:** confirms the pattern is mainstream. Vocabulary borrow only ("event source", "filter", "fan-out") — implementation should be straight CLJS.

### 2.8 The original Seon unified-flow plan

`docs/prds/unified-flow/design.md` is **completed status** on the JVM side. It defines per-namespace processes with one step-fn each, routed via core.async.flow. It does NOT speak to the agent-loop wake problem — it's a cross-namespace RPC backbone, not an event-driven runtime.

**Two concrete relevance points:**

1. **Tx-meta envelope shape.** Flow's `::msg/fn`, `::msg/args`, `::msg/correlation-id` (design.md §2) is the right wire format for effects. The Elm `Cmd` we'd build maps onto this — same field names where they overlap. Don't re-invent.
2. **Error/report channels as separate streams.** Flow has one error-chan and one report-chan per graph. For Seon, we'd want errors and "agent reports" (telemetry-grade events) to flow back through DB transacts too — i.e., one mechanism, not three. The flow design doesn't conflict; it just operates at a different layer.

**Verdict:** doesn't answer the agent-loop question, and adopting flow in the CLJS pod is its own project. The wire-format influence is welcome.

---

## 3. Synthesis — what fits Seon

The shortlist after the survey:

1. **Datahike tx-report queue (2.4) is the bus.** Already shipping, already in use, free bitemporal replay.
2. **Elm-shaped update fn (2.5) is the handler contract.** Pure(-ish) `(db, tx-report) -> {:tx :effects}` makes handlers testable, replayable, and composable.
3. **Effects as data, executed by an interpreter.** Borrowed from Elm. Allows fire-and-forget async (the handler returns `{:effects [{:kind :llm-call ...}]}`, the interpreter runs the Promise, on resolve the interpreter transacts a `:seon.async-result` row, which fans out via (1) again).
4. **Reactive-context principle (2.3 + existing `concepts/reactive-context`) stays the rendering contract.** Sections are pure fns of the DB; no wake involvement; no entity-per-section is strictly required (though the current `:seon.ctx` priority/symbol entities are fine — they're configuration, not dispatch).
5. **Per-agent dispatcher** owns the state machine (`:idle ↔ :running ↔ :paused`) and the single fiber. The dispatcher IS the actor (2.1's only real contribution).

What we DROP from the trigger sketch:

- **`:seon.trigger/on` match-spec.** No DSL; the per-agent listener filters in CLJS (one fn per kind of wake event, registered by name).
- **`:seon.trigger/slot`.** Section render stays a pure derivation; doesn't need wake.
- **`{:ctx ... :wake? ... :tx ...}` return envelope conflating render and wake.** Two separate return shapes: render fn returns a string; effect handler returns `{:tx :effects}`.

What we KEEP from the trigger sketch:

- **Handler identity is data in the DB.** `:seon.fn/sym` already gives us this — handlers are just functions, persisted via detect-and-tee, resurrected via `replay-program-graph!`. **Registering a handler = transacting a row that says "this fn handles this event kind for this agent."** A `:seon.agent.handler` entity, scoped to an agent, naming an event kind and a fn-symbol.
- **Same primitive across substrate, agent customization, cross-agent visibility.** Substrate ships default handlers (user-message → run-loop kick); agent can transact additional handlers (`:seon.async-result/kind :llm-tool-call` → fold tool result into context); cross-agent handlers are just handlers scoped to `:seon.agent/id <other>` or unscoped.

---

## 4. The recommended design

### 4.1 Schema sketch

```
;; Wake / effect events — one entity per event arriving on the bus.
;; Existing :seon.message is one kind; new kinds use sibling namespaces.
::seon.async-result/id        [:string {:seon.db/identity true}]
::seon.async-result/agent     :seon.db/ref           ; → :seon.agent
::seon.async-result/kind      :keyword               ; :llm-tool/done, :spawn/done, etc.
::seon.async-result/correlation-id :string {:optional true}
::seon.async-result/payload   :string                ; pr-str / EDN string
::seon.async-result/at        :inst

;; Handler registration — handler identity, scoped, declarative.
;; (Substitute / superset of the trigger entity in the original sketch.)
::seon.handler/id             [:string {:seon.db/identity true}]
::seon.handler/agent          :seon.db/ref {:optional true}   ; nil = global
::seon.handler/event-kind     :keyword                         ; :user-message, :async-result, :tool-result, …
::seon.handler/fn             :symbol                          ; ns-qualified, resolved at dispatch
::seon.handler/priority       :long {:optional true}

;; Agent state enum gains :paused (per turn-as-unit-2026-05-25 §Q5).
::seon.agent/state            [:enum :idle :running :paused]

```

No match-spec on the handler; the handler fn itself decides whether the tx is interesting to it. `:event-kind` is a coarse pre-filter that lives in code (the dispatcher's `case` on `:kind`) — handler entities for a given kind get called in priority order.

### 4.2 Dispatcher contract

The per-agent dispatcher (replaces `user-message-handler`) registered once per agent via `db/listen!` keyed `[::agent-dispatch <agent-id>]`:

```
(defn agent-dispatcher [{:seon.agent/keys [id] :as input}]
  (fn [{:seon.db/keys [db attr-index]}]
    (let [events  (extract-events db attr-index id)  ; pure
          ctx     {:seon.db/db db :seon.agent/id id}
          {:keys [tx effects wake?]}
          (reduce (fn [acc event]
                    (let [handlers (lookup-handlers db id (:event/kind event))]
                      (reduce (fn [a h] (merge-effects a (h ctx event)))
                              acc handlers)))
                  {:tx [] :effects [] :wake? false}
                  events)]
      (when (seq tx)      (await (db/transact! {:seon.db/tx-data tx
                                                :seon.db/opts {:tx-meta {:seon.db/origin :handler}}})))
      (when (seq effects) (run-effects! input effects))
      (when (and wake? (not= :running (current-state db id)))
        (js/setTimeout #(db/with-agent id (fn [] (run-agentic-loop! input))) 0)))))

```

`extract-events` is pure: scans `attr-index` for added datoms of known kinds (`:seon.message/role`, `:seon.async-result/kind`, etc.), returns a normalized seq of `{:event/kind … :event/eid …}`. Maps directly onto the existing pattern at agent.cljs:339-341 — just generalized beyond `:seon.message/role`.

`run-effects!` is the Elm interpreter: it knows how to execute `{:kind :llm-call ...}`, `{:kind :spawn-agent ...}`, `{:kind :run-test ...}`. Async effects' completions land back as transacts of `:seon.async-result`, completing the loop via (1).

### 4.3 Answers to the explicit questions

- **Wake mechanism**: (c) tx-listener → per-agent dispatcher → conditional `run-agentic-loop!`. NOT (a) wake-queue table (redundant — the tx log IS the queue), NOT (b) channel block (volatile, doesn't survive restart). The current `setTimeout 0` re-entry is correct and stays.
- **Section composition vs wake**: **different primitives**. Sections are pure DB queries; wake is effect dispatch on tx-report. The trigger-as-one-thing sketch was wrong here. Reactive-context already nailed sections; don't break it.
- **External async results**: **transact a `:seon.async-result` row** when the Promise resolves; the dispatcher fan-out handles it like any other event. Optional fast path: a Promise the originating handler can `await` directly (correlation-id stored in the effect descriptor); useful when the originating turn is still in-flight. But the transact path is the *spine* — it survives the originating turn closing.
- **Cycle prevention**: tx-meta `:seon.db/origin` enum already has `:replay`; add `:handler` for handler-emitted tx. Dispatcher skips events from `:seon.db/origin :handler` for handlers that have `:seon.handler/skip-self? true` (default true). Plus a hard depth-guard counter in a fiber-local (ALS) to abort runaway chains. Most handlers will only respond to `:user`, `:agent`, or `:async-result` origins anyway.
- **Multi-agent on shared DB**: one listener per agent, all registered against the same tx-report fan-out. Datahike's `d/listen!` is multi-callback by design. N agents = N callbacks per tx, each filtering for its own agent. With N=10 this is trivial; if it ever becomes an issue, a single tenant-aware dispatcher can replace N listeners — the contract doesn't change.
- **Resumability**: pod restarts. Boot order:
  1. `replay-program-graph!` (existing) — analyzer + handler fns are alive again.
  2. Walk `:seon.handler` entities; for each, resolve `:seon.handler/fn` symbol; install the dispatcher per agent.
  3. Detect interrupted turns (`:status :running` left over) → flip to `:interrupted`, transact a system message (v1.md §7.4 plan, unchanged).
  4. Resume listener; new txs fire dispatcher normally; system message above triggers user-message-style wake; loop resumes.

### 4.4 Where the existing code already matches this design

- Tx-listener registration (agent.cljs:360–375): pattern stays, generalize from `user-message-handler` to `agent-dispatcher`.
- State-machine guard (agent.cljs:346): stays.
- `setTimeout` re-entry under `with-agent` (agent.cljs:356–357): stays.
- Tx-meta origin tagging (v1.md §2.3): stays; add `:handler` to the enum.
- Reactive section render (v1.md §5, `concepts/reactive-context`): **untouched.** This is the major bug-fix vs the trigger sketch.
- `replay-program-graph!` (v1.md §7.4): naturally extends to also install handlers (handler fns are just `:seon.fn` rows).

### 4.5 What stays out for now

- **Custom match-spec DSL.** Use plain CLJS in `extract-events`.
- **Sub-agent supervisor trees.** v2/v3 problem.
- **Channel-based wake.** Not needed.
- **Removing `:seon.ctx` section entities.** They configure render layout (priority, fn-symbol); that's fine. Keep them.

---

## 5. What Sean needs to confirm

1. **The split: derivation (render) vs effect (wake) is two primitives, not one.** This is the load-bearing claim. If Sean still wants one unified mechanism, this whole recommendation flips and we have to re-evaluate.
2. **`:seon.async-result` is the right name / shape** for "an external async thing completed; agents should react to it." Could be split per-kind (`:seon.llm-result`, `:seon.tool-result`, etc.) instead — open call.
3. **`:seon.handler` as a real entity** (vs leaving handlers process-only and re-installed on every boot from a hardcoded substrate list). Recommend the entity; it lets agents add handlers via REPL and survives restart via the existing code-as-data mechanism. But it does add one more entity class.
4. **Cycle-guard policy.** Recommend `:seon.db/origin :handler` tx-meta + handlers default-skipping their own origin + a fiber-local depth counter (max 16). Cheaper alternatives exist (always require explicit subscribe to handler-origin events).
5. **Per-agent listener vs single tenant-aware listener.** Recommend N listeners (one per agent) for clarity; collapse later if fan-out cost becomes real.

---

## Cross-references

- `src/seon/agent.cljs:321-375` — current kick listener (the prototype of the dispatcher).
- `src/seon/agent.cljs:663-706` — `run-agentic-loop!` (the fiber, unchanged).
- `docs/prds/agent-runtime/v1.md` §2.3 — tx-meta enum (extend with `:handler`).
- `docs/prds/agent-runtime/v1.md` §5–§5.3 — section composer (stays purely derivational).
- `docs/prds/agent-runtime/v1.md` §7.4 — resume / interrupted-turn handling.
- `docs/prds/agent-runtime/research/turn-as-unit-2026-05-25.md` — `:paused` enum addition + kick-handler skip.
- `docs/seon/concepts/reactive-context.md` — derive-not-store rule; sections-as-fns-of-db.
- `docs/seon/concepts/code-as-data-runtime.md` — handler fns ARE `:seon.fn` rows; one mechanism viewed many ways.
- `docs/prds/unified-flow/design.md` §1–§2 — flow primitive reference; envelope shape borrow.
