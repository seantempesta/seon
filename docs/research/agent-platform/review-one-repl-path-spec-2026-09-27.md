---
type: review
status: revise
created: 2026-09-27
revised: 2026-09-27
spec: docs/research/agent-platform/one-repl-path-spec-2026-09-27.md
spec-commit: 79f88387e
scope: review only, revision 4 and landed steps
---

# One REPL path: revision 4 review

The implementation composes a stage-valued fold, SCI producer observations, per-form speculative validation, and one recording transaction. The named rev-4 proofs pass after correcting the review's namespace-isolation harness, but additional REPL proofs find execution blockers in totality, provenance, exact spans, deferred gating, world propagation, and call accounting. **REVISE: C1–C6 below.**

This replaces the revision-3 review at `f3c1db347`. AGENTS.md and the rev-4 spec were read in full, with the REPL, data-oriented Clojure, testing and database-owner guidance. Q1 A, forgiving input, threaded db-after, storable registered keys, def/defonce entities, and the owner's acceptance of +429 prototype lines are settled. This review does not reopen those decisions.

## REPL evidence

The rev-4 baseline is an archive of `79f88387e`, not the concurrently changing prototype directory. Its namespaces and corpus runner were copied under `one-path-review.v4.*`; contracts retain their original keyword identities. MCP calls used root `/Users/sean/src/seon`, cluster `default`, mode `jvm`, sessions `one-path-review-v4` and `one-path-review-v4b`. Database probes acquired disposable agent executions and released them in `finally`. No production test request, `bin/test` gate, default stop/reset, worktree, or foreign session operation was started.

```clojure
(one-path-review.v4.one-path-proof/prove!)
;; initial isolated run: 90/92
(one-path-review.v4.one-path-proof/run-proofs!
  #{:world-members-agree :b4-writer-refusal-reply})
;; both PASS after repairing the isolation harness: 92/92 across run + rerun
```

The first two reds were review-harness errors, not implementation defects: the namespace rewrite changed a schema namespace string and the diagnostic's `:seon.batch/settle` keyword. The corrected copy also resolves the schema's sink predicate in the review namespace. No implementation logic was changed to obtain these passes. Twelve rows read saved SCI-fork evidence; they are not twelve fresh fork executions. The 94-item corpus passed, with its ten declared differences. Summed proof time was 179,014 ms; shared-JVM heap use was 2,840 MiB before and 1,356 MiB after (not retained batch size).

| Requested measurement | Reproduced at MCP JVM | Limit of the evidence |
|---|---|---|
| One contracted defn, complete recorded batch | 265, 234, 264, 269, 217 ms | Last run: derive 9.71, apply/gate 82.55, settle 118.98 ms. |
| h36, 22 registrations | 2,789 ms | Apply 2,034.89 ms. Threading is real; this remains a multi-second batch. |
| Forty chained definitions, 79 forms | 11,329 ms | Apply 9,305.81 ms. This was the expressly requested proof-suite probe, not approval of an ordinary operation taking this long. |
| Fresh hub feedback | 10 tests: 28 ms; 22: 20 ms; 150: 37 ms | Default's own 22-test head: 10 ms, 11 stale/11 never-run. Under 50 ms reproduced. |
| Concurrent test agents | PASS, 618 ms | Distinct disposable branches; production assertion/member recording still awaits conversion. |
| MCP/agent reply parity | PASS, nine behaviors | Prototype wrappers around the common reply; not an installed MCP/Flow integration test. |
| JVM feedback | PASS, 238 ms | Arithmetic receives the feedback key. It does not establish JVM world custody; C5 does the database probe. |
| Forgiving input | 52 replies, 35 ms; repaired and malformed tagged-reader cases PASS | B3's duplicate semantic-read counterexamples are closed. |
| S3 contracts admission | 0 findings, 532.46 ms | Actual `seon.contracts-compile-test/check`, archived eight source files, packaged resources, empty source baselines. |

Default was initially unavailable, recovered as PID 23961, then was externally restarted as PID 30305. The first JVM did not expose `sci.lang/*write-observer*`; the second did. The review neither requested nor performed either restart. On the second JVM, the actual observer was loaded only into a review namespace and substituted as the request's observer stage. Concurrent `alter-var-root` and `intern` calls on two forks produced exactly 20 and 30 observations, final values 20/30, and left the base at 0. This is positive evidence for scoped observation, but not for watch provenance (C2).

Raw evidence and finite counterexample forms are in `tmp/one-path-review/v4/`: `runtime/proof-results.edn`, `corrected-isolation-results.edn`, `proof.clj`, `counter-results.edn`, `s3-runtime/proof.clj`, `s3-counter-results.edn`, `producer2-results.edn`, `committed-sink.edn`, and `jvm-world.edn`. The essential failing forms are reproduced below so the findings survive independently of disposable files. `producer-results.edn` retains an earlier probe with a reused turn ID; its recording refusal is a harness mistake, corrected with a fresh ID in `producer2-results.edn`.

## Execution blockers

Source locations in this section are the landed S3 archive `af8ee847e`, except C2 (SCI `ac3b0a30`) and C6 (S3b `7749eb1c7`). All six findings are independently reproduced at the REPL; none is inferred merely from a missing test.

### C1 — P0: an observation-stage failure still loses an evaluated value

`seon.batch/evaluate-step`, `src/seon/batch.clj:193`, retains the value in a local volatile but publishes it only after processing observations. Its final `assoc` into each observation can throw; the outer fold then returns the batch from before the evaluation.

```clojure
;; tiny-request reads one nil form and its evaluator returns 42.
(b/evaluate-batch
  (assoc (tiny-request 42) :seon.batch/observe
    (fn [_ _ write! run]
      (run)
      (write! (sorted-map 1 :bad)))))
;; values []
;; diagnostic: "fold: class java.lang.Long cannot be cast to class clojure.lang.Keyword ..."
```

This extends B1's imported non-map case: the malformed observation is a map, so `map?` lets it reach the throwing normalization. The failure belongs beside `42`; losing it is forbidden even when a stage supplies malformed data. The same counterexample fails on rev 4 and S3.

**Smallest fix:** publish the evaluated entry before any observation normalization, classification, or timing/finalization that can fail. Guard that remaining work using the batch that already contains the value. Tighten the observation producer's contract as well; validation itself must not discard the published entry.

### C2 — P0: a watch's rebinding inherits a definition's origin and persists false source

SCI `src/sci/impl/evaluator.cljc:55` binds `*def-origin*` around `bindRoot`; `src/sci/lang.cljc:111` invokes both the observer and watches inside that binding. A later write made by a watch therefore appears to be the original definition.

```clojure
(def watched 0)
(add-watch #'watched :once
  (fn [_ v _ _]
    (remove-watch v :once)
    (alter-var-root v inc)))
(def watched 2)
watched
;; live result: 3
;; BOTH final writes have origin {:line 3 :column 1 ...}
;; stored :seon.fn/source: "(def watched 2)"
;; diagnostics []
```

`one-path-review.s3.counter/watch-rebinding-origin` uses the real SCI producer observer on a disposable world. Reacquiring the stored definition would produce 2, not the observed 3. The imported throwing-watch proof tests delivery order, not this re-entrant provenance. B2/B7 cannot be considered closed for this class.

**Smallest fix:** capture the direct write's origin/kind in its event, then clear those dynamic producer tags while invoking observer callbacks and watches. A nested genuine def installs its own tag; an `alter-var-root` rebinding has no definition origin. Add this counterexample to the fork proof and verify that the pipeline keeps the rebinding private rather than storing stale source.

### C3 — P0: exact-span observation and Q1 expansion delivery are not connected

Three independently exercised seams fail:

1. `src/seon/sci/batch.clj:19` takes the old reader's options without `:end-location true`. The actual producer emits `{:end-line nil :end-column nil}`. Literal definitions reach the start-only fallback in `src/seon/turn/batch.clj:119`; generated definitions use containment. These are not exact joins, and the nil ends violate the declared origin schema.
2. `src/seon/sci/observe.clj:24` destructures `:end-line`/`:end-column` from form metadata. SCI actually returns `:end-row`/`:end-col`. Even with end locations explicitly enabled, no `:expansion` event is emitted.
3. `join-write` checks full span only when `:expanded-by` is absent. `expansion-hooks` at `src/seon/turn/batch.clj:203`, and the S8a patch, key only by start coordinates and treat batch coordinates as form-relative coordinates.

```clojure
(defmacro review-single [] (list 'def 'review-made 7))
(review-single)
review-made
;; value 7; zero :expansion events
;; review-made private: :no-definition-at-origin
;; enabling :end-location fixes origin ends, but still emits zero expansions

;; Same analyzed defn/call [1 1 1 30], supplied origin [1 1 1 12]:
(join-write analysis coords
  {:seon.batch/sym 'user/f
   :seon.batch/origin {:line 1 :column 1 :end-line 1 :end-column 12
                      :expanded-by 'defn}})
;; :matched -- must refuse this non-identical span

;; Hook for observed [2 1 2 99] substitutes into actual (single) at [2 1 2 9].
;; A batch-line-12 event finds no call in the sliced per-form analysis.
```

These are `producer-spans`, `generated-full-span`, and `hook-spans` in the review proofs. A standalone hook invoked with already-correct row numbers does not prove the observer → sliced source → synthetic analyzer composition.

**Smallest fix:** enable end locations in the single semantic reader; translate SCI metadata keys at the observer; carry/translate all four coordinates from batch to form to synthetic text. Match the owning macro call by its complete span before assigning generated members, and key hooks by that same complete span. Remove the production start-only fallback. Before S8a converts callers, prove stored generated entities and reacquisition for a macro call on a later line and an indented call, including multiple generated definitions.

### C4 — P0: deferred gating commits a bad definition and hides its refusal

`src/seon/turn/gate.clj:108` chooses only the first failing entity in a form, and removes only that entity. `src/seon/turn/batch.clj:619` records the locally gated batch but returns only the commit on the successful first attempt, losing the updated entries/program/feedback from the reply.

```clojure
(declare late)
(do
  (defn deferred-a {:malli/schema [:=> [:cat] :int]} [] (late))
  (defn deferred-b {:malli/schema [:=> [:cat] :int]} [] (late)))
(defn late {:malli/schema [:=> [:cat] :string]} [] "bad")
42
;; applied true; value 42 retained
;; stored: deferred-a false, deferred-b true
;; facts-errors []; reply diagnostics []
;; feedback changed: [user/deferred-a user/deferred-b user/late]
```

Both functions fail their own output contract. One is committed, and the reply names the rejected definition as changed while omitting its error. This falsifies OQ1's “only validated facts” and B4's final reply claim with ordinary source, not an injected writer.

**Smallest fix:** gate the entire deferred form's change, reject that form consistently, and re-fold/revalidate surviving changes and their dependencies. Return the final batch state to the caller on every settlement path, including first-attempt success, and conclude from that state. The same refusal must be visible in the stored evaluation, immediate MCP reply, and next agent reply.

### C5 — P1: the world is incomplete at real execution boundaries

Four finite proofs identify the missing construction/binding work:

```clojure
(world/agent-world execution {})
(world/jvm-world "default" disposable-connection {})
;; both contain :seon.agent/id nil; the member is optional, but nil is invalid

(world/read-only-world agent-world database)
;; retains the IDENTICAL :seon.batch/sink, contrary to §7's "none"

;; A throwing evaluation followed by a show stage that reads b/*world*:
;; world-at-print nil, even though the request has the agent's world

;; Through jvm-request with a declared disposable connection/database:
(seon.db/pull [:seon.agent/id] [:seon.agent/id "root"])
;; seon.db/missing-connection-error, instead of the branch's root record
```

Locations: `src/seon/turn/world.clj:25,61,76`; `src/seon/batch.clj:256` fallback printing; `src/seon/turn/batch.clj:899` JVM request. The successful thread-hop proof explicitly calls `with-world`; these paths do not all do so. The JVM observation is of the supplied S3 request on the installed pre-S3a runtime; the full supply change must repeat it armed, rather than relying on the arithmetic-only JVM proof.

**Smallest fix:** conditionally associate the optional agent id, remove the sink from read-only worlds, and establish the declared world around every evaluation/print path, including JVM evaluation and failure printing. Use the existing `with-world` boundary to carry custody/read basis where required. Test omitted and explicitly supplied members after the S3a wrapper is armed; explicit caller values must still win.

### C6 — P1: the committed sink patch drops calls made in a nested world

S3b `7749eb1c7`, `src/seon/profile.clj:138`, retains one Boolean re-entry guard per cell/thread. When a counted function enters another world and invokes the same function, that second world's call bypasses the sink entirely.

```clojure
(let [f (profile/with-cell (profile/cell {:seon.profile/sym 'review/f})
          (fn f [inner?]
            (profile/timed
              (if inner? :inner
                (binding [batch/*world* world-b] (f true))))))]
  (binding [batch/*world* world-a] (f false)))
;; world-a calls {review/f 1}
;; world-b calls {} -- expected {review/f 1}
```

Reproduced both from the spec patch and the committed S3b source, loaded in scratch namespaces. The imported 1,000/1,500 proof invokes the counter macro directly and cannot exercise `timed`'s guard. C5's retained read-only sink also contaminates the caller's sink once wrappers use it.

**Smallest fix:** make call-accounting re-entry distinguish the current world/sink, while preserving the intended same-world wrapper deduplication and JVM-wide inclusive timing. Restore the prior world entry on exit. Prove the real composed wrapper, including nested worlds and exceptional returns, not just `count-into-world!` in isolation.

## What is closed and what still needs installation proof

B1's nil/sentinel/non-map examples pass; C1 extends the remaining totality gap. B2's authored mismatch passes, but C2/C3 block provenance and full exactness. B3 is closed by structural repair before semantic reading. B4's writer refusal and moved-head example pass; C4 exposes the deferred-settlement path. B5's source contracts gate is now genuinely green for S3; C5 is an output-world defect outside the named case. B6's shared JVM floor proofs pass. B7's joined concurrent callbacks and lazy-print definition pass; callback provenance and nested-world accounting still fail.

The rev-4 supply patch is explicitly a plan, not installed proof. Its existing missing-key and arity machinery has to be checked through the armed wrapper with every world member, optional map entries, explicit foreign database/connection arguments, validation order, no matching-arity prefixing, and a raw executor hop. The supplied proof map contains no complete armed supply matrix. This is a required S3a completion check, not a new request to change the owner's ruling.

The change-relative gate's named pure tests pass. This does not yet establish the S5/S12 production test-agent lifecycle and requester-side `:seon.test.member/*` recording. `test-outcomes` forks one context, reacquires test sources, and calls `eval/run-tests`; the production harness/recording conversion remains to be reviewed when committed. An absent/refused test result must be unknown/refused, never accepted as green (`test-outcomes` currently maps missing results to `:absent`). No production test runs were started to fill that gap.

## Per-step review

| Step / commit observed | Review result and remaining execution boundary |
|---|---|
| S1 `3afba653f`, SCI `ac3b0a30` | Producer Vars now verified in the restarted JVM; two concurrent forks preserve the base and observe every write. C2 is a concrete fork blocker. Saved 397-test/five-baseline-failure evidence was read, not rerun as a separate SCI suite. |
| S2 Q4 | No separate landing observed at this cutoff. Named lone-key proof passes through prototype interim logic; removing that logic still requires the schema/datahike producer change in the same cut. |
| S3 `af8ee847e` | Eight namespaces and schema land additively. All eight load in isolated namespaces (115 ms). Archive lint: **0 errors, 945 warnings, 16 info**, 502 files, 5,012 ms. Real contracts gate: **0 findings**, empty baselines. C1–C5 survive this commit. No old evaluator caller was converted or retired in this step. Publicizing helper seams/moving `captured-locals` to the analyzer, listed in S3, did not land here; private Var calls remain. |
| S3a supply | Full supply implementation not yet a reviewed commit. `cdb0418cd` makes `read-evidence-current?`'s database slot named and adjusts its caller-preservation regression; this is only one part of S3a. Required armed matrix above remains outstanding. |
| S3b `7749eb1c7` | Committed macro composition reproduced C6. The change adds the sink increment, but does not convert the old exact-count tests. Root should run `seon.profile-test` with the new nested-world regression after arming. |
| S3c host bindings | No landing observed; no installation claim. |
| S4 product turns | No converted diff observed; C4 must be fixed before this becomes the product reply. |
| S5 install gate | No production caller retirement observed; deferred gate fails C4. Test-agent lifecycle/member recording still needs its actual converted diff. |
| S6 refresh | No converted diff observed. Supplied observed-write classifier proof passes. |
| S7 MCP | `ada9deecd` adds the `my.agent` branch owners and schemas. The MCP conversion is still a patch, so it is not proof that the tool uses that owner. `run-form`/`run-events`, JVM evaluation and projection conversion remain outstanding. C5 names a JVM world failure. |
| S7a `6996cd574`, `a3c1bcf81`, `9ac21b93f` | Reviewed diffs: history selector now retains evidence refs, render reach keys include the as-of identity, vector-pull uses the central cache, and `latest-evaluations` pulls full evidence only for kept evaluations. The latter retires the old body in place and keeps its caller signature. Earlier prototype equality passes; newly installed behavior still needs root’s refresh tests. The last commit bounds captured index patterns; its matching schema bound is explicitly pending. Its diff was reviewed after the final archive snapshot, not independently benchmarked here. |
| S8 input/render | Forgiving stage is present; product/render/web caller retirement not observed. |
| S8a analyzer hooks | Patch read; C3 prevents its instructions working as written. End-to-end generated entity/reacquisition proof is required. |
| S9 acquisition/removal | No converted production diff observed. The new acquisition request loads, but old evaluator consumers remain. |
| S10/S11 evaluator/reader deletion | No retirement observed; refresh the complete manifest immediately before these cuts. |
| S12 test agents | Prototype concurrency passes; actual production member evidence and recorded assertion outcomes remain unproven. |
| S13 proof tests | No landed `seon.batch-proof-test` observed. Root owns the central test list. |
| S14 extensions | Explicitly deferred, not an implemented entrant. |
| §12 `036a92fce` | Diff uses existing `nearest-base` and the bounded value tier, avoiding speculative insertion into the committed projection cache. Regression added: `seon.db-test/speculative-declarations-never-enter-the-committed-projection-cache`. The committed implementation's before/after numbers were not independently reproduced in this review's still-older JVM. |

The 126-row manifest was checked against fresh S3 clj-kondo Var-use analysis. It includes namespace requires and internal calls scheduled for deletion, not 126 distinct surviving calls. There are 120 live filtered usages; normalizing absent/nil `:from-var` and line shifts leaves no newly omitted surviving Clojure caller. Current shifted examples are `test/seon/ai_test.clj:681,778` and `test/seon/render_simplification_test.clj:760,783`. The manifest still does not inventory resource symbols: examples include `resources/seon/tasks/seon.turn.edn:19,62` and `resources/seon/tasks/seon.sci.eval.edn:44,55,198`. Include them in the appropriate owner-coordinated deletion step before retiring the named Vars. Their current existence is not evidence that a future deletion is loadable.

The net −900 production-line figure is a remaining-cut estimate, not an achieved measurement: S3 is additive and S4 onward have not retired the old paths. The accepted +429 prototype lines are not a blocker. No claim is made that all fourteen intermediate commits or all entrants have been proved merely because the additive namespace load succeeds.

## Landing and central verification

Only this review document is committed; production/test/resource/reference-code net change is **0**. Review-owned scratch proofs and raw evidence are retained under `tmp/one-path-review/v4` and excluded from the commit.

Final verification used `git archive` HEAD (`a3c1bcf81`) plus only this document’s diff: `clj-kondo --lint src script test --cache false` returned **0 errors, 945 warnings, 16 info** over 502 files in 7,408 ms. The contracts compile check read 494 JVM source files in 1,866.50 ms: **0 src/test findings**, and the same **10 pre-existing script-only findings** as the rev-3 review, at `script/seon/dev/docstring.clj:285,318,334,360` and `script/seon/dev/markdown.clj:1020,1050,1090,1124,1170,1215`. Thus the full script contracts check is not green; the review introduces none of those failures. No foreign file was changed to make a check pass. Later documentation-only evidence updates do not change the checked source, scripts, tests, or schema inputs. All **23 review-owned namespaces were unloaded**, zero remained, and the foreign prototype namespaces were verified preserved. The documentation change itself requires no behavioral test run. Root's focused list for the reviewed implementation is `seon.profile-test`, `seon.call-preparation-test`, `seon.sci.eval-test`, `seon.sci.kernel-test`, `seon.repl-test`, `seon.test.accretion-test`, `seon.dev.mcp-bridge-test`, `seon.turn-test`, `seon.rereads-test`, and the exact new database regression above. Add the counterexample regressions to S13's `seon.batch-proof-test`: `malformed-map-observation`, `watch-rebinding-origin`, `producer-spans`, `generated-full-span`, `hook-spans`, `deferred-gates`, `constructor-absence`, `error-print-world`, `read-only-sink`, and `nested-world-sink`. These names identify required proofs, not already installed tests.

Foreign boundaries: default's external restart and existing health faults; the spec/implementation lane's loaded namespaces; in-flight source, resource, dependency and test changes; full supply/adoption not yet installed. No foreign session or file was resumed, messaged, edited, restored, staged, or committed.

## Revision-4 verdict

**REVISE — C1 totality; C2 watch provenance; C3 exact spans and expansion delivery; C4 deferred gate/settlement; C5 world construction and bindings; C6 nested-world call accounting.** Each has a failing REPL form and a bounded producer fix above. Passing the named map, archive lint, and contracts compilation does not close these failures.

## Rev-S4a review — §10.0 at ec06edfb7

The proposed composition is right: the product turn supplies settlement to the existing fold, and that settlement owns the one recording transaction and its turn side effects. This review checks the proposed seam against its actual producers and consumers; it does not claim S4a has landed. Earlier revision verdicts above are historical, not a fresh audit of those revisions.

**REVISE — A1–A6 below block executing §10.0 as written.** Source locations below were checked at `6f7cee1f2`; the reviewed design is pinned independently to `ec06edfb7`. Owner rulings on data renders, missing-only world supply, and thin MCP stand. None needs reopening.

### Evidence and scope

MCP JVM probes used explicit root `/Users/sean/src/seon`, cluster `default`, session `one-path-review-s4a`. Default was reachable with ten existing open faults; it changed externally from PID 96513 to 98430 during acquisition. I neither stopped nor reset it. With r12 running, probes used held database values, two local evidence worlds, bounded/joined futures, and one scratch namespace; no branch writes, test requests, provider calls, or foreign namespace reloads. The host probes exercise the real installed fold, settlement projector, world boundary, read collector and profiling macros. They are not a claim of product-turn integration or SCI-future installation proof.

Reproduction source: `tmp/one-path-review/s4a/one_path_review/s4a.clj`, namespace `one-path-review.s4a`. `results.edn`, `input-refusal.edn`, and `settled-replacement.edn` retain full results. `(one-path-review.s4a/run)` took **3.874 ms**, **14 ms** in the MCP envelope including scratch loading; input/refusal probe **3 ms**, settlement replacement **2 ms**. An initial fixture used an invalid short profile digest; the armed contract correctly refused it, and the corrected probe used 64-character digests. An initial replacement fixture omitted required `tx-count`; the final proof below includes it and validates. Neither invalid fixture is counted as a product failure.

### A1 — High: the read-stage input does not contain the proposed form/event

**Failing form, run at the REPL:**

```clojure
(seon.turn/planned-sources "(+ 1 2)" 'user 1000)
;; [{:seon.ns/name user, :seon.cluster.eval/source "(+ 1 2)"}]
```

There is no parsed form or reader event to hand to the fold. `resume-turn` also reconstructs its source inputs from stored source strings (`turn.clj:5403–5429`); the optional event there is the special no-forms error, not an ordinary retained read. The source planner has already read the text, and `disposition-rule-error` reads it yet again at `turn.clj:5087`. Supplying these maps directly as `:seon.batch/form` evaluates data instead of `(+ 1 2)`; reading their text again preserves the duplicate semantic reader. The fold's read stage additionally needs version text, offsets and cursor progress, not just the source ordinal.

**Smallest fix:** specify and implement the actual handoff in S4a. Use the existing live forgiving reader as the semantic reader, preserve its form/span/version into evaluation and disposition, and move the minimum source-planning handoff from S8 into this cut. If retaining an earlier parsed event, name its producer and retention path and prove aliases/namespace changes and delimiter repair work without re-reading. Pre-opened receipt identities may remain; they are not parsed-form storage. Change the disposition check to consume the held form.

### A2 — High: the settlement adapter and disposition stage cannot preserve the required evaluation yet

**Failing forms:** `(one-path-review.s4a/adapter-proof)` returns these losses:

```clojure
{:lost-shown-keys [:seon.eval/shown :seon.eval/value-edn
                   :seon.eval/value-tokens :seon.eval/renderer]
 :lost-cost-keys [:seon.eval/duration-ms :seon.eval/allocated-bytes]
 :receipt {:seon.turn/id "review-s4a", :seon.cluster.eval/ordinal 0,
           :seon.eval/shown "42", :seon.cluster.eval/ns [:seon.ns/name user],
           :seon.sci.eval/ending-ns user}}
```

The probe supplies a rich shown map through the real fold and places duration/allocation in the admission record exactly where §10.0 puts them. `batch/print-into` (`batch.clj:175`) only retains `:seon.batch/shown` and print diagnostics; extra shown members disappear. `evaluation-facts` reads duration/allocation at the evaluation's **top level**, and requires integer duration (`turn.clj:149–158`); the fold measures a double. The cost-loss probe was repeated with a complete kernel record, namespace and print options: both `:seon.sci.eval/evaluation` and `:seon.turn/evaluation-facts-request` validated, and both cost fields were still absent (`adapter-valid.edn`, 2 ms). An attempted use of the error-only `unrun-evaluation` constructor with integer 42 was correctly refused and is excluded. These are producer/consumer mismatches, not reasons to add another printer.

The required handoff is larger than the design's mapping:

| Consumer | Required handoff |
|---|---|
| `evaluation-facts`, `turn.clj:94` | Ordinal/run identity; origin; original value plus error/outcome; output and interruption; namespace **lookup ref** and ending symbol; top-level integer duration/acquisition/allocation; read evidence and basis; print options; whole shown/size/diagnostic map. Gate evidence may disappear only with its retired producer. |
| `settlement-projection`, `turn.clj:209` | Preserve `value-edn`, staged writes and any still-produced report artifact until the existing blob staging owner consumes them. A printed string alone cannot reconstruct them. |
| `evaluation-terminal-data`, `turn.clj:3670` | Cluster, time, agent, run, process, ordinal, trigger and form-problem attribution beside the complete evaluation; disposition and original value survive independently of program-facts refusal. |
| `settle-batch!`, `turn.clj:3910` | Existing close/delivery/plan/fault/blob/submission work plus the fold's program transaction data. Return prepared results and the applied outcome/basis/durability so `resume-turn` can answer and advance correctly. |
| New code-evidence path | Declare the stored schema and retain it through `evaluation-facts`, receipt attribute selection/recording and kept-history pulls; merely associating it on the in-memory evaluation is insufficient. |

The success/failure evaluator currently owns output and interruption (`eval.clj:4315–4400`); `sci.batch/evaluate` returns only the raw value or a thrown wrapper carrying the failure value. A form such as `(do (println "kept") 42)` therefore needs an explicit output producer in this handoff. This was traced statically, not run through an unimplemented S4a adapter.

The proposed **boolean** `stop?` on an already published entry has a second shape contradiction. For `(my.agent/done "done") 42`, the required output is an updated refusal evaluation, printed/bound as that refusal, followed by continued evaluation as the old loop does. Returning true stops with the old successful disposition; false retains it. Calling `unrun-evaluation` inside a predicate does not replace an immutable entry. The existing rule is at `turn.clj:5080` and its replacement/show/bind ordering at `5210–5257`.

**Smallest fix:** declare one complete evaluation adapter and retain the actual shown map in the fold's entry schema. Map cost to its existing top-level keys, retain output/interruption and error causes, and convert namespace symbols to the required ref shape. Make the disposition stage return an updated entry plus the stop decision, before final show/handle binding, with access to the already-read form and whether it is last. Keep a pure boolean `stop?` only if a separate existing outcome stage performs that transformation. Add the producer/consumer field matrix and these conversions to S4a, not a promise to fill them later.

### A3 — High: correct future ownership does not make the saved evidence complete

**Failing form:** `(one-path-review.s4a/async-proof)` holds a future until after the originating form's evidence is frozen, then releases it while another world is active. It performs a real `db/pull` in each world. The result, filtering call counts to the two probe symbols, is:

```clojure
{:frozen {:calls {}, :reads 0}
 :owner-after {:calls {review/late 1}, :reads 1}
 :other-world {:calls {review/nested 1}, :reads 1}}
```

World propagation works when the world's scope explicitly carries `db/*read-evidence-sink*`: calls/reads do not cross. But `batch/calls` and `db/read-evidence` produce immutable snapshots. The late call/read is absent from the saved entry and the counts already added to the batch. Even deferring collection until settlement leaves the same race for a future finishing after settlement. `profile/timed` counts on completion (`profile.clj:143–184`), so an already-entered but unfinished call is also absent. An empty snapshot is not positive proof of no dependency.

**Smallest fix:** declare evidence completion separately from the returned value. Carry both sinks in the form's world/scope on every hop, freeze complete evidence only after owned work actually exits, and treat pending/late evidence as incomplete/UNKNOWN, never cache-current. Preserve the value immediately with the missing terminal event diagnostic; do not hold the reply hostage to an arbitrary host future. State the policy for unjoined work and its ownership explicitly. Prove completion after the form, after the next form, and after attempted settlement; the named joined-future case alone cannot prove this guarantee.

### A4 — High: looking up a symbol's current digest is not evidence of the definition that ran

**Failing form:** `(one-path-review.s4a/digest-proof)` creates two real profiling cells with the **same symbol and different valid digests**, calls both through `with-cell`/`timed`, then performs the proposed end-of-form name lookup:

```clojure
{:values [:old :new], :calls {review/f 2}, :end-lookup {review/f "new"}}
```

This corresponds to holding an old callable while redefining its Var, or invoking both versions within one form. A query against the before value misses the new version; a query against the after value misattributes the old one. A query against the connection head adds another race. Current cells already carry `:seon.profile/digest` (`profile.clj:73–91`), but `with-cell`/`count-into-world!` discard that identity when counting into the world's symbol-only sink (`:110–140`).

The refusal identity ruling is **confirmed**: an armed `seon.profile/cell` input refusal returned `:seon.instrument/fn seon.profile/cell` and caller `"user (NO_SOURCE_FILE:1)"`. Use the former, never the latter. It does not solve the lost executed-definition identity or unknown/unarmed callees.

**Smallest fix:** record the executed cell's symbol **and held definition digest** at the existing instrumentation producer; derive counts separately if callers still need symbol totals. Missing digest is explicit unknown. Preserve the refusing callee from the actual refusal and associate it with its invocation's held program basis; never silently substitute a newer definition. Compare only these reached identities during refresh, not a whole-program identity. A missing/deleted callee is changed/unknown. This uses existing cell information, not another registry.

### A5 — High: settlement's valid state replacement can erase published values

Injected throwing show, derive and settle stages each retained `[42]` with their corresponding diagnostic. That part of the fold passes. The state-returning settlement seam that S4a is about to use does not preserve the invariant:

```clojure
(let [commit {:seon.batch/applied false, :seon.batch/tx-count 0,
              :seon.batch/settled {:seon.batch/entries []}}]
  (seon.batch/evaluate-batch
    (assoc (one-path-review.s4a/request)
           :seon.batch/settle (fn [_ _] commit))))
;; values [], diagnostics []
;; valid-candidate-value? for :seon.batch/commit => true
```

`batch.clj:429` merges settlement's arbitrary `:seon.batch/settled` map over the published batch. A mapper that omits one prepared receipt, or returns no prepared entries after a later-stage refusal, loses evaluated values without a contract error or fault. The open `:seon.batch/settled` schema permits this. This is a controlled stage-boundary counterexample, not a claim that the current normal settlement returns an empty vector.

**Smallest fix:** settlement may return program/working state and per-ordinal diagnostics/fact dispositions; merge those into the held entries without replacing their original value/error or dropping ordinals. Reject malformed updates beside those entries. Strengthen the settlement output contract and keep the fold's preservation invariant at this seam, including the new turn adapter's writer-refusal path.

### A6 — High: the deletion list is not a loadable S4a cut

The proposed deleted names still serve owners outside the replaced loop. These are real source callers, not comments:

| Deleted name | Surviving consumer at the reviewed HEAD |
|---|---|
| `namespace-rows` | `source-rows`, `turn.clj:665`, needed precisely while pre-opened rows stay; `record-evaluated-call`, `:1620` |
| `evaluated-namespaces` | `receipt-settle-batch-tx`, `:1101`; `record-evaluated-call`, `:1620` |
| `analyze-settlement` | Two-argument `receipt-settle-tx`, `:1074` |
| `schema-attribute-change-tx` | `row-tx`, `:1319,1399`; deleting only the batch program-row path leaves these references |
| `restore-evaluations!` | `resume-turn`, `:5480`; direct tests `turn_test.clj:268,278` |
| `gate-function-install` | Quoted resolution in `cluster/turn_test.clj:2638` and `cluster/agent_test.clj:1342`; task symbols in `resources/seon/tasks/seon.sci.eval.edn:44,55` |

The falsifying check is the nonempty current Var-use set for deleted names after excluding only callers the design replaces; the archive clj-kondo evidence is recorded below. For example, deleting `namespace-rows` while retaining `source-rows` makes compiling `(namespace-rows db starting-namespace sources [])` fail before a turn can open. I did not perform that deletion or reload the foreign namespace to manufacture a failure.

**Smallest fix:** list and convert each surviving consumer in S4a, or move that helper's deletion to the step retiring its last caller. Keep the small namespace-upsert owner while pre-opened rows require it. Convert test/quoted/resource references in the same cut. Reprice the estimated −130 only after this manifest is fixed; it is not a measured net change.

### The two open questions and the execution boundary

**Q1: keep pre-opened evaluation rows until S8.** They provide stable run/ordinal identities, handles, interruption accounting and the last ordinal used by settlement. Keeping those facts is compatible with one evaluation/recording fold; keeping a second semantic reader is not. A1 fixes the missing form handoff, and A6 preserves their namespace producer. Do not run unfinished rows after a process restart. On disposition, settle the evaluated prefix and let the existing run-close/interruption owner account for the rest.

**Q2: pass the fold's program facts as data to `settle-batch!`.** Accept this direction. Do not also invoke `turn.batch/settle`, which already writes evaluation/program facts: that would create two settlements and omit or duplicate turn side effects. Extend the existing settlement transaction construction and contract. Preserve its writer-side conflict/refusal checks, pending submission, blobs, close/delivery/plan work, applied basis and durability result. Evidence and program facts must describe the same held execution; do not re-evaluate to prepare settlement. Update `resume-turn`'s post-commit installation logic, which still reads `:seon.program/row` at `:5487–5502`, to consume accepted fold entities/observed Vars before deleting that field. This is an explicit caller conversion, not a second evaluator.

This review starts no test run. The documentation itself needs no behavioral tests. Root's existing focused list for the implementation is `seon.turn-test/a-forms-read-evidence-is-only-what-the-form-read`, `seon.turn-test/batch-settlement-preserves-declaration-order`, `seon.turn-test/a-refused-settlement-keeps-each-evaluated-value`, `seon.turn-test/settlement-refuses-a-divergent-program-change-since-opening`, `seon.turn-test/generated-system-runs-grow-only-after-their-settled-prefix`, `seon.profile-test/a-re-entry-through-the-same-wrapper-is-inside-one-outermost-call`, plus `seon.dev.mcp-bridge-test` for the real transport. Add focused S4a regressions named `held-reader-form`, `settlement-field-parity`, `early-done-replacement`, `late-form-evidence`, `executed-digest-evidence`, and `settlement-preserves-published-entries`; these are required new proof names, not claims of installed tests. Product/SCI future and entry-parity proofs must run through the actual converted entrant centrally. P1's ≤20%/50 ms regression requirement remains; no timing of an unimplemented S4a turn is asserted.

### S4a landing verification

Archive verification pinned HEAD **8a2bc3a15** plus only this document's diff. `clj-kondo --lint <archive>/src <archive>/script <archive>/test --cache false`, with Var-use analysis enabled, returned **0 errors, 2,725 warnings, 16 info**, 505 files, **6,578 ms**. This is an error-free lint result, not zero findings. The six A6 target names have **11 actual Var usages**, saved in `kondo-summary.edn`; quoted `ns-resolve` and resource references were checked separately. The larger lint time is proportional to the requested full source/script/test scan plus analysis, not an ordinary evaluation benchmark.

The installed contracts checker, including its namespace-placement check, read **497 files in 2,270.28 ms** against the archive's packaged projection. It returned **0 src/test findings** and the same **10 foreign script contract failures** recorded in the earlier review: `script/seon/dev/docstring.clj:285,318,334,360` and `script/seon/dev/markdown.clj:1020,1050,1090,1124,1170,1215`. Thus the full contracts gate remains red; this documentation change introduces none of those failures. This full-population compilation is the required gate, not a product latency claim. Raw results are in `contracts.edn`. Final documentation-only evidence additions do not change any checked contract/schema/source input.

Only this document is committed: **0 production/test/resource/reference-code lines changed**. Scratch evidence stays under `tmp/one-path-review/s4a`; the future joined during the probe. Another external default restart ended the review session before explicit namespace cleanup; a fresh MCP session verified the scratch namespace absent (`cleanup.edn`). No review namespace was reloaded into that new JVM. The owned archive was removed after checking; full lint evidence is retained compressed as `kondo.edn.gz`. Foreign boundaries are r12 and its database/test state, the external default restarts and pre-existing health faults, the spec/implementation namespaces, the ten script contract failures, and all concurrent source/resource/dependency changes. No other lane's session or file was resumed, messaged or edited.

**Rev-S4a verdict: REVISE — A1 held reader input; A2 evaluation/disposition adapter; A3 asynchronous evidence completion; A4 executed definition identity; A5 settlement value preservation; A6 loadable caller/deletion manifest.**
