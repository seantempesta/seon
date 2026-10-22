# Render: what a person or a model reads

Every view, the model's context included, is a render: the one value printer (`seon.print`), or a render function declared on the value's schema (`:seon.render/html` for the page, `:seon.render/ai` for the context). Nothing else shapes output (owner, 2026-10-21: no clipping functions).

## No clipping

A cut never drops silently. It says what it omitted and how to read the rest. Storage keeps the exact value (an error's message, a running form, a provider's HTTP body travel whole); renders budget it. There is no `clip`, no `subs` for display, no `take n` without its count, and no stylesheet clip (`text-overflow: ellipsis`, `line-clamp`, `overflow: hidden` on text): the render's budget is the clamp.


## Prose

`seon.print/prose` shows text as itself (unquoted) under a token budget, sized as the printer sizes (3.2 characters a token). It fits: the text. It does not: the longest prefix the budget holds, ended after a line break or else a space, then the cut, `…+N chars in M more lines`. Given a source form that answers the whole text, the cut names `(subs source offset)`, which reads exactly the rest (with `:one-line?`, the offset is still the source's). On the page, `seon.render.ui/prose` links the cut to the page that shows the whole, or carries the whole in the cut's title; a printed value's cut names its result handle (`result/h3 at its REPL`).


## Bounded lists

A list shown in part is one call, `seon.print/bounded` (items, its bound's config key, where, and optionally the total): it answers the items shown and the line `seon.print/more` writes, `+N more: where`, where being the form or page that holds them all, e.g. `+7 more: ended, all in (seon.task/attempts (deref (:conn sys)))`. Every bound is a `:seon.config.render.list/*` key in `resources/seon/config/default.edn` (rows, newest, brief, top), never a literal. Counts are of the whole, not of what a view kept (root's defects count every one; a card's evaluations count by their ids). Root's context groups what repeats: blocked tasks by what blocks them (`seon.render.ui/waiting-on`, the page's own), contract gaps as one list of names.


## Tiles are data; the context reads the page's derivations

Every dashboard tile is `seon.render.ui/metric-tile` over a map (`:kicker`, `:sub` or `:pill`, `:big` and `:unit`, `:series` and `:tone`, a `:body`, `:rows` of `[dt dd title class]`, `:explain`), so a new tile is a map. The writer, fork server and main are `seon.render.ui/load-tiles`, which root's context (`seon.render.ai`) prints as one line each; its newest events are the stream's own words (`seon.render.ui/event-detail`), a cost verdict's lines are the page's (`seon.render.ui/verdict-lines`), and how an agent is reads `seon.render.ui/shown-status` and `seon.render.ui/ended-as`. A proc's defect line is `seon.render.ui/proc-health`, once: the health tile lists every proc by it, the effect owners among them. One-line text in a row or the context is `seon.print/line`.


## Health is what flows

Nothing is pinged and nothing runs on a beat (owner, 2026-10-21). A render pass happens on an event, an api answer or a tab's request. A proc is healthy unless a problem was recorded of it: the health tile lists each declared proc with the defects root recorded of it. Series are one point per change (transactions per writer batch, ms per fork, datoms per main commit). A value only a ping carried is its owner's to emit when it changes; until then the tile says it is not reported. `bin/check idle` proves an idle root with a tab open steps nothing, and that a spawn and a commit still reach the tab.


## A context read is shown once

An outside agent reads its current-state block through root's entrance (`{:op :context}`, recorded with origin `:context`). The agent's page shows the text it last read, made again from that turn's basis (`seon.context/recorded`, `seon.context/render`: a turn records the commit it read and the observations no db holds, never the text); every other view of that read (a card, a history row, the stream) refers to it (`read its current-state block · ≈N tokens`), never repeats it. The agent's own turn and the door print the block the same way (`seon.context/messages`, 16,000 tokens).

