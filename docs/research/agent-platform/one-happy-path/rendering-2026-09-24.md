---
type: research
status: current
created: 2026-09-24
scope: One happy path census for printing and rendering (seon.print, seon.render*, seon.repl, *-ai/*-html families, clipping, web page renderer, token estimation).
---
# One happy path: printing and rendering

Read-only census at `43addd46c`. It extends [agent-context-render-pipeline](../agent-context-render-pipeline-2026-09-24.md)
(which covers the agent prompt), [shrink-web](../shrink-web-2026-09-23.md) and deletion-hunt N15/fipp. Line counts are
clj-kondo var-definition spans over `src/` (analysis dump in the lane scratchpad). The live probes ran in JVM mode on `default`
(pid 90471, armed), read only.

**What exists.** A value becomes a print node twice: once in `seon.sci.admit` (788 lines) for storage, and again in
`seon.render.value/value-node*` for display. It is fitted to a budget twice (`value-node*` cuts, then `seon.print/fit`
searches). It is emitted by a hand pretty-printer, `seon.print` (1,367 lines). The emitted text is wrapped in a hand-built
`#:seon.repl{…}` string, then shown by two session projections, `walk/history`+`prompt/select` and `seon.render.transcript`.
Forms, meanwhile, go through `clojure.pprint` code-dispatch. **The one happy path:** `clojure.pprint` (or `pr-str`) under
`*print-length*`/`*print-level*`/`*print-namespace-maps*` prints both forms and values, and clojure.main's grammar frames them
(`prn` value, `ex-str` error). One session projection feeds both the prompt and the page, and one value cut carries the
elision grammar (count, path, requery) that Clojure's `...` lacks.

## Measured and source-proven differences (broken first)

1. **The value printer leaks `(quote x)`, `(var x)` and `(clojure.core/deref x)`. `clojure.pprint` and fipp do not.**
   I printed the same form `'(seon.db/q '[… 'my/f] #'clojure.core/map @x)` four ways:
   - `seon.print/emit-text`: `(seon.db/q (quote [...(quote my/f)]]) (var clojure.core/map)\n  (clojure.core/deref x))`
   - `seon.repl/source-text` (pprint code-dispatch): `(seon.db/q '[… 'my/f]] #'clojure.core/map @x)`
   - `fipp.clojure/pprint`: the same output as pprint.
   - `value/prepare` with the agent profile: `'(a (quote b))` → `(a (quote b))`.

   `clojure.pprint/dispatch.clj:45` `reader-macros` does this job, and `pprint-map` (`:106-110`) already prints `#:ns{}`
   maps through `lift-ns`.
2. **Three namespaced-map conventions.** `seon.print` prints `#:seon.x{:id 0 …}`. `repl/source-text` binds
   `*print-namespace-maps* false` (`repl.clj:92`). `value/bounded-text` also binds it false (`value.clj:80`). So one datum
   prints differently depending on which printer drew it.
3. **Admission costs 40× `pr-str`.** For a 2,000-map vector: `admit-value` took 116.5 ms, `seon.print/emit-text` 26.4 ms and
   `value/prepare` (agent profile, 15,000-token budget) 11.2 ms. `pr-str` took 2.9 ms, and pprint code-dispatch under
   `*print-length* 8 *print-level* 4` took 2.2 ms. The full-text pprint of the same value took 237 ms, so pprint is only fast
   when it is bounded.
4. **Presentation limits apply twice in one render.** `value-node*` spends a volatile token budget and cuts by
   `max-children`/`max-depth` (`value.clj:440-510`). `print/fit` then halves the child and string limits until
   `tokens/estimate` fits (`print.cljc:1294-1359`). This breaks the law "Apply presentation limits once".
5. **The transcript projection is the slowest renderer in the process.** The profile on `default` records
   `render-outline` at 11,852 ms and `render-ledger` at 11,335 ms, each ×1 (`runtime_status`). Both are over 10 s, and no
   issue or authorization is cited for them.
6. **Print, then read back.** Openers print source strings, and `turn/declared-sources` re-parses every block through
   `planned-sources` (`turn.clj:1852-1905`). `repl/shown-value` (`:82`) and `transcript/readable-shown` (`:1111`) both read
   shown text back into data, and `repl/pretty-response` (`:449`) pprints the read-back a second time for humans.

## Table (ranked by lines deletable)

| concern | our mechanism(s) (file:line, lines) | the one happy path | evidence it duplicates or diverges | delete | keep/adapt | risk + first slice |
|---|---|---|---|---|---|---|
| Session projection | `render/transcript.clj:30-1095` history half (≈1,065 of 2,379: own selectors, `history` :339 29, `candidate-history` :599 54, `agent-history` :874 54, `history-entries` :751 39, `emission` :418 36) beside `walk/history` :927 49 + `prompt/select` | ONE owner: `walk/history` → `prompt/select` units, read by the prompt, `eval.drive` and the page | two selections of one session (pipeline B7). `transcript/emission` re-derives what `repl/entity-emission` (`repl.clj:360`, 80) derives. The outline and ledger are measured at 11.3–11.9 s | ≈700 (UNVERIFIED: web/drive caller audit pending) | keep the ledger/debug HTML half (≈1,280, a product decision) | high. Slice: `drive.clj:288` and `web.clj:1489` read `walk/history` units, then delete `transcript/history`/`emission` |
| Value → print node, twice | `sci/admit.clj` walk (`open-node` :204 117, `frame-advance` :335 56, `project` :431 51, `semantic-*` :486-582 96) **and** `render/value.clj` `value-node*` :427 114 + `attribute-value` :388 36. The stored node round-trips node → `semantic-value` → `value-node*` → node (`value.clj:688`; MCP path `cluster.clj:404-510`) | store the value's canonical EDN through ONE bounded `print-method` writer; display from the value | `value-node*` itself falls back to `admit/admit-value` (`value.clj:527-535`). Admission is 40× `pr-str` (finding 3) | ≈450 (UNVERIFIED: sci Var/record identity projection must survive as `print-method`) | keep the byte bound and the SCI-interrupt check | high. Slice: probe `print-method` + bounded writer against `admit` on the admit test corpus |
| Text pretty-printer | `print.cljc` text path: `TextSink` :214 18, `append-chunk!`/`soft-separator` :192-213, `emit` multimethods :491-790 (≈300), `table-*` :496-557 (≈60) | `clojure.pprint/write` code-dispatch (`dispatch.clj:45,106`) with `*print-length*`/`*print-level*`; `pr-str` for single-line | finding 1 (quote/var/deref leak), finding 2 (ns-map divergence); fipp (on the classpath via malli, `fipp/fipp 0.6.29`) matches pprint | ≈380 | keep `HiccupSink`, `references`/`lookup-reference` (entity links in HTML are Seon's own) | medium: shown text changes. Slice: AI text = pprint of the fitted value; add the reader-macro cases first (≈10 lines) if the node must stay |
| HTML serializer | `render/hiccup.clj:205-525` (≈320: `shorthand` :258 75, `append-element!` :428 37, `->string` :486 40, escaping, styles, classes) | chassis `c/html` (1.0.365, which the vendored hyperlith already uses; not on Seon's classpath) | shrink-web §0.6 and hunt N15 still hold at HEAD | ≈300 | keep the `hiccup?` validator (:94 59) as the schema predicate | owner OK for the `deps.edn` addition. Slice: byte-compare on `web-test` pages |
| Render invocation cache | `render.clj` `render-program-evidence` :701 → `unknown-stable-evidence` :917 (≈216), `render-call` :1404 183, `shared-cache` :1618 24 | `clojure.core.cache.wrapped/lookup-or-miss` memo keyed by what the render reads (commit id / definition digest) | re-decides currency by read evidence (cut-analysis row 12, dependency audit row 15) | ≈250 | keep `render-call`'s producer dispatch (≈60) | medium; plan cut 4. Slice: memo one producer class |
| Double budget cut | `value-node*` cut/`remaining` (`value.clj:430-510`, ≈50) and `print/fit` + `fit-node`/`fit-children`/`fit-text`/`structural-elision` (`print.cljc:1131-1359`, ≈230) | ONE cut: `fit` over the value, emitting one elision value (`print/elision` :982) whose `print-method` prints count, path and requery | finding 4 | ≈120 (keep one) | keep `render-elision-ai` :457 (the elision grammar Clojure lacks) | medium. Slice: delete `value-node*`'s budget volatile; `fit` stays the one spot |
| REPL response grammar | `repl.clj` `response-entries` :168 44, `response` :213 17, `def-note` :118 ~25 (re-reads the source with `edn/read-string`); `text` :256 `;; full value` line | clojure.main: `prn` value (`main.clj:492`), `ex-str` error (`main.clj:268`, already used by `error-text` :148), one `;; result/… · N ms` comment | four response shapes, and the error is `pr-str`'d into an escaped string (pipeline §0.3) | ≈70 | keep `error-text`, `input-text` | low. Slice (a) of the pipeline doc |
| Openers print, then re-read | domain producers return `source-text` strings (35 `(list 'quote …)` in 13 files; `issue/opening.clj` 12 `source-text` calls); `turn/declared-sources` :1852 54 + `planned-sources` re-parse | producers return `{comment form}` data; the turn evaluates the form it was given | finding 6; pipeline B2/B3 | ≈120 | keep `repl/source-text` as the one form printer | medium; `turn.clj` is held by another lane. Slice: one producer (`render-faults-ai`) |
| Error text | `value.clj` `causes`/`link-lines`/`floor` :90-131 (≈40, own `subs` 8,192 clip); `cluster.clj` `first-seon-frame` :355 + `exception-summary` :364 (27); `repl/error-text` | `Throwable->map` (`core_print.clj:467`) + `ex-triage`/`ex-str` (`main.clj:207,268`) | three renderings of one Throwable; `floor` clips outside the value renderer's `fit` | ≈60 | keep `bounded-text` as the one total fallback | low (error lane owns the constructor). Slice: `exception-summary` → `ex-triage` |
| Web delivery | `web.clj:3640-3683` router built per request (`:3753` `(fn [request] ((handler service) request))`); `resource` :2963 with a production regex :2969 | `reitit.ring/router` once at `start!`; `reitit.ring/create-resource-handler` (`ring.cljc:289`) | still present at HEAD; 1.07 ms/request (shrink-web P1) | ≈40 | — | low. Slice S1 of shrink-web |
| Second bounded writer | `admit.clj` `utf8-length` :97 18 + `write!` :116 25 beside `value/bounded-text` :65 24 + `datum` :47 17 | ONE bounded `java.io.Writer` under `print-method` | two writers that cut output at a limit (bytes vs chars) | ≈40 | keep one, parameterized by unit | low; folds into the admission row |
| Code highlighting | `repl/syntax-tokens` :293 ~45 (hand lexer) + `transcript/colour-source` :1673 (a copy of `syntax-spans`) | `rewrite-clj.parser/parse-string-all` node tags (lossless whitespace/comments) | two span builders; the lexer is a second reader (the owner's "we wrote our own parser") | ≈40 | keep a lexer only for incomplete replies (rewrite-clj throws on unbalanced input: UNVERIFIED) | low. Slice: delete `colour-source` (4) now |
| Human pretty response | `repl/pretty-response` :449 8 + callers :486,:493 | show the exact shown text | reads shown text back and prints it a second way | ≈12 | — | low |
| Default AI source | `render/render-default-ai-source` :1368 10 (bare `pr-str` → `(quote [*])`) | `repl/source-text` | third form printer (pipeline §0.4) | ≈8 | — | low |
| Token estimation | `ai/tokens.cljc` (210), the one owner; `prompt.clj` calibration | keep: there is no tokenizer dependency on the classpath | no second estimator found (`rg chars-per-token\|(/ … 4)` finds only the owner and prompt) | 0 | keep | — |
| `*-ai`/`*-html` families | 63 `*-ai` (953 lines), 77 `*-html` (1,559) in 30 namespaces | one pair per entity schema (a law); the default attribute-map printer | lawful when declared; the defect is B2 (they return printed source), covered above | 0 here | keep | — |

**Total deletable in this area: ≈2,590 lines**, of which ≈1,150 are UNVERIFIED (the transcript and admission rows). The
transcript debug half (≈1,280) and web debug (≈1,100) are extra, but only if the owner makes a product decision.

**Three highest-value slices.**
1. **One session projection.** The page and `eval.drive` read `walk/history` units, and the transcript history half is
   deleted. That is ≈700 lines, and it removes the two 11 s renders.
2. **One printer.** The AI text of values goes through `clojure.pprint` code-dispatch. This fixes the quote leak and the
   ns-map split, and deletes ≈380 lines of `seon.print` text path plus the second budget cut (≈120).
3. **One value projection.** Admission stores bounded `print-method` EDN, and rendering reads values. That is ≈450 lines,
   and admission's 40× cost goes with it.

**What the dependencies genuinely lack.**
- Clojure's `...` names no count, path or requery. Keep one elision value with a `print-method`; this is not a fork.
- fipp ignores `*print-namespace-maps*`: `fipp.edn` printed `{:seon.x/id 0 …}` where pprint prints `#:seon.x{…}`. Use
  `clojure.pprint`, not fipp, so there is no fork to fix.
- rewrite-clj cannot parse an incomplete reply (UNVERIFIED). If the page must colour partial replies, fix that in the
  rewrite-clj fork (lenient parse), not beside it.
- chassis is not on the classpath. Adding it is an owner decision.

```clojure
#:seon.task{:id :seon.render.transcript.task/one-session-projection
            :intent "seon.render.transcript re-selects and re-emits an agent session beside walk/history + prompt/select, and its outline/ledger measure 11.3-11.9 s. Page and eval.drive read the walk/history units; delete transcript history, candidate-history, agent-history, history-entries and emission."}
#:seon.task{:id :seon.sci.admit.task/store-printed-edn-not-print-nodes
            :intent "A value becomes a print node in admission and again in value-node*, round-tripping through semantic-value, at 40x pr-str. Store canonical EDN from one bounded print-method writer and render from the value."}
#:seon.task{:id :seon.print.task/ai-text-through-clojure-pprint
            :intent "seon.print's text emitter prints (quote x), (var x) and (clojure.core/deref x) where clojure.pprint code-dispatch prints 'x #'x @x and #:ns{} maps. Emit AI value text with clojure.pprint/write under *print-length*/*print-level*; delete the TextSink/table/emit text path."}
#:seon.task{:id :seon.render.hiccup.task/serialize-with-chassis
            :intent "seon.render.hiccup hand-serializes HTML (escaping, void elements, shorthand, styles), which chassis c/html already does; replace ->string with chassis once the owner approves the dependency."}
#:seon.task{:id :seon.render.task/render-call-is-a-memo
            :intent "render-call keeps an invocation cache that re-decides currency by read evidence; replace it with clojure.core.cache.wrapped/lookup-or-miss keyed by what the render reads."}
#:seon.task{:id :seon.print.task/one-presentation-cut
            :intent "value-node* spends a token budget and cuts children/depth, then print/fit searches the same limits again; keep fit as the one cut and delete value-node*'s budget."}
#:seon.task{:id :seon.repl.task/clojure-main-response-grammar
            :intent "seon.repl/response hand-builds a #:seon.repl{} string with pr-str'd errors; print the value as prn does and the error as clojure.main/ex-str, then one ;; handle and ms comment."}
#:seon.task{:id :seon.turn.task/openers-return-forms
            :intent "Domain openers print source strings that declared-sources re-parses before evaluation; producers return {comment form} data and the turn evaluates the form it was handed."}
#:seon.task{:id :seon.render.value.task/one-throwable-rendering
            :intent "value/floor, cluster/exception-summary and repl/error-text render one Throwable three ways; derive them from Throwable->map and clojure.main/ex-triage + ex-str."}
#:seon.task{:id :seon.render.web.task/router-once-and-resource-handler
            :intent "web/handler compiles the Reitit router on every request and serves static files through a production regex; build the router once at start! and use reitit.ring/create-resource-handler."}
#:seon.task{:id :seon.sci.admit.task/one-bounded-writer
            :intent "admit's utf8-length/write! and value/bounded-text are two writers that cut print-method output at a limit; keep one bounded java.io.Writer."}
#:seon.task{:id :seon.repl.task/highlight-from-rewrite-clj
            :intent "repl/syntax-tokens is a hand Clojure lexer and transcript/colour-source copies syntax-spans; colour from rewrite-clj parse-string-all node tags and delete the copy."}
#:seon.task{:id :seon.repl.task/show-exact-shown-text
            :intent "repl/pretty-response reads shown text back and pprints it a second way for humans; show the exact shown text."}
#:seon.task{:id :seon.render.task/default-source-through-source-text
            :intent "render-default-ai-source pr-strs its form, printing (quote [*]); print it with repl/source-text, the one form printer."}
```
