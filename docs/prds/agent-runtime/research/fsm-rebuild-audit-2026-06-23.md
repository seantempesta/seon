---
type: research
status: active
tags: [research, agent]
---

# FSM Rebuild Audit — feature/agent-fsm (2026-06-23)

## TL;DR

No cheating: every FSM-disabled test was disabled because the behavior it pinned was genuinely deleted (answer-accounting, `completed-at`, `<turns>`, retry self-message) — none was disabled to silence a real regression — but TWO of them carried still-live coverage that must be re-pinned in the new model. Blocking/high items: **9 high** (0 hard-blockers — the FSM core itself is sound), the worst being two user-visible breakages from the enum migration. Single most important fix: **the inspector + provider-failure surfaces still key on the orphaned `:seon.agent/completed-at` / deleted `:seon.agent.turn/messages` attrs, so the "completed agents" grid is permanently empty and the provider-failure chat bubble is permanently dead** (#16 + #14).

## §1 Test triage

### FSM-disabled tests (disabled by WAVE A / U3 / U4 — in scope)

| Test file | Disabled by | Verdict | Rationale |
|-----------|-------------|---------|-----------|
| `agent_loop_test.cljs.disabled` | 1699f46 (WAVE A) | **correctly-disabled** | Pins `unanswered-live-inbound?`, the `:replied` halt, empty-turn re-prompt + give-up self-message — all DELETED in §5 (stop policy is now the FSM cap, not inbound-counting). The whole premise is gone. |
| `agent_retry_test.cljs.disabled` | 1699f46 (WAVE A) | **correctly-disabled** | Pins `ask-and-eval!` transport-retry + `:error`+self-message on give-up. Self→self writes deleted (§1); the give-up self-message mechanism is gone (no-forms halt is clean `:idle`). NOTE: if bounded transport-retry is still desired behavior, it now lives in `seon.agent.turn` and needs a fresh test — see new-test plan. |
| `agent_context_test.cljs.disabled` | 1699f46 (WAVE A) | **over-disabled-should-reenable (rewritten)** | Most assertions pin the deleted `<system>`/`<your-entity>`/`<turns>`/§2.9-status-line XML world (dead). BUT invariants (a) no-stored-ctx → full default context, (b) agent-path ≡ inspector-path (ONE composer), (e) bounded-context safety are TIMELESS and untested elsewhere. Re-pin (a/b/e) against the comment-block transcript. |
| `agent/turns_test.cljs.disabled` | 1699f46 (WAVE A) | **correctly-disabled** | Pins the `<turns>` countdown section + `task-in-progress?` + `default-turns-cap`, all deleted (`seon.agent.turns` ns is dead, tracked #11). No surviving coverage. |
| `agent_lifecycle_test.cljs.disabled` | 1699f46 (WAVE A) | **over-disabled-should-reenable (rewritten)** | Pins `complete!`/`completed-at`/`resumable-agent-ids` — the deleted lifecycle. BUT the lifecycle itself is REPLACED, not removed: `set-state! :completed` + `armable-agent-ids` (state ≠ `:terminated`) is the new resume contract and is UNTESTED. Rewrite against the state enum: complete→`:completed`, terminate→`:terminated` (unwakeable), resume set = armable. |
| `web/inspector_chips_test.cljs.disabled` | d71310c (U3) | **over-disabled-should-reenable (rewritten)** | Disabled because it pinned `complete!` stamping `completed-at`. But its REAL subject is the header AGENTS·TURNS·FACTS chip contract + `?system=1` toggle, which is unrelated to the lifecycle attr and still live. Re-pin the chip contract; drop the `complete!`/`completed-at` assertion. |
| `gym/driver_test.cljs.disabled`, `gym/paid_test.cljs.disabled` | e313add (U4) | **correctly-disabled (transitional)** | Gym driver couples to the old loop; re-enable belongs to the gym A/B follow-up after the transcript fold (#11/#12) lands, not this audit. |

### Out-of-scope disabled tests (NOT FSM — leave as-is)

`flow/*.disabled`, `db/datahike/flow_test`, `web/sse/flow_test`, `runtime_test`, `session_test`, `system/config_test`, `_disabled/*` — all JVM-track / core.async-flow quarantine, disabled long before the FSM branch (1b1b58b and earlier). Not this rebuild's concern.

### NEW-test plan (grouped)

**A. FSM lifecycle / state enum (replaces `agent_lifecycle_test`)**
- `complete` → `:seon.agent/state :completed`; `:completed` is WAKEABLE (a new inbound resumes → `:active`).
- `terminate` → `:terminated`; UNWAKEABLE (wake trigger no-ops, `armable-agent-ids` excludes it).
- `armable-agent-ids` = every agent with state ≠ `:terminated`, sorted; the boot resume set.
- `set-state!` unknown id → fail envelope; idempotent re-complete.

**B. FSM loop stop policy (replaces `agent_loop_test`)**
- `run-loop!` halts on each of: external state change, superseded wake, cap reached, turn `:error`, lifecycle verb (`complete`/`wait`), no-forms quiet → clean `:idle`.
- sliding `effective-cap` = `max-turns-per-loop` + `inbounds-during-this-wake`.
- wake-handler read-then-write-with-recheck (no atom); `:terminated` is unwakeable.

**C. Messaging verbs**
- `message/agent` REFUSES `to = me` (self→self impossible); `message/user` writes the row; blank content refused; hop-cap refusal. (`agent/message_test.cljs` exists — extend it, don't fork.)

**D. Comment-block transcript (replaces parts of `agent_context_test`)**
- no-stored-ctx → full default context (pure fn of DB).
- agent-path ≡ inspector-preview path (ONE composer) byte-identical.
- bounded-context safety (multi-MB eval result does not blow context).
- masthead + `;;; ── turn N ──` + `;;; ◀` inbound + `;;=>` result shapes present; readline `loop K/cap` equals the ENFORCED cap (see §3 divergence).

**E. Provider-failure derivation (with #14)**
- a turn with `:seon.agent.turn/status :error` and NO self-message → the `::system` chat bubble still renders (re-derived from status alone).

**F. Inspector lifecycle split (with #16)**
- roster groups completed = `(= :completed state)`, active = rest; a `:completed` agent leaves the active grid.

## §2 Cleanup items by kind

### orphaned-attr — `:seon.agent/completed-at` (NEVER written; #16)

One root cause, five readers. The attr is registered (`agent.cljs:125`) and listed in `client.cljs:341` bootstrap-attrs, but NO code writes it (complete writes `:seon.agent/state :completed`; serve.cljs POST `/complete` calls `set-state!`). Every reader is now driving on a permanently-absent attr:

- `inspector.cljs:957-958` — active/completed grid split → **completed grid always empty, `:completed` agents stuck in active** (high, user-visible). #16.
- `inspector.cljs:99-126` — `list-agents-data` `cond->` projection branch is dead. #16.
- `inspector.cljs:903-926` — per-tile "✓ complete" button gate (`when-not completed-at`). #16.
- `client.cljs:341` — dead bootstrap-attrs schema entry. #16.
- `ctx.cljs:1806-1808` — `assemble-context` pulls it (and `turns-cap`) but NOT `:seon.agent/wake`/`max-turns-per-loop`, forcing transcript.cljs to re-pull the entity. #16.

**Fix (one patch):** repoint all five at `:seon.agent/state` (`:completed`/`:terminated`), then delete the `agent.cljs:125` registration and the bootstrap-attrs entry. Add `:seon.agent/wake` + `:seon.agent/max-turns-per-loop` to the assemble pull.

### broken-ref — `:seon.agent.turn/messages` (DELETED; #14)

- `render/chat.cljs:131-155` — `provider-failure-rows` joins `[?t :seon.agent.turn/messages ?m]`; the attr is deleted (`client.cljs:374` documents it), so the query NEVER binds and the `::system` provider-failure bubble is **permanently dead** (high). Re-derive from `[?t :seon.agent.turn/status :error]` alone and synthesize the human-facing text. #14.

### dead-code — answer-accounting / deleted sections (#11)

- `ctx/prompt.cljs:1-95` — whole ns dead (renders `:assistant`, "TRANSACT THE :assistant MESSAGE NOW", `run-agentic-loop!`); still required at `client.cljs:160`, re-exported at `agent.cljs:74,174`. Delete ns + require + re-export. #11.
- `agent/turns.cljs:1-58` — whole ns dead (`<turns>` XML, `task-in-progress?`, `with-turn!`); still required at `client.cljs:146`. Delete ns + require. #11.
- `ctx.cljs:1416-1453` — `task-in-progress?` dead (only consumers are the two dead sections); docstring cites deleted `unanswered-live-inbound?`/`<turns>`/`:replied`. Delete. (Keep `latest-live-inbound` — still used by `retrieval-query`.) #11.
- `ctx.cljs:692-741` — `turns-since-inbound` dead once the two sections go; re-export at `agent.cljs:157`. #11.
- `agent.cljs:73-74,174` — `ctx-prompt` require + `prompt-section` re-export, dead with the ns. #11.

### orphaned-attr / inconsistency — `:seon.agent/turns-cap` (two-paths-one-job; #19)

`:seon.agent/turns-cap` (old) and `:seon.agent/max-turns-per-loop` (FSM) both registered (`agent.cljs:136` / `:109`). `create!` threads `turns-cap` onto the entity but the FSM reads `max-turns-per-loop`, so **a configured cap lands on the dead attr and is silently inert.** Collapse to `max-turns-per-loop`: delete the `turns-cap` registration, `default-turns-cap`/`turns-cap` re-exports, `create!`'s arg, `ctx/turns-cap` (`ctx.cljs:117-130`), assemble pull. #19. (Note: still LIVE until #19 lands — `ctx/turns-cap` + `agent/turns` read it — so do NOT remove from bootstrap-attrs yet.)

### smell — latent / honesty (NEW, none tracked)

- `agent.cljs:609-625` — `complete`'s parent branch passes `(:seon.agent/parent ent)` (a datahike Entity printing `{:db/id N}`) straight into `message!` as `:to`; `message!`'s `to` normalization has no map case → falls to `:else [to]` → invalid ref. Latent (no producer sets `:seon.agent/parent` today) but live reachable. **Fix:** pass `(:db/id (:seon.agent/parent ent))` or normalize `{:db/id N}` in `message!`. **NEW.**
- `agent.cljs:596-625` — `wait`/`complete` read `(db/current-agent-id)` with no nil guard and IGNORE the transact envelope, unconditionally returning `:waiting`/`:completed`. `terminate` + the message verbs guard nil id; these two are inconsistent — a failed park reports success. **Fix:** branch on `:seon.db/ok?` or guard nil id with a loud envelope. **NEW.**
- `ctx/transcript.cljs:74-101` — LOCAL copy of the inbound gate (`inbound-msg?`) duplicating `seon.agent/inbound-msg-datom?` to dodge a require cycle, with a TODO. §2 mandates ONE predicate. Unify once the gate moves to `message.cljs`. #17.
- `db/internal.cljs:52-58` — TWO ALS instances (`als-instance` + `agent-id-als`); the U0/#7 unify+rename is correctly still pending. Confirmed not a regression. #7.
- `agent.cljs:496-501,828` — `create!`/`set-purpose!` docstrings use `<your-entity>` angle-bracket section naming that reads XML-ish against the comment-block framing. Cosmetic. **NEW (low).**

### stale-docstring — actively MISLEADING (high; fix outside #15 sweep)

- `agent.cljs:117-124` — comment asserts serve.cljs "still calls the removed `agent/complete!`". **FALSE** — serve.cljs:390 already calls `set-state! :completed`. This is changelog-rot that LIES about current code and renders into agent context. **Fix now**, don't wait for #15.

### stale-docstring — bulk changelog rot (#15)

Pervasive dated/issue-ref/"replaces the deleted X" prose that renders into agent context. Representative (not exhaustive):

- `agent.cljs:62-68,84-87,138-176` + `fsm.cljs:3` + `turn.cljs:3-4` + ~40 sites — dates, `U#`/`P6`/`#NN`, `spec-05 §22.5`, "REPLACES the deleted …".
- `ctx.cljs:1-60,255-261,630-632,765-777,1531-1573` — ns docstring + `core-default-ctx` + `message-render-cap` + `current-turn` + `system-text` all describe the deleted `<turn>`/`<user>`/`woken-by`/`:prompt`/`:turns`/§2.9-status world.
- `render/chat.cljs:22-32,131-140,200-223`, `render/default.cljs:15-20,97-113,209-215` — name deleted `ask-and-eval!`/`run-agentic-loop!`/self-message fold/`<system>`-aliases/"never resumed" (`:completed` is now wakeable).
- `client.cljs:128,1880-1885,2153-2160`, `serve.cljs:193-197` — name deleted `install-user-trigger!`/`run-agentic-loop!`/`user-msg-for-agent?`/`complete!`.
- `eval.cljs:1-35,35,1905,2602-2606,2621-2623` + `warn.cljs:977` — `<past-evals>`/`<warnings>` XML mentions + `run-agentic-loop!`/`agent.cljs/run-turn!` stale caller names.

**All #15.** The two genuinely-misleading ones to escalate ahead of the bulk sweep: `agent.cljs:117-124` (lies about serve) and `render/chat.cljs:131-140` (describes a dead derivation).

## §3 Spec divergences / inconsistencies

1. **Displayed cap ≠ enforced cap (high, #19 + NEW display defect).** `ctx/transcript.cljs:251,268` readline + turn-header show `loop K/cap` via `ctx/turns-cap`, reading OLD `:seon.agent/turns-cap` (default 20). The loop enforces `fsm/effective-cap` = `max-turns-per-loop` + `inbounds-during-this-wake`. The agent sees a cap number that does not match when the loop halts. **Repoint `ctx/turns-cap` at `effective-cap`** (the display fix is live even before #19 collapses the attrs).

2. **XML tags ABOVE the comment-block transcript (medium, NEW — ctx-XML-removal follow-up).** The masthead promises "you write forms + `;;`, the runtime writes the rest" as one comment-block, but still-composed sections emit XML: `render-namespace` `<namespace …>` (`ctx.cljs:1206-1232`, `ctx/namespaces.cljs`), `<system>` wrapper + `<inventory>`/`<open-todos>` (`ctx.cljs:779,936-960,1018`), and the inventory/relevant/your-entity/live-tile envelopes. This is the §2 "substrate must never break its own format rules" hazard. §0 scopes the broader ctx split out of FSM, so medium — but it undercuts the eval'able-Clojure north star. Convert envelopes to comment-block headers (`;; ── namespace seon.db (signatures) ──`).

3. **`in-ns` rejected but the transcript example shows it working (low, latent).** `eval.cljs:35,2284` reject `(in-ns 'foo)`; the §2 transcript example shows `(in-ns 'my.foo) ;;=> my.foo` flipping the readline ns. Aspirational (U7 deferred) — not a current bug, but track so the ns=>-readline and the parser don't ship inconsistent.

4. **`creation-evals!` is the pre-FSM startup path (medium, #12).** `client.cljs:2087-2151` still opens a "creation turn" via the old mechanism; §5 says "turn 0 IS the bootstrap, emitted in the new comment-block format." Replace with turn-0-as-transcript-bootstrap. #12.

## §4 Recommended NEW tasks (not covered by #11–#19)

1. **Fix `complete`'s parent-delivery ref shape + `message!` `to` normalization** — `agent.cljs:609-625` passes a `{:db/id N}` Entity as `:to`; will silently misfire the moment a spawn path sets `:seon.agent/parent`. Add a map case to `message!`'s `to` handling (or pass the eid). Why: latent correctness bug in live-reachable lifecycle code.

2. **Make `wait`/`complete` honest about transact failure** — `agent.cljs:596-625` ignore the envelope and the nil-id case, returning success-shaped values; `terminate`/messaging verbs already guard. Why: a failed park/complete must not report success — consistency + honesty across the verb set.

3. **Correct the actively-false serve.cljs comment NOW (ahead of the #15 sweep)** — `agent.cljs:117-124` claims serve "still calls the removed `complete!`"; it calls `set-state!`. Why: it is the one piece of changelog-rot that LIES about current code and renders into agent context; pull it out of the bulk sweep.

4. **ctx-XML-removal follow-up** — convert the still-composed `namespaces`/`system`/`inventory`/`relevant`/`your-entity`/`live-tile` envelopes from XML tags to comment-block headers so the whole agent-facing context obeys the masthead's one-format contract (§3 item 2). Why: substrate-consistency; the agent learns the format from what it's shown, and XML above the transcript teaches the wrong shape.

5. **Re-pin the FACTS-chip contract test** — `inspector_chips_test` was disabled for an unrelated `completed-at` assertion; its AGENTS·TURNS·FACTS + `?system=1` coverage is still live and now untested. Why: regression surface left bare by an over-broad disable.
