---
type: audit
status: complete
created: 2026-09-26
auditor: gpt-6-astra
audited-head: 20b1568603e8d1e732b31aa2568b3279c436458b
scope: agent REPL path; read-only investigation; no tests
---

# Agent REPL path: root-cause audit, 2026-09-26

The installed path reads forms, evaluates them in SCI, derives program changes, gates them, settles a turn, records evidence and renders results. Several stages independently reconstruct the namespace, program, error identity or execution outcome; those reconstructions disagree on ordinary Clojure values and forms.

This audit assigns those decisions to their existing owners. It proposes removing duplicate decisions, not adding special cases for `require`, `defn`, `letfn`, sorted maps or individual exception classes.

## Basis and limits

Auditor: Astra. I read `CLAUDE.md` end to end before investigation, the data-oriented Clojure and REPL skills, the reports below, relevant source and dependency source, and today's history under `src/seon/sci`, `src/seon/turn.clj` and `src/seon/fn.clj`. No tests, production edits, branch mutations, additional JVMs or agent launches were performed. Three JVM read-only probes ran sequentially through MCP, with explicit root `/Users/sean/src/seon` and cluster `default`.

The clean-system report and initial probes concern **013e1292407505cd6c812ea7537f5029f24e3a1e**. During this audit, HEAD advanced to **20b1568603e8d1e732b31aa2568b3279c436458b**. Unless explicitly qualified, source coordinates below refer to that audited HEAD. The intervening changes are incorporated as source changes, not credited as installed proof:

| Commit | Change | What this audit can establish |
|---|---|---|
| `926326d8c` | Required host Vars no longer receive the SCI generation stamp; regeneration retains paths absent from the previous base. | Removes the particular `require` admission trigger. Still uses separate generation/path rules for retention and authorship. |
| `e9b2066ac` | `identity-names` accepts a set; `declared-callers` supplies a set. | Corrects the input mismatch reproduced below. Does not correct refusal construction from compiled predicates. |
| `5d353149b` | Error rendering falls back to the base renderer instead of inventing a contract finding. | Removes that render-time finding. `error-alternatives` still derives specialized presentation from the operation's output contract. |
| `b51fbb71d` | Deletes the unused error contract-finding render functions. | Removes 21 additional lines from the retired presentation path; source proof only. |
| `20b156860` | Adds task records and a task landing; intervening `c72265004` adds a landing. | No additional runtime-source changes affecting the coordinates below. |

Reports are observations at their recorded revisions, not proof that every defect remains at audited HEAD. “Source changed” below means precisely that. An unproven cause is marked as such; an observation is not attributed to a convenient nearby function.

Evidence abbreviations:

- **S** — `tmp/orchestrator/smoke-postnuke-2026-09-26.md`.
- **R** — `docs/research/agent-platform/dogfood-report-2026-09-26.md`, numbered findings and later cycles.
- **A** — `docs/research/agent-platform/dogfood-author-opus-2026-09-26.md`.
- **M** — `docs/research/agent-platform/dogfood-maintainer-opus-2026-09-26.md`.
- **N** — `docs/research/agent-platform/dogfood-dogfood-newcomer-astra-2026-09-26.md`.
- **Y** — `docs/research/agent-platform/dogfood-dogfood-analyst-astra-2026-09-26.md`.
- **L** — today's landings: `fix-agent-path`, `unrestrict-sci`, `agent-repl-gaps`, `inv-agent-run-stability-opus`, `fix-root-graph-panic`, `fix-root-refusal-schema`; also `docs/research/agent-platform/agent-restrictions-and-rollback-2026-09-26.md` (outside `landing/`).
- **Q1–Q3** — the audit probes recorded below.

The failure inventory contains 52 symptom classes (one class per numbered row). Counts count symptoms once, under their primary explanatory model; overlapping causal paths are stated separately. They include historical defects and source-corrected defects. They are not counts of currently failing tests. Proposed consolidations remain subject to the stated dependency guarantees and acceptance evidence.

## Observed failures and Clojure acceptance bars

Seon's declared schema, test-first and merge policies add requirements that `clojure.main` does not have. A policy refusal can be correct. It must name that policy, preserve the evaluated evidence and terminate normally; it must not substitute a different Clojure meaning or a core exception.

### M1: Reconstructing language state from several partial representations — 14 symptoms

| ID | Observation and evidence | Producing code or surviving decision at audited HEAD | Acceptance bar |
|---|---|---|---|
| N1 | `require` rejected approximately 300 Malli Vars as undeclared changes (S). Source changed in `926326d8c`. | `src/seon/sci/eval.clj:249` loader; `:491` turn-interns; `:568` program-definitions; `:848` unindexed-change error. At `013e12924:271`, the loader stamped every copy with the same marker the declaration scanner consumed. | `(require '[malli.core :as m])` returns nil, establishes the alias and library Vars, and permits `m/validate`; it does not author those Vars. |
| N2 | Required dependency Vars disappeared on the next turn while the alias survived (L fix-agent-path). | `src/seon/sci/eval.clj:3082` regeneration, especially `:3107`; loader `:249`. `38ab16d95` fixed retention by stamping Vars and thereby produced N1. | The alias and its referents remain usable in the same REPL context on subsequent submissions. |
| N3 | Top-level `(ns ... (:require ...))` followed by a definition using that alias was analyzed without the new alias (R11, L). Partial source repairs. | `src/seon/sci/eval.clj:1166` namespace-source; `src/seon/fn.clj:930` synthetic analysis batch; `src/seon/turn.clj:3332`, `:3514`, `:5099` additional analyses. | Read/evaluate successive forms against the namespace resulting from the preceding form. An analyzer sees the same aliases. |
| N4 | `(do (in-ns 'x) (defn ...))` refused the actual definition as unindexed (R12). | `src/seon/sci/eval.clj:611` program-change matching, `:943` admission, `:4542` admission from the entering reader context, `:4559` single program row. | `do` evaluates its forms in order; `in-ns` changes where the subsequent definition is interned. Contracted definitions retain that meaning. |
| N5 | `register!` inside `let` refused because no isolated schema delta was recognized (R17). | `src/seon/sci/eval.clj:3696` declared-row recognizes a schema operation before evaluation; `:4679` candidate selection also depends on reader classification. | Lexical binding and sequencing do not alter the called writer's identity or schema-validation responsibility. |
| N6 | Registering a schema before a redefinition in one batch bypassed checks that the redefinition alone encountered (A2). Exact bypass branch remains unproven. | Relevant competing paths: `src/seon/sci/eval.clj:3703`, `:4562`, `:4579`; `src/seon/turn.clj:3321`, `:4863`. These are locations to distinguish, not proof of which one skipped admission. | The same authored definition and world receive the same policy decision regardless of whether a preceding schema write was submitted in the same batch. |
| N7 | Plain-SCI-valid `letfn` produced nil `HasName`; inline protocol `defrecord` produced `SciUnbound` cast errors (R19). Reported fixed by `5bed888d0`. | Current definition detection is `src/seon/sci/eval.clj:548`, `:568`; raw-contract processing `src/seon/fn.clj:790`; runtime definition metadata `src/seon/sci/eval.clj:902`. Historical classification treated implementation artifacts as program declarations. | Lexical functions execute locally; a record declaration and its methods have SCI's supported semantics without separate accidental function admissions. |
| N8 | `System`, `Math`, futures and Java interop were restricted or inconsistently available; `System/nanoTime` and `future` pass in S, while other parity issues persist in reports (R14, L). | `src/seon/sci/eval.clj:327` base construction, `:249` loader; dependency class handling in SCI. `2cae09887`, `b086ecd42` changed classes/add-ons; fork `7d28561e` changed normalized class allowance. | Supported host classes and asynchronous functions behave consistently in base and forked contexts; unsupported operations identify the actual missing facility. |
| N9 | A Date-using function evaluated, then became “host-bound / loaded files unavailable” (A4). | `src/seon/fn.clj:1029` host-bound-callers uses the default SCI class table at `:1052`; `:1070` declaration classification; `src/seon/sci/eval.clj:1619`, `:1694`, `:1722` install classification/refusal. | A definition executable in the configured interpreter does not become unsupported merely because an analyzer consulted a different class inventory. |
| N10 | Alias/first-party calls in the entry gate failed although direct test invocation succeeded (M2, R2). | `src/seon/sci/eval.clj:4763` run-candidate-test evaluates stored test source; `src/seon/test.clj:1805` clean-request-handle prepares a different world. | A test's namespace, required aliases and program dependencies are available in either entrance. |
| N11 | Rehydration lost a caller when its callee had not yet been installed; row survived and later test said no executable Var (R20). Source changed by declaration-first installation. | `src/seon/sci/eval.clj:2937` program installation; `:2958` declaration pass; `:2986` sorted installation; `:1752` failed-install removal. | Mutually referring definitions retain their bindings; installation order does not silently delete one. Unavailable execution prevents a green result. |
| N12 | Branches created before adoption became unusable, with loaded identity/host-bound refusals on ordinary forms (R1, L). Source changed. | `src/seon/sci/eval.clj:785` restore-or-unavailable; `:1606` overridden-row?; `:1631` install-row; `:3082` regeneration. | A branch's program remains its program. Explicit adoption changes named definitions and affected callers while preserving private state. |
| N13 | Namespace reset to `user` across MCP submissions; supplied namespace was rejected or ignored in earlier runs (R smaller, A9, L). Turn-side fix alone did not remove MCP defaulting. | `script/seon/dev/mcp.clj:337` namespace-symbol! defaults `user`; `:426`, `:433`, `:616` passes it; `src/seon/turn.clj:5067` carries ending namespace. | An omitted namespace preserves current `*ns*`; an explicit namespace changes it once through the same entrance. |
| N14 | `clojure.test` direct failures had no output, `run-tests` reported zero or rejected namespaces; `my.test/run` ignored namespace (R23). Source changed in `5454abd5b`; residual protocol differences documented in L. | `src/seon/sci/eval.clj:279` SCI test adapters call host test-vars; `:328` base; `:4479` writer bindings. | A defined test runs once, reports its assertions to the current test output and resolves the supplied namespace. Hooks and reporting behavior are either supported or explicitly identified as a remaining difference. |

### M2: Several independently constructed admission/test worlds — 11 symptoms

| ID | Observation and evidence | Producing code or surviving decision at audited HEAD | Acceptance bar |
|---|---|---|---|
| T1 | Fresh contracted `defn` failed before its body ran: `identity-names ... invalid-input` (S, Q1). Source changed in `e9b2066ac`. | `src/seon/turn.clj:3339` selects tests; `src/seon/fn.clj:1885`, `:1888`, `:1896` pass sets; `:1849` now accepts sets, previously sequential-only. | The declaration returns its Var and `(smoke-inc 1)` returns 2, subject to a valid Seon gate decision. An internal collection mismatch is not a user refusal. |
| T2 | Tiny changes selected 1,495–1,626 tests; unresolved support references and long recursive gates followed (R2, M4, N, Y). | `src/seon/fn.clj:1775`, `:1800` conservative graph; `:1868` call-reach; `:1942` gate-sets has different map/positional meanings. Selection changed in `20a87b1d1`. | Relevant tests are selected from declared invocation dependencies; quoted data alone does not execute a test. A bounded request terminates with its selection evidence. |
| T3 | Registering an agent schema caused `my.test/check` to fail during fork/contract installation with missing schema (A1). | `src/seon/test.clj:1624` program overlay; `:1805` clean request; `:1876`, `:1878`, `:1882` context/environment/fork; `src/seon/sci/eval.clj:3621`, `:3654`, `:3686` receiving projection and pre-acquisition rearming. The report locates failure during this fork; this audit did not reproduce which schema contribution was missing. | The test world contains the schema and functions from the same captured branch basis before contracts are compiled. |
| T4 | Generated checks/test installation wrote generated notes into the live working branch (A3); wrong inherited test custody was also reported (R3, not reproduced fixture-free later). | `src/seon/turn.clj:3345` passes cluster connection to candidate; `src/seon/sci/eval.clj:4628` SCI fork; `:4763` candidate tests; `src/seon/test/accretion.clj:110`, `:201`, `:255` invokes generated cases. | Ordinary invocation performs its requested write once. Verification runs in explicit disposable custody and cannot add generated business data to the author's branch. A SCI fork alone does not isolate the database. |
| T5 | Test context lacked agent/plan custody; `my.plan/current` could not be exercised as documented (R6). | `src/seon/test.clj:1805` clean-request-handle; `:2188` run; candidate entrance `src/seon/sci/eval.clj:4763`. | A declared agent-world input is supplied by the existing harness, or refused before execution by name. It is not silently replaced by a context without an agent. |
| T6 | Auto-check used empty/schemaless generated databases, and failure evidence contained parser placeholders (R13); later nil explanation violated observation schema (L). | `src/seon/test/accretion.clj:149` supplied-registry, `:172` generator fallback, `:138` returned?, `:142` observation; `resources/seon/schemas/seon.test.accretion.edn:14` explanation string. | Supplied database values come from declared world inputs; absent explanation is absent, not a stored nil or parser artifact. |
| T7 | Entry gate passed explicit nil candidate context although its optional contract excludes nil (L fix-agent-path). | `src/seon/turn.clj:3347`; `src/seon/sci/eval.clj:4679` creates candidates only for reader-recognized functions; `:4788` evaluate-candidate. | Optional absence remains no key. All supported declaration forms receive the same admission world. |
| T8 | Docstring-only change selected/executed behavior tests without distinguishing unchanged behavior (M3); ordinary single checks took 2–3.6 seconds, one schema case 17.3 seconds (R, A, N). | `src/seon/turn.clj:3332`, `:3339`; `src/seon/test.clj:1805`; `src/seon/sci/eval.clj:4788` rebuild/check path. | Verification reuses evidence whose actual program/input dependencies remain valid; changed declarations still receive required metadata validation. No claim here that every docstring is semantically irrelevant. |
| T9 | Caller arity changes did not receive an adequate gate; `breaks` failed on non-string retirement input and initially named deleted `launch!` (R16, M6–7). | `src/my/program.clj:281` breaks advisory; `src/seon/fn.clj:1868` call reach; declaration analysis `src/seon/fn.clj:930`. | Changing a callable signature identifies actual affected callers and required proof. An advisory's own input contract must describe its supported requests; it is not a second gate. |
| T10 | `propose-merge!` included inherited/foreign changes; no selection facility to propose only intended definitions (A7). | `src/my/program.clj:443` proposal; `src/seon/program.cljc:97` overrides; `src/my/program.clj:584` override view. | REPL mutation and merge selection remain distinct. A proposal accurately names its full program delta before acceptance; selective merge is an explicit product decision, not implied by evaluation success. |
| T11 | Request-map supplied database worked while positional declared database did not; static arity docs still included supplied inputs (A19, R smaller). | `src/seon/call_preparation.clj:307` schema defaults, `:569` candidate indexes, `:680` supplied-map-entries; `src/seon/sci/eval.clj:2341` documentation contract. | Explicit arguments win; omitted inputs are supplied by their declared schema name consistently across supported call shapes. Documentation shows the effective callable shapes. |

### M3: Inferring error identity after the producer — 8 symptoms

| ID | Observation and evidence | Producing code or surviving decision at audited HEAD | Acceptance bar |
|---|---|---|---|
| E1 | Every ordinary exception acquired “none of its declared error alternatives matches it” (S, R, N). Source changed in `5d353149b`. | `src/seon/render.clj:415` uses error operation as returning function; `:349` removes base alternative; `:515` now falls back to base. Previous `013e12924:520` emitted a finding from this mismatch. | `(/ 1 0)` reports arithmetic failure. A boundary named in an error does not imply that boundary returned the error as its entire result. |
| E2 | Actual invalid input was obscured by “the refusal could not be built” (S, Q1–Q2). | `src/seon/instrument.clj:542` canonicalizes compiled `m/form`; `:577` expected shape; `:642`, `:657` refusal/fallback; `src/seon/schema.clj:600`, `:607` rejects unnamed callable. | Invalid input is rejected before body entry with its original offending value and contract; reporting it does not require re-admitting a compiled predicate as authored source. |
| E3 | Division, assertion and nested `ex-info` lost class/ex-data/cause and were replaced by failure-value diagnostics (R14). Later runs preserve much of this. | `src/seon/sci/eval.clj:4327`, `:4357` failure-result; `src/seon/error/refusal.clj:91`, `:114` diagnostic/chain. | Preserve original throwable class, message, ex-data and causes, plus source location. Reporting wrappers do not replace the cause. |
| E4 | Interpreted arity errors printed `(nil)` rather than the function identity (R14, L). | `src/seon/sci/eval.clj:4028` interpreted-arity-message reconstructs identity from SCI callstack metadata. | Wrong-arity failure identifies the called function when available and actual arity; unavailable identity remains explicitly unknown. |
| E5 | Analysis failure schema was not an error-base subtype; fault recording itself failed/panicked (L stability/root-refusal). | `src/seon/fn.clj:1001` unresolved-macro-refusal; `resources/seon/schemas/seon.fn.edn:246` declaration; `src/seon/error.clj:522` validates error declaration; `src/seon/turn.clj:3514` now diverts analysis into a phase result. | Analysis refusal is one producer-declared value that both caller and fault recorder can consume; routing cannot change whether its schema is valid. |
| E6 | Three settlement paths guessed the refusal schema; missing match produced nil and another fault (L root-refusal). | `src/seon/turn.clj:3786`, `:3861`, `:4197` separate guesses. | A refusal's identity follows it from its producer; settlement does not infer it from incidental members. |
| E7 | `inst?` versus quoted `inst?` contracts failed through different runtime/raw contract paths (A5). | `src/seon/fn.clj:790` raw authored metadata; `src/seon/sci/eval.clj:921` runtime data-contract!; `src/seon/test/accretion.clj:75` rejects callable objects; `src/seon/schema.clj:582` canonical-definition. | Clojure permits callable metadata; Seon's durable contract policy may require a named predicate, but one admission owner resolves/validates it and gives a stable refusal. |
| E8 | `*e` was nil after failure; `Throwable->map` was unavailable (N). | `src/seon/sci/eval.clj:1097` result binding and `:4327` failure path; namespace exposure in `:327`. No `clojure.main` exception-register stage appears in that path. | The last uncaught exception is inspectable through `*e`, with structured cause data, or the entrance explicitly declares the differing exception handle. |

### M4: Execution success, settlement and terminal ownership are separate guesses — 9 symptoms

| ID | Observation and evidence | Producing code or surviving decision at audited HEAD | Acceptance bar |
|---|---|---|---|
| L1 | Symbol-key sorted map (M5), string-key sorted map (Y) left a run open; Q3 reproduces the current classification exception. | `src/seon/turn.clj:3309` validates arbitrary result as my.turn/value; `src/seon/schema.clj:3659`; Malli map lookup `reference-code/malli/src/malli/core.cljc:1276`; also unchecked result keyword lookup `src/seon/turn.clj:4958`. | `(sorted-map 'a 1)` returns and prints that map. Classification does not compare its keys with unrelated keywords. |
| L2 | Alias/definition batch hung with no worker, including repeated 40/45/120-second observations (R22, M1). | Escapes can occur after evaluator protection at `src/seon/turn.clj:4905`, `:4911`, `:4938`; phase catches only ExceptionInfo at `:3502`; step cancels its backstop in `finally` at `:5866`. Exact cause of each historical hang is not established. | Each submitted run has a recorded terminal outcome, including failures after evaluation; cancellation/timeout remains owned until actual exit. |
| L3 | One settlement refusal replaced results for other forms and could become a core fault (R15). Source partly changed by `266b52353`. | `src/seon/turn.clj:3842`, `:3874`, `:3888` failed-ordinal reconstruction and fallback to all values; transaction `:3684`. | Distinguish evaluated values from atomic publication outcome. A failed publication does not claim that successful computations returned the refusal. |
| L4 | A refusal left the execution unavailable for later ordinary forms (R21). Source changed by `853bae417`. | `src/seon/sci/eval.clj:785`, `:3082`; turn resume `src/seon/turn.clj:5067`. | Recoverable user refusal ends that operation; subsequent independent forms still execute. Failed program installation remains explicitly unavailable until reconciled. |
| L5 | Regex-literal query read evidence failed EDN settlement and left no completion (R4). Later verified fixed. | `src/seon/db.clj:926`, `:943` current read-evidence retention policy; recording `src/seon/turn.clj:4938`. `b96d6e684` removed unsupported retained literal payload in this path. | The query may accept a Pattern even though EDN cannot encode it; diagnostic persistence must not change query semantics or lose termination. |
| L6 | Writes through another connection looked read-only/generated and caused root faults (L root-graph). Source repair excludes foreign reads, leaving a writes-without-reads question. | `src/seon/turn.clj:2002` read-only-evaluation? infers from own receipt/effects and read connections; generated fault `:2081`. | Record effects where the writer performs them. No observed local write is not proof of a read-only evaluation. |
| L7 | Bound-entity generated reads were reported as changed by unrelated new turn-id facts (L root-refusal). | `src/seon/turn.clj:2081` generated-read-fault now consults precise patterns; `src/seon/db.clj:1055` index change witness. | Refresh only reads whose actual query/index dependencies changed; an attribute's unrelated use is insufficient. |
| L8 | Branch-local messages did not reach the intended recipient (R5); later verified fixed. | `src/my/message.clj:48` supplies sender custody; `src/seon/cluster/message.clj:706`, `:727`, `:746` now choose recipient home and write under that custody. `fdd9926e8` replaced sender-branch-only delivery. | A successful send has durable delivery evidence in the declared recipient world. Branch custody cannot silently imply delivery elsewhere. |
| L9 | Root graph stopped/panicked; status could itself fail when pinging a stopped graph (L root-graph, Y). | `src/seon/cluster/agent.clj:514`, `:548` transform handles interruption only; `src/seon/turn.clj:5863` finalization; `src/seon/oversight.clj:93` status now checks graph state before ping. | A core fault leaves a readable terminal status and usable diagnostic REPL. Status reports failure without depending on the failed graph answering. |

### M5: Reconstructing values through presentation — 10 symptoms

| ID | Observation and evidence | Producing code or surviving decision at audited HEAD | Acceptance bar |
|---|---|---|---|
| P1 | `keys`, `vals`, `sort`, `seq`, `rseq`, arrays and lazy values printed as objects/pending instead of values (R7). Later verified fixed. | `src/seon/render/value.clj:365` seq-cells, `:413` classification; record handling near `:468`. | Finite sequences print their elements; bounded lazy output identifies elision and retrieval. The underlying result remains available. |
| P2 | Partial map with an entity key selected a renderer needing fields absent from the map; evaluation failed (R8). | `src/seon/render.clj:336`, `:345` entity-owner uses owned key subset, not satisfaction of the renderer input contract; `src/seon/sci/eval.clj:4600` shown-result is inside evaluation success. | A partial map stays a partial map; renderer inapplicability yields floor output/diagnostic without changing computation success. |
| P3 | Rendering a rendering error recurred, producing eight levels and roughly 25 KB (R9); invalid JVM argument produced about 30 KB (M10). | `src/seon/render/value.clj:650`, `:679` declared render replacement/recursive node construction; `src/seon/render.clj:496`; `src/seon/sci/eval.clj:4363` renders failure while constructing failure result. | A rendering failure produces one diagnostic with retrievable underlying value, not a new recursively rendered evaluation failure. |
| P4 | Captured println output disappeared from MCP; stderr merged into stdout (R10). Output forwarding repaired in `b66e…`; test output binding in `5454abd5b`. | `script/seon/dev/mcp.clj:441`, `:451`, `:454`, `:457`; `src/seon/sci/eval.clj:4479`; `src/seon/repl.clj:449`. | `(println "x")` displays `x` then nil; output and exception evidence retain their meaning across entrance/recording/printing. |
| P5 | `doc` replaced keyword map keys with entire schema forms; supplied parameters remained; examples/specs absent (R18, N). | `src/seon/sci/eval.clj:2361`, especially `:2367` postwalk-replace over the whole contract; `:2338` function schema. | Documentation describes the real keys and callable arguments. Schema expansion affects schema positions, not arbitrary literal keys. |
| P6 | `dir` displayed columns/rows while its actual map had functions/schemas; accessing displayed `:rows` returned empty/nil; printed database `t` was not a data key (A10, N, Y). | `src/seon/repl.clj:32` directory renderer; `src/seon/sci/eval.clj:2415` directory-value; `src/seon/render/value.clj:1059` edge text. | Printed explanatory views label transformations and provide runnable access to the actual value; display keys are not presented as stored keys. |
| P7 | Self/agent wildcard pull printed 181,252 characters, including a 141,224-character prompt; dir and explanatory refusals produced 25–30 KB (N, Y, A12–13). | `src/seon/render/value.clj:679`, `:693`, `:986`, `:1059`; nested strings and declared text pass through different budget decisions. | One value-print budget spans the complete tree; cuts identify count/path/retrieval. The record stores complete values separately from bounded shown text. |
| P8 | Schema properties containing `:seon.render/ai` were executed as the properties map's own renderer, producing repeated large refusals (A8). | `src/seon/render.clj:585` explicit-value treats a map's attachment as its renderer; `src/seon/render/value.clj:650`. | Inspecting schema data does not invoke a renderer intended for instances of that schema. The owner must distinguish schema declaration position from value attachment. |
| P9 | Vector of notes used raw nested rendering while one note used its renderer; issue renderer omitted body; new renderer pair appeared only on next turn (A16, N). | `src/seon/render.clj:524` root-only entity selection; `:389` attribute identity; `src/seon/sci/eval.clj:1214`, `:4579` projection acquisition. | A curated view declares omissions and retrieval. Render pair visibility follows a named projection basis, not accidental root/nested position or submission timing. Current root-only behavior is explicit policy, not proof of an evaluation defect. |
| P10 | Bare `source`/`apropos`/`find-doc` were unresolved; qualified source said not found for interpreted definitions; namespace objects opaque (R smaller, N, L). Source/namespace support partly added. | `src/seon/sci/eval.clj:2510`, `:2531` stored source; `:327` exposed namespaces; `src/seon/render/value.clj:413` floor classification. | Standard discovery facilities resolve where advertised; source reflects stored authored source. Missing convenience referrals are distinguishable from a missing definition. |

### Observations that do not establish another agent-path root cause

These are retained to avoid turning omissions or correlations into causal claims. They are excluded from the 52 model-symptom counts.

| Evidence | Observation | Disposition and code boundary |
|---|---|---|
| S, M8 | Uncontracted helper definition was refused. | Consistent with schema gate policy; unlike `clojure.main`, Seon may require a contract. The failure under audit is contracted T1, not this requirement. Admission: `src/seon/sci/eval.clj:943`. |
| A6, M1, Y | OOM, lost runtime, 17.7/17.8 GB heap dump after alias/test/retirement attempts. | No retained-allocation chain in these reports establishes which attempt caused the OOM. Broad selection and repeated context construction are candidates, not a heap diagnosis. Preserve separate allocation/retention evidence before assigning cause. |
| A11 | A schema reference failed in a batch, then worked alone; not reproduced in replacement runtime. | Relevant projection acquisitions are N6/T3. Exact failing producer not established; do not count a second proven projection defect. |
| A15, R smaller | Branch list at boot threw `fut` null; absent branch produced a raw large error. | Missing/readiness handling at the cluster/branch entrance needs its own captured stack and installed probe. No exact producing function was established by this audit. |
| R smaller, M13 | Hooks attributed a foreign dirty edit to the investigator. | Repository publication/tooling observation, outside the agent REPL path. No production hook change proposed. |
| R smaller, N | Help/tool list and next-turn grammar stale; nonexistent old issue symbols; external task not automatically a hosted plan; no obvious issue-fix shortcut. | Discovery/documentation mismatch, not proof that a named old function should exist. `src/seon/bootstrap.clj:16`, `script/seon/dev/mcp.clj:830`, directory P5/P10. The REPL skill's private-only restrictions also conflict with current unrestricted custody instructions. |
| A14, Y | Removal/deletion or selective branch/test operations unavailable or yielded unknown-operation replies; later retirement attempts timed out. | Separate product capability/retirement questions. `src/my/program.clj:700` onward and proposal T10; do not infer an implemented operation from a desired workflow. Terminal timeout obligations are L2/L9. |
| M9 | Unbalanced submission/read error attributed to `for`. | A malformed input should yield a located reader error. The report does not establish that valid `for` is broken. Reader: `src/seon/sci/eval.clj:1040`. |
| M12 | Example contract borrowed `my.plan` request schema; private status-view lacked contract. | Authoring/schema defects in that example, not evidence of a Clojure-form restriction. Contract admission remains required. |
| A18 | Cardinality-many pull returned vector where author expected a set. | Native pulled representation versus declared application schema must be reconciled at the data owner. This is not enough evidence to change Datahike pull semantics. |
| R smaller | Wrong plan-completion action; background request required EDN strings. | Interface discovery/contract issues; no reproduced runtime misexecution established. |
| S | `nuke --help` rejected. | CLI help behavior, outside this audit's agent path. |
| Y | Initial `get_value` path used string `"cause"` instead of keyword `:cause`. | Caller mistake; the corrected retrieval could not finish after the runtime disappeared. |

## Model errors, authorities and deletions

The inventories below enumerate the competing decision sites found in the traced path, including decisions that remain after today's local fixes. Deletion estimates are source-line estimates of replaceable responsibilities, not a proposed diff, measured net reduction or permission to remove required behavior. They overlap; do not sum them as a promised reduction.

### M1 — A partial description of execution is treated as execution's authority

**Owner:** SCI owns namespace bindings, Var roots and evaluation order. The program writer owns which resulting declarations are admitted as durable program rows. Static analysis describes dependencies of those declarations; it does not decide which namespace a completed form actually used.

**Current decisions:** reader recognition (`src/seon/sci/eval.clj:1040`, `:3696`, `:4679`); generation/metadata diff (`:493`, `:548`, `:568`); textual matching and single declaration (`:611`, `:864`, `:943`); synthesized namespace source (`:1166`); raw-source analysis (`src/seon/fn.clj:930`); gate analysis (`src/seon/turn.clj:3332`); settlement analysis (`:967`, `:3514`, `:5099`); static host classification (`src/seon/fn.clj:1029`, `:1070`); installed-row classification (`src/seon/sci/eval.clj:1606`, `:1619`, `:1694`); regenerated private layer (`:3082`); MCP namespace default (`script/seon/dev/mcp.clj:337`). Test namespace recreation adds `src/seon/sci/eval.clj:4763`.

**Why they disagree:** the reader sees syntax before execution; generation marks SCI ownership/copy-on-write, not semantic authorship; namespace synthesis handles particular top-level forms; a batch's writer can see a later database than its first analyzer; a default SCI class table differs from the configured context; regeneration needs to retain library copies that admission must not publish. `38ab16d95` is a direct demonstration: it repaired retention by changing admission evidence. `926326d8c` removes that collision but adds another retention criterion; it does not establish generation as an authorship guarantee.

**Smallest consolidation:** retain the existing SCI context and take namespace/Var observations from it; pass the resulting declaration identities, source spans and world basis to one program-admission analysis. Gate and settlement consume that same analysis and validate its basis at the writer. Make MCP omission preserve the context namespace. Use the actual configured interpreter's capabilities for executability. Library loading remains loading; it produces no authored declaration merely because Vars appeared.

Delete repeated declaration analysis in the gate/settlement (roughly 45–80 lines), namespace-source splicing (about 30), overlapping host-bound decisions (about 35–60), and regeneration-specific recovery/classification when retained-context adoption proves equivalent (about 65–90). Do not delete the existing preservation logic before proving private roots, aliases, imports, declarations and changed program bindings across adoption.

**Dependency guarantee and gap:** pinned SCI `7d28561e0b8b94fa08227dc80c7a8c43bc8aa308`, `reference-code/sci/src/sci/core.cljc:345` forks contexts; `:751`, `:762` expose namespace state and installation; `reference-code/sci/src/sci/impl/utils.cljc:359`, `:368`, `:394` implement generation/copy-on-write/root binding. These are state seams, not an installed declaration-event/source-span callback. Arbitrary macro-generated definitions still need a demonstrated source-association rule. Do not invent a library callback or promise that a namespace snapshot alone supplies source spans.

**Inputs, recomputation, cost:** analysis consumes authored source, resulting namespace resolution and the exact packaged projection. Recompute when those dependencies change, not per gate, settlement and print. Target one read and evaluation per form, analysis proportional to changed declarations and reached dependencies, and adoption proportional to changed rows/callers. Current repeated context/analysis work is visible in source; this audit does not provide an allocation profile for it.

### M2 — A copied interpreter is treated as a complete test world

**Owner:** the existing test-world/custody owner supplies one captured program, schema projection, connection, agent context and explicit effects. Admission selects proof and consumes its result. The writer decides whether that proof still applies.

**Current decisions:** turn gate (`src/seon/turn.clj:3321`); candidate SCI fork and optional context (`src/seon/sci/eval.clj:4628`, `:4679`, `:4788`); source-replayed gate tests (`:4763`); clean test request (`src/seon/test.clj:1805`, `:1876`); contract arming against loaded projection (`:2122`, `:2146`); generated supplied values (`src/seon/test/accretion.clj:149`, `:172`, `:201`); gate selection's two meanings (`src/seon/fn.clj:1942`); advisory caller/merge decisions (`src/my/program.clj:281`, `:443`); argument defaults (`src/seon/call_preparation.clj:569`, `:680`).

**Why they disagree:** the SCI context can be private while its connection is the live cluster's; program rows can be overlaid while the environment still carries another projection; direct tests and gate tests reconstruct different namespace worlds; an omitted candidate context becomes a present nil; a generated database is not the declared current database; test selection has an unrelated conservative interpretation under another arity. T1 is a local contract-shape mistake in this path, not proof that all selection must be rewritten.

**Smallest consolidation:** route candidate proof through the existing harness's explicit captured world, with schema and program installed before arming and one disposable connection for generated calls. Preserve the candidate's evaluated root as a value. Remove the candidate-only source/test runner and its independent world reconstruction (approximately 70–110 lines across `run-candidate-test`/`evaluate-candidate`), duplicate projection precedence (about 15–25), and generic generation fallback for world-owned inputs (about 15–30). Keep the corrected set contract. Give conservative reach a name distinct from test selection and have the gate consume one selection result. Proposal selection is a separate owner decision; this consolidation alone does not deliver selective merging.

**Guarantee:** SCI fork isolates interpreter state, not a supplied database connection. Malli validates the contract actually supplied; it cannot infer that a structurally valid but foreign database/projection is the intended world. No replacement evaluator or test harness is justified.

**Inputs, recomputation, cost:** captured commit/program digests, projection, branch custody, candidate root, declared inputs and selected tests. Prepare once per changed test world; branch writes from its captured value. Selection cost follows reached invocation edges, not whole-program mentions. Existing reports contain 2–3.6-second ordinary checks and a 17.3-second schema case; no fresh test timings were collected because this assignment forbids tests. These remain unmet performance evidence, not accepted costs.

### M3 — An error's location is treated as its producer contract

**Owner:** the producer declares error identity; the armed function boundary validates its actual inputs and outputs. The exception boundary preserves the throwable. Rendering selects a presentation; settlement stores the declared value.

**Current decisions:** Malli instrument callback and refusal builder (`src/seon/instrument.clj:282`, `:542`, `:550`, `:642`); canonical source conversion (`src/seon/schema.clj:582`, `:600`); raw/runtime contract conversion (`src/seon/fn.clj:790`, `src/seon/sci/eval.clj:921`); failure-value construction (`src/seon/sci/eval.clj:4327`); phase relabeling (`src/seon/turn.clj:3502`); three settlement discriminators (`:3786`, `:3861`, `:4197`); error declaration validation (`src/seon/error.clj:522`); operation-derived rendering (`src/seon/render.clj:349`, `:365`, `:415`, `:515`).

**Why they disagree:** `evaluate` returns an evaluation envelope, while an error inside it names evaluation as its location. Comparing that error to the envelope's return schema produces a false finding. `failure-value` can legitimately return the base error, but render analysis removes that alternative. A compiled Malli predicate is a live callable; asking durable source canonicalization to reconstruct its name can fail while explaining the original failure. Finally, settlement guesses schema identity from members even when producers already attach identity in metadata.

**Smallest consolidation:** carry the producer's declared error identity and original cause through the existing path; use the producer's already-armed output contract for validation. Keep `5d353149b`'s removal of renderer-generated findings, and remove operation-based reconstruction where producer identity already answers renderer selection (about 20–45 lines; retain general return-schema rendering for ordinary values). Replace the three schema-guessing tables with the producer decision (roughly 35–55 lines). Stop canonicalizing a compiled expected schema while building a refusal; preserve the admitted declarative form/identity at arming (about 10–20 conversion/fallback lines can disappear once equivalence is proved). Correct the analysis-error declaration itself instead of routing around its missing base relationship.

**Dependency guarantee:** pinned Malli `a89ebef0572628b9198b825e5aa8b59484902593`, `reference-code/malli/src/malli/core.cljc:2210` function-schema instrumentation, `:2233` input check and `:2235` output check; `:3139` `-instrument`. Malli's armed input check correctly rejected a set for `:sequential` in Q1. The refusal failure occurred afterward in Seon's conversion. Do not weaken validation to make that symptom disappear.

**Inputs, recomputation, cost:** admitted contract data and compiled validators are prepared once per contract/projection. A failure carries the offending value, declared identity, operation and cause chain. Explaining one failure must not scan function-output alternatives or re-admit compiled functions on every consumer. Q1/Q2 show the local producer fault in approximately one millisecond; they do not measure full rendering cost.

### M4 — Completion and effects are inferred outside the operation that owns them

**Owner:** the turn's execution boundary owns settlement and its terminal event until actual exit. Database writers own effects and read witnesses. A result classifier only classifies; it must be total over valid Clojure values.

**Current decisions:** evaluator try/catch (`src/seon/sci/eval.clj:4479`, `:4620`); post-evaluation gate/disposition/recording (`src/seon/turn.clj:4905`, `:4911`, `:4938`, `:4958`); limited phase catch (`:3502`); guessed failed ordinal (`:3874`); generated/read-only inference (`:2002`, `:2081`); step completion and bound cancellation (`:5863`); graph transform (`src/seon/cluster/agent.clj:514`, `:548`); status ping (`src/seon/oversight.clj:93`).

**Why they disagree:** a form may evaluate successfully and then throw during classification, analysis or evidence storage. The evaluator cannot catch those later operations. `step`'s docstring says an escaped pass leaves its bound armed (`src/seon/turn.clj:5744`), but `finally` cancels it whenever it publishes a ready permit (`:5866`), including exceptional exit. A permit is not a durable terminal receipt. Separately, absence of an own-connection receipt is not proof that no other writer acted.

**Smallest consolidation:** make the existing turn boundary record the terminal outcome of the entire read/evaluate/gate/settle/record operation, preserve evaluated results separately from publication status, and retire the bound only after its owning terminal/exit condition. Use safe classification of known discriminators before a schema validator whose key lookups can throw. Consume writer effect/read evidence instead of inferring effects from the absence of observations.

Delete failed-ordinal/result rewriting (roughly 25–45 lines), redundant success/cancel inference (about 10–20), and read-only heuristics when writer evidence replaces them (about 20–30). Do not add a second watchdog or catch-and-continue path. Core faults still follow the declared panic/record policy; the fix is complete ownership and recording, not suppression.

**Dependency guarantee:** Malli's map validator uses associative keyword lookup (`reference-code/malli/src/malli/core.cljc:1266`, `:1276`). Clojure sorted maps can reject comparison with another key type. `map?` is not a guarantee that every keyword lookup is safe. Q3 directly demonstrates the current Seon caller's missing precondition. It does not establish that Malli promises exception-free validation for all arbitrary comparators.

**Inputs, recomputation, cost:** one run identity, actual execution/exit state, writer receipts and precise read dependencies. Derive completion once per run and refresh only changed read inputs. Q3 takes 0.317 ms to throw; the 60-second/hundreds-of-seconds observations concern lost terminal ownership, not expensive sorted-map computation.

### M5 — Presentation is allowed to reinterpret the value it displays

**Owner:** the retained result/record owns the value. The existing renderer-selection and floor printer own presentation, with one whole-value budget. A renderer's failure is a presentation diagnostic, not the evaluation's result.

**Current decisions:** structural entity inference (`src/seon/render.clj:336`); root/nested distinction (`:524`); value-level attachment (`:585`); generic return-schema inference (`:365`); recursive declared rendering (`src/seon/render/value.clj:650`, `:679`); declared-text clipping (`:693`); final rendering (`:1059`); documentation postwalk (`src/seon/sci/eval.clj:2361`); directory display conversion (`src/seon/repl.clj:32`); evaluator's shown-result (`src/seon/sci/eval.clj:3859`, `:4363`, `:4600`); MCP output extraction (`script/seon/dev/mcp.clj:451`).

**Why they disagree:** owning a few attribute names does not establish satisfaction of an entity renderer's input contract; a schema-properties map is data about a renderer rather than necessarily its instance; a recursive schema substitution changes map keys as well as schema positions; display tables expose keys absent from the underlying value; nested declared output applies a local budget before the final face. Rendering inside success/failure construction makes a display fault change the apparent execution outcome.

**Smallest consolidation:** finish and retain the evaluation before printing; select renderers against their declared input/attachment context; let the existing floor render a renderer diagnostic and underlying retrievable value once. Traverse contract structure with Malli's schema facilities rather than generic data postwalk. Make transformed displays carry explicit runnable access to their actual values. Apply one budget at the shared value tree.

Remove generic documentation substitution (about 10–20 lines), structural partial-entity claiming where contract satisfaction is authoritative (about 10–20), duplicate local clipping/error recursion (about 20–40), and evaluator-side display-to-evaluation failure conversion (about 15–30). Retain schema render pairs and custom renderers; replacing them with a parallel printer would repeat the model error.

**Inputs, recomputation, cost:** actual retained value, viewing context, explicit render profile and packaged projection. Recompute presentation for changed value/profile/relevant renderer, not because an unrelated fact changed. Traverse at most the declared print budget plus explicit bounded query work. N's 181,252-character result is approximately 45,000 tokens at four characters/token, an estimate rather than tokenizer measurement; the 25–30 KB examples are roughly 6,000–8,000 tokens. A durable complete value does not require that much prompt text.

## Ranked fix order

Coverage rank is symptoms addressed per owner consolidation: M1 (14), M2 (11), M5 (10), M4 (9), M3 (8). These are historical symptom-class counts, not guaranteed remaining failures. The execution order below puts terminal ownership and accurate refusals first because later proof depends on them; it then follows coverage rank. Current source repairs must receive installed proof before being called resolved.

| Order | Owner correction | Symptoms addressed / work removed | Required evidence before integration |
|---|---|---|---|
| 1 | Turn owns total result classification and terminal settlement (M4). | Directly L1–L3, L9; closes the failure-containment path needed to investigate all other groups. Approximately 35–65 lines of duplicate inference/rewrite can disappear. | Sorted maps, analyzer refusal, recording failure and renderer failure each produce one terminal run; escaped execution retains the bound until exit. |
| 2 | Producer owns refusal identity and contract data (M3); prove the three new HEAD corrections. | Eight error symptoms; E1 and T1/N1 have narrow source repairs already, not installed proof. Deletes schema guesses and render-time validation. | Exact S forms succeed/refuse as intended; original contract failures remain inspectable; no “refusal could not be built”; no operation-derived contract finding. |
| 3 | SCI state plus one writer analysis owns declaration/namespace meaning (M1). | Fourteen language symptoms, including both directions of the require/retention regression. Highest broad symptom coverage per consolidation; approximately 110–200 overlapping lines are candidates before regeneration retirement. | Same forms across top-level, `do`, `let`, namespace changes and consecutive submissions; preserve private roots and resolve callees across adoption; explicit source-span proof for macro declarations. |
| 4 | Existing test owner supplies one captured world (M2). | Eleven admission/proof symptoms; T1's local shape is already corrected in source. Deletes approximately 100–160 lines of candidate-only reconstruction. | Gate/direct-test namespace and schema equivalence; generated writes stay on disposable custody; positive proof has correct basis; ordinary operations meet their declared bound. |
| 5 | Renderer owns one bounded presentation after evaluation (M5). | Ten presentation symptoms; removes evaluation reclassification and repeated budgets. Approximately 55–110 lines of competing decisions are candidates. | Partial maps, schema definitions, nested collections and renderer exceptions preserve underlying values; one bounded transcript with cuts/retrieval and faithful stdout/error evidence. |

There is no proposed combined 500-line subsystem. Each correction removes a decision from a downstream consumer and moves its input to the existing authority. If a slice needs a new registry, evaluator, printer, runner or watchdog, its proposed deletion has not yet been demonstrated.

## Read-only probes and installed evidence

Runtime status identified PID 43753, started `2026-09-26T16:47:16Z`, alive, no missing readiness members; readiness was 43,018 ms. It also reported three errored receipts and one unowned namespace. That is not a clean-health result. S records about 62 seconds for nuke, 43 seconds readiness and approximately 574 MB after GC versus 1.5 GB before GC. Those are report measurements, not fresh audit measurements. No fresh heap measurement, full-path throughput claim or browser-paint proof is made here.

All probes used MCP `eval_clj`, JVM mode, read-only, root `/Users/sean/src/seon`, cluster `default`, timeout 5,000 ms. No probe overlapped another. The connection was explicit; no SCI context or branch was created. Complete returned envelopes were inspected. They showed prepl execution of 4, 4 and 3 ms respectively, with no reported evaluation exception outside the caught local values.

Q1 tested the installed function's real armed input boundary:

```clojure
(let [started (System/nanoTime)
      database @(seon.cluster.boot/connection "default")]
  {:contract (:malli/schema (meta #'seon.fn/identity-names))
   :set-valid? (malli.core/validate [:sequential :int] #{})
   :vector-valid? (malli.core/validate [:sequential :int] [])
   :caught
   (try (#'seon.fn/identity-names identity database #{})
        (catch clojure.lang.ExceptionInfo e
          {:message (ex-message e)
           :declared-schema (:seon.error/declared-schema (meta (ex-data e)))
           :expected (:seon.error/expected (ex-data e))
           :construction-cause
           (when-let [cause (:seon.error/throwable (ex-data e))]
             {:class (.getName (class cause))
              :message (ex-message cause) :data (ex-data cause)})}))
   :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)})
```

Result: set-valid false, vector-valid true; installed contract input was `[:cat [:fn <clojure.core/ifn? callable>] :seon.db/database-value [:sequential :int]]`; message `seon.fn/identity-names :malli.core/invalid-input: the refusal could not be built.` Declared fallback schema was `:seon.error/base`. `:construction-cause` was nil because diagnostic construction had already moved the throwable into its chain, not because there was no cause. Elapsed **0.958917 ms**. This proves the installed pre-`e9b2066ac` mismatch, not the adoption of the new set contract.

Q2 read that chain through the same failing owner call:

```clojure
(let [started (System/nanoTime)
      database @(seon.cluster.boot/connection "default")]
  (try (#'seon.fn/identity-names identity database #{})
       (catch clojure.lang.ExceptionInfo e
         {:chain (:seon.error/chain (ex-data e))
          :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)})))
```

Result: a `clojure.lang.ExceptionInfo` with message “A durable Malli definition contains an unnamed callable: contracts are data; use a registered predicate schema or a quoted symbol naming an admitted predicate.” Data identified `:seon.schema/noncanonical-definition`, reason `:seon.schema/unnamed-callable`, value the live `clojure.core/ifn?` callable. Frames led through `seon.schema/callable-symbol` (`src/seon/schema.clj:607`) and canonical-definition. Elapsed **1.072041 ms**. Thus the second failure is not the original invalid input: the explanation path attempts durable source conversion of a compiled callable.

Q3 called the actual current disposition owner:

```clojure
(let [started (System/nanoTime)
      database @(seon.cluster.boot/connection "default")
      projection (seon.schema/projection-from-database database)]
  {:disposition
   (try (seon.turn/disposition projection (sorted-map 'a 1))
        (catch Throwable e
          {:class (.getName (class e)) :message (ex-message e)}))
   :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)})
```

Result: `java.lang.ClassCastException`, `clojure.lang.Symbol cannot be cast to clojure.lang.Keyword`; elapsed **0.316916 ms**. This distinguishes the present turn classifier from the earlier instrument-wrapper explanation in maintainer finding 5. It does not execute a full agent turn or prove that every observed timeout shares this exception.

## History and parity checks

The relevant history is a sequence of changes at different representations: `20a87b1d1` changed mention reach to call reach; `e90e18b29` predeclared bindings; `71d3d74c5` folded namespace context during settlement; `853bae417` allowed later runs after refusal; `e6c3487ad` added stop/join behavior; `85b459845` retained the prior namespace in the turn path; `be96a64ba` moved host-bound refusal to calls; `2cae09887`/`b086ecd42` expanded SCI facilities; `5454abd5b` added test/source adapters; `38ab16d95` changed library Var generations; `5ced0160b` reconstructed namespace source; `90c542fd0` narrowed foreign-read classification; `e2d1690d4` refined read-pattern evidence. None alone made the downstream models agree.

I inspected the `20a87b1d1` and `38ab16d95` diffs, not only their messages. The former explicitly retains conservative positional gate reach while changing map-arity test selection. The latter explicitly gives required library copies SCI generation metadata so regeneration retains them. The subsequent `926326d8c` diff removes that same stamp because declaration admission consumes it. This is direct historical evidence for M1, independent of any claim about a future design.

Pinned Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5` provides the acceptance reference: `reference-code/clojure/src/clj/clojure/main.clj:368` REPL; `:428` read/eval/print; `:446`, `:447` exception handling and `*e`; `reference-code/clojure/src/jvm/clojure/lang/Compiler.java:7711`, `:7742` evaluation and top-level `do` sequencing. Clojure does not require the reader to recognize an entire `do` as a `defn` before the inner definition can take effect. Seon may gate durable admission, but its gate must operate on that actual execution meaning.

Completion evidence still required after implementation is the exact failing-form matrix above through the shared agent entrance, including MCP and agent turn, with real SCI, armed contracts and declared test custody. This audit deliberately does not run that matrix: the assignment permits small read-only probes and prohibits tests. Source edits, commit messages and scratch proofs in landings are not substitutes for installed publication/adoption/arming and terminal-record evidence.

## Final summary: top five model errors

1. **Language state is reconstructed from syntax, generation and multiple namespace inventories — 14 symptoms.** SCI state and one writer analysis should decide declaration meaning.
2. **A copied interpreter is treated as a complete test world — 11 symptoms.** One captured test world should supply program, projection, custody and proof inputs.
3. **Presentation reinterprets the retained value — 10 symptoms.** Rendering should follow evaluation with one selection path and whole-value budget.
4. **Completion and effects are inferred outside their owning operation — 9 symptoms.** Turn finalization and database writers should provide terminal and effect evidence.
5. **Error identity is reconstructed from location and compiled forms — 8 symptoms.** Producers and armed contracts should declare errors once; consumers should preserve that decision.
