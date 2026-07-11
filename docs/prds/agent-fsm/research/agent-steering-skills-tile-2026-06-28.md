---
type: research
status: active
tags: [research, agent, context]
---

# Agent steering, skills system, live-tile usage + two design ideas (2026-06-28)

> Investigation against the LIVE pod (7890, provider `:deepseek`, wire-server store
> `data/clusters/default`). Every token figure is `chars/4` measured on the REAL rendered
> bytes via `seon.agent.inspect/ctx-preview` (the byte-identical debug of what the LLM
> receives). One live agent was driven end-to-end to settle the live-tile question. NO src
> edits — investigation + design only.

## TL;DR

- **The hardcoded steering every agent gets is the SYSTEM message** =
  `seon.agent.ctx/system-text` (12 458 chars, **~3 114 tok**), byte-stable, identical for
  root and every normal agent. It is NOT the soul and NOT a file.
- **Root vs a normal agent differ in EXACTLY ONE block: the live tile.** Root's live-tile
  content is seeded to `seon.render.system/system-view` (the fleet/system dashboard,
  **~857 tok**); a normal agent's live-tile block is the welcome-placeholder + how-to-wire
  steering (**~611 tok**). Every other block — system message, namespaces floor, todos,
  warnings, transcript — is the **same seed** (`default-seed-blocks`). There is **no
  role/`:kind` divergence**; root is just a normal agent with one post-seed tile transact
  (`client.cljs:2359-2364`).
- **The "skills system" does NOT exist in code yet.** It is a *design doc only*
  (`my-skills-design-2026-06-28.md`, status `draft`, authored today). No `src/my/skills.cljs`,
  zero `my.skills` references in `src/`. The closest existing thing is the `my.*` toolkit floor
  + the dormant (currently 0-token) `:shared-instructions` block.
- **Agents DO use the live tile, and the context DOES steer them to.** Live-driven a fresh
  normal agent with "show my todo list as a live tile" → it authored
  `my.agent.<id>/todo-tile` (a render-twin fn) and wired it onto
  `:seon.render.live-canvas/content` across 12 turns / 39 evals, then confirmed to the human.
  Live-proven below.
- Design (a) **skill catalog + `enable-skill`**: fits the block model cleanly — it IS the
  `my.skills` design doc's level-1 catalog + level-2 `load`/`install!`. Recommend building it,
  with the catalog slotting into the already-empty `:shared-instructions` band.
- Design (b) **collapse evals under the agent message**: feasible and cheap, but **HTML-twin
  only** — the `:ai` text transcript MUST stay flat (it is an eval'able REPL session). Concrete
  change is a grouping pass in `transcript-block-html`. Sketched below.

---

## 1. What steers root vs a normal agent (token weights, chars/4)

### 1a. The SYSTEM message — the hardcoded steering (identical for ALL agents)

`seon.ai/effective-system-prompt` returns `seon.agent.ctx/system-text` for every call (no
override in normal use). It is the LLM `system` role; SOUL.md / AGENTS.md are *context*
sections, not this. Measured live:

```
seon.agent.ctx/system-text — 12 458 chars  →  ~3 114 tok   (byte-stable, same every turn/agent)
```

What it actually says (verbatim section headers, full text in `ctx.cljs:885`):

- `; ── system ──` opener — "You are at a live Clojure REPL on one human's runtime. The REPL
  is your only tool… It is ClojureScript in a long-running Node process… NO JVM."
- **THE LIVE CONTEXT SYSTEM** — "This whole prompt re-derives from the shared database every
  turn: every section is a view of NOW, not an accumulating log. Fix a problem and its warning
  vanishes…"
- **THE TRANSCRIPT IS ONE EVAL'ABLE REPL SESSION** — the `;` comment / form / `;=>` value
  contract; "Re-evaluating it would run only the forms… a replayable program."
- **EVAL MECHANICS** — "A form RUNS only if it starts with `(` on a new line… a bare data
  literal you paste does NOT evaluate."
- **THINK IN COMMENTS** — reasoning lives in `;` lines; backticks + code fences derail the
  reader.
- **REPORT THE VALUE YOUR LAST EVAL RETURNED** — never quote a remembered number, only the
  `;=>` value.
- **RESULT VARS** — every eval's value is a live `result/<id>` var; dig into big results with
  Clojure rather than re-querying; printed values are summaries/placeholders.
- **STATE ACROSS TURNS** — `(defn …)` and `(def !x (atom …))` persist; a bare `(def x 42)`
  does not survive read-back (self-host limit).
- **ERRORS ARE VALUES** — "Core calls never throw at you — a failure comes back as data."
- **THE RENDERING SYSTEM** — render twins `:seon.render/ai` + `:seon.render/html`.
- **THE SHARED STORE** — one shared datahike; `register!` before transact; ≥2-segment
  namespaced attrs.
- **THE NAMESPACES BELOW are real loaded code** — your own ns + `my.*` + `todo` render full;
  the rest of seon is queryable/searchable, not dumped; "Never hallucinate a fn name —
  discover it" with the three discovery calls.
- **BUILD YOUR ENVIRONMENT** — "This runtime is yours to shape… CREATE a namespace…
  `my.*` your code, `my.kb.*` your knowledge, `seon.*` the core (call it, never redefine it)."
- **STANDING TEACHINGS** — consult stored knowledge first; store what you verify; keep fns
  small + one `:test` example; mint a todo per step; address-todo on inbound; messages render
  as markdown.
- **MESSAGING + LIFECYCLE** — the four verbs (`message/user`, `message/agent`, `wait`,
  `complete`); tell-your-human cadence; done-via-verb; per-loop sliding-window cap; canvas/panel
  hiccup splicing; grade `my.kb` facts with source+confidence.

This 3 114-tok block is the **entire hardcoded steering** and it is **the same for root and
every normal agent**. Roles do not change it.

### 1b. ROOT's live context (`ctx-preview {:seon.agent/id "root"}`) — live

```
section          tok     steers toward
---------------  -----   ------------------------------------------------------------
:system          3114    (the hardcoded block above)
:namespaces      7928    THE BODY — full source of my.kb, my.kb.shared, seon.agent.todo,
                          + root's home ns my.agent.root; rest of seon = queryable
:live-tile        857    *** ROOT'S DISTINCTIVE PIECE *** — content = system-view, the
                          fleet/system dashboard :ai twin (every agent + its state)
:open-todos        97     root's open work items (derived; vanishes when none)
:transcript       458     masthead + flat event log + folded readline
---------------  -----
TOTAL           12548
   (dormant, 0 tok this turn: :shared-instructions, :warnings, :relevant-source, :inventory)
```

### 1c. A NORMAL agent's live context (freshly created `Rtd-2606281344`) — live

```
section          tok     steers toward
---------------  -----   ------------------------------------------------------------
:system          3114    identical hardcoded block
:namespaces      7858    full source of my.kb, my.kb.shared, seon.agent.todo + its
                          own home ns (see leak note §1e)
:live-tile        611    *** the welcome placeholder + HOW-TO-WIRE steering ***: shows the
                          core welcome card body, then copy-paste (a) literal-hiccup and
                          (b) tile-fn recipes, "PRESENT RICHLY, OR JUST REPLY IN MARKDOWN"
:warnings         859    NOISE this run — cross-agent failed-evals from root's history
                          (the failed-evals warning is cross-agent; ~0 in a clean store)
:transcript        78     grows as the agent acts
---------------  -----
   (dormant, 0 tok: :shared-instructions, :open-todos, :relevant-source, :inventory)
```

### 1d. Side-by-side — root vs normal, block by block

```
block                 ROOT      NORMAL    difference
--------------------  --------  --------  --------------------------------------------
system (hardcoded)    3114      3114      IDENTICAL
namespaces floor      7928      7858      same set + own home ns (root's is populated)
live-tile             857       611       *** THE ONLY STRUCTURAL STEERING DIFFERENCE ***
                                          root = system-view fleet dashboard;
                                          normal = welcome + "how to set a tile"
open-todos            97        0/derived derived per agent
warnings              0         (noise)   derived, cross-agent; not a seed difference
transcript            458       78        derived per agent
shared-instructions   0         0         DORMANT empty slot in both (see §2)
--------------------  --------  --------  --------------------------------------------
TOTAL (this turn)     ~12.5k    ~11.7k clean
```

**Conclusion:** root and a normal agent are steered by the SAME 3 114-tok system message and
the SAME `default-seed-blocks` layout. The *only* role-distinguishing steering is the
**live-tile content** — root's tile is the system/fleet dashboard, a normal agent's tile is
the empty-welcome + wiring tutorial. "Root vs normal" is one seeded tile transact, nothing
more (`client.cljs:2359`). This is the `:no-kind` discriminator philosophy honored:
capability via data, not a class stamp.

### 1e. The toolkit / verb catalog the agent sees

- **Verbs taught by name in the system message:** `message/user`, `message/agent`, `wait`,
  `complete`; `seon.agent.todo/add!` + `done!`; `schema/register!`, `db/transact!`,
  `db/query`, `db/pull`, `db/store-inventory`, `seon.agent.search/grep`,
  `seon.agent.ctx/render-namespace`.
- **Rendered-in-full namespaces (the `:namespaces` body):** `my.kb` (the worked DB manual),
  `my.kb.shared`, `seon.agent.todo` (the one curated seon.* tool example —
  `full-source-whitelist #{:seon.agent.todo}`, `namespaces.cljs:126`), plus the agent's
  `my.agent.<id>` home ns. **The rest of `seon.*` is NOT dumped** — indexed + grep-able only.
- **Smell flagged — cross-agent home-ns leak:** `namespaces-block` renders **every** `my.*`
  ns in full (`full-source-ns?` → `my-ns-name?`, `namespaces.cljs:62,137`). With one agent
  that means the probe saw `my.agent.root` (root's private workspace) in full, not its own.
  `home-ns` resolves correctly per-agent, but the include rule is "all `my.*`", so as the
  fleet grows **every agent will see every peer's `my.agent.<id>` home ns dumped in full**.
  May be intentional ("`my.*` is the one human's shared world"), but `my.agent.<id>` reads
  like per-agent scratch space; worth a deliberate decision before fleets scale token cost.

---

## 2. The "skills system" — does NOT exist in code yet (design-only)

**Finding: there is NO skills system in `src/seon` (or `src/my`).** Evidence:

- `src/my/skills.cljs` does **not exist**; `grep -rl "my.skills" src/` returns **nothing**.
- The only artifact is **`docs/prds/agent-fsm/research/my-skills-design-2026-06-28.md`**
  (status `draft`, authored today) — a design that maps `my.skills` onto existing machinery
  (a loaded skill = a `:seon.agent.ctx/block`; `load` = `install!`; `unload` = `remove!`;
  body rides the existing `file-block` + `:seon.agent.ctx/file-path` path; catalog = one
  section block; token footer derived via `seon.ai.tokens/estimate`). It is the substrate the
  owner's "skills are new" likely refers to — but it is unbuilt.
- The repo's `.claude/skills/*` (datahike, clojurescript) are **Claude-Code agent skills**
  (for me), not seon-agent skills. The design proposes seeding *those files* as the first
  `my.skills` rows, but that wiring does not exist.

**Closest existing thing today:** the `my.*` toolkit floor (§1e) — always-loaded full source
of `my.kb` etc. — plus the **dormant `:shared-instructions` block** (priority 10,
`my.kb.shared/instructions-block`), which currently renders **0 tokens** (live-measured: the
reactive block returns `""` because no shared-instruction data is seeded). That empty,
already-wired band is exactly where the design's always-on skill catalog would slot.

So: **no preloaded vs on-demand skill cost to report — there are no skills.** The design's
intended costs would be: catalog (level-1 name+description) ~tens of tok always-on; a loaded
body (level-2) = its `SKILL.md` size, `chars/4`, shown with a derived `~N tok` footer and an
`unload` hint.

---

## 3. Do agents actually USE the live tile? — YES (live-proven)

**Store scan:** only **root** carries a custom `:seon.render.live-canvas/content`
(`seon.render.system/system-view`, its seeded dashboard). No other persisted agent had one at
start — because only root existed.

**Does the context steer agents to set one?** Yes, structurally and every turn. The
`:live-tile` block (`live_tile.cljs:98-139`) ALWAYS renders (~611 tok for a normal agent) and
contains explicit copy-paste recipes: "(a) literal hiccup — instant, no fn needed" and "(b) a
tile FN in your home ns — re-derives every render", each with a full `seon.db/transact!`
example targeting `:seon.render.live-canvas/content`. It also softens it: "PRESENT RICHLY, OR
JUST REPLY IN MARKDOWN… your LATEST REPLY renders as a real markdown card… Build a tile only
when you want to show something richer."

**Live drive (the proof):** created a fresh normal agent `Rtd-2606281344`, armed its wake
trigger (`seon.client/rearm-wake-triggers!`), and POSTed to `/chat`: *"Show my todo list as a
live tile on my canvas please, not just a text reply."* (DeepSeek, the default adapter.)

Observed in `logs/pod.log` + the store:

- The agent ran **12 turns, 39 evals**, then `halt verb — complete`.
- It **authored `my.agent.Rtd-2606281344/todo-tile`** — a render-twin fn in its OWN home ns
  (it chose recipe (b)) — and transacted that qualified symbol onto
  `:seon.render.live-canvas/content`. Store read-back:
  `{:tile #{["my.agent.Rtd-2606281344/todo-tile"]}}`.
- Rendering its tile live (`seon.render/render-agent-tile`) returns a real twin:
  - `:seon.render/ai` → `"Your human sees your open todo list: 0 items — "`
  - `:seon.render/hiccup` → `[:div {:class "p-3 flex flex-col gap-2"} [:h2 … "My Open Tasks"]
    [:p … "All caught up — nothing open."]]`
  - `:seon.render/error` → `nil`. It is **derived from the agent's open todos**, so it
    self-updates — exactly the reactive pattern the context teaches.
- It then messaged the human: *"Done! Your todo list is now showing as a live tile on your
  canvas… the tile updates automatically as tasks change."*

So the earlier-#19 behavior (a DeepSeek agent building a todos tile) **still happens** —
reproduced cold here. The live-tile steering works: a normal agent, given a tile-shaped task,
builds a proper render-twin fn and wires it without being told the API (it discovered it from
the `:live-tile` block's recipes). (Note: the probe agent + its tile now persist in the
default store; harmless, but a `cluster reset` clears it if a pristine world is wanted.)

---

## 4. Two owner design ideas — assessment + recommendation

### (a) Preloaded skill CATALOG + `enable-skill` at known token cost

**This is exactly the unbuilt `my.skills` design** (`my-skills-design-2026-06-28.md`), and it
fits the current context-block model with near-zero new mechanism:

- **Always-on catalog (level-1, cheap):** one section block (slot it into the *already-empty*
  `:shared-instructions` priority-10 band so it rides the cached static prefix). It queries
  `:my.skills/name` + `:my.skills/description` rows and renders one line each + a `● loaded`
  marker derived from the agent's own ctx blocks. Cost ≈ tens of tok, constant.
- **`enable-skill` = `(my.skills/load :name)` = `seon.agent.ctx/install!`** a single block
  whose body is the `SKILL.md` (read fresh via the existing `file-block` /
  `:seon.agent.ctx/file-path` path; agent-authored skills carry an inline `:my.skills/body`).
  Put loaded bodies at **priority 30 (volatile band, > `stable-priority-max` 20)** so
  load/unload never busts the cached prefix.
- **Known token cost:** a `~N tok` footer **derived at render** via `seon.ai.tokens/estimate`
  over the body, plus `≈X%` of the per-turn prompt total (read from the existing per-turn
  fiber stash `:relevant-source` already uses), with a `(my.skills/unload :name)` hint. No
  stored count — reactive, self-healing.
- **`disable-skill` = `(my.skills/unload :name)` = `remove!`** the block; tokens gone next
  render.

**Fit:** perfect — it reuses `install!`/`remove!`, `file-block`, `quote-lines`,
`tokens/estimate`, and the `my.*` row model. The only genuinely-new code is a 3-attr
`:my.skills/*` schema, one body+footer render fn, one catalog render fn, three thin verbs, and
a ~10-line frontmatter `name`+`description` scanner. **Recommendation: BUILD IT.** It directly
serves the owner's "balance preloading a steering stub with on-demand full bodies at a known
cost," and it slots into the dormant `:shared-instructions` band so it adds catalog tokens
where there are currently zero. Honor the design's anti-fork guardrails (no `:kind`, no second
markdown loader, no stored token count). Pre-seed `.claude/skills/{datahike,clojurescript}` as
the first two rows to prove the path with zero new content.

### (b) Collapse evals UNDER the agent's message (natural conversation, expandable evals)

**Goal:** in the transcript, nest a turn's evals under its agent message so the human reads a
conversation of messages with expandable supporting evals.

**Feasibility against the current render:** feasible, but with one hard constraint —
**do this in the HTML twin ONLY, never the `:ai` text.** The `:ai` transcript
(`transcript-block`, `transcript.cljs:335`) is, by system-message contract, "ONE eval'able
REPL session" — a flat, byte-stable, time-ordered union of messages + evals
(`ordered-events`, `:362`) whose flat ordering is load-bearing for both replay semantics and
the cache prefix. Grouping it would break the "re-evaluating reproduces your state" promise.
The human-facing **HTML twin** (`transcript-block-html`, `:428`) has no such constraint — it
already renders each event to its own card via `seon.handlers.eval/render-html` /
`seon.handlers.message/render-html`.

**Concrete change (HTML-lane, mine):**

1. In `transcript-block-html`, after `ordered-events` produces the flat `:at`-sorted seq,
   add a **grouping pass**: bucket each run of evals onto the most recent **agent (outbound)
   message** that precedes them — a "turn" = an agent message + the evals it triggered until
   the next message. Inbound (human) messages stay top-level (they open a turn).
2. Render each agent-message card normally, then append a collapsible
   `<details>` containing its bucketed eval cards as nested "supporting evals" (with a count,
   e.g. `▸ 4 evals`). Inbound human messages render as plain top-level cards.
3. Keep `transcript-block` (the `:ai` text) **untouched** — flat, as-is.

**Gotchas to respect:** (i) the value-explorer `<summary>`-flex marker gotcha from prior UI
work — don't put `flex` directly on `<summary>` or the `▾` disclosure marker disappears; wrap
contents in an inner flex `<div>`. (ii) Evals that precede the FIRST agent message (e.g. the
bootstrap turn) have no parent message — render them under a synthetic "session start" group
or top-level. (iii) The grouping is a pure derivation over the same event stream — no stored
"turn container" (turn boundaries are explicitly NOT containers today, `:342`); keep it that
way. **Recommendation: do it, HTML-twin only, as a grouping pass in `transcript-block-html`;
leave the `:ai` transcript flat.** Low risk, real UX win, no agent-facing behavior change.

---

## Live proofs (REPL-verifiable)

- `(quot (count seon.agent.ctx/system-text) 4)` → `3114`
- `(inspect/ctx-preview {:seon.agent/id "root"})` → total `12548`; sections as §1b.
- `(inspect/ctx-preview {:seon.agent/id "Rtd-2606281344"})` → sections as §1c.
- `(db/query '[:find ?c :where [?e :seon.agent/id "Rtd-2606281344"]
   [?e :seon.render.live-canvas/content ?c]])` → `#{["my.agent.Rtd-2606281344/todo-tile"]}`
- `(seon.render/render-agent-tile {:seon.agent/id "Rtd-2606281344" :seon.db/db @db/*conn*})`
  → real `:seon.render/ai` + `:seon.render/hiccup` twin (todo tile).
- `(my.kb.shared/instructions-block {…})` → `""` (the `:shared-instructions` slot is dormant).
- `grep -rl "my.skills" src/` → nothing; `src/my/skills.cljs` absent.

## Source map (files read)

- `src/seon/agent/ctx.cljs` — `system-text` (`:885`), `default-seed-blocks` (`:1603`),
  `install!`/`remove!` (`:1697`/`:1731`), `seed-default-ctx!`.
- `src/seon/ai.cljs` — `effective-system-prompt` (`:360`), `debug-full-prompt` (`:371`).
- `src/seon/agent/inspect.cljs` — `ctx-preview` (`:59`).
- `src/seon/agent/ctx/live_tile.cljs` — the `:live-tile` block + wiring steering (`:19-156`).
- `src/seon/agent/ctx/namespaces.cljs` — `included-ns?`/`full-source-ns?`/
  `full-source-whitelist` (`:82-157`) — the toolkit floor + the home-ns leak.
- `src/seon/agent/ctx/transcript.cljs` — `ordered-events` (`:320`), `transcript-block`
  (`:335`), `transcript-block-html` (`:428`).
- `src/seon/client.cljs` — root's system-view seed (`:2359`), `rearm-wake-triggers!`
  (`:1940`), `boot-one-agent!` (`:1990`).
- `src/seon/agent.cljs` — `create!` (`:414`); `src/seon/web/serve.cljs` — `/chat` (`:420`).
- `docs/prds/agent-fsm/research/my-skills-design-2026-06-28.md` — the (unbuilt) skills design.
