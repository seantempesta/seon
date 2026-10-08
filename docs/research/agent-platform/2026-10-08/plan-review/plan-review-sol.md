---
type: research
status: findings
created: 2026-10-08
lane: plan-review-sol
---

# Independent review of the agent-loop plan

2026-10-08 · lane `plan-review-sol` · documentation only

## Verdict and review basis

**Yes: the direction still makes sense. The revised §5 is substantially better than the plan at the start of this review. It is not yet the shortest executable path to a live agent's code being accepted, installed and reviewed by root.** Keep the single REPL entrance, help + own exchanges + block, task-driven work, ordinary branch custody and root's merge task. Resolve the remaining ownership and proof semantics before adding more machinery.

I read AGENTS.md end to end, the complete ledger, agent-loop.md, merge.md, owner-rulings.md and `git log --oneline a5fc4cb49..HEAD`. Initial review HEAD was `6d3c22dbb`; the ledger was subsequently revised in `235a3378d` and the specifications/indexes in `71f94ef6e`. I read the revised §5 and §6 before writing this verdict. Those revisions already retire obsolete context rows, add SCI-2/3 and R-1…R-9, and move the next live run ahead of the broad competency suite. These are improvements, not outstanding findings against the revised plan.

The assignment-home fix landed during review in `cd1e3455c`. The help finding was corrected in `35933f0ad`: **the real model request already rendered help**; the deficient probe lacked the render profile. Do not retain “all live models receive the raw help map” as an established cause. The failed-declaration, inherited-test and settings findings in `6d3c22dbb` remain valuable raw evidence, not proof that every diagnosis in their task intents is correct.

Code claims below cite fully qualified symbols. Proposed symbols in cards are explicitly described as proposed. Source inspection proves a seam's implementation; it does not prove its adoption in default. I did not read any other lane's plan-review report, alter source, transact against default, restart it, message another lane, or launch a model.

## Ranked findings

### 1. High: revised §5 still bundles too much into the first reviewed merge

The new order correctly starts with the live failures, completion and root's assignments. Its first end-to-end target nevertheless follows broad no-kinds/word conversions, merge-request redesign, automatic merge, declaration provenance and another reset. The goal requires a worker to finish a real task and root to review and land its change. It does not require automatic merge, every naming sweep, conflict norms or the entire competency matrix before that first positive proof.

Installed `my.program/propose-merge!`, `my.program/prepare-merge!`, `my.program/accept-merge!`, `my.program/diff`, `seon.cluster.source/prepare-merge!` and `seon.cluster.source/accept-merge!` demonstrate an existing merge path, not the proposed one-call `merge!`. Do not bolt a second path onto them. Identify the smallest coherent conversion of their current owner that gives root a task, an actual diff, a tested candidate and a durable decision. Retire superseded entry points with their callers. Integrate the request/task changes in that conversion rather than first finishing one merge design and then wrapping it in RT.

**Missing endpoint:** acceptance of database program entities is not yet evidence that files, loaded Vars and published program identity agree. Revised R-3 recognizes this, but the stated first target still ends at `merge!`. The goal needs the accepted definition callable through the ordinary entrance after write-back/reload, and its current proof recorded there. The merge must also tolerate a target that moved since review, preserve explicit target/home/merge roles, and retain a recoverable outcome across their commit window.

**Change:** make the first milestone “one worker fix, one root-held review task, one accepted diff, one installed callable change, current proof and closed request.” Do the mechanically required renames within that slice; schedule independent sweeps and automatic merge afterward. Keep mandatory integrity, current-positive-proof and main-red checks. This is sequencing, not an exemption from them.

**Research to settle it:** trace one existing request through `seon.task/propose!`, the two installed source merge owners and write-back/reload, on a disposable live-store branch. Record which guarantee is absent at each step. Price the smallest conversion from those observations. Add an acceptance proof for the installed result, not only a merged database row.

### 2. High: task completion and answer cards still have a dependency cycle and unnecessary intermediate representations

R-2 is the right redesign question, but the launch cards remain contradictory. RT-3 waits for F5; the ledger's F5 depends on F6; F6 depends on A1; RT-6 says A1 must be amended before launch and waits for RT-3. Following all those instructions cannot produce a first answer. The revised §5's standalone completion slice can break this cycle, but it must replace the dependencies in the cards, not remain a parallel explanation.

F6 also reads an answer by tested branch/agent, stores `:seon.test.member/answer-digest`, and then RT-5 replaces that with task identity and task-data digest. Root holds multiple questions. Agent or branch identity is therefore an insufficient answer identity from the first implementation. Installed `seon.task/agent-task` selects the latest assignment; installed `seon.task/assignment` refuses several. RT-3's oldest-held semantics are a real change, not just vocabulary.

A second unanswered seam is the lifetime of the answer: RT-6 makes it a component of the assignment, while RT-3 retracts the assignment on completion. Which current or historical value makes the completed answer accessible and validates the retained proof afterward? The intended history may be sufficient, but neither card names that read. Do not leave it to implicit component behavior.

**Change:** implement one assignment lifecycle with explicit task identity, assignment-scoped answers, declared proof inputs and completion/retraction together. Split code completion from merge-request creation as R-2 proposes. Avoid landing the agent-answer/answer-digest design only to move and generalize it immediately. Amend A1, F6, RT-3, RT-5 and RT-6 as one consistent design before dispatch.

**Research:** two questions held by root, two different answers using one generic proof, revision of one question, completion/retraction, answer retrieval afterward, and reassignment. Observe held assignments and proof validity at each transition. A proof that one branch's answer is green is not enough.

### 3. High: RT-9 mistakes green target tests for an unnecessary merge

RT-9 says the source task's proofs are red on the target until merge, and treats `assign-tx` refusing `:proofs-green` as “done, no merge request.” This is not implied by initial assignment while red. The target can acquire a different fix after the source forked. A behavior-preserving refactor can leave all target tests green. A source-added proof may not exist on the target. Documentation and cost work can also have acceptable program changes without the assumed red target behavior.

Installed `seon.task/assign!` checks `seon.test/verified?`, whose installed contract is current green test evidence, not program equivalence. The owner’s no-change rule explicitly also requires main to hold the **tested program**. RT-9's all-green shortcut omits that requirement. A root review task needs a proof of a decision on the requested change; source behavior proofs are evidence for review, not necessarily the root task's completion predicate.

**Change:** preserve source proof identities/digests on the request, but generate the root task's proof around the requested program and durable review outcome. Only the actual no-program-change case may finish without a request. Keep these ordinary task data and tests, not a separate review runner or new task kind.

RT-10 needs corresponding input evidence: copying result rows from a merge branch to the target is valid only if the tested program, task/answer inputs, fixture/input roots and schema context match the committed target. A matching reach digest alone is not that guarantee. It must also specify where review and reaching-test evidence remain accessible after merge/source branch retirement; this review has not established what retention GC preserves.

**Research:** concurrent alternative fixes, a new source proof, a behavior-preserving refactor, a schema-dependent proof and a moved target. Assert the request survives when there is a real diff, root's completion follows its decision, and copied green cannot survive changed proof inputs.

### 4. High: SCI-2/3 are the right next deletion, but runner green is not their acceptance proof

Run 3 recorded green in the automatic step while an inherited test at the agent's own REPL still called main's callee. This is exactly the kind of false confidence that can let a lane finish with the wrong context. The evidence belongs to `seon.sci.eval/an-inherited-test-never-sees-the-branch-redefinition-of-its-callee`.

`seon.sci.eval/fork-cluster-ctx` still loops over installed interpreted functions and calls `seon.sci.eval/install-function-contract!`; `seon.test/clean-request-handle` still derives a candidate `seon.sci.eval/base-ctx` rather than relying on the agent's live base. These are the two concrete owners to reconcile. SCI's per-context Var identity is necessary but does not by itself remove Seon's per-fork wrappers or their captured inputs.

**Change:** make SCI-2's proof cover the exact live shape, through the ordinary entrant: inherited test called directly, its `:test` function, `clojure.test/test-vars` and the automatic step must agree immediately and in a later evaluation. Include sibling recorder, connection, registry and supplied-input isolation. Then SCI-3 should delete the copy-induced re-analysis, reverse-closure work and clean-world interim with their obsolete tests/tasks. Revised §5 already gives this priority; keep it there.

**Research:** distinguish the dependencies a contract legitimately compiles once from custody inputs that must be supplied from the executing context. Do not replace stale arming snapshots with whole-schema/world acquisition on every call. Measure fork work and call work separately. Dense Var slots (SCI-4) remain contingent on profiles; they are not a correctness prerequisite.

### 5. High: two cost cards claim semantic equivalence that their proposed checks do not establish

**CO-8:** `:seon.schema/identity-only` describes how an object is represented, not a guarantee that its admission predicate is a class check. Installed `seon.schema/projection-value?` accepts maps holding registry and forms keys. Two maps of the same JVM class can have different answers. Live read-only probe `result/e5912af7330df` returned `{:same-class true :projection-validation [true false]}` in 1 ms. Replacing that schema with a class test broadens admission. `seon.env/environment?` also deliberately uses the class of its retained record instance to survive reload; hardcoding the newest generated class would change its semantics.

**CO-11:** `[:cat :any]` is not universal. Live `malli.core/validator` returned `[false true false]` for `[]`, `[1]`, `[1 2]` (`result/e6f7092afa331`, 11 ms). Malli's `malli.core/-=>-schema` obtains arity bounds and checks them separately inside its `-instrument-f` implementation. There may be a safe optimization that skips element validation while retaining argument cardinality, but “drop the always-true input arm” is an insufficient specification. `:any` output is a different case.

**Change:** retain each original predicate unless an exact accepted-set proof supports replacement. Rewrite CO-8 as a table of actual predicates and equivalences. Rewrite CO-11 around the fork's existing arity and validation mechanisms; keep full scope as the owner ruled. There is no measured need for a generalized universal-schema analyzer.

**Research:** malformed same-class maps, retained records after reload, released connections, wrong arities, varargs and guards. These are correctness counterexamples now, not microbenchmarks to defer. CO-7's schema-internal unwrapped predicates and CO-9/10's repeated preparation remain plausible smaller optimizations, with context-aware Var access and the approved refusals preserved.

### 6. High: the simpler context must still show call failures and proof feedback with no evaluation row

The help/exchanges/block architecture is good. Installed `seon.context/exchanges` begins with `seon.eval/of-agent` and retains evaluations whose call has model attempts. A provider failure or context refusal produces a call with no evaluation, per agent-loop.md. It cannot enter that evaluation-first traversal. A prose-only reply also has no evaluated form. The existing task `seon.context/a-lost-model-call-is-not-in-the-model-context` names this gap.

The model must learn what happened to its own call, not only what successfully evaluated. Automatic proof feedback must likewise survive a later context build without re-running the tests. CX-11 is still dependent on TX-0. This feedback matters to the next live run now, even if the eventual stored-call layout changes later.

**Change:** derive exchanges from the authoritative call/agent relationship, preserving zero-evaluation calls and their diagnostics, and attach the recorded proof-step summary to the relevant exchange. Keep help/profile failures explicit. Never restore system turns, captured prompts or an opening run to recover this information.

R-7 correctly identifies repeated work: the current `seon.context/exchanges` re-reads evaluations and calls `seon.repl/entity-emission` / `seon.repl/text` on each model call. Agent-loop.md's proc-held append claim does not describe that implementation. Store/reuse exact shown units or content-keyed render results through the existing cache; do not add another transcript store. CX-13's assignment boundary is important for root, whose successive questions must not accumulate unlimited unrelated history.

**Research:** failed provider call, read-only reader failure, prose-only reply, recorded proof failure, reassignment and restart. Compare the complete model input data with the MCP observation, including diagnostic/result handles. Measure increasing exchange counts; cached text reuse must leave historical units stable.

### 7. High: recording cards are cyclic; taskifying errors is still buried behind the entire error conversion

R-1/R-4 are justified. There is an actual launch cycle in README-recording and the cards: RP-11b waits for RP-7's zero-entry call recording; RP-7 waits for RP-11c; RP-11c waits for RP-11b. The ledger's simpler chain does not resolve that contradiction. RP-11c also mentions deleted generated-opening callers and waits for retired S4-02b. The RP-8 ledger row still promises preservation of resolved alias names while README-recording's answered Q-R1 explicitly rejects stored aliases and source rewriting.

**Change:** re-run the transaction census at one pinned post-rip revision before declaring seven machinery commits remain. Re-cut a loadable recording slice that handles an ordinary reply and a zero-entry reply together, then delete the old reading path. Use SCI's actual write hook, exact analyzed spans and the existing one entrance; do not add a second write-capture mechanism after SCI-1. Align RP-8 with the answered Clojure reload rule.

RT-12 changes the installed `seon.error/commit-call` notification producer. It should be designed with RT-7 and the single recorder, not treated as a reason to finish all error constructors, read carriers and presentation conversions first. Its current dependency TX-3b requires S5-05, which requires E-SREADS. Since CX-2 requires an assignment, old root messages no longer suffice to make root act. Deferring task generation can leave known system defects recorded but idle.

**Research:** count and attribute transactions for success, no-form reply, provider refusal, new error, repeated error and crash before recording. Separately prove “new signature creates root/steward work” through the installed recorder. Decide the smallest ordering that preserves error delivery before removing origin copies. Do not run two routing mechanisms during the cut.

### 8. High: a bounded test queue has no guaranteed progress when the model waits

Revised R-6 names this risk correctly. F4c's queue runs only when the next submission did not trigger its own test step. Installed `seon.turn/step-due?` triggers on every evaluated form, including `(+ 1 2)`. CX-2 lets a no-form reply wait. Therefore “the next pass will continue” is not a liveness guarantee. F4d's example non-changing form actually triggers another step, and its blanket “program equals base ⇒ empty queue” conflicts with outstanding answer/task proofs.

**Change:** make pending test work derivable and executable without another provider response. Use Flow's existing agent work/exit ownership, not a timer or new scheduler. Completion must exclude pending, unfinished and unavailable required evidence. Reuse a current red result as an observation without calling it acceptable green; distinguish this from `seon.test/verified?`.

**Research:** a proof consumes the bound, reaching tests remain pending, then the model waits; restart; answer-only task with unchanged program; test timeout with late actual exit. Assert finite progress, no overlapping member execution and no premature completion. All of this can be demonstrated with injected worlds and zero paid calls.

### 9. Medium: native EDN is a credible deletion direction, not a shortcut through the codec decision

R-5 should distinguish atomic values from relational component/ref sequences. The codec research at `codec/codec-sol.md` supplies useful dependency evidence, which I checked against the pinned owners: Datahike `86c3301a` and Konserve `e4334756`. Installed `datahike.datom/compare-value` and `datahike.datom/cmp-nil` disagree on unsupported same-class comparisons: the latter falls back to class order. A live read-only comparison of `[1 2]` and `[1 "x"]` returned zero (`result/e6f7092afa331`). Native whole values cannot safely enter those indexes until exact equality and total ordering are proved.

A natural-compare/digest fallback is not a total-order design. A digest identity alone is not exact equality. Existing `seon.blob/inline-or-stage` accepts text; “store EDN in blobs” is additional admission/serialization work, not an installed capability. Cold native collection restoration, query grammar, temporal roots and comparator-version compatibility also require dependency proofs.

**Change:** prefer shrinking Seon's codec to the attributes that actually need bridging immediately. Choose whole-value native storage only with a maintained-fork proof and an explicit deletion list. Do not claim it removes ordered relationship entities, ownership validation or ref joins. Defer general representation machinery unrelated to the next real task.

**Research:** total-order laws with forced collisions/equality equivalences; cold serialization with collection predicates as well as equality; query/pull/history/as-of; cardinality-many atomic composites; branch merge and ordered refs. CO-4 can avoid unnecessary decoding of already-native datoms without waiting for a universal EDN storage design.

### 10. Medium: SR-H's accounting and SCI bindings can retain far more than the purported byte budget

`seon.sci.eval/bind-result!` currently retains the value both in the context's result map and a SCI result Var. SR-H moves the map to Caffeine but proposes weight `4 × value-tokens`, falling back to shown tokens. A concise printed value, lazy sequence, closure or host object can retain a large graph. Presentation size is not retained heap. A cache with correct eviction can still leave the same value strongly reachable from SCI bindings.

**Change:** keep Caffeine and remove duplicate owners, but state the weighting guarantee honestly. Resolve handles through one owner; define the lifetime of any per-evaluation SCI binding and prove its retirement. Do not parse forms with another reader merely to pre-intern mentioned handles. Resolve that hook through the existing reader/SCI seams. SR-H need not grow into a general heap-size walker.

**Research:** small rendering of a large retained graph, evicted handles after subsequent calls, nil results, lazy cuts and old context retirement. Use retained-memory evidence as well as cache weights. “Nothing walks the value” and “bounded live-result heap” are separate promises; the card currently combines them without proof.

### 11. Medium: root questions need reopening semantics, and repeat counts are not durable merely because a future commit may carry them

RT-7 derives one question handle from its subject refs. RT-12 must ask again when a resolved signature opens. The same error entity or merge request can therefore produce the same question handle after its earlier decision. The cards must define how the new assignment/subject state invalidates the old generic proof and how the old answer remains historical. Hashing the subject identity is deduplication, not an opening identity or evidence of a new decision.

TX-3a's “repeat writes nothing” relies on installed `seon.fault/folded-tx` carrying counts on a later commit. Repeats held only in the process can disappear on a crash or remain absent from home status if no home commit follows. Its proof explicitly makes an extra home commit before asserting count 2. RT-12's immediate count-2 example needs to account for this. The durability law and a zero-home-commit repeat cannot both be asserted without naming the durable carrier elsewhere.

**Research:** reopen the same error signature after resolution, reassign the same question, update a stopped request, and crash before any opportunistic home commit. Decide whether counts are durable facts or expressly lossy telemetry. Reuse the evaluation's durable transaction where possible; do not introduce an independent retry or background store.

### 12. Medium: cost attribution and reporting still permit misleading acceptance evidence

The cost-pattern design's strongest choices are good: changed-input work, caller-held immutable reports with contiguity checks, demand-driven reach, and shelving generic SCC/delta machinery until measured. Keep them. But pre-rip costs are hypotheses about the next priority, not present rankings.

Installed `seon.profile/measure` snapshots global definition counters; its docstring says concurrent unrelated work counts. CO-0's complete called map fixes the hot-list cutoff, not request attribution. In a live multi-agent JVM a “derivation ran once” proof can observe another agent's calls. GC and virtual-thread allocation scope have similar limits.

**Change:** identify the request/world counter sink and test-visible observation scope before using call counts as concurrency-sensitive correctness assertions. Make CO-12 remeasure the actual post-SCI/post-rip request before ranking small predicate costs against world rebuilding and decoding. Commit raw evidence with parent/change identity and separate cold acquisition, warm execution and retained memory.

The live status observed here reinforces that need: the shared cache was effectively at its 512 MB budget, and `:seon.test.runner/reach-declarations` accounted for 244,930,688 bytes (47%), a 37% hit rate and 726 ms load. This is a present signal, not a causal proof. It supports measuring demand-driven reach before inventing another cache or optimizing nanoseconds of Var reads.

## Recommended executable path

1. Prove the live branch shape correctly: task-home assignment, root settings route, successful declaration recovery after a failed first definition, inherited-test/automatic-step parity. The assignment fix is landed; do not silently treat the other endpoints as installed.
2. Re-cut one assignment/completion/answer design and its proofs. Make waiting test work complete without another paid call. Ship task-data proof inputs directly, eliminating the F6→RT-5 detour.
3. Give root generated review/error work through the same assignment writer. Make a root decision, source proof green and accepted installed program three distinct facts. Preserve ordinary task tests and root's existing review authority.
4. Convert the existing merge owner once, with explicit target/home/merge roles and current test evidence. Run one real DeepSeek-flash task through root review, installation and cleanup. Use the defects it exposes for the next small cards.
5. Re-census and shrink recording/error cards; fix measured changed-input cost and bounded-retention failures at their owners. Mandatory correctness failures remain immediate. Automatic merge, norms, full naming sweeps and codec-wide changes follow actual need.
6. Run the broader competency cases, moved-target/conflict/crash cases and wire-format matrix. C0 compares the real model path's overhead with the ordinary entrance; absolute allocation figures need current evidence and a retained-memory interpretation.

This is not “go fast and patch.” The pause for ownership/proof research is what avoids constructing and then moving the same answer model, layering root tasks over an obsolete merge driver, or retaining two context/test worlds.

## Delete next; where growth is unnecessary

The most defensible deletion is SCI-3 after exact SCI-2 parity: copy-induced caller reinterpretation, its reverse-closure walk, the clean-test-world interim and superseded tests/tasks. Next, delete old reading/recording helpers with the last entrant conversion, and error messages when their task producer is installed. Avoid a second merge listener beside assignment wakes and avoid preserving a bespoke codec when dependency-native values have actually been proved.

Do not delete relationship ordering, integrity checks, current proof input evidence or error delivery simply because their old producer disappeared. Do not rebuild deleted capture/read-evidence schemas to make stale cards compile. The updated ledger's retirement of VEC-2, VEC-7 and SR-R is correct; their mentions and old opening prerequisites should disappear from all active launch cards as well.

The growth risk is concentrated, not hypothetical: inspected owner files totaled 18,005 physical lines across `seon.turn`, `seon.sci.eval`, `seon.db`, `seon.test.runner` and `seon.context` alone (comments/contracts included, not a source-LOC accounting claim). New generic queues, universal schema classifiers, transcript caches, error routers or version-two merge owners would compound it. Prefer deletion in those owners over a new framework. A generic root question proof is useful only with explicit task inputs and lifecycle; a template hierarchy is not required for a single demonstrated root job.

## Evidence and verification limits

Initial `bin/seon status` took 1.78 s and returned default `:faulted`, PID 15625, start instant `2026-10-08T13:43:14.038Z`, loaded archive `0de932400`. MCP `runtime_status` confirmed degraded state. Reported faults included a `seon.db/transact!` stored-test digest mismatch, the failed live slug proof, and missing completed collection evidence. Heap live was 686,296,840 bytes, maximum heap 4,294,967,296 bytes. These are shared operating findings; neither runtime availability nor run-3 green is whole-loop health. No recovery action was taken by this lane.

Read-only JVM probes used MCP agent `plan-review-sol`, cluster `default`. `result/eb3c32b445049` (1 ms) confirmed installed owners and the absence of deleted `seon.turn/system-turn` and `seon.turn/compact!`. The REPL skill still described those deleted APIs when read; its living claims need correction. The probe envelope exposed `:seon.repl/ms`, but not `:seon.repl/alloc-mb`; no allocation measurement is inferred from silence. Result handles are live observations, not durable substitutes for the exact forms/results recorded above.

`clj-kondo --lint src script test --cache false` completed in 7,991 ms: **0 errors, 1,450 warnings**. This is zero errors, not zero findings. Foreign working changes were present in `src/seon/context.clj`, `src/seon/turn.clj` and turn tests during inspection; several subsequently landed. Symbol evidence was checked against committed source where an in-flight change affected it. No foreign path was staged or edited. Publication, adoption, reload and proposed card correctness are not established by this documentation review.

No test reaches this Markdown as a program input. The requested candidate check names `seon.instrument-test/host-boundaries-enforce-per-arity-declared-schemas-in-both-modes` as an installed contract/arity baseline; it is not a regression proof for CO-8/11 or the unimplemented task/merge proposals. Candidate-check result and final landing evidence are recorded here after the gate returns.
