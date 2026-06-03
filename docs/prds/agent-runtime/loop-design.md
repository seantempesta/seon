---
type: prd
status: draft
tags: [prd, agent]
---

# Agent runtime loop — dispatcher + handlers + effects

The agentic loop has converged. This PRD locks the shape: **two
primitives (sections and handlers), one bus (`d/listen!` on the
tx-report queue), effects as data**. Restart-safe, agent-authorable,
cross-agent-by-default, no separate event system.

This document is a SPEC. Where v1.md still describes the
session/turn machinery in code we have since simplified, this PRD
supersedes those sections. Companion visualization:
[loop-design.html](loop-design.html) walks five live scenarios.

> **Status: draft.** Implementation lives on `feature/agent-runtime`.
> Every claim about handler semantics, effect descriptors, and tx-meta
> propagation is verifiable via REPL probes in the existing CLJS pod
> — verify before committing structural changes.

## 1. Problem statement

The agent runtime must:

- Drive a **state machine per agent** (`:idle ↔ :running ↔ :paused`)
  with a single in-fiber writer at any time.
- React to **async events** from heterogeneous sources: incoming user
  messages, LLM responses that resolve after the originating turn
  closed, MCP/tool results, sub-agent spawns, pod restarts mid-turn.
- Support **multiple agents in one pod** sharing one Datahike conn,
  each making independent progress, each able to see (and react to)
  state written by another agent.
- Survive **pod restart** — agents that were `:running` when the
  process died resume on the next boot without losing causal
  continuity (the user shouldn't have to re-send the message).
- Stay **observable** — every wake, every effect, every transact is
  forensically attributable via tx-meta and queryable from the same
  REPL the agent uses.
- Be **self-extensible** — an agent can transact a new handler entity
  mid-turn and have it take effect on the next tx.

What v1.md already nailed: render is `fn(db) → string`; the message
log is the conversation; the eval log is the program. What v1.md left
under-specified: how wake actually works once the loop has to
respond to more than just user messages, and how the runtime keeps
handler-emitted txs from chasing each other forever.

## 2. Two primitives: sections vs handlers

These are distinct. Conflating them was the bug in earlier sketches.

### Sections — derivation

A **section** is `(db, ctx-input) → string`. Pure, always-on,
cached only as a perf escape hatch. Rendered into the agent's
prompt on every turn. Registered as a `:seon.ctx` entity on
`:seon.agent/ctx` (priority + fn-symbol); composer pulls, sorts,
calls, joins. Detailed in v1.md §5 and
[[../../seon/concepts/reactive-context]] — unchanged.

Sections do NOT trigger anything. They are read-only. When the
underlying state goes away, the section renders empty and the
surface vanishes — no acknowledgement, no clearing, no
notification.

### Handlers — dispatch

A **handler** is `(db, tx-report, agent-id) → {:tx [...] :effects [...]}`.
Fires when matching datoms land in a committed tx. Returns
declarative data: more txs to write, more effects to execute.

Handler **identity** is a `:seon.handler` entity (DB-resident,
restart-safe, agent-authorable). The handler **function** is a
plain CLJS fn discovered by symbol — resolved at dispatch time
through the same `lookup-value` path sections use. Resume rebuilds
the fn population via `replay-program-graph!`; the entity tells
the dispatcher which fns are live handlers and what they match on.

**Why two primitives, not one:** sections re-render every turn no
matter what changed; handlers fire exactly when their predicate
matches a tx's added datoms. Sections answer "what should the agent
see right now?". Handlers answer "what should the runtime do
because something just happened?". They live on different time bases
and have different return shapes; collapsing them produced the
self-trigger loops earlier sketches got tangled in.

## 3. Schemas

All attrs registered via `seon.schema/register!`. Namespaced
keywords throughout. No `:any`. Optional = absent. CLAUDE.md data
rules hold.

```clojure
;; --- Agent (unchanged from v1, plus :paused) -----------------------

(schema/register! :seon.agent/id    [:string {:min 12 :max 12
                                              :seon.db/identity true}])
(schema/register! :seon.agent/state [:enum :idle :running :paused])
(schema/register! :seon.agent/turns ; component-many ref to :seon.turn
                  [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! :seon.agent/ctx
                  [:vector {:seon.db/component true} :seon.db/ref])

;; --- Message — the mailbox primitive -------------------------------
;; Everything reactive that requires the agent to *reason* is a
;; message. Always-on background state (env tick, system stats) is
;; sectional, not a message.

(schema/register! :seon.message/id      [:string {:min 12 :max 12
                                                  :seon.db/identity true}])
(schema/register! :seon.message/from    :seon.db/ref) ; → agent / :user / :system sentinel
(schema/register! :seon.message/to      [:vector :seon.db/ref])
(schema/register! :seon.message/at      :inst)
(schema/register! :seon.message/role    [:enum :user :assistant :system])
(schema/register! :seon.message/content :string)
(schema/register! :seon.message/refs    [:vector :seon.db/ref])      ; data in scope for the recipient
(schema/register! :seon.message/in-reply-to :seon.db/ref {:optional true})

;; --- Handler — DB-resident dispatch identity -----------------------

(schema/register! :seon.handler/id        [:string {:min 12 :max 12
                                                    :seon.db/identity true}])
(schema/register! :seon.handler/agent     :seon.db/ref {:optional true}) ; nil = tenant-wide
(schema/register! :seon.handler/name      :keyword)                       ; humans / queries
(schema/register! :seon.handler/match     [:map
                                           [:seon.match/attr  :keyword]
                                           [:seon.match/value {:optional true} :any-scalar]])
(schema/register! :seon.handler/fn        :symbol)                        ; ns-qualified
(schema/register! :seon.handler/on-origin [:set [:enum :user :agent :system :handler :replay]]
                                          ; default in code: #{:user :agent :system}
                                          {:optional true})
(schema/register! :seon.handler/priority  :long {:optional true})

;; --- Async result — the "external thing finished" envelope ---------

(schema/register! :seon.async-result/id    [:string {:min 12 :max 12
                                                     :seon.db/identity true}])
(schema/register! :seon.async-result/agent :seon.db/ref)
(schema/register! :seon.async-result/kind  :keyword)               ; :llm/done, :tool/done, :spawn/done
(schema/register! :seon.async-result/corr  :string {:optional true}) ; correlation id from the originating effect
(schema/register! :seon.async-result/payload :string)              ; pr-str EDN
(schema/register! :seon.async-result/at    :inst)

;; --- Eval — turn linkage is implicit, via :from-message ------------
;; No :seon.turn entity. "Current turn" = "evals whose
;; :from-message is the latest assistant message".

(schema/register! :seon.eval/from-message :seon.db/ref) ; → :seon.message of :role :assistant
;; All other :seon.eval/* attrs from v1.md §2.1 unchanged.

```

`:seon.match/attr` + optional `:seon.match/value` is intentionally
dumb. V1 of the dispatcher matches "did this tx add a datom for
attr X (and optionally with value V)?". When richer matching is
needed (datalog predicates, attribute conjunctions), `:match`
becomes a richer schema — the handler entity stays the same shape.
Do not invent a DSL ahead of need.

## 4. The dispatcher

One in-process Datahike `d/listen!` registration **per pod**
(tenant-aware), keyed `[::seon.runtime/dispatch]`. Replaces the
N-listeners-per-agent pattern. Single tx-report fanout, indexed
walk over `:seon.handler` entities.

Sequence per committed tx:

1. **Tx commits.** `d/transact!` resolves; tx-report enters the
   listener queue.
2. **Listener invokes the dispatcher** with `{:db :db-after :tx-data :tx-meta}`.
3. **Dispatcher reads `(:seon.db/origin tx-meta)`.** Default rule:
   if origin is `:handler` AND the handler entity's
   `:seon.handler/on-origin` does not include `:handler`, skip the
   handler. Prevents trivial self-loops.
4. **Walk `:seon.handler` entities.** Pulled once and indexed by
   `:seon.match/attr` (per-attr cache; invalidated when a
   `:seon.handler` entity is added/retracted). For each added datom
   in `:tx-data`, look up handlers whose `:match/attr` matches; if
   `:match/value` is set, filter by `=`; for each surviving handler,
   evaluate `:seon.handler/agent` scope (nil = all agents; otherwise
   only the matching agent).
5. **Invoke matched handlers in priority order.** Each handler
   receives `{:seon.db/db <db-after> :seon.db/tx-report <report> :seon.agent/id <id>}`
   and returns `{:tx [...] :effects [...]}` (either may be absent).
6. **Apply `:tx`** as a single `db/transact!` with
   `:tx-meta {:seon.db/origin :handler :seon.db/agent-id <id>}`.
   This commit fires the listener again with `origin :handler` —
   the default skip rule keeps it from cycling unless explicitly
   opted in.
7. **Apply `:effects`** by handing each map to the effect
   interpreter (§5). Effects execute outside the listener thread
   (via `js/setTimeout 0` or `await` as appropriate to the kind).

The dispatcher itself is synchronous from the listener's
perspective — `:tx` is committed before returning, `:effects` are
enqueued for the event-loop tick. No promise chains escape the
listener callback.

**Fiber-local depth counter.** The dispatcher wraps each invocation
in an ALS scope carrying `:seon.runtime/handler-depth`. If a handler-
origin tx triggers another handler whose own `:tx`/`:effects` would
trigger further handlers, the depth counter increments. At depth >
16 the dispatcher refuses to proceed and transacts a
`:seon.runtime/depth-exceeded` warning entity. Defense-in-depth
behind the origin-skip rule.

## 5. Effect catalogue

Effects are **data**, not function calls. The interpreter
(`seon.runtime/run-effect!`) is a multimethod on `:effect/type`.
Adding an effect kind is adding a multimethod method + an entry to
this catalogue. The handler that emits an effect knows nothing
about Promises, Node APIs, or the DB conn.

| `:effect/type` | Required keys | Runtime behavior |
|---|---|---|
| `:wake` | `:agent` (agent id) | If agent state is `:stopped`, flip to `:running` and invoke `(run-agent-loop! {:agent-id agent})`. If already `:running`, no-op. If `:paused`, no-op. |
| `:run-llm` | `:agent`, `:request` (map with `:model :messages` etc.), `:corr` (correlation id) | Runtime invokes the configured LLM client. On resolve, transacts `{:seon.async-result/kind :llm/done :corr corr :agent agent :payload <pr-str-response>}`. On reject, transacts the same with `:kind :llm/error` and the error as payload. The result-arrival handler picks it up. |
| `:spawn-agent` | `:parent` (agent id), `:kind` (keyword), `:initial-message` (string), `:refs` (vector of refs in scope) | Runtime mints a child `:seon.agent/id`, transacts the agent entity, attaches the initial-message as `:seon.message/from <parent> :to [<child>]`. The wake-on-message handler fires for the child; child's first turn renders the message + refs. |
| `:tx` | `:tx-data` (vector) | Recursive transact, tagged `:seon.db/origin :handler`. Subject to the origin-skip rule on downstream handlers. Useful for follow-up writes a handler wants to be a separate commit. |
| (future) `:fetch-url` | `:agent`, `:url`, `:corr` | HTTP fetch through `seon.fs`-style capability gate; result transacted as `:seon.async-result/kind :http/done`. Not in v1. |
| (future) `:read-file` | `:agent`, `:path`, `:corr` | Same pattern, fs capability gate. Not in v1. |

**Why effects are descriptors, not closures:** a closure carries
captured state from the moment it was created. A descriptor is
inert data — it can be persisted, logged, replayed, inspected from
the REPL, or refused by the runtime if the agent lacks the
capability. The dispatcher and the handler stay pure-ish; the
interpreter is the one place that holds the messy mechanics
(Promises, timeouts, capability checks).

## 6. Cycle prevention + depth guard

Two layers:

1. **Origin-skip default.** Every handler-emitted tx carries
   `:tx-meta {:seon.db/origin :handler}`. The dispatcher's default
   policy is **do not fire handlers on `:handler`-origin txs**.
   Handlers that genuinely want to chain (e.g., a handler that
   reacts to another handler's normalization output) must opt in
   via `:seon.handler/on-origin #{:agent :handler}`. The vast
   majority of handlers never set this and never participate in
   chains.

2. **Fiber-local depth guard.** Even with opt-in chaining, the
   dispatcher caps total handler depth in a single causal chain at
   16. The counter lives in ALS so concurrent agents have
   independent counters. At cap, the dispatcher transacts a
   `:seon.runtime/depth-exceeded` warning and stops; the warning
   surfaces in the next render via a section query.

Handler authors who want to "ping-pong" between two handlers must
acknowledge it explicitly via `on-origin`. There is no implicit
chain. This is the corollary of the reactive-context principle: if
two things should converge, the convergence is queried, not
notified.

## 7. Resumability

Pod restart is observed as: process dies, LMDB on disk persists,
new process opens conn, datoms are intact. Resume sequence:

1. **`replay-program-graph!`** (v1.md §7.4) — bulk-load each ns's
   reconstituted source. Handler fns become live again.
2. **Install dispatcher.** Single `d/listen!` registration; build
   the per-attr handler index from `:seon.handler` entities.
3. **Detect interrupted agents.** Query `:seon.agent/state :running`.
   For each, transact:
   `{:seon.message/from :system :to [<agent-id>] :role :system
     :content "Pod restarted mid-turn. Your prior thinking may be
     incomplete. Decide what to do."}`. Origin `:system`. This is a
   real tx; the wake-on-message handler fires; the agent resumes
   via its normal next-render path. No special interrupt code in
   the agent loop.
4. **Resume listener.** Future txs fire the dispatcher normally.

Target: from boot to "agent has a system message and the wake
handler has fired" in < 200ms for an agent population of 10. The
slow part is `replay-program-graph!`; once handlers are reinstalled
the bus is hot.

## 8. Substrate-shipped handlers

The substrate ships three default handlers, transacted into the
`:seon.handler` table at boot (idempotent via identity-attr upsert):

| `:seon.handler/name` | `:match` | Effect |
|---|---|---|
| `:wake-on-message-to-agent` | `{:attr :seon.message/to :value <agent-id>}` (one entity per agent) | `{:effect/type :wake :agent <agent-id>}` |
| `:route-async-result` | `{:attr :seon.async-result/agent :value <agent-id>}` (one per agent) | `{:effect/type :wake :agent <agent-id>}` |
| `:surface-eval-error` | `{:attr :seon.eval/error-data}` | None. Rendering happens via the error-section query on the next render. Present so handlers are the documented place to extend if/when an error needs an effect (e.g., auto-spawn a debug agent). |

Per-agent install at agent-creation time: when `seon.agent/create!`
mints a new `:seon.agent/id`, it also transacts the per-agent
copies of `:wake-on-message-to-agent` and `:route-async-result`
keyed to the new id.

## 9. Per-agent customization

An agent transacts a new handler from inside a turn the same way it
transacts anything else:

```clojure
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.handler/id    (seon.db/new-id!)
     :seon.handler/agent [:seon.agent/id (seon.agent/id)]
     :seon.handler/name  :auto-rerun-failed-test
     :seon.handler/match {:seon.match/attr  :seon.test/status
                          :seon.match/value :failed}
     :seon.handler/fn    'my.handlers/rerun-failed-test
     :seon.handler/priority 50}]})

```

The handler-index cache invalidates on `:seon.handler` writes; the
new handler is live on the next tx. The fn `'my.handlers/rerun-failed-test`
must already exist (or be defined in the same eval batch) — same
discovery path as section fns.

Retracting a handler: `[:db/retractEntity <handler-eid>]`. Cache
invalidates. Tomorrow's run looks the same as if the handler had
never existed.

This is the agent-authorable extension surface. Substrate handlers
ship as data; agent handlers are written as data; the dispatcher
treats them identically.

## 10. Acceptance criteria

1. **Single user message → single turn → stop.** User sends one
   message; wake handler fires; agent renders, calls LLM, evals one
   form, emits narration-only second response, dispatcher detects
   zero forms + no pending effects, flips agent to `:stopped`.
   `:seon.agent/state` is `:stopped` within 50ms of the narration
   tx committing.
2. **Two agents converse.** Agent A sends a message to agent B
   (`:seon.message/to [B]`). B's wake-on-message handler fires, B
   renders the message via its messages-section query, replies to
   A. A's wake handler fires on the reply. Both threads visible
   from a single `(d/pull db ... :seon.agent/id "A")` walking
   `:seon.agent/_to` reverse-refs.
3. **Async LLM completion after turn close.** Agent issues
   `:effect/type :run-llm` mid-turn, finishes the rest of the turn
   with only narration, stops. 3s later the LLM Promise resolves;
   the runtime transacts `:seon.async-result/kind :llm/done`;
   `:route-async-result` fires; agent wakes and renders with the
   result available in its `recent-async-results` section.
4. **Pod restart resumes interrupted agent.** Agent state is
   `:running`; pod is killed; pod boots; within 200ms the boot
   sequence has transacted a system message and the wake handler
   has fired. From the agent's perspective, the next render shows
   the system-restart message in its messages section; it decides
   how to proceed.
5. **Eval error surfaces without a handler firing.** Agent evals a
   form that throws; the error lands as `:seon.eval/error-data`;
   no effect fires; the next render's error-section query picks up
   the error and includes it in the prompt; the agent reasons
   about it and emits a fixed form on the next turn. Then the
   error-section query returns empty and the surface vanishes.
6. **Cycle guard fires.** A test handler installed with
   `:on-origin #{:agent :handler}` and a fn that always emits
   another `:tx` is triggered; the depth counter caps at 16; a
   `:seon.runtime/depth-exceeded` warning entity appears; no
   further handler invocations occur for that causal chain.
7. **Per-agent handler authoring.** Agent transacts a handler
   inside a turn; on the next tx that matches, the new handler
   fires. Retract the handler; on the subsequent matching tx, it
   does not fire.

## 11. Non-goals (v1)

- **Distributed multi-pod dispatch.** The dispatcher is single-pod.
  Future work: cross-pod via the WIT-typed sidecar boundary or a
  flow port if/when the CLJS pod gains flow. Today's design lives
  cleanly inside one wasmtime instance.
- **Backpressure / queue depth control.** If transacts arrive faster
  than handlers can drain, the listener queue grows. Acceptable for
  single-user, single-pod, agent population ≤ 10. Revisit when
  the substrate hosts a UI with high-frequency input.
- **Cross-WASM-instance routing.** Same constraint — defer to flow
  port. (Flow port itself is also deferred; see
  [[research/cljs-flow-port-feasibility-2026-05-25]].)
- **Retry / backoff policies for failed effects.** A failed
  `:run-llm` transacts an `:llm/error` async-result; the handler
  decides whether to retry. No substrate-level retry loop. Agents
  that want retry author a handler for `:llm/error`.
- **Match-spec DSL.** Attr + scalar value only in v1. Datalog-
  predicate matching is plausible but unproven; defer until a real
  handler needs it.

## 12. Migration from current code

Concrete changes from current `feature/agent-runtime`:

- **`src/seon/agent.cljs:336–358` (`user-message-handler`)** —
  generalize from "match `:seon.message/role :user`" to "dispatch
  via `:seon.handler` walk". Becomes `seon.runtime/dispatcher` in a
  new `seon.runtime` namespace. The current setTimeout-under-ALS
  re-entry pattern (lines 356–357) survives intact — that's the
  `:effect/type :wake` interpreter.
- **`src/seon/agent.cljs:360–375` (`install-user-trigger!`)** —
  collapse to `install-dispatcher!`, called once per pod, not once
  per agent. Per-agent handler entities are transacted by
  `create!` instead.
- **`src/seon/agent.cljs:489–522` (`current-session` /
  `start-session!` / `ensure-session!`)** — delete. Turn linkage
  to messages is implicit via `:seon.eval/from-message`.
- **`src/seon/agent.cljs:597–661` (`run-turn!`)** — drop
  `ensure-session!` plumbing; turn entity stays as the
  prompt-text + status + agent-state-transition vehicle. Implicit-
  turn derivation is a separate query (`evals-of-current-turn`).
  Stop condition becomes: zero forms emitted AND no effects queued
  by the post-eval handler walk → transact `:seon.agent/state :stopped`.
- **`src/seon/agent.cljs:663–706` (`run-agentic-loop!`)** —
  shrinks to "call `run-turn!`; if eval-batch returned zero forms
  AND the dispatcher reports no queued effects for this agent,
  stop". The "loop until policy fires" pattern goes away — the
  agent stops at end-of-turn; the next external event re-wakes via
  the wake handler.
- **`src/seon/eval.cljs`** — `eval-batch!` already attaches evals
  to a turn (the `:tee` arg at ~line 709). Add `:seon.eval/from-message`
  pointing at the assistant message that emitted the batch — one
  new ref, no structural change.
- **`src/seon/client.cljs`** — bootstrap-attrs gain
  `:seon.handler/*` and `:seon.async-result/*`. The stub-llm path
  becomes an `:effect/type :run-llm` consumer (interpreter side),
  not a direct fn call from inside `ask-and-eval!`.
- **New: `src/seon/runtime.cljs`** — houses the dispatcher,
  handler-index cache, effect interpreter (multimethod on
  `:effect/type`), substrate-default-handler-install at boot.

The eval log, the program graph, and the section composer are
unchanged. Sections continue to render strings from queries; the
new schemas extend what's queryable but don't alter the rendering
contract.

## 13. Open questions

1. **Should `:seon.handler/match` accept a datalog query?** v1 is
   attr+value only. Real handlers may want "match when entity has
   both attr X and attr Y". Cheapest extension: a `:where` clause
   evaluated per added eid. Defer until a substrate handler
   genuinely needs it.
2. **Effects with capability gates** — `:fetch-url` and `:read-file`
   need the same default-deny allowlist that `seon.fs` uses. Where
   does the allowlist live for effects? Per-agent on
   `:seon.agent/capabilities`? Per-pod in config? Likely per-agent;
   confirm before shipping any non-trivial effect kind.
3. **Async-result garbage.** Long-lived agents accumulate
   `:seon.async-result` entities. Are they renderable history or
   garbage? Probable answer: history (the agent might want to see
   what tool calls completed in the last hour), with a janitor
   handler that retracts ones older than N hours. Defer the
   janitor.
4. **Stop-condition interaction with effects-in-flight.** If a
   handler issued `:run-llm` and we stop the agent at end-of-turn,
   the LLM Promise is still pending; when it resolves we wake the
   agent. Correct. But what if the user pauses the agent in
   between? The async-result still lands; the `:route-async-result`
   handler sees `:paused` and… enqueues? drops? We recommend "land
   the async-result row, leave the agent paused; on unpause the
   next render shows the result via a section query". Confirm.
