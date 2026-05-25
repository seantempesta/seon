---
type: research
status: draft
tags: [research, agent]
---

# Turn-as-unit — killing `:seon.session`, making `:seon.agent/turns` the spine

## TL;DR

`:seon.session` is a redundant layer between `:seon.agent` and `:seon.turn`. In the v1 design it carries no state that is not derivable from the turn/message log — `:seon.session/at` is `(min :seon.turn/at)`, `:seon.session/turn-count` is `(count :seon.session/turns)`, `turns-since-user` was already deleted from `:seon.session/*` (the comment in `seon.client/agent-bootstrap-attrs:241` confirms this). The session-id ALS (Tier 1) was deleted on 2026-05-25 (commit `31e31cb`). Tier 2 — killing the entity — is the natural next step: collapse `agent → sessions → turns` to `agent → turns` (`:seon.agent/turns [:vector {:component true} :ref]`). The hard parts are not the schema delete; they are (a) the turn-boundary semantics under "agent continues as long as it wants" and (b) the `:paused` enum addition + kick-handler contract.

This deliverable maps the current loop, the proposed loop, the open questions, the concrete delete/add list, and a sequencing recommendation.

---

## Section 1 — The current agentic loop

Lifecycle from `seon.client/start-agent!` (`src/seon/client.cljs:514–591`) to a user message arriving:

1. **Boot** mints `agent-id` locally, opens conn, runs `replay-program-graph!`, calls `seon.agent/boot!` (`src/seon/agent.cljs:402–422`) which `create!`s the agent entity (state `:idle`, no session) and `install-user-trigger!`s a tx-listener keyed `[::user-message-trigger id]`.
2. **No session at boot.** `start-session!` is called lazily by `ensure-session!` on the first `run-turn!` (`agent.cljs:597–661`). The first user message kicks the loop; the kick handler schedules `run-agentic-loop!` via `setTimeout 0` (`agent.cljs:336–358`).
3. **One turn (`run-turn!`)** — agent.cljs:597–661:
   - `(ensure-session! id)` returns the latest `:seon.session` or opens one.
   - `turn-id` is minted; `prompt` is rendered sync via `render-prompt` → `assemble-ctx`.
   - Two nested ALS scopes (`with-agent`, `with-tx-context`) wrap `with-turn!` (the bracketing combinator). `with-turn!` writes the open-tx (turn entity with `:status :running` + `:prompt-text`, agent state `:running`) then awaits `ask-and-eval!`, then writes the close-tx (`:status :done`, fold in assistant message, agent state `:idle`).
   - `ask-and-eval!` (agent.cljs:577–595) is exactly one LLM call → `repl/parse-forms` → one `seval/eval-batch!` call. Returns `{:seon.turn/messages [<one assistant msg>] :seon.agent/eval-count <n-ok>}`.
4. **End-of-turn decision** sits in `run-agentic-loop!` (agent.cljs:663–706). Three stop cases: `:status :error`, `n-forms == 0` (no eval forms in the reply), or `(turns-since-user id) >= (turns-cap id)`. Otherwise `recur` — the SAME loop body issues another `run-turn!` against accumulated DB state. Critical: `run-agentic-loop!` itself is the "continues as long as it wants" mechanism — not a self-kick, not a setTimeout; a tight `loop`/`await`/`recur` inside one `^:async` fn.
5. **"continues as long as it wants" operationally:** while inside `run-agentic-loop!`, each `run-turn!` completes (close-tx flips state back to `:idle`, then immediately back to `:running` for the next turn's open-tx). User messages that arrive mid-loop slip through the kick handler's `(when-not (= :running state) …)` guard (agent.cljs:344–346) — they queue silently in the DB and are visible to the next render via `messages-section`. Cap or zero-forms ends the loop; control returns to the kick handler's `setTimeout` callback, which exits.
6. **`turns-since-user` is a query, not a counter** (agent.cljs:886–913). It runs a datalog `(max ?at)` over user messages, then counts `:seon.session/turns` whose `:seon.turn/at` is strictly greater. Reactive-context principle, applied correctly. Same with `current-ns` (agent.cljs:867–884) — derived from the latest successful eval's `:seon.eval/ns`.

What `:seon.session` actually carries today: `:seon.session/id`, `:seon.session/at`, `:seon.session/turns` (component-many to `:seon.turn`). That's it — `turn-count` and `turns-since-user` were deleted (`agent.cljs:114–117`, `client.cljs:241–243`).

---

## Section 2 — One turn, many forms

Verified by reading `ask-and-eval!` + `eval-batch!`:

- **One `run-turn!` = one LLM call = one `eval-batch!` = N forms in one batch.** The parsed reply text is a vector of `{:kind :form}` / `{:kind :comment}` entries (from `seon.parse`); `eval-batch!` runs them in a `doseq` (`eval.cljs:833`) with per-form try/record. Multiple forms in a single LLM response all live under ONE `:seon.turn` entity.
- **"Turn" today = one LLM round-trip.** Not "one continuous run before pausing." Multi-turn behaviour (multi-LLM-round-trip without user intervention) is the `run-agentic-loop!` outer recursion, not a property of a turn.
- **Should we redefine "turn = run-until-pause"?** No. The current alignment is good:
  - Bulk-load resume reconstructs ns sources from `:seon.fn/source` / `:seon.schema/source` (read once at boot, replayed). Turn boundaries do not gate replay — eval entities replay individually via tx order if at all (today the design is bulk-load, NOT replay-every-eval).
  - The 1:1 LLM-call ↔ turn entity mapping is what makes `:seon.turn/prompt-text` unambiguous (the literal text the LLM saw on THIS round-trip). Decoupling them would force us to invent "sub-turn" or store multiple prompt-texts per turn.
  - `eval-batch!`'s return shape `{:n-ok, :n-fail, :ids}` is the per-LLM-call success metric `run-agentic-loop!` reads. If turn ≠ LLM call, the stop-policy contract has to be rewired.

Keep turn = one LLM call. The "continues as long as it wants" is the loop, not the turn unit.

---

## Section 3 — Proposed model

```
agent
├── :seon.agent/state        ; [:enum :idle :running :paused]   (+ :paused, see Q5)
├── :seon.agent/turns        ; [:vector {:component true} :ref]  → :seon.turn (append-only)
└── :seon.agent/ctx          ; (unchanged)
```

Per-site translation table:

| Site (file:line) | Today | Proposed |
|---|---|---|
| `current-session` (agent.cljs:489–494) | last session by `:at` | DELETE — no callers needed |
| `turn-index` (agent.cljs:496–502) | `(count (:seon.session/turns session))` | `(count (:seon.agent/turns agent))` |
| `ensure-session!` / `start-session!` (agent.cljs:504–522) | open or reuse session | DELETE — no session to ensure |
| `with-turn!` body (agent.cljs:534–575) | writes nested under `{:seon.session/id _ :seon.session/turns [{...turn...}]}` | writes `{:seon.agent/id _ :seon.agent/turns [{...turn...}]}` |
| `run-turn!` (agent.cljs:614–620) | `ensure-session!` → `turn-id` mint → `turn-index session-id` | drop `ensure-session!`; `turn-index` reads from agent |
| `messages` (agent.cljs:830–842) | walks `session → turns → messages` | walks `agent → turns → messages` (one less level) |
| `evals` (agent.cljs:853–865) | walks `session → turns → evals` | walks `agent → turns → evals` |
| `current-ns` (agent.cljs:877–884) | walks `:seon.agent/sessions → :seon.session/turns → :seon.turn/evals` | walks `:seon.agent/turns → :seon.turn/evals` |
| `current-turn` (agent.cljs:844–851) | last turn on current session | last turn on agent |
| `turns-since-user` (agent.cljs:886–913) | turns on current session whose `:at > latest user :at` | turns on agent whose `:at > latest user :at` (filter same; collection source changes) |
| `prompt-section` (agent.cljs:1136–1143) | `(count (:seon.session/turns sess))` | `(count (:seon.agent/turns agent))` |
| `root-pull` (agent.cljs:814–828) | nested pull with `:seon.agent/sessions [:seon.session/turns [...]]` | nested pull with `:seon.agent/turns [...]` (one level shallower) |
| `start-of-conversation` (not yet a function) | `(min :seon.session/at)` | `(min :seon.turn/at)` query — derive on demand |
| `eval-batch!` `record-eval!` (eval.cljs:759–760) | writes `{:seon.turn/id turn-id :seon.turn/evals [eval-map]}` — turn already exists | **UNCHANGED.** Turn is attached to agent by `with-turn!`'s open-tx; eval-batch only knows the turn-id. |
| `agent-bootstrap-attrs` (client.cljs:220–298) | lists `:seon.session/id :seon.session/at :seon.session/turns :seon.agent/sessions` | DELETE those four lines; ADD `:seon.agent/turns` |
| stub-llm script (client.cljs:487–512) | references `(seon.db/current-agent-id)` — no session refs | **UNCHANGED.** Already session-free post-Tier-1. |

`:seon.turn/*` attrs all stay. The turn entity is unchanged; only its parent ref changes.

Render side observations:
- `current-ns-section` (agent.cljs:986–1016) does not walk sessions — it pulls `:seon.ns` reverse-refs. Untouched.
- `warnings-section` (agent.cljs:1018–1121) runs cross-agent queries — already does NOT filter by session. Untouched.
- `system-section` (agent.cljs:944–971) is a string template — only the discovery cheat-sheet in the docstring needs touching if it mentions sessions (it doesn't currently).

---

## Section 4 — Open design questions

### Q1. Turn boundary

**Recommendation: keep turn = one LLM call.** (See §2.) No change to when `:seon.turn` rows are minted: `run-turn!`'s `turn-id` mint at agent.cljs:617, one per LLM round-trip. The "continues as long as it wants" property lives in `run-agentic-loop!`'s recur, not in stretching the turn entity.

### Q2. Forward ref, back ref, or both?

Today eval entities attach to turns via the forward component-many `:seon.turn/evals`. The proposed `:seon.agent/turns` mirrors that pattern. **Question: do we want `:seon.turn/agent` as a plain back-ref too?**

Arguments for adding `:seon.turn/agent`:
- Some queries are easier expressed `[?t :seon.turn/agent ?a]` than `[?a :seon.agent/turns ?t]`. The `warnings-section` cross-agent query (agent.cljs:1037–1067) currently doesn't need agent attribution because it doesn't filter by agent; if/when it does, walking from a turn to its agent without going through `:seon.agent/_turns` (datahike's reverse-ref) is more ergonomic.
- The `turns-since-user` query (agent.cljs:898–907) currently joins messages to agents via `:seon.message/agent`. Turns have no direct agent ref today either — they reach the agent only via the component chain. A direct `:seon.turn/agent` would let the query say "turns belonging to this agent with `:at > latest-user-:at`" without traversing collections.

Arguments against:
- Reactive-context principle: one source of truth. Datahike's reverse-ref pulls (`:seon.agent/_turns`) and reverse-ref datalog give us back-direction queries from a forward ref.
- Adding it means tee logic in `record-eval!` and `with-turn!`'s open-tx have to set it (or rely on datahike's auto-population when writing as nested-map under the agent — which works for `with-turn!` but is more fragile if anything writes turns directly).

**Recommendation: forward only (`:seon.agent/turns`).** Use `:seon.agent/_turns` reverse-ref in pulls; use `[?a :seon.agent/turns ?t]` in datalog. Add `:seon.turn/agent` later only if a real query becomes painful.

### Q3. Turn identity

`:seon.turn/id` (12-char base62 via `db/new-id!`, marked `{:seon.db/identity true}`) stays. No change.

### Q4. `:seon.turn/status` + interrupted-turn handling

Today `:status` is `[:enum :running :done :error]`. Plan item 16 (Phase D) was going to query for `:status :running` at boot and flip them to `:interrupted` (or per decision 9, transact a `:system` message and kick the loop).

Under the new model: the most recent turn IS the only one that could be `:running`, but the schema doesn't enforce that. The query is unchanged — `[?t :seon.turn/status :running]` returns the interrupted turn(s).

**Recommendation:** add `:interrupted` to the status enum, do the flip at boot, transact a system message attached to the agent (not the turn, since the agent is the parent), kick the loop. Same as decision 9, just attached to the agent directly.

### Q5. `:paused` enum semantics

The MVP-Platform handoff (STATUS.md `374–382`) says "`:paused` enum + kick-listener skip in the SAME commit." Three possible semantics:

- (a) **Kick handler skips:** user messages still land in the DB; loop just doesn't auto-run. `(agent/chat …)` is fire-and-forget.
- (b) **Bootable but won't run-loop:** distinct from (a) only in that there's an explicit `(agent/unpause! id)` verb that flips to `:idle` and triggers a kick if pending messages exist.
- (c) **Entirely dormant:** also stop the broadcast tx-listener, the web tile shows "(paused)".

**Recommendation: (a) + (b).** Kick handler treats `:paused` like `:running` (no-op). `(agent/unpause! id)` flips to `:idle` and explicitly invokes `run-agentic-loop!` if `(turns-since-user) < cap` AND there are pending user messages. Renderer can show the state. (c) is out of scope — broadcast already keys off DB tx, no special handling needed.

State enum: `[:enum :idle :running :paused]`. Add `:interrupted` for turns (Q4), NOT for agents.

### Q6. Multi-turn-in-flight

Today: cannot happen. `run-agentic-loop!` is a tight loop; the kick handler refuses to re-enter if state is `:running`. The ALS substrate is per-fiber, so in principle a future change could allow concurrent turns under different ALS scopes. The current state-flip pattern prevents it at the agent-state level: `with-turn!`'s open-tx writes `:state :running`, close-tx writes `:state :idle`. A second concurrent `with-turn!` would race on the close-tx.

**Recommendation:** explicit single-turn-per-agent invariant, enforced by the state machine. Document in `:seon.agent/state` docstring. If concurrent turns are ever wanted (parallel sub-agents), that's a separate concept (per Platform's next chain: orchestrator + task sub-agents — STATUS.md `385–394`), each with their own agent entity.

### Q7. MVP-side coupling (item 10 — detect-and-tee)

Read `git diff src/seon/eval.cljs` — actually unchanged in the working tree (only `src/seon/agent.cljs` and `src/seon/client.cljs` show as modified). The `record-eval!`/`eval-batch!`/`build-tee-entities` are already committed in HEAD. Item 10 was actually shipped (look at `record-eval!`'s `:tee` arg at eval.cljs:709 and `build-tee-entities` upstream at eval.cljs:~640). The Phase B item 10 is therefore done, not in flight.

**No changes needed to `record-eval!` for the session-kill.** `record-eval!` only reads `turn-id` and writes via `{:seon.turn/id turn-id :seon.turn/evals [eval-map]}` (eval.cljs:759–760). The turn-id alone is enough — the turn entity (created earlier by `with-turn!`) is parented to whichever entity `with-turn!` chose. Today that's a session; tomorrow it's the agent. `record-eval!` doesn't care.

The working-tree diff on `client.cljs` adds new `:seon.fn/*` projection attrs and `:seon.schema/created-at` to `agent-bootstrap-attrs` — those are the analyzer-driven extraction projections, orthogonal to the session-kill.

---

## Section 5 — Concrete delete/add list

### Schema deletes (`src/seon/agent.cljs:208–222`)

- `:seon.session/id`
- `:seon.session/at`
- `:seon.session/turns`
- `:seon.agent/sessions`

### Schema adds (`src/seon/agent.cljs`)

- `:seon.agent/turns [:vector {:seon.db/component true} :seon.db/ref]`
- `:seon.agent/state` enum extension to include `:paused` (and arguably `:interrupted` for turns — see Q4)
- (Optional) `:seon.turn/agent :seon.db/ref` — recommend against (Q2)

### Function deletes (`src/seon/agent.cljs`)

- `current-session` (489–494)
- `ensure-session!` (517–522)
- `start-session!` (504–515)
- Forward-declares of those three (297–298)

### Function rewrites (`src/seon/agent.cljs`)

- `turn-index` (496–502) — read agent's turns
- `with-turn!` (534–575) — write turn nested under agent, not session
- `run-turn!` (597–661) — drop `ensure-session!`/`session-id` plumbing, drop `:seon.session/id-of-session` keyword from `with-turn!` input map; `run-turn!`'s `with-tx-context` no longer needs `:seon.db/session-id`
- `messages`, `evals`, `current-turn`, `current-ns`, `turns-since-user`, `prompt-section`, `root-pull` — replace `(:seon.session/turns session)` walks with `(:seon.agent/turns agent)`
- `install-user-trigger!` / `user-message-handler` — add `:paused` to the skip predicate (agent.cljs:346)
- (NEW) `unpause!` verb

### Schema/causality cleanup (`src/seon/db.clj?` — actually `seon.db` in CLJS)

- `:seon.db/session-id` tx-meta attr — delete (v1.md `460` registers it; STATUS.md `377` says session-kill is platform's lane, so this attr goes too)
- `with-tx-context` callers that pass `:seon.db/session-id` — drop the key

### Bootstrap attr list (`src/seon/client.cljs:241–246`)

```
:seon.session/id
:seon.session/at
:seon.session/turns
:seon.agent/sessions
```
DELETE these four lines; add `:seon.agent/turns`.

### Doc updates

- `docs/prds/agent-runtime/v1.md` §2.1 (lines 199–222) — rewrite the causality graph block
- `docs/prds/agent-runtime/v1.md` §2.4 (504–528) — root-pull idiom one level shallower
- `docs/prds/agent-runtime/v1.md` §5.2 (recent-evals-section description) — "current session" wording
- `docs/prds/agent-runtime/v1.md` §6.1 (run-turn! pseudocode) — drop ensure-session!
- `docs/prds/agent-runtime/v1.md` §7 (boot description) — drop start-session
- `docs/prds/agent-runtime/STATUS.md` — mark Tier 2 done in recent ships

### Estimated surface

~5 source files, ~150–250 LOC net delete (more delete than add). Mostly Platform's lane per STATUS.md `375–378`. The agent.cljs delta dominates; client.cljs is tiny; eval.cljs is zero-change.

---

## Section 6 — Sequencing recommendation

**Recommend option (b) — Platform does session-kill first, then MVP rebases.**

Rationale:

1. **Item 10 (detect-and-tee) is already in HEAD.** The working-tree diff on `eval.cljs` is empty; the analyzer-driven extraction landed on or before 2026-05-25 (the `build-tee-entities`, `record-eval!` `:tee` arg, `seon.analyzer-info` are all in committed code). Only `client.cljs` (bootstrap-attrs additions) and `agent.cljs` (currently unknown working-tree changes — `git diff` was empty for eval, but agent.cljs shows as modified) are in flight. This re-prioritizes the question: there is no large item-10 patch waiting to land.

2. **Item 9 bug fixes (B1, A6, C3, E3) are surgical inside `seon.analyzer-info` and `seon.eval`.** They do not touch session/turn boundaries. They can land before, during, or after the session-kill without conflict.

3. **The session-kill is mechanical and reviewable as one coherent patch.** Doing it first means MVP rebases small unrelated work onto a smaller, simpler causality graph. Doing it second means Platform rebases its bigger structural change onto MVP's small in-flight changes — wrong direction.

4. **Reversibility:** the session-kill is reversible only via git revert; once schemas are deleted, existing data is gone. Land it on a fresh DB (the `:memory` case is the norm today — STATUS.md `471` "Live pod still uses the OLD schema"). The patch itself is small enough to review in one sitting.

5. **Risk of interruption:** if the laptop dies mid-patch, the session-kill is a single commit. Item 10 was historically larger; but it's already in. Splitting item-10-bug-fixes from session-kill keeps each landed unit small.

**Concrete sequencing:**
- (i) Platform lands session-kill + `:paused` + kick-handler skip + Q4 `:interrupted` turn handling, ONE commit, on a fresh DB.
- (ii) MVP lands item-9 bug fixes (B1, A6, C3, E3) in a separate commit, on top.
- (iii) MVP lands the `agent.cljs` / `client.cljs` working-tree changes (analyzer projections) after both — they appear orthogonal to the session-kill.

---

## Things needing Sean's attention before Platform proceeds

1. **Confirm item 10 status.** Working tree shows zero diff on `eval.cljs`; the analyzer-driven extraction looks complete in HEAD. If Platform was expecting an in-flight item-10 patch from MVP, that expectation is stale. STATUS.md `374–382` (the Tier-2 coordination note) reads as if item 10 was pending; reality may have moved.

2. **Q2 decision: forward ref only, or add `:seon.turn/agent` back-ref?** Recommendation is forward-only, but if Sean wants the back-ref for query ergonomics, decide before Platform writes the schema.

3. **Q4 decision: `:interrupted` turn status + attach the system-message-on-resume to the agent (not the turn).** Plan item 16's framing assumed sessions; verify the agent-attached system message is the desired UX.

4. **Q5 `:paused` semantics decision** — (a)+(b) above, or stricter (c).

5. **`:seon.db/session-id` tx-meta attr removal.** This is a tx-meta change that affects every transact'd entity's causality bundle. Confirm it goes away with the entity (recommend yes — agent-id alone is sufficient attribution under the new model).
