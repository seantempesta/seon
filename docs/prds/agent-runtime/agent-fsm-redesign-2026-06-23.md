---
type: prd
status: draft
tags: [prd, agent, flow, database]
---

# PRD: The agent runtime as a finite state machine — REPL transcript, DB-backed states, explicit lifecycle verbs (2026-06-23)

**Status: draft — design agreed in conversation 2026-06-23; for one focused
implementation session.** NEVER name the downstream consumer — use `acme`.

## TL;DR

Redesign the agent messaging + agentic loop as an explicit finite state
machine whose state lives in the **database** (on the agent record), is
**re-read every loop iteration**, and is **externally controllable**. The
agent's whole existence is one **append-only REPL transcript** told from a
transaction-log perspective; state transitions are **REPL calls and their
return values** (`(agent/wait …)` yields `=> :waiting`; the waking message opens
the next turn at its head — never duplicated), not out-of-band events.
Completion is **explicit** (`complete` / `wait`)
or **implicit** (no forms / per-loop cap) — never inferred from a reply count.

This DELETES the entire answer-accounting layer (`unanswered-live-inbound?`,
`live-inbound-count`, `user-facing-reply-count`, `reply!`'s woken-by
targeting) and the runtime atom latch (`!kick-scheduled`), and REPLACES the
`:idle`/`:running` two-value flag + atom guard with a DB-modeled FSM.

It KEEPS the verified-good seams untouched: per-form eval isolation
(errors-are-values), `eval-count = n-ok + n-fail`, the `origin`/`handled?`
wake gate, the per-tx (not per-datom) listener, `result/<id>` vars, and the
reactive live-tile/SSE substrate.

## Why this supersedes the prior plans

Three documents converged on pieces of this; this doc is the unifying spec:

- [[conversation-timeline-2026-06-22]] already decided to KILL answer-accounting
  and render ONE chronological transcript — but kept a `replied-since-inbound?`
  halt. **This doc deletes even that halt** (halt on no-forms / `complete` /
  `wait` / cap), so the timeline doc's "Loop halt" section is superseded.
- [[context-v4-repl-realism-2026-06-11]] / [[transcript-redesign-2026-06-18]]
  shipped the REPL-faithful transcript (turns as `;;`-comments + forms + `=>`
  results, `<turn>`/`<user>` envelopes, `result/<id>` vars). **This doc keeps
  that and adds** the explicit live-turn header + the `wait`/`complete` resume
  annotation + DB-backed state.
- [[unified-loop-v1]] / `loop-design.md` proposed an elaborate
  `:seon.turn-request` dispatcher / handler / effect-bus FSM. **Do NOT
  resurrect it** — it was draft, never shipped. The FSM here is the simple
  `run-agentic-loop!` with DB state, not an effect bus.

## What is already TRUE (verified 2026-06-23 — do not rebuild or re-litigate)

A workflow audit + adversarial verification established the real state of the
code. The redesign must respect these:

1. **The single-pod "multi-wake race" is already closed.** The tx-listener
   fires once per-tx, not per-datom (`db/internal.cljs:1448`); the kick
   handler's guard-read → latch-set is one synchronous JS block with no await
   (`agent.cljs:618-621`); the wire feed cannot replay an own tx
   (`server/boot.clj:104-125`, at-most-once delivery). The transcript
   corruption the owner remembers was **#43** — tile-recovery forging a
   *human-looking* message that re-armed the wake — already fixed by
   `:seon.agent.message/origin :core` gating.
2. **The real remaining bugs in that area are different:** (a) **duplication**
   — `message!` has no idempotency, and every turn stores a self→self copy of
   the LLM's full reply (`agent.cljs:1130-1158`) *separate* from any outbound
   message → words appear twice; (b) **deaf** — the latch clears via a Promise
   `.finally` (`agent.cljs:632`); if the loop promise never settles, an `:idle`
   reset is missed, or the wire drops an event, the agent goes permanently
   deaf. Wire delivery drops, never duplicates, so **recovery must be
   pull/derive-based, never replay-based.**
3. **There is no wall-clock "turn timer."** The bound is `turns-cap` (a COUNT,
   default 20, `ctx.cljs:117`). Real timers exist only for transport (2 s LLM
   retry, 60 s HTTP timeout → turn `:error`) and per-form eval (10 s).
4. **Per-form failure is the ONE robust seam.** `eval-batch!` runs every form
   (`eval.cljs:2646`); failures are envelopes, not throws (`eval.cljs:899`);
   `eval-count = n-ok + n-fail` (`agent.cljs:1140`, paid for by gym S-12), so a
   turn whose only form failed still recurs and shows the `=> ✗` next turn.
   **Preserve this exactly.**
5. **`reply!` is hard-wired to the first sender** (`message.cljs:260`, woken-by
   fixed to `(first waking)` at `agent.cljs:615`, threaded constant
   `agent.cljs:1318`), and the halt is a global COUNT balance
   (`agent.cljs:1448`) that mis-handles batches (3 msgs need 3 replies) and
   lets a peer consult falsely "answer" the human.
6. **There is no explicit done signal.** `complete!` (`agent.cljs:805`) stamps
   `:seon.agent/completed-at` for boot-resume only; the loop never calls it.

## The principle

The reactive context IS the truth, derived from the DB. The agent lives in one
continuous REPL whose transcript is an **append-only transaction log**: "this
happened, then this happened." There is **no separate state-machine vocabulary
exposed to the agent** — transitions are ordinary REPL calls (`agent/wait`,
`complete`, `message/user`) and their return values. The substrate never tracks
"which message was answered." The agent reads its history and decides what to
say and whom to say it to.

Standing laws this obeys: reactive-context (derive, never store-and-clear),
cache-prefix order (static→volatile, append-only transcript = cache-optimal),
maps with namespaced keywords everywhere.

## 1. The finite state machine

`:seon.agent/state` is a registered enum on the agent record. It is the SINGLE
source of truth for liveness — no atoms.

```clojure
(schema/register! :seon.agent/state
  [:enum :idle :active :waiting :completed :terminated])
```

| State | Meaning | Wakeable by a message? | Loop running? |
|-------|---------|------------------------|---------------|
| `:idle` | neutral / between work | yes → `:active` | no |
| `:active` | a loop is running, taking turns | no (running loop picks it up) | yes |
| `:waiting` | parked via `(agent/wait …)`, expecting input | yes → `:active` | no |
| `:completed` | finished via `(complete …)` or no-forms | yes → `:active` | no |
| `:terminated` | hard-stopped (orchestrator kill) | **NO** — state must be changed first | no |

### Transitions

```
                 inbound message (origin ∈ {:human :agent}, from ≠ me,
                 hops < cap, handled? ≠ true)  AND  state ∈ {:idle :waiting :completed}
   {:idle :waiting :completed} ───────────────────────────────────────────▶ :active
                                                                              │
   :active ──(turn emitted forms and/or messages)──────────────────────────▶ :active   (recur)
   :active ──(agent/wait note)─────────────────────────────────────────────▶ :waiting
   :active ──(complete result)─────────────────────────────────────────────▶ :completed  (+ deliver result to parent)
   :active ──(turn emitted nothing actionable, after ≤2 thinking-mode re-prompts)─▶ :idle
   :active ──(per-loop turn count ≥ max-turns-per-loop)─────────────────────▶ :idle   (+ cap note)
   :active ──(LLM error / 60s timeout / catastrophic throw)─────────────────▶ :idle   (+ error turn)
   :active ──(external writer set :completed | :terminated | :idle)─────────▶ stop loop  (orchestrator control)
   any ─────(external writer set :terminated)──────────────────────────────▶ :terminated  (no wake until changed)
```

### The loop, redesigned (replaces `run-agentic-loop!` + the atom latch)

Each iteration **re-reads `:seon.agent/state` from the DB** and decides:

```clojure
;; pseudocode — the WHOLE stop policy
(loop [empty-streak 0]
  (let [state (agent-state-from-db id)]
    (cond
      ;; external control — an orchestrator changed our state mid-loop
      (not= :active state)                      :halt-external

      (>= (loop-turn-count id wake-id)
          (max-turns-per-loop))                 (do (cap-note!) :halt-cap)

      :else
      (let [result (await (run-turn! input))]   ; one LLM turn + eval-batch
        (cond
          (= :error (turn-status result))       :halt-error          ; → :idle
          (terminal-verb-called? result)        :halt-verb           ; wait/complete already set state
          (zero? (eval+msg-count result))       (if (< empty-streak 2)
                                                  (do (thinking-nudge!) (recur (inc empty-streak)))
                                                  :halt-quiet)        ; → :idle, clean
          :else                                 (recur 0))))))        ; made progress, keep going
```

Key properties:
- **Liveness is DB-derived.** No `!kick-scheduled` atom. The "am I already
  looping" guard is a transactional state set: a wake transitions
  `{:idle :waiting :completed} → :active` ONLY if the current value is still
  wakeable (a compare-and-set in the same tx). Two concurrent wakes: the first
  sets `:active`, the second's CAS no-ops.
- **Self-healing wake.** Because liveness is `(wakeable? state)` and the loop
  re-queries inbound each turn, a dropped wire event self-heals: the next tx (or
  a derived "idle-with-unprocessed-inbound" surface) re-derives the wake. No
  event needs re-delivery. This is the verified fix for the #49 intake race
  WITHOUT latch-narrowing (which re-opened the double-loop).
- **`:active` reset on EVERY exit.** A `try/finally` sets the terminal state
  (the generalized `ensure-idle!` discipline, `agent.cljs:1039`). If a writer
  set `:terminated`/`:completed` externally, finally must NOT clobber it (set
  `:idle` only if still `:active`).
- **Stuck-`:active` recovery.** Most hangs are bounded (60 s HTTP, 10 s
  per-form, SCI interrupt for tile fns). The residual (a hung promise) is
  recovered by (a) the orchestrator/human writing a new state — the explicit
  escape hatch the owner asked for — and (b) a **reactive "agent looks stuck"
  surface** (derived: `:active` AND no turn in N s AND a live inbound exists),
  never a stored flag.

### Counters

- **Monotonic turn number** — `:seon.agent.turn/n`, never reset, so the agent
  sees turns always climbing as it reviews history.
- **Per-loop cap** — each turn stamps `:seon.agent.turn/wake-id` (the id of the
  wake that started the current `:active` episode). Per-loop count =
  `(count turns where wake-id = current)`; cap when `≥ (max-turns-per-loop)`.
  `max-turns-per-loop` reads `:seon.agent/max-turns-per-loop` on the record else
  `SEON_MAX_TURNS_PER_LOOP` env else 20. Fully DB-derived; configurable.

## 2. The transcript representation (comment-block REPL — no XML)

The transcript is ONE accretive fn, `(transcript db agent-id)`, returning a
string whose shared prefix is BYTE-IDENTICAL every turn (pure append) so the
whole history caches. NO XML — Clojure has none, and an XML shape invites
mimicry. Demarcation is comment blocks keyed off repeating characters. FOUR
unmistakable channels:

- `(forms)`      — the agent's code (the only non-comment lines it writes)
- `;; narration` — the agent's own comments
- `;;; runtime`  — the runtime's lines: turn rules, the masthead, INBOUND
                   messages, the status block (`;;;` is idiomatic Clojure for
                   heading comments AND a clean "not yours" boundary)
- `=> value`     — the runtime's eval results (real REPL), trailing `;; result/<id>`

The agent NEVER writes `;;;` or `=>` (the masthead says so; the parser strips
any it emits). Turn 0 is the substrate-run bootstrap (inventory + instructions +
hello + park); the agent's first real turn is turn 1. Turn numbers are
monotonic — never reset.

### One uniform rule for messages (this is what kills the duplication)

Every inbound message renders EXACTLY ONCE, as a `;;; ◀ from X @ time — "…"`
line at the **head of the turn that first sees it** (the turn the agent can
first act on). No second copy, no "wait returned it" special case:
`(agent/wait …)` simply yields `=> :waiting`, and the waking message is the next
turn's head line — identical to any other inbound. A message that arrived **while
the previous turn's LLM call was still running** is exactly this case: it lands
at the head of the next turn, flagged NEW, so the agent always sees what it has
not yet acted on. A batch is N head lines. (Derived from `:at` vs the prior
turn's `:at` — NO "answered/unanswered" tracking; that accounting is deleted, §1.)

### Canonical example (turn 0 → LIVE)

```
;;; ═══════════════ seon · my.agent.seon · your live REPL ═══════════════════
;;; Below already happened — your history, oldest first, byte-stable. YOU wrote
;;; the (forms) and the ;; comments; the runtime wrote the ;;; lines and the =>
;;; results. You never write ;;; or =>. Append new forms after the cursor.

;;; ─── turn 0 · bootstrap · 14:00:00 ──────────────────────────────────────────
;; Starting up — checking the shared store and my standing instructions first.
(seon.db/store-inventory)
=> {:seon.kb/note 9, :seon.fn 42, …}                          ;; result/a1
(my.kb.system/instructions)
=> [{:my.kb/text "consult the store before researching"} …]   ;; result/a2
(message/user "Hi Sean — I'm up; store has 9 notes. What should I work on?")
=> {:delivered true}
(agent/wait "awaiting first task")
=> :waiting

;;; ─── turn 1 · 14:04:13 ─────────────────────────────────────────────────────
;;; ◀ from :user @ 14:04:12 (4m after you parked) — "refactor the foo namespace"
;; On it.
(refactor 'foo)
=> {:moved 3 :ok true}                                        ;; result/b7

;;; ─── turn 2 · 14:04:31 ─────────────────────────────────────────────────────
;; Verify, then report.
(run-tests 'foo)
=> {:pass 12 :fail 0}                                         ;; result/c2
(message/user "foo refactored — moved 3 fns into bar, 12 tests green")
=> {:delivered true}
(agent/wait "anything else?")
=> :waiting

;;; ─── turn 3 · 14:09:03 ─────────────────────────────────────────────────────
;;; ◀ from :user @ 14:09:02 (4m32s after you parked) — "yes — now do baz too"
;; on it — same treatment for baz.
(analyze 'baz)
=> {:fns 5}                                                   ;; result/d1

;;; ─── turn 4 · 14:09:20 · ◀ YOU ARE HERE ────────────────────────────────────
;;; ◀ 1 NEW — arrived while you were working on turn 3, not yet acted on:
;;;     :agent/researcher @ 14:09:15 — "heads up: baz depends on qux"
;;; turn 4 · loop turn 4/20 · state active · 14:09:20 PDT
;;; ▸ Mid-task (baz). Emit forms to continue · (agent/wait "…") to pause for input
;;;   · (complete "…") when the whole task is truly done.
my.agent.seon=>
```

### Rules

- A turn = one LLM completion, preceded by a `;;; ─── turn N · <time> ───` rule.
  The timestamp makes time-passing visible; a wait/idle gap is annotated on the
  next inbound line (`(4m after you parked)`).
- Inbound messages: one `;;; ◀ from X @ time — "…"` line at the head of the turn
  that first sees them. Never duplicated, never a wait-return.
- `(agent/wait "…") => :waiting`, `(complete "…") => :completed` — the verb shows
  its effect as a plain result; the FSM state change is real (§1).
- **History vs LIVE:** `(transcript …)` returns turns 0..(live-1), byte-stable.
  The LIVE turn — its rule, its NEW-message flag, the status block, the cursor —
  is the VOLATILE tail, regenerated each turn AFTER the stable prefix, so it
  never busts the cache. The NEW flag (derived: `:at` since your last turn)
  appears only while live; in later history those messages render as the plain
  `;;; ◀ from …` head line.
- Keep `result/<id>` vars + per-component caps; elision deferred (priority:
  clear in every state first).

### The status block (the steering tail)

The final `;;;` block — part of the volatile tail — is the ONE place that
changes freely without cost (it follows the stable prefix). It carries the live
turn number, per-loop turns left, state, and dynamic steering derived from the
DB. After a failed form, e.g.:

```
;;; ─── turn 9 · 14:30:10 · ◀ YOU ARE HERE ────────────────────────────────────
;;; turn 9 · loop turn 9/20 (+1 granted) · state active · 14:30:10 PDT
;;; ▸ 1 of your last forms failed (result/h4). Fix it; or if your work is done,
;;;   (complete "…"); to pause for the user, (agent/wait "…").
my.agent.seon=>
```

## 3. The building-block API (clear, no magic, all through the DB)

```clojure
;; --- messaging: explicit target, substrate fills the rest ---
(message/user "…")              ; from = me (ALS scope), to = the one user
(message/agent agent-id "…")    ; from = me, to = [agent-id]
;; substrate fills :id :at :hops :origin; reply! is DELETED.

;; --- lifecycle: end the wake ---
(agent/wait "status note")      ; park → :waiting; resumes when a message arrives,
                                ; rendered as this call's return value next turn.
(complete "result message")     ; → :completed; delivers result to :seon.agent/parent
                                ; (the subagent-result channel). One arity.
;; …or simply emit no forms → loop ends → :idle (clean, not an error).

;; --- live status tile + work queue ---
(seon.agent.todo/add! {:seon.agent.todo/title "…"})   ; log work to keep going
(seon.agent.todo/complete! {:seon.agent.todo/id …})   ; tx → tile re-renders live
```

- **`message/user` / `message/agent`** are thin wrappers over the kept
  `message!` (`message.cljs:164`). `from` is the ALS agent scope; `message/user`
  needs no target (single user for now); `message/agent` takes the id. The
  schema is already many-to-many (`to` is `[:vector :seon.db/ref]`); we simply
  stop collapsing it to 1:1.
- **`agent/wait`** sends nothing — it parks with a status note. To message +
  park, call `(message/user "…")` then `(agent/wait "…")` (no magic, two
  explicit acts). The bootstrap demonstrates exactly this.
- **`complete`** sets `:completed`, stamps `:seon.agent/completed-at`, and, if
  `:seon.agent/parent` is set, delivers the result message to the parent — the
  channel for spawned subagents. A new inbound reactivates a `:completed` agent.
- **`terminate`** (orchestrator-only, via `message!`/a verb): set
  `:terminated`. The wake gate refuses to wake it; only an explicit state change
  revives it.

## 4. Prose & error policy (self-reinforcement)

Override [[reliability-fixes-49-53-2026-06-21]] #50's "drop prose" for
natural-language prose specifically — the owner wants the cleaned replay to
keep reinforcing correct behavior. `repl/internal.cljc:348` (`parse-forms`)
changes:

| Input the model emitted | Stored / replayed as | Why |
|---|---|---|
| `;; comment` | narration on next form (unchanged) | the taught reasoning channel |
| natural-language prose before a form | **converted to `;;` narration** | self-reinforce: replay shows it did it right |
| a genuine `(list …)` form | evaluated (unchanged) | the only evaluated shape |
| fabricated `=> v` / `;; => v` / bare data-literal-as-result | **stripped, NOT echoed** + one correction note | echoing teaches FAKING runtime output |
| unrecoverable broken form | `;; ⚠ DO NOT DO THIS — <what broke>; <how to fix>` then the bad span | mistakes are poison; wrap them loudly |

The split: **NL prose → comments** (benign narration, reinforces narrate-then-
act); **fabricated runtime output → never echoed** (the genuine imitation
hazard #50 was really guarding). Keep parinfer per-form repair on by default
([[eval-robustness-and-debug-2026-06-18]]).

## 5. The duplication fix

- **Delete the per-turn self→self assistant message row** (`agent.cljs:1130-
  1158`). The turn IS recorded by its eval rows (forms + `;;` narration) and its
  outbound `message/*` rows — the assistant's output appears ONCE. Keep the
  verbatim raw-reply capture as a **debug file** (`response.txt`) and the #27
  on-reply hook fire-site, NOT as a transcript message.
- **`message/*` are explicit forms** in the transcript, so two identical sends
  render as two visible `(message/user …)` forms — honest, not mystery
  duplicate rows. (Optional: dedupe identical content+to within one turn.)

## 6. Live status tile = the todo queue, rendered

The tile substrate is already pure-reactive (one attr → re-derived → SSE-morphed
on every relevant tx, coalesced 100 ms — `inspector.cljs:1409/1478`). The todo
queue (`agent/todo.cljs`) is today agent-facing text only. **Wire the default
core tile fn to query `:seon.agent.todo` and render open + recently-done items
as a live progress list.** Completing a todo is a tx → the tile re-renders
showing the item done. One mechanism, two derived views (agent context + human
UI). No new machinery; matches "live status tile listing work-to-do with
progress shown live as items complete."

## 7. Bootstrap (show, don't tell)

Extend `creation-evals!` (`client.cljs:2106`) — which already runs real startup
forms AS the agent through `eval-batch!` into a creation turn so they land in
the transcript — to ALSO demonstrate the new idiom: a `(message/user "…ready")`
then `(agent/wait "…")`. The agent's very first history then shows the exact
loop-completion pattern it reinforces forever (the V3-E "imitation over
obedience" principle, live-proven).

## 8. Delete / replace map (file:line)

DELETE:
- `unanswered-live-inbound?` `agent.cljs:1411`; `live-inbound-count` `:1352`;
  `user-facing-reply-count` `:1385` — the whole answer-accounting layer.
- `reply!` `message.cljs:244-267` and its `current-turn`/woken-by targeting.
- the `!kick-scheduled` atom `agent.cljs:553` + the `:running` string guard.
- the per-turn self→self assistant note `agent.cljs:1130-1158`.

REPLACE / REWRITE:
- `:seon.agent/state` enum `agent.cljs:129` → 5-value FSM.
- `run-agentic-loop!` `agent.cljs:1483` → DB-state-driven loop (§1).
- `inbound-message-handler` `agent.cljs:580` + `install-user-trigger!` `:654` →
  transactional CAS wake (no atom); record ALL waking ids on the wake.
- `with-turn!`/`with-turn-body!`/`ensure-idle!` `agent.cljs:994/1063/1039` →
  generalized terminal-state reset (keep the failsafe discipline).
- `complete!` `agent.cljs:805` → loop terminator + parent delivery (§3).
- `transcript-section` `ctx/transcript.cljs:183` → §2 representation (live-turn
  header + resume annotation).
- `parse-forms` `repl/internal.cljc:348` → §4 prose policy.
- `default-turns-cap` `ctx.cljs:117` → per-loop cap via `wake-id` + env.

KEEP (verified-good): per-form eval isolation `eval.cljs:2646/899`;
`eval-count = n-ok+n-fail` `agent.cljs:1140`; `origin`/`handled?` wake gate
`message.cljs:48/56`; per-tx listener `db/internal.cljs:1448`; `result/<id>`
vars; the reactive tile/SSE substrate; the 24k transcript budget + caps.

ADD: `message/user`, `message/agent`, `agent/wait`, `terminate`;
`:seon.agent/parent`, `:seon.agent.turn/n`, `:seon.agent.turn/wake-id`,
`:seon.agent/max-turns-per-loop`.

## 9. Verification / falsification (acme harness, not the default cluster)

- **FSM:** drive idle→active→waiting→active→completed; assert each transition
  is a single DB state row, the transcript shows it, and no atom is consulted.
- **Batch (the reply!-1:1 fix):** human sends 3 messages; agent answers all with
  ONE `(message/user …)`; assert the loop does NOT spin to the cap and the
  transcript shows 3 inbounds → 1 reply naturally.
- **External kill:** while `:active`, an orchestrator writes `:terminated`; the
  loop halts at the next iteration; a subsequent message does NOT wake it until
  state is changed.
- **Deaf self-heal:** simulate a dropped wire event; assert an idle agent with a
  live unprocessed inbound re-derives its wake (no replay).
- **No duplication:** a turn that sends one `message/user` produces exactly one
  message row and one transcript line; the words never appear twice.
- **Thinking-mode:** a zero-form (reasoning-only) completion re-prompts ≤2× then
  idles cleanly (no `:error`, no "dead" silence).
- **Prose:** NL prose before a form replays as `;;`; a fabricated `=> v` is not
  echoed; a broken form replays wrapped in `;; ⚠ DO NOT DO THIS …`.
- `bin/test-cljs` green.

## 10. What we are explicitly NOT doing (tried-and-failed)

timestamp/count answer-accounting; the forged-message wake (#43); latch-
narrowing (re-opens the double-loop); whole-reply parinfer repair; full
refusal-gate removal; the `:seon.turn-request` dispatcher/effect-bus FSM;
the literal tx-log walk; mandatory eval fences; worker `postMessage` of the
db value.

## Cross-references

- [[conversation-timeline-2026-06-22]] — kill answer-accounting + one timeline
  (this supersedes its halt section).
- [[context-v4-repl-realism-2026-06-11]], [[transcript-redesign-2026-06-18]] —
  the REPL-faithful transcript this builds on.
- [[reliability-fixes-40-43-2026-06-21]], [[reliability-fixes-49-53-2026-06-21]],
  `research/reliability-49-53-deepdive-2026-06-22` — #43 origin gating, #49
  intake race (drain-not-narrow), #50 prose policy (overridden here for NL prose).
- [[live-tiles-prd-2026-06-11]], [[tile-isolation-prd-2026-06-21]] — the tile
  substrate + SCI interrupt (the "no infinite-run" answer for tile fns).
- [[reply-hook-and-myns-home-design-2026-06-22]] — the #27 on-reply seam (the
  raw-reply fold survives as the hook fire-site even after the message row drops).
- `src/seon/agent.cljs`, `src/seon/agent/message.cljs`, `src/seon/agent/todo.cljs`,
  `src/seon/ctx/transcript.cljs`, `src/seon/repl/internal.cljs`,
  `src/seon/client.cljs` (`creation-evals!`), `src/seon/render/live_tile.cljs`.
