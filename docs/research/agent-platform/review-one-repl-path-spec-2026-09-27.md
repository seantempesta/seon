---
type: review
status: revise
created: 2026-09-27
revised: 2026-09-27
spec: docs/research/agent-platform/one-repl-path-spec-2026-09-27.md
spec-commit: 5205cd9b2
scope: review only, revision 3
---

# Review: one REPL path specification, revision 3

The proposed implementation has one stage-valued fold, per-form speculative validation, an SCI producer observer, and a final recording transaction. The supplied suite reproduces **63/63**, but additional REPL proofs falsify totality, exact attribution, single semantic reading, and final reply correctness. **REVISE** before executing the landing instructions.

The complete `AGENTS.md`, revision 3 specification and proof suite were read, with the REPL, data-oriented Clojure, testing and database-owner guidance. The owner's Q1 A, forgiving input, per-form db-after, storable registered attributes and def/defonce entities remain settled. This review replaces revision 2 in place; the previous review is preserved at `a531e47ab`.

## Evidence and reproducibility

Review target: `5205cd9b2`; source references below are that commit's `tmp/one-path/src`, unless another revision is stated. The six namespaces, schemas, pinned-SCI adapter, fork diff and proof artifacts were read. **The patched SCI observer is not installed in default.** Its rows in `one-path-proof/prove!` read saved scratch results; they are not live observer executions.

The first requested run was interrupted by an external default restart, from PID 71396 to 84717. No restart or reset was requested or performed by this review. The replacement JVM already held the spec lane's `seon.batch`, `seon.sci.*`, `seon.turn.batch` and `one-path-proof` namespaces. I did not redefine, unload or use that lane's mutable proof state. A `git archive 5205cd9b2 tmp/one-path` copy under `tmp/one-path-review/baseline` was loaded with only namespace names and evidence paths changed: `one-path-review.impl.*` and `one-path-review.baseline-proof`. Keyword contracts remained unchanged; the sentinel counterexample accounts for its auto-qualified keyword. The corpus runner was likewise copied into a review-owned namespace. These are ownership accommodations, not implementation fixes.

At MCP `eval_clj`, explicit root `/Users/sean/src/seon`, cluster `default`, mode `jvm`, review session `one-path-review-v3b`:

```clojure
(one-path-review.baseline-proof/prove!)
;; {:pass 63, :of 63, :failing []}
```

The successful run took **105,617.69 ms**. Heap in use was 6,929 MiB before and 4,429 MiB after; old generation after its last GC was 2,452/1,621 MiB. These are shared-JVM observations, not isolated allocation measurements. An earlier completed attempt encountered a transient foreign `Unexpected projection construction` exception and returned 16/63; it is retained separately and is not counted as an implementation failure. The successful rerun followed a disposable probe showing the boundary available again. Runs acquired disposable agent executions and released them in `finally`; no production test request or `bin/test` was started.

| Probe | Reproduced result | What it proves |
|---|---|---|
| One contracted defn | 623, 257, 228, 235, 215 ms | Five complete prototype batches; warm result is 215–257 ms, not the obsolete 40 ms docstring. |
| h36, 22 registrations | 2,450 ms; apply 1,895.40 ms | Every version is applied; schema threading is not yet sub-second. |
| 40 chained definitions, 79 forms | 16,998 ms; apply 13,125.41 ms; settle 1,463.77 ms | The cost remains concentrated in speculative application. The requested suite includes this long probe; this is not ordinary-operation approval. |
| Concurrent test agents | PASS, 673 ms, two distinct branches | Two prototype bodies and their recording can run concurrently. It does not prove concurrent production `clojure.test` assertion reporting. |
| MCP/agent-turn equivalence | PASS, eight examples, 4,388 ms | Equality of two wrappers around the proposed function after transport normalization and timing removal. No actual MCP handler or agent Flow turn was converted. |
| JVM feedback | `{:seon.feedback/changed []}`, 24 ms | Feedback key exists for `(+ 1 2)`. It proves neither JVM definition observation nor the one floor printer. |
| Schema-name/contract proof | 53 forms, 89 contracts; PASS, 13 ms | Malli compiles these forms. This is weaker than the required source-admission gate; see B5. |

Raw review evidence is in `tmp/one-path-review/`: `suite-summary-retry.edn`, the baseline's `proof-results.edn`, `counter-results.edn`, `counter2.edn`, `counter3.edn`, `jvm-floor.edn`, `manifest-check.edn`, and `proposed-contract-findings.edn`. Counterexample definitions are in **`proof.clj`, namespace `one-path-review`**. They are finite MCP probes, not test-runner requests. Their essential forms and outputs are included below so the findings survive independently of disposable files.

## What revision 3 actually closes

| Prior finding | Verified disposition |
|---|---|
| V2-1: producer callback in retry/after watches | The fork diff moves `bindRoot` outside the env swap and before watches. Arming uses the exact Var and suppresses its own notification. Saved inherited, nested-callback, throwing-watch, intern and concurrency rows support these fixes. Full patched-observer integration remains unproven; B2 names a concrete missing connection. |
| V2-2: provenance/captures | Saved origins now distinguish generated roots, and the record helper carries its owning declaration. Live captured-local and record-acquisition proofs pass. Exact matching and expansion-to-entity composition are still false, B2. |
| V2-3: totality | Supplied misreported-span, shadowed namespace, and ordinary injected failures pass. Other lawful values and later failures lose outcomes, B1. |
| V2-4: schema versions | Re-registration and register-then-use pass. Independently, an unresolved replacement is refused and the earlier `:int` schema remains committed. Projection performance remains a separate owner-boundary concern. |
| V2-5: ordered effects | Supplied remove/rebind/remove-ns/read-only proofs pass. Additional remove-ns, recreate, define and read probe commits the recreated definition. |
| V2-6: semantic rereads | Valid tagged prose suffix and repair-before-require pass. A tagged value inside a repaired form is read twice, B3. |
| V2-7: loadable cut | No new schema-name collision; require graph proof passes. Actual admission of the six files refuses, B5. The reference inventory needs resource coverage. |
| V2-8: entrants/recording | Prototype JVM recording, nested-render settlement and concurrent test bodies pass. Production entrant conversion is still an instruction, not installed evidence. The JVM implementation still has a second printer, B6. |
| OQ1 | Ordinary malformed facts are logged and omitted, and the moved-head example commits the surviving facts. Final refusal delivery and feedback are wrong, B4. |

The SCI patch's +107/−32 and four additional test functions are real. The reported 397 tests/1,517 assertions with five failures versus 393/1,485 with the same five failures is saved evidence, not a fresh SCI suite run by this review. No claim of independent patched-SCI installation is made.

## Execution blockers

Severity **P0** means lost evaluated outcomes or inability to pass a required landing gate. **P1** means a required semantic guarantee fails. Each finding includes its smallest producer-level fix; none reopens an owner ruling.

### B1 — P0: a later print failure can erase the evaluated value

**Failing proof:** `(one-path-review/nil-print-failure)`. It supplies a one-form reader, an evaluator returning nil, and a show stage throwing `ex-info`. The result is:

```clojure
{:entries []
 :diagnostics ["fold: Cannot invoke ... Class.getName() ... is null"]}
```

`seon.batch/show-last:193–207` catches the intended print failure, then calls `.getName` on `(class nil)`. The enclosing `guard :fold` restores the batch from **before evaluation**. Thus the documentation saying the value was already published is false at the actual guard boundary.

Two additional proofs expose the same boundary:

```clojure
;; The actual original sentinel value is :seon.batch/none.
;; An observer calls the evaluator, then throws.
(one-path-review/actual-sentinel)
;; one error entry; no :seon.batch/value, no observer diagnostic

(one-path-review/malformed-observation)
;; observer emits 42, evaluator returns 42
;; {:entries [], :diagnostics ["fold: ... Long cannot be cast ... Associative ..."]}
```

The first collides with `evaluate-step:118`'s keyword sentinel; the second fails while enriching observations at line 142, after the evaluator returned. A broken later producer is precisely the totality boundary, not permission to erase the result.

**Smallest fix:** retain the minimal outcome at the evaluator-return boundary independently of the fallible form-step result. Use a unique object sentinel, make the nil fallback total, and guard observation bookkeeping/printing against that retained batch. Preserve the original failure diagnostic, rather than replacing it with fallback failure. The regression must assert both entry presence and `contains? :seon.batch/value`, so nil is distinguishable from absence.

### B2 — P1: the exact-span and generated-definition path is not implemented end to end

**Failing proof:** `(one-path-review/exact-end-mismatch)` supplies an observed authored span `[1 1 1 12]` and a kondo definition of the same name spanning `[1 1 1 30]`. `join-write` returns **`:matched`**. At `seon.turn.batch:145–150`, an empty exact match falls back to start-position and containment candidates. This is a heuristic even when the producer supplied an authoritative complete span.

**Failing proof:** `(one-path-review/generated-with-producer-origin)` evaluates:

```clojure
(defmacro single [] (list 'def 'made 7))
(single)
made
;; value 7; made is not stored; private reason :no-definition-at-origin
```

The proof supplies the saved patched-producer origin for `made`, `{:line 2 :column 1 :end-line 2 :end-column 9 :expanded-by 'single}`, through the observer stage. It does not pretend the fork is installed. The ordinary adapter run fails identically. `seon.sci.observe:15–38` binds only `*write-observer*`; it never binds `*expansion-observer*`. `seon.turn.batch/analyze-form:196–206` calls `runtime-analysis-batch` without expansion evidence. The standalone `q1-single-def-kondo` scratch hook proves a mechanism that these six landing namespaces do not compose.

**Smallest fix:** reject a full authored-span mismatch without fallback; confine the temporary start-only adapter rule to that adapter. Bind and carry the actual expansion evidence into the per-form analyzer's hook, keyed by the exact owning call occurrence; prove stored entities and reacquisition, not only classified events. Do not add macro-name cases.

### B3 — P1: delimiter repair repeats a semantic reader effect

**Failing proof:** `(one-path-review/repaired-tag-read-count)` uses a scratch SCI context and explicit reader:

```clojure
(let [calls (atom 0)
      opts {:readers {'review/tag (fn [x] (swap! calls inc) x)}}]
  (seon.sci.forgive/forms (sci.core/init {}) opts :code "[#review/tag 1")
  @calls)
;; 2, although the accepted result is one [#review/tag 1] form
```

`read-one:89–106` executes the tag reader before encountering the missing bracket. `next-form:230–242` repairs and semantically reads the accepted form again. Neutral structural trials fix look-ahead alias/tag effects, but do not undo the first reader effect. The supplied test covers a valid tagged suffix, not this case.

**Smallest fix:** complete the structural delimiting/repair decision before the one semantic read of that accepted form. Preserve live namespace resolution at the semantic cursor. A malformed semantic tag should return its declared read error, not trigger a second invocation. Keep this in the one input stage; no tag-specific retry cache.

### B4 — P1: settlement can hide failure and report facts that never committed

**Failing proof:** `(one-path-review/writer-refusal-reply)` evaluates 42 with a writer returning `{:seon.error/message "review writer refusal"}`. Both attempts refuse. The batch retains 42 and the refusal, but `reply` returns:

```clojure
{:seon.batch/evaluations [{:seon.batch/ordinal 0 :seon.batch/shown "42" ...}]
 :seon.batch/diagnostics []
 :seon.batch/commit {}}
```

`settle:657–662` returns `:seon.batch/refused`; `reply:743–745` selects only applied/count/storage-error, silently dropping it. No diagnostic reaches `record-faults` for this refusal. A writer or storage failure must remain beside the value and reach the agent.

**Failing proof:** `(one-path-review/moved-head-feedback)` extends the suite's moved-head case. The old basis has `review-base`; the actual head adds `review-dep` calling it. The batch defines `review-new`, attempts to unmap `review-base`, and returns 9. Rebase correctly refuses the deletion and commits the rest, but:

```clojure
{:applied? true :base-stored? true
 :errors ["Program retraction refused."]
 :feedback {:seon.feedback/changed [user/review-new]
            :seon.feedback/removed [user/review-base] ...}}
```

`evaluate-batch:285–300` concludes before settlement; rebase replaces only entries. The feedback is not from the committed facts as specified.

**Smallest fix:** make settlement answer the final accepted working/program state and complete refusal as data. Finalize feedback from that state, including the rebase path, before constructing the recorded/shown reply. Route unrecoverable settlement failures through the existing fault owner and include their typed diagnostic in both entrants' reply. Do not silently select the refusal away.

### B5 — P0: S3 fails the required contracts gate

The suite compiles Malli forms, whereas the landing gate also checks complete contracts on every newly admitted function. The actual installed `seon.contracts-compile-test/check`, against an archive with the proposed six files and schema added, with empty baselines for those new files, returns **six `:incomplete-contract` errors** in 440.83 ms:

| File under `tmp/one-path/src` | Function/line | Refusal |
|---|---|---|
| `seon/sci/arm.clj` | `var-symbol:11` | bare `:any` |
| `seon/sci/batch.clj` | `evaluate:35` | bare `:any` |
| `seon/sci/batch.clj` | `around:68` | bare `:any` |
| `seon/sci/forgive.clj` | `structural-forms:112` | bare `:any` |
| `seon/sci/forgive.clj` | `structured?:177` | no contract |
| `seon/sci/observe.clj` | `observe:15` | bare `:any` |

Reproducing form, with `paths` naming those new archive files:

```clojure
(seon.contracts-compile-test/check
 {:seon.contracts-compile-test/root archive
  :seon.contracts-compile-test/paths paths
  :seon.contracts-compile-test/baselines (zipmap paths (repeat ""))})
```

**Smallest fix:** use the already declared form/Var/batch schemas where applicable, justify genuinely polymorphic pass-through boundaries under the established exemption, and add `structured?`'s contract. Run this exact gate, including the observer, before calling the six files ready to copy into S3. Publicizing private Vars does not repair these findings.

### B6 — P1: the JVM entrant retains a separate, unbounded printer

**Failing proof:** `(one-path-review/jvm-floor)` passes this through `jvm-request` and the common fold:

```clojure
(apply str (repeat 12000 "x"))
;; full value length 12000; shown length 12002
;; feedback {:seon.feedback/changed []}
```

`seon.turn.batch/jvm-request:835–850` uses its own `pr-str` with `*print-length* 20` / `*print-level* 4`, and its own `"ERR "` stringifier. Those limits do not bound strings. The emitted text is approximately 3,000 estimated tokens for this one value, without the declared floor's cut/requery behavior. The SCI show stage uses the existing show owner; the JVM stage bypasses it. The host evaluator is an allowed stage difference; this second printer is not.

**Smallest fix:** supply the same value/error floor stage to JVM mode, with explicit world/profile and host observation data rather than an SCI evaluator dependency. Delete the local printer and error stringifier. Prove a bounded string, a throwing value printer and a declared renderer through both entrant adapters. The existing `jvm-gets-feedback` arithmetic proof is insufficient.

### B7 — P1: the batch drops valid concurrent and print-time observations

**Failing proof:** `(one-path-review/concurrent-observation)` supplies the declared observer stage, starts eight finite futures, waits for all of them, and then returns the evaluator's value. Each future calls `on-write` 1,000 times with a valid namespace event. In 13 ms:

```clojure
{:expected 8000 :observed 2705 :values [42]}
```

`seon.batch/evaluate-step:117,127` uses a volatile vector and `vswap!`, which is not an atomic read-modify-write across threads. Dynamic binding conveyance is not accumulation safety. The fork's two-threads proof gives each thread its own observer; it does not exercise several futures feeding one batch's callback.

**Failing proof:** `(one-path-review/lazy-definition)`:

```clojure
(lazy-seq (def late-root 42) (list late-root))
;; shown (42), live late-root = 42, stored? false, no diagnostic
```

The observer exits when evaluation returns; `form-step` realizes the value in `show-last` afterward. A producer binding therefore cannot see the definition performed during printing. This is distinct from deliberate read-only rendering in a separate render entrant.

**Smallest fix:** serialize/atomically accumulate this call's concurrent producer events, with an explicit completion boundary before draining them. Keep the same observation scope through the value realization owned by this form's print stage, then derive from those effects. An atomic per-call accumulator has a concrete concurrency reason; a context-wide env watch or a lazy-value special case does not repair this owner boundary.

## Instructions, caller inventory and size

Fresh `clj-kondo --cache false` analysis of `src script test` in an archive of `e999fdd1b` corroborates the manifest's surviving Clojure Var references. Normalizing archive filenames and an absent versus nil `:from-var` leaves no new surviving caller missing from the supplied inventory. Four manifest rows are calls inside functions themselves scheduled for deletion (`gate-function-install` and `planned-sources`); they need step ordering, not an additional conversion. The listed 126 entries include namespace requires, not 126 distinct live call sites.

The manifest is **not** the complete source/test/schema/task inventory claimed in §10. Committed resource references remain outside kondo's Var-use analysis, including `resources/seon/tasks/seon.turn.edn:5,19,31,62,77,87` and `resources/seon/tasks/seon.sci.eval.edn:176`. Add these to the exact S4/S5/S8/S9 retirement checklist and coordinate with their owner; the source cut must not leave task `:must-read` symbols naming retired functions. No foreign resource was edited by this review.

S3's additive require structure is materially better than revision 2. Passing a graph-cycle check does not prove fourteen intermediate commits load; B5 supplies a direct gate failure before any caller conversion. S4–S12 still require their actual converted diffs to pass archive lint/load. S14 is explicitly deferred and should not be counted as a completed extension path. S12 explicitly leaves assertion-as-data open: the concurrent proof uses ordinary expression values, not production assertion counters or test member facts. Complete that declared interface before executing S12; it is not supplied by the concurrency timing.

The **≈−900 production lines** is an estimate combining 2,286 measured deletions with 1,642 new namespace lines and estimated conversion ranges. It is no longer the earlier −1,600 claim, and is not a measured net landing diff. No separate verdict blocker is raised for an honestly labeled estimate. Owner decisions Q1–Q5 are unchanged; the listed costs do not include the remaining producer, finalization and gate fixes above.

## Incoming world/projection amendment

At the world snapshot check, HEAD was `9abfbbe93`; the world changes and their proof updates are **still uncommitted**, and the owning spec still presents §12 as options. They were independently copied and hash-pinned under `tmp/one-path-review/amendment`, then loaded only as `one-path-review.world.*`. The source/hash manifest is `source-hashes.json`; the captured `seon/turn/batch.clj` SHA-256 is `c6752c2eba1c642126214e2fa9f17e3a277078aec1337a26d1cf8e23fe63c179`. The original working files still matched the captured hashes at the last check. This is review of that concrete incoming code, not acceptance of a future committed amendment or a claim that §12 has been implemented in `seon.db`.

The supplied `world-members-agree`, `worlds-never-cross`, `world-survives-compute-hop` and `speculative-batch-keeps-committed-projection` probes all pass through MCP. Explicit writer arguments and re-established custody are the right composition; two forks preserve independent definitions. The projection-only direct-from-basis operation measured **34 ms before / 31 ms after** h36. The existing cached path measured **7 / 5 ms** in this run, so this run does **not** reproduce the reported cache-eviction slowdown. Nor does calling `schema/load-projection` directly prove the proposed change in every `db/with` and writer path. Preserve the measured distinction and require the same held basis and validated transaction through the actual changed owner for the §12 landing proof.

**Additional B5 failure in this snapshot:** `(one-path-review/world-contracts)` builds both `agent-world` and `jvm-world` with `{:seon.agent/id "root"}`, as the supplied world probes do, and validates their outputs using `malli.core/validate`/`explain` with the declared `:seon.batch/world`. Both are invalid at `[:seon.turn/id]`, whose value is nil. The constructors at lines 59–70 and 85 always install that optional key, whereas the schema permits absence, not nil. **Smallest fix:** conditionally associate omitted optional ids, or declare the id required at the constructor input and supply it before calling. Validate the actual worlds in the proof, not only agreement among selected members.

The actual new-function admission check on the snapshot reports **eight** incomplete contracts: the original six plus `seon.turn.batch/with-world:105` and `test-body-value:581`. Apply B5's schema/exemption fix to these too. The world/projection addition does not change the failing B1–B4/B6–B7 implementation boundaries.

## Landing verification and boundaries

This review changes only this document; net production/test/resource/reference-code lines are **0**. Its counterexamples and raw results remain in the authorized review-owned `tmp/one-path-review` directory and are not included in the commit.

The landing archive is **HEAD `9abfbbe93` plus only this document**, not the shared working tree. `clj-kondo --lint src script test --cache false` reports **0 errors, 948 warnings, 16 informational findings**, 4,855 ms over 494 files (exit 2 for warnings). The static `seon.contracts-compile-test/check` compiles the archive's packaged schemas and contracts across **486** JVM source/script/test paths: **2,074.92 ms**, the same ten pre-existing `:contract-compile` findings in `script/seon/dev/docstring.clj:285,318,334,360` and `script/seon/dev/markdown.clj:1020,1050,1090,1124,1170,1215`. There are **zero source/test contract findings**. This is not a claim of a green whole-tree contract check. Markdown validation is valid with zero violations; `git diff --check` is clean.

Foreign boundaries are the externally restarted, faulted default JVM (13 open faults at reconnection), the spec lane's loaded prototype namespaces and uncommitted amendment, concurrent test/source/dependency/task-resource changes, and the ten script-only contract findings. None was repaired, resumed, messaged, staged or included in this review. No worktree, default stop/reset, source publication or production test run occurred. Disposable executions were released; all 17 review-owned loaded namespaces were unloaded (zero remain; foreign prototype namespaces verified present), with the spec lane's namespaces left alone.

**Focused tests for root:** this doc-only landing needs no behavioral test request. Before implementation lands, promote these exact review proofs into `seon.batch-proof-test`: `nil-print-failure`, `actual-sentinel`, `malformed-observation`, `exact-end-mismatch`, `generated-with-producer-origin`, `repaired-tag-read-count`, `writer-refusal-reply`, `moved-head-feedback`, `jvm-floor`, `concurrent-observation`, `lazy-definition`, and `world-contracts`. Run the supplied proof regressions there plus `seon.sci.forgive-test`, `seon.sci.eval-test`, `seon.dev.mcp-bridge-test`, `seon.test.one-request-test`, and `seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection` in the one central list. The MCP test must exercise the actual handler and agent reply; the test-agent test must include failing assertions and member facts. Run the SCI fork's `sci.write-observer-test` centrally with the patched observer actually wired into this path. No separate lane test request was started.

## Revision 3 verdict

**REVISE — B1–B7.** Retain every evaluated outcome across all later failures; join exact spans and wire observed expansions/realization effects through derivation; perform repairs before semantic reader effects; deliver settlement failures and derive feedback from the final accepted facts; pass the actual contracts admission gate; remove the JVM entrant's second printer; and retain concurrent and print-time producer events. Each has a failing finite proof and a producer-level fix above.
