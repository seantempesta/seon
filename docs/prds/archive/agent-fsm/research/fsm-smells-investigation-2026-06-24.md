---
type: research
status: active
tags: [research, agent, flow]
---

# FSM loop-path smells — root-cause + fix proposals (2026-06-24)

Read-only investigation of the three pre-existing loop-path smells flagged in
[[docs/prds/agent-fsm/agent-loop.md]] ("Pre-existing FSM smells to fix in this
pass", ~line 596). Source-reading + git/doc archaeology only — no edits, no
REPL, no pod restart. The loop ns has already been renamed `seon.agent.fsm` →
`seon.agent.loop` (`src/seon/agent/loop.cljs`); this report uses the new name.

## TL;DR — the three verdicts

1. **`set-state!` ghost-creates a phantom agent — REAL.** `agent.cljs:350-357`
   upserts `{:seon.agent/id id :seon.agent/state state}`; `:seon.agent/id` is an
   identity attr, so an unknown id mints a brand-new agent carrying only
   id+state. Every real caller operates on an already-`create!`d agent, so an
   existence guard breaks nothing. **Fix:** guard inside `set-state!` itself —
   read the entity, fail LOUD with an error envelope (errors-are-values, matches
   `create!`/`message!`) if absent. Cost is one local `db/entity` read (sub-ms),
   already paid twice per loop iteration anyway.

2. **Premature-park — REAL but ORTHOGONAL to the stop policy.** Every original
   premature-idle report traces to a CONTEXT/CONTENT failure (the task is
   evicted from the transcript, or the user message never reached context, or a
   broken query convinced the agent no work existed) — the agent then *correctly*
   emits zero actionable forms and the no-forms halt parks it. The simplified
   stop policy (`(not= :active after)` immediate halt + `empty-streak < 2`
   2-turn thinking guard + eval-count = n-ok+n-fail) is a NET IMPROVEMENT and
   does not regress park, but it does NOT fix the root cause because the root
   cause is upstream of the loop. The real fix is the context-render work
   (task-pin / activity-log / never-evict-the-task) owned by
   [[docs/prds/agent-fsm/context-render.md]]; the loop change is necessary-but-
   not-sufficient.

3. **`woken-by` → `wake` naming — REAL, and worse than "two names".**
   `:seon.agent.turn/woken-by` is registered NOWHERE in `src/`. The LIVE turn
   schema registers `:seon.agent.turn/wake` (`turn.cljs:68`) and the live
   `open-turn!` writes `:seon.agent.turn/wake` (`turn.cljs:283`). Only the
   gym/test path (`gym/driver.cljs`, `.disabled` tests, 3 `.edn` fixtures, one
   stale comment) still uses `woken-by` — so the gym driver writes/queries an
   attribute the live runtime never produces. **Fix:** rename every gym/test
   `woken-by` → `wake` (the live attr), update the 3 `.edn` fixture keys to
   match (data fixtures the driver reads), and fix the one comment. Live-risk
   sites: `gym/driver.cljs` only. Everything else is `.disabled` or a fixture.

---

## Smell 1 — `set-state!` ghost-creates a phantom agent

### Confirmed behavior

`src/seon/agent.cljs:350-357`:

```clojure
(defn ^:async set-state!
  "Transact a new `:seon.agent/state` for the agent (upsert by id).
   Returns the transact-response promise. Map-in. ..."
  {:malli/schema [:=> [:cat ::set-state-request] :seon.db/transact-response]}
  [{:seon.agent/keys [id state]}]
  (await (db/transact!
           {:seon.db/tx-data [{:seon.agent/id id :seon.agent/state state}]})))
```

`:seon.agent/id` is an identity attr (`agent.cljs:94` region; it's the
upsert key used everywhere via `[:seon.agent/id id]` lookup refs). A transact
of `{:seon.agent/id "unknown" :seon.agent/state :idle}` therefore UPSERTS — if
no agent with that id exists, datahike creates a new entity holding ONLY
`:seon.agent/id` + `:seon.agent/state`. That is a phantom agent: no purpose, no
sessions, no wake, no creation evals. The docstring even says "upsert by id"
out loud, so the behavior is intentional-but-unguarded. The same upsert footgun
exists in `fresh-wake!` (`agent.cljs:359-369`, `{:seon.agent/id id
:seon.agent/wake wake}`), worth noting as a sibling but not in scope here.

### Every caller of `set-state!` (grep `src/` + `test/`)

| Call site | id source | Operates on an existing agent? |
|---|---|---|
| `loop.cljs:216` (run-loop! `finally` reset → `:idle`) | the loop's own `id`, already woken | YES — the loop only runs for an agent that was `create!`d, armed, and woken |
| `loop.cljs:284` (wake-handler → `:active`) | the agent whose listener fired | YES — `install-wake-trigger!` only arms `armable-agent-ids`, all of which exist |
| `web/serve.cljs:389` (HTTP "mark complete" → `:completed`) | `agent-id` from the request path | INTENDED to be existing; an unknown path-param id would currently MINT a phantom |
| `test/seon/agent_lifecycle_test.cljs:120,121,131,359` | test-created agents | YES (tests `create!` first) |
| `test/seon/web/inspector_chips_test.cljs:170` | test-created agent | YES |

No legitimate caller relies on the upsert-creates-a-new-agent behavior. Every
production path is preceded by `create!` (`agent.cljs:424-461`), which seeds the
entity with `:seon.agent/state :idle` (`agent.cljs:447`) plus optional purpose
and `max-turns-per-loop`. So the first legitimate `set-state!` always lands on
an existing entity. The ONLY way an unknown id reaches `set-state!` is a bug
(stale id in a closure, a malformed `web/serve.cljs` path param, a future REPL
"nudge" verb) — exactly the case a guard should reject loudly rather than paper
over by minting a ghost the inspector then displays as a real agent.

### Original flag context

The smell is listed in `agent-loop.md:601-603` ("`set-state!` ghost-creates an
agent on an unknown id (no existence guard) — add the guard so a stray id can't
mint a phantom agent entity"). It is sibling to the `create!` honesty stance
already in the code: `create!`'s docstring (`agent.cljs:437-441`) says "A failed
create means NO agent entity; callers must branch instead of chasing a ghost."
`set-state!` violates that same principle from the other direction — it
SUCCEEDS into a ghost.

### Proposed fix (concrete)

Guard in `set-state!` ITSELF (not a shared helper — only `set-state!` has the
unguarded-upsert problem on `state`; `fresh-wake!`'s ghost is only reachable via
the same illegitimate paths and can be guarded in the same patch if desired).
Fail LOUD with an error envelope, consistent with `create!`/`message!`
(errors-are-values, not throw — the loop's `finally` and the wake-handler must
not have an exception torn through them):

```clojure
(defn ^:async set-state!
  "Transact a new `:seon.agent/state` for an EXISTING agent (lookup by id).
   Returns the transact-response. If no agent with `id` exists, returns a
   loud error envelope WITHOUT minting a phantom entity (set-state! never
   creates an agent — create! does). Map-in. `^:async`."
  {:malli/schema [:=> [:cat ::set-state-request] :seon.db/transact-response]}
  [{:seon.agent/keys [id state]}]
  (if (nil? (db/entity {:seon.db/ref [:seon.agent/id id]}))
    (do (js/console.error
          (str "seon.agent/set-state!: no agent " id
               " — refusing to mint a phantom (state=" state ")"))
        {:seon.db/ok? false
         :seon.db/error {:seon.error/message
                         (str "set-state! on unknown agent " id)}})
    (await (db/transact!
             {:seon.db/tx-data [{:seon.agent/id id :seon.agent/state state}]}))))
```

**Where:** in `set-state!`. A shared helper is over-engineering for one call
shape; if `fresh-wake!` gets the same guard, factor a tiny private
`(agent-exists? id)` predicate at that point — not before.

**Fail-loud vs no-op:** fail loud (envelope + `console.error`). A silent no-op
hides the real bug (a stale id) and the caller can't tell park-failed from
park-succeeded — exactly the dishonesty the `fsm-rebuild-audit` flagged for
`wait`/`complete` (see Smell 2). The envelope shape matches
`:seon.db/transact-response`'s failure branch so the existing `(false?
(:seon.db/ok? res))` callers already handle it.

**Perf cost:** one `db/entity` lookup-ref read against the local lazy db value.
The loop ALREADY does two such reads per iteration (`loop.cljs:159` top,
`loop.cljs:187` after-turn) and the `finally` does a third (`loop.cljs:213`), all
sub-millisecond on the `:memory`-class pod conn. Adding one more on the
state-write is negligible — and the wake/finally paths only call `set-state!`
once per transition, not per turn.

**Confirm no legitimate upsert-creates reliance:** `create!` is the sole agent
minter (it's the only writer of `:seon.agent/state` alongside a fresh
`:seon.agent/id` for a NEW entity, `agent.cljs:444-453`). The guard does NOT
touch `create!`. Every `set-state!` caller above is post-create. Verified safe.

### Live-verification scenarios for #5

- Drive a normal wake → turn → clean park; confirm `set-state!` succeeds (the
  `:active` and `:idle` writes land on the existing agent). Read back
  `:seon.agent/state` history — exactly the real agent's transitions, no new eid.
- Call `(seon.agent/set-state! {:seon.agent/id "no-such-id" :seon.agent/state :idle})`
  against the live pod; assert it returns `{:seon.db/ok? false ...}` and that
  `(seon.agent/armable-agent-ids {})` count is UNCHANGED (no phantom appeared).
- Hit `web/serve.cljs`'s mark-complete endpoint with a bogus agent-id; assert
  500 + no phantom in the inspector roster.

---

## Smell 2 — Premature-park

### Confirmed behavior + what the CURRENT loop does

The current stop `cond` (`loop.cljs:162-205`), in order:

| # | Branch (line) | Condition | Result |
|---|---|---|---|
| A | top `loop.cljs:163` | `(not= :active state)` | `:halt-external` |
| B | top `loop.cljs:166` | `(not= wake my-wake)` | `:halt-superseded` |
| C | top `loop.cljs:170` | `turns-this-wake ≥ effective-cap` | `:halt-cap` |
| D | post-turn `loop.cljs:190` | turn `:status :error` | `:halt-error` |
| E | post-turn `loop.cljs:195` | `(and (not= :active after) (not= :idle after))` | `:halt-verb` |
| F | post-turn `loop.cljs:198-202` | `forms = 0` AND `empty-streak ≥ 2` | `:halt-quiet` (clean `:idle`) |
| G | `catch :default loop.cljs:206` | threw | `:halt-throw` |

`forms` is `(:seon.agent/eval-count r)` (`loop.cljs:188`). The 2-turn
`empty-streak` guard (`loop.cljs:199`) already gives the model a "thinking mode":
TWO consecutive zero-form turns before parking, not one. This is a genuine
guard against parking-too-early on a single narration-only turn.

The spec's simplified policy (agent-loop.md Decisions 1, table at lines 223-246)
DELETES arm E and adds `(not= :active after)` as the first post-turn branch so
verb-parks halt immediately, with the intent moving to a `stop-reason` tx-meta.
The no-forms halt's `eval-count` is `n-ok + n-fail` (attempted forms), so an
all-ERRORED turn has `forms > 0` → does NOT count as a quiet turn → recurs and
the next turn shows the errors. That nuance is preserved.

### Original flag context (quoted)

The premature-park report is consistently a CONTENT problem, never a stop-policy
bug. Direct evidence:

- `e2e-demo-findings-2026-06-08.md:514-521`: "Agent (correctly, per its prompt)
  looked for 'the most recent user> line in the transcript' — found none. Fell
  back to QUERYING for the latest user message; failed TWICE ... Concluded no
  message existed, transacted 'waiting for next task'-type replies, one EMPTY
  assistant message (content ''), went idle." Root cause (same report, 505-512):
  "User messages never reach the agent's context" — the `/chat` handler
  transacted the user message standalone, never attached to a turn.

- `destub-curate-and-behavior-2026-06-24.md:131-133`: "lifecycle is reached via
  the lost-task path too often — the agent parks AFTER re-greeting rather than
  after delivering the result." And the headline root cause,
  `destub-...:137-143`: "TASK FORGOTTEN MID-LOOP (most damaging). The inbound
  `;;; ◀ from :user ... '<task>'` line renders ONLY at the head of the turn that
  first sees it. The transcript is capped at 24000 chars ...; after 2-3 turns
  the oldest turn block is evicted along with the ONLY copy of the task. ... By
  turn 3 the agent had no idea what it was assigned and reverted to the generic
  SOUL.md greeting — even though it had ALREADY computed the complete correct
  audit in turn 1."

- `e2e-demo-findings-2026-06-08.md:1446-1448`: "A1 judge-a 0 —
  premature-idle/no-reply (f3 blind-idle sibling): A's whole arc = tile-wire,
  inventory, ONE grep (result display truncated at 1500/16405 chars), idle. No
  store, no reply. Real." — i.e. a TRUNCATED display starved the agent of the
  data it needed, and it parked.

- `wake-message-race-2026-06-23.md:182-185`: the fresh-mint turn-0 greeting fails
  with an error envelope and "turn 0 then parks via `agent/wait`" — a boot-order
  bug (#9), not a stop-policy bug.

A related but DISTINCT honesty defect (`fsm-rebuild-audit-2026-06-23.md:92,127`):
`wait`/`complete` "IGNORE the transact envelope, unconditionally returning
`:waiting`/`:completed` ... a failed park reports success." That is a verb-return
honesty bug, not the loop parking early — but it compounds premature-park because
a failed park still looks parked.

### VERDICT: ORTHOGONAL — the simplified policy does NOT cause/fix premature-park

The failure mode in every report is:

1. The agent loses (or never receives) the task/message in its rendered context.
2. Having nothing to act on, it emits narration / a greeting / nothing → zero
   actionable forms (or it deliberately `(agent/wait ...)`s).
3. The no-forms halt (F) — or the verb-park (E, soon A) — parks it CORRECTLY
   given the (impoverished) input it saw.

The loop did its job. The stop policy is downstream of the real failure (context
content). The simplified policy:

- **Does not make park worse.** The 2-turn `empty-streak` guard is unchanged; an
  all-errored turn still recurs (eval-count = n-ok+n-fail); the cap is unchanged.
  Deleting arm E only changes WHEN a verb-park is observed (immediately at the
  next top check vs at arm E this iteration) — no extra turn is run either way
  (`run-turn!` at `loop.cljs:178` runs only after the top cond), so there's no
  behavioral regression.
- **Does not fix the root cause.** Nothing in the stop `cond` can re-surface an
  evicted task or repair a context that never carried the user message. The fix
  lives in [[docs/prds/agent-fsm/context-render.md]]: the task-pin / activity-log
  / "the task never evicts" work (the auto-todo-on-inbound write hook in
  agent-loop.md "Auto-todo on inbound" is the write half — it creates an
  address-todo the moment a `:human` message lands, so the work item survives
  transcript eviction).

So: the loop change is NECESSARY (the immediate `(not= :active after)` halt + the
stop-reason tx-meta make the activity-log honest about WHY it parked, which is
how you'll *see* a premature park in the timeline) but NOT SUFFICIENT. The actual
premature-park cure is the render-side task durability.

ONE concrete loop-side hardening worth doing in this pass (complementary, not a
duplicate of the render work): make `wait`/`complete` honest about transact
failure (the `fsm-rebuild-audit` finding) so a FAILED park doesn't report a
clean `:idle`. Combined with Smell 1's `set-state!` guard, a park that can't
write its state is now LOUD instead of a silent ghost-park. This is the loop's
share of the fix; the rest is render.

### Live-verification scenarios for #5 (prove premature-park is gone)

Drive on DeepSeek after the state-collapse + context-render task-pin land:

- **Narration-only turn:** prompt the agent to "think out loud for a moment
  before doing anything." Expect: turn 1 emits zero forms, loop RECURS (empty-
  streak 1), turn 2 acts. Assert the agent does NOT park after the single
  narration turn (the 2-turn guard holds).
- **Multi-step plan across transcript eviction:** give a task requiring ≥4
  turns of work so the original task message scrolls past the transcript budget.
  Assert the agent still knows its task on turn 4+ (via the pinned todo /
  activity log) and does NOT revert to a generic greeting + park. This is the
  exact `destub-...:137-143` regression — it's the headline test.
- **Mid-tool-thinking:** a turn that emits only a comment block + a `(comment
  ...)` form (no actionable defn/transact). Confirm eval-count counts the
  attempted form correctly and the agent doesn't quiet-halt while mid-plan.
- **All-errored turn:** a turn where every form throws. Assert eval-count =
  n-ok+n-fail > 0 → loop RECURS (does NOT quiet-park), and the next turn's
  prompt shows the errors.
- **Honest failed park:** simulate a `wait`/`complete` whose transact fails;
  assert the verb returns an error envelope (not `:idle`) and the loop does NOT
  record a clean park (ties Smell 1 + the audit honesty fix).
- **Clean complete:** a Q&A task where the agent delivers the result then
  `(complete ...)`s. Assert it parks AFTER delivering (not after re-greeting),
  the activity log shows `stop-reason :complete`, and the result message reached
  the human/parent. (`complete` was "not exercised" per destub-...:131 — exercise
  it.)

---

## Smell 3 — `woken-by` → `wake` naming

### Confirmed behavior

`:seon.agent.turn/woken-by` is **registered nowhere in `src/`** (grep confirmed:
`grep -rn "register.*woken-by\|:seon.agent.turn/woken-by" src/` → no hits). The
LIVE turn schema registers the per-turn wake anchor as `:seon.agent.turn/wake`
(`turn.cljs:68`: `(schema/register! :seon.agent.turn/wake :seon.db/id)`), it's an
optional entity field (`turn.cljs:96`), and `open-turn!` writes it
(`turn.cljs:283`: `wake (assoc :seon.agent.turn/wake wake)`). The loop's sliding
cap counts turns by `:seon.agent.turn/wake` (`loop.cljs:81,97`).

So `woken-by` is the OLD name for the per-turn wake anchor (the message-driven-
turn marker the gym uses to exclude the creation turn's tutorial evals from
"first eval"). The live runtime fully migrated to `:seon.agent.turn/wake`; the
gym/test path did not. The gym driver consequently writes and queries
`:seon.agent.turn/woken-by`, an attribute the live `open-turn!` never produces —
the gym scratch store gets it only because the driver explicitly passes it into
`run-turn!`/`run-agentic-loop!` and seeds it on fixture turns.

Note also: the driver calls `agent/run-turn!` and `agent/run-agentic-loop!`
(`driver.cljs:1060,1075`), but the live fns are `seon.agent.turn/run-turn!`
(`turn.cljs:405`) and `seon.agent.loop/run-loop!` (`loop.cljs:148`) — there is
no `seon.agent/run-agentic-loop!` and no `agent/run-turn!`. The gym driver is
broader-stale than just the attr name; flag for the gym-revival owner, but the
attr rename is the in-scope piece here.

### Every `woken-by` usage (grep `src/` + `test/`)

| Site | Kind | What it is |
|---|---|---|
| `src/seon/dev/test_preload.cljs:34` | LIVE (comment only) | a comment noting the DELETED `agent-context-test` "pinned the old turn shape woken-by/messages". No code. |
| `test/seon/gym/driver.cljs:501,505,518,526,546,561,568,1042,1064,1079,1092` | LIVE test code | `eval-at+source` / `turn-prompt-files` query `[?t :seon.agent.turn/woken-by _]`; `send-user-message!` doc + `drive-stub-turns!`/`drive-loop!` PASS `:seon.agent.turn/woken-by [:seon.agent.message/id mid]` into the turn; `ensure-agent!` doc references it. |
| `test/seon/gym/scenarios/s01-stub-pipeline-smoke.edn:39` | `.edn` fixture | predicate query clause `[?t :seon.agent.turn/woken-by ?m]` |
| `test/seon/gym/scenarios/s32-consult-before-research.edn:95` | `.edn` fixture | comment referencing "the driver's woken-by scoping" |
| `test/seon/gym/scenarios/consults-findings-run8.edn:76` | `.edn` fixture | comment referencing "the driver's woken-by scoping" |
| `test/seon/agent_loop_test.cljs.disabled:474,530` | `.disabled` | seeds/asserts `:seon.agent.turn/woken-by` |
| `test/seon/agent_context_test.cljs.disabled:98,104,352,371,381,384` | `.disabled` | old transcript-shape tests on `woken-by` |
| `test/seon/gym/driver_test.cljs.disabled:452,460` | `.disabled` | fixture turn carrying `woken-by` |

### Original flag context

`agent-loop.md:607-608`: "`woken-by` → `wake` naming (the gym/test path) — align
the wake terminology with the live `db/listen!` wake; no two names for one
mechanism." Confirmed: the live mechanism is `:seon.agent.turn/wake` (the
per-turn stamp) + `:seon.agent/wake` (the agent's wake-episode token); `woken-by`
is the stale duplicate name for the per-turn stamp in the gym/test lane only.

### Proposed fix (concrete, old → new)

Rename every `:seon.agent.turn/woken-by` → `:seon.agent.turn/wake` (the live
attr). This unifies the gym onto the real schema and removes the duplicate name.

**LIVE (must compile + the driver must still work) — `test/seon/gym/driver.cljs`:**
- `:501,505` (docstring), `:518,526` (`eval-at+source` query clauses),
  `:546,561,568` (`turn-prompt-files` docstring + clauses), `:1042` (docstring),
  `:1064,1079` (the `:seon.agent.turn/woken-by [:seon.agent.message/id mid]` keys
  passed into `run-turn!`/`run-agentic-loop!`), `:1092` (docstring) →
  `:seon.agent.turn/wake`.
  - CAUTION: the live `open-turn!` writes `:seon.agent.turn/wake` as a
    `:seon.db/id` (the AGENT's current wake token), NOT a message lookup-ref.
    The driver currently passes a `[:seon.agent.message/id mid]` lookup-ref as
    `woken-by`. After the rename these are the SAME attr with INCOMPATIBLE value
    types (`:seon.db/id` vs a ref). The driver-revival owner must reconcile: either
    (a) the gym stamps the agent's real wake token (matching the live shape) and
    derives "message-driven turn" some other way, or (b) keep a SEPARATE gym-only
    marker attr under a `:seon.gym.turn/*` namespace for "message-driven" rather
    than overloading the live `:seon.agent.turn/wake`. Recommendation: (b) — the
    gym's intent ("exclude the creation turn") is a GYM concern; map it to a
    `:seon.gym.turn/message-driven?` boolean (or reuse the existing
    `:seon.gym.turn/*` schemas at `driver.cljs:127-141`) instead of forcing it
    onto the live wake attr. This avoids a type-clash on a live attribute. FLAG
    for the gym owner — this is beyond a mechanical rename and shouldn't be done
    blind in the loop pass.

**`.edn` fixtures (data the driver reads — rename MUST match whatever the driver
queries):**
- `s01-stub-pipeline-smoke.edn:39` — the predicate query clause
  `[?t :seon.agent.turn/woken-by ?m]`. If the driver keeps querying a per-turn
  marker, this key must match it exactly. If recommendation (b) is taken, this
  becomes `:seon.gym.turn/message-driven?` (and the predicate shape changes).
- `s32-consult-before-research.edn:95`, `consults-findings-run8.edn:76` —
  comments only; update the prose to the new name.

**`.disabled` tests (low risk — not compiled/run):**
- `agent_loop_test.cljs.disabled:474,530`, `agent_context_test.cljs.disabled:*`,
  `gym/driver_test.cljs.disabled:452,460` — rename for consistency OR leave (they
  are disabled and the agent-fsm redesign already plans to rewrite the transcript
  tests; per test_preload.cljs:34's comment the `agent-context-test` was deleted
  for pinning this old shape). Lowest priority.

**Comment-only (`src/`):**
- `src/seon/dev/test_preload.cljs:34` — update the comment text `woken-by` → the
  current name. Trivial.

### Verdict + scope note

REAL smell, but the honest fix is BIGGER than a string replace because the gym
overloaded a now-live attr with an incompatible value type. The clean resolution
is: (1) trivially fix the comment in `test_preload.cljs`; (2) hand the gym driver
+ `.edn` fixtures to the gym-revival owner to either adopt the live
`:seon.agent.turn/wake` shape correctly or move the "message-driven turn" marker
to a `:seon.gym.turn/*` attr — NOT a blind rename, which would create a
type-clash on `:seon.agent.turn/wake` (`:seon.db/id` vs message-ref). The
`.disabled` sites can ride along or wait for the planned transcript-test rewrite.

### Live-verification scenarios for #5

- Drive a real wake → turn cycle; read back the turn's `:seon.agent.turn/wake`
  and confirm it equals the agent's `:seon.agent/wake` episode token (the live
  shape — a `:seon.db/id`, NOT a message ref). This pins what the gym must
  match.
- After the gym fix, run the stub gym suite (s01) and confirm the
  message-driven-turn scoping still excludes the creation turn's tutorial evals
  from "first eval" (the original reason `woken-by` existed).

---

## Cross-cutting notes for the loop-rewrite owner

- Smell 1 (set-state! guard) + the `fsm-rebuild-audit` `wait`/`complete` honesty
  fix together make EVERY park observable: no phantom ghost-park (Smell 1), no
  silent failed-park (audit). Do them in the same patch as the state-collapse —
  they touch the same verbs/lines.
- Smell 2 is correctly OUT of the loop spec's hot path: the loop changes
  (immediate `(not= :active after)` halt + stop-reason tx-meta) make premature
  park VISIBLE in the activity log, but the cure is render-side task durability.
  Don't try to "fix premature-park in the cond" — that would be papering over a
  context bug with a stop-policy hack.
- Smell 3 is mostly a gym-lane cleanup; the only `src/` touch is one comment.
  The real work is reconciling the gym's overloaded attr — flag it, don't
  blind-rename.
