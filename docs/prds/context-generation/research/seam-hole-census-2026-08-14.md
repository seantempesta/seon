---
type: research
status: current
tags: [research, render, context, audit, enforcement]
---

# The seam-hole census — every path to an output seam, 2026-08-14

Purpose: the owner's diagnosis is that the ugly outputs came from HOLES —
places where a value reaches an output seam WITHOUT passing through the one
pipeline. This document is the complete backwards trace from the two seams,
so the one-renderer design can name choke points instead of patching sites.
Every row carries `file:line` read at the bytes on branch
`context-generation-drive` (working tree, 2026-08-14). No production edits
were made.

## 0. Method, and what counts as a hole

The pipeline stages, abbreviated for the tables (PRD §0):

| tag | stage |
|---|---|
| **admit** | storage admission — declared caps, honest elisions (`seon.sci.admit`) |
| **derive** | derivation — history unit / walk membership, from facts |
| **select** | projection selection — the four-step chain in `seon.render/producer` |
| **face** | declared render output, or the derived floor |
| **print** | the value printer — `seon.print/fit` + `emit-text`/`emit-both` |
| **assemble** | assembly — page hiccup; for a model call, member selection against the prompt budget |
| **deliver** | provider wire / SSE morphs |

A **hole** is any code path where a value becomes seam bytes while skipping
one or more of those stages. Three shapes recur:

1. **Direct printing** — `pr-str` / `str` / `format` / `str/join` produces
   the final bytes; the printer never runs, so no profile, no elision value,
   no requery identity.
2. **Parallel assembly** — a namespace builds its own text or hiccup
   document beside the engine (its own budget loop, its own elision
   phrasing, its own placeholder).
3. **Early serialization** — hiccup is turned into a STRING before the seam,
   after which nothing downstream (lint, fit, dedup, morph diffing on
   structure) can see it.

Two structural facts frame everything below.

**The documented AI assembly is dead code.** `seon.render.walk/prose`
(`src/seon/render/walk.clj:606-709`), whose docstring says "THE
`:seon.render/ai` ASSEMBLY", has NO production caller — repo-wide the only
callers are its own 1-arity (`walk.clj:634`) and three tests
(`test/seon/render/transcript_test.clj:47`, `test/seon/error_test.clj:745`,
`test/seon/background_test.clj:74`). The live prompt is assembled in
`seon.render.web/history-text` (`src/seon/render/web.clj:1340-1350`). One
consequence is a live defect, not a stylistic one: `seon.effect/context-suffix`
(`src/seon/effect.clj:724-812`) — the background-work guidance, pending-effect
list, and long-call advice — is reachable ONLY from `prose`
(`walk.clj:705`), so **no live prompt has ever carried it**. That is
absence-as-health at the byte level: the guidance is written, tested, and
never delivered.

**The declared sink census can see six functions.** `:seon.fn/external-sink`
is declared at exactly six sites repo-wide: `src/seon/render.clj:538` and
`:557` (`render-ai`, `render-html`), `src/seon/render/web.clj:1569`
(`write-package!`) and `:2001` (`page-response`), and
`src/seon/test/runner.clj:363, 1078` (codec). Everything else in the tables
below is invisible to `seon.fn/output-path-report` by construction (§3).

---

## 1. Seam A — bytes that become `:seon.cluster.prompt/text` and MCP tool results

The live AI byte path, end to end:

`seon.render.walk/history` (`walk.clj:893-926`) → per-entry
`:seon.render.history/bytes` (`walk.clj:885-888`) →
`seon.render.web/append-history` (`web.clj:1286-1338`) →
`history-segments`/`history-text` (`web.clj:1340-1350`) →
`context-pass` (`web.clj:1352-1396`) → `seon.render/acquire-context!`
(`render.clj:713-744`) → `seon.cluster.prompt/acquire-within-budget`
(`prompt.clj:226-277`) → `seon.cluster.loop` (`loop.clj:1410`) →
`seon.ai/request-body` (`ai.clj:652-667`).

| # | Hole | file:line | What crosses | Stages skipped | Proposed enforcement |
|---|---|---|---|---|---|
| A1 | The prompt's per-entry byte unit is built by `str` + `pr-str`: `(str ns "=> " (pr-str form) "\n" printed-value)` | `src/seon/render/walk.clj:885-888` | every history entry's form half | print, assemble | The history entry must carry a NODE (form + printed value as admitted nodes) and be emitted by the one printer; a `:seon.render.history/bytes` key that is a bare string is the thing to delete |
| A2 | `history-text` = `(apply str (history-segments …))` with `"\n\n"` glue | `src/seon/render/web.clj:1340-1350` | the whole prompt | assemble (no member-level budget; no elision value for anything omitted) | Assembly becomes a typed operation over member values (blocks + costs), returning text plus the elision chips it dropped; string concatenation of entry bytes becomes unconstructable |
| A3 | The budget is applied by RE-WALKING at a lower distance, not by member selection: `(recur (dec distance))` on `:over` | `src/seon/cluster/prompt.clj:226-277` (loop at `:228`, decrement at `:265-266`) | whole neighbourhood branches | assemble (member-level elision) | The seam-A budget contract: input is a vector of costed members, output is (retained members, elision chips); a re-derivation loop cannot express that shape |
| A4 | The reminder tail is appended by `str` after budgeting | `src/seon/cluster/prompt.clj:212-224` (`:222-223`) | the reply-medium reminder | select, face, print, assemble (appended after the budget check, then re-measured) | Reminder becomes an ordinary assembly member with its own cost, not a tail |
| A5 | `seon.render/walk` — the agent-facing walk function — returns `(pr-str {…units…})` | `src/seon/render.clj:971-978` | the entire units map, incl. every rendered unit string and repl-state | print (no fit, no profile, no elision), assemble | The agent-facing entry must return through `print/emit-text` of an admitted node; `pr-str` at an agent boundary is exactly the graph-query subject |
| A6 | `walk-error` returns `(pr-str {…error map…})` | `src/seon/render.clj:839-843` | a flat error value | select, face, print | Error values render through the pipeline like every other value (PRD §2) |
| A7 | Unbounded agent print output: `evaluation-output` concatenates the raw `StringWriter` contents with no cap — the namespace docstring at `:69` claims it is "bounded by the same `max-string` cap", which is false | `src/seon/sci/eval.clj:299-306`, writer bound at `:2071-2072`, stored at `:1822-1824`, `:1853-1855` | everything the agent's form printed | admit (no cap at all), print | The storage-admission seam must own this string exactly as it owns result values (`print/admit-string`, `admit.clj:236-241`); the false docstring is a separate defect |
| A8 | Gate/advisory lines are `str`-appended onto that same output field | `src/seon/cluster/loop.clj:233-240`; advisory joined at `src/seon/sci/eval.clj:2163-2166` | test-gate and accretion advisory prose | select, face, print | These are separate facts with their own faces, not text glued into a receipt attribute |
| A9 | `render-receipt-ai` returns the STORED `result-edn` string verbatim, `str`-joined with output and error | `src/seon/cluster/run.clj:2001-2025` | every eval result reaching the prompt | print (the consumer's profile never re-fits the stored bytes) | A receipt's result is a stored NODE; the face returns the node and the printer fits it for this consumer |
| A10 | Run state as generated English sentences ("It completed.", "It was interrupted at form N — …") | `src/seon/cluster/run.clj:1913-1966` | run disposition facts | face (prose substituted for data — PRD rip-out #3) | Attribute face over the run's own attributes; prose only under declared instruction entities |
| A11 | Message face as a sentence: `(str "Agent " from " said" … ": " content)` | `src/seon/cluster/message.clj:459-471` | message content + sender/recipient | face | Same as A10 |
| A12 | `seon.error/render-ai` flattens arbitrary nested evidence with `pr-str` per attribute | `src/seon/error.clj:1013-1020` (`evidence-text`), `:1023-1030` | every error value's evidence subtree | select (nested values never re-enter selection), face, print | Error evidence is ordinary data rendered by the nested walk; a `pr-str` join inside a face is the graph-query subject |
| A13 | `seon.render.ns` re-implements the whole pipeline: `full-ai-text` `str/join` + `pr-str`; `compact-ai-text` `pr-str` of a vector; `minimal-ai-text` `pr-str`; `budgeted-ai` its own budget loop; `referenced-schema-cap 40` leaking into prose three times | `src/seon/render/ns.clj:415-437`, `:454-467`, `:478-488`, `:490-506`, `:398-413`, cap at `:95`, `:187`, `:414`, `:452`, `:541` | every namespace page's agent context | print, assemble (second budget owner) | One of the two full re-implementations of `fit`; deletion + floor is the enforcement. A graph query "who calls `tokens/estimate` outside the two seams" names it |
| A14 | `seon.render.transcript` is the other re-implementation: `ai-output` `str/join "\n\n"` (`:753-762`), its own `fits?`/`projection` budget loop (`:806-840`), `marker-text` as a fourth independent elision phrasing (`:748-751`) | `src/seon/render/transcript.clj:748-762`, `:792-840` | the transcript half of every agent context | print, assemble (third budget owner) | Same as A13 |
| A15 | A render function RAISES the budget it was handed: `budget (max requested minimum)` | `src/seon/render/transcript.clj:832` | the transcript's token allowance | assemble (the consumer's budget is not authoritative) | Budgets travel down only; a face that returns more than its allowance is a contract violation the stage boundary refuses |
| A16 | HTML markup bytes evict prompt content: `output-tokens` takes the MAX of the AI estimate and the serialized-HTML estimate, and that drives entry inclusion | `src/seon/render/transcript.clj:792-799`, used by `fits?` `:801-803` | presentation overhead reaching into acquisition | assemble (the model's budget is spent on `<pre>` and `data-*` attributes) | Cost is measured on the projection being assembled; measuring the twin is unconstructable once assembly takes members, not text |
| A17 | The `:summary` detail tier is a provable no-op — all four text render functions ignore their `detail` argument, so `best-summary` re-tests an identical candidate | `src/seon/render/transcript.clj:584`, `:625`, `:636`, `:667`; `best-summary` `:804-807` | nothing — the API shape lies | face | Delete the tier; a parameter no implementation reads is a graph-detectable defect |
| A18 | `receipt-printed-value` recovers the value half by CHARACTER SURGERY on rendered text (`subs` past the prompt prefix) | `src/seon/render/transcript.clj:890-894` | every receipt entry in history | derive (the value is re-derived by slicing bytes instead of read from facts) | The entry carries form and value separately from facts; slicing rendered text is the defect |
| A19 | `history-entries` runs a fourth budget loop that DROPS entries with no elision marker at all | `src/seon/render/transcript.clj:958-968` | whole transcript entries | assemble (silent absence — the canonical failure class) | Every drop emits an elision chip; "returned fewer members than admitted, with no elision" is assertable at the assembly boundary |
| A20 | The floor printer bare-truncates by default: `default-options` carries `::length`/`::level`, and the emitter then emits literal `"..."` / `"#"` tokens with no count, path or requery | `src/seon/print.cljc:250-254`, `:260-270`, tokens at `:383`, `:392`, `:430`, `:453`, `:488`, `:497`, `:542`, `:557`; only `src/seon/render/value.clj:503-507` nils them out | any value printed by a direct `emit-text` caller | print (the honest-elision half) | Delete the `::length`/`::level` options; the ONLY structural cut is `fit`'s elision node. Then every `emit-text` caller is honest by construction |
| A21 | Direct `emit-text` callers that therefore get bare truncation: MCP tool text, `doc`/`dir` contract lines, the floor's own layout path | `src/seon/cluster.clj:274-281`; `src/seon/sci/eval.clj:1018-1030`; `src/seon/render/value.clj:372`, `:389-390`, `:430` | MCP results, injected REPL documentation, registered-shape layouts | print (as A20) | Follows from A20 |
| A22 | `doc`/`dir` build agent-visible text by `str` + `println` splicing | `src/seon/sci/eval.clj:1032-1047` (`role-contract-lines`), `:1049-1071` (`arity-contract-lines`), `:1105-1130`, `:1133-1153` | every injected documentation face | select, face, print | The documentation face is a render output over program rows; `println` of hand-built lines is the subject of the same graph query as A5 |
| A23 | Opening-episode admission runs its own token budget over `entry-source` strings | `src/seon/bootstrap.clj:395-409` | which generated opening forms exist at all | assemble (a fifth budget owner, acquisition-scoped) | Legitimately a different decision (membership, not presentation), but it must be the SAME budget contract; today it is a private loop |
| A24 | `activation-refusal` hand-rolls `" … N more."` prose beside a correct elision value it already built | `src/seon/cluster/source.clj:30-57` (prose at `:52-55`, elision value at `:38-49`) | operator-facing refusals | face, print | One elision representation; the prose sentence is dead weight the printer already owns |
| A25 | `dead`: `prose` assembles `;; d<N> · <lookup>` headers, `(pr-str output)` for non-string outputs, a hand-built `;; branches-elided=N` footer and its own elision-token count | `src/seon/render/walk.clj:636-709` (`:653-658`, `:686-694`) | nothing today — no production caller | — | DELETE with `context-suffix`'s only call site; re-home the background guidance as an assembly member (see §0) |
| A26 | MCP envelopes are `pr-str` documents; form previews are `subs` + `"…"` | `script/seon/dev/mcp.clj:118`, `:399`, `:580`, `:591`, `:728`, `:807` | every MCP tool result | select, face, print | Dev tooling, outside `src/`, but it is a real agent-facing seam: it should consume the same projected node the cluster already produces (`seon.cluster/mcp-project`) rather than re-printing |

**Also on this seam, not a hole but a hazard:** `fit`'s token check
(`src/seon/print.cljc:931`) uses the shipped calibration while
`src/seon/cluster/prompt.clj:196` threads the cluster's observed
calibration, so the floor and the budget gate can disagree about what a
token is.

---

## 2. Seam B — hiccup and strings that become HTTP responses and SSE patches

The live HTML byte path: `page-result` (`web.clj:512-680`) → `surface-html`
(`web.clj:259-281`, serializes to STRING here) → package framing
(`web.clj:920-946`) → `write-package!` (`web.clj:1557-1600`) / `page-response`
(`web.clj:2000-2036`).

| # | Hole | file:line | What crosses | Stages skipped | Proposed enforcement |
|---|---|---|---|---|---|
| B1 | The banned `renderer unavailable` placeholder is CONSTRUCTED IN FOUR PLACES as a hiccup literal | `src/seon/render/walk.clj:586-590`; `src/seon/render.clj:790-792`; `src/seon/render/web.clj:278-280`, `:579-581`, `:622-623` | every render failure | face (a swallow wearing a label — PRD §2 bans it) | The dev dial panics; in prod the error VALUE renders through the pipeline as an error card. No literal placeholder string exists anywhere: a graph query over string literals in render namespaces, plus `seon.render.lint`'s existing placeholder-class check run for real (§3) |
| B2 | Hiccup is serialized to a STRING per unit, long before delivery | `src/seon/render/web.clj:259-281` (`hiccup/->string` at `:275`) | every page unit | assemble (from here on the page is opaque text: no lint, no structural dedup, no fit) | Serialize once, at delivery. Packages retain hiccup; `frame-bytes` (`web.clj:909-918`) is the only `->string` caller |
| B3 | The document shell is `(str "<!doctype html>" (hiccup/->string …))` | `src/seon/render/web.clj:181-236` (`:195-197`) | every page | assemble | Same as B2 — the shell is hiccup until the response |
| B4 | Re-splicing serialized fragments back in as `hiccup/raw` | `src/seon/render/web.clj:848`, `:2032`, `:2034`, `:2058`, `:2061` | whole page bodies | assemble (structure laundered through strings and back) | Follows from B2: with structure retained, `raw` is unnecessary at these sites |
| B5 | `session-timeline` — a second content renderer with private `db/pull`s, `(pr-str form)` for sources, `[:pre [:code result]]` for AI text, and a hand-built "reply reduced into N forms" sentence | `src/seon/render/web.clj:451-504` (`:484`, `:497-499`, `:501-503`) | the agent page's turn structure | select, face, print | One derivation, three faces (PRD §1); the turn chrome is an HTML face over receipt facts, not a private renderer |
| B6 | That second renderer is spliced into the specialist's output by `pop`/`conj` surgery, mirrored on the other side | `src/seon/render/web.clj:506-510`; `src/seon/render/transcript.clj:1006-1008` | a block's own hiccup vector | face (a caller edits a face's return value) | Blocks compose as blocks; mutating another block's vector is the defect a stage-boundary contract catches immediately |
| B7 | A second Clojure lexer, and `code-toggle` emitting BOTH renderings with one CSS-hidden | `src/seon/render/web.clj:316-398` (`highlighted-source` `:349-388`, `code-toggle` `:390-398`) | every displayed source form | face (the print faces already classify this — `print.cljc:123` `face-class`) | One highlighter (the archaeology revival, PRD §4); duplicate classification is a graph-detectable duplicate mechanism |
| B8 | The agent transcript's HTML is the AI TEXT in a monospace box; `floor-text` calls `value/render-ai` for BOTH outputs — there is no `floor-html` | `src/seon/render/transcript.clj:774`, `floor-text` at `:550-557` | the main content of every agent page | select, face (the `/html` projection is never chosen) | The `/html` projection is required at this boundary; a face returning AI text where hiccup is declared is the stage-contract violation of PRD §2 |
| B9 | Family HTML twins that only wrap their AI sentence: `[:article [:p text]]` | `src/seon/cluster/run.clj:1968-1975`, `:1984-1991`; `src/seon/cluster/message.clj:474-479` | run, form and message blocks | face (the HTML face IS the AI face) | Same as B8; these dissolve when A10/A11 become attribute faces |
| B10 | Hand-written `<dt>/<dd>` twins keyed on literal map keys, instead of `value/declared-attributes` which already derives the ordered identity-first list | `src/seon/config.clj:76-90`; `src/seon/cluster.clj:182-187`; `src/seon/effect.clj:104-112`; `src/seon/db.clj:1944-1952` (this one dumps every datom uncapped); the derivation at `src/seon/render/value.clj:215` | config, cluster, effect and database cards | face, print (uncapped at `db.clj:1944-1952`) | Adding a schema attribute must not silently omit it from a face: attribute faces derive their rows |
| B11 | The floor's registered-shape layout builds `[:dl]`/`[:ol]` out of `emit-text` STRINGS and discards the `HiccupSink` | `src/seon/render/value.clj:413-438` (`map-html` `:401-411`, callers `:387-390`, `:430`) | every value matching a registered schema | print (the structural browser is skipped exactly when the shape is known) | The tee'd sink is the mechanism; a layout path that reads only the text half is the defect |
| B12 | `fit` measures and chops `pr-str` of the HTML tree — so a correctly elided over-budget page degrades to a mid-string chop of its own markup | `src/seon/print.cljc:825-838` (`projected-text` `:829-832`, `bounded-text` `:834-838`), invoked from `src/seon/render.clj:517-532` | any over-budget HTML block | print (the honest-fit half) | PRD §1: HTML has NO budget. Deleting the budget from the HTML path deletes this hole outright |
| B13 | `TextSink -fragment` `pr-str`s a projected fragment whenever the output is not `/ai` | `src/seon/print.cljc:106-112` | HTML fragments below root | print | PRD rip-out #2 |
| B14 | The distance-cap elision marker is suppressed for HTML only: the prompt says "connections elided", the page silently shows nothing | `src/seon/render/walk.clj:596-597` | omitted neighbours | face (absence-as-health) | Elisions are the same value in both projections; a projection-conditional omission is exactly what a face-equivalence property (PRD §6.8) fails on |
| B15 | Live provider text goes into `[:pre [:code text]]` with no admission or printing | `src/seon/render/web.clj:286-301` (`:299-300`) | streamed model output | admit, select, face, print | Streaming partials are values; they render through the same path with a streaming profile |
| B16 | `reasoning-disclosure` builds its summary with `(str first-line "…")` | `src/seon/render/transcript.clj:700-708` (`:705`) | reasoning text | print (a hand-made ellipsis, not an elision value) | Structural windowing is the printer's job |
| B17 | The raw AI prompt is pasted into HTML as `[:pre prompt]`, assembled by `str/join "\n\n"` | `src/seon/render/web.clj:787-817` (`:803`), `:827-836` (`:835`) | the whole debug page's primary content | select, face, print | The debug view is a declared HTML face over the same history units (PRD §1.5); re-joining bytes in the web layer is the parallel path |
| B18 | `generic-entity` — a private EAV dump with its own collection cap and hand-built elision error map | `src/seon/render/web.clj:707-754` (cap at `:725`, elision at `:744-751`) | `/data?entity=…` responses | face, print (its own elision representation) | The floor already renders entities; one elision value |
| B19 | `/data` calls `value/render-html` DIRECTLY, bypassing `render/render-call` | `src/seon/render/web.clj:2200-2206` (`:2204`) | every `/data` page | select (no candidates, no schema render function, no retained-call evidence, no cost fact) | Every HTML byte leaves through one call owner; a second entry point into the floor is the hole |
| B20 | Plain-text HTTP bodies straight from error messages and literals — no shell, no error card, no pipeline | `src/seon/render/web.clj:2009-2011`, `:2078-2080`, `:1896-1899`, `:1902-1907`, `:1919-1923` | 404/422/500/503 responses | select, face, print, assemble | PRD §2's human face is one designed error card; a `:body` that is a raw string is refusable at the response boundary |
| B21 | Package framing and delivery see only strings; nothing checks the page before it goes out | `src/seon/render/web.clj:909-918`, `:920-946`, `:1557-1600` | every SSE patch | assemble (no lint, no structural verification at the one declared sink) | The lint check belongs HERE (§3), on hiccup, before serialization |
| B22 | 75 of 171 CSS classes have no render function (`seon-transcript-human/agent/peer/system`, `plan-tree`, `seon-data-drill`, …) | `resources/public/css/input.css` | — | — | Not a hole but its shadow: the designed page exists in CSS and not in code. A derived render function/class reconciliation is the check that would have said so |

---

## 3. Why the two existing enforcement precedents cannot see most of this

### `seon.fn/text-boundary-report` (`src/seon/fn.clj:967-997`, asserted empty at `test/seon/fn_test.clj:1262-1263`)

It censuses callers of ONE private function, `seon.print/bounded-text`
(`fn.clj:971`), against an authorized set of two (`fn.clj:974-975`). It is
exact and it holds — and it is blind to every row above, for three reasons:

1. **Wrong subject.** `bounded-text` is one bounding implementation.
   `pr-str`, `str`, `subs`, `str/join` and `format` are not in its graph at
   all: they are `clojure.core` calls, not indexed `:seon.fn/sym` rows, so
   `output-graph` (`fn.clj:799-836`) has no edge to walk.
2. **The reachability census needs declared endpoints.** `source-output-paths`
   (`fn.clj:884-947`) walks from a source to a function carrying
   `:seon.fn/external-sink`. Only six functions declare one (§0). The prompt
   seam is not among them: no function on the path
   `walk/history → web/history-text → prompt/prompt → ai/request-body`
   declares a sink, so the classification loop never even starts for the AI
   seam's real path. `render-ai` IS declared — but it is a *stage*, not the
   seam, and everything assembled after it (A1-A4) is downstream of the
   declaration.
3. **Boundaries are self-declared metadata.** `:seon.fn/projection-boundary`
   (`fn.clj:826-832`, `:838-863`) is read from the function's own metadata.
   A namespace that re-implements `fit` (A13, A14) declares nothing, so it
   is neither `:projected` nor `:bypass` — it is simply absent from the
   graph's opinion.

Net: the report answers "does anyone call the one bounder without
permission?" It cannot answer "does any byte reach an agent without being
printed?", which is the question this census asks.

### `seon.render.lint` (`src/seon/render/lint.clj`)

The checks are the right checks — placeholder classes (`:342-355`),
split fences via a real delimiter scan (`:241-306`, `:357-370`), duplicated
subtrees (`:372-398`), `pr-str` soup escaping into page text (`:400-426`),
and absent required regions treated as a FINDING (`:428-451`). Three
reasons it sees almost nothing today:

1. **No production caller.** `grep` over `src/`, `script/` and `test/`
   returns only `test/seon/render/lint_test.clj`. Nothing on the response
   or SSE path calls `check`.
2. **It arrives after the structure is gone.** Even if wired in at
   `page-response`, the page is already a map of STRINGS by then (B2), and
   `check` takes hiccup. The one place structure still exists is inside
   `page-result` before `surface-html`.
3. **It is a page-level check, not a stage contract.** It can say "this page
   contains two identical subtrees"; it cannot say "this value reached HTML
   without a `/html` projection" (B8) or "this face returned more than its
   budget" (A15). Those are boundary facts, not tree facts.

Its own `excerpt` also uses `subs` + a literal `"…"` (`lint.clj:318-322`,
floor at `:55`) — the defect-detection namespace committing the defect
class it detects.

---

## 4. The enforcement mechanism design space

Three mechanisms, with what each genuinely catches and what it cannot.

### 4.1 Graph-query census, asserted empty in tests

*Shape:* a Datalog query over `:seon.fn` facts, wrapped in a report
function, asserted empty by one regression — the `text-boundary-report`
pattern, generalized.

*Catches:* structural facts about CODE — who calls a bounding function, who
calls `tokens/estimate` outside the two budget seams, who emits a string
literal matching a placeholder, which namespaces build hiccup without a
declared `/html` boundary, which faces are declared but unreachable (A25
would have been caught by "declared render assembly with no caller").

*Cannot catch:* anything about VALUES at runtime — a face returning the
wrong projection, a budget silently raised, an entry dropped without an
elision.

*Precondition, and this is the load-bearing part:* the graph must index the
core calls it needs. `:seon.fn/calls` today relates indexed first-party
symbols; `pr-str`/`str/join`/`subs` usage is visible only through
`:seon.fn/keywords`-style literal indexing, which does not cover core
symbols. **Before any census over direct printing can be written, the
analyzer must record core-call edges** (`src/seon/fn/analyzer.clj`) or an
equivalent declared fact. Without that, a "no `pr-str` at a boundary" query
is a check that reports health when its subject is absent — the exact
failure class this project names as recurring.

### 4.2 Runtime type refusal at the seam

*Shape:* the seam accepts only a constructed type. `:seon.cluster.prompt/text`
stops being a `:string` and becomes an assembled value carrying its members,
their costs, and its elisions; an HTTP `:body` stops being a string and
becomes a page value; a package retains hiccup. Producing the string is the
private act of one owner at the very end.

*Catches:* every hole in §1 and §2 that consists of "someone made bytes" —
A1, A2, A4, A5, A6, B2, B3, B4, B17, B19, B20 all become
*unconstructable*, not discouraged, because there is no way to hand a
string to the seam.

*Cannot catch:* a face that returns a well-typed but wrong value — a `/html`
face returning `[:p ai-text]` (B9) is a perfectly valid hiccup value.

*Cost:* it is the largest change, and it is the one the PRD's "skipping a
stage is unconstructable" sentence actually requires.

### 4.3 Instrumented stage contracts

*Shape:* each of the eight stage boundaries validates its input and output
against a declared Malli contract, from the program graph, with the R41 dial
selecting panic (dev) or error fact (prod) — PRD §2. The precedent that this
is currently missing: on a face failing `valid-projection?`,
`src/seon/render.clj:468-474` RETURNS THE UNPROJECTED NODE silently.

*Catches:* value-level violations — B8, B9 (a `/html` boundary contract that
refuses "hiccup whose entire text content equals the `/ai` text" is
checkable), A15 (output tokens > handed budget), A19 (members out < members
in, with no elision), B14 (projection-conditional elision), plus every
future one.

*Cannot catch:* code that never reaches a stage boundary at all — a
namespace assembling its own page (B5) satisfies no contract because it
enters no stage.

### 4.4 Recommendation: all three, in this order of dependency

They are complementary, and each covers the other's blind spot precisely:

- **Types make the seam unreachable by strings** (4.2) — this is what closes
  the "someone made bytes" class permanently.
- **Contracts make each stage honest about values** (4.3) — this is what
  closes "the right shape, the wrong content".
- **The graph census makes the remaining parallel paths visible** (4.1) —
  this is what closes "a namespace that never entered the pipeline",
  including new ones written later.

The census is also the only one that can be asserted EMPTY, which is what
makes it a drift alarm rather than a spot check. It must be written against
facts that exist: the analyzer work in 4.1 is a prerequisite, not a detail.

---

## 5. The minimal choke-point set

Every hole in §1 and §2 is closed by one of these seven. Each is a single
owner; none is a new mechanism except where noted.

1. **One assembly type at seam A.** `:seon.cluster.prompt/text` is produced
   by exactly one function, from a value carrying members, costs and
   elisions. Closes A1, A2, A3, A4, A19, and makes A14/A13's private budget
   loops structurally pointless. *New contract, existing machinery.*

2. **One agent-facing print exit.** Every value an agent sees leaves through
   `print/emit-text` over an admitted node — including `seon.render/walk`,
   `doc`/`dir`, and MCP. Closes A5, A6, A21, A22, A26.

3. **Delete `::length`/`::level` from the printer.** The only structural cut
   is `fit`'s elision node. Closes A20 and A21 at the root, and removes the
   two-elision-representation split (PRD rip-out #5). *A deletion.*

4. **Storage admission owns every stored string.** The eval output string
   passes `print/admit-string` like every other value. Closes A7, A8, and
   the false docstring at `sci/eval.clj:69`.

5. **Faces return DATA; prose only under instruction entities.** Closes A9,
   A10, A11, A12, B9, B10 — and dissolves B8, because once the AI face is
   data, wrapping it in `[:pre]` stops being tempting and stops being valid.
   *This is PRD wave 2, unchanged.*

6. **HTML stays hiccup until delivery, and delivery lints it.** One
   `->string` caller (`frame-bytes`), one response constructor that takes a
   page value, and `seon.render.lint/check` run at the declared sink before
   serialization. Closes B1, B2, B3, B4, B15, B16, B17, B19, B20, B21, and
   gives B5/B6/B7/B11/B18 a boundary that can refuse them. *Wiring plus one
   deletion; lint already exists and is already total.*

7. **No budget on the HTML path.** Closes B12 and A16 (the max-of-twins cost
   measurement has nothing to measure), and removes the double-fit
   (`value.clj:504` then `render.clj:524`). *A deletion.*

Two items sit outside the seven because they are defects to file rather than
design:

- `seon.render.walk/prose` + `seon.effect/context-suffix`: a written,
  tested, never-delivered context contribution (§0). Either re-home the
  background guidance as an assembly member or delete both.
- `src/seon/print.cljc:572` — `(:?_current-ns_?/face node)`, a botched alias
  substitution making the totality branch's own error value permanently nil,
  guarded by a test that only checks the key NAME appears
  (`test/seon/print_test.clj:240`). Already noted in the re-audit §2.14;
  repeated here because it is on the print path this census walks.
