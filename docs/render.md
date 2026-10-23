# Render: what a person or a model reads

Every view, the model's context included, is a render: the one value printer (`seon.print`), or a render function declared on the value's schema (``:seon.render/html`` for the page, ``:seon.render/ai`` for the context). Nothing else shapes output, and nothing clips.

## No clipping

A cut never drops silently. It says what it omitted and how to read the rest. Storage keeps the exact value (an error's message, a running form, a provider's HTTP body travel whole); renders budget it. There is no `clip`, no `subs` for display, no `take n` without its count, and no stylesheet clip (`text-overflow: ellipsis`, `line-clamp`, `overflow: hidden` on text): the render's budget is the clamp.


## Prose, and the one string cutter

Text is cut one way, inside a value or shown as itself: the printer's string cutter (`seon.print.select/break-at`) ends the shown part after a line break in its room's second half, else after a space in its final quarter, else at the room, and the cut says `…+N chars in M more lines` and the form that reads exactly the rest (`seon.print.emit/cut-words`). A string in a printed value reads `"its prefix"…+7279 chars in 88 more lines (subs result/h3 19523)`. `seon.print/prose` shows text as itself (unquoted) under a token budget, sized as the printer sizes (`:seon.config.ai/chars-per-token-prior` characters a token, `seon.print.select/estimate`, the one sizer: the history budget and the scripted model's usage count with it too): whole when it fits, else that same cut; given a source form that answers the whole text, the cut names `(subs source offset)` (with `:one-line?`, the offset is still the source's). On the page, `seon.render.ui/prose` links the cut to the page that shows the whole, or carries the whole in the cut's title; a printed value's cut names its result handle (`result/h3 at its REPL`).

A cut is read as its text, where it stands: the printer answers no list of its cuts. Decided by blind scoring on production-shaped values (`notes/printer-eval/README.md`): Haiku readers answered every question about a cut value from the inline cut alone (100%, 60 answers), and ranked it first in 30 of 30 three-way comparisons against the same text with a closing "left out" list (repeats every cut, up to 2.9x the tokens) or with footnote markers; prose's cut words beat the bare `…+N` in 18 of 22 pairs (the unit and the line count tell a reader how much it is missing), and prose's break beat a cut at the last line end, which can spend most of the room on nothing (47 of 256 tokens shown in one case).


## Bounded lists

A list shown in part is one call, `seon.print/bounded` (items, its bound's config key, where, and optionally the total): it answers the items shown and the line `seon.print/more` writes, `+N more: where`, where being the form or page that holds them all, e.g. `+7 more: ended, all in (seon.task/runs (deref (:conn sys)))`. Every bound is a `:seon.config.render.list/*` key in `resources/seon/config/default.edn` (rows, newest, brief, top), never a literal. Counts are of the whole, not of what a view kept (root's defects count every one; a card's evaluations count by their ids). Root's context groups what repeats: blocked tasks by what blocks them (`seon.render.ui/waiting-on`, the page's own), contract gaps as one list of names.


A render's budget, when its env names none, is config too: `:seon.config.render/value-budget` for a value, `:seon.config.render/root-context-budget` for root's context (`seon.render.ai/system`) and `:seon.config.render/history-row-budget` for each evaluation in an agent's history.

## The frame and its units

A page is a layout (the declared render of `:seon.render/system` or `:seon.render/agent-page`) answering a skeleton with a slot per part, and `:seon.render/units`: each part's id, the exact value it reads (its input) and that input's schema (`:schema`), whose declared render shows it (`seon.render/html`). A part is declared on its value's schema like any other render: `:seon.render/swarm`, `:seon.render/work`, `:seon.render/procs-health`, `:seon.render/zoom-head`, … in `resources/seon/schemas/seon.render.edn`; a unit never names a render function. The render proc (`seon.render.proc`) renders a unit when its input changed and patches it when its bytes changed. Two units still name a render function, `events-list` and `history-list`, each around rows serialized ahead; jolt/printer-eval makes their rows ordinary units, and then a unit's `:render` goes.

The frame is the one part with no value behind it: the document (`seon.web/page-doc`), its head and scripts (`seon.render.ui/shell`, with the theme's and Esc's inline script) and the top bar (`seon.render.ui/topbar`). It is fixed hiccup, the same on every page, served once with the page; nothing in it changes with the system, so nothing patches it. A part that shows something the system holds is never added to the frame.

## Tiles are data; the context reads the page's derivations

Every dashboard tile is `seon.render.ui/metric-tile` over a map (`:kicker`, `:sub` or `:pill`, `:big` and `:unit`, `:series` and `:tone`, a `:body`, `:rows` of `[dt dd title class]`, `:explain`), so a new tile is a map. The writer, fork server and main are `seon.render.ui/load-tiles`, which root's context (`seon.render.ai`) prints as one line each. Each fact the page and the context both show has one producer they share:
- **A lifecycle is declared once, on its enum's schema,** and read through one reader, `seon.contract/properties` (an enum's members, in order: `seon.contract/enum-values`):
  - `:seon.agent/status` carries its live, stopped and gone sets (`seon.root/gone-statuses`, read by root, the web, the server and the check harness; `seon.render.ui/live`; `seon.render.ui/status-order`);
  - `:seon.run/status` its finished, judged and failed sets (`seon.reconcile/finished`; a death in `seon.problems/report`);
  - `:seon.task/state` a task's observed states with each pill's title (`seon.render.ui/state-title`). A task's state is derived once, from the reconciler's index (`seon.reconcile/task-state`), and every task row reads it (`seon.render.proc/task-rows`).

  A new status is one enum entry. Not this: a status set typed out at each site that asks.
- **How an agent is:** `seon.render.ui/shown-status` and `seon.render.ui/ended-as` (an agent's card and its stream's stop events alike); its newest events are the stream's own words (`seon.render.ui/event-detail`); a cost verdict's lines are `seon.cost/verdict-lines`.
- **A proc's defect line** is `seon.render.ui/proc-health`: the health tile lists every proc by it, the effect owners among them.
- **Runs, merges and evaluations:** a test run's words are `seon.render.ui/run-words` and its redness `seon.proofs/red?`; a merge's result is `seon.render.ui/merge-words`; an evaluation's arrow and value are one render for a card's row and a history row, its text a reply item's `:printed` or a record's `:shown` (an error's too: `seon.repl/record-tx`).
- **Errors:** an error or failure shown reads `:seon.error/message` of the one error map ([errors](errors.md#errors)), never a second `{:error "text"}` shape.
- **Numbers:** root's view numbers (p50s, transactions per commit) are `seon.root/view-numbers`, read by `seon.root/state` and the render proc alike.
- One-line text in a row or the context is `seon.print/line`. A root function called from the page is recorded on main by the render proc when root answers it (origin `:ui`), with that answer ([dashboard](dashboard.md)).


## The page is units

A view's layout (`seon.render.ui/system-page`, `seon.render.ui/agent-page`) answers its skeleton, with a slot where each part goes, and its units: each part's id, its input and its render. The render proc renders a unit again only when its input changed and sends only bytes that changed (`seon.render.proc/step-units`); a page or keyframe is the skeleton with every slot filled from what it retained (`seon.render.proc/compose`). A unit with a `:container` is added to it when it is new (appended, or prepended for a stream's rows, newest first), replaced by its id when it changes, and removed when the layout no longer has it (`seon.render.proc/finish-patch`): an agent's card, an event of root's stream (a stop row says how the run ended once main does: its input changed), an evaluation of an agent's history (written once, so rendered once, and sent as its own row).


## Health is what flows

Nothing is pinged and nothing runs on a beat. A render pass happens on an event, an api answer or a tab's request. What is wrong has one producer, what is recorded or derived from main's facts: root's context and the problems banner (`seon.render.ai/problems`) read root's defects and main's open problems (`seon.problems/health`: [health](health.md)), never re-derive them from the live view; root records an agent's death (`seon.root/end-agent`), and a red run, refused merge or refused spawn as it folds it into the agent's view (`seon.root/set-agent`). A proc is healthy unless a problem was recorded of it: the health tile lists each declared proc with the defects root recorded of it. Series are one point per change (transactions per writer batch, ms per fork, datoms per main commit). A value only a ping carried is its owner's to emit when it changes; until then the tile says it is not reported. `bin/check idle` proves an idle root with a tab open steps nothing, and that a spawn and a commit still reach the tab.


## A context read is shown once

An outside agent reads its current-state block through root's server (`{:op :context}`, recorded with origin `:context`). The agent's page shows the text it last read, made again from that turn's basis (`seon.context/recorded`, `seon.context/render`: a turn records the commit it read and the observations no db holds, never the text); every other view of that read (a card, a history row, the stream) refers to it (`read its current-state block · ≈N tokens`), never repeats it. The agent's own turn and the server print the block the same way (`seon.context/messages`, `seon.context/reader-text`: the run sections, then the turn sections, each under `:seon.config.render/context-budget` tokens).

## The model's context: a cached prefix, then the turn

A model request sends what does not change first, so the provider serves it from its prefix cache, and sends nothing twice.

```seon.context/messages``` makes every request in one order:
1. **The help page** ([agent](agent.md)) as the system message: the same bytes for every agent and every turn (`seon.context/help-text`).
2. **The run sections** (`seon.context/run-keys`: the task and its plan, the references, the helpers it does not name, the cited sections, the evidence), made from the run's first turn's basis on the commit it read, so they stay byte-identical while the run works even when the program they cite changes.
3. **The scrollback**: each earlier turn's reply, its eval calls and each call's answer as a tool message, whole turns within `:seon.config.render/history-budget`, the newest kept.
4. **The turn sections**: everything else in the block (the turn, the proofs, refused, the test run, the last reply, unmerged entries, and `:will-merge`, every change the branch's merge will carry to main, from `seon.program/divergence` against the agent's `:base`, its branch's merge base with main (by content, as `seon.program/changed-vars`), never main's head, which shows main's later changes as the branch's), the last evaluations while no earlier turn shows them, and how many earlier turns the history budget left out.

So each request starts with the whole of the one before but its last message (`seon.context-test/the-prefix-stays-byte-identical-across-turns`), and the plan, its references and its sections go out once a request (`seon.context-test/each-section-is-sent-once-per-request`). Not this: the block sent twice a request, or a note that edits an earlier message, which breaks the prefix for every later turn. v1 measured the same split live: stable run sections of about 8.5k tokens, turn sections of about 0.75k, 85-91% cache hits. Measured through the server on three open tasks (printer estimate, `:seon.config.ai/chars-per-token-prior`): the help page is 2,132 tokens, the same for every agent; the run sections 1,639-1,887 (the references 2.1-2.4 KB); the turn sections 45-76 before any proof has run. A first request is 3.9k tokens, 54% of it shared by every agent; each later turn adds its scrollback and about 60 tokens of turn sections, so with 2k tokens of scrollback a turn sends about 2.1k uncached tokens and the cached prefix is 65% of the second request and 79% of the fourth. What the provider served from its cache is recorded on each turn (`:seon.turn/cache-hit-tokens`, `:seon.turn/cache-miss-tokens`, by `seon.turn/usage`), absent when it did not say.

DeepSeek's prefix cache ([its guide](https://api-docs.deepseek.com/guides/kv_cache)) serves a request only from a stored prefix unit it fully matches. Units are made at the end of each request's input and output, at prefixes several requests share (found lazily: the third request hits what two earlier ones shared), and at fixed intervals inside a long input; the cache is best effort, takes seconds to build, and is dropped hours to days after its last use. Since each request drops the one before's last message, the newest scrollback turn sits on no unit boundary and hits only through the shared-prefix units. Once the scrollback passes `:seon.config.render/history-budget`, `seon.context/kept-turns` drops the oldest kept turn on every turn, so the message after the run sections changes each request and from then on the whole scrollback misses the cache.

A reply cut at `:seon.config.ai/max-tokens` (`:seon.turn/finish` "length"; `seon.model/reply-of` keeps the cut text and the forgiving reader repairs it) is almost always a repetition loop, not long work. Over 370 deepseek-flash turns with thinking off and a 65,536-token bound, 66 (18%) ended "length" at 184k-415k characters, each repeating 2-47 distinct forms (typically one ``clojure.repl/source`` call 300+ times), every form evaluated (up to 4,206 in one turn); they spent 45% of all tokens and 168 of 181 model-minutes (median 151 s against 2 s for an ordinary turn). 18 of 33 first turns looped, so the loop is not only history fed back. The largest ordinary reply was 19,809 characters (about 5k tokens).

The field's evidence for this shape: SWE-agent (arXiv 2405.15793, SWE-bench Lite, 18.0% resolved) lost 3.0 points with full history instead of collapsing all but the last 5 observations, 5.3 with a whole-file viewer instead of 100 lines, 6.0 with iterative instead of summarized search and 1.7 with no demonstration, and its successes took a median of 12 steps against a mean of 21 for its failures. CodeAct (arXiv 2402.01030) found code as the one action up to 20% more successful in fewer turns; Seon's one `eval` tool is CodeAct ([model](model.md#one-tool-eval)). aider ranks a 1k-token map of symbols by references, and Manus calls the KV-cache hit rate the first metric and keeps its context append-only and deterministically serialized.


## How a value is printed

A value is printed in two passes, selection under one token budget and then layout, so its text and its hiccup come from one selection and every cut names the form that reads the rest.

```seon.print/print-value``` is the one entry: a value, a handle the cuts read through (``result/h3``, or a form), a budget in tokens (```seon.print/budgets```: a reply's, a part's, a form's and the context's, each a `:seon.config.render*` key), a depth and a width. ```seon.print.select/show``` selects: it allots the budget breadth before depth (every child of a collection shows before any one deepens), identity keys first, and builds a print-node tree whose cut nodes carry their continuation forms; a value whose metadata holds ``:seon.print/full`` prints whole. ```seon.print.emit/emit-both``` lays the tree out as fipp does (begin, end, nest, line, text over one operation stream) and answers the text, and the hiccup the page's value panel shows when asked; fipp itself is only the layout's test oracle (`seon.print-test/the-layout-is-fipps`). Printing through fipp cost 45.4 KB and 16-17 µs per entry of a flat map, the printer 2.41 KB and 1.8 µs. Not this: Jolt's `clojure.pprint`, which would give that back. ```seon.print.select/estimate``` is the one sizer of tokens. `seon.print/text`, `seon.print/line` and `seon.print/prose` are the printer at a budget for text, a row and prose ([Prose](#prose-and-the-one-string-cutter), [Bounded lists](#bounded-lists)).

## One printer

There is one value printer, `seon.print`, and nothing is cut before it. A value a person or a model reads is its schema's declared render (`seon.render`) or the printer's text; a value that is not text reaching the page's serializer (`seon.web.html/html`), a one-line row (`seon.print/line`) or prose (`seon.render.ui/prose`) is printed by the printer, never `str`. The REPL answers every value through `seon.render/printed`: a value whose producer names its schema (`seon.task/task`, `seon.problems/report`: the value's metadata ``:seon.render/schema``) reads by its declared AI render, the same text root's context and the page are made from, and ends with the form that shows the whole value as data, `(with-meta result/h3 nil)`; a map that merely holds a key (a partial pull, tx data) prints as the data it is. An evaluation's error reads by the error map's render (its message, fix, variant and where; Jolt's diagnostics are keys of the map, printed once, [errors](errors.md#an-eval-errors-diagnostics)). The page's HTML renders of the same schemas show the same facts.

`seon.render-test/one-printer-and-no-cut-before-it` reads the compiler's facts (`:form/refs` of src imported, `seon.program/live-forms`; each var's form, and each method's): every var outside `seon.print` that references `pr-str`, `prn-str`, `print-str`, `with-out-str`, `pr`, `prn`, `printf` or `clojure.pprint`'s `pprint`, `pp`, `write` or `cl-format` is named below; every var of a display namespace (`my.*`, `seon.render.*`, `seon.web*`, `seon.context`, `seon.server`, `seon.cost`, `seon.repl`, `seon.agent`, `seon.problems`) that references `subs`, `take`, `take-last` or `drop-last` is named below; and every var outside them that calls the printer with a budget (`seon.print/text`, `line`, `prose`: a cut where a value is made) is named below. An unnamed one is red, and so is a name here that no longer does it. (`butlast` is left out: destructuring's expansion references it in every function. Not covered: `str`, `print`, `println` and `format` on data, and `first` or `drop` on rows shown, which the compiler's facts cannot tell from their use on data; review reads for them.)

### Printing beside the printer: the wire

Text for the wire, storage, a digest, a file, a URL, a command line or code to evaluate is `seon.edn/encode` (EDN that reads back, whole: no print limits), and only that var prints beside the printer:

- `seon.edn/encode`: EDN for storage and the wire, whole.

An agent's namespace refers `clojure.pprint`'s `pp` and `pprint` (`seon.agent/make-ns!`), as a plain Clojure REPL does (AGENTS §4, "what would a plain REPL do"); what they write reaches the model as the evaluation's `:out`, budgeted as prose. A check's console lines are the printer's whole text (`seon.main/shown`).

### Cut where a value is made

- `seon.main/shown`: names a budget only to print whole (the value's ``:seon.print/full`` metadata).
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
- `seon.render.proc/order`: the grid shows every live agent and the newest `:seon.config.render.list/ended` ended ones; an agent's end is the event that pushes the oldest off, and the tile counts those off it.
- `seon.render.proc/eval-records`: an agent's page shows its newest `:seon.config.render.list/history` evaluations; `seon.render.ui/history-more` says how many more its branch records.
- `seon.render.ai/section-costly`: the newest `:seon.config.render.list/top` evaluations of each agent its costliest functions are summed from.
