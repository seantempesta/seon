---
type: issue
status: open
severity: friction
tags: [issue, agent, flow]
---

# Test-suite audit — fragile/stale/wrong tests + bug-finding (2026-06-25)

Read-only audit of `test/seon/**` against the canonical current targets
(`docs/prds/agent-fsm/agent-loop.md`, `context-render.md`) and the LIVE pod
runtime. Goal: find tests that hide bugs (divergent setup vs real wiring),
plus fragile/stale/dead tests. **Audit only — no test/source edits.**

## Live ground-truth observed (read-only MCP evals, pod `HtK-2606251913`)

The FSM + render refactor is LARGELY LANDED in the live build:

- `:seon.agent/state` = `[:enum :active :idle :terminated]` — the 5→3 collapse
  is **DONE** (`:waiting`/`:completed` gone).
- `:seon.agent.message/handled?` — **NOT registered** (deleted).
- `:seon.handler/input` / handler-registry schemas — **NOT registered** (deleted).
- `:seon.agent.loop/stop-reason` `:keyword` — exists (new tx-meta).
- `ask-and-eval!`, `task-in-progress?`, `unanswered-live-inbound?` — **gone**.
- `:seon.agent/turns-cap` — gone; `:seon.agent/max-turns-per-loop` — present.

### A concurrent agent is mid-fix RIGHT NOW

Working tree has uncommitted edits to `src/seon/ctx/your_entity.cljs`,
`src/seon/ctx/live_tile.cljs`, `test/seon/ctx_test.cljs` (the
"context-loop-regression-sweep-2026-06-25" issue). It is actively curing F2:
`your-entity-section` now **resolves the entity from the db by id** when
`:seon.agent/entity` is absent (your_entity.cljs:28-47), and a NEW prompt-path
test was added (`live-tile-section-stable-on-composer-input`, ctx_test.cljs:387).
This audit accounts for that in-flight state; the test-WEAKNESS findings below
survive the source fix (they describe what the tests can/can't catch, not the
current source bug).

---

## 1. BUG-HIDING tests — fix first (divergent setup vs real prompt wiring)

The F2 class: the REAL prompt path (`seon.agent.turn/render-prompt` →
`render/render :seon.render/ai ctx (ctx/context-root ctx)`) builds
`ctx = {:seon.db/db db :seon.agent/id id}` with **NO `:seon.agent/entity`**, and
`seon.render/render` (render.cljs:641) injects only `:seon.render/node` +
`:seon.render/render` into the child ctx — it does **not** add the entity. The
INSPECTOR path (`seon.ctx/ctx-sections`, ctx.cljs:1922) does
`(assoc ctx :seon.agent/entity (:seon.agent/entity root))` BEFORE rendering. So
any section fn that reads `:seon.agent/entity` from ctx renders fine in the
inspector and gets nil in the real prompt. `your-entity-section` returned `""`
on nil entity (silent vanish from the model's prompt); `live-tile-section` was
already robust (resolves from db itself, live_tile.cljs:82-89).

| # | Test (ns:line) | What it SETS UP | What the runtime ACTUALLY produces | Bug it hides |
|---|---|---|---|---|
| B1 | `seon.ctx-test/purpose-entity-and-your-entity-and-verbs` (ctx_test.cljs:456) — asserts your-entity content via `section-text`→`assemble`→`ctx/ctx-sections` (helper at :314-339) | reads `:your-entity` text out of `:seon.render/section-texts`, which `assemble` (:324) sources from **`ctx-sections` (INSPECTOR path)** that injects `:seon.agent/entity` | the real prompt path renders your-entity with **nil entity** → `""` (vanishes) | your-entity (purpose, tile-wiring, "this map IS you") **silently absent from the model prompt** while present in the human inspector — exactly F2 |
| B2 | `seon.ctx-test/your-entity-teaches-derive-purpose-only-while-unset` (ctx_test.cljs:341) — same `section-text` (inspector) path | asserts the purpose-derivation teaching appears/vanishes in the INSPECTOR your-entity text | prompt-path your-entity is nil-entity → the teaching never reaches the model | a fresh agent never sees the "derive your purpose" instruction in its actual prompt |
| B3 | the `assemble` helper itself (ctx_test.cljs:314-339) | computes BOTH `:seon.render/text` (prompt path, :322) AND `:seon.render/section-texts` (inspector path, :324) but every section-content assertion reads the **inspector** slice via `section-text` (:336) | the two paths can diverge on entity-dependent sections | structurally enables B1/B2; no test asserts the two slices AGREE |

GOOD pattern to copy (already added by the sweep):
`seon.ctx-test/live-tile-section-stable-on-composer-input` (ctx_test.cljs:387)
exercises the section directly with `{:seon.db/db … :seon.agent/id …}` (no
entity) AND the real `render/render … (context-root ctx)` path. It only checks
for "render failed" absence though — it does **not** assert your-entity/live-tile
CONTENT is present in the prompt-path render (see Coverage gaps).

---

## 2. Prioritized findings (all categories)

| Test (ns:line) | Category | Severity | Fix direction |
|---|---|---|---|
| ctx_test.cljs:456 (B1) | BUG-HIDING (inspector-path masks prompt) | High (latent) | Add a sibling assertion that the PROMPT-path render (`render/render :ai ctx (context-root ctx)`, ctx = {db,id}) CONTAINS the your-entity content. |
| ctx_test.cljs:341 (B2) | BUG-HIDING | High (latent) | Same — assert the purpose teaching via the prompt path, not `section-text`. |
| ctx_test.cljs:314-339 (B3) | BUG-HIDING (helper) | Med | Make `assemble`/`section-text` assert prompt-slice ≡ inspector-slice for entity-dependent sections, or add an explicit identity test. |
| agent_context_test.cljs.disabled (invariant **b**) | STALE but high-value | High | PORT invariant (b) "render-prompt ≡ inspect/ctx-preview byte-identity" to the keystone render — this is the exact F2 catch. |
| agent_lifecycle_test.cljs:3 (docstring "the 5-value enum") | STALE doc | Low | s/5-value/3-value/. Body is CORRECT (drives real `run-loop!`, asserts wait→:idle, complete→:idle, terminate→:terminated). |
| agent_loop_test.cljs.disabled | DEAD (pre-FSM) | n/a (disabled) | DELETE — `unanswered-live-inbound?`/`:replied`/`handled?`/`max-empty-reprompts` all gone; surviving invariant (multi-msg no-deaf) already in agent_lifecycle_test. |
| agent_retry_test.cljs.disabled | DEAD (`ask-and-eval!` gone) | n/a (disabled) | PORT the retry invariant to the new `seon.agent.turn` LLM path IF retry-once-on-transport still exists there; else DELETE. |
| agent_context_test.cljs.disabled (a,c,d,e,f,g,h) | DEAD (v4 markers) | n/a (disabled) | DELETE the `<system>`/`<your-entity>`/`<turns>` marker + §2.9-status-line + result-var-glyph pins (keystone replaced markers with `;;; ┌─` brackets). Keep only (a)/(b)/(e) as PORT. |
| agent/turns_test.cljs.disabled | DEAD (section + fns gone) | n/a (disabled) | DELETE — `<turns>` section, `task-in-progress?`, `:seon.agent/turns-cap`, `default-turns-cap`, `run-agentic-loop!` all removed; successor is `seon.ctx.usage`/P6. |
| inspector_chips_test.cljs:157 (`#_`-discarded) | STALE, correctly parked | Low | Already `#_`-discarded with a clear note; rewrite vs the activity-log derivation when it lands. No run risk. |
| gym/driver.cljs:307-321 (comments + capture) | STALE comment / inspector-path capture | Low | Comments still say "assemble-context"; capture now via `ctx-sections` `:seon.render/section-texts` (INSPECTOR path) — informational telemetry only, never gates `pass?`. Verify the gym still captures post-keystone; refresh the comments. |
| live_tile_test.cljs:139-144 | FRAGILE-ish prose pins | Low | Pins welcome wording (`#"haven't wired"`, `#"core default"`). live-tile is a fairly stable surface, but prefer the contract token `:seon.render.live-tile/content` (already also asserted) over prose. |
| ctx_test.cljs:545 (`"[broken] render failed:"`) | FRAGILE exact render-line pin | Low | Acceptable as an error contract, but it pins the exact render-error format of an actively-reshaped surface. |

NOTE — schema_test.cljs:148 is NOT stale: it asserts `:seon.handler/input` is
**absent** from the kind catalog, which is CORRECT now that the handler registry
was deleted. Keep.

NOTE — agent/message_test.cljs (P4 auto-todo block, :218-) is CORRECTLY updated:
no `handled?` assertions, "addressed derives from the linked todo's completion".
Good alignment with the new model.

---

## 3. Disabled tests — port-or-delete verdicts

All four reference fns/attrs that are **gone live** — if re-enabled they would
fail to compile/run. **Worktree trap:** the dev hook re-enables `.disabled`
tests inside worktrees; any agent working in a worktree would wrongly
re-activate all four and break the suite. They are safe only in the main tree.

- **agent_loop_test.cljs.disabled → DELETE.** Pins the pre-FSM stop policy
  (`unanswered-live-inbound?`, halt-`:replied`, `handled?`,
  `max-empty-reprompts`) — none exist. The one durable invariant ("N messages
  in a burst all get seen/answered") is already covered by
  agent_lifecycle_test's no-deaf test driving the real `run-loop!`.
- **agent_context_test.cljs.disabled → PARTIAL PORT.** Resurrect invariant
  **(b)** `render-prompt ≡ inspect/ctx-preview` byte-identity (the F2 catch),
  **(a)** "no stored ctx still gets the full default context", **(e)** the
  bounded-context (multi-MB result) guard — rewritten to the keystone render.
  DELETE (d/f/g/h): the `<x>` markers, §2.9 status line, result-var glyphs,
  byte-identical-`<system>` are all dead-model.
- **agent_retry_test.cljs.disabled → PORT-IF-EXISTS / else DELETE.** The
  transport-retry-once invariant is still desirable behavior, but its target
  `seon.agent/ask-and-eval!` is gone. Confirm whether `seon.agent.turn`'s LLM
  call retries once on a transport error; port the invariant onto that fn, or
  delete if the behavior was dropped.
- **agent/turns_test.cljs.disabled → DELETE.** The `<turns>` countdown section,
  `task-in-progress?`, `:seon.agent/turns-cap`, `default-turns-cap`,
  `run-agentic-loop!` are all removed. No salvageable invariant (the sliding
  cap is exercised via `max-turns-per-loop`; the dynamic surface is now the
  P6 first-turn bootstrap + `usage-section`).

---

## 4. Coverage gaps worth adding (do not write here — list only)

1. **Prompt-path renders entity-dependent sections (the F2 regression test).**
   `render/render :seon.render/ai {:seon.db/db db :seon.agent/id id}
   (context-root ctx)` must CONTAIN your-entity content (its `:seon.agent/purpose`,
   the "this map IS you" anchor) for an agent with `:seon.agent/entity` NOT
   manually injected — i.e. catch a section silently returning `""` because it
   read entity off a ctx the prompt path never populates. Generalize beyond
   your-entity: every section that reads ctx-entity.
2. **prompt-path ≡ inspector-path identity** (porting disabled (b)): for the
   same (db,id), the joined `render/render … (context-root ctx)` text and the
   inspector's reassembly from `ctx-sections` must agree section-for-section
   (the "ONE composer, divergence impossible" invariant).
3. **live-tile always valid-or-clear-loading** in the PROMPT path: a throwing /
   unresolvable tile → a clean `; …loading…` / "Wired: <sym>" message, never a
   bare `⚠`, never a malli keyword, never a vanish. (ctx_test:387 checks "render
   failed" absence; extend to assert the POSITIVE content + the broken-fn name
   through `context-root`, not just the direct section call.)
4. **Loop FSM transitions** (thin since agent_loop_test was disabled): the
   stop-policy `cond` arms — cap (`:turn-cap`), no-forms empty-streak
   (`:no-forms`), turn-error (`:turn-error`), verb-park caught at top check,
   superseded-wake bail — each landing `:idle` with the right
   `:seon.agent.loop/stop-reason` tx-meta; and the activity-log derivation
   reading those transitions out of `d/history`.

---

## 5. If we fix these, we'd likely surface/prevent these real bugs

- Porting/adding **gap #1 + #2** (prompt-path content + prompt≡inspector
  identity) would have caught **F2** (your-entity / any entity-dependent
  section vanishing from the real model prompt while green in the inspector) —
  the exact class B1/B2 mask today, and the highest-leverage addition.
- Adding **gap #3** prevents the live-tile "render failed / malli code / vanish"
  regressions from reaching the model prompt (only the direct-call shape is
  guarded now).
- Porting **gap #4** restores loop-stop coverage lost when agent_loop_test was
  disabled — guarding premature-park and the stop-reason tx-meta the activity
  log depends on.
- Deleting the 4 dead `.disabled` files removes the worktree re-enable trap (a
  whole-suite breakage waiting for the next agent who works in a worktree).
