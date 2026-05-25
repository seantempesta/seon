---
type: prd
status: draft
tags: [prd, agent, runtime]
---

# Unified agent runtime loop — one verb, one entity, one bus

Supersedes the dispatcher/handler/effects sections of
[loop-design.md](loop-design.md) (§2 handler half, §3 handler schema, §4
dispatcher, §5 effect catalogue, §6 cycle guard, §8 substrate handlers, §9
per-agent customization). The sections it does NOT supersede — derivational
sections (§2 sections half), resumability (§7), migration (§12), acceptance
(§10) augmented — are noted inline.

The shift: **registering a handler is the same shape as registering a schema,
and a handler entity is just data on the same bus everything else uses.** One
verb (`handler/register!`), one entity (`:seon.handler`), one bus
(`d/listen!` on the tx-report). Effects-as-data stays; the rest is pruned
hard.

## 1. The verb

```clojure
(schema/register!  ::msg-content :string)
(handler/register! ::wake-on-message {:attr :seon.message/to}
                   'seon.runtime/wake-on-message)
```

Same shape, same place in the file, same lifecycle. `handler/register!` takes:

1. **A namespaced keyword** — the handler's identity (`:seon.handler/name` in the entity).
2. **A match map** — `{:attr :keyword}` or `{:attr :keyword :value <scalar>}`.
3. **A fn-symbol** — qualified; resolved through `seon.eval/lookup-value` (same path sections use).
4. **Optional opts map** — `{:seon.handler/agent <ref> :seon.handler/priority N :seon.handler/on-origin #{:user :agent :system}}`.

Internally `register!` does one `db/transact!` of a `:seon.handler` map.
Identity-attr upsert means re-registering replaces. That's the substrate side.

An agent registers an in-turn handler the same way (it's the same fn):

```clojure
(seon.handler/register! :my.handlers/rerun-failed-test
                        {:attr :seon.test/status :value :failed}
                        'my.handlers/rerun-failed-test
                        {:seon.handler/agent [:seon.agent/id (seon.db/current-agent-id)]
                         :seon.handler/priority 50})
```

Substrate-shipped vs agent-authored handlers are **identical except for
`:seon.handler/agent` presence**.

## 2. The entity

```clojure
;; --- The match shape — registered once so other handlers can reference it
(schema/register! :seon.handler/match
  [:map [:seon.match/attr :keyword]
        [:seon.match/value {:optional true} :seon.handler/match-value]])

;; The match-value is whatever scalar Datahike can store: string, keyword,
;; int, inst, uuid, ref (lookup-ref tuple). NOT :any.
(schema/register! :seon.handler/match-value
  [:or :string :keyword :int :inst :uuid :seon.db/ref])

;; --- The handler entity ---------------------------------------------------
;; Identity is the COMPOSITE [name agent]. Substrate handlers have agent nil
;; and form one row each. Agent-scoped handlers form one row per (name,agent).
(schema/register! :seon.handler/name     :keyword)            ; namespaced kw
(schema/register! :seon.handler/agent    [:maybe :seon.db/ref]) ; nil ⇒ substrate-wide
(schema/register! :seon.handler/key
  [:tuple {:seon.db/identity true} :keyword [:maybe :seon.db/ref]])
;; (Composite tuple identity; see open question §6 if Datahike requires a
;; scalar identity instead — fall back to the deterministic hash of the
;; tuple as the identity string.)

(schema/register! :seon.handler/match     :seon.handler/match)
(schema/register! :seon.handler/fn        :symbol)
(schema/register! :seon.handler/priority  :long {:optional true})  ; default 0
(schema/register! :seon.handler/on-origin
  [:set {:default #{:user :agent :system}}
        [:enum :user :agent :system :handler :replay]])
```

That's the whole schema for handlers. **No `:seon.handler/slot`.** **No
`:seon.handler/id` separate from `:name`.** **No effect-catalogue schema
beyond `:effect/type`** (effects are open-shape maps validated per kind by
their multimethod method's `:malli/schema` metadata — same pattern as the
renderer).

## 3. Sections vs handlers — kept distinct, share machinery

Sections stay derivational (see [reactive-context](../../seon/concepts/reactive-context.md)). A
section is a fn whose `:malli/schema` output is `:seon.render/ai-response`
(or `/html-response`). The renderer's data-shape dispatch already discovers
them via the program graph — no `:seon.handler` row needed.

A handler is a fn whose output schema is `:seon.handler/result`:

```clojure
(schema/register! :seon.handler/result
  [:map [:tx      {:optional true} [:vector :map]]
        [:effects {:optional true} [:vector [:map [:effect/type :keyword]]]]])
```

**Why not unify?** Sean's challenge: "can sections be handlers too?" The
honest answer: **at the registration layer, yes — both are 'a registered fn
with a typed return value'.** At the dispatch layer, no. Sections re-run on
every render (time base: turn). Handlers fire on tx-match (time base: each
committed tx). Collapsing them produces the self-triggering loop that
killed earlier sketches — a section that "could fire as a handler" would
re-run on every tx of every kind, then either we filter (= match predicate
= handlers) or we don't (= unbounded re-render). The split is load-bearing.

What we DO share: the registration verb pattern, the symbol-resolution path
(`seon.eval/lookup-value`), the `:malli/schema`-driven discovery, and the
renderer's output-shape dispatch. Two primitives, one machine.

## 4. The dispatcher (compressed)

Single `d/listen!` per pod keyed `[::seon.runtime/dispatch]`. Per committed
tx:

1. Read `(:seon.db/origin tx-meta)`. If `:handler` and the matched handler's
   `:on-origin` doesn't include `:handler`, skip. (Default cycle-guard.)
2. Build the per-attr handler index lazily (rebuilt when `:seon.handler`
   datoms land in any tx). For each added datom, look up handlers by
   `:seon.match/attr`; if `:value` set, filter by `=`; scope by
   `:seon.handler/agent`.
3. Invoke each matched handler with `{:seon.db/db db-after :seon.db/tx-report
   report :seon.agent/id <id-being-dispatched-to>}`. Sort by `:priority` desc.
4. Each returns `{:tx [...] :effects [...]}` (either may be absent).
5. **Apply `:tx`** as one `db/transact!` with `:tx-meta {:seon.db/origin :handler}`.
6. **Apply `:effects`** by handing each map to `seon.runtime/run-effect!`
   (multimethod on `:effect/type`). Effects execute on the next event-loop
   tick.

Fiber-local depth counter (ALS) capped at 16 — defense in depth behind the
origin-skip rule.

## 5. Effect catalogue — minimum viable

Three effect kinds in v1, all the five demo scenarios need:

| `:effect/type` | Required keys | Behavior |
|---|---|---|
| `:effect/wake` | `:agent` | If state `:stopped`, flip `:running` + `(run-agent-loop! agent)`. Else no-op. |
| `:effect/run-llm` | `:agent` `:request` `:corr` | LLM client call; on resolve transact `:seon.async-result/kind :llm/done :corr corr`. On reject `:llm/error`. |
| `:effect/spawn-agent` | `:parent` `:kind` `:initial-message` `:refs` | Mint child agent + initial message; per-agent handlers register at creation. |

**Killed from loop-design.md §5:** `:tx` as an effect kind (handlers return
`{:tx ...}` directly — no point routing it through the effect interpreter),
the speculative `:fetch-url`/`:read-file` rows (capability-gated effects are a
v2 problem, not v1 ergonomics). When a real handler needs `:fetch-url`,
adding it is "write a new multimethod method + document required keys."

## 6. Substrate handlers — three registrations at boot

```clojure
;; In seon.runtime, at namespace load:
(handler/register! :seon.handler/wake-on-message-to-agent
                   {:attr :seon.message/to}
                   'seon.runtime/wake-on-message-to-agent)

(handler/register! :seon.handler/route-async-result
                   {:attr :seon.async-result/agent}
                   'seon.runtime/route-async-result)

(handler/register! :seon.handler/surface-eval-error
                   {:attr :seon.eval/error-data}
                   'seon.runtime/noop)  ; the error-section query handles surfacing
```

No per-agent copies of these (a substrate-killed concept from
loop-design.md §8) — substrate handlers have `:agent nil` and the dispatcher
scopes by reading the inbound tx's relevant agent (e.g., for
`:seon.message/to`, the agent IS the value of the matched datom; for
`:seon.async-result/agent`, same).

## 7. End-to-end: one scenario, shown as data

User sends "what's 2+2" to agent A. Walking the data transacted at each
step:

```clojure
;; t0  — user message lands
{:seon.message/id      "msg-abc-2605241830"
 :seon.message/role    :user
 :seon.message/from    :user
 :seon.message/to      [[:seon.agent/id "A-abc-2605241830"]]
 :seon.message/content "what's 2+2"
 :seon.message/at      #inst "..."}
;; tx-meta: {:seon.db/origin :user}

;; t1  — dispatcher walks. wake-on-message-to-agent matches (attr
;;       :seon.message/to). Handler returns:
{:effects [{:effect/type :effect/wake :agent "A-abc-2605241830"}]}
;; :effect/wake interpreter checks state → :stopped → flips :running,
;; transacts:
{:db/id [:seon.agent/id "A-abc-2605241830"]
 :seon.agent/state :running}
;; tx-meta: {:seon.db/origin :handler}  ← does NOT re-fire wake (default origin-skip)

;; t2  — run-agent-loop! invokes render → composes prompt → calls LLM.
;;       Handler returns:
{:effects [{:effect/type :effect/run-llm
            :agent "A-abc-2605241830"
            :corr "corr-xyz-2605241830"
            :request {:model "..." :messages [...]}}]}
;; (interpreter side, not committed to DB until LLM resolves)

;; t3  — LLM resolves 800ms later. Interpreter transacts:
{:seon.async-result/id      "ar-def-2605241830"
 :seon.async-result/agent   [:seon.agent/id "A-abc-2605241830"]
 :seon.async-result/kind    :llm/done
 :seon.async-result/corr    "corr-xyz-2605241830"
 :seon.async-result/payload "(+ 2 2)"
 :seon.async-result/at      #inst "..."}
;; tx-meta: {:seon.db/origin :system}

;; t4  — dispatcher walks. route-async-result matches (attr
;;       :seon.async-result/agent). Handler returns:
{:effects [{:effect/type :effect/wake :agent "A-abc-2605241830"}]}
;; Agent state already :running, no-op. But the eval-batch picks up
;; the new async-result via its render-time section query.

;; t5  — eval runs (+ 2 2) → 4. Agent emits final assistant message:
{:seon.message/id      "msg-ghi-2605241830"
 :seon.message/role    :assistant
 :seon.message/from    [:seon.agent/id "A-abc-2605241830"]
 :seon.message/to      [:user]
 :seon.message/content "4"
 :seon.message/at      #inst "..."}
;; tx-meta: {:seon.db/origin :agent}

;; t6  — eval-batch returned narration only (no forms), dispatcher reports
;;       no queued effects for agent A → run-agent-loop! transacts:
{:db/id [:seon.agent/id "A-abc-2605241830"]
 :seon.agent/state :stopped}
;; tx-meta: {:seon.db/origin :system}
```

Six transacts, one verb per substrate handler, no event queue, no
notification channel. The DB log IS the audit trail.

## 8. What we kept from loop-design.md

- **Two primitives — sections (derivational) and handlers (dispatch).** §3 of this PRD argues the unification question on the merits.
- **Effects-as-data + multimethod interpreter.** Cut to 3 kinds for v1.
- **`d/listen!` once-per-pod dispatcher** with per-attr handler index cache.
- **Origin-skip default for handler-emitted txs**, opt-in via `:on-origin`.
- **Fiber-local depth counter (16-cap) as defense in depth.**
- **Resumability** (loop-design.md §7): `replay-program-graph!` resurrects handler fns; `:seon.handler` entities are queried at boot to rebuild the per-attr index. Interrupted-agent recovery (system message kicks wake-on-message) unchanged.
- **Migration plan** (loop-design.md §12): unchanged except `install-user-trigger!` becomes `install-dispatcher!` AND `handler/register!` is called once at boot for the three substrate handlers.

## 9. What we killed and why

| Killed | Reason |
|---|---|
| `:seon.handler/id` separate from `:seon.handler/name` | Name is the identity attr (composite with agent). Two identities = drift. |
| `:seon.handler/slot` | Was speculative ergonomics for render coupling; handlers don't render. |
| Per-agent copies of substrate handlers at agent-creation time | Substrate handlers with `:agent nil` scope by reading the tx's relevant agent. One entity, not N. |
| `:effect/type :tx` as a distinct effect | Handlers already return `{:tx ...}`. Routing tx through the effect interpreter adds latency and a hop. |
| Speculative `:fetch-url` / `:read-file` rows | Not in v1 scope. Add by writing a multimethod method when a handler needs it. |
| Match-spec DSL beyond attr+value | Three substrate handlers fit; agent handlers fit. Datalog-style `:where` waits for a real consumer. |
| Interceptors / middleware (never in loop-design.md but tempting from re-frame) | No use case in the five scenarios. Re-introduce when one appears. |
| Co-effect injection | Handler receives `:seon.db/db` and queries freely. The DB IS the co-effect. |
| Separate event queue | Datahike's tx-report queue is the queue. |

## 10. Acceptance criteria

1. **`handler/register!` is one call site, no fan-out.** Adding a new
   handler — substrate or agent — is one form. Search the codebase for
   "handler/register!" and you see every handler in the system.
2. **Substrate and agent-authored handlers are bytewise-identical except
   for `:seon.handler/agent`.** Show the diff between a substrate handler's
   registered entity and an agent-authored one — only one key differs.
3. **Re-registering a handler under the same `[name agent]` replaces, no
   duplicate.** `(handler/register! :foo {...} 'old-fn)` then
   `(handler/register! :foo {...} 'new-fn)` → one entity, `:fn` is
   `'new-fn`, identity-attr upsert.
4. **Five scenarios from loop-design.html pass end-to-end** with the new
   dispatcher and the three substrate handlers only. No additional
   substrate handlers needed.
5. **No `:effect/type :tx`.** Handlers that want a follow-up tx return
   `{:tx [...]}` directly. Grep confirms no `:tx` effect anywhere.
6. **Origin-skip is the default.** A handler that emits `{:tx [{...}]}`
   does not re-fire itself on that tx. Test: install a handler matching
   `:foo/bar` that always emits `{:tx [{:foo/bar 1}]}`. After one fire,
   no recursion.
7. **Depth-guard fires at 16.** A handler with `:on-origin #{:agent
   :handler}` that always emits a chaining tx halts at depth 16 with a
   `:seon.runtime/depth-exceeded` warning entity.
8. **Resume.** Pod restart: `replay-program-graph!` runs;
   `:seon.handler` entities are queried and the per-attr index is rebuilt;
   any `:seon.agent/state :running` left from before gets a system
   message; wake-on-message fires; agent resumes normally. < 200ms for
   an agent population of 10.

## 11. Open questions (the two I don't have a clean answer to)

1. **Composite tuple identity in Datahike.** `:seon.handler/key` as
   `[:tuple :keyword [:maybe :seon.db/ref]]` with
   `{:seon.db/identity true}` requires Datahike's composite-tuple
   identity feature. If that's flaky (or not yet in our pinned version
   of datahike-cljs), the fallback is `:seon.handler/name` alone as
   identity + a hard rule "substrate handlers have nil agent, agent
   handlers MUST use namespaced kw including agent suffix"
   (e.g. `:my.handlers/rerun-failed-test--agent-A`). Ugly but works.
   **Need Sean to confirm which path** before the schema lands.

2. **Renderer-driven handler output rendering.** Sean's `:seon.render/ai`
   question: can a handler's *output data* carry render metadata so the
   runtime knows how to surface the result without a separate slot
   mechanism? The proposal: handlers return `{:tx :effects}`, and the
   *tx* contains entities that the renderer's existing dispatch picks up
   on the next render — i.e., the result IS the render input, no extra
   metadata needed. But that assumes handlers always write before they
   want something rendered; transient "show this once" results don't fit.
   **Need Sean to confirm:** is there a real case where a handler wants
   to surface something WITHOUT persisting it? If yes, we need a
   render-effect kind. If no, the existing renderer is the whole
   answer and the question dissolves.

## 12. Cross-references

- `docs/prds/agent-runtime/loop-design.md` — the parent PRD this supersedes in part
- `docs/prds/agent-runtime/loop-design.html` — five scenarios this design must pass
- `docs/prds/agent-runtime/research/re-frame-vs-roll-own-2026-05-25.md` — the dispatch choice
- `docs/prds/agent-runtime/research/agent-loop-pattern-survey-2026-05-25.md` — pattern survey
- `docs/seon/concepts/reactive-context.md` — why sections stay derivational
- `docs/seon/concepts/code-as-data-runtime.md` — why handlers can be DB entities and survive restart
- `src/seon/schema.cljc` — the `register!` pattern parallel
- `src/seon/render.cljs` + `src/seon/render/default.cljs` — the renderer dispatch leveraged
- `docs/prds/agent-runtime/v1.md` §5 (sections), §7.4 (resume) — unchanged baseline
