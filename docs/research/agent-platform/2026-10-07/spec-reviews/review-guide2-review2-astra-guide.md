# Second-round review — review2-astra-guide

Revise before treating this as an executable reference. The first round's major data-loss, premature-test-deletion, printer and backpressure findings are fixed. Remaining blockers concern error propagation, crash semantics and the owner's newer task/error rulings.

Read-only review of the uncommitted working tree. Only this report was written. No tests, evaluations, lint, contract compilation, lifecycle operations, commits or delegation. HEAD observed during the review: `860fcc15fe`; the revision notes describe earlier `33fb994f2`. Concurrent work was left alone. Source evidence below is not installation evidence. **Tests required for this report-only change: none.**

Abbreviations: **D** = `.agents/skills/data-oriented-clojure/SKILL.md`; **T** = `.agents/skills/clojure-testing/SKILL.md`; **R** = `docs/seon/architecture/reference-code.md`; **A** = `AGENTS.md` (verified `CLAUDE.md -> AGENTS.md`); **E** = `docs/seon/specs/errors.md`; **L** = `docs/seon/specs/agent-loop.md`; **N** = `tmp/clojure-guide/guide-revision-notes.md`. Locations refer to the reviewed working files. Repeated manifestations count once.

## HIGH

### H1. The separate fault model survives, and deleted producers still require green proofs

Contradicts rulings **(a)** and **(e)**. A:36,89,262,273-276,290 still calls Seon's failures faults; D:368 teaches recording a fault. Merely replacing that noun is insufficient: E:243 explicitly defines a separate class of errors based on whether the boundary can hand them to an agent. E:270-272 requires tests even when the producer is deleted, and E:373-375 repeats the general AND rule. E:375,380 still leaves the task home pending/open, although (a) decides it.

Other **target** occurrences needing the same correction:

- E:6,14-15,35,138 (After column),166; §6 heading at 241; 250-266 (proposed row, task attachment, receipt and evaluation/call attributes); 275; 303-308,314-315,328 (retained fault owner),342-344,371-380.
- L:40,44,135,137,153,156,214,245,350,352,377-378,390. Its proposed `fault-signatures` repeats the retired model.
- N:45,138,190-192 overstates alignment or preserves the old target wording. N:17's dated runtime `:faulted` observation is historical evidence, not a conflicting law.

Existing filenames/functions cited as historical or deletion targets are legitimate: E:11,43,69-74,170,268,276-278,286,316-317,357-358,376; L:258,266,280,323,382. Keep those identifiers when naming what retires, rather than falsely renaming source that still exists.

**Replace A's recording/delivery policy and D:368 with this policy (shorten the example comment to its first sentence):**

> Seon's own failures are errors. Record them at the task home once per signature, with a count, a task and one notification; repeats update the count without another notification. Carry each occurrence on the evaluation or call where it happened. There is no separate fault concept. A recorded error resolves when its producer is deleted. For a producer that still exists, resolution requires the changed producer and the task's current green test evidence.

**Replace E:270-272:**

> A recorded error resolves on deletion of its producer. Otherwise it resolves when the producer's current digest differs from `latest-digest` and every success test naming its task has a green run recorded after `latest-at`. A digest change alone does not resolve a surviving producer's error.

Revise the proposed attributes/owner names consistently with the retired vocabulary, including the slice and test lists. The report does not invent replacement installed attributes.

### H2. Completion still requires an explicit successful complete!, and tests need not exist before assignment

Contradicts **(b)** and omits **(c)**. A:293-294 says the agent marks its task complete. L:231-238 gives `my.task/complete!` the successful-proof path; L:248-249 derives successful disposition from that call. The same wrong mechanism appears in L:313-315 (P5),343-344 (T7), and 379-380's §4 reference. The pass sketch L:97-106 has no post-change success-test step. Deferring `:no-proofs` refusal to completion (L:235) is not prelaunch admission. A:417-420's launch instruction and T:255-302's workflow never state the new prerequisite.

**Replace A:293-294's completion sentences and L:231-238's mechanism:**

> A task must name its success tests before it can be assigned or launched. After a pass changes code reached by those tests, the loop runs them against the resulting program. All green completes the task; green tests are the only success. `my.task/complete!` is solely the agent's explicit bailout for a broken or impossible task: it records the reason and returns the task to its assigner. The task listener delivers the resulting notification.

Update the pass, P5, disposition and T7 to this mechanism. Add admission proof that missing success tests prevent both assignment and launch, and bailout proof that a reason is recorded, the assigner receives the task, and no successful completion is recorded. These are requested target behaviors, not claims that tests with those names are installed.

### H3. The new crash law promises rollback of effects that already committed

A:286-290 says a crash before settlement “leaves the database unchanged.” This is false even for the target. L:159-163 explicitly preserves completed message/task/file/remote effects before settlement; L:345-346 and 386 preserve that distinction. A:289-290 itself requires consequential operations to be durable before answering. N:164-167 repeats the false wording, and N:190-192 calls it alignment.

**Replace the crash sentence:**

> TARGET (agent-loop cut): a crash before settlement loses that pass's evaluation and call bookkeeping, not effects already completed by its forms. The next pass derives work from committed facts; an effect can repeat unless its owner makes it idempotent.

Keep the single synchronous settlement and its durability requirement. Do not claim that all form effects belong to that one transaction.

### H4. Throwable->map is prescribed despite the spec's explicit cycle/suppression requirement

D:313-324,659 and R:37 contradict E:67-76. The pinned `reference-code/clojure/src/clj/clojure/core_print.clj:494-501` follows `.getCause` without a seen-set and never traverses suppressed exceptions. It can loop forever and loses cleanup evidence. `src/seon/error/refusal.clj:181-190` already has a cycle-safe cause walk; `test/seon/error/refusal_test.clj:218-245` protects suppression and cycles.

The claimed EQUIVALENT signature rewrite is also false without an acyclic-chain precondition: `src/seon/test/runner.clj:85-88` can stop at an outer signature before entering a downstream cycle; eager `Throwable->map` never returns. “Only where a boundary already holds the map” does not repair the general boundary instruction. N:209-211's refinement wrongly retains the equivalence claim.

**Replace D:314-324 and R's chain instruction:**

> The owning boundary walks cause and suppressed edges once, with identity-based cycle detection, and passes the resulting referenced links onward. Use `Throwable->map`'s native link shape as the data model, not `Throwable->map` as the traversal: it is neither cycle-safe nor suppression-complete. Reuse the existing chain owner until the errors cut replaces it.

Delete D:659's unconditional equivalence row. An already-derived chain may be searched without another Throwable traversal.

### H5. The worked returning-tool catch swallows every typed error

D:363-364's `error-of` accepts any truthy `:seon.error/type`; it does not identify the tool's own alternatives. E:121-131 requires a cached validator of the tool's exact `:multi`, propagating contract refusals/core errors. A foreign typed error returned by this example may become an output-contract error, masking the original failure and blame. D:375's output check is not a substitute for correct catch selection.

**Replace step 3 (a TARGET fragment):**

```clojure
;; tool-error? is the cached validator of :my.fs/tool-error,
;; compiled from the tool's owning projection and supplied here.
(catch clojure.lang.ExceptionInfo e
  (let [data (ex-data e)]
    (if (tool-error? data) data (throw e))))
```

Delete the uncontracted `error-of` helper. Say explicitly that this is a catch fragment, not a complete runnable tool: `read-opened request ...` is not an executable call to today's six-argument helper (`src/seon/fs/jvm.clj:429-442`). The TARGET label for `fail!`/`caught` is correct; it does not excuse implementing the wrong target.

## MEDIUM

### M1. New current-tense laws are not consistently separated from uninstalled targets

A:244-248 and D:563-565 say the index resolves backticked references and reports unresolved ones. Installation is **unverified**. The inspected index path stores doc text (`src/seon/fn.clj:786`), derives task links from metadata (`:625-642`), and `src/seon/sci/eval.clj:1888-1916` splits docstrings into prose/examples; none proves the new reference resolver. N:119-124 changes the claimed mechanism without naming its installation proof.

A:257-263's universal return/throw policy is the intended errors migration, not current behavior: `src/seon/db.clj:3005-3021` still declares returned read errors, and `src/seon/test.clj:2236-2243` still lists `:or` variants rather than the target `:multi`. A:291-294 states the new live-context loop in the present tense despite N:191-192 explicitly admitting the old loop runs. R:38 similarly states the target report adapter as current Seon behavior. D:379's “Every function is contracted” also omits A:235's explicit exception for legacy code.

**Add at each affected rule, rather than only in revision notes:**

> TARGET: this is the required behavior after its owning cut. Until its producer and callers are converted and adopted together, preserve the current installed path and its error propagation. A source declaration or this rule is not installation evidence.

For doc references specifically: “Author references as backticked qualified names. TARGET: the index resolves them into subject context and reports unresolved references; verify the resolver before relying on that behavior.” For contracts: “Contract every new or changed admitted function; legacy uncontracted code is not back-filled wholesale.”

### M2. The remaining pull-many “EQUIVALENT” row changes error and evaluation semantics

D:669 advertises `(map #(db/pull db sel %) ids)` → `(db/pull-many db sel ids)` as equivalent merely because results align. The first is lazy, with a returned error in an individual slot; the second is eager and can return one error for the entire batch. Compare `src/seon/db.clj:3005-3021` with `:3053-3071`, and `reference-code/datahike/src/datahike/pull_api.cljc:541-555`. Bounds/read evidence also move from individual requests to a batch. A consumer taking one result need not read all ids today.

**Replace the row's label/condition:**

> BEHAVIOR CHANGE in evaluation, bounds and refusal shape. Use `pull-many` when the consumer needs the complete batch and handles its whole-batch refusal. Successful values remain input-aligned; preserve the caller's required evidence and resource scope.

D:616-617's absolute “N reads … become one query” should likewise say to batch when the complete set is needed, not prescribe eager acquisition for prefix consumers.

### M3. The initialization decomposition is not a pure core, and its new contracts omit the important shape

D:117-179 calls `initialization-round` pure. Its `entity-ready?` calls `lookup-resolution`, which calls `db/pull` (`src/seon/cluster.clj:1083,1100-1103`). Pull appends captured read evidence (`src/seon/db.clj:2983-2984`; sink mutation `:570-580`). An immutable database does not make the entire instrumented call free of effects. Halving readiness calls changes observations/work; it is not a proof of full operational equivalence.

The example's round contract (D:124-126) permits `{}` and arbitrary `::refused :map`, with neither ready/waiting required. `refuse-round!` accepts plain `:map` (D:146). These do not declare the two verdict shapes that the prose says exist, contrary to D:379-403. `missing-lookups` says “unresolved” (D:135), but `lookup-refs-in` collects all lookup refs, including those already resolved (`src/seon/cluster.clj:1054-1067`). That behavior matches the original and should be described honestly.

**Replace the decomposition introduction:**

> This splits a readiness-read round, refusal construction and transaction effects. The round still uses Seon's read owner and captures its evidence. With finite map inputs and stable successful reads, the returned nil, transaction groups/order, metadata and refusal payloads are preserved; readiness-predicate calls are halved. This is not a pure-function example or proof of identical read observations.

Declare the round as an `:or` of a required `::refused` lookup/refusal map and a map requiring both `::ready` and `::waiting`; reuse that shape on `refuse-round!`. Describe `missing-lookups` as “The distinct lookup refs mentioned by waiting entities, as refusal data.”

Reading finds no syntax/arity error in the four definitions when placed in `seon.cluster`; the missing final explicit nil is harmless because loop exhaustion returns nil. Contract compilation, admission and runtime equivalence remain **unverified**.

### M4. The absence section overstates what schemas admit

D:51-52 says a union, open map or optional key admits “all four states.” Counterexample by schema meaning: `[:map [:x/k {:optional true} :int]]` permits absence but rejects present nil, false and empty collections. `[:or :string :int]` admits neither nil nor false. Openness concerns undeclared keys, not loosening the value schema of a declared one.

**Replace:**

> `:any` can admit nil, false and empty values. A union admits only its alternatives; an optional map entry permits absence but validates a present value against its declared schema; an open map permits additional keys. Remove a presence or variant guard only when that exact schema makes it redundant.

D:39's keyword sentinel also needs “only if the sentinel cannot be a legitimate value”; `find` avoids that collision. The rest of §0's truthiness, `:or`, `some` and boolean-vs-seq distinctions is correct.

### M5. The origin decision is still open, and the replacement is incompletely stated

Ruling **(d)** is not reflected in L:392, which leaves the name open “beside” the old string. L:39 and 371 mention replacing `:author` without saying that the old `:seon.eval/origin` string retires. The four guide files do not independently contradict the new key, but they defer to this stale authority.

**Replace L:392 and clarify L:39/371:**

> `:seon.cluster.eval/origin` is `[:enum :model :outside :test]` and replaces the old `:seon.eval/origin` string. Convert its producers, receipt schemas and readers together in the loop cut; do not keep both origins as parallel current facts.

Source distinction: `resources/seon/schemas/seon.eval.edn:1` still declares the string; `seon.cluster.eval.edn:94,163` still includes it. This is a target conversion, not a claim that the new field is installed.

### M6. The corrected seven-key example still needs its renamed arguments

D:204-217 says the namespace gains aliases, but its body uses `ctx`, `db` and `conn`. The current arglist is `[database connection agent-id caps render-context]` (`src/seon/render/web.clj:691-702`). Alias declarations do not define these locals. Dropped fields are fixed; literal replacement of the body still does not compile.

**Exact addition before the snippet:**

> This body also renames the arglist to `[db conn agent-id caps ctx]`; keep the five-argument order unchanged. The namespace gains the aliases listed below.

Alternatively keep the existing local names throughout the snippet. Sol's first-round suggested body had the same omission; do not count that review's suggestion as compile evidence.

## LOW

### L1. Two dependency descriptions still say more than their citations

- D:242-243 says the pinned `select-keys` uses a not-found sentinel. `reference-code/clojure/src/clj/clojure/core.clj:1566-1570` actually uses `RT/find` and the returned map entry. Presence semantics are right. Replace “uses a not-found sentinel” with “uses `find` and copies the returned entry.”
- R:45 says “The library loops answer per form.” nREPL does; SCI's cited `eval-string*` evaluates each form but returns only the final value at EOF (`reference-code/sci/src/sci/impl/interpreter.cljc:106-114`). Replace with “SCI reads/evaluates each form and returns the last result; nREPL sends a response per form. Seon records all evaluated forms in its single settlement.”

### L2. Residual absolutes and an incomplete cache-key explanation reduce teaching value

D:551-561,681 mandates a 1–3-line docstring while also allowing public `my.*` examples/sections. Apply the limit to the summary, not the whole docstring. The extra blanket bans on a `§` character and any design rationale in source exceed A:244-248's actual law; D:569's own “comment why” needs room for the reason a non-obvious implementation is correct. Replace with:

> Keep the summary to 1–3 lines. Public `my.*` documentation may add the declared sections. Put history and extended design discussion in Git, tasks or the owning spec; keep a short invariant or reason beside code when it prevents a wrong edit.

D:600-602 says `call-reach-key` is derived from two attribute revisions. The copied call body is correct, but the key also includes connection, generation and conservative revision, and has a separate as-of key (`src/seon/render.clj:847-874`). These are the high-value part of this example: two equal attribute revision values from different worlds do not prove equal content (`reference-code/datahike/src/datahike/query.cljc:3010-3029`). State those components and the as-of rule. Do not simplify the example into a two-revision key.

## First-round HIGH/MEDIUM disposition

IDs below belong to the named first-round report, not the findings above. “Resolved” addresses that original claim, not an assertion that the whole document is now safe.

| Astra finding | Disposition and current evidence |
|---|---|
| H1 unsafe truthiness rewrites | Resolved: D:241-251,462-471,656,663-665 adds nil/false, metadata and boolean conditions. |
| H2 lost request/settlement data | Partially: all seven members and staged writes restored (D:204-217,478-494); body locals still need M6. |
| H3 test exemption/deletion | Resolved: T:19-34,143-168 keeps entrance/atomicity/delivery proof, helper assertions and replacement in the cut. |
| H4 premature unwrap/guard removal | Resolved: D:298-311,326-336,437-446,646,657,661 keeps existing unwraps and exact guard conditions. |
| H5 printer quota | Resolved: R:46 expressly rejects sink truncation as Seon's printer. |
| H6 dropping buffer/backpressure | Resolved: R:50,75 separates wake coalescing from pending-byte admission. |
| H7 global-state permission | Resolved: T:284-288 treats preservation helpers as cleanup, not permission. |
| H8 missing merge seam | Resolved: R:61 states the existing serialized fence/validator seam and caller obligations. |
| M1 clj-reload scan called pure | Resolved: D:183-194,524-525; R:47 labels scan effectful and uses topo-sort. New decomposition issue is M3 above. |
| M2 misdiagnosed Seon examples | Resolved in the named examples: D:68-71,237-239,510-512,527-530,612-627 preserves real obligations and labels sketches. |
| M3 arbitrary universal style | Partially: most are review prompts (D:56-60,198-200,221-223,255-256,450-455,514-517,576-585); residual docstring/source absolutes remain L2. |
| M4 weakened/undeclared contracts | Resolved for the original interrupted-warning and shape-shortening guidance (D:379-403,396-398). New round contracts are M3. |
| M5 domain messages/report path | Partially: D:338-343 and R:38 fix domain prose, failure-only spelling and throwing report. Chain recommendation now contradicts revised E; H4. |
| M6 false testing counterexamples | Resolved: T:38-41,70-94,115-135 corrects private reach, string exceptions, property law, printer scope and doc-example claims. |
| M7 no complete minimal tests | Resolved structurally: T:175-253 provides three complete tests with real helpers; signature inspection below. |
| M8 misleading area table/missing areas | Resolved: R:26-30,40,42-46,51-53 separates idiom/obligation and adds the areas. |
| M9 stale seams | Resolved: R:60-75 repairs merge, currency, nil default limit, timeout and drain ranges. Query “correction” remains explicitly unverified. |
| M10 missing semantic discipline | Partially: absence, registration, decomposition, batching and scope added (D:33-52,73-179,401-433,616-639); M3/M4 remain. |

| Sol 6.1 finding | Disposition and current evidence |
|---|---|
| H1 schemas do not prove refusal | Resolved: T:19-23,202-229. |
| H2 premature deletion | Resolved: T:141-173. |
| H3 future throwing-read semantics | Resolved for the deletion instruction: D:298-311,443-446,646-657; global current/target wording still M1. |
| H4 lost input/staged writes | Partially: D:204-217,478-494 restores data; M6 above. |
| H5 printer model | Resolved: R:45-46. |
| H6 socket bytes | Resolved: R:50,75. |
| M1 generic equivalence | Resolved for its named rows: D:241-251,465-471. New batch row M2 remains. |
| M2 stale dependency claims | Resolved for scan, reload return shape, merge, limits and principal ranges: D:183-194,317-320; R:47,60-75. New chain-spec conflict and small descriptions are H4/L1. |
| M3 absolute style | Partially: prompts replace the major rules; L2 remains. |
| M4 misdiagnosed owners | Resolved: D:68-71,237-239,527-539,612-627; previous max-query criticism removed. |
| M5 harness limits/global restore | Resolved: T:284-293; actual-exit owner and duration checks agree with source. |
| M6 messages/guard conditions | Resolved: D:338-343,379-403,437-446. |
| M7 architecture analogy/coverage | Resolved: R:26-30,40-46,51-53. |
| M8 testing examples | Partially: T:70-94 fixes tables/property/regex; a complete bounded Seon property example is still absent, though the existing law is named. This is an optional teaching omission, not grounds to delete it. |
| M9 missing useful examples | Partially: real tests, registration and cache example now exist; M3/M4/L2 identify their remaining limitations. |

**Wrongly rejected?** N:196 correctly says no finding was disproved outright. The settlement vector precondition (N:198-201) is a sound refinement: the old `(fnil conj [])` prepends to a list. Constructor validation and `:my.fs/not-found` correction (N:202-206) agree with current source (`refusal.clj:743-784`, `my.fs.edn:121`). Deferring an unverified Seon merge-call citation and condensing streaming advice are reasonable. The chain-equivalence refinement at N:209-211 is the wrong retention, for H4's concrete termination counterexample.

## Worked-example verdicts

| Example | Reading-based verdict |
|---|---|
| T:183-197 identity table | Syntactically complete; arities and digests match `src/seon/id.clj:95-112` and `test/seon/id_test.clj:5-10`. Fixed printing at `id.clj:47-56` supports nil/false, symbol/string, 1/1N and insertion-order cases. No execution claimed. |
| T:206-223 refused write | Matches `test/seon/reset_edges_test.clj:72-83`, reduced to one attribute. Positive before-state plus request-id and unchanged basis remains useful. `with-database` one-arg arity and finally release verified at `test_support.clj:841-882`. Requires a running test member, correctly stated at T:176-177. |
| T:235-247 invalid SCI call | Exact helper sequence/source shape exists at `test/seon/sci/admit_test.clj:528-542`. One-arg `fork-cluster-ctx` (`test_support.clj:319-343`), two-arg `agent-value` (`:437-462`) and its evaluate path (`:370-435`) match. TARGET assertions use E's future report fields; label correct. |
| D:73-179 initialization | Definitions fit existing helpers and preserve transaction grouping/order under the stated successful-read conditions. No missing tail nil bug. Pure/equivalent claims and contracts need M3; no compile/adoption/run proof. |
| D:348-375 error flow | TARGET constructor and schema shape agree with E's target, distinct from today's `:my.fs/not-found`. Not runnable as one block (schema entries, unresolved locals/imports, ellipsis, incomplete tool call). Catch is substantively wrong, H5; recording vocabulary H1. |
| D:33-52 absence | Core truthiness distinctions correct; schema generalization and colliding sentinel require M4. |
| D:392-398 naked-key contract | Current `interrupted-warning` contract is `src/seon/turn.clj:243-254`; rewriting registered `[k k]` to `k`, and optional `[k opts k]` to `[k opts]`, preserves it in `seon.turn`. `::missing-results` must be read in that namespace. |
| D:415-433 registered total input | Valid schema-entry sketch for adding inside the resource map, not a complete replacement resource file (`resources/seon/schemas/seon.schema.admission.edn:1` is a map). `src/seon/id.clj:41-45` provides the exact consumer shape; `schema/internal.cljc:152-220` recognizes exemption/reason/generator. Same admitted values/generator after registration; publication/admission unverified. The name is explicitly proposed, so “EQUIVALENT once adopted” is an acceptable conditional label. Add braces only if presenting a whole file. |
| D:478-494 settlement | Correct for absent/vector staged writes: preserves all prior writes and strips staging from receipt, matches `src/seon/turn.clj:189-209`. Correct conditional EQUIVALENT label; nil would also happen to work. |
| D:604-609 cache | Exact four-argument `seon.cache/lookup` usage from `src/seon/render.clj:876-891`, supported by `cache.clj:160-184`. Nil-key fallback correct; full key teaching needs L2. |

All compile/run judgments above are **by reading**, not tests. Proposed test namespaces are not claimed to be installed or green. T:228-229's placeholder is correctly TARGET rather than guessed current evidence.

## Every changed reference-code citation: checkout audit

All 20 library checkouts named at D:24-28 matched their HEAD gitlinks when checked; none was fetched or changed. Pins: babashka-process `43bdd65e18`, clerk `4c25999462`, clj-kondo `57252e0797`, clj-reload `61c6fa7785`, clojure `3bc2b3e91f`, core.async `dc35f3e0d7`, datahike `d9121bbbad`, datastar-clojure `1cef624e9e`, edamame `196ea5e4ef`, editscript `b493ccf2e9`, fipp `255a19061f`, http-kit `f56bbeaf97`, hyperlith `b08a8e8689`, kaocha `8846f91c9b`, konserve `8700ca7a95`, langchain4clj `889f9e60e3`, malli `43c6dca8d2`, nrepl `f3a044153e`, rewrite-clj `60782e501a`, sci `ce0aa53b9b`.

I opened the cited ranges, including unchanged citations embedded in changed rows. The ledger groups repeated citations; paths below begin at `reference-code/`. **Supported** means the source supports the narrowed library claim, not Seon's installation. Bare definition-line citations were followed into their implementation where needed.

| Opened citation(s) | Verdict |
|---|---|
| `rewrite-clj/src/rewrite_clj/zip/move.cljc:8-26`; `zip.cljc:275,309-311,335,343` | Supported: small movement composition and zipper interfaces. |
| `clj-reload/src/clj_reload/parse.clj:163-175`; `core.clj:185-191,206-229,270-381` | Supported with corrected pure-sort/effectful-scan split; failure return adds `:exception`. Ordering also inspected at `core.clj:236-257`. |
| `clj-reload/src/clj_reload/core.clj:54-55,60-65,313,322-325` | Supported: one state atom and lock; not a purity guarantee. |
| `malli/src/malli/core.cljc:203-207,247-260,353-361,717-718` | Supported: constructor/thrower, composition, per-object cache, destructuring. |
| `malli/src/malli/core.cljc:2210-2245,2672-2712,3166-3187` | Supported: report-return does not block function entry; cached validator/explainer and wrapper interfaces. |
| `malli/src/malli/error.cljc:44-172,245-280,288-306,332-390` | Supported: message table, private distance implementation, public explanation/spelling/humanize functions. |
| `malli/src/malli/util.cljc:128` (read through 143) | Supported with limit: closes implicitly open maps recursively; explicitly open maps remain open. |
| `malli/src/malli/registry.cljc:11-96` | Supported: own protocol, copied HashMap fast registry, persistent simple registry, composite/lazy composition. |
| `malli/docs/function-schemas.md:526-530`; `docs/reusable-schemas.md:116-120` | Supported as library patterns. The latter uses a documentation-local `register!`, not an installed Seon API. |
| `malli/test/malli/core_test.cljc:382-389,2836` | Supported: commented table branches and actual private-Var reach. |
| `clojure/src/clj/clojure/set.clj:78-87` | Supported: rename-keys copies present entries. |
| `clojure/src/clj/clojure/core.clj:1559-1571` | Presence claim supported; implementation description wrong, L1. |
| `clojure/src/clj/clojure/core.clj:7054-7065,7298-7310,7355-7363,8136-8150` | Supported: eager/transient builders, ordered grouping buckets and metadata-preserving update-vals. |
| `clojure/src/clj/clojure/core_print.clj:473-507` | Native link shape supported; proposed traversal use rejected, H4. |
| `clojure/src/clj/clojure/test.clj:708-737`; `core/server.clj:275` | Supported: test/fixture dispatch and io-prepl entry point; not Seon's evidence/resource guarantees. |
| `clojure/test/clojure/test_clojure/string.clj:90-104` | Supported as a table of exception-class cases, now correctly qualified. |
| `datahike/src/datahike/api/impl.cljc:30-42`; `api/specification.cljc:429` | Supported: delegate/argument normalization and final-report-validator contract. First is a transact example, not proof of read semantics. |
| `datahike/src/datahike/db/transaction.cljc:27-32,998-1015,1172-1173,1225-1234,1296` | Supported: error data, ref sweep, writer-time db, non-nil rejection (false rejects too), final validator invocation. |
| `datahike/src/datahike/pull_api.cljc:16,315-323,541-555` | Supported: nil default, explicit truncation, eager aligned pull-many. Does not prove D:669 equivalence, M2. |
| `datahike/src/datahike/versioning.cljc:212-279,323-367,457-501,550-588,738-756` | Supported: shared branching, exclusive-custody force, materialization/release, copy warning, arg-map merge. |
| `datahike/src/datahike/writing.cljc:883-911` | Supported: merge routes through transact/fence/validation then adds lineage. |
| `datahike/src/datahike/query.cljc:3010-3029`; `db.cljc:423-442` | Supported: generation/connection/conservative/attribute currency. No newly established transaction-time bug; guide properly marks that old correction unverified. |
| `datahike/src/datahike/datom.cljc:271-315,325-359` | Supported: comparator delegation and supported-type considerations. |
| `datahike/src/datahike/gc.cljc:22-81,144-167` | Supported: heads/parents/cutoff and shared visitation; unlink is not instant erasure. |
| `datahike/test/datahike/test/lookup_refs_test.cljc:11-26` | Supported: one setup, value/error tables; not a Seon fixture. |
| `fipp/src/fipp/edn.cljc:9-21`; `visit.cljc:8-52`; `engine.cljc:10-46` | Supported: document construction, visitor protocol/type dispatch, node multimethods; no claim of Seon's budget implementation. |
| `babashka-process/src/babashka/process.cljc:95-114,165-173,215-221,674-708` | Supported: resource-bearing exception, signalling descendants, function-valued defaults and merged shell options. Exit is separate. |
| `sci/src/sci/impl/interpreter.cljc:94-114` | Bindings/reader loop supported; “answers per form” needs L1. |
| `sci/src/sci/core.cljc:260-276,334-340`; `impl/utils.cljc:410` (read 394-418) | Supported: intern, fork and inherited-Var copying; not deep object/JVM isolation. |
| `sci/doc/interrupt.md:52,63-87` | Supported: interpreted interruption does not stop arbitrary host calls. |
| `nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:72-180`; `src/java/nrepl/out/QuotaBoundWriter.java:26-37` | Supported: injected reader/evaluator and character sink truncation. |
| `edamame/src/edamame/core.cljc:126`; `test/edamame/core_test.cljc:21-30` | Supported: parse-next entry and exact data/parse assertions. |
| `core.async/src/main/clojure/clojure/core/async.clj:150-160` | Supported body-wrapping macro. |
| `core.async/src/main/clojure/clojure/core/async/flow.clj:136-155,165-200,265-286,291-309`; `src/test/clojure/clojure/core/async/flow_test.clj:34-41` | Supported: responders-only ping, describe/workload, step arities and map adapter. |
| `core.async/src/main/clojure/clojure/core/async/flow/impl.clj:29-36,257-260,271-320` | Supported: future submission, timed get and proc loop; timeout is not termination. |
| `clerk/src/nextjournal/clerk/viewer.cljc:1470-1497` | Supported: explicit viewer then first predicate; Seon's separate order is correctly attributed. |
| `hyperlith/src/hyperlith/core.clj:24`; `impl/datastar.clj:42-49,122-181` | Supported: comment example and subscribed/coalesced refresh with non-drain-waiting send. |
| `http-kit/src/org/httpkit/server.clj:345-350`; `src/java/org/httpkit/server/ServerAtta.java:44-66` | Supported: pending-byte snapshot and drain/close completion, no automatic bound. |
| `datastar-clojure/libraries/sdk-http-kit/src/main/starfederation/datastar/clojure/adapter/http_kit.clj:23-76` | Supported: SSE callback/resource adapter. |
| `kaocha/src/kaocha/testable.clj:105-137`; `type/var.clj:30-45`; `plugin/filter.clj:10-99` | Supported: type dispatch, result/count merging and plan filtering. |
| `langchain4clj/src/langchain4clj/core.clj:249-262` | Supported: provider-dispatched model construction; no wire-fold claim. |
| `editscript/test/editscript/core_test.cljc:196-205` | Supported as the explicitly qualified 2,000-trial dependency law, not Seon's fixed-seed fixture. |
| `konserve/test/konserve/memory_test.cljc:22-23`; `src/konserve/compliance_test.cljc:14` | Supported helper-call pattern; literal absence of is does not establish vacuity. |
| `clj-kondo/src/clj_kondo/core.clj:67,242-270`; `impl/cache.clj:171-215` | Supported: cache merging before findings and retention of in-run definitions with disk cache disabled. |

Caffeine has no reference checkout citation; R:42 appropriately names the Seon owner and jar. All other new Seon-owner links are source leads, not verified installed APIs. This audit finds no missing pinned path among the reference-code citations.

## CLAUDE.md sentence-change audit

This covers N:104-185's old→new edits against the actual A diff.

| Changed sentence/group | Verdict |
|---|---|
| A:108-110 dual review | Correct normative law, exactly ruling (f): fresh Opus plus Astra, independent. This report supplies one review only; it does not claim the other review happened. |
| A:124-125 fifteen-minute fixes | Normative law; ownership exception retained. No accidental expansion into another lane's files. |
| A:244-248 docstrings/costs/references | Authoring/cost rules clear; reference-resolution mechanism unverified target, M1. Task references must not be mistaken for the metadata proof links currently indexed. |
| A:249-252 grounded vocabulary | Removing “turn and evaluation” fits target retirement of turns, but remaining laws at A:26,150-158,180-183 still describe turns/session-owned evaluations. Mark the architectural transition explicitly; L:370 targets evaluations directly on the agent. |
| A:257-263 return/throw | Matches E's target carrier division, with exact-returned-variant rule retained. Current-vs-target problem M1 and retired fault word H1. Do not remove load-bearing read unwraps yet. |
| A:264-272 error shape/constructor | Constructor signature and dropping at/layer explicitly TARGET: good. References-not-copies retained. Type/message requiredness belongs to the cut too (E:39-42,312-314), not a claim that all current base maps already require them. |
| Deleted “one way to build, store and show” sentence | Constructor uniqueness and producer identity remain; printer laws A:299-339 remain. No independent weakened-law finding, but add H1's one-recorder rule rather than relying on deleted prose. |
| A:286-290 recovery | One synchronous durable settlement preserved; removal of redundant “no asynchronous” does not license fire-and-forget. Crash/database clause wrong, H3; pass model needs TARGET. |
| A:291-295 work/context/completion | Live context is target, M1; completion contradicts newer ruling, H2. Removal of rewrite-history prohibition here is covered by A:324-325's stored-history rule. |
| A:374-377 bound reasons/profile evidence | Clear law; keeps default, measured justification and distinction between acquisition and execution. T:289-293/source runner now accurately separates elapsed check and actual exit. |
| A:421-424 removed duplicate lane-proof sentence | No proof obligation lost: A:118-123,127-136,364-373 retain archive/installed proof, reporting and centrally owned broad tiers. |

## Usability, size and verification limits

The revision is substantially more usable: law versus prompt, explicit conditions, positive pre-state, real fixture signatures, and dependency limits are worth the growth. Highest-value missing content is now the exact post-pass completion/admission/bailout rule, cycle-safe error boundary, and full cache-key explanation—not another catalogue of stylistic rewrites. A complete bounded Seon property example would be useful but is optional. Avoid repeating unsafe abbreviated conditions in §14 after qualifying them correctly earlier.

Measured without a tokenizer, using the notes' stated **Unicode characters / 4** method:

| File | Estimated tokens |
|---|---:|
| D | 10,470 |
| T | 4,917 |
| R | 4,274 |
| Guide total | **19,660 ≈ 19.7k** |
| A, additional | 10,779 |
| Guide plus mandatory A | **30,439 ≈ 30.4k** |

Thus ~19.7k is right for the three guide files only. Exact model-token count is **unverified** (`tiktoken` unavailable); no package was installed. No need to pad toward the earlier requested size.

No runtime status/probe was taken, so N's historical degraded-runtime counts are not current evidence. No foreign breakage blocked this read-only work. Root needs no tests for the report or prose corrections. If turning the samples into code, the existing named checks to select centrally are `seon.id-test/data-shape-and-explicit-length-determine-identity`, `seon.reset-edges-test/analysis-is-a-required-positive-fact`, `seon.sci.admit-test/an-absent-storage-bound-refuses-and-names-the-key-it-wanted`, and (for actual chain changes) `seon.error.refusal-test/a-cleanup-failure-survives-beside-the-primary`. The decomposition would need its own refused-read/no-ready/group-order regression through its owner; no installed test name was verified for that proposed edit. E's T2/T7/T9 and revised L T7/admission/bailout obligations are target specifications, not installed test names.

## Counts and files read

**5 HIGH, 6 MEDIUM, 2 LOW.** No runtime, compilation or test pass claimed.

Files read, whole documents or relevant source spans (search-only hits excluded):

- The four reviewed files D/T/R/A; CLAUDE.md symlink target; E/L; N; `tmp/model-trial/review-guide-trial-astra-guide.md`; `tmp/model-trial/review-guide-trial-sol61-guide.md`.
- Every reference-code file listed in the citation-audit table, at its recorded checkout pin.
- `src/seon/cluster.clj`, `src/seon/render.clj`, `src/seon/render/web.clj`, `src/seon/turn.clj`, `src/seon/db.clj`, `src/seon/id.clj`, `src/seon/fn.clj`, `src/seon/cache.clj`, `src/seon/sci/eval.clj`, `src/seon/fs/jvm.clj`, `src/seon/error/refusal.clj`, `src/seon/schema/admission.clj`, `src/seon/schema/internal.cljc`, `src/seon/test.clj`, `src/seon/test/runner.clj`.
- `test/seon/test_support.clj`, `test/seon/id_test.clj`, `test/seon/reset_edges_test.clj`, `test/seon/sci/admit_test.clj`, `test/seon/error/refusal_test.clj`.
- `resources/seon/schemas/seon.schema.admission.edn`, `resources/seon/schemas/my.fs.edn`, and the relevant declared origin/bound members of `resources/seon/schemas/seon.eval.edn`, `resources/seon/schemas/seon.cluster.eval.edn`, `resources/seon/schemas/seon.test.edn`.
