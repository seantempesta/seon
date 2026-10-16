---
title: Printer audit, merged inventory
date: 2026-10-16
lane: printer-merge
type: audit
status: complete
---

# Printer audit: merged and verified inventory

Lane printer-merge (Sonnet), 2026-10-16, read-only on code, verified at HEAD `707c14c21` plus the uncommitted working tree. Inputs: [opus.md](opus.md) with [opus-findings.edn](opus-findings.edn) (91 findings) and [astra.md](astra.md) with [astra-findings.edn](astra-findings.edn) (569 findings). Output: [inventory.edn](inventory.edn), one map per merged finding. The owner's model (2026-10-16): the form runs, its result's schema selects a render, the AI face returns data, the one value printer prints it; nothing calls a render by name or clips a value outside the printer.

## Result

- **609 merged findings**, one per (symbol, category): 277 CONFIRMED, 124 REFUTED, 208 NEEDS-RULING.
- The 208 NEEDS-RULING findings are 11 class questions (R1 to R11 below); 150 of them are one class (R1, a scalar interpolated into fixed message prose). One more ruling, R12, is a CLAUDE.md amendment, not a finding.
- 141 findings were found by both audits, 428 by Astra only, 31 by Opus only, and 9 more came from this lane's schema probe (declared AI renders whose output schema admits `:string`).
- Astra's 569 are 141 records shared with Opus plus 428 only Astra found. Its largest group, category 3 (441 findings), is mostly fixed prose and chrome, not value printing: of the 348 Astra-only category 3 records, most are `:seon.error/message` strings with a scalar (the R1 class, 150 members in all) or page labels, log lines and codecs (REFUTED, 104 Astra-only records in all); the value-printing ones are the CONFIRMED classes below.

## How it was merged and verified

1. Opus's grouped findings (F55, F56, F62, F63, F90) were expanded to producer symbols by mapping each `file:line` to its enclosing var with a clj-kondo `var-definitions` analysis of `src` and `script` at HEAD. Opus F76 (96 hiccup leaves in 24 files, no symbol) is kept as `GROUP/F76`; the category 6 families are keyed by their first function. `seon.env` print-method was aligned to `clojure.core/print-method`.
2. Records were keyed by (fully qualified symbol, category). Category numbers match in both audits; Opus's 3a to 3e are kept as `:subcategory`.
3. Every Astra symbol exists at HEAD and every Astra evidence snippet was found again in its file (568 of 569; the one other is `clojure.core/print-method`, whose defmethod is in `src/seon/env.clj`). That proves the sites exist, not that they are defects.
4. Every record found by one audit only, and every INFERRED one, was classified by reading its code excerpt or the function at HEAD. Of the 141 records both audits found, 98 are CONFIRMED by agreement (basis `:agreement`); 43 needed a reading because they are encodings, labels or schema-probe cases. Every declared AI render's output schema was read from source to find the string-returning ones (basis `:schema-probe`).
5. Not done: no probe on default (the lane may not touch it), so Opus's measured allocation numbers (196 empty `[…2]` lines, a docstring cut to 259 of 1,050 characters) were re-read in `seon.render.value` (`sibling-level`, `admit-member`, `string-shown`, `member-rank`) and are consistent with the code, but were not re-measured. No test was run.

## Found while verifying

- **The render set is wider than the resources.** Both audits (and Opus's proposed R1 derivation from `resources/seon/schemas/*.edn`) miss pairs declared in Clojure source: `seon.bootstrap` (`render-help-ai`, `render-help-section-ai`), `seon.render` (`render-form`), `seon.schema.edn` (`seon.config/render-ai`, `seon.agent/render-settings-ai`), `seon.sci.lookup` (`seon.repl/render-directory-ai`). The static check must derive the set from `seon.schema.edn/packaged-forms`, which includes them. `seon.config/render-ai` is a string-returning AI render neither audit listed.
- **A static check already exists and is red.** `seon.print-census-test/no-value-is-printed-outside-the-value-printer` (`seon.census` scan of `src` for `pr-str`, `prn-str`, `print-str`, `println-str`, `pprint`) was added red by commit `18919b663` (85 sites in 33 files); its exception list is a hand map holding one entry, `seon.fs/write-edn-atomically!`. `seon.source-census-test/no-timeout-or-limit-is-a-hardcoded-number` is the red R4 sibling. P0 extends these rather than adding an analyzer.
- **Opus F83 refined.** `seon.ai/registry-ai` and `registry-html` have no `src` caller but `test/seon/ai_test.clj` calls both, so deleting them converts that test in the same commit.
- **27 of 57 declared AI renders admit `:string`** (Opus counted 24 of 61): 5 are legitimate or false positives and the other 22 convert; `my.program/render-source-ai` and `seon.cluster.instruction/instruction-ai` are text-as-content, `seon.render.value/render-ai` and `seon.print/render-elision-ai` are the printer's own, `seon.repl/render-directory-ai` is a false positive (a nested `:string`).
- **Working-tree edits.** `src/seon/context.clj` and `src/seon/render.clj` carry uncommitted changes (lane one-render and another lane); findings in them were read in the working tree and may move.

## Counts

| Category | Merged | CONFIRMED | NEEDS-RULING | REFUTED |
|---|---|---|---|---|
| 1 render called by name | 47 | 47 | 0 | 0 |
| 2 render-call outside the walk | 2 | 2 | 0 | 0 |
| 3 second printer (3a-3e) | 461 | 149 | 195 | 117 |
| 4 clipping outside the printer | 49 | 36 | 12 | 1 |
| 5 hand-built hiccup | 23 | 17 | 1 | 5 |
| 6 parallel paths | 18 | 18 | 0 | 0 |
| 7 printer internals | 9 | 8 | 0 | 1 |

| Category 3 subtype | Merged | CONFIRMED | NEEDS-RULING | REFUTED |
|---|---|---|---|---|
| 3a | 155 | 87 | 18 | 50 |
| 3b | 186 | 37 | 149 | 0 |
| 3c | 49 | 5 | 0 | 44 |
| 3d | 61 | 10 | 28 | 23 |
| 3e | 10 | 10 | 0 | 0 |

| Found by | Merged | CONFIRMED | NEEDS-RULING | REFUTED |
|---|---|---|---|---|
| opus + astra | 141 | 117 | 15 | 9 |
| astra | 428 | 131 | 193 | 104 |
| opus | 31 | 24 | 0 | 7 |
| schema-probe | 9 | 5 | 0 | 4 |

| Phase | Ids | CONFIRMED | NEEDS-RULING | Lane one-render (named) |
|---|---|---|---|---|
| P1 | M001-M029 | 29 | 0 | 0 |
| P2 | M030-M060 | 31 | 0 | 25 |
| P3 | M061-M130 | 70 | 0 | 0 |
| P4 | M131-M352 | 72 | 150 | 0 |
| P5 | M353-M406 | 54 | 0 | 26 |
| P6 | M407-M485 | 21 | 58 | 2 |

| Class (CONFIRMED) | Findings |
|---|---|
| value-in-message | 40 |
| value-print | 28 |
| clip-text | 26 |
| render-calls-render | 23 |
| render-call-by-name | 20 |
| string-ai-render | 20 |
| parallel-paths | 18 |
| domain-hiccup | 17 |
| prose-layout | 11 |
| hardcoded-dial | 10 |
| printer-internal | 8 |
| error-prose-face | 7 |
| number-layout | 7 |
| printer-entry-call | 7 |
| render-composition | 6 |
| identity-by-print | 5 |
| render-prints-text | 4 |
| test-report-composition | 4 |
| script-core-text | 3 |
| second-printer-inside-printer | 3 |
| render-call-outside-walk | 2 |
| date-layout | 2 |
| error-storage | 2 |
| render-prose | 1 |
| second-printer-fallback | 1 |
| size-by-print | 1 |
| prose-face | 1 |

## Lane one-render

65 merged findings are in lane one-render's named scope or its held files (51 CONFIRMED, 12 REFUTED, 2 NEEDS-RULING). By phase: P1 0, P2 25, P3 0, P4 0, P5 26, P6 2. Ids: M031, M033-M045, M050-M060, M355, M357-M379, M403-M404, M438, M457, M520, M559-M566, M590-M592. The lane's brief names the exchange and evaluation render, `must-read-ai`, `seon.render.web/render-source-call` and the reference-tokens elision; the rest are findings in the four files it holds (`src/seon/context.clj`, `src/seon/repl.clj`, `src/seon/render/transcript.clj`, `src/seon/render/sent.clj`), which this lane found open and did not verify as being converted.

## NEEDS-RULING

**R1 error-message-scalar** (150 findings: M135-M138, M140-M145, M147-M153, M155-M159, M161-M163, M167-M182, M185-M190, M192-M194, M196-M199, M201-M206, M208, M219, M238, M240-M241, M246, M249-M251, M253-M254, M256, M258-M266, M269-M272, M274-M279, M282-M291, M293-M296, M298-M313, M315-M327, M329-M330, M332-M334, M336-M341, M345-M347, M351-M352)
- Question: May an error, reason or refusal message interpolate a scalar (symbol, keyword, id, count, path) into short fixed prose?
- Recommendation: Allow scalars that name the subject; require every structured value (map, vector, form, evidence) to be a data key and never printed into the message. The value-in-message class (category 3b, CONFIRMED) already covers the second half. A static check cannot see scalars, so this stays a review rule.

**R2 script-display** (15 findings: M408-M410, M412-M413, M415, M417-M425)
- Question: Do the `script/` operator and dev tools print values through the one printer, or only fixed prose and EDN data?
- Recommendation: Fixed prose and counters are allowed, EDN written whole for a machine reader is a codec, and any value shown to a person (`core-text`, `pprint`, `prn` of rows) goes through the one entry once it exists. Scripts already calling `core-text` are CONFIRMED because that entry is retired.

**R3 canonical-encoder** (25 findings: M432, M444-M446, M451-M452, M454-M456, M460, M463-M475, M484-M485)
- Question: Is `seon.id/printed` with `print-canonical!` the one declared canonical EDN encoder (the census already lists `seon.fs/write-edn-atomically!` as a hand exception), and does the `seon.sci.admit` print-node writer collapse into it?
- Recommendation: Yes: declare one encoder with `^{:seon.print/emitter true}`, convert the stored `pr-str` facts (`:seon.fn.*/form`, `-edn`, `:seon.schema/form`) to call it, and keep it lossless and never clipped. Identity built from printed text stays CONFIRMED under identity-by-print and moves to the structural digest.

**R4 top-k-statistic** (9 findings: M430, M433, M438-M439, M448-M450, M457, M462)
- Question: May a producer keep only the top k of a measured set (profile top, cost top, hot list, history children) before any printer sees it?
- Recommendation: Only as a semantic aggregation that keeps the total count beside the kept members and reads k from a config dial; otherwise the printer elides. `hot-limit` 20 and the others are hardcoded today, which is a defect on its own.
- Members: `start-flow-benchmark/costs`, `seon.batch/top`, `seon.context/branch-cost`, `seon.db/cost-upserts`, `seon.profile/measure`, `seon.profile/selected`, `seon.profile/summary`, `seon.render.transcript/agent-history-under`, `seon.schema.admission/similar-identities`.

**R5 sort-by-str** (2 findings: M436, M461)
- Question: Is ordering by `str` of identities allowed, given the law against printing to sort?
- Recommendation: No: sort with `compare` on the keywords and symbols, or by a digest key. Low priority, not display.
- Members: `seon.cluster.source/proven-file`, `seon.schema/unresolved-reference-refusal`.

**R6 host-object-print-method** (2 findings: M441-M442)
- Question: Who owns the text of a live host object (an Environment, a `#object[...]` name)?
- Recommendation: The printer's opaque-object node. `clojure.core/print-method` for the Environment and `seon.error.refusal/object-name` defer to it or are deleted.
- Members: `clojure.core/print-method`, `seon.error.refusal/object-name`.

**R7 log-tail-window** (1 finding: M426)
- Question: Is the last-n-lines acquisition window of a subprocess log acceptable?
- Recommendation: Yes as an explicit acquisition window with the full-log reference attached, once the printer has the tail-first option (O8); the 30-line and 40-line literals become dials.
- Members: `seon.operator/log-tail`.

**R8 identity-dom** (1 finding: M459)
- Question: May a DOM or graph node id be built by `pr-str` of `[branch eid]`?
- Recommendation: No: use the structural digest (`seon.id/id`); a DOM id needs a string, not printed EDN.
- Members: `seon.render.web/graph-node-id`.

**R9 semantic-parse** (1 finding: M447)
- Question: May `seon.problems/agent-error-parts` parse an old error message string and drop a suffix?
- Recommendation: No: the producer carries the head and the message as separate error members; the parse is the defect, deleted with the producer fix.
- Members: `seon.problems/agent-error-parts`.

**R10 printer-owned-html** (1 finding: M458)
- Question: Is `seon.render.walk/related-html` (related-entity counts and schema names as hiccup) part of the printer's emission or a second layout?
- Recommendation: Part of the printer: it becomes a print node with count and requery data, emitted by the one HTML face.
- Members: `seon.render.walk/related-html`.

**R11 agent-text-face** (1 finding: M435)
- Question: Does `seon.cluster.agent/whoami` stay a text face beside the identity pair?
- Recommendation: No: it is the identity pair's AI data printed once; delete the parallel text.
- Members: `seon.cluster.agent/whoami`.

**R12 doc amendment** (not a finding). CLAUDE.md Presentation still says a render that wants a nested thing rendered calls that render explicitly and that render output is never re-dispatched, and the comment in `seon.render.value/make-node` says only the root is offered to its render. The owner's 2026-10-16 model (nested dispatch by schema, nothing calls a render by name) replaces both. Amend CLAUDE.md and the `make-node` comment in the commit that lands P1; until then every category 1b finding is correct under the new model and wrong under the old text.

## REFUTED (legitimate or not a display path)

Every item below keeps its record in the inventory with the reason; none is a printer bypass. Where a refuted `pr-str` is an encoding, the R3 sweep still routes it through the declared encoder once R3 is ruled.

- **label** (51). 96 hiccup leaves are mostly labels and counts; the domain-value ones are separate findings; page chrome: heading or label with a name or count, HTML transport/UI, no domain value shown; cut-count chrome beside the printer's own cut data. Members: M486 `GROUP/F76`, M506 `seon.cluster/render-html`, M512 `seon.cluster.agent/render-creation-html`, M513 `seon.cluster.agent/render-identity-html`, M515 `seon.cluster.message/inbox-html`, M516 `seon.cluster.message/render-html`, M517 `seon.cluster.message/render-inbox-html`, M519 `seon.config/render-html`, M520 `seon.context/reply-html`, M524 `seon.effect/render-html`, M528 `seon.error/render-faults-html`, M529 `seon.error/render-html`, M530 `seon.error/reply-html`, M540 `seon.health/finding-item`, M541 `seon.health/render-html`, M550 `seon.render.ns/function-html`, M551 `seon.render.ns/render-alias-html`, M552 `seon.render.ns/render-html`, M553 `seon.render.ns/render-import-html`, M554 `seon.render.ns/render-refer-html`, M555 `seon.render.ns/task-html`, M556 `seon.render.schema/check-html`, M558 `seon.render.schema/named-list`, M559 `seon.render.sent/exchange-html`, M560 `seon.render.sent/view-html`, M561 `seon.render.transcript/render-history-html`, M562 `seon.render.transcript/render-runtime-html`, M564 `seon.render.transcript/runtime-turn-table`, M565 `seon.render.transcript/session-header`, M566 `seon.render.transcript/test-html`, M568 `seon.render.var/render-html`, M569 `seon.render.walk/related-html`, M570 `seon.render.web/applicable-renderers-html`, M571 `seon.render.web/applicable-renderers-html`, M573 `seon.render.web/calls-html`, M574 `seon.render.web/chains-page-result`, M575 `seon.render.web/chains-page-result`, M576 `seon.render.web/create-owner-form`, M577 `seon.render.web/debug-found-value`, M578 `seon.render.web/debug-graph-html`, M579 `seon.render.web/debug-graph-html`, M583 `seon.render.web/debug-response`, M585 `seon.render.web/graph-model`, M587 `seon.render.web/shell`, M588 `seon.render.web/stream-strip-html`, M590 `seon.repl/def-note`, M591 `seon.repl/render-directory-html`, M592 `seon.repl/render-html`, M600 `seon.task/attempt-html`, M601 `seon.task/chain-html`, M602 `seon.task/chain-task-html`.
- **scalar-label** (24). class name of a failure inside fixed failure prose in the MCP dev bridge; type name in a fixed bridge failure message; remedy sentence interpolating cluster name and root path. Members: M493 `seon.dev.mcp/failure-text`, M494 `seon.dev.mcp/jvm-string!`, M496 `seon.dev.mcp/start-remedy`, M499 `seon.operator/built-template!`, M500 `seon.operator/candidate-tests!`, M525 `seon.env/members-of`, M531 `seon.error.refusal/refusal`, M537 `seon.fs/caller-frame`, M542 `seon.instrument/caller-frame`, M543 `seon.instrument/definition-location`, M545 `seon.problems/resolution-text`, M546 `seon.problems/stale-var-ai`, M548 `seon.render/unknown-stable-evidence`, M572 `seon.render.web/block-metadata`, M581 `seon.render.web/debug-read-dependencies-html`, M584 `seon.render.web/failed-page-result`, M586 `seon.render.web/scalar-label`, M593 `seon.schema/fallback-caller`, M596 `seon.sci.eval/interface-proxy`, M598 `seon.sci.reader/read-events`, M605 `seon.test/host-text`, M606 `seon.test/unfulfilled`, M607 `seon.test.runner/failure-report`, M609 `seon.test.runner/run-vars!`.
- **codec** (19). pr-str of a contract builds a benchmark form to evaluate; code generation, never shown; pr-str builds a form for the live root and EDN log lines appended to a file; machine data; whole result written as one EDN data file for later reading; never clipped. Members: M492 `seon.dev.harness-costs/wrapper-overhead`, M498 `seon.interpreted-census/-main`, M502 `seon.operator/write-diagnostic!`, M511 `seon.cluster.agent/namespace-seed-call`, M514 `seon.cluster.message/data-link`, M526 `seon.error/evidence-path`, M527 `seon.error/observed-key-generator`, M534 `seon.fn/var-entity`, M535 `seon.fn.analyzer/expansion-hooks`, M536 `seon.fn.analyzer/program-prelude`, M539 `seon.fs/write-edn-atomically!`, M544 `seon.mcp/runtime-status`, M557 `seon.render.schema/link`, M563 `seon.render.transcript/runtime-message-link`, M580 `seon.render.web/debug-query-strings`, M582 `seon.render.web/debug-response`, M589 `seon.render.web/surface-html`, M595 `seon.schema.admission/-main`, M603 `seon.test/clean-request-handle`.
- **progress-log** (15). bridge log line of fixed prose parts; one-off sweep script prints file and heading counts; queue position progress line. Members: M495 `seon.dev.mcp/log`, M497 `seon.docstring-headings/-main`, M501 `seon.operator/check-slot!`, M503 `seon.cluster/full-source-refresh!`, M504 `seon.cluster/load-development-definitions!`, M505 `seon.cluster/populate-source!`, M507 `seon.cluster/report-analysis-warnings!`, M508 `seon.cluster/report-source-progress!`, M509 `seon.cluster/serve!`, M510 `seon.cluster/warn-low-space!`, M532 `seon.fn/add-contract-facts`, M533 `seon.fn/index!`, M538 `seon.fs/log-deletion!`, M594 `seon.schema/warn-classpath-fallback!`, M597 `seon.sci.lookup/agent-test-ns`.
- **authored-prose** (4). authored template prose (commit message, task plan or contract plan text) with scalar interpolation; text is the content; task plan prose authored with interpolated scalars. Members: M518 `seon.cluster.source/commit-message`, M521 `seon.contracts/plan`, M599 `seon.task/adoption-conflict`, M604 `seon.test/file-two-outcomes!`.
- **text-is-content** (3). the source text is the content; the instruction's own words are the content; returns authored source, the requery form as text (output schema :seon.render/source); the text is the content. Members: M487 `my.program/render-source-ai`, M488 `seon.cluster.instruction/instruction-ai`, M547 `seon.render/render-default-ai-source`.
- **printer-own** (2). the printer's own elision text, an emitter; the printer's entry returns text by definition. Members: M489 `seon.print/render-elision-ai`, M490 `seon.render.value/render-ai`.
- **library-reporter** (1). clojure.test testing-vars-str header in the gate log. Members: M608 `seon.test.runner/report-event!`.
- **printer-general-rule** (1). reads the database's general ref schema per attribute, the printer's general reference rule per CLAUDE.md. Members: M567 `seon.render.value/reference-value`.
- **place-identity** (1). a live host object printed by str gives class and identity, which CLAUDE.md allows for places. Members: M522 `seon.db/connection-identity`.
- **false-positive** (1). output schema holds a nested :string, not a text render. Members: M491 `seon.repl/render-directory-ai`.
- **emitter** (1). the HTML serializer stringifying leaves is the printer's own emitter. Members: M549 `seon.render.hiccup/append-node!`.
- **acquisition-window** (1). byte-budgeted edit source window returned with a completeness flag. Members: M523 `seon.edit/context-window`.

## Fix phases

P0 is the proof harness and goes first; P1 is the one change everything else waits on; P2 to P5 can then run in parallel (file-disjoint), and P6 follows the rulings. Phase ids are contiguous ranges of the merged ids.

### P0 static check (first, red)
- Scope: extend `seon.print-census-test/no-value-is-printed-outside-the-value-printer` (rule R3, also scan `script`, replace the hand exception map by `^{:seon.print/emitter true}` on the declared encoder) and `seon.source-census-test/no-timeout-or-limit-is-a-hardcoded-number` (rule R4); add one render census (rules R1 and R2) over clj-kondo analysis with the render set derived from `seon.schema.edn/packaged-forms` and the printer set derived from `seon.sci.eval/shown-result`, with liveness assertions that the printer set holds `seon.render.value/prepare` and `seon.render/render-call`. Mutation fixtures per Astra's check design.
- No finding converts in P0; each CONFIRMED finding belongs to the rule below, and the phases turn rules green per namespace.
- Red now: R3 85 sites in 33 files per its commit (this lane did not re-run it).

| Rule | Covers | CONFIRMED findings it turns green |
|---|---|---|
| R1 (render called by name) | category 1 and 6 | 65 |
| R2 (render-call outside the walk) | category 2 | 2 |
| R3 (pr-str, pprint, with-out-str, print-method) | category 3 | 149 |
| R4 (literal count or def'd policy number) | category 4 | 36 |
| review (not statically checkable) | category 5, 7 and the R1 to R11 rulings | 25 |

### P1
- Findings: 29 (29 CONFIRMED, 0 NEEDS-RULING), ids M001-M029.
- Scope: Printer core: one entry `(print value options)`, nested dispatch by schema (root and nested units through the walk), the allocation policy (structure first, strings whole while they fit, largest collection collapses first, a large sequence ends with one count and one `drop` form), options (budget by position and age, detail, consumer, multi-part unit, tail-first, declared display units), `core-text`, `bounded-text`, `datum` and `throwable-text` retired, the schema-specific pieces (`render-database-identity-ai`, `walk-with-related`) moved to their owners.
- Files (findings per file): `src/seon/render/value.clj` 18, `src/seon/render.clj` 6, `src/seon/sci/eval.clj` 3, `src/seon/context.clj` 1.
- Depends on: P0 red first. No other phase may call a retired entry once P1 lands, so P1 keeps the old entries as thin callers of the new one until the last caller converts (HEAD boots after each commit).
- Red proof candidates: Existing: `seon.printer-elision-test/a-child-cap-follows-the-budget`, `seon.print-test/one-cut-face-and-held-order`, `seon.render.value-law-test/the-text-stays-inside-its-charge-and-budget`. New rows (red now): a 1,050-character docstring prints whole before digests at 1,024 tokens; no run of collapsed `[…N]` members; a render attached to a nested map is selected without a named call; `seon.print-census-test` and R4 green on `seon.render.value`.

### P2
- Findings: 31 (31 CONFIRMED, 0 NEEDS-RULING), ids M030-M060.
- Scope: Reply assembly and exchange composition: `seon.repl/reply-map`, `response-entries`, `shown-output`, `text`, `source-text`, `input-text`, `reply-text`, `live-response`; `seon.context` exchange, history elision, cost and token badges, `must-read-ai`, `model-context`, `block-views`; `seon.mcp` reply and runtime-status; `seon.ai/transcript`. Most of it is lane one-render's files; the lane's stated scope is the `:named` count.
- Files (findings per file): `src/seon/context.clj` 13, `src/seon/repl.clj` 11, `src/seon/mcp.clj` 4, `src/seon/ai.clj` 1.
- Depends on: P1 (one entry, multi-part unit, position, tail-first). Files are held by lane one-render, so P2 is a patch set until that lane releases them.
- Red proof candidates: Existing: `seon.repl-test/one-evaluation-emits-comment-prompt-and-response`, `seon.repl-test/response-key-order-is-the-emitter-not-the-map`, `seon.repl-test/printed-output-is-separate-from-the-value`, `seon.reply-consistency-test`, `seon.cost-render-test/a-reply-shows-its-cost-as-the-ai-face-and-binds-the-whole-cost`, `seon.render.sent-test/a-call-view-shows-its-sections-its-pairs-and-every-cut`. Static: R3 green on `seon.repl`, `seon.context`, `seon.mcp`.

### P3
- Findings: 70 (70 CONFIRMED, 0 NEEDS-RULING), ids M061-M130.
- Scope: Render functions that return text or call another render by name: the string-returning AI renders, renders wrapping the AI text in `[:pre]`, render pairs calling renders (`render-faults-html`, `chain-task-html`, `inbox-html`), number and date layout, domain hiccup beside a pair, parallel faces for one value. Each render returns pruned data; the printer picks the face.
- Files (findings per file): `src/seon/ai.clj` 10, `src/seon/problems.clj` 7, `src/seon/cluster/agent.clj` 6, `src/seon/cluster/message.clj` 4, `src/seon/test/accretion.clj` 4, `src/seon/cluster/boot.clj` 4, `src/seon/db.clj` 4, `src/seon/batch.clj` 3, `src/seon/health.clj` 3.
- Depends on: P1 (nested dispatch and consumer option) and the R3 ruling for the number-layout items (declared display units).
- Red proof candidates: Existing: `seon.render.entity-pairs-test`, `seon.render.faults-test`, `seon.ai-test`, `seon.render.history-test`. New static rule (red now): no declared AI render's output schema admits `:string` unless its pair declares the text as the content (27 of 57 admit it today, 22 convert). R1 green outside the walk.

### P4
- Findings: 222 (72 CONFIRMED, 150 NEEDS-RULING), ids M131-M352.
- Scope: Errors: the prose faces in `seon.error` (five AI derivations), `fit-fact-payload` and `bounded-text` storage, `value-edn`, `pr-str` of values into `:seon.error/message` (category 3b, 40 CONFIRMED), the hardcoded suggestion counts, and the scalar-interpolation class (R1).
- Files (findings per file): `src/seon/error.clj` 33, `src/seon/db.clj` 22, `src/seon/schema.clj` 17, `src/seon/sci/eval.clj` 13, `src/seon/turn.clj` 9, `src/seon/test.clj` 8, `src/seon/fn.clj` 8, `src/seon/cluster/agent.clj` 7, `src/seon/call_preparation.clj` 7.
- Depends on: P1 (options) and P3 (error render pair returns data). The R1 ruling decides the 150 scalar items; the 40 value-in-message items do not wait for it.
- Red proof candidates: Existing: `seon.error-test/diagnostic-construction-preserves-domain-members`, `seon.error-storage-bytes-test`, `seon.error-evidence-test`, `seon.test-runner-test/a-huge-execution-failure-becomes-bounded-text`. New row: every error constructor with a map, a vector and a form as offending value leaves `:seon.error/message` free of them. Static: R3 green on `seon.error*`, `seon.fault`.

### P5
- Findings: 54 (54 CONFIRMED, 0 NEEDS-RULING), ids M353-M406.
- Scope: Web, transcript and debug pages: `seon.render.web` (debug preview, header, found values, failure and page results, source call), `seon.render.transcript`, `seon.render.sent`, `seon.render.ns`, `seon.render.schema`, `seon.render.var`, `seon.render.test`. Pages pass the displayed subject, evidence, error and measurement to the printer with an HTML consumer and a debug position; chrome, URLs and escaping stay.
- Files (findings per file): `src/seon/render/web.clj` 26, `src/seon/render/transcript.clj` 19, `src/seon/render/sent.clj` 4, `src/seon/render/schema.clj` 1, `src/seon/render/var.clj` 1.
- Depends on: P1 and P3. Transcript and sent files are held by lane one-render.
- Red proof candidates: Existing: `seon.render.web-test`, `seon.render.transcript-test`, `seon.render.sent-test`, `seon.render.web-debug-test`, `seon.html-views-test`. Static: R1 and R2 green on `seon.render.web`, `seon.render.transcript`, `seon.render.sent`.

### P6
- Findings: 79 (21 CONFIRMED, 58 NEEDS-RULING), ids M407-M485.
- Scope: The rest: scripts, identity-by-print, the canonical encoder (R3), hardcoded policy numbers, top-k cuts (R4), test report composition (`seon.test`, `seon.test.runner`), the log tail, size by print.
- Files (findings per file): `src/seon/sci/admit.clj` 11, `script/seon/dev/markdown.clj` 8, `src/seon/program.cljc` 6, `script/seon/operator.clj` 4, `src/seon/test/runner.clj` 4, `script/seon/dev/docstring.clj` 3, `src/seon/test.clj` 3, `src/seon/profile.clj` 3, `src/seon/sci/reader.cljc` 2.
- Depends on: P1 for the one entry; R2, R3, R4, R5, R7, R8 rulings. Independent of P2 to P5.
- Red proof candidates: Existing red: `seon.print-census-test/no-value-is-printed-outside-the-value-printer` (extend to `script` and to the declared emitter), `seon.source-census-test/no-timeout-or-limit-is-a-hardcoded-number`. Existing green to keep: `seon.test-runner-test/a-value-testing-label-is-captured-as-its-printed-string`.
