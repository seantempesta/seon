# Review: Clojure implementation guide

**Lane:** `trial-astra-guide` · **Date:** 2026-10-07 · **Disposition:** revise before making this mandatory.

The guide would improve attention to error plumbing, repeated transformations and oversized functions. It is not yet safe to follow literally. Several advertised rewrites change results; the deletion policy removes evidence that the owner explicitly requires; two library analogies discard Seon's required behavior. The problem is precision, not the approximately 13.6k-token size. More complete, correct examples would be worth additional reading.

Locations below use **D** = `.agents/skills/data-oriented-clojure/SKILL.md`, **T** = `.agents/skills/clojure-testing/SKILL.md`, **R** = `docs/seon/architecture/reference-code.md`. Other paths are repository-relative. Repeated appearances of one defect are one finding. The citation and example ledgers below refer back to these findings rather than adding to the count.

Read-only source review. No tests, Clojure evaluations, lint, contracts compilation, JVM operations or commits were performed. Source inspection does not verify installation. No tracked file was edited. Foreign in-flight changes, including `test/my/examples_test.clj`, were read where relevant and left untouched. Required tests for this report-only change: **none**.

## High findings

### H1. Two mechanical rewrites are not equivalent

**D:114-121,280-284,470,477.**

* `(first (filter p xs))` preserves a matching `false` or `nil`. `(some #(when (p %) %) xs)` skips it. For `p = (constantly true)` and `xs = [false :later]`, the former returns `false`, the latter `:later`. This follows directly from `some`'s truthy-result semantics; no execution is needed to establish it. These are both single traversals, with different allocation/evaluation behavior, not a two-pass versus one-pass transformation.
* `(cond-> r (:k m) (assoc :k (:k m)))` tests truthiness; `merge` plus `select-keys` tests presence. With `r = {:k :old}` and `m = {:k false}`, the old result is `{:k :old}` and the proposed result is `{:k false}`. A present nil similarly changes the result, including overwriting an existing value. `reference-code/clojure/src/clj/clojure/core.clj:1559-1571` explicitly uses a not-found sentinel. The research warned about false at `tmp/clojure-guide/idioms-src.md:174-175`; the guide drops even that qualification. `src/seon/fn.clj:692` accepts `:map` metadata, so the example's input contract does not make the shortcut generally equivalent.

**Replacement for D 2.4:**

> Copy a group of presence-based optional keys with `select-keys` and `merge`. Before replacing conditional associations, distinguish absent, nil and false and preserve collision behavior. A truthiness test is not a presence test; retain it unless the admitted input schema makes the two equivalent.

**Replacement for the first D 6.2/quick-table entry:**

> Keep `(first (filter p xs))` when you need the first matching value, including nil or false. Use `some` when you want the first truthy result of a function. Use a reducing early exit when allocation matters and preserve matching false/nil values.

`update-vals` is a useful recommendation, but mention that it preserves input metadata (`core.clj:8136-8146`), whereas the shown `into {}` does not. Do not call all three rows unconditional equivalences.

### H2. The two concrete map rewrites drop required work/data

**D:81-86,286-301.** `src/seon/render/web.clj:691-702` adds seven request members. D's supposedly equivalent shorthand omits the evaluation time limit, value-render root and admission caps. This is not merely shorter keyword spelling. Either label it explicitly as an excerpt or show all seven members.

More seriously, `src/seon/turn.clj:189-209` appends a newly staged blob to **existing** `:seon.blob/staged-writes`, removes that transport member from the receipt, and returns all staged writes. D's six-line replacement returns only `[staged]` or `[]` and leaves the transport member in the receipt. Existing staged writes are lost in both branches. This bug was copied from `tmp/clojure-guide/idioms-src.md:413-432`.

**Replace that example with:**

> Preserve all three results: the receipt without the staging member, the original evaluation, and every existing staged write plus any newly staged value. Threading is useful only when it preserves that contract. This example is not an equivalent six-line rewrite; remove the claimed reduction until a complete replacement is proved.

A source-equivalent body, offered for review rather than claimed as tested or necessarily better, is:

```clojure
(let [staged (when-let [edn (:seon.eval/value-edn evaluation)]
               (blob/stage! (:seon.db/connection cluster) edn))
      receipt (cond-> (dissoc evaluation :seon.blob/staged-writes)
                staged (dissoc :seon.eval/value-edn)
                staged (assoc :seon.eval/value-digest (:seon.blob/digest staged)))]
  [receipt evaluation
   (cond-> (vec (:seon.blob/staged-writes evaluation)) staged (conj staged))])
```

Here named intermediates improve clarity. “Do not name an intermediate you use once” is the wrong lesson.

### H3. The test exemption/deletion policy contradicts the binding test decisions

**T:18-30,140-161.** An armed schema is not proof that the right producer returns the right variant, that arming occurred, that a refused transaction leaves the database unchanged, or that the error reaches the user. A warning certainly is not proof. Dependency tests also do not prove Seon's integration with the dependency, especially its maintained-fork hooks and supplied worlds. `errors.md:275-285` expressly requires returned-error, instrumentation and undeclared-variant tests. T would classify these as contract duplicates.

The chosen counterexample proves this problem: `test/seon/reset_edges_test.clj:72-83` checks that retracting required declaration facts produces a request identity **and leaves the basis unchanged**. It does not merely check `:min 1`; an isolated `m/explain` cannot prove writer atomicity.

“Headed for deletion,” “renamed away,” private reach, no literal `is`, and stand-ins are not sufficient deletion criteria. A renamed function may retain every behavior. A helper can emit assertions: T's own good example, `reference-code/konserve/test/konserve/memory_test.cljc:22-23`, delegates to `compliance-test`. T:160-161 permits deleting the only behavior proof and filing a future task, whereas `CLAUDE.md:368-370` requires the replacement test with the cut. `errors.md:287-292` says per-test triage **with the machinery**; `agent-loop.md:269-275` preserves fold behaviors and moves their tests. Finally T:30,159 forbids cost/revision assertions, while `agent-loop.md:260-261` explicitly requires zero cache misses and exact changed-input work.

**Replace T §1's blanket exemption and §3's opening with:**

> Prefer one test per observable behavior through its owning entrance. A schema declaration does not replace a test of producer choice, instrumentation, persistence, refusal atomicity or delivery. Do not duplicate a schema validator's own semantics; retain the integration proof that Seon uses it correctly.
>
> Delete a test when its behavior is retired in the same slice, or when equivalent current proof remains and is named. An owner-approved future deletion is not a present deletion. Move surviving behaviors to the new entrance with the cut. Convert an invalid fixture or private-only assertion when it is the only proof; do not leave a replacement task instead of the proof. Trace assertion events through helpers before calling a test vacuous.
>
> Keep deterministic assertions about required work, transaction identity, misses and recomputation. Profile wall-clock performance separately; avoid fragile machine-speed thresholds in ordinary behavior tests.

### H4. The quick checklist authorizes removing checks before their producer changes

**D:134-137,160-172,192-202,249-259,471,475.** The prose acknowledges that throwing reads are an S4 target, but the quick table says the bare call already throws. “Apply §13” is not sufficient protection against a local fifteen-minute deletion. `src/my/program.clj:106-155` still unwraps five potentially returned read failures. Deleting one before the producer's return-to-throw migration passes an error map downstream as ordinary data.

Likewise “armed input” does not mean `map?`/`contains?` is redundant: `:any`, unions, open maps and optional keys require discrimination. The example in `src/seon/turn.clj:336-350` checks a **new read result**, not its validated request; `current-run` explicitly returns an error alternative at `:329-332`.

**Replacement:**

> Remove a guard only when the specific input contract proves that exact fact, or when the producer's newly installed contract has made the failure case impossible. Union discrimination, optional-key presence and checks of newly read state remain meaningful. Migrate a read's carrier, its contract and every affected caller in one accepted slice. Until throwing reads are installed, retain existing unwraps; mark the bare-call example as a target, not a quick rewrite.

Also change D:200-202: a returning entrant such as `seon.test/run` still needs its exact returning error variants; the reader migration does not authorize narrowing it to success only (`errors.md:162-165,298`).

### H5. The REPL reading row substitutes character truncation for the required value printer

**R:38.** `reference-code/nrepl/src/java/nrepl/out/QuotaBoundWriter.java:27-36` writes a prefix of a character buffer and throws on overflow. It does not cut a value before layout, preserve valid printed structure, produce paths/dig forms, budget a whole shape, or share an AI/HTML node tree. Fipp's `pretty-coll` (`edn.cljc:9-20`) implements per-collection print length/level, not Seon's global semantic budget. The research itself labels this replacement an owner decision (`map-repl-path.md:235-249`); the guide turns it into an instruction without that qualification.

This conflicts with `CLAUDE.md:330-337` and is **not** listed in guide-notes' conflicts.

**Replace the row's last sentence:**

> Read the quota writer as an example of bounding streamed text, and Fipp as an example of constructing and laying out printer documents. Neither implements Seon's semantic budget, cuts or retrieval forms. Preserve the single value printer and its AI/HTML semantics; do not replace it with a character quota or a second clipping layer.

### H6. A dropping buffer is not transport backpressure

**R:42,67.** Hyperlith subscribes before the first render and drops refresh signals, correctly. But its `send!` (`reference-code/hyperlith/src/hyperlith/impl/datastar.clj:42-49`) directly calls asynchronous `hk/send!`; its loop (`:160-175`) does not await socket drain. A slow network can therefore queue output bytes even with a one-item refresh channel. “A slow tab skips ticks instead of queueing bytes” asserts a guarantee the cited implementation does not provide.

The old seam citation, `http-kit/server.clj:321-326`, is an on-close status-code list. The actual maintained-fork seam is `reference-code/http-kit/src/org/httpkit/server.clj:345-350`, returning pending bytes and a drain-or-close completion. Merely obtaining it does not impose a bound.

**Replacement:**

> Subscribe before the first render and coalesce replaceable refresh signals with a bounded channel. Bound outgoing bytes separately: observe `write-state` and wait for drain-or-close according to the connection's declared limit. A dropping refresh buffer alone does not bound the transport queue.

### H7. The harness section grants permission for forbidden global-state changes

**T:194-195.** The preserving helpers exist (`test/seon/test_support.clj:1114-1155`), but availability is not authorization. “A test that deliberately changes it uses … Anything else is a defect” implies that restoring in `finally` makes JVM-wide mutation valid. It cannot prevent overlap with another member while the body runs. `CLAUDE.md:355-357` prohibits JVM-global test state/restoring reloaded roots and restricts a test to arming its own Vars. This additional conflict is absent from guide-notes.

**Replacement:**

> Ordinary tests must not mutate JVM-global state. A test may arm only its own Vars. These preserving helpers exist for the existing host-bound harness; their existence does not authorize shared registry mutation or restoring reloaded roots. A test requiring such effects must use the declared isolated platform path.

### H8. The merge seam table describes a missing feature that the pinned fork already has

**R:53.** `reference-code/datahike/src/datahike/versioning.cljc:738-749` documents and accepts the arg-map carrying `:datahike/expected-basis-t`. `writing.cljc:902-911` implements `merge-writer!` by calling ordinary `transact!`, preserving the basis fence and report validation (`:889-900`). The cited `:860-889` is mostly the preceding index-installation operation and only the beginning of `transact!`.

Calling this a missing dependency contract invites a duplicate merge implementation. Whether every Seon caller supplies the fields is a separate integration question, not proved by this row.

**Replacement:**

> `versioning.cljc:738-749`; `writing.cljc:883-911`: the arg-map merge routes through the ordinary writer and transaction path, carrying the expected-basis fence and report validation while adding merge parents. The caller still supplies the selected content and lineage. Verify Seon's caller supplies the required fields; do not recreate this seam.

## Medium findings

### M1. The flagship pure-state example is effectful

**D:46-47,329-341; R:39.** `clj-reload/core.clj:206-221` reads dynamic configuration, the clock, `*loaded-libs*` and filesystem scan results. `unload` uses it inside `swap!`, but that does not make `scan` pure. Calling it pure three times teaches I/O in retryable state transitions. The library serializes reload operations with its own lock; copying the `swap!` expression without that context is particularly misleading.

**Replacement:**

> clj-reload illustrates one owner for reload state and dependency-ordered effects. Its `scan` performs observations and is not a pure transition. In Seon, acquire observations once, then pass them to a pure state transformation. Prefer the existing database, Flow proc or SCI owner before adding an atom.

Use `topo-sort-fn` (`core.clj:185-191`) for the pure transformation example; retain `unload` only as the effectful shell example. The consolidation of related launcher state is a reasonable direction, but a single atom alone does not make launch/cancel/retire effects atomic.

### M2. Several named counterexamples misidentify the problem or overclaim the remedy

**D:35-42,103-112,309-322,424-442.**

* `write-owned-values-error` really is large (`src/seon/db.clj:4082-4331`). It already contains folds and a worklist; `charge!` is invoked through the `write-entity-value` callback (`:4105-4108`). “The volatile traversal becomes a reduce” needs a changed callback/state interface, not a mechanical replacement. No complete 20-40-line equivalent has been demonstrated. Name the separable roles and preserved cycle/ownership/bound/attempted-write checks; do not promise a line count.
* `receipt-entry` (`src/seon/render/transcript.clj:258-286`) is a normalization boundary, not just eight copied keys: it retains the receipt, resolves its namespace, derives its time with a fallback, extracts run relationships and supplies a common transcript envelope. Eliminate only proven duplicate facts/consumers, not this boundary by analogy.
* `admit-declarations!` (`src/seon/schema.clj:4185-4226`) does re-key heavily, but also computes changed dependency closures, derived predicates and restricted schema/function subjects. Passing the old projection alone does not replace the complete validation request. A worthwhile owner refactor must preserve those additions.
* `error/message` (`src/seon/error.clj:114-128`) implements ordered fallback among existing message, transition explanation, exception cause and unknown input. A multimethod on a type does not reproduce that precedence. Retire obsolete branches with the error-spec implementation, not because every `cond` implies a missing discriminator.
* `turn-function-result` (`src/seon/render/web.clj:677-680`) performs **zero** resource probes when `find-ns` succeeds, otherwise up to three, short-circuiting. D's “three times on every call” is false. R:86 already says not to cache filename existence. Cache a pure spelling transformation only if measured; a loaded-namespace/classpath observation needs the corresponding changing input.
* `previous-ending-namespace` already puts `(max ?evaluation)` inside the query (`src/seon/turn.clj:570`). D:442 prescribes the existing code. Combining the selected entity's attribute into one query is a different task requiring a correct aggregate/join formulation.
* `caller-data` has five unwrap sites, correctly observed, but uses two reads per ordinary caller and a third only when the first identity lookup fails (`src/my/program.clj:128-144`), plus shared arity reads. A batch pull is promising, but the suggested fragment omits sorting, callee-specific arity filtering, declared arities and construction of sites. Label it an acquisition sketch, not a complete replacement.

**Replacement rule:**

> For each BAD/REWRITE pair, state the behavior being preserved and show the complete replacement, or label it a design sketch with the remaining obligations. A smaller snippet is not evidence of a smaller equivalent implementation.

### M3. Several universal style rules are arbitrary or contradict the guide's own examples

**D:25-42,64-68,88-101,123-130,263-268,286,324-325,390,400-415.**

* Extract every closure/comment into a function conflicts with inlining every small single-use helper. Judge the named responsibility and control/effect boundary, not header/body length. A closure capturing two stable values is not inherently worse than threading them through multiple new contracts.
* “More than three positionals become one map” contradicts the four-argument error constructor endorsed at D:154 and the six-argument Fipp example at D:94. Stable relational arguments and an options map are different roles.
* Arglist destructuring is a good default, not a ban on naming values from an intermediate map or using explicit renaming to distinguish before/after worlds.
* `loop` is also appropriate for primitive/index walks and explicit worklists; `idioms-src.md:372-385` preserves these, but D removes the qualification.
* Protocols are not only for host types you do not own. Fipp's own `EdnPrinter` implements its own `IVisitor` (`reference-code/fipp/src/fipp/edn.cljc:22-24`). Prefer functions/maps by default; justify actual type dispatch instead of inventing this restriction.
* “Locals are one word” discards distinctions such as `db-before`/`db-after`. `scan`, `unload` and `reload`, the named GOOD examples, do not obey the advertised pure-noun/effect-`!` rule. Clear names are more valuable than a token quota, especially for persistent schema identities.
* D 8.3 permits an ambient evaluation world, then says every function reading a dynamic Var should take an argument. Distinguish the entrant's binding and declared world supply (`CLAUDE.md:194-205`) from an ordinary helper's hidden service lookup; otherwise the two instructions conflict.
* A macro must not evaluate every argument once: binding symbols and declarations are syntax. Runtime expressions should not be unintentionally evaluated more than once; `with-lock` intentionally gives the body its control-flow semantics.

**Replacement:**

> Treat 5-15 lines as a useful example size and 40 as a review prompt, not a semantic limit. Split distinct responsibilities; keep a small helper or local when its name explains an otherwise opaque step. Prefer arglist destructuring and threading when they improve the data flow. Use an options map for numerous optional inputs; keep clear positional essentials. Keep simple loops for evolving worklists or index/state walks. Name values so competing roles remain distinguishable. Macros accept syntax deliberately and evaluate each runtime expression only as often as their documented semantics require.

Keep these as diff-review questions, not automatic rejection rules. `CLAUDE.md:70` (“Add no hypothetical restrictions”) supports that distinction.

### M4. Contract brevity must not erase the facts callers rely on

**D:215-245,494-496.** Naked map entries and registry references are good Malli idioms. The conclusion that private contracts should use existing `:map`/`:int` names can encourage weakening complete shapes to save text, particularly beside D 5.1's promise that contracts eliminate all internal probes. A plain `:map` proves none of its required members. The `interrupted-warning` replacement also introduces `::interrupted-warning` without its registry definition; it is not a standalone usable replacement.

**Replacement:**

> Shorten contract spelling without weakening the declared shape. Reuse a named schema only when it has the required meaning; use naked entries for registered attributes. Keep optionality, bounds, relationships and exact returning error variants. If a reusable shape needs a new name, include its schema definition and publication/adoption change with the function. Small one-off structural contracts may stay inline. Do not substitute `:map` or `:any` merely to fit one line.

D:240's publication/adoption instruction is good; show it applied to the preceding example.

### M5. Error guidance drops two important owner decisions

**D:174-190,204-211; R:34-35.** “Do not write messages by hand” is correct for reconstructed validation prose but wrong for domain failures: the binding constructor explicitly takes the producer's message (`errors.md:93-102`). Also `errors.md:299-300` limits closed-schema spelling suggestions to the refusal path; R mentions `closed-schema` but does not preserve this condition. Applying it to valid open-map calls would change admission or add work.

Malli's instrumentation `report` return does not prevent entry (`reference-code/malli/src/malli/core.cljc:2228-2240`); the new reading row should repeat that consequence, already correctly stated in R:58.

`Throwable->map` is appropriate once at the owning boundary. It builds cause/frame data (`core_print.clj:473-500`); replacing a short-circuit signature lookup with full conversion at every use is not automatically less work. Pass an already-derived chain where one exists.

**Replacement:**

> Producers supply concise domain messages to the error constructor. Validation explanations come from Malli. On an invalid call only, explain against `mu/closed-schema` for spelling suggestions and retain only suggested extra-key errors; valid open-map calls pay no such work. The instrumentation report must throw through the declared error boundary to prevent an invalid call entering the function. Convert a Throwable once where the error is owned and reuse its chain.

### M6. Several test examples contradict what they are cited to prove

**T:21-22,38-39,64-78,91-104,118-120,134-135.**

* Malli does reference a private Var: `reference-code/malli/test/malli/core_test.cljc:2836` uses `@#'m/-function-schemas*`, declared private at `src/malli/core.cljc:3108`. SCI's `interop_test.cljc:63` calls `analyzer/analyze` directly. Delete “no references” and “never the analyzer”; they are false at the pinned checkout. They do not invalidate Seon's preference for behavior through the entrance.
* `reference-code/clojure/test/clojure/test_clojure/string.clj:90-104` asserts thrown `NullPointerException`, not `ex-data`, a variant key, or a returned error. It supports rows for one failure class, not T 2.6's data-error claim. Use the Edamame data assertion already cited at T:45 instead.
* `cluster-branch-is-one-injective-derivation` really states a law: equality of inputs iff equality of derived branches, plus repeatability and separation from `:db` (`test/seon/cluster/registry_test.clj:210-226`). It is not disproved by calling its subject a naming function. Keep or improve bounded inputs/positive trial evidence; do not suggest examples automatically replace injectivity.
* The condemned printer test is in the printer's own namespace (`test/seon/render/value_test.clj:1253-1288`), explicitly exempted by T:115 and163. Its stringified diagnostic assertion should become a data assertion; the printed reply and runnable-cut assertions can legitimately remain.
* `shown-value-diff-round-trips-changed-paths` is already a nine-line table over four pairs with two related assertions (`test/seon/db_test.clj:2595-2603`). `are` is an option, not a demonstrated simplification. The research's `canon.md:449-450` warns about source-location loss; keep that qualification and allow `doseq` with named cases.
* The current examples runner checks nonblank examples and absence of errors for most examples, with a special exact tally check for `my.test/run` (`test/my/examples_test.clj:182-200`). It does not generally pin the returned map. Therefore a value assertion through the same public function need not duplicate doc-example execution.

**Replacement for T 2.11:**

> Executed docstring examples prove that public usage still runs. Reuse their exact behavioral assertions when present; add a focused test when the runner checks only successful execution and the returned data or effect is the behavior at risk.

### M7. “Three lines of setup” needs an actual Seon example, not a dependency analogy

**T:52-62,167-205.** The guide names real helpers, but shows no complete minimal Seon test using them. Datahike's bare `empty-db`/`db-with` fixture does not demonstrate Seon's writer-checked entities, member custody, armed functions and fixed render profiles. `support/evaluate` requires caps and obtains a member database when omitted (`test/seon/test_support.clj:370-435`); `with-database` is not usable outside its member context. The broad list “renders, diffs, readers … no world” is false for any renderer that reads its viewing database or reader depending on a registry.

**Replacement/addition:**

> Pick the smallest world the behavior actually reads: plain values for a pure transform; a member's database branch for writes; the member's SCI context and declared render caps for reply behavior. Do not remove required custody, profiles or writer provenance to meet a setup line count. When setup repeats, extract one named fixture with explicit inputs and cleanup.

Add three **complete, source-verified** examples: a pure transform table, a refused write preserving the basis, and an agent evaluation returning an error map. Show imports, required inputs and assertion data. The report does not invent a new `support/db-with` or claim an unexecuted example passes. Restore a short distinction between body-duration reporting and proof of actual exit; `duration-failures` (`src/seon/test/runner.clj:342-360`) detects an overrun after elapsed time is known, not termination by itself.

### M8. The area table overstates equivalence and omits important jobs

**R:26-28,36,41,44-45.** “Same job … not a different shape” is not justified by small dependency helpers. Research explicitly distinguishes legitimate Seon obligations and owner decisions (`map-repl-path.md:362-404`; `map-data-contracts.md:280-302`). It also contradicts the last row's sensible “copy the shape, not the dependency” qualification by not defining which part of the shape is relevant.

Clerk demonstrates first matching predicate, not that registry order is established once or that overlapping predicates cannot be ambiguous (`viewer.cljc:1470-1496`). `langchain4clj/streaming.clj:20-28` forwards callbacks and contains no fold into a result; HTTP-kit `client.clj:296` is the start of a large request API, not a provider parsing/state model. Babashka's exception data can contain process resources; Seon's errors must refer to those by identity (`CLAUDE.md:264-266`). Destroying a process tree is not observing exit. “Seon adds only digest fencing and refusal data” omits edit publication/write-back equivalence.

**Replacement introduction:**

> Read the row's functions and their callers to understand the named pattern. Record which guarantee you can reuse and which Seon obligations it does not provide. Borrow the smallest compatible composition; a similar function's line count is not proof that a different requirement can be deleted. Distinguish an upstream seam, our maintained fork, installed behavior and a target design.

Add explicit entry points for program analysis/write-back, content caching/read evidence, and branch/store lifecycle. They can link to the existing seam table and skills instead of adding a second architecture document. Detailed row verdicts appear below.

### M9. Several retained seam citations are stale or too narrow to establish the guarantee

**R:52,55-56,63,65-67.** The full audit below gives replacements. The most consequential factual error is that many-pull defaults to 1,000: `reference-code/datahike/src/datahike/pull_api.cljc:16` sets the default to **nil**. `:315,323` apply configured limits, not that old default. Guide-notes already flags this; a mandatory guide must remove the false assertion rather than ship it beside a private warning.

The query range `query.cljc:2568-2590` covers a dependency-filter tail and the beginning of cache-context advancement, not the actual read-currency predicate or the claimed transaction-time exclusion defect. `:3010-3029` is the generation/revision comparison. Mark the old “specified correction” **unverified/stale** until its current producer and failing case are identified. Do not prescribe a fix from an old plan.

The notes call several other ranges unverified which now do check out: incoming-ref retraction/validator, GC, Flow workload docs and serial loop. Keep those; narrow what each range proves.

**Replacement pull guarantee:**

> This pinned fork has no default many-pull limit (`+default-limit+` is nil). Explicit selectors/limits still determine completeness; preserve the caller's complete-read contract and verify any explicit truncation is surfaced.

### M10. The most useful missing coverage is semantic discipline around shortening

The research is mined extensively, but the draft overweights lexical cleanup and under-specifies the preservation checks that make large cuts safe. Add these concrete rules, with the identified sources:

1. **Presence and defaults:** restore absent/nil/false distinctions, `find`/sentinels, and `:or`'s absent-only behavior (`canon.md:388,412-413`; `idioms-src.md:174-175`). This would have prevented H1.
2. **Bloat follows duplicate responsibility:** before splitting a giant function, identify repeated acquisition, validation, error conversion and storage of derivable facts; remove the duplicate owner, then extract remaining roles. Add one complete decomposition with its state/effect inputs, not a promise of 20 lines (`map-data-contracts.md:280-325`; `map-cluster-test-tools.md:239-264`).
3. **Re-keying versus projection:** pass open maps unchanged within one domain; at a real consumer boundary declare what is transformed and why. Preserve unknown members for pass-through APIs, but do not blindly carry live resources into persisted/printed values (`canon.md:389-392`; the actual transcript example).
4. **Repeated schemas include generators/exemptions:** move the repeated candidate shape, generation domain and admission explanation together into a named schema. D 4.2 hints at this but loses the concrete generator lesson (`idioms-src.md:360-366`). A valid constructor/schema must still be shown, not guessed.
5. **Streaming reductions:** allow consuming a reducible/stream without first materializing the whole input, even when consuming all of it (`canon.md:432`). D 12.3's “laziness for a prefix” is too narrow. Keep effects and resource consumption within the owning scope.
6. **Do not reproduce generic traversal scaffolding:** no new batch wrapper whose only job is mapping the single-item function, unless it establishes a meaningful batch boundary such as one transaction, one query or one recording (`canon.md:405-406`). This complements, rather than contradicts, genuine batching.

These additions are higher value than mandatory one-word locals, a universal three-positional cap, or repeating every rule in both a quick table and a commit checklist.

## Low findings

### L1. The pin roster is incomplete, and verification language exceeds the evidence

**D:13,18-21; T:11; R:29-30.** The eleven printed abbreviated revisions match the checked-out HEADs. But citations also use Clerk, clj-kondo, Edamame, editscript, HTTP-kit, Kaocha, Konserve, nREPL and datastar-clojure, absent from that roster. Puget is mentioned without a file/function at D:351. Complete the roster or refer directly to the gitlink plus observed checkout identity; do not say all referenced pins are listed when they are not.

Observed omitted revisions: clerk `4c25999462`, clj-kondo `57252e0797`, edamame `196ea5e4ef`, editscript `b493ccf2e9`, http-kit `f56bbeaf97`, kaocha `8846f91c9b`, konserve `8700ca7a95`, nrepl `f3a044153e`, datastar-clojure `1cef624e9e`.

“S1 is installed” (D:136), backtick reference resolution/index diagnostics (D:383-385), the blanket census-enforcement assertion (T:28-29), and the current snapshot statistics are **not independently runtime-verified in this review**. Mark installed claims with the probe/evidence that established them, or say “source currently has …”. `guide-notes.md:41-68` correctly distinguishes some research targets from present APIs; apply that discipline to the guide itself.

### L2. A few small accuracy/maintenance cuts would improve trust

**D:355** calls all eight db delays `seon.error` dependencies; `src/seon/db.clj:53-68` has five error and three call-preparation resolutions. Say “five error and three call-preparation delays.” **D:177-185/R:39** says clj-reload returns/throws the same map, but its return adds `:exception`; say “the same outcome fields, with the exception carried separately.” **D:14-16/T:9-10** repeat snapshot census numbers without a revision/method link. Keep one dated evidence link instead of making these counts part of every implementation's instructions.

`guide-notes.md` already enumerates the docstring headings/costs, link syntax, return/throw, error identity and refusal-field conflicts. Those still need authoritative reconciliation before rollout; they are not re-counted here. The extra conflicts in H3-H7 are not covered by that list.

## Reference citation audit

Every explicit reference-code location in D, T and R was opened at the local checkout. “Supported” below means the source shows the stated local pattern, not that Seon has installed the dependency/API or that every proposed replacement is safe. No cited file was missing. Broad function/file references were checked at the named implementation. The following are the wrong, weak or materially incomplete citations; repeated uses share a row.

| Draft location / cited code | Verdict and correction |
|---|---|
| D 1.2,8.1; R reload / clj-reload `core.clj:304-332`, `:313` | `scan` is effectful; see `:206-221`. M1. |
| D 3.3; R errors/reload / clj-reload `:367-381` | Correct constructor/throw/return pattern; not literally identical maps (`:exception` on returned result). L2. |
| D 3.3 quick rewrite / Clojure `core_print.clj:473-500` | Correct `:via` support; whole conversion is not evidence of a cheaper short-circuit signature search. M5. |
| D 8.2 / babashka-process `:215-219`; “fipp and puget” | Options show supplied functions, not an existing namespace cycle being removed. Puget has no checkable site. Keep as input-injection analogy; cite a visitor implementation rather than asserting more. |
| D 10.1 / unlocated clj-reload names | Functions exist; names do not demonstrate the claimed suffix/purity naming rule. M3. |
| T 1,2.1 / universal private-Var/analyzer claims | Refuted by Malli `core_test.cljc:2836` and SCI `interop_test.cljc:63`. M6. |
| T 2.6 / Clojure `string.clj:90-104` | Exception-class table, not error data. M6. |
| R REPL / nREPL quota writer; Fipp `edn.cljc:9-21` | Character quota and local print limits, not semantic cuts/global budget. H5. |
| R REPL / nREPL `interruptible_eval.clj:72-125` | Read/eval/caught seams exist, but the range stops during the evaluator; extend through its completion (`:180`) when teaching the whole loop. |
| R render / Clerk `viewer.cljc:1470-1497` | First-match selection verified; neither ordering-once nor absence of overlapping predicates proved. M8. |
| R SSE / Hyperlith `datastar.clj:122-181` | Subscribe-before-render and signal coalescing verified; byte backpressure not provided. H6. |
| R test runner / Kaocha filter `:10-60` | Begins `filter-testable` but omits its actual selection branches; include through `:99`. The test result carries testable data as well as counters; do not reduce its contract to “a counter map.” |
| R shell / process `:95-114,165-173` | Error process map and destroy operations exist; not a Seon-safe error payload or proof of exit. M8. |
| R edit / rewrite-clj zipper facade starts `:275,335,343,444` | Named operations exist; read their implementation/caller and `root-string` too. These short starts do not prove publication or source-equivalent write-back. |
| R provider / langchain4clj `streaming.clj:20-28` | Callback forwarding only; no fold shown. M8. |
| R provider / HTTP-kit client `:296` | Request API entry exists; insufficient as the provider wire/streaming model. M8. |
| R:52 / versioning `:457-490,550-585` | Commit materialization exists; release is actually `:494-501`; concurrent-copy warning completes at `:585-588`. Extend ranges. |
| R:53 / versioning `:738`, writing `:860-889` | Missing-seam claim false; cite `:738-749`, `writing:883-911`. H8. |
| R:55 / query `:2568-2590` | Does not establish the stale tx-time defect; actual currency comparison at `:3010-3029`. M9. |
| R:56 / pull `:16,315,323` | Default nil, not 1,000. M9. Datom `:325-359` shows comparator dispatch; inspect the selected value comparator before claiming support for a new admitted type. |
| R:63 / Flow impl `:29-36,271-320` | Future creation and serial loop verified; these excerpts alone do not establish all termination/timeout behavior. Include the timed-get/cancellation caller for that claim. |
| R:65 / clj-kondo `core.clj:242-270` | Cache processing precedes final findings; “disk off still resolves in-run” additionally needs the cache/analysis consumer, not this orchestration excerpt alone. |
| R:67 / HTTP-kit server `:321-326` | Close-status doc text, not drain seam. Use `:345-350`; H6. |

The remaining references support their stated **local** patterns:

* **D:** rewrite-clj movement `move.cljc:8-26`; Datahike argument normalization `api/impl.cljc:30-42`; Malli error-key table `error.cljc:44-62`; Fipp arglist destructuring `edn.cljc:9-10`; Malli `core.cljc:717-718`; Clojure `set.clj:78-87` and `core.clj:1559-1571`; babashka shell options `process.cljc:674-708`; Malli constructor `core.cljc:203-207`; Datahike unique refusal `transaction.cljc:27-32`; Malli messages/distance `error.cljc:245-259,288-306`; contract/reusable-schema docs `function-schemas.md:526-530`, `reusable-schemas.md:116-120`; cached validators `core.cljc:353-361,2672-2686`; Clojure `group-by`, `frequencies`, `into`, `mapv`, `update-vals`/`update-keys` at the cited ranges; SCI reader loop `interpreter.cljc:94-114`; Malli composition `core.cljc:247-257` (continues to260); Fipp node dispatch `engine.cljc:10-46`; langchain4clj provider dispatch `core.clj:249-262`; SCI dynamic bindings `interpreter.cljc:99-103`; Hyperlith comment `core.clj:24`; clj-reload `with-lock:60-65`; core.async `defblockingop:150-160`.
* **T:** Edamame `core_test.cljc:21-30`; Datahike lookup fixtures/tables `lookup_refs_test.cljc:10-25`; Clojure string tables `:48-58,90-104` as tables only; Malli test table `core_test.cljc:382-389`; editscript property `core_test.cljc:196-205` as an explicitly abridged law; Konserve shared compliance `memory_test.cljc:22-23`, `compliance_test.cljc:14`; Datahike final-report validation `transaction.cljc:1225-1234,1296`.
* **R, beyond duplicates above:** Malli instrumentation/compiled-schema ranges `core.cljc:2210-2245,2672-2712,3166-3187`, error composition `error.cljc:44-172,288-306,332-390`, closed schema `util.cljc:128`, registry `registry.cljc:17-96`; Datahike transaction-call/final-validator `transaction.cljc:1172-1173,1225-1234`, API specification `:429`, batched pull `pull_api.cljc:541-555`, incoming-ref sweep `transaction.cljc:998-1015`, branching/custody `versioning.cljc:212-277,323-367`, GC `gc.cljc:22-81,144-167`; SCI `core.cljc:260-276,334-340`, `utils.cljc:410`, interruption document `:52,63-87`; Flow `flow.clj:136-155,165-200,265-286,291-305`, minimal step `flow_test.clj:34-41`; Fipp visit `visit.cljc:32-52`; Hyperlith shim/ETag `datastar.clj:66-102`; Datastar HTTP-kit adapter `http_kit.clj:23`; Kaocha runner `testable.clj:105-137`, var runner `type/var.clj:30-44`; Clojure fixture dispatch `test.clj:708-737` and `io-prepl` `server.clj:275` (body through296); Edamame `core.cljc:126`.

Malli registry composition is correctly relevant, but “a registry is a map” is shorthand: its registry implementations include protocol-backed behavior and lazy mutable memoization. Borrow the API/compiled-schema cache; do not add an unowned Seon registry cache by copying the implementation.

## Seon example inventory

All named subjects were located. “Supported” is a source observation, not a passing runtime proof or approval to refactor/delete.

| Example | Source / verdict |
|---|---|
| `db/write-owned-values-error` | `src/seon/db.clj:4082`: giant mixed-role function and captured state verified; promised reduce/line-count rewrite unproved (M2). |
| `test/select`, `test/run`, `sci.eval/evaluate` | `src/seon/test.clj:701,2207`; `src/seon/sci/eval.clj:3862`: size examples verified by definition spans; not complete reviewed replacement designs. |
| `turn/settlement-projection` | `src/seon/turn.clj:189`: blob effect in projection verified; supplied rewrite wrong (H2). |
| `cluster/transact-initialization!` | `src/seon/cluster.clj:1105-1152`: repeated readiness predicate at1124-1125 verified. `group-by` can remove this repeated predicate while preserving order; the loop still needs read-refusal/no-progress handling and writer semantics. A full five-line shell is not demonstrated. |
| `web/debug-turn-request` | `src/seon/render/web.clj:691`: verbose literals verified; rewrite incomplete (H2). |
| `schema/admit-declarations!`, `transcript/receipt-entry` | `src/seon/schema.clj:4185`, `src/seon/render/transcript.clj:258`: not interchangeable examples of gratuitous copying (M2). |
| `fn/var-entity` | `src/seon/fn.clj:692,785`: conditional copying/comment present; presence rewrite needs H1's precondition. |
| `web/debug-diagnostic` | `src/seon/render/web.clj:655`: seven positional inputs verified; error-spec constructor is a better model, but the call still needs the appropriate named variant and fields. |
| `web/turn-function-result` | `src/seon/render/web.clj:668`: hand-built diagnostic verified; resource-probe frequency wrong (M2). |
| `my.program/caller-data` | `src/my/program.clj:106`: five unwraps and repeated acquisition verified; batch proposal only partial (H4/M2). |
| `test.runner/throwable-signature` | `src/seon/test/runner.clj:83`: hand loop verified; whole Throwable conversion is not proved cheaper (M5). |
| `error/schema-expectation`, `error/edit-distance` | `src/seon/error.clj:865,1949`: custom prose and Levenshtein implementation verified; Malli is the right dependency to compose. |
| `turn/interrupted-warning` | `src/seon/turn.clj:233`: repetitive contract verified; replacement must register its new output name (M4). |
| `turn/require-open-run` | `src/seon/turn.clj:336`: read-result discrimination, not redundant argument checking (H4). |
| `error/message` | `src/seon/error.clj:99`: excessive docstring verified; fallback policy does not mechanically become type dispatch (M2). |
| `flow/start-work-launcher!`, `flow/submit!` | `src/seon/flow.clj:656,760`: three related atoms at each cited site verified; consolidation needs lifecycle/event semantics preserved (M1). |
| `db` delayed resolutions | `src/seon/db.clj:49-68`: cycle workaround verified; count by dependency wrong (L2). |
| `effect/*request-context*`, `refusal/let-ok` | `src/seon/effect.clj:36`, `src/seon/error/refusal.clj:791`: existing dynamic carrier and error-value macro are legitimate migration targets; the guide must not prohibit an installed carrier before the producer/caller migration. Their complete replacement and installed alternatives remain unverified here. |
| `turn/previous-ending-namespace` | `src/seon/turn.clj:563`: aggregate already exists (M2). |
| `analysis-is-a-required-positive-fact` | `test/seon/reset_edges_test.clj:72`: refusal atomicity, not just schema cardinality (H3). |
| `turn-write-schema-does-not-include-error-observations` | `test/seon/db_test.clj:2558`: private intermediate assertion verified; triage the protected behavior, not just syntax. |
| `one-print-node-owns-semantic-value-and-result-edn` | `test/seon/sci/admit_test.clj:141`: binds six print Vars and checks serialization/semantic-value equivalence as data. This is an isolation/round-trip property, not obviously useless because it names a stage. Route through the correct owner if retained; do not discard its law by label. |
| `shown-value-diff-round-trips-changed-paths` | `test/seon/db_test.clj:2595`: already compact table/law (M6). |
| `cluster-branch-is-one-injective-derivation` | `test/seon/cluster/registry_test.clj:210`: real property (M6). |
| `disarm-interrupts-an-in-flight-turn-and-records-it-interrupted` | `test/seon/cluster/agent_test.clj:893-926`: 12s marker and Var stand-in verified; subject is concurrency, explicitly in T's protected category. Convert with the corresponding implementation; no blanket deletion. |
| `a-renderer-that-throws-still-prints-through-the-floor-and-says-large-value` | `test/seon/render/value_test.clj:1253`: printer exception applies (M6). |
| Shared fixture names | `with-render-context-proc` at `test/seon/turn_loop_test.clj:378`, `test/seon/gen/loop_test.clj:61`, `test/seon/cluster/turn_test.clj:147`; `with-cluster` at `concurrency_test.clj:155`, `concurrency_independence_test.clj:77`, `cluster/turn_test.clj:192`, `cluster/armed_test.clj:64`. Repeated names verified; identical responsibilities/lifetimes are not established by identical names alone. |

## “Read before you build”: row-by-row judgment

| Area | Keep / improve |
|---|---|
| Errors | Strong small models: Malli constructor/thrower, Datahike detection site, Clojure cause data. Add the exact Seon constructor/entrant contract and carrier migration status. Do not copy dependency raw error payloads into storage. |
| Contracts/instrumentation | Best chosen dependency. Add the report-must-throw consequence and failure-only spelling path (M5); retain exact shape despite one-line preference. |
| Schemas/registry | Strong API/composition examples. Distinguish dependency-owned compiled caches from Seon's bounded cache and world-specific projection. Naked-key syntax alone is not canonical schema publication. |
| Database layer | Strong writer/final-report/pull-many choices. Also route lifecycle/merge work to the corrected versioning/GC rows; identify writer serialization versus immutable pure derivation. |
| REPL/evaluation/printing | Split reading intent into reader/evaluator stage composition and printer document construction. SCI/nREPL are useful; quota writer is not Seon's printer (H5). Include the full evaluator body, not only setup. |
| Reload/adoption | clj-reload is a good ordering/incremental scan model, not pure scan or Seon adoption semantics. Its unload/remove/reload behavior cannot simply replace Seon's custody, private-state preservation, arm and record obligations. |
| Agent loop/Flow | Best basic model. Couple the minimal transform with the reviewed agent-loop's one settlement and terminal-event rules. A small transform example does not prove cancellation or crash behavior. |
| Render selection/value printing | Clerk first-match and Fipp document dispatch are useful. Keep Seon's explicit override/local/schema-specific selection order; do not infer precedence from arbitrary predicate registration. Include semantic cuts/budget obligations. |
| Web/SSE | Hyperlith is good for subscription order, CPU dispatch and signal coalescing; add actual HTTP-kit drain seam for bytes. Datastar adapter demonstrates response construction, not backpressure. |
| Test runner | Kaocha data/dispatch plus clojure.test fixture dispatch are good. Include the full filter and emphasize Seon's assertion evidence, recorded failures, member worlds and actual-exit ownership. |
| Shell/fs/edit | Separate these jobs. Babashka is useful for options/process execution; rewrite-clj for syntax-preserving zipper edits. No filesystem read/write/lifecycle model is actually supplied. Add a verified filesystem/JDK example and Seon's publication boundary; current suggested addition is unverified. |
| LLM provider | Provider dispatch shape is useful, but Java builders/callback adapters hide the wire work Seon owns. Prefer the provider skill and actual request/chunk-fold owners alongside a verified streaming parser/caller. The current excerpt does not supply that model. |

Missing **jobs**, not necessarily new dependencies: analysis/source-span joins and write-back; branch/store acquisition/release/GC; content cache and read-evidence reuse; schema/config publication with unchanged-input reuse. The old seam table partly covers them but the mandatory area selector does not tell their implementer where to start.

## Usability and size

Keep the mandatory reading principle. Do not shrink to a slogan checklist. The strongest sections are one constructor/one boundary, reuse Malli, named schema reuse, explicit state ownership, batched reads, eager work within effect/resource scopes, measured optimization, injected effects and assertions on data.

Make the main guide a precise set of rules plus complete examples. Each rewrite should say whether it is (a) an equivalence with preconditions, (b) a behavior change requiring the named contract/test change, or (c) a target design awaiting installation. That small label would prevent most high findings here.

Spend additional tokens on the three test examples in M7, one full giant-function decomposition, one safe re-keying boundary, and one full error propagation example including producer, schema, entrant and assertion. A further several thousand tokens of this material would be worthwhile. No need to expand to 50k merely to meet a size target.

Cut the one-word-local rule, blanket argument/loop/protocol restrictions, repeated census counts, unsupported “every/never” library claims, and duplicate unconditional quick rewrites. Keep one concise review checklist whose questions point to the complete examples. Keep research counts and dated evidence linked, not repeated in every required read. The existing owner-conflict notes should remain migration evidence; they must not serve as hidden exceptions to instructions an implementing agent will read as settled law.

## Files read

Drafts, authority and research, in full: `.agents/skills/data-oriented-clojure/SKILL.md`; `.agents/skills/clojure-testing/SKILL.md`; `docs/seon/architecture/reference-code.md`; `tmp/clojure-guide/guide-notes.md`; `CLAUDE.md`/the supplied `AGENTS.md` instructions; `docs/seon/specs/errors.md`; `docs/seon/specs/agent-loop.md`; `tmp/clojure-guide/idioms-src.md`; `tmp/clojure-guide/idioms-tests.md`; `tmp/clojure-guide/census.md`; `tmp/clojure-guide/canon.md`; `tmp/clojure-guide/map-cluster-test-tools.md`; `tmp/clojure-guide/map-data-contracts.md`; `tmp/clojure-guide/map-render-web.md`; `tmp/clojure-guide/map-repl-path.md`. Draft diffs were also inspected. External websites summarized by the research were not independently reverified.

First-party source/tests/resources, relevant definitions/ranges: `src/seon/db.clj`, `src/seon/schema.clj`, `src/seon/fn.clj`, `src/seon/turn.clj`, `src/seon/cluster.clj`, `src/seon/cluster/source.clj`, `src/seon/render/web.clj`, `src/seon/render/transcript.clj`, `src/seon/error.clj`, `src/seon/error/refusal.clj`, `src/seon/effect.clj`, `src/seon/flow.clj`, `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/sci/eval.clj`, `src/my/program.clj`; `test/seon/test_support.clj`, `test/seon/reset_edges_test.clj`, `test/seon/db_test.clj`, `test/seon/cluster/registry_test.clj`, `test/seon/cluster/agent_test.clj`, `test/seon/sci/admit_test.clj`, `test/seon/render/value_test.clj`, `test/my/examples_test.clj`; `resources/seon/schemas/seon.turn.loop.edn`. Fixture-name searches additionally covered the six fixture-containing test files named in the inventory; those files were not reviewed end to end.

Dependency files, cited functions/ranges (prefix `reference-code/`):

* `clojure/src/clj/clojure/{core.clj,core_print.clj,set.clj,test.clj,core/server.clj}`; `clojure/test/clojure/test_clojure/string.clj`.
* `malli/src/malli/{core.cljc,error.cljc,registry.cljc,util.cljc}`; `malli/docs/{function-schemas.md,reusable-schemas.md}`; `malli/test/malli/core_test.cljc`.
* `datahike/src/datahike/{api/impl.cljc,api/specification.cljc,db/transaction.cljc,pull_api.cljc,versioning.cljc,writing.cljc,query.cljc,datom.cljc,gc.cljc}`; `datahike/test/datahike/test/lookup_refs_test.cljc`.
* `sci/src/sci/{core.cljc,impl/interpreter.cljc,impl/utils.cljc}`; `sci/doc/interrupt.md`; `sci/test/sci/interop_test.cljc`.
* `clj-reload/src/clj_reload/core.clj`; `rewrite-clj/src/rewrite_clj/{zip.cljc,zip/move.cljc}`; `fipp/src/fipp/{edn.cljc,visit.cljc,engine.cljc}`.
* `core.async/src/main/clojure/clojure/core/async.clj`, `core.async/src/main/clojure/clojure/core/async/flow.clj`, `core.async/src/main/clojure/clojure/core/async/flow/impl.clj`, `core.async/src/test/clojure/clojure/core/async/flow_test.clj`.
* `babashka-process/src/babashka/process.cljc`; `hyperlith/src/hyperlith/{core.clj,impl/datastar.clj}`; `http-kit/src/org/httpkit/{client.clj,server.clj}`; `datastar-clojure/libraries/sdk-http-kit/src/main/starfederation/datastar/clojure/adapter/http_kit.clj`.
* `langchain4clj/src/langchain4clj/{core.clj,streaming.clj}`; `clerk/src/nextjournal/clerk/viewer.cljc`; `clj-kondo/src/clj_kondo/core.clj`.
* `nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj`; `nrepl/src/java/nrepl/out/QuotaBoundWriter.java`; `edamame/src/edamame/core.cljc`; `edamame/test/edamame/core_test.cljc`.
* `kaocha/src/kaocha/{testable.clj,type/var.clj,plugin/filter.clj}`; `editscript/test/editscript/core_test.cljc`; `konserve/test/konserve/memory_test.cljc`; `konserve/src/konserve/compliance_test.cljc`.

**Findings: 8 high, 10 medium, 2 low (20 total).**
