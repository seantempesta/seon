---
type: prd
status: active
tags: [prd, agent, flow]
---

# The agent loop is a function of the database

## Overview

The agent loop is DB-REACTIVE. The agent's runnability is ONE datom —
`:seon.agent/state` on the agent record (`agent.cljs:89`). A datahike
`d/listen` tx-listener (`fsm.cljs:229-291`, armed via
`install-wake-trigger!` at `fsm.cljs:293-312`) fires on every transact;
when a freshly-added inbound `:seon.agent.message/to` datom lands AND the
agent is in a wakeable state, the listener mints a fresh wake-episode token,
flips state to `:active`, and starts `run-loop!` (`fsm.cljs:148-216`). The
loop re-reads `{state wake}` off the record EACH iteration and halts on the
single `cond` that IS the whole stop policy. On a clean exit it resets state
to the single wakeable state. There is NO kick-atom, NO wake flag, NO
`!kick-scheduled` — the FSM refactor (`612e17e…0126b7c`) already deleted that
era. The DB is the sole coordination surface: state + wake-token are stored
datoms, the cap is a derived datalog count, the wake is a tx-listener.

This spec is the LOOP half of the agent-fsm push. Its sibling
[[docs/prds/agent-fsm/context-render.md]] is the CONTEXT/RENDER half: the
transcript renders this loop's event stream, and the activity-log
RENDERABLE is described render-side there; the activity-log DERIVATION (the
history query + the tx-meta it joins) is described loop-side here.

The one design change this spec asks for: **the state model is over-built.**
Five states (`:idle :active :waiting :completed :terminated`) collapse to
three BEHAVIORAL classes the loop and wake actually distinguish — running /
wakeable / dead. The differences between `:idle`, `:waiting`, and
`:completed` are pure INTENT (what it is waiting for, the completion result,
why the last loop ended) — that intent is DATA, not separate machine states.
This spec simplifies to `:active`, `:idle`, `:terminated` (with `:idle` the
single wakeable state) and moves the intent into a stop-reason tx-meta + the
existing `:seon.agent/wait-note` field. This is the same "don't be a dumbass"
consolidation the FSM refactor already started — finish it.

## The state model: 5 → 3

### Current model (5 states) — `agent.cljs:89`

```clojure
(schema/register! :seon.agent/state
  [:enum :idle :active :waiting :completed :terminated])
```

Semantics as documented (`agent.cljs:35-45,83-89`):

| State | Meaning | Wakeable? |
|---|---|---|
| `:idle` | neutral / between work | YES → `:active` |
| `:active` | a loop is running | NO (running loop's sliding cap picks up inbounds) |
| `:waiting` | parked via `(agent/wait note)` | YES → `:active` |
| `:completed` | finished via `(complete result)` / no-forms | YES → `:active` |
| `:terminated` | orchestrator kill | NO (unwakeable until state changes) |

### The verified evidence — the loop treats 3 of the 5 IDENTICALLY

The directive's claim is that `:idle`, `:waiting`, `:completed` are all
wakeable and are gated identically. Verified against `fsm.cljs` end-to-end:

1. **The wake gate branches on a SET, never on the individual value**
   (`fsm.cljs:266`):

   ```clojure
   (if (contains? #{:active :terminated} state)
     (log id "wake skipped" …)        ; not wakeable
     …mint wake, set :active, run-loop!)   ; wakeable
   ```

   The "wakeable" branch is `(complement #{:active :terminated})` — i.e.
   `:idle`, `:waiting`, AND `:completed` take the EXACT same path. Nothing
   reads which of the three it was.

2. **`armable-agent-ids` branches on a SET too** (`agent.cljs:374-394`):
   `[(not= :terminated ?state)]` — every non-`:terminated` agent is armable.
   `:idle`/`:waiting`/`:completed` are indistinguishable to trigger arming.

3. **The loop stop `cond` never reads `:waiting`/`:completed` by name.** The
   top-of-loop check is `(not= :active state)` (`fsm.cljs:163`) — ANY
   non-`:active` value halts the loop identically. The post-turn verb check
   is `(and (not= :active after) (not= :idle after))` (`fsm.cljs:195`) — it
   halts on "anything that is neither `:active` nor `:idle`", logging
   `state=after` generically; it does not branch on WHICH terminal value.

4. **The wait-note is surfaced from a DATA field, not from the state.**
   `(agent/wait note)` writes `:seon.agent/wait-note` (`agent.cljs:105`,
   `agent.cljs:520-536`) — a separate string attr. Nothing reads the
   `:waiting` state to find the note; readers read `:seon.agent/wait-note`.
   So "waiting" already carries its intent as data, independent of the state
   enum value.

5. **`complete`'s only behavioral extra is the parent-delivery conditional**
   (`agent.cljs:553-556`), which keys off `:seon.agent/parent` being set —
   NOT off the `:completed` state. The state write itself is just "park,
   wakeable."

Conclusion: nothing in the wake/stop/cap logic NEEDS `:waiting` or
`:completed` as distinct states. They are wakeable-parked + a reason, and the
reason is already (wait) or should be (complete) data.

### Simplified model (3 states)

```clojure
(schema/register! :seon.agent/state [:enum :active :idle :terminated])
```

| State | Behavioral class | Wakeable? | Replaces |
|---|---|---|---|
| `:active` | a loop is running | NO — running loop's sliding cap absorbs new inbounds | `:active` |
| `:idle` | not running; a message wakes it | YES → `:active` | `:idle` + `:waiting` + `:completed` |
| `:terminated` | dead; orchestrator kill | NO — change state first | `:terminated` |

The old → new mapping:

- `:idle` → `:idle` (unchanged)
- `:active` → `:active` (unchanged)
- `:waiting` → `:idle`, with the INTENT carried by `:seon.agent/wait-note`
  (already a field) AND the stop-reason tx-meta `:seon.agent.loop/stop-reason
  :wait` on the parking transition.
- `:completed` → `:idle`, with the INTENT carried by the stop-reason tx-meta
  `:seon.agent.loop/stop-reason :complete` (and the result already delivered
  via `message!` to the parent or the human — `complete` does not need a
  state to hold the result; the result is a message).
- `:terminated` → `:terminated` (unchanged) — the ONE state that genuinely
  changes behavior (unwakeable), so it stays.

Rationale: the three behavioral classes the system actually branches on are
RUNNING / WAKEABLE / DEAD. `:waiting` and `:completed` were `:idle`-with-a-
story; the story is `:seon.agent/wait-note` + `:seon.agent.loop/stop-reason`,
both already data (one existing, one new for the activity log anyway). One
fewer enum value the wake gate, the loop, and `armable-agent-ids` must reason
about; the "why did the last loop end" question is answered by querying the
state-transition history's tx-meta, which is strictly richer than a state
that only told you the most-recent reason and got overwritten on the next
transition.

### Every changed call site

| Call site (file:line) | Today | Becomes |
|---|---|---|
| `:seon.agent/state` enum (`agent.cljs:89`) | `[:enum :idle :active :waiting :completed :terminated]` | `[:enum :active :idle :terminated]` |
| state docstrings (`agent.cljs:35-45`, `:83-89`) | 5-state table | 3-state table (running / wakeable / dead) |
| `wait` (`agent.cljs:520-536`) | tx `{:state :waiting :wait-note note}` | tx `{:state :idle :wait-note note}` with tx-meta `:seon.agent.loop/stop-reason :wait`. Still returns `:idle` (was `:waiting`); the verb's value is the new state. |
| `complete` (`agent.cljs:538-560`) | tx `{:state :completed}` + parent delivery | tx `{:state :idle}` with tx-meta `:seon.agent.loop/stop-reason :complete` + the SAME parent-delivery conditional. Returns `:idle`. |
| `terminate` (`agent.cljs:562-573`) | tx `{:state :terminated}` | UNCHANGED (`:terminated` stays). Add tx-meta `:seon.agent.loop/stop-reason :terminate` for the activity log. |
| `run-loop!` finally (`fsm.cljs:209-216`) | resets `:active`→`:idle` on clean exit | UNCHANGED — `:idle` is still the clean-exit state. Add tx-meta `:seon.agent.loop/stop-reason` (`:no-forms`/`:turn-cap`/`:turn-error`) so the implicit exits are logged too. |
| `run-loop!` top check (`fsm.cljs:163`) | `(not= :active state)` halts | UNCHANGED — any non-`:active` still halts. |
| `run-loop!` verb check (`fsm.cljs:195`) | `(and (not= :active after) (not= :idle after))` | **REPLACE with `(not= :active after)` → halt.** The loop already checks state at the TOP and AFTER the turn (the owner's "check at both" — each is one cheap `db/entity` state read). With verbs writing `:idle`, the after-turn check should halt on ANY non-`:active` (verb-park, external reset, terminate) so it halts IMMEDIATELY, not on the next iteration. No LLM turn is wasted either way — `run-turn!` (`fsm.cljs:178`) runs only AFTER the cond, so even the next top check halts before any new turn — but the after-turn `(not= :active after)` is tidy + immediate, and the intent (`:wait`/`:complete`) lives in the stop-reason tx-meta, not a state. |
| `wake-handler` gate (`fsm.cljs:266`) | `(contains? #{:active :terminated} state)` | UNCHANGED — `:idle` is the only wakeable state, exactly the complement. |
| `wake-handler` log (`fsm.cljs:269-274`) | branches text on `:active` vs other | simplify: `:active` = "running loop covers it"; else `:terminated` = "terminated; change state first". |
| `armable-agent-ids` (`agent.cljs:374-394`) | `[(not= :terminated ?state)]` | UNCHANGED — still "everything but `:terminated`". |
| `inbound-msg-datom?` (`agent.cljs:404-418`) | gate clauses | UNCHANGED by the state collapse, but DELETE the `handled?` clause (see below). |
| `create!` (`agent.cljs:446-453`) | seeds `:state :idle` | UNCHANGED. |

Net: the enum loses two values, ONE `cond` arm is deleted (`fsm.cljs:195`),
three verbs write `:idle` instead of distinct parked states + carry a
stop-reason tx-meta, and the docstrings shrink to three states. No new state,
no wake/stop/cap logic regression — every branch that mattered keyed off the
RUNNING / WAKEABLE / DEAD partition, which is preserved exactly.

## DB-reactive wake

The wake is one datahike `d/listen` tx-listener per agent. It IS
DB-reactive — no polling, no function-call mousetrap.

Wiring chain (verified):

- `seon.client` arms `install-wake-trigger!` per armable agent at boot and on
  hot reload.
- `install-wake-trigger!` (`fsm.cljs:293-312`) registers `(wake-handler
  input)` via `db/listen!` under a stable per-agent key
  `[:seon.agent/user-message-trigger id]`. Idempotent: it unlistens the prior
  key first, so a hot reload never leaves two listeners firing for one agent.
- `db/listen!` (`db.cljs:904`) → `d/listen` on the local pod conn; the wrapper
  builds `{:seon.db/db :seon.db/db-before :seon.db/datoms :seon.db/attr-index}`
  and calls the handler on EVERY transact (datahike fires post-commit).
- `wake-handler` (`fsm.cljs:229-291`): filters the added
  `(:seon.agent.message/to attr-index)` datoms through
  `agent/inbound-msg-datom?`; partitions out hop-exhausted ones (loud
  `console.error`, no wake — `fsm.cljs:253-261`); for the waking set reads
  `:seon.agent/state` off the snapshot. Wakeable (`:idle`) → `js/setTimeout 0`
  (breaks the AsyncLocalStorage scope so the loop re-enters `with-agent`) →
  `fresh-wake!` → `set-state! :active` → `run-loop!` stamped with that wake.

The inbound gate (`inbound-msg-datom?`, `agent.cljs:404-418`): an added
`:seon.agent.message/to me` datom WAKES iff `to ∋ me` ∧ `from ≠ me` ∧
`origin ∈ {:human :agent}` (i.e. NOT `:core`) ∧ `hops < warn/hop-cap`. The
to-check is load-bearing (every agent installs the listener; without it one
message wakes every agent). After this spec, the `handled?` clause is GONE
(see "Delete `:seon.agent.message/handled?`").

Concurrency is optimistic via the DB, no atom/CAS (`fsm.cljs:13-21`): two
simultaneous wakes both write `:active` + their own `:seon.agent/wake` token;
last-writer-wins; the losing loop re-reads a different wake at `fsm.cljs:166`
and bails. The only stateful holders in the path are genuinely-stateful
runtime artifacts (the datahike conn, the bootstrap `compile-state`, the
per-process `!sessions-opened-this-run` session-freshness atom in
`turn.cljs`) — none holds wake/stop coordination state.

## The loop + stop policy

`run-loop!` (`fsm.cljs:148-216`) runs agentic turns stamped with `my-wake`
until a stop fires; the whole policy is the `cond`. Each iteration re-reads
`{state wake}` off the record:

| # | Trigger (file:line) | Condition | Final state | stop-reason tx-meta |
|---|---|---|---|---|
| A | external `fsm.cljs:163` | `(not= :active state)` | whatever was written | (the writer's) |
| B | superseded `fsm.cljs:166` | `(not= wake my-wake)` | winner's `:active` | none (no transition) |
| C | cap `fsm.cljs:170` | `turns-this-wake ≥ effective-cap` | `:idle` (finally) | `:turn-cap` |
| D | turn error `fsm.cljs:190` | turn `:status :error` | `:idle` (finally) | `:turn-error` |
| E | verb `fsm.cljs:195` | post-turn state ≠ `:active`/`:idle` | (verb wrote it) | (the verb's) |
| F | no forms `fsm.cljs:198` | `eval-count = 0`, `empty-streak ≥ 2` | `:idle` (finally) | `:no-forms` |
| G | threw `fsm.cljs:206` | `catch :default` | `:idle` (finally) | `:turn-error` |

After the simplification, arm E (`fsm.cljs:195`) is DELETED: the verbs now
write `:idle`, so a verb-park is caught by arm A on the next iteration's top
check (same as any external `:idle`), and the verb already stamped its own
stop-reason. The implicit exits (A/C/D/F/G) land `:idle` via the single
`finally` block (`fsm.cljs:209-216`), which gains a stop-reason tx-meta on the
reset transact so the activity log can name WHY. The turn (`turn.cljs`) never
writes `:seon.agent/state` — only `:seon.agent.turn/status`; the loop owns the
implicit state writes, the verbs own the explicit ones.

Nuance preserved: the no-forms halt uses `eval-count = n-ok + n-fail`
(attempted forms). A turn where every form ERRORED is not a quiet halt — it
recurs so the next turn shows the errors. The 2-turn `empty-streak` guard
(`fsm.cljs:199`) gives the model a "thinking mode" — two consecutive
zero-form turns before halting.

## Turns + sliding-window cap

The per-loop cap is a SLIDING WINDOW, fully derived (no stored grant, no
cap-note message). Helpers in `fsm.cljs:51-145`:

- `default-max-turns-per-loop` (`:51`) = 20.
- `max-turns-per-loop` (`:56`) = `:seon.agent/max-turns-per-loop` on the
  entity, else env `SEON_MAX_TURNS_PER_LOOP`, else 20.
- `turns-this-wake` (`:68`) = datalog `count` of turns whose
  `:seon.agent.turn/wake` = `my-wake`. Derived.
- `first-turn-at-this-wake` (`:85`) = the `:at` of the first turn this wake
  (bounds the inbound window).
- `inbounds-during-this-wake` (`:104`) = count of inbound messages (`to ∋
  me`, `from ≠ me`, `origin ≠ :core`, `hops < hop-cap`, `:at ≥` the first
  turn of this wake) — the SAME gate as the wake, expressed in datalog.
- `effective-cap` (`:134`) = `max-turns-per-loop + inbounds-during-this-wake`.

Every inbound (human OR peer) that lands mid-wake grants +1 turn, so a
message that arrives during an LLM call always earns a turn to be SEEN and
answered. None of these helpers branch on the state enum — the cap is purely
turn-count vs base+inbounds, unaffected by the 5→3 collapse.

## Activity log

The activity log is a DERIVED timeline over the agent's `:seon.agent/state`
history — bitemporal datahike — joined to each transition's `:db/txInstant`
and the cause/stop-reason tx-meta. Nothing is stored that isn't already a
datom: the state-transition HISTORY is the log.

The pod conn is opened with `:keep-history? true` (a boot precondition,
asserted at `internal.cljs:1412`), so `d/history` and `d/as-of` work on the
pod db value — verified live 2026-06-24. seon.db does NOT yet expose
wrappers; this spec adds the thin ones (see Functions).

Every transact already auto-stamps `:db/txInstant` plus the tx-meta set
(`internal.cljs:101-106`): `:seon.db/agent-id`, `:seon.db/session-id`,
`:seon.db/turn-id`, `:seon.db/eval-id`, `:seon.db/origin`, `:seon.db/replay?`,
`:seon.db/resume-marker?`. (`:seon.db/request-id`, also seen on tx-meta, is NOT
in this set and is orthogonal: it is the WIRE-SERVER's per-write ECHO-SUPPRESSION
id — the pod generates a UUID per forwarded write (`store/wire.cljs:235`), the
wire-server threads it into the committed tx-meta (`server/wire.clj:398-419`), and
the pod recognizes its own tx on the broadcast feed and skips re-firing local
listeners (`:own-skips`, live-proven). GENUINE provenance — keep it. It's injected
below the `seon.db` layer, so it isn't in the pod's `tx-meta-attrs`. TWO defects:
(1) it is BADLY NAMED — `:seon.db/request-id` implies an HTTP request + a db-core
concern; it is neither. RENAME to `:seon.store.wire/write-id` (a wire-protocol
per-write id; `seon.store.wire` generates it, `seon.server.wire` persists it) so
the namespace alone says what it is. (2) it is declared only as a raw datahike
`:db/ident` in `server/wire.clj` `seed-base-schema!` — and that is CORRECT for the
writer: the wire-server uses RAW datahike schema, not Malli/`seon.schema`, so the
seed IS its declaration; it is a wire-protocol field, NOT a `seon.db` Malli-domain
attr, and must not be forced into that registry. FIX = the rename, as ONE
coordinated patch across the wire boundary (both processes restarted together — a
protocol key both sides must agree on; no migration, only live in-flight ids
matter for echo suppression): JVM `seed-base-schema!` (`server/wire.clj:81`) + the
broadcast threading (`wire.clj:398-419,343-355`), and pod generation + match
(`store/wire.cljs:235,324-334`, `!own-request-ids`), all move to
`:seon.store.wire/write-id`; rewrite the seed docstring to name the
echo-suppression purpose. The activity log uses `:db/txInstant` + the declared agent-side set; this
wire id is not involved.) This spec adds
TWO loop-specific tx-meta attrs, stamped on state-transition transacts only:

- `:seon.agent.loop/cause` — a `:seon.db/ref` to what caused the transition
  (e.g. the waking message entity, on the `:idle`→`:active` wake transact).
- `:seon.agent.loop/stop-reason` — a `:keyword` naming why a loop ended:
  `:complete` / `:terminate` / `:wait` / `:no-forms` / `:turn-cap` /
  `:turn-error`.

The activity-log RENDERABLE (the agent-facing / inspector-facing timeline
view — "created · idle→active ◀ msg · hit turn cap") is render-side and lives
in [[docs/prds/agent-fsm/context-render.md]]. This spec owns the DERIVATION:
the history query that produces the timeline rows.

The derivation, conceptually: walk the agent entity's `:seon.agent/state`
datoms across `d/history` (each carries its transaction), pull each tx's
`:db/txInstant` + `:seon.agent.loop/cause` + `:seon.agent.loop/stop-reason`,
order by txInstant. The result is the activity log — a function of the DB at
render time, self-healing (no stored log to clear).

## Delete `:seon.agent.message/handled?`

`:seon.agent.message/handled?` (`:boolean`, registered at `message.cljs:53`,
entity-kind optional at `message.cljs:85`) is DEAD-BUT-WIRED: read in exactly
two negative gates, written by NOTHING in production `.cljs`.

- Reader 1 — the wake gate `inbound-msg-datom?` (`agent.cljs:418`):
  `(not (true? (:seon.agent.message/handled? msg)))`.
- Reader 2 — the transcript gate `transcript/inbound-msg?`
  (`transcript.cljs:101`): the same clause, in the hand-copied predicate.
- Writers — ZERO. The only `handled? = true` writes are in
  `test/seon/agent_loop_test.cljs.disabled`.

The intended writer was a deterministic tx-hook (a future chat-control like
`/persona`) that would consume a message in-tx without waking the agent. The
DB-reactive replacement is cleaner and already gated: route any "consumed,
don't wake" message through `:seon.agent.message/origin :core` (the gate
already excludes `:core` — `agent.cljs:417`, `fsm.cljs:126`). A slash-command
is substrate-originated, not human-conversational; `:core` is the honest
provenance, and it is permanently non-waking and non-rendering (self-healing).

"Addressed" derives from the linked todo's completion (see Auto-todo on inbound,
below), NOT from a `handled?` flag.

Deletion (no successor flag):

- Schema register at `message.cljs:53` and the entity-kind optional entry at
  `message.cljs:85`.
- The clause at `agent.cljs:418` (becomes always-true → drop it).
- The clause at `transcript.cljs:101` (same).
- The `.disabled` test refs (already disabled).

## Auto-todo on inbound (message intake — independent of render)

Message processing is WRITE-side, independent of context render (render is a pure
read projection). When an inbound `:human` message lands, create one address-todo
RIGHT AWAY — not deferred to the agent's next turn or to render. The todo carries
a SHORT clipped preview (NOT the full message) + a `:seon.agent.todo/message`
back-ref; the agent pulls the full message by its identity attr
`:seon.agent.message/id` (`message.cljs:29`): `(db/pull '[*] [:seon.agent.message/id id])`.
`context-render.md` only RENDERS the resulting todo.

DECISION — the write fires atomically in `seon.agent.message/message!`
(`message.cljs:161`), in the SAME tx as the inbound message (atomic with the
message's birth, no cascade, no idempotency concern), gated on origin `:human`.
(The alternative — a sibling `db/listen!` reactor on inbound `:human` datoms — was
rejected: a post-commit cascade needing idempotency via the back-ref.)

EMBED a worked example (code-teaches): the building blocks exist (`message.cljs:29`
id is identity; `todo.cljs:24` has an `add!` `;;=>` example; the seon.db cheat-sheet
teaches pull-by-lookup-ref) — add ONE example showing the clipped-preview todo +
pulling the linked message by id.

## Dormant handler registry

There is a SECOND, dormant wake mechanism that never fires in the pod: the
DB-stored handler registry (`seon.handler`, the `:seon.handler/*` schemas)
plus a seeded `:wake/on-message` handler entity (`client.cljs:2018-2024`)
pointing at `'seon.handlers.wake/wake-on-message`, matching
`:seon.agent.message/to` with `:on-origin #{:user :agent}`.

No `.cljs` dispatcher reads `query-handlers` for DISPATCH — only the
inspector reads it for DISPLAY (`inspect.cljs:106`). The real wake is the
`db/listen!` closure (`wake-handler`). The registry's dispatcher "lives in
`seon.runtime`", which is CLJ-only (the paused JVM track). So an inspecting
human sees a `:wake/on-message` handler entity that does nothing in the pod.

DECISION (owner, 2026-06-24): DELETE it entirely. It is an explicit v0 stub
(`handler.cljs:15` "does NOT install a dispatcher"; `handlers/wake.cljs:10` "no
`d/listen!` bus yet") that the live `db/listen!` wake superseded — never
dispatched, only displayed, so it lies to the inspector. Remove: the
`seon.handler` ns + all `:seon.handler/*` and `:seon.handler.match/*` schemas;
the seeded `:wake/on-message` (`client.cljs:2018-2024`) + the `seon.handlers.wake`
require + the handler fn `seon.handlers.wake/wake-on-message`; and the inspector
display (`inspect.cljs:107` `:seon.handler/list`, `inspector.cljs:207`). ONE wake
path remains: the `db/listen!` closure. The declarative-trigger IDEA (agent-
authorable DB triggers on a real dispatcher) is worth reviving LATER, built clean
on `db/listen!` — not shipped as a seeded-but-dead stub now.

## Inbound-predicate dedup

`transcript/inbound-msg?` (`transcript.cljs:88-102`) is a HAND-COPY of
`agent/inbound-msg-datom?` (`agent.cljs:404-418`), kept only to dodge a
require cycle (the `TODO unify` at `transcript.cljs:84`: `seon.agent` requires
`seon.ctx.transcript`, so requiring it back cycles). Two copies of the wake
rule WILL drift — the gate is load-bearing and a divergence means a message
wakes but doesn't render (or vice versa).

Fix: move the gate to a cycle-free ns and have both callers use it. The two
predicates differ in shape (the agent version takes a datom + db + my-eid;
the transcript version takes a pulled message map + my-eid), so the shared
form is the boolean RULE — `from ≠ me ∧ origin ∉ {:core} ∧ hops < hop-cap`
(the `handled?` clause is being deleted, simplifying both). Candidate homes:
`seon.agent.message` (owns the message data) or a tiny `seon.agent.gate`.
Both call the one rule; the datom-vs-map adapter stays at each call site.

## Schemas (new / changed)

New (tx-meta, stamped on state-transition transacts):

- `:seon.agent.loop/cause` — `:seon.db/ref` (what caused a transition; e.g.
  the waking message). References the canonical ref shape; never inline.
- `:seon.agent.loop/stop-reason` — `:keyword`
  (`:complete`/`:terminate`/`:wait`/`:no-forms`/`:turn-cap`/`:turn-error`).

These must be added to `internal.cljs` `tx-meta-attrs`
(`internal.cljs:101-106`) so the auto-merge + the `assert-preconditions!`
registration check know about them, AND registered as scalar attrs in
`seon.db` (the bridge derives the datahike declaration).

Changed:

- `:seon.agent/state` (`agent.cljs:89`) — `[:enum :idle :active :waiting
  :completed :terminated]` → `[:enum :active :idle :terminated]`.

Deleted:

- `:seon.agent.message/handled?` (`message.cljs:53` register + `:85`
  entity-kind entry).

Unchanged but worth noting: `:seon.agent/wait-note` (`agent.cljs:105`) stays —
it carries the wait INTENT that the `:waiting` state used to imply.

## Functions (new / changed)

New thin seon.db wrappers (bitemporal history is reachable on the pod db value
— `keep-history? true` — but seon.db exposes no wrappers yet; these are the
`seon.db` API for the activity log derivation):

- `seon.db/as-of` — map-in `{:seon.db/db :seon.db/t}` → db value as of `t`
  (wraps `d/as-of`).
- `seon.db/history` — map-in `{:seon.db/db}` → the history db value (wraps
  `d/history`); queries over it return all assertions/retractions with their
  transactions.
- `seon.db/since` — map-in `{:seon.db/db :seon.db/t}` → db value of datoms
  added after `t` (wraps `d/since`).

Each fully specced map-in/map-out per CLAUDE.md; each takes a db value (the
pod's local lazy db) — these are READ-side, no wire-server round-trip.

Changed (the state-model collapse + tx-meta stamping):

- `seon.agent/wait` (`agent.cljs:520`) — write `:state :idle` (was
  `:waiting`); add stop-reason tx-meta `:wait`; return `:idle`.
- `seon.agent/complete` (`agent.cljs:538`) — write `:state :idle` (was
  `:completed`); add stop-reason tx-meta `:complete`; keep parent delivery;
  return `:idle`.
- `seon.agent/terminate` (`agent.cljs:562`) — add stop-reason tx-meta
  `:terminate` (state unchanged).
- `seon.agent.fsm/run-loop!` (`fsm.cljs:148`) — DELETE the post-turn verb arm
  (`fsm.cljs:195`); add stop-reason tx-meta to the `finally` reset
  (`:no-forms`/`:turn-cap`/`:turn-error`) and to the explicit halt branches.
- `seon.agent.fsm/wake-handler` (`fsm.cljs:229`) — stamp
  `:seon.agent.loop/cause` (the waking message ref) on the
  `:idle`→`:active` transition; simplify the skip-log branch.
- `seon.agent/inbound-msg-datom?` (`agent.cljs:404`) — drop the `handled?`
  clause; ideally relocate the boolean rule to a cycle-free ns.
- `seon.ctx.transcript/inbound-msg?` (`transcript.cljs:88`) — drop the
  `handled?` clause; call the shared rule.
- An activity-log derivation fn (loop-side) that queries
  `:seon.agent/state` history + tx-meta into ordered timeline rows; the
  RENDERABLE that displays them is in the context-render spec.

## Blast radius (file:line)

- `src/seon/agent.cljs:89` — state enum 5→3.
- `src/seon/agent.cljs:35-45,83-89` — state docstrings → 3-state.
- `src/seon/agent.cljs:404-418` — `inbound-msg-datom?` drop `handled?` clause
  (`:418`); relocate rule to cycle-free ns.
- `src/seon/agent.cljs:520-536` — `wait` writes `:idle` + stop-reason tx-meta.
- `src/seon/agent.cljs:538-560` — `complete` writes `:idle` + stop-reason
  tx-meta; parent delivery unchanged.
- `src/seon/agent.cljs:562-573` — `terminate` + stop-reason tx-meta.
- `src/seon/agent/fsm.cljs:163` — top check unchanged (any non-`:active`
  halts).
- `src/seon/agent/fsm.cljs:195` — DELETE the verb arm.
- `src/seon/agent/fsm.cljs:209-216` — `finally` reset + stop-reason tx-meta.
- `src/seon/agent/fsm.cljs:229-291` — `wake-handler` stamp `cause`; simplify
  skip-log.
- `src/seon/agent/fsm.cljs:266` — wakeable gate unchanged (`:idle` is the
  complement of `#{:active :terminated}`).
- `src/seon/agent/message.cljs:53,85` — DELETE `:seon.agent.message/handled?`.
- `src/seon/ctx/transcript.cljs:88-102` — drop `handled?` clause; call the
  shared rule (`:101` is the clause).
- `src/seon/db.cljs` — register `:seon.agent.loop/cause` +
  `:seon.agent.loop/stop-reason`; add `as-of`/`history`/`since` wrappers.
- `src/seon/db/internal.cljs:101-106` — add the two new tx-meta attrs to
  `tx-meta-attrs` (if they auto-merge) OR pass them explicitly at the
  transition call sites (decision in Open questions).
- `src/seon/client.cljs:2018-2024` — DELETE the `:wake/on-message` registry
  seed; drop the `seon.handlers.wake` boot require (`client.cljs:128`).
- `test/seon/agent_loop_test.cljs.disabled` — `handled?` refs (already
  disabled).

## Sequence (never leaves a parallel system)

1. **Add the tx-meta attrs** (`:seon.agent.loop/cause`,
   `:seon.agent.loop/stop-reason`) — register in seon.db, wire into the
   transition call sites. (Pure addition; nothing reads them yet.)
2. **Delete `:seon.agent.message/handled?`** — schema + the two gate clauses
   in the same patch (do not leave a dangling reader). Atomic.
3. **Unify the inbound predicate** — move the rule to a cycle-free ns, point
   both callers at it (same patch as step 2 so the gate is touched once).
4. **Collapse the state enum** — enum 5→3 + `wait`/`complete` write `:idle` +
   `terminate` stop-reason + DELETE the `fsm.cljs:195` verb arm + docstrings,
   ALL in one atomic patch. (A half-collapsed enum would let `wait` write a
   value the schema rejects.)
5. **Add the seon.db history wrappers** + the activity-log derivation fn.
6. **Delete the dormant handler-registry seed** from the CLJS boot.
7. **`bin/seon cluster reset default`** (fresh store — the old enum values
   would masquerade as live bugs) → live-drive a wake / wait / complete /
   terminate / cap cycle on DeepSeek and verify the activity-log derivation
   reads the right transitions.

Each step compiles clean on its own; no `*-v2` parallel path at any point.

## Decisions (locked — build to these)

1. **Verb-`:idle` mid-turn:** add `(not= :active after)` as the FIRST post-turn
   cond branch → halt IMMEDIATELY on any non-`:active` (verb-park / external
   reset / terminate). Cheap (the state read the loop already does), immediate,
   no distinct state. Verify on a live `(complete …)` drive.
2. **Loop tx-meta (`:cause` / `:stop-reason`): EXPLICIT per-transition** at each
   call site — NOT added to the auto-merge `tx-meta-attrs` (they are
   per-transition values, not ambient; auto-merging would make
   `assert-preconditions!` expect them on every tx).
3. **`seon.handler`: DELETE ENTIRELY** — ns + `:seon.handler/*` schemas + the
   seed + the handler fn + the inspector display. One wake path remains (the
   `db/listen!` closure). Tracked #27.
4. **`d/since`: NOT built.** The activity log uses `d/history` (all transitions)
   + `d/as-of` (point-in-time). Add only those two `seon.db` wrappers; add
   `since` only if a concrete caller needs it.
5. **Auto-todo write: in `message!`, ATOMIC** with the inbound `:human` message
   (same tx) — NOT a separate listener. Simplest, atomic, no cascade/idempotency.
6. **`request-id` → `:seon.store.wire/write-id`** — rename + the raw-datahike
   seed under the new name on the wire-server. Tracked #28.
7. **`clock` foot-gun (`transcript.cljs:110`):** replace the silent
   `(or inst (js/Date.))` with a guard that fails loud if no stored `:at` — fix
   in P1 (a stray live clock in transcript text would break byte-stability).

## Non-goals

- Render of the activity log (the timeline VIEW) — that is render-side, in
  [[docs/prds/agent-fsm/context-render.md]].
- The todo RENDERABLE and "addressed derives from todo completion" are render
  concerns owned by context-render.md (P4). The auto-todo WRITE hook (message
  intake) is owned HERE (see "Auto-todo on inbound") — it is write-side, not
  render.
- The render-fn-as-schema-metadata / no `register-renderer!` finding — render
  side; not a loop concern.
- The SOUL/AGENTS.md ordering + the `AGENTS.md` filename bug (topics 3-4 of
  the research) — separate from the loop.
- Any change to the wire-server / write path — the history wrappers are
  READ-side over the local pod db value.
- Re-introducing a kick-atom, wake flag, reply-accounting, or per-message
  processed flag — all deleted; this spec only finishes the consolidation.

## Pre-existing FSM smells to fix in this pass

The loop rewrite is the natural time to clear these older flagged items — they
all live in the FSM / wake / state path:

- **`set-state!` ghost-creates an agent on an unknown id** (no existence guard) —
  add the guard so a stray id can't mint a phantom agent entity (`agent.cljs`
  `set-state!`).
- **Premature-park** — an agent parking before it should. Re-check against the
  simplified stop policy (the `(not= :active after)` halt + the `empty-streak < 2`
  no-forms guard) so it doesn't go idle with work still pending.
- **`woken-by` → `wake` naming** (the gym/test path) — align the wake terminology
  with the live `db/listen!` wake; no two names for one mechanism.

Verify each on a live `(complete …)` / wake drive after the state-model collapse lands.
