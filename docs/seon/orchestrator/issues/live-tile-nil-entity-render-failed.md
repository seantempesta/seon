---
type: issue
status: completed
tags: [issue, agent, render]
---

# Live-tile section rendered "⚠ render failed: :malli.core/invalid-input"

## Symptom

On every fresh boot the `:live-tile` context section rendered, into the
agent's own prompt:

```
;; ⚠ [:live-tile] render failed: :malli.core/invalid-input
```

A bare, swallowed malli code — the exact unactionable placeholder the owner
banned. The agent reading its own context could not tell what its human saw,
nor how to fix it.

## Root cause

`seon.ctx.live-tile/live-tile-section` destructured `entity
:seon.agent/entity` from its ctx arg, but the composer's PROMPT path
(`seon.agent.turn/render-prompt`) injects only `{:seon.db/db …
:seon.agent/id …}` — it never supplies `:seon.agent/entity`. (The recursion
handle in `seon.render/render` threads the original ctx to children; the
agent entity is pulled onto the ROOT node only, so it never reaches a child
section.) So `entity` was nil.

On a fresh world `:seon.render.live-tile/content` is not yet installed (lazy
schema install at first transact), so the `installed-schema` gate was false
and the section used the nil `entity` directly. It then called
`live-tile/wired-content {:seon.render/entity nil}`; that fn's instrumented
input schema requires `:seon.render/entity` be a `:map`, so it threw
`:malli.core/invalid-input`. The section's outer guard (`seon.render/render`,
render.cljs:643-648) caught the throw and reported it as the `⚠` placeholder.

`wired-content` itself was fine — given a real entity map (or even `{}`) it
returns the welcome default. The bug was purely how the section resolved the
entity.

## Sibling regression (F1)

`seon.ctx.your-entity/your-entity-section` had the SAME root cause but failed
SILENTLY: it returned `""` on the nil entity, so the section vanished from
the prompt with no marker (the agent never saw its own entity — "this map IS
you"). Confirmed via the prompt dump: only `soul`, `namespaces`, `live-tile`,
`transcript` brackets appeared; `your-entity` was missing.

The asymmetry that hid F1: the inspector path (`seon.ctx/ctx-sections`,
ctx.cljs:1922) DOES `assoc :seon.agent/entity` into the ctx before rendering
children, so the debug view rendered both sections fine — only the model's
real prompt diverged.

## Fix (local + defensive, two section files)

Both sections now resolve the agent entity from the db value passed in, as a
pure function of state — no reliance on a `:seon.agent/entity` ctx key the
prompt path doesn't pass, no stored state, no cache, no atom, no globalThis.

- `src/seon/ctx/live_tile.cljs` — `live-tile-section`:
  - resolves `::content` by `:seon.agent/id` via `seon.db/pull` behind the
    `installed-schema` gate (falls back to `{}` → the welcome default when
    the attr is uninstalled or absent), so `wired-content` always gets a map;
  - the body still flows through `seon.render/render-agent-tile` — the ONE
    SCI+timer-safe tile entry point;
  - the whole section is wrapped in a `try` that degrades any unexpected
    throw to a CLEAR loading/safe-state message (what's loading, that the
    human sees the calm welcome card, how to re-wire) — never a bare malli
    code or `⚠`.

- `src/seon/ctx/your_entity.cljs` — `your-entity-section`:
  - when `:seon.agent/entity` is absent, pulls the agent entity from the db
    by `:seon.agent/id` (same pull shape `context-root` uses), so the section
    stops silently returning `""` and always shows the agent its own entity.

No `turn.cljs` / `render.cljs` / `ctx.cljs` composer edits — the central
entity-injection unification (single-source threading so the prompt-ai and
inspector-left views are byte-identical) is a separate follow-up wave. These
fixes are defensive and survive that later change (a pure fn of db composes
cleanly with reactive render-once-and-transact).

## Stability contract (owner requirement)

The live-tile section must ALWAYS show valid content OR a clear
loading/safe-state message that is actionable to the agent — NEVER a bare
`⚠ render failed` with a swallowed error code. Held by two layers:

1. `render-agent-tile` is throw-safe: a tile fn that throws or hangs returns
   `error-response` (a calm "updating this panel" card for the human +
   `"YOUR LIVE TILE IS BROKEN — … Fix the fn"` twin for the agent).
2. the section's own `try` backstop turns any other unexpected failure into
   an actionable safe-state string.

## SCI + timer safety finding

The safety mechanism is `seon.render.sci` (`invoke-bounded`,
`bounding-enabled?`, `agent-authored-sym?`, `recover-hung-tile!`,
`default-budget-ms`). It runs AGENT-authored tile fns under a wall-clock
deadline via SCI's `:interrupt-fn` (`deadline-interrupt-fn` throws an
un-catchable, un-forgeable interrupt once `js/Date.now` passes the
per-render deadline), so a non-terminating tile (`(loop [] (recur))`) aborts
in-process instead of freezing the single pod thread; on a tripped deadline
`recover-hung-tile!` resets the tile to welcome and warns the agent.

`seon.render/render-agent-tile` (render.cljs:414-474) already routes through
this: it SCI-bounds agent-authored tile symbols, falls back to the compiled
path for core symbols, runs a serializer-faithful structural check on the
hiccup, and wraps everything in a `try` → `error-response`. The live-tile
SECTION's body comes from `render-agent-tile`, so the body path was ALWAYS
through the safety — the regression was only in the section's separate header
resolution (`wired-content` for the wired-label), which is pure resolution
and can't hang. The fix keeps the body on the safe path and removes the nil
from the header path.

## Live proofs

- Repro before fix: section threw `:malli.core/invalid-input` (ex-data named
  `wired-content` arg 0 `:seon.render/entity` `nil`).
- After fix (fresh world, prompt ctx `{:seon.db/db … :seon.agent/id …}`, NO
  injected entity): full context render has `:has-warn false`,
  `:has-render-failed false`, brackets `["soul" "namespaces" "your-entity"
  "live-tile" "transcript"]`; `your-entity` direct call = 717 chars.
- Throw-safety: wiring `:seon.render.live-tile/content` to a broken
  vector-of-vectors hiccup → section shows `YOUR LIVE TILE IS BROKEN — …
  Splice the children …`, `has-warn false`, `has-malli false`; retracted →
  back to welcome.

## Regression guard

`test/seon/ctx_test.cljs` →
`live-tile-section-stable-on-composer-input`: creates an agent, calls both
sections with the EXACT composer input shape (db + id only, no entity),
asserts no `⚠`, no `malli`, `Wired:` present, your-entity present
(`YOUR OWN ENTITY`); the broken-tile case asserts a clear message naming the
wired fn. Full CLJS suite green.
