---
type: research
status: active
tags: [agent, research, context]
---

# Context coherence audit — does what the agent SEES match the new reality?

Read-only coherence pass over the FULL always-on agent context as rendered by
the LIVE default pod (7890), against the reality this session shipped: the
`my.data`/`my.ui`/`my.canvas` toolkit, canvas-first live tiles, the `:kind`
purge (attribute-presence inventory), the `:live-tile` trim, signature-trimmed
namespaces, and refreshed skills. Distinct from the token audit — this asks
"does the context TEACH the new mechanics," not "is it too big."

Method: rendered `seon.agent.ctx/render-context` for a live agent
(`ETF-2606282057`) + read `system-text` from source + every always-on block +
the `seon-skills/*` bodies. Sizes in tokens (`seon.ai.tokens/estimate`).

## TL;DR — the top 3 misalignments

1. **The toolkit is discoverable by NAME only, not by USE.** `my.ui` / `my.canvas`
   render as `(no public fns indexed yet — query by name)` and `my.data` shows
   schemas with ZERO fns in the live store — so the worked examples, signatures,
   and the `rows → group-sum → max-by` composition chain the agent needs are NOT
   in the always-on context. The live-tile block NAMES the three nses (good),
   but a cold agent cannot see how to call them. This is the generalized form of
   the #42 "my.data 0× when signature-trimmed" regression — and `my.ui`/`my.canvas`
   are WORSE than `my.data` (empty vs schema-only).

2. **The live skills-catalog is stale vs the source skills.** The catalog the
   agent reads still describes `ui-canvas` as "…and the honest read-only
   scope (no interactive buttons yet)" — the OLD frontmatter — while the
   `seon-skills/ui-canvas/SKILL.md` FILE is fully updated (teaches `my.ui`
   static / `my.canvas` INTERACTIVE buttons-WORK / `my.data`). And the new
   `data-modeling` skill is ABSENT from the catalog entirely. Both are seed-lag:
   the DB `:my.skills/*` rows predate this session's file edits.

3. **Canvas-first is not in the stable `system-text`.** The byte-stable system
   block teaches "TELL YOUR HUMAN with `(message/user …)`" as THE way to inform
   the human, with no mention of show-don't-tell. Canvas-primacy ("THIS canvas
   is your PRIMARY surface… messages are backup narration that scrolls away")
   lives ONLY in the volatile `live-tile` block + the off-by-default
   `ui-canvas` skill. The doctrine and the stable teaching pull in opposite
   directions on emphasis.

**Is the toolkit discoverable from always-on context today? NO — only its
names.** A fresh agent sees `my.ui` / `my.canvas` / `my.data` named in the
live-tile block (with INTERACTIVE called out) but to actually USE them must
either load the `ui-canvas` skill (OFF by default — `default-load [:repl]`)
or grep `src/my/*.cljs`. The namespaces block, which is where worked code should
land, shows them empty.

## Live evidence (read-only, observed on pod 7890)

- Full context for `ETF-2606282057`: **13813 tok** / 55423 chars; `system-text`
  alone **3114 tok**. Always-on blocks present: `skills-catalog`, `repl` (loaded
  skill body), `namespaces`, `live-tile`, `warnings`, `inventory`, `transcript`.
- Indexed fns per toolkit ns in the live store:
  `{my.data [] my.ui [] my.canvas [] my.kb [<13 fns>]}` — only `my.kb` has its
  worked example indexed.
- `config/system.edn` `:seon.config/default-load [:repl]` — only the repl skill
  body is always-on; the toolkit cookbook (`ui-canvas`) is opt-in.
- `src/seon/client.cljs` requires `[my.kb] [my.kb.shared] [my.skills]` — NOT
  `my.data` / `my.ui` / `my.canvas`, so they are not pulled into the build/index by
  the boot path (only `my.kb` is the seeded worked example).
- `seon.agent.ctx.namespaces/canonical-full-my-ns` = `#{:my.kb :my.data}` — even
  when indexed, `my.ui` / `my.canvas` only ever render signature-trimmed.
- Live-tile block DOES name the toolkit correctly (the new reality):
  `my.ui — dual-render … / my.canvas — INTERACTIVE button/input … (buttons WORK) /
  my.data — sum-by/max-by/group-sum`. So the NAMES + roles are coherent.

## Misalignment table

| # | Surface (lane) | Issue | New reality it should match | Fix | Lane |
|---|----------------|-------|------------------------------|-----|------|
| 1 | `namespaces` block | `my.ui`/`my.canvas` render `(no public fns indexed yet)`; `my.data` shows schemas only — toolkit usage NOT in always-on ctx | `toolkit.md` target: the `my.*` toolkit is ONE seeded, indexed, rendered-full-every-turn definition | (a) `client.cljs` require `my.data`/`my.ui`/`my.canvas` so they build + index; (b) add `:my.ui`/`:my.canvas` to `canonical-full-my-ns` (its own docstring already anticipates this) | Core |
| 2 | `skills-catalog` (live DB rows) | `ui-canvas` desc stale ("no interactive buttons yet"; omits the toolkit); `data-modeling` skill missing entirely | `seon-skills/ui-canvas/SKILL.md` + `data-modeling/SKILL.md` files are current (interactive controls, full toolkit) | re-seed skills (`bin/seon cluster reset default`) so DB `:my.skills/*` rows match files; verify both appear | Core/op |
| 3 | `system-text` messaging teaching | "TELL YOUR HUMAN with `(message/user …)`" is the only taught channel; canvas-first absent from the stable prefix | live-tile block: "THIS canvas is your PRIMARY surface… messages are backup"; `ui-canvas` = show-don't-tell | add one line to `system-text` (THE RENDERING SYSTEM / messaging) establishing canvas-first + pointing at the live-tile block | Core |
| 4 | `system-text` lines ~1008/1028 | "every indexed **kind**" / "lists every stored **KIND** + its attrs" | inventory block reframed post-purge: "One line per attribute NAMESPACE", "entities have no kind" | reword KIND → "attribute namespace" / "what's stored" to match the inventory it describes | Core |
| 5 | default skill loadout | toolkit cookbook (`ui-canvas`) + `data-modeling` are opt-in (`default-load [:repl]`) | session made tiles the primary surface + toolkit central | consider adding `:ui-canvas` (and/or `:data-modeling`) to a role's `default-load`, or accept opt-in but guarantee #1 so the namespaces block carries the worked code | U/Core |

Not a bug (correctly coherent with the purge, verified):

- `inventory` block + `seon.db`/`inventory.cljs` source: "entities have no kind",
  "One line per attribute NAMESPACE" — fully attribute-presence. The `data-oriented-clojure`
  and `data-modeling` skill bodies teach no-kinds explicitly. No agent-facing
  surface teaches an entity-kind pattern. (Only residual is the `system-text`
  wording in #4.)
- `repl` skill body, transcript framing, result-vars, errors-as-values: current.
- No stale `/world` reference in agent-facing context/skills.
- "chars" appears only in display-clip guardrail internals (truncation markers),
  not as a size reported to the agent — tokens are used for reporting.
- `namespaces.cljs` is mid-#42 (Core active) — audited as in flux, not a bug.

## Top contradictions / staleness

1. **Catalog vs file** (seed lag) — `ui-canvas` live desc ≠ file desc;
   `data-modeling` indexed nowhere. Whole class: any skill edited this session is
   stale in the live catalog until a re-seed. [#2]
2. **"KIND" in system-text vs attribute-presence inventory** — the purge landed
   in the inventory + db source but not in the system block that describes them. [#4]
3. **message-first (system-text) vs canvas-first (live-tile + skill)** — soft
   emphasis contradiction; the stable prefix never states show-don't-tell. [#3]

## Prioritized fix list

- **P0 Core — make the toolkit always-on usable [#1].** `client.cljs` require
  `my.data`/`my.ui`/`my.canvas`; add `:my.ui`/`:my.canvas` to `canonical-full-my-ns`.
  This is THE fix for the (generalized) #42 adoption regression: the worked
  examples land in the namespaces block instead of `(no public fns indexed yet)`.
- **P0 op — `bin/seon cluster reset default` [#2].** Re-seeds skill rows (clears
  stale `ui-canvas` desc, surfaces `data-modeling`) AND re-indexes the
  toolkit fns. Needed to actually observe the P0 Core fix live.
- **P1 Core — establish canvas-first in `system-text` [#3].** One line in the
  stable prefix so show-don't-tell isn't only in the volatile block.
- **P2 Core — reword "KIND" → "attribute namespace" in `system-text` [#4].**
- **P3 U/Core — reconsider `default-load` [#5].** Add the toolkit cookbook to a
  role loadout, or rely on P0 making the namespaces block self-teaching.

## Lane tags (precise)

- **U** (skill content I own): the `seon-skills/ui-canvas` + `data-modeling`
  FILES are already coherent — no edit needed; the staleness is in the seeded DB
  copy (Core/op re-seed). `live_tile.cljs` block content is coherent (names the
  toolkit, canvas-first). No U-lane edits identified beyond confirming the files.
- **Core** (`system-text` / `namespaces` / `client.cljs` / `canonical-full-my-ns`
  / inventory): #1, #3, #4 are Core edits; #2/#5 are Core seed/config + operational.
