---
title: Independent Astra review of landed agent-platform code
type: reference
status: complete
reviewed-through: 4647f1339cd67600efc5b0159ff5eb89d1c6aebf
reviewed-from: 9e0f1cba8^
lane: landed-review-astra
---

# Independent review

The reviewed tree has five high-priority defects: test reporting destroys the original exception, off-context error handles retain no live value, the edit hook can evaluate in the wrong cluster, the hook clips the printer's reply, and automatic proof selection hides read errors. Six further findings concern assignment serialization, unconverted tests, the hook's simulated proof, the stdout profile, an obsolete performance assertion and operator printing. These need correction before the affected slices count as complete.

Scope is `9e0f1cba8^..4647f1339cd67600efc5b0159ff5eb89d1c6aebf` on `refactor/agent-platform`, the HEAD captured when this review began. Source citations below are at that reviewed tree, not the moving checkout. Later landings are outside this review. I did not read `landed-review-opus.md`, contact another lane, change product code, reload default or create a worktree. This is the independent Astra half; it does not substitute for the owner's other review.

Authority: [owner rulings](owner-rulings.md), AGENTS.md, the owning cards and the Clojure, SCI, Malli, Datahike and Konserve source at the reviewed pins. In particular, owner rulings §§1–2 require serial REPL behavior and values surviving later failures; §§4, 6–10 govern tests, live results, proportional cost, printing, dependencies and state. A row marked “no additional defect identified” below is a static review conclusion within this scope, not a claim of fresh installed proof.

## Ranked findings

### 1. High — reporting a test error destroys the agent's exception

**Commit:** `5295011dd`. **Location:** `src/seon/sci/eval.clj:290`, especially lines 295–298.

`agent-do-report` calls `setStackTrace` with an empty array on the original Throwable and every cause. It removes the agent's frames as well as host frames. Anyone retaining that exception subsequently observes the damaged object. The cause traversal has no repeated-object detection; a two-exception cause cycle never reaches `clojure.test/do-report`. This contradicts owner ruling §8: the exception must never be mutated, and the printer receives cause-chain data.

**Reproduced on default:** a locally allocated `ex-info` had 191 frames before this function and zero afterward. The call took 0.780 ms; MCP reported 3 ms, result `result/ed16cf39a46bc`. No cyclic exception was submitted to the live process. Its nontermination follows from the unbounded `iterate`/`take-while` traversal.

**Fix:** preserve the Throwable; translate it into the existing error data at the reporting boundary and pass the report through the value printer. `seon.error.refusal/chain` (`src/seon/error/refusal.clj:392`) already handles repeated causes and suppressed exceptions; compose that owner rather than adding another traversal. Add a regression through an actual SCI test request that retains the exception and asserts unchanged frames and error data. Exercise the cyclic case in the bounded test entrance. Existing task: `seon.sci.eval/a-test-report-erases-the-agents-stack-frames`.

### 2. High — an off-context error returns a handle without retaining its value

**Commit:** `0c97d0bdd` (SR-D). **Location:** `src/seon/error.clj:512`, particularly lines 525–530. **Test gap:** `test/seon/error_result_test.clj:126`.

`prepare-result` always creates a result id and prints elisions using its handle. It retains the actual value only inside `(when ctx ...)`, although its contract explicitly permits a request without a SCI context and its comment names background errors as that case. SR-D also deletes the former full-value storage. Consequently an off-context error can immediately return a handle whose elided value was never retained. This is not ordinary eviction or loss after restart. It contradicts the commit's claim that the full value is cached and the storage ruling in §§2 and 6.

The off-path regression checks shown-text size and the absence of the former blob; unlike the context-backed test, it never follows the result handle. This is a static producer/caller finding, not a live storage mutation performed by this review.

**Fix:** retain every live result in the shared result-cache owner independently of a SCI context, then expose that same handle to whichever agent receives the error. Have the resolver distinguish eviction from a live value. Tighten the producer's result contract to require the retention operation and add an off-context regression that retrieves an elided child. Do not restore a second serialized copy.

### 3. High — the edit hook reads one cluster and evaluates in another

**Commit:** `3c15a3434` (RF-4). **Location:** `bin/seon-hook:1241`, call at line 1295; configuration read at line 1262. **Consumer:** `src/seon/mcp.clj:148`.

The source comparison sends the configured cluster to `branch-state-form`. The subsequent `eval-request` sends only `:code` and `:agent`. MCP then selects the only running cluster, or `default` when several run. With a nondefault configured cluster alongside default, the hook compares that cluster's branch and sends the definitions and tests to default's lane branch. It can also report “no changed forms” from the wrong basis. This is a concrete mismatch between the read and write custody, not a security restriction.

**Fix:** pass the configured cluster through `eval-request` into every MCP request. Declare the request shape so the cluster is required. Prove it with two real test clusters containing different source for the same symbol, then assert the resulting definition and recorded tests on the selected branch. No multi-cluster mutations were attempted on default.

### 4. High — the edit hook clips an already printed reply and can hide test failures

**Commit:** `3c15a3434` (RF-4). **Location:** `bin/seon-hook:1322`; existing clipping mechanism at lines 1361–1381.

The new `entrance-feedback` routes the concatenated definition reply and reaching-test reply through `clip-feedback`. That function uses a second budget, default 300 estimated tokens, and cuts the string with `subs`. A long Var render can consume the entire prefix and remove the later failing-test reply; cutting within an EDN value also destroys the reply's data shape. RF-4 explicitly requires printing the reply text as the value printer made it. Owner ruling §8 prohibits this second clipping step.

**Fix:** carry and emit the complete saved printer replies as units. Put hook status beside them as data. Remove this clipping call rather than increasing its budget. Add a regression whose definition reply exceeds 300 estimated tokens and whose following test reply contains an error; assert that both complete replies survive. This regression belongs with the real entrance test in finding 8.

### 5. High — failed proof reads are treated as no proofs

**Commit:** `09f86c000` (F4a). **Location:** `src/seon/turn.clj:5886`, particularly lines 5892–5897.

`task-proofs` checks an error from `task/agent-task`, but extracts `:seon.task/sym` directly from `db/pull` and `:seon.task/proofs` directly from each `task/task-edges` result. Both reads have error variants. A pull refusal becomes `nil`, then `#{}`; an edges refusal becomes an empty set. `run-proofs` treats an empty set as no work and returns nil. A read failure can therefore suppress the agent's proof step without a diagnostic. The `task/task-edges` union at `src/seon/task.clj:326` makes this error path explicit.

**Fix:** inspect each complete read result before selecting its members and return the original error immediately. Keep actual absence distinct from a refused read. Add regressions using the canonical injected database/read-refusal mechanism, asserting the test-summary error as data through the real request entrance. The current happy-path F4a proof does not cover these unions. This is a static finding; no malformed shared database was manufactured.

### 6. Medium — assignment eligibility is checked outside the writer

**Commit:** `d9789e22e` (S6-06a). **Location:** `src/seon/task.clj:495` through 512; `written` at line 407.

The new no-proofs/all-green decision is computed from a pre-read immutable database. `written` then submits only a plain assignment map; it carries no checked basis or transaction function for that decision. Another writer can remove the last proof, record the final green proof or revise the task between these operations, yet the assignment still commits against the stale decision. Generic schema validation cannot enforce this relationship. The law is “decide at the authority,” including a pre-read's basis.

**Fix:** derive eligibility and revision in the same Datahike transaction function that produces the assignment, or validate the observed relevant basis inside that writer. Existing `tasks-tx` usage nearby shows the library seam. Keep the decision proportional to this task's proof set. Add a concurrent-writer regression using real connections and returned refusal data; do not emulate the writer with Var redefinitions. No timing claim or live race is asserted here.

### 7. Medium — assignment's new requirement leaves reaching context tests invalid

**Commit:** `d9789e22e`, interacting with `4445ab12c` and `88bfd19cb`. **Locations:** `test/seon/context_test.clj:47`, `:63`, `:79`, `:92`.

The new assignment rule correctly refuses tasks with no proofs. These callers create a task and call `assign!` before declaring any proof, discard its result and then expect an assigned task in the current-state block. Thus the new behavior is not converted across its reaching callers in the same slice. The later “pending” expectation also needs reconciliation with automatic proof execution after a redefinition; it must not be preserved by disabling the new production behavior.

**Affected vars, predicted red from source, not run by this review:**

- `seon.context-test/current-state-shows-the-current-task-declaration`
- `seon.context-test/a-red-proof-shows-expected-actual-and-message-as-data`
- `seon.context-test/a-proof-result-before-the-latest-reached-definition-is-pending`

**Fix:** declare the task's genuinely non-green proof through the normal entrance before assignment, assert assignment success, then exercise the current-state behavior. Update the last test to assert the current evidence after the automatic step. These files had foreign in-flight edits; this lane neither changed nor used those edits as reviewed evidence.

### 8. Medium — RF-4's named proof replaces the entire entrance

**Commit:** `3c15a3434`. **Location:** `test/seon/dev/hook_test.clj:83`, especially lines 98–111 and 132–138.

The card requires a live test root, the changed definition on its branch and the resulting Var render. The test instead uses `with-redefs` for configuration, lane lookup, advertisement and `prepl-value!`, records requests in a new atom, returns the literal `"the reply"`, and asserts substrings. It cannot catch finding 3, admission failures, namespace resolution failures, absent recorded test evidence or failure to produce a Var render. It also violates the explicit no-Var-redefinition test rule. This is evidence of string construction, not proof that an edit evaluates on the lane's branch.

**Fix:** run the hook against the canonical live test-root fixture and inspect branch entities, evaluation data and test results. Substitute only the external process/transport effect as an explicit value where needed. Assert the Var render's data. Keep a small pure test for request construction if useful. Several newly added hook helpers also have no Malli contracts; add complete contracts for their request/response boundaries while making this correction, rather than accepting the script as a second untyped entrance.

### 9. Medium — stdout chooses global defaults instead of the viewing agent's profile

**Commit:** `d78473124`; `9c88ae5b8` fixes the database requirement but retains this defect. **Location:** `src/seon/repl.clj:172` through 192.

`shown-output` claims to use the agent's one budget but obtains its profile from `seon.config/defaults` through global delays. Its arguments are just output and evaluation id. A cluster/agent profile change cannot affect this path. The reply's stdout is consequently rendered under a different decision from the rest of that agent's values. The database re-pull in its elision also predates the current requirement to use the live result first.

**Fix:** carry the viewer's already acquired profile and live result reference into this printing operation; let the value printer make the single budget decision. Remove the default lookup. Assert two deliberately different supplied profiles through the entrance, using data where possible and keeping text assertions in printer/transcript tests. Do not introduce another profile resolver.

### 10. Medium — P11 still accepts the retired “20% or 50 ms” threshold

**Commit:** `666c767f7`. **Location:** `test/seon/repl_differential_p11_test.clj:26`, especially line 32.

The production change correctly separates print settings from configuration that keys base-context acquisition. Its regression uses `(max (* 1.2 floor) (+ floor 50))` as the allowed later cost. Owner ruling §7 expressly replaces this rule. It both permits a large relative regression on a small baseline and makes shared scheduling noise a correctness assertion. Passing it does not establish that an unchanged base was reused.

**Fix:** assert the actual invariant with the real request entrance and Caffeine's existing cache statistics: a print-setting change must not cause an unrelated base derivation. Keep paired timings as evidence, reporting every regression and explaining the required work. Remove the arbitrary tolerance; do not replace it with a newly invented target.

### 11. Medium — successful starts add another unbounded diagnostic print

**Commit:** `9d60b0948`. **Location:** `script/seon/operator.clj:1985`, calling `write-diagnostic!` at lines 1928–1938.

The new successful-start branch first writes the entire result with `pr-str`, then emits its summary with `prn`. Moving the full value from terminal output to a log file does not satisfy the one-printer law: the log still builds a second complete printed copy, without the printer's profile, budget or retrieval forms. The helper existed for failures; this commit expands that path to every successful start. The small summary itself is a useful render selection.

**Fix:** keep the result as data, make the summary a render over it, and print both human-visible output and any stored shown text through the value printer with an explicit operator profile. Persist only the canonical stored representation if it must outlive the process. Add a test using a finite large start result that asserts the summary's data and the printer-owned stored text. No live start was invoked to test a static printing violation.

## Runtime and measured evidence

`bin/seon status` and MCP `runtime_status` succeeded. Default was available but **degraded**, running archive `406bd11d396628b7919d4a3200f3bb92e9707c72`, PID 90144, process start `2026-10-08T07:21:50.023Z`. Health reported 14 over-bound and 23 errored recordings and one unowned namespace. These are pre-existing runtime observations, not attributed to individual reviewed commits without a corresponding probe. Later commits through the review HEAD were not installed in that process.

Heap maximum was 4,294,967,296 bytes; observed live heap was approximately 1.36–1.38 GB, central cache approximately 511 MB against its 512 MB budget, and platform thread count 333. These are concurrent-process snapshots, not retained-memory estimates for a particular change. No forced GC, reload, reset, branch mutation or provider run was used.

All probes used MCP `eval_clj`, `agent="root"`, `cluster="default"`, `mode="jvm"`. JVM mode's lack of custody was respected: database reads explicitly acquired `(seon.cluster.boot/connection "default")`. Probe bodies used local finite values. Ordinary MCP recording still occurred. Complete reply envelopes were inspected; the probes below returned values without error diagnostics. Measurements cover the installed owner calls, not a parent/change benchmark of uninstalled code.

| Probe | Required work and result | Wall time | Same-thread allocation |
|---|---|---|---|
| Reader, 100 repeated forms | 100 segments | 2.748 ms | 163,968 bytes |
| Reader, 1,000 repeated forms | 1,000 segments | 7.378 ms | 1,397,056 bytes |
| Reader, 2,000 repeated forms | 2,000 segments | 9.904 ms | 2,792,472 bytes |
| Reverse closure of `seon.sci.forgive/read-state`, run 1 | 164 affected functions, one test | 38.047 ms | 48,845,504 bytes |
| Same closure, run 2 | same result | 28.707 ms | 48,117,920 bytes |
| Same closure, run 3 | same result | 29.030 ms | 48,083,600 bytes |
| Test reach alone, runs 1/2/3 | one test | 1.237 / 1.163 / 1.143 ms | 1,794,728 / 1,794,376 / 1,793,496 bytes |

Reader allocation tracks input size on these samples. The measured reverse closure still derives unused test reach when caller refresh only needs functions, but that work is about 4% of warm time here, not the 85% in `c2000f9bb`'s different workload. Its existing task is `seon.sci.eval/a-redefinition-walks-the-reverse-closure-with-its-test-reach`. Keep the concern, but do not generalize the percentage or claim a demonstrated 10× defect from this probe. Separating function reach from test reach must retain one dependency owner, not create a second graph implementation.

The installed reader also returned the correct segment values for nested discards, metadata after a discard, a discarded reader-conditional form and a character literal. Result `result/e8bf06d1c3c10`, MCP 3 ms. The reader scaling probe was `result/e851fc1f58398`, MCP 27 ms; closure comparison was `result/ed27d8e109feb`, MCP 102 ms. Result handles are ephemeral; the forms and numbers below are the durable evidence.

### Exact probe forms

```clojure
(let [t (ex-info "review throwable" {})
      before (count (.getStackTrace t))
      start (System/nanoTime)
      printed (with-out-str
                (#'seon.sci.eval/agent-do-report
                 {:type :error :message "review" :expected true :actual t}))]
  {:review/before-frames before
   :review/after-frames (count (.getStackTrace t))
   :review/output-length (count printed)
   :review/ms (/ (- (System/nanoTime) start) 1e6)})
;; {:review/before-frames 191, :review/after-frames 0,
;;  :review/output-length 0, :review/ms 0.779833}

(mapv (fn [text]
        {:review/input text
         :review/segments (seon.sci.forgive/segments text :code)})
      ["#_ #_ 1 2 3" "#_ ^:private [1] 2"
       "^#_ :ignored :private [1] 2" "#?(:clj #_ 1 2) 3"
       "#_#_1 2 3" "\\newline 3"])

(let [db (seon.db/db (seon.cluster.boot/connection "default"))
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.getId (Thread/currentThread))
      measure (fn [f]
                (let [b (.getThreadAllocatedBytes bean tid)
                      t (System/nanoTime) value (f)]
                  {:review/ms (/ (- (System/nanoTime) t) 1e6)
                   :review/allocated-bytes (- (.getThreadAllocatedBytes bean tid) b)
                   :review/value value}))]
  (mapv (fn [n]
          (let [text (apply str (repeat n "(+ 1 2)\n"))]
            (assoc (measure #(count (:seon.batch/segments
                                    (seon.sci.forgive/segments text :code))))
                   :review/forms n)))
        [100 1000 2000]))

(let [db (seon.db/db (seon.cluster.boot/connection "default"))
      sym 'seon.sci.forgive/read-state
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.getId (Thread/currentThread))
      measure (fn [f]
                (let [b (.getThreadAllocatedBytes bean tid)
                      t (System/nanoTime) v (f)]
                  {:review/ms (/ (- (System/nanoTime) t) 1e6)
                   :review/allocated-bytes (- (.getThreadAllocatedBytes bean tid) b)
                   :review/affected (count (:seon.fn/affected v))
                   :review/tests (count (if (map? v) (:seon.fn/tests v) v))
                   :review/error (boolean (:seon.error/at v))}))]
  {:review/reverse-closure
   (mapv (fn [_] (measure #(seon.fn/reverse-closure
                           {:seon.db/db db :seon.fn/seeds #{sym}}))) (range 3))
   :review/test-reach-only
   (mapv (fn [_] (measure #(#'seon.fn/call-reach db [sym]))) (range 3))})
```

## Commit-by-commit judgment

Each row covers the changed code and the corresponding claim, error paths, law compliance, test approach and cost shape. “Static” means no new defect identified beyond the numbered findings and stated limitations; it does not certify all tests green. Test-only rows have no production hot-path cost. Cost reductions quoted by commit authors were not silently promoted into measurements by this review. Superseded implementations are named so an already-fixed defect is not assigned again.

| Commit | Judgment |
|---|---|
| `9e0f1cba8` | Static: publication reads selected identities and reuses derivations; reaching tests cover selected reads. Cost follows selection more closely; full publication was not replayed on default. |
| `bb4446294` | Static: canonical membership depends on names, so a names-only key removes irrelevant changes without dropping its input. |
| `131082e0d` | Static: database-free printer unit and ordered reply entries address off-custody printing. Hard print-length/level behavior has printer tests. Does not establish whole-request budget compliance for later stdout path (9). |
| `84a3c5e4d` | Static after later fixes: entrants share the reader stepper and failed reads remain values. The reserved-response-map restriction is incompatible with the REPL law but is deleted by `e21fc4674`; no open duplicate finding. |
| `a4d438dc6` | Static: removes the dependency after its last caller; no new algorithm. |
| `f50ef6059` | Static: deletes the unwired loop, schema and tests rather than retaining a second execution owner. |
| `438328b34` | Static: namespace directory pulls once and derives its columns from that value; cost follows displayed entries. |
| `2bf3e10db` | Static: origin rename carried through schema and callers; no intended behavior or cost change. |
| `97faccb88` | Static: reuses definition projection/arity/storage derivations; tests address changed inputs. Parent/change allocation not rerun here. |
| `d11dbe2b6` | Static: authored namespace declarations remain distinct from analyzer-added references; avoids persisting synthetic source. |
| `7876f594e` | Static: documentation uses supplied SCI metadata/context and reference classification. Later refusal handling is completed in `7a9ceb44a`. |
| `94affe518` | Static: carries line count and joins chunks once; removes repeated accumulated-prefix work. Allocation benchmark not repeated on live publication. |
| `b391c0ef9` | Static: redefinition selects its newly authored contract; regression reaches serial definition/call behavior. |
| `28c141d56` | Static with later `7a9ceb44a`: schema and namespace documentation return data. |
| `e24fda839` | Static: reader position starts at the actual source location; later reader refactoring supersedes mechanism. |
| `4c28e748f` | Static: replacement registry derives affected declarations; held immutable schema inputs are appropriate cache inputs. |
| `ca00cfd7e` | Static: same-form registered schemas use the active arming projection; regression reaches the original write entrance. |
| `5295011dd` | Reject: finding 1, reproduced. Removing frames is not a presentation optimization. |
| `b5ff51e4c` | Static: lazy realization during showing retains evaluation observation custody; does not add a second evaluator. |
| `4455e4917` | Static after `bd63ce073`: writer narrows requested attributes/candidate schemas. The follow-up is necessary to keep owning validation complete. |
| `37d11f6a0` | Static: acquire current program before forking, preserving the agent's current branch basis. |
| `b20762d6b` | Static: declared caller and identity work follows requested reach; no independently measured 10× claim. |
| `bd63ce073` | Static: restores owning validation for declaration-only projections rather than exempting them. |
| `c816e3fde` | Useful differential proof: plain SCI is an independent oracle and Seon is called through its entrance; the oracle is not a second product evaluator. |
| `0667ee448` | Static: malformed contract arming reports a diagnostic and preserves the evaluated function. Valid contract refusal must remain distinct; existing differential coverage addresses that distinction. |
| `d78473124` | Finding 9: uses the printer but acquires the wrong profile. |
| `e21fc4674` | Static: SCI owns parsing/data-reader policy; removes the reserved-map veto. |
| `ded5e35de` | Static: removes the native mutation veto to restore valid SCI behavior. Branch/platform distinction still applies. |
| `f9dc29814` | Static: ending namespace captured in finally, including a throw after in-ns; appropriate error-path regression. |
| `c6ae8875d` | Static test/oracle review: competency uses real task and merge behavior; no paid provider run performed by this review. |
| `c98ade404` | Static after `4c3be3361`: linear forgiveness replaces repeated scanning; live finite segment/scaling probes support the final reader's shape. Earlier discard/repair defects were fixed in-range. |
| `9c88ae5b8` | Static correction: stdout uses database-free printer unit and compact presentation diagnostics; finding 9 remains. |
| `76daa8fd5` | Static: definition writes preserve namespace metadata and update only observed resolver state; MCP regression reaches the failure. |
| `666c767f7` | Production separation is correct; finding 10 rejects the test's arbitrary timing tolerance. |
| `6191eace4` | Static with `48191071f`: one cluster base and SCI forks remove per-agent rebuilds. Overlay cost follows differing definitions and reverse callers; no new retained-heap measurement made. |
| `7569761aa` | Static after `4c3be3361`: repair information reaches the reply as data; final offset mapping addresses same-line attribution. |
| `48191071f` | Static: removes recorder identity from the shared-base key and applies recorder at the fork, fixing the unnecessary second base. |
| `3168005b3` | Static with `8de6239e2`/`c2000f9bb`: preserves agent Var identity so earlier closures see redefinition; real MCP regressions are stronger than inspecting intern tables alone. |
| `78b5c04ac` | Static: no-source-position is admitted as observed origin; eval-created definitions no longer fail the origin schema. |
| `ce2f48b7b` | Measurement test: distinguishes one armed invocation from whole-request totals; those totals must not be blamed on the named callee. |
| `18e658348` | Static: retained compiled facts decide storability rather than rebuilding admission diagnostics; saves unnecessary work without changing write requirements. |
| `8de6239e2` | Static: records committed own rows and updates foreign changes into retained Vars, preserving old callers. Tests include a second writer. |
| `5e7dd5e4a` | Static: explicit test identities avoid an unrelated changed-definition scan and unrelated pending results. |
| `4c3be3361` | Static plus live reader probes: nested discard and repair ownership corrected; repair lookup grows with repairs and segment search rather than repeated whole-source scans. |
| `7a9ceb44a` | Static: preserves schema-doc read refusal and reads SCI namespace table without another evaluated form. |
| `953efe25d` | Static: only differing rows are parsed into replacement projection; finding differing rows still visits its input population, so this is not proof of fully incremental discovery. |
| `c2000f9bb` | Static: inherited callers reinterpreted after a differing Var write; finite multi-agent regression addresses the stale caller. Live reverse-closure measurement above narrows its unused-test-reach cost claim. |
| `c68e8df15` | Static: one declared-edge attribute read per selection frontier; same dependency owner retained. |
| `e27763aa9` | Static: avoids deriving a reuse digest when observed changed reach already prevents reuse. |
| `ad831095f` | Test conversion: aligns cluster tests with current data and namespace rules; no evidence that deleting the old assumptions weakens the named replacement behaviors. |
| `a4c4b7262` | Test conversion: host-bound execution remains compiled and branch declarations enter normally; long boot remains a measured integration limitation, not a sub-second proof. |
| `a20670650` | Test conversion: error body and render assertions follow current printer/data behavior; no new production path. |
| `65a20b84a` | Test conversion: removes retired oracle forms and follows registration response shape; does not prove newly removed concepts still work. |
| `909e49261` | Test conversion: fixtures meet writer digest/namespace requirements; unresolved writes remain explicit rather than silently storable. |
| `16a8f0135` | Static: deletes unused second EDN printer; correct direction under one-printer law. |
| `9fc503097` | Static: one inline-or-stage UTF-8 threshold decision; boundary tests matter because character count is not byte count. |
| `89d678ca3` | Measurement-only test: error storage accounting does not itself prove retrieval or bounded live retention (finding 2). |
| `8bcec45ec` | Static: task declaration revision and existing assignment revisions change together; new assignment eligibility still has finding 6. |
| `aa6131f1a` | Dependency review: pins Datahike commit-aware cross-connection revisions; prerequisite for sharing immutable derivations across branches. No default dependency replacement attempted. |
| `754e20ff6` | Static after `513cc7799`: publication coverage pulls touched program rows, not every touched entity's full tree. |
| `9d60b0948` | Finding 11: concise successful-start selection is useful, but the new call expands the existing unbounded diagnostic-file printer. |
| `513cc7799` | Static: joins component parents in the final database value, avoiding obsolete historical membership. |
| `205ab05c2` | Dependency-only: records Aero source; no claim that config loading has already moved to Aero. |
| `c62a1d6b1` | Static: explicit 4 GiB heap and store-cache dials replace host-dependent sizing; observed default heap matches. Does not complete the separate Datahike byte-bounded-cache work. |
| `65cf8c26d` | Static: program-attribute derivation keyed by equal schema forms rather than unrelated projection identity. |
| `0c97d0bdd` | Reject completeness claim: finding 2. Shown-text/blob decision is centralized, but off-context full-value retention is absent. |
| `4445ab12c` | Static rendering: current task declaration is read live. Its assignment setup tests need conversion under finding 7. |
| `ead65e695` | Static: content-valid reach cache shared across branches using Datahike revisions; no writer stamp or parallel revision owner introduced. |
| `9f43acf5e` | Dependency/configuration: removes MaxRAMPercentage and pins immutable/compressed Konserve implementation; no second heap policy. |
| `b233d838f` | Narrow correctness fix: normalization source keeps fitted data rather than the original bulk text. Existing admission clipping remains debt for its deleting owner; this is not proof of one-printer completion. |
| `e7f120672` | Test/evidence: heap-cap test checks operator arguments; dials census is evidence, not implementation of every proposed dial. |
| `89583e65a` | Static: uses Konserve immutable compressed writes instead of recreating compression/storage ownership. Live store not modified to replay blob tests. |
| `1e7dca3bb` | Dependency-only: source gitlink matches the Konserve dependency. |
| `dddd50f5c` | Static: schema findings carry a registered type; caller/schema rename is consistent within the slice. |
| `4e307ec54` | Test conversion: host-test assertions move to Var-render data, generated source and symbol task names; reported separate defects are not turned into blanket exemptions. |
| `e6d5ef732` | Static operator review: queue and slot dial make check admission explicit. This is operator process admission, not a replacement scheduler for agent Flow graphs; no concurrent check stress run here. |
| `09f86c000` | Finding 5. Serial task-proof execution follows the F4a slice, but its read errors must be preserved. Full reaching-test execution belongs to subsequent F4 work, not a claim proved here. |
| `88bfd19cb` | Static data rendering of proof state; finding 7 invalidates its current assignment fixtures after the later guard. |
| `42740dfe3` | Static: program identity supplies classification and the redundant kind field is removed from readers. |
| `406bd11d3` | Static: calls Malli composite registries and removes Seon's reify; library lookup precedence is retained. |
| `822dc7021` | Metadata-only code delta: attaches the existing normalization-storage defect to its function and test; no separate behavior change. |
| `ad032b3b3` | Static only, not installed at probe time: memoizes predicate-symbol population from unchanged inputs rather than walking every schema again. |
| `dbd488520` | Static: removes disabled Gemini review machinery; no lost active gate identified. |
| `494600ec7` | Static operator review: path claims and explicit repeated --test parsing protect coherent commits; working bin/lane subsequently changed and is a foreign boundary for this lane. |
| `aff834c5a` | Static: branch write-back analyzes the merged program rather than one side; no live merge/write-back performed by this read-only review. |
| `d9789e22e` | Findings 6 and 7. The no-proofs/all-green policy matches the ruling, but writer serialization and reaching callers are incomplete. |
| `3c15a3434` | Findings 3, 4 and 8. The hook's request construction, presentation and proof do not yet meet RF-4. |
| `4647f1339` | Static only: fills a newly required dial from its declared shipped decision and sends config path/cause; live adoption of this later commit was not claimed. |

Executable research files were not silently counted as docs-only. They are measurement apparatus, not installed functions or fresh proof. The following additional commits contain such code without a production change; their judgments apply only to that apparatus.

| Commit | Research-code judgment |
|---|---|
| `b66af420e` | JFR ranking now streams with ijson, avoiding whole-file loading. Main-thread filter limits what the numbers mean. |
| `a7154e3ee` | Cache/publication probes are finite; publication probe changes a source file and belongs on its owned root. New ranker collects all threads despite its inherited “main-thread” docstring; do not compare those totals as if thread populations matched. |
| `d0048b721` | Plain SCI differential oracle is independent; it prints values inside its timed function, so timings include that printing and cannot be called pure evaluation cost. |
| `a4f0f625f` | Historical base-cache probes depend on private APIs and owned-root setup. Useful diagnostic evidence at their revision, not executable proof at current HEAD. |
| `58dbc934a` | Concurrent memory probe measures whole-process allocation/heap, including concurrent work; its unbounded waits and setup atoms make it research apparatus, not a compliant production runner. |
| `aeb692d87` | Message census is static heuristic discovery, not clj-kondo classification or proof of runtime emission. Do not make its text matches an admission rule. |
| `4b4caf142` | Cache misses use Caffeine statistics; heap-after-GC samples are approximate whole-process retention, not exact per-agent ownership. |
| `e9a0defec` | Raw-return sizing/sampling prototype is not a substitute printer and must not be promoted into a second production clipping rule. |
| `ee264dfa9` | Transaction census uses real listener/write-hook seams. Probe's polling, mutable global cells and 200 ms wait are measurement apparatus; its stop window includes the wait, while driver call time is separate. No probe was loaded here and the accompanying Opus prose was not consulted. |
| `1935efbcc` | Reload/recopy experiment uses an owned throwaway archive and host load-file; it is evidence for a design, not installed incremental reload behavior or an allowed default probe. |

Research patches attached to `131082e0d` and `ce2f48b7b` were reviewed as proposed changes in their surrounding slices. `bfc7e04c7` only carries an AGENTS.md prose patch and cards; it is docs-only. Resource task/evidence-only updates and other prose-only commits were skipped for behavioral review.

## Dependency and verification limits

The reviewed pins include SCI `ce0aa53b9b`, Malli `43c6dca8d2`, Datahike `86c3301a`, Konserve `e43347564`, Clojure `3bc2b3e91f` and clj-reload `61c6fa7785`. Relevant inspected seams include SCI's serial `eval-string*` reader/evaluator binding, Malli `simple-registry` and `composite-registry` (`src/malli/registry.cljc:22`, `:55` at that pin), and Datahike's transaction-function/commit-revision ownership. These support composition; they do not excuse Seon maintaining a second evaluator, printer, revision stamp or unvalidated state cell. No new production atom was approved by this review. Historical test/probe atoms do not establish an owner exception.

Foreign boundary: default's installed archive stops at `406bd11d3`; later source was reviewed statically. The shared checkout also contained in-flight edits to `bin/lane`, schema/configuration files, `src/seon/context.clj`, `src/seon/sci/eval.clj`, `src/seon/test.clj`, `src/seon/turn.clj` and associated tests. Review citations came from Git, not those edits. In-flight work did not stop independent review, and none was resumed, messaged, edited or reverted.

This lane changes only this report: source delta 0, test delta 0. No behavior test reaches a Markdown review. The three named context tests above are predicted incompatibilities, not fabricated executed reds. No broad suite, publication/adoption check, browser check or retained-heap benchmark was run for the report. Existing red-test task records in the reviewed commits are not a fresh test run by this reviewer.

Before committing, `clj-kondo --lint src script test --cache false --parallel` completed in 6,519 ms: **0 errors, 1,477 warnings**, process exit 2. The warnings are not represented as a clean warning-free result. This is full-tree lint evidence on the shared checkout; the report introduces no Clojure. The documentation-only branch of `bin/lane commit` applies under AGENTS.md's explicit rule that documentation is not a program gate input. It must not publish this review or start a test JVM merely to claim a proof unrelated to the changed file.
