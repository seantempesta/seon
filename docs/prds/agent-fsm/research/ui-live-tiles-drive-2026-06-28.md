---
type: research
status: draft
tags: [research, agent]
---

# ui-canvas live drive — does the skill move an agent from "telling" to "showing"?

Live DeepSeek drive on the fresh default pod (7890), 2026-06-28. One child agent
(`AIC-2606281626`, parent `root`) given the human message:

> "Show me, at a glance, what you're able to do — explore your own capabilities
> and present a clear view I can read without scrolling."

No coaching, no naming the skill. Driven to completion (run closed via
`lifecycle/complete`). Evidence below is observed from the live store — eval log,
the `:seon.render.live-canvas/content` datom, `render-agent-tile` output, the
rendered transcript block.

## TL;DR

- **The agent SHOWED, not just told.** It defined a real tile fn
  `my.agent.AIC-2606281626/capabilities-tile`, wired it onto
  `:seon.render.live-canvas/content` (eval #4), and `render-agent-tile` produces
  valid hiccup + an `:seon.render/ai` twin. It ALSO sent a rich markdown message
  pointing at the tile ("Your canvas shows a live capabilities card. What would
  you like me to do first?"). This is exactly the tile+message pattern the owner
  wanted — the "only messaging" failure mode did **not** happen.
- **But the skill itself was never loaded.** Loaded skills = `[:repl]` only. The
  agent wired the tile from the **always-on `:live-tile` context block**, not from
  `(my.skills/load :ui-canvas)`. The skill was *discoverable* (`:ui-canvas ○`
  in its catalog, referenced in its narration + ai-twin) but never opened. So this
  drive validates the always-on block + the tile mechanism — it does **not** prove
  the `ui-canvas` SKILL added marginal value here.
- **Two failure costs, both real:** (1) the agent guessed **non-safelisted CSS**
  for its status dots (`inline-block w-2 h-2 rounded-full bg-signal`,
  `border-border-100`) — confirmed absent from `input.css` `@source inline`, so the
  dots render as zero-size invisible spans; the skill's safelist section would have
  fixed this *if loaded*. (2) The agent **burned ~100 evals reverse-engineering how
  to message its human** — it read `src/seon/agent/message.cljs` via Node `fs`
  before discovering `(require '[seon.agent.message :as message])` + `message/user`.
- **Both shipped fixes hold:** small stored values render WHOLE (transact/todo
  envelopes render complete; only large values collapse). Empty/orphan evals are
  silent (no `✗ READ ERROR`). Genuine prose-as-code still (correctly) read-errors —
  the agent produced 12 of those by writing reasoning into the code field.

## Did it see / load / use the skill?

- **Saw it:** the always-on skills-catalog block rendered
  `; - :ui-canvas  ○ — Show your human a live VIEW, don't just message them…`.
  Its eval-#0 narration: *"The skills catalog is already rendered above — I have
  :repl loaded and four others available."* The tile's ai-twin lists all five
  including `ui-canvas`.
- **Did NOT load it:** `(my.skills/list)` for this agent →
  loaded = `[:repl]`. No `(my.skills/load :ui-canvas)` ever ran. `:repl` is the
  default-seeded skill, not one the agent chose.
- **Used the tile mechanism anyway:** from the always-on `:live-tile` block, which
  carries copy-paste examples. So the agent learned the ONE move (`wire
  :seon.render.live-canvas/content`) without the deep skill — good for the mechanism,
  but it means the skill's richer guidance (safelist, faces, `seon.render/block`)
  never reached the agent.

## The tile it built (the win)

`capabilities-tile` is a genuine derive-don't-store fn — it calls `(skills/list)`
and computes `loaded-count` live every render, returns both
`:seon.render/hiccup` and `:seon.render/ai`. `render-agent-tile` output:

```
ai => "Your human sees a capabilities card: 7 bullet points covering database,
       knowledge storage, on-demand skills (1/5 loaded), todo planning, live
       tiles, messaging, and Node interop. Skills available: clojurescript,
       data-oriented-clojure, datahike, repl, ui-canvas."
hiccup => [:div {:class "p-3 flex flex-col gap-3"}
            [:h2 {:class "text-sm font-bold text-signal"} "What I Can Do"] …]
```

Quality: clean semantic structure, valid safelisted layout/text classes
(`flex flex-col gap-3`, `text-sm font-bold text-signal`, `text-text-200`,
`text-2xs`), a live `loaded/total` count, a footer telling the human how to load
a skill. Single face (no compact/expanded) — fine for this content.

**The one styling defect** — the per-row status dot:
`[:span {:class "inline-block w-2 h-2 rounded-full bg-signal"}]`. Checked against
`resources/public/css/input.css` `@source inline(...)`: `w-2`, `h-2`,
`rounded-full`, `bg-signal`, `border-border-100` are all **absent** from the
safelist (only `w-full h-full`, `rounded rounded-md`, `bg-base-*` exist). Tailwind
generates nothing for them → the dots are invisible zero-size spans. The human
sees the seven text lines but no bullet markers. This is precisely the trap the
skill's safelist section warns about — but the agent never loaded that section.

## Where it struggled (quote the evals)

1. **Messaging the human — ~100 evals of reverse-engineering.** The agent's
   reflex `(message/user …)` failed: `"\`message/user\` is not defined"`. It then
   tried `(complete …)`, `(require 'seon.message)`, `seon.agent.message.interface`,
   `msg/send-to-user` — all dead ends — and resorted to
   `(.readFileSync (js/require "fs") "src/seon/agent/message.cljs")` to read the
   source and discover the verb. It finally landed `(require '[seon.agent.message
   :as message])` (eval #136) → `(message/user "## What I Can Do…")` (eval #137).
   The message-to-human verb is **not aliased in the agent's home ns and not
   surfaced in-context** — a serious, skill-independent UX gap.

2. **Prose-as-code → 12 `✗ READ ERROR`s.** The agent repeatedly put reasoning into
   the eval field: `(it's referenced in my require)`, `(returns a Promise)`,
   `(they're in the transcript above)`, a full paragraph `"(after \`;\`) is visible
   to my human. … Let me check if there's a \`seon.message\` namespace…"`. Each
   read-errors. Calm/helpful individually, but the transcript carries 12 of them
   (34 `✗` marks total). This is a segmentation problem (agent not delimiting prose
   vs forms), adjacent to but not the same as the orphan-delimiter fix.

3. **`db/store-inventory` guessed wrong first** (eval #0) — minor; it adapted.

## Did the two fixes hold?

- **Result truncation (small values render WHOLE): HOLDS.** Transcript shows full
  envelopes, e.g. `;=> {:seon.db/ok? true, :seon.db/tempids {}, :seon.db/tx
  536870956, :seon.db/tx-count 8, :seon.db/added 8, :seon.db/retracted 0}`, full
  `{:seon.agent.todo/ok? true, :seon.agent.todo/id "lxg-2606281626"}`, the agent's
  own entity pulls. Only large values collapse (`{:seon.db/kinds […{…1 keys}]}`).
  One truncation marker in a 13K-token transcript. The agent could read its own
  small data back.
- **Error-wall on empty/orphan evals: HOLDS (for empty/orphan).** Empty-source
  evals (#21, #27) are `ok? true`, silent — no `✗ READ ERROR`. A genuine malformed
  token `\`:` and prose paragraphs DO still produce individual `✗ READ ERROR`
  lines — correct behavior (they really didn't parse), not a regression. No
  *spam wall* of identical errors observed.

## Net verdict

**Mechanism: PASS. Skill attribution: UNPROVEN.** The agent moved from telling to
showing — it led with a live tile AND messaged — which is the owner's win
condition. But it did so from the **always-on `:live-tile` block**, never loading
`ui-canvas`. So this drive proves the always-on surface + tile wiring work
end-to-end; it does not yet prove the *skill* changes behavior, because the agent
got enough from the always-on block to never feel it needed the deeper skill. The
clearest place the loaded skill *would* have helped — the safelist — is exactly
where the agent silently degraded (invisible CSS dots).

### Skill-improvement suggestions (the skill is yours to refine)

1. **Hoist the safelist warning into the always-on `:live-tile` block (or shrink
   the skill's role to "the rest").** Agents reflexively reach for
   `w-2 h-2 rounded-full bg-*` for status dots — three independent live drives now.
   Either expand the safelist to include a dot primitive, or make the `● ` unicode
   dot the loud, first example for status (the skill mentions `● running` only in
   passing). The agent will not load the skill before guessing CSS, so the dot
   guidance must live where it already looks.
2. **The skill can't fix discovery if it's never loaded.** Consider whether the
   catalog line should nudge "load me *before* you author your first tile" — or
   accept that the always-on block is the real teacher and the skill is the
   reference. Right now the skill's deep value (faces, `seon.render/block`,
   safelist) is stranded behind a load the agent skips.

### Unbuilt capability the agent clearly wanted (for the owner)

- **A discoverable "message your human" verb.** Not interactivity this time — the
  agent never hunted for an onClick. It hunted, for ~100 evals and a Node-`fs`
  source read, for **how to send a message to its human.** `message/user` is the
  right verb but it's not aliased in the home ns and not shown in-context;
  `(require '[seon.agent.message :as message])` is undiscoverable without reading
  source. This is the single biggest eval-burn in the drive and is orthogonal to
  tiles — surfacing the messaging verb (alias in home ns, or a line in the
  namespaces/inventory block) would save the most agent effort.
