# Render: what a person or a model reads

Every view, the model's context included, is a render: the one value printer (`seon.print`), or a render function declared on the value's schema (`:seon.render/html` for the page, `:seon.render/ai` for the context). Nothing else shapes output (owner, 2026-10-21: no clipping functions).

## No clipping

A cut never drops silently. It says what it omitted and how to read the rest. Storage keeps the exact value (an error's message, a running form, a provider's HTTP body travel whole); renders budget it. There is no `clip`, no `subs` for display, no `take n` without its count, and no stylesheet clip (`text-overflow: ellipsis`, `line-clamp`, `overflow: hidden` on text): the render's budget is the clamp.


## Prose, and the one string cutter

Text is cut one way, inside a value or shown as itself: the printer's string cutter (`seon.print.select/break-at`) ends the shown part after a line break in its room's second half, else after a space in its final quarter, else at the room, and the cut says `…+N chars in M more lines` and the form that reads exactly the rest (`seon.print.emit/cut-words`). A string in a printed value reads `"its prefix"…+7279 chars in 88 more lines (subs result/h3 19523)`. `seon.print/prose` shows text as itself (unquoted) under a token budget, sized as the printer sizes (3.2 characters a token, `seon.print.select/estimate`, the one sizer: the history budget and the scripted model's usage count with it too): whole when it fits, else that same cut; given a source form that answers the whole text, the cut names `(subs source offset)` (with `:one-line?`, the offset is still the source's). On the page, `seon.render.ui/prose` links the cut to the page that shows the whole, or carries the whole in the cut's title; a printed value's cut names its result handle (`result/h3 at its REPL`).

A cut is read as its text, where it stands: the printer answers no list of its cuts. Decided by blind scoring on production-shaped values (2026-10-23, `notes/printer-eval/README.md`): Haiku readers answered every question about a cut value from the inline cut alone (100%, 60 answers), and ranked it first in 30 of 30 three-way comparisons against the same text with a closing "left out" list (repeats every cut, up to 2.9x the tokens) or with footnote markers; prose's cut words beat the bare `…+N` in 18 of 22 pairs (the unit and the line count tell a reader how much it is missing), and prose's break beat a cut at the last line end, which can spend most of the room on nothing (47 of 256 tokens shown in one case).


## Bounded lists

A list shown in part is one call, `seon.print/bounded` (items, its bound's config key, where, and optionally the total): it answers the items shown and the line `seon.print/more` writes, `+N more: where`, where being the form or page that holds them all, e.g. `+7 more: ended, all in (seon.task/attempts (deref (:conn sys)))`. Every bound is a `:seon.config.render.list/*` key in `resources/seon/config/default.edn` (rows, newest, brief, top), never a literal. Counts are of the whole, not of what a view kept (root's defects count every one; a card's evaluations count by their ids). Root's context groups what repeats: blocked tasks by what blocks them (`seon.render.ui/waiting-on`, the page's own), contract gaps as one list of names.


A render's budget, when its env names none, is config too: `:seon.config.render/value-budget` for a value, `:seon.config.render/root-context-budget` for root's context (`seon.render.ai/system`) and `:seon.config.render/history-row-budget` for each evaluation in an agent's history.

## Tiles are data; the context reads the page's derivations

Every dashboard tile is `seon.render.ui/metric-tile` over a map (`:kicker`, `:sub` or `:pill`, `:big` and `:unit`, `:series` and `:tone`, a `:body`, `:rows` of `[dt dd title class]`, `:explain`), so a new tile is a map. The writer, fork server and main are `seon.render.ui/load-tiles`, which root's context (`seon.render.ai`) prints as one line each; its newest events are the stream's own words (`seon.render.ui/event-detail`), a cost verdict's lines are the agent's and the page's (`seon.cost/verdict-lines`), and how an agent is reads `seon.render.ui/shown-status` and `seon.render.ui/ended-as` (an agent's card and its stream's stop events alike). A lifecycle is declared once, on its enum's schema, and read through one reader, `seon.contract/properties` (an enum's members, in order: `seon.contract/enum-values`): `:seon.agent/status` carries its live, stopped and gone sets (`seon.root/gone-statuses`, read by root, the web's all=>, the server and the check harness; `seon.render.ui/live`; the swarm's `seon.render.ui/status-order`), `:seon.attempt/status` its finished, judged and failed sets (`seon.reconcile/finished`; a death in `seon.problems/report`), and `:seon.task/state` a task's observed states with each pill's title (`seon.render.ui/state-title`); a task's state is derived once, from the reconciler's index (`seon.reconcile/task-state`), and every task row reads it (`seon.render.proc/task-rows`). A new status is one enum entry. Not this: a status set typed out at each site that asks. A proc's defect line is `seon.render.ui/proc-health`, once: the health tile lists every proc by it, the effect owners among them. One-line text in a row or the context is `seon.print/line`. A test run's words are `seon.render.ui/run-words` and its redness `seon.proofs/red?`; a merge's result is `seon.render.ui/merge-words` (the card's pill and the stream's row); an evaluation's arrow and value are one render for a card's row and a history row, its text a reply item's `:printed` or a record's `:shown` (an error's too: `seon.repl/record-tx`). A refusal or failure shown reads `:seon.error/message` of the one error map, never a second `{:error "text"}` shape. Root's view numbers (p50s, transactions per commit) are `seon.root/view-numbers`, read by `seon.root/state` and the render proc alike. A root function called from the page is recorded on main by the render proc when root answers it (origin `:ui`), with that answer.


## The page is units

A view's layout (`seon.render.ui/system-page`, `seon.render.ui/agent-page`) answers its skeleton, with a slot where each part goes, and its units: each part's id, its input and its render. The render proc renders a unit again only when its input changed and sends only bytes that changed (`seon.render.proc/step-units`); a page or keyframe is the skeleton with every slot filled from what it retained (`seon.render.proc/compose`). A unit with a `:container` is added to it when it is new (appended, or prepended for a stream's rows, newest first), replaced by its id when it changes, and removed when the layout no longer has it (`seon.render.proc/finish-patch`): an agent's card, an event of root's stream (a stop row says how the attempt ended once main does: its input changed), an evaluation of an agent's history (written once, so rendered once, and sent as its own row).


## Health is what flows

Nothing is pinged and nothing runs on a beat (owner, 2026-10-21). A render pass happens on an event, an api answer or a tab's request. What is wrong has one producer, what is recorded: root's context and the problems banner (`seon.render.ai/problems`) read root's defects and main's recorded problems (`seon.problems/report`), never re-derive them from the live view; root records an agent's death (`seon.root/end-agent`), and a red run, refused merge or refused spawn as it folds it into the agent's view (`seon.root/set-agent`). A proc is healthy unless a problem was recorded of it: the health tile lists each declared proc with the defects root recorded of it. Series are one point per change (transactions per writer batch, ms per fork, datoms per main commit). A value only a ping carried is its owner's to emit when it changes; until then the tile says it is not reported. `bin/check idle` proves an idle root with a tab open steps nothing, and that a spawn and a commit still reach the tab.


## A context read is shown once

An outside agent reads its current-state block through root's server (`{:op :context}`, recorded with origin `:context`). The agent's page shows the text it last read, made again from that turn's basis (`seon.context/recorded`, `seon.context/render`: a turn records the commit it read and the observations no db holds, never the text); every other view of that read (a card, a history row, the stream) refers to it (`read its current-state block · ≈N tokens`), never repeats it. The agent's own turn and the server print the block the same way (`seon.context/messages`, `seon.context/reader-text`: the attempt sections, then the turn sections, each under 16,000 tokens).

## The model's context: a cached prefix, then the turn

A model request sends what does not change first, so the provider serves it from its prefix cache, and sends nothing twice.

```seon.context/messages``` makes every request in one order:
1. **The help page** ([agent](agent.md)) as the system message: the same bytes for every agent and every turn (`seon.context/help-text`).
2. **The attempt sections** (`seon.context/attempt-keys`: the task and its plan, the references, the helpers it does not name, the cited sections, the evidence), made from the attempt's first turn's basis on the commit it read, so they stay byte-identical while the attempt works even when the program they cite changes.
3. **The scrollback**: each earlier turn's reply, its eval calls and each call's answer as a tool message, whole turns within `:seon.config.render/history-budget`, the newest kept.
4. **The turn sections**: everything else in the block (the turn, the proofs, refused, the test run, the last reply, unmerged entries, and `:will-merge`, every change the branch's merge will carry to main, from `seon.program/divergence` against the agent's `:base`, its branch's merge base with main (by content, as `seon.program/changed-vars`), never main's head, which shows main's later changes as the branch's: owner, D4), the last evaluations while no earlier turn shows them, and how many earlier turns the history budget left out.

So each request starts with the whole of the one before but its last message (`seon.context-test/the-prefix-stays-byte-identical-across-turns`), and the plan, its references and its sections go out once a request (`seon.context-test/each-section-is-sent-once-per-request`). Before (2026-10-22), the block went out twice a request, in the first turn's message and again in the current one, and the overflow note edited the first message, breaking the prefix every turn after overflow. v1 measured the same split live: stable attempt sections of about 8.5k tokens, turn sections of about 0.75k, 85-91% cache hits. Measured 2026-10-22 through the server on three open tasks (printer estimate, 3.2 characters a token): the help page is 2,132 tokens, the same for every agent; the attempt sections 1,639-1,887 (the references 2.1-2.4 KB, were 8.4-10.0 KB of whole source); the turn sections 45-76 before any proof has run. A first request is 3.9k tokens (was 4.5k, with 24% of it shared by every agent, now 54%); each later turn adds its scrollback and about 60 tokens of turn sections, where the old request added the whole 3.2-3.6k block again, so with 2k tokens of scrollback a turn sends about 2.1k uncached tokens (was 5.4k) and the cached prefix is 65% of the second request and 79% of the fourth (was 45% and 61%). What the provider served from its cache is recorded on each turn (`:seon.turn/cache-hit-tokens`, `:seon.turn/cache-miss-tokens`, by `seon.turn/usage`), absent when it did not say.


## One printer

There is one value printer, `seon.print`, and nothing is cut before it (owner, 2026-10-22: "Do not create more than one value printer and no clipping happens before then"). A value a person or a model reads is its schema's declared render (`seon.render`) or the printer's text; a value that is not text reaching the page's serializer (`seon.web.html/html`), a one-line row (`seon.print/line`) or prose (`seon.render.ui/prose`) is printed by the printer, never `str`. The REPL answers every value through `seon.render/printed`: a value whose producer names its schema (`seon.task/task`, `seon.problems/report`: the value's metadata `:seon.render/schema`) reads by its declared AI render, the same text root's context and the page are made from, and ends with the form that shows the whole value as data, `(with-meta result/h3 nil)`; a map that merely holds a key (a partial pull, tx data) prints as the data it is. An evaluation's error reads by the error map's render (its message, fix, variant and where). The page's HTML renders of the same schemas show the same facts.

`seon.render-test/one-printer-and-no-cut-before-it` reads the compiler's facts (`:form/refs` of src imported, `seon.program/live-forms`; each var's form, and each method's): every var outside `seon.print` that references `pr-str`, `prn-str`, `print-str`, `with-out-str`, `pr`, `prn`, `printf` or `clojure.pprint`'s `pprint`, `pp`, `write` or `cl-format` is named below; every var of a display namespace (`my.*`, `seon.render.*`, `seon.web*`, `seon.context`, `seon.mcp`, `seon.server`, `seon.cost`, `seon.repl`, `seon.agent`, `seon.problems`) that references `subs`, `take`, `take-last` or `drop-last` is named below; and every var outside them that calls the printer with a budget (`seon.print/text`, `line`, `prose`: a cut where a value is made) is named below. An unnamed one is red, and so is a name here that no longer does it. (`butlast` is left out: destructuring's expansion references it in every function. Not covered: `str`, `print`, `println` and `format` on data, and `first` or `drop` on rows shown, which the compiler's facts cannot tell from their use on data; review reads for them.)

### Printing beside the printer: the wire

Text for the wire, storage, a digest, a file, a URL, a command line or code to evaluate is `seon.edn/encode` (EDN that reads back, whole: no print limits), and only that var prints beside the printer:

- `seon.edn/encode`: EDN for storage and the wire, whole.

An agent's namespace refers `clojure.pprint`'s `pp` and `pprint` (`seon.agent/make-ns!`), as a plain Clojure REPL does (AGENTS §4, "what would a plain REPL do"); what they write reaches the model as the evaluation's `:out`, budgeted as prose. A check's console lines are the printer's whole text (`seon.check/shown`).

### Cut where a value is made

- `seon.check/shown`: names a budget only to print whole (the value's `:seon.print/full` metadata).
- `seon.contract/problems`, `seon.task/file-declarations`: a refused value (a function's argument, a task file's entry) may be infinite, lazy or a host object, and travels on the wire as text, so its error keeps the printer's cut, which says what it omitted; the live value stays with the caller.

### Cuts in a display namespace that are not cuts of output

- `seon.render.ui/kname`, `seon.render.ui/dom-id`: a keyword's text without its colon.
- `my.agent/file-source`, `seon.repl/form-src`: a form's own text out of its file or its reply (`subs` at a reader's offsets).
- `my.root/task-files`: a task file's namespace from its file name.
- `seon.render.ui/utc-offset-ms`: parsing `:seon.config.web/utc-offset`.
- `seon.web/handler`, `seon.server/mcp-reply`: parsing a request's path and a tool's name.
- `seon.context/near`: the whole line a repaired delimiter is on, the printer budgets it.
- `seon.render.proc/main-info`, `seon.render.proc/system-value`: a sparkline's points, the newest `:seon.config.render.list/series`.
- `seon.render.proc/pass`: the stream keeps the newest `:seon.config.render.list/stream` events; its header (`seon.render.ui/events-count`) says how many older it did not keep.
- `seon.render.proc/eval-records`: an agent's page shows its newest `:seon.config.render.list/history` evaluations; `seon.render.ui/history-more` says how many more its branch records.
- `seon.render.ai/section-costly`: the newest `:seon.config.render.list/top` evaluations of each agent its costliest functions are summed from.
