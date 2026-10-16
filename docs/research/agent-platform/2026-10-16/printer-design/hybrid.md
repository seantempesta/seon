---
title: Printer core, hybrid plan
date: 2026-10-16
lane: printer-hybrid
type: plan
status: proposed; awaiting the narrow check
---

# The one value printer: hybrid plan

Lane printer-hybrid (Fable), 2026-10-16, from [Plan A (Opus)](opus.md), [Plan B (Astra)](astra.md), the
[merged inventory](../printer-audit/inventory.md) (609 findings, phases P1 to P6 by id), lane one-render's seven
findings (`a028e7231`, [patch](../one-render-evaluation-pair.patch)) and root's rulings R1 to R12 of 2026-10-16. I
wrote neither plan. Code claims below were re-read at HEAD `61e788b41` plus the working tree; nothing was run on
default. This is a plan, not production code.

**Phase status:** P0 proposed · P1a proposed · P1b proposed · P1c proposed · P1d proposed · P1e proposed · P1f
proposed · P2 (inventory P2 to P6 groups) proposed. Each implementing commit updates this line.

## 1. The contract (the owner's model, as both plans restate it)

A form runs. Its value goes to one entry, `seon.render.value/show`, with one request. Selection looks the value up for
a render by schema; if one fits, its result passes through: lazily for HTML, and the AI result is printed by the one
value printer under the one budget. The printer branches on shape, size and class, never on schema; it shows a
good-faith picture of what a value is, whole when it fits, and every cut carries a plain `clojure.core` form on a
handle that digs for the rest. A render returns data, never text; nothing calls a render by name; the printer offers
every reached map and Var to selection, so composition is the printer's. A docstring is never cut so that a digest can
show.

## 2. Where A and B agree (stated once, adopted)

1. **One entry, one request, one answer** in `seon.render.value`; the request reuses the existing names
   (`:seon.render/value`, `:seon.render/output`, `:seon.render/profile`, `:seon.render/distance`,
   `:seon.print/options`, `:seon.repl/handle`); the answer carries the tree, the cuts as `seon.print/elision` data,
   the token charge, diagnostics, and text or hiccup only for the outputs asked for. No HTML is built on the AI path.
2. **Nested dispatch is the printer walk's.** A render's returned root is lowered once in its occurrence's place; its
   child maps and Vars are new subjects that select their own pairs. Renders never call renders. HTML hiccup is a
   lazy leaf for AI prints.
3. **Allocation policy:** structure first (every key, every collection's kind and count); strings whole while they
   fit; collections give way before strings; a sequence or set shows whole members then exactly one `…N` with a
   `drop` form (never a run of collapsed members); tail-first is an explicit direction; one budget charges everything
   shown, nested faces included; every number is a profile dial. `chunk-width`, `scalar-bound`, `name-bound`,
   `nest-cap`, `sorted-bound`, `tuple-bound`, `text-limits` and `core-profile` go.
4. **The second printer is deleted:** `datum`, `bounded-text`, `throwable-text`, `link-lines`, `causes`,
   `text-limits`, `core-profile`, `core-text`, `core-html`, `text`, `html`, `render-ai`, `render-html` and the
   `seon.render.value/render-ai`/`render-html` schema attachments (`seon.render.edn` line 9, `seon.program.edn` line
   247). One structural last resort runs the same tree and emitter; a hostile host object is its class and identity
   node. `seon.error/fit-fact-payload` and stored `bounded-text` are the error owner's storage conversion, not P1.
5. **The reply is one map under one budget** (`reply-map`, `shown-output`, `beside-share`, the "spent/beside"
   arithmetic and `response-entries`' text glue are deleted); history units carry a position; the saved
   `:seon.eval/shown` text is replayed as `seon.print/printed`, never re-budgeted by characters.
6. **P0 is a static check** extending the two red census tests that already exist
   (`seon.print-census-test/no-value-is-printed-outside-the-value-printer`, 85 sites since `18919b663`;
   `seon.source-census-test/no-timeout-or-limit-is-a-hardcoded-number`) plus a render census whose render set is
   derived from `seon.schema.edn/packaged-forms` (pairs are also declared in Clojure source: `seon.bootstrap`,
   `seon.render`, `seon.schema.edn`, `seon.sci.lookup`).
7. **String-returning AI renders become data** (22 of the 27 that admit `:string`; `my.program/render-source-ai`
   and `seon.cluster.instruction/instruction-ai` carry text as content and stay).
8. **`seon.print/printed`, `seon.print/full`, `*budget*` and the `:seon.print/budget` metadata stay** as the
   already-printed value and the agent's explicit widening.

## 3. Where they differ: decisions

| Question | A | B | Decision and why |
|---|---|---|---|
| Entry name and shape | `show` over one request map; rename `prepare` and convert its callers at once | `print-value value request`; old entries delegate until their last caller converts | **`seon.render.value/show` over one request map** (A): the value is a request member, so Seon's default filling by schema name applies to it like any input, and `prepare`'s 61 call sites convert by one scripted sweep. **Old entries delegate** (B): `text`, `html`, `core-text`, `core-html`, `render-ai`, `render-html` become one-line callers of `show` in P1a and are deleted in P1f with their last callers, so each slice is ~15 minutes and HEAD boots after each. |
| Request schema | one open map, custody members all optional; structural when none | `:multi` on `:seon.render/mode`, declared arm requires db, ctx, projection, caps, time-limit | **A.** The mode is a function of what the caller holds (two derivations of one fact); and CLAUDE.md fills omitted binding-schema members (`:seon.db/db`, `:seon.sci.eval/ctx`) from the evaluation's bindings, which only works when they are optional members. Boot and fault paths hold none and print structurally. B's caps and time-limit stay as render-call inputs derived from the request schema (below), never a second display budget. |
| Options to renders | derive `render-argument`'s keys from the request schema; pass `:seon.render/options` whole | "call preparation supplies the same bindings to each face" | **A**, concretely: `seon.render/render-argument`'s 18-key `select-keys` (`src/seon/render.clj`, re-read) is replaced by `(malli.core/explicit-keys (m/schema :seon.render/request))`, and `:seon.render/options` is one member a render reads by its own input schema. One-render finding 1. |
| Position and age | `:seon.render/position {age :newest/:older, index}`; `:older` budget is the existing `:seon.config.context/reference-tokens` dial, read by the printer | position enum (current/history/context/debug/log), age int, a new `position-budgets` rule table dial; migrate and delete reference-tokens | **A.** Two existing dials cover the two observed cases; a rule table is policy nobody reads yet ("do the work when its data exists"). Debug and log are consumers, not positions: `:seon.render/output` gains `:seon.render/line`. |
| Detail level, include-errors? | no new enum; `:seon.render/distance` moves into the request; diagnostics are always a part; an older unit drops meta because its producer leaves it out | `:seon.render/detail` enum and `include-errors?` flag | **A.** Count before designing: no render reads a detail enum today; `:seon.render/options` carries a render-specific choice when one exists. Errors are data beside the value, not a flag. |
| Allocation algorithm | measure once (`width(x, cap)`, saturating at the remaining budget) then a water-fill: whole if it fits; map skeleton then grow whole-at-min, strings smallest first, collections smallest first; local charge = flat width + one line-and-indent per admitted member line (an upper bound, 2 to 7% over) | collapse largest collection first, then cut largest string, incremental deltas; charge must include fipp layout exactly; prototype measured ~10× today's cost because it reprinted | **A's water-fill with B's invariants.** Grow-from-minimum does work proportional to what is shown (2.5 ms / 2.7 MB against 28 ms / 63 MB on the 4,956-tuple query); collapse-from-whole sizes the whole value. The charge is an upper bound; the existing law `seon.render.value-law-test/the-text-stays-inside-its-charge-and-budget` (text ≤ charge ≤ budget) stays the proof, extended to multi-line strings and nested maps (B's cases). `settle`, `breaks?`, `extras`, `start-column`, `chain`, `push-work`, `schedule`, `sibling-level`, `admit-member`, `admit-chunk`, `complete-chunk`, `complete-tail`, `fill-strings`, `fold-collapsed-tail`, `tuple-items`, `tuple-node` go; `fill-end`, `classify`, `seq-cells`, `held-order`, `member-rank` (display order only), `reference-value`, `cut-node`, `node-continuation`, `tree` stay. |
| Re-entry in nested dispatch | thread `:seon.render/rendering` so producers on the chain are not re-selected | no producer suppression set: two same-schema children both render; cycles are reference nodes | **B's semantics.** A render's returned root is not re-offered (lowered once); its children are fresh subjects, so a task render's `:seon.task/problems [err …]` shows each error by the error pair, and two errors both render. Termination is the printer's general rules, not an identity set: budget, `max-depth`, and the existing cycle rule (a value already on its own ancestor chain is a reference node). `:seon.render/rendering` survives only for the case it was built for, a producer that delegates its own value onward. |
| Cuts inside render output | the cut's form is `(seon.print/full result/eN)` (re-show the value whole) | retain the face's output as a live result in the existing bounded result cache with its own handle; cuts use `get-in`/`drop`/`subs` on that handle | **B.** That is the dig the owner asked for; `full` re-shows the whole value, which is bulk. The render output is retained in `seon.cache` under the content key `seon.render/invocation` already uses (producer, value digest, read evidence), with a handle minted by the existing result-identity owner, and every cut inside it continues on that handle. Without custody the existing `:seon.print/requery-refusal` answers. One-render finding 3: a cut of any value with a handle carries a form. |
| Contract-fit selection and a producer-declared schema | select a declared pair only when `m/validator` of the render's input accepts the value; `:seon.render/schema` on the request is tried first | viewer-namespace contract fit; declared schema most-specific; no per-child validator compilation | **A.** Malli caches the compiled validator on the compiled schema (`malli.core/validator`), so cost is one validation per reached candidate (0.04 to 0.16 ms measured per selection); it is what makes the §9.1 defect (a partial map selected, refused, 4,000-token diagnostic) impossible, and `:seon.render/schema` covers held and unsaved evaluations (one-render findings 4 and 5). A compiled render needs no SCI context (`declared`'s `(:seon.sci.eval/ctx unit)` precondition applies only to interpreted renders). |
| Multi-part reply | `:seon.render/parts` vector of part maps with keys and direction; answer returns per-part text | the reply is one ordinary map; parts are path annotations; no extra budget | **B's shape with A's output.** The request's value is the reply map itself (`#:seon.repl{:value :out :ms …}`); `:seon.render/parts` is `{path {:seon.render/direction :tail}}` annotations; `:seon.render/outputs` (B) is `#{:text}`, `#{:hiccup}` or with `:parts`, and `:parts` returns each root member's text emitted from the same tree, which is what `seon.ai/chat-messages` needs to send form and reply apart (one-render finding 2) without re-splitting text. |
| Static-check printer set | vars reachable from `show` over `:var-usages` | never reachability: it reaches general code and exempts what it should find | **B.** The permitted set is the existing declared `seon.print-census-test/printer-files` (`src/seon/print.cljc`, `src/seon/render/value.clj`) plus `src/seon/render.clj` for `render-call`, with A's liveness assertion that the set defines `show`, `emit-both` and `render-call`, and B's mutation fixtures (one small source string per rule that the rule must catch). |
| R4 (literal counts) | a clj-kondo hook on `subs`/`take`/`take-last`/`drop-last` | — | **Neither hook:** extend the existing `no-timeout-or-limit-is-a-hardcoded-number` census to the printer and render namespaces; no new analyzer. |
| HTML native children | hiccup is a leaf | a typed child slot node so hiccup can hold domain children rendered by their own pairs | **B, but in P2 with its first caller** (the web pages, inventory P5): in P1 no render needs it. |
| Phase order | P0, entry, allocation, nested, parts, position, second printer | entry, nested, allocation, reply, faces and callers, second printer, integrated proof | **P0 → entry → allocation → nested → reply → history → second printer → P2 groups.** Allocation before nested so nested output is never printed by the queue allocator; B's integrated proof and R12 doc amendment land with P1c, the commit that makes nested dispatch real. |

## 4. What both plans missed

1. **Render output that the REPL evaluates.** `:seon.render.call/source-output?` (`seon.render` lines 608 to 1657,
   `seon.render.web` 1375, schema `seon.render.value.edn` line 44) makes the REPL path evaluate forms a render
   returned. CLAUDE.md: a render returns data, never forms to execute; the form face is shown, not run. B names it,
   A does not. It is retired in P1d with its last producer (`my.program/render-source-ai` returns source as
   `seon.print/printed` content, and `seon.render.web/render-source-call` becomes an ordinary HTML request in P2).
   Count the producers first (`rg source-output?`: 2 producers today).
2. **The fast path is the proof that matters.** Printing runs on every reply, and the common reply is small
   (`{:a 1}`: 0.11 ms today). Both cost proofs were on large values. Every phase that touches the printer asserts
   `seon.profile/measure` parent against change on three probes in the same quiet run: the small reply, the
   `seon.sci.eval/shown-result` function entity at 1,024 tokens, the 4,956-tuple query at 6,144.
3. **`seon.render.value/artifact`, `artifact-edn`, `read-artifact`, `artifact-value`** (21 outside callers) sit in
   the printer namespace but are admission storage, not display. They move to their owner (`seon.sci.admit`) in
   P1f so `seon.render.value` holds only the printer; neither plan listed them.
4. **The printer-as-render schema attachments** must be retracted in the commit that deletes `render-ai`/`render-html`
   (B says "remove schema attachments"; A's deletion list omits the two resource lines).
5. **File ownership now.** `src/seon/sci/eval.clj` is held by lane fix-sci-eval (uncommitted, `bin/lane status`);
   P1a's change to `shown-result` is a patch to that lane or waits for its commit. The four one-render files
   (`context.clj`, `repl.clj`, `render/transcript.clj`, `render/sent.clj`) are clean at HEAD, so P1d and P1e claim
   them normally.
6. **The history unit's two halves.** A re-shows older units with a position; B replays the saved emission as
   `printed`. Combined (P1e): an older unit enters as `seon.print/printed` of its saved `:seon.eval/shown` with its
   known token size; the allocator admits it whole or, over `reference-tokens`, collapses it to its root's minimum,
   which is the reference `{:seon.repl/handle h …N (get-in h [])}`. No characters of a saved unit are re-budgeted,
   and `exchange-ai`'s hand reference and `core-text` go.

## 5. Root's rulings, folded in

| Ruling | Where it lands |
|---|---|
| R1 error messages are short fixed text, values (scalars included) as data keys | P2-errors (inventory P4): one scripted sweep of the ~150 + 40 sites; P0's R3 rule is the proof for the printed ones, a review row for scalars |
| R2 `script/` shows values through `show`; fixed prose and machine EDN stay | P2-rest (inventory P6), after P1a exists |
| R3 `seon.id/printed` is the one EDN encoder; `seon.sci.admit`'s writer folds into it | P2-rest; the encoder carries `^{:seon.print/emitter true}` and the census's hand exception map (`canonical-edn`, one entry) is deleted in that commit |
| R4 top-k only with the total beside it and k from a dial | P2-rest, with `no-timeout-or-limit-is-a-hardcoded-number` as proof |
| R5 never sort by printed text | P2-rest (`seon.cluster.source/proven-file`, `seon.schema/unresolved-reference-refusal`) |
| R6 the opaque-object node owns host-object text; `seon.env` print-method defers | P1f (the node exists there) |
| R7 log tail window with tail-first and the full-log reference | P2-rest, after P1b's `:tail` direction |
| R8 `graph-node-id` uses `seon.id/id` | P2-web |
| R9 `seon.problems/agent-error-parts` carries members, no parsing | P2-errors |
| R10 `seon.render.walk/related-html` becomes a print node of the HTML face | P1c (it is the `with-related` move) |
| R11 delete `seon.cluster.agent/whoami` | P2-faces |
| R12 amend CLAUDE.md "Presentation" (line 582: "never re-dispatches … calls that render explicitly") and the `make-node` comment to the 2026-10-16 model | P1c's commit |

## 6. Phases

All printer phases touch host-bound namespaces (`seon.render.value`, `seon.print`, `seon.render`, `seon.sci.eval`),
so each commits through `bin/lane commit LANE -m MSG --test NS... PATHS` with the tests named; a deletion lands in the
commit that converts its last caller; HEAD boots after each. "Cost" means `seon.profile/measure` parent against change
on the three probes of §4.2 in one run, asserted in a test that a wall-clock-only bound cannot flake (allocation and
time both lower than parent on the query; small reply not slower).

| Phase | Files | Change | Deletions (in that commit) | Red proof that resolves | Cost |
|---|---|---|---|---|---|
| **P0** harness | `test/seon/print_census_test.clj`, `test/seon/source_census_test.clj`, new `test/seon/render_census_test.clj` | Render census over clj-kondo analysis (through `seon.fn.analyzer`): R1 a usage of a var in R (from `packaged-forms`) outside the printer files; R2 a usage of `seon.render/render-call`, `declared-projection`, `selection` outside them; R5 a declared AI render whose output schema admits `:string` and is not marked text-as-content. Extend R3 (`pr-str` family, `clojure.pprint`, `with-out-str`, `print-method`) to `script` and to the `^{:seon.print/emitter true}` exception; extend R4 to the render and printer namespaces. Liveness: the printer set defines `show` (after P1a; `prepare` until then), `emit-both`, `render-call`. One mutation fixture per rule. | none | All rules red on HEAD (about 65 R1/R2, 150 R3, 36 R4, 22 R5 per the inventory); each later phase names the namespaces it turns green | none |
| **P1a** one entry, one request | `resources/seon/schemas/seon.render.edn`, `seon.render.value.edn`, `src/seon/render/value.clj`, `src/seon/render.clj`, patch to `src/seon/sci/eval.clj` (`shown-result`) | Publish `:seon.render/request` (value, optional `parts` annotations, optional `schema`, `output` + `:seon.render/line`, `outputs`, `profile`, `position`, `distance`, `options`, `direction`; custody `db`, `ctx`, `agent/id`, `repl/handle`, `cursor` all optional) and `:seon.render.value/shown` (tree, cuts, tokens, diagnostics, optional text/hiccup/parts). `show` = today's `project` over the request, with cuts collected in `tree`. `render-argument` derives its keys from the request schema and passes `:seon.render/options` whole. `prepare` renamed by one sweep (61 sites); `text`, `html`, `core-text`, `core-html`, `render-ai`, `render-html` delegate to `show`. | `prepare`'s name; `render-argument`'s 18-key list; `print-option-keys` | New `seon.render.value-test/show-answers-tree-text-and-cuts` (table over the six real shapes built through canonical writers: function entity, task, error, query, must-read, evaluation; asserts cut paths and continuations as data). New `seon.render-test/a-render-receives-request-options` (red: options dropped today). Extend `ai-values-are-laid-out-by-fipp-at-their-width`. Publication proven by reload on a branch of the live store. | not slower on all three |
| **P1b** allocation | `src/seon/render/value.clj`, `resources/seon/schemas/seon.render.profile.edn`, `seon.config.render.agent.edn` | §3's water-fill replaces `select-nodes`' queue; dials `whole-width` 24, `max-name` 256, `max-number-digits` 32, `sort-bound` 64, `max-depth`; `:tail` direction; every cut of a value with a handle carries a form. | the 16 functions listed in §3 "Allocation"; `scalar-bound`, `chunk-width`, `name-bound`, `nest-cap`, `sorted-bound`, `tuple-bound` | Rows in `seon.render.value-test` on cut data: the 1,050-character docstring of `seon.sci.eval/shown-result` has no cut at `[:seon.fn/doc]` at 1,024 and 512 tokens (red: `…+774`, `…+976`); the 4,956-tuple relation at 6,144 has exactly one cut, at `[]`, with `(drop k h)` and every shown tuple whole (red: 80 `[…2]`); the 24-attribute error at 512 shows all keys (red: `…2` dropped); a map whose keys alone exceed the budget has exactly one key cut; a 2,000-line `:out` shows its tail with `(subs h 0 m)`. Extend `documentation-body-is-whole-or-one-executable-elision`, `ai-realizes-only-the-members-it-prints`, `a-map-beyond-the-budget-costs-the-budget-not-its-size`, `the-text-stays-inside-its-charge-and-budget` (multi-line strings, nested maps). R4 green on `seon.render.value`. | query ≤ ¼ parent's allocation and lower time; function entity and small reply not slower |
| **P1c** nested dispatch | `src/seon/render/value.clj`, `src/seon/render.clj`, `src/seon/render/walk.clj`, `src/seon/db.clj`, `CLAUDE.md`/`AGENTS.md` line 582 | `make-node` offers `declared` at every reached keyword-lookup map and Var; a render's root lowered once, children fresh; compiled renders need no SCI ctx; declared selection requires contract fit (`m/validator`); `:seon.render/schema` tried first; render output retained in `seon.cache` under the `invocation` key with its own handle for cuts; `with-related` becomes the entity render's data at distance ≥ 1 and `related-html` a print node (R10); `render-database-identity-ai` moves to `seon.db`; identity-projection lookup moves into selection's declared stage. R12 amendment. | `walk-with-related` hook in the printer; `declared`'s `(zero? id)` and ctx precondition; the `make-node` comment; `:seon.render/rendering` except for self-delegation | New `seon.render.value-test/nested-values-select-their-pairs` (a vector of three stored errors prints each through its pair, asserted by `:seon.render.value/renders`; red: root only). New `seon.render-test/selection-requires-contract-fit` (the two-key partial error selects nothing, prints as data, no diagnostic; red: 4,000-token diagnostic). New `seon.render.value-test/held-evaluation-selects-by-declared-schema` (no `:db/id`, no ctx, `:seon.render/schema :seon.repl/emission`). New `seon.printer-elision-test/a-derived-face-cut-reads-the-derived-value` (a renamed field in render output; its cut runs on the retained handle; after eviction it answers gone). Existing `declared-producers-still-have-absolute-precedence` stays green. R1 green inside `seon.render.*`. | 40-evaluation transcript through `show` ≤ direct path (one-render finding 6: 43 vs 16 ms); small reply not slower |
| **P1d** one reply | `src/seon/repl.clj`, `src/seon/ai.clj`, `src/seon/sci/eval.clj` (patch while held), `src/seon/render.clj`, `src/seon/render/web.clj`, `src/my/program.clj` | `seon.repl/response` builds the reply map; `show` prints it with `[:seon.repl/out]` annotated `:tail`; `outputs #{:text :parts}`; `seon.ai/chat-messages` takes form and reply from `:parts`; `:seon.render.call/source-output?` retired, source renders return `seon.print/printed` content. | `reply-map`, `entries-in-order`, `ends-in-comment?`, `shown-output`, `beside-share`, `floor-text` (structural `show`), `evaluated-source` and `source-output?` with their schema member | New `seon.repl-test/a-reply-is-one-map-under-one-budget` (value and `:out` both over budget share it; shown tokens ≤ budget; asserted on parts). New `seon.ai-test/chat-messages-take-form-and-reply-parts`. New `seon.render.value-test/a-rendered-form-is-never-evaluated` (zero effects). Existing `the-reply-is-one-edn-map-whatever-the-value`, `printed-output-is-separate-from-the-value`, `a-response-carries-errors-as-data-and-late-diagnostics-beside-the-value`, `one-evaluation-emits-comment-prompt-and-response` stay green (declared goldens). R3 green on `seon.repl`. | a reply printed once, not twice: `show` calls per reply = 1, and reply time not slower |
| **P1e** history | `src/seon/context.clj`, `src/seon/render/transcript.clj`, `src/seon/render/sent.clj` | `model-context` sends each unit as `printed` of its saved emission with `:seon.render/position`; `:older` over `reference-tokens` collapses to the root's minimum (the reference); debug AI column, provider prompt and stored history read the same answer. | `exchange-ai`'s reference construction and its `core-text`; `exchange-text`; transcript text joins | New `seon.context-test/an-older-unit-over-its-budget-collapses-to-its-reference` (handle, tokens, `get-in` form as data). New `seon.context-test/history-is-one-derivation`. Existing `history-preserves-shown-text-without-applying-a-later-profile`, `a-failure-claim-shows-its-saved-printed-value-once`, `a-printed-value-shows-its-text-unchanged-wherever-it-sits`, `a-call-view-shows-its-sections-its-pairs-and-every-cut` stay green. R1/R3 green on `seon.context`, `seon.render.transcript`, `seon.render.sent`. | context build for a 40-unit history not slower than parent |
| **P1f** retire the second printer | `src/seon/render/value.clj`, `src/seon/print.cljc`, `src/seon/env.clj`, `src/seon/error.clj`, `src/seon/sci/admit.clj`, the 22 `core-text` files, `seon.render.edn` line 9, `seon.program.edn` line 247 | Edge sites (boot, fault, store, web emergency, operator, 6 scripts) call `show` with no custody and `:seon.render/line` or `:seon.render/ai`, budget from `seon.config/defaults`; non-edge sites (21) pass their value as data in their render's output; a Throwable is `throwable-value` data with frames as a vector the allocator cuts; `seon.error/render-chain-ai` drops `:frames`; Environment's `print-method` defers to the opaque node (R6); `artifact*` move to `seon.sci.admit`. | `text-limits`, `datum`, `bounded-text`, `causes`, `link-lines`, `throwable-text`, `core-profile`, `plain-projection`, `diagnostics-shown`, `unavailable`, `opaque` (becomes the node), `core-text`, `core-html`, `text`, `html`, `render-ai`, `render-html`, both schema attachments | New `seon.render.value-test/a-hostile-throwable-prints-as-class-and-identity` (a `getMessage` that throws). New `seon.render.value-test/a-printer-failure-prints-structurally-once`. Extend `a-lazy-result-that-throws-while-printing-is-shown-with-its-failure`, `terminal-emission-is-total-for-an-unknown-or-absent-face`. R3 green on `seon.render.value`, `seon.error`, `seon.fault`; the census's `printer-files` set loses nothing. | all three not slower |
| **P2** (parallel, file-disjoint, Sonnet, each with P0's rule as proof) | inventory groups | **faces** (P3 ids M062-M131, R11): the 22 text AI renders return data; renders calling renders by name converted; number and date layout through declared `:seon.config/display-unit`/`display-divisor` (O7). **errors** (P4, R1, R9): one scripted sweep of `pr-str`-into-message and scalar interpolation; `fit-fact-payload` stores data or a blob reference (`seon.blob/inline-or-stage`). **web** (P5, R8): pages pass subject, evidence, error and measurement to `show` with `:seon.render/html`; the typed child-slot node lands with its first page; `render-source-call` becomes a request. **rest** (P6, R2-R5, R7): scripts, the one encoder, top-k with totals and dials, test-runner report composition, log tail. | per group, each wrapper with its last caller | per group the named existing tests (`seon.render.entity-pairs-test`, `seon.render.faults-test`, `seon.error-test/diagnostic-construction-preserves-domain-members`, `seon.render.web-test`, `seon.render.transcript-test`, `seon.test-runner-test`) plus the R1 to R5 rule going green for the group's namespaces; a new row only for a behavior class no test covers | each page or report not slower |

**After P2:** rerun one-render's 600-evaluation comparison (594 matched before contract fit; expect no bare `…+N`),
Astra reviews against this plan, each finding reproduced before a fix at its root cause.

## 7. Risks the narrow check must reproduce on default before any task is filed

- The §9.1 partial-map selection (4,000-token diagnostic) and the `…+774` docstring cut reproduce in SCI on a
  disposable agent with the agent profile.
- `seon.render/invocation`'s cache key holds (producer, value digest, read evidence) and nothing per agent, so the
  retained render-output handle (§3) can reuse it.
- `malli.core/validator` on a render's declared input is cached on the compiled schema (`reference-code/malli`,
  `malli.core/-cached`), so contract-fit selection compiles once per schema.
- The 40-evaluation transcript path exists as a probe (one-render's harness) and runs on parent.
- `bin/lane status` at launch: `src/seon/sci/eval.clj` is still held or released.
