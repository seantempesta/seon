# Review: mandatory Clojure reference guide

Lane: trial-sol61-guide. Reviewed 2026-10-07 against Seon HEAD 33fb994f24c91d846e873edc6a547ed5e7d22b3d plus the uncommitted drafts.

**Do not make this draft mandatory unchanged.** Its strongest material is worth keeping: explicit data, named schemas, small functions with distinct responsibilities, batched reads, reuse of compiled validators, and tests through the public entrance. The main defects are instructions that turn reasonable preferences into unsafe mechanical transformations or delete positive proof. More tokens would help if they buy complete, correct examples.

This was a source review only. No tests, evaluations, lint, contract compilation, commits, JVM operations, or tracked-file edits were performed. Loaded runtime APIs and health are **unverified**. Existing foreign changes, including test/my/examples_test.clj and the dirty http-kit checkout, were left untouched; they did not block review. Findings are recorded here rather than as tasks because only this report may be written.

Below, D means .agents/skills/data-oriented-clojure/SKILL.md, T means .agents/skills/clojure-testing/SKILL.md, R means docs/seon/architecture/reference-code.md. Reference paths are relative to reference-code/.

## High severity

### H1. A declared contract does not prove the refusal behavior; the named example is misdescribed

T:18-20 and 146-148 say the contract and REPL warning prove refusal on every call and permit deleting the test after m/explain rejects its input. An input validator proves membership in a schema. It does not prove that the production entrance is armed, that the writer validates the final owning value, that a refusal leaves state unchanged, or that the correct variant reaches the reply. A warning is not enforcement.

The counter-example actually checks a positive analyzed digest, refusal of two attribute retractions, a returned write-attempt identity, and an unchanged basis (test/seon/reset_edges_test.clj:72-83). It is not a test of a cardinality stated with :min 1. Deleting it after explaining a map would discard the transaction invariant.

This conflicts with CLAUDE.md:262-263 and 359-370, and with errors.md:275-285 (T1, T3 and T7 explicitly require returning/armed error behavior).

Replace T §1 item 1 and deletion item 3 with:

> Do not add a test that merely re-expresses a schema's shape. Tighten the producer's schema, and keep one regression through the real entrance proving the relevant refusal, delivery and unchanged state. m/explain is a useful schema probe; it is not proof that the production path enforces that schema. Delete duplicate shape assertions only after identifying the regression that preserves the behavior.

### H2. Deletion is authorized too early, and by implementation shape rather than protected behavior

T:21-25 and 140-161 permit deleting tests because a subject is private, renamed, condemned for future deletion, or exercised through with-redefs. Those are reasons to inspect or convert a test, not proof that its observable behavior is obsolete. T:160-161 even allows removing the only proof and filing a future task. Root discussion protects only some of the cases (T:163-165); a surviving public seon.* behavior can lose all proof.

The reviewed specs require deletion with the machinery and per-test triage (errors.md:287-292; agent-loop.md:269-275). CLAUDE.md:369-370 requires replacement behavior tests to land with the cut. Private implementation does not make the public requirement private.

Also, “no is” is not vacuity (T:157-158). The guide's own GOOD compliance test has no is in its body: konserve/test/konserve/memory_test.cljc:22-23 delegates to assertions at konserve/src/konserve/compliance_test.cljc:18-30. support/assert-check! also reports an assertion internally (test/seon/test_support.clj:783-787). The runner counts assertion events, not source spellings (src/seon/test/runner.clj:325-340).

Replace the deletion-policy introduction and affected items with:

> Classify the behavior before deleting its test. Delete a duplicate only when another current test proves that behavior through the same entrance. Delete an obsolete test in the slice that removes its mechanism. If a private-Var or stand-in test protects surviving behavior, replace it with an entrance test using an injected world in the same slice. Renaming alone does not delete behavior. Count actual assertion evidence, including assertions in helpers; absence of a literal is is not evidence of vacuity.

### H3. The quick guide applies future throwing-read semantics to today's callers

D:170-172 acknowledges reads throw only after S4, but prohibits new unwrap blocks before then and tells agents to “fix what you touch.” D:471 simply instructs replacement with a bare throwing call. D:493 turns probing error keys into a checklist defect. An agent following the summary can remove necessary propagation while reads still return refusal values.

The installed source of my.program/caller-data checks five returned reads (src/my/program.clj:111-144). current-run can return a read refusal, and require-open-run propagates it before checking run state (src/seon/turn.clj:325-354). Removing that distinction prematurely turns a read failure into some downstream result/refusal. Errors S4 explicitly couples removal of read catches and conversion of callers (errors.md:263-265).

Replace D §3.2's rewrite and the quick-table row with:

> After the read owner and all affected callers have been converted in errors S4, use the bare throwing read. Before that conversion, retain propagation of returned read refusals through the existing owner. Verify the installed behavior before deleting an unwrap or narrowing a caller's output contract; source plans do not prove runtime installation.

D:136's “S1 is installed” is source-supported, but runtime installation is unverified in this review. Say “S1 is present in the checked source; verify the loaded program before relying on it.”

### H4. Two advertised shorter Seon rewrites discard real inputs or settlement data

D:81-85 removes :seon.sci.eval/time-limit-ms, :seon.render.value/root and :seon.sci.admit/caps from debug-turn-request. They are explicit additions in src/seon/render/web.clj:691-702 and need not already be in render-context. This is a keyword-spelling example presented as a rewrite, not marked as an excerpt.

D:295-300 loses any existing :seon.blob/staged-writes. The current settlement-projection appends the newly staged blob to those writes, removes the staging key from the receipt in both branches, and returns every staged write (src/seon/turn.clj:199-209). The proposed no-EDN branch instead leaks the staging key into the receipt and returns no writes.

For the spelling example, retain all keys:

~~~clojure
(merge ctx
       {::db/db db
        ::cluster/name (or (::cluster/name ctx)
                           (get-in ctx [::loop/cluster ::cluster/name]))
        ::db/connection conn
        ::agent/id agent-id
        ::sci.eval/time-limit-ms (::config.eval/time-limit-ms ctx)
        ::value/root [::agent/id agent-id]
        ::admit/caps caps})
~~~

Declare the additional aliases. For the settlement example, replace the snippet with this preservation-first body, or remove the claimed complete rewrite:

~~~clojure
(let [writes (vec (:seon.blob/staged-writes evaluation))
      receipt (dissoc evaluation :seon.blob/staged-writes)]
  (if-let [edn (:seon.eval/value-edn receipt)]
    (let [staged (blob/stage! (::db/connection cluster) edn)]
      [(-> receipt
           (dissoc :seon.eval/value-edn)
           (assoc :seon.eval/value-digest (:seon.blob/digest staged)))
       evaluation (conj writes staged)])
    [receipt evaluation writes]))
~~~

These replacements were checked by source comparison, not executed. Staging effects remain; moving them to the settlement owner is a separate caller-and-contract change. A smaller-looking snippet is not evidence that that movement is complete.

### H5. The recommended printer model violates Seon's whole-value budget and retrieval contract

R:38 recommends quota-writer clipping with print-length/print-level “before layout.” QuotaBoundWriter.write actually truncates an already supplied character buffer and then throws (nrepl/src/java/nrepl/out/QuotaBoundWriter.java:26-36). It does not select whole nodes, budget by estimated tokens, preserve readable syntax, provide dig forms, or cut before layout. Its implementation counts characters despite its byte-oriented documentation.

CLAUDE.md:298-303 and 330-337 require the whole original value, one value-printer budget, and retrieval information for cuts. The research itself explains why Portal/ordinary printer limits do not supply Seon's missing retrieval contract (tmp/clojure-guide/map-render-web.md:315-337).

Replace the printing recommendation with:

> Read Fipp for document construction and layout. Read nREPL's quota writer only as an example of bounding writes to a sink; it can cut syntax and supplies no retrieval. Seon's value printer selects whole nodes under one token budget and emits runnable retrieval forms before final text or hiccup output. Preserve the original value and never clip the laid-out reply again.

Keep nREPL for injected reader/evaluator functions, with the corrected evaluation-loop range in the citation ledger.

### H6. A dropping wake buffer does not bound queued socket bytes

R:42 says a slow tab skips ticks “instead of queueing bytes.” Hyperlith's dropping buffer bounds refresh notifications (hyperlith/src/hyperlith/impl/datastar.clj:140-147). Its loop renders, flushes an in-memory compressed stream and calls send! without waiting for socket drain (same file:160-175; send!:42-49). It can keep submitting bytes while the underlying server retains pending writes.

R:67's supposed drain citation, http-kit server.clj:321-326, is just close-status documentation. The actual maintained-fork seam is server.clj:345-350. ServerAtta.java:61-66 exposes queued-byte count; :44-58 supplies drain/close completion. This seam is evidence a caller can observe completion, not automatic bounded admission.

Replace the SSE recommendation with:

> Subscribe before the first render. A dropping buffer of one bounds replaceable wake notifications; it does not bound socket output. Admit another write according to the connection owner's pending-byte bound and drain-or-close completion. Read http-kit write-state and its Java implementation for that seam, and retain resource ownership until actual completion.

## Medium severity

### M1. Several generic “equivalent” transformations change value semantics

D:280-284 and 477-485 omit the preconditions for their rewrites.

- first/filter cannot generally become some returning the matching element. With p = some? and xs = [false :later], first/filter returns false; some skips the false match and returns :later.
- cond-> guarded by (:k m) omits false/nil values; select-keys includes present false/nil keys. D:114-121 and 470 change that behavior. Seon explicitly preserves false as a completed fact in src/seon/fn.clj:773-776, contrary to the research's assumption that there are no false flags (idioms-src.md:175).
- update-vals preserves map metadata (the cited core.clj:8136-8150); into {} does not. That can matter for admitted/rendered values.
- not/empty? returns a boolean; seq returns a sequence or nil. D:485 is valid in a truthiness test, not as a replacement for a boolean result.
- group-by stores buckets eagerly and assumes the predicate returns exactly true/false when destructuring those keys. Boolean readiness does satisfy that assumption here; arbitrary truthy predicates do not.

Replace the §6.2 introduction with:

> Prefer one reducing/transducing pass when it preserves the result, order, metadata, evaluation and absence semantics. Keep first/filter when a matching value may be false or nil; some is for a truthy result. Use select-keys to copy present keys, including false; preserve an existing truthiness policy explicitly. Use seq in condition position and boolean when the result contract requires a boolean.

This is the most valuable missing tiny-value example: distinguish absent, nil, false and empty before any mechanical rewrite.

### M2. Citation claims exceed the source; stale seam claims are being promoted as re-read facts

D:47, 332-333 and R:39 call clj-reload/scan pure. It reads a clock, dynamic config and loaded libs, calls filesystem scanning, and may throw (clj_reload/core.clj:206-229). Using it in swap! is not evidence of purity. D:177-186 and R:34,39 call the reload failure map identical in returned/thrown form, but the returned map adds :exception (core.clj:370-381).

R:53 says the merge path lacks the expected-basis/validator contract. The pinned fork provides the arg-map basis fence (versioning.cljc:744-756; writing.cljc:883-911), and Seon accepts parents through db/transact! with ordinary validation (src/seon/db.clj:5913-5915). The current merge consumer also checks touched digests in its transaction function (src/seon/cluster/source.clj:1024-1026). Do not prescribe a new full-head restriction merely because a seam supports it.

R:56 still says many-pull defaults to 1,000. +default-limit+ is nil (pull_api.cljc:16), applied by :315-323. An explicit limit can truncate; that is a different claim. guide-notes.md:91-95 already acknowledges this defect, yet R:29 says every line was re-read.

Exact replacement seam rows:

> Merge accepts caller-supplied content and parents through the serialized writer. Its arg-map supports expected-basis-t, and the ordinary final-report validator applies. Seon checks its chosen touched-entity preconditions in the transaction function; Datahike supplies lineage, not semantic merge conflict resolution.

> Pull's default many-value limit is nil in this pinned fork. An explicit limit applies take without a completeness marker. Index comparison delegates to compare-value; read its supported types and failure behavior before admitting a new value type.

For scan, replace “pure scan” with “scan computes a reload plan while reading filesystem and runtime state”; choose a truly pure transform as the pure-core GOOD example. The full wrong/weak citation ledger follows below.

### M3. Arbitrary counts and absolute style rules encourage extraction, indirection and weaker names

D:25-42 guarantees a 20-40-line result from closure extraction without a design or measurement. D:64-65 says never extract to hold three lines, despite GOOD movement functions whose bodies are one line. D:88 prohibits local destructuring; D:123 arbitrarily replaces more than three positionals; D:263 excludes valid index/worklist loops acknowledged by idioms-src.md:372-388. D:286 forbids a useful named intermediate solely because it is used once. D:400-408 forces one-word locals, even when before/after databases or separate connections need distinct names. D:412-413 says every macro argument must be evaluated once, which excludes legitimate conditional/short-circuit bodies. The pre-commit list strengthens “when it fits” into mandatory one-line contracts (D:496).

D:324-325's protocol restriction is also false as general Clojure advice: Malli's Registry protocol is implemented by its own reified registries (malli/src/malli/registry.cljc:11-28), and Fipp defines and implements its own visitor protocol (fipp/src/fipp/visit.cljc:8-30; fipp/src/fipp/edn.cljc:22-34). Seon can prefer maps without claiming protocols serve only foreign host types.

D:357-364 also allows custody/the evaluation world as ambient, then says any function reading a dynamic Var for data should receive an argument. Clarify the exception: the declared entrance/supply boundary may bind the evaluation world; ordinary functions take their non-custody inputs explicitly. Do not use the exception to add another ambient service or the last sentence to remove the established supply boundary.

Replace the main size/extraction rules with:

> Keep each function responsible for one operation. Around forty lines is a prompt to inspect mixed roles, not a correctness limit. Extract a named concept or reused operation when that reduces the reader's work; inline a trivial single-use wrapper when its boundary adds no meaning. Keep a local closure when its lexical context is the simplest correct owner of a callback. Show the resulting function and its callers before claiming a reduction.

Other precise replacements:

> Destructure where the shape becomes known; prefer the arglist for unconditional input fields. Keep explicit lookup when presence, computed keys or evaluation order matter.

> Prefer an options map for numerous independent options; retain positional arguments when their order expresses the operation.

> Choose loop for a reader, worklist or index/state walk when reduce does not express the work clearly.

> A macro evaluates each expression as promised by its control syntax; prevent unintended repeated evaluation. Put ordinary computation in functions.

> Prefer data dispatch for data variants. Introduce a protocol only for a demonstrated type-based polymorphic boundary, following Seon's restriction on bespoke layers.

### M4. Several Seon examples do not establish the alleged defect or a complete smaller alternative

All named functions exist, but these diagnoses need correction:

| Draft claim | What the source actually establishes |
|---|---|
| D:35-42, replace the traversal volatile with reduce | write-owned-values-error has an explicitly explained callback-owned accumulator (src/seon/db.clj:4105-4108,4140-4149). Its traversal includes ancestor and iterative postorder walks (:4206-4273). Size/mixed roles are real; an ordinary reduce is not a demonstrated replacement for callback charging, memoized reads and bounded graph traversal. Extract roles first; leave algorithm/state replacement marked unproven. |
| D:108-112, receipt-entry is a mirrored map | It adapts evaluation data into a shared transcript-entry shape, derives namespace/time fallbacks and is consumed alongside other kinds by entry-order (src/seon/render/transcript.clj:258-309). It may be simplifiable, but “pass the original map” does not preserve this interface. |
| D:108-112, all admit-declarations! copying and validation go | Besides renaming, it computes defaults, compiled schemas, predicate functions and affected sets (src/seon/schema.clj:4198-4217). Reuse the projection's keys where appropriate, but preserve those derivations and declaration validation. |
| D:275-278, every entity is read twice | filter/remove each call entity-ready? once per entity; that predicate may make several reads. A prior entity-lookup-refusal pass also remains (src/seon/cluster.clj:1110-1125). group-by halves readiness-predicate calls, not necessarily all database reads. |
| D:430-431, three resource probes every call | find-ns short-circuits all resource probes on the loaded path; some short-circuits on the first found resource (src/seon/render/web.clj:675-680). Path rewriting is repeated; zero to three resource checks occur. Profile before adding caching. |
| D:438-442, put max inside the query | previous-ending-namespace already has max in its query (src/seon/turn.clj:570-577). A correct one-query retrieval or the agent-loop owner decision to replace this derivation with current-ns is needed; the proposed instruction changes nothing. |
| D:440-441, complete caller-data batching | Caller identities presently come from both function/test symbols. Its output also uses callee declared arities, filtering, per-call arity expansion and sorting (src/my/program.clj:109-154). The two proposed reads illustrate batching only; they are not the full rewrite. |
| D:354, eight delayed seon.error resolutions | src/seon/db.clj:53-68 has five error resolutions and three call-preparation resolutions. Correct the count and owners. |

The examples that do establish the advertised problem: debug-diagnostic's seven positionals (web.clj:655-666); returned-read unwrap repetition in caller-data; interrupted-warning's repeated inline attributes (turn.clj:240-254); schema-expectation's hand-written validation English (error.clj:865-895); edit-distance's duplicated algorithm (:1949-1960); redundant truthy-copy clauses in var-entity (fn.clj:785 onward); mixed readiness/effects in transact-initialization!; the long function bodies named in D:37-38; and the long error/message docstring (error.clj:99-128).

start-work-launcher! and submit! really allocate the cited sibling atoms (flow.clj:669-679,788-790). But collocating them in one atom is only a sketch: admission, draining, callback ownership and external effects still require a reviewed transition model. D:329 and 482 must lead with “use the existing owner; if an atom is necessary, colocate state that must agree,” rather than making an atom the default.

### M5. Harness guidance loses important limits and endorses global restoration too broadly

T:194-195 licenses deliberate global mutations whenever a preserving helper is used. Those helpers do snapshot/restore instrumentation and live-cluster projections (test_support.clj:1114-1155), but existence does not make whole-JVM mutation safe under concurrent tests. CLAUDE.md:355-357 restricts tests to their own Vars and forbids restoring reloaded roots. This additional conflict is not in guide-notes.

T:196-199 omits that duration-failures runs on reported completion (runner.clj:342-379), while the SCI body arm interrupts interpreted execution and bounded-result separately watches actual exit, retaining the branch while a host body remains live (test.clj:278-318). The old skill distinguished those guarantees; dropping that distinction makes the short “harness today” less useful.

Replace those bullets with:

> Preserve instrumentation and mutate only the Vars owned by the test. Branch fixtures do not isolate JVM Vars/classes or live-cluster projections. A preserving helper is cleanup, not permission to mutate another owner's state or restore roots across reload.

> duration-failures reports an overrun when a body completes. SCI's per-body arm interrupts interpreted work; it cannot guarantee exit from a host call. bounded-result observes actual exit under the request remainder and retains resources for a still-live body. A timeout is not a kill or proof of cleanup.

The fixture/evaluation/assertion helper names in §4 exist and mostly match their source descriptions. Their runtime installation remains unverified.

### M6. “Do not write messages” and “trust the contract” omit essential distinctions

D:204-205 prohibits hand-written messages generally, but the binding constructor requires a producer's message (errors.md:88 onward). Malli explains schema violations; it does not explain domain events such as absent files or failed admission. Its spelling algorithm is private (malli/error.cljc:256); cite the public with-spell-checking composition, not that private function as an API to call.

D:249-250 and 475 prohibit contains?/map? on all armed inputs. An open map with an optional key, a union, or a total predicate still needs legitimate presence/variant checks. Broad :map/:int choices in §4.2 do not establish invariants about members. D:235-237 introduces ::interrupted-warning without declaring it; no such registered output name was found in the checked source/resources. The snippet is a proposed schema extraction, not a compilable replacement.

Replace with:

> Use Malli's explanation and schema messages for validation failures. The producer supplies concise domain messages to the one error constructor. Build spelling suggestions through the public Malli error functions on the refusal path.

> Remove a shape guard only when the exact armed contract entails its condition. Retain union dispatch, optional-member presence and domain predicates. Naming a schema shortens the contract; do not widen a contract to :map or :int merely to shorten it. Declare, compile, publish and adopt any newly named shape before using it.

### M7. The area table should teach transferable seams and limits, not prescribe an identical architecture

R:26-29 says Seon may need more lines “but not a different shape.” Research shows actual differences: blob/refusal/process identity requirements (map-cluster-test-tools.md:175-187), token-budget retrieval (map-render-web.md:315-337), and provider wire/cache policy (map-cluster-test-tools.md:204-215). Mature code is a model for a particular concern, not proof that Seon's different contract fits it.

Replace the introduction with:

> Before implementing, read the row's library owner and Seon's first-party caller. State which guarantee you are reusing, what the library does not provide, and the inputs and cost of the composition. Copy the relevant idiom; preserve Seon's declared behavior rather than copying the library's surrounding architecture.

The area-by-area verdict is below. Missing high-value areas: data modeling/refs/components and transaction-owned validation; central content-keyed cache composition; immutable branch/release/store ownership; source analysis and exact-span write-back. Some appear in the seam table, but no “read first” row connects those seams to an implementation concern. Add those rows, including named Seon consumers and the relevant skill. Do not add a new machinery proposal.

### M8. Testing examples are useful, but two alleged anti-patterns are valid and one sample is misleading

T:64-78 makes every variation an are row. The two named item-8 tests cover distinct total-path behaviors: a throwing read ends reading, while a malformed span preserves the evaluated value (test/seon/run_test.clj:68-83). Combining them because of their prefix obscures the distinction. shown-value-diff-round-trips-changed-paths uses doseq to make two assertions per case, which are cannot directly express as a single expression without hiding assertion behavior (db_test.clj:2595-2603). Tables are good; are is not a universal objective.

The “bad” property cluster-branch-is-one-injective-derivation actually tests injectivity and repeatability with a fixed seed (cluster/registry_test.clj:210-226), exactly a law under T:80. A few examples cannot establish why injectivity testing is excessive.

T:48 inserts a literal ellipsis into the regex. The cited edamame message does not contain that character (edamame/core_test.cljc:21-26), so copying the snippet produces a failing example. T:85-88 is explicitly abridged, but neither demonstrates the promised bounded generators/fixed seed nor Seon's assert-check! entrance. Label it a dependency pattern and add an actual Seon property example.

Replace the table rule with:

> Put cases of the same behavior in a small are table when one expression expresses the assertion. Use doseq with separate is assertions when each case has multiple independent observations. Keep tests separate when their boundary, setup, failure class or resource lifetime differs.

T:58's “renders ... with no world” also needs “pure transforms need no world; a render that reads receives an immutable database and fixed profile.” CLAUDE.md:319-321 explicitly supplies the render's read world.

### M9. High-value research lessons were shortened out, while costly surface rules remain

Keep the big seven concerns, but add complete examples that prevent their actual failure classes:

1. **Missing/false/nil/default semantics.** canon.md:73,110,388,413; restore the practical absence paragraph dropped in guide-notes.md:64-65. H4/M1 show why it belongs beside copying/destructuring.
2. **Positive proof before negative assertions.** The old testing skill's useful “establish before-state, refuse write, independently check after-state” was dropped. T:157 hints at it, but H1's example is then incorrectly condemned. Add one short writer regression preserving basis on refusal.
3. **Named schemas with generators/exemptions.** idioms-src.md:347-367 identifies repeated exemption prose and generator vectors. §4.2's one sentence does not show the actual registered form and shortened function contract. Add the complete owner-schema declaration, a consumer, and its compiled check; do not offer an undeclared ::interrupted-warning.
4. **One real extraction, not a guaranteed target line count.** Show a complete before/after split separating read/derive/validate/effect without hiding charging or changing writer custody. Census.md:595 explicitly says giant-function splitting alone has zero removable lines; splitting should expose/delete plumbing.
5. **Real minimal Seon setup.** idioms-tests.md:291-310 sketches it; T §2.3 names helpers but shows no runnable Seon example. Add one pure case, one canonical writer case and one real SCI entrance case, each asserting only relevant data and handling teardown.
6. **No negative proof from silence; no deferred replacement proof.** Tie the examples to writer failure, error delivery and assertion counts, not screenshots/strings or schema validity alone.
7. **No trivial batch-wrapper/helper layer.** canon.md:405 and census.md:590-594 distinguish parametric reuse from extra wrappers/test-only production helpers. Show one face/operation parameter replacing duplicated bodies, without conflating renderer-specific selection with generic printer logic.
8. **Cost as a falsifiable dependency claim.** Retain measured before/after and changed-input identity. Add one concrete content-valid cache key example; clarify that Malli's per-object cache saves repeated work on the same compiled schema, not every reconstruction of an equivalent form.

T:30 and 159 must distinguish noisy elapsed-time assertions from deterministic work-count requirements. The latter are expressly required by agent-loop.md:260-261: zero eval-unit misses on repeated context, then exactly the settled forms. Replacement:

> Keep measured timings as profile evidence and declared bounds in the runner. Keep deterministic tests of required recomputation, query or cache-miss counts when those counts are the specified behavior.

## Low severity

### L1. Pins and range precision do not match the claimed verification scope

D:18-20 pins eleven libraries correctly against their checked-out HEADs. But T:11-12 and R:28-29 imply that list pins all cited examples. It omits clerk, nrepl, edamame, editscript, kaocha, konserve, clj-kondo, http-kit and datastar-clojure.

Add the checked revisions or say to derive each gitlink/checkout revision as R:16-21 already instructs. Do not call every example pinned by the shorter list. Runtime resolution remains a separate check.

Missing checked-out revisions (shortened here): clerk 4c25999462; nrepl f3a044153e; edamame 196ea5e4ef; editscript b493ccf2e9; kaocha 8846f91c9b; konserve 8700ca7a95; clj-kondo 57252e0797; http-kit f56bbeaf97; datastar-clojure 1cef624e9e. The dirty http-kit status is not a claim that these cited lines are deployed.

### L2. Mandatory-read tokens should buy proof-bearing examples, not repeated mechanical checklists

D:13 claims every rule has GOOD/counter-example/rewrite; several do not (e.g. §1.3, §7.2, §9.2-9.4, §12.5). guide-notes.md:83-84 repeats that claim. Correct it rather than filling gaps with token padding.

D §14 and §15 repeat rules while stripping their conditions, producing H3/M1. Either remove the quick rewrites, or make the precondition part of each row. Cut cross-library count comparisons and word-count/one-word-name prescriptions that are not actionable evidence about this diff. Keep the anti-narration rule, but permit the shortest adequate invariant or ownership explanation; a hard one-line maximum can erase exactly what a future agent needs.

The roughly 13.6k-token size is not excessive for the owner's purpose. It is also not an accuracy credential. Spend additional space on the complete, correct examples in M9 and the limits in the area table. A longer guide with those worked cases would be more useful than this shorter guide.

## Reference-code citation audit

All named citation files and cited symbols exist in the local reference checkouts. “Exists” is separate from whether the cited range supports the claim. The eleven explicitly listed revisions matched. The following are **all wrong or materially weak reference-code claims/ranges found**, including duplicates across the documents; routine arity continuations are not treated as errors unless they omit the claimed behavior.

| Draft location | Citation and verdict/correction |
|---|---|
| D:47,332-333; R:39 | clj-reload/core.clj:304-332 does call scan via swap!, but scan at :206-229 is effectful. Not a pure-core/state-update example. M2. |
| D:177-186; R:34,39 | clj-reload/core.clj:367-381 is a useful return/throw example, but not identical maps: :exception exists only in the returned map. M2. |
| D:125; R:44 | babashka/process.cljc:674-678,704 shows options/default merging, not a maximum of three positional inputs. The full :674-708 shell form supports option normalization and delegation. M3. |
| D:351-352 | babashka/process.cljc:215-219 does not include :program-resolver, which is at :220. Extend the range. |
| D:188-190; R:34 | Throwable->map at core_print.clj:473-500 does supply :via, but the form continues through :507 and builds root stack trace data too (:499-501). Replacing an early signature search with this whole conversion can add work. Prefer the one boundary conversion and reuse its data; no measured performance improvement is established. |
| D:211 | Malli -levenshtein at error.cljc:245-259 exists but is private. The usable seam is with-spell-checking at :332 onward, not a new caller of the private algorithm. M6. |
| D:324-325 | The cited corpus itself contains protocols for owned types: Malli registry and Fipp visitors. The alleged foreign-types-only rule is not supported. M3. |
| R:35 | Malli core.cljc:2210-2240 reports validation then continues if report returns (:2235-2236). “Refuses input” requires a report that stops entry. Explain/humanize are a Seon report composition, not automatic behavior of this wrapper. R:58 states the limit correctly; repeat it in the new row. |
| R:36 | Registry:17-96 supports several Registry implementations, not “a registry is a map” universally. lazy calls a supplied provider; it does not automatically compile arbitrary forms. core.cljc:353-361 caches on the compiled object, not by equivalent form content across fresh objects. |
| R:38 | nREPL interruptible_eval.clj:72-125 sets up seams/caught but ends before the read/eval loop at :131-179. Extend to :180. It has multiple catch sites; “one caught” is one helper, not one catch. SCI eval-string* returns the final value; nREPL sends per-value responses. Neither cited function itself returns a collection of every evaluation. |
| R:38 | nREPL QuotaBoundWriter.java does sink clipping, not pre-layout whole-node budgeting. H5. |
| R:40 | flow.clj:291-305 ends at the fn opener. The map->step delegation is :306-309. Extend to :309 and include process lifecycle arities. “One transform per pass” is the reviewed Seon design, not a restriction that core.async proves. |
| R:41 | Clerk viewer.cljc:1470-1497 proves explicit viewer then first predicate match. It does not prove a registry was “ordered once” or predicates cannot overlap. Say deterministic first match according to supplied order. |
| R:42 | Hyperlith buffer/flush code supports coalescing wakes, not bounding pending socket bytes. H6. Datastar ->sse-response:23 is only the start of its docstring; read its body through :76 for callback/resource ordering. |
| R:43 | Kaocha plugin/filter.clj:10-60 ends before skip/focus decisions; actual filter-testable runs through :99. type/var.clj:30-44 omits returned result at :45, which merges testable data with counters. It is not just a counter map. |
| R:44 | babashka destroy-tree:165-173 signals destruction; it does not await actual process/descendant exit. rewrite-clj zip.cljc:275,335,343,444 are public facade delegates; add root-string:309-311 and delegated owner code when changing behavior. They prove a zipper API, not “Seon adds only digest fencing and refusal data.” |
| R:45 | langchain4clj/core.clj:249-262 demonstrates provider dispatch, but builds Java models rather than Seon's wire request. streaming.clj:20-28 only forwards callbacks; it contains no fold into a value. http-kit/client.clj:296 starts request documentation, not the complete body. Do not claim SSE parsing, usage normalization or cache-field behavior from these ranges. |
| R:52 | versioning.cljc:457-490 includes materialization but not release-materialized-db:494-501. The concurrent-copy warning starts at :584 and continues to :588, beyond :585. Extend to those owning forms. |
| R:53 | writing.cljc:860-889 is mostly secondary-index installation plus transact! docs. Use :883-911 and versioning.cljc:738-756. The alleged missing fence/validator is stale. M2. |
| R:55 | query.cljc:2568-2590 starts in dependency selection and ends before advance-query-cache-context completes at :2592. Transaction-time exclusion is in db.cljc:423-442 (specifically :430), not this cited span. Its correctness for transaction-time readers is unverified here; do not assert an outstanding correction solely from this citation. |
| R:56 | datom.cljc:325-359 mostly wraps comparison; the actual supported-value dispatch is compare-value:271-315. Pull default 1,000 is false in the cited fork. M2. |
| R:63 | flow/impl.clj:29-36,271-320 shows future/serial-loop machinery, but timed .get is at :258-260. Add that exact span for “timeout is not cancellation/exit.” |
| R:65 | clj-kondo core.clj:242-270 shows cache-before-final-findings, but add impl/cache.clj:171-215 for retained in-run definitions and the nil disk-cache path. |
| R:67 | http-kit server.clj:321-326 documents close statuses. Use :345-350 plus ServerAtta.java:44-66 for write-state/drain. H6. |
| T:22 | “Malli and sci tests contain no references to private Vars” is false at least for Malli: core_test.cljc:2836 dereferences m/-function-schemas*. This does not authorize that style in Seon; remove the false library claim. |
| T:55,66 | Datahike setup:10-14 omits its second entity/closing let at :15; failure table:16-25 omits its third failure row at :26. Use :11-26. |
| T:97 | Clojure string.clj:90-104 is exception-class testing, not error type/member data. Use edamame's ex-data assertion or an actual Seon type/member example for §2.6. |
| T:48; T:83-88 | edamame regex was altered into a nonmatching ellipsis; editscript's example omits edits/script roundtrip and does not demonstrate bounded/seeded Seon setup. Hints about adaptation must be explicit. M8. |

Other citation groups were read and support their bounded claims:

- rewrite-clj zip/move.cljc:8-26; Fipp edn.cljc:9-21; Malli core.cljc:717-718: small named operations and input destructuring.
- Clojure set.clj:78-87 and core.clj:1559-1571: rename/select present keys.
- Malli core.cljc:203-207 and Datahike transaction.cljc:27-32: constructor/thrower and failure-at-detection.
- Malli docs/function-schemas.md:526-530, docs/reusable-schemas.md:116-120: metadata contracts and named/naked map keys.
- Malli error.cljc:44-172,288-306,332-390 and util.cljc:128-145: schema messages, humanization, spelling composition and explicitly open-map limit.
- Clojure core.clj:7033-7051,7054-7065,7298-7310,7355-7363,8136-8164; Malli core.cljc:247-260: standard collection/threading idioms, subject to M1.
- Fipp engine.cljc:10-46 and visit.cljc:32-52; Clerk's selection ranges: data/node dispatch and first-match selection, subject to the ledger limits.
- SCI interpreter.cljc:94-114, core.cljc:260-276,334-340, utils.cljc:410 onward, doc/interrupt.md:52,63-87: reader loop, intern, context fork/copy-on-write and host-call interruption limit.
- clj-reload/core.clj:54-55,60-65,185,270-302: central roster, body-wrapping macro, ordering and namespace load/unload helpers; not purity proof.
- core.async/async.clj:150-160, flow.clj:136-200,265-286, flow_test.clj:34-41: macro, process/workload and minimal step.
- Datahike api/impl.cljc:30-42, api/specification.cljc:429, transaction.cljc:998-1015,1172-1173,1225-1234,1296, pull_api.cljc:541-555, versioning.cljc:212-277,323-367, gc.cljc:22-81,144-167: delegate, transactional db, incoming sweep, final-report callback including non-nil false rejection, aligned batched reads, shared-index branch/head and reachability/cutoff seams.
- Malli registry.cljc:17-30, core.cljc:353-361,2672-2712,3166-3187: persistent/copying registry and compiled-object derivations/instrumentation.
- Clojure test.clj:708-737 and core/server.clj:275-308: fixture dispatch and io-prepl framing.
- Edamame core.cljc:126-146, core_test.cljc:21-30; Malli core_test.cljc:382-389; Clojure test string.clj:48-58; Konserve memory_test.cljc:22-23 and compliance_test.cljc:14 onward; Hyperlith core.clj:24: parser/ex-data/table/compliance/short-comment patterns.

## “Read before you build” choices by area

| Area | Assessment and concrete change |
|---|---|
| Errors | Malli/Datahike are strong constructor/thrower models. Keep the binding Seon returned-tool distinction and constructor references/chain conversion. Mark clj-reload as analogous carriers, not identical maps. |
| Contracts/instrumentation | Best reference is the chosen Malli wrapper. Add the report-return/throw limit and Seon's first-party report owner; the snippets do not automatically build Seon's error. |
| Schemas/registry | Good chosen source. Read full registry and compiled schema cache together; explain object identity versus content-keyed Seon reuse and complete declaration admission. |
| Database | Strong seams. Add expected pre-read/write decision, attempted versus effective datoms, and final owning-value validation. Do not copy direct Datahike calls into every Seon namespace; CLAUDE.md:224-226 fixes the owner. |
| REPL/evaluation/printing | Split into two rows. SCI/nREPL/edamame are good evaluator seams; Fipp plus Seon's printer contract are the printing models. Remove quota writer as the product printer recipe. |
| Reload/adoption | clj-reload is a good ordering/loader reference, not a complete model for branch adoption, arming and publication evidence. Its unload/load failure handling is library-specific; preserve Seon's explicit unavailable state. |
| Agent/Flow | Good choice. Extend to map->step's body and lifecycle transitions. Pair with reviewed agent-loop pass/settlement decisions; keep state in the proc rather than treating a central atom as the default. |
| Render selection | Clerk/Fipp are good narrow models. Pair with Seon's consumer precedence, supplied viewer world and nested-render contract. First match is deterministic even if predicates overlap. |
| Web/SSE | Hyperlith is good for tap-before-render and wake coalescing. Add actual http-kit pending-write seam and Seon's bounded writer owner; distinguish notification buffer from socket queue. |
| Test runner | Kaocha/Clojure are good data plan/hook/fixture references. Read full filter/result forms and pair with Seon's recording/custody/release owners; a counter map is not durable proof by itself. |
| Shell/fs/edit | Split shell from structural edit and filesystem behavior. Babashka is good for argv/options; it lacks Seon's retained identity/bounded-capture/exit guarantees. rewrite-clj is appropriate for lossless zipper operations, with its delegated implementations. Neither is the fs/no-follow/cleanup model. |
| LLM provider | langchain4clj is useful for dispatch/builders only. Its Java client delegation is not a model of Seon's SSE fold, provider usage normalization or prompt-cache controls. Read those Seon owners and the provider skill for the wire behavior; do not infer policy from a callback adapter. |

## Files read and verification limits

Read in full: the four requested drafts; CLAUDE.md; docs/seon/specs/errors.md; docs/seon/specs/agent-loop.md; tmp/clojure-guide/idioms-src.md, idioms-tests.md, census.md, canon.md, map-data-contracts.md, map-repl-path.md, map-render-web.md, map-cluster-test-tools.md. Inspected the draft diffs and removed guidance, including the old harness limits. Existing notes' nine listed CLAUDE conflicts are not counted again; H1/H2/H5/H6/M5 and deterministic-work-count guidance identify additional conflicts.

Read relevant source forms/ranges, not each entire file:

- Seon: deps.edn; src/seon/db.clj, turn.clj, cluster.clj, schema.clj, fn.clj, error.clj, flow.clj, test.clj, test/runner.clj, effect.clj, render/web.clj, render/transcript.clj, cluster/source.clj; src/my/program.clj; resources/seon/schemas/seon.test.edn and seon.turn.loop.edn.
- Tests/support: test/seon/test_support.clj, reset_edges_test.clj, db_test.clj, run_test.clj, sci/admit_test.clj, render/value_test.clj, cluster/registry_test.clj, cluster/agent_test.clj; docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md's merge-authority section.
- Clojure reference: src/clj/clojure/core.clj, set.clj, core_print.clj, test.clj, core/server.clj; test/clojure/test_clojure/string.clj.
- Malli: src/malli/core.cljc, error.cljc, registry.cljc, util.cljc; docs/function-schemas.md, reusable-schemas.md; test/malli/core_test.cljc.
- SCI: src/sci/impl/interpreter.cljc, impl/utils.cljc, core.cljc; doc/interrupt.md; test/sci/core_test.cljc.
- Datahike: src/datahike/api/impl.cljc, api/specification.cljc, db/transaction.cljc, db.cljc, query.cljc, datom.cljc, pull_api.cljc, versioning.cljc, writing.cljc, gc.cljc; test/datahike/test/lookup_refs_test.cljc.
- clj-reload: src/clj_reload/core.clj. rewrite-clj: src/rewrite_clj/zip.cljc, zip/move.cljc. Fipp: src/fipp/edn.cljc, visit.cljc, engine.cljc.
- core.async: src/main/clojure/clojure/core/async.clj, async/flow.clj, async/flow/impl.clj; src/test/clojure/clojure/core/async/flow_test.clj.
- nREPL: src/clojure/nrepl/middleware/interruptible_eval.clj; src/java/nrepl/out/QuotaBoundWriter.java.
- Clerk: src/nextjournal/clerk/viewer.cljc. Hyperlith: src/hyperlith/core.clj, impl/datastar.clj.
- http-kit: src/org/httpkit/server.clj, client.clj; src/java/org/httpkit/server/AsyncChannel.java, ServerAtta.java. Datastar: libraries/sdk-http-kit/src/main/starfederation/datastar/clojure/adapter/http_kit.clj.
- Kaocha: src/kaocha/testable.clj, type/var.clj, plugin/filter.clj.
- Babashka-process: src/babashka/process.cljc. Langchain4clj: src/langchain4clj/core.clj, streaming.clj.
- Edamame: src/edamame/core.cljc; test/edamame/core_test.cljc. Editscript: test/editscript/core_test.cljc.
- Konserve: test/konserve/memory_test.cljc; src/konserve/compliance_test.cljc. clj-kondo: src/clj_kondo/core.clj, impl/cache.clj.

Unverified: runtime installation, arming, adoption and tool health; execution of proposed replacements; profile/census figures and their reproducibility; guarantees not present in the opened source (notably general transaction-time currency correctness). No empirical speedup is claimed. This report needs no tests. If owners implement the cited behavioral rewrites, their regressions belong to those implementation slices, not this read-only review.

**Findings count: high 6; medium 9; low 2. Total 17.**
