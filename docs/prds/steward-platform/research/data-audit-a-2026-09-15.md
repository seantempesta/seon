---
type: research
status: active
date: 2026-09-15
tags: [research, schema, test, context]
---

# Data audit A — program facts to verifiable tasks

## Finding and verification boundary

**The smallest viable chain is an explicitly identified function plus a reaching test, rendered from their existing program rows, with test evidence tied to the exact tested definitions, followed by export into two explicitly selected new programming files.** No durable problem entity is needed. Current reachability and test-result persistence are insufficient to declare that chain complete: Juniper's saved test calls its saved function but has neither that call edge nor a subject ref; the default has one latest test result among 1,608 test identities. The existing artifact/export owners do not write admitted definitions back to programming files. Evidence: probes **red**, **agentRows**, **agentSource2**, **fnGaps**, and the source/export section below.

This is a dated, read-only research audit, not an implementation or a claim of system health. Read end to end: [AGENTS.md](../../../../AGENTS.md), [the draft PRD](../plan/stewards-self-improving-prd-2026-09-15.md), all schema resources for the fifteen assigned families, their owning source namespaces, and [run 4](explain_probe_run4_2026_09_15.edn), [run 5](explain_probe_run5_2026_09_15.edn), [run 7](explain_probe_run7_2026_09_15.edn). Also read the program roadmap entry and the relevant dependency sources below. Each account is one EDN line; account citations below refer to line 1. The schema inventory below covers every top-level declaration, including its schema properties/docstring where supplied. Many primitive aliases have no docstring; their semantics were established from the writers, not inferred from their spelling.

Read source owners: `src/seon/fn.clj`, `src/seon/program.cljc`, `src/seon/error.clj`, `src/seon/problems.clj`, `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/test/accretion.clj`, `src/seon/cluster/source.clj`, `src/seon/cluster/export.clj`, `src/seon/artifact.clj`, and `src/seon/render/ns.clj`. The arity/output/source/export schema families do not imply same-named executable namespaces: arities are written by `seon.program`, output paths computed by `seon.fn`, and source/export operations owned under `seon.cluster` (source anchors below).

MCP JVM forms used explicit custody `@(seon.operator/connection "default")`; no `def`, evaluation submission, test execution, transaction, or lifecycle operation was performed on default. Anonymous bindings below are local to each probe. Initial `runtime_status` reported alive but health/flow unknown with “Read timed out”; this was reported immediately. JVM eval answered. One larger source/pull probe timed out at 20 seconds; a smaller query subsequently retrieved both sources (**agentSource2**). No alternate transport was used.

Default continued changing: each probe reports its own basis, from 536877302 through 536877479. Counts from different bases are not one snapshot. The working tree had extensive foreign edits, including contract, turn, runner, error, artifact, and schema owners; they were preserved. A later observed HEAD was `af278535cb7369726983f4ee60cf739abc5381bf`. Source line anchors describe the files inspected during this audit; the structural-contract probe explicitly depends on a concurrently edited, loaded helper and is diagnostic evidence only. The sole owned path is this note.

### Dependency ledger

| Mechanism | Dependency evidence | First-party use and consequence |
|---|---|---|
| Malli schema AST and canonical arities | `reference-code/malli/src/malli/core.cljc:2865` | `src/seon/program.cljc:589` calls Malli and derives stored AST/arity/binding facts. Do not reparse contract text into a second model. |
| Transaction function sees the writer's database | `reference-code/datahike/src/datahike/db/transaction.cljc:1152`; writer entry `reference-code/datahike/src/datahike/writer.cljc:393` | `src/seon/test/runner.clj:1290,1345`: result replacement and retractions belong in `record-tx`, invoked through `:db.fn/call`. |
| Since excludes the supplied time point | `reference-code/datahike/src/datahike/db.cljc:142,150` | Test freshness must compare an actual tested basis on its own branch; “some transaction happened later” does not prove a repaired definition was tested. |
| Existing static analysis and runtime batch projection | `src/seon/fn.clj:277,355,465,488,555,603` | Read the kondo-analysis consumer: it constructs exact form spans and filters resolved targets before projecting calls. Empty dangling refs are not a completeness proof. |
| One program declaration owner | `src/seon/program.cljc:43,697,754,858` | Source, identities, refs, exact replacement and deletion already have an owner. Candidate new attributes belong in its existing ownership projection. |

## Read the counts correctly

The inventory uses **distinct entity holders of an attribute**, not datom cardinality. For example, 5,328 holders of `:seon.fn/calls` includes test rows, and each holder may call many targets. “Installed” means a `:db/ident` exists; a declared value schema such as `:seon.fn.output/report` need not be installed as an attribute. An installed attribute with zero holders is different from an uninstalled value contract. Probe **census** was a whole-database attribute census; **inventory0–3** restricted the question to every declared key.

At basis 536877302: 4,621 fn identities; 3,850 source-bearing rows; 4,608 fn namespace refs; 1,019 specs/ASTs/arity links; 411 namespaces; 313 namespace source forms; two namespace stewards. Tests: 1,608 identities, 1,599 source/namespace holders, one result, zero subjects and zero pending subjects. At basis 536877309: seven evaluations carry accretion summaries/report blobs, zero gate-test refs, and zero test-run identities. Identity-only dependency rows and preserved tombstones are intentional shapes, not automatically defective missing source (`src/seon/fn.clj:1641,1893`; `src/seon/program.cljc:858`).

### What the model accounts actually establish

| Account | Observed need | Data/render consequence |
|---|---|---|
| Run 4, line 1 | It could not reconcile what `dir` resolved with its attempted database queries, or distinguish admitted definitions from private values. | Render the actual subject identity, source, contract, namespace and a runnable exact lookup; do not imply a raw private `def` is durable. Probe **agentSource2** provides the durable definition evidence. |
| Run 5, line 1 | A failing example corrected the wrong customer aggregation; a vector/lazy-sequence mismatch was hard to interpret; unchanged read refreshes looked like new work. | Keep expected/actual assertions and declared input shape in subject context. Test success must be persisted evidence, not the author's narrative. Input shapes already exist in arity facts (`src/seon/program.cljc:543,589`); result capture exists (`src/seon/test/runner.clj:147`). |
| Run 7, line 1 | It mistook “no example test gates…” for refusal, relied on private `example-rows`, and claimed completion from its own attempted operations. | Separate admission outcome, advisory, captured result, and persisted source. `gate-report` permits zero tests unless a real failure refuses installation (`src/seon/test/accretion.clj:230,261`). Its historical claim that `testing` was unavailable is not proof of current availability; current injections use `clojure.test :publics` (`src/seon/program.cljc:20`). |

These are accounts of what the model believed. In particular, run 7's claim to have sent a message is not a delivery fact. The existing issue [A plan step can be marked complete while its done-when is false](../../../seon/issues/a-plan-step-can-be-marked-complete-without-its-done-when-being-true.md), lines 12–27, records the independent empty-message query; this lane does not repeat that delivery audit. The saved test's dependence on private `example-rows` is independently confirmed by **agentSource2**, so exporting that test alone would not reconstruct a runnable fixture. [Fault resolution has no declared fact](../../../seon/issues/fault-resolution-has-no-declared-fact.md), lines 12–26, already records the related resolution gap; the regression association proposed here supplies positive repair evidence in the existing test family instead of a bare resolved flag.

## Context selectors and render coverage

Use these selectors as shared values in candidate query functions; the context function must diagnose an absent subject, rather than returning an empty successful task. Reverse calls need an additional query when the holder can be either a fn or a test.

```clojure
;; F: function subject
[:seon.fn/sym :seon.fn/source :seon.fn/doc :seon.fn/arglists
 :seon.fn/private? :seon.fn/macro? :seon.fn/spec
 {:seon.fn/ns [:seon.ns/name :seon.ns/source :seon.ns/doc
               {:seon.ns/requires [:seon.ns/name]}
               {:seon.ns/aliases [*]}
               {:seon.ns/refers [*]}
               {:seon.ns/imports [*]}
               {:seon.ns/steward [:seon.agent/id]}]}
 {:seon.fn/calls [:seon.fn/sym :seon.fn/doc :seon.fn/spec]}
 {:seon.fn/arities
  [:seon.fn.arity/order :seon.fn.arity/min :seon.fn.arity/max
   :seon.fn.arity/input :seon.fn.arity/output
   :seon.fn.arity/guard
   {:seon.fn.arity/return-schema [:seon.schema.shape/fingerprint
                                  :seon.schema.shape/form]}
   {:seon.fn.arity/arguments [* {:seon.fn.argument/binding [*]}
                              {:seon.fn.argument/schema
                               [:seon.schema.shape/fingerprint
                                :seon.schema.shape/form]}]}
   {:seon.fn.arity/input-refs [:seon.schema/key :seon.schema/form]}
   {:seon.fn.arity/output-refs [:seon.schema/key :seon.schema/form]}]}]

;; T: test subject and its latest evidence
[:seon.test/sym :seon.test/source :seon.test/usage
 :seon.test/pending-subject
 :seon.test/pass-count :seon.test/fail-count :seon.test/error-count
 :seon.test/failing-assertions :seon.test/failure-message
 :seon.test/run-at :seon.test/run-basis-t
 {:seon.test/ns [:seon.ns/name :seon.ns/source]}
 {:seon.test/subject [:seon.fn/sym :seon.fn/source :seon.fn/spec]}
 {:seon.fn/calls [:seon.fn/sym :seon.fn/source :seon.fn/spec]}]

;; E: actual error occurrence
[:seon.error/id :seon.error/signature :seon.error/at
 :seon.error/basis-t :seon.error/process :seon.error/kind
 :seon.error/op :seon.error/proc :seon.error/cid
 :seon.error/message :seon.instrument/fn
 :seon.error/data-edn :seon.error/data-blob :seon.error/capped?
 {:seon.error/agent [:seon.agent/id]}
 {:seon.error/run [:seon.turn/id
                   {:seon.turn/agent [:seon.agent/id]}]}
 {:seon.error/steward [:seon.agent/id]}]
```

The exact nested argument/binding expansion must use the declared argument schema; it is not necessary to pull the whole AST to teach a simple task. Input/output refs already supply canonical schema definitions (`resources/seon/schemas/seon.fn.argument.edn:1`, `seon.fn.binding.edn:1`; `src/seon/program.cljc:519`). The candidate fn/test context functions return the existing `:seon.fn/fn` and `:seon.test/test` shapes with optional accreted context keys, or a flat `:seon.error/value`; no closed task-kind envelope is needed.

| Entity/value in selectors | Existing pair | Missing / action |
|---|---|---|
| Fn (`:seon.fn/fn`) | Only legacy `:seon.render/form seon.render.ns/function-form` (`resources/seon/schemas/seon.fn.edn:1`; function `src/seon/render/ns.clj:834`). | No entity AI/HTML pair. Add it here, showing source, contract, related tests and exact success call. |
| Test (`:seon.test/test`, result) | No ordinary test pair (`resources/seon/schemas/seon.test.edn:1`). The not-runnable error has an error pair. | Add one test entity pair, including failure evidence and run provenance; result is attributes of the same entity. |
| Arity, argument, binding, AST | Data components, no entity pair in their schema resources. | Render within fn pair; do not add scalar/component task renderers. |
| Namespace | `seon.render.ns/render-ai` / `render-html` (`resources/seon/schemas/seon.ns.edn:10`). Alias/refer/import pairs also exist (`src/seon/render/ns.clj:303,324,348`). | Draft's “missing namespace renderer” is incorrect. Extend existing pair where needed. Current AI chooses documentation forms; HTML uses namespace contents (`src/seon/render/ns.clj:846,878`). |
| Referenced schema | Only schema-form, no entity AI/HTML pair (`resources/seon/schemas/seon.schema.edn:69`). | For MVP fn pair may print its declared shape; full standalone schema tasks need that existing schema's pair. |
| Error fact | `seon.error/render-ai` / `render-html` (`resources/seon/schemas/seon.error.edn:1`; `src/seon/error.clj:1395,1406` in later working tree). | Reuse. Resolve `:seon.instrument/fn` string to F separately; it is not a pull ref. |
| Turn / agent refs | Turn pair exists (`src/seon/turn.clj:1764,1851`); refs here select identities. | Full agent-context construction belongs to ordinary agent/turn flow; this audit does not certify its end-to-end delivery. |
| Problems aggregate | `seon.problems/ai-prose` / `html-report` (`resources/seon/schemas/seon.problems.edn:1`). | It is already derived; do not persist each aggregate. AI prose omits recurring-error details that HTML/log show (`src/seon/problems.clj:445,522,547`). |
| Accretion report | Refused-install error has `seon.test.accretion/render-ai` / `render-html`; ordinary report has no pair (`resources/seon/schemas/seon.test.accretion.edn:1`). | Show relevant stored evaluation fields through its existing evaluation context; no standalone task family. |
| Test run, output-path report, source publication | No ordinary entity pair in `seon.test.run.edn`, `seon.fn.output.edn`, `seon.source.edn`. | Embed run provenance in test pair, paths in fn context. Publication values need no separate MVP block. |
| Program / runner / cluster.source / export / artifact | Primarily function request/result/error schemas, not subject entities. | No subject pair required simply because their value keys are empty. |

## The seven task chains

Candidate public names below are proposals, not installed definitions. The executable local function bodies and exact results are in the probe appendix. `DB` below abbreviates existing `:seon.db/database-value`, `Fsym` existing `:seon.fn/sym`, `Tsym` existing `:seon.test/sym`. Each production boundary must return its declared value or `:seon.error/value` on a failed read; the compact successful queries below assume the supplied canonical database. These abbreviations are prose, not new schema keys.

### 1. Red test

| Chain stage | Facts, function and boundary |
|---|---|
| Input → | `:seon.test/sym` 1,608; latest pass/fail/error/run-at/run-basis each 1; source 1,599; failing assertions/message 0 (**inventory1**, basis 536877400). |
| Detector → | Candidate `seon.test/red`, contract `[:=> [:cat DB] [:or [:vector Tsym] :seon.error/value]]`: query positive fail OR error, normalize collection to vector. **red** ran it: `[]`. This proves no recorded red result in that snapshot; 1,607 tests have no recorded result and remain unknown. |
| Context → | Pull T from the returned test identity, then F for each subject/call target. Existing namespace pair; missing test/fn pair. Include failing assertions, current test source, current subject source and tested-program evidence, not only the failure message. Capture seam: `src/seon/test/runner.clj:147,475,1290`. |
| Success → | Candidate `seon.test/green-after?`, contract `[:=> [:cat DB Tsym :seon.db/basis-t] :boolean]`, body in **success**: requires existing source, positive pass, zero fail/error, stored basis at least requested basis. Existing Juniper test: old basis true, current basis false; absent subject false. This is a local lower-bound check, **not sufficient freshness across publication/branches**. Final `seon.test/verified?` below additionally requires the exact tested program fingerprint. |
| Missing → | Reuse all result attrs and `record-tx`. Persist one run identity plus tested program provenance and `:seon.test/run` ref (proposal below, `seon.test.run.edn` + `seon.test.edn`). Cost: one run row, O(tests executed) refs in the existing transaction; no new transaction per assertion. Freshness and recording-failure semantics require runner/source integration. |

### 2. Recurring fault signature

| Chain stage | Facts, function and boundary |
|---|---|
| Input → | At basis 536877345, seven signatures, five with at least two occurrences; largest count 3,224 (**faults**). Earlier census: 3,444 fault IDs/signatures/processes; 3,386 ops; 21 agent refs; one run ref; zero steward refs. Full later census in **inventory2** is at its own basis. |
| Detector → | Candidate `seon.error/recurring`, contract `[:=> [:cat DB [:int {:min 2}]] [:or [:vector [:tuple :seon.error/signature :pos-int]] :seon.error/value]]`: group error IDs by signature and filter count >= supplied threshold. Exact query/result in **faults**. Existing `seon.problems/error-signatures` already groups signatures (`src/seon/problems.clj:99`); expose/reuse that derivation instead of adding a stored occurrences counter. |
| Context → | Pull E for occurrences selected by signature (latest plus bounded examples in AI projection), join `:seon.instrument/fn` string to `:seon.fn/sym`, pull F, then reaching T. **faults** joined 3,422 fault rows to fn rows. `:seon.error/op` is a flow operation: observed `:step` 3,411 and `:seon.agent/turn-completion-backstop` 10. It is not a function ref. Reuse error/namespace pairs; fn/test pair missing. |
| Success → | Candidate `seon.error/regression-verified?`, contract `[:=> [:cat DB :seon.error/signature :seon.source/digest] :boolean]`: a test explicitly associated with this signature must exist and satisfy `seon.test/verified?` for the current tested-program digest. No such association is stored today. Merely finding no later error is not success. Signature hashes process + class/kind/frame (`src/seon/error.clj:258`), so restart also changes this identity. |
| Missing → | Candidate cardinality-many `:seon.test/error-signatures`, aliasing the existing signature value, in `resources/seon/schemas/seon.test.edn`, emitted from test metadata by `seon.fn/var-row` / runtime analysis and admitted by `seon.program`. Cost one ref-like string datom per regression/signature, no occurrence counter. Reuse existing run freshness changes. For fault turn attribution, no new attr: carry the actual known turn to existing `:seon.error/run` at the failure seam. |

**Turn attribution is a measured question, not a blanket defect.** The one linked fault is `7ec5ffc9-a329-44b1-9b0b-9ce722352f4f`, function `seon.turn/turn-completion-backstop-failure`, turn `958adc16c4b1`, agent root (**faults**). `commit-fault!` attributes only structurally tagged agent faults to their currently open turn; untagged cluster/render faults intentionally have no turn (`src/seon/cluster.clj:2370,2393`). `prepare` accepts turn ID and writes `:seon.error/run`; `commit-tx` omits nonexistent refs (`src/seon/error.clj:559,1229`). Thus “3,443 missing run refs” is not “3,443 turn-attribution bugs.” The commit-time open-turn query can lose an already closed turn; proving a particular lost link requires failure-time evidence, which this census does not supply. Existing stewardship join is fn → ns → steward (`src/seon/error.clj:1095`); zero stored stewards plus only two namespace stewards is not evidence that every fault lacks a resolvable fn.

### 3. Public function without a reaching test

| Chain stage | Facts, function and boundary |
|---|---|
| Input → | Fn `private?` 3,850 holders, calls 5,328 holders across functions/tests, test subjects 0; fn namespace refs present on both agent-installed functions (**agentRows**). |
| Detector → | Existing `seon.fn/functions-without-tests` (`src/seon/fn.clj:850`), candidate task entry may directly call it; contract uses DB → vector Fsym/error. **fnGaps**: 288, including Juniper `largest-customer` and root `largest`. Its rules use explicit subject and transitive calls (`:773`); `tests-reaching` / gate-set also handle pending-subject (`:807,824`). |
| Context → | F plus T from `tests-reaching`, reverse holder query with both fn/test identity attrs, and namespace test inventory when reach set is empty. Show that “no recorded reaching test” is evidence about this graph. Fn/test pair missing; namespace pair exists. |
| Success → | Candidate `seon.fn/tested?`, contract `[:=> [:cat DB Fsym :seon.source/digest] :boolean]`: subject still has a definition, at least one current reaching test exists, and that test satisfies `seon.test/verified?` at the supplied program digest. Reuse `tests-reaching`; zero tests returns false. Actual Juniper result is **false at the reachability step**, despite its recorded passing assertions. |
| Missing → | First repair/reconcile existing `:seon.fn/calls` and use existing `:seon.test/subject`, not a new test-link family. Analyzer seam `src/seon/fn.clj:325,355,465,555`; definition transaction seam `src/seon/turn.clj:1120,1375`. Cost O(actual call edges) in the existing admission/reanalysis transaction, one explicit subject ref per test. Then add common run freshness evidence and pairs. |

**Independent evidence of incomplete agent call analysis:** **agentSource2** contains two calls to `largest-customer`, while **agentRows** contains only `clojure.core/=` and `clojure.test/is` edges on that test and no subject. Its first assertion refers to private `example-rows`; source export must make that fixture reproducible. This proves a missing graph edge; it does not by itself identify which analyzer phase omitted it. Runtime resolution filters and synthesized namespace context are the owner to probe next, not a proven root cause (`src/seon/fn.clj:465–601`).

### 4. Public function without a complete contract

| Chain stage | Facts, function and boundary |
|---|---|
| Input → | Fn source/private/spec/AST/arity facts already exist. At initial census 1,019 spec/AST/arity holders, 1,165 arity components; later 1,168. Input-refs 904 then 907, output-refs 721 then 724; guard refs 0. These are contract structure, not completeness verdicts (**census**, **inventory0**). |
| Detector → | Candidate `seon.fn/without-contracts`, contract DB → vector Fsym/error, query source + explicit public + not macro + absent spec. **detectors**: 60 at basis 536877479. The earlier 66 included six macros (**fnGaps**); do not open six function-contract tasks from that difference. Test helper functions occur in the sample; scope them by actual public declaration/provenance, not a namespace-name exclusion. |
| Detector, present spec → | Candidate `seon.fn/contract-findings`, contract DB + Fsym → vector of the existing schema finding shape/error. Reuse the structural admission checker with the supplied canonical projection. **structural** ran the loaded helper over stored public specs: 205 unjustified positions on 179 functions. This is **not an authoritative count of complete-contract violations**: refs need canonical expansion, exemptions need the owning policy, and this helper was concurrently edited. Earlier lexical scan found 75 specs containing `:any`/`:some`; that count is discarded as a completeness detector. |
| Context → | F including AST/arity/argument and referenced schema definitions, plus error paths and reaching T. Fn/test pair missing; nested shape printed in fn pair. Example run 5 shows why shape/path evidence matters. |
| Success → | Candidate `seon.fn/contract-complete?`, contract DB + Fsym → boolean/error. Existing source/spec required, then the exact `seon.schema/assert-complete-contract!` policy on the canonical projection and `seon.program/contract-facts` arity bijection must accept; reaching regression must satisfy `tested?` when behavior changed. Do not equate nonempty spec string with completion. |
| Missing → | No new durable completeness boolean. Reuse specs and canonical schema definitions; expose total read-only findings from existing schema owner (`src/seon/schema.clj:1088`; `src/seon/schema/internal.cljc:22,114`; `src/seon/program.cljc:589`). Cost zero extra datoms for a verdict; ordinary source/spec/derived arity replacement per fix. Add the fn/test pairs and common test provenance only. |

### 5. Call to a missing function

| Chain stage | Facts, function and boundary |
|---|---|
| Input → | Calls target entity refs; fn identity stubs intentionally exist. At basis 536877479 candidate `:seon.fn/unresolved-calls` is not installed (**detectors**). |
| Detector → | Candidate `seon.fn/dangling-calls`, contract DB → vector [Fsym, entity-id]/error: query a caller's call ref with no target `:seon.fn/sym`. **fnGaps** returned `[]`; **detectors** returned `#{}`. This tests ref identity integrity only. Runtime analysis can omit unresolved target evidence before calls are written; identity stubs are also not proof of a callable implementation (`src/seon/fn.clj:465,555,1641`). |
| Context → | F for caller, exact source, namespace aliases/refers/imports, target identity/source/contract where available, the analyzer/resolver's actual diagnostic, and reaching T. Fn/test pair missing. Never produce a repair task merely because a dependency stub has no first-party source. |
| Success → | Candidate `seon.fn/calls-resolved?`, contract DB + Fsym + tested-program digest → boolean/error: current caller exists, analysis of that exact caller/program completed, no unresolved-call diagnostics remain, and its reaching resolution regression is verified. Empty diagnostics without evidence that analysis ran is unknown. The existing dangling query cannot prove this predicate today. |
| Missing → | If unresolved calls may survive admission, persist `:seon.fn/unresolved-calls` (set of qualified symbol strings) next to calls in `seon.fn.edn`, emitted at the analyzer/resolver seam that actually knows failure, plus an analysis identity/revision if it cannot be derived from the accepted program/run provenance. Retract these values on successful reanalysis through `seon.program` exact ownership. Cost O(unresolved targets), no duplicate resolved call graph. Prefer using already emitted typed analysis refusal/error evidence when admission rejects the form; a refused form is an evaluation task, not a missing durable fn. |

This class is not fully enumerable from current admitted call facts. A truthful function can return “unavailable: unresolved-call evidence was not retained,” rather than claiming zero missing functions. Do not add a name-based heuristic for external/core symbols.

### 6. Docstring example that fails

| Chain stage | Facts, function and boundary |
|---|---|
| Input → | Fn docs 1,624 holders. `:seon.test/usage` has five rows (**examples**); usage marks example tests, not arbitrary extracted doc snippets. Existing whole documentation regression is present but has no result attrs (**detectors**). |
| Detector → | Candidate `seon.test/doc-example-failures`, contract DB → vector Tsym/error. Today its only usable stored-test detector is red status of `my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context`: **success** returned `[]`, while **detectors** returned only its identity, so verdict is **unobserved**, not passing. No durable per-doc-example failure set can be queried today. |
| Context → | F with exact docstring and executable example selected by the existing documentation parser, T with assertion/error, namespace and fixture inputs. Current regression reads examples through `doc`, runs forms through real SCI plus canonical database fixture, and includes filesystem/web/flow fixture setup (`test/my/examples_test.clj:43,146–190`). It does not establish all public codebase docstrings were visited: selection is instruction toolkit namespaces, excluding internal functions. |
| Success → | Candidate `seon.test/doc-example-verified?`, contract DB + Fsym + tested-program digest → boolean: a current example test whose subject is this function and whose captured excerpt equals the currently selected doc example exists and is verified. A green unrelated usage test is insufficient. No such per-example provenance is written today. |
| Missing → | First split reporting at the existing example runner seam so each documented subject's executable example has an ordinary deterministic test identity/source and existing `:seon.test/subject`, with proposed `:seon.test/doc-example` string in `seon.test.edn` for the exact extracted excerpt. Keep fixture work in the canonical test owner; no default execution in a detector. Cost one test declaration + one excerpt + ordinary result per subject example, batched with the run. If docs have multiple separately reported snippets, derive test identity from subject and ordinal using `seon.id/id`; do not create a separate example family. |

### 7. Dead function

| Chain stage | Facts, function and boundary |
|---|---|
| Input → | Function source, reverse calls and reaching tests. Keyword-use facts are literal keyword occurrence, not read/write dependency facts (`src/seon/fn.clj:875`). |
| Detector → | Candidate `seon.fn/unreferenced-definitions`, contract DB → vector Fsym/error. **fnGaps** starts with functions-without-tests and retains source-bearing functions without reverse call holders: 219. It includes `my.agent/done`, `my.background/await`, `my.edit/exact!`. The count is a review candidate set, **not 219 dead functions**. |
| Context → | F, all reverse holder identities and their source, namespace interface, function-value references if recorded, and reaching T. Existing all-functions-callable rule means an externally callable public function can legitimately have no internal callers. Fn/test pair missing. |
| Success → | Do not generate an automatic deletion task from this detector. A bounded candidate task can succeed by adding a meaningful verified reaching test; an explicitly chosen retirement can succeed only when a baseline proves the subject had source, its same identity survives with source absent, no remaining required references exist, and fresh canonical tests/publication prove dependents still work. A query on an absent identity is never proof of a completed retirement. |
| Missing → | For stronger static usage context, proposed `:seon.fn/references` refs next to `:seon.fn/calls` in `seon.fn.edn`, emitted from analyzer var usages including function values, carried on both fn/test declarations. Cost O(distinct non-call target refs) in the existing declaration transaction. Dynamic callers still defeat a complete “dead” proof; keep that limitation as a task constraint, not a guessed `:dead?` attribute. Existing `seon.program/deletion-row` (`:858`) already preserves identity. |

## Subject-bound success: what can be written now and what cannot

The appendix contains the exact executable lower-bound success query and negative absent-subject probe. The following is the proposed final ordinary function contract/body, **not executed against default because the proposed attributes do not exist**. It requires an exact supplied fingerprint of the program being certified; that fingerprint includes tested source and contracts/schemas and must be derived from the acquired test environment, not from a later database read.

```clojure
(defn verified?
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.test/sym :seon.source/digest]
    [:or :boolean :seon.error/value]]}
  [db test-symbol tested-program-digest]
  (let [result
   (seon.db/q
    '[:find ?test .
      :in $ ?symbol ?digest
      :where
      [?test :seon.test/sym ?symbol]
      [?test :seon.test/source _]
      [?test :seon.test/pass-count ?passes]
      [(pos? ?passes)]
      [?test :seon.test/fail-count 0]
      [?test :seon.test/error-count 0]
      [?test :seon.test/run ?run]
      [?run :seon.test.run/id _]
      [?run :seon.test.run/program-digest ?digest]]
    db test-symbol tested-program-digest)]
    (if (and (map? result) (:seon.error/kind result))
      result
      (boolean result))))
```

A missing result/identity/source/ref returns false. A typed query refusal returns an error, never completion. This is the same error-as-value boundary required of all composed success functions. A caller must require `true?`, never merely truthiness.

The composed functions are queries over the same world, with no new scheduler or durable problem:
- `seon.fn/tested?`: require subject source; derive `seon.fn/tests-reaching`; some reaching Tsym satisfies `verified?`.
- `seon.error/regression-verified?`: query tests whose proposed `:seon.test/error-signatures` includes the supplied signature; some such test satisfies `verified?`.
- `seon.test/doc-example-verified?`: derive the current example through the existing documentation owner, query subject + proposed `doc-example` equality, then `verified?`.
- `seon.fn/contract-complete?`: source/spec presence plus existing canonical contract validation; its behavioral obligation additionally calls `tested?`.
- `seon.fn/calls-resolved?`: require analysis evidence for the supplied program and caller, then no unresolved-call findings, then `tested?`.
- Retirement predicate: compare subject source in explicit baseline/current database values, preserve identical fn identity, inspect remaining recorded references, and require positive current dependent test evidence. With no complete reference evidence, it is an explicitly reviewed retirement, not a total dead-code detector.

Each function should expose the failed condition as ordinary query data in its companion context function; no persisted success flag is necessary. Final task acceptance evaluates the function, never a model's explanation of its work.

### Concrete predicate bodies for the other chains

These are **proposed read-only bodies**, not installed functions or successful live probes. They complement the executed detectors above. The three queries below use the proposed run attributes. Their owners/contracts are:
- `seon.fn/tested?`: `[:=> [:cat :seon.db/database-value :seon.fn/sym :seon.source/digest] [:or :boolean :seon.error/value]]`.
- `seon.error/regression-verified?`: `[:=> [:cat :seon.db/database-value :seon.error/signature :seon.source/digest] [:or :boolean :seon.error/value]]`.
- `seon.test/doc-example-verified?`: `[:=> [:cat :seon.db/database-value :seon.fn/sym :string :seon.source/digest] [:or :boolean :seon.error/value]]`. The excerpt is the current documentation owner's extracted example, not model-authored text.

This ordinary Datalog rule is shown once to keep the specifications readable; implementation should keep it in the test owner beside `verified?`, rather than copy a second result policy into each detector.

```clojure
[[(verified-test ?test ?digest)
  [?test :seon.test/source _]
  [?test :seon.test/pass-count ?passes]
  [(pos? ?passes)]
  [?test :seon.test/fail-count 0]
  [?test :seon.test/error-count 0]
  [?test :seon.test/run ?run]
  [?run :seon.test.run/id _]
  [?run :seon.test.run/program-digest ?digest]]]
```

Bind that rule vector as `rules`. For each query, return a typed error unchanged; otherwise return `(boolean result)`. The current fn→tests derivation is `(seon.fn/tests-reaching db function-symbol)`, bound as `gate-symbols`; diagnose its error before executing the query.

```clojure
;; seon.fn/tested?
(seon.db/q
 '[:find ?test .
   :in $ % ?subject [?gate ...] ?digest
   :where
   [?fn :seon.fn/sym ?subject]
   [?fn :seon.fn/source _]
   [?test :seon.test/sym ?gate]
   (verified-test ?test ?digest)]
 db rules function-symbol gate-symbols current-program-digest)

;; seon.error/regression-verified?
;; Subject existence is checked in the supplied fault database when results
;; are on another branch; this query uses the explicit result database.
(seon.db/q
 '[:find ?test .
   :in $ % ?signature ?digest
   :where
   [?test :seon.test/error-signatures ?signature]
   (verified-test ?test ?digest)]
 result-db rules signature current-program-digest)

;; seon.test/doc-example-verified?
(seon.db/q
 '[:find ?test .
   :in $ % ?subject ?excerpt ?digest
   :where
   [?fn :seon.fn/sym ?subject]
   [?fn :seon.fn/source _]
   [?fn :seon.fn/doc _]
   [?test :seon.test/subject ?fn]
   [?test :seon.test/doc-example ?excerpt]
   (verified-test ?test ?digest)]
 db rules function-symbol current-example current-program-digest)
```

For recurrence across default/current-src, give the success function **both explicit database values**, or supply the already resolved error subject plus result database in one open request. Do not look up another cluster implicitly. The one-DB contract above describes the case where subject and evidence are in that database; the cross-branch request must declare its two worlds before implementation. The signature association is proposed historical evidence, not a claim that a regression proves every possible cause sharing that signature repaired.

For missing calls, an empty `unresolved-calls` set needs positive analysis evidence. If the admitted declaration's source provenance does not already prove the matching analysis completed, the concrete additional proposed attr is `:seon.fn/analysis-program-digest :seon.source/digest` in `seon.fn.edn`, written by completed canonical analysis and replaced with declaration facts. One scalar datom per analyzed function. Then `calls-resolved?` uses this query **and** the verified reaching-test predicate:

```clojure
(seon.db/q
 '[:find ?fn .
   :in $ ?subject ?digest
   :where
   [?fn :seon.fn/sym ?subject]
   [?fn :seon.fn/source _]
   [?fn :seon.fn/analysis-program-digest ?digest]
   (not [?fn :seon.fn/unresolved-calls])]
 db function-symbol current-program-digest)
```

The cheaper alternative is no extra analysis scalar: accept only programs from the existing canonical publication gate, which rejects unresolved names; the successful run's exact program fingerprint then supplies the positive evidence. Prefer that for the MVP. Do not manufacture a detector of rejected, non-admitted definitions by stamping every accepted fn.

For contract completeness, the concrete success call must reuse the prepared validation request that the schema projection already builds (`src/seon/schema.clj:2232`). This is an extraction of existing code into a total query function, not a new saved contract verdict. Proposed `seon.schema/contract-request` input is explicit projection + subject identity and output is the existing validation request fields (identity, definition, canonical forms/admissions, predicate bindings, compile options); it must select the same subject/spec in DB. Given that request and the subject's existing `seon.program/contract-facts` request, the success body is:

```clojure
;; Source and spec were pulled from the actual subject, not supplied by agent.
;; Both requests derive from the same explicit database/projection.
(try
  (seon.schema/assert-complete-contract! validation-request)
  (seon.program/contract-facts contract-facts-request)
  true
  (catch clojure.lang.ExceptionInfo error
    (merge {:seon.error/kind :seon.fn/signature-refused
            :seon.error/message (.getMessage error)}
           (ex-data error))))
```

The boundary also converts unexpected core failures using the existing diagnostic owner. This is **policy acceptance**, not “no advisories”; the context must render returned non-terminal advisories if the task promises stricter completion. A source/spec presence query alone remains insufficient. Do not use the concurrently edited structural helper's 179-function count as the acceptance policy.

For a deliberately approved retirement, the following candidate `seon.fn/definition-retired?` has contract `[:=> [:cat :seon.db/database-value :seon.db/database-value :seon.fn/sym] [:or :boolean :seon.error/value]]` and checks **only the definition transition**:

```clojure
(seon.db/q
 '[:find ?after .
   :in $before $after ?symbol
   :where
   [$before ?before :seon.fn/sym ?symbol]
   [$before ?before :seon.fn/source _]
   [$after ?after :seon.fn/sym ?symbol]
   [(= ?before ?after)]
   (not [$after ?after :seon.fn/source])]
 baseline-db current-db function-symbol)
```

It requires related database history where entity IDs are stable. Final retirement success additionally requires no remaining recorded calls/references and a fresh dependent regression run for the current program. If dynamic entry points are not ruled out, return that evidence gap; there is no sound general `dead?` predicate in the facts audited here. Adding the subject's meaningful passing test remains the immediately constructible success path for this candidate class.

In all these calls, `current-program-digest` is derived from the **current program being certified**, never copied unchanged from a task opened before edits. Capture/reuse the acquired program's existing identity/projection inputs once per run; do not hash the entire program separately for every test. Content equality across file export needs a canonical declaration fingerprint (the same identities, source, specs and schema dependencies), not comparison of a file-byte digest to a database-entity-ID digest. That canonical fingerprint function belongs beside `seon.program` declaration normalization, using the existing identity/hash owner.

## Where test results go today; change to default current-src recording

| Entry / transition | Current behavior and evidence | Necessary change |
|---|---|---|
| `seon.test/run` | Uses supplied cluster connection, captures basis before execution and commits results through runner (`src/seon/test.clj:7`). Default's one result is consistent with this path; the census alone does not prove historical caller identity. | Keep agent-local evidence in that agent's branch. If exporting/accepting into source, carry the exact tested definition fingerprint; don't silently relabel the branch/basis. |
| `seon.test.runner/commit-results!` | Has an explicit connection; no implicit destination. Calls `record-tx` inside writer and returns db-after pulls (`src/seon/test/runner.clj:1345`). | Keep one writer seam. Extend completion and recorded evidence, not a second recorder. |
| `record-tx` | Overwrites latest stats on test identity, clears stale failure attrs atomically, creates namespace identity for a new result-only row; can therefore yield test identities without source. Captures no run entity/link (`:1290–1365`). | Add immutable run row and test→run ref in same transaction. Preserve capture-time program identity. Validate it against the program being certified at the writer/publication boundary. |
| Ordinary `bin/test` persistence | Bare/changed and bulk modes with no explicit namespace or result cluster use source root for persistent results; explicit namespaces and platform-only runs do not (`bin/test:235–249,738`). | Owner's “results to current-src by default” must define these selections explicitly. Recommended: every completed canonical gate records its selected tests, including explicit namespace/platform, unless caller explicitly selects another result destination. Recording partial selection does not certify the whole suite. |
| Persistent branch | Both in-process and generated JVM paths use `:test-results`, forked from `:current-src`; both derive `run-basis-t` from destination DB at recording time (`src/seon/test/runner.clj:1405–1471`). | Replace these duplicate destination paths with one source-publication-owned operation. Do not merely change `:test-results` to `:current-src`: that branch is advanced by expected-head publication and deliberately has no retained live connection (`src/seon/cluster/source.clj:1,273,375`). |
| Run identity | Schema already declares `:seon.test.run/id`, `at`, `git-sha` (`resources/seon/schemas/seon.test.run.edn:1`); coordinator constructs them, adapters discard them when forming completion (`src/seon/test/runner.clj:1394,1420,1458,2600`). No run rows on default (**inventory1**). | Actually write these existing attributes. Add run's `program-digest`, captured `basis-t`, captured `branch` (existing source branch value shape), and optional captured `source-commit-id` in the same existing family; add `:seon.test/run` ref. Source commit alone is not enough for an edited agent program. |
| Full publication | `publish!` populates a scratch from base; incremental `upsert!` branches exact current commit (`src/seon/cluster/source.clj:273,375`). | Current-src result facts must survive a full rebuild. Preserve immutable run evidence and test identity/latest result attrs deliberately while replacing only program-owned attributes. Keep old run fingerprints: stale results remain historical evidence, not green for a changed program. |
| Recording failure | `finish-run!` prints recording failure but exit status is based on test summary (`src/seon/test/runner.clj:2414–2451`). | A task requiring durable evidence cannot succeed on that exit code alone. Either gate completion includes recording failure or acceptance checks actual committed run/test facts. |

**Proposed schema delta, minimum shared across chains:** in `seon.test.edn`, `:seon.test/run :seon.db/ref`; in `seon.test.run.edn`, reuse existing id/at/git-sha and add `program-digest :seon.source/digest`, `basis-t :seon.db/basis-t`, `branch :seon.source/branch`, optional `source-commit-id :seon.source/commit-id`. The fingerprint derives from the canonical complete tested program, including tests and contract dependencies, with one derivation shared by execution and acceptance. No per-function “last tested” mirror. Branch/basis is diagnostic custody; program fingerprint is cross-branch content equality.

Estimated storage: one run identity with 6–7 scalar facts, one ref per result, existing result datoms unchanged. History already keeps replacement transactions; no new result entity family. Estimated engineering scope: runner completion/capture, source publication preservation, and one canonical regression covering record→rebuild→changed program→stale result. This is cross-owner work, presented for decision rather than implemented in this research lane.

## Exactly what exists for admitted definitions → files

| Direction / owner | What exists | What does not follow from it |
|---|---|---|
| Files → program | `seon.fn/build-artifact`, `build-manifest`, indexing/reconciliation; exact form text and namespace forms (`src/seon/fn.clj:126,1182,1278,1977`). File artifact carries path/digest/rows/identities (`resources/seon/schemas/seon.fn.file.edn:1`). | File path/order/spans are not durable fn/test attributes in the owned declaration shapes (`src/seon/program.cljc:43`). |
| Admitted fn/test → database | `seon.program/declaration-row`, runtime analysis and turn row transaction retain source, contract, ns and selected links (`src/seon/program.cljc:754`; `src/seon/fn.clj:603`; `src/seon/turn.clj:1120`). **agentSource2** proves actual stored fn/test text. | Private `def` values needed by tests are not a restorable file model. Exact authored source alone does not supply fixtures, namespace imports or schemas. |
| Source publication | `seon.cluster.source/snapshot` reads file bytes/digests; publish/upsert advances a database branch (`src/seon/cluster/source.clj:97,273,375`). | No reverse `.clj` writer. |
| Standalone artifact | Build packages current-source initialization rows; `seon.artifact/install-initialization-pages!` loads resource `seon/artifact/current-src.edn` into an empty store, then main boots cluster (`build.clj:61`; `src/seon/artifact.clj:9,53,76`). | `seon.artifact` is a standalone boot entry, not an admitted fn/test exporter. |
| Export | `seon.cluster.export/export!` copies/reidentifies the held Datahike store into a parent directory using its existing clone/retransaction/atomic move path (`src/seon/cluster/export.clj:135,189,228,293`; `resources/seon/schemas/seon.export.edn:1`). | Store export does not write source or test namespaces. |
| Filesystem/edit capabilities | `my.fs/write!`, `my.edit/form!`, `exact!`, `lines!` provide bounded writes and digest-aware edits (`src/my/fs.clj:57`; `src/my/edit.clj:35,59,82`). | They do not select durable program rows, assemble a dependency-complete namespace, or certify exported definitions. Reuse these operations after pure assembly; don't build a second generic file writer. |

**Existing end-to-end source-file export: absent in the inspected owners.** There are both durable source strings and writing primitives; the missing operation joins them under a tested-program identity and explicit file destinations.

Recommended MVP restriction: write a **new, explicitly named source namespace and test namespace into two new explicit paths**, with a declared/exported set of fn/test rows and reproducible fixture source. Require existing schema/dependency closure or include separately selected schema artifacts before certification. Refuse unresolved/private dependencies. The Juniper source as-is uses private `example-rows` and domain schema refs, so it is evidence of why assembly validation is necessary, not a ready-to-export artifact (**agentSource2**).

Candidate pure `seon.program/source-files`: input an open `:seon.export/source-request` containing explicit target paths, namespace identities and selected durable identities plus DB; output a vector of existing `:seon.fn.file/path` plus exact `:seon.program/source` bytes (new ordinary `:seon.export/source-file` value schema) or error. Compose namespace requires/refers/imports from stored namespace facts and requested target mapping; rewrite names with Clojure forms, not regex. Validate duplicate identities, external/private references and file collisions. Preserve exact definition source where no identity rewrite is needed.

Candidate `seon.cluster.export/source!`: same pure result plus scoped environment and expected destination state → bounded filesystem effect results/error, through the existing fs/edit owner. Keep its request/result value keys in `seon.export.edn`; no `seon.source-file` durable family for the MVP. The normal file→program analyzer must reproduce the intended admitted definitions, and the ordinary isolated gate must rerun the test from exported files. Only then does the success function certify the exported program.

A general edit-in-place exporter is a separate scope: original file identity, declaration positions/order, comments, non-function forms and conditional `.cljc` structure are not reconstructible from current fn/test rows alone (`src/seon/program.cljc:43`; `resources/seon/schemas/seon.fn.file.edn:1`). If needed later, persist the existing file-artifact path/digest model and explicit declaration→file relation, rather than guessing paths from namespace names. No such extra storage is required for two explicit new files.

## One page: the system as inputs and outputs

| Producer → | Attribute/value → | Consumer → task/result |
|---|---|---|
| File analyzer / runtime analyzed declaration (`fn.clj:355,603`) | `seon.fn`: sym, ns, source, doc, spec, calls, keywords, privacy, macro | Fn detectors and fn context; admission and source-file assembly |
| Malli + `program/contract-facts` (`program.cljc:589`) | `seon.fn.arity`: arguments, min/max, input/output, schema refs, guard; AST/binding components | Contract validation; argument teaching; generative checks |
| `fn/output-path-report` (`fn.clj:1063`) | `seon.fn.output` report values from calls + external-sink/projection-boundary | Fn context for output-path defects; no stored report mirror |
| Namespace analysis (`fn.clj:355`) | `seon.ns`: source, requires, aliases/refers/imports; asserted steward | Namespace render pair, call resolution, source assembly, error stewardship |
| Static/runtime test declaration (`fn.clj:325,355,555`) | `seon.test`: sym, ns, source, calls, subject, usage | Reaching tests; detector; test context; test execution |
| Real `runner/run-var!` → `record-tx` (`runner.clj:489,1290`) | `seon.test`: latest pass/fail/error/assertions/time/basis | Red detector; success predicate, currently lacking program freshness |
| Coordinator (`runner.clj:2600`) | `seon.test.run`: id/at/git-sha in returned value, **not committed today** | Proposed immutable run provenance and test→run link |
| Runner capture/completion (`runner.clj:475,1345`) | `seon.test.runner`: transient captured results/completion/summary | One result transaction; explicit source publication integration |
| Candidate SCI + test.check/gates (`accretion.clj:131,261`) | `seon.test.accretion`: report values; seven stored evaluation summary/blob holders | Admission refusal/advisory; candidate correction; not latest durable test results |
| Flow fault → `error/prepare`, `commit-tx` (`error.clj:494,1129`) | `seon.error`: occurrence, signature, evidence, provenance; instrument/fn | Recurrence query → error/F/T context → associated regression |
| Existing `problems/problems` (`problems.clj:356`) | `seon.problems`: derived aggregate value | Existing report pairs; candidates without new problem records |
| `program/declaration-row` / `exact-replacement-tx` (`program.cljc:754,840`) | `seon.program` row/identity/ownership values | Admission, reconciliation and candidate pure source-file assembly |
| `cluster.source/snapshot`, publish/upsert (`cluster/source.clj:97,273,375`) | `seon.source`: digest/build/activation; branch/commit values | Program acquisition, exact tested-program comparison, source publication |
| Publication seal/refusals (`cluster/source.clj:218`) | `seon.cluster.source` request failure values | Operator diagnostics; no separate task entity |
| Store export (`cluster/export.clj:293`) | `seon.export` parent-dir/path/request values | Portable store; proposed source export adds a distinct operation here |
| Packaged initialization (`artifact.clj:53`) | `seon.artifact` failure values; resource program rows | Empty-store initialization; not reverse source export |
| Proposed detector fn → existing entity pair → ordinary agent creation/turn | Existing subject identity, exact context forms and exact success function call | Agent edits/admission → captured test run → persisted result → pure source assembly → filesystem effects → isolated gate from files → success query true |

The last row is the missing integration, not a claim of a demonstrated autonomous loop. No paid agent session was launched in this audit.

## Probe appendix — exact read-only forms and returned values

Each form ran via MCP JVM mode in session `data-audit-a`, root `/Users/sean/src/seon`, cluster `default`. The EDN below is the returned `seon.dev.mcp/value` string, decoded from the JSON tool envelope. Query setup failures (an uninstalled guessed `:seon.fn/name` and a double-colon keyword spelling) were corrected before the successful probes below; neither yielded data evidence. The timed-out `agentSource` form is superseded by `agentSource2`.

### census

```clojure
(let [db @(seon.operator/connection "default") families #{"seon.fn" "seon.fn.arity" "seon.ns" "seon.test" "seon.error" "seon.source"} rows (seon.db/q '[:find ?a (count ?e) :where [?e ?a _]] db)] (pr-str {:audit/basis (seon.db/basis-t db) :audit/counts (vec (sort (filter #(families (namespace (first %))) rows)))}))
```

```edn
#:audit{:basis 536877302, :counts [[:seon.error/agent 21] [:seon.error/at 3444] [:seon.error/basis-t 3444] [:seon.error/capped? 3444] [:seon.error/cid 3376] [:seon.error/class 332] [:seon.error/data-blob 3387] [:seon.error/data-edn 3444] [:seon.error/data-size 3444] [:seon.error/dropped-fault-count 178] [:seon.error/dropped-fault-digest 178] [:seon.error/id 3444] [:seon.error/kind 3572] [:seon.error/message 3444] [:seon.error/op 3386] [:seon.error/proc 3387] [:seon.error/process 3444] [:seon.error/refusal 11] [:seon.error/refusal-shape 11] [:seon.error/run 1] [:seon.error/signature 3444] [:seon.error/throwable-class 3387] [:seon.fn/arglists 3850] [:seon.fn/arities 1019] [:seon.fn/ast 1019] [:seon.fn/calls 5328] [:seon.fn/doc 1624] [:seon.fn/doc-order 4] [:seon.fn/external-sink 6] [:seon.fn/internal? 2] [:seon.fn/keywords 4134] [:seon.fn/macro? 6] [:seon.fn/ns 4608] [:seon.fn/private? 3850] [:seon.fn/projection-boundary 6] [:seon.fn/source 3850] [:seon.fn/spec 1019] [:seon.fn/sym 4621] [:seon.fn/workload 10] [:seon.fn.arity/argument-count 1165] [:seon.fn.arity/arguments 1106] [:seon.fn.arity/arity 1165] [:seon.fn.arity/guard 9] [:seon.fn.arity/guard-schema 9] [:seon.fn.arity/input 1165] [:seon.fn.arity/input-refs 904] [:seon.fn.arity/max 1155] [:seon.fn.arity/min 1165] [:seon.fn.arity/order 1165] [:seon.fn.arity/output 1165] [:seon.fn.arity/output-refs 721] [:seon.fn.arity/return-schema 1165] [:seon.ns/aliases 300] [:seon.ns/doc 214] [:seon.ns/imports 101] [:seon.ns/name 411] [:seon.ns/refers 203] [:seon.ns/requires 305] [:seon.ns/source 313] [:seon.ns/steward 2] [:seon.source/activation-closure 1] [:seon.source/built-at 1] [:seon.source/commit-id 1] [:seon.source/digest 1] [:seon.test/error-count 1] [:seon.test/fail-count 1] [:seon.test/ns 1599] [:seon.test/pass-count 1] [:seon.test/run-at 1] [:seon.test/run-basis-t 1] [:seon.test/source 1599] [:seon.test/sym 1608] [:seon.test/usage 5]]}
```

### remainingCounts

```clojure
(let [db @(seon.operator/connection "default") families #{"seon.fn.output" "seon.test.run" "seon.test.runner" "seon.test.accretion" "seon.problems" "seon.program" "seon.cluster.source" "seon.export" "seon.artifact" "seon.instrument"} rows (seon.db/q '[:find ?a (count ?e) :where [?e ?a _]] db)] (pr-str {:audit/basis (seon.db/basis-t db) :audit/counts (vec (sort (filter #(families (namespace (first %))) rows)))}))
```

```edn
#:audit{:basis 536877309, :counts [[:seon.instrument/fn 3391] [:seon.test.accretion/case-count 7] [:seon.test.accretion/executed-count 7] [:seon.test.accretion/gate-fail-count 7] [:seon.test.accretion/gate-pass-count 7] [:seon.test.accretion/gate-test-count 7] [:seon.test.accretion/report-blob 7] [:seon.test.accretion/report-size 7] [:seon.test.accretion/seed 7] [:seon.test.accretion/status 7]]}
```

### red

```clojure
(let [db @(seon.operator/connection "default")] (pr-str {:audit/basis (seon.db/basis-t db) :audit/red (seon.db/q '[:find [?s ...] :where [?e :seon.test/sym ?s] (or-join [?e] (and [?e :seon.test/fail-count ?n] [(pos? ?n)]) (and [?e :seon.test/error-count ?n] [(pos? ?n)]))] db) :audit/results (seon.db/q '[:find [(pull ?e [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count :seon.test/run-basis-t]) ...] :where [?e :seon.test/run-at]] db)}))
```

```edn
#:audit{:basis 536877309, :red [], :results [#:seon.test{:sym "my.agents.juniper/largest-customer-test", :pass-count 2, :fail-count 0, :error-count 0, :run-basis-t 536876445}]}
```

### fnGaps

```clojure
(let [db @(seon.operator/connection "default") untested (seon.fn/functions-without-tests db) no-spec (seon.db/q '[:find [?s ...] :where [?f :seon.fn/sym ?s] [?f :seon.fn/private? false] [?f :seon.fn/source] (not [?f :seon.fn/spec])] db) dangling (seon.db/q '[:find ?caller ?target :where [?caller :seon.fn/calls ?target] (not [?target :seon.fn/sym])] db) dead (seon.db/q '[:find [?s ...] :in $ [?s ...] :where [?f :seon.fn/sym ?s] [?f :seon.fn/source] (not [_ :seon.fn/calls ?f])] db untested)] (pr-str {:audit/basis (seon.db/basis-t db) :audit/untested [(count untested) (vec (take 10 untested))] :audit/no-spec [(count no-spec) (vec (take 10 (sort no-spec)))] :audit/dangling (vec dangling) :audit/dead-candidates [(count dead) (vec (take 10 (sort dead)))]}))
```

```edn
#:audit{:basis 536877309, :untested [288 ["my.agent/done" "my.agent/identity" "my.agent/settings" "my.agent/settings!" "my.agents.juniper/largest-customer" "my.agents.root/largest" "my.background/await" "my.background/background" "my.background/poll" "my.edit/exact!"]], :no-spec [66 ["my.background/background" "my.test/run" "seon.bootstrap/dir" "seon.bootstrap/doc" "seon.bootstrap/help" "seon.cluster.agent-test/fixture-evaluate" "seon.cluster.source-test/activation" "seon.cluster.source-test/populate!" "seon.cluster.source-test/populate-blocked!" "seon.cluster.source-test/populate-fails!"]], :dangling [], :dead-candidates [219 ["my.agent/done" "my.agent/identity" "my.agent/settings" "my.agent/settings!" "my.agents.juniper/largest-customer" "my.agents.root/largest" "my.background/await" "my.background/background" "my.background/poll" "my.edit/exact!"]]}
```

### agentRows

```clojure
(let [db @(seon.operator/connection "default")] (pr-str {:audit/basis (seon.db/basis-t db) :audit/fns (seon.db/q '[:find [(pull ?e [:seon.fn/sym :seon.fn/private? {:seon.fn/ns [:seon.ns/name]} {:seon.fn/calls [:seon.fn/sym]} {:seon.fn/_calls [:seon.fn/sym :seon.test/sym]}]) ...] :where [?e :seon.fn/sym] [?e :seon.schema.admission/source :agent]] db) :audit/tests (seon.db/q '[:find [(pull ?e [:seon.test/sym {:seon.test/ns [:seon.ns/name]} {:seon.test/subject [:seon.fn/sym]} {:seon.fn/calls [:seon.fn/sym]} :seon.test/run-basis-t]) ...] :where [?e :seon.test/sym] [?e :seon.schema.admission/source :agent]] db)}))
```

```edn
#:audit{:basis 536877312, :fns [#:seon.fn{:sym "my.agents.root/largest", :private? false, :ns #:seon.ns{:name my.agents.root}, :calls [#:seon.fn{:sym "clojure.core/>"} #:seon.fn{:sym "clojure.core/empty?"} #:seon.fn{:sym "clojure.core/fn"} #:seon.fn{:sym "clojure.core/if"} #:seon.fn{:sym "clojure.core/reduce"}]} #:seon.fn{:sym "my.agents.juniper/largest-customer", :private? false, :ns #:seon.ns{:name my.agents.juniper}, :calls [#:seon.fn{:sym "clojure.core/apply"} #:seon.fn{:sym "clojure.core/fn"} #:seon.fn{:sym "clojure.core/fnil"} #:seon.fn{:sym "clojure.core/if"} #:seon.fn{:sym "clojure.core/let"} #:seon.fn{:sym "clojure.core/reduce"} #:seon.fn{:sym "clojure.core/seq"} #:seon.fn{:sym "clojure.core/update"}]}], :tests [{:seon.test/sym "my.agents.juniper/largest-customer-test", :seon.test/run-basis-t 536876445, :seon.test/ns #:seon.ns{:name my.agents.juniper}, :seon.fn/calls [#:seon.fn{:sym "clojure.core/="} #:seon.fn{:sym "clojure.test/is"}]}]}
```

### faults

```clojure
(let [db @(seon.operator/connection "default") counts (seon.db/q '[:find ?sig (count ?e) :where [?e :seon.error/id] [?e :seon.error/signature ?sig]] db) recurring (vec (sort-by (comp - second) (filter #(>= (second %) 2) counts))) top-sig (ffirst recurring)] (pr-str {:audit/basis (seon.db/basis-t db) :audit/signatures (count counts) :audit/recurring-count (count recurring) :audit/recurring recurring :audit/ops (seon.db/q '[:find ?op (count ?e) :where [?e :seon.error/id] [?e :seon.error/op ?op]] db) :audit/function-links (count (seon.db/q '[:find ?e :where [?e :seon.error/id] [?e :seon.instrument/fn ?s] [?f :seon.fn/sym ?s]] db)) :audit/run-row (seon.db/q '[:find [(pull ?e [:seon.error/id :seon.error/kind :seon.instrument/fn {:seon.error/run [:seon.turn/id {:seon.turn/agent [:seon.agent/id {:seon.agent/namespace [:seon.ns/name]}]}]}]) ...] :where [?e :seon.error/id] [?e :seon.error/run]] db)}))
```

```edn
#:audit{:basis 536877345, :signatures 7, :recurring-count 5, :recurring [["1b8b87f8addda81d0b6a81ab36556192a1e0f0bbc207b875006e07f598742fe1" 3224] ["a5fa1ea0795b6141ba82f2205c8651f499a33de6474d1a1380302ce02f84965e" 178] ["fdefb565b330da8083d0b14ba50d05c4363c560d11ac5c949dce093abb5b2ed1" 57] ["c602af63d801187feea263d06871ba464692dfcb9d7ef534eacc787b1329790a" 10] ["e7f372b291503c12f65e061a4841a0035a473efd9f0c275a0ab1622db7a7452f" 9]], :ops [[:step 3411] [:seon.agent/turn-completion-backstop 10]], :function-links 3422, :run-row [{:seon.error/id "7ec5ffc9-a329-44b1-9b0b-9ce722352f4f", :seon.error/kind :seon.agent/turn-completion-backstop, :seon.instrument/fn "seon.turn/turn-completion-backstop-failure", :seon.error/run #:seon.turn{:id "958adc16c4b1", :agent #:seon.agent{:id "root", :namespace #:seon.ns{:name my.agents.root}}}}]}
```

### agentSource2

```clojure
(let [db @(seon.operator/connection "default")] (pr-str {:audit/basis (seon.db/basis-t db) :audit/agent-test (seon.db/q '[:find ?source . :where [?t :seon.test/sym "my.agents.juniper/largest-customer-test"] [?t :seon.test/source ?source]] db) :audit/agent-fn (seon.db/q '[:find ?source . :where [?f :seon.fn/sym "my.agents.juniper/largest-customer"] [?f :seon.fn/source ?source]] db)}))
```

```edn
#:audit{:basis 536877454, :agent-test "(deftest largest-customer-test\n  (is (= {:customer \"Ada\" :total 115}\n         (largest-customer example-rows)))\n  (is (= {:customer \"Bea\" :total 100}\n         (largest-customer [{:example/order \"b1\" :example/customer \"Bea\" :example/amount 100}]))))", :agent-fn "(defn largest-customer\n  {:malli/schema [:=> [:cat [:vector :example/order-row]]\n                  [:or [:map [:customer :example/customer] [:total :example/amount]]\n                   :seon.error/value]]}\n  [rows]\n  (let [totals (reduce (fn [acc row]\n                         (update acc (:example/customer row) (fnil + 0) (:example/amount row)))\n                       {}\n                       rows)]\n    (if (seq totals)\n      (let [[customer total] (apply max-key val totals)]\n        {:customer customer :total total})\n      {:seon.error/kind :seon.error/no-orders\n       :seon.error/message \"No orders supplied; there is no largest customer.\"})))"}
```

### structural

```clojure
(let [db @(seon.operator/connection "default") rows (seon.db/q '[:find ?s ?spec :where [?f :seon.fn/sym ?s] [?f :seon.fn/private? false] [?f :seon.fn/spec ?spec]] db) findings (vec (mapcat (fn [[s spec]] (map #(assoc % :seon.fn/sym s) (seon.schema.internal/permissive-positions {:seon.schema/definition (clojure.edn/read-string spec)}))) rows)) unjustified (filterv #(not (:seon.schema/justified? %)) findings)] (pr-str {:audit/basis (seon.db/basis-t db) :audit/positions (count findings) :audit/unjustified (count unjustified) :audit/functions (count (distinct (map :seon.fn/sym unjustified))) :audit/sample (vec (take 6 unjustified))}))
```

```edn
#:audit{:basis 536877423, :positions 205, :unjustified 205, :functions 179, :sample [{:seon.schema/path [1 1 1], :seon.schema/definition :any, :seon.schema.advisory/kind :undefined, :seon.schema/justified? false, :seon.fn/sym "seon.test.accretion/generatable?"} {:seon.schema/path [2 1 1], :seon.schema/definition :any, :seon.schema.advisory/kind :undefined, :seon.schema/justified? false, :seon.fn/sym "seon.test.accretion/generatable?"} {:seon.schema/path [2 1 3], :seon.schema/definition [:* :seon.schema/value], :seon.schema.advisory/kind :value-tail, :seon.schema/justified? false, :seon.fn/sym "seon.db/diff"} {:seon.schema/path [1 1], :seon.schema/definition :seon.schema/value, :seon.schema.advisory/kind :bare-value, :seon.schema/justified? false, :seon.fn/sym "seon.print/value-at"} {:seon.schema/path [2 1], :seon.schema/definition :some, :seon.schema.advisory/kind :undefined, :seon.schema/justified? false, :seon.fn/sym "seon.turn/refresh-tx"} {:seon.schema/path [1 2], :seon.schema/definition :seon.schema/value, :seon.schema.advisory/kind :bare-value, :seon.schema/justified? false, :seon.fn/sym "seon.schema.internal/map-identity-entry-key"}]}
```

### examples

```clojure
(let [db @(seon.operator/connection "default")] (pr-str {:audit/basis (seon.db/basis-t db) :audit/usage-tests (seon.db/q '[:find [?s ...] :where [?e :seon.test/sym ?s] [?e :seon.test/usage true]] db) :audit/example-regression (seon.db/pull db [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count :seon.test/run-basis-t] [:seon.test/sym "my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context"]) :audit/subject-rows (seon.db/q '[:find (count ?e) . :where [?e :seon.test/subject]] db)}))
```

```edn
#:audit{:basis 536877364, :usage-tests ["my.message-test/read-pulls-one-admitted-message-row" "my.examples-fixture/arithmetic" "seon.db-test/diff-replays-one-read-by-derived-identity" "my.turn-test/the-lifecycle-walkthrough-is-executable-data" "my.message-test/inbox-lists-this-agents-messages-newest-last"], :example-regression #:seon.test{:sym "my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context"}, :subject-rows nil}
```

### success

```clojure
(let [db @(seon.operator/connection "default")
 red-tests (fn [db] (seon.db/q '[:find [?s ...] :where [?t :seon.test/sym ?s] (or-join [?t] (and [?t :seon.test/fail-count ?n] [(pos? ?n)]) (and [?t :seon.test/error-count ?n] [(pos? ?n)]))] db))
 green-after? (fn [db sym basis] (boolean (seon.db/q '[:find ?t . :in $ ?s ?basis :where [?t :seon.test/sym ?s] [?t :seon.test/source _] [?t :seon.test/pass-count ?p] [(pos? ?p)] [?t :seon.test/fail-count 0] [?t :seon.test/error-count 0] [?t :seon.test/run-basis-t ?b] [(>= ?b ?basis)]] db sym basis)))
 documented-example-reds (fn [db] (filterv #(= % "my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context") (red-tests db)))]
(pr-str {:audit/basis (seon.db/basis-t db) :audit/red (red-tests db) :audit/doc-regression-red (documented-example-reds db)
:audit/green-old-basis (green-after? db "my.agents.juniper/largest-customer-test" 536876445)
:audit/green-current-basis (green-after? db "my.agents.juniper/largest-customer-test" (seon.db/basis-t db))
:audit/absent-subject-green (green-after? db "audit.definitely.absent/test" 0)}))
```

```edn
#:audit{:basis 536877472, :red [], :doc-regression-red [], :green-old-basis true, :green-current-basis false, :absent-subject-green false}
```

### detectors

```clojure
(let [db @(seon.operator/connection "default")
 missing-contracts (fn [db] (seon.db/q '[:find [?s ...] :where [?f :seon.fn/sym ?s] [?f :seon.fn/source _] [?f :seon.fn/private? false] (not [?f :seon.fn/macro? true]) (not [?f :seon.fn/spec])] db))
 undefined-targets (fn [db] (seon.db/q '[:find ?caller ?target :where [?f :seon.fn/sym ?caller] [?f :seon.fn/calls ?target] (not [?target :seon.fn/sym])] db))
 example-result (fn [db sym] (seon.db/q '[:find (pull ?t [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count :seon.test/run-basis-t]) . :in $ ?s :where [?t :seon.test/sym ?s]] db sym))]
(pr-str {:audit/basis (seon.db/basis-t db)
:audit/missing-contract-count (count (missing-contracts db))
:audit/missing-contract-sample (take 8 (sort (missing-contracts db)))
:audit/undefined-targets (undefined-targets db)
:audit/example-result (example-result db "my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context")
:audit/unresolved-call-facts-installed? (boolean (seon.db/q '[:find ?e . :where [?e :db/ident :seon.fn/unresolved-calls]] db))}))
```

```edn
#:audit{:basis 536877479, :missing-contract-count 60, :missing-contract-sample ("seon.cluster.agent-test/fixture-evaluate" "seon.cluster.source-test/activation" "seon.cluster.source-test/populate!" "seon.cluster.source-test/populate-blocked!" "seon.cluster.source-test/populate-fails!" "seon.cluster.source-test/populate-from-data!" "seon.cluster.store-transact-test/refusing-call" "seon.cluster.turn-test/fake-evaluate"), :undefined-targets #{}, :example-result #:seon.test{:sym "my.examples-test/public-docstring-examples-run-in-the-canonical-agent-context"}, :unresolved-call-facts-installed? false}
```

## Complete declared-key inventory

Read every assigned schema resource end to end. The following is the full returned inventory, reformatted as tables; `false/0` is a declaration not installed as a database attribute, not a missing row. All schema-resource links point to the files whose whole maps were read. Counts are distinct holders. The inventory's attribute names are literal data from those resources, not inferred roles.

### inventory0

```clojure
(let [db @(seon.operator/connection "default") attrs [:seon.fn/analysis-failed :seon.fn/analysis-failed-error :seon.fn/arglists :seon.fn/arglists-override? :seon.fn/arities :seon.fn/ast :seon.fn/calls :seon.fn/capability-graph-malformed :seon.fn/capability-graph-malformed-error :seon.fn/capability-rule :seon.fn/doc :seon.fn/doc-order :seon.fn/duplicate-program-identity :seon.fn/duplicate-program-identity-error :seon.fn/existing-program-entity :seon.fn/external-sink :seon.fn/fn :seon.fn/index-phase :seon.fn/index-refused :seon.fn/index-refused-error :seon.fn/index-request :seon.fn/index-transaction-refused :seon.fn/index-transaction-refused-error :seon.fn/internal? :seon.fn/keywords :seon.fn/macro? :seon.fn/manifest-absent :seon.fn/manifest-absent-error :seon.fn/missing-population :seon.fn/ns :seon.fn/population-incomplete :seon.fn/population-incomplete-error :seon.fn/private? :seon.fn/projection-boundary :seon.fn/resource :seon.fn/roots :seon.fn/schema-declaration-invalid :seon.fn/schema-declaration-invalid-error :seon.fn/scratch-not-fresh :seon.fn/scratch-not-fresh-error :seon.fn/signature-refused :seon.fn/signature-refused-error :seon.fn/source :seon.fn/source-checkout-required :seon.fn/source-checkout-required-error :seon.fn/source-file-invalid :seon.fn/source-file-invalid-error :seon.fn/source-span-absent :seon.fn/source-span-absent-error :seon.fn/spec :seon.fn/sym :seon.fn/workload :seon.fn.arity/argument-count :seon.fn.arity/arguments :seon.fn.arity/arity :seon.fn.arity/guard :seon.fn.arity/guard-refs :seon.fn.arity/guard-schema :seon.fn.arity/input :seon.fn.arity/input-refs :seon.fn.arity/max :seon.fn.arity/min :seon.fn.arity/order :seon.fn.arity/output :seon.fn.arity/output-refs :seon.fn.arity/return-schema :seon.fn.arity/row :seon.ns/aliases :seon.ns/doc :seon.ns/imports :seon.ns/name :seon.ns/ns :seon.ns/refers :seon.ns/requires :seon.ns/source :seon.ns/steward] counts (into {} (seon.db/q '[:find ?a (count ?e) :where [?e ?a _]] db))] (pr-str {:audit/basis (seon.db/basis-t db) :audit/attributes (mapv (fn [a] [a (boolean (get (:schema db) a)) (get counts a 0)]) attrs)}))
```

Basis 536877400. Returned rows: 76.

| Attribute | Installed? | Holders |
|---|---|---:|
| `:seon.fn/analysis-failed` | false | 0 |
| `:seon.fn/analysis-failed-error` | false | 0 |
| `:seon.fn/arglists` | true | 3850 |
| `:seon.fn/arglists-override?` | true | 0 |
| `:seon.fn/arities` | true | 1019 |
| `:seon.fn/ast` | true | 1019 |
| `:seon.fn/calls` | true | 5328 |
| `:seon.fn/capability-graph-malformed` | false | 0 |
| `:seon.fn/capability-graph-malformed-error` | false | 0 |
| `:seon.fn/capability-rule` | false | 0 |
| `:seon.fn/doc` | true | 1624 |
| `:seon.fn/doc-order` | true | 4 |
| `:seon.fn/duplicate-program-identity` | false | 0 |
| `:seon.fn/duplicate-program-identity-error` | false | 0 |
| `:seon.fn/existing-program-entity` | false | 0 |
| `:seon.fn/external-sink` | true | 6 |
| `:seon.fn/fn` | false | 0 |
| `:seon.fn/index-phase` | false | 0 |
| `:seon.fn/index-refused` | false | 0 |
| `:seon.fn/index-refused-error` | false | 0 |
| `:seon.fn/index-request` | false | 0 |
| `:seon.fn/index-transaction-refused` | false | 0 |
| `:seon.fn/index-transaction-refused-error` | false | 0 |
| `:seon.fn/internal?` | true | 2 |
| `:seon.fn/keywords` | true | 4134 |
| `:seon.fn/macro?` | true | 6 |
| `:seon.fn/manifest-absent` | false | 0 |
| `:seon.fn/manifest-absent-error` | false | 0 |
| `:seon.fn/missing-population` | false | 0 |
| `:seon.fn/ns` | true | 4608 |
| `:seon.fn/population-incomplete` | false | 0 |
| `:seon.fn/population-incomplete-error` | false | 0 |
| `:seon.fn/private?` | true | 3850 |
| `:seon.fn/projection-boundary` | true | 6 |
| `:seon.fn/resource` | false | 0 |
| `:seon.fn/roots` | false | 0 |
| `:seon.fn/schema-declaration-invalid` | false | 0 |
| `:seon.fn/schema-declaration-invalid-error` | false | 0 |
| `:seon.fn/scratch-not-fresh` | false | 0 |
| `:seon.fn/scratch-not-fresh-error` | false | 0 |
| `:seon.fn/signature-refused` | false | 0 |
| `:seon.fn/signature-refused-error` | false | 0 |
| `:seon.fn/source` | true | 3850 |
| `:seon.fn/source-checkout-required` | false | 0 |
| `:seon.fn/source-checkout-required-error` | false | 0 |
| `:seon.fn/source-file-invalid` | false | 0 |
| `:seon.fn/source-file-invalid-error` | false | 0 |
| `:seon.fn/source-span-absent` | false | 0 |
| `:seon.fn/source-span-absent-error` | false | 0 |
| `:seon.fn/spec` | true | 1019 |
| `:seon.fn/sym` | true | 4621 |
| `:seon.fn/workload` | true | 10 |
| `:seon.fn.arity/argument-count` | true | 1168 |
| `:seon.fn.arity/arguments` | true | 1109 |
| `:seon.fn.arity/arity` | true | 1168 |
| `:seon.fn.arity/guard` | true | 9 |
| `:seon.fn.arity/guard-refs` | true | 0 |
| `:seon.fn.arity/guard-schema` | true | 9 |
| `:seon.fn.arity/input` | true | 1168 |
| `:seon.fn.arity/input-refs` | true | 907 |
| `:seon.fn.arity/max` | true | 1158 |
| `:seon.fn.arity/min` | true | 1168 |
| `:seon.fn.arity/order` | true | 1168 |
| `:seon.fn.arity/output` | true | 1168 |
| `:seon.fn.arity/output-refs` | true | 724 |
| `:seon.fn.arity/return-schema` | true | 1168 |
| `:seon.fn.arity/row` | false | 0 |
| `:seon.ns/aliases` | true | 300 |
| `:seon.ns/doc` | true | 214 |
| `:seon.ns/imports` | true | 101 |
| `:seon.ns/name` | true | 411 |
| `:seon.ns/ns` | false | 0 |
| `:seon.ns/refers` | true | 203 |
| `:seon.ns/requires` | true | 305 |
| `:seon.ns/source` | true | 313 |
| `:seon.ns/steward` | true | 2 |

### inventory1

```clojure
(let [db @(seon.operator/connection "default") attrs [:seon.test/error-count :seon.test/fail-count :seon.test/failing-assertions :seon.test/failure-identity :seon.test/failure-message :seon.test/not-runnable :seon.test/not-runnable-error :seon.test/ns :seon.test/pass-count :seon.test/pending-subject :seon.test/result :seon.test/results :seon.test/run-at :seon.test/run-basis-t :seon.test/source :seon.test/subject :seon.test/sym :seon.test/test :seon.test/usage :seon.test/var :seon.test.run/at :seon.test.run/git-sha :seon.test.run/id :seon.test.run/run :seon.test.runner/captured-result :seon.test.runner/captured-results :seon.test.runner/completion :seon.test.runner/default-cluster-refused :seon.test.runner/default-cluster-refused-error :seon.test.runner/error-count :seon.test.runner/fail-count :seon.test.runner/invalid-long-reason :seon.test.runner/invalid-long-reason-error :seon.test.runner/invalid-marker-reason :seon.test.runner/invalid-marker-reason-error :seon.test.runner/invalid-selection-mode :seon.test.runner/invalid-selection-mode-error :seon.test.runner/invalid-silence-seconds :seon.test.runner/invalid-silence-seconds-error :seon.test.runner/long-reason :seon.test.runner/long-test-ns-hook :seon.test.runner/long-test-ns-hook-error :seon.test.runner/namespaces :seon.test.runner/pass-count :seon.test.runner/process-tree-exit-backstop :seon.test.runner/process-tree-exit-backstop-error :seon.test.runner/record-request :seon.test.runner/record-tx :seon.test.runner/run-request :seon.test.runner/run-result :seon.test.runner/selection-mode :seon.test.runner/silence-seconds :seon.test.runner/summary :seon.test.runner/test-count :seon.test.runner/unknown-worker-command :seon.test.runner/unknown-worker-command-error :seon.test.runner/unresolved-test-var :seon.test.runner/unresolved-test-var-error :seon.test.runner/worker-launch-failure :seon.test.runner/worker-launch-failure-error :seon.test.accretion/actual :seon.test.accretion/advisories :seon.test.accretion/auto-check :seon.test.accretion/auto-check-request :seon.test.accretion/auto-check-result :seon.test.accretion/candidate-auto-check-request :seon.test.accretion/candidate-context-request :seon.test.accretion/candidate-ctx :seon.test.accretion/candidate-request :seon.test.accretion/candidate-result :seon.test.accretion/capabilities :seon.test.accretion/case-count :seon.test.accretion/evaluation :seon.test.accretion/executed-count :seon.test.accretion/expected :seon.test.accretion/expected-actual :seon.test.accretion/explanation :seon.test.accretion/failure :seon.test.accretion/failure-group :seon.test.accretion/failure-groups :seon.test.accretion/failure-shape :seon.test.accretion/failure-source :seon.test.accretion/failures :seon.test.accretion/gate-fail-count :seon.test.accretion/gate-failure :seon.test.accretion/gate-pass-count :seon.test.accretion/gate-report :seon.test.accretion/gate-report-request :seon.test.accretion/gate-set :seon.test.accretion/gate-test-count :seon.test.accretion/gate-tests :seon.test.accretion/install-refused :seon.test.accretion/install-refused-error :seon.test.accretion/install? :seon.test.accretion/orientation :seon.test.accretion/pass? :seon.test.accretion/report-blob :seon.test.accretion/report-edn :seon.test.accretion/report-size :seon.test.accretion/results :seon.test.accretion/seed :seon.test.accretion/skip-reason :seon.test.accretion/status :seon.test.accretion/test-count :seon.test.accretion/test-fail-count :seon.test.accretion/test-pass-count] counts (into {} (seon.db/q '[:find ?a (count ?e) :where [?e ?a _]] db))] (pr-str {:audit/basis (seon.db/basis-t db) :audit/attributes (mapv (fn [a] [a (boolean (get (:schema db) a)) (get counts a 0)]) attrs)}))
```

Basis 536877400. Returned rows: 106.

| Attribute | Installed? | Holders |
|---|---|---:|
| `:seon.test/error-count` | true | 1 |
| `:seon.test/fail-count` | true | 1 |
| `:seon.test/failing-assertions` | true | 0 |
| `:seon.test/failure-identity` | false | 0 |
| `:seon.test/failure-message` | true | 0 |
| `:seon.test/not-runnable` | false | 0 |
| `:seon.test/not-runnable-error` | false | 0 |
| `:seon.test/ns` | true | 1599 |
| `:seon.test/pass-count` | true | 1 |
| `:seon.test/pending-subject` | true | 0 |
| `:seon.test/result` | false | 0 |
| `:seon.test/results` | false | 0 |
| `:seon.test/run-at` | true | 1 |
| `:seon.test/run-basis-t` | true | 1 |
| `:seon.test/source` | true | 1599 |
| `:seon.test/subject` | true | 0 |
| `:seon.test/sym` | true | 1608 |
| `:seon.test/test` | false | 0 |
| `:seon.test/usage` | true | 5 |
| `:seon.test/var` | false | 0 |
| `:seon.test.run/at` | true | 0 |
| `:seon.test.run/git-sha` | true | 0 |
| `:seon.test.run/id` | true | 0 |
| `:seon.test.run/run` | false | 0 |
| `:seon.test.runner/captured-result` | false | 0 |
| `:seon.test.runner/captured-results` | false | 0 |
| `:seon.test.runner/completion` | false | 0 |
| `:seon.test.runner/default-cluster-refused` | false | 0 |
| `:seon.test.runner/default-cluster-refused-error` | false | 0 |
| `:seon.test.runner/error-count` | false | 0 |
| `:seon.test.runner/fail-count` | false | 0 |
| `:seon.test.runner/invalid-long-reason` | false | 0 |
| `:seon.test.runner/invalid-long-reason-error` | false | 0 |
| `:seon.test.runner/invalid-marker-reason` | false | 0 |
| `:seon.test.runner/invalid-marker-reason-error` | false | 0 |
| `:seon.test.runner/invalid-selection-mode` | false | 0 |
| `:seon.test.runner/invalid-selection-mode-error` | false | 0 |
| `:seon.test.runner/invalid-silence-seconds` | false | 0 |
| `:seon.test.runner/invalid-silence-seconds-error` | false | 0 |
| `:seon.test.runner/long-reason` | false | 0 |
| `:seon.test.runner/long-test-ns-hook` | false | 0 |
| `:seon.test.runner/long-test-ns-hook-error` | false | 0 |
| `:seon.test.runner/namespaces` | false | 0 |
| `:seon.test.runner/pass-count` | false | 0 |
| `:seon.test.runner/process-tree-exit-backstop` | false | 0 |
| `:seon.test.runner/process-tree-exit-backstop-error` | false | 0 |
| `:seon.test.runner/record-request` | false | 0 |
| `:seon.test.runner/record-tx` | false | 0 |
| `:seon.test.runner/run-request` | false | 0 |
| `:seon.test.runner/run-result` | false | 0 |
| `:seon.test.runner/selection-mode` | false | 0 |
| `:seon.test.runner/silence-seconds` | false | 0 |
| `:seon.test.runner/summary` | false | 0 |
| `:seon.test.runner/test-count` | false | 0 |
| `:seon.test.runner/unknown-worker-command` | false | 0 |
| `:seon.test.runner/unknown-worker-command-error` | false | 0 |
| `:seon.test.runner/unresolved-test-var` | false | 0 |
| `:seon.test.runner/unresolved-test-var-error` | false | 0 |
| `:seon.test.runner/worker-launch-failure` | false | 0 |
| `:seon.test.runner/worker-launch-failure-error` | false | 0 |
| `:seon.test.accretion/actual` | false | 0 |
| `:seon.test.accretion/advisories` | false | 0 |
| `:seon.test.accretion/auto-check` | false | 0 |
| `:seon.test.accretion/auto-check-request` | false | 0 |
| `:seon.test.accretion/auto-check-result` | false | 0 |
| `:seon.test.accretion/candidate-auto-check-request` | false | 0 |
| `:seon.test.accretion/candidate-context-request` | false | 0 |
| `:seon.test.accretion/candidate-ctx` | false | 0 |
| `:seon.test.accretion/candidate-request` | false | 0 |
| `:seon.test.accretion/candidate-result` | false | 0 |
| `:seon.test.accretion/capabilities` | false | 0 |
| `:seon.test.accretion/case-count` | true | 7 |
| `:seon.test.accretion/evaluation` | false | 0 |
| `:seon.test.accretion/executed-count` | true | 7 |
| `:seon.test.accretion/expected` | false | 0 |
| `:seon.test.accretion/expected-actual` | false | 0 |
| `:seon.test.accretion/explanation` | false | 0 |
| `:seon.test.accretion/failure` | false | 0 |
| `:seon.test.accretion/failure-group` | false | 0 |
| `:seon.test.accretion/failure-groups` | false | 0 |
| `:seon.test.accretion/failure-shape` | false | 0 |
| `:seon.test.accretion/failure-source` | false | 0 |
| `:seon.test.accretion/failures` | false | 0 |
| `:seon.test.accretion/gate-fail-count` | true | 7 |
| `:seon.test.accretion/gate-failure` | false | 0 |
| `:seon.test.accretion/gate-pass-count` | true | 7 |
| `:seon.test.accretion/gate-report` | false | 0 |
| `:seon.test.accretion/gate-report-request` | false | 0 |
| `:seon.test.accretion/gate-set` | false | 0 |
| `:seon.test.accretion/gate-test-count` | true | 7 |
| `:seon.test.accretion/gate-tests` | true | 0 |
| `:seon.test.accretion/install-refused` | false | 0 |
| `:seon.test.accretion/install-refused-error` | false | 0 |
| `:seon.test.accretion/install?` | false | 0 |
| `:seon.test.accretion/orientation` | false | 0 |
| `:seon.test.accretion/pass?` | false | 0 |
| `:seon.test.accretion/report-blob` | true | 7 |
| `:seon.test.accretion/report-edn` | true | 0 |
| `:seon.test.accretion/report-size` | true | 7 |
| `:seon.test.accretion/results` | false | 0 |
| `:seon.test.accretion/seed` | true | 7 |
| `:seon.test.accretion/skip-reason` | false | 0 |
| `:seon.test.accretion/status` | true | 7 |
| `:seon.test.accretion/test-count` | false | 0 |
| `:seon.test.accretion/test-fail-count` | false | 0 |
| `:seon.test.accretion/test-pass-count` | false | 0 |

### inventory2

```clojure
(let [db @(seon.operator/connection "default") attrs [:seon.error/agent :seon.error/at :seon.error/basis-t :seon.error/capped? :seon.error/cid :seon.error/class :seon.error/commit-tx-request :seon.error/data :seon.error/data-blob :seon.error/data-content :seon.error/data-edn :seon.error/data-size :seon.error/doc :seon.error/dropped-fault-count :seon.error/dropped-fault-digest :seon.error/evidence :seon.error/fact :seon.error/id :seon.error/kind :seon.error/message :seon.error/normalize-request :seon.error/notice :seon.error/notice-request :seon.error/notification :seon.error/notification-limit :seon.error/occurrence :seon.error/occurrences :seon.error/op :seon.error/prepare-request :seon.error/prepared :seon.error/proc :seon.error/process :seon.error/reason :seon.error/refusal :seon.error/refusal-shape :seon.error/refusal-value :seon.error/run :seon.error/signature :seon.error/source :seon.error/steward :seon.error/steward-request :seon.error/throwable-class :seon.error/unclassified :seon.error/unclassified-error :seon.error/value :seon.problems/author :seon.problems/deferred-agent :seon.problems/error-signature :seon.problems/errored-receipt :seon.problems/evaluation-failed :seon.problems/evaluation-failed-error :seon.problems/failed-run :seon.problems/form-problem :seon.problems/form-problem-request :seon.problems/id :seon.problems/missing-model :seon.problems/occurrences :seon.problems/problems :seon.problems/request :seon.problems/stale-var :seon.problems/unbound-var :seon.problems/unbound-var-error :seon.problems/unowned-namespace] counts (into {} (seon.db/q '[:find ?a (count ?e) :where [?e ?a _]] db))] (pr-str {:audit/basis (seon.db/basis-t db) :audit/attributes (mapv (fn [a] [a (boolean (get (:schema db) a)) (get counts a 0)]) attrs)}))
```

Basis 536877410. Returned rows: 63.

| Attribute | Installed? | Holders |
|---|---|---:|
| `:seon.error/agent` | true | 21 |
| `:seon.error/at` | true | 3534 |
| `:seon.error/basis-t` | true | 3534 |
| `:seon.error/capped?` | true | 3534 |
| `:seon.error/cid` | true | 3465 |
| `:seon.error/class` | true | 332 |
| `:seon.error/commit-tx-request` | false | 0 |
| `:seon.error/data` | false | 0 |
| `:seon.error/data-blob` | true | 3476 |
| `:seon.error/data-content` | false | 0 |
| `:seon.error/data-edn` | true | 3534 |
| `:seon.error/data-size` | true | 3534 |
| `:seon.error/doc` | false | 0 |
| `:seon.error/dropped-fault-count` | true | 178 |
| `:seon.error/dropped-fault-digest` | true | 178 |
| `:seon.error/evidence` | false | 0 |
| `:seon.error/fact` | false | 0 |
| `:seon.error/id` | true | 3534 |
| `:seon.error/kind` | true | 3662 |
| `:seon.error/message` | true | 3534 |
| `:seon.error/normalize-request` | false | 0 |
| `:seon.error/notice` | false | 0 |
| `:seon.error/notice-request` | false | 0 |
| `:seon.error/notification` | false | 0 |
| `:seon.error/notification-limit` | false | 0 |
| `:seon.error/occurrence` | false | 0 |
| `:seon.error/occurrences` | false | 0 |
| `:seon.error/op` | true | 3475 |
| `:seon.error/prepare-request` | false | 0 |
| `:seon.error/prepared` | false | 0 |
| `:seon.error/proc` | true | 3476 |
| `:seon.error/process` | true | 3534 |
| `:seon.error/reason` | false | 0 |
| `:seon.error/refusal` | true | 11 |
| `:seon.error/refusal-shape` | true | 11 |
| `:seon.error/refusal-value` | false | 0 |
| `:seon.error/run` | true | 1 |
| `:seon.error/signature` | true | 3534 |
| `:seon.error/source` | false | 0 |
| `:seon.error/steward` | true | 0 |
| `:seon.error/steward-request` | false | 0 |
| `:seon.error/throwable-class` | true | 3476 |
| `:seon.error/unclassified` | false | 0 |
| `:seon.error/unclassified-error` | false | 0 |
| `:seon.error/value` | false | 0 |
| `:seon.problems/author` | false | 0 |
| `:seon.problems/deferred-agent` | false | 0 |
| `:seon.problems/error-signature` | false | 0 |
| `:seon.problems/errored-receipt` | false | 0 |
| `:seon.problems/evaluation-failed` | false | 0 |
| `:seon.problems/evaluation-failed-error` | false | 0 |
| `:seon.problems/failed-run` | false | 0 |
| `:seon.problems/form-problem` | false | 0 |
| `:seon.problems/form-problem-request` | false | 0 |
| `:seon.problems/id` | true | 0 |
| `:seon.problems/missing-model` | false | 0 |
| `:seon.problems/occurrences` | false | 0 |
| `:seon.problems/problems` | false | 0 |
| `:seon.problems/request` | false | 0 |
| `:seon.problems/stale-var` | false | 0 |
| `:seon.problems/unbound-var` | false | 0 |
| `:seon.problems/unbound-var-error` | false | 0 |
| `:seon.problems/unowned-namespace` | false | 0 |

### inventory3

```clojure
(let [db @(seon.operator/connection "default") attrs [:seon.program/declaration-refused :seon.program/declaration-refused-error :seon.program/declaration-row :seon.program/delete-identities :seon.program/deletion-row :seon.program/identity :seon.program/identity-attribute :seon.program/ns :seon.program/owned-attributes :seon.program/row :seon.program/rows :seon.program/schema-row-properties :seon.program/shape :seon.program/source :seon.program/source-attribute :seon.source/activation :seon.source/activation-closure :seon.source/branch :seon.source/built-at :seon.source/built? :seon.source/commit-id :seon.source/current :seon.source/database :seon.source/digest :seon.source/digest-request :seon.source/expected-commit-id :seon.source/file-digests :seon.source/path :seon.source/populate :seon.source/populate-request :seon.source/population :seon.source/previous-database :seon.source/publish-request :seon.source/published :seon.source/roots :seon.source/snapshot :seon.source/upsert-request :seon.source/upsert-row :seon.source/upsert-rows :seon.cluster.source/invalid-source-seal :seon.cluster.source/invalid-source-seal-error :seon.cluster.source/populate-unresolvable :seon.cluster.source/populate-unresolvable-error :seon.cluster.source/publish-readback-failed :seon.cluster.source/publish-readback-failed-error :seon.cluster.source/refused :seon.cluster.source/refused-error :seon.cluster.source/root-absent :seon.cluster.source/root-absent-error :seon.cluster.source/rule :seon.cluster.source/stale-publication :seon.cluster.source/stale-publication-error :seon.cluster.source/unsafe-incremental-rows :seon.cluster.source/unsafe-incremental-rows-error :seon.fn.output/ai-paths :seon.fn.output/bypasses :seon.fn.output/classification :seon.fn.output/codec-paths :seon.fn.output/counterexamples :seon.fn.output/external-sink :seon.fn.output/first-bypass :seon.fn.output/html-paths :seon.fn.output/path :seon.fn.output/path-report :seon.fn.output/paths :seon.fn.output/projected :seon.fn.output/report :seon.fn.output/report-artifact :seon.fn.output/required-projection :seon.fn.output/sink :seon.fn.output/sinks :seon.fn.output/source :seon.fn.output/totals :seon.fn.output/unresolved :seon.export/parent-dir :seon.export/path :seon.export/request :seon.artifact/refused :seon.artifact/refused-error] counts (into {} (seon.db/q '[:find ?a (count ?e) :where [?e ?a _]] db))] (pr-str {:audit/basis (seon.db/basis-t db) :audit/attributes (mapv (fn [a] [a (boolean (get (:schema db) a)) (get counts a 0)]) attrs)}))
```

Basis 536877414. Returned rows: 79.

| Attribute | Installed? | Holders |
|---|---|---:|
| `:seon.program/declaration-refused` | false | 0 |
| `:seon.program/declaration-refused-error` | false | 0 |
| `:seon.program/declaration-row` | false | 0 |
| `:seon.program/delete-identities` | false | 0 |
| `:seon.program/deletion-row` | false | 0 |
| `:seon.program/identity` | false | 0 |
| `:seon.program/identity-attribute` | false | 0 |
| `:seon.program/ns` | false | 0 |
| `:seon.program/owned-attributes` | false | 0 |
| `:seon.program/row` | false | 0 |
| `:seon.program/rows` | false | 0 |
| `:seon.program/schema-row-properties` | false | 0 |
| `:seon.program/shape` | false | 0 |
| `:seon.program/source` | false | 0 |
| `:seon.program/source-attribute` | false | 0 |
| `:seon.source/activation` | false | 0 |
| `:seon.source/activation-closure` | true | 1 |
| `:seon.source/branch` | false | 0 |
| `:seon.source/built-at` | true | 1 |
| `:seon.source/built?` | false | 0 |
| `:seon.source/commit-id` | true | 1 |
| `:seon.source/current` | false | 0 |
| `:seon.source/database` | false | 0 |
| `:seon.source/digest` | true | 1 |
| `:seon.source/digest-request` | false | 0 |
| `:seon.source/expected-commit-id` | false | 0 |
| `:seon.source/file-digests` | false | 0 |
| `:seon.source/path` | false | 0 |
| `:seon.source/populate` | false | 0 |
| `:seon.source/populate-request` | false | 0 |
| `:seon.source/population` | false | 0 |
| `:seon.source/previous-database` | false | 0 |
| `:seon.source/publish-request` | false | 0 |
| `:seon.source/published` | false | 0 |
| `:seon.source/roots` | false | 0 |
| `:seon.source/snapshot` | false | 0 |
| `:seon.source/upsert-request` | false | 0 |
| `:seon.source/upsert-row` | false | 0 |
| `:seon.source/upsert-rows` | false | 0 |
| `:seon.cluster.source/invalid-source-seal` | false | 0 |
| `:seon.cluster.source/invalid-source-seal-error` | false | 0 |
| `:seon.cluster.source/populate-unresolvable` | false | 0 |
| `:seon.cluster.source/populate-unresolvable-error` | false | 0 |
| `:seon.cluster.source/publish-readback-failed` | false | 0 |
| `:seon.cluster.source/publish-readback-failed-error` | false | 0 |
| `:seon.cluster.source/refused` | false | 0 |
| `:seon.cluster.source/refused-error` | false | 0 |
| `:seon.cluster.source/root-absent` | false | 0 |
| `:seon.cluster.source/root-absent-error` | false | 0 |
| `:seon.cluster.source/rule` | false | 0 |
| `:seon.cluster.source/stale-publication` | false | 0 |
| `:seon.cluster.source/stale-publication-error` | false | 0 |
| `:seon.cluster.source/unsafe-incremental-rows` | false | 0 |
| `:seon.cluster.source/unsafe-incremental-rows-error` | false | 0 |
| `:seon.fn.output/ai-paths` | false | 0 |
| `:seon.fn.output/bypasses` | false | 0 |
| `:seon.fn.output/classification` | false | 0 |
| `:seon.fn.output/codec-paths` | false | 0 |
| `:seon.fn.output/counterexamples` | false | 0 |
| `:seon.fn.output/external-sink` | false | 0 |
| `:seon.fn.output/first-bypass` | false | 0 |
| `:seon.fn.output/html-paths` | false | 0 |
| `:seon.fn.output/path` | false | 0 |
| `:seon.fn.output/path-report` | false | 0 |
| `:seon.fn.output/paths` | false | 0 |
| `:seon.fn.output/projected` | false | 0 |
| `:seon.fn.output/report` | false | 0 |
| `:seon.fn.output/report-artifact` | false | 0 |
| `:seon.fn.output/required-projection` | false | 0 |
| `:seon.fn.output/sink` | false | 0 |
| `:seon.fn.output/sinks` | false | 0 |
| `:seon.fn.output/source` | false | 0 |
| `:seon.fn.output/totals` | false | 0 |
| `:seon.fn.output/unresolved` | false | 0 |
| `:seon.export/parent-dir` | false | 0 |
| `:seon.export/path` | false | 0 |
| `:seon.export/request` | false | 0 |
| `:seon.artifact/refused` | false | 0 |
| `:seon.artifact/refused-error` | false | 0 |

## Validation and landing boundary

Only this Markdown note is owned and changed by data-audit-a. The live probes are database reads through the requested MCP JVM mode; no source was installed and no model session was launched. MCP's own projection may store an oversized result blob (inventory1 returned a retrievable digest); no audit form called a write API. The initial runtime-status timeout and the later timed-out large pull are recorded above, with the successful replacement probes. All seven task classes are specified; missing-call completeness, general deadness, per-example status and exact-program test freshness are explicitly unavailable from current stored facts, rather than reported healthy.

The required command was `bin/test --paths docs/prds/context-generation/research/data-audit-a-2026-09-15.md --platform`. It printed exactly one snapshot difference, this added note, over HEAD `af278535cb7369726983f4ee60cf739abc5381bf`. Result: **84 tests, 505 assertions, 0 failures, 0 errors; exit 0**. It loaded the canonical program and armed contracts, ran the declared platform tier, and did not run a full suite. Recorded phases: snapshot 8 s, dependency-cache/classpath 6 s, worker checkouts 15 s, published-base 109 s (108,481 ms waiting for its cache lock; reused base in 5 ms), coordinator/tests 143 s. This is an isolated platform proof, not a live export implementation proof or certification of foreign working-tree edits. The runner removed its successful root `tmp/test-runs/run.kK4Txz` before returning.

Documentation checks: 33 Clojure/EDN fenced blocks read successfully with `*read-eval* false` through the local Clojure-compatible reader; all Markdown links resolve. Reader validation is syntax-only, not execution of the proposed future functions. Every inventory row was matched to the read schema declarations: 324 returned rows for 324 assigned keys. No production tests were added for this research-only edit. Editorial additions after the gate snapshot do not change executable inputs; no broader test run was warranted. No separate scratch script/worktree remains; the embedded probe forms preserve the evidence in this one committed note. Foreign edits were neither restored nor included.

## Minimum viable point — build in this order

**Recommendation: one function task, one explicit test subject, two new files.** Use an existing source-bearing function identified by `functions-without-tests`; first distinguish missing reachability evidence from a missing test. Do not begin with dead-function deletion, arbitrary doc effects or automatic recurring-fault closure. The measured Juniper edge gap and one-result census show why (`fnGaps`, `agentRows`, `agentSource2`, `red`).

1. **Data model and capture first.** Reuse fn/test/ns/source/spec/calls/subject; fix the missing admitted test call-edge class with the real SCI fixture. Persist the existing test-run identity and add test→run plus captured program fingerprint/basis/branch in the existing schema families. One transaction records run and test results through `record-tx`. Implement current-src recording through its publication owner and retain result evidence across full source rebuilds. Exact required changes and write costs are in the results section.
2. **Query functions.** Expose `seon.test/red`, reuse `seon.fn/functions-without-tests` and `tests-reaching`, add total `seon.test/verified?` and `seon.fn/tested?`. Derive the fingerprint once from the actual tested program. Negative cases must include missing subject, no results, zero assertions, changed source/schema/test after a pass, and a result from another branch containing different code. These conditions are the task's completion definition.
3. **Entity context.** Add one AI/HTML pair to `:seon.fn/fn` and one to `:seon.test/test`; extend existing namespace renderer only where necessary. The fn pair selects F, relevant T, exact test command and success call; the test pair shows failure/expected/actual and tested-program identity. Render actual data including input/output schema refs. Keep AI bounds at existing render seams. No problem family, detector registry or new task-kind stamp.
4. **Ordinary agent session.** Supply the existing plan/agent flow with the selected durable subject and these rendered query forms. Show exact admitted `defn`, `deftest` with explicit `:seon.test/subject`, and `my.test/run` examples from current namespace facts. Require a self-contained fixture, explicit empty-input behavior and meaningful assertions. Accept only a fresh persisted run for the repaired function/test. This is an implementation/proof step; this audit did not run an agent or certify that plan binding.
5. **Pure assembly, then existing file effect.** Implement proposed `seon.program/source-files` and `seon.cluster.export/source!` with open request/result value schemas under `seon.export.edn`. Supply two explicit new file paths and namespace mappings; include required fixtures and dependencies. Reuse fs/edit output/deadline/digest contracts. No new durable file attributes for this constrained case.
6. **Prove disk output, not only database admission.** Read the written bytes; analyze them with the existing file→program owner and compare requested identities/contracts/refs. Run the canonical isolated, armed gate from those files. Record that tested program's provenance, and require `seon.fn/tested?` to return **true** for that program. Persist through the ordinary publication/commit path. A passing in-memory test before export is insufficient; no operation here can recover private `example-rows` from its name.
7. **Only then widen.** Add error→regression metadata for recurring faults, per-doc-example test evidence, unresolved-call retention and non-call references if those task classes are next. They are not dependencies of the one-function MVP.

Minimum new **durable** attributes: test→run and run program provenance. Minimum new **pairs**: fn and test. Minimum new **mechanisms**: none beyond extending existing admission, result transaction, rendering, source publication and filesystem seams; the missing source assembly/export functions connect them. This is still several owner changes: the common tested-program identity and source-publication preservation must be designed together. The narrower two-new-files constraint avoids a general source editor and its file-position model.

The completion chain is explicit:

```text
stored fn/test/namespace facts
  → functions-without-tests / red
  → fn + test entity render pairs
  → ordinary agent admits repaired definition + self-contained test
  → real test run writes results and exact tested-program identity
  → source-files derives two explicit file bodies
  → existing bounded file writes
  → canonical gate loads those files and commits its run evidence
  → tested?(subject, exact exported program digest) = true
```

No model-authored “done” statement, missing error, empty result set or successful store export substitutes for the last observation.
