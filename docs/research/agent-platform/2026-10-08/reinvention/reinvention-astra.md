# Independent dependency census — reinvention-astra

2026-10-08. Research only. Owner authority: “Please stop reinventing shit that already exists. It just makes our codebase harder to manage and our versions are usually worse. The exception is if the library doesn't do what we need to do, but bring those decisions to me and we can decide on a case by case basis.” No source implementation or prototype was installed. The independent Opus report was not opened.

The strongest immediate deletions are schema-error prose, repeated collection-error validation, long-lived extra caches and eager loops of individual pulls. The largest apparent replacements concern ordered storage, read evidence, evaluation and Flow, but each has a specific semantic gap requiring the owner’s choice. A library’s existence is not evidence that it supplies that missing behavior.

## Basis and limits

Source citations below refer to the committed archive **aa6131f1a9dd73beafe6d5dc669c7bd08f976617**, captured after the initial working-tree search. Other lanes were committing and editing concurrently; an archive avoids mixing their changing lines into this census. All **106 src Clojure/ClojureScript-compatible files, 90,334 physical lines**, were inventoried by path and top-level definitions; searches covered dependency calls, readers, evaluators, printers, caches, graph traversal, transactions and test execution. Suspected owners and their dependency implementations were then read directly. Repeated callers are grouped by mechanism. This is a source census with verified interface gaps, not a proof that every branch of every owner is replaceable or that no further small duplication exists.

The table counts inclusive **physical lines in the inspected spans**, including contracts/docstrings. Those are not promised deletions. “Delete” is a deliberately rough net-line estimate for the stated smaller option, excluding new tests; owner-decision figures are conditional. Rank is **estimated lines deleted × risk of retaining the duplicate**, with risk 1 (presentation) to 5 (storage, concurrency, custody). This is not probability of implementation success. Overlapping owner spans are not additive. Slice estimates include focused proof but exclude broad root integration. Every OWNER DECISION paragraph states a concrete gap and options; none grants permission to implement an exception.

Paths after the first library path in a cell are relative to that library’s src directory unless fully qualified. Caffeine paths are the exact 3.2.4 upstream files linked below. nREPL and clj-reload are reference checkouts, **not dependencies in deps.edn**; adding them is a decision, not an installed capability.

## Ranked census

| Rank | Mechanism | Seon file:line and physical lines | Library file:line and supplied behavior | Delete | Risk | Product | Verdict |
|---:|---|---|---|---:|---:|---:|---|
| 1 | Ordered values in Datahike | src/seon/schema/datahike.clj:625–1010 (386); src/seon/db.clj:4977–5066 (90) | reference-code/datahike/src/datahike/schema.cljc:31–51; reference-code/datahike/src/datahike/db/transaction.cljc:1018–1046: symbols and tuples, tuple validation | ~300 | 5 | 1500 | **OWNER DECISION** |
| 2 | Query dependency capture and replay | src/seon/db.clj:700–986 (287), 1228–1370 (143) | reference-code/datahike/src/datahike/query.cljc:2956–3030; reference-code/datahike/src/datahike/pull_api.cljc:558–598: dependency plans and reads with evidence | ~250 | 5 | 1250 | **OWNER DECISION** |
| 3 | Source signatures and a second analysis pass | src/seon/fn/signature.cljc:16–147 (132); src/seon/program.cljc:768–1008 (241); src/seon/fn/analyzer.clj:565–667 (103) | reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:642–718, 904–935; reference-code/clj-kondo/src/clj_kondo/core.clj:242–294: arities, analyzed definitions and usage data | ~200 | 4 | 800 | **OWNER DECISION** |
| 4 | Transaction prevalidation and component traversal | src/seon/db.clj:3700–3836 (137), 4097–4348 (252) | reference-code/datahike/src/datahike/db/transaction.cljc:998–1016, 1225–1246: reference sweep and final-report validator | ~150 | 5 | 750 | **OWNER DECISION** |
| 5 | The read, evaluate, record, print loop | src/seon/batch.clj:355–620 (266), 687–761 (75); src/seon/turn/batch.clj:891–969 (79) | reference-code/clojure/src/clj/clojure/main.clj:368–460; reference-code/clojure/src/clj/clojure/core/server.clj:194–273: configurable repl and structured prepl | ~180 | 4 | 720 | **OWNER DECISION** |
| 6 | Printer selection and layout prediction | src/seon/render/value.clj:549–967 (419), 1040–1395 (356); src/seon/print.cljc:549–629 (81) | reference-code/fipp/src/fipp/engine.cljc:10–70, 73–255; reference-code/fipp/src/fipp/edn.cljc:9–22: document layout and collection limits | ~200 | 3 | 600 | **OWNER DECISION** |
| 7 | Call preparation and arity planning | src/seon/call_preparation.clj:126–168 (43), 515–717 (203), 825–1130 (306) | reference-code/malli/src/malli/core.cljc:2210–2245, 2280–2310, 3166–3187: function arities and instrumentation | ~150 | 4 | 600 | **OWNER DECISION** |
| 8 | Flow work launching | src/seon/flow.clj:75–163 (89), 426–848 (423) | reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243–323: proc execution, inputs, state and errors | ~120 | 5 | 600 | **OWNER DECISION** |
| 9 | SCI context regeneration | src/seon/sci/eval.clj:2879–2999 (121) | reference-code/sci/src/sci/core.cljc:260–275, 334–340; reference-code/sci/src/sci/impl/utils.cljc:410: intern, bind-root! and generation forks | ~80 | 5 | 400 | **OWNER DECISION** |
| 10 | Schema error prose | src/seon/error.clj:834–895 (62), 915–1008 (94) | reference-code/malli/src/malli/error.cljc:44–172, 288–306, 332–390: schema messages and humanization | ~110 | 3 | 330 | **REPLACE** |
| 11 | Schema references and inherited maps | src/seon/schema.clj:63–177 (115); src/seon/schema/internal.cljc:47–151 (105) | reference-code/malli/src/malli/core.cljc:1953–2027, 2657–2670; reference-code/malli/src/malli/util.cljc:9–13, 52–111: scoped refs, walk, equality and merge | ~60 | 4 | 240 | **OWNER DECISION** |
| 12 | Separate caches around dependency results | src/seon/db.clj:1434–1460 (27); src/seon/sci/eval.clj:3089–3098 (10), 3337–3341 (5); src/seon/call_preparation.clj:388–404 (17); src/seon/fn/analyzer.clj:253–258 (6) | Caffeine 3.2.4 AsyncCache.java:54–98; LocalAsyncCache.java:78–108; src/seon/cache.clj:53–75, 160–234: existing shared owner | ~60 | 4 | 240 | **REPLACE** |
| 13 | clj-kondo input and cache management | src/seon/fn/analyzer.clj:215–472 (258), 668–795 (128) | reference-code/clj-kondo/src/clj_kondo/core.clj:242–294; reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:176–215: in-run definitions, disk synchronization, findings | ~60 | 4 | 240 | **OWNER DECISION** |
| 14 | Caffeine entry claiming and completion | src/seon/cache.clj:108–159 (52), 160–234 (75) | Caffeine 3.2.4 AsyncCache.java:54–98; LocalAsyncCache.java:78–108, 198–232: atomic get, shared future, completion removal | ~45 | 5 | 225 | **OWNER DECISION** |
| 15 | Bounded database neighborhood acquisition | src/seon/render/walk.clj:189–538 (350), 675–838 (164) | reference-code/datahike/src/datahike/pull_api.cljc:58–107, 315–323, 541–598: compiled recursive pull, bounds and aligned results | ~70 | 3 | 210 | **OWNER DECISION** |
| 16 | Blob reachability through Datahike indexes | src/seon/cluster/registry.clj:435–534 (100) | reference-code/datahike/src/datahike/gc.cljc:22–81, 89–167; reference-code/konserve/src/konserve/gc.cljc:9: marking extension and sweep | ~40 | 5 | 200 | **OWNER DECISION** |
| 17 | Konserve blob staging and verification | src/seon/blob.clj:61–179 (119), 239–324 (86) | reference-code/konserve/src/konserve/core.cljc:634–688; reference-code/datahike/src/datahike/gc.cljc:89–167: binary operations and reachability exclusion | ~40 | 5 | 200 | **OWNER DECISION** |
| 18 | Schema registry construction and admission | src/seon/schema.clj:589–645 (57); src/seon/schema/internal.cljc:329–425 (97) | reference-code/malli/src/malli/registry.cljc:24–30, 54–95; reference-code/malli/src/malli/core.cljc:2657–2711: registries, compilation, walk, cached validation | ~40 | 4 | 160 | **OWNER DECISION** |
| 19 | Ordered namespace loading | src/seon/cluster.clj:1858–1884 (27); src/seon/sci/eval.clj:2387–2424 (38) | reference-code/clj-reload/src/clj_reload/parse.clj:163–175; reference-code/clj-reload/src/clj_reload/core.clj:185–191, 304–381: topological ordering and reload | ~45 | 3 | 135 | **OWNER DECISION** |
| 20 | Malli union error selection | src/seon/instrument.clj:379–427 (49) | reference-code/malli/src/malli/core.cljc:996–1038; reference-code/malli/src/malli/error.cljc:332–390: full union explanations and humanization | ~35 | 3 | 105 | **OWNER DECISION** |
| 21 | Namespace reader state | src/seon/sci/reader.cljc:75–225 (151), 384–448 (65) | reference-code/edamame/src/edamame/core.cljc:60–76, 161–166; reference-code/edamame/src/edamame/impl/ns_parser.cljc:45–102; reference-code/sci/src/sci/impl/parser.cljc:60–167 | ~35 | 3 | 105 | **OWNER DECISION** |
| 22 | Error chains and clojure.main triage | src/seon/error/refusal.clj:195–241 (47), 365–440 (76); src/seon/sci/kernel.clj:732–771 (40) | reference-code/clojure/src/clj/clojure/core_print.clj:473–506; reference-code/clojure/src/clj/clojure/main.clj:207–345: Throwable->map, ex-triage and ex-str | ~20 | 5 | 100 | **OWNER DECISION** |
| 23 | SCI future and pmap adaptation | src/seon/sci/kernel.clj:669–719 (51) | reference-code/sci/src/sci/addons/future.clj:7–47; reference-code/clojure/src/clj/clojure/core.clj:7108–7167: conveyed futures and pmap | ~20 | 5 | 100 | **OWNER DECISION** |
| 24 | Forgiving source and parser recovery | src/seon/sci/forgive.clj:21–312 (292); src/seon/sci/reader.cljc:615–817 (203) | reference-code/edamame/src/edamame/impl/parser.cljc:275–309; reference-code/edamame/src/edamame/core.cljc:126–154: structured delimiter errors and incremental reading | ~40 | 2 | 80 | **OWNER DECISION** |
| 25 | Repeated individual pulls | src/my/program.clj:106–156 (51); src/seon/cluster/registry.clj:588–628 (41) | reference-code/datahike/src/datahike/pull_api.cljc:541–556: eager, input-aligned pull-many | ~20 | 3 | 60 | **REPLACE** |
| 26 | Missing-name spelling suggestions | src/seon/error.clj:1920–1957 (38) | reference-code/malli/src/malli/error.cljc:245–280, 339–372: edit distance and thresholded spelling suggestions | ~15 | 3 | 45 | **OWNER DECISION** |
| 27 | Counted sliding buffer | src/seon/cluster/agent.clj:107–135 (29) | reference-code/core.async/src/main/clojure/clojure/core/async/impl/buffers.clj:60–81, 121–127: SlidingBuffer and datafy | ~20 | 2 | 40 | **OWNER DECISION** |
| 28 | Display tokenization | src/seon/repl.clj:416–467 (52) | reference-code/edamame/src/edamame/core.cljc:126–154; reference-code/rewrite-clj/src/rewrite_clj/parser.cljc:34–44: parsed forms and lossless syntax nodes | ~25 | 1 | 25 | **OWNER DECISION** |
| 29 | Collection error revalidation | src/seon/error.clj:896–914 (19) | reference-code/malli/src/malli/core.cljc:2688–2711; reference-code/malli/src/malli/error.cljc:288–306: explanation entries include child schema, value and path | ~12 | 2 | 24 | **REPLACE** |
| 30 | Primitive function detection | src/seon/instrument.clj:65–81 (17) | reference-code/malli/src/malli/instrument.clj:15–16, 18–41: primitive predicate and installation exclusion | ~12 | 2 | 24 | **OWNER DECISION** |
| 31 | Schema admission syntax walk | src/seon/schema/admission.clj:198–227 (30) | reference-code/malli/src/malli/core.cljc:2657–2670; reference-code/malli/src/malli/util.cljc:168–187: schema walk and subschemas | ~10 | 2 | 20 | **OWNER DECISION** |
| 32 | Fipp scalar and collection emission | src/seon/print.cljc:475–548 (74), 630–700 (71) | reference-code/fipp/src/fipp/edn.cljc:24–113; reference-code/fipp/src/fipp/engine.cljc:10–70: scalar visitor and document serialization | ~15 | 1 | 15 | **OWNER DECISION** |
| 33 | clojure.test execution | src/seon/test/runner.clj:383–499 (117); src/seon/sci/eval.clj:276–339 (64) | reference-code/clojure/src/clj/clojure/test.clj:710–737: test-var, test-vars and fixture dispatch | ~10 | 1 | 10 | **OWNER DECISION** |
| 34 | Datahike merge and reconciliation | src/seon/program.cljc:704–751 (48); src/seon/reconcile.cljc:263–330 (68) | reference-code/datahike/src/datahike/versioning.cljc:738–769; reference-code/editscript/src/editscript/core.cljc:20–90: merge parents and structural diff | ~0 | 4 | 0 | **OWNER DECISION** |
| 35 | File and subprocess adapters | src/seon/fs/jvm.clj:160–275 (116); src/seon/cluster/process.clj:146–218 (73); src/seon/shell/jvm.clj:245–302 (58) | reference-code/babashka/fs/src/babashka/fs.cljc:227–276, 407–429; reference-code/babashka-process/src/babashka/process.cljc:156–170, 453–500 | ~0 | 4 | 0 | **OWNER DECISION** |
| 36 | nREPL sessions and transport | src/seon/mcp.clj:171–303 (133); src/seon/turn/batch.clj:891–969 (79) | reference-code/nrepl/src/clojure/nrepl/middleware/session.clj:161–259; reference-code/nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:72–179: sessions and evaluator | ~0 | 3 | 0 | **OWNER DECISION** |

**1. Ordered values in Datahike.** Gap: Datahike tuples do not provide Seon’s arbitrary ordered, duplicate-preserving vectors of component references. The schema bridge builds cells, decodes pulls and lowers queries; its 386-line span is not just serialization. Options: use native tuples for suitable scalar fields and explicit component entities elsewhere (2–4 days, smaller surface); retain cells as an approved extension (no deletion); add ordered values to the maintained Datahike fork (at least a week, storage compatibility work). Native homogeneous tuples are capped at eight elements; calling every vector a tuple is incorrect.

**2. Query dependency capture and replay.** Gap: Datahike supplies source/attribute dependency plans; Seon additionally proves that writes to other entities sharing those attributes leave a retained read unchanged. Its query walker sometimes executes additional queries to discover bindings. Options: accept conservative attribute invalidation and delete pattern capture/replay machinery (2–3 days, more rerenders); move precise read evidence into Datahike’s existing query execution (4–7 days, preserves precision without rerunning joins); retain the extension after a measured case shows that precision pays. No speedup from deleting it is established here.

**3. Source signatures and a second analysis pass.** Gap: Seon needs authored binding structure and renamed local identities, while clj-kondo’s exposed arglists can reflect authored :arglists metadata and do not supply every binding relationship Seon stores. Options: consume clj-kondo’s existing arity/metadata fields and stop storing unsupported detail (2 days); expose exact bindings from the maintained analyzer’s existing parse (3–5 days, recommended); retain the reparsing with its exact-source regression (no deletion). Do not replace declaration parsing with macroexpand: that executes macros and changes the observation boundary.

**4. Transaction prevalidation and component traversal.** Gap: Datahike validates transaction grammar, native types and references, but not Seon’s complete Malli owning values, multiple owners, component cycles or application deletion rules. The native final-report hook is already used. Options: remove only duplicated submitted-value normalization and validate the final report through that hook (2–4 days, must preserve attempted-write refusals); reduce admission to native Datahike rules (1–2 days, explicitly weaker); retain complete-owning-value validation as an approved policy extension. Deleting the whole writer would remove guarantees the dependency does not make.

**5. The read, evaluate, record, print loop.** Gap: clojure.main offers read/eval/print/caught hooks, but not Seon’s per-form observation plus one durable settlement that preserves a value when a later stage fails. Options: use clojure.main/repl as the outer loop with Seon recording in hooks (3–5 days, prove continuation and SCI bindings); keep a small data fold over the existing stages (2–4 days, recommended); choose plain prepl behavior (1 day, loses durable agent semantics). The existing socket server already comes from clojure.core.server; it is not a duplicate server.

**6. Printer selection and layout prediction.** Gap: Fipp lays out documents and limits collection depth/length, but does not allocate Seon’s shared token budget, retain paths for runnable continuations, or produce linked hiccup. Seon already uses Fipp for final text layout. Options: choose nodes using the budget and let Fipp alone decide breaks (2–4 days, modest appearance changes); accept Fipp’s ordinary limits (1–2 days, loses continuation/budget guarantees); preserve only measured width-sensitive selection (approved extension). Do not count all printer lines as a replaceable pretty printer.

**7. Call preparation and arity planning.** Gap: Malli validates declared arguments but does not fill omitted arguments or map members from an evaluation’s world by schema identity. Seon already derives arities with Malli. Options: require explicit world arguments and delete supply planning (2–4 days plus caller conversion); retain a small supply adapter before Malli’s instrumented function (3–5 days, preserves ergonomics); move supply into a maintained Malli extension only if it serves other consumers. A validation wrapper is not a replacement for supply; never prefix an already-matching arity.

**8. Flow work launching.** Gap: a Flow proc processes serially and reports errors, while this launcher adds bounded concurrent submissions, interruption handles and exactly one completion callback after actual exit. Options: represent each admitted job as an ordinary proc and put counts in proc state (3–5 days); keep a small bounded executor adapter with Flow channels (2–3 days); retain the current launcher only after its extra state is justified. Flow’s compute timeout is a timed get, not proof of cancellation or termination. Seon’s graph executor and join also wait for actual worker exit, which Flow stop does not promise. The three per-submission atoms are additional ownership machinery, not Flow state.

**9. SCI context regeneration.** Gap: sci/fork isolates subsequent definitions, but does not merge a changed base into an existing fork while preserving private Var identities and closures. Options: keep the agent context and advance only changed declarations through SCI (2–4 days, recommended); make replacement contexts discard private state (1 day, behavior change); retain the regeneration merge (no deletion). The ordinary acquisition path can reuse a held context, so this body is not evidence of a whole-world copy on every call. Measure its actual entrance frequency before ranking it as a hot path.

**10. Schema error prose.** Use Malli error-message/with-error-messages on the original explanation and retain only Seon’s operation, source and result-handle adapter. Gap: none for explaining a failed schema; Seon’s exact English template is presentation, not a validation requirement. Delete schema-expectation, bound-text and the parallel type vocabulary, then convert their prose consumers together (1–2 days). Keep custom :error/message on the schema that owns it. Regression: missing keys, numeric/collection bounds and nested paths remain data; do not freeze the current sentence wording.

**11. Schema references and inherited maps.** Gap: Malli’s form equality does not prove equal meaning under different local registries, and its default merge lets the later entry win instead of refusing conflicting inherited members. Options: retain only those two policy checks around Malli walk/merge callbacks (2–3 days); use native merge semantics (1 day, changes requiredness/conflict rules); move scope-aware equality into the maintained fork (2–4 days). Canonical-reference attribution must preserve the registry that resolved the name; a keyword-only seen set is unsound.

**12. Separate caches around dependency results.** Move long-lived derived-result retention into the existing seon.cache kinds; Caffeine already supplies eviction and concurrent retention. Gap: no missing cache capability was found for these ordinary retained values. Keep the exact content/connection/generation keys; replacing a key with a commit id alone is not equivalent. Estimate 1–2 days to convert these owners and their retention tests, deleting 40–80 lines. Do not move Malli’s own schema caches, clj-kondo’s disk cache or a per-call memo merely to enforce uniformity.

**13. clj-kondo input and cache management.** Gap: clj-kondo’s cache is indexed by namespace, while Seon must analyze captured bytes without publishing rejected branch definitions as shared dependency facts. Options: feed all captured files in one run with cache disabled and reuse only dependency input evidence (1–2 days); expose an immutable dependency-analysis input through the maintained analyzer (3–5 days); retain private mirrors with the library managing their parse/cache (no deletion). Disabling disk cache does not erase definitions analyzed in that same run; the source explicitly retains them. Do not blame mirror overhead for measured indexing latency without profiling it.

**14. Caffeine entry claiming and completion.** Gap: Seon requires caller-thread derivation outside the map update, nested cache lookups and refusal values returned to waiters without retention; the atomic mapping callback must not update other mappings. Options: use AsyncCache.get with derivation dispatched outside that callback and carry the evaluation bindings (1–2 days, changes execution thread); retain putIfAbsent claiming but delegate completion where possible (half a day); request a Caffeine seam for caller-thread post-claim work. Exceptional completion also triggers dependency logging. Keep weighted entries and per-kind accounting separate from the concurrency choice.

**15. Bounded database neighborhood acquisition.** Gap: Datahike pull follows a selector; Seon chooses neighbors by schema connections and viewing distance and supplies continuations for acquisition bounds. Options: compile a selector once from those choices and delete the repeated shallow-pull assembly (2–4 days); use ordinary pull selectors as the user contract (1–2 days, changes navigation); keep the custom frontier only for continuation/distance semantics. Datahike’s default many-pull limit at this pin is nil: the local claim about avoiding a fixed 1,000 cap is stale, not a reason to maintain another pull engine.

**16. Blob reachability through Datahike indexes.** Gap: Datahike marks its index objects, not arbitrary application digest values stored inside datoms; Seon’s extension traverses PersistentSortedSet internals to collect those values. Options: use Datahike queries for digest attributes (1 day, may repeat shared-subtree reads); expose an attribute-value fold at Datahike’s index owner (2–4 days, recommended); approve the current traversal with dependency regression coverage. Keep Konserve sweep and Datahike’s reachability permit; there is no second collector to delete. The dependency extension already exists, so a new Seon storage API is unnecessary.

**17. Konserve blob staging and verification.** Gap: Konserve stores a supplied key and streams bytes; it does not choose a content digest or atomically tie a blob’s visibility to a Datahike root transaction. Seon already delegates storage and range reads. Options: retain hashing and the reachability permit but trust successful backend writes (1 day, deletes reread verification); add backend digest verification while writing (2–3 days, avoids rereading each blob); retain full reread verification (no deletion). Current publication hashes during staging and reads the whole blob again even when its key already exists: cost is at least proportional to blob length, not just the requested prefix.

**18. Schema registry construction and admission.** Gap: Malli compiles schemas but permits shapes Seon disallows for stored declarations, including unrestricted polymorphism without Seon’s admission metadata. Options: retain domain admission over Malli’s compiled nodes and delete only duplicate grammar checks (1–2 days); accept every Malli-valid schema (1 day, weakens admission); retain current policy after listing which consumer needs each restriction. The registry already composes Malli lazy/simple/composite registries and retained compiled objects; replacing all 4,000-plus schema lines with a registry call is not supported by this evidence.

**19. Ordered namespace loading.** Gap: clj-reload is present as reference source but absent from deps.edn and the running namespace set; its order is dependents-first, while Seon requires dependencies-first and has its own cycle error value. Options: add the dependency and reverse topo-sort with a cycle callback (half a day, deterministic tie order changes); use clj-reload’s complete unload/load lifecycle (2–3 days, must prove arming and loaded-program identity); retain two local sorts only with owner approval. The isolated comparison below verifies chain order, not equivalence of all tie orders or reload effects.

**20. Malli union error selection.** Gap: Malli reports all failed :or alternatives, while Seon chooses one using shared map keys, depth and problem count. Options: use Malli’s full explanation and let the value printer budget it (half a day); change suitable schemas to a discriminated :multi (1–2 days, explicit model change); retain the ranking only for a demonstrated undirected union. Do not remove unrelated problem attribution while replacing the ranking. Exact English and a particular “closest” branch are separate product choices.

**21. Namespace reader state.** Gap: Edamame tracks ns aliases and exposes parsed require/import options, while Seon also tracks in-ns, literal runtime require, refer renaming and supplied publics without evaluation. Options: use parse-ns-form for ns clauses and retain only runtime transitions (1 day); require an evaluating SCI context for resolution (2 days, removes static-reader independence); retain a minimal static adapter. Its current grammar already uses clojure.core.specs.alpha; do not replace that with another hand-written libspec grammar. The executing path already delegates syntax-quote resolution to SCI.

**22. Error chains and clojure.main triage.** Gap: Throwable->map follows causes without a visited set and omits suppressed exceptions; Seon requires bounded/cycle-safe evidence and SCI source attribution. Options: keep one safe traversal and feed clojure.main’s triage (half a day, already largely composed); accept native exception conversion (small deletion, weaker termination/evidence); improve Clojure’s conversion upstream before removing Seon’s traversal. The custom triage input reads only what ex-triage consumes. Replacing it with full Throwable->map can increase work and hang on a cycle.

**23. SCI future and pmap adaptation.** Gap: SCI’s future addon conveys SCI bindings, but its compiled pmap calls host future-call and bypasses Seon’s arm and owned-work count. Options: add an injectable future-call to the maintained SCI addon and remove the copied pmap (1 day, recommended); remove pmap from the supplied toolkit (half a day, less capability); retain this 27-line algorithm copy as an explicit exception. Seon’s future-call itself already delegates to clojure.core/future-call; its extra counting must remain until actual body exit, not cancellation.

**24. Forgiving source and parser recovery.** Gap: Edamame reads Clojure and reports delimiter/token locations, but does not turn model prose into comments or decide which missing delimiter repair preserves intent. Options: require valid Clojure input and use parse-next directly (1 day, removes forgiveness); use Edamame errors to make repairs explicit and require resubmission (2 days); approve only the smallest lexical repair pass (2–3 days). The current balancing and token scans duplicate lexical work but implement a separate permissive-input policy. They are not evidence that Edamame lacks a reader.

**25. Repeated individual pulls.** Use pull-many for the complete function/test metadata batches in caller-data and configuration rows in retention-cutoff. Gap: none for these eager consumers; preserve missing-result positions and turn a batch refusal into a refusal, never rows. Keep caller arity queries and the history/retention calculation. Estimated half to one day and 10–30 lines deleted. The benefit is fewer wrapper/evidence crossings and shared selector work, not a claim that Datahike stops touching each requested entity. A lazy prefix consumer would be a different case.

**26. Missing-name spelling suggestions.** Gap: Malli’s public spelling pass handles schema extra keys and invalid dispatch values, while Seon ranks the nearest eight function names even when all are poor matches. Options: use Malli’s suggestions for schema failures and list namespace publics for unresolved functions (half a day); expose a public generic suggestion function in the maintained Malli fork (half a day); retain the ranking with owner approval. Copying Malli’s private distance function again is not a fix. Do not claim with-spell-checking directly implements the existing function-name UI.

**27. Counted sliding buffer.** Gap: core.async’s sliding buffer does not report how many values it overwrote. Options: use async/sliding-buffer 1 and omit the dropped count (half a day, recommended if signals are rederivable); add a small observation hook at the dependency buffer (1 day); retain the copied buffer as an approved observation extension. The copy has its own linked list, protocol implementation and atom solely to preserve that count; no queueing algorithm is missing upstream.

**28. Display tokenization.** Gap: displayed replies can be incomplete or contain printer elisions that are not valid Clojure; both semantic reading and structural parsing may refuse them. Options: display plain escaped text (half a day, deletes lexer); tokenize valid source with rewrite-clj and leave failed regions plain (1 day); retain the small lossless lexer. It is presentation classification, not a second evaluator. Do not feed displayed text back into SCI just to obtain highlighting.

**29. Collection error revalidation.** Delete collection-member-problem from the diagnostic path and consume the child errors already returned by Malli’s original explanation (half a day). Gap: none when the producer passes that explanation, as instrumentation does. For a synthesized problem lacking children, explain the original schema once at that producer rather than scanning a collection in the prose layer. This avoids validating collection members again after the failed validation and preserves the actual failing path. The implementation slice must convert synthesized-problem callers together.

**30. Primitive function detection.** Gap: the exact predicate exists but is private; public instrument! excludes primitive functions while also performing installation. Options: expose the existing predicate from the maintained fork and call it (half a day, recommended); use the private Var deliberately with a pin/parity test (smallest edit, private coupling); stop offering a separate armability prediction and report actual installation (1 day). The Seon docstring claiming it asks Malli’s rule obscures that its body is a copied implementation.

**31. Schema admission syntax walk.** Gap: house findings run on authored forms before complete admission, including unresolved or malformed forms that an ordinary Malli compile can refuse. Options: run house checks over Seon’s already-existing structural-schema and Malli walk (1 day, recommended after malformed-input parity); perform compile failure reporting first and drop speculative house findings (half a day); retain the raw walker for malformed forms only. Its hand-coded :map/:multi child selection is a real second grammar, not a necessary part of checking names.

**32. Fipp scalar and collection emission.** Gap: Seon’s node stream carries hiccup, reference links and explicit elisions; ordinary Fipp EDN documents carry text layout only. Options: use Fipp’s scalar/document constructors and keep the hiccup adapter (1 day); use text-only rendering everywhere (1–2 days, loses links); retain the dual-consumer node representation but remove any second width engine under the earlier printer item. Fipp already owns layout, so replacing emit-text alone promises little deletion. Clojure’s printer already owns readable scalar spelling through literal.

**33. clojure.test execution.** Gap: clojure.test supplies fixtures/assertions, not branch custody, SCI namespace objects, stored definition evidence, duration refusal or partial test-ns-hook selection. The runner already calls test/test-vars; no second assertion engine was found. Options: retain only event capture and world preparation around it (1–2 days); use plain clojure.test for host-only execution (half a day, loses branch evidence); keep the current adapter after removing separately proven global-state restoration. Do not count test selection, persistence and dependency reach as duplicated test execution.

**34. Datahike merge and reconciliation.** Gap: Datahike merge explicitly requires caller-supplied transaction content; it does not resolve three-way conflicts by program declaration identity or reconcile exact entity ownership. Options: retain the small semantic decision with Datahike performing the write (recommended); use one branch wholesale (1 day, loses selective acceptance); choose editscript diffs as presentation only (half a day, no semantic deletion). These are application rules over a dependency-owned branch mechanism, not evidence that Seon rebuilt branching or transaction serialization.

**35. File and subprocess adapters.** Gap: the dependencies provide traversal and process creation/destruction, but the inspected APIs do not establish Seon’s no-follow relative-handle custody or verified process identity plus actual tree exit. Options: adopt ordinary path/process semantics and delete the stronger policy (1–3 days, owner must accept it); keep the narrow policy adapters and delegate all copying/spawning (already substantially done); add the missing hooks to the existing dependencies. This is included because deps.edn is in scope; it does not justify silently imposing those policies elsewhere.

**36. nREPL sessions and transport.** Gap: nREPL sessions own host bindings and evaluation queues, not SCI branch custody, Seon settlement or the MCP protocol; nREPL is not a declared runtime dependency here. Options: keep MCP as an adapter to the existing batch path (no transport rewrite); add nREPL only as another entrance using that path (2–4 days, adds capability but no demonstrated deletion); replace MCP tooling with nREPL clients (several days, product change). There is no verified drop-in deletion estimate. The loaded namespace check returned false.

## Already delegated: do not create a replacement project

The scan also checked the following owners. These are exclusions from the deletion estimate, not additional exceptions requested from the owner.

| Owner | Existing dependency composition | What remains application-specific |
|---|---|---|
| src/seon/instrument.clj:613–680, 823–937 | malli.core/-instrument, core.cljc:3166–3187 | World supply, profiling and conversion of the dependency report. Malli already selects arities and checks input/output/guard. |
| src/seon/schema.clj:3824–3837, 3910–3924 | malli.core/validator and explainer, core.cljc:2672–2711 | Projection lookup and named missing-schema refusal. The old claim that write-validator stores a second plain validator is stale: baseline src/seon/db.clj:3771 calls m/validator directly. |
| src/seon/cluster/registry.clj:205–260; src/seon/cluster/store.clj:644–715 | Datahike branch!/branch-as-db and connection acquisition, versioning.cljc:273–323, 457–501 | Roster facts, custody and retirement policy. No whole-store copying in ordinary branch creation. |
| src/seon/cluster/source.clj:847–1038 | Datahike merge through its writer, versioning.cljc:738–769 | Selection by declaration identity, proofs and accepted digests. |
| src/seon/cluster/registry.clj:561–587 | Datahike gc-storage, Konserve sweep! | Root extension and retention policy; not another sweep implementation. |
| src/seon/blob.clj:367–377 | konserve.core/bget-range, core.cljc:658–671 | Digest lookup and caller range. No full-blob range-read replacement is needed. |
| src/seon/sci/reader.cljc:491–571 | sci/parse-next+string; SCI delegates parsing to Edamame | Recording source spans, policy and recovery. |
| src/seon/sci/arm.clj:1–60; src/seon/sci/observe.clj:19–60 | SCI observers and namespace state | Seon’s facts and wrapper selection. No second interpreter. |
| src/seon/await.clj:75–153 | core.async/alts!! and timeout; timed deref/get | Shared deadline and a typed report naming the missing completion. No independent scheduler. |
| src/seon/sci/kernel.clj:61–116, 919–1002 | SCI :interrupt-fn and sci.interrupt/interrupt! | Deadline/owned-work policy. Arbitrary host calls remain outside SCI interruption. |
| src/seon/edit.clj:1–end; src/seon/edit/jvm.clj:48–159 | rewrite-clj zipper; parser.cljc:34–44 | Exact original byte splice, source precondition and filesystem effect. |
| src/seon/web/extract.clj:1–14; src/seon/web/jvm.clj:264–270 | Jsoup parse/text | Selection of already-captured bytes. No second HTML parser found. |
| src/seon/schedule.clj | cron-utils parser/execution-time objects | Nominal instant identity and repeated-local-minute policy; the scan found no second cron grammar. |
| src/seon/render/hiccup.clj; src/seon/render/web.clj | Datastar/http-kit transport and Reitit routing | Render selection, read evidence, bounded delivery and hiccup. These are not supplied by Fipp or nREPL. |
| src/seon/ai.clj | data.json and the host HTTP client | Provider request/stream normalization; none of the requested dependency families implements it. Langchain4clj is reference-only and was not treated as an installed substitute. |
| src/my/*, task/message/config/health/profile/effect/turn owners | Thin entrance adapters and ordinary Clojure data operations over the above owners | Application identities, durable observations, routing and policy. No dependency API was found that replaces these domains wholesale. |

The extra-cache row is intentionally distinct from dependency-owned caches. Malli’s -cached (core.cljc:353–363) retains derived operations on schema objects; it is already used. A schema projection’s extra arity/selector products are not automatically plain-validator duplication. Likewise clj-kondo’s in-run resolution, Datahike’s query/index caches and Konserve’s backend locking are not Seon implementations to delete.

## Runtime and cost evidence

`bin/seon status` and MCP `runtime_status` both reported **:faulted** on default, at basis 536871984, commit 6ac73174-b1cb-585d-abd2-05109e7f788b. The running source archive was 608305d3c1bb5dcd6807ec2118d13e901cb12cec, not the research baseline. Existing health evidence showed:

| Installed armed function | Calls | Mean ms | Maximum ms | Existing task handle shown by health |
|---|---:|---:|---:|---|
| seon.fn/analyzed-files | 18 | 1434.221 | 7932 | none shown; missing task association is a finding |
| seon.cluster/adopt-entities! | 17 | 1576.614 | 2774 | 5dbb7de36bdb |
| seon.cluster.source/publish! | 16 | 1800.829 | 4302 | 9665c2ed25f0 |
| seon.fn/index! | 16 | 1749.824 | 4129 | 12037fb1e077, 84fdc131a599 |

These are observed aggregate profiles, **not attribution to any census item** and not parent/change measurements. No implementation performance claim is made from them. Heap live was 1,136,656,384 bytes (about 1.06 GiB); heap used at the MCP observation was 2,336,464,888 bytes; heap maximum was 10,997,465,088 bytes. The shared cache held an estimated 349,151,092 of its 512,000,000-byte budget. Health showed six open faults, 47 errored receipts, 12 regressions and a task-ai render refusal associated with existing handle 68f9fc8b8da1. No reset, adoption or recovery was attempted.

A read-only JVM evaluation, agent root / cluster default, returned result/e7d7380159b27 in **1 ms**, with no diagnostic in its envelope. The exact availability query was:

```clojure
(mapv (fn [s]
        [s (boolean (and (find-ns (symbol (namespace s)))
                         (ns-resolve (symbol (namespace s)) (symbol (name s)))))])
      '[malli.core/walk malli.core/validator malli.core/-instrument
        malli.error/with-spell-checking malli.error/humanize
        datahike.query/query-dependency-plan
        datahike.pull-api/pull-many-with-evidence
        sci.core/fork sci.core/parse-next+string
        clojure.core.async.flow/start clojure.test/test-vars
        clojure.main/ex-triage edamame.core/parse-ns-form
        clj-reload.core/reload nrepl.server/start-server])
```

All entries returned true except clj-reload.core/reload and nrepl.server/start-server. This checks loaded Vars, **not replacement semantics**. It neither requires new namespaces nor defines functions in default. Source citations elsewhere are source guarantees, not claims of installed behavior.

The one isolated algorithm measurement used Babashka and the pinned clj-reload source, outside default’s JVM. It loaded only the exact reload-order form extracted from the archived cluster.clj and supplied its throwing refused! helper. No production reload occurred. The graph was a chain with ascending namespace names depending on the next name: a deliberately adverse case for Seon’s ascending search. Both implementations returned the same order for each measured chain. Three warmups and five samples:

| Namespaces / edges | Seon samples, ms | Reverse clj-reload topo-sort samples, ms |
|---|---|---|
| 50 / 49 | 0.425, 0.420, 0.411, 0.409, 0.410 | 0.244, 0.240, 0.252, 0.244, 0.243 |
| 200 / 199 | 7.626, 7.436, 7.354, 7.512, 7.279 | 3.300, 3.295, 3.154, 3.125, 3.086 |
| 500 / 499 | 51.403, 53.042, 53.546, 53.093, 52.415 | 20.618, 20.541, 21.082, 21.506, 20.624 |

Both algorithms repeat work over the remaining graph and are quadratic on this chain; this experiment does not justify inventing a third sorter. It supports considering the maintained implementation for simplicity. Babashka timing is not compiled JVM timing, the input is synthetic, no retained-size comparison was made, and independent namespaces can have a different valid tie order. Each measured call was under one second. Total shell measurement execution was 0.65 s.

Reproduction (the measurement code is preserved here because this lane owns only this report): extract the form beginning `(defn reload-order` and ending before `(defn- namespace-requires` from baseline src/seon/cluster.clj into an owned temporary file, then run the following with `bb --classpath reference-code/clj-reload/src`, substituting that temporary filename.

```clojure
(require '[clj-reload.parse :as reload] '[clojure.set :as set])
(defn refused! [message data] (throw (ex-info message data)))
(load-file "tmp/reinvention-astra/reload-order.clj")
(defn elapsed [f]
  (let [start (System/nanoTime)] (f) (/ (- (System/nanoTime) start) 1e6)))
(doseq [n [50 200 500]]
  (let [names (mapv #(symbol (format "n%04d" %)) (range n))
        deps (into {} (map-indexed
                       (fn [i x] [x (if (= i (dec n)) #{} #{(names (inc i))})]) names))
        ours #(reload-order (set names) deps)
        library #(vec (reverse (reload/topo-sort deps)))]
    (assert (= (ours) (library)))
    (dotimes [_ 3] (ours) (library))
    (println {:namespaces n :edges (dec n)
              :seon-ms (vec (repeatedly 5 #(elapsed ours)))
              :clj-reload-ms (vec (repeatedly 5 #(elapsed library)))})))
```

Cost questions left open are explicit: precise-read capture versus conservative invalidation; full-blob reread versus backend verification; cache derivation thread changes; printer width selection; and context regeneration entrance frequency. Their source costs are identified above, but no controlled candidate exists in this research lane. A slice must measure the same real input before/after before claiming a win.

## Pinned dependency evidence

| Dependency | Revision or version read |
|---|---|
| Malli | 43c6dca8d2d8df3162b13725939954807be4a4ed |
| Datahike | 86c3301a00de8c49b2690083b2b1e69677bf0c8d |
| SCI | ce0aa53b9bf35029e0bc27a962d799c196a37874 |
| core.async Flow | dc35f3e0d7bc2eef502e77982f48641f025c8051; declared 1.10.874-alpha3 |
| Caffeine | declared 3.2.4; exact upstream tag v3.2.4 sources |
| clj-reload | 61c6fa77855efc0b4d052f06c7cc8b2b8817c4aa; reference only |
| clj-kondo | 57252e07975710aa579b24f0d1b2b1e04195caa2 |
| Fipp | 255a19061fdc20d67eca09a17e9af282aa8bf909; declared 0.6.29 |
| nREPL | f3a044153eaa6050e7447655dab5bc42cfdd68c2; reference only |
| Clojure | 3bc2b3e91fdff462620d84bbdfd40379d9c562e5; declared 1.12.5 |
| Konserve | 8700ca7a95784bbe5d6fb4dce1d7bc2ad96cbc37 |
| Edamame | 196ea5e4eff390428d8cfd33661cc415a2eec76b |

Caffeine has no reference-code checkout in this tree. Its verified source is [AsyncCache.java at v3.2.4](https://github.com/ben-manes/caffeine/blob/v3.2.4/caffeine/src/main/java/com/github/benmanes/caffeine/cache/AsyncCache.java#L54-L98) and [LocalAsyncCache.java at v3.2.4](https://github.com/ben-manes/caffeine/blob/v3.2.4/caffeine/src/main/java/com/github/benmanes/caffeine/cache/LocalAsyncCache.java#L78-L108). The former specifies atomic loading and the mapping restriction; the latter implements computeIfAbsent and completion handling. No jar/source version equivalence beyond the declared version and tag is claimed.

## Landing and foreign boundary

Only docs/research/agent-platform/2026-10-08/reinvention/reinvention-astra.md is claimed and changed. No source, schema, test, skill, default prototype, provider request or another lane’s session was changed. The report’s baseline is preserved in Git; temporary inventory/measurement files were confined to tmp/reinvention-astra.

Foreign boundary at entry: uncommitted src/seon/db.clj, error.clj, fault.clj, schedule.clj, cluster/{agent,registry,status}.clj, src/my/agent.clj, associated error/registry schemas and tests, and dependency checkout changes. Those were not repaired, restored, staged or included. The archive baseline records the subsequent committed Datahike pin; the initial running system remained on its older archive. Working-tree line numbers in those owners can therefore differ from this report.

`clj-kondo --lint src script test --cache false --parallel` completed in **5051 ms: 0 errors, 1469 warnings**, exit status 2 from warnings. This satisfies the assignment’s zero-error requirement, not a zero-warning claim. It did not write the shared analysis cache. No tests reach this Markdown change: docs are outside seon.test.cache/input-roots. No namespace tests were selected, and no test run or boot is claimed. Existing health regressions are reported above; this lane did not run or triage them, so it cannot label individual test Vars green or red.

Commit through `bin/lane commit reinvention-astra -m MSG PATH` uses the documented documentation-only path: no program publication, no test widening and no throwaway boot. Adding arbitrary --test entries would prove unrelated code and violate the repository’s rule that documentation does not widen gates. The requested deliverable is evidence and owner choices; implementation tests belong to the chosen slice. Net source/test lines: **0 / 0**. Commit identity is supplied by the lane result, never by an amend of this document.
