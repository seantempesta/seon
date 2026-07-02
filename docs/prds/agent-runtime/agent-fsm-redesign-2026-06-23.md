---
type: prd
status: draft
tags: [prd, agent, flow, database]
---

# PRD: The agent runtime as a finite state machine — one eval'able REPL transcript, DB-backed states, explicit lifecycle verbs (2026-06-23)

**Status: draft — design agreed in conversation 2026-06-23. Branch `feature/agent-fsm`
(rollback = `git checkout feature/embeddings`). Goal: a WORKING slice, live-tested
with DeepSeek ASAP, then iterate the format. NEVER name the downstream consumer — use `acme`.**

## North star (why this shape)

**The entire agent context becomes valid, eval'able Clojure.** The transcript is a
REPL session: `;;` comments + forms + `;;`-commented results. Re-evaluating the
forms (comments pass through, ignored) reproduces the agent's state — so the
context IS a replayable program, and rolling the DB back to a point and replaying
the forms reconstructs it. **No XML, no magic.** Comments carry everything a human
(or the LLM) needs to understand what happened and when; forms carry the state.

This PRD is the first step toward that: one simple, understandable, adaptable
system where the prompt, the transcript, and the program graph are the same thing
viewed from one angle. Everything below serves that — if a choice adds a parallel
mechanism, a tag, or a non-eval'able artifact, it's wrong.

## TL;DR

Rebuild the agent loop + messaging as a finite state machine whose state lives in
the **DB** (on the agent record), is **re-read every loop iteration**, and is
**externally controllable** (an orchestrator stops a rogue agent by writing its
state; `:terminated` can't be woken). The loop ends on explicit `(complete …)` /
`(agent/wait …)` or implicit no-forms / per-loop cap — **never** a reply count.
The context is ONE bottom section: an append-only, eval'able REPL transcript whose
**live readline carries the status** (current ns, time, turn, loop, state) — the
separate prompt/turns/status sections fold INTO it.

**This is a CONSOLIDATION, not an addition.** We DELETE the answer-accounting
layer, `reply!`, the `!kick-scheduled` atom, the self→self notes, the XML
transcript, and the separate prompt/turns sections. We do NOT keep two code paths
doing the same thing, and we do NOT port the old creation-evals — turn 0 IS the
bootstrap, expressed in the new format. See §5 for the exact consolidation map.

## What is already TRUE (verified 2026-06-23 — keep, don't rebuild)

1. The single-pod multi-wake **race is already closed** (per-tx listener, sync
   handler, no-replay wire). The remembered corruption was #43's forged human
   message — fixed by `origin :core` gating. KEEP the origin/handled? gate.
2. **Per-form eval isolation is the one robust seam** — `eval-batch!` runs every
   form (errors are envelopes, `eval.cljs:2646/899`); `eval-count = n-ok + n-fail`
   (`agent.cljs:1140`). KEEP untouched.
3. No wall-clock loop timer; the only bound is the turn count. The 60 s LLM HTTP
   timeout → turn `:error` → loop ends. KEEP.
4. `my.kb.system` seeds an **empty** instruction singleton — there is NO stored
   old-model instruction corpus to migrate. Standing guidance = SOUL.md +
   `system-text` + the turn-0 bootstrap demo only.

## 0. Foundations — module layout, naming, state (start fresh, no baggage)

Audit (workflow w1odvsl6w) findings: only ONE fragile coordination atom exists
(`!kick-scheduled`) — everything else is genuine runtime state the project's rules
sanction. The two ALS scopes are ALREADY ergonomic-defaults-only. The real baggage
is naming drift (three "is this agent alive?" vocabularies, three "stop the loop"
concepts) and the 93KB / 1820-line everything-namespace.

Four layout rules: **subnamespaces per concern**; a **`.internal` sibling** for
dense wiring so the public ns reads as the *what*; **no file near 2000 lines**;
**readable by reading the namespaces**. Every change is an in-place move/rename or a
delete — never a `*-v2` or a parallel ns.

### Module layout (split the 1820-line agent.cljs along real seams)

Schemas co-locate with **the namespace whose name the keyword carries** (the rule),
which is also each record's data-owner. `:seon.agent/*` → `seon.agent`;
`:seon.agent.turn/*` → `seon.agent.turn`; `:seon.agent.message/*` →
`seon.agent.message`; `:seon.eval/*` stays in `seon.eval`. The fsm is a *processor*
of `:seon.agent/state`/`wake` but does not own those keywords — no facade needed, no
cycle (the verbs that write state and the loop that reads it don't call each other —
the loop just re-reads the state the verbs transacted).

- `seon.agent` — the agent RECORD + the agent-facing verbs (the readable core): the
  `:seon.agent/*` schemas (id/purpose/state/wake/parent/max-turns-per-loop +
  entity map), `create!` / `boot!`, state helpers (`current-state` / `set-state!` /
  `fresh-wake!`), and the lifecycle verbs `wait` / `complete` / `terminate` (each a
  small state transact). The agent's `agent/` alias points here. Requires db +
  schema only — NOT fsm/turn, so nothing cycles. (~450 lines)
- `seon.agent.fsm` (+ `.internal`) — the loop machinery: `install-wake-trigger!` +
  `wake-handler`, `run-loop!`, the halt/cap policy. Requires `seon.agent` (schemas +
  state helpers) + `seon.agent.turn` (calls `run-turn!`). `.internal` = wake-id
  recheck, the per-loop count + effective-cap queries, the release/finally.
- `seon.agent.turn` (+ `.internal`) — one turn: `run-turn!`, `open-turn!` /
  `close-turn!`, `ask-and-eval!`, `call-llm!`. Requires `seon.agent` (schemas) + ctx
  + eval + message. `.internal` = the eval-fold + debug capture plumbing.
- `seon.agent.message` — stays: `message!` + `message/user` + `message/agent`
  (refuses `to = me`) + the `:seon.agent.message/*` schemas. Requires db.
- DELETE `seon.agent.turns` (folds into the transcript readline).
- `seon.ctx.transcript` — rewritten in place, absorbs prompt + turns (§2). DELETE
  `seon.ctx.prompt`. Extract `system-text` to `seon.ctx.system` (ctx.cljs is itself
  >2k — split system-text + the shared read API out; the rest of the ctx split is
  flagged, out of FSM scope).

Dependency direction (acyclic): fsm → {seon.agent, turn}; turn → {seon.agent, ctx,
eval, message}; seon.agent → {db, schema}; message → {db}. The boot path (client)
requires fsm to `install-wake-trigger!`.

### ALS — unify, rename, keep ergonomic-defaults-only

Merge the two ALS instances (`agent-id-als` + `tx-context`) into ONE fiber-local
store in `seon.db.internal` carrying ONLY the default bundle
(`:seon.db/agent-id/session-id/turn-id/origin`). Rename the public face
`with-tx-context` → **`with-tx-meta` / `current-tx-meta`** (it is the tx-meta
bundle, not a general "context bag"); keep `with-agent` / `current-agent-id`. It must
NEVER carry FSM state (state/wake/loop-count) — those live on the agent record.
(`warnings-als` is a separate eval-internal ALS — leave it in seon.eval.)

### State model

- DELETE: `!kick-scheduled` → DB `:seon.agent/state :active` + `:seon.agent/wake`.
- KEEP (genuine runtime artifacts): DB conns, the ALS instance(s), compile-state +
  version stamps, the `result/<id>` ring buffer, the wire de-dup set
  (`!own-request-ids`), SCI tile guards, per-call let-accumulators, `!boot-sessions`
  (rename → `!sessions-opened-this-run`).
- FLAG (out of scope): `!fallback-warned` (warn-once dedupe → should be a derived
  surface) — leave a note, not part of this rebuild.

### Rename table (all in place, same patch as the touching unit)

| Old | New |
|-----|-----|
| `:seon.agent/state` `:idle`/`:running` | `:idle :active :waiting :completed :terminated` |
| `!kick-scheduled` atom + `:running` guard | DELETE → `:seon.agent/wake` + recheck |
| `:seon.agent.turn/woken-by` | DELETE → `:seon.agent/wake` (episode key) |
| `reply!` | DELETE → `message/user`, `message/agent` |
| `run-agentic-loop!` | `run-loop!` (fsm) |
| `install-user-trigger!` / `inbound-message-handler` | `install-wake-trigger!` / `wake-handler` |
| `live-agent-ids` / `all-running-agents` / `resumable-agent-ids` | one `armable-agent-ids` (state ≠ `:terminated`) |
| `turns-cap` / `default-turns-cap` | `max-turns-per-loop` (`:seon.agent/max-turns-per-loop`, env `SEON_MAX_TURNS_PER_LOOP`) |
| `with-turn!` / `with-turn-body!` / `ensure-idle!` | `open-turn!` / `close-turn!` (failsafe → loop finally) |
| `complete!` (lifecycle stamp) | `complete` (repurposed terminal verb) |
| `unanswered-live-inbound?` / `live-inbound-count` / `user-facing-reply-count` / `query-count` / `task-in-progress?` / `inbox-count` | DELETE (no rename) |
| `transcript-section` / `render-turn` / `woken-by-line` / `pending-inbound-line` | `transcript` / `turn-header` / `inbound-line` (rewritten) |
| `prompt-section` | DELETE → readline in `transcript` |
| `with-tx-context` / `current-tx-context` | `with-tx-meta` / `current-tx-meta` |
| `empty-completion-nudge` / `give-up-text` / `max-empty-reprompts` | DELETE (steering → readline; bare `(< empty-streak 2)` recur) |

The module split is realized **incrementally** as each build unit (§6) lands its
module — no big-bang refactor. The ALS-unify + the `:running→:active` enum rename +
its ripple (client / inspector / render.default) ride in Unit 1 (the enum's home).

## 1. The finite state machine

**Everything is DB state** — the agent record, sessions, turns, evals, messages are
all entities. The distinction: the agent record's `state` + `wake` are the FSM
*coordination* truth (the loop's stop/go); turns/evals/messages are the *history*
(the log). The loop reads coordination each iteration and derives the rest.

### Schema

```clojure
;; --- agent record: identity + config + the FSM coordination truth ---
(schema/register! :seon.agent/id      [:string {:seon.db/identity true}])
(schema/register! :seon.agent/purpose :string)
(schema/register! :seon.agent/state   [:enum :idle :active :waiting :completed :terminated]) ; STORED (coordination)
(schema/register! :seon.agent/wake    :seon.db/id)            ; STORED — current wake-episode token (reuses id shape)
(schema/register! :seon.agent/max-turns-per-loop :int)        ; STORED optional — base per-loop cap (else env/20)
(schema/register! :seon.agent/parent  :seon.db/ref)           ; STORED optional — subagent→parent (delivery descoped)
(schema/register! :seon.agent/wait-note :string)              ; STORED optional — surfaced to monitoring agents
(schema/register! :seon.agent
  [:map {:seon.db/entity true}
   [:seon.agent/id]
   [:seon.agent/purpose            {:optional true}]
   [:seon.agent/state]
   [:seon.agent/wake               {:optional true}]
   [:seon.agent/max-turns-per-loop {:optional true}]
   [:seon.agent/parent             {:optional true}]
   [:seon.agent/wait-note          {:optional true}]])

;; --- turn: history + the per-loop episode key ---
(schema/register! :seon.agent.turn/wake :seon.db/id)          ; STORED — which wake-episode this turn belongs to
;; KEEP: :seon.agent.turn/id (identity) :at (inst) :status [:running :done :error]
;;       :seon.agent.turn/evals (component vector) :prompt-text :prompt-file
;; DELETE: :seon.agent.turn/woken-by (→ wake), :seon.agent.turn/messages (self→self note gone)
```

KEEP unchanged: `:seon.agent.session`, `:seon.eval`, `:seon.agent.message`
(incl. `origin [:enum :human :agent :core]` + `handled?` — the wake gate).

### Store-vs-derive (the rule: derive by default; store only if expensive OR useful to others)

- **STORED** (coordination / not derivable): `:seon.agent/state`, `:seon.agent/wake`,
  `:seon.agent.turn/wake`. Plus config (`max-turns-per-loop`, `parent`) and
  `:seon.agent/wait-note` — stored because it's surfaced to *monitoring* agents
  (a parent watching its subagents), so it earns a slot even though it's cheap.
- **DERIVED** (cheap queries, never stored): the monotonic turn number (position in
  the `:at`-ordered walk across all sessions — accumulates from 0, never resets,
  survives resume); the per-loop count (`count turns where wake = my-wake`); the
  effective cap (below); the NEW-message flag (inbound `:at` > prior turn `:at`); all
  transcript text.

| State | Meaning | Wakeable? | Looping? |
|-------|---------|-----------|----------|
| `:idle` | neutral / between work | yes → `:active` | no |
| `:active` | a loop is running | no (running loop picks it up) | yes |
| `:waiting` | parked via `(agent/wait …)` | yes → `:active` | no |
| `:completed` | finished via `(complete …)` / no-forms | yes → `:active` | no |
| `:terminated` | orchestrator kill | **NO** (change state first) | no |

### Wake — read-then-write-with-recheck (no atom, no true CAS needed)

The pod is not the writer (writes forward async to wire-server), so there is no
synchronous CAS. Instead:

1. An inbound datom fires the per-tx listener. The handler reads the agent's state
   from its local db snapshot. If state ∉ wakeable (`:active`/`:terminated`) → no-op.
2. If wakeable: generate a fresh **wake-id**, `await (transact! …)` setting
   `:seon.agent/state :active` and `:seon.agent/wake` = wake-id, then start the loop
   stamped with that wake-id.
3. **The loop self-guards.** Each iteration re-reads `:seon.agent/wake`; if it ≠ the
   loop's own wake-id, a newer wake superseded it → bail. Two simultaneous idle
   wakes both write `:active` + their wake-id; last-writer-wins; the losing loop
   reads back a different wake-id and bails. No double loop, no atom — optimistic
   concurrency via the DB.

`:seon.agent/wake` is the ONE coordination token, and it doubles as the per-loop
episode key (below). It **replaces** the deleted `:seon.agent.turn/woken-by` attr —
NOT a parallel attr (see §5).

### The loop (replaces `run-agentic-loop!` + the atom)

```clojure
;; the WHOLE stop policy — re-reads DB state each iteration
(loop [empty-streak 0]
  (let [{state :seon.agent/state wake :seon.agent/wake} (agent-rec id)]
    (cond
      (not= :active state)                 :halt-external      ; orchestrator changed state
      (not= wake my-wake)                  :halt-superseded    ; a newer wake owns the agent
      (>= (turns-this-wake id my-wake)
          (effective-cap id my-wake))      :halt-cap   ; base + inbounds-this-wake; NO self-note
      :else
      (let [r (await (run-turn! input))]
        (cond
          (= :error (turn-status r))       :halt-error               ; → :idle
          (terminal-verb? r)               :halt-verb                ; wait/complete set state
          (zero? (actionable-count r))     (if (< empty-streak 2)
                                             (recur (inc empty-streak)) ; thinking-mode guard
                                             :halt-quiet)             ; → :idle, CLEAN (no error)
          :else                            (recur 0))))))
;; finally: if still :active and my-wake still owns it, set :idle.
```

### The per-loop cap is a SLIDING WINDOW that every message extends

Turn numbers accumulate from 0 forever (the display number, derived). The CAP is a
window measured from THIS wake's start — `turns-this-wake` vs an **effective cap**,
all derived:

```
effective-cap = (max-turns-per-loop id) + (inbounds-during-this-wake id my-wake)
inbounds-during-this-wake = count messages to-me, from ≠ me, origin ∈ {:human :agent},
                            :at ≥ the first turn of this wake
```

So **every inbound (human OR peer) grants +1 turn** — guaranteeing the agent gets a
turn to SEE and respond to a message that landed mid-LLM-call before the cap can
bite. A purely autonomous run (no inbounds) still stops at the base cap. No stored
grant, no `cap-note` message (steering lives in the derived readline). Agent↔agent
floods are already bounded by the hop-cap, so the bump needs no extra ceiling.

### No self-messaging, ever

`message/agent` REFUSES `to = me` (loud error). `message/user` only ever targets the
one human. There are no self→self messages anywhere — the per-turn note, nudges, and
cap/give-up notes are all DELETED; an agent's "notes to itself" are just `;;` comments
in its turn (eval narration), and steering is the derived readline. The wake gate
already ignores `from = me`; this makes it a hard prohibition at the verb.

### External control + recovery

The loop re-reads state, so an orchestrator writing `:completed`/`:terminated`/`:idle`
stops it next iteration. Stuck-`:active` (a hung promise that never settles) is
recovered by (a) the manual state write, and (b) a derived "looks stuck" surface
(state `:active` ∧ no turn in N s ∧ live inbound). A dropped wire event self-heals
only on the next tx for that agent — honest limitation, manual write is the backstop.

## 2. The transcript = the whole bottom of the context

There is ONE bottom section — an append-only, eval'able REPL transcript that
**absorbs the old prompt/turns/status sections.** It is produced by one fn,
`(transcript db agent-id)`, deterministic and append-only (same prefix every call).

### Channels — all valid Clojure (eval'able north star)

- `(forms)` — the agent's code (the ONLY thing it writes besides `;;`)
- `;; narration` — the agent's own comments
- `;;=> value` — a runtime result, **as a comment** (`result/<id>` handle) — so
  re-evaluating runs only the forms and reproduces state
- `;;; runtime` — runtime structure: the turn/status header comments and inbound
  message lines (`;;;` = idiomatic Clojure heading comment, and "not yours")
- `ns=>` — the REPL readline (render sugar): shows the **current namespace**; an
  `(in-ns 'x)` flips it to `x=>`, so the agent sees which ns the next form evals in.
  Derivable from the `in-ns` history; the eval'able export is forms + comments.

The agent writes two things: `(forms)` and `;;` comments. The `;;;` headers, the
`;;=>` results, and the `ns=>` prompt are the runtime's — it adds them around the
agent's forms so the agent can read what happened.

### Framing rule — positive-only, reinforce live-and-current (LOAD-BEARING)

A KNOWN failure: when results were shown with a `;;=>`-style marker, agents began
WRITING `;;=>` lines themselves (treating them as a way to store values) and then
referenced those fabricated values. The fix is FRAMING, and the framing has rules:

- **Lead with what TO do, never with a prohibition.** Tell the agent "you write
  forms and `;;` comments; the runtime shows each form's value on the next line as
  `;;=>` — that's how your results arrive, on the turn after you write the form."
  Do NOT lead with "never write `;;=>`" — a negative example primes the very
  behavior it forbids (standing owner rule). Show only the right shape; explain
  what the agent is seeing.
- **Reinforce that this REPL is LIVE and ALWAYS CURRENT** — it re-derives from the
  DB every turn, so it is never a stale transcript being replayed. The agent's
  prior training prepped it for static files/notebooks; this is different, and the
  masthead names that difference plainly.
- **The substrate must NEVER break its own format rules.** If we ever emit a shape
  we told the agent only the runtime emits, the contract is broken and the agent
  will mimic it. The render is the single source; keep it consistent every turn.

The masthead (the first lines of the transcript, rendered every turn) carries this:

```
;;; ═══════════════ seon · my.agent.seon · live REPL ═══════════════════════
;;; This is your live REPL — a Clojure session backed by the database. The
;;; history below is real and ALWAYS current: it re-derives from the DB every
;;; turn, so it is never stale. You write Clojure forms and ;; comments. After
;;; each form the runtime evaluates it and shows the value on the next line as
;;; `;;=> …` — that is how your results arrive, on the turn after you write the
;;; form. So just write the form; read its `;;=>` next turn. Append below.
```

### The folded readline carries the status

No separate status/prompt/turns section. Each turn opens with a `;;;` header line
carrying turn · time · loop-count · state · any inbound, and the **live readline**
at the very bottom is the cursor + that same status — that IS the steering surface
(time lets the agent judge what's expensive; ns lets it know where forms land).

### Canonical example (turn 0 bootstrap → LIVE)

```
;;; ── turn 0 · bootstrap · 14:00:00 · my.agent.seon ───────────────────────────
;; Starting up — checking the store and my standing instructions first.
my.agent.seon=> (seon.db/store-inventory)
;;=> {:seon.kb/note 9, :seon.fn 42, …}                        ;; result/a1
my.agent.seon=> (my.kb.system/instructions)
;;=> [{:my.kb/text "consult the store before researching"} …] ;; result/a2
my.agent.seon=> (message/user "Hi Sean — I'm up; 9 notes in the store. What should I work on?")
;;=> {:delivered true}
my.agent.seon=> (agent/wait "awaiting first task")
;;=> :waiting

;;; ── turn 1 · 14:04:13 · loop 1/20 · my.agent.seon ─────────────────────────
;;; ◀ from :user @ 14:04:12 (4m after you parked) — "refactor the foo namespace"
;; On it.
my.agent.seon=> (in-ns 'my.foo)
;;=> my.foo
my.foo=> (refactor)
;;=> {:moved 3 :ok true}                                       ;; result/b7

;;; ── turn 2 · 14:09:40 · loop 2/20 · my.foo ────────────────────────────────
;;; ◀ 1 NEW — arrived while you were working on turn 1, not yet acted on:
;;;     :agent/researcher @ 14:09:15 — "heads up: foo depends on qux"
my.foo=>
```

### Rules

- A turn = one LLM completion, opened by a `;;; ── turn N · <time> · loop K/cap ·
  <ns> ──` header. The header IS the status; the live readline at the bottom is the
  cursor (current ns) + the steering this turn (e.g. "1 of your last forms failed
  (result/h4) — fix it, or (complete …)/(agent/wait …)").
- **Every inbound message renders EXACTLY ONCE**, as a `;;; ◀ from X @ time — "…"`
  line at the head of the turn that first sees it (the turn the agent can first act
  on) — including a message that arrived mid-LLM-call (flagged NEW). No
  duplication, no "wait returns the message." The head render applies the SAME gate
  as the wake (`from ≠ me`, `origin ∈ {:human :agent}`, `handled? ≠ true`, `hops <
  cap`) — reuse ONE predicate so a `:core` nudge never renders as a fake inbound.
- `(agent/wait …) ;;=> :waiting`, `(complete …) ;;=> :completed` — verb effect as a
  plain commented result; the FSM transition is real.
- Deterministic + append-only (true while elision stays deferred). When elision
  lands later, the elision note goes in the volatile live tail so history stays
  byte-stable. Keep `result/<id>` vars + per-component caps.

## 3. The verbs (clear, no magic, all through the DB — with schemas)

```clojure
(message/user "…")           ; from = me (ALS scope), to = the one user
(message/agent agent-id "…") ; from = me, to = [agent-id]
(agent/wait "note")          ; → :waiting; resumes when a message arrives
(complete "result")          ; → :completed; if :seon.agent/parent set, message! it the result
;; …or emit no forms → :idle (clean). (terminate …) is orchestrator-only.
```

- `message/user` / `message/agent` are thin wrappers over the kept `message!`
  (`message.cljs:164`); `reply!` is **deleted**. Each gets a `:malli/schema`
  (reuse `::message-response`): e.g. `message/user [:=> [:catn [::content :string]]
  ::message-response]`.
- `agent/wait` / `complete` / `terminate` each get a `:malli/schema` (note `^:async`
  fns are not runtime-instrumented, so the schema is the only contract).
- **`complete` is the repurposed `complete!` (`agent.cljs:805`) IN PLACE** — rename
  the symbol to `complete`, take a result-message arg, set `:completed` (now
  WAKEABLE — drop the stale "is HISTORY / never resumed" comment), and IF
  `:seon.agent/parent` is set, `message!` the result to the parent (which wakes it
  via the normal inbound gate — no new channel). `seon.agent.todo/complete!` is a
  DIFFERENT fn (work-item closer) — leave it alone.
- **Parent delivery is descoped to a thin conditional.** No spawn path sets
  `:seon.agent/parent` yet, so it's absent for all agents until the subagent-spawn
  PRD lands; `complete` works (state + result-to-user) regardless. Register the attr
  but do not build a "subagent channel" abstraction with no producer.

## 4. Prose & error policy (self-reinforcement)

In `parse-forms` (`repl/internal.cljc:348`), override #50's drop-prose for NL prose:

- `;; comment` → narration on the next form (unchanged)
- natural-language prose before a form → **converted to `;;` narration** (replay
  shows it did it right)
- a genuine `(list …)` → evaluated (unchanged)
- fabricated `;;=>` / `=>` / bare data-literal-as-result → **stripped, not echoed** +
  one correction note (the genuine #50 anti-faking guard stays)
- unrecoverable broken form → `;; ⚠ DO NOT DO THIS — <what broke>; <how to fix>`

Keep parinfer per-form repair on. This is a change-table against the ONE existing
`parse-forms` — NOT a second parser.

## 5. Consolidation map — what we DELETE / MERGE (NO parallel paths)

This is the section to get right: **one code path per job.** Every row is an
in-place edit or a deletion — never a new `*-v2` symbol or ns.

DELETE outright (no replacement):

- Answer-accounting: `unanswered-live-inbound?` (`agent.cljs:1411`),
  `live-inbound-count` (`:1352`), `user-facing-reply-count` (`:1385`),
  `query-count` (`:1347`), `task-in-progress?` (`ctx.cljs:1407`), `inbox-count`.
- `reply!` (`message.cljs:244-267`) and the `current-turn`/woken-by targeting.
- The `!kick-scheduled` atom (`agent.cljs:553`) + the `:running` string guard.
- ALL self→self (from=to=me) message transacts: the per-turn fold (`:1130-1158`),
  the empty-completion nudge (`:1573-1576`), the give-up `:error` self-message
  (`:1587-1599`). The no-forms halt is a clean `:idle`, not an `:error`+self-message.
- `empty-completion-nudge` / `empty-completion-give-up-text` defs; the turn-cap note
  string. (Steering now lives in the derived live readline, §2.)
- The `:seon.agent.turn/woken-by` attr + its writes (`:267/615/630/1033/1319`) —
  REPLACED by the `:seon.agent/wake` episode token (§1), not carried alongside.
- The old `creation-evals!` mechanism — **not ported.** Turn 0 IS the bootstrap,
  emitted in the new format (the same inventory + instructions + hello + park forms).

MERGE / REWRITE IN PLACE (one path, not two):

- `prompt-section` (`ctx/prompt.cljs`) + `turns-section` (`agent/turns.cljs`) +
  `transcript-section` (`ctx/transcript.cljs`) → **ONE transcript section** with the
  folded readline/status (§2). The XML walk (`render-turn`, `woken-by-line`,
  `pending-inbound-line`, `repeat-wake?`, resume marker) is rewritten to the
  comment-block model IN `seon.ctx.transcript`. `format-eval-row` (`ctx.cljs:424`)
  emits `;;=>`-commented results (already row-shaped — edit, don't fork).
- `seon.ctx/system-text` — rewrite the standing teaching: `reply!`×8 → `message/user`
  / `message/agent`; delete "ONE reply per question / the loop stops" + "not served
  until reply! lands"; teach `(agent/wait …)`/`(complete …)`/no-forms; describe the
  comment-block REPL + `;;;`/`;;=>` channels (not `<past-evals>`/`<tag>`).
- `complete!` → `complete` (repurpose in place, §3).
- `run-agentic-loop!` (`:1483`) + `inbound-message-handler` (`:580`) +
  `install-user-trigger!` (`:654`) → the §1 DB-state loop + recheck wake.
- `:seon.agent/state` enum (`:129`) → 5-value, IN PLACE.
- `stub-llm` (`client.cljs:1828`) → emit `(message/user …)` not `reply!`.
- `parse-forms` (`internal.cljc:348`) → §4 in place.
- `warn.cljs` render-warnings — drop the `<warnings>` XML envelope for the
  comment-block form, in place (no `render-warnings-v2`).

ENUM RIPPLE — must move `:running → :active` in the SAME atomic patch (else agents
silently don't wake / mis-render): `live-agent-ids` (`client.cljs:1870` — this
installs triggers; a `:waiting` agent must still get one), `all-running-agents`
(`render/default.cljs:220`), the `(= :running state)` pulse checks
(`inspector.cljs:259/376/726`), `resumable-agent-ids` (`client.cljs:1886`). Resolve
`:completed`-is-wakeable vs the `completed-at` resume predicate: arm triggers for
every non-`:terminated` agent.

KEEP (verified-good): per-form eval isolation, `eval-count = n-ok+n-fail`, the
`origin`/`handled?` wake gate (`inbound-msg-datom?` `agent.cljs:557-578` — reuse it
for the transcript head render too), the per-tx listener, `result/<id>` vars.

DECISION on `:seon.agent.turn/status` (`agent.cljs:262`, `[:running :done :error]`):
keep it turn-level and distinct from the agent FSM (a turn is running/done/error; an
agent is idle/active/…). Document the distinction; do not rename.

## 6. Build order — units, dependencies, parallel vs sequential

DeepSeek live drives are pre-authorized. The target is a WORKING slice, live-tested,
then iterate the format. Each unit below has a concrete **deliverable** and a
**verify** (a REPL/live check, not a test suite). Tests are deferred (see bottom).

### Dependency graph

```
U1 schema+state ──┬──> U2 messaging  ─┐
 (FOUNDATION,     ├──> U3 lifecycle   ─┤
  do first)       ├──> U5 turn ──> U4 loop ─┐
                  └──> U6 transcript ───────┴──> LIVE TEST ──> U7 prose · U8 tile
 (parallel, anytime after nothing): U0 ALS-unify+rename · enum-ripple (needs U1's enum)
```

**Sequential backbone (critical path):** U1 → U5 → U4 → LIVE TEST. The loop (U4)
calls `run-turn!` (U5); the turn needs the schema (U1).
**Parallel off U1:** U2 (messaging), U3 (lifecycle verbs), U6 (transcript) — different
files, no dependency on the loop. U6 can be built alongside U4; the live test needs
both. **Independent anytime:** U0 (ALS unify/rename) + the enum-ripple rename (gated
only on U1 defining `:active`). Per the one-live-cluster rule, FILE edits parallelize
but pod-affecting **verification is serialized** (one pod).

### Units

- **U1 — schema + state helpers** (`seon.agent`). FOUNDATION, do first. The 5-value
  enum + `wake`/`max-turns-per-loop`/`parent`/`wait-note` schemas + entity map;
  `current-state`/`set-state!`/`fresh-wake!`; update `create!`/`boot!` to seed `:idle`.
  *Verify:* REPL — `create!` an agent, read `:seon.agent/state :idle`, `set-state!`
  round-trips, `fresh-wake!` mints a distinct id.
- **U2 — messaging verbs** (`seon.agent.message`). `message/user` + `message/agent`
  (refuses `to = me`, loud) + schemas; DELETE `reply!`; fix `stub-llm`. *Verify:* REPL
  — `message/user` writes one row from=me to=user; `message/agent` to a peer writes a
  row; `message/agent` to self → error envelope, no row.
- **U3 — lifecycle verbs** (`seon.agent`). `wait`/`complete`/`terminate` as state
  transacts (`complete` repurposes `complete!`; parent-delivery is the thin
  conditional). *Verify:* REPL — each verb sets the right `state` (+ `wait-note` for
  wait); no self-message emitted.
- **U5 — turn execution** (`seon.agent.turn`). `run-turn!` + `open-turn!`/`close-turn!`
  (stamp `:seon.agent.turn/wake`); DELETE the self→self fold + `woken-by` + the
  `messages` attr. *Verify:* REPL — one turn opens/closes, stamps wake, records evals,
  flips turn-status `:done`; no self→self message row exists.
- **U4 — loop + wake** (`seon.agent.fsm`) — DONE 2026-06-23. `wake-handler` +
  `install-wake-trigger!`, `run-loop!`, halt/cap/`effective-cap`/recheck; DELETED
  `!kick-scheduled` + all answer-accounting + the self→self halt-notes +
  `empty-completion`/`give-up`/`max-empty-reprompts`. Carved `agent.cljs` into
  `seon.agent.turn` (the turn machinery + `:seon.agent.session/*`/`:seon.agent.turn/*`
  schemas) + `seon.agent.fsm` (loop + wake) + a slimmed `seon.agent` (record +
  verbs + `inbound-msg-datom?` gate). The wake trigger moved out of `agent/boot!`
  into the client boot path (`boot-one-agent!` + `rearm-user-triggers!`), so
  `seon.agent` requires NEITHER fsm NOR turn (acyclic). KEY FIX: the turn no longer
  touches `:seon.agent/state` — the LOOP owns it (sets `:active` at wake, `:idle` in
  its finally); a per-turn `:idle` reset had halted the loop after one turn.
  *Verified live (stub-driven, OgT-2606231721):* wake → one loop; double-wake → ONE
  survivor (loser `halt superseded`, ran 0 turns); no-forms → 3 turns (empty-streak
  guard) then `halt no actionable forms → :idle (clean)`; cap=5 → exactly 5 turns
  then `halt cap — 5/5`; `:terminated` → 0 LLM calls (unwakeable). Shadow 0 warnings,
  no circular dependency. (DeepSeek end-to-end drive is the orchestrator's clean-boot test.)
- **U6 — transcript + readline** (`seon.ctx.transcript` + `seon.ctx.system`). The §2
  comment-block render (masthead, `;;;` turn headers, `;;=>` results via
  `format-eval-row`, `;;; ◀` inbound head-lines, the live readline); rewrite
  `system-text`; DELETE `ctx/prompt` + `agent/turns`; emit turn 0 as the bootstrap.
  *Verify:* render a driven agent's transcript and eyeball EVERY state (active, waiting,
  resumed, batch, at-cap) — it reads clean, no XML, no fabricated `;;=>`.
- **LIVE TEST (DeepSeek)** — drive idle→active→wait→active→complete; send a batch of 3
  and confirm one targeted reply suffices (no cap-spin); a peer message to an idle
  agent starts a loop, to an at-cap agent bumps the cap; confirm the agent never
  writes `;;=>` itself. **Iterate the format here.**
- **U7 — prose policy** (`repl/internal.cljc`, §4) and **U8 — live-tile = todo queue**
  — independent, AFTER the format is proven.
- **U0 — ALS unify + rename** (`seon.db`, anytime) — merge the two ALS instances,
  `with-tx-context → with-tx-meta`. Mechanical; not on the critical path.

### Tests — deferred wholesale (owner directive)

Do NOT rewrite tests after each change while we're still proving the format — they
pin exact output and we WILL discover issues in flight. Each deletion unit just
**deletes** the dead-model test namespaces (`agent_loop_test` whole-ns, the
`reply!`/XML/`:running` assertions) so the build compiles — no replacements. Rely on
the per-unit REPL verifies + the live DeepSeek drive. Fresh FSM tests + gym come only
once the system is confirmed to be what we wanted.

## 7. What we are explicitly NOT doing

timestamp/count answer-accounting; the forged-message wake (#43); latch-narrowing;
whole-reply parinfer repair; full refusal-gate removal; the `:seon.turn-request`
dispatcher/effect-bus; the literal tx-log walk; mandatory eval fences; worker
`postMessage` of the db value; a subagent "result channel" abstraction with no
producer; any `*-v2` symbol or parallel ns.

## Cross-references

- [[conversation-timeline-2026-06-22]] (kill answer-accounting — superseded halt),
  [[context-v4-repl-realism-2026-06-11]], [[transcript-redesign-2026-06-18]] (the
  REPL transcript this consolidates), [[reliability-fixes-49-53-2026-06-21]] (#50
  prose, overridden for NL prose), [[live-tiles-prd-2026-06-11]],
  [[tile-isolation-prd-2026-06-21]], [[reply-hook-and-myns-home-design-2026-06-22]]
  (#27 fold survives as a hook fire-site).
- `src/seon/agent.cljs`, `src/seon/agent/message.cljs`, `src/seon/agent/todo.cljs`,
  `src/seon/ctx.cljs` + `src/seon/ctx/transcript.cljs` + `ctx/prompt.cljs`,
  `src/seon/agent/turns.cljs`, `src/seon/repl/internal.cljc`, `src/seon/client.cljs`
  (`stub-llm`, the turn-0 bootstrap), `src/seon/web/inspector.cljs`,
  `src/seon/render/default.cljs`, `src/seon/warn.cljs`.
