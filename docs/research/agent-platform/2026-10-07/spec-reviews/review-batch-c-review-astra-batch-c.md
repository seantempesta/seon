---
type: review
status: changes-required
created: 2026-10-07
reviewer: review-astra-batch-c
---

# Batch C — review-astra-batch-c

Changes requested: `7a6878374`, `8df768c25`, `bd5e0e8c9`, `fdc1fe9e9`. Reviewed their diffs and the combined committed source at `fdc1fe9e95bf408894c95709767a7fd7e42f31ec`, from a Git archive. `bd5e0e8c9` is the heap-retained change, regardless of its incorrect message. This is one independent review, not a claim that both reviewers approved.

**Verdict: changes required.** Throwable admission introduces irreversible evidence loss; suffix compression changes failure attribution. The broader requested guarantees also have residual depth and lifecycle gaps. No tests were run by this lane. Findings below distinguish new regressions from pre-existing defects and source proof from installed proof. File/line references throughout are to the reviewed archive, not other lanes' changing working files.

## Severity-ranked findings

### R1 — P1, new: admitting a Throwable silently destroys large agent-authored ex-data

`src/seon/sci/admit.clj:348` now passes the throwable to `error/chain`. That is explicitly the **bounded** chain: `src/seon/error/refusal.clj:543` declares `:seon.error/bounded-chain`, and `:547` supplies `bounded-data`. A string longer than 16,384 characters becomes an object-name string (`:278`, `:363`, `:478`), not a retrievable reference. The chain docstring itself acknowledges this at `:540`. Admission then writes that replacement into both its semantic value and EDN (`src/seon/sci/admit.clj:477`, `:781`).

Concrete case: return `(ex-info "boom" {:payload (apply str (repeat 20000 "x"))})` as an ordinary value, with an admission storage allowance large enough to hold it. Before this commit, `Throwable->map` preserved the payload. Now the admitted data contains a `#object[...]` string instead of the 20,000-character string. Even unbounded admission takes this path. An ordinary returned throwable has no fault occurrence whose blob could restore the payload. This violates the ONE-printer law and “agents' values never clipped” independently of the already planned errors cut.

**Fix:** use the complete referenced cause-chain representation at value admission (the existing `recorded-chain` accepts an explicit projection), or retire bounding from the chain owner with its contracts and consumers. Pass the admission world's projection explicitly. Keep presentation limits in the value printer. Tighten the producer contract to describe the complete result. Extend `seon.sci.admit-test/an-admitted-throwable-is-its-cause-chain-never-a-trace` to assert exact ex-data after admission and EDN readback, with a payload above the former evidence limit and below storage admission's limit. Its current `{:a 1}` at `test/seon/sci/admit_test.clj:588` cannot detect this regression.

Evidence: source-proven data flow; candidate execution and storage round trip **unverified** because these definitions are not installed in default.

### R5 — P1, known pre-existing blocker to a blanket cycle-safety claim: the evaluation boundary can still hang before the safe chain walk

The changed chain walker is cycle-safe, but `kernel/failure-value` first calls `fault/carried-receipt` (`src/seon/sci/kernel.clj:833`). That reader walks `(iterate ex-cause failure)` without a seen-set (`src/seon/fault.clj:338`). Its own docstring already records the hang and task `465ff60ccda0` (`:329–335`). Later, `refusal/refusal` has another unguarded cause loop (`src/seon/error/refusal.clj:889–904`). Therefore a legal two-Throwable cause cycle cannot be declared handled through the one REPL path merely because `chain` terminates.

**Fix:** use the existing cycle-safe `cause-links` value in these readers and the boundary; reuse the observation instead of starting another traversal. Keep task `465ff60ccda0`; do not file a duplicate. Root must verify this with bounded destructive isolation, not hang default. The current cycle regression at `test/seon/error/refusal_test.clj:241` calls only `chain` and `root-frame`. No cycle was executed during this review.

### R2 — P2, new: shared-tail compression loses which frame belongs to the root failure

`stack-frames` discards the common suffix at `src/seon/error/refusal.clj:173`, without recording the shared suffix's length or starting frame. `diagnostic` still selects the first frame of the deepest nonempty compressed link at `:777–785`. That is no longer equivalent to `root-frame`.

Concrete input: outer throwable frames `[W A B]`, root cause frames `[A B]`, all first-party frames. The new chain is outer `[W A B]`, root with no frames. `diagnostic` reports `W`; `root-frame` correctly reports `A` because it reads the root's full trace (`:218–223`). More strongly, a root trace `[B]` produces the same compressed chain as `[A B]`. The stored representation cannot tell those failure locations apart. The same loss affects a suppressed link whose trace is a suffix of its suppressor. Aggregate preservation of distinct frames does not preserve their association with each failure.

**Fix:** derive the principal frame from the original root before compression, using the existing owner; preserve enough per-link shared-tail information to locate each cause/suppressed failure without duplicating the trace. Update the link schema with that representation. If `without-links` removes an enclosing link, transfer or repair any references to its frames; it currently only renumbers relationships (`:563–578`), while `kernel/failure-value` removes SCI location links after compression (`src/seon/sci/kernel.clj:837`).

Extend `seon.error.refusal-test/the-frame-comes-from-the-root-cause` with identical traces, strict suffix traces, an outer wrapper with an extra first-party prefix, and suppression. Assert `diagnostic`'s frame equals `root-frame`, and each link's location remains recoverable. The current fixture's distinct root prefix does not cover this case. Candidate runtime reproduction **unverified**; the selection error follows directly from the concrete arrays above.

### R3 — P2, residual coverage gap: long-lived starts outside the new wrapper still capture the evaluation

The two changed connection sites and graph constructor are covered (`src/seon/cluster/store.clj:654`, `:691`; `src/seon/flow.clj:132`). The requested whole-class audit is not complete:

- `src/seon/db.clj:116–124`: sampling `connection-generator` calls `fresh-connection`, whose `d/connect` is unwrapped. Sampling inside an evaluation starts writers with that evaluation's bindings. The returned connection has a lifetime beyond the call.
- `src/seon/render/web.clj:138–143`: the first sample of `mult-generator` realizes a process-held delay containing `async/mult`. Its parked go-loop captures the first sampler's frame and is retained by the Var. This bypasses `start-graph!` entirely.
- `src/seon/cluster/store.clj:575`: `open-store!` still opens its main connection in the caller's frame. The notes deliberately exclude the boot call, but this public constructor and `seon.cluster/acquire-root-store!` (`src/seon/cluster.clj:435`, `:468`) are callable from evaluations; the contract does not make this boot-only. Tests also call this constructor inside their evaluation worlds.

The dependency confirms the mechanism: Datahike's writer starts its processing and commit `go-try` loops at `reference-code/datahike/src/datahike/writer.cljc:122`, `:231`; core.async captures the frame at `reference-code/core.async/src/main/clojure/clojure/core/async/impl/go.clj:1048` and restores it around execution at `:895–918`.

**Fix:** cover these resource creation owners too, or remove the process-held generator resource where it is unnecessary. Do not prohibit legitimate evaluation callers to hide the gap. Carry any needed projection as an explicit input, then enter root bindings at resource creation. Extend the writer regression to the overlay and main-connection entrances; add a real graph/mult entrance regression. Do not restore the caller's entire frame.

These are pre-existing uncorrected entrances, not leaks introduced by `bd5e0e8c9`. The source proves binding conveyance; additional heap-retention measurements and initialized-state behavior are **unverified**.

### R4 — P2, residual depth gap: an explicit traversal stack does not make set rebuilding depth-safe

`referenced` now descends iteratively, but rebuilding a changed set still calls `(conj (disj held original) r)` at `src/seon/error/refusal.clj:423`. Hashing `r` recurses through an un-hashed nested vector: `reference-code/clojure/src/jvm/clojure/lang/APersistentVector.java:163–174` calls `Util.hasheq` on each child. Thus a deep set member containing a place still overflows during reconstruction, even though descent no longer uses the JVM stack.

Concrete probe for root: build 10,000 nested vectors around an atom, warming each original vector's hash as it is constructed; put the resulting vector in a set; pass that set to `references` with a nil projection. Original insertion succeeds because each child hash is cached. Referencing the atom rebuilds every vector; inserting that newly rebuilt, un-hashed vector into the result set recursively hashes all 10,000 levels. The added regression (`test/seon/sci/kernel_test.clj:190`) uses only a vector around `[]` and never exercises this collection reconstruction.

**Fix:** preserve depth safety through reconstruction/hash operations as well as descent, or compose the existing depth-safe value owner. Extend the regression through the public error entrance with a changed set member, nested map values, and map entries. Assert the error data remains available, not merely that a bounded printer returns a string. Do not make deep agent values illegal to avoid the walk. This edge remains **unverified by execution**; recursive hashing is verified in the pinned dependency source. It is a surviving limitation, not a newly introduced failure for that input.

## What passed source review; remaining verification limits

### Error frames

- `cause-links` uses an explicit loop/seen-set (`refusal.clj:194–203`); `walk-links` retains each encountered throwable's class, nonempty message and referenced data and queues suppression with its parent index (`:461–476`, `:505–521`). Normal cause cycles and suppression terminate at this owner. This does not establish the complete evaluation guarantee in R5.
- SCI conversion keeps distinct `[where line column]` triples and the outermost occurrence (`kernel.clj:781–798`). Pinned SCI supplies the callstack from ex-data (`reference-code/sci/src/sci/core.cljc:419–422`). Unique location triples survive, but repeated call order/depth does not; consumers should not treat the deduplicated vector as an exact call sequence. Innermost attribution with recursive repeated sites is **unverified** (`refusal.clj:648–697` reads it for caller attribution).
- `current-frames` has no remaining source/script/test Clojure reference in the archive. `first-frame-answer` skips its own JVM frames, stops on the first answer, and preserves the false-to-stop sentinel (`refusal.clj:598–620`). `calling-definition`, instrument's caller lookup and filesystem caller lookup are converted (`:622`; `src/seon/instrument.clj:148`; `src/seon/fs.clj:168`). Armed caller attribution is **unverified**, not inferred from this search.
- Complexity correction: `stack-frames` still retrieves both full trace arrays and compares their common suffix (`refusal.clj:173–177`). With L identical traces of depth D it still performs O(L×D) comparisons/array copying, although retained frames and classification work shrink greatly. The notes' O(distinct frames + links) description is not the total algorithmic cost. Correct the claim; keep the measured allocation improvement as evidence attributed to the original lane.

### Root bindings

The basic mechanism is sound: `Var.dvals` is an ordinary ThreadLocal whose fresh frame is `Frame.TOP` (`reference-code/clojure/src/jvm/clojure/lang/Var.java:51–75`); reset and restoration are paired in `finally` (`flow.clj:88–91`). Root `*ns*` is `clojure.core`, verified at the live JVM. It is deliberately different from the calling evaluation's namespace.

| Start family | Review result |
|---|---|
| Branch / overlay writer | Wrapped at the actual `d/connect`; explicit configuration retained. |
| Cluster, hosted branch, agent and work-launcher graphs | All use `start-graph!`: `cluster.clj:3444`, `cluster/host.clj:110`, `cluster/agent.clj:1364`, `flow.clj:703`. Cluster pages mult is a join inside the wrapper (`cluster.clj:3452`). |
| Proc world / projection | Executor restores the supplied projection/state (`flow.clj:103–112`); transition/transform dispatch carries the explicit environment (`:386–406`). Writer listener-failure handler closes over its own environment (`cluster/store.clj:731–739`). |
| Proc initialization / joins | Run synchronously inside root bindings. Init is outside `in-world` (`flow.clj:394`), and projection installation in the executor does not wrap synchronous init. Current turn/mailbox/schedule init consumes explicit args; armer registration uses the route's explicit connection. Full branch-projection and init-time contract behavior is **unverified**. Add a start-graph integration test, not only a helper test. |
| Direct virtual threads | Cache derivation (`cache.clj:226`), graph stop (`flow.clj:298`), shell task (`shell/jvm.clj:90`), test body/watcher (`test.clj:289`, `:300`), web feed and worker executor (`render/web.clj:2826`, `:4068`) do not inherently convey Clojure Var bindings. Explicit closure retention is a separate concern. |
| Futures | Kernel futures intentionally carry the evaluation and owned-work count until exit (`sci/kernel.clj:669–691`); do not erase that custody. Subprocess output future (`cluster/process.clj:275`) belongs to the awaited process operation. No new detached service future is introduced by this patch. |
| Generator mult / generator connection / main store connection | Missed entrances; R3. |

The branch regression proves the right leak class by asking an actual writer transaction function which sink it sees (`test/seon/cluster/store_test.clj:337–362`). The helper test proves conveyance/reset (`test/seon/flow_test.clj:63`), but would remain green if the call were accidentally removed from `start-graph!`. Neither is evidence of a post-restart heap plateau here.

### Printers-errors

No additional message-information regression found in the converted `fdc1fe9e9` sites. The useful values remain as follows:

| Producer | Evidence retained |
|---|---|
| Form/exact edit refusal | Complete candidate vectors and ambiguous line numbers, computed only on refusal (`src/seon/edit.clj:327`, `:339`, `:397`). No replacement printer was introduced. Missing regression: inputs exceeding the deleted byte cap, asserting all candidate/line data. |
| Merge acceptance / write-back | Changed identities remain keys of expected/offending digest maps (`cluster/source.clj:813`, `:1238`). Existing tests assert those maps, including the long write-back row-changed branch. |
| Fault entity acquisition | Non-nil offending value stays at `:seon.error/offending` (`error.clj:2385–2389`); nil is represented by absence, per the storage rule. |
| Unknown test evidence | Message and offending data passed as maps to the value printer (`problems.clj:633`, `:654`). The existing golden covers failed-test prose, not this changed family. |
| Schema cycle / missing output | `:seon.schema/cycle-path` (`schema.clj:175`) and `:seon.schema/value` (`:705`) remain data. |
| Invalid / nilable schema | Definition and Malli cause remain (`schema/internal.cljc:498–506`); suggested base shape is newly explicit at `:544`. Assert it as data in the nilable test. |
| Unarmed function / malformed PREPL | Original throwable remains the cause (`sci/arm.clj:41–44`; `script/seon/operator.clj:230–232`). |
| Refused fixture write | Full transaction/report and `[index entity]` remain in ex-data (`test/seon/test_support.clj:253–257`); changed assertion reads the offending entity as data (`test_support_test.clj:83`). |
| Situation trigger | Message id replaces printed lookup-ref (`cluster/agent.clj:399–400`); the producer explicitly builds `[:seon.message/id id]` (`src/seon/bootstrap.clj:155–159`). |

This is a review of converted sites, not a claim that all printing has been removed. Existing error bounding, `execution-error-text`, and error/prose renderers still exist. No new value-printing destination was introduced by these message replacements. R1 is the material cross-slice conflict with preserving error data.

## Verification and landing evidence

- `bin/seon status` and MCP `runtime_status` reached default, PID 30874, source archive `a8e3bde5b475e54fead8ed107a86d67d47d10a13`. Runtime was **faulted**, with 14 open faults, 105 failed tests and 43 unknown tests; live heap reported about 3.48 GB, max about 11.0 GB. This is baseline health, not attributed to batch C. Existing over-bound tasks included `d46e436e65f4`, `5f54ae50d841`, `c87892f697b8`.
- Exact installed-API probe, MCP JVM mode, cluster `default`, agent `root`: `(mapv (fn [s] {:symbol s :loaded? (boolean (find-var s))}) '[seon.flow/call-with-root-bindings seon.error.refusal/post-order seon.error.refusal/current-frames seon.error.refusal/chain])`. Result `result/e40a27d40bc99`: false, false, true, true respectively. **The reviewed definitions are not installed.** The envelope provided value and handle, but no timing; no timing is invented here.
- Dependencies read at gitlinks: Clojure `3bc2b3e91f`, core.async `dc35f3e0d7`, Datahike `d9121bbbad`, Malli `43c6dca8d2`, SCI `ce0aa53b9b`. Malli compile owner is `m/schema`/`m/function-schema`, via the existing contracts checker, not a parallel validator.
- `clj-kondo --lint src script test --cache false --parallel` ran in the `fdc1fe9e9` archive, with ignored dependency lint configuration linked as `bin/lane` specifies: **0 errors, 1,523 warnings, 6,275 ms**. This is a whole-input static scan, not an ordinary operation; its cost follows all source/script/test files. A docs-only review diff changes none of those inputs.
- Called the already loaded `seon.contracts-compile-test/check` through MCP JVM mode, with `:root` set to that archive and `:paths` the 23 Clojure files touched by the four requested commits. Supplied default's projection as `:candidate`; the checker compares packaged forms before reuse. Result `result/ed4ed66b0fa68`: `{:files 23 :findings [] :elapsed-ms 416.716791}`. This statically compiles declared contracts against the archive population; it does not adopt or execute the implementation. No baseline argument: this is compile proof, not a claim that every changed function passes contract-completeness policy.
- Foreign boundary: working-tree edits belong to `printers-rest`, `printers-round2`, `examples-world` and other review work. In particular, refusal/error/schema resources and tests changed during inspection. Those bytes were excluded by reviewing the archive; no reload, restart, test run, worktree, foreign file edit or foreign-session action was performed.
- Changed path: this review only. Source/test delta: **0/0**. Publication, adoption, armed runtime behavior, memory improvement, browser paint and central test results remain **unverified**. Review findings are not completion of the implementation tasks.

## Exact focused tests for root's central run

No test runs from this lane. Existing focused identities:

1. `seon.sci.kernel-test/a-failures-cost-follows-its-distinct-frames-not-its-depth`
2. `seon.sci.kernel-test/an-error-holding-a-ten-thousand-deep-value-keeps-its-error`
3. `seon.sci.admit-test/an-admitted-throwable-is-its-cause-chain-never-a-trace`
4. `seon.error.refusal-test/the-frame-comes-from-the-root-cause`
5. `seon.error.refusal-test/the-frame-is-the-first-one-the-caller-wrote`
6. `seon.error.refusal-test/a-cleanup-failure-survives-beside-the-primary`
7. `seon.error.refusal-test/every-producer-references-the-worlds-it-was-handed`
8. `seon.cluster.store-test/a-branch-writer-never-keeps-the-opening-evaluations-bindings`
9. `seon.flow-test/a-go-loop-started-with-root-bindings-keeps-none-of-the-callers`
10. `seon.flow-test/graph-construction-joins-every-declared-tap-before-resume`
11. `seon.flow-test/stop-joins-the-graph-only-after-its-procs-exit`
12. `seon.edit-test/selectors-refuse-ambiguity-and-use-parsed-dispatch`
13. `seon.edit-test/exact-edit-counts-before-changing`
14. `seon.test-support-test/a-refused-fixture-write-is-reported-at-the-write` (platform)
15. `seon.schema-test/canonical-definition-refuses-a-function-schema-without-output`
16. `seon.schema-test/canonical-self-references-refuse-at-registration`
17. `seon.schema-test/a-nilable-value-schema-is-refused-at-admission-never-at-build`
18. `seon.cluster.merge-test/a-candidate-merges-only-its-tested-green-program` (declared long)
19. `seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span` (declared long; includes row-changed evidence)

Also centralize the existing `seon.instrument-test`, `seon.agent-situation-test`, `seon.cluster.agent-arming-test` namespaces for armed caller/location and lifecycle coverage. Prior reds reported in the original notes are not passes; route each current red to its owner. The kernel cost test's wall-clock/allocation assertions are measurement gates, not deterministic data proof.

Before approval, extend the named tests for R1–R4, complete candidate/line preservation, nilable base-definition data and unknown-test evidence data. Add a graph-start integration case asserting no caller sink/world is retained while the graph's explicit projection, connection and agent identity remain available at initialization and execution. R5 needs the owner's bounded isolated cycle regression. These additions are requested proof, not tests claimed to exist.
