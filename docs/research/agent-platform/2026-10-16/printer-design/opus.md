---
title: Printer design, P1 (Opus plan)
date: 2026-10-16
lane: printer-design-opus
type: plan
status: proposed
---

# The one value printer: P1 plan (Opus)

Lane printer-design-opus, 2026-10-16. Basis: HEAD `ae7130094`, default running `91e6ff519`. Inputs: the two
printer audits ([opus](../printer-audit/opus.md), [astra](../printer-audit/astra.md)), the owner's model of
2026-10-16, root's relay of lane one-render's evidence, and REPL probes on real values (SCI on agent
`printer-design-opus`, scratch namespace `scratch.printer-probe`; nothing loaded into default's JVM). I did not read
Astra's plan.

**The owner's model, restated as the contract this plan implements.** A form runs. Its value is offered to render
selection. If a render is found, its result passes through: lazily for HTML, and the AI result through the one value
printer, with no exceptions. A render returns data, never clipped text. The printer gives a good-faith picture of
any value, with no "if schema X" branch, and shows the parts plus how to dig into the rest. A useful docstring is
never clipped so that small fields get equal shares.

## 1. Summary

The printer already has most of the right parts: one selection that emits both faces, cuts that carry runnable
continuation forms, `seon.print/printed` as a raw-text node, fipp as the layout engine, and the `beside`/`spent`
hooks for sharing a budget. What is wrong is four things around those parts:

1. **Seven entries and three budgets.** The entries are `seon.render.value/prepare`, `text`, `html`, `core-text`,
   `core-html`, `render-ai` and `render-html`. Around them sit `seon.sci.eval/shown-result`, `seon.repl/reply-map`
   and `seon.repl/shown-output`, and `seon.render/render-call`. The three budgets are the profile, the fixed core
   budget of 1000 tokens, and `text-limits`.
2. **Root-only dispatch.** `seon.render.value/make-node` offers only node 0 to `declared`, so every composite render
   and page renders its children by name. The audits count 59 direct calls, the 1b class, and the hand hiccup.
3. **Breadth-before-depth allocation with 32-character doubling.** It clips docstrings so that digests and keyword
   sets can show, drops map keys, and fills large sequences with `[…2]` placeholders.
4. **A second printer.** `datum`, `bounded-text`, `throwable-text` and `text-limits` sit under `core-text`, which has
   42 call sites.

The plan has seven phases. P0 adds a static ownership check, red at first, which the later phases turn green. P1a
adds one entry, `seon.render.value/show`, over one Malli request `:seon.render/request`. P1b replaces the allocator.
P1c turns on nested dispatch. P1d makes multi-part units. P1e moves position and age into the request. P1f deletes
the second printer.

Measured on real values, the prototype allocator:

- keeps the 1,050-character docstring of `seon.sci.eval/shown-result` whole at 1,024 and at 512 tokens, where today
  shows 276 and 74 characters;
- shows 71 whole tuples and one `…4885 (drop 71 result/x)` for a 4,956-row query, where today shows 80 `[…2]`
  placeholders and 33 cut docs;
- prints that query in time that grows with the part shown (2.5 ms interpreted in SCI) rather than with the value
  (28 ms compiled today).

`seon.render.value` should shrink from 1,943 lines to about 1,100.

## 2. What exists: data flow and owners

```
form → SCI eval → seon.sci.eval/shown-result (binds result/eN, profile, beside share)
     → seon.render.value/prepare → project → select-nodes (make-node → declared → seon.render/declared-projection
        → seon.render/selection → produce)          ; root only
     → tree → seon.print/emit-both (fipp ops → text, same op stream → hiccup)
     → :seon.eval/shown text   (stored; the MCP reply and the next context both read it)
seon.repl/response-entries glues :value + core-text(:findings …) + value/text(:out, spent) into "#:seon.repl{…}" text
seon.context/exchange-ai elides older replies by :seon.config.context/reference-tokens, printing its reference with core-text
pages: seon.render.web calls renders and core-text by name; seon.render/render-call is a second dispatch
```

**What each owner already does right, and keeps:**

- **fipp** (`reference-code/fipp`, `fipp.engine/serialize`, `annotate-rights`, `annotate-begins`, `format-nodes`)
  owns line layout. Seon never re-implements it. `seon.print/emit-both` turns one print-node tree into one fipp op
  stream, then into text and, by a fold over the same stream, hiccup. **fipp has no budget, selection or elision.**
  Those are Seon's, and they are this plan's subject.
- `seon.render.value/classify` puts every reached object, by its exact class, into a permitted-operation row. Host
  objects are class and identity, laziness is stepped to the point it prints, and Vars, SCI records and Throwables are
  data. This is correct and general, and it stays.
- `seon.print/continuation`, `path-form` and `elision` give every cut its `drop`/`subs`/`get-in` form on the result
  handle, and the HTML expander is the same continuation. These stay.
- `seon.print/printed` (`#seon.print/printed "…"`) is a value that carries already-printed text. It is how a part
  that is already text (a form's source) enters a larger value. It stays and becomes the multi-part mechanism.
- `seon.render/declared-projection` already says "at the root and at every depth" and refuses re-entry through
  `:seon.render/rendering`. Only `make-node`'s `(zero? id)` keeps it root-only (rule 10 of the namespace docstring).
- `seon.print/full`, the `:seon.print/budget` metadata and `seon.print/*budget*` are the agent's explicit
  widenings. They stay.

## 3. Measured: today against the proposed policy

The probe ran on 2026-10-16 in SCI on agent `printer-design-opus`, with the agent profile and the database value.
There was no SCI context in the request, so no render pairs ran: this is the printer alone, as in the audit.

- "Today" is `seon.render.value/prepare`.
- "New" is the disposable prototype allocator (§5.3), printed single-line by `pr-str`.
- Tokens are `seon.ai.tokens/estimate`.

The prototype measures flat width roughly, so it runs up to 3% over budget. The production allocator keeps today's
exact charge (§5.4).

| Value (whole size) | Budget | Today: cuts | New: cuts | What changed |
|---|---|---|---|---|
| Function entity `seon.sci.eval/shown-result`, 20 attributes (4,105 tok) | 1,024 | doc `…+774` (276 of 1,050 chars shown), source `…+6786`, collections `…4 …2 …41 …36 …29` | doc whole, source `…+6022` (1,018 chars shown), collections `…1 …37 …50 …44` | doc whole, 4× more source |
| Same | 512 | doc `…+976` (74 chars), source `…+6946`, `…+224`, 4 collections | doc whole, source collapsed to its form, one other string `…+665`, 4 collections | doc whole |
| Stored error entity, 24 attributes (2,701 tok) | 256 | `…7`: **7 attributes dropped** (sorted map, so the dig form is the whole value) | 11 attributes, then `…13` (the keys alone do not fit 256 tokens) | both cut keys at this budget |
| Same | 512 | `:seon.error/data-edn` `…+5709`, `…2 …3` | `:data-edn` `…+5469`, `…4 …5`, every key shown | all keys shown, strings longer |
| Task, 938-character plan (400 tok) | 256 | plan `…+536` | plan `…+517` | same |
| Stored evaluation (1,361 tok) | 512 | two strings cut (`…+98`, `…+2620`) | one string cut (`…+2616`) | short string whole |
| Query `[:find ?s ?d]` over `:seon.fn/doc`, 4,956 tuples (422,385 tok) | 6,144 | **80 `[…2]` placeholders**, 33 docs cut, 379 lines | **71 whole tuples, 0 docs cut, then `…4885 (drop 71 result/x)`** | no garbage |

**Time** (mean of 5 after one warm call, same JVM):

| Value | Today `prepare` (compiled) | Prototype (interpreted in SCI) |
|---|---|---|
| Function entity at 1,024 | 4.7 ms | 10.1 ms |
| Error at 512 | 2.0 ms | 5.0 ms |
| Task at 256 | 0.6 ms | — |
| `{:a 1}` | 0.11 ms | — |
| 4,956-tuple query at 6,144 | **28.1 ms, 63 MB allocated** | **2.5 ms, 2.7 MB** |

Today's selection grows with the members it queues, because every level of a large sequence is queued. The proposed
selection grows with what it shows plus one bounded width measurement per shown node (§5.3). In the prototype's
interpreted form the function entity is slower only because `width` is recomputed per call. Compiled, and with
widths memoized per node in the selection state, it is expected under today's.

**Layout share.** Indentation is 7.0% of today's function-entity text (221 of 3,136 characters), 4.5% of the query
text and 2.1% of the error text. That bounds the over-charge of the local indentation charge proposed in §5.4.

## 4. One-render's evidence, accounted for

Root relayed these from lane one-render's attempt to route evaluations through the printer. Its sketch is
`docs/research/agent-platform/2026-10-16/one-render-evaluation-pair.patch`.

1. **No options channel.** `seon.render/render-argument` copies a fixed list of 18 request keys.
   - P1a replaces the hand list. The render unit's keys are derived from the request schema:
     `(malli.core/explicit-keys (m/schema :seon.render/request))` minus the printer-private keys.
   - `:seon.render/options` is one declared member, passed whole. A render reads the members its own input schema
     declares, such as the position or `:seon.repl/input?`.
   - The fixed list is a hand list beside a schema (CLAUDE.md, "Derive pulled shapes and attribute lists from entity
     schemas"); deleting it is the fix.
2. **The provider message needs form and reply apart.** `seon.ai/chat-messages` puts forms in the assistant message
   and replies in the user message. P1d's multi-part unit returns each part's text under
   `:seon.render.value/parts`, keyed by part, beside the joined text, with all parts under one budget. The consumer
   takes `:seon.ai/form` and `:seon.ai/reply` separately; nothing re-splits text.
3. **594 of 600 stored evaluations matched; 6 of more than 6,200 tokens were cut with a bare `…+N`.** That bare cut
   is today's raw render-text cut when the value cannot carry `seon.print/full`'s metadata (`make-node`,
   `:raw-continuation`).
   - P1b's rule: **every cut of a value with a handle carries a form.**
   - Render data is cut like any data, with the form `(seon.print/full result/eN)` that re-shows the value under no
     budget. The handle names the value, not the render output, so re-showing is the dig.
   - Raw render text disappears once the text renders become data (P2). Until then, its cut carries the same form.
4. **The evaluation render was selected only when the pull included `:db/id`, and only with an SCI context.** That is
   structural matching on a pull shape.
5. **Held and unsaved evaluations are not receipts.**

   For 4 and 5:
   - The request gets an optional `:seon.render/schema`. The producer that knows what it holds says so, and selection
     tries that schema's declared pair first. This is the schema analogue of the value's own `:seon.render/ai`
     override; the existing `seon.render/selection` stages gain one, before structural matching.
   - Without an SCI context, selection still runs **compiled** renders. A Var whose render is a host-bound function
     needs no SCI context. P1c removes the `(:seon.sci.eval/ctx unit)` condition from `declared` and keeps it only
     for interpreted renders.
6. **No render caching: 43 ms through the printer against 16 ms direct for 40 evaluations.**
   - P1c reuses `seon.render/invocation`, `retain-invocation!` and `call-cache-evidence`, the existing caches keyed by
     content and read evidence, for nested selections. No new cache is added.
   - The proof for the 40-evaluation transcript is a `seon.profile/measure` assertion: the printer path costs no more
     than the direct path, parent against change.
7. **59 direct calls.** P0's check counts them from clj-kondo analysis, and P1c makes their conversion possible: a
   caller passes the value, not the render.

## 5. The design

### 5.1 One entry

```clojure
(seon.render.value/show request) ;=> :seon.render.value/shown
```

**Who calls it** (the complete list after P1; every other display path is a P0 finding):

- `seon.sci.eval/shown-result`: an evaluation's value. It stays the owner that binds `result/eN`, records sizes and
  evaluates REPL-entry renders.
- `seon.repl/response`: the reply, as one multi-part unit (P1d).
- `seon.context/model-context`: the history units, one per evaluation, with their position (P1e).
- Web pages: consumer `:seon.render/html`, evaluated when a viewer looks.
- Edge callers that may have no database (boot, the web emergency body, fault recording): the same entry with no
  custody, which is structural (P1f).
- The test runner's failure report: expected, actual and contexts as parts.

**The request** (`resources/seon/schemas/seon.render.edn`; members reuse existing names wherever one exists):

```clojure
:request
[:map {:description "What the one value printer shows, for whom, under what budget. Producer: every caller of seon.render.value/show."}
 ;; WHAT: one value, or several parts printed as one map under one budget (P1d)
 [:seon.render/value {:optional true} :seon.render/value]
 [:seon.render/parts {:optional true} [:vector :seon.render/part]]
 [:seon.render/schema {:optional true} :qualified-keyword]          ; the producer's declared schema of the value (§4.4–5)
 ;; FOR WHOM
 [:seon.render/output :seon.render/output]                          ; existing enum, gains :seon.render/line (one line, logs)
 [:seon.render/profile {:optional true} :seon.render.profile/profile]
 [:seon.render/position {:optional true} :seon.render/position]     ; P1e
 [:seon.render/distance {:optional true} :seon.render/distance]     ; existing: reference depth
 [:seon.render/options {:optional true} :seon.render/options]       ; passed whole to the selected render (§4.1)
 [:seon.render/direction {:optional true} [:enum :head :tail]]      ; tail for logs and outputs (P1b)
 ;; CUSTODY (all optional: a request with none prints structurally)
 [:seon.db/db {:optional true} :seon.db/database-value]
 [:seon.sci.eval/ctx {:optional true} :seon.sci.eval/ctx]
 [:seon.agent/id {:optional true} :seon.agent/id]
 [:seon.repl/handle {:optional true} :seon.repl/handle]              ; what every dig form reads from
 [:seon.render.data/cursor {:optional true} :seon.render.data/cursor]]

:part
[:map
 [:seon.render.part/key :qualified-keyword]                          ; :seon.repl/value, :seon.ai/form, :seon.repl/out …
 [:seon.render/value :seon.render/value]                             ; a seon.print/printed for text already printed (a source)
 [:seon.render/direction {:optional true} [:enum :head :tail]]
 [:seon.repl/handle {:optional true} :seon.repl/handle]]

:position
[:map
 [:seon.render.position/age [:enum :newest :older]]
 [:seon.render.position/index {:optional true} [:int {:min 0}]]]
```

**The answer** (`resources/seon/schemas/seon.render.value.edn`, extending today's `:seon.render.value/projection`):

```clojure
:shown
[:map
 [:seon.render.value/tree :seon.print/node]                           ; the one selection; text and hiccup are emitted from it
 [:seon.render.value/text {:optional true} :string]                   ; AI and line consumers
 [:seon.render.value/html {:optional true} :seon.render/hiccup]       ; HTML consumer
 [:seon.render.value/parts {:optional true} [:map-of :qualified-keyword :string]]
 [:seon.render.value/cuts [:vector :seon.print/elision]]              ; the dig data: path, omitted, bound, continuation
 [:seon.render.value/shown-tokens :int]
 [:seon.render.value/value-tokens {:optional true} :int]              ; today's whole-characters estimate, as tokens
 [:seon.render.value/renders {:optional true} [:set :qualified-symbol]] ; every producer selected, at any depth
 [:seon.render.value/diagnostics {:optional true} :seon.render.value/diagnostics]]
```

`:seon.render.value/cuts` is new as an answer member, but not as data: each cut is today's `seon.print/elision` node,
collected while `tree` builds. An agent can be shown its cuts as data, and a test asserts on them, never on text.

**Deleted by the end of P1:**

- In `seon.render.value`:
  - `text`, `html`, `core-text`, `core-html`, `render-ai`, `render-html`, `render-ai-data`, `render-html-data` and
    `prepare`. `prepare` is renamed to `show` with the request schema; its 41 callers are converted in the same
    commit.
- In `seon.repl`:
  - `reply-map`, `entries-in-order`, `ends-in-comment?` and `shown-output`.
  - `response-entries`'s text-gluing. It returns parts instead.

`seon.sci.eval/floor-text` is replaced by `show` with structural output.

### 5.2 Nested dispatch under the one budget

**Change** (`seon.render.value/make-node`): offer `declared` at every reached node that `keyword-lookup?` permits and
at every Var, not only at `id 0`. Four rules make it safe:

1. **A render's output is data and is printed by the same allocation.** Every keyword-lookup map in it is offered to
   selection again, except to the producers already on the chain. `seon.render/declared-projection` already refuses
   them through `:seon.render/rendering`; `make-node` threads that set through the node context. This is what lets a
   task render return `{… :seon.task/problems [err …]}` and have each error shown by the error render without
   calling it. **Renders never call renders**, and composition is the printer's.
2. **Selection by contract fit.** A pair is selected only when the value validates the render's declared input. The
   validator is `malli.core/validator`, compiled once per schema and cached by Malli's own registry.

   The probe found today's selection failing this rule (§9.1): a two-key partial error map was selected for
   `seon.db/render-rejection-ai`, refused at its input contract, and replaced by a 4,000-token diagnostic.

   With contract-fit selection, a value no render accepts prints as the data it is. That is no fallback: it is the
   floor, which is the correct answer.
3. **One budget.** A nested render's output is a node in the parent's selection, charged to the parent's remaining
   budget (audit option O2). A render may ask for more with `:seon.print/budget` metadata on its data, as today.
   Nothing gets a budget of its own.
4. **HTML is lazy.** For `:seon.render/html` the render's hiccup is a leaf node. It is produced only when a page asks
   for the HTML consumer, never during an AI print.

**Cost.** One `seon.render/selection` on a reached map measured 0.04 to 0.16 ms (warm and cold). Selection is offered
only to nodes the allocator reaches, so the count is bounded by the shown nodes, about 70 at 6,144 tokens: at most
about 11 ms uncached. Producer invocations reuse the content-keyed `seon.render/invocation` cache (§4.6). The phase
proof asserts the 40-evaluation transcript cost, parent against change.

**What moves out of the printer:**

- `seon.render.walk/with-related`, the one hop beside a root entity, becomes the entity render's own data at
  distance ≥ 1. The walk acquires; the render returns data; the printer prints.
- `declared`'s `:seon.schema/identity-projection` lookup moves into selection's declared stage, which already reads
  the projection.
- `render-database-identity-ai` moves to `seon.db`, the schema's owner.

The printer then has no schema-aware code (audit F90).

### 5.3 Allocation policy

It replaces rules 2, 4 and 5 of the namespace docstring: breadth before depth, short before long, and 32-character
doubling chunks. The policy is general: it branches on shape and size, never on schema. **Display order and
allocation order are separate.** A map still displays identity attributes first and then its held or alphabetical
order (`held-order`, kept). The allocator decides only how much each member gets.

**Measure.** `width(x, cap)` is the flat printed width of `x`, stopping as soon as it passes `cap`. One pass records,
on each node, `w` (its whole width, or "more than cap") and `min` (its least form):

- a scalar or name is its whole width, up to `:seon.render.profile/max-name`;
- a short value (`w ≤ :seon.render.profile/whole-width`) is whole;
- a string is its collapsed cut `"…+N (subs h-path 0)"`;
- a collection is `…N h-path`.

The cost is bounded by what is shown: each measure stops at the remaining budget, so the total is O(B + shown
nodes), never O(value). `counted?` collections answer `count` in O(1). A lazy seq is stepped only as far as it
prints, as `seq-cells` does today, and an unknown tail is `…?`.

**`allot(node, budget)`:**

1. **Whole when it fits.** If `w ≤ budget`, the node is whole. This is the common case, and it is the fast path.
2. **A string over budget** shows its head, or its tail when the request's direction is `:tail`, at the
   characters left after its cut form. The cut is backed to the last line end inside the head (today's `fill-end`)
   and carries `(subs path n)`, or `(subs path 0 m)` for a tail.
3. **A map over budget: structure first.**
   - The skeleton is every key plus each value's `min`.
   - If the skeleton does not fit (a 5,000-key map, or 24 long keys at 256 tokens), the keys show in display order at
     their `min` until the budget ends, then one `…N` with its form.
   - Otherwise the budget left after the skeleton is filled in this order:
     1. values already whole at their minimum;
     2. **strings, smallest first**;
     3. **collections, smallest first**.
   - Each value in that order grows to whole if its extra fits. The first that does not fit gets everything left,
     recursively. Every later value stays at its `min`.

   This is "largest collections collapse first; strings whole while they fit; a docstring is never cut so that a
   digest can show", stated as one water-fill.
4. **A sequence or set over budget: whole members, then one cut.**
   - Members are admitted in order, or from the end for `:tail`, each whole while it fits beside the reserved cut.
   - If the first member alone is over budget, it is allotted recursively.
   - Then comes exactly one `…N (drop k path)`, or `(drop-last k path)` for a tail.
   - **There is never a run of collapsed members.** Today's `fold-collapsed-tail` existed to mitigate those runs.
5. **Depth** is bounded by the profile's `max-depth`. The session's `*print-length*` and `*print-level*` bound it as
   hard as Clojure's own printer does; both are kept from today.
6. **`seon.print/full`** sets the budget to infinite. It is kept.

**Dials.** Every number is a profile dial in `resources/seon/schemas/seon.render.profile.edn` and
`seon.config.render.agent.edn`, with shipped defaults:

| Dial | Default | Replaces |
|---|---|---|
| `:token-budget`, `:token-ceiling` | as today | — |
| `:whole-width` | 24 characters: a value at most this wide is never cut | `scalar-bound`, `tuple-bound`, `chunk-width` |
| `:max-name` | 256 | `name-bound` |
| `:max-number-digits` | 32 | `scalar-bound` for big numbers |
| `:sort-bound` | 64 | `sorted-bound` |
| `:max-depth` | as today | `nest-cap`; fipp's indentation cap becomes the layout's `nest-depth`, already in `seon.print` |

### 5.4 The charge stays exact, and gets local

Today's charge is the laid-out length, including fipp's breaks. `settle`, `breaks?`, `extras`, `start-column` and
`chain` keep it exact through a cascade on every admission. The proposed charge:

```
charge = flat width
       + one (newline + indent at depth d) per admitted member line
```

That is an upper bound on fipp's output, since a broken group puts each member on its own line at its nest, and a
local one: each admission adds its own term, with no cascade. The over-charge is bounded by the measured indentation
share, 2 to 7%. The `(> (count text) limit)` assertion in `project` is kept as the invariant: the text is never
longer than the charge.

**Deleted:** `settle`, `breaks?`, `extras`, `start-column`, `chain`, `push-work`, `schedule` with its queue,
`sibling-level`, `admit-member`, `admit-chunk`, `complete-chunk`, `complete-tail`, `fill-strings` (`fill-end` survives
for line-end backing), `fold-collapsed-tail`, `tuple-items` and `tuple-node`. Tuples need no special case: a short
tuple fits whole.

**Kept:** `classify`, `seq-cells`, `list-row`, `held-order`, `member-rank` (now display order only),
`reference-value`, `cut-node`, `node-continuation`, `tree`, `omitted-characters`, `path-url` and `breadcrumbs`.

### 5.5 Options: one request, position replacing the history elision

- **Budget by position (audit O3).** The budget comes from the profile and the position:
  - `:newest` takes the profile's budget;
  - `:older` takes `:seon.config.context/reference-tokens`, the existing dial, now read by the printer.

  Over that budget, the unit's root does what any collection over budget does: its skeleton is shown, and when even
  the skeleton is over, it collapses to its `min`, which is the reference `{:seon.repl/handle h …N h}`.
  `seon.context/exchange-ai`'s hand reference and its `core-text` print are deleted. The reference is the general
  root collapse, not a second mechanism.
- **Detail level and reference depth (O4).** `:seon.render/distance` exists; it moves from the render-argument list
  into the request and is read by selection and by the walk. "Focused by default" is the render's choice of data at
  distance 0. No new detail enum is added: a render that offers more takes `:seon.render/options`.
- **Consumer (O6).** `:seon.render/output` gains `:seon.render/line`: the AI tree emitted with fipp width 0 (the
  profile's `:single-line` composition, which exists), for log and exception lines.
- **Include errors.** Diagnostics are always beside the value, as one part, and never dropped. A history unit that
  is not the newest drops the turn's meta (`:ms`, `:alloc-mb`), as today. Since a part is data, that is the caller
  leaving those parts out, not a print option.
- **Display units (O7).** The printer applies `:seon.config/display-unit` and `display-divisor` from the attribute's
  schema properties at emit. Those are general properties of any attribute, which deletes the `format` calls the
  audit lists (F51, F53, F63). This is P2, after the core.

### 5.6 Multi-part units

A reply is `{:seon.render/parts [{:seon.render.part/key :seon.repl/value …} {… :seon.repl/out :seon.render/direction :tail} …]}`.

**How the budget is shared.** The allocator treats the parts as the members of one map whose keys are the part keys,
so the water-fill above shares the one budget:

- the small parts (`:ms`, `:result`, `:ns`) are whole;
- the value and `:out` share what is left, smallest first;
- `:out` is cut from its tail with `(subs result/oN 0 m)`.

Today `beside-share` gives `:out` at most half the budget, and `response-entries` measures the reply by printing it
twice. Both are deleted.

**What a part that is already text does.** A form's source enters as a `seon.print/printed` value: whole, or, if over
budget, cut like a string with its own form.

**The answer** carries `:seon.render.value/parts` (each part's own text) and the joined `#:seon.repl{…}` text, which
the existing `:namespace-maps?` emitter prints. `seon.ai/chat-messages` takes the form and reply parts separately
(§4.2). The transcript and the debug view take the same parts.

### 5.7 Retiring the second printer

**The last resort is single and structural.**

- If `show` with renders throws, the same `show` runs once with `:seon.render.value/structural?` (no renders), as
  `project`'s catch does today.
- If that throws as well, the answer is one node, `{::print/face ::print/object ::print/class c ::print/rep "0x…"}`,
  beside the failure's `compact-failure` data. That is class and identity, read without calling any of the value's
  methods: `opaque` today, made a node.
- No `pr-str` is involved at any step.

**A Throwable is data.** `classify` already turns a safe Throwable into data (`throwable-value`). `shown-chain`'s
per-link class, message, ex-data and frames become that data. The frames are a vector, so the allocator cuts them by
budget with a `drop` form. `text-limits`' `:links`, `:frames`, `:entries`, `:suppressed` and `:throwable-chars`
disappear.

**Deleted from `seon.render.value`:** `text-limits`, `datum`, `bounded-text`, `causes` (absorbed into
`throwable-value`, which already walks causes by identity), `link-lines`, `throwable-text`, `core-profile`,
`plain-projection`, `diagnostics-shown` (diagnostics become a part) and `unavailable`.

**How the 42 `core-text` sites convert:**

- An edge site (boot, fault, web emergency, the operator) calls `show` with no custody and `:seon.render/output
  :seon.render/line` or `:seon.render/ai`. The budget comes from `seon.config/defaults`, a compiled constant needing
  no database, so a broken configuration cannot stop it.
- A non-edge site (the 21 inside renders and context) passes its value as data in its render's output instead, and
  nested dispatch prints it.

**`seon.error.refusal/diagnostic` and stored errors** keep evidence as data, or as a blob reference through
`seon.blob/inline-or-stage`. They never hold `bounded-text`. This is Astra's priority 2, `seon.error/fit-fact-payload`,
and it is a storage conversion owned by the error lane: P1f only removes the printer functions it borrows.

### 5.8 The cost of printing (it runs on every reply)

| What | Cost today | Cost after P1 |
|---|---|---|
| A small value (`{:a 1}`, the common reply) | 0.11 ms | The same fast path, rule 1, plus one width measure |
| Selection | Grows with members queued, because every level of a large sequence is queued (28 ms and 63 MB for 4,956 tuples) | O(B + shown nodes) |
| Nested selection | — | At most about 70 selections per reply at 6,144 tokens (≤ 11 ms uncached, cached by content through `seon.render/invocation`) |
| Emission | fipp over the shown tree | Unchanged |
| A reply | Printed twice (`response-entries` measures it with `reply-map`) | One selection over all its parts |

Every phase's proof includes a `seon.profile/measure` assertion on the end-to-end probe it changes, timed on parent
and change in the same run.

## 6. Phases and red proofs

Each phase is one loadable slice that boots. The printer namespaces, `seon.render.value`, `seon.print`,
`seon.sci.eval` and `seon.render`, are host-bound and platform code. Each commits through `bin/lane commit … --test …`
with the named tests. A deletion lands in the commit that converts its last caller. The phase-status line belongs in
this file's owning plan once the hybrid adopts it.

### P0: the ownership check (the harness)

**Change.** Add `seon.render.ownership-test`, or the existing lint test namespace if root names one. It runs one
in-process `clj-kondo.core/run!` over `src` and `script` with `{:analysis {:keywords true :arglists true}}`, through
`seon.fn.analyzer`'s owner rather than a new analyzer. Its derived sets:

- **R** is every symbol value of `:seon.render/ai`, `:seon.render/html` or `:seon.render/form` in
  `seon.schema.edn/packaged-forms`.
- **P** is every var reachable from `seon.render.value/show` over `:var-usages`, minus R.

There is no hand list, as in the Opus audit.

**Rules:**

- **R1:** a usage of a var in R from outside P.
- **R2:** a usage of `seon.render/render-call`, `declared-projection` or `selection` from outside P.
- **R3:** `pr-str`, `prn-str`, `print-str`, `with-out-str`, `clojure.pprint/*`, `fipp.*` or `print-method` outside a
  var marked `^{:seon.print/emitter true}`. The mark goes on `seon.print/literal` and the canonical EDN encoder
  (`seon.sci.admit/canonical-edn`).
- **R4:** a number-literal count to `subs`, `take`, `take-last` or `drop-last`, or a numeric `def` in a namespace of R
  or P.
- **R5 (new):** an AI render (in R) whose declared output schema is `:string`.

The liveness check asserts that P contains `seon.render.value/show` and `seon.print/emit-both`.

**Red proofs.** One deftest per rule. Every one is red on HEAD; the audits count roughly 59 sites for R1/R2, about 135
for R3 and 24 for R5. Each later phase turns its rule's slice green for the files it converts. Each rule is data, with
the finding's file, symbol and span, so the report names the next site to convert.

### P1a: one entry, one request, an options channel

**Change.**

- Add `:seon.render/request`, `:seon.render/part`, `:seon.render/position` and `:seon.render.value/shown`.
- Add `seon.render.value/show`, which is `project` over the request, plus `:cuts` collected in `tree`.
- `seon.sci.eval/shown-result` calls `show`.
- `seon.render/render-argument` derives its keys from `:seon.render/request`, not its 18-key list, and passes
  `:seon.render/options` whole.

**Red proofs.**

- `seon.render.value-test/show-answers-tree-text-and-cuts` is a table over the six real shapes. The function entity,
  task, error, query, must-read and evaluation are built through canonical writers in the harness. Each asserts
  `:seon.render.value/cuts` paths and continuations as data.
- `seon.render-test/a-render-receives-request-options` is red today, because options are dropped by the 18-key list.

### P1b: the allocation policy

**Change.** §5.3 and §5.4 replace the queue allocator in `select-nodes`; the deletions are listed there.

**Red proofs** (new rows in `seon.render.value-test`, asserted on cut data, never on text):

1. **A docstring survives.** The function entity of `seon.sci.eval/shown-result` at 1,024 and at 512 tokens has no
   cut at path `[:seon.fn/doc]`. Red today: `…+774` and `…+976`.
2. **No placeholder runs.** A 4,956-tuple relation at 6,144 tokens has exactly one cut at path `[]`, with
   `:seon.print/continuation` `(drop k result/x)`, and every shown tuple is whole. Red today: 80 collapsed members.
3. **Structure first.** A 24-attribute error entity at 512 tokens shows all 24 keys. A map whose skeleton is over
   budget has exactly one key cut. Red today: `…7` at 256 and `…2` dropped at 512.
4. **Every cut digs.** A cut of a value with a handle has a continuation, render data included (one-render evidence
   3). Red today: the bare `…+N` on render text.
5. **Charge bounds the text** (a property). For generated values bounded by `seon.print/node-generator`'s domain,
   the emitted text is never longer than the charge, and the charge never exceeds the budget. This law exists today
   as a throw in `project`; it becomes a `malli.generator/check` property.
6. **Tail direction.** A 2,000-line `:out` part shows its last lines and a `(subs result/oN 0 m)` form.
7. **Cost.** `seon.profile/measure` on printing the 4,956-tuple relation, parent against change, asserts the
   allocation is at most a quarter of the parent's (today 63 MB) and the time is lower. The function entity at
   1,024 is no slower than the parent.

### P1c: nested dispatch

**Change.**

- `make-node` offers `declared` at every keyword-lookup map and Var, threading `:seon.render/rendering`.
- `declared` drops the SCI-context precondition for compiled renders.
- Selection requires contract fit, `m/validator` of the render's input.
- `:seon.render/schema` on the request is honored first.
- `with-related` and the identity-projection lookup move as listed in §5.2.

**Red proofs.**

- `seon.render.value-test/nested-values-select-their-pairs`: a vector of three stored error maps prints each through
  its declared AI pair, asserted by `:seon.render.value/renders`. Red today: root only.
- `seon.render-test/selection-requires-contract-fit`: the two-key partial error map from §9.1 selects no pair and
  prints as data with no diagnostic. Red today: `seon.db/render-rejection-ai` refused, with a 4,000-token
  diagnostic.
- `seon.render-test/a-producer-is-not-re-entered`: a render whose output holds a value of its own schema terminates,
  and the inner value prints as data.
- `seon.render.value-test/held-evaluation-selects-by-declared-schema`: an evaluation with no `:db/id` and no SCI
  context, requested with `:seon.render/schema :seon.repl/emission`, selects the evaluation pair (one-render evidence
  4 and 5).
- Cost: the 40-evaluation transcript through `show` costs no more than the direct path, parent against change
  (evidence 6).

### P1d: multi-part units

**Change.**

- `seon.repl/response` builds parts, and `show` prints them.
- Delete `reply-map`, `entries-in-order`, `ends-in-comment?`, `shown-output` and `beside-share`.
- `seon.ai/chat-messages` reads `:seon.render.value/parts`.

**Red proofs.**

- `seon.repl-test/a-reply-is-one-unit-under-one-budget`: a value and `:out` that both overflow share one budget, and
  the reply's shown tokens are at most the budget. Asserted on parts data.
- `seon.ai-test/chat-messages-take-form-and-reply-parts`: the provider messages hold form and reply from the parts,
  with no text re-split.
- The existing golden transcripts in `seon.repl-test` stay green. They are the declared golden text tests.

### P1e: position and age

**Change.**

- `seon.context/model-context` sends each evaluation as a request with `:seon.render/position`.
- Delete `exchange-ai`'s reference construction and its `core-text`.
- `:seon.config.context/reference-tokens` is read by `show` for `:older`.

**Red proofs.**

- `seon.context-test/an-older-unit-over-its-budget-collapses-to-its-reference`: the reference is the root's cut, with
  the handle and the `get-in` form, as data.
- `seon.context-test/history-is-one-derivation`: the debug AI column, the provider prompt and the stored history
  read the same `show` answer. CLAUDE.md, "one derivation, every consumer".

### P1f: retire the second printer

**Change.** Delete the vars listed in §5.7. Convert the 42 `core-text` sites, 21 non-edge and 11 edge (5 of them in
scripts), together with `seon.error/render-chain-ai`'s use of `:frames`.

**Red proofs.**

- P0's R3 and R4 are green for `seon.render.value` and `seon.repl`.
- `seon.render.value-test/a-hostile-throwable-prints-as-class-and-identity`: a Throwable whose `getMessage` throws
  prints as a node, never through `pr-str`.
- `seon.render.value-test/a-printer-failure-prints-structurally-once`: one diagnostic part, value whole or cut.

### Then P2 (named, not designed here)

- The 24 text-returning AI renders become data (R5 green); the audit list is F45–F47, F49, F52, F53 and F55.
- `pr-str` into error messages becomes data keys (F56).
- The web layer's direct calls (F09–F17) are removed.
- Display units (O7) are added.

Each is a mechanical slice for Sonnet once P1c exists, with P0's rule as its proof.

## 7. The simplest alternative considered

**Keep the queue allocator and add "never cut a string while a collection can collapse" as a rank.** Rejected.

- The breadth-first level order is the cause of both garbage classes. Docstrings lose because they grow one chunk
  per level beside every sibling's growth. Placeholder runs appear because the sequence tail is queued one level
  deeper per doubling and still costs its brackets.
- A rank inside that queue still admits collapsed members before the budget ends, and still needs
  `fold-collapsed-tail` to tidy them afterward.
- The water-fill is shorter (§5.4's deletions) and does work proportional to what it shows.

**A second, "rich" printer for renders.** Rejected outright: it is the problem this plan removes.

## 8. Risks and open questions for the hybrid

- **Nested dispatch changes many outputs at once** in P1c. The golden transcripts and the `seon.render.*` tests will
  move. Classify each red by the commit that changed it, and never wholesale-triage.
- **Contract-fit selection may drop selections that work today by accident**, as with partial maps. That is the
  intended behavior, but the 594/600 comparison (one-render's harness) should be rerun after P1c.
- **The local indentation charge over-charges by 2 to 7%.** If the hybrid wants exactness, keep `settle` for the
  final pass only, never per admission.
- **Small-map sorting.** "Identity first, then held or alphabetical order" is today's display order. Whether short
  before long should stay a display rule is owner taste. The plan keeps it display-only.
- **What I did not measure.** A live must-read over budget (none found, as in the audit), and nested dispatch's real
  cost on a live page. That needs P1c's code and is its cost proof.

## 9. Out-of-scope findings (from these probes; each needs its task filed by root)

1. **Selection picks a render whose input contract the value fails, and the failure prints at about 4,000 tokens.**
   - Reproduction: in SCI, evaluating `(select-keys err [:seon.error/type :seon.error/message])` on a stored
     `:seon.db.write/validation-refusal` error. That selects `seon.db/render-rejection-ai`; the contract refuses six
     missing keys; the reply's `:diagnostics` carries 29 stack frames and six full problem maps.
   - Two defects:
     - selection without contract fit (the producer is `seon.render/selection`);
     - the diagnostic printed whole, with whole frames, which CLAUDE.md forbids ("Seon never generates bulk of its
       own").
   - Proposed task: `seon.render/selection-selects-a-pair-whose-input-the-value-fails`, in
     `resources/seon/tasks/seon.render.edn`. P1c's `selection-requires-contract-fit` is its proof.
2. **Quoted symbols auto-require their namespace.** `(def handle 'result/x)` reported
   `:auto-required #{result}`, and `'seon.sci.eval/shown-result` inside a quoted pull reported `#{seon.sci.eval}`.
   Quoted data is not a reference. Proposed task: `seon.sci.eval/auto-require-reads-quoted-symbols`.
3. **A repeated fix text is printed in every reply entry.** A 12-form request repeated the 260-character
   `:uncontracted` fix prose 10 times, about 700 tokens of noise. The fix belongs once per reply, or in help.
   `response-entries` already drops fixes for older units, so the same rule applies within one reply. Proposed task:
   `seon.repl/findings-repeat-fix-prose-per-entry`.
4. **First requests on a new agent branch are slow.**
   - `(ns …) (def db …) (count (q …))` cost 1,174 ms and 1,612 MB.
   - Twelve `defn`s cost 1,364 ms and 1,723 MB, with per-form `:ms` of 17–51 and per-form `:alloc-mb` of 23.9 for a
     trivial `defn`.
   - That is far over ten times what the forms should cost. Proposed task:
     `seon.sci.eval/a-trivial-defn-allocates-24-mb-on-an-agent-branch`, with these numbers.
5. **An interpret error whose cause had already been fixed was still reported.**
   - The error was `Cannot interpret scratch.printer-probe/today: Unable to resolve symbol: profile`
     (`seon.sci.eval/install-row!`).
   - It appeared after `profile` was defined in a later request: `today` was installed in a request where `profile`
     failed to define.
   - It is recorded as an error on the branch while the function works. Worth a reproduction before filing.
