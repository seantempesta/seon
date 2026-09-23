---
type: research
status: current
created: 2026-09-23
---

# Independent review: the system documents itself as code and data

**UNVERIFIED — recommendation:** Keep the core idea: existing declarations and authored data become queryable facts, and ordinary functions select and render their context. Do not make namespace membership, call reach, transaction order, or passing tests carry meanings they do not establish. Add a small home for conceptual prose; keep task intent separate from executable test admission; make deliverables include schema declarations explicitly.

**VERIFIED — principal gaps:** The draft cannot express the first page's schema deliverables; its namespace contracts reject the toy's namespace; its staleness rule misses a changed body with an unchanged docstring; and the toy's red tests cannot all be shared acceptance requirements while its two tasks merge independently. These follow from the declarations and selection code examined below. **UNVERIFIED:** No new documentation/task lifecycle, merge policy, or device runtime is proved installed by this review.

## Evidence boundary

**VERIFIED:** Source baseline was `db0ebf75ef740f7d5f0588e73f93106472a2a6f5`, branch `refactor/agent-platform`. Pinned gitlinks inspected: Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`, clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`, Malli `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`, Datahike `c79cd03a44427ac1734d917c7484c3e529c77716`, SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2`. Seon's local dependency coordinates are in `deps.edn:13`, `deps.edn:25`, `deps.edn:55`. Git history examined included draft changes `643495509`, `425d2b2aa` and page experiment `5dab9e74a`.

**VERIFIED:** “VERIFIED” below means inspected source, a directly reproducible deduction, or the explicitly identified isolated Babashka probe. It never means installation on `default`. “UNVERIFIED” identifies recommendations, estimates, unmeasured behavior, and absent runtime proof. Paths and lines are relative to the repository. `D` means `docs/research/agent-platform/design-namespace-agent-context-draft-2026-09-23.md`; `P` means `docs/wiki/seon/doc/errors_render_once.md`.

**VERIFIED:** Read the complete draft, laws, toy schema/source/tests, saved kondo analysis, toy extraction code, page, and recorded page context. The saved kondo artifact has 9 var definitions, 4 marked tests, 112 keyword observations, 61 var usages, and no `:symbols` entries (`tmp/wiki-toy/kondo.edn:1`). Its recorded 52 ms analysis duration is historical evidence, not a fresh benchmark. The page experiment's retained script contains a description of transcript forms rather than their implementations (`tmp/wiki-experiment-2026-09-23.clj:1`); its recorded timings cannot be reproduced from that file alone.

## Does it meet the owner's requirements?

**VERIFIED:** The following assessments compare the assignment's owner model with the cited artifact. “Meets” describes the design intent, not installation.

| Owner requirement | Assessment and evidence |
|---|---|
| Document the whole system; the plan is a query | **VERIFIED — partial.** D:10 gives a declaration graph; D:191 roots everything in a namespace. Cross-namespace concepts, the primer and judgement laws have no declared node in D:123. P:4 begins filling that gap, but P:22 still embeds a plan inside a page. Querying task membership is possible in principle; complete system coverage is **UNVERIFIED**. |
| Documentation cannot silently drift | **VERIFIED — fails the stated example.** D:217 compares linked targets' redefinition order with the declaring definition. Changing a function's own body without its docstring need not change any target. The existing digest includes source and doc together (`src/seon/program.cljc:361`, `src/seon/fn.clj:666`, `src/seon/fn.clj:689`); it detects changed content, not reviewed prose. |
| Distinguish code from the data it operates on | **VERIFIED — partial.** Configuration already admits initialization rows and reconciles desired values (`config/default.edn:511`, `src/seon/config.clj:653`). D:145 restricts deliverables to qualified symbols. P:26 asks for schema-property edits but P:27 delivers only a renderer function. Schema keys and non-code task outputs are missing from this boundary. |
| Define Malli data before tests/functions | **VERIFIED — partial.** Toy schema exists, but its description's lift/rest distinction is not enforced (`tmp/wiki-toy/schemas/toy.workout.edn:9`). Summary day counts are unrestricted integers (`:12`). D:223 describes fault-to-test work without spelling out schema review as a preceding stage. |
| Generative tests specify input/output relationships | **VERIFIED — partial.** The toy has a `defspec`, but its oracle calls `set-volume`, the helper used by the subject (`tmp/wiki-toy/test/toy/workout_test.clj:13`, `tmp/wiki-toy/src/toy/workout.clj:28`). It proves composition consistency, not independently correct volume. Both task tests are single examples (`tmp/wiki-toy/test/toy/workout_test.clj:27`, `:36`). |
| Run task tests after every turn | **VERIFIED — incomplete specification.** D:51 specifies re-prompting while red, but no precise post-turn settlement point or selection of task tests on every turn. **UNVERIFIED:** This cadence is implemented. |
| Missing symbol is failing test | **VERIFIED — partial.** Toy weekly test defers resolution to execution (`tmp/wiki-toy/test/toy/workout_test.clj:41`), yielding an error in the isolated run. Missing test identities instead produce a resolution refusal in the production source (`src/seon/test.clj:1294`), not an executed failing assertion. D:204 also calls them “not yet written.” All must mean incomplete, but these are different evidence. |
| Tasks are data; task types are context functions | **VERIFIED — meets intent, incomplete shape.** D:139 and D:153 say this directly. Toy task values omit id, context function and explicit acceptance set (`tmp/wiki-toy/test/toy/workout_test.clj:21`); the extractor infers taskhood by presence of `:seon.task/ns` (`tmp/wiki-toy/experiment/scratch/wiki_toy.clj:82`). The production admission for these sketches is **UNVERIFIED**. |
| Done is decided by tests | **VERIFIED — partial.** D:231 requires current green evidence and a base/branch regression comparison. D:51 freezes digests even though D:270 permits tests that do not exist yet. A nonexistent test has no digest to freeze. D:59 allows direct root merge; acceptance must still retain the mandatory combined-program checks in `AGENTS.md:119`. |
| SCI is scratch; only task deliverables merge | **VERIFIED — partial.** D:45 expands deliverables by changed definitions they reach. This is an unbounded extension of the declared set, and excludes necessary schema changes. SCI supplies context forking (`reference-code/sci/src/sci/core.cljc:345`), but that guarantee is about Vars, not effect rollback or selective publication. |
| Refusals teach the correct form; bounded re-prompt; ask root | **VERIFIED — meets intent.** D:51–62 states these outcomes. Native Malli explanations already contain schema, value and errors (`reference-code/malli/src/malli/core.cljc:2642`). **UNVERIFIED:** End-to-end preservation, delivery and task retries work as described. |
| Must-read always included; see-also related | **VERIFIED — partial.** D:102 promises one-hop inclusion or a named budget finding. D:191 also makes the entire namespace story mandatory, without reconciling that amount with the budget. Link targets are restricted to qualified symbols, although namespace and page names can be simple symbols (D:105; P:4). |
| Symbols, full names, no string mini-language | **VERIFIED — inconsistent artifacts.** D:35 says prose is never parsed. Toy `doc-tokens` parses code spans from strings (`tmp/wiki-toy/experiment/scratch/wiki_toy.clj:36`). Task dependency “Needs accept-rest-days first” exists only in prose (`tmp/wiki-toy/test/toy/workout_test.clj:32`). |
| Standard Clojure agents know | **VERIFIED — largely meets intent.** The toy uses ordinary `ns`, `defn`, `def`, `deftest`, `defspec`, and EDN. However the assertion “two metadata keys, nothing else” (D:63) does not describe its task metadata, task fields, or P's document grammar. Those additions need explicit schemas and examples. |
| Agents first, including less capable agents | **VERIFIED — partial design.** Contracts, source and concrete test outcomes are available in the proposed context (D:191). Automatically ranked neighborhoods do not explain contradictory contracts or provenance. The page context presents proposed calls and truncated source (`tmp/wiki-experiment-context-declare-base-renderer.txt:1`). **UNVERIFIED:** Such context improves completion rates or cost. |
| Devices; ~10 subsystems; ≤10k lines; library first; sub-second | **VERIFIED — unresolved.** D:263 prices only fragments of new machinery, not its task lifecycle or retirement integration. D:182 adds a reference database; D:206 adds ranking. **UNVERIFIED:** Total size, memory, warm context latency and deployment compatibility meet the target. The toy timings below prove none of these. |
| Do not skip steps or enforce principles dogmatically | **VERIFIED — partial.** D:236 prioritizes observed pain. P:26 nevertheless prescribes a repair before distinguishing the rendering stages, and its requested tests do not exist (P:32). The smaller path is to establish schema meaning and reproduce the decision before implementing that repair. |

## Concrete contradictions and avoidable machinery

**VERIFIED — namespace is not a qualified Var symbol:** `toy.workout` is a simple symbol; `toy.workout/log-entry` is qualified. Clojure checks for a namespace component, not a dot (`reference-code/clojure/src/clj/clojure/core.clj:1646`); Malli's `:qualified-symbol` uses that predicate (`reference-code/malli/src/malli/core.cljc:822`). D:135 and D:141 therefore reject the toy namespace and P:24, despite D:32 correctly saying namespaces use `:symbol`. P:4's `seon.doc.errors-render-once` similarly cannot be a target of D:108's qualified-symbol-only link attribute.

**VERIFIED — quoting is context-sensitive:** In the Babashka probe, toy namespace metadata holds `[(quote clojure.core/transduce) (quote malli.core/validate)]`, whereas function metadata holds `[toy.workout/set-volume]` and test metadata holds `toy.workout-test/accept-rest-days`. The saved kondo metadata also contains quoted forms and unqualified `set-volume`, while the corresponding usage resolves it (`tmp/wiki-toy/kondo.edn:1`, source `tmp/wiki-toy/src/toy/workout.clj:14`, `:26`). **UNVERIFIED — recommendation:** Normalize through the existing analyzer's resolver evidence, validate the resulting symbols, and show accepted spellings for each producer. Do not copy a metadata value blindly or invent a second alias resolver.

**VERIFIED — observations are not calls or specifications:** The toy extractor puts every `:from-var` usage into `:seon.fn/calls`, including references in data (`tmp/wiki-toy/experiment/scratch/wiki_toy.clj:59`, `:76`). Production already distinguishes references from calls (`src/seon/fn.clj:376`, `:382`). The toy extracts schema references by collecting keywords indiscriminately (`tmp/wiki-toy/experiment/scratch/wiki_toy.clj:86`); production uses Malli's walker to distinguish actual references (`src/seon/schema.clj:61`). These toy shortcuts must not become the production graph.

**VERIFIED — “most specific” is not schema subsumption:** The inspected selector scores the count of required/present attributes and then deduplicates renderer symbols (`src/seon/render.clj:364`). Two matched schemas declaring the same function do not alone create ambiguity at that stage (`:374`). Namespace contract selection is a separate, earlier stage (`:453`, `:519`). P:9's historical diagnosis is therefore **UNVERIFIED** from current source; P:14 and D:212 overstate the selector's guarantee. The result-handle omission is directly supported by `src/seon/repl.clj:220`. **UNVERIFIED — recommendation:** Reproduce through `seon.render/selection`, assert which stage selected/refused, then decide the smallest repair. Moving declarations to the base may be worthwhile but is not yet proof of this cause.

**VERIFIED — task scope is neither dependency closure nor namespace membership:** P:26 requests editing `:seon.error/base` and many narrower schemas. A function-only deliverable set cannot name those edits. Conversely D:46 can promote any changed helper made reachable by the submitted function. `AGENTS.md:100` also distinguishes task/runtime data from program rows. **UNVERIFIED — recommendation:** Keep explicit function/test symbols and a separate explicit set of schema keys. Compute a candidate dependency diff, then require root to accept any added deliverables. Return task outputs such as answers/data through their owning writer; do not merge an agent's whole scratch state to deliver them.

**UNVERIFIED — remove four premature mechanisms:** Start without PageRank; select the task, acceptance tests, subject schemas, focused functions and direct must-reads in a stable order. Add ranking only after comparative task evidence. Start without a second reference database; reuse version-pinned library rows in the existing program store, measuring the sharing before claiming per-branch copies. Start without general prose-token extraction; explicit links are sufficient. Start without a new task runner; make post-turn checks requests to the existing test owner. D:182, D:206 and D:263 motivate these proposals; actual net savings remain unmeasured.

**VERIFIED — tests are bounded evidence:** D:50 calls model-family review a way to stop cheating, but D:51 freezes only acceptance test digests. The toy property delegates part of its oracle to a mutable production helper (`tmp/wiki-toy/test/toy/workout_test.clj:16`). **UNVERIFIED — recommendation:** Review the test's oracle and relevant helper/input changes as part of the submitted diff; do not claim that frozen test text or a different model family guarantees honest outcomes. Require a regression's base failure to be the intended behavioral failure, not merely a setup or missing-fixture error. Preserve the root escape hatch for requirements that tests cannot adequately express.

**VERIFIED — dependency seams already exist:** Malli walks schemas and caches validators/explainers on compiled schemas (`reference-code/malli/src/malli/core.cljc:2611`, `:2626`, `:2642`). SCI forks contexts (`reference-code/sci/src/sci/core.cljc:345`). Seon already analyzes literal `def` initializers using kondo's node parser (`src/seon/fn/analyzer.clj:485`). It already reconciles desired rows (`src/seon/reconcile.cljc:254`) and selects reaching tests (`src/seon/fn.clj:1531`). **UNVERIFIED — recommendation:** Compose these owners; a new graph registry, evaluator, validator cache, or handwritten Clojure parser has no demonstrated need here.

## G1–G6: smallest viable choices and prices

**UNVERIFIED — estimate convention:** Prices below are rough engineering days for one focused implementation and meaningful regression evidence, not elapsed promises, measured latency, or a combined project quote. They exclude repairing unrelated platform failures. Alternatives overlap; do not sum them. Each option states its guarantee and sacrifice. All performance/line-count projections need measurement before implementation is accepted.

### G1 — long-form prose

**VERIFIED:** P proves a readable authored layout, but repeated EDN blocks carry task semantics tied to headings (P:18, P:35). The retained experiment describes H1/first-block and H2 rules without retaining their parser (`tmp/wiki-experiment-2026-09-23.clj:7`). D:15's docstring-only home does not cover this artifact.

| Option | Price, guarantee, sacrifice |
|---|---|
| **UNVERIFIED — A, recommended:** Markdown text plus one ordinary EDN manifest/sidecar | **0.5–1.5 days.** Page identity, body path, links and task membership are explicit EDN; ingestion stores body bytes/digest in the same declared page row. No prose interpretation. Costs two authoring surfaces, but only one prose authority. Use existing reconciliation; do not add every page manually to config. |
| **UNVERIFIED — B:** One reserved EDN header block in each Markdown page | **1–2 days.** One file, explicit data, real Markdown AST supplies the header node/span. Remaining fences are examples. Costs one parser dependency and a small documented file convention. |
| **UNVERIFIED — C:** Repeated section declarations as P demonstrates | **2–4 days.** Structured per-section contexts and tasks. Costs stable section identities, edit/retraction rules, ordering and scope semantics; headings alone must not become task identity. Choose only if section-level querying materially helps. |

**UNVERIFIED — recommendation detail:** A page is an ordinary documented entity, not a namespace pretending to contain Vars. Reverse links come from queries, so authors do not maintain both directions. The primer and laws can be ordinary pages selected explicitly by task context functions. Missing body files, duplicate page identities and unresolved authored links should fail admission visibly. Whole-system coverage remains an editorial responsibility; a graph cannot invent unwritten knowledge.

### G2 — task declaration and docstrings

**VERIFIED:** Raw kondo var-definition output selects metadata/doc/spans, not arbitrary initializer values (`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:87`). That does not imply evaluation is necessary: Seon's existing parser already recognizes recursively literal/quoted `def` initializers, including a docstring (`src/seon/fn/analyzer.clj:485`, `:514`). The toy instead dereferences runtime Vars (`tmp/wiki-toy/experiment/scratch/wiki_toy.clj:72`).

| Option | Price, guarantee, sacrifice |
|---|---|
| **UNVERIFIED — A, recommended:** Task is EDN entity data with its own explanation string | **0.5–1.5 days** for declaration ingestion, excluding lifecycle. Read data without running a test namespace; qualify symbol values directly. Tests name the task by metadata or the task names tests—choose one authoritative direction. Sacrifice the `def` docstring spelling; preserve its prose as a declared task attribute. |
| **UNVERIFIED — B:** Keep ordinary `def` with a literal, preferably wholly quoted, task map | **1–2 days.** Reuse the existing kondo-node parse; extract the docstring and literal initializer, refusing nonliteral task initializers. No arbitrary evaluation. Sacrifice computed initializers and require a small extension at the current analyzer, not a `deftask` language. |
| **UNVERIFIED — C:** Obtain task values after loading/evaluating the namespace | **0.5–1 day** for the bridge, additional isolation cost unknown. Supports any computed value and Clojure docstrings. Sacrifices static read-only discovery and couples indexing to load success/effects; inappropriate as the default authoring format. |

**UNVERIFIED — recommendation detail:** Task data describes work; a task-instance writer supplies runtime identity/provenance as needed. Do not reconcile progress, budgets or completion back to authored defaults. Make the acceptance-test review a stage: an unresolved test name is an obligation; root freezes actual source digests only when reviewed tests exist, before implementation. This resolves D:51 versus D:270 without placeholder tests.

### G3 — parsing code inside prose

**VERIFIED:** The toy regex splitter and `edn/read` silently discard parse failures (`tmp/wiki-toy/experiment/scratch/wiki_toy.clj:36`, `:42`). This conflicts with D:35 and cannot distinguish illustrative names, locals, quoted data and executable references reliably. Its doc links are therefore not authoritative dependencies.

| Option | Price, guarantee, sacrifice |
|---|---|
| **UNVERIFIED — A, recommended:** Do not parse docstrings for graph edges | **0–0.5 day.** Keep authored `must-read`/`see-also`, schema references and test examples. No Markdown dependency for docstrings; no false executable links. Sacrifice automatic navigation from incidental inline code. |
| **UNVERIFIED — B:** Markdown AST only for advisory mentions and rendering | **1–2 days.** Preserve spans and label mentions as observations; never turn them into merge dependencies. Costs parser integration and unresolved-mention diagnostics. |
| **UNVERIFIED — C:** Executable fenced examples | **2–5 days.** Parse real Markdown, analyze selected Clojure blocks through the existing analyzer, run explicit example tests with namespace/custody. Costs execution semantics, setup and diagnostic provenance; duplicates ordinary test authoring unless the examples are a primary product. |

**VERIFIED — web/library evidence:** CommonMark Java exposes an AST, visitors and optional block/inline source spans; these are suitable seams for B or G1-B, without a new Markdown parser ([upstream README](https://github.com/commonmark/commonmark-java#source-positions)). Malli itself declares a separate `test-doc-blocks` tool alias (`reference-code/malli/deps.edn:37`). **UNVERIFIED:** Either library/tool is compatible with the intended device or Seon publication path; no integration was performed.

### G4 — red task tests versus shared-head merge

**VERIFIED:** `a-week-is-summarized` reaches `log-entry` as well as the missing summary (`tmp/wiki-toy/test/toy/workout_test.clj:41`). The gate follows calls, references and subjects (`src/seon/fn.clj:1543`). If both task tests are admitted on shared head, fixing rest-day logging still selects a red weekly-summary obligation. Filtering by task metadata would weaken `AGENTS.md:119`, not solve the contradiction.

| Option | Price, guarantee, sacrifice |
|---|---|
| **UNVERIFIED — A, recommended:** Keep red acceptance tests on the task's branch | **3–7 days** including task admission and extending the existing merge boundary for additions. Shared catalog may hold task intent and unresolved acceptance names; executable red definitions enter the task branch. Merge code and its new tests atomically after combined-head checks. Sacrifice globally runnable future tests; retain their named pending state as task data. |
| **UNVERIFIED — B:** Store proposed test source as task-owned data; admit it on branch acquisition | **4–8 days.** Future tests can be browsed from shared head without claiming they are current executable program rows. Costs an explicit proposed-versus-admitted boundary and source/digest verification. Useful if branch retention is expensive. |
| **UNVERIFIED — C:** Treat both changes as one coordinated task/merge | **0.5–1 day** for this toy's orchestration; **2–4 more** if new declarations must pass the current merge owner. All admitted obligations must become green together. No exemptions. Sacrifice independent delivery and scale poorly if many unrelated future tests share common helpers. |

**UNVERIFIED — recommendation detail:** After every completed agent turn, request exactly its reviewed acceptance set against the acquired branch; no green while a body remains unfinished. At merge add all required current affected tests. Preserve missing-definition, assertion-failure, exception and unavailable-evidence distinctions while all derive “not done.” Root may revise the task with recorded reason; direct merge must not bypass the combined gate. An explicit dependency between task instances is useful only if it governs acquisition; prose cannot supply that fact.

### G5 — devices

**VERIFIED:** D:170 requires runtime Var/classpath/dependency discovery, and the toy extraction requires host runtime Vars and `clojure.repl/source-fn` (`tmp/wiki-toy/experiment/scratch/wiki_toy.clj:72`, `:75`). The checked-out dependency declaration alone proves no device footprint or packaging (`deps.edn:13`).

| Option | Price, guarantee, sacrifice |
|---|---|
| **UNVERIFIED — A, recommended:** Analyze where code is authored; ship indexed data and render/context functions | **1–3 days** for a format/loading prototype, platform delivery excluded. Device reads and queries the same facts. Sacrifice unrestricted local source authoring; source-to-index toolchain stays off the constrained device. |
| **UNVERIFIED — B:** On-device task/data edits; remote code analysis/publication | **2–5 days.** Device agents can use/edit schema-valid data and propose code; publication evidence returns with content identity. Costs an explicit connectivity/adoption boundary and an offline incomplete state. |
| **UNVERIFIED — C:** Full analyzer/compiler/indexer on-device | **5–15+ days** just to establish one target's feasibility. Enables offline code authoring if dependencies and footprint work. Sacrifices initial scope and likely size; no compatibility/latency guarantee yet. |

**UNVERIFIED — recommendation detail:** Do not make Babashka or SCI synonymous with the whole authoring toolchain. Requiring on-device parsing is a product capability decision, not a consequence of “the program is data.” Name one device/runtime and its offline authoring requirement before paying for C.

### G6 — reassertion, order and documentation review

**VERIFIED — partial confirmation:** The production index skips equal normalized rows, selects changed attributes and retracts only differing owned attributes (`src/seon/fn.clj:3255`, `src/seon/program.cljc:1282`). Datahike preserves the current datom for an identical assertion by suppressing the effective change (`reference-code/datahike/src/datahike/db/transaction.cljc:606`). `seon.reconcile/reconcile!` avoids a transaction when its plan is empty (`src/seon/reconcile.cljc:430`). The source publication unchanged-input path also returns without rebuilding (`src/seon/cluster/source.clj:562`, `:583`).

**VERIFIED — limits:** This is not the stronger statement “only changed datoms are ever reasserted”: a changed many-valued attribute can be retracted wholesale and re-added (`src/seon/program.cljc:1323`). Nor does an idempotent assertion imply no transaction metadata/commit work. More fundamentally, editing a consumer after a dependency may be unrelated to reviewing that dependency; changing body and documentation in the same transaction is not proof either. D:217's ordering is at most a warning heuristic, not freshness evidence. **UNVERIFIED:** Current installed index/adoption paths preserve all relevant transactions exactly as assumed.

| Option | Price, guarantee, sacrifice |
|---|---|
| **UNVERIFIED — A:** Conservative change warnings only | **0.5–1 day.** Compare old/new declaration and doc values; warn on changed declaration with unchanged prose and on changed declared dependencies. Catches the owner's example. Sacrifice persistent acknowledgement and precise clearing; false positives are intentional. |
| **UNVERIFIED — B, recommended when acknowledgement is needed:** Review observations over content identities | **1–3 days.** Store which subject/doc/dependency digests a review examined; currentness is equality against those contents. This is evidence with an author/reason, not a writer-maintained “fresh” flag. Costs a small observation schema and explicit review action; cannot prove prose truth. |
| **UNVERIFIED — C:** Transaction-order heuristic | **0.5–1 day.** Cheap local warning from existing datoms if the exact write paths are proved. Sacrifice reliable clearing, body/doc comparison and cross-branch/import meaning. Retain only as an advisory signal, never “documentation verified.” |

**UNVERIFIED — smallest rollout:** Begin with A to make potential drift visible. Add B only for documents that must remain acknowledged across edits. Do not force pointless docstring changes when the explanation still applies; an explicit review of unchanged prose is the right evidence. Required probes: identical reindex, body-only edit, doc-only edit, unrelated file edit, same-transaction body/doc edit, many-link edit preserving one member, dependency pin change, and branch adoption. Record actual datoms plus content identities; source review here is not that installed proof.

**VERIFIED — implementation boundary that must be priced:** The inspected `prepare-merge!` is issue-based and explicitly refuses added/retracted definitions, accepting existing replacements (`src/seon/cluster/source.clj:760`, `:767`). A new `weekly-summary` and its acceptance test cannot land through that path as written. **UNVERIFIED — recommendation:** Extend this owner under the combined gate when implementing G4; do not add another merger. The option estimates include this risk, not proof that the change is small.

## The toy from an agent's point of view

**VERIFIED — actual behavior:** Isolated Babashka using the checked-out Malli source accepted `{kind :rest}`, `{kind :lift}` without sets, and a rest entry with nonempty valid sets under `:toy.workout/entry`. `log-entry` accepted `{kind :invalid, sets [1]}` and threw `ExceptionInfo("entry has no sets", {:entry ...})` for a rest entry. Keys in this sentence abbreviate `:toy.workout/*`; exact probe below. The cause is the body at `tmp/wiki-toy/src/toy/workout.clj:36`, not Malli validation. Its docstring's “after validating it” is false for the uninstrumented function (`:31`).

**UNVERIFIED — context for `accept-rest-days`:** Include the task's explanation and explicit deliverables; exact `:entry`, `:kind`, `:sets`, `:set` schemas and their descriptions; `log-entry` source/contract; the failing test with actual exception; the existing successful lift example; and current execution/arming facts. Include the validation owner's callable shape/registry convention, not just the name `malli.core/validate`. Show the contradiction between schema prose and constraints and let the agent ask root whether lift days require nonempty sets and whether rest sets must be absent or empty. Do not require an agent to infer that product decision from the bug comment.

**UNVERIFIED — acceptance relationships for that task:** For valid log and valid entry, result is the old log followed by exactly that entry; the prefix/order is preserved and length grows by one. A rest entry succeeds. Invalid entries refuse through the declared boundary. Generate both entry kinds, empty/nonempty logs and meaningful invalid partitions. The task must decide whether validation belongs in an armed entry contract or the body; duplicating the check is not automatically correct.

**UNVERIFIED — context for `weekly-summary`:** Include its output schema and reviewed acceptance source; `total-volume`, `set-volume` and their tests; representative mixed/all-rest/all-lift/empty inputs; and the acceptance state of rest logging. State that the input is an already chosen week's vector: the toy has no date, calendar, timezone or deduplication fields (`tmp/wiki-toy/schemas/toy.workout.edn:9`). Do not invent week selection or database storage. The direct pure summary need not depend on fixing log ingestion; the existing integration test does (`tmp/wiki-toy/test/toy/workout_test.clj:41`).

**UNVERIFIED — acceptance relationships for summary:** Counts equal the number of entries of each declared kind, their sum equals input length, and volume equals an independently expressed sum of reps×weight over legitimate lift sets. Concatenation combines counts and volumes; adding a rest day changes only its count. The empty result has zero counts and double `0.0`. Resolve the schema's rest-with-sets ambiguity first. Specify numeric comparison tolerance if arbitrary floating weights are generated. This defines outcomes without prescribing the implementation.

**VERIFIED — likely sources of confusion:** Task symbols and function symbols share names (`toy.workout-test/weekly-summary` versus `toy.workout/weekly-summary`); task dependencies appear only in prose; `must-read validate` suggests a check that the body never calls; the generative test is a helper-based oracle; “one entry per day” has no date identity; namespace must-reads ask for `transduce` even for a task that need not use it (`tmp/wiki-toy/src/toy/workout.clj:2`, `:14`, `:30`; `tmp/wiki-toy/test/toy/workout_test.clj:13`, `:30`). **UNVERIFIED — recommendation:** Context should show discrepancies explicitly, retain fully qualified names, and prioritize the task's decisions over a full namespace tour.

## Names and value types

**UNVERIFIED — recommendations:** Prefer the following minimal changes. These are design names, not installed attributes.

| Proposed name | Keep/change and reason |
|---|---|
| `:seon/must-read` | Keep the owner's name. Specify “include the described value,” one-hop expansion, and visible budget refusal. Do not conflate a link with proof of a completed read. Admit namespace/page symbols as well as qualified Var symbols, validating resolution by the target's identity. |
| `:seon/see-also` | Keep. It expresses relatedness without promising calls or dependencies. Derive reverse links. |
| `:seon/task` on tests | Prefer `:seon.test/task` if this relation is test-specific; otherwise retain the shared key with one identical meaning everywhere. Store a task's symbolic identity, not its evaluated map. Avoid authoring both this and a competing task→test roster; designate one as authoritative. |
| `:seon.task/ns` | Keep `ns`, which is Clojure vocabulary, but reuse the namespace-name schema (`:symbol` with namespace existence policy), not `:qualified-symbol`. It means perspective/responsibility, not merge scope. |
| `:seon.task/delivers` | Acceptable user-facing name, inadequate value domain. Keep function/test deliverables as native symbols and add explicit `:seon.task/schema-keys` for schema definitions. If renamed for precision use `:seon.task/definitions`, but do not build a generic artifact taxonomy before other outputs need it. |
| `:seon.doc/symbols` | If retained, prefer `:seon.doc/mentions-symbols`; an observed mention is not an executable dependency. Under G3-A delete this derived field entirely. |
| `:seon.doc/keys` | Prefer `:seon.doc/mentions-keywords` for lexical observations, or `:seon.doc/schema-keys` for explicitly declared schema subjects. “Keys” blurs map keys, keyword values and schema identities. Under G3-A no lexical field is needed. |
| Related: `:seon.task/context` | Prefer `:seon.task/context-fn` if it stores a function symbol, distinguishing the selector from its resulting context. Keep task types as these functions. |

**VERIFIED — storage consequence:** A heterogeneous keyword/symbol union is not automatically native symbol storage in the current bridge: mixed-type `:or` falls back to an EDN string codec (`src/seon/schema/datahike.clj:116`). Collection schemas become cardinality-many (`:105`), so a vector spelling does not preserve author order. **UNVERIFIED — recommendation:** Separate symbol and schema-key attributes where required; use explicit ordered components only when order has meaning. Reuse declared identities rather than adding a string grammar for mixed targets.

## One alternative to the overextended part of the design

**UNVERIFIED — alternative: a task-centered query over ordinary program and document data.** Namespace remains a useful owner and navigation filter; it is not the universal unit of explanation. The graph is an index of explicit facts, not the authority deciding what may merge or what the user meant. A task context function receives one immutable database value and the task, queries its acceptance tests, subject declarations and required pages, and returns bounded ordinary reads. The plan view queries these same task facts and current evidence. This preserves the owner's core idea while dropping automatic whole-namespace stories and graph-reach merge expansion.

**UNVERIFIED — smallest composition and expected work:**

1. Read source with the existing analyzer, schemas with Malli, task/page manifests as EDN, and page text as bytes. Work is proportional to changed source/page bytes plus reached schema structure; invalidate only on those inputs or analyzer/library revisions.
2. Reconcile exact desired facts through the existing writer. Work follows touched identities/attributes, with ordinary index lookup costs; no second registry or transaction-order freshness cache.
3. Select context from task subjects/tests and direct required links. Work is proportional to the selected neighborhood plus final rendered output. Name any omitted units; do not walk all library Vars every turn.
4. Run the task acceptance request after turn settlement; reuse only current positive test evidence under the existing owner's rules. Cost is the selected test bodies and their required acquisition. Budget exhaustion remains incomplete, never green.
5. Prepare an explicit program diff, add affected tests, review, then use the ordinary merge owner. A helper's reachability proposes a scope addition; it does not authorize one.

**VERIFIED — available composition points:** `src/seon/fn/analyzer.clj:496`, `src/seon/schema.clj:61`, `src/seon/reconcile.cljc:418`, `src/seon/fn.clj:1531`, `src/seon/test.clj:1266`, `src/seon/cluster/source.clj:736`. **UNVERIFIED:** Integration size, installed behavior, warm timings and net deletions. Before implementing, price this as one vertical slice: one real page, one schema-changing task and one body-only drift warning. Do not approve a platform-wide replacement from the toy alone.

## Reproducible diagnostic evidence and landing limits

**VERIFIED:** Only this deliverable is authored by this lane. No default-system operation, Seon test gate, worktree, dependency install or production edit was performed. A pre-existing dirty/untracked indication under `reference-code/http-kit` was left untouched. Source/test line delta is **0/0**. These probes ran in the existing `tmp/wiki-toy` directory; they wrote no probe files.

**VERIFIED — probe setup:** Use `bb --classpath` with absolute paths to `reference-code/malli/src`, cached `~/.m2/repository/borkdude/dynaload/0.3.5/dynaload-0.3.5.jar`, cached `~/.m2/repository/org/clojure/test.check/1.1.1/test.check-1.1.1.jar`, `tmp/wiki-toy/src`, and `tmp/wiki-toy/test`. The first attempt omitted dynaload and failed to load Malli; adding the already-cached dependency resolved it. Exact core probe:

```clojure
(require '[clojure.edn :as edn] '[malli.core :as m] '[toy.workout :as w])
(let [reg (merge (m/default-schemas)
                 (edn/read-string (slurp "schemas/toy.workout.edn")))
      entry (m/schema :toy.workout/entry {:registry reg})]
  [(m/validate entry {:toy.workout/kind :rest})
   (m/validate entry {:toy.workout/kind :lift})
   (m/validate entry {:toy.workout/kind :rest
                      :toy.workout/sets [{:toy.workout/exercise "squat"
                                          :toy.workout/reps 5
                                          :toy.workout/weight 100.0}]})
   (w/log-entry [] {:toy.workout/kind :invalid :toy.workout/sets [1]})
   (try (w/log-entry [] {:toy.workout/kind :rest})
        (catch Exception e [(ex-message e) (ex-data e)]))])
;; [true true true [#:toy.workout{:kind :invalid, :sets [1]}]
;;  ["entry has no sets" {:entry #:toy.workout{:kind :rest}}]]
```

**VERIFIED — measurements:** The measured validation/body form took **0.272375 ms**; full process **0.08 s**, peak RSS **102,842,368 bytes**, via `/usr/bin/time -l`. Namespace-predicate/artifact inspection took **1.970875 ms**. No warm repetition or allocation profile was taken. These are isolated diagnostic measurements, not armed Seon operation timings.

```clojure
(require '[toy.workout-test] '[clojure.test :as t])
(let [output (java.io.StringWriter.)
      start (System/nanoTime)
      result (binding [t/*test-out* output *out* output]
               (t/run-tests 'toy.workout-test))]
  {:result result :elapsed-ms (/ (- (System/nanoTime) start) 1e6)})
;; {:result {:test 4, :pass 2, :fail 0, :error 2, :type :summary},
;;  :elapsed-ms 5.4225}
```

**VERIFIED:** Errors were `a-rest-day-is-logged` and `a-week-is-summarized`; process **0.08 s**, peak RSS **99,549,184 bytes**. An earlier diagnostic rebound `clojure.test/report` and consequently suppressed its counters; its zero totals were discarded and the ordinary reporter was used for the result above. The toy's property has no fixed seed (`tmp/wiki-toy/test/toy/workout_test.clj:13`), so this is one diagnostic run, not reproducible generative proof of all behavior.

**UNVERIFIED — remaining proof:** No production publication, adoption, arming, Datahike transaction-order experiment, browser paint, real agent completion comparison or device packaging was tested. A stale skill citation was observed: `.agents/skills/data-oriented-clojure/SKILL.md:49` points to `AGENTS.md:271` for contracts; the current law is at `AGENTS.md:156`. The assignment permits only this deliverable, so the skill was not edited. No issue index or other lane's files were changed.

**VERIFIED — document checks:** Frontmatter, fence balance, and 71 explicit file/line targets passed local structural checks; the staged file passed `git diff --check`. The automatic repository-wide Markdown check reported 57 issues; displayed failures concerned historical dependency-pin citations in other landing files. That repository-wide check is not a pass, and those files are outside this assignment. Final deliverable: 229 lines; no production source or test edits.
