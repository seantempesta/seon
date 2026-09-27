---
type: review
status: revise
created: 2026-09-27
spec: docs/research/agent-platform/one-repl-path-spec-2026-09-27.md
spec-commit: e773f68cf622d77dfb3919339927360e285be2ad
scope: review only
---

# Review: one REPL path specification

Review date: 2026-09-27. Scope: review only; this document is the only committed change.

**The direction is right, but the instructions are not executable as written.** The prototype misses root writes, can associate the wrong implementation with an exact-looking source location, loses completed values on a bookkeeping failure, and leaves callers of deleted functions. Its small-batch timings reproduce. Those timings do not establish a complete, total, single production path.

Read first: the complete working-tree `AGENTS.md`, especially the one-path and totality laws. Then the complete specification at `e773f68cf`, its appendices, and `tmp/one-path/{pipeline.clj,entrants.clj,corpus.clj,results-final.edn,sci-location.diff}`. The pipeline file equals appendix B. The REPL, data-oriented Clojure, and testing skills informed the review. No production or test implementation was changed, no test request was started, and default was never stopped or reset.

Evidence baseline: checkout HEAD `c932dd4b4eaca55df11db4c9ccb8302a06e3e016`, advancing during review to `58d7f5f0d`. Source references below were checked with `git grep/show HEAD`, not inferred from the spec's older line numbers. The cited pipeline, SCI evaluation, reader, turn, and write-back seams remained stable; test-runner references were rebased after concurrent commits. Pinned dependencies: SCI `743d1c4317d95af9d1af74928392b913c0d1b91d`, clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`, Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`.

The installed JVM was PID 99936, start `2026-09-27T18:57:53.464Z`, from archive `4ff5053eeeb61eb7f2270b8f6fc44240dbf36db2`. Both `bin/seon status` and MCP `runtime_status` reached it. Its initial health was **faulted**, with nine open fault signatures, 121 failed tests, 17 stale Vars, and three replaced roots. Its cache weighed 510,357,294 of 512,000,000 bytes. These are pre-existing conditions, not evidence of a healthy installation or defects caused by this review. Probes below exercised installed dependencies and prototype code, not a newly installed production pipeline.

## Findings

Severity: **P0** blocks implementing this specification as a correct, loadable cut; **P1** violates a required behavior; **P2** misstates evidence or estimates.

### R1 — P0: an env watch is not a definition/root-write observer

**Evidence.** `pipeline.clj:111–158` starts with no held Vars, adds a Var only when its namespace-map binding changes identity, and checks only held roots. SCI `eval-def` mutates a same-generation Var's root and metadata before returning the env map (`reference-code/sci/src/sci/impl/evaluator.cljc:25–54`). A redefinition in a retained context need not introduce a different Var into that map.

Installed-REPL probes, each in a fresh private `sci/init` context, first evaluated `(def f 1)`, then installed the prototype watch. All three subsequent inputs returned `2` with **zero effects**:

```clojure
(def f 2) f
(intern 'user 'f 2) f
(alter-var-root #'f inc) f
```

This is also a missed-arming defect, not merely incomplete metadata. With the watch installed before `(def f 1) (intern 'user 'f 2)`, the callback saw only root `1`; the final root was `2`.

The relevant SCI ownership seams are `namespaces.cljc:610` (`sci-intern`), `:823` (`sci-alter-var-root`), and `utils.cljc:368,394` (`owned-var`, `bind-root!`). A same-generation write changes the root without an env swap. For an inherited Var, `owned-var` may publish a copy **before** `bind-root!` writes its new root: a watch can observe the old root at the wrong point. The latter also marks `:sci/rebound`; the prototype ignores that evidence.

Re-entrancy independently fails. Both observers use the single watch key `::observe` (`pipeline.clj:140,158`). The sequence “outer watch; define a; inner watch; define b; stop inner; define c; stop outer” returned `{:outer [user/a], :inner [user/b]}`. Definition `c` had no observer. A distinct key alone would still need a rule for nested ownership and duplicate arming.

**Concurrency distinction.** Ordinary forks do not share their env atom: `sci/core.cljc:334–340` makes a new atom and generation. A genuinely concurrent two-thread probe on sibling forks returned values `[1 2]`, base value `0`, effects `[[user/f] [user/f]]`, and both threads exited; MCP reported 9 ms. Thus “other agents share the base” is not by itself a watch race. Conversely, this does not prove same-context overlap safe: the watch's volatile accumulator is not a concurrent accumulator, and callbacks inspect mutable roots after swaps. The spec supplies neither an ownership rule nor evidence for that case. Shared mutable objects inside Var roots are also not copied by a fork (R8).

**Smallest fix.** Replace the env-diff inference with an execution-scoped SCI observation seam at the actual successful root/metadata write, after generation ownership is resolved and before the defining operation returns. Pass the operation, context, evaluated root, and source occurrence as values. Cover `def`, `intern`, and root alteration through their real dependency owners; tag arming's own root replacement so it is not a second authored definition. Scope nested observations explicitly. Keep namespace removal observation at its owner. Do not add per-entrant watches or seed-and-rescan the entire namespace table.

### R2 — P1: start locations and “companions” do not prove the claimed exact join

**Evidence.** The prototype groups kondo definitions by unqualified name, converts coordinates, and selects either equal **start positions** or operator-restricted containment (`pipeline.clj:259–318`). It does not compare two full source spans. `:origin` stores only line/column, and `first` chooses a usage when several share that position. It does perform coordinate arithmetic and name grouping despite §4's “no position arithmetic and no name search.” These operations are not inherently wrong; they are not the stronger provenance guarantee asserted by the spec.

R1 produces an actual false attribution, not just a theoretical ambiguity. This input at the installed REPL returned `2` for the invalid string argument, with no diagnostic, a `:matched` join, and `{:applied true}`:

```clojure
(defn review-rebound {:malli/schema [:=> [:cat :int] :int]} [x] 1)
(intern 'user 'review-rebound (fn [x] 2))
(review-rebound "bad")
```

The admitted row's source was the first `defn`, whose body returns `1`. Thus an exact-looking location neither described the final implementation nor established that its contract remained armed. MCP elapsed: 97 ms.

The SCI patch is a useful location correction, with limited proof. `analyzer.cljc:859–869` supplies a fallback location; `:2099` merges expansion metadata over call metadata; S1 at `:2106` binds the location of **the expansion value**. The macro can provide different metadata. `with-top-level-loc` (`:1800–1822`) still carries only line/column, not an immutable authored occurrence or expansion identity. Comparing the supplied `locs-orig.edn` and `locs-patched.edn` confirmed ten changed items and sixteen changed Var positions, including two internal record helpers; fourteen newly matchable binds is consistent with that count. I did not patch or reload SCI or rerun its tests. The claimed suite result is supplied evidence, not fresh review proof.

Record helpers expose another gap. `records.cljc:425–473` generates `__->R__ctor__` as an actual function, and `->R` calls it. For `(defrecord ReviewR [a])`, the live prototype returned the helper as `:unanalyzed/:no-definition-at-origin`, not `:companion`. It admitted both factories as host-bound rows, each with the entire identical `(defrecord ReviewR [a])` source. No companion classification exists in `pipeline/join`. Shared origin alone does not prove that an arbitrary extra bind belongs to a declaration, or explain how two factory rows acquire one record declaration without replaying it twice.

**Smallest fix.** Specify one immutable source-occurrence/expansion provenance value at SCI's producer, including source identity and exact span. Join only observed successful writes that retain that provenance. Represent generated members' relationship to their generating declaration explicitly, using the actual expansion; do not special-case constructor spelling or infer membership from shared line/column. For unavailable provenance, return a typed unjoined result and preserve the value. Prove the row's source, contract, and acquired behavior as well as its join label. Q1-A belongs in this same provenance work, not as an optional patch to an already “exact” join.

### R3 — P0: totality fails before the completed prefix reaches the batch

**Evidence.** `read-evaluate-loop` holds completed entries in a local loop (`pipeline.clj:172–208`). Namespace capture, source slicing, reader bookkeeping, and loop setup surround the small try that retains the evaluated value. Its outer catch (`:222–225`) replaces the entire prefix with a single error entry.

This exact finite probe used a private plain SCI context:

```clojure
42
(def ns-name (fn [_] (throw (ex-info "ns-after failed" {}))))
43
```

`pipeline/run` returned only `{:ordinal 1, :error "ns-after failed"}` and reply `{:values ["ERR ns-after failed"], :diagnostics [] ...}`. Value `42` and the successfully returned Var disappeared; `43` was never evaluated. The failing bookkeeping expression is the prototype's evaluation of unqualified `(ns-name *ns*)`. MCP elapsed: 3 ms. Changing that one expression would fix this example but would not fix prefix loss at the boundary.

The later-stage wrapper does preserve an already-attached batch on a synchronous throw. However, a lazy value that throws during `reply` printing left both entry values intact **and no `:reply` at all**; the only diagnostic was `reply failed: print failed` (3 ms). One entry's bad printer therefore removed the whole reply projection. There is no fault-recording stage in the prototype. `stage` (`:48`) catches throws, but cannot make a stage that never returns total, and its own diagnostic/timing construction is outside a second protected result boundary.

Production instructions do not close this proof gap: saying every stage is total and pointing to one injected analyzer throw does not prove ending-namespace capture, contract-output rejection, per-entry rendering, failed fault delivery, writer refusal, refused refusal settlement, or actual timeout exit. S7's “capture in finally” must not allow that capture to replace the body value.

**Smallest fix.** Publish each evaluated outcome into the fold's retained value before any subsequent bookkeeping. Make each subsequent operation append a local diagnostic while retaining that value and the prefix. Give printing a per-entry fallback/handle through the existing floor, and specify the final reply when recording itself is unavailable. Reuse the existing bounded execution owner for blocking stages, retaining ownership until actual exit. Test injected failures at every boundary, including diagnostic delivery; do not solve this with one catch around the whole batch.

### R4 — P1: the effect reduction can persist definitions already removed

**Evidence.** `join` reduces only `:bind` effects, keyed by Var object (`pipeline.clj:296`). It ignores subsequent unmaps and namespace removal. `derive-rows` consults those held Vars even after they have been unmapped. A live probe of a contracted `review-removed`, followed by `(ns-unmap *ns* 'review-removed)` and `(resolve 'review-removed)`, returned effects `[:bind :unmap]`, values `[Var nil nil]`, yet admitted `user/review-removed` and reported `{:applied true}` (109 ms).

S14's proposed removal rows cover “stored identities”; a name born and removed in the same batch is not necessarily in the basis. Nor does this rule define remove-then-redefine, namespace removal, or multiple Var objects for the same identity. Merely appending both upsert and delete rows leaves correctness to ordering not specified here. S16 is also scheduled after the cut even though it changes deletion's writer from an immediate transaction to batch settlement.

The claimed h13 proof does not exercise successful deletion. `results-final.edn` records an argument-count refusal from `my.program/ns-unmap!`; the plain run cannot resolve that function. `corpus.clj:16–17` normalizes both different errors to `ERR`, so it marks this item equal and green while the function remains defined.

**Smallest fix.** Fold ordered bind/unmap/remove-ns effects by program identity into the final program change, retaining the full observation history separately. A new-then-removed identity produces no upsert; removing an existing identity produces its canonical retraction. Convert the deletion writer in the same cut. Replace h13 with a successful operation and assert both final SCI resolution and final stored facts.

### R5 — P1: namespace facts needed by the admitted source are omitted

**Evidence.** `batch-rows` builds definition digests using SCI's namespace bindings (`pipeline.clj:323–354`), but keeps only function/test entities. Settlement's `ns-tx` writes only the namespace name (`:500–504`). S11's namespace closure also discusses names, while S13 deletes `namespace-source` and `namespace-context-row` without assigning their surviving fact-production role.

This ordinary namespace-and-definition batch evaluated correctly (`nil`, Var, `"OK"`) but its candidate write was refused (77 ms):

```clojure
(ns review.alias (:require [clojure.string :as a]))
(defn f {:malli/schema [:=> [:cat :string] :string]} [x] (a/upper-case x))
(f "ok")
```

Refusal: “The stored definition digest of `[:seon.fn/sym review.alias/f]` is not the one its definition produces.” The expected digest was `04d96efbcebd2349b8c337d864a8c9f68bb74e30d992472830ceec5412481bf4`; the row supplied `a5f1c05fe20678b4c53727c2ea9502b76837b8efb875b54f75622d69adc93cef`. A subsequent pull of that refused candidate correctly returned an invalid-read error; it was not a successful pull-back proof.

Closure capture (§4) has a related unresolved lifecycle: refusing only **write-back** of `(let [k 1] (defn f ... k ...))` is too late to establish that an admitted row's isolated source can be reacquired on another branch. The spec acknowledges the missing captured environment but supplies no merge/acquisition instruction for it.

**Smallest fix.** Derive and settle the observed namespace binding facts that source reconstruction and definition digests actually consume, with the function rows in the same writer transaction. Validate the final namespace/function combination, then acquire it in a fresh context and compare behavior. For a non-reconstructible closure, preserve the live value and report non-admission before claiming a durable program row; do not replay its enclosing effects or invent captures.

### R6 — P0: S3–S14 is not a closed, loadable cut; −1,600 is unproven

**Evidence.** These current-HEAD callers survive the instructions but reference proposed deletions or depend on removed facts:

| Deleted or changed owner | Current caller/reference | Missing conversion |
|---|---|---|
| `one-event` | `src/seon/sci/eval.clj:1448` | `install-function!` still reads and evaluates source directly. |
| `one-event` | `src/seon/sci/eval.clj:1921,1946` | Test installation and deletion replay in `install-row!`. |
| `deleted-schema-key` | `src/seon/sci/eval.clj:1931,2126,2128` | Installation and evaluated-row adoption still call the deleted helper. |
| `:seon.sci.eval/bindings` | `src/seon/sci/eval.clj:1985` | `transfer-evaluated-roots!` still derives transferred roots from the old evaluation field; S7 removes its producer. |
| `:seon.sci.eval/restore`, execution-unavailable schema | `src/seon/turn.clj:5257–5276,5425` | S13 calls line 5266 a branch to delete; it is a contract reference inside surviving `restore-evaluations!`, which `resume-turn` calls. |
| Reader declaration facts | `src/seon/turn.clj:2083–2094` | Refresh/replay classification still rereads and calls `program/declaration-row`; deleting reader facts changes replay decisions. |
| `one-event`, `unmap-row`, `declared-row` | `test/seon/sci/eval_test.clj:1184,1198,1238,1244` | Private-Var references not enumerated for conversion. |
| `refuse-install` | `test/seon/test/accretion_test.clj:211` | S10 lists lines 25 and 195, but misses this separate caller (second gate call is now 196). |
| Deleted definitions in task refs | `resources/seon/tasks/seon.sci.eval.edn:140,156,161,166`; `seon.sci.reader.edn:12`; `seon.turn.edn:5,62,127` | These are live `:must-read` references beyond the listed S10 changes. |

At least the unresolved `one-event` calls prevent loading after the prescribed deletion. A broad `rg` instruction after deleting named forms is a way to discover missing work, not an executable conversion manifest with all callers listed.

The replacement also lacks a complete request/stage contract for the claimed JVM reader/evaluator and test thunk. §3 gives `evaluate-batch` the existing SCI request; S7 adds `:seon.eval/form`, while the thunk occurs only in prose in §5. The prototype's `join :ambiguous` does not satisfy §2's join-kind enum as written. S13 removes schemas referenced by surviving contracts. Compiling the existing checkout does not compile these proposed schemas and consumers.

Deleting `test/seon/sci/program_change_test.clj` is not justified by deletion of the admission diff: line 35 tests concurrent **loaded-program adoption**, through still-surviving `call-detecting-program-change`. Its “replace value with refusal” assertion needs revision for totality, while the concurrency behavior still needs coverage.

**Size check.** The 33 S13 entries do sum to 795 lines. That arithmetic is verified. There is no candidate production diff proving ~610 additions/~2,210 deletions or their non-overlap after all caller, contract, entrant, and provenance work. The number −1,600 is a planning estimate, not a verified result; fixing this manifest can change either side. Corpus timing cannot establish loadability or line count.

**Smallest fix.** Rewrite the cut around an explicit removed-symbol/schema → surviving-caller manifest at a pinned HEAD. Include acquisition, transfer, restoration, deletion, task refs, and test thunk contracts. Keep the loaded-program regression with updated semantics. Produce one prospective diff, lint/load/compile that archived candidate with resources and consumers, and calculate its actual source/test numstat. Do not retain compatibility paths solely to preserve the advertised deletion number.

### R7 — P1: §5 shares stages, but does not specify one function for every entrant

**Evidence.** `pipeline/run` owns the fold, but `entrants/jvm-batch` and `render-batch` assemble separate folds directly (`entrants.clj:29–51`). In the production instructions, `evaluate-batch` owns reading/evaluation, whereas `turn/evaluate-sources` owns analysis, derivation, gate and row attachment. Shared stage functions are useful, but these are different orchestration owners.

| Entrant | What the proposed instructions cover | Remaining current-HEAD path |
|---|---|---|
| Product/MCP SCI | S9 and S15 | `turn/source-events:1972`, `source-key:1978`, `read-only-evaluation?:2073`, and `evaluable-source?:2488` reread source. S18 removes a different reader region. |
| MCP JVM | S15 proposes host reader/evaluator values | No single pipeline request admits those values or defines shared recording/reply semantics; appendix C is another loop/fold. “No custody” explains no program writes, not the full observation-retention policy. |
| Tests | §5 asserts the body is a thunk in stage 1b | No S-step changes `src/seon/test.clj:252–280,1966` or `test/runner.clj:438,1712,1815–1836`. They still own body invocation and separate result commit. A common kernel arm is not a call through `evaluate`. |
| Render/context/preview | S17 routes two functions | The dependency's raw fork is not the read-only environment (R8). Define nested recording, rendering depth/fallback, and which already-rendered units the debug page displays. |
| Schema registration | Always-bound delta | This works for measured h36. Same-batch validation still needs namespace/schema/function consistency and producer-correct observations. |
| Install gate | S10 invokes gate work after the fold | Does not give auto-check/test bodies their explicit pipeline entrance and settlement ownership. |
| Merge/write-back/files | S17 changes `proven-file` | `src/seon/cluster/source.clj:1070` is a proof over candidate file bytes, not an evaluation request. Routing one analysis call does not itself make a file entrant. Acquisition's raw evaluator calls remain (R6). |

Not every EDN parser in the repository is a competing REPL: `render/value.clj:1401` reads a stored print artifact, for example. The spec should state that boundary explicitly instead of calling every reader “the one reader.” Actual executable-source rereads and replay classification above are the problem.

**Smallest fix.** Give one orchestrator a declared request carrying reader/evaluator/recording/print stages as values. Make adapters call it; a read-only or already-evaluated/file-proof request can supply identity/no-effect stages without invoking code again. Carry parsed forms and observed effect/read classifications to later consumers. List test body/recording, acquisition, refresh, gate, and file-proof conversions in the same manifest. Keep only transport encoding outside it.

### R8 — P1: a raw SCI fork plus a `:db` map entry does not prove read-only rendering

**Evidence.** `entrants.clj:36–51` forks the viewer and adds a database value to its batch. It does not replace inherited Seon custody/environment services. `sci/fork` changes only the env atom; compare production's explicit environment and custody setup at `src/seon/sci/eval.clj:3825–3853`. The prototype's empty bind diagnostics do not certify absence of effects.

A finite live probe defined `(def shared (atom 0))` in a private viewer context, forked it, and ran the entrant's `read-evaluate` stages on `(swap! shared inc)`. It returned `{:value 1, :viewer-state 1, :effects []}` in 3 ms. No actual agent's state was used. This falsifies “fork/discard means the viewer cannot be written,” without needing a database transaction. A raw fork also preserves any inherited connection carrier until an owner explicitly replaces it; the prototype did not prove that boundary.

**Smallest fix.** Specify and use the existing execution environment's database-value custody, removing inherited connection services at entry. State precisely how read-only rendering handles mutation of shared private objects and allowed effects, and test that actual boundary. Report observed mutations as diagnostics without pretending to undo arbitrary effects by discarding a namespace map. Do not introduce a second render evaluator or security sandbox.

### R9 — P2: the performance result is real for the prototype, but the proof labels overreach

**Evidence.** Fresh MCP JVM reproduction below confirms the useful small-batch numbers. Nevertheless `pipeline/gate:450` only filters missing contracts; it does not run auto-checks, tests, or revert a refused root. `settle:490` applies `db/with`, not durable settlement or receipts. Schema batches first apply a schema candidate and then the full transaction (`:506,516`), so “one transaction” means one intended durable settlement, not one total validation/application in this prototype. `reply:525` prints after the batch with ordinary `pr-str`, not production's per-entry declared renderer under the arm.

The corpus compares bounded printed representations and normalizes all `ERR ...` text to `ERR` (`corpus.clj:10–17,20–39`). It neither compares complete arbitrary values nor independently asserts the expected behavior of every case. h13 is a concrete false green (R4). h35 explicitly differs in observable realization timing; calling it “by design” is not proof that the proposed printing order preserves behavior. “Zero refused commits” means zero candidate-write refusals in those inputs, not successful durable commits or acquisition. R5 adds an ordinary input that does refuse.

**Smallest fix.** Label these as prototype microbenchmarks and candidate-write checks. Retain the speed claim within that scope. Commit a measurement driver that exercises the complete installed entrance, uses independent semantic assertions for error classes and effects, proves final rows/acquisition, counts reads/evaluations/analysis/writer transactions/prints, and records allocation and latency on parent and change. Root owns the full gate/integration round; this review did not run it.

## Fresh REPL measurements

All substantive review probes used MCP `eval_clj`, `mode: jvm`, explicit root `/Users/sean/src/seon`, cluster `default`, and an owned session `review-one-repl-20260927`; the first read-only namespace discovery used the default session. The prototype was evaluated into a new scratch namespace `review.onepath.prototype` by replacing the namespace token `pipeline`; no existing host Var was redefined and no other lane's namespace was edited. SCI contexts were fresh/disposable, and candidate writes used `seon.db/with`. The SCI location patch was not installed.

| Input/run | Prototype total ms | Read/eval | Analyze | Derive | Settle build | Final apply | Calling-thread allocation MB |
|---|---:|---:|---:|---:|---:|---:|---:|
| Single defn, first acquired value | 531 | 6 | 14 | 8 | 6 | 413 | not sampled |
| Single defn, warm 1 | 44 | 2 | 13 | 3 | 5 | 17 | 51.552 |
| Single defn, warm 2 | 37 | 2 | 6 | 2 | 5 | 17 | 42.769 |
| Single defn, warm 3 | 37 | 2 | 6 | 2 | 5 | 18 | 42.770 |
| h36, run 1 | 370 | 148 | 8 | 73 | 129 | 8 | 900.032 |
| h36, run 2 | 310 | 142 | 7 | 70 | 80 | 8 | 746.653 |

Every listed candidate application returned `{:applied true}`, with empty diagnostics. h36 wrote 22 candidate schema entries. Totals include acquisition and stage overhead, so the displayed columns need not sum to the total. Thread allocation is allocation, not retained heap, and omits allocations by other threads. The entire five-run warm MCP evaluation took 801 ms. The initial one-defn MCP evaluation took 534 ms. The old-path 512–948 ms and 50.8–54.7 s comparisons were **not** rerun; the latter is a historical branch measurement, not a current-head comparison established here.

Exact warm measurement form (after loading the scratch prototype):

```clojure
(let [db @(seon.cluster.boot/connection "default")
      src "(defn review-f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"
      h36 (slurp "/Users/sean/src/seon/tmp/valid-clojure-corpus/items/h36-register-22.clj")
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.threadId (Thread/currentThread))
      measure (fn [text]
                (let [a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
                      r (review.onepath.prototype/run {:db db :text text})
                      b (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)]
                  (assoc (review.onepath.prototype/summary r)
                         :allocated-mb (/ (- b a) 1e6))))]
  {:single (mapv (fn [_] (measure src)) (range 3))
   :h36 (mapv (fn [_] (measure h36)) (range 2))})
```

The initial result was elided by MCP. Its full summary was inspected through `seon.cluster/mcp-value`, digest `652da94985333b1e0ac0028c91ecc482989337ca1726b91946909f1c3110388f`, projecting timings, allocation, commit and diagnostics. Elision was not treated as success. The first exploratory namespace-discovery call unnecessarily requested the SCI resource text and was elided (about 10,000 estimated tokens of source); that output established no correctness claim.

## Q1–Q5: recommendations and prices

These are review estimates, not measurements or authorized implementation assignments. No replacement line budget is credible before R6's closed diff.

| Question | Review ruling | Cost/guarantee correction |
|---|---|---|
| Q1, generated definitions | **A is the direction required by the owner's exactness intent; B is not acceptable.** C is an explicit reduced persistence guarantee, not completion of that intent. | Kondo's string hook really re-executes a macro in its own SCI (`hooks.clj:225–280`, `core.clj:224`), so B can assert wrong facts even when the name matches. A needs the actual expansion keyed by invocation/source identity, ordered nested expansions, metadata/span preservation, and an explicit bridge into kondo's hook/node API. The claimed +12/+50 lines and 2–3 h are unproven. Price a bounded 2–3 h feasibility probe first; integrated observation/join proof plausibly takes 1–2 days, pending that probe. |
| Q2, model prose | **A is reasonable if the owner accepts REPL-input semantics; the stated prose guarantee needs revision.** | Plain prose often reads as symbols: `Here is the answer` is readable input that fails during evaluation, not unreadable text. It will not necessarily stop at `unread-from`. A should promise “first reader error stops,” with text intended as commentary written as comments. No heuristic prose detection. The 50 min deletion estimate excludes still-live reader consumers and reply/schema conversions (R6/R7); budget the manifest/contract conversion before pricing the cut. B can preserve segmentation only as code ranges, with no reader/evaluator of its own. |
| Q3, facts within a batch | **A is the smallest viable basis semantics; retain it and document it.** | Private execution can use the live Var while database `doc` reads the basis. “0 lines” omits caller/expectation changes, but avoids a second speculative program. B violates the intended one-analysis/one-settlement composition as priced, and its ~30 ms is not supported by the spec's own 315 ms historical speculative cost. Do not recommend B without a separately measured changed-input algorithm. |
| Q4, lone registered key | **A is correct for value schemas.** | Storage membership must be derived from the final entity-schema projection, not inferred from the existence of a key. B invents a required marker and forces a population sweep without demonstrating a correctness failure in valid value schemas. +8 lines may cover the reply field, but not the writer refusal, contracts, successful-storage and value-only proof. h16 supports the value-only behavior, not that complete +8-line implementation. |
| Q5, non-function definitions | **A is the smallest initial persistence scope, with explicit limitations.** | “Private” is a storage choice, not proof that mutations of inherited MultiFns/protocols or atom roots are confined. Function factories generated by records already cross this classification (R2). Account for affected callable dependencies, refusal/merge/acquisition behavior and honest reply notes. Zero incremental lines is unproven. B entails a new persistent effect/dependency model; “days” has no acceptance scope and must not silently enter this cut. |

Q1 is not optional if the specification continues to promise exact support for definitions emitted by arbitrary admitted macros. Q2–Q5 can be policy choices only after their guarantees are stated precisely. None warrants a second evaluator or a per-case fallback.

## Evidence reproduction and focused tests for root

The scratch observer probes require only SCI plus the prototype's private `observe!`. For the missed writes, run each `src` separately:

```clojure
(let [observe (deref (ns-resolve 'review.onepath.prototype 'observe!))
      probe (fn [src]
              (let [ctx (sci.core/init {})
                    _ (sci.core/eval-string* ctx "(def f 1)")
                    stop (observe ctx (constantly 0) (constantly nil))
                    v (sci.core/eval-string* ctx src)]
                {:value v :effects (mapv #(select-keys % [:effect :sym :origin]) (stop))}))]
  (mapv probe ["(def f 2) f"
               "(intern 'user 'f 2) f"
               "(alter-var-root #'f inc) f"]))
;; => [{:value 2 :effects []} {:value 2 :effects []} {:value 2 :effects []}]
```

For the totality example in R3, the exact outer invocation was:

```clojure
(let [db @(seon.cluster.boot/connection "default")
      r (review.onepath.prototype/run
         {:db db :ctx (sci.core/init {})
          :text "42 (def ns-name (fn [_] (throw (ex-info \"ns-after failed\" {})))) 43"})]
  {:entries (mapv (fn [e]
                   (cond-> (select-keys e [:ordinal :value :source])
                     (:error e) (assoc :error (ex-message (:error e)))))
                 (:entries r))
   :reply (:reply r)})
```

For later printing failure, this proves retained values but absent reply, without printing the bad lazy value again:

```clojure
(let [stage (deref (ns-resolve 'review.onepath.prototype 'stage))
      v (lazy-seq (throw (ex-info "print failed" {})))
      r (stage {:entries [{:value v} {:value 42}]}
               :reply review.onepath.prototype/reply)]
  {:values-retained (and (identical? v (get-in r [:entries 0 :value]))
                         (= 42 (get-in r [:entries 1 :value])))
   :reply-present? (contains? r :reply)
   :diagnostics (:diagnostics r)})
;; => values-retained true, reply-present? false, one reply-stage diagnostic
```

**No behavior tests are needed for this documentation-only change.** Root should run these exact proposed regressions after the implementation; they are proposed names from the spec, not assertions that those tests are installed:

- `seon.sci.eval-test/every-definition-is-observed-at-the-def-and-armed-before-the-next-expression`: add pre-existing same-generation Vars, equal-root metadata changes, `intern`, root alteration, sibling forks and nested evaluation.
- `seon.fn-test/binds-join-kondo-definitions-exactly`: add runtime rebound provenance, generated-member ownership, repeated macro invocation, Unicode/multiline spans and ambiguity; assert acquired behavior, not just labels.
- `seon.cluster.evaluate-source-test/one-batch-settles-in-one-transaction-with-every-row`: add namespace aliases/refers and schema/definition digest consistency; count actual durable transactions.
- `seon.cluster.evaluate-source-test/a-definition-and-its-removal-in-one-batch-settle-once`: add new-then-remove, remove-then-redefine and remove-ns; assert resolution plus pulled facts.
- `seon.cluster.evaluate-source-test/a-refused-refusal-settlement-returns-the-completed-prefix`: expand to bookkeeping, gate, classification, rendering, primary recording and fault-recording failures; retain nil and false values too.
- `seon.sci.eval-test/a-render-batch-cannot-define-or-write`: exercise actual database custody, inherited connection carriers, private mutable roots and nested rendering.
- `seon.dev.mcp-bridge-test/jvm-and-sci-events-carry-the-shown-text-once`: count the one print and preserve a good entry beside a failing renderer.
- `sci.core-test/generated-definitions-carry-their-macro-call-location`: supplement S1 with expansion-supplied metadata and repeated/nested expansions; this is dependency proof owned by root/fork owner.

Keep and revise the installed `seon.sci.program-change-test/an-evaluation-refuses-its-value-when-the-loaded-program-changes` for value-preserving adoption diagnostics. Include the existing integration tests named by S9/S17 after root verifies their current installed identities. This lane ran no harness, fork suite, bulk tier, provider request, publication or adoption.

## Landing evidence and foreign boundary

The static validation uses an owned `git archive` snapshot, not the shared working tree or a worktree. Initial archive: `tmp/review-one-repl-20260927/archive.Znxlaa`, pinned to `c932dd4b4`; no foreign working-tree diff was included. The first attempted archive extraction from a nested cwd was empty; its 7 ms lint was discarded, not counted. Corrected extraction contained 512 files under `src script test`.

`clj-kondo --cache false --lint src script test` on the corrected archive reported **0 errors, 948 warnings**, 6,396 ms (exit 2 for warnings). Warnings are not a zero-finding pass. No shared analyzer cache was written.

The installed static `seon.contracts-compile-test/check` was invoked with the archive root and all 485 `.clj/.cljc` paths under `src script test`, using the archive's packaged projection. It completed in **1,575 ms**, finding ten refusals, all in pre-existing script contracts: `script/seon/dev/docstring.clj:285,318,334,360` and `script/seon/dev/markdown.clj:1020,1050,1090,1124,1170,1215`. Their contracts use schema Vars/symbols that the indexer's canonical EDN contract path refuses. There were no source/test contract findings in that result. The compiler used is Malli `m/function-schema` (`reference-code/malli/src/malli/core.cljc:3130`), reached through `contracts_compile_test.clj:254–286`. This was a static compile check, not a test request or proof of boot/adoption. The whole-check elapsed time exceeds one second in proportion to 485 files; this review does not attribute it to one armed production call.

Foreign boundaries are the installed faulted/older JVM, those ten existing script contract refusals, archive lint warnings, and concurrent source/error/instrumentation/dependency changes. They are not reasons to change or pause another lane's work. No source, test, resources, reference-code, issue index, or another lane's session/files were edited. New defect evidence is retained here for root to map to tasks; the existing parse/reanalysis and cost issues in `resources/seon/tasks/seon.turn.edn:61,79,126` already overlap parts of R6/R7/R9. This review does not duplicate or edit that schedule.

A second validation used archive `final.QyLNIX` at `4c3960692` plus this document. Lint again reported **0 errors, 948 warnings**, 7,767 ms. The original named MCP session was lost to an externally initiated restart; this lane did not request it. Reconnection found PID 20628, start `2026-09-27T19:29:29Z`, ready in 29,163 ms, still faulted with nine open signatures. The scratch prototype namespace was absent, so its private objects had been released with the old JVM. The new owned session was `review-one-repl-validation-20260927`.

On that JVM, the same second-archive compile check read 485 files and reproduced exactly the same ten script-only findings in **3,442 ms** (MCP 3,488 ms). After HEAD advanced again, the next archive `commit.3MctGT` contained `ed1179a23` plus this document: lint reported **0 errors, 948 warnings** in 10,343 ms; the 485-file contract compile check reproduced the same ten script-only findings in **4,730 ms** (MCP 4,743 ms). The final archive, `final-head`, contained `58d7f5f0d` plus this document after the foreign vector-storage commit: lint again reported **0 errors, 948 warnings** in 9,717 ms; all 486 source/script/test files compiled through the checker with the same ten script-only findings in **2,842 ms** (MCP 2,852 ms). These repeated checks were required by intervening source/test changes, not evidence of a green compile report. They are the explicitly requested static checks, not test runs.

Raw lint and compile findings are retained under `tmp/review-one-repl-20260927/`; the owned archive directories are removed after validation. The owned validation namespace was removed, and both concurrency-probe threads had positively exited. No live operator root or another lane's evidence is part of cleanup. Net production/test change: **0/0 lines**. Only this review document is staged and committed.

## Verdict

**REVISE**, with R1–R9 resolved in the owning spec: replace the watch with producer observation; carry exact provenance; preserve the completed prefix and reply through every later failure; reduce deletions correctly; persist namespace facts needed for reconstruction; close the caller/schema cut and measure its real size; route every entrant through one orchestrator; establish actual render read-only semantics; and narrow/re-run the proof claims at the installed production boundary. Keep the measured batching approach and Q3/Q4's simpler policies. Do not begin S3–S14 from the present instructions.
