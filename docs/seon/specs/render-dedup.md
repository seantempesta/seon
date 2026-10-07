---
type: spec
status: draft
created: 2026-09-28
tags: [spec, render, web, transcript]
---
# Render and web: one pipeline, no second assembly

Owner (2026-09-28): "The web ui and the debug page (also a transcript render so why are these duplicated?) Don't kill
good features. Find duplication and get rid of it." Read at HEAD `587412b85` plus the working tree. Line counts are
`wc -l` today; every `file:line` was read. Anything not read is marked UNVERIFIED. Estimates are estimates until a
branch has done the cut.

Area today (`wc -l`): web.clj 4197, transcript.clj 2452, render.clj 1963, value.clj 1479, walk.clj 1316, print.cljc
810, repl.clj 631, hiccup.clj 525, web/jvm.clj 468, prompt.clj 460, context.clj 420, print/value.clj 124 = 14845
(plus data 224, ns 272, var 159, test 166, schema 187, block 96, route 60 = 1164 in sibling `render/*`).
`web/jvm.clj` is the outbound HTTP fetch/search tool, not a render surface: no finding, out of scope.

## 1. Feature inventory (keep all)

Web UI (namespace and agent pages):
- Namespace page, agent alias, canonical redirects: `page-response` web.clj:3195, `canonical-namespace-response` 3712.
- Page units derivation (walk, render call per unit, rank layout): `page-result` 469.
- Fleet oversight at root: `::fleet-oversight` unit inside `page-result` (515).
- Message bar (human to agent): `message-bar-html` 303; POST `inbound` 3024, `inbound-response` 3764, `received-response` 3813.
- Shell, surface ids, stream strip: `shell` 341, `surface-html` 412, `stream-strip-html` 439.
- Live updates over SSE with retained packages and patches: `feed` 2826, `render-step` 2577, `render-pass` 2356, `next-package` 1834.
- Streaming partial of an in-flight turn: `unsettled-stream?` 2040, `stream-strip-html`.
- Health page: `health-page-result` 2065, `health-response` 3740.
- Create-owner control and context actions: `create-owner-form` 3158, `create-owner-response` 3774, `context-action-response` 3790, `change-context` 2469.
- Drill endpoint (open a cut value from its path): `data-response` 3852.
- Static css/js, same-origin guard, failure page (500 through the error pair): 3939, 3957, `failure-response` 3964.
- Agent header with turn controls: `transcript/render-agent-header` 1287.

Debug page, entity view (`?subject=`, the namespace debug tab):
- Header (viewer, subject, snapshot, output): `debug-header-html` 812; program identity 802.
- Found values with paths, attribute descriptions, links: `debug-found-value` 1248, `debug-found-values-html` 1329.
- Read dependencies of a render (Datahike dependency plan): `debug-read-dependencies-html` 1014.
- Applicable renderers and the selected renderer's contract and definition: `applicable-renderers-html` 1181, `debug-selected-renderer-details` 1090, `debug-renderer-contract` 982.
- Render experiments (run a chosen producer over the subject, AI and HTML face): `debug-render-experiment` 1482, `selected-unit-experiment` 1158, `experiment-preview-html` 1065.
- Entity ref graph (cytoscape JSON): `graph-model` 898, `debug-graph-html` 947.

Debug page, agent view (`?sent`, `?record`, dispatched in `debug-response`): the header, one context view and the
agent record. The context view is `sent-context-html`: the turn's own context function `seon.cluster.prompt/prompt`
printed through the value printer's HTML face, computed when the reader opens it. The turn session, outline, turn
ledger, problem panel, captured-prefix check and the walk graph page (`?n=`) are deleted (S2a).

Transcript consumers (who reads the same evaluations):
- Agent's next-turn context (history units): `transcript/history-entries` 793, called from `cluster/agent.clj:857`; priced in `prompt/history-contributions` prompt.clj:189.
- Run, history and runtime AI/HTML faces attached by schema: `render-run-ai` 762 / `render-run-html` 868 (seon.turn.edn:8), `render-history-ai` 971 / `-html` 977 (seon.turn.edn:67), `render-runtime-ai` 1014 / `-html` 2396 (seon.runtime.edn:7).
- Whole transcript AI text for eval drives: `eval/drive.clj:280` calls `transcript/render-ai` 713.
- Single evaluation face (`:seon.render/ai|html` of the evaluation schema): `repl/render-ai` 571, `repl/render-html` 596.
- Turn's own in-flight text: `turn.clj:2215` builds `repl/text` from admitted form plus evaluation.
- MCP reply: `repl/response` 349 (UNVERIFIED which MCP function calls it; not read).

## 2. Duplication map

Ranked by lines that can go and by defect evidence.

**D1. The AI context is derived inside the web namespace, with a second retention system (~300 lines, wrong owner).**
`render/acquire-context!` (render.clj:1724) resolves `seon.render.web/derive-context!` (render.clj:49-50, web.clj:2502)
by `requiring-resolve`. The agent's prompt path therefore loads and depends on the web page owner. `derive-context!`
(2502-2575, plus `change-context` 2469, `history-segments` 2461, `candidate-call-ids` 1933) holds a retained-call and
read-evidence cache (`::ai-calls`) that repeats the page cache's (`::calls`, `derive-page` 2081, `acquire-root` 1974,
`refresh-root` 1997, `retained-interest` 1921): the same evidence-current test (`same-invocation-evidence?`,
`retained-program-current?`, `read-evidence-current?`) written twice. Survives: one retention owner in `seon.render`
keyed by call identity; the AI history call and page unit calls are two producers in it, not two caches. The debug
ledger's data was also computed inside this AI acquisition behind a request flag; S2a deleted the ledger and the flag.

**D2. One evaluation entity becomes an emission three ways (~150 lines).**
(a) `transcript/receipt-entry` 258 then `transcript/emission` 470-508 (history, prompt, `render-ai`);
(b) `repl/entity-emission` repl.clj:487-569 (`repl/render-ai|html`);
(c) `turn.clj:2215` merging form and evaluation maps for `repl/text`. (a) and (b) are near-identical `select-keys`
lists plus handle, changed-since and namespace resolution; (b) pulls the namespace and renderer symbol from the
database when the caller forgot them. Survives: (b) `repl/entity-emission`, taking the pulled entity and its
database; `transcript/emission` and the `receipt-entry` re-shaping disappear: the history pull selects the evaluation
attributes once (`receipt-selector` 40 already lists them) and the entity flows to `repl/text` unchanged. This is the
producer of red 1 (section 5; the session page that omitted the database is deleted).

**D3. The debug page re-assembled what the agent saw, four times (deleted in S2a).**
The turn session, the outline, the ledger and the sent-context panel each acquired the context again, each wrapping
the acquisition in its own `catch ExceptionInfo`. Now one view remains, `sent-context-html`, calling the turn's
context function once when opened; a thrown refusal reaches the web handler's entrant boundary and a returned one prints
as the refusal value. S4 replaces the context function and the view's one call with it.

**D4. Two graph pages (the walk page deleted in S2a).**
The entity ref graph (`graph-model`, `debug-graph-html`) stays; the walk page (`context-graph-model`,
`graph-debug-html`) is gone, with its legend and its grouping and windows.

**D5. Two "AI face beside HTML face" experiments (one deleted in S2a).**
`debug-render-experiment` / `experiment-preview-html` / `selected-unit-experiment` (entity page) remain; the walk
page's `face` closure went with `graph-debug-html`.

**D6. Value bounding in four places (~130 lines).** `print.value/bounded-text` print/value.clj:54, delegate
`value/bounded-text` value.clj:76 (its docstring says it "then goes"), `value/floor` 86 (delegate of `throwable-text`),
and `error.clj:356 bounded-text` / `853 bound-text` (UNVERIFIED whether these are the same operation; both named
bounded). CLAUDE.md: one clipping spot. Landed (printers-round2, 2026-10-07): `seon.print.value` folded into
`seon.render.value` as the core printer's private last resort; `core-text`/`core-html` print with no render,
database or config, `text`/`html` are the total unit entries; the delegates are deleted and every caller converted.

**D7. Transcript AI/HTML faces restate one selection (~250 lines).**
`render-ai` 713 and `render-html` 834 share `projection` 697 (good). But `render-run-ai|html`, `render-history-ai|html`,
`render-runtime-ai|html`, `render-agent-header` and `session-header` 1237 each re-derive turn entities
(`turn-entities` 1131, `debug-turn-selector` 1115, `history-run-selector` 898, `runtime-selector` 1008: four
selectors over turns) and time/duration formatting (`runtime-time` 1055, `runtime-duration` 1063). Survives: one turn pull with
one selector and one turn-link function in `seon.render.route`.

**D8. Page fragment path vs debug fragment path in the web proc (~250 lines).**
`page-result` 469 (namespace page) and `debug-page-result` 1728 both: walk request (`walk-request` 3178), render call
per unit, `surface-html`, retained calls, captured calls, interest publication. `derive-page` 2081 branches on
`debug?` to pick one. Survives: `page-result` parameterised by subject and output; the debug entity page is a page
whose units are `debug-found-value`s.

**D9. Value printer internals (mostly single, small residue).**
Verified single pipeline: `value/prepare` builds the selected tree once and emits text and hiccup from the same node
stream (`print/emit-both` print.cljc:646; value.clj:1332-1339 puts `:seon.print/text` and `:seon.print/hiccup` on one
projection). No second HTML walker here. Residue: `render-ai-data` / `render-html-data` (value.clj:1381-1391) are
accessors of a map key; `elided-text` (337) / `render-elision-ai` (352) / `form-text` (328) are three functions for
the one cut text (UNVERIFIED which have external callers); `print.cljc` still carries generators
(`node-generator` 110, ~150 lines of test-only generative code in a source namespace; UNVERIFIED users).
`hiccup.clj` (525) is a hand-written hiccup-to-string emitter; whether a maintained library (`hiccup2`, `replicant`)
in the deps could replace it is UNVERIFIED (deps.edn not read).

**D10. Per-schema branches in printers.** Searched value.clj `classify` (428) and `declared` (728): schema-specific
branches were not found in the ranges read; `classify` is by value kind. UNVERIFIED for the unread middle of
`prepare` (1240-1330). Not a confirmed duplication.

## 3. Target design

One pipeline, per CLAUDE.md "Presentation": **select units, render function per consumer, value printer, text or hiccup.**

1. **Assembly owner (`seon.render`, with `cluster/prompt` composing).** `context-view`, a Malli-declared map
   `:seon.render/context-view {:units [...] :segments [...] :text str :frame ... :refused nil-or-error-value}` built by
   `seon.render/acquire-context!` (kept; contract unchanged) which now does the work itself. It calls
   `render.walk/history` (walk.clj:840) and the retention helpers that today sit in web.clj. `derive-context!`
   (web.clj:2502) is deleted; the `requiring-resolve` at render.clj:49 goes. Contract
   `[:=> [:cat :seon.render/context-request] [:or :seon.render/context-view :seon.render/context-error]]`.
2. **Units.** History units are evaluation entities pulled once with `receipt-selector`; text comes from
   `repl/text` over `repl/entity-emission` (D2). `transcript/history-entries` becomes a 20-line wrapper or is deleted
   and `cluster/agent.clj:857` calls `acquire-context!` units. `render-run-*`, `render-history-*`, `render-runtime-*`
   stay as schema-attached faces but share one turn pull and one link function (D7).
3. **Retention.** One cache kind in `seon.cache` keyed by call id and read-evidence (CLAUDE.md "one central cache");
   `::calls` and `::ai-calls` merge. Contract of the surviving check: `same-invocation-evidence?` (render.clj:994).
4. **One view of the context.** The debug page's `sent-context-html` calls the turn's context function when opened
   and prints the composed value through `seon.render.value/html`; it holds no second assembly. S4 changes the Var it
   calls, in the same commit as the turn's.
5. **Debug page = the same units.** The view shows the composed map the turn hands the model. Per-turn ledger
   measures are gone; `::transcript/ledger?` and `::transcript/ledger-data` are deleted from acquisition.
6. **Faces.** Every consumer face is `render-call` (render.clj:1496) selecting by the existing three-step rule; both
   faces of one value come from one `value/prepare` (print.cljc emit-both). Graph and experiment pages use it for
   the AI/HTML pair (D4, D5).
7. **One graph.** The entity ref graph is the only graph page (S2a deleted the walk page).
8. **One floor.** The value printer's core (`seon.render.value/core-text`, `core-html`), its projection-free text
   private to it; delegates in value.clj deleted.
9. **Web proc.** `page-result` handles namespace and debug subjects; `feed`, `render-step`, `render-pass` unchanged in
   role but without the context derivation. `web.clj` splits by owner: routes/handlers, proc/feed/packages, page and
   debug units (three files, none over ~700 lines).

Cost target: opening a debug page performs one acquisition (today at least two, three with `?sent`; measured: not
measured). Session render is O(entries).

## 4. Deletion estimate

| file | now | after | main cuts |
|---|---|---|---|
| render/web.clj | 4197 | ~1900 | D1 context derivation (~300), D4 graph (~200), D5 experiments (~150), D8 debug fragment path (~250), split-out unchanged code moves not deleted; sent-context (~20) |
| render/transcript.clj | 2452 | ~1300 | D2 receipt-entry/emission (~150), D3 three acquisitions and catches (~150), D7 selectors/urls (~200) |
| render.clj | 1963 | ~1700 | takes over cache helpers (+~120), drops requiring-resolves (-~30); net small |
| repl.clj | 631 | ~560 | absorbs emission callers; `turn.clj:2215` no longer merges maps |
| render/value.clj | 1479 | ~1400 | delegates (-15), accessors (-10); middle of `prepare` unread |
| print.cljc | 810 | ~650 | generators to test (-150) if unused in src (UNVERIFIED) |
| print/value.clj | 124 | 124 | the surviving floor |
| hiccup.clj | 525 | 525 or ~100 | library replacement is an open question |
| walk.clj, prompt.clj, context.clj, web/jvm.clj | 2664 | ~2600 | no duplication found in the ranges read |
| **total** | **14845** | **~10950** | **~3900 lines** |

Honest limit: this design keeps the features that remain after S2a (which deleted the turn session, outline, ledger
and problem panel) and still does not reach the area's 1.5k budget; that needs a redesign of the value printer and
walk.

## 5. Known reds in this area, and which the design dissolves

Verified by reading:
1. `error/render-html` 500 body drops the class: `failure-response` (web.clj:3964) passes the diagnostic to
   `error/render-html` whose body is built from `rendered-reply`/`reply-html` (error.clj:2301-2323); the kicker shows
   `:seon.error/operation` (the handler symbol), not the throwable class. Partly dissolved by D6 (one error face);
   the class needs a line in `rendered-reply` (not part of this dedup). Root cause path not traced past error.clj:2323.
   UNVERIFIED which test asserts the class.

UNVERIFIED (named in the assignment, not read or reproduced):
2. next-turn fixture opening (`web_debug_test`): likely tied to `@turn-opening-db` (render.clj:1731) in
   `acquire-context!`; D1/D3 make the opening a field of the view, but the failing fixture was not run.
3. `agent-render-profile` budget 3200 < 3238: `agent-render-profile` (render.clj:65) only copies
   `:seon.config.render.agent/token-budget`; the 3200 default and the 3238 prompt were not located (grep found no
   `3200` in `resources/seon` and no `token-budget` assertion in the web tests). Not dissolved by this design; it is
   a config default versus a fixture size.
4. Reconnect tests 23-42 s: `feed` (2826) and `await-feed-package!` (2798) were not timed. D1's merged cache and
   D3's single acquisition remove per-connection context derivations (hypothesis, unmeasured); the rest is unknown.

## 6. Open questions for root

1. Budget. Decided: S2a cut the ledger, outline, session and problem views; the remaining target is a value-printer
   and walk redesign, not a feature cut.
2. Where does the retention cache live: `seon.render` (recommended) or `seon.cache` directly, given CLAUDE.md's
   "every Seon cache is a kind in `seon.cache`"? The current `::calls` and `::ai-calls` are state in the shared
   cache atom (`render/shared-cache` render.clj:1695); confirm that atom has a reason (CLAUDE.md "atoms are a smell").
3. May `transcript/history-entries` be deleted, given `bootstrap.clj:247` names it in a source check and
   `resources/seon/tasks/seon.mcp.edn:9` lists it in `:must-read`? Both convert in the same commit.
4. `hiccup.clj` replacement by a library: allowed? Needs a deps check and the owner's rule on new dependencies.
5. Are `render-runtime-*` and `render-history-html` used by any live page? Only schema attachment and tests were
   found (test/seon/render/transcript_test.clj:1068, context_nits_test.clj:27); if no page shows them they are
   deletion candidates.
6. Ownership: this touches web.clj, transcript.clj, render.clj, repl.clj, turn.clj:2215, cluster/agent.clj:857, error.clj
   and schemas seon.turn/seon.runtime edn. Root must assign one owner per file; the order that keeps HEAD loading is
   D6 (floor), D2 (emission), D1 (context owner), D3 (view), then D4/D5/D8 (web only).

## 7. Root rulings (2026-09-28)

- R1 Budget: **(a)**, keep every feature and delete the duplication now (~3.9k). The rest of the area budget comes
  from the area rewrite (value printer and walk) later, from its own reviewed spec. No feature cut without the owner.
- R2 Cache: **`seon.cache`** kinds keyed by content (CLAUDE.md "One central cache"); `render/shared-cache` must name
  its reason or go.
- R3 Yes: delete `transcript/history-entries`; convert bootstrap.clj:247 and the seon.mcp task's :must-read in the
  same commit.
- R4 hiccup.clj: only if a library already on the classpath does the job (check deps.edn); no new dependency in a
  dedup slice.
- R5 Verify live use with a probe on the running pages; delete `render-runtime-*` / `render-history-html` if no page
  shows them.
- R6 Order as given: D6, D2, D1, D3, then D4/D5/D8. One lane per slice, one owner per file, each slice after the
  issue cut releases the render call sites.
