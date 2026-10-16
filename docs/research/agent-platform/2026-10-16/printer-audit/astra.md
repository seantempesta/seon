# Independent printer audit — Astra

2026-10-16. Lane: printer-audit-astra. Read-only source audit; no implementation changes.

The principal problem is multiple owners of selection and composition. `seon.sci.eval/shown-result` is the intended print entrance, but the root value currently reaches `seon.render.value/declared` and `seon.render/declared-projection`; related units reach `seon.render.walk/neighborhood` and `seon.render/render-call`. Evaluation/history, errors, test reports and web/debug pages also compose independently printed fragments. Converting only direct renderer calls would leave these second derivations and budgets intact.

## Authority and evidence boundary

Read CLAUDE.md's entire Presentation section and “One derivation, every consumer.” The owner's 2026-10-16 instruction makes the print stage responsible for selecting each unit's schema renderer and emitting its returned data under one budget. It also explicitly says nothing calls a renderer by name. CLAUDE.md presently permits an explicit nested-renderer call; this audit uses the assignment's newer, stricter instruction. Direct nested calls below are therefore findings even where the older sentence permitted them. A self-call forwarding an arity is included as an edge, not counted as evidence of an independent domain pair.

Source basis: `db7e1b49dc4cbec1c6d3dbcdf268132f12d3dcf6`. The complete inventory was verified against this committed basis, including every source-file hash and every verbatim evidence snippet. Foreign in-flight edits later changed src/my/program.clj and src/seon/sci/lookup.clj; the quoted snippets still match, but their current file hashes differ. The audit deliberately retains the committed basis, rather than mixing snapshots. Evidence is dated: each finding includes the file, existing fully qualified symbol, source snippets and source-file SHA-256. Those line coordinates are evidence locators, not living design references. `clojure.core/print-method` names the Environment defmethod in `src/seon/env.clj`; its owner is `seon.env`.

Scope: 145 files under src/ and script/. clj-kondo analyzed src/, script/ and test/ together, so namespace aliases and first-party references resolve. Findings are restricted to src/ and script/. All 220 symbol-valued schema render attachments were collected by parsing every resources/seon/schemas/*.edn file and walking its data, including nested property maps and `:seon.render/form`. They name 126 distinct Vars; all 126 exist in the analysis. The full catalog is below. Resolved usages, enclosing definition spans and a lexical search for writers, limits and Hiccup informed source reading. Macro-expanded duplicate occurrences are deduplicated by original file/span/target. Call-site counts and finding counts are deliberately different: a finding is one category at one producer, containing all its reviewed presentation-relevant sites. Evidence may retain nearby non-presentation expressions to keep the source context intact.

**VERIFIED** means the source establishes the claimed conversion, dispatch or cut. It does not mean the function was executed in default or that a visible regression was reproduced. **INFERRED** means the source establishes the operation but its presentation relevance, semantic-bound meaning, or consolidation decision needs further consumer tracing/proof. In particular, the inventory retains independent EDN encodings as inferred candidates when their eventual display use is not established. These must not all become implementation tasks without reproduction. The audit is exhaustive over the resolved schema edges and reviewed primitive candidates; static analysis cannot prove exhaustive coverage of dynamically resolved functions, arbitrary higher-order code, Java toString implementations or unexecuted conditional loading.

`bin/seon status` and MCP runtime_status observed default alive at PID 93447 on the source basis, with health `:faulted`: 27 open faults, 387 errored receipts, 19 agent-error signatures, 9 unowned namespaces and 1 untested-hot warning. These are a pre-existing foreign boundary, not caused or repaired by this audit. No eval_clj probe, restart, reset or source load was needed. No Opus audit files, other lane sessions or work products were read. The four one-render files are included and marked in every applicable finding; their eventual conversion is not assumed complete.

## Totals

These are audit findings/candidates, not a claim that every inferred case is a defect. Categories overlap at a producer; totals are not distinct functions or runtime event counts.

| Category | Description | VERIFIED | INFERRED | Total |
|---|---|---:|---:|---:|
| 1 | Direct schema renderer references/calls | 47 | 0 | 47 |
| 2 | render-call outside the walk | 2 | 0 | 2 |
| 3 | Second value-to-text routes | 320 | 121 | 441 |
| 4 | Pre-print cuts / limit candidates | 31 | 16 | 47 |
| 5 | Domain layout outside its schema face | 21 | 0 | 21 |
| 6 | Parallel selection or presentation families | 4 | 7 | 11 |

**569 findings: 425 VERIFIED, 144 INFERRED; 502 distinct producer symbols, 88 owning namespaces.** The machine-readable inventory is [astra-findings.edn](astra-findings.edn), a vector containing one map per finding. Each map names its audience, owner, conversion, applicable pair or structural mode, missing request capability, evidence and status.

## Ten priority owners

Priority is architectural reach and evidence loss, not measured call frequency. No performance ranking is claimed.

1. **Root selection: `seon.sci.eval/shown-result`, `seon.render.value/declared`, `seon.render/declared-projection`.** The root and neighborhood have different selection entrances. Source-output renderers can execute forms via `seon.sci.eval/evaluated-source`; `seon.render.web/render-source-call` implements another route. Make the walk own selection for root and nested units; render output remains data, source and requery forms remain data. Renderer execution and ordinary user evaluation are different effects. Preserve returned values and attached render failures.
2. **Error storage: `seon.error/fit-fact-payload`, `seon.error/bounded-text`, `seon.error/value-edn`.** The inline storage allowance is divided among fields and repeatedly reduced; evidence becomes bounded text/markers before anyone prints it. Preserve structured evidence or its blob reference. Storage thresholds decide inline versus blob, never display content. The error schema pair then returns data to P. This needs a storage-owner conversion, not another print option to authorize lost evidence.
3. **Printer fallback: `seon.render.value/core-text`, `seon.render.value/datum`, `seon.render.value/throwable-text`.** core-text normally reuses the projection machinery; calling it an entirely separate normal printer would be inaccurate. Its fixed profile and emergency datum/Throwable text paths are the issue: 400 characters, 8 items, depth 3, 12 entries, 8 links, 10 frames, 3 suppressed causes and 8192 Throwable characters appear in independent policies. Use the same structural tree/emitter in the failure path, with typed opaque/error nodes and the caller's budget.
4. **Evaluation composition: `seon.repl/text`, `seon.repl/response-entries`, `seon.repl/reply-map`, `seon.repl/shown-output`, `seon.repl/live-response`.** Hand-written EDN-like envelopes, separate stdout allocation and live HTML re-selection coexist with saved AI text. Make one evaluation value and selected print tree; express input/reply/stdout/diagnostic composition and live/history position in its request. Persist exact emission and reuse it without re-budgeting it.
5. **Context/history: `seon.context/exchange-ai`, `seon.context/exchange-text`, `seon.context/block-views`, `seon.context/must-read-ai`, `seon.context/model-context`.** Direct dispatch/calls and form/reply composition bypass P; reference-tokens replaces already printed older replies with handle text. Select whole historical units for the context. Represent an omitted unit with its bound/count/path/requery through P, instead of substring or reprint policies. A whole-unit context acquisition budget is separate from a value's print budget.
6. **Test reports: `seon.test.runner/report-pair`, `seon.test.runner/message-text`, `seon.test.runner/failure-message`, `seon.test/failure-text`.** Blob storage size caps presentation; expected/actual get manually split shares; message length is compared with tokens and contexts become strings. Keep clojure.test report data intact. Its schema pair composes assertion, contexts, expected, actual and location; the shared budget emits them once. Raw assertion prose remains authored text.
7. **Web/debug: `seon.render.web/render-source-call`, `seon.render.web/debug-preview-html`, `seon.render.web/debug-header-html`, `seon.render.web/data-response`, `seon.render.web/failure-response`.** Direct pair calls, source evaluation and hand-built entity/measurement labels sit beside walk output. Page shell, URLs, CSS and escaping remain transport/UI responsibilities; the displayed subject, read evidence, error and measurement values enter P with an HTML consumer and a debug position. Debug selection inspection is data, not a reason for a second selector.
8. **Transcripts/sent attempts: `seon.render.transcript/projected-entry`, `seon.render.transcript/floor-text`, `seon.render.transcript/render-run-html`, `seon.render.sent/exchange-html`, `seon.render.sent/call-header`.** Evaluation, turn, cost and model-attempt facts acquire additional independent layouts. Share the domain derivation and select the existing exchange/attempt/turn/cost pairs by schema. Consolidation of runtime/run/history pairs is INFERRED until their subject contracts are compared; different named data may warrant different pairs.
9. **Generic mini-renderers: `seon.db/transaction-value-html`, `seon.batch/cost-cell`, `seon.id/print-canonical!`, `seon.sci.admit/leaf-edn`.** The first two recursively format arbitrary values; replace that recursion with native children in the printer tree. The latter two independently write EDN. Trace their storage/source contracts before changing them: migrate redundant text to native data, and preserve lossless machine encoding where required. Never send a truncated display string back to a reader.
10. **Operational/report cuts: `seon.dev.markdown/format-violations`, `seon.dev.docstring/format-findings`, `seon.test.platform/run-check!`, `seon.operator/-main`.** Reports are assembled and cut at a local length, subprocess failures retain only 30 lines, and operator results use ordinary printing. Return report/error data and retain the full output by reference; emit the report once. A deliberate log-tail request needs its acquisition bound and full-log reference, not an implicit claim of complete evidence.

## Missing or incompletely shared request capabilities

These are semantic gaps, not a proposal for another policy map or duplicate dials. Reuse `:seon.print/options` and the existing request schemas. The current printer already declares length, level, width, budget, namespace-maps? and table?; render-value options already include structural?; print nodes already model elision units, omitted counts, bound-by, requery forms and continuations. Those mechanisms must become the common path rather than be recreated.

| Capability | Existing seam to reuse | Conversion it enables |
|---|---|---|
| One consumer + position on the complete display request | `:seon.render/requested-output`, `:seon.render/call-request`, `:seon.sci.eval/shown-request` | AI/HTML and current/history/debug/summary differences without named face calls or parallel composers. Position needs a declared meaning; it must not become arbitrary caller-specific exceptions. |
| Root and nested schema selection through the same walk | `seon.render.walk/neighborhood`, `seon.render/render-call`, `seon.sci.eval/shown-result` | Replace root declared-projection selection and web's independent dispatch; preserve viewer namespace and explicit override precedence. |
| Structured composition under one budget | `:seon.print/options`, `:seon.print/node`, the existing projection tree | Evaluation form/reply/stdout/errors, test expected/actual and context costs share one allocation. Fragments are data, not already printed strings with local budgets. |
| Historical emission reuse and whole-unit selection | Saved `:seon.eval/shown` plus evaluation identity | Keep saved units exact; expose whole-unit elisions as data. Distinguish replay of saved emission from an explicitly requested live re-render. Do not count/reclip the same bytes in every consumer. |
| Source/code display without executing render output | Existing source/read-form concepts and structural mode | Source syntax highlighting and runnable requery forms share P. A code display mode preserves source; a separate lossless machine codec never inherits display truncation. |
| Structural/opaque/error fallback at the same entrance | Existing structural? and print-node faces | Errors, early boot and unprintable host objects need no second bounded-text writer or fixed fallback profile. Binding requirements cannot force pre-boot callers to acquire a cluster to print an error. |
| A general measurement/identity representation | Existing cost/identity schema pairs | Token, time, allocation and currency values remain data until their domain pair and final emitter. Avoid independent badges and sprintf-style scalar fragments. |
| Complete cut provenance at every display cut | Existing bound-by, omitted, path/requery/continuation fields | History, frames, shown-tests, report lists and strings identify the bound, exact or explicitly unknown count, path and runnable requery. Keep query acquisition windows and finite top-k statistics separate. |
| Read-only render scope and one retained derivation | Existing explicit viewer/database/profile inputs and content-keyed projection | Select once, return data, and emit text/hiccup from the same selection. Effects stay in the evaluation. Existing caches should be keyed by the actual inputs, not supplemented with another cache. |

No option repairs a producer that has already lost evidence, a hash built from printed text, or an independently stored copy. Those are producer/storage/identity changes. The proper result for an unbounded lazy sequence is not to count it eagerly just to fill an elision count; use the printer's unknown/pending form.

## Boundaries that must survive conversion

- Parsing source, path normalization, UTF-8 byte spans, schema grammar and edit splices use substring/take operations without clipping a displayed value. They are not category 4 merely because the operator name matches.
- Query cardinality, explicit search top-k, graph acquisition and work deadlines can bound the requested data. Their meaning and completeness must be explicit; the printer cannot force every query to acquire the universe. Profile top-k and diagnostic-neighbor limits remain INFERRED candidates in this audit for this reason.
- Literal page chrome, HTML escaping, CSS numeric encodings, subprocess argv and JSON/URL codecs are transport. Source-confirmed pure protocol sites such as `seon.render.web/debug-query-strings`, `seon.cluster.message/data-link`, `seon.operator/request-form` and `seon.operator/launch-form` are excluded from totals. Their values must still use P when displayed. `seon.render.hiccup/append-node!` is retained as INFERRED because raw scalar children can represent either emitted leaves or an unprinted domain value.
- Symbol construction, source metadata and preserved authored text are not a license to reprint arbitrary values. Required source/EDN encodings retained as INFERRED findings need a lossless migration plan, not a budgeted serializer. Identity derived by printing is a separate confirmed violation of CLAUDE.md's structural-identity rule.
- No direct Fipp invocation outside `seon.print` was found. Fipp itself remains the emission library. Existing schema renderers returning Hiccup are valid; category 5 is about domain presentation duplicated outside the correct pair, not every Hiccup literal.

## Permanent check design

Implement one deftest over fresh clj-kondo analysis, with exact source ASTs from the existing Clojure parser. A check over the whole source/schema population is preferable to an analyze-call hook alone: it can see schema attachments, aliases, higher-order references, callers and all source roots together. A hook can report the same findings while editing using `clj-kondo.hooks-api/reg-finding!`; it must consume the same rule data, not maintain a second implementation.

Proposed test name (not an installed Var): `seon.render.audit-test/presentation-has-one-owner`. The implementation must first choose the owning existing lint/test namespace; this audit adds no checker or namespace. Inputs are source bytes, schema EDN and clj-kondo analysis; outputs are ordinary finding maps with resolved symbol, source span, category and data-flow reason. Recompute when those inputs change; key reusable analysis by their content. Complexity is O(AST nodes + resolved edges + schema attachments), plus a worklist propagation over changed call summaries; no rendered values or live database needed.

1. **Derive renderer ownership from schemas.** Parse every schema resource, collect symbol-valued ai/html/form properties and retain schema/output evidence. Resolve all targets against analysis. Do not author a renderer name allowlist. Attribute uses to enclosing definitions and original spans, deduplicating .cljc/macro expansion duplicates. Include first-class usages in map/partial/apply, Var references and statically resolvable requiring-resolve literals, not just list heads. Schema data declarations themselves are not executable calls.
2. **Category 1 rule.** Outside the actual selection/emission implementation, an executable reference to a schema-declared face is a finding. A renderer needing a common transformation calls a plain data function; it does not call another face. Self-arity forwarding remains visible and can be eliminated with an ordinary private data helper. The ownership boundary is the canonical walk/printer graph rooted at shown-result, not every function transitively reachable from it and not every namespace named render. Otherwise all callers could be accidentally allowed.
3. **Category 2 rule.** A resolved executable reference to `seon.render/render-call` has exactly one owner: `seon.render.walk`. Reject other owners, including alternate root selectors that bypass this entry. A named declaration describes that ownership; it is not an ever-growing exemption list of callers.
4. **Category 3 rule.** Outside emission ownership, forbid independent generic print operations (pr-str/prn-str/print-str, pprint, Fipp, print-method/writer loops and value-capturing with-out-str) on display flows. Track calls through aliases and wrappers. For str/format/StringBuilder/.write, distinguish literal/already emitted text and protocol codecs from native domain values using declared contracts plus local/call-return data flow. Error-message, log, task-plan, test-report, text response and Hiccup-child positions are sinks. A native value-to-text conversion at one of those sinks fails. Required codecs are declared by their input/output meaning and verified round-trip behavior, not by a hand exemption list. Printing to derive identity fails separately and moves to the structural digest owner.
5. **Category 4 rule.** Propagate display-flow origin through local bindings, helpers and returns. A size-based subs/take/take-last/subvec/count threshold/token threshold reaching a display sink outside the canonical budget selection is a finding. Semantic parsing and explicit acquisition/top-k contracts must be distinguishable by data meaning, including completeness/requery where relevant. Reject post-print slicing and local budget arithmetic unconditionally on emitted text. Do not ban every take or assume a function named bounded is necessarily clipping.
6. **Proofs for the checker.** Mutation fixtures introduce an aliased direct face call, first-class map/partial face, illegal render-call, a wrapper around pr-str, formatting a number into an error/Hiccup node, a hidden StringBuilder writer, a history token cutoff and a helper performing subs on displayed data. Each must produce the correct finding at its original source span. Positive fixtures cover source parsing, URL/JSON codecs, explicitly requested query windows, literal prose, the printer's own emitter and reuse of a saved emission. Include .cljc branches, anonymous functions, quoted symbols versus executable resolving, and a schema file that introduces a previously unknown renderer: its direct call must fail without editing checker rules.

There is a real limit: clj-kondo resolves Vars, not arbitrary runtime types or complete effect/data flow. Do not claim this check can prove absence of all dynamic printers. Unresolved/dynamic display paths should produce an explicit analysis finding with the unknown edge, requiring a declared contract or bounded proof; they must never be silently classified clean. Categories 1–2 and raw generic printing can be enforced mechanically. Complete 3–4 enforcement needs the display/codec/acquisition distinctions above, followed by end-to-end tests through P. This is an architecture ownership test requested by the owner, not a runtime refusal path around faulty presentation.

Dependency seams read locally: clj-kondo `clj-kondo.hooks-api/reg-finding!`, `clj-kondo.hooks-api/resolve`, `clj-kondo.hooks-api/ns-analysis` at eb245d8260736ca463cda85219b6fc3ae73699c3; Fipp serialization at 255a19061fdc20d67eca09a17e9af282aa8bf909; Malli pinned at dd13ff81139159778edb0a67b491eccc0baa991d. Use the installed `seon.fn.analyzer` analysis owner when implementing; do not add another analyzer. The findings inventory is evidence, not a checker allowlist or grandfathered baseline.

## Reproduction and verification

The analysis command was:

```sh
clj-kondo --lint src script test --cache false --config '{:output {:format :json} :analysis {:var-usages {:shallow false} :locals true :arglists true}}'
```

It analyzed 591 files in 15,746 ms: 0 errors, 1,802 warnings, 15 informational findings. Exit 2 was the warnings exit status; it was not zero findings. Schema collection used clojure.edn/read-string, clojure.walk/postwalk and symbol? on property values, retaining resource path and top-level schema key; every collected symbol was joined to clj-kondo's var-definitions. Candidate joins use var-usages.to/name, enclosing definition spans and original source ranges. Source reading then classified displays, parsers, encodings and explicit windows. Nothing was run as SCI root. Runtime health evidence above is observation only, not a test pass.

Final cache-disabled lint again analyzed 591 files: 0 errors, 1,802 warnings, 15 informational findings; reported analysis 7,996 ms, wall time 9.33 s, CPU 8.87 s user + 0.41 s system, maximum RSS 1,429,274,624 bytes (about 1.33 GiB). This measures the audit tool, not Seon runtime performance. Artifact validation parsed all 569 EDN maps, checked unique IDs and producer/category pairs, resolved every first-party producer and related symbol against the analysis, and verified every evidence snippet and SHA-256 against the committed basis. The one external multimethod, clojure.core/print-method, was verified in the pinned Clojure source and the Environment defmethod. All 126 cataloged renderer Vars resolved. Source/test line delta is zero. Both outputs are documentation only. `bin/lane` explicitly uses `seon.test.cache/gate-input?` to skip a throwaway JVM when all committed paths are documentation and no test is named. No executable test reaches these two artifacts; inventing a --test would not prove this audit. No runtime tests were run or claimed green, and no pre-existing test reds were accepted. The lane's exact two-path commit remains separate from foreign test/source work.

## Namespace totals

C1–C6 have the meanings in the totals table. Counts include INFERRED candidates.

| Owner | C1 | C2 | C3 | C4 | C5 | C6 | Total |
|---|---:|---:|---:|---:|---:|---:|---:|
| `my.background` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `my.program` | 1 | 0 | 3 | 1 | 0 | 0 | 5 |
| `my.root` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.agent` | 0 | 0 | 2 | 0 | 0 | 0 | 2 |
| `seon.ai` | 4 | 0 | 9 | 0 | 0 | 0 | 13 |
| `seon.background` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.batch` | 0 | 0 | 6 | 1 | 1 | 0 | 8 |
| `seon.call-preparation` | 0 | 0 | 7 | 0 | 0 | 0 | 7 |
| `seon.central-round` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.check-corpus` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.cluster` | 0 | 0 | 11 | 0 | 0 | 0 | 11 |
| `seon.cluster.agent` | 2 | 0 | 15 | 0 | 0 | 1 | 18 |
| `seon.cluster.boot` | 1 | 0 | 3 | 0 | 0 | 0 | 4 |
| `seon.cluster.host` | 0 | 0 | 2 | 0 | 0 | 0 | 2 |
| `seon.cluster.instruction` | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| `seon.cluster.message` | 2 | 0 | 7 | 0 | 0 | 1 | 10 |
| `seon.cluster.process` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.cluster.registry` | 0 | 0 | 5 | 0 | 0 | 0 | 5 |
| `seon.cluster.source` | 0 | 0 | 5 | 0 | 0 | 0 | 5 |
| `seon.cluster.status` | 1 | 0 | 1 | 0 | 0 | 0 | 2 |
| `seon.cluster.store` | 0 | 0 | 6 | 0 | 0 | 0 | 6 |
| `seon.config` | 0 | 0 | 4 | 0 | 0 | 0 | 4 |
| `seon.context` | 3 | 1 | 5 | 2 | 2 | 2 | 15 |
| `seon.contracts` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.db` | 0 | 0 | 25 | 3 | 1 | 0 | 29 |
| `seon.dev.context-corpus` | 0 | 0 | 1 | 1 | 0 | 0 | 2 |
| `seon.dev.dependency-digest` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.dev.docstring` | 0 | 0 | 2 | 1 | 0 | 0 | 3 |
| `seon.dev.harness-costs` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.dev.markdown` | 0 | 0 | 7 | 1 | 0 | 0 | 8 |
| `seon.dev.mcp` | 0 | 0 | 4 | 0 | 0 | 0 | 4 |
| `seon.docstring-headings` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.edit` | 0 | 0 | 0 | 1 | 0 | 0 | 1 |
| `seon.edit.jvm` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.effect` | 0 | 0 | 3 | 0 | 0 | 0 | 3 |
| `seon.env` | 0 | 0 | 4 | 0 | 0 | 0 | 4 |
| `seon.error` | 2 | 0 | 24 | 10 | 0 | 1 | 37 |
| `seon.error.refusal` | 0 | 0 | 3 | 0 | 0 | 0 | 3 |
| `seon.eval.drive` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.fault` | 0 | 0 | 2 | 0 | 0 | 0 | 2 |
| `seon.fn` | 0 | 0 | 12 | 0 | 0 | 0 | 12 |
| `seon.fn.analyzer` | 0 | 0 | 2 | 0 | 0 | 0 | 2 |
| `seon.fn.signature` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.fs` | 0 | 0 | 4 | 0 | 0 | 0 | 4 |
| `seon.fs.jvm` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.health` | 1 | 0 | 4 | 1 | 0 | 0 | 6 |
| `seon.id` | 0 | 0 | 2 | 0 | 0 | 0 | 2 |
| `seon.instrument` | 0 | 0 | 8 | 0 | 0 | 0 | 8 |
| `seon.interpreted-census` | 0 | 0 | 2 | 0 | 0 | 0 | 2 |
| `seon.maintenance` | 0 | 0 | 2 | 0 | 0 | 0 | 2 |
| `seon.mcp` | 3 | 0 | 4 | 0 | 0 | 0 | 7 |
| `seon.merge` | 0 | 0 | 2 | 0 | 0 | 0 | 2 |
| `seon.operator` | 0 | 0 | 7 | 1 | 0 | 0 | 8 |
| `seon.problems` | 2 | 0 | 6 | 2 | 1 | 0 | 11 |
| `seon.profile` | 0 | 0 | 0 | 3 | 0 | 0 | 3 |
| `seon.program` | 0 | 0 | 7 | 0 | 0 | 0 | 7 |
| `seon.reconcile` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.render` | 4 | 0 | 6 | 0 | 0 | 1 | 11 |
| `seon.render.hiccup` | 0 | 0 | 2 | 0 | 0 | 0 | 2 |
| `seon.render.ns` | 0 | 0 | 6 | 0 | 0 | 0 | 6 |
| `seon.render.schema` | 0 | 0 | 4 | 0 | 0 | 0 | 4 |
| `seon.render.sent` | 1 | 0 | 3 | 0 | 1 | 1 | 6 |
| `seon.render.transcript` | 4 | 0 | 12 | 1 | 5 | 1 | 23 |
| `seon.render.value` | 0 | 0 | 4 | 7 | 0 | 1 | 12 |
| `seon.render.var` | 0 | 0 | 1 | 1 | 0 | 0 | 2 |
| `seon.render.walk` | 0 | 0 | 2 | 0 | 1 | 0 | 3 |
| `seon.render.web` | 10 | 1 | 27 | 0 | 9 | 0 | 47 |
| `seon.repl` | 1 | 0 | 10 | 2 | 0 | 1 | 14 |
| `seon.schema` | 0 | 0 | 21 | 0 | 0 | 0 | 21 |
| `seon.schema.admission` | 0 | 0 | 6 | 1 | 0 | 0 | 7 |
| `seon.schema.datahike` | 0 | 0 | 3 | 0 | 0 | 0 | 3 |
| `seon.schema.edn` | 0 | 0 | 4 | 0 | 0 | 0 | 4 |
| `seon.schema.internal` | 0 | 0 | 3 | 0 | 0 | 0 | 3 |
| `seon.sci.admit` | 0 | 0 | 11 | 0 | 0 | 0 | 11 |
| `seon.sci.eval` | 0 | 0 | 15 | 1 | 0 | 0 | 16 |
| `seon.sci.kernel` | 0 | 0 | 2 | 0 | 0 | 0 | 2 |
| `seon.sci.lookup` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.sci.reader` | 0 | 0 | 3 | 0 | 0 | 0 | 3 |
| `seon.sentinel` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `seon.task` | 2 | 0 | 7 | 0 | 0 | 0 | 9 |
| `seon.test` | 0 | 0 | 15 | 1 | 0 | 1 | 17 |
| `seon.test.accretion` | 1 | 0 | 2 | 1 | 0 | 0 | 4 |
| `seon.test.platform` | 0 | 0 | 0 | 1 | 0 | 0 | 1 |
| `seon.test.runner` | 1 | 0 | 11 | 2 | 0 | 0 | 14 |
| `seon.turn` | 0 | 0 | 12 | 0 | 0 | 0 | 12 |
| `seon.turn.batch` | 0 | 0 | 3 | 0 | 0 | 0 | 3 |
| `seon.vocabulary` | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| `start-flow-benchmark` | 0 | 0 | 0 | 1 | 0 | 0 | 1 |

## one-render overlap

The assignment says one-render holds these files. This table inventories them without claiming or editing them. The EDN `:one-render-lane` field marks each finding.

| File | Categories and counts | Finding IDs |
|---|---|---|
| src/seon/context.clj | C1: 3, C2: 1, C3: 5, C4: 2, C5: 2, C6: 2 | A013, A014, A015, A048, A143, A144, A145, A146, A147, A493, A494, A539, A540, A561, A562 |
| src/seon/repl.clj | C1: 1, C3: 10, C4: 2, C6: 1 | A043, A360, A361, A362, A363, A364, A365, A366, A367, A368, A369, A528, A529, A568 |
| src/seon/render/transcript.clj | C1: 4, C3: 12, C4: 1, C5: 5, C6: 1 | A025, A026, A027, A028, A308, A309, A310, A311, A312, A313, A314, A315, A316, A317, A318, A319, A519, A544, A545, A546, A547, A548, A565 |
| src/seon/render/sent.clj | C1: 1, C3: 3, C5: 1, C6: 1 | A024, A305, A306, A307, A543, A564 |

## Renderer catalog derived from schema resources

All 126 symbols below resolve to definitions. Each attachment is listed with its schema, face and resource, including shared generic faces and form renderers. This catalog is generated evidence, not an authority to be maintained by hand.

| Renderer Var | Schema attachments (face; resource) |
|---|---|
| `my.program/render-breakage-ai` | `:seon.program/breakage` (ai; resources/seon/schemas/seon.program.edn) |
| `my.program/render-source-ai` | `:my.program/declaration-source` (ai; resources/seon/schemas/my.program.edn) |
| `my.turn/usage-form` | `:my.turn/usage-unit` (form; resources/seon/schemas/my.turn.edn) |
| `seon.agent/render-settings-ai` | `:seon.agent/settings` (ai; resources/seon/schemas/seon.agent.edn) |
| `seon.agent/render-settings-form` | `:seon.agent/settings` (form; resources/seon/schemas/seon.agent.edn) |
| `seon.agent/render-settings-html` | `:seon.agent/settings` (html; resources/seon/schemas/seon.agent.edn) |
| `seon.ai/attempt-ai` | `:seon.ai/attempt` (ai; resources/seon/schemas/seon.ai.edn) |
| `seon.ai/attempt-html` | `:seon.ai/attempt` (html; resources/seon/schemas/seon.ai.edn) |
| `seon.ai/model-ai` | `:seon.ai.model/entity` (ai; resources/seon/schemas/seon.ai.model.edn) |
| `seon.ai/model-html` | `:seon.ai.model/entity` (html; resources/seon/schemas/seon.ai.model.edn) |
| `seon.ai/provider-ai` | `:seon.ai.model/provider-entity` (ai; resources/seon/schemas/seon.ai.model.edn) |
| `seon.ai/provider-html` | `:seon.ai.model/provider-entity` (html; resources/seon/schemas/seon.ai.model.edn) |
| `seon.background/render-ai` | `:my.background/invalid-call-error` (ai; resources/seon/schemas/my.background.edn)<br>`:my.background/missing-result-error` (ai; resources/seon/schemas/my.background.edn)<br>`:my.background/invalid-result-error` (ai; resources/seon/schemas/my.background.edn) |
| `seon.background/render-html` | `:my.background/invalid-call-error` (html; resources/seon/schemas/my.background.edn)<br>`:my.background/missing-result-error` (html; resources/seon/schemas/my.background.edn)<br>`:my.background/invalid-result-error` (html; resources/seon/schemas/my.background.edn) |
| `seon.batch/render-request-cost-ai` | `:seon.cost/request` (ai; resources/seon/schemas/seon.cost.edn) |
| `seon.batch/render-request-cost-html` | `:seon.cost/request` (html; resources/seon/schemas/seon.cost.edn) |
| `seon.bootstrap/render-help-ai` | `:seon.help/help` (ai; resources/seon/schemas/seon.help.edn) |
| `seon.bootstrap/render-help-html` | `:seon.help/help` (html; resources/seon/schemas/seon.help.edn) |
| `seon.cluster.agent/identity-form` | `:seon.agent/agent` (form; resources/seon/schemas/seon.agent.edn) |
| `seon.cluster.agent/render-creation-ai` | `:seon.agent/creation-result` (ai; resources/seon/schemas/seon.agent.edn) |
| `seon.cluster.agent/render-creation-html` | `:seon.agent/creation-result` (html; resources/seon/schemas/seon.agent.edn) |
| `seon.cluster.agent/render-identity-ai` | `:seon.agent/agent` (ai; resources/seon/schemas/seon.agent.edn) |
| `seon.cluster.agent/render-identity-html` | `:seon.agent/agent` (html; resources/seon/schemas/seon.agent.edn) |
| `seon.cluster.agent/render-situation-ai` | `:seon.agent/situation` (ai; resources/seon/schemas/seon.agent.edn) |
| `seon.cluster.agent/situation-form` | `:seon.agent/situation` (form; resources/seon/schemas/seon.agent.edn) |
| `seon.cluster.instruction/instruction-ai` | `:seon.cluster.instruction/instruction` (ai; resources/seon/schemas/seon.cluster.instruction.edn) |
| `seon.cluster.instruction/instruction-html` | `:seon.cluster.instruction/instruction` (html; resources/seon/schemas/seon.cluster.instruction.edn) |
| `seon.cluster.message/inbox-form` | `:seon.message/to` (form; resources/seon/schemas/seon.message.edn) |
| `seon.cluster.message/render-ai` | `:seon.message/message` (ai; resources/seon/schemas/seon.message.edn) |
| `seon.cluster.message/render-html` | `:seon.message/message` (html; resources/seon/schemas/seon.message.edn) |
| `seon.cluster.message/render-inbox-ai` | `:seon.message/to` (ai; resources/seon/schemas/seon.message.edn) |
| `seon.cluster.message/render-inbox-html` | `:seon.message/to` (html; resources/seon/schemas/seon.message.edn) |
| `seon.cluster.status/render-ai` | `:seon.cluster.status/concern` (ai; resources/seon/schemas/seon.cluster.status.edn) |
| `seon.cluster.status/render-form` | `:seon.cluster.status/concern` (form; resources/seon/schemas/seon.cluster.status.edn) |
| `seon.cluster.status/render-html` | `:seon.cluster.status/concern` (html; resources/seon/schemas/seon.cluster.status.edn) |
| `seon.cluster/render-ai` | `:seon.cluster/cluster` (ai; resources/seon/schemas/seon.cluster.edn) |
| `seon.cluster/render-form` | `:seon.cluster/cluster` (form; resources/seon/schemas/seon.cluster.edn) |
| `seon.cluster/render-html` | `:seon.cluster/cluster` (html; resources/seon/schemas/seon.cluster.edn) |
| `seon.context/current-state-ai` | `:seon.context/current-state` (ai; resources/seon/schemas/seon.context.edn) |
| `seon.context/exchange-ai` | `:seon.context/exchange` (ai; resources/seon/schemas/seon.context.edn) |
| `seon.context/exchange-html` | `:seon.context/exchange` (html; resources/seon/schemas/seon.context.edn) |
| `seon.db/render-rejection-ai` | `:seon.db.write/validation-refusal` (ai; resources/seon/schemas/seon.db.write.edn) |
| `seon.db/render-rejection-html` | `:seon.db.write/validation-refusal` (html; resources/seon/schemas/seon.db.write.edn) |
| `seon.db/render-transaction-ai` | `:seon.db/transaction-report` (ai; resources/seon/schemas/seon.db.edn) |
| `seon.db/render-transaction-html` | `:seon.db/transaction-report` (html; resources/seon/schemas/seon.db.edn) |
| `seon.effect/render-ai` | `:seon.effect/receipt` (ai; resources/seon/schemas/seon.effect.edn) |
| `seon.effect/render-html` | `:seon.effect/receipt` (html; resources/seon/schemas/seon.effect.edn) |
| `seon.error/ai-prose` | `:seon.error/notice` (ai; resources/seon/schemas/seon.error.edn) |
| `seon.error/elision-html` | `:seon.render.walk/elided-error` (html; resources/seon/schemas/seon.render.walk.edn) |
| `seon.error/elision-prose` | `:seon.render.walk/elided-error` (ai; resources/seon/schemas/seon.render.walk.edn) |
| `seon.error/faults-form` | `:seon.error/of-steward` (form; resources/seon/schemas/seon.error.edn) |
| `seon.error/finding-ai` | `:seon.error/error` (ai; resources/seon/schemas/seon.error.edn) |
| `seon.error/mcp-prose` | `:seon.dev.mcp/projection-failed-error` (ai; resources/seon/schemas/seon.dev.mcp.edn) |
| `seon.error/render-ai` | `:seon.error/base` (ai; resources/seon/schemas/seon.error.edn)<br>`:seon.error/fact` (ai; resources/seon/schemas/seon.error.edn)<br>`:seon.fn/index-refused-error` (ai; resources/seon/schemas/seon.fn.edn)<br>`:my.web/redirect-limit-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.web/missing-location-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.web/redirect-loop-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.web/invalid-url-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.web/unparseable-response-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.web/timeout-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.web/transport-failed-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.web/no-credential-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.web/response-limit-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.web/provider-failed-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.web/projection-failed-error` (ai; resources/seon/schemas/my.web.edn)<br>`:my.turn/blank-result-error` (ai; resources/seon/schemas/my.turn.edn)<br>`:my.turn/blank-note-error` (ai; resources/seon/schemas/my.turn.edn)<br>`:seon.schema/invalid-schema-error` (ai; resources/seon/schemas/seon.schema.edn)<br>`:seon.schema/undefined-contract-error` (ai; resources/seon/schemas/seon.schema.edn)<br>`:seon.schema/unknown-shape-error` (ai; resources/seon/schemas/seon.schema.edn)<br>`:seon.schema/missing-projection-error` (ai; resources/seon/schemas/seon.schema.edn)<br>`:seon.agent/no-such-agent-error` (ai; resources/seon/schemas/seon.agent.edn)<br>`:my.message/no-about-error` (ai; resources/seon/schemas/my.message.edn)<br>`:my.message/no-recipient-error` (ai; resources/seon/schemas/my.message.edn)<br>`:my.message/no-content-error` (ai; resources/seon/schemas/my.message.edn)<br>`:seon.bootstrap/agent-not-found-error` (ai; resources/seon/schemas/seon.bootstrap.edn)<br>`:my.fs/not-regular-file-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/changed-during-read-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/blob-unavailable-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/read-limit-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/glob-failed-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/not-directory-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/read-failed-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/write-limit-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/stale-digest-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/already-exists-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/write-failed-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/not-found-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/path-refused-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/invalid-utf8-window-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/atomic-write-unsupported-error` (ai; resources/seon/schemas/my.fs.edn)<br>`:my.fs/invalid-glob-error` (ai; resources/seon/schemas/my.fs.edn) |
| `seon.error/render-chain-ai` | `:seon.error/chain` (ai; resources/seon/schemas/seon.error.edn) |
| `seon.error/render-chain-html` | `:seon.error/chain` (html; resources/seon/schemas/seon.error.edn) |
| `seon.error/render-faults-ai` | `:seon.error/of-steward` (ai; resources/seon/schemas/seon.error.edn)<br>`:seon.error/agent` (ai; resources/seon/schemas/seon.error.edn) |
| `seon.error/render-faults-html` | `:seon.error/of-steward` (html; resources/seon/schemas/seon.error.edn)<br>`:seon.error/agent` (html; resources/seon/schemas/seon.error.edn) |
| `seon.error/render-form` | `:seon.error/base` (form; resources/seon/schemas/seon.error.edn) |
| `seon.error/render-html` | `:seon.error/error` (html; resources/seon/schemas/seon.error.edn)<br>`:seon.error/base` (html; resources/seon/schemas/seon.error.edn)<br>`:seon.error/fact` (html; resources/seon/schemas/seon.error.edn)<br>`:seon.fn/index-refused-error` (html; resources/seon/schemas/seon.fn.edn)<br>`:my.web/redirect-limit-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.web/missing-location-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.web/redirect-loop-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.web/invalid-url-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.web/unparseable-response-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.web/timeout-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.web/transport-failed-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.web/no-credential-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.web/response-limit-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.web/provider-failed-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.web/projection-failed-error` (html; resources/seon/schemas/my.web.edn)<br>`:my.turn/blank-result-error` (html; resources/seon/schemas/my.turn.edn)<br>`:my.turn/blank-note-error` (html; resources/seon/schemas/my.turn.edn)<br>`:seon.schema/invalid-schema-error` (html; resources/seon/schemas/seon.schema.edn)<br>`:seon.schema/undefined-contract-error` (html; resources/seon/schemas/seon.schema.edn)<br>`:seon.schema/unknown-shape-error` (html; resources/seon/schemas/seon.schema.edn)<br>`:seon.schema/missing-projection-error` (html; resources/seon/schemas/seon.schema.edn)<br>`:seon.agent/no-such-agent-error` (html; resources/seon/schemas/seon.agent.edn)<br>`:my.message/no-about-error` (html; resources/seon/schemas/my.message.edn)<br>`:my.message/no-recipient-error` (html; resources/seon/schemas/my.message.edn)<br>`:my.message/no-content-error` (html; resources/seon/schemas/my.message.edn)<br>`:seon.bootstrap/agent-not-found-error` (html; resources/seon/schemas/seon.bootstrap.edn)<br>`:my.fs/not-regular-file-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/changed-during-read-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/blob-unavailable-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/read-limit-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/glob-failed-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/not-directory-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/read-failed-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/write-limit-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/stale-digest-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/already-exists-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/write-failed-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/not-found-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/path-refused-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/invalid-utf8-window-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/atomic-write-unsupported-error` (html; resources/seon/schemas/my.fs.edn)<br>`:my.fs/invalid-glob-error` (html; resources/seon/schemas/my.fs.edn) |
| `seon.health/render-ai` | `:seon.health/report` (ai; resources/seon/schemas/seon.health.edn) |
| `seon.health/render-html` | `:seon.health/report` (html; resources/seon/schemas/seon.health.edn) |
| `seon.mcp/render-request-reply-ai` | `:seon.repl/request-reply` (ai; resources/seon/schemas/seon.repl.edn) |
| `seon.mcp/render-request-reply-html` | `:seon.repl/request-reply` (html; resources/seon/schemas/seon.repl.edn) |
| `seon.print/render-elision-ai` | `:seon.print/elision` (ai; resources/seon/schemas/seon.print.edn) |
| `seon.problems/ai-prose` | `:seon.problems/problems` (ai; resources/seon/schemas/seon.problems.edn) |
| `seon.problems/html-report` | `:seon.problems/problems` (html; resources/seon/schemas/seon.problems.edn) |
| `seon.problems/missing-model-ai` | `:seon.problems/missing-model` (ai; resources/seon/schemas/seon.problems.edn) |
| `seon.problems/missing-model-html` | `:seon.problems/missing-model` (html; resources/seon/schemas/seon.problems.edn) |
| `seon.ref/render-doc-ai` | `:seon.ref/resolved-doc` (ai; resources/seon/schemas/seon.ref.edn) |
| `seon.ref/render-doc-html` | `:seon.ref/resolved-doc` (html; resources/seon/schemas/seon.ref.edn) |
| `seon.render.ns/agent-namespace-ai` | `:seon.agent/namespace` (ai; resources/seon/schemas/seon.agent.edn) |
| `seon.render.ns/agent-namespace-form` | `:seon.agent/namespace` (form; resources/seon/schemas/seon.agent.edn) |
| `seon.render.ns/function-ai` | `:seon.fn/fn` (ai; resources/seon/schemas/seon.fn.edn) |
| `seon.render.ns/function-html` | `:seon.fn/fn` (html; resources/seon/schemas/seon.fn.edn) |
| `seon.render.ns/held-task-ai` | `:seon.task/of-agent` (ai; resources/seon/schemas/seon.task.edn) |
| `seon.render.ns/held-task-form` | `:seon.task/of-agent` (form; resources/seon/schemas/seon.task.edn) |
| `seon.render.ns/held-task-html` | `:seon.task/of-agent` (html; resources/seon/schemas/seon.task.edn) |
| `seon.render.ns/namespace-form` | `:seon.ns/ns` (form; resources/seon/schemas/seon.ns.edn) |
| `seon.render.ns/render-ai` | `:seon.ns/ns` (ai; resources/seon/schemas/seon.ns.edn) |
| `seon.render.ns/render-alias-ai` | `:seon.ns.alias/binding` (ai; resources/seon/schemas/seon.ns.alias.edn) |
| `seon.render.ns/render-alias-form` | `:seon.ns.alias/binding` (form; resources/seon/schemas/seon.ns.alias.edn) |
| `seon.render.ns/render-alias-html` | `:seon.ns.alias/binding` (html; resources/seon/schemas/seon.ns.alias.edn) |
| `seon.render.ns/render-html` | `:seon.ns/ns` (html; resources/seon/schemas/seon.ns.edn) |
| `seon.render.ns/render-import-ai` | `:seon.ns.import/binding` (ai; resources/seon/schemas/seon.ns.import.edn) |
| `seon.render.ns/render-import-html` | `:seon.ns.import/binding` (html; resources/seon/schemas/seon.ns.import.edn) |
| `seon.render.ns/render-refer-ai` | `:seon.ns.refer/binding` (ai; resources/seon/schemas/seon.ns.refer.edn) |
| `seon.render.ns/render-refer-form` | `:seon.ns.refer/binding` (form; resources/seon/schemas/seon.ns.refer.edn) |
| `seon.render.ns/render-refer-html` | `:seon.ns.refer/binding` (html; resources/seon/schemas/seon.ns.refer.edn) |
| `seon.render.ns/task-ai` | `:seon.task/task` (ai; resources/seon/schemas/seon.task.edn) |
| `seon.render.ns/task-form` | `:seon.task/task` (form; resources/seon/schemas/seon.task.edn) |
| `seon.render.ns/task-html` | `:seon.task/task` (html; resources/seon/schemas/seon.task.edn) |
| `seon.render.schema/render-ai` | `:seon.schema/registration` (ai; resources/seon/schemas/seon.schema.edn)<br>`:seon.schema/schema` (ai; resources/seon/schemas/seon.schema.edn) |
| `seon.render.schema/render-form` | `:seon.schema/registration` (form; resources/seon/schemas/seon.schema.edn)<br>`:seon.schema/schema` (form; resources/seon/schemas/seon.schema.edn) |
| `seon.render.schema/render-html` | `:seon.schema/registration` (html; resources/seon/schemas/seon.schema.edn)<br>`:seon.schema/schema` (html; resources/seon/schemas/seon.schema.edn) |
| `seon.render.sent/view-html` | `:seon.render.sent/view` (html; resources/seon/schemas/seon.render.sent.edn) |
| `seon.render.test/render-ai` | `:seon.test/result` (ai; resources/seon/schemas/seon.test.edn)<br>`:seon.test/test` (ai; resources/seon/schemas/seon.test.edn) |
| `seon.render.test/render-form` | `:seon.test/result` (form; resources/seon/schemas/seon.test.edn)<br>`:seon.test/test` (form; resources/seon/schemas/seon.test.edn) |
| `seon.render.test/render-html` | `:seon.test/result` (html; resources/seon/schemas/seon.test.edn)<br>`:seon.test/test` (html; resources/seon/schemas/seon.test.edn) |
| `seon.render.test/render-run-ai` | `:seon.test.run/run` (ai; resources/seon/schemas/seon.test.run.edn) |
| `seon.render.test/render-run-form` | `:seon.test.run/run` (form; resources/seon/schemas/seon.test.run.edn) |
| `seon.render.test/render-run-result-ai` | `:seon.test/run-result` (ai; resources/seon/schemas/seon.test.edn) |
| `seon.render.transcript/message-form` | `:seon.message/message` (form; resources/seon/schemas/seon.message.edn) |
| `seon.render.transcript/render-history-ai` | `:seon.turn/agent` (ai; resources/seon/schemas/seon.turn.edn) |
| `seon.render.transcript/render-history-html` | `:seon.turn/agent` (html; resources/seon/schemas/seon.turn.edn) |
| `seon.render.transcript/render-run-ai` | `:seon.turn/turn` (ai; resources/seon/schemas/seon.turn.edn) |
| `seon.render.transcript/render-run-html` | `:seon.turn/turn` (html; resources/seon/schemas/seon.turn.edn) |
| `seon.render.transcript/render-runtime-ai` | `:seon.runtime/entity` (ai; resources/seon/schemas/seon.runtime.edn) |
| `seon.render.transcript/render-runtime-form` | `:seon.runtime/entity` (form; resources/seon/schemas/seon.runtime.edn) |
| `seon.render.transcript/render-runtime-html` | `:seon.runtime/entity` (html; resources/seon/schemas/seon.runtime.edn) |
| `seon.render.value/render-ai` | `:seon.render/call-request` (ai; resources/seon/schemas/seon.render.edn) |
| `seon.render.value/render-database-identity-ai` | `:seon.db/database-value-identity` (ai; resources/seon/schemas/seon.db.edn) |
| `seon.render.value/render-html` | `:seon.program/breakage` (html; resources/seon/schemas/seon.program.edn)<br>`:seon.render/call-request` (html; resources/seon/schemas/seon.render.edn) |
| `seon.render.var/render-ai` | `:seon.render.var/var` (ai; resources/seon/schemas/seon.render.var.edn) |
| `seon.render.var/render-html` | `:seon.render.var/var` (html; resources/seon/schemas/seon.render.var.edn) |
| `seon.render/unknown-ai` | `:seon.render/unknown` (ai; resources/seon/schemas/seon.render.edn) |
| `seon.render/unknown-html` | `:seon.render/unknown` (html; resources/seon/schemas/seon.render.edn) |
| `seon.repl/render-ai` | `:seon.cluster.eval/receipt` (ai; resources/seon/schemas/seon.cluster.eval.edn) |
| `seon.repl/render-directory-ai` | `:seon.repl/directory` (ai; resources/seon/schemas/seon.repl.edn) |
| `seon.repl/render-directory-html` | `:seon.repl/directory` (html; resources/seon/schemas/seon.repl.edn) |
| `seon.repl/render-html` | `:seon.cluster.eval/receipt` (html; resources/seon/schemas/seon.cluster.eval.edn) |
| `seon.task/attempt-html` | `:seon.chain/attempt-view` (html; resources/seon/schemas/seon.chain.edn) |
| `seon.task/chain-html` | `:seon.chain/view` (html; resources/seon/schemas/seon.chain.edn) |
| `seon.task/chain-task-html` | `:seon.chain/task-view` (html; resources/seon/schemas/seon.chain.edn) |
| `seon.test.accretion/render-ai` | `:seon.test.accretion/install-refused-error` (ai; resources/seon/schemas/seon.test.accretion.edn) |
| `seon.test.accretion/render-html` | `:seon.test.accretion/install-refused-error` (html; resources/seon/schemas/seon.test.accretion.edn) |

## Finding index

Full evidence and per-finding conversion are in the EDN inventory. VERIFIED is source verification, not runtime reproduction. Symbols below were checked against the same analysis.

| ID | Category | Status | Producer | File |
|---|---:|---|---|---|
| A001 | 1 | VERIFIED | `my.program/search-match` | src/my/program.clj |
| A002 | 1 | VERIFIED | `seon.ai/attempt-ai` | src/seon/ai.clj |
| A003 | 1 | VERIFIED | `seon.ai/attempt-html` | src/seon/ai.clj |
| A004 | 1 | VERIFIED | `seon.ai/registry-ai` | src/seon/ai.clj |
| A005 | 1 | VERIFIED | `seon.ai/registry-html` | src/seon/ai.clj |
| A006 | 1 | VERIFIED | `seon.cluster.agent/render-id-ai` | src/seon/cluster/agent.clj |
| A007 | 1 | VERIFIED | `seon.cluster.agent/render-id-html` | src/seon/cluster/agent.clj |
| A008 | 1 | VERIFIED | `seon.cluster.boot/request!` | src/seon/cluster/boot.clj |
| A009 | 1 | VERIFIED | `seon.cluster.instruction/instruction-html` | src/seon/cluster/instruction.clj |
| A010 | 1 | VERIFIED | `seon.cluster.message/inbox-html` | src/seon/cluster/message.clj |
| A011 | 1 | VERIFIED | `seon.cluster.message/render-inbox-html` | src/seon/cluster/message.clj |
| A012 | 1 | VERIFIED | `seon.cluster.status/render-html` | src/seon/cluster/status.clj |
| A013 | 1 | VERIFIED | `seon.context/block-views` | src/seon/context.clj |
| A014 | 1 | VERIFIED | `seon.context/exchange-html` | src/seon/context.clj |
| A015 | 1 | VERIFIED | `seon.context/model-context` | src/seon/context.clj |
| A016 | 1 | VERIFIED | `seon.error/render-faults-ai` | src/seon/error.clj |
| A017 | 1 | VERIFIED | `seon.error/render-faults-html` | src/seon/error.clj |
| A018 | 1 | VERIFIED | `seon.health/render-html` | src/seon/health.clj |
| A019 | 1 | VERIFIED | `seon.mcp/render-request-reply-ai` | src/seon/mcp.clj |
| A020 | 1 | VERIFIED | `seon.mcp/render-request-reply-html` | src/seon/mcp.clj |
| A021 | 1 | VERIFIED | `seon.mcp/runtime-status` | src/seon/mcp.clj |
| A022 | 1 | VERIFIED | `seon.problems/ai-prose` | src/seon/problems.clj |
| A023 | 1 | VERIFIED | `seon.problems/html-report` | src/seon/problems.clj |
| A024 | 1 | VERIFIED | `seon.render.sent/exchange-html` | src/seon/render/sent.clj |
| A025 | 1 | VERIFIED | `seon.render.transcript/floor-text` | src/seon/render/transcript.clj |
| A026 | 1 | VERIFIED | `seon.render.transcript/history-entries` | src/seon/render/transcript.clj |
| A027 | 1 | VERIFIED | `seon.render.transcript/html-entries` | src/seon/render/transcript.clj |
| A028 | 1 | VERIFIED | `seon.render.transcript/projected-entry` | src/seon/render/transcript.clj |
| A029 | 1 | VERIFIED | `seon.render.web/chain-fragment` | src/seon/render/web.clj |
| A030 | 1 | VERIFIED | `seon.render.web/data-response` | src/seon/render/web.clj |
| A031 | 1 | VERIFIED | `seon.render.web/debug-found-values-html` | src/seon/render/web.clj |
| A032 | 1 | VERIFIED | `seon.render.web/debug-page-result` | src/seon/render/web.clj |
| A033 | 1 | VERIFIED | `seon.render.web/debug-preview-html` | src/seon/render/web.clj |
| A034 | 1 | VERIFIED | `seon.render.web/experiment-preview-html` | src/seon/render/web.clj |
| A035 | 1 | VERIFIED | `seon.render.web/failed-page-result` | src/seon/render/web.clj |
| A036 | 1 | VERIFIED | `seon.render.web/failure-response` | src/seon/render/web.clj |
| A037 | 1 | VERIFIED | `seon.render.web/health-page-result` | src/seon/render/web.clj |
| A038 | 1 | VERIFIED | `seon.render.web/sent-html` | src/seon/render/web.clj |
| A039 | 1 | VERIFIED | `seon.render/inline-rendered` | src/seon/render.clj |
| A040 | 1 | VERIFIED | `seon.render/renderer-failure` | src/seon/render.clj |
| A041 | 1 | VERIFIED | `seon.render/unknown-html` | src/seon/render.clj |
| A042 | 1 | VERIFIED | `seon.render/unknown-output` | src/seon/render.clj |
| A043 | 1 | VERIFIED | `seon.repl/render-directory-html` | src/seon/repl.clj |
| A044 | 1 | VERIFIED | `seon.task/chain-html` | src/seon/task.clj |
| A045 | 1 | VERIFIED | `seon.task/chain-task-html` | src/seon/task.clj |
| A046 | 1 | VERIFIED | `seon.test.accretion/render-html` | src/seon/test/accretion.clj |
| A047 | 1 | VERIFIED | `seon.test.runner/report-value` | src/seon/test/runner.clj |
| A048 | 2 | VERIFIED | `seon.context/must-read-ai` | src/seon/context.clj |
| A049 | 2 | VERIFIED | `seon.render.web/render-source-call` | src/seon/render/web.clj |
| A050 | 3 | VERIFIED | `clojure.core/print-method` | src/seon/env.clj |
| A051 | 3 | VERIFIED | `my.background/invalid-call` | src/my/background.clj |
| A052 | 3 | VERIFIED | `my.program/declaration-pair` | src/my/program.clj |
| A053 | 3 | VERIFIED | `my.program/locate` | src/my/program.clj |
| A054 | 3 | VERIFIED | `my.program/refusal` | src/my/program.clj |
| A055 | 3 | VERIFIED | `my.root/open-assignment` | src/my/root.clj |
| A056 | 3 | VERIFIED | `seon.agent/identity` | src/seon/agent.clj |
| A057 | 3 | INFERRED | `seon.agent/render-settings-html` | src/seon/agent.clj |
| A058 | 3 | INFERRED | `seon.ai/labelled-price` | src/seon/ai.clj |
| A059 | 3 | INFERRED | `seon.ai/model-ai` | src/seon/ai.clj |
| A060 | 3 | INFERRED | `seon.ai/model-html` | src/seon/ai.clj |
| A061 | 3 | VERIFIED | `seon.ai/parsed-completion` | src/seon/ai.clj |
| A062 | 3 | INFERRED | `seon.ai/provider-ai` | src/seon/ai.clj |
| A063 | 3 | INFERRED | `seon.ai/registry-ai` | src/seon/ai.clj |
| A064 | 3 | VERIFIED | `seon.ai/send-request` | src/seon/ai.clj |
| A065 | 3 | INFERRED | `seon.ai/transcript` | src/seon/ai.clj |
| A066 | 3 | INFERRED | `seon.ai/usd-per-million` | src/seon/ai.clj |
| A067 | 3 | VERIFIED | `seon.background/poll` | src/seon/background.clj |
| A068 | 3 | VERIFIED | `seon.batch/cost-cell` | src/seon/batch.clj |
| A069 | 3 | VERIFIED | `seon.batch/diagnostic` | src/seon/batch.clj |
| A070 | 3 | VERIFIED | `seon.batch/evaluate-step` | src/seon/batch.clj |
| A071 | 3 | VERIFIED | `seon.batch/read-step` | src/seon/batch.clj |
| A072 | 3 | VERIFIED | `seon.batch/settled-into` | src/seon/batch.clj |
| A073 | 3 | VERIFIED | `seon.batch/unprintable` | src/seon/batch.clj |
| A074 | 3 | VERIFIED | `seon.call-preparation/coherent-supplier` | src/seon/call_preparation.clj |
| A075 | 3 | INFERRED | `seon.call-preparation/derive-snapshot` | src/seon/call_preparation.clj |
| A076 | 3 | VERIFIED | `seon.call-preparation/incoherent` | src/seon/call_preparation.clj |
| A077 | 3 | VERIFIED | `seon.call-preparation/prepare` | src/seon/call_preparation.clj |
| A078 | 3 | VERIFIED | `seon.call-preparation/supply-from-row` | src/seon/call_preparation.clj |
| A079 | 3 | VERIFIED | `seon.call-preparation/supply-from-world` | src/seon/call_preparation.clj |
| A080 | 3 | VERIFIED | `seon.call-preparation/unavailable` | src/seon/call_preparation.clj |
| A081 | 3 | INFERRED | `seon.central-round/-main` | script/seon/central_round.clj |
| A082 | 3 | INFERRED | `seon.check-corpus/-main` | script/seon/check_corpus.clj |
| A083 | 3 | VERIFIED | `seon.cluster.agent/bail-chain!` | src/seon/cluster/agent.clj |
| A084 | 3 | VERIFIED | `seon.cluster.agent/chain-bails` | src/seon/cluster/agent.clj |
| A085 | 3 | VERIFIED | `seon.cluster.agent/merge-chains!` | src/seon/cluster/agent.clj |
| A086 | 3 | VERIFIED | `seon.cluster.agent/merge-refused-reason` | src/seon/cluster/agent.clj |
| A087 | 3 | INFERRED | `seon.cluster.agent/namespace-seed-call` | src/seon/cluster/agent.clj |
| A088 | 3 | VERIFIED | `seon.cluster.agent/refusal-section` | src/seon/cluster/agent.clj |
| A089 | 3 | VERIFIED | `seon.cluster.agent/refused-reason` | src/seon/cluster/agent.clj |
| A090 | 3 | VERIFIED | `seon.cluster.agent/render-creation-ai` | src/seon/cluster/agent.clj |
| A091 | 3 | VERIFIED | `seon.cluster.agent/render-creation-html` | src/seon/cluster/agent.clj |
| A092 | 3 | VERIFIED | `seon.cluster.agent/render-id-html` | src/seon/cluster/agent.clj |
| A093 | 3 | VERIFIED | `seon.cluster.agent/render-identity-html` | src/seon/cluster/agent.clj |
| A094 | 3 | VERIFIED | `seon.cluster.agent/render-situation-ai` | src/seon/cluster/agent.clj |
| A095 | 3 | VERIFIED | `seon.cluster.agent/submit-source-in-projection` | src/seon/cluster/agent.clj |
| A096 | 3 | VERIFIED | `seon.cluster.agent/unfinished-intent` | src/seon/cluster/agent.clj |
| A097 | 3 | VERIFIED | `seon.cluster.agent/whoami` | src/seon/cluster/agent.clj |
| A098 | 3 | VERIFIED | `seon.cluster.boot/banner` | src/seon/cluster/boot.clj |
| A099 | 3 | VERIFIED | `seon.cluster.boot/diagnostic` | src/seon/cluster/boot.clj |
| A100 | 3 | VERIFIED | `seon.cluster.boot/request!` | src/seon/cluster/boot.clj |
| A101 | 3 | INFERRED | `seon.cluster.host/home-call` | src/seon/cluster/host.clj |
| A102 | 3 | INFERRED | `seon.cluster.host/host!` | src/seon/cluster/host.clj |
| A103 | 3 | VERIFIED | `seon.cluster.message/delivery` | src/seon/cluster/message.clj |
| A104 | 3 | INFERRED | `seon.cluster.message/format-ai` | src/seon/cluster/message.clj |
| A105 | 3 | VERIFIED | `seon.cluster.message/inbound-tx` | src/seon/cluster/message.clj |
| A106 | 3 | INFERRED | `seon.cluster.message/inbox-html` | src/seon/cluster/message.clj |
| A107 | 3 | VERIFIED | `seon.cluster.message/read` | src/seon/cluster/message.clj |
| A108 | 3 | INFERRED | `seon.cluster.message/render-html` | src/seon/cluster/message.clj |
| A109 | 3 | INFERRED | `seon.cluster.message/render-inbox-html` | src/seon/cluster/message.clj |
| A110 | 3 | VERIFIED | `seon.cluster.process/start-instant-unavailable!` | src/seon/cluster/process.clj |
| A111 | 3 | INFERRED | `seon.cluster.registry/branch!` | src/seon/cluster/registry.clj |
| A112 | 3 | VERIFIED | `seon.cluster.registry/branch-commit-id` | src/seon/cluster/registry.clj |
| A113 | 3 | INFERRED | `seon.cluster.registry/reset-cluster!` | src/seon/cluster/registry.clj |
| A114 | 3 | VERIFIED | `seon.cluster.registry/retention-cutoff` | src/seon/cluster/registry.clj |
| A115 | 3 | INFERRED | `seon.cluster.registry/retire-branch!` | src/seon/cluster/registry.clj |
| A116 | 3 | INFERRED | `seon.cluster.source/commit-message` | src/seon/cluster/source.clj |
| A117 | 3 | VERIFIED | `seon.cluster.source/lint-refusal` | src/seon/cluster/source.clj |
| A118 | 3 | VERIFIED | `seon.cluster.source/proven-file` | src/seon/cluster/source.clj |
| A119 | 3 | INFERRED | `seon.cluster.source/resolve-population` | src/seon/cluster/source.clj |
| A120 | 3 | VERIFIED | `seon.cluster.source/unmerged-refusal` | src/seon/cluster/source.clj |
| A121 | 3 | INFERRED | `seon.cluster.status/render-html` | src/seon/cluster/status.clj |
| A122 | 3 | INFERRED | `seon.cluster.store/acquire-branch!` | src/seon/cluster/store.clj |
| A123 | 3 | INFERRED | `seon.cluster.store/create-store!` | src/seon/cluster/store.clj |
| A124 | 3 | INFERRED | `seon.cluster.store/datahike-log!` | src/seon/cluster/store.clj |
| A125 | 3 | VERIFIED | `seon.cluster.store/listener-failed!` | src/seon/cluster/store.clj |
| A126 | 3 | INFERRED | `seon.cluster.store/open-branch!` | src/seon/cluster/store.clj |
| A127 | 3 | INFERRED | `seon.cluster.store/open-store!` | src/seon/cluster/store.clj |
| A128 | 3 | VERIFIED | `seon.cluster/call-with-program-change` | src/seon/cluster.clj |
| A129 | 3 | VERIFIED | `seon.cluster/ensure-entity!` | src/seon/cluster.clj |
| A130 | 3 | VERIFIED | `seon.cluster/full-source-refresh!` | src/seon/cluster.clj |
| A131 | 3 | VERIFIED | `seon.cluster/load-development-definitions!` | src/seon/cluster.clj |
| A132 | 3 | VERIFIED | `seon.cluster/populate-source!` | src/seon/cluster.clj |
| A133 | 3 | VERIFIED | `seon.cluster/render-html` | src/seon/cluster.clj |
| A134 | 3 | VERIFIED | `seon.cluster/report-analysis-warnings!` | src/seon/cluster.clj |
| A135 | 3 | VERIFIED | `seon.cluster/report-source-progress!` | src/seon/cluster.clj |
| A136 | 3 | VERIFIED | `seon.cluster/require-coherent-program!` | src/seon/cluster.clj |
| A137 | 3 | VERIFIED | `seon.cluster/serve!` | src/seon/cluster.clj |
| A138 | 3 | VERIFIED | `seon.cluster/warn-low-space!` | src/seon/cluster.clj |
| A139 | 3 | VERIFIED | `seon.config/agent-cluster` | src/seon/config.clj |
| A140 | 3 | VERIFIED | `seon.config/desired-tempid` | src/seon/config.clj |
| A141 | 3 | INFERRED | `seon.config/render-ai` | src/seon/config.clj |
| A142 | 3 | INFERRED | `seon.config/render-html` | src/seon/config.clj |
| A143 | 3 | VERIFIED | `seon.context/cost-badge` | src/seon/context.clj |
| A144 | 3 | VERIFIED | `seon.context/exchange-ai` | src/seon/context.clj |
| A145 | 3 | VERIFIED | `seon.context/exchange-text` | src/seon/context.clj |
| A146 | 3 | VERIFIED | `seon.context/reply-html` | src/seon/context.clj |
| A147 | 3 | VERIFIED | `seon.context/token-badge` | src/seon/context.clj |
| A148 | 3 | INFERRED | `seon.contracts/plan` | src/seon/contracts.clj |
| A149 | 3 | INFERRED | `seon.db/connection-identity` | src/seon/db.clj |
| A150 | 3 | VERIFIED | `seon.db/datoms` | src/seon/db.clj |
| A151 | 3 | VERIFIED | `seon.db/foreign-connection-error` | src/seon/db.clj |
| A152 | 3 | VERIFIED | `seon.db/lookup-ref-error` | src/seon/db.clj |
| A153 | 3 | VERIFIED | `seon.db/missing-connection-error` | src/seon/db.clj |
| A154 | 3 | VERIFIED | `seon.db/missing-pull-selector-error` | src/seon/db.clj |
| A155 | 3 | VERIFIED | `seon.db/pull-call` | src/seon/db.clj |
| A156 | 3 | VERIFIED | `seon.db/read-declarations` | src/seon/db.clj |
| A157 | 3 | VERIFIED | `seon.db/rejection-message` | src/seon/db.clj |
| A158 | 3 | VERIFIED | `seon.db/removed-definition-error` | src/seon/db.clj |
| A159 | 3 | INFERRED | `seon.db/render-transaction-html` | src/seon/db.clj |
| A160 | 3 | VERIFIED | `seon.db/replay-read` | src/seon/db.clj |
| A161 | 3 | VERIFIED | `seon.db/retention-check` | src/seon/db.clj |
| A162 | 3 | VERIFIED | `seon.db/stored-value-check` | src/seon/db.clj |
| A163 | 3 | VERIFIED | `seon.db/transact-call` | src/seon/db.clj |
| A164 | 3 | INFERRED | `seon.db/transaction-value-html` | src/seon/db.clj |
| A165 | 3 | VERIFIED | `seon.db/unknown-attribute-error` | src/seon/db.clj |
| A166 | 3 | VERIFIED | `seon.db/unsupplied-custody-error` | src/seon/db.clj |
| A167 | 3 | VERIFIED | `seon.db/validate-pulled-value` | src/seon/db.clj |
| A168 | 3 | VERIFIED | `seon.db/value-type-error` | src/seon/db.clj |
| A169 | 3 | VERIFIED | `seon.db/vectors-tx` | src/seon/db.clj |
| A170 | 3 | VERIFIED | `seon.db/write-digest-error` | src/seon/db.clj |
| A171 | 3 | VERIFIED | `seon.db/write-error-identity` | src/seon/db.clj |
| A172 | 3 | VERIFIED | `seon.db/write-map-error` | src/seon/db.clj |
| A173 | 3 | VERIFIED | `seon.db/write-owned-values-error` | src/seon/db.clj |
| A174 | 3 | VERIFIED | `seon.dev.context-corpus/-main` | script/seon/dev/context_corpus.clj |
| A175 | 3 | VERIFIED | `seon.dev.dependency-digest/digest-declarations!` | script/seon/dev/dependency_digest.clj |
| A176 | 3 | VERIFIED | `seon.dev.docstring/check-one` | script/seon/dev/docstring.clj |
| A177 | 3 | VERIFIED | `seon.dev.docstring/format-findings` | script/seon/dev/docstring.clj |
| A178 | 3 | INFERRED | `seon.dev.harness-costs/wrapper-overhead` | script/seon/dev/harness_costs.clj |
| A179 | 3 | VERIFIED | `seon.dev.markdown/format-violations` | script/seon/dev/markdown.clj |
| A180 | 3 | VERIFIED | `seon.dev.markdown/rule-heading-increment` | script/seon/dev/markdown.clj |
| A181 | 3 | VERIFIED | `seon.dev.markdown/rule-no-bare-urls` | script/seon/dev/markdown.clj |
| A182 | 3 | VERIFIED | `seon.dev.markdown/rule-required-fields` | script/seon/dev/markdown.clj |
| A183 | 3 | VERIFIED | `seon.dev.markdown/rule-valid-tags` | script/seon/dev/markdown.clj |
| A184 | 3 | VERIFIED | `seon.dev.markdown/rule-valid-type` | script/seon/dev/markdown.clj |
| A185 | 3 | VERIFIED | `seon.dev.markdown/rule-wikilink-target-exists` | script/seon/dev/markdown.clj |
| A186 | 3 | VERIFIED | `seon.dev.mcp/failure-text` | script/seon/dev/mcp.clj |
| A187 | 3 | VERIFIED | `seon.dev.mcp/jvm-string!` | script/seon/dev/mcp.clj |
| A188 | 3 | VERIFIED | `seon.dev.mcp/log` | script/seon/dev/mcp.clj |
| A189 | 3 | VERIFIED | `seon.dev.mcp/start-remedy` | script/seon/dev/mcp.clj |
| A190 | 3 | INFERRED | `seon.docstring-headings/-main` | script/seon/docstring_headings.clj |
| A191 | 3 | INFERRED | `seon.edit.jvm/stale-source` | src/seon/edit/jvm.clj |
| A192 | 3 | INFERRED | `seon.effect/payload-face` | src/seon/effect.clj |
| A193 | 3 | INFERRED | `seon.effect/render-ai` | src/seon/effect.clj |
| A194 | 3 | INFERRED | `seon.effect/render-html` | src/seon/effect.clj |
| A195 | 3 | VERIFIED | `seon.env/absent-member-error` | src/seon/env.clj |
| A196 | 3 | INFERRED | `seon.env/members-of` | src/seon/env.clj |
| A197 | 3 | VERIFIED | `seon.env/require-environment` | src/seon/env.clj |
| A198 | 3 | VERIFIED | `seon.error.refusal/construction-refusal` | src/seon/error/refusal.clj |
| A199 | 3 | VERIFIED | `seon.error.refusal/object-name` | src/seon/error/refusal.clj |
| A200 | 3 | VERIFIED | `seon.error.refusal/refusal` | src/seon/error/refusal.clj |
| A201 | 3 | VERIFIED | `seon.error/ai-prose` | src/seon/error.clj |
| A202 | 3 | VERIFIED | `seon.error/bound-text` | src/seon/error.clj |
| A203 | 3 | VERIFIED | `seon.error/bounded-text` | src/seon/error.clj |
| A204 | 3 | VERIFIED | `seon.error/call-lines` | src/seon/error.clj |
| A205 | 3 | VERIFIED | `seon.error/elision-html` | src/seon/error.clj |
| A206 | 3 | VERIFIED | `seon.error/evidence-path` | src/seon/error.clj |
| A207 | 3 | VERIFIED | `seon.error/evidence-text` | src/seon/error.clj |
| A208 | 3 | VERIFIED | `seon.error/explain-problem` | src/seon/error.clj |
| A209 | 3 | VERIFIED | `seon.error/fit-fact-payload` | src/seon/error.clj |
| A210 | 3 | VERIFIED | `seon.error/message` | src/seon/error.clj |
| A211 | 3 | VERIFIED | `seon.error/message-tx` | src/seon/error.clj |
| A212 | 3 | VERIFIED | `seon.error/notice-ai-prose` | src/seon/error.clj |
| A213 | 3 | VERIFIED | `seon.error/problem-parts` | src/seon/error.clj |
| A214 | 3 | VERIFIED | `seon.error/problem-sentence` | src/seon/error.clj |
| A215 | 3 | VERIFIED | `seon.error/render-chain-html` | src/seon/error.clj |
| A216 | 3 | VERIFIED | `seon.error/render-faults-ai` | src/seon/error.clj |
| A217 | 3 | VERIFIED | `seon.error/render-faults-html` | src/seon/error.clj |
| A218 | 3 | VERIFIED | `seon.error/render-html` | src/seon/error.clj |
| A219 | 3 | VERIFIED | `seon.error/reply-html` | src/seon/error.clj |
| A220 | 3 | VERIFIED | `seon.error/scalar-text` | src/seon/error.clj |
| A221 | 3 | VERIFIED | `seon.error/schema-expectation` | src/seon/error.clj |
| A222 | 3 | VERIFIED | `seon.error/time-limit-prose` | src/seon/error.clj |
| A223 | 3 | VERIFIED | `seon.error/value-description` | src/seon/error.clj |
| A224 | 3 | VERIFIED | `seon.error/value-edn` | src/seon/error.clj |
| A225 | 3 | VERIFIED | `seon.eval.drive/await-fact!` | src/seon/eval/drive.clj |
| A226 | 3 | INFERRED | `seon.fault/print-unrecorded!` | src/seon/fault.clj |
| A227 | 3 | VERIFIED | `seon.fault/record!` | src/seon/fault.clj |
| A228 | 3 | INFERRED | `seon.fn.analyzer/expansion-hooks` | src/seon/fn/analyzer.clj |
| A229 | 3 | INFERRED | `seon.fn.analyzer/program-prelude` | src/seon/fn/analyzer.clj |
| A230 | 3 | INFERRED | `seon.fn.signature/declaration-parts` | src/seon/fn/signature.cljc |
| A231 | 3 | INFERRED | `seon.fn/add-contract-facts` | src/seon/fn.clj |
| A232 | 3 | INFERRED | `seon.fn/analyze-forms` | src/seon/fn.clj |
| A233 | 3 | VERIFIED | `seon.fn/assert-clean-analysis!` | src/seon/fn.clj |
| A234 | 3 | VERIFIED | `seon.fn/assert-populated!` | src/seon/fn.clj |
| A235 | 3 | INFERRED | `seon.fn/index!` | src/seon/fn.clj |
| A236 | 3 | INFERRED | `seon.fn/manifest-data` | src/seon/fn.clj |
| A237 | 3 | VERIFIED | `seon.fn/reconciled-paths` | src/seon/fn.clj |
| A238 | 3 | VERIFIED | `seon.fn/require-committed!` | src/seon/fn.clj |
| A239 | 3 | VERIFIED | `seon.fn/seed-refused!` | src/seon/fn.clj |
| A240 | 3 | VERIFIED | `seon.fn/task-refused!` | src/seon/fn.clj |
| A241 | 3 | VERIFIED | `seon.fn/unresolved-macro-refusal` | src/seon/fn.clj |
| A242 | 3 | INFERRED | `seon.fn/var-entity` | src/seon/fn.clj |
| A243 | 3 | VERIFIED | `seon.fs.jvm/flat-error` | src/seon/fs/jvm.clj |
| A244 | 3 | VERIFIED | `seon.fs/admit-destructive-path!` | src/seon/fs.clj |
| A245 | 3 | VERIFIED | `seon.fs/caller-frame` | src/seon/fs.clj |
| A246 | 3 | VERIFIED | `seon.fs/log-deletion!` | src/seon/fs.clj |
| A247 | 3 | INFERRED | `seon.fs/write-edn-atomically!` | src/seon/fs.clj |
| A248 | 3 | INFERRED | `seon.health/finding-item` | src/seon/health.clj |
| A249 | 3 | VERIFIED | `seon.health/refused-schemas` | src/seon/health.clj |
| A250 | 3 | INFERRED | `seon.health/render-html` | src/seon/health.clj |
| A251 | 3 | INFERRED | `seon.health/unit-section` | src/seon/health.clj |
| A252 | 3 | INFERRED | `seon.id/print-canonical!` | src/seon/id.clj |
| A253 | 3 | INFERRED | `seon.id/printed` | src/seon/id.clj |
| A254 | 3 | VERIFIED | `seon.instrument/actionable-problem` | src/seon/instrument.clj |
| A255 | 3 | VERIFIED | `seon.instrument/apply!` | src/seon/instrument.clj |
| A256 | 3 | VERIFIED | `seon.instrument/attributed-problem` | src/seon/instrument.clj |
| A257 | 3 | VERIFIED | `seon.instrument/caller-frame` | src/seon/instrument.clj |
| A258 | 3 | VERIFIED | `seon.instrument/compiled-wrapper` | src/seon/instrument.clj |
| A259 | 3 | VERIFIED | `seon.instrument/definition-location` | src/seon/instrument.clj |
| A260 | 3 | VERIFIED | `seon.instrument/violation` | src/seon/instrument.clj |
| A261 | 3 | VERIFIED | `seon.instrument/wrap-interpreted` | src/seon/instrument.clj |
| A262 | 3 | INFERRED | `seon.interpreted-census/-main` | script/seon/interpreted_census.clj |
| A263 | 3 | INFERRED | `seon.interpreted-census/batch-lines` | script/seon/interpreted_census.clj |
| A264 | 3 | VERIFIED | `seon.maintenance/refuse-misspelled-options!` | src/seon/maintenance.clj |
| A265 | 3 | INFERRED | `seon.maintenance/unverified-root-clause` | src/seon/maintenance.clj |
| A266 | 3 | INFERRED | `seon.mcp/execution` | src/seon/mcp.clj |
| A267 | 3 | VERIFIED | `seon.mcp/instance` | src/seon/mcp.clj |
| A268 | 3 | INFERRED | `seon.mcp/printed-shown` | src/seon/mcp.clj |
| A269 | 3 | VERIFIED | `seon.mcp/runtime-status` | src/seon/mcp.clj |
| A270 | 3 | INFERRED | `seon.merge/assignment-row` | src/seon/merge.clj |
| A271 | 3 | VERIFIED | `seon.merge/conflict-task!` | src/seon/merge.clj |
| A272 | 3 | VERIFIED | `seon.operator/-main` | script/seon/operator.clj |
| A273 | 3 | VERIFIED | `seon.operator/built-template!` | script/seon/operator.clj |
| A274 | 3 | VERIFIED | `seon.operator/candidate-tests!` | script/seon/operator.clj |
| A275 | 3 | VERIFIED | `seon.operator/check-slot!` | script/seon/operator.clj |
| A276 | 3 | VERIFIED | `seon.operator/headline` | script/seon/operator.clj |
| A277 | 3 | VERIFIED | `seon.operator/program-classes` | script/seon/operator.clj |
| A278 | 3 | VERIFIED | `seon.operator/write-diagnostic!` | script/seon/operator.clj |
| A279 | 3 | INFERRED | `seon.problems/ai-prose` | src/seon/problems.clj |
| A280 | 3 | VERIFIED | `seon.problems/html-report` | src/seon/problems.clj |
| A281 | 3 | VERIFIED | `seon.problems/missing-model-ai` | src/seon/problems.clj |
| A282 | 3 | INFERRED | `seon.problems/resolution-text` | src/seon/problems.clj |
| A283 | 3 | INFERRED | `seon.problems/row` | src/seon/problems.clj |
| A284 | 3 | INFERRED | `seon.problems/stale-var-ai` | src/seon/problems.clj |
| A285 | 3 | INFERRED | `seon.program/arity-entity` | src/seon/program.cljc |
| A286 | 3 | INFERRED | `seon.program/binding-entity` | src/seon/program.cljc |
| A287 | 3 | VERIFIED | `seon.program/component-id` | src/seon/program.cljc |
| A288 | 3 | VERIFIED | `seon.program/key-kind` | src/seon/program.cljc |
| A289 | 3 | INFERRED | `seon.program/label-facts` | src/seon/program.cljc |
| A290 | 3 | INFERRED | `seon.program/map-binding-entries` | src/seon/program.cljc |
| A291 | 3 | INFERRED | `seon.program/typed-key-facts` | src/seon/program.cljc |
| A292 | 3 | VERIFIED | `seon.reconcile/refuse!` | src/seon/reconcile.cljc |
| A293 | 3 | INFERRED | `seon.render.hiccup/append-node!` | src/seon/render/hiccup.clj |
| A294 | 3 | VERIFIED | `seon.render.hiccup/shorthand` | src/seon/render/hiccup.clj |
| A295 | 3 | INFERRED | `seon.render.ns/function-html` | src/seon/render/ns.clj |
| A296 | 3 | INFERRED | `seon.render.ns/render-alias-html` | src/seon/render/ns.clj |
| A297 | 3 | INFERRED | `seon.render.ns/render-html` | src/seon/render/ns.clj |
| A298 | 3 | INFERRED | `seon.render.ns/render-import-html` | src/seon/render/ns.clj |
| A299 | 3 | INFERRED | `seon.render.ns/render-refer-html` | src/seon/render/ns.clj |
| A300 | 3 | INFERRED | `seon.render.ns/task-html` | src/seon/render/ns.clj |
| A301 | 3 | VERIFIED | `seon.render.schema/check-html` | src/seon/render/schema.clj |
| A302 | 3 | INFERRED | `seon.render.schema/link` | src/seon/render/schema.clj |
| A303 | 3 | INFERRED | `seon.render.schema/named-list` | src/seon/render/schema.clj |
| A304 | 3 | VERIFIED | `seon.render.schema/render-html` | src/seon/render/schema.clj |
| A305 | 3 | VERIFIED | `seon.render.sent/call-header` | src/seon/render/sent.clj |
| A306 | 3 | VERIFIED | `seon.render.sent/exchange-html` | src/seon/render/sent.clj |
| A307 | 3 | VERIFIED | `seon.render.sent/view-html` | src/seon/render/sent.clj |
| A308 | 3 | VERIFIED | `seon.render.transcript/cost-html` | src/seon/render/transcript.clj |
| A309 | 3 | VERIFIED | `seon.render.transcript/message-text` | src/seon/render/transcript.clj |
| A310 | 3 | VERIFIED | `seon.render.transcript/render-history-html` | src/seon/render/transcript.clj |
| A311 | 3 | VERIFIED | `seon.render.transcript/render-runtime-html` | src/seon/render/transcript.clj |
| A312 | 3 | VERIFIED | `seon.render.transcript/runtime-duration` | src/seon/render/transcript.clj |
| A313 | 3 | VERIFIED | `seon.render.transcript/runtime-message-link` | src/seon/render/transcript.clj |
| A314 | 3 | VERIFIED | `seon.render.transcript/runtime-time` | src/seon/render/transcript.clj |
| A315 | 3 | VERIFIED | `seon.render.transcript/runtime-turn-table` | src/seon/render/transcript.clj |
| A316 | 3 | VERIFIED | `seon.render.transcript/session-header` | src/seon/render/transcript.clj |
| A317 | 3 | VERIFIED | `seon.render.transcript/session-stall` | src/seon/render/transcript.clj |
| A318 | 3 | VERIFIED | `seon.render.transcript/test-html` | src/seon/render/transcript.clj |
| A319 | 3 | VERIFIED | `seon.render.transcript/turn-header` | src/seon/render/transcript.clj |
| A320 | 3 | INFERRED | `seon.render.value/bounded-text` | src/seon/render/value.clj |
| A321 | 3 | INFERRED | `seon.render.value/core-text` | src/seon/render/value.clj |
| A322 | 3 | VERIFIED | `seon.render.value/link-lines` | src/seon/render/value.clj |
| A323 | 3 | INFERRED | `seon.render.value/throwable-text` | src/seon/render/value.clj |
| A324 | 3 | INFERRED | `seon.render.var/render-html` | src/seon/render/var.clj |
| A325 | 3 | VERIFIED | `seon.render.walk/connection-observation` | src/seon/render/walk.clj |
| A326 | 3 | INFERRED | `seon.render.walk/related-html` | src/seon/render/walk.clj |
| A327 | 3 | VERIFIED | `seon.render.web/applicable-renderers-html` | src/seon/render/web.clj |
| A328 | 3 | VERIFIED | `seon.render.web/block-metadata` | src/seon/render/web.clj |
| A329 | 3 | VERIFIED | `seon.render.web/calls-html` | src/seon/render/web.clj |
| A330 | 3 | VERIFIED | `seon.render.web/chains-page-result` | src/seon/render/web.clj |
| A331 | 3 | VERIFIED | `seon.render.web/create-owner-form` | src/seon/render/web.clj |
| A332 | 3 | VERIFIED | `seon.render.web/create-owner-response` | src/seon/render/web.clj |
| A333 | 3 | VERIFIED | `seon.render.web/debug-found-value` | src/seon/render/web.clj |
| A334 | 3 | VERIFIED | `seon.render.web/debug-graph-html` | src/seon/render/web.clj |
| A335 | 3 | VERIFIED | `seon.render.web/debug-header-html` | src/seon/render/web.clj |
| A336 | 3 | VERIFIED | `seon.render.web/debug-preview-html` | src/seon/render/web.clj |
| A337 | 3 | VERIFIED | `seon.render.web/debug-read-dependencies-html` | src/seon/render/web.clj |
| A338 | 3 | VERIFIED | `seon.render.web/debug-renderer-contract` | src/seon/render/web.clj |
| A339 | 3 | VERIFIED | `seon.render.web/debug-response` | src/seon/render/web.clj |
| A340 | 3 | VERIFIED | `seon.render.web/debug-subject-link` | src/seon/render/web.clj |
| A341 | 3 | VERIFIED | `seon.render.web/ensure-namespace-owner!` | src/seon/render/web.clj |
| A342 | 3 | VERIFIED | `seon.render.web/failed-page-result` | src/seon/render/web.clj |
| A343 | 3 | VERIFIED | `seon.render.web/failure-response` | src/seon/render/web.clj |
| A344 | 3 | VERIFIED | `seon.render.web/graph-model` | src/seon/render/web.clj |
| A345 | 3 | VERIFIED | `seon.render.web/graph-node-id` | src/seon/render/web.clj |
| A346 | 3 | VERIFIED | `seon.render.web/handler` | src/seon/render/web.clj |
| A347 | 3 | VERIFIED | `seon.render.web/missing-agent-response` | src/seon/render/web.clj |
| A348 | 3 | VERIFIED | `seon.render.web/received-response` | src/seon/render/web.clj |
| A349 | 3 | VERIFIED | `seon.render.web/render-source-call` | src/seon/render/web.clj |
| A350 | 3 | VERIFIED | `seon.render.web/scalar-label` | src/seon/render/web.clj |
| A351 | 3 | VERIFIED | `seon.render.web/shell` | src/seon/render/web.clj |
| A352 | 3 | VERIFIED | `seon.render.web/stream-strip-html` | src/seon/render/web.clj |
| A353 | 3 | VERIFIED | `seon.render.web/unreportable-page-result` | src/seon/render/web.clj |
| A354 | 3 | VERIFIED | `seon.render/ambiguity` | src/seon/render.clj |
| A355 | 3 | VERIFIED | `seon.render/declared-projection` | src/seon/render.clj |
| A356 | 3 | INFERRED | `seon.render/render-default-ai-source` | src/seon/render.clj |
| A357 | 3 | VERIFIED | `seon.render/renderer-failure` | src/seon/render.clj |
| A358 | 3 | VERIFIED | `seon.render/unknown-ai` | src/seon/render.clj |
| A359 | 3 | VERIFIED | `seon.render/unknown-stable-evidence` | src/seon/render.clj |
| A360 | 3 | VERIFIED | `seon.repl/def-note` | src/seon/repl.clj |
| A361 | 3 | VERIFIED | `seon.repl/input-text` | src/seon/repl.clj |
| A362 | 3 | VERIFIED | `seon.repl/render-directory-html` | src/seon/repl.clj |
| A363 | 3 | VERIFIED | `seon.repl/render-html` | src/seon/repl.clj |
| A364 | 3 | VERIFIED | `seon.repl/reply-map` | src/seon/repl.clj |
| A365 | 3 | VERIFIED | `seon.repl/reply-text` | src/seon/repl.clj |
| A366 | 3 | VERIFIED | `seon.repl/response-entries` | src/seon/repl.clj |
| A367 | 3 | VERIFIED | `seon.repl/shown-output` | src/seon/repl.clj |
| A368 | 3 | VERIFIED | `seon.repl/source-text` | src/seon/repl.clj |
| A369 | 3 | VERIFIED | `seon.repl/text` | src/seon/repl.clj |
| A370 | 3 | VERIFIED | `seon.schema.admission/-main` | src/seon/schema/admission.clj |
| A371 | 3 | VERIFIED | `seon.schema.admission/collision-findings` | src/seon/schema/admission.clj |
| A372 | 3 | VERIFIED | `seon.schema.admission/exact-reuse-findings` | src/seon/schema/admission.clj |
| A373 | 3 | VERIFIED | `seon.schema.admission/house-findings` | src/seon/schema/admission.clj |
| A374 | 3 | VERIFIED | `seon.schema.admission/name-overlap-findings` | src/seon/schema/admission.clj |
| A375 | 3 | VERIFIED | `seon.schema.admission/undefined-slot-finding` | src/seon/schema/admission.clj |
| A376 | 3 | VERIFIED | `seon.schema.datahike/lower-clause` | src/seon/schema/datahike.clj |
| A377 | 3 | VERIFIED | `seon.schema.datahike/refuse-slot!` | src/seon/schema/datahike.clj |
| A378 | 3 | VERIFIED | `seon.schema.datahike/storable-properties-in` | src/seon/schema/datahike.clj |
| A379 | 3 | VERIFIED | `seon.schema.edn/merge-schema-resources` | src/seon/schema/edn.clj |
| A380 | 3 | VERIFIED | `seon.schema.edn/read-schema-resource` | src/seon/schema/edn.clj |
| A381 | 3 | VERIFIED | `seon.schema.edn/refusal` | src/seon/schema/edn.clj |
| A382 | 3 | VERIFIED | `seon.schema.edn/validate-resource-placement!` | src/seon/schema/edn.clj |
| A383 | 3 | VERIFIED | `seon.schema.internal/assert-compilable-schema!` | src/seon/schema/internal.cljc |
| A384 | 3 | VERIFIED | `seon.schema.internal/assert-complete-schema!` | src/seon/schema/internal.cljc |
| A385 | 3 | VERIFIED | `seon.schema.internal/assert-non-nilable-value-schema!` | src/seon/schema/internal.cljc |
| A386 | 3 | VERIFIED | `seon.schema/assert-acyclic-references!` | src/seon/schema.clj |
| A387 | 3 | VERIFIED | `seon.schema/assert-config-display!` | src/seon/schema.clj |
| A388 | 3 | VERIFIED | `seon.schema/bind-contract-predicates` | src/seon/schema.clj |
| A389 | 3 | INFERRED | `seon.schema/canonical-schema-entities` | src/seon/schema.clj |
| A390 | 3 | VERIFIED | `seon.schema/compilable-form` | src/seon/schema.clj |
| A391 | 3 | INFERRED | `seon.schema/fallback-caller` | src/seon/schema.clj |
| A392 | 3 | VERIFIED | `seon.schema/identity-only-descriptors-in` | src/seon/schema.clj |
| A393 | 3 | VERIFIED | `seon.schema/parsed-form` | src/seon/schema.clj |
| A394 | 3 | VERIFIED | `seon.schema/parsed-rows` | src/seon/schema.clj |
| A395 | 3 | VERIFIED | `seon.schema/projection-explainer` | src/seon/schema.clj |
| A396 | 3 | VERIFIED | `seon.schema/projection-validator` | src/seon/schema.clj |
| A397 | 3 | VERIFIED | `seon.schema/projection-without-schema` | src/seon/schema.clj |
| A398 | 3 | INFERRED | `seon.schema/pulled-attribute-entry` | src/seon/schema.clj |
| A399 | 3 | INFERRED | `seon.schema/pulled-form-from-spec` | src/seon/schema.clj |
| A400 | 3 | VERIFIED | `seon.schema/register-candidate!` | src/seon/schema.clj |
| A401 | 3 | VERIFIED | `seon.schema/register-core-predicate!` | src/seon/schema.clj |
| A402 | 3 | VERIFIED | `seon.schema/render-contract-refusal!` | src/seon/schema.clj |
| A403 | 3 | VERIFIED | `seon.schema/runtime-predicate` | src/seon/schema.clj |
| A404 | 3 | VERIFIED | `seon.schema/unloaded-predicate-functions` | src/seon/schema.clj |
| A405 | 3 | VERIFIED | `seon.schema/unresolved-reference-refusal` | src/seon/schema.clj |
| A406 | 3 | INFERRED | `seon.schema/warn-classpath-fallback!` | src/seon/schema.clj |
| A407 | 3 | INFERRED | `seon.sci.admit/admit-walk` | src/seon/sci/admit.clj |
| A408 | 3 | INFERRED | `seon.sci.admit/canonical-edn` | src/seon/sci/admit.clj |
| A409 | 3 | INFERRED | `seon.sci.admit/frame-advance` | src/seon/sci/admit.clj |
| A410 | 3 | INFERRED | `seon.sci.admit/items-frame` | src/seon/sci/admit.clj |
| A411 | 3 | INFERRED | `seon.sci.admit/leaf-edn` | src/seon/sci/admit.clj |
| A412 | 3 | INFERRED | `seon.sci.admit/object-node` | src/seon/sci/admit.clj |
| A413 | 3 | INFERRED | `seon.sci.admit/open-node` | src/seon/sci/admit.clj |
| A414 | 3 | INFERRED | `seon.sci.admit/scalar-leaf-prefix` | src/seon/sci/admit.clj |
| A415 | 3 | INFERRED | `seon.sci.admit/sci-named` | src/seon/sci/admit.clj |
| A416 | 3 | INFERRED | `seon.sci.admit/semantic-leaf` | src/seon/sci/admit.clj |
| A417 | 3 | INFERRED | `seon.sci.admit/string-leaf!` | src/seon/sci/admit.clj |
| A418 | 3 | VERIFIED | `seon.sci.eval/acquire-program!` | src/seon/sci/eval.clj |
| A419 | 3 | VERIFIED | `seon.sci.eval/build-base-ctx` | src/seon/sci/eval.clj |
| A420 | 3 | VERIFIED | `seon.sci.eval/call-detecting-program-change` | src/seon/sci/eval.clj |
| A421 | 3 | VERIFIED | `seon.sci.eval/cycle-refusals` | src/seon/sci/eval.clj |
| A422 | 3 | VERIFIED | `seon.sci.eval/evaluated-source` | src/seon/sci/eval.clj |
| A423 | 3 | VERIFIED | `seon.sci.eval/failure-text` | src/seon/sci/eval.clj |
| A424 | 3 | VERIFIED | `seon.sci.eval/host-namespace!` | src/seon/sci/eval.clj |
| A425 | 3 | VERIFIED | `seon.sci.eval/identity-name` | src/seon/sci/eval.clj |
| A426 | 3 | INFERRED | `seon.sci.eval/install-namespace-bindings!` | src/seon/sci/eval.clj |
| A427 | 3 | INFERRED | `seon.sci.eval/install-row!` | src/seon/sci/eval.clj |
| A428 | 3 | INFERRED | `seon.sci.eval/interface-proxy` | src/seon/sci/eval.clj |
| A429 | 3 | VERIFIED | `seon.sci.eval/interpretation-error` | src/seon/sci/eval.clj |
| A430 | 3 | INFERRED | `seon.sci.eval/interpreted-arity-message` | src/seon/sci/eval.clj |
| A431 | 3 | INFERRED | `seon.sci.eval/one-event` | src/seon/sci/eval.clj |
| A432 | 3 | INFERRED | `seon.sci.eval/promote-member!` | src/seon/sci/eval.clj |
| A433 | 3 | VERIFIED | `seon.sci.kernel/failure-value` | src/seon/sci/kernel.clj |
| A434 | 3 | VERIFIED | `seon.sci.kernel/invoke` | src/seon/sci/kernel.clj |
| A435 | 3 | INFERRED | `seon.sci.lookup/agent-test-ns` | src/seon/sci/lookup.clj |
| A436 | 3 | INFERRED | `seon.sci.reader/function-declaration` | src/seon/sci/reader.cljc |
| A437 | 3 | VERIFIED | `seon.sci.reader/read-events` | src/seon/sci/reader.cljc |
| A438 | 3 | INFERRED | `seon.sci.reader/schema-declaration` | src/seon/sci/reader.cljc |
| A439 | 3 | VERIFIED | `seon.sentinel/-main` | script/seon/sentinel.clj |
| A440 | 3 | VERIFIED | `seon.task/abandon!` | src/seon/task.clj |
| A441 | 3 | VERIFIED | `seon.task/adoption-conflict` | src/seon/task.clj |
| A442 | 3 | VERIFIED | `seon.task/attempt-html` | src/seon/task.clj |
| A443 | 3 | VERIFIED | `seon.task/chain-html` | src/seon/task.clj |
| A444 | 3 | VERIFIED | `seon.task/chain-task-html` | src/seon/task.clj |
| A445 | 3 | VERIFIED | `seon.task/refused` | src/seon/task.clj |
| A446 | 3 | VERIFIED | `seon.task/run-held!` | src/seon/task.clj |
| A447 | 3 | VERIFIED | `seon.test.accretion/render-ai` | src/seon/test/accretion.clj |
| A448 | 3 | VERIFIED | `seon.test.accretion/render-failure` | src/seon/test/accretion.clj |
| A449 | 3 | VERIFIED | `seon.test.runner/assertionless-failure` | src/seon/test/runner.clj |
| A450 | 3 | VERIFIED | `seon.test.runner/duration-failures` | src/seon/test/runner.clj |
| A451 | 3 | VERIFIED | `seon.test.runner/failure-message` | src/seon/test/runner.clj |
| A452 | 3 | VERIFIED | `seon.test.runner/failure-report` | src/seon/test/runner.clj |
| A453 | 3 | VERIFIED | `seon.test.runner/message-text` | src/seon/test/runner.clj |
| A454 | 3 | VERIFIED | `seon.test.runner/record-latest-tx` | src/seon/test/runner.clj |
| A455 | 3 | VERIFIED | `seon.test.runner/recorded-member-results` | src/seon/test/runner.clj |
| A456 | 3 | VERIFIED | `seon.test.runner/report-event!` | src/seon/test/runner.clj |
| A457 | 3 | VERIFIED | `seon.test.runner/run-vars!` | src/seon/test/runner.clj |
| A458 | 3 | VERIFIED | `seon.test.runner/static-reach-digests` | src/seon/test/runner.clj |
| A459 | 3 | VERIFIED | `seon.test.runner/task-input` | src/seon/test/runner.clj |
| A460 | 3 | VERIFIED | `seon.test/admission-refusal!` | src/seon/test.clj |
| A461 | 3 | VERIFIED | `seon.test/apply-seeds!` | src/seon/test.clj |
| A462 | 3 | VERIFIED | `seon.test/arm-first-loads!` | src/seon/test.clj |
| A463 | 3 | VERIFIED | `seon.test/bounded-result` | src/seon/test.clj |
| A464 | 3 | VERIFIED | `seon.test/clean-request-handle` | src/seon/test.clj |
| A465 | 3 | VERIFIED | `seon.test/failure-text` | src/seon/test.clj |
| A466 | 3 | VERIFIED | `seon.test/file-two-outcomes!` | src/seon/test.clj |
| A467 | 3 | VERIFIED | `seon.test/host` | src/seon/test.clj |
| A468 | 3 | VERIFIED | `seon.test/host-text` | src/seon/test.clj |
| A469 | 3 | VERIFIED | `seon.test/member-result` | src/seon/test.clj |
| A470 | 3 | VERIFIED | `seon.test/program-overlay-tx` | src/seon/test.clj |
| A471 | 3 | VERIFIED | `seon.test/resolve-test` | src/seon/test.clj |
| A472 | 3 | VERIFIED | `seon.test/tally` | src/seon/test.clj |
| A473 | 3 | VERIFIED | `seon.test/unfulfilled` | src/seon/test.clj |
| A474 | 3 | VERIFIED | `seon.test/unknown` | src/seon/test.clj |
| A475 | 3 | VERIFIED | `seon.turn.batch/jvm-evaluate` | src/seon/turn/batch.clj |
| A476 | 3 | VERIFIED | `seon.turn.batch/refuse-cycle!` | src/seon/turn/batch.clj |
| A477 | 3 | INFERRED | `seon.turn.batch/schema-tx` | src/seon/turn/batch.clj |
| A478 | 3 | VERIFIED | `seon.turn/dial-absent` | src/seon/turn.clj |
| A479 | 3 | VERIFIED | `seon.turn/episode-exhausted!` | src/seon/turn.clj |
| A480 | 3 | INFERRED | `seon.turn/proposal-text` | src/seon/turn.clj |
| A481 | 3 | INFERRED | `seon.turn/read-row` | src/seon/turn.clj |
| A482 | 3 | INFERRED | `seon.turn/record-attempt!` | src/seon/turn.clj |
| A483 | 3 | VERIFIED | `seon.turn/refuse!` | src/seon/turn.clj |
| A484 | 3 | INFERRED | `seon.turn/render-ai` | src/seon/turn.clj |
| A485 | 3 | INFERRED | `seon.turn/segmented-sources` | src/seon/turn.clj |
| A486 | 3 | INFERRED | `seon.turn/settle-batch-refusal!` | src/seon/turn.clj |
| A487 | 3 | VERIFIED | `seon.turn/turn-completion-error` | src/seon/turn.clj |
| A488 | 3 | INFERRED | `seon.turn/with-diagnostic` | src/seon/turn.clj |
| A489 | 3 | VERIFIED | `seon.turn/write-refusal-error` | src/seon/turn.clj |
| A490 | 3 | INFERRED | `seon.vocabulary/-main` | script/seon/vocabulary.clj |
| A491 | 4 | VERIFIED | `my.program/sizes` | src/my/program.clj |
| A492 | 4 | INFERRED | `seon.batch/top` | src/seon/batch.clj |
| A493 | 4 | VERIFIED | `seon.context/branch-cost` | src/seon/context.clj |
| A494 | 4 | VERIFIED | `seon.context/exchange-ai` | src/seon/context.clj |
| A495 | 4 | INFERRED | `seon.db/cost-upserts` | src/seon/db.clj |
| A496 | 4 | INFERRED | `seon.db/registered-attribute-candidates` | src/seon/db.clj |
| A497 | 4 | VERIFIED | `seon.db/render-transaction-ai` | src/seon/db.clj |
| A498 | 4 | VERIFIED | `seon.dev.context-corpus/duplicates` | script/seon/dev/context_corpus.clj |
| A499 | 4 | VERIFIED | `seon.dev.docstring/format-findings` | script/seon/dev/docstring.clj |
| A500 | 4 | VERIFIED | `seon.dev.markdown/format-violations` | script/seon/dev/markdown.clj |
| A501 | 4 | INFERRED | `seon.edit/context-window` | src/seon/edit.clj |
| A502 | 4 | VERIFIED | `seon.error/bounded-text` | src/seon/error.clj |
| A503 | 4 | VERIFIED | `seon.error/call-lines` | src/seon/error.clj |
| A504 | 4 | VERIFIED | `seon.error/fit-fact-payload` | src/seon/error.clj |
| A505 | 4 | INFERRED | `seon.error/local-owners` | src/seon/error.clj |
| A506 | 4 | INFERRED | `seon.error/names-in` | src/seon/error.clj |
| A507 | 4 | INFERRED | `seon.error/nearest-calls` | src/seon/error.clj |
| A508 | 4 | INFERRED | `seon.error/nearest-locals` | src/seon/error.clj |
| A509 | 4 | INFERRED | `seon.error/nearest-names` | src/seon/error.clj |
| A510 | 4 | INFERRED | `seon.error/nearest-schemas` | src/seon/error.clj |
| A511 | 4 | VERIFIED | `seon.error/render-chain-ai` | src/seon/error.clj |
| A512 | 4 | VERIFIED | `seon.health/render-ai` | src/seon/health.clj |
| A513 | 4 | INFERRED | `seon.operator/log-tail` | script/seon/operator.clj |
| A514 | 4 | INFERRED | `seon.problems/agent-error-parts` | src/seon/problems.clj |
| A515 | 4 | VERIFIED | `seon.problems/resolution-text` | src/seon/problems.clj |
| A516 | 4 | INFERRED | `seon.profile/measure` | src/seon/profile.clj |
| A517 | 4 | INFERRED | `seon.profile/selected` | src/seon/profile.clj |
| A518 | 4 | INFERRED | `seon.profile/summary` | src/seon/profile.clj |
| A519 | 4 | VERIFIED | `seon.render.transcript/agent-history-under` | src/seon/render/transcript.clj |
| A520 | 4 | VERIFIED | `seon.render.value/bounded-text` | src/seon/render/value.clj |
| A521 | 4 | VERIFIED | `seon.render.value/causes` | src/seon/render/value.clj |
| A522 | 4 | VERIFIED | `seon.render.value/core-text` | src/seon/render/value.clj |
| A523 | 4 | VERIFIED | `seon.render.value/datum` | src/seon/render/value.clj |
| A524 | 4 | VERIFIED | `seon.render.value/link-lines` | src/seon/render/value.clj |
| A525 | 4 | VERIFIED | `seon.render.value/shown-chain` | src/seon/render/value.clj |
| A526 | 4 | VERIFIED | `seon.render.value/throwable-text` | src/seon/render/value.clj |
| A527 | 4 | VERIFIED | `seon.render.var/status` | src/seon/render/var.clj |
| A528 | 4 | VERIFIED | `seon.repl/response-entries` | src/seon/repl.clj |
| A529 | 4 | VERIFIED | `seon.repl/shown-output` | src/seon/repl.clj |
| A530 | 4 | INFERRED | `seon.schema.admission/similar-identities` | src/seon/schema/admission.clj |
| A531 | 4 | VERIFIED | `seon.sci.eval/interpreted-arity-message` | src/seon/sci/eval.clj |
| A532 | 4 | VERIFIED | `seon.test.accretion/render-ai` | src/seon/test/accretion.clj |
| A533 | 4 | VERIFIED | `seon.test.platform/run-check!` | src/seon/test/platform.clj |
| A534 | 4 | VERIFIED | `seon.test.runner/message-text` | src/seon/test/runner.clj |
| A535 | 4 | VERIFIED | `seon.test.runner/report-pair` | src/seon/test/runner.clj |
| A536 | 4 | VERIFIED | `seon.test/program-overlay-tx` | src/seon/test.clj |
| A537 | 4 | VERIFIED | `start-flow-benchmark/costs` | script/start_flow_benchmark.clj |
| A538 | 5 | VERIFIED | `seon.batch/cost-cell` | src/seon/batch.clj |
| A539 | 5 | VERIFIED | `seon.context/cost-badge` | src/seon/context.clj |
| A540 | 5 | VERIFIED | `seon.context/token-badge` | src/seon/context.clj |
| A541 | 5 | VERIFIED | `seon.db/transaction-value-html` | src/seon/db.clj |
| A542 | 5 | VERIFIED | `seon.problems/row` | src/seon/problems.clj |
| A543 | 5 | VERIFIED | `seon.render.sent/call-header` | src/seon/render/sent.clj |
| A544 | 5 | VERIFIED | `seon.render.transcript/cost-html` | src/seon/render/transcript.clj |
| A545 | 5 | VERIFIED | `seon.render.transcript/runtime-turn-table` | src/seon/render/transcript.clj |
| A546 | 5 | VERIFIED | `seon.render.transcript/session-header` | src/seon/render/transcript.clj |
| A547 | 5 | VERIFIED | `seon.render.transcript/test-html` | src/seon/render/transcript.clj |
| A548 | 5 | VERIFIED | `seon.render.transcript/turn-header` | src/seon/render/transcript.clj |
| A549 | 5 | VERIFIED | `seon.render.walk/related-html` | src/seon/render/walk.clj |
| A550 | 5 | VERIFIED | `seon.render.web/applicable-renderers-html` | src/seon/render/web.clj |
| A551 | 5 | VERIFIED | `seon.render.web/calls-html` | src/seon/render/web.clj |
| A552 | 5 | VERIFIED | `seon.render.web/chains-page-result` | src/seon/render/web.clj |
| A553 | 5 | VERIFIED | `seon.render.web/debug-found-value` | src/seon/render/web.clj |
| A554 | 5 | VERIFIED | `seon.render.web/debug-graph-html` | src/seon/render/web.clj |
| A555 | 5 | VERIFIED | `seon.render.web/debug-header-html` | src/seon/render/web.clj |
| A556 | 5 | VERIFIED | `seon.render.web/debug-read-dependencies-html` | src/seon/render/web.clj |
| A557 | 5 | VERIFIED | `seon.render.web/debug-response` | src/seon/render/web.clj |
| A558 | 5 | VERIFIED | `seon.render.web/stream-strip-html` | src/seon/render/web.clj |
| A559 | 6 | INFERRED | `seon.cluster.agent/render-id-ai` | src/seon/cluster/agent.clj |
| A560 | 6 | INFERRED | `seon.cluster.message/inbox-html` | src/seon/cluster/message.clj |
| A561 | 6 | INFERRED | `seon.context/cost-badge` | src/seon/context.clj |
| A562 | 6 | VERIFIED | `seon.context/exchange-text` | src/seon/context.clj |
| A563 | 6 | INFERRED | `seon.error/render-chain-ai` | src/seon/error.clj |
| A564 | 6 | INFERRED | `seon.render.sent/view-html` | src/seon/render/sent.clj |
| A565 | 6 | INFERRED | `seon.render.transcript/render-run-html` | src/seon/render/transcript.clj |
| A566 | 6 | VERIFIED | `seon.render.value/declared` | src/seon/render/value.clj |
| A567 | 6 | VERIFIED | `seon.render/declared-projection` | src/seon/render.clj |
| A568 | 6 | VERIFIED | `seon.repl/live-response` | src/seon/repl.clj |
| A569 | 6 | INFERRED | `seon.test/failure-text` | src/seon/test.clj |
