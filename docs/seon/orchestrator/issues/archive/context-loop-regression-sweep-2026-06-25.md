---
type: issue
status: resolved
severity: friction
tags: [issue, agent, flow]
---

# Context + loop wiring regression sweep — 2026-06-25

Read-only audit of the CLJS pod's context composer + agentic loop on
`feature/agent-fsm`. Hunt: wiring regressions of the live-tile class (a
section destructuring a ctx key the composer never supplies) and adjacent
drift. The live-tile section itself is OUT of scope (already being fixed) —
it is the template for the bug class.

## Prioritized summary

| # | Finding | Sev | Location |
|---|---------|-----|----------|
| F1 | `your-entity` section SILENTLY vanishes from the real prompt — same root cause as live-tile (ctx lacks `:seon.agent/entity`) | P0 | `seon/ctx/your_entity.cljs:26-27` + `seon/agent/turn.cljs:188` |
| F2 | Entity-injection asymmetry: inspector path injects `:seon.agent/entity`, prompt path does NOT → the two diverge (the thing shared-render claims is impossible). One central fix resolves F1 + live-tile together | P1 | `seon/ctx.cljs:1922` vs `seon/agent/turn.cljs:188`; root pull at `ctx.cljs:1840-1847` |
| F3 | Stale agent-facing contract: `add-section!` docstring promises section fns get `{:seon.db/db … :seon.agent/entity …}`; prompt path supplies only db+id | P1 | `seon/agent.cljs:633` |
| F4 | Render guard masks regressions asymmetrically: a throw shows `;; ⚠ … render failed` (loud), but a section returning `""` on nil input vanishes with NO marker (silent) | P2 | `seon/render.cljs:643-648`; `your_entity.cljs:27` |
| F5 | Duplicated sliding-window cap logic (drift risk between enforced cap and displayed cap) — justified by a require cycle but flagged | P2 | `seon/ctx.cljs:285` vs `seon/agent/loop.cljs:175` |

Negative results (checked, NOT broken): `:shared-instructions`,
`:warnings`, `:open-todos`, `:inventory`, `:namespaces`, `:transcript`
sections; the loop / lifecycle / message / todo FSM. Details at the bottom.

## Live proof

Rendered prompt dump (`tmp/ctx-dump.txt`, 3037 lines) contains ONLY four
section brackets: `soul` (1-51), `namespaces` (53-3007), `live-tile`
(3011-3013, the `⚠ render failed: :malli.core/invalid-input`), `transcript`
(3015-3038). Missing entirely: `shared-instructions`, `your-entity`,
`warnings`, `open-todos`, `inventory`.

Live eval against the running pod (agent `HtK-2606251913`):

```
prompt-ctx-keys                      => (:seon.db/db :seon.agent/id)   ; NO :seon.agent/entity
your-entity-section <prompt ctx>     => "" (blank)
your-entity-section <ctx+entity>     => 717 chars (renders fine)
context-root :seon.agent/entity      => present on the ROOT node only
shared-instr / warnings / open-todos / inventory  => all 0  (empty state, db present — legit blank)
```

So `:soul` … `:namespaces` make the byte-stable prefix (cache boundary
correctly lands after namespaces, dump line 3009; `stable-priority-max` 20,
namespaces priority 20 ✓). `your-entity` is the only NON-blank-by-state
section that disappears — confirming F1.

---

## F1 — `your-entity` section silently vanishes from the prompt (P0)

`seon/ctx/your_entity.cljs:26`:

```clojure
[{:seon.agent/keys [id] entity :seon.agent/entity}]
(if (nil? entity) "" …)
```

The composer's prompt path (`render-prompt`, `seon/agent/turn.cljs:188`)
builds `ctx {:seon.db/db db :seon.agent/id agent-id}` and calls
`(render/render :seon.render/ai ctx (ctx/context-root ctx))`. `context-root`
pulls the agent entity and puts it on the ROOT node map
(`:seon.agent/entity`, `ctx.cljs:1843-1844`), but the recursion handle
`render` threads only the ORIGINAL `ctx` to children
(`render.cljs:642` — `#(render view ctx %)`), so `:seon.agent/entity` never
reaches a child section. `entity` is nil → the section returns `""` → it is
dropped.

**Expected:** the agent sees its own entity ("this map IS you") every turn —
the surface the SOUL leans on ("transact onto your own entity", "add the
section to your own context"). **Actual:** the section is absent from every
real prompt. Identical root cause to the live-tile bug; live-tile THROWS
(its `:malli/schema` requires the key), your-entity silently returns `""`,
which is worse (no `⚠`).

**Fix direction:** inject `:seon.agent/entity` into the ctx threaded to
children (see F2 — one fix covers live-tile + your-entity).

## F2 — entity-injection asymmetry: inspector ≠ prompt (P1, the root cause)

The inspector path (`ctx-sections`, `ctx.cljs:1920-1922`) pulls the root and
does `ctx* (assoc ctx :seon.agent/entity (:seon.agent/entity root))`, then
renders each child with `ctx*` — so `:seon.agent/entity` IS in scope and
your-entity/live-tile render there. The prompt path (`render-prompt`,
`turn.cljs:188`) does NOT do this assoc. Result: the inspector's debug view
shows `your-entity` (and a working live-tile), the model's actual prompt
drops both — the exact divergence the shared-render design claims is
impossible ("the human's debug view and the model's prompt can never
diverge", `turn.cljs:177-178`).

**Fix direction (turtles, one place):** centralize entity injection so
neither caller can forget — e.g. have `render-context-ai`/`context-root`
re-inject the pulled `:seon.agent/entity` into the ctx its children render
under, or pull-once-and-assoc inside `render-prompt` exactly as
`ctx-sections` already does. A single central fix resolves F1, live-tile,
and any future entity-reading section at once. Do NOT fix live-tile
narrowly (e.g. re-pulling inside the tile) — that leaves your-entity broken.

## F3 — stale agent-facing contract in `add-section!` (P1)

`seon/agent.cljs:633` (the `add-section!` docstring agents read to author
their own computed sections):

> a qualified symbol of a fn called at every render with
> `{:seon.db/db … :seon.agent/entity …}`

The prompt render path supplies only `:seon.db/db` + `:seon.agent/id`
(proven above). Any agent that writes a section fn destructuring
`:seon.agent/entity` will get nil and silently render `""` — the same trap,
now taught as the documented API. **Fix direction:** after F2 lands, the
contract becomes true; until then the docstring overstates what's injected.
(Owner directive — stale agent-facing instructions tank performance; align
this when F2 is fixed.)

## F4 — render guard masks regressions asymmetrically (P2)

`render.cljs:643-648`: a section fn that THROWS is caught and rendered as
`;; ⚠ [name] render failed: <msg>` — loud, self-healing, good (this is how
live-tile is visible). But a section that returns `""` (your-entity on nil
entity, `your_entity.cljs:27`) is indistinguishable from a legitimately
empty section (warnings/todos/inventory on clean state) and vanishes with no
marker. So the guard surfaces throw-class regressions but HIDES
nil-collapses-to-blank regressions. **Fix direction:** the nil-entity branch
should not survive F2; while it does, it is the reason F1 went unnoticed.
Not a place to add a new error string — fix the wiring (F2) so entity is
never nil.

## F5 — duplicated sliding-window cap logic (P2 smell)

`seon/ctx.cljs:285` `effective-cap [agent-id db]` is a hand-copied mirror of
`seon/agent/loop.cljs:175` `effective-cap [id my-wake]` (base cap + inbounds
since this wake's first turn). The readline (`transcript.cljs:298`) displays
the ctx copy; the loop ENFORCES its own. The docstring justifies the
duplication (requiring `seon.agent.loop` would cycle fsm → seon.agent →
seon.ctx). Acceptable, but two copies of the cap window WILL drift; if the
display and the enforced cap disagree, the readline's steering ("you are
near the per-loop cap") misleads the agent. **Fix direction:** extract the
pure window computation into a cycle-free helper ns both require, or accept
+ add a test pinning the two to agree.

---

## Negative results (verified NOT broken)

- **`:shared-instructions`** (`my/kb/shared.cljs:96`), **`:warnings`**
  (`ctx/warnings.cljs:10`), **`:open-todos`**
  (`agent/todo/internal.cljs:93`), **`:inventory`**
  (`ctx/inventory.cljs`): all destructure only `:seon.db/db` (+ `:seon.agent/id`)
  — keys the composer DOES supply — and correctly return `""` on empty
  state. Live-confirmed 0 rows / 0 chars each on the current near-empty
  store. They are absent from the dump because the cluster is fresh, not
  because of a wiring fault. (Inventory showing nothing on a store that has
  a message + session is plausible given its post-bootstrap filter; worth a
  glance later but not a regression — it reads db fine.)
- **`:namespaces`** (`ctx/namespaces.cljs:240`) and **`:transcript`**
  (`ctx/transcript.cljs:335`): destructure `:seon.db/db` / `:seon.agent/id` /
  `:seon.render/render` — all supplied; both render. Transcript's `render*`
  falls back to a local ai render when the handle is absent
  (`transcript.cljs:370`) — correct for the direct-call (gym/re-export) path.
- **Loop FSM** (`agent/loop.cljs`): 3-state model (`:idle` wakeable /
  `:active` running / `:terminated` unwakeable), the whole stop policy in
  one `cond`, the sliding cap (`effective-cap = base + inbounds-this-wake`),
  the read-then-write-with-recheck wake (no atom), the hop guard at wake,
  and the `finally` reset stamping the implicit-exit `stop-reason` all match
  `docs/prds/agent-fsm/agent-loop.md`. No drift found.
- **Lifecycle** (`agent/lifecycle.cljs`): `wait`/`complete` both park to the
  single `:idle` (difference is `:seon.agent.loop/stop-reason` tx-meta, not a
  distinct state); `terminate` → `:terminated`; each errors-as-values, none
  writes a self→self message. Matches the spec.
- **Message ↔ todo coupling (P4 cure)** (`agent/message.cljs:215-232`): a
  `:human`-origin inbound to an agent recipient auto-mints ONE address-todo
  per agent-recipient ATOMICALLY in the same tx (back-ref to the message,
  owner = agent), no listener/cascade; "addressed" is derived from todo
  completion (no stored `handled?`). Outbound agent→user mints nothing
  (origin `:agent`) — consistent with the dump (agent messaged the user,
  then `(wait …)` → `:idle`, zero todos). Correct.
- **Activity log** (`agent/loop.cljs:399`): derives over tx-meta-bearing txs
  (cause/stop-reason) + reconstructs state as-of each tx from
  `:seon.agent/state` history — so idle→idle wait/complete txs (no new state
  datom) still report. Schema-consistent. No fault found.
- **Cache boundary / byte-stable prefix**: `stable-priority-max` 20; soul(5),
  agents(8), shared-instructions(10), namespaces(20) form the prefix; the
  in-band boundary lands after namespaces (dump line 3009). Correct.

## One-line fix ordering for the orchestrator

F2 is the keystone — fixing entity-injection centrally resolves F1
(your-entity), the in-flight live-tile bug, and makes F3's contract true.
F4 disappears once F2 lands. F5 is independent cleanup.

## Resolution (2026-06-28 audit)

Closed RESOLVED/SUPERSEDED per `docs/seon/orchestrator/issues-audit-2026-06-28.md`:
the `your-entity` section was removed (`37c47f27`); there is now a single
byte-identical `render-context` producer and the effective-cap duplication is
gone.
